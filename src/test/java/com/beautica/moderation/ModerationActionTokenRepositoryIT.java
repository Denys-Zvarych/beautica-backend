package com.beautica.moderation;

import com.beautica.AbstractIntegrationTest;
import com.beautica.moderation.dto.ModerationAction;
import com.beautica.moderation.entity.ModerationActionToken;
import com.beautica.moderation.entity.ReportTargetType;
import com.beautica.moderation.repository.ModerationActionTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 414 — {@code moderation_action_tokens} (V218) against real Postgres: the single-use claim, the nonce and
 * liveness conditions, target-scoped revocation, and every CHECK constraint.
 */
@DisplayName("ModerationActionTokenRepository — V218 (phase 414)")
class ModerationActionTokenRepositoryIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MICROS);
    private static final String NONCE = "b".repeat(64);

    @Autowired
    private ModerationActionTokenRepository repository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    @DisplayName("claim is single-use: the first claim gets 1 row, the second 0; the nonce is consumed")
    void should_claimOnce_when_claimedTwice() {
        String hash = insert("REVIEW", UUID.randomUUID(), "REMOVE", NOW.plus(Duration.ofDays(7)), NONCE,
                NOW.plus(Duration.ofMinutes(30)));

        int first = tx(() -> repository.claim(hash, NONCE, NOW));
        int second = tx(() -> repository.claim(hash, NONCE, NOW));

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT confirm_nonce_hash IS NULL AND used_at IS NOT NULL "
                + "FROM moderation_action_tokens WHERE token_hash = ?", Boolean.class, hash)).isTrue();
    }

    @Test
    @DisplayName("claim gets 0 rows for an expired token, a revoked token, a wrong nonce and an expired nonce")
    void should_notClaim_when_notLiveOrNonceBad() {
        Instant later = NOW.plus(Duration.ofMinutes(30));
        String expired = insert("REVIEW", UUID.randomUUID(), "REMOVE", NOW.minusSeconds(1), NONCE, later);
        String revoked = insert("REVIEW", UUID.randomUUID(), "REMOVE", NOW.plus(Duration.ofDays(1)), NONCE, later);
        jdbcTemplate.update("UPDATE moderation_action_tokens SET revoked_at = ? WHERE token_hash = ?",
                Timestamp.from(NOW), revoked);
        String wrongNonce = insert("REVIEW", UUID.randomUUID(), "REMOVE", NOW.plus(Duration.ofDays(1)), NONCE, later);
        String staleNonce = insert("REVIEW", UUID.randomUUID(), "REMOVE", NOW.plus(Duration.ofDays(1)), NONCE,
                NOW.minusSeconds(1));
        String noNonce = insert("REVIEW", UUID.randomUUID(), "REMOVE", NOW.plus(Duration.ofDays(1)), null, null);

        assertThat(tx(() -> repository.claim(expired, NONCE, NOW))).isZero();
        assertThat(tx(() -> repository.claim(revoked, NONCE, NOW))).isZero();
        assertThat(tx(() -> repository.claim(wrongNonce, "c".repeat(64), NOW))).isZero();
        assertThat(tx(() -> repository.claim(staleNonce, NONCE, NOW))).isZero();
        assertThat(tx(() -> repository.claim(noNonce, NONCE, NOW))).isZero();
    }

    @Test
    @DisplayName("setNonce overwrites on a live row and is a no-op on a used row")
    void should_setNonceOnlyOnLiveRow_when_called() {
        String live = insert("CLIENT_REVIEW", UUID.randomUUID(), "DISMISS", NOW.plus(Duration.ofDays(1)), NONCE,
                NOW.plus(Duration.ofMinutes(30)));
        String used = insert("CLIENT_REVIEW", UUID.randomUUID(), "DISMISS", NOW.plus(Duration.ofDays(1)), null, null);
        jdbcTemplate.update("UPDATE moderation_action_tokens SET used_at = ? WHERE token_hash = ?",
                Timestamp.from(NOW), used);

        int onLive = tx(() -> repository.setNonce(live, "d".repeat(64), NOW.plus(Duration.ofMinutes(30)), NOW));
        int onUsed = tx(() -> repository.setNonce(used, "d".repeat(64), NOW.plus(Duration.ofMinutes(30)), NOW));

        assertThat(onLive).isEqualTo(1);
        assertThat(onUsed).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT confirm_nonce_hash FROM moderation_action_tokens "
                + "WHERE token_hash = ?", String.class, live)).isEqualTo("d".repeat(64));
    }

    @Test
    @DisplayName("revokeAllForTarget touches only that one target's not-yet-revoked rows — claimed rows included")
    void should_revokeOnlyRowsOfTarget_when_revoking() {
        UUID target = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        insert("REVIEW", target, "REMOVE", NOW.plus(Duration.ofDays(1)), null, null);
        insert("REVIEW", target, "DISMISS", NOW.plus(Duration.ofDays(1)), null, null);
        String used = insert("REVIEW", target, "RESTORE", NOW.plus(Duration.ofDays(1)), null, null);
        jdbcTemplate.update("UPDATE moderation_action_tokens SET used_at = ? WHERE token_hash = ?",
                Timestamp.from(NOW.minusSeconds(60)), used);
        insert("REVIEW", other, "REMOVE", NOW.plus(Duration.ofDays(1)), null, null);
        insert("CLIENT_REVIEW", target, "REMOVE", NOW.plus(Duration.ofDays(1)), null, null);

        int revoked = tx(() -> repository.revokeAllForTarget(ReportTargetType.REVIEW, target, NOW));

        assertThat(revoked).isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM moderation_action_tokens "
                + "WHERE revoked_at IS NOT NULL", Integer.class)).isEqualTo(3);
        assertThat(revokedAtIsNull(used)).as("a claimed row is revoked too").isFalse();
    }

    @Test
    @DisplayName("audit-fix: claim → concurrent resolve revokes the target → releaseClaim cannot revive the link")
    void should_notRevive_when_concurrentResolveCommittedBeforeRelease() {
        UUID target = UUID.randomUUID();
        String hash = insert("REVIEW", target, "REMOVE", NOW.plus(Duration.ofDays(1)), NONCE,
                NOW.plus(Duration.ofMinutes(30)));
        tx(() -> repository.claim(hash, NONCE, NOW));
        tx(() -> repository.revokeAllForTarget(ReportTargetType.REVIEW, target, NOW.plusSeconds(1)));

        int released = tx(() -> repository.releaseClaim(hash, NOW));

        assertThat(released).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT used_at IS NOT NULL AND revoked_at IS NOT NULL "
                + "FROM moderation_action_tokens WHERE token_hash = ?", Boolean.class, hash)).isTrue();
    }

    @Test
    @DisplayName("audit-fix: revokeLiveWithoutOpenReport revokes live rows only — an expired row is left untouched")
    void should_skipExpiredRows_when_revokingWithoutOpenReport() {
        String live = insert("REVIEW", UUID.randomUUID(), "REMOVE", NOW.plus(Duration.ofDays(1)), null, null);
        String expired = insert("REVIEW", UUID.randomUUID(), "REMOVE", NOW.minusSeconds(1), null, null);

        int revoked = tx(() -> repository.revokeLiveWithoutOpenReport(NOW, 100));

        assertThat(revoked).isEqualTo(1);
        assertThat(revokedAtIsNull(live)).isFalse();
        assertThat(revokedAtIsNull(expired)).as("already dead, only waits for the purge").isTrue();
    }

    @Test
    @DisplayName("audit-fix: releaseClaim un-uses exactly that claim; a revoked or differently-stamped row stays used")
    void should_releaseOnlyMatchingClaim_when_releasing() {
        String hash = insert("REVIEW", UUID.randomUUID(), "REMOVE", NOW.plus(Duration.ofDays(1)), NONCE,
                NOW.plus(Duration.ofMinutes(30)));
        tx(() -> repository.claim(hash, NONCE, NOW));

        int wrongStamp = tx(() -> repository.releaseClaim(hash, NOW.minusSeconds(5)));
        int released = tx(() -> repository.releaseClaim(hash, NOW));

        assertThat(wrongStamp).isZero();
        assertThat(released).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT used_at IS NULL FROM moderation_action_tokens "
                + "WHERE token_hash = ?", Boolean.class, hash)).isTrue();
    }

    @Test
    @DisplayName("audit-fix: Persistable — saveAll inserts minted rows; a row read back is not new")
    void should_insertMintedRowsAndLoadAsNotNew_when_savedAll() {
        UUID target = UUID.randomUUID();
        List<ModerationActionToken> rows = List.of(
                ModerationActionToken.mint(ReportTargetType.REVIEW, target, ModerationAction.REMOVE, hex(),
                        NOW.plus(Duration.ofDays(7)), NOW),
                ModerationActionToken.mint(ReportTargetType.REVIEW, target, ModerationAction.DISMISS, hex(),
                        NOW.plus(Duration.ofDays(7)), NOW));

        transactionTemplate.executeWithoutResult(status -> repository.saveAll(rows));
        ModerationActionToken loaded = transactionTemplate.execute(status ->
                repository.findByTokenHash(rows.get(0).getTokenHash()).orElseThrow());

        assertThat(rows).allSatisfy(row -> assertThat(row.isNew()).as("cleared by @PostPersist").isFalse());
        assertThat(loaded.isNew()).as("cleared by @PostLoad").isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM moderation_action_tokens WHERE target_id = ?",
                Integer.class, target)).isEqualTo(2);
    }

    @Test
    @DisplayName("audit-fix: revokeAllForTarget's predicate is served by the partial index ix_mat_live_target "
            + "(seq scan disabled so the tiny test table cannot hide a predicate mismatch)")
    void should_useLiveTargetIndex_when_revokingForTarget() {
        List<String> plan = transactionTemplate.execute(status -> {
            jdbcTemplate.execute("SET LOCAL enable_seqscan = off");
            return jdbcTemplate.queryForList("EXPLAIN UPDATE moderation_action_tokens SET revoked_at = now() "
                    + "WHERE target_type = 'REVIEW' AND target_id = '" + UUID.randomUUID() + "' "
                    + "AND revoked_at IS NULL", String.class);
        });

        assertThat(String.join("\n", plan)).contains("ix_mat_live_target");
    }

    @Test
    @DisplayName("CHECK: RESTORE on a photo / profile is rejected")
    void should_rejectRestore_when_targetNotReview() {
        assertThatThrownBy(() -> insert("PORTFOLIO_PHOTO", UUID.randomUUID(), "RESTORE", NOW.plus(Duration.ofDays(1)),
                null, null)).isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_mat_restore");
        assertThatThrownBy(() -> insert("SALON_PROFILE", UUID.randomUUID(), "RESTORE", NOW.plus(Duration.ofDays(1)),
                null, null)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("CHECK: bad token-hash format, bad nonce format, unknown action / target type, unpaired nonce")
    void should_rejectMalformedRows_when_inserting() {
        Instant exp = NOW.plus(Duration.ofDays(1));
        assertThatThrownBy(() -> insertRaw("REVIEW", "REMOVE", "A".repeat(64), null, null, exp))
                .hasMessageContaining("chk_mat_hash_fmt");
        assertThatThrownBy(() -> insertRaw("REVIEW", "REMOVE", hex(), "xyz", NOW, exp))
                .hasMessageContaining("chk_mat_nonce_fmt");
        assertThatThrownBy(() -> insertRaw("REVIEW", "REMOVE", hex(), NONCE, null, exp))
                .hasMessageContaining("chk_mat_nonce_pair");
        assertThatThrownBy(() -> insertRaw("REVIEW", "REMOVE", hex(), null, NOW, exp))
                .hasMessageContaining("chk_mat_nonce_pair");
        assertThatThrownBy(() -> insertRaw("REVIEW", "DELETE", hex(), null, null, exp))
                .hasMessageContaining("chk_mat_action");
        assertThatThrownBy(() -> insertRaw("BOOKING_NOTE", "REMOVE", hex(), null, null, exp))
                .hasMessageContaining("chk_mat_target_type");
    }

    @Test
    @DisplayName("token_hash is unique")
    void should_rejectDuplicateHash_when_inserting() {
        String hash = hex();
        insertRaw("REVIEW", "REMOVE", hash, null, null, NOW.plus(Duration.ofDays(1)));

        assertThatThrownBy(() -> insertRaw("REVIEW", "DISMISS", hash, null, null, NOW.plus(Duration.ofDays(1))))
                .hasMessageContaining("ux_mat_token_hash");
    }

    private String insert(String type, UUID targetId, String action, Instant expiresAt, String nonceHash,
                          Instant nonceExpiresAt) {
        String hash = hex();
        jdbcTemplate.update("INSERT INTO moderation_action_tokens (id, target_type, target_id, action, token_hash, "
                        + "confirm_nonce_hash, confirm_nonce_expires_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), type, targetId, action, hash, nonceHash,
                nonceExpiresAt == null ? null : Timestamp.from(nonceExpiresAt), Timestamp.from(expiresAt));
        return hash;
    }

    private void insertRaw(String type, String action, String hash, String nonceHash, Instant nonceExpiresAt,
                           Instant expiresAt) {
        jdbcTemplate.update("INSERT INTO moderation_action_tokens (id, target_type, target_id, action, token_hash, "
                        + "confirm_nonce_hash, confirm_nonce_expires_at, expires_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), type, UUID.randomUUID(), action, hash, nonceHash,
                nonceExpiresAt == null ? null : Timestamp.from(nonceExpiresAt), Timestamp.from(expiresAt));
    }

    private boolean revokedAtIsNull(String hash) {
        return jdbcTemplate.queryForObject("SELECT revoked_at IS NULL FROM moderation_action_tokens WHERE token_hash = ?",
                Boolean.class, hash);
    }

    private static String hex() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
    }

    private int tx(java.util.function.IntSupplier statement) {
        Integer result = transactionTemplate.execute(status -> statement.getAsInt());
        return result == null ? 0 : result;
    }
}
