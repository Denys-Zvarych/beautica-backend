package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Content assertions for {@code V153__invite_tokens_revocation_and_history.sql} (Q21: "Flyway
 * applied with no error" proves nothing about what the migration actually produced).
 *
 * <p>Replaces {@code InviteTokenSalonPendingIndexMigrationTest}, which asserted the continued
 * existence of {@code idx_invite_tokens_salon_pending}. V153 DROPs that index, so those three
 * assertions could not be kept — they now assert the opposite, that it is gone, which is the
 * property that matters: V149's partial {@code (salon_id, expires_at) WHERE is_used = false} index
 * can never serve the unfiltered history query, so leaving it behind would be dead write-amplifying
 * weight on every invite dispatch.
 *
 * <p>V153 also swaps the {@code ux_invite_tokens_active} predicate. That is the concurrency
 * backstop for invite dispatch, and its predicate is what releases the active slot for a re-invite
 * — the revocation columns and the supersede write are useless without it, so it is asserted by
 * its live definition rather than trusted.
 */
@DisplayName("V153 migration — invite_tokens revocation columns, constraints and index swap")
class InviteTokenRevocationIndexMigrationTest extends AbstractIntegrationTest {

    private static final String HISTORY_INDEX = "idx_invite_tokens_salon_created";
    private static final String ACTIVE_INDEX = "ux_invite_tokens_active";
    private static final String RETIRED_INDEX = "idx_invite_tokens_salon_pending";
    private static final String EMAIL_ACTIVE_INDEX = "idx_invite_tokens_email_active";
    private static final String RETIRED_EMAIL_INDEX = "idx_invite_tokens_email_used";

    /** The SQL the history finder issues, shaped exactly as Hibernate renders the JPQL. */
    private static final String HISTORY_SQL =
            "SELECT * FROM invite_tokens WHERE salon_id = '00000000-0000-0000-0000-000000000001'::uuid "
                    + "ORDER BY created_at DESC, id DESC LIMIT 200";

    /**
     * The dispatch idempotency lookup, exactly as
     * {@code findByEmailAndSalonIdAndIsUsedFalseAndRevokedAtIsNull} renders it.
     */
    private static final String DISPATCH_SQL =
            "SELECT * FROM invite_tokens WHERE email = 'probe@beautica.test' "
                    + "AND salon_id = '00000000-0000-0000-0000-000000000001'::uuid "
                    + "AND is_used = false AND revoked_at IS NULL";

    /**
     * Control: the same lookup plus one predicate the index cannot cover, which MUST therefore
     * appear as a residual {@code Filter}. Without it, "no Filter line" could just mean EXPLAIN
     * never prints Filter lines for this table.
     */
    private static final String DISPATCH_RESIDUAL_SQL = DISPATCH_SQL + " AND role = 'SALON_ADMIN'";

    /** Control: same table, same shape, an ORDER BY this index cannot serve. */
    private static final String UNSERVEABLE_SQL =
            "SELECT * FROM invite_tokens WHERE salon_id = '00000000-0000-0000-0000-000000000001'::uuid "
                    + "ORDER BY expires_at DESC, id DESC LIMIT 200";

    // ── columns + constraints ─────────────────────────────────────────────────

    @Test
    @DisplayName("V153 — revoked_at and revoked_reason exist with the declared types and are nullable")
    void should_addRevocationColumns_when_v153Applied() {
        List<java.util.Map<String, Object>> columns = jdbcTemplate.queryForList(
                "SELECT column_name, data_type, is_nullable, character_maximum_length "
                        + "FROM information_schema.columns "
                        + "WHERE table_name = 'invite_tokens' "
                        + "AND column_name IN ('revoked_at', 'revoked_reason') "
                        + "ORDER BY column_name");

        assertThat(columns).hasSize(2);
        assertThat(columns.get(0))
                .as("revoked_at must be a nullable timestamptz — a live invite has no revocation instant")
                .containsEntry("column_name", "revoked_at")
                .containsEntry("data_type", "timestamp with time zone")
                .containsEntry("is_nullable", "YES");
        assertThat(columns.get(1))
                .as("revoked_reason is the persisted RevocationReason name; length 16 fits both "
                        + "constants with room to spare and is mirrored by the entity's @Column")
                .containsEntry("column_name", "revoked_reason")
                .containsEntry("data_type", "character varying")
                .containsEntry("is_nullable", "YES")
                .containsEntry("character_maximum_length", 16);
    }

    /**
     * The pair CHECK is what makes {@code revoked_at IS NULL} a trustworthy stand-in for "not
     * revoked" everywhere it is used — most importantly inside {@code ux_invite_tokens_active}'s
     * predicate and the dispatch finder. A half-written row would silently take an invite out of
     * the active slot while still reading as live in the history.
     */
    @Test
    @DisplayName("V153 — ck_invite_tokens_revoked_pair rejects a row with only one of the two columns set")
    void should_rejectHalfWrittenRevocation_when_onlyOneColumnIsSet() {
        assertThat(constraintNames())
                .as("the pair CHECK must exist by name")
                .contains("ck_invite_tokens_revoked_pair");

        assertThat(insertProbeRowFails("NOW()", "NULL"))
                .as("revoked_at without a reason must be rejected")
                .isTrue();
        assertThat(insertProbeRowFails("NULL", "'CANCELLED'"))
                .as("a reason without revoked_at must be rejected — this is the direction that would "
                        + "corrupt ux_invite_tokens_active, which keys off revoked_at alone")
                .isTrue();
        assertThat(insertProbeRowFails("NOW()", "'CANCELLED'"))
                .as("control — a coherently revoked row must be ACCEPTED, otherwise the two "
                        + "assertions above would pass for the wrong reason")
                .isFalse();
    }

    @Test
    @DisplayName("V153 — ck_invite_tokens_revoked_reason admits only CANCELLED and SUPERSEDED")
    void should_rejectUnknownRevocationReason_when_v153Applied() {
        assertThat(constraintNames()).contains("ck_invite_tokens_revoked_reason");

        assertThat(insertProbeRowFails("NOW()", "'DELETED'"))
                .as("a reason outside the RevocationReason enum must be rejected at the DB, so a "
                        + "new constant cannot ship without the migration that widens this CHECK")
                .isTrue();
        assertThat(insertProbeRowFails("NOW()", "'SUPERSEDED'"))
                .as("control — SUPERSEDED must be accepted")
                .isFalse();
    }

    // ── the index swap ────────────────────────────────────────────────────────

    @Test
    @DisplayName("V153 — idx_invite_tokens_salon_created exists on (salon_id, created_at DESC, id DESC) "
            + "and is NOT partial")
    void should_createHistoryIndex_when_v153Applied() {
        String indexDef = indexDefinitionOf(HISTORY_INDEX);

        assertThat(indexDef)
                .as("the history index must exist — the endpoint's ORDER BY has nothing else to ride")
                .isNotNull();
        assertThat(indexDef)
                .as("column order is load-bearing: salon_id must lead so the salon filter is an "
                        + "Index Cond, with created_at DESC then id DESC supplying the total order — "
                        + "def: %s", indexDef)
                .containsPattern("salon_id[^)]*created_at DESC[^)]*id DESC");
        assertThat(indexDef.toUpperCase(Locale.ROOT))
                .as("it must NOT be partial: the history query is unfiltered, so any WHERE clause "
                        + "here would exclude rows the endpoint is required to return — def: %s",
                        indexDef)
                .doesNotContain(" WHERE ");
    }

    @Test
    @DisplayName("V153 — idx_invite_tokens_salon_pending is GONE (V149's partial index cannot serve "
            + "the unfiltered history query and must not be left behind)")
    void should_dropTheRetiredPendingIndex_when_v153Applied() {
        assertThat(indexNamesOnInviteTokens())
                .as("a partial WHERE is_used = false index can never serve `salon_id = ? ORDER BY "
                        + "created_at DESC LIMIT 200`, so keeping it would only add write cost to "
                        + "every invite dispatch")
                .doesNotContain(RETIRED_INDEX);
    }

    @Test
    @DisplayName("V153 — ux_invite_tokens_active is re-scoped to `is_used = false AND revoked_at IS NULL` "
            + "and stays UNIQUE on (lower(email), salon_id)")
    void should_rescopeTheActiveUniqueIndex_when_v153Applied() {
        String indexDef = indexDefinitionOf(ACTIVE_INDEX);

        assertThat(indexDef)
                .as("the dispatch concurrency backstop must survive the swap")
                .isNotNull();
        assertThat(indexDef)
                .as("it must remain UNIQUE on lower(email) + salon_id — def: %s", indexDef)
                .contains("CREATE UNIQUE INDEX")
                .contains("lower(")
                .contains("salon_id");
        assertThat(indexDef)
                .as("revoked_at IS NULL is what RELEASES the slot for a re-invite: a SUPERSEDED row "
                        + "keeps is_used = false, so without this clause the kept row would block "
                        + "every replacement invite to the same address — def: %s", indexDef)
                .containsIgnoringCase("is_used = false")
                .containsIgnoringCase("revoked_at IS NULL");
    }

    /**
     * Structural plan probe, in the style of {@code BookedDaysPartialIndexMatchIT}: with
     * {@code enable_seqscan} off the planner takes the history index if — and only if — it is
     * USABLE for this predicate and ordering, so the verdict carries no dependence on row counts,
     * {@code ANALYZE}, or planner cost defaults. The control query differs ONLY in its sort key
     * and must NOT be servable, which is what proves the assertion is measuring the index's
     * ordering rather than the mere presence of an index on {@code salon_id}.
     *
     * <p>Everything runs in a transaction that is always rolled back, so the {@code SET LOCAL}
     * never escapes onto the pooled connection.
     */
    @Test
    @DisplayName("V153 — the history query plans as an ordered scan of idx_invite_tokens_salon_created "
            + "with no Sort node, while the same query sorted by expires_at needs one")
    void should_serveTheHistoryQueryFromTheIndex_when_v153Applied() {
        Plans plans = explainBoth();

        assertThat(plans.history())
                .as("the history read must ride the index — a Seq Scan here means the endpoint sorts "
                        + "the salon's whole invite table on every page load. Plan:%n%s", plans.history())
                .contains(HISTORY_INDEX);
        assertThat(plans.history())
                .as("and it must supply the ORDER BY directly: a Sort node means the index's column "
                        + "order no longer matches `created_at DESC, id DESC`. Plan:%n%s", plans.history())
                .doesNotContain("Sort");

        assertThat(plans.control())
                .as("CONTROL — ordering by expires_at cannot be served by an index keyed on "
                        + "created_at, so this plan MUST carry a Sort. If it does not, the assertion "
                        + "above is not measuring ordering at all. Plan:%n%s", plans.control())
                .contains("Sort");
    }

    /**
     * P2. V16's {@code idx_invite_tokens_email_used (email, is_used)} was adequate only while an
     * expired invite displaced by a re-invite was HARD-DELETED. V153 retains those rows as
     * SUPERSEDED history and a SUPERSEDED row keeps {@code is_used = false}, so under V16's shape
     * every past invite for a churned address was fetched and then discarded by
     * {@code revoked_at IS NULL} as a residual filter — measured at
     * {@code Rows Removed by Filter: 50} after 50 re-invites of one address, growing linearly.
     * The retention change is what created the cost, so the index fix ships in the same migration.
     */
    @Test
    @DisplayName("V153 — idx_invite_tokens_email_used is REPLACED by a partial "
            + "idx_invite_tokens_email_active on (email, salon_id)")
    void should_replaceTheEmailIndexWithAPartialActiveIndex_when_v153Applied() {
        assertThat(indexNamesOnInviteTokens())
                .as("V16's index must be dropped, not left alongside — two indexes serving one "
                        + "lookup is pure write amplification on every invite dispatch")
                .doesNotContain(RETIRED_EMAIL_INDEX);

        String indexDef = indexDefinitionOf(EMAIL_ACTIVE_INDEX);
        assertThat(indexDef)
                .as("the dispatch lookup needs a NON-expression index: Postgres cannot answer a "
                        + "bare `email = ?` from ux_invite_tokens_active's lower(email) key")
                .isNotNull();
        assertThat(indexDef)
                .as("salon_id joins email in the KEY so the equality half of the predicate is fully "
                        + "an Index Cond — def: %s", indexDef)
                .containsPattern("\\(email[^)]*salon_id\\)");
        assertThat(indexDef)
                .as("both booleans belong in the PREDICATE, not the key: a row leaves the live set "
                        + "permanently once accepted/cancelled/superseded, so the index stays a "
                        + "handful of rows instead of growing with every invite ever sent — def: %s",
                        indexDef)
                .containsIgnoringCase("WHERE")
                .containsIgnoringCase("is_used = false")
                .containsIgnoringCase("revoked_at IS NULL");
    }

    /**
     * The behavioural half of the assertion above: the whole dispatch predicate must resolve
     * inside the index, leaving NO residual {@code Filter}. That is precisely what V16's shape
     * could not do, and the residual is what accumulated with churn. The control query adds one
     * uncovered predicate and must show a Filter, so a green result here cannot come from EXPLAIN
     * simply never printing Filter lines.
     */
    @Test
    @DisplayName("V153 — the dispatch lookup resolves entirely as an Index Cond with no residual "
            + "filter, while the same query plus an uncovered predicate keeps one")
    void should_serveTheDispatchLookupWithNoResidualFilter_when_v153Applied() {
        Plans plans = explainPair(DISPATCH_SQL, DISPATCH_RESIDUAL_SQL);

        assertThat(plans.history())
                .as("the dispatch lookup must ride the partial index. Plan:%n%s", plans.history())
                .contains(EMAIL_ACTIVE_INDEX);
        assertThat(plans.history())
                .as("no residual Filter: every clause is either an Index Cond or implied by the "
                        + "index predicate. A Filter line here means superseded history rows are "
                        + "being fetched and thrown away, without bound. Plan:%n%s", plans.history())
                .doesNotContain("Filter:");

        assertThat(plans.control())
                .as("CONTROL — `role = ?` is in neither the key nor the predicate, so this plan "
                        + "MUST carry a Filter. If it does not, the assertion above proves nothing. "
                        + "Plan:%n%s", plans.control())
                .contains("Filter:");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private record Plans(String history, String control) { }

    private Plans explainBoth() {
        return explainPair(HISTORY_SQL, UNSERVEABLE_SQL);
    }

    /**
     * EXPLAINs both statements on ONE connection with {@code enable_seqscan} off, so the planner
     * takes an index if and only if it is USABLE — no dependence on row counts, {@code ANALYZE},
     * or cost defaults. Always rolled back, so the {@code SET LOCAL} never escapes onto the
     * pooled connection.
     */
    private Plans explainPair(String subject, String control) {
        return jdbcTemplate.execute((ConnectionCallback<Plans>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET LOCAL enable_seqscan = off");
                return new Plans(explain(statement, subject), explain(statement, control));
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    private static String explain(Statement statement, String sql) throws java.sql.SQLException {
        StringBuilder plan = new StringBuilder();
        try (ResultSet rs = statement.executeQuery("EXPLAIN " + sql)) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }

    private List<String> indexNamesOnInviteTokens() {
        return jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'invite_tokens'", String.class);
    }

    private String indexDefinitionOf(String indexName) {
        List<String> defs = jdbcTemplate.queryForList(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'invite_tokens' AND indexname = ?",
                String.class, indexName);
        return defs.isEmpty() ? null : defs.get(0);
    }

    private List<String> constraintNames() {
        return jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE conrelid = 'invite_tokens'::regclass",
                String.class);
    }

    /**
     * Attempts one INSERT carrying the given revocation column values and reports whether the DB
     * rejected it, always rolling back so nothing survives into another test.
     *
     * @param revokedAt SQL literal for {@code revoked_at} — {@code "NOW()"} or {@code "NULL"}
     * @param reason    SQL literal for {@code revoked_reason} — e.g. {@code "'CANCELLED'"} or
     *                  {@code "NULL"}
     */
    private boolean insertProbeRowFails(String revokedAt, String reason) {
        return jdbcTemplate.execute((ConnectionCallback<Boolean>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.execute(
                        "INSERT INTO invite_tokens (id, token, email, salon_id, role, expires_at, is_used, "
                                + "revoked_at, revoked_reason, created_at, updated_at) VALUES "
                                + "(gen_random_uuid(), 'probe-" + java.util.UUID.randomUUID() + "', "
                                + "'probe@beautica.test', NULL, 'SALON_MASTER', NOW() + interval '1 day', "
                                + "false, " + revokedAt + ", " + reason + ", NOW(), NOW())");
                return false;
            } catch (java.sql.SQLException e) {
                return true;
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        });
    }
}
