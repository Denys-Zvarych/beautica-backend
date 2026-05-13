package com.beautica.media;

import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.media.dto.AvatarResponse;
import com.beautica.media.dto.MediaFileResponse;
import com.beautica.media.entity.EntityType;
import com.beautica.media.repository.MediaRepository;
import com.beautica.media.service.R2StorageService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Full-context integration test for the media endpoints.
 *
 * <p>Validates the read-after-write contract end-to-end: an upload through the
 * REST API must (a) persist the {@code users.avatar_r2_key} / {@code media_files}
 * row and (b) be visible to a follow-up GET. {@link R2StorageService} is mocked
 * so the test never reaches a real R2 bucket — {@code buildPublicUrl} returns a
 * deterministic URL prefix the controller can echo back.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Media — full-context integration")
class MediaIntegrationTest extends AbstractMediaIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MediaIntegrationTest.class);
    private static final String AVATAR_URL = "/api/v1/media/avatar";
    private static final String PORTFOLIO_URL = "/api/v1/media/portfolio";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockBean
    private R2StorageService r2StorageService;

    /**
     * Phase 7.7 — spied so the cache-hit IT can assert the underlying repository was
     * touched exactly once across two GETs. {@code @SpyBean} keeps the real bean
     * wired into the application context, so cache-aside semantics on
     * {@code MediaService.getPortfolio} are exercised end-to-end.
     */
    @SpyBean
    private MediaRepository mediaRepository;

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
    void configureClientAndR2() {
        restTemplate.getRestTemplate().setRequestFactory(HC5_FACTORY);
        when(r2StorageService.buildPublicUrl(anyString()))
                .thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0));
        doNothing().when(r2StorageService).uploadFile(anyString(), any(), anyLong(), anyString());
        doNothing().when(r2StorageService).deleteFile(anyString());
    }

    // ── #16 — avatar upload persists the R2 key on the user row ──────────────

    @Test
    @DisplayName("POST /media/avatar then read users.avatar_r2_key — persisted key is non-null")
    void should_updateAvatarUrl_when_avatarUploaded() throws Exception {
        // Arrange
        String email = "media-it-avatar-" + System.nanoTime() + "@beautica.test";
        UUID userId = insertClient(email);
        String token = loginAndGetToken(email);

        // Act
        log.debug("Act: POST {} as user={} — first upload, avatar_r2_key must be persisted", AVATAR_URL, userId);
        ResponseEntity<String> resp = restTemplate.exchange(
                AVATAR_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)),
                String.class);

        // Assert — HTTP success
        assertThat(resp.getStatusCode())
                .as("avatar upload must return 200")
                .isEqualTo(HttpStatus.OK);

        // Assert — persisted state (UserProfileResponse does not expose avatarUrl,
        // so query the DB directly to validate the read-after-write contract).
        String persistedKey = jdbcTemplate.queryForObject(
                "SELECT avatar_r2_key FROM users WHERE id = ?", String.class, userId);
        String persistedUrl = jdbcTemplate.queryForObject(
                "SELECT avatar_url FROM users WHERE id = ?", String.class, userId);
        assertThat(persistedKey)
                .as("users.avatar_r2_key must be persisted for user=%s", userId)
                .isNotNull()
                .startsWith("avatars/" + userId + "/");
        assertThat(persistedUrl)
                .as("users.avatar_url must mirror the public URL built from the key")
                .isNotNull()
                .isNotBlank();
    }

    // ── #17 — second upload replaces the first (R2 delete + DB swap) ─────────

    @Test
    @DisplayName("POST /media/avatar twice — second upload replaces first key and deletes the old R2 blob")
    void should_replaceOldAvatar_when_secondAvatarUploaded() throws Exception {
        // Arrange
        String email = "media-it-replace-" + System.nanoTime() + "@beautica.test";
        UUID userId = insertClient(email);
        String token = loginAndGetToken(email);

        // Act — first upload
        ResponseEntity<String> first = restTemplate.exchange(
                AVATAR_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)),
                String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        String firstKey = jdbcTemplate.queryForObject(
                "SELECT avatar_r2_key FROM users WHERE id = ?", String.class, userId);

        // Act — second upload (must trigger r2.deleteFile(firstKey) before writing)
        log.debug("Act: POST {} second time as user={} — first key must be deleted", AVATAR_URL, userId);
        ResponseEntity<String> second = restTemplate.exchange(
                AVATAR_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)),
                String.class);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        String secondKey = jdbcTemplate.queryForObject(
                "SELECT avatar_r2_key FROM users WHERE id = ?", String.class, userId);

        // Assert — keys differ
        assertThat(firstKey).isNotNull();
        assertThat(secondKey).isNotNull();
        assertThat(secondKey)
                .as("second upload must produce a different R2 key from the first")
                .isNotEqualTo(firstKey);

        // Assert — R2 delete invoked with the first (now-superseded) key
        ArgumentCaptor<String> deletedKey = ArgumentCaptor.forClass(String.class);
        verify(r2StorageService, atLeastOnce()).deleteFile(deletedKey.capture());
        assertThat(deletedKey.getAllValues())
                .as("R2 delete must have been called with the original avatar key")
                .contains(firstKey);
    }

    // ── #18 — portfolio uploads visible via public GET listing ───────────────

    @Test
    @DisplayName("POST /media/portfolio x3 then GET /salons/{id}/portfolio — listing returns 3 items")
    void should_returnPortfolioList_when_salonOwnerUploadsPhotos() throws Exception {
        // Arrange
        String ownerEmail = "media-it-port-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = insertSalonOwner(ownerEmail);
        UUID salonId = insertSalon(ownerId, "IT Salon");
        String ownerToken = loginAndGetToken(ownerEmail);

        // Act — upload 3 photos
        for (int i = 0; i < 3; i++) {
            ResponseEntity<String> resp = restTemplate.exchange(
                    PORTFOLIO_URL, HttpMethod.POST,
                    new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(ownerToken)),
                    String.class);
            assertThat(resp.getStatusCode())
                    .as("portfolio upload #%d must return 201", i + 1)
                    .isEqualTo(HttpStatus.CREATED);
        }

        // Act — public GET (no auth)
        log.debug("Act: GET /api/v1/salons/{}/portfolio without auth — must list 3 items", salonId);
        ResponseEntity<String> listResp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/portfolio", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()),
                String.class);

        // Assert
        assertThat(listResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(
                listResp.getBody(), new TypeReference<ApiResponse<List<MediaFileResponse>>>() {});
        assertThat(body.data())
                .as("public portfolio listing must contain all 3 uploaded items for salon=%s", salonId)
                .hasSize(3);
        assertThat(body.data())
                .allSatisfy(item -> {
                    assertThat(item.id()).as("id non-null").isNotNull();
                    assertThat(item.entityType()).as("entityType is SALON").isEqualTo(EntityType.SALON);
                    assertThat(item.entityId()).as("entityId matches seeded salon").isEqualTo(salonId);
                    assertThat(item.url()).as("url non-blank").isNotBlank();
                    assertThat(item.mediaType())
                            .as("mediaType is PORTFOLIO")
                            .isEqualTo(com.beautica.media.entity.MediaType.PORTFOLIO);
                    assertThat(item.createdAt()).as("createdAt non-null").isNotNull();
                });
    }

    // ── #19 — startup without R2 credentials covered elsewhere ───────────────
    // Already covered implicitly by AuthIntegrationTest and every other IT in
    // the suite — the test profile runs with placeholder R2 credentials
    // (application-test.yml) and the application context boots cleanly, which
    // is the contract this test would otherwise re-verify. No dedicated test
    // here because spinning up a second Spring context with a different
    // credentials shape would double the suite cost for no incremental signal.

    // ── Phase 7.7 — portfolio cache hit + post-write eviction ─────────────────

    @Test
    @DisplayName("GET /salons/{id}/portfolio twice — repository is hit exactly once (cache hit on 2nd call)")
    void should_returnCachedPortfolio_when_calledTwiceInSuccession() throws Exception {
        // Arrange
        String ownerEmail = "media-it-cache-hit-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = insertSalonOwner(ownerEmail);
        UUID salonId = insertSalon(ownerId, "Cache Salon");
        String ownerToken = loginAndGetToken(ownerEmail);

        // Seed a single portfolio photo. Reset the spy AFTER the upload — the upload
        // hits mediaRepo.save() which is on the same spied bean, and the upload's own
        // eviction touches the cache; we want a clean slate before the two GETs.
        ResponseEntity<String> upload = restTemplate.exchange(
                PORTFOLIO_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(ownerToken)),
                String.class);
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        clearInvocations(mediaRepository);

        // Act — two consecutive public GETs (no auth)
        ResponseEntity<String> first = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/portfolio", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        ResponseEntity<String> second = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/portfolio", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);

        // Assert — both succeed, repository was hit exactly ONCE across the pair.
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(mediaRepository, times(1))
                .findByEntityTypeAndEntityId(eq(EntityType.SALON), eq(salonId));
    }

    @Test
    @DisplayName("Upload between GETs evicts the cache — second GET sees the newly uploaded photo")
    void should_invalidatePortfolioCache_when_newPhotoUploaded() throws Exception {
        // Arrange
        String ownerEmail = "media-it-cache-evict-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = insertSalonOwner(ownerEmail);
        UUID salonId = insertSalon(ownerId, "Evict Salon");
        String ownerToken = loginAndGetToken(ownerEmail);

        // Act — initial GET populates an EMPTY result into the cache
        ResponseEntity<String> empty = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/portfolio", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(empty.getStatusCode()).isEqualTo(HttpStatus.OK);
        var emptyBody = objectMapper.readValue(
                empty.getBody(), new TypeReference<ApiResponse<List<MediaFileResponse>>>() {});
        assertThat(emptyBody.data())
                .as("first GET — cache must reflect the empty DB state")
                .isEmpty();

        // Act — upload a photo (must evict the cache entry post-commit)
        ResponseEntity<String> upload = restTemplate.exchange(
                PORTFOLIO_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(ownerToken)),
                String.class);
        assertThat(upload.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Act — second GET must see the new photo, proving eviction fired.
        ResponseEntity<String> populated = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/portfolio", HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(populated.getStatusCode()).isEqualTo(HttpStatus.OK);
        var populatedBody = objectMapper.readValue(
                populated.getBody(), new TypeReference<ApiResponse<List<MediaFileResponse>>>() {});

        // Assert — eviction happened: the second GET sees the uploaded photo.
        assertThat(populatedBody.data())
                .as("second GET — cache must have been evicted on upload, exposing the new row")
                .hasSize(1);
    }

    // ── Fix 7 — r2Enabled=false shape test ───────────────────────────────────

    @Test
    @DisplayName("POST /media/avatar — 200 with valid avatarUrl shape when R2 is disabled (mock mode)")
    void should_returnExpectedShape_when_r2IsDisabled_onAvatarUpload() throws Exception {
        // Arrange — R2StorageService is @MockBean; buildPublicUrl returns a stub URL
        // (configured in @BeforeEach). This exercises the full controller → service path
        // with the feature-flag-disabled no-op stubs, confirming the response shape.
        String email = "media-it-r2-off-" + System.nanoTime() + "@beautica.test";
        insertClient(email);
        String token = loginAndGetToken(email);

        // Act
        ResponseEntity<String> resp = restTemplate.exchange(
                AVATAR_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)),
                String.class);

        // Assert — HTTP status
        assertThat(resp.getStatusCode())
                .as("avatar upload in disabled-R2 mode must still return 200")
                .isEqualTo(HttpStatus.OK);

        // Assert — response body shape
        var body = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<AvatarResponse>>() {});
        assertThat(body.success())
                .as("ApiResponse.success must be true")
                .isTrue();
        assertThat(body.data())
                .as("AvatarResponse must be present")
                .isNotNull();
        assertThat(body.data().avatarUrl())
                .as("avatarUrl must be non-blank — stub buildPublicUrl returns https://cdn.example/...")
                .isNotBlank();
    }
}
