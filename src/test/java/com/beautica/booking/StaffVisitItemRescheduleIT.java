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

import java.time.Instant;
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
 *   <li>200 and the moved row actually moves — and, symmetrically, a provider from another practice
 *       gets 403 with the row unmoved
 *       ({@link #should_return403_when_aForeignMasterReschedulesAWalkInItem}), which is the
 *       authorization guard this list used to claim without covering;</li>
 *   <li>the siblings are byte-identical — the locked per-item rule
 *       ({@code project_completion_is_per_service}): moving ONE service never touches another, and
 *       the visit may legally become non-contiguous. This suite deliberately never asserts
 *       contiguity, which would pin the OPPOSITE of the locked decision;</li>
 *   <li>the visit stays client-less in the DATABASE — no row and no header gains a {@code client_id}
 *       because one leg moved. The same test also carries a phase-263 TRIPWIRE on the response DTO's
 *       shape, which is not a leak check and cannot fail today — see that method's javadoc for the
 *       distinction;</li>
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
        List<Map<String, Object>> before = itemRows(visit.appointmentId());
        UUID moving = (UUID) before.get(1).get("id");
        // 14:00 is on the 30-minute grid anchored at the seeded 09:00 work start, inside the
        // 09:00-17:00 window, and clear of every sibling's window (09:00-12:00).
        OffsetDateTime newStart = tomorrowAt(LocalTime.of(14, 0));

        ResponseEntity<String> resp = rescheduleItem(
                visit.appointmentId(), moving, tokenFor(solo.email()), newStart);

        assertThat(resp.getStatusCode())
                .as("a master moving one leg of their own walk-in visit must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> after = itemRows(visit.appointmentId());
        assertThat(after).as("the visit must still hold all %d items", SERVICE_COUNT).hasSize(SERVICE_COUNT);

        Map<String, Object> movedAfter = row(after, moving);
        assertThat(instantOf(movedAfter, "starts_at"))
                .as("the moved leg's starts_at must equal the requested time")
                .isEqualTo(newStart.toInstant());
        assertThat(instantOf(movedAfter, "ends_at"))
                .as("the moved leg's ends_at must be starts_at + the FROZEN %d-minute duration", DURATION_MINUTES)
                .isEqualTo(newStart.plusMinutes(DURATION_MINUTES).toInstant());
        assertThat(movedAfter.get("status")).isEqualTo("CONFIRMED");

        for (Map<String, Object> sibling : List.of(before.get(0), before.get(2))) {
            UUID siblingId = (UUID) sibling.get("id");
            assertThat(row(after, siblingId))
                    .as("sibling %s must be byte-for-byte unchanged by a per-item move — every "
                            + "column, updated_at included, so a stray re-persist that changes "
                            + "nothing but the audit stamp still fails: no re-layout, no cascade, "
                            + "no gap-closing (the visit is allowed to become non-contiguous)",
                            siblingId)
                    .isEqualTo(sibling);
        }

        assertThat(appointmentStatus(visit.appointmentId()))
                .as("the header status is untouched by a per-item move")
                .isEqualTo("CONFIRMED");
    }

    /**
     * <b>Two assertions of very different strength, deliberately kept in one test — read the names.</b>
     *
     * <ul>
     *   <li>The DB half is the real guarantee: no row of the visit, and not the header, gains a
     *       {@code client_id} because one leg moved. That is falsifiable and would go red if the
     *       reschedule path ever attached the acting user as a client.</li>
     *   <li>The response half is a <b>tripwire, not a guarantee</b>. {@code AppointmentDetailResponse}
     *       declares no {@code client*}/{@code guest} component at all, so the
     *       {@code isMissingNode()} loop is structurally satisfied by the DTO's shape and cannot fail
     *       today whatever the service does. It earns its place only as a phase-263 alarm: the moment
     *       someone adds one of those components to the DTO — which is exactly what ungating walk-in
     *       identity on visit read would mean — this loop goes red and forces the decision to be
     *       explicit. It must never be read as evidence that the response was checked for leaks.</li>
     * </ul>
     */
    @Test
    @DisplayName("the rescheduled walk-in visit keeps client_id NULL on every row and on the header; "
            + "plus a phase-263 tripwire on the response DTO's shape")
    void should_keepEveryRowClientLess_when_walkInVisitItemIsRescheduled() throws Exception {
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

        // TRIPWIRE, not a leak check — see this method's javadoc. AppointmentDetailResponse declares
        // none of these components, so the loop cannot fail today; it exists to go red the moment one
        // is ADDED, which is precisely what ungating walk-in identity on visit read (phase 263) would
        // require. Do not cite it as evidence the response was checked.
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        for (String clientField : List.of("client", "clientId", "clientFirstName", "clientLastName", "guest")) {
            assertThat(data.path(clientField).isMissingNode())
                    .as("phase-263 tripwire: AppointmentDetailResponse must still declare no '%s' "
                            + "component — adding one is an ungating decision, not a refactor",
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

    /**
     * <b>The authorization negative this class's own javadoc named as untested.</b>
     *
     * <p>Point 1 of "What this pins" says every null-client guard on this route was structurally
     * untested — authorization first among them — yet every test above drives the route as the
     * legitimate owning master. A walk-in row is {@code client_id IS NULL}: the exact shape an
     * "actor is the owning client OR actor is the provider" predicate falls through, because the
     * first disjunct can never match and only the second is doing any work. If the provider half
     * were widened (or dropped) nothing here would have noticed.
     *
     * <p>The foreign actor is a second INDEPENDENT_MASTER — a real, active, bookable provider whose
     * ONLY difference from the legitimate one is identity, so the 403 cannot come from role gating
     * or from an inactive account.
     *
     * <p><b>The status assertion alone is not the test.</b> A 403 returned AFTER the row was already
     * moved is precisely the bug this guards, so the row's {@code starts_at} is read back from the
     * database and compared against its pre-request value.
     */
    @Test
    @DisplayName("a master from another practice reschedules a leg of someone else's walk-in — 403 "
            + "AND the row does not move")
    void should_return403_when_aForeignMasterReschedulesAWalkInItem() throws Exception {
        Independent owner = seedIndependentMaster();
        Independent stranger = seedIndependentMaster();
        Visit visit = walkInVisitFor(owner, tomorrowAt(LocalTime.of(9, 0)));
        UUID moving = visit.booking(1);
        OffsetDateTime startsAtBefore = dbStartsAt(moving);

        ResponseEntity<String> resp = rescheduleItem(
                visit.appointmentId(), moving, tokenFor(stranger.email()), tomorrowAt(LocalTime.of(14, 0)));

        assertThat(resp.getStatusCode())
                .as("the walk-in belongs to another master's calendar; a client-less row gives the "
                        + "ownership disjunct nothing to match, so the provider-scope check is the "
                        + "only thing that can refuse this — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStartsAt(moving))
                .as("a 403 with a mutated row is the bug this test exists for — the refusal must "
                        + "happen before the write, not after it")
                .isEqualTo(startsAtBefore);
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

    /**
     * <b>{@code SELECT *}, not a hand-listed column subset.</b> The sibling assertion claims the rows
     * are "byte-for-byte unchanged", and a projection can only ever support that claim for the
     * columns it happens to name. The earlier 6-column {@code ItemRow} record excluded
     * {@code updated_at} — the canonical tell of a stray re-persist — so a cascade that touched a
     * sibling and bumped nothing but its audit stamp satisfied the claim in full. Same idiom
     * {@code AbstractStaffVisitShapeIT#bookingRow} already uses for the identical claim.
     */
    private List<Map<String, Object>> itemRows(UUID appointmentId) {
        return jdbcTemplate.queryForList(
                "SELECT * FROM bookings WHERE appointment_id = ? ORDER BY starts_at", appointmentId);
    }

    private Map<String, Object> row(List<Map<String, Object>> rows, UUID id) {
        return rows.stream().filter(r -> id.equals(r.get("id"))).findFirst()
                .orElseThrow(() -> new AssertionError("item " + id + " not found in " + rows));
    }

    /**
     * {@code queryForList} hands back the JDBC-native {@link java.sql.Timestamp} for a
     * {@code timestamptz}, not an {@link OffsetDateTime} — so instants are read out through here
     * rather than cast at the call site. Comparison is on the INSTANT: the column stores a point in
     * time, and the driver's rendering offset is not part of the contract under test.
     */
    private static Instant instantOf(Map<String, Object> row, String column) {
        return ((java.sql.Timestamp) row.get(column)).toInstant();
    }

    /** The moving row's {@code starts_at} read straight from the DB — the half a status-only 403
     * assertion cannot cover. */
    private OffsetDateTime dbStartsAt(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT starts_at FROM bookings WHERE id = ?", OffsetDateTime.class, bookingId);
    }

    private String appointmentStatus(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }
}
