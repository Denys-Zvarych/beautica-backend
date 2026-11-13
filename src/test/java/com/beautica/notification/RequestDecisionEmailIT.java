package com.beautica.notification;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.service.RequestKind;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.service.CategoryRequestService;
import com.beautica.service.service.ServiceTypeSuggestionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static com.beautica.notification.RequestDecisionEmailExpectedCopy.REASON_MARKER;
import static com.beautica.notification.RequestDecisionEmailExpectedCopy.bodyLine;
import static com.beautica.notification.RequestDecisionEmailExpectedCopy.reasonLine;
import static com.beautica.notification.RequestDecisionEmailExpectedCopy.subject;
import static com.beautica.support.MimeMessageBodies.htmlBodyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 491 — the requester's decision e-mail (NTF-35): every approve / reject on the token review pages
 * enqueues ONE {@code REQUEST_DECIDED} outbox row in the decision transaction, and the real drain renders
 * the real {@code email/request-decision} template over a mocked SMTP transport.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Phase 491 — request-decision e-mail (category request + service-type suggestion)")
class RequestDecisionEmailIT extends AbstractIntegrationTest {

    private static final String CATEGORY_APPROVE = "/api/v1/service-categories/requests/approve";
    private static final String CATEGORY_REJECT = "/api/v1/service-categories/requests/reject";
    private static final String SUGGESTION_APPROVE = "/api/v1/service-types/suggestions/approve";
    private static final String SUGGESTION_REJECT = "/api/v1/service-types/suggestions/reject";

    // HAIRDRESSING is seeded APPROVED + active and mapped to a System-A bucket (V74/V78).
    private static final String SEEDED_CATEGORY = "HAIRDRESSING";
    private static final String CATEGORY_NAME = "ITDECISION_MAIL_CAT";
    private static final String CATEGORY_DISPLAY = "Тест-категорія Лист";
    private static final String SUGGESTED_TYPE = "Qzmail Ritual Plumbix";
    private static final String REASON = "Така категорія вже існує — оберіть існуючу";
    private static final String ADMIN_EMAIL = "admin@beautica.test";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CategoryRequestService categoryRequestService;
    @Autowired private ServiceTypeSuggestionService suggestionService;
    @Autowired private SpringTemplateEngine templateEngine;
    @Autowired private NotificationOutboxDrainWorker drainWorker;

    @Value("${app.invite.from-email:noreply@beautica.app}")
    private String fromAddress;

    private JavaMailSender mailSender;

    private record Requester(UUID id, String email) {
    }

    @BeforeEach
    void wireRealDecisionEmailOverAMockedTransport() {
        mailSender = mock(JavaMailSender.class);
        Session session = Session.getInstance(new Properties());
        when(mailSender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(session));
        EmailNotificationService real = new EmailNotificationService(mailSender, templateEngine, fromAddress);
        doAnswer(inv -> {
            real.sendRequestDecisionEmail(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                    inv.getArgument(3), inv.getArgument(4));
            return null;
        }).when(emailNotificationService).sendRequestDecisionEmail(anyString(), any(), anyBoolean(), anyString(), any());
    }

    @AfterEach
    void cleanCatalogueRows() {
        // Runs before AbstractIntegrationTest#cleanDb (which deletes notification_outbox, in_app_notification and
        // users). These catalogue tables are not in cleanDb: remove only this test's rows, children first
        // (service_types -> suggestion -> category).
        jdbcTemplate.update("DELETE FROM in_app_notification");
        jdbcTemplate.update("DELETE FROM service_types WHERE name_uk = ?", SUGGESTED_TYPE);
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE suggested_name = ?", SUGGESTED_TYPE);
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE category_name = ?", CATEGORY_NAME);
        jdbcTemplate.update("DELETE FROM platform_categories WHERE name = ?", CATEGORY_NAME);
    }

    // ── enqueue: same transaction as the decision ────────────────────────────────────────────

    @Test
    @DisplayName("Reject POST writes exactly one PENDING REQUEST_DECIDED row (ids + flag only) with the status change")
    void should_writeOneIdsOnlyOutboxRow_when_categoryRejected() throws Exception {
        Requester requester = newRequester();
        String token = submitCategory(requester);

        ResponseEntity<String> decision = post(CATEGORY_REJECT, token, REASON);

        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(categoryStatus()).isEqualTo("REJECTED");
        List<Map<String, Object>> rows = decisionRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("status")).isEqualTo("PENDING");
        assertThat(rows.get(0).get("aggregate_id")).isEqualTo(requester.id());
        String payload = (String) rows.get(0).get("payload");
        assertThat(payload).doesNotContain(REASON, CATEGORY_DISPLAY, CATEGORY_NAME, requester.email());
        assertThat(objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {}))
                .containsOnlyKeys("kind", "requestId", "approved", "recipientUserId")
                .containsEntry("kind", RequestKind.CATEGORY_REQUEST.name())
                .containsEntry("approved", false)
                .containsEntry("recipientUserId", requester.id().toString());
    }

    @Test
    @DisplayName("The outbox row fails at COMMIT (deferred trigger) — neither the row nor the decision persists")
    void should_rollBackDecisionAndRow_when_outboxRowCannotCommit() throws Exception {
        installCommitFailureTrigger();
        try {
            Requester requester = newRequester();
            String token = submitCategory(requester);

            ResponseEntity<String> decision = post(CATEGORY_REJECT, token, REASON);

            assertThat(decision.getStatusCode().is2xxSuccessful()).as("commit must fail: %s", decision).isFalse();
            assertThat(categoryStatus()).as("the decision rolled back with its outbox row").isEqualTo("PENDING");
            assertThat(decisionRows()).isEmpty();
        } finally {
            dropCommitFailureTrigger();
        }
    }

    @Test
    @DisplayName("Re-POSTing the used token adds no second row")
    void should_addNoSecondRow_when_usedTokenRePosted() throws Exception {
        Requester requester = newRequester();
        String token = submitCategory(requester);

        post(CATEGORY_REJECT, token, REASON);
        post(CATEGORY_APPROVE, token, null);
        post(CATEGORY_REJECT, token, REASON);

        assertThat(decisionRows()).hasSize(1);
    }

    // ── drain: who gets what ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Drained category reject with a reason — ONE message, to the requester only; admin and others get nothing")
    void should_emailOnlyRequesterWithReason_when_categoryRejectDrained() throws Exception {
        Requester requester = newRequester();
        Requester bystander = newRequester();
        String token = submitCategory(requester);
        clearInvocations(emailService);
        post(CATEGORY_REJECT, token, REASON);

        drainWorker.drain();

        MimeMessage message = singleSentMessage();
        assertThat(recipientsOf(message)).containsExactly(requester.email())
                .doesNotContain(bystander.email(), ADMIN_EMAIL);
        assertThat(message.getSubject()).isEqualTo(subject(RequestKind.CATEGORY_REQUEST, false, CATEGORY_DISPLAY));
        assertThat(htmlBodyOf(message)).contains(reasonLine(REASON));
        verifyNoInteractions(emailService);
        assertThat(decisionRows().get(0).get("status")).isEqualTo("SENT");
    }

    @Test
    @DisplayName("Drained suggestion approve — approve copy, no reason block")
    void should_sendApproveCopyWithoutReason_when_suggestionApproveDrained() throws Exception {
        Requester requester = newRequester();
        String token = submitSuggestion(requester);
        post(SUGGESTION_APPROVE, token, null);

        drainWorker.drain();

        MimeMessage message = singleSentMessage();
        assertThat(recipientsOf(message)).containsExactly(requester.email());
        assertThat(message.getSubject()).isEqualTo(subject(RequestKind.SERVICE_TYPE_SUGGESTION, true, SUGGESTED_TYPE));
        assertThat(htmlBodyOf(message)).contains(bodyLine(RequestKind.SERVICE_TYPE_SUGGESTION, true, SUGGESTED_TYPE))
                .doesNotContain(REASON_MARKER);
    }

    @Test
    @DisplayName("Drained suggestion reject with a blank reason — reject copy, no reason block")
    void should_renderNoReasonBlock_when_suggestionRejectedWithBlankReason() throws Exception {
        Requester requester = newRequester();
        String token = submitSuggestion(requester);
        post(SUGGESTION_REJECT, token, "   ");

        drainWorker.drain();

        MimeMessage message = singleSentMessage();
        assertThat(message.getSubject()).isEqualTo(subject(RequestKind.SERVICE_TYPE_SUGGESTION, false, SUGGESTED_TYPE));
        assertThat(htmlBodyOf(message)).contains(bodyLine(RequestKind.SERVICE_TYPE_SUGGESTION, false, SUGGESTED_TYPE))
                .doesNotContain(REASON_MARKER);
    }

    @Test
    @DisplayName("A 300-code-point reason renders in full")
    void should_renderFullReason_when_reasonIs300CodePoints() throws Exception {
        Requester requester = newRequester();
        String token = submitCategory(requester);
        String boundary = "ж".repeat(300);
        post(CATEGORY_REJECT, token, boundary);

        drainWorker.drain();

        assertThat(htmlBodyOf(singleSentMessage())).contains(reasonLine(boundary));
    }

    // ── D3: no e-mail for a gone / inactive requester, row ends SENT ─────────────────────────

    @Test
    @DisplayName("Requester hard-deleted before the drain — no message, row SENT (never DEAD)")
    void should_sendNothingAndMarkSent_when_requesterDeletedBeforeDrain() throws Exception {
        Requester requester = newRequester();
        String token = submitCategory(requester);
        post(CATEGORY_REJECT, token, REASON);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", requester.id());

        drainWorker.drain();

        verify(mailSender, never()).send(any(MimeMessage.class));
        assertThat(decisionRows().get(0).get("status")).isEqualTo("SENT");
    }

    @Test
    @DisplayName("Requester inactive at drain time — no message, row SENT")
    void should_sendNothingAndMarkSent_when_requesterInactive() throws Exception {
        Requester requester = newRequester();
        String token = submitSuggestion(requester);
        post(SUGGESTION_REJECT, token, REASON);
        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", requester.id());

        drainWorker.drain();

        verify(mailSender, never()).send(any(MimeMessage.class));
        assertThat(decisionRows().get(0).get("status")).isEqualTo("SENT");
    }

    @Test
    @DisplayName("requested_by_user_id re-pointed to another user before the drain — no message to anyone, row SENT")
    void should_sendNothingAndMarkSent_when_requesterColumnNoLongerMatches() throws Exception {
        Requester requester = newRequester();
        Requester other = newRequester();
        String token = submitCategory(requester);
        post(CATEGORY_REJECT, token, REASON);
        jdbcTemplate.update("UPDATE platform_categories SET requested_by_user_id = ? WHERE name = ?",
                other.id(), CATEGORY_NAME);

        drainWorker.drain();

        verify(mailSender, never()).send(any(MimeMessage.class));
        assertThat(decisionRows().get(0).get("status")).isEqualTo("SENT");
    }

    // ── QA 491: the remaining kind × decision × reason cells, escaping, suggestion tx ────────

    @Test
    @DisplayName("Drained category approve — category approve subject + body line, no reason block, requester only")
    void should_sendCategoryApproveCopy_when_categoryApproveDrained() throws Exception {
        Requester requester = newRequester();
        String token = submitCategory(requester);
        post(CATEGORY_APPROVE, token, null);

        drainWorker.drain();

        MimeMessage message = singleSentMessage();
        assertThat(recipientsOf(message)).containsExactly(requester.email());
        assertThat(message.getSubject()).isEqualTo(subject(RequestKind.CATEGORY_REQUEST, true, CATEGORY_DISPLAY));
        assertThat(htmlBodyOf(message)).contains(bodyLine(RequestKind.CATEGORY_REQUEST, true, CATEGORY_DISPLAY))
                .doesNotContain(REASON_MARKER);
    }

    @Test
    @DisplayName("Drained suggestion reject with a reason — suggestion reject copy + «Причина: …» line")
    void should_sendSuggestionRejectWithReason_when_suggestionRejectDrained() throws Exception {
        Requester requester = newRequester();
        String token = submitSuggestion(requester);
        post(SUGGESTION_REJECT, token, REASON);

        drainWorker.drain();

        MimeMessage message = singleSentMessage();
        assertThat(recipientsOf(message)).containsExactly(requester.email());
        assertThat(message.getSubject()).isEqualTo(subject(RequestKind.SERVICE_TYPE_SUGGESTION, false, SUGGESTED_TYPE));
        assertThat(htmlBodyOf(message)).contains(bodyLine(RequestKind.SERVICE_TYPE_SUGGESTION, false, SUGGESTED_TYPE))
                .contains(reasonLine(REASON));
    }

    @Test
    @DisplayName("Drained category reject with the reason field omitted — reject copy, no reason block")
    void should_renderNoReasonBlock_when_categoryRejectedWithReasonOmitted() throws Exception {
        Requester requester = newRequester();
        String token = submitCategory(requester);
        post(CATEGORY_REJECT, token, null);

        drainWorker.drain();

        MimeMessage message = singleSentMessage();
        assertThat(message.getSubject()).isEqualTo(subject(RequestKind.CATEGORY_REQUEST, false, CATEGORY_DISPLAY));
        assertThat(htmlBodyOf(message)).contains(bodyLine(RequestKind.CATEGORY_REQUEST, false, CATEGORY_DISPLAY))
                .doesNotContain(REASON_MARKER);
    }

    @Test
    @DisplayName("A <script> reason posted on the token page reaches the e-mail HTML-escaped, never as live markup")
    void should_escapeScriptReason_when_rejectReasonCarriesMarkup() throws Exception {
        Requester requester = newRequester();
        String token = submitCategory(requester);
        post(CATEGORY_REJECT, token, "<script>alert('x')</script>&\"");

        drainWorker.drain();

        String html = htmlBodyOf(singleSentMessage());
        assertThat(html).contains(reasonLine("&lt;script&gt;alert(&#39;x&#39;)&lt;/script&gt;&amp;&quot;"))
                .doesNotContain("<script>");
    }

    @Test
    @DisplayName("Suggestion approve POST writes one SERVICE_TYPE_SUGGESTION row keyed by the suggestion id")
    void should_writeOneSuggestionRow_when_suggestionApproved() throws Exception {
        Requester requester = newRequester();
        String token = submitSuggestion(requester);

        ResponseEntity<String> decision = post(SUGGESTION_APPROVE, token, null);

        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> rows = decisionRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("aggregate_id")).isEqualTo(requester.id());
        assertThat(objectMapper.readValue((String) rows.get(0).get("payload"), new TypeReference<Map<String, Object>>() {}))
                .containsOnlyKeys("kind", "requestId", "approved", "recipientUserId")
                .containsEntry("kind", RequestKind.SERVICE_TYPE_SUGGESTION.name())
                .containsEntry("requestId", suggestionId().toString())
                .containsEntry("approved", true)
                .containsEntry("recipientUserId", requester.id().toString());
    }

    @Test
    @DisplayName("Suggestion approve whose outbox row fails at COMMIT — no row, suggestion PENDING, no promoted type")
    void should_rollBackSuggestionApproval_when_outboxRowCannotCommit() throws Exception {
        installCommitFailureTrigger();
        try {
            Requester requester = newRequester();
            String token = submitSuggestion(requester);

            ResponseEntity<String> decision = post(SUGGESTION_APPROVE, token, null);

            assertThat(decision.getStatusCode().is2xxSuccessful()).as("commit must fail: %s", decision).isFalse();
            assertThat(suggestionStatus()).as("the approval rolled back with its outbox row").isEqualTo("PENDING");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM service_types WHERE name_uk = ?",
                    Integer.class, SUGGESTED_TYPE)).as("the promotion rolled back too").isZero();
            assertThat(decisionRows()).isEmpty();
        } finally {
            dropCommitFailureTrigger();
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private void installCommitFailureTrigger() {
        jdbcTemplate.execute("""
                CREATE FUNCTION it_491_fail_at_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'it-491 forced commit failure'; END; $$
                """);
        jdbcTemplate.execute("""
                CREATE CONSTRAINT TRIGGER it_491_fail_at_commit_trg AFTER INSERT ON notification_outbox
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
                WHEN (NEW.event_type = 'REQUEST_DECIDED') EXECUTE FUNCTION it_491_fail_at_commit()
                """);
    }

    private void dropCommitFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS it_491_fail_at_commit_trg ON notification_outbox");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS it_491_fail_at_commit()");
    }

    private UUID suggestionId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM service_type_suggestion WHERE suggested_name = ?", UUID.class, SUGGESTED_TYPE);
    }

    private String suggestionStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM service_type_suggestion WHERE suggested_name = ?", String.class, SUGGESTED_TYPE);
    }

    private Requester newRequester() {
        UUID id = UUID.randomUUID();
        String email = "decision-mail-" + System.nanoTime() + "@beautica.test";
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)", id, email, passwordEncoder.encode("test-password"));
        return new Requester(id, email);
    }

    private String submitCategory(Requester requester) {
        categoryRequestService.submitRequest(
                new CreateCategoryRequestRequest(CATEGORY_NAME, CATEGORY_DISPLAY, null), requester.id());
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendCategoryRequestNotification(
                anyString(), anyString(), anyString(), anyString(), url.capture());
        return url.getValue().substring(url.getValue().lastIndexOf('/') + 1);
    }

    private String submitSuggestion(Requester requester) {
        suggestionService.submitSuggestionInternal(SEEDED_CATEGORY, SUGGESTED_TYPE, "decision mail IT", requester.id());
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendServiceTypeSuggestionNotification(
                anyString(), anyString(), anyString(), anyString(), anyString(), url.capture());
        return url.getValue().substring(url.getValue().indexOf("token=") + "token=".length());
    }

    private ResponseEntity<String> post(String path, String token, String reason) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        if (reason != null) {
            form.add("reason", reason);
        }
        return restTemplate.postForEntity(path, new HttpEntity<>(form, headers), String.class);
    }

    private List<Map<String, Object>> decisionRows() {
        return jdbcTemplate.queryForList("SELECT status, aggregate_id, payload FROM notification_outbox "
                + "WHERE event_type = 'REQUEST_DECIDED' ORDER BY created_at");
    }

    private String categoryStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM platform_categories WHERE name = ?", String.class, CATEGORY_NAME);
    }

    private MimeMessage singleSentMessage() {
        ArgumentCaptor<MimeMessage> captor = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(captor.capture());
        return captor.getValue();
    }

    private static List<String> recipientsOf(MimeMessage message) throws Exception {
        return Arrays.stream(message.getAllRecipients()).map(Object::toString).toList();
    }
}
