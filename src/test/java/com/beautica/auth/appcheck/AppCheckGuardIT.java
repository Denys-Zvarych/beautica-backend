package com.beautica.auth.appcheck;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.jsonwebtoken.JwtBuilder;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * B514 (AUTH-54) — App Check in ENFORCE on {@code POST /auth/register/phone/otp/send}, against a WireMock JWKS
 * serving a test RSA key ({@link AppCheckTestTokens}). The per-IP OTP bucket runs at its production capacity (3) so
 * the filter-before-guard order is observable. Also pins the {@link RequireAppCheck} path ledger.
 */
@TestPropertySource(properties = {
        "app.rate-limit.otp-send-capacity=3",
        "app.auth.app-check.mode=ENFORCE",
        "app.auth.app-check.project-number=" + AppCheckTestTokens.PROJECT_NUMBER,
        "app.auth.app-check.app-ids=" + AppCheckTestTokens.APP_ID,
        "app.auth.app-check.jwks-min-refetch-interval=0s"})
@DisplayName("App Check ENFORCE on the registration OTP send (B514, AUTH-54)")
class AppCheckGuardIT extends AbstractPhoneIdentityIT {

    private static final String JWKS_PATH = "/v1/jwks";
    private static final WireMockServer JWKS = startJwksStub();

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private AppCheckJwks appCheckJwks;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private static WireMockServer startJwksStub() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        stubJwksUp(server);
        return server;
    }

    private static void stubJwksUp(WireMockServer server) {
        server.stubFor(get(urlEqualTo(JWKS_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(AppCheckTestTokens.jwksJson())));
    }

    @DynamicPropertySource
    static void jwksUrl(DynamicPropertyRegistry registry) {
        registry.add("app.auth.app-check.jwks-url", () -> "http://localhost:" + JWKS.port() + JWKS_PATH);
    }

    @AfterEach
    void restoreJwks() {
        JWKS.resetAll();
        stubJwksUp(JWKS);
    }

    private ResponseEntity<String> sendWithToken(String phone, String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.set(AppCheckGuard.HEADER, token);
        }
        return post(SEND_URL, Map.of("phone", phone), headers);
    }

    private double counter(String result) {
        var counter = meterRegistry.find(AppCheckGuard.METRIC).tag("result", result).tag("mode", "ENFORCE")
                .tag("endpoint", "POST " + SEND_URL).counter();
        return counter == null ? 0 : counter.count();
    }

    static Stream<Arguments> rejectedTokens() {
        Instant now = Instant.now();
        UnaryOperator<JwtBuilder> expired = b -> b.expiration(Date.from(now.minusSeconds(5)));
        return Stream.of(
                Arguments.of("missing", null),
                Arguments.of("bad signature", AppCheckTestTokens.signedByForeignKey(now)),
                Arguments.of("expired", AppCheckTestTokens.token(now, expired)),
                Arguments.of("wrong aud", AppCheckTestTokens.token(now, b -> b.audience().single("projects/1"))),
                Arguments.of("sub not in app-ids",
                        AppCheckTestTokens.token(now, b -> b.subject(AppCheckTestTokens.OTHER_APP_ID))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rejectedTokens")
    @DisplayName("a missing or invalid App Check token is 403 APP_CHECK_FAILED and no code is stored or sent")
    void should_return403AndSendNothing_when_tokenMissingOrInvalid(String label, String token) {
        String phone = newPhone();

        ResponseEntity<String> response = sendWithToken(phone, token);

        assertThat(response.getStatusCode()).as(label).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("APP_CHECK_FAILED");
        assertThat(response.getBody()).doesNotContain(phone);
        verify(otpSmsSender, never()).send(anyString(), anyString());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM phone_otps WHERE phone = ?",
                Integer.class, phone)).isZero();
    }

    @Test
    @DisplayName("a valid App Check token lets the code go out and counts a valid check")
    void should_sendCodeAndCountValid_when_tokenValid() {
        String phone = newPhone();
        double validBefore = counter("valid");

        ResponseEntity<String> response = sendWithToken(phone, AppCheckTestTokens.valid(Instant.now()));

        assertThat(response.getStatusCode()).as("body: %s", response.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(lastCodeSentTo(phone)).matches("\\d{6}");
        assertThat(counter("valid")).isEqualTo(validBefore + 1);
    }

    @Test
    @DisplayName("a valid token with a landline is 400 PHONE_NOT_UA_MOBILE and no SMS")
    void should_return400PhoneNotUaMobileWithoutSms_when_validTokenButLandline() {
        ResponseEntity<String> response = sendWithToken("+380441234567", AppCheckTestTokens.valid(Instant.now()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("PHONE_NOT_UA_MOBILE");
        verify(otpSmsSender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("with Google's keys down and nothing cached the send is 503 APP_CHECK_UNAVAILABLE")
    void should_return503Unavailable_when_jwksDownAndCacheEmpty() {
        ReflectionTestUtils.setField(appCheckJwks, "snapshot", null);
        JWKS.resetAll();
        JWKS.stubFor(get(urlEqualTo(JWKS_PATH)).willReturn(aResponse().withStatus(500)));
        String phone = newPhone();

        ResponseEntity<String> response = sendWithToken(phone, AppCheckTestTokens.valid(Instant.now()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(json(response).at("/data/code").asText()).isEqualTo("APP_CHECK_UNAVAILABLE");
        verify(otpSmsSender, never()).send(anyString(), anyString());
    }

    @Test
    @DisplayName("an exhausted per-IP bucket answers 429 before App Check runs")
    void should_answer429BeforeAppCheck_when_perIpBucketExhausted() {
        double missingBefore = counter("missing");
        for (int i = 0; i < 3; i++) {
            assertThat(sendWithToken(newPhone(), null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        ResponseEntity<String> fourth = sendWithToken(newPhone(), null);

        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(counter("missing")).as("the 429 never reached the guard").isEqualTo(missingBefore + 3);
    }

    @Test
    @DisplayName("only the registration and the signed-in phone OTP sends carry @RequireAppCheck (B514 + B516; B520 extends it)")
    void should_pinRequireAppCheckLedger_to_registrationAndUserPhoneOtpSend() {
        Set<String> annotated = new TreeSet<>();
        handlerMapping.getHandlerMethods().forEach((info, method) -> {
            if (method.hasMethodAnnotation(RequireAppCheck.class)) {
                info.getMethodsCondition().getMethods().forEach(httpMethod ->
                        info.getPatternValues().forEach(path -> annotated.add(httpMethod + " " + path)));
            }
        });

        assertThat(annotated).containsExactly(
                "POST /api/v1/auth/register/phone/otp/send",
                "POST /api/v1/users/me/phone/otp/send");
    }

    // ── B516 (AUTH-54): add / change phone, forgot / change password by SMS ────

    private static final String USER_PHONE_SEND_URL = "/api/v1/users/me/phone/otp/send";
    private static final String FORGOT_URL = "/api/v1/auth/forgot-password";
    private static final String CHANGE_OTP_URL = "/api/v1/users/me/change-password/request-otp";

    @Test
    @DisplayName("B516: add-phone send without a token -> 403 APP_CHECK_FAILED, no SMS; with a valid token -> 200")
    void should_gateUserPhoneSend_when_enforce() {
        String email = newEmail();
        seedUser("CLIENT", email, true, null, null);
        String token = accessToken(email);
        String phone = newPhone();

        ResponseEntity<String> rejected = postAs(token, USER_PHONE_SEND_URL, Map.of("phone", phone));
        HttpHeaders withToken = bearer(token);
        withToken.set(AppCheckGuard.HEADER, AppCheckTestTokens.valid(Instant.now()));
        ResponseEntity<String> accepted = post(USER_PHONE_SEND_URL, Map.of("phone", phone), withToken);

        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(rejected).at("/data/code").asText()).isEqualTo("APP_CHECK_FAILED");
        assertThat(accepted.getStatusCode()).as("body: %s", accepted.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(smsCountTo(phone)).isEqualTo(1);
    }

    @Test
    @DisplayName("B516: forgot by a registered or an unknown phone without a token -> the same 403, no SMS")
    void should_answerSame403_when_forgotByPhoneWithoutToken() {
        String registered = newPhone();
        seedUser("CLIENT", null, false, registered, null);

        ResponseEntity<String> known = post(FORGOT_URL, Map.of("identifier", registered));
        ResponseEntity<String> unknown = post(FORGOT_URL, Map.of("identifier", newPhone()));

        assertThat(known.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(withoutRequestId(known)).as("only the per-request id differs").isEqualTo(withoutRequestId(unknown));
        assertThat(unknown.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        verify(otpSmsSender, never()).send(anyString(), anyString());
    }

    private JsonNode withoutRequestId(ResponseEntity<String> response) {
        ObjectNode body = (ObjectNode) json(response);
        body.remove("requestId");
        return body;
    }

    @Test
    @DisplayName("B516: forgot by email without a token is unchanged (200, emailed code)")
    void should_answer200_when_forgotByEmailWithoutToken() {
        String email = newEmail();
        seedUser("CLIENT", email, true, newPhone(), null);

        ResponseEntity<String> response = post(FORGOT_URL, Map.of("email", email));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(emailNotificationService).sendPasswordResetOtpEmail(any(), anyString());
    }

    @Test
    @DisplayName("B516: change-password by SMS without a token -> 403, no SMS; by email without a token -> 200")
    void should_gateOnlySmsBranch_when_changePasswordWithoutToken() {
        String smsPhone = newPhone();
        seedUser("CLIENT", null, false, smsPhone, null);
        String emailPhone = newPhone();
        seedUser("CLIENT", newEmail(), true, emailPhone, null);

        ResponseEntity<String> sms = postAs(accessToken(smsPhone), CHANGE_OTP_URL, Map.of());
        ResponseEntity<String> mail = postAs(accessToken(emailPhone), CHANGE_OTP_URL, Map.of());

        assertThat(sms.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(json(sms).at("/data/code").asText()).isEqualTo("APP_CHECK_FAILED");
        assertThat(mail.getStatusCode()).isEqualTo(HttpStatus.OK);
        verify(otpSmsSender, never()).send(anyString(), anyString());
    }
}
