package com.beautica.support;

import org.apache.hc.client5.http.impl.DefaultHttpRequestRetryStrategy;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.TimeValue;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

/**
 * The ONE place an integration test's Apache HC5 request factory is built.
 *
 * <p>Exists because 96 test classes had each installed a bare {@code HttpClients.createDefault()}
 * in their own {@code @BeforeEach}, silently discarding the timeout and retry policy
 * {@link com.beautica.AbstractIntegrationTest} configures. A default HC5 client has an INFINITE
 * response timeout and DOES retry: a rate-limit 429 that resets the socket then hangs the whole
 * suite instead of failing one case. Fixing one copy would only have re-created the drift, so the
 * policy moved here and the base class installs it for every test.
 *
 * <p><b>Use {@link com.beautica.AbstractIntegrationTest}, not this class,</b> unless your test
 * cannot extend it — a standalone {@code @SpringBootTest} that declares its own context. Those call
 * {@link #timeoutBoundedRequestFactory()} directly; everything else inherits the hook.
 */
public final class TestHttpClients {

    /**
     * Finite response timeout on every phase of the exchange. Ten seconds is far longer than any
     * endpoint under test needs and far shorter than the 27-minute suite hang an infinite timeout
     * produced in CI.
     */
    private static final int TIMEOUT_MILLIS = 10_000;

    private TestHttpClients() {
    }

    /**
     * A fresh connection pool with a finite response timeout and ZERO retries.
     *
     * <p>Retries are off deliberately: several suites assert on 429/409 responses whose sockets the
     * server resets, and a retrying client turns a deterministic assertion into a hang.
     *
     * <p>Returns a NEW factory per call — callers install it per test so no case inherits a
     * stale or closed pool from the previous one.
     */
    public static HttpComponentsClientHttpRequestFactory timeoutBoundedRequestFactory() {
        var httpClient = HttpClients.custom()
                .setRetryStrategy(new DefaultHttpRequestRetryStrategy(0, TimeValue.ZERO_MILLISECONDS))
                .build();
        var factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        factory.setConnectionRequestTimeout(TIMEOUT_MILLIS);
        factory.setConnectTimeout(TIMEOUT_MILLIS);
        factory.setReadTimeout(TIMEOUT_MILLIS);
        return factory;
    }
}
