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

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM TelegramLink l WHERE l.chatId = :chatId")
    int deleteByChat(@Param("chatId") long chatId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM TelegramLink l WHERE l.phoneHash = :phoneHash")
    int deleteByPhone(@Param("phoneHash") byte[] phoneHash);

    /** Moves the chat's {@code from} rows to {@code to}; no-op on rows already in another state. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE TelegramLink l SET l.status = :to, l.statusChangedAt = :now
             WHERE l.chatId = :chatId AND l.status = :from
            """)
    int transitionByChat(@Param("chatId") long chatId, @Param("from") TelegramLinkStatus from,
                         @Param("to") TelegramLinkStatus to, @Param("now") Instant now);

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
