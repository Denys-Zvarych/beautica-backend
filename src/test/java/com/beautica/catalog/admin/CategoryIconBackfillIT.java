package com.beautica.catalog.admin;

import com.beautica.catalog.CatalogCacheEvictor;
import com.beautica.AbstractIntegrationTest;
import com.beautica.media.service.R2StorageService;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * B540 (SVC-E12) — {@code POST /api/v1/internal/catalog/category-icons/backfill} on Postgres with the R2 fake. Same
 * context as {@code InternalCategoryIconIT} (same key property, same {@code @MockBean}). The backfill touches every
 * seeded category, so {@link #cleanUp()} clears the icon columns of ALL rows (no sibling class sees an icon).
 */
@DisplayName("Category icon backfill (IT) — set / skipped / missing, idempotent (B540)")
@TestPropertySource(properties = "app.internal-catalog-key=" + InternalCategoryIconIT.CATALOG_KEY)
class CategoryIconBackfillIT extends AbstractIntegrationTest {

    private static final String URL = "/api/v1/internal/catalog/category-icons/backfill";
    private static final String WITH_ICON = "IT540_WITH_ICON";
    private static final String NO_MATCH = "IT540_NO_MATCH";
    private static final String REJECTED = "IT540_REJECTED";
    /** Seeded (V74) and in the bundled manifest. */
    private static final String BUNDLED = "HAIRDRESSING";
    private static final String OWN_KEY = "catalog/category-icons/" + WITH_ICON + "/0123456789abcdef.svg";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CatalogCacheEvictor cacheEvictor;
    @Autowired private CacheManager cacheManager;

    @MockBean private R2StorageService r2;

    private CatalogRequestFixture fixture;

    @BeforeEach
    void seed() {
        reset(r2);
        when(r2.isEnabled()).thenReturn(true);
        when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0));
        when(r2.deleteFiles(any())).thenReturn(Set.of());
        fixture = new CatalogRequestFixture(jdbcTemplate, passwordEncoder);
        fixture.insertCategory(WITH_ICON, "Іт540 з іконкою", "APPROVED", null, Instant.parse("2020-01-01T10:00:00Z"));
        fixture.insertCategory(NO_MATCH, "Іт540 без збігу", "PENDING", null, Instant.parse("2020-01-02T10:00:00Z"));
        fixture.insertCategory(REJECTED, "Іт540 відхилена", "REJECTED", null, Instant.parse("2020-01-03T10:00:00Z"));
        jdbcTemplate.update("UPDATE platform_categories SET icon_key = ?, icon_sha256 = ?, icon_updated_at = now() "
                + "WHERE name = ?", OWN_KEY, "a".repeat(64), WITH_ICON);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("UPDATE platform_categories SET icon_key = NULL, icon_sha256 = NULL, icon_updated_at = NULL "
                + "WHERE icon_key IS NOT NULL");
        fixture.cleanup();
        cacheEvictor.evictAll();
    }

    private JsonNode backfill() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Internal-Key", InternalCategoryIconIT.CATALOG_KEY);
        headers.set(AdminActor.HEADER, "beautica.ua@gmail.com");
        ResponseEntity<String> response = restTemplate.exchange(URL, HttpMethod.POST, new HttpEntity<>(headers),
                String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private static List<String> names(JsonNode array) {
        List<String> names = new ArrayList<>();
        array.forEach(n -> names.add(n.asText()));
        return names;
    }

    private int auditRows(String name) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog_admin_audit WHERE action = 'ICON_SET' "
                + "AND target_id = ?", Integer.class, name);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("first call: bundled one set, iconed one skipped (untouched), unmatched one missing, rejected ignored; "
            + "second call sets nothing; one audit row")
    void should_setSkipAndReport_when_backfillRunTwice() throws Exception {
        JsonNode first = backfill();
        JsonNode second = backfill();

        assertThat(names(first.path("set"))).contains(BUNDLED).doesNotContain(WITH_ICON, NO_MATCH, REJECTED);
        assertThat(names(first.path("skipped"))).contains(WITH_ICON);
        assertThat(names(first.path("missing"))).contains(NO_MATCH).doesNotContain(REJECTED, BUNDLED);
        assertThat(names(second.path("set"))).isEmpty();
        assertThat(names(second.path("skipped"))).contains(BUNDLED, WITH_ICON);
        assertThat(jdbcTemplate.queryForObject("SELECT icon_key FROM platform_categories WHERE name = ?", String.class,
                WITH_ICON)).isEqualTo(OWN_KEY);
        assertThat(jdbcTemplate.queryForObject("SELECT icon_key FROM platform_categories WHERE name = ?", String.class,
                BUNDLED)).startsWith("catalog/category-icons/" + BUNDLED + "/");
        assertThat(auditRows(BUNDLED)).isEqualTo(1);
        assertThat(auditRows(WITH_ICON)).isZero();
    }

    @Test
    @DisplayName("the run evicts the platform-catalog caches once at the end (not per category); per-master caches "
            + "(masterServices) survive")
    void should_evictPlatformCatalogOnlyAfterRun_when_iconsSet() throws Exception {
        Cache approved = cacheManager.getCache("approved-categories");
        Cache masterServices = cacheManager.getCache("masterServices");
        assertThat(approved).isNotNull();
        assertThat(masterServices).isNotNull();
        approved.put("it540-probe", "warm");
        masterServices.put("it540-probe", "warm");

        JsonNode result = backfill();

        assertThat(names(result.path("set"))).contains(BUNDLED);
        assertThat(approved.get("it540-probe")).isNull();
        assertThat(masterServices.get("it540-probe")).isNotNull();
    }
}
