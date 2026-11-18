package com.beautica.config;

import com.beautica.notification.sms.OtpSmsSender;
import com.beautica.notification.sms.RecipientInvalidSpikeAlarm;
import com.beautica.notification.sms.SmsDeliveryAmbiguousException;
import com.beautica.notification.sms.SmsDeliveryException;
import com.beautica.notification.sms.StagingSmsDailyCap;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;

/**
 * B520 audit-fix — the {@code smsclub} {@link OtpSmsSender} bean ({@link SmsConfig#smsClubOtpSmsSender}) against a
 * WireMock SMSClub: the staff invite goes out with the invite lifetime (a day, not the 10-min OTP one) and the invite
 * timeouts; provider outages surface as transient {@link SmsDeliveryException}s (the outbox breaker counts them),
 * refusals do not.
 */
@DisplayName("SMSClub staff invite sender — invite lifetime, invite timeouts, transient failures (B520)")
class SmsClubStaffInviteSenderTest {

    private static final String PATH = "/sms/send";
    private static final String PHONE = "+380671234567";
    private static final String SUCCESS = "{\"success_request\":{\"info\":{\"106\":\"380671234567\"}}}";

    private WireMockServer wireMock;
    private SmsClubProperties properties;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        properties = new SmsClubProperties();
        properties.setToken("smsclub-test-token");
        properties.setBaseUrl(wireMock.baseUrl() + PATH);
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @SuppressWarnings("unchecked")
    private OtpSmsSender sender() {
        return new SmsConfig().smsClubOtpSmsSender(RestClient.builder(), properties,
                mock(ObjectProvider.class, invocation -> null), spikeAlarm());
    }

    private RecipientInvalidSpikeAlarm spikeAlarm() {
        return new RecipientInvalidSpikeAlarm(java.time.Clock.systemUTC(),
                properties.getRecipientInvalidSpikeThreshold(), properties.getRecipientInvalidSpikeWindow());
    }

    private void stub(int status, String body) {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    @DisplayName("the staff invite is sent with lifetime 1440 min (≥ 24 h); the OTP keeps its 10-min lifetime")
    void should_sendInviteWithDayLongLifetime_when_sendingStaffInvite() {
        stub(200, SUCCESS);
        OtpSmsSender sender = sender();

        sender.sendStaffInvite(PHONE, "invite text");
        sender.send(PHONE, "otp text");

        assertThat(properties.getInviteLifetimeMinutes()).isGreaterThanOrEqualTo(24 * 60);
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.message", equalTo("invite text")))
                .withRequestBody(matchingJsonPath("$.lifetime", equalTo("1440"))));
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.message", equalTo("otp text")))
                .withRequestBody(matchingJsonPath("$.lifetime", equalTo("10"))));
    }

    @Test
    @Timeout(5)
    @DisplayName("the invite uses its own (shorter) read timeout: a stalled provider fails it fast, as transient")
    void should_failFastAsTransient_when_providerStallsBeyondInviteReadTimeout() {
        properties.setInviteReadTimeout(Duration.ofMillis(300));
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withFixedDelay(2_000).withBody(SUCCESS)));

        var thrown = catchThrowableOfType(() -> sender().sendStaffInvite(PHONE, "invite text"),
                SmsDeliveryAmbiguousException.class);

        assertThat(thrown.isTransient()).isTrue();
    }

    @Test
    @DisplayName("a connection reset (provider unreachable) is a transient failure")
    void should_markTransient_when_connectionFails() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        var thrown = catchThrowableOfType(() -> sender().sendStaffInvite(PHONE, "invite text"),
                SmsDeliveryException.class);

        assertThat(thrown.isTransient()).isTrue();
    }

    @ParameterizedTest(name = "HTTP {0} -> transient={1}")
    @CsvSource({"503, true", "500, true", "429, true", "400, false", "401, false"})
    @DisplayName("5xx / 429 are transient (provider outage); a 4xx refusal is not")
    void should_classifyHttpFailure_when_providerAnswersError(int status, boolean expectedTransient) {
        stub(status, "{}");

        var thrown = catchThrowableOfType(() -> sender().sendStaffInvite(PHONE, "invite text"),
                SmsDeliveryException.class);

        assertThat(thrown.isTransient()).isEqualTo(expectedTransient);
    }

    @Test
    @DisplayName("a staging daily cap (when present) is shared by the OTP and the invite instance")
    void should_shareStagingCap_when_capBeanPresent() {
        stub(200, SUCCESS);
        StagingSmsDailyCap cap = new StagingSmsDailyCap(java.time.Clock.systemUTC(), 1);
        @SuppressWarnings("unchecked")
        ObjectProvider<StagingSmsDailyCap> provider = mock(ObjectProvider.class, invocation -> cap);
        OtpSmsSender sender = new SmsConfig().smsClubOtpSmsSender(RestClient.builder(), properties, provider,
                spikeAlarm());
        sender.send(PHONE, "otp text");

        var thrown = catchThrowableOfType(() -> sender.sendStaffInvite(PHONE, "invite text"),
                SmsDeliveryException.class);

        assertThat(thrown).isNotNull();
        assertThat(thrown.isTransient()).isFalse();
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
        wireMock.verify(0, postRequestedFor(urlEqualTo(PATH))
                .withRequestBody(matchingJsonPath("$.message", equalTo("invite text"))));
    }
}
