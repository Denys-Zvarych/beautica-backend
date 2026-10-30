package com.beautica.notification.telegram.webhook;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.telegram.link.LinkStatus;
import com.beautica.notification.telegram.link.TelegramLinkService;
import com.beautica.notification.telegram.link.TelegramLinkTestSupport;
import com.beautica.support.MutableTestClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;

import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.GUEST_PHONE;
import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.T0;
import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.SECRET;
import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.chatMessage;
import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.contact;
import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.membership;
import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 406 QA — whole guest conversations and abuse cases over the real HTTP endpoint and real Postgres, with the
 * start token issued by {@link TelegramLinkService#issueForBooking} (405) and the outcome read through
 * {@link TelegramLinkService#statusForPhone} (the service behind 407's status API) as well as the rows.
 * Same context configuration as {@code TelegramWebhookControllerIT}, so the cached Spring context is reused.
 */
@Import({TestSecurityConfig.class, TelegramLinkTestSupport.ClockConfig.class})
@TestPropertySource(properties = {
        TelegramLinkTestSupport.BOT_USERNAME_PROPERTY,
        TelegramWebhookTestSupport.ENABLED_PROPERTY,
        TelegramWebhookTestSupport.CAPTURE_MODE_PROPERTY,
        TelegramWebhookTestSupport.SECRET_PROPERTY})
@DisplayName("TelegramWebhookController — end-to-end conversations and abuse cases (phase 406 QA)")
class TelegramWebhookConversationIT extends AbstractIntegrationTest {

    private static final long GUEST_CHAT = 7_400_000_001L;
    private static final long INTRUDER_CHAT = 7_400_000_002L;
    /** The guest's booking phone as Telegram sends it in a contact (no {@code +}). */
    private static final String GUEST_CONTACT = "380501234567";

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

    private Long tokenClaimedBy(String rawToken) {
        return jdbcTemplate.queryForObject("SELECT claimed_chat_id FROM telegram_link_tokens "
                + "WHERE token_hash = sha256(convert_to(?, 'UTF8'))", Long.class, rawToken);
    }

    private int linkCount() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_links", Integer.class);
    }

    private String linkStatusOf(long chatId) {
        return jdbcTemplate.queryForObject("SELECT status FROM telegram_links WHERE chat_id = ?", String.class, chatId);
    }

    /** 200 + exactly one {@code sendMessage} to {@code chatId} with the D5 copy; returns its {@code reply_markup}. */
    private JsonNode reply(ResponseEntity<String> response, long chatId, String expectedText) throws Exception {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("method").asText()).isEqualTo("sendMessage");
        assertThat(body.path("chat_id").asLong()).isEqualTo(chatId);
        assertThat(body.path("text").asText()).isEqualTo(expectedText);
        assertThat(body.toString()).as("no reply ever carries the booking phone")
                .doesNotContain(GUEST_CONTACT).doesNotContain("501234567");
        return body.path("reply_markup");
    }

    private static void assertNoReply(ResponseEntity<String> response) {
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNull();
    }

    private static void assertShareKeyboard(JsonNode markup) {
        assertThat(markup.path("keyboard").get(0).get(0).path("text").asText()).isEqualTo("Поділитися номером");
        assertThat(markup.path("keyboard").get(0).get(0).path("request_contact").asBoolean()).isTrue();
        assertThat(markup.path("one_time_keyboard").asBoolean()).isTrue();
    }

    private static void assertUnlinkKeyboard(JsonNode markup) {
        assertThat(markup.path("keyboard").get(0).get(0).path("text").asText()).isEqualTo("Повернутися до SMS");
        assertThat(markup.path("is_persistent").asBoolean()).isTrue();
    }

    private static void assertRemoveKeyboard(JsonNode markup) {
        assertThat(markup.path("remove_keyboard").asBoolean()).isTrue();
    }

    /** /start + own contact from {@link #GUEST_CHAT} → ACTIVE link; returns the token. */
    private String linkGuest() throws Exception {
        String token = issueToken();
        reply(webhook.post(text(GUEST_CHAT, "/start " + token)), GUEST_CHAT, TelegramBotReplies.ASK_PHONE);
        reply(webhook.post(contact(GUEST_CHAT, GUEST_CONTACT, GUEST_CHAT)), GUEST_CHAT, TelegramBotReplies.LINKED);
        assertThat(linkService.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.ACTIVE);
        return token;
    }

    @Nested
    @DisplayName("full conversations")
    class FullConversation {

        @Test
        @DisplayName("start with an issued token, share own phone, then /stop: linked, then back to SMS")
        void should_linkThenUnlink_when_guestStartsSharesOwnPhoneThenStops() throws Exception {
            String token = issueToken();

            JsonNode askPhone = reply(webhook.post(text(GUEST_CHAT, "/start " + token)),
                    GUEST_CHAT, TelegramBotReplies.ASK_PHONE);
            assertShareKeyboard(askPhone);
            assertThat(tokenState(token)).as("claimed only, not yet linked").isEqualTo("CLAIMED");
            assertThat(linkService.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.NOT_LINKED);

            JsonNode linked = reply(webhook.post(contact(GUEST_CHAT, GUEST_CONTACT, GUEST_CHAT)),
                    GUEST_CHAT, TelegramBotReplies.LINKED);
            assertUnlinkKeyboard(linked);
            assertThat(tokenState(token)).isEqualTo("LINKED");
            assertThat(linkStatusOf(GUEST_CHAT)).isEqualTo("ACTIVE");
            assertThat(linkService.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.ACTIVE);
            assertThat(linkService.findActiveForPhone(GUEST_PHONE))
                    .hasValueSatisfying(active -> assertThat(active.chatId()).isEqualTo(GUEST_CHAT));

            JsonNode stopped = reply(webhook.post(text(GUEST_CHAT, "/stop")), GUEST_CHAT, TelegramBotReplies.STOPPED);
            assertRemoveKeyboard(stopped);
            assertThat(linkCount()).isZero();
            assertThat(linkService.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.NOT_LINKED);
        }

        @Test
        @DisplayName("blocking the bot marks the link BLOCKED; re-tapping the deep link re-links it")
        void should_markBlockedThenRelink_when_kickedThenStartRetapped() throws Exception {
            String token = linkGuest();

            assertNoReply(webhook.post(membership(GUEST_CHAT, "kicked")));
            assertThat(linkStatusOf(GUEST_CHAT)).isEqualTo("BLOCKED");
            assertThat(linkService.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.BLOCKED);
            assertThat(linkService.findActiveForPhone(GUEST_PHONE)).as("no send to a blocked chat").isEmpty();

            JsonNode relinked = reply(webhook.post(text(GUEST_CHAT, "/start " + token)),
                    GUEST_CHAT, TelegramBotReplies.LINKED);

            assertUnlinkKeyboard(relinked);
            assertThat(linkCount()).isEqualTo(1);
            assertThat(linkService.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.ACTIVE);
        }

        @Test
        @DisplayName("blocking then unblocking the bot (member) restores the ACTIVE link without a reply")
        void should_restoreActive_when_kickedThenMemberAgain() throws Exception {
            linkGuest();
            webhook.post(membership(GUEST_CHAT, "kicked"));

            assertNoReply(webhook.post(membership(GUEST_CHAT, "member")));

            assertThat(linkService.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.ACTIVE);
        }

        @Test
        @DisplayName("a blocked chat sending /start with a non-token payload stays BLOCKED and gets the invalid copy")
        void should_stayBlocked_when_blockedChatStartsWithMalformedPayload() throws Exception {
            linkGuest();
            webhook.post(membership(GUEST_CHAT, "kicked"));

            JsonNode markup = reply(webhook.post(text(GUEST_CHAT, "/start not-a-token")),
                    GUEST_CHAT, TelegramBotReplies.INVALID_LINK);

            assertRemoveKeyboard(markup);
            assertThat(linkStatusOf(GUEST_CHAT)).isEqualTo("BLOCKED");
        }

        @Test
        @DisplayName("after sharing someone else's contact the guest can still link by sharing their own")
        void should_linkOnOwnContact_when_foreignContactSharedFirst() throws Exception {
            String token = issueToken();
            webhook.post(text(GUEST_CHAT, "/start " + token));
            reply(webhook.post(contact(GUEST_CHAT, GUEST_CONTACT, INTRUDER_CHAT)),
                    GUEST_CHAT, TelegramBotReplies.NOT_OWN_CONTACT);

            reply(webhook.post(contact(GUEST_CHAT, GUEST_CONTACT, GUEST_CHAT)), GUEST_CHAT, TelegramBotReplies.LINKED);

            assertThat(tokenState(token)).isEqualTo("LINKED");
            assertThat(linkService.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("abuse cases")
    class Abuse {

        @Test
        @DisplayName("another person's contact card carrying the booking phone is refused and links nothing")
        void should_replyNotOwnContactWithoutLink_when_contactBelongsToAnotherUser() throws Exception {
            String token = issueToken();
            webhook.post(text(GUEST_CHAT, "/start " + token));

            JsonNode markup = reply(webhook.post(contact(GUEST_CHAT, GUEST_CONTACT, INTRUDER_CHAT)),
                    GUEST_CHAT, TelegramBotReplies.NOT_OWN_CONTACT);

            assertShareKeyboard(markup);
            assertThat(linkCount()).isZero();
            assertThat(tokenState(token)).as("claim kept for a retry").isEqualTo("CLAIMED");
            assertThat(linkService.statusForPhone(GUEST_PHONE)).isEqualTo(LinkStatus.NOT_LINKED);
        }

        @Test
        @DisplayName("a second chat replaying a token another chat already claimed gets the invalid copy")
        void should_replyInvalidAndKeepClaim_when_otherChatStartsClaimedToken() throws Exception {
            String token = issueToken();
            webhook.post(text(GUEST_CHAT, "/start " + token));

            JsonNode markup = reply(webhook.post(text(INTRUDER_CHAT, "/start " + token)),
                    INTRUDER_CHAT, TelegramBotReplies.INVALID_LINK);

            assertRemoveKeyboard(markup);
            assertThat(tokenState(token)).isEqualTo("CLAIMED");
            assertThat(tokenClaimedBy(token)).isEqualTo(GUEST_CHAT);
        }

        @Test
        @DisplayName("a second chat sharing the booking phone without its own claim links nothing")
        void should_replyHelpWithoutLink_when_otherChatSharesBookingPhoneWithoutClaim() throws Exception {
            String token = issueToken();
            webhook.post(text(GUEST_CHAT, "/start " + token));
            webhook.post(text(INTRUDER_CHAT, "/start " + token));

            JsonNode markup = reply(webhook.post(contact(INTRUDER_CHAT, GUEST_CONTACT, INTRUDER_CHAT)),
                    INTRUDER_CHAT, TelegramBotReplies.HELP);

            assertRemoveKeyboard(markup);
            assertThat(linkCount()).isZero();
            assertThat(tokenState(token)).isEqualTo("CLAIMED");
        }

        @Test
        @DisplayName("a second chat replaying a token already linked elsewhere cannot take over the link")
        void should_keepOriginalLink_when_otherChatStartsLinkedToken() throws Exception {
            String token = linkGuest();

            reply(webhook.post(text(INTRUDER_CHAT, "/start " + token)), INTRUDER_CHAT, TelegramBotReplies.INVALID_LINK);
            webhook.post(contact(INTRUDER_CHAT, GUEST_CONTACT, INTRUDER_CHAT));

            assertThat(linkCount()).isEqualTo(1);
            assertThat(linkService.findActiveForPhone(GUEST_PHONE))
                    .hasValueSatisfying(active -> assertThat(active.chatId()).isEqualTo(GUEST_CHAT));
        }

        @Test
        @DisplayName("/stop or a kick from a different chat never touches the guest's link")
        void should_keepGuestLink_when_otherChatStopsOrKicks() throws Exception {
            linkGuest();

            reply(webhook.post(text(INTRUDER_CHAT, "/stop")), INTRUDER_CHAT, TelegramBotReplies.STOPPED);
            assertNoReply(webhook.post(membership(INTRUDER_CHAT, "kicked")));

            assertThat(linkStatusOf(GUEST_CHAT)).isEqualTo("ACTIVE");
        }

        @ParameterizedTest(name = "chat type {0}")
        @ValueSource(strings = {"group", "supergroup", "channel"})
        @DisplayName("a non-private chat with the guest's chat id is ignored: no reply, no unlink, no block, no claim")
        void should_ignoreWithoutDbWrite_when_chatIsNotPrivate(String chatType) throws Exception {
            String token = linkGuest();
            String second = issueToken();

            assertNoReply(webhook.post(chatMessage(GUEST_CHAT, chatType, GUEST_CHAT, "/stop")));
            assertNoReply(webhook.post(chatMessage(GUEST_CHAT, chatType, GUEST_CHAT, "/start " + second)));
            assertNoReply(webhook.post(membership(GUEST_CHAT, chatType, "kicked")));

            assertThat(linkStatusOf(GUEST_CHAT)).isEqualTo("ACTIVE");
            assertThat(tokenState(token)).isEqualTo("LINKED");
            assertThat(tokenState(second)).isEqualTo("ISSUED");
        }

        @Test
        @DisplayName("a wrong secret of the right length is refused 401 and writes nothing")
        void should_return401WithoutClaim_when_secretSameLengthButWrong() {
            String token = issueToken();
            char last = SECRET.charAt(SECRET.length() - 1);
            String forged = SECRET.substring(0, SECRET.length() - 1) + (last == 'a' ? 'b' : 'a');

            ResponseEntity<String> response = webhook.post(text(GUEST_CHAT, "/start " + token), forged, "198.51.100.40");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNull();
            assertThat(tokenState(token)).isEqualTo("ISSUED");
        }

        @ParameterizedTest(name = "secret header {0}")
        @ValueSource(strings = {"missing", "wrong"})
        @DisplayName("a missing or wrong secret is refused 401 before the body is read: an oversized body is not 413")
        void should_return401NotPayloadTooLarge_when_secretBadAndBodyOversized(String variant) {
            String oversized = text(GUEST_CHAT, "x".repeat(TelegramWebhookController.MAX_BODY_BYTES));
            String secret = "missing".equals(variant) ? null : "wrong-" + SECRET;

            ResponseEntity<String> response = webhook.post(oversized, secret, "198.51.100.41");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody()).isNull();
        }

        @Test
        @DisplayName("a body of exactly 64 KB is accepted and a body one byte larger is 413")
        void should_acceptExactly64KbAndRefuseOneByteMore_when_bodyAtCap() throws Exception {
            String help = text(GUEST_CHAT, "/help");
            int padding = TelegramWebhookController.MAX_BODY_BYTES - help.getBytes(StandardCharsets.UTF_8).length;
            String atCap = help + " ".repeat(padding);
            String overCap = atCap + " ";
            assertThat(atCap.getBytes(StandardCharsets.UTF_8)).hasSize(TelegramWebhookController.MAX_BODY_BYTES);

            reply(webhook.post(atCap), GUEST_CHAT, TelegramBotReplies.HELP);
            assertThat(webhook.post(overCap).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        }

        @ParameterizedTest(name = "body {0}")
        @ValueSource(strings = {"", "null", "[]", "\"text\"", "42", "{\"message\":\"not-an-object\"}",
                "{\"message\":{\"chat\":{\"id\":\"abc\",\"type\":\"private\"}}}", "{\"update_id\":1"})
        @DisplayName("an authenticated body that is empty, malformed or the wrong JSON shape is a 200 no-op")
        void should_return200NoOp_when_bodyMalformedOrWrongShape(String body) {
            String token = issueToken();

            assertNoReply(webhook.post(body));

            assertThat(tokenState(token)).isEqualTo("ISSUED");
            assertThat(linkCount()).isZero();
        }

        @Test
        @DisplayName("the permitAll matcher is POST on the exact path: GET or a sub-path still needs a JWT")
        void should_requireAuthentication_when_otherMethodOrSubPath() {
            HttpHeaders headers = new HttpHeaders();
            headers.set(TelegramWebhookSecretVerifier.SECRET_HEADER, SECRET);
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<String> entity = new HttpEntity<>(text(GUEST_CHAT, "/help"), headers);

            ResponseEntity<String> get = restTemplate.exchange(
                    TelegramWebhookTestSupport.PATH, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            ResponseEntity<String> subPath = restTemplate.exchange(
                    TelegramWebhookTestSupport.PATH + "/extra", HttpMethod.POST, entity, String.class);

            assertThat(get.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(subPath.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }
}
