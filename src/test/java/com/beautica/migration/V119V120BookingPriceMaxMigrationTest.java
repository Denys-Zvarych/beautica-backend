package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@code V119__add_bookings_price_max_at_booking.sql} and
 * {@code V120__backfill_bookings_price_max_at_booking.sql} (Phase 26.9 — freeze the booking price
 * band).
 *
 * <p>Per QA playbook Q21, "Flyway applied with no error" proves nothing about a migration's actual
 * outcome — the schema shape IS the contract here, and three of V119's properties are load-bearing
 * design decisions the file argues for at length rather than incidental details:
 * <ul>
 *   <li><b>NULLABLE.</b> {@code NULL} is the meaningful majority value ("single price — render
 *       {@code price_at_booking} alone"): every FIXED service, and every RANGE service whose master
 *       set a {@code price_override}. A future migration adding {@code NOT NULL} (with any default)
 *       would silently turn "no band" into "a band of zero/whatever", which the DTO layer cannot
 *       distinguish from a genuinely frozen ceiling.</li>
 *   <li><b>No CHECK.</b> V119 explicitly declines a
 *       {@code price_max_at_booking >= price_at_booking} constraint, because even {@code NOT VALID}
 *       it would be enforced on every future UPDATE — a single legacy row that slipped through
 *       would become un-cancellable and un-reschedulable, bricking booking mutations. This test
 *       fails if someone "hardens" the column later without re-reading that reasoning.</li>
 *   <li><b>Same NUMERIC(10,2) as the floor it pairs with.</b> A wider or narrower type on the
 *       ceiling would round one end of the band differently from the other.</li>
 * </ul>
 *
 * <p>Also pins that BOTH versions are recorded as applied — the split into two files is itself the
 * lock-avoidance contract (V119's ACCESS EXCLUSIVE must commit before V120's full-table scan
 * begins), so a future "tidy-up" that merges them back must fail here, not silently in production
 * during a Railway rolling deploy.
 *
 * <p>Fully read-only and order-independent; {@code cleanDb()} never touches catalog metadata, so no
 * fixture or cleanup is required. ASCII-only.
 */
@DisplayName("V119/V120 migrations — the frozen price_max_at_booking column")
class V119V120BookingPriceMaxMigrationTest extends AbstractIntegrationTest {

    private static final String COLUMN_QUERY = """
            SELECT is_nullable, data_type, numeric_precision, numeric_scale, column_default
            FROM information_schema.columns
            WHERE table_name = 'bookings' AND column_name = ?
            """;

    private Map<String, Object> column(String columnName) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(COLUMN_QUERY, columnName);
        assertThat(rows).as("column bookings.%s must exist", columnName).hasSize(1);
        return rows.get(0);
    }

    @Test
    @DisplayName("V119 adds bookings.price_max_at_booking")
    void should_addPriceMaxColumn_when_v119Applied() {
        assertThat(column("price_max_at_booking")).isNotNull();
    }

    @Test
    @DisplayName("price_max_at_booking is NULLABLE with no default — NULL is the meaningful "
            + "'single price' value, not a missing one")
    void should_beNullableWithNoDefault_when_priceMaxColumnInspected() {
        Map<String, Object> col = column("price_max_at_booking");

        assertThat(col.get("is_nullable"))
                .as("NOT NULL would force a default that the DTO layer cannot distinguish from a "
                        + "genuinely frozen ceiling — NULL means 'render price_at_booking alone'")
                .isEqualTo("YES");
        assertThat(col.get("column_default"))
                .as("a default would fabricate a band on every FIXED-service booking")
                .isNull();
    }

    @Test
    @DisplayName("price_max_at_booking is NUMERIC(10,2) — byte-for-byte the type of the "
            + "price_at_booking floor it pairs with")
    void should_matchFloorColumnType_when_priceMaxColumnInspected() {
        Map<String, Object> ceiling = column("price_max_at_booking");
        Map<String, Object> floor = column("price_at_booking");

        assertThat(ceiling.get("data_type")).isEqualTo("numeric");
        assertThat(ceiling.get("numeric_precision")).isEqualTo(10);
        assertThat(ceiling.get("numeric_scale")).isEqualTo(2);
        assertThat(ceiling)
                .as("a band whose two ends round differently is a rendering bug waiting to happen")
                .containsAllEntriesOf(Map.of(
                        "data_type", floor.get("data_type"),
                        "numeric_precision", floor.get("numeric_precision"),
                        "numeric_scale", floor.get("numeric_scale")));
    }

    @Test
    @DisplayName("no CHECK constraint references price_max_at_booking — V119 declines one "
            + "deliberately, because it would brick mutations on any legacy row that slipped through")
    void should_haveNoCheckConstraint_when_priceMaxColumnInspected() {
        List<String> checks = jdbcTemplate.queryForList("""
                SELECT con.conname
                FROM pg_constraint con
                JOIN pg_class rel ON rel.oid = con.conrelid
                WHERE rel.relname = 'bookings'
                  AND con.contype = 'c'
                  AND pg_get_constraintdef(con.oid) LIKE '%price_max_at_booking%'
                """, String.class);

        assertThat(checks)
                .as("a price_max >= price_at_booking CHECK is enforced on every future UPDATE even "
                        + "as NOT VALID, so one bad legacy row would make its booking impossible to "
                        + "cancel or reschedule — see V119's closing comment")
                .isEmpty();
    }

    @Test
    @DisplayName("price_max_at_booking carries the column comment that documents NULL = single price")
    void should_documentNullSemantics_when_columnCommentInspected() {
        String comment = jdbcTemplate.queryForObject("""
                SELECT col_description(rel.oid, att.attnum)
                FROM pg_class rel
                JOIN pg_attribute att ON att.attrelid = rel.oid
                WHERE rel.relname = 'bookings' AND att.attname = 'price_max_at_booking'
                """, String.class);

        assertThat(comment)
                .as("the NULL semantics are not inferable from the schema; the comment is the only "
                        + "in-database record of them")
                .isNotNull()
                .contains("NULL = single price");
    }

    @Test
    @DisplayName("V119 and V120 are BOTH recorded as applied, as separate versions — the split IS "
            + "the lock-avoidance contract and must never be merged back")
    void should_recordBothVersionsSeparately_when_flywayHistoryInspected() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT version, success
                FROM flyway_schema_history
                WHERE version IN ('119', '120')
                ORDER BY version
                """);

        assertThat(rows)
                .as("merging the backfill back into V119 would hold ACCESS EXCLUSIVE on `bookings` "
                        + "across the full-table scan, blocking every read during a rolling deploy")
                .extracting(r -> r.get("version"), r -> r.get("success"))
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("119", true),
                        org.assertj.core.groups.Tuple.tuple("120", true));
    }
}
