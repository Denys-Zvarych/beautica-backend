package com.beautica.master.service;

import com.beautica.common.DateRange;
import com.beautica.common.cache.MasterCachePrefixEvictor;
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
import com.beautica.master.entity.WeeklyScheduleDayWindow;
import com.beautica.master.entity.WorkingInterval;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.ScheduleExceptionRepository;
import com.beautica.master.repository.WeeklyScheduleRepository;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import lombok.RequiredArgsConstructor;
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
import java.util.Objects;
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
 * <p><b>OQ-1 — REVERSED (2026-07-26 design, "schedule override over existing bookings").</b> This
 * class itself still has no {@code BookingRepository} dependency and {@link #upsertOverride} still
 * performs an unconditional write when called directly — it remains the low-level "just persist
 * this override" primitive, and stays booking-agnostic on purpose (no cross-feature repository
 * coupling in this class; see {@code AuthorizationService}/{@code hasManagementAccess} for the only
 * kind of cross-feature reach this service makes). The former "always allowed, never blocked by nor
 * mutating existing bookings" guarantee is what changed: {@code POST /overrides/{date}} is no
 * longer wired to call this method directly. It is wired to
 * {@code com.beautica.booking.service.ScheduleOverrideConflictService#applyOverrideWithConflictHandling},
 * which recomputes conflicting {@code CONFIRMED} bookings inside the SAME write transaction, either
 * rejects the write with a 409 (no {@code cancelOverlapping} consent) or declines every conflict
 * (via the existing single-booking/appointment-child decline paths) immediately after calling this
 * method. Any OTHER future caller of {@link #upsertOverride} — bypassing that orchestrator — reverts
 * to the old unconditional-write behaviour; there is intentionally no enforcement inside this class
 * that prevents that, exactly as there was none enforcing OQ-1 before. <b>OQ-3 (gaps).</b> An
 * uncovered date resolves to {@link EffectiveDaySource#NO_SCHEDULE} with empty intervals.
 *
 * <p><b>No N+1.</b> {@link #resolveEffectiveRange} bulk-loads overrides and overlapping templates for the
 * whole window in two queries, then folds each date in-memory via {@link DateRange}.
 */
@Service
@RequiredArgsConstructor
public class MasterScheduleService {

    private static final int MAX_INTERVALS_PER_DAY = 6;
    private static final int MAX_DISCRETE_TIMES_PER_DAY = 24;

    /**
     * Every master-availability cache a SCHEDULE write can invalidate. All five are keyed by a SpEL inline
     * list whose first element is the masterId, so all five are evicted by the same master-prefix scan
     * ({@link MasterCachePrefixEvictor}):
     * <ul>
     *   <li>{@code available-slots} — {@code {masterId, date, masterServiceId}}</li>
     *   <li>{@code master-working-days} — {@code {masterId, from, to}} (schedule-shape mode)</li>
     *   <li>{@code master-usable-schedule} — {@code {masterId, from, to}} ({@link #hasUsableSchedule})</li>
     *   <li>{@code master-service-bookable} — {@code {masterId, masterServiceId, from, to}}</li>
     *   <li>{@code master-bookable-days} — {@code {masterId, from, to, masterServiceId}} (the
     *       availability-aware mode of the working-days endpoint)</li>
     * </ul>
     * A schedule change can flip the verdict of ANY of them for ANY of the master's services, so the write
     * path evicts the whole set; the window/date portion of those keys cannot be evicted selectively.
     */
    private static final String[] SCHEDULE_WRITE_CACHES = {
            "available-slots",
            "master-working-days",
            "master-usable-schedule",
            "master-service-bookable",
            "master-bookable-days",
    };

    private final WeeklyScheduleRepository weeklyScheduleRepository;
    private final ScheduleExceptionRepository scheduleExceptionRepository;
    private final MasterRepository masterRepository;
    private final ScheduleDateMath dateMath;
    private final AuthorizationService authz;
    private final ScheduleMapper scheduleMapper;
    private final MasterCachePrefixEvictor cacheEvictor;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;

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
        evictSlotsAfterCommit(master);
        return scheduleMapper.toWeeklyScheduleResponse(saved);
    }

    // ---- Step 3: per-date override upsert -----------------------------------------------

    /**
     * Upserts a single per-date override (DAY_OFF or CUSTOM_HOURS): loads/authorizes the master,
     * rejects a past date, validates kind/mode consistency, then atomically replaces the override's
     * intervals. Always writes unconditionally — it does not itself query, cancel, or notify
     * {@code CONFIRMED} bookings that the new intended availability no longer covers.
     *
     * <p><b>Former OQ-1 ("always allowed") is reversed at the endpoint level, not in this method
     * body.</b> See the class-level javadoc's "OQ-1 — REVERSED" section:
     * {@code ScheduleOverrideConflictService#applyOverrideWithConflictHandling} is the only
     * production caller (via {@code MasterController}'s {@code PUT /overrides/{date}}), and it is
     * the one that recomputes booking conflicts and either rejects the write (409, no consent) or
     * declines the conflicting bookings right after this method returns, inside the same
     * transaction. Calling this method directly — as the existing {@code MasterScheduleServiceIT} /
     * {@code MasterScheduleEdgeCaseIT} / {@code MasterScheduleSecurityIT} suites still do — retains
     * the original unconditional-write behaviour with zero conflict handling, by design (those
     * suites exercise the override CRUD invariants in isolation from the booking-conflict feature).
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
        List<WorkIntervalDto> overrideIntervals =
                request.kind() == ScheduleExceptionKind.CUSTOM_HOURS && !explicitTimes
                        ? request.intervals() : List.of();
        // Phase 15.12: display-only working window. resolveWindow yields null for a DAY_OFF and for an
        // EXPLICIT_TIMES override (both pass an empty interval list here), so a mode/kind flip clears the
        // stored bounds exactly as it clears the opposite child collection.
        //
        // ORDER MATTERS: this must run BEFORE replaceOverrideIntervals/replaceOverrideDiscreteTimes.
        // Both of those flush() mid-method (to force orphan DELETEs ahead of the re-INSERTs), and that
        // flush writes the parent row too. Setting kind=DAY_OFF above while window_start/window_end still
        // hold a previous CUSTOM_HOURS row's values would make the flushed row violate
        // chk_exc_window_kind ("a window only exists on CUSTOM_HOURS") and fail the whole upsert. Clearing
        // the window first keeps every intermediate flush state a legal row.
        WindowBounds window = resolveWindow(request.windowStart(), request.windowEnd(), overrideIntervals);
        override.setWindowStart(window != null ? window.start() : null);
        override.setWindowEnd(window != null ? window.end() : null);

        replaceOverrideIntervals(override, overrideIntervals);
        replaceOverrideDiscreteTimes(override, explicitTimes ? request.times() : List.of());

        ScheduleException saved = scheduleExceptionRepository.save(override);
        evictSlotsAfterCommit(master);
        return scheduleMapper.toOverrideResponse(saved);
    }

    /**
     * Loads the target master and verifies WRITE (schedule-management) authority — the exact
     * {@link #loadActiveMaster} + {@link AuthorizationService#enforceCanManageMasterSchedule} pair
     * {@link #upsertOverride} performs internally moments later. Exposed for
     * {@code com.beautica.booking.service.ScheduleOverrideConflictService}, which must confirm the
     * caller may manage {@code masterId}'s schedule BEFORE it runs its own booking-conflict query —
     * a query that returns client/guest identities and must not be reachable by an unauthorized
     * caller even though {@code MasterController}'s {@code @PreAuthorize} SpEL already gates the
     * same endpoint (defense in depth, matching this class's own re-check pattern on every write).
     *
     * <p>Deliberately public rather than duplicating {@code MasterRepository} + this exact
     * authorization call inside the booking package: cross-feature reach goes through a service's
     * public interface, never a sibling feature's repository directly (this class already owns the
     * "load + enforce" invariant for schedule writes; a second, drifting copy of it elsewhere would
     * be the actual anti-pattern).
     */
    public Master requireScheduleManagementAuthority(UUID actorId, UUID masterId) {
        Master master = loadActiveMaster(masterId);
        authz.enforceCanManageMasterSchedule(actorId, master);
        return master;
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
        evictSlotsAfterCommit(master);
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
        evictSlotsAfterCommit(master);
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
     *
     * <p><b>Phase 15.12 — window-free.</b> Does not project the display-only working window, and therefore
     * never initializes the LAZY {@code dayWindows} collection. This is the variant every consumer that
     * reduces the projection to availability should call: {@code SlotCalculationService} (three call
     * sites) and {@link #getClientWorkingDays}. The one consumer that renders the window —
     * {@code MasterController}'s {@code GET /effective-schedule} — calls
     * {@link #resolveEffectiveRangeForDisplay} instead.
     */
    @Transactional(readOnly = true)
    public List<EffectiveDayResponse> resolveEffectiveRange(UUID masterId, LocalDate from, LocalDate to) {
        return foldRange(masterId, from, to).stream().map(ResolvedDay::day).toList();
    }

    /**
     * Phase 15.12: {@link #resolveEffectiveRange} plus the display-only working-window projection, for the
     * {@code GET /effective-schedule} endpoint the mobile day editor hydrates from (it derives breaks as
     * {@code window MINUS intervals}, which is the only way an edge-flush break survives a reload).
     *
     * <p><b>Not a second resolver.</b> It runs the very same {@link #foldRange} core and then maps each
     * result through {@link #withWindow}, a purely additive step that fills two nullable fields on an
     * already-decided day. Availability is settled before decoration begins, so the window provably cannot
     * change {@link EffectiveDayResponse#isWorkingDay()} or any interval — there is one fold, one
     * precedence rule, one verdict. Only this method pays the extra batched {@code dayWindows} load.
     */
    @Transactional(readOnly = true)
    public List<EffectiveDayResponse> resolveEffectiveRangeForDisplay(
            UUID masterId, LocalDate from, LocalDate to) {
        return foldRange(masterId, from, to).stream().map(this::withWindow).toList();
    }

    /**
     * The single range-fold core: bulk-loads overrides and overlapping templates in two queries, then
     * resolves every date in-memory. Returns each resolved day alongside the entity that produced it, so a
     * caller that wants the display-only window can decorate without re-querying, and a caller that does
     * not simply drops the entity — the difference is a {@code map}, never a second fold.
     */
    private List<ResolvedDay> foldRange(UUID masterId, LocalDate from, LocalDate to) {
        // Read path: past dates are included (the calendar paints greyed history — Phase 15.5 Step 3),
        // so use the read-window guard rather than assertWithinBounds (which forbids a past start).
        dateMath.assertExpandable(from, to);
        List<LocalDate> dates = dateMath.expandInclusive(from, to);

        Map<LocalDate, ScheduleException> overridesByDate = scheduleExceptionRepository
                .findByMasterIdAndDateBetweenWithIntervals(masterId, from, to).stream()
                .collect(Collectors.toMap(ScheduleException::getDate, e -> e, (a, b) -> a));
        List<WeeklySchedule> windows =
                weeklyScheduleRepository.findOverlappingRangeWithIntervals(masterId, from, to);

        List<ResolvedDay> result = new ArrayList<>(dates.size());
        for (LocalDate date : dates) {
            ScheduleException override = overridesByDate.get(date);
            if (override != null) {
                result.add(new ResolvedDay(resolveFromOverride(date, override), override, null));
            } else {
                WeeklySchedule covering = firstCovering(windows, date);
                result.add(new ResolvedDay(resolveFromTemplate(date, covering), null, covering));
            }
        }
        return result;
    }

    /**
     * A resolved day plus the entity that produced it. The entity is carried only so
     * {@link #withWindow} can read the display-only bounds off it without a second query; exactly one of
     * {@code override}/{@code covering} is non-null (and both are null for a {@code NO_SCHEDULE} date).
     */
    private record ResolvedDay(
            EffectiveDayResponse day, ScheduleException override, WeeklySchedule covering) {
    }

    /**
     * Phase 15.12: fills in the display-only working-window bounds of whichever source produced the day.
     * Purely additive — it copies an already-resolved day and sets two nullable fields; it never revisits
     * the availability decision.
     *
     * <p>Returns the day untouched (window {@code null}) whenever a window would be meaningless: no
     * intervals (day off / uncovered weekday / no schedule), or an EXPLICIT_TIMES day, whose discrete
     * times ARE the slot set and which has no window-with-breaks affordance. Reading the template branch
     * is what initializes the batch-fetched {@code dayWindows} collection — one query per fold, and only
     * on this path.
     */
    private EffectiveDayResponse withWindow(ResolvedDay resolved) {
        EffectiveDayResponse day = resolved.day();
        boolean explicitTimes = day.times() != null && !day.times().isEmpty();
        if (day.intervals().isEmpty() || explicitTimes) {
            return day;
        }
        if (resolved.override() != null) {
            return scheduleMapper.toEffectiveDayWithWindow(day.date(), day.source(), day.intervals(),
                    resolved.override().getWindowStart(), resolved.override().getWindowEnd());
        }
        if (resolved.covering() == null) {
            return day;
        }
        var window = scheduleMapper.findDayWindow(resolved.covering(), dateMath.isoDow(day.date()));
        return scheduleMapper.toEffectiveDayWithWindow(day.date(), day.source(), day.intervals(),
                window.map(WeeklyScheduleDayWindow::getWindowStart).orElse(null),
                window.map(WeeklyScheduleDayWindow::getWindowEnd).orElse(null));
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
        // resolveEffectiveRange (the window-FREE variant), never resolveEffectiveRangeForDisplay: this
        // reduces to a boolean, so projecting the display-only window would load dayWindows purely to
        // throw it away (Phase 15.12). Calling the public reducer rather than foldRange directly costs
        // nothing — it is a one-line map over the same core — and keeps this a genuine "thin reducer over
        // resolveEffectiveRange", which is what MasterScheduleServiceTest stubs.
        return resolveEffectiveRange(masterId, from, to).stream()
                .map(day -> new MasterWorkingDayResponse(day.date(), day.isWorkingDay()))
                .toList();
    }

    // ---- Step 8 (Phase 23.x perf follow-up): short-circuiting boolean usability gate -----

    /**
     * Boolean equivalent of {@code getClientWorkingDays(masterId, from,
     * to).stream().anyMatch(MasterWorkingDayResponse::working)} that stops at the first working day
     * instead of materializing the full {@code [from, to]} projection first. {@link #getClientWorkingDays}
     * always resolves every date in the requested range via {@link #resolveEffectiveRange} before a
     * caller's {@code anyMatch} can short-circuit the final scan — so a 181-day booking-horizon check
     * paid the full 181-date resolution even when the very first date was already a working day. This
     * method short-circuits the day-walk itself: it returns as soon as a working day is found.
     *
     * <p><b>Same resolver, identical verdict.</b> Not a parallel/divergent predicate — it bulk-loads the
     * exact same {@code overridesByDate}/{@code windows} data {@link #resolveEffectiveRange} loads, folds
     * each date via the exact same {@link #resolveFromOverride}/{@link #resolveFromTemplate}/
     * {@link #firstCovering} helpers, and reduces each result via the exact same
     * {@link EffectiveDayResponse#isWorkingDay()} that {@link #getClientWorkingDays} does. Because every
     * step is the same code, not a reimplementation, this returns {@code true} for a date range iff
     * {@code getClientWorkingDays(...).anyMatch(...)} would — for every schedule shape (no schedule, an
     * expired {@code valid_to}, a zero-interval template row, an EXPLICIT_TIMES-only day, a future-only
     * window). Only the loop control differs: an early {@code return true} instead of continuing to fold
     * every remaining date.
     *
     * <p><b>Caching.</b> Mirrors {@link #getClientWorkingDays}: cached in {@code master-usable-schedule}
     * (60 sec TTL, {@code sync=true} to collapse a thundering herd), keyed identically
     * {@code {#masterId, #from, #to}}, and evicted by master prefix from {@link #evictSlotsAfterCommit}
     * alongside {@code available-slots} and {@code master-working-days} on every schedule write.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "master-usable-schedule", key = "{#masterId, #from, #to}", sync = true)
    public boolean hasUsableSchedule(UUID masterId, LocalDate from, LocalDate to) {
        dateMath.assertExpandable(from, to);
        List<LocalDate> dates = dateMath.expandInclusive(from, to);

        Map<LocalDate, ScheduleException> overridesByDate = scheduleExceptionRepository
                .findByMasterIdAndDateBetweenWithIntervals(masterId, from, to).stream()
                .collect(Collectors.toMap(ScheduleException::getDate, e -> e, (a, b) -> a));
        List<WeeklySchedule> windows =
                weeklyScheduleRepository.findOverlappingRangeWithIntervals(masterId, from, to);

        for (LocalDate date : dates) {
            ScheduleException override = overridesByDate.get(date);
            EffectiveDayResponse day = override != null
                    ? resolveFromOverride(date, override)
                    : resolveFromTemplate(date, firstCovering(windows, date));
            if (day.isWorkingDay()) {
                return true;
            }
        }
        return false;
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
        // Availability only — the display-only working window is layered on afterwards by #withWindow, and
        // only for the display path, so this core never touches the override's window columns.
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
        // Availability only — the display-only working window is layered on afterwards by #withWindow, and
        // only for the display path, so this core never initializes the LAZY dayWindows collection.
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
        Master master = masterRepository.findByIdWithUserAndSalon(masterId)
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
        schedule.getDayWindows().clear();     // orphanRemoval queues DELETEs for the old day-window rows
        // Force the orphan DELETEs to the DB before re-inserting. With hibernate.order_inserts=true the
        // ActionQueue runs all INSERTs before all DELETEs, so a re-sent row whose unique key matches a
        // surviving old row would collide (23505: uq_working_intervals_no_dup /
        // uq_working_interval_times_no_dup / uq_day_window_per_day). Flushing here makes it
        // delete-before-insert.
        weeklyScheduleRepository.flush();

        for (WeeklyScheduleDayRequest day : days) {
            switch (day.effectiveMode()) {
                case INTERVAL -> {
                    addIntervals(schedule, day);
                    addDayWindow(schedule, day);
                }
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

    /**
     * Phase 15.12: records the day's display-only working window, when the client sent one AND the day
     * actually has working intervals. {@link #resolveWindow} returns {@code null} for a day off (empty
     * interval list) and for an omitted window, so no row is written in either case — a window is never
     * synthesized from {@code min(start)..max(end)} (that would be indistinguishable from one the user
     * chose and would silently assert "this day has no edge-flush break").
     */
    private void addDayWindow(WeeklySchedule schedule, WeeklyScheduleDayRequest day) {
        WindowBounds window = resolveWindow(day.windowStart(), day.windowEnd(), day.intervals());
        if (window == null) {
            return;
        }
        schedule.getDayWindows().add(WeeklyScheduleDayWindow.builder()
                .schedule(schedule)
                .dayOfWeek(day.dayOfWeek())
                .windowStart(window.start())
                .windowEnd(window.end())
                .build());
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
            case INTERVAL -> {
                assertIntervalsNonOverlapping(day.intervals());
                // Phase 15.12: fail before any collection is mutated. resolveWindow is pure, so
                // addDayWindow re-derives the same value later without re-validating side effects.
                resolveWindow(day.windowStart(), day.windowEnd(), day.intervals());
            }
            case EXPLICIT_TIMES -> validateDayTimes(day.times());
        }
    }

    /**
     * Phase 15.12: the validated, seconds-zeroed working window for one INTERVAL day, or {@code null} when
     * no window is to be stored.
     *
     * <p>The window is <b>display-only</b> metadata that lets the mobile editor rebuild a break sitting flush
     * against an edge of the working day (such a break leaves no gap between intervals, so gap
     * reconstruction loses it). It is NEVER consulted when computing availability — {@code intervals} stay
     * the single canonical source, and {@link #resolveEffectiveDay}/{@link #resolveEffectiveRange} (hence
     * {@code SlotCalculationService}) never read it.
     *
     * <p>Rules:
     * <ul>
     *   <li><b>Day off / no intervals</b> — the window carries no meaning; any supplied value is ignored and
     *       {@code null} is stored (never invented from {@code min(start)..max(end)}).</li>
     *   <li><b>Both omitted</b> — {@code null}; the legacy shape, behaves exactly as before 15.12.</li>
     *   <li><b>One of two</b> — rejected: a half-specified window is malformed input.</li>
     *   <li><b>Ordering</b> — {@code windowEnd > windowStart} strictly. No wraparound: the window inherits
     *       the locked no-cross-midnight interval contract (a night shift is two ISO-weekday rows).</li>
     *   <li><b>Containment</b> — {@code windowStart <= min(interval.startTime)} and
     *       {@code windowEnd >= max(interval.endTime)}: the window must contain every working interval,
     *       otherwise the derived {@code window MINUS intervals} break list would be nonsense.</li>
     * </ul>
     *
     * <p>This mirrors {@code WeeklyScheduleDayRequest#isWindowConsistent()} /
     * {@code ScheduleOverrideRequest#isWindowConsistent()} defensively (a malformed payload that reached the
     * service is still rejected) and adds the containment rule those declarative checks cannot express.
     */
    private WindowBounds resolveWindow(
            LocalTime windowStart, LocalTime windowEnd, List<WorkIntervalDto> intervals) {
        if (intervals == null || intervals.isEmpty()) {
            return null; // day off / no working time — a "working window" is not a thing here
        }
        if (windowStart == null && windowEnd == null) {
            return null;
        }
        if (windowStart == null || windowEnd == null) {
            throw new BusinessException(
                    "windowStart and windowEnd must both be provided or both omitted");
        }
        LocalTime start = zeroSeconds(windowStart);
        LocalTime end = zeroSeconds(windowEnd);
        if (!end.isAfter(start)) {
            throw new BusinessException("Working window end must be after its start");
        }
        LocalTime earliest = intervals.stream()
                .map(WorkIntervalDto::startTime).map(this::zeroSeconds)
                .min(Comparator.naturalOrder()).orElseThrow();
        LocalTime latest = intervals.stream()
                .map(WorkIntervalDto::endTime).map(this::zeroSeconds)
                .max(Comparator.naturalOrder()).orElseThrow();
        if (start.isAfter(earliest) || end.isBefore(latest)) {
            throw new BusinessException("Working window must contain every working interval of the day");
        }
        return new WindowBounds(start, end);
    }

    /** Phase 15.12: a validated, seconds-zeroed display-only working window. */
    private record WindowBounds(LocalTime start, LocalTime end) {
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
                case INTERVAL -> {
                    assertIntervalsNonOverlapping(request.intervals());
                    // Phase 15.12: fail before any collection is mutated. resolveWindow is pure, so
                    // upsertOverride re-derives the same value later without re-validating side effects.
                    resolveWindow(request.windowStart(), request.windowEnd(), request.intervals());
                }
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
        // Defence in depth behind the element-level @NotNull on both request DTOs' interval lists. This
        // method (and resolveWindow, which runs right after it on both surfaces) dereferences every entry,
        // so a null element would NPE into a 500. The DTO constraint stops that at the HTTP boundary; this
        // stops it for callers that construct a request in-process and bypass @Valid entirely — i.e. the
        // schedule ITs. It is NOT what protects the conflict-preview flow: MasterController declares
        // @Valid on ScheduleOverrideRequest, and in any case ScheduleOverrideConflictService dereferences
        // the intervals in fullyCovers well before it calls upsertOverride, so this guard could not fire
        // first there. That path is covered by OverrideConflictQueryRequest's own element-level @NotNull.
        if (intervals.stream().anyMatch(Objects::isNull)) {
            throw new BusinessException("An interval must not be null");
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
     * Evicts all five of the affected master's availability caches ({@link #SCHEDULE_WRITE_CACHES})
     * <b>after commit</b> so a parallel reader cannot repopulate stale availability mid-write (§F). A
     * schedule/override change can affect any service's slots for the master, so we evict by master prefix
     * (the cache key's first element is the masterId) rather than per
     * {@code (date, masterServiceId)}/{@code (from, to)} — bounded to one master, not blanket.
     *
     * <p><b>Off the request thread (Perf MEDIUM-3).</b> The five keyset scans (~5 500 key comparisons) used
     * to run synchronously on the committing thread. They are now submitted to {@code cacheEvictionExecutor}
     * from inside the same {@code afterCommit} hook — so ordering is unchanged (never before commit), but a
     * schedule write no longer pays for the scan. The scan logic itself lives in the shared
     * {@link MasterCachePrefixEvictor} (it was duplicated verbatim here and in
     * {@code SlotCalculationService}).
     *
     * <p><b>Salon catalogue (perf/security #2).</b> A schedule change can flip whether a master has any
     * free future slot, so it can add/remove a service from the master's SALON catalogue. When the master
     * belongs to a salon ({@code master.salon} — JOIN-FETCHed by {@code loadActiveMaster}), the salon's
     * {@code salon-service-catalog} entry is evicted too; an independent master (null salon) owns no salon
     * catalogue entry. The salon id is captured before the callback, not read from the entity afterCommit.
     */
    private void evictSlotsAfterCommit(Master master) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        UUID masterId = master.getId();
        UUID salonId = master.getSalon() != null ? master.getSalon().getId() : null;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cacheEvictor.evictByMasterPrefix(masterId, SCHEDULE_WRITE_CACHES);
                if (salonId != null) {
                    salonCatalogCacheEvictor.evict(salonId);
                }
            }
        });
    }
}
