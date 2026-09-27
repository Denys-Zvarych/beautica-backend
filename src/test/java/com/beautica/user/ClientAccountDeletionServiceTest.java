package com.beautica.user;

import com.beautica.auth.AuthService;
import com.beautica.auth.Role;
import com.beautica.auth.TokensValidAfterCache;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.AppointmentRepository;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.service.BookingService;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.repository.MediaRepository;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.beautica.review.repository.ClientReviewRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClientAccountDeletionService#deleteOwnAccount} (Phase 300).
 *
 * <p>Role/salonId scoping is already enforced by {@code @PreAuthorize("hasRole('CLIENT')")} on
 * {@code UserController#deleteMyAccount}. These tests cover the service-layer behaviour that is
 * NOT expressible in SpEL — the defence-in-depth re-check (§3) — plus the disposal ORDER: cancel
 * future bookings through the ordinary {@code cancelBooking} path (never a bulk-decline seam),
 * delete them, resolve childless-vs-surviving appointment headers, detach every remaining
 * booking/appointment, delete {@code client_reviews}, flush, then the hard delete, then the
 * after-commit cache evictions, the token denylist, and the after-commit R2 sweep registration.
 * Mirrors {@code SalonServiceRemoveAdminTest}'s shape for the analogous staff hard-delete flow.
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
    private NotificationOutboxRepository notificationOutboxRepository;

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

    @Test
    @DisplayName("cancels+deletes future CONFIRMED bookings, deletes childless appointment headers, "
            + "detaches every surviving booking/appointment, deletes client_reviews, flushes, then "
            + "hard-deletes the user and denylists the caller's own token")
    void should_disposeEveryReferencingRow_then_hardDeleteUser_when_callerIsClientWithNoSalon() {
        UUID clientId = UUID.randomUUID();
        User client = buildClient(clientId);
        ReflectionTestUtils.setField(client, "avatarR2Key", "clients/" + clientId + "/avatar.jpg");

        UUID futureBookingId1 = UUID.randomUUID();
        UUID futureBookingId2 = UUID.randomUUID();
        List<UUID> futureBookingIds = List.of(futureBookingId1, futureBookingId2);

        Appointment childlessAppointment = Appointment.builder().id(UUID.randomUUID()).client(client).build();
        Appointment survivingAppointment = Appointment.builder().id(UUID.randomUUID()).client(client).build();

        Booking survivingBooking1 = Booking.builder().id(UUID.randomUUID()).client(client).build();
        Booking survivingBooking2 = Booking.builder().id(UUID.randomUUID()).client(client).build();

        when(userRepository.findByIdForUpdate(clientId)).thenReturn(Optional.of(client));
        when(mediaRepository.findByUploaderId(clientId)).thenReturn(List.of());
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
        when(bookingService.findFutureConfirmedBookingIdsForClient(clientId)).thenReturn(futureBookingIds);
        when(appointmentRepository.findByClientId(clientId))
                .thenReturn(List.of(childlessAppointment, survivingAppointment));
        when(bookingRepository.findAppointmentIdsWithSurvivingBookings(
                List.of(childlessAppointment.getId(), survivingAppointment.getId())))
                .thenReturn(List.of(survivingAppointment.getId()));
        when(bookingRepository.findByClientId(clientId))
                .thenReturn(List.of(survivingBooking1, survivingBooking2));

        service.deleteOwnAccount(clientId, "raw-access-token");

        // Future bookings: cancelled ONE AT A TIME through the ordinary client cancel path, never
        // the salon/master bulk-decline seam — then physically deleted.
        verify(bookingService).cancelBooking(eq(clientId), eq(futureBookingId1), any(CancelBookingRequest.class));
        verify(bookingService).cancelBooking(eq(clientId), eq(futureBookingId2), any(CancelBookingRequest.class));
        // The STATUS_CHANGED outbox rows cancelBooking just enqueued for these bookings MUST be
        // deleted before the bookings themselves — aggregate_id carries no FK, so leaving them
        // would orphan the row and dead-letter it in the drain worker.
        verify(notificationOutboxRepository).deleteByAggregateIdIn(futureBookingIds);
        verify(bookingRepository).deleteAllByIdInBatch(futureBookingIds);

        // Appointment headers: childless one is physically deleted, the surviving one is detached.
        verify(appointmentRepository).deleteAllByIdInBatch(List.of(childlessAppointment.getId()));
        assertThat(survivingAppointment.isClientDetached()).isTrue();
        assertThat(survivingAppointment.getClient()).isNull();
        assertThat(survivingAppointment.getGuestName()).isEqualTo(ClientAccountDeletionService.DETACHED_CLIENT_LABEL);
        assertThat(survivingAppointment.getGuestSurname()).isNull();

        // Every remaining booking is detached — one state change, never split.
        assertThat(survivingBooking1.isClientDetached()).isTrue();
        assertThat(survivingBooking1.getClient()).isNull();
        assertThat(survivingBooking1.getGuestName()).isEqualTo(ClientAccountDeletionService.DETACHED_CLIENT_LABEL);
        assertThat(survivingBooking2.isClientDetached()).isTrue();

        // client_reviews (provider→client) are deleted outright — D3.
        verify(clientReviewRepository).deleteBySubjectClientId(clientId);

        // The explicit flush MUST run before the hard delete (mandatory, not defensive).
        verify(bookingRepository).flush();
        verify(userRepository).deleteAllByIdInBatch(List.of(clientId));

        // After-commit cache evictions and the token denylist.
        verify(tokensValidAfterCache).invalidateAfterCommit(clientId);
        verify(userProfileCacheEvictor).evictAfterCommit(clientId);
        verify(authService).denylistAccessToken("raw-access-token");

        // No detach/delete escape hatch on client_reviews' own FK — the whole graph disposal must
        // never touch the salon/master bulk-decline seam.
        verify(bookingService, times(2)).cancelBooking(eq(clientId), any(), any());

        // Perf finding 1: survivorship for every remaining header is resolved in exactly ONE
        // round trip — never a per-appointment probe.
        verify(bookingRepository, times(1))
                .findAppointmentIdsWithSurvivingBookings(any());
    }

    @Test
    @DisplayName("perf finding 1: resolves appointment-header survivorship in ONE set-based query, "
            + "detaching survivors and deleting childless headers from the in-memory partition")
    void should_partitionAppointmentHeadersFromOneQuery_when_someSurviveAndSomeDoNot() {
        UUID clientId = UUID.randomUUID();
        User client = buildClient(clientId);

        Appointment childless1 = Appointment.builder().id(UUID.randomUUID()).client(client).build();
        Appointment childless2 = Appointment.builder().id(UUID.randomUUID()).client(client).build();
        Appointment surviving1 = Appointment.builder().id(UUID.randomUUID()).client(client).build();

        when(userRepository.findByIdForUpdate(clientId)).thenReturn(Optional.of(client));
        when(mediaRepository.findByUploaderId(clientId)).thenReturn(List.of());
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
        when(bookingService.findFutureConfirmedBookingIdsForClient(clientId)).thenReturn(List.of());
        when(appointmentRepository.findByClientId(clientId))
                .thenReturn(List.of(childless1, childless2, surviving1));
        when(bookingRepository.findAppointmentIdsWithSurvivingBookings(
                List.of(childless1.getId(), childless2.getId(), surviving1.getId())))
                .thenReturn(List.of(surviving1.getId()));
        when(bookingRepository.findByClientId(clientId)).thenReturn(List.of());

        service.deleteOwnAccount(clientId, "token");

        // Exactly ONE round trip for ALL three headers — never a per-appointment probe.
        verify(bookingRepository, times(1))
                .findAppointmentIdsWithSurvivingBookings(any());
        verify(appointmentRepository).deleteAllByIdInBatch(List.of(childless1.getId(), childless2.getId()));
        assertThat(surviving1.isClientDetached()).isTrue();
        assertThat(childless1.isClientDetached()).isFalse();
        assertThat(childless2.isClientDetached()).isFalse();
    }

    @Test
    @DisplayName("perf finding 1: never issues the survivorship query when no appointment headers remain")
    void should_skipSurvivorshipQuery_when_noAppointmentHeadersRemain() {
        UUID clientId = UUID.randomUUID();
        User client = buildClient(clientId);

        when(userRepository.findByIdForUpdate(clientId)).thenReturn(Optional.of(client));
        when(mediaRepository.findByUploaderId(clientId)).thenReturn(List.of());
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
        when(bookingService.findFutureConfirmedBookingIdsForClient(clientId)).thenReturn(List.of());
        when(appointmentRepository.findByClientId(clientId)).thenReturn(List.of());
        when(bookingRepository.findByClientId(clientId)).thenReturn(List.of());

        service.deleteOwnAccount(clientId, "token");

        // appointment_id IN () is wasted work the empty-collection guard must skip entirely.
        verify(bookingRepository, never()).findAppointmentIdsWithSurvivingBookings(any());
        verify(appointmentRepository, never()).deleteAllByIdInBatch(any());
        // No future bookings — the outbox cleanup's own empty-collection guard must also skip.
        verify(notificationOutboxRepository, never()).deleteByAggregateIdIn(any());
    }

    @Test
    @DisplayName("perf finding 2: throws BusinessException before any write when future booking count "
            + "exceeds the cap")
    void should_throwBusinessBeforeAnyWrite_when_futureBookingCountExceedsCap() {
        UUID clientId = UUID.randomUUID();
        User client = buildClient(clientId);
        List<UUID> tooManyFutureBookings = Stream
                .generate(UUID::randomUUID)
                .limit(ClientAccountDeletionService.MAX_FUTURE_BOOKINGS_PER_SELF_DELETE + 1)
                .toList();

        when(userRepository.findByIdForUpdate(clientId)).thenReturn(Optional.of(client));
        when(mediaRepository.findByUploaderId(clientId)).thenReturn(List.of());
        when(bookingService.findFutureConfirmedBookingIdsForClient(clientId)).thenReturn(tooManyFutureBookings);

        assertThatThrownBy(() -> service.deleteOwnAccount(clientId, "token"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining(String.valueOf(tooManyFutureBookings.size()))
                .hasMessageContaining(String.valueOf(ClientAccountDeletionService.MAX_FUTURE_BOOKINGS_PER_SELF_DELETE));

        // No cancellation, no deletion, nothing detached — the cap fails BEFORE any write.
        verify(bookingService, never()).cancelBooking(any(), any(), any());
        verify(bookingRepository, never()).deleteAllByIdInBatch(any());
        verify(userRepository, never()).deleteAllByIdInBatch(any());
        verifyNoInteractions(appointmentRepository, clientReviewRepository, accountBlobPurgeRegistrar, notificationOutboxRepository);
    }

    @Test
    @DisplayName("perf finding 2: succeeds when future booking count is exactly at the cap boundary")
    void should_succeed_when_futureBookingCountEqualsCapExactly() {
        UUID clientId = UUID.randomUUID();
        User client = buildClient(clientId);
        List<UUID> exactlyAtCap = Stream
                .generate(UUID::randomUUID)
                .limit(ClientAccountDeletionService.MAX_FUTURE_BOOKINGS_PER_SELF_DELETE)
                .toList();

        when(userRepository.findByIdForUpdate(clientId)).thenReturn(Optional.of(client));
        when(mediaRepository.findByUploaderId(clientId)).thenReturn(List.of());
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
        when(bookingService.findFutureConfirmedBookingIdsForClient(clientId)).thenReturn(exactlyAtCap);
        when(appointmentRepository.findByClientId(clientId)).thenReturn(List.of());
        when(bookingRepository.findByClientId(clientId)).thenReturn(List.of());

        service.deleteOwnAccount(clientId, "token");

        verify(bookingService, times(exactlyAtCap.size())).cancelBooking(eq(clientId), any(), any());
        verify(notificationOutboxRepository).deleteByAggregateIdIn(exactlyAtCap);
        verify(bookingRepository).deleteAllByIdInBatch(exactlyAtCap);
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
        when(bookingService.findFutureConfirmedBookingIdsForClient(clientId)).thenReturn(List.of());
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
                accountBlobPurgeRegistrar, authService, tokensValidAfterCache, userProfileCacheEvictor,
                notificationOutboxRepository);
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
