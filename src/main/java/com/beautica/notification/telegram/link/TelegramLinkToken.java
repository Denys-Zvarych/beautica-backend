package com.beautica.notification.telegram.link;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.domain.Persistable;

import java.security.MessageDigest;
import java.util.Arrays;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Phase 405 D6/D7 — one deep-link start token ({@code telegram_link_tokens}, V224). Only the SHA-256 of the raw
 * token is stored. {@code phoneHash} is the source booking's guest phone hash, the value a shared contact must
 * match. Rows cascade with their booking and are purged by phase 409 after expiry. No external storage.
 *
 * <p>Implements {@link Persistable} because the id is assigned: without it {@code save} would {@code merge}, i.e.
 * issue a SELECT before every INSERT.
 */
@Entity
// idx_tg_tokens_claim (claimed_chat_id) WHERE state = 'CLAIMED' is a PARTIAL index in V224; JPA @Index cannot express
// the predicate, so it is not mirrored here (a non-partial mirror would misdescribe it).
@Table(name = "telegram_link_tokens", indexes = {
        @Index(name = "idx_tg_tokens_booking", columnList = "booking_id"),
        @Index(name = "idx_tg_tokens_phone", columnList = "phone_hash"),
        @Index(name = "idx_tg_tokens_expiry", columnList = "expires_at")})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TelegramLinkToken implements Persistable<byte[]> {

    @Id
    @Column(name = "token_hash", nullable = false, updatable = false)
    private byte[] tokenHash;

    @Column(name = "booking_id", nullable = false, updatable = false)
    private UUID bookingId;

    @Column(name = "phone_hash", nullable = false, updatable = false)
    private byte[] phoneHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 16)
    private TelegramTokenState state;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    @Column(name = "claimed_chat_id")
    private Long claimedChatId;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Transient
    private boolean fresh;

    public static TelegramLinkToken issue(byte[] tokenHash, UUID bookingId, byte[] phoneHash,
                                          Instant now, Instant expiresAt) {
        TelegramLinkToken token = new TelegramLinkToken();
        token.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash").clone();
        token.bookingId = Objects.requireNonNull(bookingId, "bookingId");
        token.phoneHash = Objects.requireNonNull(phoneHash, "phoneHash").clone();
        token.state = TelegramTokenState.ISSUED;
        token.createdAt = Objects.requireNonNull(now, "now");
        token.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        token.fresh = true;
        return token;
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    public boolean isClaimedBy(long chatId) {
        return claimedChatId != null && claimedChatId == chatId;
    }

    /** Constant-time comparison with a candidate phone hash (D7). */
    public boolean phoneMatches(byte[] candidate) {
        return candidate != null && MessageDigest.isEqual(phoneHash, candidate);
    }

    void claim(long chatId, Instant now) {
        requireState(state == TelegramTokenState.ISSUED || state == TelegramTokenState.CLAIMED);
        if (!isClaimedBy(chatId)) {
            claimedChatId = chatId;
            claimedAt = now;
        }
        state = TelegramTokenState.CLAIMED;
    }

    /** D7 — back to unclaimed (still expiring); the chat claimed another token. */
    void release() {
        requireState(state == TelegramTokenState.CLAIMED);
        state = TelegramTokenState.ISSUED;
        claimedChatId = null;
        claimedAt = null;
    }

    boolean hasTokenHash(byte[] candidate) {
        return Arrays.equals(tokenHash, candidate);
    }

    void finish(TelegramTokenState terminal, Instant now) {
        requireState(state == TelegramTokenState.CLAIMED
                && (terminal == TelegramTokenState.LINKED || terminal == TelegramTokenState.MISMATCH));
        state = terminal;
        finishedAt = now;
    }

    private static void requireState(boolean ok) {
        if (!ok) {
            throw new IllegalStateException("Illegal telegram link token transition");
        }
    }

    @Override
    @JsonIgnore
    public byte[] getId() {
        return tokenHash.clone();
    }

    @JsonIgnore
    public byte[] getPhoneHash() {
        return phoneHash.clone();
    }

    @JsonIgnore
    public byte[] getTokenHash() {
        return tokenHash.clone();
    }

    /** The booking the token was issued for (407) — the booking whose details follow a successful link. */
    public UUID getBookingId() {
        return bookingId;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        fresh = false;
    }

    @Override
    public boolean isNew() {
        return fresh;
    }
}
