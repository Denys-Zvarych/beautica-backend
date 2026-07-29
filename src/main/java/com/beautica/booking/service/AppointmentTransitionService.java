package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.AppointmentCancelRequest;
import com.beautica.booking.dto.AppointmentDetailResponse;
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
     * @throws NotFoundException  the {@code bookingId} is not a child of an existing, authorized visit (404)
     * @throws ForbiddenException a missing appointment id, or the actor lacks provider authority over the
     *                            visit (403) — the missing-id case is collapsed to 403, not 404, to avoid
     *                            an existence oracle (Finding 8)
     * @throws BusinessException  the target child is not CONFIRMED — already terminal (409)
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
        boolean headerWasLocked = lockHeaderBeforeItemTransition(appointmentId);

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
     *   <li>the canonical appointments-before-bookings lock order — {@link #lockHeaderBeforeItemTransition}
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
     * @param bookingIds     the target children's ids — every one MUST already be a CONFIRMED child of
     *                       {@code appointmentId} (the caller found them so, in the SAME transaction,
     *                       moments earlier); a stale/foreign id is a 404/409 exactly as the single-item
     *                       method throws for it
     * @param evictAfterCommit whether this call registers its own after-commit availability-cache
     *                          eviction — {@code false} for the batched schedule-override-conflict path
     * @return the targeted items, now DECLINED, in the order {@code bookingIds} was supplied
     * @throws ForbiddenException the actor lacks provider authority over the visit (403)
     * @throws NotFoundException  a {@code bookingId} is not a child of this appointment (404)
     * @throws BusinessException  a target child is not CONFIRMED — already terminal (409)
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
        // touching a single row, rather than leaving a partial decline behind.
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
        // header lock runs BEFORE any target's own status change is written below.
        boolean headerWasLocked = lockHeaderBeforeItemTransition(appointmentId);

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

        if (evictAfterCommit) {
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
     * <p>Enqueues EXACTLY ONE review prompt for the whole visit (BE-6) — the client may leave ONE
     * review per completed visit (via {@code POST /appointments/{id}/review}), so the prompt fires
     * once, referencing the FIRST item (never one per service), mirroring how the single status-changed
     * notification is enqueued. The drain worker rehydrates that booking → its client (= the visit
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
     * <p><b>Notification</b> is actor-branched exactly like the single-booking path: reuses
     * {@code NotificationOutboxService#enqueueBookingRescheduled} verbatim, referencing the FIRST
     * item only (never one per service) — the drain worker addresses the OTHER party from that one
     * booking, exactly as every other visit-level notification in this class does.
     *
     * @throws ForbiddenException             non-owning client / non-authorized provider (403)
     * @throws BusinessException              non-CONFIRMED visit, a conflicting new slot, or an
     *                                        unbookable new time (409); {@code BookingStartsAtValidator}
     *                                        rejects a bad new time (400)
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
     * Visit read-only-after-elapse guard for the CLIENT cancel path — mirrors
     * {@code BookingService#assertNotElapsedForClient} lifted to the visit: the window has fully
     * passed once the LAST item's {@code endsAt} is before "now" (items arrive ordered by
     * {@code startsAt} ascending, so the last is the visit's end). Compared on the absolute instant
     * via the injected {@link Clock} so tests can pin an elapsed visit deterministically.
     */
    private void assertVisitNotElapsedForClient(List<Booking> items) {
        Booking last = items.get(items.size() - 1);
        if (last.getEndsAt().toInstant().isBefore(clock.instant())) {
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
     * exactly as decline/complete/not-complete now do — a plain full load would surface a 404 for a
     * missing id BEFORE the ownership guard, leaking existence to a valid provider. Authorizes via
     * {@link AuthorizationService#enforceCanRescheduleBooking} on the first item (same
     * provider-authority shape as decline/complete/not-complete), then guards temporal validity via
     * {@link BookingTemporalGuard#assertCurrentNotElapsedForReschedule} on the visit's current start
     * — NOT {@link #assertVisitNotElapsedForClient} (which compares the visit's END): a provider can
     * no longer move a visit that has already begun, mirroring
     * {@code BookingService#resolveBookingForProviderReschedule} exactly. The CLIENT reschedule path
     * ({@link #resolveVisitForClientReschedule}) is unaffected — it already collapses to 403.
     */
    private VisitContext resolveVisitForProviderReschedule(UUID actorUserId, UUID appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        VisitContext ctx = new VisitContext(appointment, loadItemsOrThrow(appointmentId));
        authz.enforceCanRescheduleBooking(actorUserId, ctx.firstItem());

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
