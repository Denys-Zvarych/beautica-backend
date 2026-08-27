package com.beautica.booking.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.beautica.booking.dto.AppointmentItemRescheduleRequest;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.booking.dto.RescheduleBookingRequest;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Race-proven negative half of {@code com.beautica.booking.ClientConflictOverrideIT}'s RESCHEDULE
 * coverage — see that class's own Javadoc "The RESCHEDULE-path negative ('master busy') race tests
 * live in a SEPARATE class" paragraph for why these two tests cannot live there: both need to pause a
 * reschedule request at a PACKAGE-PRIVATE seam ({@link BookingService#isStillConfirmed} /
 * {@link AppointmentTransitionService#lockAppointmentHeaderBeforeItemReschedule}), which requires
 * this test class to sit in {@code com.beautica.booking.service} — the same constraint that already
 * placed {@link BookingCancelRescheduleConcurrencyIT} and
 * {@link AppointmentCrossPathTransitionConcurrencyIT} here.
 *
 * <p><b>What these tests guard.</b> {@code RescheduleBookingRequest#allowClientOverlap()} /
 * {@code AppointmentItemRescheduleRequest#allowClientOverlap()} (product decision 2026-08-22, widened
 * 2026-08-26) waive ONLY the CLIENT's own-calendar conflict check — never the master-scoped
 * {@code BookingRepository#existsOverlapExcluding} call a few lines below it in
 * {@link BookingService#rescheduleBooking} (:1893) / {@link AppointmentTransitionService
 * #rescheduleAppointmentItem} (:1067). A mutation widening either `if` to also test
 * {@code req.allowClientOverlap()} — so the master-scoped call is skipped whenever the CLIENT opted
 * in — would let a client who set {@code allowClientOverlap=true} for their OWN overlap silently walk
 * past a DIFFERENT client's already-CONFIRMED claim on the same master slot too. That mutation was
 * empirically confirmed to leave the entire pre-existing {@code com.beautica.booking.*} package
 * green — nothing in it ever exercises a reschedule racing a same-master conflict into existence.
 *
 * <p><b>Why a race, not a pre-seeded conflict.</b> {@code assertStartsOnAvailableSlot} /
 * {@code assertItemStartsOnAvailableSlot} run BEFORE the per-master advisory lock and BEFORE
 * {@code existsOverlapExcluding}, and the slot list they consult already has the master's CONFIRMED
 * bookings subtracted. A conflicting booking committed BEFORE the reschedule request starts is
 * therefore rejected at THAT earlier guard, with the SAME generic "Slot not available" 409 —
 * {@code existsOverlapExcluding} is never reached, so a naive pre-seeded-conflict test cannot exercise
 * (let alone mutation-prove) the line under test here. Racing the occupying booking to commit strictly
 * AFTER the reschedule's own (unlocked) slot read but strictly BEFORE its (locked)
 * {@code existsOverlapExcluding} call is the only interleaving that reaches it.
 *
 * <p><b>Why the HTTP status alone is not the load-bearing assertion.</b> Both reschedule methods wrap
 * their {@code saveAndFlush} in {@code catch (DataIntegrityViolationException e)}, converting the DB's
 * own {@code no_overlapping_bookings} EXCLUDE constraint into the IDENTICAL 409 "Slot not available"
 * the application-level guard produces. Since {@code existsOverlapExcluding} and the EXCLUDE
 * constraint check the EXACT same predicate against the EXACT same already-committed row at the EXACT
 * same instant (immediately adjacent, same transaction, same lock held), no timing can ever separate
 * "the app guard rejected it" from "the app guard was skipped and the DB backstop caught it instead" —
 * the HTTP response is byte-for-byte identical either way. Each test below therefore asserts
 * {@code verify(bookingRepository).existsOverlapExcluding(...)} as the assertion that actually proves
 * the guard ran rather than being bypassed by the flag; the 409/persistence assertions alongside it
 * document today's (correct, defense-in-depth) behaviour but would NOT go red under the mutation on
 * their own.
 *
 * <p><b>The two CLIENT-initiated positive tests below</b> (backend-qa LOW / backend-perf, cycle audit
 * 2026-08-26) — {@link #should_rescheduleBooking_when_onlyTheClientOverlapsThemselvesAndAllowClientOverlapIsTrue},
 * {@link #should_rescheduleAppointmentItem_when_onlyTheClientOverlapsThemselvesAndAllowClientOverlapIsTrue}
 * — moved here from {@code com.beautica.booking.ClientConflictOverrideIT}, which no longer needs a
 * {@code BookingRepository} spy of its own once they left. This class already spies
 * {@code BookingRepository} + {@code BookingService} + {@code AppointmentTransitionService} for the
 * race tests above, so co-locating the positive half here reuses that same context instead of paying
 * for a second, narrower one — cutting this diff's new distinct {@code @SpyBean} combinations from 2
 * to 1 (that 3-spy combo is itself irreducible: Mockito cannot {@code callRealMethod()} on a Spring
 * Data JPA proxy, so the pause-gates above must sit on the package-private service seams, and the
 * repository spy is the mutation-proof both halves need). Neither positive test races anything or
 * uses the {@code doAnswer} pause gates — they simply reuse the spies already paid for here.
 */
@Import(TestSecurityConfig.class)
@DisplayName("PATCH .../reschedule — master-scoped existsOverlapExcluding survives a same-master race "
        + "even with allowClientOverlap=true (product decision 2026-08-22, widened 2026-08-26)")
class RescheduleMasterOverlapGuardConcurrencyIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String APPOINTMENTS_URL = "/api/v1/appointments";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** Verify-only (never stubbed with fake behaviour) — see class Javadoc for why this call carries
     * the mutation-proof weight neither reschedule test's HTTP status assertion can carry alone. */
    @SpyBean
    private BookingRepository bookingRepository;

    @SpyBean
    private BookingService bookingService;

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
    @DisplayName("RESCHEDULE — the ONE THAT MATTERS for PATCH /bookings/{id}/reschedule: a reschedule "
            + "that read the master's slot as FREE, then found it taken by client A by the time it "
            + "reached its own freshness re-check, must still 409 with allowClientOverlap=true — "
            + "existsOverlapExcluding still RAN")
    void should_stillRejectDoubleBooking_when_reschedulingIntoAnotherClientsMasterSlotEvenWithAllowClientOverlap()
            throws Exception {
        String clientAEmail = "rmog-resched-clienta-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientAEmail, "CLIENT", null);
        String clientAToken = fixtures.tokenFor(clientAEmail);

        String clientBEmail = "rmog-resched-clientb-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientBEmail, "CLIENT", null);
        String clientBToken = fixtures.tokenFor(clientBEmail);

        UUID masterId = fixtures.createIndependentMaster(
                "rmog-resched-master-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        fixtures.addWorkingHoursForEveryDay(masterId);

        ZonedDateTime occupiedSlot = ZonedDateTime.now(TimeZones.KYIV).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        ZonedDateTime freeSlot = occupiedSlot.plusHours(4);

        // Client B's OWN pre-existing standalone booking, at a slot clear of the target — this is
        // the booking B will try to move.
        var bookingOnB = new CreateBookingRequest(masterId, masterServiceId, freeSlot, null, null, false);
        ResponseEntity<String> respB = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(bookingOnB, fixtures.bearerHeaders(clientBToken)), String.class);
        assertThat(respB.getStatusCode())
                .as("setup: client B's booking must succeed — body: %s", respB.getBody())
                .isEqualTo(HttpStatus.CREATED);
        UUID clientBBookingId =
                UUID.fromString(objectMapper.readTree(respB.getBody()).path("data").path("id").asText());

        // One-sided gate: BookingService#isStillConfirmed runs AFTER assertStartsOnAvailableSlot but
        // BEFORE the client/master locks and existsOverlapExcluding — the reschedule thread's own
        // (and ONLY, for this bookingId) call to it pauses here, letting client A's occupying
        // booking commit to full completion first.
        CountDownLatch rescheduleReachedGate = new CountDownLatch(1);
        CountDownLatch occupierCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            rescheduleReachedGate.countDown();
            boolean released = occupierCommitted.await(10, TimeUnit.SECONDS);
            if (!released) {
                throw new IllegalStateException("occupier booking never committed — test setup is broken");
            }
            return invocation.callRealMethod();
        }).when(bookingService).isStillConfirmed(eq(clientBBookingId));

        CountDownLatch rescheduleDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> rescheduleResponse = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                var rescheduleRequest = new RescheduleBookingRequest(occupiedSlot.toOffsetDateTime(), true);
                rescheduleResponse.set(restTemplate.exchange(
                        BOOKINGS_URL + "/" + clientBBookingId + "/reschedule", HttpMethod.PATCH,
                        new HttpEntity<>(rescheduleRequest, fixtures.bearerHeaders(clientBToken)),
                        String.class));
            } finally {
                rescheduleDone.countDown();
            }
        });

        assertThat(rescheduleReachedGate.await(10, TimeUnit.SECONDS))
                .as("the reschedule thread must reach its own freshness re-check within 10s")
                .isTrue();

        var clientARequest = new CreateBookingRequest(masterId, masterServiceId, occupiedSlot, null, null, false);
        ResponseEntity<String> respA = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(clientARequest, fixtures.bearerHeaders(clientAToken)), String.class);
        assertThat(respA.getStatusCode())
                .as("occupier: client A's booking must succeed while the reschedule is paused — body: %s",
                        respA.getBody())
                .isEqualTo(HttpStatus.CREATED);

        occupierCommitted.countDown();
        assertThat(rescheduleDone.await(10, TimeUnit.SECONDS))
                .as("the reschedule thread must finish within 10s once released")
                .isTrue();

        assertThat(rescheduleResponse.get().getStatusCode())
                .as("allowClientOverlap must NEVER let a reschedule steal a slot a DIFFERENT client "
                        + "committed while this request was in flight — body: %s",
                        rescheduleResponse.get().getBody())
                .isEqualTo(HttpStatus.CONFLICT);

        // THE load-bearing assertion (see class Javadoc): the DB's own no_overlapping_bookings
        // EXCLUDE constraint would produce the SAME 409 above even if this application-level guard
        // were skipped entirely, so this verify — not the status code — is what actually proves
        // existsOverlapExcluding ran rather than being bypassed by the flag.
        verify(bookingRepository).existsOverlapExcluding(
                eq(masterId), argThat(odt -> odt != null && odt.isEqual(occupiedSlot.toOffsetDateTime())),
                any(OffsetDateTime.class), eq(clientBBookingId));

        OffsetDateTime persistedStartsAt = jdbcTemplate.queryForObject(
                "SELECT starts_at FROM bookings WHERE id = ?", OffsetDateTime.class, clientBBookingId);
        assertThat(persistedStartsAt.toInstant())
                .as("client B's booking must still be at its ORIGINAL free slot — the steal attempt "
                        + "was never persisted")
                .isEqualTo(freeSlot.toOffsetDateTime().toInstant());

        long confirmedForMaster = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE master_id = ? AND status = 'CONFIRMED'",
                Long.class, masterId);
        assertThat(confirmedForMaster)
                .as("the master must still hold exactly its two CONFIRMED bookings — no double-booking "
                        + "was created")
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("RESCHEDULE ITEM — the ONE THAT MATTERS for PATCH .../services/{bookingId}/reschedule: "
            + "a per-item reschedule that read the master's slot as FREE, then found it taken by "
            + "client A by the time it reached its own header lock, must still 409 with "
            + "allowClientOverlap=true — existsOverlapExcluding still RAN")
    void should_stillRejectDoubleBooking_when_reschedulingAppointmentItemIntoAnotherClientsMasterSlotEvenWithAllowClientOverlap()
            throws Exception {
        String clientAEmail = "rmog-itemresched-clienta-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientAEmail, "CLIENT", null);
        String clientAToken = fixtures.tokenFor(clientAEmail);

        String clientBEmail = "rmog-itemresched-clientb-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientBEmail, "CLIENT", null);
        String clientBToken = fixtures.tokenFor(clientBEmail);

        UUID masterId = fixtures.createIndependentMaster(
                "rmog-itemresched-master-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        fixtures.addWorkingHoursForEveryDay(masterId);

        ZonedDateTime occupiedSlot = ZonedDateTime.now(TimeZones.KYIV).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        ZonedDateTime freeSlot = occupiedSlot.plusHours(4);

        // Client B's own single-service visit on the SAME master, at a slot clear of the target.
        String visitBody = objectMapper.writeValueAsString(Map.of(
                "masterId", masterId.toString(),
                "masterServiceIds", List.of(masterServiceId.toString()),
                "startsAt", freeSlot.toOffsetDateTime().toString()));
        ResponseEntity<String> visitResponse = restTemplate.exchange(
                APPOINTMENTS_URL, HttpMethod.POST,
                new HttpEntity<>(visitBody, fixtures.bearerHeaders(clientBToken)), String.class);
        assertThat(visitResponse.getStatusCode())
                .as("setup: client B's visit must succeed — body: %s", visitResponse.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode visitData = objectMapper.readTree(visitResponse.getBody()).path("data");
        UUID appointmentId = UUID.fromString(visitData.path("id").asText());
        UUID itemBookingId = UUID.fromString(visitData.path("items").get(0).path("bookingId").asText());

        // One-sided gate: AppointmentTransitionService#lockAppointmentHeaderBeforeItemReschedule runs
        // AFTER assertItemStartsOnAvailableSlot but BEFORE the client/master locks and
        // existsOverlapExcluding — the reschedule thread's own (and ONLY, for this appointmentId)
        // call to it pauses here, letting client A's occupying booking commit to full completion
        // first. Package-private — reachable ONLY because this test lives in
        // com.beautica.booking.service (see class Javadoc).
        CountDownLatch rescheduleReachedGate = new CountDownLatch(1);
        CountDownLatch occupierCommitted = new CountDownLatch(1);
        doAnswer(invocation -> {
            rescheduleReachedGate.countDown();
            boolean released = occupierCommitted.await(10, TimeUnit.SECONDS);
            if (!released) {
                throw new IllegalStateException("occupier booking never committed — test setup is broken");
            }
            return invocation.callRealMethod();
        }).when(appointmentTransitionService).lockAppointmentHeaderBeforeItemReschedule(eq(appointmentId));

        CountDownLatch rescheduleDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> rescheduleResponse = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                var rescheduleItemRequest = new AppointmentItemRescheduleRequest(
                        occupiedSlot.toOffsetDateTime(), true);
                rescheduleResponse.set(restTemplate.exchange(
                        APPOINTMENTS_URL + "/" + appointmentId + "/services/" + itemBookingId + "/reschedule",
                        HttpMethod.PATCH,
                        new HttpEntity<>(rescheduleItemRequest, fixtures.bearerHeaders(clientBToken)),
                        String.class));
            } finally {
                rescheduleDone.countDown();
            }
        });

        assertThat(rescheduleReachedGate.await(10, TimeUnit.SECONDS))
                .as("the reschedule thread must reach its own header-lock attempt within 10s")
                .isTrue();

        var clientARequest = new CreateBookingRequest(masterId, masterServiceId, occupiedSlot, null, null, false);
        ResponseEntity<String> respA = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(clientARequest, fixtures.bearerHeaders(clientAToken)), String.class);
        assertThat(respA.getStatusCode())
                .as("occupier: client A's booking must succeed while the per-item reschedule is "
                        + "paused — body: %s", respA.getBody())
                .isEqualTo(HttpStatus.CREATED);

        occupierCommitted.countDown();
        assertThat(rescheduleDone.await(10, TimeUnit.SECONDS))
                .as("the reschedule thread must finish within 10s once released")
                .isTrue();

        assertThat(rescheduleResponse.get().getStatusCode())
                .as("allowClientOverlap must NEVER let a per-item reschedule steal a slot a DIFFERENT "
                        + "client committed while this request was in flight — body: %s",
                        rescheduleResponse.get().getBody())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(bookingRepository).existsOverlapExcluding(
                eq(masterId), argThat(odt -> odt != null && odt.isEqual(occupiedSlot.toOffsetDateTime())),
                any(OffsetDateTime.class), eq(itemBookingId));

        OffsetDateTime persistedStartsAt = jdbcTemplate.queryForObject(
                "SELECT starts_at FROM bookings WHERE id = ?", OffsetDateTime.class, itemBookingId);
        assertThat(persistedStartsAt.toInstant())
                .as("client B's item must still be at its ORIGINAL free slot — the steal attempt was "
                        + "never persisted")
                .isEqualTo(freeSlot.toOffsetDateTime().toInstant());

        long confirmedForMaster = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE master_id = ? AND status = 'CONFIRMED'",
                Long.class, masterId);
        assertThat(confirmedForMaster)
                .as("the master must still hold exactly its two CONFIRMED bookings — no double-booking "
                        + "was created")
                .isEqualTo(2L);
    }

    // ── CLIENT-INITIATED POSITIVE — moved from ClientConflictOverrideIT (backend-qa LOW, 2026-08-26) ─

    @Test
    @DisplayName("RESCHEDULE — 200 when allowClientOverlap=true and the ONLY overlap is the client's "
            + "OWN other booking; the target master's slot is genuinely free, and "
            + "existsOverlapExcluding still RAN (proving it was not skipped)")
    void should_rescheduleBooking_when_onlyTheClientOverlapsThemselvesAndAllowClientOverlapIsTrue() throws Exception {
        String clientEmail = "cco-resched-self-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        UUID masterAId = fixtures.createIndependentMaster(
                "cco-resched-self-masterA-" + System.nanoTime() + "@beautica.test");
        UUID masterAServiceId = fixtures.createIndependentMasterService(masterAId);
        fixtures.addWorkingHoursForEveryDay(masterAId);

        UUID masterBId = fixtures.createIndependentMaster(
                "cco-resched-self-masterB-" + System.nanoTime() + "@beautica.test");
        UUID masterBServiceId = fixtures.createIndependentMasterService(masterBId);
        fixtures.addWorkingHoursForEveryDay(masterBId);

        ZonedDateTime slotT1 = ZonedDateTime.now(TimeZones.KYIV).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        ZonedDateTime slotT2 = slotT1.plusHours(4); // master B's ORIGINAL slot — clear of T1

        var bookingOnA = new CreateBookingRequest(masterAId, masterAServiceId, slotT1, null, null, false);
        ResponseEntity<String> respA = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(bookingOnA, fixtures.bearerHeaders(clientToken)), String.class);
        assertThat(respA.getStatusCode())
                .as("setup: booking on master A must succeed — body: %s", respA.getBody())
                .isEqualTo(HttpStatus.CREATED);

        var bookingOnB = new CreateBookingRequest(masterBId, masterBServiceId, slotT2, null, null, false);
        ResponseEntity<String> respB = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(bookingOnB, fixtures.bearerHeaders(clientToken)), String.class);
        assertThat(respB.getStatusCode())
                .as("setup: booking on master B must succeed — body: %s", respB.getBody())
                .isEqualTo(HttpStatus.CREATED);
        UUID bookingBId = UUID.fromString(objectMapper.readTree(respB.getBody()).path("data").path("id").asText());

        // Move the master-B booking onto T1 — master B's own calendar is empty at T1 (genuinely
        // free), so the ONLY conflict this creates is the CLIENT's own calendar (master A's booking,
        // also at T1). allowClientOverlap=true must let this succeed.
        var rescheduleRequest = new RescheduleBookingRequest(slotT1.toOffsetDateTime(), true);
        ResponseEntity<String> rescheduleResponse = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingBId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(rescheduleRequest, fixtures.bearerHeaders(clientToken)), String.class);

        assertThat(rescheduleResponse.getStatusCode())
                .as("allowClientOverlap=true must let the client reschedule INTO their own overlap "
                        + "when the target master's slot is genuinely free — body: %s",
                        rescheduleResponse.getBody())
                .isEqualTo(HttpStatus.OK);

        // Mutation-proof: existsOverlapExcluding must run UNCONDITIONALLY, even when
        // allowClientOverlap=true — it is guarded only by the SIBLING `if` (assertNoClientConflict-
        // Excluding), never by this one. Widening the `if` around this call to also test
        // req.allowClientOverlap() (the exact mutation this suite guards against) would short-
        // circuit this call entirely and this verify would see ZERO interactions.
        verify(bookingRepository).existsOverlapExcluding(
                eq(masterBId), argThat(odt -> odt != null && odt.isEqual(slotT1.toOffsetDateTime())),
                any(OffsetDateTime.class), eq(bookingBId));

        OffsetDateTime persistedStartsAt = jdbcTemplate.queryForObject(
                "SELECT starts_at FROM bookings WHERE id = ?", OffsetDateTime.class, bookingBId);
        assertThat(persistedStartsAt.toInstant())
                .as("the reschedule must be persisted at the new (overlapping-with-self) time")
                .isEqualTo(slotT1.toOffsetDateTime().toInstant());
    }

    @Test
    @DisplayName("RESCHEDULE ITEM — 200 when allowClientOverlap=true and the ONLY overlap is the "
            + "client's OWN other booking; the item's target master slot is genuinely free, and "
            + "existsOverlapExcluding still RAN")
    void should_rescheduleAppointmentItem_when_onlyTheClientOverlapsThemselvesAndAllowClientOverlapIsTrue()
            throws Exception {
        String clientEmail = "cco-itemresched-self-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        UUID masterAId = fixtures.createIndependentMaster(
                "cco-itemresched-self-masterA-" + System.nanoTime() + "@beautica.test");
        UUID masterAServiceId = fixtures.createIndependentMasterService(masterAId);
        fixtures.addWorkingHoursForEveryDay(masterAId);

        UUID masterBId = fixtures.createIndependentMaster(
                "cco-itemresched-self-masterB-" + System.nanoTime() + "@beautica.test");
        UUID masterBServiceId = fixtures.createIndependentMasterService(masterBId);
        fixtures.addWorkingHoursForEveryDay(masterBId);

        ZonedDateTime slotT1 = ZonedDateTime.now(TimeZones.KYIV).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        ZonedDateTime slotT2 = slotT1.plusHours(4);

        var bookingOnA = new CreateBookingRequest(masterAId, masterAServiceId, slotT1, null, null, false);
        ResponseEntity<String> respA = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(bookingOnA, fixtures.bearerHeaders(clientToken)), String.class);
        assertThat(respA.getStatusCode())
                .as("setup: booking on master A must succeed — body: %s", respA.getBody())
                .isEqualTo(HttpStatus.CREATED);

        // Client's own single-service visit on master B, at a clear time.
        String visitBody = objectMapper.writeValueAsString(Map.of(
                "masterId", masterBId.toString(),
                "masterServiceIds", List.of(masterBServiceId.toString()),
                "startsAt", slotT2.toOffsetDateTime().toString()));
        ResponseEntity<String> visitResponse = restTemplate.exchange(
                APPOINTMENTS_URL, HttpMethod.POST,
                new HttpEntity<>(visitBody, fixtures.bearerHeaders(clientToken)), String.class);
        assertThat(visitResponse.getStatusCode())
                .as("setup: client's visit on master B must succeed — body: %s", visitResponse.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode visitData = objectMapper.readTree(visitResponse.getBody()).path("data");
        UUID appointmentId = UUID.fromString(visitData.path("id").asText());
        UUID itemBookingId = UUID.fromString(visitData.path("items").get(0).path("bookingId").asText());

        // Move the master-B item onto T1 — master B's own calendar is empty at T1, so the ONLY
        // conflict is the client's OWN calendar (master A's booking, also at T1).
        var rescheduleItemRequest = new AppointmentItemRescheduleRequest(slotT1.toOffsetDateTime(), true);
        ResponseEntity<String> rescheduleResponse = restTemplate.exchange(
                APPOINTMENTS_URL + "/" + appointmentId + "/services/" + itemBookingId + "/reschedule",
                HttpMethod.PATCH, new HttpEntity<>(rescheduleItemRequest, fixtures.bearerHeaders(clientToken)),
                String.class);

        assertThat(rescheduleResponse.getStatusCode())
                .as("allowClientOverlap=true must let a per-item reschedule land on the client's own "
                        + "overlap when the target master's slot is genuinely free — body: %s",
                        rescheduleResponse.getBody())
                .isEqualTo(HttpStatus.OK);

        verify(bookingRepository).existsOverlapExcluding(
                eq(masterBId), argThat(odt -> odt != null && odt.isEqual(slotT1.toOffsetDateTime())),
                any(OffsetDateTime.class), eq(itemBookingId));

        OffsetDateTime persistedStartsAt = jdbcTemplate.queryForObject(
                "SELECT starts_at FROM bookings WHERE id = ?", OffsetDateTime.class, itemBookingId);
        assertThat(persistedStartsAt.toInstant())
                .as("the per-item reschedule must be persisted at the new (overlapping-with-self) time")
                .isEqualTo(slotT1.toOffsetDateTime().toInstant());
    }
}
