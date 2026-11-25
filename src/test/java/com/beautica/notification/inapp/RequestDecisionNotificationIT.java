package com.beautica.notification.inapp;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.catalog.admin.AdminActor;
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
import org.springframework.test.context.TestPropertySource;

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
 * Phase 372 — the platform operator's approve/reject writes ONE in-app feed item for the REQUESTER only, in the
 * decision's transaction. Since B545 the catalog portal is the only decision path, so every decision here goes through
 * the real internal catalog API ({@code POST /api/v1/internal/catalog/requests/{kind}/{id}/approve|reject}); every
 * assertion reads the real {@code GET /api/v1/notifications}.
 */
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = "app.internal-catalog-key=" + RequestDecisionNotificationIT.CATALOG_KEY)
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Phase 372 — request-decision feed items (category request + service-type suggestion)")
class RequestDecisionNotificationIT extends AbstractIntegrationTest {

    static final String CATALOG_KEY = "test-internal-catalog-key-dddddddddddddddd";
    private static final String BASE = "/api/v1/internal/catalog/requests";
    private static final String ACTOR = "beautica.ua@gmail.com";
    private static final String CATEGORY_APPROVE = "CATEGORY/approve";
    private static final String CATEGORY_REJECT = "CATEGORY/reject";
    private static final String SUGGESTION_APPROVE = "SERVICE_TYPE/approve";
    private static final String SUGGESTION_REJECT = "SERVICE_TYPE/reject";
    /** test profile {@code app.frontend.base-url} + the portal requests page (B545 operator email link). */
    private static final String PORTAL_URL = "http://localhost:3000/admin/requests";

    // HAIRDRESSING is seeded APPROVED + active and mapped to a System-A bucket (V74/V78).
    private static final String SEEDED_CATEGORY = "HAIRDRESSING";
    private static final String CATEGORY_DISPLAY = "Тест-категорія Рішення";
    private static final String CATEGORY_NAME = "ITDECISION_CAT";
    private static final String SUGGESTED_TYPE = "Qzdecision Ritual Plumbix";
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
        String requestId = submitCategory(requester, null);

        ResponseEntity<String> decision = post(CATEGORY_APPROVE, requestId, null);

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
    @DisplayName("Category rejected with a reason — the item carries the TRIMMED reason, stored on the request")
    void should_writeRejectedItemWithReason_when_categoryRejectedWithReason() throws Exception {
        Requester requester = newRequester();
        String requestId = submitCategory(requester, null);

        ResponseEntity<String> decision = post(CATEGORY_REJECT, requestId, "  " + REASON + "  ");

        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode item = feed(requester).get(0);
        assertThat(item.path("type").asText()).isEqualTo("CATEGORY_REQUEST_REJECTED");
        assertThat(item.path("target").path("kind").asText()).isEqualTo("SERVICE_CATALOG");
        assertThat(item.path("params").path("categoryName").asText()).isEqualTo(CATEGORY_DISPLAY);
        assertThat(item.path("params").path("decisionReason").asText()).as("trimmed").isEqualTo(REASON);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT decision_reason FROM platform_categories WHERE name = ?", String.class, CATEGORY_NAME))
                .isEqualTo(REASON);
    }

    @Test
    @DisplayName("Category rejected with a whitespace-only reason — the item and the request store no reason")
    void should_writeRejectedItemWithoutReason_when_reasonBlank() throws Exception {
        Requester requester = newRequester();
        String requestId = submitCategory(requester, null);

        post(CATEGORY_REJECT, requestId, "   \n\t ");

        JsonNode item = feed(requester).get(0);
        assertThat(item.path("type").asText()).isEqualTo("CATEGORY_REQUEST_REJECTED");
        assertThat(item.path("params").path("decisionReason").isNull()).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT decision_reason FROM platform_categories WHERE name = ?", String.class, CATEGORY_NAME))
                .isNull();
    }

    @Test
    @DisplayName("Category reject with a 301-char reason — 400, request stays PENDING, no item")
    void should_rejectOverlongReasonWithoutDeciding_when_reasonExceeds300() throws Exception {
        Requester requester = newRequester();
        String requestId = submitCategory(requester, null);

        ResponseEntity<String> decision = post(CATEGORY_REJECT, requestId, "ж".repeat(301));

        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM platform_categories WHERE name = ?", String.class, CATEGORY_NAME))
                .isEqualTo("PENDING");
        assertThat(feed(requester)).isEmpty();
    }

    @Test
    @DisplayName("Category reject with a 300-char reason containing markup — accepted, verbatim in the feed")
    void should_acceptReasonOfExactly300_when_rejected() throws Exception {
        Requester requester = newRequester();
        String requestId = submitCategory(requester, null);
        String boundary = "<b>x</b>" + "ж".repeat(292);

        ResponseEntity<String> decision = post(CATEGORY_REJECT, requestId, boundary);

        assertThat(boundary).hasSize(300);
        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(feed(requester).get(0).path("params").path("decisionReason").asText()).isEqualTo(boundary);
    }

    @Test
    @DisplayName("Category decided twice — the second gets 409 REQUEST_NOT_PENDING, still exactly one item")
    void should_writeNoSecondItem_when_decidedTwice() throws Exception {
        Requester requester = newRequester();
        String requestId = submitCategory(requester, null);

        post(CATEGORY_REJECT, requestId, REASON);
        ResponseEntity<String> replay = post(CATEGORY_APPROVE, requestId, null);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(feed(requester)).hasSize(1);
        assertThat(feed(requester).get(0).path("type").asText()).isEqualTo("CATEGORY_REQUEST_REJECTED");
    }

    @Test
    @DisplayName("Category request with no requester (legacy/deleted user) — the decision commits, no item is written")
    void should_commitDecisionAndWriteNothing_when_requesterNull() throws Exception {
        Requester requester = newRequester();
        String requestId = submitCategory(requester, null);
        jdbcTemplate.update("UPDATE platform_categories SET requested_by_user_id = NULL WHERE name = ?", CATEGORY_NAME);

        ResponseEntity<String> decision = post(CATEGORY_REJECT, requestId, REASON);

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
        String requestId = submitCategory(requester, "Qzdecision Initial Service");

        post(CATEGORY_APPROVE, requestId, null);

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
        String requestId = submitSuggestion(requester);

        ResponseEntity<String> decision = post(SUGGESTION_APPROVE, requestId, null);

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
        String requestId = submitSuggestion(requester);

        post(SUGGESTION_REJECT, requestId, REASON);

        JsonNode item = feed(requester).get(0);
        assertThat(item.path("type").asText()).isEqualTo("SERVICE_TYPE_SUGGESTION_REJECTED");
        assertThat(item.path("params").path("serviceName").asText()).isEqualTo(SUGGESTED_TYPE);
        assertThat(item.path("params").path("decisionReason").asText()).isEqualTo(REASON);
    }

    @Test
    @DisplayName("Suggestion rejected with an empty reason — the item has no reason")
    void should_writeRejectedItemWithoutReason_when_suggestionReasonBlank() throws Exception {
        Requester requester = newRequester();
        String requestId = submitSuggestion(requester);

        post(SUGGESTION_REJECT, requestId, "");

        JsonNode item = feed(requester).get(0);
        assertThat(item.path("type").asText()).isEqualTo("SERVICE_TYPE_SUGGESTION_REJECTED");
        assertThat(item.path("params").path("decisionReason").isNull()).isTrue();
    }

    @Test
    @DisplayName("Suggestion reject with a 301-char reason — 400, suggestion stays PENDING, no item")
    void should_rejectOverlongReasonWithoutDeciding_when_suggestionReasonExceeds300() throws Exception {
        Requester requester = newRequester();
        String requestId = submitSuggestion(requester);

        ResponseEntity<String> decision = post(SUGGESTION_REJECT, requestId, "ж".repeat(301));

        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM service_type_suggestion WHERE suggested_name = ?", String.class, SUGGESTED_TYPE))
                .isEqualTo("PENDING");
        assertThat(feed(requester)).isEmpty();
    }

    @Test
    @DisplayName("Suggestion decided three times (approve, approve, reject) — still exactly one item")
    void should_writeNoSecondItem_when_suggestionDecidedRepeatedly() throws Exception {
        Requester requester = newRequester();
        String requestId = submitSuggestion(requester);

        post(SUGGESTION_APPROVE, requestId, null);
        post(SUGGESTION_APPROVE, requestId, null);
        post(SUGGESTION_REJECT, requestId, REASON);

        assertThat(feed(requester)).hasSize(1);
    }

    @Test
    @DisplayName("Suggestion with no requester — the decision commits, no item is written")
    void should_commitDecisionAndWriteNothing_when_suggestionRequesterNull() throws Exception {
        Requester requester = newRequester();
        String requestId = submitSuggestion(requester);
        jdbcTemplate.update("UPDATE service_type_suggestion SET requested_by_user_id = NULL "
                + "WHERE suggested_name = ?", SUGGESTED_TYPE);

        post(SUGGESTION_REJECT, requestId, REASON);

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
            String requestId = submitCategory(requester, null);

            ResponseEntity<String> decision = post(CATEGORY_REJECT, requestId, REASON);

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
        String requestId = submitSuggestion(requester);

        post(SUGGESTION_REJECT, requestId, REASON);

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

    // ── end-to-end: real submit API → portal-link email → portal decision → requester's feed ──

    @Test
    @DisplayName("E2E — INDEPENDENT_MASTER submits a category request over the API, the operator email links to the "
            + "portal, the portal approves: the master's GET /notifications shows CATEGORY_REQUEST_APPROVED")
    void should_showApprovedItemInMastersFeed_when_categoryRequestedViaApiAndApprovedInPortal() throws Exception {
        Requester master = newIndependentMaster();
        String requestId = submitCategoryViaApi(master);

        ResponseEntity<String> decision = post(CATEGORY_APPROVE, requestId, null);

        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode feed = feed(master);
        assertThat(feed).hasSize(1);
        JsonNode item = feed.get(0);
        assertThat(item.path("type").asText()).isEqualTo("CATEGORY_REQUEST_APPROVED");
        assertThat(item.path("target").path("kind").asText()).isEqualTo("SERVICE_CATALOG");
        assertThat(item.path("params").path("categoryName").asText()).isEqualTo(CATEGORY_DISPLAY);
        assertThat(item.path("params").path("decisionReason").isNull()).as("approve never carries a reason").isTrue();
    }

    @Test
    @DisplayName("E2E — INDEPENDENT_MASTER's API category request rejected with a reason in the portal: "
            + "the master's feed shows CATEGORY_REQUEST_REJECTED with categoryName and the reason")
    void should_showRejectedItemWithReasonInMastersFeed_when_categoryRequestedViaApiAndRejected() throws Exception {
        Requester master = newIndependentMaster();
        String requestId = submitCategoryViaApi(master);

        post(CATEGORY_REJECT, requestId, REASON);

        JsonNode item = feed(master).get(0);
        assertThat(item.path("type").asText()).isEqualTo("CATEGORY_REQUEST_REJECTED");
        assertThat(item.path("target").path("kind").asText()).isEqualTo("SERVICE_CATALOG");
        assertThat(item.path("params").path("categoryName").asText()).isEqualTo(CATEGORY_DISPLAY);
        assertThat(item.path("params").path("decisionReason").asText()).isEqualTo(REASON);
    }

    @Test
    @DisplayName("E2E — INDEPENDENT_MASTER suggests a service type over the API, the portal rejects with a "
            + "reason: feed shows SERVICE_TYPE_SUGGESTION_REJECTED, serviceName, categoryName, reason")
    void should_showRejectedSuggestionWithReasonInMastersFeed_when_suggestedViaApiAndRejected() throws Exception {
        Requester master = newIndependentMaster();
        ResponseEntity<String> submit = restTemplate.exchange("/api/v1/service-types/suggest", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        new SuggestServiceTypeRequest(SUGGESTED_TYPE, SEEDED_CATEGORY, "decision e2e")),
                        jsonBearer(master)), String.class);
        assertThat(submit.getStatusCode()).as("body=%s", submit.getBody()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(capturedSuggestionPortalUrl()).isEqualTo(PORTAL_URL);
        String requestId = suggestionId();

        post(SUGGESTION_REJECT, requestId, REASON);

        JsonNode item = feed(master).get(0);
        assertThat(item.path("type").asText()).isEqualTo("SERVICE_TYPE_SUGGESTION_REJECTED");
        assertThat(item.path("target").path("kind").asText()).isEqualTo("SERVICE_CATALOG");
        assertThat(item.path("params").path("serviceName").asText()).isEqualTo(SUGGESTED_TYPE);
        assertThat(item.path("params").path("categoryName").asText()).isEqualTo(categoryDisplayName(SEEDED_CATEGORY));
        assertThat(item.path("params").path("decisionReason").asText()).isEqualTo(REASON);
    }

    @Test
    @DisplayName("The admin's reject reason never reaches the logs (category + suggestion), while the decision "
            + "itself IS logged — proves the capture is live, not vacuous")
    void should_neverLogReason_when_bothRequestKindsRejectedWithReason(CapturedOutput output) throws Exception {
        String sentinel = "SENTINEL-REASON-372-zq77";
        Requester requester = newRequester();
        String categoryRequestId = submitCategory(requester, null);
        String suggestionRequestId = submitSuggestion(requester);
        // application.yml pins com.beautica at INFO; open it to TRACE so a leak at ANY level is caught.
        Logger beautica = (Logger) LoggerFactory.getLogger("com.beautica");
        Level previous = beautica.getLevel();
        beautica.setLevel(Level.TRACE);
        try {
            post(CATEGORY_REJECT, categoryRequestId, sentinel);
            post(SUGGESTION_REJECT, suggestionRequestId, sentinel);
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

    /** POSTs the category request through the real API as {@code requester}; returns the request id. */
    private String submitCategoryViaApi(Requester requester) throws Exception {
        ResponseEntity<String> submit = restTemplate.exchange("/api/v1/service-categories/requests", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        new CreateCategoryRequestRequest(CATEGORY_NAME, CATEGORY_DISPLAY, null)),
                        jsonBearer(requester)), String.class);
        assertThat(submit.getStatusCode()).as("body=%s", submit.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(capturedCategoryPortalUrl()).isEqualTo(PORTAL_URL);
        return categoryId();
    }

    private String capturedCategoryPortalUrl() {
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendCategoryRequestNotification(
                anyString(), anyString(), anyString(), anyString(), url.capture());
        return url.getValue();
    }

    private String capturedSuggestionPortalUrl() {
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendServiceTypeSuggestionNotification(
                anyString(), anyString(), anyString(), anyString(), anyString(), url.capture());
        return url.getValue();
    }

    private ServiceTestFixtures fixtures() {
        return new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    @Test
    @DisplayName("Race — concurrent approve + reject on one category: exactly one decides, "
            + "the other gets 409, one feed item matching the winner")
    void should_writeOneNotification_when_approveAndRejectRace() throws Exception {
        Requester requester = newRequester();
        String categoryRequestId = submitCategory(requester, null);

        String winner = raceApproveAndReject(CATEGORY_APPROVE, CATEGORY_REJECT, categoryRequestId,
                "SELECT id FROM platform_categories WHERE name = ? FOR UPDATE", CATEGORY_NAME, "platform_categories");

        String expectedType = CATEGORY_APPROVE.equals(winner)
                ? "CATEGORY_REQUEST_APPROVED" : "CATEGORY_REQUEST_REJECTED";
        JsonNode feed = feed(requester);
        assertThat(feed).as("exactly one category decision item").hasSize(1);
        assertThat(feed.get(0).path("type").asText()).as("the item is the WINNER's decision").isEqualTo(expectedType);
        assertThat(countFeedRows()).isEqualTo(1);
    }

    @Test
    @DisplayName("Race — concurrent approve + reject on one suggestion: exactly one decides, "
            + "the other gets 409, one feed item matching the winner")
    void should_writeOneNotification_when_suggestionApproveAndRejectRace() throws Exception {
        Requester requester = newRequester();
        String suggestionRequestId = submitSuggestion(requester);

        String winner = raceApproveAndReject(SUGGESTION_APPROVE, SUGGESTION_REJECT, suggestionRequestId,
                "SELECT id FROM service_type_suggestion WHERE suggested_name = ? FOR UPDATE", SUGGESTED_TYPE,
                "service_type_suggestion");

        String expectedType = SUGGESTION_APPROVE.equals(winner)
                ? "SERVICE_TYPE_SUGGESTION_APPROVED" : "SERVICE_TYPE_SUGGESTION_REJECTED";
        JsonNode feed = feed(requester);
        assertThat(feed).as("exactly one suggestion decision item").hasSize(1);
        assertThat(feed.get(0).path("type").asText()).as("the item is the WINNER's decision").isEqualTo(expectedType);
        assertThat(countFeedRows()).isEqualTo(1);
    }

    /**
     * Forces a TRUE overlap: a raw connection holds the request row's lock, both POSTs are launched and
     * must be observed blocked on a row lock in {@code pg_stat_activity} BEFORE the holder commits. Only
     * then is the outcome decided by the production lock ({@code findByIdForUpdate}): the loser re-reads a
     * decided row → 409 REQUEST_NOT_PENDING. Without the lock both read PENDING and both decide.
     *
     * @return the winner's decision path ({@code approvePath} or {@code rejectPath})
     */
    private String raceApproveAndReject(String approvePath, String rejectPath, String requestId,
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
                    pool.submit(() -> { start.await(); return post(approvePath, requestId, null); }),
                    pool.submit(() -> { start.await(); return post(rejectPath, requestId, null); }));
            start.countDown();

            assertThat(awaitRowLockWaiters(table, 2))
                    .as("both approve AND reject must be observably queued on the %s row lock — otherwise "
                            + "the two requests never overlapped and this test proves nothing", table)
                    .isTrue();
            holder.commit();

            List<HttpStatus> statuses = new ArrayList<>();
            for (Future<ResponseEntity<String>> f : results) {
                statuses.add(HttpStatus.valueOf(f.get(30, TimeUnit.SECONDS).getStatusCode().value()));
            }
            assertThat(statuses)
                    .as("EXACTLY ONE racer decides, the other loses with 409 REQUEST_NOT_PENDING")
                    .containsExactlyInAnyOrder(HttpStatus.OK, HttpStatus.CONFLICT);
            return statuses.get(0) == HttpStatus.OK ? approvePath : rejectPath;
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
        assertThat(capturedCategoryPortalUrl()).isEqualTo(PORTAL_URL);
        return categoryId();
    }

    private String categoryId() {
        return String.valueOf(jdbcTemplate.queryForObject(
                "SELECT id FROM platform_categories WHERE name = ?", Long.class, CATEGORY_NAME));
    }

    private String suggestionId() {
        return String.valueOf(jdbcTemplate.queryForObject(
                "SELECT id FROM service_type_suggestion WHERE suggested_name = ?", UUID.class, SUGGESTED_TYPE));
    }

    private String submitSuggestion(Requester requester) {
        suggestionService.submitSuggestionInternal(SEEDED_CATEGORY, SUGGESTED_TYPE, "decision IT", requester.id());
        assertThat(capturedSuggestionPortalUrl()).isEqualTo(PORTAL_URL);
        return suggestionId();
    }

    /** The portal decision: {@code action} is {@code KIND/approve|reject}, {@code id} the request id. */
    private ResponseEntity<String> post(String action, String id, String reason) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", CATALOG_KEY);
        headers.set(AdminActor.HEADER, ACTOR);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String[] kindAndVerb = action.split("/");
        String body = reason == null ? null : objectMapper.writeValueAsString(java.util.Map.of("reason", reason));
        return restTemplate.postForEntity(BASE + "/" + kindAndVerb[0] + "/" + id + "/" + kindAndVerb[1],
                new HttpEntity<>(body, headers), String.class);
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
