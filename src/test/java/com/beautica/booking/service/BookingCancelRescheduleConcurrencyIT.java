package com.beautica.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Regression test for G1 (root cause) and G4 (standalone widening), cycle-7 audit 2026-08-03 —
 * races a client-initiated {@code PATCH /bookings/{id}/cancel} against a client-initiated
 * {@code PATCH /bookings/{id}/reschedule} of the SAME STANDALONE (non-appointment) booking.
 *
 * <p><b>Why this pairing is uniquely interesting.</b> Unlike every appointment-child pairing
 * {@code AppointmentCrossPathTransitionConcurrencyIT} covers, a standalone booking has NO header to
 * lock and neither {@link BookingService#cancelBooking(UUID, UUID, com.beautica.booking.dto.CancelBookingRequest)}
 * nor {@link BookingService#rescheduleBooking} acquires ANY row-level lock keyed on the booking
 * itself (the client/master advisory locks in {@code rescheduleBooking} protect SLOT conflicts, not
 * this row). Before {@code Booking} carried {@code @DynamicUpdate} (G1), the entity-staleness defect
 * class was at its WORST here: a cancel's blind, full-column save could silently revert a
 * concurrent reschedule's committed new time back to the cancel's own stale, pre-race snapshot (or
 * vice versa) — with no lock and no recheck to even narrow the window. G1 (the {@code @DynamicUpdate}
 * annotation) and G4 (widening the {@code existsConfirmedById} freshness re-check to run
 * unconditionally, not just for appointment children) together close it.
 *
 * <p><b>Deterministic rendezvous without a lock to hang it off.</b> Every OTHER concurrency IT in
 * this package spies on a header-LOCK call to force two racers to arrive at the same instant. This
 * pairing has no such call, so it uses the purpose-built seam instead:
 * {@link BookingService#isStillConfirmed} (G2/G4's spy-able wrapper around
 * {@code BookingRepository#existsConfirmedById} — see that method's Javadoc for why it exists).
 * Rather than a two-arrivals {@code CyclicBarrier} (both racers hit the SAME statement
 * simultaneously), this test uses a ONE-SIDED gate: the cancel thread's FIRST call to
 * {@code isStillConfirmed} (tracked via an {@code AtomicInteger} call counter, since BOTH racers
 * call the SAME method with the SAME argument) pauses there, signalling the main thread via a
 * latch. The main thread then drives the reschedule call to completion SYNCHRONOUSLY — its own
 * call to {@code isStillConfirmed} is the SECOND invocation, which proceeds immediately (the
 * pre-race booking is still CONFIRMED, so the recheck legitimately passes) — and only once the
 * reschedule's HTTP response is in hand does it release the cancel thread's pause. This forces the
 * exact interleaving needed to prove the {@code @DynamicUpdate} property: the cancel's own
 * in-memory {@code Booking} entity was loaded (with the ORIGINAL start time) BEFORE the reschedule
 * committed, yet cancel's own save — which runs AFTER the reschedule has already committed a NEW
 * time — must not revert it.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Standalone booking — client cancel racing a client reschedule of the SAME booking "
        + "(G1 @DynamicUpdate + G4 widened freshness re-check)")
class BookingCancelRescheduleConcurrencyIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @SpyBean
    private BookingService bookingService;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    @Test
    @DisplayName("G1: a reschedule that commits its NEW time BEFORE a concurrent cancel's own save "
            + "must have that new time SURVIVE the cancel — status ends CANCELLED AND the rescheduled "
            + "time is preserved, proving the cancel's save no longer writes the untouched time columns")
    void should_preserveBothEffects_when_cancelRacesRescheduleOfSameStandaloneBooking() throws Exception {
        String masterEmail = "standalone-race-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientEmail = "standalone-race-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        ZonedDateTime originalStart = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = createStandaloneBooking(clientToken, masterId, masterServiceId, originalStart);
        OffsetDateTime newStartsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();

        // One-sided gate (see class Javadoc): the FIRST call to isStillConfirmed (the cancel
        // thread's) pauses; the SECOND (the reschedule's, driven synchronously below) proceeds
        // immediately.
        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch cancelReachedGate = new CountDownLatch(1);
        CountDownLatch rescheduleCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (callCount.incrementAndGet() == 1) {
                cancelReachedGate.countDown();
                boolean released = rescheduleCommitted.await(10, TimeUnit.SECONDS);
                if (!released) {
                    throw new IllegalStateException("reschedule never committed — test setup is broken");
                }
            }
            return invocation.callRealMethod();
        }).when(bookingService).isStillConfirmed(eq(bookingId));

        CountDownLatch cancelDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> respCancel = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                HttpHeaders headers = fixtures.bearerHeaders(clientToken);
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> entity = new HttpEntity<>(
                        "{\"cancellationReason\":\"CLIENT_CANCELLED\"}", headers);
                respCancel.set(restTemplate.exchange(
                        BOOKINGS_URL + "/" + bookingId + "/cancel", HttpMethod.PATCH, entity, String.class));
            } finally {
                cancelDone.countDown();
            }
        });

        boolean reachedGate = cancelReachedGate.await(10, TimeUnit.SECONDS);
        assertThat(reachedGate)
                .as("the cancel thread must reach its freshness re-check within 10s")
                .isTrue();

        // Drive the reschedule to full completion on the main thread WHILE the cancel thread is
        // paused, holding its OWN stale (original-time) in-memory Booking entity but no lock of any
        // kind on this row.
        HttpHeaders rescheduleHeaders = fixtures.bearerHeaders(clientToken);
        rescheduleHeaders.setContentType(MediaType.APPLICATION_JSON);
        String rescheduleBody = "{\"newStartsAt\":\"" + newStartsAt + "\"}";
        ResponseEntity<String> respReschedule = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(rescheduleBody, rescheduleHeaders), String.class);
        assertThat(respReschedule.getStatusCode())
                .as("the reschedule must succeed — nothing has raced it yet at this point — body: %s",
                        respReschedule.getBody())
                .isEqualTo(HttpStatus.OK);

        rescheduleCommitted.countDown();
        boolean cancelFinished = cancelDone.await(10, TimeUnit.SECONDS);
        assertThat(cancelFinished).as("the cancel thread must finish within 10s once released").isTrue();
        assertThat(respCancel.get().getStatusCode())
                .as("G4: the cancel's own freshness re-check must still see the booking as CONFIRMED "
                        + "(the reschedule never changes status) — body: %s", respCancel.get().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        // The central G1 assertion: BOTH effects survive. Pre-G1, cancel's blind, full-column save
        // (booking.setStatus(CANCELLED); bookingRepository.save(booking)) would have written its
        // OWN stale startsAt/endsAt (loaded before the reschedule committed) back over the fresh
        // row, silently reverting the reschedule. With @DynamicUpdate, that save's dirty-column set
        // is status/cancellationReason/clientCancellationNote ONLY.
        assertThat(dbStatus(bookingId))
                .as("the cancel's effect on STATUS must survive")
                .isEqualTo("CANCELLED");
        assertThat(dbStartsAt(bookingId).toInstant())
                .as("G1: the reschedule's committed NEW time must survive the LATER cancel's "
                        + "@DynamicUpdate save — this assertion was impossible to make truthfully "
                        + "before G1")
                .isEqualTo(newStartsAt.toInstant());
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────────────────────

    private UUID createStandaloneBooking(
            String clientToken, UUID masterId, UUID masterServiceId, ZonedDateTime startsAt) throws Exception {
        var request = new CreateBookingRequest(masterId, masterServiceId, startsAt, null, null);
        HttpHeaders headers = fixtures.bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST, new HttpEntity<>(request, headers), String.class);
        assertThat(resp.getStatusCode())
                .as("standalone booking setup must succeed — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<BookingResponse>>() {});
        return body.data().id();
    }

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private OffsetDateTime dbStartsAt(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT starts_at FROM bookings WHERE id = ?", OffsetDateTime.class, bookingId);
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
