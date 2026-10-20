package com.beautica.config;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Shared secret the website's SSR Worker (website phase 008) sends on the public booking-page info reads
 * ({@code GET /api/v1/book/{slug}/info}, {@code GET /api/v1/book/s/{slug}/info}).
 *
 * <p>Binding prefix {@code app.booking-info}; {@code worker-key} comes from the optional env var
 * {@code BOOKING_INFO_WORKER_KEY}. The Worker calls from ONE egress IP, so the per-IP info bucket would
 * put every website visitor into a single 60/min budget. When a request carries
 * {@code X-Beautica-Worker-Key} equal to this value (constant-time compare), {@code AuthRateLimitFilter}
 * keys the info bucket on the visitor IP the Worker forwards in {@code CF-Connecting-IP} instead.
 *
 * <p>Blank (the default) turns the feature off: the header is ignored and every caller is keyed exactly as
 * before. The value is a secret — never log it.
 *
 * <p>Validated at boot: a non-blank key shorter than {@value #MIN_WORKER_KEY_LENGTH} characters (ignoring
 * surrounding whitespace) fails startup. The check is an {@link AssertTrue} on a derived boolean, never a
 * constraint on the field itself, so the bind-failure report names {@code workerKeyLongEnough=false} and
 * can never echo the secret. Generate it with {@code openssl rand -base64 32}.
 */
@Validated
@ConfigurationProperties(prefix = "app.booking-info")
public class BookingInfoWorkerProperties {

    static final int MIN_WORKER_KEY_LENGTH = 32;

    private String workerKey = "";

    public String getWorkerKey() {
        return workerKey;
    }

    public void setWorkerKey(String workerKey) {
        this.workerKey = workerKey;
    }

    /**
     * Boot guard: blank (feature off) or at least {@value #MIN_WORKER_KEY_LENGTH} characters. A short shared
     * secret is brute-forceable against the public info reads.
     */
    @AssertTrue(message = "app.booking-info.worker-key (BOOKING_INFO_WORKER_KEY) must be blank or at least "
            + MIN_WORKER_KEY_LENGTH + " characters; generate it with: openssl rand -base64 32")
    public boolean isWorkerKeyLongEnough() {
        return !isEnabled() || workerKey.strip().length() >= MIN_WORKER_KEY_LENGTH;
    }

    /** {@code true} when a non-blank worker key is configured. */
    public boolean isEnabled() {
        return workerKey != null && !workerKey.isBlank();
    }
}
