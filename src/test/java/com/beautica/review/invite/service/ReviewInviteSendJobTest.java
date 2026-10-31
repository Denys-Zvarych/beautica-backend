package com.beautica.review.invite.service;

import com.beautica.notification.channel.GuestMessageRouters;
import com.beautica.notification.sms.SmsDeliveryAmbiguousException;
import com.beautica.notification.sms.SmsDeliveryException;
import com.beautica.notification.sms.SmsService;
import com.beautica.review.invite.support.ReviewInviteTestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReviewInviteSendJob — phase 400 D3/D4/D6 tick")
class ReviewInviteSendJobTest {

    private static final Instant MIDDAY = Instant.parse("2026-06-10T09:00:00Z");  // 12:00 Kyiv
    private static final Instant NIGHT = Instant.parse("2026-06-10T20:00:00Z");   // 23:00 Kyiv

    @Mock
    private ReviewInviteSendService sendService;
    @Mock
    private SmsService smsService;

    private ReviewInviteSendJob job;

    @BeforeEach
    void setUp() {
        job = new ReviewInviteSendJob(sendService, new QuietHoursCalculator(ReviewInviteTestProperties.enabled()),
                GuestMessageRouters.smsOnly(smsService), ReviewInviteTestProperties.enabled(), Clock.fixed(MIDDAY, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("daytime tick: expiry sweep, then claim, then one send per prepared SMS")
    void should_sweepClaimAndSend_when_daytime() {
        PreparedInviteSms sms = new PreparedInviteSms(UUID.randomUUID(), "+380501234567", "text");
        when(sendService.claimAndPrepare(MIDDAY)).thenReturn(List.of(sms));

        job.run();

        verify(sendService).expireSent(MIDDAY);
        verify(smsService).send("+380501234567", "text");
        verify(sendService, never()).recordFailedSend(any());
    }

    @Test
    @DisplayName("inside the Kyiv quiet window: expiry still runs, nothing is claimed or sent")
    void should_claimNothing_when_quietHours() {
        job.tick(NIGHT);

        verify(sendService).expireSent(NIGHT);
        verify(sendService, never()).claimAndPrepare(any());
        verify(smsService, never()).send(any(), any());
    }

    @Test
    @DisplayName("a provider failure is recorded for that invite only; the rest of the batch still goes out")
    void should_recordFailureAndContinue_when_oneSendFails() {
        PreparedInviteSms failing = new PreparedInviteSms(UUID.randomUUID(), "+380501111111", "a");
        PreparedInviteSms ok = new PreparedInviteSms(UUID.randomUUID(), "+380502222222", "b");
        when(sendService.claimAndPrepare(MIDDAY)).thenReturn(List.of(failing, ok));
        doThrow(new SmsDeliveryException("boom")).when(smsService).send("+380501111111", "a");

        job.tick(MIDDAY);

        verify(sendService).recordFailedSend(failing.inviteId());
        verify(smsService).send("+380502222222", "b");
        verify(sendService, never()).recordFailedSend(ok.inviteId());
    }

    @Test
    @DisplayName("a failing failure write-back does not abort the remaining sends")
    void should_continueBatch_when_failureWriteBackThrows() {
        PreparedInviteSms failing = new PreparedInviteSms(UUID.randomUUID(), "+380501111111", "a");
        PreparedInviteSms ok = new PreparedInviteSms(UUID.randomUUID(), "+380502222222", "b");
        when(sendService.claimAndPrepare(MIDDAY)).thenReturn(List.of(failing, ok));
        doThrow(new SmsDeliveryException("boom")).when(smsService).send("+380501111111", "a");
        doThrow(new IllegalStateException("db down")).when(sendService).recordFailedSend(failing.inviteId());

        job.tick(MIDDAY);

        verify(smsService).send("+380502222222", "b");
    }

    @Test
    @DisplayName("an UNKNOWN provider outcome (read timeout) is recorded as ambiguous — never as a retryable failure")
    void should_recordAmbiguousNotRetry_when_providerOutcomeUnknown() {
        PreparedInviteSms sms = new PreparedInviteSms(UUID.randomUUID(), "+380501111111", "a");
        when(sendService.claimAndPrepare(MIDDAY)).thenReturn(List.of(sms));
        doThrow(new SmsDeliveryAmbiguousException("unknown", new RuntimeException()))
                .when(smsService).send("+380501111111", "a");

        job.tick(MIDDAY);

        verify(sendService).recordAmbiguousSend(sms.inviteId());
        verify(sendService, never()).recordFailedSend(any());
    }

    @Test
    @DisplayName("send budget exhausted mid-batch: the rest is released back to SCHEDULED, never sent")
    void should_releaseUnsentRows_when_sendBudgetExhausted() {
        PreparedInviteSms first = new PreparedInviteSms(UUID.randomUUID(), "+380501111111", "a");
        PreparedInviteSms second = new PreparedInviteSms(UUID.randomUUID(), "+380502222222", "b");
        PreparedInviteSms third = new PreparedInviteSms(UUID.randomUUID(), "+380503333333", "c");
        Duration budget = ReviewInviteTestProperties.enabled().sendBudget();
        // tick start, before send #1 (in budget), before send #2 (budget spent)
        ReviewInviteSendJob budgeted = new ReviewInviteSendJob(sendService,
                new QuietHoursCalculator(ReviewInviteTestProperties.enabled()), GuestMessageRouters.smsOnly(smsService),
                ReviewInviteTestProperties.enabled(),
                new SequenceClock(MIDDAY, MIDDAY.plusSeconds(1), MIDDAY.plus(budget)));
        when(sendService.claimAndPrepare(MIDDAY)).thenReturn(List.of(first, second, third));

        budgeted.tick(MIDDAY);

        verify(smsService).send("+380501111111", "a");
        verify(smsService, never()).send("+380502222222", "b");
        verify(smsService, never()).send("+380503333333", "c");
        verify(sendService).releaseUnsent(List.of(second.inviteId(), third.inviteId()));
    }

    @Test
    @DisplayName("within the send budget the whole batch goes out and nothing is released")
    void should_releaseNothing_when_batchFitsBudget() {
        PreparedInviteSms first = new PreparedInviteSms(UUID.randomUUID(), "+380501111111", "a");
        PreparedInviteSms second = new PreparedInviteSms(UUID.randomUUID(), "+380502222222", "b");
        when(sendService.claimAndPrepare(MIDDAY)).thenReturn(List.of(first, second));

        job.tick(MIDDAY);

        verify(smsService).send("+380501111111", "a");
        verify(smsService).send("+380502222222", "b");
        verify(sendService, never()).releaseUnsent(any());
    }

    /** A clock that returns the given instants in order, then repeats the last one. */
    private static final class SequenceClock extends Clock {

        private final Deque<Instant> instants;

        SequenceClock(Instant... instants) {
            this.instants = new ArrayDeque<>(List.of(instants));
        }

        @Override
        public Instant instant() {
            return instants.size() > 1 ? instants.pop() : instants.peek();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
