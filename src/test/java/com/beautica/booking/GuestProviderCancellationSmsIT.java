package com.beautica.booking;

import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.service.BookingService;
import com.beautica.common.TimeZones;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 390 — the guest (LINK, no account) provider-cancellation SMS, end to end with
 * {@code app.booking.sms.enabled=true}: the real gated {@code SmsService} (Turbosms against
 * WireMock), the real outbox drain and the real {@code BookingSmsDispatcher} (synchronous under the
 * test profile). Every assertion reads the provider wire, so "exactly one SMS with this exact text"
 * is a claim about what would actually be sent and billed.
 *
 * <p>Phase 435: rows created through the public route carry the V230 sealed token, so the SMS ends with
 * «Деталі або записатись знову {guest's own /book/cancel/ page}»; rows seeded directly
 * ({@link #insertConfirmedLinkBooking}, no sealed token — a pre-V230 row) get the body alone. No row
 * ever links a master or salon rebook page.
 *
 * <p>The flag-off row lives in {@link WalkInBookingSmsIT} (the flag-off context of the same
 * hierarchy); this class shares its context with {@link WalkInBookingSmsEnabledIT}.
 */
@TestPropertySource(properties = "app.booking.sms.enabled=true")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("GuestProviderCancellationSmsIT — phase 390 guest SMS on provider cancellation")
class GuestProviderCancellationSmsIT extends AbstractWalkInBookingSmsIT {


    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("provider declines a guest single booking — exactly ONE SMS, exact fixed text with "
            + "the guest's OWN booking page (sealed token, phase 435), never the salon/master rebook page, no "
            + "provider note, ≤ 3 UCS-2 segments, and neither the phone nor the body ever reaches stdout/stderr")
    void should_sendOneExactSms_when_providerDeclinesGuestBooking(CapturedOutput output) throws Exception {
        OffsetDateTime startsAt = tomorrowAtNoon();
        SettledGuest guest = createGuestBookingSettled(startsAt);
        giveSalonSlug();

        ResponseEntity<Void> resp = declineBooking(guest.id(), "Майстер захворів, див. evil.example");
        drainWorker.drain();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String text = sentText();
        assertThat(text).isEqualTo(expectedWithPage(startsAt, guest.cancelUrl()));
        assertThat(text.length()).isLessThanOrEqualTo(201);
        assertThat(text).doesNotContain("захворів").doesNotContain(SERVICE_NAME).doesNotContain(MASTER_NAME)
                .as("an owner-type master's own slug is never offered").doesNotContain(salon.bookingSlug());
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText()).isEqualTo(LINK_PHONE);
        assertNoPiiInLogs(LINK_PHONE, LINK_SUBSCRIBER_DIGITS);
        assertThat(logMessages()).noneMatch(line -> line.contains(text) || line.contains(expectedBody(startsAt)));
        assertThat(output.getAll())
                .as("the console capture must be non-empty, or the assertions below are vacuous")
                .isNotBlank()
                .doesNotContain(LINK_PHONE)
                .doesNotContain(LINK_SUBSCRIBER_DIGITS)
                .doesNotContain(expectedBody(startsAt))
                .doesNotContain("скасовано");
    }

    @Test
    @DisplayName("provider declines ONE service of a multi-service guest visit — exactly ONE SMS linking the "
            + "visit page (phase 475: the whole-visit decline route is gone; the per-service route is the one left)")
    void should_sendOneSms_when_providerDeclinesOneServiceOfGuestVisit() throws Exception {
        OffsetDateTime startsAt = tomorrowAt(java.time.LocalTime.of(10, 0));
        UUID second = insertService(salon.masterId(), "SALON", salon.salonId());
        SettledGuest visit = createGuestVisitSettled(startsAt, List.of(salon.masterServiceId(), second));
        UUID appointmentId = visit.id();
        giveSalonSlug();

        String ownerToken = tokenFor(salon.ownerEmail());
        UUID firstItem = jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at LIMIT 1", UUID.class, appointmentId);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/appointments/" + appointmentId + "/services/" + firstItem + "/decline", HttpMethod.PATCH,
                new HttpEntity<>("{}", bearerHeaders(ownerToken)), String.class);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bookings WHERE appointment_id = ? AND status = 'DECLINED'",
                Integer.class, appointmentId)).isEqualTo(1);
        assertThat(sentText()).isEqualTo(expectedWithPage(startsAt, visit.cancelUrl()));
    }

    @Test
    @DisplayName("GST-75 regression — provider declines BOTH services of a guest visit one by one: one SMS per "
            + "declined service, each naming that service's own start")
    void should_sendOneSmsPerService_when_providerDeclinesGuestVisitServicesOneByOne() {
        OffsetDateTime startsAt = tomorrowAt(java.time.LocalTime.of(10, 0));
        UUID second = insertService(salon.masterId(), "SALON", salon.salonId());
        SettledGuest visit = createGuestVisitSettled(startsAt, List.of(salon.masterServiceId(), second));
        String ownerToken = tokenFor(salon.ownerEmail());
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, starts_at FROM bookings WHERE appointment_id = ? ORDER BY starts_at", visit.id());

        for (Map<String, Object> item : items) {
            ResponseEntity<String> resp = restTemplate.exchange(
                    "/api/v1/appointments/" + visit.id() + "/services/" + item.get("id") + "/decline",
                    HttpMethod.PATCH, new HttpEntity<>("{}", bearerHeaders(ownerToken)), String.class);
            assertThat(resp.getStatusCode()).as("body: %s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
            drainWorker.drain();
        }

        assertThat(sentTexts()).containsExactly(
                expectedWithPage(startOf(items.get(0)), visit.cancelUrl()),
                expectedWithPage(startOf(items.get(1)), visit.cancelUrl()));
    }

    @Test
    @DisplayName("GST-75 — salon closure over a 2-service guest visit: one SALON_CLOSED row and one SMS per "
            + "service, each naming that service's own start")
    void should_sendOneSmsPerService_when_salonClosureCancelsGuestVisit() {
        OffsetDateTime startsAt = tomorrowAt(java.time.LocalTime.of(10, 0));
        UUID second = insertService(salon.masterId(), "SALON", salon.salonId());
        SettledGuest visit = createGuestVisitSettled(startsAt, List.of(salon.masterServiceId(), second));
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, starts_at FROM bookings WHERE appointment_id = ? ORDER BY starts_at", visit.id());

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salon.salonId(), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(tokenFor(salon.ownerEmail()))), String.class);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("salon delete body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForList("SELECT aggregate_id FROM notification_outbox "
                + "WHERE event_type = 'SALON_CLOSED'", UUID.class))
                .containsExactlyInAnyOrder((UUID) items.get(0).get("id"), (UUID) items.get(1).get("id"));
        assertThat(sentTexts()).containsExactlyInAnyOrder(
                expectedWithPage(startOf(items.get(0)), visit.cancelUrl()),
                expectedWithPage(startOf(items.get(1)), visit.cancelUrl()));
    }

    @Test
    @DisplayName("NTF-06 / D12 — salon closure over a REGISTERED client's 2-service visit: still ONE SALON_CLOSED "
            + "row per visit, zero SMS (NTF-D04)")
    void should_keepOneRowPerVisit_when_salonClosureCancelsRegisteredVisit() {
        SeededUser client = insertUser("CLIENT", null);
        insertRegisteredVisit(client.id(), salon.masterId(), salon.masterServiceId(),
                tomorrowAt(java.time.LocalTime.of(10, 0)), 2);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salon.salonId(), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(tokenFor(salon.ownerEmail()))), String.class);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("salon delete body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox "
                + "WHERE event_type = 'SALON_CLOSED'", Integer.class)).isEqualTo(1);
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("provider declines a REGISTERED client's booking — zero SMS (email/push channel)")
    void should_sendNoSms_when_registeredClientBookingDeclined() {
        SeededUser client = insertUser("CLIENT", null);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, client.id(), salon.masterId(), salon.masterServiceId(), salon.salonId(),
                tomorrowAtNoon(), tomorrowAtNoon().plusHours(1));

        ResponseEntity<Void> resp = declineBooking(bookingId, null);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("a decline whose transaction rolls back sends nothing — the outbox row never commits")
    void should_sendNoSms_when_declineTransactionRollsBack() {
        UUID bookingId = createGuestBookingAndSettle(tomorrowAtNoon());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            bookingService.declineBooking(salon.ownerId(), bookingId,
                    new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null));
            status.setRollbackOnly();
        });
        drainWorker.drain();

        assertThat(onlyBooking().get("status")).isEqualTo("CONFIRMED");
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("salon closure cascades over a future guest booking — exactly ONE SMS linking the guest's own "
            + "(now read-only) booking page, never a rebook page (the salon is gone)")
    void should_sendOneOwnPageSms_when_salonClosureCancelsGuestBooking() throws Exception {
        OffsetDateTime startsAt = tomorrowAtNoon();
        SettledGuest guest = createGuestBookingSettled(startsAt);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salon.salonId(), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(tokenFor(salon.ownerEmail()))), String.class);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("salon delete body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(sentText()).isEqualTo(expectedWithPage(startsAt, guest.cancelUrl()));
    }

    @Test
    @DisplayName("master removal cascades over a future legacy guest booking (no sealed token) — exactly ONE "
            + "body-only SMS, never the removed master's own page")
    void should_sendOneSalonLinkSms_when_masterRemovalCancelsGuestBooking() throws Exception {
        OffsetDateTime startsAt = tomorrowAtNoon();
        String salonSlug = giveSalonSlug();
        SeededUser masterUser = insertUser("SALON_MASTER", salon.salonId());
        UUID removedMasterId = UUID.randomUUID();
        String removedMasterSlug = "rm-" + removedMasterId;
        jdbcTemplate.update("INSERT INTO masters (id, user_id, salon_id, master_type, booking_slug, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', ?, true, NOW(), NOW())",
                removedMasterId, masterUser.id(), salon.salonId(), removedMasterSlug);
        UUID masterServiceId = insertService(removedMasterId, "SALON", salon.salonId());
        insertConfirmedLinkBooking(removedMasterId, masterServiceId, startsAt);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salon.salonId() + "/masters/" + removedMasterId, HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(tokenFor(salon.ownerEmail()))), String.class);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("master removal body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        String text = sentText();
        assertThat(text).isEqualTo(expectedBody(startsAt));
        assertThat(text).doesNotContain(removedMasterSlug);
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText()).isEqualTo(LINK_PHONE);
    }

    @Test
    @DisplayName("an INDEPENDENT master declines a legacy guest booking (no sealed token) — exactly ONE "
            + "body-only SMS, no rebook link")
    void should_sendOneMasterLinkSms_when_independentMasterDeclinesGuestBooking() throws Exception {
        OffsetDateTime startsAt = tomorrowAtNoon();
        Independent independent = seedIndependentMaster();
        String masterSlug = "i-" + independent.masterId();
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", masterSlug, independent.masterId());
        UUID bookingId = insertConfirmedLinkBooking(
                independent.masterId(), independent.masterServiceId(), null, startsAt);

        ResponseEntity<Void> resp = declineBookingAs(independent.email(), bookingId, null);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String text = sentText();
        assertThat(text).isEqualTo(expectedBody(startsAt));
        assertThat(text).doesNotContain("/book/s/").doesNotContain(MASTER_NAME);
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText()).isEqualTo(LINK_PHONE);
    }

    @Test
    @DisplayName("a legacy guest booking (no sealed token) with an invited SALON_MASTER (who has a slug) is declined "
            + "— exactly ONE body-only SMS, never the salon master's own page")
    void should_sendOneSalonLinkSms_when_salonMasterGuestBookingDeclined() throws Exception {
        OffsetDateTime startsAt = tomorrowAtNoon();
        String salonSlug = giveSalonSlug();
        SeededUser masterUser = insertUser("SALON_MASTER", salon.salonId());
        UUID salonMasterId = UUID.randomUUID();
        String salonMasterSlug = "sm-" + salonMasterId;
        jdbcTemplate.update("INSERT INTO masters (id, user_id, salon_id, master_type, booking_slug, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', ?, true, NOW(), NOW())",
                salonMasterId, masterUser.id(), salon.salonId(), salonMasterSlug);
        UUID masterServiceId = insertService(salonMasterId, "SALON", salon.salonId());
        UUID bookingId = insertConfirmedLinkBooking(salonMasterId, masterServiceId, startsAt);

        ResponseEntity<Void> resp = declineBooking(bookingId, null);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String text = sentText();
        assertThat(text).isEqualTo(expectedBody(startsAt));
        assertThat(text).as("a salon master's own page is never offered").doesNotContain(salonMasterSlug);
    }

    // ── phase 478 QA — per-service guest messages on every cascade, registered clients untouched ──────────

    @Test
    @DisplayName("GST-75 bound (security LOW) — a 10-service walk-in visit (MAX_SERVICES_PER_VISIT) declined "
            + "service by service: exactly 10 dispatches, one per service, each naming its own start; the visit "
            + "spends ONE per-phone budget reservation")
    void should_dispatchExactlyTen_when_tenServiceWalkInVisitDeclinedServiceByService() {
        Visit visit = createSettledTenServiceWalkIn();
        String ownerToken = tokenFor(salon.ownerEmail());
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, starts_at FROM bookings WHERE appointment_id = ? ORDER BY starts_at", visit.appointmentId());
        assertThat(items).as("fixture: a full-size visit").hasSize(MAX_SERVICES_PER_VISIT);

        for (Map<String, Object> item : items) {
            declineVisitItem(ownerToken, visit.appointmentId(), (UUID) item.get("id"));
            drainWorker.drain();
        }

        List<String> sent = sentTexts();
        assertThat(sent).as("one message per declined service, in decline order")
                .extracting(text -> text.split("\n\n", 2)[0])
                .containsExactlyElementsOf(items.stream().map(i -> expectedBody(startOf(i))).toList());
        assertThat(sent).as("every message links the same visit page").extracting(text -> text.split("\n\n", 2)[1])
                .containsOnly(sent.get(0).split("\n\n", 2)[1]);
        assertThat(walkInCancellationReservations(visit.appointmentId()))
                .as("the visit is charged once, not once per service").isEqualTo(1);
    }

    @Test
    @DisplayName("GST-75 hard per-phone ceiling (phase 478 audit-fix) — two full 10-service walk-in visits to ONE phone "
            + "send all 20 per-service cancellation messages; the 21st cancellation message to that phone in the window "
            + "(a third visit) is suppressed and logged, while the decline itself succeeds")
    void should_suppressFurtherCancellationMessage_when_phoneAlreadyGotPerVisitBoundInWindow(CapturedOutput output) {
        Visit firstVisit = createSettledTenServiceWalkIn(tomorrowAt(java.time.LocalTime.of(10, 0)));
        Visit secondVisit = createSettledTenServiceWalkIn(tomorrowAt(java.time.LocalTime.of(10, 0)).plusDays(1));
        Visit extra = postWalkIn(salon.masterId(), List.of(salon.masterServiceId()), tokenFor(salon.ownerEmail()),
                tomorrowAt(java.time.LocalTime.of(16, 0)));
        drainWorker.drain();
        resetTurbosms();
        // The walk-ins were created before the window, so the shared per-phone budget (5) is NOT what suppresses the
        // 21st message — only the hard ceiling can.
        jdbcTemplate.update("UPDATE bookings SET created_at = created_at - INTERVAL '2 hours' "
                + "WHERE appointment_id IN (?, ?, ?)", firstVisit.appointmentId(), secondVisit.appointmentId(),
                extra.appointmentId());
        String ownerToken = tokenFor(salon.ownerEmail());
        for (Visit visit : List.of(firstVisit, secondVisit)) {
            for (UUID item : jdbcTemplate.queryForList("SELECT id FROM bookings WHERE appointment_id = ? "
                    + "ORDER BY starts_at", UUID.class, visit.appointmentId())) {
                declineVisitItem(ownerToken, visit.appointmentId(), item);
                drainWorker.drain();
            }
        }
        assertThat(sentTexts()).as("precondition: two full visits are never truncated by the ceiling")
                .hasSize(2 * MAX_SERVICES_PER_VISIT);

        declineVisitItem(ownerToken, extra.appointmentId(), extra.bookingIds().get(0));
        drainWorker.drain();

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class,
                extra.bookingIds().get(0))).as("the decline itself always succeeds").isEqualTo("DECLINED");
        assertThat(sentTexts()).as("the 21st cancellation message to one phone in the window is suppressed")
                .hasSize(2 * MAX_SERVICES_PER_VISIT);
        assertThat(output.getAll())
                .contains("event=walk_in_cancellation_sms_suppressed reason=per_phone_ceiling bookingId="
                        + extra.bookingIds().get(0))
                .contains("event=walk_in_cancellation_sms_visit_charged bookingId=");
    }

    @Test
    @DisplayName("GST-75 — master removal over a 2-service guest (LINK) visit: one MASTER_REMOVED row and one SMS "
            + "per service, each naming that service's own start")
    void should_sendOneSmsPerService_when_masterRemovalCancelsGuestVisit() {
        UUID removedMasterId = insertRemovableSalonMaster();
        UUID masterServiceId = insertService(removedMasterId, "SALON", salon.salonId());
        OffsetDateTime startsAt = tomorrowAt(java.time.LocalTime.of(10, 0));
        UUID appointmentId = insertGuestLinkAppointment(LINK_PHONE, java.time.Instant.now(), java.time.Instant.now());
        UUID first = insertGuestVisitItem(appointmentId, removedMasterId, masterServiceId, startsAt);
        UUID second = insertGuestVisitItem(appointmentId, removedMasterId, masterServiceId, startsAt.plusHours(1));

        ResponseEntity<String> resp = removeMaster(removedMasterId);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("master removal body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(outboxAggregateIds("MASTER_REMOVED")).containsExactlyInAnyOrder(first, second);
        assertThat(sentTexts()).containsExactlyInAnyOrder(expectedBody(startsAt), expectedBody(startsAt.plusHours(1)));
    }

    @Test
    @DisplayName("GST-75 — a guest block (POST /blocks) over a 2-service LINK visit: one STATUS_CHANGED row and one "
            + "SMS per declined service, each naming its own start, nothing about a block")
    void should_sendOneSmsPerService_when_guestBlockCancelsGuestVisit() {
        OffsetDateTime startsAt = tomorrowAt(java.time.LocalTime.of(10, 0));
        UUID second = insertService(salon.masterId(), "SALON", salon.salonId());
        SettledGuest visit = createGuestVisitSettled(startsAt, List.of(salon.masterServiceId(), second));
        List<Map<String, Object>> items = jdbcTemplate.queryForList(
                "SELECT id, starts_at FROM bookings WHERE appointment_id = ? ORDER BY starts_at", visit.id());

        ResponseEntity<String> resp = postBlock((UUID) items.get(0).get("id"));
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("block body=%s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(outboxAggregateIds("STATUS_CHANGED"))
                .containsExactlyInAnyOrder((UUID) items.get(0).get("id"), (UUID) items.get(1).get("id"));
        assertThat(sentTexts()).containsExactlyInAnyOrder(
                expectedWithPage(startOf(items.get(0)), visit.cancelUrl()),
                expectedWithPage(startOf(items.get(1)), visit.cancelUrl()));
    }

    @Test
    @DisplayName("NTF-D04 / D2 — master removal over a REGISTERED client's 2-service visit: ONE MASTER_REMOVED row "
            + "per visit, zero SMS")
    void should_keepOneRowAndSendNoSms_when_masterRemovalCancelsRegisteredVisit() {
        UUID removedMasterId = insertRemovableSalonMaster();
        UUID masterServiceId = insertService(removedMasterId, "SALON", salon.salonId());
        SeededUser client = insertUser("CLIENT", null);
        insertRegisteredVisit(client.id(), removedMasterId, masterServiceId, tomorrowAt(java.time.LocalTime.of(10, 0)), 2);

        ResponseEntity<String> resp = removeMaster(removedMasterId);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("master removal body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(outboxAggregateIds("MASTER_REMOVED")).hasSize(1);
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    @Test
    @DisplayName("NTF-D04 / D2 — a client block over a REGISTERED client's 2-service visit: both services declined, "
            + "ONE STATUS_CHANGED row per visit, zero SMS")
    void should_keepOneRowAndSendNoSms_when_clientBlockCancelsRegisteredVisit() {
        SeededUser client = insertUser("CLIENT", null);
        UUID appointmentId = insertRegisteredVisit(client.id(), salon.masterId(), salon.masterServiceId(),
                tomorrowAt(java.time.LocalTime.of(10, 0)), 2);
        UUID anyItem = jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at LIMIT 1", UUID.class, appointmentId);

        ResponseEntity<String> resp = postBlock(anyItem);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("block body=%s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbcTemplate.queryForList("SELECT status FROM bookings WHERE appointment_id = ?", String.class,
                appointmentId)).containsExactly("DECLINED", "DECLINED");
        assertThat(outboxAggregateIds("STATUS_CHANGED")).hasSize(1);
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static final int MAX_SERVICES_PER_VISIT =
            com.beautica.booking.service.SlotCalculationService.MAX_SERVICES_PER_VISIT;

    /**
     * A real 10-service STAFF walk-in (RAW_PHONE) tomorrow 10:00–15:00: 30-minute services so the whole visit fits the
     * 09:00–17:00 fixture day. Its confirmation SMS is drained and cleared.
     */
    private Visit createSettledTenServiceWalkIn() {
        return createSettledTenServiceWalkIn(tomorrowAt(java.time.LocalTime.of(10, 0)));
    }

    /** {@link #createSettledTenServiceWalkIn()} starting at {@code startsAt} (fresh services each call). */
    private Visit createSettledTenServiceWalkIn(OffsetDateTime startsAt) {
        List<UUID> serviceIds = new java.util.ArrayList<>(List.of(salon.masterServiceId()));
        while (serviceIds.size() < MAX_SERVICES_PER_VISIT) {
            serviceIds.add(insertService(salon.masterId(), "SALON", salon.salonId()));
        }
        jdbcTemplate.update("UPDATE service_definitions SET base_duration_minutes = 30 WHERE owner_id = ?",
                salon.salonId());
        Visit visit = postWalkIn(salon.masterId(), serviceIds, tokenFor(salon.ownerEmail()), startsAt);
        drainWorker.drain();
        resetTurbosms();
        return visit;
    }

    private void declineVisitItem(String token, UUID appointmentId, UUID bookingId) {
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/appointments/" + appointmentId + "/services/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>("{}", bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode()).as("per-service decline body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
    }

    private int walkInCancellationReservations(UUID appointmentId) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM guest_reschedule_sms_sends s "
                + "JOIN bookings b ON b.id = s.booking_id WHERE b.appointment_id = ?", Integer.class, appointmentId);
    }

    private List<UUID> outboxAggregateIds(String eventType) {
        return jdbcTemplate.queryForList(
                "SELECT aggregate_id FROM notification_outbox WHERE event_type = ?", UUID.class, eventType);
    }

    /** An invited SALON_MASTER of the fixture salon whom the owner can remove; returns the master id. */
    private UUID insertRemovableSalonMaster() {
        SeededUser masterUser = insertUser("SALON_MASTER", salon.salonId());
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())", masterId, masterUser.id(),
                salon.salonId());
        return masterId;
    }

    private ResponseEntity<String> removeMaster(UUID masterId) {
        return restTemplate.exchange("/api/v1/salons/" + salon.salonId() + "/masters/" + masterId, HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(tokenFor(salon.ownerEmail()))), String.class);
    }

    /** {@code POST /api/v1/blocks {bookingId}} as the salon owner. */
    private ResponseEntity<String> postBlock(UUID bookingId) {
        String body = objectMapper.createObjectNode().put("bookingId", bookingId.toString()).toString();
        return restTemplate.exchange("/api/v1/blocks", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(tokenFor(salon.ownerEmail()))), String.class);
    }

    /** One CONFIRMED LINK guest item (no sealed token — body-only SMS) of {@code appointmentId}; returns its id. */
    private UUID insertGuestVisitItem(UUID appointmentId, UUID masterId, UUID masterServiceId, OffsetDateTime startsAt) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, master_id, master_service_id, salon_id, appointment_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, cancel_token_hash, created_at, "
                        + "updated_at) VALUES (?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 60, 0, 'LINK', ?, ?, ?, "
                        + "encode(sha256(convert_to(?, 'UTF8')), 'hex'), NOW(), NOW())",
                bookingId, masterId, masterServiceId, salon.salonId(), appointmentId, startsAt, startsAt.plusHours(1),
                GUEST_NAME, GUEST_SURNAME, LINK_PHONE, UUID.randomUUID().toString());
        return bookingId;
    }

    /** A registered client's CONFIRMED APP visit of {@code count} hourly items from {@code startsAt}; returns its id. */
    private UUID insertRegisteredVisit(UUID clientId, UUID masterId, UUID masterServiceId, OffsetDateTime startsAt,
                                       int count) {
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO appointments (id, client_id, salon_id, status, booking_source, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'CONFIRMED', 'APP', NOW(), NOW())", appointmentId, clientId,
                salon.salonId());
        for (int i = 0; i < count; i++) {
            OffsetDateTime itemStart = startsAt.plusHours(i);
            jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, "
                            + "appointment_id, status, starts_at, ends_at, price_at_booking, "
                            + "duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, created_at, "
                            + "updated_at) VALUES (?, ?, ?, ?, ?, ?, 'CONFIRMED', ?, ?, 350.00, 60, 0, 'APP', NOW(), NOW())",
                    UUID.randomUUID(), clientId, masterId, masterServiceId, salon.salonId(),
                    appointmentId, itemStart, itemStart.plusHours(1));
        }
        return appointmentId;
    }

    private static String expectedBody(OffsetDateTime startsAt) {
        return "Beautica: Ваш запис " + DATE_FMT.format(startsAt.atZoneSameInstant(TimeZones.KYIV))
                + " о " + startsAt.atZoneSameInstant(TimeZones.KYIV).toLocalTime() + " скасовано.";
    }

    /** Phase 435 — the body plus the guest's own booking page paragraph (sealed-token rows). */
    private static String expectedWithPage(OffsetDateTime startsAt, String cancelUrl) {
        return expectedBody(startsAt) + "\n\nДеталі або записатись знову " + cancelUrl;
    }

    private static OffsetDateTime startOf(Map<String, Object> row) {
        Object value = row.get("starts_at");
        return value instanceof OffsetDateTime odt ? odt
                : ((java.sql.Timestamp) value).toInstant().atOffset(java.time.ZoneOffset.UTC);
    }

    /** Gives the fixture salon a V200-shaped booking slug and returns it. */
    private String giveSalonSlug() {
        String slug = "s-" + salon.salonId();
        jdbcTemplate.update("UPDATE salons SET booking_slug = ? WHERE id = ?", slug, salon.salonId());
        return slug;
    }

    private ResponseEntity<Void> declineBooking(UUID bookingId, String comment) {
        return declineBookingAs(salon.ownerEmail(), bookingId, comment);
    }

    private ResponseEntity<Void> declineBookingAs(String actorEmail, UUID bookingId, String comment) {
        // ObjectMapper, not concatenation (Q16): a quote or backslash in the fixture note must not corrupt the body.
        java.util.Map<String, String> fields = new java.util.LinkedHashMap<>();
        fields.put("cancellationReason", "PROVIDER_UNAVAILABLE");
        if (comment != null) {
            fields.put("comment", comment);
        }
        String body;
        try {
            body = objectMapper.writeValueAsString(fields);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        return restTemplate.exchange("/api/v1/bookings/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(tokenFor(actorEmail))), Void.class);
    }}
