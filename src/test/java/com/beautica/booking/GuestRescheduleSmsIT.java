package com.beautica.booking;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.PhoneSmsBudgetUsage;
import com.beautica.booking.service.GuestRescheduleSmsBudget;
import com.beautica.common.TimeZones;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.beautica.notification.service.BookingVisit;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Phase 431 — end to end with {@code app.booking.sms.enabled=true} (real gated {@code SmsService}
 * against WireMock, real outbox drain, synchronous {@code smsSendExecutor} under the test profile):
 *
 * <ul>
 *   <li><b>Part A</b> — a PROVIDER reschedule of an account-less guest booking (LINK guest or STAFF
 *       walk-in) sends the guest ONE fixed-copy SMS naming the new Kyiv date/time.</li>
 *   <li><b>Part B</b> — a PROVIDER reschedule or decline also e-mails the booking's PERFORMING master
 *       ("the salon moved / cancelled it") unless they are the actor; the in-app feed item comes from
 *       the existing phase 333 matrix and is asserted here so both channels are pinned together.</li>
 * </ul>
 *
 * <p>The fixture's default master is an owner-operated {@code SALON_OWNER} master, so the owner acting
 * on that master's booking is a SELF action (no master e-mail), while the owner or an admin acting on
 * an invited {@code SALON_MASTER}'s booking is not. Shares its context with
 * {@link GuestProviderCancellationSmsIT} and {@link WalkInBookingSmsEnabledIT}; the flag-off row lives
 * in {@link GuestRescheduleSmsDisabledIT}. Every e-mail assertion is on the {@code @MockBean}
 * {@code EmailNotificationService} declared by {@code AbstractIntegrationTest}.
 */
@TestPropertySource(properties = "app.booking.sms.enabled=true")
@DisplayName("GuestRescheduleSmsIT — phase 431 guest reschedule SMS + performing-master e-mail")
class GuestRescheduleSmsIT extends AbstractWalkInBookingSmsIT {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private BookingRepository bookingRepository;

    @Autowired
    private GuestRescheduleSmsBudget smsBudget;

    // ── Part A: guest SMS ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("provider reschedules a LINK guest booking — exactly ONE SMS to the guest phone with the NEW Kyiv "
            + "time, fixed copy, and neither the phone nor the body is logged")
    void should_sendOneSmsWithNewKyivTime_when_providerReschedulesLinkGuestBooking() throws Exception {
        UUID bookingId = insertConfirmedLinkBooking(salon.masterId(), salon.masterServiceId(), tomorrowAtNoon());
        OffsetDateTime newStart = tomorrowAt(LocalTime.of(14, 0));

        ResponseEntity<String> resp = rescheduleBooking(salon.ownerEmail(), bookingId, newStart);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        String text = sentText();
        assertThat(text).isEqualTo(expectedSms(newStart));
        assertThat(text.length()).isLessThanOrEqualTo(70);
        assertThat(text).doesNotContain(SERVICE_NAME).doesNotContain(MASTER_NAME).doesNotContain(GUEST_NAME);
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText()).isEqualTo(LINK_PHONE);
        assertNoPiiInLogs(LINK_PHONE, LINK_SUBSCRIBER_DIGITS);
        assertThat(logMessages()).noneMatch(line -> line.contains(text));
        assertThat(rescheduleRow().getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("provider reschedules a STAFF walk-in — exactly ONE SMS to the walk-in's phone (user decision 2026-10-30)")
    void should_sendOneSms_when_providerReschedulesWalkIn() throws Exception {
        assertThat(createWalkIn(tomorrowAtNoon()).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        drainWorker.drain();
        resetTurbosms();
        UUID bookingId = (UUID) onlyBooking().get("id");
        OffsetDateTime newStart = tomorrowAt(LocalTime.of(15, 0));

        ResponseEntity<String> resp = rescheduleBooking(salon.ownerEmail(), bookingId, newStart);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(sentText()).isEqualTo(expectedSms(newStart));
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText()).isEqualTo(E164_PHONE);
    }

    @Test
    @DisplayName("provider reschedules a REGISTERED client's booking — zero SMS, the client e-mail goes out unchanged")
    void should_sendNoSmsAndClientEmail_when_registeredClientBookingRescheduled() {
        SeededUser client = insertUser("CLIENT", null);
        UUID bookingId = insertConfirmedAppBooking(client.id(), salon.masterId(), salon.masterServiceId());

        ResponseEntity<String> resp = rescheduleBooking(salon.ownerEmail(), bookingId, tomorrowAt(LocalTime.of(14, 0)));
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        verify(emailNotificationService, times(1)).sendBookingRescheduledClientEmail(eq(client.email()), any());
        assertThat(rescheduleRow().getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("an anonymised guest (guest_phone NULL) — zero SMS, and the row still drains to SENT")
    void should_sendNoSms_when_guestIsAnonymised() {
        UUID bookingId = insertConfirmedLinkBooking(salon.masterId(), salon.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("UPDATE bookings SET guest_name = 'Видалений гість', guest_surname = NULL, guest_phone = NULL, "
                + "cancel_token_hash = NULL, guest_pii_anonymised_at = NOW() WHERE id = ?", bookingId);

        ResponseEntity<String> resp = rescheduleBooking(salon.ownerEmail(), bookingId, tomorrowAt(LocalTime.of(14, 0)));
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(rescheduleRow().getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    @DisplayName("one service of a guest visit is moved — exactly ONE SMS, naming that service's new time")
    void should_sendOneSms_when_oneGuestVisitServiceMoved() throws Exception {
        UUID second = insertService(salon.masterId(), "SALON", salon.salonId());
        UUID appointmentId = createGuestVisitAndSettle(tomorrowAt(LocalTime.of(10, 0)),
                List.of(salon.masterServiceId(), second));
        UUID secondItem = visitItems(appointmentId).get(1);
        OffsetDateTime newStart = tomorrowAt(LocalTime.of(15, 0));

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/appointments/" + appointmentId + "/services/" + secondItem + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>("{\"newStartsAt\":\"" + newStart + "\"}", bearerHeaders(tokenFor(salon.ownerEmail()))),
                String.class);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(sentText()).isEqualTo(expectedSms(newStart));
    }

    @Test
    @DisplayName("a whole guest visit is moved — exactly ONE SMS for the visit, naming its new start")
    void should_sendOneSmsPerVisit_when_wholeGuestVisitMoved() throws Exception {
        UUID second = insertService(salon.masterId(), "SALON", salon.salonId());
        UUID appointmentId = createGuestVisitAndSettle(tomorrowAt(LocalTime.of(10, 0)),
                List.of(salon.masterServiceId(), second));
        OffsetDateTime newStart = tomorrowAt(LocalTime.of(13, 0));

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/appointments/" + appointmentId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>("{\"newStartsAt\":\"" + newStart + "\"}", bearerHeaders(tokenFor(salon.ownerEmail()))),
                String.class);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(sentText()).isEqualTo(expectedSms(newStart));
    }

    // ── Audit-fix cycle 1: burst collapse, staleness, request cap, per-phone budget ───

    @Test
    @DisplayName("four provider moves in a burst before one drain — exactly ONE SMS, naming the LAST new time")
    void should_sendOneSms_when_bookingRescheduledFourTimesInBurst() throws Exception {
        UUID bookingId = insertConfirmedLinkBooking(salon.masterId(), salon.masterServiceId(), tomorrowAtNoon());
        List<LocalTime> moves = List.of(LocalTime.of(13, 0), LocalTime.of(14, 0), LocalTime.of(15, 0), LocalTime.of(16, 0));

        for (LocalTime move : moves) {
            ResponseEntity<String> resp = rescheduleBooking(salon.ownerEmail(), bookingId, tomorrowAt(move));
            assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        }
        drainWorker.drain();

        assertThat(sentText()).isEqualTo(expectedSms(tomorrowAt(LocalTime.of(16, 0))));
        assertThat(outboxRepository.findAll()).allMatch(row -> row.getStatus() == OutboxStatus.SENT);
    }

    @Test
    @DisplayName("a guest booking moved, then declined before the drain — no reschedule SMS (only the decline SMS)")
    void should_skipSms_when_bookingCancelledBeforeDrain() {
        UUID bookingId = insertConfirmedLinkBooking(salon.masterId(), salon.masterServiceId(), tomorrowAtNoon());

        assertThat(rescheduleBooking(salon.ownerEmail(), bookingId, tomorrowAt(LocalTime.of(14, 0))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(declineBooking(salon.ownerEmail(), bookingId).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        drainWorker.drain();

        assertThat(sentTexts()).hasSize(1).allMatch(text -> text.contains("скасовано"))
                .noneMatch(text -> text.contains("перенесено"));
    }

    @Test
    @DisplayName("user decision 2026-10-30: the 6th provider reschedule of a guest booking within 24 h is rejected "
            + "with 409 RESCHEDULE_LIMIT_REACHED and the booking is unchanged")
    void should_reject6thReschedule_when_guestBookingRescheduled5TimesIn24h() throws Exception {
        UUID bookingId = insertConfirmedLinkBooking(salon.masterId(), salon.masterServiceId(), tomorrowAtNoon());
        List<LocalTime> moves = List.of(LocalTime.of(9, 0), LocalTime.of(10, 0), LocalTime.of(11, 0),
                LocalTime.of(13, 0), LocalTime.of(14, 0));
        for (LocalTime move : moves) {
            assertThat(rescheduleBooking(salon.ownerEmail(), bookingId, tomorrowAt(move)).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
        }
        int rowsBefore = outboxRepository.findAll().size();

        ResponseEntity<String> sixth = rescheduleBooking(salon.ownerEmail(), bookingId, tomorrowAt(LocalTime.of(15, 0)));

        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var body = objectMapper.readTree(sixth.getBody());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("data").path("code").asText()).isEqualTo("RESCHEDULE_LIMIT_REACHED");
        assertThat(((java.sql.Timestamp) onlyBooking().get("starts_at")).toInstant())
                .as("the rejected move changes nothing").isEqualTo(tomorrowAt(LocalTime.of(14, 0)).toInstant());
        assertThat(outboxRepository.findAll()).as("nothing enqueued for the rejected move").hasSize(rowsBefore);
    }

    @Test
    @DisplayName("a one-service move counts against its VISIT: 5 visit moves (whole + per-service) then the 6th is rejected")
    void should_countServiceMovesAgainstVisit_when_guestVisitRescheduled() {
        UUID second = insertService(salon.masterId(), "SALON", salon.salonId());
        UUID appointmentId = createGuestVisitAndSettle(tomorrowAt(LocalTime.of(9, 0)),
                List.of(salon.masterServiceId(), second));
        String token = tokenFor(salon.ownerEmail());
        // Non-overlapping hops: a whole-visit move onto its own current span trips the DB exclusion
        // constraint mid-update (an existing, unrelated 409).
        List<LocalTime> wholeMoves = List.of(LocalTime.of(12, 0), LocalTime.of(15, 0), LocalTime.of(9, 0));
        for (LocalTime move : wholeMoves) {
            ResponseEntity<String> resp = moveVisit(token, appointmentId, tomorrowAt(move));
            assertThat(resp.getStatusCode()).as("whole move to %s body=%s", move, resp.getBody()).isEqualTo(HttpStatus.OK);
        }
        UUID secondItem = visitItems(appointmentId).get(1);
        for (LocalTime move : List.of(LocalTime.of(13, 0), LocalTime.of(14, 0))) {
            ResponseEntity<String> resp = moveVisitItem(token, appointmentId, secondItem, tomorrowAt(move));
            assertThat(resp.getStatusCode()).as("item move to %s body=%s", move, resp.getBody()).isEqualTo(HttpStatus.OK);
        }

        List<Instant> startsBefore = visitItems(appointmentId).stream().map(this::itemStart).toList();
        int rowsBefore = outboxRepository.findAll().size();

        ResponseEntity<String> sixth = moveVisit(token, appointmentId, tomorrowAt(LocalTime.of(15, 0)));

        assertThat(sixth.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(sixth.getBody()).contains("RESCHEDULE_LIMIT_REACHED");
        assertThat(visitItems(appointmentId).stream().map(this::itemStart).toList())
                .as("the rejected whole-visit move changes no item").isEqualTo(startsBefore);
        assertThat(outboxRepository.findAll()).as("nothing enqueued for the rejected move").hasSize(rowsBefore);
    }

    @Test
    @DisplayName("the cap holds on the ONE-SERVICE route too: after 5 whole-visit moves, moving one item of the same "
            + "visit is the 6th reschedule — 409 RESCHEDULE_LIMIT_REACHED, the item and the outbox unchanged")
    void should_reject6thRescheduleOnItemRoute_when_visitAlreadyMovedFiveTimes() {
        UUID second = insertService(salon.masterId(), "SALON", salon.salonId());
        UUID appointmentId = createGuestVisitAndSettle(tomorrowAt(LocalTime.of(9, 0)),
                List.of(salon.masterServiceId(), second));
        String token = tokenFor(salon.ownerEmail());
        // Non-overlapping hops ending back at 09:00, so the 6th (item 10:00 -> 13:00) is a move that
        // succeeds on an uncapped visit (see should_countServiceMovesAgainstVisit_when_guestVisitRescheduled).
        for (LocalTime move : List.of(LocalTime.of(12, 0), LocalTime.of(15, 0), LocalTime.of(9, 0),
                LocalTime.of(13, 0), LocalTime.of(9, 0))) {
            ResponseEntity<String> resp = moveVisit(token, appointmentId, tomorrowAt(move));
            assertThat(resp.getStatusCode()).as("whole move to %s body=%s", move, resp.getBody()).isEqualTo(HttpStatus.OK);
        }
        UUID secondItem = visitItems(appointmentId).get(1);
        Instant itemStartBefore = itemStart(secondItem);
        int rowsBefore = outboxRepository.findAll().size();

        ResponseEntity<String> sixth = moveVisitItem(token, appointmentId, secondItem, tomorrowAt(LocalTime.of(13, 0)));

        assertThat(sixth.getStatusCode()).as("body=%s", sixth.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(readJson(sixth.getBody()).path("data").path("code").asText()).isEqualTo("RESCHEDULE_LIMIT_REACHED");
        assertThat(itemStart(secondItem)).as("the rejected item move changes nothing").isEqualTo(itemStartBefore);
        assertThat(outboxRepository.findAll()).as("nothing enqueued for the rejected move").hasSize(rowsBefore);
    }

    @Test
    @DisplayName("a REGISTERED client's booking has no reschedule cap — six provider moves all succeed")
    void should_notCapReschedule_when_registeredClientBooking() {
        SeededUser client = insertUser("CLIENT", null);
        UUID bookingId = insertConfirmedAppBooking(client.id(), salon.masterId(), salon.masterServiceId());
        List<LocalTime> moves = List.of(LocalTime.of(9, 0), LocalTime.of(10, 0), LocalTime.of(11, 0),
                LocalTime.of(13, 0), LocalTime.of(14, 0), LocalTime.of(15, 0));

        for (LocalTime move : moves) {
            ResponseEntity<String> resp = rescheduleBooking(salon.ownerEmail(), bookingId, tomorrowAt(move));
            assertThat(resp.getStatusCode()).as("move to %s body=%s", move, resp.getBody()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    @DisplayName("walk-in confirmations + reschedule SMS share 5/hour per phone: over budget the SMS is skipped "
            + "SILENTLY (the move still succeeds), and reschedule SMS never block the next walk-in create")
    void should_suppressSms_when_phoneBudgetExhausted() throws Exception {
        assertThat(createWalkIn(tomorrowAt(LocalTime.of(9, 0))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createWalkIn(tomorrowAt(LocalTime.of(11, 0))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        drainWorker.drain();
        List<UUID> walkIns = jdbcTemplate.queryForList("SELECT id FROM bookings ORDER BY starts_at", UUID.class);
        UUID first = walkIns.get(0);
        UUID second = walkIns.get(1);
        resetTurbosms();

        moveAndDrain(first, LocalTime.of(13, 0));
        moveAndDrain(first, LocalTime.of(14, 0));
        moveAndDrain(second, LocalTime.of(15, 0));
        assertThat(sentTexts()).as("2 walk-ins + 3 reschedules = the 5/hour budget").hasSize(3);
        resetTurbosms();
        ResponseEntity<String> overBudget = rescheduleBooking(salon.ownerEmail(), second, tomorrowAt(LocalTime.of(16, 0)));
        drainWorker.drain();

        assertThat(overBudget.getStatusCode()).as("the budget is silent — never a rejection").isEqualTo(HttpStatus.OK);
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(outboxRepository.findAll()).allMatch(row -> row.getStatus() == OutboxStatus.SENT);
        assertNoPiiInLogs(E164_PHONE, PHONE_SUBSCRIBER_DIGITS);
        assertThat(createWalkIn(tomorrowAt(LocalTime.of(10, 0))).getStatusCode())
                .as("user rule 2026-10-30: the walk-in 429 counts walk-in visits only (2 < 5) — the 3 reschedule "
                        + "SMS must not block it").isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("regression (user rule 2026-10-30): reschedule SMS from ANOTHER salon to the same phone never push a "
            + "walk-in create to 429")
    void should_notBlockWalkIn_when_otherSalonsRescheduleSmsSpentThePhoneBudget() {
        Independent other = seedIndependentMaster();
        jdbcTemplate.update("INSERT INTO bookings (id, master_id, master_service_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                        + "guest_name, guest_surname, guest_phone, cancel_token, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 60, 0, 'LINK', ?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), other.masterId(), other.masterServiceId(), tomorrowAt(LocalTime.of(9, 0)),
                tomorrowAt(LocalTime.of(10, 0)), GUEST_NAME, GUEST_SURNAME, E164_PHONE, UUID.randomUUID());
        UUID otherBooking = jdbcTemplate.queryForObject("SELECT id FROM bookings WHERE master_id = ?", UUID.class,
                other.masterId());
        for (int i = 0; i < 5; i++) {
            jdbcTemplate.update("INSERT INTO guest_reschedule_sms_sends (booking_id, sent_at) VALUES (?, NOW())",
                    otherBooking);
        }

        ResponseEntity<String> resp = createWalkIn(tomorrowAt(LocalTime.of(12, 0)));

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("perf INFO parity: the walk-in half of countPhoneSmsBudgetUsedSince equals "
            + "countStaffWalkInVisitsForPhoneSince on the same data (visit-distinct, STAFF-only, windowed), and "
            + "reschedule sends are counted only in their own half")
    void should_countSameWalkInVisits_when_budgetQueryAndWalkInQueryReadSamePhone() {
        UUID second = insertService(salon.masterId(), "SALON", salon.salonId());
        assertThat(createWalkIn(tomorrowAt(LocalTime.of(9, 0)), List.of(salon.masterServiceId(), second))
                .getStatusCode()).as("2-item walk-in visit = ONE visit").isEqualTo(HttpStatus.CREATED);
        assertThat(createWalkIn(tomorrowAt(LocalTime.of(13, 0))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(createWalkIn(tomorrowAt(LocalTime.of(15, 0))).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        jdbcTemplate.update("UPDATE bookings SET created_at = NOW() - INTERVAL '2 hours' WHERE starts_at = ?",
                tomorrowAt(LocalTime.of(15, 0)));
        UUID linkSamePhone = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, starts_at, "
                        + "ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, cancel_token, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 60, 0, 'LINK', ?, ?, ?, ?, NOW(), NOW())",
                linkSamePhone, salon.masterId(), salon.masterServiceId(), salon.salonId(),
                tomorrowAt(LocalTime.of(11, 0)), tomorrowAt(LocalTime.of(12, 0)), GUEST_NAME, GUEST_SURNAME,
                E164_PHONE, UUID.randomUUID());
        for (int i = 0; i < 2; i++) {
            jdbcTemplate.update("INSERT INTO guest_reschedule_sms_sends (booking_id, sent_at) VALUES (?, NOW())",
                    linkSamePhone);
        }
        Instant since = Instant.now().minus(java.time.Duration.ofHours(1));

        long walkInOnly = bookingRepository.countStaffWalkInVisitsForPhoneSince(E164_PHONE, since);
        PhoneSmsBudgetUsage usage = bookingRepository.countPhoneSmsBudgetUsedSince(E164_PHONE, since);

        assertThat(walkInOnly).as("2 in-window walk-in visits; the 2 h old walk-in and the LINK booking excluded")
                .isEqualTo(2L);
        assertThat(usage.getWalkInVisits()).as("the two walk-in predicates must not drift").isEqualTo(walkInOnly);
        assertThat(usage.getRescheduleSends()).isEqualTo(2L);
        assertThat(usage.total()).isEqualTo(4L);
    }

    @Test
    @DisplayName("daily purge removes rows older than 2 days from BOTH phase-431 logs and keeps the younger ones")
    void should_purgeRowsOlderThanTwoDays_when_purgeExpiredRuns() {
        UUID bookingId = insertConfirmedLinkBooking(salon.masterId(), salon.masterServiceId(), tomorrowAtNoon());
        for (String age : List.of("3 days", "1 day")) {
            jdbcTemplate.update("INSERT INTO guest_reschedule_sms_sends (booking_id, sent_at) "
                    + "VALUES (?, NOW() - CAST(? AS INTERVAL))", bookingId, age);
            jdbcTemplate.update("INSERT INTO guest_provider_reschedules (scope_id, booking_id, rescheduled_at) "
                    + "VALUES (?, ?, NOW() - CAST(? AS INTERVAL))", bookingId, bookingId, age);
        }

        smsBudget.purgeExpired();

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM guest_reschedule_sms_sends "
                + "WHERE sent_at < NOW() - INTERVAL '2 days'", Long.class)).as("old SMS rows purged").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM guest_provider_reschedules "
                + "WHERE rescheduled_at < NOW() - INTERVAL '2 days'", Long.class)).as("old reschedule rows purged").isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM guest_reschedule_sms_sends", Long.class))
                .as("the 1-day-old SMS row survives").isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM guest_provider_reschedules", Long.class))
                .as("the 1-day-old reschedule row survives").isEqualTo(1L);
    }

    // ── Part B: performing-master e-mail on a provider reschedule ──────────────────

    @Test
    @DisplayName("the OWNER moves the salon master's booking — the master gets the e-mail and a feed item; the owner "
            + "gets nothing; the guest still gets one SMS")
    void should_notifyPerformingMasterOnly_when_ownerReschedulesSalonMastersBooking() throws Exception {
        SalonMaster sm = seedSalonMaster();
        UUID bookingId = insertConfirmedLinkBooking(sm.masterId(), sm.masterServiceId(), tomorrowAtNoon());

        ResponseEntity<String> resp = rescheduleBooking(salon.ownerEmail(), bookingId, tomorrowAt(LocalTime.of(14, 0)));
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        verify(emailNotificationService, times(1)).sendBookingRescheduledBySalonEmail(eq(sm.email()), any());
        verify(emailNotificationService, never()).sendBookingRescheduledBySalonEmail(eq(salon.ownerEmail()), any());
        verify(emailNotificationService, never()).sendBookingRescheduledEmail(anyString(), any());
        assertThat(feedRecipients("BOOKING_RESCHEDULED")).containsExactly(sm.userId());
        assertThat(sentText()).isEqualTo(expectedSms(tomorrowAt(LocalTime.of(14, 0))));
        assertThat(rescheduleRow().getPayload()).doesNotContain(LINK_PHONE).doesNotContain(LINK_SUBSCRIBER_DIGITS);
        List<NotificationOutboxEntry> masterRows = rowsOf(OutboxEventType.MASTER_BOOKING_RESCHEDULED);
        assertThat(masterRows).as("D6: the master e-mail is its own outbox row").singleElement().satisfies(row -> {
            assertThat(row.getAggregateId()).isEqualTo(bookingId);
            assertThat(row.getStatus()).isEqualTo(OutboxStatus.SENT);
            assertThat(row.getPayload()).contains(salon.ownerId().toString())
                    .doesNotContain(LINK_PHONE).doesNotContain(LINK_SUBSCRIBER_DIGITS);
        });
    }

    @Test
    @DisplayName("an ADMIN moves the owner-master's own booking — the owner (performing master) is e-mailed")
    void should_notifyOwnerMaster_when_adminReschedulesOwnersBooking() {
        SeededUser admin = insertUser("SALON_ADMIN", salon.salonId());
        UUID bookingId = insertConfirmedLinkBooking(salon.masterId(), salon.masterServiceId(), tomorrowAtNoon());

        ResponseEntity<String> resp = rescheduleBooking(admin.email(), bookingId, tomorrowAt(LocalTime.of(14, 0)));
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        verify(emailNotificationService, times(1)).sendBookingRescheduledBySalonEmail(eq(salon.ownerEmail()), any());
        assertThat(feedRecipients("BOOKING_RESCHEDULED")).contains(salon.ownerId()).doesNotContain(admin.id());
    }

    @Test
    @DisplayName("the performing master moves their own booking (owner-master, and an independent master) — no "
            + "master e-mail")
    void should_notEmailMaster_when_actorIsThePerformingMaster() {
        UUID ownBooking = insertConfirmedLinkBooking(salon.masterId(), salon.masterServiceId(), tomorrowAtNoon());
        Independent solo = seedIndependentMaster();
        UUID soloBooking = insertConfirmedLinkBooking(solo.masterId(), solo.masterServiceId(), null,
                tomorrowAt(LocalTime.of(10, 0)));

        ResponseEntity<String> ownResp = rescheduleBooking(salon.ownerEmail(), ownBooking, tomorrowAt(LocalTime.of(14, 0)));
        ResponseEntity<String> soloResp = rescheduleBooking(solo.email(), soloBooking, tomorrowAt(LocalTime.of(15, 0)));
        drainWorker.drain();

        assertThat(ownResp.getStatusCode()).as("body=%s", ownResp.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(soloResp.getStatusCode()).as("body=%s", soloResp.getBody()).isEqualTo(HttpStatus.OK);
        verify(emailNotificationService, never()).sendBookingRescheduledBySalonEmail(anyString(), any());
        assertThat(outboxRepository.findAll()).allMatch(row -> row.getStatus() == OutboxStatus.SENT);
        assertThat(rowsOf(OutboxEventType.MASTER_BOOKING_RESCHEDULED))
                .as("D6: no master row is even enqueued when the performing master is the actor").isEmpty();
    }

    @Test
    @DisplayName("an old PROVIDER reschedule row WITHOUT the actor field still drains to SENT and never e-mails the master")
    void should_drainLegacyRowWithoutMasterEmail_when_payloadHasNoActor() {
        SalonMaster sm = seedSalonMaster();
        UUID bookingId = insertConfirmedLinkBooking(sm.masterId(), sm.masterServiceId(), tomorrowAtNoon());
        NotificationOutboxEntry legacy = outboxRepository.save(NotificationOutboxEntry.builder()
                .eventType(OutboxEventType.BOOKING_RESCHEDULED)
                .aggregateId(bookingId)
                .payload("{\"initiatedBy\":\"PROVIDER\"}")
                .build());

        drainWorker.drain();

        NotificationOutboxEntry drained = outboxRepository.findById(legacy.getId()).orElseThrow();
        assertThat(drained.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(drained.getLastError()).isNull();
        verify(emailNotificationService, never()).sendBookingRescheduledBySalonEmail(anyString(), any());
    }

    // ── Part B: performing-master e-mail on a provider decline ─────────────────────

    @Test
    @DisplayName("an ADMIN declines the salon master's booking — the master gets ONE 'salon cancelled' e-mail and a "
            + "feed item; the admin gets nothing")
    void should_notifyPerformingMaster_when_adminDeclinesSalonMastersBooking() {
        SeededUser admin = insertUser("SALON_ADMIN", salon.salonId());
        SalonMaster sm = seedSalonMaster();
        UUID bookingId = insertConfirmedLinkBooking(sm.masterId(), sm.masterServiceId(), tomorrowAtNoon());

        ResponseEntity<Void> resp = declineBooking(admin.email(), bookingId);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(emailNotificationService, times(1)).sendBookingDeclinedBySalonEmail(eq(sm.email()), any());
        verify(emailNotificationService, never()).sendBookingDeclinedBySalonEmail(eq(admin.email()), any());
        assertThat(feedRecipients("BOOKING_DECLINED")).containsExactly(sm.userId());
    }

    @Test
    @DisplayName("the performing master declines their own booking — no master e-mail")
    void should_notEmailMaster_when_performingMasterDeclinesOwnBooking() {
        UUID bookingId = insertConfirmedLinkBooking(salon.masterId(), salon.masterServiceId(), tomorrowAtNoon());

        ResponseEntity<Void> resp = declineBooking(salon.ownerEmail(), bookingId);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(emailNotificationService, never()).sendBookingDeclinedBySalonEmail(anyString(), any());
    }

    @Test
    @DisplayName("an ADMIN declines a whole 2-service guest visit — the performing master gets exactly ONE e-mail, "
            + "naming both services")
    void should_emailMasterOncePerVisit_when_adminDeclinesWholeVisit() {
        SeededUser admin = insertUser("SALON_ADMIN", salon.salonId());
        UUID second = insertService(salon.masterId(), "SALON", salon.salonId());
        UUID appointmentId = createGuestVisitAndSettle(tomorrowAt(LocalTime.of(10, 0)),
                List.of(salon.masterServiceId(), second));

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/appointments/" + appointmentId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>("{}", bearerHeaders(tokenFor(admin.email()))), String.class);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(emailNotificationService, times(1)).sendBookingDeclinedBySalonEmail(eq(salon.ownerEmail()),
                argThat((BookingVisit visit) -> visit.items().size() == 2));
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private record SalonMaster(UUID userId, String email, UUID masterId, UUID masterServiceId) {
    }

    /** An invited SALON_MASTER of the fixture salon, with one service and the 09:00–17:00 schedule. */
    private SalonMaster seedSalonMaster() {
        SeededUser user = insertUser("SALON_MASTER", salon.salonId());
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())", masterId, user.id(), salon.salonId());
        UUID masterServiceId = insertService(masterId, "SALON", salon.salonId());
        // The invited-SALON_MASTER schedule branch reads the actor's role from the SecurityContext
        // (see AbstractStaffBookingIT#seedSalon), so the owner's authentication is installed for this
        // one fixture call and cleared straight after.
        var auth = new UsernamePasswordAuthenticationToken(salon.ownerEmail(), null,
                List.of(new SimpleGrantedAuthority("ROLE_SALON_OWNER")));
        auth.setDetails(salon.ownerId());
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            giveWorkingHours(salon.ownerId(), masterId);
        } finally {
            SecurityContextHolder.clearContext();
        }
        return new SalonMaster(user.id(), user.email(), masterId, masterServiceId);
    }

    private UUID insertConfirmedAppBooking(UUID clientId, UUID masterId, UUID masterServiceId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salon.salonId(),
                tomorrowAtNoon(), tomorrowAtNoon().plusHours(1));
        return bookingId;
    }

    private ResponseEntity<String> rescheduleBooking(String actorEmail, UUID bookingId, OffsetDateTime newStart) {
        return restTemplate.exchange("/api/v1/bookings/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>("{\"newStartsAt\":\"" + newStart + "\"}", bearerHeaders(tokenFor(actorEmail))),
                String.class);
    }

    private ResponseEntity<Void> declineBooking(String actorEmail, UUID bookingId) {
        return restTemplate.exchange("/api/v1/bookings/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>("{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\"}", bearerHeaders(tokenFor(actorEmail))),
                Void.class);
    }

    private void moveAndDrain(UUID bookingId, LocalTime newTime) {
        ResponseEntity<String> resp = rescheduleBooking(salon.ownerEmail(), bookingId, tomorrowAt(newTime));
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        drainWorker.drain();
    }

    private ResponseEntity<String> moveVisit(String token, UUID appointmentId, OffsetDateTime newStart) {
        return restTemplate.exchange("/api/v1/appointments/" + appointmentId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>("{\"newStartsAt\":\"" + newStart + "\"}", bearerHeaders(token)), String.class);
    }

    private ResponseEntity<String> moveVisitItem(String token, UUID appointmentId, UUID bookingId, OffsetDateTime newStart) {
        return restTemplate.exchange(
                "/api/v1/appointments/" + appointmentId + "/services/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>("{\"newStartsAt\":\"" + newStart + "\"}", bearerHeaders(token)), String.class);
    }

    /** Every Turbosms message text recorded since the last reset, in order. */
    private List<String> sentTexts() {
        return TURBOSMS.findAll(postRequestedFor(urlEqualTo(TURBOSMS_PATH))).stream()
                .map(request -> readJson(request.getBodyAsString()).path("sms").path("text").asText())
                .toList();
    }

    private List<UUID> visitItems(UUID appointmentId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at", UUID.class, appointmentId);
    }

    private List<UUID> feedRecipients(String type) {
        return jdbcTemplate.queryForList(
                "SELECT recipient_user_id FROM in_app_notification WHERE type = ?", UUID.class, type);
    }

    private NotificationOutboxEntry rescheduleRow() {
        List<NotificationOutboxEntry> rows = outboxRepository.findAll().stream()
                .filter(row -> row.getEventType() == OutboxEventType.BOOKING_RESCHEDULED)
                .toList();
        assertThat(rows).hasSize(1);
        return rows.get(0);
    }

    private List<NotificationOutboxEntry> rowsOf(OutboxEventType type) {
        return outboxRepository.findAll().stream().filter(row -> row.getEventType() == type).toList();
    }

    private Instant itemStart(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT starts_at FROM bookings WHERE id = ?", java.sql.Timestamp.class,
                bookingId).toInstant();
    }

    private static String expectedSms(OffsetDateTime newStart) {
        var kyiv = newStart.atZoneSameInstant(TimeZones.KYIV);
        return "Beautica: Ваш запис перенесено на " + DATE_FMT.format(kyiv) + " о " + TIME_FMT.format(kyiv) + ".";
    }
}
