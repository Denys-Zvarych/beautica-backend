package com.beautica.media;

import com.beautica.config.TestSecurityConfig;
import com.beautica.media.entity.EntityType;
import com.beautica.media.repository.MediaRepository;
import com.beautica.media.service.MediaService;
import com.beautica.media.service.R2StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 362 — salon portfolio writes against the real stack (HTTP → security → service → Postgres).
 *
 * <p>Locked rule (2026-10-12): salon portfolio photos belong to the SALON. Any manager of the salon (owner or
 * the bound SALON_ADMIN) may add or delete any of its photos — the uploader is never compared. The salon id is
 * explicit in the path, so a multi-salon owner targets salon #2 directly (the former oldest-salon bug).
 *
 * <p>Same context as {@link MediaIntegrationTest} ({@code @MockBean R2StorageService} + {@code @SpyBean MediaRepository}) — no new context.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Salon portfolio — owner + bound admin manage, public listing, 30-photo cap (Phase 362)")
class SalonPortfolioIT extends AbstractMediaIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private R2StorageService r2;
    // G4: spied so the 503 path can assert the reference probe is skipped. Same mock/spy set as
    // MediaIntegrationTest, so this joins that cached context rather than creating a new one.
    @SpyBean private MediaRepository mediaRepository;

    @Override protected TestRestTemplate restTemplate() { return restTemplate; }
    @Override protected ObjectMapper objectMapper() { return objectMapper; }
    @Override protected PasswordEncoder passwordEncoder() { return passwordEncoder; }

    private UUID ownerId;
    private String ownerEmail;
    private UUID salonId;

    @BeforeEach
    void seedOwnerSalonAndStubR2() {
        reset(r2);
        when(r2.isEnabled()).thenReturn(true);
        when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0));
        doNothing().when(r2).uploadFile(anyString(), any(), anyLong(), anyString());
        doNothing().when(r2).deleteFile(anyString());

        ownerEmail = "p362-it-owner-" + System.nanoTime() + "@beautica.test";
        ownerId = insertSalonOwner(ownerEmail);
        salonId = insertSalon(ownerId, "P362 Salon One");
    }

    @Test
    @DisplayName("multi-salon owner uploads to salon #2 → 201, row entity_id = salon #2, key under salon #2's root; "
            + "salon #1 untouched")
    void should_storeRowOnSecondSalon_when_multiSalonOwnerTargetsIt() throws Exception {
        // Arrange — salon #2 is NEWER than salon #1 (the removed branch always picked the oldest)
        UUID salon2 = insertSalon(ownerId, "P362 Salon Two");
        String token = loginAndGetToken(ownerEmail);

        // Act
        ResponseEntity<String> resp = upload(salon2, token);

        // Assert
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        UUID mediaId = UUID.fromString(data.path("id").asText());
        assertThat(data.path("entityType").asText()).isEqualTo("SALON");
        assertThat(data.path("entityId").asText()).as("response entityId").isEqualTo(salon2.toString());
        assertThat(data.path("mediaType").asText()).isEqualTo("PORTFOLIO");
        var row = jdbcTemplate.queryForMap(
                "SELECT entity_type, entity_id, media_type, uploader_id, r2_key, r2_url FROM media_files WHERE id = ?",
                mediaId);
        assertThat(row.get("entity_type")).isEqualTo("SALON");
        assertThat(row.get("entity_id")).as("persisted entity_id").isEqualTo(salon2);
        assertThat(row.get("media_type")).isEqualTo("PORTFOLIO");
        assertThat(row.get("uploader_id")).isEqualTo(ownerId);
        assertThat((String) row.get("r2_key")).startsWith("portfolio/salons/" + salon2 + "/");
        assertThat(row.get("r2_url")).isEqualTo("https://cdn.example/" + row.get("r2_key"));
        assertThat(data.path("url").asText()).isEqualTo(row.get("r2_url"));
        assertThat(portfolioCount(salonId)).as("salon #1 receives nothing").isZero();
        verify(r2).uploadFile(eq((String) row.get("r2_key")), any(), anyLong(), eq("image/jpeg"));
    }

    @Test
    @DisplayName("bound SALON_ADMIN uploads → 201 (row on the salon, uploader = admin), then deletes an "
            + "OWNER-uploaded photo → 204, row gone")
    void should_letBoundAdminUploadAndDeleteOwnersPhoto_when_adminManagesSalon() throws Exception {
        // Arrange
        String ownerToken = loginAndGetToken(ownerEmail);
        UUID ownerPhoto = mediaId(upload(salonId, ownerToken));
        String adminEmail = "p362-it-admin-" + System.nanoTime() + "@beautica.test";
        UUID adminId = insertUser(adminEmail, "SALON_ADMIN", salonId);
        String adminToken = loginAndGetToken(adminEmail);

        // Act
        ResponseEntity<String> adminUpload = upload(salonId, adminToken);
        ResponseEntity<String> adminDelete = delete(salonId, ownerPhoto, adminToken);

        // Assert
        assertThat(adminUpload.getStatusCode()).as("body=%s", adminUpload.getBody()).isEqualTo(HttpStatus.CREATED);
        UUID adminPhoto = mediaId(adminUpload);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT uploader_id FROM media_files WHERE id = ?", UUID.class, adminPhoto)).isEqualTo(adminId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT entity_id FROM media_files WHERE id = ?", UUID.class, adminPhoto)).isEqualTo(salonId);
        assertThat(adminDelete.getStatusCode()).as("admin deletes owner's photo, body=%s", adminDelete.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowExists(ownerPhoto)).as("owner-uploaded row removed").isFalse();
        assertThat(rowExists(adminPhoto)).as("admin's own upload untouched").isTrue();
    }

    @Test
    @DisplayName("OWNER deletes an ADMIN-uploaded photo → 204, row gone (uploader never compared)")
    void should_letOwnerDeleteAdminsPhoto_when_ownerManagesSalon() throws Exception {
        // Arrange
        String adminEmail = "p362-it-admin2-" + System.nanoTime() + "@beautica.test";
        insertUser(adminEmail, "SALON_ADMIN", salonId);
        UUID adminPhoto = mediaId(upload(salonId, loginAndGetToken(adminEmail)));
        String ownerToken = loginAndGetToken(ownerEmail);

        // Act
        ResponseEntity<String> resp = delete(salonId, adminPhoto, ownerToken);

        // Assert
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowExists(adminPhoto)).isFalse();
        assertThat(portfolioCount(salonId)).isZero();
    }

    @Test
    @DisplayName("public GET /salons/{id}/portfolio lists newest first and reflects upload then delete "
            + "(portfolio cache evicted on both writes)")
    void should_reflectUploadAndDeleteNewestFirst_when_publicListingCached() throws Exception {
        // Arrange — an older seeded photo, then warm the cache with it
        UUID older = insertPortfolioRow(salonId, "NOW() - INTERVAL '1 hour'");
        String ownerToken = loginAndGetToken(ownerEmail);
        assertThat(publicListing(salonId)).as("cache warmed with the seeded row").containsExactly(older);

        // Act — upload (must evict) and re-read
        UUID newer = mediaId(upload(salonId, ownerToken));
        List<UUID> afterUpload = publicListing(salonId);
        // Act — delete the older row (must evict) and re-read
        assertThat(delete(salonId, older, ownerToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        List<UUID> afterDelete = publicListing(salonId);

        // Assert
        assertThat(afterUpload).as("newest first, upload visible despite the warm cache")
                .containsExactly(newer, older);
        assertThat(afterDelete).as("delete visible despite the warm cache").containsExactly(newer);
    }

    @Test
    @DisplayName("cap boundary: 29 photos → upload 201 (30th); 30 photos → upload 409 PORTFOLIO_LIMIT_REACHED, "
            + "count stays 30, the 31st never reaches R2 (F2 pre-check)")
    void should_acceptThirtiethAndReject31st_when_salonPortfolioAtCap() throws Exception {
        // Arrange
        for (int i = 0; i < MediaService.MAX_PORTFOLIO_PHOTOS - 1; i++) {
            insertPortfolioRow(salonId, "NOW()");
        }
        String ownerToken = loginAndGetToken(ownerEmail);

        // Act
        ResponseEntity<String> thirtieth = upload(salonId, ownerToken);
        ResponseEntity<String> thirtyFirst = upload(salonId, ownerToken);

        // Assert
        assertThat(thirtieth.getStatusCode()).as("the 30th photo fits, body=%s", thirtieth.getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(thirtyFirst.getStatusCode()).as("the 31st photo is over the cap, body=%s", thirtyFirst.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        // Audit F6b: the cap carries a machine-readable data.code — mobile branches on it, never on the text.
        JsonNode rejected = objectMapper.readTree(thirtyFirst.getBody());
        assertThat(rejected.path("success").asBoolean(true)).isFalse();
        assertThat(rejected.path("data").path("code").asText()).isEqualTo("PORTFOLIO_LIMIT_REACHED");
        assertThat(rejected.path("message").asText())
                .as("the cap's own copy reaches the wire, not the genericised CONFLICT message")
                .isEqualTo("Portfolio photo limit reached");
        assertThat(portfolioCount(salonId)).isEqualTo(MediaService.MAX_PORTFOLIO_PHOTOS);
        // Audit F2: the unlocked pre-check rejects before the R2 PUT — only the 30th was uploaded, nothing deleted.
        verify(r2, times(1)).uploadFile(anyString(), any(), anyLong(), anyString());
        verify(r2, never()).deleteFile(anyString());
    }

    @Test
    @DisplayName("F6a: another transaction holds the salon row lock past the 3s lock_timeout → 503 + Retry-After "
            + "(never the cap's 409), no row, the uploaded blob discarded directly — no reference probe (G4)")
    void should_return503WithRetryAfter_when_salonRowLockHeldElsewhere() throws Exception {
        // Arrange
        String ownerToken = loginAndGetToken(ownerEmail);
        ResponseEntity<String> resp;

        // Act — a raw connection holds the same FOR NO KEY UPDATE row lock the upload needs.
        try (java.sql.Connection holder = jdbcTemplate.getDataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (var st = holder.prepareStatement("SELECT id FROM salons WHERE id = ? FOR NO KEY UPDATE")) {
                st.setObject(1, salonId);
                try (var rs = st.executeQuery()) {
                    assertThat(rs.next()).as("the holder really locked the salon row").isTrue();
                }
            }
            try {
                resp = upload(salonId, ownerToken);
            } finally {
                holder.rollback();
            }
        }

        // Assert
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        JsonNode busy = objectMapper.readTree(resp.getBody());
        assertThat(busy.path("success").asBoolean(true)).isFalse();
        assertThat(busy.path("message").asText())
                .as("the allow-listed busy copy, never the cap's or the generic conflict message")
                .isEqualTo(com.beautica.common.exception.ServiceUnavailableMessages.PORTFOLIO_BUSY);
        assertThat(busy.path("data").path("code").isMissingNode())
                .as("no PORTFOLIO_LIMIT_REACHED code on a lock timeout, body=%s", resp.getBody()).isTrue();
        assertThat(portfolioCount(salonId)).isZero();
        org.mockito.ArgumentCaptor<String> uploaded = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(r2).uploadFile(uploaded.capture(), any(), anyLong(), anyString());
        verify(r2).deleteFile(uploaded.getValue());
        // Positive control: the spy records this path's repository calls, so the never() below is not vacuous.
        verify(mediaRepository).countPortfolioOfActiveSalon(salonId);
        verify(mediaRepository, never()).existsByEntityTypeAndEntityIdAndR2Key(any(), any(), anyString());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private ResponseEntity<String> upload(UUID salon, String token) {
        return restTemplate.exchange("/api/v1/salons/" + salon + "/portfolio", HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)), String.class);
    }

    private ResponseEntity<String> delete(UUID salon, UUID mediaId, String token) {
        return restTemplate.exchange("/api/v1/salons/" + salon + "/portfolio/" + mediaId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(token)), String.class);
    }

    private UUID mediaId(ResponseEntity<String> created) throws Exception {
        assertThat(created.getStatusCode()).as("body=%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(created.getBody()).path("data").path("id").asText());
    }

    private List<UUID> publicListing(UUID salon) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/salons/" + salon + "/portfolio",
                HttpMethod.GET, new HttpEntity<>(new HttpHeaders()), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<UUID> ids = new ArrayList<>();
        for (JsonNode item : objectMapper.readTree(resp.getBody()).path("data").path("content")) {
            assertThat(item.path("entityType").asText()).isEqualTo(EntityType.SALON.name());
            assertThat(item.path("entityId").asText()).isEqualTo(salon.toString());
            ids.add(UUID.fromString(item.path("id").asText()));
        }
        return ids;
    }

    /** Raw row under the salon's own portfolio root; {@code createdAtSql} is a trusted SQL literal. */
    private UUID insertPortfolioRow(UUID salon, String createdAtSql) {
        UUID id = UUID.randomUUID();
        String key = "portfolio/salons/" + salon + "/seed-" + id + ".jpg";
        jdbcTemplate.update("INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, r2_key, "
                        + "r2_url, created_at, updated_at) VALUES (?, ?, 'SALON', ?, 'PORTFOLIO', ?, ?, "
                        + createdAtSql + ", " + createdAtSql + ")",
                id, ownerId, salon, key, "https://cdn.example/" + key);
        return id;
    }

    private int portfolioCount(UUID salon) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_files WHERE entity_type = 'SALON' AND entity_id = ?", Integer.class, salon);
    }

    private boolean rowExists(UUID mediaId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_files WHERE id = ?", Integer.class, mediaId) == 1;
    }
}
