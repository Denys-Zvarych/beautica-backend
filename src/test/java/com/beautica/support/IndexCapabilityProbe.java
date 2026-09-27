package com.beautica.support;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@code EXPLAIN}s a query against ONE nominated index on ONE table, with every competing index on
 * that table removed — so what the plan reports is the index's CAPABILITY, never the cost model's
 * opinion of it.
 *
 * <p><b>Why an index needs a behavioural probe at all.</b> An index is INVISIBLE to every functional
 * test: no query returns a different row because of it, and {@code ddl-auto=validate} does not check
 * {@code @Table(indexes = ...)} against the real schema. A catalog assertion on
 * {@code pg_indexes.indexdef} catches a dropped or renamed index but cannot tell whether the shape it
 * pins still BUYS anything. That is the gap this probe closes.
 *
 * <p><b>Why it does not pin WHICH index the planner picks on a natural plan.</b> That was the first
 * attempt everywhere it was tried, and it was a flake. It needs a large seeded fixture (an empty table
 * costs every candidate index identically, so the choice is arbitrary), and MEASURED on PG 16.13 the
 * choice then moved on changes that mean nothing semantically — most sharply, seeding onto a fresh
 * relfilenode versus seeding into the space a preceding test's {@code DELETE} left behind and
 * reclaimed with a plain {@code VACUUM} flipped the winner for the SAME rows and the SAME
 * {@code relpages}. Nothing about the schema differed between those runs — only where the rows
 * physically landed, which is decided by the test that happened to run BEFORE. An assertion with that
 * property is a red build waiting for an unlucky ordering.
 *
 * <p><b>What replaced it, and why that is cost-independent by construction.</b>
 * {@link #explainWithOnly} drops every non-constraint index on the probed table except the one under
 * test, inside a transaction that is always ROLLED BACK (Postgres DDL is transactional, so the catalog
 * is untouched afterwards), and disables {@code seqscan} and {@code bitmapscan}. The planner is then
 * left with essentially two candidate plans for that one index — {@code Index Scan} and
 * {@code Index Only Scan} — and Postgres does not COST that choice: {@code build_index_paths} calls
 * {@code check_index_only} once and sets the flag on the single {@code IndexPath} it builds, so an
 * {@code Index Only Scan} appears if and only if the index supplies every column the query references.
 * Likewise, whether a qual becomes an {@code Index Cond} scan key or is demoted to a residual
 * {@code Filter} is decided structurally by {@code match_clause_to_indexcol} — the column and operator
 * family either match the index or they do not. "Covered" and "SARGable" are structural facts about
 * the index, and those are what tests built on this probe assert.
 *
 * <p>The extraction was driven by the {@code salons} locality tests needing exactly this shape
 * (backend-QA 2026-09-20, test-hygiene LOW-1): they had been pinning a chosen index name on a natural
 * cost-based plan over a ~200-salon seed — the same defect class this probe was created to remove from
 * the {@code bookings} migration tests. The mechanism is table-agnostic; only the query shapes are not,
 * so those stay with the per-table subclass or caller.
 *
 * <p>Read-only: it creates nothing, leaves no rows, and never commits. ASCII-only.
 */
public class IndexCapabilityProbe {

    /**
     * The probed table name is interpolated into SQL (Postgres cannot bind an identifier), so it is
     * constrained to a bare unquoted identifier. Every caller passes a compile-time constant, and
     * this keeps that a checked property rather than a convention.
     */
    private static final Pattern BARE_IDENTIFIER = Pattern.compile("[a-z_][a-z0-9_]*");

    private final JdbcTemplate jdbcTemplate;
    private final String table;

    public IndexCapabilityProbe(JdbcTemplate jdbcTemplate, String table) {
        if (table == null || !BARE_IDENTIFIER.matcher(table).matches()) {
            throw new IllegalArgumentException("not a bare lower-case identifier: " + table);
        }
        this.jdbcTemplate = jdbcTemplate;
        this.table = table;
    }

    /**
     * {@code EXPLAIN}s {@code sql} with {@code indexName} as the ONLY droppable index available on the
     * probed table, then rolls everything back.
     *
     * <p>{@code enable_seqscan} and {@code enable_bitmapscan} are turned off so the remaining
     * candidates are {@code Index Scan} and {@code Index Only Scan} on {@code indexName} — a choice
     * Postgres makes structurally rather than by cost (see the class javadoc). Constraint-backed
     * indexes (a table's {@code _pkey}, unique constraints, GiST exclusions) cannot be dropped without
     * dropping their constraints and are left in place; a test asserting an {@code Index Cond} on a
     * non-key column is unaffected by them, because a primary key on {@code id} cannot supply that
     * scan key however the planner costs it.
     *
     * <p>Everything runs on one connection inside an explicit transaction that ends in
     * {@code ROLLBACK}, so neither the dropped indexes nor the {@code SET LOCAL}s escape onto the
     * pooled connection — the shape {@code InviteTokenRevocationIndexMigrationTest} established.
     *
     * @throws IllegalStateException if {@code indexName} does not exist. A missing index is a real
     *     failure, but letting it fall through would report as "the planner chose some other scan",
     *     which reads like a cost problem; naming it here localises it immediately.
     */
    public String explainWithOnly(String indexName, String sql) {
        List<String> droppable = droppableIndexes();
        if (!droppable.contains(indexName)) {
            throw new IllegalStateException("cannot probe '" + indexName + "': no such droppable "
                    + "index on " + table + ". Present: " + droppable);
        }
        return jdbcTemplate.execute((ConnectionCallback<String>) connection -> {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                // DROP INDEX takes ACCESS EXCLUSIVE on the table. Nothing else in a test JVM
                // should hold a conflicting lock, but a background job in the live application
                // context could: fail fast with Postgres' own lock-timeout error rather than
                // hanging the build until the suite timeout. (The seeded fixtures this replaced
                // took the same lock via TRUNCATE, with no bound at all.)
                statement.execute("SET LOCAL lock_timeout = '5s'");
                statement.execute("SET LOCAL enable_seqscan = off");
                statement.execute("SET LOCAL enable_bitmapscan = off");
                for (String index : droppable) {
                    if (!index.equals(indexName)) {
                        statement.execute("DROP INDEX " + index);
                    }
                }
                return explain(statement, sql);
            } finally {
                connection.rollback();
                connection.setAutoCommit(autoCommit);
            }
        });
    }

    /**
     * Every index on the probed table that is NOT the implementation of a constraint, and can
     * therefore be dropped directly. Resolved fresh on each call rather than cached, so an index a
     * future migration adds is included automatically instead of silently competing with the one
     * under test.
     */
    private List<String> droppableIndexes() {
        return jdbcTemplate.queryForList("""
                SELECT c.relname FROM pg_class c
                JOIN pg_index x ON x.indexrelid = c.oid
                WHERE x.indrelid = '%s'::regclass
                  AND NOT EXISTS (SELECT 1 FROM pg_constraint k WHERE k.conindid = c.oid)
                ORDER BY c.relname
                """.formatted(table), String.class);
    }

    private static String explain(Statement statement, String sql) throws SQLException {
        StringBuilder plan = new StringBuilder();
        // COSTS OFF: the numbers are exactly what these assertions must not depend on, and leaving
        // them out keeps a failure message readable.
        try (ResultSet rs = statement.executeQuery("EXPLAIN (COSTS OFF) " + sql)) {
            while (rs.next()) {
                plan.append(rs.getString(1)).append('\n');
            }
        }
        return plan.toString();
    }
}
