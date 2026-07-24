package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@code V118__drop_price_sort_indices.sql} (Phase 26.8 — retire
 * {@code GET /bookings/me}'s {@code priceAtBooking} sort).
 *
 * <p>Per QA playbook Q21, "Flyway applied with no error" proves nothing about a migration's
 * actual outcome. This test pins the outcome directly via {@code pg_indexes} on the fully
 * migrated chain (this module's Testcontainers Postgres always runs every migration up to the
 * latest {@code V*}, so this doubles as the "applies cleanly on a fresh DB" proof — there is no
 * separate legacy-snapshot DB in this suite to also apply it against a pre-existing {@code V117}
 * state):
 * <ul>
 *   <li>{@code idx_bookings_master_price_id} / {@code idx_bookings_client_price_id} (V117,
 *       Phase 26.6) must be GONE — their only query shape,
 *       {@code ?sort=priceAtBooking,desc}/{@code ,asc}, is rejected with 400 by
 *       {@code BookingService#SORTABLE_BOOKING_PROPERTIES} as of this phase, so Postgres can
 *       never choose them again.</li>
 *   <li>{@code idx_bookings_master_starts_at} / {@code idx_bookings_client_starts_at} (V117) must
 *       still exist — untouched by V118, they serve the surviving {@code startsAt} sort, the
 *       {@code DEFAULT_BOOKING_SORT} substitution, and the timeline's day-rail queries.</li>
 * </ul>
 *
 * <p>Also pins {@code idx_bookings_master_completed_starts_at}'s (V43) column list to exactly
 * {@code (master_id, starts_at)} — a structural guard unrelated to V118's own drop, added here
 * because this class already has the {@code pg_indexes} machinery. See
 * {@link #should_notContainIdColumn_when_completedStartsAtIndexInspected()} for why: that index's
 * lack of an {@code id} column is load-bearing for
 * {@link com.beautica.booking.BookingMyBookingsSortIT
 * #should_maintainDeterministicPagination_when_manyBookingsShareIdenticalStartsAt()}'s proof that
 * the code-level {@code id ASC} tiebreaker (not index key order) resolves pagination ties.
 *
 * <p>Fully read-only and order-independent; {@code cleanDb()} never touches catalog metadata, so
 * no fixture or cleanup is required. ASCII-only.
 */
@DisplayName("V118 migration — drop the two priceAtBooking-sort indices, keep the startsAt ones")
class V118DropPriceSortIndicesMigrationTest extends AbstractIntegrationTest {

    private static final String INDEX_EXISTS_QUERY = """
            SELECT COUNT(*)
            FROM pg_indexes
            WHERE tablename = ?
              AND indexname = ?
            """;

    private static final String INDEX_DEF_QUERY = """
            SELECT indexdef
            FROM pg_indexes
            WHERE tablename = ?
              AND indexname = ?
            """;

    private boolean indexExists(String indexName) {
        Integer n = jdbcTemplate.queryForObject(INDEX_EXISTS_QUERY, Integer.class, "bookings", indexName);
        return n != null && n == 1;
    }

    /**
     * Returns the ordered column list of a btree index on {@code bookings}, parsed out of
     * {@code pg_indexes.indexdef} (e.g. {@code "...USING btree (master_id, starts_at) WHERE..."}
     * yields {@code ["master_id", "starts_at"]}).
     */
    private List<String> indexColumns(String indexName) {
        String indexDef = jdbcTemplate.queryForObject(INDEX_DEF_QUERY, String.class, "bookings", indexName);
        assertThat(indexDef).as("index %s must exist", indexName).isNotNull();

        String columnList = indexDef.replaceFirst(".*USING btree \\(([^)]*)\\).*", "$1");
        return Arrays.stream(columnList.split(","))
                .map(String::trim)
                .collect(Collectors.toList());
    }

    @Test
    @DisplayName("idx_bookings_master_price_id no longer exists (V118 drop)")
    void should_dropMasterPriceIndex_when_v118Applied() {
        assertThat(indexExists("idx_bookings_master_price_id"))
                .as("V117's master-scope priceAtBooking-sort index must be DROPPED — its only "
                        + "query shape is now rejected with 400 before any query is built")
                .isFalse();
    }

    @Test
    @DisplayName("idx_bookings_client_price_id no longer exists (V118 drop)")
    void should_dropClientPriceIndex_when_v118Applied() {
        assertThat(indexExists("idx_bookings_client_price_id"))
                .as("V117's client-scope priceAtBooking-sort index must be DROPPED — it never had "
                        + "a caller at all, per the phase doc's design decision")
                .isFalse();
    }

    @Test
    @DisplayName("idx_bookings_master_starts_at still exists — untouched by V118")
    void should_keepMasterStartsAtIndex_when_v118Applied() {
        assertThat(indexExists("idx_bookings_master_starts_at"))
                .as("the surviving startsAt sort and the default-sort substitution both depend on "
                        + "this index — V118 must not touch it")
                .isTrue();
    }

    @Test
    @DisplayName("idx_bookings_client_starts_at still exists — untouched by V118")
    void should_keepClientStartsAtIndex_when_v118Applied() {
        assertThat(indexExists("idx_bookings_client_starts_at"))
                .as("the CLIENT-path startsAt sort depends on this index — V118 must not touch it")
                .isTrue();
    }

    /**
     * Structural regression guard, not a V118 assertion — {@code idx_bookings_master_completed_starts_at}
     * is V43, untouched by V118. Pinned here because {@code
     * com.beautica.booking.BookingMyBookingsSortIT#should_maintainDeterministicPagination_when_manyBookingsShareIdenticalStartsAt()}
     * depends entirely on this index having NO {@code id} column: its {@code ?status=COMPLETED}
     * query plans onto this partial index, whose ties resolve by heap physical order (which that
     * test deliberately perturbs via {@code CLUSTER}) rather than by index key order — the only
     * plan shape that can prove the code-level {@code id ASC} tiebreaker is doing anything.
     *
     * <p>If a future phase widens this index to add {@code id} — a reasonable-looking optimisation,
     * and exactly what V117 did to {@code idx_bookings_master_starts_at} /
     * {@code idx_bookings_client_starts_at} — the query plan would revert to resolving ties via the
     * index's own key order, and {@code BookingMyBookingsSortIT}'s tiebreaker test would silently
     * become unfailable: green whether or not the tiebreaker exists, with no CI signal. This test
     * exists to fail loudly the moment that happens, instead.
     *
     * <p>Mutation-verified (Phase 26.8 audit): temporarily recreating the index as
     * {@code (master_id, starts_at, id)} makes this test FAIL; restoring the original V43 definition
     * makes it PASS again.
     */
    @Test
    @DisplayName("idx_bookings_master_completed_starts_at has no id column — "
            + "BookingMyBookingsSortIT's tiebreaker proof depends on it")
    void should_notContainIdColumn_when_completedStartsAtIndexInspected() {
        assertThat(indexColumns("idx_bookings_master_completed_starts_at"))
                .as("an id column here would let the ?status=COMPLETED query plan resolve ties via "
                        + "index key order instead of heap physical order, silently defanging "
                        + "BookingMyBookingsSortIT's tiebreaker proof")
                .containsExactly("master_id", "starts_at");
    }
}
