package com.beautica.booking;

import com.beautica.auth.phoneotp.OtpSmsExpectedCopy;
import com.beautica.notification.sms.OtpSmsSender;
import com.beautica.notification.sms.SmsClubOtpSmsSender;
import com.beautica.notification.sms.SmsClubService;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.beautica.common.exception.ServiceUnavailableMessages;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 439 — {@code beautica.sms.mode=smsclub} end to end: the real conditional beans, the real request paths and a
 * WireMock SMSClub endpoint. Lives in {@code com.beautica.booking} (not {@code notification.sms}) to reuse the
 * package-private {@link AbstractWalkInBookingSmsIT} rig (fixtures, HTTP helpers, root log capture) rather than
 * fork it. No {@code SmsService} mock — the bean SmsConfig selects IS the subject.
 */
// Phase 440: otp-domain set so DOMAIN_BOUND and PLAIN differ on the SMSClub wire (empty would render both alike).
@TestPropertySource(properties = {"app.booking.sms.enabled=true", "beautica.sms.mode=smsclub",
        "beautica.sms.otp-domain=" + SmsClubSmsIT.OTP_DOMAIN})
@DisplayName("SmsClubSmsIT — beautica.sms.mode=smsclub, booking flag on")
class SmsClubSmsIT extends AbstractWalkInBookingSmsIT {

    static final String OTP_DOMAIN = "beautica.app";
    private static final String SMSCLUB_PATH = "/sms/send";
    private static final String SMSCLUB_TOKEN = "wiremock-smsclub-token";

    /** Started once per JVM, like the base class's Turbosms server (same cached-context reasoning). */
    private static final WireMockServer SMSCLUB = new WireMockServer(options().dynamicPort());

    static {
        SMSCLUB.start();
        Runtime.getRuntime().addShutdownHook(new Thread(SMSCLUB::stop));
    }

    @DynamicPropertySource
    static void smsClubEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.sms.smsclub.base-url", () -> SMSCLUB.baseUrl() + SMSCLUB_PATH);
        // Non-blank on purpose: a blank token short-circuits before the HTTP call.
        registry.add("app.sms.smsclub.token", () -> SMSCLUB_TOKEN);
    }

    @BeforeEach
    void resetSmsClub() {
        SMSCLUB.resetAll();
    }

    private static void stubSuccessFor(String phoneE164) {
        SMSCLUB.stubFor(post(urlEqualTo(SMSCLUB_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success_request\":{\"info\":{\"106\":\"" + phoneE164.substring(1) + "\"}}}")));
    }

    private JsonNode onlySmsClubPayload() throws Exception {
        List<LoggedRequest> requests = SMSCLUB.findAll(postRequestedFor(urlEqualTo(SMSCLUB_PATH)));
        assertThat(requests).as("exactly one SMSClub call, never retried").hasSize(1);
        return objectMapper.readTree(requests.get(0).getBodyAsString());
    }

    @Test
    @DisplayName("smsclub mode — the injected beans are the SMSClub ones")
    void should_injectSmsClubBeans_when_modeIsSmsClub() {
        assertThat(smsService).isInstanceOf(SmsClubService.class);
        assertThat(applicationContext.getBean(OtpSmsSender.class)).isInstanceOf(SmsClubOtpSmsSender.class);
    }

    @Test
    @DisplayName("guest phone-OTP — one SMSClub request with lifetime=10, nothing to Turbosms")
    void should_sendOtpOnceWithOtpLifetime_when_guestRequestsCode() throws Exception {
        stubSuccessFor(OTP_PHONE);

        ResponseEntity<String> resp = sendOtp(OTP_PHONE);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode sent = onlySmsClubPayload();
        assertThat(sent.path("phone")).hasSize(1);
        assertThat(sent.path("phone").get(0).asText()).isEqualTo(OTP_PHONE.substring(1));
        assertThat(sent.path("lifetime").asInt()).isEqualTo(10);
        SMSCLUB.verify(postRequestedFor(urlEqualTo(SMSCLUB_PATH))
                .withHeader("Authorization", equalTo("Bearer " + SMSCLUB_TOKEN)));
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertNoPiiInLogs(OTP_PHONE, OTP_SUBSCRIBER_DIGITS);
    }

    @Test
    @DisplayName("walk-in confirmation — one SMSClub request with lifetime=60 and src_addr=BEAUTY")
    void should_sendWalkInConfirmationOnce_when_gateIsOn() throws Exception {
        stubSuccessFor(E164_PHONE);

        ResponseEntity<String> resp = createWalkIn(tomorrowAtNoon());

        assertConfirmedWalkInBody(resp);
        JsonNode sent = onlySmsClubPayload();
        assertThat(sent.path("phone").get(0).asText()).isEqualTo(E164_PHONE.substring(1));
        assertThat(sent.path("lifetime").asInt()).isEqualTo(60);
        assertThat(sent.path("src_addr").asText()).isEqualTo("BEAUTY");
        assertThat(sent.path("message").asText()).contains(MASTER_NAME);
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertNoPiiInLogs(E164_PHONE, PHONE_SUBSCRIBER_DIGITS);
    }

    /** Mirrors {@code WalkInBookingSmsEnabledIT#should_keepTheBookingConfirmed_when_turbosmsReturns5xx}. */
    @Test
    @DisplayName("walk-in + SMSClub blacklist add_info — same outcome as a Turbosms error: 201, CONFIRMED, logged by class")
    void should_keepTheBookingConfirmed_when_smsClubBlacklistsTheNumber() {
        SMSCLUB.stubFor(post(urlEqualTo(SMSCLUB_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success_request\":{\"add_info\":{\"" + E164_PHONE.substring(1)
                        + "\":\"This number is in the black list\"}}}")));

        ResponseEntity<String> resp = createWalkIn(tomorrowAtNoon());

        assertConfirmedWalkInBody(resp);
        SMSCLUB.verify(1, postRequestedFor(urlEqualTo(SMSCLUB_PATH)));
        assertThat(onlyBooking().get("status")).isEqualTo("CONFIRMED");
        assertThat(logMessages())
                .anyMatch(line -> line.contains("Booking SMS send failed")
                        && line.contains("kind=walk_in_confirmation")
                        && line.contains("cause=SmsDeliveryException"))
                .anyMatch(line -> line.contains("reason=BLACKLISTED"))
                .noneMatch(line -> line.toLowerCase(java.util.Locale.ROOT).contains("black list"));
        assertNoPiiInLogs(E164_PHONE, PHONE_SUBSCRIBER_DIGITS);
    }
    /** Phase 440 — the guest OTP send with an explicit {@code smsFormat}, body via Jackson (Q16). */
    private ResponseEntity<String> sendOtp(String phone, String smsFormat) throws Exception {
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", PublicBookingPageFixtures.randomIp());
        String body = objectMapper.writeValueAsString(java.util.Map.of("phone", phone, "privacyAccepted", true,
                "privacyPolicyVersion", "2026-10-20", "smsFormat", smsFormat));
        return restTemplate.exchange("/api/v1/book/otp/send", org.springframework.http.HttpMethod.POST,
                new org.springframework.http.HttpEntity<>(body, headers), String.class);
    }

    private static String codeIn(String text) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\d{6}").matcher(text);
        assertThat(m.find()).as("no 6-digit code in the SMSClub message").isTrue();
        return m.group();
    }

    @Test
    @DisplayName("phase 440: smsFormat PLAIN in smsclub mode — SMSClub `message` is exactly the human line, log says format=plain")
    void should_sendPlainMessageToSmsClub_when_smsFormatPlain() throws Exception {
        stubSuccessFor(OTP_PHONE);

        ResponseEntity<String> resp = sendOtp(OTP_PHONE, "PLAIN");

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        String message = onlySmsClubPayload().path("message").asText();
        String code = codeIn(message);
        assertThat(message).isEqualTo(OtpSmsExpectedCopy.plain(code)).doesNotContain("@" + OTP_DOMAIN);
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(logMessages())
                .anyMatch(line -> line.startsWith("OTP sent to ") && line.endsWith(" format=plain"))
                .noneMatch(line -> line.contains(code));
        assertNoPiiInLogs(OTP_PHONE, OTP_SUBSCRIBER_DIGITS);
    }

    @Test
    @DisplayName("phase 440: no smsFormat in smsclub mode — SMSClub `message` is today's domain-bound text, log says format=domain_bound")
    void should_sendDomainBoundMessageToSmsClub_when_smsFormatMissing() throws Exception {
        stubSuccessFor(OTP_PHONE);

        ResponseEntity<String> resp = sendOtp(OTP_PHONE);

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        String message = onlySmsClubPayload().path("message").asText();
        String code = codeIn(message);
        assertThat(message).isEqualTo(OtpSmsExpectedCopy.domainBound(code, OTP_DOMAIN));
        assertThat(logMessages())
                .anyMatch(line -> line.startsWith("OTP sent to ") && line.endsWith(" format=domain_bound"))
                .noneMatch(line -> line.contains(code));
    }

    /**
     * QA phase 439 — the OTP channel's failure path in smsclub mode: an SMSClub refusal (business 200 or HTTP error)
     * must surface exactly as a Turbosms failure does — the generic 503, one provider call, no retry, nothing secret
     * or personal in the body or the log.
     */
    @ParameterizedTest(name = "SMSClub reply #{index}")
    @ValueSource(strings = {"200:{\"success_request\":{\"add_info\":{\"380509876543\":\"This number is in the black list\"}}}",
            "200:{\"success_request\":{\"add_info\":{\"src_addr\":\"Sender name is incorrect\"}}}",
            "401:{}", "429:{}", "453:{}", "503:provider down"})
    @DisplayName("guest phone-OTP + SMSClub refusal — generic 503, one request, no token/phone/code in body or log")
    void should_return503WithoutRetry_when_smsClubRefusesTheOtp(String reply) {
        int status = Integer.parseInt(reply.substring(0, 3));
        SMSCLUB.stubFor(post(urlEqualTo(SMSCLUB_PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(reply.substring(4))));

        ResponseEntity<String> resp = sendOtp(OTP_PHONE);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(resp.getBody()).contains(ServiceUnavailableMessages.OTP_SEND_FAILED)
                .doesNotContain("SMSClub").doesNotContain("reason=").doesNotContain(OTP_SUBSCRIBER_DIGITS);
        SMSCLUB.verify(1, postRequestedFor(urlEqualTo(SMSCLUB_PATH)));
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(logMessages())
                .anyMatch(line -> line.contains("OTP send failed") && line.contains("SmsDeliveryException"))
                .noneMatch(line -> line.contains(SMSCLUB_TOKEN))
                .noneMatch(line -> line.toLowerCase(java.util.Locale.ROOT).contains("black list"))
                .noneMatch(line -> line.contains("Sender name is incorrect"));
        assertNoPiiInLogs(OTP_PHONE, OTP_SUBSCRIBER_DIGITS);
    }

    @Test
    @DisplayName("smsclub mode — the SMSClub bearer token never appears in any log line of a successful walk-in send")
    void should_neverLogTheSmsClubToken_when_walkInConfirmationIsSent() {
        stubSuccessFor(E164_PHONE);

        createWalkIn(tomorrowAtNoon());

        SMSCLUB.verify(1, postRequestedFor(urlEqualTo(SMSCLUB_PATH)));
        assertThat(logMessages()).isNotEmpty()
                .anyMatch(line -> line.contains("status=OK") && line.contains("provider=smsclub"))
                .noneMatch(line -> line.contains(SMSCLUB_TOKEN))
                .noneMatch(line -> line.contains("Bearer"));
    }
}
