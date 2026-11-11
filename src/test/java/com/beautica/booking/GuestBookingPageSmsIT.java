package com.beautica.booking;

import com.beautica.common.TimeZones;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 435 QA — the guest's OWN booking page link ({@code /c/{token}} since the follow-up, rebuilt from the V230 sealed copy)
 * and the removed self-cancel SMS, asserted at the Turbosms wire (real gated {@code SmsService}, real outbox drain,
 * real {@code GuestCancelTokenCipher}). Every row creates its booking through the public LINK route, so the sealed
 * copy is the one production writes — no fixture guesses its format or row binding.
 *
 * <p>Same {@code @TestPropertySource} as {@link GuestProviderCancellationSmsIT}, so the cached context (and its
 * WireMock port) is shared.
 */
@TestPropertySource(properties = "app.booking.sms.enabled=true")
@DisplayName("GuestBookingPageSmsIT — phase 435 guest booking-page link in provider SMS; no self-cancel SMS")
class GuestBookingPageSmsIT extends AbstractWalkInBookingSmsIT {

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    // ── provider decline → «Деталі або записатись знову {own page}» ──────────────────────────

    @Test
    @DisplayName("provider declines a LINK single booking → ONE SMS whose «Деталі» link is the guest's own page "
            + "(decrypted from the sealed copy) and that page then answers the read-only DECLINED view")
    void should_sendDeclineSmsWithOwnPageLink_when_providerDeclinesLinkBooking() throws Exception {
        OffsetDateTime startsAt = tomorrowAtNoon();
        ResponseEntity<String> created = createGuestBooking(startsAt);
        String cancelUrl = cancelUrlOf(created);
        UUID bookingId = UUID.fromString(readJson(created.getBody()).path("bookingId").asText());
        settle();

        ResponseEntity<Void> decline = declineBooking(bookingId);
        drainWorker.drain();

        assertThat(decline.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(sentText()).isEqualTo(cancelledBody(startsAt) + "\n\nДеталі або записатись знову " + cancelUrl);
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText()).isEqualTo(LINK_PHONE);
        ResponseEntity<String> page = getPage(tokenOf(cancelUrl));
        assertThat(page.getStatusCode()).as(page.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(readJson(page.getBody()).path("status").asText()).isEqualTo("DECLINED");
        assertThat(logMessages()).as("the link (it carries the raw token) is never logged")
                .noneMatch(line -> line.contains(tokenOf(cancelUrl)));
    }

    @Test
    @DisplayName("provider declines ONE service of a LINK visit → ONE SMS linking the VISIT header's page (the "
            + "token the guest got at create); phase 475 removed the whole-visit decline")
    void should_sendDeclineSmsWithVisitPageLink_when_providerDeclinesOneServiceOfLinkVisit() throws Exception {
        OffsetDateTime startsAt = tomorrowAt(java.time.LocalTime.of(10, 0));
        UUID second = insertService(salon.masterId(), "SALON", salon.salonId());
        ResponseEntity<String> created = createGuestVisit(startsAt, List.of(salon.masterServiceId(), second));
        String cancelUrl = cancelUrlOf(created);
        UUID appointmentId = UUID.fromString(readJson(created.getBody()).path("appointmentId").asText());
        settle();

        String ownerToken = tokenFor(salon.ownerEmail());
        UUID firstItem = jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at LIMIT 1", UUID.class, appointmentId);

        ResponseEntity<String> decline = restTemplate.exchange(
                "/api/v1/appointments/" + appointmentId + "/services/" + firstItem + "/decline",
                HttpMethod.PATCH, new HttpEntity<>("{}", bearerHeaders(ownerToken)), String.class);
        drainWorker.drain();

        assertThat(decline.getStatusCode()).as(decline.getBody()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(sentText()).isEqualTo(cancelledBody(startsAt) + "\n\nДеталі або записатись знову " + cancelUrl);
    }

    @Test
    @DisplayName("row binding (AAD): a sealed value copied onto ANOTHER booking never opens there — the decline SMS "
            + "drops the «Деталі» paragraph instead of linking the other guest's page; only the class name is logged")
    void should_dropLink_when_sealedValueCopiedFromAnotherBooking() throws Exception {
        ResponseEntity<String> donor = createGuestBooking(tomorrowAt(java.time.LocalTime.of(10, 0)));
        String donorToken = tokenOf(cancelUrlOf(donor));
        UUID donorId = UUID.fromString(readJson(donor.getBody()).path("bookingId").asText());
        OffsetDateTime startsAt = tomorrowAt(java.time.LocalTime.of(14, 0));
        ResponseEntity<String> target = createGuestBooking(startsAt);
        UUID targetId = UUID.fromString(readJson(target.getBody()).path("bookingId").asText());
        settle();
        jdbcTemplate.update("UPDATE bookings SET cancel_token_sealed = "
                + "(SELECT cancel_token_sealed FROM bookings WHERE id = ?) WHERE id = ?", donorId, targetId);

        ResponseEntity<Void> decline = declineBooking(targetId);
        drainWorker.drain();

        assertThat(decline.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(sentText()).isEqualTo(cancelledBody(startsAt)).doesNotContain(donorToken);
        assertThat(logMessages())
                .anyMatch(line -> line.contains("Guest booking page link dropped") && line.contains(targetId.toString()))
                .noneMatch(line -> line.contains(donorToken));
    }

    // ── guest self-cancel → NO guest SMS (user decision 2026-11-04) ──────────────────────────

    @Test
    @DisplayName("guest cancels their own LINK single booking by link → CANCELLED, provider notified, ZERO SMS on "
            + "the wire (after commit AND after the outbox drain)")
    void should_sendNoSms_when_guestCancelsOwnLinkBooking() throws Exception {
        ResponseEntity<String> created = createGuestBooking(tomorrowAtNoon());
        String token = tokenOf(cancelUrlOf(created));
        UUID bookingId = UUID.fromString(readJson(created.getBody()).path("bookingId").asText());
        settle();

        ResponseEntity<String> cancel = postCancel(token);
        drainWorker.drain();

        assertThat(cancel.getStatusCode()).as(cancel.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId))
                .isEqualTo("CANCELLED");
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_outbox WHERE "
                + "event_type = 'CLIENT_CANCELLED' AND aggregate_id = ?", Integer.class, bookingId))
                .as("the provider is still told").isEqualTo(1);
    }

    @Test
    @DisplayName("guest cancels their own LINK visit by link → visit CANCELLED, ZERO SMS on the wire")
    void should_sendNoSms_when_guestCancelsOwnLinkVisit() throws Exception {
        UUID second = insertService(salon.masterId(), "SALON", salon.salonId());
        ResponseEntity<String> created = createGuestVisit(tomorrowAt(java.time.LocalTime.of(10, 0)),
                List.of(salon.masterServiceId(), second));
        String token = tokenOf(cancelUrlOf(created));
        UUID appointmentId = UUID.fromString(readJson(created.getBody()).path("appointmentId").asText());
        settle();

        ResponseEntity<String> cancel = postCancel(token);
        drainWorker.drain();

        assertThat(cancel.getStatusCode()).as(cancel.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM appointments WHERE id = ?", String.class,
                appointmentId)).isEqualTo("CANCELLED");
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
    }

    // ── 24h reminder, salon variant ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("24h reminder for a SALON LINK booking → exact salon variant: salon | master | service line, the "
            + "salon's «Адреса:» line and the guest's own page as «Скасувати запис:»")
    void should_sendSalonReminderWithAddressAndOwnPage_when_salonBookingDueTomorrow() throws Exception {
        jdbcTemplate.update("UPDATE salons SET name = 'Студія Лілія', street = 'Хрещатик', building_no = '10', "
                + "location_note = 'вхід з двору' WHERE id = ?", salon.salonId());
        ResponseEntity<String> created = createGuestBooking(tomorrowAtNoon());
        String cancelUrl = cancelUrlOf(created);
        UUID bookingId = UUID.fromString(readJson(created.getBody()).path("bookingId").asText());
        settle();

        runReminderSweep(bookingId);

        OffsetDateTime remindedStart = jdbcTemplate.queryForObject(
                "SELECT starts_at FROM bookings WHERE id = ?", OffsetDateTime.class, bookingId);
        String time = HH_MM.format(remindedStart.atZoneSameInstant(TimeZones.KYIV));
        assertThat(sentText()).isEqualTo("Beautica: Нагадуємо, що завтра у Вас запис!\n\n"
                + "Студія Лілія | " + MASTER_NAME + " | " + SERVICE_NAME + " о " + time + "\n"
                + "Адреса: Хрещатик 10 (вхід з двору)\n"
                + "Скасувати запис: " + cancelUrl);
        assertThat(sentTurbosmsPayload().path("recipients").get(0).asText()).isEqualTo(LINK_PHONE);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private static String cancelledBody(OffsetDateTime startsAt) {
        ZonedDateTime kyiv = startsAt.atZoneSameInstant(TimeZones.KYIV);
        return "Beautica: Ваш запис " + DATE_FMT.format(kyiv) + " о " + HH_MM.format(kyiv) + " скасовано.";
    }

    /** Drains the create's own outbox rows and clears the confirmation SMS. */
    private void settle() {
        drainWorker.drain();
        resetTurbosms();
    }

    private String cancelUrlOf(ResponseEntity<String> created) {
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        String url = readJson(created.getBody()).path("cancelUrl").asText();
        assertThat(url).matches("^https?://.+/c/[0-9A-Za-z]{16}$");
        return url;
    }

    private static String tokenOf(String cancelUrl) {
        return cancelUrl.substring(cancelUrl.lastIndexOf('/') + 1);
    }

    private ResponseEntity<String> createGuestVisit(OffsetDateTime startsAt, List<UUID> masterServiceIds)
            throws JsonProcessingException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("masterServiceIds", masterServiceIds);
        body.put("startsAt", startsAt.toString());
        body.put("name", GUEST_NAME);
        body.put("surname", GUEST_SURNAME);
        body.put("privacyAccepted", true);
        body.put("privacyPolicyVersion", "2026-10-20");
        return restTemplate.exchange("/api/v1/book/" + salon.bookingSlug() + "/booking", HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(body), guestBookingHeaders()), String.class);
    }

    private ResponseEntity<Void> declineBooking(UUID bookingId) throws JsonProcessingException {
        String body = objectMapper.writeValueAsString(Map.of("cancellationReason", "PROVIDER_UNAVAILABLE"));
        return restTemplate.exchange("/api/v1/bookings/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(tokenFor(salon.ownerEmail()))), Void.class);
    }

    private ResponseEntity<String> postCancel(String token) {
        return restTemplate.exchange("/api/v1/book/cancel/" + token, HttpMethod.POST,
                new HttpEntity<>(ipHeaders()), String.class);
    }

    private ResponseEntity<String> getPage(String token) {
        return restTemplate.exchange("/api/v1/book/cancel/" + token, HttpMethod.GET,
                new HttpEntity<>(ipHeaders()), String.class);
    }

    private static HttpHeaders ipHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", PublicBookingPageFixtures.randomIp());
        return headers;
    }
}
