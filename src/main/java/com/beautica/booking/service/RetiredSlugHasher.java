package com.beautica.booking.service;

import com.beautica.booking.dto.BookingLinkKind;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Phase 378 audit (security MEDIUM) — the ONE way a retired slug becomes its tombstone key
 * ({@code retired_booking_slugs.slug_hash}, V205): lower-hex SHA-256 of {@code kind.name() + ':' + slug}
 * in UTF-8. The tombstone outlives account erasure, so it never stores the name-derived slug itself; every
 * insert and every "is this slug retired?" probe goes through {@link #hash}. V205's SQL backfill computes
 * the identical preimage ({@code encode(sha256(convert_to(kind || ':' || slug, 'UTF8')), 'hex')}).
 *
 * <p>The kind is part of the preimage because master and salon slugs live in different URL namespaces
 * (D3): an equal slug of the other kind is a different tombstone. Phase 379's {@code 410 Gone} lookup of a
 * retired slug must hash through here too.
 *
 * <p><b>Not a secret.</b> The hash is unsalted and a slug is a guessable name plus 4 characters, so anyone
 * who can read the table can confirm a guessed slug by hashing it. It only keeps the plaintext slug out of
 * the row; never reuse this helper for sensitive data.
 */
public final class RetiredSlugHasher {

    private static final String ALGORITHM = "SHA-256";
    private static final char SEPARATOR = ':';
    private static final HexFormat HEX = HexFormat.of();

    private RetiredSlugHasher() {
    }

    /** The 64-char lower-hex tombstone key of {@code slug} in {@code kind}'s namespace. */
    public static String hash(BookingLinkKind kind, String slug) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(slug, "slug");
        byte[] preimage = (kind.name() + SEPARATOR + slug).getBytes(StandardCharsets.UTF_8);
        return HEX.formatHex(sha256().digest(preimage));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            // Every Java SE platform must provide SHA-256 (MessageDigest Javadoc) — unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
