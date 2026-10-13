package com.beautica.media;

import com.beautica.config.TestSecurityConfig;
import com.beautica.media.repository.MediaRepository;
import com.beautica.media.service.MediaService;
import com.beautica.media.service.R2StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 362 D4/D5 — the independent-master portfolio ({@code /media/portfolio}) against the real stack: the
 * 30-photo cap (boundary 30th → 201, 31st → 409 PORTFOLIO_LIMIT_REACHED, rejected before any R2 PUT), and the narrowed delete —
 * MASTER rows only (a salon row → 404), authorized by owning entity (another master's row → 404 since audit H3, 2026-10-12: a uniform 404
 * avoids an existence oracle).
 *
 * <p>Same context as {@link MediaIntegrationTest} ({@code @MockBean R2StorageService} + {@code @SpyBean MediaRepository}) — no new context.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Master portfolio — 30-photo cap and MASTER-row-only delete on /media/portfolio (Phase 362)")
class MasterPortfolioIT extends AbstractMediaIntegrationTest {

    private static final String PORTFOLIO_URL = "/api/v1/media/portfolio";

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

    private record MasterFixture(UUID userId, UUID masterId, String token) {}

    private MasterFixture master;

    @BeforeEach
    void seedMasterAndStubR2() throws Exception {
        reset(r2);
        when(r2.isEnabled()).thenReturn(true);
        when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0));
        doNothing().when(r2).uploadFile(anyString(), any(), anyLong(), anyString());
        doNothing().when(r2).deleteFile(anyString());
        master = independentMaster("a");
    }

    @Test
    @DisplayName("cap boundary: 29 photos → upload 201 (30th, row on the master); 30 photos → upload 409 "
            + "PORTFOLIO_LIMIT_REACHED, count stays 30, the 31st never reaches R2 (F2 pre-check)")
    void should_acceptThirtiethAndReject31st_when_masterPortfolioAtCap() throws Exception {
        // Arrange
        for (int i = 0; i < MediaService.MAX_PORTFOLIO_PHOTOS - 1; i++) {
            insertMasterRow(master.userId(), master.masterId());
        }

        // Act
        ResponseEntity<String> thirtieth = upload(master.token());
        ResponseEntity<String> thirtyFirst = upload(master.token());

        // Assert
        assertThat(thirtieth.getStatusCode()).as("body=%s", thirtieth.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(objectMapper.readTree(thirtieth.getBody()).path("data").path("entityId").asText())
                .isEqualTo(master.masterId().toString());
        assertThat(thirtyFirst.getStatusCode()).as("body=%s", thirtyFirst.getBody()).isEqualTo(HttpStatus.CONFLICT);
        // Audit F6b: the cap carries a machine-readable data.code — mobile branches on it, never on the text.
        assertThat(objectMapper.readTree(thirtyFirst.getBody()).path("success").asBoolean(true)).isFalse();
        assertThat(objectMapper.readTree(thirtyFirst.getBody()).path("data").path("code").asText())
                .isEqualTo("PORTFOLIO_LIMIT_REACHED");
        assertThat(objectMapper.readTree(thirtyFirst.getBody()).path("message").asText())
                .isEqualTo("Portfolio photo limit reached");
        assertThat(masterCount(master.masterId())).isEqualTo(MediaService.MAX_PORTFOLIO_PHOTOS);
        // Audit F2: the unlocked pre-check rejects before the R2 PUT — only the 30th was uploaded, nothing deleted.
        ArgumentCaptor<String> uploaded = ArgumentCaptor.forClass(String.class);
        verify(r2, times(1)).uploadFile(uploaded.capture(), any(), anyLong(), anyString());
        assertThat(uploaded.getValue()).startsWith("portfolio/independent/" + master.masterId() + "/");
        verify(r2, never()).deleteFile(anyString());
    }

    @Test
    @DisplayName("F6a: another transaction holds the master row lock past the 3s lock_timeout → 503 + Retry-After "
            + "(never the cap's 409), no row, the uploaded blob discarded directly — no reference probe (G4)")
    void should_return503WithRetryAfter_when_masterRowLockHeldElsewhere() throws Exception {
        // Arrange
        ResponseEntity<String> resp;

        // Act — a raw connection holds the same FOR NO KEY UPDATE row lock lockIdForUpdate needs.
        try (java.sql.Connection holder = jdbcTemplate.getDataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (var st = holder.prepareStatement("SELECT id FROM masters WHERE id = ? FOR NO KEY UPDATE")) {
                st.setObject(1, master.masterId());
                try (var rs = st.executeQuery()) {
                    assertThat(rs.next()).as("the holder really locked the master row").isTrue();
                }
            }
            try {
                resp = upload(master.token());
            } finally {
                holder.rollback();
            }
        }

        // Assert
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getHeaders().getFirst("Retry-After")).isEqualTo("2");
        var body = objectMapper.readTree(resp.getBody());
        assertThat(body.path("message").asText())
                .isEqualTo(com.beautica.common.exception.ServiceUnavailableMessages.PORTFOLIO_BUSY);
        assertThat(body.path("data").path("code").isMissingNode()).as("body=%s", resp.getBody()).isTrue();
        assertThat(masterCount(master.masterId())).isZero();
        ArgumentCaptor<String> uploaded = ArgumentCaptor.forClass(String.class);
        verify(r2).uploadFile(uploaded.capture(), any(), anyLong(), anyString());
        assertThat(uploaded.getValue()).startsWith("portfolio/independent/" + master.masterId() + "/");
        verify(r2).deleteFile(uploaded.getValue());
        // Positive control: the spy records this path's repository calls, so the never() below is not vacuous.
        verify(mediaRepository).countByEntityTypeAndEntityIdAndMediaType(
                com.beautica.media.entity.EntityType.MASTER, master.masterId(),
                com.beautica.media.entity.MediaType.PORTFOLIO);
        verify(mediaRepository, never()).existsByEntityTypeAndEntityIdAndR2Key(any(), any(), anyString());
    }

    @Test
    @DisplayName("DELETE /media/portfolio/{id} of a SALON row → 404 (salon rows use the salon endpoint); row kept")
    void should_return404_when_masterDeletesSalonRow() throws Exception {
        // Arrange
        UUID ownerId = insertSalonOwner("p362-mp-owner-" + System.nanoTime() + "@beautica.test");
        UUID salonId = insertSalon(ownerId, "P362 MP Salon");
        UUID salonRow = insertRow(ownerId, "SALON", salonId, "portfolio/salons/");

        // Act
        ResponseEntity<String> resp = delete(salonRow, master.token());

        // Assert
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rowCount(salonRow)).isEqualTo(1);
        verify(r2, never()).deleteFile(anyString());
    }

    @Test
    @DisplayName("DELETE /media/portfolio/{id} of ANOTHER master's row → 404 (H3: same as unknown id); row kept, R2 untouched")
    void should_return404_when_masterDeletesAnotherMastersRow() throws Exception {
        // Arrange
        MasterFixture other = independentMaster("b");
        UUID otherRow = insertMasterRow(other.userId(), other.masterId());

        // Act
        ResponseEntity<String> resp = delete(otherRow, master.token());

        // Assert
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rowCount(otherRow)).isEqualTo(1);
        verify(r2, never()).deleteFile(anyString());
    }

    @Test
    @DisplayName("DELETE /media/portfolio/{id} of the master's OWN row → 204, row gone (positive control for the 404s)")
    void should_return204_when_masterDeletesOwnRow() throws Exception {
        // Arrange
        UUID ownRow = insertMasterRow(master.userId(), master.masterId());

        // Act
        ResponseEntity<String> resp = delete(ownRow, master.token());

        // Assert
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowCount(ownRow)).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private MasterFixture independentMaster(String tag) throws Exception {
        String email = "p362-mp-im-" + tag + "-" + System.nanoTime() + "@beautica.test";
        UUID userId = insertUser(email, "INDEPENDENT_MASTER", null);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, created_at, updated_at) VALUES (?, ?, NULL, 'INDEPENDENT_MASTER', 0, 0, true, NOW(), NOW())",
                masterId, userId);
        return new MasterFixture(userId, masterId, loginAndGetToken(email));
    }

    private UUID insertMasterRow(UUID uploaderId, UUID masterId) {
        return insertRow(uploaderId, "MASTER", masterId, "portfolio/independent/");
    }

    private UUID insertRow(UUID uploaderId, String entityType, UUID entityId, String root) {
        UUID id = UUID.randomUUID();
        String key = root + entityId + "/seed-" + id + ".jpg";
        jdbcTemplate.update("INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, r2_key, "
                        + "r2_url, created_at, updated_at) VALUES (?, ?, ?, ?, 'PORTFOLIO', ?, ?, NOW(), NOW())",
                id, uploaderId, entityType, entityId, key, "https://cdn.example/" + key);
        return id;
    }

    private ResponseEntity<String> upload(String token) {
        return restTemplate.exchange(PORTFOLIO_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)), String.class);
    }

    private ResponseEntity<String> delete(UUID mediaId, String token) {
        return restTemplate.exchange(PORTFOLIO_URL + "/" + mediaId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(token)), String.class);
    }

    private int masterCount(UUID masterId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_files WHERE entity_type = 'MASTER' AND entity_id = ?", Integer.class, masterId);
    }

    private int rowCount(UUID mediaId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_files WHERE id = ?", Integer.class, mediaId);
    }
}
