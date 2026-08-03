package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.AppointmentDetailResponse;
import com.beautica.booking.dto.AppointmentItemRescheduleRequest;
import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.entity.Appointment;
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
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

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
