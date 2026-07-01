package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for the three query-path indexes added by the 19.x perf work:
 * <ul>
 *   <li>{@code V93} → {@code idx_bookings_master_client_starts_at} on {@code bookings}
 *       (master_id, client_id, starts_at DESC) — the favorites LATERAL top-1 lookup.</li>
 *   <li>{@code V95} → {@code idx_bookings_client_starts_at} on {@code bookings}
 *       (client_id, starts_at DESC) — the {@code GET /bookings/me} ORDER BY without a
 *       Sort node.</li>
 *   <li>{@code V96} → {@code idx_reviews_client_created} on {@code reviews}
 *       (client_id, created_at DESC) — the {@code GET /reviews/me} ORDER BY without a
 *       Sort node.</li>
 * </ul>
 *
 * <p>Per QA playbook Q21, "Flyway applied with no error" proves nothing about a
 * migration's actual outcome. This test pins each declared index's EXISTENCE directly via
 * {@code pg_indexes} on the migrated chain, and asserts the redundant single-column
 * {@code idx_reviews_client_id} V96 DROPs is GONE. Fully read-only and order-independent;
 * {@code cleanDb()} never touches catalog metadata, so no fixture or cleanup is required.
 * ASCII-only.
 */
@DisplayName("V93/V95/V96 migration — booking + review query-path indexes")
class BookingReviewQueryIndexesMigrationTest extends AbstractIntegrationTest {

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

    private boolean indexExists(String table, String indexName) {
        Integer n = jdbcTemplate.queryForObject(INDEX_EXISTS_QUERY, Integer.class, table, indexName);
        return n != null && n == 1;
    }

    private String indexDef(String table, String indexName) {
        return jdbcTemplate.queryForObject(INDEX_DEF_QUERY, String.class, table, indexName);
    }

    // ── V93 — idx_bookings_master_client_starts_at ────────────────────────────────

    @Test
    @DisplayName("idx_bookings_master_client_starts_at exists on bookings(master_id, client_id, starts_at DESC)")
    void should_createMasterClientStartsAtIndex_when_v93Applied() {
        assertThat(indexExists("bookings", "idx_bookings_master_client_starts_at"))
                .as("V93 composite index for the favorites LATERAL top-1 lookup")
                .isTrue();
        assertThat(indexDef("bookings", "idx_bookings_master_client_starts_at"))
                .as("idx_bookings_master_client_starts_at must cover (master_id, client_id, starts_at)")
                .contains("master_id")
                .contains("client_id")
                .contains("starts_at");
    }

    // ── V95 — idx_bookings_client_starts_at ───────────────────────────────────────

    @Test
    @DisplayName("idx_bookings_client_starts_at exists on bookings(client_id, starts_at DESC)")
    void should_createClientStartsAtIndex_when_v95Applied() {
        assertThat(indexExists("bookings", "idx_bookings_client_starts_at"))
                .as("V95 composite index for GET /bookings/me ordered scan")
                .isTrue();
        assertThat(indexDef("bookings", "idx_bookings_client_starts_at"))
                .as("idx_bookings_client_starts_at must cover (client_id, starts_at)")
                .contains("client_id")
                .contains("starts_at");
    }

    // ── V96 — idx_reviews_client_created created, idx_reviews_client_id dropped ────

    @Test
    @DisplayName("idx_reviews_client_created exists on reviews(client_id, created_at DESC)")
    void should_createReviewsClientCreatedIndex_when_v96Applied() {
        assertThat(indexExists("reviews", "idx_reviews_client_created"))
                .as("V96 composite index for GET /reviews/me ordered scan")
                .isTrue();
        assertThat(indexDef("reviews", "idx_reviews_client_created"))
                .as("idx_reviews_client_created must cover (client_id, created_at)")
                .contains("client_id")
                .contains("created_at");
    }

    @Test
    @DisplayName("idx_reviews_client_id no longer exists (V96 drop — strict prefix of idx_reviews_client_created)")
    void should_dropRedundantReviewsClientIdIndex_when_v96Applied() {
        assertThat(indexExists("reviews", "idx_reviews_client_id"))
                .as("idx_reviews_client_id must be DROPPED by V96 — the composite "
                        + "idx_reviews_client_created handles WHERE client_id = ? via leftmost-prefix")
                .isFalse();
    }
}
