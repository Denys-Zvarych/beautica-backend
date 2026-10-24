package com.beautica.booking.retention;

import com.beautica.TestPostgresImage;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.beautica.booking.retention.GuestPiiFixtures.PHONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 381 — V202 over a database that already holds guest data. A private container is migrated to V200
 * (V201 is reserved by phase 378), every guest-row shape is seeded, then Flyway migrates to head ONCE
 * (same recipe as {@link V199GuestPiiRetentionMigrationTest}).
 *
 * <p>Lives beside that test because {@link GuestPiiFixtures} is package-private.
 */
@DisplayName("V202 migration — guest privacy consent columns over existing rows (Phase 381)")
class V202GuestPrivacyConsentMigrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static GuestPiiFixtures.Provider provider;
    private static final Map<String, UUID> BOOKINGS = new LinkedHashMap<>();
    private static UUID legacyVisit;
    private static final Map<UUID, Map<String, Object>> BOOKINGS_BEFORE = new HashMap<>();
    private static Map<String, Object> visitBefore;

    @BeforeAll
    static void migrateTo200SeedLegacyRowsThenMigrateToHead() {
        postgres = new PostgreSQLContainer<>(TestPostgresImage.IMAGE);
        postgres.start();
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("200")).load().migrate();

        GuestPiiFixtures fx = new GuestPiiFixtures(jdbc);
        provider = fx.createIndependentMaster();
        UUID clientId = fx.createClient();
        BOOKINGS.put("legacy link live", fx.link(provider, "CONFIRMED", NOW.plusDays(3), PHONE, UUID.randomUUID()));
        BOOKINGS.put("legacy link completed", fx.link(provider, "COMPLETED", NOW.minusMonths(2), PHONE, null));
        BOOKINGS.put("staff walk-in", fx.staffWalkIn(provider, "COMPLETED", NOW.minusMonths(3), PHONE));
        BOOKINGS.put("app", fx.app(provider, "COMPLETED", NOW.minusMonths(4), clientId));
        legacyVisit = fx.appointment("LINK", "CONFIRMED", PHONE, UUID.randomUUID(), null);
        BOOKINGS.put("legacy link visit item",
                fx.visitItem(provider, legacyVisit, "LINK", "CONFIRMED", NOW.plusDays(5), PHONE));
        BOOKINGS.values().forEach(id -> BOOKINGS_BEFORE.put(id, fx.booking(id)));
        visitBefore = fx.appointmentRow(legacyVisit);

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("V202 is recorded as successful over a populated database")
    void should_recordV202Successful_when_guestRowsExistBeforeMigration() {
        Boolean success = jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '202'", Boolean.class);

        assertThat(success).isTrue();
    }

    @Test
    @DisplayName("both tables get the two consent columns, nullable, with the declared types")
    void should_addNullableConsentColumns_when_migrated() {
        for (String table : List.of("bookings", "appointments")) {
            List<Map<String, Object>> columns = jdbc.queryForList("""
                    SELECT column_name, data_type, is_nullable, character_maximum_length
                      FROM information_schema.columns
                     WHERE table_name = ? AND column_name LIKE 'guest_privacy_%'
                     ORDER BY column_name
                    """, table);

            assertThat(columns).as(table).hasSize(2);
            assertThat(columns.get(0)).containsEntry("column_name", "guest_privacy_accepted_at")
                    .containsEntry("data_type", "timestamp with time zone").containsEntry("is_nullable", "YES");
            assertThat(columns.get(1)).containsEntry("column_name", "guest_privacy_policy_version")
                    .containsEntry("is_nullable", "YES").containsEntry("character_maximum_length", 32);
        }
    }

    @Test
    @DisplayName("both pair CHECKs exist and are validated")
    void should_haveValidatedPairChecks_when_migrated() {
        List<Map<String, Object>> constraints = jdbc.queryForList("""
                SELECT conname, convalidated FROM pg_constraint
                 WHERE conname IN ('ck_bookings_guest_privacy_pair', 'ck_appointments_guest_privacy_pair')
                """);

        assertThat(constraints).hasSize(2)
                .allSatisfy(c -> assertThat(c.get("convalidated")).as("%s", c.get("conname")).isEqualTo(true));
    }

    @Test
    @DisplayName("legacy rows are byte-identical apart from the two new columns, which are NULL")
    void should_leaveLegacyRowsUntouched_when_migrated() {
        BOOKINGS.forEach((shape, id) -> {
            Map<String, Object> after = new HashMap<>(jdbc.queryForMap("SELECT * FROM bookings WHERE id = ?", id));
            assertThat(after.remove("guest_privacy_accepted_at")).as("consent of %s", shape).isNull();
            assertThat(after.remove("guest_privacy_policy_version")).as("version of %s", shape).isNull();
            assertThat(after.remove("guest_pii_anonymised_at")).as("marker of %s", shape).isNull();
            Map<String, Object> before = new HashMap<>(BOOKINGS_BEFORE.get(id));
            before.remove("guest_pii_anonymised_at");
            assertThat(after).as("booking shape: %s", shape).isEqualTo(before);
        });
        Map<String, Object> visit = new HashMap<>(jdbc.queryForMap("SELECT * FROM appointments WHERE id = ?", legacyVisit));
        assertThat(visit.remove("guest_privacy_accepted_at")).isNull();
        assertThat(visit.remove("guest_privacy_policy_version")).isNull();
        assertThat(visit.remove("guest_pii_anonymised_at")).isNull();
        Map<String, Object> visitExpected = new HashMap<>(visitBefore);
        visitExpected.remove("guest_pii_anonymised_at");
        assertThat(visit).isEqualTo(visitExpected);
    }

    @Test
    @DisplayName("the bookings pair CHECK rejects (time, NULL) and (NULL, version), accepts both-set and both-NULL")
    void should_enforcePairOnBookings_when_consentWritten() {
        UUID booking = BOOKINGS.get("legacy link completed");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE bookings SET guest_privacy_accepted_at = NOW() WHERE id = ?", booking))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_bookings_guest_privacy_pair");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE bookings SET guest_privacy_policy_version = '2026-10-20' WHERE id = ?", booking))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_bookings_guest_privacy_pair");
        assertThat(jdbc.update("UPDATE bookings SET guest_privacy_accepted_at = NOW(), "
                + "guest_privacy_policy_version = '2026-10-20' WHERE id = ?", booking)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE bookings SET guest_privacy_accepted_at = NULL, "
                + "guest_privacy_policy_version = NULL WHERE id = ?", booking)).isEqualTo(1);
    }

    @Test
    @DisplayName("the appointments pair CHECK rejects (time, NULL) and (NULL, version), accepts both-set and both-NULL")
    void should_enforcePairOnAppointments_when_consentWritten() {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE appointments SET guest_privacy_accepted_at = NOW() WHERE id = ?", legacyVisit))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_appointments_guest_privacy_pair");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE appointments SET guest_privacy_policy_version = '2026-10-20' WHERE id = ?", legacyVisit))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_appointments_guest_privacy_pair");
        assertThat(jdbc.update("UPDATE appointments SET guest_privacy_accepted_at = NOW(), "
                + "guest_privacy_policy_version = '2026-10-20' WHERE id = ?", legacyVisit)).isEqualTo(1);
        assertThat(jdbc.update("UPDATE appointments SET guest_privacy_accepted_at = NULL, "
                + "guest_privacy_policy_version = NULL WHERE id = ?", legacyVisit)).isEqualTo(1);
    }
}
