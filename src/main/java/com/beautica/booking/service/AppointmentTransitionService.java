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
     * {@code BookingService#notCompleteBooking} (same provider authority shape as decline/complete),
     * including its elapsed guard: a provider cannot mark a visit a no-show before its slot has
     * begun ({@link BookingTemporalGuard#assertElapsedForNotComplete}, evaluated on the visit's
     * FIRST item's {@code startsAt} — the instant the whole visit begins — mirroring how
     * {@link #resolveVisitForProviderReschedule} resolves visit timing off
     * {@code ctx.firstItem().getStartsAt()}). Also evicts the actor's revenue dashboard after commit
     * for parity with the single path.
     */
    @Transactional
    public void notCompleteAppointment(UUID actorId, UUID appointmentId, AppointmentProviderNoteRequest req) {
        VisitContext ctx = loadVisitOrThrow(appointmentId);
        authz.enforceCanCancelBooking(actorId, ctx.firstItem());
        assertHeaderTransition(ctx.appointment(), BookingStatus.NOT_COMPLETED);
        BookingTemporalGuard.assertElapsedForNotComplete(ctx.firstItem().getStartsAt(), clock);

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

        OffsetDateTime newFirstStart = req.newStartsAt();
        BookingStartsAtValidator.validate(newFirstStart, clock);

        Master master = items.get(0).getMaster();
        UUID masterId = master.getId();
        List<UUID> masterServiceIds = items.stream().map(b -> b.getMasterService().getId()).toList();

        // Same working-hours / effective-day validation the create path relies on, run over the
        // WHOLE multi-service block (BE-2's N-service overload). Run BEFORE any lock, mirroring
        // BookingService#assertStartsOnAvailableSlot's placement.
        assertVisitStartsOnAvailableSlot(masterId, masterServiceIds, newFirstStart);

        // Timing-only re-layout — preserves every item's frozen duration/buffer/price.
        List<VisitPlanner.PlannedWindow> windows = visitPlanner.replanFromNewStart(items, newFirstStart);
        OffsetDateTime newLastEnd = windows.get(windows.size() - 1).endsAt();

        // Old (date, masterServiceId) keys, captured BEFORE any item is mutated, for after-commit
        // cache eviction (mirrors AppointmentService#registerSlotEviction).
        Set<SlotKey> oldSlotKeys = collectSlotKeys(items);

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

        for (int i = 0; i < items.size(); i++) {
            VisitPlanner.PlannedWindow window = windows.get(i);
            items.get(i).reschedule(window.startsAt(), window.endsAt());
        }

        List<Booking> saved;
        try {
            saved = bookingRepository.saveAll(items);
            bookingRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }

        // Phase 27.3 outbox family, reused verbatim: referencing the FIRST item only (never one
        // per service) — the drain worker addresses the OTHER party from that one booking.
        outboxService.enqueueBookingRescheduled(saved.get(0).getId(), initiatedByProvider);

        registerRescheduleEviction(masterId, salonIdOfMaster(master), oldSlotKeys, collectSlotKeys(saved));

        return appointmentService.enrich(appointment, saved);
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
     * Moves every child item to {@code target} in lockstep, stamping {@code reason} when the target is
     * terminal-with-reason (null for {@code COMPLETED}). Notes are NOT set on items — they live on the
     * header. The items are managed entities (loaded via the graph fetch); {@code saveAll} makes the
     * flush explicit so all N updates land in this transaction.
     */
    private void transitionItems(List<Booking> items, BookingStatus target, CancellationReason reason) {
        for (Booking item : items) {
            item.setStatus(target);
            if (reason != null) {
                item.setCancellationReason(reason);
            }
        }
        bookingRepository.saveAll(items);
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
