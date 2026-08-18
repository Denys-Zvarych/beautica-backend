package com.beautica.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
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
 * Deterministic reproduction of the pre-existing (NOT introduced by track 27.x, but newly
 * REACHABLE by it) lost-update race in
 * {@link AppointmentTransitionService#lockAppointmentHeaderBeforeClientItemCancel}, documented in
 * that method's own Javadoc and flagged by both {@code backend-perf} (HIGH — the per-recompute
 * {@code findByAppointmentIdWithGraph} 5-JOIN-FETCH read, since removed) and {@code backend-security}
 * (MEDIUM — no lock/{@code @Version} on the {@code appointments} header, so two concurrent
 * leg-cancels can each observe the OTHER'S sibling as still {@code CONFIRMED} and strand the header
 * at {@code CONFIRMED} forever, even though every leg is actually terminal).
 *
 * <p>Before track 27.x this window was unreachable via HTTP: a client could never cancel two legs
 * of the same visit concurrently because a single-booking cancel always refused an appointment
 * child outright. The widening in {@code BookingService#cancelBooking} makes it reachable for the
 * first time — hence this dedicated regression test, separate from
 * {@link com.beautica.booking.BookingAppointmentChildTransitionGuardIT} (which stays free of the
 * {@code @SpyBean} machinery below so its whole class does not need its own dirtied Spring context).
 *
 * <p><b>Why a bare {@code CountDownLatch}-gated HTTP race is not enough.</b> {@code READ COMMITTED}
 * means each transaction only ever sees the OTHER transaction's data once it has committed — the
 * bug requires both transactions' header-recompute reads to land BEFORE either commits. Firing two
 * HTTP requests "at the same time" from virtual threads (the pattern
 * {@link com.beautica.booking.GuestCancelConcurrencyIT} uses for a genuinely atomic, DB-serialized
 * race) does not guarantee that timing here: on a fast enough machine one request could plausibly
 * complete and commit before the second even starts its read, making the test pass for the wrong
 * reason (no race actually occurred) — exactly the "hoping threads interleave a certain way"
 * flakiness this suite avoids elsewhere (no {@code Thread.sleep}, no assumed scheduler behaviour).
 *
 * <p><b>How this test forces the race deterministically instead.</b> A {@code @SpyBean} on
 * {@link AppointmentTransitionService} intercepts
 * {@code lockAppointmentHeaderBeforeClientItemCancel} — the exact method that acquires the header's
 * {@code SELECT ... FOR UPDATE} lock, the actual serialization point — and makes BOTH racing calls
 * rendezvous on a {@link CyclicBarrier} of size 2 before either is allowed to proceed to the real
 * (still fully transactional — the spy delegates to the real, already-{@code @Transactional}-proxied
 * bean) implementation. Neither call's transaction can possibly have committed yet when the barrier
 * releases both, so both attempt the SAME row lock at effectively the same instant — the exact
 * precondition for the bug, every single run.
 *
 * <p><b>Fixed.</b> The per-item header recompute is now a two-phase
 * lock-then-collapse split — {@link AppointmentTransitionService#lockAppointmentHeaderBeforeClientItemCancel}
 * (phase 1, spied on here) locks the header via {@code SELECT ... FOR UPDATE}, and
 * {@link AppointmentTransitionService#collapseAppointmentHeaderAfterClientItemCancel} (phase 2, called
 * by {@code BookingService#cancelBooking} AFTER it persists this child's own CANCELLED status)
 * delegates to {@code AppointmentRepository#collapseHeaderIfNoConfirmedSiblingsRemain} — a single
 * conditional {@code UPDATE ... WHERE status = 'CONFIRMED' AND NOT EXISTS (SELECT 1 FROM bookings
 * WHERE appointment_id = ? AND status = 'CONFIRMED')} that folds the "any sibling still CONFIRMED"
 * check into the same statement that flips the header, so Postgres's row-level locking during the
 * {@code UPDATE} makes the check-and-flip atomic. The split moved WHEN the lock is acquired (before
 * this child's own row is written, not after — cycle-2 audit finding 1, the canonical
 * appointments-before-bookings lock order) without changing the underlying serialization mechanism
 * this test exercises. Re-enabled as this fix's acceptance test; moved into this package (from
 * {@code com.beautica.booking}) specifically so the now-package-private
 * {@code lockAppointmentHeaderBeforeClientItemCancel} stays spy-able without being forced
 * {@code public} purely for test reachability (cycle-2 audit finding 5) — it now shares the
 * cross-package-{@code public} {@code com.beautica.booking.BookingTestFixtures} instead.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Concurrent last-two-legs client cancel — header-recompute lost-update race (fixed via atomic conditional UPDATE)")
class AppointmentClientLegCancelConcurrencyIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String APPOINTMENTS_URL = "/api/v1/appointments";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @SpyBean
    private AppointmentTransitionService appointmentTransitionService;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    @Test
    @DisplayName("cancelling the LAST TWO legs of a two-service visit truly concurrently must still "
            + "collapse the header to CANCELLED — today both racing recomputes observe the OTHER leg as "
            + "still CONFIRMED and strand the header at CONFIRMED forever")
    void should_collapseHeaderToCancelled_when_bothLastLegsCancelledConcurrently() throws Exception {
        Visit visit = createTwoServiceVisitLegs();
        UUID appointmentId = visit.id();
        UUID child0 = childAt(appointmentId, 0);
        UUID child1 = childAt(appointmentId, 1);
        String clientToken = visit.clientToken();

        CyclicBarrier bothRacersAtTheStaleRead = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothRacersAtTheStaleRead.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(appointmentTransitionService)
                .lockAppointmentHeaderBeforeClientItemCancel(eq(appointmentId));

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<ResponseEntity<String>> resp0 = new AtomicReference<>();
        AtomicReference<ResponseEntity<String>> resp1 = new AtomicReference<>();

        startRacer(go, done, resp0, clientToken, child0);
        startRacer(go, done, resp1, clientToken, child1);
        go.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);

        assertThat(finished).as("both racers must finish within 30s").isTrue();
        assertThat(resp0.get().getStatusCode())
                .as("leg 0's cancel must succeed — body: %s", resp0.get().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(resp1.get().getStatusCode())
                .as("leg 1's cancel must succeed — body: %s", resp1.get().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(child0)).isEqualTo("CANCELLED");
        assertThat(dbStatus(child1)).isEqualTo("CANCELLED");
        assertThat(appointmentStatus(appointmentId))
                .as("every leg is terminal — the header must collapse to CANCELLED, not strand at "
                        + "CONFIRMED (the lost-update race this test forces deterministically)")
                .isEqualTo("CANCELLED");
    }

    private void startRacer(CountDownLatch go, CountDownLatch done, AtomicReference<ResponseEntity<String>> out,
            String clientToken, UUID childId) {
        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                HttpHeaders headers = fixtures.bearerHeaders(clientToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> entity = new HttpEntity<>(
                        "{\"cancellationReason\":\"CLIENT_CANCELLED\"}", headers);
                out.set(restTemplate.exchange(
                        BOOKINGS_URL + "/" + childId + "/cancel", HttpMethod.PATCH, entity, String.class));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    /** A created two-service CONFIRMED visit plus the owning client's token. */
    private record Visit(UUID id, String clientToken) {}

    /** Creates a fresh INDEPENDENT_MASTER + CLIENT and a two-service CONFIRMED visit. */
    private Visit createTwoServiceVisitLegs() throws Exception {
        String masterEmail = "leg-race-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "leg-race-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);

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
        return new Visit(UUID.fromString(data.path("id").asText()), clientToken);
    }

    private UUID childAt(UUID appointmentId, int offset) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at OFFSET ? LIMIT 1",
                UUID.class, appointmentId, offset);
    }

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String appointmentStatus(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }

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
