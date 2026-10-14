package com.beautica.search.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.ServiceTestFixtures;
import com.beautica.service.dto.ApprovedCategoryResponse;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.dto.ServiceTypeResponse;
import com.beautica.service.service.CategoryRequestService;
import com.beautica.service.service.ServiceTypeMatch;
import com.beautica.service.service.ServiceTypeSlugResolver;
import com.beautica.service.service.ServiceTypeSuggestionService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;

/**
 * End-to-end regression for the "approved catalogue entry invisible until the TTL expired" bug
 * (branch fix/catalogue-cache-evictions). Real Caffeine caches, real Postgres, real HTTP filter
 * chain: the admin decision goes through the actual permitAll review POST with the e-mailed token.
 *
 * <p>Before the fix, approving a service-type suggestion evicted none of {@code service-types},
 * {@code service-type-search} or {@code searchSuggestionCatalogue}, and approving a category did
 * not evict {@code searchSuggestionCatalogue}: a warm cache kept serving the pre-approval list.
 *
 * <p>The suggestions HTTP endpoint joins the catalogue with per-place AVAILABILITY, so a
 * freshly approved type (no provider offers it yet) can never surface there; the catalogue half
 * is asserted through the real {@link SearchSuggestionCatalogue} proxy instead, which is why this
 * IT lives in the package that owns the package-private snapshot records.
 *
 * <p>Negative half: the review POSTs are public, so a garbage token (or a valid reject) must NOT
 * flush the platform-wide caches — asserted by cached-value identity, which a re-population after
 * an eviction cannot satisfy.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Catalogue approval — caches evicted post-commit on APPROVED only, end-to-end over HTTP")
class CatalogueApprovalCacheEvictionIT extends AbstractIntegrationTest {

    private static final String SUGGESTION_APPROVE = "/api/v1/service-types/suggestions/approve";
    private static final String SUGGESTION_REJECT = "/api/v1/service-types/suggestions/reject";
    private static final String CATEGORY_APPROVE = "/api/v1/service-categories/requests/approve";
    private static final String CATEGORY_REJECT = "/api/v1/service-categories/requests/reject";
    private static final String APPROVED_CATEGORIES = "/api/v1/service-categories/approved";
    private static final String INTERNAL_CATEGORIES = "/api/v1/internal/service-categories";
    // application-test.yml app.internal-api-key — keep in sync.
    private static final String INTERNAL_KEY = "test-internal-api-key";

    // HAIRDRESSING is seeded APPROVED + active (V74) and mapped to a System-A bucket (V78).
    private static final String SEEDED_CATEGORY = "HAIRDRESSING";
    private static final String SUGGESTED_TYPE = "Qzevict Plumbix Ritual";
    private static final String TYPE_QUERY = "qzevict plumbix ritual";
    private static final String NEW_CATEGORY = "ITCACHEEVICT_CAT";
    private static final String INTERNAL_CATEGORY = "ITCACHEEVICT_OPS";

    private static final List<String> CATALOGUE_CACHES = List.of(
            "service-types", "service-type-search", SearchSuggestionCatalogue.CACHE_NAME,
            "approved-categories", "platform-category-order");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private CacheManager cacheManager;
    @Autowired private SearchSuggestionCatalogue suggestionCatalogue;
    @Autowired private ServiceTypeSlugResolver slugResolver;
    @Autowired private ServiceTypeSuggestionService suggestionService;
    @Autowired private CategoryRequestService categoryRequestService;

    @AfterEach
    void cleanCatalogueRows() {
        // Runs before AbstractIntegrationTest#cleanDb (which deletes users + clears every cache).
        // FK order: service_types (promoted row) -> service_type_suggestion (FK users) -> platform_categories.
        jdbcTemplate.update("DELETE FROM service_types WHERE name_uk = ?", SUGGESTED_TYPE);
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE suggested_name = ?", SUGGESTED_TYPE);
        jdbcTemplate.update("DELETE FROM platform_categories WHERE name IN (?, ?)", NEW_CATEGORY, INTERNAL_CATEGORY);
    }

    // ── positive: approval is visible immediately ───────────────────────────────────────────

    @Test
    @DisplayName("approve service-type suggestion via review POST — new type visible at once in /service-types?q=, the suggestions catalogue and slug resolution")
    void should_exposeNewTypeImmediately_when_suggestionApprovedOverHttp() throws Exception {
        String token = submitSuggestionAndCaptureToken();
        assertThat(searchServiceTypes()).as("precondition: search warmed with no hit").isEmpty();
        assertThat(catalogueServiceNames()).as("precondition: catalogue warmed without the type")
                .doesNotContain(SUGGESTED_TYPE);
        assertThat(cacheSize("service-type-search")).as("precondition: service-type-search warm").isPositive();
        assertThat(cacheSize(SearchSuggestionCatalogue.CACHE_NAME)).as("precondition: catalogue warm").isPositive();

        ResponseEntity<String> decision = postDecision(SUGGESTION_APPROVE, token);

        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decision.getBody()).contains("Пропозицію прийнято");
        assertThat(searchServiceTypes())
                .as("GET /service-types?q= serves the approved type without waiting for the TTL")
                .extracting(ServiceTypeResponse::nameUk)
                .containsExactly(SUGGESTED_TYPE);
        assertThat(catalogueServiceNames())
                .as("search-suggestions catalogue includes the approved type")
                .contains(SUGGESTED_TYPE);
        String slug = jdbcTemplate.queryForObject(
                "SELECT slug FROM service_types WHERE name_uk = ?", String.class, SUGGESTED_TYPE);
        assertThat(slugResolver.resolve(List.of(slug)))
                .as("slug resolution (service-types 'ALL' cache) resolves the approved type")
                .containsExactly(Optional.of(new ServiceTypeMatch(promotedTypeId(), SUGGESTED_TYPE)));
    }

    @Test
    @DisplayName("approve category request via review POST — new category visible at once in /service-categories/approved and the suggestions catalogue")
    void should_exposeNewCategoryImmediately_when_categoryApprovedOverHttp() throws Exception {
        String ownerToken = newOwnerToken();
        String token = submitCategoryRequestAndCaptureToken();
        assertThat(approvedCategoryNames(ownerToken)).as("precondition: approved list warmed without it")
                .doesNotContain(NEW_CATEGORY);
        assertThat(catalogueCategoryKeys()).as("precondition: catalogue warmed without it")
                .doesNotContain(NEW_CATEGORY);

        ResponseEntity<String> decision = postDecision(CATEGORY_APPROVE, token);

        assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(decision.getBody()).contains("Категорію підтверджено");
        assertThat(approvedCategoryNames(ownerToken))
                .as("GET /service-categories/approved lists the approved category without waiting for the TTL")
                .contains(NEW_CATEGORY);
        assertThat(catalogueCategoryKeys())
                .as("search-suggestions catalogue includes the approved category")
                .contains(NEW_CATEGORY);
    }

    @Test
    @DisplayName("internal ops POST creates a category — visible at once in /service-categories/approved and the suggestions catalogue")
    void should_exposeNewCategoryImmediately_when_createdThroughInternalApi() throws Exception {
        String ownerToken = newOwnerToken();
        assertThat(approvedCategoryNames(ownerToken)).doesNotContain(INTERNAL_CATEGORY);
        assertThat(catalogueCategoryKeys()).doesNotContain(INTERNAL_CATEGORY);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Key", INTERNAL_KEY);

        ResponseEntity<String> created = restTemplate.exchange(INTERNAL_CATEGORIES, HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of("name", INTERNAL_CATEGORY)), headers),
                String.class);

        assertThat(created.getStatusCode()).as("body=%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(approvedCategoryNames(ownerToken)).contains(INTERNAL_CATEGORY);
        assertThat(catalogueCategoryKeys()).contains(INTERNAL_CATEGORY);
    }

    // ── negative: public endpoints must not flush the caches ────────────────────────────────

    @Test
    @DisplayName("garbage token on both approve POSTs — neutral 200, every catalogue cache keeps its exact cached value")
    void should_keepCachesWarm_when_approvePostedWithGarbageToken() throws Exception {
        Map<String, Map<Object, Object>> before = warmAllCatalogueCachesAndSnapshot();

        ResponseEntity<String> typeDecision = postDecision(SUGGESTION_APPROVE, garbageToken());
        ResponseEntity<String> categoryDecision = postDecision(CATEGORY_APPROVE, garbageToken());

        assertThat(typeDecision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(categoryDecision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertCachesUntouched(before);
    }

    @Test
    @DisplayName("valid-token reject of a suggestion and a category request — persisted REJECTED, caches keep their exact cached value")
    void should_keepCachesWarm_when_validRequestsRejected() throws Exception {
        String suggestionToken = submitSuggestionAndCaptureToken();
        String categoryToken = submitCategoryRequestAndCaptureToken();
        Map<String, Map<Object, Object>> before = warmAllCatalogueCachesAndSnapshot();

        ResponseEntity<String> typeDecision = postDecision(SUGGESTION_REJECT, suggestionToken);
        ResponseEntity<String> categoryDecision = postDecision(CATEGORY_REJECT, categoryToken);

        assertThat(typeDecision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(categoryDecision.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM service_type_suggestion WHERE suggested_name = ?", String.class, SUGGESTED_TYPE))
                .isEqualTo("REJECTED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM platform_categories WHERE name = ?", String.class, NEW_CATEGORY))
                .isEqualTo("REJECTED");
        assertCachesUntouched(before);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private Map<String, Map<Object, Object>> warmAllCatalogueCachesAndSnapshot() throws Exception {
        searchServiceTypes();
        approvedCategoryNames(newOwnerToken());
        suggestionCatalogue.snapshot(); // also warms service-types 'ALL' + platform-category-order
        Map<String, Map<Object, Object>> before = new HashMap<>();
        for (String name : CATALOGUE_CACHES) {
            Map<Object, Object> entries = Map.copyOf(nativeCache(name).asMap());
            assertThat(entries).as("precondition: cache %s is warm", name).isNotEmpty();
            before.put(name, entries);
        }
        return before;
    }

    private void assertCachesUntouched(Map<String, Map<Object, Object>> before) {
        before.forEach((name, entries) -> entries.forEach((key, value) ->
                assertThat(nativeCache(name).asMap().get(key))
                        .as("cache %s key %s must not be evicted/re-populated", name, key)
                        .isSameAs(value)));
    }

    @SuppressWarnings("unchecked")
    private Cache<Object, Object> nativeCache(String name) {
        return ((CaffeineCache) cacheManager.getCache(name)).getNativeCache();
    }

    private long cacheSize(String name) {
        return nativeCache(name).asMap().size();
    }

    private ResponseEntity<String> postDecision(String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", token);
        return restTemplate.postForEntity(path, new HttpEntity<>(form, headers), String.class);
    }

    private List<ServiceTypeResponse> searchServiceTypes() throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/service-types?q={q}", String.class, TYPE_QUERY);
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<List<ServiceTypeResponse>>>() {}).data();
    }

    private List<String> approvedCategoryNames(String bearer) throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(bearer);
        ResponseEntity<String> resp = restTemplate.exchange(
                APPROVED_CATEGORIES, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(),
                        new TypeReference<ApiResponse<List<ApprovedCategoryResponse>>>() {}).data()
                .stream().map(ApprovedCategoryResponse::name).toList();
    }

    private List<String> catalogueServiceNames() {
        return suggestionCatalogue.snapshot().services().stream().map(CatalogueServiceEntry::nameUk).toList();
    }

    private List<String> catalogueCategoryKeys() {
        return suggestionCatalogue.snapshot().categories().stream().map(CatalogueCategoryEntry::key).toList();
    }

    private UUID promotedTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM service_types WHERE name_uk = ?", UUID.class, SUGGESTED_TYPE);
    }

    private String submitSuggestionAndCaptureToken() throws Exception {
        suggestionService.submitSuggestionInternal(SEEDED_CATEGORY, SUGGESTED_TYPE, "cache IT", newRequester());
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendServiceTypeSuggestionNotification(
                anyString(), anyString(), anyString(), anyString(), anyString(), url.capture());
        return url.getValue().substring(url.getValue().indexOf("token=") + "token=".length());
    }

    private String submitCategoryRequestAndCaptureToken() throws Exception {
        categoryRequestService.submitRequest(
                new CreateCategoryRequestRequest(NEW_CATEGORY, "Кеш-евікшн тест", null), newRequester());
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendCategoryRequestNotification(
                anyString(), anyString(), anyString(), anyString(), url.capture());
        return url.getValue().substring(url.getValue().lastIndexOf('/') + 1);
    }

    private static String garbageToken() {
        return "garbage-" + UUID.randomUUID().toString().replace("-", "");
    }

    private ServiceTestFixtures fixtures() {
        return new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    private UUID newRequester() throws Exception {
        return fixtures().createSalonOwnerWithSalonAndGetUserId("cache-evict-" + System.nanoTime() + "@beautica.test");
    }

    private String newOwnerToken() throws Exception {
        return fixtures().createSalonOwnerAndGetToken("cache-reader-" + System.nanoTime() + "@beautica.test");
    }
}
