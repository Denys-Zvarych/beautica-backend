package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.TimeZones;
import com.beautica.master.dto.MasterWorkingDayResponse;
import com.beautica.master.service.MasterScheduleService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-context integration test for the booking-selection endpoint
 * {@code GET /api/v1/salons/{salonId}/services/{serviceDefId}/masters} (Phase 23.x), driving the
 * real {@link com.beautica.booking.service.BookingMasterService} + {@link MasterScheduleService}
 * schedule-usability gate through Spring Security (public matcher) and a real Testcontainers
 * Postgres seeded with the Flyway taxonomy.
 *
 * <h2>The bug this locks down</h2>
 * A master that is {@code is_active} + actively assigned to a service but has <b>zero usable
 * schedule</b> (no weekly template, or a template that resolves to no working day in the 180-day
 * booking horizon) must NEVER be offered as a booking target — otherwise the client picks them and
 * the calendar then shows every date disabled (the reported "Роман Пасічник" case). The gate reuses
 * {@link MasterScheduleService#getClientWorkingDays} — the exact resolver the client calendar reads
 * — so this list and the calendar can never disagree; the {@code antiDriftConsistency} test pins
 * that equivalence directly.
 *
 * <h2>Seeding strategy</h2>
 * Schedules are seeded via raw JDBC (not {@code MasterScheduleService.upsertWeeklySchedule}) because
 * the matrix requires shapes the write path deliberately rejects — a past {@code valid_to}, a
 * zero-interval template row, a {@code valid_from} beyond the booking horizon. All windows are
 * positioned relative to the service's own clock ({@link #today}) so the tests are date-independent.
 */
@DisplayName("BookingMasterServiceIT — bookable-master selection + schedule gate (Testcontainers, no auth)")
class BookingMasterServiceIT extends AbstractIntegrationTest {

    // The booking horizon mirrors BookingStartsAtValidator.MAX_DAYS_AHEAD (package-private, 180).
    private static final int HORIZON_DAYS = 180;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private MasterScheduleService masterScheduleService;

    @Autowired
    private Clock clock;

    /** "Today" exactly as {@code BookingMasterService} computes it (autowired clock, Kyiv zone). */
    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeZones.KYIV));
    }

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── HTTP + JSON helpers ─────────────────────────────────────────────────────

    private ResponseEntity<String> callEndpoint(UUID salonId, UUID serviceDefId) {
        return restTemplate.getForEntity(
                "/api/v1/salons/" + salonId + "/services/" + serviceDefId + "/masters", String.class);
    }

    /** The {@code data} array of a 200 response, as a list of {masterId, masterServiceId} JSON nodes. */
    private JsonNode dataArray(ResponseEntity<String> resp) {
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        try {
            JsonNode root = objectMapper.readTree(resp.getBody());
            assertThat(root.get("success").asBoolean()).isTrue();
            return root.get("data");
        } catch (Exception e) {
            throw new AssertionError("response body was not valid JSON: " + resp.getBody(), e);
        }
    }

    private List<UUID> masterIds(JsonNode data) {
        return java.util.stream.StreamSupport.stream(data.spliterator(), false)
                .map(n -> UUID.fromString(n.get("masterId").asText()))
                .toList();
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 1. Schedule-usability gate — inclusion / exclusion per schedule shape
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Schedule-usability gate")
    class ScheduleGate {

        @Test
        @DisplayName("bookable INTERVAL master is returned once with the correct masterServiceId")
        void should_includeMaster_when_activeAssignedWithIntervalWorkingDayInHorizon() {
            UUID salonId = seedActiveSalon();
            UUID serviceDefId = seedSalonService(salonId, true);
            Seed s = seedMasterWithAssignment(salonId, serviceDefId, true, true, "Олена", "Коваль");
            // INTERVAL working day on today's ISO weekday, open-ended from today → today is a working
            // day inside the [today, today+180] horizon.
            seedIntervalSchedule(s.masterId(), today(), null, today().getDayOfWeek().getValue(),
                    LocalTime.of(9, 0), LocalTime.of(17, 0));

            JsonNode data = dataArray(callEndpoint(salonId, serviceDefId));

            assertThat(data).hasSize(1);
            assertThat(UUID.fromString(data.get(0).get("masterId").asText()))
                    .as("the single bookable master is returned")
                    .isEqualTo(s.masterId());
            assertThat(UUID.fromString(data.get(0).get("masterServiceId").asText()))
                    .as("masterServiceId must be THIS master's active assignment id (the key the slot/"
                            + "booking-create calls need)")
                    .isEqualTo(s.masterServiceId());
        }

        @Test
        @DisplayName("scheduleless master (zero weekly_schedules) is excluded but a bookable sibling remains — the Роман Пасічник bug")
        void should_excludeScheduleless_whileKeepingBookableSibling() {
            UUID salonId = seedActiveSalon();
            UUID serviceDefId = seedSalonService(salonId, true);
            Seed bookable = seedMasterWithAssignment(salonId, serviceDefId, true, true, "Олена", "Коваль");
            seedIntervalSchedule(bookable.masterId(), today(), null, today().getDayOfWeek().getValue(),
                    LocalTime.of(9, 0), LocalTime.of(17, 0));
            // Active + assigned, but no schedule at all (the auto-created owner-master shape).
            Seed scheduleless = seedMasterWithAssignment(salonId, serviceDefId, true, true, "Роман", "Пасічник");

            List<UUID> ids = masterIds(dataArray(callEndpoint(salonId, serviceDefId)));

            assertThat(ids)
                    .as("the scheduled master is bookable; the scheduleless master is filtered out")
                    .containsExactly(bookable.masterId())
                    .doesNotContain(scheduleless.masterId());
        }

        @Test
        @DisplayName("master whose only schedule window's valid_to is entirely in the past is excluded")
        void should_excludeMaster_when_scheduleWindowIsEntirelyPast() {
            UUID salonId = seedActiveSalon();
            UUID serviceDefId = seedSalonService(salonId, true);
            Seed s = seedMasterWithAssignment(salonId, serviceDefId, true, true, "Past", "Window");
            // Window [today-60, today-30] never overlaps [today, today+180] → NO_SCHEDULE for every horizon date.
            seedIntervalSchedule(s.masterId(), today().minusDays(60), today().minusDays(30),
                    today().getDayOfWeek().getValue(), LocalTime.of(9, 0), LocalTime.of(17, 0));

            assertThat(masterIds(dataArray(callEndpoint(salonId, serviceDefId)))).isEmpty();
        }

        @Test
        @DisplayName("zero-interval schedule row covering the horizon is excluded — the gate reuses isWorkingDay, not EXISTS(schedule row)")
        void should_excludeMaster_when_scheduleRowHasNoIntervalsOrTimes() {
            UUID salonId = seedActiveSalon();
            UUID serviceDefId = seedSalonService(salonId, true);
            Seed s = seedMasterWithAssignment(salonId, serviceDefId, true, true, "Empty", "Template");
            // A weekly_schedules row that DOES cover the horizon, but with no working_intervals and no
            // discrete times for any day → every date resolves to a non-working TEMPLATE day. A naive
            // EXISTS(weekly_schedules) gate would wrongly include this master.
            seedEmptySchedule(s.masterId(), today(), null);

            assertThat(masterIds(dataArray(callEndpoint(salonId, serviceDefId))))
                    .as("a covering-but-empty schedule row must NOT make a master bookable")
                    .isEmpty();
        }

        @Test
        @DisplayName("EXPLICIT_TIMES-only day (discrete times, no intervals) makes the master bookable")
        void should_includeMaster_when_scheduleUsesExplicitTimesOnly() {
            UUID salonId = seedActiveSalon();
            UUID serviceDefId = seedSalonService(salonId, true);
            Seed s = seedMasterWithAssignment(salonId, serviceDefId, true, true, "Discrete", "Slots");
            // No intervals; only discrete bookable times on today's ISO weekday → working day in horizon.
            seedExplicitTimesSchedule(s.masterId(), today(), null, today().getDayOfWeek().getValue(),
                    LocalTime.of(10, 0), LocalTime.of(11, 30));

            assertThat(masterIds(dataArray(callEndpoint(salonId, serviceDefId))))
                    .as("an EXPLICIT_TIMES day is a usable schedule")
                    .containsExactly(s.masterId());
        }

        @Test
        @DisplayName("schedule whose valid_from is beyond today+180 is excluded now (future-only)")
        void should_excludeMaster_when_scheduleStartsBeyondHorizon() {
            UUID salonId = seedActiveSalon();
            UUID serviceDefId = seedSalonService(salonId, true);
            Seed s = seedMasterWithAssignment(salonId, serviceDefId, true, true, "Future", "Only");
            // valid_from = today+181 → no overlap with [today, today+180] → excluded until the horizon reaches it.
            seedIntervalSchedule(s.masterId(), today().plusDays(HORIZON_DAYS + 1), null,
                    today().getDayOfWeek().getValue(), LocalTime.of(9, 0), LocalTime.of(17, 0));

            assertThat(masterIds(dataArray(callEndpoint(salonId, serviceDefId)))).isEmpty();
        }

        @Test
        @DisplayName("master with an inactive master_services assignment is excluded (never a candidate)")
        void should_excludeMaster_when_assignmentInactive() {
            UUID salonId = seedActiveSalon();
            UUID serviceDefId = seedSalonService(salonId, true);
            // Fully schedulable, but the assignment itself is is_active=false → filtered by the candidate query.
            Seed s = seedMasterWithAssignment(salonId, serviceDefId, false, true, "Inactive", "Assignment");
            seedIntervalSchedule(s.masterId(), today(), null, today().getDayOfWeek().getValue(),
                    LocalTime.of(9, 0), LocalTime.of(17, 0));

            assertThat(masterIds(dataArray(callEndpoint(salonId, serviceDefId)))).isEmpty();
        }

        @Test
        @DisplayName("master with is_active=false is excluded (never a candidate) even with an active assignment + usable schedule")
        void should_excludeMaster_when_masterRowInactive() {
            UUID salonId = seedActiveSalon();
            UUID serviceDefId = seedSalonService(salonId, true);
            // Mirrors should_excludeMaster_when_assignmentInactive, but flips which leg of the candidate
            // query gate is violated: the assignment IS active, but the master row itself is
            // is_active=false. findBookableAssignmentsBySalonAndServiceDef filters on `m.isActive = true`
            // as its own predicate (independent of msa.isActive) — this pins that leg specifically.
            Seed s = seedMasterWithAssignment(salonId, serviceDefId, true, false, "Inactive", "MasterRow");
            seedIntervalSchedule(s.masterId(), today(), null, today().getDayOfWeek().getValue(),
                    LocalTime.of(9, 0), LocalTime.of(17, 0));

            assertThat(masterIds(dataArray(callEndpoint(salonId, serviceDefId))))
                    .as("an inactive master row must never surface as bookable, regardless of assignment "
                            + "or schedule state")
                    .isEmpty();
        }

        @Test
        @DisplayName("valid salon + service but all candidates scheduleless → 200 with [] (not 404)")
        void should_return200EmptyList_when_allCandidatesScheduleless() {
            UUID salonId = seedActiveSalon();
            UUID serviceDefId = seedSalonService(salonId, true);
            // One active, actively-assigned candidate with no schedule → a valid empty result, not a 404.
            seedMasterWithAssignment(salonId, serviceDefId, true, true, "Lonely", "Candidate");

            ResponseEntity<String> resp = callEndpoint(salonId, serviceDefId);

            assertThat(resp.getStatusCode())
                    .as("an empty bookable list is a 200 [], never a 404")
                    .isEqualTo(HttpStatus.OK);
            assertThat(masterIds(dataArray(resp))).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 2. 404 boundary — salon / service resolution (existence-oracle avoidance)
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("404 boundary")
    class NotFoundBoundary {

        @Test
        @DisplayName("service definition owned by a DIFFERENT salon → 404 (not an empty list)")
        void should_return404_when_serviceBelongsToAnotherSalon() {
            UUID salonA = seedActiveSalon();
            UUID salonB = seedActiveSalon();
            // Service belongs to salon B; the request targets salon A.
            UUID serviceOfB = seedSalonService(salonB, true);

            ResponseEntity<String> resp = callEndpoint(salonA, serviceOfB);

            assertThat(resp.getStatusCode())
                    .as("a cross-salon service must 404 (never leak as a valid empty roster)")
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("service definition owned by an INDEPENDENT_MASTER (ownerType != SALON) → 404")
        void should_return404_when_serviceOwnerTypeIsNotSalon() {
            UUID salonId = seedActiveSalon();
            UUID independentServiceDefId = seedIndependentMasterService();

            assertThat(callEndpoint(salonId, independentServiceDefId).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("inactive (soft-deleted) service definition → 404")
        void should_return404_when_serviceInactive() {
            UUID salonId = seedActiveSalon();
            UUID inactiveServiceDefId = seedSalonService(salonId, false);

            assertThat(callEndpoint(salonId, inactiveServiceDefId).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("unknown salon id → 404")
        void should_return404_when_salonDoesNotExist() {
            assertThat(callEndpoint(UUID.randomUUID(), UUID.randomUUID()).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("inactive salon → 404 even if the service row exists")
        void should_return404_when_salonInactive() {
            UUID inactiveSalonId = seedSalon(false);
            UUID serviceDefId = seedSalonService(inactiveSalonId, true);

            assertThat(callEndpoint(inactiveSalonId, serviceDefId).getStatusCode())
                    .as("an inactive salon is indistinguishable from a missing one — 404")
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 3. Anti-drift — endpoint inclusion ⇔ calendar working-days agree
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Anti-drift: booking list agrees with the calendar's working-days resolver")
    class AntiDrift {

        @Test
        @DisplayName("inclusion in the endpoint ⇔ getClientWorkingDays(today, today+180) has ≥1 working day — both directions")
        void should_matchWorkingDaysResolver_forBothIncludedAndExcludedMasters() {
            UUID salonId = seedActiveSalon();
            UUID serviceDefId = seedSalonService(salonId, true);
            Seed included = seedMasterWithAssignment(salonId, serviceDefId, true, true, "Included", "Master");
            seedIntervalSchedule(included.masterId(), today(), null, today().getDayOfWeek().getValue(),
                    LocalTime.of(9, 0), LocalTime.of(17, 0));
            Seed excluded = seedMasterWithAssignment(salonId, serviceDefId, true, true, "Excluded", "Master");
            // no schedule for `excluded`

            List<UUID> listed = masterIds(dataArray(callEndpoint(salonId, serviceDefId)));

            LocalDate from = today();
            LocalDate to = from.plusDays(HORIZON_DAYS);
            boolean includedHasWorkingDay = hasAnyWorkingDay(included.masterId(), from, to);
            boolean excludedHasWorkingDay = hasAnyWorkingDay(excluded.masterId(), from, to);

            // Lock both directions: the endpoint's membership predicate is EXACTLY "the calendar shows
            // ≥1 working day in the horizon". A future change to either code path that breaks this
            // equivalence fails here before it can silently mislead the mobile calendar.
            assertThat(listed.contains(included.masterId()))
                    .as("included master: endpoint membership == calendar has a working day")
                    .isEqualTo(includedHasWorkingDay)
                    .isTrue();
            assertThat(listed.contains(excluded.masterId()))
                    .as("excluded master: endpoint membership == calendar has a working day")
                    .isEqualTo(excludedHasWorkingDay)
                    .isFalse();
        }

        private boolean hasAnyWorkingDay(UUID masterId, LocalDate from, LocalDate to) {
            return masterScheduleService.getClientWorkingDays(masterId, from, to).stream()
                    .anyMatch(MasterWorkingDayResponse::working);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // 4. PII safety — the master.user JOIN FETCH must not leak into the response
    // ════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("PII safety")
    class PiiSafety {

        @Test
        @DisplayName("response JSON contains none of the master's email / phone / password hash")
        void should_notLeakUserPii_when_returningBookableMaster() {
            UUID salonId = seedActiveSalon();
            UUID serviceDefId = seedSalonService(salonId, true);
            String email = "leak-probe-" + System.nanoTime() + "@beautica.test";
            String phone = "+380671234567";
            String passwordHash = "$2a$10$leakProbeHashValue0000000000000000000000000000000000";
            Seed s = seedMasterWithAssignment(
                    salonId, serviceDefId, true, true, "Pii", "Probe", email, phone, passwordHash);
            seedIntervalSchedule(s.masterId(), today(), null, today().getDayOfWeek().getValue(),
                    LocalTime.of(9, 0), LocalTime.of(17, 0));

            ResponseEntity<String> resp = callEndpoint(salonId, serviceDefId);
            assertThat(masterIds(dataArray(resp))).containsExactly(s.masterId()); // master IS returned

            String body = resp.getBody();
            assertThat(body)
                    .as("the JOIN FETCH on master.user loads PII into memory; none of it may reach the wire")
                    .doesNotContain(email)
                    .doesNotContain(phone)
                    .doesNotContain(passwordHash);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════
    // Fixtures (raw JDBC — controls schedule shapes the write path rejects)
    // ════════════════════════════════════════════════════════════════════════════

    private record Seed(UUID masterId, UUID masterServiceId, UUID userId) {
    }

    private UUID seedActiveSalon() {
        return seedSalon(true);
    }

    private UUID seedSalon(boolean active) {
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'SALON_OWNER', true, true)",
                ownerId, "owner-" + System.nanoTime() + "@beautica.test");
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId, active);
        return salonId;
    }

    /** A SALON-owned service definition for {@code salonId}. */
    private UUID seedSalonService(UUID salonId, boolean active) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Manicure', ?, 60, 500.00, 0, ?, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId(), active);
        return serviceDefId;
    }

    /** A service owned by an INDEPENDENT_MASTER (ownerType != SALON) — used for the 404 boundary. */
    private UUID seedIndependentMasterService() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', true, true)",
                userId, "ind-" + System.nanoTime() + "@beautica.test");
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Solo Service', ?, 60, 400.00, 0, true, NOW(), NOW())",
                serviceDefId, userId, resolveServiceTypeId());
        return serviceDefId;
    }

    private Seed seedMasterWithAssignment(UUID salonId, UUID serviceDefId, boolean assignmentActive,
            boolean masterActive, String firstName, String lastName) {
        return seedMasterWithAssignment(salonId, serviceDefId, assignmentActive, masterActive,
                firstName, lastName,
                "m-" + System.nanoTime() + "@beautica.test", "+380500000000", "x");
    }

    private Seed seedMasterWithAssignment(UUID salonId, UUID serviceDefId, boolean assignmentActive,
            boolean masterActive, String firstName, String lastName,
            String email, String phone, String passwordHash) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, first_name, last_name, "
                        + "phone_number, professional_title, avatar_url, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', ?, ?, ?, ?, 'Майстер манікюру', "
                        + "'https://cdn.example/a.jpg', true, true)",
                userId, email, passwordHash, salonId, firstName, lastName, phone);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', 4.80, 12, ?, NOW(), NOW())",
                masterId, userId, salonId, masterActive);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId, assignmentActive);
        return new Seed(masterId, masterServiceId, userId);
    }

    // ── schedule seeders ────────────────────────────────────────────────────────

    private UUID insertWeeklySchedule(UUID masterId, LocalDate validFrom, LocalDate validTo) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                scheduleId, masterId, validFrom, validTo);
        return scheduleId;
    }

    private void seedIntervalSchedule(UUID masterId, LocalDate validFrom, LocalDate validTo,
            int isoDow, LocalTime start, LocalTime end) {
        UUID scheduleId = insertWeeklySchedule(masterId, validFrom, validTo);
        jdbcTemplate.update(
                "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                        + "VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), scheduleId, isoDow, start, end);
    }

    private void seedExplicitTimesSchedule(UUID masterId, LocalDate validFrom, LocalDate validTo,
            int isoDow, LocalTime... times) {
        UUID scheduleId = insertWeeklySchedule(masterId, validFrom, validTo);
        for (LocalTime t : times) {
            jdbcTemplate.update(
                    "INSERT INTO working_interval_times (id, schedule_id, day_of_week, slot_time) "
                            + "VALUES (?, ?, ?, ?)",
                    UUID.randomUUID(), scheduleId, isoDow, t);
        }
    }

    /** A weekly_schedules row covering the window but with NO intervals and NO discrete times. */
    private void seedEmptySchedule(UUID masterId, LocalDate validFrom, LocalDate validTo) {
        insertWeeklySchedule(masterId, validFrom, validTo);
    }

    /** Resolves a real, selectable {@code service_types.id} (V111 made this column NOT NULL). */
    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }
}
