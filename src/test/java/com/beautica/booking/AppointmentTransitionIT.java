package com.beautica.booking;

import static org.assertj.core.api.Assertions.assertThat;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Full-HTTP-stack suite for visit (appointment) transitions over real Postgres. Phase 475 removed the
 * whole-visit {@code cancel}/{@code decline}/{@code complete}/{@code not-complete}/{@code reschedule}
 * routes and {@code PATCH /bookings/{id}/not-complete}; this suite pins that each is unmapped (404) and
 * changes nothing, then covers the per-service decline
 * ({@code PATCH /appointments/{id}/services/{bookingId}/decline}): one child only, header collapse on
 * the last CONFIRMED child, note on the child row, slot release, 404/409 error contract.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Visit transitions — removed whole-visit routes (404) + per-service decline")
class AppointmentTransitionIT extends AbstractIntegrationTest {

    private static final String APPOINTMENTS_URL = "/api/v1/appointments";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void seedFixtures() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // Phase 475 — the whole-visit routes and every not-complete route are unmapped (D2: Spring's 404)
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "PATCH {0} as the visit''s own {1} → 404, visit untouched")
    @CsvSource({
            "/api/v1/appointments/{a}/cancel,       CLIENT",
            "/api/v1/appointments/{a}/cancel,       PROVIDER",
            "/api/v1/appointments/{a}/decline,      CLIENT",
            "/api/v1/appointments/{a}/decline,      PROVIDER",
            "/api/v1/appointments/{a}/complete,     CLIENT",
            "/api/v1/appointments/{a}/complete,     PROVIDER",
            "/api/v1/appointments/{a}/not-complete, CLIENT",
            "/api/v1/appointments/{a}/not-complete, PROVIDER",
            "/api/v1/appointments/{a}/reschedule,   CLIENT",
            "/api/v1/appointments/{a}/reschedule,   PROVIDER",
            "/api/v1/bookings/{b}/not-complete,     CLIENT",
            "/api/v1/bookings/{b}/not-complete,     PROVIDER"
    })
    @DisplayName("every removed route (5 whole-visit + PATCH /bookings/{id}/not-complete) answers 404 to BOTH the "
            + "visit's own client and its provider, and changes nothing (no route sets NOT_COMPLETED, BOOK-D12)")
    void should_return404AndChangeNothing_when_removedRouteCalledByClientOrProvider(String template, String actor)
            throws Exception {
        Visit visit = createTwoServiceVisit("gone-" + actor.toLowerCase() + "-" + Math.abs(template.hashCode()));
        UUID child = childIdsOf(visit.id()).get(0);
        String path = template.replace("{a}", visit.id().toString()).replace("{b}", child.toString());
        String token = "CLIENT".equals(actor) ? visit.clientToken() : visit.masterToken();
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"newStartsAt\":\"" + ZonedDateTime.now(TimeZones.KYIV).plusDays(20).toOffsetDateTime()
                + "\",\"providerComment\":\"note\",\"cancellationReason\":\"CLIENT_NO_SHOW\"}";

        ResponseEntity<String> resp = restTemplate.exchange(path, HttpMethod.PATCH, new HttpEntity<>(body, headers),
                String.class);

        assertThat(resp.getStatusCode())
                .as("PATCH %s must be unmapped for %s — body: %s", template, actor, resp.getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertVisitStatus(visit.id(), "CONFIRMED");
        assertAllItemsStatus(visit.id(), "CONFIRMED", 2);
        assertThat(statusChangedCount(visit.id())).isEqualTo(0L);
    }

    @ParameterizedTest(name = "PATCH {0} with NO token → 401, visit untouched")
    @CsvSource({
            "/api/v1/appointments/{a}/cancel",
            "/api/v1/appointments/{a}/decline",
            "/api/v1/appointments/{a}/complete",
            "/api/v1/appointments/{a}/not-complete",
            "/api/v1/appointments/{a}/reschedule",
            "/api/v1/bookings/{b}/not-complete"
    })
    @DisplayName("a removed route answers 401 (not 404) to an anonymous caller — the auth gate runs before handler "
            + "mapping, so an unauthenticated caller cannot enumerate which routes exist; with a token it is 404")
    void should_return401NotFoundOracle_when_removedRouteCalledWithoutToken(String template) throws Exception {
        Visit visit = createTwoServiceVisit("gone-anon-" + Math.abs(template.hashCode()));
        UUID child = childIdsOf(visit.id()).get(0);
        String path = template.replace("{a}", visit.id().toString()).replace("{b}", child.toString());
        HttpHeaders anonymous = new HttpHeaders();
        anonymous.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> resp = restTemplate.exchange(path, HttpMethod.PATCH,
                new HttpEntity<>("{\"cancellationReason\":\"CLIENT_NO_SHOW\"}", anonymous), String.class);

        assertThat(resp.getStatusCode()).as("anonymous PATCH %s — body: %s", path, resp.getBody())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertAllItemsStatus(visit.id(), "CONFIRMED", 2);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════════════
    // Per-service (per-child) decline — PATCH /appointments/{id}/services/{bookingId}/decline
    // ══════════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("PER-SERVICE decline sets ONLY the target child to DECLINED and leaves its sibling "
            + "CONFIRMED — the header stays CONFIRMED, and the single STATUS_CHANGED references the "
            + "DECLINED CHILD (not item 0, not the header) so the client is told which service was lost")
    void should_declineOnlyTargetChild_andKeepSiblingConfirmed_when_perServiceDecline() throws Exception {
        Visit visit = createTwoServiceVisit("per-svc-critical");
        List<UUID> children = childIdsOf(visit.id());
        UUID child0 = children.get(0); // 10:00 — the one that MUST survive
        UUID child1 = children.get(1); // 11:00 — the one we decline

        // Decline the SECOND child: proves both (a) siblings stay CONFIRMED and (b) the notification
        // references the actually-declined child rather than always item 0.
        ResponseEntity<String> resp = patchServiceDecline(visit.masterToken(), visit.id(), child1,
                "{\"providerComment\":\"Цю послугу скасовую\"}");

        assertThat(resp.getStatusCode())
                .as("a per-service decline of one child must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // THE CRITICAL REGRESSION: the sibling is untouched.
        assertThat(itemStatus(child0))
                .as("the sibling service must stay CONFIRMED — a per-service decline must NOT cancel the "
                        + "whole visit (the bug this endpoint fixes)")
                .isEqualTo("CONFIRMED");
        // The target child is the only one that moved.
        assertThat(itemStatus(child1)).as("the declined service line").isEqualTo("DECLINED");
        assertThat(childRow(child1).get("cancellation_reason"))
                .as("the declined child carries PROVIDER_UNAVAILABLE").isEqualTo("PROVIDER_UNAVAILABLE");
        // The header stays CONFIRMED while a sibling remains CONFIRMED.
        assertVisitStatus(visit.id(), "CONFIRMED");

        // Exactly one STATUS_CHANGED for the whole visit, and it references the DECLINED CHILD.
        assertThat(statusChangedCount(visit.id()))
                .as("exactly ONE STATUS_CHANGED for a per-service decline").isEqualTo(1L);
        assertThat(statusChangedCountForBooking(child1))
                .as("the STATUS_CHANGED must reference the declined child so the client learns WHICH "
                        + "service was declined")
                .isEqualTo(1L);
        assertThat(statusChangedCountForBooking(child0))
                .as("no notification is enqueued for the untouched sibling").isEqualTo(0L);
    }

    @Test
    @DisplayName("declining the LAST CONFIRMED child collapses the header to DECLINED — with a sibling "
            + "already gone, declining the remaining child terminates the whole visit")
    void should_collapseHeaderToDeclined_when_lastConfirmedChildDeclined() throws Exception {
        Visit visit = createTwoServiceVisit("per-svc-collapse");
        List<UUID> children = childIdsOf(visit.id());
        UUID child0 = children.get(0);
        UUID child1 = children.get(1);

        // First child declined — header must remain CONFIRMED (a sibling is still live).
        assertThat(patchServiceDecline(visit.masterToken(), visit.id(), child0, "{}").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertVisitStatus(visit.id(), "CONFIRMED");

        // Declining the LAST remaining CONFIRMED child collapses the header.
        ResponseEntity<String> resp = patchServiceDecline(visit.masterToken(), visit.id(), child1, "{}");

        assertThat(resp.getStatusCode())
                .as("declining the last CONFIRMED child must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(itemStatus(child0)).isEqualTo("DECLINED");
        assertThat(itemStatus(child1)).isEqualTo("DECLINED");
        assertVisitStatus(visit.id(), "DECLINED");
        assertThat(header(visit.id()).get("cancellation_reason"))
                .as("the collapsed header carries PROVIDER_UNAVAILABLE").isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(header(visit.id()).get("provider_comment"))
                .as("collapsing the header via per-service declines writes NO note to the header — the "
                        + "notes live on the individual child rows")
                .isNull();
    }

    @Test
    @DisplayName("the provider note on a per-service decline lands on the CHILD row and the header note "
            + "is left untouched (the header stays CONFIRMED, so a header note would be illegal)")
    void should_writeProviderNoteOnChildRow_andLeaveHeaderNoteUntouched_onPerServiceDecline() throws Exception {
        Visit visit = createTwoServiceVisit("per-svc-note");
        List<UUID> children = childIdsOf(visit.id());
        UUID declined = children.get(0);

        String note = "Майстер не встигне зробити цю послугу";
        ResponseEntity<String> resp = patchServiceDecline(visit.masterToken(), visit.id(), declined,
                "{\"providerComment\":\"" + note + "\"}");

        assertThat(resp.getStatusCode())
                .as("per-service decline with a note must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(childRow(declined).get("provider_comment"))
                .as("the provider note is written onto the declined CHILD row").isEqualTo(note);
        Map<String, Object> header = header(visit.id());
        assertThat(header.get("provider_comment"))
                .as("the header note stays null — the header is still CONFIRMED and its note must not be "
                        + "written on a partial decline")
                .isNull();
        assertVisitStatus(visit.id(), "CONFIRMED");
    }

    @Test
    @DisplayName("a per-service decline releases ONLY the declined child's slot — a new booking can take "
            + "the freed slot while the surviving sibling still blocks its own")
    void should_releaseDeclinedChildSlot_whileSiblingHoldsItsSlot() throws Exception {
        String masterEmail = "appt-per-svc-slot-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "appt-per-svc-slot-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);
        String masterToken = fixtures.tokenFor(masterEmail);

        // Visit: serviceA [10:00,11:00), serviceB [11:00,12:00).
        ZonedDateTime firstStart = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID appointmentId = postTwoServiceVisit(clientToken, masterId, firstStart, serviceA, serviceB);
        List<UUID> children = childIdsOf(appointmentId);
        UUID child0 = children.get(0); // the 10:00 slot we will free

        assertThat(patchServiceDecline(masterToken, appointmentId, child0, "{}").getStatusCode())
                .as("freeing the first child's slot").isEqualTo(HttpStatus.NO_CONTENT);

        // A different client books the SAME master at the now-freed 10:00 slot → must succeed.
        String rivalEmail = "appt-per-svc-slot-rival-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(rivalEmail, "CLIENT", null);
        String rivalToken = fixtures.tokenFor(rivalEmail);
        ResponseEntity<String> freed = postVisitRaw(rivalToken, masterId, firstStart, serviceA);
        assertThat(freed.getStatusCode())
                .as("the declined child's 10:00 slot must be re-bookable — body: %s", freed.getBody())
                .isEqualTo(HttpStatus.CREATED);

        // The surviving sibling still holds 11:00 — a booking at 11:00 must be rejected.
        ResponseEntity<String> blocked = postVisitRaw(rivalToken, masterId, firstStart.plusHours(1), serviceB);
        assertThat(blocked.getStatusCode())
                .as("the surviving sibling must still block its own 11:00 slot — body: %s", blocked.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    @DisplayName("a per-service decline for a bookingId that is not a child of the appointment returns "
            + "404 — for a random UUID AND for a real booking belonging to a DIFFERENT visit (no oracle "
            + "beyond the visit the caller is already authorized on)")
    void should_return404_when_bookingIdIsNotAChildOfTheAppointment() throws Exception {
        Visit visit = createTwoServiceVisit("per-svc-404");

        // (a) a completely unknown booking id.
        assertThat(patchServiceDecline(visit.masterToken(), visit.id(), UUID.randomUUID(), "{}").getStatusCode())
                .as("a bookingId absent from the appointment is a 404")
                .isEqualTo(HttpStatus.NOT_FOUND);

        // (b) a REAL child booking that belongs to a DIFFERENT visit — indistinguishable from missing,
        // so still 404 (no cross-visit existence oracle) even though the row genuinely exists.
        Visit other = createTwoServiceVisit("per-svc-404-other");
        UUID foreignChild = childIdsOf(other.id()).get(0);
        assertThat(patchServiceDecline(visit.masterToken(), visit.id(), foreignChild, "{}").getStatusCode())
                .as("a real booking id from another visit must be a uniform 404, not a leak")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(itemStatus(foreignChild))
                .as("the foreign visit's child must be left untouched").isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("re-declining an already-DECLINED child returns 409 and changes nothing — the child "
            + "stays DECLINED, the sibling stays CONFIRMED, and no second STATUS_CHANGED is enqueued")
    void should_return409AndChangeNothing_when_redecliningAnAlreadyDeclinedChild() throws Exception {
        Visit visit = createTwoServiceVisit("per-svc-409");
        List<UUID> children = childIdsOf(visit.id());
        UUID child0 = children.get(0);
        UUID child1 = children.get(1);
        assertThat(patchServiceDecline(visit.masterToken(), visit.id(), child0, "{}").getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> second = patchServiceDecline(visit.masterToken(), visit.id(), child0, "{}");

        assertThat(second.getStatusCode())
                .as("re-declining a terminal child is a 409 — the per-item transition guard")
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(itemStatus(child0)).isEqualTo("DECLINED");
        assertThat(itemStatus(child1)).as("the sibling is untouched by the rejected re-decline").isEqualTo("CONFIRMED");
        assertVisitStatus(visit.id(), "CONFIRMED");
        assertThat(statusChangedCountForBooking(child0))
                .as("the rejected re-decline must not enqueue a second notification for the child")
                .isEqualTo(1L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** A created two-service CONFIRMED visit plus the client and owning-master tokens to act on it. */
    private record Visit(UUID id, String clientToken, String masterToken) {}

    /**
     * Creates an INDEPENDENT_MASTER + CLIENT and posts a two-service CONFIRMED visit, returning its id
     * and both actors' tokens. {@code tag} keeps the generated emails unique per test.
     */
    private Visit createTwoServiceVisit(String tag) throws Exception {
        String masterEmail = "appt-tx-" + tag + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "appt-tx-" + tag + "-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);
        String masterToken = fixtures.tokenFor(masterEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        String body = """
                {"masterId":"%s","masterServiceIds":["%s","%s"],"startsAt":"%s"}
                """.formatted(masterId, serviceA, serviceB, startsAt.toOffsetDateTime());
        HttpHeaders headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> created = restTemplate.exchange(
                APPOINTMENTS_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(created.getStatusCode())
                .as("visit setup must succeed — body: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode data = objectMapper.readTree(created.getBody()).path("data");
        UUID appointmentId = UUID.fromString(data.path("id").asText());
        fixtures.dropCreateTimeNotifications(appointmentId);
        return new Visit(appointmentId, clientToken, masterToken);
    }

    /** PATCH the per-service decline route {@code /appointments/{id}/services/{bookingId}/decline}. */
    private ResponseEntity<String> patchServiceDecline(String token, UUID appointmentId, UUID bookingId, String body) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + bookingId + "/decline",
                HttpMethod.PATCH, entity, String.class);
    }

    /** The visit's chained child booking ids, ordered by starts_at ascending (item 0 = earliest). */
    private List<UUID> childIdsOf(UUID appointmentId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at", UUID.class, appointmentId);
    }

    private String itemStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private Map<String, Object> childRow(UUID bookingId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, cancellation_reason, provider_comment FROM bookings WHERE id = ?", bookingId);
    }

    private Long statusChangedCountForBooking(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'STATUS_CHANGED' AND aggregate_id = ?",
                Long.class, bookingId);
    }

    /** POSTs a multi-service visit and returns its appointment id (asserting 201). */
    private UUID postTwoServiceVisit(
            String clientToken, UUID masterId, ZonedDateTime startsAt, UUID serviceA, UUID serviceB) throws Exception {
        ResponseEntity<String> resp = postVisitRaw(clientToken, masterId, startsAt, serviceA, serviceB);
        assertThat(resp.getStatusCode())
                .as("visit setup must succeed — body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(resp.getBody()).path("data").path("id").asText());
    }

    /** POSTs a visit for the given services at {@code startsAt} and returns the RAW response (no assert). */
    private ResponseEntity<String> postVisitRaw(
            String clientToken, UUID masterId, ZonedDateTime startsAt, UUID... serviceIds) {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < serviceIds.length; i++) {
            if (i > 0) {
                ids.append(',');
            }
            ids.append('"').append(serviceIds[i]).append('"');
        }
        String body = """
                {"masterId":"%s","masterServiceIds":[%s],"startsAt":"%s"}
                """.formatted(masterId, ids, startsAt.toOffsetDateTime());
        HttpHeaders headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(
                APPOINTMENTS_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    private void assertVisitStatus(UUID appointmentId, String expected) {
        String status = jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
        assertThat(status).as("appointment header status").isEqualTo(expected);
    }

    private void assertAllItemsStatus(UUID appointmentId, String expected, int expectedCount) {
        List<String> statuses = jdbcTemplate.queryForList(
                "SELECT status FROM bookings WHERE appointment_id = ? ORDER BY starts_at",
                String.class, appointmentId);
        assertThat(statuses)
                .as("every chained item must move in lockstep with the header")
                .hasSize(expectedCount)
                .allSatisfy(s -> assertThat(s).isEqualTo(expected));
    }

    private Map<String, Object> header(UUID appointmentId) {
        return jdbcTemplate.queryForMap(
                "SELECT cancellation_reason, client_cancellation_note, provider_comment "
                        + "FROM appointments WHERE id = ?", appointmentId);
    }

    private Long statusChangedCount(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'STATUS_CHANGED' "
                        + "AND aggregate_id IN (SELECT id FROM bookings WHERE appointment_id = ?)",
                Long.class, appointmentId);
    }

    /**
     * Open-ended weekly schedule with all seven ISO weekdays 08:00–20:00 so a near-future visit can be
     * booked on any day. Mirrors {@code AppointmentCreateIT}'s local helper of the same name.
     */
    private void addWorkingHoursForEveryDay(UUID masterId) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to) "
                        + "VALUES (?, ?, DATE '2020-01-01', NULL)",
                scheduleId, masterId);
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, ?, ?, '08:00', '20:00')",
                    UUID.randomUUID(), scheduleId, day);
        }
    }
}
