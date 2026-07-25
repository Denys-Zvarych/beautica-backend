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
import java.util.Set;
import java.util.UUID;

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
     */
    @Transactional
    public void cancelAppointment(UUID clientId, UUID appointmentId, AppointmentCancelRequest req) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .filter(a -> a.getClient() != null && a.getClient().getId().equals(clientId))
                .orElseThrow(() -> new ForbiddenException("Access denied"));
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
     * Mirrors {@code BookingService#declineBooking} (authority via
     * {@link AuthorizationService#enforceCanCancelBooking} — admits SALON_OWNER / SALON_ADMIN /
     * INDEPENDENT_MASTER). No elapse guard: resolving an elapsed visit is exactly the provider's job.
     */
    @Transactional
    public void declineAppointment(UUID actorId, UUID appointmentId, AppointmentProviderNoteRequest req) {
        VisitContext ctx = loadVisitOrThrow(appointmentId);
        authz.enforceCanCancelBooking(actorId, ctx.firstItem());
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
     * <p><b>Authority</b> is the same provider authority as the whole-visit decline
     * ({@link AuthorizationService#enforceCanCancelBooking} on the visit's single master, evaluated
     * against the first item — SALON_OWNER / SALON_ADMIN / INDEPENDENT_MASTER). A {@code bookingId}
     * that is not a child of this appointment is a {@code 404} — same missing→404 shape as a sibling
     * lookup elsewhere, no existence oracle beyond the visit the caller is already authorized on.
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
     * @throws NotFoundException  the appointment (or the {@code bookingId} within it) does not exist (404)
     * @throws ForbiddenException the actor lacks provider authority over the visit (403)
     * @throws BusinessException  the target child is not CONFIRMED — already terminal (409)
     */
    @Transactional
    public void declineAppointmentItem(
            UUID actorId, UUID appointmentId, UUID bookingId, AppointmentProviderNoteRequest req) {
        VisitContext ctx = loadVisitOrThrow(appointmentId);
        authz.enforceCanCancelBooking(actorId, ctx.firstItem());

        Booking target = ctx.items().stream()
                .filter(item -> item.getId().equals(bookingId))
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Appointment service not found"));

        assertItemTransition(target, BookingStatus.DECLINED);

        target.setStatus(BookingStatus.DECLINED);
        target.setCancellationReason(CancellationReason.PROVIDER_UNAVAILABLE);
        // Note on the CHILD (its own status is now DECLINED — legal per chk_provider_comment_status);
        // the header note is never touched on a partial decline.
        target.setProviderComment(BookingComments.normalize(req == null ? null : req.providerComment()));
        bookingRepository.save(target);

        recomputeHeaderAfterItemDecline(ctx.appointment(), ctx.items());

        // Reference the DECLINED CHILD (not item 0) so the client notification names the right service.
        outboxService.enqueueStatusChanged(target.getId());

        // Single-item availability eviction — reuse the whole-visit after-commit hook over a one-item
        // list; a declined child frees only its own slot. No revenue impact on a decline (null actor).
        registerEviction(List.of(target), null);
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
        VisitContext ctx = loadVisitOrThrow(appointmentId);
        authz.enforceCanCompleteBooking(actorId, ctx.firstItem());
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
        VisitContext ctx = loadVisitOrThrow(appointmentId);
        authz.enforceCanCancelBooking(actorId, ctx.firstItem());
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

    /**
     * Loads a visit for a PROVIDER transition: a missing appointment (or an orphan header with no
     * items) is a {@code 404}, matching {@code BookingService#loadBookingOrThrow}. Ownership is then
     * enforced by the caller's {@code enforceCan*} guard ({@code 403} for a foreign visit) — the same
     * missing→404 / foreign→403 split the single-service provider paths produce.
     */
    private VisitContext loadVisitOrThrow(UUID appointmentId) {
        Appointment appointment = appointmentRepository.findById(appointmentId)
                .orElseThrow(() -> new NotFoundException("Appointment not found"));
        return new VisitContext(appointment, loadItemsOrThrow(appointmentId));
    }

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
     * Header recompute after a per-service decline (locked, safe invariant): while ≥1 child remains
     * CONFIRMED the header stays CONFIRMED; declining the LAST CONFIRMED child collapses the header to
     * {@code DECLINED}. No note is written to the header (it stays untouched — the note lives on the
     * declined child), so the {@code chk_appointment_provider_comment_status} CHECK is never at risk.
     * The header is persisted only when it actually transitions.
     */
    private void recomputeHeaderAfterItemDecline(Appointment appointment, List<Booking> items) {
        boolean anyConfirmed = items.stream()
                .anyMatch(item -> item.getStatus() == BookingStatus.CONFIRMED);
        if (!anyConfirmed) {
            appointment.setStatus(BookingStatus.DECLINED);
            appointment.setCancellationReason(CancellationReason.PROVIDER_UNAVAILABLE);
            appointmentRepository.save(appointment);
        }
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
     * PROVIDER-path authorization + status + elapsed resolution for {@link #rescheduleAppointment}
     * — loads via {@link #loadVisitOrThrow} (the same full load every other provider action uses),
     * authorizes via {@link AuthorizationService#enforceCanRescheduleBooking} on the first item
     * (same provider-authority shape as decline/complete/not-complete), then guards temporal
     * validity via {@link BookingTemporalGuard#assertCurrentNotElapsedForReschedule} on the
     * visit's current start — NOT {@link #assertVisitNotElapsedForClient} (which compares the
     * visit's END): a provider can no longer move a visit that has already begun, mirroring
     * {@code BookingService#resolveBookingForProviderReschedule} exactly.
     */
    private VisitContext resolveVisitForProviderReschedule(UUID actorUserId, UUID appointmentId) {
        VisitContext ctx = loadVisitOrThrow(appointmentId);
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
