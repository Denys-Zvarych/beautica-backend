package com.beautica.booking.retention;

import com.beautica.TestPostgresImage;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

/**
 * Phase 373 acceptance "V199 applies on a database with existing guest data; constraint validation
 * succeeds" (Q21). {@code AbstractIntegrationTest} migrates an EMPTY database, so V199's DROP + ADD
 * of {@code chk_bookings_guest_fields} / {@code chk_appointment_guest_fields} has never been
 * validated against pre-existing rows there.
 *
 * <p>Mirrors {@code V197PendingSuggestionCollapseMigrationTest}: a private container is migrated to
 * V198, every guest-column shape that V162 admits is seeded, then Flyway migrates to head ONCE.
 */
@DisplayName("V199 migration — applies over existing guest/walk-in/APP/detached rows and leaves them intact")
class V199GuestPiiRetentionMigrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.now(ZoneOffset.UTC);

    private static PostgreSQLContainer<?> postgres;
    private static JdbcTemplate jdbc;
    private static final Map<String, UUID> BOOKINGS = new LinkedHashMap<>();
    private static final Map<String, UUID> VISITS = new LinkedHashMap<>();
    private static final Map<UUID, Map<String, Object>> BOOKINGS_BEFORE = new HashMap<>();
    private static final Map<UUID, Map<String, Object>> VISITS_BEFORE = new HashMap<>();

    @BeforeAll
    static void migrateTo198SeedLegacyRowsThenMigrateToHead() {
        postgres = new PostgreSQLContainer<>(TestPostgresImage.IMAGE);
        postgres.start();
        DataSource dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("198")).load().migrate();

        GuestPiiFixtures fx = new GuestPiiFixtures(jdbc);
        GuestPiiFixtures.Provider provider = fx.createIndependentMaster();
        UUID clientId = fx.createClient();

        BOOKINGS.put("link live with token", fx.link(provider, "CONFIRMED", NOW.plusDays(3), PHONE, UUID.randomUUID()));
        BOOKINGS.put("link cancelled, token consumed", fx.link(provider, "CANCELLED", NOW.minusDays(5), PHONE, null));
        BOOKINGS.put("link completed 13 months ago", fx.link(provider, "COMPLETED", NOW.minusMonths(13), PHONE, null));
        BOOKINGS.put("link declined", fx.link(provider, "DECLINED", NOW.minusDays(9), PHONE, null));
        BOOKINGS.put("staff walk-in", fx.staffWalkIn(provider, "COMPLETED", NOW.minusMonths(14), PHONE));
        BOOKINGS.put("staff registered client", fx.staffRegistered(provider, "COMPLETED", NOW.minusDays(2), clientId));
        BOOKINGS.put("app", fx.app(provider, "COMPLETED", NOW.minusMonths(15), clientId));
        UUID detached = fx.app(provider, "COMPLETED", NOW.minusMonths(16), clientId);
        jdbc.update("UPDATE bookings SET client_id = NULL, client_detached_at = NOW(), "
                + "guest_name = 'Видалений клієнт' WHERE id = ?", detached);
        BOOKINGS.put("detached ex-client", detached);

        UUID linkVisit = fx.appointment("LINK", "CONFIRMED", PHONE, UUID.randomUUID(), null);
        BOOKINGS.put("link visit item", fx.visitItem(provider, linkVisit, "LINK", "CONFIRMED", NOW.plusDays(4), PHONE));
        VISITS.put("link visit", linkVisit);
        UUID staffVisit = fx.appointment("STAFF", "COMPLETED", PHONE, null, provider.ownerUserId());
        BOOKINGS.put("staff visit item", fx.visitItem(provider, staffVisit, "STAFF", "COMPLETED",
                NOW.minusMonths(13), PHONE));
        VISITS.put("staff visit", staffVisit);

        BOOKINGS.values().forEach(id -> BOOKINGS_BEFORE.put(id, fx.booking(id)));
        VISITS.values().forEach(id -> VISITS_BEFORE.put(id, fx.appointmentRow(id)));

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    @DisplayName("V199 is recorded as successful over a populated bookings/appointments table")
    void should_recordV199Successful_when_guestRowsExistBeforeMigration() {
        Boolean success = jdbc.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = '199'", Boolean.class);

        assertThat(success).isTrue();
    }

    @Test
    @DisplayName("every guest CHECK on bookings and appointments is VALIDATED (no NOT VALID left behind)")
    void should_validateEveryGuestConstraint_when_migrated() {
        List<Map<String, Object>> constraints = jdbc.queryForList("""
                SELECT conname, convalidated FROM pg_constraint
                 WHERE conrelid IN ('bookings'::regclass, 'appointments'::regclass)
                   AND conname IN ('chk_bookings_guest_fields', 'chk_bookings_guest_pii_anonymised_scope',
                                   'chk_appointment_guest_fields', 'chk_appointments_guest_pii_anonymised_scope')
                """);

        assertThat(constraints).hasSize(4)
                .allSatisfy(c -> assertThat(c.get("convalidated")).as("%s", c.get("conname")).isEqualTo(true));
    }

    @Test
    @DisplayName("pre-existing rows are byte-identical apart from the new marker, which is NULL")
    void should_leaveExistingRowsIntact_when_migrated() {
        BOOKINGS.forEach((shape, id) -> {
            Map<String, Object> after = new HashMap<>(jdbc.queryForMap("SELECT * FROM bookings WHERE id = ?", id));
            assertThat(after.remove("guest_pii_anonymised_at")).as("marker of %s", shape).isNull();
            assertThat(after).as("booking shape: %s", shape).isEqualTo(BOOKINGS_BEFORE.get(id));
        });
        VISITS.forEach((shape, id) -> {
            Map<String, Object> after = new HashMap<>(jdbc.queryForMap("SELECT * FROM appointments WHERE id = ?", id));
            assertThat(after.remove("guest_pii_anonymised_at")).as("marker of %s", shape).isNull();
            assertThat(after).as("visit shape: %s", shape).isEqualTo(VISITS_BEFORE.get(id));
        });
    }

    @Test
    @DisplayName("a legacy LINK row, a legacy STAFF walk-in and a legacy visit header accept the D4 anonymisation")
    void should_acceptAnonymisation_when_legacyGuestRowsWiped() {
        int bookingsWiped = jdbc.update("""
                UPDATE bookings SET guest_name = ?, guest_surname = NULL, guest_phone = NULL,
                       client_comment = NULL, client_cancellation_note = NULL, cancel_token = NULL,
                       guest_pii_anonymised_at = NOW()
                 WHERE id IN (?, ?)
                """, GuestPii.ANONYMISED_NAME,
                BOOKINGS.get("link completed 13 months ago"), BOOKINGS.get("staff walk-in"));
        int visitsWiped = jdbc.update("""
                UPDATE appointments SET guest_name = ?, guest_surname = NULL, guest_phone = NULL,
                       client_comment = NULL, client_cancellation_note = NULL, cancel_token = NULL,
                       guest_pii_anonymised_at = NOW()
                 WHERE id = ?
                """, GuestPii.ANONYMISED_NAME, VISITS.get("staff visit"));

        assertThat(bookingsWiped).isEqualTo(2);
        assertThat(visitsWiped).isEqualTo(1);
    }

    @Test
    @DisplayName("the job and erase indexes exist and exclude detached ex-client rows")
    void should_createPartialIndexes_when_migrated() {
        Map<String, String> defs = new HashMap<>();
        jdbc.query("SELECT indexname, indexdef FROM pg_indexes WHERE indexname IN "
                        + "('idx_bookings_guest_pii_due', 'idx_appointments_guest_pii_due', "
                        + "'idx_bookings_guest_phone_open', 'idx_appointments_guest_phone_open')",
                rs -> {
                    defs.put(rs.getString(1), rs.getString(2));
                });

        assertThat(defs).hasSize(4);
        assertThat(defs.values()).allSatisfy(def -> assertThat(def)
                .contains("client_id IS NULL")
                .contains("guest_pii_anonymised_at IS NULL")
                .contains("client_detached_at IS NULL"));
    }
}
