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
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Full-HTTP-stack smoke suite for BE-4's four appointment-level (visit) status transitions over real
 * Postgres. Each transition acts on the WHOLE visit: the {@code appointments} header AND every chained
 * {@code bookings} item move to the terminal state in lockstep, atomically. These tests prove the
 * lockstep + atomicity + single-notification guarantees and the illegal/foreign-actor error contract;
 * the exhaustive QA matrix (per-role authz, elapsed-clock guard, cache-eviction assertions) follows.
 */
@Import(TestSecurityConfig.class)
@DisplayName("PATCH /appointments/{id}/(cancel|decline|complete|not-complete) — visit transitions")
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
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    @Test
    @DisplayName("client cancel moves the visit AND all items to CANCELLED in lockstep, writes the "
            + "note+reason on the header, and enqueues exactly ONE STATUS_CHANGED notification")
    void should_moveAllItemsToCancelled_when_clientCancelsVisit() throws Exception {
        Visit visit = createTwoServiceVisit("cancel");

        ResponseEntity<String> resp = patch(visit.clientToken(), visit.id(), "cancel",
                "{\"clientCancellationNote\":\"Змінилися плани\"}");

        assertThat(resp.getStatusCode())
                .as("client cancel must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertVisitStatus(visit.id(), "CANCELLED");
        assertAllItemsStatus(visit.id(), "CANCELLED", 2);
        Map<String, Object> header = header(visit.id());
        assertThat(header.get("cancellation_reason")).isEqualTo("CLIENT_CANCELLED");
        assertThat(header.get("client_cancellation_note")).isEqualTo("Змінилися плани");
        assertThat(header.get("provider_comment")).as("client cancel writes no provider note").isNull();
        assertThat(statusChangedCount(visit.id()))
                .as("exactly ONE STATUS_CHANGED per visit transition, not one per item")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("provider decline moves the visit AND all items to DECLINED in lockstep, writes the "
            + "provider note + PROVIDER_UNAVAILABLE on the header, and enqueues ONE STATUS_CHANGED")
    void should_moveAllItemsToDeclined_when_providerDeclinesVisit() throws Exception {
        Visit visit = createTwoServiceVisit("decline");

        ResponseEntity<String> resp = patch(visit.masterToken(), visit.id(), "decline",
                "{\"providerComment\":\"Майстер захворів\"}");

        assertThat(resp.getStatusCode())
                .as("provider decline must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertVisitStatus(visit.id(), "DECLINED");
        assertAllItemsStatus(visit.id(), "DECLINED", 2);
        Map<String, Object> header = header(visit.id());
        assertThat(header.get("cancellation_reason")).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(header.get("provider_comment")).isEqualTo("Майстер захворів");
        assertThat(statusChangedCount(visit.id())).isEqualTo(1L);
    }

    @Test
    @DisplayName("provider complete moves the visit AND all items to COMPLETED in lockstep and "
            + "enqueues exactly ONE STATUS_CHANGED notification")
    void should_moveAllItemsToCompleted_when_providerCompletesVisit() throws Exception {
        Visit visit = createTwoServiceVisit("complete");

        ResponseEntity<String> resp = patch(visit.masterToken(), visit.id(), "complete", null);

        assertThat(resp.getStatusCode())
                .as("provider complete must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertVisitStatus(visit.id(), "COMPLETED");
        assertAllItemsStatus(visit.id(), "COMPLETED", 2);
        assertThat(statusChangedCount(visit.id())).isEqualTo(1L);
    }

    @Test
    @DisplayName("provider no-show moves the visit AND all items to NOT_COMPLETED in lockstep and "
            + "writes the provider note + CLIENT_NO_SHOW on the header")
    void should_moveAllItemsToNotCompleted_when_providerMarksNoShow() throws Exception {
        Visit visit = createTwoServiceVisit("noshow");

        ResponseEntity<String> resp = patch(visit.masterToken(), visit.id(), "not-complete",
                "{\"providerComment\":\"Клієнт не прийшов\"}");

        assertThat(resp.getStatusCode())
                .as("provider no-show must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertVisitStatus(visit.id(), "NOT_COMPLETED");
        assertAllItemsStatus(visit.id(), "NOT_COMPLETED", 2);
        Map<String, Object> header = header(visit.id());
        assertThat(header.get("cancellation_reason")).isEqualTo("CLIENT_NO_SHOW");
        assertThat(header.get("provider_comment")).isEqualTo("Клієнт не прийшов");
        assertThat(statusChangedCount(visit.id())).isEqualTo(1L);
    }

    @Test
    @DisplayName("re-declining an already-DECLINED visit returns 400 and changes nothing — the visit "
            + "and every item stay DECLINED and no second STATUS_CHANGED is enqueued (idempotent-safe)")
    void should_return400AndChangeNothing_when_decliningAnAlreadyTerminalVisit() throws Exception {
        Visit visit = createTwoServiceVisit("re-decline");
        patch(visit.masterToken(), visit.id(), "decline", "{}");

        ResponseEntity<String> second = patch(visit.masterToken(), visit.id(), "decline", "{}");

        assertThat(second.getStatusCode())
                .as("a second decline is an illegal transition from a terminal state — 400, "
                        + "the same error the single-booking path returns")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertVisitStatus(visit.id(), "DECLINED");
        assertAllItemsStatus(visit.id(), "DECLINED", 2);
        assertThat(statusChangedCount(visit.id()))
                .as("the rejected re-decline must not enqueue a second notification")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a foreign provider (a different master) declining someone else's visit is denied "
            + "with 403 and the visit + all items stay CONFIRMED (nothing transitions)")
    void should_return403AndKeepConfirmed_when_foreignProviderDeclines() throws Exception {
        Visit visit = createTwoServiceVisit("foreign");
        // A completely unrelated independent master authenticates and tries to decline the visit.
        String foreignEmail = "appt-foreign-provider-" + System.nanoTime() + "@beautica.test";
        fixtures.createIndependentMaster(foreignEmail);
        String foreignToken = fixtures.tokenFor(foreignEmail);

        ResponseEntity<String> resp = patch(foreignToken, visit.id(), "decline", "{}");

        assertThat(resp.getStatusCode())
                .as("a provider with no authority over the visit's master must be forbidden")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertVisitStatus(visit.id(), "CONFIRMED");
        assertAllItemsStatus(visit.id(), "CONFIRMED", 2);
        assertThat(statusChangedCount(visit.id())).isEqualTo(0L);
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
        return new Visit(UUID.fromString(data.path("id").asText()), clientToken, masterToken);
    }

    private ResponseEntity<String> patch(String token, UUID appointmentId, String action, String body) {
        HttpHeaders headers = fixtures.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> entity = body == null ? new HttpEntity<>(headers) : new HttpEntity<>(body, headers);
        return restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/" + action, HttpMethod.PATCH, entity, String.class);
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
