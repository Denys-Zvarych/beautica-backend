package com.beautica.booking.service;

import com.beautica.booking.domain.MasterBookability;
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
import java.util.Optional;
import java.util.UUID;

@Service
public class SlotCalculationService {

    private static final Duration SLOT_STEP = Duration.ofMinutes(30);
    private static final String SLOTS_CACHE = "available-slots";
    private static final String BOOKABLE_CACHE = "master-service-bookable";
    private static final String BOOKABLE_DAYS_CACHE = "master-bookable-days";

    /**
     * The three per-master availability caches a BOOKING write invalidates, all evicted together by
     * master prefix — see {@link #evictMasterAvailabilityCaches}.
     *
     * <p>Deliberately the same set, and the same technique, that a SCHEDULE write already sweeps via
     * {@code MasterScheduleService#SCHEDULE_WRITE_CACHES}; that array additionally carries
     * {@code master-working-days} / {@code master-usable-schedule}, which are pure functions of the
     * schedule shape and so cannot be moved by a booking.
     */
    private static final String[] BOOKING_WRITE_CACHES = {
            SLOTS_CACHE,
            BOOKABLE_CACHE,
            BOOKABLE_DAYS_CACHE,
    };

    /**
     * durationOverride max (480 min) + bufferMinutesAfter max (120 min) — see the DTO validation matrix.
     *
     * <p><b>Public since BE-3:</b> the appointment (multi-service single-visit) create path
     * ({@code AppointmentService}) enforces the identical Σ-duration ceiling on the chained block it
     * persists, so it references THIS constant rather than re-declaring the literal — the create cap
     * and the availability cap can never drift apart. Visibility only; the value/semantics are
     * unchanged from BE-2.
     */
    public static final int MAX_TOTAL_DURATION_MINUTES = 600;

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
     * same eviction (the by-master {@link #evictMasterAvailabilityCaches} sweep) it always had.
     *
     * <p>Delegates to the N-service core {@link #computeAvailableSlots} with a 1-element list; the summed
     * duration of a 1-element list is exactly that service's effective duration, so the output is identical
     * to the pre-BE-2 implementation.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = SLOTS_CACHE, key = "{#masterId, #date, #masterServiceId}", sync = true)
    public List<AvailableSlotResponse> getAvailableSlots(UUID masterId, LocalDate date, UUID masterServiceId) {
        return computeAvailableSlots(masterId, date, List.of(masterServiceId), null);
    }

    /**
     * <b>Preloaded-assignment single-service slot list (Perf MEDIUM, 2026-08-11)</b> — same contract, same
     * cache and the SAME cache key as the three-arg overload above, plus the assignment the caller already
     * holds.
     *
     * <p><b>Why.</b> The two single-service CREATE paths — {@code BookingService#doCreateBooking} and
     * {@code GuestBookingService#createSingleServiceBooking} — resolve the assignment with
     * {@code findByMasterIdAndIdWithGraph} before they reach the schedule-fit gate, and the gate's
     * availability read then issued the IDENTICAL finder a second time through
     * {@link #loadBookableAssignments}. Same persistence context, same instance — a pure SQL round trip for
     * nothing, paid on every create that misses the {@code available-slots} cache.
     *
     * <p><b>Exactly the {@code hasBookableFutureSlot(…, preloaded, …)} pattern</b>
     * ({@link #hasBookableFutureSlot(UUID, UUID, MasterServiceAssignment, LocalDate, LocalDate)}):
     * {@code preloaded} is deliberately NOT part of the cache key, so this overload and the three-arg one
     * share one cache entry per {@code (masterId, date, masterServiceId)} and a create can be served from
     * (and can populate) the very entry {@code GET /masters/{id}/slots} produced.
     *
     * <p><b>Caller contract: a MANAGED instance loaded in THIS transaction.</b> {@link #matchesRequest}
     * verifies IDENTITY only — assignment id, owning master, {@code isActive}. It does NOT compare
     * {@code durationOverrideMinutes}, {@code serviceDefinition.baseDurationMinutes} or
     * {@code serviceDefinition.bufferMinutesAfter} — precisely the three fields {@link #effectiveDuration}
     * reads to size every slot. A verified {@code preloaded} is therefore trusted for its DURATION, not
     * merely for its identity: passing "any assignment with the right id" is NOT safe.
     *
     * <p><b>Why it is nonetheless safe today.</b> All four producers load through
     * {@code MasterServiceRepository#findByMasterIdAndIdWithGraph} inside the SAME {@code @Transactional} as
     * the guard that calls this — {@code BookingService#doCreateBooking} ({@code BookingService}:1827 →
     * :1896) and {@code GuestBookingService#createSingleServiceBooking} ({@code GuestBookingService}:214 →
     * :228) for this overload; {@code AppointmentService#doCreateAppointment} and
     * {@code GuestBookingService#createGuestVisit} through the shared {@code VisitPlanner#planChainedItems}
     * ({@code VisitPlanner}:73) for the list overload below. No caller mutates duration or buffer, and the
     * {@link #loadBookableAssignments} fallback re-queries the SAME persistence context, so Hibernate returns
     * the identical managed instance — the fallback is not one bit fresher. In-transaction divergence is
     * impossible, so nothing wrong can be computed and nothing wrong can be cached.
     *
     * <p><b>The sharp edge, for whoever adds the fifth caller.</b> {@code preloaded} is NOT part of the cache
     * key (deliberately — see above). A caller handing in a DETACHED or SYNTHETIC assignment that merely
     * carries the right id, master and active flag would satisfy {@link #matchesRequest}, size the slot list
     * from ITS stale duration, and have that list CACHED and served to every other caller under a key that
     * never mentions it. Pass a managed instance loaded in this transaction, or pass {@code null}. Two
     * bounding notes so this reads precisely rather than alarmingly: the multi-service {@code preloaded}
     * overload is NOT {@code @Cacheable}, so this cache exposure is single-service only; and the key's
     * silence about duration is pre-existing on the three-arg overload above, which has always keyed on
     * {@code masterServiceId} alone.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = SLOTS_CACHE, key = "{#masterId, #date, #masterServiceId}", sync = true)
    public List<AvailableSlotResponse> getAvailableSlots(
            UUID masterId, LocalDate date, UUID masterServiceId, MasterServiceAssignment preloaded) {
        return computeAvailableSlots(masterId, date, List.of(masterServiceId),
                preloaded != null ? List.of(preloaded) : null);
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
        return computeAvailableSlots(masterId, date, masterServiceIds, null);
    }

    /**
     * <b>Preloaded-assignment multi-service slot list (Perf MEDIUM, 2026-08-11)</b> — the N-service twin of
     * {@link #getAvailableSlots(UUID, LocalDate, UUID, MasterServiceAssignment)}, for the two visit CREATE
     * paths ({@code AppointmentService#doCreateAppointment} and {@code GuestBookingService#createGuestVisit})
     * whose shared {@code VisitPlanner#planChainedItems} has already run {@code findByMasterIdAndIdWithGraph}
     * once per chained service. Without this the gate re-ran that finder N more times, so a 4-service
     * {@code POST /appointments} issued 8 assignment queries where 4 suffice (bounded by
     * {@value #MAX_SERVICES_PER_VISIT}, so never an unbounded N+1 — just avoidable on every create).
     *
     * <p>{@code preloaded} must be PARALLEL to {@code masterServiceIds} (same size, same order) and — like
     * its single-service twin — must be MANAGED instances loaded in THIS transaction. {@link #matchesRequest}
     * verifies id / master / active element-wise and NOT the duration or buffer fields
     * {@link #effectiveDuration} sums, so a verified list is trusted for its Σ duration; see the
     * single-service overload's Javadoc for the full invariant and why it holds. The only producer is
     * {@code VisitPlanner#planChainedItems} ({@code VisitPlanner}:73), on behalf of
     * {@code AppointmentService#doCreateAppointment} and {@code GuestBookingService#createGuestVisit}, both
     * inside the create transaction. This overload is UNCACHED (same reason the three-arg list overload is —
     * see its Javadoc), so a bad {@code preloaded} here could only corrupt the one response, never a cache
     * entry other callers are served from.
     */
    @Transactional(readOnly = true)
    public List<AvailableSlotResponse> getAvailableSlots(
            UUID masterId, LocalDate date, List<UUID> masterServiceIds,
            List<MasterServiceAssignment> preloaded) {
        assertServiceIds(masterServiceIds);
        return computeAvailableSlots(masterId, date, masterServiceIds, preloaded);
    }

    /**
     * <b>STAFF create-path slot list (Phase 22.2)</b> — the same single-service list as
     * {@link #getAvailableSlots(UUID, LocalDate, UUID, MasterServiceAssignment)} with ONE difference:
     * the lead-time floor is {@link Duration#ZERO} instead of {@link BookingWindow#minLead()}, so a
     * slot starting at or after "now" is offered rather than one starting at or after "now + 15 min".
     *
     * <p><b>Why it must exist.</b> The staff create path proves schedule-fit exactly the way every
     * other create path does — by requiring {@code startsAt} to MATCH a slot this service generates
     * ({@link BookingSlotAvailabilityGuard}). The STAFF lead-time rule is "now is allowed, the past
     * is not" ({@code BookingStartsAtValidator#validateStaff}), so with the 15-minute floor still
     * baked into the generator a walk-in happening now would pass the lead-time check and then be
     * rejected by the schedule-fit check for a reason that has nothing to do with the schedule.
     * The two floors move together or the path contradicts itself — the exact class of bug
     * {@link BookingWindow} was created to prevent.
     *
     * <p><b>Deliberately NOT {@code @Cacheable}.</b> A different floor is a different answer, and the
     * {@code available-slots} key ({@code masterId, date, masterServiceId}) does not mention the
     * floor. Sharing that key would let one staff read populate the entry every client-facing
     * {@code GET /masters/{id}/slots} is served from, offering self-service clients slots inside the
     * 15-minute window they are then rejected for booking. Staff creates are rare (a human typing a
     * form) so the uncached read costs nothing worth the risk. Same reasoning as the multi-service
     * overloads above, for a different reason.
     *
     * <p>{@code preloaded} carries the caller's already-JOIN-FETCHed assignment exactly as the cached
     * overload does; the same "managed instance loaded in THIS transaction" contract applies, and
     * since this method caches nothing, a bad one could only corrupt its own response.
     *
     * <h2>CONTRACT — <b>never wire this into a client-facing controller</b> (security LOW, 2026-08-18)</h2>
     * This is a {@code public} method on an injectable {@code @Service} (it must be public for the
     * {@code @Transactional} proxy to apply — package-private would silently disable it), so nothing
     * in the type system stops a future {@code @RestController} from {@code @Autowired}-ing
     * {@code SlotCalculationService} and returning this list. Doing so would offer <b>self-service
     * clients slots inside the 15-minute lead-time window they are then 400'd for booking</b> — the
     * exact "the API offers what it will not accept" defect {@link BookingWindow} exists to prevent.
     *
     * <p>Permitted callers: the STAFF create path's schedule-fit gate
     * ({@link #isStaffSlotAvailable}, which is what {@link BookingSlotAvailabilityGuard} actually
     * uses), the equivalence assertions that prove the two agree, and — when Phase 22.4/22.5 ships
     * one — a staff-only endpoint behind
     * {@code hasAnyRole('SALON_OWNER','SALON_ADMIN','INDEPENDENT_MASTER')} plus
     * {@code @authz.canBookForMaster}. Never {@code permitAll}, never a CLIENT-reachable route.
     * {@code SlotCalculationServiceTest#should_haveNoControllerCaller_when_scanningForGetStaffAvailableSlots}
     * enforces the negative half of that by scanning the source tree.
     */
    @Transactional(readOnly = true)
    public List<AvailableSlotResponse> getStaffAvailableSlots(
            UUID masterId, LocalDate date, UUID masterServiceId, MasterServiceAssignment preloaded) {
        return computeAvailableSlots(masterId, date, List.of(masterServiceId),
                preloaded != null ? List.of(preloaded) : null, Duration.ZERO);
    }

    /**
     * <b>STAFF schedule-fit EXISTENCE check (perf LOW, 2026-08-18)</b> — "is {@code startsAt} one of
     * the slots {@link #getStaffAvailableSlots} would offer for this master + service on this date?",
     * answered without materialising that list.
     *
     * <p><b>Identical verdict, by construction.</b> It runs the same prologue
     * ({@link #resolveDayAvailability}: date-range guards, assignment resolution and its 404s,
     * {@link MasterBookability} gate, summed duration, effective-day resolution, occupancy load) and
     * the same {@link TimeSlotCalculator} walk at the same {@link Duration#ZERO} floor; the only
     * difference is that the start-instant comparison happens INSIDE the per-interval loop
     * ({@link #dayFreeRangesContainStart}) instead of over a fully-built list, so it returns as soon
     * as a work interval contains the start and never allocates an {@link AvailableSlotResponse} at
     * all. The comparison is on the {@link java.time.Instant}, exactly as
     * {@code BookingSlotAvailabilityGuard}'s {@link OffsetDateTime#isEqual} membership test was — the
     * caller's offset/zone representation is irrelevant to both.
     *
     * <p><b>The exit is BETWEEN intervals, never within one.</b> Each interval is still handed to
     * {@link TimeSlotCalculator#calculateAvailableSlots}, which walks that interval's whole grid and
     * materialises every accepted {@code TimeRange} before this method scans it — so what is saved is
     * the {@link AvailableSlotResponse} mapping plus the intervals after the matching one, not the
     * per-interval candidate walk. A genuine within-interval exit would need a target-start variant of
     * {@code TimeSlotCalculator}'s walk, and leaving that class untouched is the deliberate
     * correctness-first trade: it is the single grid oracle the CLIENT paths are proved against, and
     * forking its walk to shave allocations on the staff create path would put the two at risk of
     * disagreeing about which starts exist.
     *
     * <p><b>Why it is worth having.</b> The client create path amortises its whole-day slot list
     * through the {@code available-slots} cache, so building it once serves many requests. The staff
     * list is deliberately uncached (a different lead-time floor under the same key would poison the
     * client-facing entry — see {@link #getStaffAvailableSlots}), so the staff create path paid for a
     * whole day's {@code AvailableSlotResponse} objects plus two {@code ZonedDateTime}s each, on
     * every single create, purely to answer a boolean, with zero reuse. ({@link #SLOT_STEP} is 30
     * minutes and a day's work intervals are disjoint, so a Kyiv day holds at most 48 grid positions
     * in total; a realistic 9-12h working day yields 17-24.)
     *
     * <p>{@code preloaded} follows the same "managed instance loaded in THIS transaction" contract as
     * every other {@code preloaded} overload. This method caches nothing.
     *
     * @return {@code true} iff a generated staff slot starts at exactly {@code startsAt}
     */
    @Transactional(readOnly = true)
    public boolean isStaffSlotAvailable(
            UUID masterId, LocalDate date, UUID masterServiceId, MasterServiceAssignment preloaded,
            OffsetDateTime startsAt) {
        return resolveDayAvailability(masterId, date, List.of(masterServiceId),
                preloaded != null ? List.of(preloaded) : null)
                .map(day -> dayFreeRangesContainStart(
                        date, day.effective(), day.totalDuration(), day.occupied(),
                        // STAFF floor: minimum lead 0, so the cutoff IS the request's single `now`.
                        day.now(), startsAt.toInstant()))
                .orElse(false);
    }

    /**
     * Shared slot-list core for both the single-service and multi-service entry points, at the
     * standard {@link BookingWindow#minLead()} lead-time floor — every read path and every create
     * path except STAFF.
     *
     * <p>{@code preloaded} is the caller's already-resolved assignment list ({@code null} on every read
     * path) — see {@link #resolveAssignments}.
     */
    private List<AvailableSlotResponse> computeAvailableSlots(
            UUID masterId, LocalDate date, List<UUID> masterServiceIds,
            List<MasterServiceAssignment> preloaded) {
        return computeAvailableSlots(masterId, date, masterServiceIds, preloaded, BookingWindow.minLead());
    }

    /**
     * Shared slot-list core. Identical to the pre-BE-2 single-service body except that the block
     * length is the SUM of the ordered assignments' effective durations
     * ({@link #validatedTotalDuration}) rather than one service's.
     *
     * <p>{@code minLead} is the lead-time floor applied to every generated candidate:
     * {@link BookingWindow#minLead()} for every caller except the STAFF create path, which passes
     * {@link Duration#ZERO} (see {@link #getStaffAvailableSlots}). It is the ONLY parameter that can
     * change the verdict between those two entry points — the effective-day resolution, the master
     * bookability gate, the duration arithmetic and the occupancy subtraction are all shared
     * verbatim, so the staff list can never disagree with the client list about anything other than
     * the floor.
     */
    private List<AvailableSlotResponse> computeAvailableSlots(
            UUID masterId, LocalDate date, List<UUID> masterServiceIds,
            List<MasterServiceAssignment> preloaded, Duration minLead) {
        return resolveDayAvailability(masterId, date, masterServiceIds, preloaded)
                .map(day -> computeDayFreeRanges(
                        date, day.effective(), day.totalDuration(), day.occupied(),
                        day.now().plus(minLead))
                        .stream()
                        .map(r -> new AvailableSlotResponse(
                                r.start().atZone(TimeZones.KYIV),
                                r.end().atZone(TimeZones.KYIV)))
                        .toList())
                .orElseGet(List::of);
    }

    /**
     * Everything a day's slot answer needs before the {@link TimeSlotCalculator} walk itself: the
     * request's single {@code now}, the summed effective duration, the resolved effective day and
     * that day's CONFIRMED occupancy.
     *
     * <p><b>Extracted, not rewritten (perf LOW, 2026-08-18).</b> This is the former prologue of
     * {@link #computeAvailableSlots}, moved verbatim so that the materialising slot list
     * ({@link #computeAvailableSlots}) and the STAFF existence check
     * ({@link #isStaffSlotAvailable}) share ONE implementation of every guard, every 404 and every
     * load. The two therefore cannot drift on WHICH requests are valid, only on what they do with the
     * free ranges afterwards.
     *
     * @return empty when the day yields no slots for a non-error reason — an unbookable master or a
     *         day with no work intervals. Genuine input errors still throw from here exactly as they
     *         did before (past/too-far date → 400; unknown, foreign or inactive assignment → 404).
     */
    private Optional<DayAvailability> resolveDayAvailability(
            UUID masterId, LocalDate date, List<UUID> masterServiceIds,
            List<MasterServiceAssignment> preloaded) {
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
        //
        // A CREATE path hands its already-JOIN-FETCHed assignments in via `preloaded` (Perf MEDIUM,
        // 2026-08-11) and skips the reload entirely — see #resolveAssignments.
        List<MasterServiceAssignment> assignments =
                resolveAssignments(masterId, masterServiceIds, preloaded);

        // Guard: master must be BOOKABLE to expose any slots. All chained assignments share the same
        // master (they are loaded master-scoped), so the first one's master carries the liveness flag.
        // deactivateOwnerMaster (and the general deactivateMaster) sets masters.is_active = false
        // but leaves master_services rows intact — check the master entity itself here.
        //
        // The salon term (MasterBookability, 2026-08 re-audit LOW) stops a closed salon's master from
        // listing slots the create path would then reject: an empty list beats a live CTA that 404s on
        // tap.
        //
        // For the CREATE paths this guard is only that dead-CTA fix — they re-check MasterBookability
        // themselves. For the three RESCHEDULE routes it IS the security boundary, and the only one:
        // BookingService#rescheduleBooking, AppointmentTransitionService#rescheduleAppointment and
        // #rescheduleAppointmentItem all authorize a start time by requiring it to MATCH a slot this
        // method returns, and never call MasterBookability directly. Returning an empty list here is
        // what stops a client moving a live booking onto a master whose salon has since closed —
        // so do NOT weaken or relocate this check without giving those three their own guard
        // (MasterBookability's javadoc, "Derivative enforcers").
        //
        // Free: findByMasterIdAndIdWithGraph LEFT JOIN FETCHes salon.
        if (!MasterBookability.isBookable(assignments.get(0).getMaster())) {
            return Optional.empty();
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
        //
        // Phase 15.8/15.9 EXPLICIT_TIMES days are answered from their DECLARED times, never from the
        // derived [min..max] window the resolver also projects — see #isExplicitTimes / the
        // TimeSlotCalculator#calculateDeclaredSlots Javadoc for the bug that motivated this.
        EffectiveDayResponse effective = masterScheduleService.resolveEffectiveDay(masterId, date);
        List<WorkIntervalDto> intervals = effective.intervals();
        boolean explicitTimes = isExplicitTimes(effective);
        if (!explicitTimes && (intervals == null || intervals.isEmpty())) {
            return Optional.empty();
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
        //
        // NOT evaluated for an EXPLICIT_TIMES day: its single projected "interval" is the DERIVED
        // display window, which is legitimately degenerate (start == end) when the day declares exactly
        // one time — that would trip the ordering probe below and widen the booking query by a whole
        // extra day for nothing. Declared candidates are day-bounded (they must end by this date's Kyiv
        // midnight — see TimeSlotCalculator#calculateDeclaredSlots), so the tight single-day window is
        // always sufficient for them.
        OffsetDateTime dayStart = date.atStartOfDay(TimeZones.KYIV).toOffsetDateTime();
        boolean crossesMidnight = !explicitTimes && intervals.stream()
                .anyMatch(iv -> !iv.endTime().isAfter(iv.startTime()));
        LocalDate windowEndDate = crossesMidnight ? date.plusDays(2) : date.plusDays(1);
        OffsetDateTime dayEnd = windowEndDate.atStartOfDay(TimeZones.KYIV).toOffsetDateTime();

        // Step 7: load existing bookings that overlap the day window (CONFIRMED only).
        // Loaded once for the whole day and subtracted from every interval below.
        //
        // Two-column projection, never managed entities (Perf MEDIUM, 2026-08-11) — the same
        // findActiveTimeRangesByMasterInRange the whole-window loader (#loadOccupiedByDay) already uses,
        // with a byte-identical predicate and the same idx_bookings_master_slot_overlap index. This body
        // used to call findOverlappingByMaster (SELECT *), which was harmless while it ran only inside
        // read-only slot reads — but since the schedule-fit gate put this read on all four CREATE paths,
        // every one of those 20+-column managed Bookings (guest PII and cancel tokens included) joined the
        // create transaction's persistence context and got dirty-checked at flush. A master with 15
        // bookings that day added 15 managed entities to every create's flush for two getters.
        List<TimeRange> occupied = bookingRepository
                .findActiveTimeRangesByMasterInRange(masterId, dayStart, dayEnd)
                .stream()
                .map(b -> new TimeRange(b.startsAt().toInstant(), b.endsAt().toInstant()))
                .toList();

        // Step 8 belongs to the CALLER: it generates candidate slots per resolved interval and unions
        // the results (shared with the free-slot bookability gate — see computeDayFreeRanges). Calling
        // TimeSlotCalculator once per interval is the multi-interval generalization of the legacy
        // single-window call — gaps between intervals (lunch breaks) naturally yield no slots. The
        // cutoff is derived from the SAME `now` the range guard above used (Perf LOW-2), never re-read
        // per interval — which is why `now` is carried out of here rather than re-sampled downstream.
        //
        // The caller's `minLead` is BookingWindow.minLead() for every path but the STAFF create path,
        // which uses Duration.ZERO — so `now.plus(minLead)` is byte-for-byte
        // BookingWindow.bookableCutoff(now) on every pre-22.2 path. See #getStaffAvailableSlots.
        return Optional.of(new DayAvailability(effective, totalDuration, occupied, now));
    }

    /**
     * The resolved, pre-walk state of ONE master-day-service(s) availability request — see
     * {@link #resolveDayAvailability}. {@code now} is the request's single clock read (Perf LOW-2),
     * carried so the caller derives its cutoff from the same instant the range guards used.
     */
    private record DayAvailability(
            EffectiveDayResponse effective, Duration totalDuration, List<TimeRange> occupied,
            Instant now) {
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
     * ({@link #evictMasterAvailabilityCaches}) — a booking anywhere in the window can flip a day.
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

        // Same MasterBookability gate as getAvailableSlots (2026-08 re-audit LOW) — a closed salon's
        // master reports every day non-working rather than advertising days whose slots cannot be booked.
        if (!MasterBookability.isBookable(assignments.get(0).getMaster())) {
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
     * {@link #evictMasterAvailabilityCaches}.
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
        // MasterBookability folds in the salon term (2026-08 re-audit LOW): a closed salon's master is
        // not bookable, so the catalogue/master-list gate must not advertise it. Both finders that
        // produce an `msa` for this method initialise the salon, so the deref costs no lazy load on
        // either path: findByMasterIdAndIdWithGraph LEFT JOIN FETCHes it (master-scoped — it must
        // keep salon-less independent masters), while findBookableAssignmentsBySalonAndServiceDef,
        // the `preloaded` caller BookingMasterService's finder, uses an INNER JOIN FETCH — that one
        // is salon-scoped (WHERE s.id = :salonId), so a non-null salon was already required and the
        // inner join drops nothing.
        if (msa == null || !msa.isActive() || !MasterBookability.isBookable(msa.getMaster())) {
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
     * The assignments backing this request: the caller's {@code preloaded} list when it verifies against
     * the request, otherwise a fresh {@link #loadBookableAssignments} load (Perf MEDIUM, 2026-08-11).
     *
     * <p><b>Fall back, never throw.</b> A mismatch is a caller bug, not a client input, and this is a pure
     * perf shortcut — so an unverifiable {@code preloaded} degrades to the DB load rather than inventing a
     * new failure mode. The unknown / foreign / inactive → 404 semantics stay exactly where they were, in
     * {@link #loadBookableAssignment}, and are still what an inactive assignment hits (a non-active entity
     * fails {@link #matchesRequest}, falls through to the load, and 404s there as before).
     */
    private List<MasterServiceAssignment> resolveAssignments(
            UUID masterId, List<UUID> masterServiceIds, List<MasterServiceAssignment> preloaded) {
        return matchesRequest(masterId, masterServiceIds, preloaded)
                ? preloaded
                : loadBookableAssignments(masterId, masterServiceIds);
    }

    /**
     * True iff {@code preloaded} is IDENTITY-compatible with what {@link #loadBookableAssignments} would have
     * returned for {@code (masterId, masterServiceIds)}: parallel to the id list, each element non-null,
     * ACTIVE, carrying the requested assignment id and belonging to the requested master. Every check is
     * in-memory — the master's id is served off the {@code @ManyToOne} proxy without a statement — so
     * verifying costs nothing next to the query it replaces.
     *
     * <p><b>Identity ONLY — never the duration fields.</b> This deliberately does not compare
     * {@code durationOverrideMinutes}, {@code serviceDefinition.baseDurationMinutes} or
     * {@code serviceDefinition.bufferMinutesAfter}, the three values {@link #effectiveDuration} reads to size
     * every slot — it has nothing fresh to compare them against without re-issuing the very query
     * {@code preloaded} exists to avoid. So a "match" means "the right ROW", NOT "the same field values the
     * DB holds right now": the duration is taken on trust from the caller. What makes that sound is the
     * caller contract documented on the {@code preloaded} overloads — a managed instance loaded in the same
     * transaction, where the fallback load would hand back that identical instance anyway. Read together
     * with the cache-key note there before relaxing either side.
     */
    private boolean matchesRequest(
            UUID masterId, List<UUID> masterServiceIds, List<MasterServiceAssignment> preloaded) {
        if (preloaded == null || preloaded.size() != masterServiceIds.size()) {
            return false;
        }
        for (int i = 0; i < masterServiceIds.size(); i++) {
            MasterServiceAssignment msa = preloaded.get(i);
            if (msa == null
                    || !msa.isActive()
                    || !masterServiceIds.get(i).equals(msa.getId())
                    || msa.getMaster() == null
                    || !masterId.equals(msa.getMaster().getId())) {
                return false;
            }
        }
        return true;
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
     * <b>THE per-day slot-shape switch.</b> True iff {@code day}'s effective schedule is an
     * {@code EXPLICIT_TIMES} one (a weekly-template weekday or a per-date {@code CUSTOM_HOURS} override
     * carrying discrete start times — Phase 15.8 / 15.9), in which case its bookable starts are exactly
     * those declared times and its {@code intervals} carry only the DERIVED display window
     * {@code [min..max]} ({@code ScheduleMapper#toDerivedWindow}).
     *
     * <p>Read by BOTH the materialised slot list ({@link #computeDayFreeRanges}) and the day-gate
     * predicate ({@link #isDayBookable}), so {@code working-days} and {@code /slots} branch on the same
     * one-line rule and cannot diverge on the shape of a day (pinned by
     * {@code BookingAvailabilityAgreementIT}).
     *
     * <p>{@code times} is {@code null} on every non-EXPLICIT_TIMES day (see
     * {@link EffectiveDayResponse}), hence the explicit null check rather than an emptiness test alone.
     */
    private boolean isExplicitTimes(EffectiveDayResponse day) {
        return day.times() != null && !day.times().isEmpty();
    }

    /**
     * Free slots for one resolved day — the union across its work intervals for an INTERVAL day
     * (extracted from getAvailableSlots Step 8), or the day's DECLARED times for an
     * {@code EXPLICIT_TIMES} day. {@code occupied} must already be narrowed to the target date's window;
     * {@code cutoff} is the request's single lead-time floor (Perf LOW-2 — never re-derived per interval).
     *
     * <p><b>Interval days are byte-for-byte unchanged</b>, including the fixed {@link #SLOT_STEP} grid
     * anchored at each interval's start and the stranded tail it can leave (a 13:30–15:15 window offering
     * no 14:15 start for a 60-min service) — reviewed and deliberately KEPT (2026-08-11 product decision).
     *
     * <p>An {@code EXPLICIT_TIMES} day emits exactly the times the master declared, subject to the same
     * lead-time cutoff and the same strict-overlap test, plus a same-Kyiv-day end bound — see
     * {@link TimeSlotCalculator#calculateDeclaredSlots}. It must NOT be strided on {@link #SLOT_STEP}
     * across the derived {@code [min..max]} window: that fabricated undeclared starts AND made the last
     * declared time unbookable.
     */
    private List<TimeRange> computeDayFreeRanges(
            LocalDate date, EffectiveDayResponse day, Duration totalDuration,
            List<TimeRange> occupied, Instant cutoff) {
        if (isExplicitTimes(day)) {
            return timeSlotCalculator.calculateDeclaredSlots(
                    date, day.times(), totalDuration, occupied, cutoff);
        }
        List<TimeRange> result = new ArrayList<>();
        for (WorkIntervalDto interval : day.intervals()) {
            result.addAll(timeSlotCalculator.calculateAvailableSlots(
                    date, interval.startTime(), interval.endTime(), totalDuration, SLOT_STEP, occupied,
                    cutoff));
        }
        return result;
    }

    /**
     * <b>Membership counterpart of {@link #computeDayFreeRanges}</b> (perf LOW, 2026-08-18): true iff
     * one of the free ranges that method would return starts at exactly {@code startsAt}.
     *
     * <p>Deliberately a mirror of that method line for line — the same {@link #isExplicitTimes} shape
     * switch, the same {@link TimeSlotCalculator} calls with the same arguments, the same per-interval
     * iteration order — differing only in that the test is applied per interval and returns at the
     * first matching INTERVAL, instead of accumulating every range for the caller to scan. It is
     * {@link #isDayBookable}'s loop shape with an equality predicate in place of "any" — and, like
     * that method, it inherits {@link TimeSlotCalculator}'s allocation behaviour unchanged: each
     * interval's candidate list is still built in full before being scanned, so the exit is between
     * intervals, not within one. That is deliberate; see {@link #isStaffSlotAvailable}.
     *
     * <p><b>No availability semantics live here.</b> Which candidates exist, and at which grid
     * positions, is entirely {@link TimeSlotCalculator}'s (untouched) answer; this only asks whether
     * {@code startsAt} is among them. So the verdict is identical to the list-membership test
     * {@code BookingSlotAvailabilityGuard} previously ran over {@link #getStaffAvailableSlots} —
     * pinned by {@code BookingAvailabilityAgreementIT} case 19.
     *
     * @param startsAt the requested start as an {@link Instant}; {@link TimeRange#start()} is an
     *                 instant too, so the caller's offset/zone representation cannot affect the match
     *                 — the same property {@link OffsetDateTime#isEqual} gave the list comparison
     */
    private boolean dayFreeRangesContainStart(
            LocalDate date, EffectiveDayResponse day, Duration totalDuration,
            List<TimeRange> occupied, Instant cutoff, Instant startsAt) {
        if (isExplicitTimes(day)) {
            return startsOneOf(timeSlotCalculator.calculateDeclaredSlots(
                    date, day.times(), totalDuration, occupied, cutoff), startsAt);
        }
        for (WorkIntervalDto interval : day.intervals()) {
            if (startsOneOf(timeSlotCalculator.calculateAvailableSlots(
                    date, interval.startTime(), interval.endTime(), totalDuration, SLOT_STEP, occupied,
                    cutoff), startsAt)) {
                return true;
            }
        }
        return false;
    }

    /** True iff any range in {@code ranges} begins at exactly {@code startsAt}. */
    private static boolean startsOneOf(List<TimeRange> ranges, Instant startsAt) {
        for (TimeRange range : ranges) {
            if (range.start().equals(startsAt)) {
                return true;
            }
        }
        return false;
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
        List<TimeRange> occupied = occupiedByDay.getOrDefault(day.date(), List.of());
        // EXPLICIT_TIMES: same shape switch (#isExplicitTimes) and same declared-times predicate the
        // slot list uses, only short-circuiting — so a day whose declared times ALL fail the filters
        // (past the cutoff, occupied, or unable to finish inside the Kyiv day) reports working = false,
        // exactly matching the empty slot list. The derived [min..max] window is deliberately NOT
        // consulted here either.
        if (isExplicitTimes(day)) {
            return timeSlotCalculator.hasDeclaredSlot(
                    day.date(), day.times(), totalDuration, occupied, cutoff);
        }
        List<WorkIntervalDto> intervals = day.intervals();
        if (intervals == null || intervals.isEmpty()) {
            return false;
        }
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

    /**
     * Evicts one master's booking-availability caches by master prefix, after commit — the per-date slot
     * lists ({@code available-slots}, {@link #getAvailableSlots}), the {@code master-service-bookable}
     * verdict ({@link #hasBookableFutureSlot}) and the {@code master-bookable-days} calendar projection
     * ({@link #getBookableWorkingDays}). All three are keyed by a SpEL inline-list whose FIRST element is
     * the masterId ({@code {#masterId, #date, #masterServiceId}}, {@code {#masterId, #masterServiceId,
     * #from, #to}}, {@code {#masterId, #from, #to, #masterServiceId}} — a {@link List} at runtime), so
     * every key for this master is dropped, bounded to one master and never a blanket {@code clear()}
     * (Anti-Bug §F-6). Mirrors {@code MasterScheduleService#evictSlotsAfterCommit}.
     *
     * <p><b>Why {@code available-slots} is swept by MASTER and not by {@code (master, date, service)}.</b>
     * A master performs one service at a time, so a booking of service A consumes wall-clock time that
     * bounds the slots offered for EVERY other service B the same master performs — the A-shaped booking
     * shortens or deletes B's candidate slots on that date. Evicting only the booked service's key left
     * every OTHER service's cached list advertising a time that was already taken, for the full 60-second
     * TTL. Creation was still refused (the {@code existsOverlap} check plus the {@code bookings} GIST
     * exclusion on {@code (master_id, tstzrange(starts_at, ends_at))} — {@code V18__create_bookings.sql} —
     * return 409), so the defect was display-only and self-healing; it nonetheless offered a slot that
     * could not be booked, which is what this sweep removes.
     *
     * <p><b>Why the whole master and not just the written date.</b> Caffeine behind Spring's cache
     * abstraction supports no prefix/wildcard eviction, so the only mechanism available is the keyset scan
     * in {@link MasterCachePrefixEvictor} — and its predicate matches on a key PREFIX. Matching two
     * elements ({@code masterId} + {@code date}) would preserve the master's other dates, but it would also
     * make correctness depend on the caller deriving the same Kyiv-civil date the READ path keys on; the
     * booking-write call sites derived it with a bare {@code startsAt.toLocalDate()} (the date in whatever
     * offset the row came back in, not Kyiv), so a near-midnight booking evicted a key that never existed.
     * Dropping the date from the eviction predicate removes that failure mode by construction. The cost is
     * the master's other cached dates, which is bounded: {@code available-slots} holds 500 entries across
     * ALL masters × dates × services, so one master's share is small, the booking write rate per master is
     * far below one per 60-second TTL, and the same write already sweeps the larger
     * {@code master-bookable-days} (2 000 entries) by master prefix for exactly this reason.
     *
     * <p>Called from every booking-write {@code afterCommit} hook — {@code BookingService} (create,
     * decline, complete, not-complete, cancel, reschedule; {@code declineBookingForBatch} deliberately
     * does NOT, because its caller {@code ScheduleOverrideConflictService} issues ONE combined sweep for
     * the whole batch), {@code AppointmentService} and {@code AppointmentTransitionService}
     * (multi-service visits), {@code GuestBookingService} and {@code GuestVisitCancellationService} (LINK),
     * {@code BookingCancellationService} — from {@code MasterService} on master (de)activation, and from
     * {@code ServiceCatalogService} on service-definition mutations.
     *
     * <p>The keyset scan runs on the {@code cacheEvictionExecutor}, off the committing request thread
     * (Perf MEDIUM-3) — see {@link MasterCachePrefixEvictor}. Callers still invoke this from
     * {@code afterCommit}, so eviction can only ever happen AFTER the write is visible; the async hop can
     * delay it past commit, never move it before (Anti-Bug §F-2).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void evictMasterAvailabilityCaches(UUID masterId) {
        cacheEvictor.evictByMasterPrefix(masterId, BOOKING_WRITE_CACHES);
    }
}
