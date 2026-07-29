package com.beautica.master;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.common.TimeZones;
import com.beautica.master.dto.EffectiveDaySource;
import com.beautica.master.dto.OverrideConflictQueryRequest;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;
import com.beautica.master.service.MasterScheduleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers IT for the 2026-07-26 "schedule override over existing bookings" design — the
 * REVERSAL of {@code MasterScheduleService.upsertOverride}'s former OQ-1 ("always allowed, never
 * blocked by nor mutating existing bookings"). Drives the FULL HTTP stack (real filter chain, real
 * {@code @PreAuthorize} SpEL, real {@link com.beautica.booking.service.ScheduleOverrideConflictService},
 * real Postgres) so the conflict detection + decline-on-confirm behaviour is proven end to end, not
 * just at the mocked-collaborator unit level ({@code ScheduleOverrideConflictServiceTest}) or the pure
 * predicate level ({@code ScheduleConflictCalculatorTest}).
 *
 * <p><b>Authorization is deliberately NOT re-tested here.</b> {@link MasterScheduleSecurityIT}'s
 * {@code OverrideConflictsPreviewAuthorization} nested class already covers the preview endpoint's
 * {@code canManageMasterSchedule} gate (foreign master, read-only SALON_MASTER, CLIENT, anonymous,
 * nonexistent masterId — all 403/401, no existence oracle). This class exercises the DOMAIN behaviour
 * only: conflict detection across every {@code kind}/{@code mode} combination, the 409-vs-200-with-
 * decline write fork, the past-booking exclusion, appointment partial/full decline, and preview
 * projection correctness (client vs. guest name, truncation).
 *
 * <p><b>Fixture style.</b> Bookings are seeded directly via SQL (mirrors the established house
 * pattern in {@code AppointmentTransitionMatrixIT} / {@code BookingNoteVisibilityIT}) rather than
 * through {@code POST /bookings}: the endpoint under test is the OVERRIDE write/preview, not booking
 * creation, and SQL seeding is the only way to place a booking at an exact, deterministic
 * {@code [startsAt, endsAt)} window without fighting the real slot/working-hours engine. Each test
 * seeds its own fresh {@code INDEPENDENT_MASTER} (own random actor id), so there is no shared mutable
 * state between tests and no rate-limit-bucket collision (the per-actor {@code PUT .../overrides}
 * bucket is keyed by user id — see {@code BookingRateLimitFilter}).
 *
 * <p><b>No frozen clock.</b> Unlike {@code MasterScheduleEdgeCaseIT}, this class does not override
 * {@code ClockConfig.systemClock()}: {@link ScheduleOverrideRequest#date} carries
 * {@code @FutureOrPresent}, validated against the REAL system clock, so a fixed fake-past clock would
 * make every write 400 at the validation layer before ever reaching the conflict engine. Tests that
 * need a future date use {@code LocalDate.now(TimeZones.KYIV).plusDays(30)}; the past-booking-exclusion
 * test (S7) uses real wall-clock {@code OffsetDateTime.now()} arithmetic instead of a fixed instant.
 */
@DisplayName("Schedule override over existing bookings — conflict detection & cancellation (2026-07-26 design)")
class MasterScheduleOverrideConflictIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/masters";
    private static final LocalDate FUTURE_DATE = LocalDate.now(TimeZones.KYIV).plusDays(30);
    private static final String CONFLICT_MESSAGE = "Request could not be completed due to a conflict";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MasterScheduleService scheduleService;

    // ══════════════════════════════════════════════════════════════════════════════════
    // Fixtures — actors, masters, services, bookings
    // ══════════════════════════════════════════════════════════════════════════════════

    private record Actor(UUID userId, String email, Role role) {}

    private record SeededMaster(UUID masterId, Actor owner) {}

    private Actor seedUser(Role role) {
        UUID userId = UUID.randomUUID();
        String email = role.name().toLowerCase() + "-ovc-" + userId + "@beautica.test";
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', ?, 'Fn', 'Ln', true, true)",
                userId, email, role.name());
        return new Actor(userId, email, role);
    }

    private SeededMaster seedIndependentMaster() {
        Actor owner = seedUser(Role.INDEPENDENT_MASTER);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, owner.userId());
        return new SeededMaster(masterId, owner);
    }

    private UUID createMasterService(SeededMaster master, String serviceName) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, ?, ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, master.owner().userId(), serviceName,
                resolveUnusedServiceTypeId("INDEPENDENT_MASTER", master.owner().userId()));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, master.masterId(), serviceDefId);
        return masterServiceId;
    }

    private String tokenFor(Actor a) {
        return jwtTokenProvider.generateAccessToken(a.userId(), a.email(), a.role());
    }

    private HttpHeaders auth(Actor a) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(tokenFor(a));
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private static OffsetDateTime kyiv(LocalDate date, LocalTime time) {
        return date.atTime(time).atZone(TimeZones.KYIV).toOffsetDateTime();
    }

    private UUID insertConfirmedBooking(
            UUID clientId, UUID masterId, UUID masterServiceId, OffsetDateTime startsAt, OffsetDateTime endsAt) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, startsAt, endsAt);
        return bookingId;
    }

    private UUID insertConfirmedGuestBooking(
            UUID masterId, UUID masterServiceId, OffsetDateTime startsAt, OffsetDateTime endsAt,
            String guestName, String guestSurname) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, cancel_token, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'LINK', ?, ?, '+380501112233', "
                        + "?, NOW(), NOW())",
                bookingId, masterId, masterServiceId, startsAt, endsAt, guestName, guestSurname, UUID.randomUUID());
        return bookingId;
    }

    /**
     * Seeds a CONFIRMED appointment header (no salon) plus {@code itemCount} contiguous one-hour
     * CONFIRMED bookings, each a distinct master-service, chained from {@code firstStart}. Mirrors
     * {@code AppointmentTransitionMatrixIT#seedVisitRows}.
     */
    private UUID seedAppointmentVisit(
            UUID clientId, SeededMaster master, int itemCount, OffsetDateTime firstStart) {
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO appointments (id, client_id, salon_id, status, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, NULL, 'CONFIRMED', 'APP', NOW(), NOW())",
                appointmentId, clientId);
        OffsetDateTime cursor = firstStart;
        for (int i = 0; i < itemCount; i++) {
            UUID masterServiceId = createMasterService(master, "Visit item " + i);
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, appointment_id, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, 'APP', ?, NOW(), NOW())",
                    UUID.randomUUID(), clientId, master.masterId(), masterServiceId, cursor,
                    cursor.plusHours(1), appointmentId);
            cursor = cursor.plusHours(1);
        }
        return appointmentId;
    }

    private String bookingStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String bookingCancellationReason(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT cancellation_reason FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String bookingProviderComment(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT provider_comment FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String appointmentStatus(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM appointments WHERE id = ?", String.class, appointmentId);
    }

    private List<String> childStatuses(UUID appointmentId) {
        return jdbcTemplate.queryForList(
                "SELECT status FROM bookings WHERE appointment_id = ? ORDER BY starts_at", String.class, appointmentId);
    }

    // ══════════════════════════════════════════════════════════════════════════════════
    // HTTP helpers
    // ══════════════════════════════════════════════════════════════════════════════════

    private ResponseEntity<String> putOverride(UUID masterId, Actor actor, ScheduleOverrideRequest request)
            throws Exception {
        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(request), auth(actor));
        return restTemplate.exchange(
                BASE + "/" + masterId + "/overrides/" + request.date(), HttpMethod.PUT, entity, String.class);
    }

    private ResponseEntity<String> previewConflicts(UUID masterId, Actor actor, OverrideConflictQueryRequest request)
            throws Exception {
        HttpEntity<String> entity = new HttpEntity<>(objectMapper.writeValueAsString(request), auth(actor));
        return restTemplate.exchange(
                BASE + "/" + masterId + "/overrides/conflicts", HttpMethod.POST, entity, String.class);
    }

    private JsonNode dataOf(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 1/2 — DAY_OFF over a future booking: 409 without the flag, decline with it
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DAY_OFF over a future CONFIRMED booking")
    class DayOffOverFutureBooking {

        @Test
        @DisplayName("without cancelOverlapping -> 409, override NOT persisted, booking stays CONFIRMED")
        void should_rejectWith409_when_dayOffWouldOrphanFutureBookingAndFlagIsFalse() throws Exception {
            SeededMaster master = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            UUID masterServiceId = createMasterService(master, "Manicure");
            UUID bookingId = insertConfirmedBooking(client.userId(), master.masterId(), masterServiceId,
                    kyiv(FUTURE_DATE, LocalTime.of(10, 0)), kyiv(FUTURE_DATE, LocalTime.of(11, 0)));

            ResponseEntity<String> resp = putOverride(master.masterId(), master.owner(),
                    new ScheduleOverrideRequest(FUTURE_DATE, ScheduleExceptionKind.DAY_OFF, List.of()));

            assertThat(resp.getStatusCode())
                    .as("a DAY_OFF that would orphan a CONFIRMED booking must be rejected without consent")
                    .isEqualTo(HttpStatus.CONFLICT);
            assertThat(objectMapper.readTree(resp.getBody()).path("message").asText())
                    .as("the conflict count/message is redacted at the client boundary (GlobalExceptionHandler)")
                    .isEqualTo(CONFLICT_MESSAGE);
            assertThat(bookingStatus(bookingId)).isEqualTo("CONFIRMED");
            assertThat(scheduleService.resolveEffectiveDay(master.masterId(), FUTURE_DATE).source())
                    .as("the rejected write must leave no override row behind")
                    .isEqualTo(EffectiveDaySource.NO_SCHEDULE);
        }

        @Test
        @DisplayName("with cancelOverlapping=true -> override persisted, booking DECLINED/PROVIDER_UNAVAILABLE, "
                + "no note (D2 REVISED — a master taking a day-off/pause gives no reason)")
        void should_persistOverrideAndDeclineBookingWithoutAnyNote_when_dayOffAndCancelOverlappingIsTrue() throws Exception {
            SeededMaster master = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            UUID masterServiceId = createMasterService(master, "Manicure");
            UUID bookingId = insertConfirmedBooking(client.userId(), master.masterId(), masterServiceId,
                    kyiv(FUTURE_DATE, LocalTime.of(10, 0)), kyiv(FUTURE_DATE, LocalTime.of(11, 0)));

            ResponseEntity<String> resp = putOverride(master.masterId(), master.owner(),
                    new ScheduleOverrideRequest(FUTURE_DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL,
                            List.of(), null, true));

            assertThat(resp.getStatusCode())
                    .as("consenting to cancelOverlapping must let the DAY_OFF write through — body: %s", resp.getBody())
                    .isEqualTo(HttpStatus.OK);
            assertThat(scheduleService.resolveEffectiveDay(master.masterId(), FUTURE_DATE).source())
                    .isEqualTo(EffectiveDaySource.OVERRIDE_DAY_OFF);
            assertThat(bookingStatus(bookingId)).isEqualTo("DECLINED");
            assertThat(bookingCancellationReason(bookingId)).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(bookingProviderComment(bookingId))
                    .as("D2 REVISED (2026-07-26 product decision reversal) — no master note on this path")
                    .isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 3/4 — CUSTOM_HOURS / INTERVAL narrowing
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CUSTOM_HOURS / INTERVAL narrowing")
    class CustomHoursNarrowing {

        @Test
        @DisplayName("narrowed interval does NOT cover the booking -> preview reports it, write 409s without the flag")
        void should_reportAndReject_when_narrowedIntervalOrphansBooking() throws Exception {
            SeededMaster master = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            UUID masterServiceId = createMasterService(master, "Haircut");
            UUID bookingId = insertConfirmedBooking(client.userId(), master.masterId(), masterServiceId,
                    kyiv(FUTURE_DATE, LocalTime.of(14, 0)), kyiv(FUTURE_DATE, LocalTime.of(15, 0)));
            List<WorkIntervalDto> narrowed = List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(13, 0)));

            ResponseEntity<String> preview = previewConflicts(master.masterId(), master.owner(),
                    new OverrideConflictQueryRequest(FUTURE_DATE, FUTURE_DATE, ScheduleExceptionKind.CUSTOM_HOURS,
                            WeekdayMode.INTERVAL, narrowed, null));
            assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode previewData = dataOf(preview);
            assertThat(previewData.path("totalCount").asInt()).isEqualTo(1);
            assertThat(previewData.path("truncated").asBoolean()).isFalse();
            JsonNode conflict = previewData.path("conflicts").get(0);
            assertThat(conflict.path("bookingId").asText()).isEqualTo(bookingId.toString());
            assertThat(conflict.path("serviceName").asText()).isEqualTo("Haircut");

            ResponseEntity<String> put = putOverride(master.masterId(), master.owner(),
                    new ScheduleOverrideRequest(FUTURE_DATE, ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL,
                            narrowed, null, false));
            assertThat(put.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(bookingStatus(bookingId)).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("narrowed interval STILL fully covers the booking -> no conflict, writes cleanly")
        void should_writeCleanly_when_narrowedIntervalStillCoversBooking() throws Exception {
            SeededMaster master = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            UUID masterServiceId = createMasterService(master, "Haircut");
            UUID bookingId = insertConfirmedBooking(client.userId(), master.masterId(), masterServiceId,
                    kyiv(FUTURE_DATE, LocalTime.of(10, 0)), kyiv(FUTURE_DATE, LocalTime.of(11, 0)));
            List<WorkIntervalDto> stillCovers = List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)));

            ResponseEntity<String> put = putOverride(master.masterId(), master.owner(),
                    new ScheduleOverrideRequest(FUTURE_DATE, ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL,
                            stillCovers, null, false));

            assertThat(put.getStatusCode())
                    .as("cancelOverlapping is irrelevant when there is no conflict — body: %s", put.getBody())
                    .isEqualTo(HttpStatus.OK);
            assertThat(bookingStatus(bookingId)).isEqualTo("CONFIRMED");
            assertThat(scheduleService.resolveEffectiveDay(master.masterId(), FUTURE_DATE).source())
                    .isEqualTo(EffectiveDaySource.OVERRIDE_CUSTOM);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 5 — EXPLICIT_TIMES mode
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("CUSTOM_HOURS / EXPLICIT_TIMES")
    class ExplicitTimesMode {

        @Test
        @DisplayName("booking startsAt matches a discrete time -> no conflict, writes cleanly")
        void should_writeCleanly_when_bookingStartMatchesADiscreteTime() throws Exception {
            SeededMaster master = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            UUID masterServiceId = createMasterService(master, "Brows");
            UUID bookingId = insertConfirmedBooking(client.userId(), master.masterId(), masterServiceId,
                    kyiv(FUTURE_DATE, LocalTime.of(10, 0)), kyiv(FUTURE_DATE, LocalTime.of(10, 45)));

            ResponseEntity<String> put = putOverride(master.masterId(), master.owner(),
                    new ScheduleOverrideRequest(FUTURE_DATE, ScheduleExceptionKind.CUSTOM_HOURS,
                            WeekdayMode.EXPLICIT_TIMES, List.of(),
                            List.of(LocalTime.of(10, 0), LocalTime.of(12, 0)), false));

            assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(bookingStatus(bookingId)).isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("booking startsAt matches NO discrete time -> conflict, 409 without the flag")
        void should_reject_when_bookingStartMatchesNoDiscreteTime() throws Exception {
            SeededMaster master = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            UUID masterServiceId = createMasterService(master, "Brows");
            UUID bookingId = insertConfirmedBooking(client.userId(), master.masterId(), masterServiceId,
                    kyiv(FUTURE_DATE, LocalTime.of(10, 15)), kyiv(FUTURE_DATE, LocalTime.of(11, 0)));

            ResponseEntity<String> preview = previewConflicts(master.masterId(), master.owner(),
                    new OverrideConflictQueryRequest(FUTURE_DATE, FUTURE_DATE, ScheduleExceptionKind.CUSTOM_HOURS,
                            WeekdayMode.EXPLICIT_TIMES, null, List.of(LocalTime.of(10, 0), LocalTime.of(11, 0))));
            assertThat(dataOf(preview).path("totalCount").asInt())
                    .as("10:15 matches neither discrete time 10:00 nor 11:00")
                    .isEqualTo(1);

            ResponseEntity<String> put = putOverride(master.masterId(), master.owner(),
                    new ScheduleOverrideRequest(FUTURE_DATE, ScheduleExceptionKind.CUSTOM_HOURS,
                            WeekdayMode.EXPLICIT_TIMES, List.of(),
                            List.of(LocalTime.of(10, 0), LocalTime.of(11, 0)), false));
            assertThat(put.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(bookingStatus(bookingId)).isEqualTo("CONFIRMED");
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 6 — Widening hours: regression guard for the "extend my day" flow
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Widening hours — 'extend my day' must never conflict")
    class WideningHours {

        @Test
        @DisplayName("re-saving the SAME date with WIDER hours over an already-covered booking -> zero conflicts")
        void should_reportZeroConflicts_when_wideningAnExistingCustomHoursOverride() throws Exception {
            SeededMaster master = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            UUID masterServiceId = createMasterService(master, "Massage");
            UUID bookingId = insertConfirmedBooking(client.userId(), master.masterId(), masterServiceId,
                    kyiv(FUTURE_DATE, LocalTime.of(9, 30)), kyiv(FUTURE_DATE, LocalTime.of(10, 0)));
            List<WorkIntervalDto> initial = List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(12, 0)));
            ResponseEntity<String> initialPut = putOverride(master.masterId(), master.owner(),
                    new ScheduleOverrideRequest(FUTURE_DATE, ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL,
                            initial, null, false));
            assertThat(initialPut.getStatusCode())
                    .as("establishing the initial CUSTOM_HOURS override must succeed")
                    .isEqualTo(HttpStatus.OK);

            List<WorkIntervalDto> widened = List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(18, 0)));
            ResponseEntity<String> preview = previewConflicts(master.masterId(), master.owner(),
                    new OverrideConflictQueryRequest(FUTURE_DATE, FUTURE_DATE, ScheduleExceptionKind.CUSTOM_HOURS,
                            WeekdayMode.INTERVAL, widened, null));
            assertThat(dataOf(preview).path("conflicts")).isEmpty();
            assertThat(dataOf(preview).path("totalCount").asInt()).isZero();

            ResponseEntity<String> widenPut = putOverride(master.masterId(), master.owner(),
                    new ScheduleOverrideRequest(FUTURE_DATE, ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL,
                            widened, null, false));
            assertThat(widenPut.getStatusCode())
                    .as("widening hours must never require cancelOverlapping — body: %s", widenPut.getBody())
                    .isEqualTo(HttpStatus.OK);
            assertThat(bookingStatus(bookingId)).isEqualTo("CONFIRMED");
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 7 — past/in-progress booking on today's date is never reported or cancelled
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Past/in-progress booking on today's date")
    class PastOrInProgressBookingExcluded {

        @Test
        @DisplayName("a booking that already started today is NOT reported and NOT cancelled by a same-day DAY_OFF")
        void should_excludeAlreadyStartedBooking_when_dayOffAppliedToToday() throws Exception {
            SeededMaster master = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            UUID masterServiceId = createMasterService(master, "Pedicure");
            LocalDate today = LocalDate.now(TimeZones.KYIV);
            OffsetDateTime alreadyStarted = OffsetDateTime.now().minusMinutes(10);
            UUID bookingId = insertConfirmedBooking(client.userId(), master.masterId(), masterServiceId,
                    alreadyStarted, alreadyStarted.plusMinutes(60));

            ResponseEntity<String> preview = previewConflicts(master.masterId(), master.owner(),
                    new OverrideConflictQueryRequest(today, today, ScheduleExceptionKind.DAY_OFF, null, null, null));
            assertThat(dataOf(preview).path("conflicts"))
                    .as("a booking whose startsAt is already in the past must never surface as a conflict")
                    .isEmpty();

            ResponseEntity<String> put = putOverride(master.masterId(), master.owner(),
                    new ScheduleOverrideRequest(today, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL,
                            List.of(), null, true));
            assertThat(put.getStatusCode())
                    .as("no conflicts exist for today once the started booking is excluded, so this must succeed "
                            + "even though cancelOverlapping=true — body: %s", put.getBody())
                    .isEqualTo(HttpStatus.OK);
            assertThat(bookingStatus(bookingId))
                    .as("the already-started booking must survive the same-day DAY_OFF untouched")
                    .isEqualTo("CONFIRMED");
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 8 — Multi-service appointment: partial decline vs. full-header collapse
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multi-service appointment conflicts")
    class AppointmentConflicts {

        @Test
        @DisplayName("only the overlapped child declines; header stays CONFIRMED while a sibling remains CONFIRMED")
        void should_declineOnlyOverlappedChild_when_someAppointmentServicesStillFit() throws Exception {
            SeededMaster master = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            // Three contiguous one-hour items: 09:00-10:00, 10:00-11:00, 11:00-12:00.
            UUID appointmentId = seedAppointmentVisit(client.userId(), master, 3, kyiv(FUTURE_DATE, LocalTime.of(9, 0)));
            List<UUID> childIds = jdbcTemplate.queryForList(
                    "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at", UUID.class, appointmentId);

            // New hours [09:00-11:00] fully cover items 1 & 2 but orphan item 3 (11:00-12:00).
            List<WorkIntervalDto> partialCover = List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(11, 0)));
            ResponseEntity<String> put = putOverride(master.masterId(), master.owner(),
                    new ScheduleOverrideRequest(FUTURE_DATE, ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL,
                            partialCover, null, true));

            assertThat(put.getStatusCode())
                    .as("only ONE of three items conflicts, so cancelOverlapping=true must succeed — body: %s",
                            put.getBody())
                    .isEqualTo(HttpStatus.OK);
            assertThat(bookingStatus(childIds.get(0))).isEqualTo("CONFIRMED");
            assertThat(bookingStatus(childIds.get(1))).isEqualTo("CONFIRMED");
            assertThat(bookingStatus(childIds.get(2))).isEqualTo("DECLINED");
            assertThat(bookingCancellationReason(childIds.get(2))).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(bookingProviderComment(childIds.get(2)))
                    .as("D2 REVISED (2026-07-26 product decision reversal) — no master note on this path, "
                            + "including an appointment-child decline")
                    .isNull();
            assertThat(appointmentStatus(appointmentId))
                    .as("the header must stay CONFIRMED while at least one CONFIRMED child remains")
                    .isEqualTo("CONFIRMED");
        }

        @Test
        @DisplayName("every child overlaps -> all decline and the header collapses to DECLINED")
        void should_collapseHeaderToDeclined_when_lastConfirmedChildIsDeclined() throws Exception {
            SeededMaster master = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            UUID appointmentId = seedAppointmentVisit(client.userId(), master, 2, kyiv(FUTURE_DATE, LocalTime.of(9, 0)));
            List<UUID> childIds = jdbcTemplate.queryForList(
                    "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at", UUID.class, appointmentId);

            ResponseEntity<String> put = putOverride(master.masterId(), master.owner(),
                    new ScheduleOverrideRequest(FUTURE_DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL,
                            List.of(), null, true));

            assertThat(put.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(childStatuses(appointmentId))
                    .as("every chained item must be declined once the whole day is closed")
                    .containsExactly("DECLINED", "DECLINED");
            assertThat(appointmentStatus(appointmentId))
                    .as("the header collapses to DECLINED only once the LAST CONFIRMED child goes")
                    .isEqualTo("DECLINED");
            for (UUID childId : childIds) {
                assertThat(bookingCancellationReason(childId)).isEqualTo("PROVIDER_UNAVAILABLE");
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 9 — Preview projection: registered client vs. guest (LINK) display name
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Preview projection — clientDisplayName for registered client vs. guest")
    class PreviewClientDisplayName {

        @Test
        @DisplayName("a registered client's conflict row carries their first+last name")
        void should_returnRegisteredClientName_inPreview() throws Exception {
            SeededMaster master = seedIndependentMaster();
            UUID clientId = UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                            + "is_active, email_verified) VALUES (?, ?, 'x', 'CLIENT', 'Olena', 'Kovalenko', true, true)",
                    clientId, "ovc-client-" + clientId + "@beautica.test");
            UUID masterServiceId = createMasterService(master, "Manicure");
            insertConfirmedBooking(clientId, master.masterId(), masterServiceId,
                    kyiv(FUTURE_DATE, LocalTime.of(10, 0)), kyiv(FUTURE_DATE, LocalTime.of(11, 0)));

            ResponseEntity<String> preview = previewConflicts(master.masterId(), master.owner(),
                    new OverrideConflictQueryRequest(FUTURE_DATE, FUTURE_DATE, ScheduleExceptionKind.DAY_OFF,
                            null, null, null));

            JsonNode conflict = dataOf(preview).path("conflicts").get(0);
            assertThat(conflict.path("clientDisplayName").asText()).isEqualTo("Olena Kovalenko");
            assertThat(conflict.path("appointmentId").isNull())
                    .as("a standalone booking's conflict row must carry a null appointmentId")
                    .isTrue();
        }

        @Test
        @DisplayName("a guest (LINK) conflict row carries the guest's name, not a null/blank display name")
        void should_returnGuestName_inPreview_forLinkBooking() throws Exception {
            SeededMaster master = seedIndependentMaster();
            UUID masterServiceId = createMasterService(master, "Waxing");
            insertConfirmedGuestBooking(master.masterId(), masterServiceId,
                    kyiv(FUTURE_DATE, LocalTime.of(15, 0)), kyiv(FUTURE_DATE, LocalTime.of(16, 0)),
                    "Ihor", "Petrenko");

            ResponseEntity<String> preview = previewConflicts(master.masterId(), master.owner(),
                    new OverrideConflictQueryRequest(FUTURE_DATE, FUTURE_DATE, ScheduleExceptionKind.DAY_OFF,
                            null, null, null));

            JsonNode conflict = dataOf(preview).path("conflicts").get(0);
            assertThat(conflict.path("clientDisplayName").asText())
                    .as("a guest booking has no registered client — the response must fall back to the guest name")
                    .isEqualTo("Ihor Petrenko");
            assertThat(conflict.path("serviceName").asText()).isEqualTo("Waxing");
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 10 — Preview truncation / totalCount semantics
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Preview truncation semantics")
    class PreviewTruncation {

        @Test
        @DisplayName("501 conflicting bookings -> conflicts capped at 500, totalCount=501, truncated=true")
        void should_capConflictsAndFlagTruncated_when_conflictCountExceedsPreviewLimit() throws Exception {
            SeededMaster master = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            UUID masterServiceId = createMasterService(master, "Bulk service");
            OffsetDateTime base = kyiv(FUTURE_DATE, LocalTime.of(0, 0));
            // One INSERT ... SELECT generate_series round trip: 501 back-to-back one-minute CONFIRMED
            // bookings for the SAME master, non-overlapping ('[)' half-open ranges touch but never cross).
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "SELECT gen_random_uuid(), ?, ?, ?, 'CONFIRMED', "
                            + "  ? + (n || ' minutes')::interval, ? + ((n+1) || ' minutes')::interval, "
                            + "  500.00, 1, 0, 'APP', NOW(), NOW() "
                            + "FROM generate_series(0, 500) AS n",
                    client.userId(), master.masterId(), masterServiceId, base, base);

            ResponseEntity<String> preview = previewConflicts(master.masterId(), master.owner(),
                    new OverrideConflictQueryRequest(FUTURE_DATE, FUTURE_DATE, ScheduleExceptionKind.DAY_OFF,
                            null, null, null));

            assertThat(preview.getStatusCode()).isEqualTo(HttpStatus.OK);
            JsonNode data = dataOf(preview);
            assertThat(data.path("totalCount").asInt())
                    .as("the TRUE conflict count must always be reported, even past the cap")
                    .isEqualTo(501);
            assertThat(data.path("conflicts"))
                    .as("the returned list must be capped at the service's 500-row result limit")
                    .hasSize(500);
            assertThat(data.path("truncated").asBoolean()).isTrue();
        }

        @Test
        @DisplayName("a handful of conflicts stays well under the cap -> truncated=false, totalCount==conflicts.size()")
        void should_notTruncate_when_conflictCountIsWellUnderTheCap() throws Exception {
            SeededMaster master = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            UUID masterServiceId = createMasterService(master, "Small batch");
            insertConfirmedBooking(client.userId(), master.masterId(), masterServiceId,
                    kyiv(FUTURE_DATE, LocalTime.of(9, 0)), kyiv(FUTURE_DATE, LocalTime.of(9, 30)));
            insertConfirmedBooking(client.userId(), master.masterId(), masterServiceId,
                    kyiv(FUTURE_DATE, LocalTime.of(10, 0)), kyiv(FUTURE_DATE, LocalTime.of(10, 30)));

            ResponseEntity<String> preview = previewConflicts(master.masterId(), master.owner(),
                    new OverrideConflictQueryRequest(FUTURE_DATE, FUTURE_DATE, ScheduleExceptionKind.DAY_OFF,
                            null, null, null));

            JsonNode data = dataOf(preview);
            assertThat(data.path("totalCount").asInt()).isEqualTo(2);
            assertThat(data.path("conflicts")).hasSize(2);
            assertThat(data.path("truncated").asBoolean()).isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 11 — Abuse backstop (>100 conflicts): 409 end-to-end on real Postgres (backend-QA
    // re-audit finding 4). The unit-level guard is already pinned by
    // ScheduleOverrideConflictServiceTest#should_rejectWithConflict_when_conflictCountExceedsAbuseCap
    // (mocked collaborators) — this closes the gap to the real write path, real Postgres, real
    // ownership/authz, real transaction rollback.
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Abuse backstop (>100 conflicts) — end-to-end, real DB")
    class AbuseBackstopEndToEnd {

        @Test
        @DisplayName("101 conflicting bookings + cancelOverlapping=true -> 409, every booking stays CONFIRMED "
                + "(the whole write is rejected atomically, not partially applied)")
        void should_reject409AndDeclineNothing_when_conflictCountExceedsAbuseCapOnRealDb() throws Exception {
            SeededMaster master = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            UUID masterServiceId = createMasterService(master, "Abuse-cap service");
            OffsetDateTime base = kyiv(FUTURE_DATE, LocalTime.of(0, 0));
            // 101 back-to-back one-minute CONFIRMED bookings (n=0..100) — one more than
            // ScheduleOverrideConflictService.MAX_CONFLICTS_PER_WRITE (100).
            jdbcTemplate.update(
                    "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                            + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                            + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                            + "SELECT gen_random_uuid(), ?, ?, ?, 'CONFIRMED', "
                            + "  ? + (n || ' minutes')::interval, ? + ((n+1) || ' minutes')::interval, "
                            + "  500.00, 1, 0, 'APP', NOW(), NOW() "
                            + "FROM generate_series(0, 100) AS n",
                    client.userId(), master.masterId(), masterServiceId, base, base);

            ResponseEntity<String> put = putOverride(master.masterId(), master.owner(),
                    new ScheduleOverrideRequest(FUTURE_DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL,
                            List.of(), null, true));

            assertThat(put.getStatusCode())
                    .as("101 conflicts exceeds the 100-per-write abuse cap even WITH cancelOverlapping consent "
                            + "— body: %s", put.getBody())
                    .isEqualTo(HttpStatus.CONFLICT);
            Long stillConfirmed = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM bookings WHERE master_id = ? AND status = 'CONFIRMED'",
                    Long.class, master.masterId());
            assertThat(stillConfirmed)
                    .as("the abuse backstop rejects the WHOLE write before any booking is touched")
                    .isEqualTo(101L);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'STATUS_CHANGED'", Long.class))
                    .as("a rejected write must never dispatch a single guest/client-facing SMS")
                    .isZero();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 12 — STATUS_CHANGED outbox row accounting, real DB (backend-QA re-audit finding 4; INVERTED
    // 2026-07-26 for D6, product decision reversal). This test used to pin exactly ONE
    // STATUS_CHANGED row per declined standalone booking; it now pins exactly ZERO — an
    // override-driven decline enqueues no notification at all, so this is now the regression guard
    // for D6 (a future reader must not "fix" this back to enqueueing).
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("STATUS_CHANGED outbox row accounting — real DB")
    class OutboxRowAccounting {

        @Test
        @DisplayName("3 standalone conflicting bookings declined -> ZERO STATUS_CHANGED rows (D6 — no "
                + "notification for override-driven declines)")
        void should_enqueueNoStatusChangedRows_onRealDb() throws Exception {
            SeededMaster master = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            UUID masterServiceId = createMasterService(master, "Outbox accounting service");
            UUID booking1 = insertConfirmedBooking(client.userId(), master.masterId(), masterServiceId,
                    kyiv(FUTURE_DATE, LocalTime.of(9, 0)), kyiv(FUTURE_DATE, LocalTime.of(9, 30)));
            UUID booking2 = insertConfirmedBooking(client.userId(), master.masterId(), masterServiceId,
                    kyiv(FUTURE_DATE, LocalTime.of(10, 0)), kyiv(FUTURE_DATE, LocalTime.of(10, 30)));
            UUID booking3 = insertConfirmedBooking(client.userId(), master.masterId(), masterServiceId,
                    kyiv(FUTURE_DATE, LocalTime.of(11, 0)), kyiv(FUTURE_DATE, LocalTime.of(11, 30)));

            ResponseEntity<String> put = putOverride(master.masterId(), master.owner(),
                    new ScheduleOverrideRequest(FUTURE_DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL,
                            List.of(), null, true));

            assertThat(put.getStatusCode())
                    .as("all three bookings must be declined — body: %s", put.getBody())
                    .isEqualTo(HttpStatus.OK);
            for (UUID bookingId : List.of(booking1, booking2, booking3)) {
                assertThat(countStatusChangedOutboxRows(bookingId))
                        .as("D6 (2026-07-26 product decision reversal): an override-driven decline enqueues "
                                + "NOTHING — the client discovers the cancellation from the booking's status "
                                + "alone; notifying for this flow is deferred to a later phase")
                        .isEqualTo(0L);
            }
        }

        private Long countStatusChangedOutboxRows(UUID bookingId) {
            return jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'STATUS_CHANGED' AND aggregate_id = ?",
                    Long.class, bookingId);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 13 — Guest (LINK) booking: actual decline via cancelOverlapping=true, not just the preview
    // projection (backend-QA re-audit finding 4). Section 9 above ("Preview projection") only ever
    // drove the guest booking through the read-only preview; this exercises the REAL write/decline
    // path for a guest booking end to end.
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Guest (LINK) booking — actual decline via the real write path")
    class GuestBookingActualDecline {

        @Test
        @DisplayName("a guest (LINK) conflicting booking is actually DECLINED (not just previewed) when "
                + "cancelOverlapping=true")
        void should_declineGuestBooking_when_cancelOverlappingIsTrueOnRealWrite() throws Exception {
            SeededMaster master = seedIndependentMaster();
            UUID masterServiceId = createMasterService(master, "Guest decline service");
            UUID guestBookingId = insertConfirmedGuestBooking(master.masterId(), masterServiceId,
                    kyiv(FUTURE_DATE, LocalTime.of(15, 0)), kyiv(FUTURE_DATE, LocalTime.of(16, 0)),
                    "Ihor", "Petrenko");

            ResponseEntity<String> put = putOverride(master.masterId(), master.owner(),
                    new ScheduleOverrideRequest(FUTURE_DATE, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL,
                            List.of(), null, true));

            assertThat(put.getStatusCode())
                    .as("the guest booking must be declined via the real write path — body: %s", put.getBody())
                    .isEqualTo(HttpStatus.OK);
            assertThat(bookingStatus(guestBookingId)).isEqualTo("DECLINED");
            assertThat(bookingCancellationReason(guestBookingId)).isEqualTo("PROVIDER_UNAVAILABLE");
            assertThat(bookingProviderComment(guestBookingId))
                    .as("D2 REVISED (2026-07-26 product decision reversal) — no master note on this path")
                    .isNull();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM notification_outbox WHERE event_type = 'STATUS_CHANGED' AND aggregate_id = ?",
                    Long.class, guestBookingId))
                    .as("D6 (2026-07-26 product decision reversal): the guest decline enqueues NOTHING either — "
                            + "no note, no notification, for any booking declined via this path")
                    .isEqualTo(0L);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 14 — Schedule-override-write rate limit sized for a realistic multi-day span (2026-07-26
    // product decision reversal, D6). This nested class REPLACES the former "decline rate-limit
    // budget exhausted" coverage here: ScheduleOverrideConflictService no longer charges a
    // proportional per-conflict token (the shared decline bucket + BookingDeclineRateLimiter it
    // used to charge from are both gone — an override-driven decline dispatches no notification at
    // all, so there is nothing left to bound proportionally to blast radius). PUT
    // /overrides/{date} now has its own dedicated bucket
    // (RateLimitConfig#scheduleOverrideWriteBuckets), sized so a legitimate month-long vacation
    // (~31 consecutive PUTs, one per date — the mobile client expands a multi-day save client-side)
    // never trips it. This closes the gap the filter-level unit test
    // (BookingRateLimitFilterTest#should_allowThirtyOneConsecutivePuts_when_actorSavesAMonthLongVacation)
    // cannot: that test builds the filter directly against a hand-built cache mirroring the
    // documented default; this drives the REAL autowired bean (default capacity, no
    // application-test.yml override — see that file's comment), real HTTP stack, real Postgres.
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Schedule-override-write rate limit — realistic multi-day span, real bucket, real DB")
    class ScheduleOverrideWriteRateLimit {

        @Test
        @DisplayName("31 consecutive PUTs for a month-long vacation span never trip the dedicated "
                + "override-write bucket")
        void should_notThrottle_when_actorSavesAThirtyOneDayVacationSpan() throws Exception {
            SeededMaster master = seedIndependentMaster();

            for (int i = 0; i < 31; i++) {
                LocalDate date = FUTURE_DATE.plusDays(i);
                ResponseEntity<String> put = putOverride(master.masterId(), master.owner(),
                        new ScheduleOverrideRequest(date, ScheduleExceptionKind.DAY_OFF, List.of()));

                assertThat(put.getStatusCode())
                        .as("PUT #%d of a 31-day vacation span must never be throttled by the dedicated "
                                + "override-write bucket — body: %s", i + 1, put.getBody())
                        .isEqualTo(HttpStatus.OK);
            }
        }
    }
}
