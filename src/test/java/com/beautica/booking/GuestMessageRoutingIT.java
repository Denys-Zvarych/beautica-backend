package com.beautica.booking;

import com.beautica.booking.dto.AppointmentProviderNoteRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.job.BookingReminderJob;
import com.beautica.booking.service.BookingService;
import com.beautica.booking.service.BookingSmsDispatcher;
import com.beautica.common.TimeZones;
import com.beautica.common.security.PhoneHasher;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.telegram.StagingTelegramChatAllowList;
import com.beautica.notification.telegram.TelegramChatAllowList;
import com.beautica.notification.telegram.link.TelegramLinkRepository;
import com.beautica.notification.telegram.link.TelegramLinkTestSupport;
import com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport;
import com.beautica.support.MutableTestClock;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 408 — the guest-message channel router end to end: real outbox drain, real reminder job (24h + the 413 2h
 * sweep), real {@code TelegramBotApiClient} against a WireMock Bot API behind the staging allow-list, and the real
 * gated Turbosms {@code SmsService} against WireMock. Every assertion reads the two provider wires, so "Telegram
 * only" / "exactly one SMS" are claims about what would actually be delivered.
 *
 * <p>The clock is pinned to 12:00 Kyiv (daytime) so the 2h sweep's night rule never fires; every booking is
 * written relative to it. Nothing here goes through an authenticated HTTP call (JJWT validates against the system
 * clock), so declines run through {@link BookingService} directly, as the outbox sees them.
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.booking.reminder-2h.enabled=true",
        "app.telegram.enabled=true",
        "app.telegram.mode=live",
        "app.telegram.bot-username=" + TelegramLinkTestSupport.BOT_USERNAME,
        "app.telegram.webhook-secret=" + GuestMessageRoutingIT.WEBHOOK_SECRET,
        "app.telegram.bot-token=" + GuestMessageRoutingIT.BOT_TOKEN})
@Import({GuestMessageRoutingIT.ClockConfig.class, GuestMessageRoutingIT.AllowListConfig.class})
@DisplayName("GuestMessageRoutingIT — phase 408 Telegram-when-linked, SMS otherwise")
class GuestMessageRoutingIT extends AbstractWalkInBookingSmsIT {

    /** Obviously fake, built (not literal) so secret scanners do not fire. */
    static final String BOT_TOKEN = "123456789:xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx";
    static final String WEBHOOK_SECRET = "it-routing-secret-0123456789abcdef0123456789abcdef";
    private static final String GONE_BODY =
            "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}";
    /** A second chat the same phone is re-linked to (phase 408 markBlocked chat-match race). */
    private static final long RELINKED_CHAT = 7_300_000_002L;
    private static final String SEND_PATH = "/bot" + BOT_TOKEN + "/sendMessage";
    private static final String OK_BODY = "{\"ok\":true,\"result\":{\"message_id\":1}}";

    private static final long ALLOWED_CHAT = 7_300_000_001L;
    private static final long STRANGER_CHAT = 7_399_999_999L;
    private static final String STRANGER_PHONE = "+380503334455";
    /**
     * Each test starts one minute after the previous one ended (never earlier): the live client's send gate refills
     * from this clock, so a clock that never moves, or moves back, would rate-limit the sixth send of the class.
     * Stays within Kyiv daytime from the 12:00 base.
     */
    private Instant now;
    /** 12:00 Kyiv on the REAL Kyiv date (the base class seeds the schedule from the real date). */
    private static final Instant BASE = java.time.LocalDate.now(TimeZones.KYIV).atTime(12, 0)
            .atZone(TimeZones.KYIV).toInstant();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    static final WireMockServer TELEGRAM = new WireMockServer(options().dynamicPort());

    static {
        TELEGRAM.start();
        Runtime.getRuntime().addShutdownHook(new Thread(TELEGRAM::stop));
    }

    @DynamicPropertySource
    static void telegramEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.telegram.api-base-url", () -> "http://localhost:" + TELEGRAM.port());
    }

    /** Starts at {@link #BASE}, so the base class's schedule seeding (which runs first) sees today's date. */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        MutableTestClock systemClock() {
            return new MutableTestClock(BASE);
        }
    }

    /** Phase 432 staging allow-list in front of the live client: only {@link #ALLOWED_CHAT} may be messaged. */
    @TestConfiguration
    static class AllowListConfig {
        @Bean
        TelegramChatAllowList telegramChatAllowList() {
            return new StagingTelegramChatAllowList(List.of(ALLOWED_CHAT));
        }
    }

    @Autowired private BookingReminderJob reminderJob;
    @Autowired private NotificationOutboxDrainWorker drainWorker;
    @Autowired private BookingService bookingService;
    @Autowired private com.beautica.booking.service.GuestRescheduleSmsBudget smsBudget;
    @Autowired private com.beautica.booking.service.AppointmentTransitionService appointmentTransitionService;
    @Autowired private BookingSmsDispatcher bookingSmsDispatcher;
    @Autowired private TelegramLinkRepository linkRepository;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MutableTestClock clock;
    @Autowired private com.beautica.notification.telegram.link.TelegramLinkService linkService;

    @BeforeEach
    void resetTelegramAndClock() {
        Instant previous = clock.instant();
        now = (previous.isBefore(BASE) ? BASE : previous).plus(Duration.ofMinutes(1));
        clock.set(now);
        stubTelegram(200, OK_BODY);
    }

    @Test
    @DisplayName("ACTIVE link → 24h and 2h reminders reach Telegram only, text identical to the SMS template")
    void should_sendRemindersToTelegramOnly_when_phoneLinked() throws Exception {
        link(LINK_PHONE, ALLOWED_CHAT);
        OffsetDateTime in24h = at(now.plus(Duration.ofHours(24)));
        OffsetDateTime in2h = at(now.plus(Duration.ofHours(2)));
        insertLinkBooking(LINK_PHONE, in24h);
        insertLinkBooking(LINK_PHONE, in2h);

        reminderJob.sendReminders();
        reminderJob.sweepTwoHour();

        assertThat(telegramTexts()).containsExactly(
                "Beautica: Нагадуємо!\n" + SERVICE_NAME + " у " + MASTER_NAME + "\nЗавтра о " + kyivTime(in24h),
                "Beautica: Нагадуємо!\n" + SERVICE_NAME + " у " + MASTER_NAME + "\nСьогодні о " + kyivTime(in2h));
        assertThat(telegramRequests()).allSatisfy(r ->
                assertThat(json(r).path("chat_id").asLong()).isEqualTo(ALLOWED_CHAT));
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(jdbcTemplate.queryForObject("SELECT last_sent_at IS NOT NULL FROM telegram_links",
                Boolean.class)).isTrue();
        assertNoPiiInLogs(LINK_PHONE, LINK_SUBSCRIBER_DIGITS);
        assertThat(logMessages()).noneMatch(line -> line.contains(String.valueOf(ALLOWED_CHAT)));
    }

    @Test
    @DisplayName("the 2h reminder is sent once: a second tick inside the window sends nothing more")
    void should_sendTwoHourReminderOnce_when_sweptTwice() {
        link(LINK_PHONE, ALLOWED_CHAT);
        insertLinkBooking(LINK_PHONE, at(now.plus(Duration.ofMinutes(120))));

        reminderJob.sweepTwoHour();
        clock.advance(Duration.ofMinutes(15));
        reminderJob.sweepTwoHour();

        assertThat(telegramRequests()).hasSize(1);
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("not linked → the 2h reminder goes by SMS (SMS guests get the new 2h reminder too)")
    void should_sendTwoHourReminderBySms_when_notLinked() throws Exception {
        OffsetDateTime in2h = at(now.plus(Duration.ofHours(2)));
        insertLinkBooking(LINK_PHONE, in2h);

        reminderJob.sweepTwoHour();

        assertThat(sentText()).isEqualTo(
                "Beautica: Нагадуємо!\n" + SERVICE_NAME + " у " + MASTER_NAME + "\nСьогодні о " + kyivTime(in2h));
        assertThat(telegramRequests()).isEmpty();
    }

    @Test
    @DisplayName("provider decline of a linked LINK guest → one Telegram message, no SMS, outbox row SENT")
    void should_sendDeclineToTelegram_when_phoneLinked() {
        link(LINK_PHONE, ALLOWED_CHAT);
        UUID bookingId = insertLinkBooking(LINK_PHONE, at(now.plus(Duration.ofDays(1))));

        decline(bookingId);
        drainWorker.drain();

        assertThat(telegramTexts()).singleElement().asString().startsWith("Beautica: Ваш запис ");
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(statusChangedRowStatus(bookingId)).isEqualTo("SENT");
    }

    @Test
    @DisplayName("Telegram 403 → exactly one SMS + link BLOCKED; the next reminder goes by SMS without calling Telegram")
    void should_fallBackAndBlock_when_telegramSaysGone() throws Exception {
        link(LINK_PHONE, ALLOWED_CHAT);
        stubTelegram(403, "{\"ok\":false,\"error_code\":403,\"description\":\"Forbidden: bot was blocked by the user\"}");
        insertLinkBooking(LINK_PHONE, at(now.plus(Duration.ofHours(2))));

        reminderJob.sweepTwoHour();

        assertThat(telegramRequests()).hasSize(1);
        assertThat(sentText()).contains("Сьогодні о");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM telegram_links", String.class))
                .isEqualTo("BLOCKED");
        assertThat(logMessages()).noneMatch(line -> line.contains("bot was blocked by the user"));

        resetTurbosms();
        stubTelegram(200, OK_BODY);
        insertLinkBooking(LINK_PHONE, at(now.plus(Duration.ofHours(24))));
        reminderJob.sendReminders();

        assertThat(sentText()).contains("Завтра о");
        assertThat(telegramRequests()).isEmpty();
    }

    @Test
    @DisplayName("outbox decline with Telegram 500 → exactly one SMS, row SENT, link untouched")
    void should_fallBackToSms_when_telegramFailsTransiently() throws Exception {
        link(LINK_PHONE, ALLOWED_CHAT);
        stubTelegram(500, "{\"ok\":false,\"error_code\":500,\"description\":\"Internal Server Error\"}");
        UUID bookingId = insertLinkBooking(LINK_PHONE, at(now.plus(Duration.ofDays(1))));

        decline(bookingId);
        drainWorker.drain();

        assertThat(telegramRequests()).hasSize(1);
        assertThat(sentText()).startsWith("Beautica: Ваш запис ");
        assertThat(statusChangedRowStatus(bookingId)).isEqualTo("SENT");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM telegram_links", String.class))
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("staging allow-list refuses a non-listed chat → no Bot API call, exactly one SMS (432 QA INFO)")
    void should_fallBackToSms_when_chatNotOnStagingAllowList() throws Exception {
        link(STRANGER_PHONE, STRANGER_CHAT);
        insertLinkBooking(STRANGER_PHONE, at(now.plus(Duration.ofHours(2))));

        reminderJob.sweepTwoHour();

        assertThat(telegramRequests()).isEmpty();
        assertThat(sentText()).contains("Сьогодні о");
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText()).isEqualTo(STRANGER_PHONE);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM telegram_links", String.class))
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("walk-in with a linked phone declined by the provider → exactly one Telegram message, no SMS")
    void should_sendWalkInDeclineToTelegram_when_phoneLinked() {
        link(E164_PHONE, ALLOWED_CHAT);
        UUID bookingId = insertWalkIn(E164_PHONE, at(now.plus(Duration.ofDays(1))));

        decline(bookingId);
        drainWorker.drain();

        assertThat(telegramTexts()).singleElement().asString().startsWith("Beautica: Ваш запис ");
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("walk-in with an unlinked phone declined by the provider → exactly one SMS")
    void should_sendWalkInDeclineBySms_when_notLinked() throws Exception {
        UUID bookingId = insertWalkIn(E164_PHONE, at(now.plus(Duration.ofDays(1))));

        decline(bookingId);
        drainWorker.drain();

        assertThat(sentText()).startsWith("Beautica: Ваш запис ");
        assertThat(telegramRequests()).isEmpty();
    }

    @Test
    @DisplayName("audit-fix: a 10-service walk-in visit declined as a whole → exactly ONE message")
    void should_sendOneMessage_when_tenServiceWalkInVisitDeclined() {
        UUID appointmentId = insertWalkInVisit(E164_PHONE, at(now.plus(Duration.ofDays(1))), 10);
        authenticateAsOwner();

        appointmentTransitionService.declineAppointment(salon.ownerId(), appointmentId,
                new AppointmentProviderNoteRequest(null));
        drainWorker.drain();

        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("audit-fix: walk-in visit lines declined one by one → one message per visit per window")
    void should_sendOneMessagePerVisit_when_walkInLinesDeclinedOneByOne() {
        UUID appointmentId = insertWalkInVisit(E164_PHONE, at(now.plus(Duration.ofDays(1))), 3);
        authenticateAsOwner();
        List<UUID> items = jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at", UUID.class, appointmentId);

        appointmentTransitionService.declineAppointmentItem(salon.ownerId(), appointmentId, items.get(0),
                new AppointmentProviderNoteRequest(null));
        drainWorker.drain();
        appointmentTransitionService.declineAppointmentItem(salon.ownerId(), appointmentId, items.get(1),
                new AppointmentProviderNoteRequest(null));
        drainWorker.drain();

        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("audit-fix cycle 2: a back-pressure re-queued walk-in row reserves budget ONCE and sends ONCE")
    void should_reserveOnceAndSendOnce_when_walkInCancellationRowRequeued() {
        UUID bookingId = insertWalkIn(E164_PHONE, at(now.plus(Duration.ofDays(1))));
        decline(bookingId);
        UUID entryId = jdbcTemplate.queryForObject("SELECT id FROM notification_outbox "
                + "WHERE event_type = 'STATUS_CHANGED' AND aggregate_id = ?", UUID.class, bookingId);
        // Six earlier drain attempts of THIS row, each reserving before back-pressure re-queued it.
        for (int attempt = 0; attempt < 6; attempt++) {
            assertThat(smsBudget.tryReserveWalkInCancellation(bookingId, null, E164_PHONE, entryId)).isTrue();
        }

        drainWorker.drain();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM guest_reschedule_sms_sends "
                + "WHERE outbox_entry_id = ?", Integer.class, entryId)).isEqualTo(1);
        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(statusChangedRowStatus(bookingId)).isEqualTo("SENT");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = BookingSmsDispatcher.Kind.class,
            names = {"GUEST_CONFIRMATION", "WALK_IN_CONFIRMATION", "GUEST_CANCELLATION", "GUEST_VISIT_CANCELLATION"})
    @DisplayName("unrouted kinds with an ACTIVE link → SMS only, the Bot API is never called")
    void should_sendSmsOnly_when_kindIsUnrouted(BookingSmsDispatcher.Kind kind) {
        link(LINK_PHONE, ALLOWED_CHAT);

        bookingSmsDispatcher.dispatch(kind, LINK_PHONE, "Beautica: Запис підтверджено!");

        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(telegramRequests()).isEmpty();
    }

    @Test
    @DisplayName("E2E: guest links via bot /start + contact share → 24h reminder in Telegram only; Telegram 403 → "
            + "link BLOCKED + the reminder by SMS; the next 24h reminder → SMS only, Bot API untouched")
    void should_linkDeliverFallBackAndStayOnSms_when_guestLinksThenBlocksBot() throws Exception {
        UUID first = insertLinkBooking(LINK_PHONE, at(now.plus(Duration.ofHours(24))));
        String startToken = TelegramLinkTestSupport.rawToken(linkService.issueForBooking(first, LINK_PHONE));
        TelegramWebhookTestSupport webhook = new TelegramWebhookTestSupport(restTemplate);

        assertThat(webhook.post(TelegramWebhookTestSupport.text(ALLOWED_CHAT, "/start " + startToken),
                WEBHOOK_SECRET, "198.51.100.41").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(webhook.post(TelegramWebhookTestSupport.contact(ALLOWED_CHAT, LINK_PHONE.substring(1), ALLOWED_CHAT),
                WEBHOOK_SECRET, "198.51.100.42").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(linkStatusAndChat()).as("405/406: contact share proved the booking phone")
                .isEqualTo("ACTIVE/" + ALLOWED_CHAT);
        assertThat(telegramRequests()).as("the webhook replies inline, never through the Bot API").isEmpty();

        reminderJob.sendReminders();

        assertThat(telegramTexts()).singleElement().asString().contains("Завтра о");
        assertThat(json(telegramRequests().get(0)).path("chat_id").asLong()).isEqualTo(ALLOWED_CHAT);
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));

        stubTelegram(403, GONE_BODY);
        advanceClock(Duration.ofMinutes(70));
        insertLinkBooking(LINK_PHONE, at(now.plus(Duration.ofHours(24))));

        reminderJob.sendReminders();

        assertThat(telegramRequests()).as("one Telegram attempt, answered 403").hasSize(1);
        assertThat(sentText()).as("the same reminder falls back to SMS").contains("Завтра о");
        assertThat(linkStatusAndChat()).isEqualTo("BLOCKED/" + ALLOWED_CHAT);

        resetTurbosms();
        stubTelegram(200, OK_BODY);
        advanceClock(Duration.ofMinutes(70));
        insertLinkBooking(LINK_PHONE, at(now.plus(Duration.ofHours(24))));

        reminderJob.sendReminders();

        assertThat(sentText()).contains("Завтра о");
        assertThat(telegramRequests()).as("a BLOCKED link is never tried again").isEmpty();
        assertNoPiiInLogs(LINK_PHONE, LINK_SUBSCRIBER_DIGITS);
    }

    @Test
    @DisplayName("audit-fix (markBlocked chat match): a 403 for the OLD chat never blocks the phone re-linked to a "
            + "new chat while the fallback SMS was in flight")
    void should_keepRelinkedPhoneActive_when_lateGoneArrivesForOldChat() throws Exception {
        link(LINK_PHONE, ALLOWED_CHAT);
        stubTelegram(403, GONE_BODY);
        // A slow SMS leg holds the router between its link lookup (old chat) and the post-SMS markBlocked.
        TURBOSMS.resetAll();
        TURBOSMS.stubFor(post(urlEqualTo(TURBOSMS_PATH)).willReturn(aResponse().withStatus(200)
                .withFixedDelay(1_500).withHeader("Content-Type", "application/json")
                .withBody("{\"response_status\":\"OK\"}")));
        insertLinkBooking(LINK_PHONE, at(now.plus(Duration.ofHours(2))));
        ExecutorService sweeper = Executors.newSingleThreadExecutor();
        try {
            Future<?> sweep = sweeper.submit(reminderJob::sweepTwoHour);
            Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> !telegramRequests().isEmpty());

            link(LINK_PHONE, RELINKED_CHAT);
            sweep.get(20, TimeUnit.SECONDS);
        } finally {
            sweeper.shutdownNow();
        }

        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(linkStatusAndChat()).as("the re-linked chat stays ACTIVE").isEqualTo("ACTIVE/" + RELINKED_CHAT);
    }

    @Test
    @DisplayName("always-SMS at IT level: guest OTP with an ACTIVE link → one SMS, the Bot API is never called")
    void should_sendOtpBySmsOnly_when_phoneLinked() {
        link(LINK_PHONE, ALLOWED_CHAT);

        ResponseEntity<String> response = sendOtp(LINK_PHONE);

        assertThat(response.getStatusCode().is2xxSuccessful()).as("body=%s", response.getBody()).isTrue();
        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(telegramRequests()).isEmpty();
    }

    @Test
    @DisplayName("always-SMS at IT level: public guest booking (confirmation + cancel link) with an ACTIVE link → "
            + "one SMS, no Bot API call")
    void should_sendGuestConfirmationBySmsOnly_when_phoneLinked() throws Exception {
        link(LINK_PHONE, ALLOWED_CHAT);

        ResponseEntity<String> created = createGuestBooking(tomorrowKyivAt(12));
        drainWorker.drain();

        assertThat(created.getStatusCode()).as("body=%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(sentText()).startsWith("Beautica: Запис підтверджено!").contains("/book/cancel/");
        assertThat(telegramRequests()).isEmpty();
    }

    @Test
    @DisplayName("always-SMS at IT level: the guest's own cancellation (public cancel link) with an ACTIVE link → "
            + "one SMS, no Bot API call")
    void should_sendGuestCancellationBySmsOnly_when_phoneLinked() throws Exception {
        link(LINK_PHONE, ALLOWED_CHAT);
        ResponseEntity<String> created = createGuestBooking(tomorrowKyivAt(12));
        assertThat(created.getStatusCode()).as("body=%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        drainWorker.drain();
        String cancelUrl = readJson(created.getBody()).findValue("cancelUrl").asText();
        resetTurbosms();
        stubTelegram(200, OK_BODY);

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", PublicBookingPageFixtures.randomIp());
        ResponseEntity<String> cancelled = restTemplate.exchange(
                "/api/v1/book/cancel/" + cancelUrl.substring(cancelUrl.lastIndexOf('/') + 1), HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
        drainWorker.drain();

        assertThat(cancelled.getStatusCode().is2xxSuccessful()).as("body=%s", cancelled.getBody()).isTrue();
        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(telegramRequests()).isEmpty();
    }

    @Test
    @DisplayName("always-SMS at IT level: provider reschedule of a linked LINK guest (431 notice) → one SMS, "
            + "no Bot API call")
    void should_sendRescheduleBySmsOnly_when_phoneLinked() throws Exception {
        link(LINK_PHONE, ALLOWED_CHAT);
        UUID bookingId = insertLinkBooking(LINK_PHONE, tomorrowKyivAt(12));
        authenticateAsOwner();

        bookingService.rescheduleBooking(salon.ownerId(), com.beautica.auth.Role.SALON_OWNER, bookingId,
                new com.beautica.booking.dto.RescheduleBookingRequest(tomorrowKyivAt(14), false));
        drainWorker.drain();

        assertThat(sentText()).contains("14:00");
        assertThat(telegramRequests()).isEmpty();
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private void advanceClock(Duration by) {
        clock.advance(by);
        now = clock.instant();
    }

    private String linkStatusAndChat() {
        return jdbcTemplate.queryForObject("SELECT status || '/' || chat_id FROM telegram_links", String.class);
    }

    /** A slot inside the seeded 09:00–17:00 schedule, tomorrow on the clock's Kyiv date. */
    private OffsetDateTime tomorrowKyivAt(int hour) {
        return now.atZone(TimeZones.KYIV).toLocalDate().plusDays(1).atTime(hour, 0).atZone(TimeZones.KYIV)
                .toOffsetDateTime();
    }

    private void stubTelegram(int status, String body) {
        TELEGRAM.resetAll();
        TELEGRAM.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void link(String phone, long chatId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                linkRepository.upsert(UUID.randomUUID(), phoneHasher.hash(phone).hash(), chatId, now));
    }

    /** A confirmed LINK booking created a day earlier (never short notice). */
    private UUID insertLinkBooking(String phone, OffsetDateTime startsAt) {
        return insertGuestLinkBooking(phone, startsAt, Duration.ofHours(1), now.minus(Duration.ofDays(1)));
    }

    private UUID insertWalkIn(String phone, OffsetDateTime startsAt) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, starts_at, "
                        + "ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, created_by_user_id, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 60, 0, 'STAFF', ?, ?, ?, ?, ?, ?)",
                bookingId, salon.masterId(), salon.masterServiceId(), salon.salonId(), startsAt, startsAt.plusHours(1),
                GUEST_NAME, GUEST_SURNAME, phone, salon.ownerId(), at(now.minus(Duration.ofDays(1))), at(now));
        return bookingId;
    }

    /** The appointment transition guard reads the caller from the SecurityContext (cleared after each test). */
    private void authenticateAsOwner() {
        var token = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                salon.ownerId(), null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_SALON_OWNER")));
        token.setDetails(salon.ownerId());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(token);
    }

    @org.junit.jupiter.api.AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private UUID insertWalkInVisit(String phone, OffsetDateTime startsAt, int items) {
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO appointments (id, salon_id, status, booking_source, guest_name, "
                        + "guest_surname, guest_phone, created_at, updated_at) "
                        + "VALUES (?, ?, 'CONFIRMED', 'STAFF', ?, ?, ?, ?, ?)",
                appointmentId, salon.salonId(), GUEST_NAME, GUEST_SURNAME, phone, at(now.minus(Duration.ofDays(1))),
                at(now));
        for (int i = 0; i < items; i++) {
            OffsetDateTime itemStart = startsAt.plusMinutes(10L * i);
            UUID bookingId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO bookings (id, master_id, master_service_id, salon_id, appointment_id, "
                            + "status, starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, guest_name, guest_surname, guest_phone, "
                            + "created_by_user_id, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, "
                            + "350.00, 10, 0, 'STAFF', ?, ?, ?, ?, ?, ?)",
                    bookingId, salon.masterId(), salon.masterServiceId(), salon.salonId(), appointmentId, itemStart,
                    itemStart.plusMinutes(10), GUEST_NAME, GUEST_SURNAME, phone, salon.ownerId(),
                    at(now.minus(Duration.ofDays(1))), at(now));
        }
        return appointmentId;
    }

    private void decline(UUID bookingId) {
        bookingService.declineBooking(salon.ownerId(), bookingId,
                new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null));
    }

    private String statusChangedRowStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM notification_outbox "
                + "WHERE event_type = 'STATUS_CHANGED' AND aggregate_id = ?", String.class, bookingId);
    }

    private List<LoggedRequest> telegramRequests() {
        return TELEGRAM.findAll(postRequestedFor(urlEqualTo(SEND_PATH)));
    }

    private List<String> telegramTexts() {
        return telegramRequests().stream().map(r -> json(r).path("text").asText()).toList();
    }

    private JsonNode json(LoggedRequest request) {
        return readJson(request.getBodyAsString());
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String kyivTime(OffsetDateTime startsAt) {
        return TIME_FMT.format(startsAt.atZoneSameInstant(TimeZones.KYIV));
    }
}
