package com.beautica.media;

import com.beautica.config.TestSecurityConfig;
import com.beautica.media.service.CloudflareCachePurger;
import com.beautica.media.service.R2StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.beautica.support.R2DeleteLedger.purgedKeys;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * End-to-end (real HTTP -> controller -> service -> Postgres) proof that removing or replacing a photo
 * removes exactly the right R2 object, and never a foreign one. R2 is a {@code @MockBean} whose invocations
 * are the ledger of every upload/delete; the test profile runs {@code blobPurgeExecutor} synchronously, so
 * after-commit purges are visible as soon as the HTTP response returns.
 */
@Import(TestSecurityConfig.class)
@DisplayName("R2 blob purge — end-to-end HTTP (portfolio delete, avatar lifecycle, salon delete)")
class R2BlobPurgeEndToEndIT extends AbstractMediaIntegrationTest {

    private static final String AVATAR_URL = "/api/v1/media/avatar";
    /** Phase 362: salon portfolio writes are salon-scoped ({@code /media/portfolio} is INDEPENDENT_MASTER only). */
    private static String salonPortfolioUrl(UUID salonId) {
        return "/api/v1/salons/" + salonId + "/portfolio";
    }
    private static final String CDN = "https://cdn.example/";

    /** Phase 417 — Cloudflare API stand-in for the edge-cache purge. */
    private static final String CF_ZONE = "0123456789abcdef0123456789abcdef";
    private static final String CF_TOKEN = "it-purge-token";
    private static final String CF_PURGE_PATH = "/client/v4/zones/" + CF_ZONE + "/purge_cache";
    private static WireMockServer cloudflare;

    @BeforeAll
    static void startCloudflare() {
        cloudflare = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        cloudflare.start();
    }

    @AfterAll
    static void stopCloudflare() {
        cloudflare.stop();
    }

    @DynamicPropertySource
    static void cloudflareCdn(DynamicPropertyRegistry registry) {
        registry.add("app.cloudflare-cdn.api-base-url", () -> "http://localhost:" + cloudflare.port() + "/client/v4");
        registry.add("app.cloudflare-cdn.allow-custom-api-base-url", () -> "true");
        registry.add("app.cloudflare-cdn.zone-id", () -> CF_ZONE);
        registry.add("app.cloudflare-cdn.purge-token", () -> CF_TOKEN);
    }

    @Autowired private CloudflareCachePurger cloudflareCachePurger;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private R2StorageService r2;

    @Override protected TestRestTemplate restTemplate() { return restTemplate; }
    @Override protected ObjectMapper objectMapper() { return objectMapper; }
    @Override protected PasswordEncoder passwordEncoder() { return passwordEncoder; }

    @BeforeEach
    void setUpR2() {
        reset(r2);
        when(r2.isEnabled()).thenReturn(true);
        when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> CDN + inv.getArgument(0));
        when(r2.extractKeyFromPublicUrl(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            return url.startsWith(CDN) ? Optional.of(url.substring(CDN.length())) : Optional.empty();
        });
        doNothing().when(r2).uploadFile(anyString(), any(), anyLong(), anyString());
        doNothing().when(r2).deleteFile(anyString());
        when(r2.deleteFiles(any())).thenReturn(Set.of());
    }

    @AfterEach
    void dropFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_fail_avatar_clear ON users");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_avatar_clear()");
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_fail_media_delete ON media_files");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_media_delete()");
    }

    // ── portfolio delete ────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /salons/{id}/portfolio/{mediaId} — uploaded photo: 204, row gone, exactly its own key deleted in R2")
    void should_deleteRowAndExactlyItsBlob_when_ownerDeletesUploadedPortfolioPhoto() throws Exception {
        Owner o = owner("pf-own");
        ResponseEntity<String> up = restTemplate.exchange(salonPortfolioUrl(o.salonId()), HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(o.token())), String.class);
        assertThat(up.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID mediaId = jdbcTemplate.queryForObject(
                "SELECT id FROM media_files WHERE entity_id = ?", UUID.class, o.salonId());
        String key = jdbcTemplate.queryForObject(
                "SELECT r2_key FROM media_files WHERE id = ?", String.class, mediaId);
        clearInvocations(r2);

        ResponseEntity<Void> resp = deletePortfolio(o.salonId(), mediaId, o.token());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mediaRowCount(mediaId)).as("media_files row removed").isZero();
        assertThat(key).as("server-built key sits under the salon's own portfolio root")
                .startsWith("portfolio/salons/" + o.salonId() + "/");
        assertThat(purgedKeys(r2)).containsExactly(key);
    }

    @Test
    @DisplayName("DELETE /salons/{id}/portfolio/{mediaId} — row whose key sits under ANOTHER salon's prefix: 204, row gone, R2 untouched")
    void should_deleteRowButNotTouchR2_when_portfolioKeyHasForeignPrefix() throws Exception {
        Owner o = owner("pf-foreign");
        Owner victim = owner("pf-victim");
        String victimKey = "portfolio/salons/" + victim.salonId() + "/victim.jpg";
        UUID mediaId = insertPortfolioRow(o.ownerId(), o.salonId(), victimKey);

        ResponseEntity<Void> resp = deletePortfolio(o.salonId(), mediaId, o.token());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(mediaRowCount(mediaId)).as("row still removed even though R2 was skipped").isZero();
        assertThat(purgedKeys(r2)).as("another salon's object must never be deleted").isEmpty();
    }

    @Test
    @DisplayName("DELETE /salons/{id}/portfolio/{mediaId} — row whose key is another user's AVATAR: R2 untouched")
    void should_notDeleteAvatarObject_when_portfolioRowPointsAtAnAvatarKey() throws Exception {
        Owner o = owner("pf-avatar");
        String avatarKey = "avatars/" + UUID.randomUUID() + "/a.jpg";
        UUID mediaId = insertPortfolioRow(o.ownerId(), o.salonId(), avatarKey);

        ResponseEntity<Void> resp = deletePortfolio(o.salonId(), mediaId, o.token());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(purgedKeys(r2)).isEmpty();
    }

    @Test
    @DisplayName("DELETE /salons/{id}/portfolio/{mediaId} — R2 delete runs only AFTER the row delete has committed")
    void should_purgeBlobOnlyAfterRowDeleteCommitted_when_portfolioPhotoDeleted() throws Exception {
        Owner o = owner("pf-order");
        String key = "portfolio/salons/" + o.salonId() + "/order.jpg";
        UUID mediaId = insertPortfolioRow(o.ownerId(), o.salonId(), key);
        List<Integer> rowCountWhenPurged = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            rowCountWhenPurged.add(mediaRowCount(mediaId));
            return Set.of();
        }).when(r2).deleteFiles(any());

        ResponseEntity<Void> resp = deletePortfolio(o.salonId(), mediaId, o.token());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(purgedKeys(r2)).containsExactly(key);
        assertThat(rowCountWhenPurged).as("row already gone (committed) when the blob was deleted")
                .containsExactly(0);
    }

    @Test
    @DisplayName("DELETE /salons/{id}/portfolio/{mediaId} — row delete fails (rollback): non-2xx, row intact, NOTHING deleted in R2")
    void should_keepRowAndBlob_when_portfolioRowDeleteRollsBack() throws Exception {
        Owner o = owner("pf-rollback");
        String key = "portfolio/salons/" + o.salonId() + "/keep.jpg";
        UUID mediaId = insertPortfolioRow(o.ownerId(), o.salonId(), key);
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_media_delete() RETURNS trigger AS $$
                BEGIN
                  RAISE EXCEPTION 'forced media row delete failure';
                END $$ LANGUAGE plpgsql""");
        jdbcTemplate.execute("CREATE TRIGGER trg_fail_media_delete BEFORE DELETE ON media_files "
                + "FOR EACH ROW EXECUTE FUNCTION fail_media_delete()");

        ResponseEntity<Void> resp = deletePortfolio(o.salonId(), mediaId, o.token());

        assertThat(resp.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(mediaRowCount(mediaId)).as("rolled-back delete leaves the row").isEqualTo(1);
        assertThat(purgedKeys(r2)).as("a rolled-back row delete must never purge the live blob").isEmpty();
    }

    @Test
    @DisplayName("DELETE /salons/{id}/portfolio/{mediaId} — phase 417: after commit, ONE Cloudflare purge_cache call "
            + "carries exactly the deleted object's public URL")
    void should_purgeDeletedObjectUrlFromEdgeOnce_when_portfolioPhotoDeletedAfterCommit() throws Exception {
        Owner o = owner("pf-cdn");
        String key = "portfolio/salons/" + o.salonId() + "/cdn.jpg";
        UUID mediaId = insertPortfolioRow(o.ownerId(), o.salonId(), key);
        R2StorageService realR2 = realR2WithCloudflareOk();
        List<Integer> rowCountWhenPurged = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            rowCountWhenPurged.add(mediaRowCount(mediaId));
            return realR2.deleteFiles(inv.getArgument(0));
        }).when(r2).deleteFiles(any());

        ResponseEntity<Void> resp = deletePortfolio(o.salonId(), mediaId, o.token());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowCountWhenPurged).as("purge ran after the row delete committed").containsExactly(0);
        cloudflare.verify(1, postRequestedFor(urlEqualTo(CF_PURGE_PATH))
                .withHeader("Authorization", equalTo("Bearer " + CF_TOKEN))
                .withRequestBody(equalToJson("{\"files\":[\"" + CDN + key + "\"]}")));
    }

    // ── avatar ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST /media/avatar twice — exactly one object survives: uploaded = {k1, k2}, deleted = {k1}, DB = k2")
    void should_leaveExactlyOneLiveObject_when_avatarUploadedTwice() throws Exception {
        String email = "av-twice-" + System.nanoTime() + "@beautica.test";
        UUID userId = insertClient(email);
        String token = loginAndGetToken(email);

        assertThat(postAvatar(token).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(postAvatar(token).getStatusCode()).isEqualTo(HttpStatus.OK);

        List<String> uploaded = uploadedKeys();
        String live = avatarKey(userId);
        assertThat(uploaded).hasSize(2).doesNotHaveDuplicates().contains(live);
        List<String> survivors = new ArrayList<>(uploaded);
        survivors.removeAll(purgedKeys(r2));
        assertThat(survivors).as("R2 objects left after two uploads").containsExactly(live);
        assertThat(purgedKeys(r2)).containsExactly(uploaded.get(0));
    }

    @Test
    @DisplayName("DELETE /media/avatar — keyed avatar: 204, both pointers null, exactly that key deleted; repeat is a no-op")
    void should_clearPointersAndDeleteBlobOnce_when_avatarDeletedTwice() throws Exception {
        String email = "av-del-" + System.nanoTime() + "@beautica.test";
        UUID userId = insertClient(email);
        String token = loginAndGetToken(email);
        assertThat(postAvatar(token).getStatusCode()).isEqualTo(HttpStatus.OK);
        String key = avatarKey(userId);
        clearInvocations(r2);

        ResponseEntity<Void> first = deleteAvatar(token);
        ResponseEntity<Void> second = deleteAvatar(token);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(avatarKey(userId)).isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT avatar_url FROM users WHERE id = ?", String.class, userId))
                .isNull();
        assertThat(purgedKeys(r2)).as("deleted once, and only on the first DELETE").containsExactly(key);
    }

    @Test
    @DisplayName("DELETE /media/avatar — DB clear fails (rollback): non-2xx, pointers intact, NOTHING deleted in R2")
    void should_keepPointersAndBlob_when_avatarClearRollsBack() throws Exception {
        String email = "av-rollback-" + System.nanoTime() + "@beautica.test";
        UUID userId = insertClient(email);
        String token = loginAndGetToken(email);
        assertThat(postAvatar(token).getStatusCode()).isEqualTo(HttpStatus.OK);
        String key = avatarKey(userId);
        clearInvocations(r2);
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_avatar_clear() RETURNS trigger AS $$
                BEGIN
                  IF OLD.avatar_r2_key IS NOT NULL AND NEW.avatar_r2_key IS NULL THEN
                    RAISE EXCEPTION 'forced avatar clear failure';
                  END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql""");
        jdbcTemplate.execute("CREATE TRIGGER trg_fail_avatar_clear BEFORE UPDATE ON users "
                + "FOR EACH ROW EXECUTE FUNCTION fail_avatar_clear()");

        ResponseEntity<Void> resp = deleteAvatar(token);

        assertThat(resp.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(avatarKey(userId)).as("rolled-back clear leaves the pointer").isEqualTo(key);
        assertThat(purgedKeys(r2)).as("a rolled-back write must never purge the live blob").isEmpty();
    }

    @Test
    @DisplayName("DELETE /users/me — client with an uploaded (keyed) avatar: 204, exactly that avatar object deleted")
    void should_purgeKeyedAvatar_when_clientSelfDeletes() throws Exception {
        String email = "av-selfdel-" + System.nanoTime() + "@beautica.test";
        UUID userId = insertClient(email);
        String token = loginAndGetToken(email);
        assertThat(postAvatar(token).getStatusCode()).isEqualTo(HttpStatus.OK);
        String key = avatarKey(userId);
        clearInvocations(r2);

        ResponseEntity<Void> resp = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, userId))
                .isZero();
        assertThat(purgedKeys(r2)).containsExactly(key);
    }

    // ── salon delete ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /salons/{id} — logo, cover and portfolio objects under the salon's own roots are deleted; "
            + "another salon's logo the row points at is NOT")
    void should_purgeOwnSalonImageryOnly_when_salonDeletedThroughApi() throws Exception {
        Owner o = owner("salon-del");
        Owner other = owner("salon-other");
        String logoKey = "salons/" + o.salonId() + "/logo/l.jpg";
        String foreignCoverKey = "salons/" + other.salonId() + "/cover/c.jpg";
        jdbcTemplate.update("UPDATE salons SET avatar_url = ?, cover_image_url = ? WHERE id = ?",
                CDN + logoKey, CDN + foreignCoverKey, o.salonId());
        String portfolioKey = "portfolio/salons/" + o.salonId() + "/p.jpg";
        insertPortfolioRow(o.ownerId(), o.salonId(), portfolioKey);

        ResponseEntity<Void> resp = restTemplate.exchange("/api/v1/salons/" + o.salonId(), HttpMethod.DELETE,
                new HttpEntity<>(bearer(o.token())), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(purgedKeys(r2)).containsExactlyInAnyOrder(logoKey, portfolioKey)
                .doesNotContain(foreignCoverKey);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM media_files WHERE entity_id = ?", Integer.class, o.salonId())).isZero();
    }

    // ── phase 417: edge purge on account / salon deletion ──────────────────────

    @Test
    @DisplayName("DELETE /users/me — phase 417: the deleted account's avatar URL is purged from the Cloudflare edge, "
            + "and nothing else")
    void should_purgeAvatarUrlFromEdge_when_clientDeletesAccount() throws Exception {
        String email = "cdn-selfdel-" + System.nanoTime() + "@beautica.test";
        UUID userId = insertClient(email);
        String token = loginAndGetToken(email);
        assertThat(postAvatar(token).getStatusCode()).isEqualTo(HttpStatus.OK);
        String key = avatarKey(userId);
        R2StorageService realR2 = realR2WithCloudflareOk();
        doAnswer(inv -> realR2.deleteFiles(inv.getArgument(0))).when(r2).deleteFiles(any());

        ResponseEntity<Void> resp = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(key).as("fixture: the upload stored a keyed avatar").isNotNull();
        assertThat(edgePurgedUrls()).as("Cloudflare purge_cache files across all calls")
                .containsExactly(CDN + key);
    }

    @Test
    @DisplayName("DELETE /salons/{id} — phase 417: own logo + portfolio URLs are purged from the Cloudflare edge; "
            + "the foreign cover URL the row points at is NOT")
    void should_purgeOwnSalonImageryUrlsFromEdge_when_salonDeleted() throws Exception {
        Owner o = owner("salon-cdn");
        Owner other = owner("salon-cdn-other");
        String logoKey = "salons/" + o.salonId() + "/logo/l.jpg";
        String foreignCoverKey = "salons/" + other.salonId() + "/cover/c.jpg";
        jdbcTemplate.update("UPDATE salons SET avatar_url = ?, cover_image_url = ? WHERE id = ?",
                CDN + logoKey, CDN + foreignCoverKey, o.salonId());
        String portfolioKey = "portfolio/salons/" + o.salonId() + "/p.jpg";
        insertPortfolioRow(o.ownerId(), o.salonId(), portfolioKey);
        R2StorageService realR2 = realR2WithCloudflareOk();
        doAnswer(inv -> realR2.deleteFiles(inv.getArgument(0))).when(r2).deleteFiles(any());

        ResponseEntity<Void> resp = restTemplate.exchange("/api/v1/salons/" + o.salonId(), HttpMethod.DELETE,
                new HttpEntity<>(bearer(o.token())), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(edgePurgedUrls()).as("Cloudflare purge_cache files across all calls")
                .containsExactlyInAnyOrder(CDN + logoKey, CDN + portfolioKey)
                .doesNotContain(CDN + foreignCoverKey);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    /**
     * Resets the Cloudflare stand-in to answer every purge with success and returns a REAL R2StorageService (S3
     * mocked) wired to the Spring-configured purger, so its deleteFiles runs the phase-417 purge hook. Tests route
     * the @MockBean's after-commit deleteFiles into it, keeping the mock's ledger intact.
     */
    private R2StorageService realR2WithCloudflareOk() {
        cloudflare.resetAll();
        cloudflare.stubFor(post(urlEqualTo(CF_PURGE_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("{\"success\":true,\"errors\":[]}")));
        S3Client s3 = mock(S3Client.class);
        when(s3.deleteObjects(any(DeleteObjectsRequest.class))).thenReturn(DeleteObjectsResponse.builder().build());
        return new R2StorageService(Optional.of(s3), "test-bucket", CDN, cloudflareCachePurger);
    }

    /** Every URL sent to Cloudflare purge_cache (with the expected bearer), across all requests, in order. */
    private List<String> edgePurgedUrls() throws Exception {
        List<String> urls = new ArrayList<>();
        for (LoggedRequest req : cloudflare.findAll(postRequestedFor(urlEqualTo(CF_PURGE_PATH))
                .withHeader("Authorization", equalTo("Bearer " + CF_TOKEN)))) {
            objectMapper.readTree(req.getBodyAsString()).get("files").forEach(f -> urls.add(f.asText()));
        }
        return urls;
    }

    private record Owner(UUID ownerId, UUID salonId, String token) {}

    private Owner owner(String tag) throws Exception {
        String email = tag + "-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = insertSalonOwner(email);
        UUID salonId = insertSalon(ownerId, "Salon " + tag + " " + System.nanoTime());
        return new Owner(ownerId, salonId, loginAndGetToken(email));
    }

    private UUID insertPortfolioRow(UUID uploaderId, UUID salonId, String key) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, "
                        + "r2_key, r2_url, created_at, updated_at) VALUES (?, ?, 'SALON', ?, 'PORTFOLIO', ?, ?, NOW(), NOW())",
                id, uploaderId, salonId, key, CDN + key);
        return id;
    }

    private int mediaRowCount(UUID mediaId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM media_files WHERE id = ?", Integer.class, mediaId);
    }

    private String avatarKey(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT avatar_r2_key FROM users WHERE id = ?", String.class, userId);
    }

    private ResponseEntity<String> postAvatar(String token) {
        return restTemplate.exchange(AVATAR_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)), String.class);
    }

    private ResponseEntity<Void> deleteAvatar(String token) {
        return restTemplate.exchange(AVATAR_URL, HttpMethod.DELETE, new HttpEntity<>(bearer(token)), Void.class);
    }

    private ResponseEntity<Void> deletePortfolio(UUID salonId, UUID mediaId, String token) {
        return restTemplate.exchange(salonPortfolioUrl(salonId) + "/" + mediaId, HttpMethod.DELETE,
                new HttpEntity<>(bearer(token)), Void.class);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    private List<String> uploadedKeys() {
        List<String> keys = new ArrayList<>();
        mockingDetails(r2).getInvocations().forEach(inv -> {
            if (inv.getMethod().getName().equals("uploadFile")) {
                keys.add(inv.getArgument(0));
            }
        });
        return keys;
    }
}
