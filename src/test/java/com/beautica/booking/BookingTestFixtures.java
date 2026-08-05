package com.beautica.booking;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.TimeZones;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared HTTP + JDBC fixture helpers for the Phase 26.x {@code GET /bookings/me} integration-test
 * family — {@link BookingMyBookingsMultiStatusFilterIT} (26.1), {@link BookingMyBookingsSortIT}
 * (26.3), {@link BookingMyBookingsDateRangeFilterIT} (26.2). Extracted per backend-qa's own 26.2
 * audit (LOW finding — these seven helpers were byte-identical copy-paste across all three IT
 * classes, crossing the three-occurrence "extraction overdue" threshold, Q4 in the QA playbook).
 *
 * <p>Mirrors the {@code com.beautica.service.ServiceTestFixtures} convention already established
 * in this codebase: a package-private, constructor-injected plain class instantiated per test in
 * {@code @BeforeEach} — deliberately NOT a shared base class, so each IT keeps extending {@link
 * com.beautica.AbstractIntegrationTest} directly and its Spring context wiring is untouched.
 *
 * <p><b>What did NOT move here</b> — each IT's {@code insertBooking}/{@code callMyBookings*}
 * helpers were checked and found to have genuinely diverged (different SQL columns bound, different
 * parameter shapes: multi-status filter's insert takes a {@code status} enum string, sort's insert
 * additionally takes a {@code price} string, date-range's insert is always {@code CONFIRMED} and
 * takes no status at all), so those stay local to their own IT rather than being force-merged.
 *
 * <p>{@link SalonFixture}/{@code createSalon}/{@code createSalonService} were extracted here per
 * backend-qa's Phase 26.5 audit (LOW finding — {@code BookingMyBookedDaysIT} became the second IT
 * to need a full salon graph, crossing the Q4 "2+ occurrences" threshold). The two prior copies
 * (in {@link BookingMyBookingsMultiStatusFilterIT} and {@code BookingMyBookedDaysIT}) were
 * byte-identical except for a cosmetic test-suite email prefix on the seeded salon master
 * ({@code "mbmsf-salon-master-"} vs {@code "mbbd-salon-master-"}) — that field is never read back
 * by any caller (both suites address the master purely via {@link SalonFixture#masterId()}), so it
 * was safe to unify on a single generic prefix rather than threading a caller-supplied one through.
 *
 * <p><b>{@code public} (cycle-2 audit finding 5).</b> The class and the handful of members below
 * used cross-package by {@code com.beautica.booking.service.AppointmentClientLegCancelConcurrencyIT}
 * / {@code AppointmentCrossPathTransitionConcurrencyIT} are {@code public} specifically so those
 * two concurrency regression tests can live in {@code com.beautica.booking.service} — the same
 * package as {@code AppointmentTransitionService} — which in turn lets the header-lock methods they
 * {@code @SpyBean} stay package-private instead of being forced {@code public} purely for test
 * reachability. This is test-only source ({@code src/test}), never shipped.
 */
public class BookingTestFixtures {

    static final String TEST_PASSWORD = "Str0ngP@ss1!";

    private final TestRestTemplate restTemplate;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;

    public BookingTestFixtures(
            TestRestTemplate restTemplate,
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            PasswordEncoder passwordEncoder
    ) {
        this.restTemplate = restTemplate;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    public UUID createIndependentMaster(String email) {
        UUID userId = createUser(email, "INDEPENDENT_MASTER", null);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    public UUID createIndependentMasterService(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId, resolveUnusedServiceTypeId("INDEPENDENT_MASTER", userId));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    /** Resolves a real, selectable {@code service_types.id} (V111 made this column NOT NULL). */
    UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    /**
     * Resolves a real, selectable {@code service_types.id} that {@code (ownerType, ownerId)} does
     * NOT already hold an ACTIVE {@code service_definitions} row for.
     *
     * <p><b>Why this exists.</b> V121 added
     * {@code ux_service_def_owner_service_type_active} — a partial UNIQUE index on
     * {@code (owner_type, owner_id, service_type_id) WHERE is_active}. {@link
     * #resolveServiceTypeId()} is deterministic ({@code ORDER BY st.name_uk LIMIT 1}), so every
     * definition seeded for one owner through it collided on the SAME type: the second insert for
     * a master now raises a unique violation. Seeding a multi-service owner is a legitimate,
     * widespread fixture need across the 26.x IT family (a serviceId filter needs two services to
     * filter BETWEEN, a per-row statement gate needs a distinct service per booking), so the fix
     * belongs here rather than in each caller.
     *
     * <p>Deliberately a {@code NOT EXISTS} against live rows rather than an in-memory
     * counter/OFFSET: it is stateless (correct no matter how many fixture instances a test builds),
     * it mirrors the invariant it is dodging exactly, and it degenerates to the SAME type
     * {@link #resolveServiceTypeId()} returns on an owner's first service — so single-service
     * fixtures keep their previous, deterministic behaviour.
     *
     * <p>Note the query is only sound because the caller INSERTs the definition before asking
     * again; every fixture here does. It throws (empty result) rather than silently reusing a type
     * if an owner ever exhausts the ~20 seeded selectable types — a loud failure, not a 409.
     */
    UUID resolveUnusedServiceTypeId(String ownerType, UUID ownerId) {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "AND NOT EXISTS (SELECT 1 FROM service_definitions sd "
                        + "                WHERE sd.owner_type = ? AND sd.owner_id = ? "
                        + "                  AND sd.service_type_id = st.id AND sd.is_active = TRUE) "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class, ownerType, ownerId);
    }

    public String tokenFor(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login must succeed for %s", email).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    public HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    List<UUID> extractIds(JsonNode root) {
        List<UUID> ids = new ArrayList<>();
        for (JsonNode row : root.path("data").path("data")) {
            ids.add(UUID.fromString(row.path("id").asText()));
        }
        return ids;
    }

    /** Bundle of a seeded salon graph so tests can address the owner, its salon, and its master. */
    record SalonFixture(UUID salonId, String ownerEmail, UUID masterId, String masterEmail) {}

    SalonFixture createSalon(String ownerEmail) {
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);

        String masterEmail = "salon-master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return new SalonFixture(salonId, ownerEmail, masterId, masterEmail);
    }

    // ── visit (appointment) fixtures ─────────────────────────────────────────
    //
    // Extracted here per the Q4 "extraction overdue" threshold: AppointmentReviewIT and
    // AppointmentTransitionIT each carried a byte-identical addWorkingHoursForEveryDay plus a
    // near-identical "create master + client + N services + post POST /appointments" block that
    // differed only in the generated email prefix, the service count, and how many of the
    // resulting ids each suite happened to keep. The record below is the UNION of the two local
    // {@code Visit} records, so both call sites read the accessors they already used.
    //
    // NOT moved: each suite's own assertion/inspection helpers (childIdsOf, itemStatus,
    // postVisitRaw, patchServiceDecline, …) — those are genuinely suite-specific and force-merging
    // them would couple two unrelated test surfaces.

    private static final String APPOINTMENTS_URL = "/api/v1/appointments";

    /**
     * A created CONFIRMED visit plus every id/token either caller needs to drive and inspect it —
     * the union of the two local {@code Visit} records this replaces.
     */
    public record VisitFixture(UUID id, String clientToken, UUID clientId, UUID masterId, String masterToken) {}

    /**
     * Creates an INDEPENDENT_MASTER + CLIENT and posts a CONFIRMED visit of {@code serviceCount}
     * chained services through the REAL {@code POST /appointments} endpoint — the one the mobile app
     * uses for EVERY booking. {@code serviceCount == 1} is deliberately supported and is the
     * dominant production shape: {@code CreateAppointmentRequest.masterServiceIds} is
     * {@code @NotEmpty}, not {@code size > 1}, so a single-service booking still gets a full
     * Appointment header with {@code bookings.appointment_id} set.
     *
     * @param emailPrefix per-suite, per-test discriminator woven into the generated emails (e.g.
     *                    {@code "appt-rev-sibling"}) so concurrent suites never collide on the
     *                    users unique index
     */
    public VisitFixture createConfirmedVisit(String emailPrefix, int serviceCount) throws Exception {
        String masterEmail = emailPrefix + "-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createIndependentMaster(masterEmail);
        String clientEmail = emailPrefix + "-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        List<UUID> serviceIds = new ArrayList<>(serviceCount);
        for (int i = 0; i < serviceCount; i++) {
            serviceIds.add(createIndependentMasterService(masterId));
        }
        addWorkingHoursForEveryDay(masterId);
        String clientToken = tokenFor(clientEmail);
        String masterToken = tokenFor(masterEmail);

        ZonedDateTime startsAt = ZonedDateTime.now(TimeZones.KYIV)
                .plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        // Body via ObjectMapper (§Q16) rather than string concatenation, so a fixture value can
        // never silently corrupt the JSON.
        String body = objectMapper.writeValueAsString(Map.of(
                "masterId", masterId.toString(),
                "masterServiceIds", serviceIds.stream().map(UUID::toString).toList(),
                "startsAt", startsAt.toOffsetDateTime().toString()));
        HttpHeaders headers = bearerHeaders(clientToken);
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> created = restTemplate.exchange(
                APPOINTMENTS_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
        assertThat(created.getStatusCode())
                .as("visit setup must succeed — body: %s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        JsonNode data = objectMapper.readTree(created.getBody()).path("data");

        return new VisitFixture(
                UUID.fromString(data.path("id").asText()), clientToken, clientId, masterId, masterToken);
    }

    /**
     * Open-ended weekly schedule with all seven ISO weekdays 08:00–20:00 so a near-future visit can
     * be booked on any day.
     */
    public void addWorkingHoursForEveryDay(UUID masterId) {
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

    UUID createSalonService(UUID salonId, UUID masterId) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveUnusedServiceTypeId("SALON", salonId));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }
}
