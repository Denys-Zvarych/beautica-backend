package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.AppointmentCancelRequest;
import com.beautica.booking.dto.AppointmentDetailResponse;
import com.beautica.booking.dto.AppointmentItemRescheduleRequest;
import com.beautica.booking.dto.AppointmentProviderNoteRequest;
import com.beautica.booking.dto.AppointmentRescheduleRequest;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.cache.MasterCachePrefixEvictor;
import com.beautica.common.exception.BookingElapsedException;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ClientBookingConflictException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.entity.Master;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.salon.entity.Salon;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Appointment-level (visit) status transitions (BE-4): the four lifecycle operations that move a
 * whole multi-service visit — the {@link Appointment} header AND every chained {@link Booking} item —
 * to a terminal state in lockstep, atomically.
 *
 * <p><b>Mirrors the single-service transitions</b> in {@code BookingService}
 * ({@code declineBooking} / {@code completeBooking} / {@code notCompleteBooking} / {@code cancelBooking})
 * lifted to the visit level. Each operation is ONE {@code @Transactional} unit, so a partial visit
 * (some items terminal, some CONFIRMED) is impossible: the header status/notes and all N item
 * statuses are flushed together on commit, and any failure rolls the whole thing back.
 *
 * <p><b>Notes live on the header only</b> (locked booking-notes decision). The child items receive the
 * status change and the matching {@code cancellationReason} (so an item read individually via
 * {@code GET /bookings/{id}} stays consistent with a single-service terminal booking), but never a
 * {@code providerComment}/{@code clientCancellationNote} — those denormalize to the visit HEADER,
 * mutually visible per CLAUDE.md.
 *
 * <p><b>Cancellation reasons are fixed by the operation</b>, never chosen by the caller: a client
 * cancel is {@code CLIENT_CANCELLED}, a provider decline {@code PROVIDER_UNAVAILABLE}, a no-show
 * {@code CLIENT_NO_SHOW}. This is why the request bodies carry only the optional note.
 *
 * <p><b>Cache eviction after commit.</b> Moving every item to a terminal state frees its slot (the
 * {@code no_overlapping_bookings} GIST EXCLUDE predicate is {@code status = 'CONFIRMED'} only), so
 * each transition evicts the master's availability caches (per (date, service) touched, plus the
 * master's free-slot verdict, the salon catalogue, and the master-calendar page cache) after commit —
 * reusing the exact single-service booking-write eviction hooks, fanned out over the visit's items.
 * {@code complete} additionally evicts the actor's revenue dashboard (COMPLETED feeds revenue).
 */
@Service
@RequiredArgsConstructor
public class AppointmentTransitionService {

    private final AppointmentRepository appointmentRepository;
    private final BookingRepository bookingRepository;
    private final AuthorizationService authz;
    private final NotificationOutboxService outboxService;
    private final SlotCalculationService slotCalculationService;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    private final MasterCachePrefixEvictor cachePrefixEvictor;
    private final VisitPlanner visitPlanner;
    private final AppointmentService appointmentService;
    private final Clock clock;

    /**
     * Client-initiated visit cancel — the visit HEADER and every item move to {@code CANCELLED}
     * with reason {@code CLIENT_CANCELLED}; the optional client note is written to the header.
     *
     * <p>Mirrors {@code BookingService#cancelBooking}: existence + ownership + guest collapse to a
     * single uniform {@code 403} (no existence oracle — a missing id, a guest visit, and a foreign
     * visit are indistinguishable), then the same {@code CONFIRMED}-only status guard ({@code 400})
     * and the same read-only-after-elapse guard ({@code 409 BOOKING_ALREADY_ELAPSED}), evaluated on
     * the WHOLE visit (the last item's {@code endsAt} — the instant the visit window fully passes).
     *
     * <p><b>Cycle-3 audit finding 1 (authz-before-lock).</b> Ownership is resolved via
     * {@link AppointmentRepository#findClientIdById} — a scalar projection, not an entity load —
     * BEFORE {@link #lockHeaderForWholeVisitTransition} runs, so a non-owner (or a guessed/foreign
     * id) never causes a real row lock or the item {@code JOIN FETCH} below: a missing id and a
     * foreign visit both fail this check identically and instantly, with zero DB writes attempted.
     */
    @Transactional
    public void cancelAppointment(UUID clientId, UUID appointmentId, AppointmentCancelRequest req) {
        UUID ownerClientId = appointmentRepository.findClientIdById(appointmentId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        if (!ownerClientId.equals(clientId)) {
            throw new ForbiddenException("Access denied");
        }

        Appointment appointment = lockHeaderForWholeVisitTransition(appointmentId);
        List<Booking> items = loadItemsOrThrow(appointmentId);

        if (appointment.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(
                    "Cannot cancel a booking in status %s".formatted(appointment.getStatus()));
        }
        assertVisitNotElapsedForClient(items);

        appointment.setStatus(BookingStatus.CANCELLED);
        appointment.setCancellationReason(CancellationReason.CLIENT_CANCELLED);
        appointment.setClientCancellationNote(BookingComments.normalize(req == null ? null : req.clientCancellationNote()));
        transitionItems(items, BookingStatus.CANCELLED, CancellationReason.CLIENT_CANCELLED);

        // No revenue eviction on a client cancel — a CANCELLED visit never enters revenue (mirrors
        // BookingService#cancelBooking).
        persistAndNotify(appointment, items, null);
    }

    /**
     * Provider-initiated visit decline — the header and every item move to {@code DECLINED} with
     * reason {@code PROVIDER_UNAVAILABLE}; the optional provider note is written to the header.
     * Mirrors {@code BookingService#declineBooking} exactly on BOTH axes: authority via
     * {@link AuthorizationService#enforceCanCancelBooking} (admits SALON_OWNER / SALON_ADMIN /
     * INDEPENDENT_MASTER) AND the missing-id contract — both collapse a missing id to the same
     * uniform 403 as a foreign visit (Finding 8 — no existence oracle). No elapse guard: resolving
     * an elapsed visit is exactly the provider's job.
     */
    @Transactional
    public void declineAppointment(UUID actorId, UUID appointmentId, AppointmentProviderNoteRequest req) {
        // Cycle-3 audit finding 1 (authz-before-lock): provider authority is resolved via the
        // lightweight BookingCompletionAccess projection (AuthorizationService#enforceCanManageAppointment)
        // — no Booking/Appointment entity load — BEFORE lockHeaderForWholeVisitTransition runs, so a
        // non-owner (or a guessed/foreign id) never causes a real row lock or the item JOIN FETCH
        // below. A missing appointment id and an existing-but-foreign visit both resolve to the SAME
        // 403, instantly, with zero DB writes attempted (Finding 8 — no existence oracle preserved).
        authz.enforceCanManageAppointment(actorId, appointmentId);

        Appointment appointment = lockHeaderForWholeVisitTransition(appointmentId);
        VisitContext ctx = new VisitContext(appointment, loadItemsOrThrow(appointmentId));
        assertHeaderTransition(ctx.appointment(), BookingStatus.DECLINED);

        ctx.appointment().setStatus(BookingStatus.DECLINED);
        ctx.appointment().setCancellationReason(CancellationReason.PROVIDER_UNAVAILABLE);
        ctx.appointment().setProviderComment(BookingComments.normalize(req == null ? null : req.providerComment()));
        transitionItems(ctx.items(), BookingStatus.DECLINED, CancellationReason.PROVIDER_UNAVAILABLE);

        // No revenue eviction on a decline (mirrors BookingService#declineBooking).
        persistAndNotify(ctx.appointment(), ctx.items(), null);
    }

    /**
     * Provider-initiated PER-SERVICE decline — declines exactly ONE service line of a multi-service
     * visit, leaving its siblings CONFIRMED. The additive counterpart of {@link #declineAppointment}
     * (which terminates the WHOLE visit): mirrors {@code BookingService#declineBooking} lifted to a
     * single chained item, so a client who booked N services with one master can lose ONE without the
     * others being cancelled.
     *
     * <p><b>Authority</b> is the same provider authority as the whole-visit decline, but resolved via
     * {@link AuthorizationService#enforceCanManageAppointment} — the projection-only, per-APPOINTMENT
     * check the three whole-visit methods use — rather than the entity-based
     * {@link AuthorizationService#enforceCanCancelBooking}. {@code enforceCanManageAppointment}
     * fetches EVERY chained row's {@code (masterUserId, salonId)} pair via
     * {@link BookingRepository#findAllCompletionAccessByAppointmentId} and requires provider
     * authority over ALL of them (not just one), so it is authoritative for this per-ITEM operation
     * regardless of whether the visit is actually single-master — reusing it does not widen or
     * weaken the check even if a future writer ever appended a different-master item. A {@code
     * bookingId} that is not a child of this appointment is a {@code 404} — same missing→404 shape
     * as a sibling lookup elsewhere, no existence oracle beyond the visit the caller is already
     * authorized on.
     *
     * <p><b>Cycle-4 audit finding 2 (authz before entity load).</b> Authorization now runs BEFORE any
     * {@code Appointment}/{@code Booking} entity is loaded — a previous version loaded the full
     * {@code Appointment} ({@code findById}) AND the 5-way {@code JOIN FETCH} item list
     * ({@link #loadItemsOrThrow}) first, so a foreign appointment id cost a full entity load before
     * being rejected (an existence-oracle-by-query-cost versus a missing id's instant reject, and
     * wasted work on a request that was always going to be refused). The pre-check is
     * projection-only — {@code findAllCompletionAccessByAppointmentId} never touches the persistence
     * context — so, exactly like the whole-visit methods' {@code enforceCanManageAppointment} calls,
     * it cannot poison a later "fresh snapshot" read with a stale cached entity. No lock is taken
     * here either way (the header lock below still runs, unaffected, after the child is located), so
     * this fix closes a query-cost oracle only, not a timing/lock oracle.
     *
     * <p><b>The note lives on the CHILD row</b>, not the header: the declined child's OWN status is
     * {@code DECLINED}, satisfying the V114 {@code chk_provider_comment_status} CHECK; the header stays
     * {@code CONFIRMED} while ≥1 sibling remains CONFIRMED, so writing a note to the header would
     * violate {@code chk_appointment_provider_comment_status} (which requires a terminal header). The
     * note is OPTIONAL for all roles (locked booking-notes decision) and mutually visible.
     *
     * <p><b>Header recompute (locked, safe invariant):</b> while ≥1 child remains CONFIRMED the header
     * stays CONFIRMED; declining the LAST CONFIRMED child collapses the header to {@code DECLINED}
     * (reason {@code PROVIDER_UNAVAILABLE}) — no richer mixed-terminal header semantics.
     *
     * <p><b>Slot + notification:</b> the {@code no_overlapping_bookings} GIST EXCLUDE is
     * {@code status = 'CONFIRMED'} only, so the declined child auto-releases its own slot while its
     * siblings keep their times — no re-plan needed. Availability caches are evicted after commit over
     * the single freed (date, service) key, and EXACTLY ONE status-changed notification is enqueued
     * referencing the DECLINED CHILD (never item 0) so the client is told which service was declined.
     *
     * <p><b>Freshness re-check (F1, HIGH, cycle-6 audit 2026-08-03 — fixed here).</b> Immediately
     * after {@link #lockAppointmentHeaderBeforeItemDecline} returns, and BEFORE {@code target}'s own status
     * is mutated, this method re-verifies {@code target} via
     * {@link BookingRepository#existsConfirmedById} — a scalar, entity-manager-bypassing probe,
     * never a second entity load. {@code target} was necessarily loaded (via
     * {@link #loadItemsOrThrow}) BEFORE any lock could protect that read, so the lock above only
     * proves the HEADER is still CONFIRMED, not that THIS leg still is: a per-item reschedule of
     * the SAME leg (fired near-concurrently at the sibling per-item endpoint) leaves the header
     * CONFIRMED — a sibling remains — while moving {@code target} to a new time without changing
     * its status. {@code Booking} now carries {@code @DynamicUpdate} (G1, cycle-7 audit
     * 2026-08-03), so this decline's own save would write only {@code status}/
     * {@code cancellationReason}/{@code providerComment} and could no longer clobber the
     * concurrent reschedule's new time even without this check — but proceeding anyway would
     * still declare a leg DECLINED that the client, moments earlier, had already moved to a new
     * time, which is a confusing double-write on the SAME leg even though no column is corrupted.
     * The check also fires when {@code headerWasLocked} is {@code false} (a whole-visit transition
     * already moved every CONFIRMED item, including this one, to a terminal state):
     * {@link #assertItemTransition}'s check ran on the SAME pre-lock stale snapshot and could not
     * have caught that either. A mismatch aborts with the same 409 shape as every other conflict
     * in this class.
     *
     * @throws NotFoundException  the {@code bookingId} is not a child of an existing, authorized visit (404)
     * @throws ForbiddenException a missing appointment id, or the actor lacks provider authority over the
     *                            visit (403) — the missing-id case is collapsed to 403, not 404, to avoid
     *                            an existence oracle (Finding 8)
     * @throws BusinessException  the target child is not CONFIRMED — already terminal, or changed
     *                            concurrently since being loaded (409)
     */
    @Transactional
    public void declineAppointmentItem(
            UUID actorId, UUID appointmentId, UUID bookingId, AppointmentProviderNoteRequest req) {
        // Cycle-4 audit finding 2: authorize via the projection-only, per-APPOINTMENT check BEFORE
        // loading any entity — no Appointment findById, no 5-way JOIN FETCH item list — collapsing a
        // missing appointment id to the SAME uniform 403 enforceCanManageAppointment throws for an
        // existing-but-foreign visit (Finding 8 — no existence oracle), now at projection cost instead
        // of a full entity-load cost. The bookingId-not-a-child 404 below is UNCHANGED — it is reached
        // only after the caller is authorized on the visit, so it is not an oracle.
        authz.enforceCanManageAppointment(actorId, appointmentId);

        List<Booking> items = loadItemsOrThrow(appointmentId);
        Booking target = items.stream()
                .filter(item -> item.getId().equals(bookingId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Appointment service not found"));

        assertItemTransition(target, BookingStatus.DECLINED);

        // Canonical appointments-before-bookings lock order (cycle-2 audit finding 1): lock the
        // header BEFORE this child row's own status change is written below — previously this ran
        // AFTER bookingRepository.save(target), inverted relative to the order the whole-visit
        // transition methods take after their own authorization check (lockHeaderForWholeVisitTransition
        // — cycle-3 audit finding 1 moved that call after authz, not before item loads/mutation).
        // The actual "does a CONFIRMED sibling remain" UPDATE still has to run AFTER the child's own
        // status change is flushed (see collapseHeaderAfterItemTransition) — only the LOCK moves
        // earlier, not the collapse.
        boolean headerWasLocked = lockAppointmentHeaderBeforeItemDecline(appointmentId);

        // Freshness re-check (F1, HIGH, cycle-6 audit 2026-08-03) — see this method's own
        // "Freshness re-check" Javadoc paragraph above. Runs regardless of headerWasLocked (see
        // that paragraph for why the false case needs it too).
        if (!bookingRepository.existsConfirmedById(target.getId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Service changed concurrently — please retry");
        }

        target.setStatus(BookingStatus.DECLINED);
        target.setCancellationReason(CancellationReason.PROVIDER_UNAVAILABLE);
        // Note on the CHILD (its own status is now DECLINED — legal per chk_provider_comment_status);
        // the header note is never touched on a partial decline.
        target.setProviderComment(BookingComments.normalize(req == null ? null : req.providerComment()));
        bookingRepository.save(target);

        collapseHeaderAfterItemTransition(
                appointmentId, headerWasLocked, BookingStatus.DECLINED, CancellationReason.PROVIDER_UNAVAILABLE, null);

        // Reference the DECLINED CHILD (not item 0) so the client notification names the right service.
        outboxService.enqueueStatusChanged(target.getId());

        // Single-item availability eviction — reuse the whole-visit after-commit hook over a one-item
        // list; a declined child frees only its own slot. No revenue impact on a decline (null actor).
        registerEviction(List.of(target), null);
    }

    /**
     * Batched per-service decline for MULTIPLE sibling items of the SAME appointment in ONE write —
     * the schedule-override-conflict counterpart of {@link #declineAppointmentItem} (perf finding,
     * 2026-07-26 audit). {@code ScheduleOverrideConflictService} used to call
     * {@link #declineAppointmentItem} once PER conflicting sibling: for K conflicting items of the
     * SAME appointment that reloads the appointment's full {@code JOIN FETCH} item list
     * ({@link #loadItemsOrThrow}) K times, and re-locks/re-collapses the header K times — O(K) reloads
     * of an O(K)-row list, i.e. O(K²) row reads overall for one appointment. This method authorizes
     * ONCE, loads the item list ONCE, locks the header ONCE, transitions every targeted item in
     * memory, saves ONCE (batched {@code saveAll}), and collapses the header ONCE — O(K) total row
     * reads/writes regardless of how many siblings are declined.
     *
     * <p><b>Every invariant {@link #declineAppointmentItem} enforces is preserved</b>, applied
     * per-item in memory rather than duplicated as transition logic:
     * <ul>
     *   <li>the canonical appointments-before-bookings lock order — {@link #lockAppointmentHeaderBeforeItemDecline}
     *       still runs BEFORE any target's status is mutated;</li>
     *   <li>"collapse to DECLINED only when the last CONFIRMED child goes" — the single
     *       {@link #collapseHeaderAfterItemTransition} call runs AFTER every targeted item's new
     *       status is flushed via {@code saveAll}, so its {@code NOT EXISTS} sibling check correctly
     *       observes ALL of them, not just the first;</li>
     *   <li>the per-item CONFIRMED-only transition guard ({@link #assertItemTransition}), checked for
     *       EVERY target before ANY of them is mutated — one stale sibling rejects the whole batch
     *       rather than leaving a partial decline.</li>
     * </ul>
     *
     * <p><b>Eviction is opt-out</b> via {@code evictAfterCommit}: the default single-item path always
     * evicts (see {@link #declineAppointmentItem}); the schedule-override-conflict caller passes
     * {@code false} and performs ONE combined after-commit eviction itself, covering every
     * appointment group AND every standalone booking declined in the same write (perf finding — N
     * redundant {@code master-service-bookable}/{@code master-bookable-days} prefix scans collapsed
     * into the ONE scan {@code MasterScheduleService#upsertOverride} already runs for the write).
     *
     * <p><b>No notification is ever enqueued by this method (D6, 2026-07-26 product decision
     * reversal).</b> Unlike {@link #declineAppointmentItem}, which always enqueues a status-changed
     * notification per decline, THIS method is only ever called by the schedule-override-conflict
     * write path, and that path enqueues nothing at all: the client discovers the cancellation from
     * the booking's status alone, and notifying for this flow is deferred to a later phase. This is a
     * deliberate, permanent property of this method — do not "fix" it back by re-adding an
     * {@code outboxService.enqueueStatusChanged} call here.
     *
     * <p><b>Batched freshness re-check (G3, HIGH, cycle-7 audit 2026-08-03 — fixed here).</b> This
     * method has no per-endpoint self-racer ({@code ScheduleOverrideConflictService} is its only
     * caller, and one {@code PUT /overrides/{date}} write calls it at most once per appointment), but
     * it has an obvious CROSS-endpoint racer: {@link #loadItemsOrThrow} above loads {@code items}
     * UNLOCKED, and between that load and {@link #lockAppointmentHeaderBeforeItemDecline} below, a
     * client can cancel or reschedule one of {@code targets} via its own per-item route — a realistic
     * interleaving (a provider adjusting working hours while a client acts on a leg that happens to
     * fall in the newly-blocked window), not a contrived one. Immediately after the header lock, this
     * method re-verifies every target via {@link BookingRepository#findConfirmedIdsByAppointmentId} —
     * the same batched, entity-manager-bypassing projection {@link #rescheduleAppointment} already
     * uses for its own post-lock recheck — and FILTERS {@code targets} down to the still-CONFIRMED
     * survivors, rather than aborting the whole batch on any single mismatch (the per-item methods'
     * all-or-nothing shape).
     *
     * <p><b>Why filter, not abort (unlike the per-item, client-facing methods).</b> Those methods
     * abort-on-mismatch because their target list is an explicit, single-item CLIENT request — the
     * caller named exactly this booking and deserves a definite 409 if its precondition already
     * lapsed. This method's target list is not a request at all — it is INTERNALLY DERIVED, moments
     * earlier in the SAME transaction, from "every CONFIRMED booking that conflicts with the schedule
     * change about to be applied" ({@code ScheduleOverrideConflictService#declineConflicts}, its
     * only caller). A target that left CONFIRMED in the
     * interleaving window (cancelled or moved away by its client) is, BY DEFINITION, no longer a
     * conflict: there is nothing left to decline, and there is no caller-visible request to reject
     * with a 409 — the provider's {@code PUT} was never "about" that specific booking. Aborting the
     * entire batch over one such survivor would be strictly worse: every OTHER genuinely-conflicting
     * sibling would incorrectly stay CONFIRMED against a schedule that no longer permits it, which is
     * the actual invariant this write path exists to protect. {@code Booking}'s new
     * {@code @DynamicUpdate} (G1) means a stale target's save would not have corrupted any column
     * either way (only {@code status}/{@code cancellationReason}/{@code providerComment} are
     * dirtied) — but declining a booking that is no longer CONFIRMED (e.g. re-declining an
     * already-CANCELLED row, or worse, silently discarding a client's fresher terminal state by
     * writing DECLINED over it) would still be a real, user-visible correctness bug, which is exactly
     * what filtering prevents.
     *
     * @param bookingIds     the target children's ids — every one MUST have been a CONFIRMED child of
     *                       {@code appointmentId} when the caller found them so, in the SAME
     *                       transaction, moments earlier; a foreign id is still a 404 (that is a
     *                       caller bug, never a legitimate race), but one that raced away to a
     *                       terminal state since is silently dropped from the result (see "Batched
     *                       freshness re-check" above) rather than rejecting the whole batch
     * @param evictAfterCommit whether this call registers its own after-commit availability-cache
     *                          eviction — {@code false} for the batched schedule-override-conflict path
     * @return the SURVIVING targeted items, now DECLINED, in {@code bookingIds} order — may be a
     *         strict subset of {@code bookingIds} (never a superset) if any target raced to a
     *         terminal state between this method's unlocked load and its post-lock recheck; empty if
     *         every target did
     * @throws ForbiddenException the actor lacks provider authority over the visit (403)
     * @throws NotFoundException  a {@code bookingId} is not a child of this appointment (404)
     * @throws BusinessException  a target child was not CONFIRMED at the initial, unlocked check —
     *                            already terminal at load time, before any race window opened (409)
     */
    List<Booking> declineAppointmentItems(
            UUID actorId, UUID appointmentId, List<UUID> bookingIds, AppointmentProviderNoteRequest req,
            boolean evictAfterCommit) {
        // Same projection-only, authz-before-any-load ordering as declineAppointmentItem (cycle-4
        // audit finding 2) — never an entity load before authorization has already succeeded.
        authz.enforceCanManageAppointment(actorId, appointmentId);

        List<Booking> items = loadItemsOrThrow(appointmentId);
        Map<UUID, Booking> itemsById = items.stream().collect(Collectors.toMap(Booking::getId, b -> b));

        // Validate EVERY target before mutating ANY — a batch either fully applies or throws before
        // touching a single row, rather than leaving a partial decline behind. This guards against a
        // caller bug (a bookingId that was never CONFIRMED at all); the RACE case — a target that WAS
        // CONFIRMED here but left it before the lock below — is handled separately, by filtering, not
        // by this guard (see "Batched freshness re-check" in the class-level Javadoc above).
        List<Booking> targets = new ArrayList<>(bookingIds.size());
        for (UUID bookingId : bookingIds) {
            Booking target = itemsById.get(bookingId);
            if (target == null) {
                throw new NotFoundException("Appointment service not found");
            }
            assertItemTransition(target, BookingStatus.DECLINED);
            targets.add(target);
        }

        // Canonical appointments-before-bookings lock order (mirrors declineAppointmentItem): the
        // header lock runs BEFORE any target's own status change is written below. Routed through
        // the SAME package-private wrapper declineAppointmentItem uses (G3, cycle-7 audit
        // 2026-08-03 — previously called the private lockHeaderBeforeItemTransition directly) so
        // AppointmentCrossPathTransitionConcurrencyIT has an identical @SpyBean-able rendezvous
        // point for this batched path too; see that wrapper's Javadoc.
        boolean headerWasLocked = lockAppointmentHeaderBeforeItemDecline(appointmentId);

        // Batched freshness re-check (G3, HIGH, cycle-7 audit 2026-08-03) — see this method's own
        // Javadoc for the cross-endpoint racer and why filtering (not aborting) is correct here. Every
        // `targets` entry was loaded (via loadItemsOrThrow, above) before this lock existed to protect
        // that read; this scalar, entity-manager-bypassing projection is the only way to learn which
        // ones are STILL CONFIRMED without poisoning — or being poisoned by — the identity map.
        Set<UUID> stillConfirmedIds = bookingRepository.findConfirmedIdsByAppointmentId(appointmentId);
        targets = targets.stream().filter(t -> stillConfirmedIds.contains(t.getId())).toList();

        String note = BookingComments.normalize(req == null ? null : req.providerComment());
        for (Booking target : targets) {
            target.setStatus(BookingStatus.DECLINED);
            target.setCancellationReason(CancellationReason.PROVIDER_UNAVAILABLE);
            target.setProviderComment(note);
        }
        bookingRepository.saveAll(targets);

        // Collapse runs ONCE, after every target's new status is flushed — so its NOT EXISTS sibling
        // check sees ALL of them at once, not just the first (the actual O(K²) fix: one collapse
        // instead of K, each of which would otherwise re-run the same conditional UPDATE).
        collapseHeaderAfterItemTransition(
                appointmentId, headerWasLocked, BookingStatus.DECLINED, CancellationReason.PROVIDER_UNAVAILABLE, null);

        // No outboxService.enqueueStatusChanged loop here — see this method's own javadoc (D6): the
        // schedule-override-conflict write path this method exists for enqueues no notification at all.

        // Guard against the now-possible empty list (every target raced away — see "Batched
        // freshness re-check" above): registerEviction indexes items.get(0) unconditionally and would
        // throw IndexOutOfBoundsException on an empty list, and there is nothing to evict for either.
        if (evictAfterCommit && !targets.isEmpty()) {
            registerEviction(targets, null);
        }
        return targets;
    }

    /**
     * Provider-initiated visit completion — the header and every item move to {@code COMPLETED}.
     * Mirrors {@code BookingService#completeBooking} (authority via
     * {@link AuthorizationService#enforceCanCompleteBooking}); no note, no cancellation reason. Also
     * evicts the actor's revenue dashboard after commit (COMPLETED feeds revenue).
     *
     * <p>Enqueues EXACTLY ONE review-requested notification for the whole visit (BE-6) — the prompt
     * fires once, referencing the FIRST item (never one per service), mirroring how the single
     * status-changed notification is enqueued. Reviews themselves are created per-booking via
     * {@code POST /api/v1/reviews} with a {@code bookingId} (locked rule: 1 booking = 1 feedback;
     * there is no visit-level review endpoint) — this single prompt only nudges the client toward the
     * visit's first booking. The drain worker rehydrates that booking → its client (= the visit
     * client) as the recipient. Skipped for a guest (LINK) visit with no registered account to review
     * with, exactly as {@code BookingService#completeBooking} skips it.
     */
    @Transactional
    public void completeAppointment(UUID actorId, UUID appointmentId) {
        // Cycle-3 audit finding 1 (authz-before-lock): provider authority is resolved via the
        // lightweight BookingCompletionAccess projection (AuthorizationService#enforceCanManageAppointment)
        // — no Booking/Appointment entity load — BEFORE lockHeaderForWholeVisitTransition runs, so a
        // non-owner (or a guessed/foreign id) never causes a real row lock or the item JOIN FETCH
        // below. A missing appointment id and an existing-but-foreign visit both resolve to the SAME
        // 403, instantly, with zero DB writes attempted (Finding 8 — no existence oracle preserved).
        authz.enforceCanManageAppointment(actorId, appointmentId);

        Appointment appointment = lockHeaderForWholeVisitTransition(appointmentId);
        VisitContext ctx = new VisitContext(appointment, loadItemsOrThrow(appointmentId));
        assertHeaderTransition(ctx.appointment(), BookingStatus.COMPLETED);

        ctx.appointment().setStatus(BookingStatus.COMPLETED);
        transitionItems(ctx.items(), BookingStatus.COMPLETED, null);

        // Evict the actor's revenue dashboard (COMPLETED feeds revenue), keyed on the actor id
        // exactly as BookingService#completeBooking does.
        persistAndNotify(ctx.appointment(), ctx.items(), actorId);

        // ONE review prompt for the visit (BE-6) — never one per item. Enqueued in this same
        // completion transaction (enqueueReviewRequested is MANDATORY-propagation); a second complete
        // is impossible (assertHeaderTransition rejects a non-CONFIRMED header), so it is at-most-once
        // by construction. Guest visits have no client to review with — skip (mirrors the single path).
        if (ctx.appointment().getClient() != null) {
            outboxService.enqueueReviewRequested(ctx.firstItem().getId());
        }
    }

    /**
     * Provider-initiated visit no-show — the header and every item move to {@code NOT_COMPLETED} with
     * reason {@code CLIENT_NO_SHOW}; the optional provider note is written to the header. Mirrors
     * {@code BookingService#notCompleteBooking} (same provider authority shape as decline/complete).
     * Also evicts the actor's revenue dashboard after commit for parity with the single path.
     */
    @Transactional
    public void notCompleteAppointment(UUID actorId, UUID appointmentId, AppointmentProviderNoteRequest req) {
        // Cycle-3 audit finding 1 (authz-before-lock): provider authority is resolved via the
        // lightweight BookingCompletionAccess projection (AuthorizationService#enforceCanManageAppointment)
        // — no Booking/Appointment entity load — BEFORE lockHeaderForWholeVisitTransition runs, so a
        // non-owner (or a guessed/foreign id) never causes a real row lock or the item JOIN FETCH
        // below. A missing appointment id and an existing-but-foreign visit both resolve to the SAME
        // 403, instantly, with zero DB writes attempted (Finding 8 — no existence oracle preserved).
        authz.enforceCanManageAppointment(actorId, appointmentId);

        Appointment appointment = lockHeaderForWholeVisitTransition(appointmentId);
        VisitContext ctx = new VisitContext(appointment, loadItemsOrThrow(appointmentId));
        assertHeaderTransition(ctx.appointment(), BookingStatus.NOT_COMPLETED);

        ctx.appointment().setStatus(BookingStatus.NOT_COMPLETED);
        ctx.appointment().setCancellationReason(CancellationReason.CLIENT_NO_SHOW);
        ctx.appointment().setProviderComment(BookingComments.normalize(req == null ? null : req.providerComment()));
        transitionItems(ctx.items(), BookingStatus.NOT_COMPLETED, CancellationReason.CLIENT_NO_SHOW);

        // Evict the actor's revenue dashboard, keyed on the actor id exactly as
        // BookingService#notCompleteBooking does.
        persistAndNotify(ctx.appointment(), ctx.items(), actorId);
    }

    /**
     * Moves an ENTIRE {@code CONFIRMED} multi-service visit to a new future time — every chained
     * item shifts lockstep to a new contiguous, back-to-back block, preserving item order and each
     * item's frozen {@code priceAtBooking} / {@code durationMinutesAtBooking} /
     * {@code bufferMinutesAtBooking} snapshot. The visit-level analogue of {@code PATCH
     * /bookings/{id}/reschedule} (Phase 27.2), lifted to BE-4's all-or-nothing shape.
     *
     * <p><b>Re-layout.</b> {@link VisitPlanner#replanFromNewStart} is the timing-only twin of the
     * create-path {@link VisitPlanner#planChainedItems}: it resolves NOTHING from
     * {@code master_services}, so a catalogue change since booking can never leak into an existing
     * visit's frozen price/duration — only the clock position of the whole block moves.
     *
     * <p><b>Authorization/status/elapsed resolution</b> branches exactly like
     * {@code BookingService#rescheduleBooking}: CLIENT (ownership +
     * {@link #assertVisitNotElapsedForClient}) vs. PROVIDER
     * ({@link AuthorizationService#enforceCanRescheduleBooking} on the first item +
     * {@link BookingTemporalGuard#assertCurrentNotElapsedForReschedule} on the visit's current
     * start).
     *
     * <p><b>Schedule-fit</b> reuses {@link SlotCalculationService#getAvailableSlots(UUID, LocalDate,
     * List)} — the multi-service (BE-2) overload — over the visit's ordered
     * {@code masterServiceId}s, exactly mirroring {@code BookingService#assertStartsOnAvailableSlot}'s
     * single-service call (run BEFORE any lock, same placement).
     *
     * <p><b>Concurrency</b> reuses the exact machinery {@code BookingService#rescheduleBooking} and
     * {@code AppointmentService#doCreateAppointment} share: the client-then-master advisory-lock
     * order (targeting the visit's OWNING CLIENT, {@code appointment.getClient()}, never
     * {@code actorUserId} on the provider path — a guest/LINK visit has no client account, so that
     * step is skipped cleanly), the client-conflict check (excluding the visit's OWN items via
     * {@code appointment_id}, not a single booking id — a visit occupies N rows), and ONE span
     * overlap check over {@code [newFirstStart, newLastEnd)} (again excluding the visit's own rows)
     * — so the whole re-planned block either lands atomically or nothing moves. The per-row
     * {@code no_overlapping_bookings} GIST EXCLUDE remains the authoritative backstop on each
     * update (mapped to the same 409 below).
     *
     * <p><b>Header lock (cycle-5 audit finding 1, 2026-08-03 — fixed here).</b> Before this fix this
     * method took NO appointment-header lock at all — unlike every per-child mutator in this class
     * (per-item decline, per-item cancel, per-item reschedule), which all lock the header first, per
     * the canonical appointments-before-bookings order (cycle-2 audit finding 1) — so a whole-visit
     * reschedule racing any per-child write was never serialized against it. Now locks the header,
     * in the canonical header→client→master order, via {@link #lockAppointmentHeaderBeforeItemReschedule}
     * — the SAME conditional lock ({@code AppointmentRepository#lockHeaderIfConfirmed})
     * {@link #rescheduleAppointmentItem} already takes, matching this method's own precondition
     * ("CONFIRMED, or fail") rather than the unconditional {@link #lockHeaderForWholeVisitTransition}
     * the cancel/decline/complete/not-complete quartet use. Immediately follows with a scalar
     * freshness re-check ({@link BookingRepository#findConfirmedIdsByAppointmentId}) against the
     * items this call is about to move: the lock alone only proves the HEADER is still CONFIRMED,
     * not that every ITEM this call already loaded (necessarily before any lock could protect that
     * read) is still individually CONFIRMED — a concurrent per-service decline can leave the header
     * CONFIRMED (a sibling remains) while flipping ONE of this block's own targets. {@code Booking}
     * now carries {@code @DynamicUpdate} (G1, cycle-7 audit 2026-08-03), so this re-plan's own save
     * would only write {@code starts_at}/{@code ends_at} (+ {@code updated_at}) for that item and
     * could no longer clobber the decline's {@code status} column even without this check — but
     * proceeding anyway would silently move a now-DECLINED leg back onto the calendar at a new
     * time, resurrecting it in effect (its status stays whatever the decline set, but it is no
     * longer a valid, bookable member of this re-planned block). A mismatch aborts with the same
     * 409 shape as every other conflict below — a clean, retryable loss of the race. Both the lock
     * and the re-check run AFTER
     * {@link #assertVisitStartsOnAvailableSlot} and the in-memory re-plan, preserving the existing
     * tight-lock-window discipline (only the client/master advisory locks and the DB writes below were
     * ever inside the lock window; this adds one more cheap, indexed lookup, not a new DB-heavy step).
     *
     * <p><b>Notification</b> is actor-branched exactly like the single-booking path: reuses
     * {@code NotificationOutboxService#enqueueBookingRescheduled} verbatim, referencing the FIRST
     * item only (never one per service) — the drain worker addresses the OTHER party from that one
     * booking, exactly as every other visit-level notification in this class does.
     *
     * @throws ForbiddenException             non-owning client / non-authorized provider (403)
     * @throws BusinessException              non-CONFIRMED visit (either at the initial unlocked
     *                                        check or the post-lock recheck), a conflicting new
     *                                        slot, an unbookable new time, or a target item that
     *                                        left CONFIRMED concurrently (409);
     *                                        {@code BookingStartsAtValidator} rejects a bad new
     *                                        time (400)
     * @throws BookingElapsedException        the CLIENT path only — the visit has already elapsed (409)
     * @throws ClientBookingConflictException  the new window overlaps another booking the owning
     *                                        client already holds (409, {@code CLIENT_BOOKING_CONFLICT})
     */
    @Transactional
    public AppointmentDetailResponse rescheduleAppointment(
            UUID actorUserId, Role actorRole, UUID appointmentId, AppointmentRescheduleRequest req) {
        boolean initiatedByProvider = actorRole != Role.CLIENT;
        VisitContext ctx = initiatedByProvider
                ? resolveVisitForProviderReschedule(actorUserId, appointmentId)
                : resolveVisitForClientReschedule(actorUserId, appointmentId);
        Appointment appointment = ctx.appointment();
        List<Booking> items = ctx.items();
        // Resurrection guard: only CONFIRMED items are re-planned/moved. A per-service-declined child
        // (DECLINED) keeps its released slot and its old row untouched — it must not be shifted back
        // onto the calendar by a whole-visit reschedule. A CONFIRMED header guarantees ≥1 CONFIRMED
        // child (declining the last one collapses the header), so this list is never empty. For the
        // common all-CONFIRMED visit this equals `items` in the same order — behaviour is unchanged.
        List<Booking> confirmedItems = items.stream()
                .filter(b -> b.getStatus() == BookingStatus.CONFIRMED)
                .toList();

        OffsetDateTime newFirstStart = req.newStartsAt();
        BookingStartsAtValidator.validate(newFirstStart, clock);

        Master master = confirmedItems.get(0).getMaster();
        UUID masterId = master.getId();
        List<UUID> masterServiceIds = confirmedItems.stream().map(b -> b.getMasterService().getId()).toList();

        // Same working-hours / effective-day validation the create path relies on, run over the
        // WHOLE multi-service block (BE-2's N-service overload). Run BEFORE any lock, mirroring
        // BookingService#assertStartsOnAvailableSlot's placement.
        assertVisitStartsOnAvailableSlot(masterId, masterServiceIds, newFirstStart);

        // Timing-only re-layout — preserves every item's frozen duration/buffer/price. Re-planned
        // over the CONFIRMED items only (declined items are excluded from the moving block).
        List<VisitPlanner.PlannedWindow> windows = visitPlanner.replanFromNewStart(confirmedItems, newFirstStart);
        OffsetDateTime newLastEnd = windows.get(windows.size() - 1).endsAt();

        // Old (date, masterServiceId) keys, captured BEFORE any item is mutated, for after-commit
        // cache eviction (mirrors AppointmentService#registerSlotEviction).
        Set<SlotKey> oldSlotKeys = collectSlotKeys(confirmedItems);

        // Cycle-5 audit finding 1 (2026-08-03) — canonical appointments-before-bookings lock order
        // (cycle-2 audit finding 1), applied here for the FIRST time on this method: everything
        // above (the unlocked resolve/authz/status/elapsed checks, the availability query, and the
        // in-memory re-plan) stays exactly where it was — only the lock itself is new, positioned
        // here, immediately before the client/master advisory locks, so header→client→master is the
        // full acquisition order. Reuses lockAppointmentHeaderBeforeItemReschedule — the SAME
        // conditional lock rescheduleAppointmentItem already takes for this exact seam — rather than
        // inventing a new one; a false result means a concurrent per-child writer moved the header
        // out of CONFIRMED since the unlocked check above, reported as the same 409 shape that
        // caller uses for its own analogous false case.
        if (!lockAppointmentHeaderBeforeItemReschedule(appointmentId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "Visit is no longer CONFIRMED");
        }

        // Freshness re-check, immediately after the lock — see this method's own "Header lock"
        // Javadoc paragraph for why this is required, not optional, once ANY item is loaded before
        // the lock exists to protect that read: the lock above proves the HEADER is still
        // CONFIRMED, not that every item THIS call is about to move is. A scalar,
        // entity-manager-bypassing projection (never re-touches the already-managed, possibly-stale
        // Booking instances) is the only way to answer that without poisoning — or being poisoned
        // by — the identity map.
        Set<UUID> stillConfirmedIds = bookingRepository.findConfirmedIdsByAppointmentId(appointmentId);
        boolean anyTargetLeftConfirmed = confirmedItems.stream()
                .map(Booking::getId)
                .anyMatch(id -> !stillConfirmedIds.contains(id));
        if (anyTargetLeftConfirmed) {
            throw new BusinessException(HttpStatus.CONFLICT, "Visit changed concurrently — please retry");
        }

        // Same critical section as doCreateAppointment / rescheduleBooking, client-then-master
        // order — deadlock freedom (see BookingRepository.acquireClientAdvisoryLockWithTimeout).
        User owningClient = appointment.getClient();
        Integer lockResult;
        if (owningClient != null) {
            acquireClientLock(owningClient.getId());
            assertNoClientConflictExcludingAppointment(owningClient.getId(), newFirstStart, newLastEnd, appointmentId);
            lockResult = bookingRepository.acquireAdvisoryLock(masterId);
        } else {
            // No client lock was taken (guest visit) — the master lock must fuse its own
            // lock_timeout, mirroring GuestBookingService/BookingService's no-client-lock shape.
            lockResult = bookingRepository.acquireAdvisoryLockWithTimeout(masterId);
        }
        if (lockResult == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Advisory lock acquisition failed");
        }

        // ONE span overlap check over the WHOLE new block, excluding the visit's own rows — the
        // same "check once, insert/update N" shape doCreateAppointment uses for creation.
        if (bookingRepository.existsOverlapExcludingAppointment(masterId, newFirstStart, newLastEnd, appointmentId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        for (int i = 0; i < confirmedItems.size(); i++) {
            VisitPlanner.PlannedWindow window = windows.get(i);
            confirmedItems.get(i).reschedule(window.startsAt(), window.endsAt());
        }

        List<Booking> saved;
        try {
            saved = bookingRepository.saveAll(confirmedItems);
            bookingRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        // Phase 27.3 outbox family, reused verbatim: referencing the FIRST (confirmed) item only
        // (never one per service) — the drain worker addresses the OTHER party from that one booking.
        outboxService.enqueueBookingRescheduled(saved.get(0).getId(), initiatedByProvider);

        registerRescheduleEviction(masterId, salonIdOfMaster(master), oldSlotKeys, collectSlotKeys(saved));

        // Render the FULL visit (moved CONFIRMED items are mutated in place within `items`; any
        // declined item keeps its old row) so the response still shows every service line.
        return appointmentService.enrich(appointment, items);
    }

    /**
     * Moves ONE service line of a CONFIRMED multi-service visit to a new time, leaving every
     * sibling item's window byte-for-byte unchanged (LOCKED per-item semantics — phase 30.1 L1/L2).
     * The per-item counterpart of {@link #rescheduleAppointment}, exposed at
     * {@code PATCH /appointments/{appointmentId}/services/{bookingId}/reschedule} (phase 30.5).
     *
     * <p><b>Contiguity is deliberately relaxed.</b> Unlike {@link #rescheduleAppointment}, which
     * re-lays-out the whole block back-to-back via {@link VisitPlanner#replanFromNewStart}, this
     * method performs NO follower re-layout, NO cascade and NO gap-closing. After it runs the
     * visit's items may have gaps between them; the appointment stays ONE appointment with ALL of
     * its items. What is NOT relaxed is the no-sibling-overlap invariant (phase 30.3) —
     * {@link #assertNoSiblingOverlap} plus the master-scoped {@code existsOverlapExcluding} plus the
     * DB's {@code no_overlapping_bookings} EXCLUDE constraint all still forbid a moved item from
     * colliding with a CONFIRMED sibling of the SAME visit (a self-double-book of one master).
     *
     * <p><b>The item stays CONFIRMED at the new time</b> and the header's status cannot change, so
     * unlike the per-item decline/cancel paths there is NO phase-2 header collapse (phase 30.2 D2) —
     * verify this is NOT "fixed" back in by a later reader trying to restore symmetry.
     *
     * <p><b>Authorization runs before any entity load</b> (phase 30.1 D5, commit {@code 4d156c0}):
     * the provider path uses the projection-only {@link AuthorizationService#enforceCanManageAppointment};
     * the client path compares {@link AppointmentRepository#findClientIdById} against
     * {@code actorUserId} — a missing appointment, a foreign visit, and a guest (LINK) visit all
     * collapse to the SAME uniform 403 (no existence oracle). The {@code bookingId}-not-a-child 404
     * is reached only once the caller is already authorized on the visit.
     *
     * <p><b>The temporal guard is PER-ROW, not visit-level</b> (phase 30.1 D6) —
     * {@link #assertVisitNotElapsedForClient} is deliberately never called here: an elapsed sibling
     * must not freeze a still-future leg, which is exactly the outcome this feature exists to avoid.
     *
     * <p><b>Lock order: header → client → master</b> (canonical appointments-before-bookings order,
     * cycle-2 audit finding 1) — {@link #lockAppointmentHeaderBeforeItemReschedule} runs first and,
     * UNLIKE its other caller ({@code BookingService#rescheduleBooking}, phase 30.2), a {@code false}
     * result here IS a 409: on this appointment-scoped route the header is a named part of the
     * request, so a non-CONFIRMED header is a genuine conflict the caller must see.
     *
     * <p><b>Freshness re-check (F1, HIGH, cycle-6 audit 2026-08-03 — fixed here).</b> Immediately
     * after the header lock above succeeds, and BEFORE {@code target} is mutated, this method
     * re-verifies {@code target}'s OWN status via {@link BookingRepository#existsConfirmedById} — a
     * scalar, entity-manager-bypassing probe, never a second entity load of {@code target} itself.
     * Before this fix, the lock above only proved the HEADER was still CONFIRMED; it said nothing
     * about {@code target}, which was loaded (via {@link #loadItemsOrThrow}) necessarily BEFORE any
     * lock could protect that read. A per-service decline or a per-service client cancel of THIS
     * SAME leg (fired near-concurrently at the two sibling per-item endpoints) leaves the header
     * CONFIRMED — a sibling remains — so BOTH operations' header-lock guard passes. {@code Booking}
     * now carries {@code @DynamicUpdate} (G1, cycle-7 audit 2026-08-03): this method only calls
     * {@code target.reschedule(...)}, which touches {@code startsAt}/{@code endsAt} alone, so the
     * status field stays at its loaded (stale, {@code CONFIRMED}) value and Hibernate's dirty-check
     * finds it UNCHANGED from the load-time snapshot — the resulting UPDATE never mentions
     * {@code status} at all, and the concurrent decline/cancel's terminal status structurally
     * cannot be resurrected by this save, WITH OR WITHOUT this recheck. What the recheck still
     * prevents is the resulting incoherent state where a leg the client just cancelled (or the
     * provider just declined) silently acquires a brand-new time while remaining in that terminal
     * status — a confusing outcome for the caller even though no column is corrupted. A mismatch
     * aborts with the same 409 shape as every other conflict below — a clean, retryable loss of the
     * race. Runs BEFORE the client/master advisory locks, preserving the existing tight-lock-window
     * discipline (mirrors
     * {@link #rescheduleAppointment}'s identical placement).
     *
     * @throws ForbiddenException             missing, foreign, or guest visit (uniform 403)
     * @throws NotFoundException              {@code bookingId} is not a child of {@code appointmentId} (404)
     * @throws BusinessException              header or item not CONFIRMED, sibling overlap, or slot
     *                                        unavailable (409); a bad {@code newStartsAt} (400)
     * @throws BookingElapsedException        the CLIENT is moving an already-elapsed item (409)
     * @throws ClientBookingConflictException the new window overlaps another booking the owning
     *                                        client holds (409, {@code CLIENT_BOOKING_CONFLICT})
     */
    @Transactional
    public AppointmentDetailResponse rescheduleAppointmentItem(
            UUID actorUserId, Role actorRole, UUID appointmentId, UUID bookingId,
            AppointmentItemRescheduleRequest req) {
        boolean initiatedByProvider = actorRole != Role.CLIENT;

        // Step 2 — authorize BEFORE any entity load (phase 30.1 D5). Provider: the projection-only,
        // per-appointment authority check. Client: a bare client_id projection compared to the
        // actor — a missing/foreign/guest visit all collapse to the SAME uniform 403.
        if (initiatedByProvider) {
            authz.enforceCanManageAppointment(actorUserId, appointmentId);
        } else {
            UUID ownerClientId = appointmentRepository.findClientIdById(appointmentId)
                    .orElseThrow(() -> new ForbiddenException("Access denied"));
            if (!ownerClientId.equals(actorUserId)) {
                throw new ForbiddenException("Access denied");
            }
        }

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        List<Booking> items = loadItemsOrThrow(appointmentId);
        Booking target = items.stream()
                .filter(item -> item.getId().equals(bookingId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Appointment service not found"));

        // Security audit F5 (cross-batch) — defense-in-depth tripwire, NOT a redundant re-check of
        // the guard above. The CLIENT branch above authorizes off Appointment.client_id alone (a
        // projection-only, pre-load comparison); this checks Booking.client on the TARGET row
        // itself — a DIFFERENT column the DB does not constrain to agree with the appointment's
        // client_id (no FK/CHECK ties the two). Today they are always equal by construction
        // (AppointmentService#doCreateAppointment sets both from the same variable), but that is an
        // application-level invariant only, and this is the one per-item CLIENT path that does not
        // otherwise re-verify it: the provider branch gets an equivalent second layer from
        // AuthorizationService#enforceCanManageAppointment (which reads every item's OWN
        // masterUserId/salonId, not the header's), and BookingService#cancelAppointmentItem's client
        // path gets it "for free" because cancelBooking's own load re-filters by booking.getClient().
        // Fails with the SAME uniform 403 as the guard above — never a distinguishable error.
        if (!initiatedByProvider
                && (target.getClient() == null || !target.getClient().getId().equals(actorUserId))) {
            throw new ForbiddenException("Access denied");
        }

        assertItemReschedulable(target);
        if (initiatedByProvider) {
            BookingTemporalGuard.assertCurrentNotElapsedForReschedule(target.getStartsAt(), clock);
        } else {
            assertItemNotElapsedForClient(target);
        }

        OffsetDateTime newStartsAt = req.newStartsAt();
        BookingStartsAtValidator.validate(newStartsAt, clock);

        Master master = target.getMaster();
        UUID masterId = master.getId();

        // Same working-hours / effective-day validation the single-booking reschedule relies on —
        // the SINGLE-service getAvailableSlots overload, since only ONE item moves (never the
        // multi-service overload rescheduleAppointment uses for the whole block). Run BEFORE any
        // lock, keeping the lock window tight (backend-perf).
        assertItemStartsOnAvailableSlot(masterId, target.getMasterService().getId(), newStartsAt);

        // Duration + buffer are frozen at the original booking; mirror the create-path end-time
        // formula. NEVER VisitPlanner — no re-layout, no cascade (L2).
        OffsetDateTime newEndsAt = newStartsAt.plusMinutes(
                (long) target.getDurationMinutesAtBooking() + target.getBufferMinutesAtBooking());

        // Phase 30.3 layer 1 — in-memory sibling pre-check, before any lock, zero extra queries.
        assertNoSiblingOverlap(items, bookingId, newStartsAt, newEndsAt);

        // Captured BEFORE any mutation, for after-commit cache eviction.
        Set<SlotKey> oldSlotKeys = collectSlotKeys(List.of(target));

        // Lock order: header → client → master (canonical, cycle-2 audit finding 1). Unlike
        // BookingService#rescheduleBooking's use of this SAME method (phase 30.2), a false result
        // here IS a 409 — the header is a named part of THIS route's request (phase 30.1 D4).
        if (!lockAppointmentHeaderBeforeItemReschedule(appointmentId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "Visit is no longer CONFIRMED");
        }

        // Freshness re-check (F1, HIGH, cycle-6 audit 2026-08-03) — see this method's own
        // "Freshness re-check" Javadoc paragraph above for the full rationale. `target` was loaded
        // before this lock existed to protect that read; the lock alone only proves the HEADER is
        // still CONFIRMED, not that THIS specific item still is.
        if (!bookingRepository.existsConfirmedById(target.getId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Service changed concurrently — please retry");
        }

        Integer lockResult;
        User owningClient = appointment.getClient();
        if (owningClient != null) {
            acquireClientLock(owningClient.getId());
            assertNoClientConflictExcludingBooking(owningClient.getId(), newStartsAt, newEndsAt, bookingId);
            lockResult = bookingRepository.acquireAdvisoryLock(masterId);
        } else {
            // No client lock was taken (guest visit) — the master lock must fuse its own
            // lock_timeout, mirroring every other no-client-lock shape in this codebase.
            lockResult = bookingRepository.acquireAdvisoryLockWithTimeout(masterId);
        }
        if (lockResult == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Advisory lock acquisition failed");
        }

        // Phase 30.3 layer 3 — booking-scoped exclusion (excludes ONLY the moving row), so a
        // CONFIRMED sibling of the SAME visit IS caught. Never existsOverlapExcludingAppointment,
        // whose appointment-wide exclusion is blind to siblings by construction (30.3's single
        // highest-risk mistake, called out in three phase docs).
        if (bookingRepository.existsOverlapExcluding(masterId, newStartsAt, newEndsAt, bookingId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        target.reschedule(newStartsAt, newEndsAt);
        Booking saved;
        try {
            saved = bookingRepository.saveAndFlush(target);
        } catch (DataIntegrityViolationException e) {
            // Phase 30.3 layer 4 — the DB's own no_overlapping_bookings EXCLUDE constraint,
            // authoritative even if every application guard above were bypassed.
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        // No collapseHeaderIfNoConfirmedSiblingsRemain call — the item stays CONFIRMED, so the
        // header's status cannot change (phase 30.2 D2). This is a deliberate omission, not an
        // oversight; do not "restore symmetry" with the decline/cancel item paths by adding one.

        // Reference the MOVED CHILD (never item 0) so the notification names the right service.
        outboxService.enqueueBookingRescheduled(saved.getId(), initiatedByProvider);

        registerRescheduleEviction(masterId, salonIdOfMaster(master), oldSlotKeys, collectSlotKeys(List.of(saved)));

        // The in-memory list was loaded ordered by the OLD startsAt; the mutation above may have
        // moved `target` out of that order, so it must be re-sorted before rendering — the stale
        // order would otherwise emit a wrong header startsAt and a wrong items[] order (phase 30.4
        // step 19 / phase 30.1 D2). The id tiebreak keeps the order deterministic when a moved item
        // lands exactly on a sibling's start (legal per phase 30.3).
        List<Booking> reordered = items.stream()
                .sorted(Comparator.comparing(Booking::getStartsAt).thenComparing(Booking::getId))
                .toList();
        return appointmentService.enrich(appointment, reordered);
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private List<Booking> loadItemsOrThrow(UUID appointmentId) {
        List<Booking> items = bookingRepository.findByAppointmentIdWithGraph(appointmentId);
        if (items.isEmpty()) {
            // Unreachable for a real visit (always ≥1 chained row); guard so firstItem()/eviction
            // never dereference an empty list.
            throw new NotFoundException("Appointment not found");
        }
        return items;
    }

    /**
     * Shared guard for the four WHOLE-VISIT transition methods (cancel/decline/complete/notComplete):
     * unconditionally locks the header row via {@link AppointmentRepository#lockHeaderRegardlessOfStatus}
     * — collapsing a missing appointment id to the SAME uniform 403 every caller already throws for an
     * existing-but-foreign visit (Finding 8, no existence oracle) — then loads the now-locked entity
     * for the caller's own status checks.
     *
     * <p><b>Called AFTER authorization, not before it (cycle-3 audit finding 1).</b> Every caller
     * resolves existence/ownership FIRST — via a scalar/projection query that never hydrates a
     * {@code Booking}/{@code Appointment} entity ({@link AppointmentRepository#findClientIdById} for
     * {@code cancelAppointment}, {@code AuthorizationService#enforceCanManageAppointment} for the
     * three provider transitions) — and only calls this method once that check has already thrown for
     * a non-owner. A cycle-2 wording error had this method called at the literal top of each method,
     * BEFORE authorization: a caller who did not own the appointment could still force a real row lock
     * (and the item {@code JOIN FETCH} that followed) with a guessed UUID before being rejected, and
     * created a timing oracle (a contended foreign id blocked up to the 3s {@code lock_timeout} before
     * a 409, versus an instant 403 for a non-existent id). Fixed: no lock is taken, and no entity
     * (including this one) is loaded, until authorization has already succeeded.
     *
     * <p><b>Lock order (cycle-2 audit finding 1) is unaffected by the above.</b> Still called BEFORE
     * any chained item is loaded or mutated, preserving the canonical
     * {@code appointments}-row-before-{@code bookings}-rows lock order shared with the per-item paths
     * ({@link #lockHeaderBeforeItemTransition}, {@code BookingService#cancelBooking}) — see
     * {@link AppointmentRepository#lockHeaderRegardlessOfStatus}'s Javadoc for the full deadlock-order
     * rationale. The pre-lock authorization check reads NOTHING from {@code bookings}/{@code appointments}
     * via an entity load (only scalar projections), so the write-lock order this method establishes is
     * still the very first entity-level touch of either table in the transaction.
     *
     * <p>Package-private (not {@code private}) so
     * {@code AppointmentCrossPathTransitionConcurrencyIT} (same package) can {@code @SpyBean} +
     * {@code doAnswer} this EXACT method to force a whole-visit racer to rendezvous with a
     * per-item racer at the precise instant both are about to attempt the SAME row lock — the
     * regression test for finding 1.
     *
     * @return the locked, fully-loaded header entity — never {@code null}
     * @throws ForbiddenException the appointment id does not exist (403 — no existence oracle)
     */
    Appointment lockHeaderForWholeVisitTransition(UUID appointmentId) {
        if (appointmentRepository.lockHeaderRegardlessOfStatus(appointmentId).isEmpty()) {
            throw new ForbiddenException("Access denied");
        }
        // The lock above already proved the row exists and is now held by this transaction, so this
        // load cannot legitimately come back empty — the orElseThrow is defensive, not a real branch.
        return appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
    }

    /**
     * The single source of the illegal/duplicate-transition error — same {@link BusinessException}
     * ({@code 400}) with the same message shape as {@code BookingService#assertTransition}. A visit
     * that is already terminal (re-decline, cancel-after-complete, etc.) is only ever transitionable
     * from {@code CONFIRMED}, so a non-CONFIRMED header is rejected identically to the single path —
     * no new error envelope is invented.
     */
    private void assertHeaderTransition(Appointment appointment, BookingStatus target) {
        if (appointment.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(
                    "Cannot transition from %s to %s".formatted(appointment.getStatus(), target));
        }
    }

    /**
     * Per-CHILD transition guard for {@link #declineAppointmentItem} — a single service line is only
     * declinable from {@code CONFIRMED}; an already-terminal child (a prior per-service decline, or a
     * whole-visit terminal) is a {@code 409}. The visit-level analogue of the header guard, evaluated
     * on the child row rather than the header (the header may still be CONFIRMED with mixed children).
     */
    private void assertItemTransition(Booking item, BookingStatus target) {
        if (item.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Cannot transition service from %s to %s".formatted(item.getStatus(), target));
        }
    }

    /**
     * Phase 1 of the two-phase per-item header recompute, shared by BOTH per-item paths —
     * {@link #declineAppointmentItem} (provider decline) and
     * {@link #lockAppointmentHeaderBeforeClientItemCancel} (client cancel, track 27.x widening).
     * Locks the header via {@link AppointmentRepository#lockHeaderIfConfirmed}
     * ({@code SELECT ... FOR UPDATE ... WHERE status = 'CONFIRMED'}) and reports whether it was
     * actually CONFIRMED-and-now-locked, WITHOUT touching the collapse UPDATE yet.
     *
     * <p><b>Split from the collapse step (cycle-2 audit finding 1 — lock order).</b> The single
     * combined lock-then-collapse method this replaced was called AFTER the caller's own child row
     * was mutated/saved — inverted relative to the canonical {@code appointments}-before-
     * {@code bookings} lock order the whole-visit methods now take explicitly
     * ({@link #lockHeaderForWholeVisitTransition}). The LOCK half can and must move earlier (before
     * the caller writes its child row); the COLLAPSE half (phase 2,
     * {@link #collapseHeaderAfterItemTransition}) cannot — its {@code NOT EXISTS} sibling check is
     * only correct once the child's OWN new status is visible, so it must stay positioned after the
     * child save. Splitting the two is the only way to satisfy both constraints at once.
     *
     * @return {@code true} if the header was CONFIRMED and is now locked by this transaction —
     *         {@link #collapseHeaderAfterItemTransition} must run later in this same transaction
     *         iff this is {@code true}; {@code false} means the header had already left CONFIRMED
     *         (nothing was locked, nothing needs collapsing — a harmless idempotent replay)
     */
    private boolean lockHeaderBeforeItemTransition(UUID appointmentId) {
        return appointmentRepository.lockHeaderIfConfirmed(appointmentId).isPresent();
    }

    /**
     * Thin package-private wrapper around {@link #lockHeaderBeforeItemTransition}, originally added
     * for {@link #declineAppointmentItem} specifically — same visibility rationale as
     * {@link #lockAppointmentHeaderBeforeClientItemCancel}/
     * {@link #lockAppointmentHeaderBeforeItemReschedule}: {@code AppointmentCrossPathTransitionConcurrencyIT}
     * (same package) needs an exact, {@code @SpyBean}-able rendezvous point at THIS method's
     * precise header-lock instant to force a per-item decline racer to arrive at its own lock
     * attempt at effectively the same moment as another racer (F1, HIGH, cycle-6 audit
     * 2026-08-03). Spying {@link com.beautica.booking.repository.AppointmentRepository} directly is
     * not viable here — Mockito cannot {@code callRealMethod()} on a Spring Data JPA repository's
     * dynamically-proxied interface method — so, exactly like the cancel/reschedule pair, a bare
     * package-private forwarding method is the seam. No behavioural change: {@code
     * declineAppointmentItem} previously called {@link #lockHeaderBeforeItemTransition} directly.
     *
     * <p><b>Now also used by {@link #declineAppointmentItems}</b> (the batched sibling; G3, cycle-7
     * audit 2026-08-03). Previously that method called the private method directly, on the
     * reasoning that it had "no dedicated concurrency IT requiring this seam" — REJECTED by this
     * cycle's audit (see {@link #declineAppointmentItems}'s own Javadoc, "Batched freshness
     * re-check"): it has an obvious cross-endpoint racer via {@code ScheduleOverrideConflictService},
     * and this cycle adds the dedicated IT that racer needed. Routing both callers through this SAME
     * wrapper — rather than adding a second, near-duplicate one — means one spy point now serves
     * both the single-item and batched decline concurrency tests.
     */
    boolean lockAppointmentHeaderBeforeItemDecline(UUID appointmentId) {
        return lockHeaderBeforeItemTransition(appointmentId);
    }

    /**
     * Phase 2 of the two-phase per-item header recompute — must run AFTER the caller's own child row
     * mutation is at least pending in the SAME persistence context (a prior {@code save(...)} call in
     * this transaction), and only when {@code headerWasLocked} (the phase-1 result) is {@code true}.
     *
     * <p><b>Locked, safe invariant (unchanged):</b> while ≥1 child remains CONFIRMED the header stays
     * CONFIRMED, untouched; losing the LAST CONFIRMED child collapses the header to {@code target}
     * with {@code reason}.
     *
     * <p>Delegates to {@link AppointmentRepository#collapseHeaderIfNoConfirmedSiblingsRemain}, ONE
     * conditional {@code UPDATE} whose {@code WHERE} clause folds the "no CONFIRMED sibling remains"
     * check into the same statement that flips the header — see that method's Javadoc "Concurrency"
     * section for why the phase-1 lock is what actually makes this atomic against a truly-concurrent
     * sibling transition, not this UPDATE's conditional {@code WHERE} alone.
     * {@code flushAutomatically = true} on that repository method flushes the caller's pending child
     * UPDATE (made moments earlier via {@code bookingRepository.save(...)} in the SAME transaction)
     * BEFORE this statement's {@code NOT EXISTS} subquery runs — the subquery reads straight from the
     * DB and bypasses the Hibernate session, so without that flush it could still see the child's
     * PRE-transition status.
     *
     * <p>{@code headerNote} is written unconditionally in the SAME {@code UPDATE} as
     * {@code status}/{@code reason} — safe against
     * {@code chk_appointment_client_cancellation_note_status} (V124) because Postgres evaluates a row
     * CHECK against the row's FINAL post-statement values, never an intermediate per-column state.
     * Pass {@code null} when the per-item note legitimately lives on the CHILD instead (the provider
     * per-service decline shape).
     *
     * <p><b>Round-trip cost (cycle-2 audit finding 4 — corrected).</b> {@code headerWasLocked = false}
     * (the header had ALREADY left CONFIRMED before phase 1 ran — an idempotent replay) is the ONLY
     * case that skips this call entirely, saving a round trip. In the common case — one leg
     * transitioning while ≥1 sibling remains CONFIRMED — phase 1's lock still succeeds
     * ({@code headerWasLocked = true}), so THIS UPDATE still runs, just as a guaranteed 0-row no-op
     * (the {@code NOT EXISTS} check correctly finds a CONFIRMED sibling and refuses to flip the
     * header). Steady state is therefore reliably lock (phase 1) + conditional UPDATE (phase 2) — two
     * round trips, not one; only the terminal-replay edge case is cheaper.
     */
    private void collapseHeaderAfterItemTransition(
            UUID appointmentId, boolean headerWasLocked, BookingStatus target, CancellationReason reason,
            String headerNote) {
        if (!headerWasLocked) {
            return;
        }
        appointmentRepository.collapseHeaderIfNoConfirmedSiblingsRemain(
                appointmentId, target.name(), reason.name(), headerNote);
    }

    /**
     * Phase 1 (lock) of the header recompute for a CLIENT per-leg cancel via
     * {@code PATCH /bookings/{id}/cancel} (track 27.x widening —
     * {@code BookingService#cancelBooking} no longer refuses an appointment child). Called by
     * {@code BookingService#cancelBooking} BEFORE it saves the target child's own CANCELLED status —
     * the canonical appointments-before-bookings lock order (cycle-2 audit finding 1) — delegating to
     * the shared {@link #lockHeaderBeforeItemTransition}.
     *
     * <p><b>Visibility (cycle-2 audit finding 5, resolved).</b> Package-private, not {@code public}:
     * its only caller, {@code BookingService#cancelBooking}, already lives in this same package
     * ({@code com.beautica.booking.service}), and {@code AppointmentClientLegCancelConcurrencyIT} —
     * the regression test that {@code @SpyBean}s this exact method — now lives in this package too
     * (moved from {@code com.beautica.booking} specifically to unblock this narrowing; it shares the
     * now-{@code public} {@code com.beautica.booking.BookingTestFixtures} across the package
     * boundary instead).
     *
     * <p><b>No authorization check of its own.</b> The caller MUST have already established that
     * {@code appointmentId} is a header the acting client is authorized to mutate (as
     * {@code BookingService#cancelBooking} does, transitively, by having already loaded and validated
     * ownership of the CHILD booking that carries this {@code appointmentId} FK) before calling this
     * method — it trusts that precondition unconditionally.
     *
     * @return see {@link #lockHeaderBeforeItemTransition} — the caller must thread this into
     *         {@link #collapseAppointmentHeaderAfterClientItemCancel} later in the SAME transaction
     */
    @Transactional
    boolean lockAppointmentHeaderBeforeClientItemCancel(UUID appointmentId) {
        return lockHeaderBeforeItemTransition(appointmentId);
    }

    /**
     * Phase-1 header lock for a per-item RESCHEDULE that does NOT collapse the header — the
     * reschedule counterpart of {@link #lockAppointmentHeaderBeforeClientItemCancel} (phase 30.2 —
     * closes a latent lock-order defect: {@code BookingService#rescheduleBooking} already moves a
     * single appointment child today, but — unlike {@code cancelBooking}, which compensates with
     * exactly this seam — took only the per-client/per-master advisory locks and never locked the
     * {@code appointments} header at all, inverting the canonical appointments-before-bookings lock
     * order cycle-2 audit finding 1 established for every other class of per-child write).
     *
     * <p><b>No phase-2 companion, deliberately.</b> A rescheduled item stays {@code CONFIRMED} — its
     * transition never removes a {@code CONFIRMED} sibling from the header's count — so
     * {@link AppointmentRepository#collapseHeaderIfNoConfirmedSiblingsRemain}'s {@code NOT EXISTS}
     * guard could never fire for this caller. Adding a collapse call here would be a pointless
     * {@code UPDATE}; do not "restore symmetry" with the cancel pair by adding one.
     *
     * <p><b>Three callers, not each rolling their own lock call.</b> {@code
     * BookingService#rescheduleBooking} (this bugfix, cross-package — hence package-private, not
     * {@code private}) discards the result: on the legacy booking-scoped route a non-CONFIRMED
     * header under a CONFIRMED child is a state the child-level status guard already vetted, so
     * turning {@code false} into an error here would be a behaviour change beyond a lock-order fix.
     * {@link #rescheduleAppointmentItem} (the appointment-scoped per-item route, phase 30.4, same
     * class) and {@link #rescheduleAppointment} (the WHOLE-visit route, cycle-5 audit finding 1,
     * 2026-08-03 — this method had NO header lock at all before that fix) both DO treat {@code
     * false} as a 409 instead — on both routes the header is either a named part of the request URL
     * or the very resource the caller asked to move, so a non-CONFIRMED header is a genuine state
     * conflict either caller must see. {@link #rescheduleAppointment}'s own precondition ("CONFIRMED,
     * or fail") is exactly this method's shape, which is why it reuses this seam rather than
     * {@link #lockHeaderForWholeVisitTransition} (unconditional lock, status asserted separately in
     * Java) despite otherwise belonging to the whole-visit family — all three callers reuse this ONE
     * seam so none of them can drift on lock order relative to the others.
     *
     * @return see {@link #lockHeaderBeforeItemTransition} — interpreted differently by
     *         {@code BookingService#rescheduleBooking} versus the other two callers above; never a
     *         phase-2 trigger either way, since a rescheduled item never leaves {@code CONFIRMED}.
     */
    @Transactional
    boolean lockAppointmentHeaderBeforeItemReschedule(UUID appointmentId) {
        return lockHeaderBeforeItemTransition(appointmentId);
    }

    /**
     * Phase 2 (collapse) of the header recompute for a CLIENT per-leg cancel — the client-cancel
     * counterpart of {@link #collapseHeaderAfterItemTransition}, called by
     * {@code BookingService#cancelBooking} AFTER it has persisted (via
     * {@code bookingRepository.save(...)}) the target child's own CANCELLED status in the SAME
     * transaction (this method's default REQUIRED propagation joins the caller's
     * {@code @Transactional}). Same locked invariant as the provider per-service decline (header
     * stays CONFIRMED while ≥1 sibling remains CONFIRMED; cancelling the LAST CONFIRMED child
     * collapses the header to CANCELLED with reason CLIENT_CANCELLED), but — UNLIKE the decline
     * precedent — the client's cancellation note legitimately belongs on the HEADER even for a
     * single-leg cancel (booking-notes are visit-level, mutually visible, not per-service), so it is
     * passed through here to be written on the transitioning branch.
     *
     * @param appointmentId          the visit header owning the just-cancelled child
     * @param headerWasLocked        the result of the matching
     *                               {@link #lockAppointmentHeaderBeforeClientItemCancel} call earlier
     *                               in THIS transaction
     * @param clientCancellationNote the client's already-normalized cancellation note for THIS
     *                               cancel action, or {@code null}; written to the header only if the
     *                               header actually transitions to CANCELLED
     */
    @Transactional
    void collapseAppointmentHeaderAfterClientItemCancel(
            UUID appointmentId, boolean headerWasLocked, String clientCancellationNote) {
        collapseHeaderAfterItemTransition(
                appointmentId, headerWasLocked, BookingStatus.CANCELLED, CancellationReason.CLIENT_CANCELLED,
                clientCancellationNote);
    }

    /**
     * Visit read-only-after-elapse guard for the CLIENT cancel/reschedule-whole-visit path — mirrors
     * {@code BookingService#assertNotElapsedForClient} lifted to the visit: the window has fully
     * passed once the visit's END — {@code max(endsAt)} over every item (terminal included), NOT
     * {@code items.get(size-1).getEndsAt()} (phase 30.1/30.7: the row with the greatest
     * {@code startsAt} is no longer necessarily the row with the greatest {@code endsAt} once a
     * per-item move can separate a terminal sibling from the rest) — is before "now". Compared on
     * the absolute instant via the injected {@link Clock} so tests can pin an elapsed visit
     * deterministically.
     *
     * <p><b>Whole-visit only.</b> A per-item move ({@link #rescheduleAppointmentItem}) uses the
     * PER-ROW temporal guard instead (phase 30.1 D6) — an elapsed sibling must never freeze a still-
     * future leg, which this whole-visit guard would otherwise do if reused there.
     */
    private void assertVisitNotElapsedForClient(List<Booking> items) {
        OffsetDateTime visitEnd = items.stream()
                .map(Booking::getEndsAt)
                .max(Comparator.naturalOrder())
                .orElseThrow(); // items is non-empty — loadItemsOrThrow guards this
        if (visitEnd.toInstant().isBefore(clock.instant())) {
            throw new BookingElapsedException();
        }
    }

    /**
     * CLIENT-path ownership + status + elapsed resolution for {@link #rescheduleAppointment} —
     * mirrors {@link #cancelAppointment}'s existence/ownership collapse (a missing id, a guest
     * visit, and a foreign visit are indistinguishably a single {@code 403}), then the same
     * {@code CONFIRMED}-only status guard ({@code 409}, matching
     * {@code BookingService#resolveBookingForClientReschedule}'s status wording) and the same
     * read-only-after-elapse guard ({@link #assertVisitNotElapsedForClient}).
     */
    private VisitContext resolveVisitForClientReschedule(UUID actorUserId, UUID appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .filter(a -> a.getClient() != null && a.getClient().getId().equals(actorUserId))
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        List<Booking> items = loadItemsOrThrow(appointmentId);

        if (appointment.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Cannot reschedule a booking in status %s".formatted(appointment.getStatus()));
        }
        assertVisitNotElapsedForClient(items);
        return new VisitContext(appointment, items);
    }

    /**
     * PROVIDER-path authorization + status + elapsed resolution for {@link #rescheduleAppointment}.
     * Existence + ownership collapse to a single uniform 403 (Finding 8 — existence oracle): a
     * missing appointment id and an existing-but-foreign visit are indistinguishable to the caller,
     * exactly as decline/complete/not-complete now do. Guards temporal validity via
     * {@link BookingTemporalGuard#assertCurrentNotElapsedForReschedule} on the visit's current start
     * — NOT {@link #assertVisitNotElapsedForClient} (which compares the visit's END): a provider can
     * no longer move a visit that has already begun, mirroring
     * {@code BookingService#resolveBookingForProviderReschedule} exactly. The CLIENT reschedule path
     * ({@link #resolveVisitForClientReschedule}) is unaffected — it already collapses to 403.
     *
     * <p><b>F2 (MEDIUM) + F3 (LOW), cycle-6 audit 2026-08-03 — fixed here.</b> This method used to
     * authorize via {@link AuthorizationService#enforceCanRescheduleBooking} on {@code
     * ctx.firstItem()} alone — the single-{@code Booking} overload, which checks only item[0]'s
     * master/salon — AFTER already loading the full {@code Appointment} ({@code findById}) and the
     * 5-way-{@code JOIN FETCH} item list ({@link #loadItemsOrThrow}). Two defects: (F2) this is
     * narrower than the {@code @PreAuthorize} SpEL it replaced ({@code canRescheduleAppointment},
     * which required authority over EVERY item via
     * {@link BookingRepository#findAllCompletionAccessByAppointmentId}'s {@code allMatch}), and
     * inconsistent with every sibling whole-visit transition
     * ({@link #declineAppointment}/{@link #completeAppointment}/{@link #notCompleteAppointment}),
     * all of which authorize via the all-items {@link AuthorizationService#enforceCanManageAppointment}.
     * A visit is single-master only by CONSTRUCTION of today's only writer
     * ({@code VisitPlanner.planChainedItems} resolves every item off one {@code Master}, and
     * nothing ever calls {@code Booking.setMaster()} afterwards) — {@code
     * findAllCompletionAccessByAppointmentId}'s own Javadoc states plainly that this invariant is
     * NOT enforced by any DB constraint, which is exactly why the sibling methods refuse to trust
     * a single item's master. (F3) authorizing AFTER two full entity loads meant a foreign or
     * non-owner provider paid for both before ever being rejected — an authorize-before-load
     * violation the per-item provider paths ({@link #declineAppointmentItem},
     * {@link #rescheduleAppointmentItem}) do not have. Fixed: {@code enforceCanManageAppointment}
     * — the SAME projection-only, all-items check the sibling whole-visit methods already use —
     * now runs FIRST, before any {@code Appointment}/{@code Booking} entity is loaded. The
     * {@code @PreAuthorize} on this route is role-only (cycle-5 audit finding 2), so this
     * projection still runs exactly ONCE per request — this fix does not reintroduce the
     * duplicate-query defect that cycle-5 closed.
     */
    private VisitContext resolveVisitForProviderReschedule(UUID actorUserId, UUID appointmentId) {
        // F2/F3: authorize over ALL items, BEFORE any entity load — see this method's own Javadoc.
        authz.enforceCanManageAppointment(actorUserId, appointmentId);

        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        VisitContext ctx = new VisitContext(appointment, loadItemsOrThrow(appointmentId));

        if (ctx.appointment().getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Cannot reschedule a booking in status %s".formatted(ctx.appointment().getStatus()));
        }
        BookingTemporalGuard.assertCurrentNotElapsedForReschedule(ctx.firstItem().getStartsAt(), clock);
        return ctx;
    }

    /**
     * Schedule-fit guard for the whole re-planned block — reuses the create-path working-hours /
     * effective-day oracle ({@link SlotCalculationService#getAvailableSlots(UUID, LocalDate, List)},
     * the BE-2 multi-service overload) exactly as {@code BookingService#assertStartsOnAvailableSlot}
     * does for one service. Compared by {@code isEqual} on the slot start instant, same as the
     * single-booking guard. A non-matching start throws the same {@code 409 "Slot not available"}.
     */
    private void assertVisitStartsOnAvailableSlot(UUID masterId, List<UUID> masterServiceIds, OffsetDateTime startsAt) {
        boolean onSchedule = slotCalculationService
                .getAvailableSlots(masterId, startsAt.toLocalDate(), masterServiceIds)
                .stream()
                .anyMatch(slot -> slot.startsAt().toOffsetDateTime().isEqual(startsAt));
        if (!onSchedule) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }
    }

    /**
     * Per-ITEM schedule-fit guard for {@link #rescheduleAppointmentItem} — the single-service
     * {@link SlotCalculationService#getAvailableSlots(UUID, LocalDate, UUID)} overload, mirroring
     * {@code BookingService#assertStartsOnAvailableSlot} exactly. Deliberately NOT the multi-service
     * overload {@link #assertVisitStartsOnAvailableSlot} uses — only ONE item moves here, never the
     * whole block.
     *
     * <p><b>Perf audit F3 (cross-batch).</b> Delegates to {@link BookingSlotAvailabilityGuard} — the
     * single shared implementation this method and {@code BookingService#assertStartsOnAvailableSlot}
     * both call, replacing what used to be two byte-for-byte duplicate method bodies (each one's own
     * Javadoc said it "mirrors ... exactly"). See that class's Javadoc for why it is a static utility
     * rather than a shared bean (avoids a circular dependency — {@code BookingService} already
     * depends on THIS class for the per-item client-cancel header-lock seam).
     */
    private void assertItemStartsOnAvailableSlot(UUID masterId, UUID masterServiceId, OffsetDateTime startsAt) {
        BookingSlotAvailabilityGuard.assertStartsOnAvailableSlot(
                slotCalculationService, masterId, masterServiceId, startsAt);
    }

    /**
     * Per-CHILD {@code CONFIRMED}-only transition guard for {@link #rescheduleAppointmentItem} —
     * a single service line is only reschedulable from {@code CONFIRMED}; an already-terminal
     * child (a prior per-service decline/cancel, or a whole-visit terminal) is a {@code 409}. Shaped
     * on {@link #assertItemTransition}, kept distinct because the message names "reschedule" rather
     * than a generic target status.
     */
    private void assertItemReschedulable(Booking target) {
        if (target.getStatus() != BookingStatus.CONFIRMED) {
            throw new BusinessException(HttpStatus.CONFLICT,
                    "Cannot reschedule service in status %s".formatted(target.getStatus()));
        }
    }

    /**
     * Per-ROW read-only-after-elapse guard for a CLIENT per-item reschedule — mirrors
     * {@code BookingService#assertNotElapsedForClient} evaluated on the TARGET item alone, never the
     * visit (phase 30.1 D6): a sibling's elapsed-ness is irrelevant to whether THIS row may still be
     * moved. Compared on the absolute instant via the injected {@link Clock}.
     */
    private void assertItemNotElapsedForClient(Booking item) {
        if (item.getEndsAt().toInstant().isBefore(clock.instant())) {
            throw new BookingElapsedException();
        }
    }

    /**
     * Rejects a per-item move that would overlap a {@code CONFIRMED} sibling of the SAME visit — a
     * self-double-book of one master (phase 30.3 layer 1). Half-open comparison, so a move that
     * lands exactly on a sibling's boundary is legal (that is the original back-to-back layout).
     * Terminal siblings are exempt: their slots are released.
     *
     * <p>Runs before any lock, over the already-loaded item list, at zero extra queries. It exists
     * for DETERMINISM, not for safety — the booking-scoped {@code existsOverlapExcluding} call and
     * the DB's {@code no_overlapping_bookings} EXCLUDE constraint already make the overlap
     * unreachable. Without it a same-visit collision would surface as
     * {@code CLIENT_BOOKING_CONFLICT} for an account-bound visit but as {@code "Slot not available"}
     * for a guest visit — two different errors for one cause. Keep it, and keep this javadoc, even
     * if a reviewer proposes deleting it as redundant (phase 30.3 D3).
     */
    private void assertNoSiblingOverlap(
            List<Booking> items, UUID movingBookingId, OffsetDateTime newStartsAt, OffsetDateTime newEndsAt) {
        boolean overlapsSibling = items.stream()
                .filter(item -> !item.getId().equals(movingBookingId))
                .filter(item -> item.getStatus() == BookingStatus.CONFIRMED)
                .anyMatch(sibling -> newStartsAt.isBefore(sibling.getEndsAt()) && newEndsAt.isAfter(sibling.getStartsAt()));
        if (overlapsSibling) {
            throw new BusinessException(HttpStatus.CONFLICT, "Cannot overlap another service in the same visit");
        }
    }

    private void acquireClientLock(UUID clientId) {
        Integer lockResult = bookingRepository.acquireClientAdvisoryLockWithTimeout(clientId);
        if (lockResult == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Advisory lock acquisition failed");
        }
    }

    /**
     * Throws {@link ClientBookingConflictException} if the owning client already holds a
     * {@code CONFIRMED} booking (with ANY master/salon) overlapping {@code [startsAt, endsAt)},
     * excluding the visit's OWN chained rows via {@code appointment_id} (a visit occupies N rows,
     * not one — see {@link BookingRepository#findFirstConflictingClientBookingIdExcludingAppointment}).
     * Caller must hold {@link #acquireClientLock(UUID)} first so two concurrent requests from the
     * same client cannot both pass this check.
     */
    private void assertNoClientConflictExcludingAppointment(
            UUID clientId, OffsetDateTime startsAt, OffsetDateTime endsAt, UUID appointmentId) {
        bookingRepository.findFirstConflictingClientBookingIdExcludingAppointment(clientId, startsAt, endsAt, appointmentId)
                .ifPresent(conflictId -> {
                    throw clientConflictException(conflictId);
                });
    }

    /**
     * Same as {@link #assertNoClientConflictExcludingAppointment} but excludes a SINGLE booking's
     * own row via {@code bookingId} — the per-item (phase 30.4) counterpart, used by
     * {@link #rescheduleAppointmentItem} where only ONE item moves rather than the whole visit.
     * Deliberately NOT {@link BookingRepository#findFirstConflictingClientBookingIdExcludingAppointment},
     * whose appointment-wide exclusion would also hide a conflict against one of THIS visit's OTHER
     * (unmoved) items — the same "blind to siblings" trap {@code existsOverlapExcludingAppointment}
     * carries for the overlap check (phase 30.3).
     */
    private void assertNoClientConflictExcludingBooking(
            UUID clientId, OffsetDateTime startsAt, OffsetDateTime endsAt, UUID excludeBookingId) {
        bookingRepository.findFirstConflictingClientBookingIdExcluding(clientId, startsAt, endsAt, excludeBookingId)
                .ifPresent(conflictId -> {
                    throw clientConflictException(conflictId);
                });
    }

    /**
     * Re-hydrated with the full association graph so the exception can read service name +
     * master display name without a lazy load — mirrors {@code BookingService}'s identical helper.
     * The row was just found by the query above in this same transaction, so it is guaranteed to
     * still exist.
     */
    private ClientBookingConflictException clientConflictException(UUID conflictingBookingId) {
        Booking conflict = bookingRepository.findByIdWithFullGraph(conflictingBookingId)
                .orElseThrow(() -> new BusinessException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Conflicting booking could not be loaded"));
        return new ClientBookingConflictException(conflict);
    }

    /** Distinct (Kyiv-civil date, masterServiceId) keys touched by every item in {@code items}. */
    private Set<SlotKey> collectSlotKeys(List<Booking> items) {
        Set<SlotKey> keys = new LinkedHashSet<>();
        for (Booking item : items) {
            UUID serviceId = item.getMasterService().getId();
            keys.add(new SlotKey(item.getStartsAt().toLocalDate(), serviceId));
            keys.add(new SlotKey(item.getEndsAt().toLocalDate(), serviceId));
        }
        return keys;
    }

    private static UUID salonIdOfMaster(Master master) {
        Salon salon = master.getSalon();
        return salon != null ? salon.getId() : null;
    }

    /**
     * After-commit availability-cache eviction for a reschedule — the union of the OLD (freed) and
     * NEW (now-occupied) {@code (date, masterServiceId)} keys, plus the per-master free-slot
     * verdict, the salon catalogue, and the master-calendar page cache, each evicted once. No
     * revenue-dashboard eviction: a rescheduled visit stays {@code CONFIRMED} (never terminal), so
     * it cannot affect revenue. Mirrors {@link #registerEviction}'s after-commit shape.
     */
    private void registerRescheduleEviction(UUID masterId, UUID salonId, Set<SlotKey> oldKeys, Set<SlotKey> newKeys) {
        Set<SlotKey> slotKeys = new LinkedHashSet<>(oldKeys);
        slotKeys.addAll(newKeys);

        Runnable task = () -> {
            for (SlotKey key : slotKeys) {
                slotCalculationService.evictAvailableSlots(masterId, key.date(), key.masterServiceId());
            }
            slotCalculationService.evictBookableFutureSlotsByMaster(masterId);
            if (salonId != null) {
                salonCatalogCacheEvictor.evict(salonId);
            }
            cachePrefixEvictor.evictByKeyPrefixNow(masterId, "master-calendar");
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /**
     * Moves every CONFIRMED child item to {@code target} in lockstep, stamping {@code reason} when the
     * target is terminal-with-reason (null for {@code COMPLETED}). Notes are NOT set on items — they
     * live on the header. The items are managed entities (loaded via the graph fetch); {@code saveAll}
     * makes the flush explicit so all touched updates land in this transaction.
     *
     * <p><b>Resurrection guard:</b> only CONFIRMED children are transitioned. A child already moved to
     * a terminal state by a per-service decline ({@link #declineAppointmentItem}) is skipped, so a
     * later whole-visit {@code complete}/{@code decline}/{@code not-complete}/{@code cancel} can never
     * flip an already-DECLINED service back into the whole-visit terminal state.
     */
    private void transitionItems(List<Booking> items, BookingStatus target, CancellationReason reason) {
        List<Booking> touched = new ArrayList<>(items.size());
        for (Booking item : items) {
            if (item.getStatus() != BookingStatus.CONFIRMED) {
                continue;
            }
            item.setStatus(target);
            if (reason != null) {
                item.setCancellationReason(reason);
            }
            touched.add(item);
        }
        bookingRepository.saveAll(touched);
    }

    /**
     * Persists the header, enqueues EXACTLY ONE status-changed notification for the whole visit
     * (referencing the first item — never one per service), and registers the after-commit cache
     * eviction fanned out over the visit's items. {@code revenueActorId} is the authenticated actor
     * whose revenue dashboard must be evicted (COMPLETED / NOT_COMPLETED feed revenue), or
     * {@code null} when the transition does not affect revenue (client cancel / provider decline).
     */
    private void persistAndNotify(Appointment appointment, List<Booking> items, UUID revenueActorId) {
        appointmentRepository.save(appointment);
        outboxService.enqueueStatusChanged(items.get(0).getId());
        registerEviction(items, revenueActorId);
    }

    /**
     * After-commit availability-cache eviction for the whole visit — reuses the exact single-service
     * booking-write hooks so a parallel reader cannot repopulate stale data mid-write. Distinct
     * (Kyiv-civil date, masterServiceId) keys are collected across every item (both the start and end
     * day, in case a service spans midnight); the per-master free-slot verdict, the salon catalogue,
     * and the master-calendar page cache are each evicted once. A non-null {@code revenueActorId}
     * additionally evicts that actor's revenue dashboard, keyed on the actor id exactly as
     * {@code BookingService#evictRevenueDashboardAfterCommit} does.
     */
    private void registerEviction(List<Booking> items, UUID revenueActorId) {
        Master master = items.get(0).getMaster();
        UUID masterId = master.getId();
        Salon salon = master.getSalon();
        UUID salonId = salon != null ? salon.getId() : null;

        Set<SlotKey> slotKeys = new LinkedHashSet<>();
        for (Booking item : items) {
            UUID serviceId = item.getMasterService().getId();
            slotKeys.add(new SlotKey(item.getStartsAt().toLocalDate(), serviceId));
            slotKeys.add(new SlotKey(item.getEndsAt().toLocalDate(), serviceId));
        }

        Runnable task = () -> {
            for (SlotKey key : slotKeys) {
                slotCalculationService.evictAvailableSlots(masterId, key.date(), key.masterServiceId());
            }
            slotCalculationService.evictBookableFutureSlotsByMaster(masterId);
            if (salonId != null) {
                salonCatalogCacheEvictor.evict(salonId);
            }
            cachePrefixEvictor.evictByKeyPrefixNow(masterId, "master-calendar");
            if (revenueActorId != null) {
                cachePrefixEvictor.evictByKeyPrefixNow(revenueActorId, "revenue-dashboard");
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    /** The header + its ordered, fully-hydrated chained items — a single master per visit. */
    private record VisitContext(Appointment appointment, List<Booking> items) {
        Booking firstItem() {
            return items.get(0);
        }
    }

    /** Distinct availability-cache eviction key: one Kyiv-civil date × one master-service. */
    private record SlotKey(LocalDate date, UUID masterServiceId) {}
}
