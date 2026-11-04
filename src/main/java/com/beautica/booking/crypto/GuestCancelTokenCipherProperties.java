package com.beautica.booking.crypto;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

/**
 * Phase 435 — the dedicated AES-256 key that seals the guest cancel token at rest
 * ({@code bookings/appointments.cancel_token_sealed}), bound from {@code app.guest-cancel-token.key}
 * ({@code GUEST_CANCEL_TOKEN_KEY}). Separate from {@code OUTBOX_PAYLOAD_KEY} so either key can be rotated, or
 * leak, without touching the other's data (boot fails when the two are equal — {@link GuestCancelTokenKeyGuard}).
 * Length is validated when {@link GuestCancelTokenCipher} is built (boot fails fast); Bean Validation here only
 * requires the value.
 *
 * <p>Phase 435 audit-fix — {@code keyPrevious} ({@code app.guest-cancel-token.key-previous},
 * {@code GUEST_CANCEL_TOKEN_KEY_PREVIOUS}) is optional and DECRYPT-ONLY: during a rotation it keeps the values
 * sealed under the old key openable while every new value is sealed under {@code key}.
 */
@ConfigurationProperties(prefix = "app.guest-cancel-token")
@Validated
public record GuestCancelTokenCipherProperties(

        @NotBlank(message = "app.guest-cancel-token.key must be provided (Base64-encoded 32-byte key)")
        String key,

        String keyPrevious
) {
    @ConstructorBinding
    public GuestCancelTokenCipherProperties {
    }

    /** No previous key — the steady state outside a rotation. */
    public GuestCancelTokenCipherProperties(String key) {
        this(key, null);
    }

    /** Never print either key (actuator configprops, debug logs, heap dumps). */
    @Override
    public String toString() {
        return "GuestCancelTokenCipherProperties{key=[REDACTED], keyPrevious="
                + (keyPrevious == null || keyPrevious.isBlank() ? "[NONE]" : "[REDACTED]") + "}";
    }
}
