package com.beautica.auth.filter;

import com.beautica.config.ClientIpProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.regex.Pattern;

/**
 * The one client-IP resolution rule behind the per-IP rate-limit buckets, extracted from
 * {@link AuthRateLimitFilter} (phase 382 D5) so the login lockout key, the Turnstile {@code remoteip} fallback and
 * the Telegram webhook reject throttle see the same address the buckets charged — never a second resolver that could
 * drift from it. Holds the bound {@code app.rate-limit.trust-forwarded-headers} flag, so no caller can pick the rule.
 *
 * <p>Trust off (the {@code local} profile): the socket peer only — no proxy in front, so every header is
 * caller-typed.
 *
 * <p>Trust on: the rightmost non-empty {@code X-Forwarded-For} entry when it is a valid IP literal of at most 45
 * chars ({@link #parseIpLiteral}), else the socket peer. The header is trusted ONLY because
 * exactly one Railway edge-proxy hop sits in front of the app and appends the address it saw as the rightmost
 * entry, so the caller controls only entries to its left. {@code X-Real-IP} is never read: it may be passed through
 * from the caller at Railway's edge. A second proxy or CDN layer in front would make the rightmost entry that layer's
 * address. Exception: a request reaching the app over Railway private networking ({@code *.railway.internal})
 * bypasses the edge proxy, so the header is entirely caller-controlled there.
 *
 * <p>A header-derived address is returned in canonical form ({@link InetAddress#getHostAddress()}), so one address
 * maps to one bucket key whatever its notation.
 */
@Component
public class ClientIpResolver {

    private static final String FORWARDED_FOR_HEADER = "X-Forwarded-For";
    private static final int MAX_IP_LITERAL_LENGTH = 45;
    private static final Pattern IPV4_LITERAL = Pattern.compile(
            "^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$");
    // First char hex or ':' — InetAddress.getByName only takes its literal-parsing path for such a value
    // (a leading '.' would fall through to a DNS lookup); the ':' requirement below makes it IPv6-only.
    private static final Pattern IPV6_LITERAL_CHARS = Pattern.compile("^[0-9A-Fa-f:][0-9A-Fa-f:.]*$");
    // IPv6 clients are keyed on their /64: one subscriber is routinely handed a whole /64, so a per-/128
    // key would let a single host mint 2^64 fresh keys by rotating its interface identifier.
    private static final int IPV6_BUCKET_PREFIX_BYTES = 8;
    private static final String IPV6_BUCKET_SUFFIX = "/64";

    private final boolean trustForwardedHeaders;

    public ClientIpResolver(ClientIpProperties properties) {
        this.trustForwardedHeaders = properties.trustForwardedHeaders();
    }

    /** @return the client IP for the per-IP buckets under the bound trust rule */
    public String resolve(HttpServletRequest request) {
        if (!trustForwardedHeaders) {
            return request.getRemoteAddr();
        }
        InetAddress forwarded = parseIpLiteral(rightmostForwardedFor(request));
        return forwarded != null ? forwarded.getHostAddress() : request.getRemoteAddr();
    }

    /** The rightmost non-empty {@code X-Forwarded-For} entry, or {@code null} when there is none. */
    private static String rightmostForwardedFor(HttpServletRequest request) {
        // Every header line, not just the first: an edge that appends a separate line would otherwise leave a
        // caller-supplied first line in control of the key.
        Enumeration<String> lines = request.getHeaders(FORWARDED_FOR_HEADER);
        if (lines == null || !lines.hasMoreElements()) {
            return null;
        }
        String xfwd = String.join(",", Collections.list(lines));
        String[] parts = xfwd.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String part = parts[i].trim();
            if (!part.isEmpty()) {
                return part;
            }
        }
        return null;
    }

    /**
     * Phone-first hardening B10 (AUTH-D21, AUTH-D27 e) — the per-client key of a resolved IP: an IPv6 address keys on
     * its /64 ({@code 2001:db8:0:0:0:0:0:0/64}), IPv4 (and IPv4-mapped IPv6) on the address. A value that is not a
     * bare IP literal (already {@code …/64}, a clamped socket address) is returned as is. Used by the per-IP buckets
     * of the OTP, review-invite, Telegram-link and login routes and by the login lockout, so both see one key.
     */
    public static String prefixKey(String ip) {
        String prefixed = visitorIpBucketKey(ip);
        return prefixed != null ? prefixed : ip;
    }

    /**
     * Bucket key for an IP literal, or {@code null} when the value is not one. IPv4 (and IPv4-mapped IPv6, which
     * parses to IPv4) keys on the canonical address; any other IPv6 address keys on its /64 prefix, so rotating the
     * low 64 bits never mints a new key. An IPv6 address whose /64 prefix is all zeros (IPv4-compatible
     * {@code ::1.2.3.4}, loopback, ...) keys on the full canonical /128 address instead, so such values never collapse
     * into one shared {@code 0:0:0:0:0:0:0:0/64} key.
     */
    public static String visitorIpBucketKey(String value) {
        InetAddress address = parseIpLiteral(value);
        if (address == null) {
            return null;
        }
        if (!(address instanceof Inet6Address)) {
            return address.getHostAddress();
        }
        byte[] bytes = address.getAddress();
        if (isZeroPrefix(bytes)) {
            return address.getHostAddress();
        }
        Arrays.fill(bytes, IPV6_BUCKET_PREFIX_BYTES, bytes.length, (byte) 0);
        try {
            return InetAddress.getByAddress(bytes).getHostAddress() + IPV6_BUCKET_SUFFIX;
        } catch (UnknownHostException e) {
            return null;
        }
    }

    /** True when the first {@value #IPV6_BUCKET_PREFIX_BYTES} bytes (the /64 prefix) are all zero. */
    private static boolean isZeroPrefix(byte[] bytes) {
        for (int i = 0; i < IPV6_BUCKET_PREFIX_BYTES; i++) {
            if (bytes[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Parses an IPv4/IPv6 LITERAL, or returns {@code null}. No DNS lookup is possible: a value reaches
     * {@link InetAddress#getByName} only when it is a strict dotted-quad, or contains a ':' and starts with a
     * hex digit or ':' — exactly the values the JDK parses as a literal (an IPv6 that fails to parse throws
     * {@link UnknownHostException} instead of resolving). A leading '.' or any other character is rejected
     * up front.
     */
    public static InetAddress parseIpLiteral(String value) {
        if (value == null) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.isEmpty() || candidate.length() > MAX_IP_LITERAL_LENGTH) {
            return null;
        }
        boolean ipv4 = IPV4_LITERAL.matcher(candidate).matches();
        boolean ipv6 = !ipv4 && candidate.indexOf(':') >= 0 && IPV6_LITERAL_CHARS.matcher(candidate).matches();
        if (!ipv4 && !ipv6) {
            return null;
        }
        try {
            return InetAddress.getByName(candidate);
        } catch (UnknownHostException e) {
            return null;
        }
    }
}
