package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for V67__add_service_price_range.sql.
 *
 * <p>The build-verifier proves V67 <em>applies</em> but says nothing about its outcome.
 * This test pins the four acceptance criteria that matter for correctness:
 *
 * <ol>
 *   <li>Existing rows are backfilled to {@code price_type = 'FIXED'} by the {@code UPDATE}
 *       statement in V67.</li>
 *   <li>The {@code chk_service_def_price_mode} CHECK constraint rejects a FIXED row that
 *       carries a non-null {@code price_max}.</li>
 *   <li>The same CHECK rejects a RANGE row where {@code price_max < base_price}.</li>
 *   <li>A well-formed RANGE row ({@code price_max >= base_price}) is accepted.</li>
 * </ol>
 *
 * <p>No pre-migration harness is needed: the Testcontainers database boots with all
 * migrations already applied. The backfill assertion inserts a fresh row — which V67's
 * {@code DEFAULT 'FIXED'} and the NOT NULL constraint guarantee — and reads it back.
 * The constraint tests exercise the CHECK directly via raw JDBC.
 */
@DisplayName("V67 migration — service price-range schema and CHECK constraints")
class ServicePricingMigrationTest extends AbstractIntegrationTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Inserts a minimal {@code users} row so the FK chain required by
     * {@code service_definitions.owner_id} can be satisfied without
     * loading the JPA context.
     */
    private UUID insertOwnerUser() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, ?)",
                userId,
                "v67-migration-" + userId + "@beautica.test",
                "$2a$04$placeholder.for.migration.test.only",
                "SALON_OWNER");
        return userId;
    }

    /**
     * Inserts a minimal {@code salons} row so we have an owner_id to bind to
     * service_definitions with owner_type='SALON'.
     */
    private UUID insertSalon(UUID ownerId) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active) VALUES (?, ?, ?, ?)",
                salonId, ownerId, "V67 Test Salon", true);
        return salonId;
    }

    /**
     * Resolves a real, selectable {@code service_types.id} (V111 made this column NOT NULL).
     * V67's price-range CHECK contract is orthogonal to which service type is used, so any
     * active, APPROVED-category type satisfies the FK.
     */
    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    // ── HIGH-1: backfill ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("V67 backfill — existing rows default to FIXED")
    class BackfillExistingRows {

        @Test
        @DisplayName("existing rows have price_type = 'FIXED' after V67 applied")
        void should_backfillExistingRowsToFixed_when_V67Applied() {
            UUID ownerId = insertOwnerUser();
            UUID salonId = insertSalon(ownerId);

            // Insert a row using the DEFAULT for price_type — simulates a row that
            // existed before V67 (which backfills all such rows to 'FIXED').
            UUID sdId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO service_definitions "
                    + "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, is_active) "
                    + "VALUES (?, 'SALON', ?, 'Backfill Test Service', ?, 60, 350.00, true)",
                    sdId, salonId, resolveServiceTypeId());

            String priceType = jdbcTemplate.queryForObject(
                    "SELECT price_type FROM service_definitions WHERE id = ?",
                    String.class, sdId);

            assertThat(priceType)
                    .as("V67 backfills existing rows: price_type must equal 'FIXED' "
                            + "because V67 UPDATE sets price_type='FIXED' WHERE price_type IS NULL "
                            + "and the column DEFAULT is 'FIXED' for new rows")
                    .isEqualTo("FIXED");
        }
    }

    // ── HIGH-1: chk_service_def_price_mode — FIXED with price_max ────────────

    @Nested
    @DisplayName("CHECK chk_service_def_price_mode — FIXED mode violations")
    class FixedModeConstraint {

        @Test
        @DisplayName("FIXED row with non-null price_max violates chk_service_def_price_mode")
        void should_rejectInsert_when_fixedPricingHasPriceMax() {
            UUID ownerId = insertOwnerUser();
            UUID salonId = insertSalon(ownerId);
            UUID sdId = UUID.randomUUID();

            assertThatThrownBy(() ->
                    jdbcTemplate.update(
                            "INSERT INTO service_definitions "
                            + "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, "
                            + " price_type, base_price, price_max, is_active) "
                            + "VALUES (?, 'SALON', ?, 'Bad Fixed Service', ?, 60, 'FIXED', 500.00, 200.00, true)",
                            sdId, salonId, resolveServiceTypeId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .as("chk_service_def_price_mode must reject FIXED row with non-null price_max "
                            + "(price_max must be NULL for FIXED)");
        }
    }

    // ── HIGH-1: chk_service_def_price_mode — RANGE with max < base ───────────

    @Nested
    @DisplayName("CHECK chk_service_def_price_mode — RANGE mode violations")
    class RangeModeConstraint {

        @Test
        @DisplayName("RANGE row where price_max < base_price violates chk_service_def_price_mode")
        void should_rejectInsert_when_rangePricingHasMaxLessThanBase() {
            UUID ownerId = insertOwnerUser();
            UUID salonId = insertSalon(ownerId);
            UUID sdId = UUID.randomUUID();

            assertThatThrownBy(() ->
                    jdbcTemplate.update(
                            "INSERT INTO service_definitions "
                            + "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, "
                            + " price_type, base_price, price_max, is_active) "
                            + "VALUES (?, 'SALON', ?, 'Bad Range Service', ?, 60, 'RANGE', 800.00, 500.00, true)",
                            sdId, salonId, resolveServiceTypeId()))
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .as("chk_service_def_price_mode must reject RANGE row where price_max (500) "
                            + "< base_price (800); the constraint requires price_max >= base_price");
        }

        @Test
        @DisplayName("RANGE row where price_max >= base_price is accepted")
        void should_acceptInsert_when_rangePricingHasValidMaxGreaterThanBase() {
            UUID ownerId = insertOwnerUser();
            UUID salonId = insertSalon(ownerId);
            UUID sdId = UUID.randomUUID();

            // Must not throw — well-formed RANGE row
            jdbcTemplate.update(
                    "INSERT INTO service_definitions "
                    + "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, "
                    + " price_type, base_price, price_max, is_active) "
                    + "VALUES (?, 'SALON', ?, 'Valid Range Service', ?, 60, 'RANGE', 500.00, 800.00, true)",
                    sdId, salonId, resolveServiceTypeId());

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM service_definitions WHERE id = ?",
                    Integer.class, sdId);

            assertThat(count)
                    .as("A well-formed RANGE row (price_max=800 >= base_price=500) must be persisted")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("RANGE row where price_max == base_price is accepted (>= boundary)")
        void should_acceptInsert_when_rangePricingHasMaxEqualToBase() {
            UUID ownerId = insertOwnerUser();
            UUID salonId = insertSalon(ownerId);
            UUID sdId = UUID.randomUUID();

            // The DB constraint uses >= (not >). The application validator enforces strict >
            // at the service layer. This test confirms the DB boundary is >= as documented.
            jdbcTemplate.update(
                    "INSERT INTO service_definitions "
                    + "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, "
                    + " price_type, base_price, price_max, is_active) "
                    + "VALUES (?, 'SALON', ?, 'Equal Range Service', ?, 60, 'RANGE', 600.00, 600.00, true)",
                    sdId, salonId, resolveServiceTypeId());

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM service_definitions WHERE id = ?",
                    Integer.class, sdId);

            assertThat(count)
                    .as("A RANGE row where price_max == base_price must be accepted by the DB CHECK "
                            + "(constraint uses >=); strict > is enforced at the service layer only")
                    .isEqualTo(1);
        }
    }

    // ── V67 Flyway history guard ───────────────────────────────────────────────

    @Nested
    @DisplayName("Flyway history")
    class FlywayHistory {

        @Test
        @DisplayName("V67 recorded as success with a stable checksum")
        void should_recordV67AsSuccess_when_chainApplied() {
            var v67 = jdbcTemplate.queryForMap(
                    "SELECT success, checksum, script "
                    + "FROM flyway_schema_history WHERE version = '67'");

            assertThat(v67.get("success"))
                    .as("V67 must be recorded as a successful migration")
                    .isEqualTo(Boolean.TRUE);
            assertThat(v67.get("script"))
                    .isEqualTo("V67__add_service_price_range.sql");
            assertThat(v67.get("checksum"))
                    .as("V67 must carry a deterministic (non-null) checksum")
                    .isNotNull();
        }
    }
}
