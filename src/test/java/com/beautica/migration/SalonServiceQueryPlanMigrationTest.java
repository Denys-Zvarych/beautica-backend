package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for the schema behind {@code GET /bookings/salon/{salonId}?serviceId=...} — the
 * Phase 319 pair that created it and the PR #129 audit fix that superseded half of it:
 * <ul>
 *   <li>{@code V166} &rarr; CREATE {@code idx_bookings_salon_service_starts_at} on {@code bookings}
 *       {@code (salon_id, master_service_id, starts_at DESC)} — the composite that keeps a
 *       SINGLE-valued service chip on the salon scope with an Incremental Sort instead of leading
 *       with the V18 FK index and paying a blocking Sort. <b>DROPPED by V169</b>, whose KEY LIST IS
 *       IDENTICAL to it — same columns, same order, same DESC — differing only by a non-key
 *       {@code INCLUDE (status, ends_at)} payload. Not a strict prefix, which would be the weaker
 *       claim; identical keys mean every access path V166 served, V169 serves the same way.</li>
 *   <li>{@code V167} &rarr; CREATE STATISTICS {@code st_bookings_salon_service} on
 *       {@code (salon_id, master_service_id)} — the extended statistics that stop the planner
 *       multiplying two selectivities it wrongly assumes are independent, which is what made
 *       TWO chips ({@code = ANY} on a middle index column) fall off the composite and back onto a
 *       blocking Sort over the whole match set.</li>
 *   <li>{@code V169} &rarr; CREATE {@code idx_bookings_salon_service_partition_starts_at} on
 *       {@code (salon_id, master_service_id, starts_at DESC) INCLUDE (status, ends_at)} and DROP
 *       V166's index. {@code BookingController#getSalonBookings} accepts {@code ?serviceId=} and
 *       {@code ?partition=} on the SAME request, and V168 never measured that combination: with no
 *       index carrying both {@code master_service_id} AND the partition columns, the planner
 *       abandoned every salon-scope index and led with {@code idx_bookings_master_service_id}
 *       (V18's bare FK index) into a Bitmap Heap Scan — one random heap fetch per matched row.
 *       Measured, the {@code COUNT} companion for {@code partition=HISTORY} + one {@code serviceId}
 *       went 103 buffers (Heap Blocks exact=100) &rarr; 5 (Index Only Scan, Heap Fetches 0).</li>
 * </ul>
 *
 * <p><b>This class is named for a query plan and, as of PR #129, actually asserts one</b> (audit
 * findings: the missing query-plan assertion, backend-perf 2026-09-20, and the
 * query-plan-in-name-only class naming, backend-QA 2026-09-20). It previously asserted
 * {@code pg_indexes.indexdef} / {@code pg_statistic_ext} TEXT only, which cannot see whether the
 * shape it pins still BUYS anything: an index that is present, correctly spelled and no longer able
 * to answer its query index-only costs write amplification on the hottest table and returns nothing.
 *
 * <p><b>The plan cases below assert CAPABILITY, not the planner's choice.</b> Pinning the chosen
 * index was the first attempt and it was a flake — measured, the same 6 000-row fixture produced a
 * different winner depending on whether a preceding test's {@code DELETE} had left reclaimable
 * space in the heap. {@link BookingsIndexCapabilityProbe}'s javadoc carries the measurement, why
 * the capability form is cost-independent by construction, and what it gives up. Absolute buffer
 * counts remain deliberately unasserted — they are fixture- and version-dependent.
 *
 * <p>Per QA playbook Q21, "Flyway applied with no error" proves nothing about a migration's actual
 * outcome — and that is sharper than usual for {@code V167}: a statistics object is INVISIBLE in
 * every functional test. No query returns a different row because of it, {@code ddl-auto=validate}
 * knows nothing about {@code pg_statistic_ext}, and a typo'd or silently-dropped
 * {@code CREATE STATISTICS} would leave this entire suite green while the IN(2) pathology it exists
 * to remove came straight back. This test is the only thing standing behind it.
 *
 * <p><b>Why the populated-data half of V167 is deliberately NOT asserted.</b> {@code V167} ends with
 * {@code ANALYZE bookings}, without which the object is declared but the planner sees nothing.
 * Asserting a {@code pg_statistic_ext_data} row from the MIGRATION would be a FIXTURE assertion, not
 * a migration one: Postgres stores no extended-statistics data for a zero-row relation (measured on
 * PG 16), and {@code bookings} is empty when the Testcontainers chain migrates. The assertion would
 * pin "the table was empty", go green forever, and say nothing about the {@code ANALYZE} line.
 *
 * <p>Every case here is read-only and order-independent: the catalog assertions read
 * {@code pg_indexes} / {@code pg_statistic_ext}, and the capability probes seed nothing, need no
 * {@code VACUUM}, and roll their transaction back. ASCII-only.
 */
@DisplayName("V166/V167/V169 migrations — salon+service booking query PLAN (index, statistics, "
        + "and the partition-covering replacement)")
class SalonServiceQueryPlanMigrationTest extends AbstractIntegrationTest {

    private static final String V169_INDEX = "idx_bookings_salon_service_partition_starts_at";
    private static final String SUPERSEDED_V166_INDEX = "idx_bookings_salon_service_starts_at";
    private static final String V18_FK_INDEX = "idx_bookings_master_service_id";
    private static final String STATISTICS_NAME = "st_bookings_salon_service";

    private BookingsIndexCapabilityProbe probe;

    /**
     * Fresh per test, and matching NO row: the capability probe asserts what the index can serve,
     * which is a property of the index's shape, not of any data. Random rather than a shared
     * constant so no case can come to depend on another's fixture.
     */
    private UUID salonId;
    private UUID masterServiceId;

    @BeforeEach
    void buildProbe() {
        probe = new BookingsIndexCapabilityProbe(jdbcTemplate);
        salonId = UUID.randomUUID();
        masterServiceId = UUID.randomUUID();
    }

    private String indexDefinition(String indexName) {
        List<String> definitions = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'bookings' AND indexname = ?",
                String.class, indexName);
        return definitions.isEmpty() ? null : definitions.get(0);
    }

    // ── catalog shape ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("V169 — idx_bookings_salon_service_partition_starts_at exists on bookings, keyed "
            + "(salon_id, master_service_id, starts_at DESC) with status/ends_at as an INCLUDE "
            + "payload; promoting either to a key column measured WORSE on partition=PAST")
    void should_createSalonServicePartitionCoveringIndex_when_v169Applied() {
        String definition = indexDefinition(V169_INDEX);

        assertThat(definition)
                .as("V169's covering index must exist on bookings")
                .isNotNull();
        assertThat(definition)
                .as("key column ORDER is the whole point: salon_id equality, then the service "
                        + "equality, then starts_at as the SORT key. A different order cannot serve "
                        + "findIdsBySalonIdFilteredByPartition's ORDER BY starts_at DESC without a "
                        + "blocking Sort.")
                .contains("(salon_id, master_service_id, starts_at DESC)");
        assertThat(definition)
                .as("status and ends_at must be an INCLUDE payload, NOT trailing key columns. They "
                        + "are filter payload on this path — never a seek bound (HISTORY is a "
                        + "NEGATION, PAST an OR) and never a sort key. Measured, promoting them to "
                        + "key columns makes the planner append a status scan key that multiplies "
                        + "index descents by the status-list length: partition=PAST + serviceId(20) "
                        + "counted 247 buffers against 84 for this shape. Both variants are 39 MB, "
                        + "so the trade is pure plan quality. See V169's measurement table.")
                .contains("INCLUDE (status, ends_at)");
    }

    @Test
    @DisplayName("V169 — idx_bookings_salon_service_starts_at (V166) is GONE: V169 carries V166's "
            + "key list IDENTICALLY plus an INCLUDE payload, so it is a strict capability superset, "
            + "and measured, carrying both costs +3.1 buffers on EVERY booking INSERT to save 11 "
            + "on one non-default read")
    void should_dropSupersededV166Index_when_v169Applied() {
        String supersededDefinition = indexDefinition(SUPERSEDED_V166_INDEX);
        String replacementDefinition = indexDefinition(V169_INDEX);

        assertThat(supersededDefinition)
                .as("V166's (salon_id, master_service_id, starts_at DESC) must no longer exist — "
                        + "V169 REPLACES it rather than adding beside it. Unlike V168's decision to "
                        + "KEEP V19 (where dropping the prefix measured the board's DEFAULT request "
                        + "regressing 299 -> 432 buffers), the counter-measurement here says the "
                        + "opposite: keeping V166 improves exactly ONE shape in the whole table "
                        + "(serviceId(20) with no partition, 84 -> 73) and it is no screen's default.")
                .isNull();
        assertThat(replacementDefinition)
                .as("and the replacement must genuinely carry V166's shape as its leading key "
                        + "prefix, so nothing V166 served loses its index when V166 disappears")
                .contains("(salon_id, master_service_id, starts_at DESC)");
    }

    @Test
    @DisplayName("V167 — st_bookings_salon_service exists on bookings over exactly "
            + "(salon_id, master_service_id) with all three kinds (ndistinct, dependencies, mcv); "
            + "dropping any one of them silently degrades a different estimate")
    void should_createSalonServiceExtendedStatistics_when_v167Applied() {
        Integer count = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM pg_statistic_ext e
                JOIN pg_class c ON c.oid = e.stxrelid
                WHERE c.relname = 'bookings' AND e.stxname = ?
                """,
                Integer.class, STATISTICS_NAME);

        String kinds = jdbcTemplate.queryForObject(
                "SELECT stxkind::text FROM pg_statistic_ext WHERE stxname = ?",
                String.class, STATISTICS_NAME);

        List<String> columns = jdbcTemplate.queryForList(
                """
                SELECT a.attname
                FROM pg_statistic_ext e
                JOIN pg_attribute a ON a.attrelid = e.stxrelid AND a.attnum = ANY (e.stxkeys)
                WHERE e.stxname = ?
                ORDER BY a.attname
                """,
                String.class, STATISTICS_NAME);

        assertThat(count)
                .as("V167's statistics object must exist and must be attached to bookings")
                .isEqualTo(1);
        assertThat(kinds)
                .as("d = ndistinct, f = dependencies, m = mcv. `dependencies` alone is not enough: "
                        + "it is consulted only for plain equality, and the IN(2) shape this "
                        + "migration exists to fix is decomposed into ORed equalities whose "
                        + "combined selectivity the MCV list is what bounds.")
                .contains("d")
                .contains("f")
                .contains("m");
        assertThat(columns)
                .as("exactly the correlated pair — a third column would change which estimates the "
                        + "object answers")
                .containsExactly("master_service_id", "salon_id");
    }


    // ── index capability ───────────────────────────────────────────────────────
    //
    // What each index CAN serve, with every competing index removed — not which index the planner
    // picks when they all compete. See BookingsIndexCapabilityProbe's javadoc for why the
    // choice-based form these replace was measured flipping on heap layout alone.
    //
    // The first two cases are a PAIR, and the pair is what pins the INCLUDE clause specifically:
    // rebuilt in V166's shape (identical keys, no INCLUDE payload) the partition case goes RED and
    // the no-partition case stays GREEN, because only the former references status and ends_at.

    @Test
    @DisplayName("V169 — the partition+serviceId COUNT is answerable from "
            + "idx_bookings_salon_service_partition_starts_at ALONE, index-only: the partition "
            + "predicate is read out of the INCLUDE payload, never the heap. This is the measured "
            + "103 -> 5 buffer win. Strip the INCLUDE — or half of it — and this goes RED")
    void should_serveThePartitionAndServiceCountFromTheIncludePayload_when_onlyV169sIndexExists() {
        String plan = probe.explainWithOnly(V169_INDEX,
                BookingsIndexCapabilityProbe.countWhere(
                        BookingsIndexCapabilityProbe.salonScope(salonId)
                                + " AND " + BookingsIndexCapabilityProbe.serviceScope(masterServiceId)
                                + " AND " + BookingsIndexCapabilityProbe.historyPredicate()));

        assertThat(plan)
                .as("Index ONLY Scan, not a plain Index Scan. Postgres emits an index-only plan if "
                        + "and only if the index supplies every column the query references, so a "
                        + "plain Index Scan here means the INCLUDE (status, ends_at) clause stopped "
                        + "covering the partition predicate and every matched row is a heap visit "
                        + "again — which is what V169 exists to remove. Measured, an index rebuilt "
                        + "with INCLUDE (status) alone already fails this. Plan:%n%s", plan)
                .contains("Index Only Scan using " + V169_INDEX);
        assertThat(plan)
                .as("salon_id must be a SEEK BOUND. Without the salon scope leading the index the "
                        + "planner falls to V18's bare FK index (%s) and discards the salon to a "
                        + "post-scan filter. Plan:%n%s", V18_FK_INDEX, plan)
                .contains("Index Cond: (salon_id =");
    }

    @Test
    @DisplayName("V169 — the single service chip with NO partition is still answerable index-only, "
            + "which is the whole justification for dropping V166: V169's key list IS V166's, "
            + "identically, so nothing V166 served loses its index")
    void should_keepTheSingleServiceChipCoveredIndexOnly_when_v169ReplacedV166() {
        String plan = probe.explainWithOnly(V169_INDEX,
                BookingsIndexCapabilityProbe.countWhere(
                        BookingsIndexCapabilityProbe.salonScope(salonId)
                                + " AND " + BookingsIndexCapabilityProbe.serviceScope(masterServiceId)));

        assertThat(plan)
                .as("this is the shape V166 was created for and V169 must keep serving it. Unlike "
                        + "its partition sibling above, this case survives an index rebuilt without "
                        + "the INCLUDE payload — which is exactly why both are needed. Plan:%n%s",
                        plan)
                .contains("Index Only Scan using " + V169_INDEX);
    }

    @Test
    @DisplayName("CONTROL — the same shape with one predicate no column of the index carries "
            + "(price_at_booking) degrades to a plain Index Scan, so the two Index-Only assertions "
            + "above cannot pass vacuously")
    void should_fallBackToAPlainIndexScan_when_aPredicateIsCoveredByNoColumnOfTheIndex() {
        String plan = probe.explainWithOnly(V169_INDEX,
                BookingsIndexCapabilityProbe.countWhere(
                        BookingsIndexCapabilityProbe.salonScope(salonId)
                                + " AND " + BookingsIndexCapabilityProbe.serviceScope(masterServiceId)
                                + " AND b.price_at_booking > 0"));

        assertThat(plan)
                .as("price_at_booking is in no key or INCLUDE column of this index, so the plan "
                        + "MUST leave the index tuple and visit the heap. If it does not, EXPLAIN "
                        + "is not reporting what the assertions above believe it reports and they "
                        + "prove nothing. Plan:%n%s", plan)
                .contains("Index Scan using " + V169_INDEX)
                .doesNotContain("Index Only Scan");
    }
}
