package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for V35__add_search_indexes_and_user_geo.sql (Phase 6.1).
 *
 * <p>The build-verifier's boot only proves V35 <em>applies</em> and that
 * Hibernate {@code ddl-auto=validate} accepted the entities. That says nothing
 * about V35's actual outcome — the two new geo columns on {@code users}, the
 * four search indexes it creates (one of them a <em>partial</em> index with a
 * {@code WHERE price_override IS NOT NULL} predicate), and the legacy
 * {@code idx_salons_city} it DROPs.
 *
 * <p>Per QA playbook Q21, for a schema migration the contract IS its
 * correctness, so this test pins every declared object of V35 directly through
 * {@code information_schema} / {@code pg_indexes} SQL — not via JPA state. The
 * test is fully read-only and order-independent; {@code cleanDb()} does not
 * touch catalog metadata, so no fixture or cleanup is required.
 */
@DisplayName("V35 migration — search indexes + users geo columns")
class SearchIndexesAndUserGeoMigrationTest extends AbstractIntegrationTest {

    private static final String COLUMN_META_QUERY = """
            SELECT data_type, is_nullable, character_maximum_length
            FROM information_schema.columns
            WHERE table_name = ?
              AND column_name = ?
            """;

    private static final String INDEX_EXISTS_QUERY = """
            SELECT COUNT(*)
            FROM pg_indexes
            WHERE tablename = ?
              AND indexname = ?
            """;

    private static final String INDEX_DEF_QUERY = """
            SELECT indexdef
            FROM pg_indexes
            WHERE tablename = ?
              AND indexname = ?
            """;

    private boolean indexExists(String table, String indexName) {
        Integer n = jdbcTemplate.queryForObject(INDEX_EXISTS_QUERY, Integer.class, table, indexName);
        return n != null && n == 1;
    }

    private String indexDef(String table, String indexName) {
        return jdbcTemplate.queryForObject(INDEX_DEF_QUERY, String.class, table, indexName);
    }

    // ---------------------------------------------------------------------
    // Step 0 — geo columns on users (city, region)
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("users geo columns")
    class GeoColumns {

        private void assertNullableVarchar100(String column) {
            Map<String, Object> meta = jdbcTemplate.queryForMap(COLUMN_META_QUERY, "users", column);
            assertThat(meta.get("data_type"))
                    .as("users.%s type", column)
                    .isEqualTo("character varying");
            assertThat(meta.get("is_nullable"))
                    .as("users.%s must be NULLABLE — V35 adds geo columns NULLABLE "
                            + "so pre-existing rows do not violate a constraint", column)
                    .isEqualTo("YES");
            assertThat(meta.get("character_maximum_length"))
                    .as("users.%s VARCHAR length", column)
                    .isEqualTo(100);
        }

        @Test
        @DisplayName("users.city is a nullable varchar(100)")
        void should_addNullableCity_when_v35Applied() {
            assertNullableVarchar100("city");
        }

        @Test
        @DisplayName("users.region is a nullable varchar(100)")
        void should_addNullableRegion_when_v35Applied() {
            assertNullableVarchar100("region");
        }
    }

    // ---------------------------------------------------------------------
    // Step 1 — the four search indexes V35 creates
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("search indexes created")
    class CreatedIndexes {

        // The schema asserted here is the FULL migrated chain, not V35 in
        // isolation. V36__search_perf_indexes.sql supersedes two of V35's
        // indexes (idx_users_city_region, idx_masters_avg_rating) with partial
        // variants and DROPs the originals IN THE SAME MIGRATION. Those two are
        // therefore asserted under DroppedIndex below. idx_salons_city_region is
        // the only V35-created search index that survives the chain unchanged.

        @Test
        @DisplayName("idx_salons_city_region exists on salons(city, region) and survives the chain")
        void should_createSalonsCityRegionIndex_when_v35Applied() {
            assertThat(indexExists("salons", "idx_salons_city_region"))
                    .as("idx_salons_city_region").isTrue();
            assertThat(indexDef("salons", "idx_salons_city_region"))
                    .as("idx_salons_city_region must be the composite (city, region) B-tree")
                    .contains("city")
                    .contains("region");
        }
    }

    // ---------------------------------------------------------------------
    // Partial index on master_services.price_override
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("partial index on price_override")
    class PartialIndex {

        @Test
        @DisplayName("idx_master_services_price_override exists")
        void should_createPriceOverridePartialIndex_when_v35Applied() {
            assertThat(indexExists("master_services", "idx_master_services_price_override"))
                    .as("idx_master_services_price_override").isTrue();
        }

        @Test
        @DisplayName("idx_master_services_price_override is PARTIAL with WHERE price_override IS NOT NULL")
        void should_carryNotNullPredicate_when_v35Applied() {
            // The whole point of V35's partial index is the predicate: a full B-tree on a
            // mostly-NULL column is wasted writes. If a future migration recreates this index
            // without the WHERE clause, the partial-index intent silently regresses — pin the
            // predicate via the catalog index definition so that drift fails the build.
            String def = indexDef("master_services", "idx_master_services_price_override");

            assertThat(def)
                    .as("idx_master_services_price_override must be PARTIAL "
                            + "(WHERE price_override IS NOT NULL), not a full B-tree")
                    .contains("WHERE")
                    .contains("price_override IS NOT NULL");
        }
    }

    // ---------------------------------------------------------------------
    // Legacy idx_salons_city is DROPPED (now redundant under the composite)
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("legacy / superseded indexes dropped")
    class DroppedIndex {

        @Test
        @DisplayName("idx_salons_city no longer exists (V35 drop — superseded by idx_salons_city_region)")
        void should_dropLegacySalonsCityIndex_when_v35Applied() {
            assertThat(indexExists("salons", "idx_salons_city"))
                    .as("idx_salons_city must be DROPPED by V35 — the composite "
                            + "idx_salons_city_region handles WHERE city = ? via leftmost-prefix")
                    .isFalse();
        }

        @Test
        @DisplayName("idx_users_city_region no longer exists (V36 drop — replaced by partial idx_users_active_city_region)")
        void should_dropUsersCityRegionIndex_when_v36Applied() {
            // V35 created idx_users_city_region; V36 DROPs it and replaces it with
            // a partial (WHERE is_active = true) variant. The migrated end-state
            // must reflect V36, not V35's transient object.
            assertThat(indexExists("users", "idx_users_city_region"))
                    .as("idx_users_city_region must be DROPPED by V36")
                    .isFalse();

            assertThat(indexExists("users", "idx_users_active_city_region"))
                    .as("V36 replacement idx_users_active_city_region must exist")
                    .isTrue();
            assertThat(indexDef("users", "idx_users_active_city_region"))
                    .as("idx_users_active_city_region must be PARTIAL on (city, region) WHERE is_active")
                    .contains("city")
                    .contains("region")
                    .contains("WHERE")
                    .contains("is_active");
        }

        @Test
        @DisplayName("idx_masters_avg_rating no longer exists (V36 drop — replaced by partial idx_masters_active_rating)")
        void should_dropMastersAvgRatingIndex_when_v36Applied() {
            // V35 created the non-partial idx_masters_avg_rating; V36 DROPs it and
            // replaces it with idx_masters_active_rating (avg_rating DESC NULLS LAST, id)
            // WHERE is_active = true.
            assertThat(indexExists("masters", "idx_masters_avg_rating"))
                    .as("idx_masters_avg_rating must be DROPPED by V36")
                    .isFalse();

            assertThat(indexExists("masters", "idx_masters_active_rating"))
                    .as("V36 replacement idx_masters_active_rating must exist")
                    .isTrue();
            assertThat(indexDef("masters", "idx_masters_active_rating"))
                    .as("idx_masters_active_rating must keep avg_rating DESC and be PARTIAL on is_active")
                    .contains("avg_rating DESC")
                    .contains("WHERE")
                    .contains("is_active");
        }
    }

    // ---------------------------------------------------------------------
    // Migration-history determinism guard
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("Flyway history")
    class FlywayHistory {

        @Test
        @DisplayName("V35 recorded as success with a stable checksum")
        void should_recordV35AsSuccess_when_chainApplied() {
            Map<String, Object> v35 = jdbcTemplate.queryForMap(
                    "SELECT success, checksum, script "
                            + "FROM flyway_schema_history WHERE version = '35'");

            assertThat(v35.get("success"))
                    .as("V35 must be recorded as a successful migration")
                    .isEqualTo(Boolean.TRUE);
            assertThat(v35.get("script"))
                    .isEqualTo("V35__add_search_indexes_and_user_geo.sql");
            assertThat(v35.get("checksum"))
                    .as("V35 must carry a deterministic (non-null) checksum")
                    .isNotNull();
        }
    }
}
