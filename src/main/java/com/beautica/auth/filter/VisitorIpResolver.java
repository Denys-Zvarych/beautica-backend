package com.beautica.auth.filter;

import com.beautica.common.security.ConstantTimeSecrets;
import com.beautica.config.BookingInfoWorkerProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/**
 * The visitor IP to hand to third parties on behalf of a request (phase 382 audit): today the Turnstile
 * {@code remoteip}.
 *
 * <p>Same trust rule as the guest-info rate limit ({@code AuthRateLimitFilter#resolveGuestInfoBucketKey}):
 * when the website Worker feature is on and the request carries {@code X-Beautica-Worker-Key} equal to
 * {@code BOOKING_INFO_WORKER_KEY} (constant-time compare), the Worker's egress IP is not the visitor, so the
 * IP literal it forwards in {@code CF-Connecting-IP} is used. Anything else — feature off, header absent,
 * wrong key, missing or non-IP-literal {@code CF-Connecting-IP} — falls back to {@link ClientIpResolver}
 * exactly as before. The key is a secret and is never logged.
 */
@Component
public class VisitorIpResolver {

    private static final String WORKER_KEY_HEADER = "X-Beautica-Worker-Key";
    private static final String CF_CONNECTING_IP_HEADER = "CF-Connecting-IP";

    private final String workerKey;

    public VisitorIpResolver(BookingInfoWorkerProperties properties) {
        this.workerKey = properties.isEnabled() ? properties.getWorkerKey() : null;
    }

    /** @return the trusted forwarded visitor IP, else the rate-limit filter's client IP */
    public String resolve(HttpServletRequest request) {
        if (workerKey != null) {
            String supplied = request.getHeader(WORKER_KEY_HEADER);
            if (supplied != null && ConstantTimeSecrets.matches(supplied, workerKey)) {
                String forwarded = request.getHeader(CF_CONNECTING_IP_HEADER);
                if (AuthRateLimitFilter.parseIpLiteral(forwarded) != null) {
                    return forwarded.trim();
                }
            }
        }
        return ClientIpResolver.resolve(request);
    }
}
