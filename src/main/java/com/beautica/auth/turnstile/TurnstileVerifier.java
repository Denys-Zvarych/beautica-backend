package com.beautica.auth.turnstile;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ServiceUnavailableMessages;
import com.beautica.common.exception.TurnstileRejectedException;
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
 */
@Slf4j
@Component
public class TurnstileVerifier {

    /** Cloudflare caps a Turnstile token at 2048 characters. */
    static final int MAX_TOKEN_LENGTH = 2048;

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
            log.warn("turnstile rejected codes={}", answer.errorCodes() == null ? List.of() : answer.errorCodes());
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
                        throw unavailable("status=" + res.getStatusCode().value());
                    })
                    .body(TurnstileSiteverifyResponse.class);
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            // Translating, never swallowing: ANY failure to obtain a usable answer must fail CLOSED as a 503.
            // That includes the JDK client's read timeout, which surfaces as a bare CancellationException
            // (not a RestClientException), plus connect errors and body-conversion failures. Only the
            // exception TYPE is logged: the message could carry the URL, and the request holds the secret.
            throw unavailable("cause=" + e.getClass().getSimpleName());
        }
        if (answer == null) {
            throw unavailable("cause=empty-body");
        }
        return answer;
    }

    private static BusinessException unavailable(String reason) {
        log.warn("turnstile siteverify unavailable {}", reason);
        return new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ServiceUnavailableMessages.TURNSTILE_UNAVAILABLE);
    }
}
