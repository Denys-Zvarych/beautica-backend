package com.beautica.notification;

import com.beautica.AbstractIntegrationTest;
import com.beautica.notification.mail.BrevoApiMailSender;
import com.beautica.notification.mail.MailTransportProperties;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.support.dto.SupportAttachment;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.ServeEvent;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

/**
 * Phase 374 — the outbox over the Brevo HTTPS API transport, end to end on real Postgres: the context runs with
 * {@code app.mail.transport=brevo-api}, so the ONLY {@link JavaMailSender} is {@link BrevoApiMailSender}, pointed at
 * WireMock. Real outbox, real drain worker, real Thymeleaf; the base class's {@code @MockBean EmailNotificationService}
 * delegates {@code sendInviteEmail} to a real instance built on the context's sender and mail properties.
 */
@ExtendWith(OutputCaptureExtension.class)
@TestPropertySource(properties = {
        "app.mail.transport=brevo-api",
        "app.mail.logo-url=https://beautica.app/email/logo.png",
        "app.mail.brevo-api.api-key=xkeysib-it-secret-key"
})
@DisplayName("Phase 374 — outbox over the Brevo HTTPS API transport (WireMock + Testcontainers)")
class BrevoApiOutboxIT extends AbstractIntegrationTest {

    private static final String PATH = "/v3/smtp/email";
    private static final String DEAD_METRIC = "notification.outbox.dead";
    private static final String BREAKER_METRIC = "notification.outbox.breaker.trips";
    private static final WireMockServer BREVO = new WireMockServer(options().dynamicPort());

    static {
        // JVM-lifetime like the Postgres container: the base URL must exist before the context is cached.
        BREVO.start();
    }

    @DynamicPropertySource
    static void brevoBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("app.mail.brevo-api.base-url", BREVO::baseUrl);
    }

    @Autowired
    private JavaMailSender mailSender;
    @Autowired
    private MailTransportProperties mailProperties;
    @Autowired
    private SpringTemplateEngine templateEngine;
    @Autowired
    private NotificationOutboxDrainWorker drainWorker;
    @Autowired
    private NotificationOutboxService outboxService;
    @Autowired
    private PlatformTransactionManager txManager;
    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void wireRealEmailServiceOnTheApiTransport() {
        BREVO.resetAll();
        EmailNotificationService realEmail =
                new EmailNotificationService(mailSender, templateEngine, "noreply@beautica.app", mailProperties);
        doAnswer(inv -> {
            realEmail.sendInviteEmail(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2));
            return null;
        }).when(emailNotificationService).sendInviteEmail(anyString(), anyString(), anyString());
    }

    private static void stub(int status, String body) {
        BREVO.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private UUID enqueueInvite(String email, int minutesAgo) {
        UUID aggregateId = UUID.randomUUID();
        new TransactionTemplate(txManager).executeWithoutResult(status -> outboxService.enqueueInvite(
                aggregateId, email, "https://beautica.app/invite/accept?token=QA374", "QA Salon"));
        jdbcTemplate.update("UPDATE notification_outbox SET created_at = now() - make_interval(mins => ?) "
                + "WHERE aggregate_id = ?", minutesAgo, aggregateId);
        return aggregateId;
    }

    private Map<String, Object> row(UUID aggregateId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, attempts, last_error FROM notification_outbox WHERE aggregate_id = ?", aggregateId);
    }

    private double counter(String name) {
        return meterRegistry.counter(name).count();
    }

    @Test
    @DisplayName("the context's only JavaMailSender is the API sender — no SMTP JavaMailSenderImpl exists")
    void should_wireBrevoApiSender_when_transportBrevoApi() {
        assertThat(mailSender).isInstanceOf(BrevoApiMailSender.class);
    }

    @Test
    @DisplayName("201 — an invite row drains to SENT; the API got htmlContent with the hosted logo; no recipient in logs")
    void should_sendRowOverApi_when_brevoAccepts(CapturedOutput output) {
        stub(201, "{\"messageId\":\"<it@smtp-relay.mailin.fr>\"}");
        UUID invite = enqueueInvite("invitee-zq374@beautica.test", 1);

        drainWorker.drain();

        assertThat(row(invite).get("status")).isEqualTo("SENT");
        List<ServeEvent> events = BREVO.getAllServeEvents();
        assertThat(events).singleElement().satisfies(e -> {
            String body = e.getRequest().getBodyAsString();
            assertThat(e.getRequest().getHeader("api-key")).isEqualTo("xkeysib-it-secret-key");
            assertThat(body).contains("\"htmlContent\"").contains("https://beautica.app/email/logo.png")
                    .doesNotContain("cid:beauticaLogo").doesNotContain("\"attachment\"");
        });
        assertThat(output.getAll()).doesNotContain("invitee-zq374").doesNotContain("xkeysib-it-secret-key");
    }

    @Test
    @DisplayName("503 ×3 — the breaker trips once, the 4th (youngest) row stays PENDING with attempts unchanged")
    void should_tripBreaker_when_brevoReturns503() {
        stub(503, "{\"code\":\"x\",\"message\":\"down\"}");
        UUID first = enqueueInvite("a-zq374@beautica.test", 40);
        UUID second = enqueueInvite("b-zq374@beautica.test", 30);
        UUID third = enqueueInvite("c-zq374@beautica.test", 20);
        UUID youngest = enqueueInvite("d-zq374@beautica.test", 10);
        double tripsBefore = counter(BREAKER_METRIC);

        drainWorker.drain();

        for (UUID failed : List.of(first, second, third)) {
            assertThat(row(failed).get("status")).isEqualTo("PENDING");
            assertThat(row(failed).get("attempts")).isEqualTo(1);
        }
        assertThat(row(youngest).get("attempts")).as("breaker deferral is not an attempt").isEqualTo(0);
        assertThat(row(youngest).get("status")).isEqualTo("PENDING");
        assertThat(counter(BREAKER_METRIC) - tripsBefore).isEqualTo(1.0);
        BREVO.verify(3, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    @DisplayName("400 invalid_parameter — not counted by the breaker (all 4 rows attempted); DEAD after 3 attempts")
    void should_notTripBreakerAndDeadLetter_when_brevoReturns400InvalidParameter() {
        stub(400, "{\"code\":\"invalid_parameter\",\"message\":\"bad a-zq374@beautica.test\"}");
        List<UUID> rows = List.of(enqueueInvite("a-zq374@beautica.test", 40), enqueueInvite("b-zq374@beautica.test", 30),
                enqueueInvite("c-zq374@beautica.test", 20), enqueueInvite("d-zq374@beautica.test", 10));
        double tripsBefore = counter(BREAKER_METRIC);
        double deadBefore = counter(DEAD_METRIC);

        drainWorker.drain();

        assertThat(rows).allSatisfy(r -> assertThat(row(r).get("attempts")).isEqualTo(1));
        assertThat(counter(BREAKER_METRIC) - tripsBefore).as("permanent failures never trip the breaker").isZero();
        assertThat((String) row(rows.get(0)).get("last_error")).doesNotContain("a-zq374").doesNotContain("bad");

        drainWorker.drain();
        drainWorker.drain();

        assertThat(rows).allSatisfy(r -> assertThat(row(r).get("status")).isEqualTo("DEAD"));
        assertThat(counter(DEAD_METRIC) - deadBefore).isEqualTo(4.0);
    }

    @Test
    @DisplayName("support contact — attachment and reply-to go out in a single API request")
    void should_sendSupportContactInOneRequest_when_attachmentAndReplyTo() throws Exception {
        stub(201, "{\"messageId\":\"m\"}");
        EmailService realEmailService = new EmailService(mailSender, templateEngine, "noreply@beautica.app");
        byte[] pdf = "%PDF-1.4 support".getBytes();

        realEmailService.sendSupportContactEmail("support@beautica.app", UUID.randomUUID().toString(),
                "user-zq374@example.com", "Help", "body text",
                List.of(new SupportAttachment("attachment-1.pdf", "application/pdf", pdf))).get();

        assertThat(BREVO.getAllServeEvents()).singleElement().satisfies(e -> {
            String body = e.getRequest().getBodyAsString();
            assertThat(body).contains("\"replyTo\":{\"email\":\"user-zq374@example.com\"}")
                    .contains("\"name\":\"attachment-1.pdf\"")
                    .contains(Base64.getEncoder().encodeToString(pdf));
        });
    }
}
