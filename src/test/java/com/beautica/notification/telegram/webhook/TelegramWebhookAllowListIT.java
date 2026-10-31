package com.beautica.notification.telegram.webhook;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.telegram.StagingTelegramChatAllowList;
import com.beautica.notification.telegram.TelegramChatAllowList;
import com.beautica.notification.telegram.link.TelegramLinkService;
import com.beautica.notification.telegram.link.TelegramLinkTestSupport;
import com.beautica.support.MutableTestClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.GUEST_PHONE;
import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.T0;
import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.contact;
import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.membership;
import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 406 × 432 D3 — the staging inbound gate: with the tester allow-list in place, an update from a chat that is
 * not listed is answered 200 with no reply, no claim, no proof and no DB write; a listed chat goes through.
 * The {@code telegramChatAllowList} bean is replaced by the staging implementation (test profile allows overriding).
 */
@Import({TestSecurityConfig.class, TelegramLinkTestSupport.ClockConfig.class,
        TelegramWebhookAllowListIT.StagingAllowListConfig.class})
@TestPropertySource(properties = {
        TelegramLinkTestSupport.BOT_USERNAME_PROPERTY,
        TelegramWebhookTestSupport.ENABLED_PROPERTY,
        TelegramWebhookTestSupport.CAPTURE_MODE_PROPERTY,
        TelegramWebhookTestSupport.SECRET_PROPERTY})
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("TelegramWebhookController — staging inbound allow-list (phase 406 / 432 D3)")
class TelegramWebhookAllowListIT extends AbstractIntegrationTest {

    private static final long LISTED_CHAT = 7_200_000_001L;
    private static final long STRANGER_CHAT = 7_299_999_999L;

    @TestConfiguration
    static class StagingAllowListConfig {
        @Bean
        TelegramChatAllowList telegramChatAllowList() {
            return new StagingTelegramChatAllowList(List.of(LISTED_CHAT));
        }
    }

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private TelegramLinkService linkService;
    @Autowired private MutableTestClock clock;
    @Autowired private PasswordEncoder passwordEncoder;

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

    @Test
    @DisplayName("return 200 without reply or claim when start from non listed chat")
    void should_return200WithoutReplyOrClaim_when_startFromNonListedChat(CapturedOutput output) {
        String token = issueToken();

        ResponseEntity<String> response = webhook.post(text(STRANGER_CHAT, "/start " + token));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNull();
        assertThat(tokenState(token)).isEqualTo("ISSUED");
        assertThat(output.getAll()).contains("event=telegram_update type=ignored result=not_allow_listed")
                .doesNotContain(Long.toString(STRANGER_CHAT));
    }

    @Test
    @DisplayName("write nothing when contact stop or membership from non listed chat")
    void should_writeNothing_when_contactStopOrMembershipFromNonListedChat() {
        String token = issueToken();
        webhook.post(text(LISTED_CHAT, "/start " + token));
        jdbcTemplate.update("UPDATE telegram_link_tokens SET claimed_chat_id = ? "
                + "WHERE token_hash = sha256(convert_to(?, 'UTF8'))", STRANGER_CHAT, token);

        ResponseEntity<String> share = webhook.post(contact(STRANGER_CHAT, "380501234567", STRANGER_CHAT));
        ResponseEntity<String> stop = webhook.post(text(STRANGER_CHAT, "/stop"));
        ResponseEntity<String> kicked = webhook.post(membership(STRANGER_CHAT, "kicked"));

        assertThat(share.getBody()).isNull();
        assertThat(stop.getBody()).isNull();
        assertThat(kicked.getBody()).isNull();
        assertThat(tokenState(token)).isEqualTo("CLAIMED");
        assertThat(linkCount()).isZero();
    }

    @Test
    @DisplayName("claim when start from listed chat")
    void should_claim_when_startFromListedChat() {
        String token = issueToken();

        ResponseEntity<String> response = webhook.post(text(LISTED_CHAT, "/start " + token));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains(TelegramBotReplies.ASK_PHONE);
        assertThat(tokenState(token)).isEqualTo("CLAIMED");
    }
}
