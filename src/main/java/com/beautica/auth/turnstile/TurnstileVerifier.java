package com.beautica.auth.turnstile;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ServiceUnavailableMessages;
import com.beautica.common.exception.TurnstileRejectedException;
import com.beautica.common.observability.ExternalCallFailure;
import com.beautica.config.OutboundTimeouts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.List;
import java.util.Set;

/**
 * Verifies a Cloudflare Turnstile token against {@code siteverify} before an OTP SMS is sent (phase 382).
 *
 * <p><b>Fail closed.</b> When enabled, a missing/blank/oversized token, a {@code success:false} answer and a
 * hostname/action mismatch are all {@link TurnstileRejectedException} (400, one generic message — no oracle);
 * a timeout, connect error, non-2xx status or unparsable body is a 503
 * ({@link ServiceUnavailableMessages#TURNSTILE_UNAVAILABLE}). There is no path on which an unverifiable
 * token passes. When disabled (local, test, default) it is a no-op and the token is ignored.
 *
 * <p><b>HTTP client</b> follows {@code TurbosmsService}: JDK client pinned to HTTP/1.1, redirects never
 * followed, explicit connect/read timeouts, no retry (a replayed token is {@code timeout-or-duplicate}).
 * Timeouts are connect 2s + read 2s (a request waits at most ~4s before failing closed). <b>Secrets:</b> neither the secret nor the token is logged or put in an exception message; a rejection
 * logs Cloudflare's {@code error-codes} only.
 *
 * <p><b>Failures are ERROR (phase 441).</b> A failed siteverify call — timeout, I/O, non-2xx, empty body — and a
 * {@code success:false} carrying a server-side code ({@link #SERVICE_SIDE_ERROR_CODES}: wrong/missing secret, bad
 * request, Cloudflare internal error) log ONE {@link ExternalCallFailure} ERROR ({@code service=turnstile}). A
 * visitor-side rejection ({@code invalid-input-response}, {@code timeout-or-duplicate}, hostname/action mismatch) is a
 * successful call whose answer is "check failed" — it stays WARN.
 */
@Slf4j
@Component
public class TurnstileVerifier {

    /** Cloudflare caps a Turnstile token at 2048 characters. */
    static final int MAX_TOKEN_LENGTH = 2048;
    /** Siteverify {@code error-codes} that mean our call was broken, not the visitor's token (phase 441). */
    static final Set<String> SERVICE_SIDE_ERROR_CODES =
            Set.of("missing-input-secret", "invalid-input-secret", "bad-request", "internal-error");
    private static final String SERVICE = "turnstile";
    private static final String OP_VERIFY = "verify";

    private final TurnstileProperties properties;
    private final RestClient restClient;
    private final Set<String> expectedHostnames;

    public TurnstileVerifier(RestClient.Builder restClientBuilder, TurnstileProperties properties) {
        this.properties = properties;
        this.expectedHostnames = Set.copyOf(properties.getExpectedHostnames());
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(OutboundTimeouts.requirePositive(
                        "app.turnstile.connect-timeout", properties.getConnectTimeout()))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(OutboundTimeouts.requirePositive(
                "app.turnstile.read-timeout", properties.getReadTimeout()));
        this.restClient = restClientBuilder.clone().requestFactory(requestFactory).build();
    }

    /**
     * @param token    the widget's response token from the request body (may be {@code null})
     * @param remoteIp the visitor's IP as resolved by the rate-limit filter, or {@code null}/blank to omit
     * @throws TurnstileRejectedException when the token is missing, invalid, replayed or for another site/action
     * @throws BusinessException          (503) when siteverify cannot be reached or answered unusably
     */
    public void verify(String token, String remoteIp) {
        verify(token, remoteIp, properties.getExpectedAction());
    }

    /**
     * Phase 401 — {@link #verify(String, String)} for the guest review page (submit and opt-out): identical checks,
     * but the action must equal {@code app.turnstile.review-expected-action} (the website renders that widget with
     * {@code review_submit}, not {@code otp_send}).
     */
    public void verifyReviewInvite(String token, String remoteIp) {
        verify(token, remoteIp, properties.getReviewExpectedAction());
    }

    private void verify(String token, String remoteIp, String expectedAction) {
        if (!properties.isEnabled()) {
            return;
        }
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            log.warn("turnstile rejected reason=token-invalid");
            throw new TurnstileRejectedException();
        }
        TurnstileSiteverifyResponse answer = callSiteverify(token, remoteIp);
        if (!answer.success()) {
            List<String> codes = answer.errorCodes() == null ? List.of() : answer.errorCodes();
            codes.stream().filter(SERVICE_SIDE_ERROR_CODES::contains).findFirst().ifPresent(code ->
                    // Phase 441: a secret/request/provider-side code means the CALL failed, not the visitor → ERROR.
                    failure().http(HttpStatus.OK.value()).reason(code).detail(String.join(",", codes)).log(log));
            log.warn("turnstile rejected codes={}", codes);
            throw new TurnstileRejectedException();
        }
        if (!expectedHostnames.isEmpty() && !expectedHostnames.contains(answer.hostname())) {
            log.warn("turnstile rejected reason=hostname-mismatch");
            throw new TurnstileRejectedException();
        }
        if (!expectedAction.isEmpty() && !expectedAction.equals(answer.action())) {
            log.warn("turnstile rejected reason=action-mismatch");
            throw new TurnstileRejectedException();
        }
    }

    private TurnstileSiteverifyResponse callSiteverify(String token, String remoteIp) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("secret", properties.getSecretKey());
        form.add("response", token);
        if (remoteIp != null && !remoteIp.isBlank()) {
            form.add("remoteip", remoteIp);
        }
        TurnstileSiteverifyResponse answer;
        try {
            answer = restClient.post()
                    .uri(properties.getSiteverifyUrl())
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(form)
                    .retrieve()
                    .onStatus(status -> !status.is2xxSuccessful(), (req, res) -> {
                        int code = res.getStatusCode().value();
                        failure().http(code).reason("http_" + code).log(log);
                        throw unavailable("status=" + code);
                    })
                    .body(TurnstileSiteverifyResponse.class);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            // Translating, never swallowing: ANY failure to obtain a usable answer must fail CLOSED as a 503.
            // That includes the JDK client's read timeout, which surfaces as a bare CancellationException
            // (not a RestClientException), plus connect errors and body-conversion failures. The secret travels in
            // the form body, which RestClient's exceptions never echo; the URL is the public siteverify endpoint.
            failure().reason(ExternalCallFailure.transportReason(e))
                    .detail(ExternalCallFailure.rootCauseName(e)).log(log, e);
            throw unavailable("cause=" + e.getClass().getSimpleName());
        }
        if (answer == null) {
            failure().http(HttpStatus.OK.value()).reason(ExternalCallFailure.UNREADABLE_RESPONSE)
                    .detail("empty body").log(log);
            throw unavailable("cause=empty-body");
        }
        return answer;
    }

    private static ExternalCallFailure failure() {
        return ExternalCallFailure.of(SERVICE, OP_VERIFY);
    }

    /** The ERROR line was already logged by the caller (phase 441); this WARN keeps the 503 mapping visible. */
    private static BusinessException unavailable(String reason) {
        log.warn("turnstile siteverify unavailable {}", reason);
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ServiceUnavailableMessages.TURNSTILE_UNAVAILABLE);
    }
}
