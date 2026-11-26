package com.beautica.catalog.admin;

import com.beautica.AbstractIntegrationTest;
import java.util.Iterator;
import java.util.EnumSet;
import java.nio.charset.CharacterCodingException;
import java.nio.ByteBuffer;
import org.springframework.core.io.ClassPathResource;
import com.beautica.common.exception.CategoryIconInvalidException;
import com.beautica.catalog.CatalogCacheEvictor;
import com.beautica.catalog.icon.CategoryIconReferenceGuard;
import com.beautica.catalog.icon.CategoryIconSanitizer;
import com.beautica.catalog.icon.CategoryIconService;
import com.beautica.catalog.icon.CategoryIconUrls;
import com.beautica.media.service.AfterCommitBlobPurger;
import com.beautica.media.service.R2StorageService;
import com.beautica.service.ServiceTestFixtures;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B539 (SVC-58 / SVC-59) — {@code PUT /api/v1/internal/catalog/categories/{name}/icon} through the REAL chain (Tomcat,
 * Security, {@code InternalApiKeyFilter}, MVC) on Postgres, read back through the app's picker
 * ({@code GET /service-categories/approved}) and the portal reads ({@code GET /internal/catalog/categories},
 * {@code GET /internal/catalog/requests}). R2 is a {@code @MockBean} (the media-IT fake): its invocations are the
 * ledger of uploads / deletes, and the test profile runs {@code blobPurgeExecutor} synchronously.
 *
 * <p>Own rows: {@value #CATEGORY} (APPROVED), {@value #PENDING_CATEGORY} and {@value #REJECTED_CATEGORY} (requests),
 * removed by the fixture; {@code catalog_admin_audit} is truncated by {@code cleanDb}.
 */
@DisplayName("InternalCategoryIconController (IT) — icon upload, iconUrl on every category read")
@TestPropertySource(properties = "app.internal-catalog-key=" + InternalCategoryIconIT.CATALOG_KEY)
class InternalCategoryIconIT extends AbstractIntegrationTest {

    static final String CATALOG_KEY = "test-internal-catalog-key-iiiiiiiiiiiiiiii";
    private static final String MASTER_KEY = "test-internal-api-key";
    private static final String BASE = "/api/v1/internal/catalog";
    private static final String ACTOR = "beautica.ua@gmail.com";
    private static final String CDN = "https://cdn.example/";
    private static final String CATEGORY = "IT539_CAT";
    private static final String PENDING_CATEGORY = "IT539_PENDING";
    private static final String REJECTED_CATEGORY = "IT539_REJECTED";
    private static final String SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path d=\"M0 0L24 24\"/></svg>";
    private static final String SVG_2 =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><circle cx=\"12\" cy=\"12\" r=\"9\"/></svg>";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CatalogCacheEvictor cacheEvictor;
    @Autowired private CacheManager cacheManager;
    @Autowired private PlatformCategoryRepository platformCategoryRepository;
    @Autowired private CatalogAdminAuditService auditService;
    @Autowired private CategoryIconUrls iconUrls;
    @Autowired private CategoryIconReferenceGuard referenceGuard;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private Clock clock;

    @MockBean private R2StorageService r2;

    private CatalogRequestFixture fixture;
    private ServiceTestFixtures serviceFixtures;

    @BeforeEach
    void seed() {
        reset(r2);
        when(r2.isEnabled()).thenReturn(true);
        when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> CDN + inv.getArgument(0));
        when(r2.deleteFiles(any())).thenReturn(Set.of());
        fixture = new CatalogRequestFixture(jdbcTemplate, passwordEncoder);
        serviceFixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        fixture.insertCategory(CATEGORY, "Іт539 категорія", "APPROVED", null, Instant.parse("2020-01-01T10:00:00Z"));
        cacheEvictor.evictAll();
    }

    @AfterEach
    void cleanUp() {
        fixture.cleanup();
        cacheEvictor.evictAll();
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    private ResponseEntity<String> put(String name, String key, String actor, String svg) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.set("X-Internal-Key", key);
        }
        if (actor != null) {
            headers.set(AdminActor.HEADER, actor);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = objectMapper.writeValueAsString(Map.of("svg", svg));
        return restTemplate.exchange(BASE + "/categories/" + name + "/icon", HttpMethod.PUT,
                new HttpEntity<>(json, headers), String.class);
    }

    private JsonNode getInternal(String path) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", CATALOG_KEY);
        ResponseEntity<String> response = restTemplate.exchange(BASE + path, HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private JsonNode approvedEntry(String token, String name) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/service-categories/approved",
                HttpMethod.GET, new HttpEntity<>(serviceFixtures.bearerHeaders(token)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        for (JsonNode node : objectMapper.readTree(response.getBody()).path("data")) {
            if (name.equals(node.path("name").asText())) {
                return node;
            }
        }
        throw new AssertionError("category not in the approved list: " + name);
    }

    private static JsonNode byName(JsonNode array, String field, String value) {
        for (JsonNode node : array) {
            if (value.equals(node.path(field).asText())) {
                return node;
            }
        }
        throw new AssertionError(value + " not found");
    }

    private Map<String, Object> iconRow(String name) {
        return jdbcTemplate.queryForMap(
                "SELECT icon_key, icon_sha256, icon_updated_at FROM platform_categories WHERE name = ?", name);
    }

    private int iconSetAuditRows(String name) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog_admin_audit WHERE action = 'ICON_SET' "
                + "AND target_type = 'CATEGORY' AND target_id = ?", Integer.class, name);
        return count == null ? 0 : count;
    }

    private static String expectedKey(String name, String svg) {
        String sha = CategoryIconSanitizer.sanitize(svg.getBytes(StandardCharsets.UTF_8)).sha256();
        return "catalog/category-icons/" + name + "/" + sha.substring(0, 16) + ".svg";
    }

    // ── cases ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PUT with the catalog key: 200 {name, iconUrl}, row has key + hash, one ICON_SET row, the warmed "
            + "approved list shows the new iconUrl on the next call")
    void should_storeAuditAndExposeIconUrl_when_iconPut() throws Exception {
        String token = serviceFixtures.createClientAndGetToken("it539-client-" + UUID.randomUUID() + "@beautica.test");
        assertThat(approvedEntry(token, CATEGORY).path("iconUrl").isNull()).isTrue(); // warms approved-categories
        String key = expectedKey(CATEGORY, SVG);

        ResponseEntity<String> response = put(CATEGORY, CATALOG_KEY, ACTOR, SVG);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("name").asText()).isEqualTo(CATEGORY);
        assertThat(data.path("iconUrl").asText()).isEqualTo(CDN + key);
        Map<String, Object> row = iconRow(CATEGORY);
        assertThat(row.get("icon_key")).isEqualTo(key);
        assertThat((String) row.get("icon_sha256")).matches("^[0-9a-f]{64}$");
        assertThat(row.get("icon_updated_at")).isNotNull();
        assertThat(iconSetAuditRows(CATEGORY)).isEqualTo(1);
        verify(r2).uploadFile(eq(key), any(), anyLong(), eq("image/svg+xml"), eq("public, max-age=31536000, immutable"));
        assertThat(approvedEntry(token, CATEGORY).path("iconUrl").asText()).isEqualTo(CDN + key);
    }

    @Test
    @DisplayName("W050: anonymous GET /service-categories/approved is 200 with iconUrl (real SecurityConfig); "
            + "anonymous POST /service-categories/requests is still 401")
    void should_serveApprovedAnonymously_when_noToken() throws Exception {
        put(CATEGORY, CATALOG_KEY, ACTOR, SVG);

        ResponseEntity<String> anonymous = restTemplate.getForEntity("/api/v1/service-categories/approved",
                String.class);
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> anonymousPost = restTemplate.exchange("/api/v1/service-categories/requests",
                HttpMethod.POST, new HttpEntity<>("{\"displayName\":\"Іт\"}", json), String.class);
        ResponseEntity<String> anonymousPut = restTemplate.exchange("/api/v1/service-categories/approved",
                HttpMethod.PUT, new HttpEntity<>("{}", json), String.class);

        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode entry = byName(objectMapper.readTree(anonymous.getBody()).path("data"), "name", CATEGORY);
        assertThat(entry.path("iconUrl").asText()).isEqualTo(CDN + expectedKey(CATEGORY, SVG));
        assertThat(entry.has("id")).isFalse();
        assertThat(anonymousPost.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymousPut.getStatusCode().value()).isIn(401, 403);
    }

    @Test
    @DisplayName("re-PUT of the same icon: 200 no-op, still one audit row, one upload")
    void should_beNoOp_when_sameIconPutTwice() throws Exception {
        put(CATEGORY, CATALOG_KEY, ACTOR, SVG);

        ResponseEntity<String> again = put(CATEGORY, CATALOG_KEY, ACTOR, "<!-- same -->" + SVG);

        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(iconSetAuditRows(CATEGORY)).isEqualTo(1);
        verify(r2, times(1)).uploadFile(anyString(), any(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("replace: the previous object is deleted after commit, the new one is not")
    void should_purgePreviousObject_when_iconReplaced() throws Exception {
        put(CATEGORY, CATALOG_KEY, ACTOR, SVG);
        String oldKey = expectedKey(CATEGORY, SVG);

        ResponseEntity<String> response = put(CATEGORY, CATALOG_KEY, ACTOR, SVG_2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(iconRow(CATEGORY).get("icon_key")).isEqualTo(expectedKey(CATEGORY, SVG_2));
        verify(r2).deleteFiles(List.of(oldKey));
        verify(r2, never()).deleteFiles(List.of(expectedKey(CATEGORY, SVG_2)));
        assertThat(iconSetAuditRows(CATEGORY)).isEqualTo(2);
    }

    @Test
    @DisplayName("the master internal key is accepted too (SVC-54)")
    void should_acceptMasterKey_when_iconPut() throws Exception {
        ResponseEntity<String> response = put(CATEGORY, MASTER_KEY, ACTOR, SVG);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("wrong or missing key: refused (401/403), nothing written")
    void should_refuse_when_keyWrongOrMissing() throws Exception {
        ResponseEntity<String> wrong = put(CATEGORY, "not-the-key", ACTOR, SVG);
        ResponseEntity<String> missing = put(CATEGORY, null, ACTOR, SVG);

        assertThat(wrong.getStatusCode().value()).isIn(401, 403);
        assertThat(missing.getStatusCode().value()).isIn(401, 403);
        assertThat(iconRow(CATEGORY).get("icon_key")).isNull();
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("script in the SVG: 400 {code: ICON_INVALID, reason: FORBIDDEN_ELEMENT}, nothing written")
    void should_return400IconInvalidWithReason_when_svgHasScript() throws Exception {
        ResponseEntity<String> response =
                put(CATEGORY, CATALOG_KEY, ACTOR, SVG.replace("<path", "<script>alert(1)</script><path"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("data").path("code").asText()).isEqualTo("ICON_INVALID");
        assertThat(body.path("data").path("reason").asText()).isEqualTo("FORBIDDEN_ELEMENT");
        assertThat(body.path("message").asText()).doesNotContain("script");
        assertThat(iconRow(CATEGORY).get("icon_key")).isNull();
        assertThat(iconSetAuditRows(CATEGORY)).isZero();
    }

    @Test
    @DisplayName("16 KiB + 1 of SVG: 400 ICON_INVALID / TOO_LARGE")
    void should_return400TooLarge_when_svgOverSixteenKibibytes() throws Exception {
        String padding = "<!--" + "x".repeat(16 * 1024) + "-->";

        ResponseEntity<String> response = put(CATEGORY, CATALOG_KEY, ACTOR, SVG + padding);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(objectMapper.readTree(response.getBody()).path("data").path("reason").asText())
                .isEqualTo("TOO_LARGE");
    }

    @Test
    @DisplayName("missing X-Admin-Actor: 400; unknown category: 404")
    void should_return400Or404_when_actorMissingOrCategoryUnknown() throws Exception {
        ResponseEntity<String> noActor = put(CATEGORY, CATALOG_KEY, null, SVG);
        ResponseEntity<String> unknown = put("IT539_NOPE", CATALOG_KEY, ACTOR, SVG);

        assertThat(noActor.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("REJECTED request: 409 CATEGORY_REJECTED")
    void should_return409CategoryRejected_when_categoryRejected() throws Exception {
        fixture.insertCategory(REJECTED_CATEGORY, "Іт539 відхилена", "REJECTED", null, Instant.parse("2020-01-02T10:00:00Z"));

        ResponseEntity<String> response = put(REJECTED_CATEGORY, CATALOG_KEY, ACTOR, SVG);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(response.getBody()).path("data").path("code").asText())
                .isEqualTo("CATEGORY_REJECTED");
    }

    @Test
    @DisplayName("iconUrl on the portal reads: the category list, and a PENDING category request in the queue")
    void should_exposeIconUrlOnPortalReads_when_iconsSet() throws Exception {
        fixture.insertCategory(PENDING_CATEGORY, "Іт539 запит", "PENDING", null, Instant.parse("2020-01-03T10:00:00Z"));
        put(CATEGORY, CATALOG_KEY, ACTOR, SVG);
        put(PENDING_CATEGORY, CATALOG_KEY, ACTOR, SVG_2);

        JsonNode categories = getInternal("/categories");
        JsonNode requests = getInternal("/requests?status=PENDING&size=100").path("data");

        assertThat(byName(categories, "name", CATEGORY).path("iconUrl").asText())
                .isEqualTo(CDN + expectedKey(CATEGORY, SVG));
        assertThat(byName(categories, "name", PENDING_CATEGORY).path("iconUrl").asText())
                .isEqualTo(CDN + expectedKey(PENDING_CATEGORY, SVG_2));
        assertThat(byName(requests, "name", PENDING_CATEGORY).path("iconUrl").asText())
                .isEqualTo(CDN + expectedKey(PENDING_CATEGORY, SVG_2));
    }

    // ── content-addressed purge: reference re-check at purge time (B539 audit) ───────────────────────────────────

    /** Purge tasks queued here run only when the test drains them — a purge executor delayed past later writes. */
    private final List<Runnable> delayedPurges = new ArrayList<>();
    private static final AdminActor OPERATOR = new AdminActor(ACTOR);

    private CategoryIconService serviceWithDelayedPurges() {
        AfterCommitBlobPurger delayed = new AfterCommitBlobPurger(r2, delayedPurges::add, new SimpleMeterRegistry(),
                List.of(referenceGuard));
        return new CategoryIconService(platformCategoryRepository, r2, delayed, auditService, cacheEvictor, iconUrls,
                clock, transactionTemplate, referenceGuard);
    }

    private void runDelayedPurges() {
        List<Runnable> due = List.copyOf(delayedPurges);
        delayedPurges.clear();
        due.forEach(Runnable::run);
    }

    @Test
    @DisplayName("A→B→A with the purges delayed until after the last commit: A (live again) is NOT deleted, B is")
    void should_keepLiveObject_when_purgeOfReusedKeyRunsLate() {
        CategoryIconService service = serviceWithDelayedPurges();
        String keyA = expectedKey(CATEGORY, SVG);
        String keyB = expectedKey(CATEGORY, SVG_2);

        service.setIcon(CATEGORY, SVG, OPERATOR);
        service.setIcon(CATEGORY, SVG_2, OPERATOR);
        service.setIcon(CATEGORY, SVG, OPERATOR);
        runDelayedPurges();

        assertThat(iconRow(CATEGORY).get("icon_key")).isEqualTo(keyA);
        verify(r2, never()).deleteFiles(argThat(keys -> keys.contains(keyA)));
        // B is unreferenced: its upload release and its replaced-object purge both delete it (idempotent).
        verify(r2, atLeastOnce()).deleteFiles(List.of(keyB));
    }

    @Test
    @DisplayName("rollback race: an attempt uploads A and rolls back while another commits A; the loser's late purge "
            + "of A finds the committed reference and keeps the object")
    void should_keepObject_when_rolledBackUploadPurgeLandsAfterWinnerCommit() {
        CategoryIconService service = serviceWithDelayedPurges();
        String keyA = expectedKey(CATEGORY, SVG);
        String keyB = expectedKey(CATEGORY, SVG_2);
        service.setIcon(CATEGORY, SVG_2, OPERATOR);
        runDelayedPurges();

        transactionTemplate.executeWithoutResult(status -> {
            service.setIcon(CATEGORY, SVG, OPERATOR);
            status.setRollbackOnly();
        });
        assertThat(iconRow(CATEGORY).get("icon_key")).isEqualTo(keyB);
        service.setIcon(CATEGORY, SVG, OPERATOR);
        runDelayedPurges();

        assertThat(iconRow(CATEGORY).get("icon_key")).isEqualTo(keyA);
        verify(r2, never()).deleteFiles(argThat(keys -> keys.contains(keyA)));
        verify(r2).deleteFiles(List.of(keyB));
    }

    @Test
    @DisplayName("pending-upload race: A→B queues a purge of A; a new write of A has PUT the object but not yet "
            + "committed when that purge runs — the in-flight claim keeps A, so the committed row never points at a "
            + "deleted object")
    void should_keepObject_when_purgeOfKeyRunsBetweenItsUploadAndCommit() {
        CategoryIconService service = serviceWithDelayedPurges();
        String keyA = expectedKey(CATEGORY, SVG);
        service.setIcon(CATEGORY, SVG, OPERATOR);
        service.setIcon(CATEGORY, SVG_2, OPERATOR);
        doAnswer(inv -> {
            runDelayedPurges();
            return null;
        }).when(r2).uploadFile(eq(keyA), any(), anyLong(), anyString(), anyString());

        service.setIcon(CATEGORY, SVG, OPERATOR);
        runDelayedPurges();

        assertThat(iconRow(CATEGORY).get("icon_key")).isEqualTo(keyA);
        verify(r2, never()).deleteFiles(argThat(keys -> keys.contains(keyA)));
    }

    @Test
    @DisplayName("an icon upload empties the platform-catalog caches only: per-master masterServices / "
            + "booking-slug-info entries survive (they hold no iconUrl)")
    void should_keepPerMasterCaches_when_iconUploaded() throws Exception {
        Cache masterServices = cacheManager.getCache("masterServices");
        Cache slugInfo = cacheManager.getCache("booking-slug-info");
        Cache approved = cacheManager.getCache("approved-categories");
        assertThat(masterServices).isNotNull();
        assertThat(slugInfo).isNotNull();
        assertThat(approved).isNotNull();
        masterServices.put("it539-probe", "warm");
        slugInfo.put("it539-probe", "warm");
        approved.put("it539-probe", "warm");

        ResponseEntity<String> response = put(CATEGORY, CATALOG_KEY, ACTOR, SVG);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(masterServices.get("it539-probe")).isNotNull();
        assertThat(slugInfo.get("it539-probe")).isNotNull();
        assertThat(approved.get("it539-probe")).isNull();
    }

    // ── B539 / B541 QA additions ─────────────────────────────────────────────────

    /** The fixed 400 message (GlobalExceptionHandler); the portal maps {@code data.reason}, never this text. */
    private static final String ICON_INVALID_MESSAGE = "Invalid category icon";

    private ResponseEntity<String> internalJson(HttpMethod method, String url, String key, Object body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", key);
        headers.set(AdminActor.HEADER, ACTOR);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(url, method, new HttpEntity<>(objectMapper.writeValueAsString(body), headers),
                String.class);
    }

    @Test
    @DisplayName("W050 real chain: anonymous GET /approved carries Cache-Control public, max-age=0, s-maxage=300 "
            + "(not Spring Security's no-cache); anonymous POST / PATCH / DELETE on /approved stay 401/403")
    void should_permitOnlyAnonymousGet_when_approvedPathCalledWithOtherMethods() throws Exception {
        HttpHeaders json = new HttpHeaders();
        json.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> get = restTemplate.getForEntity("/api/v1/service-categories/approved", String.class);
        ResponseEntity<String> post = restTemplate.exchange("/api/v1/service-categories/approved", HttpMethod.POST,
                new HttpEntity<>("{}", json), String.class);
        ResponseEntity<String> patch = restTemplate.exchange("/api/v1/service-categories/approved", HttpMethod.PATCH,
                new HttpEntity<>("{}", json), String.class);
        ResponseEntity<String> delete = restTemplate.exchange("/api/v1/service-categories/approved",
                HttpMethod.DELETE, new HttpEntity<>(json), String.class);

        assertThat(get.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(get.getHeaders().getCacheControl()).isEqualTo("max-age=0, public, s-maxage=300");
        assertThat(get.getHeaders().getPragma()).as("no Spring Security no-cache Pragma").isNull();
        assertThat(post.getStatusCode().value()).as("anonymous POST /approved").isIn(401, 403);
        assertThat(patch.getStatusCode().value()).as("anonymous PATCH /approved").isIn(401, 403);
        assertThat(delete.getStatusCode().value()).as("anonymous DELETE /approved").isIn(401, 403);
    }

    @Test
    @DisplayName("SVC-59 over HTTP: every text reject fixture of the shared table (icon-fixtures/expected.json) is "
            + "400 {success:false, code: ICON_INVALID, reason: <table verdict>} with the fixed message, nothing "
            + "written; together they cover every reason but TOO_LARGE (own test)")
    void should_return400WithTableReason_when_eachSharedRejectFixturePut() throws Exception {
        JsonNode table = objectMapper.readTree(new ClassPathResource("catalog/icon-fixtures/expected.json")
                .getInputStream());
        EnumSet<CategoryIconInvalidException.Reason> seen = EnumSet.noneOf(CategoryIconInvalidException.Reason.class);
        int sent = 0;

        for (Iterator<Map.Entry<String, JsonNode>> it = table.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> row = it.next();
            if (!row.getKey().startsWith("reject/")) {
                continue;
            }
            byte[] bytes = new ClassPathResource("catalog/icon-fixtures/" + row.getKey()).getInputStream()
                    .readAllBytes();
            String text;
            try {
                // The PUT body is JSON text: a byte-level fixture (latin-1, PNG) cannot travel over it unchanged.
                text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            } catch (CharacterCodingException notUtf8) {
                continue;
            }
            CategoryIconInvalidException.Reason reason =
                    CategoryIconInvalidException.Reason.valueOf(row.getValue().asText());

            ResponseEntity<String> response = put(CATEGORY, CATALOG_KEY, ACTOR, text);

            JsonNode body = objectMapper.readTree(response.getBody());
            assertThat(response.getStatusCode()).as("%s status", row.getKey()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(body.path("success").asBoolean()).as("%s success", row.getKey()).isFalse();
            assertThat(body.path("data").path("code").asText()).as("%s code", row.getKey())
                    .isEqualTo(CategoryIconInvalidException.ERROR_CODE);
            assertThat(body.path("data").path("reason").asText()).as("%s reason", row.getKey())
                    .isEqualTo(reason.name());
            assertThat(body.path("message").asText()).as("%s message (fixed copy)", row.getKey())
                    .isEqualTo(ICON_INVALID_MESSAGE)
                    .doesNotContain(text);
            seen.add(reason);
            sent++;
        }

        assertThat(sent).as("text reject fixtures sent").isGreaterThanOrEqualTo(25);
        assertThat(seen).isEqualTo(EnumSet.complementOf(EnumSet.of(CategoryIconInvalidException.Reason.TOO_LARGE)));
        assertThat(iconRow(CATEGORY).get("icon_key")).isNull();
        assertThat(iconSetAuditRows(CATEGORY)).isZero();
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("B541 gate OFF (the default): approve, unhide and internal create of an iconless category all pass "
            + "(no CATEGORY_ICON_REQUIRED)")
    void should_allowApproveUnhideAndCreateWithoutIcon_when_gateOff() throws Exception {
        String pending = "IT539_GATEOFF_P";
        String hidden = "IT539_GATEOFF_H";
        String created = "IT539_GATEOFF_C";
        long pendingId = fixture.insertCategory(pending, "Іт539 вимкн запит", "PENDING", null,
                Instant.parse("2020-01-04T10:00:00Z"));
        fixture.insertCategory(hidden, "Іт539 вимкн прихована", "APPROVED", null, Instant.parse("2020-01-05T10:00:00Z"));
        jdbcTemplate.update("UPDATE platform_categories SET active = false WHERE name = ?", hidden);
        try {
            ResponseEntity<String> approved = internalJson(HttpMethod.POST,
                    BASE + "/requests/CATEGORY/" + pendingId + "/approve", CATALOG_KEY, Map.of());
            ResponseEntity<String> unhidden = internalJson(HttpMethod.PATCH, BASE + "/categories/" + hidden,
                    CATALOG_KEY, Map.of("active", true));
            ResponseEntity<String> createdResponse = internalJson(HttpMethod.POST,
                    "/api/v1/internal/service-categories", MASTER_KEY, Map.of("name", created));

            assertThat(approved.getStatusCode()).as("approve, body=%s", approved.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(unhidden.getStatusCode()).as("unhide, body=%s", unhidden.getBody()).isEqualTo(HttpStatus.OK);
            assertThat(createdResponse.getStatusCode()).as("create, body=%s", createdResponse.getBody())
                    .isEqualTo(HttpStatus.CREATED);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM platform_categories WHERE name = ?",
                    String.class, pending)).isEqualTo("APPROVED");
            assertThat(jdbcTemplate.queryForObject("SELECT active FROM platform_categories WHERE name = ?",
                    Boolean.class, hidden)).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT icon_key FROM platform_categories WHERE name = ?",
                    String.class, created)).isNull();
        } finally {
            jdbcTemplate.update("DELETE FROM platform_categories WHERE name = ?", created);
        }
    }
}
