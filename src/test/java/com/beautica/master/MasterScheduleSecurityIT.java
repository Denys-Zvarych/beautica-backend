package com.beautica.master;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.WeeklyScheduleDayRequest;
import com.beautica.master.dto.WeeklyScheduleRequest;
import com.beautica.master.dto.WeeklyScheduleResponse;
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

    /** An invited SALON_MASTER-type master inside a fresh salon — for exercising the SALON
     *  management branch of {@code canManageMasterSchedule} (owner/admin access via
     *  {@code hasManagementAccess}, as opposed to the INDEPENDENT_MASTER self-ownership branch
     *  {@link #seedIndependentMaster} exercises). */
    private record SeededSalonMaster(UUID masterId, UUID salonId, Actor salonOwner) {
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

    /**
     * An invited {@code SALON_MASTER}-type master (never self-managing) inside a fresh salon owned
     * by a freshly-seeded {@code SALON_OWNER}. Used by the S5 positive-salon-branch cases (finding
     * 2, 2026-07-26 backend-QA re-audit): this master's {@code canManageMasterSchedule} verdict
     * comes ONLY from {@code hasManagementAccess(salonId, actorId, actorRole)}, so it exercises the
     * SAME salon-management branch for both a {@code SALON_OWNER} and a {@code SALON_ADMIN} actor —
     * unlike a {@code SALON_OWNER}-type (owner-operated) master, whose branch checks direct owner-id
     * equality instead and would never reach {@code hasManagementAccess} at all.
     */
    private SeededSalonMaster seedSalonInvitedMaster() {
        Actor owner = seedUser(Role.SALON_OWNER);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, owner.userId(), "S5 positive-branch salon " + salonId);
        Actor invitedMasterUser = seedUser(Role.SALON_MASTER);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', 0, true, NOW(), NOW())",
                masterId, invitedMasterUser.userId(), salonId);
        return new SeededSalonMaster(masterId, salonId, owner);
    }

    /** An invited {@code SALON_ADMIN} whose {@code users.salon_id} points at {@code salonId}. */
    private Actor seedSalonAdmin(UUID salonId) {
        UUID userId = UUID.randomUUID();
        String email = "salonadmin-" + userId + "@beautica.test";
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, salon_id, first_name, "
                        + "last_name, is_active, email_verified) VALUES (?, ?, 'x', 'SALON_ADMIN', ?, 'Fn', 'Ln', "
                        + "true, true)",
                userId, email, salonId);
        return new Actor(userId, email, Role.SALON_ADMIN);
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
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF, null));

            ResponseEntity<String> resp = restTemplate.exchange(
                    BASE + "/" + victim.masterId() + "/overrides/" + FUTURE_FROM, HttpMethod.DELETE,
                    new HttpEntity<>(auth(attacker.owner())), String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(scheduleService.resolveEffectiveDay(victim.masterId(), FUTURE_FROM).source())
                    .as("the victim's day-off override must survive the rejected clear")
                    .isEqualTo(com.beautica.master.dto.EffectiveDaySource.OVERRIDE_DAY_OFF);
        }

        private UUID resolveOnlyScheduleId(SeededMaster m) {
            // The management GET now returns WeeklyScheduleResponse.id, so resolve the persisted id from the
            // wire payload itself — the same path a real editor uses to target PUT (no DB-side shortcut).
            return readOnlyListedScheduleId(m, m.owner());
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // S5 — POST /overrides/conflicts authorization (2026-07-26 design / backend-security audit
    // finding 6): this preview is deliberately gated by canManageMasterSchedule (MANAGE, not READ)
    // because it exposes client/guest identities — a read-only SALON_MASTER (who passes
    // canReadMasterSchedule) must still be denied. No case for this endpoint previously existed
    // anywhere in the suite, so a future regression swapping the controller's @PreAuthorize gate to
    // canReadMasterSchedule would have passed silently. Mirrors the shape of the S1 read/write
    // matrix above (foreign master, role denial, anonymous, no existence oracle).
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("S5 — POST /overrides/conflicts authorization (MANAGE gate, not READ)")
    class OverrideConflictsPreviewAuthorization {

        private String previewBody() throws Exception {
            return objectMapper.writeValueAsString(new com.beautica.master.dto.OverrideConflictQueryRequest(
                    FUTURE_FROM, FUTURE_FROM, ScheduleExceptionKind.DAY_OFF, null, null, null));
        }

        private HttpHeaders jsonOnlyHeaders() {
            HttpHeaders h = new HttpHeaders();
            h.setContentType(MediaType.APPLICATION_JSON);
            return h;
        }

        private ResponseEntity<String> preview(UUID masterId, Actor actor) throws Exception {
            HttpEntity<String> req = new HttpEntity<>(
                    previewBody(), actor == null ? jsonOnlyHeaders() : auth(actor));
            return restTemplate.exchange(
                    BASE + "/" + masterId + "/overrides/conflicts", HttpMethod.POST, req, String.class);
        }

        @Test
        @DisplayName("a FOREIGN master previewing another master's conflicts → 403")
        void should_forbidForeignMaster_onPreview() throws Exception {
            SeededMaster victim = seedIndependentMaster();
            SeededMaster attacker = seedIndependentMaster();

            ResponseEntity<String> resp = preview(victim.masterId(), attacker.owner());

            assertThat(resp.getStatusCode())
                    .as("a master may never preview another master's booking conflicts")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("a read-only SALON_MASTER previewing conflicts → 403 (MANAGE gate, not READ)")
        void should_forbidSalonMaster_onPreview() throws Exception {
            SeededMaster m = seedIndependentMaster();
            Actor salonMaster = seedUser(Role.SALON_MASTER);

            ResponseEntity<String> resp = preview(m.masterId(), salonMaster);

            assertThat(resp.getStatusCode())
                    .as("canManageMasterSchedule short-circuits SALON_MASTER — a regression to "
                            + "canReadMasterSchedule would let a read-only calendar viewer see client names")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("CLIENT previewing conflicts → 403")
        void should_forbidClient_onPreview() throws Exception {
            SeededMaster m = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);

            ResponseEntity<String> resp = preview(m.masterId(), client);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        @DisplayName("anonymous previewing conflicts → 401")
        void should_rejectAnonymous_onPreview() throws Exception {
            SeededMaster m = seedIndependentMaster();

            ResponseEntity<String> resp = preview(m.masterId(), null);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("a nonexistent masterId → the SAME 403 as a foreign master (no existence oracle)")
        void should_return403_when_masterIdDoesNotExist() throws Exception {
            SeededMaster owner = seedIndependentMaster();

            ResponseEntity<String> resp = preview(UUID.randomUUID(), owner.owner());

            assertThat(resp.getStatusCode())
                    .as("a missing master must be indistinguishable from a foreign one")
                    .isEqualTo(HttpStatus.FORBIDDEN);
        }

        // ────────────────────────────────────────────────────────────────────────────
        // Positive salon-management branch (backend-QA re-audit finding 2, 2026-07-26): every
        // case above proves the negative matrix, but canManageMasterSchedule's SALON branch
        // (hasManagementAccess — as opposed to the INDEPENDENT_MASTER self-ownership branch the
        // rest of this suite exercises) was never positively exercised for THIS endpoint. Not a
        // live hole (the gate is shared with every other master-schedule write endpoint, which
        // already has salon-branch coverage elsewhere), but a new route deserves its own positive
        // proof rather than relying on that sharing implicitly.
        // ────────────────────────────────────────────────────────────────────────────

        @Test
        @DisplayName("a SALON_OWNER previewing conflicts for a master in THEIR OWN salon → 200 (positive salon branch)")
        void should_allowSalonOwnerOfSameSalon_onPreview() throws Exception {
            SeededSalonMaster m = seedSalonInvitedMaster();

            ResponseEntity<String> resp = preview(m.masterId(), m.salonOwner());

            assertThat(resp.getStatusCode())
                    .as("canManageMasterSchedule's salon-management branch (hasManagementAccess) must ALLOW "
                            + "the SALON_OWNER of the master's own salon — never positively exercised for this "
                            + "endpoint before this test")
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("a SALON_ADMIN previewing conflicts for a master in THEIR salon → 200 (positive salon branch)")
        void should_allowSalonAdminOfSameSalon_onPreview() throws Exception {
            SeededSalonMaster m = seedSalonInvitedMaster();
            Actor admin = seedSalonAdmin(m.salonId());

            ResponseEntity<String> resp = preview(m.masterId(), admin);

            assertThat(resp.getStatusCode())
                    .as("canManageMasterSchedule's salon-management branch (hasManagementAccess) must ALLOW "
                            + "a SALON_ADMIN of the master's own salon — never positively exercised for this "
                            + "endpoint before this test")
                    .isEqualTo(HttpStatus.OK);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // S4 — read identity round-trip: GET exposes id → PUT updates in place (no duplicate)
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("S4 — weekly-schedule id round-trip")
    class IdRoundTrip {

        /**
         * Regression for the missing-{@code id} gap that produced the duplicate-window bug:
         * {@code GET /weekly-schedules} returned windows with no identity, so a mobile client that
         * reloaded could not target {@code PUT /weekly-schedules/{scheduleId}} — it re-POSTed and tripped
         * {@code MasterScheduleService.assertNoWindowOverlap} ({@code BusinessException} "...overlaps an
         * existing window starting..."). This pins the full HTTP loop: create → reload → PUT the listed id
         * → 200, same id, updated in place, exactly one window persisted.
         */
        @Test
        @DisplayName("GET exposes a non-null id; PUT-ing it updates in place (200, same id, no duplicate, no overlap)")
        void should_exposeIdAndUpdateInPlace_when_reEditingAfterReload() throws Exception {
            SeededMaster m = seedIndependentMaster();

            // Create the initial window over HTTP.
            ResponseEntity<String> created = restTemplate.exchange(
                    BASE + "/" + m.masterId() + "/weekly-schedules", HttpMethod.POST,
                    new HttpEntity<>(weeklyBody(), auth(m.owner())), String.class);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            // Reload the list and read the id straight off the wire — the very path the mobile client uses.
            UUID listedId = readOnlyListedScheduleId(m, m.owner());
            assertThat(listedId)
                    .as("GET /weekly-schedules must expose a non-null window id for editors to target PUT")
                    .isNotNull();

            // PUT the SAME window (overlapping itself) targeting the listed id — the original failure was a
            // re-POST here, which 422'd on the overlap guard. With the id, this updates in place.
            String updatedBody = objectMapper.writeValueAsString(new WeeklyScheduleRequest(
                    FUTURE_FROM, null,
                    List.of(new WeeklyScheduleDayRequest(2,
                            List.of(new WorkIntervalDto(LocalTime.of(10, 0), LocalTime.of(16, 0)))))));
            ResponseEntity<String> put = restTemplate.exchange(
                    BASE + "/" + m.masterId() + "/weekly-schedules/" + listedId, HttpMethod.PUT,
                    new HttpEntity<>(updatedBody, auth(m.owner())), String.class);

            assertThat(put.getStatusCode())
                    .as("re-editing the reloaded id must succeed in place, not trip the overlap guard")
                    .isEqualTo(HttpStatus.OK);
            assertThat(objectMapper.readTree(put.getBody()).path("data").path("id").asText())
                    .as("the PUT response must echo the same window id, not a freshly created one")
                    .isEqualTo(listedId.toString());
            assertThat(scheduleService.listWeeklySchedules(m.masterId()))
                    .as("exactly one window must remain — updated in place, never duplicated")
                    .singleElement()
                    .extracting(WeeklyScheduleResponse::id).isEqualTo(listedId);
        }
    }

    /** Reads the master's single weekly window id from the management GET wire payload. */
    private UUID readOnlyListedScheduleId(SeededMaster m, Actor reader) {
        try {
            ResponseEntity<String> resp = get("/" + m.masterId() + "/weekly-schedules", reader);
            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            var data = objectMapper.readTree(resp.getBody()).path("data");
            assertThat(data).as("the listed payload must contain exactly one window").hasSize(1);
            return UUID.fromString(data.get(0).path("id").asText());
        } catch (Exception e) {
            throw new IllegalStateException("failed to read listed schedule id", e);
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
    // S5 — Phase 15.11: CLIENT-safe working-days endpoint
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("S5 — GET working-days (CLIENT-safe boolean day-gating)")
    class WorkingDaysEndpoint {

        private ResponseEntity<String> workingDays(SeededMaster m, String from, String to, Actor actor) {
            return get("/" + m.masterId() + "/working-days?from=" + from + "&to=" + to, actor);
        }

        @Test
        @DisplayName("CLIENT reading working-days -> 200 (contrast with S1: CLIENT is 403 on effective-schedule)")
        void should_allowClient_unlikeEffectiveSchedule() {
            SeededMaster m = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            String today = LocalDate.now().toString();
            String week = LocalDate.now().plusDays(7).toString();

            assertThat(workingDays(m, today, week, client).getStatusCode())
                    .as("CLIENT must be able to read boolean working-day gating for the calendar")
                    .isEqualTo(HttpStatus.OK);
        }

        @Test
        @DisplayName("response body exposes only date + working — no intervals/times/source leak to CLIENT")
        void should_exposeOnlyDateAndWorking_toClient() throws Exception {
            SeededMaster m = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            scheduleService.upsertWeeklySchedule(m.owner().userId(), m.masterId(), null,
                    new WeeklyScheduleRequest(FUTURE_FROM, null, List.of(new WeeklyScheduleDayRequest(
                            FUTURE_FROM.getDayOfWeek().getValue(),
                            List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0)))))));

            ResponseEntity<String> resp =
                    workingDays(m, FUTURE_FROM.toString(), FUTURE_FROM.toString(), client);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            var day = objectMapper.readTree(resp.getBody()).path("data").get(0);
            assertThat(day.path("date").asText()).isEqualTo(FUTURE_FROM.toString());
            assertThat(day.path("working").asBoolean()).isTrue();
            assertThat(day.has("intervals")).as("no interval detail may reach CLIENT").isFalse();
            assertThat(day.has("times")).as("no discrete-time detail may reach CLIENT").isFalse();
            assertThat(day.has("source")).as("no schedule provenance may reach CLIENT").isFalse();
            assertThat(resp.getBody())
                    .doesNotContain("\"startTime\"")
                    .doesNotContain("\"endTime\"");
        }

        @Test
        @DisplayName("anonymous (no token) -> 401")
        void should_rejectAnonymous() {
            SeededMaster m = seedIndependentMaster();

            assertThat(workingDays(m, LocalDate.now().toString(), LocalDate.now().plusDays(1).toString(), null)
                    .getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        @DisplayName("span > 366 days -> 400 (same range-cap guard the read endpoints already enforce, S2)")
        void should_reject_when_spanExceeds366Days() {
            SeededMaster m = seedIndependentMaster();
            Actor client = seedUser(Role.CLIENT);
            LocalDate from = LocalDate.now();
            LocalDate to = from.plusDays(367);

            assertThat(workingDays(m, from.toString(), to.toString(), client).getStatusCode())
                    .as("working-days reuses MasterScheduleService.resolveEffectiveRange's range-cap guard")
                    .isEqualTo(HttpStatus.BAD_REQUEST);
        }

        /**
         * Security-audit regression: {@code getClientWorkingDays}/{@code resolveEffectiveRange}
         * intentionally never checks master existence. A nonexistent masterId and a real master with no
         * schedule configured both correctly resolve to {@link com.beautica.master.dto.EffectiveDaySource#NO_SCHEDULE}
         * for every date, so both return 200 with every {@code working} field {@code false} — no
         * status/timing/shape differential that would let a CLIENT enumerate valid master ids. This pins
         * that intentional behavior against future regression (e.g. an added existence check that would
         * turn this into a 404 oracle).
         */
        @Test
        @DisplayName("nonexistent masterId -> 200, every day working:false (no existence oracle, intentional)")
        void should_return200AllFalse_when_masterIdDoesNotExist() throws Exception {
            UUID nonexistentMasterId = UUID.randomUUID();
            Actor client = seedUser(Role.CLIENT);
            String today = LocalDate.now().toString();
            String week = LocalDate.now().plusDays(7).toString();

            ResponseEntity<String> resp = get(
                    "/" + nonexistentMasterId + "/working-days?from=" + today + "&to=" + week, client);

            assertThat(resp.getStatusCode())
                    .as("a nonexistent masterId must not be distinguishable from a real master with no "
                            + "schedule configured — both resolve to NO_SCHEDULE, never 404")
                    .isEqualTo(HttpStatus.OK);
            var data = objectMapper.readTree(resp.getBody()).path("data");
            assertThat(data).isNotEmpty();
            for (var day : data) {
                assertThat(day.path("working").asBoolean())
                        .as("every day must resolve to working:false for an id with no schedule rows")
                        .isFalse();
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // S3 — V83 contract: reason/note keys never appear on any read path (columns dropped)
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("S3 — reason/note never appear in serialized read bodies (V83)")
    class ResponseShapeExposure {

        @Test
        @DisplayName("the PUBLIC GET /masters/{id} profile body carries no reason or note key")
        void should_notExposeReasonOrNote_onPublicProfile() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertOverride(m.owner().userId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF, null));

            // No auth header — this is the unauthenticated public profile endpoint.
            ResponseEntity<String> resp = restTemplate.getForEntity(
                    BASE + "/" + m.masterId(), String.class);

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("the public master profile must not echo a removed reason/note key (V83)")
                    .doesNotContain("\"note\"")
                    .doesNotContain("\"reason\"");
        }

        @Test
        @DisplayName("the (authorized) effective-schedule projection serializes no reason or note key")
        void should_notExposeReasonOrNote_onEffectiveScheduleRead() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertOverride(m.owner().userId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF, null));

            ResponseEntity<String> resp = get(
                    "/" + m.masterId() + "/effective-schedule?from=" + FUTURE_FROM + "&to=" + FUTURE_FROM,
                    m.owner());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("EffectiveDayResponse exposes only date/source/intervals — no reason/note (V83)")
                    .contains("OVERRIDE_DAY_OFF")   // the source IS exposed
                    .doesNotContain("\"reason\"")
                    .doesNotContain("\"note\"");
        }

        @Test
        @DisplayName("the effective-schedule projection serializes no workingDay key (isWorkingDay is @JsonIgnore)")
        void should_notExposeWorkingDay_onEffectiveScheduleRead() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertOverride(m.owner().userId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF, null));

            ResponseEntity<String> resp = get(
                    "/" + m.masterId() + "/effective-schedule?from=" + FUTURE_FROM + "&to=" + FUTURE_FROM,
                    m.owner());

            assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resp.getBody())
                    .as("isWorkingDay() is an internal helper for MasterScheduleService.getClientWorkingDays "
                            + "only — it must not leak onto the wire as an extra 'workingDay' field")
                    .doesNotContain("\"workingDay\"");
        }
    }
}
