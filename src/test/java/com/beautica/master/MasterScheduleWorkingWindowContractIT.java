package com.beautica.master;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
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

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15.12 — the {@code windowStart}/{@code windowEnd} display-only working-window bounds across the
 * REAL HTTP boundary (filter chain → Jackson deserialization → Bean Validation → service → Postgres →
 * Jackson serialization), for all three schedule endpoints the mobile schedule editor talks to.
 *
 * <h2>Why this class exists — what the existing 15.12 suites could NOT observe</h2>
 * {@code MasterScheduleWorkingWindowIT} calls {@code MasterScheduleService} directly, and
 * {@code ScheduleDtoValidationTest} feeds a {@code Validator} hand-built record instances. Both construct
 * the DTOs in Java, so <b>neither ever produces or consumes a single byte of JSON</b>. They therefore
 * cannot catch:
 * <ul>
 *   <li>a request field that never binds (wrong wire name, missing setter path, a {@code @JsonIgnore}) —
 *       the server would accept the body, store {@code null}, and answer 200/201, looking healthy;</li>
 *   <li>a response field that never serializes — the round trip passes in Java and vanishes on the wire;</li>
 *   <li>the wire ENCODING of a {@link java.time.LocalTime} ({@code "09:00:00"} vs {@code "09:00"} vs an
 *       array), which the Flutter client's generated Dio model parses literally;</li>
 *   <li>whether the pre-15.12 payload — the body every SHIPPED app sends, with no window keys at all —
 *       still succeeds;</li>
 *   <li>whether a rejected window is a clean 400 with the standard error envelope rather than a 500.</li>
 * </ul>
 * That gap matters disproportionately here because the whole feature IS a wire contract, and
 * {@code beautica-mobile} generates its Dio client from the SpringDoc spec these very endpoints produce.
 *
 * <h2>Method</h2>
 * Request bodies are built as {@link LinkedHashMap}s and serialized by the application
 * {@link ObjectMapper} (never string concatenation — §Q16), so every JSON key is written out
 * <b>literally</b> in this file rather than being derived from the record's component names. A rename on
 * either side breaks these tests. Responses are asserted through {@link ObjectMapper#readTree} against
 * literal JSON paths for the same reason — deserializing back into the DTO would hide a wire-name
 * mismatch behind the very record that caused it.
 *
 * <p>The endpoints' authorization matrix is owned by {@code MasterScheduleSecurityIT}; the window's
 * business rules by {@code MasterScheduleWorkingWindowIT}; its invisibility to the slot engine by
 * {@code SlotCalculationScheduleIT}. None of that is duplicated here.
 */
@DisplayName("Phase 15.12 working window — HTTP wire contract (full stack, real Postgres)")
class MasterScheduleWorkingWindowContractIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/masters";

    /** Safe-margin future date: well clear of "today" so the no-past-edit guard never trips by accident. */
    private static final LocalDate FUTURE_FROM = LocalDate.now().plusDays(30);

    /** The first Monday on or after {@link #FUTURE_FROM} — ISO day-of-week 1, the weekday every test uses. */
    private static final LocalDate FUTURE_MONDAY =
            FUTURE_FROM.with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    // ── seeding & transport helpers ───────────────────────────────────────────────────

    private record SeededMaster(UUID masterId, UUID userId, String email) {
    }

    /** A solo master whose owning user is itself an {@code INDEPENDENT_MASTER} (self-manages its schedule). */
    private SeededMaster seedIndependentMaster() {
        UUID userId = UUID.randomUUID();
        String email = "wire-" + userId + "@beautica.test";
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', 'Fn', 'Ln', "
                        + "true, true)",
                userId, email);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, master_type, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 0, true, NOW(), NOW())",
                masterId, userId);
        return new SeededMaster(masterId, userId, email);
    }

    private HttpHeaders auth(SeededMaster m) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(
                jwtTokenProvider.generateAccessToken(m.userId(), m.email(), Role.INDEPENDENT_MASTER));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> send(String path, HttpMethod method, SeededMaster m, Object body)
            throws Exception {
        String payload = body == null ? null : objectMapper.writeValueAsString(body);
        return restTemplate.exchange(
                BASE + path, method, new HttpEntity<>(payload, auth(m)), String.class);
    }

    /** The {@code data} node of the {@code ApiResponse} envelope. */
    private JsonNode data(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody()).path("data");
    }

    private JsonNode envelope(ResponseEntity<String> response) throws Exception {
        return objectMapper.readTree(response.getBody());
    }

    // ── raw wire bodies: every JSON key is spelled out literally on purpose ────────────

    private static Map<String, Object> intervalJson(String startTime, String endTime) {
        Map<String, Object> interval = new LinkedHashMap<>();
        interval.put("startTime", startTime);
        interval.put("endTime", endTime);
        return interval;
    }

    /**
     * One INTERVAL weekday in raw wire form, WITHOUT any window keys. Tests that need a window
     * {@code put()} {@code "windowStart"}/{@code "windowEnd"} themselves, so an omitted window is
     * expressed by genuinely omitting the keys — exactly what a pre-15.12 client sends.
     */
    private static Map<String, Object> weeklyDayJson(int dayOfWeek, List<Map<String, Object>> intervals) {
        Map<String, Object> day = new LinkedHashMap<>();
        day.put("dayOfWeek", dayOfWeek);
        day.put("mode", "INTERVAL");
        day.put("intervals", intervals);
        return day;
    }

    private static Map<String, Object> weeklyBodyJson(Map<String, Object> day) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("validFrom", FUTURE_FROM.toString());
        body.put("validTo", null);
        body.put("days", List.of(day));
        return body;
    }

    /** A CUSTOM_HOURS INTERVAL override in raw wire form, WITHOUT any window keys (see above). */
    private static Map<String, Object> overrideBodyJson(
            LocalDate date, List<Map<String, Object>> intervals) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("date", date.toString());
        body.put("kind", "CUSTOM_HOURS");
        body.put("mode", "INTERVAL");
        body.put("intervals", intervals);
        body.put("cancelOverlapping", false);
        return body;
    }

    /** Asserts the JSON key exists on the wire AND is JSON {@code null} — presence matters to codegen. */
    private static void assertWindowKeysPresentAndNull(JsonNode node, String context) {
        assertThat(node.has("windowStart"))
                .as("%s — the windowStart KEY must still be emitted (an omitted key and a null value are "
                        + "different things to the generated Dio model), node=%s", context, node)
                .isTrue();
        assertThat(node.has("windowEnd")).as("%s — the windowEnd KEY must still be emitted", context).isTrue();
        assertThat(node.get("windowStart").isNull())
                .as("%s — windowStart must be null, actual=%s", context, node.get("windowStart"))
                .isTrue();
        assertThat(node.get("windowEnd").isNull())
                .as("%s — windowEnd must be null, actual=%s", context, node.get("windowEnd"))
                .isTrue();
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // POST/PUT /masters/{id}/weekly-schedules — the weekly-template surface
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Weekly template — POST/PUT /masters/{id}/weekly-schedules")
    class WeeklyTemplateWire {

        @Test
        @DisplayName("POST — a body carrying windowStart/windowEnd binds, persists and comes back as HH:mm:ss")
        void should_roundTripWindowOverHttp_when_weeklyDayCarriesWindowBounds() throws Exception {
            SeededMaster m = seedIndependentMaster();
            // The editor's day is 09:00–18:00 with a 09:00–10:00 break flush against the START, which
            // collapses to the single interval [10:00–18:00] — the exact shape the feature exists to save.
            Map<String, Object> day = weeklyDayJson(1, List.of(intervalJson("10:00:00", "18:00:00")));
            day.put("windowStart", "09:00:00");
            day.put("windowEnd", "18:00:00");

            ResponseEntity<String> response =
                    send("/" + m.masterId() + "/weekly-schedules", HttpMethod.POST, m, weeklyBodyJson(day));

            assertThat(response.getStatusCode())
                    .as("a window-carrying weekly template must be created, body=%s", response.getBody())
                    .isEqualTo(HttpStatus.CREATED);

            JsonNode monday = data(response).path("days").get(0);
            assertThat(monday.path("windowStart").asText())
                    .as("windowStart must reach the client as the literal ISO wall clock the Dio model "
                            + "parses — a bare \"09:00\" or a [9,0] array would break generated clients")
                    .isEqualTo("09:00:00");
            assertThat(monday.path("windowEnd").asText()).isEqualTo("18:00:00");
            assertThat(monday.path("intervals").get(0).path("startTime").asText())
                    .as("availability is untouched — still the single [10:00–18:00] interval")
                    .isEqualTo("10:00:00");

            UUID scheduleId = UUID.fromString(data(response).path("id").asText());
            Map<String, Object> stored = jdbcTemplate.queryForMap(
                    "SELECT window_start, window_end FROM weekly_schedule_day_windows "
                            + "WHERE schedule_id = ? AND day_of_week = 1", scheduleId);
            assertThat(stored.get("window_start"))
                    .as("the bound must reach Postgres, not just the echoed response")
                    .hasToString("09:00:00");
            assertThat(stored.get("window_end")).hasToString("18:00:00");
        }

        @Test
        @DisplayName("PUT — replacing the template over HTTP replaces the window, never accumulates a row")
        void should_roundTripNewWindowOverHttp_when_templateReplacedViaPut() throws Exception {
            SeededMaster m = seedIndependentMaster();
            Map<String, Object> created = weeklyDayJson(1, List.of(intervalJson("10:00:00", "18:00:00")));
            created.put("windowStart", "09:00:00");
            created.put("windowEnd", "18:00:00");
            ResponseEntity<String> createResponse =
                    send("/" + m.masterId() + "/weekly-schedules", HttpMethod.POST, m, weeklyBodyJson(created));
            UUID scheduleId = UUID.fromString(data(createResponse).path("id").asText());

            Map<String, Object> widened = weeklyDayJson(1, List.of(intervalJson("10:00:00", "18:00:00")));
            widened.put("windowStart", "08:00:00");
            widened.put("windowEnd", "20:00:00");
            ResponseEntity<String> response = send(
                    "/" + m.masterId() + "/weekly-schedules/" + scheduleId, HttpMethod.PUT, m,
                    weeklyBodyJson(widened));

            assertThat(response.getStatusCode())
                    .as("replacing a template through PUT must be a clean 200, body=%s", response.getBody())
                    .isEqualTo(HttpStatus.OK);
            JsonNode monday = data(response).path("days").get(0);
            assertThat(monday.path("windowStart").asText()).isEqualTo("08:00:00");
            assertThat(monday.path("windowEnd").asText()).isEqualTo("20:00:00");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM weekly_schedule_day_windows WHERE schedule_id = ?",
                    Integer.class, scheduleId))
                    .as("the delete-then-reinsert replace must leave exactly one row (uq_day_window_per_day)")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("POST — the pre-15.12 payload (no window keys at all) still succeeds and returns nulls")
        void should_return201WithNullWindowKeys_when_weeklyBodyOmitsWindowEntirely() throws Exception {
            SeededMaster m = seedIndependentMaster();
            // This is byte-for-byte what every SHIPPED mobile build sends. If it ever 400s, every
            // installed app loses the ability to save a schedule — the highest-blast-radius regression
            // this change can cause, and one no Java-level test can observe.
            Map<String, Object> legacyDay = weeklyDayJson(1, List.of(intervalJson("10:00:00", "18:00:00")));

            ResponseEntity<String> response = send(
                    "/" + m.masterId() + "/weekly-schedules", HttpMethod.POST, m, weeklyBodyJson(legacyDay));

            assertThat(response.getStatusCode())
                    .as("a body with no window keys is the legacy contract and must still be accepted, "
                            + "body=%s", response.getBody())
                    .isEqualTo(HttpStatus.CREATED);
            assertWindowKeysPresentAndNull(
                    data(response).path("days").get(0), "legacy weekly-template response");

            UUID scheduleId = UUID.fromString(data(response).path("id").asText());
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM weekly_schedule_day_windows WHERE schedule_id = ?",
                    Integer.class, scheduleId))
                    .as("no window is ever synthesized from min(start)..max(end) for a legacy payload")
                    .isZero();
        }

        @Test
        @DisplayName("POST — 400 with a populated errors map when only windowStart is sent")
        void should_return400WithFieldErrors_when_onlyWindowStartSent() throws Exception {
            SeededMaster m = seedIndependentMaster();
            Map<String, Object> halfWindow = weeklyDayJson(1, List.of(intervalJson("10:00:00", "18:00:00")));
            halfWindow.put("windowStart", "09:00:00");

            ResponseEntity<String> response = send(
                    "/" + m.masterId() + "/weekly-schedules", HttpMethod.POST, m, weeklyBodyJson(halfWindow));

            assertThat(response.getStatusCode())
                    .as("a half-specified window must be a Bean Validation 400, body=%s", response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            JsonNode envelope = envelope(response);
            assertThat(envelope.path("success").asBoolean())
                    .as("the error envelope must report success=false").isFalse();
            assertThat(envelope.path("errors").isObject())
                    .as("the top-level errors map is what the mobile ErrorMapperInterceptor renders, "
                            + "envelope=%s", envelope)
                    .isTrue();
            assertThat(envelope.path("errors").isEmpty())
                    .as("the errors map must actually carry the failing field, envelope=%s", envelope)
                    .isFalse();
        }

        @Test
        @DisplayName("POST — 400 (not 500) and nothing persisted when the window excludes an interval")
        void should_return400NotServerError_when_weeklyWindowDoesNotContainIntervals() throws Exception {
            SeededMaster m = seedIndependentMaster();
            // Containment is a SERVICE-layer rule (it needs the interval list), so it surfaces as a
            // BusinessException. If that ever escaped the handler chain the caller would see a 500.
            Map<String, Object> tooNarrow = weeklyDayJson(1, List.of(intervalJson("10:00:00", "18:00:00")));
            tooNarrow.put("windowStart", "11:00:00");
            tooNarrow.put("windowEnd", "18:00:00");

            ResponseEntity<String> response = send(
                    "/" + m.masterId() + "/weekly-schedules", HttpMethod.POST, m, weeklyBodyJson(tooNarrow));

            assertThat(response.getStatusCode())
                    .as("a containment violation must be a clean 400, never a 500, body=%s",
                            response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(envelope(response).path("success").asBoolean()).isFalse();
            assertThat(envelope(response).path("message").asText())
                    .as("BusinessException(BAD_REQUEST) is genericised by GlobalExceptionHandler — the "
                            + "internal rule text must not leak to the client")
                    .isEqualTo("Invalid request");

            ResponseEntity<String> reread =
                    send("/" + m.masterId() + "/weekly-schedules", HttpMethod.GET, m, null);
            assertThat(data(reread).isEmpty())
                    .as("a rejected window must leave no partial template behind, body=%s", reread.getBody())
                    .isTrue();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // PUT /masters/{id}/overrides/{date} — the per-date surface
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Per-date override — PUT /masters/{id}/overrides/{date}")
    class OverrideWire {

        @Test
        @DisplayName("PUT — a body carrying windowStart/windowEnd binds, persists and comes back as HH:mm:ss")
        void should_roundTripWindowOverHttp_when_overrideCarriesWindowBounds() throws Exception {
            SeededMaster m = seedIndependentMaster();
            LocalDate date = FUTURE_MONDAY;
            // 09:00–18:00 with a 17:00–18:00 break flush against the END → the single [09:00–17:00].
            Map<String, Object> body = overrideBodyJson(date, List.of(intervalJson("09:00:00", "17:00:00")));
            body.put("windowStart", "09:00:00");
            body.put("windowEnd", "18:00:00");

            ResponseEntity<String> response =
                    send("/" + m.masterId() + "/overrides/" + date, HttpMethod.PUT, m, body);

            assertThat(response.getStatusCode())
                    .as("a window-carrying override must be accepted, body=%s", response.getBody())
                    .isEqualTo(HttpStatus.OK);
            assertThat(data(response).path("windowStart").asText())
                    .as("the override endpoint routes through ScheduleOverrideConflictService — this pins "
                            + "that the orchestrator forwards the window instead of rebuilding the request")
                    .isEqualTo("09:00:00");
            assertThat(data(response).path("windowEnd").asText()).isEqualTo("18:00:00");
            assertThat(data(response).path("intervals").get(0).path("endTime").asText())
                    .as("availability is untouched — still the single [09:00–17:00] interval")
                    .isEqualTo("17:00:00");

            Map<String, Object> stored = jdbcTemplate.queryForMap(
                    "SELECT window_start, window_end FROM schedule_exceptions "
                            + "WHERE master_id = ? AND date = ?", m.masterId(), date);
            assertThat(stored.get("window_start")).hasToString("09:00:00");
            assertThat(stored.get("window_end")).hasToString("18:00:00");
        }

        @Test
        @DisplayName("PUT — the pre-15.12 override payload (no window keys) still succeeds and returns nulls")
        void should_return200WithNullWindowKeys_when_overrideBodyOmitsWindow() throws Exception {
            SeededMaster m = seedIndependentMaster();
            LocalDate date = FUTURE_MONDAY;

            ResponseEntity<String> response = send("/" + m.masterId() + "/overrides/" + date, HttpMethod.PUT,
                    m, overrideBodyJson(date, List.of(intervalJson("09:00:00", "17:00:00"))));

            assertThat(response.getStatusCode())
                    .as("the legacy override body must still be accepted, body=%s", response.getBody())
                    .isEqualTo(HttpStatus.OK);
            assertWindowKeysPresentAndNull(data(response), "legacy override response");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT window_start FROM schedule_exceptions WHERE master_id = ? AND date = ?",
                    String.class, m.masterId(), date))
                    .as("a legacy override stores no window — never a synthesized min(start)..max(end)")
                    .isNull();
        }

        @Test
        @DisplayName("PUT — 400 (not 500) when the override window excludes an interval")
        void should_return400NotServerError_when_overrideWindowDoesNotContainIntervals() throws Exception {
            SeededMaster m = seedIndependentMaster();
            LocalDate date = FUTURE_MONDAY;
            Map<String, Object> body = overrideBodyJson(date, List.of(intervalJson("10:00:00", "18:00:00")));
            body.put("windowStart", "11:00:00");
            body.put("windowEnd", "18:00:00");

            ResponseEntity<String> response =
                    send("/" + m.masterId() + "/overrides/" + date, HttpMethod.PUT, m, body);

            assertThat(response.getStatusCode())
                    .as("a containment violation on the override surface must also be a 400, body=%s",
                            response.getBody())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(envelope(response).path("success").asBoolean()).isFalse();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM schedule_exceptions WHERE master_id = ?",
                    Integer.class, m.masterId()))
                    .as("a rejected override must leave no row behind")
                    .isZero();
        }

        @Test
        @DisplayName("PUT — flipping CUSTOM_HOURS to DAY_OFF over HTTP clears the window (no chk_exc_window_kind 500)")
        void should_clearStoredWindowOverHttp_when_overrideFlipsToDayOff() throws Exception {
            SeededMaster m = seedIndependentMaster();
            LocalDate date = FUTURE_MONDAY;
            Map<String, Object> withWindow =
                    overrideBodyJson(date, List.of(intervalJson("09:00:00", "17:00:00")));
            withWindow.put("windowStart", "09:00:00");
            withWindow.put("windowEnd", "18:00:00");
            send("/" + m.masterId() + "/overrides/" + date, HttpMethod.PUT, m, withWindow);

            // The bare DAY_OFF body a real client sends. The row already holds a window, and the upsert
            // flushes mid-method — if the window were not cleared BEFORE that flush, the intermediate row
            // would violate chk_exc_window_kind and the caller would get a 409/500 instead of a 200.
            Map<String, Object> dayOff = new LinkedHashMap<>();
            dayOff.put("date", date.toString());
            dayOff.put("kind", "DAY_OFF");

            ResponseEntity<String> response =
                    send("/" + m.masterId() + "/overrides/" + date, HttpMethod.PUT, m, dayOff);

            assertThat(response.getStatusCode())
                    .as("flipping a window-carrying override to DAY_OFF must succeed, body=%s",
                            response.getBody())
                    .isEqualTo(HttpStatus.OK);
            assertThat(data(response).path("kind").asText()).isEqualTo("DAY_OFF");
            assertWindowKeysPresentAndNull(data(response), "DAY_OFF override response");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT window_start FROM schedule_exceptions WHERE master_id = ? AND date = ?",
                    String.class, m.masterId(), date))
                    .as("chk_exc_window_kind forbids a window on a DAY_OFF row — Postgres must hold null")
                    .isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // GET /masters/{id}/effective-schedule — the surface the mobile day editor hydrates from
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * The mobile day editor seeds itself from {@code GET /effective-schedule}, not from
     * {@code GET /overrides}, so the window has to survive JSON serialization on THIS endpoint or the
     * edge-flush break stays invisible in the app regardless of what the write endpoints echo back.
     */
    @Nested
    @DisplayName("Effective schedule — GET /masters/{id}/effective-schedule")
    class EffectiveScheduleWire {

        /** The single-date JSON node for {@code date} out of the range projection. */
        private JsonNode dayFor(SeededMaster m, LocalDate date) throws Exception {
            ResponseEntity<String> response = send(
                    "/" + m.masterId() + "/effective-schedule?from=" + date + "&to=" + date,
                    HttpMethod.GET, m, null);
            assertThat(response.getStatusCode())
                    .as("the owning master must be able to read its effective schedule, body=%s",
                            response.getBody())
                    .isEqualTo(HttpStatus.OK);
            return data(response).get(0);
        }

        private void postTemplate(SeededMaster m, Map<String, Object> day) throws Exception {
            ResponseEntity<String> response =
                    send("/" + m.masterId() + "/weekly-schedules", HttpMethod.POST, m, weeklyBodyJson(day));
            assertThat(response.getStatusCode())
                    .as("template seeding must succeed, body=%s", response.getBody())
                    .isEqualTo(HttpStatus.CREATED);
        }

        @Test
        @DisplayName("TEMPLATE day — the JSON carries the template weekday's stored window")
        void should_carryTemplateWindowInJson_when_sourceIsTemplate() throws Exception {
            SeededMaster m = seedIndependentMaster();
            Map<String, Object> day = weeklyDayJson(1, List.of(intervalJson("10:00:00", "18:00:00")));
            day.put("windowStart", "09:00:00");
            day.put("windowEnd", "18:00:00");
            postTemplate(m, day);

            JsonNode monday = dayFor(m, FUTURE_MONDAY);

            assertThat(monday.path("source").asText()).isEqualTo("TEMPLATE");
            assertThat(monday.path("windowStart").asText())
                    .as("the client derives its breaks as window MINUS intervals — 09:00–10:00 here")
                    .isEqualTo("09:00:00");
            assertThat(monday.path("windowEnd").asText()).isEqualTo("18:00:00");
            assertThat(monday.path("intervals").get(0).path("startTime").asText()).isEqualTo("10:00:00");
        }

        @Test
        @DisplayName("OVERRIDE_CUSTOM day — the JSON carries the override's window, not the template's")
        void should_carryOverrideWindowInJson_when_sourceIsOverrideCustom() throws Exception {
            SeededMaster m = seedIndependentMaster();
            Map<String, Object> templateDay = weeklyDayJson(1, List.of(intervalJson("10:00:00", "18:00:00")));
            templateDay.put("windowStart", "09:00:00");
            templateDay.put("windowEnd", "18:00:00");
            postTemplate(m, templateDay);
            Map<String, Object> override =
                    overrideBodyJson(FUTURE_MONDAY, List.of(intervalJson("12:00:00", "15:00:00")));
            override.put("windowStart", "12:00:00");
            override.put("windowEnd", "16:00:00");
            send("/" + m.masterId() + "/overrides/" + FUTURE_MONDAY, HttpMethod.PUT, m, override);

            JsonNode monday = dayFor(m, FUTURE_MONDAY);

            assertThat(monday.path("source").asText()).isEqualTo("OVERRIDE_CUSTOM");
            assertThat(monday.path("windowStart").asText())
                    .as("override beats template for the window exactly as it does for the intervals")
                    .isEqualTo("12:00:00");
            assertThat(monday.path("windowEnd").asText()).isEqualTo("16:00:00");
        }

        @Test
        @DisplayName("OVERRIDE_DAY_OFF day — the JSON carries null window bounds")
        void should_carryNullWindowInJson_when_sourceIsOverrideDayOff() throws Exception {
            SeededMaster m = seedIndependentMaster();
            Map<String, Object> templateDay = weeklyDayJson(1, List.of(intervalJson("10:00:00", "18:00:00")));
            templateDay.put("windowStart", "09:00:00");
            templateDay.put("windowEnd", "18:00:00");
            postTemplate(m, templateDay);
            Map<String, Object> dayOff = new LinkedHashMap<>();
            dayOff.put("date", FUTURE_MONDAY.toString());
            dayOff.put("kind", "DAY_OFF");
            send("/" + m.masterId() + "/overrides/" + FUTURE_MONDAY, HttpMethod.PUT, m, dayOff);

            JsonNode monday = dayFor(m, FUTURE_MONDAY);

            assertThat(monday.path("source").asText()).isEqualTo("OVERRIDE_DAY_OFF");
            assertWindowKeysPresentAndNull(monday,
                    "a closed day has no working window, and the template's must not bleed through");
        }

        @Test
        @DisplayName("legacy TEMPLATE day (no window stored) — the JSON carries null, never a synthesized span")
        void should_carryNullWindowInJson_when_templateStoredNoWindow() throws Exception {
            SeededMaster m = seedIndependentMaster();
            postTemplate(m, weeklyDayJson(1, List.of(intervalJson("10:00:00", "18:00:00"))));

            JsonNode monday = dayFor(m, FUTURE_MONDAY);

            assertThat(monday.path("source").asText()).isEqualTo("TEMPLATE");
            assertThat(monday.path("intervals").get(0).path("startTime").asText()).isEqualTo("10:00:00");
            assertWindowKeysPresentAndNull(monday,
                    "min(start)..max(end) is NEVER synthesized — that would assert 'no edge-flush break'");
        }
    }
}
