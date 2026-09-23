package db.migration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link V176__vacuum_cities_after_hromada_backfill} — the three properties that
 * make it worth being a separate migration, none of which any post-migration measurement can see.
 *
 * <p>A database where this migration ran and one where it did not differ only in
 * {@code pg_relation_size} and in how many dead tuples {@code cities} carries, and autovacuum moves
 * BOTH on its own schedule. So an integration assertion on either number is a race with the daemon
 * (memory {@code project_postgres_check_null_passes} is the same failure shape in a different
 * place: a fact that looks measurable and is not). What is deterministic is the SQL this migration
 * issues, the order it issues it in, and whether it opts out of Flyway's transaction — all three
 * are pure function calls against a mock {@link Connection}, so a PostgreSQL container would be
 * minutes of startup to assert a string (§M-1).
 *
 * <p>The behavioural half — that the chain leaves a COMPACT index behind — lives in
 * {@code SettlementSearchCostGuardIT.should_keepTheTrigramIndexCompact_when_theMigrationsHaveApplied},
 * which measures the artefact after the real chain has run. Neither class subsumes the other:
 * deleting the {@code reclaim} call from {@code migrate} leaves this class green, and swapping the
 * two statements leaves the IT green (V175's own REINDEX already holds the index under that
 * ceiling — which is exactly why V175 keeps it).
 */
@DisplayName("V176__vacuum_cities_after_hromada_backfill — what it issues, and outside a transaction")
class V176VacuumCitiesAfterHromadaBackfillTest {

    private final V176__vacuum_cities_after_hromada_backfill migration =
            new V176__vacuum_cities_after_hromada_backfill();

    @Test
    @DisplayName("opts out of Flyway's transaction — VACUUM is illegal inside one")
    void should_runOutsideATransaction_when_flywayAsks() {
        // The whole reason this is a separate version rather than two more lines in V175. V175 is
        // deliberately transactional: its row-count check is the guard that makes it structurally
        // incapable of INSERTing a settlement the occupied-territory exclusion set removed, and a
        // guard whose abort does not roll the write back is not a guard. VACUUM cannot run inside
        // a transaction block, so the cleanup had to leave.
        //
        // Flipping this to true does not fail at compile time and fails no other test — it fails
        // at deploy, on Railway, mid-release, with "VACUUM cannot run inside a transaction block".
        assertThat(migration.canExecuteInTransaction())
                .as("if this is ever true the migration stops working — VACUUM cannot run "
                        + "inside a transaction block")
                .isFalse();
    }

    @Test
    @DisplayName("vacuums cities and only THEN rebuilds the trigram index")
    void should_vacuumBeforeReindexing_when_reclaiming() throws Exception {
        // The ORDER is the whole content of this migration. REINDEX before VACUUM rebuilds the
        // index over the 26 041 dead tuples V175 left — which is precisely what V175's own
        // in-transaction REINDEX already does, so a swapped V176 would be a no-op that costs
        // 180 ms and looks correct: same statements, same counts, same rows, index still 1 976 kB
        // instead of 1 176 kB.
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);

        migration.reclaim(connection);

        InOrder order = inOrder(statement);
        order.verify(statement).execute("VACUUM (ANALYZE) cities");
        order.verify(statement).execute("REINDEX INDEX idx_cities_name_uk_trgm");
    }

    @Test
    @DisplayName("rebuilds plainly, never CONCURRENTLY — a rebuild that leaves the old index behind")
    void should_notReindexConcurrently_when_theRebuildIsIssued() throws Exception {
        // REINDEX CONCURRENTLY is legal here — unlike in V175, this migration has no transaction —
        // which is exactly why it needs an assertion rather than being impossible. It builds the
        // replacement alongside the original and can leave an INVALID leftover index behind if it
        // is interrupted, on a permitAll endpoint's only defence against a full-table similarity()
        // sweep. On a 25 697-row table the plain rebuild is 61 ms of ACCESS EXCLUSIVE, taken during
        // a deploy; that is the trade this migration makes deliberately.
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        ArgumentCaptor<String> issued = ArgumentCaptor.forClass(String.class);

        migration.reclaim(connection);

        verify(statement, times(2)).execute(issued.capture());
        assertThat(issued.getAllValues())
                .noneMatch(sql -> sql.toUpperCase().contains("CONCURRENTLY"));
    }

    @Test
    @DisplayName("closes the statement even when the vacuum throws")
    void should_closeTheStatement_when_theVacuumFails() throws Exception {
        // try-with-resources, asserted because a leaked Statement on Flyway's own connection
        // outlives the migration, and this migration runs with no transaction to clean up after it.
        Connection connection = mock(Connection.class);
        Statement statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute(anyString())).thenThrow(new SQLException("could not obtain lock"));

        assertThatThrownBy(() -> migration.reclaim(connection))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("could not obtain lock");

        verify(statement).close();
    }
}
