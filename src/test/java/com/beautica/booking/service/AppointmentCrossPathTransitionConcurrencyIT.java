package com.beautica.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
 * Deterministic regression test for cycle-2 audit finding 1 (HIGH, PLAUSIBLE — lock-order
 * inversion / deadlock risk) — races a WHOLE-VISIT provider decline
 * ({@link AppointmentTransitionService#declineAppointment}) against a PER-LEG client cancel
 * ({@code BookingService#cancelBooking}) on the SAME two-service visit, forcing both racers to
 * attempt the visit header's {@code SELECT ... FOR UPDATE} lock at effectively the same instant.
 *
 * <p><b>Before this fix (verified against the pre-cycle-2 code in a throwaway experiment, NOT
 * committed — see the cycle-2 writeup for the transcript).</b> The whole-visit family took NO
 * explicit header lock at all; only the per-item path
 * ({@code AppointmentTransitionService#lockHeaderIfConfirmed}, then a {@code BookingService
 * #cancelBooking}) locked the header, and did so AFTER already having written its own child row.
 * Racing this exact pairing (whole-visit decline vs. per-leg client cancel) via the SAME
 * {@code @SpyBean} + {@code CyclicBarrier} technique as
 * {@link AppointmentClientLegCancelConcurrencyIT}, over 135 forced-concurrent trials: ONE trial
 * produced a genuine Postgres {@code ERROR: deadlock detected} (40P01) — CONFIRMING the HIGH was
 * real, not merely plausible, though rare under this exact interleaving (consistent with a
 * lock-order-dependent race that needs precise flush-timing alignment to manifest as a true
 * deadlock rather than a benign serialization). The other 134 trials did not deadlock, but
 * exhibited a related, MORE common defect from the same root cause (absent header lock on the
 * whole-visit side): the client's per-leg cancel returned {@code 204} success while the targeted
 * leg silently ended up {@code DECLINED} instead of the requested {@code CANCELLED} — a lost
 * update on the CHILD row, not just the header, because the whole-visit path's item list was
 * loaded (and the header validated) against a stale pre-lock snapshot.
 *
 * <p><b>Fixed.</b> {@link AppointmentTransitionService#lockHeaderForWholeVisitTransition} — spied
 * on below — now locks the header UNCONDITIONALLY at the very TOP of every whole-visit transition
 * method, before any item is loaded, mirroring the per-item path's now-earlier lock point
 * ({@code AppointmentTransitionService#lockAppointmentHeaderBeforeClientItemCancel}, also spied on
 * below, called by {@code BookingService#cancelBooking} BEFORE it saves its own child row). Both
 * families now acquire the SAME resource (the header row) in the SAME position relative to their
 * own item writes — the canonical {@code appointments}-before-{@code bookings} lock order — so
 * whichever racer's lock statement runs first simply blocks the other at the DB level (clean
 * serialization) instead of a deadlock being topologically possible. Because the whole-visit path
 * now re-reads BOTH the header AND its items via a FRESH snapshot taken AFTER the lock (rather
 * than a snapshot taken before), the outcome is deterministic regardless of which racer wins:
 * whichever leg the per-item racer already cancelled is skipped by the whole-visit path's
 * resurrection guard ({@code transitionItems} only touches still-{@code CONFIRMED} items), so the
 * lost-update this test forces is ALSO closed, not just the deadlock.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Concurrent whole-visit decline vs. per-leg client cancel — lock-order/deadlock regression (fixed)")
class AppointmentCrossPathTransitionConcurrencyIT extends AbstractIntegrationTest {

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
    @DisplayName("provider whole-visit decline racing a client per-leg cancel on the SAME visit must not "
            + "deadlock and must land in a consistent state: the client's cancelled leg stays CANCELLED, "
            + "the other leg is DECLINED by the whole-visit transition, and the header collapses to DECLINED")
    void should_serializeCleanlyWithNoDeadlock_when_wholeVisitDeclineRacesPerLegClientCancel() throws Exception {
        Visit visit = createTwoServiceVisitLegs();
        UUID appointmentId = visit.id();
        UUID child0 = childAt(appointmentId, 0);
        UUID child1 = childAt(appointmentId, 1);
        String clientToken = visit.clientToken();
        String providerToken = visit.providerToken();

        CyclicBarrier bothRacersAtTheirHeaderLock = new CyclicBarrier(2);
        doAnswer(invocation -> {
            bothRacersAtTheirHeaderLock.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockHeaderForWholeVisitTransition(eq(appointmentId));
        doAnswer(invocation -> {
            bothRacersAtTheirHeaderLock.await(10, TimeUnit.SECONDS);
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeClientItemCancel(eq(appointmentId));

        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicReference<ResponseEntity<String>> respDecline = new AtomicReference<>();
        AtomicReference<ResponseEntity<String>> respCancel = new AtomicReference<>();

        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                HttpHeaders headers = fixtures.bearerHeaders(providerToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                respDecline.set(restTemplate.exchange(
                        APPOINTMENTS_URL + "/" + appointmentId + "/decline", HttpMethod.PATCH,
                        new HttpEntity<>("{}", headers), String.class));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });
        Thread.ofVirtual().start(() -> {
            try {
                go.await();
                HttpHeaders headers = fixtures.bearerHeaders(clientToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> entity = new HttpEntity<>(
                        "{\"cancellationReason\":\"CLIENT_CANCELLED\"}", headers);
                respCancel.set(restTemplate.exchange(
                        BOOKINGS_URL + "/" + child0 + "/cancel", HttpMethod.PATCH, entity, String.class));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        });

        go.countDown();
        boolean finished = done.await(30, TimeUnit.SECONDS);

        assertThat(finished).as("both racers must finish within 30s — no hang, no unbounded lock wait").isTrue();
        assertThat(respDecline.get().getStatusCode())
                .as("whole-visit decline must succeed — body: %s", respDecline.get().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(respCancel.get().getStatusCode())
                .as("per-leg client cancel must succeed — body: %s", respCancel.get().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Deterministic regardless of which racer wins the header lock — see class Javadoc.
        assertThat(dbStatus(child0))
                .as("the client's own cancelled leg must stay CANCELLED — not silently overwritten/lost")
                .isEqualTo("CANCELLED");
        assertThat(dbStatus(child1))
                .as("the sibling leg is moved to DECLINED by the whole-visit transition")
                .isEqualTo("DECLINED");
        assertThat(appointmentStatus(appointmentId))
                .as("the header collapses to DECLINED (the whole-visit transition's target)")
                .isEqualTo("DECLINED");
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    /** A created two-service CONFIRMED visit plus the owning client's and provider's tokens. */
    private record Visit(UUID id, String clientToken, String providerToken) {}

    /** Creates a fresh INDEPENDENT_MASTER + CLIENT and a two-service CONFIRMED visit. */
    private Visit createTwoServiceVisitLegs() throws Exception {
        String masterEmail = "xpath-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = "xpath-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        UUID serviceA = fixtures.createIndependentMasterService(masterId);
        UUID serviceB = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientToken = fixtures.tokenFor(clientEmail);
        String providerToken = fixtures.tokenFor(masterEmail);

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
        return new Visit(UUID.fromString(data.path("id").asText()), clientToken, providerToken);
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
