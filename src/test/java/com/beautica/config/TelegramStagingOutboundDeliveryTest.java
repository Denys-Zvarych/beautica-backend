package com.beautica.config;

import com.beautica.notification.telegram.TelegramBotApiClient;
import com.beautica.notification.telegram.TelegramDeliveryException;
import com.beautica.notification.telegram.TelegramMessenger;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 432 D3, outbound, end to end through the REAL {@link TelegramConfig} wiring and the REAL
 * {@link TelegramBotApiClient} against a WireMock Bot API. {@code AllowListTelegramMessengerTest} proves the decorator
 * with a mock delegate; this proves what the context actually registers: a chat that is not allow-listed on staging
 * never produces an HTTP request (and fails with the {@link TelegramDeliveryException} the router (408) maps to the SMS
 * fallback), a listed chat is sent, and prod is not wrapped at all.
 */
@DisplayName("Phase 432 — staging tester allow-list against a WireMock Bot API (real TelegramConfig wiring)")
class TelegramStagingOutboundDeliveryTest {

    private static final long LISTED_CHAT = 111_222_333L;
    private static final long STRANGER_CHAT = 444_555_666L;
    private static final String TEXT = "Нагадування: манікюр завтра о 10:00";
    private static final String OK_BODY = "{\"ok\":true,\"result\":{\"message_id\":1}}";
    private static final String SEND_PATH = "/bot" + TelegramConfigTest.TOKEN + "/sendMessage";

    private WireMockServer botApi;
    private ApplicationContextRunner runner;

    @BeforeEach
    void setUp() {
        botApi = new WireMockServer(options().dynamicPort());
        botApi.start();
        botApi.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(OK_BODY)));
        runner = new ApplicationContextRunner()
                .withUserConfiguration(TelegramConfigTest.TelegramDependencies.class, TelegramConfig.class)
                .withPropertyValues(TelegramConfigTest.VALID_LIVE)
                .withPropertyValues("app.telegram.api-base-url=http://localhost:" + botApi.port(),
                        "app.telegram.staging-allowed-chat-ids=" + LISTED_CHAT);
    }

    @AfterEach
    void tearDown() {
        botApi.stop();
    }

    private ApplicationContextRunner withProfile(String profile) {
        return runner.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile));
    }

    @Test
    @DisplayName("staging, chat not on the list → TelegramDeliveryException (SMS fallback signal) and ZERO requests reach the Bot API")
    void should_makeNoHttpCallAndThrowDelivery_when_stagingChatNotAllowListed() {
        withProfile("staging").run(context -> {
            TelegramMessenger messenger = context.getBean(TelegramMessenger.class);

            assertThatThrownBy(() -> messenger.send(STRANGER_CHAT, TEXT))
                    .isExactlyInstanceOf(TelegramDeliveryException.class)
                    .message().doesNotContain(Long.toString(STRANGER_CHAT)).doesNotContain(TEXT);
            botApi.verify(0, anyRequestedFor(anyUrl()));
        });
    }

    @Test
    @DisplayName("staging, listed tester chat → exactly one sendMessage to the Bot API carrying that chat id")
    void should_sendOnce_when_stagingChatAllowListed() {
        withProfile("staging").run(context -> {
            context.getBean(TelegramMessenger.class).send(LISTED_CHAT, TEXT);

            botApi.verify(1, postRequestedFor(urlEqualTo(SEND_PATH))
                    .withRequestBody(matchingJsonPath("$.chat_id", equalTo(Long.toString(LISTED_CHAT)))));
            botApi.verify(1, anyRequestedFor(anyUrl()));
        });
    }

    @Test
    @DisplayName("staging, a stranger then a tester → only the tester's request reaches the Bot API (the gate is per chat, not latched)")
    void should_sendOnlyListedChat_when_strangerThenTester() {
        withProfile("staging").run(context -> {
            TelegramMessenger messenger = context.getBean(TelegramMessenger.class);

            assertThatThrownBy(() -> messenger.send(STRANGER_CHAT, TEXT)).isInstanceOf(TelegramDeliveryException.class);
            messenger.send(LISTED_CHAT, TEXT);

            botApi.verify(1, anyRequestedFor(anyUrl()));
            botApi.verify(0, postRequestedFor(urlEqualTo(SEND_PATH))
                    .withRequestBody(matchingJsonPath("$.chat_id", equalTo(Long.toString(STRANGER_CHAT)))));
        });
    }

    @Test
    @DisplayName("beautica.environment=staging WITHOUT the staging profile → stranger still gets no HTTP call (fail-closed path)")
    void should_makeNoHttpCall_when_stagingEnvironmentWithoutProfile() {
        runner.withPropertyValues("beautica.environment=staging").run(context -> {
            TelegramMessenger messenger = context.getBean(TelegramMessenger.class);

            assertThatThrownBy(() -> messenger.send(STRANGER_CHAT, TEXT)).isInstanceOf(TelegramDeliveryException.class);
            botApi.verify(0, anyRequestedFor(anyUrl()));
        });
    }

    @Test
    @DisplayName("prod (list set but ignored) → bare TelegramBotApiClient, a chat outside the staging list IS delivered")
    void should_deliverToAnyChat_when_prodClientNotWrapped() {
        withProfile("prod").run(context -> {
            TelegramMessenger messenger = context.getBean(TelegramMessenger.class);
            assertThat(messenger).as("prod must not be wrapped, actual=%s", messenger.getClass())
                    .isExactlyInstanceOf(TelegramBotApiClient.class);

            messenger.send(STRANGER_CHAT, TEXT);

            botApi.verify(1, postRequestedFor(urlEqualTo(SEND_PATH))
                    .withRequestBody(matchingJsonPath("$.chat_id", equalTo(Long.toString(STRANGER_CHAT)))));
        });
    }
}
