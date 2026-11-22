package com.beautica.client.provider.repository;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plan pin for the client card's visit-items lookup ({@link ClientCardSql} {@code ITEMS_*}): the page's visit keys are
 * matched as {@code appointment_id IN (...) OR id IN (...)} so each arm reaches its own index
 * ({@code idx_bookings_appointment}, {@code bookings_pkey}) and the lookup costs the page size, never a scan of the
 * salon's / master's whole history. On the near-empty test table the planner prefers the cheap scope index (a scan of
 * the salon's rows with the key arms as a Filter), so — as in {@link V263GuestPhoneScopeIndexMigrationTest} — this is
 * an applicability probe by elimination: inside a rolled-back transaction every other plain bookings index is dropped
 * and seq / plain index scans are priced out, leaving "can both key arms drive a bitmap scan". The control case (the
 * {@code COALESCE(...) IN} form the comment in {@code ClientCardSql} warns against) proves the probe is not vacuous.
 */
@DisplayName("ClientCardSql visit items — keyed by appointment / booking index, never a scope scan")
class ClientCardVisitItemsPlanIT extends AbstractIntegrationTest {

    private static final String APPOINTMENT_INDEX = "idx_bookings_appointment";

    /** Plain (non-constraint) bookings indexes other than the appointment index; bookings_pkey is a constraint. */
    private static final String OTHER_INDEXES = """
            SELECT quote_ident(i.relname) FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid
             WHERE x.indrelid = 'bookings'::regclass
               AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = x.indexrelid)
               AND i.relname <> 'idx_bookings_appointment'
            """;
    private static final String KEY_ARMS = "(b.appointment_id IN (:visitKeys) OR b.id IN (:visitKeys))";

    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("salon, salon+master and master item lookups can read both key indexes (BitmapOr), no seq scan")
    void should_readKeyIndexes_when_visitItemsExplained() {
        for (String sql : List.of(ClientCardSql.ITEMS_SALON, ClientCardSql.ITEMS_SALON_MASTER,
                ClientCardSql.ITEMS_MASTER)) {
            String plan = explain(sql);

            assertThat(plan).as(plan)
                    .doesNotContain("Seq Scan on bookings")
                    .contains("BitmapOr")
                    .contains("Bitmap Index Scan on " + APPOINTMENT_INDEX)
                    .contains("Bitmap Index Scan on bookings_pkey");
        }
    }

    @Test
    @DisplayName("control: the COALESCE(appointment_id, id) IN form cannot use the key indexes")
    void should_notReadKeyIndexes_when_keysAreMatchedThroughCoalesce() {
        assertThat(ClientCardSql.ITEMS_SALON).contains(KEY_ARMS);
        String coalesced = ClientCardSql.ITEMS_SALON.replace(KEY_ARMS,
                "COALESCE(b.appointment_id, b.id) IN (:visitKeys)");

        String plan = explain(coalesced);

        assertThat(plan).as(plan).doesNotContain(APPOINTMENT_INDEX).doesNotContain("Bitmap Index Scan on bookings_pkey");
    }

    /** EXPLAIN with only the key indexes left and seq / plain index scans priced out; always rolled back. */
    private String explain(String sql) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("salonId", UUID.randomUUID())
                .addValue("masterId", UUID.randomUUID())
                .addValue("visitKeys", List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbcTemplate);
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            jdbcTemplate.queryForList(OTHER_INDEXES, String.class)
                    .forEach(index -> jdbcTemplate.execute("DROP INDEX " + index));
            jdbcTemplate.execute("SET LOCAL enable_indexscan = off");
            String plan = String.join("\n", named.queryForList("EXPLAIN " + sql, params, String.class));
            status.setRollbackOnly();
            return plan;
        });
    }
}
