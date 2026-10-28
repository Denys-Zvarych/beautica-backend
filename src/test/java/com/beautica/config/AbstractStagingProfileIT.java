package com.beautica.config;

import com.beautica.support.TestSecrets;
import com.beautica.AbstractIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;

/**
 * Shared wiring for ITs that boot the real {@code prod,staging} profile pair (Phase 368).
 *
 * <p>{@code inheritProfiles = false}: the {@code test} profile would disable {@code FlywayDataSourceConfig}
 * and hide the prod wiring these classes exist to exercise. {@code EnvironmentFingerprintGuard} runs for
 * real here (staging is not skipped) and must pass with the staging overlay's own values.
 */
@ActiveProfiles(profiles = {"prod", "staging"}, inheritProfiles = false)
@TestPropertySource(properties = {
        "JWT_SECRET=staging-it-jwt-secret-min-32-bytes-testonly",
        "INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY,
        "APP_OTP_PEPPER=staging-it-otp-pepper-fixed-value-min-32-chars",
        "PHONE_HASH_PEPPER=staging-it-phone-hash-pepper-fixed-value-min-32",
        "OUTBOX_PAYLOAD_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "FRONTEND_BASE_URL=https://staging.invalid",
        "PUBLIC_BASE_URL=https://api.staging.beautica.test",
        "SUPPORT_EMAIL=support@test.invalid",
        // First-deploy authorisation for the DB environment stamp (Phase 368 N2); ignored once stamped.
        "BEAUTICA_DB_STAMP_INIT=staging",
        "app.rate-limit.otp-send-capacity=100000",
        // TestAsyncConfig (from AbstractIntegrationTest) redefines emailExecutor; application-test.yml normally allows it.
        "spring.main.allow-bean-definition-overriding=true"
})
@Import(TestSecurityConfig.class)
public abstract class AbstractStagingProfileIT extends AbstractIntegrationTest {

    private static WireMockServer turbosmsStub;
    // Phase 382: staging runs the REAL siteverify round trip (default test secret); the endpoint is a stub so
    // no test reaches Cloudflare. A SEPARATE server from the Turbosms stub: the OTP tests assert that stub
    // received nothing at all.
    //
    // Started ONCE per JVM and never stopped: the Spring context is cached across the subclasses and its
    // app.turnstile.siteverify-url is bound to this port at first creation, so a per-class stub would be
    // dead (connection refused -> 503) for every later class that reuses the context.
    private static final WireMockServer turnstileStub = startTurnstileStub();

    @BeforeAll
    static void startTurbosmsStub() {
        turbosmsStub = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        turbosmsStub.start();
        turbosmsStub.stubFor(any(urlMatching(".*")).willReturn(aResponse().withStatus(200)));
    }

    @AfterAll
    static void stopTurbosmsStub() {
        turbosmsStub.stop();
    }

    private static WireMockServer startTurnstileStub() {
        WireMockServer stub = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        stub.start();
        stub.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                        com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/turnstile/v0/siteverify"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":true,\"error-codes\":[],\"hostname\":\"example.com\","
                                + "\"action\":\"test\"}")));
        return stub;
    }

    @DynamicPropertySource
    static void turbosmsBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("app.sms.turbosms.base-url", () -> "http://localhost:" + turbosmsStub.port() + "/message/send.json");
        registry.add("app.turnstile.siteverify-url",
                () -> "http://localhost:" + turnstileStub.port() + "/turnstile/v0/siteverify");
    }

    protected static WireMockServer turnstileStub() {
        return turnstileStub;
    }

    protected static WireMockServer turbosmsStub() {
        return turbosmsStub;
    }
}
