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
        // Only THREE types are reserved by name here (the three base fixtures below). The
        // throwaway definitions insertBand() creates resolve their own unused type per call —
        // see resolveUnusedServiceTypeId.
        List<UUID> typeIds = resolveServiceTypeIds(3);

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

    // ── Case 31 (Q7, 2026-09-13 audit) — the documented >=(DB) / >(Java) ASYMMETRY ──────────────
    //
    // MasterServiceBand#isLegal uses STRICT > for a RANGE floor/ceiling; V165's
    // chk_master_service_price_mode deliberately uses >=, mirroring chk_service_def_price_mode
    // (V67), so the DB is the tolerant outer backstop and the application layer is strict. That
    // asymmetry is documented in three places and was pinned by NOTHING: cases 29a/b/c cover
    // NULL-ceiling, FIXED-with-ceiling and ceiling<floor, but no case proved the CHECK ACCEPTS a
    // degenerate RANGE x-x row. An edit "aligning" the constraint to > would have gone green here
    // while silently changing what the database will store.
    //
    // A CHECK constraint cannot be proven by a test suite — only by raw SQL, in BOTH directions.

    @Test
    @DisplayName("Case 31a (Q7): chk_master_service_price_mode ACCEPTS a degenerate RANGE x-x row "
            + "— the DB comparison is >=, NOT the application layer's strict >")
    void should_acceptDegenerateRangeBand_provingTheDbComparisonIsGreaterOrEqual() {
        UUID rowId = insertBand("RANGE", new BigDecimal("500.00"), new BigDecimal("500.00"));

        Map<String, Object> row = assignmentRow(rowId);
        assertThat(row.get("price_type_override")).isEqualTo("RANGE");
        assertThat((BigDecimal) row.get("price_override")).isEqualByComparingTo("500.00");
        assertThat((BigDecimal) row.get("price_max_override"))
                .as("Q7 — the row is STORABLE. No API write path produces it (Phase 312 D8 keeps "
                        + "Java strict), but the DB must keep accepting it: tightening the CHECK to "
                        + "> is a schema change that would reject pre-existing rows, and this test "
                        + "is what turns that into a visible decision instead of a silent one.")
                .isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("Case 31b (Q7): chk_master_service_price_type rejects a price_type_override "
            + "literal outside {FIXED, RANGE}")
    void should_rejectUnknownPriceTypeLiteral() {
        Throwable thrown = catchThrowable(() ->
                insertBand("PREMIUM", new BigDecimal("500.00"), null));

        assertThat(thrown).isInstanceOf(RuntimeException.class).hasCauseInstanceOf(SQLException.class);
        // Postgres may report EITHER constraint: chk_master_service_price_mode is a strict SUPERSET
        // of chk_master_service_price_type — for any non-NULL price_type_override, mode already
        // requires the literal to be 'FIXED' or 'RANGE', so no row can violate the type constraint
        // alone. That subsumption is itself worth recording: chk_master_service_price_type is a
        // readable, self-documenting guard rather than an independently reachable one, and this
        // test pins that 'PREMIUM' is REFUSED by the database (which is the security property) and
        // that it is refused by one of the two NAMED band constraints — never by a masking
        // NOT NULL or UNIQUE violation on some other column, which is how case 30 used to pass for
        // the wrong reason.
        assertThat(thrown.getCause().getMessage())
                .as("a VARCHAR(10) column accepts 'PREMIUM' happily; only a CHECK refuses it, and "
                        + "the refusal must come from a band constraint by name")
                .satisfiesAnyOf(
                        m -> assertThat(m).contains("chk_master_service_price_type"),
                        m -> assertThat(m).contains("chk_master_service_price_mode"));
    }

    @Test
    @DisplayName("Case 31c (Q7): chk_master_service_price_type ACCEPTS a NULL price_type_override "
            + "— non-vacuity for 31b, and the Inherited majority must stay insertable")
    void should_acceptNullPriceTypeOverride() {
        UUID rowId = insertBand(null, null, null);

        assertThat(assignmentRow(rowId).get("price_type_override"))
                .as("an Inherited row is the 14 260-row majority; the literal guard must not "
                        + "reject it, or 31b would be passing because ALL values are rejected")
                .isNull();
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

    /**
     * The first active, selectable {@code service_types} row this salon has NO
     * {@code service_definitions} row for yet — so every throwaway definition {@link #insertBand}
     * creates lands on a distinct type and {@code UNIQUE (owner, service_type_id)} can never mask a
     * CHECK-constraint outcome.
     *
     * <p><b>Replaces an order-coupled counter (2026-09-13 cycle-2 audit, B12).</b> This used to be
     * {@code illegalBandTypeIds.get(ILLEGAL_BAND_TYPE_INDEX.getAndIncrement())} — a static
     * {@code AtomicInteger} indexing an 11-slot pre-sliced list. That is shared mutable state across
     * test methods: it silently couples the fixture to how many times the helper happens to be
     * called and in what order, and adding a twelfth case would have run off the end of the slice
     * with an {@code IndexOutOfBoundsException} in an unrelated-looking test. Deriving the type from
     * the DATABASE's own state is order-independent, needs no budget, and says what it means.
     */
    private static UUID resolveUnusedServiceTypeId(UUID salonId) {
        return jdbc.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "  AND NOT EXISTS (SELECT 1 FROM service_definitions sd "
                        + "                  WHERE sd.owner_type = 'SALON' AND sd.owner_id = ? "
                        + "                    AND sd.service_type_id = st.id) "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class, salonId);
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
        insertBand(priceTypeOverride, priceOverride, priceMaxOverride);
    }

    /**
     * Inserts ONE {@code master_services} row with the given band, against a FRESH
     * {@code service_definitions} row so {@code UNIQUE (master_id, service_def_id)} can never mask
     * (or fake) a CHECK-constraint outcome. Returns the new row's id; throws whatever the driver
     * throws when a constraint refuses it.
     *
     * <p>Used by both the rejection cases and, since the 2026-09-13 audit (Q7), the ACCEPTANCE
     * cases — a CHECK constraint can only be proven by raw SQL, in both directions.
     */
    private static UUID insertBand(String priceTypeOverride, BigDecimal priceOverride, BigDecimal priceMaxOverride) {
        UUID masterId = jdbc.queryForObject(
                "SELECT master_id FROM master_services WHERE id = ?", UUID.class, fixedAssignmentId);
        UUID salonId = jdbc.queryForObject(
                "SELECT owner_id FROM service_definitions WHERE id = ?", UUID.class, fixedDefId);
        UUID freshTypeId = resolveUnusedServiceTypeId(salonId);
        UUID freshDefId = insertServiceDefinition(salonId, freshTypeId, "FIXED", new BigDecimal("100.00"), null);
        UUID rowId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO master_services (id, master_id, service_def_id, price_type_override, "
                        + "price_override, price_max_override, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, true, now(), now())",
                rowId, masterId, freshDefId, priceTypeOverride, priceOverride, priceMaxOverride);
        return rowId;
    }
}
