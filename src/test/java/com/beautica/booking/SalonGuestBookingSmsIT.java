package com.beautica.booking;

import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 393 — the SMS side of the salon-scoped guest booking, at the wire (real gated {@code SmsService} against
 * WireMock Turbosms, {@code app.booking.sms.enabled=true}): each per-master call sends its OWN guest confirmation
 * (same {@code GUEST_CONFIRMATION} template as the master route, carrying that call's cancel link), and a provider
 * decline of a salon-route booking sends the phase 390 provider-cancellation SMS with the SALON's rebook link.
 *
 * <p>Same {@code @TestPropertySource} as {@link GuestProviderCancellationSmsIT}, so the cached context (and its
 * WireMock port) is shared; every guest POST uses a fresh source IP (see {@link AbstractWalkInBookingSmsIT}).
 */
@TestPropertySource(properties = "app.booking.sms.enabled=true")
@DisplayName("SalonGuestBookingSmsIT — phase 393 per-master confirmation + phase 390 decline SMS on salon routes")
class SalonGuestBookingSmsIT extends AbstractWalkInBookingSmsIT {

    private static final String SALON_REBOOK = " Записатися знову: beautica.app/book/s/";

    @Autowired private GuestTokenProvider guestTokenProvider;
    @Autowired private NotificationOutboxDrainWorker drainWorker;

    private String salonSlug;
    /** An invited SALON_MASTER with NO booking slug — reachable only through the salon route. */
    private UUID salonMasterId;
    private UUID salonMasterService;

    @BeforeEach
    void seedSalonRoute() {
        salonSlug = "sms-" + salon.salonId().toString().substring(0, 8) + "-s1";
        jdbcTemplate.update("UPDATE salons SET booking_slug = ? WHERE id = ?", salonSlug, salon.salonId());
        SeededUser masterUser = insertUser("SALON_MASTER", salon.salonId());
        salonMasterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                salonMasterId, masterUser.id(), salon.salonId());
        salonMasterService = insertService(salonMasterId, "SALON", salon.salonId());
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to) "
                + "VALUES (?, ?, DATE '2020-01-01', NULL)", scheduleId, salonMasterId);
        for (int dow = 1; dow <= 7; dow++) {
            jdbcTemplate.update("INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                    + "VALUES (?, ?, ?, '09:00', '17:00')", UUID.randomUUID(), scheduleId, dow);
        }
    }

    @Test
    @DisplayName("two masters on the salon route with ONE guest token → exactly TWO confirmations to the guest, "
            + "each carrying its own call's cancel link")
    void should_sendOneConfirmationPerMaster_when_salonVisitSplitAcrossTwoMasters() throws Exception {
        String token = guestTokenProvider.generate(LINK_PHONE);

        ResponseEntity<String> owner = salonBook(salon.masterId(), salon.masterServiceId(), tomorrowAt(LocalTime.of(10, 0)), token);
        ResponseEntity<String> master = salonBook(salonMasterId, salonMasterService, tomorrowAt(LocalTime.of(11, 0)), token);

        assertThat(owner.getStatusCode()).as(owner.getBody()).isEqualTo(HttpStatus.CREATED);
        assertThat(master.getStatusCode()).as(master.getBody()).isEqualTo(HttpStatus.CREATED);
        List<LoggedRequest> sent = TURBOSMS.findAll(postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(sent).as("one confirmation per master call").hasSize(2);
        List<String> texts = sent.stream().map(r -> readJson(r.getBodyAsString()))
                .peek(p -> assertThat(p.path("recipients").get(0).asText()).isEqualTo(LINK_PHONE))
                .map(p -> p.path("sms").path("text").asText())
                .toList();
        assertThat(texts).allMatch(t -> t.contains("Скасувати:"));
        assertThat(texts).anyMatch(t -> t.contains(cancelPath(owner)));
        assertThat(texts).anyMatch(t -> t.contains(cancelPath(master)));
        assertNoPiiInLogs(LINK_PHONE, LINK_SUBSCRIBER_DIGITS);
    }

    @Test
    @DisplayName("owner declines the slug-less salon master's salon-route booking → exactly ONE provider-cancellation "
            + "SMS with the SALON's rebook link")
    void should_sendSalonRebookSms_when_salonRouteBookingDeclined() throws Exception {
        OffsetDateTime startsAt = tomorrowAt(LocalTime.of(12, 0));
        ResponseEntity<String> created = salonBook(salonMasterId, salonMasterService, startsAt,
                guestTokenProvider.generate(LINK_PHONE));
        assertThat(created.getStatusCode()).as(created.getBody()).isEqualTo(HttpStatus.CREATED);
        drainWorker.drain();
        resetTurbosms();
        // masterServiceIds (the website's body) creates a visit, so the provider declines the visit.
        UUID appointmentId = UUID.fromString(readJson(created.getBody()).path("appointmentId").asText());

        ResponseEntity<String> decline = restTemplate.exchange("/api/v1/appointments/" + appointmentId + "/decline",
                HttpMethod.PATCH, new HttpEntity<>("{}", bearerHeaders(tokenFor(salon.ownerEmail()))), String.class);
        drainWorker.drain();

        assertThat(decline.getStatusCode()).as(decline.getBody()).isEqualTo(HttpStatus.NO_CONTENT);
        JsonNode payload = sentTurbosmsPayload();
        assertThat(payload.path("recipients").get(0).asText()).isEqualTo(LINK_PHONE);
        assertThat(payload.path("sms").path("text").asText()).endsWith(SALON_REBOOK + salonSlug);
    }

    private ResponseEntity<String> salonBook(UUID masterId, UUID masterServiceId, OffsetDateTime startsAt,
                                             String token) {
        HttpHeaders headers = guestBookingHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = """
                {"masterServiceIds":["%s"],"startsAt":"%s","name":"%s","surname":"%s","privacyAccepted":true,"privacyPolicyVersion":"2026-10-20"}
                """.formatted(masterServiceId, startsAt, GUEST_NAME, GUEST_SURNAME);
        return restTemplate.exchange("/api/v1/book/s/" + salonSlug + "/masters/" + masterId + "/booking",
                HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private String cancelPath(ResponseEntity<String> created) {
        return readJson(created.getBody()).path("cancelUrl").asText().replaceAll(".*/book/cancel/", "");
    }
}
