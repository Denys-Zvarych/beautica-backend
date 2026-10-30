package com.beautica.review.invite.service;

import com.beautica.auth.TokenGenerator;
import com.beautica.booking.enums.BookingSource;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.PhoneHashPepperConfig;
import com.beautica.review.invite.entity.ReviewInvite;
import com.beautica.review.invite.entity.ReviewInviteSkipReason;
import com.beautica.review.invite.entity.ReviewInviteStatus;
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

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewInviteSendService — phase 400 D4/D6")
class ReviewInviteSendServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-10T09:00:00Z"); // 12:00 Kyiv
    private static final String PHONE = "+380501234567";
    private static final String TOKEN = "AAAAAAAAAAAAAAAAAAAAAA";
    private static final PhoneHasher PHONE_HASHER =
            new PhoneHasher(new PhoneHashPepperConfig("unit-test-phone-hash-pepper-min-32-chars!"));

    @Mock
    private ReviewInviteRepository repository;
    @Mock
    private ReviewInviteEligibility eligibility;
    @Mock
    private TokenGenerator tokenGenerator;

    private ReviewInviteSendService service;

    @BeforeEach
    void setUp() {
        service = new ReviewInviteSendService(repository, eligibility, tokenGenerator,
                new ReviewInviteSmsText(ReviewInviteTestProperties.enabled()), ReviewInviteTestProperties.enabled());
    }

    @Test
    @DisplayName("an eligible due invite is marked SENT with SHA-256(token), 14-day expiry, and the SMS is returned")
    void should_markSentAndReturnSms_when_eligible() {
        ReviewInvite invite = scheduled(NOW.minus(Duration.ofMinutes(5)));
        stubClaim(invite);
        when(eligibility.evaluateInBatch(any(), any())).thenReturn(new Verdict(PHONE_HASHER.hash(PHONE), null));
        when(tokenGenerator.generateToken(16)).thenReturn(TOKEN);

        List<PreparedInviteSms> prepared = service.claimAndPrepare(NOW);

        assertThat(prepared).singleElement().satisfies(sms -> {
            assertThat(sms.inviteId()).isEqualTo(invite.getId());
            assertThat(sms.phoneE164()).isEqualTo(PHONE);
            assertThat(sms.text()).isEqualTo("Оцініть ваш візит: beautica.app/r/" + TOKEN);
            assertThat(sms.toString()).doesNotContain(TOKEN).doesNotContain(PHONE);
        });
        assertThat(invite.getStatus()).isEqualTo(ReviewInviteStatus.SENT);
        assertThat(invite.getTokenHash()).isEqualTo(ReviewInviteTokenHasher.hash(TOKEN));
        assertThat(invite.getSentAt()).isEqualTo(NOW);
        assertThat(invite.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
    }

    @Test
    @DisplayName("a gate failing at send → SKIPPED with that reason, no token issued, nothing to send")
    void should_skipWithReason_when_gateFailsAtSend() {
        ReviewInvite invite = scheduled(NOW.minus(Duration.ofMinutes(5)));
        stubClaim(invite);
        when(eligibility.evaluateInBatch(any(), any())).thenReturn(
                new Verdict(PHONE_HASHER.hash(PHONE), ReviewInviteSkipReason.OPTED_OUT));

        List<PreparedInviteSms> prepared = service.claimAndPrepare(NOW);

        assertThat(prepared).isEmpty();
        assertThat(invite.getStatus()).isEqualTo(ReviewInviteStatus.SKIPPED);
        assertThat(invite.getSkipReason()).isEqualTo(ReviewInviteSkipReason.OPTED_OUT);
        assertThat(invite.getTokenHash()).isNull();
        verify(tokenGenerator, never()).generateToken(16);
    }

    @Test
    @DisplayName("unsent more than 72 h after send_after → SKIPPED (STALE) before any gate is evaluated")
    void should_skipStale_when_overdueBeyondStaleAfter() {
        ReviewInvite invite = scheduled(NOW.minus(Duration.ofHours(72)).minusSeconds(1));
        stubClaim(invite);

        List<PreparedInviteSms> prepared = service.claimAndPrepare(NOW);

        assertThat(prepared).isEmpty();
        assertThat(invite.getSkipReason()).isEqualTo(ReviewInviteSkipReason.STALE);
        verify(eligibility, never()).evaluateInBatch(any(), any());
    }

    @Test
    @DisplayName("exactly 72 h overdue is not yet stale")
    void should_notBeStale_when_exactlyAtStaleBoundary() {
        ReviewInvite invite = scheduled(NOW.minus(Duration.ofHours(72)));
        stubClaim(invite);
        when(eligibility.evaluateInBatch(any(), any())).thenReturn(new Verdict(PHONE_HASHER.hash(PHONE), null));
        when(tokenGenerator.generateToken(16)).thenReturn(TOKEN);

        List<PreparedInviteSms> prepared = service.claimAndPrepare(NOW);

        assertThat(prepared).hasSize(1);
    }

    @Test
    @DisplayName("nothing due → no visit lookup")
    void should_returnEmpty_when_nothingDue() {
        when(repository.claimDue(NOW, 50)).thenReturn(List.of());

        List<PreparedInviteSms> prepared = service.claimAndPrepare(NOW);

        assertThat(prepared).isEmpty();
        verify(repository, never()).findVisitViews(any());
    }

    @Test
    @DisplayName("a failed send voids the token and re-queues; the third failure is FAILED")
    void should_requeueThenFail_when_sendFailsRepeatedly() {
        ReviewInvite invite = scheduled(NOW.minus(Duration.ofMinutes(5)));
        when(repository.findById(invite.getId())).thenReturn(Optional.of(invite));

        for (int attempt = 1; attempt <= 3; attempt++) {
            invite.markSent(ReviewInviteTokenHasher.hash(TOKEN), NOW, NOW.plus(Duration.ofDays(14)));
            service.recordFailedSend(invite.getId());
            assertThat(invite.getAttempts()).isEqualTo((short) attempt);
            assertThat(invite.getTokenHash()).isNull();
            assertThat(invite.getSentAt()).isNull();
            assertThat(invite.getExpiresAt()).isNull();
        }

        assertThat(invite.getStatus()).isEqualTo(ReviewInviteStatus.FAILED);
    }

    @Test
    @DisplayName("a failure report for an invite that is no longer SENT changes nothing")
    void should_ignoreFailure_when_inviteNotSent() {
        ReviewInvite invite = scheduled(NOW);
        when(repository.findById(invite.getId())).thenReturn(Optional.of(invite));

        service.recordFailedSend(invite.getId());

        assertThat(invite.getStatus()).isEqualTo(ReviewInviteStatus.SCHEDULED);
        assertThat(invite.getAttempts()).isZero();
    }

    @Test
    @DisplayName("the expiry sweep is one bounded statement (batch-size)")
    void should_expireOneBoundedBatch_when_sweeping() {
        when(repository.expireSent(NOW, 50)).thenReturn(7);

        int expired = service.expireSent(NOW);

        assertThat(expired).isEqualTo(7);
    }

    @Test
    @DisplayName("D7 — a booking-keyed invite is looked up via findBookingViews only and sent")
    void should_useBookingLookup_when_inviteBookingKeyed() {
        ReviewInvite invite = ReviewInvite.scheduleForBooking(UUID.randomUUID(), PHONE_HASHER.hash(PHONE),
                NOW.minus(Duration.ofMinutes(5)), NOW);
        when(repository.claimDue(NOW, 50)).thenReturn(List.of(invite));
        when(repository.findBookingViews(List.of(invite.getBookingId())))
                .thenReturn(List.of(bookingView(invite.getBookingId())));
        when(eligibility.evaluateInBatch(any(), any())).thenReturn(new Verdict(PHONE_HASHER.hash(PHONE), null));
        when(tokenGenerator.generateToken(16)).thenReturn(TOKEN);

        List<PreparedInviteSms> prepared = service.claimAndPrepare(NOW);

        assertThat(prepared).singleElement().satisfies(sms -> assertThat(sms.inviteId()).isEqualTo(invite.getId()));
        assertThat(invite.getStatus()).isEqualTo(ReviewInviteStatus.SENT);
        verify(repository, never()).findVisitViews(any());
    }

    @Test
    @DisplayName("D7 — a mixed batch issues exactly one query per subject kind and sends both")
    void should_issueOneQueryPerSubjectKind_when_batchMixesSubjects() {
        ReviewInvite visitInvite = scheduled(NOW.minus(Duration.ofMinutes(5)));
        ReviewInvite bookingInvite1 = ReviewInvite.scheduleForBooking(UUID.randomUUID(), PHONE_HASHER.hash(PHONE),
                NOW.minus(Duration.ofMinutes(5)), NOW);
        ReviewInvite bookingInvite2 = ReviewInvite.scheduleForBooking(UUID.randomUUID(), PHONE_HASHER.hash(PHONE),
                NOW.minus(Duration.ofMinutes(4)), NOW);
        when(repository.claimDue(NOW, 50)).thenReturn(List.of(visitInvite, bookingInvite1, bookingInvite2));
        when(repository.findVisitViews(List.of(visitInvite.getAppointmentId())))
                .thenReturn(List.of(view(visitInvite.getAppointmentId())));
        when(repository.findBookingViews(List.of(bookingInvite1.getBookingId(), bookingInvite2.getBookingId())))
                .thenReturn(List.of(bookingView(bookingInvite1.getBookingId()),
                        bookingView(bookingInvite2.getBookingId())));
        when(eligibility.evaluateInBatch(any(), any())).thenReturn(new Verdict(PHONE_HASHER.hash(PHONE), null));
        when(tokenGenerator.generateToken(16)).thenReturn(TOKEN);

        List<PreparedInviteSms> prepared = service.claimAndPrepare(NOW);

        assertThat(prepared).extracting(PreparedInviteSms::inviteId)
                .containsExactly(visitInvite.getId(), bookingInvite1.getId(), bookingInvite2.getId());
        verify(repository, times(1)).findVisitViews(any());
        verify(repository, times(1)).findBookingViews(any());
    }

    @Test
    @DisplayName("D7 — a booking-keyed invite whose booking vanished from the view → SKIPPED (NOTHING_TO_REVIEW)")
    void should_skipNothingToReview_when_bookingViewMissing() {
        ReviewInvite invite = ReviewInvite.scheduleForBooking(UUID.randomUUID(), PHONE_HASHER.hash(PHONE),
                NOW.minus(Duration.ofMinutes(5)), NOW);
        when(repository.claimDue(NOW, 50)).thenReturn(List.of(invite));
        when(repository.findBookingViews(List.of(invite.getBookingId()))).thenReturn(List.of());

        List<PreparedInviteSms> prepared = service.claimAndPrepare(NOW);

        assertThat(prepared).isEmpty();
        assertThat(invite.getSkipReason()).isEqualTo(ReviewInviteSkipReason.NOTHING_TO_REVIEW);
    }

    @Test
    @DisplayName("one unmappable visit row → that invite SKIPPED (INVALID_DATA); the rest of the batch is still sent")
    void should_skipInvalidDataAndContinue_when_oneViewUnmappable() {
        ReviewInvite broken = scheduled(NOW.minus(Duration.ofMinutes(5)));
        ReviewInvite healthy = scheduled(NOW.minus(Duration.ofMinutes(5)));
        when(repository.claimDue(NOW, 50)).thenReturn(List.of(broken, healthy));
        when(repository.findVisitViews(List.of(broken.getAppointmentId(), healthy.getAppointmentId())))
                .thenReturn(List.of(view(broken.getAppointmentId(), "NOT_A_SOURCE"), view(healthy.getAppointmentId())));
        when(eligibility.evaluateInBatch(any(), any())).thenReturn(new Verdict(PHONE_HASHER.hash(PHONE), null));
        when(tokenGenerator.generateToken(16)).thenReturn(TOKEN);

        List<PreparedInviteSms> prepared = service.claimAndPrepare(NOW);

        assertThat(broken.getStatus()).isEqualTo(ReviewInviteStatus.SKIPPED);
        assertThat(broken.getSkipReason()).isEqualTo(ReviewInviteSkipReason.INVALID_DATA);
        assertThat(broken.getTokenHash()).isNull();
        assertThat(prepared).singleElement().satisfies(sms -> assertThat(sms.inviteId()).isEqualTo(healthy.getId()));
        assertThat(healthy.getStatus()).isEqualTo(ReviewInviteStatus.SENT);
    }

    @Test
    @DisplayName("the batch (opt-outs + phone hashes) is preloaded ONCE and that batch is what every row is evaluated with")
    void should_preloadOptOutsOnce_when_batchHasSeveralRows() {
        ReviewInvite a = scheduled(NOW.minus(Duration.ofMinutes(5)));
        ReviewInvite b = scheduled(NOW.minus(Duration.ofMinutes(5)));
        when(repository.claimDue(NOW, 50)).thenReturn(List.of(a, b));
        when(repository.findVisitViews(List.of(a.getAppointmentId(), b.getAppointmentId())))
                .thenReturn(List.of(view(a.getAppointmentId()), view(b.getAppointmentId())));
        ReviewInviteEligibility.PreloadedBatch lookup =
                new ReviewInviteEligibility.PreloadedBatch(java.util.Map.of(), hash -> false);
        when(eligibility.preload(any())).thenReturn(lookup);
        when(eligibility.evaluateInBatch(any(), org.mockito.ArgumentMatchers.same(lookup)))
                .thenReturn(new Verdict(PHONE_HASHER.hash(PHONE), null));
        when(tokenGenerator.generateToken(16)).thenReturn(TOKEN);

        List<PreparedInviteSms> prepared = service.claimAndPrepare(NOW);

        assertThat(prepared).hasSize(2);
        verify(eligibility, times(1)).preload(any());
        verify(eligibility, times(2)).evaluateInBatch(any(), org.mockito.ArgumentMatchers.same(lookup));
        verify(eligibility, never()).evaluate(any());
        verify(eligibility, never()).evaluate(any(), any());
    }

    @Test
    @DisplayName("an UNKNOWN send outcome → FAILED at once (no retry), attempt counted, token voided")
    void should_failWithoutRetry_when_sendOutcomeAmbiguous() {
        ReviewInvite invite = scheduled(NOW.minus(Duration.ofMinutes(5)));
        invite.markSent(ReviewInviteTokenHasher.hash(TOKEN), NOW, NOW.plus(Duration.ofDays(14)));
        when(repository.findById(invite.getId())).thenReturn(Optional.of(invite));

        service.recordAmbiguousSend(invite.getId());

        assertThat(invite.getStatus()).isEqualTo(ReviewInviteStatus.FAILED);
        assertThat(invite.getAttempts()).isEqualTo((short) 1);
        assertThat(invite.getTokenHash()).isNull();
        assertThat(invite.getSentAt()).isNull();
        assertThat(invite.getExpiresAt()).isNull();
    }

    @Test
    @DisplayName("an ambiguous report for an invite that is no longer SENT changes nothing")
    void should_ignoreAmbiguous_when_inviteNotSent() {
        ReviewInvite invite = scheduled(NOW);
        when(repository.findById(invite.getId())).thenReturn(Optional.of(invite));

        service.recordAmbiguousSend(invite.getId());

        assertThat(invite.getStatus()).isEqualTo(ReviewInviteStatus.SCHEDULED);
        assertThat(invite.getAttempts()).isZero();
    }

    @Test
    @DisplayName("releasing unsent rows: SENT → SCHEDULED, token voided, attempts unchanged; other states untouched")
    void should_requeueWithoutAttempt_when_releasingUnsent() {
        ReviewInvite sent = scheduled(NOW.minus(Duration.ofMinutes(5)));
        sent.markSent(ReviewInviteTokenHasher.hash(TOKEN), NOW, NOW.plus(Duration.ofDays(14)));
        ReviewInvite skipped = scheduled(NOW.minus(Duration.ofMinutes(5)));
        skipped.skip(ReviewInviteSkipReason.STALE);
        when(repository.findAllById(List.of(sent.getId(), skipped.getId()))).thenReturn(List.of(sent, skipped));

        service.releaseUnsent(List.of(sent.getId(), skipped.getId()));

        assertThat(sent.getStatus()).isEqualTo(ReviewInviteStatus.SCHEDULED);
        assertThat(sent.getAttempts()).isZero();
        assertThat(sent.getTokenHash()).isNull();
        assertThat(sent.getSentAt()).isNull();
        assertThat(sent.getExpiresAt()).isNull();
        assertThat(skipped.getStatus()).isEqualTo(ReviewInviteStatus.SKIPPED);
    }

    @Test
    @DisplayName("releasing an empty list touches no row")
    void should_queryNothing_when_releasingNothing() {
        service.releaseUnsent(List.of());

        verify(repository, never()).findAllById(any());
    }

    private static ReviewInviteVisitView bookingView(UUID bookingId) {
        return new ReviewInviteVisitView() {
            public UUID getAppointmentId() { return null; }
            public UUID getBookingId() { return bookingId; }
            public String getBookingSource() { return BookingSource.LINK.name(); }
            public boolean getHasClient() { return false; }
            public String getGuestPhone() { return PHONE; }
            public boolean getAnonymised() { return false; }
            public String getPolicyVersion() { return ReviewInviteTestProperties.MIN_POLICY_VERSION; }
            public boolean getHasUnreviewedCompleted() { return true; }
        };
    }

    private void stubClaim(ReviewInvite invite) {
        when(repository.claimDue(NOW, 50)).thenReturn(List.of(invite));
        when(repository.findVisitViews(List.of(invite.getAppointmentId())))
                .thenReturn(List.of(view(invite.getAppointmentId())));
    }

    private static ReviewInvite scheduled(Instant sendAfter) {
        return ReviewInvite.schedule(UUID.randomUUID(), PHONE_HASHER.hash(PHONE), sendAfter, sendAfter);
    }

    private static ReviewInviteVisitView view(UUID appointmentId) {
        return view(appointmentId, BookingSource.LINK.name());
    }

    private static ReviewInviteVisitView view(UUID appointmentId, String bookingSource) {
        return new ReviewInviteVisitView() {
            public UUID getAppointmentId() { return appointmentId; }
            public UUID getBookingId() { return null; }
            public String getBookingSource() { return bookingSource; }
            public boolean getHasClient() { return false; }
            public String getGuestPhone() { return PHONE; }
            public boolean getAnonymised() { return false; }
            public String getPolicyVersion() { return ReviewInviteTestProperties.MIN_POLICY_VERSION; }
            public boolean getHasUnreviewedCompleted() { return true; }
        };
    }
}
