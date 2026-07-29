package com.beautica.booking.service;

import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.cache.MasterCachePrefixEvictor;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
}
