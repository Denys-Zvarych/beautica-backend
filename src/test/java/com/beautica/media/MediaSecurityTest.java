package com.beautica.media;

import com.beautica.config.TestSecurityConfig;
import com.beautica.media.entity.EntityType;
import com.beautica.media.service.R2StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Cross-user IDOR + content-type spoofing security regression for the media endpoints.
 *
 * <p><b>SEC-1 design note.</b> {@link com.beautica.media.controller.MediaController}
 * intentionally exposes no request-body or path-variable field that lets a caller
 * name a target user/salon/master. {@link com.beautica.media.service.MediaService}
 * resolves the owning entity server-side from the authenticated principal.
 * Phase 362 moved salon portfolio writes to the salon-scoped
 * {@code /salons/{salonId}/portfolio} routes, which DO name the salon and are
 * therefore guarded by {@code canManageSalon} + {@code enforceCanManageSalon}
 * (tests #12–#14 pin that IDOR surface); {@code /media/portfolio} is
 * INDEPENDENT_MASTER only.
 * <ul>
 *   <li>#11 — a CLIENT can only update its own avatar (no target field exists).</li>
 *   <li>#12 — owner A cannot upload to salon B's portfolio; a SALON_OWNER on
 *       {@code /media/portfolio} is refused (no salon write path remains there).</li>
 *   <li>#13 — a CLIENT is refused on both portfolio upload routes.</li>
 *   <li>#14 — owner B cannot delete salon A's photo (403 on salon A's path, 404
 *       when the row is addressed through salon B's path).</li>
 * </ul>
 *
 * <p>{@link R2StorageService} is mocked: real R2 calls would otherwise fan out
 * over the network during every test. The mock returns deterministic public URLs
 * built from the supplied key so the controller can finish the round-trip.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Media — IDOR + content-type security regression")
class MediaSecurityTest extends AbstractMediaIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(MediaSecurityTest.class);
    private static final String AVATAR_URL = "/api/v1/media/avatar";
    private static final String PORTFOLIO_URL = "/api/v1/media/portfolio";

    private static String salonPortfolioUrl(UUID salonId) {
        return "/api/v1/salons/" + salonId + "/portfolio";
    }

    /** Multipart part names a caller might try to smuggle a target user through (Phase 344 TC-3). */
    private static final List<String> SMUGGLED_TARGET_FIELDS =
            List.of("userId", "targetUserId", "adminId", "ownerId");

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
    void seedFixtures() {
        when(r2StorageService.isEnabled()).thenReturn(true);
        // Stub R2 so test paths do not fan out to a real bucket. buildPublicUrl
        // returns a deterministic URL so MediaFileResponse.url has a value.
        when(r2StorageService.buildPublicUrl(anyString()))
                .thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0));
        doNothing().when(r2StorageService).uploadFile(anyString(), any(), anyLong(), anyString());
        doNothing().when(r2StorageService).deleteFile(anyString());
    }

    // ── #11 — SEC-1 contract: CLIENT cannot target another user's avatar ─────

    @Test
    @DisplayName("POST /media/avatar — persisted avatar key is bound to the authenticated user (SEC-1 design)")
    void should_scopeAvatarKey_when_clientUploads() throws Exception {
        // SEC-1: the controller derives userId from the JWT — there is no target
        // userId parameter that a caller could spoof. The only IDOR-equivalent
        // check here is to assert the persisted avatar key reflects THE caller,
        // not anyone else. Two clients upload avatars; their stored keys must
        // not collide.
        String emailA = "sec-avatar-a-" + System.nanoTime() + "@beautica.test";
        UUID userAId = insertClient(emailA);
        String tokenA = loginAndGetToken(emailA);

        log.debug("Act: POST {} as user A — persisted avatar must be bound to user A only", AVATAR_URL);
        ResponseEntity<String> resp = restTemplate.exchange(
                AVATAR_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(tokenA)),
                String.class);

        assertThat(resp.getStatusCode())
                .as("happy-path upload as user A must succeed; the SEC-1 contract is enforced by the absence of a target-user field, not by a 403")
                .isEqualTo(HttpStatus.OK);

        String persistedKey = jdbcTemplate.queryForObject(
                "SELECT avatar_r2_key FROM users WHERE id = ?", String.class, userAId);
        assertThat(persistedKey)
                .as("avatar key must be scoped to user A (path prefix '/avatars/<userA>/...')")
                .isNotNull()
                .contains("avatars/" + userAId + "/");
    }

    // ── Phase 344 TC-2 — a smuggled target id never redirects an avatar write ─

    @Test
    @DisplayName("POST /media/avatar?userId=B with a userId=B part — only the caller A's avatar changes (Phase 344 TC-2)")
    void should_writeOnlyCallerAvatar_when_uploadSmugglesOtherUserId() throws Exception {
        String emailA = "p344-a-" + System.nanoTime() + "@beautica.test";
        UUID userAId = insertClient(emailA);
        UUID userBId = insertClient("p344-b-" + System.nanoTime() + "@beautica.test");
        String bUrlBefore = seedAvatar(userBId);
        String tokenA = loginAndGetToken(emailA);
        MultiValueMap<String, Object> body = jpegMultipartBody();
        body.add("userId", userBId.toString());

        ResponseEntity<String> resp = restTemplate.exchange(
                AVATAR_URL + "?userId=" + userBId, HttpMethod.POST,
                new HttpEntity<>(body, bearerMultipartHeaders(tokenA)), String.class);

        assertThat(resp.getStatusCode())
                .as("the smuggled userId is ignored, not rejected — the JWT alone names the target")
                .isEqualTo(HttpStatus.OK);
        assertThat(avatarUrlOf(userAId))
                .as("caller A's own avatar must be written under avatars/<A>/")
                .startsWith("https://cdn.example/avatars/" + userAId + "/");
        assertAvatarUntouched(userBId, bUrlBefore);
    }

    @Test
    @DisplayName("DELETE /media/avatar?userId=B — only the caller A's avatar is cleared (Phase 344 TC-2)")
    void should_clearOnlyCallerAvatar_when_deleteSmugglesOtherUserId() throws Exception {
        String emailA = "p344-da-" + System.nanoTime() + "@beautica.test";
        UUID userAId = insertClient(emailA);
        UUID userBId = insertClient("p344-db-" + System.nanoTime() + "@beautica.test");
        seedAvatar(userAId);
        String bUrlBefore = seedAvatar(userBId);
        String tokenA = loginAndGetToken(emailA);

        ResponseEntity<String> resp = restTemplate.exchange(
                AVATAR_URL + "?userId=" + userBId, HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(tokenA)), String.class);

        assertThat(resp.getStatusCode().is2xxSuccessful())
                .as("DELETE must succeed for the caller; got %s", resp.getStatusCode())
                .isTrue();
        assertThat(avatarUrlOf(userAId)).as("caller A's own avatar must be cleared").isNull();
        assertAvatarUntouched(userBId, bUrlBefore);
    }

    // ── Phase 344 TC-3 — an owner cannot set their salon admin's photo ────────

    @Test
    @DisplayName("SALON_OWNER naming their admin in every reachable place — owner's avatar changes, admin's does not (Phase 344 TC-3)")
    void should_writeOnlyOwnerAvatar_when_ownerTargetsAdminEverywhere() throws Exception {
        String ownerEmail = "p344-owner-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = insertSalonOwner(ownerEmail);
        UUID salonId = insertSalon(ownerId, "P344 Salon " + System.nanoTime());
        UUID adminId = insertSalonAdmin("p344-admin-" + System.nanoTime() + "@beautica.test", salonId);
        String adminUrlBefore = seedAvatar(adminId);
        String ownerToken = loginAndGetToken(ownerEmail);
        String target = adminId.toString();
        MultiValueMap<String, Object> body = jpegMultipartBody();
        for (String field : SMUGGLED_TARGET_FIELDS) {
            body.add(field, target);
        }
        HttpHeaders headers = bearerMultipartHeaders(ownerToken);
        headers.add("X-User-Id", target);
        String query = "?userId=" + target + "&targetUserId=" + target + "&adminId=" + target
                + "&salonId=" + salonId;

        ResponseEntity<String> resp = restTemplate.exchange(
                AVATAR_URL + query, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        ResponseEntity<String> pathResp = restTemplate.exchange(
                AVATAR_URL + "/" + target, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(ownerToken)), String.class);

        assertThat(resp.getStatusCode()).as("owner's own upload must succeed").isEqualTo(HttpStatus.OK);
        assertThat(pathResp.getStatusCode().value())
                .as("there is no /media/avatar/{userId} route — a path-addressed write must be a clean "
                        + "404/405, never a 2xx and never a 5xx; got %s body=%s",
                        pathResp.getStatusCode(), pathResp.getBody())
                .isIn(HttpStatus.NOT_FOUND.value(), HttpStatus.METHOD_NOT_ALLOWED.value());
        assertThat(avatarUrlOf(ownerId))
                .as("the owner's own avatar is what changes")
                .startsWith("https://cdn.example/avatars/" + ownerId + "/");
        assertAvatarUntouched(adminId, adminUrlBefore);
    }

    // ── #12 — owner A cannot upload portfolio for salon B ──────────────────────

    @Test
    @DisplayName("POST /salons/{B}/portfolio — 403 when owner A targets salon B; no row, no R2 upload")
    void should_return403_when_ownerAUploadsPortfolioForSalonB() throws Exception {
        String ownerAEmail = "sec-port-owner-a-" + System.nanoTime() + "@beautica.test";
        UUID ownerAId = insertSalonOwner(ownerAEmail);
        insertSalon(ownerAId, "Salon A");
        UUID ownerBId = insertSalonOwner("sec-port-owner-b-" + System.nanoTime() + "@beautica.test");
        UUID salonBId = insertSalon(ownerBId, "Salon B");
        String tokenA = loginAndGetToken(ownerAEmail);

        log.debug("Act: POST {} as owner A — must return 403", salonPortfolioUrl(salonBId));
        ResponseEntity<String> resp = restTemplate.exchange(
                salonPortfolioUrl(salonBId), HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(tokenA)),
                String.class);

        assertThat(resp.getStatusCode())
                .as("owner A must be denied an upload into salon B's portfolio")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(portfolioCount(salonBId)).as("no row lands in salon B").isZero();
        verify(r2StorageService, never()).uploadFile(anyString(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("POST /media/portfolio — 403 for a SALON_OWNER with an active salon (Phase 362 D4: master only)")
    void should_return403_when_salonOwnerUploadsViaMediaPortfolio() throws Exception {
        String ownerEmail = "sec-port-owner-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = insertSalonOwner(ownerEmail);
        UUID salonId = insertSalon(ownerId, "Owner Salon");
        String ownerToken = loginAndGetToken(ownerEmail);

        ResponseEntity<String> resp = restTemplate.exchange(
                PORTFOLIO_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(ownerToken)),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(portfolioCount(salonId)).as("no salon write path remains on /media/portfolio").isZero();
    }

    // ── #13 — CLIENT refused on both portfolio upload routes ───────────────────

    @Test
    @DisplayName("POST /media/portfolio and /salons/{id}/portfolio — 403 when CLIENT attempts a portfolio upload")
    void should_return403_when_unrelatedUserUploadsMasterPortfolio() throws Exception {
        String clientEmail = "sec-port-client-" + System.nanoTime() + "@beautica.test";
        insertClient(clientEmail);
        String clientToken = loginAndGetToken(clientEmail);
        UUID ownerId = insertSalonOwner("sec-port-client-owner-" + System.nanoTime() + "@beautica.test");
        UUID salonId = insertSalon(ownerId, "Client Target Salon");

        for (String url : List.of(PORTFOLIO_URL, salonPortfolioUrl(salonId))) {
            log.debug("Act: POST {} as CLIENT — role guard must reject before controller body runs", url);
            ResponseEntity<String> resp = restTemplate.exchange(
                    url, HttpMethod.POST,
                    new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(clientToken)),
                    String.class);

            assertThat(resp.getStatusCode())
                    .as("CLIENT must be denied portfolio upload on %s", url)
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }
        assertThat(portfolioCount(salonId)).isZero();
    }

    // ── #14 — cross-salon delete IDOR ────────────────────────────────────────

    @Test
    @DisplayName("DELETE /salons/{A}/portfolio/{id} — 403 when owner B deletes salon A's portfolio photo")
    void should_return403_when_userBDeletesUserAPortfolioPhoto() throws Exception {
        // Arrange — salon A has a portfolio photo
        UUID ownerAId = insertSalonOwner("sec-del-a-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = insertSalon(ownerAId, "Salon A");
        UUID mediaAId = insertMediaFile(ownerAId, EntityType.SALON, salonAId);

        // Arrange — owner B (a different salon) tries to delete that row
        String ownerBEmail = "sec-del-b-" + System.nanoTime() + "@beautica.test";
        UUID ownerBId = insertSalonOwner(ownerBEmail);
        UUID salonBId = insertSalon(ownerBId, "Salon B");
        String tokenB = loginAndGetToken(ownerBEmail);

        log.debug("Act: DELETE salon A's media as owner B, via salon A's path and via salon B's path");
        ResponseEntity<String> viaSalonA = restTemplate.exchange(
                salonPortfolioUrl(salonAId) + "/" + mediaAId, HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(tokenB)), String.class);
        ResponseEntity<String> viaSalonB = restTemplate.exchange(
                salonPortfolioUrl(salonBId) + "/" + mediaAId, HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(tokenB)), String.class);
        ResponseEntity<String> viaMediaPath = restTemplate.exchange(
                PORTFOLIO_URL + "/" + mediaAId, HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(tokenB)), String.class);

        assertThat(viaSalonA.getStatusCode())
                .as("owner B cannot manage salon A")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(viaSalonB.getStatusCode())
                .as("salon A's row addressed through salon B's path is not found")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(viaMediaPath.getStatusCode())
                .as("/media/portfolio is INDEPENDENT_MASTER only")
                .isEqualTo(HttpStatus.FORBIDDEN);
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_files WHERE id = ?", Integer.class, mediaAId);
        assertThat(remaining)
                .as("salon A's media row must still exist after the rejected deletes")
                .isEqualTo(1);
    }

    // ── SEC-1 design invariant: no caller-supplied target IDs ────────────────

    /**
     * Reflection-based design invariant guarding the SEC-1 contract. The two
     * authenticated mutations ({@code uploadAvatar}, {@code uploadPortfolioPhoto})
     * must only accept the multipart payload and the principal-derived
     * {@link Authentication} — any UUID/String/request-body parameter would
     * re-introduce the IDOR vector flagged in the Phase 7.1 audit.
     *
     * <p>Tests #11 and #12 assert positive happy-path behavior; this test makes
     * future violations impossible to slip past code review by failing at compile
     * time whenever someone adds a forbidden parameter shape.
     */
    @Test
    @DisplayName("MediaController has no request-body or query-param channel for caller-supplied user/salon/master IDs")
    void should_haveNoTargetUserParameter_in_authenticatedMutations() throws Exception {
        Class<?> controller = Class.forName("com.beautica.media.controller.MediaController");
        Method uploadAvatar = controller.getDeclaredMethod(
                "uploadAvatar", MultipartFile.class, Authentication.class);
        Method uploadPortfolio = controller.getDeclaredMethod(
                "uploadPortfolioPhoto", MultipartFile.class, Authentication.class);

        for (Method m : List.of(uploadAvatar, uploadPortfolio)) {
            for (Parameter p : m.getParameters()) {
                // Allowed: MultipartFile (the file payload) and Authentication (principal-derived).
                if (p.getType().equals(MultipartFile.class) || p.getType().equals(Authentication.class)) {
                    continue;
                }
                // No other parameter should exist. UUID/String/request-body params are
                // forbidden — they would be an IDOR vector.
                org.assertj.core.api.Assertions.fail(
                        "SEC-1 violation: " + m.getName() + " has unexpected parameter "
                                + p.getName() + " of type " + p.getType().getName());
            }
        }
    }

    // ── #15 — content-type spoofing (SVG masquerading as JPEG) ───────────────

    @Test
    @DisplayName("POST /media/avatar — 400 when an SVG body is uploaded with Content-Type image/jpeg")
    void should_return400_when_svgFileSentWithJpegContentType() throws Exception {
        String email = "sec-svg-" + System.nanoTime() + "@beautica.test";
        insertClient(email);
        String token = loginAndGetToken(email);

        byte[] svgBody = "<svg xmlns=\"http://www.w3.org/2000/svg\"></svg>".getBytes(StandardCharsets.UTF_8);
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(svgBody) {
            @Override
            public String getFilename() {
                return "evil.jpg";
            }
        });

        log.debug("Act: POST {} with SVG body declared as image/jpeg — magic-byte check must reject", AVATAR_URL);
        ResponseEntity<String> resp = restTemplate.exchange(
                AVATAR_URL, HttpMethod.POST,
                new HttpEntity<>(body, bearerMultipartHeaders(token)),
                String.class);

        assertThat(resp.getStatusCode())
                .as("magic-byte check must reject SVG masquerading as JPEG")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── seeding helpers (security-test-only) ─────────────────────────────────

    /**
     * Insert a {@code media_files} row directly via JDBC — the SEC IT exercises
     * the delete IDOR path without going through the upload pipeline so the test
     * is independent of any R2 mock state.
     */
    private UUID insertMediaFile(UUID uploaderId, EntityType entityType, UUID entityId) {
        UUID mediaId = UUID.randomUUID();
        String key = "portfolio/test/" + mediaId + ".jpg";
        jdbcTemplate.update(
                """
                INSERT INTO media_files
                  (id, uploader_id, entity_type, entity_id, media_type, r2_key, r2_url, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """,
                mediaId, uploaderId, entityType.name(), entityId,
                com.beautica.media.entity.MediaType.PORTFOLIO.name(),
                key, "https://cdn.example/" + key);
        return mediaId;
    }

    private int portfolioCount(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_files WHERE entity_type = 'SALON' AND entity_id = ?",
                Integer.class, salonId);
    }

    private UUID insertSalonAdmin(String email, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, salon_id) "
                        + "VALUES (?, ?, ?, 'SALON_ADMIN', true, true, ?)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), salonId);
        return id;
    }

    /** Gives {@code userId} an existing avatar (V188-valid pointers) so "untouched" is observable. */
    private String seedAvatar(UUID userId) {
        String key = "avatars/" + userId + "/seed.jpg";
        String url = "https://cdn.example/" + key;
        jdbcTemplate.update("UPDATE users SET avatar_r2_key = ?, avatar_url = ? WHERE id = ?", key, url, userId);
        return url;
    }

    private String avatarUrlOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT avatar_url FROM users WHERE id = ?", String.class, userId);
    }

    private void assertAvatarUntouched(UUID userId, String expectedUrl) {
        assertThat(avatarUrlOf(userId))
                .as("user %s's avatar_url must be untouched by another user's write", userId)
                .isEqualTo(expectedUrl);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT avatar_r2_key FROM users WHERE id = ?", String.class, userId))
                .as("user %s's avatar_r2_key must be untouched by another user's write", userId)
                .isEqualTo("avatars/" + userId + "/seed.jpg");
    }

    private static HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
