package com.beautica.media;

import com.beautica.config.TestSecurityConfig;
import com.beautica.media.entity.EntityType;
import com.beautica.media.service.R2StorageService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.beautica.support.R2DeleteLedger.purgedKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Phase 363 — salon portfolio photos belong to the SALON, never to whoever uploaded them.
 *
 * <p>Locked rule (user, 2026-10-12): "salon photos only related to the salon, not owner or admin or masters".
 * Staff removal, staff account deletion and staff moves never touch salon photos; only salon deletion removes
 * them. {@code media_files.uploader_id} is audit-only since V193 ({@code ON DELETE SET NULL}). An independent
 * master's personal portfolio still leaves with their account — now explicitly, by entity.
 *
 * <p>Mutation targets (both must turn cases 1 and 2 red): restoring {@code ON DELETE CASCADE} on
 * {@code uploader_id}, or restoring an uploader-keyed purge in {@code StaffAccountDisposalService}.
 *
 * <p>Same context as {@link SalonPortfolioIT} ({@code @MockBean R2StorageService} only) — no new context.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Salon portfolio ownership — photos belong to the salon, not the uploader (Phase 363)")
class SalonPortfolioOwnershipIT extends AbstractMediaIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private R2StorageService r2;

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
        when(r2.deleteFiles(anyCollection())).thenReturn(java.util.Set.of());

        ownerEmail = "p363-owner-" + System.nanoTime() + "@beautica.test";
        ownerId = insertSalonOwner(ownerEmail);
        salonId = insertSalon(ownerId, "P363 Salon");
    }

    @Test
    @DisplayName("1: admin uploads 2 salon photos, owner removes the admin → rows kept (uploader_id NULL), still "
            + "public, R2 never asked to delete them")
    void should_keepSalonPhotos_when_ownerRemovesUploadingAdmin() throws Exception {
        // Arrange
        String adminEmail = email("admin");
        UUID adminId = insertUser(adminEmail, "SALON_ADMIN", salonId);
        String adminToken = loginAndGetToken(adminEmail);
        UUID first = mediaId(upload(salonId, adminToken));
        UUID second = mediaId(upload(salonId, adminToken));
        List<String> keys = List.of(keyOf(first), keyOf(second));

        // Act
        ResponseEntity<String> removed = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/admins/" + adminId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(loginAndGetToken(ownerEmail))), String.class);

        // Assert
        assertThat(removed.getStatusCode()).as("body=%s", removed.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertSurvivedWithNullUploader(first, second);
        assertThat(publicListing(salonId)).containsExactlyInAnyOrder(first, second);
        assertThat(purgedKeys(r2)).doesNotContainAnyElementsOf(keys);
    }

    @Test
    @DisplayName("2: admin uploads, then self-deletes the account → rows kept (uploader_id NULL), still public, "
            + "R2 never asked to delete them")
    void should_keepSalonPhotos_when_uploadingAdminSelfDeletes() throws Exception {
        // Arrange
        String adminEmail = email("admin-self");
        insertUser(adminEmail, "SALON_ADMIN", salonId);
        String adminToken = loginAndGetToken(adminEmail);
        UUID first = mediaId(upload(salonId, adminToken));
        UUID second = mediaId(upload(salonId, adminToken));
        List<String> keys = List.of(keyOf(first), keyOf(second));

        // Act
        ResponseEntity<String> deleted = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(adminToken)), String.class);

        // Assert
        assertThat(deleted.getStatusCode()).as("body=%s", deleted.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertSurvivedWithNullUploader(first, second);
        assertThat(publicListing(salonId)).containsExactlyInAnyOrder(first, second);
        assertThat(purgedKeys(r2)).doesNotContainAnyElementsOf(keys);
    }

    @Test
    @DisplayName("3: a salon master's account is disposed → the salon photos they uploaded are untouched")
    void should_keepSalonPhotos_when_salonMasterRemoved() throws Exception {
        // Arrange
        UUID masterUserId = insertUser(email("master"), "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())", masterId, masterUserId, salonId);
        UUID photo = insertSalonRow(masterUserId);

        // Act
        ResponseEntity<String> removed = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(loginAndGetToken(ownerEmail))), String.class);

        // Assert
        assertThat(removed.getStatusCode()).as("body=%s", removed.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(userExists(masterUserId)).as("the master's account is gone").isFalse();
        assertSurvivedWithNullUploader(photo);
        assertThat(purgedKeys(r2)).doesNotContain(keyOf(photo));
    }

    @Test
    @DisplayName("4a: admin moved to a sibling salon → the photo stays with the old salon and the moved admin "
            + "gets 403 deleting it there")
    void should_return403OnOldSalon_when_adminMovedToSibling() throws Exception {
        // Arrange
        String movedEmail = email("moved");
        UUID movedId = insertUser(movedEmail, "SALON_ADMIN", salonId);
        UUID photo = mediaId(upload(salonId, loginAndGetToken(movedEmail)));
        String ownerToken = loginAndGetToken(ownerEmail);

        // Act
        ResponseEntity<String> moved = moveAdminToSibling(movedId, ownerToken);
        ResponseEntity<String> movedAdminDelete = delete(salonId, photo, loginAndGetToken(movedEmail));

        // Assert
        assertThat(moved.getStatusCode()).as("body=%s", moved.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(entityIdOf(photo)).as("the photo stays with the old salon").isEqualTo(salonId);
        assertThat(movedAdminDelete.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rowExists(photo)).isTrue();
    }

    @Test
    @DisplayName("4b: admin moved to a sibling salon → the old owner and the old salon's new admin can each "
            + "delete a photo the moved admin uploaded")
    void should_letOldOwnerAndNewAdminDelete_when_uploaderMovedToSibling() throws Exception {
        // Arrange
        String movedEmail = email("moved");
        UUID movedId = insertUser(movedEmail, "SALON_ADMIN", salonId);
        String movedToken = loginAndGetToken(movedEmail);
        UUID ownersDelete = mediaId(upload(salonId, movedToken));
        UUID newAdminsDelete = mediaId(upload(salonId, movedToken));
        String ownerToken = loginAndGetToken(ownerEmail);
        assertThat(moveAdminToSibling(movedId, ownerToken).getStatusCode()).isEqualTo(HttpStatus.OK);
        String newAdminEmail = email("new-admin");
        insertUser(newAdminEmail, "SALON_ADMIN", salonId);

        // Act
        ResponseEntity<String> byOwner = delete(salonId, ownersDelete, ownerToken);
        ResponseEntity<String> byNewAdmin = delete(salonId, newAdminsDelete, loginAndGetToken(newAdminEmail));

        // Assert
        assertThat(byOwner.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(byNewAdmin.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowExists(ownersDelete)).isFalse();
        assertThat(rowExists(newAdminsDelete)).isFalse();
    }

    /** Owner moves admin {@code adminId} from {@code salonId} to a freshly created sibling salon of the same owner. */
    private ResponseEntity<String> moveAdminToSibling(UUID adminId, String ownerToken) {
        UUID sibling = insertSalon(ownerId, "P363 Sibling");
        return restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/admins/" + adminId + "/salon", HttpMethod.PATCH,
                new HttpEntity<>(Map.of("destinationSalonId", sibling.toString()), jsonHeaders(ownerToken)),
                String.class);
    }

    @Test
    @DisplayName("5: owner deletes a photo uploaded by a since-removed admin → 204, blob purged after commit")
    void should_deletePhotoOfRemovedAdmin_when_ownerDeletes() throws Exception {
        // Arrange
        String adminEmail = email("gone");
        UUID adminId = insertUser(adminEmail, "SALON_ADMIN", salonId);
        UUID photo = mediaId(upload(salonId, loginAndGetToken(adminEmail)));
        String key = keyOf(photo);
        String ownerToken = loginAndGetToken(ownerEmail);
        assertThat(restTemplate.exchange("/api/v1/salons/" + salonId + "/admins/" + adminId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(ownerToken)), String.class).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Act
        ResponseEntity<String> resp = delete(salonId, photo, ownerToken);

        // Assert
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(rowExists(photo)).isFalse();
        assertThat(purgedKeys(r2)).containsOnlyOnce(key);
    }

    @Test
    @DisplayName("6: salon deletion removes all its portfolio rows and blobs (phase 268 regression)")
    void should_removeAllPortfolioRowsAndBlobs_when_salonDeleted() throws Exception {
        // Arrange
        String adminEmail = email("admin-del");
        insertUser(adminEmail, "SALON_ADMIN", salonId);
        UUID byAdmin = mediaId(upload(salonId, loginAndGetToken(adminEmail)));
        String ownerToken = loginAndGetToken(ownerEmail);
        UUID byOwner = mediaId(upload(salonId, ownerToken));
        List<String> keys = List.of(keyOf(byAdmin), keyOf(byOwner));

        // Act
        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/salons/" + salonId, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(ownerToken)), String.class);

        // Assert
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(salonRowCount(salonId)).isZero();
        assertThat(purgedKeys(r2)).containsAll(keys);
    }

    @Test
    @DisplayName("7: independent master account deletion removes their MASTER rows and blobs — no orphan rows")
    void should_removeOwnPortfolio_when_independentMasterSelfDeletes() throws Exception {
        // Arrange
        String masterEmail = email("im");
        UUID userId = insertUser(masterEmail, "INDEPENDENT_MASTER", null);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, created_at, updated_at) VALUES (?, ?, NULL, 'INDEPENDENT_MASTER', 0, 0, true, "
                        + "NOW(), NOW())", masterId, userId);
        String token = loginAndGetToken(masterEmail);
        UUID photo = mediaId(restTemplate.exchange("/api/v1/media/portfolio", HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)), String.class));
        String key = keyOf(photo);

        // Act
        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(token)), String.class);

        // Assert
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_files WHERE entity_type = 'MASTER' AND entity_id = ?",
                Integer.class, masterId)).as("no orphan MASTER rows").isZero();
        assertThat(purgedKeys(r2)).contains(key);
    }

    @Test
    @DisplayName("V193: uploader_id is nullable and its FK deletes with SET NULL (no cascade)")
    void should_beNullableWithSetNullFk_when_v193Applied() {
        String nullable = jdbcTemplate.queryForObject(
                "SELECT is_nullable FROM information_schema.columns "
                        + "WHERE table_name = 'media_files' AND column_name = 'uploader_id'", String.class);
        List<String> deleteRules = jdbcTemplate.queryForList(
                "SELECT rc.delete_rule FROM information_schema.referential_constraints rc "
                        + "JOIN information_schema.key_column_usage kcu ON kcu.constraint_name = rc.constraint_name "
                        + "WHERE kcu.table_name = 'media_files' AND kcu.column_name = 'uploader_id'", String.class);

        assertThat(nullable).isEqualTo("YES");
        assertThat(deleteRules).containsExactly("SET NULL");
    }

    @Test
    @DisplayName("V193+V194: the uploader FK is added NOT VALID (V193) then VALIDATEd (V194) — it ends VALID, named, SET NULL, and "
            + "is the only FK on uploader_id")
    void should_leaveSingleValidatedSetNullFk_when_v193Applied() {
        List<Map<String, Object>> fks = jdbcTemplate.queryForList(
                "SELECT con.conname, con.convalidated, con.confdeltype, con.confrelid::regclass::text AS target "
                        + "FROM pg_constraint con "
                        + "JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = ANY (con.conkey) "
                        + "WHERE con.conrelid = 'media_files'::regclass AND con.contype = 'f' "
                        + "AND att.attname = 'uploader_id'");

        assertThat(fks).as("exactly one FK on uploader_id — the V37 cascade FK was dropped, fks=%s", fks)
                .hasSize(1);
        Map<String, Object> fk = fks.get(0);
        assertThat(fk.get("conname")).isEqualTo("fk_media_files_uploader_id");
        assertThat(fk.get("convalidated")).as("VALIDATE CONSTRAINT ran — a NOT VALID FK skips existing rows")
                .isEqualTo(true);
        assertThat(String.valueOf(fk.get("confdeltype"))).as("'n' = ON DELETE SET NULL").isEqualTo("n");
        assertThat(fk.get("target")).isEqualTo("users");
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void assertSurvivedWithNullUploader(UUID... mediaIds) {
        for (UUID id : mediaIds) {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT entity_type, entity_id, uploader_id FROM media_files WHERE id = ?", id);
            assertThat(row.get("entity_type")).isEqualTo(EntityType.SALON.name());
            assertThat(row.get("entity_id")).isEqualTo(salonId);
            assertThat(row.get("uploader_id")).as("audit pointer nulled, row kept").isNull();
        }
    }

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
            ids.add(UUID.fromString(item.path("id").asText()));
        }
        return ids;
    }

    /** A SALON row under the salon's own root, uploaded by {@code uploaderId} (audit pointer only). */
    private UUID insertSalonRow(UUID uploaderId) {
        UUID id = UUID.randomUUID();
        String key = "portfolio/salons/" + salonId + "/seed-" + id + ".jpg";
        jdbcTemplate.update("INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, r2_key, "
                        + "r2_url, created_at, updated_at) VALUES (?, ?, 'SALON', ?, 'PORTFOLIO', ?, ?, NOW(), NOW())",
                id, uploaderId, salonId, key, "https://cdn.example/" + key);
        return id;
    }

    private static HttpHeaders jsonHeaders(String token) {
        HttpHeaders headers = authHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private String keyOf(UUID mediaId) {
        return jdbcTemplate.queryForObject("SELECT r2_key FROM media_files WHERE id = ?", String.class, mediaId);
    }

    private UUID entityIdOf(UUID mediaId) {
        return jdbcTemplate.queryForObject("SELECT entity_id FROM media_files WHERE id = ?", UUID.class, mediaId);
    }

    private boolean rowExists(UUID mediaId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_files WHERE id = ?", Integer.class, mediaId) == 1;
    }

    private boolean userExists(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId) == 1;
    }

    private int salonRowCount(UUID salon) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_files WHERE entity_type = 'SALON' AND entity_id = ?", Integer.class, salon);
    }

    private static String email(String label) {
        return "p363-" + label + "-" + System.nanoTime() + "@beautica.test";
    }
}
