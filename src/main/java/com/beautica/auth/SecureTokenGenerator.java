package com.beautica.auth;

import com.beautica.config.OtpPepperConfig;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Component
public class SecureTokenGenerator implements TokenGenerator {

    private static final int TOKEN_BYTES = 32;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
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

    // Mac is stateful and not thread-safe; one initialised instance per thread.
    private final ThreadLocal<Mac> otpMac;

    private final SecureRandom secureRandom = new SecureRandom();

    public SecureTokenGenerator(OtpPepperConfig otpPepperConfig) {
        var keySpec = new SecretKeySpec(
                otpPepperConfig.otpPepper().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        this.otpMac = ThreadLocal.withInitial(() -> {
            try {
                Mac mac = Mac.getInstance(HMAC_ALGORITHM);
                mac.init(keySpec);
                return mac;
            } catch (NoSuchAlgorithmException | InvalidKeyException e) {
                throw new IllegalStateException("HmacSHA256 not available", e);
            }
        });
    }

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

    @Override
    public String hashOtp(String rawOtp) {
        Mac mac = otpMac.get();
        mac.reset();
        byte[] digest = mac.doFinal(rawOtp.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }
}
