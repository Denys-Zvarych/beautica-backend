package com.beautica.notification.telegram.link;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 405 — a guest phone whose non-urgent messages go to a Telegram chat ({@code telegram_links}, V224).
 *
 * <p>Read model only: rows are written by {@link TelegramLinkRepository#upsert} ({@code ON CONFLICT (phone_hash)},
 * D5/D8) and changed by its bulk status updates, so the entity has no factory and no setters. Keyed by the
 * {@code PhoneHasher} HMAC, never the raw phone (D2). {@code chatId} is personal data (D3): never logged; erased by
 * phase 409. No external storage.
 */
@Entity
// idx_telegram_links_blocked (status_changed_at) WHERE status = 'BLOCKED' is a PARTIAL index in V224 (phase 409
// purge); JPA @Index cannot express the predicate, so it is not mirrored here.
@Table(name = "telegram_links",
        uniqueConstraints = @UniqueConstraint(name = "ux_telegram_links_phone", columnNames = "phone_hash"),
        indexes = @Index(name = "idx_telegram_links_chat", columnList = "chat_id"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TelegramLink {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "phone_hash", nullable = false, updatable = false)
    private byte[] phoneHash;

    @Column(name = "chat_id", nullable = false)
    private long chatId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private TelegramLinkStatus status;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    @Column(name = "status_changed_at", nullable = false)
    private Instant statusChangedAt;

    @Column(name = "last_sent_at")
    private Instant lastSentAt;

    /** Defensive copy — the key is never exposed mutable. */
    @JsonIgnore
    public byte[] getPhoneHash() {
        return phoneHash.clone();
    }
}
