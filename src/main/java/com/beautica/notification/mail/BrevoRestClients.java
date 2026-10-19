package com.beautica.notification.mail;

import com.beautica.config.OutboundTimeouts;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

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
     * @param builder        Boot's prototype builder (observability), cloned so the caller's instance is untouched
     * @param connectProperty config key named in the failure message when {@code connect} is not positive
     * @param readProperty    config key named in the failure message when {@code read} is not positive
     */
    public static RestClient build(RestClient.Builder builder, String baseUrl,
                                   String connectProperty, Duration connect,
                                   String readProperty, Duration read) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(OutboundTimeouts.requirePositive(connectProperty, connect))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(OutboundTimeouts.requirePositive(readProperty, read));
        return builder.clone().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
