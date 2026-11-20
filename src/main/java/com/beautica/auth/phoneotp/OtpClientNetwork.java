package com.beautica.auth.phoneotp;

import com.beautica.auth.filter.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Phone-first hardening audit-fix (AUTH-D26 c, user decision 2026-11-19) — the client network a phone-code request
 * comes from, the second half of the per-(phone, network) failed-verify key: {@link ClientIpResolver#prefixKey} of the
 * resolved client IP (an IPv6 /64, an IPv4 address — AUTH-D21 / AUTH-D27 e). Outside a bound HTTP request (a
 * background send, a direct service call) it is {@value #UNKNOWN}. Never logged (it is the caller's address).
 */
@Component
public class OtpClientNetwork {

    /** The key when no request is bound; admitted by {@code ck_phone_otp_failures_client_net} (V258). */
    public static final String UNKNOWN = "unknown";
    /** {@code phone_otp_failures.client_net} length; a longer value (never a valid key) collapses to UNKNOWN. */
    private static final int MAX_LENGTH = 64;

    private final ClientIpResolver clientIpResolver;

    public OtpClientNetwork(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    /** The network key of the request bound to this thread, or {@value #UNKNOWN}. */
    public String current() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            return UNKNOWN;
        }
        HttpServletRequest request = attributes.getRequest();
        String key = ClientIpResolver.prefixKey(clientIpResolver.resolve(request));
        return key == null || key.isBlank() || key.length() > MAX_LENGTH || ClientIpResolver.parseIpLiteral(
                key.endsWith("/64") ? key.substring(0, key.length() - 3) : key) == null ? UNKNOWN : key;
    }
}
