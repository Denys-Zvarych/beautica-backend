package com.beautica.catalog.icon;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * B539 — the canonical bytes of an accepted category icon and their lowercase hex sha256 (the content address).
 *
 * @param bytes  canonical UTF-8 SVG; the array is owned by this value — callers never mutate it
 * @param sha256 64 lowercase hex characters
 */
public record SanitizedIcon(byte[] bytes, String sha256) {

    static SanitizedIcon of(byte[] bytes) {
        return new SanitizedIcon(bytes, sha256Hex(bytes));
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
}
