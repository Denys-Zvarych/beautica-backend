package com.beautica.booking.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * The ONE way a guest cancel token becomes its stored form ({@code bookings.cancel_token_hash} /
 * {@code appointments.cancel_token_hash}, V206): lower-hex SHA-256 of {@code token.toString()} in UTF-8.
 *
 * <p>The raw token is a capability (whoever holds it can cancel the booking), so it lives only in the
 * guest's SMS / cancel link and in the create response that hands it to the guest. The database keeps
 * only this hash; every lookup and every consume hashes the incoming token through {@link #hash}.
 * V206's SQL backfill and its legacy-writer trigger compute the identical preimage
 * ({@code encode(sha256(convert_to(cancel_token::text, 'UTF8')), 'hex')} — Postgres renders a
 * {@code uuid} as the same lower-case hyphenated text as {@link UUID#toString()}).
 *
 * <p>Unsalted on purpose: the preimage is a 122-bit random UUID (legacy) or ~95 random bits (short, phase 435), so the hash is not invertible by
 * guessing, and a deterministic hash is what lets the unique index answer the lookup.
 */
public final class CancelTokenHasher {

    private static final String ALGORITHM = "SHA-256";
    private static final HexFormat HEX = HexFormat.of();

    private CancelTokenHasher() {
    }

    /** The 64-char lower-hex stored form of a legacy UUID {@code token} — identical to {@code hash(GuestCancelToken.of(token))}. */
    public static String hash(UUID token) {
        Objects.requireNonNull(token, "token");
        return hash(GuestCancelToken.of(token));
    }

    /**
     * The 64-char lower-hex stored form of {@code token}: SHA-256 of its raw string (a legacy UUID: its canonical
     * {@link UUID#toString()}, so every pre-existing hash still matches; a short token: its 16 base62 characters).
     */
    public static String hash(GuestCancelToken token) {
        Objects.requireNonNull(token, "token");
        byte[] preimage = token.value().getBytes(StandardCharsets.UTF_8);
        return HEX.formatHex(newSha256().digest(preimage));
    }

    /**
     * A fresh digest per call: {@link MessageDigest} is not thread-safe, and a {@code ThreadLocal} cache is
     * useless under virtual threads (same reasoning as {@code RetiredSlugHasher}).
     */
    private static MessageDigest newSha256() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // Every Java SE platform must provide SHA-256 (MessageDigest Javadoc) — unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
