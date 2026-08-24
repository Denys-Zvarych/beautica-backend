package com.beautica.booking.repository;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan guard for the favourites CATEGORY AXIS, salon arm: proves that V142's partial index
 * {@code idx_bookings_salon_client_starts_at} is actually <b>CHOSEN</b> for
 * {@link BookingRepository#findLastBookedCategoryBySalonIds}'s {@code LATERAL}, and that
 * choosing it is what turns the lookup into a top-1 seek.
 *
 * <p><b>The gap this closes.</b> {@code FavoriteCategoryIndexesMigrationTest} proves the index
 * EXISTS with the right shape, and {@code FavoriteMigrationIT} proves the derivation returns the
 * right category. Neither can see the plan — an index that exists but is never selected buys
 * nothing, and every behavioural test returns the identical row set either way. V142's whole
 * justification is a plan property (0 rows discarded and cost bounded by PAGE SIZE, versus 250
 * rows discarded per provider and cost scaling with the salon's booking volume), so only a plan
 * assertion can guard it. backend-perf measured it once; a measurement is not a regression test.
 *
 * <p><b>Why this is not the flaky small-dataset plan assertion the codebase elsewhere rejects</b>
 * (see {@code ClientBookingIdPageNoSentinelIT}'s javadoc). Two structural properties are being
 * asserted, neither of which is a cost-model preference:
 * <ol>
 *   <li><b>Partial-index USABILITY.</b> V142 is partial on
 *       {@code salon_id IS NOT NULL AND client_id IS NOT NULL}; Postgres decides whether the
 *       query predicate implies that with {@code predicate_implied_by}, a syntactic yes/no taken
 *       BEFORE any cost is computed. The probe carries exactly one index and runs with
 *       {@code enable_seqscan} off, so if the index is usable the planner must take it and if it
 *       is not, the planner has literally no alternative but a Seq Scan.</li>
 *   <li><b>Index Cond vs. Filter.</b> Whether {@code client_id} is resolved INSIDE the index
 *       descent or as a post-scan Filter is decided by the index's key list, not by statistics.
 *       That is the difference V142 exists to buy, and the legacy-index probe below shows the
 *       degradation directly rather than asserting it in a comment.</li>
 * </ol>
 *
 * <p>The SQL is read reflectively out of the {@code @Query} annotation, so rewriting the
 * repository rewrites what is probed here — this guard can never drift onto a stale hand-copied
 * string. Everything runs inside one manually-managed transaction that is always rolled back:
 * the {@code ON COMMIT DROP} probe tables and the {@code SET LOCAL} vanish with it, leaving the
 * pooled connection exactly as it was found. ASCII-only.
 */
@DisplayName("findLastBookedCategoryBySalonIds — V142's partial index must stay the chosen plan")
class LastBookedCategorySalonIndexMatchIT extends AbstractIntegrationTest {

    /** V142's index — the one the salon arm's LATERAL is shaped to hit. */
    private static final String V142_INDEX = "idx_bookings_salon_client_starts_at";

    /** The pre-V142 best candidate: (salon_id, starts_at DESC), no client_id key. */
    private static final String LEGACY_INDEX = "idx_bookings_salon_starts_at";

    private static final String PROBE_TABLE = "last_booked_cat_plan_probe";
    private static final String PROBE_INDEX = "last_booked_cat_plan_probe_idx";

    private static final String LEGACY_PROBE_TABLE = "last_booked_cat_legacy_probe";
    private static final String LEGACY_PROBE_INDEX = "last_booked_cat_legacy_probe_idx";

    // ══════════════════════════════════════════════════════════════════════════
    // 1 — the index is USABLE for the production predicate, and only for it
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Subject plus an unconditional negative control.
     *
     * <p>The control strips the {@code client_id} equality outright. No partial-index predicate
     * containing {@code client_id IS NOT NULL} can ever be implied by the ABSENCE of a
     * {@code client_id} term, in any Postgres version, at any dataset size — so the control is
     * non-matching by construction rather than by an argument about the implication prover, and
     * stays a valid negative however V142's predicate is later edited. Without it, "the plan used
     * an index" could be an artefact of the probe setup rather than a property of the predicate.
     *
     * <p>The subject is asserted FIRST and independently: a control-consistency check that ran
     * first could trip on a rewritten query and leave the subject's plan unexamined, which is the
     * failure mode {@code BookedDaysPartialIndexMatchIT} records having been mutation-shown.
     */
    @Test
    @DisplayName("the LATERAL's (salon_id, client_id) equality pair provably implies V142's "
            + "partial predicate — EXPLAIN reaches the index, while the same query with the "
            + "client_id term removed cannot and seq-scans even with enable_seqscan off")
    void should_matchThePartialIndex_when_bothEqualityTermsArePresent() {
        String lateral = productionSalonLateralSql();

        Plans plans = explainAgainstProbe(
                PROBE_TABLE, PROBE_INDEX, V142_INDEX,
                againstProbe(lateral, PROBE_TABLE),
                againstProbe(withoutClientPredicate(lateral), PROBE_TABLE));

        assertThat(plans.subject())
                .as("the salon arm's LATERAL must reach V142's partial index. A Seq Scan here "
                                + "means Postgres can no longer prove the query predicate implies "
                                + "`salon_id IS NOT NULL AND client_id IS NOT NULL` — most likely because "
                                + "a strict equality was replaced by something non-strict (IS NOT DISTINCT "
                                + "FROM, a COALESCE, an OR) — and the favourites salon page is walking each "
                                + "salon's whole timeline in production. Actual plan:%n%s",
                        plans.subject())
                .contains(PROBE_INDEX);

        assertThat(plans.control())
                .as("control — a query with NO client_id predicate at all cannot imply a partial "
                                + "index predicate that requires client_id IS NOT NULL, under any planner "
                                + "version or dataset. If this ever stops holding, the assertion above is "
                                + "no longer evidence of anything. Actual plan:%n%s",
                        plans.control())
                .doesNotContain(PROBE_INDEX);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // 2 — the index BUYS something: client_id in the Index Cond, not in a Filter
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * "The index is used" is only half the claim. V142's migration comment says the pre-V142
     * candidates forced {@code client_id} to a <b>post-scan Filter</b>, walking the salon's whole
     * timeline until this client's newest booking turned up — 250 rows discarded per provider per
     * page on the measured fixture, degrading LINEARLY with the salon's booking volume.
     *
     * <p>This test plans the SAME production predicate twice: once against a probe carrying only
     * V142's index, once against a probe carrying only the legacy {@code (salon_id, starts_at
     * DESC)} composite. The difference is structural — whether {@code client_id} can be resolved
     * inside the index descent is decided by the key list, not by statistics — so it holds at
     * Testcontainers scale. It fails the day someone "simplifies" V142's index down to the
     * legacy shape, or reorders its keys so {@code client_id} stops being the second one.
     */
    @Test
    @DisplayName("V142 resolves client_id INSIDE the index descent, where the legacy "
            + "(salon_id, starts_at DESC) composite could only apply it as a post-scan Filter")
    void should_resolveClientIdInIndexCond_when_v142IndexIsAvailable() {
        String probeSql = againstProbe(productionSalonLateralSql(), PROBE_TABLE);
        String legacySql = againstProbe(productionSalonLateralSql(), LEGACY_PROBE_TABLE);

        String v142Plan = explainAgainstProbe(
                PROBE_TABLE, PROBE_INDEX, V142_INDEX, probeSql, probeSql).subject();
        String legacyPlan = explainAgainstProbe(
                LEGACY_PROBE_TABLE, LEGACY_PROBE_INDEX, LEGACY_INDEX, legacySql, legacySql).subject();

        assertThat(filterLinesOf(legacyPlan))
                .as("control — the legacy composite has no client_id key, so the planner can only "
                                + "apply the term after reading index tuples. This is the degradation V142 "
                                + "exists to remove; if the legacy plan ever stops showing it, the "
                                + "assertion below stops distinguishing anything. Actual plan:%n%s",
                        legacyPlan)
                .anySatisfy(line -> assertThat(line).contains("client_id"));

        assertThat(filterLinesOf(v142Plan))
                .as("with V142's index the SAME predicate must resolve client_id as part of the "
                                + "Index Cond — no post-scan Filter on it at all. A client_id Filter here "
                                + "means the salon arm has silently regressed to the legacy access pattern "
                                + "and its cost is back to scaling with the salon's booking volume rather "
                                + "than with page size. Actual plan:%n%s",
                        v142Plan)
                .noneSatisfy(line -> assertThat(line).contains("client_id"));
        assertThat(v142Plan)
                .as("sanity — the V142 plan must actually be using the probe index; a Seq Scan "
                                + "would trivially satisfy the no-client_id-Filter assertion above by "
                                + "having no index scan at all. Actual plan:%n%s",
                        v142Plan)
                .contains(PROBE_INDEX);
    }

    // ══════════════════════════════════════════════════════════════════════════
    // helpers
    // ══════════════════════════════════════════════════════════════════════════

    private record Plans(String subject, String control) {}

    /**
     * The body of the {@code CROSS JOIN LATERAL (...)} in
     * {@link BookingRepository#findLastBookedCategoryBySalonIds}, read straight off the
     * {@code @Query} annotation so this guard cannot drift from the shipped text.
     *
     * <p>Only the LATERAL is probed, not the whole statement: the outer query joins
     * {@code salons}, {@code master_services} and {@code service_definitions}, none of which can
     * be retargeted onto a probe table, and none of which participate in the index decision under
     * test. The LATERAL body IS the per-provider seek V142 was added for.
     */
    private static String productionSalonLateralSql() {
        Query query;
        try {
            query = BookingRepository.class
                    .getMethod("findLastBookedCategoryBySalonIds", UUID.class, Collection.class)
                    .getAnnotation(Query.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError(
                    "findLastBookedCategoryBySalonIds's signature changed — update this guard", e);
        }
        assertThat(query)
                .as("findLastBookedCategoryBySalonIds must still be a native @Query — if it became "
                        + "a derived or Criteria query this guard silently stops guarding")
                .isNotNull();
        return lateralBodyOf(query.value());
    }

    /** Extracts the parenthesis-balanced body of the first {@code CROSS JOIN LATERAL (...)}. */
    private static String lateralBodyOf(String sql) {
        int marker = sql.toUpperCase(Locale.ROOT).indexOf("CROSS JOIN LATERAL");
        assertThat(marker)
                .as("the salon arm is no longer a CROSS JOIN LATERAL — its access pattern changed "
                        + "and this plan guard must be rewritten rather than left passing:%n%s", sql)
                .isGreaterThan(-1);
        int open = sql.indexOf('(', marker);
        assertThat(open).as("no opening parenthesis after CROSS JOIN LATERAL:%n%s", sql)
                .isGreaterThan(-1);
        int depth = 0;
        for (int i = open; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return sql.substring(open + 1, i).trim();
                }
            }
        }
        throw new AssertionError("unterminated CROSS JOIN LATERAL body in:\n" + sql);
    }

    /**
     * The unconditional negative control: the LATERAL with its {@code client_id} equality deleted.
     * Regex-based rather than an exact-string replace so it cannot silently become a no-op (and
     * therefore a copy of the subject) the moment the predicate's whitespace or alias changes; the
     * rewrite is asserted to have actually happened.
     */
    private static String withoutClientPredicate(String lateral) {
        String stripped = lateral.replaceAll("(?i)\\s*AND\\s+b\\.client_id\\s*=\\s*:clientId", "");
        assertThat(stripped)
                .as("the control rewrite matched nothing — it would be an exact copy of the "
                        + "subject and prove nothing. Subject SQL:%n%s", lateral)
                .isNotEqualTo(lateral);
        return stripped;
    }

    /**
     * Substitutes the bind parameters and the outer correlation with literals (EXPLAIN cannot
     * bind JDBC placeholders through {@code jdbcTemplate}) and retargets the query at a probe
     * table. The literal VALUES are irrelevant to partial-index implication and to the Index
     * Cond / Filter split — only the predicate's SHAPE decides both — but they must parse.
     */
    private static String againstProbe(String lateral, String probeTable) {
        String probed = lateral
                .replace(":clientId", "'" + UUID.randomUUID() + "'::uuid")
                .replace("b.salon_id = s.id", "b.salon_id = '" + UUID.randomUUID() + "'::uuid")
                .replace("FROM bookings b", "FROM " + probeTable + " b");
        assertThat(probed)
                .as("the probed SQL still references the real `bookings` table or an unbound "
                        + "parameter — the substitution above no longer matches the shipped query "
                        + "text:%n%s", probed)
                .doesNotContain("bookings b")
                .doesNotContain(":clientId")
                .doesNotContain("s.id");
        return probed;
    }

    /**
     * Plans two SQL variants against a throwaway probe table carrying exactly ONE index — a clone
     * of the named live index, rebuilt from {@code pg_indexes.indexdef} so the probe can never
     * drift from what actually shipped — with {@code enable_seqscan} off.
     */
    private Plans explainAgainstProbe(String probeTable, String probeIndex, String realIndex,
                                      String subjectSql, String controlSql) {
        String clonedIndex = cloneOfDeployedIndex(realIndex, probeTable, probeIndex);
        return jdbcTemplate.execute((ConnectionCallback<Plans>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // Structural-only probe: same column types as `bookings` and — crucially — no
                // indexes at all except the single clone below, so "which index" is unambiguous.
                statement.execute(
                        "CREATE TEMP TABLE " + probeTable + " (LIKE bookings) ON COMMIT DROP");
                statement.execute(clonedIndex);
                // With seqscan disabled the planner takes the index whenever it is USABLE; when
                // the predicate does not imply the index predicate it has no other option and
                // seq-scans anyway. A cost-model preference becomes a structural yes/no.
                statement.execute("SET LOCAL enable_seqscan = off");

                return new Plans(explain(statement, subjectSql), explain(statement, controlSql));
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    /** Rebuilds a deployed index on the probe table, verbatim from {@code pg_indexes}. */
    private String cloneOfDeployedIndex(String realIndex, String probeTable, String probeIndex) {
        List<String> defs = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE indexname = ?", String.class, realIndex);
        assertThat(defs)
                .as("%s must exist before it can be cloned onto the probe table — see "
                        + "FavoriteCategoryIndexesMigrationTest for the existence contract", realIndex)
                .hasSize(1);
        String rebound = defs.get(0)
                .replace(realIndex, probeIndex)
                .replace(" ON public.bookings ", " ON " + probeTable + " ")
                .replace(" ON bookings ", " ON " + probeTable + " ");
        assertThat(rebound)
                .as("the cloned index must target the probe table, not the real `bookings` — "
                        + "pg_indexes emitted an unexpected shape: %s", defs.get(0))
                .contains(probeTable)
                .doesNotContain("bookings ");
        return rebound;
    }

    /** The {@code Filter: ...} lines of an EXPLAIN plan, trimmed; empty when there are none. */
    private static List<String> filterLinesOf(String plan) {
        List<String> filters = new ArrayList<>();
        for (String line : plan.split("\n")) {
            if (line.trim().startsWith("Filter:")) {
                filters.add(line.trim());
            }
        }
        return filters;
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
