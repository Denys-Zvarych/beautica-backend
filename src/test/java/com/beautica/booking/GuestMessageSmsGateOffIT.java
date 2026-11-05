package com.beautica.booking;

import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.job.BookingReminderJob;
import com.beautica.booking.service.BookingService;
import com.beautica.common.TimeZones;
import com.beautica.common.security.PhoneHasher;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.telegram.link.TelegramLinkRepository;
import com.beautica.notification.telegram.link.TelegramLinkTestSupport;
import com.beautica.support.MutableTestClock;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 408 D8 (audit-fix decision, notifications dark until release) — {@code app.booking.sms.enabled=false} with
 * Telegram live and the phone ACTIVE-linked: no routed guest message leaves on ANY channel. Both provider wires are
 * stubbed 200, so a leak on either would be recorded rather than lost to an error.
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=false",
        "app.booking.reminder-2h.enabled=true",
        "app.telegram.enabled=true",
        "app.telegram.mode=live",
        "app.telegram.bot-username=" + TelegramLinkTestSupport.BOT_USERNAME,
        "app.telegram.webhook-secret=" + GuestMessageRoutingIT.WEBHOOK_SECRET,
        "app.telegram.bot-token=" + GuestMessageRoutingIT.BOT_TOKEN})
@Import({GuestMessageRoutingIT.ClockConfig.class, GuestMessageRoutingIT.AllowListConfig.class})
@DisplayName("GuestMessageSmsGateOffIT — phase 408 D8: SMS gate off → nothing on any channel")
class GuestMessageSmsGateOffIT extends AbstractWalkInBookingSmsIT {

    /** The routing IT's allow-listed chat, so only the D8 gate can stop a Telegram send here. */
    private static final long ALLOWED_CHAT = 7_300_000_001L;
    private static final String SEND_PATH = "/bot" + GuestMessageRoutingIT.BOT_TOKEN + "/sendMessage";
    private static final Instant BASE = java.time.LocalDate.now(TimeZones.KYIV).atTime(12, 0)
            .atZone(TimeZones.KYIV).toInstant();

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

    private Instant now;

    @BeforeEach
    void stubTelegramAndLinkPhones() {
        Instant previous = clock.instant();
        now = (previous.isBefore(BASE) ? BASE : previous).plus(Duration.ofMinutes(1));
        clock.set(now);
        GuestMessageRoutingIT.TELEGRAM.resetAll();
        GuestMessageRoutingIT.TELEGRAM.stubFor(post(urlEqualTo(SEND_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"ok\":true,\"result\":{\"message_id\":1}}")));
        new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
            linkRepository.upsert(UUID.randomUUID(), phoneHasher.hash(LINK_PHONE).hash(), ALLOWED_CHAT, now);
        });
    }

    @Test
    @DisplayName("ACTIVE link + SMS gate off → neither the 24h nor the 2h reminder leaves on Telegram or SMS")
    void should_sendNothingOnAnyChannel_when_smsGateOffAndRemindersDue() {
        insertLinkBooking(at(now.plus(Duration.ofHours(24))));
        insertLinkBooking(at(now.plus(Duration.ofHours(2))));

        reminderJob.sendReminders();
        reminderJob.sweepTwoHour();

        assertThat(telegramRequests()).isEmpty();
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("ACTIVE link + SMS gate off → provider decline leaves on no channel")
    void should_sendNothingOnAnyChannel_when_smsGateOffAndProviderDeclines() {
        UUID bookingId = insertLinkBooking(at(now.plus(Duration.ofDays(1))));

        bookingService.declineBooking(salon.ownerId(), bookingId,
                new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null));
        drainWorker.drain();

        assertThat(telegramRequests()).isEmpty();
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    private UUID insertLinkBooking(OffsetDateTime startsAt) {
        return insertGuestLinkBooking(LINK_PHONE, startsAt, Duration.ofHours(1), now.minus(Duration.ofDays(1)));
    }

    private static List<LoggedRequest> telegramRequests() {
        return GuestMessageRoutingIT.TELEGRAM.findAll(postRequestedFor(urlEqualTo(SEND_PATH)));
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
