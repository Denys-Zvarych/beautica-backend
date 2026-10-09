package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for V191 (partial indexes with INCLUDE (id)) (Phase 357 audit-fix).
 * Per QA playbook Q21, Flyway applying cleanly proves nothing about the outcome: pins both partial
 * indexes and their exact predicate via {@code pg_indexes} on the fully migrated chain. Read-only.
 */
@DisplayName("V191 migration — partial indexes for the pending-actions toRateClient leg")
class V191BookingsCompletedClientIndexesMigrationTest extends AbstractIntegrationTest {

    private String indexDef(String indexName) {
        return jdbcTemplate.query(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'bookings' AND indexname = ?",
                (rs, i) -> rs.getString(1), indexName).stream().findFirst().orElse("");
    }

    @Test
    @DisplayName("idx_bookings_master_completed_client is a partial btree on (master_id) for COMPLETED non-guest rows")
    void should_createMasterPartialIndex_when_v191Applied() {
        assertThat(indexDef("idx_bookings_master_completed_client"))
                .contains("(master_id) INCLUDE (id)")
                .contains("COMPLETED")
                .contains("client_id IS NOT NULL");
    }

    @Test
    @DisplayName("idx_bookings_salon_completed_client is a partial btree on (salon_id) for COMPLETED non-guest rows")
    void should_createSalonPartialIndex_when_v191Applied() {
        assertThat(indexDef("idx_bookings_salon_completed_client"))
                .contains("(salon_id) INCLUDE (id)")
                .contains("COMPLETED")
                .contains("client_id IS NOT NULL");
    }

    @Test
    @DisplayName("both indexes are valid btrees (pg_index.indisvalid, pg_am.amname)")
    void should_beValidBtree_when_v192Applied() {
        for (String name : new String[]{"idx_bookings_master_completed_client", "idx_bookings_salon_completed_client"}) {
            var rows = jdbcTemplate.queryForList(
                    "SELECT i.indisvalid AS valid, am.amname AS am FROM pg_class c "
                            + "JOIN pg_index i ON i.indexrelid = c.oid JOIN pg_am am ON am.oid = c.relam "
                            + "WHERE c.relname = ?", name);
            assertThat(rows).as(name).hasSize(1);
            assertThat(rows.get(0).get("valid")).as(name + " valid").isEqualTo(true);
            assertThat(rows.get(0).get("am")).as(name + " am").isEqualTo("btree");
        }
    }
}
