package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.AppointmentDetailResponse;
import com.beautica.booking.dto.AppointmentItemRescheduleRequest;
import com.beautica.booking.dto.AppointmentRescheduleRequest;
import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.event.BookingCompletedEvent;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.cache.MasterCachePrefixEvictor;
import com.beautica.common.exception.BookingElapsedException;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.entity.Master;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link AppointmentTransitionService}'s per-item header recompute — first unit
 * test file for this service (previously covered only at IT level by
 * {@code AppointmentTransitionIT}/{@code AppointmentTransitionMatrixIT}). Scoped narrowly to the
 * client-cancel pair — {@link AppointmentTransitionService#lockAppointmentHeaderBeforeClientItemCancel}
 * / {@link AppointmentTransitionService#collapseAppointmentHeaderAfterClientItemCancel} — the track
 * 27.x counterpart of the pre-existing provider per-service decline recompute — both share the
 * private {@code lockHeaderBeforeItemTransition}/{@code collapseHeaderAfterItemTransition} helpers,
 * so this pins the client-cancel branch (target CANCELLED / reason CLIENT_CANCELLED / header note
 * passed through) without needing the full {@code declineAppointmentItem} call graph (authz, outbox,
 * slot eviction, salon catalogue, …).
 *
 * <p><b>Rewritten for the cycle-2 lock-order fix (finding 1).</b> The previous version of this file
 * pinned a SINGLE combined {@code recomputeHeaderAfterClientItemCancel(appointmentId, note)} method
 * that locked-then-collapsed in one call. That method is now split into two phases — a LOCK phase
 * the caller ({@code BookingService#cancelBooking}) invokes BEFORE writing its own child row, and a
 * COLLAPSE phase invoked AFTER — so the caller can establish the canonical appointments-before-
 * bookings lock order without waiting for the child write to land first. These tests exercise each
 * phase independently: the lock phase's return value (whether the header was CONFIRMED-and-now-
 * locked) and the collapse phase's conditional delegation to
 * {@link AppointmentRepository#collapseHeaderIfNoConfirmedSiblingsRemain}, gated on that same
 * boolean rather than re-deriving it from a second lock call.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AppointmentTransitionService — client per-leg cancel header recompute (lock/collapse split) — unit")
class AppointmentTransitionServiceTest {

    @Mock
    private AppointmentRepository appointmentRepository;
    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private AuthorizationService authz;
    @Mock
    private NotificationOutboxService outboxService;
    @Mock
    private SlotCalculationService slotCalculationService;
    @Mock
    private SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    @Mock
    private MasterCachePrefixEvictor cachePrefixEvictor;
    @Mock
    private VisitPlanner visitPlanner;
    @Mock
    private AppointmentService appointmentService;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    private Clock clock;
    private AppointmentTransitionService appointmentTransitionService;

    private UUID appointmentId;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-07-26T10:00:00Z"), ZoneOffset.UTC);
        appointmentTransitionService = new AppointmentTransitionService(
                appointmentRepository,
                bookingRepository,
                authz,
                outboxService,
                slotCalculationService,
                salonCatalogCacheEvictor,
                cachePrefixEvictor,
                visitPlanner,
                appointmentService,
                eventPublisher,
                clock
        );
        appointmentId = UUID.randomUUID();
    }

    // ── phase 1: lockAppointmentHeaderBeforeClientItemCancel ────────────────────────────────────

    @Test
    @DisplayName("locks the header and returns true when it was still CONFIRMED")
    void should_lockHeaderAndReturnTrue_when_headerWasConfirmed() {
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));

        boolean headerWasLocked = appointmentTransitionService.lockAppointmentHeaderBeforeClientItemCancel(appointmentId);

        assertThat(headerWasLocked).isTrue();
        verify(appointmentRepository).lockHeaderIfConfirmed(appointmentId);
        verify(appointmentRepository, never()).findById(any());
        verifyNoMoreInteractions(appointmentRepository);
    }

    @Test
    @DisplayName("returns false, without loading anything else, when the header had already left CONFIRMED")
    void should_returnFalse_when_headerAlreadyLeftConfirmed() {
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.empty());

        boolean headerWasLocked = appointmentTransitionService.lockAppointmentHeaderBeforeClientItemCancel(appointmentId);

        assertThat(headerWasLocked).isFalse();
        verify(appointmentRepository).lockHeaderIfConfirmed(appointmentId);
        verifyNoMoreInteractions(appointmentRepository);
    }

    // ── phase 2: collapseAppointmentHeaderAfterClientItemCancel ─────────────────────────────────

    @Test
    @DisplayName("delegates to the atomic collapse UPDATE with target CANCELLED / reason CLIENT_CANCELLED "
            + "and the supplied note when the caller reports the header was locked")
    void should_delegateToCollapseUpdate_when_headerWasLocked() {
        appointmentTransitionService.collapseAppointmentHeaderAfterClientItemCancel(
                appointmentId, true, "не потрібно");

        verify(appointmentRepository).collapseHeaderIfNoConfirmedSiblingsRemain(
                appointmentId,
                BookingStatus.CANCELLED.name(),
                CancellationReason.CLIENT_CANCELLED.name(),
                "не потрібно");
    }

    @Test
    @DisplayName("passes a null note straight through — not defaulted to a literal \"null\" string — "
            + "when the client supplied none")
    void should_passNullNoteThrough_when_noNoteWasSupplied() {
        appointmentTransitionService.collapseAppointmentHeaderAfterClientItemCancel(appointmentId, true, null);

        verify(appointmentRepository).collapseHeaderIfNoConfirmedSiblingsRemain(
                appointmentId,
                BookingStatus.CANCELLED.name(),
                CancellationReason.CLIENT_CANCELLED.name(),
                null);
    }

    @Test
    @DisplayName("skips the collapse UPDATE entirely — no wasted round trip — when the caller reports the "
            + "header was NOT locked (idempotent replay / already terminal at phase 1)")
    void should_skipCollapseUpdate_when_headerWasNotLocked() {
        appointmentTransitionService.collapseAppointmentHeaderAfterClientItemCancel(
                appointmentId, false, "не потрібно");

        verify(appointmentRepository, never()).collapseHeaderIfNoConfirmedSiblingsRemain(any(), any(), any(), any());
        verify(appointmentRepository, never()).lockHeaderIfConfirmed(any());
        verifyNoMoreInteractions(appointmentRepository);
    }

    @Test
    @DisplayName("never loads the header entity or the visit's chained items across either phase — the "
            + "over-fetch (findById + the 5-JOIN-FETCH findByAppointmentIdWithGraph) that used to run on "
            + "every recompute stays gone; lock-then-collapse are the only repository interactions")
    void should_notLoadAppointmentOrItems_when_recomputingAfterClientCancel() {
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));

        boolean headerWasLocked = appointmentTransitionService.lockAppointmentHeaderBeforeClientItemCancel(appointmentId);
        appointmentTransitionService.collapseAppointmentHeaderAfterClientItemCancel(
                appointmentId, headerWasLocked, "більше не потрібно");

        verify(appointmentRepository, never()).findById(any());
        verify(appointmentRepository, never()).save(any());
        verify(bookingRepository, never()).findByAppointmentIdWithGraph(any());
        verify(appointmentRepository).lockHeaderIfConfirmed(any());
        verify(appointmentRepository).collapseHeaderIfNoConfirmedSiblingsRemain(any(), any(), any(), any());
        verifyNoMoreInteractions(appointmentRepository);
    }

    // ── cycle-3 audit finding 1: authz precedes the header lock on all four whole-visit methods ──
    //
    // A cycle-2 wording error had lockHeaderForWholeVisitTransition called at the literal top of
    // cancelAppointment/declineAppointment/completeAppointment/notCompleteAppointment, BEFORE the
    // ownership/authority check — so a caller who did not own the appointment could still force a
    // real SELECT ... FOR UPDATE (and the 5-JOIN-FETCH item load) with a guessed UUID before being
    // rejected. Pinned STRUCTURALLY rather than with a timing assertion (Mockito unit test, no real
    // DB/lock contention to race against) — each test proves the lock repository method is NEVER
    // invoked, and no items are ever fetched, once authorization has already thrown. A timing-based
    // proof belongs at the IT level against a real contended lock; this unit suite instead pins the
    // ordering the production code must follow to make that timing property true in the first place.

    @Test
    @DisplayName("cancelAppointment — a non-owner is rejected by the unlocked client-id projection "
            + "BEFORE any header lock is attempted, and no items are loaded")
    void should_notLockHeaderOrLoadItems_when_cancelAppointmentCalledByNonOwner() {
        UUID foreignClientId = UUID.randomUUID();
        UUID callerClientId = UUID.randomUUID();
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(foreignClientId));

        assertThatThrownBy(() -> appointmentTransitionService.cancelAppointment(callerClientId, appointmentId, null))
                .isInstanceOf(ForbiddenException.class);

        verify(appointmentRepository).findClientIdById(appointmentId);
        verify(appointmentRepository, never()).lockHeaderRegardlessOfStatus(any());
        verify(appointmentRepository, never()).findById(any());
        verify(bookingRepository, never()).findByAppointmentIdWithGraph(any());
    }

    @Test
    @DisplayName("cancelAppointment — a missing appointment id collapses to the SAME uniform 403 as a "
            + "foreign visit (no existence oracle) and also never reaches the header lock")
    void should_notLockHeaderOrLoadItems_when_cancelAppointmentCalledWithMissingId() {
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentTransitionService.cancelAppointment(UUID.randomUUID(), appointmentId, null))
                .isInstanceOf(ForbiddenException.class);

        verify(appointmentRepository, never()).lockHeaderRegardlessOfStatus(any());
        verify(bookingRepository, never()).findByAppointmentIdWithGraph(any());
    }

    @Test
    @DisplayName("declineAppointment — a caller without provider authority is rejected BEFORE any "
            + "header lock is attempted, and no items are loaded")
    void should_notLockHeaderOrLoadItems_when_declineAppointmentCalledByNonOwner() {
        UUID actorId = UUID.randomUUID();
        doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanManageAppointment(actorId, appointmentId);

        assertThatThrownBy(() -> appointmentTransitionService.declineAppointment(actorId, appointmentId, null))
                .isInstanceOf(ForbiddenException.class);

        verify(authz).enforceCanManageAppointment(actorId, appointmentId);
        verify(appointmentRepository, never()).lockHeaderRegardlessOfStatus(any());
        verify(appointmentRepository, never()).findById(any());
        verify(bookingRepository, never()).findByAppointmentIdWithGraph(any());
    }

    @Test
    @DisplayName("completeAppointment — a caller without provider authority is rejected BEFORE any "
            + "header lock is attempted, and no items are loaded")
    void should_notLockHeaderOrLoadItems_when_completeAppointmentCalledByNonOwner() {
        UUID actorId = UUID.randomUUID();
        doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanManageAppointment(actorId, appointmentId);

        assertThatThrownBy(() -> appointmentTransitionService.completeAppointment(actorId, appointmentId))
                .isInstanceOf(ForbiddenException.class);

        verify(authz).enforceCanManageAppointment(actorId, appointmentId);
        verify(appointmentRepository, never()).lockHeaderRegardlessOfStatus(any());
        verify(appointmentRepository, never()).findById(any());
        verify(bookingRepository, never()).findByAppointmentIdWithGraph(any());
    }

    // ── BookingCompletedEvent carries a CLIENT id, never an appointment id ───────────────────────
    //
    // 2026-08 security re-audit LOW. The event used to carry a `bookingId` that this path filled
    // with `appointment.getId()` (appointments.id) while BookingService filled it with
    // `booking.getId()` (bookings.id) — two disjoint id spaces behind one field name, so any
    // future `bookingRepository.findById(event.bookingId())` would silently return empty for every
    // multi-service visit. The field is gone; these tests pin what the single remaining component
    // actually means, so it cannot be re-populated from the wrong entity.

    @Test
    @DisplayName("completeAppointment publishes BookingCompletedEvent carrying the visit CLIENT's "
            + "user id — never the appointment id and never an item's booking id")
    void should_publishClientUserId_when_appointmentCompleted() {
        UUID clientUserId = UUID.randomUUID();
        UUID itemBookingId = UUID.randomUUID();
        User visitClient = new User("visit-client@beautica.test", "hash", Role.CLIENT,
                "Олена", "Коваль", "+380501110000");
        ReflectionTestUtils.setField(visitClient, "id", clientUserId);

        Appointment appointment = confirmedAppointmentFor(visitClient);
        Booking item = confirmedItemOf(itemBookingId);
        when(appointmentRepository.lockHeaderRegardlessOfStatus(appointmentId))
                .thenReturn(Optional.of(appointmentId));
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(List.of(item));

        appointmentTransitionService.completeAppointment(UUID.randomUUID(), appointmentId);

        ArgumentCaptor<Object> published = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(published.capture());
        assertThat(published.getValue())
                .isInstanceOf(BookingCompletedEvent.class)
                .extracting("clientUserId")
                .as("the passport cache is keyed on users.id — publishing appointments.id or "
                        + "bookings.id here would evict nothing and fail silently")
                .isEqualTo(clientUserId)
                .isNotEqualTo(appointmentId)
                .isNotEqualTo(itemBookingId);
    }

    @Test
    @DisplayName("completeAppointment publishes a null clientUserId for a GUEST visit — the event "
            + "records the absence faithfully rather than substituting an id")
    void should_publishNullClientUserId_when_guestVisitCompleted() {
        Appointment appointment = confirmedAppointmentFor(null);
        when(appointmentRepository.lockHeaderRegardlessOfStatus(appointmentId))
                .thenReturn(Optional.of(appointmentId));
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId))
                .thenReturn(List.of(confirmedItemOf(UUID.randomUUID())));

        appointmentTransitionService.completeAppointment(UUID.randomUUID(), appointmentId);

        verify(eventPublisher).publishEvent(new BookingCompletedEvent(null));
        // A guest visit has no account to review with, so no review prompt is enqueued either.
        verify(outboxService, never()).enqueueReviewRequested(any());
    }

    private Appointment confirmedAppointmentFor(User client) {
        return Appointment.builder()
                .id(appointmentId)
                .client(client)
                .status(BookingStatus.CONFIRMED)
                .build();
    }

    /** A CONFIRMED item with the minimal graph {@code registerEviction} indexes into. */
    private Booking confirmedItemOf(UUID bookingId) {
        return Booking.builder()
                .id(bookingId)
                .master(Master.builder().id(UUID.randomUUID()).isActive(true).build())
                .masterService(MasterServiceAssignment.builder()
                        .id(UUID.randomUUID()).isActive(true).build())
                .status(BookingStatus.CONFIRMED)
                .startsAt(OffsetDateTime.parse("2026-07-26T09:00:00Z"))
                .endsAt(OffsetDateTime.parse("2026-07-26T10:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("notCompleteAppointment — a caller without provider authority is rejected BEFORE any "
            + "header lock is attempted, and no items are loaded")
    void should_notLockHeaderOrLoadItems_when_notCompleteAppointmentCalledByNonOwner() {
        UUID actorId = UUID.randomUUID();
        doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanManageAppointment(actorId, appointmentId);

        assertThatThrownBy(() -> appointmentTransitionService.notCompleteAppointment(actorId, appointmentId, null))
                .isInstanceOf(ForbiddenException.class);

        verify(authz).enforceCanManageAppointment(actorId, appointmentId);
        verify(appointmentRepository, never()).lockHeaderRegardlessOfStatus(any());
        verify(appointmentRepository, never()).findById(any());
        verify(bookingRepository, never()).findByAppointmentIdWithGraph(any());
    }

    @Test
    @DisplayName("declineAppointmentItem — cycle-4 audit finding 2: a caller without provider authority "
            + "over the visit is rejected by the projection-only enforceCanManageAppointment check BEFORE "
            + "the Appointment entity or the item graph is ever loaded")
    void should_notLoadAppointmentOrItems_when_declineAppointmentItemCalledByNonOwner() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanManageAppointment(actorId, appointmentId);

        assertThatThrownBy(() ->
                appointmentTransitionService.declineAppointmentItem(actorId, appointmentId, bookingId, null))
                .isInstanceOf(ForbiddenException.class);

        verify(authz).enforceCanManageAppointment(actorId, appointmentId);
        verify(appointmentRepository, never()).findById(any());
        verify(appointmentRepository, never()).lockHeaderRegardlessOfStatus(any());
        verify(appointmentRepository, never()).lockHeaderIfConfirmed(any());
        verify(bookingRepository, never()).findByAppointmentIdWithGraph(any());
    }

    /**
     * Regression test for QA cycle-8 finding 2 (LOW) — {@code declineAppointmentItem}'s F1
     * freshness-recheck-false branch (the {@code existsConfirmedById} → 409 path) previously had NO
     * deterministic unit coverage; only {@code AppointmentCrossPathTransitionConcurrencyIT}'s
     * (compromised, branch-on-the-guard-under-test) concurrency tests touched it at all. Mirrors the
     * equivalent false-branch tests already pinned for {@code BookingService#cancelBooking}/
     * {@code #rescheduleBooking}/{@code #declineBookingCore}/{@code #completeBooking}/
     * {@code #notCompleteBooking} in {@code BookingServiceTest}.
     */
    @Test
    @DisplayName("declineAppointmentItem — F1 (cycle-6 audit 2026-08-03): the header lock succeeding "
            + "is NOT enough if the TARGET ITEM ITSELF already left CONFIRMED concurrently (e.g. a "
            + "per-item client cancel/reschedule of this SAME leg) — existsConfirmedById returning "
            + "false is a clean 409, with the target NEVER mutated, saved, or evicted")
    void should_throw409AndNotMutateOrSave_when_targetItselfLeftConfirmedAfterHeaderLockForDeclineItem() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Booking target = bookingItem(bookingId, BookingStatus.CONFIRMED);
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(List.of(target));
        // The header itself is still CONFIRMED — the lock succeeds...
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));
        // ...but THIS item concurrently left CONFIRMED (e.g. a per-item client cancel or a per-item
        // reschedule of the SAME leg, fired near-concurrently at a sibling endpoint) — F1's freshness
        // probe reflects that directly, never a second entity load of target itself.
        when(bookingRepository.existsConfirmedById(bookingId)).thenReturn(false);

        assertThatThrownBy(() -> appointmentTransitionService.declineAppointmentItem(
                actorId, appointmentId, bookingId, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("Service changed concurrently");

        assertThat(target.getStatus())
                .as("F1: a lost freshness race on the TARGET ITEM ITSELF must mutate nothing")
                .isEqualTo(BookingStatus.CONFIRMED);
        assertThat(target.getProviderComment())
                .as("the aborted decline's own providerComment must never be written")
                .isNull();
        verify(bookingRepository, never()).save(any());
        verify(appointmentRepository, never()).collapseHeaderIfNoConfirmedSiblingsRemain(any(), any(), any(), any());
        verify(outboxService, never()).enqueueStatusChanged(any());
        verify(slotCalculationService, never()).evictAvailableSlots(any(), any(), any());
        verify(cachePrefixEvictor, never()).evictByKeyPrefixNow(any(), any());
    }

    // ── declineAppointmentItems — batched multi-sibling decline (perf finding 1, 2026-07-26 audit) ──

    private com.beautica.booking.entity.Booking bookingItem(UUID id, com.beautica.booking.enums.BookingStatus status) {
        com.beautica.master.entity.Master master = com.beautica.master.entity.Master.builder()
                .id(UUID.randomUUID())
                .build();
        com.beautica.service.entity.MasterServiceAssignment msa =
                com.beautica.service.entity.MasterServiceAssignment.builder()
                        .id(UUID.randomUUID())
                        .build();
        return com.beautica.booking.entity.Booking.builder()
                .id(id)
                .master(master)
                .masterService(msa)
                .status(status)
                .startsAt(java.time.OffsetDateTime.parse("2026-08-10T09:00:00Z"))
                .endsAt(java.time.OffsetDateTime.parse("2026-08-10T10:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("declineAppointmentItems — a caller without provider authority is rejected BEFORE "
            + "the item list is ever loaded")
    void should_notLoadItems_when_declineAppointmentItemsCalledByNonOwner() {
        UUID actorId = UUID.randomUUID();
        doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanManageAppointment(actorId, appointmentId);

        assertThatThrownBy(() -> appointmentTransitionService.declineAppointmentItems(
                actorId, appointmentId, java.util.List.of(UUID.randomUUID()), null, false))
                .isInstanceOf(ForbiddenException.class);

        verify(authz).enforceCanManageAppointment(actorId, appointmentId);
        verify(bookingRepository, never()).findByAppointmentIdWithGraph(any());
        verify(appointmentRepository, never()).lockHeaderIfConfirmed(any());
    }

    @Test
    @DisplayName("declineAppointmentItems — K conflicting siblings of the SAME appointment are "
            + "declined via ONE item-list load, ONE header lock, and ONE collapse — never one per "
            + "sibling (the O(K²) regression this batched method exists to close)")
    void should_declineAllTargetsWithOneLoadOneLockOneCollapse_when_multipleSiblingsProvided() {
        UUID actorId = UUID.randomUUID();
        UUID booking1 = UUID.randomUUID();
        UUID booking2 = UUID.randomUUID();
        UUID booking3 = UUID.randomUUID();
        com.beautica.booking.entity.Booking item1 = bookingItem(booking1, com.beautica.booking.enums.BookingStatus.CONFIRMED);
        com.beautica.booking.entity.Booking item2 = bookingItem(booking2, com.beautica.booking.enums.BookingStatus.CONFIRMED);
        com.beautica.booking.entity.Booking item3 = bookingItem(booking3, com.beautica.booking.enums.BookingStatus.CONFIRMED);
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId))
                .thenReturn(java.util.List.of(item1, item2, item3));
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));
        // G3 (cycle-7 audit 2026-08-03) freshness recheck — no racer in THIS test, so every target
        // is still reported CONFIRMED and nothing is filtered out.
        when(bookingRepository.findConfirmedIdsByAppointmentId(appointmentId))
                .thenReturn(java.util.Set.of(booking1, booking2, booking3));

        List<com.beautica.booking.entity.Booking> declined = appointmentTransitionService.declineAppointmentItems(
                actorId, appointmentId, java.util.List.of(booking1, booking2, booking3),
                new com.beautica.booking.dto.AppointmentProviderNoteRequest("Sorry, unavailable"), false);

        assertThat(declined).containsExactly(item1, item2, item3);
        assertThat(item1.getStatus()).isEqualTo(com.beautica.booking.enums.BookingStatus.DECLINED);
        assertThat(item2.getStatus()).isEqualTo(com.beautica.booking.enums.BookingStatus.DECLINED);
        assertThat(item3.getStatus()).isEqualTo(com.beautica.booking.enums.BookingStatus.DECLINED);
        assertThat(item1.getProviderComment()).isEqualTo("Sorry, unavailable");

        // The actual O(K²)→O(K) fix: exactly ONE call each, not K.
        verify(bookingRepository, org.mockito.Mockito.times(1)).findByAppointmentIdWithGraph(appointmentId);
        verify(appointmentRepository, org.mockito.Mockito.times(1)).lockHeaderIfConfirmed(appointmentId);
        verify(appointmentRepository, org.mockito.Mockito.times(1)).collapseHeaderIfNoConfirmedSiblingsRemain(
                eq(appointmentId), eq("DECLINED"), eq("PROVIDER_UNAVAILABLE"), any());
        verify(bookingRepository).saveAll(java.util.List.of(item1, item2, item3));
        // D6 (2026-07-26 product decision reversal): this batched path never enqueues a notification —
        // see should_notEnqueueAnyNotification_when_decliningMultipleSiblings below for the dedicated
        // regression guard.
        verify(outboxService, never()).enqueueStatusChanged(any());
    }

    /**
     * Regression test for G3 (HIGH, cycle-7 audit 2026-08-03) — the batched freshness re-check's
     * "filter, not abort" contract, deterministically (no real DB race required). Every existing
     * test of this method stubs {@code findConfirmedIdsByAppointmentId} to return EXACTLY the
     * requested target set (see {@code should_declineAllTargetsWithOneLoadOneLockOneCollapse_*}'s
     * own comment: "no racer in THIS test") — none exercises the actual filtering branch this
     * method exists for. {@code AppointmentCrossPathTransitionConcurrencyIT}'s own G3 test cannot
     * substitute for this: it accepts EITHER {@code CANCELLED} or {@code DECLINED} as the raced
     * leg's final state via a lenient {@code isIn(...)}, so it cannot by itself distinguish "the
     * filter ran and excluded the stale target" from "the filter is a no-op and the stale target
     * got re-declined anyway" — both produce an accepted outcome under that assertion. This test
     * pins the filtering mechanism directly: booking2 is reported no longer CONFIRMED by the
     * post-lock recheck, so it must be excluded from BOTH the mutation and the returned list,
     * while booking1/booking3 (still reported CONFIRMED) are declined normally.
     */
    @Test
    @DisplayName("declineAppointmentItems — G3: a target no longer reported CONFIRMED by the "
            + "post-lock freshness recheck is FILTERED out of the batch — never re-declined, never "
            + "mutated, absent from the returned list — while its still-CONFIRMED siblings decline "
            + "normally")
    void should_filterOutStaleTargetAndDeclineSurvivors_when_postLockRecheckOmitsOneTarget() {
        UUID actorId = UUID.randomUUID();
        UUID booking1 = UUID.randomUUID();
        UUID booking2 = UUID.randomUUID();
        UUID booking3 = UUID.randomUUID();
        com.beautica.booking.entity.Booking item1 = bookingItem(booking1, com.beautica.booking.enums.BookingStatus.CONFIRMED);
        com.beautica.booking.entity.Booking item2 = bookingItem(booking2, com.beautica.booking.enums.BookingStatus.CONFIRMED);
        com.beautica.booking.entity.Booking item3 = bookingItem(booking3, com.beautica.booking.enums.BookingStatus.CONFIRMED);
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId))
                .thenReturn(java.util.List.of(item1, item2, item3));
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));
        // booking2 raced away (e.g. a concurrent per-item client cancel) between the unlocked load
        // above and this post-lock recheck — the projection simply omits its id.
        when(bookingRepository.findConfirmedIdsByAppointmentId(appointmentId))
                .thenReturn(java.util.Set.of(booking1, booking3));

        List<com.beautica.booking.entity.Booking> declined = appointmentTransitionService.declineAppointmentItems(
                actorId, appointmentId, java.util.List.of(booking1, booking2, booking3),
                new com.beautica.booking.dto.AppointmentProviderNoteRequest("Sorry, unavailable"), false);

        assertThat(declined)
                .as("the stale target must be a strict SUBSET, never a superset, of the requested ids")
                .containsExactly(item1, item3);
        assertThat(item1.getStatus()).isEqualTo(com.beautica.booking.enums.BookingStatus.DECLINED);
        assertThat(item3.getStatus()).isEqualTo(com.beautica.booking.enums.BookingStatus.DECLINED);
        assertThat(item2.getStatus())
                .as("G3 (HIGH): the stale target must NEVER be mutated — it is filtered out BEFORE "
                        + "the mutation loop, not re-declined over whatever concurrently changed it")
                .isEqualTo(com.beautica.booking.enums.BookingStatus.CONFIRMED);
        assertThat(item2.getProviderComment())
                .as("the stale target's providerComment must never be written")
                .isNull();
        verify(bookingRepository).saveAll(java.util.List.of(item1, item3));
    }

    /**
     * The edge the G3 filter's own guard exists for: EVERY target raced away, leaving an empty
     * survivor list. {@code registerEviction} indexes {@code targets.get(0)} unconditionally (see
     * that method's Javadoc), so an unguarded empty list would throw
     * {@code IndexOutOfBoundsException} instead of the clean, empty no-op this method's own Javadoc
     * promises ("empty if every target did"). No existing test drives {@code
     * findConfirmedIdsByAppointmentId} to an empty result for this method.
     */
    @Test
    @DisplayName("declineAppointmentItems — G3: every target racing away before the header lock "
            + "leaves an empty survivor list; the empty-list eviction guard must prevent "
            + "IndexOutOfBoundsException rather than let registerEviction index targets.get(0)")
    void should_returnEmptyListWithoutThrowing_when_everyTargetRacesAwayBeforeHeaderLock() {
        UUID actorId = UUID.randomUUID();
        UUID booking1 = UUID.randomUUID();
        com.beautica.booking.entity.Booking item1 = bookingItem(booking1, com.beautica.booking.enums.BookingStatus.CONFIRMED);
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(java.util.List.of(item1));
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));
        when(bookingRepository.findConfirmedIdsByAppointmentId(appointmentId)).thenReturn(java.util.Set.of());

        List<com.beautica.booking.entity.Booking> declined = appointmentTransitionService.declineAppointmentItems(
                actorId, appointmentId, java.util.List.of(booking1),
                null, true); // evictAfterCommit=true — the branch that would index targets.get(0)

        assertThat(declined).as("every target raced away — the survivor list must be empty").isEmpty();
        assertThat(item1.getStatus())
                .as("the sole target must never be mutated once filtered out")
                .isEqualTo(com.beautica.booking.enums.BookingStatus.CONFIRMED);
        verify(bookingRepository).saveAll(java.util.List.of());
        verify(slotCalculationService, never()).evictAvailableSlots(any(), any(), any());
        verify(cachePrefixEvictor, never()).evictByKeyPrefixNow(any(), any());
    }

    @Test
    @DisplayName("declineAppointmentItems — never enqueues a notification (D6, 2026-07-26 product "
            + "decision reversal) — the schedule-override-conflict path this method exists for tells "
            + "the client nothing beyond the booking's own status")
    void should_notEnqueueAnyNotification_when_decliningMultipleSiblings() {
        UUID actorId = UUID.randomUUID();
        UUID booking1 = UUID.randomUUID();
        UUID booking2 = UUID.randomUUID();
        com.beautica.booking.entity.Booking item1 = bookingItem(booking1, com.beautica.booking.enums.BookingStatus.CONFIRMED);
        com.beautica.booking.entity.Booking item2 = bookingItem(booking2, com.beautica.booking.enums.BookingStatus.CONFIRMED);
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId))
                .thenReturn(java.util.List.of(item1, item2));
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));
        when(bookingRepository.findConfirmedIdsByAppointmentId(appointmentId))
                .thenReturn(java.util.Set.of(booking1, booking2));

        appointmentTransitionService.declineAppointmentItems(
                actorId, appointmentId, java.util.List.of(booking1, booking2), null, false);

        verifyNoInteractions(outboxService);
    }

    @Test
    @DisplayName("declineAppointmentItems — evictAfterCommit=false (the schedule-override-conflict "
            + "path) registers no availability-cache eviction at all")
    void should_skipEviction_when_evictAfterCommitIsFalse() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        com.beautica.booking.entity.Booking item = bookingItem(bookingId, com.beautica.booking.enums.BookingStatus.CONFIRMED);
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(java.util.List.of(item));
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));
        when(bookingRepository.findConfirmedIdsByAppointmentId(appointmentId))
                .thenReturn(java.util.Set.of(bookingId));

        appointmentTransitionService.declineAppointmentItems(
                actorId, appointmentId, java.util.List.of(bookingId), null, false);

        verifyNoMoreInteractions(slotCalculationService);
        verify(salonCatalogCacheEvictor, never()).evict(any());
        verify(cachePrefixEvictor, never()).evictByKeyPrefixNow(any(), any());
    }

    @Test
    @DisplayName("declineAppointmentItems — evictAfterCommit=true (the default single-item shape) "
            + "registers the SAME after-commit eviction declineAppointmentItem would")
    void should_registerEviction_when_evictAfterCommitIsTrue() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        com.beautica.booking.entity.Booking item = bookingItem(bookingId, com.beautica.booking.enums.BookingStatus.CONFIRMED);
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(java.util.List.of(item));
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));
        when(bookingRepository.findConfirmedIdsByAppointmentId(appointmentId))
                .thenReturn(java.util.Set.of(bookingId));

        appointmentTransitionService.declineAppointmentItems(
                actorId, appointmentId, java.util.List.of(bookingId), null, true);

        // No active Spring transaction in this unit test — registerEviction's own "no synchronization
        // active" branch runs the eviction task immediately rather than skipping it.
        verify(slotCalculationService).evictAvailableSlots(any(), any(), any());
        verify(slotCalculationService).evictBookableFutureSlotsByMaster(any());
        verify(cachePrefixEvictor).evictByKeyPrefixNow(any(), eq("master-calendar"));
    }

    @Test
    @DisplayName("declineAppointmentItems — a bookingId that is not a child of this appointment is a 404, "
            + "and nothing is mutated")
    void should_throwNotFound_when_targetBookingIdIsNotAChildOfAppointment() {
        UUID actorId = UUID.randomUUID();
        UUID realChild = UUID.randomUUID();
        UUID foreignBookingId = UUID.randomUUID();
        com.beautica.booking.entity.Booking item = bookingItem(realChild, com.beautica.booking.enums.BookingStatus.CONFIRMED);
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(java.util.List.of(item));

        assertThatThrownBy(() -> appointmentTransitionService.declineAppointmentItems(
                actorId, appointmentId, java.util.List.of(realChild, foreignBookingId), null, false))
                .isInstanceOf(com.beautica.common.exception.NotFoundException.class);

        assertThat(item.getStatus())
                .as("no target may be mutated when any id in the batch fails validation")
                .isEqualTo(com.beautica.booking.enums.BookingStatus.CONFIRMED);
        verify(appointmentRepository, never()).lockHeaderIfConfirmed(any());
        verify(bookingRepository, never()).saveAll(any());
    }

    // ── rescheduleAppointment (whole-visit, cycle-5 audit finding 1) ────────────────────────────

    /**
     * Stubs everything {@code resolveVisitForClientReschedule} plus the availability/re-plan steps
     * of {@link AppointmentTransitionService#rescheduleAppointment} need to reach ITS OWN new
     * header-lock call, for a single-item CONFIRMED visit owned by {@code itemClient}. Both tests
     * below then diverge only on the lock/freshness stubs, so the new guard is pinned in isolation
     * from the (separately, IT-covered) happy-path mutation.
     */
    private Booking setUpWholeVisitRescheduleUpToLock(OffsetDateTime newFirstStart) {
        setUpRescheduleItemFixtures();
        UUID itemId = UUID.randomUUID();
        OffsetDateTime originalStart = OffsetDateTime.parse("2026-08-10T09:00:00Z");
        Booking item = confirmedItem(itemId, originalStart, originalStart.plusHours(1));
        Appointment appointment = Appointment.builder().id(appointmentId).client(itemClient).status(BookingStatus.CONFIRMED).build();

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(List.of(item));
        AvailableSlotResponse slot = new AvailableSlotResponse(
                newFirstStart.atZoneSameInstant(KYIV), newFirstStart.plusHours(1).atZoneSameInstant(KYIV));
        when(slotCalculationService.getAvailableSlots(eq(masterId), eq(newFirstStart.toLocalDate()), eq(List.of(masterServiceId))))
                .thenReturn(List.of(slot));
        when(visitPlanner.replanFromNewStart(eq(List.of(item)), eq(newFirstStart)))
                .thenReturn(List.of(new VisitPlanner.PlannedWindow(newFirstStart, newFirstStart.plusHours(1))));
        return item;
    }

    @Test
    @DisplayName("rescheduleAppointment — header lock runs BEFORE the client/master advisory locks "
            + "(canonical appointments-before-bookings order, cycle-5 audit finding 1), and a false "
            + "lock result is a clean 409 with NO advisory lock ever attempted and NOTHING mutated")
    void should_throw409AndNeverAcquireAdvisoryLocks_when_headerLeftConfirmedBeforeWholeVisitReschedule() {
        OffsetDateTime newFirstStart = OffsetDateTime.parse("2026-08-11T09:00:00Z");
        Booking item = setUpWholeVisitRescheduleUpToLock(newFirstStart);
        AppointmentRescheduleRequest req = new AppointmentRescheduleRequest(newFirstStart);
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentTransitionService.rescheduleAppointment(
                clientId, Role.CLIENT, appointmentId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(item.getStartsAt())
                .as("a lock loss must mutate nothing")
                .isEqualTo(OffsetDateTime.parse("2026-08-10T09:00:00Z"));
        verify(bookingRepository, never()).acquireClientAdvisoryLockWithTimeout(any());
        verify(bookingRepository, never()).acquireAdvisoryLock(any());
        verify(bookingRepository, never()).findConfirmedIdsByAppointmentId(any());
        verify(bookingRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("rescheduleAppointment — post-lock freshness re-check (cycle-5 audit finding 1): "
            + "the header lock alone succeeding is NOT enough if a target item already left CONFIRMED "
            + "concurrently — a stale target is a clean 409, with NO advisory lock ever attempted and "
            + "NOTHING mutated (this recheck is what rejects the whole transition rather than silently "
            + "re-planning a leg that already left CONFIRMED — see G1, cycle-7 audit 2026-08-03, for "
            + "why Booking's now-added @DynamicUpdate makes a stale save column-safe but does not make "
            + "proceeding with it correct)")
    void should_throw409AndNeverAcquireAdvisoryLocks_when_targetItemLeftConfirmedAfterHeaderLock() {
        OffsetDateTime newFirstStart = OffsetDateTime.parse("2026-08-11T09:00:00Z");
        Booking item = setUpWholeVisitRescheduleUpToLock(newFirstStart);
        AppointmentRescheduleRequest req = new AppointmentRescheduleRequest(newFirstStart);
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));
        // The header itself is still CONFIRMED (nothing collapsed it), but THIS item concurrently
        // left CONFIRMED (e.g. a per-service decline) — the freshness projection reflects that by
        // simply omitting its id, never returning a status value directly.
        when(bookingRepository.findConfirmedIdsByAppointmentId(appointmentId)).thenReturn(java.util.Set.of());

        assertThatThrownBy(() -> appointmentTransitionService.rescheduleAppointment(
                clientId, Role.CLIENT, appointmentId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(item.getStartsAt())
                .as("a lost freshness race must mutate nothing")
                .isEqualTo(OffsetDateTime.parse("2026-08-10T09:00:00Z"));
        verify(bookingRepository, never()).acquireClientAdvisoryLockWithTimeout(any());
        verify(bookingRepository, never()).acquireAdvisoryLock(any());
        verify(bookingRepository, never()).saveAll(any());
    }

    @Test
    @DisplayName("rescheduleAppointment — lock order is header, THEN client, THEN master (canonical "
            + "appointments-before-bookings order), reusing lockAppointmentHeaderBeforeItemReschedule "
            + "rather than a new mechanism, when every guard passes")
    void should_lockHeaderBeforeClientThenMasterAdvisoryLocks_when_wholeVisitRescheduleSucceeds() {
        OffsetDateTime newFirstStart = OffsetDateTime.parse("2026-08-11T09:00:00Z");
        Booking item = setUpWholeVisitRescheduleUpToLock(newFirstStart);
        AppointmentRescheduleRequest req = new AppointmentRescheduleRequest(newFirstStart);
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));
        when(bookingRepository.findConfirmedIdsByAppointmentId(appointmentId))
                .thenReturn(java.util.Set.of(item.getId()));
        when(bookingRepository.acquireClientAdvisoryLockWithTimeout(clientId)).thenReturn(1);
        when(bookingRepository.findFirstConflictingClientBookingIdExcludingAppointment(
                eq(clientId), any(), any(), eq(appointmentId))).thenReturn(Optional.empty());
        when(bookingRepository.acquireAdvisoryLock(masterId)).thenReturn(1);
        when(bookingRepository.existsOverlapExcludingAppointment(eq(masterId), any(), any(), eq(appointmentId)))
                .thenReturn(false);
        when(bookingRepository.saveAll(List.of(item))).thenReturn(List.of(item));
        when(appointmentService.enrich(any(), any())).thenReturn(org.mockito.Mockito.mock(AppointmentDetailResponse.class));

        appointmentTransitionService.rescheduleAppointment(clientId, Role.CLIENT, appointmentId, req);

        assertThat(item.getStartsAt()).isEqualTo(newFirstStart);
        InOrder order = inOrder(appointmentRepository, bookingRepository);
        order.verify(appointmentRepository).lockHeaderIfConfirmed(appointmentId);
        order.verify(bookingRepository).findConfirmedIdsByAppointmentId(appointmentId);
        order.verify(bookingRepository).acquireClientAdvisoryLockWithTimeout(clientId);
        order.verify(bookingRepository).acquireAdvisoryLock(masterId);
        order.verify(bookingRepository).saveAll(List.of(item));
    }

    @Test
    @DisplayName("rescheduleAppointment — F2/F3 (cycle-6 audit 2026-08-03): a PROVIDER without "
            + "authority over EVERY chained item (e.g. two items on different masters, the actor "
            + "authorized on only one) is rejected by the all-items enforceCanManageAppointment check "
            + "BEFORE any Appointment/Booking entity is loaded, and the OLD single-item "
            + "enforceCanRescheduleBooking check is never consulted")
    void should_rejectBeforeAnyLoadAndNeverUseSingleItemCheck_when_providerLacksAuthorityOverEveryItem() {
        UUID actorId = UUID.randomUUID();
        AppointmentRescheduleRequest req = new AppointmentRescheduleRequest(
                OffsetDateTime.parse("2026-08-11T09:00:00Z"));
        // Simulates AuthorizationService#enforceCanManageAppointment's own real behaviour for a
        // visit whose items sit on two DIFFERENT masters when the actor is authorized over only
        // one of them (pinned directly, with a real all-items projection, in
        // AuthorizationServiceTest — this test's job is only to prove
        // AppointmentTransitionService now calls THAT check, in THIS position, for the provider
        // reschedule path).
        doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanManageAppointment(actorId, appointmentId);

        assertThatThrownBy(() -> appointmentTransitionService.rescheduleAppointment(
                actorId, Role.SALON_OWNER, appointmentId, req))
                .isInstanceOf(ForbiddenException.class);

        verify(authz).enforceCanManageAppointment(actorId, appointmentId);
        // F3: no entity load of any kind before authorization has already succeeded.
        verify(appointmentRepository, never()).findById(any());
        verify(bookingRepository, never()).findByAppointmentIdWithGraph(any());
        // F2: the narrower single-item check this method used to call is fully retired here.
        verify(authz, never()).enforceCanRescheduleBooking(any(), any());
    }

    // ── rescheduleAppointmentItem (phase 30.4) + assertNoSiblingOverlap (phase 30.3) ────────────

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    private Master master;
    private MasterServiceAssignment msa;
    private User itemClient;
    private UUID masterId;
    private UUID masterServiceId;
    private UUID clientId;

    /** A CONFIRMED item sharing the shared master/masterService fixture, unless overridden. */
    private Booking confirmedItem(UUID id, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        return Booking.builder()
                .id(id)
                .client(itemClient)
                .master(master)
                .masterService(msa)
                .status(BookingStatus.CONFIRMED)
                .startsAt(startsAt)
                .endsAt(endsAt)
                .priceAtBooking(new BigDecimal("500.00"))
                .durationMinutesAtBooking((int) java.time.Duration.between(startsAt, endsAt).toMinutes())
                .bufferMinutesAtBooking(0)
                .build();
    }

    private Booking terminalItem(UUID id, BookingStatus status, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        Booking b = confirmedItem(id, startsAt, endsAt);
        b.setStatus(status);
        return b;
    }

    private void setIdViaReflection(Object target, UUID id) {
        try {
            var field = findIdField(target.getClass());
            field.setAccessible(true);
            field.set(target, id);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private java.lang.reflect.Field findIdField(Class<?> clazz) throws NoSuchFieldException {
        Class<?> current = clazz;
        while (current != null) {
            try {
                return current.getDeclaredField("id");
            } catch (NoSuchFieldException e) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException("id");
    }

    private void setUpRescheduleItemFixtures() {
        masterId = UUID.randomUUID();
        masterServiceId = UUID.randomUUID();
        clientId = UUID.randomUUID();
        master = Master.builder().id(masterId).build();
        msa = MasterServiceAssignment.builder().id(masterServiceId).build();
        itemClient = new User("client@example.com", "hash", Role.CLIENT, "First", "Last", "+380501234567");
        setIdViaReflection(itemClient, clientId); // User has no @Builder — id needs reflection
    }

    /** Stubs the single-service schedule-fit oracle so {@code newStartsAt} resolves to an on-schedule slot. */
    private void stubItemSlotAvailable(OffsetDateTime newStartsAt) {
        AvailableSlotResponse slot = new AvailableSlotResponse(
                newStartsAt.atZoneSameInstant(KYIV), newStartsAt.plusMinutes(60).atZoneSameInstant(KYIV));
        when(slotCalculationService.getAvailableSlots(eq(masterId), any(), eq(masterServiceId)))
                .thenReturn(List.of(slot));
    }

    @Test
    @DisplayName("rescheduleAppointmentItem — CLIENT happy path: moves ONLY the target row, leaves "
            + "the sibling byte-for-byte unchanged, locks header→client→master in order, notifies "
            + "referencing the MOVED CHILD, and never collapses the header")
    void should_moveOnlyTargetAndLeaveSiblingUntouched_when_clientReschedulesConfirmedItem() {
        setUpRescheduleItemFixtures();
        UUID targetId = UUID.randomUUID();
        UUID siblingId = UUID.randomUUID();
        OffsetDateTime siblingStart = OffsetDateTime.parse("2026-08-10T09:00:00Z");
        Booking sibling = confirmedItem(siblingId, siblingStart, siblingStart.plusHours(1));
        Booking target = confirmedItem(targetId, siblingStart.plusHours(2), siblingStart.plusHours(3));
        Appointment appointment = Appointment.builder().id(appointmentId).client(itemClient).build();
        OffsetDateTime newStartsAt = OffsetDateTime.parse("2026-08-11T09:00:00Z");
        AppointmentItemRescheduleRequest req = new AppointmentItemRescheduleRequest(newStartsAt);
        AppointmentDetailResponse enriched = org.mockito.Mockito.mock(AppointmentDetailResponse.class);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(clientId));
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(List.of(sibling, target));
        stubItemSlotAvailable(newStartsAt);
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));
        // F1 freshness re-check (cycle-6 audit 2026-08-03): target is still CONFIRMED post-lock.
        when(bookingRepository.existsConfirmedById(targetId)).thenReturn(true);
        when(bookingRepository.acquireClientAdvisoryLockWithTimeout(clientId)).thenReturn(1);
        when(bookingRepository.findFirstConflictingClientBookingIdExcluding(eq(clientId), any(), any(), eq(targetId)))
                .thenReturn(Optional.empty());
        when(bookingRepository.acquireAdvisoryLock(masterId)).thenReturn(1);
        when(bookingRepository.existsOverlapExcluding(eq(masterId), any(), any(), eq(targetId))).thenReturn(false);
        when(bookingRepository.saveAndFlush(target)).thenReturn(target);
        when(appointmentService.enrich(eq(appointment), any())).thenReturn(enriched);

        AppointmentDetailResponse result = appointmentTransitionService.rescheduleAppointmentItem(
                clientId, Role.CLIENT, appointmentId, targetId, req);

        assertThat(result).isSameAs(enriched);
        assertThat(target.getStartsAt()).isEqualTo(newStartsAt);
        assertThat(sibling.getStartsAt())
                .as("the sibling must keep its window byte-for-byte unchanged (L2)")
                .isEqualTo(siblingStart);
        verify(outboxService).enqueueBookingRescheduled(targetId, false);
        verify(appointmentRepository, never()).collapseHeaderIfNoConfirmedSiblingsRemain(any(), any(), any(), any());

        InOrder order = inOrder(appointmentRepository, bookingRepository);
        order.verify(appointmentRepository).lockHeaderIfConfirmed(appointmentId);
        order.verify(bookingRepository).acquireClientAdvisoryLockWithTimeout(clientId);
        order.verify(bookingRepository).acquireAdvisoryLock(masterId);
        order.verify(bookingRepository).saveAndFlush(target);
    }

    @Test
    @DisplayName("rescheduleAppointmentItem — PROVIDER-initiated move notifies with initiatedByProvider=true")
    void should_notifyProviderInitiated_when_providerReschedulesConfirmedItem() {
        setUpRescheduleItemFixtures();
        UUID targetId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.parse("2026-08-10T09:00:00Z");
        Booking target = confirmedItem(targetId, start, start.plusHours(1));
        Appointment appointment = Appointment.builder().id(appointmentId).client(itemClient).build();
        OffsetDateTime newStartsAt = OffsetDateTime.parse("2026-08-11T09:00:00Z");
        AppointmentItemRescheduleRequest req = new AppointmentItemRescheduleRequest(newStartsAt);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(List.of(target));
        stubItemSlotAvailable(newStartsAt);
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));
        // F1 freshness re-check (cycle-6 audit 2026-08-03): target is still CONFIRMED post-lock.
        when(bookingRepository.existsConfirmedById(targetId)).thenReturn(true);
        when(bookingRepository.acquireClientAdvisoryLockWithTimeout(clientId)).thenReturn(1);
        when(bookingRepository.findFirstConflictingClientBookingIdExcluding(eq(clientId), any(), any(), eq(targetId)))
                .thenReturn(Optional.empty());
        when(bookingRepository.acquireAdvisoryLock(masterId)).thenReturn(1);
        when(bookingRepository.existsOverlapExcluding(eq(masterId), any(), any(), eq(targetId))).thenReturn(false);
        when(bookingRepository.saveAndFlush(target)).thenReturn(target);
        when(appointmentService.enrich(any(), any())).thenReturn(org.mockito.Mockito.mock(AppointmentDetailResponse.class));

        appointmentTransitionService.rescheduleAppointmentItem(actorId, Role.SALON_OWNER, appointmentId, targetId, req);

        verify(outboxService).enqueueBookingRescheduled(targetId, true);
        // Provider path never touches enforceCanManageAppointment's return value here — this test
        // stubs it as a no-op via the default Mockito void behaviour (authorized).
        verify(authz).enforceCanManageAppointment(actorId, appointmentId);
    }

    @Test
    @DisplayName("assertNoSiblingOverlap (phase 30.3) — moving the target onto a CONFIRMED sibling's "
            + "window is a 409, and NOTHING is mutated or locked")
    void should_throw409_when_targetWouldOverlapConfirmedSibling() {
        setUpRescheduleItemFixtures();
        UUID targetId = UUID.randomUUID();
        UUID siblingId = UUID.randomUUID();
        OffsetDateTime siblingStart = OffsetDateTime.parse("2026-08-10T09:00:00Z");
        Booking sibling = confirmedItem(siblingId, siblingStart, siblingStart.plusHours(1));
        OffsetDateTime targetStart = siblingStart.plusHours(3);
        Booking target = confirmedItem(targetId, targetStart, targetStart.plusHours(1));
        Appointment appointment = Appointment.builder().id(appointmentId).client(itemClient).build();
        // Requested new window lands EXACTLY on the sibling's window — a strict overlap.
        AppointmentItemRescheduleRequest req = new AppointmentItemRescheduleRequest(siblingStart);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(clientId));
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(List.of(sibling, target));
        stubItemSlotAvailable(siblingStart);

        assertThatThrownBy(() -> appointmentTransitionService.rescheduleAppointmentItem(
                clientId, Role.CLIENT, appointmentId, targetId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(target.getStartsAt()).isEqualTo(targetStart);
        assertThat(sibling.getStartsAt()).isEqualTo(siblingStart);
        verify(appointmentRepository, never()).lockHeaderIfConfirmed(any());
        verify(bookingRepository, never()).acquireAdvisoryLock(any());
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("assertNoSiblingOverlap (phase 30.3) — landing EXACTLY on a sibling's boundary "
            + "(half-open touch) is legal, not an overlap")
    void should_allow_when_targetLandsExactlyOnSiblingBoundary() {
        setUpRescheduleItemFixtures();
        UUID targetId = UUID.randomUUID();
        UUID siblingId = UUID.randomUUID();
        OffsetDateTime siblingStart = OffsetDateTime.parse("2026-08-10T09:00:00Z");
        OffsetDateTime siblingEnd = siblingStart.plusHours(1);
        Booking sibling = confirmedItem(siblingId, siblingStart, siblingEnd);
        Booking target = confirmedItem(targetId, siblingStart.plusHours(5), siblingStart.plusHours(6));
        Appointment appointment = Appointment.builder().id(appointmentId).client(itemClient).build();
        // New window starts EXACTLY where the sibling ends — legal back-to-back touch.
        AppointmentItemRescheduleRequest req = new AppointmentItemRescheduleRequest(siblingEnd);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(clientId));
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(List.of(sibling, target));
        stubItemSlotAvailable(siblingEnd);
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));
        // F1 freshness re-check (cycle-6 audit 2026-08-03): target is still CONFIRMED post-lock.
        when(bookingRepository.existsConfirmedById(targetId)).thenReturn(true);
        when(bookingRepository.acquireClientAdvisoryLockWithTimeout(clientId)).thenReturn(1);
        when(bookingRepository.findFirstConflictingClientBookingIdExcluding(eq(clientId), any(), any(), eq(targetId)))
                .thenReturn(Optional.empty());
        when(bookingRepository.acquireAdvisoryLock(masterId)).thenReturn(1);
        when(bookingRepository.existsOverlapExcluding(eq(masterId), any(), any(), eq(targetId))).thenReturn(false);
        when(bookingRepository.saveAndFlush(target)).thenReturn(target);
        when(appointmentService.enrich(any(), any())).thenReturn(org.mockito.Mockito.mock(AppointmentDetailResponse.class));

        appointmentTransitionService.rescheduleAppointmentItem(clientId, Role.CLIENT, appointmentId, targetId, req);

        assertThat(target.getStartsAt()).isEqualTo(siblingEnd);
    }

    @Test
    @DisplayName("assertNoSiblingOverlap (phase 30.3) — a DECLINED sibling's released slot may be "
            + "moved into (terminal exemption)")
    void should_allow_when_targetOverlapsOnlyADeclinedSibling() {
        setUpRescheduleItemFixtures();
        UUID targetId = UUID.randomUUID();
        UUID siblingId = UUID.randomUUID();
        OffsetDateTime siblingStart = OffsetDateTime.parse("2026-08-10T09:00:00Z");
        Booking declinedSibling = terminalItem(siblingId, BookingStatus.DECLINED, siblingStart, siblingStart.plusHours(6));
        Booking target = confirmedItem(targetId, siblingStart.plusHours(10), siblingStart.plusHours(11));
        Appointment appointment = Appointment.builder().id(appointmentId).client(itemClient).build();
        // New window lands INSIDE the declined sibling's released window — allowed, it is terminal.
        OffsetDateTime newStartsAt = siblingStart.plusHours(1);
        AppointmentItemRescheduleRequest req = new AppointmentItemRescheduleRequest(newStartsAt);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(clientId));
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(List.of(declinedSibling, target));
        stubItemSlotAvailable(newStartsAt);
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));
        // F1 freshness re-check (cycle-6 audit 2026-08-03): target is still CONFIRMED post-lock.
        when(bookingRepository.existsConfirmedById(targetId)).thenReturn(true);
        when(bookingRepository.acquireClientAdvisoryLockWithTimeout(clientId)).thenReturn(1);
        when(bookingRepository.findFirstConflictingClientBookingIdExcluding(eq(clientId), any(), any(), eq(targetId)))
                .thenReturn(Optional.empty());
        when(bookingRepository.acquireAdvisoryLock(masterId)).thenReturn(1);
        when(bookingRepository.existsOverlapExcluding(eq(masterId), any(), any(), eq(targetId))).thenReturn(false);
        when(bookingRepository.saveAndFlush(target)).thenReturn(target);
        when(appointmentService.enrich(any(), any())).thenReturn(org.mockito.Mockito.mock(AppointmentDetailResponse.class));

        appointmentTransitionService.rescheduleAppointmentItem(clientId, Role.CLIENT, appointmentId, targetId, req);

        assertThat(target.getStartsAt()).isEqualTo(newStartsAt);
    }

    @Test
    @DisplayName("rescheduleAppointmentItem — a non-CONFIRMED header (lockAppointmentHeaderBeforeItemReschedule "
            + "returns false) IS a 409 on this appointment-scoped route (phase 30.1 D4, opposite of the "
            + "legacy booking-scoped route's phase 30.2 D5)")
    void should_throw409_when_headerNoLongerConfirmed() {
        setUpRescheduleItemFixtures();
        UUID targetId = UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.parse("2026-08-10T09:00:00Z");
        Booking target = confirmedItem(targetId, start, start.plusHours(1));
        Appointment appointment = Appointment.builder().id(appointmentId).client(itemClient).build();
        OffsetDateTime newStartsAt = OffsetDateTime.parse("2026-08-11T09:00:00Z");
        AppointmentItemRescheduleRequest req = new AppointmentItemRescheduleRequest(newStartsAt);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(clientId));
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(List.of(target));
        stubItemSlotAvailable(newStartsAt);
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> appointmentTransitionService.rescheduleAppointmentItem(
                clientId, Role.CLIENT, appointmentId, targetId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(bookingRepository, never()).acquireClientAdvisoryLockWithTimeout(any());
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    /**
     * Regression test for QA cycle-8 finding 2 (LOW) — {@code rescheduleAppointmentItem}'s F1
     * freshness-recheck-false branch (the {@code existsConfirmedById} → 409 path) previously had NO
     * deterministic unit coverage; only {@code AppointmentCrossPathTransitionConcurrencyIT}'s
     * (compromised, branch-on-the-guard-under-test) concurrency tests touched it at all. Distinct from
     * {@code should_throw409_when_headerNoLongerConfirmed} above, which pins the LOCK's own false
     * branch ({@code lockHeaderIfConfirmed} empty) — here the lock SUCCEEDS (the header is still
     * CONFIRMED) and it is F1's separate, item-scoped probe that fails.
     */
    @Test
    @DisplayName("rescheduleAppointmentItem — F1 (cycle-6 audit 2026-08-03): the header lock succeeding "
            + "is NOT enough if the TARGET ITEM ITSELF already left CONFIRMED concurrently (e.g. a "
            + "per-item cancel/decline of this SAME leg) — existsConfirmedById returning false is a "
            + "clean 409, with NO advisory lock ever attempted and NOTHING mutated")
    void should_throw409AndNeverAcquireAdvisoryLocks_when_targetItselfLeftConfirmedAfterHeaderLock() {
        setUpRescheduleItemFixtures();
        UUID targetId = UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.parse("2026-08-10T09:00:00Z");
        Booking target = confirmedItem(targetId, start, start.plusHours(1));
        Appointment appointment = Appointment.builder().id(appointmentId).client(itemClient).build();
        OffsetDateTime newStartsAt = OffsetDateTime.parse("2026-08-11T09:00:00Z");
        AppointmentItemRescheduleRequest req = new AppointmentItemRescheduleRequest(newStartsAt);

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(clientId));
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(List.of(target));
        stubItemSlotAvailable(newStartsAt);
        // The header itself is still CONFIRMED — the lock succeeds...
        when(appointmentRepository.lockHeaderIfConfirmed(appointmentId)).thenReturn(Optional.of(appointmentId));
        // ...but THIS item concurrently left CONFIRMED (e.g. a per-item client cancel or provider
        // decline of the SAME leg, fired near-concurrently at the sibling per-item endpoint) — F1's
        // freshness probe reflects that directly, never a second entity load of target itself.
        when(bookingRepository.existsConfirmedById(targetId)).thenReturn(false);

        assertThatThrownBy(() -> appointmentTransitionService.rescheduleAppointmentItem(
                clientId, Role.CLIENT, appointmentId, targetId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("Service changed concurrently");

        assertThat(target.getStartsAt())
                .as("F1: a lost freshness race on the TARGET ITEM ITSELF must mutate nothing")
                .isEqualTo(start);
        verify(bookingRepository, never()).acquireClientAdvisoryLockWithTimeout(any());
        verify(bookingRepository, never()).acquireAdvisoryLock(any());
        verify(bookingRepository, never()).saveAndFlush(any());
        verify(outboxService, never()).enqueueBookingRescheduled(any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    @DisplayName("rescheduleAppointmentItem — bookingId is not a child of appointmentId is a 404, "
            + "reached only AFTER the caller is already authorized on the visit")
    void should_throwNotFound_when_bookingIdIsNotAChildOfAppointment() {
        setUpRescheduleItemFixtures();
        UUID realChild = UUID.randomUUID();
        UUID foreignBookingId = UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.parse("2026-08-10T09:00:00Z");
        Booking item = confirmedItem(realChild, start, start.plusHours(1));
        Appointment appointment = Appointment.builder().id(appointmentId).client(itemClient).build();
        AppointmentItemRescheduleRequest req = new AppointmentItemRescheduleRequest(start.plusDays(1));

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(clientId));
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(List.of(item));

        assertThatThrownBy(() -> appointmentTransitionService.rescheduleAppointmentItem(
                clientId, Role.CLIENT, appointmentId, foreignBookingId, req))
                .isInstanceOf(NotFoundException.class);

        verify(appointmentRepository, never()).lockHeaderIfConfirmed(any());
    }

    @Test
    @DisplayName("rescheduleAppointmentItem — PROVIDER without authority over the visit is rejected "
            + "BEFORE any entity load")
    void should_throwForbidden_when_providerLacksAuthorityOverVisit() {
        setUpRescheduleItemFixtures();
        UUID actorId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanManageAppointment(actorId, appointmentId);
        AppointmentItemRescheduleRequest req = new AppointmentItemRescheduleRequest(
                OffsetDateTime.parse("2026-08-10T09:00:00Z"));

        assertThatThrownBy(() -> appointmentTransitionService.rescheduleAppointmentItem(
                actorId, Role.SALON_OWNER, appointmentId, targetId, req))
                .isInstanceOf(ForbiddenException.class);

        verify(appointmentRepository, never()).findById(any());
        verify(bookingRepository, never()).findByAppointmentIdWithGraph(any());
    }

    @Test
    @DisplayName("rescheduleAppointmentItem — CLIENT on a foreign visit is rejected BEFORE any entity load "
            + "(uniform 403, no existence oracle)")
    void should_throwForbidden_when_clientDoesNotOwnVisit() {
        setUpRescheduleItemFixtures();
        UUID foreignClientId = UUID.randomUUID();
        UUID targetId = UUID.randomUUID();
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(foreignClientId));
        AppointmentItemRescheduleRequest req = new AppointmentItemRescheduleRequest(
                OffsetDateTime.parse("2026-08-10T09:00:00Z"));

        assertThatThrownBy(() -> appointmentTransitionService.rescheduleAppointmentItem(
                clientId, Role.CLIENT, appointmentId, targetId, req))
                .isInstanceOf(ForbiddenException.class);

        verify(appointmentRepository, never()).findById(any());
        verify(bookingRepository, never()).findByAppointmentIdWithGraph(any());
    }

    @Test
    @DisplayName("rescheduleAppointmentItem — security audit F5 tripwire: the CLIENT owns the VISIT "
            + "(Appointment.client_id matches) but the TARGET child's OWN client_id disagrees — a "
            + "DB-unenforced invariant violation — rejected with the SAME uniform 403, and NOTHING is "
            + "locked or mutated")
    void should_throwForbidden_when_targetChildClientDisagreesWithVisitOwner() {
        setUpRescheduleItemFixtures();
        UUID targetId = UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.parse("2026-08-10T09:00:00Z");
        User mismatchedClient = new User(
                "mismatch@example.com", "hash", Role.CLIENT, "Mis", "Match", "+380509999999");
        setIdViaReflection(mismatchedClient, UUID.randomUUID());
        Booking target = Booking.builder()
                .id(targetId)
                .client(mismatchedClient)
                .master(master)
                .masterService(msa)
                .status(BookingStatus.CONFIRMED)
                .startsAt(start)
                .endsAt(start.plusHours(1))
                .priceAtBooking(new BigDecimal("500.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .build();
        Appointment appointment = Appointment.builder().id(appointmentId).client(itemClient).build();
        AppointmentItemRescheduleRequest req = new AppointmentItemRescheduleRequest(start.plusDays(1));

        // The VISIT-level guard passes — clientId genuinely owns appointmentId.
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(clientId));
        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(List.of(target));

        assertThatThrownBy(() -> appointmentTransitionService.rescheduleAppointmentItem(
                clientId, Role.CLIENT, appointmentId, targetId, req))
                .isInstanceOf(ForbiddenException.class);

        assertThat(target.getStartsAt())
                .as("the mismatched child must be completely untouched")
                .isEqualTo(start);
        verify(appointmentRepository, never()).lockHeaderIfConfirmed(any());
        verify(bookingRepository, never()).acquireClientAdvisoryLockWithTimeout(any());
        verify(bookingRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("rescheduleAppointmentItem — target item already terminal (not CONFIRMED) is a 409")
    void should_throw409_when_targetItemNotConfirmed() {
        setUpRescheduleItemFixtures();
        UUID targetId = UUID.randomUUID();
        OffsetDateTime start = OffsetDateTime.parse("2026-08-10T09:00:00Z");
        Booking target = terminalItem(targetId, BookingStatus.DECLINED, start, start.plusHours(1));
        Appointment appointment = Appointment.builder().id(appointmentId).client(itemClient).build();
        AppointmentItemRescheduleRequest req = new AppointmentItemRescheduleRequest(start.plusDays(1));

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(clientId));
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId)).thenReturn(List.of(target));

        assertThatThrownBy(() -> appointmentTransitionService.rescheduleAppointmentItem(
                clientId, Role.CLIENT, appointmentId, targetId, req))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        verify(appointmentRepository, never()).lockHeaderIfConfirmed(any());
    }

    @Test
    @DisplayName("rescheduleAppointmentItem — CLIENT moving an already-elapsed target item (per-row "
            + "guard, phase 30.1 D6) is a 409, even though a sibling is still future")
    void should_throwBookingElapsed_when_clientReschedulesElapsedTargetItem() {
        setUpRescheduleItemFixtures();
        UUID targetId = UUID.randomUUID();
        UUID futureSiblingId = UUID.randomUUID();
        // "now" is 2026-07-26T10:00:00Z (the class-level fixed clock) — this target already ended.
        OffsetDateTime elapsedStart = Instant.parse("2026-07-26T08:00:00Z").atOffset(ZoneOffset.UTC);
        Booking target = confirmedItem(targetId, elapsedStart, elapsedStart.plusHours(1));
        Booking futureSibling = confirmedItem(futureSiblingId,
                OffsetDateTime.parse("2026-08-10T09:00:00Z"), OffsetDateTime.parse("2026-08-10T10:00:00Z"));
        Appointment appointment = Appointment.builder().id(appointmentId).client(itemClient).build();
        AppointmentItemRescheduleRequest req = new AppointmentItemRescheduleRequest(
                OffsetDateTime.parse("2026-08-11T09:00:00Z"));

        when(appointmentRepository.findById(appointmentId)).thenReturn(Optional.of(appointment));
        when(appointmentRepository.findClientIdById(appointmentId)).thenReturn(Optional.of(clientId));
        when(bookingRepository.findByAppointmentIdWithGraph(appointmentId))
                .thenReturn(List.of(target, futureSibling));

        assertThatThrownBy(() -> appointmentTransitionService.rescheduleAppointmentItem(
                clientId, Role.CLIENT, appointmentId, targetId, req))
                .isInstanceOf(BookingElapsedException.class);

        verify(appointmentRepository, never()).lockHeaderIfConfirmed(any());
        verify(slotCalculationService, never()).getAvailableSlots(any(), any(), any(UUID.class));
    }
}
