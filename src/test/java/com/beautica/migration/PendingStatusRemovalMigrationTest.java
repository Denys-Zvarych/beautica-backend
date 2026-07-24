package com.beautica.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V113 migration-safety regression test (track 24.x auto-confirm — Phase 24.7, QA pattern Q21:
 * a migration's correctness IS its contract, "Flyway applied with no error" proves nothing about
 * the data or the surviving constraints).
 *
 * <p><b>What V113 does.</b> Backfills every {@code PENDING} booking row to {@code CONFIRMED},
 * then rebuilds five DB objects that named {@code PENDING} in their predicate:
 * {@code chk_booking_status}, the {@code no_overlapping_bookings} EXCLUDE gist index,
 * {@code uq_client_idempotency_key_active}, and two partial indexes. See
 * {@code V113__remove_pending_booking_status.sql} for the full statement order and rationale.
 *
 * <p><b>Why a dedicated container.</b> {@link com.beautica.AbstractIntegrationTest}'s singleton
 * container is already fully migrated past V113 — {@code PENDING} can no longer be inserted
 * there, so the pre-migration state this test needs to reproduce (a legacy PENDING row + the OLD
 * constraint definitions) cannot be represented on that container. Mirrors
 * {@code V80MigrationIntegrationTest}: drive Flyway PROGRAMMATICALLY against a private container —
 * migrate to {@code target = 112} (the last schema state where PENDING is legal), seed a PENDING
 * booking, migrate to latest (V113), and assert the post-migration contract.
 */
@DisplayName("V113 migration — PENDING booking status removed, overlap/idempotency guards survive")
class PendingStatusRemovalMigrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private static UUID clientUserId;
    private static UUID masterUserId;
    private static UUID masterId;
    private static UUID masterServiceId;
    private static UUID pendingBookingId;

    @BeforeAll
    static void startContainerAndSeedUnderV112() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // ── Arrange: migrate ONLY up to V112 — the last state where PENDING is legal. ──
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("112")
                .load()
                .migrate();

        // Precondition: the OLD (6-value) CHECK is live, so a PENDING row can legally be seeded —
        // otherwise this test would not be reproducing the real pre-V113 hazard.
        seedGraphAndPendingBooking();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static void seedGraphAndPendingBooking() {
        clientUserId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'CLIENT', true, true)",
                clientUserId, "v113-client-" + clientUserId + "@beautica.test");

        masterUserId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', true, true, 'Марія', 'Левченко')",
                masterUserId, "v113-master-" + masterUserId + "@beautica.test");

        masterId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                masterId, masterUserId);

        UUID serviceDefId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Манікюр', ?, 60, 350.00, 0, true, NOW(), NOW())",
                serviceDefId, masterUserId, resolveServiceTypeId());

        masterServiceId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        pendingBookingId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day 1 hour', "
                        + "350.00, 60, 0, NOW(), NOW())",
                pendingBookingId, clientUserId, masterId, masterServiceId);

        String seededStatus = jdbc.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, pendingBookingId);
        assertThat(seededStatus)
                .as("precondition: the legacy PENDING row is seeded under the pre-V113 (V112) schema")
                .isEqualTo("PENDING");
    }

    private static UUID resolveServiceTypeId() {
        return jdbc.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    @Test
    @DisplayName("V113 backfills the legacy PENDING row to CONFIRMED, and the CHECK/EXCLUDE/idempotency "
            + "guards all survive the constraint rebuild")
    void should_backfillPendingToConfirmed_andPreserveOverlapAndIdempotencyGuards_when_v113Runs() {
        // ── Act: run the remaining migration (V113) over a DB that holds the legacy PENDING row. ──
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // (1) the legacy row was backfilled to CONFIRMED.
        String statusAfterMigration = jdbc.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, pendingBookingId);
        assertThat(statusAfterMigration)
                .as("V113 must backfill every PENDING row to CONFIRMED before rebuilding the CHECK")
                .isEqualTo("CONFIRMED");

        // (2) chk_booking_status no longer permits PENDING.
        UUID rejectedProbeId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', NOW() + INTERVAL '2 days', NOW() + INTERVAL '2 days 1 hour', "
                        + "350.00, 60, 0, NOW(), NOW())",
                rejectedProbeId, clientUserId, masterId, masterServiceId))
                .as("chk_booking_status must reject a fresh PENDING insert after V113")
                .isInstanceOf(DataIntegrityViolationException.class);

        // (3) no_overlapping_bookings EXCLUDE still rejects an overlapping CONFIRMED insert for the
        // SAME master — the double-booking guard survived the WHERE-predicate narrowing.
        UUID overlappingProbeId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', NOW() + INTERVAL '1 day', NOW() + INTERVAL '1 day 1 hour', "
                        + "350.00, 60, 0, NOW(), NOW())",
                overlappingProbeId, clientUserId, masterId, masterServiceId))
                .as("no_overlapping_bookings must still reject a CONFIRMED booking overlapping the "
                        + "backfilled booking's window on the same master")
                .isInstanceOf(DataIntegrityViolationException.class);

        // (4) uq_client_idempotency_key_active still rejects a duplicate active idempotency key for
        // the same client — the replay-protection guard survived the WHERE-predicate narrowing.
        String idempotencyKey = "v113-idem-" + UUID.randomUUID();
        UUID firstIdemBookingId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "idempotency_key, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', NOW() + INTERVAL '5 days', NOW() + INTERVAL '5 days 1 hour', "
                        + "350.00, 60, 0, ?, NOW(), NOW())",
                firstIdemBookingId, clientUserId, masterId, masterServiceId, idempotencyKey);

        UUID duplicateIdemBookingId = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "idempotency_key, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', NOW() + INTERVAL '6 days', NOW() + INTERVAL '6 days 1 hour', "
                        + "350.00, 60, 0, ?, NOW(), NOW())",
                duplicateIdemBookingId, clientUserId, masterId, masterServiceId, idempotencyKey))
                .as("uq_client_idempotency_key_active must still reject a duplicate active idempotency "
                        + "key for the same client after V113's WHERE-predicate rebuild")
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
