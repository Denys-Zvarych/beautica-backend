package com.beautica.catalog.admin;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.TokenGenerator;
import com.beautica.catalog.CatalogCacheEvictor;
import com.beautica.service.ServiceTestFixtures;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * B505 (SVC-57) — {@code DELETE /api/v1/internal/catalog/categories/{name}|service-types/{id}} through the REAL chain
 * (Tomcat, Security, {@code InternalApiKeyFilter} with the catalog-only key, MVC) on Postgres, read back through the
 * apps' own (cached) pickers.
 *
 * <p>Own rows: categories {@value #CATEGORY} / {@value #CATEGORY_2} / {@value #REQUEST} and types under them, removed in
 * {@link #cleanUp()} in FK order (definitions on own rows → types (V73 RESTRICT) → the fixture's suggestions and
 * categories). {@code catalog_admin_audit} is truncated by {@code cleanDb}.
 */
@DisplayName("InternalCatalogController (IT) — delete an unused category / service type (B505)")
@TestPropertySource(properties = "app.internal-catalog-key=" + InternalCatalogDeleteIT.CATALOG_KEY)
class InternalCatalogDeleteIT extends AbstractIntegrationTest {

    static final String CATALOG_KEY = "test-internal-catalog-key-eeeeeeeeeeeeeeee";
    private static final String BASE = "/api/v1/internal/catalog";
    private static final String ACTOR = "beautica.ua@gmail.com";
    private static final String CATEGORY = "IT505_CAT";
    private static final String CATEGORY_2 = "IT505_CAT_TWO";
    private static final String REQUEST = "IT505_REQ";
    private static final Instant CREATED = Instant.parse("2020-01-01T10:00:00Z");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TokenGenerator tokenGenerator;
    @Autowired private CatalogCacheEvictor cacheEvictor;
    @Autowired private CatalogEntryAdminService adminService;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private CacheManager cacheManager;

    private CatalogRequestFixture fixture;
    private ServiceTestFixtures serviceFixtures;

    @BeforeEach
    void seed() {
        fixture = new CatalogRequestFixture(jdbcTemplate, passwordEncoder, tokenGenerator);
        serviceFixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        fixture.insertCategory(CATEGORY, "Іт505 категорія", "APPROVED", null, CREATED);
        fixture.insertCategory(CATEGORY_2, "Іт505 друга категорія", "APPROVED", null, CREATED);
        cacheEvictor.evictAll();
    }

    /** FK order: definitions on own types / categories → own types → fixture (suggestions, categories). */
    @AfterEach
    void cleanUp() {
        String ownCategories = "(?, ?, ?)";
        jdbcTemplate.update("DELETE FROM service_definitions WHERE category IN " + ownCategories + " OR service_type_id "
                + "IN (SELECT id FROM service_types WHERE platform_category_name IN " + ownCategories + ")",
                CATEGORY, CATEGORY_2, REQUEST, CATEGORY, CATEGORY_2, REQUEST);
        jdbcTemplate.update("DELETE FROM service_types WHERE platform_category_name IN " + ownCategories,
                CATEGORY, CATEGORY_2, REQUEST);
        fixture.cleanup();
        cacheEvictor.evictAll();
    }

    // ── seeding ────────────────────────────────────────────────────────────────

    private UUID insertType(String category, String nameUk, String slug) {
        UUID id = UUID.randomUUID();
        UUID bucket = jdbcTemplate.queryForObject("SELECT id FROM service_categories ORDER BY id LIMIT 1", UUID.class);
        jdbcTemplate.update("INSERT INTO service_types (id, category_id, name_uk, name_en, slug, is_active, "
                + "platform_category_name) VALUES (?, ?, ?, '', ?, true, ?)", id, bucket, nameUk, slug, category);
        return id;
    }

    /** A definition with an explicit {@code category} string (may differ from its type's: a legacy row). */
    private void insertDefinition(String category, UUID typeId, boolean active) {
        jdbcTemplate.update("INSERT INTO service_definitions (id, owner_type, owner_id, name, category, service_type_id, "
                + "base_duration_minutes, price_type, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                + "VALUES (?, 'SALON', ?, 'Іт505 послуга', ?, ?, 60, 'FIXED', 500.00, 0, ?, NOW(), NOW())",
                UUID.randomUUID(), UUID.randomUUID(), category, typeId, active);
    }

    private boolean typeExists(UUID id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM service_types WHERE id = ?)", Boolean.class, id));
    }

    private boolean categoryExists(String name) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM platform_categories WHERE name = ?)", Boolean.class, name));
    }

    private List<Map<String, Object>> auditRows(String targetId) {
        return jdbcTemplate.queryForList("SELECT actor, action, target_type, before::text AS b, after::text AS a "
                + "FROM catalog_admin_audit WHERE target_id = ?", targetId);
    }

    private Map<String, Object> jsonMap(Object json) throws Exception {
        return objectMapper.readValue((String) json, new TypeReference<>() {});
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    private ResponseEntity<String> delete(String path, String key, String actor) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.set("X-Internal-Key", key);
        }
        if (actor != null) {
            headers.set(AdminActor.HEADER, actor);
        }
        return restTemplate.exchange(BASE + path, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> deleteType(UUID id) {
        return delete("/service-types/" + id, CATALOG_KEY, ACTOR);
    }

    private ResponseEntity<String> deleteCategory(String name) {
        return delete("/categories/" + name, CATALOG_KEY, ACTOR);
    }

    private static void assertNoContent(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /** {@code {success:false, data:{code:"CATALOG_IN_USE", <counts>}}} — exactly the expected fields. */
    private void assertInUse(ResponseEntity<String> response, Map<String, Long> counts) throws Exception {
        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("success").asBoolean(true)).isFalse();
        Map<String, Object> data = objectMapper.convertValue(body.path("data"), new TypeReference<>() {});
        Map<String, Object> expected = new java.util.LinkedHashMap<>();
        expected.put("code", "CATALOG_IN_USE");
        counts.forEach((k, v) -> expected.put(k, v.intValue()));
        assertThat(data).as("body: %s", response.getBody()).containsExactlyInAnyOrderEntriesOf(expected);
    }

    private String conflictCode(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        return objectMapper.readTree(response.getBody()).path("data").path("code").asText();
    }

    private List<String> pickerTypeIds(String category) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/service-types?categoryName=" + category, String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> ids = new ArrayList<>();
        objectMapper.readTree(response.getBody()).path("data").forEach(t -> ids.add(t.path("id").asText()));
        return ids;
    }

    private List<String> approvedCategoryNames(String token) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/service-categories/approved",
                HttpMethod.GET, new HttpEntity<>(serviceFixtures.bearerHeaders(token)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> names = new ArrayList<>();
        objectMapper.readTree(response.getBody()).path("data").forEach(c -> names.add(c.path("name").asText()));
        return names;
    }

    // ── service type ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unused type: 204, gone from the (warmed) picker at once, one DELETE audit row with before filled")
    void should_deleteAndEvictAndAudit_when_typeUnused() throws Exception {
        UUID type = insertType(CATEGORY, "Іт505 тип А", "it505-type-a");
        assertThat(pickerTypeIds(CATEGORY)).contains(type.toString()); // warms service-types
        Cache masterServices = cacheManager.getCache("masterServices");
        masterServices.put("it505-sentinel", "warm");

        ResponseEntity<String> response = deleteType(type);

        assertNoContent(response);
        assertThat(typeExists(type)).isFalse();
        assertThat(pickerTypeIds(CATEGORY)).as("evicted after commit").doesNotContain(type.toString());
        assertThat(masterServices.get("it505-sentinel")).as("per-master label cache left warm").isNotNull();
        masterServices.evict("it505-sentinel");
        List<Map<String, Object>> rows = auditRows(type.toString());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("actor", ACTOR).containsEntry("action", "DELETE")
                .containsEntry("target_type", "SERVICE_TYPE");
        assertThat(rows.get(0).get("a")).as("a delete has no after").isNull();
        assertThat(jsonMap(rows.get(0).get("b"))).containsExactlyInAnyOrderEntriesOf(Map.of(
                "id", type.toString(), "platformCategoryName", CATEGORY, "slug", "it505-type-a",
                "nameUk", "Іт505 тип А", "nameEn", "", "active", true));
    }

    @Test
    @DisplayName("a type used only by an INACTIVE definition: 409 CATALOG_IN_USE {definitions: 1}; nothing changes")
    void should_return409WithCount_when_typeUsedByInactiveDefinition() throws Exception {
        UUID type = insertType(CATEGORY, "Іт505 тип А", "it505-type-a");
        insertDefinition(CATEGORY, type, false);

        ResponseEntity<String> response = deleteType(type);

        assertInUse(response, Map.of("definitions", 1L));
        assertThat(typeExists(type)).isTrue();
        assertThat(auditRows(type.toString())).isEmpty();
    }

    // ── category ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an unused category: 204, gone from the (warmed) approved picker at once, audit before filled; "
            + "a DECIDED suggestion does not block and is kept as history")
    void should_deleteAndEvictAndAudit_when_categoryUnused() throws Exception {
        UUID decided = fixture.insertSuggestion(CATEGORY, "Іт505 відхилена", "REJECTED", null, CREATED);
        String token = serviceFixtures.createClientAndGetToken("it505-client@beautica.test");
        assertThat(approvedCategoryNames(token)).contains(CATEGORY); // warms approved-categories

        ResponseEntity<String> response = deleteCategory(CATEGORY);

        assertNoContent(response);
        assertThat(categoryExists(CATEGORY)).isFalse();
        assertThat(approvedCategoryNames(token)).as("evicted after commit").doesNotContain(CATEGORY);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM service_type_suggestion WHERE id = ?",
                Integer.class, decided)).as("decided suggestion kept").isEqualTo(1);
        List<Map<String, Object>> rows = auditRows(CATEGORY);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("action", "DELETE").containsEntry("target_type", "CATEGORY");
        assertThat(rows.get(0).get("a")).isNull();
        Map<String, Object> before = jsonMap(rows.get(0).get("b"));
        assertThat(before).containsOnlyKeys("name", "displayName", "status", "active", "initialServiceName",
                "serviceCategoryId");
        assertThat(before).containsEntry("name", CATEGORY).containsEntry("displayName", "Іт505 категорія")
                .containsEntry("status", "APPROVED").containsEntry("active", true);
    }

    @Test
    @DisplayName("a category with a (hidden) type: 409 CATALOG_IN_USE {serviceTypes: 1, definitions: 0, ...}")
    void should_return409_when_categoryHasType() throws Exception {
        UUID type = insertType(CATEGORY, "Іт505 тип А", "it505-type-a");
        jdbcTemplate.update("UPDATE service_types SET is_active = false WHERE id = ?", type);

        ResponseEntity<String> response = deleteCategory(CATEGORY);

        assertInUse(response, Map.of("serviceTypes", 1L, "definitions", 0L, "pendingSuggestions", 0L));
        assertThat(categoryExists(CATEGORY)).isTrue();
        assertThat(auditRows(CATEGORY)).isEmpty();
    }

    /** Falsify: without the definitions count no FK catches this, and the delete would be a 204. */
    @Test
    @DisplayName("a category named only by a service_definitions.category string (no FK, no type): 409 "
            + "{definitions: 1}")
    void should_return409_when_categoryUsedOnlyByDefinitionString() throws Exception {
        UUID otherType = insertType(CATEGORY_2, "Іт505 тип Б", "it505-type-b");
        insertDefinition(CATEGORY, otherType, false);

        ResponseEntity<String> response = deleteCategory(CATEGORY);

        assertInUse(response, Map.of("serviceTypes", 0L, "definitions", 1L, "pendingSuggestions", 0L));
        assertThat(categoryExists(CATEGORY)).isTrue();
    }

    @Test
    @DisplayName("a PENDING suggestion naming the category: 409 {pendingSuggestions: 1}")
    void should_return409_when_pendingSuggestionNamesCategory() throws Exception {
        fixture.insertSuggestion(CATEGORY, "Іт505 очікує", "PENDING", null, CREATED);

        ResponseEntity<String> response = deleteCategory(CATEGORY);

        assertInUse(response, Map.of("serviceTypes", 0L, "definitions", 0L, "pendingSuggestions", 1L));
        assertThat(categoryExists(CATEGORY)).isTrue();
    }

    @Test
    @DisplayName("refused inputs: pending request 409 CATEGORY_NOT_APPROVED, unknown 404, bad name / no actor 400, "
            + "no key 401 — nothing deleted or audited")
    void should_refuse_when_inputInvalid() throws Exception {
        fixture.insertCategory(REQUEST, "Іт505 запит", "PENDING", null, CREATED);
        UUID type = insertType(CATEGORY, "Іт505 тип А", "it505-type-a");

        String notApproved = conflictCode(deleteCategory(REQUEST));
        ResponseEntity<String> unknownCategory = deleteCategory("IT505_NONE");
        ResponseEntity<String> unknownType = deleteType(UUID.randomUUID());
        ResponseEntity<String> badName = deleteCategory("it505-bad");
        ResponseEntity<String> noActor = delete("/service-types/" + type, CATALOG_KEY, null);
        ResponseEntity<String> noKey = delete("/service-types/" + type, null, ACTOR);

        assertThat(notApproved).isEqualTo("CATEGORY_NOT_APPROVED");
        assertThat(unknownCategory.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unknownType.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(badName.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noActor.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(categoryExists(REQUEST)).isTrue();
        assertThat(typeExists(type)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM catalog_admin_audit", Integer.class)).isZero();
    }

    // ── concurrency ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("race: a service created on the type commits while the delete waits on the row lock → 409, kept")
    void should_return409_when_serviceCreatedConcurrentlyBeforeDelete() throws Exception {
        UUID type = insertType(CATEGORY, "Іт505 тип А", "it505-type-a");
        HeldTransactionRace race = new HeldTransactionRace(transactionManager, jdbcTemplate);

        ResponseEntity<String> response = race.run(() -> insertDefinition(CATEGORY, type, true), () -> deleteType(type));

        assertInUse(response, Map.of("definitions", 1L));
        assertThat(typeExists(type)).isTrue();
    }

    @Test
    @DisplayName("race: a service create arriving while the delete holds the lock fails its FK after the delete "
            + "commits — no service on a deleted type slips in")
    void should_failConcurrentCreate_when_deleteHoldsTheLock() throws Exception {
        UUID type = insertType(CATEGORY, "Іт505 тип А", "it505-type-a");
        HeldTransactionRace race = new HeldTransactionRace(transactionManager, jdbcTemplate);

        Throwable thrown = catchThrowable(() -> race.run(
                () -> adminService.deleteServiceType(AdminActor.of(ACTOR), type),
                () -> {
                    insertDefinition(CATEGORY, type, true);
                    return null;
                }));

        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(typeExists(type)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM service_definitions WHERE service_type_id = ?",
                Integer.class, type)).isZero();
        assertThat(auditRows(type.toString())).extracting(r -> r.get("action")).containsExactly("DELETE");
    }

    @Test
    @DisplayName("race: a type added to the category commits while the category delete waits → 409 {serviceTypes: 1}")
    void should_return409_when_typeCreatedConcurrentlyBeforeCategoryDelete() throws Exception {
        HeldTransactionRace race = new HeldTransactionRace(transactionManager, jdbcTemplate);

        ResponseEntity<String> response = race.run(() -> insertType(CATEGORY, "Іт505 тип А", "it505-type-a"),
                () -> deleteCategory(CATEGORY));

        assertInUse(response, Map.of("serviceTypes", 1L, "definitions", 0L, "pendingSuggestions", 0L));
        assertThat(categoryExists(CATEGORY)).isTrue();
    }
}
