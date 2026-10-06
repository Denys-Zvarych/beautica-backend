package com.beautica.media;

import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.media.dto.AvatarResponse;
import com.beautica.media.service.R2StorageService;
import com.beautica.user.UserProfileResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * Phase 344 TC-1 — {@code GET /api/v1/users/me} reads back the caller's own avatar for every role.
 *
 * <p>Each case primes the {@code user-profile} cache with a {@code GET /users/me} BEFORE the avatar
 * write. Without that prime the test would pass even if the avatar writers never evicted the cache
 * (an empty cache always reads fresh), so the prime is what makes the eviction half of the contract
 * observable.
 *
 * <p>{@link R2StorageService} is mocked — no network; public URLs are deterministic.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Phase 344 — GET /users/me returns avatarUrl after POST/DELETE /media/avatar, every role")
class UserProfileAvatarIT extends AbstractMediaIntegrationTest {

    private static final String AVATAR_URL = "/api/v1/media/avatar";
    private static final String ME_URL = "/api/v1/users/me";
    private static final String CDN = "https://cdn.example/";
    private static final Set<String> SALON_STAFF_ROLES = Set.of("SALON_ADMIN", "SALON_MASTER");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

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
    @ValueSource(strings = {"SALON_OWNER", "SALON_ADMIN", "SALON_MASTER", "INDEPENDENT_MASTER", "CLIENT"})
    @DisplayName("POST then GET /users/me returns the uploaded avatarUrl")
    void should_returnUploadedAvatarUrl_when_ownAvatarUploaded(String role) throws Exception {
        String email = "p344-up-" + role.toLowerCase() + "-" + System.nanoTime() + "@beautica.test";
        UUID userId = insertUserWithRole(email, role);
        String token = loginAndGetToken(email);
        assertThat(getMe(token).avatarUrl())
                .as("%s: a fresh account has no avatar (this GET also primes the user-profile cache)", role)
                .isNull();

        String uploadedUrl = uploadAvatar(token);
        UserProfileResponse me = getMe(token);

        assertThat(uploadedUrl)
                .as("%s: upload must return a URL under the caller's own avatars/<id>/ prefix", role)
                .startsWith(CDN + "avatars/" + userId + "/");
        assertThat(me.avatarUrl())
                .as("%s: GET /users/me must return exactly the URL the upload returned — a stale "
                        + "null here means the avatar write did not evict user-profile", role)
                .isEqualTo(uploadedUrl);
        assertThat(me.avatarUrl())
                .as("%s: response must mirror users.avatar_url", role)
                .isEqualTo(jdbcTemplate.queryForObject(
                        "SELECT avatar_url FROM users WHERE id = ?", String.class, userId));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"SALON_OWNER", "SALON_ADMIN", "SALON_MASTER", "INDEPENDENT_MASTER", "CLIENT"})
    @DisplayName("DELETE then GET /users/me returns avatarUrl == null")
    void should_returnNullAvatarUrl_when_ownAvatarDeleted(String role) throws Exception {
        String email = "p344-del-" + role.toLowerCase() + "-" + System.nanoTime() + "@beautica.test";
        insertUserWithRole(email, role);
        String token = loginAndGetToken(email);
        uploadAvatar(token);
        assertThat(getMe(token).avatarUrl())
                .as("%s: precondition — avatar set and cached before the delete", role)
                .isNotNull();

        ResponseEntity<String> deleteResp = restTemplate.exchange(
                AVATAR_URL, HttpMethod.DELETE, new HttpEntity<>(bearer(token)), String.class);
        UserProfileResponse me = getMe(token);

        assertThat(deleteResp.getStatusCode().is2xxSuccessful())
                .as("%s: DELETE /media/avatar must succeed, got %s", role, deleteResp.getStatusCode())
                .isTrue();
        assertThat(me.avatarUrl())
                .as("%s: GET /users/me must read null after the delete — a non-null value means the "
                        + "delete did not evict user-profile", role)
                .isNull();
    }

    @Test
    @DisplayName("stale-cache regression: GET caches avatar #1, a REPLACE upload, the next GET shows avatar #2 immediately")
    void should_returnReplacementAvatarUrlImmediately_when_cachedAvatarIsReplaced() throws Exception {
        String email = "p344-replace-" + System.nanoTime() + "@beautica.test";
        UUID userId = insertUserWithRole(email, "SALON_OWNER");
        String token = loginAndGetToken(email);
        String firstUrl = uploadAvatar(token);
        assertThat(getMe(token).avatarUrl())
                .as("precondition — the first avatar is read back, and this GET caches a NON-null avatarUrl")
                .isEqualTo(firstUrl);

        String secondUrl = uploadAvatar(token);
        UserProfileResponse me = getMe(token);

        assertThat(secondUrl)
                .as("each upload is stored under a fresh unique key, so the two URLs must differ — "
                        + "otherwise this test could not tell a stale read from a fresh one")
                .isNotEqualTo(firstUrl)
                .startsWith(CDN + "avatars/" + userId + "/");
        assertThat(me.avatarUrl())
                .as("GET /users/me straight after a replace must show the NEW url; the old url %s here "
                        + "means the replace path served a stale user-profile entry", firstUrl)
                .isEqualTo(secondUrl);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Seeds an email-verified user of {@code role} (a fixed test constant, never input). Salon staff
     * roles are attached to a real salon, as invited staff are in production.
     */
    private UUID insertUserWithRole(String email, String role) {
        UUID salonId = null;
        if (SALON_STAFF_ROLES.contains(role)) {
            UUID ownerId = insertSalonOwner("p344-owner-" + System.nanoTime() + "@beautica.test");
            salonId = insertSalon(ownerId, "P344 Salon " + System.nanoTime());
        }
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, salon_id) "
                        + "VALUES (?, ?, ?, ?, true, true, ?)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    private String uploadAvatar(String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                AVATAR_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).as("avatar upload body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AvatarResponse>>() {})
                .data().avatarUrl();
    }

    private UserProfileResponse getMe(String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                ME_URL, HttpMethod.GET, new HttpEntity<>(bearer(token)), String.class);
        assertThat(resp.getStatusCode()).as("GET /users/me body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<UserProfileResponse>>() {})
                .data();
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
