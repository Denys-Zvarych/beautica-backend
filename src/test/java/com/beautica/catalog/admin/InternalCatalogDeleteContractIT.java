package com.beautica.catalog.admin;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.TokenGenerator;
import com.beautica.catalog.CatalogCacheEvictor;
import com.beautica.catalog.admin.dto.UpdateCatalogCategoryRequest;
import com.beautica.search.service.SearchSuggestionCatalogue;
import com.beautica.service.ServiceTestFixtures;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.SuggestServiceTypeRequest;
import com.beautica.service.entity.PriceType;
import com.beautica.service.service.ServiceTypeSuggestionService;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B505 (SVC-57) — the wire contract and side effects of the catalog-admin delete that
 * {@link InternalCatalogDeleteIT} (status / data map / races) and {@link CatalogDeleteSuggestionRaceIT} (share lock vs
 * delete) do not pin: the raw 409 envelope of every code (FK backstop and {@code CATEGORY_BUSY} included, end to end),
 * the D3 notification cascade, the audit row's exact content, history rows, the search-suggestion eviction and the
 * clean 409 a losing service create / duplicate suggestion gets.
 *
 * <p>Same {@code @TestPropertySource} as {@link InternalCatalogDeleteIT}, so both share one Spring context.
 *
 * <p>Own rows: categories {@value #CATEGORY} / {@value #CATEGORY_2} / {@value #REQUEST} and everything under them,
 * removed in {@link #cleanUp()} in FK order (history rows on own definitions → definitions → types (V73 RESTRICT) →
 * the fixture's suggestions and categories). Users, salons, masters, bookings, favourites, in-app notifications and
 * {@code catalog_admin_audit} go with {@code cleanDb}.
 */
@DisplayName("InternalCatalogController (IT) — delete contract: raw 409 bodies, cascade, audit, caches, races (B505)")
@TestPropertySource(properties = "app.internal-catalog-key=" + InternalCatalogDeleteIT.CATALOG_KEY)
class InternalCatalogDeleteContractIT extends AbstractIntegrationTest {

    private static final String KEY = InternalCatalogDeleteIT.CATALOG_KEY;
    private static final String BASE = "/api/v1/internal/catalog";
    private static final String ACTOR = "beautica.ua@gmail.com";
    private static final String CATEGORY = "IT505C_CAT";
    private static final String CATEGORY_2 = "IT505C_CAT_TWO";
    private static final String REQUEST = "IT505C_REQ";
    private static final String BACKSTOP_SLUG = "it505c-backstop";
    private static final Instant CREATED = Instant.parse("2020-01-01T10:00:00Z");
    private static final List<String> ERROR_ROOT_KEYS = List.of("success", "data", "message", "requestId");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private TokenGenerator tokenGenerator;
    @Autowired private CatalogCacheEvictor cacheEvictor;
    @Autowired private CatalogEntryAdminService adminService;
    @Autowired private ServiceTypeSuggestionService suggestionService;
    @Autowired private SearchSuggestionCatalogue searchSuggestionCatalogue;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private CacheManager cacheManager;

    private CatalogRequestFixture fixture;
    private ServiceTestFixtures serviceFixtures;

    @BeforeEach
    void seed() {
        fixture = new CatalogRequestFixture(jdbcTemplate, passwordEncoder, tokenGenerator);
        serviceFixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        fixture.insertCategory(CATEGORY, "Іт505к категорія", "APPROVED", null, CREATED);
        fixture.insertCategory(CATEGORY_2, "Іт505к друга категорія", "APPROVED", null, CREATED);
        cacheEvictor.evictAll();
    }

    /**
     * FK order: the backstop trigger (if a failed test left it) → bookings / favourites / assignments on own
     * definitions → own definitions → own types → fixture (suggestions, categories).
     */
    @AfterEach
    void cleanUp() {
        dropBackstopTrigger();
        String own = "(?, ?, ?)";
        String ownDefinitions = "SELECT id FROM service_definitions WHERE category IN " + own
                + " OR service_type_id IN (SELECT id FROM service_types WHERE platform_category_name IN " + own + ")";
        Object[] args = {CATEGORY, CATEGORY_2, REQUEST, CATEGORY, CATEGORY_2, REQUEST};
        jdbcTemplate.update("DELETE FROM favorites WHERE target_id IN (" + ownDefinitions + ")", args);
        jdbcTemplate.update("DELETE FROM bookings WHERE master_service_id IN (SELECT id FROM master_services "
                + "WHERE service_def_id IN (" + ownDefinitions + "))", args);
        jdbcTemplate.update("DELETE FROM master_services WHERE service_def_id IN (" + ownDefinitions + ")", args);
        jdbcTemplate.update("DELETE FROM service_definitions WHERE id IN (" + ownDefinitions + ")", args);
        jdbcTemplate.update("DELETE FROM service_types WHERE platform_category_name IN " + own,
                CATEGORY, CATEGORY_2, REQUEST);
        fixture.cleanup();
        cacheEvictor.evictAll();
    }

    // ── seeding ────────────────────────────────────────────────────────────────

    private UUID bucket() {
        return jdbcTemplate.queryForObject("SELECT id FROM service_categories ORDER BY id LIMIT 1", UUID.class);
    }

    private UUID insertType(String category, String nameUk, String slug) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO service_types (id, category_id, name_uk, name_en, slug, is_active, "
                + "platform_category_name) VALUES (?, ?, ?, '', ?, true, ?)", id, bucket(), nameUk, slug, category);
        return id;
    }

    private UUID insertDefinition(UUID ownerId, String category, UUID typeId, boolean active) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO service_definitions (id, owner_type, owner_id, name, category, service_type_id, "
                + "base_duration_minutes, price_type, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                + "VALUES (?, 'SALON', ?, 'Іт505к послуга', ?, ?, 60, 'FIXED', 500.00, 0, ?, NOW(), NOW())",
                id, ownerId, category, typeId, active);
        return id;
    }

    private boolean typeExists(UUID id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM service_types WHERE id = ?)", Boolean.class, id));
    }

    private boolean categoryExists(String name) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM platform_categories WHERE name = ?)", Boolean.class, name));
    }

    private int pendingSuggestions(String category, String name) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM service_type_suggestion WHERE category_name = ? "
                + "AND suggested_name = ? AND status = 'PENDING'", Integer.class, category, name);
    }

    /** A BEFORE DELETE trigger that, for the one row with {@value #BACKSTOP_SLUG}, adds a referencing definition. */
    private void installBackstopTrigger() {
        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION it505c_backstop() RETURNS trigger AS $$
                BEGIN
                    INSERT INTO service_definitions (id, owner_type, owner_id, name, category, service_type_id,
                        base_duration_minutes, price_type, base_price, buffer_minutes_after, is_active,
                        created_at, updated_at)
                    VALUES (gen_random_uuid(), 'SALON', gen_random_uuid(), 'Іт505к бекстоп',
                        OLD.platform_category_name, OLD.id, 60, 'FIXED', 500.00, 0, false, NOW(), NOW());
                    RETURN OLD;
                END $$ LANGUAGE plpgsql""");
        jdbcTemplate.execute("CREATE TRIGGER it505c_backstop BEFORE DELETE ON service_types FOR EACH ROW "
                + "WHEN (OLD.slug = '" + BACKSTOP_SLUG + "') EXECUTE FUNCTION it505c_backstop()");
    }

    private void dropBackstopTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS it505c_backstop ON service_types");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS it505c_backstop()");
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    private ResponseEntity<String> deleteType(UUID id) {
        return adminDelete("/service-types/" + id);
    }

    private ResponseEntity<String> deleteCategory(String name) {
        return adminDelete("/categories/" + name);
    }

    private ResponseEntity<String> adminDelete(String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", KEY);
        headers.set(AdminActor.HEADER, ACTOR);
        return restTemplate.exchange(BASE + path, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);
    }

    /** Provider {@code POST /service-types/suggest}; a fresh X-Forwarded-For keeps the per-IP bucket out of play. */
    private ResponseEntity<String> suggest(String token, String category, String name) {
        HttpHeaders headers = serviceFixtures.bearerHeaders(token);
        headers.set("X-Forwarded-For", "10.50." + (int) (Math.random() * 250) + "." + (int) (Math.random() * 250));
        return restTemplate.exchange("/api/v1/service-types/suggest", HttpMethod.POST,
                new HttpEntity<>(new SuggestServiceTypeRequest(name, category, null), headers), String.class);
    }

    private static void assertNoContent(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    /**
     * The whole raw 409 envelope: exactly {@code success, data, message, requestId} at the root, {@code data} equal to
     * {@code expectedData} key for key (no {@code null} key may appear), the fixed message.
     */
    private void assertConflictEnvelope(ResponseEntity<String> response, Map<String, Object> expectedData,
                                        String expectedMessage) throws Exception {
        String raw = response.getBody();
        assertThat(response.getStatusCode()).as("raw=%s", raw).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = objectMapper.readTree(raw);
        List<String> rootKeys = new ArrayList<>();
        body.fieldNames().forEachRemaining(rootKeys::add);
        assertThat(rootKeys).as("root keys, raw=%s", raw).containsExactlyElementsOf(ERROR_ROOT_KEYS);
        assertThat(body.path("success").isBoolean() && !body.path("success").asBoolean())
                .as("success=false, raw=%s", raw).isTrue();
        assertThat(body.path("message").asText()).as("message, raw=%s", raw).isEqualTo(expectedMessage);
        assertThat(body.path("requestId").asText()).as("requestId, raw=%s", raw).isNotBlank();
        Map<String, Object> data = objectMapper.convertValue(body.path("data"), new TypeReference<>() {});
        assertThat(data).as("data, raw=%s", raw).containsExactlyInAnyOrderEntriesOf(expectedData);
    }

    private static Map<String, Object> data(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            map.put((String) keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    /**
     * Runs {@code first} in a transaction held open until {@code second} (on this thread) has fully finished — unlike
     * {@link HeldTransactionRace}, which releases as soon as {@code second} blocks. For waits that must run into the
     * 3 s {@code lock_timeout}.
     */
    private <T> T holdingWhile(Runnable first, Supplier<T> second) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            CompletableFuture<Void> holder = CompletableFuture.runAsync(() ->
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        first.run();
                        firstDone.countDown();
                        try {
                            if (!release.await(30, TimeUnit.SECONDS)) {
                                throw new IllegalStateException("holder never released");
                            }
                        } catch (InterruptedException ex) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(ex);
                        }
                    }), executor);
            if (!firstDone.await(10, TimeUnit.SECONDS)) {
                holder.get(1, TimeUnit.SECONDS);
                throw new AssertionError("first never ran");
            }
            T result = second.get();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            return result;
        } finally {
            release.countDown();
            executor.shutdown();
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        }
    }

    // ── 1. raw 409 bodies ─────────────────────────────────────────────────────


    @Test
    @DisplayName("type in use: {success:false, data:{code:CATALOG_IN_USE, definitions:1}, fixed message, "
            + "requestId} — no category-only count keys")
    void should_returnTypeInUseEnvelope_when_typeHasDefinition() throws Exception {
        UUID type = insertType(CATEGORY, "Іт505к тип А", "it505c-type-a");
        insertDefinition(UUID.randomUUID(), CATEGORY, type, true);

        ResponseEntity<String> response = deleteType(type);

        assertConflictEnvelope(response, data("code", "CATALOG_IN_USE", "definitions", 1),
                "Catalog entry is in use; hide it instead");
    }

    @Test
    @DisplayName("category in use: data carries all three counts, zeros included "
            + "{code, serviceTypes:0, definitions:0, pendingSuggestions:2}")
    void should_returnCategoryInUseEnvelope_when_categoryHasPendingSuggestions() throws Exception {
        fixture.insertSuggestion(CATEGORY, "Іт505к перша", "PENDING", null, CREATED);
        fixture.insertSuggestion(CATEGORY, "Іт505к друга", "PENDING", null, CREATED);

        ResponseEntity<String> response = deleteCategory(CATEGORY);

        assertConflictEnvelope(response, data("code", "CATALOG_IN_USE", "serviceTypes", 0, "definitions", 0,
                "pendingSuggestions", 2), "Catalog entry is in use; hide it instead");
    }

    /**
     * Forces the backstop end to end: the counts run first (zero), then a BEFORE DELETE trigger inserts a
     * referencing definition, so the V111 RESTRICT FK fails at the in-method flush (23503).
     */
    @Test
    @DisplayName("FK backstop at the flush: {code:CATALOG_IN_USE} only — no counts, not the generic "
            + "data-integrity 409; the type is kept, nothing audited")
    void should_returnCodeOnly_when_fkBackstopFires() throws Exception {
        UUID type = insertType(CATEGORY, "Іт505к бекстоп", BACKSTOP_SLUG);
        installBackstopTrigger();

        ResponseEntity<String> response;
        try {
            response = deleteType(type);
        } finally {
            dropBackstopTrigger();
        }

        assertConflictEnvelope(response, data("code", "CATALOG_IN_USE"), "Catalog entry is in use; hide it instead");
        assertThat(typeExists(type)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM service_definitions WHERE service_type_id = ?",
                Integer.class, type)).as("trigger's insert rolled back with the delete").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM catalog_admin_audit", Integer.class)).isZero();
    }

    @Test
    @DisplayName("pending category request: {code:CATEGORY_NOT_APPROVED} only, fixed message")
    void should_returnNotApprovedEnvelope_when_deletingPendingRequest() throws Exception {
        fixture.insertCategory(REQUEST, "Іт505к запит", "PENDING", null, CREATED);

        ResponseEntity<String> response = deleteCategory(REQUEST);

        assertConflictEnvelope(response, data("code", "CATEGORY_NOT_APPROVED"), "Category is not approved");
        assertThat(categoryExists(REQUEST)).isTrue();
    }

    /** D8: a held portal rename ({@code FOR NO KEY UPDATE}) outlasts the suggestion's 3 s share-lock wait. */
    @Test
    @DisplayName("suggest while a portal rename holds the category past lock_timeout: {code:CATEGORY_BUSY} only, "
            + "retry message, nothing persisted")
    void should_returnCategoryBusyEnvelope_when_shareLockTimesOut() throws Exception {
        String token = serviceFixtures.createSalonOwnerAndGetToken("it505c-busy-owner@beautica.test");
        String name = "Іт505к зайнятий тип";

        ResponseEntity<String> response = holdingWhile(
                () -> adminService.updateCategory(AdminActor.of(ACTOR), CATEGORY,
                        new UpdateCatalogCategoryRequest("Іт505к перейменована", null)),
                () -> suggest(token, CATEGORY, name));

        assertConflictEnvelope(response, data("code", "CATEGORY_BUSY"),
                "Category is being updated; please try again");
        assertThat(pendingSuggestions(CATEGORY, name)).isZero();
    }

    // ── 2. what blocks / does not block ───────────────────────────────────────

    @Test
    @DisplayName("an APPROVED (decided) suggestion naming the category does not block: 204, the row is kept")
    void should_deleteCategory_when_onlyApprovedSuggestionNamesIt() throws Exception {
        UUID approved = fixture.insertSuggestion(CATEGORY, "Іт505к схвалена", "APPROVED", null, CREATED);

        ResponseEntity<String> response = deleteCategory(CATEGORY);

        assertNoContent(response);
        assertThat(categoryExists(CATEGORY)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM service_type_suggestion WHERE id = ?",
                String.class, approved)).isEqualTo("APPROVED");
    }

    // ── 3. after a delete: cascade, audit, history ────────────────────────────

    /** A wrongly approved request (the D3 use case): requester, initial service name, bucket, in-app notification. */
    private UUID seedApprovedRequestWithNotification(String requesterEmail) {
        UUID requester = fixture.insertUser(requesterEmail, "SALON_OWNER", "Іван", "Запитувач", null);
        long categoryId = fixture.insertCategory(REQUEST, "Іт505к схвалений запит", "APPROVED", requester, CREATED);
        jdbcTemplate.update("UPDATE platform_categories SET initial_service_name = ?, service_category_id = ? "
                + "WHERE name = ?", "Іт505к стартова послуга", bucket(), REQUEST);
        jdbcTemplate.update("INSERT INTO in_app_notification (id, recipient_user_id, type, category_request_id, "
                + "dedup_key) VALUES (?, ?, 'CATEGORY_REQUEST_APPROVED', ?, ?)",
                UUID.randomUUID(), requester, categoryId, "CATEGORY_REQUEST_APPROVED:" + categoryId);
        return requester;
    }

    @Test
    @DisplayName("D3: deleting a wrongly approved request's category is 204 and cascades the requester's in-app "
            + "decision notification; the requester's other data is untouched")
    void should_cascadeRequesterNotification_when_approvedRequestCategoryDeleted() throws Exception {
        UUID requester = seedApprovedRequestWithNotification("it505c-requester@beautica.test");

        ResponseEntity<String> response = deleteCategory(REQUEST);

        assertNoContent(response);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM in_app_notification WHERE recipient_user_id = ?",
                Integer.class, requester)).as("notification gone with its category (V196 CASCADE)").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, requester))
                .as("requester account kept").isEqualTo(1);
    }

    @Test
    @DisplayName("the DELETE audit row: exact before keys and values (bucket + initial name), after null, no "
            + "requester id / e-mail / name anywhere in the row")
    void should_auditCatalogFieldsOnly_when_approvedRequestCategoryDeleted() throws Exception {
        String email = "it505c-audit-requester@beautica.test";
        UUID requester = seedApprovedRequestWithNotification(email);

        ResponseEntity<String> response = deleteCategory(REQUEST);

        assertNoContent(response);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT actor, action, target_type, "
                + "before::text AS b, after::text AS a, row_to_json(c)::text AS full_row FROM catalog_admin_audit c "
                + "WHERE target_id = ?", REQUEST);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row).containsEntry("actor", ACTOR).containsEntry("action", "DELETE")
                .containsEntry("target_type", "CATEGORY");
        assertThat(row.get("a")).as("after").isNull();
        Map<String, Object> before = objectMapper.readValue((String) row.get("b"), new TypeReference<>() {});
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("name", REQUEST);
        expected.put("displayName", "Іт505к схвалений запит");
        expected.put("status", "APPROVED");
        expected.put("active", true);
        expected.put("initialServiceName", "Іт505к стартова послуга");
        expected.put("serviceCategoryId", bucket().toString());
        assertThat(before).containsExactlyInAnyOrderEntriesOf(expected);
        assertThat((String) row.get("full_row")).as("no requester data in the audit row")
                .doesNotContain(requester.toString()).doesNotContain(email).doesNotContain("Запитувач");
    }

    @Test
    @DisplayName("a type behind a past COMPLETED booking and a favourite: 409 {definitions:1}; booking, favourite "
            + "and definition untouched")
    void should_return409AndKeepHistory_when_typeHasPastBookingAndFavourite() throws Exception {
        String ownerToken = serviceFixtures.createSalonOwnerAndGetToken("it505c-history-owner@beautica.test");
        UUID salonId = serviceFixtures.createSalon(ownerToken, "Іт505к салон");
        UUID masterId = serviceFixtures.createSalonMaster(salonId);
        UUID clientId = fixture.insertUser("it505c-history-client@beautica.test", "CLIENT", "Олена", "Клієнтка", null);
        UUID type = insertType(CATEGORY, "Іт505к тип з історією", "it505c-type-history");
        UUID definition = insertDefinition(salonId, CATEGORY, type, false);
        UUID assignment = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, "
                + "updated_at) VALUES (?, ?, ?, false, NOW(), NOW())", assignment, masterId, definition);
        UUID booking = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                + "booking_source, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'COMPLETED', "
                + "NOW() - interval '30 days', NOW() - interval '30 days' + interval '1 hour', 500.00, 60, 0, 'APP', "
                + "NOW(), NOW())", booking, clientId, masterId, assignment, salonId);
        jdbcTemplate.update("INSERT INTO favorites (id, client_id, target_type, target_id, created_at) "
                + "VALUES (?, ?, 'SALON_SERVICE', ?, NOW())", UUID.randomUUID(), clientId, definition);

        ResponseEntity<String> response = deleteType(type);

        assertConflictEnvelope(response, data("code", "CATALOG_IN_USE", "definitions", 1),
                "Catalog entry is in use; hide it instead");
        assertThat(typeExists(type)).isTrue();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, booking))
                .isEqualTo("COMPLETED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM favorites WHERE target_id = ?", Integer.class,
                definition)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT service_type_id FROM service_definitions WHERE id = ?",
                UUID.class, definition)).isEqualTo(type);
    }

    // ── 4. caches ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("a deleted category drops out of the (warmed) search-suggestion catalogue on the next read")
    void should_dropFromSearchSuggestions_when_categoryDeleted() throws Exception {
        assertThat(String.valueOf(searchSuggestionCatalogue.snapshot()))
                .as("warmed snapshot lists the category").contains(CATEGORY_2);

        ResponseEntity<String> response = deleteCategory(CATEGORY_2);

        assertNoContent(response);
        assertThat(String.valueOf(searchSuggestionCatalogue.snapshot()))
                .as("searchSuggestionCatalogue + platform-category-order evicted after commit")
                .doesNotContain(CATEGORY_2);
    }

    @Test
    @DisplayName("a category delete leaves the per-master masterServices / booking-slug-info caches warm (D7)")
    void should_keepLabelCachesWarm_when_categoryDeleted() throws Exception {
        Cache masterServices = cacheManager.getCache("masterServices");
        Cache slugInfo = cacheManager.getCache("booking-slug-info");
        masterServices.put("it505c-sentinel", "warm");
        slugInfo.put("it505c-sentinel", "warm");

        ResponseEntity<String> response = deleteCategory(CATEGORY_2);

        assertNoContent(response);
        assertThat(masterServices.get("it505c-sentinel")).as("masterServices warm").isNotNull();
        assertThat(slugInfo.get("it505c-sentinel")).as("booking-slug-info warm").isNotNull();
        masterServices.evict("it505c-sentinel");
        slugInfo.evict("it505c-sentinel");
    }

    // ── 5. a service create that loses to a type delete ───────────────────────

    @Test
    @DisplayName("race: POST /salons/{id}/services on a type whose delete holds the row lock → after the delete "
            + "commits, a clean generic 409 (23503), never a 500; no service on the deleted type")
    void should_return409_when_serviceCreateLosesToTypeDelete() throws Exception {
        String ownerToken = serviceFixtures.createSalonOwnerAndGetToken("it505c-create-owner@beautica.test");
        UUID salonId = serviceFixtures.createSalon(ownerToken, "Іт505к салон гонки");
        UUID type = insertType(CATEGORY, "Іт505к тип гонки", "it505c-type-race");
        HeldTransactionRace race = new HeldTransactionRace(transactionManager, jdbcTemplate);
        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest("Іт505к нова послуга", null,
                CATEGORY, 60, 0, PriceType.FIXED, new BigDecimal("500.00"), null, null, type);

        ResponseEntity<String> response = race.run(
                () -> adminService.deleteServiceType(AdminActor.of(ACTOR), type),
                () -> restTemplate.exchange("/api/v1/salons/" + salonId + "/services", HttpMethod.POST,
                        new HttpEntity<>(request, serviceFixtures.bearerHeaders(ownerToken)), String.class));

        String raw = response.getBody();
        assertThat(response.getStatusCode()).as("raw=%s", raw).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = objectMapper.readTree(raw);
        assertThat(body.path("success").asBoolean(true)).isFalse();
        assertThat(body.path("data").isNull()).as("generic body, raw=%s", raw).isTrue();
        assertThat(body.path("message").asText()).isEqualTo("Request conflicts with existing data");
        assertThat(typeExists(type)).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM service_definitions WHERE service_type_id = ?",
                Integer.class, type)).isZero();
    }

    // ── 6. identical suggestions at the same moment ───────────────────────────

    /**
     * Perf INFO: both take the shared {@code FOR SHARE}, neither sees the other's uncommitted row, so the loser waits on
     * the V197 unique index. Released at once it hits the unique violation: the typed {@code DUPLICATE_PENDING} with the
     * pre-existing generic message byte-identical. Pins the exact envelope and exactly one row.
     */
    @Test
    @DisplayName("race: an identical suggestion submitted while the first is uncommitted → 409 {code:DUPLICATE_PENDING}, "
            + "generic message unchanged; exactly one PENDING row")
    void should_return409_when_identicalSuggestionRacesUncommittedOne() throws Exception {
        String token = serviceFixtures.createSalonOwnerAndGetToken("it505c-dup-owner@beautica.test");
        String name = "Іт505к двійник";
        HeldTransactionRace race = new HeldTransactionRace(transactionManager, jdbcTemplate);

        ResponseEntity<String> response = race.run(
                () -> suggestionService.submitSuggestion(CATEGORY, name, null, null),
                () -> suggest(token, CATEGORY, name));

        assertConflictEnvelope(response, data("code", "DUPLICATE_PENDING"),
                "Request could not be completed due to a conflict");
        assertThat(pendingSuggestions(CATEGORY, name)).isEqualTo(1);
    }

    /**
     * Same race, but the first stays uncommitted past the loser's transaction-local 3 s {@code lock_timeout} (set by
     * the B505 share-lock finder): the unique-index wait times out (55P03) → the typed, retry-able {@code CATEGORY_BUSY}.
     * Pins the exact envelope and one row.
     */
    @Test
    @DisplayName("race: an identical suggestion whose unique-index wait outlasts lock_timeout → 409 "
            + "{code:CATEGORY_BUSY}; exactly one PENDING row")
    void should_return409_when_identicalSuggestionWaitsPastLockTimeout() throws Exception {
        String token = serviceFixtures.createSalonOwnerAndGetToken("it505c-dup-timeout-owner@beautica.test");
        String name = "Іт505к двійник довгий";

        ResponseEntity<String> response = holdingWhile(
                () -> suggestionService.submitSuggestion(CATEGORY, name, null, null),
                () -> suggest(token, CATEGORY, name));

        assertConflictEnvelope(response, data("code", "CATEGORY_BUSY"),
                "Category is being updated; please try again");
        assertThat(pendingSuggestions(CATEGORY, name)).isEqualTo(1);
    }
}
