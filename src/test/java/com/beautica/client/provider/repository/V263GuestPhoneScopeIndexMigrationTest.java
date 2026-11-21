package com.beautica.client.provider.repository;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@code V263__drop_redundant_guest_phone_scope_index.sql}: V259's
 * {@code idx_bookings_guest_phone_starts_at} (same rows as V199's {@code idx_bookings_guest_phone_open} under
 * {@code chk_bookings_guest_fields}) is gone, and BOTH guest-phone readers plan on the surviving index — the retention
 * erase-by-phone lookup and the provider client card's guest scope ({@link ClientCardSql}, hence this package).
 * Seq scans are priced out so the assertions pin index applicability, not table size. On the near-empty test table
 * the card's guest scope is cheapest through its salon / master scope index (the phone arrives as an InitPlan value),
 * so the phone-index applicability probe is an index hint by elimination: inside a rolled-back transaction it drops
 * every other plain bookings index and turns plain index scans off (a bitmap scan needs a real qual or an implied
 * predicate). What remains is "can the planner prove the scope predicate implies idx_bookings_guest_phone_open's" —
 * the property the {@code client_detached_at IS NULL} term in {@code ClientCardSql} exists for; the control case
 * (the pre-V263 text without that term) proves the probe is not vacuous.
 */
@DisplayName("V263 migration — one guest-phone index serves erase-by-phone and the client card guest scope")
class V263GuestPhoneScopeIndexMigrationTest extends AbstractIntegrationTest {

    private static final String SURVIVOR = "idx_bookings_guest_phone_open";
    private static final String DROPPED = "idx_bookings_guest_phone_starts_at";

    /** Plain (non-constraint) bookings indexes other than the survivor — dropped by the applicability probe. */
    private static final String OTHER_INDEXES = """
            SELECT quote_ident(i.relname) FROM pg_index x JOIN pg_class i ON i.oid = x.indexrelid
             WHERE x.indrelid = 'bookings'::regclass
               AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = x.indexrelid)
               AND i.relname <> 'idx_bookings_guest_phone_open'
            """;
    private static final String DETACHED_TERM = " AND b.client_detached_at IS NULL)";

    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("V259's duplicate guest-phone index is dropped; V199's stays")
    void should_keepOnlyTheOpenGuestPhoneIndex_when_migrated() {
        // Act
        Integer dropped = countIndex(DROPPED);
        Integer survivor = countIndex(SURVIVOR);

        // Assert
        assertThat(dropped).isZero();
        assertThat(survivor).isOne();
    }

    @Test
    @DisplayName("the client card guest scope (salon, master) is index-backed with V259's index gone")
    void should_planGuestScopeOnAnIndex_when_guestCardsExplained() {
        // Act
        String salon = explain(ClientCardSql.CARD_GUEST_SALON, false);
        String master = explain(ClientCardSql.CARD_GUEST_MASTER, false);

        // Assert
        assertThat(salon).as(salon).doesNotContain("Seq Scan on bookings").doesNotContain(DROPPED);
        assertThat(master).as(master).doesNotContain("Seq Scan on bookings").doesNotContain(DROPPED);
    }

    @Test
    @DisplayName("the client card guest scope (salon) can read the phone through idx_bookings_guest_phone_open")
    void should_applyOpenPhoneIndexToGuestScope_when_salonGuestCardExplainedWithoutScopeIndexes() {
        // Act
        String plan = explain(ClientCardSql.CARD_GUEST_SALON, true);

        // Assert
        assertThat(plan).as(plan).contains("Bitmap Index Scan on " + SURVIVOR)
                .contains("Index Cond: ((guest_phone)::text = ").doesNotContain("Seq Scan on bookings");
    }

    @Test
    @DisplayName("the client card guest scope (independent master) can read the phone through "
            + "idx_bookings_guest_phone_open")
    void should_applyOpenPhoneIndexToGuestScope_when_masterGuestCardExplainedWithoutScopeIndexes() {
        // Act
        String plan = explain(ClientCardSql.CARD_GUEST_MASTER, true);

        // Assert
        assertThat(plan).as(plan).contains("Bitmap Index Scan on " + SURVIVOR)
                .contains("Index Cond: ((guest_phone)::text = ").doesNotContain("Seq Scan on bookings");
    }

    @Test
    @DisplayName("control: without the client_detached_at term (pre-V263 text) the open phone index is NOT applicable")
    void should_notApplyOpenPhoneIndex_when_guestScopeLacksDetachedTerm() {
        // Arrange
        assertThat(ClientCardSql.CARD_GUEST_MASTER).contains(DETACHED_TERM);
        String preV263 = ClientCardSql.CARD_GUEST_MASTER.replace(DETACHED_TERM, ")");

        // Act
        String plan = explain(preV263, true);

        // Assert
        assertThat(plan).as(plan).doesNotContain(SURVIVOR);
    }

    @Test
    @DisplayName("the retention erase-by-phone lookup can read the phone through idx_bookings_guest_phone_open")
    void should_planEraseByPhoneOnOpenPhoneIndex_when_explained() {
        // Act — applicability probe: on the near-empty shared table idx_bookings_guest_pii_due (same partial
        // predicate) ties with the phone index and the pick is stats-dependent, so only the survivor is left.
        String plan = explain("""
                SELECT id FROM bookings WHERE guest_phone = '+380501234567' AND client_id IS NULL
                   AND guest_pii_anonymised_at IS NULL AND client_detached_at IS NULL""", true);

        // Assert
        assertThat(plan).as(plan).contains("Bitmap Index Scan on " + SURVIVOR)
                .contains("Index Cond: ((guest_phone)::text = ").doesNotContain("Seq Scan on bookings");
    }

    private Integer countIndex(String index) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_indexes WHERE tablename = 'bookings' AND indexname = ?", Integer.class, index);
    }

    /**
     * EXPLAIN with seq scans priced out; {@code onlySurvivor} also drops every other plain bookings index and turns
     * plain index scans off (on an empty table a qual-less full pkey scan ties with any index scan). Always rolled
     * back, so the shared schema is untouched.
     */
    private String explain(String sql, boolean onlySurvivor) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("salonId", UUID.randomUUID())
                .addValue("masterId", UUID.randomUUID())
                .addValue("bookingId", UUID.randomUUID())
                .addValue("now", OffsetDateTime.parse("2026-06-02T09:00:00Z"));
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(jdbcTemplate);
        return new TransactionTemplate(transactionManager).execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            if (onlySurvivor) {
                jdbcTemplate.queryForList(OTHER_INDEXES, String.class)
                        .forEach(index -> jdbcTemplate.execute("DROP INDEX " + index));
                jdbcTemplate.execute("SET LOCAL enable_indexscan = off");
            }
            String plan = String.join("\n", named.queryForList("EXPLAIN " + sql, params, String.class));
            status.setRollbackOnly();
            return plan;
        });
    }
}
