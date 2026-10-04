package com.beautica.media;

import com.beautica.config.TestSecurityConfig;
import com.beautica.media.service.R2StorageService;
import com.beautica.search.service.SearchCacheNames;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * Phase 344 audit-fix cycle 1 — every master-profile cache that renders {@code users.avatar_url}
 * reflects an avatar write immediately, not after its TTL:
 * {@code master-detail-by-user} ({@code GET /masters/me}, 10 min), {@code master-detail}
 * ({@code GET /masters/{id}}, 5 min) and {@code booking-slug-info} ({@code GET /book/{slug}/info}, 60 s).
 *
 * <p>Each case WARMS all three entries before the write. Without the warm-up an empty cache always
 * reads fresh and the test could not observe a missing eviction.
 *
 * <p>{@link R2StorageService} is mocked — no network; public URLs are deterministic.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Phase 344 c1 — avatar writes evict master-detail, master-detail-by-user and booking-slug-info")
class MasterAvatarCacheEvictionIT extends AbstractMediaIntegrationTest {

    private static final String AVATAR_URL = "/api/v1/media/avatar";
    private static final String CDN = "https://cdn.example/";
    private static final String SALON_MASTER = "SALON_MASTER";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheManager cacheManager;

    @MockBean
    private R2StorageService r2StorageService;

    @Override
    protected TestRestTemplate restTemplate() {
        return restTemplate;
    }

    @Override
    protected ObjectMapper objectMapper() {
        return objectMapper;
    }

    @Override
    protected PasswordEncoder passwordEncoder() {
        return passwordEncoder;
    }

    @BeforeEach
    void stubStorage() {
        when(r2StorageService.isEnabled()).thenReturn(true);
        when(r2StorageService.buildPublicUrl(anyString())).thenAnswer(inv -> CDN + inv.getArgument(0));
        doNothing().when(r2StorageService).uploadFile(anyString(), any(), anyLong(), anyString());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"INDEPENDENT_MASTER", SALON_MASTER})
    @DisplayName("upload: all three warmed master caches show the new avatarUrl immediately")
    void should_showNewAvatarUrlInAllMasterCaches_when_avatarUploaded(String role) throws Exception {
        MasterFixture m = insertMaster(role);
        String token = loginAndGetToken(m.email());
        assertAllAvatarUrls(m, token, null, "warm-up: a fresh master has no avatar — this read caches null");

        String uploadedUrl = uploadAvatar(token);

        assertThat(uploadedUrl).startsWith(CDN + "avatars/" + m.userId() + "/");
        assertAllAvatarUrls(m, token, uploadedUrl, "after upload — a null here is a stale cached entry");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"INDEPENDENT_MASTER", SALON_MASTER})
    @DisplayName("delete: all three warmed master caches show avatarUrl == null immediately")
    void should_showNullAvatarUrlInAllMasterCaches_when_avatarDeleted(String role) throws Exception {
        MasterFixture m = insertMaster(role);
        String token = loginAndGetToken(m.email());
        String uploadedUrl = uploadAvatar(token);
        assertAllAvatarUrls(m, token, uploadedUrl, "warm-up: this read caches the uploaded avatar");

        ResponseEntity<String> deleteResp = restTemplate.exchange(
                AVATAR_URL, HttpMethod.DELETE, new HttpEntity<>(bearer(token)), String.class);

        assertThat(deleteResp.getStatusCode().is2xxSuccessful())
                .as("DELETE /media/avatar body=%s", deleteResp.getBody()).isTrue();
        assertAllAvatarUrls(m, token, null, "after delete — a URL here is a stale cached entry");
    }

    @ParameterizedTest(name = "{0} clears search = {1}")
    @CsvSource({"INDEPENDENT_MASTER,true", SALON_MASTER + ",false"})
    @DisplayName("upload: search:masters:* is cleared only for an INDEPENDENT_MASTER — the only search-visible role (344 c2)")
    void should_clearSearchCachesOnlyForIndependentMaster_when_avatarUploaded(String role, boolean cleared)
            throws Exception {
        MasterFixture m = insertMaster(role);
        String token = loginAndGetToken(m.email());
        Object sentinelKey = "p344c2-sentinel-" + UUID.randomUUID();
        for (String name : SearchCacheNames.MASTERS_ALL) {
            searchCache(name).put(sentinelKey, "warm");
        }

        uploadAvatar(token);

        for (String name : SearchCacheNames.MASTERS_ALL) {
            assertThat(searchCache(name).get(sentinelKey))
                    .as("%s after a %s avatar upload", name, role)
                    .matches(entry -> (entry == null) == cleared,
                            cleared ? "cleared (independent masters appear in search)"
                                    : "untouched (a salon master never appears in public master search)");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private record MasterFixture(UUID userId, UUID masterId, String slug, String email) {
    }

    private void assertAllAvatarUrls(MasterFixture m, String token, String expected, String why) throws Exception {
        assertThat(avatarUrlOf(getEnveloped("/api/v1/masters/me", token)))
                .as("GET /masters/me (master-detail-by-user): %s", why).isEqualTo(expected);
        assertThat(avatarUrlOf(getEnveloped("/api/v1/masters/" + m.masterId(), null)))
                .as("GET /masters/{id} (master-detail): %s", why).isEqualTo(expected);
        // GET /book/{slug}/info returns the bare DTO, not an ApiResponse envelope.
        assertThat(avatarUrlOf(getBody("/api/v1/book/" + m.slug() + "/info", null)))
                .as("GET /book/{slug}/info (booking-slug-info): %s", why).isEqualTo(expected);
    }

    /** Seeds an email-verified master account plus its {@code masters} row (with a booking slug). */
    private MasterFixture insertMaster(String role) {
        long n = System.nanoTime();
        String email = "p344c1-" + role.toLowerCase() + "-" + n + "@beautica.test";
        UUID salonId = null;
        if (SALON_MASTER.equals(role)) {
            UUID ownerId = insertSalonOwner("p344c1-owner-" + n + "@beautica.test");
            salonId = insertSalon(ownerId, "P344c1 Salon " + n);
        }
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, salon_id, "
                        + "first_name, last_name) VALUES (?, ?, ?, ?, true, true, ?, 'Olena', 'Koval')",
                userId, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        UUID masterId = UUID.randomUUID();
        String slug = "olena-koval-" + Long.toString(n, 36);
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 0, 0, true, ?, NOW(), NOW())",
                masterId, userId, salonId, role, slug);
        return new MasterFixture(userId, masterId, slug, email);
    }

    private String uploadAvatar(String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                AVATAR_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).as("avatar upload body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data").path("avatarUrl").asText();
    }

    private JsonNode getEnveloped(String path, String tokenOrNull) throws Exception {
        return getBody(path, tokenOrNull).path("data");
    }

    private JsonNode getBody(String path, String tokenOrNull) throws Exception {
        HttpHeaders headers = tokenOrNull == null ? new HttpHeaders() : bearer(tokenOrNull);
        ResponseEntity<String> resp = restTemplate.exchange(
                path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(resp.getStatusCode()).as("GET %s body=%s", path, resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody());
    }

    private static String avatarUrlOf(JsonNode data) {
        JsonNode url = data.path("avatarUrl");
        return url.isMissingNode() || url.isNull() ? null : url.asText();
    }

    private Cache searchCache(String name) {
        Cache cache = cacheManager.getCache(name);
        assertThat(cache).as("cache %s must be configured", name).isNotNull();
        return cache;
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
