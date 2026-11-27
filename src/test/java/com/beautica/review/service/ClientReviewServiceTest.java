package com.beautica.review.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ConflictException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.entity.Master;
import com.beautica.review.config.ClientRatingProperties;
import com.beautica.review.dto.CreateClientReviewRequest;
import com.beautica.review.entity.ClientReview;
import com.beautica.review.event.ClientReviewCreatedEvent;
import com.beautica.review.repository.ClientReviewRepository;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B546 (REV-D04, REV-38, REV-41) / B547 (REV-42) — {@link ClientReviewService#create}: who-is-the-subject rules for
 * registered, link-guest and walk-in bookings, the guest feature flag, and the one-rating-per-booking 409.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ClientReviewService — create (B546 guest / walk-in subjects, B547 one rating per booking)")
class ClientReviewServiceTest {

    private static final String PHONE = "+380671234567";
    private static final UUID ACTOR = UUID.randomUUID();

    @Mock private ClientReviewRepository clientReviewRepository;
    @Mock private BookingRepository bookingRepository;
    @Mock private AuthorizationService authz;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ClientReviewService service(boolean guestRatingEnabled) {
        return new ClientReviewService(clientReviewRepository, bookingRepository, authz, eventPublisher,
                new ClientRatingEligibility(new ClientRatingProperties(guestRatingEnabled)));
    }

    private static Booking booking(BookingSource source, User client, String guestPhone) {
        Booking booking = Booking.builder()
                .id(UUID.randomUUID())
                .bookingSource(source)
                .status(BookingStatus.COMPLETED)
                .client(client)
                .guestPhone(guestPhone)
                .master(mock(Master.class))
                .build();
        return booking;
    }

    private static User account() {
        User user = mock(User.class);
        org.mockito.Mockito.lenient().when(user.getId()).thenReturn(UUID.randomUUID());
        return user;
    }

    private void stubLoad(Booking booking) {
        when(bookingRepository.findByIdWithFullGraph(booking.getId())).thenReturn(Optional.of(booking));
    }

    private void stubSave() {
        when(clientReviewRepository.saveAndFlush(any(ClientReview.class))).thenAnswer(inv -> {
            ClientReview review = inv.getArgument(0);
            ReflectionTestUtils.setField(review, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(review, "createdAt", Instant.parse("2026-11-27T10:00:00Z"));
            return review;
        });
    }

    private ClientReview savedReview() {
        ArgumentCaptor<ClientReview> captor = ArgumentCaptor.forClass(ClientReview.class);
        verify(clientReviewRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private static CreateClientReviewRequest request(Booking booking) {
        return new CreateClientReviewRequest(booking.getId(), 4, "Пунктуальна");
    }

    @Test
    @DisplayName("a LINK guest with a phone is saved keyed to the phone, with no account and no aggregate event")
    void should_saveWithGuestPhone_when_linkGuestHasPhone() {
        Booking booking = booking(BookingSource.LINK, null, PHONE);
        stubLoad(booking);
        when(bookingRepository.existsUnattachedGuestBooking(booking.getId(), PHONE)).thenReturn(true);
        stubSave();

        service(true).create(ACTOR, request(booking));

        ClientReview saved = savedReview();
        assertThat(saved.getSubjectGuestPhone()).isEqualTo(PHONE);
        assertThat(saved.getSubjectClient()).isNull();
        assertThat(saved.getComment()).isEqualTo("Пунктуальна");
        verify(bookingRepository).acquireWalkInPhoneLock(PHONE);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("a STAFF walk-in with a phone is saved keyed to the phone")
    void should_saveWithGuestPhone_when_staffWalkInHasPhone() {
        Booking booking = booking(BookingSource.STAFF, null, PHONE);
        stubLoad(booking);
        when(bookingRepository.existsUnattachedGuestBooking(booking.getId(), PHONE)).thenReturn(true);
        stubSave();

        service(true).create(ACTOR, request(booking));

        assertThat(savedReview().getSubjectGuestPhone()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("an attached STAFF walk-in (account set) is saved account-keyed and recalculates the aggregate (REV-D04 reversal)")
    void should_saveAccountKeyed_when_staffWalkInIsAttached() {
        User client = account();
        Booking booking = booking(BookingSource.STAFF, client, null);
        stubLoad(booking);
        stubSave();

        service(false).create(ACTOR, request(booking));

        ClientReview saved = savedReview();
        assertThat(saved.getSubjectClient()).isSameAs(client);
        assertThat(saved.getSubjectGuestPhone()).isNull();
        verify(eventPublisher).publishEvent(new ClientReviewCreatedEvent(client.getId()));
    }

    @Test
    @DisplayName("a STAFF walk-in with no phone is 400 «no client identity» (D2)")
    void should_reject400_when_staffWalkInHasNoPhone() {
        Booking booking = booking(BookingSource.STAFF, null, null);
        stubLoad(booking);

        assertThatThrownBy(() -> service(true).create(ACTOR, request(booking)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage(ClientReviewService.NO_IDENTITY_MESSAGE);
        verify(clientReviewRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("an anonymised guest booking (GST-51) is 400, even if a phone value were still present")
    void should_reject400_when_guestPiiAnonymised() {
        Booking booking = booking(BookingSource.LINK, null, PHONE);
        ReflectionTestUtils.setField(booking, "guestPiiAnonymisedAt", Instant.parse("2026-11-01T00:00:00Z"));
        stubLoad(booking);

        assertThatThrownBy(() -> service(true).create(ACTOR, request(booking)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage(ClientReviewService.NO_IDENTITY_MESSAGE);
        verify(clientReviewRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("flag off: a guest booking keeps today's 400 and message (REV-41)")
    void should_reject400WithTodaysMessage_when_flagOffAndGuest() {
        Booking booking = booking(BookingSource.LINK, null, PHONE);
        stubLoad(booking);

        assertThatThrownBy(() -> service(false).create(ACTOR, request(booking)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessage("Cannot review a guest booking — no client account exists");
        verify(bookingRepository, never()).acquireWalkInPhoneLock(any());
    }

    @Test
    @DisplayName("a second rating of the same booking is 409 (REV-42, first writer wins)")
    void should_reject409_when_reviewAlreadyExists() {
        Booking booking = booking(BookingSource.APP, account(), null);
        stubLoad(booking);
        when(clientReviewRepository.existsByBookingId(booking.getId())).thenReturn(true);

        assertThatThrownBy(() -> service(true).create(ACTOR, request(booking)))
                .isInstanceOf(ConflictException.class)
                .hasMessage(ClientReviewService.DUPLICATE_MESSAGE);
        verify(clientReviewRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a guest booking attached by a concurrent phone verification (seen under the phone lock) is 409, never a stranded phone-keyed row")
    void should_reject409_when_guestBookingAttachedUnderLock() {
        Booking booking = booking(BookingSource.LINK, null, PHONE);
        stubLoad(booking);
        when(bookingRepository.existsUnattachedGuestBooking(booking.getId(), PHONE)).thenReturn(false);

        assertThatThrownBy(() -> service(true).create(ACTOR, request(booking)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("This booking was just linked to a client account. Try again");
        verify(clientReviewRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("a guest booking anonymised by the GST-51 sweep while the rating waited on the lock is the NO_IDENTITY 400, not the attach 409")
    void should_reject400NoIdentity_when_guestBookingAnonymisedUnderLock() {
        Booking booking = booking(BookingSource.LINK, null, PHONE);
        stubLoad(booking);
        when(bookingRepository.existsUnattachedGuestBooking(booking.getId(), PHONE)).thenReturn(false);
        when(bookingRepository.isGuestPiiAnonymised(booking.getId())).thenReturn(true);

        assertThatThrownBy(() -> service(true).create(ACTOR, request(booking)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST))
                .isNotInstanceOf(ConflictException.class)
                .hasMessage(ClientReviewService.NO_IDENTITY_MESSAGE);
        verify(clientReviewRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("the uq_client_reviews_booking race backstop (a concurrent first write) is 409 DUPLICATE")
    void should_reject409_when_saveHitsBookingUniqueConstraint() {
        Booking booking = booking(BookingSource.APP, account(), null);
        stubLoad(booking);
        when(clientReviewRepository.saveAndFlush(any(ClientReview.class))).thenThrow(violation(
                ClientReviewService.DUPLICATE_BOOKING_CONSTRAINT, UNIQUE_VIOLATION));

        assertThatThrownBy(() -> service(true).create(ACTOR, request(booking)))
                .isInstanceOf(ConflictException.class)
                .hasMessage(ClientReviewService.DUPLICATE_MESSAGE);
    }

    @Test
    @DisplayName("any other integrity violation (e.g. a CHECK) propagates unchanged, never mapped to the duplicate 409")
    void should_propagate_when_saveHitsAnotherConstraint() {
        Booking booking = booking(BookingSource.APP, account(), null);
        stubLoad(booking);
        DataIntegrityViolationException other = violation("chk_client_reviews_one_subject", CHECK_VIOLATION);
        when(clientReviewRepository.saveAndFlush(any(ClientReview.class))).thenThrow(other);

        assertThatThrownBy(() -> service(true).create(ACTOR, request(booking))).isSameAs(other);
    }

    private static final String UNIQUE_VIOLATION = "23505";
    private static final String CHECK_VIOLATION = "23514";

    private static DataIntegrityViolationException violation(String constraintName, String sqlState) {
        return new DataIntegrityViolationException("could not execute statement",
                new org.hibernate.exception.ConstraintViolationException("violation",
                        new java.sql.SQLException("violation", sqlState), constraintName));
    }
}
