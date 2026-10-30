package com.beautica.notification.telegram.link;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Phase 405 — {@code telegram_links}. Unscoped: callers prove phone/chat ownership first. */
public interface TelegramLinkRepository extends JpaRepository<TelegramLink, UUID> {

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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE TelegramLink l SET l.status = :to, l.statusChangedAt = :now
             WHERE l.id = :id AND l.status = :from
            """)
    int transitionById(@Param("id") UUID id, @Param("from") TelegramLinkStatus from,
                       @Param("to") TelegramLinkStatus to, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE TelegramLink l SET l.lastSentAt = :now WHERE l.id = :id")
    int touchLastSent(@Param("id") UUID id, @Param("now") Instant now);
}
