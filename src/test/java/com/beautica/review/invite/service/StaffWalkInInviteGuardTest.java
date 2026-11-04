package com.beautica.review.invite.service;

import com.beautica.booking.enums.BookingSource;
import com.beautica.common.security.PhoneHasher;
import com.beautica.common.security.PhoneHasher.HashedPhone;
import com.beautica.config.PhoneHashPepperConfig;
import com.beautica.review.invite.config.ReviewInviteProperties;
import com.beautica.review.invite.entity.ReviewInviteSkipReason;
import com.beautica.review.invite.repository.ReviewInviteRepository;
import com.beautica.review.invite.support.ReviewInviteTestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 434 audit-fix — {@link StaffWalkInInviteGuard} in isolation: the STAFF-only scope, the four gates in their
 * documented order (each later gate runs only when every earlier one passed), and the exact query arguments.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StaffWalkInInviteGuard — phase 434 STAFF walk-in abuse gates")
class StaffWalkInInviteGuardTest {

    private static final String PEPPER = "unit-test-phone-hash-pepper-min-32-chars!";
    private static final String PHONE = "+380501234567";
    private static final Instant NOW = Instant.parse("2026-06-10T09:00:00Z");
    private static final long PHONE_WINDOW_SECONDS = ReviewInviteProperties.DEFAULT_STAFF_PHONE_WINDOW.toSeconds();
    private static final long PROVIDER_WINDOW_SECONDS = StaffWalkInInviteGuard.PROVIDER_CAP_WINDOW.toSeconds();
    private static final long GLOBAL_CAP = ReviewInviteProperties.DEFAULT_STAFF_PHONE_GLOBAL_CAP;
    private static final long DAILY_CAP = ReviewInviteProperties.DEFAULT_STAFF_PROVIDER_DAILY_CAP;

    private final PhoneHasher phoneHasher = new PhoneHasher(new PhoneHashPepperConfig(PEPPER));

    @Mock
    private ReviewInviteRepository reviewInviteRepository;

    private StaffWalkInInviteGuard guard;
    private HashedPhone phone;
    private UUID appointmentId;

    @BeforeEach
    void setUp() {
        guard = new StaffWalkInInviteGuard(reviewInviteRepository, phoneHasher, ReviewInviteTestProperties.enabled(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        phone = phoneHasher.hash(PHONE);
        appointmentId = UUID.randomUUID();
    }

    static Stream<VisitSnapshot> nonStaffWalkInVisits() {
        return Stream.of(
                new VisitSnapshot(UUID.randomUUID(), BookingSource.LINK, false, PHONE, false, "2026-10-20", true),
                new VisitSnapshot(UUID.randomUUID(), BookingSource.STAFF, true, PHONE, false, null, true),
                new VisitSnapshot(UUID.randomUUID(), BookingSource.APP, true, PHONE, false, null, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonStaffWalkInVisits")
    @DisplayName("a visit that is not a STAFF walk-in (LINK guest, STAFF with an account, APP) → empty, no query runs")
    void should_returnEmptyWithoutQuerying_when_visitIsNotStaffWalkIn(VisitSnapshot visit) {
        Optional<ReviewInviteSkipReason> reason = guard.check(visit, phone);

        assertThat(reason).isEmpty();
        verifyNoInteractions(reviewInviteRepository);
    }

    @Test
    @DisplayName("gate 1 — the walk-in phone matches a provider account phone in another format → PROVIDER_OWN_PHONE; "
            + "no repeat / global-cap / provider-cap query runs")
    void should_skipProviderOwnPhone_when_walkInPhoneIsProviderAccountPhone() {
        when(reviewInviteRepository.findProviderAccountPhones(appointmentId, null))
                .thenReturn(List.of("+380509876543", "050 123 45 67"));

        Optional<ReviewInviteSkipReason> reason = guard.check(staffWalkIn(), phone);

        assertThat(reason).contains(ReviewInviteSkipReason.PROVIDER_OWN_PHONE);
        verify(reviewInviteRepository, never())
                .existsPriorInviteForPhoneAndProvider(any(), any(), any(), any(), anyLong());
        verify(reviewInviteRepository, never()).countPriorStaffInvitesForPhone(any(), any(), any(), any(), anyLong());
        verify(reviewInviteRepository, never()).countRecentStaffInvitesForProvider(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("gate 2 — own-phone clear, phone already invited for this provider in the window → REPEAT_PHONE; "
            + "neither cap query runs")
    void should_skipRepeatPhone_when_priorInviteForSameProviderExists() {
        when(reviewInviteRepository.findProviderAccountPhones(appointmentId, null)).thenReturn(List.of());
        when(reviewInviteRepository.existsPriorInviteForPhoneAndProvider(phone.hash(), appointmentId, null, NOW,
                PHONE_WINDOW_SECONDS)).thenReturn(true);

        Optional<ReviewInviteSkipReason> reason = guard.check(staffWalkIn(), phone);

        assertThat(reason).contains(ReviewInviteSkipReason.REPEAT_PHONE);
        verify(reviewInviteRepository, never()).countPriorStaffInvitesForPhone(any(), any(), any(), any(), anyLong());
        verify(reviewInviteRepository, never()).countRecentStaffInvitesForProvider(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("gate 3 — gates 1–2 clear, phone at staff-phone-global-cap (3) STAFF invites in the window → "
            + "PHONE_GLOBAL_CAP; the provider-cap query never runs")
    void should_skipPhoneGlobalCap_when_phoneAtGlobalCap() {
        when(reviewInviteRepository.findProviderAccountPhones(appointmentId, null)).thenReturn(List.of());
        when(reviewInviteRepository.existsPriorInviteForPhoneAndProvider(phone.hash(), appointmentId, null, NOW,
                PHONE_WINDOW_SECONDS)).thenReturn(false);
        when(reviewInviteRepository.countPriorStaffInvitesForPhone(phone.hash(), appointmentId, null, NOW,
                PHONE_WINDOW_SECONDS)).thenReturn(GLOBAL_CAP);

        Optional<ReviewInviteSkipReason> reason = guard.check(staffWalkIn(), phone);

        assertThat(reason).contains(ReviewInviteSkipReason.PHONE_GLOBAL_CAP);
        verify(reviewInviteRepository, never()).countRecentStaffInvitesForProvider(any(), any(), any(), anyLong());
    }

    @Test
    @DisplayName("gate 4 — gates 1–3 clear (global count one below cap), provider at staff-provider-daily-cap (50) in "
            + "the last 24 h → PROVIDER_DAILY_CAP; all four gates queried in documented order")
    void should_skipProviderDailyCap_when_providerAtDailyCap() {
        stubAllGatesClearExceptProviderCount(DAILY_CAP);

        Optional<ReviewInviteSkipReason> reason = guard.check(staffWalkIn(), phone);

        assertThat(reason).contains(ReviewInviteSkipReason.PROVIDER_DAILY_CAP);
        verifyGateOrder();
    }

    @Test
    @DisplayName("every gate clear (provider one below daily cap, an unparseable provider phone ignored) → empty; "
            + "each gate queried exactly once, in order")
    void should_returnEmpty_when_everyGateClear() {
        stubAllGatesClearExceptProviderCount(DAILY_CAP - 1);

        Optional<ReviewInviteSkipReason> reason = guard.check(staffWalkIn(), phone);

        assertThat(reason).isEmpty();
        verifyGateOrder();
    }

    @Test
    @DisplayName("a booking-keyed STAFF walk-in passes the booking id (appointment id null) to every gate query")
    void should_queryByBookingId_when_walkInIsBookingKeyed() {
        UUID bookingId = UUID.randomUUID();
        VisitSnapshot walkIn = new VisitSnapshot(null, bookingId, BookingSource.STAFF, false, PHONE, false, null, true);
        when(reviewInviteRepository.findProviderAccountPhones(null, bookingId)).thenReturn(List.of());
        when(reviewInviteRepository.existsPriorInviteForPhoneAndProvider(eq(phone.hash()), isNull(), eq(bookingId),
                eq(NOW), eq(PHONE_WINDOW_SECONDS))).thenReturn(false);
        when(reviewInviteRepository.countPriorStaffInvitesForPhone(eq(phone.hash()), isNull(), eq(bookingId), eq(NOW),
                eq(PHONE_WINDOW_SECONDS))).thenReturn(0L);
        when(reviewInviteRepository.countRecentStaffInvitesForProvider(isNull(), eq(bookingId), eq(NOW),
                eq(PROVIDER_WINDOW_SECONDS))).thenReturn(DAILY_CAP);

        Optional<ReviewInviteSkipReason> reason = guard.check(walkIn, phone);

        assertThat(reason).contains(ReviewInviteSkipReason.PROVIDER_DAILY_CAP);
    }

    private void stubAllGatesClearExceptProviderCount(long providerCount) {
        when(reviewInviteRepository.findProviderAccountPhones(appointmentId, null))
                .thenReturn(List.of("+380509876543", "not-a-phone"));
        when(reviewInviteRepository.existsPriorInviteForPhoneAndProvider(phone.hash(), appointmentId, null, NOW,
                PHONE_WINDOW_SECONDS)).thenReturn(false);
        when(reviewInviteRepository.countPriorStaffInvitesForPhone(phone.hash(), appointmentId, null, NOW,
                PHONE_WINDOW_SECONDS)).thenReturn(GLOBAL_CAP - 1);
        when(reviewInviteRepository.countRecentStaffInvitesForProvider(appointmentId, null, NOW,
                PROVIDER_WINDOW_SECONDS)).thenReturn(providerCount);
    }

    private void verifyGateOrder() {
        InOrder order = inOrder(reviewInviteRepository);
        order.verify(reviewInviteRepository).findProviderAccountPhones(appointmentId, null);
        order.verify(reviewInviteRepository).existsPriorInviteForPhoneAndProvider(phone.hash(), appointmentId, null,
                NOW, PHONE_WINDOW_SECONDS);
        order.verify(reviewInviteRepository).countPriorStaffInvitesForPhone(phone.hash(), appointmentId, null, NOW,
                PHONE_WINDOW_SECONDS);
        order.verify(reviewInviteRepository).countRecentStaffInvitesForProvider(appointmentId, null, NOW,
                PROVIDER_WINDOW_SECONDS);
        verifyNoMoreInteractions(reviewInviteRepository);
    }

    private VisitSnapshot staffWalkIn() {
        return new VisitSnapshot(appointmentId, BookingSource.STAFF, false, PHONE, false, null, true);
    }
}
