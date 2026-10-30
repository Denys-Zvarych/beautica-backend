package com.beautica.booking;

import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.sms.NoOpSmsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalTime;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 431 — the guest reschedule SMS with {@code app.booking.sms.enabled} at its committed default
 * ({@code false}): no property is overridden, so this class shares {@link WalkInBookingSmsIT}'s
 * flag-off context. A separate top-level class, not {@code @Nested}, for the reason given on
 * {@link AbstractWalkInBookingSmsIT}. The enabled half is {@link GuestRescheduleSmsIT}.
 */
@DisplayName("GuestRescheduleSmsDisabledIT — phase 431 guest reschedule SMS, gate off")
class GuestRescheduleSmsDisabledIT extends AbstractWalkInBookingSmsIT {

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Test
    @DisplayName("gate off — a provider reschedule of a LINK guest booking makes ZERO Turbosms requests and the "
            + "outbox row still drains to SENT")
    void should_sendNoSms_when_smsGateIsOff() {
        UUID bookingId = insertConfirmedLinkBooking(salon.masterId(), salon.masterServiceId(), tomorrowAtNoon());

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/bookings/" + bookingId + "/reschedule",
                HttpMethod.PATCH, new HttpEntity<>("{\"newStartsAt\":\"" + tomorrowAt(LocalTime.of(14, 0)) + "\"}",
                        bearerHeaders(tokenFor(salon.ownerEmail()))), String.class);
        drainWorker.drain();

        assertThat(smsService).isInstanceOf(NoOpSmsService.class);
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        TURBOSMS.verify(0, postRequestedFor(urlEqualTo(TURBOSMS_PATH)));
        assertThat(outboxRepository.findAll()).hasSize(1).allMatch(row -> row.getStatus() == OutboxStatus.SENT);
    }
}
