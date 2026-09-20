package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

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
 *       {@code idx_bookings_salon_service_starts_at} (V166) rather than the wider new index — V169
 *       has since replaced that index with {@code idx_bookings_salon_service_partition_starts_at},
 *       which is wider still, so the argument for keeping V19 only got stronger. This assertion is
 *       what makes a future "clean up the redundant prefix" migration go red instead of silently
 *       costing the salon board's DEFAULT request 44% more buffers.</li>
 * </ul>
 *
 * <p><b>And why the catalog pins are NOT sufficient on their own</b> (the missing query-plan
 * assertion finding, backend-perf 2026-09-20). Every buffer number quoted above was, until that
 * audit, unasserted: this class checked {@code pg_indexes.indexdef} TEXT and nothing else. TEXT
 * cannot see whether the shape it pins still BUYS anything — an index that is present, correctly
 * spelled and no longer able to answer its query index-only costs write amplification on the
 * system's hottest table and returns nothing, and no functional test can tell the difference. The
 * {@code should_answer*} / {@code should_order*} cases below close that gap by probing each index's
 * CAPABILITY with every competitor removed.
 *
 * <p><b>They deliberately do NOT assert which index the planner picks.</b> That was the first
 * attempt and it was a flake — measured, the same 6 000 rows produced a different winner depending
 * on whether a preceding test's {@code DELETE} had left reclaimable space in the heap.
 * {@link BookingsIndexCapabilityProbe}'s javadoc carries the measurement, why the capability form is
 * cost-independent by construction, and the one claim (V168's preference for narrow V19 over its own
 * wider index) that is accepted as untestable in consequence.
 *
 * <p>Every case here is read-only and order-independent: the catalog assertions read
 * {@code pg_indexes}, and the capability probes seed nothing, need no {@code VACUUM}, and roll their
 * transaction back. ASCII-only except this javadoc's quoted screen names.
 */
@DisplayName("V168 migration — salon partition covering indexes (add + replace V148, keep V19)")
class V168SalonPartitionCoveringIndexMigrationTest extends AbstractIntegrationTest {

    private static final String SALON_PARTITION_INDEX = "idx_bookings_salon_partition_starts_at";
    private static final String SALON_MASTER_PARTITION_INDEX = "idx_bookings_salon_master_partition_starts_at";
    private static final String SUPERSEDED_V148_INDEX = "idx_bookings_salon_master_starts_at";
    private static final String V19_PREFIX_INDEX = "idx_bookings_salon_starts_at";

    private BookingsIndexCapabilityProbe probe;

    /**
     * Fresh per test, and matching NO row: the capability probe asserts what the index can serve,
     * which is a property of the index's shape, not of any data. Random rather than a shared
     * constant so no case can come to depend on another's fixture.
     */
    private UUID salonId;
    private UUID masterId;

    @BeforeEach
    void buildProbe() {
        probe = new BookingsIndexCapabilityProbe(jdbcTemplate);
        salonId = UUID.randomUUID();
        masterId = UUID.randomUUID();
    }

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


    // ── index capability (the missing query-plan assertion finding, backend-perf 2026-09-20) ──
    //
    // These five cases assert what each index CAN do, with every competing index removed, not which
    // index the planner picks when they all compete. The choice-based form these replace was
    // measured flipping on where a preceding test's rows happened to land in the heap — see
    // BookingsIndexCapabilityProbe's javadoc for the measurement and for what the change gives up.

    @Test
    @DisplayName("V168 — the salon-wide partition COUNT is answerable from "
            + "idx_bookings_salon_partition_starts_at ALONE, index-only: the trailing status and "
            + "ends_at make the partition predicate readable from the index tuple, which is the "
            + "measured 1694 -> 495 buffer win. Narrow the index back to V19's shape and this "
            + "becomes a plain Index Scan")
    void should_answerTheSalonWidePartitionCountFromTheIndexTuple_when_onlyV168sSalonIndexExists() {
        String plan = probe.explainWithOnly(SALON_PARTITION_INDEX,
                BookingsIndexCapabilityProbe.countWhere(
                        BookingsIndexCapabilityProbe.salonScope(salonId)
                                + " AND " + BookingsIndexCapabilityProbe.historyPredicate()));

        assertThat(plan)
                .as("Index ONLY Scan, not a plain Index Scan. Postgres emits an index-only plan if "
                        + "and only if the index supplies every column the query references, so a "
                        + "plain Index Scan here means status or ends_at is back on the heap and "
                        + "the COUNT reads the salon's whole physical footprint again — exactly the "
                        + "pathology V168 removed. Plan:%n%s", plan)
                .contains("Index Only Scan using " + SALON_PARTITION_INDEX);
        assertThat(plan)
                .as("salon_id must be a SEEK BOUND, not a residual filter over the whole index — "
                        + "the leading key position is what makes the scan proportional to the "
                        + "salon rather than to the table. Plan:%n%s", plan)
                .contains("Index Cond: (salon_id =");
    }

    @Test
    @DisplayName("V168 — the masterId-chip partition COUNT is answerable from "
            + "idx_bookings_salon_master_partition_starts_at ALONE, index-only, seeking on BOTH "
            + "salon_id and master_id: with V148's narrower shape it is a plain Index Scan paying "
            + "one random heap fetch per row (measured 1420 -> 35 buffers)")
    void should_answerTheMasterChipPartitionCountFromTheIndexTuple_when_onlyV168sSalonMasterIndexExists() {
        String plan = probe.explainWithOnly(SALON_MASTER_PARTITION_INDEX,
                BookingsIndexCapabilityProbe.countWhere(
                        BookingsIndexCapabilityProbe.salonScope(salonId)
                                + " AND " + BookingsIndexCapabilityProbe.masterScope(masterId)
                                + " AND " + BookingsIndexCapabilityProbe.historyPredicate()));

        assertThat(plan)
                .as("the master chip must be served index-only. Rebuilt in V148's "
                        + "(salon_id, master_id, starts_at DESC) shape this measured as a plain "
                        + "Index Scan — one random heap fetch per matched row, 20x worse per row "
                        + "than the salon-wide shape it exists to narrow. Plan:%n%s", plan)
                .contains("Index Only Scan using " + SALON_MASTER_PARTITION_INDEX);
        assertThat(plan)
                .as("master_id sits between the salon_id equality and the starts_at sort key, so "
                        + "BOTH must be seek bounds. If master_id fell into a residual Filter the "
                        + "chip would scan every booking the salon ever took. Plan:%n%s", plan)
                .contains("Index Cond: ((salon_id =")
                .contains("AND (master_id =");
    }

    @Test
    @DisplayName("V168 — the ORDERED archive page rides idx_bookings_salon_partition_starts_at with "
            + "NO Sort node: this is the behavioural half of the column-ORDER pin above, and the "
            + "only case here that can see a re-ordered index — hoisting status/ends_at ahead of "
            + "starts_at leaves the COUNT index-only but re-introduces a blocking Sort")
    void should_orderTheArchivePageWithoutASort_when_onlyV168sSalonIndexExists() {
        String plan = probe.explainWithOnly(SALON_PARTITION_INDEX,
                BookingsIndexCapabilityProbe.orderedPageWhere(
                        BookingsIndexCapabilityProbe.salonScope(salonId)
                                + " AND " + BookingsIndexCapabilityProbe.historyPredicate()));

        assertThat(plan)
                .as("findIdsBySalonIdFilteredByPartition's ORDER BY starts_at DESC must be served "
                        + "by the index's own ordering. A Sort node means the page cannot stream: "
                        + "every matching booking in the salon is materialised and sorted before "
                        + "the LIMIT can discard it. Plan:%n%s", plan)
                .contains("Index Scan using " + SALON_PARTITION_INDEX)
                .doesNotContain("Sort");
    }

    @Test
    @DisplayName("CONTROL — the same salon scope with one predicate the index does NOT carry "
            + "(price_at_booking) degrades to a plain Index Scan, so the Index-Only assertions "
            + "above cannot pass vacuously")
    void should_fallBackToAPlainIndexScan_when_aPredicateIsCoveredByNoColumnOfTheIndex() {
        String plan = probe.explainWithOnly(SALON_PARTITION_INDEX,
                BookingsIndexCapabilityProbe.countWhere(
                        BookingsIndexCapabilityProbe.salonScope(salonId)
                                + " AND b.price_at_booking > 0"));

        assertThat(plan)
                .as("price_at_booking is in no key or INCLUDE column of this index, so the plan "
                        + "MUST leave the index tuple and visit the heap. If it does not, EXPLAIN "
                        + "is not reporting what the assertions above believe it reports and they "
                        + "prove nothing. Plan:%n%s", plan)
                .contains("Index Scan using " + SALON_PARTITION_INDEX)
                .doesNotContain("Index Only Scan");
    }

    @Test
    @DisplayName("V168 — V19 still COVERS the unfiltered salon «Записи» request index-only, which "
            + "is the reason it was kept. Which of the covering indexes the planner then PREFERS is "
            + "a cost claim and is deliberately not asserted — see the probe's javadoc")
    void should_answerTheUnfilteredSalonCountFromTheIndexTuple_when_onlyV19Exists() {
        String plan = probe.explainWithOnly(V19_PREFIX_INDEX,
                BookingsIndexCapabilityProbe.countWhere(
                        BookingsIndexCapabilityProbe.salonScope(salonId)));

        assertThat(plan)
                .as("V19 is kept BECAUSE it is the narrow index that answers the board's DEFAULT "
                        + "request (19 MB of leaf pages against 31 MB at 480k rows, measured 299 "
                        + "buffers against 432 when it is dropped). A V19 that had been rebuilt or "
                        + "widened such that it no longer answers this request index-only would "
                        + "make the whole keep-it decision moot. Plan:%n%s", plan)
                .contains("Index Only Scan using " + V19_PREFIX_INDEX);
    }
}
