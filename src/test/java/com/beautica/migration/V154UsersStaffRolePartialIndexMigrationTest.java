package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Contract test for {@code V154__users_staff_role_partial_index.sql} (Phase 289 perf-audit
 * CRITICAL-1) — the partial index {@code StaffClientReferenceAuditRepository}'s six queries all
 * rely on to avoid a {@code Seq Scan} on {@code users}.
 *
 * <p>Per QA playbook Q21, Testcontainers applying the migration during every
 * {@code @SpringBootTest} run proves only that the SQL is <em>valid</em> — it proves nothing about
 * whether the index is the <em>intended</em> shape. A CREATE INDEX with the wrong column, the wrong
 * predicate, or no predicate at all still applies cleanly and every existing
 * {@code StaffClientReferenceAuditServiceIT} case still passes (an unindexed or wrongly-scoped
 * index changes the query PLAN, never the query RESULT), so none of that phase's 11 functional
 * tests would catch a regression here. This class pins the index definition directly via
 * {@code pg_indexes}, mirroring {@code V138WalkInPhoneIndexMigrationTest} and
 * {@code V152DropRedundantMastersUserIdIndexMigrationTest} — the existing precedent for exactly
 * this shape of gap in this repo.
 *
 * <p>Read against the fully migrated chain, so the schema block doubles as the "applies cleanly on
 * a fresh DB" proof.
 */
@DisplayName("V154 migration — the partial staff-role index on users(role)")
class V154UsersStaffRolePartialIndexMigrationTest extends AbstractIntegrationTest {

    private static final String INDEX = "idx_users_staff_role";

    private static final String INDEX_DEF_QUERY = """
            SELECT indexdef FROM pg_indexes WHERE tablename = 'users' AND indexname = ?
            """;

    @Test
    @DisplayName("the index exists on users under the name the migration declares")
    void should_createTheIndex_when_v154Applied() {
        assertThat(indexDef())
                .as("without it, every StaffClientReferenceAuditRepository query filtering "
                        + "role IN ('SALON_MASTER','SALON_ADMIN') forces a Seq Scan on users — "
                        + "the exact CRITICAL-1 finding V154 exists to fix")
                .isNotNull();
    }

    @Test
    @DisplayName("it is on the role column, USING btree")
    void should_indexTheRoleColumn_when_v154Applied() {
        assertThat(indexDef())
                .as("a partial index that predicates on role but indexes a different column would "
                        + "still apply cleanly and still be found by the previous test")
                .containsIgnoringCase("USING btree")
                .contains("(role)");
    }

    @Test
    @DisplayName("it is PARTIAL on role IN ('SALON_MASTER','SALON_ADMIN') — not a full-column index")
    void should_bePartialOnStaffRoles_when_v154Applied() {
        String def = indexDef();

        assertThat(def)
                .as("a full-column index on users(role) would still satisfy the two tests above "
                        + "and would still make every query in StaffClientReferenceAuditRepository "
                        + "fast — this is the ONLY assertion in this class that catches the "
                        + "migration's own documented rationale (staff are ~20% of users; a "
                        + "full-column index pays maintenance cost on every CLIENT/SALON_OWNER/"
                        + "INDEPENDENT_MASTER write for no query it serves) silently regressing to "
                        + "an unconditional index")
                .containsIgnoringCase("WHERE");
        assertThat(def).contains("SALON_MASTER");
        assertThat(def).contains("SALON_ADMIN");
    }

    @Test
    @DisplayName("the predicate does not admit a third role — CLIENT/SALON_OWNER/INDEPENDENT_MASTER excluded")
    void should_notCoverNonStaffRoles_when_v154Applied() {
        assertThat(indexDef())
                .as("the audit only ever queries role IN (SALON_MASTER, SALON_ADMIN) — a predicate "
                        + "widened to include CLIENT (the overwhelming majority of users) would "
                        + "silently defeat the whole point of making this index partial")
                .doesNotContain("'CLIENT'")
                .doesNotContain("'SALON_OWNER'")
                .doesNotContain("'INDEPENDENT_MASTER'");
    }

    private String indexDef() {
        return jdbcTemplate.query(INDEX_DEF_QUERY, rs -> rs.next() ? rs.getString(1) : null, INDEX);
    }
}
