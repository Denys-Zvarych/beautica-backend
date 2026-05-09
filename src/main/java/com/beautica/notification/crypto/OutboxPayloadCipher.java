package com.beautica.notification.crypto;

import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Symmetric AES-256-GCM cipher for sealing short, sensitive payload fields written
 * into the {@code notification_outbox} table (e.g. invite URLs).
 *
 * <h2>Threat model</h2>
 * <p>Provides <strong>at-rest protection only</strong>. A reader of a database backup or
 * a SQL-injected {@code SELECT * FROM notification_outbox} cannot recover the plaintext
 * without also obtaining {@code OUTBOX_PAYLOAD_KEY}. This cipher does <strong>not</strong>
 * change runtime heap exposure: plaintext is materialised in JVM memory immediately
 * before {@link #seal(String)} and immediately after {@link #open(String)}.</p>
 *
 * <h2>Format</h2>
 * <p>Sealed strings have the form:</p>
 * <pre>v1:&lt;base64(IV ‖ ciphertext ‖ tag)&gt;</pre>
 * <p>where IV is 12 bytes generated fresh per call from {@link SecureRandom}, and the
 * GCM authentication tag is 128 bits (16 bytes). The {@code v1:} prefix exists so
 * future key-rotation versions can branch on prefix; {@link #open(String)} rejects
 * any other prefix.</p>
 *
 * <h2>Key</h2>
 * <p>Key material is sourced from {@link OutboxCipherProperties#payloadKey()} as a
 * Base64-encoded 32-byte (256-bit) value. The constructor validates length and fails
 * fast with {@link IllegalStateException} so a misconfigured key blocks application
 * startup rather than blowing up on first use.</p>
 *
 * <h2>Errors</h2>
 * <ul>
 *   <li>{@link #seal(String)} throws {@link IllegalStateException} if encryption fails;
 *       the cause is wrapped but never echoed in the message.</li>
 *   <li>{@link #open(String)} throws {@link IllegalStateException} on any of: unknown
 *       version prefix, malformed Base64, truncated payload, GCM tag mismatch
 *       (tampering or corruption). Constant-time tag verification is performed by
 *       {@link Cipher} itself — no manual comparison is done in this class.</li>
 * </ul>
 *
 * <p>This component is thread-safe at the public API boundary because each call
 * obtains its own {@link Cipher} instance via {@link Cipher#getInstance(String)}; the
 * {@link SecretKey} field is immutable.</p>
 */
@Component
public class OutboxPayloadCipher {

    private static final String CIPHER_TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String KEY_ALGORITHM = "AES";
    private static final int IV_LENGTH_BYTES = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final int TAG_LENGTH_BYTES = TAG_LENGTH_BITS / 8;
    private static final int KEY_LENGTH_BYTES = 32;
    private static final String VERSION_PREFIX = "v1:";

    private final SecretKey secretKey;
    private final SecureRandom secureRandom;

    public OutboxPayloadCipher(OutboxCipherProperties properties) {
        if (properties == null || properties.payloadKey() == null || properties.payloadKey().isBlank()) {
            throw new IllegalStateException(
                    "app.outbox.payload-key must be provided — refusing to start without an outbox payload key");
        }
        byte[] keyBytes;
        try {
            keyBytes = Base64.getDecoder().decode(properties.payloadKey());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "app.outbox.payload-key must be valid Base64 — refusing to start with a malformed key");
        }
        if (keyBytes.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "app.outbox.payload-key must decode to exactly " + KEY_LENGTH_BYTES
                            + " bytes (AES-256) — got " + keyBytes.length + " bytes");
        }
        this.secretKey = new SecretKeySpec(keyBytes, KEY_ALGORITHM);
        this.secureRandom = new SecureRandom();
    }

    /**
     * Encrypt {@code plaintext} (UTF-8) and return a versioned, Base64-encoded sealed
     * string of the form {@code v1:<base64(IV ‖ ciphertext ‖ tag)>}.
     *
     * @param plaintext the value to encrypt; must not be {@code null}. May be empty.
     * @return the sealed value, safe to persist in {@code notification_outbox.payload}.
     * @throws IllegalStateException if the underlying cipher fails for any reason. The
     *     plaintext is never included in the exception message.
     */
    public String seal(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext must not be null");
        }
        byte[] iv = new byte[IV_LENGTH_BYTES];
        secureRandom.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertextWithTag = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertextWithTag.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertextWithTag, 0, combined, iv.length, ciphertextWithTag.length);

            return VERSION_PREFIX + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("OutboxPayloadCipher seal failed", e);
        }
    }

    /**
     * Decrypt a sealed string previously produced by {@link #seal(String)}.
     *
     * <p>The input must start with {@code v1:}. Any other prefix, malformed Base64,
     * truncated payload, or modified bytes (which fail GCM tag verification) cause
     * {@link IllegalStateException}.</p>
     *
     * @param sealed the sealed value (versioned + Base64).
     * @return the original UTF-8 plaintext.
     * @throws IllegalStateException if the input is unrecognised or has been tampered
     *     with. The message intentionally does not distinguish "tampered" from
     *     "corrupted" to avoid an oracle.
     */
    public String open(String sealed) {
        if (sealed == null || !sealed.startsWith(VERSION_PREFIX)) {
            throw new IllegalStateException(
                    "OutboxPayloadCipher open failed — unrecognised version prefix");
        }
        byte[] combined;
        try {
            combined = Base64.getDecoder().decode(sealed.substring(VERSION_PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "OutboxPayloadCipher open failed — corrupt or tampered ciphertext", e);
        }
        if (combined.length < IV_LENGTH_BYTES + TAG_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "OutboxPayloadCipher open failed — corrupt or tampered ciphertext");
        }
        byte[] iv = new byte[IV_LENGTH_BYTES];
        System.arraycopy(combined, 0, iv, 0, IV_LENGTH_BYTES);
        byte[] ciphertextWithTag = new byte[combined.length - IV_LENGTH_BYTES];
        System.arraycopy(combined, IV_LENGTH_BYTES, ciphertextWithTag, 0, ciphertextWithTag.length);
        try {
            Cipher cipher = Cipher.getInstance(CIPHER_TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintextBytes = cipher.doFinal(ciphertextWithTag);
            return new String(plaintextBytes, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "OutboxPayloadCipher open failed — corrupt or tampered ciphertext", e);
        }
    }
}
