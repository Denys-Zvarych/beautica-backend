package com.beautica.booking.service;

import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.util.TimeSlotCalculator;
import com.beautica.common.util.TimeSlotCalculator.TimeRange;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.service.MasterScheduleService;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.repository.MasterServiceRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SlotCalculationService {

    private static final Duration SLOT_STEP = Duration.ofMinutes(30);
    private static final String BOOKABLE_CACHE = "master-service-bookable";

    private final BookingRepository bookingRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final MasterScheduleService masterScheduleService;
    private final TimeSlotCalculator timeSlotCalculator;
    private final CacheManager cacheManager;
    private final Clock kyivClock;

    public SlotCalculationService(
            BookingRepository bookingRepository,
            MasterServiceRepository masterServiceRepository,
            MasterScheduleService masterScheduleService,
            TimeSlotCalculator timeSlotCalculator,
            CacheManager cacheManager,
            Clock clock) {
        this.bookingRepository = bookingRepository;
        this.masterServiceRepository = masterServiceRepository;
        this.masterScheduleService = masterScheduleService;
        this.timeSlotCalculator = timeSlotCalculator;
        this.cacheManager = cacheManager;
        this.kyivClock = clock.withZone(TimeZones.KYIV);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "available-slots", key = "{#masterId, #date, #masterServiceId}", sync = true)
    public List<AvailableSlotResponse> getAvailableSlots(UUID masterId, LocalDate date, UUID masterServiceId) {
        // Step 1: date range validation — cheapest guard, no DB
        LocalDate today = LocalDate.now(kyivClock);
        if (date.isBefore(today)) {
            throw new BusinessException("date is in the past");
        }
        if (date.isAfter(today.plusDays(180))) {
            throw new BusinessException("date too far ahead");
        }

        // Step 2: load master service — validated first to close the working-hours oracle
        MasterServiceAssignment msa = masterServiceRepository
                .findByMasterIdAndIdWithGraph(masterId, masterServiceId)
                .orElseThrow(() -> new NotFoundException("masterService not found"));

        if (!msa.isActive()) {
            throw new BusinessException("master service is inactive");
        }

        // Guard: master must be active to expose any bookable slots.
        // deactivateOwnerMaster (and the general deactivateMaster) sets masters.is_active = false
        // but leaves master_services rows intact — check the master entity itself here.
        if (!msa.getMaster().isActive()) {
            return List.of();
        }

        // Step 3: compute effective duration (override takes precedence over base)
        Duration totalDuration = effectiveDuration(msa);

        // Step 4: upper-bound guard — durationOverride max 480 min + bufferMinutesAfter max 120 min
        if (totalDuration.toMinutes() > 600) {
            throw new BusinessException("total service duration exceeds maximum allowed");
        }

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

        // Step 7: load existing bookings that overlap the day window (PENDING + CONFIRMED only).
        // Loaded once for the whole day and subtracted from every interval below.
        List<TimeRange> occupied = bookingRepository
                .findOverlappingByMaster(masterId, dayStart, dayEnd)
                .stream()
                .map(b -> new TimeRange(b.getStartsAt().toInstant(), b.getEndsAt().toInstant()))
                .toList();

        // Step 8: generate candidate slots per resolved interval and union the results (shared with
        // the free-slot bookability gate — see computeDayFreeRanges). Calling TimeSlotCalculator once
        // per interval is the multi-interval generalization of the legacy single-window call — gaps
        // between intervals (lunch breaks) naturally yield no slots.
        return computeDayFreeRanges(date, intervals, totalDuration, occupied).stream()
                .map(r -> new AvailableSlotResponse(
                        r.start().atZone(TimeZones.KYIV),
                        r.end().atZone(TimeZones.KYIV)))
                .toList();
    }

    // ── Free-slot bookability gate (Phase 23.x — CRITICAL catalogue/master-list fix) ─────────
    //
    // A performing master is "bookable" for a service iff there is ≥1 FREE FUTURE slot: an active
    // assignment on an active master, a usable schedule in the booking window, and a generated slot
    // whose start is ≥ now + MIN_MINUTES_AHEAD after existing PENDING/CONFIRMED bookings are
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
     * + active master, resolves the schedule range once and the window's PENDING/CONFIRMED bookings once
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
        LocalDate to = from.plusDays(BookingStartsAtValidator.MAX_DAYS_AHEAD);
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

    /** Effective service duration + buffer (override beats base). Shared by the slot list and the gate. */
    private Duration effectiveDuration(MasterServiceAssignment msa) {
        int durationMinutes = msa.getDurationOverrideMinutes() != null
                ? msa.getDurationOverrideMinutes()
                : msa.getServiceDefinition().getBaseDurationMinutes();
        return Duration.ofMinutes(durationMinutes + msa.getServiceDefinition().getBufferMinutesAfter());
    }

    /**
     * Union of free slots across a day's resolved work intervals (extracted from getAvailableSlots
     * Step 8). {@code occupied} must already be narrowed to the target date's window.
     */
    private List<TimeRange> computeDayFreeRanges(
            LocalDate date, List<WorkIntervalDto> intervals, Duration totalDuration, List<TimeRange> occupied) {
        List<TimeRange> result = new ArrayList<>();
        for (WorkIntervalDto interval : intervals) {
            result.addAll(timeSlotCalculator.calculateAvailableSlots(
                    date, interval.startTime(), interval.endTime(), totalDuration, SLOT_STEP, occupied));
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
            List<WorkIntervalDto> intervals = day.intervals();
            if (intervals == null || intervals.isEmpty()) {
                continue;
            }
            List<TimeRange> free = computeDayFreeRanges(
                    day.date(), intervals, totalDuration,
                    occupiedByDay.getOrDefault(day.date(), List.of()));
            for (TimeRange r : free) {
                if (!r.start().isBefore(cutoff)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Loads the master's PENDING/CONFIRMED bookings across {@code [from, to]} in ONE query and buckets
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
        for (var b : bookingRepository.findActiveByMasterInRange(masterId, windowStart, windowEnd)) {
            TimeRange range = new TimeRange(b.getStartsAt().toInstant(), b.getEndsAt().toInstant());
            LocalDate firstDay = LocalDate.ofInstant(range.start(), TimeZones.KYIV);
            // end is exclusive: a booking ending exactly at 00:00 does not occupy the day it touches.
            LocalDate lastDay = LocalDate.ofInstant(range.end().minusNanos(1), TimeZones.KYIV);
            for (LocalDate d = firstDay; !d.isAfter(lastDay); d = d.plusDays(1)) {
                byDay.computeIfAbsent(d, k -> new ArrayList<>()).add(range);
            }
        }
        return byDay;
    }

    /** Earliest instant a slot may start to be genuinely bookable (mirrors BookingStartsAtValidator's floor). */
    private Instant bookableCutoff() {
        return kyivClock.instant().plus(Duration.ofMinutes(BookingStartsAtValidator.MIN_MINUTES_AHEAD));
    }

    // ── cache eviction ──────────────────────────────────────────────────────────────────────

    // NOT_SUPPORTED: eviction must not run inside the caller's transaction — it fires after the
    // surrounding transaction suspends so the cache is only invalidated independently of commit/rollback.
    // BookingService must call this from a TransactionSynchronization.afterCommit() callback.
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @CacheEvict(value = "available-slots", key = "{#masterId, #date, #masterServiceId}")
    public void evictAvailableSlots(UUID masterId, LocalDate date, UUID masterServiceId) {}

    /**
     * Evicts the {@code master-service-bookable} entries for one master by master prefix, after commit.
     * The cache key is the SpEL inline-list {@code {#masterId, #masterServiceId, #from, #to}} (a
     * {@link List} at runtime), so the window portion cannot be evicted per-date — a booking write
     * anywhere in the master's horizon can flip any window's verdict, so we evict every key whose first
     * element is this master (bounded to one master, not blanket). Mirrors
     * {@code MasterScheduleService#evictByMasterPrefix}. Called from the booking-write {@code afterCommit}
     * hooks; schedule writes evict the same cache from {@code MasterScheduleService}.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void evictBookableFutureSlotsByMaster(UUID masterId) {
        Cache springCache = cacheManager.getCache(BOOKABLE_CACHE);
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
