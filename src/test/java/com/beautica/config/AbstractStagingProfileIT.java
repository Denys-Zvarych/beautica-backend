package com.beautica.config;

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
        "APP_OTP_PEPPER=staging-it-otp-pepper-fixed-value-min-32-chars",
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

    @DynamicPropertySource
    static void turbosmsBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("app.sms.turbosms.base-url", () -> "http://localhost:" + turbosmsStub.port() + "/message/send.json");
    }

    protected static WireMockServer turbosmsStub() {
        return turbosmsStub;
    }
}
