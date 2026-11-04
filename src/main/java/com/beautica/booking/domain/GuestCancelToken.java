package com.beautica.booking.domain;

import java.security.SecureRandom;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Phase 435 follow-up — the guest's raw one-time cancel token (a capability: whoever holds it can open and cancel the
 * booking). Two accepted spellings:
 * <ul>
 *   <li><b>short</b> (issued since this phase): exactly {@value #SHORT_LENGTH} base62 characters
 *       ({@code [0-9A-Za-z]}), drawn from {@link SecureRandom} — 16 × log2(62) ≈ 95.3 bits of entropy. Case-sensitive;</li>
 *   <li><b>legacy UUID</b> (every token issued before): kept working forever. Parsed exactly as Spring's
 *       {@code UUID} path variable did ({@code UUID.fromString(trim)}), and held in canonical lower-case form, so the
 *       hash preimage ({@link CancelTokenHasher}) and every existing link are unchanged.</li>
 * </ul>
 * The two shapes cannot collide: a short token never contains {@code '-'}.
 *
 * <p>{@link #valueOf(String)} is what Spring's {@code ObjectToObjectConverter} calls for a {@code @PathVariable
 * GuestCancelToken}; a malformed value throws {@link IllegalArgumentException} → {@code MethodArgumentTypeMismatch}
 * → the same 400 a malformed UUID got before.
 *
 * <p>{@link #toString()} is redacted — {@link #value()} is the only way to the raw token, and it is never logged.
 */
public record GuestCancelToken(String value) {

    public static final int SHORT_LENGTH = 16;

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Pattern SHORT = Pattern.compile("^[0-9A-Za-z]{" + SHORT_LENGTH + "}$");
    /** Longest legacy spelling {@code UUID.fromString} accepts. */
    private static final int MAX_UUID_LENGTH = 36;
    /** Thread-safe; one instance for the JVM. */
    private static final SecureRandom RANDOM = new SecureRandom();

    public GuestCancelToken {
        Objects.requireNonNull(value, "value");
    }

    /** A fresh short token: {@value #SHORT_LENGTH} uniformly random base62 characters. */
    public static GuestCancelToken generate() {
        char[] chars = new char[SHORT_LENGTH];
        for (int i = 0; i < SHORT_LENGTH; i++) {
            chars[i] = ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length()));
        }
        return new GuestCancelToken(new String(chars));
    }

    /** A legacy UUID token, in its canonical form. */
    public static GuestCancelToken of(UUID legacy) {
        return new GuestCancelToken(Objects.requireNonNull(legacy, "legacy").toString());
    }

    /**
     * Parses a token as received (path variable, request body, opened sealed copy).
     *
     * @throws IllegalArgumentException when it is neither a short token nor a UUID (the message never echoes it)
     */
    public static GuestCancelToken valueOf(String raw) {
        return parse(raw).orElseThrow(() -> new IllegalArgumentException("Malformed cancel token"));
    }

    /** {@link #valueOf} without the exception. */
    public static Optional<GuestCancelToken> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String trimmed = raw.trim();
        if (SHORT.matcher(trimmed).matches()) {
            return Optional.of(new GuestCancelToken(trimmed));
        }
        if (trimmed.isEmpty() || trimmed.length() > MAX_UUID_LENGTH || trimmed.indexOf('-') < 0) {
            return Optional.empty();
        }
        try {
            return Optional.of(of(UUID.fromString(trimmed)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** {@code true} for a token issued before this phase (UUID shape). */
    public boolean isLegacy() {
        return !SHORT.matcher(value).matches();
    }

    @Override
    public String toString() {
        return "GuestCancelToken[<redacted>]";
    }
}
