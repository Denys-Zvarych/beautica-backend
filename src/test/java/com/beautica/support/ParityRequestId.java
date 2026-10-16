package com.beautica.support;

import org.springframework.http.HttpHeaders;

/**
 * Pins the per-request {@code requestId} (Phase 371, D5) so two error responses compared for
 * anti-oracle parity can stay byte-for-byte identical.
 *
 * <p>Every error {@code ApiResponse} carries the request's {@code X-Request-Id}, or a fresh UUID when
 * the header is absent or malformed. Sending the SAME valid id (matches {@code ^[A-Za-z0-9._-]{8,64}$})
 * on both compared requests removes that one legitimate per-request difference while the full body is
 * still compared, which also proves {@code requestId} is the ONLY thing that varies per request.
 */
public final class ParityRequestId {

    /** Valid per the filter's {@code ^[A-Za-z0-9._-]{8,64}$} rule. */
    public static final String VALUE = "parity-req-0001";

    private ParityRequestId() {
    }

    /** Adds the pinned {@code X-Request-Id} to {@code headers} and returns the same instance. */
    public static HttpHeaders on(HttpHeaders headers) {
        headers.set("X-Request-Id", VALUE);
        return headers;
    }
}
