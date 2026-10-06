package com.beautica.media;

import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 341 — storage-off contract against the REAL {@code R2StorageService} (no mock): the test
 * profile leaves {@code app.cloudflare-r2.enabled} at its default {@code false}, so uploads must
 * answer 503 and persist nothing, while deletes keep working.
 *
 * <p>The portfolio case with an owner who has NO salon doubles as the "guard runs first" probe:
 * without the 503 guard the service would reach the salon lookup and answer 403 instead.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Media — storage disabled (real R2StorageService), Phase 341")
class MediaStorageDisabledIT extends AbstractMediaIntegrationTest {

    private static final String AVATAR_URL = "/api/v1/media/avatar";
    private static final String PORTFOLIO_URL = "/api/v1/media/portfolio";
    private static final String NOT_CONFIGURED = "Media storage is not configured";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

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

    private long mediaRowCount() {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_files", Long.class);
        return count == null ? 0 : count;
    }

    @Test
    @DisplayName("POST /media/avatar — 503 + error envelope and avatar columns untouched when storage is off")
    void should_return503AndLeaveUserRowUntouched_when_avatarUploadedWithStorageOff() throws Exception {
        // Arrange
        String email = "media-off-avatar-" + System.nanoTime() + "@beautica.test";
        UUID userId = insertClient(email);
        String token = loginAndGetToken(email);

        // Act
        ResponseEntity<String> resp = restTemplate.exchange(
                AVATAR_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)),
                String.class);

        // Assert
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(objectMapper.readTree(resp.getBody()).path("success").asBoolean(true))
                .as("envelope success flag, body=%s", resp.getBody()).isFalse();
        assertThat(resp.getBody()).contains(NOT_CONFIGURED);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT avatar_url FROM users WHERE id = ?", String.class, userId))
                .as("avatar_url must stay NULL, not an empty string").isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT avatar_r2_key FROM users WHERE id = ?", String.class, userId))
                .as("avatar_r2_key must stay NULL").isNull();
    }

    @Test
    @DisplayName("POST /media/portfolio — 503 and no media_files row when SALON_OWNER with a salon uploads and storage is off")
    void should_return503AndInsertNoRow_when_portfolioUploadedWithStorageOff() throws Exception {
        // Arrange
        String email = "media-off-port-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = insertSalonOwner(email);
        insertSalon(ownerId, "Storage Off Salon " + System.nanoTime());
        String token = loginAndGetToken(email);
        long before = mediaRowCount();

        // Act
        ResponseEntity<String> resp = restTemplate.exchange(
                PORTFOLIO_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)),
                String.class);

        // Assert
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody()).contains(NOT_CONFIGURED);
        assertThat(mediaRowCount()).as("no media_files row may be written").isEqualTo(before);
    }

    @Test
    @DisplayName("POST /media/portfolio — 503 (not 403) for an owner without a salon, proving the storage guard runs before the salon lookup")
    void should_return503BeforeOwnershipCheck_when_storageOffAndOwnerHasNoSalon() throws Exception {
        // Arrange
        String email = "media-off-nosalon-" + System.nanoTime() + "@beautica.test";
        insertSalonOwner(email);
        String token = loginAndGetToken(email);

        // Act
        ResponseEntity<String> resp = restTemplate.exchange(
                PORTFOLIO_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)),
                String.class);

        // Assert
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("DELETE /media/avatar — still 204 when storage is off (deletes stay no-ops)")
    void should_return204_when_avatarDeletedWithStorageOff() throws Exception {
        // Arrange
        String email = "media-off-del-" + System.nanoTime() + "@beautica.test";
        insertClient(email);
        String token = loginAndGetToken(email);

        // Act
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<String> resp = restTemplate.exchange(
                AVATAR_URL, HttpMethod.DELETE, new HttpEntity<>(headers), String.class);

        // Assert
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @org.junit.jupiter.params.ParameterizedTest(name = "TC-7: POST + DELETE /salons/<id>/media/{0} — 503, row unchanged")
    @org.junit.jupiter.params.provider.ValueSource(strings = {"logo", "cover"})
    void should_return503AndLeaveSalonRowUntouched_when_salonImageWrittenWithStorageOff(String slot) throws Exception {
        // Arrange — a salon that already carries a logo + cover, so a DELETE that slipped past the guard
        // would visibly null them.
        String email = "media-off-salon-" + slot + "-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = insertSalonOwner(email);
        UUID salonId = insertSalon(ownerId, "Storage Off Salon " + System.nanoTime());
        String logo = "https://cdn.example/salons/" + salonId + "/logo/l.jpg";
        String cover = "https://cdn.example/salons/" + salonId + "/cover/c.jpg";
        jdbcTemplate.update("UPDATE salons SET avatar_url = ?, cover_image_url = ? WHERE id = ?", logo, cover, salonId);
        String token = loginAndGetToken(email);
        String url = "/api/v1/salons/" + salonId + "/media/" + slot;

        // Act
        ResponseEntity<String> post = restTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)), String.class);
        ResponseEntity<String> delete = restTemplate.exchange(url, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(token)), String.class);

        // Assert
        assertThat(post.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(post.getBody()).contains(NOT_CONFIGURED);
        assertThat(delete.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(delete.getBody()).contains(NOT_CONFIGURED);
        java.util.Map<String, Object> cols = jdbcTemplate.queryForMap(
                "SELECT avatar_url, avatar_r2_key, cover_image_url, cover_r2_key FROM salons WHERE id = ?", salonId);
        assertThat(cols.get("avatar_url")).isEqualTo(logo);
        assertThat(cols.get("cover_image_url")).isEqualTo(cover);
        assertThat(cols.get("avatar_r2_key")).isNull();
        assertThat(cols.get("cover_r2_key")).isNull();
    }
}
