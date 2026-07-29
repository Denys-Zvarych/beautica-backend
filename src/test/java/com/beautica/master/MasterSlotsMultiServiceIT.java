package com.beautica.master;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.service.SlotCalculationService;
import com.beautica.common.TimeZones;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BE-2 — HTTP-level agreement/contract IT for the <b>multi-service single-visit</b> availability
 * endpoints. Drives the REAL controller wiring ({@link com.beautica.master.controller.MasterController}
 * repeatable {@code serviceId} binding + {@code @NotEmpty}/{@code @Size} validation), the REAL
 * {@link SlotCalculationService} List-overloads (Σ effective duration, 600-min cap, N=1 → legacy path),
 * and the REAL schedule resolver + slot calculator + booking subtraction, over a real Testcontainers
 * Postgres, through Spring Security ({@code isAuthenticated()}).
 *
 * <h2>What BE-2 changed and what this locks down</h2>
 * {@code GET /masters/{id}/slots} and {@code GET /masters/{id}/working-days} now accept a repeatable
 * {@code serviceId} param ({@code List<UUID>}, {@code @Size(max=10)}) and size each candidate slot to the
 * SUM of the selected services' EFFECTIVE durations (each service's own {@code bufferMinutesAfter} folded
 * in — the D4 policy), performed back-to-back by a single master. The contract this IT proves:
 * <ol>
 *   <li><b>Summed-duration sizing</b> — a two-service chain (60+15buf, 30+15buf → Σ=120) yields FEWER,
 *       narrower slots than either service alone; buffers ARE folded in (a bug that dropped them would
 *       add an extra late slot); no slot overruns the working window.</li>
 *   <li><b>Working-days ⇔ slots agreement</b> — the {@code serviceId}-aware day-gate reports a day
 *       unbookable exactly when the summed block does not fit (after CONFIRMED bookings are subtracted),
 *       and bookable when it does — matching {@code /slots} row-for-row.</li>
 *   <li><b>N=1 identical</b> — a 1-element {@code ?serviceId=A} is byte-for-byte the legacy single-service
 *       result (the controller routes it to the cached single-arg overload).</li>
 *   <li><b>600-min cap</b> — a chain whose Σ duration exceeds 600 min fails with the SAME 400 envelope the
 *       single over-long service returns — never a 500.</li>
 *   <li><b>Validation</b> — empty {@code serviceId} → 400; &gt;10 ids → 400; malformed UUID → 400, all as a
 *       clean {@code {success:false}} envelope with no stack trace.</li>
 * </ol>
 *
 * <h2>Clock &amp; auth</h2>
 * The REAL system clock is used (NOT a frozen bean): the JWT parser validates {@code exp} against real
 * wall-time, so a frozen-past clock would mint tokens the security filter sees as expired. All fixture days
 * are {@code today + N} (N≥7), far from the 15-min booking-lead cutoff, so "now" never trims a slot and the
 * assertions stay date-independent. Tokens are minted directly via {@link JwtTokenProvider} (the same bean
 * the filter verifies with), sidestepping the rate-limited {@code /auth/login} route.
 *
 * <h2>Seeding</h2>
 * Schedules/services/bookings are seeded via raw JDBC (as {@code BookingAvailabilityAgreementIT} /
 * {@code SlotCalculationScheduleIT} do) so exact intervals and bookings can be pinned. A second service for
 * one owner uses {@link AbstractIntegrationTest#resolveUnusedServiceTypeId} to dodge V121's
 * {@code ux_service_def_owner_service_type_active} unique index.
 */
@DisplayName("MasterSlotsMultiServiceIT — repeatable serviceId: Σ-duration slots ⇔ working-days, N=1 legacy, cap, validation (real Postgres, HTTP)")
class MasterSlotsMultiServiceIT extends AbstractIntegrationTest {

    private static final BigDecimal PRICE = new BigDecimal("100.00");
    private static final LocalTime WIN_START = LocalTime.of(9, 0);
    private static final LocalTime WIN_END = LocalTime.of(12, 0);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private SlotCalculationService slotCalculationService;

    @Autowired
    private Clock clock;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LocalDate today() {
        return LocalDate.now(clock.withZone(TimeZones.KYIV));
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // 1 — Summed-duration sizing: the chain is sized to Σ (incl. buffers); fewer/narrower slots
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("1 — /slots?serviceId=A&serviceId=B sizes each slot to Σ effective duration (75+45=120): fewer & narrower than either alone, buffers folded in, no window overrun")
    void should_sizeSlotsToSummedEffectiveDuration_when_twoServicesChained() {
        Master m = seedMaster();
        UUID svcA = addService(m, 60, 15); // effective 75
        UUID svcB = addService(m, 30, 15); // effective 45
        LocalDate day = today().plusDays(7);
        seedWorkingDay(m.masterId(), day);
        HttpHeaders auth = clientAuth();

        List<LocalTime> single60 = slotStarts(callSlots(m.masterId(), day, auth, svcA));
        List<LocalTime> single30 = slotStarts(callSlots(m.masterId(), day, auth, svcB));
        List<LocalTime> chained = slotStarts(callSlots(m.masterId(), day, auth, svcA, svcB));

        // Window 09:00–12:00, step 30. Single A (75): 09:00,09:30,10:00,10:30. Single B (45): +11:00.
        assertThat(single60)
                .as("single 60+15buf service: last 75-min slot starts 10:30 (ends 11:45)")
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0),
                        LocalTime.of(10, 30));
        assertThat(single30)
                .as("single 30+15buf service: last 45-min slot starts 11:00 (ends 11:45)")
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0),
                        LocalTime.of(10, 30), LocalTime.of(11, 0));

        // Σ=120: only 09:00, 09:30, 10:00 leave room for a 2h block before 12:00 (10:30+120=12:30 > 12:00).
        assertThat(chained)
                .as("the chain is sized to Σ effective duration 120 min → exactly 3 back-to-back slots")
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0));

        // Buffers ARE folded in: if they were dropped, Σ would be 90 (60+30) and 10:30 would fit
        // (10:30+90=12:00). Its absence proves the two 15-min buffers were summed into the block.
        assertThat(chained)
                .as("buffers folded into the block: a Σ=90 (buffers-dropped) bug would add a 10:30 slot")
                .doesNotContain(LocalTime.of(10, 30));

        // Fewer & narrower than either service alone — the block grew.
        assertThat(chained.size())
                .as("the chain offers fewer starts than either single service (bigger block)")
                .isLessThan(single60.size())
                .isLessThan(single30.size());
        assertThat(chained.get(chained.size() - 1))
                .as("the chain's last bookable start (10:00) is earlier than the single service's (10:30)")
                .isBefore(single60.get(single60.size() - 1));

        // No overrun: every chained slot's Σ-block ends at/before the 12:00 window close.
        for (LocalTime start : chained) {
            assertThat(start.plusMinutes(120))
                    .as("chained slot at %s must not overrun the 12:00 window", start)
                    .isBeforeOrEqualTo(WIN_END);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // 2 — working-days(serviceId=A&B) day-gate agrees with /slots, incl. booking-collision removal
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("2 — working-days(A&B) matches /slots row-for-row: a day whose only gaps are too small for the Σ=120 block is NOT bookable (but IS for a short service)")
    void should_agreeWorkingDaysWithSlots_forSummedBlock_underBookingCollision() {
        Master m = seedMaster();
        UUID svcA = addService(m, 60, 15); // effective 75
        UUID svcB = addService(m, 30, 15); // effective 45
        LocalDate booked = today().plusDays(8);
        LocalDate free = today().plusDays(9);
        UUID sched = seedSchedule(m.masterId(), today(), null);
        addInterval(sched, booked.getDayOfWeek().getValue(), WIN_START, WIN_END);
        addInterval(sched, free.getDayOfWeek().getValue(), WIN_START, WIN_END);
        // A CONFIRMED 10:00–11:00 booking on `booked` splits its 09:00–12:00 window into two 60-min gaps.
        UUID client = seedClient();
        insertBooking(m.masterId(), svcB, client, booked, LocalTime.of(10, 0), LocalTime.of(11, 0));
        HttpHeaders auth = clientAuth();

        // The Σ=120 chain fits NEITHER 60-min gap on `booked`, but fits the untouched `free` day.
        Map<LocalDate, Boolean> chainDays =
                workingDays(callWorkingDays(m.masterId(), booked, free, auth, svcA, svcB));
        assertThat(chainDays.get(booked))
                .as("no room for a 120-min block between the split gaps → day NOT bookable")
                .isFalse();
        assertThat(chainDays.get(free))
                .as("the free day fits the 120-min block → bookable")
                .isTrue();

        // /slots agrees row-for-row with the day-gate for the chain.
        assertThat(slotStarts(callSlots(m.masterId(), booked, auth, svcA, svcB)))
                .as("/slots agrees: nothing bookable on the collided day for the chain")
                .isEmpty();
        assertThat(slotStarts(callSlots(m.masterId(), free, auth, svcA, svcB)))
                .as("/slots agrees: the free day offers the 120-min starts")
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(9, 30), LocalTime.of(10, 0));

        // A single short (45-min) service DOES fit around the same booking → the gate is about the
        // Σ block size, not merely whether the day has any free time.
        Map<LocalDate, Boolean> shortDays =
                workingDays(callWorkingDays(m.masterId(), booked, free, auth, svcB));
        assertThat(shortDays.get(booked))
                .as("a 45-min service fits the residual gaps around the booking → day bookable")
                .isTrue();
        assertThat(slotStarts(callSlots(m.masterId(), booked, auth, svcB)))
                .as("/slots agrees: the short service is bookable before and after the 10:00–11:00 booking")
                .containsExactly(LocalTime.of(9, 0), LocalTime.of(11, 0));
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // 3 — N=1: a 1-element serviceId is byte-for-byte the legacy single-service path
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("3 — a 1-element ?serviceId=A returns exactly the legacy single-service result (HTTP N=1 == single-arg overload; List.of(A) core == single-arg core)")
    void should_returnLegacyResult_when_singleElementServiceIdList() {
        Master m = seedMaster();
        UUID svcA = addService(m, 60, 15);
        LocalDate day = today().plusDays(7);
        seedWorkingDay(m.masterId(), day);
        HttpHeaders auth = clientAuth();

        // HTTP single element ⇔ the legacy single-arg service overload (what the controller routes N=1 to).
        List<LocalTime> httpSingle = slotStarts(callSlots(m.masterId(), day, auth, svcA));
        List<AvailableSlotResponse> legacy = slotCalculationService.getAvailableSlots(m.masterId(), day, svcA);
        assertThat(httpSingle)
                .as("HTTP ?serviceId=A start times equal the legacy single-arg overload's")
                .isEqualTo(legacy.stream().map(s -> s.startsAt().toLocalTime()).toList());

        // The N-service core with a 1-element list is byte-for-byte the single-arg core (whole objects).
        assertThat(slotCalculationService.getAvailableSlots(m.masterId(), day, List.of(svcA)))
                .as("List.of(A) core output is identical (start AND end) to the single-arg overload")
                .isEqualTo(legacy);

        // Same for the working-days day-gate.
        Map<LocalDate, Boolean> httpDays =
                workingDays(callWorkingDays(m.masterId(), day, day, auth, svcA));
        boolean legacyDay = slotCalculationService
                .getBookableWorkingDays(m.masterId(), day, day, svcA).get(0).working();
        assertThat(httpDays.get(day))
                .as("HTTP working-days?serviceId=A equals the legacy single-arg day-gate")
                .isEqualTo(legacyDay)
                .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // 4 — 600-min cap: a Σ-over-long chain fails with the SAME 400 envelope as a single over-long
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("4 — a chain whose Σ duration exceeds 600 min → 400 {success:false}, the SAME status/envelope a single 800-min service returns (never 500)")
    void should_return400_when_summedDurationExceedsCap_matchingSingleOverLongPath() {
        Master m = seedMaster();
        UUID svc400a = addService(m, 400, 0);
        UUID svc400b = addService(m, 400, 0); // 400 + 400 = 800 > 600
        UUID svc800 = addService(m, 800, 0);  // single over-long path
        LocalDate day = today().plusDays(7);
        // No schedule needed — the cap is enforced BEFORE the schedule is resolved.
        HttpHeaders auth = clientAuth();

        ResponseEntity<String> chained = callSlots(m.masterId(), day, auth, svc400a, svc400b);
        ResponseEntity<String> single = callSlots(m.masterId(), day, auth, svc800);

        assertThat(chained.getStatusCode())
                .as("Σ=800 > 600 cap → 400, not 500")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(success(chained))
                .as("cap failure is a clean error envelope")
                .isFalse();
        assertThat(chained.getStatusCode())
                .as("the chain cap returns the SAME status the single over-long service does")
                .isEqualTo(single.getStatusCode());
        assertThat(single.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(success(single)).isFalse();
        assertNoStackTrace(chained);
        assertNoStackTrace(single);
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // 5 — HTTP-boundary validation (empty / >10 / malformed) — 400 clean envelope, no stack trace
    // ════════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("5a — /slots with no serviceId → 400 {success:false} (required repeatable param, empty not allowed)")
    void should_return400_when_serviceIdMissing() {
        HttpHeaders auth = clientAuth();
        ResponseEntity<String> resp = callRaw(
                "/api/v1/masters/" + UUID.randomUUID() + "/slots?date=" + today().plusDays(7), auth);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(success(resp)).isFalse();
        assertNoStackTrace(resp);
    }

    @Test
    @DisplayName("5b — /slots with 11 serviceId values → 400 {success:false} (@Size(max=10) breached)")
    void should_return400_when_moreThanTenServiceIds() {
        HttpHeaders auth = clientAuth();
        StringBuilder url = new StringBuilder(
                "/api/v1/masters/" + UUID.randomUUID() + "/slots?date=" + today().plusDays(7));
        for (int i = 0; i < 11; i++) {
            url.append("&serviceId=").append(UUID.randomUUID());
        }
        ResponseEntity<String> resp = callRaw(url.toString(), auth);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(success(resp)).isFalse();
        assertNoStackTrace(resp);
    }

    @Test
    @DisplayName("5c — /slots with a malformed serviceId UUID → 400 {success:false}, no stack trace leaked")
    void should_return400_when_serviceIdMalformed() {
        HttpHeaders auth = clientAuth();
        ResponseEntity<String> resp = callRaw(
                "/api/v1/masters/" + UUID.randomUUID() + "/slots?date=" + today().plusDays(7)
                        + "&serviceId=not-a-uuid",
                auth);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(success(resp)).isFalse();
        assertNoStackTrace(resp);
    }

    @Test
    @DisplayName("5d — working-days with a malformed serviceId UUID → 400 {success:false} (same boundary as /slots)")
    void should_return400_when_workingDaysServiceIdMalformed() {
        HttpHeaders auth = clientAuth();
        LocalDate from = today().plusDays(7);
        ResponseEntity<String> resp = callRaw(
                "/api/v1/masters/" + UUID.randomUUID() + "/working-days?from=" + from + "&to=" + from
                        + "&serviceId=not-a-uuid",
                auth);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(success(resp)).isFalse();
        assertNoStackTrace(resp);
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // HTTP helpers
    // ════════════════════════════════════════════════════════════════════════════════════════

    private ResponseEntity<String> callSlots(UUID masterId, LocalDate date, HttpHeaders headers,
                                             UUID... serviceIds) {
        StringBuilder url = new StringBuilder("/api/v1/masters/" + masterId + "/slots?date=" + date);
        for (UUID id : serviceIds) {
            url.append("&serviceId=").append(id);
        }
        return callRaw(url.toString(), headers);
    }

    private ResponseEntity<String> callWorkingDays(UUID masterId, LocalDate from, LocalDate to,
                                                   HttpHeaders headers, UUID... serviceIds) {
        StringBuilder url = new StringBuilder(
                "/api/v1/masters/" + masterId + "/working-days?from=" + from + "&to=" + to);
        for (UUID id : serviceIds) {
            url.append("&serviceId=").append(id);
        }
        return callRaw(url.toString(), headers);
    }

    private ResponseEntity<String> callRaw(String url, HttpHeaders headers) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** Sorted wall-clock (Kyiv) start times of a 200 /slots response. */
    private List<LocalTime> slotStarts(ResponseEntity<String> resp) {
        assertThat(resp.getStatusCode()).as("expected 200, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        JsonNode data = readJson(resp).path("data").path("slots");
        List<LocalTime> starts = new ArrayList<>();
        for (JsonNode slot : data) {
            starts.add(parseKyivLocalTime(slot.path("startsAt").asText()));
        }
        starts.sort(null);
        return starts;
    }

    /** {date → working} of a 200 /working-days response. */
    private Map<LocalDate, Boolean> workingDays(ResponseEntity<String> resp) {
        assertThat(resp.getStatusCode()).as("expected 200, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        Map<LocalDate, Boolean> days = new LinkedHashMap<>();
        for (JsonNode day : readJson(resp).path("data")) {
            days.put(LocalDate.parse(day.path("date").asText()), day.path("working").asBoolean());
        }
        return days;
    }

    private boolean success(ResponseEntity<String> resp) {
        return readJson(resp).path("success").asBoolean();
    }

    private void assertNoStackTrace(ResponseEntity<String> resp) {
        String body = resp.getBody() == null ? "" : resp.getBody();
        assertThat(body)
                .as("error envelope must not leak a stack trace or exception class")
                .doesNotContain("at com.beautica")
                .doesNotContain("Exception")
                .doesNotContain("trace");
    }

    private JsonNode readJson(ResponseEntity<String> resp) {
        try {
            return objectMapper.readTree(resp.getBody());
        } catch (Exception e) {
            throw new AssertionError("response body was not valid JSON: " + resp.getBody(), e);
        }
    }

    /** startsAt is a Kyiv-zoned ISO string ("…+03:00" / "…+02:00", possibly with a [Europe/Kyiv] id). */
    private static LocalTime parseKyivLocalTime(String iso) {
        try {
            return ZonedDateTime.parse(iso).toLocalTime();
        } catch (Exception e) {
            return OffsetDateTime.parse(iso).toLocalTime();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════════════
    // JDBC fixtures (raw — pins exact intervals/bookings the write path would reshape)
    // ════════════════════════════════════════════════════════════════════════════════════════

    private record Master(UUID masterId, UUID userId) {}

    private Master seedMaster() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', 'Ind', "
                        + "'Master', true, true)",
                userId, "ind-" + userId + "@beautica.test");
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, master_type, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 0, true, NOW(), NOW())",
                masterId, userId);
        return new Master(masterId, userId);
    }

    /** Active service definition + active master_services assignment; returns the masterServiceId. */
    private UUID addService(Master m, int durationMinutes, int bufferMinutes) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO service_definitions (id, owner_type, owner_id, name, "
                        + "service_type_id, base_duration_minutes, base_price, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) VALUES (?, 'INDEPENDENT_MASTER', ?, 'Svc', ?, "
                        + "?, ?, ?, true, NOW(), NOW())",
                serviceDefId, m.userId(),
                resolveUnusedServiceTypeId("INDEPENDENT_MASTER", m.userId()),
                durationMinutes, PRICE, bufferMinutes);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO master_services (id, master_id, service_def_id, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, m.masterId(), serviceDefId);
        return masterServiceId;
    }

    private UUID seedSchedule(UUID masterId, LocalDate validFrom, LocalDate validTo) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, NOW(), NOW())",
                scheduleId, masterId, validFrom, validTo);
        return scheduleId;
    }

    private void addInterval(UUID scheduleId, int isoDow, LocalTime start, LocalTime end) {
        jdbcTemplate.update("INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, "
                        + "end_time) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), scheduleId, isoDow, start, end);
    }

    /** A single WIN_START–WIN_END working interval on {@code day}'s ISO weekday, open-ended from today. */
    private void seedWorkingDay(UUID masterId, LocalDate day) {
        UUID sched = seedSchedule(masterId, today(), null);
        addInterval(sched, day.getDayOfWeek().getValue(), WIN_START, WIN_END);
    }

    private UUID seedClient() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'CLIENT', 'Cli', 'Ent', true, true)",
                id, "cli-" + id + "@beautica.test");
        return id;
    }

    private void insertBooking(UUID masterId, UUID masterServiceId, UUID clientId, LocalDate date,
                               LocalTime start, LocalTime end) {
        OffsetDateTime startsAt = date.atTime(start).atZone(TimeZones.KYIV).toOffsetDateTime();
        OffsetDateTime endsAt = date.atTime(end).atZone(TimeZones.KYIV).toOffsetDateTime();
        int minutes = (int) Duration.between(startsAt, endsAt).toMinutes();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, ?, ?, 0, NOW(), NOW())",
                UUID.randomUUID(), clientId, masterId, masterServiceId, startsAt, endsAt, PRICE, minutes);
    }

    /** Bearer headers for a freshly-seeded CLIENT — token minted directly (skips rate-limited login). */
    private HttpHeaders clientAuth() {
        UUID clientId = seedClient();
        String email = "cli-auth-" + clientId + "@beautica.test";
        String token = jwtTokenProvider.generateAccessToken(clientId, email, Role.CLIENT);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
