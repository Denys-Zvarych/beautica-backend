package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V177 — backfills the legacy {@code salons.city} label from {@code cities.name_uk}.
 *
 * <p><b>Why the shared container and a re-applied SQL body.</b> {@link AbstractIntegrationTest}'s
 * container boots fully migrated on an EMPTY schema, so V177 ran there against zero salons. Each
 * test seeds the pre-fix shapes (stale free text next to a valid {@code city_id}; a post-Phase-10.6
 * {@code NULL}) and re-applies V177's exact SQL body. That is equivalent to Flyway running it: the
 * migration is one set-based {@code UPDATE} with no DDL and no temp state, so nothing about the
 * Flyway wrapper changes its effect — and it avoids a second private container (Playbook §M.2),
 * which {@code V164SalonOwnedBackfillMigrationTest} needed only because it pins an older schema.
 */
@DisplayName("V177 migration — salons.city is re-derived from the settlement taxonomy")
class V177SalonsCityBackfillMigrationTest extends AbstractIntegrationTest {

    private static final String V177_SCRIPT = "db/migration/V177__backfill_salons_city_from_settlement.sql";

    private String v177Sql;
    private UUID ownerId;

    @BeforeEach
    void loadSqlAndSeedOwner() throws IOException {
        try (InputStream in = new ClassPathResource(V177_SCRIPT).getInputStream()) {
            v177Sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, "v177-" + ownerId + "@beautica.test",
                new BCryptPasswordEncoder(4).encode("test-password"));
    }

    private UUID insertSalon(String city, UUID cityId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, is_active, city_id) VALUES (?, ?, 'V177', ?, true, ?)",
                id, ownerId, city, cityId);
        return id;
    }

    private String cityOf(UUID salonId) {
        return jdbcTemplate.queryForObject("SELECT city FROM salons WHERE id = ?", String.class, salonId);
    }

    private String settlementName(UUID cityId) {
        return jdbcTemplate.queryForObject("SELECT name_uk FROM cities WHERE id = ?", String.class, cityId);
    }

    @Test
    @DisplayName("a stale free-text city next to a valid city_id is replaced by the settlement name")
    void should_replaceStaleCity_when_cityIdPointsAtAnotherSettlement() {
        UUID cityId = testCityId();
        UUID salonId = insertSalon("Kyiv", cityId);

        jdbcTemplate.update(v177Sql);

        assertThat(cityOf(salonId))
                .isEqualTo(settlementName(cityId))
                .isEqualTo("Вінниця");
    }

    @Test
    @DisplayName("a post-Phase-10.6 salon with a NULL city gains the settlement name")
    void should_fillNullCity_when_salonWasCreatedAfterPhase106() {
        UUID cityId = testCityId();
        UUID salonId = insertSalon(null, cityId);

        jdbcTemplate.update(v177Sql);

        assertThat(cityOf(salonId)).isEqualTo(settlementName(cityId));
    }

    @Test
    @DisplayName("idempotent: a second application touches zero rows and changes nothing")
    void should_updateNothing_when_appliedTwice() {
        UUID cityId = testCityId();
        UUID stale = insertSalon("Kyiv", cityId);
        UUID alreadyCorrect = insertSalon(settlementName(cityId), cityId);

        int firstPass = jdbcTemplate.update(v177Sql);
        int secondPass = jdbcTemplate.update(v177Sql);

        assertThat(firstPass)
                .as("only the stale row is written — the already-correct one is skipped by IS DISTINCT FROM")
                .isEqualTo(1);
        assertThat(secondPass).isZero();
        assertThat(cityOf(stale)).isEqualTo(settlementName(cityId));
        assertThat(cityOf(alreadyCorrect)).isEqualTo(settlementName(cityId));
    }

    @Test
    @DisplayName("V177 is recorded as a successful Flyway migration")
    void should_recordV177AsSuccessful_when_containerBoots() {
        Boolean success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '177'", Boolean.class);

        assertThat(success).isTrue();
    }
}
