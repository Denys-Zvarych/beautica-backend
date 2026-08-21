package com.beautica.booking;

import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one route + shape combination nothing exercised:
 * {@code PATCH /appointments/&#123;appointmentId&#125;/services/&#123;bookingId&#125;/reschedule}
 * driven against a <b>STAFF walk-in visit</b> (null client, {@code booking_source = STAFF},
 * {@code Appointment} header + N chained rows).
 *
 * <p>{@link AppointmentItemRescheduleIT} covers the same route only for APP/client visits, and
 * {@link AbstractStaffVisitShapeIT}'s dual-shape matrix deliberately drives transitions exclusively
 * through the plain {@code /bookings/&#123;id&#125;/...} routes (a LEGACY row has no header to
 * address). So the per-item route had never met a client-less visit: every null-client guard on that
 * path — authorization, the client-lock skip, the notification dispatch — was structurally untested.
 *
 * <h2>What this pins</h2>
 * <ol>
 *   <li>200 and the moved row actually moves;</li>
 *   <li>the siblings are byte-identical — the locked per-item rule
 *       ({@code project_completion_is_per_service}): moving ONE service never touches another, and
 *       the visit may legally become non-contiguous. This suite deliberately never asserts
 *       contiguity, which would pin the OPPOSITE of the locked decision;</li>
 *   <li>the visit stays client-less on the write path AND on the read it returns — walk-in identity
 *       on visit read is GATED (phase 263), so no client field may appear;</li>
 *   <li>the {@code BOOKING_RESCHEDULED} row drains to {@code SENT}, never {@code DEAD}. A
 *       provider-initiated reschedule routes to {@code notifyBookingRescheduledClient}, which
 *       dereferences {@code booking.getClient()} — for a walk-in that is null, and without the guard
 *       the drain worker NPEs and buries the row DEAD after MAX_ATTEMPTS. Nothing reached that guard
 *       from the per-item route before.</li>
 * </ol>
 *
 * <p>{@code NotificationService} is left REAL (the sinks below it — {@code EmailService} /
 * {@code EmailNotificationService} — are already {@code @MockBean} on
 * {@link com.beautica.AbstractIntegrationTest}), because point 4 needs the genuine
 * enqueue-then-drain path; a mocked notification service would prove nothing about it.
 */
@Import(TestSecurityConfig.class)
@DisplayName("PATCH /appointments/{id}/services/{bookingId}/reschedule — STAFF walk-in visit (null client)")
class StaffVisitItemRescheduleIT extends AbstractStaffBookingIT {

    private static final String APPOINTMENTS_URL = "/api/v1/appointments";
    private static final int SERVICE_COUNT = 3;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    @Test
    @DisplayName("the master moves the MIDDLE leg of a three-service walk-in — 200, only that leg's "
            + "window changes, and both siblings stay byte-for-byte identical")
    void should_moveOnlyTheTargetLeg_when_masterReschedulesOneLegOfAWalkInVisit() throws Exception {
        Independent solo = seedIndependentMaster();
        Visit visit = walkInVisitFor(solo, tomorrowAt(LocalTime.of(9, 0)));
        List<ItemRow> before = itemRows(visit.appointmentId());
        UUID moving = before.get(1).id();
        // 14:00 is on the 30-minute grid anchored at the seeded 09:00 work start, inside the
        // 09:00-17:00 window, and clear of every sibling's window (09:00-12:00).
        OffsetDateTime newStart = tomorrowAt(LocalTime.of(14, 0));

        ResponseEntity<String> resp = rescheduleItem(
                visit.appointmentId(), moving, tokenFor(solo.email()), newStart);

        assertThat(resp.getStatusCode())
                .as("a master moving one leg of their own walk-in visit must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);

        List<ItemRow> after = itemRows(visit.appointmentId());
        assertThat(after).as("the visit must still hold all %d items", SERVICE_COUNT).hasSize(SERVICE_COUNT);

        ItemRow movedAfter = row(after, moving);
        assertThat(movedAfter.startsAt().toInstant())
                .as("the moved leg's starts_at must equal the requested time")
                .isEqualTo(newStart.toInstant());
        assertThat(movedAfter.endsAt().toInstant())
                .as("the moved leg's ends_at must be starts_at + the FROZEN %d-minute duration", DURATION_MINUTES)
                .isEqualTo(newStart.plusMinutes(DURATION_MINUTES).toInstant());
        assertThat(movedAfter.status()).isEqualTo("CONFIRMED");

        for (ItemRow sibling : List.of(before.get(0), before.get(2))) {
            ItemRow siblingAfter = row(after, sibling.id());
            assertThat(siblingAfter)
                    .as("sibling %s must be byte-for-byte unchanged by a per-item move — no re-layout, "
                            + "no cascade, no gap-closing (the visit is allowed to become "
                            + "non-contiguous)", sibling.id())
                    .isEqualTo(sibling);
        }

        assertThat(appointmentStatus(visit.appointmentId()))
                .as("the header status is untouched by a per-item move")
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("the rescheduled walk-in visit stays client-less — every row keeps client_id NULL and "
            + "the response exposes no client identity (walk-in identity on read is GATED)")
    void should_stayClientLess_when_walkInVisitItemIsRescheduled() throws Exception {
        Independent solo = seedIndependentMaster();
        Visit visit = walkInVisitFor(solo, tomorrowAt(LocalTime.of(9, 0)));

        ResponseEntity<String> resp = rescheduleItem(
                visit.appointmentId(), visit.booking(0), tokenFor(solo.email()),
                tomorrowAt(LocalTime.of(14, 0)));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM bookings WHERE appointment_id = ? AND client_id IS NOT NULL",
                Integer.class, visit.appointmentId()))
                .as("a walk-in has no client account — a reschedule must never attach one to any row")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT client_id FROM appointments WHERE id = ?", UUID.class, visit.appointmentId()))
                .as("the visit header must stay client-less too")
                .isNull();

        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        for (String clientField : List.of("client", "clientId", "clientFirstName", "clientLastName", "guest")) {
            assertThat(data.path(clientField).isMissingNode())
                    .as("the visit-detail response must expose no '%s' — walk-in identity on visit read "
                            + "is GATED (phase 263), so surfacing it here would ship an ungated leak",
                            clientField)
                    .isTrue();
        }
        assertThat(data.path("items").size())
                .as("and the response must still render the whole visit")
                .isEqualTo(SERVICE_COUNT);
    }

    @Test
    @DisplayName("the BOOKING_RESCHEDULED entry a walk-in per-item move enqueues drains to SENT, never "
            + "DEAD — the null-client guard in notifyBookingRescheduledClient")
    void should_drainWithoutDeadRow_when_walkInVisitItemIsRescheduled() throws Exception {
        Independent solo = seedIndependentMaster();
        Visit visit = walkInVisitFor(solo, tomorrowAt(LocalTime.of(9, 0)));
        assertThat(outboxRepository.count())
                .as("walk-in CREATE enqueues nothing (D6), so the outbox starts empty")
                .isZero();

        ResponseEntity<String> resp = rescheduleItem(
                visit.appointmentId(), visit.booking(2), tokenFor(solo.email()),
                tomorrowAt(LocalTime.of(14, 0)));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        List<NotificationOutboxEntry> rows = outboxRepository.findAll();
        assertThat(rows)
                .as("a per-item reschedule enqueues exactly one row, keyed to the MOVED child")
                .hasSize(1);
        NotificationOutboxEntry entry = rows.get(0);
        assertThat(entry.getEventType().name()).isEqualTo("BOOKING_RESCHEDULED");
        assertThat(entry.getAggregateId())
                .as("the notification must name the leg that actually moved, never item 0")
                .isEqualTo(visit.booking(2));
        assertThat(entry.getPayload())
                .as("a master-initiated move is PROVIDER-initiated, which is what routes the drain to "
                        + "the client-facing dispatch that must survive a null client")
                .contains("\"initiatedBy\":\"PROVIDER\"");

        drainWorker.drain();

        NotificationOutboxEntry drained = outboxRepository.findById(entry.getId()).orElseThrow();
        assertThat(drained.getStatus())
                .as("the null-client guard must let a walk-in BOOKING_RESCHEDULED entry drain to SENT "
                        + "(a clean no-op dispatch), never DEAD")
                .isEqualTo(OutboxStatus.SENT);
        assertThat(drained.getLastError()).isNull();
        assertThat(outboxRepository.findAll().stream().filter(e -> e.getStatus() == OutboxStatus.DEAD).count())
                .as("zero DEAD rows anywhere after draining a walk-in per-item reschedule")
                .isZero();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────

    /** A {@link #SERVICE_COUNT}-service walk-in visit on the solo master's own calendar. */
    private Visit walkInVisitFor(Independent solo, OffsetDateTime startsAt) {
        List<UUID> serviceIds = new ArrayList<>(List.of(solo.masterServiceId()));
        for (int i = 1; i < SERVICE_COUNT; i++) {
            serviceIds.add(insertService(solo.masterId(), "INDEPENDENT_MASTER", solo.userId()));
        }
        Visit visit = postWalkIn(solo.masterId(), serviceIds, tokenFor(solo.email()), startsAt);
        assertThat(visit.bookingIds()).as("fixture: the walk-in must persist as N chained rows")
                .hasSize(SERVICE_COUNT);
        return visit;
    }

    private ResponseEntity<String> rescheduleItem(
            UUID appointmentId, UUID bookingId, String token, OffsetDateTime newStartsAt) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("newStartsAt", newStartsAt.toString()));
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + bookingId + "/reschedule",
                HttpMethod.PATCH, new HttpEntity<>(body, bearerHeaders(token)), String.class);
    }

    /** Everything a per-item move may legally change on a row, so equality IS the byte-identity claim. */
    private record ItemRow(UUID id, OffsetDateTime startsAt, OffsetDateTime endsAt, String status,
                           int durationMinutes, int bufferMinutes) {}

    private List<ItemRow> itemRows(UUID appointmentId) {
        return jdbcTemplate.query(
                "SELECT id, starts_at, ends_at, status, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                (rs, rowNum) -> new ItemRow(
                        UUID.fromString(rs.getString("id")),
                        rs.getObject("starts_at", OffsetDateTime.class),
                        rs.getObject("ends_at", OffsetDateTime.class),
                        rs.getString("status"),
                        rs.getInt("duration_minutes_at_booking"),
                        rs.getInt("buffer_minutes_at_booking")),
                appointmentId);
    }

    private ItemRow row(List<ItemRow> rows, UUID id) {
        return rows.stream().filter(r -> r.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("item " + id + " not found in " + rows));
    }

    private String appointmentStatus(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }
}
