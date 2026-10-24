package com.beautica.booking.service;

import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.auth.phoneotp.GuestTokenProvider.GuestTokenClaims;
import com.beautica.common.exception.BusinessException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentMap;

/**
 * Phase 393 audit (security MEDIUM) — at most {@value #MAX_BOOKINGS_PER_TOKEN} guest bookings per guest token.
 *
 * <p><b>Why.</b> A guest token is a 30-minute multi-use bearer (one OTP verification may book a salon visit across
 * several masters, one POST each). The per-IP guest-booking bucket alone does not bound it: a caller rotating
 * source IPs could replay one token into unlimited CONFIRMED bookings (calendar flood) and billable confirmation
 * SMS. {@value #MAX_BOOKINGS_PER_TOKEN} covers the largest real visit (10 services, one master each). Applied to
 * BOTH guest create routes (master {@code /book/{slug}/booking} and salon
 * {@code /book/s/{slug}/masters/{masterId}/booking}) — they share one counter per token.
 *
 * <p><b>Key.</b> The token's {@code jti} (random per mint). A legacy token minted before the jti existed (only
 * possible within 30 minutes of the deploy) falls back to a SHA-256 of its phone — never the raw phone.
 *
 * <p><b>Counting.</b> A slot is reserved atomically BEFORE the booking body runs (so N concurrent replays cannot
 * all pass a check-then-act), and released if the surrounding transaction rolls back — a 400 / 404 / 409 attempt
 * does not burn the guest's budget; only committed bookings count. Over the cap → 429 (generic body via
 * {@code GlobalExceptionHandler}), before any DB read or SMS.
 *
 * <p><b>Storage + multi-instance caveat.</b> In-memory bounded Caffeine map, entry TTL = the guest token lifetime
 * (an entry never expires before its token: it is created after mint, its TTL is the full token lifetime, and it
 * is refreshed on each write). Correct for the current single-instance deployment. With N horizontally scaled
 * instances each keeps its own counter, so the effective cap becomes N × {@value #MAX_BOOKINGS_PER_TOKEN}
 * (still bounded) — move the counter to a shared store (Postgres / Redis) before scaling out. A restart resets
 * counters (also bounded: tokens live 30 minutes). {@link #MAX_TRACKED_TOKENS} bounds memory; size-eviction under
 * a flood of distinct tokens would require that many OTP verifications, each SMS- and per-IP-limited.
 */
@Component
public class GuestTokenBookingCap {

    static final int MAX_BOOKINGS_PER_TOKEN = 10;
    static final long MAX_TRACKED_TOKENS = 100_000;

    private final ConcurrentMap<String, Integer> bookingsByToken;

    public GuestTokenBookingCap() {
        Cache<String, Integer> cache = Caffeine.newBuilder()
                .maximumSize(MAX_TRACKED_TOKENS)
                .expireAfterWrite(GuestTokenProvider.lifetime())
                .build();
        this.bookingsByToken = cache.asMap();
    }

    /**
     * Reserves one booking for {@code claims}' token, released again if the current transaction rolls back.
     *
     * @throws BusinessException 429 when the token already holds {@value #MAX_BOOKINGS_PER_TOKEN} bookings
     */
    public void reserve(GuestTokenClaims claims) {
        String key = keyOf(claims);
        boolean[] admitted = {false};
        bookingsByToken.compute(key, (k, used) -> {
            int current = used == null ? 0 : used;
            if (current >= MAX_BOOKINGS_PER_TOKEN) {
                return used;
            }
            admitted[0] = true;
            return current + 1;
        });
        if (!admitted[0]) {
            throw new BusinessException(HttpStatus.TOO_MANY_REQUESTS, "Guest token booking cap reached");
        }
        releaseOnRollback(key);
    }

    private void releaseOnRollback(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED) {
                    bookingsByToken.computeIfPresent(key, (k, used) -> used <= 1 ? null : used - 1);
                }
            }
        });
    }

    private static String keyOf(GuestTokenClaims claims) {
        if (claims.tokenId() != null && !claims.tokenId().isBlank()) {
            return "jti:" + claims.tokenId();
        }
        return "phone:" + sha256Hex(String.valueOf(claims.phone()));
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
