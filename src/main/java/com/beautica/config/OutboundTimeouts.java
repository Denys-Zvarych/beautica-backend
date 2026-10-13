package com.beautica.config;

import java.time.Duration;

/**
 * Shared startup guard for outbound-client timeouts (R2, Firebase, Turbosms). A {@code 0} or
 * negative value means "no timeout" in the underlying HTTP stacks, which would silently
 * re-introduce the unbounded waits phase 364 closes — so it is rejected at startup.
 */
public final class OutboundTimeouts {

    private OutboundTimeouts() {
    }

    /**
     * @param property the config key, named in the failure message
     * @return {@code value}, guaranteed non-null and strictly positive
     * @throws IllegalStateException when {@code value} is null, zero or negative
     */
    public static Duration requirePositive(String property, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(
                    property + " must be a positive duration (zero or negative means an infinite timeout)");
        }
        return value;
    }

    /** {@link #requirePositive} then converts to whole milliseconds, failing on int overflow. */
    public static int requirePositiveMillis(String property, Duration value) {
        return Math.toIntExact(requirePositive(property, value).toMillis());
    }
}
