package com.beautica.notification.telegram.link;

import org.springframework.jdbc.core.JdbcTemplate;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Phase 409 — raw {@code telegram_links} / {@code telegram_link_tokens} rows for the retention and purge ITs, shaped to
 * satisfy every V224 CHECK (claim pair, finished-iff-terminal). Shared by the booking-retention and token-purge ITs.
 */
public final class TelegramLinkRows {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration CLAIM_BEFORE_EXPIRY = Duration.ofMinutes(20);
    private static final Duration FINISH_BEFORE_EXPIRY = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;

    public TelegramLinkRows(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID link(byte[] phoneHash, long chatId, TelegramLinkStatus status, Instant statusChangedAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO telegram_links (id, phone_hash, chat_id, status, linked_at, status_changed_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                id, phoneHash, chatId, status.name(), Timestamp.from(statusChangedAt), Timestamp.from(statusChangedAt));
        return id;
    }

    /** A token of {@code state} expiring at {@code expiresAt}; claimed/finished stamps sit before expiry. */
    public byte[] token(UUID bookingId, byte[] phoneHash, TelegramTokenState state, Instant expiresAt) {
        byte[] tokenHash = new byte[32];
        RANDOM.nextBytes(tokenHash);
        boolean claimed = state != TelegramTokenState.ISSUED;
        boolean finished = state == TelegramTokenState.LINKED || state == TelegramTokenState.MISMATCH;
        jdbc.update("INSERT INTO telegram_link_tokens (token_hash, booking_id, phone_hash, state, expires_at, "
                        + "claimed_chat_id, claimed_at, finished_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                tokenHash, bookingId, phoneHash, state.name(), Timestamp.from(expiresAt),
                claimed ? 4242L : null,
                claimed ? Timestamp.from(expiresAt.minus(CLAIM_BEFORE_EXPIRY)) : null,
                finished ? Timestamp.from(expiresAt.minus(FINISH_BEFORE_EXPIRY)) : null,
                Timestamp.from(expiresAt.minus(Duration.ofMinutes(30))));
        return tokenHash;
    }

    public boolean linkExists(UUID id) {
        return count("SELECT count(*) FROM telegram_links WHERE id = ?", id) == 1;
    }

    public boolean tokenExists(byte[] tokenHash) {
        return count("SELECT count(*) FROM telegram_link_tokens WHERE token_hash = ?", tokenHash) == 1;
    }

    public int tokensOfPhone(byte[] phoneHash) {
        return count("SELECT count(*) FROM telegram_link_tokens WHERE phone_hash = ?", phoneHash);
    }

    public int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
