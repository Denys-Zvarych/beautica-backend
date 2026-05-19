package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for V54__add_locality_fk_and_address.sql (Phase 10.3).
 *
 * <p>The build-verifier's boot only proves V54 <em>applies</em> and that
 * Hibernate {@code ddl-auto=validate} accepted the modified User/Salon entities.
 * That asserts entity↔schema alignment but says nothing about V54's actual
 * outcome — column types, NULLABILITY, the FK constraints, the four indexes,
 * legacy-column survival, or the {@code masters}-table-absence invariant.
 *
 * <p>Per QA playbook Q21, for a schema migration the contract IS its
 * correctness, so this test pins every acceptance criterion of
 * {@code docs/backend-phases/phase-10.3-fk-address-columns.md} directly through
 * {@code information_schema} / {@code pg_indexes} / constraint-catalog SQL —
 * not via JPA state and not row-by-row. The test is fully read-only and
 * order-independent; {@link AbstractIntegrationTest#cleanDb()} does not touch
 * catalog metadata so no fixture or cleanup is required.
 */
@DisplayName("V54 migration — locality FK + light address columns on users & salons")
class LocalityAddressColumnsMigrationTest extends AbstractIntegrationTest {

    private static final String COLUMN_META_QUERY = """
            SELECT data_type, is_nullable, character_maximum_length
            FROM information_schema.columns
            WHERE table_name = ?
              AND column_name = ?
            """;

    private static final String FK_QUERY = """
            SELECT ccu.table_name AS referenced_table
            FROM information_schema.table_constraints tc
            JOIN information_schema.constraint_column_usage ccu
              ON tc.constraint_name = ccu.constraint_name
            WHERE tc.constraint_type = 'FOREIGN KEY'
              AND tc.constraint_name = ?
            """;

    private static final String INDEX_QUERY = """
            SELECT COUNT(*)
            FROM pg_indexes
            WHERE tablename = ?
              AND indexname = ?
            """;

    private static final String COLUMN_EXISTS_QUERY = """
            SELECT COUNT(*)
            FROM information_schema.columns
            WHERE table_name = ?
              AND column_name = ?
            """;

    private Map<String, Object> columnMeta(String table, String column) {
        return jdbcTemplate.queryForMap(COLUMN_META_QUERY, table, column);
    }

    private void assertNullableColumn(
            String table, String column, String expectedType, Integer expectedMaxLength) {
        Map<String, Object> meta = columnMeta(table, column);

        assertThat(meta.get("data_type"))
                .as("%s.%s type", table, column)
                .isEqualTo(expectedType);
        assertThat(meta.get("is_nullable"))
                .as("%s.%s must be NULLABLE — V54 adds locality columns NULLABLE "
                        + "so existing dev rows do not violate a constraint", table, column)
                .isEqualTo("YES");
        assertThat(meta.get("character_maximum_length"))
                .as("%s.%s max length", table, column)
                .isEqualTo(expectedMaxLength);
    }

    // ---------------------------------------------------------------------
    // New locality columns — type + NULLABLE on BOTH users and salons
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("new locality columns (users & salons)")
    class NewColumns {

        @Test
        @DisplayName("users.city_id is a nullable uuid")
        void should_addNullableUuidCityId_when_v54Applied_users() {
            assertNullableColumn("users", "city_id", "uuid", null);
        }

        @Test
        @DisplayName("users.district_id is a nullable uuid")
        void should_addNullableUuidDistrictId_when_v54Applied_users() {
            assertNullableColumn("users", "district_id", "uuid", null);
        }

        @Test
        @DisplayName("users.street is nullable varchar(255)")
        void should_addNullableStreet_when_v54Applied_users() {
            assertNullableColumn("users", "street", "character varying", 255);
        }

        @Test
        @DisplayName("users.building_no is nullable varchar(50)")
        void should_addNullableBuildingNo_when_v54Applied_users() {
            assertNullableColumn("users", "building_no", "character varying", 50);
        }

        @Test
        @DisplayName("users.location_note is a nullable text column (M1: separate landmark field)")
        void should_addNullableLocationNote_when_v54Applied_users() {
            assertNullableColumn("users", "location_note", "text", null);
        }

        @Test
        @DisplayName("salons.city_id is a nullable uuid")
        void should_addNullableUuidCityId_when_v54Applied_salons() {
            assertNullableColumn("salons", "city_id", "uuid", null);
        }

        @Test
        @DisplayName("salons.district_id is a nullable uuid")
        void should_addNullableUuidDistrictId_when_v54Applied_salons() {
            assertNullableColumn("salons", "district_id", "uuid", null);
        }

        @Test
        @DisplayName("salons.street is nullable varchar(255)")
        void should_addNullableStreet_when_v54Applied_salons() {
            assertNullableColumn("salons", "street", "character varying", 255);
        }

        @Test
        @DisplayName("salons.building_no is nullable varchar(50)")
        void should_addNullableBuildingNo_when_v54Applied_salons() {
            assertNullableColumn("salons", "building_no", "character varying", 50);
        }

        @Test
        @DisplayName("salons.location_note is a nullable text column (M1: separate landmark field)")
        void should_addNullableLocationNote_when_v54Applied_salons() {
            assertNullableColumn("salons", "location_note", "text", null);
        }

        @Test
        @DisplayName("street and building_no are distinct columns, location_note separate (M1)")
        void should_keepStreetBuildingAndNoteSeparate_when_v54Applied() {
            // M1: not one opaque address blob — three independent columns on
            // each table give a future geocoder a clean tuple.
            for (String table : new String[] {"users", "salons"}) {
                assertThat(jdbcTemplate.queryForObject(
                        COLUMN_EXISTS_QUERY, Integer.class, table, "street"))
                        .as("%s.street present", table).isEqualTo(1);
                assertThat(jdbcTemplate.queryForObject(
                        COLUMN_EXISTS_QUERY, Integer.class, table, "building_no"))
                        .as("%s.building_no present (distinct from street)", table)
                        .isEqualTo(1);
                assertThat(jdbcTemplate.queryForObject(
                        COLUMN_EXISTS_QUERY, Integer.class, table, "location_note"))
                        .as("%s.location_note present (separate from street/building)", table)
                        .isEqualTo(1);
            }
        }
    }

    // ---------------------------------------------------------------------
    // FK constraints → cities / city_districts
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("foreign-key constraints to the Phase 10.1 taxonomy")
    class ForeignKeys {

        private String referencedTable(String constraintName) {
            return jdbcTemplate.queryForObject(FK_QUERY, String.class, constraintName);
        }

        @Test
        @DisplayName("fk_users_city_id references cities")
        void should_fkUsersCityIdToCities_when_v54Applied() {
            assertThat(referencedTable("fk_users_city_id"))
                    .as("users.city_id FK target")
                    .isEqualTo("cities");
        }

        @Test
        @DisplayName("fk_users_district_id references city_districts")
        void should_fkUsersDistrictIdToCityDistricts_when_v54Applied() {
            assertThat(referencedTable("fk_users_district_id"))
                    .as("users.district_id FK target")
                    .isEqualTo("city_districts");
        }

        @Test
        @DisplayName("fk_salons_city_id references cities")
        void should_fkSalonsCityIdToCities_when_v54Applied() {
            assertThat(referencedTable("fk_salons_city_id"))
                    .as("salons.city_id FK target")
                    .isEqualTo("cities");
        }

        @Test
        @DisplayName("fk_salons_district_id references city_districts")
        void should_fkSalonsDistrictIdToCityDistricts_when_v54Applied() {
            assertThat(referencedTable("fk_salons_district_id"))
                    .as("salons.district_id FK target")
                    .isEqualTo("city_districts");
        }
    }

    // ---------------------------------------------------------------------
    // The four FK indexes
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("FK indexes")
    class Indexes {

        private boolean indexExists(String table, String indexName) {
            Integer n = jdbcTemplate.queryForObject(
                    INDEX_QUERY, Integer.class, table, indexName);
            return n != null && n == 1;
        }

        @Test
        @DisplayName("all four FK indexes exist")
        void should_createFourFkIndexes_when_v54Applied() {
            assertThat(indexExists("users", "idx_users_city_id"))
                    .as("idx_users_city_id").isTrue();
            assertThat(indexExists("users", "idx_users_district_id"))
                    .as("idx_users_district_id").isTrue();
            assertThat(indexExists("salons", "idx_salons_city_id"))
                    .as("idx_salons_city_id").isTrue();
            assertThat(indexExists("salons", "idx_salons_district_id"))
                    .as("idx_salons_district_id").isTrue();
        }
    }

    // ---------------------------------------------------------------------
    // Legacy free-text columns survive (kept NULLABLE, NOT dropped)
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("legacy free-text columns kept (not dropped)")
    class LegacyColumns {

        @Test
        @DisplayName("users.city / users.region survive and are nullable")
        void should_keepLegacyUserColumns_when_v54Applied() {
            assertNullableColumn("users", "city", "character varying", 100);
            assertNullableColumn("users", "region", "character varying", 100);
        }

        @Test
        @DisplayName("salons.city / salons.region / salons.address survive and are nullable")
        void should_keepLegacySalonColumns_when_v54Applied() {
            // Salon legacy free-text columns were created without an explicit
            // length (V3/V4) so they are unbounded varchar — assert presence +
            // nullability, the length is intentionally unconstrained.
            for (String column : new String[] {"city", "region", "address"}) {
                Map<String, Object> meta = columnMeta("salons", column);
                assertThat(meta.get("is_nullable"))
                        .as("salons.%s legacy column must remain NULLABLE (not dropped)", column)
                        .isEqualTo("YES");
            }
        }
    }

    // ---------------------------------------------------------------------
    // masters table has NO locality columns (independent-master locality
    // lives on the User row — Master.java is unchanged this phase)
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("masters table untouched")
    class MastersUntouched {

        @Test
        @DisplayName("masters has none of the five V54 locality columns")
        void should_notAddLocalityColumns_when_v54Applied_masters() {
            for (String column : new String[] {
                    "city_id", "district_id", "street", "building_no", "location_note"}) {
                Integer n = jdbcTemplate.queryForObject(
                        COLUMN_EXISTS_QUERY, Integer.class, "masters", column);
                assertThat(n)
                        .as("masters.%s must NOT exist — independent-master locality "
                                + "lives on the User row; Master.java is unchanged in 10.3",
                                column)
                        .isZero();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Migration-history determinism guard
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Flyway history")
    class FlywayHistory {

        @Test
        @DisplayName("V54 recorded as success with a stable checksum")
        void should_recordV54AsSuccess_when_chainApplied() {
            Map<String, Object> v54 = jdbcTemplate.queryForMap(
                    "SELECT success, checksum, script "
                            + "FROM flyway_schema_history WHERE version = '54'");

            assertThat(v54.get("success"))
                    .as("V54 must be recorded as a successful migration")
                    .isEqualTo(Boolean.TRUE);
            assertThat(v54.get("script"))
                    .isEqualTo("V54__add_locality_fk_and_address.sql");
            assertThat(v54.get("checksum"))
                    .as("V54 must carry a deterministic (non-null) checksum")
                    .isNotNull();
        }
    }
}
