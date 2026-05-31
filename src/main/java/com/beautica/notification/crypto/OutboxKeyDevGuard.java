package com.beautica.notification.crypto;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Startup guard that prevents the development AES key from reaching a non-local, non-test
 * environment. Active on all profiles <em>except</em> {@code local} and {@code test}.
 *
 * <h2>How it works</h2>
 * <p>The dev key shipped in {@code .env.local} and committed to VCS is:
 * {@code xqXxTzEL0+Xlq/iNHyBgKG/nRxIiKedVmGonbisH2KM=}.
 * Its SHA-256 fingerprint (of the decoded 32 bytes) is embedded here as the constant
 * {@link #DEV_KEY_SHA256_HEX}. On startup this component computes the same fingerprint
 * for the configured {@code OUTBOX_PAYLOAD_KEY} and fails fast with
 * {@link IllegalStateException} if they match, blocking deployment with the dev default.
 * </p>
 *
 * <h2>Security note</h2>
 * <p>Only the SHA-256 hash of the dev key is stored here, not the key itself.
 * The raw key value never appears in this file or in any log statement.</p>
 */
@Slf4j
@Component
@Profile("!local & !test")
@RequiredArgsConstructor
public class OutboxKeyDevGuard {

    /**
     * SHA-256 of the decoded bytes of the dev key
     * {@code xqXxTzEL0+Xlq/iNHyBgKG/nRxIiKedVmGonbisH2KM=}.
     *
     * <p>Computed via:
     * <pre>python3 -c "import hashlib,base64; print(hashlib.sha256(base64.b64decode('xqXxTzEL0+Xlq/iNHyBgKG/nRxIiKedVmGonbisH2KM=')).hexdigest())"</pre>
     * Never update this constant with an actual key value — only its hash belongs here.
     * </p>
     */
    static final String DEV_KEY_SHA256_HEX =
            "09e2ddf7173b6717099cbc4586949b61846c8b6a03608ec2a6655e31d2e9670d";

    private final OutboxCipherProperties properties;

    @PostConstruct
    void assertNotDevKey() {
        String b64Key = properties.payloadKey();
        assertNotDevKey(b64Key);
    }

    /**
     * Package-private static helper so unit tests can exercise the guard logic directly
     * without constructing a Spring context or relying on profile wiring.
     *
     * @param b64Key the Base64-encoded AES key value to validate
     * @throws IllegalStateException if the key matches the known dev default
     */
    static void assertNotDevKey(String b64Key) {
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(b64Key);
        } catch (IllegalArgumentException e) {
            // Key is malformed Base64 — OutboxPayloadCipher's constructor will reject it
            // with a more specific message. Nothing to do here.
            return;
        }

        String actualHex = sha256Hex(keyBytes);
        if (DEV_KEY_SHA256_HEX.equals(actualHex)) {
            throw new IllegalStateException(
                    "OUTBOX_PAYLOAD_KEY is the development default and must not be used outside " +
                    "the local/test profiles. Generate a unique key: openssl rand -base64 32");
        }
    }

    private static String sha256Hex(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM spec (java.security.MessageDigest) and is
            // always present. This branch is unreachable in any supported JVM.
            throw new IllegalStateException("SHA-256 algorithm unavailable — JVM is non-compliant", e);
        }
    }
}
