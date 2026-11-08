package com.beautica.common.observability;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Phase 441 audit-fix — the per-(service, reason) Sentry budget for {@link ExternalCallFailure} events, applied by
 * {@link ExternalCallSentryThrottle}. Only what reaches Sentry is limited; every failure still logs its ERROR line.
 *
 * @param maxEventsPerWindow events forwarded per key per window; the rest are dropped (default 5)
 * @param window             base window length (default 10 minutes); a key whose window dropped events gets the next
 *                           window doubled, back to this base after a window without drops
 * @param maxKeys            distinct (service, reason) keys tracked at once, least recently used evicted (default 256)
 * @param maxWindow          cap of the doubled window (default 24 hours); must be at least {@code window}
 */
@Validated
@ConfigurationProperties(prefix = "app.observability.external-call-sentry")
public record ExternalCallSentryLimitProperties(
        @DefaultValue("5") @Min(1) @Max(1000) int maxEventsPerWindow,
        @DefaultValue("10m") @NotNull @DurationMin(seconds = 1) Duration window,
        @DefaultValue("256") @Min(1) @Max(10_000) int maxKeys,
        @DefaultValue("24h") @NotNull @DurationMin(seconds = 1) Duration maxWindow) {

    /** Bean Validation hook: the backoff cap may not be shorter than the base window. */
    @AssertTrue(message = "max-window must be at least window")
    public boolean isMaxWindowAtLeastWindow() {
        return window == null || maxWindow == null || maxWindow.compareTo(window) >= 0;
    }
}
