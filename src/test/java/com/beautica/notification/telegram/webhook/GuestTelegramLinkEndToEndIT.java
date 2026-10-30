package com.beautica.notification.telegram.webhook;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.telegram.link.TelegramLinkTestSupport;
import com.beautica.support.BookableMasterSeeder;
import com.beautica.support.MutableTestClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.BOT_USERNAME;
import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.GUEST_PHONE;
import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.OTHER_PHONE;
import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.contact;
import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 407 QA — the guest Telegram channel end to end across phases 405 (link tokens + links), 406 (webhook) and 407
 * (guest link API + the D6 booking-response block), over real HTTP only: a guest LINK booking through the public
 * booking endpoint, its cancel token from {@code cancelUrl}, {@code link-token} → the {@code t.me} start parameter →
 * webhook {@code /start} + contact share → {@code status} / {@code unlink}. Nothing is seeded through a service call.
 * Same context configuration as {@code TelegramWebhookConversationIT} (cached context reused).
 */
@Import({TestSecurityConfig.class, TelegramLinkTestSupport.ClockConfig.class})
@TestPropertySource(properties = {
        TelegramLinkTestSupport.BOT_USERNAME_PROPERTY,
        TelegramWebhookTestSupport.ENABLED_PROPERTY,
        TelegramWebhookTestSupport.CAPTURE_MODE_PROPERTY,
        TelegramWebhookTestSupport.SECRET_PROPERTY})
@DisplayName("Guest Telegram link — end to end across 405 + 406 + 407 (phase 407 QA)")
class GuestTelegramLinkEndToEndIT extends AbstractIntegrationTest {

    private static final String API = "/api/v1/book/telegram/";
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final long GUEST_CHAT = 7_500_000_001L;
    /** The booking phone as Telegram sends it in a contact (no {@code +}). */
    private static final String GUEST_CONTACT = GUEST_PHONE.substring(1);
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private MutableTestClock clock;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private GuestTokenProvider guestTokenProvider;

    private TelegramWebhookTestSupport webhook;
    private String slug;
    private UUID serviceId;

    @BeforeEach
    void setUp() {
        // Booking creation and the link-token "future start" rule need real time; other Telegram ITs pin the clock.
        clock.set(Instant.now());
        webhook = new TelegramWebhookTestSupport(restTemplate);
        UUID masterId = new BookingTestFixtures(null, jdbcTemplate, null, passwordEncoder)
                .createIndependentMaster("tg-407-e2e-" + UUID.randomUUID() + "@beautica.test");
        slug = "tg-e2e-" + masterId.toString().substring(0, 8);
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", slug, masterId);
        UUID defId = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, masterId);
        jdbcTemplate.update("UPDATE service_definitions SET category = "
                + "(SELECT platform_category_name FROM service_types WHERE id = service_type_id) WHERE id = ?", defId);
        BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, masterId);
        serviceId = jdbcTemplate.queryForObject("SELECT id FROM master_services WHERE service_def_id = ?", UUID.class,
                defId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────

    private static String nextIp() {
        int n = IP_SEQUENCE.getAndIncrement();
        return "198.20." + (n / 250) + "." + (n % 250 + 1);
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", nextIp());
        return headers;
    }

    /** POST /api/v1/book/{slug}/booking as the OTP-verified {@link TelegramLinkTestSupport#GUEST_PHONE}. */
    private JsonNode book(int daysAhead) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("serviceId", serviceId.toString());
        body.put("startsAt", ZonedDateTime.now(KYIV).plusDays(daysAhead).withHour(12).withMinute(0).withSecond(0)
                .withNano(0).toOffsetDateTime().toString());
        body.put("name", "Олена");
        body.put("surname", "Коваль");
        body.put("privacyAccepted", true);
        body.put("privacyPolicyVersion", "2025-02-03");
        HttpHeaders headers = jsonHeaders();
        headers.setBearerAuth(guestTokenProvider.generate(GUEST_PHONE));
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
        return json(response, HttpStatus.CREATED);
    }

    private ResponseEntity<String> api(String op, String cancelToken) throws Exception {
        return restTemplate.exchange(API + op, HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(Map.of("cancelToken", cancelToken)), jsonHeaders()),
                String.class);
    }

    private JsonNode json(ResponseEntity<String> response, HttpStatus expected) throws Exception {
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(expected);
        return objectMapper.readTree(response.getBody());
    }

    private static String cancelTokenOf(JsonNode created) {
        String cancelUrl = created.path("cancelUrl").asText();
        return cancelUrl.substring(cancelUrl.lastIndexOf('/') + 1);
    }

    /** The {@code start} parameter of {@code https://t.me/<bot>?start=<token>}. */
    private static String startParam(String url) {
        assertThat(url).as("deep link").matches("^https://t\\.me/" + BOT_USERNAME + "\\?start=[A-Za-z0-9_-]{43}$");
        return url.substring(url.indexOf("?start=") + "?start=".length());
    }

    private void assertBotSaid(ResponseEntity<String> response, String expectedText) throws Exception {
        JsonNode reply = json(response, HttpStatus.OK);
        assertThat(reply.path("method").asText()).isEqualTo("sendMessage");
        assertThat(reply.path("chat_id").asLong()).isEqualTo(GUEST_CHAT);
        assertThat(reply.path("text").asText()).isEqualTo(expectedText);
    }

    private int linkRows() {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_links", Integer.class);
    }

    // ── tests ────────────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("public booking → link-token → bot /start + own contact → status linked → unlink 204 → status "
            + "not linked; a later booking for the linked phone reports linked:true")
    void should_linkViaBotAndUnlinkViaApi_when_guestFollowsWholeFlow() throws Exception {
        JsonNode created = book(3);
        assertThat(created.path("telegram").toString()).isEqualTo("{\"available\":true,\"linked\":false}");
        String cancelToken = cancelTokenOf(created);
        assertThat(json(api("status", cancelToken), HttpStatus.OK).toString())
                .isEqualTo("{\"available\":true,\"linked\":false,\"offerChoice\":false}");

        JsonNode issued = json(api("link-token", cancelToken), HttpStatus.OK);
        assertThat(issued.path("alreadyLinked").asBoolean()).isFalse();
        String start = startParam(issued.path("url").asText());

        assertBotSaid(webhook.post(text(GUEST_CHAT, "/start " + start)), TelegramBotReplies.ASK_PHONE);
        assertThat(json(api("status", cancelToken), HttpStatus.OK).path("linked").asBoolean())
                .as("a claimed token is not a link until the phone is proven").isFalse();
        assertBotSaid(webhook.post(contact(GUEST_CHAT, GUEST_CONTACT, GUEST_CHAT)), TelegramBotReplies.LINKED);

        assertThat(json(api("status", cancelToken), HttpStatus.OK).toString())
                .isEqualTo("{\"available\":true,\"linked\":true,\"offerChoice\":false}");
        JsonNode again = json(api("link-token", cancelToken), HttpStatus.OK);
        assertThat(again.toString()).isEqualTo("{\"url\":null,\"expiresAt\":null,\"alreadyLinked\":true}");
        assertThat(book(4).path("telegram").toString())
                .as("D6 linked for the booker's phone").isEqualTo("{\"available\":true,\"linked\":true}");

        ResponseEntity<String> unlinked = api("unlink", cancelToken);

        assertThat(unlinked.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(json(api("status", cancelToken), HttpStatus.OK).toString())
                .isEqualTo("{\"available\":true,\"linked\":false,\"offerChoice\":false}");
        assertThat(linkRows()).as("phone back on SMS: no link row left").isZero();
        assertThat(json(api("link-token", cancelToken), HttpStatus.OK).path("alreadyLinked").asBoolean())
                .as("the guest can opt in again after unlinking").isFalse();
    }

    @Test
    @DisplayName("a contact share for a different phone than the booking's leaves the phone unlinked (status false)")
    void should_stayUnlinked_when_guestSharesPhoneOtherThanBookingPhone() throws Exception {
        String cancelToken = cancelTokenOf(book(3));
        String start = startParam(json(api("link-token", cancelToken), HttpStatus.OK).path("url").asText());
        assertBotSaid(webhook.post(text(GUEST_CHAT, "/start " + start)), TelegramBotReplies.ASK_PHONE);

        assertBotSaid(webhook.post(contact(GUEST_CHAT, OTHER_PHONE.substring(1), GUEST_CHAT)),
                TelegramBotReplies.MISMATCH);

        assertThat(json(api("status", cancelToken), HttpStatus.OK).path("linked").asBoolean()).isFalse();
        assertThat(linkRows()).isZero();
    }

    @Test
    @DisplayName("a guest-cancelled booking's token no longer reaches the API, but the link made with it survives")
    void should_keepLinkAndReturn404_when_bookingCancelledByGuestAfterLinking() throws Exception {
        String cancelToken = cancelTokenOf(book(3));
        String start = startParam(json(api("link-token", cancelToken), HttpStatus.OK).path("url").asText());
        webhook.post(text(GUEST_CHAT, "/start " + start));
        assertBotSaid(webhook.post(contact(GUEST_CHAT, GUEST_CONTACT, GUEST_CHAT)), TelegramBotReplies.LINKED);
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", nextIp());
        assertThat(restTemplate.exchange("/api/v1/book/cancel/" + cancelToken, HttpMethod.POST,
                new HttpEntity<>(headers), String.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> status = api("status", cancelToken);
        ResponseEntity<String> unlink = api("unlink", cancelToken);

        assertThat(status.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(unlink.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(linkRows()).as("link is per phone, not per booking").isEqualTo(1);
    }
}
