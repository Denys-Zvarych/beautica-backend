package com.beautica.review.invite.service;

import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.event.GuestBookingCompletedEvent;
import com.beautica.booking.event.GuestVisitCompletedEvent;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.BookingSmsProperties;
import com.beautica.config.PhoneHashPepperConfig;
import com.beautica.review.invite.config.ReviewInviteProperties;
import com.beautica.review.invite.entity.ReviewInviteSkipReason;
import com.beautica.review.invite.repository.ReviewInviteRepository;
import com.beautica.review.invite.repository.ReviewInviteVisitView;
import com.beautica.review.invite.service.ReviewInviteEligibility.Verdict;
import com.beautica.review.invite.support.ReviewInviteTestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewInviteScheduler — phase 400 D1 invite creation")
class ReviewInviteSchedulerTest {

    private static final Instant COMPLETED_AT = Instant.parse("2026-06-10T09:00:00Z"); // 12:00 Kyiv
    private static final Instant NOW = COMPLETED_AT.plusSeconds(1);
    private static final String PHONE = "+380501234567";
    private static final PhoneHasher PHONE_HASHER =
            new PhoneHasher(new PhoneHashPepperConfig("unit-test-phone-hash-pepper-min-32-chars!"));

    @Mock
    private ReviewInviteRepository repository;
    @Mock
    private ReviewInviteEligibility eligibility;
    @Mock
    private PlatformTransactionManager transactionManager;

    private ReviewInviteScheduler scheduler;
    private final UUID appointmentId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        scheduler = scheduler(ReviewInviteTestProperties.enabled(), true);
    }

    private ReviewInviteScheduler scheduler(ReviewInviteProperties props, boolean smsEnabled) {
        BookingSmsProperties sms = new BookingSmsProperties();
        sms.getSms().setEnabled(smsEnabled);
        return new ReviewInviteScheduler(repository, eligibility, new QuietHoursCalculator(props), props, sms,
                Clock.fixed(NOW, ZoneOffset.UTC), transactionManager);
    }

    @Test
    @DisplayName("invite flag off: returns before opening a transaction or reading the visit (both events)")
    void should_openNoTransaction_when_invitesDisabled() {
        ReviewInviteScheduler off = scheduler(ReviewInviteTestProperties.disabled(), true);

        off.onGuestVisitCompleted(new GuestVisitCompletedEvent(appointmentId, COMPLETED_AT));
        off.onGuestBookingCompleted(new GuestBookingCompletedEvent(bookingId, COMPLETED_AT));

        verifyNoInteractions(transactionManager, repository, eligibility);
    }

    @Test
    @DisplayName("SMS flag off: returns before opening a transaction or reading the visit (both events)")
    void should_openNoTransaction_when_smsDisabled() {
        ReviewInviteScheduler off = scheduler(ReviewInviteTestProperties.enabled(), false);

        off.onGuestVisitCompleted(new GuestVisitCompletedEvent(appointmentId, COMPLETED_AT));
        off.onGuestBookingCompleted(new GuestBookingCompletedEvent(bookingId, COMPLETED_AT));

        verifyNoInteractions(transactionManager, repository, eligibility);
    }

    @Test
    @DisplayName("an eligible LINK visit gets ONE SCHEDULED row, send_after = completedAt + 2h, insert-if-absent")
    void should_insertScheduled_when_eligibleLinkVisit() {
        stubTransaction();
        stubVisit(BookingSource.LINK, false);
        when(eligibility.evaluate(any())).thenReturn(new Verdict(PHONE_HASHER.hash(PHONE), null));

        scheduler.onGuestVisitCompleted(new GuestVisitCompletedEvent(appointmentId, COMPLETED_AT));

        verify(repository).insertIfAbsent(any(), eq(appointmentId), eq(PHONE_HASHER.hash(PHONE).hash()),
                eq("SCHEDULED"), eq(COMPLETED_AT.plusSeconds(7_200)), isNull(), eq(NOW));
    }

    @Test
    @DisplayName("an opted-out LINK visit gets a row SKIPPED (OPTED_OUT) from birth")
    void should_insertSkipped_when_gateFailsAfterHashing() {
        stubTransaction();
        stubVisit(BookingSource.LINK, false);
        when(eligibility.evaluate(any()))
                .thenReturn(new Verdict(PHONE_HASHER.hash(PHONE), ReviewInviteSkipReason.OPTED_OUT));

        scheduler.onGuestVisitCompleted(new GuestVisitCompletedEvent(appointmentId, COMPLETED_AT));

        verify(repository).insertIfAbsent(any(), eq(appointmentId), any(), eq("SKIPPED"), any(),
                eq("OPTED_OUT"), eq(NOW));
    }

    @Test
    @DisplayName("a flag off (no phone hashed) → no row at all")
    void should_insertNothing_when_flagOff() {
        stubTransaction();
        stubVisit(BookingSource.LINK, false);
        when(eligibility.evaluate(any())).thenReturn(new Verdict(null, ReviewInviteSkipReason.INVITES_DISABLED));

        scheduler.onGuestVisitCompleted(new GuestVisitCompletedEvent(appointmentId, COMPLETED_AT));

        verify(repository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("STAFF and APP visits never create a row and never reach the eligibility gates")
    void should_insertNothing_when_staffOrAppVisit() {
        stubTransaction();
        stubVisit(BookingSource.STAFF, false);
        scheduler.onGuestVisitCompleted(new GuestVisitCompletedEvent(appointmentId, COMPLETED_AT));
        stubVisit(BookingSource.APP, true);

        scheduler.onGuestVisitCompleted(new GuestVisitCompletedEvent(appointmentId, COMPLETED_AT));

        verifyNoInteractions(eligibility);
        verify(repository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("a DB failure is swallowed — an afterCommit listener must never fail the committed completion")
    void should_notThrow_when_repositoryFails() {
        stubTransaction();
        when(repository.findVisitViews(List.of(appointmentId))).thenThrow(new QueryTimeoutException("slow"));

        assertThatCode(() -> scheduler.onGuestVisitCompleted(
                new GuestVisitCompletedEvent(appointmentId, COMPLETED_AT))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("D7 — an eligible single LINK booking gets ONE booking-keyed SCHEDULED row via the booking insert")
    void should_insertBookingKeyedScheduled_when_eligibleLinkBooking() {
        stubTransaction();
        stubBooking(BookingSource.LINK, false);
        when(eligibility.evaluate(any())).thenReturn(new Verdict(PHONE_HASHER.hash(PHONE), null));

        scheduler.onGuestBookingCompleted(new GuestBookingCompletedEvent(bookingId, COMPLETED_AT));

        verify(repository).insertIfAbsentForBooking(any(), eq(bookingId), eq(PHONE_HASHER.hash(PHONE).hash()),
                eq("SCHEDULED"), eq(COMPLETED_AT.plusSeconds(7_200)), isNull(), eq(NOW));
        verify(repository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any());
        verify(repository, never()).findVisitViews(any());
    }

    @Test
    @DisplayName("D7 — a gate failing for a single LINK booking → booking-keyed row SKIPPED with the reason")
    void should_insertBookingKeyedSkipped_when_gateFailsForLinkBooking() {
        stubTransaction();
        stubBooking(BookingSource.LINK, false);
        when(eligibility.evaluate(any()))
                .thenReturn(new Verdict(PHONE_HASHER.hash(PHONE), ReviewInviteSkipReason.POLICY_VERSION));

        scheduler.onGuestBookingCompleted(new GuestBookingCompletedEvent(bookingId, COMPLETED_AT));

        verify(repository).insertIfAbsentForBooking(any(), eq(bookingId), any(), eq("SKIPPED"), any(),
                eq("POLICY_VERSION"), eq(NOW));
    }

    @Test
    @DisplayName("D7 — an APP / STAFF single booking never creates a row")
    void should_insertNothing_when_bookingNotGuestLink() {
        stubTransaction();
        stubBooking(BookingSource.STAFF, false);
        scheduler.onGuestBookingCompleted(new GuestBookingCompletedEvent(bookingId, COMPLETED_AT));
        stubBooking(BookingSource.APP, true);

        scheduler.onGuestBookingCompleted(new GuestBookingCompletedEvent(bookingId, COMPLETED_AT));

        verifyNoInteractions(eligibility);
        verify(repository, never()).insertIfAbsentForBooking(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("D7 — a booking that is not a standalone LINK booking (no view row) → nothing, no throw")
    void should_insertNothing_when_bookingViewMissing() {
        stubTransaction();
        when(repository.findBookingViews(List.of(bookingId))).thenReturn(List.of());

        scheduler.onGuestBookingCompleted(new GuestBookingCompletedEvent(bookingId, COMPLETED_AT));

        verifyNoInteractions(eligibility);
        verify(repository, never()).insertIfAbsentForBooking(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("D7 — a DB failure on the booking path is swallowed too")
    void should_notThrow_when_bookingRepositoryFails() {
        stubTransaction();
        when(repository.findBookingViews(List.of(bookingId))).thenThrow(new QueryTimeoutException("slow"));

        assertThatCode(() -> scheduler.onGuestBookingCompleted(
                new GuestBookingCompletedEvent(bookingId, COMPLETED_AT))).doesNotThrowAnyException();
    }

    private void stubBooking(BookingSource source, boolean hasClient) {
        ReviewInviteVisitView view = new ReviewInviteVisitView() {
            public UUID getAppointmentId() { return null; }
            public UUID getBookingId() { return bookingId; }
            public String getBookingSource() { return source.name(); }
            public boolean getHasClient() { return hasClient; }
            public String getGuestPhone() { return PHONE; }
            public boolean getAnonymised() { return false; }
            public String getPolicyVersion() { return ReviewInviteTestProperties.MIN_POLICY_VERSION; }
            public boolean getHasUnreviewedCompleted() { return true; }
        };
        when(repository.findBookingViews(List.of(bookingId))).thenReturn(List.of(view));
    }

    private void stubTransaction() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    private void stubVisit(BookingSource source, boolean hasClient) {
        ReviewInviteVisitView view = new ReviewInviteVisitView() {
            public UUID getAppointmentId() { return appointmentId; }
            public UUID getBookingId() { return null; }
            public String getBookingSource() { return source.name(); }
            public boolean getHasClient() { return hasClient; }
            public String getGuestPhone() { return PHONE; }
            public boolean getAnonymised() { return false; }
            public String getPolicyVersion() { return ReviewInviteTestProperties.MIN_POLICY_VERSION; }
            public boolean getHasUnreviewedCompleted() { return true; }
        };
        when(repository.findVisitViews(List.of(appointmentId))).thenReturn(List.of(view));
    }
}
