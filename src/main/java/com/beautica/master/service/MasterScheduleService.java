package com.beautica.master.service;

import com.beautica.common.DateRange;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.dto.EffectiveDaySource;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.ScheduleOverrideResponse;
import com.beautica.master.dto.WeeklyScheduleDayRequest;
import com.beautica.master.dto.WeeklyScheduleRequest;
import com.beautica.master.dto.WeeklyScheduleResponse;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.ScheduleException;
import com.beautica.master.entity.ScheduleExceptionInterval;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeeklySchedule;
import com.beautica.master.entity.WorkingInterval;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.ScheduleExceptionRepository;
import com.beautica.master.repository.WeeklyScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Phase 15.4: owns the business invariants of the master schedule model (weekly templates with a
 * validity window, per-date overrides) and the canonical effective-availability resolver.
 *
 * <p><b>Ownership / IDOR.</b> Every public method resolves the target {@link Master} and calls
 * {@link AuthorizationService#enforceCanManageMasterSchedule} (writes) before touching the schedule —
 * the repository finders are intentionally unscoped, so this service is the only gate. A master can
 * never read or write another master's schedule.
 *
 * <p><b>OQ-1 (always allow).</b> No {@code BookingRepository} dependency: override/day-off writes never
 * query, cancel, or notify bookings. <b>OQ-3 (gaps).</b> An uncovered date resolves to
 * {@link EffectiveDaySource#NO_SCHEDULE} with empty intervals.
 *
 * <p><b>No N+1.</b> {@link #resolveEffectiveRange} bulk-loads overrides and overlapping templates for the
 * whole window in two queries, then folds each date in-memory via {@link DateRange}.
 */
@Service
@RequiredArgsConstructor
public class MasterScheduleService {

    private static final int MAX_INTERVALS_PER_DAY = 6;

    private final WeeklyScheduleRepository weeklyScheduleRepository;
    private final ScheduleExceptionRepository scheduleExceptionRepository;
    private final MasterRepository masterRepository;
    private final ScheduleDateMath dateMath;
    private final AuthorizationService authz;
    private final ScheduleMapper scheduleMapper;
    private final CacheManager cacheManager;

    // ---- Step 2: weekly-template upsert -------------------------------------------------

    /**
     * Creates ({@code scheduleId == null}) or replaces ({@code scheduleId != null}) a master's weekly
     * template with a validity window, atomically replacing its intervals. Rejects past {@code validFrom},
     * far-future {@code validTo}, intra-day interval overlap, and window overlap with the master's other
     * schedules.
     */
    @Transactional
    public WeeklyScheduleResponse upsertWeeklySchedule(
            UUID actorId, UUID masterId, UUID scheduleId, WeeklyScheduleRequest request) {

        Master master = loadActiveMaster(masterId);
        authz.enforceCanManageMasterSchedule(actorId, master);

        LocalDate validFrom = request.validFrom();
        LocalDate effectiveTo = request.validTo() != null ? request.validTo() : dateMath.cap();
        dateMath.assertWithinBounds(validFrom, effectiveTo);

        List<WeeklyScheduleDayRequest> days = request.days() != null ? request.days() : List.of();
        days.forEach(this::validateDayIntervals);
        assertNoWindowOverlap(masterId, scheduleId, new DateRange(validFrom, request.validTo()));

        WeeklySchedule schedule = resolveScheduleForUpsert(scheduleId, master);
        schedule.setValidFrom(validFrom);
        schedule.setValidTo(request.validTo());
        replaceIntervals(schedule, days);

        WeeklySchedule saved = weeklyScheduleRepository.save(schedule);
        evictSlotsAfterCommit(masterId);
        return scheduleMapper.toWeeklyScheduleResponse(saved);
    }

    // ---- Step 3: per-date override upsert -----------------------------------------------

    /**
     * Upserts a single per-date override (DAY_OFF or CUSTOM_HOURS). OQ-1: always allowed — never blocked
     * by, nor mutating, existing bookings. Replaces the override's intervals atomically.
     */
    @Transactional
    public ScheduleOverrideResponse upsertOverride(
            UUID actorId, UUID masterId, ScheduleOverrideRequest request) {

        Master master = loadActiveMaster(masterId);
        authz.enforceCanManageMasterSchedule(actorId, master);
        assertEditable(request.date());
        validateOverrideConsistency(request);

        ScheduleException override = scheduleExceptionRepository
                .findByMasterIdAndDateWithIntervals(masterId, request.date())
                .orElseGet(() -> ScheduleException.builder().master(master).date(request.date()).build());

        override.setKind(request.kind());
        override.setReason(request.reason());
        override.setNote(request.note());
        replaceOverrideIntervals(override, request.kind() == ScheduleExceptionKind.CUSTOM_HOURS
                ? request.intervals() : List.of());

        ScheduleException saved = scheduleExceptionRepository.save(override);
        evictSlotsAfterCommit(masterId);
        return scheduleMapper.toOverrideResponse(saved);
    }

    // ---- Step 2b: weekly-template delete ------------------------------------------------

    /**
     * Deletes a master's weekly template by id. The schedule must belong to {@code masterId}
     * (cross-master deletes surface as 404 to avoid an existence oracle). orphanRemoval cascades
     * the working_intervals. Slots are evicted after commit.
     */
    @Transactional
    public void deleteWeeklySchedule(UUID actorId, UUID masterId, UUID scheduleId) {
        Master master = loadActiveMaster(masterId);
        authz.enforceCanManageMasterSchedule(actorId, master);

        WeeklySchedule schedule = weeklyScheduleRepository.findByIdWithIntervals(scheduleId)
                .orElseThrow(() -> new NotFoundException("Schedule not found"));
        if (!schedule.getMaster().getId().equals(master.getId())) {
            // Cross-master delete attempt — surface as not-found to avoid an existence oracle.
            throw new NotFoundException("Schedule not found");
        }
        weeklyScheduleRepository.delete(schedule);
        evictSlotsAfterCommit(masterId);
    }

    // ---- Step 1/2 reads: list weekly templates & overrides ------------------------------

    /**
     * Lists a master's weekly templates (ordered by {@code validFrom}) with intervals projected.
     * Read authorization is enforced by the controller's {@code @authz.canReadMasterSchedule}
     * SpEL gate (Phase 15.5 / OQ-2), which performs the single ownership DB lookup; this method
     * only loads and maps. Bounded by the fixed per-master set of validity windows.
     */
    @Transactional(readOnly = true)
    public List<WeeklyScheduleResponse> listWeeklySchedules(UUID masterId) {
        return weeklyScheduleRepository.findByMasterIdOrderByValidFromAscWithIntervals(masterId).stream()
                .map(scheduleMapper::toWeeklyScheduleResponse)
                .toList();
    }

    /**
     * Lists a master's per-date overrides within {@code [from, to]} inclusive (≤366d window enforced
     * here so the controller cannot request an unbounded scan). Read authorization is enforced by the
     * controller's {@code @authz.canReadMasterSchedule} SpEL gate.
     */
    @Transactional(readOnly = true)
    public List<ScheduleOverrideResponse> listOverrides(UUID masterId, LocalDate from, LocalDate to) {
        dateMath.assertExpandable(from, to);
        return scheduleExceptionRepository
                .findByMasterIdAndDateBetweenWithIntervals(masterId, from, to).stream()
                .sorted(Comparator.comparing(ScheduleException::getDate))
                .map(scheduleMapper::toOverrideResponse)
                .toList();
    }

    // ---- Step 4: clear override ---------------------------------------------------------

    /** Deletes the override for {@code (master, date)} if present; the date reverts to template/gap. */
    @Transactional
    public void clearOverride(UUID actorId, UUID masterId, LocalDate date) {
        Master master = loadActiveMaster(masterId);
        authz.enforceCanManageMasterSchedule(actorId, master);
        assertEditable(date);

        scheduleExceptionRepository.findByMasterIdAndDate(masterId, date)
                .ifPresent(scheduleExceptionRepository::delete);
        evictSlotsAfterCommit(masterId);
    }

    // ---- Step 5: single-date resolver ---------------------------------------------------

    /**
     * Canonical effective-availability rule for one date (Kyiv civil time). Pure: no booking subtraction,
     * no clock dependency beyond the caller. Override beats template beats gap.
     */
    @Transactional(readOnly = true)
    public EffectiveDayResponse resolveEffectiveDay(UUID masterId, LocalDate date) {
        var override = scheduleExceptionRepository.findByMasterIdAndDateWithIntervals(masterId, date);
        if (override.isPresent()) {
            return resolveFromOverride(date, override.get());
        }
        List<WeeklySchedule> covering =
                weeklyScheduleRepository.findOverlappingRangeWithIntervals(masterId, date, date);
        return resolveFromTemplate(date, covering.isEmpty() ? null : covering.get(0));
    }

    // ---- Step 6: range resolver (O(1) queries, in-memory fold) --------------------------

    /**
     * Effective availability for every date in {@code [from, to]} inclusive. Bulk-loads overrides and
     * overlapping templates in two queries, then folds each date in-memory — no per-date query (§E).
     */
    @Transactional(readOnly = true)
    public List<EffectiveDayResponse> resolveEffectiveRange(UUID masterId, LocalDate from, LocalDate to) {
        // Read path: past dates are included (the calendar paints greyed history — Phase 15.5 Step 3),
        // so use the read-window guard rather than assertWithinBounds (which forbids a past start).
        dateMath.assertExpandable(from, to);
        List<LocalDate> dates = dateMath.expandInclusive(from, to);

        Map<LocalDate, ScheduleException> overridesByDate = scheduleExceptionRepository
                .findByMasterIdAndDateBetweenWithIntervals(masterId, from, to).stream()
                .collect(Collectors.toMap(ScheduleException::getDate, e -> e, (a, b) -> a));
        List<WeeklySchedule> windows =
                weeklyScheduleRepository.findOverlappingRangeWithIntervals(masterId, from, to);

        List<EffectiveDayResponse> result = new ArrayList<>(dates.size());
        for (LocalDate date : dates) {
            ScheduleException override = overridesByDate.get(date);
            if (override != null) {
                result.add(resolveFromOverride(date, override));
            } else {
                result.add(resolveFromTemplate(date, firstCovering(windows, date)));
            }
        }
        return result;
    }

    // ---- resolver helpers ---------------------------------------------------------------

    private EffectiveDayResponse resolveFromOverride(LocalDate date, ScheduleException override) {
        if (scheduleMapper.isDayOff(override)) {
            return scheduleMapper.toEffectiveDay(
                    date, EffectiveDaySource.OVERRIDE_DAY_OFF, List.of(), override.getReason());
        }
        return scheduleMapper.toEffectiveDay(date, EffectiveDaySource.OVERRIDE_CUSTOM,
                scheduleMapper.toIntervalDtos(override.getIntervals()), null);
    }

    private EffectiveDayResponse resolveFromTemplate(LocalDate date, WeeklySchedule covering) {
        if (covering == null) {
            return scheduleMapper.toEffectiveDay(date, EffectiveDaySource.NO_SCHEDULE, List.of(), null);
        }
        List<WorkIntervalDto> intervals =
                scheduleMapper.toIntervalDtosForDay(covering, dateMath.isoDow(date));
        return scheduleMapper.toEffectiveDay(date, EffectiveDaySource.TEMPLATE, intervals, null);
    }

    private WeeklySchedule firstCovering(List<WeeklySchedule> windows, LocalDate date) {
        return windows.stream()
                .filter(ws -> new DateRange(ws.getValidFrom(), ws.getValidTo()).contains(date))
                .findFirst()
                .orElse(null);
    }

    // ---- write helpers ------------------------------------------------------------------

    private Master loadActiveMaster(UUID masterId) {
        Master master = masterRepository.findByIdWithSalonAndOwner(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));
        if (!master.isActive()) {
            throw new BusinessException("Master is inactive");
        }
        return master;
    }

    private void assertEditable(LocalDate date) {
        if (!dateMath.isEditable(date)) {
            throw new BusinessException("Cannot edit a schedule for a past date");
        }
    }

    private WeeklySchedule resolveScheduleForUpsert(UUID scheduleId, Master master) {
        if (scheduleId == null) {
            return WeeklySchedule.builder().master(master).intervals(new ArrayList<>()).build();
        }
        WeeklySchedule schedule = weeklyScheduleRepository.findByIdWithIntervals(scheduleId)
                .orElseThrow(() -> new NotFoundException("Schedule not found"));
        if (!schedule.getMaster().getId().equals(master.getId())) {
            // Cross-master edit attempt — surface as not-found to avoid an existence oracle.
            throw new NotFoundException("Schedule not found");
        }
        return schedule;
    }

    private void replaceIntervals(WeeklySchedule schedule, List<WeeklyScheduleDayRequest> days) {
        schedule.getIntervals().clear(); // orphanRemoval deletes the old rows on flush
        for (WeeklyScheduleDayRequest day : days) {
            if (day.intervals() == null) {
                continue;
            }
            for (WorkIntervalDto dto : day.intervals()) {
                schedule.getIntervals().add(WorkingInterval.builder()
                        .schedule(schedule)
                        .dayOfWeek(day.dayOfWeek())
                        .startTime(zeroSeconds(dto.startTime()))
                        .endTime(zeroSeconds(dto.endTime()))
                        .build());
            }
        }
    }

    private void replaceOverrideIntervals(ScheduleException override, List<WorkIntervalDto> intervals) {
        override.getIntervals().clear(); // orphanRemoval deletes the old rows on flush
        for (WorkIntervalDto dto : intervals) {
            override.getIntervals().add(ScheduleExceptionInterval.builder()
                    .exception(override)
                    .startTime(zeroSeconds(dto.startTime()))
                    .endTime(zeroSeconds(dto.endTime()))
                    .build());
        }
    }

    // ---- validation ---------------------------------------------------------------------

    /** Per-day: ≤ max count, individually ordered, pairwise non-overlapping (sorted by start). */
    private void validateDayIntervals(WeeklyScheduleDayRequest day) {
        assertIntervalsNonOverlapping(day.intervals());
    }

    private void validateOverrideConsistency(ScheduleOverrideRequest request) {
        boolean hasIntervals = request.intervals() != null && !request.intervals().isEmpty();
        boolean ok = switch (request.kind()) {
            case DAY_OFF -> request.reason() != null && !hasIntervals;
            case CUSTOM_HOURS -> request.reason() == null && hasIntervals;
        };
        if (!ok) {
            throw new BusinessException(
                    "DAY_OFF requires a reason and no intervals; CUSTOM_HOURS requires intervals and no reason");
        }
        if (request.kind() == ScheduleExceptionKind.CUSTOM_HOURS) {
            assertIntervalsNonOverlapping(request.intervals());
        }
    }

    private void assertIntervalsNonOverlapping(List<WorkIntervalDto> intervals) {
        if (intervals == null || intervals.isEmpty()) {
            return;
        }
        if (intervals.size() > MAX_INTERVALS_PER_DAY) {
            throw new BusinessException("A day may have at most " + MAX_INTERVALS_PER_DAY + " intervals");
        }
        List<WorkIntervalDto> sorted = intervals.stream()
                .sorted(Comparator.comparing(WorkIntervalDto::startTime))
                .toList();
        for (WorkIntervalDto dto : sorted) {
            if (!dto.endTime().isAfter(dto.startTime())) {
                throw new BusinessException("Interval end must be after its start");
            }
        }
        for (int i = 1; i < sorted.size(); i++) {
            // sorted by start, so an overlap exists iff the previous end is after this start
            if (sorted.get(i - 1).endTime().isAfter(sorted.get(i).startTime())) {
                throw new BusinessException("Intervals within a day must not overlap");
            }
        }
    }

    /**
     * Window overlap against the master's other schedules ({@code DateRange.overlaps}, null to = +∞).
     *
     * <p><b>MEDIUM-4 (TOCTOU).</b> Reads the master's existing schedules under a
     * {@code PESSIMISTIC_WRITE} row lock so two concurrent overlapping-window upserts for the same master
     * are serialized: the second transaction blocks on the lock until the first commits, then re-reads and
     * sees the freshly-inserted window — closing the check-then-insert race that previously let both commit.
     */
    private void assertNoWindowOverlap(UUID masterId, UUID editingScheduleId, DateRange candidate) {
        for (WeeklySchedule existing : weeklyScheduleRepository.findByMasterIdForUpdate(masterId)) {
            if (editingScheduleId != null && existing.getId().equals(editingScheduleId)) {
                continue; // the schedule being edited never conflicts with itself
            }
            DateRange existingRange = new DateRange(existing.getValidFrom(), existing.getValidTo());
            if (candidate.overlaps(existingRange)) {
                throw new BusinessException("Schedule window overlaps an existing window starting "
                        + existing.getValidFrom());
            }
        }
    }

    private LocalTime zeroSeconds(LocalTime time) {
        return time.withSecond(0).withNano(0);
    }

    // ---- cache eviction (afterCommit, per-master prefix) --------------------------------

    /**
     * Evicts the affected master's {@code available-slots} entries <b>after commit</b> so a parallel
     * reader cannot repopulate stale availability mid-write (§F). A schedule/override change can affect
     * any service's slots for the master, so we evict by master prefix (the cache key's first element is
     * the masterId) rather than per {@code (date, masterServiceId)} — bounded to one master, not blanket.
     */
    private void evictSlotsAfterCommit(UUID masterId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                doEvictAvailableSlotsByMaster(masterId);
            }
        });
    }

    private void doEvictAvailableSlotsByMaster(UUID masterId) {
        Cache springCache = cacheManager.getCache("available-slots");
        if (springCache == null) {
            return;
        }
        Object nativeCache = springCache.getNativeCache();
        if (nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache) {
            String masterIdPrefix = "[" + masterId + ",";
            caffeineCache.asMap().keySet().removeIf(k ->
                    k instanceof org.springframework.cache.interceptor.SimpleKey
                            && k.toString().contains(masterIdPrefix));
        } else {
            springCache.clear();
        }
    }
}
