package com.beautica.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@code V164__backfill_salon_owned_master_service_definitions.sql} (Phase 303).
 *
 * <p><b>What V164 does.</b> Phase 302 (commit {@code 4afede9}) changed
 * {@code POST /salons/{salonId}/masters/{masterId}/services/bulk} to persist NEW definitions
 * {@code ownerType='SALON'}. Every definition created through that endpoint BEFORE 302 shipped
 * still sits {@code ownerType='INDEPENDENT_MASTER', ownerId=<master.id>} for a salon-bound master
 * ({@code masters.salon_id IS NOT NULL}), and is therefore permanently invisible in
 * {@code GET /salons/{salonId}/services}. V164 merges those rows into the salon's catalogue
 * per (salon, service type) group — see the phase doc's D1-D7 for the full rationale, in
 * particular D2 (survivor selection), D3 (deactivate, never delete), D4 (dedupe a colliding
 * {@code master_services} assignment instead of repointing it) and D5 (carry a per-master price/
 * duration divergence into the surviving assignment's override columns).
 *
 * <p><b>Why a dedicated container, driven programmatically (mirrors {@code
 * V146RefreshTokenFamilyIdBackfillMigrationTest}).</b> {@link com.beautica.AbstractIntegrationTest}'s
 * singleton container always boots fully migrated PAST V164 on an EMPTY schema, so V164 runs there
 * as the permanent, expected no-op the phase doc describes ("on a fresh database V164 is a no-op").
 * There is never a pre-V164 legacy row for it to act on inside that container. This class instead:
 * <ol>
 *   <li>migrates a private container all the way to the latest version ONCE in
 *       {@code @BeforeAll} — this exercises V164 as a genuine Flyway migration on an empty DB (the
 *       {@link #should_recordV164AsSuccessfulNoOp_onFreshDatabase()} test below) and gives every
 *       later test a fully-formed schema to seed pre-migration-shaped rows into;</li>
 *   <li>for every numbered test case, seeds fixtures in the EXACT pre-303 shape (an active
 *       {@code INDEPENDENT_MASTER}-owned {@code service_definitions} row for a salon-bound master),
 *       then re-applies V164's SQL body directly via one {@code Statement.execute(...)} call
 *       (a single PostgreSQL simple-query message runs the whole multi-statement script as one
 *       implicit transaction, so the two {@code TEMP TABLE}s it builds never leak across a pooled
 *       connection boundary) — Flyway itself will not re-run an already-applied version, but D6
 *       requires the SQL to behave correctly under repeated application, and this is how the test
 *       harness proves it.</li>
 * </ol>
 *
 * <p>Case 10 ("GET /salons/{salonId}/services lists the backfilled service") is asserted at IT
 * level in {@code BulkServiceSetupIntegrationTest} per the phase doc's own placement, not here —
 * this class has no HTTP layer.
 */
@DisplayName("V164 migration — merge pre-302 master-owned salon service definitions into the salon catalogue")
class V164SalonOwnedBackfillMigrationTest {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static String v164Sql;

    @BeforeAll
    static void startContainerMigrateToLatestAndLoadSql() throws IOException {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();

        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // Full chain UP TO AND INCLUDING V164, against an EMPTY schema — proves the "fresh DB =>
        // no-op" half of D6 and records V164 in flyway_schema_history for the FlywayHistory test
        // below. Capped at V164 (Phase 311 fix): this class re-applies V164's raw SQL body a
        // SECOND time inside several test methods (applyV164()) against synthetic fixture rows
        // that deliberately diverge in price — exactly the shape V164's repoint logic writes a
        // bare price_override for (case 7). Migrating past V165 first would let that later
        // migration's chk_master_service_price_mode CHECK see a re-applied V164 write a partial
        // band (price_override set, price_type_override untouched — V164 predates that column and
        // can never know about it) and reject it with a DataIntegrityViolationException that has
        // nothing to do with V164 itself. Real deployments never hit this: V164 ran once, when the
        // schema head truly WAS V164, and V165's own D8 backfill (which V164 cannot see either)
        // is what keeps production data legal before the CHECK is added. Capping the target here
        // makes the test's schema match what V164 actually ran against.
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(org.flywaydb.core.api.MigrationVersion.fromVersion("164"))
                .load()
                .migrate();

        try (InputStream in = new ClassPathResource(
                "db/migration/V164__backfill_salon_owned_master_service_definitions.sql").getInputStream()) {
            v164Sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * Re-applies V164's SQL body directly. One {@link java.sql.Statement#execute(String)} call
     * carrying the WHOLE multi-statement script is a single PostgreSQL simple-query message, which
     * the server executes as one implicit transaction — so {@code v164_group_survivor} and
     * {@code v164_loser_map} (both plain {@code TEMP TABLE}, session-scoped) are visible to every
     * statement in the script regardless of connection pooling, and the {@code SET LOCAL} timeouts
     * apply to that same implicit transaction.
     */
    private static void applyV164() {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute(v164Sql);
        } catch (SQLException e) {
            throw new RuntimeException("V164 re-application failed", e);
        }
    }

    // =========================================================================
    // fixture helpers
    // =========================================================================

    private static UUID insertUser(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, created_at, updated_at) "
                        + "VALUES (?, ?, 'x', ?, true, true, now(), now())",
                id, "v164-" + id + "@beautica.test", role);
        return id;
    }

    private static UUID testCityId() {
        return jdbc.queryForObject("SELECT id FROM cities WHERE name_uk = 'Вінниця' LIMIT 1", UUID.class);
    }

    private static UUID insertSalon(UUID ownerId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO salons (id, owner_id, name, is_active, city_id, created_at, updated_at) "
                        + "VALUES (?, ?, 'V164 Test Salon', true, ?, now(), now())",
                id, ownerId, testCityId());
        return id;
    }

    /** An ATTACHED salon-bound master. */
    private static UUID insertSalonMaster(UUID salonId) {
        UUID userId = insertUser("SALON_MASTER");
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, now(), now())",
                id, userId, salonId);
        return id;
    }

    /** A DETACHED salon-bound master (user_id IS NULL, Phase 294/297) — test case 11. */
    private static UUID insertDetachedSalonMaster(UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, "
                        + "detached_first_name, detached_at, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, 'SALON_MASTER', true, 'Detached', now(), now(), now())",
                id, salonId);
        return id;
    }

    /** An INDEPENDENT master (masters.salon_id IS NULL) — test case 5. */
    private static UUID insertIndependentMaster() {
        UUID userId = insertUser("INDEPENDENT_MASTER");
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, NULL, 'INDEPENDENT_MASTER', true, now(), now())",
                id, userId);
        return id;
    }

    /**
     * Resolves {@code n} distinct, selectable {@code service_types.id} values (active leaf under
     * an APPROVED + active platform category) — enough that every test can use its own type and
     * never collide with another test's fixture under V121's partial unique index.
     */
    private static List<UUID> resolveServiceTypeIds(int n) {
        return jdbc.queryForList(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT ?",
                UUID.class, n);
    }

    private static UUID insertServiceDefinition(String ownerType, UUID ownerId, UUID serviceTypeId,
                                                 BigDecimal basePrice, int baseDurationMinutes,
                                                 boolean isActive, OffsetDateTime createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, price_type, base_price, buffer_minutes_after, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'V164 Test Service', ?, ?, 'FIXED', ?, 0, ?, ?, ?)",
                id, ownerType, ownerId, serviceTypeId, baseDurationMinutes, basePrice, isActive,
                createdAt, createdAt);
        return id;
    }

    private static UUID insertMasterService(UUID masterId, UUID serviceDefId, BigDecimal priceOverride,
                                             Integer durationOverride, boolean isActive) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO master_services (id, master_id, service_def_id, price_override, "
                        + "duration_override_minutes, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, now(), now())",
                id, masterId, serviceDefId, priceOverride, durationOverride, isActive);
        return id;
    }

    private static UUID insertCompletedBooking(UUID clientId, UUID masterId, UUID masterServiceId,
                                                UUID salonId, BigDecimal priceAtBooking,
                                                int durationMinutesAtBooking) {
        UUID id = UUID.randomUUID();
        OffsetDateTime startsAt = OffsetDateTime.now(ZoneOffset.UTC).minusDays(10);
        jdbc.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', ?, ?, ?, ?, 0, now(), now())",
                id, clientId, masterId, masterServiceId, salonId, startsAt,
                startsAt.plusMinutes(durationMinutesAtBooking), priceAtBooking, durationMinutesAtBooking);
        return id;
    }

    private static Map<String, Object> definitionRow(UUID id) {
        return jdbc.queryForMap(
                "SELECT owner_type, owner_id, is_active, base_price, base_duration_minutes, updated_at "
                        + "FROM service_definitions WHERE id = ?", id);
    }

    private static Map<String, Object> assignmentRow(UUID id) {
        return jdbc.queryForMap(
                "SELECT service_def_id, price_override, duration_override_minutes, is_active, updated_at "
                        + "FROM master_services WHERE id = ?", id);
    }

    // =========================================================================
    // Case 1 — single salon-bound master, single definition
    // =========================================================================

    @Test
    @DisplayName("case 1: one salon master with one master-owned definition is promoted to SALON, "
            + "assignment keeps pointing at the same definition id")
    void should_promoteSoleDefinitionToSalonOwned_when_oneMasterHoldsOneServiceType() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID masterId = insertSalonMaster(salonId);
        UUID typeId = resolveServiceTypeIds(1).get(0);

        UUID defId = insertServiceDefinition("INDEPENDENT_MASTER", masterId, typeId,
                new BigDecimal("300.00"), 60, true, OffsetDateTime.now(ZoneOffset.UTC).minusDays(30));
        UUID assignmentId = insertMasterService(masterId, defId, null, null, true);

        applyV164();

        assertThat(definitionRow(defId))
                .containsEntry("owner_type", "SALON")
                .containsEntry("owner_id", salonId)
                .containsEntry("is_active", true);
        assertThat(assignmentRow(assignmentId))
                .as("the sole definition IS the survivor of its own singleton group — the assignment "
                        + "was never a loser and must not be touched")
                .containsEntry("service_def_id", defId)
                .containsEntry("is_active", true);
    }

    // =========================================================================
    // Cases 2 + 3 — N-way collision (N=3), survivor = oldest by created_at
    // =========================================================================

    @Test
    @DisplayName("cases 2+3: a THREE-way collision merges into one active SALON row, the OLDEST "
            + "definition survives (id, not just count), every assignment repoints at it, and both "
            + "losers stay present but deactivated (D3)")
    void should_mergeThreeWayCollision_keepingOldestAsSurvivor() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID masterA = insertSalonMaster(salonId);
        UUID masterB = insertSalonMaster(salonId);
        UUID masterC = insertSalonMaster(salonId);
        UUID typeId = resolveServiceTypeIds(1).get(0);

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        // Deliberately inserted out of created_at order, so a naive "last row wins" bug would be
        // caught: B (middle) is inserted FIRST, A (oldest) SECOND, C (newest) THIRD.
        UUID defB = insertServiceDefinition("INDEPENDENT_MASTER", masterB, typeId,
                new BigDecimal("310.00"), 60, true, now.minusDays(20));
        UUID defA = insertServiceDefinition("INDEPENDENT_MASTER", masterA, typeId,
                new BigDecimal("300.00"), 60, true, now.minusDays(30));
        UUID defC = insertServiceDefinition("INDEPENDENT_MASTER", masterC, typeId,
                new BigDecimal("320.00"), 60, true, now.minusDays(10));

        UUID msA = insertMasterService(masterA, defA, null, null, true);
        UUID msB = insertMasterService(masterB, defB, null, null, true);
        UUID msC = insertMasterService(masterC, defC, null, null, true);

        applyV164();

        assertThat(definitionRow(defA))
                .as("A is the oldest (created_at) row in the group and must be the survivor")
                .containsEntry("owner_type", "SALON")
                .containsEntry("owner_id", salonId)
                .containsEntry("is_active", true);
        assertThat(definitionRow(defB))
                .as("B (loser) must be DEACTIVATED, not deleted — the row still exists")
                .containsEntry("is_active", false);
        assertThat(definitionRow(defC))
                .as("C (loser) must be DEACTIVATED, not deleted — the row still exists")
                .containsEntry("is_active", false);

        assertThat(assignmentRow(msA)).containsEntry("service_def_id", defA);
        assertThat(assignmentRow(msB))
                .as("B's assignment must repoint to survivor A, or the salon catalogue would be "
                        + "missing master B's service entirely (empty catalogue defect)")
                .containsEntry("service_def_id", defA);
        assertThat(assignmentRow(msC))
                .as("C's assignment must repoint to survivor A")
                .containsEntry("service_def_id", defA);

        Long activeSalonRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_definitions "
                        + "WHERE owner_type = 'SALON' AND owner_id = ? AND service_type_id = ? AND is_active = true",
                Long.class, salonId, typeId);
        assertThat(activeSalonRows).isEqualTo(1L);
    }

    // =========================================================================
    // Case 4 — pre-existing SALON row wins over a master-owned one
    // =========================================================================

    @Test
    @DisplayName("case 4: an existing active SALON-owned row survives over a master-owned one; the "
            + "master-owned row is deactivated and its assignment repointed")
    void should_keepPreExistingSalonRowAsSurvivor_when_masterOwnedRowAlsoExists() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID masterId = insertSalonMaster(salonId);
        UUID typeId = resolveServiceTypeIds(1).get(0);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // Pre-existing SALON row is the NEWER row by created_at, deliberately, so the test proves
        // D2's "already SALON-owned wins" rule rather than merely reproducing the oldest-wins rule.
        UUID salonDefId = insertServiceDefinition("SALON", salonId, typeId,
                new BigDecimal("500.00"), 45, true, now.minusDays(1));
        UUID masterDefId = insertServiceDefinition("INDEPENDENT_MASTER", masterId, typeId,
                new BigDecimal("450.00"), 45, true, now.minusDays(60));
        UUID assignmentId = insertMasterService(masterId, masterDefId, null, null, true);

        applyV164();

        assertThat(definitionRow(salonDefId))
                .as("the pre-existing SALON row survives byte-identical in ownership/activity")
                .containsEntry("owner_type", "SALON")
                .containsEntry("owner_id", salonId)
                .containsEntry("is_active", true);
        assertThat(definitionRow(masterDefId))
                .as("the master-owned row is deactivated, not deleted")
                .containsEntry("is_active", false);
        assertThat(assignmentRow(assignmentId))
                .as("the assignment is repointed at the pre-existing SALON row")
                .containsEntry("service_def_id", salonDefId)
                .containsEntry("is_active", true);
    }

    // =========================================================================
    // Case 5 — independent master untouched
    // =========================================================================

    @Test
    @DisplayName("case 5: an independent master's definition (salon_id IS NULL) is byte-identical "
            + "after V164 — owner type, owner id and is_active all unchanged")
    void should_leaveIndependentMasterDefinitionUntouched() {
        UUID masterId = insertIndependentMaster();
        UUID typeId = resolveServiceTypeIds(1).get(0);
        UUID defId = insertServiceDefinition("INDEPENDENT_MASTER", masterId, typeId,
                new BigDecimal("275.00"), 50, true, OffsetDateTime.now(ZoneOffset.UTC).minusDays(5));
        UUID assignmentId = insertMasterService(masterId, defId, null, null, true);

        Map<String, Object> before = definitionRow(defId);

        applyV164();

        assertThat(definitionRow(defId))
                .as("an independent master's definition must never be touched by V164 — "
                        + "the discriminator is masters.salon_id, not owner_type alone")
                .containsEntry("owner_type", "INDEPENDENT_MASTER")
                .containsEntry("owner_id", masterId)
                .containsEntry("is_active", true)
                .containsEntry("updated_at", before.get("updated_at"));
        assertThat(assignmentRow(assignmentId)).containsEntry("service_def_id", defId);
    }

    // =========================================================================
    // Case 6 — historical COMPLETED booking survives repointing with its frozen price
    // =========================================================================

    @Test
    @DisplayName("case 6: a past COMPLETED booking through a repointed assignment still reads back "
            + "its original price_at_booking and resolves a valid provider/service (D5)")
    void should_preserveHistoricalBookingPrice_afterAssignmentIsRepointed() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID masterSurvivor = insertSalonMaster(salonId);
        UUID masterLoser = insertSalonMaster(salonId);
        UUID clientId = insertUser("CLIENT");
        UUID typeId = resolveServiceTypeIds(1).get(0);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        UUID survivorDefId = insertServiceDefinition("INDEPENDENT_MASTER", masterSurvivor, typeId,
                new BigDecimal("400.00"), 60, true, now.minusDays(90));
        UUID loserDefId = insertServiceDefinition("INDEPENDENT_MASTER", masterLoser, typeId,
                new BigDecimal("450.00"), 60, true, now.minusDays(60));
        UUID loserAssignmentId = insertMasterService(masterLoser, loserDefId, null, null, true);

        // The booking's frozen price predates the migration and must NEVER move — it was priced at
        // the loser's base_price the day it was made, before any merge existed.
        UUID bookingId = insertCompletedBooking(clientId, masterLoser, loserAssignmentId, salonId,
                new BigDecimal("450.00"), 60);

        applyV164();

        Map<String, Object> booking = jdbc.queryForMap(
                "SELECT price_at_booking, master_service_id FROM bookings WHERE id = ?", bookingId);
        assertThat(((BigDecimal) booking.get("price_at_booking")))
                .as("D5: a past booking's frozen price must never move, regardless of the repoint")
                .isEqualByComparingTo("450.00");
        assertThat(booking.get("master_service_id"))
                .as("the booking still references the SAME master_services row — only that row's "
                        + "service_def_id moved, not the booking's foreign key")
                .isEqualTo(loserAssignmentId);

        // The join chain a receipt/read uses must still resolve without error, through the
        // REPOINTED assignment, to a live provider and service name.
        Map<String, Object> resolved = jdbc.queryForMap(
                "SELECT u.email AS provider_email, sd.name AS service_name "
                        + "FROM bookings b "
                        + "JOIN master_services ms ON ms.id = b.master_service_id "
                        + "JOIN service_definitions sd ON sd.id = ms.service_def_id "
                        + "JOIN masters m ON m.id = b.master_id "
                        + "JOIN users u ON u.id = m.user_id "
                        + "WHERE b.id = ?", bookingId);
        assertThat(resolved.get("provider_email")).isNotNull();
        assertThat(resolved.get("service_name")).isNotNull();
        assertThat(assignmentRow(loserAssignmentId)).containsEntry("service_def_id", survivorDefId);
    }

    // =========================================================================
    // Case 7 — per-master price/duration divergence lands in the override columns
    // =========================================================================

    @Test
    @DisplayName("case 7: a merged loser's diverging price/duration lands in master_services "
            + "price_override/duration_override_minutes; the survivor's base_price is unchanged (D5)")
    void should_carryDivergingPriceAndDurationIntoOverride_whenMerged() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID masterSurvivor = insertSalonMaster(salonId);
        UUID masterLoser = insertSalonMaster(salonId);
        UUID typeId = resolveServiceTypeIds(1).get(0);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        UUID survivorDefId = insertServiceDefinition("INDEPENDENT_MASTER", masterSurvivor, typeId,
                new BigDecimal("300.00"), 45, true, now.minusDays(40));
        UUID loserDefId = insertServiceDefinition("INDEPENDENT_MASTER", masterLoser, typeId,
                new BigDecimal("400.00"), 60, true, now.minusDays(20));
        UUID loserAssignmentId = insertMasterService(masterLoser, loserDefId, null, null, true);

        applyV164();

        assertThat(assignmentRow(loserAssignmentId))
                .as("the loser master's effective price (400) differs from the survivor's base_price "
                        + "(300), so it must be carried as a per-master override")
                .containsEntry("service_def_id", survivorDefId)
                .containsEntry("price_override", new BigDecimal("400.00"))
                .containsEntry("duration_override_minutes", 60);
        assertThat(definitionRow(survivorDefId))
                .as("D5: repointing must never re-price the survivor definition itself")
                .containsEntry("base_price", new BigDecimal("300.00"));
    }

    // =========================================================================
    // Case 8 — idempotent: a second application changes zero rows
    // =========================================================================

    @Test
    @DisplayName("case 8: running V164's body a second time changes zero rows (D6)")
    void should_beIdempotent_onSecondApplication() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID masterA = insertSalonMaster(salonId);
        UUID masterB = insertSalonMaster(salonId);
        UUID typeId = resolveServiceTypeIds(1).get(0);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        UUID defA = insertServiceDefinition("INDEPENDENT_MASTER", masterA, typeId,
                new BigDecimal("300.00"), 45, true, now.minusDays(40));
        UUID defB = insertServiceDefinition("INDEPENDENT_MASTER", masterB, typeId,
                new BigDecimal("400.00"), 60, true, now.minusDays(20));
        UUID msA = insertMasterService(masterA, defA, null, null, true);
        UUID msB = insertMasterService(masterB, defB, null, null, true);

        applyV164();

        Map<String, Object> defAAfterFirst = definitionRow(defA);
        Map<String, Object> defBAfterFirst = definitionRow(defB);
        Map<String, Object> msAAfterFirst = assignmentRow(msA);
        Map<String, Object> msBAfterFirst = assignmentRow(msB);

        applyV164();

        // updated_at is bumped by every UPDATE statement in this migration that actually matches a
        // row, so an UNCHANGED updated_at is proof the second run touched zero rows here — not
        // merely that the visible business columns happen to already hold the same value.
        assertThat(definitionRow(defA)).isEqualTo(defAAfterFirst);
        assertThat(definitionRow(defB)).isEqualTo(defBAfterFirst);
        assertThat(assignmentRow(msA)).isEqualTo(msAAfterFirst);
        assertThat(assignmentRow(msB)).isEqualTo(msBAfterFirst);
    }

    // =========================================================================
    // Case 9 — the partial unique index still holds after the merge
    // =========================================================================

    @Test
    @DisplayName("case 9: ux_service_def_owner_service_type_active holds after V164 — no duplicate "
            + "active (SALON, salonId, serviceTypeId) triple exists anywhere in the database")
    void should_neverProduceADuplicateActiveSalonTriple() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID masterA = insertSalonMaster(salonId);
        UUID masterB = insertSalonMaster(salonId);
        UUID masterC = insertSalonMaster(salonId);
        List<UUID> typeIds = resolveServiceTypeIds(2);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        for (UUID typeId : typeIds) {
            insertMasterService(masterA, insertServiceDefinition(
                    "INDEPENDENT_MASTER", masterA, typeId, new BigDecimal("300.00"), 45, true,
                    now.minusDays(30)), null, null, true);
            insertMasterService(masterB, insertServiceDefinition(
                    "INDEPENDENT_MASTER", masterB, typeId, new BigDecimal("310.00"), 45, true,
                    now.minusDays(20)), null, null, true);
            insertMasterService(masterC, insertServiceDefinition(
                    "INDEPENDENT_MASTER", masterC, typeId, new BigDecimal("320.00"), 45, true,
                    now.minusDays(10)), null, null, true);
        }

        applyV164();

        Long duplicateTriples = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ("
                        + "  SELECT owner_type, owner_id, service_type_id FROM service_definitions "
                        + "  WHERE is_active = true "
                        + "  GROUP BY owner_type, owner_id, service_type_id HAVING COUNT(*) > 1"
                        + ") dup",
                Long.class);
        assertThat(duplicateTriples)
                .as("ux_service_def_owner_service_type_active must never be violated by the merge")
                .isZero();

        // Behavioural proof, not just an absence-of-rows check: the index itself must still exist
        // and be valid (a partial unique index left invalid by a prior violation would still show
        // up in pg_indexes, so also confirm Postgres considers it valid).
        Boolean indexValid = jdbc.queryForObject(
                "SELECT indisvalid FROM pg_index WHERE indexrelid = "
                        + "'ux_service_def_owner_service_type_active'::regclass",
                Boolean.class);
        assertThat(indexValid).isTrue();
    }

    // =========================================================================
    // Case 11 — a detached master's definition is still backfilled
    // =========================================================================

    @Test
    @DisplayName("case 11: a detached master's (user_id IS NULL) salon-bound definition is still "
            + "promoted to SALON — a detached master's history stays readable")
    void should_backfillDetachedMasterDefinition() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID detachedMasterId = insertDetachedSalonMaster(salonId);
        UUID typeId = resolveServiceTypeIds(1).get(0);
        UUID defId = insertServiceDefinition("INDEPENDENT_MASTER", detachedMasterId, typeId,
                new BigDecimal("260.00"), 40, true, OffsetDateTime.now(ZoneOffset.UTC).minusDays(15));
        UUID assignmentId = insertMasterService(detachedMasterId, defId, null, null, true);

        applyV164();

        assertThat(definitionRow(defId))
                .containsEntry("owner_type", "SALON")
                .containsEntry("owner_id", salonId)
                .containsEntry("is_active", true);
        assertThat(assignmentRow(assignmentId)).containsEntry("service_def_id", defId);

        Map<String, Object> masterRow = jdbc.queryForMap(
                "SELECT user_id, detached_at, detached_first_name FROM masters WHERE id = ?",
                detachedMasterId);
        assertThat(masterRow.get("user_id"))
                .as("the detached master row itself is untouched by V164 — only service_definitions "
                        + "and master_services are written")
                .isNull();
        assertThat(masterRow.get("detached_first_name")).isEqualTo("Detached");
    }

    // =========================================================================
    // Phase 303 QA gap 2 (perf INFO, closed here) — the TRUE measured production arity. The phase
    // doc's status block measured the real local DB at 60 definitions / 1 salon / 5 masters / 12
    // service types, EVERY group a 5-way collision. Cases 2+3 above only exercise 3-way. The doc is
    // explicit: "a merge that handles 2->1 and is never exercised beyond it is not tested by this
    // dataset; it is flattered by nothing."
    // =========================================================================

    @Test
    @DisplayName("perf gap: a FIVE-way collision (the measured production arity) merges into one "
            + "active SALON row, the oldest survives, and ALL FIVE assignments repoint at it")
    void should_mergeFiveWayCollision_atTheMeasuredProductionArity() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID masterA = insertSalonMaster(salonId);
        UUID masterB = insertSalonMaster(salonId);
        UUID masterC = insertSalonMaster(salonId);
        UUID masterD = insertSalonMaster(salonId);
        UUID masterE = insertSalonMaster(salonId);
        UUID typeId = resolveServiceTypeIds(1).get(0);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // Inserted in a SCRAMBLED order relative to created_at (C, E, A, D, B), same technique as
        // cases 2+3, so a naive "last row wins" / "first-inserted wins" bug would be caught. B is
        // the true oldest (60 days) and must survive.
        UUID defC = insertServiceDefinition("INDEPENDENT_MASTER", masterC, typeId,
                new BigDecimal("330.00"), 60, true, now.minusDays(10));
        UUID defE = insertServiceDefinition("INDEPENDENT_MASTER", masterE, typeId,
                new BigDecimal("340.00"), 60, true, now.minusDays(40));
        UUID defA = insertServiceDefinition("INDEPENDENT_MASTER", masterA, typeId,
                new BigDecimal("300.00"), 60, true, now.minusDays(15));
        UUID defD = insertServiceDefinition("INDEPENDENT_MASTER", masterD, typeId,
                new BigDecimal("320.00"), 60, true, now.minusDays(30));
        UUID defB = insertServiceDefinition("INDEPENDENT_MASTER", masterB, typeId,
                new BigDecimal("310.00"), 60, true, now.minusDays(60));

        UUID msA = insertMasterService(masterA, defA, null, null, true);
        UUID msB = insertMasterService(masterB, defB, null, null, true);
        UUID msC = insertMasterService(masterC, defC, null, null, true);
        UUID msD = insertMasterService(masterD, defD, null, null, true);
        UUID msE = insertMasterService(masterE, defE, null, null, true);

        applyV164();

        assertThat(definitionRow(defB))
                .as("B is the oldest (60 days) of all five and must be the survivor — the exact "
                        + "5-way arity measured against the real local DB, not a flattering 2-way/3-way toy")
                .containsEntry("owner_type", "SALON")
                .containsEntry("owner_id", salonId)
                .containsEntry("is_active", true);

        Map<String, UUID> losers = Map.of("A", defA, "C", defC, "D", defD, "E", defE);
        losers.forEach((label, loserId) -> assertThat(definitionRow(loserId))
                .as("loser %s must be DEACTIVATED, not deleted", label)
                .containsEntry("is_active", false));

        assertThat(assignmentRow(msA)).as("A repoints to survivor B").containsEntry("service_def_id", defB);
        assertThat(assignmentRow(msB)).as("B's own assignment stays on itself").containsEntry("service_def_id", defB);
        assertThat(assignmentRow(msC)).as("C repoints to survivor B").containsEntry("service_def_id", defB);
        assertThat(assignmentRow(msD)).as("D repoints to survivor B").containsEntry("service_def_id", defB);
        assertThat(assignmentRow(msE)).as("E repoints to survivor B").containsEntry("service_def_id", defB);

        Long activeSalonRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM service_definitions "
                        + "WHERE owner_type = 'SALON' AND owner_id = ? AND service_type_id = ? AND is_active = true",
                Long.class, salonId, typeId);
        assertThat(activeSalonRows)
                .as("exactly one active SALON row must survive a 5-way merge — same invariant as "
                        + "3-way, now proven not to degrade with more colliding rows")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("perf gap: a scaled-down MULTI-GROUP fixture (3 service types x 3 masters each) "
            + "merges each group independently — no cross-type bleed in survivor selection")
    void should_mergeMultipleGroupsIndependently_withNoCrossTypeBleed() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID[] masters = {insertSalonMaster(salonId), insertSalonMaster(salonId), insertSalonMaster(salonId)};
        List<UUID> typeIds = resolveServiceTypeIds(3);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // Deliberately rotate WHICH master is oldest per type, so a bug that drops service_type_id
        // from the survivor-selection PARTITION/GROUP BY key (collapsing the group to "per salon"
        // instead of "per salon+type") would pick the SAME single survivor for every type — this
        // fixture makes that bug visibly wrong: type0's oldest is master 1, type1's is master 0,
        // type2's is master 2.
        int[][] ageDaysByTypeThenMaster = {
                {5, 40, 20},   // type 0: master 1 is oldest (40d)
                {30, 10, 5},   // type 1: master 0 is oldest (30d)
                {15, 5, 50},   // type 2: master 2 is oldest (50d)
        };

        Map<UUID, UUID> survivorDefByType = new HashMap<>();
        Map<UUID, List<UUID>> assignmentsByType = new HashMap<>();
        for (int t = 0; t < typeIds.size(); t++) {
            UUID typeId = typeIds.get(t);
            List<UUID> assignmentIds = new ArrayList<>();
            UUID oldestDefForType = null;
            int oldestAge = -1;
            for (int m = 0; m < masters.length; m++) {
                int ageDays = ageDaysByTypeThenMaster[t][m];
                UUID defId = insertServiceDefinition("INDEPENDENT_MASTER", masters[m], typeId,
                        new BigDecimal("300.00").add(BigDecimal.valueOf(t * 10 + m)), 60, true,
                        now.minusDays(ageDays));
                assignmentIds.add(insertMasterService(masters[m], defId, null, null, true));
                if (ageDays > oldestAge) {
                    oldestAge = ageDays;
                    oldestDefForType = defId;
                }
            }
            survivorDefByType.put(typeId, oldestDefForType);
            assignmentsByType.put(typeId, assignmentIds);
        }

        applyV164();

        for (UUID typeId : typeIds) {
            UUID expectedSurvivor = survivorDefByType.get(typeId);
            assertThat(definitionRow(expectedSurvivor))
                    .as("type %s's own oldest definition must survive, independent of the other two "
                            + "types' groups", typeId)
                    .containsEntry("owner_type", "SALON")
                    .containsEntry("owner_id", salonId)
                    .containsEntry("is_active", true);

            for (UUID assignmentId : assignmentsByType.get(typeId)) {
                assertThat(assignmentRow(assignmentId))
                        .as("every assignment for type %s must repoint at THAT type's own survivor, "
                                + "never a different type's", typeId)
                        .containsEntry("service_def_id", expectedSurvivor);
            }

            Long activeSalonRowsForType = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM service_definitions "
                            + "WHERE owner_type = 'SALON' AND owner_id = ? AND service_type_id = ? AND is_active = true",
                    Long.class, salonId, typeId);
            assertThat(activeSalonRowsForType).isEqualTo(1L);
        }

        assertThat(survivorDefByType.values())
                .as("one DISTINCT survivor per service type — a PARTITION-collapse bug would instead "
                        + "converge all three groups onto a single salon-wide survivor")
                .hasSize(3);
    }

    // =========================================================================
    // D4 — a duplicate ACTIVE assignment collision is deactivated, never repointed
    // =========================================================================

    @Test
    @DisplayName("D4: when a master already holds an active assignment to BOTH the survivor and a "
            + "loser, the duplicate assignment is deactivated (not repointed) — the UNIQUE "
            + "(master_id, service_def_id) constraint is never at risk")
    void should_deactivateDuplicateAssignment_ratherThanRepointIt_whenMasterAlreadyAssignedToSurvivor() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID masterId = insertSalonMaster(salonId);
        UUID typeId = resolveServiceTypeIds(1).get(0);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        // The pre-existing SALON row is the survivor by D2's "already SALON-owned wins" rule.
        UUID survivorDefId = insertServiceDefinition("SALON", salonId, typeId,
                new BigDecimal("500.00"), 45, true, now.minusDays(5));
        UUID loserDefId = insertServiceDefinition("INDEPENDENT_MASTER", masterId, typeId,
                new BigDecimal("450.00"), 45, true, now.minusDays(70));

        // The SAME master already has an ACTIVE assignment on the eventual survivor (e.g. assigned
        // directly by the owner at some point) AND on its own historical loser definition. Merging
        // both onto the survivor would violate master_services' full UNIQUE(master_id, service_def_id).
        UUID assignmentOnSurvivor = insertMasterService(masterId, survivorDefId, null, null, true);
        UUID assignmentOnLoser = insertMasterService(masterId, loserDefId, null, null, true);

        // A historical booking on the DEDUPED assignment is what actually makes D3's "never DELETE"
        // reasoning bite: this is the one row this migration's D2 repoint step (deliberately) never
        // repoints, so it is the only row in this whole fixture matrix still pointing at a loser
        // definition by the time the loser is deactivated — a DELETE mutation here cascades to
        // master_services (V7:9 ON DELETE CASCADE) and is then rejected by bookings.master_service_id
        // (V18:7 NO ACTION), the exact abort-mid-flight failure D3 documents.
        UUID clientId = insertUser("CLIENT");
        insertCompletedBooking(clientId, masterId, assignmentOnLoser, salonId,
                new BigDecimal("450.00"), 45);

        applyV164();

        assertThat(assignmentRow(assignmentOnLoser))
                .as("D4: the colliding assignment is deactivated and left pointing at the (now "
                        + "inactive) loser definition — repointing it would violate the UNIQUE constraint")
                .containsEntry("service_def_id", loserDefId)
                .containsEntry("is_active", false);
        assertThat(assignmentRow(assignmentOnSurvivor))
                .as("the pre-existing survivor assignment is untouched")
                .containsEntry("service_def_id", survivorDefId)
                .containsEntry("is_active", true);
        assertThat(definitionRow(loserDefId)).containsEntry("is_active", false);

        // The constraint itself never fired — proven by the fact this test reached its assertions
        // at all (a violation would have thrown a DataIntegrityViolationException out of applyV164()).
        Long assignmentsForMaster = jdbc.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ?", Long.class, masterId);
        assertThat(assignmentsForMaster).isEqualTo(2L);
    }

    // =========================================================================
    // Q16 (2026-09-13 audit) — the two SKIPS V164 makes, pinned as INTENTIONAL
    // =========================================================================
    //
    // V164:54's `sd.is_active = true` and step 4's `NOT EXISTS` are both deliberate narrowings that
    // no case pinned. A documented intentional skip still needs a test: without one, widening the
    // predicate (or losing the NOT EXISTS) is a silent behaviour change, and so is keeping it after
    // someone decides it was wrong.

    @Test
    @DisplayName("Q16a: an INACTIVE master-owned definition on a salon-bound master is NEVER "
            + "promoted — V164:54 filters the candidates CTE on sd.is_active = true, and that skip "
            + "is intentional: a deactivated service is not part of the salon's catalogue")
    void should_leaveInactiveMasterOwnedDefinitionUntouched() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID masterId = insertSalonMaster(salonId);
        UUID typeId = resolveServiceTypeIds(1).get(0);

        UUID inactiveDefId = insertServiceDefinition("INDEPENDENT_MASTER", masterId, typeId,
                new BigDecimal("300.00"), 60, false, OffsetDateTime.now(ZoneOffset.UTC).minusDays(30));

        applyV164();

        assertThat(definitionRow(inactiveDefId))
                .as("Q16a — an inactive definition keeps its INDEPENDENT_MASTER ownership; "
                        + "promoting it would resurrect a service the salon deliberately retired, "
                        + "and would collide with V121's partial unique index the moment it were "
                        + "reactivated alongside an active SALON row for the same type")
                .containsEntry("owner_type", "INDEPENDENT_MASTER")
                .containsEntry("owner_id", masterId)
                .containsEntry("is_active", false);
    }

    @Test
    @DisplayName("Q16a non-vacuity: the SAME fixture with is_active = true IS promoted — the skip "
            + "above is driven by the flag, not by anything else in the fixture")
    void should_promoteTheSameDefinition_when_itIsActive() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID masterId = insertSalonMaster(salonId);
        UUID typeId = resolveServiceTypeIds(1).get(0);

        UUID activeDefId = insertServiceDefinition("INDEPENDENT_MASTER", masterId, typeId,
                new BigDecimal("300.00"), 60, true, OffsetDateTime.now(ZoneOffset.UTC).minusDays(30));

        applyV164();

        assertThat(definitionRow(activeDefId))
                .containsEntry("owner_type", "SALON")
                .containsEntry("owner_id", salonId);
    }

    @Test
    @DisplayName("Q16b: an INACTIVE master_services row on a merged LOSER IS repointed at the "
            + "survivor and STAYS inactive — step 4 is is_active-blind by design, so unassign "
            + "state survives the merge without the row being stranded on a dead definition")
    void should_repointInactiveAssignmentOntoSurvivor_keepingItInactive() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID masterA = insertSalonMaster(salonId);
        UUID masterB = insertSalonMaster(salonId);
        UUID typeId = resolveServiceTypeIds(1).get(0);

        // masterA's definition is older, so it wins the group.
        UUID survivorDefId = insertServiceDefinition("INDEPENDENT_MASTER", masterA, typeId,
                new BigDecimal("300.00"), 60, true, OffsetDateTime.now(ZoneOffset.UTC).minusDays(30));
        insertMasterService(masterA, survivorDefId, null, null, true);

        UUID loserDefId = insertServiceDefinition("INDEPENDENT_MASTER", masterB, typeId,
                new BigDecimal("350.00"), 60, true, OffsetDateTime.now(ZoneOffset.UTC).minusDays(10));
        // masterB previously UNASSIGNED this service — an is_active = false row.
        UUID inactiveAssignmentId = insertMasterService(masterB, loserDefId, null, null, false);

        applyV164();

        assertThat(definitionRow(loserDefId))
                .as("arrange check — the loser definition really was merged away")
                .containsEntry("is_active", false);
        assertThat(assignmentRow(inactiveAssignmentId))
                .as("Q16b — step 4's NOT EXISTS excludes only a master who ALREADY holds a row "
                        + "against the survivor; it does NOT filter on is_active. So an unassigned "
                        + "row is repointed like any other. That is the right outcome: leaving it "
                        + "on a now-INACTIVE definition would strand it, and re-assigning later "
                        + "(ServiceCatalogService's D6 reactivation path, which looks the row up "
                        + "by (master, service_def_id)) would then miss it and insert a duplicate "
                        + "against the non-partial UNIQUE key.")
                .containsEntry("service_def_id", survivorDefId)
                .containsEntry("is_active", false);
        assertThat((BigDecimal) assignmentRow(inactiveAssignmentId).get("price_override"))
                .as("and the loser's diverging 350 price is carried into the override (D5) exactly "
                        + "as it is for an active row — the merge preserves what this master "
                        + "charged, ready for whenever the service is re-assigned")
                .isEqualByComparingTo("350.00");
    }

    @Test
    @DisplayName("Q16c: step 4's NOT EXISTS — a master holding rows against BOTH the loser and the "
            + "survivor keeps the loser-pointed row ON the loser, never repointed into a "
            + "UNIQUE(master_id, service_def_id) collision")
    void should_leaveLoserPointedRowInPlace_when_theMasterAlreadyHoldsTheSurvivor() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID masterA = insertSalonMaster(salonId);
        UUID masterB = insertSalonMaster(salonId);
        UUID typeId = resolveServiceTypeIds(1).get(0);

        UUID survivorDefId = insertServiceDefinition("INDEPENDENT_MASTER", masterA, typeId,
                new BigDecimal("300.00"), 60, true, OffsetDateTime.now(ZoneOffset.UTC).minusDays(30));
        insertMasterService(masterA, survivorDefId, null, null, true);

        UUID loserDefId = insertServiceDefinition("INDEPENDENT_MASTER", masterB, typeId,
                new BigDecimal("350.00"), 60, true, OffsetDateTime.now(ZoneOffset.UTC).minusDays(10));
        // masterB performs BOTH definitions — the collision step 4's NOT EXISTS exists to avoid.
        UUID onSurvivorId = insertMasterService(masterB, survivorDefId, null, null, true);
        UUID onLoserId = insertMasterService(masterB, loserDefId, null, null, true);

        applyV164();

        assertThat(assignmentRow(onLoserId))
                .as("Q16c — repointing this row would duplicate (masterB, survivor). Step 3 "
                        + "deactivates it instead and step 4 skips it, so it stays on the loser.")
                .containsEntry("service_def_id", loserDefId)
                .containsEntry("is_active", false);
        assertThat(assignmentRow(onSurvivorId))
                .as("the master's pre-existing survivor row is the one that survives, untouched")
                .containsEntry("service_def_id", survivorDefId)
                .containsEntry("is_active", true);
    }

    @Test
    @DisplayName("Q16b non-vacuity: an ACTIVE assignment on the same merged loser is repointed at "
            + "the survivor TOO — step 4 is is_active-BLIND, so the inactive row above is "
            + "repointed for the same reason, not skipped (B13: there is no skip)")
    void should_repointActiveAssignmentOnTheLoser_whenItsDefinitionIsMerged() {
        UUID owner = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(owner);
        UUID masterA = insertSalonMaster(salonId);
        UUID masterB = insertSalonMaster(salonId);
        UUID typeId = resolveServiceTypeIds(1).get(0);

        UUID survivorDefId = insertServiceDefinition("INDEPENDENT_MASTER", masterA, typeId,
                new BigDecimal("300.00"), 60, true, OffsetDateTime.now(ZoneOffset.UTC).minusDays(30));
        insertMasterService(masterA, survivorDefId, null, null, true);

        UUID loserDefId = insertServiceDefinition("INDEPENDENT_MASTER", masterB, typeId,
                new BigDecimal("350.00"), 60, true, OffsetDateTime.now(ZoneOffset.UTC).minusDays(10));
        UUID activeAssignmentId = insertMasterService(masterB, loserDefId, null, null, true);

        applyV164();

        assertThat(assignmentRow(activeAssignmentId))
                .containsEntry("service_def_id", survivorDefId)
                .containsEntry("is_active", true);
    }

    // =========================================================================
    // Flyway history
    // =========================================================================

    @Test
    @DisplayName("V164 recorded as a successful no-op with a stable checksum on a fresh database")
    void should_recordV164AsSuccessfulNoOp_onFreshDatabase() {
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT success, checksum, script FROM flyway_schema_history WHERE version = '164'");

        assertThat(row.get("success")).isEqualTo(Boolean.TRUE);
        assertThat(row.get("script"))
                .isEqualTo("V164__backfill_salon_owned_master_service_definitions.sql");
        assertThat(row.get("checksum")).isNotNull();
    }
}
