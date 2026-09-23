package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.Statement;

/**
 * V176 — reclaim what V175's full-table UPDATE left behind on {@code cities} (Phase 327).
 *
 * <p><b>The defect this closes.</b> {@code V175__backfill_settlement_hromadas} rewrites all 25 697
 * rows of {@code cities} and then rebuilds {@code idx_cities_name_uk_trgm} to pay for the bloat it
 * caused. It does that rebuild INSIDE its own transaction, because the backfill is deliberately
 * fail-closed and a mid-write abort must roll the whole thing back. The consequence is that the
 * 26 041 dead tuples the UPDATE just created are not removable yet — the transaction that made them
 * is still open — so the REINDEX rebuilds OVER them and inherits the bloat it was supposed to
 * remove. Measured on {@code postgres:16-alpine} through the real Flyway chain:
 *
 * <table>
 *   <caption>V175's rebuild, with and without a preceding VACUUM</caption>
 *   <tr><th></th><th>REINDEX with dead tuples</th><th>VACUUM then REINDEX</th></tr>
 *   <tr><td>{@code idx_cities_name_uk_trgm}</td><td>1 976 kB</td><td>1 176 kB</td></tr>
 *   <tr><td>{@code cities} heap</td><td>7 416 kB</td><td>6 816 kB</td></tr>
 * </table>
 *
 * <p><b>The heap row is smaller than it looks, and {@code VACUUM FULL} is NOT the fix.</b> Plain
 * {@code VACUUM} truncates only the empty pages that happen to land at the END of the file and
 * returns the rest as free space this table will reuse on its next INSERT. So the heap falls
 * 7 593 984 B to 6 979 584 B (7 416 kB to 6 816 kB) and stops: the space is reclaimed, not
 * returned to the filesystem. Only {@code VACUUM FULL} rewrites the file, and it does reach
 * 3 801 088 B (3 712 kB) — measured, and the figure an earlier revision of this javadoc wrongly
 * attributed to the plain {@code VACUUM} above. It is not worth taking ACCESS EXCLUSIVE on
 * {@code cities} mid-deploy to hand 3 MB back to the filesystem. The index row is what this
 * migration is for; the heap row is bookkeeping.
 *
 * <p>Ordinary autocomplete traffic pays for that until autovacuum fires: «нов», a three-character
 * keystroke, read 746 buffers / 3.73 ms against 378 / 2.08 ms on a clean table (+97 % blocks),
 * and «іванівка» 971 / 15.57 ms against 504 / 12.14 ms. On production the window is short — 26 041
 * dead tuples is far past this table's autovacuum threshold, so the daemon fires within a naptime —
 * but it is the first minute after a deploy, on a {@code permitAll} endpoint, and EVERY
 * Testcontainers replay pays it in full because those databases live minutes and never get an
 * autovacuum cycle.
 *
 * <p><b>Why this cannot live in V175.</b> {@code VACUUM} cannot run inside a transaction block.
 * V175 must stay transactional: its row-count check is the guard that makes it structurally
 * incapable of INSERTing a settlement the occupied-territory exclusion set removed, and that guard
 * is only meaningful if the abort rolls the write back. So the cleanup ships as its own migration
 * with {@link #canExecuteInTransaction()} returning {@code false}, which is what lets it
 * {@code VACUUM} at all. This is the first non-transactional migration in the chain, and it needs
 * no {@code spring.flyway.mixed}: Flyway only rejects a mix when it GROUPS migrations into one
 * transaction, and {@code group} is false by default and set nowhere here, so each migration
 * already gets its own transaction and only the one that opts out runs without one. Verified by
 * booting the whole chain with the setting absent — V176 logs {@code [non-transactional]} and
 * Flyway reports 176/176 applied.
 *
 * <p><b>V175 keeps its own REINDEX, and that is not redundancy.</b> This migration can be skipped,
 * reordered, or fail on a database where V175 succeeded. V175 must leave a USABLE index on its own
 * — the settlement autocomplete may not depend on a later migration for that — and this one makes
 * it a compact one. Deleting either leaves a different hole.
 *
 * <p><b>Statement order.</b> {@code VACUUM (ANALYZE)} first, so the dead tuples are gone before the
 * index is rebuilt over the space they occupied; {@code REINDEX} second, because only a rebuild
 * returns GIN pages to the filesystem ({@code VACUUM} reclaims them into the index's own free space
 * and {@code pg_relation_size} does not fall). Reversing the two would rebuild the index over dead
 * tuples exactly as V175 does and leave this migration doing nothing that V175 had not already
 * done. This is NOT the ordering trap V175's javadoc records — that one is {@code ANALYZE} over a
 * still-BLOATED index, which makes the planner abandon it for more terms rather than fewer. Here
 * the {@code VACUUM} removes the bloat in the same statement that collects the statistics, and the
 * {@code REINDEX} that follows writes its own {@code relpages}/{@code reltuples} into
 * {@code pg_class}, so the planner sees an accurate reading of a compact index either way.
 *
 * <p><b>Idempotent and cheap.</b> Re-running vacuums an already-clean table and rebuilds an already
 * compact index. Measured 119 ms + 61 ms against V175's own 2 496 ms.
 *
 * <p><b>No checksum.</b> This migration has no external resource: {@code BaseJavaMigration}'s
 * {@code null} checksum is correct, and its body is pinned by
 * {@code db.migration.V176VacuumCitiesAfterHromadaBackfillTest} instead.
 */
public class V176__vacuum_cities_after_hromada_backfill extends BaseJavaMigration {

    private static final Logger log =
            LoggerFactory.getLogger(V176__vacuum_cities_after_hromada_backfill.class);

    /** The GIN index V173 creates, V175 bloats and rebuilds, and this migration rebuilds clean. */
    static final String TRIGRAM_INDEX = "idx_cities_name_uk_trgm";

    /**
     * {@code false} is the entire reason this migration is a separate version: {@code VACUUM} is
     * illegal inside a transaction block, so Flyway must run it outside one.
     */
    @Override
    public boolean canExecuteInTransaction() {
        return false;
    }

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection(); // owned by Flyway — never closed here
        reclaim(connection);
        log.info("V176: vacuumed cities and rebuilt {} over the reclaimed space", TRIGRAM_INDEX);
    }

    /**
     * Package-private, not private, for the same reason as V175's seams: the statement ORDER is
     * load-bearing and invisible to any post-migration measurement — a database vacuumed AFTER the
     * rebuild and one vacuumed before it differ only in {@code pg_relation_size}, which autovacuum
     * can also move. The unit test drives this directly against a mock connection.
     *
     * @param connection Flyway's connection; never closed here
     */
    void reclaim(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("VACUUM (ANALYZE) cities");
            statement.execute("REINDEX INDEX " + TRIGRAM_INDEX);
        }
    }
}
