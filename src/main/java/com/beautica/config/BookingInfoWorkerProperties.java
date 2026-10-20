package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 */
@ConfigurationProperties(prefix = "app.booking-info")
public class BookingInfoWorkerProperties {

    private String workerKey = "";

    public String getWorkerKey() {
        return workerKey;
    }

    public void setWorkerKey(String workerKey) {
        this.workerKey = workerKey;
    }

    /** {@code true} when a non-blank worker key is configured. */
    public boolean isEnabled() {
        return workerKey != null && !workerKey.isBlank();
    }
}
