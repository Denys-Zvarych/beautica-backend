package com.beautica.booking.crypto;

import com.beautica.booking.domain.GuestCancelToken;
import com.beautica.notification.crypto.OutboxPayloadCipher;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Phase 435 — reversible at-rest copy of the guest's one-time cancel token, so every later guest SMS
 * (provider cancel, reschedule, reminders) can carry the guest's own booking page ({@code /c/{token}}). The sealed plaintext is the raw token as issued: 16
 * base62 chars (phase 435 follow-up) or a legacy canonical UUID.
 *
 * <p>The SHA-256 hash ({@code CancelTokenHasher}) stays the ONLY lookup key; the sealed copy is never queried.
 * Sealing reuses {@link OutboxPayloadCipher}'s AES-256-GCM (fresh 12-byte IV per call, 128-bit tag) over the dedicated
 * {@code app.guest-cancel-token.key}. A bad key fails boot.
 *
 * <p><b>Format (audit-fix).</b> {@code v2:<kid>:<base64(IV ‖ ciphertext ‖ tag)>}:
 * <ul>
 *   <li>{@code kid} — 8 hex chars, a domain-separated SHA-256 fingerprint of the sealing key, so {@link #open} picks
 *       the right key during a rotation ({@code app.guest-cancel-token.key-previous} is decrypt-only);</li>
 *   <li>the AES-GCM associated data is {@code "<table>:<row id>"} ({@link Row}), so a sealed value copied onto ANOTHER
 *       row does not open there.</li>
 * </ul>
 * Only {@code v2:} is accepted: the unbound {@code v1:} form (no kid, no AAD) was never written by any deployed build
 * (V230 shipped with v2 only), so {@link #open} rejects it like any other unknown version.
 *
 * <p>Neither the token nor the sealed value is ever logged; exceptions never carry either.
 */
@Component
public class GuestCancelTokenCipher {

    static final String KEY_PROPERTY = "app.guest-cancel-token.key";
    static final String PREVIOUS_KEY_PROPERTY = "app.guest-cancel-token.key-previous";

    private static final String V2_PREFIX = "v2:";
    private static final int KID_HEX_LENGTH = 8;
    private static final byte[] KID_DOMAIN = "beautica/guest-cancel-token/kid\0".getBytes(StandardCharsets.US_ASCII);
    private static final String OPEN_FAILED = "GuestCancelTokenCipher open failed";

    private final OutboxPayloadCipher current;
    private final String currentKid;
    /** Decrypt-only, {@code null} outside a rotation. */
    private final OutboxPayloadCipher previous;
    private final String previousKid;

    public GuestCancelTokenCipher(GuestCancelTokenCipherProperties properties) {
        String key = properties == null ? null : properties.key();
        this.current = OutboxPayloadCipher.withKey(key, KEY_PROPERTY, true);
        this.currentKid = keyId(key);
        String previousKey = properties == null ? null : properties.keyPrevious();
        if (previousKey == null || previousKey.isBlank()) {
            this.previous = null;
            this.previousKid = null;
        } else {
            this.previous = OutboxPayloadCipher.withKey(previousKey, PREVIOUS_KEY_PROPERTY, true);
            this.previousKid = keyId(previousKey);
            if (previousKid.equals(currentKid)) {
                throw new IllegalStateException(PREVIOUS_KEY_PROPERTY + " must differ from " + KEY_PROPERTY
                        + " — unset it once the rotation is finished");
            }
        }
    }

    /**
     * Seals {@code token} bound to {@code row} under the current key; a fresh IV per call, so sealing the same token
     * twice yields different values.
     */
    public String seal(GuestCancelToken token, Row row) {
        if (token == null) {
            throw new IllegalStateException("cancel token must not be null");
        }
        requireRow(row);
        return V2_PREFIX + currentKid + ":" + current.sealBound(token.value(), row.associatedData());
    }

    /** {@link #seal(GuestCancelToken, Row)} for a legacy UUID token (its canonical string is sealed). */
    public String seal(UUID token, Row row) {
        return seal(token == null ? null : GuestCancelToken.of(token), row);
    }

    /**
     * Opens a value produced by {@link #seal} for the SAME {@code row}.
     *
     * @throws IllegalStateException on a wrong/unknown key, tampering, a value moved from another row, an unknown
     *                               version (including the unbound {@code v1:}), or a plaintext that is neither a short token
     *                               nor a UUID ({@link GuestCancelToken#parse}) — the message never names the token or
     *                               the sealed value
     */
    public GuestCancelToken open(String sealed, Row row) {
        requireRow(row);
        if (sealed != null && sealed.startsWith(V2_PREFIX)) {
            return toToken(openV2(sealed.substring(V2_PREFIX.length()), row));
        }
        throw new IllegalStateException(OPEN_FAILED + " — unrecognised version prefix");
    }

    private String openV2(String rest, Row row) {
        int separator = rest.indexOf(':');
        if (separator != KID_HEX_LENGTH) {
            throw new IllegalStateException(OPEN_FAILED + " — malformed value");
        }
        String kid = rest.substring(0, separator);
        String body = rest.substring(separator + 1);
        if (kid.equals(currentKid)) {
            return current.openBound(body, row.associatedData());
        }
        if (previous != null && kid.equals(previousKid)) {
            return previous.openBound(body, row.associatedData());
        }
        throw new IllegalStateException(OPEN_FAILED + " — unknown key id");
    }

    private static GuestCancelToken toToken(String plaintext) {
        return GuestCancelToken.parse(plaintext)
                .orElseThrow(() -> new IllegalStateException(OPEN_FAILED + " — not a cancel token"));
    }

    private static void requireRow(Row row) {
        if (row == null) {
            throw new IllegalStateException("the owning row must be given");
        }
    }

    /** 8 hex chars of SHA-256(domain ‖ key bytes); never reveals the key. Called after the key was validated. */
    private static String keyId(String base64Key) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(KID_DOMAIN);
            byte[] hash = digest.digest(Base64.getDecoder().decode(base64Key));
            return HexFormat.of().formatHex(hash).substring(0, KID_HEX_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable — JVM is non-compliant", e);
        }
    }

    /**
     * The row a sealed value belongs to — its AES-GCM associated data is {@code "<table>:<id>"}. The id must already be
     * assigned (the caller seals after the entity is persisted).
     */
    public record Row(String table, UUID id) {

        private static final String BOOKINGS = "bookings";
        private static final String APPOINTMENTS = "appointments";

        public Row {
            if (id == null) {
                throw new IllegalStateException("the row id must be assigned before sealing or opening");
            }
        }

        public static Row booking(UUID id) {
            return new Row(BOOKINGS, id);
        }

        public static Row appointment(UUID id) {
            return new Row(APPOINTMENTS, id);
        }

        byte[] associatedData() {
            return (table + ":" + id).getBytes(StandardCharsets.UTF_8);
        }
    }
}
