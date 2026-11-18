package com.beautica.auth.appcheck;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** B514 (AUTH-54) — LOG_ONLY: bad or missing tokens are verified, counted and logged, but the SMS still goes. */
@TestPropertySource(properties = {
        "app.auth.app-check.mode=LOG_ONLY",
        "app.auth.app-check.project-number=" + AppCheckTestTokens.PROJECT_NUMBER,
        "app.auth.app-check.app-ids=" + AppCheckTestTokens.APP_ID,
        "app.auth.app-check.jwks-min-refetch-interval=0s"})
@DisplayName("App Check LOG_ONLY on the registration OTP send (B514, AUTH-54)")
class AppCheckLogOnlyIT extends AbstractPhoneIdentityIT {

    private static final String JWKS_PATH = "/v1/jwks";
    private static final WireMockServer JWKS = startJwksStub();

    @Autowired
    private MeterRegistry meterRegistry;

    private static WireMockServer startJwksStub() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        server.stubFor(get(urlEqualTo(JWKS_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(AppCheckTestTokens.jwksJson())));
        return server;
    }

    @DynamicPropertySource
    static void jwksUrl(DynamicPropertyRegistry registry) {
        registry.add("app.auth.app-check.jwks-url", () -> "http://localhost:" + JWKS.port() + JWKS_PATH);
    }

    private double counter(String result) {
        var counter = meterRegistry.find(AppCheckGuard.METRIC).tag("result", result).tag("mode", "LOG_ONLY")
                .counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @DisplayName("should_sendCodeAndCountInvalid_when_tokenSignedByForeignKey")
    void should_sendCodeAndCountInvalid_when_tokenSignedByForeignKey() {
        String phone = newPhone();
        double before = counter("invalid");
        HttpHeaders headers = new HttpHeaders();
        headers.set(AppCheckGuard.HEADER, AppCheckTestTokens.signedByForeignKey(Instant.now()));

        ResponseEntity<String> response = post(SEND_URL, Map.of("phone", phone), headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastCodeSentTo(phone)).matches("\\d{6}");
        assertThat(counter("invalid")).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("should_sendCodeAndCountMissing_when_headerAbsent")
    void should_sendCodeAndCountMissing_when_headerAbsent() {
        String phone = newPhone();
        double before = counter("missing");

        ResponseEntity<String> response = sendCode(phone);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(lastCodeSentTo(phone)).matches("\\d{6}");
        assertThat(counter("missing")).isEqualTo(before + 1);
    }
}
