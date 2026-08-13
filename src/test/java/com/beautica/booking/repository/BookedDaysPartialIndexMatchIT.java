package com.beautica.booking.repository;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release-gate guard for the 2026-08-13 master-rail status filter (backend-qa): proves that
 * {@link com.beautica.booking.repository.BookingRepository#findBookedDatesByMasterId}'s status
 * predicate is still a shape Postgres can prove IMPLIES the partial index
 * {@code idx_bookings_master_past_partition_starts_at} (V130), and is therefore still index-served.
 *
 * <p><b>The gap this closes.</b> The predicate was deliberately written as the positive allow-list
 * {@code status IN ('CONFIRMED', 'COMPLETED', 'NOT_COMPLETED')} rather than the logically-identical
 * deny-list {@code status NOT IN ('CANCELLED', 'DECLINED')}, purely because Postgres's partial-index
 * implication prover ({@code predicate_implied_by}) is SYNTACTIC: it can prove an {@code IN}-list
 * implies a superset {@code IN}-list, but it cannot prove a {@code NOT IN} over the complementary
 * values implies anything — that would require knowing the column's closed domain, which the prover
 * never consults. backend-perf measured the consequence: the deny-list form degenerates to a Seq
 * Scan (2 179 buffers, 90 525 rows filtered, ~11.7 ms) on the 366-day windows that reach a master's
 * sparse forward tail, with the real client window only 2.2 % from that planner cliff.
 *
 * <p>Nothing in {@code BookingMyBookedDaysIT}'s 13 behavioural tests can see that. Both predicate
 * forms return the IDENTICAL row set, so a future "simplification" to {@code NOT IN} keeps every
 * one of them green while silently reintroducing the Seq Scan in production. The invariant is a
 * PLAN property, and only a plan assertion can guard it. A comment cannot.
 *
 * <p><b>Why this is NOT the flaky small-dataset plan assertion {@code
 * ClientBookingIdPageNoSentinelIT}'s javadoc rejects.</b> That test refused to assert a plan
 * because the property it wanted (index scan vs. seq scan for a sentinel-free predicate) is a
 * COST-MODEL choice — at Testcontainers scale the planner has no reason to prefer either shape, so
 * the assertion would measure statistics noise. The property here is categorically different:
 * partial-index USABILITY is a structural yes/no decided by {@code predicate_implied_by} BEFORE any
 * cost is computed. This test isolates exactly that decision and nothing else:
 *
 * <ol>
 *   <li>It builds a throwaway probe table with the SAME column types as {@code bookings} and
 *       exactly ONE index — a clone of the real V130 index, recreated from the live
 *       {@code pg_indexes.indexdef} so the probe can never drift from what actually shipped.</li>
 *   <li>It disables {@code enable_seqscan}, so if the index is usable at all the planner must
 *       take it, and if it is NOT usable the planner has literally no alternative but a Seq Scan.
 *       The verdict is therefore binary and carries zero dependence on row counts, table
 *       statistics, {@code ANALYZE}, Postgres minor version or planner GUC defaults.</li>
 *   <li>It EXPLAINs the PRODUCTION SQL read reflectively out of the {@code @Query} annotation —
 *       never a hand-copied string — so rewriting the repository predicate rewrites what this test
 *       probes.</li>
 * </ol>
 *
 * <p><b>Discriminating control.</b> The same test EXPLAINs the deny-list variant under identical
 * settings and asserts it does NOT reach the index. Without that half, "the plan used an index"
 * could be an artefact of the probe setup rather than a property of the predicate; with it, the
 * test demonstrates it is measuring the implication itself.
 *
 * <p>Everything runs inside a single rolled-back transaction on one pooled connection, so the
 * {@code enable_seqscan = off} session setting and the probe table both vanish before the
 * connection returns to the pool.
 */
@DisplayName("findBookedDatesByMasterId — the status allow-list must stay index-matchable (V130)")
class BookedDaysPartialIndexMatchIT extends AbstractIntegrationTest {

    /** The V130 partial index whose predicate the repository allow-list is shaped to match. */
    private static final String REAL_INDEX = "idx_bookings_master_past_partition_starts_at";

    private static final String PROBE_TABLE = "booked_days_plan_probe";
    private static final String PROBE_INDEX = "booked_days_plan_probe_idx";

    /** Extracts the quoted members of an {@code IN (...)} / {@code = ANY (ARRAY[...])} list. */
    private static final Pattern QUOTED_LITERAL = Pattern.compile("'([A-Z_]+)'");

    /** The query's status predicate in EITHER direction — matches the allow-list and the deny-list. */
    private static final Pattern STATUS_PREDICATE = Pattern.compile("b\\.status (?:NOT )?IN \\([^)]*\\)");

    // ══════════════════════════════════════════════════════════════════════════
    // 1 — the plan probe (the actual guard)
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("the production allow-list predicate is provably index-matchable — EXPLAIN reaches "
            + "the V130 partial index, while the logically-identical NOT IN ('CANCELLED','DECLINED') "
            + "deny-list cannot and falls back to a Seq Scan even with enable_seqscan off. FAILS the "
            + "moment the repository predicate is rewritten into any form Postgres cannot prove "
            + "implies the index predicate")
    void should_matchThePartialIndex_when_theStatusPredicateIsTheAllowList() {
        String productionSql = productionBookedDaysSql();

        Plans plans = explainAgainstProbe(
                productionSql,
                asDenyListVariant(productionSql),
                withoutStatusPredicate(productionSql));

        // ASSERTED FIRST, deliberately. Every earlier draft of this test guarded the controls before
        // the subject and was mutation-shown to be worthless for it: rewriting the repository to
        // NOT IN made the deny-list control identical to production, so a control-consistency
        // assertion tripped and the PLAN was never examined. The subject's plan is the finding; the
        // controls only qualify it, so the subject goes first and nothing can mask it.
        assertThat(plans.production())
                .as("the production status predicate must reach the V130 partial index. Actual plan:"
                                + "%n%s%nA Seq Scan here means Postgres can no longer prove this predicate "
                                + "implies the index predicate, so the rail query is scanning every booking "
                                + "in the window in production (measured: 2179 buffers / 90525 rows "
                                + "filtered / ~11.7ms on a 366-day window).",
                        plans.production())
                .contains(PROBE_INDEX);

        assertThat(plans.denyList())
                .as("control — NOT IN ('CANCELLED','DECLINED') must NOT reach the partial index: "
                                + "predicate_implied_by is syntactic and never consults the column's closed "
                                + "domain, which is the WHOLE reason the allow-list form was chosen. If this "
                                + "ever stops holding, the assertion above is no longer evidence of "
                                + "anything. Actual plan:%n%s",
                        plans.denyList())
                .doesNotContain(PROBE_INDEX);

        assertThat(plans.noStatusPredicate())
                .as("control — a query with NO status predicate at all cannot imply a partial index "
                                + "predicate under any planner version or dataset, so this is the probe's "
                                + "unconditional negative: it stays red-by-construction even if the deny-list "
                                + "control above ever degenerates into a copy of production. Actual plan:%n%s",
                        plans.noStatusPredicate())
                .doesNotContain(PROBE_INDEX);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2 — drift guard in the OTHER direction: the migration side
    // ══════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("the repository allow-list and the V130 index predicate name the SAME status set — "
            + "a future migration narrowing the index predicate (or a product decision widening the "
            + "rail) breaks the match and must be caught here, not in production latency")
    void should_nameTheSameStatusSet_when_comparingQueryPredicateToIndexPredicate() {
        String indexDef = deployedIndexDefinition();

        assertThat(indexDef)
                .as("V130's partial index must exist — the allow-list's entire shape is chosen to "
                        + "match it, so its absence makes that choice meaningless")
                .isNotNull();

        Set<String> indexStatuses = quotedUppercaseLiteralsIn(predicateOf(indexDef));
        Set<String> queryStatuses = quotedUppercaseLiteralsIn(statusInListOf(productionBookedDaysSql()));

        assertThat(queryStatuses)
                .as("query allow-list %s vs V130 index predicate %s — these must be the same set. "
                                + "A query set that is a strict SUBSET would still match the index but would "
                                + "silently change what the rail dots; a set with any member the index "
                                + "predicate lacks stops matching entirely and reintroduces the Seq Scan.",
                        queryStatuses, indexStatuses)
                .isEqualTo(indexStatuses);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════════════

    private record Plans(String production, String denyList, String noStatusPredicate) {}

    /**
     * Builds the deny-list control by rewriting WHATEVER status predicate the production query
     * currently carries — {@code IN} or {@code NOT IN}, any member list — into the fixed
     * {@code NOT IN ('CANCELLED', 'DECLINED')} form.
     *
     * <p>Deliberately regex-based rather than an exact-string {@code replace} of today's predicate:
     * an exact replace silently becomes a no-op the moment the predicate is edited, leaving the
     * control indistinguishable from the subject.
     */
    private static String asDenyListVariant(String sql) {
        return STATUS_PREDICATE.matcher(sql)
                .replaceAll(Matcher.quoteReplacement("b.status NOT IN ('CANCELLED', 'DECLINED')"));
    }

    /**
     * The unconditional negative control: the same query with its status predicate deleted outright.
     * No partial index predicate can ever be implied by the absence of a predicate, in any Postgres
     * version, at any dataset size — so this control is non-matching by construction rather than by
     * an argument about {@code predicate_implied_by}, and stays a valid negative even if the
     * deny-list control above ever collapses into a copy of the subject.
     */
    private static String withoutStatusPredicate(String sql) {
        return Pattern.compile("\\s*AND b\\.status (?:NOT )?IN \\([^)]*\\)").matcher(sql).replaceAll("");
    }

    /**
     * Reads the native SQL straight off {@code findBookedDatesByMasterId}'s {@code @Query}
     * annotation so this test can never drift from the shipped query text: rewriting the predicate
     * in the repository rewrites what is EXPLAINed here.
     */
    private static String productionBookedDaysSql() {
        try {
            Query query = BookingRepository.class
                    .getMethod("findBookedDatesByMasterId",
                            UUID.class, java.time.OffsetDateTime.class, java.time.OffsetDateTime.class)
                    .getAnnotation(Query.class);
            assertThat(query)
                    .as("findBookedDatesByMasterId must still be a native @Query — if it became a "
                            + "derived or Criteria query this guard silently stops guarding")
                    .isNotNull();
            return query.value();
        } catch (NoSuchMethodException e) {
            throw new AssertionError("findBookedDatesByMasterId's signature changed — update this guard", e);
        }
    }

    private String deployedIndexDefinition() {
        List<String> defs = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, REAL_INDEX);
        return defs.isEmpty() ? null : defs.get(0);
    }

    /** The {@code WHERE ...} tail of a {@code CREATE INDEX} definition. */
    private static String predicateOf(String indexDef) {
        int where = indexDef.toUpperCase(Locale.ROOT).lastIndexOf(" WHERE ");
        assertThat(where)
                .as("index definition <%s> has no WHERE clause — it is no longer a PARTIAL index, so "
                        + "the query's allow-list shape is no longer load-bearing", indexDef)
                .isGreaterThan(-1);
        return indexDef.substring(where);
    }

    /** The {@code b.status IN (...)} fragment of the production query. */
    private static String statusInListOf(String sql) {
        Matcher matcher = Pattern.compile("b\\.status IN \\(([^)]*)\\)").matcher(sql);
        assertThat(matcher.find())
                .as("could not locate a `b.status IN (...)` list in the production SQL:%n%s", sql)
                .isTrue();
        return matcher.group(1);
    }

    private static Set<String> quotedUppercaseLiteralsIn(String fragment) {
        Set<String> literals = new TreeSet<>();
        Matcher matcher = QUOTED_LITERAL.matcher(fragment);
        while (matcher.find()) {
            literals.add(matcher.group(1));
        }
        return literals;
    }

    /**
     * Plans both predicate forms against a probe table carrying exactly one index — a clone of the
     * live V130 index — with {@code enable_seqscan} off, so index usage is decided purely by
     * partial-index implication and not by any cost estimate.
     *
     * <p>Runs entirely inside one manually-managed transaction that is always rolled back: the
     * {@code ON COMMIT DROP} temp table and the {@code SET LOCAL} both disappear with it, leaving
     * the pooled connection exactly as it was found.
     */
    private Plans explainAgainstProbe(String productionSql, String denyListSql, String noStatusSql) {
        return jdbcTemplate.execute((ConnectionCallback<Plans>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // Structural-only probe: same column types as `bookings`, and — crucially — no
                // indexes at all except the one clone below, so "which index" cannot be ambiguous.
                statement.execute(
                        "CREATE TEMP TABLE " + PROBE_TABLE + " (LIKE bookings) ON COMMIT DROP");
                statement.execute(cloneOfDeployedIndex());
                // With seqscan disabled the planner takes the index whenever it is USABLE; when the
                // predicate does not imply the index predicate it has no other option and seq-scans
                // anyway. That turns a cost-model preference into a structural yes/no.
                statement.execute("SET LOCAL enable_seqscan = off");

                return new Plans(
                        explain(statement, againstProbe(productionSql)),
                        explain(statement, againstProbe(denyListSql)),
                        explain(statement, againstProbe(noStatusSql)));
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    /**
     * Rebuilds the deployed V130 index on the probe table, verbatim from {@code pg_indexes} — so if
     * a later migration changes the real index's predicate, this probe changes with it instead of
     * quietly testing a stale hand-written copy.
     */
    private String cloneOfDeployedIndex() {
        String indexDef = deployedIndexDefinition();
        assertThat(indexDef)
                .as("V130's %s must exist before it can be cloned onto the probe table", REAL_INDEX)
                .isNotNull();
        String rebound = indexDef
                .replace(REAL_INDEX, PROBE_INDEX)
                .replace(" ON public.bookings ", " ON " + PROBE_TABLE + " ")
                .replace(" ON bookings ", " ON " + PROBE_TABLE + " ");
        assertThat(rebound)
                .as("the cloned index must target the probe table, not the real `bookings` — "
                        + "pg_indexes emitted an unexpected shape: %s", indexDef)
                .contains(PROBE_TABLE)
                .doesNotContain("bookings ");
        return rebound;
    }

    /**
     * Substitutes the three named bind parameters with literals (EXPLAIN cannot bind JDBC
     * placeholders through {@code jdbcTemplate}) and retargets the query at the probe table. The
     * literal values are irrelevant to partial-index implication — only the STATUS predicate's
     * shape decides it — but they must parse.
     */
    private static String againstProbe(String sql) {
        return sql
                .replace(":masterId", "'" + UUID.randomUUID() + "'::uuid")
                .replace(":toExclusive", "timestamptz '2032-12-31 00:00:00+02'")
                .replace(":from", "timestamptz '2032-01-01 00:00:00+02'")
                .replace("FROM bookings b", "FROM " + PROBE_TABLE + " b");
    }

    private static String explain(Statement statement, String sql) throws java.sql.SQLException {
        List<String> lines = new ArrayList<>();
        try (ResultSet rs = statement.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) {
                lines.add(rs.getString(1));
            }
        }
        return String.join("\n", lines);
    }
}
