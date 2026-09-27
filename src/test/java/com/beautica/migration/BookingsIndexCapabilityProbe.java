package com.beautica.migration;

import com.beautica.support.IndexCapabilityProbe;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

/**
 * {@link IndexCapabilityProbe} pinned to {@code bookings}, plus the salon-scope query shapes
 * {@code BookingRepositoryCustomImpl} renders — so what the plan reports is the index's CAPABILITY,
 * never the cost model's opinion of it.
 *
 * <p><b>The mechanism, and why it is cost-independent by construction, lives on
 * {@link IndexCapabilityProbe}.</b> It was extracted there when the {@code salons} locality tests
 * needed the same shape (test-hygiene LOW-1, 2026-09-20); this subclass keeps the table binding and
 * the bookings-specific SQL, which is the only part that was ever bookings-specific. Nothing about the
 * guarantee changed: same {@code DROP}-every-competitor transaction, same {@code ROLLBACK}, same
 * {@code enable_seqscan}/{@code enable_bitmapscan} off, same {@code EXPLAIN (COSTS OFF)}.
 *
 * <p><b>Why the migration tests need a behavioural probe at all.</b> An index is INVISIBLE to every
 * functional test: no query returns a different row because of it, and {@code ddl-auto=validate}
 * does not check {@code @Table(indexes = ...)} against the real schema. The catalog assertions in
 * {@link V168SalonPartitionCoveringIndexMigrationTest} and {@link SalonServiceQueryPlanMigrationTest}
 * pin {@code pg_indexes.indexdef} TEXT, which catches a dropped or renamed index but cannot tell
 * whether the shape it pins still BUYS anything. That is the gap this probe closes (the missing
 * query-plan assertion finding, backend-perf 2026-09-20).
 *
 * <p><b>Why it does not pin WHICH index the planner picks.</b> That was the first attempt, and it
 * was a flake. It needed a 6 000-row seeded fixture (an empty table costs every candidate index
 * identically, so the choice is arbitrary), and MEASURED on PG 16.13 the choice then moved on
 * changes that mean nothing semantically:
 * <ul>
 *   <li>seed onto a fresh relfilenode ({@code TRUNCATE} first) &rarr; the salon-wide partition
 *       {@code COUNT} planned as {@code Index Only Scan using idx_bookings_salon_partition_starts_at},
 *       62 buffers;</li>
 *   <li>seed into the space a preceding test's {@code DELETE} left behind, reclaimed by a plain
 *       {@code VACUUM} or by {@code VACUUM (ANALYZE)} &rarr; the SAME 6 000 rows, the same
 *       {@code relpages=140 / relallvisible=140}, and the planner moved to {@code idx_bookings_salon_starts_at}
 *       + a Bitmap Heap Scan, {@code Heap Blocks: exact=94}, 131 buffers.</li>
 * </ul>
 * Nothing about the schema differed between those two runs — only where the rows physically landed,
 * which is decided by the test that happened to run BEFORE this one. An assertion with that
 * property is a red build waiting for an unlucky ordering, and a test that fails randomly trains
 * people to ignore CI. It is gone.
 *
 * <p><b>Measured invariance.</b> The probe returned a byte-identical plan for all 8 salon-scope
 * shapes in every state that moves the cost model: an empty never-{@code ANALYZE}d table (25
 * repetitions), an empty {@code VACUUM (ANALYZE)}d table (25 repetitions), and the 6 000-row fixture
 * seeded four different ways — including both perturbations above that flip the choice-based
 * assertion. It needs no fixture, no {@code VACUUM}, and no {@code TRUNCATE}, so it is also immune
 * to test ORDERING, which the fixture was not.
 *
 * <p><b>Measured falsification.</b> Rebuilding the index under test in a degraded shape moves the
 * assertion RED, so it is not a tautology: narrowing V168's salon index to V19's
 * {@code (salon_id, starts_at DESC)} turns the partition {@code COUNT}'s {@code Index Only Scan}
 * into a plain {@code Index Scan}; stripping V169's {@code INCLUDE (status, ends_at)} — or half of
 * it — does the same to the partition+service {@code COUNT}; narrowing V168's salon+master index to
 * V148's shape does the same to the master chip; and hoisting {@code status}/{@code ends_at} ahead
 * of {@code starts_at} re-introduces a blocking {@code Sort} on the ordered page query. Dropping the
 * index outright degrades every shape to a {@code Seq Scan}.
 *
 * <p><b>What is deliberately NOT asserted, and why.</b>
 * <ul>
 *   <li><b>Absolute buffer counts</b> ({@code 1694 -> 495}, {@code 1420 -> 35}, {@code 103 -> 5}).
 *       Fixture- and version-dependent. The plan property they were a proxy for is asserted instead.</li>
 *   <li><b>{@code Heap Fetches: 0}.</b> That is a VISIBILITY MAP reading, not an index property — it
 *       reports how recently the relation was vacuumed, and it was the source of the original
 *       fixture's {@code VACUUM}-until-converged loop. {@code Index Only Scan} already proves the
 *       predicate is answerable from the index tuple, which is the thing the migration bought.</li>
 *   <li><b>Which index the planner PREFERS among several that all cover the query.</b> V168 kept V19
 *       because it measured cheaper for the board's default request; that is a genuine cost claim
 *       and there is no cost-independent way to assert it. {@code should_retainV19PrefixIndex}
 *       pins V19's existence and shape, and the probe pins that V19 still COVERS that request — a
 *       real regression in either is caught; a change in the planner's preference between two
 *       covering indexes is not, and is accepted as untestable.</li>
 *   <li><b>{@code master_service_id} as an {@code Index Cond} seek bound.</b> Whether a
 *       {@code = ANY(...)} array qual becomes a scan key or a residual filter over index tuples IS
 *       costed, and it moves with row counts. Either way the scan stays index-only, which is the
 *       property under test.</li>
 * </ul>
 *
 * <p>Read-only: it creates nothing, leaves no rows, and never commits. ASCII-only.
 */
class BookingsIndexCapabilityProbe extends IndexCapabilityProbe {

    BookingsIndexCapabilityProbe(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate, "bookings");
    }

    // -- query shapes, as BookingRepositoryCustomImpl renders them ----------------------------

    /**
     * {@code BookingSpecifications#partition}'s {@code HISTORY} arm rendered as SQL — the archive's
     * DEFAULT partition, and the one no pre-V168 index could serve because it is a NEGATION over
     * {@code status} plus a comparison of {@code ends_at} against a RUNTIME instant.
     */
    static String historyPredicate() {
        return "NOT (b.status IN ('CONFIRMED') AND b.ends_at >= now())";
    }

    /** The {@code COUNT} companion {@code BookingRepositoryCustomImpl#countMatching} issues. */
    static String countWhere(String where) {
        return "SELECT count(1) FROM bookings b WHERE " + where;
    }

    /**
     * The id page {@code BookingRepositoryCustomImpl#findIdsBySalonIdFilteredByPartition} issues —
     * the half that needs {@code starts_at} to remain a usable SORT key, which the {@code COUNT}
     * shapes cannot see.
     */
    static String orderedPageWhere(String where) {
        return "SELECT b.id FROM bookings b WHERE " + where + " ORDER BY b.starts_at DESC LIMIT 20";
    }

    static String salonScope(UUID salonId) {
        return "b.salon_id = '" + salonId + "'::uuid";
    }

    static String masterScope(UUID masterId) {
        return "b.master_id = '" + masterId + "'::uuid";
    }

    /** One service chip, as Hibernate renders {@code master_service_id IN :ids} for a bound list. */
    static String serviceScope(UUID masterServiceId) {
        return "b.master_service_id = ANY (ARRAY['" + masterServiceId + "'::uuid])";
    }
}
