package com.beautica.notification.crypto;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for the outbox payload cipher.
 *
 * <p>Bound from the {@code app.outbox} prefix. The {@code payloadKey} field carries a
 * Base64-encoded 32-byte symmetric key used by {@link OutboxPayloadCipher} to seal /
 * open sensitive notification payload fields with AES-256-GCM.</p>
 *
 * <p>Length validation of the decoded key bytes happens at cipher construction time
 * (fail-fast on application startup) — Bean Validation here only ensures the property
 * was provided at all.</p>
 */
@ConfigurationProperties(prefix = "app.outbox")
@Validated
public record OutboxCipherProperties(

        @NotBlank(message = "app.outbox.payload-key must be provided (Base64-encoded 32-byte key)")
        String payloadKey
) {
}
