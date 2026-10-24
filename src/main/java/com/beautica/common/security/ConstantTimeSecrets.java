package com.beautica.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Constant-time comparison of a caller-supplied shared secret (a header value) against the configured
 * one. Shared by {@code InternalApiKeyFilter} ({@code X-Internal-Key}) and {@code AuthRateLimitFilter}
 * ({@code X-Beautica-Worker-Key}) so both use the identical timing-safe check.
 */
public final class ConstantTimeSecrets {

    private ConstantTimeSecrets() {
    }

    /**
     * Both strings are hashed to equal-length SHA-256 digests before {@link MessageDigest#isEqual}, so
     * the comparison cannot leak the secret's length through a short-circuit return.
     */
    public static boolean matches(String supplied, String configured) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashA = digest.digest(supplied.getBytes(StandardCharsets.UTF_8));
            digest.reset();
            byte[] hashB = digest.digest(configured.getBytes(StandardCharsets.UTF_8));
            return MessageDigest.isEqual(hashA, hashB);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed present by the JVM spec; this branch is unreachable.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
