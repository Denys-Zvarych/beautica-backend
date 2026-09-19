package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@code V168}, the Phase 322 audit-fix migration that makes
 * {@code GET /bookings/salon/{salonId}?partition=} (the mobile salon «Архів» page) plan sanely:
 * <ul>
 *   <li>CREATE {@code idx_bookings_salon_partition_starts_at} on {@code bookings}
 *       {@code (salon_id, starts_at DESC, status, ends_at)} — the two trailing columns are what let
 *       {@code BookingRepositoryCustomImpl#countMatching} evaluate the partition predicate against
 *       the INDEX TUPLE instead of a Bitmap Heap Scan over every heap page the salon touches
 *       (measured 1694 buffers / {@code Heap Blocks: exact=1396} &rarr; 495, Heap Fetches 0).</li>
 *   <li>CREATE {@code idx_bookings_salon_master_partition_starts_at} on
 *       {@code (salon_id, master_id, starts_at DESC, status, ends_at)} and DROP
 *       {@code idx_bookings_salon_master_starts_at} (V148), of which it is a strict superset — the
 *       load-bearing half: with V148's narrower shape the {@code masterId} chip paid one random heap
 *       fetch per row, 1420 buffers for 2826 rows (measured 1420 &rarr; 35).</li>
 * </ul>
 *
 * <p>Per QA playbook Q21, "Flyway applied with no error" proves nothing about a migration's actual
 * outcome, and that is sharper than usual here: an index is INVISIBLE to every functional test. No
 * query returns a different row because of it, and {@code ddl-auto=validate} does NOT check
 * {@code @Table(indexes = ...)} against the real schema (empirically verified — see
 * {@code Booking.java}'s V118 note), so a typo'd, mis-ordered or silently-dropped index would leave
 * the whole suite green while the pathology it exists to remove came straight back.
 *
 * <p><b>Why the two negative/positive schema pins matter as much as the two CREATEs.</b>
 * <ul>
 *   <li>{@code idx_bookings_salon_master_starts_at} must be ABSENT. Carrying V148 alongside its own
 *       strict superset is pure write amplification on the system's hottest table, and a migration
 *       that created the superset while leaving the DROP off would be indistinguishable from a
 *       correct one without this assertion.</li>
 *   <li>{@code idx_bookings_salon_starts_at} (V19) must be PRESENT. It is a strict leading prefix of
 *       the new salon-wide index, so it looks free to drop — but V168 measured the baseline count
 *       REGRESSING 299 &rarr; 432 buffers when it is dropped, because the planner falls onto
 *       {@code idx_bookings_salon_service_starts_at} (V166) rather than the wider new index. This
 *       assertion is what makes a future "clean up the redundant prefix" migration go red instead of
 *       silently costing the salon board's DEFAULT request 44% more buffers.</li>
 * </ul>
 *
 * <p>Fully read-only and order-independent; {@code cleanDb()} never touches catalog metadata, so no
 * fixture or cleanup is required. ASCII-only except this javadoc's quoted screen name.
 */
@DisplayName("V168 migration — salon partition covering indexes (add + replace V148, keep V19)")
class V168SalonPartitionCoveringIndexMigrationTest extends AbstractIntegrationTest {

    private static final String SALON_PARTITION_INDEX = "idx_bookings_salon_partition_starts_at";
    private static final String SALON_MASTER_PARTITION_INDEX = "idx_bookings_salon_master_partition_starts_at";
    private static final String SUPERSEDED_V148_INDEX = "idx_bookings_salon_master_starts_at";
    private static final String V19_PREFIX_INDEX = "idx_bookings_salon_starts_at";

    private String indexDefinition(String indexName) {
        return jdbcTemplate.query(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'bookings' AND indexname = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                indexName);
    }

    @Test
    @DisplayName("V168 — idx_bookings_salon_partition_starts_at exists on bookings ordered "
            + "(salon_id, starts_at DESC, status, ends_at); status/ends_at must come AFTER the "
            + "starts_at sort key or the index stops serving ORDER BY starts_at DESC")
    void should_createSalonPartitionCoveringIndex_when_v168Applied() {
        String definition = indexDefinition(SALON_PARTITION_INDEX);

        assertThat(definition)
                .as("V168's salon-wide covering index must exist on bookings")
                .isNotNull();
        assertThat(definition)
                .as("column ORDER is the whole point. salon_id equality, then starts_at DESC as the "
                        + "SORT key, then the two partition-predicate columns. Hoisting status or "
                        + "ends_at ahead of starts_at would still be a valid index but would stop it "
                        + "serving findIdsBySalonIdFilteredByPartition's ORDER BY starts_at DESC, "
                        + "turning the Incremental Sort into a blocking one. Dropping either trailing "
                        + "column sends the COUNT companion back to the heap, which is the entire "
                        + "1694 -> 495 buffer win.")
                .contains("(salon_id, starts_at DESC, status, ends_at)");
    }

    @Test
    @DisplayName("V168 — idx_bookings_salon_master_partition_starts_at exists on bookings ordered "
            + "(salon_id, master_id, starts_at DESC, status, ends_at): the master-chip shape, "
            + "measured 1420 -> 35 buffers")
    void should_createSalonMasterPartitionCoveringIndex_when_v168Applied() {
        String definition = indexDefinition(SALON_MASTER_PARTITION_INDEX);

        assertThat(definition)
                .as("V168's salon+master covering index must exist on bookings")
                .isNotNull();
        assertThat(definition)
                .as("master_id sits between the salon_id equality and the starts_at sort key (V148's "
                        + "own reasoning), and status/ends_at trail it so the masterId-chip COUNT is "
                        + "an Index Only Scan instead of one random heap fetch per row")
                .contains("(salon_id, master_id, starts_at DESC, status, ends_at)");
    }

    @Test
    @DisplayName("V168 — idx_bookings_salon_master_starts_at (V148) is GONE: the new index is a "
            + "strict superset, so carrying both would be pure write amplification on the hottest "
            + "table. A migration that added the superset and forgot the DROP looks identical "
            + "without this assertion")
    void should_dropSupersededV148Index_when_v168Applied() {
        String supersededDefinition = indexDefinition(SUPERSEDED_V148_INDEX);
        String replacementDefinition = indexDefinition(SALON_MASTER_PARTITION_INDEX);

        assertThat(supersededDefinition)
                .as("V148's (salon_id, master_id, starts_at DESC) must no longer exist — V168 REPLACES "
                        + "it rather than adding beside it")
                .isNull();
        assertThat(replacementDefinition)
                .as("and the replacement must genuinely carry V148's shape as its leading prefix, so "
                        + "nothing V148 served (getSalonBookings' 8-arg ?masterId= overload, Phase "
                        + "23.4) loses its index when V148 disappears")
                .contains("(salon_id, master_id, starts_at DESC,");
    }

    @Test
    @DisplayName("V168 — idx_bookings_salon_starts_at (V19) is deliberately KEPT despite being a "
            + "strict prefix of the new salon-wide index: dropping it measured the baseline count "
            + "regressing 299 -> 432 buffers as the planner fell onto V166's index")
    void should_retainV19PrefixIndex_when_v168Applied() {
        String v19Definition = indexDefinition(V19_PREFIX_INDEX);

        assertThat(v19Definition)
                .as("V19 must still exist. It is the CHEAPEST Index Only Scan for the no-filter salon "
                        + "«Записи» request precisely BECAUSE it is narrow (19 MB of leaf pages against "
                        + "31 MB at 480k rows), and that request is the board's default. Do not 'clean "
                        + "up the redundant prefix' without re-running V168's measurement.")
                .isNotNull();
        assertThat(v19Definition)
                .as("unchanged shape — V168 must not have rebuilt or widened it either")
                .contains("(salon_id, starts_at DESC)");
    }
}
