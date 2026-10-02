package com.beautica.user;

import com.beautica.auth.AuthService;
import com.beautica.auth.Role;
import com.beautica.auth.TokensValidAfterCache;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.SalonClosureBookingCandidate;
import com.beautica.booking.service.BookingService;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.repository.MediaRepository;
import com.beautica.review.repository.ClientReviewRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClientAccountDeletionService#deleteOwnAccount} (Phase 300; future-booking
 * disposal REVERSED by Phase 338; the cascade re-routed through a BATCHED cancel entry point by
 * the 2026-09 perf/security audit, items 1-3).
 *
 * <p>Role/salonId scoping is already enforced by {@code @PreAuthorize("hasRole('CLIENT')")} on
 * {@code UserController#deleteMyAccount}. These tests cover the service-layer behaviour that is
 * NOT expressible in SpEL — the defence-in-depth re-check (§3) — plus the disposal ORDER: acquire
 * the client's own advisory lock (Phase 338 race fix), cancel future bookings through
 * {@code BookingService#cancelFutureConfirmedBookingsForClientSelfDelete} (never a per-booking
 * {@code cancelBooking} loop, never a bulk-decline seam — perf audit item 2), KEEP them, enqueue
 * one {@code CLIENT_CANCELLED} row per visit with NO outbox delete beforehand (perf/security audit
 * items 1 and 3 — the batched cancel entry point never writes a per-booking {@code STATUS_CHANGED}
 * row in the first place), detach every remaining booking/appointment (future AND past, uniformly),
 * delete {@code client_reviews}, flush, then the hard delete, then the after-commit cache
 * evictions, the token denylist, and the after-commit R2 sweep registration. Mirrors {@code
 * SalonServiceRemoveAdminTest}'s shape for the analogous staff hard-delete flow.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClientAccountDeletionService.deleteOwnAccount — unit")
class ClientAccountDeletionServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private AppointmentRepository appointmentRepository;

    @Mock
    private BookingService bookingService;

    @Mock
    private ClientReviewRepository clientReviewRepository;

    @Mock
    private MediaRepository mediaRepository;

    @Mock
    private AccountBlobPurgeRegistrar accountBlobPurgeRegistrar;

    @Mock
    private TokensValidAfterCache tokensValidAfterCache;

    @Mock
    private UserProfileCacheEvictor userProfileCacheEvictor;

    @Mock
    private AuthService authService;

    @Mock
    private Clock clock;

    @InjectMocks
    private ClientAccountDeletionService service;

    private static SalonClosureBookingCandidate candidate(UUID bookingId) {
        return candidate(bookingId, null);
    }

    private static SalonClosureBookingCandidate candidate(UUID bookingId, UUID appointmentId) {
        return new SalonClosureBookingCandidate(
                bookingId, appointmentId, UUID.randomUUID(), OffsetDateTime.now().plusDays(3));
    }

    @Test
    @DisplayName("Phase 338: acquires the client's own advisory lock BEFORE reading future "
            + "candidates, cancels+KEEPS every future CONFIRMED booking through the BATCHED "
            + "client-cancel entry point (perf audit item 2), enqueues one CLIENT_CANCELLED row "
            + "per visit with no outbox delete first (perf audit item 1/3), detaches every "
            + "remaining booking/appointment (future and past alike), deletes client_reviews, "
            + "flushes, then hard-deletes the user and denylists the caller's own token")
    void should_disposeEveryReferencingRow_then_hardDeleteUser_when_callerIsClientWithNoSalon() {
        UUID clientId = UUID.randomUUID();
        User client = buildClient(clientId);
        ReflectionTestUtils.setField(client, "avatarR2Key", "clients/" + clientId + "/avatar.jpg");

        UUID futureBookingId1 = UUID.randomUUID();
        UUID futureBookingId2 = UUID.randomUUID();
        List<SalonClosureBookingCandidate> futureCandidates =
                List.of(candidate(futureBookingId1), candidate(futureBookingId2));
        List<UUID> futureBookingIds = List.of(futureBookingId1, futureBookingId2);

        Appointment appointmentA = Appointment.builder().id(UUID.randomUUID()).client(client).build();
        Appointment appointmentB = Appointment.builder().id(UUID.randomUUID()).client(client).build();

        Booking remainingBooking1 = Booking.builder().id(UUID.randomUUID()).client(client).build();
        Booking remainingBooking2 = Booking.builder().id(UUID.randomUUID()).client(client).build();

        when(userRepository.findByIdForUpdate(clientId)).thenReturn(Optional.of(client));
        when(mediaRepository.findByUploaderId(clientId)).thenReturn(List.of());
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
        when(bookingService.findFutureConfirmedBookingCandidatesForClient(clientId)).thenReturn(futureCandidates);
        when(appointmentRepository.findByClientId(clientId))
                .thenReturn(List.of(appointmentA, appointmentB));
        when(bookingRepository.findByClientId(clientId))
                .thenReturn(List.of(remainingBooking1, remainingBooking2));

        service.deleteOwnAccount(clientId, "raw-access-token");

        // Race fix: the client's own advisory lock is acquired BEFORE the candidate read.
        InOrder order = inOrder(bookingService);
        order.verify(bookingService).acquireClientLockForSelfDelete(clientId);
        order.verify(bookingService).findFutureConfirmedBookingCandidatesForClient(clientId);

        // Perf audit item 2: every future candidate is cancelled through the ONE batched entry
        // point — never a per-booking cancelBooking loop, never the salon/master bulk-decline seam
        // — then KEPT (Phase 338 — never physically deleted).
        order.verify(bookingService).cancelFutureConfirmedBookingsForClientSelfDelete(
                eq(clientId), eq(futureBookingIds), any(CancelBookingRequest.class));
        verify(bookingRepository, never()).deleteAllByIdInBatch(any());
        verify(bookingService, never()).cancelBooking(any(), any(), any());

        // Perf audit item 1/3: the batched cancel entry point never enqueues a per-booking
        // STATUS_CHANGED row (see BookingService#cancelBookingForBatch), so there is nothing to
        // delete from the outbox before enqueueing exactly ONE CLIENT_CANCELLED row per visit.
        order.verify(bookingService).enqueueClientCancelledPerVisit(futureCandidates);

        // Appointment headers: every one is unconditionally DETACHED, never deleted (Phase 338 —
        // no header can end up childless any more, since every leg is kept).
        verify(appointmentRepository, never()).deleteAllByIdInBatch(any());
        assertThat(appointmentA.isClientDetached()).isTrue();
        assertThat(appointmentA.getClient()).isNull();
        assertThat(appointmentA.getGuestName()).isEqualTo(ClientAccountDeletionService.DETACHED_CLIENT_LABEL);
        assertThat(appointmentA.getGuestSurname()).isNull();
        assertThat(appointmentB.isClientDetached()).isTrue();

        // Every remaining booking (future-cancelled AND past) is detached — one state change, never split.
        assertThat(remainingBooking1.isClientDetached()).isTrue();
        assertThat(remainingBooking1.getClient()).isNull();
        assertThat(remainingBooking1.getGuestName()).isEqualTo(ClientAccountDeletionService.DETACHED_CLIENT_LABEL);
        assertThat(remainingBooking2.isClientDetached()).isTrue();

        // client_reviews (provider→client) are deleted outright — D3.
        verify(clientReviewRepository).deleteBySubjectClientId(clientId);

        // The explicit flush MUST run before the hard delete (mandatory, not defensive).
        verify(bookingRepository).flush();
        verify(userRepository).deleteAllByIdInBatch(List.of(clientId));

        // After-commit cache evictions and the token denylist.
        verify(tokensValidAfterCache).invalidateAfterCommit(clientId);
        verify(userProfileCacheEvictor).evictAfterCommit(clientId);
        verify(authService).denylistAccessToken("raw-access-token");

        verify(bookingService, times(1)).cancelFutureConfirmedBookingsForClientSelfDelete(any(), any(), any());
    }

    @Test
    @DisplayName("Phase 338: groups a multi-service visit's future candidates by appointmentId — "
            + "still cancels every leg through the ONE batched entry point, but enqueues exactly "
            + "ONE CLIENT_CANCELLED for the whole visit")
    void should_cancelEveryLegButEnqueueOnceForAMultiServiceVisit() {
        UUID clientId = UUID.randomUUID();
        User client = buildClient(clientId);
        UUID appointmentId = UUID.randomUUID();
        UUID legA = UUID.randomUUID();
        UUID legB = UUID.randomUUID();
        List<SalonClosureBookingCandidate> futureCandidates =
                List.of(candidate(legA, appointmentId), candidate(legB, appointmentId));

        when(userRepository.findByIdForUpdate(clientId)).thenReturn(Optional.of(client));
        when(mediaRepository.findByUploaderId(clientId)).thenReturn(List.of());
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
        when(bookingService.findFutureConfirmedBookingCandidatesForClient(clientId)).thenReturn(futureCandidates);
        when(appointmentRepository.findByClientId(clientId)).thenReturn(List.of());
        when(bookingRepository.findByClientId(clientId)).thenReturn(List.of());

        service.deleteOwnAccount(clientId, "token");

        verify(bookingService).cancelFutureConfirmedBookingsForClientSelfDelete(
                eq(clientId), eq(List.of(legA, legB)), any(CancelBookingRequest.class));
        verify(bookingService, times(1)).enqueueClientCancelledPerVisit(futureCandidates);
    }

    @Test
    @DisplayName("never issues the batched cancel/enqueue when no future bookings exist")
    void should_skipOutboxCleanupAndEnqueue_when_noFutureBookingsRemain() {
        UUID clientId = UUID.randomUUID();
        User client = buildClient(clientId);

        when(userRepository.findByIdForUpdate(clientId)).thenReturn(Optional.of(client));
        when(mediaRepository.findByUploaderId(clientId)).thenReturn(List.of());
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
        when(bookingService.findFutureConfirmedBookingCandidatesForClient(clientId)).thenReturn(List.of());
        when(appointmentRepository.findByClientId(clientId)).thenReturn(List.of());
        when(bookingRepository.findByClientId(clientId)).thenReturn(List.of());

        service.deleteOwnAccount(clientId, "token");

        verify(bookingService).cancelFutureConfirmedBookingsForClientSelfDelete(
                eq(clientId), eq(List.of()), any(CancelBookingRequest.class));
        verify(bookingService, never()).enqueueClientCancelledPerVisit(any());
    }

    @Test
    @DisplayName("detaches EVERY remaining appointment header unconditionally — never deletes one, "
            + "since Phase 338 keeps every future leg")
    void should_detachEveryAppointmentHeaderUnconditionally() {
        UUID clientId = UUID.randomUUID();
        User client = buildClient(clientId);

        Appointment appointment1 = Appointment.builder().id(UUID.randomUUID()).client(client).build();
        Appointment appointment2 = Appointment.builder().id(UUID.randomUUID()).client(client).build();

        when(userRepository.findByIdForUpdate(clientId)).thenReturn(Optional.of(client));
        when(mediaRepository.findByUploaderId(clientId)).thenReturn(List.of());
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
        when(bookingService.findFutureConfirmedBookingCandidatesForClient(clientId)).thenReturn(List.of());
        when(appointmentRepository.findByClientId(clientId))
                .thenReturn(List.of(appointment1, appointment2));
        when(bookingRepository.findByClientId(clientId)).thenReturn(List.of());

        service.deleteOwnAccount(clientId, "token");

        verify(appointmentRepository, never()).deleteAllByIdInBatch(any());
        assertThat(appointment1.isClientDetached()).isTrue();
        assertThat(appointment2.isClientDetached()).isTrue();
    }

    @Test
    @DisplayName("perf finding 2: throws BusinessException before any write when future booking count "
            + "exceeds the cap")
    void should_throwBusinessBeforeAnyWrite_when_futureBookingCountExceedsCap() {
        UUID clientId = UUID.randomUUID();
        User client = buildClient(clientId);
        List<SalonClosureBookingCandidate> tooMany = Stream
                .generate(() -> candidate(UUID.randomUUID()))
                .limit(ClientAccountDeletionService.MAX_FUTURE_BOOKINGS_PER_SELF_DELETE + 1)
                .toList();

        when(userRepository.findByIdForUpdate(clientId)).thenReturn(Optional.of(client));
        when(mediaRepository.findByUploaderId(clientId)).thenReturn(List.of());
        when(bookingService.findFutureConfirmedBookingCandidatesForClient(clientId)).thenReturn(tooMany);

        assertThatThrownBy(() -> service.deleteOwnAccount(clientId, "token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(String.valueOf(tooMany.size()))
                .hasMessageContaining(String.valueOf(ClientAccountDeletionService.MAX_FUTURE_BOOKINGS_PER_SELF_DELETE));

        // No cancellation, no enqueue, nothing detached — the cap fails BEFORE any write.
        verify(bookingService, never()).cancelFutureConfirmedBookingsForClientSelfDelete(any(), any(), any());
        verify(bookingService, never()).enqueueClientCancelledPerVisit(any());
        verify(userRepository, never()).deleteAllByIdInBatch(any());
        verifyNoInteractions(appointmentRepository, clientReviewRepository, accountBlobPurgeRegistrar);
        // The advisory lock is still acquired — the cap check itself needs the candidate read the
        // lock protects — but the cap check runs BEFORE any subsequent write.
        verify(bookingService).acquireClientLockForSelfDelete(clientId);
    }

    @Test
    @DisplayName("perf finding 2: succeeds when future booking count is exactly at the cap boundary")
    void should_succeed_when_futureBookingCountEqualsCapExactly() {
        UUID clientId = UUID.randomUUID();
        User client = buildClient(clientId);
        List<SalonClosureBookingCandidate> exactlyAtCap = Stream
                .generate(() -> candidate(UUID.randomUUID()))
                .limit(ClientAccountDeletionService.MAX_FUTURE_BOOKINGS_PER_SELF_DELETE)
                .toList();
        List<UUID> ids = exactlyAtCap.stream().map(SalonClosureBookingCandidate::bookingId).toList();

        when(userRepository.findByIdForUpdate(clientId)).thenReturn(Optional.of(client));
        when(mediaRepository.findByUploaderId(clientId)).thenReturn(List.of());
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
        when(bookingService.findFutureConfirmedBookingCandidatesForClient(clientId)).thenReturn(exactlyAtCap);
        when(appointmentRepository.findByClientId(clientId)).thenReturn(List.of());
        when(bookingRepository.findByClientId(clientId)).thenReturn(List.of());

        service.deleteOwnAccount(clientId, "token");

        verify(bookingService).cancelFutureConfirmedBookingsForClientSelfDelete(
                eq(clientId), eq(ids), any(CancelBookingRequest.class));
        verify(bookingService).enqueueClientCancelledPerVisit(exactlyAtCap);
        verify(userRepository).deleteAllByIdInBatch(List.of(clientId));
    }

    @Test
    @DisplayName("delegates the R2 blob purge registration to the shared AccountBlobPurgeRegistrar, "
            + "never calling MediaService inline (Phase 301 — promoted seam)")
    void should_delegateBlobPurgeRegistration_when_deletingOwnAccount() {
        UUID clientId = UUID.randomUUID();
        User client = buildClient(clientId);
        MediaFile portfolioRow = mock(MediaFile.class);

        when(userRepository.findByIdForUpdate(clientId)).thenReturn(Optional.of(client));
        when(mediaRepository.findByUploaderId(clientId)).thenReturn(List.of(portfolioRow));
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
        when(bookingService.findFutureConfirmedBookingCandidatesForClient(clientId)).thenReturn(List.of());
        when(appointmentRepository.findByClientId(clientId)).thenReturn(List.of());
        when(bookingRepository.findByClientId(clientId)).thenReturn(List.of());

        service.deleteOwnAccount(clientId, null);

        // The actual after-commit TransactionSynchronization mechanics now live inside
        // AccountBlobPurgeRegistrar itself (shared with StaffAccountSelfDeletionService) — pinned
        // by com.beautica.user.AccountBlobPurgeRegistrarTest (backend-qa follow-up). This test
        // only pins that the service hands it the correct pre-read pointers, never calling
        // MediaService directly.
        verify(accountBlobPurgeRegistrar).registerAfterCommit(clientId, null, List.of(portfolioRow));
    }

    @Test
    @DisplayName("throws ForbiddenException when the caller's own user row does not exist")
    void should_throwForbidden_when_userDoesNotExist() {
        UUID clientId = UUID.randomUUID();
        when(userRepository.findByIdForUpdate(clientId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteOwnAccount(clientId, "token"))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(bookingService, appointmentRepository, clientReviewRepository,
                accountBlobPurgeRegistrar, authService, tokensValidAfterCache, userProfileCacheEvictor);
        verify(bookingRepository, never()).deleteAllByIdInBatch(any());
        verify(userRepository, never()).deleteAllByIdInBatch(any());
    }

    @Test
    @DisplayName("defence in depth: throws BusinessException when the reloaded user is not a CLIENT")
    void should_throwBusiness_when_userIsNotClient() {
        UUID userId = UUID.randomUUID();
        User notAClient = new User("owner@beautica.test", "hash", Role.SALON_OWNER, "O", "W", null);
        ReflectionTestUtils.setField(notAClient, "id", userId);
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(notAClient));

        assertThatThrownBy(() -> service.deleteOwnAccount(userId, "token"))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(bookingService, appointmentRepository, clientReviewRepository, accountBlobPurgeRegistrar);
        verify(userRepository, never()).deleteAllByIdInBatch(any());
    }

    @Test
    @DisplayName("defence in depth: throws BusinessException when the CLIENT carries a salonId")
    void should_throwBusiness_when_clientHasSalonId() {
        UUID clientId = UUID.randomUUID();
        User clientWithSalon = new User(
                "client@beautica.test", "hash", Role.CLIENT, "C", "L", null, UUID.randomUUID());
        ReflectionTestUtils.setField(clientWithSalon, "id", clientId);
        when(userRepository.findByIdForUpdate(clientId)).thenReturn(Optional.of(clientWithSalon));

        assertThatThrownBy(() -> service.deleteOwnAccount(clientId, "token"))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(bookingService, appointmentRepository, clientReviewRepository, accountBlobPurgeRegistrar);
        verify(userRepository, never()).deleteAllByIdInBatch(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private User buildClient(UUID id) {
        User user = new User("client@beautica.test", "hash", Role.CLIENT, "Клієнт", "Тестовий", null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
