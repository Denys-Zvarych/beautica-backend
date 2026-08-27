package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.BookingWindow;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-HTTP-stack pin for the <b>reschedule lead-time parity</b> fix: a PROVIDER-initiated
 * reschedule now runs at the walk-in floor (minimum lead 0) instead of the client-facing 15-minute
 * floor, at BOTH the validator layer ({@code BookingStartsAtValidator}) and the slot layer
 * ({@code BookingSlotAvailabilityGuard}).
 *
 * <h2>The blind spot this closes</h2>
 * Sub-15-minute reschedule was exercised only CLIENT-initiated
 * ({@link BookingRescheduleGuardChainIT#should_return400_when_rescheduleTargetsTimeInsideLeadTimeWindow()},
 * {@code AppointmentRescheduleIT:254}), and every provider-initiated reschedule test used a
 * {@code plusDays(2)}-class target far above BOTH floors
 * ({@link BookingProviderRescheduleIT}). Reverting the fix therefore left the whole scoped suite
 * green. Each test below drives a provider token at a target only ~5 minutes out, so it fails
 * {@code 400} if the validator dispatch is reverted and {@code 409 "Slot not available"} if the
 * slot-guard dispatch is reverted — the two layers are individually falsifiable from here.
 *
 * <h2>All three call sites, individually</h2>
 * The {@code initiatedByProvider} flag was threaded through {@code BookingService#rescheduleBooking},
 * {@code AppointmentTransitionService#rescheduleAppointment} and
 * {@code AppointmentTransitionService#rescheduleAppointmentItem} independently, so a per-site
 * regression must be individually catchable — one test per route, never one representative.
 *
 * <h2>Why the past-dated floor is pinned in a UNIT test, not here</h2>
 * {@code RescheduleBookingRequest.newStartsAt} carries {@code @Future}, so a past instant is rejected
 * at the controller boundary and never reaches {@code validateStaff}. An HTTP-level "past → 400" test
 * would stay green even if the relaxed floor were removed entirely — coverage theater. That pin lives
 * in {@code BookingStartsAtValidatorTest.ProviderFloor#should_rejectPastStart_when_initiatedByProvider}.
 *
 * <h2>THE FIXTURE CONSTRAINT — read before editing (grid alignment)</h2>
 * {@code SlotCalculationService.SLOT_STEP} is 30 minutes and candidate slots are generated as
 * {@code workStart + k*30min} ({@code TimeSlotCalculator#walk}). The slot layer requires
 * {@code startsAt} to MATCH a generated candidate, so an arbitrary "now + 5 min" 409s on the slot
 * layer no matter what the validator decides — and a test written that way fails for the WRONG
 * reason, which reads exactly like "the fix does not work". {@link NearNow} therefore derives the
 * working interval FROM the target: the interval starts at {@code 00:(target.minute % 30)}, which
 * puts the target on the grid for any time of day (a whole hour is a multiple of the 30-minute step,
 * so only the minute-within-half-hour matters — and this stays true across a DST transition, whose
 * offset shift is itself exactly 60 minutes).
 *
 * <p>The second half of {@link NearNow} handles the midnight boundary: a working interval cannot
 * cross midnight (the persisted model forbids it at four layers), so a target whose booked block
 * would run past 23:59 is instead placed just after the NEXT Kyiv midnight. The branch is only taken
 * for a ~6-minute band of wall-clock time and still yields a target under 15 minutes out — see that
 * record's Javadoc for the arithmetic. No clock is pinned anywhere in this class: every time read is
 * live and coherent, and a frozen clock would break JWT minting (JJWT validates against its own
 * system clock).
 */
@Import(TestSecurityConfig.class)
@DisplayName("Provider-initiated reschedule inside the 15-minute client floor (walk-in parity)")
class ProviderNearNowRescheduleIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String APPOINTMENTS_URL = "/api/v1/appointments";
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    /**
     * How far ahead the near-now target sits. Must stay strictly below
     * {@code BookingWindow.MIN_MINUTES_AHEAD} (15) or these tests stop discriminating, and comfortably
     * above zero so fixture latency cannot push the target into the past.
     */
    private static final int LEAD_MINUTES = 5;

    /** Minutes past the next Kyiv midnight the roll-over branch targets — see {@link NearNow}. */
    private static final int ROLLOVER_MINUTE = 2;

    /** Per-service duration. Short on purpose — see {@link NearNow}'s day-end arithmetic. */
    private static final int SERVICE_MINUTES = 3;

    private static final LocalTime WORK_END = LocalTime.of(23, 59);

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

    // ── 1. BookingService#rescheduleBooking — PATCH /bookings/{id}/reschedule ─────────────────────

    @Test
    @DisplayName("PATCH /bookings/{id}/reschedule — the provider moves a booking to ~5 minutes from now: "
            + "200 and the row ACTUALLY moves (400 if the validator dispatch reverts, 409 if the slot "
            + "guard's does)")
    void should_moveBooking_when_providerReschedulesToUnderFifteenMinutesFromNow() throws Exception {
        Fixture f = seedStandaloneBooking("nearnow-booking");
        NearNow near = NearNow.forBlockOf(SERVICE_MINUTES);
        giveWorkingHoursAnchoredOn(f.masterId(), near.gridAnchor());

        ResponseEntity<String> resp = patch(
                BOOKINGS_URL + "/" + f.bookingIds().get(0) + "/reschedule", f.masterToken(), near.target());

        assertThat(resp.getStatusCode())
                .as("a provider moving their own booking to %s (%d min out, below the 15-minute CLIENT "
                        + "floor) must be accepted — body: %s",
                        near.target(), near.leadMinutes(), resp.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(dbStartsAt(f.bookingIds().get(0)).toInstant())
                .as("the persisted starts_at must be the requested near-now time, not merely a 200")
                .isEqualTo(near.target().toInstant());
        assertThat(dbEndsAt(f.bookingIds().get(0)).toInstant())
                .as("ends_at must be starts_at + the FROZEN %d-minute duration", SERVICE_MINUTES)
                .isEqualTo(near.target().plusMinutes(SERVICE_MINUTES).toInstant());
        assertThat(dbStatus(f.bookingIds().get(0))).isEqualTo("CONFIRMED");
    }

    // ── 2. AppointmentTransitionService#rescheduleAppointment — whole visit ───────────────────────

    @Test
    @DisplayName("PATCH /appointments/{id}/reschedule — the provider moves a two-service visit to ~5 "
            + "minutes from now: 200 and the whole chain re-lays out from the new start")
    void should_moveWholeVisit_when_providerReschedulesToUnderFifteenMinutesFromNow() throws Exception {
        Fixture f = seedVisit("nearnow-visit", 2);
        // The WHOLE-CHAIN oracle sizes the block to the sum of both services' durations, so the
        // fixture's day-end headroom must be sized to the sum too — not to one service.
        NearNow near = NearNow.forBlockOf(2 * SERVICE_MINUTES);
        giveWorkingHoursAnchoredOn(f.masterId(), near.gridAnchor());

        ResponseEntity<String> resp = patch(
                APPOINTMENTS_URL + "/" + f.appointmentId() + "/reschedule", f.masterToken(), near.target());

        assertThat(resp.getStatusCode())
                .as("a provider moving a whole visit to %s (%d min out) must be accepted — body: %s",
                        near.target(), near.leadMinutes(), resp.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(dbStartsAt(f.bookingIds().get(0)).toInstant())
                .as("the visit's FIRST item must sit exactly on the requested near-now start")
                .isEqualTo(near.target().toInstant());
        assertThat(dbStartsAt(f.bookingIds().get(1)).toInstant())
                .as("the SECOND item must be chained directly onto the first — the re-layout preserves "
                        + "every frozen duration")
                .isEqualTo(near.target().plusMinutes(SERVICE_MINUTES).toInstant());
    }

    // ── 3. AppointmentTransitionService#rescheduleAppointmentItem — one leg ───────────────────────

    @Test
    @DisplayName("PATCH /appointments/{id}/services/{bookingId}/reschedule — the provider moves ONE leg "
            + "to ~5 minutes from now: 200, that leg moves, the sibling is byte-identical")
    void should_moveSingleItem_when_providerReschedulesToUnderFifteenMinutesFromNow() throws Exception {
        Fixture f = seedVisit("nearnow-item", 2);
        UUID moving = f.bookingIds().get(1);
        UUID sibling = f.bookingIds().get(0);
        OffsetDateTime siblingStartBefore = dbStartsAt(sibling);
        OffsetDateTime siblingEndBefore = dbEndsAt(sibling);
        // Only ONE item moves, so the per-item route asks the SINGLE-service oracle — the block is one
        // service long, never the chain.
        NearNow near = NearNow.forBlockOf(SERVICE_MINUTES);
        giveWorkingHoursAnchoredOn(f.masterId(), near.gridAnchor());

        ResponseEntity<String> resp = patch(
                APPOINTMENTS_URL + "/" + f.appointmentId() + "/services/" + moving + "/reschedule",
                f.masterToken(), near.target());

        assertThat(resp.getStatusCode())
                .as("a provider moving one leg to %s (%d min out) must be accepted — body: %s",
                        near.target(), near.leadMinutes(), resp.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(dbStartsAt(moving).toInstant())
                .as("the moved leg must sit exactly on the requested near-now start")
                .isEqualTo(near.target().toInstant());
        assertThat(dbStartsAt(sibling).toInstant())
                .as("the sibling's starts_at must be untouched — a per-item move never re-lays out siblings")
                .isEqualTo(siblingStartBefore.toInstant());
        assertThat(dbEndsAt(sibling).toInstant())
                .as("the sibling's ends_at must be untouched too")
                .isEqualTo(siblingEndBefore.toInstant());
    }

    // ── 4. The negative pin on the newly-covered route ────────────────────────────────────────────

    /**
     * The client-initiated counterpart for the PER-ITEM route specifically. The plain-booking and
     * whole-visit routes already carry this pin
     * ({@link BookingRescheduleGuardChainIT#should_return400_when_rescheduleTargetsTimeInsideLeadTimeWindow()}
     * and {@code AppointmentRescheduleIT:254}) and are deliberately not duplicated here; the per-item
     * route had none, and without it a mutation that relaxes the floor for EVERY actor — rather than
     * dispatching on one — would pass every other test in this class.
     */
    @Test
    @DisplayName("PATCH /appointments/{id}/services/{bookingId}/reschedule — a CLIENT moving the same "
            + "leg to ~5 minutes from now still gets 400 and the row does not move")
    void should_return400_when_clientReschedulesItemToUnderFifteenMinutesFromNow() throws Exception {
        Fixture f = seedVisit("nearnow-item-client", 2);
        UUID moving = f.bookingIds().get(1);
        OffsetDateTime before = dbStartsAt(moving);
        NearNow near = NearNow.forBlockOf(SERVICE_MINUTES);
        giveWorkingHoursAnchoredOn(f.masterId(), near.gridAnchor());

        ResponseEntity<String> resp = patch(
                APPOINTMENTS_URL + "/" + f.appointmentId() + "/services/" + moving + "/reschedule",
                f.clientToken(), near.target());

        assertThat(resp.getStatusCode())
                .as("the 15-minute floor is untouched for a CLIENT-initiated move — body: %s", resp.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        // The domain message is deliberately REDACTED to "Invalid request" for every BusinessException
        // 400 (GlobalExceptionHandler:152), so the wire cannot discriminate WHICH 400 this is. The
        // discriminator is the pair: the provider test above sends an equally near-now instant on the
        // same route and gets 200 — only the actor differs.
        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(body.path("success").asBoolean(true)).isFalse();
        assertThat(body.path("message").asText())
                .as("the redacted 400 shape must be preserved — no floor value echoed back")
                .isEqualTo("Invalid request");
        assertThat(dbStartsAt(moving).toInstant())
                .as("a rejected reschedule must leave the row exactly where it was")
                .isEqualTo(before.toInstant());
    }

    // ── fixture: the near-now, on-grid target ─────────────────────────────────────────────────────

    /**
     * A reschedule target that is simultaneously (a) strictly inside the 15-minute client floor,
     * (b) strictly in the future, and (c) on the 30-minute slot grid of the working interval this
     * record also dictates.
     *
     * <p><b>(c) is the trap.</b> Candidates are {@code workStart + k*30min}, so the grid is pinned by
     * choosing {@code workStart = 00:(target.minute % 30)}: whole hours are multiples of the step, so
     * only the minute-within-half-hour has to agree, and the target then lands on the grid whatever
     * the hour. A DST transition shifts the wall clock by exactly 60 minutes, itself a multiple of the
     * step, so the alignment survives both directions.
     *
     * <p><b>The midnight branch.</b> A working interval may not cross midnight, so the slot walk only
     * emits a candidate when {@code candidate + blockMinutes <= 23:59}. When "now + 5 min" is late
     * enough that the block would run past the day's close, the target is placed at
     * {@code nextKyivMidnight + 2 min} instead. That branch is entered only while the truncated
     * local time sits in {@code [23:59 - LEAD - block, 23:59 - block]} — at most a 6-minute band for
     * the largest block used here — and over that band the resulting lead is
     * {@code (minutes to midnight) + 2}, i.e. at most {@code LEAD + block + 1 + 2 = 13} minutes:
     * still strictly under the 15-minute floor, and never less than ~7 minutes ahead of now. Keep
     * {@code LEAD_MINUTES + blockMinutes + ROLLOVER_MINUTE <= 13} when adding a longer block, or the
     * roll-over target drifts above the floor and the test silently stops discriminating.
     *
     * @param target     the instant to request
     * @param gridAnchor the {@code start_time} the fixture's working intervals must carry
     */
    private record NearNow(OffsetDateTime target, LocalTime gridAnchor, long leadMinutes) {

        static NearNow forBlockOf(int blockMinutes) {
            ZonedDateTime now = ZonedDateTime.now(KYIV).truncatedTo(ChronoUnit.MINUTES);
            ZonedDateTime candidate = now.plusMinutes(LEAD_MINUTES);

            boolean blockFitsBeforeDayClose =
                    candidate.toLocalTime().toSecondOfDay() + blockMinutes * 60L
                            <= WORK_END.toSecondOfDay();
            ZonedDateTime target = blockFitsBeforeDayClose
                    ? candidate
                    : now.toLocalDate().plusDays(1).atStartOfDay(KYIV).plusMinutes(ROLLOVER_MINUTE);

            long lead = ChronoUnit.MINUTES.between(ZonedDateTime.now(KYIV), target);
            // Self-check, not decoration: if the arithmetic above ever drifts (a longer block, a
            // bigger LEAD_MINUTES/ROLLOVER_MINUTE) the target silently rises above the 15-minute floor
            // and every test in this class keeps passing while proving nothing. Fail loudly instead.
            assertThat(lead)
                    .as("fixture invariant: the target must sit strictly INSIDE the %d-minute client "
                            + "floor, or these tests stop discriminating (target=%s)",
                            BookingWindow.MIN_MINUTES_AHEAD, target)
                    .isLessThan(BookingWindow.MIN_MINUTES_AHEAD)
                    .isPositive();
            return new NearNow(
                    target.toOffsetDateTime(), LocalTime.of(0, target.getMinute() % 30), lead);
        }
    }

    // ── fixture: rows ─────────────────────────────────────────────────────────────────────────────

    /** A seeded independent-master booking or visit plus the tokens needed to drive both actors. */
    private record Fixture(UUID masterId, UUID appointmentId, List<UUID> bookingIds,
                           String masterToken, String clientToken) {}

    /**
     * A standalone CONFIRMED booking ({@code appointment_id} NULL) parked three days out. The OLD time
     * is never re-validated by a reschedule, so it needs no grid alignment — it only has to be far
     * enough from the near-now target that the booking's own row cannot occupy it.
     */
    private Fixture seedStandaloneBooking(String tag) throws Exception {
        Seed seed = seedActors(tag, 1);
        UUID bookingId = UUID.randomUUID();
        OffsetDateTime start = parkedStart();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, appointment_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 'CONFIRMED', ?, ?, 500.00, ?, 0, 'APP', NULL, NOW(), NOW())",
                bookingId, seed.clientId(), seed.masterId(), seed.serviceIds().get(0),
                start, start.plusMinutes(SERVICE_MINUTES), SERVICE_MINUTES);
        return new Fixture(seed.masterId(), null, List.of(bookingId),
                seed.masterToken(), seed.clientToken());
    }

    /** A CONFIRMED visit of {@code itemCount} contiguous items, parked three days out. */
    private Fixture seedVisit(String tag, int itemCount) throws Exception {
        Seed seed = seedActors(tag, itemCount);
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO appointments (id, client_id, salon_id, status, booking_source, created_at, "
                        + "updated_at) VALUES (?, ?, NULL, 'CONFIRMED', 'APP', NOW(), NOW())",
                appointmentId, seed.clientId());
        List<UUID> bookingIds = new ArrayList<>();
        OffsetDateTime cursor = parkedStart();
        for (UUID serviceId : seed.serviceIds()) {
            UUID bookingId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, appointment_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, NULL, 'CONFIRMED', ?, ?, 500.00, ?, 0, 'APP', ?, NOW(), NOW())",
                    bookingId, seed.clientId(), seed.masterId(), serviceId,
                    cursor, cursor.plusMinutes(SERVICE_MINUTES), SERVICE_MINUTES, appointmentId);
            bookingIds.add(bookingId);
            cursor = cursor.plusMinutes(SERVICE_MINUTES);
        }
        return new Fixture(seed.masterId(), appointmentId, bookingIds,
                seed.masterToken(), seed.clientToken());
    }

    private record Seed(UUID masterId, UUID clientId, List<UUID> serviceIds,
                        String masterToken, String clientToken) {}

    private Seed seedActors(String tag, int serviceCount) throws Exception {
        String masterEmail = tag + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        String clientEmail = tag + "-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        List<UUID> serviceIds = new ArrayList<>();
        for (int i = 0; i < serviceCount; i++) {
            serviceIds.add(fixtures.createIndependentMasterService(
                    masterId, "Near-now service " + i, SERVICE_MINUTES));
        }
        // Tokens are minted BEFORE the near-now target is computed, so the only work between the
        // target's computation and the PATCH is one schedule insert — fixture latency can never eat
        // into the lead.
        return new Seed(masterId, clientId, serviceIds,
                fixtures.tokenFor(masterEmail), fixtures.tokenFor(clientEmail));
    }

    /** Three days out — far from any near-now target, and never re-validated by a reschedule. */
    private static OffsetDateTime parkedStart() {
        return ZonedDateTime.now(KYIV).plusDays(3)
                .withHour(10).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();
    }

    /**
     * Seven-day open-ended weekly schedule running {@code anchor}–23:59. The anchor is what puts the
     * near-now target on the 30-minute candidate grid — see {@link NearNow}. Raw JDBC (mirroring
     * {@link BookingTestFixtures#addWorkingHoursForEveryDay}) rather than
     * {@code MasterScheduleService}, so the anchor is taken verbatim.
     */
    private void giveWorkingHoursAnchoredOn(UUID masterId, LocalTime anchor) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to) "
                        + "VALUES (?, ?, DATE '2020-01-01', NULL)",
                scheduleId, masterId);
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), scheduleId, day, anchor, WORK_END);
        }
    }

    // ── HTTP + DB helpers ─────────────────────────────────────────────────────────────────────────

    private ResponseEntity<String> patch(String url, String token, OffsetDateTime newStartsAt) throws Exception {
        String body = objectMapper.writeValueAsString(
                java.util.Map.of("newStartsAt", newStartsAt.toString()));
        return restTemplate.exchange(
                url, HttpMethod.PATCH, new HttpEntity<>(body, fixtures.bearerHeaders(token)), String.class);
    }

    private OffsetDateTime dbStartsAt(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT starts_at FROM bookings WHERE id = ?", OffsetDateTime.class, bookingId);
    }

    private OffsetDateTime dbEndsAt(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT ends_at FROM bookings WHERE id = ?", OffsetDateTime.class, bookingId);
    }

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }
}
