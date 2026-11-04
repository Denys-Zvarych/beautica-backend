package com.beautica.booking;

import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.job.BookingReminderJob;
import com.beautica.booking.service.BookingService;
import com.beautica.common.TimeZones;
import com.beautica.common.security.PhoneHasher;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.telegram.link.TelegramLinkRepository;
import com.beautica.support.MutableTestClock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
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

import static com.beautica.booking.GuestMessageExpectedCopy.CANCEL_LINK_SUFFIX_REGEX;
import static com.beautica.booking.GuestMessageExpectedCopy.CONFIRMATION_HEADLINE;
import static com.beautica.booking.GuestMessageExpectedCopy.PROVIDER_CANCELLATION_BODY_REGEX;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 408 QA — {@code app.telegram.enabled=false} (the release default): a phone with an ACTIVE link still gets
 * every routed message by SMS, byte-for-byte the pre-408 text, and the link row is never touched. The Bot API base
 * URL and token point at the routing IT's WireMock on purpose, so "no Telegram call" is observed at a live wire
 * rather than assumed from a no-op bean.
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.booking.reminder-2h.enabled=true",
        "app.telegram.enabled=false",
        "app.telegram.bot-token=" + GuestMessageRoutingIT.BOT_TOKEN})
@Import(GuestTwoHourReminderIT.ClockConfig.class)
@DisplayName("GuestMessageTelegramOffIT — phase 408 flag off: every guest message by SMS exactly as before")
class GuestMessageTelegramOffIT extends AbstractWalkInBookingSmsIT {

    private static final Instant NOW = java.time.LocalDate.now(TimeZones.KYIV).atTime(12, 0)
            .atZone(TimeZones.KYIV).toInstant();
    private static final long CHAT = 7_300_000_001L;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    @DynamicPropertySource
    static void telegramEndpoint(DynamicPropertyRegistry registry) {
        registry.add("app.telegram.api-base-url", () -> "http://localhost:" + GuestMessageRoutingIT.TELEGRAM.port());
    }

    @Autowired private BookingReminderJob reminderJob;
    @Autowired private NotificationOutboxDrainWorker drainWorker;
    @Autowired private BookingService bookingService;
    @Autowired private TelegramLinkRepository linkRepository;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private MutableTestClock clock;

    @BeforeEach
    void pinClockAndLinkPhone() {
        clock.set(NOW);
        GuestMessageRoutingIT.TELEGRAM.resetAll();
        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                linkRepository.upsert(UUID.randomUUID(), phoneHasher.hash(LINK_PHONE).hash(), CHAT, NOW));
    }

    @Test
    @DisplayName("ACTIVE link + flag off → 24h and 2h reminders by SMS with the exact templates; no Bot API call, "
            + "link untouched")
    void should_sendRemindersBySms_when_telegramFlagOffAndPhoneLinked() throws Exception {
        OffsetDateTime in24h = at(NOW.plus(Duration.ofHours(24)));
        OffsetDateTime in2h = at(NOW.plus(Duration.ofHours(2)));
        insertLinkBooking(in24h);
        insertLinkBooking(in2h);

        reminderJob.sendReminders();
        String reminder24h = sentText();
        resetTurbosms();
        reminderJob.sweepTwoHour();

        assertThat(reminder24h).isEqualTo(
                salonReminderPrefix("завтра") + SERVICE_NAME + " о " + kyivTime(in24h));
        assertThat(sentText()).isEqualTo(
                salonReminderPrefix("сьогодні") + SERVICE_NAME + " о " + kyivTime(in2h));
        assertThat(telegramRequests()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT status || '/' || (last_sent_at IS NULL) FROM telegram_links",
                String.class)).isEqualTo("ACTIVE/true");
    }

    @Test
    @DisplayName("ACTIVE link + flag off → provider decline by exactly one SMS, no Bot API call")
    void should_sendDeclineBySms_when_telegramFlagOffAndPhoneLinked() throws Exception {
        UUID bookingId = insertLinkBooking(at(NOW.plus(Duration.ofDays(1))));

        bookingService.declineBooking(salon.ownerId(), bookingId,
                new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null));
        drainWorker.drain();

        assertThat(sentText()).containsPattern(PROVIDER_CANCELLATION_BODY_REGEX);
        TURBOSMS.verify(1, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(telegramRequests()).isEmpty();
    }


    @Test
    @DisplayName("user decision 2026-11-04: ACTIVE link + flag off → public guest booking confirmation + cancel link "
            + "by exactly one SMS, no Bot API call, link untouched")
    void should_sendGuestConfirmationBySms_when_telegramFlagOffAndPhoneLinked() throws Exception {
        OffsetDateTime startsAt = NOW.atZone(TimeZones.KYIV).toLocalDate().plusDays(1).atTime(12, 0)
                .atZone(TimeZones.KYIV).toOffsetDateTime();

        ResponseEntity<String> created = createGuestBooking(startsAt);
        drainWorker.drain();

        assertThat(created.getStatusCode()).as("body=%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(sentText()).startsWith(CONFIRMATION_HEADLINE)
                .endsWith(readJson(created.getBody()).findValue("cancelUrl").asText())
                .containsPattern(CANCEL_LINK_SUFFIX_REGEX);
        assertThat(telegramRequests()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("SELECT status || '/' || (last_sent_at IS NULL) FROM telegram_links",
                String.class)).as("flag off never reads past the gate, so the link is never touched")
                .isEqualTo("ACTIVE/true");
    }
    private UUID insertLinkBooking(OffsetDateTime startsAt) {
        return insertGuestLinkBooking(LINK_PHONE, startsAt, Duration.ofHours(1), NOW.minus(Duration.ofDays(1)));
    }

    private static List<LoggedRequest> telegramRequests() {
        return GuestMessageRoutingIT.TELEGRAM.findAll(postRequestedFor(urlEqualTo(
                "/bot" + GuestMessageRoutingIT.BOT_TOKEN + "/sendMessage")));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String kyivTime(OffsetDateTime startsAt) {
        return TIME_FMT.format(startsAt.atZoneSameInstant(TimeZones.KYIV));
    }
}
