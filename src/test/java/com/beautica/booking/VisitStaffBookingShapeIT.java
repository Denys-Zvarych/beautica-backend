package com.beautica.booking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 22.15 — the NEW arm of the dual-shape parity matrix: a STAFF visit built through the real
 * {@code POST /masters/&#123;masterId&#125;/bookings} endpoint, exactly the way production creates
 * one today — {@code Appointment} header + N chained {@code bookings} rows, even at N = 1 (Phase
 * 258 D2, no size short-circuit).
 */
@DisplayName("VisitStaffBookingShapeIT — appointment header + N chained rows, built through POST /masters/{id}/bookings")
class VisitStaffBookingShapeIT extends AbstractStaffVisitShapeIT {

    @Override
    protected boolean hasAppointmentHeader() {
        return true;
    }

    @Override
    protected Visit givenStaffVisit(int serviceCount, OffsetDateTime startsAt) {
        List<UUID> serviceIds = nServices(serviceCount);
        return postWalkIn(salon.masterId(), serviceIds, providerToken(), startsAt);
    }

    protected Visit postWalkIn(UUID masterId, List<UUID> masterServiceIds, String token, OffsetDateTime startsAt) {
        String idsJson = masterServiceIds.stream()
                .map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(","));
        String body = """
                {"masterServiceIds":[%s],"startsAt":"%s",
                 "guest":{"name":"%s","surname":"%s","phone":"%s"}}
                """.formatted(idsJson, startsAt, GUEST_FIRST_NAME, GUEST_LAST_NAME, RAW_PHONE);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/bookings", HttpMethod.POST,
                new HttpEntity<>(body, bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("walk-in visit setup must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        try {
            var data = objectMapper.readTree(resp.getBody()).path("data");
            UUID appointmentId = UUID.fromString(data.path("id").asText());
            List<UUID> bookingIds = new java.util.ArrayList<>();
            data.path("items").forEach(item -> bookingIds.add(UUID.fromString(item.path("bookingId").asText())));
            return new Visit(appointmentId, bookingIds);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse visit response: " + resp.getBody(), e);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // D6 — the notification silence, re-pinned at N > 1
    // ════════════════════════════════════════════════════════════════════════════
    //
    // Not part of the shared dual-shape @Nested matrix: this is exclusively a CREATE-time behaviour
    // of the real endpoint (StaffBookingService never enqueues an outbox row on any authorized
    // path), so it is meaningless against the LEGACY fixture (a raw JDBC insert obviously enqueues
    // nothing — asserting that would prove nothing about production code). Already pinned at N = 1
    // by StaffBookingEndpointIT#should_sendWalkInSmsAndNoNotification_when_staffBookingSucceeds;
    // this is the N > 1 re-pin the phase's D6 asks for, on all three authorized paths.
    //
    // NOTE for the QA report: docs/backend-phases/phase-262-22.15-walkin-visit-dual-shape-parity-matrix.md's
    // "Files touched" table does not list a file for this test, and the strict "two subclasses
    // contain fixtures only" acceptance bullet would forbid adding it to the shared matrix. Placed
    // here as the least-bad fit — this class already talks to the real endpoint on all three roles
    // it would otherwise need duplicated fixture code for. Flagged, not silently deviated.

    @Test
    @DisplayName("D6 — notification_outbox stays empty after a 5-service walk-in, on all three authorized paths")
    void should_enqueueNothing_when_multiServiceWalkInCreated() {
        // 5 services x DURATION_MINUTES(60) = 5h, so the owner and admin visits (same master) need
        // distinct days to both fit the 09:00-17:00 (8h) window without colliding.
        postWalkIn(salon.masterId(), nServices(5), tokenFor(salon.ownerEmail()), kyivAt(1, 9, 0));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class))
                .as("SALON_OWNER path")
                .isZero();

        String adminEmail = insertUser("SALON_ADMIN", salon.salonId()).email();
        postWalkIn(salon.masterId(), nServices(5), tokenFor(adminEmail), kyivAt(2, 9, 0));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class))
                .as("SALON_ADMIN path")
                .isZero();

        Independent solo = seedIndependentMaster();
        List<UUID> soloServices = new java.util.ArrayList<>();
        soloServices.add(solo.masterServiceId());
        for (int i = 1; i < 5; i++) {
            soloServices.add(insertService(solo.masterId(), "INDEPENDENT_MASTER", solo.userId()));
        }
        postWalkIn(solo.masterId(), soloServices, tokenFor(solo.email()), kyivAt(1, 9, 0));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notification_outbox", Integer.class))
                .as("INDEPENDENT_MASTER self-booking path")
                .isZero();
    }

    /** {@code daysFromNow} days from today at the given Kyiv wall-clock time — {@link #tomorrowAt}
     * only reaches +1 day, and this test needs +2 to keep two same-master 5-hour visits apart. */
    private OffsetDateTime kyivAt(int daysFromNow, int hour, int minute) {
        return java.time.LocalDate.now(com.beautica.common.TimeZones.KYIV).plusDays(daysFromNow)
                .atTime(hour, minute).atZone(com.beautica.common.TimeZones.KYIV).toOffsetDateTime();
    }
}
