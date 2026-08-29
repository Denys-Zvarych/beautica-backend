package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies V149__invite_tokens_salon_pending_index.sql produces the correct index (Phase 23.1 QA
 * audit — Perf MEDIUM: {@code GET /salons/{salonId}/invites/pending} filters on {@code salon_id}
 * but no prior index led with it).
 *
 * <p>Q21 (migration content assertion): "Flyway applied with no error" proves nothing about the
 * index actually existing or being shaped correctly — this test asserts by name, column order, and
 * partial-WHERE clause, mirroring {@code PrimarySalonMigrationTest}'s pattern.
 */
@DisplayName("V149 migration — invite_tokens salon-scoped pending-invite index")
class InviteTokenSalonPendingIndexMigrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("V149 — idx_invite_tokens_salon_pending index exists on invite_tokens")
    void should_createIdxInviteTokensSalonPending_when_v149Applied() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'invite_tokens' "
                        + "AND indexname = 'idx_invite_tokens_salon_pending'",
                String.class);

        assertThat(indexes)
                .as("idx_invite_tokens_salon_pending must be created by V149")
                .containsExactly("idx_invite_tokens_salon_pending");
    }

    @Test
    @DisplayName("V149 — idx_invite_tokens_salon_pending leads with salon_id then expires_at")
    void should_leadWithSalonIdThenExpiresAt_when_v149Applied() {
        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'invite_tokens' "
                        + "AND indexname = 'idx_invite_tokens_salon_pending'",
                String.class);

        // e.g. "CREATE INDEX idx_invite_tokens_salon_pending ON public.invite_tokens
        //       USING btree (salon_id, expires_at) WHERE (is_used = false)"
        int salonIdPos = indexDef.indexOf("salon_id");
        int expiresAtPos = indexDef.indexOf("expires_at");
        assertThat(salonIdPos)
                .as("salon_id must appear in the index definition — body: %s", indexDef)
                .isPositive();
        assertThat(expiresAtPos)
                .as("expires_at must appear in the index definition — body: %s", indexDef)
                .isGreaterThan(salonIdPos);
    }

    @Test
    @DisplayName("V149 — idx_invite_tokens_salon_pending is partial (WHERE is_used = false)")
    void should_makeIndexPartialOnUnusedTokens_when_v149Applied() {
        String indexDef = jdbcTemplate.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'invite_tokens' "
                        + "AND indexname = 'idx_invite_tokens_salon_pending'",
                String.class);

        assertThat(indexDef)
                .as("idx_invite_tokens_salon_pending must be a partial index restricted to unused "
                        + "tokens, matching the query predicate the endpoint actually issues")
                .containsIgnoringCase("where")
                .containsIgnoringCase("is_used");
    }

}
