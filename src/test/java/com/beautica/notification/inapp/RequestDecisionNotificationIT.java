package com.beautica.notification.inapp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.ServiceTestFixtures;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.dto.SuggestServiceTypeRequest;
import com.beautica.service.service.CategoryRequestService;
import com.beautica.service.service.ServiceTypeSuggestionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.awaitility.core.ConditionTimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

/**
 * Phase 372 — the platform admin's approve/reject on the token-authenticated review pages writes ONE
 * in-app feed item for the REQUESTER only, in the decision's transaction. Every decision here goes
 * through the real review POST; every assertion reads the real {@code GET /api/v1/notifications}.
 */
@Import(TestSecurityConfig.class)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Phase 372 — request-decision feed items (category request + service-type suggestion)")
class RequestDecisionNotificationIT extends AbstractIntegrationTest {

    private static final String CATEGORY_APPROVE = "/api/v1/service-categories/requests/approve";
    private static final String CATEGORY_REJECT = "/api/v1/service-categories/requests/reject";
    private static final String SUGGESTION_APPROVE = "/api/v1/service-types/suggestions/approve";
    private static final String SUGGESTION_REJECT = "/api/v1/service-types/suggestions/reject";

    // HAIRDRESSING is seeded APPROVED + active and mapped to a System-A bucket (V74/V78).
    private static final String SEEDED_CATEGORY = "HAIRDRESSING";
    private static final String CATEGORY_DISPLAY = "Тест-категорія Рішення";
    private static final String CATEGORY_NAME = "ITDECISION_CAT";
    private static final String SUGGESTED_TYPE = "Qzdecision Ritual Plumbix";
    private static final String ALREADY_DECIDED_HEADING = "Рішення вже прийнято";
    private static final String REASON = "Така категорія вже існує — оберіть існуючу";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CategoryRequestService categoryRequestService;
    @Autowired private ServiceTypeSuggestionService suggestionService;

    private record Requester(UUID id, String email, String token) {
    }

    @AfterEach
    void cleanCatalogueRows() {
        // Runs before AbstractIntegrationTest#cleanDb. in_app_notification is deleted there first;
        // these two tables are not in cleanDb, so remove only this test's rows (FK: notification -> request).
        jdbcTemplate.update("DELETE FROM in_app_notification");
        jdbcTemplate.update("DELETE FROM service_types WHERE name_uk = ?", SUGGESTED_TYPE);
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE suggested_name = ?", SUGGESTED_TYPE);
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE category_name = ?", CATEGORY_NAME);
        jdbcTemplate.update("DELETE FROM platform_categories WHERE name = ?", CATEGORY_NAME);
    }

    // ── category request ─────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Category approved — the requester gets exactly one unread CATEGORY_REQUEST_APPROVED item (SERVICE_CATALOG, categoryName, no reason)")
    void should_writeOneApprovedItem_forRequester_when_categoryApproved() throws Exception {
        Requester requester = newRequester();
        String token = submitCategory(requester, null);

        ResponseEntity<String> decision = post(CATEGORY_APPROVE, token, null);

        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode feed = feed(requester);
        assertThat(feed).hasSize(1);
        JsonNode item = feed.get(0);
        assertThat(item.path("type").asText()).isEqualTo("CATEGORY_REQUEST_APPROVED");
        assertThat(item.path("read").asBoolean()).isFalse();
        assertThat(item.path("target").path("kind").asText()).isEqualTo("SERVICE_CATALOG");
        assertThat(item.path("target").path("bookingId").isNull()).isTrue();
        assertThat(item.path("target").path("salonId").isNull()).isTrue();
        assertThat(item.path("params").path("categoryName").asText()).isEqualTo(CATEGORY_DISPLAY);
        assertThat(item.path("params").path("decisionReason").isNull()).isTrue();
    }

    @Test
    @DisplayName("Category rejected with a reason — the item carries the TRIMMED reason, stored on the request and echoed on the result page")
    void should_writeRejectedItemWithReason_when_categoryRejectedWithReason() throws Exception {
        Requester requester = newRequester();
        String token = submitCategory(requester, null);

        ResponseEntity<String> decision = post(CATEGORY_REJECT, token, "  " + REASON + "  ");

        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode item = feed(requester).get(0);
        assertThat(item.path("type").asText()).isEqualTo("CATEGORY_REQUEST_REJECTED");
        assertThat(item.path("target").path("kind").asText()).isEqualTo("SERVICE_CATALOG");
        assertThat(item.path("params").path("categoryName").asText()).isEqualTo(CATEGORY_DISPLAY);
        assertThat(item.path("params").path("decisionReason").asText()).as("trimmed").isEqualTo(REASON);
        assertThat(decision.getBody()).contains(REASON);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT decision_reason FROM platform_categories WHERE name = ?", String.class, CATEGORY_NAME))
                .isEqualTo(REASON);
    }

    @Test
    @DisplayName("Category rejected with a whitespace-only reason — the item and the request store no reason")
    void should_writeRejectedItemWithoutReason_when_reasonBlank() throws Exception {
        Requester requester = newRequester();
        String token = submitCategory(requester, null);

        post(CATEGORY_REJECT, token, "   \n\t ");

        JsonNode item = feed(requester).get(0);
        assertThat(item.path("type").asText()).isEqualTo("CATEGORY_REQUEST_REJECTED");
        assertThat(item.path("params").path("decisionReason").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT decision_reason FROM platform_categories WHERE name = ?", String.class, CATEGORY_NAME))
                .isNull();
    }

    @Test
    @DisplayName("Category reject with a 301-char reason — neutral page, request stays PENDING, no item")
    void should_rejectOverlongReasonWithoutDeciding_when_reasonExceeds300() throws Exception {
        Requester requester = newRequester();
        String token = submitCategory(requester, null);

        ResponseEntity<String> decision = post(CATEGORY_REJECT, token, "ж".repeat(301));

        assertThat(decision.getBody()).contains("Посилання недоступне");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM platform_categories WHERE name = ?", String.class, CATEGORY_NAME))
                .isEqualTo("PENDING");
        assertThat(feed(requester)).isEmpty();
    }

    @Test
    @DisplayName("Category reject with a 300-char reason containing markup — accepted, HTML-escaped on the result page, verbatim in the feed")
    void should_acceptReasonOfExactly300_and_escapeItInThePage_when_rejected() throws Exception {
        Requester requester = newRequester();
        String token = submitCategory(requester, null);
        String boundary = "<b>x</b>" + "ж".repeat(292);

        ResponseEntity<String> decision = post(CATEGORY_REJECT, token, boundary);

        assertThat(boundary).hasSize(300);
        assertThat(decision.getBody()).contains("&lt;b&gt;x&lt;/b&gt;").doesNotContain("<b>x</b>");
        assertThat(feed(requester).get(0).path("params").path("decisionReason").asText()).isEqualTo(boundary);
    }

    @Test
    @DisplayName("Category token replayed after a decision — «Рішення вже прийнято», still exactly one item")
    void should_writeNoSecondItem_when_tokenReplayed() throws Exception {
        Requester requester = newRequester();
        String token = submitCategory(requester, null);

        post(CATEGORY_REJECT, token, REASON);
        ResponseEntity<String> replay = post(CATEGORY_APPROVE, token, null);

        assertThat(replay.getBody()).contains("Рішення вже прийнято");
        assertThat(feed(requester)).hasSize(1);
        assertThat(feed(requester).get(0).path("type").asText()).isEqualTo("CATEGORY_REQUEST_REJECTED");
    }

    @Test
    @DisplayName("Category token expired — approve writes no item")
    void should_writeNothing_when_tokenExpired() throws Exception {
        Requester requester = newRequester();
        String token = submitCategory(requester, null);
        jdbcTemplate.update(
                "UPDATE platform_categories SET token_expires_at = now() - interval '1 day' WHERE name = ?",
                CATEGORY_NAME);

        post(CATEGORY_APPROVE, token, null);

        assertThat(feed(requester)).isEmpty();
        assertThat(countFeedRows()).isZero();
    }

    @Test
    @DisplayName("Unknown tokens on all four decision endpoints — no item written")
    void should_writeNothing_when_tokenIsGarbage() throws Exception {
        newRequester();

        post(CATEGORY_APPROVE, "garbage-" + UUID.randomUUID(), null);
        post(CATEGORY_REJECT, "garbage-" + UUID.randomUUID(), REASON);
        post(SUGGESTION_APPROVE, "garbage-" + UUID.randomUUID(), null);
        post(SUGGESTION_REJECT, "garbage-" + UUID.randomUUID(), REASON);

        assertThat(countFeedRows()).isZero();
    }

    @Test
    @DisplayName("Category request with no requester (legacy/deleted user) — the decision commits, no item is written")
    void should_commitDecisionAndWriteNothing_when_requesterNull() throws Exception {
        Requester requester = newRequester();
        String token = submitCategory(requester, null);
        jdbcTemplate.update("UPDATE platform_categories SET requested_by_user_id = NULL WHERE name = ?", CATEGORY_NAME);

        ResponseEntity<String> decision = post(CATEGORY_REJECT, token, REASON);

        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM platform_categories WHERE name = ?", String.class, CATEGORY_NAME))
                .isEqualTo("REJECTED");
        assertThat(countFeedRows()).isZero();
    }

    @Test
    @DisplayName("Category approval auto-creates a PENDING suggestion — only the category item is written, nobody else is notified")
    void should_notNotifyAutoCreatedSuggestion_and_notifyNobodyElse_when_categoryApproved() throws Exception {
        Requester requester = newRequester();
        Requester bystander = newRequester();
        String token = submitCategory(requester, "Qzdecision Initial Service");

        post(CATEGORY_APPROVE, token, null);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM service_type_suggestion WHERE category_name = ? AND status = 'PENDING'",
                Long.class, CATEGORY_NAME)).as("the auto-created suggestion exists, still PENDING").isEqualTo(1L);
        assertThat(feed(requester)).as("only the category item").hasSize(1);
        assertThat(feed(requester).get(0).path("type").asText()).isEqualTo("CATEGORY_REQUEST_APPROVED");
        assertThat(feed(bystander)).isEmpty();
        assertThat(countFeedRows()).isEqualTo(1L);
    }

    // ── service-type suggestion ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Suggestion approved — one SERVICE_TYPE_SUGGESTION_APPROVED item with serviceName and the category's display name")
    void should_writeOneApprovedItem_forRequester_when_suggestionApproved() throws Exception {
        Requester requester = newRequester();
        String token = submitSuggestion(requester);

        ResponseEntity<String> decision = post(SUGGESTION_APPROVE, token, null);

        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode feed = feed(requester);
        assertThat(feed).hasSize(1);
        JsonNode item = feed.get(0);
        assertThat(item.path("type").asText()).isEqualTo("SERVICE_TYPE_SUGGESTION_APPROVED");
        assertThat(item.path("target").path("kind").asText()).isEqualTo("SERVICE_CATALOG");
        assertThat(item.path("params").path("serviceName").asText()).isEqualTo(SUGGESTED_TYPE);
        assertThat(item.path("params").path("categoryName").asText())
                .as("display name looked up from the category wire name")
                .isEqualTo(categoryDisplayName(SEEDED_CATEGORY));
        assertThat(item.path("params").path("decisionReason").isNull()).isTrue();
    }

    @Test
    @DisplayName("Suggestion rejected with a reason — the item carries serviceName and the reason")
    void should_writeRejectedItemWithReason_when_suggestionRejectedWithReason() throws Exception {
        Requester requester = newRequester();
        String token = submitSuggestion(requester);

        post(SUGGESTION_REJECT, token, REASON);

        JsonNode item = feed(requester).get(0);
        assertThat(item.path("type").asText()).isEqualTo("SERVICE_TYPE_SUGGESTION_REJECTED");
        assertThat(item.path("params").path("serviceName").asText()).isEqualTo(SUGGESTED_TYPE);
        assertThat(item.path("params").path("decisionReason").asText()).isEqualTo(REASON);
    }

    @Test
    @DisplayName("Suggestion rejected with an empty reason — the item has no reason")
    void should_writeRejectedItemWithoutReason_when_suggestionReasonBlank() throws Exception {
        Requester requester = newRequester();
        String token = submitSuggestion(requester);

        post(SUGGESTION_REJECT, token, "");

        JsonNode item = feed(requester).get(0);
        assertThat(item.path("type").asText()).isEqualTo("SERVICE_TYPE_SUGGESTION_REJECTED");
        assertThat(item.path("params").path("decisionReason").isNull()).isTrue();
    }

    @Test
    @DisplayName("Suggestion reject with a 301-char reason — neutral page, suggestion stays PENDING, no item")
    void should_rejectOverlongReasonWithoutDeciding_when_suggestionReasonExceeds300() throws Exception {
        Requester requester = newRequester();
        String token = submitSuggestion(requester);

        ResponseEntity<String> decision = post(SUGGESTION_REJECT, token, "ж".repeat(301));

        assertThat(decision.getBody()).contains("Посилання недоступне");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM service_type_suggestion WHERE suggested_name = ?", String.class, SUGGESTED_TYPE))
                .isEqualTo("PENDING");
        assertThat(feed(requester)).isEmpty();
    }

    @Test
    @DisplayName("Suggestion token replayed (approve, approve, reject) — still exactly one item")
    void should_writeNoSecondItem_when_suggestionTokenReplayed() throws Exception {
        Requester requester = newRequester();
        String token = submitSuggestion(requester);

        post(SUGGESTION_APPROVE, token, null);
        post(SUGGESTION_APPROVE, token, null);
        post(SUGGESTION_REJECT, token, REASON);

        assertThat(feed(requester)).hasSize(1);
    }

    @Test
    @DisplayName("Suggestion token expired — reject writes no item")
    void should_writeNothing_when_suggestionTokenExpired() throws Exception {
        Requester requester = newRequester();
        String token = submitSuggestion(requester);
        jdbcTemplate.update("UPDATE service_type_suggestion SET token_expires_at = now() - interval '1 day' "
                + "WHERE suggested_name = ?", SUGGESTED_TYPE);

        post(SUGGESTION_REJECT, token, REASON);

        assertThat(countFeedRows()).isZero();
    }

    @Test
    @DisplayName("Suggestion with no requester — the decision commits, no item is written")
    void should_commitDecisionAndWriteNothing_when_suggestionRequesterNull() throws Exception {
        Requester requester = newRequester();
        String token = submitSuggestion(requester);
        jdbcTemplate.update("UPDATE service_type_suggestion SET requested_by_user_id = NULL "
                + "WHERE suggested_name = ?", SUGGESTED_TYPE);

        post(SUGGESTION_REJECT, token, REASON);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM service_type_suggestion WHERE suggested_name = ?", String.class, SUGGESTED_TYPE))
                .isEqualTo("REJECTED");
        assertThat(countFeedRows()).isZero();
    }

    // ── atomicity ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("The item fails at COMMIT (deferred trigger) — the decision and its reason roll back with it")
    void should_rollBackDecision_when_itemCannotCommit() throws Exception {
        // A DEFERRED constraint trigger makes the feed row's INSERT succeed inside the transaction but
        // fail at COMMIT. If the item were written in a separate transaction (or after commit) the
        // decision would survive; because it shares the decision's transaction, BOTH roll back.
        jdbcTemplate.execute("""
                CREATE FUNCTION it_372_fail_at_commit() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'it-372 forced commit failure'; END; $$
                """);
        jdbcTemplate.execute("""
                CREATE CONSTRAINT TRIGGER it_372_fail_at_commit_trg AFTER INSERT ON in_app_notification
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION it_372_fail_at_commit()
                """);
        try {
            Requester requester = newRequester();
            String token = submitCategory(requester, null);

            ResponseEntity<String> decision = post(CATEGORY_REJECT, token, REASON);

            assertThat(decision.getStatusCode().is2xxSuccessful()).as("commit must fail: %s", decision).isFalse();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT status FROM platform_categories WHERE name = ?", String.class, CATEGORY_NAME))
                    .as("the decision rolled back with its item").isEqualTo("PENDING");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT decision_reason FROM platform_categories WHERE name = ?", String.class, CATEGORY_NAME))
                    .isNull();
            assertThat(countFeedRows()).isZero();
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS it_372_fail_at_commit_trg ON in_app_notification");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS it_372_fail_at_commit()");
        }
    }

    // ── feed contract: privacy + isolation ───────────────────────────────────────────────────

    @Test
    @DisplayName("Feed JSON exposes no requester email/id, salon or counterpart, and another user's feed does not show the item")
    void should_exposeOnlyNamesAndReason_and_hideItFromOtherUsers_when_rejected() throws Exception {
        Requester requester = newRequester();
        Requester other = newRequester();
        String token = submitSuggestion(requester);

        post(SUGGESTION_REJECT, token, REASON);

        String rawFeed = rawFeed(requester);
        assertThat(rawFeed).doesNotContain(requester.email(), requester.id().toString());
        JsonNode item = objectMapper.readTree(rawFeed).path("data").get(0);
        assertThat(item.path("target").path("salonId").isNull()).isTrue();
        assertThat(item.path("params").path("counterpartName").isNull()).isTrue();
        assertThat(item.path("params").path("salonName").isNull()).isTrue();
        assertThat(feed(other)).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM in_app_notification WHERE salon_id IS NOT NULL OR subject_user_id IS NOT NULL",
                Long.class)).isZero();
    }

    // ── end-to-end: real submit API → emailed link → review POST → requester's feed ─────────

    @Test
    @DisplayName("E2E — INDEPENDENT_MASTER submits a category request over the API, admin opens the emailed "
            + "link and approves: the master's GET /notifications shows CATEGORY_REQUEST_APPROVED / SERVICE_CATALOG")
    void should_showApprovedItemInMastersFeed_when_categoryRequestedViaApiAndApprovedViaEmailedLink() throws Exception {
        Requester master = newIndependentMaster();
        String reviewUrl = submitCategoryViaApi(master);
        String token = reviewUrl.substring(reviewUrl.lastIndexOf('/') + 1);
        openEmailedLink(reviewUrl);

        ResponseEntity<String> decision = post(CATEGORY_APPROVE, token, null);

        assertThat(decision.getBody()).contains("Категорію підтверджено");
        JsonNode feed = feed(master);
        assertThat(feed).hasSize(1);
        JsonNode item = feed.get(0);
        assertThat(item.path("type").asText()).isEqualTo("CATEGORY_REQUEST_APPROVED");
        assertThat(item.path("target").path("kind").asText()).isEqualTo("SERVICE_CATALOG");
        assertThat(item.path("params").path("categoryName").asText()).isEqualTo(CATEGORY_DISPLAY);
        assertThat(item.path("params").path("decisionReason").isNull()).as("approve never carries a reason").isTrue();
    }

    @Test
    @DisplayName("E2E — INDEPENDENT_MASTER's API category request rejected with a reason via the emailed link: "
            + "the master's feed shows CATEGORY_REQUEST_REJECTED with categoryName and the reason")
    void should_showRejectedItemWithReasonInMastersFeed_when_categoryRequestedViaApiAndRejected() throws Exception {
        Requester master = newIndependentMaster();
        String reviewUrl = submitCategoryViaApi(master);
        String token = reviewUrl.substring(reviewUrl.lastIndexOf('/') + 1);
        openEmailedLink(reviewUrl);

        post(CATEGORY_REJECT, token, REASON);

        JsonNode item = feed(master).get(0);
        assertThat(item.path("type").asText()).isEqualTo("CATEGORY_REQUEST_REJECTED");
        assertThat(item.path("target").path("kind").asText()).isEqualTo("SERVICE_CATALOG");
        assertThat(item.path("params").path("categoryName").asText()).isEqualTo(CATEGORY_DISPLAY);
        assertThat(item.path("params").path("decisionReason").asText()).isEqualTo(REASON);
    }

    @Test
    @DisplayName("E2E — INDEPENDENT_MASTER suggests a service type over the API, admin rejects with a reason via "
            + "the emailed link: feed shows SERVICE_TYPE_SUGGESTION_REJECTED, serviceName, categoryName, reason")
    void should_showRejectedSuggestionWithReasonInMastersFeed_when_suggestedViaApiAndRejected() throws Exception {
        Requester master = newIndependentMaster();
        ResponseEntity<String> submit = restTemplate.exchange("/api/v1/service-types/suggest", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        new SuggestServiceTypeRequest(SUGGESTED_TYPE, SEEDED_CATEGORY, "decision e2e")),
                        jsonBearer(master)), String.class);
        assertThat(submit.getStatusCode()).as("body=%s", submit.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        String reviewUrl = capturedSuggestionReviewUrl();
        String token = reviewUrl.substring(reviewUrl.indexOf("token=") + "token=".length());
        openEmailedLink(reviewUrl);

        post(SUGGESTION_REJECT, token, REASON);

        JsonNode item = feed(master).get(0);
        assertThat(item.path("type").asText()).isEqualTo("SERVICE_TYPE_SUGGESTION_REJECTED");
        assertThat(item.path("target").path("kind").asText()).isEqualTo("SERVICE_CATALOG");
        assertThat(item.path("params").path("serviceName").asText()).isEqualTo(SUGGESTED_TYPE);
        assertThat(item.path("params").path("categoryName").asText()).isEqualTo(categoryDisplayName(SEEDED_CATEGORY));
        assertThat(item.path("params").path("decisionReason").asText()).isEqualTo(REASON);
    }

    @Test
    @DisplayName("Suggestion result page HTML-escapes the admin's reason (th:text), while the feed keeps it verbatim")
    void should_escapeReasonInSuggestionResultPage_when_rejectedWithMarkup() throws Exception {
        Requester requester = newRequester();
        String token = submitSuggestion(requester);
        String markup = "<script>alert(372)</script>";

        ResponseEntity<String> decision = post(SUGGESTION_REJECT, token, markup);

        assertThat(decision.getBody())
                .contains("&lt;script&gt;alert(372)&lt;/script&gt;")
                .doesNotContain(markup);
        assertThat(feed(requester).get(0).path("params").path("decisionReason").asText()).isEqualTo(markup);
    }

    @Test
    @DisplayName("The admin's reject reason never reaches the logs (category + suggestion), while the decision "
            + "itself IS logged — proves the capture is live, not vacuous")
    void should_neverLogReason_when_bothRequestKindsRejectedWithReason(CapturedOutput output) throws Exception {
        String sentinel = "SENTINEL-REASON-372-zq77";
        Requester requester = newRequester();
        String categoryToken = submitCategory(requester, null);
        String suggestionToken = submitSuggestion(requester);
        // application.yml pins com.beautica at INFO; open it to TRACE so a leak at ANY level is caught.
        Logger beautica = (Logger) LoggerFactory.getLogger("com.beautica");
        Level previous = beautica.getLevel();
        beautica.setLevel(Level.TRACE);
        try {
            post(CATEGORY_REJECT, categoryToken, sentinel);
            post(SUGGESTION_REJECT, suggestionToken, sentinel);
        } finally {
            beautica.setLevel(previous);
        }

        assertThat(output.getAll())
                .as("premise: com.beautica DEBUG decision logging is being captured")
                .contains("in-app notify type=CATEGORY_REQUEST_REJECTED")
                .contains("in-app notify type=SERVICE_TYPE_SUGGESTION_REJECTED");
        assertThat(output.getAll()).as("the reason is never logged").doesNotContain(sentinel);
        assertThat(feed(requester)).as("both items carry it to the requester instead").hasSize(2)
                .allSatisfy(item -> assertThat(item.path("params").path("decisionReason").asText()).isEqualTo(sentinel));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private Requester newIndependentMaster() throws Exception {
        String email = "decision-im-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures booking = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        booking.createIndependentMaster(email);
        UUID id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        return new Requester(id, email, booking.tokenFor(email));
    }

    private HttpHeaders jsonBearer(Requester requester) {
        HttpHeaders headers = fixtures().bearerHeaders(requester.token());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /** POSTs the category request through the real API as {@code requester}; returns the emailed review URL. */
    private String submitCategoryViaApi(Requester requester) throws Exception {
        ResponseEntity<String> submit = restTemplate.exchange("/api/v1/service-categories/requests", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        new CreateCategoryRequestRequest(CATEGORY_NAME, CATEGORY_DISPLAY, null)),
                        jsonBearer(requester)), String.class);
        assertThat(submit.getStatusCode()).as("body=%s", submit.getBody()).isEqualTo(HttpStatus.CREATED);
        return capturedCategoryReviewUrl();
    }

    /** The admin opens the emailed link: the review page renders with the optional reason textarea. */
    private void openEmailedLink(String reviewUrl) {
        URI uri = URI.create(reviewUrl);
        String pathAndQuery = uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery());
        ResponseEntity<String> page = restTemplate.getForEntity(pathAndQuery, String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("name=\"reason\"").contains("maxlength=\"300\"");
    }

    private String capturedCategoryReviewUrl() {
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendCategoryRequestNotification(
                anyString(), anyString(), anyString(), anyString(), url.capture());
        return url.getValue();
    }

    private String capturedSuggestionReviewUrl() {
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendServiceTypeSuggestionNotification(
                anyString(), anyString(), anyString(), anyString(), anyString(), url.capture());
        return url.getValue();
    }

    private ServiceTestFixtures fixtures() {
        return new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    @Test
    @DisplayName("Race — concurrent approve + reject on one category token: exactly one decides, "
            + "the other gets «Рішення вже прийнято», one feed item matching the winner")
    void should_writeOneNotification_when_approveAndRejectRace() throws Exception {
        Requester requester = newRequester();
        String categoryToken = submitCategory(requester, null);

        String winner = raceApproveAndReject(CATEGORY_APPROVE, CATEGORY_REJECT, categoryToken,
                "SELECT id FROM platform_categories WHERE name = ? FOR UPDATE", CATEGORY_NAME, "platform_categories");

        String expectedType = winner.contains("Категорію підтверджено")
                ? "CATEGORY_REQUEST_APPROVED" : "CATEGORY_REQUEST_REJECTED";
        JsonNode feed = feed(requester);
        assertThat(feed).as("exactly one category decision item").hasSize(1);
        assertThat(feed.get(0).path("type").asText()).as("the item is the WINNER's decision").isEqualTo(expectedType);
        assertThat(countFeedRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("Race — concurrent approve + reject on one suggestion token: exactly one decides, "
            + "the other gets «Рішення вже прийнято», one feed item matching the winner")
    void should_writeOneNotification_when_suggestionApproveAndRejectRace() throws Exception {
        Requester requester = newRequester();
        String suggestionToken = submitSuggestion(requester);

        String winner = raceApproveAndReject(SUGGESTION_APPROVE, SUGGESTION_REJECT, suggestionToken,
                "SELECT id FROM service_type_suggestion WHERE suggested_name = ? FOR UPDATE", SUGGESTED_TYPE,
                "service_type_suggestion");

        String expectedType = winner.contains("Пропозицію прийнято")
                ? "SERVICE_TYPE_SUGGESTION_APPROVED" : "SERVICE_TYPE_SUGGESTION_REJECTED";
        JsonNode feed = feed(requester);
        assertThat(feed).as("exactly one suggestion decision item").hasSize(1);
        assertThat(feed.get(0).path("type").asText()).as("the item is the WINNER's decision").isEqualTo(expectedType);
        assertThat(countFeedRows()).isEqualTo(1);
    }

    /**
     * Forces a TRUE overlap: a raw connection holds the request row's lock, both POSTs are launched and
     * must be observed blocked on a row lock in {@code pg_stat_activity} BEFORE the holder commits. Only
     * then is the outcome decided by the production lock ({@code findByTokenHashForUpdate}): the loser's
     * re-check fails on the cleared token_hash → ALREADY_DECIDED. Without the lock both read PENDING,
     * both UPDATE (queued behind the holder) and both decide — two decision pages, zero «already decided».
     *
     * @return the winner's response body
     */
    private String raceApproveAndReject(String approvePath, String rejectPath, String token,
                                        String lockRowSql, String rowKey, String table) throws Exception {
        Connection holder = jdbcTemplate.getDataSource().getConnection();
        holder.setAutoCommit(false);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            try (PreparedStatement ps = holder.prepareStatement(lockRowSql)) {
                ps.setString(1, rowKey);
                assertThat(ps.executeQuery().next()).as("premise: the request row exists and is locked").isTrue();
            }
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ResponseEntity<String>>> results = List.of(
                    pool.submit(() -> { start.await(); return post(approvePath, token, null); }),
                    pool.submit(() -> { start.await(); return post(rejectPath, token, null); }));
            start.countDown();

            assertThat(awaitRowLockWaiters(table, 2))
                    .as("both approve AND reject must be observably queued on the %s row lock — otherwise "
                            + "the two requests never overlapped and this test proves nothing", table)
                    .isTrue();
            holder.commit();

            List<String> bodies = new ArrayList<>();
            for (Future<ResponseEntity<String>> f : results) {
                ResponseEntity<String> response = f.get(30, TimeUnit.SECONDS);
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
                bodies.add(response.getBody());
            }
            assertThat(bodies)
                    .as("EXACTLY ONE racer must lose with the already-decided page")
                    .filteredOn(body -> body.contains(ALREADY_DECIDED_HEADING))
                    .hasSize(1);
            return bodies.stream().filter(body -> !body.contains(ALREADY_DECIDED_HEADING)).findFirst().orElseThrow();
        } finally {
            if (!holder.isClosed()) {
                holder.rollback();
                holder.close();
            }
            pool.shutdownNow();
        }
    }

    private boolean awaitRowLockWaiters(String table, int expected) {
        try {
            await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(50)).until(() -> {
                Long waiters = jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                                + "AND wait_event_type = 'Lock' AND pid <> pg_backend_pid() AND query ILIKE ?",
                        Long.class, "%" + table + "%");
                return waiters != null && waiters >= expected;
            });
            return true;
        } catch (ConditionTimeoutException e) {
            return false;
        }
    }

    private Requester newRequester() throws Exception {
        String email = "decision-" + System.nanoTime() + "@beautica.test";
        String token = fixtures().createSalonOwnerAndGetToken(email);
        UUID id = jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
        return new Requester(id, email, token);
    }

    private String submitCategory(Requester requester, String initialServiceName) {
        categoryRequestService.submitRequest(
                new CreateCategoryRequestRequest(CATEGORY_NAME, CATEGORY_DISPLAY, initialServiceName), requester.id());
        String url = capturedCategoryReviewUrl();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private String submitSuggestion(Requester requester) {
        suggestionService.submitSuggestionInternal(SEEDED_CATEGORY, SUGGESTED_TYPE, "decision IT", requester.id());
        String url = capturedSuggestionReviewUrl();
        return url.substring(url.indexOf("token=") + "token=".length());
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

    private String rawFeed(Requester requester) {
        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/notifications?size=20", HttpMethod.GET,
                new HttpEntity<>(fixtures().bearerHeaders(requester.token())), String.class);
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return resp.getBody();
    }

    private JsonNode feed(Requester requester) throws Exception {
        return objectMapper.readTree(rawFeed(requester)).path("data");
    }

    private long countFeedRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM in_app_notification", Long.class);
    }

    private String categoryDisplayName(String name) {
        return jdbcTemplate.queryForObject(
                "SELECT display_name FROM platform_categories WHERE name = ?", String.class, name);
    }
}
