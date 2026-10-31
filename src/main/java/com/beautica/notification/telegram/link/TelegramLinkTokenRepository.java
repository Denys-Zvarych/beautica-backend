package com.beautica.notification.telegram.link;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Phase 405 — {@code telegram_link_tokens}. Unscoped: the service decides who may touch which row. */
public interface TelegramLinkTokenRepository extends JpaRepository<TelegramLinkToken, byte[]> {

    /**
     * {@code hashtextextended} seed of the per-phone issue lock. Seeds are allocated in ONE registry — the
     * "Advisory-lock salt allocation" list on {@code MasterServiceRepository#acquireBulkSetupLockWithTimeout};
     * claim new ones there.
     */
    int PHONE_ISSUE_LOCK_SEED = 5;
    /** {@code hashtextextended} seed of the per-chat claim lock (same registry as {@link #PHONE_ISSUE_LOCK_SEED}). */
    int CHAT_CLAIM_LOCK_SEED = 6;

    /**
     * D6 — serialises {@code issueForBooking} per phone so the cap's delete-then-insert cannot be raced past 5 live
     * tokens. Keyed on the phone HASH (hex), never the raw phone. Fused 3 s {@code lock_timeout}, as on every
     * first-in-transaction advisory lock in the codebase (e.g. {@code BookingRepository#acquireWalkInPhoneLock}):
     * a wait beyond it aborts with {@code 55P03} instead of parking a pool connection forever.
     */
    @Query(value = "SELECT 1 FROM (SELECT set_config('lock_timeout', '3s', true), "
            + "pg_advisory_xact_lock(hashtextextended(encode(:phoneHash, 'hex'), " + PHONE_ISSUE_LOCK_SEED + "))) sub",
            nativeQuery = true)
    Integer acquirePhoneIssueLock(@Param("phoneHash") byte[] phoneHash);

    /**
     * D7/D8 — serialises {@code claim} per chat; the first lock of every claim. Two claims of one chat therefore never
     * interleave, and two first-time claims of different tokens cannot both end {@code CLAIMED}. Claim lock order:
     * this chat lock, then {@link #lockClaimRows} (the target and the chat's {@code CLAIMED} rows, in
     * {@code token_hash} order, the same order the eviction delete locks in).
     */
    @Query(value = "SELECT 1 FROM (SELECT set_config('lock_timeout', '3s', true), "
            + "pg_advisory_xact_lock(hashtextextended(CAST(:chatId AS text), " + CHAT_CLAIM_LOCK_SEED + "))) sub",
            nativeQuery = true)
    Integer acquireChatClaimLock(@Param("chatId") long chatId);

    /**
     * D7/D8 — every row a {@code claim} may write, locked in ONE statement in {@code token_hash} order before anything
     * is mutated: the target token plus every {@code CLAIMED} row of the chat (the claims it must release). The
     * eviction delete ({@link #deleteLiveBeyondNewest}) locks in the same order, so a claim and a concurrent issue
     * for the same phone can never hold one row each while waiting on the other (no 40P01). Fused 3 s
     * {@code lock_timeout} (the joined config subquery runs before {@code LockRows} waits). Rows a concurrent writer
     * moved out of the predicate are dropped by Postgres's post-wait re-check.
     */
    @Query(value = """
            SELECT t.* FROM telegram_link_tokens t
            CROSS JOIN (SELECT set_config('lock_timeout', '3s', true)) lock_cfg
             WHERE t.token_hash = :tokenHash OR (t.claimed_chat_id = :chatId AND t.state = 'CLAIMED')
             ORDER BY t.token_hash
             FOR UPDATE OF t
            """, nativeQuery = true)
    List<TelegramLinkToken> lockClaimRows(@Param("tokenHash") byte[] tokenHash, @Param("chatId") long chatId);

    /**
     * D7/D8 — this chat's unexpired {@code CLAIMED} token, locked. A concurrent proof that already finished the
     * row makes Postgres re-check the predicate after the lock wait and return nothing (→ {@code NO_PENDING}).
     * Newest claim wins should two parallel claims ever leave two rows claimed by one chat.
     *
     * <p>This is the FIRST lock of a {@code proveContact} transaction, so the 3 s {@code lock_timeout} is fused here
     * (the joined one-row config subquery is evaluated before {@code LockRows} waits — the
     * {@code SalonRepository#findByIdForUpdate} shape); a longer wait aborts with {@code 55P03}.
     */
    @Query(value = """
            SELECT t.* FROM telegram_link_tokens t
            CROSS JOIN (SELECT set_config('lock_timeout', '3s', true)) lock_cfg
             WHERE t.claimed_chat_id = :chatId AND t.state = 'CLAIMED' AND t.expires_at > :now
             ORDER BY t.claimed_at DESC
             LIMIT 1
             FOR UPDATE OF t
            """, nativeQuery = true)
    Optional<TelegramLinkToken> findPendingClaimForUpdate(@Param("chatId") long chatId, @Param("now") Instant now);

    /**
     * D6 — per-phone cap: deletes every live ({@code ISSUED}/{@code CLAIMED}, unexpired) token of the phone beyond
     * the {@code keep} it retains. Called with {@code cap - 1} right before an insert (under
     * {@link #acquirePhoneIssueLock}), so the new token is the cap-th. {@code CLAIMED} tokens are retained first, so a
     * guest mid-flow (pressed Start, not yet shared the phone) is evicted only after every {@code ISSUED} one.
     *
     * <p>Victims are row-locked in {@code token_hash} order (the outer {@code ORDER BY token_hash FOR UPDATE}; the
     * inner priority sort takes no locks), so the lock order is deterministic against a concurrent {@code claim}
     * of the same rows. The live filter is repeated on {@code v}: the innermost subselect is an uncorrelated hashed
     * subplan, so only {@code v}'s own predicate is re-checked against the locked row version after a lock wait — a
     * row a concurrent prove just turned {@code LINKED} (or a claim just changed) is then not deleted.
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            DELETE FROM telegram_link_tokens
             WHERE token_hash IN (
                   SELECT v.token_hash FROM telegram_link_tokens v
                    WHERE v.token_hash IN (
                          SELECT token_hash FROM telegram_link_tokens
                           WHERE phone_hash = :phoneHash AND state IN ('ISSUED', 'CLAIMED') AND expires_at > :now
                           ORDER BY (state = 'CLAIMED') DESC, created_at DESC, token_hash
                          OFFSET :keep)
                      AND v.state IN ('ISSUED', 'CLAIMED') AND v.expires_at > :now
                    ORDER BY v.token_hash
                    FOR UPDATE OF v)
            """, nativeQuery = true)
    int deleteLiveBeyondNewest(@Param("phoneHash") byte[] phoneHash, @Param("now") Instant now,
                               @Param("keep") int keep);

    /**
     * Phase 409 D4 — deletes ONE bounded batch of tokens whose {@code expires_at} is before {@code cutoff}, in ANY
     * state (terminal {@code LINKED}/{@code MISMATCH} rows hold {@code claimed_chat_id} + {@code phone_hash}), in its
     * own {@code REQUIRES_NEW} transaction (the {@code NotificationOutboxRepository#deleteTerminalBatchBefore}
     * shape). Served by {@code idx_tg_tokens_expiry} (V224). {@code MATERIALIZED} CTE so {@code LIMIT}/
     * {@code SKIP LOCKED} is evaluated exactly once; {@code SKIP LOCKED} never waits (a row a claim/prove holds is
     * taken next run).
     * No tie-break column in the {@code ORDER BY}: a {@code SKIP LOCKED} claim never waits, so lock order cannot
     * deadlock, and which equal-timestamp row goes first does not matter.
     *
     * @return rows deleted, never more than {@code limit}
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            WITH doomed AS MATERIALIZED (
                SELECT token_hash FROM telegram_link_tokens
                 WHERE expires_at < :cutoff
                 ORDER BY expires_at
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
            )
            DELETE FROM telegram_link_tokens t USING doomed WHERE t.token_hash = doomed.token_hash
            """, nativeQuery = true)
    int deleteExpiredBatchBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /**
     * Phase 409 D3 — the erase: deletes every token of the phone (any state, any age). Victims are row-locked in
     * {@code token_hash} order — the order {@link #lockClaimRows} and {@link #deleteLiveBeyondNewest} lock in, so an
     * erase racing a claim cannot deadlock — under the fused, transaction-local 3 s {@code lock_timeout}
     * ({@code 55P03} instead of a parked erase). Rides {@code idx_tg_tokens_phone}; a phone holds a handful of rows.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM telegram_link_tokens
             WHERE token_hash IN (
                   SELECT v.token_hash FROM telegram_link_tokens v
                   CROSS JOIN (SELECT set_config('lock_timeout', '3s', true)) lock_cfg
                    WHERE v.phone_hash = :phoneHash
                    ORDER BY v.token_hash
                    FOR UPDATE OF v)
            """, nativeQuery = true)
    int deleteAllByPhoneHash(@Param("phoneHash") byte[] phoneHash);
}
