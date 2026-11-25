package com.beautica.catalog.admin;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.TokenGenerator;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.catalog.CatalogCacheEvictor;
import com.beautica.common.TimeZones;
import com.beautica.service.ServiceTestFixtures;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.entity.PriceType;
import com.beautica.support.BookableMasterSeeder;
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
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B504 (SVC-56) — {@code /api/v1/internal/catalog/categories|service-types} through the REAL chain (Tomcat, Security,
 * {@code InternalApiKeyFilter} with the catalog-only key, MVC) on Postgres, read back through the apps' own endpoints.
 *
 * <p>Own rows: category {@value #CATEGORY} with two types, removed in {@link #cleanUp()} (types first: V73 FK RESTRICT).
 * The "still bookable" case hides a SEEDED category and type (a real bookable service needs them); their original
 * {@code active} / names are restored by JDBC in {@link #cleanUp()} and every catalog cache is emptied, so no sibling
 * class sees the change. {@code catalog_admin_audit} is truncated by {@code cleanDb}.
 */
@DisplayName("InternalCatalogController (IT) — rename / hide / unhide category and service type")
@TestPropertySource(properties = "app.internal-catalog-key=" + InternalCatalogControllerIT.CATALOG_KEY)
class InternalCatalogControllerIT extends AbstractIntegrationTest {

    static final String CATALOG_KEY = "test-internal-catalog-key-dddddddddddddddd";
    private static final String BASE = "/api/v1/internal/catalog";
    private static final String ACTOR = "beautica.ua@gmail.com";
    private static final String CATEGORY = "IT504_CAT";
    private static final String CATEGORY_LABEL = "Ітпятсотчотири категорія";
    private static final String TYPE_A = "Ітсістмасаж стоп";
    private static final String TYPE_B = "Ітсістпілінг обличчя";
    /** A second own category, used only by the D20 "switch to ANOTHER hidden category" case. */
    private static final String CATEGORY_2 = "IT504_CAT_TWO";
    private static final String CATEGORY_SUGGESTION = "CATEGORY|" + CATEGORY + "|null";
    private static final String TYPE_A_SUGGESTION = "SERVICE|" + CATEGORY + "|it504-type-a";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TokenGenerator tokenGenerator;
    @Autowired private CatalogCacheEvictor cacheEvictor;
    @Autowired private com.beautica.service.service.SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    @Autowired private CacheManager cacheManager;

    private CatalogRequestFixture fixture;
    private ServiceTestFixtures serviceFixtures;
    private UUID typeA;
    private UUID typeB;
    private final List<Runnable> restores = new ArrayList<>();

    @BeforeEach
    void seed() {
        fixture = new CatalogRequestFixture(jdbcTemplate, passwordEncoder, tokenGenerator);
        serviceFixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        fixture.insertCategory(CATEGORY, CATEGORY_LABEL, "APPROVED", null, Instant.parse("2020-01-01T10:00:00Z"));
        typeA = insertType(TYPE_A, "Zyxquorbel footwork", "it504-type-a");
        typeB = insertType(TYPE_B, "", "it504-type-b");
        cacheEvictor.evictAll();
    }

    /**
     * Seeded rows restored and booking-path rows removed first ({@link #restores}); then any service still on an own
     * type (created over HTTP by the D4 / D20 cases: assignments before definitions), then the own types (V73 FK
     * RESTRICT to the category), then the fixture's categories.
     */
    @AfterEach
    void cleanUp() {
        restores.forEach(Runnable::run);
        String ownDefinitions = "SELECT sd.id FROM service_definitions sd JOIN service_types st ON st.id = "
                + "sd.service_type_id WHERE st.platform_category_name IN (?, ?)";
        jdbcTemplate.update("DELETE FROM master_services WHERE service_def_id IN (" + ownDefinitions + ")",
                CATEGORY, CATEGORY_2);
        jdbcTemplate.update("DELETE FROM service_definitions WHERE id IN (" + ownDefinitions + ")", CATEGORY, CATEGORY_2);
        jdbcTemplate.update("DELETE FROM service_types WHERE platform_category_name IN (?, ?)", CATEGORY, CATEGORY_2);
        fixture.cleanup();
        cacheEvictor.evictAll();
    }

    private UUID insertType(String nameUk, String nameEn, String slug) {
        return insertType(CATEGORY, nameUk, nameEn, slug);
    }

    private UUID insertType(String category, String nameUk, String nameEn, String slug) {
        UUID id = UUID.randomUUID();
        UUID bucket = jdbcTemplate.queryForObject("SELECT id FROM service_categories ORDER BY id LIMIT 1", UUID.class);
        jdbcTemplate.update("INSERT INTO service_types (id, category_id, name_uk, name_en, slug, is_active, "
                + "platform_category_name) VALUES (?, ?, ?, ?, ?, true, ?)", id, bucket, nameUk, nameEn, slug, category);
        return id;
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

    private ResponseEntity<String> patchCategory(String name, Map<String, Object> body) throws Exception {
        return call(HttpMethod.PATCH, BASE + "/categories/" + name, CATALOG_KEY, ACTOR, body);
    }

    private ResponseEntity<String> patchType(Object id, Map<String, Object> body) throws Exception {
        return call(HttpMethod.PATCH, BASE + "/service-types/" + id, CATALOG_KEY, ACTOR, body);
    }

    private JsonNode json(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    private static void assertOk(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** The raw wire shape of a refused write: {@code {success: false, data: {code}}} — the code is the ONLY field. */
    private void assertConflictCode(ResponseEntity<String> response, String code) throws Exception {
        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = json(response);
        List<String> dataFields = new ArrayList<>();
        body.path("data").fieldNames().forEachRemaining(dataFields::add);
        assertThat(body.path("success").isBoolean() && !body.path("success").asBoolean())
                .as("success:false, body=%s", response.getBody()).isTrue();
        assertThat(dataFields).as("data carries only the stable code, body=%s", response.getBody())
                .containsExactly("code");
        assertThat(body.path("data").path("code").asText()).isEqualTo(code);
    }

    /**
     * A 400 from a {@code BusinessException}. Its message is redacted to a generic text on the wire, so the cause is
     * proven by each caller's positive control (the same request accepted once the row is visible again).
     */
    private void assertBadRequest(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).path("success").asBoolean(true)).isFalse();
    }

    /** The apps' live type picker: {@code GET /service-types?categoryName=} (permitAll). */
    private List<String> pickerTypeNames(String category) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/service-types?categoryName=" + category, String.class);
        assertOk(response);
        List<String> names = new ArrayList<>();
        json(response).path("data").forEach(t -> names.add(t.path("nameUk").asText()));
        return names;
    }

    /**
     * The cached type search: {@code GET /service-types?q=} (permitAll, cache {@code service-type-search}). Queried by the
     * Latin {@code name_en}: pg_trgm extracts no trigrams from Cyrillic under the test container's C locale.
     */
    private List<String> searchTypeIds(String q) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/service-types?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8), String.class);
        assertOk(response);
        List<String> ids = new ArrayList<>();
        json(response).path("data").forEach(t -> ids.add(t.path("id").asText()));
        return ids;
    }

    /** The apps' category picker: {@code GET /service-categories/approved} (any auth, cache approved-categories). */
    private List<String> approvedCategoryNames(String token) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/service-categories/approved",
                HttpMethod.GET, new HttpEntity<>(serviceFixtures.bearerHeaders(token)), String.class);
        assertOk(response);
        List<String> names = new ArrayList<>();
        json(response).path("data").forEach(c -> names.add(c.path("name").asText()));
        return names;
    }

    /**
     * The search box, national (no place): one {@code "TYPE|categoryKey|serviceTypeSlug"} per suggestion. Cyrillic is
     * matched in memory (folded labels), so it works under the container's C locale; the URI template encodes it.
     */
    private List<String> suggestions(String q) throws Exception {
        ResponseEntity<String> response =
                restTemplate.getForEntity("/api/v1/search/suggestions?q={q}&limit=8", String.class, q);
        assertOk(response);
        List<String> rows = new ArrayList<>();
        json(response).path("data").forEach(s -> rows.add(s.path("type").asText() + "|"
                + s.path("categoryKey").asText() + "|" + s.path("serviceTypeSlug").asText()));
        return rows;
    }

    /** Service ids in the public salon catalogue {@code GET /salons/{id}/services} (anonymous). */
    private List<String> salonCatalogueServiceIds(UUID salonId) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/salons/" + salonId + "/services",
                String.class);
        assertOk(response);
        List<String> ids = new ArrayList<>();
        json(response).path("data").path("categories")
                .forEach(g -> g.path("services").forEach(sd -> ids.add(sd.path("id").asText())));
        return ids;
    }

    /** The public master profile list {@code GET /masters/{id}/services} (anonymous): definition id -> label. */
    private Map<String, String> masterServiceLabels(UUID masterId) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity("/api/v1/masters/" + masterId + "/services",
                String.class);
        assertOk(response);
        Map<String, String> labels = new HashMap<>();
        json(response).path("data").forEach(ms -> labels.put(ms.path("serviceDefinition").path("id").asText(),
                ms.path("serviceDefinition").path("categoryDisplayName").asText()));
        return labels;
    }

    /** {@code categoryName} (the label) of one booking's row in {@code GET /clients/me/timeline}. */
    private String timelineLabel(String clientToken, String bookingId) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/clients/me/timeline", HttpMethod.GET,
                new HttpEntity<>(serviceFixtures.bearerHeaders(clientToken)), String.class);
        assertOk(response);
        List<String> labels = new ArrayList<>();
        json(response).path("data").path("data").forEach(t -> { // ApiResponse<PageResponse<...>>
            if (bookingId.equals(t.path("bookingId").asText())) {
                assertThat(t.path("categoryKey").asText()).as("raw slug field unchanged").isEqualTo(CATEGORY);
                labels.add(t.path("categoryName").asText());
            }
        });
        assertThat(labels).as("timeline row of booking %s, body=%s", bookingId, response.getBody()).hasSize(1);
        return labels.get(0);
    }

    /** {@code categoryDisplayName} of {@code GET /bookings/{id}} as the booking's client. */
    private String bookingDetailLabel(String clientToken, String bookingId) throws Exception {
        ResponseEntity<String> detail = restTemplate.exchange("/api/v1/bookings/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(serviceFixtures.bearerHeaders(clientToken)), String.class);
        assertOk(detail);
        return json(detail).path("data").path("categoryDisplayName").asText();
    }

    /** {@code POST /bookings} as {@code clientToken} for the 10:00 Kyiv slot two days out; asserts 201, returns id. */
    private String bookTenOClock(UUID masterId, UUID assignmentId, String clientToken) throws Exception {
        ResponseEntity<String> booked = restTemplate.exchange("/api/v1/bookings", HttpMethod.POST,
                new HttpEntity<>(new CreateBookingRequest(masterId, assignmentId,
                        ZonedDateTime.of(LocalDate.now(TimeZones.KYIV).plusDays(2), LocalTime.of(10, 0), TimeZones.KYIV),
                        null, null, false), serviceFixtures.bearerHeaders(clientToken)), String.class);
        assertThat(booked.getStatusCode()).as(booked.getBody()).isEqualTo(HttpStatus.CREATED);
        return json(booked).path("data").path("id").asText();
    }

    /** A logged-in SALON_OWNER with one salon (real HTTP), for the create / edit paths. */
    private record Owner(String token, UUID salonId) {}

    private Owner ownerWithSalon() throws Exception {
        String token = serviceFixtures.createSalonOwnerAndGetToken("it504-owner-" + UUID.randomUUID() + "@beautica.test");
        return new Owner(token, serviceFixtures.createSalon(token, "Іт504 салон власника"));
    }

    /** {@code POST /salons/{id}/services} — the create path a hidden category / type must block (new use). */
    private ResponseEntity<String> createSalonService(Owner owner, String category, UUID typeId) {
        return restTemplate.exchange("/api/v1/salons/" + owner.salonId() + "/services", HttpMethod.POST,
                new HttpEntity<>(new CreateServiceDefinitionRequest("Іт504 нова послуга", null, category, 60, 0,
                        PriceType.FIXED, new BigDecimal("500.00"), null, null, typeId),
                        serviceFixtures.bearerHeaders(owner.token())), String.class);
    }

    /** {@code PATCH /services/{id}} as the owner with a raw JSON body (the mobile edit form re-sends both fields). */
    private ResponseEntity<String> patchService(Owner owner, UUID defId, Map<String, Object> body) throws Exception {
        return restTemplate.exchange("/api/v1/services/" + defId, HttpMethod.PATCH,
                new HttpEntity<>(objectMapper.writeValueAsString(body), serviceFixtures.bearerHeaders(owner.token())),
                String.class);
    }

    private UUID insertSalonDefinition(UUID salonId, String category, UUID typeId) {
        UUID defId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO service_definitions (id, owner_type, owner_id, name, category, service_type_id, "
                + "base_duration_minutes, price_type, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                + "VALUES (?, 'SALON', ?, ?, ?, ?, 60, 'FIXED', 500.00, 0, true, NOW(), NOW())",
                defId, salonId, "Іт504 наявна послуга", category, typeId);
        return defId;
    }

    private List<Map<String, Object>> auditRows(String targetId) {
        return jdbcTemplate.queryForList("SELECT actor, action, target_type, before::text AS b, after::text AS a "
                + "FROM catalog_admin_audit WHERE target_id = ?", targetId);
    }

    private static Map<String, Object> rowWithAction(List<Map<String, Object>> rows, String action) {
        return rows.stream().filter(r -> action.equals(r.get("action"))).findFirst().orElseThrow();
    }

    private Map<String, Object> jsonMap(Object json) throws Exception {
        return objectMapper.readValue((String) json, new TypeReference<>() {});
    }

    private static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    // ── lists ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /categories lists every category with active, usage and type counts")
    void should_listCategoryWithCounts_when_called() throws Exception {
        ResponseEntity<String> response = call(HttpMethod.GET, BASE + "/categories", CATALOG_KEY, null, null);

        assertOk(response);
        JsonNode row = null;
        for (JsonNode node : json(response).path("data")) {
            if (CATEGORY.equals(node.path("name").asText())) {
                row = node;
            }
        }
        assertThat(row).as("seeded category listed").isNotNull();
        assertThat(row.path("displayName").asText()).isEqualTo(CATEGORY_LABEL);
        assertThat(row.path("status").asText()).isEqualTo("APPROVED");
        assertThat(row.path("active").asBoolean()).isTrue();
        assertThat(row.path("usageCount").asLong()).isZero();
        assertThat(row.path("typeCount").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("GET /categories/{name}/service-types lists hidden types too; unknown name 404, malformed 400")
    void should_listAllTypes_when_categoryKnown() throws Exception {
        assertOk(patchType(typeB, body("active", false)));

        ResponseEntity<String> response =
                call(HttpMethod.GET, BASE + "/categories/" + CATEGORY + "/service-types", CATALOG_KEY, null, null);
        ResponseEntity<String> unknown =
                call(HttpMethod.GET, BASE + "/categories/IT504_NOPE/service-types", CATALOG_KEY, null, null);
        ResponseEntity<String> malformed =
                call(HttpMethod.GET, BASE + "/categories/it504-bad/service-types", CATALOG_KEY, null, null);

        assertOk(response);
        Map<String, Boolean> activeByName = new HashMap<>();
        json(response).path("data").forEach(t -> activeByName.put(t.path("nameUk").asText(), t.path("active").asBoolean()));
        assertThat(activeByName).containsExactlyInAnyOrderEntriesOf(Map.of(TYPE_A, true, TYPE_B, false));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── type rename / hide ─────────────────────────────────────────────────────

    @Test
    @DisplayName("renaming a type shows the new name at once in the picker and the (cached) type search; slug kept")
    void should_showNewNameImmediately_when_typeRenamed() throws Exception {
        assertThat(searchTypeIds("zyxquorbel")).contains(typeA.toString()); // warms service-type-search

        ResponseEntity<String> response =
                patchType(typeA, body("nameUk", "Ітвосьмимасаж  ніг", "nameEn", "Plimvortex footwork"));

        assertOk(response);
        assertThat(pickerTypeNames(CATEGORY)).containsExactlyInAnyOrder("Ітвосьмимасаж ніг", TYPE_B);
        assertThat(searchTypeIds("plimvortex")).contains(typeA.toString());
        assertThat(searchTypeIds("zyxquorbel")).as("stale cached hit evicted").doesNotContain(typeA.toString());
        assertThat(jdbcTemplate.queryForObject("SELECT slug FROM service_types WHERE id = ?", String.class, typeA))
                .isEqualTo("it504-type-a");
    }

    @Test
    @DisplayName("hiding a type drops it from the picker and the type search")
    void should_dropTypeFromPickerAndSearch_when_typeHidden() throws Exception {
        assertThat(searchTypeIds("zyxquorbel")).contains(typeA.toString()); // warms service-type-search

        assertOk(patchType(typeA, body("active", false)));

        assertThat(pickerTypeNames(CATEGORY)).containsExactly(TYPE_B);
        assertThat(searchTypeIds("zyxquorbel")).doesNotContain(typeA.toString());
    }

    @Test
    @DisplayName("a type renamed onto another type's name (hidden included) is 409 TYPE_EXISTS; unknown id 404")
    void should_return409_when_typeNameTaken() throws Exception {
        assertOk(patchType(typeB, body("active", false)));

        ResponseEntity<String> duplicate = patchType(typeA, body("nameUk", TYPE_B.toUpperCase()));
        ResponseEntity<String> unknown = patchType(UUID.randomUUID(), body("nameUk", "Нова назва"));
        ResponseEntity<String> malformedId = patchType("not-a-uuid", body("nameUk", "Нова назва"));

        assertConflictCode(duplicate, "TYPE_EXISTS");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(malformedId.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(pickerTypeNames(CATEGORY)).containsExactly(TYPE_A);
    }

    // ── category rename / hide ─────────────────────────────────────────────────

    @Test
    @DisplayName("hiding a category drops it from the approved list; unhide + rename brings it back with the new name")
    void should_dropAndRestoreCategory_when_hiddenThenUnhidden() throws Exception {
        String token = serviceFixtures.createClientAndGetToken("it504-client@beautica.test");
        assertThat(approvedCategoryNames(token)).contains(CATEGORY); // warms approved-categories

        assertOk(patchCategory(CATEGORY, body("active", false)));
        List<String> afterHide = approvedCategoryNames(token);
        assertOk(patchCategory(CATEGORY, body("active", true, "displayName", "Ітпятсотчотири нова")));

        assertThat(afterHide).doesNotContain(CATEGORY);
        ResponseEntity<String> approved = restTemplate.exchange("/api/v1/service-categories/approved",
                HttpMethod.GET, new HttpEntity<>(serviceFixtures.bearerHeaders(token)), String.class);
        List<String> labels = new ArrayList<>();
        json(approved).path("data").forEach(c -> {
            if (CATEGORY.equals(c.path("name").asText())) {
                labels.add(c.path("displayName").asText());
            }
        });
        assertThat(labels).containsExactly("Ітпятсотчотири нова");
    }

    @Test
    @DisplayName("category rename onto another live category's label is 409 CATEGORY_NAME_EXISTS; pending row 409 "
            + "CATEGORY_NOT_APPROVED; unknown 404")
    void should_return409_when_categoryEditRefused() throws Exception {
        String takenLabel = jdbcTemplate.queryForObject("SELECT display_name FROM platform_categories "
                + "WHERE status = 'APPROVED' AND name <> ? ORDER BY name LIMIT 1", String.class, CATEGORY);
        fixture.insertCategory("IT504_PEND", "Ітпятсотчотири заявка", "PENDING", null,
                Instant.parse("2020-01-02T10:00:00Z"));

        ResponseEntity<String> duplicate = patchCategory(CATEGORY, body("displayName", takenLabel.toLowerCase()));
        ResponseEntity<String> pending = patchCategory("IT504_PEND", body("active", true));
        ResponseEntity<String> unknown = patchCategory("IT504_NOPE", body("active", false));

        assertConflictCode(duplicate, "CATEGORY_NAME_EXISTS");
        assertConflictCode(pending, "CATEGORY_NOT_APPROVED");
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbcTemplate.queryForObject("SELECT active FROM platform_categories WHERE name = 'IT504_PEND'",
                Boolean.class)).isFalse();
    }

    @Test
    @DisplayName("missing actor, empty body, invalid name → 400; no key → 401; nothing written")
    void should_rejectBadWrites_when_inputInvalid() throws Exception {
        ResponseEntity<String> noActor =
                call(HttpMethod.PATCH, BASE + "/categories/" + CATEGORY, CATALOG_KEY, null, body("active", false));
        ResponseEntity<String> empty = patchCategory(CATEGORY, body());
        ResponseEntity<String> badName = patchType(typeA, body("nameUk", "Масаж 😀"));
        ResponseEntity<String> noKey =
                call(HttpMethod.PATCH, BASE + "/categories/" + CATEGORY, null, ACTOR, body("active", false));

        assertThat(noActor.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(badName.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(noKey.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(auditRows(CATEGORY)).isEmpty();
        assertThat(auditRows(typeA.toString())).isEmpty();
    }

    // ── audit ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("audit rows: RENAME + HIDE for one PATCH, UNHIDE later; an unchanged PATCH writes none")
    void should_writeAuditRows_when_writesChangeSomething() throws Exception {
        assertOk(patchCategory(CATEGORY, body("displayName", "Ітпятсотчотири друга", "active", false)));
        assertOk(patchType(typeA, body("active", false)));
        assertOk(patchType(typeA, body("active", true)));
        assertOk(patchType(typeA, body("nameUk", TYPE_A, "active", true)));

        List<Map<String, Object>> categoryRows = auditRows(CATEGORY);
        assertThat(categoryRows).extracting(r -> r.get("action")).containsExactlyInAnyOrder("RENAME", "HIDE");
        assertThat(categoryRows).allSatisfy(r -> {
            assertThat(r.get("actor")).isEqualTo(ACTOR);
            assertThat(r.get("target_type")).isEqualTo("CATEGORY");
        });
        Map<String, Object> rename = rowWithAction(categoryRows, "RENAME");
        assertThat(jsonMap(rename.get("b"))).containsExactly(Map.entry("displayName", CATEGORY_LABEL));
        assertThat(jsonMap(rename.get("a"))).containsExactly(Map.entry("displayName", "Ітпятсотчотири друга"));
        assertThat(jsonMap(rowWithAction(categoryRows, "HIDE").get("a"))).containsExactly(Map.entry("active", false));
        List<Map<String, Object>> typeRows = auditRows(typeA.toString());
        assertThat(typeRows).extracting(r -> r.get("action")).containsExactlyInAnyOrder("HIDE", "UNHIDE");
        assertThat(typeRows).extracting(r -> r.get("target_type")).containsOnly("SERVICE_TYPE");
    }

    // ── D4: existing services keep working ─────────────────────────────────────

    @Test
    @DisplayName("hiding the type AND the category of an existing service leaves it bookable (D4)")
    void should_keepExistingServiceBookable_when_itsTypeAndCategoryAreHidden() throws Exception {
        // Keep the seeder off this class's own types (they would block cleanUp's DELETE via the FK).
        jdbcTemplate.update("UPDATE platform_categories SET active = false WHERE name = ?", CATEGORY);
        UUID masterId = BookableMasterSeeder.insertIndependentMaster(jdbcTemplate, "Іт504");
        UUID defId = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, masterId);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, masterId);
        UUID seededType = jdbcTemplate.queryForObject(
                "SELECT service_type_id FROM service_definitions WHERE id = ?", UUID.class, defId);
        String seededCategory = jdbcTemplate.queryForObject(
                "SELECT platform_category_name FROM service_types WHERE id = ?", String.class, seededType);
        restores.add(() -> jdbcTemplate.update("UPDATE service_types SET is_active = true WHERE id = ?", seededType));
        restores.add(() -> jdbcTemplate.update(
                "UPDATE platform_categories SET active = true WHERE name = ?", seededCategory));

        assertOk(patchType(seededType, body("active", false)));
        assertOk(patchCategory(seededCategory, body("active", false)));

        serviceFixtures.bookTenOClockSlot(masterId, LocalDate.now(TimeZones.KYIV).plusDays(2));
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM service_definitions WHERE id = ?",
                Boolean.class, defId)).isTrue();
    }

    // ── D18: existing services keep their category label after a hide ───────

    /**
     * A salon service on this class's own category + type with a bookable master; the booking-path rows are removed in
     * {@link #cleanUp()} ahead of the types they reference (cleanDb runs only after this class's @AfterEach).
     */
    private record SalonService(UUID salonId, UUID masterId, UUID assignmentId, UUID defId) {}

    private SalonService seedSalonServiceOnOwnCategory() {
        UUID salonId = BookableMasterSeeder.insertSalon(jdbcTemplate, "Салон IT504");
        UUID masterId = BookableMasterSeeder.insertSalonMaster(jdbcTemplate, salonId);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, masterId);
        UUID defId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO service_definitions (id, owner_type, owner_id, name, category, service_type_id, "
                + "base_duration_minutes, price_type, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                + "VALUES (?, 'SALON', ?, ?, ?, ?, 60, 'FIXED', 500.00, 0, true, NOW(), NOW())",
                defId, salonId, TYPE_A, CATEGORY, typeA);
        UUID assignmentId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                + "VALUES (?, ?, ?, true, NOW(), NOW())", assignmentId, masterId, defId);
        restores.add(() -> {
            jdbcTemplate.update("DELETE FROM bookings WHERE master_service_id = ?", assignmentId);
            jdbcTemplate.update("DELETE FROM master_services WHERE id = ?", assignmentId);
            jdbcTemplate.update("DELETE FROM service_definitions WHERE id = ?", defId);
        });
        return new SalonService(salonId, masterId, assignmentId, defId);
    }

    @Test
    @DisplayName("D18 — a hidden category's existing services keep its display name: salon catalogue group + row, "
            + "booking detail; the approved picker still drops it")
    void should_keepDisplayName_when_existingServicesCategoryIsHidden() throws Exception {
        SalonService seeded = seedSalonServiceOnOwnCategory();
        UUID salonId = seeded.salonId();
        String token = serviceFixtures.createClientAndGetToken("it504-d18@beautica.test");
        String bookingId = bookTenOClock(seeded.masterId(), seeded.assignmentId(), token);

        assertOk(patchCategory(CATEGORY, body("active", false)));

        ResponseEntity<String> catalogue = restTemplate.getForEntity(
                "/api/v1/salons/" + salonId + "/services", String.class);
        assertOk(catalogue);
        JsonNode group = null;
        for (JsonNode g : json(catalogue).path("data").path("categories")) {
            if (CATEGORY.equals(g.path("category").asText())) {
                group = g;
            }
        }
        assertThat(group).as("group of the hidden category, body=%s", catalogue.getBody()).isNotNull();
        assertThat(group.path("displayName").asText()).isEqualTo(CATEGORY_LABEL);
        assertThat(group.path("services").get(0).path("categoryDisplayName").asText()).isEqualTo(CATEGORY_LABEL);
        ResponseEntity<String> detail = restTemplate.exchange("/api/v1/bookings/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(serviceFixtures.bearerHeaders(token)), String.class);
        assertOk(detail);
        assertThat(json(detail).path("data").path("categoryDisplayName").asText()).isEqualTo(CATEGORY_LABEL);
        assertThat(json(detail).path("data").path("categoryName").asText()).as("raw slug field unchanged")
                .isEqualTo(CATEGORY);
        assertThat(approvedCategoryNames(token)).doesNotContain(CATEGORY);
    }

    @Test
    @DisplayName("D18 — a rename reaches the salon group and GET /masters/{id}/services on the next request")
    void should_showRenamedLabel_when_categoryRenamedAfterCatalogueWasCached() throws Exception {
        SalonService existing = seedSalonServiceOnOwnCategory();
        UUID salonId = existing.salonId();
        String url = "/api/v1/salons/" + salonId + "/services";
        assertOk(restTemplate.getForEntity(url, String.class)); // warms platform-category-labels
        assertThat(masterServiceLabels(existing.masterId())).as("warms masterServices")
                .containsEntry(existing.defId().toString(), CATEGORY_LABEL);

        assertOk(patchCategory(CATEGORY, body("displayName", "Ітпятсотчотири перейменована")));
        // The per-salon salon-service-catalog entry is outside CatalogCacheEvictor by design (60-s TTL bound); drop
        // it so the next read rebuilds from platform-category-labels, the cache under test.
        salonCatalogCacheEvictor.evictCatalogOnly(salonId);

        List<String> labels = new ArrayList<>();
        json(restTemplate.getForEntity(url, String.class)).path("data").path("categories").forEach(g -> {
            if (CATEGORY.equals(g.path("category").asText())) {
                labels.add(g.path("displayName").asText());
            }
        });
        assertThat(labels).containsExactly("Ітпятсотчотири перейменована");
        assertThat(masterServiceLabels(existing.masterId()))
                .as("GET /masters/{id}/services: masterServices evicted by the rename, no TTL wait")
                .containsEntry(existing.defId().toString(), "Ітпятсотчотири перейменована");
    }

    // ── D21: hiding a category drops its still-active types from SERVICE suggestions ──

    @Test
    @DisplayName("D21 — hiding a category drops its active type's SERVICE suggestion; the existing service stays "
            + "bookable and found by serviceTypeSlugs; unhide brings the suggestion back")
    void should_dropTypeSuggestion_when_itsCategoryIsHidden() throws Exception {
        SalonService existing = seedSalonServiceOnOwnCategory();
        String client = serviceFixtures.createClientAndGetToken("it504-d21-" + UUID.randomUUID() + "@beautica.test");
        assertThat(suggestions("Ітсістмасаж")).as("precondition: suggested").contains(TYPE_A_SUGGESTION);

        assertOk(patchCategory(CATEGORY, body("active", false)));

        assertThat(suggestions("Ітсістмасаж")).as("hidden category: its type is not suggested")
                .doesNotContain(TYPE_A_SUGGESTION);
        assertThat(salonIdsByServiceType("it504-type-a")).as("services filter still finds the existing service")
                .contains(existing.salonId().toString());
        bookTenOClock(existing.masterId(), existing.assignmentId(), client);

        assertOk(patchCategory(CATEGORY, body("active", true)));

        assertThat(suggestions("Ітсістмасаж")).as("unhide restores it").contains(TYPE_A_SUGGESTION);
    }

    /** Salon ids from the public search filtered by one service-type slug ({@code GET /search/salons}). */
    private List<String> salonIdsByServiceType(String slug) throws Exception {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/search/salons?serviceTypeSlugs={slug}&page=0&size=20", String.class, slug);
        assertOk(response);
        return json(response).path("data").findValuesAsText("salonId");
    }

    // ── D4 end to end: hide blocks only NEW use; unhide restores ──────────────

    @Test
    @DisplayName("D4 — a hidden category refuses a NEW service (400) and leaves the approved picker and search "
            + "suggestions, while the existing service stays listed and bookable; unhide restores both")
    void should_blockOnlyNewUse_when_categoryHiddenThenUnhidden() throws Exception {
        SalonService existing = seedSalonServiceOnOwnCategory();
        Owner owner = ownerWithSalon();
        String client = serviceFixtures.createClientAndGetToken("it504-d4c-" + UUID.randomUUID() + "@beautica.test");
        assertThat(approvedCategoryNames(client)).as("precondition: offered").contains(CATEGORY);
        assertThat(suggestions("Ітпятсотчотири")).as("precondition: suggested").contains(CATEGORY_SUGGESTION);

        assertOk(patchCategory(CATEGORY, body("active", false)));

        assertBadRequest(createSalonService(owner, CATEGORY, typeB));
        assertThat(approvedCategoryNames(client)).doesNotContain(CATEGORY);
        assertThat(suggestions("Ітпятсотчотири")).doesNotContain(CATEGORY_SUGGESTION);
        assertThat(salonCatalogueServiceIds(existing.salonId())).as("salon catalogue")
                .contains(existing.defId().toString());
        assertThat(masterServiceLabels(existing.masterId())).as("master profile list")
                .containsKey(existing.defId().toString());
        bookTenOClock(existing.masterId(), existing.assignmentId(), client);

        assertOk(patchCategory(CATEGORY, body("active", true)));

        assertThat(approvedCategoryNames(client)).contains(CATEGORY);
        assertThat(suggestions("Ітпятсотчотири")).contains(CATEGORY_SUGGESTION);
        ResponseEntity<String> createdAfterUnhide = createSalonService(owner, CATEGORY, typeB);
        assertThat(createdAfterUnhide.getStatusCode()).as(createdAfterUnhide.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("D4 — a hidden type refuses a NEW service (400) and leaves the search suggestions, while the "
            + "existing service stays listed and bookable; unhide restores both")
    void should_blockOnlyNewUse_when_typeHiddenThenUnhidden() throws Exception {
        SalonService existing = seedSalonServiceOnOwnCategory();
        Owner owner = ownerWithSalon();
        String client = serviceFixtures.createClientAndGetToken("it504-d4t-" + UUID.randomUUID() + "@beautica.test");
        assertThat(suggestions("Ітсістмасаж")).as("precondition: suggested").contains(TYPE_A_SUGGESTION);

        assertOk(patchType(typeA, body("active", false)));

        assertBadRequest(createSalonService(owner, CATEGORY, typeA));
        assertThat(suggestions("Ітсістмасаж")).doesNotContain(TYPE_A_SUGGESTION);
        assertThat(salonCatalogueServiceIds(existing.salonId())).as("salon catalogue")
                .contains(existing.defId().toString());
        assertThat(masterServiceLabels(existing.masterId())).as("master profile list")
                .containsKey(existing.defId().toString());
        bookTenOClock(existing.masterId(), existing.assignmentId(), client);

        assertOk(patchType(typeA, body("active", true)));

        assertThat(suggestions("Ітсістмасаж")).contains(TYPE_A_SUGGESTION);
        ResponseEntity<String> createdAfterUnhide = createSalonService(owner, CATEGORY, typeA);
        assertThat(createdAfterUnhide.getStatusCode()).as(createdAfterUnhide.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    // ── D20: PATCH re-send rule ────────────────────────────────────────────────

    @Test
    @DisplayName("D20 — PATCH /services/{id} re-sending the service's OWN hidden category and type is 200")
    void should_acceptPatch_when_serviceResendsItsOwnHiddenCategoryAndType() throws Exception {
        Owner owner = ownerWithSalon();
        UUID defId = insertSalonDefinition(owner.salonId(), CATEGORY, typeA);
        assertOk(patchType(typeA, body("active", false)));
        assertOk(patchCategory(CATEGORY, body("active", false)));

        ResponseEntity<String> response = patchService(owner, defId,
                body("name", "Іт504 оновлена", "category", CATEGORY, "serviceTypeId", typeA.toString()));

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = json(response).path("data");
        assertThat(data.path("category").asText()).isEqualTo(CATEGORY);
        assertThat(data.path("serviceTypeId").asText()).isEqualTo(typeA.toString());
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM service_definitions WHERE id = ?", String.class, defId))
                .as("the edit itself persisted").isEqualTo("Іт504 оновлена");
    }

    @Test
    @DisplayName("D20 — PATCH /services/{id} switching to ANOTHER hidden type, or to another hidden category, is 400 "
            + "and changes nothing")
    void should_rejectPatch_when_serviceSwitchesToAnotherHiddenTypeOrCategory() throws Exception {
        Owner owner = ownerWithSalon();
        UUID defId = insertSalonDefinition(owner.salonId(), CATEGORY, typeA);
        fixture.insertCategory(CATEGORY_2, "Ітпятсотчотири інша", "APPROVED", null, Instant.parse("2020-01-03T10:00:00Z"));
        UUID typeC = insertType(CATEGORY_2, "Ітсістдепіляція рук", "", "it504-type-c");
        assertOk(patchType(typeB, body("active", false)));
        assertOk(patchCategory(CATEGORY_2, body("active", false)));

        ResponseEntity<String> otherType = patchService(owner, defId, body("serviceTypeId", typeB.toString()));
        ResponseEntity<String> otherCategory = patchService(owner, defId,
                body("category", CATEGORY_2, "serviceTypeId", typeC.toString()));

        assertBadRequest(otherType);
        assertBadRequest(otherCategory);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT category, service_type_id FROM service_definitions WHERE id = ?", defId);
        assertThat(row).containsEntry("category", CATEGORY).containsEntry("service_type_id", typeA);
        // Positive control: the same switches are accepted once their target is visible again.
        assertOk(patchType(typeB, body("active", true)));
        assertOk(patchService(owner, defId, body("serviceTypeId", typeB.toString())));
        assertOk(patchCategory(CATEGORY_2, body("active", true)));
        assertOk(patchService(owner, defId, body("category", CATEGORY_2, "serviceTypeId", typeC.toString())));
    }

    // ── D18: categoryDisplayName for a hidden category, and after a rename ─────

    @Test
    @DisplayName("D18 — a hidden category keeps its label on the slug page (ServiceSummaryDto), the master profile "
            + "(ServiceDefinitionResponse) and the client's passport timeline")
    void should_labelHiddenCategory_when_readThroughSlugPageMasterProfileAndTimeline() throws Exception {
        SalonService existing = seedSalonServiceOnOwnCategory();
        String slug = "it504-d18-" + Long.toHexString(System.nanoTime());
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", slug, existing.masterId());
        String client = serviceFixtures.createClientAndGetToken("it504-d18t-" + UUID.randomUUID() + "@beautica.test");
        String bookingId = bookTenOClock(existing.masterId(), existing.assignmentId(), client);
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", UUID.fromString(bookingId));

        assertOk(patchCategory(CATEGORY, body("active", false)));

        ResponseEntity<String> slugPage = restTemplate.getForEntity("/api/v1/book/" + slug + "/info", String.class);
        assertOk(slugPage);
        JsonNode summary = json(slugPage).path("services").get(0);
        assertThat(summary).as("slug page service, body=%s", slugPage.getBody()).isNotNull();
        assertThat(summary.path("category").asText()).as("raw slug field unchanged").isEqualTo(CATEGORY);
        assertThat(summary.path("categoryDisplayName").asText()).isEqualTo(CATEGORY_LABEL);
        assertThat(masterServiceLabels(existing.masterId()))
                .containsExactlyEntriesOf(Map.of(existing.defId().toString(), CATEGORY_LABEL));
        assertThat(timelineLabel(client, bookingId)).isEqualTo(CATEGORY_LABEL);
    }

    @Test
    @DisplayName("D18 — a rename reaches the booking detail and the passport timeline on the very next request "
            + "(platform-category-labels warmed, then evicted by the write)")
    void should_showRenamedLabelOnNextRequest_when_detailAndTimelineWereReadBeforeTheRename() throws Exception {
        SalonService existing = seedSalonServiceOnOwnCategory();
        String client = serviceFixtures.createClientAndGetToken("it504-d18r-" + UUID.randomUUID() + "@beautica.test");
        String bookingId = bookTenOClock(existing.masterId(), existing.assignmentId(), client);
        jdbcTemplate.update("UPDATE bookings SET status = 'COMPLETED' WHERE id = ?", UUID.fromString(bookingId));
        assertThat(bookingDetailLabel(client, bookingId)).as("warm: detail").isEqualTo(CATEGORY_LABEL);
        assertThat(timelineLabel(client, bookingId)).as("warm: timeline").isEqualTo(CATEGORY_LABEL);

        assertOk(patchCategory(CATEGORY, body("displayName", "Ітпятсотчотири оновлена")));

        assertThat(bookingDetailLabel(client, bookingId)).isEqualTo("Ітпятсотчотири оновлена");
        assertThat(timelineLabel(client, bookingId)).isEqualTo("Ітпятсотчотири оновлена");
    }

    // ── rename validation ──────────────────────────────────────────────────────

    @Test
    @DisplayName("a name that NFKC lengthens past 100 chars (51 × 'ﬁ' → 102) is 400 for a category and a type, "
            + "nothing changes or is audited; exactly 100 after NFKC (50 × 'ﬁ') is accepted")
    void should_return400_when_nfkcLengthensNamePast100() throws Exception {
        String ligatures = "ﬁ".repeat(51);

        ResponseEntity<String> category = patchCategory(CATEGORY, body("displayName", ligatures));
        ResponseEntity<String> type = patchType(typeA, body("nameUk", ligatures));

        assertBadRequest(category);
        assertBadRequest(type);
        assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM platform_categories WHERE name = ?",
                String.class, CATEGORY)).isEqualTo(CATEGORY_LABEL);
        assertThat(jdbcTemplate.queryForObject("SELECT name_uk FROM service_types WHERE id = ?", String.class, typeA))
                .isEqualTo(TYPE_A);
        assertThat(auditRows(CATEGORY)).isEmpty();
        assertThat(auditRows(typeA.toString())).isEmpty();
        assertOk(patchCategory(CATEGORY, body("displayName", "ﬁ".repeat(50))));
        assertOk(patchType(typeA, body("nameUk", "ﬁ".repeat(50))));
        assertThat(jdbcTemplate.queryForObject("SELECT display_name FROM platform_categories WHERE name = ?",
                String.class, CATEGORY)).as("boundary: stored in NFKC form").isEqualTo("fi".repeat(50));
        assertThat(jdbcTemplate.queryForObject("SELECT name_uk FROM service_types WHERE id = ?", String.class, typeA))
                .isEqualTo("fi".repeat(50));
    }

    @Test
    @DisplayName("unhiding a type while an ACTIVE type of the same category has its name is 409 TYPE_EXISTS; "
            + "it stays hidden and no UNHIDE is audited")
    void should_return409TypeExists_when_unhideCollidesWithActiveTypeName() throws Exception {
        assertOk(patchType(typeA, body("active", false)));
        insertType(TYPE_A, "", "it504-type-a-twin");

        ResponseEntity<String> unhide = patchType(typeA, body("active", true));

        assertConflictCode(unhide, "TYPE_EXISTS");
        assertThat(jdbcTemplate.queryForObject("SELECT is_active FROM service_types WHERE id = ?", Boolean.class, typeA))
                .isFalse();
        assertThat(auditRows(typeA.toString())).extracting(r -> r.get("action")).containsExactly("HIDE");
    }

    // ── audit keys, no-op ──────────────────────────────────────────────────────

    @Test
    @DisplayName("audit rows carry exactly {nameUk, nameEn} for a type RENAME and exactly {active} for HIDE / UNHIDE "
            + "(type and category); the category wire name survives a rename")
    void should_recordExactBeforeAfterKeys_when_renamedHiddenAndUnhidden() throws Exception {
        assertOk(patchType(typeA, body("nameUk", "Ітвосьмимасаж ніг", "nameEn", "Plimvortex footwork",
                "active", false)));
        assertOk(patchType(typeA, body("active", true)));
        assertOk(patchCategory(CATEGORY, body("displayName", "Ітпятсотчотири третя", "active", false)));
        assertOk(patchCategory(CATEGORY, body("active", true)));

        List<Map<String, Object>> typeRows = auditRows(typeA.toString());
        assertThat(typeRows).extracting(r -> r.get("action")).containsExactlyInAnyOrder("RENAME", "HIDE", "UNHIDE");
        Map<String, Object> rename = rowWithAction(typeRows, "RENAME");
        assertThat(jsonMap(rename.get("b"))).isEqualTo(Map.of("nameUk", TYPE_A, "nameEn", "Zyxquorbel footwork"));
        assertThat(jsonMap(rename.get("a"))).isEqualTo(Map.of("nameUk", "Ітвосьмимасаж ніг", "nameEn", "Plimvortex footwork"));
        assertVisibilityRow(rowWithAction(typeRows, "HIDE"), true, false);
        assertVisibilityRow(rowWithAction(typeRows, "UNHIDE"), false, true);
        List<Map<String, Object>> categoryRows = auditRows(CATEGORY);
        assertThat(categoryRows).extracting(r -> r.get("action")).containsExactlyInAnyOrder("RENAME", "HIDE", "UNHIDE");
        assertVisibilityRow(rowWithAction(categoryRows, "HIDE"), true, false);
        assertVisibilityRow(rowWithAction(categoryRows, "UNHIDE"), false, true);
        assertThat(jdbcTemplate.queryForObject("SELECT name FROM platform_categories WHERE display_name = ?",
                String.class, "Ітпятсотчотири третя")).as("wire name unchanged").isEqualTo(CATEGORY);
    }

    private void assertVisibilityRow(Map<String, Object> row, boolean before, boolean after) throws Exception {
        assertThat(row.get("actor")).isEqualTo(ACTOR);
        assertThat(jsonMap(row.get("b"))).isEqualTo(Map.of("active", before));
        assertThat(jsonMap(row.get("a"))).isEqualTo(Map.of("active", after));
    }

    @Test
    @DisplayName("a PATCH equal to the current values writes no audit row and evicts no catalog cache; "
            + "a changing PATCH then does evict")
    void should_evictNothing_when_patchEqualsCurrentValues() throws Exception {
        Cache cache = cacheManager.getCache("service-type-search");
        assertThat(cache).isNotNull();
        String sentinel = "it504-sentinel-" + UUID.randomUUID();
        cache.put(sentinel, List.of());

        assertOk(patchCategory(CATEGORY, body("displayName", CATEGORY_LABEL, "active", true)));
        assertOk(patchType(typeA, body("nameUk", TYPE_A, "nameEn", "Zyxquorbel footwork", "active", true)));

        assertThat(cache.get(sentinel)).as("no-op PATCH evicted a catalog cache").isNotNull();
        assertThat(auditRows(CATEGORY)).isEmpty();
        assertThat(auditRows(typeA.toString())).isEmpty();
        assertOk(patchType(typeA, body("active", false)));
        assertThat(cache.get(sentinel)).as("positive control: a real change evicts").isNull();
    }
}
