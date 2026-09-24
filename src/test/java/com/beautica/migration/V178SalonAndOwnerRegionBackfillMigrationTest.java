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
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V178 — backfills {@code salons.city/region} and a SALON_OWNER's {@code users.city/region}
 * from the settlement taxonomy.
 *
 * <p>Same harness as {@link V177SalonsCityBackfillMigrationTest}: the shared container ran V178
 * against an empty schema, so each test seeds the pre-fix shapes and re-applies V178's exact SQL
 * body (two set-based {@code UPDATE}s, no DDL, no temp state) in one simple-query message.
 */
@DisplayName("V178 migration — salon region and SALON_OWNER city/region are re-derived from the settlement taxonomy")
class V178SalonAndOwnerRegionBackfillMigrationTest extends AbstractIntegrationTest {

    private static final String V178_SCRIPT =
            "db/migration/V178__backfill_salon_and_owner_region_from_settlement.sql";

    private String v178Sql;
    private UUID cityId;

    @BeforeEach
    void loadSql() throws IOException {
        try (InputStream in = new ClassPathResource(V178_SCRIPT).getInputStream()) {
            v178Sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        cityId = testCityId();
    }

    /** Runs the whole two-statement script; returns the total rows updated across both. */
    private int applyV178() {
        return jdbcTemplate.execute((java.sql.Connection conn) -> {
            try (var stmt = conn.createStatement()) {
                int total = 0;
                boolean isResultSet = stmt.execute(v178Sql);
                while (true) {
                    if (!isResultSet) {
                        int count = stmt.getUpdateCount();
                        if (count == -1) {
                            break;
                        }
                        total += count;
                    }
                    isResultSet = stmt.getMoreResults();
                }
                return total;
            } catch (SQLException e) {
                throw new IllegalStateException("V178 re-application failed", e);
            }
        });
    }

    private UUID insertUser(String role, String city, String region, UUID userCityId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, city, region, city_id) "
                        + "VALUES (?, ?, ?, ?, true, true, ?, ?, ?)",
                id, "v178-" + id + "@beautica.test", new BCryptPasswordEncoder(4).encode("test-password"),
                role, city, region, userCityId);
        return id;
    }

    private UUID insertSalon(UUID ownerId, String city, String region) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, city, region, is_active, city_id) VALUES (?, ?, 'V178', ?, ?, true, ?)",
                id, ownerId, city, region, cityId);
        return id;
    }

    private Map<String, Object> labels(String table, UUID id) {
        return jdbcTemplate.queryForMap("SELECT city, region FROM " + table + " WHERE id = ?", id);
    }

    private String settlementName() {
        return jdbcTemplate.queryForObject("SELECT name_uk FROM cities WHERE id = ?", String.class, cityId);
    }

    private String oblastName() {
        return jdbcTemplate.queryForObject(
                "SELECT o.name_uk FROM cities c JOIN oblasts o ON o.id = c.oblast_id WHERE c.id = ?",
                String.class, cityId);
    }

    @Test
    @DisplayName("salon: stale free-text city + region next to a valid city_id become the settlement + oblast names")
    void should_replaceStaleSalonLabels_when_cityIdPointsAtASettlement() {
        UUID ownerId = insertUser("SALON_OWNER", null, null, null);
        UUID salonId = insertSalon(ownerId, "Kyiv", "Kyiv oblast");

        applyV178();

        assertThat(labels("salons", salonId))
                .containsEntry("city", settlementName())
                .containsEntry("region", oblastName());
    }

    @Test
    @DisplayName("salon: a post-Phase-10.6 NULL region is filled")
    void should_fillNullSalonRegion_when_salonWasCreatedAfterPhase106() {
        UUID ownerId = insertUser("SALON_OWNER", null, null, null);
        UUID salonId = insertSalon(ownerId, settlementName(), null);

        applyV178();

        assertThat(labels("salons", salonId)).containsEntry("region", oblastName());
    }

    @Test
    @DisplayName("SALON_OWNER: stale city + region next to the salon-synced city_id become the settlement + oblast names")
    void should_replaceStaleOwnerLabels_when_ownerCityIdCameFromSalonCreate() {
        UUID ownerId = insertUser("SALON_OWNER", "Kyiv", "Kyiv oblast", cityId);

        applyV178();

        assertThat(labels("users", ownerId))
                .containsEntry("city", settlementName())
                .containsEntry("region", oblastName());
    }

    @Test
    @DisplayName("non-owner roles are out of scope and left byte-for-byte untouched")
    void should_leaveClientUntouched_when_roleIsNotSalonOwner() {
        UUID clientId = insertUser("CLIENT", "Kyiv", "Kyiv oblast", cityId);

        applyV178();

        assertThat(labels("users", clientId))
                .containsEntry("city", "Kyiv")
                .containsEntry("region", "Kyiv oblast");
    }

    @Test
    @DisplayName("idempotent: a second application touches zero rows")
    void should_updateNothing_when_appliedTwice() {
        UUID ownerId = insertUser("SALON_OWNER", "Kyiv", null, cityId);
        insertSalon(ownerId, "Kyiv", "Kyiv oblast");
        insertSalon(ownerId, settlementName(), oblastName());

        int firstPass = applyV178();
        int secondPass = applyV178();

        assertThat(firstPass)
                .as("the stale salon and the stale owner are written; the already-correct salon is skipped")
                .isEqualTo(2);
        assertThat(secondPass).isZero();
    }

    @Test
    @DisplayName("V178 is recorded as a successful Flyway migration")
    void should_recordV178AsSuccessful_when_containerBoots() {
        Boolean success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '178'", Boolean.class);

        assertThat(success).isTrue();
    }
}
