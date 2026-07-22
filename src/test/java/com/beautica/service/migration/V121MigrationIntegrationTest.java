package com.beautica.service.migration;

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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V121 migration-safety regression test (QA pattern Q21 — a migration's correctness IS its
 * contract; "Flyway applied with no error" proves nothing about the rows it rewrote).
 *
 * <p>V121 is not a schema-only change. Before creating the partial unique index
 * {@code ux_service_def_owner_service_type_active} it <b>mutates pre-existing production
 * rows</b>: step 0 snapshots every colliding loser into a permanent {@code v121_dedup_log},
 * step 1 soft-deactivates exactly those rows, step 1b recomputes
 * {@code masters.min_effective_price} for exactly the masters step 1 touched. Every one of
 * those steps is silent and irreversible in production, and none of them is exercised by the
 * ordinary Testcontainers suite: {@link com.beautica.AbstractIntegrationTest} runs Flyway on an
 * empty database at context start, so V121 there always finds zero collisions and the entire
 * data-repair path executes as a no-op. The one collision group that motivated the migration
 * lives only in the developer's local database.
 *
 * <p><b>Approach.</b> A private container is migrated to <em>V120 only</em>, colliding rows are
 * seeded at that schema version, and V121 is then applied. That ordering — impossible with the
 * shared singleton container, where Flyway has already finished before any test can insert — is
 * the whole reason this class exists and owns its own container.
 *
 * <p><b>The fixture</b> (one INDEPENDENT_MASTER holding a 3-row collision, plus an untouched
 * control master):
 * <ul>
 *   <li>{@code bookedDefId} — oldest by every recency tie-break, but the only one carrying a
 *       booking. Must SURVIVE: the {@code EXISTS(bookings)} predicate leads the ORDER BY
 *       precisely so a definition a real client transacted against is never the one silently
 *       deactivated. Making it the oldest is what gives that assertion teeth — under the plain
 *       {@code updated_at DESC} tie-break it would have lost.</li>
 *   <li>{@code cheapestDefId} — no bookings, and priced BELOW the survivor, so it is the master's
 *       current {@code min_effective_price}. Deactivating it makes the denormalised column stale,
 *       which is exactly what step 1b must repair.</li>
 *   <li>{@code newestDefId} — no bookings, newest {@code updated_at}: the row a booking-blind
 *       survivor rule would have kept.</li>
 *   <li>a control master whose {@code min_effective_price} is seeded deliberately WRONG and who
 *       has no collisions. Step 1b must leave it wrong — its scope is the log, not "every master
 *       with some inactive definition". A regression to the unbounded superset would silently
 *       "fix" it, and the Flyway transaction that gates Railway startup would grow with the
 *       lifetime accumulation of ordinary soft-deletes.</li>
 * </ul>
 */
@DisplayName("V121 migration — dedupes active service definitions, preserves booked rows, repairs min_effective_price")
class V121MigrationIntegrationTest {

    private static final String DUPLICATE_INDEX = "ux_service_def_owner_service_type_active";

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    /** The collision group's owner. */
    private static UUID collidingMasterId;
    /** The control master — no collisions, deliberately stale min_effective_price. */
    private static UUID untouchedMasterId;

    private static UUID collidingTypeId;
    private static UUID otherTypeId;

    private static UUID bookedDefId;
    private static UUID cheapestDefId;
    private static UUID newestDefId;

    @BeforeAll
    static void migrateToV120_seedCollisions_thenApplyV121() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // ── Arrange, part 1: the schema as it stood the moment before V121 shipped. ──
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("120")
                .load()
                .migrate();

        seedCollidingRows();

        // ── Act: apply V121 (and anything after it) onto that populated database. ──
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    private static void seedCollidingRows() {
        List<UUID> typeIds = jdbc.queryForList(
                "SELECT id FROM service_types WHERE is_active = TRUE ORDER BY slug LIMIT 2",
                UUID.class);
        assertThat(typeIds)
                .as("V75+ seeds the service-type taxonomy; the fixture needs two real types")
                .hasSize(2);
        collidingTypeId = typeIds.get(0);
        otherTypeId = typeIds.get(1);

        collidingMasterId = insertIndependentMaster("dup-master-" + UUID.randomUUID() + "@beautica.test");
        untouchedMasterId = insertIndependentMaster("control-master-" + UUID.randomUUID() + "@beautica.test");

        OffsetDateTime now = OffsetDateTime.now();

        // The survivor: OLDEST, but the only one with booking history.
        bookedDefId = insertActiveDefinition(collidingMasterId, collidingTypeId,
                "Класичне нарощення (booked)", "500.00", now.minusDays(30));
        // Loser 1 — cheapest, so it is the master's current min_effective_price.
        cheapestDefId = insertActiveDefinition(collidingMasterId, collidingTypeId,
                "Класичне нарощення (cheap twin)", "100.00", now.minusDays(10));
        // Loser 2 — newest; the row a booking-blind survivor rule would have kept.
        newestDefId = insertActiveDefinition(collidingMasterId, collidingTypeId,
                "Класичне нарощення (newest twin)", "700.00", now.minusMinutes(5));

        UUID bookedAssignmentId = insertActiveAssignment(collidingMasterId, bookedDefId);
        insertActiveAssignment(collidingMasterId, cheapestDefId);
        insertActiveAssignment(collidingMasterId, newestDefId);

        insertCompletedBooking(collidingMasterId, bookedAssignmentId);

        // The control master: a single, non-colliding definition.
        UUID controlDefId = insertActiveDefinition(untouchedMasterId, otherTypeId,
                "Педикюр", "250.00", now.minusDays(1));
        insertActiveAssignment(untouchedMasterId, controlDefId);

        // Stale on purpose. The colliding master's 100.00 is genuinely correct pre-migration and
        // becomes stale only because V121 deactivates that row; the control's 999.99 was never
        // correct, so if it changes, step 1b ran outside its declared scope.
        jdbc.update("UPDATE masters SET min_effective_price = 100.00 WHERE id = ?", collidingMasterId);
        jdbc.update("UPDATE masters SET min_effective_price = 999.99 WHERE id = ?", untouchedMasterId);

        // Precondition — the fixture IS the entire active population, so the log assertions below
        // can be exact rather than "contains". A future seed migration adding its own colliding
        // service_definitions would trip this rather than silently weakening the test.
        Long activeDefs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE is_active = TRUE", Long.class);
        assertThat(activeDefs)
                .as("no migration seeds service_definitions; the fixture must be the whole population")
                .isEqualTo(4L);
    }

    private static UUID insertIndependentMaster(String email) {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, 'INDEPENDENT_MASTER')",
                userId, email, "$2a$04$notusedhere");
        UUID masterId = UUID.randomUUID();
        jdbc.update("INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', TRUE, now(), now())",
                masterId, userId);
        return masterId;
    }

    private static UUID insertActiveDefinition(
            UUID ownerId, UUID serviceTypeId, String name, String basePrice, OffsetDateTime touchedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO service_definitions "
                        + "(id, owner_type, owner_id, name, category, base_duration_minutes, base_price, "
                        + " price_type, is_active, service_type_id, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, ?, 'NAIL_SERVICE', 60, ?, 'FIXED', TRUE, ?, ?, ?)",
                id, ownerId, name, new BigDecimal(basePrice), serviceTypeId, touchedAt, touchedAt);
        return id;
    }

    private static UUID insertActiveAssignment(UUID masterId, UUID serviceDefId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO master_services (id, master_id, service_def_id, is_active) VALUES (?, ?, ?, TRUE)",
                id, masterId, serviceDefId);
        return id;
    }

    private static void insertCompletedBooking(UUID masterId, UUID masterServiceId) {
        UUID clientId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, 'CLIENT')",
                clientId, "client-" + UUID.randomUUID() + "@beautica.test", "$2a$04$notusedhere");
        // COMPLETED, so the no_overlapping_bookings exclusion constraint (PENDING/CONFIRMED only)
        // is irrelevant to the fixture.
        jdbc.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + " starts_at, ends_at, price_at_booking, duration_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', now() - interval '2 days', "
                        + " now() - interval '2 days' + interval '1 hour', 500.00, 60, now(), now())",
                UUID.randomUUID(), clientId, masterId, masterServiceId);
    }

    // ── Step 0 — the forensic log ─────────────────────────────────────────────

    @Test
    @DisplayName("v121_dedup_log records exactly the two losers, never the survivor")
    void should_logExactlyTheDeactivatedRows_when_migrationDedupes() {
        List<UUID> loggedIds = jdbc.queryForList("SELECT id FROM v121_dedup_log", UUID.class);

        // Exactness in both directions is the point: a log that also named the survivor would
        // make step 1 (which joins this table) deactivate the entire collision group, wiping the
        // owner's service outright, and every "the index now holds" assertion would still pass.
        assertThat(loggedIds)
                .as("the log is the audit trail AND step 1's driving set — it must name the losers only")
                .containsExactlyInAnyOrder(cheapestDefId, newestDefId);
    }

    @Test
    @DisplayName("v121_dedup_log carries the owner + service-type key and a deactivation timestamp for each row")
    void should_recordOwnerAndTypeAndTimestamp_when_rowIsLogged() {
        var row = jdbc.queryForMap(
                "SELECT owner_type, owner_id, service_type_id, deactivated_at FROM v121_dedup_log WHERE id = ?",
                cheapestDefId);

        // Without these columns the log cannot answer "which service of mine disappeared", which
        // is the only support question it exists to answer.
        assertThat(row.get("owner_type")).isEqualTo("INDEPENDENT_MASTER");
        assertThat(row.get("owner_id")).isEqualTo(collidingMasterId);
        assertThat(row.get("service_type_id")).isEqualTo(collidingTypeId);
        assertThat(row.get("deactivated_at")).as("deactivated_at must be populated").isNotNull();
    }

    // ── Step 1 — survivor selection ───────────────────────────────────────────

    @Test
    @DisplayName("the definition carrying booking history survives even though it is the oldest")
    void should_keepTheBookedDefinitionActive_when_collisionIsResolved() {
        // The EXISTS(bookings) predicate LEADS the ORDER BY. Drop it and updated_at DESC wins,
        // making newestDefId the survivor and silently detaching a real client's booking history
        // from the owner's live menu.
        assertThat(isActive(bookedDefId))
                .as("booked definition must survive — it is the one a client actually transacted against")
                .isTrue();
        assertThat(isActive(newestDefId))
                .as("newest untouched twin must lose despite winning every recency tie-break")
                .isFalse();
        assertThat(isActive(cheapestDefId)).isFalse();
    }

    @Test
    @DisplayName("deactivation is soft — the losing rows still exist and keep their bookings reachable")
    void should_preserveLosingRows_when_theyAreDeactivated() {
        Long surviving = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_definitions WHERE id IN (?, ?, ?)", Long.class,
                bookedDefId, cheapestDefId, newestDefId);

        // A DELETE would have cascaded through master_services into bookings. The migration must
        // mirror the application's own soft delete, nothing harsher.
        assertThat(surviving).as("V121 must not delete rows").isEqualTo(3L);
    }

    // ── Step 1b — denormalised price repair, scoped to the affected masters ───

    @Test
    @DisplayName("min_effective_price is recomputed for the master whose cheapest definition was deactivated")
    void should_recomputeMinEffectivePrice_when_theCheapestDefinitionWasDeactivated() {
        // 100.00 was correct until step 1 deactivated the row it came from; the live minimum over
        // the remaining active definitions is the survivor's 500.00. Leaving 100.00 in place would
        // keep the master in search results for a price band they no longer offer.
        assertThat(minEffectivePrice(collidingMasterId))
                .as("stale 100.00 must be replaced by the surviving definition's price")
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("min_effective_price is left alone for a master the dedup never touched")
    void should_leaveMinEffectivePriceUntouched_when_masterHadNoCollision() {
        // Seeded deliberately wrong (999.99 vs the real 250.00). Step 1b joins v121_dedup_log, so
        // this master is out of scope and the wrong value must survive. If it were "corrected"
        // here, the UPDATE would be running over an unbounded superset that grows with every
        // ordinary soft-delete for the app's whole lifetime — inside the Flyway transaction that
        // gates Railway startup.
        assertThat(minEffectivePrice(untouchedMasterId))
                .as("step 1b's scope is the dedup log, not every master with an inactive definition")
                .isEqualByComparingTo("999.99");
    }

    // ── Step 2 — the constraint itself ────────────────────────────────────────

    @Test
    @DisplayName("the partial unique index exists and rejects a second ACTIVE definition of the same type")
    void should_rejectSecondActiveDefinition_when_indexIsInPlace() {
        assertThatThrownBy(() -> insertActiveDefinition(
                collidingMasterId, collidingTypeId, "Класичне нарощення (post-migration)", "800.00",
                OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(DUPLICATE_INDEX);
    }

    @Test
    @DisplayName("the index is partial — the deactivated twins do not block re-creating the type elsewhere")
    void should_allowActiveDefinition_when_onlyInactiveTwinsExistForThatOwner() {
        // The deduped losers are inactive rows of collidingTypeId. A fresh owner must still be
        // able to offer that type; and more importantly the partial predicate is what keeps the
        // deactivated rows from becoming permanent tombstones.
        UUID freshMasterId = insertIndependentMaster("fresh-" + UUID.randomUUID() + "@beautica.test");

        UUID created = insertActiveDefinition(
                freshMasterId, collidingTypeId, "Класичне нарощення", "450.00", OffsetDateTime.now());

        assertThat(isActive(created)).isTrue();
    }

    // ── Idempotence ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("re-running Flyway does not re-apply V121 or rewrite the forensic log")
    void should_notRewriteTheLog_when_migrateIsRunAgain() {
        Long before = jdbc.queryForObject("SELECT COUNT(*) FROM v121_dedup_log", Long.class);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        // v121_dedup_log is created with CREATE TABLE ... AS, so a re-applied V121 would fail
        // outright ("relation already exists") — a Railway startup crash-loop. Flyway's version
        // ledger is what prevents that, and the log staying byte-identical is the observable proof
        // the repair ran exactly once.
        Long after = jdbc.queryForObject("SELECT COUNT(*) FROM v121_dedup_log", Long.class);
        assertThat(after).isEqualTo(before);

        Integer v121Rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '121'", Integer.class);
        assertThat(v121Rows).as("V121 must be recorded exactly once").isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static boolean isActive(UUID serviceDefId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT is_active FROM service_definitions WHERE id = ?", Boolean.class, serviceDefId));
    }

    private static BigDecimal minEffectivePrice(UUID masterId) {
        return jdbc.queryForObject(
                "SELECT min_effective_price FROM masters WHERE id = ?", BigDecimal.class, masterId);
    }
}
