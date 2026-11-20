package com.beautica.notification.inapp.push;

import com.beautica.notification.service.EmailNotificationService;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.service.RequestDecisionEvent;
import com.beautica.notification.service.RequestDecisionLoader;
import com.beautica.notification.service.RequestDecisionTarget;
import com.beautica.notification.service.RequestKind;
import com.google.firebase.messaging.Message;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 367 QA gate — the outbox e-mail reliability fix, end to end on real Postgres.
 *
 * <p>Real {@link NotificationOutboxService} (sealed INVITE payloads), real {@code claimPendingBatch} CTE,
 * real {@link NotificationOutboxDrainWorker}, real {@code NotificationService}, and a REAL
 * {@link EmailNotificationService} (real Thymeleaf engine) whose only fake is the SMTP edge: a
 * {@link JavaMailSender} that throws {@link MailSendException} on every send (a Brevo outage / {@code PENDING}
 * creds). The base class's {@code @MockBean EmailNotificationService} delegates {@code sendRequestDecisionEmail} (B520: was the invite e-mail) to
 * that real instance, so the {@code MailException -> EmailDeliveryException(transient)} translation runs for
 * real. Push goes through the real dispatcher to the mocked FCM edge ({@code FirebaseSender}).
 *
 * <p>Unit coverage ({@code NotificationOutboxDrainWorkerTest}, {@code NotificationOutboxEmailFailureTest})
 * proves each decision in isolation; this class proves they COMPOSE through the persisted row state:
 * attempts, status, {@code last_error} and the Micrometer counters across separate drain ticks.
 */
@TestPropertySource(properties = "FIREBASE_ENABLED=true")
@DisplayName("Phase 367 — outbox under an SMTP outage: retry -> DEAD, breaker, push still delivered (Testcontainers)")
class OutboxSmtpOutageIT extends AbstractInAppPushFlowIT {

    private static final String DEAD_METRIC = "notification.outbox.dead";
    private static final String BREAKER_METRIC = "notification.outbox.breaker.trips";

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;
    @Autowired
    private NotificationOutboxService outboxService;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private SpringTemplateEngine templateEngine;
    @Autowired
    private MeterRegistry meterRegistry;

    private JavaMailSender failingSmtp;

    @BeforeEach
    void wireFailingSmtpBehindRealEmailService() {
        failingSmtp = mock(JavaMailSender.class);
        when(failingSmtp.createMimeMessage()).thenAnswer(inv -> new MimeMessage(Session.getInstance(new Properties())));
        doThrow(new MailSendException("535 Authentication failed for relay-recipient-leak@beautica.test"))
                .when(failingSmtp).send(any(MimeMessage.class));
        EmailNotificationService realEmail =
                new EmailNotificationService(failingSmtp, templateEngine, "noreply@beautica.app");
        doAnswer(inv -> {
            realEmail.sendRequestDecisionEmail(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                    inv.getArgument(3), inv.getArgument(4));
            return null;
        }).when(emailNotificationService).sendRequestDecisionEmail(anyString(), any(), anyBoolean(), anyString(), any());
        // B520: the invite is an SMS now — the requester's decision e-mail (REQUEST_DECIDED) is this test's e-mail row.
        // The loader is mocked so no category-request fixture is needed: every row resolves to its seeded address.
        recipients.clear();
        when(requestDecisionLoader.load(any())).thenAnswer(inv -> snapshotFor(inv.getArgument(0)));
    }

    /** Enqueues one INVITE row through the real service and backdates it, so claim order is deterministic. */
    private UUID enqueueEmail(String email, int minutesAgo) {
        UUID aggregateId = UUID.randomUUID();
        recipients.put(aggregateId, email);
        new TransactionTemplate(txManager).executeWithoutResult(status -> outboxService.enqueueRequestDecided(
                RequestKind.CATEGORY_REQUEST, "1", true, aggregateId));
        jdbcTemplate.update("UPDATE notification_outbox SET created_at = now() - make_interval(mins => ?) "
                + "WHERE aggregate_id = ?", minutesAgo, aggregateId);
        return aggregateId;
    }

    @org.springframework.boot.test.mock.mockito.MockBean
    private RequestDecisionLoader requestDecisionLoader;

    /** Recipient user id (= aggregate id) → address, per test. */
    private final Map<UUID, String> recipients = new java.util.concurrent.ConcurrentHashMap<>();

    private RequestDecisionLoader.Snapshot snapshotFor(java.util.Collection<RequestDecisionEvent> events) {
        Map<UUID, com.beautica.user.User> users = new java.util.HashMap<>();
        for (RequestDecisionEvent event : events) {
            com.beautica.user.User user = mock(com.beautica.user.User.class);
            when(user.getEmail()).thenReturn(recipients.get(event.recipientUserId()));
            when(user.isActive()).thenReturn(true);
            users.put(event.recipientUserId(), user);
        }
        RequestDecisionLoader.Snapshot snapshot = mock(RequestDecisionLoader.Snapshot.class);
        when(snapshot.target(any())).thenAnswer(inv -> new RequestDecisionTarget("Манікюр", null,
                ((RequestDecisionEvent) inv.getArgument(0)).recipientUserId()));
        when(snapshot.recipient(any())).thenAnswer(inv ->
                users.get(((RequestDecisionEvent) inv.getArgument(0)).recipientUserId()));
        return snapshot;
    }

    private Map<String, Object> row(UUID aggregateId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, attempts, last_error FROM notification_outbox WHERE aggregate_id = ?", aggregateId);
    }

    private double counter(String name) {
        return meterRegistry.counter(name).count();
    }

    @Test
    @DisplayName("an invite whose SMTP send keeps failing is retried on each tick and lands DEAD after 3 attempts — "
            + "never SENT, last_error names the template only, DEAD counter +1")
    void should_retryThenDeadLetter_when_smtpKeepsFailing() {
        UUID invite = enqueueEmail("invitee-zq91@beautica.test", 1);
        double deadBefore = counter(DEAD_METRIC);

        drainWorker.drain();

        Map<String, Object> afterFirst = row(invite);
        assertThat(afterFirst.get("status")).as("first failure is a retry, not SENT (the old swallow bug)")
                .isEqualTo("PENDING");
        assertThat(afterFirst.get("attempts")).isEqualTo(1);
        assertThat((String) afterFirst.get("last_error"))
                .as("last_error must name the template and never echo the recipient or the relay text")
                .startsWith("Email delivery failed [template=")
                .doesNotContain("invitee-zq91", "relay-recipient-leak", "535");

        drainWorker.drain();
        drainWorker.drain();

        Map<String, Object> afterThird = row(invite);
        assertThat(afterThird.get("status")).as("MAX_ATTEMPTS reached").isEqualTo("DEAD");
        assertThat(afterThird.get("attempts")).isEqualTo(3);
        assertThat(counter(DEAD_METRIC) - deadBefore).as("one row dead-lettered").isEqualTo(1.0);
        verify(failingSmtp, times(3)).send(any(MimeMessage.class));

        drainWorker.drain();

        verify(failingSmtp, times(3).description("a DEAD row is never claimed again")).send(any(MimeMessage.class));
    }

    @Test
    @DisplayName("during an SMTP outage a drain still delivers every push row; 3 consecutive e-mail failures trip "
            + "the breaker and the 4th (youngest) invite is deferred with no attempt counted")
    void should_dispatchPushAndDeferRemainingEmail_when_smtpDownAndBreakerTrips() throws Exception {
        Rig rig = seedRigWithProviderDeviceTokens();
        clientBooks(rig, startsAt());
        // Keep only the 3 INAPP_PUSH rows the booking produced; the booking e-mail rows go through the
        // base class's no-op e-mail mock and would only blur the breaker arithmetic.
        jdbcTemplate.update("DELETE FROM notification_outbox WHERE event_type <> 'INAPP_PUSH'");
        assertThat(countOutbox("INAPP_PUSH")).as("one push row per provider feed row").isEqualTo(3);
        // E-mail rows OLDER than the push rows: under plain oldest-first they would be claimed first.
        UUID oldest = enqueueEmail("outage-a@beautica.test", 40);
        UUID second = enqueueEmail("outage-b@beautica.test", 30);
        UUID third = enqueueEmail("outage-c@beautica.test", 20);
        UUID youngest = enqueueEmail("outage-d@beautica.test", 10);
        double tripsBefore = counter(BREAKER_METRIC);

        drainWorker.drain();

        List<Message> sent = awaitSends(3);
        assertThat(sent).extracting(AbstractInAppPushFlowIT::targetTokenOf)
                .as("every provider token got its push although e-mail is down")
                .containsExactlyInAnyOrderElementsOf(rig.tokenByRecipient().values());
        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM notification_outbox WHERE event_type = 'INAPP_PUSH'", String.class))
                .as("push rows SENT in the same tick").containsOnly("SENT").hasSize(3);
        for (UUID failed : List.of(oldest, second, third)) {
            Map<String, Object> r = row(failed);
            assertThat(r.get("status")).as("failed invite stays retryable").isEqualTo("PENDING");
            assertThat(r.get("attempts")).as("failed invite counted one attempt").isEqualTo(1);
            assertThat(r.get("last_error")).as("failed invite records why").isNotNull();
        }
        Map<String, Object> deferred = row(youngest);
        assertThat(deferred.get("status")).as("breaker-deferred invite back to PENDING").isEqualTo("PENDING");
        assertThat(deferred.get("attempts")).as("breaker deferral is not a delivery attempt").isEqualTo(0);
        assertThat(deferred.get("last_error")).as("never dispatched, so no error recorded").isNull();
        verify(failingSmtp, times(3).description("breaker stops SMTP calls after 3 consecutive failures"))
                .send(any(MimeMessage.class));
        assertThat(counter(BREAKER_METRIC) - tripsBefore).as("one breaker trip").isEqualTo(1.0);
    }
}
