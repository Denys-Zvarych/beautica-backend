package com.beautica.review.invite.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;

/**
 * Phase 400 D4 — {@code review_invites.token_hash}: the raw 32-byte SHA-256 of the base64url invite token's UTF-8
 * bytes (V213 {@code chk_review_invites_token_hash_len}). The send job hashes at issue; phase 401 hashes the path
 * token the same way for the lookup. Unkeyed on purpose: the token is 128 random bits, so the digest is not
 * brute-forceable (contrast {@code PhoneHasher}, whose input space is tiny). Same shape as {@code CancelTokenHasher}.
 */
public final class ReviewInviteTokenHasher {

    private static final String ALGORITHM = "SHA-256";

    private ReviewInviteTokenHasher() {
    }

    public static byte[] hash(String rawToken) {
        Objects.requireNonNull(rawToken, "rawToken");
        try {
            return MessageDigest.getInstance(ALGORITHM).digest(rawToken.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // Every Java SE platform must provide SHA-256 (MessageDigest Javadoc) — unreachable.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
