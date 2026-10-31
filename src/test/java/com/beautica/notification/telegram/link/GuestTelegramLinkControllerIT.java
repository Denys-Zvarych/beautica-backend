package com.beautica.notification.telegram.link;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.booking.domain.CancelTokenHasher;
import com.beautica.booking.retention.GuestPiiAnonymisationService;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.TestSecurityConfig;
import com.beautica.support.BookableMasterSeeder;
import com.beautica.support.MutableTestClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.BOT_USERNAME;
import static com.beautica.notification.telegram.link.TelegramLinkTestSupport.GUEST_PHONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;

/**
 * Phase 407 — {@code POST /api/v1/book/telegram/{link-token,status,unlink}} end to end against real Postgres through
 * the production {@code SecurityConfig} (anonymous access) and {@code AuthRateLimitFilter} (per-IP 429s), plus the D6
 * {@code telegram} block of a real guest booking. Capture mode: the flag is on with no Bot API credentials.
 * {@link TelegramLinkService} is a spy so one case can make the post-commit link read fail.
 *
 * <p>The {@code chk_bookings_guest_fields} CHECK forbids a cancel token on any APP row (pinned below); V226 (phase 412)
 * lets a live STAFF walk-in with a guest phone carry one, which the STAFF cases below exercise.
 */
@Import({TestSecurityConfig.class, TelegramLinkTestSupport.ClockConfig.class})
@TestPropertySource(properties = {
        TelegramLinkTestSupport.BOT_USERNAME_PROPERTY,
        "app.telegram.enabled=true",
        "app.telegram.mode=capture",
        "app.telegram.webhook-secret=it-webhook-secret-0123456789abcdef0123456789abcdef0123456789abcdefzz"})
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("GuestTelegramLinkController — integration (phase 407)")
class GuestTelegramLinkControllerIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/book/telegram";
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final AtomicInteger IP_SEQUENCE = new AtomicInteger(1);
    private static final AtomicInteger HOUR_SEQUENCE = new AtomicInteger(1);
    private static final AtomicInteger IPV6_SEQUENCE = new AtomicInteger(1);

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private MutableTestClock clock;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private GuestTokenProvider guestTokenProvider;
    @Autowired private GuestPiiAnonymisationService anonymisationService;
    @SpyBean private TelegramLinkService linkService;

    private UUID masterId;
    private UUID masterServiceId;

    @BeforeEach
    void setUp() {
        // The shared MutableTestClock is pinned by other Telegram ITs; booking creation and the link-token "future
        // start" rule must see real time here.
        clock.set(Instant.now());
        BookingTestFixtures fixtures = new BookingTestFixtures(null, jdbcTemplate, null, passwordEncoder);
        masterId = fixtures.createIndependentMaster("tg-407-" + UUID.randomUUID() + "@beautica.test");
        masterServiceId = fixtures.createIndependentMasterService(masterId);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────────────

    /** A LINK booking carrying {@code cancelToken}'s hash, starting {@code hoursFromNow} away (distinct slot). */
    private UUID linkBooking(UUID cancelToken, String status, int hoursFromNow, String phone) {
        UUID bookingId = UUID.randomUUID();
        int minuteShift = HOUR_SEQUENCE.getAndIncrement() * 2;
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, cancel_token_hash, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, ?, NULL, ?, NOW() + make_interval(hours => ?, mins => ?), "
                        + "NOW() + make_interval(hours => ?, mins => ?) + interval '1 minute', 500.00, 1, 0, 'LINK', "
                        + "'Гість', 'Тестовий', ?, ?, NOW(), NOW())",
                bookingId, masterId, masterServiceId, status, hoursFromNow, minuteShift, hoursFromNow, minuteShift,
                phone, CancelTokenHasher.hash(cancelToken));
        return bookingId;
    }

    /** A STAFF walk-in with a guest phone and a cancel token — the shape phase 412 introduces. */
    private UUID staffWalkIn(UUID cancelToken, int hoursFromNow, String phone) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, cancel_token_hash, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, ?, NULL, 'CONFIRMED', NOW() + make_interval(hours => ?), "
                        + "NOW() + make_interval(hours => ?) + interval '1 hour', 500.00, 60, 0, 'STAFF', "
                        + "'Гість', 'Тестовий', ?, ?, NOW(), NOW())",
                bookingId, masterId, masterServiceId, hoursFromNow, hoursFromNow, phone,
                CancelTokenHasher.hash(cancelToken));
        return bookingId;
    }

    private void activeLink(String phone, long chatId) {
        jdbcTemplate.update("INSERT INTO telegram_links (id, phone_hash, chat_id, status, linked_at, status_changed_at) "
                + "VALUES (?, ?, ?, 'ACTIVE', NOW(), NOW())", UUID.randomUUID(), phoneHasher.hash(phone).hash(), chatId);
    }

    private int linkCount(String phone) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_links WHERE phone_hash = ?", Integer.class,
                phoneHasher.hash(phone).hash());
    }

    private static String nextIp() {
        int n = IP_SEQUENCE.getAndIncrement();
        return "198.18." + (n / 250) + "." + (n % 250 + 1);
    }

    private ResponseEntity<String> post(String op, String cancelToken) {
        return post(op, cancelToken, nextIp());
    }

    private ResponseEntity<String> post(String op, String cancelToken, String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", ip);
        return restTemplate.exchange(BASE + "/" + op, HttpMethod.POST,
                new HttpEntity<>(tokenBody(cancelToken), headers), String.class);
    }

    /** Built with Jackson, never string concatenation (QA Q16). */
    private String tokenBody(String cancelToken) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of("cancelToken", cancelToken));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private JsonNode json(ResponseEntity<String> response, HttpStatus expected) throws Exception {
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(expected);
        return objectMapper.readTree(response.getBody());
    }

    private void assertGeneric404(ResponseEntity<String> response) throws Exception {
        JsonNode body = json(response, HttpStatus.NOT_FOUND);
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("message").asText()).isEqualTo("Resource not found");
    }

    // ── link-token ───────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("link-token")
    class LinkToken {

        @Test
        @DisplayName("should return deep link and store booking phone hash when link booking confirmed")
        void should_returnDeepLinkAndStoreBookingPhoneHash_when_linkBookingConfirmed(CapturedOutput output)
                throws Exception {
            UUID token = UUID.randomUUID();
            UUID bookingId = linkBooking(token, "CONFIRMED", 48, GUEST_PHONE);

            JsonNode body = json(post("link-token", token.toString()), HttpStatus.OK);

            String url = body.path("url").asText();
            assertThat(url).matches("^https://t\\.me/" + BOT_USERNAME + "\\?start=[A-Za-z0-9_-]{43}$");
            assertThat(body.path("alreadyLinked").asBoolean()).isFalse();
            assertThat(Instant.parse(body.path("expiresAt").asText())).isAfter(Instant.now());
            byte[] storedPhoneHash = jdbcTemplate.queryForObject(
                    "SELECT phone_hash FROM telegram_link_tokens WHERE booking_id = ?", byte[].class, bookingId);
            assertThat(storedPhoneHash).isEqualTo(phoneHasher.hash(GUEST_PHONE).hash());
            String rawStart = url.substring(url.indexOf("?start=") + "?start=".length());
            assertThat(output.getAll()).contains("event=telegram_link_api op=issue result=ISSUED bookingId=" + bookingId)
                    .doesNotContain(rawStart).doesNotContain(token.toString()).doesNotContain(GUEST_PHONE)
                    .doesNotContain(GUEST_PHONE.substring(1));
        }

        @Test
        @DisplayName("should return already linked without token when phone active")
        void should_returnAlreadyLinkedWithoutToken_when_phoneActive() throws Exception {
            UUID token = UUID.randomUUID();
            linkBooking(token, "CONFIRMED", 48, GUEST_PHONE);
            activeLink(GUEST_PHONE, 7_100_000_001L);

            JsonNode body = json(post("link-token", token.toString()), HttpStatus.OK);

            assertThat(body.path("url").isNull()).isTrue();
            assertThat(body.path("expiresAt").isNull()).isTrue();
            assertThat(body.path("alreadyLinked").asBoolean()).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens", Integer.class))
                    .isZero();
        }

        @Test
        @DisplayName("should return 404 when booking cancelled")
        void should_return404_when_bookingCancelled() throws Exception {
            UUID token = UUID.randomUUID();
            linkBooking(token, "CANCELLED", 48, GUEST_PHONE);

            assertGeneric404(post("link-token", token.toString()));
        }

        @Test
        @DisplayName("should return 404 when confirmed booking already started")
        void should_return404_when_confirmedBookingAlreadyStarted() throws Exception {
            UUID token = UUID.randomUUID();
            linkBooking(token, "CONFIRMED", -2, GUEST_PHONE);

            assertGeneric404(post("link-token", token.toString()));
        }

        @Test
        @DisplayName("should return 404 when token unknown")
        void should_return404_when_tokenUnknown() throws Exception {
            assertGeneric404(post("link-token", UUID.randomUUID().toString()));
        }

        @Test
        @DisplayName("should return 400 when token malformed")
        void should_return400_when_tokenMalformed() {
            ResponseEntity<String> response = post("link-token", "not-a-uuid");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("should return 429 when sixth call from one IP")
        void should_return429_when_sixthCallFromOneIp() {
            String ip = nextIp();
            for (int i = 0; i < 5; i++) {
                assertThat(post("link-token", UUID.randomUUID().toString(), ip).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND);
            }

            ResponseEntity<String> limited = post("link-token", UUID.randomUUID().toString(), ip);

            assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(limited.getHeaders().getFirst("Retry-After")).isNotBlank();
            assertThat(limited.getBody()).isEqualTo("{\"error\":\"Too many requests\"}");
        }

        @Test
        @DisplayName("should return 429 when fourth call for one token from rotating IPs")
        void should_return429_when_fourthCallForOneTokenFromRotatingIps() throws Exception {
            UUID token = UUID.randomUUID();
            for (int i = 0; i < 3; i++) {
                assertThat(post("link-token", token.toString()).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            }

            ResponseEntity<String> limited = post("link-token", token.toString());

            JsonNode body = json(limited, HttpStatus.TOO_MANY_REQUESTS);
            assertThat(limited.getHeaders().getFirst("Retry-After")).isNotBlank();
            assertThat(body.path("message").asText()).isEqualTo("Too many requests — please slow down");
        }
    }

    // ── status ───────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("status")
    class Status {

        @Test
        @DisplayName("should not offer choice when link booking not linked")
        void should_notOfferChoice_when_linkBookingNotLinked() throws Exception {
            UUID token = UUID.randomUUID();
            linkBooking(token, "CONFIRMED", 48, GUEST_PHONE);

            JsonNode body = json(post("status", token.toString()), HttpStatus.OK);

            assertThat(body.toString()).isEqualTo("{\"available\":true,\"linked\":false,\"offerChoice\":false}");
        }

        @Test
        @DisplayName("should return 404 when APP booking cannot hold token")
        void should_return404_when_appBookingCannotHoldToken() throws Exception {
            UUID clientId = new BookingTestFixtures(null, jdbcTemplate, null, passwordEncoder)
                    .createUser("tg-407-client-" + UUID.randomUUID() + "@beautica.test", "CLIENT", null);
            UUID appBooking = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, NULL, 'CONFIRMED', NOW() + interval '3 days', "
                            + "NOW() + interval '3 days 1 hour', 500.00, 60, 0, 'APP', NOW(), NOW())",
                    appBooking, clientId, masterId, masterServiceId);
            UUID token = UUID.randomUUID();

            assertThatThrownBy(() -> jdbcTemplate.update("UPDATE bookings SET cancel_token_hash = ? WHERE id = ?",
                    CancelTokenHasher.hash(token), appBooking)).isInstanceOf(DataIntegrityViolationException.class);
            assertGeneric404(post("status", token.toString()));
            assertGeneric404(post("link-token", token.toString()));
        }

        @Test
        @DisplayName("should return 404 when guest phone anonymised")
        void should_return404_when_guestPhoneAnonymised() throws Exception {
            UUID token = UUID.randomUUID();
            UUID bookingId = linkBooking(token, "COMPLETED", -24 * 430, GUEST_PHONE);
            assertThat(post("status", token.toString()).getStatusCode()).isEqualTo(HttpStatus.OK);

            anonymisationService.anonymiseDue(Instant.now().minus(Duration.ofDays(365)));

            assertThat(jdbcTemplate.queryForObject("SELECT guest_phone FROM bookings WHERE id = ?", String.class,
                    bookingId)).isNull();
            assertGeneric404(post("status", token.toString()));
            assertGeneric404(post("unlink", token.toString()));
        }

        @Test
        @DisplayName("should stay available when provider declined keeps token")
        void should_stayAvailable_when_providerDeclinedKeepsToken() throws Exception {
            UUID token = UUID.randomUUID();
            linkBooking(token, "DECLINED", 48, GUEST_PHONE);

            JsonNode body = json(post("status", token.toString()), HttpStatus.OK);

            assertThat(body.toString()).isEqualTo("{\"available\":true,\"linked\":false,\"offerChoice\":false}");
        }

        @Test
        @DisplayName("should report linked when past cancelled booking phone active")
        void should_reportLinked_when_pastCancelledBookingPhoneActive() throws Exception {
            UUID token = UUID.randomUUID();
            linkBooking(token, "CANCELLED", -48, GUEST_PHONE);
            activeLink(GUEST_PHONE, 7_100_000_002L);

            JsonNode body = json(post("status", token.toString()), HttpStatus.OK);

            assertThat(body.toString()).isEqualTo("{\"available\":true,\"linked\":true,\"offerChoice\":false}");
        }

        @Test
        @DisplayName("should return 404 when token unknown")
        void should_return404_when_tokenUnknown() throws Exception {
            assertGeneric404(post("status", UUID.randomUUID().toString()));
        }

        @Test
        @DisplayName("should return 429 when read budget spent across status and unlink")
        void should_return429_when_readBudgetSpentAcrossStatusAndUnlink() {
            String ip = nextIp();
            for (int i = 0; i < 30; i++) {
                assertThat(post(i % 2 == 0 ? "status" : "unlink", UUID.randomUUID().toString(), ip).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND);
            }

            assertThat(post("status", UUID.randomUUID().toString(), ip).getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }

    // ── unlink ───────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("unlink")
    class Unlink {

        @Test
        @DisplayName("should delete link idempotently when linked")
        void should_deleteLinkIdempotently_when_linked() {
            UUID token = UUID.randomUUID();
            linkBooking(token, "CONFIRMED", 48, GUEST_PHONE);
            activeLink(GUEST_PHONE, 7_100_000_003L);

            ResponseEntity<String> first = post("unlink", token.toString());
            ResponseEntity<String> repeat = post("unlink", token.toString());

            assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(repeat.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
            assertThat(linkService.findActiveForPhone(GUEST_PHONE)).isEmpty();
            assertThat(linkCount(GUEST_PHONE)).isZero();
        }

        @Test
        @DisplayName("should keep other phones link when unlinking")
        void should_keepOtherPhonesLink_when_unlinking() {
            UUID token = UUID.randomUUID();
            linkBooking(token, "CONFIRMED", 48, GUEST_PHONE);
            activeLink(TelegramLinkTestSupport.OTHER_PHONE, 7_100_000_004L);

            assertThat(post("unlink", token.toString()).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            assertThat(linkCount(TelegramLinkTestSupport.OTHER_PHONE)).isEqualTo(1);
        }

        @Test
        @DisplayName("should return 404 when token unknown")
        void should_return404_when_tokenUnknown() throws Exception {
            assertGeneric404(post("unlink", UUID.randomUUID().toString()));
        }
    }

    // ── STAFF walk-ins (phase 412) ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("STAFF walk-in (phase 412)")
    class StaffWalkIn {

        @Test
        @DisplayName("should issue deep link when STAFF walk in with phone")
        void should_issueDeepLink_when_staffWalkInWithPhone() throws Exception {
            UUID token = UUID.randomUUID();
            UUID bookingId = staffWalkIn(token, 48, GUEST_PHONE);

            JsonNode body = json(post("link-token", token.toString()), HttpStatus.OK);

            assertThat(body.path("url").asText())
                    .matches("^https://t\\.me/" + BOT_USERNAME + "\\?start=[A-Za-z0-9_-]{43}$");
            assertThat(body.path("alreadyLinked").asBoolean()).isFalse();
            assertThat(jdbcTemplate.queryForObject("SELECT phone_hash FROM telegram_link_tokens WHERE booking_id = ?",
                    byte[].class, bookingId)).isEqualTo(phoneHasher.hash(GUEST_PHONE).hash());
        }

        @Test
        @DisplayName("should offer choice when STAFF walk in not linked")
        void should_offerChoice_when_staffWalkInNotLinked() throws Exception {
            UUID token = UUID.randomUUID();
            staffWalkIn(token, 48, GUEST_PHONE);

            JsonNode body = json(post("status", token.toString()), HttpStatus.OK);

            assertThat(body.toString()).isEqualTo("{\"available\":true,\"linked\":false,\"offerChoice\":true}");
        }

        @Test
        @DisplayName("should report linked without choice when STAFF walk in linked")
        void should_reportLinkedWithoutChoice_when_staffWalkInLinked() throws Exception {
            UUID token = UUID.randomUUID();
            staffWalkIn(token, 48, GUEST_PHONE);
            activeLink(GUEST_PHONE, 7_100_000_010L);

            JsonNode body = json(post("status", token.toString()), HttpStatus.OK);

            assertThat(body.toString()).isEqualTo("{\"available\":true,\"linked\":true,\"offerChoice\":false}");
        }
    }

    // ── D5 rate limits through the real RateLimitConfig beans (QA) ───────────────────────────────────

    @Nested
    @DisplayName("D5 rate limits — wired buckets")
    class RateLimits {

        @Test
        @DisplayName("link-token per-IP bucket keys on the IPv6 /64: a rotated interface id is still the 6th call, "
                + "another /64 is not")
        void should_return429ForSamePrefix_when_ipv6InterfaceIdRotates() {
            int n = IPV6_SEQUENCE.getAndIncrement();
            String prefix = "2001:db8:407:" + Integer.toHexString(n) + ":";
            for (int i = 1; i <= 5; i++) {
                assertThat(post("link-token", UUID.randomUUID().toString(), prefix + ":" + Integer.toHexString(i))
                        .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            }

            ResponseEntity<String> samePrefix = post("link-token", UUID.randomUUID().toString(),
                    prefix + "ffff:ffff:ffff:ffff");
            ResponseEntity<String> otherPrefix = post("link-token", UUID.randomUUID().toString(),
                    "2001:db8:408:" + Integer.toHexString(n) + "::1");

            assertThat(samePrefix.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(otherPrefix.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("status + unlink share one per-IPv6-/64 bucket: 30 rotated addresses, then the 31st is 429")
        void should_return429ForSamePrefix_when_readBudgetSpentAcrossIpv6Addresses() {
            int n = IPV6_SEQUENCE.getAndIncrement();
            String prefix = "2001:db8:409:" + Integer.toHexString(n) + "::";
            for (int i = 1; i <= 30; i++) {
                assertThat(post(i % 2 == 0 ? "status" : "unlink", UUID.randomUUID().toString(),
                        prefix + Integer.toHexString(i)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            }

            assertThat(post("unlink", UUID.randomUUID().toString(), prefix + "beef").getStatusCode())
                    .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        @DisplayName("per-IP 429 on link-token carries Retry-After 600 (the 10-minute window) and leaves the "
                + "status/unlink bucket of the same IP untouched")
        void should_keepReadBucketOpenWithRetryAfter600_when_linkTokenBudgetSpent() throws Exception {
            String ip = nextIp();
            UUID token = UUID.randomUUID();
            linkBooking(token, "CONFIRMED", 48, GUEST_PHONE);
            for (int i = 0; i < 5; i++) {
                post("link-token", UUID.randomUUID().toString(), ip);
            }

            ResponseEntity<String> limited = post("link-token", token.toString(), ip);
            ResponseEntity<String> status = post("status", token.toString(), ip);

            assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(limited.getHeaders().getFirst("Retry-After")).isEqualTo("600");
            assertThat(json(status, HttpStatus.OK).path("available").asBoolean()).isTrue();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens", Integer.class))
                    .as("a rate-limited call issues nothing").isZero();
        }

        @Test
        @DisplayName("per-token budget: an ELIGIBLE booking gets 3 deep links, the 4th is 429 with a numeric "
                + "Retry-After in (0, 600] and issues no token; status for that token still answers")
        void should_return429WithoutIssuing_when_fourthLinkTokenForEligibleBooking() throws Exception {
            UUID token = UUID.randomUUID();
            UUID bookingId = linkBooking(token, "CONFIRMED", 48, GUEST_PHONE);
            for (int i = 0; i < 3; i++) {
                assertThat(json(post("link-token", token.toString()), HttpStatus.OK).path("url").asText())
                        .startsWith("https://t.me/");
            }
            int tokensBefore = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM telegram_link_tokens WHERE booking_id = ?", Integer.class, bookingId);

            ResponseEntity<String> limited = post("link-token", token.toString());

            assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(Long.parseLong(limited.getHeaders().getFirst("Retry-After"))).isBetween(1L, 600L);
            assertThat(limited.getBody()).doesNotContain(token.toString());
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM telegram_link_tokens WHERE booking_id = ?",
                    Integer.class, bookingId)).isEqualTo(tokensBefore);
            assertThat(post("status", token.toString()).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // ── security ─────────────────────────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("SecurityConfig scoping")
    class Security {

        @Test
        @DisplayName("should return 401 when put on permit all path")
        void should_return401_when_putOnPermitAllPath() {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Forwarded-For", nextIp());

            ResponseEntity<String> response = restTemplate.exchange(BASE + "/status", HttpMethod.PUT,
                    new HttpEntity<>(tokenBody(UUID.randomUUID().toString()), headers), String.class);

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("should return 401 when post on unlisted sibling path")
        void should_return401_when_postOnUnlistedSiblingPath() {
            ResponseEntity<String> response = post("link", UUID.randomUUID().toString());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
    }

    // ── D6: guest booking response ───────────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("guest booking response telegram block (D6)")
    class BookingResponse {

        private String slug;
        private UUID bookableServiceId;

        @BeforeEach
        void seedBookableMaster() {
            UUID bookableMaster = new BookingTestFixtures(null, jdbcTemplate, null, passwordEncoder)
                    .createIndependentMaster("tg-407-page-" + UUID.randomUUID() + "@beautica.test");
            slug = "tg-407-" + bookableMaster.toString().substring(0, 8);
            jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", slug, bookableMaster);
            UUID defId = BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, bookableMaster);
            jdbcTemplate.update("UPDATE service_definitions SET category = "
                    + "(SELECT platform_category_name FROM service_types WHERE id = service_type_id) WHERE id = ?", defId);
            BookableMasterSeeder.seedUsableSchedule(jdbcTemplate, bookableMaster);
            bookableServiceId = jdbcTemplate.queryForObject(
                    "SELECT id FROM master_services WHERE service_def_id = ?", UUID.class, defId);
        }

        private ResponseEntity<String> book(String idsField, Object ids, int daysAhead) throws Exception {
            ZonedDateTime startsAt = ZonedDateTime.now(KYIV).plusDays(daysAhead)
                    .withHour(12).withMinute(0).withSecond(0).withNano(0);
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(guestTokenProvider.generate(GUEST_PHONE));
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Forwarded-For", nextIp());
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put(idsField, ids);
            body.put("startsAt", startsAt.toOffsetDateTime().toString());
            body.put("name", "Олена");
            body.put("surname", "Коваль");
            body.put("privacyAccepted", true);
            body.put("privacyPolicyVersion", "2025-02-03");
            return restTemplate.exchange("/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                    new HttpEntity<>(objectMapper.writeValueAsString(body), headers), String.class);
        }

        private String cancelTokenOf(JsonNode created) {
            String cancelUrl = created.path("cancelUrl").asText();
            return cancelUrl.substring(cancelUrl.lastIndexOf('/') + 1);
        }

        @Test
        @DisplayName("should report available not linked when phone has no link")
        void should_reportAvailableNotLinked_when_phoneHasNoLink() throws Exception {
            JsonNode created = json(book("serviceId", bookableServiceId.toString(), 3), HttpStatus.CREATED);

            assertThat(created.path("telegram").toString()).isEqualTo("{\"available\":true,\"linked\":false}");
        }

        @Test
        @DisplayName("should return 201 with unavailable telegram when post commit link read throws")
        void should_return201WithUnavailableTelegram_when_postCommitLinkReadThrows() throws Exception {
            doThrow(new DataAccessResourceFailureException("link read failed"))
                    .when(linkService).statusForPhone(GUEST_PHONE);

            JsonNode created = json(book("serviceId", bookableServiceId.toString(), 7), HttpStatus.CREATED);

            assertThat(created.path("telegram").toString()).isEqualTo("{\"available\":false,\"linked\":false}");
            UUID bookingId = UUID.fromString(created.path("bookingId").asText());
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId))
                    .isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("should report linked when phone has active link")
        void should_reportLinked_when_phoneHasActiveLink() throws Exception {
            activeLink(GUEST_PHONE, 7_100_000_005L);

            JsonNode created = json(book("serviceId", bookableServiceId.toString(), 4), HttpStatus.CREATED);

            assertThat(created.path("telegram").toString()).isEqualTo("{\"available\":true,\"linked\":true}");
        }

        @Test
        @DisplayName("should resolve visit token when multi service visit booked")
        void should_resolveVisitToken_when_multiServiceVisitBooked() throws Exception {
            JsonNode created = json(book("masterServiceIds", java.util.List.of(bookableServiceId.toString()), 5),
                    HttpStatus.CREATED);
            String cancelToken = cancelTokenOf(created);
            UUID firstItem = UUID.fromString(created.path("bookingId").asText());

            JsonNode status = json(post("status", cancelToken), HttpStatus.OK);
            JsonNode link = json(post("link-token", cancelToken), HttpStatus.OK);

            assertThat(created.path("appointmentId").isNull()).isFalse();
            assertThat(status.toString()).isEqualTo("{\"available\":true,\"linked\":false,\"offerChoice\":false}");
            assertThat(link.path("url").asText()).startsWith("https://t.me/" + BOT_USERNAME + "?start=");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT booking_id FROM telegram_link_tokens", UUID.class)).isEqualTo(firstItem);
        }

        @Test
        @DisplayName("should return 404 when token consumed by guest cancel")
        void should_return404_when_tokenConsumedByGuestCancel() throws Exception {
            JsonNode created = json(book("serviceId", bookableServiceId.toString(), 6), HttpStatus.CREATED);
            String cancelToken = cancelTokenOf(created);
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-Forwarded-For", nextIp());
            ResponseEntity<String> cancelled = restTemplate.exchange("/api/v1/book/cancel/" + cancelToken,
                    HttpMethod.POST, new HttpEntity<>(headers), String.class);
            assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

            assertGeneric404(post("status", cancelToken));
        }
    }
}
