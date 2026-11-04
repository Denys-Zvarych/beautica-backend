package com.beautica.booking.crypto;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Phase 435 — refuses to boot any non-{@code local}/{@code test} profile with the development
 * {@code GUEST_CANCEL_TOKEN_KEY} committed in {@code application-local.yml} (the {@code OutboxKeyDevGuard}
 * pattern) as either the current key or {@code key-previous} (a rotation must not keep opening values sealed under a
 * public key). Only the SHA-256 of the decoded dev key lives here; no key value is ever logged.
 */
@Component
@Profile("!local & !test")
@RequiredArgsConstructor
public class GuestCancelTokenKeyDevGuard {

    /** SHA-256 of the decoded bytes of the dev key in {@code application-local.yml}. */
    static final String DEV_KEY_SHA256_HEX =
            "1b888b41ad75e8b963b664b4a43adb230bc3db5d81d1b4b11678616de028a0b6";

    private final GuestCancelTokenCipherProperties properties;

    @PostConstruct
    void assertNotDevKey() {
        assertNotDevKey(properties.key(), "GUEST_CANCEL_TOKEN_KEY");
        String previous = properties.keyPrevious();
        if (previous != null && !previous.isBlank()) {
            assertNotDevKey(previous, "GUEST_CANCEL_TOKEN_KEY_PREVIOUS");
        }
    }

    /** Package-private so the rule is unit-testable without a Spring context. */
    static void assertNotDevKey(String b64Key) {
        assertNotDevKey(b64Key, "GUEST_CANCEL_TOKEN_KEY");
    }

    /** {@code name} is the env var reported in the boot failure; never the key itself. */
    static void assertNotDevKey(String b64Key, String name) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(b64Key);
        } catch (IllegalArgumentException e) {
            return; // GuestCancelTokenCipher rejects a malformed key with the specific message
        }
        if (DEV_KEY_SHA256_HEX.equals(sha256Hex(keyBytes))) {
            throw new IllegalStateException(
                    name + " is the development default and must not be used outside "
                            + "the local/test profiles. Generate a unique key: openssl rand -base64 32");
        }
    }

    private static String sha256Hex(byte[] input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable — JVM is non-compliant", e);
        }
    }
}
