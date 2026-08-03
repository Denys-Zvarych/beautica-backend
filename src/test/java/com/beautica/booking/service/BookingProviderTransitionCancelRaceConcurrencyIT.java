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
 * Regression test for G5 — the asymmetric half of the G4 defect class that survived cycle-7's
 * widening. Races each of the three standalone-booking PROVIDER transitions ({@code
 * BookingService#declineBookingCore}, {@code #completeBooking}, {@code #notCompleteBooking})
 * against a client-initiated {@code PATCH /bookings/{id}/cancel} of the SAME standalone booking.
 *
 * <p><b>Why this is the reverse direction of {@link BookingCancelRescheduleConcurrencyIT}, not a
 * duplicate.</b> G4 made {@code cancelBooking}'s own freshness re-check unconditional, which
 * protects it when it is the SECOND writer in a race (its sibling test above proves this for a
 * concurrent reschedule). Nothing protected the reverse: before this fix, a provider transition
 * that loaded the row while still CONFIRMED could commit its own terminal status UNCONDITIONALLY,
 * even after a concurrent client cancel had already committed CANCELLED first — silently
 * overwriting the client's CANCELLED record and discarding {@code clientCancellationNote}. A
 * provider could trigger this deliberately by firing decline/complete/not-complete the instant a
 * cancellation looked imminent, forcing a DECLINED/COMPLETED/NOT_COMPLETED outcome over the
 * client's own cancellation.
 *
 * <p><b>Deterministic rendezvous, roles reversed from the sibling test.</b> Exactly like {@link
 * BookingCancelRescheduleConcurrencyIT}, there is no lock on this path to hang a two-arrivals
 * {@code CyclicBarrier} off, so this reuses the same one-sided-gate technique via {@link
 * BookingService#isStillConfirmed} — but here the PROVIDER thread's call is deliberately made the
 * FIRST (paused) one, and the client cancel — driven synchronously on the main thread — is the
 * SECOND (which proceeds immediately, since the provider thread is paused before it has mutated
 * anything, so the booking is genuinely still CONFIRMED when cancel's own recheck runs). Once
 * cancel has committed CANCELLED, the paused provider thread is released; its OWN recheck now
 * legitimately observes the booking is no longer CONFIRMED and must abort with 409 rather than
 * completing its mutation — that 409 is the assertion this whole class exists to make.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Standalone booking — provider decline/complete/not-complete racing a client cancel of "
        + "the SAME booking (G5 — the reverse direction of G4's freshness re-check)")
class BookingProviderTransitionCancelRaceConcurrencyIT extends AbstractIntegrationTest {

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
    @DisplayName("G5: a provider decline whose load raced a client cancel that commits FIRST must "
            + "abort 409, not overwrite the CANCELLED row — clientCancellationNote survives")
    void should_return409AndPreserveCancellation_when_declineRacesClientCancelOfSameStandaloneBooking() throws Exception {
        RaceFixture race = setUpRace("decline");

        ResponseEntity<String> providerResponse = runRace(race,
                BOOKINGS_URL + "/" + race.bookingId() + "/decline", HttpMethod.PATCH,
                "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\"}");

        assertProviderTransitionRejected(providerResponse);
        assertCancellationSurvived(race.bookingId());
        assertThat(providerCommentInDb(race.bookingId()))
                .as("the decline's own providerComment must never have been written — the save "
                        + "aborted before any column was persisted")
                .isNull();
    }

    @Test
    @DisplayName("G5: a provider complete whose load raced a client cancel that commits FIRST must "
            + "abort 409, not overwrite the CANCELLED row — clientCancellationNote survives")
    void should_return409AndPreserveCancellation_when_completeRacesClientCancelOfSameStandaloneBooking() throws Exception {
        RaceFixture race = setUpRace("complete");
        // completeBooking requires the booking to have already STARTED (assertElapsedForComplete),
        // while the client cancel requires it to NOT have fully ENDED yet (assertNotElapsedForClient
        // compares endsAt) — so the booking must be pushed into an ONGOING window, satisfying both
        // guards simultaneously, rather than left at its create-time future slot.
        markOngoing(race.bookingId());

        ResponseEntity<String> providerResponse = runRace(race,
                BOOKINGS_URL + "/" + race.bookingId() + "/complete", HttpMethod.PATCH, null);

        assertProviderTransitionRejected(providerResponse);
        assertCancellationSurvived(race.bookingId());
    }

    @Test
    @DisplayName("G5: a provider not-complete whose load raced a client cancel that commits FIRST "
            + "must abort 409, not overwrite the CANCELLED row — clientCancellationNote survives")
    void should_return409AndPreserveCancellation_when_notCompleteRacesClientCancelOfSameStandaloneBooking() throws Exception {
        RaceFixture race = setUpRace("not-complete");

        ResponseEntity<String> providerResponse = runRace(race,
                BOOKINGS_URL + "/" + race.bookingId() + "/not-complete", HttpMethod.PATCH,
                "{\"cancellationReason\":\"CLIENT_NO_SHOW\"}");

        assertProviderTransitionRejected(providerResponse);
        assertCancellationSurvived(race.bookingId());
        assertThat(providerCommentInDb(race.bookingId()))
                .as("the not-complete's own providerComment must never have been written — the save "
                        + "aborted before any column was persisted")
                .isNull();
    }

    // ── shared race harness ──────────────────────────────────────────────────────────────────────

    private record RaceFixture(UUID bookingId, String providerToken, String clientToken) {}

    private RaceFixture setUpRace(String suffix) throws Exception {
        String masterEmail = "provider-race-" + suffix + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);
        String clientEmail = "provider-race-" + suffix + "-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);
        // The independent master IS the provider actor — hasProviderAuthorityOverBooking admits the
        // master's own user for an INDEPENDENT_MASTER booking (no salon/admin indirection needed).
        String providerToken = fixtures.tokenFor(masterEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = createStandaloneBooking(clientToken, masterId, masterServiceId, startsAt);
        return new RaceFixture(bookingId, providerToken, clientToken);
    }

    /**
     * Drives the one-sided gate: the PROVIDER thread's call to {@code isStillConfirmed} (started
     * async, so it always arrives first) pauses there; the client cancel, driven synchronously on
     * the main thread right after, is the second call and proceeds immediately, committing
     * CANCELLED. Only once that commit is in hand is the provider thread released to re-evaluate
     * its own recheck against the now-CANCELLED row and abort with 409.
     */
    private ResponseEntity<String> runRace(
            RaceFixture race, String providerPath, HttpMethod providerMethod, String providerBody)
            throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);
        CountDownLatch providerReachedGate = new CountDownLatch(1);
        CountDownLatch cancelCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            if (callCount.incrementAndGet() == 1) {
                providerReachedGate.countDown();
                boolean released = cancelCommitted.await(10, TimeUnit.SECONDS);
                if (!released) {
                    throw new IllegalStateException("cancel never committed — test setup is broken");
                }
            }
            return invocation.callRealMethod();
        }).when(bookingService).isStillConfirmed(eq(race.bookingId()));

        CountDownLatch providerDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> providerResponse = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                HttpHeaders headers = fixtures.bearerHeaders(race.providerToken());
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<String> entity = new HttpEntity<>(providerBody, headers);
                providerResponse.set(restTemplate.exchange(providerPath, providerMethod, entity, String.class));
            } finally {
                providerDone.countDown();
            }
        });

        boolean reachedGate = providerReachedGate.await(10, TimeUnit.SECONDS);
        assertThat(reachedGate)
                .as("the provider thread must reach its freshness re-check within 10s")
                .isTrue();

        // Drive the client cancel to full completion on the main thread WHILE the provider thread
        // is paused, holding its own stale (pre-cancel) in-memory Booking entity but no lock of any
        // kind on this row.
        HttpHeaders cancelHeaders = fixtures.bearerHeaders(race.clientToken());
        cancelHeaders.setContentType(MediaType.APPLICATION_JSON);
        String cancelBody = "{\"cancellationReason\":\"CLIENT_CANCELLED\",\"comment\":\"race note\"}";
        ResponseEntity<String> cancelResponse = restTemplate.exchange(
                BOOKINGS_URL + "/" + race.bookingId() + "/cancel", HttpMethod.PATCH,
                new HttpEntity<>(cancelBody, cancelHeaders), String.class);
        assertThat(cancelResponse.getStatusCode())
                .as("the cancel must succeed — nothing has raced it yet at this point — body: %s",
                        cancelResponse.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        cancelCommitted.countDown();
        boolean providerFinished = providerDone.await(10, TimeUnit.SECONDS);
        assertThat(providerFinished).as("the provider thread must finish within 10s once released").isTrue();
        return providerResponse.get();
    }

    private void assertProviderTransitionRejected(ResponseEntity<String> providerResponse) {
        assertThat(providerResponse.getStatusCode())
                .as("G5: the provider transition's own freshness re-check must reject a booking the "
                        + "client cancel already moved off CONFIRMED — body: %s", providerResponse.getBody())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    private void assertCancellationSurvived(UUID bookingId) {
        assertThat(dbStatus(bookingId))
                .as("the client's CANCELLED status must survive the losing provider transition")
                .isEqualTo("CANCELLED");
        assertThat(dbClientCancellationNote(bookingId))
                .as("the client's cancellation note must survive — never silently discarded by the "
                        + "losing provider transition's own (aborted) save")
                .isEqualTo("race note");
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

    /**
     * Pushes a CONFIRMED booking's window into an ONGOING state — {@code starts_at} in the recent
     * past, {@code ends_at} still in the future — the only window that satisfies BOTH {@code
     * completeBooking}'s {@code assertElapsedForComplete} (requires {@code now >= startsAt}) AND
     * the racing client cancel's {@code assertNotElapsedForClient} (requires {@code now < endsAt})
     * simultaneously. Mirrors the {@code markElapsed} precedent in {@code
     * BookingAppointmentChildTransitionGuardIT}, but stops short of pushing {@code ends_at} into
     * the past too, since THIS race needs the client cancel to still be legal.
     */
    private void markOngoing(UUID bookingId) {
        jdbcTemplate.update(
                "UPDATE bookings SET starts_at = NOW() - INTERVAL '5 minutes', "
                        + "ends_at = NOW() + INTERVAL '55 minutes' WHERE id = ?",
                bookingId);
    }

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String dbClientCancellationNote(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT client_cancellation_note FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String providerCommentInDb(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT provider_comment FROM bookings WHERE id = ?", String.class, bookingId);
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
