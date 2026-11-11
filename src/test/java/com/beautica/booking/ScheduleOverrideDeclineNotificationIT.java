package com.beautica.booking;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.service.ScheduleOverrideConflictService;
import com.beautica.common.security.PhoneHasher;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.notification.inapp.service.InAppNotificationService.BookingEventTarget;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.telegram.TelegramMessenger;
import com.beautica.notification.telegram.link.TelegramLinkRepository;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Phase 474 (SCH-37 / NTF-40, reverses the 2026-07-26 D6) — a day off or narrowed hours saved with
 * {@code cancelOverlapping=true} notifies every declined booking exactly like a provider decline, one
 * notice per visit, through the real override endpoint, the real outbox drain and the real gated
 * {@code SmsService} (Turbosms against WireMock).
 *
 * <p>NTF-D04 (locked 2026-11-11): a registered client gets feed + push (+ the existing e-mail) only —
 * never SMS or Telegram.
 *
 * <p><b>Own context (phase 474 QA).</b> The Telegram channel is ON ({@code capture} mode, so no Bot API
 * credentials) with the {@link TelegramMessenger} replaced by a Mockito mock: the router's real link lookup and
 * fallback run, and "zero Telegram" for a registered client is only meaningful because the linked-guest
 * control test proves the channel is live here. A pure-observer {@link StatementInspector} backs the
 * statement-count guard. Guests whose phone is not linked still go by SMS, so every other test is unchanged.
 */
@TestPropertySource(properties = {
        "app.booking.sms.enabled=true",
        "app.telegram.enabled=true",
        "app.telegram.mode=capture"})
@Import(ScheduleOverrideDeclineNotificationIT.SqlCaptureConfig.class)
@DisplayName("ScheduleOverrideDeclineNotificationIT — phase 474 day-off / narrowed-hours declines notify")
class ScheduleOverrideDeclineNotificationIT extends AbstractWalkInBookingSmsIT {

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Autowired
    private ScheduleOverrideConflictService scheduleOverrideConflictService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TelegramLinkRepository linkRepository;

    @Autowired
    private PhoneHasher phoneHasher;

    @Autowired
    private InAppNotificationService inAppNotificationService;

    @Autowired
    private BookingRepository bookingRepository;

    @MockitoBean
    private TelegramMessenger telegramMessenger;

    private static final long LINKED_CHAT = 7_474_000_001L;

    /** SQL issued on request (Tomcat) threads while {@link #capturing} is set — never the test's own JdbcTemplate. */
    static final List<String> CAPTURED_SQL = new CopyOnWriteArrayList<>();
    static volatile boolean capturing;

    @Test
    @DisplayName("parity — a registered client's booking declined by a day off produces the same outbox and feed "
            + "rows as PATCH /bookings/{id}/decline with no note; draining it sends zero SMS (NTF-D04)")
    void should_matchProviderDeclineNotifications_when_registeredClientBookingDeclinedByDayOff() {
        SeededUser client = insertUser("CLIENT", null);
        OffsetDateTime first = tomorrowAtNoon();
        OffsetDateTime second = first.plusDays(1);
        UUID patchDeclined = insertRegisteredBooking(client.id(), first);
        UUID overrideDeclined = insertRegisteredBooking(client.id(), second);
        String ownerToken = tokenFor(salon.ownerEmail());

        ResponseEntity<String> patch = restTemplate.exchange(
                "/api/v1/bookings/" + patchDeclined + "/decline", HttpMethod.PATCH,
                new HttpEntity<>("{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\"}", bearerHeaders(ownerToken)),
                String.class);
        List<String> patchOutbox = outboxEventTypes();
        List<String> patchFeed = feedRows();
        clearNotifications();
        ResponseEntity<String> put = putDayOff(salon.masterId(), ownerToken, second.toLocalDate(), true);
        List<String> overrideOutbox = outboxEventTypes();
        List<String> overrideFeed = feedRows();
        drainWorker.drain();

        assertThat(patch.getStatusCode()).as("decline body=%s", patch.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(put.getStatusCode()).as("override body=%s", put.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(bookingStatus(overrideDeclined)).isEqualTo("DECLINED");
        assertThat(overrideOutbox).as("same outbox rows as a provider decline").isEqualTo(patchOutbox)
                .contains("STATUS_CHANGED");
        assertThat(overrideFeed).as("same feed rows as a provider decline").isEqualTo(patchFeed)
                .containsExactly("BOOKING_DECLINED:" + client.id());
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("registered client's visit of 3 services, 2 conflict with narrowed hours — one feed row and one "
            + "STATUS_CHANGED row for the visit, zero SMS")
    void should_notifyOncePerVisit_when_twoOfThreeRegisteredVisitItemsConflict() {
        SeededUser client = insertUser("CLIENT", null);
        OffsetDateTime start = tomorrowAt(LocalTime.of(10, 0));
        UUID appointmentId = insertRegisteredAppointment(client.id());
        UUID first = insertRegisteredItem(client.id(), appointmentId, start);
        UUID second = insertRegisteredItem(client.id(), appointmentId, start.plusHours(1));
        UUID third = insertRegisteredItem(client.id(), appointmentId, start.plusHours(2));

        ResponseEntity<String> put = putOverride(salon.masterId(), tokenFor(salon.ownerEmail()),
                new ScheduleOverrideRequest(start.toLocalDate(), ScheduleExceptionKind.CUSTOM_HOURS,
                        WeekdayMode.INTERVAL, List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(11, 0))),
                        null, true));
        drainWorker.drain();

        assertThat(put.getStatusCode()).as("override body=%s", put.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(bookingStatus(first)).as("partial decline — the in-hours item survives").isEqualTo("CONFIRMED");
        assertThat(bookingStatus(second)).isEqualTo("DECLINED");
        assertThat(bookingStatus(third)).isEqualTo("DECLINED");
        assertThat(outboxCount("STATUS_CHANGED")).as("D4 — one row per visit").isEqualTo(1);
        assertThat(outboxAggregate("STATUS_CHANGED")).as("representative = earliest declined item").isEqualTo(second);
        assertThat(feedRows()).containsExactly("BOOKING_DECLINED:" + client.id());
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("LINK guest single booking declined by a day off — exactly ONE fixed GST-41 SMS with the guest's "
            + "own page link, no note")
    void should_sendOneGuestSms_when_linkGuestBookingDeclinedByDayOff() throws Exception {
        OffsetDateTime startsAt = tomorrowAtNoon();
        SettledGuest guest = createGuestBookingSettled(startsAt);

        ResponseEntity<String> put = putDayOff(salon.masterId(), tokenFor(salon.ownerEmail()),
                startsAt.toLocalDate(), true);
        drainWorker.drain();

        assertThat(put.getStatusCode()).as("override body=%s", put.getBody()).isEqualTo(HttpStatus.OK);
        String text = sentText();
        assertThat(text).isEqualTo(expectedBody(startsAt) + "\n\nДеталі або записатись знову " + guest.cancelUrl());
        assertThat(guest.cancelUrl()).contains("/c/");
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText()).isEqualTo(LINK_PHONE);
    }

    @Test
    @DisplayName("GST-75 — LINK guest visit of 3 services, 2 conflict: one SMS per declined service, each naming "
            + "that service's own start; the in-hours service gets none")
    void should_sendOneSmsPerDeclinedService_when_twoOfThreeGuestVisitItemsConflict() throws Exception {
        OffsetDateTime start = tomorrowAt(LocalTime.of(10, 0));
        UUID second = insertService(salon.masterId(), "SALON", salon.salonId());
        UUID third = insertService(salon.masterId(), "SALON", salon.salonId());
        createGuestVisitSettled(start, List.of(salon.masterServiceId(), second, third));

        ResponseEntity<String> put = putOverride(salon.masterId(), tokenFor(salon.ownerEmail()),
                new ScheduleOverrideRequest(start.toLocalDate(), ScheduleExceptionKind.CUSTOM_HOURS,
                        WeekdayMode.INTERVAL, List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(11, 0))),
                        null, true));
        drainWorker.drain();

        assertThat(put.getStatusCode()).as("override body=%s", put.getBody()).isEqualTo(HttpStatus.OK);
        List<OffsetDateTime> declinedStarts = declinedStarts();
        assertThat(declinedStarts).hasSize(2);
        assertThat(outboxCount("STATUS_CHANGED")).as("one guest row per declined service").isEqualTo(2);
        assertThat(sentTexts()).as("one SMS per declined service, each naming its own start")
                .hasSize(2)
                .anyMatch(text -> text.startsWith(expectedBody(declinedStarts.get(0))))
                .anyMatch(text -> text.startsWith(expectedBody(declinedStarts.get(1))))
                .noneMatch(text -> text.startsWith(expectedBody(start)));
    }

    @Test
    @DisplayName("GST-75 — Telegram-linked LINK guest, whole 3-service visit declined by a day off: one Telegram "
            + "message per service, zero SMS")
    void should_sendOneTelegramPerDeclinedService_when_linkedGuestVisitDeclinedByDayOff() {
        OffsetDateTime start = tomorrowAt(LocalTime.of(10, 0));
        UUID second = insertService(salon.masterId(), "SALON", salon.salonId());
        UUID third = insertService(salon.masterId(), "SALON", salon.salonId());
        createGuestVisitSettled(start, List.of(salon.masterServiceId(), second, third));
        resetTurbosms();
        link(LINK_PHONE, LINKED_CHAT);

        ResponseEntity<String> put = putDayOff(salon.masterId(), tokenFor(salon.ownerEmail()),
                start.toLocalDate(), true);
        drainWorker.drain();

        assertThat(put.getStatusCode()).as("override body=%s", put.getBody()).isEqualTo(HttpStatus.OK);
        org.mockito.ArgumentCaptor<String> text = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(telegramMessenger, org.mockito.Mockito.times(3)).sendWaiting(eq(LINKED_CHAT), text.capture());
        List<OffsetDateTime> declinedStarts = declinedStarts();
        assertThat(declinedStarts).hasSize(3);
        assertThat(text.getAllValues()).as("each message names its own service's start")
                .allMatch(value -> declinedStarts.stream().anyMatch(at -> value.startsWith(expectedBody(at))))
                .extracting(value -> value.substring(0, expectedBody(start).length()))
                .doesNotHaveDuplicates();
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("STAFF walk-in with a phone declined by a day off — exactly ONE SMS to the walk-in's number")
    void should_sendSms_when_walkInWithPhoneDeclinedByDayOff() throws Exception {
        OffsetDateTime startsAt = tomorrowAtNoon();
        String ownerToken = tokenFor(salon.ownerEmail());
        postWalkIn(salon.masterId(), List.of(salon.masterServiceId()), ownerToken, startsAt);
        drainWorker.drain();
        resetTurbosms();

        ResponseEntity<String> put = putDayOff(salon.masterId(), ownerToken, startsAt.toLocalDate(), true);
        drainWorker.drain();

        assertThat(put.getStatusCode()).as("override body=%s", put.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(sentText()).startsWith(expectedBody(startsAt));
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText()).isEqualTo(E164_PHONE);
    }

    /**
     * A phoneless account-less walk-in cannot exist ({@code chk_bookings_guest_fields}: a STAFF row with no
     * client requires {@code guest_phone}); the only phoneless walk-in is one booked for a REGISTERED client.
     */
    @Test
    @DisplayName("STAFF walk-in booked for a REGISTERED client (no guest phone) declined by a day off — feed row, "
            + "zero SMS, no error")
    void should_sendNoSms_when_registeredClientWalkInDeclinedByDayOff() {
        SeededUser client = insertUser("CLIENT", null);
        OffsetDateTime startsAt = tomorrowAtNoon();
        UUID bookingId = insertRegisteredWalkIn(client.id(), startsAt);

        ResponseEntity<String> put = putDayOff(salon.masterId(), tokenFor(salon.ownerEmail()),
                startsAt.toLocalDate(), true);
        drainWorker.drain();

        assertThat(put.getStatusCode()).as("override body=%s", put.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(bookingStatus(bookingId)).isEqualTo("DECLINED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE status = 'DEAD'", Integer.class)).isZero();
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("owner sets an invited SALON_MASTER's day off — that master gets the decline feed row and the "
            + "MASTER_BOOKING_DECLINED row (NTF-17)")
    void should_notifyPerformingMaster_when_ownerSetsSalonMasterDayOff() {
        OffsetDateTime startsAt = tomorrowAtNoon();
        SeededUser masterUser = insertUser("SALON_MASTER", salon.salonId());
        UUID salonMasterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                salonMasterId, masterUser.id(), salon.salonId());
        UUID masterServiceId = insertService(salonMasterId, "SALON", salon.salonId());
        insertConfirmedLinkBooking(salonMasterId, masterServiceId, startsAt);

        ResponseEntity<String> put = putDayOff(salonMasterId, tokenFor(salon.ownerEmail()),
                startsAt.toLocalDate(), true);

        assertThat(put.getStatusCode()).as("override body=%s", put.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(feedRows()).contains("BOOKING_DECLINED:" + masterUser.id());
        assertThat(outboxCount("MASTER_BOOKING_DECLINED")).isEqualTo(1);
    }

    @Test
    @DisplayName("an independent master's OWN day off — no self-notice (actor excluded)")
    void should_notNotifyActor_when_independentMasterSetsOwnDayOff() {
        OffsetDateTime startsAt = tomorrowAtNoon();
        Independent independent = seedIndependentMaster();
        insertConfirmedLinkBooking(independent.masterId(), independent.masterServiceId(), null, startsAt);

        ResponseEntity<String> put = putDayOff(independent.masterId(), tokenFor(independent.email()),
                startsAt.toLocalDate(), true);

        assertThat(put.getStatusCode()).as("override body=%s", put.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(feedRows()).noneMatch(row -> row.endsWith(independent.userId().toString()));
        assertThat(outboxCount("MASTER_BOOKING_DECLINED")).isZero();
        assertThat(outboxCount("STATUS_CHANGED")).as("the guest is still told").isEqualTo(1);
    }

    @Test
    @DisplayName("write refused (409, no cancelOverlapping) — nothing enqueued, nothing sent")
    void should_enqueueNothing_when_overrideRefusedWithoutCancelOverlapping() {
        SeededUser client = insertUser("CLIENT", null);
        OffsetDateTime startsAt = tomorrowAtNoon();
        UUID bookingId = insertRegisteredBooking(client.id(), startsAt);

        ResponseEntity<String> put = putDayOff(salon.masterId(), tokenFor(salon.ownerEmail()),
                startsAt.toLocalDate(), false);
        drainWorker.drain();

        assertThat(put.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(bookingStatus(bookingId)).isEqualTo("CONFIRMED");
        assertThat(outboxEventTypes()).isEmpty();
        assertThat(feedRows()).isEmpty();
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("rollback mid-write — no outbox and no feed rows (same transaction as the declines)")
    void should_leaveNoNotificationRows_when_overrideTransactionRollsBack() {
        SeededUser client = insertUser("CLIENT", null);
        OffsetDateTime startsAt = tomorrowAtNoon();
        UUID bookingId = insertRegisteredBooking(client.id(), startsAt);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            scheduleOverrideConflictService.applyOverrideWithConflictHandling(salon.ownerId(), salon.masterId(),
                    new ScheduleOverrideRequest(startsAt.toLocalDate(), ScheduleExceptionKind.DAY_OFF,
                            WeekdayMode.INTERVAL, List.of(), null, true));
            status.setRollbackOnly();
        });
        drainWorker.drain();

        assertThat(bookingStatus(bookingId)).isEqualTo("CONFIRMED");
        assertThat(outboxEventTypes()).isEmpty();
        assertThat(feedRows()).isEmpty();
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("NTF-D04 — a registered client whose phone IS Telegram-linked, declined by a day off: feed row, "
            + "zero Telegram, zero SMS")
    void should_sendNoTelegramAndNoSms_when_registeredClientWithLinkedPhoneDeclinedByDayOff() {
        SeededUser client = insertUser("CLIENT", null);
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?", LINK_PHONE, client.id());
        link(LINK_PHONE, LINKED_CHAT);
        OffsetDateTime startsAt = tomorrowAtNoon();
        UUID bookingId = insertRegisteredBooking(client.id(), startsAt);

        ResponseEntity<String> put = putDayOff(salon.masterId(), tokenFor(salon.ownerEmail()),
                startsAt.toLocalDate(), true);
        drainWorker.drain();

        assertThat(put.getStatusCode()).as("override body=%s", put.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(bookingStatus(bookingId)).isEqualTo("DECLINED");
        assertThat(feedRows()).containsExactly("BOOKING_DECLINED:" + client.id());
        assertThat(outboxRowStatus("STATUS_CHANGED")).as("the drain processed the row (no silent skip)")
                .isEqualTo("SENT");
        verify(telegramMessenger, never()).sendWaiting(anyLong(), anyString());
        verify(telegramMessenger, never()).send(anyLong(), anyString());
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("control — a LINK guest whose phone is Telegram-linked, declined by a day off: exactly ONE Telegram "
            + "message with the fixed GST-41 text, zero SMS")
    void should_sendOneTelegramAndNoSms_when_linkedGuestDeclinedByDayOff() {
        OffsetDateTime startsAt = tomorrowAtNoon();
        SettledGuest guest = createGuestBookingSettled(startsAt);
        resetTurbosms();
        link(LINK_PHONE, LINKED_CHAT);

        ResponseEntity<String> put = putDayOff(salon.masterId(), tokenFor(salon.ownerEmail()),
                startsAt.toLocalDate(), true);
        drainWorker.drain();

        assertThat(put.getStatusCode()).as("override body=%s", put.getBody()).isEqualTo(HttpStatus.OK);
        org.mockito.ArgumentCaptor<String> text = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(telegramMessenger).sendWaiting(eq(LINKED_CHAT), text.capture());
        assertThat(text.getValue()).isEqualTo(expectedBody(startsAt) + "\n\nДеталі або записатись знову "
                + guest.cancelUrl());
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("a Telegram-linked LINK guest whose Telegram send fails, declined by a day off — exactly ONE SMS "
            + "fallback to the guest's number")
    void should_fallBackToOneSms_when_telegramSendFailsForDayOffDecline() throws Exception {
        link(LINK_PHONE, LINKED_CHAT);
        doThrow(new IllegalStateException("telegram down")).when(telegramMessenger).sendWaiting(anyLong(), anyString());
        OffsetDateTime startsAt = tomorrowAtNoon();
        insertGuestLinkBooking(LINK_PHONE, startsAt, Duration.ofHours(1), Instant.now().minus(Duration.ofDays(1)));

        ResponseEntity<String> put = putDayOff(salon.masterId(), tokenFor(salon.ownerEmail()),
                startsAt.toLocalDate(), true);
        drainWorker.drain();

        assertThat(put.getStatusCode()).as("override body=%s", put.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(sentText()).startsWith(expectedBody(startsAt));
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText()).isEqualTo(LINK_PHONE);
    }

    @Test
    @DisplayName("D4 — two registered visits each PARTIALLY declined by narrowed hours: exactly one STATUS_CHANGED "
            + "row and one feed row per visit, on each visit's earliest declined item; in-hours items stay CONFIRMED")
    void should_notifyExactlyOncePerVisit_when_twoVisitsArePartiallyDeclined() {
        SeededUser client = insertUser("CLIENT", null);
        OffsetDateTime day = tomorrowAt(LocalTime.of(9, 0));
        UUID visitA = insertRegisteredAppointment(client.id());
        UUID aKept = insertRegisteredItem(client.id(), visitA, day.plusHours(1));
        UUID aFirstDeclined = insertRegisteredItem(client.id(), visitA, day.plusHours(2));
        UUID aSecondDeclined = insertRegisteredItem(client.id(), visitA, day.plusHours(3));
        UUID visitB = insertRegisteredAppointment(client.id());
        UUID bKept = insertRegisteredItem(client.id(), visitB, day);
        UUID bDeclined = insertRegisteredItem(client.id(), visitB, day.plusHours(4));

        ResponseEntity<String> put = putOverride(salon.masterId(), tokenFor(salon.ownerEmail()),
                new ScheduleOverrideRequest(day.toLocalDate(), ScheduleExceptionKind.CUSTOM_HOURS,
                        WeekdayMode.INTERVAL, List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(11, 0))),
                        null, true));

        assertThat(put.getStatusCode()).as("override body=%s", put.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(List.of(bookingStatus(aKept), bookingStatus(bKept))).containsOnly("CONFIRMED");
        assertThat(List.of(bookingStatus(aFirstDeclined), bookingStatus(aSecondDeclined), bookingStatus(bDeclined)))
                .containsOnly("DECLINED");
        assertThat(jdbcTemplate.queryForList("SELECT aggregate_id FROM notification_outbox "
                + "WHERE event_type = 'STATUS_CHANGED'", UUID.class))
                .as("one row per visit, keyed on its earliest declined item")
                .containsExactlyInAnyOrder(aFirstDeclined, bDeclined);
        assertThat(jdbcTemplate.queryForList("SELECT booking_id FROM in_app_notification "
                + "WHERE type = 'BOOKING_DECLINED' AND recipient_user_id = ?", UUID.class, client.id()))
                .as("one feed row per visit (partial = booking-keyed notice)")
                .containsExactlyInAnyOrder(aFirstDeclined, bDeclined);
    }

    /**
     * Perf guard (phase 474 QA). The notification leg of an override write must cost a CONSTANT number of
     * statements whatever the number of declined visits: 1 visit and 20 visits must issue the same count of
     * statements touching {@code in_app_notification} / {@code notification_outbox}. Scoped to the notification
     * tables on purpose — the decline leg ({@code declineAppointmentItems} once per visit) predates phase 474 and
     * is out of its scope; the total is printed in the failure message for context.
     */
    @Test
    @DisplayName("perf — the notification statements of one override write stay FLAT as declined visits grow "
            + "(1 vs 20 whole visits)")
    void should_keepNotificationStatementCountFlat_asDeclinedVisitCountGrows() {
        SeededUser client = insertUser("CLIENT", null);
        String ownerToken = tokenFor(salon.ownerEmail());
        Map<String, Integer> one = declineVisitsAndCountStatements(client.id(), ownerToken, 1,
                tomorrowAt(LocalTime.of(7, 0)));
        Map<String, Integer> twenty = declineVisitsAndCountStatements(client.id(), ownerToken, 20,
                tomorrowAt(LocalTime.of(7, 0)).plusDays(1));

        assertThat(one.get("notification")).as("fixture: the inspector saw the notification writes").isPositive();
        assertThat(twenty.get("notification"))
                .as("notification statements must not scale with visits — 1 visit=%s, 20 visits=%s "
                        + "(whole write: 1 visit=%s, 20 visits=%s statements)", one, twenty,
                        one.get("total"), twenty.get("total"))
                .isEqualTo(one.get("notification"));
    }

    /**
     * Perf INFO (phase 474 QA) — beyond 50 queued outbox rows the auto-flush splits them into JDBC batches of
     * {@code hibernate.jdbc.batch_size} (50), so the notification leg may grow by one statement per extra batch but
     * never by one per visit: 60 single-item visits (60 outbox rows, under the 100-conflict cap) stay within
     * {@code 1-visit count + ceil(outboxRows / 50)}.
     */
    @Test
    @DisplayName("perf — 60 visits (outbox rows past one JDBC batch of 50) keep the notification statements "
            + "bounded by the batch count, never one per visit")
    void should_boundNotificationStatementsByBatchCount_when_sixtyVisitsDeclined() {
        SeededUser client = insertUser("CLIENT", null);
        String ownerToken = tokenFor(salon.ownerEmail());
        Map<String, Integer> one = declineVisitsAndCountStatements(client.id(), ownerToken, 1, 1,
                tomorrowAt(LocalTime.of(7, 0)));
        int outboxBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class);
        Map<String, Integer> sixty = declineVisitsAndCountStatements(client.id(), ownerToken, 60, 1,
                tomorrowAt(LocalTime.of(7, 0)).plusDays(1));
        int outboxRows = jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class)
                - outboxBefore;
        int batches = (outboxRows + 49) / 50;

        assertThat(outboxRows).as("fixture: 60 visits must queue more than one JDBC batch of outbox rows")
                .isGreaterThan(50);
        assertThat(sixty.get("notification"))
                .as("60 visits (%s outbox rows, %s batches) must cost at most 1-visit count + batches; 1=%s, 60=%s",
                        outboxRows, batches, one, sixty)
                .isLessThanOrEqualTo(one.get("notification") + batches)
                .isLessThan(60);
    }

    /**
     * Perf guard (phase 478, AC4) — per-service guest rows (GST-75) still JDBC-batch: 30 two-service LINK guest
     * visits queue 60 {@code STATUS_CHANGED} rows (plus the per-visit master rows), and the notification leg stays
     * within the 1-visit count plus one statement per extra batch of 50 — never one per row or per visit.
     */
    @Test
    @DisplayName("perf — 30 guest visits x 2 declined services (one outbox row per service) keep the notification "
            + "statements bounded by the batch count")
    void should_boundNotificationStatementsByBatchCount_when_guestVisitsDeclinedPerService() {
        String ownerToken = tokenFor(salon.ownerEmail());
        Map<String, Integer> one = declineGuestVisitsAndCountStatements(ownerToken, 1, 2,
                tomorrowAt(LocalTime.of(7, 0)));
        int outboxBefore = jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class);
        Map<String, Integer> thirty = declineGuestVisitsAndCountStatements(ownerToken, 30, 2,
                tomorrowAt(LocalTime.of(7, 0)).plusDays(1));
        int outboxRows = jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class)
                - outboxBefore;
        int batches = (outboxRows + 49) / 50;

        assertThat(outboxRows).as("fixture: per-service guest rows exceed one JDBC batch").isGreaterThan(50);
        assertThat(thirty.get("notification"))
                .as("30 guest visits (%s outbox rows, %s batches) must cost at most 1-visit count + batches; 1=%s, 30=%s",
                        outboxRows, batches, one, thirty)
                .isLessThanOrEqualTo(one.get("notification") + batches)
                .isLessThan(30);
    }

    /**
     * Security INFO (phase 474 QA) — an independent master has no salon, so the bulk statement binds a
     * {@code NULL} element in its {@code salon_id} uuid[]. With an actor other than the master (a system/admin
     * actor) BOTH the client's and the master's rows are written, and neither may pick up any salon id.
     */
    @Test
    @DisplayName("bulk decline of an independent master's registered-client booking, actor ≠ master — client and "
            + "master feed rows both carry salon_id NULL")
    void should_writeNullSalonId_when_bulkDeclineNotifiesIndependentMastersClient() {
        SeededUser client = insertUser("CLIENT", null);
        Independent independent = seedIndependentMaster();
        UUID bookingId = UUID.randomUUID();
        OffsetDateTime startsAt = tomorrowAtNoon();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 'CONFIRMED', ?, ?, 350.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, client.id(), independent.masterId(), independent.masterServiceId(),
                startsAt, startsAt.plusHours(1));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Booking booking = bookingRepository.findByIdWithFullGraph(bookingId).orElseThrow();
            inAppNotificationService.notifyBookingEventsBulk(InAppNotificationType.BOOKING_DECLINED,
                    List.of(BookingEventTarget.forBooking(booking)), UUID.randomUUID());
        });

        List<Map<String, Object>> rows = jdbcTemplate.queryForList("SELECT recipient_user_id, booking_id, salon_id "
                + "FROM in_app_notification WHERE type = 'BOOKING_DECLINED'");
        assertThat(rows).extracting(r -> r.get("recipient_user_id"))
                .containsExactlyInAnyOrder(client.id(), independent.userId());
        assertThat(rows).extracting(r -> r.get("booking_id")).containsOnly(bookingId);
        assertThat(rows).extracting(r -> r.get("salon_id")).as("rows=%s", rows).containsOnlyNulls();
    }

    /**
     * Dev INFO (phase 474 QA) — the push twin {@code insertEventRowsBulkWithPush} against a real database: with
     * push enabled, one {@code INAPP_PUSH} outbox row per inserted feed row whose recipient holds an ACTIVE device
     * token (the client), none for a recipient whose only token is inactive (the performing master). The flag is
     * read at construction, so the bean's field is flipped for this test only and restored in {@code finally}.
     */
    @Test
    @DisplayName("push on — override decline writes feed rows via the push twin and ONE INAPP_PUSH row, for the "
            + "recipient with an active device token only")
    void should_enqueueInAppPushForActiveTokenOnly_when_pushEnabledOverrideDecline() {
        SeededUser client = insertUser("CLIENT", null);
        SeededUser masterUser = insertUser("SALON_MASTER", salon.salonId());
        UUID salonMasterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                salonMasterId, masterUser.id(), salon.salonId());
        UUID masterServiceId = insertService(salonMasterId, "SALON", salon.salonId());
        OffsetDateTime startsAt = tomorrowAtNoon();
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, client.id(), salonMasterId, masterServiceId, salon.salonId(),
                startsAt, startsAt.plusHours(1));
        jdbcTemplate.update("INSERT INTO device_tokens (user_id, token, platform, is_active) VALUES (?, ?, 'ANDROID', ?)",
                client.id(), "it-token-" + client.id(), true);
        jdbcTemplate.update("INSERT INTO device_tokens (user_id, token, platform, is_active) VALUES (?, ?, 'ANDROID', ?)",
                masterUser.id(), "it-token-" + masterUser.id(), false);

        ResponseEntity<String> put;
        ReflectionTestUtils.setField(inAppNotificationService, "pushEnabled", true);
        try {
            put = putDayOff(salonMasterId, tokenFor(salon.ownerEmail()), startsAt.toLocalDate(), true);
        } finally {
            ReflectionTestUtils.setField(inAppNotificationService, "pushEnabled", false);
        }

        assertThat(put.getStatusCode()).as("override body=%s", put.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(feedRows()).containsExactlyInAnyOrder(
                "BOOKING_DECLINED:" + client.id(), "BOOKING_DECLINED:" + masterUser.id());
        assertThat(jdbcTemplate.queryForList("SELECT n.recipient_user_id FROM notification_outbox o "
                + "JOIN in_app_notification n ON n.id = o.aggregate_id WHERE o.event_type = 'INAPP_PUSH'", UUID.class))
                .as("one push row, for the active-token recipient only")
                .containsExactly(client.id());
    }

    /** Declines {@code visits} whole 2-item registered visits with one DAY_OFF write; returns statement counts. */
    private Map<String, Integer> declineVisitsAndCountStatements(UUID clientId, String ownerToken, int visits,
                                                                 OffsetDateTime firstStart) {
        return declineVisitsAndCountStatements(clientId, ownerToken, visits, 2, firstStart);
    }

    /** As above with {@code itemsPerVisit} back-to-back 15-minute items per visit. */
    private Map<String, Integer> declineVisitsAndCountStatements(UUID clientId, String ownerToken, int visits,
                                                                 int itemsPerVisit, OffsetDateTime firstStart) {
        for (int i = 0; i < visits; i++) {
            UUID appointmentId = insertRegisteredAppointment(clientId);
            OffsetDateTime visitStart = firstStart.plusMinutes(15L * itemsPerVisit * i);
            for (int item = 0; item < itemsPerVisit; item++) {
                insertRegisteredItem(clientId, appointmentId, visitStart.plusMinutes(15L * item), 15);
            }
        }
        Map<String, Integer> counts = measureDayOffStatements(ownerToken, firstStart);
        assertThat(outboxCountForDeclinedVisits(firstStart.toLocalDate()))
                .as("fixture: every visit was notified").isEqualTo(visits);
        return counts;
    }

    /**
     * Phase 478 — guest twin of {@link #declineVisitsAndCountStatements}: {@code visits} LINK guest visits of
     * {@code itemsPerVisit} 15-minute items; every declined item gets its own {@code STATUS_CHANGED} row (GST-75).
     */
    private Map<String, Integer> declineGuestVisitsAndCountStatements(String ownerToken, int visits,
                                                                      int itemsPerVisit, OffsetDateTime firstStart) {
        Instant created = Instant.now().minus(Duration.ofDays(1));
        for (int i = 0; i < visits; i++) {
            UUID appointmentId = insertGuestLinkAppointment(LINK_PHONE, created, created);
            OffsetDateTime visitStart = firstStart.plusMinutes(15L * itemsPerVisit * i);
            for (int item = 0; item < itemsPerVisit; item++) {
                UUID itemId = insertGuestLinkBooking(LINK_PHONE, visitStart.plusMinutes(15L * item),
                        Duration.ofMinutes(15), created);
                jdbcTemplate.update("UPDATE bookings SET appointment_id = ? WHERE id = ?", appointmentId, itemId);
            }
        }
        Map<String, Integer> counts = measureDayOffStatements(ownerToken, firstStart);
        assertThat(outboxCountForDeclinedVisits(firstStart.toLocalDate()))
                .as("fixture: every declined guest service was notified").isEqualTo(visits * itemsPerVisit);
        return counts;
    }

    /** One DAY_OFF write on {@code firstStart}'s date, measured; returns notification-table and total statements. */
    private Map<String, Integer> measureDayOffStatements(String ownerToken, OffsetDateTime firstStart) {
        CAPTURED_SQL.clear();
        capturing = true;
        ResponseEntity<String> put;
        try {
            put = putDayOff(salon.masterId(), ownerToken, firstStart.toLocalDate(), true);
        } finally {
            capturing = false;
        }
        assertThat(put.getStatusCode()).as("override body=%s", put.getBody()).isEqualTo(HttpStatus.OK);
        List<String> sql = new ArrayList<>(CAPTURED_SQL);
        int notification = (int) sql.stream().map(q -> q.toLowerCase(Locale.ROOT))
                .filter(q -> q.contains("in_app_notification") || q.contains("notification_outbox"))
                .count();
        return Map.of("notification", notification, "total", sql.size());
    }

    private int outboxCountForDeclinedVisits(LocalDate date) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox o JOIN bookings b "
                + "ON b.id = o.aggregate_id WHERE o.event_type = 'STATUS_CHANGED' "
                + "AND (b.starts_at AT TIME ZONE 'Europe/Kyiv')::date = ?", Integer.class, date);
    }

    /** Pure observer on the Hibernate session factory; records only while a measured request is in flight. */
    @TestConfiguration
    static class SqlCaptureConfig {
        @Bean
        HibernatePropertiesCustomizer scheduleOverrideDeclineSqlCaptureCustomizer() {
            return props -> props.put("hibernate.session_factory.statement_inspector",
                    new CapturingStatementInspector());
        }
    }

    static final class CapturingStatementInspector implements StatementInspector {
        @Override
        public String inspect(String sql) {
            if (capturing && sql != null && isRequestThread(Thread.currentThread().getName())) {
                CAPTURED_SQL.add(sql);
            }
            return sql;
        }

        /** Tomcat request threads: {@code tomcat-handler-N} (virtual threads) or {@code http-nio-…-exec-N}. */
        private static boolean isRequestThread(String name) {
            return name.startsWith("tomcat-handler") || name.startsWith("http-nio");
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private void link(String phone, long chatId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(s ->
                linkRepository.upsert(UUID.randomUUID(), phoneHasher.hash(phone).hash(), chatId, Instant.now()));
    }

    private String outboxRowStatus(String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM notification_outbox WHERE event_type = ?", String.class, eventType);
    }

    private static String expectedBody(OffsetDateTime startsAt) {
        return "Beautica: Ваш запис " + DATE_FMT.format(startsAt.atZoneSameInstant(com.beautica.common.TimeZones.KYIV))
                + " о " + startsAt.atZoneSameInstant(com.beautica.common.TimeZones.KYIV).toLocalTime() + " скасовано.";
    }

    private ResponseEntity<String> putDayOff(UUID masterId, String token, LocalDate date, boolean cancelOverlapping) {
        return putOverride(masterId, token, new ScheduleOverrideRequest(
                date, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL, List.of(), null, cancelOverlapping));
    }

    private ResponseEntity<String> putOverride(UUID masterId, String token, ScheduleOverrideRequest request) {
        try {
            return restTemplate.exchange("/api/v1/masters/" + masterId + "/overrides/" + request.date(),
                    HttpMethod.PUT, new HttpEntity<>(objectMapper.writeValueAsString(request), bearerHeaders(token)),
                    String.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID insertRegisteredBooking(UUID clientId, OffsetDateTime startsAt) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(),
                startsAt, startsAt.plusHours(1));
        return bookingId;
    }

    private UUID insertRegisteredAppointment(UUID clientId) {
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO appointments (id, client_id, salon_id, status, booking_source, "
                + "created_at, updated_at) VALUES (?, ?, ?, 'CONFIRMED', 'APP', NOW(), NOW())",
                appointmentId, clientId, salon.salonId());
        return appointmentId;
    }

    private UUID insertRegisteredItem(UUID clientId, UUID appointmentId, OffsetDateTime startsAt) {
        return insertRegisteredItem(clientId, appointmentId, startsAt, 60);
    }

    private UUID insertRegisteredItem(UUID clientId, UUID appointmentId, OffsetDateTime startsAt, int minutes) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, "
                        + "appointment_id, status, starts_at, ends_at, price_at_booking, "
                        + "duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, ?, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(), appointmentId,
                startsAt, startsAt.plusMinutes(minutes), minutes);
        return bookingId;
    }

    private UUID insertRegisteredWalkIn(UUID clientId, OffsetDateTime startsAt) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 60, 0, 'STAFF', NOW(), NOW())",
                bookingId, clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(),
                startsAt, startsAt.plusHours(1));
        return bookingId;
    }

    /** Start instants of every DECLINED booking, earliest first. */
    private List<OffsetDateTime> declinedStarts() {
        return jdbcTemplate.queryForList("SELECT starts_at FROM bookings WHERE status = 'DECLINED' ORDER BY starts_at",
                OffsetDateTime.class);
    }

    private String bookingStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    /** Every outbox row's event type, sorted — the parity shape (ids differ by construction). */
    private List<String> outboxEventTypes() {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM notification_outbox ORDER BY event_type", String.class);
    }

    private int outboxCount(String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_outbox WHERE event_type = ?", Integer.class, eventType);
    }

    private UUID outboxAggregate(String eventType) {
        return jdbcTemplate.queryForObject(
                "SELECT aggregate_id FROM notification_outbox WHERE event_type = ?", UUID.class, eventType);
    }

    /** Every feed row as {@code TYPE:recipient}, sorted. */
    private List<String> feedRows() {
        return jdbcTemplate.queryForList(
                "SELECT type || ':' || recipient_user_id FROM in_app_notification ORDER BY 1", String.class);
    }

    /** Outbox before feed — no FK between them, order only for readability. */
    private void clearNotifications() {
        jdbcTemplate.update("DELETE FROM notification_outbox");
        jdbcTemplate.update("DELETE FROM in_app_notification");
    }
}
