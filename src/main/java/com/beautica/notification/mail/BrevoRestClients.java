package com.beautica.notification.mail;

import com.beautica.config.OutboundTimeouts;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CancellationException;

/**
 * Builds the Brevo {@link RestClient}s (sender and boot probe) with the same transport rules as
 * {@code TurbosmsService}: the JDK client named explicitly (never {@code detect()}, which picks the test-only
 * Apache HC5 that silently retries 429/503 — a duplicate e-mail here), HTTP/1.1 pinned, redirects never followed
 * (a 3xx must not re-issue the send elsewhere), and connect/read timeouts validated positive.
 */
public final class BrevoRestClients {

    private BrevoRestClients() {
    }

    /**
     * One JDK client per caller (sender: long-lived; probe: closed after its single call). Connect timeout validated.
     */
    public static HttpClient httpClient(String connectProperty, Duration connect) {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(OutboundTimeouts.requirePositive(connectProperty, connect))
                .build();
    }

    /**
     * @param builder      Boot's prototype builder (observability), cloned so the caller's instance is untouched
     * @param httpClient   from {@link #httpClient}; several RestClients may share one (same pool, different budgets)
     * @param readProperty config key named in the failure message when {@code read} is not positive
     */
    public static RestClient build(RestClient.Builder builder, String baseUrl, HttpClient httpClient,
                                   String readProperty, Duration read) {
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(OutboundTimeouts.requirePositive(readProperty, read));
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    /**
     * {@code true} when {@code failure} is a network/timeout failure anywhere in its cause chain:
     * {@link ResourceAccessException}, any {@link IOException} (incl. {@code HttpTimeoutException},
     * {@code ConnectException}), or {@link CancellationException} — {@link JdkClientHttpRequestFactory} enforces
     * its read timeout by cancelling the response future, which can surface unwrapped.
     */
    public static boolean isTransportFailure(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ResourceAccessException || t instanceof IOException
                    || t instanceof CancellationException) {
                return true;
            }
        }
        return false;
    }
}
