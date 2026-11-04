package com.beautica.booking.crypto;

import com.beautica.notification.crypto.OutboxCipherProperties;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.Base64;

/**
 * Phase 435 audit-fix — refuses to boot (every profile) when {@code GUEST_CANCEL_TOKEN_KEY}, or its decrypt-only
 * {@code GUEST_CANCEL_TOKEN_KEY_PREVIOUS}, is the same key as {@code OUTBOX_PAYLOAD_KEY}. The two secrets must stay
 * independent: one leaking or being rotated must never expose or break the other's data.
 *
 * <p>Compares the DECODED bytes (so two Base64 spellings of one key are still caught) with
 * {@link MessageDigest#isEqual}, which is constant-time. A value that does not decode is skipped here — the cipher
 * that owns it rejects it with its own message. No key value is ever logged or put in a message.
 */
@Component
@RequiredArgsConstructor
public class GuestCancelTokenKeyGuard {

    private final GuestCancelTokenCipherProperties guestKeys;
    private final OutboxCipherProperties outboxKeys;

    @PostConstruct
    void assertIndependentOfOutboxKey() {
        assertIndependent(guestKeys.key(), guestKeys.keyPrevious(), outboxKeys.payloadKey());
    }

    /** Package-private so the rule is unit-testable without a Spring context. */
    static void assertIndependent(String guestKey, String guestPreviousKey, String outboxKey) {
        byte[] outbox = decodeOrNull(outboxKey);
        if (outbox == null) {
            return;
        }
        if (sameKey(guestKey, outbox)) {
            throw new IllegalStateException(
                    "GUEST_CANCEL_TOKEN_KEY must not equal OUTBOX_PAYLOAD_KEY — generate a dedicated key: "
                            + "openssl rand -base64 32");
        }
        if (sameKey(guestPreviousKey, outbox)) {
            throw new IllegalStateException(
                    "GUEST_CANCEL_TOKEN_KEY_PREVIOUS must not equal OUTBOX_PAYLOAD_KEY");
        }
    }

    private static boolean sameKey(String b64Key, byte[] other) {
        byte[] key = decodeOrNull(b64Key);
        return key != null && MessageDigest.isEqual(key, other);
    }

    private static byte[] decodeOrNull(String b64Key) {
        if (b64Key == null || b64Key.isBlank()) {
            return null;
        }
        try {
            return Base64.getDecoder().decode(b64Key);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
