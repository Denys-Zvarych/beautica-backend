package com.beautica.auth;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class SecureTokenGenerator implements TokenGenerator {

    private static final int TOKEN_BYTES = 32;
    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    // ThreadLocal avoids per-call MessageDigest.getInstance() while remaining thread-safe.
    // MessageDigest is stateful and not thread-safe, so one instance per thread is required.
    private static final ThreadLocal<MessageDigest> SHA256 = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    });

    private final SecureRandom secureRandom = new SecureRandom();

    @Override
    public String generateToken() {
        var bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return BASE64URL.encodeToString(bytes);
    }

    @Override
    public String generateOtp() {
        int code = secureRandom.nextInt(1_000_000); // 0..999999
        return String.format("%06d", code);
    }

    @Override
    public String hash(String rawToken) {
        MessageDigest md = SHA256.get();
        md.reset();
        byte[] digest = md.digest(rawToken.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
