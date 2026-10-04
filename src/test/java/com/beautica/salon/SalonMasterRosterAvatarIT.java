package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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
import static org.assertj.core.api.Assertions.tuple;

/**
 * Salon roster ({@code GET /salons/{id}/masters}, {@code GET /masters/by-salon/{id}}) must carry
 * each master's own {@code users.avatar_url} — {@code MasterSummaryResponse.from} hardcoded
 * {@code null}. Two masters, one with an avatar and one without, pin per-row mapping.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Salon master roster — avatarUrl mapping")
class SalonMasterRosterAvatarIT extends AbstractIntegrationTest {

    private static final String AVATAR = "https://cdn.beautica.test/avatars/roster-avatar.jpg";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private SalonItFixtures fixtures;

    @BeforeEach
    void seedFixtures() {
        fixtures = new SalonItFixtures(
                restTemplate, jdbcTemplate, objectMapper, passwordEncoder, this::testCityId);
    }

    @Test
    @DisplayName("GET /salons/{id}/masters — avatarUrl set -> value, unset -> null")
    void should_returnAvatarUrlPerMaster_when_salonRosterListed() throws Exception {
        Roster roster = seedRoster();

        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/v1/salons/" + roster.salonId() + "/masters", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertAvatarsPerRow(objectMapper.readTree(response.getBody()), roster);
    }

    @Test
    @DisplayName("GET /masters/by-salon/{id} — avatarUrl set -> value, unset -> null")
    void should_returnAvatarUrlPerMaster_when_bySalonListed() throws Exception {
        Roster roster = seedRoster();

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/masters/by-salon/" + roster.salonId(), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(roster.ownerToken())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertAvatarsPerRow(objectMapper.readTree(response.getBody()), roster);
    }

    private void assertAvatarsPerRow(JsonNode body, Roster roster) {
        JsonNode rows = body.path("data").path("data");

        assertThat(rows)
                .extracting(r -> r.path("masterId").asText(),
                        r -> r.path("avatarUrl").isNull() ? null : r.path("avatarUrl").asText())
                .containsExactlyInAnyOrder(
                        tuple(roster.withAvatarMasterId().toString(), AVATAR),
                        tuple(roster.withoutAvatarMasterId().toString(), null));
    }

    private Roster seedRoster() throws Exception {
        UUID ownerId = fixtures.insertUser("owner-roster-" + UUID.randomUUID() + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "Roster Salon");
        UUID withAvatar = insertSalonMaster("roster-a-" + UUID.randomUUID() + "@beautica.test", salonId, AVATAR);
        UUID without = insertSalonMaster("roster-b-" + UUID.randomUUID() + "@beautica.test", salonId, null);
        String token = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));
        return new Roster(salonId, withAvatar, without, token);
    }

    private UUID insertSalonMaster(String email, UUID salonId, String avatarUrl) {
        UUID userId = fixtures.insertSalonMasterUser(email, salonId);
        jdbcTemplate.update("UPDATE users SET avatar_url = ? WHERE id = ?", avatarUrl, userId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', 0, 0, true, NOW(), NOW())",
                masterId, userId, salonId);
        return masterId;
    }

    private record Roster(UUID salonId, UUID withAvatarMasterId, UUID withoutAvatarMasterId, String ownerToken) {}
}
