package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@code V152__drop_redundant_masters_user_id_index.sql} (Phase 265 audit-fix
 * cycle 2, LOW — redundant index).
 *
 * <p>Per QA playbook Q21, "Flyway applied with no error" proves nothing about a migration's actual
 * outcome; a {@code DROP INDEX IF EXISTS} that names a typo'd index succeeds silently and drops
 * nothing. This test pins the outcome directly via {@code pg_indexes} on the fully migrated chain
 * (this module's Testcontainers Postgres runs every migration up to the latest {@code V*}, so it
 * doubles as the "applies cleanly on a fresh DB" proof), mirroring
 * {@link V118DropPriceSortIndicesMigrationTest} — the existing precedent for a drop-index
 * migration.
 *
 * <p>The pair of assertions is the whole point, and neither is meaningful alone:
 * <ul>
 *   <li>{@code idx_masters_user_id} (V4) must be GONE — a plain btree on a single column that
 *       already carries a UNIQUE constraint, so it is pure write amplification and buffer-cache
 *       occupancy with no plan it can serve that the constraint's index cannot.</li>
 *   <li>{@code masters_user_id_key} — the index Postgres created to enforce that UNIQUE constraint
 *       ({@code Master}: {@code @JoinColumn(name = "user_id", unique = true)}) — must SURVIVE.
 *       This is the assertion that makes the first one safe. Every user-scoped finder in
 *       {@code MasterRepository} ({@code findActiveByUserIdWithUserAndSalon},
 *       {@code findByUserIdWithSalon}, {@code existsByUserIdAndMasterTypeAndIsActiveTrue}) is an
 *       equality lookup on {@code user_id}; without this index the "redundant" drop would be a
 *       sequential scan on the hottest provider path in the app. A migration that dropped BOTH
 *       would satisfy the first assertion perfectly.</li>
 * </ul>
 *
 * <p>Also pins {@code idx_masters_user_owner_type} (V56, the partial unique index enforcing one
 * {@code SALON_OWNER}-type row per user) as still present — it also leads on {@code user_id} and
 * is the one a future "clean up the duplicate user_id indexes" sweep is most likely to mistake for
 * another redundant copy. It is not: its {@code WHERE master_type = 'SALON_OWNER'} predicate is
 * what makes the owner-as-master uniqueness invariant enforceable, and JPA's {@code @Index} cannot
 * express it.
 *
 * <p>Fully read-only and order-independent; {@code cleanDb()} never touches catalog metadata, so
 * no fixture or cleanup is required. ASCII-only.
 */
@DisplayName("V152 migration — drop the redundant masters(user_id) btree, keep the UNIQUE index")
class V152DropRedundantMastersUserIdIndexMigrationTest extends AbstractIntegrationTest {

    private static final String INDEX_EXISTS_QUERY = """
            SELECT COUNT(*)
            FROM pg_indexes
            WHERE tablename = 'masters'
              AND indexname = ?
            """;

    private boolean indexExists(String indexName) {
        Integer n = jdbcTemplate.queryForObject(INDEX_EXISTS_QUERY, Integer.class, indexName);
        return n != null && n == 1;
    }

    @Test
    @DisplayName("idx_masters_user_id no longer exists (V152 drop)")
    void should_dropRedundantUserIdIndex_when_v152Applied() {
        assertThat(indexExists("idx_masters_user_id"))
                .as("V4's plain btree on masters(user_id) duplicates the UNIQUE constraint's own "
                        + "index on the same single column — it can serve no plan the unique index "
                        + "cannot, while being maintained on every INSERT, row-moving UPDATE and "
                        + "VACUUM")
                .isFalse();
    }

    @Test
    @DisplayName("masters_user_id_key survives — the drop must not remove the UNIQUE index it relies on")
    void should_keepUniqueUserIdIndex_when_v152Applied() {
        assertThat(indexExists("masters_user_id_key"))
                .as("this is the index that makes the V152 drop safe: every user-scoped finder in "
                        + "MasterRepository is an equality lookup on user_id and plans onto it. "
                        + "Without this assertion a migration that dropped BOTH indexes would still "
                        + "satisfy should_dropRedundantUserIdIndex_when_v152Applied, and the "
                        + "hottest provider read path would silently become a sequential scan")
                .isTrue();
    }

    @Test
    @DisplayName("idx_masters_user_owner_type survives — a partial unique index, not another duplicate")
    void should_keepPartialOwnerTypeIndex_when_v152Applied() {
        assertThat(indexExists("idx_masters_user_owner_type"))
                .as("V56's partial unique index (WHERE master_type = 'SALON_OWNER') also leads on "
                        + "user_id and superficially resembles a third copy. It is not: its "
                        + "predicate is what enforces one owner-master row per user, the invariant "
                        + "MasterService#createMasterForOwner and Phase 265's whole owner-as-master "
                        + "toggle rest on")
                .isTrue();
    }
}
