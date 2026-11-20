package com.beautica.auth.registration;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AUTH-D21 / AUTH-41 (security audit 2026-11-19, {@code fix/rate-limit-client-ip}) — the client-IP rule end to end over
 * HTTP, through the real filter chain and the {@code test}-profile {@code ClientIpResolver} (trust-forwarded-headers on,
 * the prod rule): the per-IP key is the RIGHT-most valid {@code X-Forwarded-For} entry across ALL header lines, so a
 * caller rotating the entries to its left (or a whole caller-typed first header line) never mints a fresh budget, and
 * the OTP-send bucket counts an IPv6 client per /64.
 *
 * <p>Runs on the registration OTP send ({@code POST /api/v1/auth/register/phone/otp/send}) at its REAL per-IP capacity
 * (3 / 15 min) — the same property set as {@link PhoneOtpSendPerIpLimitIT}, so both share one cached context. Every
 * test owns its right-most IP / /64 prefix (TEST-NET-2 / 2001:db8::/32 documentation ranges) and sends each code to a
 * fresh phone, so neither the singleton per-IP bucket nor the per-phone cap couples tests.
 */
@TestPropertySource(properties = "app.rate-limit.otp-send-capacity=3")
@DisplayName("ClientIpForwardedForRateLimitIT — per-IP key = right-most X-Forwarded-For, IPv6 per /64 (AUTH-D21)")
class ClientIpForwardedForRateLimitIT extends AbstractPhoneIdentityIT {

    private static final int OTP_SEND_CAPACITY = 3;
    private static final String FORWARDED_FOR = "X-Forwarded-For";
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    /** A right-most (edge-appended) client IP unique in this JVM (TEST-NET-2). */
    private static String nextEdgeIp() {
        return "198.51.100." + (SEQUENCE.incrementAndGet() % 254 + 1);
    }

    /** A caller-typed, rotating left entry (TEST-NET-1), different on every call. */
    private static String nextSpoofedIp() {
        return "192.0.2." + (SEQUENCE.incrementAndGet() % 254 + 1);
    }

    /** A /64 prefix (as {@code 2001:db8:<n>:<n>}) unique in this JVM. */
    private static String nextIpv6Prefix() {
        int n = SEQUENCE.incrementAndGet();
        return "2001:db8:" + Integer.toHexString(n) + ":" + Integer.toHexString(n * 7 + 1);
    }

    /**
     * POSTs a registration OTP send for a fresh phone with the given {@code X-Forwarded-For} header lines, one header
     * line per element (the base class {@code post} pins its own single per-instance line, so it cannot drive this).
     */
    private ResponseEntity<String> sendCodeWithForwardedFor(String phone, List<String> forwardedForLines) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        forwardedForLines.forEach(line -> headers.add(FORWARDED_FOR, line));
        try {
            String body = objectMapper.writeValueAsString(Map.of("phone", phone));
            return restTemplate.exchange(SEND_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("rotating the LEFT X-Forwarded-For entries under one fixed right-most IP → the 4th send is 429, "
            + "no SMS; another right-most IP is still served")
    void should_return429_when_leftForwardedForEntriesRotateUnderFixedRightmostIp() {
        String edgeIp = nextEdgeIp();
        for (int i = 0; i < OTP_SEND_CAPACITY; i++) {
            ResponseEntity<String> withinBudget = sendCodeWithForwardedFor(newPhone(),
                    List.of(nextSpoofedIp() + ", " + nextSpoofedIp() + ", " + edgeIp));
            assertThat(withinBudget.getStatusCode()).as("send %d within the per-IP budget", i + 1)
                    .isEqualTo(HttpStatus.OK);
        }
        String overBudgetPhone = newPhone();
        String otherClientPhone = newPhone();

        ResponseEntity<String> overBudget = sendCodeWithForwardedFor(overBudgetPhone,
                List.of(nextSpoofedIp() + ", " + nextSpoofedIp() + ", " + edgeIp));
        ResponseEntity<String> otherClient = sendCodeWithForwardedFor(otherClientPhone,
                List.of(nextSpoofedIp() + ", " + nextEdgeIp()));

        assertThat(overBudget.getStatusCode())
                .as("fresh left entries must not mint a fresh budget, body=%s", overBudget.getBody())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(overBudget.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(smsCountTo(overBudgetPhone)).as("no SMS for the throttled send").isZero();
        assertThat(otherClient.getStatusCode()).as("a different right-most IP keeps its own budget")
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("a rotating caller-typed FIRST X-Forwarded-For header line + a fixed edge-appended second line → "
            + "the 4th send is 429 (every header line is read, right-most wins)")
    void should_return429_when_callerTypedFirstHeaderLineRotatesAboveFixedEdgeLine() {
        String edgeIp = nextEdgeIp();
        for (int i = 0; i < OTP_SEND_CAPACITY; i++) {
            assertThat(sendCodeWithForwardedFor(newPhone(), List.of(nextSpoofedIp(), edgeIp)).getStatusCode())
                    .as("send %d within the per-IP budget", i + 1).isEqualTo(HttpStatus.OK);
        }
        String overBudgetPhone = newPhone();

        ResponseEntity<String> overBudget = sendCodeWithForwardedFor(overBudgetPhone,
                List.of(nextSpoofedIp(), edgeIp));

        assertThat(overBudget.getStatusCode())
                .as("a fresh first header line must not mint a fresh budget, body=%s", overBudget.getBody())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(smsCountTo(overBudgetPhone)).as("no SMS for the throttled send").isZero();
    }

    @Test
    @DisplayName("3 sends from one IPv6 address → a never-seen address in the same /64 is 429; "
            + "an address in another /64 is still served")
    void should_shareOtpSendBucket_when_twoIpv6AddressesInOneSlash64() {
        String prefix = nextIpv6Prefix();
        String firstHost = prefix + ":0:0:0:1";
        String secondHost = prefix + ":a1b2:c3d4:e5f6:7788";
        String otherPrefixHost = nextIpv6Prefix() + "::1";
        for (int i = 0; i < OTP_SEND_CAPACITY; i++) {
            assertThat(sendCodeWithForwardedFor(newPhone(), List.of(firstHost)).getStatusCode())
                    .as("send %d from the first host", i + 1).isEqualTo(HttpStatus.OK);
        }
        String overBudgetPhone = newPhone();
        String otherPrefixPhone = newPhone();

        ResponseEntity<String> overBudget = sendCodeWithForwardedFor(overBudgetPhone, List.of(secondHost));
        ResponseEntity<String> otherPrefix = sendCodeWithForwardedFor(otherPrefixPhone, List.of(otherPrefixHost));

        assertThat(overBudget.getStatusCode())
                .as("the second host never sent, but its /64 has spent 3, body=%s", overBudget.getBody())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(overBudget.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank();
        assertThat(smsCountTo(overBudgetPhone)).as("no SMS for the throttled send").isZero();
        assertThat(otherPrefix.getStatusCode()).as("another /64 keeps its own budget").isEqualTo(HttpStatus.OK);
    }
}
