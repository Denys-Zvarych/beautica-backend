package com.beautica.catalog.admin;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.auth.TokenGenerator;
import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.service.service.CategoryRequestService;
import com.beautica.service.service.ServiceTypeSuggestionService;
import com.beautica.service.ServiceTestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B502 (SVC-55) — {@code /api/v1/internal/catalog/requests} through the REAL chain (Tomcat, Security,
 * {@code InternalApiKeyFilter}, MVC) on Postgres. Rows are seeded by JDBC with fixed 2020 {@code created_at} values,
 * so they sort ahead of anything a sibling class leaves behind; list asserts filter to the seeded ids.
 */
@DisplayName("InternalCatalogRequestController (IT) — pending request queue + name edit")
@TestPropertySource(properties = "app.internal-catalog-key=" + InternalCatalogRequestControllerIT.CATALOG_KEY)
class InternalCatalogRequestControllerIT extends AbstractIntegrationTest {

    static final String CATALOG_KEY = "test-internal-catalog-key-cccccccccccccccc";
    private static final String MASTER_KEY = "test-internal-api-key";
    private static final String BASE = "/api/v1/internal/catalog/requests";
    private static final String ACTOR = "beautica.ua@gmail.com";
    private static final String SUGGESTION_CATEGORY = "IT502_SUGG";
    private static final String OWNER_EMAIL = "it502-owner@beautica.test";
    private static final String MASTER_EMAIL = "it502-master@beautica.test";
    private static final String MASTER_PHONE = "+380501112233";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private TokenGenerator tokenGenerator;
    @Autowired private ServiceTypeSuggestionService suggestionService;
    @Autowired private CategoryRequestService categoryRequestService;
    @Autowired private PlatformTransactionManager transactionManager;

    private CatalogRequestFixture fixture;

    @BeforeEach
    void createFixture() {
        fixture = new CatalogRequestFixture(jdbcTemplate, passwordEncoder, tokenGenerator);
    }

    @AfterEach
    void removeSeededRequests() {
        fixture.cleanup();
    }

    // ── seeding ────────────────────────────────────────────────────────────────

    private UUID insertUser(String email, String role, String first, String last, String phone) {
        return fixture.insertUser(email, role, first, last, phone);
    }

    private UUID insertOwnerWithSalon(String salonName) {
        UUID owner = insertUser(OWNER_EMAIL, "SALON_OWNER", "Олена", "Власник", null);
        jdbcTemplate.update("INSERT INTO salons (id, owner_id, name, city_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, true, NOW(), NOW())",
                UUID.randomUUID(), owner, salonName, majorCityIdByName("Вінниця"));
        return owner;
    }

    private long insertCategory(String name, String displayName, String status, UUID requester, Instant createdAt) {
        return fixture.insertCategory(name, displayName, status, requester, createdAt);
    }

    private UUID insertSuggestion(String category, String name, String status, UUID requester, Instant createdAt) {
        return fixture.insertSuggestion(category, name, status, requester, createdAt);
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    private ResponseEntity<String> call(HttpMethod method, String url, String key, String actor, Object body)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.set("X-Internal-Key", key);
        }
        if (actor != null) {
            headers.set(AdminActor.HEADER, actor);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = body == null ? null : objectMapper.writeValueAsString(body);
        return restTemplate.exchange(url, method, new HttpEntity<>(json, headers), String.class);
    }

    private ResponseEntity<String> patch(String kind, Object id, Object body) throws Exception {
        return call(HttpMethod.PATCH, BASE + "/" + kind + "/" + id, CATALOG_KEY, ACTOR, body);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private static final Map<String, String> CONFLICT_MESSAGES = Map.of(
            "REQUEST_NOT_PENDING", "Request is no longer pending",
            "DUPLICATE_PENDING", "A matching request is already pending",
            "TYPE_EXISTS", "A service type with this name already exists");

    /**
     * The § 10 typed-error contract the website Worker (W040) reads: the RAW body is exactly
     * {@code {"success":false,"data":{"code":"<CODE>"},"message":"<fixed>","requestId":"…"}} — no other key, nothing
     * else under {@code data}, the message fixed per code.
     */
    private void assertConflictCode(ResponseEntity<String> response, String code) throws Exception {
        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        String raw = response.getBody();
        assertThat(raw).as("raw 409 body").startsWith("{\"success\":false,\"data\":{\"code\":\"" + code + "\"},"
                + "\"message\":\"" + CONFLICT_MESSAGES.get(code) + "\"");
        JsonNode body = json(response);
        assertThat(keys(body)).as("root keys, raw=%s", raw).containsExactly("success", "data", "message", "requestId");
        assertThat(keys(body.path("data"))).as("data carries the code only, raw=%s", raw).containsExactly("code");
        assertThat(body.path("requestId").asText()).as("requestId, raw=%s", raw).isNotBlank();
    }

    private static List<String> keys(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private String suggestedName(UUID id) {
        return jdbcTemplate.queryForObject("SELECT suggested_name FROM service_type_suggestion WHERE id = ?",
                String.class, id);
    }

    // ── list ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET lists both kinds oldest first with provider labels and no email or phone")
    void should_listBothKindsOldestFirst_when_pendingRowsExist() throws Exception {
        UUID owner = insertOwnerWithSalon("Салон IT502");
        UUID master = insertUser(MASTER_EMAIL, "INDEPENDENT_MASTER", "Ірина", "Майстер", MASTER_PHONE);
        long cat1 = insertCategory("IT502_FIRST", "Перша", "PENDING", owner, Instant.parse("2020-01-01T10:00:00Z"));
        UUID sug1 = insertSuggestion(SUGGESTION_CATEGORY, "Друга", "PENDING", master,
                Instant.parse("2020-01-02T10:00:00Z"));
        long cat2 = insertCategory("IT502_THIRD", "Третя", "PENDING", master, Instant.parse("2020-01-03T10:00:00Z"));
        UUID sug2 = insertSuggestion(SUGGESTION_CATEGORY, "Четверта", "PENDING", null,
                Instant.parse("2020-01-04T10:00:00Z"));
        UUID decided = insertSuggestion(SUGGESTION_CATEGORY, "Вирішена", "APPROVED", owner,
                Instant.parse("2020-01-01T09:00:00Z"));

        ResponseEntity<String> response = call(HttpMethod.GET, BASE + "?size=100", CATALOG_KEY, null, null);

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        List<String> seeded = List.of(String.valueOf(cat1), sug1.toString(), String.valueOf(cat2), sug2.toString(),
                decided.toString());
        List<JsonNode> rows = new ArrayList<>();
        json(response).path("data").path("data").forEach(n -> {
            if (seeded.contains(n.path("id").asText())) {
                rows.add(n);
            }
        });
        assertThat(rows).extracting(n -> n.path("id").asText())
                .containsExactly(String.valueOf(cat1), sug1.toString(), String.valueOf(cat2), sug2.toString());
        assertThat(rows).extracting(n -> n.path("kind").asText())
                .containsExactly("CATEGORY", "SERVICE_TYPE", "CATEGORY", "SERVICE_TYPE");
        assertThat(rows).extracting(n -> n.path("requesterLabel").asText())
                .containsExactly("Салон IT502", "Ірина Майстер", "Ірина Майстер", "—");
        assertThat(rows.get(0).path("name").asText()).isEqualTo("IT502_FIRST");
        assertThat(rows.get(0).path("displayName").asText()).isEqualTo("Перша");
        assertThat(rows.get(1).path("categoryName").asText()).isEqualTo(SUGGESTION_CATEGORY);
        assertThat(rows.get(1).path("status").asText()).isEqualTo("PENDING");
        assertThat(rows.get(0).path("createdAt").asText()).isEqualTo("2020-01-01T10:00:00Z");
        assertThat(response.getBody()).doesNotContain(OWNER_EMAIL, MASTER_EMAIL, MASTER_PHONE, "@beautica.test");
    }

    @Test
    @DisplayName("GET ?status=APPROVED lists decided history only")
    void should_listDecidedRows_when_statusApproved() throws Exception {
        UUID pending = insertSuggestion(SUGGESTION_CATEGORY, "Очікує", "PENDING", null, Instant.parse("2020-02-01T10:00:00Z"));
        UUID approved = insertSuggestion(SUGGESTION_CATEGORY, "Схвалена", "APPROVED", null,
                Instant.parse("2020-02-02T10:00:00Z"));

        ResponseEntity<String> response = call(HttpMethod.GET, BASE + "?status=APPROVED&size=100", CATALOG_KEY, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(approved.toString()).doesNotContain(pending.toString());
    }

    // ── PATCH ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH SERVICE_TYPE renames the pending suggestion and writes one audit row")
    void should_renameAndAudit_when_suggestionPending() throws Exception {
        UUID id = insertSuggestion(SUGGESTION_CATEGORY, "Масаж ніг", "PENDING", null, Instant.parse("2020-03-01T10:00:00Z"));

        ResponseEntity<String> response = patch("SERVICE_TYPE", id, Map.of("name", "Масаж  стоп"));

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(suggestedName(id)).isEqualTo("Масаж стоп");
        Map<String, Object> audit = jdbcTemplate.queryForMap("SELECT actor, action, target_type, before::text AS b, "
                + "after::text AS a FROM catalog_admin_audit WHERE target_id = ?", id.toString());
        assertThat(audit.get("actor")).isEqualTo(ACTOR);
        assertThat(audit.get("action")).isEqualTo("EDIT_REQUEST_NAME");
        assertThat(audit.get("target_type")).isEqualTo("TYPE_SUGGESTION");
        assertThat((String) audit.get("b")).contains("Масаж ніг");
        assertThat((String) audit.get("a")).contains("Масаж стоп");
    }

    @Test
    @DisplayName("PATCH CATEGORY edits display name + initial service name, never the wire name")
    void should_editDisplayNameOnly_when_categoryPending() throws Exception {
        long id = insertCategory("IT502_EDIT", "Нейл арт", "PENDING", null, Instant.parse("2020-03-02T10:00:00Z"));

        ResponseEntity<String> response = patch("CATEGORY", id, Map.of("name", "Нейл-арт", "initialServiceName", "Френч"));

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT name, display_name, initial_service_name FROM platform_categories WHERE id = ?", id);
        assertThat(row.get("name")).isEqualTo("IT502_EDIT");
        assertThat(row.get("display_name")).isEqualTo("Нейл-арт");
        assertThat(row.get("initial_service_name")).isEqualTo("Френч");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog_admin_audit WHERE target_id = ? "
                + "AND target_type = 'CATEGORY_REQUEST'", Long.class, String.valueOf(id))).isEqualTo(1L);
    }

    @Test
    @DisplayName("PATCH on an APPROVED row → 409 data.code REQUEST_NOT_PENDING, nothing changes")
    void should_return409RequestNotPending_when_rowApproved() throws Exception {
        UUID id = insertSuggestion(SUGGESTION_CATEGORY, "Схвалена", "APPROVED", null, Instant.parse("2020-03-03T10:00:00Z"));

        ResponseEntity<String> response = patch("SERVICE_TYPE", id, Map.of("name", "Інша"));

        assertConflictCode(response, "REQUEST_NOT_PENDING");
        assertThat(suggestedName(id)).isEqualTo("Схвалена");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog_admin_audit", Long.class)).isZero();
    }

    @Test
    @DisplayName("PATCH with a name breaking SVC-07 → 400")
    void should_return400_when_nameBreaksAllowList() throws Exception {
        UUID id = insertSuggestion(SUGGESTION_CATEGORY, "Масаж", "PENDING", null, Instant.parse("2020-03-04T10:00:00Z"));

        ResponseEntity<String> response = patch("SERVICE_TYPE", id, Map.of("name", "Масаж 💅"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(suggestedName(id)).isEqualTo("Масаж");
    }

    @Test
    @DisplayName("PATCH to another pending suggestion's name (any case) → 409 data.code DUPLICATE_PENDING")
    void should_return409DuplicatePending_when_otherPendingHasName() throws Exception {
        insertSuggestion(SUGGESTION_CATEGORY, "Омбре", "PENDING", null, Instant.parse("2020-03-05T10:00:00Z"));
        UUID id = insertSuggestion(SUGGESTION_CATEGORY, "Омбрэ", "PENDING", null, Instant.parse("2020-03-05T11:00:00Z"));

        ResponseEntity<String> response = patch("SERVICE_TYPE", id, Map.of("name", "омбре"));

        assertConflictCode(response, "DUPLICATE_PENDING");
        assertThat(suggestedName(id)).isEqualTo("Омбрэ");
    }

    @Test
    @DisplayName("PATCH to an existing active service type's name → 409 data.code TYPE_EXISTS")
    void should_return409TypeExists_when_activeTypeHasName() throws Exception {
        ServiceTestFixtures.SeededServiceType type =
                new ServiceTestFixtures(null, jdbcTemplate, objectMapper, passwordEncoder)
                        .activeSelectableServiceTypes(1).get(0);
        UUID id = insertSuggestion(type.platformCategoryName(), "Тимчасова IT502", "PENDING", null,
                Instant.parse("2020-03-06T10:00:00Z"));

        ResponseEntity<String> response = patch("SERVICE_TYPE", id, Map.of("name", type.nameUk()));

        assertConflictCode(response, "TYPE_EXISTS");
    }

    @Test
    @DisplayName("PATCH without X-Admin-Actor → 400; unknown id → 404")
    void should_return400Or404_when_actorMissingOrIdUnknown() throws Exception {
        UUID id = insertSuggestion(SUGGESTION_CATEGORY, "Масаж", "PENDING", null, Instant.parse("2020-03-07T10:00:00Z"));

        ResponseEntity<String> noActor = call(HttpMethod.PATCH, BASE + "/SERVICE_TYPE/" + id, CATALOG_KEY, null,
                Map.of("name", "Інша"));
        ResponseEntity<String> unknown = patch("SERVICE_TYPE", UUID.randomUUID(), Map.of("name", "Інша"));

        assertThat(noActor.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(suggestedName(id)).isEqualTo("Масаж");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── keys ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no key → 401; master key and catalog key both → 200")
    void should_requireMasterOrCatalogKey_when_listing() throws Exception {
        ResponseEntity<String> none = call(HttpMethod.GET, BASE, null, null, null);
        ResponseEntity<String> master = call(HttpMethod.GET, BASE, MASTER_KEY, null, null);
        ResponseEntity<String> catalog = call(HttpMethod.GET, BASE, CATALOG_KEY, null, null);

        assertThat(none.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(master.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(catalog.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("valid user JWT without an internal key → 401 on GET and PATCH")
    void should_return401_when_onlyUserJwtSent() throws Exception {
        UUID user = insertUser("it502-jwt@beautica.test", "SALON_OWNER", "Ім'я", "Прізвище", null);
        UUID id = insertSuggestion(SUGGESTION_CATEGORY, "Масаж", "PENDING", null, Instant.parse("2020-04-01T10:00:00Z"));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(jwtTokenProvider.generateAccessToken(user, "it502-jwt@beautica.test", Role.SALON_OWNER));
        headers.set(AdminActor.HEADER, ACTOR);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> list = restTemplate.exchange(BASE, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        ResponseEntity<String> edit = restTemplate.exchange(BASE + "/SERVICE_TYPE/" + id, HttpMethod.PATCH,
                new HttpEntity<>("{\"name\":\"Інша\"}", headers), String.class);

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(edit.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(suggestedName(id)).isEqualTo("Масаж");
    }

    @Test
    @DisplayName("PATCH with 100 raw chars that NFKC expands past 100 (ﬁ → fi) → 400")
    void should_return400_when_nfkcExpandsNamePast100() throws Exception {
        UUID id = insertSuggestion(SUGGESTION_CATEGORY, "Масаж", "PENDING", null, Instant.parse("2020-04-02T10:00:00Z"));

        ResponseEntity<String> response = patch("SERVICE_TYPE", id, Map.of("name", "\uFB01".repeat(100)));

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).path("data").isNull()).isTrue();
        assertThat(suggestedName(id)).isEqualTo("Масаж");
    }

    // ── edit vs email-link approve (row lock) ──────────────────────────────────

    private record RacedSuggestion(UUID id, String rawToken, String category) {}

    private RacedSuggestion seedApprovable(String name, Instant createdAt) {
        String category = new ServiceTestFixtures(null, jdbcTemplate, objectMapper, passwordEncoder)
                .activeSelectableServiceTypes(1).get(0).platformCategoryName();
        CatalogRequestFixture.Seeded<UUID> row = fixture.pendingSuggestionWithToken(category, name, null,
                Instant.now().plus(Duration.ofDays(3)), createdAt);
        return new RacedSuggestion(row.id(), row.rawToken(), category);
    }

    private <T> T raceAfterHeldTransaction(Runnable first, Supplier<T> second) throws Exception {
        return new HeldTransactionRace(transactionManager, jdbcTemplate).run(first, second);
    }

    @Test
    @DisplayName("approve holds the row → a parallel edit waits on FOR UPDATE, then gets REQUEST_NOT_PENDING")
    void should_refuseEdit_when_approveCommitsFirst() throws Exception {
        RacedSuggestion row = seedApprovable("Гонка IT502 перша", Instant.parse("2020-05-01T10:00:00Z"));
        fixture.trackType("Гонка IT502 перша");

        Throwable failure = null;
        try {
            raceAfterHeldTransaction(
                    () -> assertThat(suggestionService.approve(row.rawToken()))
                            .isEqualTo(ServiceTypeSuggestionService.DecisionOutcome.APPROVED),
                    () -> suggestionService.editPendingName(row.id(), "Гонка IT502 змінена"));
        } catch (CatalogRequestConflictException ex) {
            failure = ex;
        }

        assertThat(failure).as("the edit must lose, not overwrite the approved row")
                .isInstanceOfSatisfying(CatalogRequestConflictException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(CatalogRequestConflictException.Code.REQUEST_NOT_PENDING));
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT status, suggested_name, token_hash FROM service_type_suggestion WHERE id = ?", row.id());
        assertThat(stored.get("status")).isEqualTo("APPROVED");
        assertThat(stored.get("suggested_name")).isEqualTo("Гонка IT502 перша");
        assertThat(stored.get("token_hash")).isNull();
    }

    @Test
    @DisplayName("edit holds the row → a parallel email-link approve waits, then promotes the EDITED name")
    void should_promoteEditedName_when_editCommitsFirst() throws Exception {
        RacedSuggestion row = seedApprovable("Гонка IT502 друга", Instant.parse("2020-05-02T10:00:00Z"));
        fixture.trackType("Гонка IT502 виправлена");

        ServiceTypeSuggestionService.DecisionOutcome outcome = raceAfterHeldTransaction(
                () -> suggestionService.editPendingName(row.id(), "Гонка IT502 виправлена"),
                () -> suggestionService.approve(row.rawToken()));

        assertThat(outcome).isEqualTo(ServiceTypeSuggestionService.DecisionOutcome.APPROVED);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_types WHERE name_uk = ? "
                + "AND platform_category_name = ?", Long.class, "Гонка IT502 виправлена", row.category())).isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM service_types WHERE name_uk = ?",
                Long.class, "Гонка IT502 друга")).isZero();
    }

    // ── CATEGORY edit vs email-link approve (row lock) ──────────────────────────

    private record RacedCategory(long id, String rawToken, String name) {}

    private RacedCategory seedApprovableCategory(String name, String displayName, String initialServiceName,
                                                 Instant createdAt) {
        CatalogRequestFixture.Seeded<Long> row = fixture.pendingCategoryWithToken(name, displayName, null,
                initialServiceName, Instant.now().plus(Duration.ofDays(3)), createdAt);
        return new RacedCategory(row.id(), row.rawToken(), name);
    }

    @Test
    @DisplayName("category approve holds the row → a parallel edit waits on FOR UPDATE, then gets REQUEST_NOT_PENDING")
    void should_refuseCategoryEdit_when_categoryApproveCommitsFirst() throws Exception {
        RacedCategory row = seedApprovableCategory("IT502_RACE_ONE", "Гонка перша", null,
                Instant.parse("2020-05-03T10:00:00Z"));

        Throwable failure = null;
        try {
            raceAfterHeldTransaction(
                    () -> assertThat(categoryRequestService.approve(row.rawToken()))
                            .isEqualTo(CategoryRequestService.DecisionOutcome.APPROVED),
                    () -> categoryRequestService.editPendingName(row.id(), "Гонка змінена", null));
        } catch (CatalogRequestConflictException ex) {
            failure = ex;
        }

        assertThat(failure).as("the edit must lose, not overwrite the approved category (lost update)")
                .isInstanceOfSatisfying(CatalogRequestConflictException.class, ex -> assertThat(ex.getCode())
                        .isEqualTo(CatalogRequestConflictException.Code.REQUEST_NOT_PENDING));
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT status, active, display_name, token_hash FROM platform_categories WHERE id = ?", row.id());
        assertThat(stored.get("status")).isEqualTo("APPROVED");
        assertThat(stored.get("active")).isEqualTo(true);
        assertThat(stored.get("display_name")).isEqualTo("Гонка перша");
        assertThat(stored.get("token_hash")).isNull();
    }

    @Test
    @DisplayName("category edit holds the row → a parallel email-link approve waits, then approves the EDITED name")
    void should_approveEditedDisplayName_when_categoryEditCommitsFirst() throws Exception {
        RacedCategory row = seedApprovableCategory("IT502_RACE_TWO", "Гонка друга", null,
                Instant.parse("2020-05-04T10:00:00Z"));

        CategoryRequestService.DecisionOutcome outcome = raceAfterHeldTransaction(
                () -> categoryRequestService.editPendingName(row.id(), "Гонка виправлена", null),
                () -> categoryRequestService.approve(row.rawToken()));

        assertThat(outcome).isEqualTo(CategoryRequestService.DecisionOutcome.APPROVED);
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT name, status, display_name FROM platform_categories WHERE id = ?", row.id());
        assertThat(stored.get("name")).isEqualTo("IT502_RACE_TWO");
        assertThat(stored.get("status")).isEqualTo("APPROVED");
        assertThat(stored.get("display_name")).isEqualTo("Гонка виправлена");
    }

    // ── owner answer Q3: the portal-edited name is the name the email link approves ──

    private ResponseEntity<String> approveByEmailLink(String path, String rawToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", rawToken);
        return restTemplate.exchange(path, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    }

    @Test
    @DisplayName("Q3 SERVICE_TYPE — portal PATCH, then the email-link approve promotes the edited name")
    void should_promotePortalEditedName_when_suggestionApprovedByEmailLink() throws Exception {
        RacedSuggestion row = seedApprovable("Q3 IT502 з помилкою", Instant.parse("2020-06-01T10:00:00Z"));
        fixture.trackType("Q3 IT502 виправлена");
        fixture.trackType("Q3 IT502 з помилкою");

        ResponseEntity<String> edit = patch("SERVICE_TYPE", row.id(), Map.of("name", "Q3 IT502 виправлена"));
        ResponseEntity<String> approve = approveByEmailLink("/api/v1/service-types/suggestions/approve", row.rawToken());

        assertThat(edit.getStatusCode()).as("edit body: %s", edit.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(approve.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM service_type_suggestion WHERE id = ?",
                String.class, row.id())).as("the edit kept the email token valid (track D2)").isEqualTo("APPROVED");
        assertThat(jdbcTemplate.queryForList("SELECT name_uk FROM service_types WHERE platform_category_name = ? "
                        + "AND name_uk LIKE 'Q3 IT502%'", String.class, row.category()))
                .containsExactly("Q3 IT502 виправлена");
    }

    @Test
    @DisplayName("Q3 CATEGORY — portal PATCH, then the email-link approve publishes the edited display + initial name")
    void should_approvePortalEditedNames_when_categoryApprovedByEmailLink() throws Exception {
        RacedCategory row = seedApprovableCategory("IT502_Q3", "Брови з помилкою", "Корекція з помилкою",
                Instant.parse("2020-06-02T10:00:00Z"));

        ResponseEntity<String> edit = patch("CATEGORY", row.id(),
                Map.of("name", "Брови", "initialServiceName", "Корекція брів"));
        ResponseEntity<String> approve = approveByEmailLink("/api/v1/service-categories/requests/approve",
                row.rawToken());

        assertThat(edit.getStatusCode()).as("edit body: %s", edit.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(approve.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> category = jdbcTemplate.queryForMap(
                "SELECT name, display_name, status, active FROM platform_categories WHERE id = ?", row.id());
        assertThat(category.get("status")).as("the edit kept the email token valid (track D2)").isEqualTo("APPROVED");
        assertThat(category.get("active")).isEqualTo(true);
        assertThat(category.get("name")).as("wire name never changes (track D4)").isEqualTo("IT502_Q3");
        assertThat(category.get("display_name")).isEqualTo("Брови");
        assertThat(jdbcTemplate.queryForList("SELECT suggested_name FROM service_type_suggestion "
                + "WHERE category_name = ?", String.class, "IT502_Q3"))
                .as("the initial service the approve spawns carries the EDITED name")
                .containsExactly("Корекція брів");
    }

    // ── list: pagination, status filter, requester label ────────────────────────

    private ResponseEntity<String> list(String query) throws Exception {
        return call(HttpMethod.GET, BASE + "?" + query, CATALOG_KEY, null, null);
    }

    private static List<String> ids(JsonNode page) {
        List<String> ids = new ArrayList<>();
        page.path("data").path("data").forEach(n -> ids.add(n.path("id").asText()));
        return ids;
    }

    @Test
    @DisplayName("GET pages both kinds by created_at → kind → id; ?sort is ignored; page boundaries hold")
    void should_pageBothKindsInStableOrder_when_requestsSpanPages() throws Exception {
        Instant t1 = Instant.parse("2019-01-01T10:00:00Z");
        Instant t2 = Instant.parse("2019-01-02T10:00:00Z");
        // Inserted out of order; same-timestamp pairs exercise both tie-breakers.
        UUID sugAtT1 = insertSuggestion(SUGGESTION_CATEGORY, "Пагінація один", "PENDING", null, t1);
        long catAtT1 = insertCategory("IT502_PAGE_A", "Пагінація А", "PENDING", null, t1);
        long catLast = insertCategory("IT502_PAGE_B", "Пагінація Б", "PENDING", null,
                Instant.parse("2019-01-03T10:00:00Z"));
        UUID sugAtT2a = insertSuggestion(SUGGESTION_CATEGORY, "Пагінація два", "PENDING", null, t2);
        UUID sugAtT2b = insertSuggestion(SUGGESTION_CATEGORY, "Пагінація три", "PENDING", null, t2);
        List<String> t2ByIdText = new ArrayList<>(List.of(sugAtT2a.toString(), sugAtT2b.toString()));
        t2ByIdText.sort(Comparator.naturalOrder());

        JsonNode page0 = json(list("page=0&size=2&sort=createdAt,desc"));
        JsonNode page1 = json(list("page=1&size=2&sort=createdAt,desc"));
        JsonNode page2 = json(list("page=2&size=2&sort=createdAt,desc"));

        assertThat(ids(page0)).as("CATEGORY before SERVICE_TYPE at the same created_at")
                .containsExactly(String.valueOf(catAtT1), sugAtT1.toString());
        assertThat(ids(page1)).as("same kind + created_at → id order").containsExactly(t2ByIdText.toArray(String[]::new));
        assertThat(ids(page2)).as("the 5th row opens page 2").first().isEqualTo(String.valueOf(catLast));
        long total = page0.path("data").path("totalElements").asLong();
        assertThat(total).isGreaterThanOrEqualTo(5L);
        assertThat(page2.path("data").path("page").asInt()).isEqualTo(2);
        assertThat(page2.path("data").path("size").asInt()).isEqualTo(2);
        assertThat(page2.path("data").path("totalPages").asInt()).isEqualTo((int) ((total + 1) / 2));
    }

    @Test
    @DisplayName("GET past the last page → 200 with an empty page and the real total")
    void should_returnEmptyPage_when_pageBeyondTotal() throws Exception {
        insertSuggestion(SUGGESTION_CATEGORY, "Одна", "PENDING", null, Instant.parse("2019-01-04T10:00:00Z"));

        ResponseEntity<String> response = list("page=100000&size=100");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode page = json(response).path("data");
        assertThat(page.path("data").isArray()).isTrue();
        assertThat(page.path("data")).isEmpty();
        assertThat(page.path("totalElements").asLong()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("GET ?status=REJECTED / APPROVED lists only that status, both kinds; non-request categories excluded")
    void should_filterBothKindsByStatus_when_statusRejectedOrApproved() throws Exception {
        Instant t = Instant.parse("2019-02-01T10:00:00Z");
        long catPending = insertCategory("IT502_ST_PEND", "Очікує", "PENDING", null, t);
        long catApproved = insertCategory("IT502_ST_APPR", "Схвалена", "APPROVED", null, t.plusSeconds(60));
        long catRejected = insertCategory("IT502_ST_REJ", "Відхилена", "REJECTED", null, t.plusSeconds(120));
        UUID sugPending = insertSuggestion(SUGGESTION_CATEGORY, "Очікує", "PENDING", null, t.plusSeconds(180));
        UUID sugApproved = insertSuggestion(SUGGESTION_CATEGORY, "Схвалена", "APPROVED", null, t.plusSeconds(240));
        UUID sugRejected = insertSuggestion(SUGGESTION_CATEGORY, "Відхилена", "REJECTED", null, t.plusSeconds(300));
        fixture.trackCategory("IT502_ST_SEED");
        jdbcTemplate.update("INSERT INTO platform_categories (name, display_name, active, status, created_at) "
                + "VALUES ('IT502_ST_SEED', 'Засіяна', true, 'APPROVED', ?)", Timestamp.from(t.minusSeconds(60)));
        long seedLike = jdbcTemplate.queryForObject("SELECT id FROM platform_categories WHERE name = 'IT502_ST_SEED'",
                Long.class);
        Set<String> seeded = new HashSet<>(List.of(String.valueOf(catPending), String.valueOf(catApproved),
                String.valueOf(catRejected), sugPending.toString(), sugApproved.toString(), sugRejected.toString(),
                String.valueOf(seedLike)));

        JsonNode rejected = json(list("status=REJECTED&size=100"));
        JsonNode approved = json(list("status=APPROVED&size=100"));

        assertThat(ids(rejected).stream().filter(seeded::contains).toList())
                .containsExactly(String.valueOf(catRejected), sugRejected.toString());
        assertThat(ids(approved).stream().filter(seeded::contains).toList())
                .as("a seeded / ops-created category (requested_at NULL) is not a request")
                .containsExactly(String.valueOf(catApproved), sugApproved.toString());
        rejected.path("data").path("data").forEach(n -> assertThat(n.path("status").asText()).isEqualTo("REJECTED"));
        approved.path("data").path("data").forEach(n -> assertThat(n.path("status").asText()).isEqualTo("APPROVED"));
    }

    private UUID insertSalon(UUID owner, String name, boolean primary, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO salons (id, owner_id, name, city_id, is_active, is_primary, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, true, ?, ?, ?)",
                id, owner, name, majorCityIdByName("Вінниця"), primary, Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        return id;
    }

    @Test
    @DisplayName("GET requesterLabel: owned salon (primary first) → name → workplace salon → «—»; no email/phone")
    void should_resolveRequesterLabelInFallbackOrder_when_requestersDiffer() throws Exception {
        UUID owner = insertUser("it502-l-owner@beautica.test", "SALON_OWNER", "Олена", "Власник", "+380501110001");
        UUID workplace = insertSalon(owner, "Робочий салон IT502", false, Instant.parse("2019-01-01T00:00:00Z"));
        insertSalon(owner, "Головний салон IT502", true, Instant.parse("2019-01-02T00:00:00Z"));
        UUID named = insertUser("it502-l-named@beautica.test", "INDEPENDENT_MASTER", "Ірина", "Майстер",
                "+380501110002");
        UUID namedStaff = insertUser("it502-l-staff@beautica.test", "SALON_MASTER", "Марко", null, "+380501110003");
        UUID namelessStaff = insertUser("it502-l-nameless@beautica.test", "SALON_MASTER", null, null,
                "+380501110004");
        jdbcTemplate.update("UPDATE users SET salon_id = ? WHERE id IN (?, ?)", workplace, namedStaff, namelessStaff);
        UUID blank = insertUser("it502-l-blank@beautica.test", "INDEPENDENT_MASTER", "  ", null, "+380501110005");
        Instant t = Instant.parse("2019-03-01T10:00:00Z");
        long r1 = insertCategory("IT502_LBL_OWNER", "Власник", "PENDING", owner, t);
        UUID r2 = insertSuggestion(SUGGESTION_CATEGORY, "Іменна", "PENDING", named, t.plusSeconds(60));
        long r3 = insertCategory("IT502_LBL_STAFF", "Працівник", "PENDING", namedStaff, t.plusSeconds(120));
        UUID r4 = insertSuggestion(SUGGESTION_CATEGORY, "Безіменна", "PENDING", namelessStaff, t.plusSeconds(180));
        long r5 = insertCategory("IT502_LBL_BLANK", "Порожнє", "PENDING", blank, t.plusSeconds(240));
        UUID r6 = insertSuggestion(SUGGESTION_CATEGORY, "Без автора", "PENDING", null, t.plusSeconds(300));
        List<String> seeded = List.of(String.valueOf(r1), r2.toString(), String.valueOf(r3), r4.toString(),
                String.valueOf(r5), r6.toString());

        ResponseEntity<String> response = list("size=100");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<JsonNode> rows = new ArrayList<>();
        json(response).path("data").path("data").forEach(n -> {
            if (seeded.contains(n.path("id").asText())) {
                rows.add(n);
            }
        });
        assertThat(rows).extracting(n -> n.path("requesterLabel").asText()).containsExactly(
                "Головний салон IT502", // owner: own primary salon beats the person's name and the older salon
                "Ірина Майстер",        // no salon owned: first + last name
                "Марко",                // salon staff WITH a name: the name beats the workplace salon
                "Робочий салон IT502",  // salon staff without a name: the workplace salon
                "—",                    // whitespace-only name, no salon: placeholder
                "—");                   // no requester at all
        rows.forEach(n -> assertThat(keys(n)).as("row keys").containsExactlyInAnyOrder("kind", "id", "name",
                "displayName", "categoryName", "initialServiceName", "description", "requesterLabel", "createdAt",
                "status"));
        assertThat(response.getBody()).as("no requester email or phone anywhere in the raw body")
                .doesNotContain("@beautica.test", "it502-l-", "+38050111000", "380501110", "email", "phone");
    }

    // ── typed 409 for the CATEGORY kind + audit row content ─────────────────────

    @Test
    @DisplayName("PATCH CATEGORY on a REJECTED request → 409 raw {success:false,data:{code:REQUEST_NOT_PENDING}}")
    void should_return409RequestNotPending_when_categoryRejected() throws Exception {
        long id = insertCategory("IT502_REJ_EDIT", "Відхилена", "REJECTED", null, Instant.parse("2020-07-01T10:00:00Z"));

        ResponseEntity<String> response = patch("CATEGORY", id, Map.of("name", "Інша"));

        assertConflictCode(response, "REQUEST_NOT_PENDING");
        assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM platform_categories WHERE id = ?",
                String.class, id)).isEqualTo("Відхилена");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog_admin_audit", Long.class)).isZero();
    }

    private Map<String, Object> onlyAuditRow(String targetId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT actor, action, target_type, "
                + "before::text AS b, after::text AS a FROM catalog_admin_audit WHERE target_id = ?", targetId);
        assertThat(rows).as("exactly one audit row for %s", targetId).hasSize(1);
        return rows.get(0);
    }

    @Test
    @DisplayName("audit SERVICE_TYPE edit: before/after hold exactly {suggestedName}, no requester email or phone")
    void should_auditSuggestionEditWithExplicitFieldsOnly_when_requesterHasContactData() throws Exception {
        UUID requester = insertUser("it502-audit-s@beautica.test", "INDEPENDENT_MASTER", "Аудит", "Майстер",
                "+380501119901");
        UUID id = insertSuggestion(SUGGESTION_CATEGORY, "Пілінг стари", "PENDING", requester,
                Instant.parse("2020-07-02T10:00:00Z"));

        ResponseEntity<String> response = patch("SERVICE_TYPE", id, Map.of("name", "Пілінг старий"));

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> audit = onlyAuditRow(id.toString());
        assertThat(audit.get("actor")).isEqualTo(ACTOR);
        assertThat(audit.get("action")).isEqualTo("EDIT_REQUEST_NAME");
        assertThat(audit.get("target_type")).isEqualTo("TYPE_SUGGESTION");
        JsonNode before = objectMapper.readTree((String) audit.get("b"));
        JsonNode after = objectMapper.readTree((String) audit.get("a"));
        assertThat(keys(before)).containsExactly("suggestedName");
        assertThat(keys(after)).containsExactly("suggestedName");
        assertThat(before.path("suggestedName").asText()).isEqualTo("Пілінг стари");
        assertThat(after.path("suggestedName").asText()).isEqualTo("Пілінг старий");
        assertThat(audit.get("b") + " " + audit.get("a")).as("no requester data in the audit row")
                .doesNotContain("it502-audit-s", "@", "380501119901", requester.toString());
    }

    @Test
    @DisplayName("audit CATEGORY edit: before/after hold exactly {displayName, initialServiceName}, no requester data")
    void should_auditCategoryEditWithExplicitFieldsOnly_when_requesterHasContactData() throws Exception {
        UUID requester = insertUser("it502-audit-c@beautica.test", "INDEPENDENT_MASTER", "Аудит", "Категорія",
                "+380501119902");
        long id = insertCategory("IT502_AUDIT", "Татуаж стари", "PENDING", requester,
                Instant.parse("2020-07-03T10:00:00Z"));

        ResponseEntity<String> response = patch("CATEGORY", id, Map.of("name", "Татуаж"));

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        Map<String, Object> audit = onlyAuditRow(String.valueOf(id));
        assertThat(audit.get("actor")).isEqualTo(ACTOR);
        assertThat(audit.get("action")).isEqualTo("EDIT_REQUEST_NAME");
        assertThat(audit.get("target_type")).isEqualTo("CATEGORY_REQUEST");
        JsonNode before = objectMapper.readTree((String) audit.get("b"));
        JsonNode after = objectMapper.readTree((String) audit.get("a"));
        assertThat(keys(before)).containsExactlyInAnyOrder("displayName", "initialServiceName");
        assertThat(keys(after)).containsExactlyInAnyOrder("displayName", "initialServiceName");
        assertThat(before.path("displayName").asText()).isEqualTo("Татуаж стари");
        assertThat(after.path("displayName").asText()).isEqualTo("Татуаж");
        assertThat(after.path("initialServiceName").isNull()).as("absent initialServiceName keeps NULL").isTrue();
        assertThat(audit.get("b") + " " + audit.get("a")).as("no requester data in the audit row")
                .doesNotContain("it502-audit-c", "@", "380501119902", requester.toString());
    }
}
