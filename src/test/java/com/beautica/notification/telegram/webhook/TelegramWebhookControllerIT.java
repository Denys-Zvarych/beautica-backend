package com.beautica.notification.telegram.webhook;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.telegram.link.TelegramLinkService;
import com.beautica.notification.telegram.link.TelegramLinkTestSupport;
import com.beautica.support.MutableTestClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.GUEST_PHONE;
import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.T0;
import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.SECRET;
import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.contact;
import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.membership;
import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 406 — {@code POST /api/v1/telegram/webhook} end to end against real Postgres: secret auth (D2), body cap and
 * chat-type filter (D3), reject throttle (D4), the conversation table and reply bodies (D5), status codes (D6) and
 * log hygiene (D7). Capture mode, so the flag is on with no Bot API credentials.
 */
@Import({TestSecurityConfig.class, TelegramLinkTestSupport.ClockConfig.class})
@TestPropertySource(properties = {
        TelegramLinkTestSupport.BOT_USERNAME_PROPERTY,
        TelegramWebhookTestSupport.ENABLED_PROPERTY,
        TelegramWebhookTestSupport.CAPTURE_MODE_PROPERTY,
        TelegramWebhookTestSupport.SECRET_PROPERTY})
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("TelegramWebhookController — integration (phase 406)")
class TelegramWebhookControllerIT extends AbstractIntegrationTest {

    private static final long CHAT = 7_123_456_789L;
    private static final String CONTACT_BARE = "380501234567";
    private static final String OTHER_CONTACT = "+380671112233";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TelegramLinkService linkService;
    @Autowired private MutableTestClock clock;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;

    private TelegramWebhookTestSupport webhook;
    private TelegramLinkTestSupport links;

    @BeforeEach
    void setUp() {
        clock.set(T0);
        webhook = new TelegramWebhookTestSupport(restTemplate);
        links = new TelegramLinkTestSupport(jdbcTemplate, passwordEncoder);
    }

    private String issueToken() {
        return TelegramLinkTestSupport.rawToken(
                linkService.issueForBooking(links.guestBooking(GUEST_PHONE), GUEST_PHONE));
    }

    private String tokenState(String rawToken) {
        return jdbcTemplate.queryForObject("SELECT state FROM telegram_link_tokens "
                + "WHERE token_hash = sha256(convert_to(?, 'UTF8'))", String.class, rawToken);
    }

    private int linkCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_links", Integer.class);
    }

    private String linkStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM telegram_links WHERE chat_id = ?", String.class, CHAT);
    }

    /** 200 + a single {@code sendMessage} to {@link #CHAT} with the exact D5 copy. */
    private JsonNode reply(ResponseEntity<String> response, String expectedText) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("method").asText()).isEqualTo("sendMessage");
        assertThat(body.path("chat_id").asLong()).isEqualTo(CHAT);
        assertThat(body.path("text").asText()).isEqualTo(expectedText);
        return body.path("reply_markup");
    }

    private static void assertShareKeyboard(JsonNode markup) {
        assertThat(markup.path("keyboard").get(0).get(0).path("text").asText()).isEqualTo("Поділитися номером");
        assertThat(markup.path("keyboard").get(0).get(0).path("request_contact").asBoolean()).isTrue();
        assertThat(markup.path("resize_keyboard").asBoolean()).isTrue();
        assertThat(markup.path("one_time_keyboard").asBoolean()).isTrue();
    }

    private static void assertUnlinkKeyboard(JsonNode markup) {
        assertThat(markup.path("keyboard").get(0).get(0).path("text").asText()).isEqualTo("Повернутися до SMS");
        assertThat(markup.path("keyboard").get(0).get(0).has("request_contact")).isFalse();
        assertThat(markup.path("resize_keyboard").asBoolean()).isTrue();
        assertThat(markup.path("is_persistent").asBoolean()).isTrue();
    }

    private static void assertRemoveKeyboard(JsonNode markup) {
        assertThat(markup.path("remove_keyboard").asBoolean()).isTrue();
    }

    /** start + own contact → ACTIVE link; returns the token. */
    private String linkChat() throws Exception {
        String token = issueToken();
        webhook.post(text(CHAT, "/start " + token));
        reply(webhook.post(contact(CHAT, CONTACT_BARE, CHAT)), TelegramBotReplies.LINKED);
        return token;
    }

    @Nested
    @DisplayName("authentication and input limits (D2, D3, D6)")
    class Transport {

        @Test
        @DisplayName("return 401 with empty body when secret missing")
        void should_return401WithEmptyBody_when_secretMissing() {
            ResponseEntity<String> response = webhook.post(text(CHAT, "/help"), null, "198.51.100.10");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNull();
        }

        @Test
        @DisplayName("return 401 without db write when secret wrong")
        void should_return401WithoutDbWrite_when_secretWrong() {
            String token = issueToken();

            ResponseEntity<String> response =
                    webhook.post(text(CHAT, "/start " + token), SECRET + "x", "198.51.100.11");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNull();
            assertThat(tokenState(token)).isEqualTo("ISSUED");
        }

        @Test
        @DisplayName("return 200 when secret correct")
        void should_return200_when_secretCorrect() throws Exception {
            reply(webhook.post(text(CHAT, "/help")), TelegramBotReplies.HELP);
        }

        @Test
        @DisplayName("return 413 when body over 64 KB")
        void should_return413_when_bodyOver64Kb() {
            String oversized = text(CHAT, "x".repeat(TelegramWebhookController.MAX_BODY_BYTES));

            ResponseEntity<String> response = webhook.post(oversized);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        }

        @Test
        @DisplayName("return 200 no op when group chat")
        void should_return200NoOp_when_groupChat() {
            String token = issueToken();
            String group = """
                    {"update_id":9,"message":{"chat":{"id":-1001234567890,"type":"supergroup"},
                     "from":{"id":%d},"text":"/start %s"}}""".formatted(CHAT, token);

            ResponseEntity<String> response = webhook.post(group);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNull();
            assertThat(tokenState(token)).isEqualTo("ISSUED");
        }

        @Test
        @DisplayName("return 200 no op when body malformed or unsupported")
        void should_return200NoOp_when_bodyMalformedOrUnsupported() {
            ResponseEntity<String> malformed = webhook.post("{not json");
            ResponseEntity<String> unsupported = webhook.post("{\"update_id\":10,\"edited_message\":{}}");

            assertThat(malformed.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(malformed.getBody()).isNull();
            assertThat(unsupported.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(unsupported.getBody()).isNull();
        }

        @Test
        @DisplayName("return 429 when 61 bad secret requests from one ip")
        void should_return429_when_61BadSecretRequestsFromOneIp() {
            String ip = "203.0.113.61";
            for (int i = 0; i < 60; i++) {
                assertThat(webhook.post(text(CHAT, "/help"), "wrong", ip).getStatusCode())
                        .isEqualTo(HttpStatus.UNAUTHORIZED);
            }

            ResponseEntity<String> sixtyFirst = webhook.post(text(CHAT, "/help"), "wrong", ip);
            ResponseEntity<String> genuine = webhook.post(text(CHAT, "/help"), SECRET, ip);

            assertThat(sixtyFirst.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(genuine.getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("not count valid secret requests when throttling")
        void should_notCountValidSecretRequests_when_throttling() {
            String ip = "203.0.113.62";
            for (int i = 0; i < 70; i++) {
                webhook.post(text(CHAT, "/help"), SECRET, ip);
            }

            ResponseEntity<String> firstBad = webhook.post(text(CHAT, "/help"), "wrong", ip);

            assertThat(firstBad.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("conversation (D5)")
    class Conversation {

        @Test
        @DisplayName("ask for phone and claim without link when start with valid token")
        void should_askForPhoneAndClaimWithoutLink_when_startWithValidToken() throws Exception {
            String token = issueToken();

            JsonNode markup = reply(webhook.post(text(CHAT, "/start " + token)), TelegramBotReplies.ASK_PHONE);

            assertShareKeyboard(markup);
            assertThat(tokenState(token)).isEqualTo("CLAIMED");
            assertThat(linkCount()).isZero();
        }

        @Test
        @DisplayName("link active when own contact matches booking phone")
        void should_linkActive_when_ownContactMatchesBookingPhone() throws Exception {
            String token = issueToken();
            webhook.post(text(CHAT, "/start " + token));

            JsonNode markup = reply(webhook.post(contact(CHAT, CONTACT_BARE, CHAT)), TelegramBotReplies.LINKED);

            assertUnlinkKeyboard(markup);
            assertThat(linkStatus()).isEqualTo("ACTIVE");
            assertThat(tokenState(token)).isEqualTo("LINKED");
        }

        @Test
        @DisplayName("keep one row when contact update replayed")
        void should_keepOneRow_when_contactUpdateReplayed() throws Exception {
            linkChat();

            ResponseEntity<String> replay = webhook.post(contact(CHAT, CONTACT_BARE, CHAT));

            assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(linkCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("reply mismatch without link when contact phone differs")
        void should_replyMismatchWithoutLink_when_contactPhoneDiffers() throws Exception {
            String token = issueToken();
            webhook.post(text(CHAT, "/start " + token));

            JsonNode markup = reply(webhook.post(contact(CHAT, OTHER_CONTACT, CHAT)), TelegramBotReplies.MISMATCH);

            assertRemoveKeyboard(markup);
            assertThat(linkCount()).isZero();
            assertThat(tokenState(token)).isEqualTo("MISMATCH");
        }

        @Test
        @DisplayName("ask own contact without link when contact user id is not sender")
        void should_askOwnContactWithoutLink_when_contactUserIdIsNotSender() throws Exception {
            String token = issueToken();
            webhook.post(text(CHAT, "/start " + token));

            JsonNode markup = reply(webhook.post(contact(CHAT, CONTACT_BARE, CHAT + 1)),
                    TelegramBotReplies.NOT_OWN_CONTACT);

            assertShareKeyboard(markup);
            assertThat(linkCount()).isZero();
            assertThat(tokenState(token)).isEqualTo("CLAIMED");
        }

        @Test
        @DisplayName("reply help when contact without claim")
        void should_replyHelp_when_contactWithoutClaim() throws Exception {
            JsonNode markup = reply(webhook.post(contact(CHAT, CONTACT_BARE, CHAT)), TelegramBotReplies.HELP);

            assertRemoveKeyboard(markup);
            assertThat(linkCount()).isZero();
        }

        @Test
        @DisplayName("reply invalid when start with expired token")
        void should_replyInvalid_when_startWithExpiredToken() throws Exception {
            String token = issueToken();
            clock.advance(Duration.ofMinutes(31));

            JsonNode markup = reply(webhook.post(text(CHAT, "/start " + token)), TelegramBotReplies.INVALID_LINK);

            assertRemoveKeyboard(markup);
            assertThat(tokenState(token)).isEqualTo("ISSUED");
        }

        @Test
        @DisplayName("reply linked when start with linked token from same chat")
        void should_replyLinked_when_startWithLinkedTokenFromSameChat() throws Exception {
            String token = linkChat();

            JsonNode markup = reply(webhook.post(text(CHAT, "/start " + token)), TelegramBotReplies.LINKED);

            assertUnlinkKeyboard(markup);
        }

        @Test
        @DisplayName("reactivate and reply linked when start retapped while blocked")
        void should_reactivateAndReplyLinked_when_startRetappedWhileBlocked() throws Exception {
            String token = linkChat();
            webhook.post(membership(CHAT, "kicked"));

            reply(webhook.post(text(CHAT, "/start " + token)), TelegramBotReplies.LINKED);

            assertThat(linkStatus()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("delete link when unlink button text")
        void should_deleteLink_when_unlinkButtonText() throws Exception {
            linkChat();

            JsonNode markup = reply(webhook.post(text(CHAT, "Повернутися до SMS")), TelegramBotReplies.STOPPED);

            assertRemoveKeyboard(markup);
            assertThat(linkCount()).isZero();
        }

        @Test
        @DisplayName("delete link when stop command")
        void should_deleteLink_when_stopCommand() throws Exception {
            linkChat();

            JsonNode markup = reply(webhook.post(text(CHAT, "/stop")), TelegramBotReplies.STOPPED);

            assertRemoveKeyboard(markup);
            assertThat(linkCount()).isZero();
        }

        @Test
        @DisplayName("reply help keeping keyboard when bare start or other text")
        void should_replyHelpKeepingKeyboard_when_bareStartOrOtherText() throws Exception {
            JsonNode bare = reply(webhook.post(text(CHAT, "/start")), TelegramBotReplies.HELP);
            JsonNode other = reply(webhook.post(text(CHAT, "добрий день")), TelegramBotReplies.HELP);

            assertThat(bare.isMissingNode()).isTrue();
            assertThat(other.isMissingNode()).isTrue();
        }

        @Test
        @DisplayName("mark blocked without reply when kicked")
        void should_markBlockedWithoutReply_when_kicked() throws Exception {
            linkChat();

            ResponseEntity<String> response = webhook.post(membership(CHAT, "kicked"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNull();
            assertThat(linkStatus()).isEqualTo("BLOCKED");
        }

        @Test
        @DisplayName("reactivate when member after kicked")
        void should_reactivate_when_memberAfterKicked() throws Exception {
            linkChat();
            webhook.post(membership(CHAT, "kicked"));

            ResponseEntity<String> response = webhook.post(membership(CHAT, "member"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNull();
            assertThat(linkStatus()).isEqualTo("ACTIVE");
        }

        @Test
        @DisplayName("no op when member without link")
        void should_noOp_when_memberWithoutLink() {
            ResponseEntity<String> response = webhook.post(membership(CHAT, "member"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(linkCount()).isZero();
        }
    }

    @Nested
    @DisplayName("logging (D7)")
    class Logging {

        @Test
        @DisplayName("log no token phone chat id or text when full flow runs")
        void should_logNoTokenPhoneChatIdOrText_when_fullFlowRuns(CapturedOutput output) throws Exception {
            String token = issueToken();
            webhook.post(text(CHAT, "/start " + token));
            webhook.post(contact(CHAT, OTHER_CONTACT, CHAT));
            String second = issueToken();
            webhook.post(text(CHAT, "/start " + second));
            webhook.post(contact(CHAT, CONTACT_BARE, CHAT + 1));
            webhook.post(contact(CHAT, CONTACT_BARE, CHAT));
            webhook.post(membership(CHAT, "kicked"));
            webhook.post(membership(CHAT, "member"));
            webhook.post(text(CHAT, "добрий день"));
            webhook.post(text(CHAT, "/stop"));
            // The reject summary is once per minute for the whole endpoint and this context's clock is shared:
            // move past any window an earlier test opened so this rejection is the one that writes the line.
            clock.advance(Duration.ofHours(2));
            webhook.post(text(CHAT, "/help"), "wrong-" + SECRET, "198.51.100.99");

            String logs = output.getAll();

            assertThat(logs).contains("event=telegram_update type=start result=CLAIMED")
                    .contains("event=telegram_update type=contact result=LINKED")
                    .contains("event=telegram_update type=stop result=UNLINKED")
                    .contains("event=telegram_webhook_rejected rejected=");
            assertThat(logs).doesNotContain(token).doesNotContain(second)
                    .doesNotContain(CONTACT_BARE).doesNotContain("671112233")
                    .doesNotContain(Long.toString(CHAT)).doesNotContain(Long.toString(CHAT + 1))
                    .doesNotContain("добрий день").doesNotContain(SECRET);
        }
    }
}
