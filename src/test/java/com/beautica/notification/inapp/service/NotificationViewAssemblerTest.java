package com.beautica.notification.inapp.service;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.entity.Master;
import com.beautica.notification.inapp.dto.NotificationParams;
import com.beautica.notification.inapp.dto.NotificationResponse;
import com.beautica.notification.inapp.dto.NotificationTarget;
import com.beautica.notification.inapp.dto.TargetKind;
import com.beautica.notification.inapp.entity.InAppNotification;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.review.repository.ReviewRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceTypeSuggestionRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 495 (NTF-48) — read-side resolution of a {@code BOOKING_REMINDER} row for its client: the booking target
 * (same shape as a client's {@code BOOKING_RESCHEDULED} row) and the contract params, and the NTF-10 lost-access
 * fallback ({@code NONE}, no params). Repositories and {@link AuthorizationService} are mocked; the resolution code
 * under test is the real {@link NotificationViewAssembler}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("NotificationViewAssembler — BOOKING_REMINDER (phase 495)")
class NotificationViewAssemblerTest {

    private static final OffsetDateTime STARTS_AT = OffsetDateTime.parse("2026-11-15T10:30:00Z");

    @Mock private BookingRepository bookingRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private UserRepository userRepository;
    @Mock private ReviewRepository reviewRepository;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private ServiceTypeSuggestionRepository suggestionRepository;
    @Mock private AuthorizationService authorizationService;

    private NotificationViewAssembler assembler;
    private UUID clientId;
    private UUID bookingId;
    private UUID salonId;

    @BeforeEach
    void setUp() {
        assembler = new NotificationViewAssembler(bookingRepository, salonRepository, userRepository,
                reviewRepository, platformCategoryRepository, suggestionRepository, authorizationService,
                Clock.fixed(Instant.parse("2026-11-14T10:30:00Z"), ZoneOffset.UTC));
        clientId = UUID.randomUUID();
        bookingId = UUID.randomUUID();
        salonId = UUID.randomUUID();
    }

    @Test
    @DisplayName("client view: BOOKING target (bookingId, no appointmentId, salonId) + counterpart, service, "
            + "count 1, startsAt, salon and master names; every other param null")
    void should_returnBookingTargetAndParams_when_clientViewsReminder() {
        // Arrange
        Booking booking = salonBooking();
        when(bookingRepository.findAllByIdsWithGraph(List.of(bookingId))).thenReturn(List.of(booking));
        when(authorizationService.isViewAuthorized(eq(Role.CLIENT), eq(clientId), eq(clientId), any(), anyBoolean(),
                any())).thenReturn(true);

        // Act
        NotificationResponse view = assembler.assemble(List.of(reminderRow()), clientId, Role.CLIENT).get(0);

        // Assert
        assertThat(view.type()).isEqualTo(InAppNotificationType.BOOKING_REMINDER);
        assertThat(view.target()).isEqualTo(new NotificationTarget(TargetKind.BOOKING, bookingId, null, salonId));
        assertThat(view.params()).isEqualTo(new NotificationParams("Салон Бута", "Манікюр", 1,
                STARTS_AT.toInstant(), "Салон Бута", "Ірина Мельник", null, null, null, null));
    }

    @Test
    @DisplayName("client view of an independent master's booking: counterpart = master, salonName null")
    void should_nameMasterAsCounterpart_when_noSalon() {
        // Arrange
        Booking booking = bookingWith(null);
        when(bookingRepository.findAllByIdsWithGraph(List.of(bookingId))).thenReturn(List.of(booking));
        when(authorizationService.isViewAuthorized(eq(Role.CLIENT), eq(clientId), eq(clientId), any(), anyBoolean(),
                any())).thenReturn(true);

        // Act
        NotificationResponse view = assembler.assemble(List.of(reminderRow()), clientId, Role.CLIENT).get(0);

        // Assert
        assertThat(view.target().kind()).isEqualTo(TargetKind.BOOKING);
        assertThat(view.params().counterpartName()).isEqualTo("Ірина Мельник");
        assertThat(view.params().salonName()).isNull();
        assertThat(view.params().masterName()).isEqualTo("Ірина Мельник");
    }

    @Test
    @DisplayName("lost access (no longer the booking's client) → target NONE, no params (NTF-10)")
    void should_returnNoneWithoutParams_when_clientLostAccess() {
        // Arrange
        Booking booking = salonBooking();
        when(bookingRepository.findAllByIdsWithGraph(List.of(bookingId))).thenReturn(List.of(booking));
        when(authorizationService.isViewAuthorized(any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(false);

        // Act
        NotificationResponse view = assembler.assemble(List.of(reminderRow()), clientId, Role.CLIENT).get(0);

        // Assert
        assertThat(view.target()).isEqualTo(NotificationTarget.none(salonId));
        assertThat(view.params()).isNull();
    }

    @Test
    @DisplayName("booking gone → target NONE, no params (NTF-10)")
    void should_returnNoneWithoutParams_when_bookingGone() {
        // Arrange
        when(bookingRepository.findAllByIdsWithGraph(List.of(bookingId))).thenReturn(List.of());

        // Act
        NotificationResponse view = assembler.assemble(List.of(reminderRow()), clientId, Role.CLIENT).get(0);

        // Assert
        assertThat(view.target().kind()).isEqualTo(TargetKind.NONE);
        assertThat(view.params()).isNull();
    }

    private InAppNotification reminderRow() {
        return InAppNotification.builder()
                .id(UUID.randomUUID())
                .recipientUserId(clientId)
                .type(InAppNotificationType.BOOKING_REMINDER)
                .bookingId(bookingId)
                .salonId(salonId)
                .dedupKey("BOOKING_REMINDER:" + bookingId + ":" + STARTS_AT.toEpochSecond())
                .createdAt(Instant.parse("2026-11-14T10:00:00Z"))
                .build();
    }

    private Booking salonBooking() {
        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);
        when(salon.getName()).thenReturn("Салон Бута");
        return bookingWith(salon);
    }

    private Booking bookingWith(Salon salon) {
        User client = mock(User.class);
        when(client.getId()).thenReturn(clientId);
        User masterUser = mock(User.class);
        when(masterUser.getId()).thenReturn(UUID.randomUUID());
        Master master = mock(Master.class);
        when(master.getId()).thenReturn(UUID.randomUUID());
        when(master.getUser()).thenReturn(masterUser);
        when(master.isActive()).thenReturn(true);
        when(master.displayFirstName()).thenReturn("Ірина");
        when(master.displayLastName()).thenReturn("Мельник");

        Booking booking = mock(Booking.class, Answers.RETURNS_DEEP_STUBS);
        when(booking.getId()).thenReturn(bookingId);
        when(booking.getClient()).thenReturn(client);
        when(booking.getMaster()).thenReturn(master);
        when(booking.getSalon()).thenReturn(salon);
        when(booking.getStartsAt()).thenReturn(STARTS_AT);
        when(booking.getMasterService().getServiceDefinition().getName()).thenReturn("Манікюр");
        return booking;
    }
}
