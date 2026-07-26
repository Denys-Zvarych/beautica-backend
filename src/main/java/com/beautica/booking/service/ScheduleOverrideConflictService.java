package com.beautica.booking.service;

import com.beautica.booking.dto.AppointmentProviderNoteRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.OverrideConflictCandidate;
import com.beautica.common.TimeZones;
import com.beautica.common.cache.MasterCachePrefixEvictor;
import com.beautica.common.exception.BusinessException;
import com.beautica.master.dto.OverrideConflictPreviewResponse;
import com.beautica.master.dto.OverrideConflictResponse;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.ScheduleOverrideResponse;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;
import com.beautica.master.service.MasterScheduleService;
import com.beautica.master.service.ScheduleConflictCalculator;
import com.beautica.master.service.ScheduleDateMath;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Resolves the booking-conflict side of the 2026-07-26 "schedule override over existing bookings"
 * design: reverses {@code MasterScheduleService}'s former OQ-1 ("always allowed — never blocked by,
 * nor mutating, existing bookings") for {@code PUT /api/v1/masters/{masterId}/overrides/{date}}, and
 * backs the read-only preview {@code POST /api/v1/masters/{masterId}/overrides/conflicts}.
 *
 * <p><b>Why this lives in {@code booking.service}, not {@code master.service}.</b> The conflict
 * PREDICATE itself is the dependency-free {@link ScheduleConflictCalculator} in
 * {@code master.service} (independently unit-testable, per the design's own guidance). This class is
 * the Spring-wired orchestrator around it, and it needs {@link BookingRepository} plus the existing
 * decline paths ({@link BookingService#declineBooking}, {@link AppointmentTransitionService
 * #declineAppointmentItem}) — both {@code booking.service} concerns. Placing it here (rather than
 * adding those dependencies to {@code MasterScheduleService}) also avoids a circular Spring bean
 * graph: {@link SlotCalculationService} and {@link AppointmentTransitionService} already depend on
 * {@link MasterScheduleService}, so the reverse edge (master → booking) would cycle back through
 * either of them. {@code booking} depending on {@code master.service}'s public API — the existing,
 * established direction ({@link SlotCalculationService}, {@link BookingService} already do it) — is
 * the only edge that cannot cycle.
 *
 * <p><b>No new transition logic (locked design constraint).</b> Every actual status change is made
 * by an existing, already-audited method: standalone conflicting bookings are declined via
 * {@link BookingService#declineBookingForBatch} (the same mutation {@link BookingService#declineBooking}
 * runs, minus its own per-call cache eviction — see the perf note below); an appointment-child
 * conflict group is declined via {@link AppointmentTransitionService#declineAppointmentItems} (which
 * owns the appointments-before-bookings lock order and the "collapse the header only when the last
 * CONFIRMED child goes" invariant, batched over every conflicting sibling of one visit). This class
 * only decides WHICH bookings conflict and WHICH of those two paths each one takes.
 *
 * <p><b>Perf (2026-07-26 audit, findings 1 &amp; 3).</b> {@link #declineConflicts} groups conflicting
 * candidates by {@code appointmentId} BEFORE declining, so K conflicting siblings of the SAME
 * appointment are declined through ONE {@code declineAppointmentItems} call (one authz check, one
 * item-list load, one header lock, one collapse) instead of K separate
 * {@code declineAppointmentItem} calls — each of which used to reload the whole visit item list,
 * an O(K²) row-read pattern for one appointment. Both decline paths additionally skip their own
 * per-call cache eviction for this write ({@code evictAfterCommit = false} /
 * {@code declineBookingForBatch}); this class registers exactly ONE combined after-commit eviction
 * for the whole write (see {@link #registerMasterCalendarEviction}) instead of one redundant scan
 * PER declined booking — the {@code available-slots}/{@code master-service-bookable}/
 * {@code master-bookable-days}/{@code master-working-days}/{@code master-usable-schedule} caches are
 * already evicted once, by master prefix, from {@code MasterScheduleService#upsertOverride}'s own
 * {@code evictSlotsAfterCommit} a few lines above in the SAME transaction — the only eviction this
 * class still owns is {@code master-calendar} (booking-list caches), which that call does not cover.
 */
@Service
@RequiredArgsConstructor
public class ScheduleOverrideConflictService {

    /**
     * Abuse backstop on conflicts declined in a SINGLE write (backend-security audit finding 5b) —
     * NOT a normal-operation limit. The write path is single-date/single-master by construction (only
     * the read-only preview spans a range), so a legitimate busy day realistically has ≤ ~50
     * conflicts; 100 is a generous ceiling that only ever rejects a runaway/malicious request (e.g. a
     * compromised SALON_OWNER looping a DAY_OFF override to mass-cancel every booking they own in one
     * write).
     *
     * <p><b>2026-07-26 product decision reversal (D6) — no longer an SMS/notification concern.</b>
     * This bound predates D6: it used to also guard against fanning an attacker-authored
     * {@code providerComment} out to every guest phone number on file (this class charged a
     * proportional rate-limit token per conflict for exactly that reason). Neither applies anymore —
     * an override-driven decline carries no note and enqueues no notification at all (see
     * {@link ScheduleOverrideRequest}'s class javadoc), so this route no longer has an outbound-
     * message abuse vector to bound. The cap is kept regardless: 100 uninvited, unexplained booking
     * cancellations in one request is still worth rejecting as almost certainly a bug or an abusive
     * account, independent of whether anyone is notified.
     */
    private static final int MAX_CONFLICTS_PER_WRITE = 100;

    /**
     * Result cap for the read-only preview (perf finding 4) — a range up to 366 days could otherwise
     * return an unbounded list. 500 is generous relative to the write path's 100-conflict abuse cap
     * (this is a READ, so a wider ceiling costs nothing but response size) while still bounding
     * payload size; {@link OverrideConflictPreviewResponse#truncated()} tells the caller there is
     * more rather than silently dropping rows past the cap.
     */
    private static final int MAX_PREVIEW_RESULTS = 500;

    /**
     * Hard cap on the RAW candidate rows {@link #loadCandidates} will ever pull from the DB for one
     * call, independent of {@link #MAX_PREVIEW_RESULTS} (backend-perf audit finding 3, 2026-07-26
     * re-audit). Without this, a range up to 366 days ({@code ScheduleDateMath}'s own cap) would let
     * {@link #previewConflicts} load EVERY {@code CONFIRMED} booking the master has in that whole
     * span into a {@code List} before the conflict predicate — and the {@code MAX_PREVIEW_RESULTS}
     * cap — ever run, so memory/row count scaled with the master's total confirmed-booking volume,
     * not with actual conflicts.
     *
     * <p>5000 is deliberately far above {@code MAX_PREVIEW_RESULTS} (this caps the SCAN, not the
     * response) and far above what one master could realistically accrue as CONFIRMED future
     * bookings even across the widest allowed range — so it is never expected to bind for legitimate
     * traffic; it exists purely to bound the pathological case. The single-date write path
     * ({@link #findConflictsForDate}) shares this same cap for code-path symmetry (one
     * {@link #loadCandidates}, never a non-capped variant alongside it — Anti-Bug §E-1) but can never
     * actually hit it: one calendar day cannot physically contain thousands of non-overlapping
     * bookings for one master.
     */
    static final int MAX_CANDIDATES_SCANNED = 5000;

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;
    private final AppointmentTransitionService appointmentTransitionService;
    private final MasterScheduleService masterScheduleService;
    private final MasterCachePrefixEvictor cachePrefixEvictor;
    private final ScheduleDateMath dateMath;
    private final Clock clock;

    /**
     * Read-only preview backing {@code POST /overrides/conflicts}: every {@code CONFIRMED} booking in
     * {@code [from, to]} that the intended override (constant across the whole range, mirroring how
     * the mobile client saves a multi-day span in one shape) would leave without availability.
     *
     * <p><b>In-transaction authz re-check (security audit finding 7).</b> This class's own convention
     * ({@link #applyOverrideWithConflictHandling}) re-verifies authority INSIDE the method as defense
     * in depth because the query below returns client/guest identities — this method previously
     * relied solely on the controller's {@code @authz.canManageMasterSchedule} SpEL gate, with no
     * compiler signal if a future internal caller (or a copy-pasted controller method) ever swapped
     * that gate for the read-only {@code canReadMasterSchedule} (which a {@code SALON_MASTER} passes).
     * {@link MasterScheduleService#requireScheduleManagementAuthority} closes that gap the same way
     * the write path already does.
     *
     * <p><b>Result cap (perf finding 4).</b> Capped at {@value #MAX_PREVIEW_RESULTS} conflicts with an
     * explicit {@link OverrideConflictPreviewResponse#truncated()} flag rather than a silent cutoff or
     * {@code Pageable}: this is a POST with a body-driven query shape (kind/mode/intervals/times), not
     * a plain paginated GET, so there is no natural {@code ?page=}/{@code ?size=} query-param home for
     * a {@code Pageable}; the mobile client already renders a scrollable list plus a count, so a
     * boolean it can turn into "показати ще N" messaging is strictly more useful than either a hard
     * 500 error past the cap or rows silently missing with no signal at all.
     *
     * <p><b>Scan cap (perf finding 3, 2026-07-26 re-audit) — {@code totalCount} semantics.</b>
     * {@link #loadCandidates} additionally caps the RAW row scan at {@value #MAX_CANDIDATES_SCANNED}
     * — a SEPARATE, earlier cap than {@link #MAX_PREVIEW_RESULTS} (which only trims the already-
     * filtered conflict list). When the scan itself is capped
     * ({@link OverrideConflictPreviewResponse#scanTruncated()} {@code == true}), {@code totalCount} is
     * no longer the TRUE number of conflicts in range — it is only the count of conflicts found among
     * the rows the scan managed to load, i.e. a LOWER BOUND on the true total. This can only ever
     * happen for a master with an implausibly large confirmed-booking volume across the requested
     * range (see {@value #MAX_CANDIDATES_SCANNED}'s own javadoc); {@code truncated} and
     * {@code scanTruncated} are deliberately independent signals so the caller can tell "there are
     * more conflicts than shown" apart from "the count itself may be incomplete".
     */
    @Transactional(readOnly = true)
    public OverrideConflictPreviewResponse previewConflicts(
            UUID actorId, UUID masterId, LocalDate from, LocalDate to,
            ScheduleExceptionKind kind, WeekdayMode mode,
            List<WorkIntervalDto> intervals, List<LocalTime> times) {
        masterScheduleService.requireScheduleManagementAuthority(actorId, masterId);
        dateMath.assertExpandable(from, to);
        WeekdayMode effectiveMode = mode != null ? mode : WeekdayMode.INTERVAL;

        CandidateScan scan = loadCandidates(masterId, from, to);
        List<OverrideConflictResponse> all = scan.candidates().stream()
                .filter(candidate -> ScheduleConflictCalculator.conflicts(
                        kind, effectiveMode, intervals, times,
                        kyivDate(candidate.startsAt()), candidate.startsAt(), candidate.endsAt()))
                .map(this::toResponse)
                .toList();

        boolean resultTruncated = all.size() > MAX_PREVIEW_RESULTS;
        List<OverrideConflictResponse> page = resultTruncated ? all.subList(0, MAX_PREVIEW_RESULTS) : all;
        return new OverrideConflictPreviewResponse(page, all.size(), resultTruncated, scan.scanTruncated());
    }

    /**
     * Write orchestration backing {@code PUT /overrides/{date}}: recomputes conflicts for
     * {@code request.date()} INSIDE this transaction (never trusting a client-supplied id list —
     * "consent means cancel whatever overlaps"), then either rejects the write (409, no
     * {@code cancelOverlapping} consent), writes it as-is (no conflicts — byte-for-byte the old
     * behaviour), or writes it and declines every conflict, all before this method returns.
     */
    @Transactional
    public ScheduleOverrideResponse applyOverrideWithConflictHandling(
            UUID actorId, UUID masterId, ScheduleOverrideRequest request) {
        // Defense in depth (mirrors upsertOverride's own internal re-check): confirm write authority
        // BEFORE the booking-conflict query below ever runs, even though the controller's SpEL gate
        // already covers this endpoint — that query returns client/guest identities.
        //
        // Perf audit finding 8 (double Master load, KEPT — judgment call, not fixed). This call
        // loads Master via MasterScheduleService#loadActiveMaster, and moments later
        // masterScheduleService.upsertOverride() loads the SAME row again internally. Only the
        // AUTHORIZATION check needs to run twice here (defense in depth); the entity load itself is
        // incidental to it. Deduplicating would mean either (a) threading a pre-loaded Master through
        // a new upsertOverride overload — but upsertOverride is a PUBLIC method with its own direct
        // callers (MasterScheduleServiceIT / MasterScheduleEdgeCaseIT / MasterScheduleSecurityIT all
        // call it directly, per its own class javadoc's "OQ-1 — REVERSED" section, exercising the
        // override CRUD invariants in isolation) that do NOT pre-authorize — a two-overload split
        // risks a future caller reaching the no-recheck overload without ever having authorized, a
        // silent authorization bypass; or (b) inlining upsertOverride's body here, duplicating
        // write logic this class's own javadoc says it must never do. Both cost more in coupling/risk
        // than the load saves: it is one indexed-PK-anchored JOIN FETCH across three tables
        // (`masterRepository.findByIdWithUserAndSalon` — master ⋈ user ⋈ salon, not a bare
        // single-row lookup) on a write path that already takes an advisory lock and issues several
        // more queries (conflict scan, override upsert, N decline writes) in the same transaction —
        // not a measurable hot-path cost relative to everything else this transaction already does.
        // Kept as-is.
        masterScheduleService.requireScheduleManagementAuthority(actorId, masterId);

        // Perf/consistency fix (backend-perf audit finding 2): this transaction can UPDATE many
        // `bookings` rows for masterId (every declined conflict) without ever taking the per-master
        // advisory lock the booking create/reschedule paths take (BookingRepository.acquireAdvisoryLock
        // /acquireAdvisoryLockWithTimeout, salt 0) before their own overlap check — so the two write
        // paths were not serialized against each other. Taken AFTER authorization (never before —
        // the same authz-before-lock ordering every transition method in this package already follows,
        // so a foreign/guessed masterId rejects instantly rather than after waiting on a lock it should
        // never have been allowed to attempt) and BEFORE the conflict query below, so the whole
        // "detect conflicts → decline them → write the override" sequence is serialized against a
        // concurrent create/reschedule for the SAME master exactly like the booking-write paths
        // serialize against each other. Reuses the exact fused lock+lock_timeout helper
        // GuestBookingService uses (no preceding client lock on this path) — never a hand-rolled
        // pg_advisory_xact_lock call.
        if (bookingRepository.acquireAdvisoryLockWithTimeout(masterId) == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Advisory lock acquisition failed");
        }

        List<OverrideConflictCandidate> conflicts = findConflictsForDate(
                masterId, request.date(), request.kind(), request.effectiveMode(),
                request.intervals(), request.times());

        // Abuse backstop (backend-security audit finding 5b) — see MAX_CONFLICTS_PER_WRITE's javadoc.
        // Rejected unconditionally, even with cancelOverlapping=true: this is single-date/single-master
        // by construction, so a legitimate day never approaches this ceiling.
        if (conflicts.size() > MAX_CONFLICTS_PER_WRITE) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    ("This override would decline %d bookings, exceeding the %d-per-write "
                            + "abuse-prevention limit")
                                    .formatted(conflicts.size(), MAX_CONFLICTS_PER_WRITE));
        }
        if (!conflicts.isEmpty() && !request.cancelOverlapping()) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    ("This override would leave %d confirmed booking(s) without availability; "
                            + "resubmit with cancelOverlapping=true to confirm cancellation")
                                    .formatted(conflicts.size()));
        }

        // 2026-07-26 product decision reversal (D6) — this write's ONLY rate-limit exposure is now
        // the ordinary destructive-bulk-write concern (see RateLimitConfig#scheduleOverrideWriteBuckets
        // and BookingRateLimitFilter), enforced entirely in the filter BEFORE this method ever runs.
        // There used to be an additional, proportional charge here (one extra token per booking about
        // to be declined, from the SAME bucket PATCH .../decline uses) because a mass-decline could
        // fan an attacker-authored providerComment out to real guest phone numbers as SMS — that
        // vector no longer exists: an override-driven decline carries no note and dispatches no
        // notification at all (D6), so there is nothing left to bound proportionally to blast radius.
        // Removed along with BookingDeclineRateLimiter.

        ScheduleOverrideResponse response = masterScheduleService.upsertOverride(actorId, masterId, request);
        declineConflicts(actorId, conflicts);
        // The ONLY eviction this class still owns for the write — see the class javadoc's perf note.
        if (!conflicts.isEmpty()) {
            registerMasterCalendarEviction(masterId);
        }
        return response;
    }

    private List<OverrideConflictCandidate> findConflictsForDate(
            UUID masterId, LocalDate date, ScheduleExceptionKind kind, WeekdayMode mode,
            List<WorkIntervalDto> intervals, List<LocalTime> times) {
        // scanTruncated() is intentionally ignored here — see MAX_CANDIDATES_SCANNED's javadoc: a
        // single calendar day cannot physically contain enough non-overlapping bookings for one
        // master to ever reach that cap, so this write path's candidate set is always complete.
        return loadCandidates(masterId, date, date).candidates().stream()
                .filter(candidate -> ScheduleConflictCalculator.conflicts(
                        kind, mode, intervals, times, date, candidate.startsAt(), candidate.endsAt()))
                .toList();
    }

    /**
     * Declines every conflicting booking, routed by {@link OverrideConflictCandidate#appointmentId}
     * — never new transition logic, just dispatch onto the two existing decline paths (see the
     * class javadoc). Always passes a {@code null} note (D2 REVISED — see
     * {@link ScheduleOverrideRequest}'s class javadoc: a master taking a day-off/pause gives no
     * reason); both target methods still call {@code BookingComments.normalize} internally on that
     * {@code null}, exactly as every other decline caller relies on.
     *
     * <p><b>Grouped by appointment (perf finding 1).</b> Standalone conflicts (no appointment) are
     * declined one call each — {@link BookingService#declineBookingForBatch} only ever touches its
     * OWN single row, so that is already O(1) per booking. Appointment-child conflicts are grouped by
     * {@code appointmentId} first: every conflicting sibling of the SAME visit is declined through
     * ONE {@link AppointmentTransitionService#declineAppointmentItems} call, not one call per sibling
     * — see that method's javadoc for the O(K²)→O(K) mechanism. Both decline paths run with eviction
     * AND notification suppressed ({@code evictAfterCommit = false} / the {@code ForBatch} variant —
     * see D6 in {@link ScheduleOverrideRequest}'s class javadoc); the caller
     * ({@link #applyOverrideWithConflictHandling}) performs the single combined eviction, and nothing
     * performs a notification enqueue for this path at all.
     */
    private void declineConflicts(UUID actorId, List<OverrideConflictCandidate> conflicts) {
        Map<UUID, List<OverrideConflictCandidate>> byAppointment = new LinkedHashMap<>();
        for (OverrideConflictCandidate candidate : conflicts) {
            if (candidate.appointmentId() == null) {
                bookingService.declineBookingForBatch(actorId, candidate.bookingId(),
                        new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null));
            } else {
                byAppointment.computeIfAbsent(candidate.appointmentId(), id -> new ArrayList<>())
                        .add(candidate);
            }
        }

        for (Map.Entry<UUID, List<OverrideConflictCandidate>> entry : byAppointment.entrySet()) {
            List<UUID> bookingIds = entry.getValue().stream()
                    .map(OverrideConflictCandidate::bookingId)
                    .collect(Collectors.toList());
            appointmentTransitionService.declineAppointmentItems(
                    actorId, entry.getKey(), bookingIds,
                    new AppointmentProviderNoteRequest(null), false);
        }
    }

    /**
     * The single combined after-commit eviction this class owns for a write that declined ≥1
     * conflict (perf finding 3). {@code masterId} is the ONLY master ever touched by one
     * {@code PUT /overrides/{date}} write (the write is single-date/single-master by construction —
     * see the design-premise correction in the 2026-07-26 audit), so there is no per-booking
     * (masterId, salonId) set to collect: it is already the method parameter. Mirrors
     * {@code MasterScheduleService#evictSlotsAfterCommit}'s single batched after-commit pattern
     * (register-once, skip entirely when no transaction is active — never a synchronous fallback
     * that would run the scan before commit, Anti-Bug §F-2) rather than the N independent
     * {@code registerSlotEviction}/{@code registerEviction} calls the two decline methods would
     * otherwise each make.
     */
    private void registerMasterCalendarEviction(UUID masterId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                cachePrefixEvictor.evictByKeyPrefixNow(masterId, "master-calendar");
            }
        });
    }

    /**
     * Every {@code CONFIRMED} booking of {@code masterId} in {@code [from, to]} — ONE query for the
     * whole range (Anti-Bug §E), never one per expanded date. {@code notBefore} folds the range
     * floor and the "not already started" clock guard into a single lower bound (see
     * {@link BookingRepository#findConfirmedCandidatesForOverrideConflictCheck}'s javadoc).
     *
     * <p>Requests {@value #MAX_CANDIDATES_SCANNED} {@code + 1} rows (the classic "limit-plus-one"
     * trick, perf finding 3): if the query actually returns the extra row, the scan was truncated —
     * that sentinel row is dropped before returning, so callers never see more than
     * {@value #MAX_CANDIDATES_SCANNED} candidates regardless.
     */
    private CandidateScan loadCandidates(UUID masterId, LocalDate from, LocalDate to) {
        OffsetDateTime windowStart = from.atStartOfDay(TimeZones.KYIV).toOffsetDateTime();
        OffsetDateTime windowEnd = to.plusDays(1).atStartOfDay(TimeZones.KYIV).toOffsetDateTime();
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime notBefore = windowStart.isAfter(now) ? windowStart : now;
        List<OverrideConflictCandidate> rows = bookingRepository.findConfirmedCandidatesForOverrideConflictCheck(
                masterId, notBefore, windowEnd, PageRequest.of(0, MAX_CANDIDATES_SCANNED + 1));
        boolean scanTruncated = rows.size() > MAX_CANDIDATES_SCANNED;
        List<OverrideConflictCandidate> candidates = scanTruncated ? rows.subList(0, MAX_CANDIDATES_SCANNED) : rows;
        return new CandidateScan(candidates, scanTruncated);
    }

    /**
     * {@link #loadCandidates}'s result: the (possibly query-capped) candidate rows plus a signal
     * distinct from {@link OverrideConflictPreviewResponse#truncated()} — see
     * {@value #MAX_CANDIDATES_SCANNED}'s javadoc.
     */
    private record CandidateScan(List<OverrideConflictCandidate> candidates, boolean scanTruncated) {
    }

    private OverrideConflictResponse toResponse(OverrideConflictCandidate candidate) {
        return new OverrideConflictResponse(
                candidate.bookingId(),
                candidate.appointmentId(),
                kyivDate(candidate.startsAt()),
                candidate.startsAt(),
                candidate.endsAt(),
                candidate.clientDisplayName(),
                candidate.serviceName());
    }

    private static LocalDate kyivDate(OffsetDateTime instant) {
        return instant.atZoneSameInstant(TimeZones.KYIV).toLocalDate();
    }
}
