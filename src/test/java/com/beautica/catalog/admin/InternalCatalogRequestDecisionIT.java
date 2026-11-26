package com.beautica.catalog.admin;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.catalog.admin.CatalogRequestFixture.Seeded;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.service.RequestKind;
import com.beautica.support.MockedMailSender;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.beautica.notification.RequestDecisionEmailExpectedCopy.reasonLine;
import static com.beautica.support.MimeMessageBodies.htmlBodyOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * B503 (SVC-06) — {@code POST /api/v1/internal/catalog/requests/{kind}/{id}/approve|reject} through the REAL chain
 * (Tomcat, Security, {@code InternalApiKeyFilter} with the catalog-scoped key, MVC) on Postgres. Since B545 the portal
 * is the only decision path (the emailed links answer 410, {@code RetiredRequestLinksIT}).
 */
@DisplayName("InternalCatalogRequestController (IT) — approve / reject by id")
@TestPropertySource(properties = "app.internal-catalog-key=" + InternalCatalogRequestDecisionIT.CATALOG_KEY)
class InternalCatalogRequestDecisionIT extends AbstractIntegrationTest {

    static final String CATALOG_KEY = "test-internal-catalog-key-dddddddddddddddd";
    private static final String BASE = "/api/v1/internal/catalog/requests";
    private static final String ACTOR = "beautica.ua@gmail.com";
    /** Seeded APPROVED + active and mapped to a System-A bucket (V74/V78). */
    private static final String SEEDED_CATEGORY = "HAIRDRESSING";
    private static final String REQUESTER_EMAIL = "it503-requester@beautica.test";
    private static final String REQUESTER_PHONE = "+380501115030";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private NotificationOutboxDrainWorker drainWorker;
    @Autowired private CatalogRequestAdminService adminService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private SpringTemplateEngine templateEngine;

    @Value("${app.invite.from-email:noreply@beautica.app}")
    private String fromAddress;

    private CatalogRequestFixture fixture;

    @BeforeEach
    void createFixture() {
        fixture = new CatalogRequestFixture(jdbcTemplate, passwordEncoder);
    }

    @AfterEach
    void removeSeededCatalogRows() {
        fixture.cleanup();
    }

    // ── seeding (shared SQL in CatalogRequestFixture) ──────────────────────────

    private UUID insertRequester() {
        return insertRequester(REQUESTER_EMAIL, REQUESTER_PHONE);
    }

    private UUID insertRequester(String email, String phone) {
        return fixture.insertUser(email, "INDEPENDENT_MASTER", "Ірина", "Майстер", phone);
    }

    private Seeded<UUID> seedSuggestion(String name, UUID requester) {
        return new Seeded<>(fixture.pendingSuggestion(SEEDED_CATEGORY, name, requester, null));
    }

    private Seeded<Long> seedCategory(String name, String displayName, UUID requester) {
        return seedCategory(name, displayName, requester, null);
    }

    private Seeded<Long> seedCategory(String name, String displayName, UUID requester, String initialServiceName) {
        return new Seeded<>(fixture.pendingCategory(name, displayName, requester, initialServiceName, null));
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    private ResponseEntity<String> decide(String kind, Object id, String action, String key, String actor, String json) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.set("X-Internal-Key", key);
        }
        if (actor != null) {
            headers.set(AdminActor.HEADER, actor);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(BASE + "/" + kind + "/" + id + "/" + action, HttpMethod.POST,
                new HttpEntity<>(json, headers), String.class);
    }

    /** B502 queue: the ids of the {@code status} page (size 100), in the order the portal shows them. */
    private List<String> queueIds(String status) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", CATALOG_KEY);
        ResponseEntity<String> response = restTemplate.exchange(BASE + "?status=" + status + "&size=100",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        List<String> ids = new ArrayList<>();
        objectMapper.readTree(response.getBody()).path("data").path("data").forEach(n -> ids.add(n.path("id").asText()));
        return ids;
    }

    /** B502 name edit over HTTP. */
    private ResponseEntity<String> editName(String kind, Object id, Map<String, String> body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", CATALOG_KEY);
        headers.set(AdminActor.HEADER, ACTOR);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(BASE + "/" + kind + "/" + id, HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
    }

    /** A public catalogue read as the app makes it (bearer optional). */
    private String publicRead(String path, UUID bearerUser) {
        HttpHeaders headers = new HttpHeaders();
        if (bearerUser != null) {
            headers.setBearerAuth(jwtTokenProvider.generateAccessToken(bearerUser, REQUESTER_EMAIL,
                    Role.INDEPENDENT_MASTER));
        }
        ResponseEntity<String> response = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers),
                String.class);
        assertThat(response.getStatusCode()).as("GET %s body: %s", path, response.getBody()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private List<Map<String, Object>> inAppRows() {
        return jdbcTemplate.queryForList("SELECT recipient_user_id, type, category_request_id, "
                + "service_type_suggestion_id FROM in_app_notification");
    }

    private ResponseEntity<String> approve(String kind, Object id) {
        return decide(kind, id, "approve", CATALOG_KEY, ACTOR, null);
    }

    private ResponseEntity<String> reject(String kind, Object id, String json) {
        return decide(kind, id, "reject", CATALOG_KEY, ACTOR, json);
    }

    private void assertOk(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("success").asBoolean()).isTrue();
        assertThat(body.path("data").isNull()).isTrue();
    }

    private void assertRequestNotPending(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).startsWith(
                "{\"success\":false,\"data\":{\"code\":\"REQUEST_NOT_PENDING\"},\"message\":\"Request is no longer pending\"");
    }

    private long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private long decisionRows(RequestKind kind, String requestId) {
        return count("SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'REQUEST_DECIDED' "
                + "AND payload LIKE ? AND payload LIKE ?",
                "%\"kind\":\"" + kind.name() + "\"%", "%\"requestId\":\"" + requestId + "\"%");
    }

    private long typeRows(String name) {
        return count("SELECT COUNT(*) FROM service_types WHERE name_uk = ? AND platform_category_name = ?",
                name, SEEDED_CATEGORY);
    }

    private Map<String, Object> onlyAuditRow(String targetId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT actor, action, target_type, "
                + "before::text AS b, after::text AS a FROM catalog_admin_audit WHERE target_id = ?", targetId);
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private Map<String, Object> jsonMap(Object json) throws Exception {
        return objectMapper.readValue((String) json, new TypeReference<>() {});
    }

    // ── approve ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("approve SERVICE_TYPE → service_types row, exactly one REQUEST_DECIDED row, APPROVE audit")
    void should_promoteAndEnqueueOneDecision_when_suggestionApprovedById() throws Exception {
        UUID requester = insertRequester();
        Seeded<UUID> row = seedSuggestion("IT503 Ритуал схвалення", requester);

        ResponseEntity<String> response = approve("SERVICE_TYPE", row.id());

        assertOk(response);
        assertThat(typeRows("IT503 Ритуал схвалення")).isEqualTo(1L);
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT status, decided_at FROM service_type_suggestion WHERE id = ?", row.id());
        assertThat(stored.get("status")).isEqualTo("APPROVED");
        assertThat(stored.get("decided_at")).isNotNull();
        assertThat(decisionRows(RequestKind.SERVICE_TYPE_SUGGESTION, row.id().toString())).isEqualTo(1L);
        Map<String, Object> audit = onlyAuditRow(row.id().toString());
        assertThat(audit.get("actor")).isEqualTo(ACTOR);
        assertThat(audit.get("action")).isEqualTo("APPROVE");
        assertThat(audit.get("target_type")).isEqualTo("TYPE_SUGGESTION");
        assertThat(jsonMap(audit.get("b"))).containsExactly(Map.entry("status", "PENDING"));
        assertThat(jsonMap(audit.get("a"))).containsExactly(Map.entry("status", "APPROVED"));
    }

    @Test
    @DisplayName("approve CATEGORY → APPROVED + active, and GET /service-categories/approved shows it on the next call")
    void should_showApprovedCategoryOnNextCall_when_categoryApprovedById() throws Exception {
        UUID requester = insertRequester();
        Seeded<Long> row = seedCategory("IT503_CAT_APPROVE", "Категорія IT503", requester);
        HttpHeaders auth = new HttpHeaders();
        auth.setBearerAuth(jwtTokenProvider.generateAccessToken(requester, REQUESTER_EMAIL, Role.INDEPENDENT_MASTER));
        ResponseEntity<String> before = restTemplate.exchange("/api/v1/service-categories/approved", HttpMethod.GET,
                new HttpEntity<>(auth), String.class);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(before.getBody()).as("cache warmed without the pending row").doesNotContain("IT503_CAT_APPROVE");

        ResponseEntity<String> response = approve("CATEGORY", row.id());
        ResponseEntity<String> after = restTemplate.exchange("/api/v1/service-categories/approved", HttpMethod.GET,
                new HttpEntity<>(auth), String.class);

        assertOk(response);
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT status, active FROM platform_categories WHERE id = ?", row.id());
        assertThat(stored.get("status")).isEqualTo("APPROVED");
        assertThat(stored.get("active")).isEqualTo(true);
        assertThat(after.getBody()).as("cache evicted after commit").contains("IT503_CAT_APPROVE");
        assertThat(decisionRows(RequestKind.CATEGORY_REQUEST, String.valueOf(row.id()))).isEqualTo(1L);
        assertThat(onlyAuditRow(String.valueOf(row.id())).get("target_type")).isEqualTo("CATEGORY_REQUEST");
    }

    /**
     * The carried initial service name becomes a PENDING suggestion in its own REQUIRES_NEW transaction, started from
     * inside the admin transaction (which holds the category row lock and the audit insert). It must commit alongside,
     * not deadlock or vanish.
     */
    @Test
    @DisplayName("approve CATEGORY with an initial service name → one pending suggestion, one decision e-mail, one audit row")
    void should_spawnOnePendingSuggestion_when_categoryWithInitialServiceApprovedById() throws Exception {
        UUID requester = insertRequester();
        Seeded<Long> row = seedCategory("IT503_CAT_INITIAL", "Початкова IT503", requester, "Перша послуга IT503");

        ResponseEntity<String> response = approve("CATEGORY", row.id());

        assertOk(response);
        List<Map<String, Object>> spawned = jdbcTemplate.queryForList("SELECT suggested_name, status, "
                + "requested_by_user_id FROM service_type_suggestion WHERE category_name = ?", "IT503_CAT_INITIAL");
        assertThat(spawned).hasSize(1);
        assertThat(spawned.get(0).get("suggested_name")).isEqualTo("Перша послуга IT503");
        assertThat(spawned.get(0).get("status")).isEqualTo("PENDING");
        assertThat(spawned.get(0).get("requested_by_user_id")).isEqualTo(requester);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM platform_categories WHERE id = ?", String.class,
                row.id())).isEqualTo("APPROVED");
        assertThat(decisionRows(RequestKind.CATEGORY_REQUEST, String.valueOf(row.id()))).isEqualTo(1L);
        assertThat(onlyAuditRow(String.valueOf(row.id())).get("action")).isEqualTo("APPROVE");
    }

    /**
     * B545 expand/contract (V266): during the Railway deploy overlap a pre-B545 pod can still mint a request WITH a
     * token; a B545 pod no longer maps the token columns and leaves the hash on the decided row. V266 dropped the
     * token-pending CHECKs, so that decision commits instead of failing on the CHECK (falsified by re-adding the
     * CHECK: approve → 500).
     */
    @Test
    @DisplayName("deploy overlap: a request minted with a token by a pre-B545 pod is still approved in the portal")
    void should_approve_when_pendingRowStillCarriesLegacyToken() throws Exception {
        UUID requester = insertRequester();
        Seeded<Long> category = seedCategory("IT545_CAT_LEGACY_TOKEN", "Стара заявка IT545", requester);
        Seeded<UUID> suggestion = seedSuggestion("IT545 Ритуал старий токен", requester);
        jdbcTemplate.update("UPDATE platform_categories SET token_hash = ?, token_expires_at = now() + interval '1 day' "
                + "WHERE id = ?", "a".repeat(64), category.id());
        jdbcTemplate.update("UPDATE service_type_suggestion SET token_hash = ?, "
                + "token_expires_at = now() + interval '1 day' WHERE id = ?", "b".repeat(64), suggestion.id());

        ResponseEntity<String> categoryResponse = approve("CATEGORY", category.id());
        ResponseEntity<String> suggestionResponse = approve("SERVICE_TYPE", suggestion.id());

        assertOk(categoryResponse);
        assertOk(suggestionResponse);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM platform_categories WHERE id = ?", String.class,
                category.id())).isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM service_type_suggestion WHERE id = ?",
                String.class, suggestion.id())).isEqualTo("APPROVED");
    }

    // ── reject ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("reject with a reason → cleaned reason reaches the drained decision e-mail, nothing promoted")
    void should_putReasonInDecisionEmail_when_rejectedWithReason() throws Exception {
        UUID requester = insertRequester();
        Seeded<UUID> row = seedSuggestion("IT503 Ритуал відмови", requester);
        String cleaned = "Така послуга вже є — оберіть існуючу";

        ResponseEntity<String> response = reject("SERVICE_TYPE", row.id(),
                objectMapper.writeValueAsString(Map.of("reason", "  Така послуга вже є\n— оберіть існуючу ")));
        drainWorker.drain();

        assertOk(response);
        assertThat(typeRows("IT503 Ритуал відмови")).isZero();
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT status, decision_reason FROM service_type_suggestion WHERE id = ?", row.id());
        assertThat(stored.get("status")).isEqualTo("REJECTED");
        assertThat(stored.get("decision_reason")).isEqualTo(cleaned);
        verify(emailNotificationService).sendRequestDecisionEmail(eq(REQUESTER_EMAIL),
                eq(RequestKind.SERVICE_TYPE_SUGGESTION), eq(false), eq("IT503 Ритуал відмови"), eq(cleaned));
        Map<String, Object> audit = onlyAuditRow(row.id().toString());
        assertThat(audit.get("action")).isEqualTo("REJECT");
        assertThat(jsonMap(audit.get("a"))).as("append-only audit keeps no free text")
                .containsOnly(Map.entry("status", "REJECTED"), Map.entry("reasonPresent", true));
        assertThat((String) audit.get("a") + audit.get("b"))
                .doesNotContain(cleaned, "Така послуга", REQUESTER_EMAIL, REQUESTER_PHONE);
    }

    @Test
    @DisplayName("reject CATEGORY with no body → REJECTED, inactive, no reason")
    void should_rejectCategoryWithoutReason_when_noBody() throws Exception {
        Seeded<Long> row = seedCategory("IT503_CAT_REJECT", "Відмова IT503", insertRequester());

        ResponseEntity<String> response = reject("CATEGORY", row.id(), null);

        assertOk(response);
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT status, active, decision_reason FROM platform_categories WHERE id = ?", row.id());
        assertThat(stored.get("status")).isEqualTo("REJECTED");
        assertThat(stored.get("active")).isEqualTo(false);
        assertThat(stored.get("decision_reason")).isNull();
    }

    @Test
    @DisplayName("reject with a 301-code-point reason → 400, nothing decided")
    void should_return400AndKeepPending_when_reasonTooLong() throws Exception {
        Seeded<UUID> row = seedSuggestion("IT503 Ритуал задовгий", null);

        ResponseEntity<String> response = reject("SERVICE_TYPE", row.id(),
                objectMapper.writeValueAsString(Map.of("reason", "ж".repeat(301))));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM service_type_suggestion WHERE id = ?",
                String.class, row.id())).isEqualTo("PENDING");
        assertThat(count("SELECT COUNT(*) FROM catalog_admin_audit")).isZero();
    }

    // ── first decision wins ────────────────────────────────────────────────────

    @Test
    @DisplayName("a second decision → 409 data.code REQUEST_NOT_PENDING; still one outbox row and one audit row")
    void should_return409_when_decidedTwice() throws Exception {
        Seeded<UUID> row = seedSuggestion("IT503 Ритуал двічі", insertRequester());

        ResponseEntity<String> first = approve("SERVICE_TYPE", row.id());
        ResponseEntity<String> second = reject("SERVICE_TYPE", row.id(), null);

        assertOk(first);
        assertRequestNotPending(second);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM service_type_suggestion WHERE id = ?",
                String.class, row.id())).isEqualTo("APPROVED");
        assertThat(decisionRows(RequestKind.SERVICE_TYPE_SUGGESTION, row.id().toString())).isEqualTo(1L);
        onlyAuditRow(row.id().toString());
    }

    /**
     * Two approves of one id: the first holds the {@code FOR UPDATE} row lock; the parallel HTTP approve waits, then
     * sees APPROVED → 409. Falsified by swapping {@code findByIdForUpdate} for an unlocked load in
     * {@code approveById}: the contender then double-decides (second promotion / 500) and this test fails.
     */
    @Test
    @DisplayName("two parallel approves of one id → one promotion, one outbox row, the loser gets 409")
    void should_promoteOnceAndReturn409_when_twoApprovesRace() throws Exception {
        Seeded<UUID> row = seedSuggestion("IT503 Ритуал гонка", insertRequester());

        ResponseEntity<String> loser = new HeldTransactionRace(transactionManager, jdbcTemplate).run(
                () -> adminService.approve(AdminActor.of(ACTOR), CatalogRequestKind.SERVICE_TYPE, row.id().toString()),
                () -> approve("SERVICE_TYPE", row.id()));

        assertRequestNotPending(loser);
        assertThat(typeRows("IT503 Ритуал гонка")).isEqualTo(1L);
        assertThat(decisionRows(RequestKind.SERVICE_TYPE_SUGGESTION, row.id().toString())).isEqualTo(1L);
        onlyAuditRow(row.id().toString());
    }

    // ── full portal lifecycle (B502 list + edit → B503 decide → public catalogue) ─────────────

    /**
     * The public {@code GET /service-types} list is cached ({@code service-types} 'ALL') and warmed BEFORE the decision,
     * so the edited name showing up afterwards proves the portal approve's {@code evictPlatformCatalog} reached the cache the app
     * reads (falsified by dropping {@code cacheEvictor.evictPlatformCatalog()} from {@code CatalogRequestAdminService#approve}).
     */
    @Test
    @DisplayName("portal SERVICE_TYPE lifecycle: queued → renamed → approved shows the EDITED name in the cached public "
            + "catalogue; the rejected one never appears")
    void should_publishEditedTypeAndHideRejected_when_portalListsEditsAndDecides() throws Exception {
        UUID requester = insertRequester();
        Seeded<UUID> kept = seedSuggestion("IT503 Ритуал до правки", requester);
        Seeded<UUID> refused = seedSuggestion("IT503 Ритуал відхилений", requester);
        String edited = "Ритуал після правки";
        fixture.trackType(edited);
        String cachedBefore = publicRead("/api/v1/service-types", null);

        List<String> queued = queueIds("PENDING");
        ResponseEntity<String> edit = editName("SERVICE_TYPE", kept.id(), Map.of("name", edited));
        ResponseEntity<String> approved = approve("SERVICE_TYPE", kept.id());
        ResponseEntity<String> rejected = reject("SERVICE_TYPE", refused.id(), null);
        String cachedAfter = publicRead("/api/v1/service-types", null);
        String appPicker = publicRead("/api/v1/service-types?categoryName=" + SEEDED_CATEGORY, null);

        assertThat(queued).contains(kept.id().toString(), refused.id().toString());
        assertThat(edit.getStatusCode()).as("body: %s", edit.getBody()).isEqualTo(HttpStatus.OK);
        assertOk(approved);
        assertOk(rejected);
        assertThat(cachedBefore).as("cache warmed before the decision").doesNotContain(edited);
        assertThat(cachedAfter).as("cached public list refreshed by the portal approve").contains(edited)
                .doesNotContain("IT503 Ритуал до правки", "IT503 Ритуал відхилений");
        assertThat(appPicker).as("app picker for the category").contains(edited)
                .doesNotContain("IT503 Ритуал до правки", "IT503 Ритуал відхилений");
        assertThat(queueIds("PENDING")).doesNotContain(kept.id().toString(), refused.id().toString());
        assertThat(queueIds("APPROVED")).contains(kept.id().toString()).doesNotContain(refused.id().toString());
        assertThat(queueIds("REJECTED")).contains(refused.id().toString()).doesNotContain(kept.id().toString());
    }

    @Test
    @DisplayName("portal CATEGORY lifecycle: queued → display name edited → approved shows the EDITED name in the "
            + "cached approved list; the rejected one never appears")
    void should_publishEditedCategoryAndHideRejected_when_portalListsEditsAndDecides() throws Exception {
        UUID requester = insertRequester();
        Seeded<Long> kept = seedCategory("IT503_CAT_LIFECYCLE", "Категорія до правки", requester);
        Seeded<Long> refused = seedCategory("IT503_CAT_REFUSED", "Категорія відхилена", requester);
        String edited = "Категорія після правки";
        String cachedBefore = publicRead("/api/v1/service-categories/approved", requester);

        List<String> queued = queueIds("PENDING");
        ResponseEntity<String> edit = editName("CATEGORY", kept.id(), Map.of("name", edited));
        ResponseEntity<String> approved = approve("CATEGORY", kept.id());
        ResponseEntity<String> rejected = reject("CATEGORY", refused.id(), null);
        String cachedAfter = publicRead("/api/v1/service-categories/approved", requester);

        assertThat(queued).contains(String.valueOf(kept.id()), String.valueOf(refused.id()));
        assertThat(edit.getStatusCode()).as("body: %s", edit.getBody()).isEqualTo(HttpStatus.OK);
        assertOk(approved);
        assertOk(rejected);
        assertThat(cachedBefore).doesNotContain("IT503_CAT_LIFECYCLE", "IT503_CAT_REFUSED");
        JsonNode published = null;
        for (JsonNode n : objectMapper.readTree(cachedAfter).path("data")) {
            if ("IT503_CAT_LIFECYCLE".equals(n.path("name").asText())) {
                published = n;
            }
        }
        assertThat(published).as("approved category in the refreshed public list: %s", cachedAfter).isNotNull();
        assertThat(published.path("displayName").asText()).isEqualTo(edited);
        assertThat(cachedAfter).doesNotContain("IT503_CAT_REFUSED", "Категорія відхилена", "Категорія до правки");
        assertThat(queueIds("REJECTED")).contains(String.valueOf(refused.id()));
        assertThat(queueIds("PENDING")).doesNotContain(String.valueOf(kept.id()), String.valueOf(refused.id()));
    }

    // ── decision e-mail content ─────────────────────────────────────────────────

    /**
     * The real {@code email/request-decision} template over a mocked SMTP transport: a portal reason with markup, a tab
     * and a zero-width space reaches the e-mail cleaned by the email path's rules and HTML-escaped; the outbox row
     * carries ids only (the reason is read at drain time from {@code decision_reason}).
     */
    @Test
    @DisplayName("portal reject with a markup reason → the drained e-mail shows it cleaned and HTML-escaped; the "
            + "outbox payload holds no reason text")
    void should_renderCleanedEscapedReason_when_portalRejectReasonCarriesMarkup() throws Exception {
        JavaMailSender mailSender = MockedMailSender.create();
        EmailNotificationService real = new EmailNotificationService(mailSender, templateEngine, fromAddress);
        doAnswer(inv -> {
            real.sendRequestDecisionEmail(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2),
                    inv.getArgument(3), inv.getArgument(4));
            return null;
        }).when(emailNotificationService).sendRequestDecisionEmail(anyString(), any(), anyBoolean(), anyString(), any());
        Seeded<UUID> row = seedSuggestion("IT503 Ритуал розмітка", insertRequester());
        String raw = "  <b>Дубль</b>\tвже є & \"так\"​ ";
        String cleaned = "<b>Дубль</b> вже є & \"так\"";

        ResponseEntity<String> response = reject("SERVICE_TYPE", row.id(),
                objectMapper.writeValueAsString(Map.of("reason", raw)));
        String payload = jdbcTemplate.queryForObject("SELECT payload FROM notification_outbox "
                + "WHERE event_type = 'REQUEST_DECIDED'", String.class);
        drainWorker.drain();

        assertOk(response);
        assertThat(jdbcTemplate.queryForObject("SELECT decision_reason FROM service_type_suggestion WHERE id = ?",
                String.class, row.id())).isEqualTo(cleaned);
        assertThat(payload).contains(row.id().toString()).doesNotContain("Дубль", "<b>");
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender, times(1)).send(sent.capture());
        assertThat(sent.getValue().getAllRecipients()).extracting(Object::toString).containsExactly(REQUESTER_EMAIL);
        String html = htmlBodyOf(sent.getValue());
        assertThat(html).contains(reasonLine("&lt;b&gt;Дубль&lt;/b&gt; вже є &amp; &quot;так&quot;"))
                .doesNotContain("<b>Дубль", "​");
    }

    // ── in-app feed item ───────────────────────────────────────────────────────

    @Test
    @DisplayName("each portal decision writes exactly one in-app item, to that request's requester, typed by the "
            + "outcome; a refused second decision adds none")
    void should_writeOneInAppItemForRequester_when_portalDecides() throws Exception {
        UUID suggester = insertRequester();
        UUID categoryRequester = insertRequester("it503-other@beautica.test", "+380501115031");
        Seeded<UUID> suggestion = seedSuggestion("IT503 Ритуал стрічка", suggester);
        Seeded<Long> category = seedCategory("IT503_CAT_FEED", "Стрічка IT503", categoryRequester);

        assertOk(approve("SERVICE_TYPE", suggestion.id()));
        assertOk(reject("CATEGORY", category.id(), null));
        ResponseEntity<String> second = approve("CATEGORY", category.id());

        assertRequestNotPending(second);
        assertThat(inAppRows())
                .extracting(r -> r.get("recipient_user_id"), r -> r.get("type"), r -> r.get("category_request_id"),
                        r -> r.get("service_type_suggestion_id"))
                .containsExactlyInAnyOrder(
                        tuple(suggester, "SERVICE_TYPE_SUGGESTION_APPROVED", null,
                                suggestion.id()),
                        tuple(categoryRequester, "CATEGORY_REQUEST_REJECTED",
                                category.id(), null));
    }

    // ── 400 paths leave nothing behind ─────────────────────────────────────────

    @Test
    @DisplayName("reject without actor, unknown kind on approve/reject, 301-code-point CATEGORY reason → 400; still "
            + "PENDING, no audit, outbox or in-app row")
    void should_return400AndLeaveNoTrace_when_rejectInputInvalid() throws Exception {
        Seeded<Long> row = seedCategory("IT503_CAT_BAD_INPUT", "Погане введення IT503", insertRequester());
        String reason = objectMapper.writeValueAsString(Map.of("reason", "ж".repeat(301)));

        ResponseEntity<String> noActor = decide("CATEGORY", row.id(), "reject", CATALOG_KEY, null,
                objectMapper.writeValueAsString(Map.of("reason", "Коротка причина")));
        ResponseEntity<String> badKindApprove = decide("SALON", row.id(), "approve", CATALOG_KEY, ACTOR, null);
        ResponseEntity<String> badKindReject = decide("SALON", row.id(), "reject", CATALOG_KEY, ACTOR, null);
        ResponseEntity<String> tooLong = reject("CATEGORY", row.id(), reason);

        assertThat(List.of(noActor, badKindApprove, badKindReject, tooLong))
                .extracting(ResponseEntity::getStatusCode)
                .containsOnly(HttpStatus.BAD_REQUEST);
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT status, active, decision_reason FROM platform_categories WHERE id = ?", row.id());
        assertThat(stored).containsEntry("status", "PENDING").containsEntry("active", false)
                .containsEntry("decision_reason", null);
        assertThat(count("SELECT COUNT(*) FROM catalog_admin_audit")).isZero();
        assertThat(count("SELECT COUNT(*) FROM notification_outbox")).isZero();
        assertThat(inAppRows()).isEmpty();
    }

    // ── access + input ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("no key / user JWT only → 401; missing actor → 400; unknown id → 404; malformed id → 400")
    void should_refuseBeforeDeciding_when_accessOrInputInvalid() throws Exception {
        UUID requester = insertRequester();
        Seeded<UUID> row = seedSuggestion("IT503 Ритуал доступ", requester);
        HttpHeaders jwtOnly = new HttpHeaders();
        jwtOnly.setBearerAuth(jwtTokenProvider.generateAccessToken(requester, REQUESTER_EMAIL, Role.INDEPENDENT_MASTER));
        jwtOnly.set(AdminActor.HEADER, ACTOR);

        ResponseEntity<String> noKey = decide("SERVICE_TYPE", row.id(), "approve", null, ACTOR, null);
        ResponseEntity<String> jwt = restTemplate.exchange(BASE + "/SERVICE_TYPE/" + row.id() + "/approve",
                HttpMethod.POST, new HttpEntity<>(jwtOnly), String.class);
        ResponseEntity<String> noActor = decide("SERVICE_TYPE", row.id(), "approve", CATALOG_KEY, null, null);
        ResponseEntity<String> unknown = approve("SERVICE_TYPE", UUID.randomUUID());
        ResponseEntity<String> malformed = approve("CATEGORY", "abc");

        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(jwt.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(noActor.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM service_type_suggestion WHERE id = ?",
                String.class, row.id())).isEqualTo("PENDING");
        assertThat(count("SELECT COUNT(*) FROM catalog_admin_audit")).isZero();
    }
}
