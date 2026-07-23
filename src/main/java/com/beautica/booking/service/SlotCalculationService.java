package com.beautica.booking.service;

import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.common.BookingWindow;
import com.beautica.common.TimeZones;
import com.beautica.common.cache.MasterCachePrefixEvictor;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.util.TimeSlotCalculator;
import com.beautica.common.util.TimeSlotCalculator.TimeRange;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.BookingTimeRange;
import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.dto.MasterWorkingDayResponse;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.service.MasterScheduleService;
import com.beautica.master.service.ScheduleDateMath;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.repository.MasterServiceRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SlotCalculationService {

    private static final Duration SLOT_STEP = Duration.ofMinutes(30);
    private static final String BOOKABLE_CACHE = "master-service-bookable";
    private static final String BOOKABLE_DAYS_CACHE = "master-bookable-days";

    /** durationOverride max (480 min) + bufferMinutesAfter max (120 min) — see the DTO validation matrix. */
    private static final int MAX_TOTAL_DURATION_MINUTES = 600;

    /**
     * Upper bound on the number of services chained into ONE single-visit slot/day request (BE-2). The
     * {@value #MAX_TOTAL_DURATION_MINUTES}-min total-duration ceiling already caps a realistic chain far
     * below this, so this is the belt-and-braces size guard against an oversized {@code serviceId} list
     * (each id costs a master-service lookup). Mirrored by {@code @Size(max = …)} at the controller boundary.
     */
    public static final int MAX_SERVICES_PER_VISIT = 10;

    /**
     * Maximum span (in days BETWEEN the endpoints, so 63 inclusive dates) of the {@code serviceId}-PRESENT
     * mode of {@code GET /masters/{masterId}/working-days} — see {@link #getBookableWorkingDays}.
     * Deliberately far tighter than {@code ScheduleDateMath}'s 366-day read window, which still governs the
     * {@code serviceId}-ABSENT (schedule-shape) mode — that contract is unchanged.
     */
    private static final long MAX_BOOKABLE_SPAN_DAYS = 62L;

    private final BookingRepository bookingRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final MasterScheduleService masterScheduleService;
    private final ScheduleDateMath dateMath;
    private final TimeSlotCalculator timeSlotCalculator;
    private final MasterCachePrefixEvictor cacheEvictor;
    private final Clock kyivClock;

    public SlotCalculationService(
            BookingRepository bookingRepository,
            MasterServiceRepository masterServiceRepository,
            MasterScheduleService masterScheduleService,
            ScheduleDateMath dateMath,
            TimeSlotCalculator timeSlotCalculator,
            MasterCachePrefixEvictor cacheEvictor,
            Clock clock) {
        this.bookingRepository = bookingRepository;
        this.masterServiceRepository = masterServiceRepository;
        this.masterScheduleService = masterScheduleService;
        this.dateMath = dateMath;
        this.timeSlotCalculator = timeSlotCalculator;
        this.cacheEvictor = cacheEvictor;
        this.kyivClock = clock.withZone(TimeZones.KYIV);
    }

    /**
     * <b>Legacy single-service slot list</b> — behind {@code GET /masters/{id}/slots?serviceId=…} for a
     * one-service request, and called directly by {@code GuestBookingService#availableSlots} and
     * {@code BookingService}'s on-schedule create check. Left byte-for-byte UNCHANGED: the same cache
     * ({@code available-slots}, key {@code {masterId, date, masterServiceId}}, {@code sync=true}) and the
     * same eviction (per-key {@link #evictAvailableSlots} + the master-prefix sweeps) it always had.
     *
     * <p>Delegates to the N-service core {@link #computeAvailableSlots} with a 1-element list; the summed
     * duration of a 1-element list is exactly that service's effective duration, so the output is identical
     * to the pre-BE-2 implementation.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "available-slots", key = "{#masterId, #date, #masterServiceId}", sync = true)
    public List<AvailableSlotResponse> getAvailableSlots(UUID masterId, LocalDate date, UUID masterServiceId) {
        return computeAvailableSlots(masterId, date, List.of(masterServiceId));
    }

    /**
     * <b>Multi-service single-visit slot list (BE-2).</b> Sizes each candidate slot to the SUM of the
     * ordered {@code masterServiceIds}' effective durations — one contiguous back-to-back block performed
     * by a single master — and otherwise reuses the exact free-range subtraction the single-service path
     * uses (only the duration handed to {@link TimeSlotCalculator} changes).
     *
     * <p><b>D4 buffer policy.</b> Each service's own {@code bufferMinutesAfter} is applied after it —
     * between chained services and at the tail — because {@link #effectiveDuration} already folds a
     * service's buffer into its effective length, so summing the effective durations yields exactly
     * {@code Σ (duration_i + buffer_i)}. The server always sums server-side from the resolved assignments;
     * a client-supplied duration is never trusted.
     *
     * <p><b>Cap.</b> The summed duration is bounded by the same {@value #MAX_TOTAL_DURATION_MINUTES}-min
     * ceiling the single-service path enforces (via {@link #validatedTotalDuration}); an over-long chain
     * fails with the same {@link BusinessException}, never a silent truncation.
     *
     * <p><b>Not cached — deliberately.</b> For {@code N=1} the controller routes to the cached single-arg
     * overload above (identical legacy key), so single-element requests key identically to the legacy
     * value. The {@code N&gt;1} path is intentionally left uncached: the booking-write eviction hook
     * ({@code BookingService}/{@code GuestBookingService}/{@code BookingCancellationService}) evicts
     * {@code available-slots} only per single-UUID key, so a list-keyed entry would go stale after a
     * single-service booking write on any chained service. Caching the ordered-list key is deferred to
     * BE-3, where the write path gains multi-key (master-prefix) eviction — until then a never-evicted
     * key is not introduced.
     */
    @Transactional(readOnly = true)
    public List<AvailableSlotResponse> getAvailableSlots(
            UUID masterId, LocalDate date, List<UUID> masterServiceIds) {
        assertServiceIds(masterServiceIds);
        return computeAvailableSlots(masterId, date, masterServiceIds);
    }

    /**
     * Shared slot-list core for both the single-service and multi-service entry points. Identical to the
     * pre-BE-2 single-service body except that the block length is the SUM of the ordered assignments'
     * effective durations ({@link #validatedTotalDuration}) rather than one service's.
     */
    private List<AvailableSlotResponse> computeAvailableSlots(
            UUID masterId, LocalDate date, List<UUID> masterServiceIds) {
        // Step 1: date range validation — cheapest guard, no DB.
        // ONE clock read for the whole request (Perf LOW-2): `today`, the horizon and the bookable cutoff
        // are all derived from the same Instant, so no two checks can straddle a clock tick.
        Instant now = kyivClock.instant();
        LocalDate today = LocalDate.ofInstant(now, TimeZones.KYIV);
        if (date.isBefore(today)) {
            throw new BusinessException("date is in the past");
        }
        if (date.isAfter(today.plusDays(BookingWindow.MAX_DAYS_AHEAD))) {
            throw new BusinessException("date too far ahead");
        }

        // Step 2: load each master service in order — validated first to close the working-hours oracle.
        // Shared with getBookableWorkingDays, so an unknown / foreign / inactive service answers identically
        // on the slot endpoint and on the availability-aware working-days endpoint.
        List<MasterServiceAssignment> assignments = loadBookableAssignments(masterId, masterServiceIds);

        // Guard: master must be active to expose any bookable slots. All chained assignments share the same
        // master (they are loaded master-scoped), so the first one's master carries the liveness flag.
        // deactivateOwnerMaster (and the general deactivateMaster) sets masters.is_active = false
        // but leaves master_services rows intact — check the master entity itself here.
        if (!assignments.get(0).getMaster().isActive()) {
            return List.of();
        }

        // Steps 3+4: summed effective duration (override beats base, plus each service's own buffer) with
        // the upper-bound guard.
        Duration totalDuration = validatedTotalDuration(assignments);

        // Slot calculation is master-type agnostic: the effective-availability resolver
        // (weekly templates + per-date overrides) and bookings are keyed by master_id alone.
        // A SALON_OWNER master with a weekly template and an active master_services row is
        // bookable identically to any other master type. Master liveness (masters.is_active)
        // is checked above before reaching this point — do not remove that guard.

        // Step 5: resolve the effective availability for this date via the Phase 15.4 resolver.
        // This replaces the legacy single-WorkingHours window + schedule-exception closure check with
        // the unified model: multi-interval days, validity windows, custom-hours overrides, and
        // day-offs. NO_SCHEDULE (gap) or OVERRIDE_DAY_OFF resolve to empty intervals — no slots.
        EffectiveDayResponse effective = masterScheduleService.resolveEffectiveDay(masterId, date);
        List<WorkIntervalDto> intervals = effective.intervals();
        if (intervals == null || intervals.isEmpty()) {
            return List.of();
        }

        // Step 6: compute the booking-query window in OffsetDateTime.
        // The lower bound is the date's start of day; the upper bound normally is date+1 00:00.
        // crossesMidnight is ALWAYS false for persisted intervals: the model enforces endTime > startTime
        // at four layers (WorkIntervalDto.isOrdered, MasterScheduleService validation, chk_interval_order,
        // chk_exc_interval_order), so a resolved interval can never satisfy endTime <= startTime. A night
        // shift is two single-calendar-day rows on two adjacent ISO weekdays, each subtracted on its own
        // date query — never one wrapping interval. This widen GUARDS AN UNREACHABLE MODEL STATE: were a
        // cross-midnight interval ever to slip through, its post-midnight slots (and the bookings on them)
        // would run into the next calendar day, and a flat [date 00:00, date+1 00:00) window would never
        // load a post-midnight booking (the native finder filters starts_at < windowEnd) → double-book.
        // It stays defensive and cheap (one extra day only on a state that cannot occur); the normal path
        // keeps the tight single-day window (Anti-Bug §E narrow window).
        OffsetDateTime dayStart = date.atStartOfDay(TimeZones.KYIV).toOffsetDateTime();
        boolean crossesMidnight = intervals.stream()
                .anyMatch(iv -> !iv.endTime().isAfter(iv.startTime()));
        LocalDate windowEndDate = crossesMidnight ? date.plusDays(2) : date.plusDays(1);
        OffsetDateTime dayEnd = windowEndDate.atStartOfDay(TimeZones.KYIV).toOffsetDateTime();

        // Step 7: load existing bookings that overlap the day window (CONFIRMED only).
        // Loaded once for the whole day and subtracted from every interval below.
        List<TimeRange> occupied = bookingRepository
                .findOverlappingByMaster(masterId, dayStart, dayEnd)
                .stream()
                .map(b -> new TimeRange(b.getStartsAt().toInstant(), b.getEndsAt().toInstant()))
                .toList();

        // Step 8: generate candidate slots per resolved interval and union the results (shared with
        // the free-slot bookability gate — see computeDayFreeRanges). Calling TimeSlotCalculator once
        // per interval is the multi-interval generalization of the legacy single-window call — gaps
        // between intervals (lunch breaks) naturally yield no slots. The cutoff is derived from the SAME
        // `now` the range guard above used (Perf LOW-2), not re-read per interval.
        return computeDayFreeRanges(date, intervals, totalDuration, occupied, BookingWindow.bookableCutoff(now))
                .stream()
                .map(r -> new AvailableSlotResponse(
                        r.start().atZone(TimeZones.KYIV),
                        r.end().atZone(TimeZones.KYIV)))
                .toList();
    }

    // ── Availability-aware calendar day-gating (booking-contract fix) ────────────────────────

    /**
     * Per-date <b>bookability</b> projection for {@code [from, to]} — the {@code serviceId}-aware mode of
     * {@code GET /masters/{masterId}/working-days}. A date is {@code working = true} iff the client could
     * actually complete a booking on it: the master's resolved schedule for that date leaves a free range
     * (after CONFIRMED bookings are subtracted) long enough for the service's effective duration,
     * starting at or after {@link #bookableCutoff()} and within the booking horizon.
     *
     * <p><b>Why this exists.</b> {@code MasterScheduleService#getClientWorkingDays} — the no-{@code
     * serviceId} mode of the same endpoint — answers a pure SCHEDULE-SHAPE question
     * ({@link EffectiveDayResponse#isWorkingDay()}: does the date carry template/override content?). It
     * never sees the service duration, never subtracts bookings, and applies no lead-time cutoff. The
     * mobile calendar gated day selection on it, so TODAY (already past the cutoff) and fully-booked
     * future days rendered as selectable, and the slot screen then showed "no free time". This method is
     * the availability-aware answer the client calendar needs; the master's own schedule UI keeps calling
     * the schedule-shape mode, unchanged.
     *
     * <p><b>One code path, one cutoff (the whole point).</b> This does NOT re-implement any rule. It walks
     * the very same {@link MasterScheduleService#resolveEffectiveRange} projection, and asks
     * {@link #isDayBookable} — the exact per-day predicate {@link #hasFreeFutureSlot} uses — which in turn
     * calls {@link #computeDayFreeRanges} (the same {@link TimeSlotCalculator} subtraction
     * {@link #getAvailableSlots} materializes) and compares against the same {@link #bookableCutoff()}
     * (itself {@link BookingWindow#bookableCutoff(Clock)}, the floor
     * {@code BookingStartsAtValidator} enforces on create). So {@code working == true} for a date iff
     * {@code getAvailableSlots(masterId, date, masterServiceId)} is non-empty — the two cannot disagree.
     *
     * <p><b>N+1.</b> Bookings for the WHOLE window are loaded in ONE query and bucketed by Kyiv-civil date
     * ({@link #loadOccupiedByDay}), so a 31-day calendar month costs one booking query, not 31.
     *
     * <p><b>Horizon &amp; past dates.</b> Dates past {@code today + }{@link BookingWindow#MAX_DAYS_AHEAD}
     * and dates before today report {@code working = false} rather than throwing: the caller may
     * legitimately request a window that straddles either edge (the mobile calendar renders whole months),
     * and a booking there would be rejected on create anyway. Both are answered by pure date comparison
     * BEFORE {@link #isDayBookable} runs (Perf HIGH-2) — a past date used to fall through to full slot
     * generation only to discover every candidate sits below the cutoff. TODAY itself is NOT short-circuited:
     * it can still be bookable later in the day.
     *
     * <p><b>Window cap (Perf HIGH-1 / security).</b> The span is capped at
     * {@value #MAX_BOOKABLE_SPAN_DAYS} days (a 400 beyond that) — see {@link #assertBookableSpan}. This is
     * the {@code serviceId}-PRESENT mode only; the {@code serviceId}-ABSENT schedule-shape mode keeps its
     * 366-day allowance untouched.
     *
     * <p><b>Errors.</b> Mirrors {@link #getAvailableSlots} exactly (shared
     * {@link #loadBookableAssignment}): an unknown, foreign OR inactive {@code masterServiceId} → 404,
     * indistinguishably (security LOW-1). An inactive MASTER is not an error — every day simply reports
     * {@code working = false}, mirroring the empty slot list.
     *
     * <p><b>Caching.</b> {@code master-bookable-days}, key {@code {#masterId, #from, #to,
     * #masterServiceId}} — {@code masterServiceId} is part of the key because two services with different
     * durations legitimately yield different day sets. 60 sec TTL, {@code sync = true} (hot client-calendar
     * key). Evicted by master prefix on every schedule write
     * ({@code MasterScheduleService#evictSlotsAfterCommit}) AND every booking write
     * ({@link #evictBookableFutureSlotsByMaster}) — a booking anywhere in the window can flip a day.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = BOOKABLE_DAYS_CACHE,
            key = "{#masterId, #from, #to, #masterServiceId}", sync = true)
    public List<MasterWorkingDayResponse> getBookableWorkingDays(
            UUID masterId, LocalDate from, LocalDate to, UUID masterServiceId) {
        // Legacy single-service calendar day-gate — UNCHANGED cache key/eviction. Delegates to the
        // N-service core with a 1-element list (its summed duration is exactly this service's), so a
        // single-service request is byte-for-byte identical to before.
        return computeBookableWorkingDays(masterId, from, to, List.of(masterServiceId));
    }

    /**
     * <b>Multi-service single-visit calendar day-gate (BE-2).</b> The N-service counterpart of the
     * single-service {@link #getBookableWorkingDays(UUID, LocalDate, LocalDate, UUID)} — a date is bookable
     * iff the master's schedule leaves a free range fitting the SUM of the ordered services' effective
     * durations (D4 buffer policy, see {@link #getAvailableSlots(UUID, LocalDate, List)}), so the calendar
     * day-gate agrees with the multi-service slot list. Not cached for the same reason the multi-service
     * slot list is not (see that method) — the controller routes {@code N=1} to the cached single-arg
     * overload above.
     */
    @Transactional(readOnly = true)
    public List<MasterWorkingDayResponse> getBookableWorkingDays(
            UUID masterId, LocalDate from, LocalDate to, List<UUID> masterServiceIds) {
        assertServiceIds(masterServiceIds);
        return computeBookableWorkingDays(masterId, from, to, masterServiceIds);
    }

    /**
     * Shared calendar day-gate core for both entry points. Identical to the pre-BE-2 single-service body
     * except the fitted block length is the summed effective duration of the ordered assignments.
     */
    private List<MasterWorkingDayResponse> computeBookableWorkingDays(
            UUID masterId, LocalDate from, LocalDate to, List<UUID> masterServiceIds) {

        // Range guards FIRST — pure in-memory arithmetic, zero DB (Perf LOW-1). A malformed or oversized
        // range now 400s without a round-trip, where it previously paid for loadBookableAssignment first.
        //
        // This does NOT reopen the working-hours oracle the original ordering guarded: assertExpandable /
        // assertBookableSpan look only at the two caller-supplied dates and the clock — they read nothing
        // about the master, the service, or the schedule, so their verdict is identical for a master that
        // exists and one that does not. The property that matters is preserved verbatim: the assignment is
        // still resolved BEFORE any SCHEDULE read (resolveEffectiveRange) or booking load, so an
        // unauthorized probe still learns nothing about a master's availability without a valid,
        // master-scoped masterServiceId.
        dateMath.assertExpandable(from, to);
        assertBookableSpan(from, to);

        List<MasterServiceAssignment> assignments = loadBookableAssignments(masterId, masterServiceIds);

        List<EffectiveDayResponse> days = masterScheduleService.resolveEffectiveRange(masterId, from, to);

        if (!assignments.get(0).getMaster().isActive()) {
            return days.stream()
                    .map(day -> new MasterWorkingDayResponse(day.date(), false))
                    .toList();
        }

        Duration totalDuration = validatedTotalDuration(assignments);
        Map<LocalDate, List<TimeRange>> occupiedByDay = loadOccupiedByDay(masterId, from, to);
        // ONE clock read for the whole projection (Perf LOW-2): today, the horizon and the cutoff all
        // derive from the same Instant, and that same cutoff is threaded down into TimeSlotCalculator so
        // the day verdict and the slot list cannot key off two different "now"s.
        Instant now = kyivClock.instant();
        Instant cutoff = BookingWindow.bookableCutoff(now);
        LocalDate today = LocalDate.ofInstant(now, TimeZones.KYIV);
        LocalDate horizonEnd = today.plusDays(BookingWindow.MAX_DAYS_AHEAD);

        return days.stream()
                .map(day -> new MasterWorkingDayResponse(
                        day.date(),
                        // Past-date fast path (Perf HIGH-2), evaluated before the slot walk: every
                        // candidate on a past date is below the cutoff by definition, so generating them
                        // was pure waste. TODAY is deliberately NOT excluded (isBefore, not isAfter-today).
                        !day.date().isBefore(today)
                                && !day.date().isAfter(horizonEnd)
                                && isDayBookable(day, totalDuration, occupiedByDay, cutoff)))
                .toList();
    }

    /**
     * Caps the {@code serviceId}-PRESENT calendar window at {@value #MAX_BOOKABLE_SPAN_DAYS} days between
     * endpoints (Perf HIGH-1 + security MEDIUM-1).
     *
     * <p>The {@code master-bookable-days} cache is keyed on the RAW client {@code from}/{@code to}. Under
     * the inherited 366-day allowance the valid key space was ~400 000 {@code (from, to)} pairs <em>per
     * (master, service)</em> against a 2 000-entry cache, and every forced miss costs 4 DB queries plus a
     * slot walk over the whole window — so an authenticated client rotating {@code from} by one day per
     * request could evict every legitimate entry and turn a cached read into a sustained DB amplifier.
     * Capping the span attacks the same root cause from the other side: it bounds the COST of a miss and
     * the SIZE of an entry (≤63 records instead of ≤366), which also bounds the cache's retained heap
     * (see {@code CacheConfig}). The per-IP throttle on this route ({@code AuthRateLimitFilter}) bounds the
     * RATE of misses; together the churn a single caller can force stays far below the cache's 60-second
     * TTL, so a hot legitimate entry survives.
     *
     * <p>62 days = two full calendar months. The mobile booking calendar pages month-by-month and never
     * needs more; a wider window is not a legitimate client shape.
     */
    private void assertBookableSpan(LocalDate from, LocalDate to) {
        if (ChronoUnit.DAYS.between(from, to) > MAX_BOOKABLE_SPAN_DAYS) {
            throw new BusinessException(
                    "Date range exceeds the maximum of " + (MAX_BOOKABLE_SPAN_DAYS + 1)
                            + " days when serviceId is supplied");
        }
    }

    // ── Free-slot bookability gate (Phase 23.x — CRITICAL catalogue/master-list fix) ─────────
    //
    // A performing master is "bookable" for a service iff there is ≥1 FREE FUTURE slot: an active
    // assignment on an active master, a usable schedule in the booking window, and a generated slot
    // whose start is ≥ now + MIN_MINUTES_AHEAD after existing CONFIRMED bookings are
    // subtracted. This is the SINGLE verdict shared by the salon catalogue
    // (ServiceCatalogService#getSalonServiceCatalog) and the booking master-list
    // (BookingMasterService#getBookableMasters), so the two can never diverge. The free-slot check
    // reuses the exact same effective-day resolver (MasterScheduleService) and TimeSlotCalculator
    // subtraction that getAvailableSlots uses, differing only in that it stops at the first free
    // future slot instead of materializing every slot.

    /**
     * True iff the {@code (masterId, masterServiceId)} assignment has at least one free future slot in
     * {@code [from, to]} — the per-master, per-service bookability verdict for the booking master-list.
     *
     * <p><b>Thin cached wrapper (Perf #4).</b> On a cache HIT the cached verdict is returned and the
     * method body never runs — the hot master-list path pays nothing. On a cache MISS the assignment is
     * resolved from {@code preloaded} when the caller already holds the JOIN-FETCHed entity
     * ({@code BookingMasterService#getBookableMasters}), avoiding a redundant
     * {@code findByMasterIdAndIdWithGraph} reload; only a caller with no entity (pass {@code null})
     * triggers the reload. {@code preloaded} is deliberately NOT part of the cache key
     * ({@code {#masterId, #masterServiceId, #from, #to}}), so two callers — one with, one without the
     * entity — share the same cached verdict.
     *
     * <p>An inactive/missing assignment or inactive master is not bookable → {@code false} (never an
     * exception): callers treat this as a pure eligibility predicate.
     *
     * <p>Cached in {@code master-service-bookable} (60s TTL, {@code sync=true}) mirroring
     * {@code master-usable-schedule}; evicted by master prefix from every schedule write
     * ({@code MasterScheduleService#evictSlotsAfterCommit}) and every booking write
     * ({@code BookingService}/{@code GuestBookingService}/{@code BookingCancellationService}) via
     * {@link #evictBookableFutureSlotsByMaster}.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = BOOKABLE_CACHE, key = "{#masterId, #masterServiceId, #from, #to}", sync = true)
    public boolean hasBookableFutureSlot(UUID masterId, UUID masterServiceId,
                                         MasterServiceAssignment preloaded, LocalDate from, LocalDate to) {
        MasterServiceAssignment msa = preloaded != null
                ? preloaded
                : masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId).orElse(null);
        return hasBookableFutureSlot(msa, from, to);
    }

    /**
     * Entity-taking core of the free-slot bookability verdict (Perf #4) — no cache, no reload. Shared by
     * the cached {@link #hasBookableFutureSlot(UUID, UUID, MasterServiceAssignment, LocalDate, LocalDate)}
     * wrapper (on a cache miss) and any caller that already holds the assignment. Guards active assignment
     * + active master, resolves the schedule range once and the window's CONFIRMED bookings once
     * (bucketed per day — Perf #3), then short-circuits on the first free slot starting ≥ now +
     * {@code MIN_MINUTES_AHEAD}.
     */
    boolean hasBookableFutureSlot(MasterServiceAssignment msa, LocalDate from, LocalDate to) {
        if (msa == null || !msa.isActive() || !msa.getMaster().isActive()) {
            return false;
        }
        UUID masterId = msa.getMaster().getId();
        List<EffectiveDayResponse> days = masterScheduleService.resolveEffectiveRange(masterId, from, to);
        Map<LocalDate, List<TimeRange>> occupiedByDay = loadOccupiedByDay(masterId, from, to);
        return hasFreeFutureSlot(days, effectiveDuration(msa), occupiedByDay, bookableCutoff());
    }

    /**
     * Batched catalogue gate: given ONE master and its candidate assignments (each with
     * {@code serviceDefinition} initialised), returns the subset that is bookable within the standard
     * booking horizon (today … today + {@code MAX_DAYS_AHEAD}, computed internally so the horizon lives
     * in the booking package). Resolves the master's schedule range ONCE and loads the window's bookings
     * ONCE, then tests each assignment's effective duration against the shared in-memory free-slot walk —
     * so the catalogue does O(distinct masters) heavy loads, not O(services × masters). Not cached (the
     * per-service {@link #hasBookableFutureSlot} cache backs the hot master-list path; the catalogue is
     * the batched path). The verdict is identical to {@link #hasBookableFutureSlot} for the same window.
     */
    @Transactional(readOnly = true)
    public List<MasterServiceAssignment> filterBookableAssignments(
            UUID masterId, List<MasterServiceAssignment> assignments) {
        if (assignments.isEmpty()) {
            return List.of();
        }
        LocalDate from = LocalDate.now(kyivClock);
        LocalDate to = from.plusDays(BookingWindow.MAX_DAYS_AHEAD);
        List<EffectiveDayResponse> days = masterScheduleService.resolveEffectiveRange(masterId, from, to);
        Map<LocalDate, List<TimeRange>> occupiedByDay = loadOccupiedByDay(masterId, from, to);
        Instant cutoff = bookableCutoff();
        List<MasterServiceAssignment> bookable = new ArrayList<>();
        for (MasterServiceAssignment msa : assignments) {
            if (hasFreeFutureSlot(days, effectiveDuration(msa), occupiedByDay, cutoff)) {
                bookable.add(msa);
            }
        }
        return bookable;
    }

    // ── shared internals ────────────────────────────────────────────────────────────────────

    /**
     * Loads the {@code (masterId, masterServiceId)} assignment with its {@code serviceDefinition} graph and
     * asserts it is active. Shared by {@link #getAvailableSlots} (behind {@code GET /masters/{id}/slots})
     * and {@link #getBookableWorkingDays} (behind {@code GET /masters/{id}/working-days?serviceId=…}).
     *
     * <p><b>ONE status for all three failure modes: 404 (security LOW-1).</b> Unknown, foreign and inactive
     * all answer {@code masterService not found}. The finder is already master-scoped, so a foreign service
     * is indistinguishable from a missing one — but the inactive case used to answer 400 ("master service is
     * inactive"), which handed a caller holding a stale id a two-valued oracle: 400 meant "this service is
     * soft-deleted but still attached to THIS master", 404 meant "removed or never existed". A deactivated
     * assignment is, from a booking client's perspective, exactly as absent as one that never existed —
     * so it reports as such, and the three cases become indistinguishable.
     */
    private MasterServiceAssignment loadBookableAssignment(UUID masterId, UUID masterServiceId) {
        MasterServiceAssignment msa = masterServiceRepository
                .findByMasterIdAndIdWithGraph(masterId, masterServiceId)
                .orElseThrow(() -> new NotFoundException("masterService not found"));
        if (!msa.isActive()) {
            throw new NotFoundException("masterService not found");
        }
        return msa;
    }

    /**
     * Resolves each {@code masterServiceId} in order via {@link #loadBookableAssignment}, preserving the
     * caller-supplied ordering (the D4 buffer policy chains the services in that order). Every id is
     * validated with the same unknown/foreign/inactive → 404 semantics as the single-service path, so a
     * chain containing one bad id fails identically to a single bad id. The list is small (bounded by
     * {@link #assertServiceIds}), so the per-id lookups are acceptable.
     */
    private List<MasterServiceAssignment> loadBookableAssignments(UUID masterId, List<UUID> masterServiceIds) {
        List<MasterServiceAssignment> assignments = new ArrayList<>(masterServiceIds.size());
        for (UUID masterServiceId : masterServiceIds) {
            assignments.add(loadBookableAssignment(masterId, masterServiceId));
        }
        return assignments;
    }

    /**
     * Sum of the ordered assignments' effective durations ({@link #effectiveDuration}, which already folds
     * each service's own {@code bufferMinutesAfter} — the D4 policy: buffer applied between chained services
     * and at the tail), guarded by the {@value #MAX_TOTAL_DURATION_MINUTES}-min ceiling. For a 1-element
     * list this equals the single service's effective duration, so the single-service path is unchanged.
     * The duration is always summed server-side from the resolved assignments — a client-supplied duration
     * is never trusted.
     */
    private Duration validatedTotalDuration(List<MasterServiceAssignment> assignments) {
        long totalMinutes = assignments.stream()
                .mapToLong(msa -> effectiveDuration(msa).toMinutes())
                .sum();
        if (totalMinutes > MAX_TOTAL_DURATION_MINUTES) {
            throw new BusinessException("total service duration exceeds maximum allowed");
        }
        return Duration.ofMinutes(totalMinutes);
    }

    /**
     * Boundary guard for the ordered service-id list shared by the multi-service slot list and calendar
     * day-gate: non-empty and within {@value #MAX_SERVICES_PER_VISIT}. Defensive second line behind the
     * controller's {@code @NotEmpty}/{@code @Size} — the service must never trust an empty or unbounded
     * list (an empty list would NPE on {@code assignments.get(0)}; an unbounded one is a slot-calculator
     * amplifier).
     */
    private void assertServiceIds(List<UUID> masterServiceIds) {
        if (masterServiceIds == null || masterServiceIds.isEmpty()) {
            throw new BusinessException("at least one serviceId is required");
        }
        if (masterServiceIds.size() > MAX_SERVICES_PER_VISIT) {
            throw new BusinessException(
                    "at most " + MAX_SERVICES_PER_VISIT + " services can be booked in a single visit");
        }
    }

    /** Effective service duration + buffer (override beats base). Shared by the slot list and the gate. */
    private Duration effectiveDuration(MasterServiceAssignment msa) {
        int durationMinutes = msa.getDurationOverrideMinutes() != null
                ? msa.getDurationOverrideMinutes()
                : msa.getServiceDefinition().getBaseDurationMinutes();
        return Duration.ofMinutes(durationMinutes + msa.getServiceDefinition().getBufferMinutesAfter());
    }

    /**
     * Union of free slots across a day's resolved work intervals (extracted from getAvailableSlots
     * Step 8). {@code occupied} must already be narrowed to the target date's window; {@code cutoff} is the
     * request's single lead-time floor (Perf LOW-2 — never re-derived per interval).
     */
    private List<TimeRange> computeDayFreeRanges(
            LocalDate date, List<WorkIntervalDto> intervals, Duration totalDuration,
            List<TimeRange> occupied, Instant cutoff) {
        List<TimeRange> result = new ArrayList<>();
        for (WorkIntervalDto interval : intervals) {
            result.addAll(timeSlotCalculator.calculateAvailableSlots(
                    date, interval.startTime(), interval.endTime(), totalDuration, SLOT_STEP, occupied,
                    cutoff));
        }
        return result;
    }

    /**
     * Walks {@code days} ascending and returns {@code true} at the first free slot starting at/after
     * {@code cutoff}. Reuses {@link #computeDayFreeRanges} (the same subtraction getAvailableSlots uses),
     * so the gate's verdict cannot drift from the slot list beyond the {@code cutoff} lead-time floor.
     *
     * <p><b>Perf #3.</b> {@code occupiedByDay} is the master's window bookings pre-bucketed by Kyiv-civil
     * date ({@link #loadOccupiedByDay}) so each walked day indexes its slice in O(1) instead of re-scanning
     * the whole ≤180-day booking list per day (the previous O(days × bookings), worst for a fully-booked
     * master's whole-horizon walk and repeated per assignment in the catalogue).
     */
    private boolean hasFreeFutureSlot(
            List<EffectiveDayResponse> days, Duration totalDuration,
            Map<LocalDate, List<TimeRange>> occupiedByDay, Instant cutoff) {
        for (EffectiveDayResponse day : days) {
            if (isDayBookable(day, totalDuration, occupiedByDay, cutoff)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>THE single per-day bookability predicate.</b> True iff {@code day}'s resolved schedule leaves at
     * least one free range that fits {@code totalDuration} and starts at/after {@code cutoff}, once the
     * day's CONFIRMED bookings are subtracted.
     *
     * <p>Three consumers share it and therefore cannot disagree: the calendar day projection
     * ({@link #getBookableWorkingDays}, one call per date), the short-circuiting bookability gate
     * ({@link #hasFreeFutureSlot} → catalogue + booking master-list), and — through the same
     * {@link TimeSlotCalculator} walk with the same {@code cutoff} — the slot list itself
     * ({@link #getAvailableSlots}, which differs only in materialising every slot rather than stopping at
     * the first).
     *
     * <p><b>Existence, not materialisation (Perf MEDIUM-4).</b> This asks
     * {@link TimeSlotCalculator#hasAvailableSlot}, which returns at the FIRST bookable candidate, and stops
     * walking intervals as soon as one day-interval answers true. It previously built every free slot for
     * the day (up to 6 intervals × ~48 candidates), scanned for the first at/after {@code cutoff}, and
     * discarded the rest — ~48× the allocations for a boolean. The cutoff is now applied INSIDE the walk
     * (one floor, the caller's), so the post-filter loop that re-asserted it is gone.
     */
    private boolean isDayBookable(
            EffectiveDayResponse day, Duration totalDuration,
            Map<LocalDate, List<TimeRange>> occupiedByDay, Instant cutoff) {
        List<WorkIntervalDto> intervals = day.intervals();
        if (intervals == null || intervals.isEmpty()) {
            return false;
        }
        List<TimeRange> occupied = occupiedByDay.getOrDefault(day.date(), List.of());
        for (WorkIntervalDto interval : intervals) {
            if (timeSlotCalculator.hasAvailableSlot(
                    day.date(), interval.startTime(), interval.endTime(), totalDuration, SLOT_STEP,
                    occupied, cutoff)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Loads the master's CONFIRMED bookings across {@code [from, to]} in ONE query and buckets
     * them by Kyiv-civil date (Perf #3) so the day-walk indexes each day's slice directly. Window bounded
     * by the caller's ≤180-day booking horizon (§E-3). A booking is added to every civil day it overlaps
     * ({@code [dateOf(start) .. dateOf(end − ε)]}), reproducing the previous per-day overlap test
     * ({@code o.start < dayEnd && o.end > dayStart}) exactly — {@code end} is exclusive, so a booking that
     * ends precisely at midnight does not occupy the next day.
     */
    private Map<LocalDate, List<TimeRange>> loadOccupiedByDay(UUID masterId, LocalDate from, LocalDate to) {
        OffsetDateTime windowStart = from.atStartOfDay(TimeZones.KYIV).toOffsetDateTime();
        OffsetDateTime windowEnd = to.plusDays(1).atStartOfDay(TimeZones.KYIV).toOffsetDateTime();
        Map<LocalDate, List<TimeRange>> byDay = new HashMap<>();
        // Two-column projection, not managed entities (Perf MEDIUM-1) — see BookingTimeRange.
        for (BookingTimeRange b : bookingRepository
                .findActiveTimeRangesByMasterInRange(masterId, windowStart, windowEnd)) {
            TimeRange range = new TimeRange(b.startsAt().toInstant(), b.endsAt().toInstant());
            LocalDate firstDay = LocalDate.ofInstant(range.start(), TimeZones.KYIV);
            // end is exclusive: a booking ending exactly at 00:00 does not occupy the day it touches.
            LocalDate lastDay = LocalDate.ofInstant(range.end().minusNanos(1), TimeZones.KYIV);
            for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
                byDay.computeIfAbsent(d, k -> new ArrayList<>()).add(range);
            }
        }
        return byDay;
    }

    /**
     * Earliest instant a slot may start to be genuinely bookable. Delegates to
     * {@link BookingWindow#bookableCutoff(Clock)} — the SAME floor {@code BookingStartsAtValidator}
     * enforces on booking create and {@link TimeSlotCalculator#calculateAvailableSlots} applies when
     * generating the slot list, so what the API offers is exactly what it accepts.
     */
    private Instant bookableCutoff() {
        return BookingWindow.bookableCutoff(kyivClock);
    }

    // ── cache eviction ──────────────────────────────────────────────────────────────────────

    // NOT_SUPPORTED: eviction must not run inside the caller's transaction — it fires after the
    // surrounding transaction suspends so the cache is only invalidated independently of commit/rollback.
    // BookingService must call this from a TransactionSynchronization.afterCommit() callback.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @CacheEvict(value = "available-slots", key = "{#masterId, #date, #masterServiceId}")
    public void evictAvailableSlots(UUID masterId, LocalDate date, UUID masterServiceId) {}

    /**
     * Evicts one master's booking-availability caches by master prefix, after commit — both the
     * {@code master-service-bookable} verdict ({@link #hasBookableFutureSlot}) and the
     * {@code master-bookable-days} calendar projection ({@link #getBookableWorkingDays}). Both are keyed by
     * a SpEL inline-list whose FIRST element is the masterId ({@code {#masterId, #masterServiceId, #from,
     * #to}} and {@code {#masterId, #from, #to, #masterServiceId}} — a {@link List} at runtime), so the
     * window portion cannot be evicted per-date: a booking write anywhere in the master's horizon can flip
     * any window's verdict and any day's availability. We therefore evict every key whose first element is
     * this master (bounded to one master, not blanket). Mirrors
     * {@code MasterScheduleService#evictByMasterPrefix}.
     *
     * <p>Called from the booking-write {@code afterCommit} hooks ({@code BookingService},
     * {@code GuestBookingService}, {@code BookingCancellationService}), from {@code MasterService} on
     * master (de)activation, and from {@code ServiceCatalogService} on service-definition mutations.
     * Schedule writes evict the same two caches from {@code MasterScheduleService#evictSlotsAfterCommit}.
     *
     * <p>The keyset scan itself now runs on the {@code cacheEvictionExecutor}, off the committing request
     * thread (Perf MEDIUM-3) — see {@link MasterCachePrefixEvictor}. Callers are unchanged: they still
     * invoke this from {@code afterCommit}, so eviction can only ever happen AFTER the write is visible.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void evictBookableFutureSlotsByMaster(UUID masterId) {
        cacheEvictor.evictByMasterPrefix(masterId, BOOKABLE_CACHE, BOOKABLE_DAYS_CACHE);
    }
}
