package com.beautica.service.migration;

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
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Contract test for {@code V165__add_master_service_price_band.sql}'s D8 backfill and the
 * {@code chk_master_service_price_mode} CHECK constraint it installs (Phase 311).
 *
 * <p><b>Isolated raw-container harness, not {@code AbstractIntegrationTest}</b> — mirrors {@code
 * V164SalonOwnedBackfillMigrationTest} (REUSE-FIRST): {@code AbstractIntegrationTest}'s shared
 * Testcontainers instance is already migrated to HEAD by the time any {@code @SpringBootTest} runs,
 * so there is no way to insert a PRE-V165-shape row (a bare {@code price_override} with no {@code
 * price_type_override}, and no CHECK to forbid it) through that harness — the constraint would
 * already be live. This class instead starts its OWN container, migrates it to exactly V164, seeds
 * the pre-existing "legacy" rows the backfill must lift, and THEN migrates to HEAD so V165 (and
 * only V165) runs against them.
 *
 * <h2>Case 27 note</h2>
 * D8 measured ZERO {@code (RANGE definition, non-null override)} rows locally — the entire local
 * backfill population (48 rows) sits against FIXED definitions. Case 27 is therefore constructed
 * by this fixture, not discovered, exactly as the phase doc requires: it is the only way to prove
 * the backfill's {@code CASE WHEN sd.price_type = 'RANGE'} arm is correct rather than merely
 * untested (mutation 16).
 */
@DisplayName("V165 migration — per-master price band backfill (Phase 311 D8) + chk_master_service_price_mode")
class MasterServiceBandBackfillIT {

    private static PostgreSQLContainer<?> postgres;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;

    private static UUID fixedDefId;
    private static UUID fixedAssignmentId;
    private static UUID rangeDefId;
    private static UUID rangeAssignmentId;
    private static UUID noOverrideAssignmentId;

    /** Distinct service types for the illegal-band tests' throwaway definitions — see {@link #insertIllegalBand}. */
    private static List<UUID> illegalBandTypeIds;
    private static final java.util.concurrent.atomic.AtomicInteger ILLEGAL_BAND_TYPE_INDEX =
            new java.util.concurrent.atomic.AtomicInteger(0);

    @BeforeAll
    static void migrateToV164SeedLegacyRowsThenMigrateToHead() {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        // Step 1 — migrate to EXACTLY V164: the schema has price_override but neither
        // price_type_override nor price_max_override, and no chk_master_service_price_mode yet —
        // the true pre-Phase-311 shape.
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("164"))
                .load()
                .migrate();

        UUID salonId = insertSalonWithOwner();
        UUID masterId = insertSalonMaster(salonId);
        List<UUID> typeIds = resolveServiceTypeIds(10);
        illegalBandTypeIds = typeIds.subList(3, 10);

        // Case 26 fixture — a bare price_override against a FIXED definition (the ENTIRE local
        // backfill population's actual shape, 48/48 rows).
        fixedDefId = insertServiceDefinition(salonId, typeIds.get(0), "FIXED", new BigDecimal("400.00"), null);
        fixedAssignmentId = insertMasterServiceLegacyOverride(masterId, fixedDefId, new BigDecimal("550.00"));

        // Case 27 fixture — a bare price_override against a RANGE definition. Zero such rows exist
        // locally; constructed here so the backfill's RANGE arm is genuinely exercised.
        rangeDefId = insertServiceDefinition(salonId, typeIds.get(1), "RANGE", new BigDecimal("300.00"), new BigDecimal("600.00"));
        rangeAssignmentId = insertMasterServiceLegacyOverride(masterId, rangeDefId, new BigDecimal("450.00"));

        // Case 28 fixture — no override at all (the 14 260-row Inherited majority).
        UUID noOverrideDefId = insertServiceDefinition(salonId, typeIds.get(2), "FIXED", new BigDecimal("200.00"), null);
        noOverrideAssignmentId = insertMasterServiceLegacyOverride(masterId, noOverrideDefId, null);

        // Step 2 — migrate to HEAD: applies V165 (and only V165 — 1-164 are already recorded) —
        // the backfill runs against exactly the three rows above.
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

    private static Map<String, Object> assignmentRow(UUID id) {
        return jdbc.queryForMap(
                "SELECT price_type_override, price_override, price_max_override "
                        + "FROM master_services WHERE id = ?", id);
    }

    // ── Case 26 — FIXED-def override lifted to FIXED, no ceiling invented ───────────────────────

    @Test
    @DisplayName("Case 26: a bare price_override against a FIXED definition is lifted to "
            + "price_type_override = 'FIXED', price_max_override IS NULL")
    void should_liftFixedOverride_when_definitionIsFixed() {
        var row = assignmentRow(fixedAssignmentId);

        assertThat(row.get("price_type_override")).isEqualTo("FIXED");
        assertThat((BigDecimal) row.get("price_override")).isEqualByComparingTo("550.00");
        assertThat(row.get("price_max_override")).as("no ceiling invented for a FIXED lift").isNull();
    }

    // ── Case 27 — RANGE-def override lifted to RANGE, WITH the definition's ceiling ─────────────

    @Test
    @DisplayName("Case 27: a bare price_override against a RANGE definition is lifted to "
            + "price_type_override = 'RANGE' carrying the DEFINITION'S price_max (D8's CASE arm)")
    void should_liftRangeOverride_when_definitionIsRange() {
        var row = assignmentRow(rangeAssignmentId);

        assertThat(row.get("price_type_override")).isEqualTo("RANGE");
        assertThat((BigDecimal) row.get("price_override")).isEqualByComparingTo("450.00");
        assertThat((BigDecimal) row.get("price_max_override"))
                .as("D8's CASE WHEN sd.price_type = 'RANGE' arm — the definition's own ceiling (600)")
                .isEqualByComparingTo("600.00");
    }

    // ── Case 28 — a row with no override is left entirely NULL, not written ────────────────────

    @Test
    @DisplayName("Case 28: a row with NO price_override is left entirely NULL (Inherited) — not "
            + "written by the backfill")
    void should_leaveNoOverrideRowUntouched_when_noPriceOverrideSet() {
        var row = assignmentRow(noOverrideAssignmentId);

        assertThat(row.get("price_type_override")).isNull();
        assertThat(row.get("price_override")).isNull();
        assertThat(row.get("price_max_override")).isNull();
    }

    // ── Case 29 — every illegal shape is rejected by the DB CHECK after migration ───────────────

    @Test
    @DisplayName("Case 29a: chk_master_service_price_mode rejects RANGE with a NULL ceiling "
            + "(mutation 17's target)")
    void should_rejectRangeWithNullCeiling() {
        assertThatThrownBy(() -> insertIllegalBand("RANGE", new BigDecimal("100.00"), null))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("Case 29b: chk_master_service_price_mode rejects FIXED with a non-NULL ceiling")
    void should_rejectFixedWithCeiling() {
        assertThatThrownBy(() -> insertIllegalBand("FIXED", new BigDecimal("100.00"), new BigDecimal("200.00")))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(SQLException.class);
    }

    @Test
    @DisplayName("Case 29c: chk_master_service_price_mode rejects RANGE with ceiling < floor")
    void should_rejectRangeWithCeilingBelowFloor() {
        assertThatThrownBy(() -> insertIllegalBand("RANGE", new BigDecimal("500.00"), new BigDecimal("100.00")))
                .isInstanceOf(RuntimeException.class)
                .hasCauseInstanceOf(SQLException.class);
    }

    // ── Case 30 — the constraint rejects a partial band written directly via SQL (D2 at the DB) ──

    @Test
    @DisplayName("Case 30: the constraint rejects price_override set with price_type_override NULL "
            + "— proving the DB, not just Java, enforces D2's all-or-nothing rule")
    void should_rejectPartialBand_writtenDirectlyViaSql() {
        // QA audit (2026-09-12): the original version of this test inserted directly against
        // fixedDefId, which fixedAssignmentId already holds an ACTIVE row for. That collides with
        // master_services' pre-existing UNIQUE(master_id, service_def_id) (V7:15) regardless of
        // whether chk_master_service_price_mode is present, correct, or deleted entirely — so the
        // test passed for the WRONG reason (a unique-key violation, not the CHECK) and would stay
        // green even if the CHECK constraint's naive three-branch OR (no IS NOT NULL guard) were
        // reintroduced, silently re-accepting a partial band. Routed through insertIllegalBand,
        // which mints a FRESH service_definitions row per call for exactly this reason (see its
        // javadoc), and the failure is pinned to the CHECK constraint by name so a masking
        // violation of a different kind fails loudly instead of passing silently.
        Throwable thrown = catchThrowable(() -> insertIllegalBand(null, new BigDecimal("999.00"), null));

        assertThat(thrown).isInstanceOf(RuntimeException.class).hasCauseInstanceOf(SQLException.class);
        assertThat(thrown.getCause())
                .as("must fail on chk_master_service_price_mode BY NAME — a masking UNIQUE or "
                        + "NOT NULL violation on some other column must not pass for the wrong reason")
                .hasMessageContaining("chk_master_service_price_mode");
    }

    // ── fixture helpers ──────────────────────────────────────────────────────────────────────────

    private static UUID insertSalonWithOwner() {
        UUID ownerId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, created_at, updated_at) "
                        + "VALUES (?, ?, 'x', 'SALON_OWNER', true, true, now(), now())",
                ownerId, "backfillit-owner-" + ownerId + "@beautica.test");
        UUID salonId = UUID.randomUUID();
        UUID cityId = jdbc.queryForObject("SELECT id FROM cities LIMIT 1", UUID.class);
        jdbc.update(
                "INSERT INTO salons (id, owner_id, name, is_active, city_id, created_at, updated_at) "
                        + "VALUES (?, ?, 'Backfill IT Salon', true, ?, now(), now())",
                salonId, ownerId, cityId);
        return salonId;
    }

    private static UUID insertSalonMaster(UUID salonId) {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified, created_at, updated_at) "
                        + "VALUES (?, ?, 'x', 'SALON_MASTER', ?, true, true, now(), now())",
                userId, "backfillit-master-" + userId + "@beautica.test", salonId);
        UUID masterId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, now(), now())",
                masterId, userId, salonId);
        return masterId;
    }

    private static List<UUID> resolveServiceTypeIds(int n) {
        return jdbc.queryForList(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT ?",
                UUID.class, n);
    }

    private static UUID insertServiceDefinition(
            UUID salonId, UUID serviceTypeId, String priceType, BigDecimal basePrice, BigDecimal priceMax) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, price_type, base_price, price_max, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Backfill IT Service', ?, 60, ?, ?, ?, 0, true, now(), now())",
                id, salonId, serviceTypeId, priceType, basePrice, priceMax);
        return id;
    }

    /** The PRE-V165 shape: only price_override, no price_type_override column exists yet. */
    private static UUID insertMasterServiceLegacyOverride(UUID masterId, UUID serviceDefId, BigDecimal priceOverride) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO master_services (id, master_id, service_def_id, price_override, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, true, now(), now())",
                id, masterId, serviceDefId, priceOverride);
        return id;
    }

    /**
     * Attempts a direct INSERT of an illegal (post-V165) band shape, for cases 29/30. Uses a FRESH
     * {@code service_definitions} row every call — {@code master_services}' full (non-partial)
     * {@code UNIQUE (master_id, service_def_id)} would otherwise trip on {@code fixedDefId}, which
     * {@code masterId} already holds an active row for, and mask the CHECK violation this method
     * exists to prove.
     */
    private static void insertIllegalBand(String priceTypeOverride, BigDecimal priceOverride, BigDecimal priceMaxOverride) {
        UUID masterId = jdbc.queryForObject(
                "SELECT master_id FROM master_services WHERE id = ?", UUID.class, fixedAssignmentId);
        UUID salonId = jdbc.queryForObject(
                "SELECT owner_id FROM service_definitions WHERE id = ?", UUID.class, fixedDefId);
        UUID freshTypeId = illegalBandTypeIds.get(ILLEGAL_BAND_TYPE_INDEX.getAndIncrement());
        UUID freshDefId = insertServiceDefinition(salonId, freshTypeId, "FIXED", new BigDecimal("100.00"), null);
        jdbc.update(
                "INSERT INTO master_services (id, master_id, service_def_id, price_type_override, "
                        + "price_override, price_max_override, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, true, now(), now())",
                UUID.randomUUID(), masterId, freshDefId, priceTypeOverride, priceOverride, priceMaxOverride);
    }
}
