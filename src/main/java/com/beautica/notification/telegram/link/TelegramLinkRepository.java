package com.beautica.notification.telegram.link;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Phase 405 — {@code telegram_links}. Unscoped: callers prove phone/chat ownership first. */
public interface TelegramLinkRepository extends JpaRepository<TelegramLink, UUID> {

    /**
     * {@code hashtextextended} seed of the per-phone retention lock (phase 409 D1). Allocated in the ONE registry —
     * the "Advisory-lock salt allocation" list on {@code MasterServiceRepository#acquireBulkSetupLockWithTimeout}.
     */
    int PHONE_RETENTION_LOCK_SEED = 7;

    /** One link as the orphan backstop sees it: id + phone hash, nothing else. */
    interface LinkKey {
        UUID getId();

        byte[] getPhoneHash();
    }

    Optional<TelegramLink> findByPhoneHash(byte[] phoneHash);

    boolean existsByPhoneHashAndChatIdAndStatus(byte[] phoneHash, long chatId, TelegramLinkStatus status);

    /** D5/D8 — insert, or rebind the phone to the newly proven chat as {@code ACTIVE}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO telegram_links (id, phone_hash, chat_id, status, linked_at, status_changed_at)
            VALUES (:id, :phoneHash, :chatId, 'ACTIVE', :now, :now)
            ON CONFLICT (phone_hash) DO UPDATE
               SET chat_id = EXCLUDED.chat_id,
                   status = 'ACTIVE',
                   linked_at = EXCLUDED.linked_at,
                   status_changed_at = EXCLUDED.status_changed_at
            """, nativeQuery = true)
    int upsert(@Param("id") UUID id, @Param("phoneHash") byte[] phoneHash, @Param("chatId") long chatId,
               @Param("now") Instant now);

    /**
     * Deletes the chat's links. Phase 406 audit: bounded by the same fused, transaction-local 3 s
     * {@code lock_timeout} as every other 405 lock — the joined one-row config subquery is evaluated before the
     * delete locks a row (a joined row needs both sides), so a held row aborts with {@code 55P03} instead of
     * parking a webhook thread.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM telegram_links l
             USING (SELECT set_config('lock_timeout', '3s', true)) lock_cfg
             WHERE l.chat_id = :chatId
            """, nativeQuery = true)
    int deleteByChat(@Param("chatId") long chatId);

    /**
     * Deletes the phone's link (guest «Повернутися до SMS», phase 407 unlink). Phase 407 perf audit: the same fused,
     * transaction-local 3 s {@code lock_timeout} shape as {@link #deleteByChat}, so a held row aborts with
     * {@code 55P03} (global 409) instead of waiting out the statement timeout.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM telegram_links l
             USING (SELECT set_config('lock_timeout', '3s', true)) lock_cfg
             WHERE l.phone_hash = :phoneHash
            """, nativeQuery = true)
    int deleteByPhone(@Param("phoneHash") byte[] phoneHash);

    /**
     * Phase 409 D1 — deletes the links of {@code phoneHashes} (phones whose last guest booking the retention batch
     * just anonymised, still unbooked under the retention lock). Runs inside the batch transaction; the same fused,
     * transaction-local 3 s {@code lock_timeout} as {@link #deleteByPhone}: a held row aborts the batch with
     * {@code 55P03} (rolled back, retried next run) instead of waiting out the statement timeout. Rides
     * {@code ux_telegram_links_phone}; the caller bounds the collection by its batch size.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            DELETE FROM telegram_links l
             USING (SELECT set_config('lock_timeout', '3s', true)) lock_cfg
             WHERE l.phone_hash IN (:phoneHashes)
            """, nativeQuery = true)
    int deleteByPhones(@Param("phoneHashes") Collection<byte[]> phoneHashes);

    /**
     * Phase 409 D1 — which of {@code phoneHashes} have a link at all (most freed phones never linked Telegram), so the
     * retention batch locks and re-checks only those. Rides {@code ux_telegram_links_phone}; no row lock.
     */
    @Query(value = "SELECT l.phone_hash FROM telegram_links l WHERE l.phone_hash IN (:phoneHashes)",
            nativeQuery = true)
    List<byte[]> findLinkedPhoneHashes(@Param("phoneHashes") Collection<byte[]> phoneHashes);

    /**
     * Phase 409 D1 (audit-fix cycle 1) — per-phone retention lock, keyed on the phone HASH hex (never the raw phone),
     * taken by every retention batch for each linked phone, in hash order, BEFORE its "still has a booking?" check.
     * Two instances that anonymise the phone's last two bookings in parallel therefore serialise: the second waits
     * for the first to commit, and its (READ COMMITTED, later) check sees both bookings anonymised and deletes the
     * link. Fused transaction-local 3 s {@code lock_timeout}, like {@code TelegramLinkTokenRepository
     * #acquirePhoneIssueLock}: a longer wait aborts the batch with {@code 55P03}.
     */
    @Query(value = "SELECT 1 FROM (SELECT set_config('lock_timeout', '3s', true), "
            + "pg_advisory_xact_lock(hashtextextended(encode(:phoneHash, 'hex'), " + PHONE_RETENTION_LOCK_SEED
            + "))) sub", nativeQuery = true)
    Integer acquirePhoneRetentionLock(@Param("phoneHash") byte[] phoneHash);

    /**
     * Phase 409 orphan backstop (audit-fix cycle 1) — one keyset page of links created before {@code linkedBefore},
     * in id order after {@code afterId}. Primary-key range scan bounded by {@code limit}; no row lock.
     */
    @Query(value = """
            SELECT l.id AS id, l.phone_hash AS phoneHash FROM telegram_links l
             WHERE l.id > :afterId AND l.linked_at < :linkedBefore
             ORDER BY l.id
             LIMIT :limit
            """, nativeQuery = true)
    List<LinkKey> findLinkKeysPage(@Param("afterId") UUID afterId, @Param("linkedBefore") Instant linkedBefore,
                                   @Param("limit") int limit);

    /**
     * Phase 409 orphan backstop — deletes the given links (one backstop page, bounded by its size) in its own
     * {@code REQUIRES_NEW} transaction. {@code SKIP LOCKED} never waits; {@code linked_at} is re-checked so a link
     * re-bound since the page was read (the upsert keeps the id and restamps {@code linked_at}) survives.
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            WITH doomed AS MATERIALIZED (
                SELECT id FROM telegram_links
                 WHERE id IN (:ids) AND linked_at < :linkedBefore
                 FOR UPDATE SKIP LOCKED
            )
            DELETE FROM telegram_links l USING doomed WHERE l.id = doomed.id
            """, nativeQuery = true)
    int deleteOrphansByIds(@Param("ids") Collection<UUID> ids, @Param("linkedBefore") Instant linkedBefore);

    /**
     * Phase 409 D2 — deletes ONE bounded batch of {@code BLOCKED} links whose {@code status_changed_at} is before
     * {@code cutoff}, in its own {@code REQUIRES_NEW} transaction (the {@code NotificationOutboxRepository
     * #deleteTerminalBatchBefore} shape). Served by the partial index {@code idx_telegram_links_blocked} (V224); the
     * {@code MATERIALIZED} CTE evaluates {@code LIMIT}/{@code FOR UPDATE SKIP LOCKED} exactly once, and
     * {@code SKIP LOCKED} never waits, so no {@code lock_timeout} is needed. A row a webhook is reactivating right now
     * is skipped and re-evaluated next run.
     * No tie-break column in the {@code ORDER BY}: a {@code SKIP LOCKED} claim never waits, so lock order cannot
     * deadlock, and which equal-timestamp row goes first does not matter.
     *
     * @return rows deleted, never more than {@code limit}
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            WITH doomed AS MATERIALIZED (
                SELECT id FROM telegram_links
                 WHERE status = 'BLOCKED'
                   AND status_changed_at < :cutoff
                 ORDER BY status_changed_at
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
            )
            DELETE FROM telegram_links l USING doomed WHERE l.id = doomed.id
            """, nativeQuery = true)
    int deleteBlockedBatchBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** Moves the chat's {@code from} rows to {@code to}; no-op on rows already in another state. */
    default int transitionByChat(long chatId, TelegramLinkStatus from, TelegramLinkStatus to, Instant now) {
        return transitionByChatBounded(chatId, from.name(), to.name(), now);
    }

    /**
     * {@link #transitionByChat} with the fused, transaction-local 3 s {@code lock_timeout} (phase 406 audit; same
     * shape as {@link #deleteByChat}). Status values are the {@link TelegramLinkStatus} names (V224 CHECK).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE telegram_links l SET status = :to, status_changed_at = :now
              FROM (SELECT set_config('lock_timeout', '3s', true)) lock_cfg
             WHERE l.chat_id = :chatId AND l.status = :from
            """, nativeQuery = true)
    int transitionByChatBounded(@Param("chatId") long chatId, @Param("from") String from, @Param("to") String to,
                                @Param("now") Instant now);

    /**
     * Moves one link from {@code from} to {@code to} only while it still points at {@code chatId} (phase 408
     * audit-fix: a 403 from a stale chat must not block the phone after it was re-linked to a new chat — the upsert
     * keeps the row id and swaps {@code chat_id}). Fused, transaction-local 3 s {@code lock_timeout}, same shape as
     * {@link #transitionByChatBounded}.
     */
    default int transitionById(UUID id, long chatId, TelegramLinkStatus from, TelegramLinkStatus to, Instant now) {
        return transitionByIdBounded(id, chatId, from.name(), to.name(), now);
    }

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE telegram_links l SET status = :to, status_changed_at = :now
              FROM (SELECT set_config('lock_timeout', '3s', true)) lock_cfg
             WHERE l.id = :id AND l.chat_id = :chatId AND l.status = :from
            """, nativeQuery = true)
    int transitionByIdBounded(@Param("id") UUID id, @Param("chatId") long chatId, @Param("from") String from,
                              @Param("to") String to, @Param("now") Instant now);

    /** Stamps {@code last_sent_at}; fused, transaction-local 3 s {@code lock_timeout} (phase 408 audit-fix). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE telegram_links l SET last_sent_at = :now
              FROM (SELECT set_config('lock_timeout', '3s', true)) lock_cfg
             WHERE l.id = :id
            """, nativeQuery = true)
    int touchLastSent(@Param("id") UUID id, @Param("now") Instant now);
}
