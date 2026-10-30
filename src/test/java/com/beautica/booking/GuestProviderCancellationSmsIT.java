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
 * <p>The seeded fixture master is a {@code SALON_OWNER}-type master WITH a booking slug, so the
 * decline rows also prove the locked access rule: only an {@code INDEPENDENT_MASTER}'s own page is
 * ever offered — an owner's (or salon master's) decline links the SALON page.
 *
 * <p>The flag-off row lives in {@link WalkInBookingSmsIT} (the flag-off context of the same
 * hierarchy); this class shares its context with {@link WalkInBookingSmsEnabledIT}.
 */
@TestPropertySource(properties = "app.booking.sms.enabled=true")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("GuestProviderCancellationSmsIT — phase 390 guest SMS on provider cancellation")
class GuestProviderCancellationSmsIT extends AbstractWalkInBookingSmsIT {

    private static final String SALON_REBOOK = " Записатися знову: beautica.app/book/s/";

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("provider declines a guest single booking — exactly ONE SMS, exact fixed text with "
            + "the SALON's rebook link (owner-type master), no provider note, ≤ 2 UCS-2 segments, and "
            + "neither the phone nor the body ever reaches stdout/stderr")
    void should_sendOneExactSms_when_providerDeclinesGuestBooking(CapturedOutput output) throws Exception {
        OffsetDateTime startsAt = tomorrowAtNoon();
        UUID bookingId = createGuestBookingAndSettle(startsAt);
        String salonSlug = giveSalonSlug();

        ResponseEntity<Void> resp = declineBooking(bookingId, "Майстер захворів, див. evil.example");
        drainWorker.drain();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        String text = sentText();
        assertThat(text).isEqualTo(expectedBody(startsAt) + SALON_REBOOK + salonSlug);
        assertThat(text.length()).isLessThanOrEqualTo(134);
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
    @DisplayName("provider declines a whole multi-service guest visit — exactly ONE SMS for the visit")
    void should_sendOneSmsPerVisit_when_providerDeclinesWholeGuestVisit() throws Exception {
        OffsetDateTime startsAt = tomorrowAt(java.time.LocalTime.of(10, 0));
        UUID second = insertService(salon.masterId(), "SALON", salon.salonId());
        UUID appointmentId = createGuestVisitAndSettle(startsAt, List.of(salon.masterServiceId(), second));
        String salonSlug = giveSalonSlug();

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/appointments/" + appointmentId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>("{}", bearerHeaders(tokenFor(salon.ownerEmail()))), String.class);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bookings WHERE appointment_id = ? AND status = 'DECLINED'",
                Integer.class, appointmentId)).isEqualTo(2);
        assertThat(sentText()).isEqualTo(expectedBody(startsAt) + SALON_REBOOK + salonSlug);
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
    @DisplayName("provider marks a guest booking NOT_COMPLETED (no-show) — zero SMS")
    void should_sendNoSms_when_guestBookingNotCompleted() {
        UUID bookingId = createGuestBookingAndSettle(tomorrowAtNoon());
        jdbcTemplate.update("UPDATE bookings SET starts_at = NOW() - interval '3 hours', "
                + "ends_at = NOW() - interval '2 hours' WHERE id = ?", bookingId);

        ResponseEntity<Void> resp = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId + "/not-complete", HttpMethod.PATCH,
                new HttpEntity<>("{\"cancellationReason\":\"CLIENT_NO_SHOW\"}",
                        bearerHeaders(tokenFor(salon.ownerEmail()))), Void.class);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(onlyBooking().get("status")).isEqualTo("NOT_COMPLETED");
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
    @DisplayName("salon closure cascades over a future guest booking — exactly ONE SMS, body only "
            + "(no rebook link: the salon is gone)")
    void should_sendOneBodyOnlySms_when_salonClosureCancelsGuestBooking() throws Exception {
        OffsetDateTime startsAt = tomorrowAtNoon();
        createGuestBookingAndSettle(startsAt);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salon.salonId(), HttpMethod.DELETE,
                new HttpEntity<>(bearerHeaders(tokenFor(salon.ownerEmail()))), String.class);
        drainWorker.drain();

        assertThat(resp.getStatusCode()).as("salon delete body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(sentText()).isEqualTo(expectedBody(startsAt));
    }

    @Test
    @DisplayName("master removal cascades over a future guest booking — exactly ONE SMS with the SALON's "
            + "rebook link only, never the removed master's own page")
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
        assertThat(text).isEqualTo(expectedBody(startsAt) + SALON_REBOOK + salonSlug);
        assertThat(text).doesNotContain(removedMasterSlug);
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText()).isEqualTo(LINK_PHONE);
    }

    @Test
    @DisplayName("an INDEPENDENT master declines a guest booking — exactly ONE SMS with the master's OWN "
            + "rebook link (the page the guest booked through)")
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
        assertThat(text).isEqualTo(expectedBody(startsAt) + " Записатися знову: beautica.app/book/" + masterSlug);
        assertThat(text).doesNotContain("/book/s/").doesNotContain(MASTER_NAME);
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText()).isEqualTo(LINK_PHONE);
    }

    @Test
    @DisplayName("a guest booking with an invited SALON_MASTER (who has a slug) is declined — exactly ONE SMS with "
            + "the SALON's rebook link, never the salon master's own page")
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
        assertThat(text).isEqualTo(expectedBody(startsAt) + SALON_REBOOK + salonSlug);
        assertThat(text).as("a salon master's own page is never offered").doesNotContain(salonMasterSlug);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static String expectedBody(OffsetDateTime startsAt) {
        return "Beautica: Ваш запис " + DATE_FMT.format(startsAt.atZoneSameInstant(TimeZones.KYIV))
                + " о " + startsAt.atZoneSameInstant(TimeZones.KYIV).toLocalTime() + " скасовано.";
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
