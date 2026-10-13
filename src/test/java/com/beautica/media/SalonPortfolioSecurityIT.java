package com.beautica.media;

import com.beautica.config.TestSecurityConfig;
import com.beautica.media.service.R2StorageService;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 362 — the salon portfolio IDOR matrix against the real stack (closes the security audit's LOW
 * "no real-stack coverage" finding). Every refused caller is refused BEFORE any storage call: the R2 mock is
 * never stubbed for those tests, so ANY interaction (even {@code isEnabled()}) fails
 * {@code verifyNoInteractions}, and the salon's seeded photo is left in place.
 *
 * <p>Same context as {@link SalonImageSecurityIT} ({@code @MockBean R2StorageService} only) — no new context.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Salon portfolio — IDOR / role matrix on POST + DELETE, public GET (Phase 362)")
class SalonPortfolioSecurityIT extends AbstractMediaIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private R2StorageService r2;

    @Override protected TestRestTemplate restTemplate() { return restTemplate; }
    @Override protected ObjectMapper objectMapper() { return objectMapper; }
    @Override protected PasswordEncoder passwordEncoder() { return passwordEncoder; }

    private String ownerEmail;
    private UUID ownerId;
    private UUID salonId;
    private UUID photoId;

    @BeforeEach
    void seedSalonWithOnePhoto() {
        reset(r2);
        ownerEmail = "p362-sec-owner-" + System.nanoTime() + "@beautica.test";
        ownerId = insertSalonOwner(ownerEmail);
        salonId = insertSalon(ownerId, "P362 Sec Salon A");
        photoId = insertPortfolioRow(ownerId, "SALON", salonId);
    }

    @ParameterizedTest(name = "{0} → 403 on POST and DELETE; photo kept, R2 untouched")
    @ValueSource(strings = {"CLIENT", "SALON_MASTER", "INDEPENDENT_MASTER"})
    @DisplayName("non-manager roles (CLIENT, SALON_MASTER of THIS salon, INDEPENDENT_MASTER) → 403 on POST and DELETE")
    void should_return403OnPostAndDelete_when_callerRoleCannotManageSalons(String role) throws Exception {
        // Arrange — the SALON_MASTER is bound to this very salon, so only the role gate refuses it
        String email = "p362-sec-" + role.toLowerCase() + "-" + System.nanoTime() + "@beautica.test";
        insertUser(email, role, "SALON_MASTER".equals(role) ? salonId : null);
        String token = loginAndGetToken(email);

        // Act + Assert
        assertPostAndDeleteForbidden(token);
    }

    @Test
    @DisplayName("OWNER of a different salon → 403 on POST and DELETE of salon A; photo kept, R2 untouched")
    void should_return403OnPostAndDelete_when_callerOwnsAnotherSalon() throws Exception {
        // Arrange
        String email = "p362-sec-foreign-owner-" + System.nanoTime() + "@beautica.test";
        insertSalon(insertSalonOwner(email), "P362 Sec Salon B");
        String token = loginAndGetToken(email);

        // Act + Assert
        assertPostAndDeleteForbidden(token);
    }

    @Test
    @DisplayName("SALON_ADMIN bound to a different salon → 403 on POST and DELETE of salon A; photo kept, R2 untouched")
    void should_return403OnPostAndDelete_when_callerAdministersAnotherSalon() throws Exception {
        // Arrange
        UUID salonB = insertSalon(insertSalonOwner("p362-sec-owner-b-" + System.nanoTime() + "@beautica.test"),
                "P362 Sec Salon B");
        String email = "p362-sec-foreign-admin-" + System.nanoTime() + "@beautica.test";
        insertUser(email, "SALON_ADMIN", salonB);
        String token = loginAndGetToken(email);

        // Act + Assert
        assertPostAndDeleteForbidden(token);
    }

    @Test
    @DisplayName("anonymous → 401 on POST and DELETE; photo kept, R2 untouched")
    void should_return401OnPostAndDelete_when_anonymous() {
        // Arrange
        HttpHeaders multipart = new HttpHeaders();
        multipart.setContentType(MediaType.MULTIPART_FORM_DATA);

        // Act
        ResponseEntity<String> post = restTemplate.exchange(collectionUrl(salonId), HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), multipart), String.class);
        ResponseEntity<String> delete = restTemplate.exchange(itemUrl(salonId, photoId), HttpMethod.DELETE,
                new HttpEntity<>(new HttpHeaders()), String.class);

        // Assert
        assertThat(post.getStatusCode()).as("anonymous POST").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(delete.getStatusCode()).as("anonymous DELETE").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertPhotoKept();
        verifyNoInteractions(r2);
    }

    @Test
    @DisplayName("anonymous GET /salons/{id}/portfolio stays public → 200 listing the salon's photo")
    void should_return200WithPhoto_when_anonymousReadsPortfolio() throws Exception {
        // Act
        ResponseEntity<String> resp = restTemplate.exchange(collectionUrl(salonId), HttpMethod.GET,
                new HttpEntity<>(new HttpHeaders()), String.class);

        // Assert
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode content = objectMapper.readTree(resp.getBody()).path("data").path("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).path("id").asText()).isEqualTo(photoId.toString());
        assertThat(content.get(0).path("entityId").asText()).isEqualTo(salonId.toString());
    }

    @Test
    @DisplayName("owner of BOTH salons: salon B's photo addressed via salon A's path → 404; B's row kept, R2 untouched")
    void should_return404_when_salonBPhotoAddressedThroughSalonAPath() throws Exception {
        // Arrange — the caller can manage both salons, so only the path↔row binding can refuse it
        UUID salonB = insertSalon(ownerId, "P362 Sec Salon A2");
        UUID photoB = insertPortfolioRow(ownerId, "SALON", salonB);
        String token = loginAndGetToken(ownerEmail);

        // Act
        ResponseEntity<String> resp = restTemplate.exchange(itemUrl(salonId, photoB), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(token)), String.class);

        // Assert
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rowCount(photoB)).as("salon B's photo survives").isEqualTo(1);
        assertPhotoKept();
        verifyNoInteractions(r2);
    }

    @Test
    @DisplayName("an INDEPENDENT_MASTER's portfolio row addressed via the salon path by the salon owner → 404; row kept")
    void should_return404_when_masterRowAddressedThroughSalonPath() throws Exception {
        // Arrange
        UUID masterRow = insertPortfolioRow(ownerId, "MASTER", seedIndependentMaster());
        String token = loginAndGetToken(ownerEmail);

        // Act
        ResponseEntity<String> resp = restTemplate.exchange(itemUrl(salonId, masterRow), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(token)), String.class);

        // Assert
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(rowCount(masterRow)).isEqualTo(1);
        verifyNoInteractions(r2);
    }

    @ParameterizedTest(name = "inactive salon, {0} → 404 on POST and DELETE")
    @ValueSource(strings = {"SALON_OWNER", "SALON_ADMIN"})
    @DisplayName("inactive salon → 404 for a manager on POST and DELETE; photo kept, nothing stored")
    void should_return404OnPostAndDelete_when_salonInactive(String role) throws Exception {
        // Arrange
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salonId);
        String token;
        if ("SALON_OWNER".equals(role)) {
            token = loginAndGetToken(ownerEmail);
        } else {
            String email = "p362-sec-inactive-admin-" + System.nanoTime() + "@beautica.test";
            insertUser(email, "SALON_ADMIN", salonId);
            token = loginAndGetToken(email);
        }
        when(r2.isEnabled()).thenReturn(true);

        // Act
        ResponseEntity<String> post = restTemplate.exchange(collectionUrl(salonId), HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)), String.class);
        ResponseEntity<String> delete = restTemplate.exchange(itemUrl(salonId, photoId), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(token)), String.class);

        // Assert
        assertThat(post.getStatusCode()).as("POST body=%s", post.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete.getStatusCode()).as("DELETE body=%s", delete.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertPhotoKept();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_files WHERE entity_type = 'SALON' AND entity_id = ?", Integer.class, salonId))
                .as("no row added to the inactive salon").isEqualTo(1);
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("bound SALON_ADMIN may manage the portfolio but is still 403 on logo + cover POST/DELETE")
    void should_return403OnLogoAndCover_when_boundAdminCallsThem() throws Exception {
        // Arrange
        String email = "p362-sec-admin-logo-" + System.nanoTime() + "@beautica.test";
        insertUser(email, "SALON_ADMIN", salonId);
        String token = loginAndGetToken(email);

        for (String slot : new String[]{"logo", "cover"}) {
            String url = "/api/v1/salons/" + salonId + "/media/" + slot;

            // Act
            ResponseEntity<String> post = restTemplate.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)), String.class);
            ResponseEntity<String> delete = restTemplate.exchange(url, HttpMethod.DELETE,
                    new HttpEntity<>(authHeaders(token)), String.class);

            // Assert
            assertThat(post.getStatusCode()).as("POST %s", slot).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(delete.getStatusCode()).as("DELETE %s", slot).isEqualTo(HttpStatus.FORBIDDEN);
        }
        verifyNoInteractions(r2);
    }

    @Test
    @DisplayName("SALON_OWNER on POST /media/portfolio and DELETE /media/portfolio/{id} → 403 (master-only routes); "
            + "no salon row written or removed")
    void should_return403_when_salonOwnerUsesMasterPortfolioRoutes() throws Exception {
        // Arrange
        String token = loginAndGetToken(ownerEmail);

        // Act
        ResponseEntity<String> post = restTemplate.exchange("/api/v1/media/portfolio", HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)), String.class);
        ResponseEntity<String> delete = restTemplate.exchange("/api/v1/media/portfolio/" + photoId,
                HttpMethod.DELETE, new HttpEntity<>(authHeaders(token)), String.class);

        // Assert
        assertThat(post.getStatusCode()).as("POST body=%s", post.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete.getStatusCode()).as("DELETE body=%s", delete.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertPhotoKept();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_files WHERE uploader_id = ?", Integer.class, ownerId))
                .as("no new row from the owner").isEqualTo(1);
        verifyNoInteractions(r2);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void assertPostAndDeleteForbidden(String token) {
        ResponseEntity<String> post = restTemplate.exchange(collectionUrl(salonId), HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)), String.class);
        ResponseEntity<String> delete = restTemplate.exchange(itemUrl(salonId, photoId), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(token)), String.class);

        assertThat(post.getStatusCode()).as("POST body=%s", post.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(delete.getStatusCode()).as("DELETE body=%s", delete.getBody()).isEqualTo(HttpStatus.FORBIDDEN);
        assertPhotoKept();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_files WHERE entity_type = 'SALON' AND entity_id = ?", Integer.class, salonId))
                .as("no row added to salon A").isEqualTo(1);
        verifyNoInteractions(r2);
    }

    private void assertPhotoKept() {
        assertThat(rowCount(photoId)).as("salon A's seeded photo survives").isEqualTo(1);
    }

    private int rowCount(UUID mediaId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_files WHERE id = ?", Integer.class, mediaId);
    }

    private UUID insertPortfolioRow(UUID uploaderId, String entityType, UUID entityId) {
        UUID id = UUID.randomUUID();
        String root = "SALON".equals(entityType) ? "portfolio/salons/" : "portfolio/independent/";
        String key = root + entityId + "/seed-" + id + ".jpg";
        jdbcTemplate.update("INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, r2_key, "
                        + "r2_url, created_at, updated_at) VALUES (?, ?, ?, ?, 'PORTFOLIO', ?, ?, NOW(), NOW())",
                id, uploaderId, entityType, entityId, key, "https://cdn.example/" + key);
        return id;
    }

    private UUID seedIndependentMaster() {
        UUID userId = insertUser("p362-sec-im-" + System.nanoTime() + "@beautica.test", "INDEPENDENT_MASTER", null);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, created_at, updated_at) VALUES (?, ?, NULL, 'INDEPENDENT_MASTER', 0, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private static String collectionUrl(UUID salon) {
        return "/api/v1/salons/" + salon + "/portfolio";
    }

    private static String itemUrl(UUID salon, UUID mediaId) {
        return collectionUrl(salon) + "/" + mediaId;
    }
}
