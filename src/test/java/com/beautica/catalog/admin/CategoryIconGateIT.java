package com.beautica.catalog.admin;

import com.beautica.AbstractIntegrationTest;
import com.beautica.catalog.CatalogCacheEvictor;
import com.beautica.media.service.R2StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.time.Instant;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * B541 (SVC-60) — the icon gate ON ({@code app.catalog.icon-required=true}) through the real chain: portal approve,
 * unhide and internal create without an icon → 409 {@code CATEGORY_ICON_REQUIRED} with nothing written; with an icon
 * they pass. (Gate OFF = every other catalog IT, which runs on the default {@code false}.)
 */
@DisplayName("Category icon gate ON (IT) — approve / unhide / internal create (B541)")
@TestPropertySource(properties = {
        "app.internal-catalog-key=" + InternalCategoryIconIT.CATALOG_KEY,
        "app.catalog.icon-required=true"})
class CategoryIconGateIT extends AbstractIntegrationTest {

    private static final String CATALOG_KEY = InternalCategoryIconIT.CATALOG_KEY;
    private static final String MASTER_KEY = "test-internal-api-key";
    private static final String ACTOR = "beautica.ua@gmail.com";
    private static final String PENDING = "IT541_PENDING";
    private static final String HIDDEN = "IT541_HIDDEN";
    private static final String CREATED = "IT541_CREATED";
    private static final String VISIBLE = "IT541_VISIBLE";
    private static final String SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path d=\"M0 0L24 24\"/></svg>";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CatalogCacheEvictor cacheEvictor;
    @Autowired private CacheManager cacheManager;

    @MockBean private R2StorageService r2;

    private CatalogRequestFixture fixture;
    private long pendingId;

    @BeforeEach
    void seed() {
        reset(r2);
        when(r2.isEnabled()).thenReturn(true);
        when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0));
        when(r2.deleteFiles(any())).thenReturn(Set.of());
        fixture = new CatalogRequestFixture(jdbcTemplate, passwordEncoder);
        pendingId = fixture.insertCategory(PENDING, "Іт541 запит", "PENDING", null, Instant.parse("2020-01-01T10:00:00Z"));
        fixture.insertCategory(HIDDEN, "Іт541 прихована", "APPROVED", null, Instant.parse("2020-01-02T10:00:00Z"));
        jdbcTemplate.update("UPDATE platform_categories SET active = false WHERE name = ?", HIDDEN);
        cacheEvictor.evictAll();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM platform_categories WHERE name = ?", CREATED);
        fixture.cleanup();
        cacheEvictor.evictAll();
    }

    private ResponseEntity<String> call(HttpMethod method, String url, String key, Object body) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", key);
        headers.set(AdminActor.HEADER, ACTOR);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String json = body == null ? null : objectMapper.writeValueAsString(body);
        return restTemplate.exchange(url, method, new HttpEntity<>(json, headers), String.class);
    }

    private void assertIconRequired(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(response.getBody()).path("data").path("code").asText())
                .isEqualTo("CATEGORY_ICON_REQUIRED");
    }

    private String column(String column, String name) {
        return jdbcTemplate.queryForObject("SELECT " + column + "::text FROM platform_categories WHERE name = ?",
                String.class, name);
    }

    private int auditRows() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog_admin_audit", Integer.class);
        return count == null ? 0 : count;
    }

    private ResponseEntity<String> approve() throws Exception {
        return call(HttpMethod.POST, "/api/v1/internal/catalog/requests/CATEGORY/" + pendingId + "/approve",
                CATALOG_KEY, null);
    }

    private void putIcon(String name) throws Exception {
        ResponseEntity<String> put = call(HttpMethod.PUT, "/api/v1/internal/catalog/categories/" + name + "/icon",
                CATALOG_KEY, Map.of("svg", SVG));
        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("approve without an icon: 409, row stays PENDING, no audit row; after PUT icon the same id approves")
    void should_refuseThenApprove_when_iconMissingThenUploaded() throws Exception {
        ResponseEntity<String> refused = approve();

        assertIconRequired(refused);
        assertThat(column("status", PENDING)).isEqualTo("PENDING");
        assertThat(auditRows()).isZero();
        putIcon(PENDING);
        ResponseEntity<String> approved = approve();
        assertThat(approved.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(column("status", PENDING)).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("reject never needs an icon")
    void should_reject_when_noIcon() throws Exception {
        ResponseEntity<String> rejected = call(HttpMethod.POST,
                "/api/v1/internal/catalog/requests/CATEGORY/" + pendingId + "/reject", CATALOG_KEY, Map.of());

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(column("status", PENDING)).isEqualTo("REJECTED");
    }

    @Test
    @DisplayName("unhide without an icon: 409, stays hidden; with an icon: unhidden")
    void should_refuseThenUnhide_when_iconMissingThenUploaded() throws Exception {
        ResponseEntity<String> refused = call(HttpMethod.PATCH, "/api/v1/internal/catalog/categories/" + HIDDEN,
                CATALOG_KEY, Map.of("active", true));

        assertIconRequired(refused);
        assertThat(column("active", HIDDEN)).isEqualTo("false");
        putIcon(HIDDEN);
        ResponseEntity<String> unhidden = call(HttpMethod.PATCH, "/api/v1/internal/catalog/categories/" + HIDDEN,
                CATALOG_KEY, Map.of("active", true));
        assertThat(unhidden.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(column("active", HIDDEN)).isEqualTo("true");
    }

    @Test
    @DisplayName("internal create without svg: 409, no row; with svg: 201, row has an icon")
    void should_refuseThenCreate_when_svgMissingThenGiven() throws Exception {
        ResponseEntity<String> refused = call(HttpMethod.POST, "/api/v1/internal/service-categories", MASTER_KEY,
                Map.of("name", CREATED));

        assertIconRequired(refused);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM platform_categories WHERE name = ?",
                Integer.class, CREATED)).isZero();
        ResponseEntity<String> created = call(HttpMethod.POST, "/api/v1/internal/service-categories", MASTER_KEY,
                Map.of("name", CREATED, "svg", SVG));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(column("icon_key", CREATED)).startsWith("catalog/category-icons/" + CREATED + "/");
    }

    @Test
    @DisplayName("gate ON never blocks a non-live-making edit of an iconless category: rename, PATCH active=true on an "
            + "already-live row, hide, and a rename of a hidden one — all 200 and applied")
    void should_allowRenameHideAndNoOpActive_when_gateOnAndNoIcon() throws Exception {
        fixture.insertCategory(VISIBLE, "Іт541 видима", "APPROVED", null, Instant.parse("2020-01-03T10:00:00Z"));
        String url = "/api/v1/internal/catalog/categories/";

        ResponseEntity<String> renamed = call(HttpMethod.PATCH, url + VISIBLE, CATALOG_KEY,
                Map.of("displayName", "Іт541 нова назва"));
        ResponseEntity<String> stillLive = call(HttpMethod.PATCH, url + VISIBLE, CATALOG_KEY, Map.of("active", true));
        ResponseEntity<String> hidden = call(HttpMethod.PATCH, url + VISIBLE, CATALOG_KEY, Map.of("active", false));
        ResponseEntity<String> hiddenRenamed = call(HttpMethod.PATCH, url + HIDDEN, CATALOG_KEY,
                Map.of("displayName", "Іт541 інша назва"));

        assertThat(renamed.getStatusCode()).as("rename, body=%s", renamed.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(stillLive.getStatusCode()).as("active=true on a live row, body=%s", stillLive.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(hidden.getStatusCode()).as("hide, body=%s", hidden.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(hiddenRenamed.getStatusCode()).as("rename hidden, body=%s", hiddenRenamed.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(column("display_name", VISIBLE)).isEqualTo("Іт541 нова назва");
        assertThat(column("active", VISIBLE)).isEqualTo("false");
        assertThat(column("display_name", HIDDEN)).isEqualTo("Іт541 інша назва");
        assertThat(column("active", HIDDEN)).as("a rename does not unhide").isEqualTo("false");
        assertThat(column("icon_key", VISIBLE)).isNull();
    }

    @Test
    @DisplayName("internal create WITH svg empties the warmed approved-categories cache (the one eviction, after the "
            + "icon transaction commits)")
    void should_evictApprovedCache_when_createdWithSvg() throws Exception {
        Cache approved = cacheManager.getCache("approved-categories");
        assertThat(approved).isNotNull();
        approved.put("all", "warm");

        ResponseEntity<String> created = call(HttpMethod.POST, "/api/v1/internal/service-categories", MASTER_KEY,
                Map.of("name", CREATED, "svg", SVG));

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(approved.get("all")).as("approved-categories after create-with-icon").isNull();
    }
}
