package com.beautica.master;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.ScheduleExceptionReason;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.WeeklyScheduleDayRequest;
import com.beautica.master.dto.WeeklyScheduleRequest;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.service.MasterScheduleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 15.6 — security & performance <b>acceptance</b> guard for the master-schedule endpoints,
 * driven over the FULL HTTP stack (filter chain → real {@code @PreAuthorize} SpEL → real
 * {@link com.beautica.common.security.AuthorizationService} → real Postgres).
 *
 * <h2>Why this exists vs the existing slice</h2>
 * {@code MasterScheduleControllerTest} ({@code @WebMvcTest}) mocks {@code AuthorizationService}, so it
 * only proves the predicate is <em>wired</em> — it never exercises the real authz logic against seeded
 * ownership rows. This IT mints real JWTs for seeded users (owning master, foreign master, salon
 * owner/admin, client) so {@code canReadMasterSchedule}/{@code canManageMasterSchedule} evaluate against
 * actual data. It closes the 15.6 acceptance items:
 * <ul>
 *   <li><b>S1</b> — authorization negative matrix: foreign master read/write → 403; CLIENT read → 403;
 *       anonymous → 401; foreign-{@code scheduleId} IDOR → 404 (no existence oracle); override is
 *       master-scoped.</li>
 *   <li><b>S2</b> — read-path bounds abuse → 400: {@code to-from > 366d}, far-future {@code to}
 *       (&gt; today+2y), and {@code from>to}.</li>
 *   <li><b>S3</b> — the override free-text {@code note} (potential SICK_DAY PII) never appears on the
 *       public {@code GET /api/v1/masters/{masterId}} profile NOR on any unauthorized read path.</li>
 * </ul>
 *
 * <p>Bean-Validation payload caps (≤7 days / ≤6 intervals / 2001-char note / control char) are already
 * pinned by {@code ScheduleDtoValidationTest}; the service-layer temporal invariants by
 * {@code MasterScheduleServiceIT}. This class deliberately does not duplicate them — it pins the gates
 * the other layers cannot reach (real SpEL authz over seeded data + HTTP status mapping).
 */
@DisplayName("MasterSchedule — 15.6 security & exposure (full HTTP stack, real authz, real Postgres)")
class MasterScheduleSecurityIT extends AbstractIntegrationTest {

    private static final String BASE = "/api/v1/masters";
    private static final LocalDate FUTURE_FROM = LocalDate.now().plusDays(30);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MasterScheduleService scheduleService;

    // ── seeding ───────────────────────────────────────────────────────────────────────

    private record Actor(UUID userId, String email, Role role) {
    }

    private record SeededMaster(UUID masterId, Actor owner) {
    }

    private Actor seedUser(Role role) {
        UUID userId = UUID.randomUUID();
        String email = role.name().toLowerCase() + "-" + userId + "@beautica.test";
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', ?, 'Fn', 'Ln', true, true)",
                userId, email, role.name());
        return new Actor(userId, email, role);
    }

    /** A solo master whose owning user is itself an INDEPENDENT_MASTER. */
    private SeededMaster seedIndependentMaster() {
        Actor owner = seedUser(Role.INDEPENDENT_MASTER);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO masters (id, user_id, master_type, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 0, true, NOW(), NOW())",
                masterId, owner.userId());
        return new SeededMaster(masterId, owner);
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

    private String weeklyBody() throws Exception {
        return objectMapper.writeValueAsString(new WeeklyScheduleRequest(
                FUTURE_FROM, null,
                List.of(new WeeklyScheduleDayRequest(1,
                        List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))))));
    }

    private ResponseEntity<String> get(String path, Actor actor) {
        HttpEntity<Void> req = actor == null ? null : new HttpEntity<>(auth(actor));
        return restTemplate.exchange(BASE + path, HttpMethod.GET, req, String.class);
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // S1 — authorization negative matrix (real authz, seeded rows)
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("S1 — authorization matrix")
    class AuthorizationMatrix {

        @Test
        @DisplayName("read: owning INDEPENDENT_MASTER → 200 on every schedule read endpoint")
        void should_allowOwner_onReads() {
            SeededMaster m = seedIndependentMaster();
            String today = LocalDate.now().toString();
            String week = LocalDate.now().plusDays(7).toString();

            assertThat(get("/" + m.masterId() + "/weekly-schedules", m.owner()).getStatusCode())
                    .isEqualTo(HttpStatus.OK);
            assertThat(get("/" + m.masterId() + "/overrides?from=" + today + "&to=" + week, m.owner())
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(get("/" + m.masterId() + "/effective-schedule?from=" + today + "&to=" + week, m.owner())
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("read: a FOREIGN master reading another master's schedule → 403")
        void should_forbidForeignMaster_onRead() {
            SeededMaster victim = seedIndependentMaster();
            SeededMaster attacker = seedIndependentMaster();

            ResponseEntity<String> resp =
                    get("/" + victim.masterId() + "/weekly-schedules", attacker.owner());

            assertThat(resp.getStatusCode())
                    .as("a master may never read another master's schedule")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("read: CLIENT on any schedule read endpoint → 403 (fast-path role denial)")
        void should_forbidClient_onRead() {
            SeededMaster m = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            String today = LocalDate.now().toString();
            String week = LocalDate.now().plusDays(7).toString();

            assertThat(get("/" + m.masterId() + "/weekly-schedules", client).getStatusCode())
                    .isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(get("/" + m.masterId() + "/effective-schedule?from=" + today + "&to=" + week, client)
                    .getStatusCode())
                    .as("CLIENT has no schedule-read path; only the public /slots endpoint")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("read: anonymous (no token) on a schedule read endpoint → 401")
        void should_rejectAnonymous_onRead() {
            SeededMaster m = seedIndependentMaster();

            ResponseEntity<String> resp = get("/" + m.masterId() + "/weekly-schedules", null);

            assertThat(resp.getStatusCode())
                    .as("the schedule read endpoints require authentication")
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("write: a FOREIGN master creating another master's weekly template → 403, nothing persisted")
        void should_forbidForeignMaster_onWrite() throws Exception {
            SeededMaster victim = seedIndependentMaster();
            SeededMaster attacker = seedIndependentMaster();

            ResponseEntity<String> resp = restTemplate.exchange(
                    BASE + "/" + victim.masterId() + "/weekly-schedules", HttpMethod.POST,
                    new HttpEntity<>(weeklyBody(), auth(attacker.owner())), String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(scheduleService.listWeeklySchedules(victim.masterId()))
                    .as("no schedule may be persisted for the victim after a forbidden write")
                    .isEmpty();
        }

        @Test
        @DisplayName("write: CLIENT creating a weekly template → 403 (fast-path role denial)")
        void should_forbidClient_onWrite() throws Exception {
            SeededMaster m = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);

            ResponseEntity<String> resp = restTemplate.exchange(
                    BASE + "/" + m.masterId() + "/weekly-schedules", HttpMethod.POST,
                    new HttpEntity<>(weeklyBody(), auth(client)), String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("IDOR: editing a scheduleId owned by ANOTHER master → 404 (no existence oracle, no cross-master mutation)")
        void should_return404_when_editingForeignScheduleId() throws Exception {
            SeededMaster a = seedIndependentMaster();
            SeededMaster b = seedIndependentMaster();
            // 'a' owns a real weekly template; resolve its server-assigned id from the DB.
            scheduleService.upsertWeeklySchedule(a.owner().userId(), a.masterId(), null,
                    new WeeklyScheduleRequest(FUTURE_FROM, null, List.of(new WeeklyScheduleDayRequest(1,
                            List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))))));
            UUID foreignScheduleId = resolveOnlyScheduleId(a);

            // 'b' owns its own masterId (passes the canManageMasterSchedule gate) but supplies 'a'-owned
            // scheduleId on the edit path — the service ownership re-check must 404, not mutate or 403.
            ResponseEntity<String> resp = restTemplate.exchange(
                    BASE + "/" + b.masterId() + "/weekly-schedules/" + foreignScheduleId, HttpMethod.PUT,
                    new HttpEntity<>(weeklyBody(), auth(b.owner())), String.class);

            assertThat(resp.getStatusCode())
                    .as("cross-master edit must surface as 404, never 403 or a successful mutation")
                    .isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(resolveOnlyScheduleId(a))
                    .as("the victim's schedule must be untouched (same id, no cross-master replace)")
                    .isEqualTo(foreignScheduleId);
        }

        @Test
        @DisplayName("override is master-scoped: a foreign actor clearing the victim's override → 403, override survives")
        void should_forbidForeignClear_onOverride() {
            SeededMaster victim = seedIndependentMaster();
            SeededMaster attacker = seedIndependentMaster();
            scheduleService.upsertOverride(victim.owner().userId(), victim.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF,
                            ScheduleExceptionReason.SICK_DAY, null, null));

            ResponseEntity<String> resp = restTemplate.exchange(
                    BASE + "/" + victim.masterId() + "/overrides/" + FUTURE_FROM, HttpMethod.DELETE,
                    new HttpEntity<>(auth(attacker.owner())), String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(scheduleService.resolveEffectiveDay(victim.masterId(), FUTURE_FROM).source())
                    .as("the victim's day-off override must survive the rejected clear")
                    .isEqualTo(com.beautica.master.dto.EffectiveDaySource.OVERRIDE_DAY_OFF);
        }

        private UUID resolveOnlyScheduleId(SeededMaster m) {
            // The management GET returns WeeklyScheduleResponse which has no id; resolve the persisted id
            // straight from the DB so the HTTP IDOR test pins the REAL cross-master schedule.
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM weekly_schedules WHERE master_id = ?", UUID.class, m.masterId());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // S2 — read-path bounds abuse → 400
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("S2 — read-range bounds")
    class ReadBounds {

        private ResponseEntity<String> effective(SeededMaster m, String from, String to) {
            return get("/" + m.masterId() + "/effective-schedule?from=" + from + "&to=" + to, m.owner());
        }

        @Test
        @DisplayName("effective-schedule with a span > 366 days → 400")
        void should_reject_when_spanExceeds366Days() {
            SeededMaster m = seedIndependentMaster();
            LocalDate from = LocalDate.now();
            LocalDate to = from.plusDays(367); // 367 days between endpoints > the 365 cap (366 inclusive)

            assertThat(effective(m, from.toString(), to.toString()).getStatusCode())
                    .as("an unbounded scan window must be rejected at the read boundary")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("effective-schedule with a far-future 'to' (> today+2y) → 400")
        void should_reject_when_toBeyondFarFutureCap() {
            SeededMaster m = seedIndependentMaster();
            LocalDate from = LocalDate.now();
            LocalDate to = LocalDate.now().plusYears(2).plusDays(2);

            assertThat(effective(m, from.toString(), to.toString()).getStatusCode())
                    .as("a 'to' past the two-year cap must be rejected")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("effective-schedule with from > to → 400")
        void should_reject_when_fromAfterTo() {
            SeededMaster m = seedIndependentMaster();
            LocalDate from = LocalDate.now().plusDays(10);
            LocalDate to = LocalDate.now().plusDays(3);

            assertThat(effective(m, from.toString(), to.toString()).getStatusCode())
                    .as("an inverted read window must be rejected")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        @DisplayName("overrides read with a span > 366 days → 400 (same guard on the override list path)")
        void should_reject_when_overridesSpanExceeds366Days() {
            SeededMaster m = seedIndependentMaster();
            LocalDate from = LocalDate.now();
            LocalDate to = from.plusDays(367);

            assertThat(get("/" + m.masterId() + "/overrides?from=" + from + "&to=" + to, m.owner())
                    .getStatusCode())
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // S3 — note (potential SICK_DAY PII) never leaks on public / unauthorized read paths
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("S3 — note never leaks")
    class NoteExposure {

        private static final String SECRET = "Surgery at Clinic X — strictly private";

        @Test
        @DisplayName("the SICK_DAY note is absent from the PUBLIC GET /masters/{id} profile body")
        void should_notLeakNote_onPublicProfile() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertOverride(m.owner().userId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF,
                            ScheduleExceptionReason.SICK_DAY, SECRET, null));

            // No auth header — this is the unauthenticated public profile endpoint.
            ResponseEntity<String> resp = restTemplate.getForEntity(
                    BASE + "/" + m.masterId(), String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("the public master profile must never echo a private schedule note")
                    .doesNotContain(SECRET)
                    .doesNotContain("\"note\"");
        }

        @Test
        @DisplayName("the SICK_DAY note is absent from the (authorized) public effective-schedule projection")
        void should_notLeakNote_onEffectiveScheduleRead() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertOverride(m.owner().userId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF,
                            ScheduleExceptionReason.SICK_DAY, SECRET, null));

            ResponseEntity<String> resp = get(
                    "/" + m.masterId() + "/effective-schedule?from=" + FUTURE_FROM + "&to=" + FUTURE_FROM,
                    m.owner());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("EffectiveDayResponse carries the reason but never the free-text note")
                    .contains("SICK_DAY")          // the reason IS exposed (non-PII enum)
                    .doesNotContain(SECRET)         // the free-text note is NOT
                    .doesNotContain("\"note\"");
        }
    }
}
