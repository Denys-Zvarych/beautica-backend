package com.beautica.notification.mail;

import com.beautica.config.OutboundTimeouts;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * {@code app.mail.brevo-api.*} (Phase 374, D3). The key is {@code BREVO_API_KEY}; it is deliberately not
 * {@code @NotBlank} here — the fingerprint guard owns the prod fatality (D8), so every other profile boots
 * without it. {@link #toString()} masks the key.
 *
 * @param baseUrl        Brevo API origin; overridable only so tests can point at WireMock
 * @param apiKey         {@code xkeysib-…} transactional key, sent per request in the {@code api-key} header
 * @param connectTimeout TCP connect timeout, validated positive
 * @param readTimeout    response timeout, validated positive
 * @param senderName     display name used when the MIME {@code From} carries none (Brevo caps names at 70)
 */
@Validated
@ConfigurationProperties(prefix = "app.mail.brevo-api")
public record BrevoApiProperties(
        @NotBlank @DefaultValue("https://api.brevo.com") String baseUrl,
        @DefaultValue("") String apiKey,
        @DefaultValue("3s") Duration connectTimeout,
        @DefaultValue("10s") Duration readTimeout,
        @NotBlank @Size(max = 70) @DefaultValue("Beautica") String senderName
) {

    public BrevoApiProperties {
        OutboundTimeouts.requirePositive("app.mail.brevo-api.connect-timeout", connectTimeout);
        OutboundTimeouts.requirePositive("app.mail.brevo-api.read-timeout", readTimeout);
    }

    /** @return {@code true} when a key is configured (blank means the transport cannot authenticate) */
    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Never prints the key (a record's generated toString would). */
    @Override
    public String toString() {
        return "BrevoApiProperties[baseUrl=" + baseUrl + ", apiKey=" + (hasApiKey() ? "***" : "<blank>")
                + ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout
                + ", senderName=" + senderName + "]";
    }
}
