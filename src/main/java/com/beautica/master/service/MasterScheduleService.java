package com.beautica.master.service;

import com.beautica.common.DateRange;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.dto.EffectiveDaySource;
import com.beautica.master.dto.MasterWorkingDayResponse;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.ScheduleOverrideResponse;
import com.beautica.master.dto.WeeklyScheduleDayRequest;
import com.beautica.master.dto.WeeklyScheduleRequest;
import com.beautica.master.dto.WeeklyScheduleResponse;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.DiscreteTime;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.OverrideDiscreteTime;
import com.beautica.master.entity.ScheduleException;
import com.beautica.master.entity.ScheduleExceptionInterval;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;
import com.beautica.master.entity.WeeklySchedule;
import com.beautica.master.entity.WorkingInterval;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.ScheduleExceptionRepository;
import com.beautica.master.repository.WeeklyScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
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
    private static final int MAX_DISCRETE_TIMES_PER_DAY = 24;

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
        days.forEach(this::validateDay);
        assertNoWindowOverlap(masterId, scheduleId, new DateRange(validFrom, request.validTo()));

        WeeklySchedule schedule = resolveScheduleForUpsert(scheduleId, master);
        schedule.setValidFrom(validFrom);
        schedule.setValidTo(request.validTo());
        replaceDayCollections(schedule, days);

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
        // Phase 15.9: route a CUSTOM_HOURS override by its mode. Every upsert is a full replace, so both
        // child collections are cleared first; an INTERVAL override contributes no discrete times and an
        // EXPLICIT_TIMES override contributes no intervals. A DAY_OFF clears both.
        boolean explicitTimes = request.kind() == ScheduleExceptionKind.CUSTOM_HOURS
                && request.effectiveMode() == WeekdayMode.EXPLICIT_TIMES;
        replaceOverrideIntervals(override, request.kind() == ScheduleExceptionKind.CUSTOM_HOURS && !explicitTimes
                ? request.intervals() : List.of());
        replaceOverrideDiscreteTimes(override, explicitTimes ? request.times() : List.of());

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

    // ---- Step 7 (Phase 15.11): CLIENT-safe boolean working-day resolver -----------------

    /**
     * CLIENT-facing calendar day-gating: for every date in {@code [from, to]} inclusive, whether the
     * master is working — boolean only, no hours/intervals/times. Reuses {@link #resolveEffectiveRange}
     * verbatim (same override/template/gap precedence, same {@code assertExpandable} range-cap guard —
     * not duplicated here) and reduces each {@link EffectiveDayResponse} via
     * {@link EffectiveDayResponse#isWorkingDay()} before it leaves the service layer, so no schedule
     * detail the {@code CLIENT} role is blocked from reading (see
     * {@link AuthorizationService#canReadMasterSchedule}) ever reaches the controller.
     *
     * <p><b>Caching (perf follow-up).</b> Pure function of its 3 params, mirroring
     * {@code SlotCalculationService#getAvailableSlots}: cached in {@code master-working-days}
     * (60 sec TTL, {@code sync=true} to collapse a thundering herd on a popular master when the entry
     * expires). Evicted by master prefix from {@link #evictSlotsAfterCommit} alongside
     * {@code available-slots} on every schedule write.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "master-working-days", key = "{#masterId, #from, #to}", sync = true)
    public List<MasterWorkingDayResponse> getClientWorkingDays(UUID masterId, LocalDate from, LocalDate to) {
        return resolveEffectiveRange(masterId, from, to).stream()
                .map(day -> new MasterWorkingDayResponse(day.date(), day.isWorkingDay()))
                .toList();
    }

    // ---- resolver helpers ---------------------------------------------------------------

    private EffectiveDayResponse resolveFromOverride(LocalDate date, ScheduleException override) {
        if (scheduleMapper.isDayOff(override)) {
            return scheduleMapper.toEffectiveDay(
                    date, EffectiveDaySource.OVERRIDE_DAY_OFF, List.of());
        }
        // Phase 15.9: an EXPLICIT_TIMES custom-hours override (≥1 discrete-time row) projects its discrete
        // times AND a derived window [min..max] as the single interval, mirroring the EXPLICIT_TIMES
        // template day. Override precedence is preserved — this branch is reached only when an override
        // covers the date (template never consulted).
        List<LocalTime> times = scheduleMapper.toOverrideDiscreteTimes(override);
        if (!times.isEmpty()) {
            return scheduleMapper.toEffectiveDay(date, EffectiveDaySource.OVERRIDE_CUSTOM,
                    scheduleMapper.toDerivedWindow(times), times);
        }
        return scheduleMapper.toEffectiveDay(date, EffectiveDaySource.OVERRIDE_CUSTOM,
                scheduleMapper.toIntervalDtos(override.getIntervals()));
    }

    private EffectiveDayResponse resolveFromTemplate(LocalDate date, WeeklySchedule covering) {
        if (covering == null) {
            return scheduleMapper.toEffectiveDay(date, EffectiveDaySource.NO_SCHEDULE, List.of());
        }
        int isoDow = dateMath.isoDow(date);
        // Phase 15.8: an EXPLICIT_TIMES day (≥1 discrete-time row) projects its discrete times AND a
        // derived window [min..max] as the single interval, so window-only consumers keep working.
        List<LocalTime> times = scheduleMapper.toDiscreteTimesForDay(covering, isoDow);
        if (!times.isEmpty()) {
            return scheduleMapper.toEffectiveDay(
                    date, EffectiveDaySource.TEMPLATE, scheduleMapper.toDerivedWindow(times), times);
        }
        List<WorkIntervalDto> intervals = scheduleMapper.toIntervalDtosForDay(covering, isoDow);
        return scheduleMapper.toEffectiveDay(date, EffectiveDaySource.TEMPLATE, intervals);
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

    /**
     * Phase 15.8: atomically rebuilds <em>both</em> day collections from the request, routing each day by
     * its mode. Because every upsert is a full replace, a day that flips mode (e.g. INTERVAL →
     * EXPLICIT_TIMES) naturally clears the opposite collection: an EXPLICIT_TIMES day contributes no
     * {@link WorkingInterval} and an INTERVAL day contributes no {@link DiscreteTime}, and both collections
     * are cleared before re-insert.
     */
    private void replaceDayCollections(WeeklySchedule schedule, List<WeeklyScheduleDayRequest> days) {
        schedule.getIntervals().clear();      // orphanRemoval queues DELETEs for the old interval rows
        schedule.getDiscreteTimes().clear();  // orphanRemoval queues DELETEs for the old discrete-time rows
        // Force the orphan DELETEs to the DB before re-inserting. With hibernate.order_inserts=true the
        // ActionQueue runs all INSERTs before all DELETEs, so a re-sent row whose unique key matches a
        // surviving old row would collide (23505: uq_working_intervals_no_dup /
        // uq_working_interval_times_no_dup). Flushing here makes it delete-before-insert.
        weeklyScheduleRepository.flush();

        for (WeeklyScheduleDayRequest day : days) {
            switch (day.effectiveMode()) {
                case INTERVAL -> addIntervals(schedule, day);
                case EXPLICIT_TIMES -> addDiscreteTimes(schedule, day);
            }
        }
    }

    private void addIntervals(WeeklySchedule schedule, WeeklyScheduleDayRequest day) {
        if (day.intervals() == null) {
            return;
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

    private void addDiscreteTimes(WeeklySchedule schedule, WeeklyScheduleDayRequest day) {
        for (LocalTime time : normalizeDayTimes(day.times())) {
            schedule.getDiscreteTimes().add(DiscreteTime.builder()
                    .schedule(schedule)
                    .dayOfWeek(day.dayOfWeek())
                    .slotTime(time)
                    .build());
        }
    }

    private void replaceOverrideIntervals(ScheduleException override, List<WorkIntervalDto> intervals) {
        override.getIntervals().clear(); // orphanRemoval queues DELETEs for the old rows
        // Force the orphan DELETEs to the DB before re-inserting. With hibernate.order_inserts=true
        // the ActionQueue runs all INSERTs before all DELETEs, so a re-sent interval whose unique key
        // matches a surviving old row would collide (23505). Flushing here makes it delete-before-insert.
        scheduleExceptionRepository.flush();
        for (WorkIntervalDto dto : intervals) {
            override.getIntervals().add(ScheduleExceptionInterval.builder()
                    .exception(override)
                    .startTime(zeroSeconds(dto.startTime()))
                    .endTime(zeroSeconds(dto.endTime()))
                    .build());
        }
    }

    /**
     * Phase 15.9: atomically replaces an override's discrete-time rows. Mirrors
     * {@link #replaceOverrideIntervals}: clears the collection (orphanRemoval queues DELETEs) and flushes
     * before re-insert so a re-sent time whose unique key matches a surviving old row cannot collide
     * (23505: {@code uq_schedule_exception_times_no_dup}) under {@code hibernate.order_inserts=true}.
     * Times are seconds-zeroed, de-duplicated and ascending (shared {@link #normalizeDayTimes}).
     */
    private void replaceOverrideDiscreteTimes(ScheduleException override, List<LocalTime> times) {
        override.getDiscreteTimes().clear(); // orphanRemoval queues DELETEs for the old rows
        scheduleExceptionRepository.flush();
        for (LocalTime time : normalizeDayTimes(times)) {
            override.getDiscreteTimes().add(OverrideDiscreteTime.builder()
                    .exception(override)
                    .slotTime(time)
                    .build());
        }
    }

    // ---- validation ---------------------------------------------------------------------

    /**
     * Phase 15.8: validates one weekday by its mode. INTERVAL → interval non-overlap rules;
     * EXPLICIT_TIMES → discrete-time rules. Mode exclusivity (no intervals AND times on the same day) is a
     * DTO {@code @AssertTrue} invariant — here we trust {@link WeeklyScheduleDayRequest#effectiveMode()}.
     */
    private void validateDay(WeeklyScheduleDayRequest day) {
        switch (day.effectiveMode()) {
            case INTERVAL -> assertIntervalsNonOverlapping(day.intervals());
            case EXPLICIT_TIMES -> validateDayTimes(day.times());
        }
    }

    /**
     * Phase 15.8: an EXPLICIT_TIMES day must carry a non-empty, in-bounds list of discrete times within the
     * {@code ≤ MAX_DISCRETE_TIMES_PER_DAY} cap. De-duplication and ascending sort are applied on persist
     * (see {@link #normalizeDayTimes}); here we reject the empty/oversized cases that {@code @AssertTrue}
     * does not (a malformed payload that reached the service is defended in depth).
     */
    private void validateDayTimes(List<LocalTime> times) {
        if (times == null || times.isEmpty()) {
            throw new BusinessException("An EXPLICIT_TIMES day must have at least one time");
        }
        if (normalizeDayTimes(times).size() > MAX_DISCRETE_TIMES_PER_DAY) {
            throw new BusinessException(
                    "A day may have at most " + MAX_DISCRETE_TIMES_PER_DAY + " discrete times");
        }
    }

    /** Seconds-zeroed, de-duplicated, ascending discrete times for one day. */
    private List<LocalTime> normalizeDayTimes(List<LocalTime> times) {
        if (times == null) {
            return List.of();
        }
        return times.stream()
                .map(this::zeroSeconds)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Phase 15.9: defence-in-depth mirror of the DTO {@code @AssertTrue} (a malformed payload that reached
     * the service is still rejected). A {@code DAY_OFF} carries neither intervals nor times; a
     * {@code CUSTOM_HOURS} override carries either intervals (INTERVAL) or a non-empty times list
     * (EXPLICIT_TIMES), never both. Mode-specific content rules reuse the shared 15.8 helpers.
     */
    private void validateOverrideConsistency(ScheduleOverrideRequest request) {
        boolean hasIntervals = request.intervals() != null && !request.intervals().isEmpty();
        boolean hasTimes = request.times() != null && !request.times().isEmpty();
        boolean ok = switch (request.kind()) {
            case DAY_OFF -> !hasIntervals && !hasTimes;
            case CUSTOM_HOURS -> switch (request.effectiveMode()) {
                case INTERVAL -> hasIntervals && !hasTimes;
                case EXPLICIT_TIMES -> hasTimes && !hasIntervals;
            };
        };
        if (!ok) {
            throw new BusinessException(
                    "DAY_OFF carries no intervals or times; CUSTOM_HOURS carries either intervals "
                            + "(INTERVAL) or a non-empty times list (EXPLICIT_TIMES), never both");
        }
        if (request.kind() == ScheduleExceptionKind.CUSTOM_HOURS) {
            switch (request.effectiveMode()) {
                case INTERVAL -> assertIntervalsNonOverlapping(request.intervals());
                case EXPLICIT_TIMES -> validateDayTimes(request.times());
            }
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
     * Evicts the affected master's {@code available-slots} <b>and</b> {@code master-working-days}
     * entries <b>after commit</b> so a parallel reader cannot repopulate stale availability mid-write
     * (§F). A schedule/override change can affect any service's slots for the master, so we evict by
     * master prefix (the cache key's first element is the masterId) rather than per
     * {@code (date, masterServiceId)}/{@code (from, to)} — bounded to one master, not blanket.
     */
    private void evictSlotsAfterCommit(UUID masterId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                doEvictAvailableSlotsByMaster(masterId);
                doEvictWorkingDaysByMaster(masterId);
            }
        });
    }

    private void doEvictAvailableSlotsByMaster(UUID masterId) {
        evictByMasterPrefix("available-slots", masterId);
    }

    /**
     * Mirrors {@link #doEvictAvailableSlotsByMaster}: {@code master-working-days} is keyed
     * {@code {#masterId, #from, #to}}, so the same masterId-prefix Caffeine key scan applies.
     */
    private void doEvictWorkingDaysByMaster(UUID masterId) {
        evictByMasterPrefix("master-working-days", masterId);
    }

    /**
     * {@code @Cacheable} with an explicit SpEL {@code key} (e.g. {@code key = "{#masterId, #from, #to}"})
     * is never wrapped in a {@link org.springframework.cache.interceptor.SimpleKey} — that type is only
     * produced by the default {@code SimpleKeyGenerator} when no {@code key} attribute is given. The
     * {@code {...}} inline-list SpEL literal evaluates to a {@link List} (an {@code ArrayList}) at
     * runtime, so eviction matches on the real key's runtime type — {@link List} — and compares its
     * first element (the masterId) directly, rather than an {@code instanceof SimpleKey} check (which
     * never matches these keys) or fragile {@code toString()} substring matching.
     */
    private void evictByMasterPrefix(String cacheName, UUID masterId) {
        Cache springCache = cacheManager.getCache(cacheName);
        if (springCache == null) {
            return;
        }
        Object nativeCache = springCache.getNativeCache();
        if (nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache) {
            caffeineCache.asMap().keySet().removeIf(k ->
                    k instanceof List<?> keyParts
                            && !keyParts.isEmpty()
                            && masterId.equals(keyParts.get(0)));
        } else {
            springCache.clear();
        }
    }
}
