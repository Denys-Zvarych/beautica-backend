package com.beautica.user;

import com.beautica.auth.dto.RefreshRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.beautica.support.R2DeleteLedger.purgedKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doAnswer;

/**
 * Phase 488 (SUP-11 / SUP-67 / SAL-47, AC2 + AC5) — {@code SALON_OWNER} self-delete through the real
 * {@code DELETE /api/v1/users/me} stack: salons created and deleted through the API (so the owner-as-master row
 * exists exactly as production builds it), the account deleted, the same email re-registered as a brand-new
 * account that reaches none of the old data, and the old salon kept as anonymous client history. The
 * re-registered account's list endpoints (bookings, notifications, favourites, blocks) are pinned against a
 * fully seeded world in {@link OwnerSelfDeleteResidualAccessIT}; this class shares its base and context.
 */
@DisplayName("DELETE /api/v1/users/me — SALON_OWNER self-delete (Phase 488)")
class OwnerAccountSelfDeleteIT extends AbstractOwnerSelfDeleteWorldIT {

    @Test
    @DisplayName("deleted salon with history → 204; old tokens dead; salon kept ownerless + contacts blank; client "
            + "history intact; same email re-registers as a fresh account that sees none of the old data (AC5)")
    void should_deleteOwnerAndDetachSalon_when_allSalonsDeleted() throws Exception {
        String email = "owner-del-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = csd.createUser(email, "SALON_OWNER", null, "Тест", "Власник");
        Tokens old = loginTokens(email);
        UUID salonId = createSalon(old.access(), "Detach Me");
        jdbcTemplate.update("UPDATE salons SET phone = '+380501112233', instagram_url = 'detach_me' WHERE id = ?",
                salonId);
        UUID ownerMasterId = jdbcTemplate.queryForObject(
                "SELECT id FROM masters WHERE user_id = ?", UUID.class, ownerId);
        UUID masterServiceId = insertMasterService(ownerMasterId, salonId);
        UUID clientId = csd.createClient();
        String clientEmail = emailOf(clientId);
        UUID bookingId = csd.insertBooking(clientId, ownerMasterId, masterServiceId, salonId, "COMPLETED", PAST);
        csd.insertReview(bookingId, clientId, ownerMasterId, salonId, 5, "Чудово");
        csd.insertClientReview(bookingId, clientId, ownerMasterId, salonId, 5);
        inviteViaApi(old.access(), salonId);
        deleteSalon(old.access(), salonId);

        ResponseEntity<String> response = deleteMe(old.access());

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(ownerId)).isFalse();
        assertThat(get("/api/v1/users/me", old.access()).getStatusCode())
                .as("old access token is dead").isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(restTemplate.postForEntity("/api/v1/auth/refresh", new RefreshRequest(old.refresh()), String.class)
                .getStatusCode().is2xxSuccessful()).as("old refresh token is rejected").isFalse();

        Map<String, Object> salon = jdbcTemplate.queryForMap(
                "SELECT owner_id, is_active, phone, instagram_url, name FROM salons WHERE id = ?", salonId);
        assertThat(salon.get("owner_id")).as("salon detached").isNull();
        assertThat(salon.get("is_active")).isEqualTo(false);
        assertThat(salon.get("phone")).as("contact blanked").isNull();
        assertThat(salon.get("instagram_url")).as("contact blanked").isNull();
        assertThat(salon.get("name")).as("name kept for client history").isEqualTo("Detach Me");
        assertThat(csd.count("SELECT COUNT(*) FROM invite_tokens WHERE salon_id = ?", salonId)).isZero();
        assertThat(csd.masterExists(ownerMasterId)).as("owner-master kept as a name stub (history)").isTrue();
        assertThat(csd.masterUserId(ownerMasterId)).isNull();
        assertThat(csd.masterDetachedFirstName(ownerMasterId)).isEqualTo("Тест");
        assertThat(csd.bookingExists(bookingId)).isTrue();
        assertThat(csd.count("SELECT COUNT(*) FROM reviews WHERE booking_id = ?", bookingId)).isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?", bookingId)).isEqualTo(1);

        ResponseEntity<String> clientBookings = get("/api/v1/bookings/me", fixtures.tokenFor(clientEmail));
        assertThat(clientBookings.getStatusCode()).as("body: %s", clientBookings.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(clientBookings.getBody()).as("client still sees the past booking at the detached salon")
                .contains(bookingId.toString()).contains("Detach Me");

        assertFreshAccountSeesNothing(email, ownerId, salonId, bookingId);
    }

    @Test
    @DisplayName("an ACTIVE salon → 409 data.code=OWNER_HAS_ACTIVE_SALONS and every row unchanged (AC2)")
    void should_return409WithCode_when_ownerHasActiveSalon() throws Exception {
        String email = "owner-active-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = csd.createUser(email, "SALON_OWNER", null, "Тест", "Власник");
        Tokens tokens = loginTokens(email);
        UUID salonId = createSalon(tokens.access(), "Still Open");
        inviteViaApi(tokens.access(), salonId);
        Map<String, Long> before = rowCounts();
        Map<String, Object> salonBefore = salonRow(salonId);

        ResponseEntity<String> response = deleteMe(tokens.access());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("data").path("code").asText()).isEqualTo("OWNER_HAS_ACTIVE_SALONS");
        assertThat(body.path("message").asText()).isEqualTo("Спочатку видаліть усі свої салони");
        assertThat(rowCounts()).as("nothing changed").isEqualTo(before);
        assertThat(salonRow(salonId)).isEqualTo(salonBefore);
        assertThat(csd.userExists(ownerId)).isTrue();
        assertThat(get("/api/v1/users/me", tokens.access()).getStatusCode())
                .as("session survives a refusal").isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("one active + one deleted salon → 409 OWNER_HAS_ACTIVE_SALONS, the deleted salon stays owned")
    void should_return409_when_oneActiveAndOneDeletedSalon() throws Exception {
        String email = "owner-mixed-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = csd.createUser(email, "SALON_OWNER", null, "Тест", "Власник");
        Tokens tokens = loginTokens(email);
        UUID deleted = createSalon(tokens.access(), "Gone");
        createSalon(tokens.access(), "Open");
        deleteSalon(tokens.access(), deleted);

        ResponseEntity<String> response = deleteMe(tokens.access());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(response.getBody()).path("data").path("code").asText())
                .isEqualTo("OWNER_HAS_ACTIVE_SALONS");
        assertThat(jdbcTemplate.queryForObject("SELECT owner_id FROM salons WHERE id = ?", UUID.class, deleted))
                .isEqualTo(ownerId);
    }

    @Test
    @DisplayName("an owner who never created a salon → 204, account gone")
    void should_return204_when_ownerNeverCreatedSalon() throws Exception {
        String email = "owner-none-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = csd.createUser(email, "SALON_OWNER", null, "Тест", "Власник");

        ResponseEntity<String> response = deleteMe(login(email));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(ownerId)).isFalse();
    }

    @Test
    @DisplayName("the avatar key is purged from R2 after commit, exactly once")
    void should_purgeAvatarAfterCommit_when_ownerDeleted() throws Exception {
        String email = "owner-avatar-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = csd.createUser(email, "SALON_OWNER", null, "Тест", "Власник");
        String avatarKey = seedAvatar(ownerId);

        ResponseEntity<String> response = deleteMe(login(email));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(purgedKeys(r2)).filteredOn(avatarKey::equals).hasSize(1);
    }

    @Test
    @DisplayName("a deletion that rolls back after the sweep was registered never purges the avatar")
    void should_notPurgeAvatar_when_deletionRollsBack() throws Exception {
        String email = "owner-rollback-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = csd.createUser(email, "SALON_OWNER", null, "Тест", "Власник");
        String avatarKey = seedAvatar(ownerId);
        String token = login(email);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("forced rollback after the purge was registered");
        }).when(accountBlobPurgeRegistrar).registerAfterCommit(any(User.class), anyList());

        ResponseEntity<String> response = deleteMe(token);

        assertThat(response.getStatusCode().is5xxServerError()).as("body: %s", response.getBody()).isTrue();
        assertThat(csd.userExists(ownerId)).as("rolled back").isTrue();
        assertThat(purgedKeys(r2)).doesNotContain(avatarKey);
    }

    @Test
    @DisplayName("V236: an ownerless ACTIVE salon violates chk_salons_ownerless_inactive; an ownerless inactive one is allowed")
    void should_rejectOwnerlessActiveSalon_when_inserted() {
        UUID cityId = testCityId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, NULL, 'Orphan', true, NOW(), NOW(), ?)", UUID.randomUUID(), cityId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_salons_ownerless_inactive");
        assertThat(jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, NULL, 'Detached', false, NOW(), NOW(), ?)", UUID.randomUUID(), cityId))
                .isEqualTo(1);
    }

    // ── AC5 ────────────────────────────────────────────────────────────────────

    private void assertFreshAccountSeesNothing(String email, UUID oldId, UUID oldSalonId, UUID oldBookingId)
            throws Exception {
        String fresh = reRegister(email, SelfRegistrationRole.SALON_OWNER);

        JsonNode me = objectMapper.readTree(get("/api/v1/users/me", fresh).getBody()).path("data");
        assertThat(me.path("id").asText()).as("a brand-new account id").isNotEqualTo(oldId.toString());
        assertThat(objectMapper.readTree(get("/api/v1/salons/mine", fresh).getBody()).path("data").size())
                .as("GET /salons/mine is empty").isZero();
        assertThat(get("/api/v1/masters/me", fresh).getStatusCode())
                .as("no master profile is reachable").isEqualTo(HttpStatus.NOT_FOUND);
        for (String path : List.of("/api/v1/salons/" + oldSalonId, "/api/v1/salons/" + oldSalonId + "/invites",
                "/api/v1/salons/" + oldSalonId + "/staff", "/api/v1/bookings/" + oldBookingId)) {
            ResponseEntity<String> denied = get(path, fresh);
            assertThat(denied.getStatusCode()).as("%s must not be reachable by the re-registered account — body: %s",
                    path, denied.getBody()).isIn(HttpStatus.FORBIDDEN, HttpStatus.NOT_FOUND);
        }
        Map<String, Object> salon = jdbcTemplate.queryForMap(
                "SELECT owner_id, phone, instagram_url FROM salons WHERE id = ?", oldSalonId);
        assertThat(salon.get("owner_id")).as("old salon still anonymised, never relinked by email").isNull();
        assertThat(salon.get("phone")).isNull();
        assertThat(salon.get("instagram_url")).isNull();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String seedAvatar(UUID userId) {
        String key = "avatars/" + userId + "/a-" + UUID.randomUUID() + ".jpg";
        jdbcTemplate.update("UPDATE users SET avatar_r2_key = ?, avatar_url = ? WHERE id = ?",
                key, "https://cdn.example/" + key, userId);
        return key;
    }

    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : List.of("users", "salons", "masters", "invite_tokens", "bookings", "refresh_tokens",
                "media_files", "master_services", "service_definitions")) {
            counts.put(table, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class));
        }
        return counts;
    }

    private Map<String, Object> salonRow(UUID salonId) {
        return jdbcTemplate.queryForMap(
                "SELECT owner_id, is_active, phone, instagram_url, name, updated_at FROM salons WHERE id = ?", salonId);
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
