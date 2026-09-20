package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.common.TimeZones;
import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.dto.EffectiveDaySource;
import com.beautica.salon.dto.SalonMasterEffectiveScheduleResponse;
import com.beautica.support.NotATimedTest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 321 — {@code GET /api/v1/salons/{salonId}/masters/effective-schedule}.
 *
 * <p>Four things are pinned here, and the second is the reason the endpoint exists at all.
 *
 * <h2>1. Roster-completeness is load-bearing (cases 1-3)</h2>
 * The mobile salon «Записи» board renders THREE distinct states off this payload, and they must never
 * collapse into two:
 * <ul>
 *   <li>master id <b>absent</b> from the response — unknown / still loading, rendered as today;</li>
 *   <li>{@code OVERRIDE_DAY_OFF} — greyed column, «Вихідний»;</li>
 *   <li>{@code NO_SCHEDULE} — greyed column, «Графік не задано».</li>
 * </ul>
 * A master with zero schedule rows is exactly the case an "optimisation" would drop, and dropping it
 * makes "off today" indistinguishable from "not loaded" — so a slow fetch would render as a wall of
 * grey rather than a wall of empty columns. Case 1 pins the master's presence, case 2 pins that the
 * two greyed sources stay distinguishable, case 3 pins that ACTIVE is still the roster predicate.
 *
 * <h2>2. The statement ledgers at N = 60 (cases 10 and 11) — measured by SQL capture, never by a counter</h2>
 * The whole justification for this endpoint over an N-request mobile fan-out is that its cost is
 * {@code 3 + ceil(S/50) + ceil(O/50)} statements — three fixed, two CHUNKED, none per-master — where
 * S is weekly-schedule rows and O is non-{@code DAY_OFF} override rows inside the window. Those are
 * TWO independent dimensions and they need two cases: case 10 seeds templates only and pins S; case 11
 * holds the roster and the span constant and varies O alone. Case 10's "no statement may scale"
 * sweep cannot speak for the override dimension at all — with no overrides seeded,
 * {@code schedule_exception_times} is never issued and that half of the sweep passes vacuously.
 *
 * <p>A per-item count reported by a counter is a FLOOR, and — the trap
 * this project has already been bitten by — this codebase runs
 * {@code hibernate.default_batch_fetch_size: 50}, so ANY statement count taken at N &lt; 50 is flat
 * for the wrong reason: Hibernate's lazy {@code WeeklySchedule.discreteTimes} batch-fetch collapses
 * into a single chunk and hides its own N-dependence. N = 60 crosses that boundary deliberately.
 *
 * <p>The assertion is therefore a DIFFERENTIAL, not a magic total: going from a 10-master salon to a
 * 60-master salon — six times the roster — must add exactly ONE statement, the second
 * {@code working_interval_times} chunk ({@code ceil(60/50) = 2} vs {@code ceil(10/50) = 1}). A
 * regression to per-master resolution would add 50. The per-table enumeration alongside it names
 * every statement, so a future reader does not have to trust a bare number.
 *
 * <p><b>Harness</b>: the dependency-free Hibernate {@link StatementInspector} capture already proven
 * by {@code SalonSiblingProjectionShapeIT}, {@code UserDynamicUpdateShapeIT} and
 * {@code LocalityDiscoveryPerfHardeningTest}. Fixtures are inserted with raw JDBC, which the
 * inspector does not see, so the sink holds exactly the statements the request issued.
 *
 * <h2>3. Authorization (cases 4-8)</h2>
 * The gate is the IDENTICAL expression phase 319's {@code GET /bookings/salon/{salonId}/booked-days}
 * carries, verbatim. The role half alone would admit any owner/admin for ANY salon id, so both halves
 * get a negative case: {@code SALON_MASTER} (wrong role), a foreign owner and an admin assigned
 * elsewhere (right role, wrong salon), anonymous, and a salon id that does not exist — which must 403
 * rather than 404, so the endpoint is not an existence oracle. Case 4b is the matching POSITIVE: an
 * admin OF this salon reads the board. Without it, narrowing the gate to {@code hasRole('SALON_OWNER')}
 * would lock every salon admin out with a suite that stays entirely green.
 *
 * <h2>4. Tenant isolation is a CONTENT property (case 3b)</h2>
 * The roster query {@code MasterRepository#findBySalonIdAndIsActiveTrueWithUser} backs three endpoints.
 * Cases 1-3 put every master they create into ONE salon, so dropping that query's
 * {@code m.salon.id = :salonId} predicate leaves all of them green by DB-state coincidence — the only
 * red test would be case 10's statement ledger, which a reader can talk themselves out of as flaky.
 * Case 3b stations ACTIVE masters in a SECOND salon so the predicate is the only thing keeping them
 * out of the body. Case 12 covers the other documented-but-unasserted invariant on this path: the
 * batch resolver is window-free.
 *
 * <h2>5. The wire SHAPE of an interval (case 13)</h2>
 * Cases 1-12 read the body through a {@link TypeReference} into the response records, and that read is
 * blind in both directions — it tolerates a missing key and DISCARDS an unknown one. A Bean Validation
 * predicate following the JavaBean {@code isXxx()} convention ({@code WorkIntervalDto#isOrdered}) was
 * therefore serialized as a constant {@code "ordered": true} on every interval of this
 * {@code roster × days} payload, and published as an input property on every embedding request schema,
 * with the whole suite green — reaching the committed mobile OpenAPI snapshot unreviewed. Case 13 reads
 * the RAW {@link JsonNode} instead and pins the interval key set exactly.
 */
@Import(SalonMasterEffectiveScheduleIT.SqlCaptureConfig.class)
@DisplayName("GET /salons/{salonId}/masters/effective-schedule — Phase 321")
class SalonMasterEffectiveScheduleIT extends AbstractIntegrationTest {

    static final List<String> CAPTURED_SQL = new CopyOnWriteArrayList<>();

    /** Roster sizes for the differential ledger — 10 is below Hibernate's batch-fetch chunk, 60 above. */
    private static final int SMALL_ROSTER = 10;
    private static final int LARGE_ROSTER = 60;

    /**
     * Statements a 6x roster is allowed to add: exactly one — the SECOND
     * {@code working_interval_times} batch-fetch chunk ({@code ceil(60/50)} vs {@code ceil(10/50)}).
     */
    private static final long ALLOWED_STATEMENT_GROWTH = 1L;

    /** Mirrors {@code spring.jpa.properties.hibernate.default_batch_fetch_size} in application.yml. */
    private static final int BATCH_FETCH_SIZE = 50;

    /**
     * Overrides per master in case 11's DENSE fixture. 60 masters x 10 = 600 {@code CUSTOM_HOURS}
     * rows in the window, i.e. 12 batch-fetch chunks against the sparse fixture's 2 — a differential
     * far too big to be produced by anything other than real chunking.
     */
    private static final int OVERRIDES_PER_DENSE_MASTER = 10;

    /** The full 62-inclusive-day board span, so case 11 measures the endpoint at its documented ceiling. */
    private static final int LEDGER_SPAN_DAYS = 61;

    private static final Pattern FROM_TABLE = Pattern.compile("\\bfrom\\s+([a-z_]+)");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private SalonItFixtures fixtures;

    /**
     * Today in Kyiv civil time, read from the same system clock the application's own {@code Clock}
     * bean is built on under the {@code test} profile — never a hardcoded literal, which would rot the
     * moment it drifts outside {@code ScheduleDateMath}'s one-year past floor.
     */
    private LocalDate today;

    @BeforeEach
    void setUp() {
        fixtures = new SalonItFixtures(
                restTemplate, jdbcTemplate, objectMapper, passwordEncoder, this::testCityId);
        today = LocalDate.now(TimeZones.KYIV);
        CAPTURED_SQL.clear();
    }

    // ── 1-3: roster-completeness and the three renderable states ───────────────────────────

    @Test
    @DisplayName("1 — a master with ZERO schedule rows still appears, with a full NO_SCHEDULE day list")
    void should_includeMaster_when_masterHasNoScheduleRowsAtAll() throws Exception {
        // Arrange — two active masters; only one has a template. The other has nothing at all, which
        // is precisely the row a groupingBy-keyed implementation would silently drop.
        Fixture f = salonWithOwner("no-rows");
        UUID scheduled = insertMaster(f.salonId(), "scheduled");
        UUID bare = insertMaster(f.salonId(), "bare");
        seedIntervalTemplate(scheduled, LocalTime.of(9, 0), LocalTime.of(18, 0));
        LocalDate from = today.plusDays(2);
        LocalDate to = from.plusDays(2);

        // Act
        List<SalonMasterEffectiveScheduleResponse> body = getOk(f.token(), f.salonId(), from, to);

        // Assert
        assertThat(body).extracting(SalonMasterEffectiveScheduleResponse::masterId)
                .containsExactlyInAnyOrder(scheduled, bare);
        assertThat(daysOf(body, bare))
                .as("absent != empty: the bare master gets a full day list, every day NO_SCHEDULE")
                .hasSize(3)
                .allSatisfy(day -> assertThat(day.source()).isEqualTo(EffectiveDaySource.NO_SCHEDULE));
        assertThat(daysOf(body, scheduled))
                .extracting(EffectiveDayResponse::source)
                .containsOnly(EffectiveDaySource.TEMPLATE);
    }

    @Test
    @DisplayName("2 — OVERRIDE_DAY_OFF stays distinct from NO_SCHEDULE on the same date")
    void should_reportDistinctSources_when_oneMasterIsOffAndAnotherHasNoSchedule() throws Exception {
        // Arrange — same date, three masters, three different reasons to look 'not working'.
        Fixture f = salonWithOwner("distinct-sources");
        UUID offToday = insertMaster(f.salonId(), "off");
        UUID working = insertMaster(f.salonId(), "working");
        UUID unscheduled = insertMaster(f.salonId(), "unscheduled");
        LocalDate date = today.plusDays(3);
        seedIntervalTemplate(offToday, LocalTime.of(9, 0), LocalTime.of(18, 0));
        seedIntervalTemplate(working, LocalTime.of(10, 0), LocalTime.of(20, 0));
        seedDayOffOverride(offToday, date);

        // Act
        List<SalonMasterEffectiveScheduleResponse> body = getOk(f.token(), f.salonId(), date, date);

        // Assert
        assertThat(daysOf(body, offToday)).singleElement()
                .satisfies(day -> {
                    assertThat(day.source()).isEqualTo(EffectiveDaySource.OVERRIDE_DAY_OFF);
                    assertThat(day.intervals()).isEmpty();
                });
        assertThat(daysOf(body, unscheduled)).singleElement()
                .satisfies(day -> assertThat(day.source()).isEqualTo(EffectiveDaySource.NO_SCHEDULE));
        assertThat(daysOf(body, working)).singleElement()
                .satisfies(day -> {
                    assertThat(day.source()).isEqualTo(EffectiveDaySource.TEMPLATE);
                    assertThat(day.intervals()).singleElement().satisfies(interval -> {
                        assertThat(interval.startTime()).isEqualTo(LocalTime.of(10, 0));
                        assertThat(interval.endTime()).isEqualTo(LocalTime.of(20, 0));
                    });
                });
    }

    @Test
    @DisplayName("3 — an INACTIVE master is not on the roster and must not appear")
    void should_omitMaster_when_masterIsInactive() throws Exception {
        // Arrange
        Fixture f = salonWithOwner("inactive");
        // Both masters carry a template, so the ONLY thing that can decide their fate is
        // masters.is_active — never "has no schedule rows", which case 1 owns.
        UUID active = insertMaster(f.salonId(), "active");
        UUID inactive = insertMaster(f.salonId(), "inactive", false);
        seedIntervalTemplate(active, LocalTime.of(9, 0), LocalTime.of(18, 0));
        seedIntervalTemplate(inactive, LocalTime.of(9, 0), LocalTime.of(18, 0));
        LocalDate from = today.plusDays(2);

        // Act
        List<SalonMasterEffectiveScheduleResponse> body = getOk(f.token(), f.salonId(), from, from);

        // Assert
        assertThat(body).extracting(SalonMasterEffectiveScheduleResponse::masterId)
                .containsExactly(active)
                .doesNotContain(inactive);
    }

    @Test
    @DisplayName("3b — masters of ANOTHER salon never appear, even when that salon's roster is bigger")
    void should_omitForeignSalonMasters_when_anotherSalonHasActiveMasters() throws Exception {
        // Arrange — two salons, BOTH staffed with ACTIVE, template-carrying masters, and the foreign
        // one deliberately the bigger of the two.
        //
        // This fixture is the point of the test. Cases 1-3 all put every master they create into the
        // SAME salon, so their containsExactlyInAnyOrder assertions hold even if the roster query
        // loses its salon predicate entirely — they pass by DB-state coincidence, and a mutation of
        // MasterRepository#findBySalonIdAndIsActiveTrueWithUser that drops `m.salon.id = :salonId`
        // leaves every one of them GREEN. That query backs three endpoints; un-scoped, it hands any
        // salon owner or admin the working pattern of every active master on the platform. Here the
        // foreign masters exist and are active, so the salon predicate is the ONLY thing that can
        // keep them out of this body.
        Fixture target = salonWithOwner("tenant-target");
        Fixture foreign = salonWithOwner("tenant-foreign");
        UUID mine = insertMaster(target.salonId(), "mine");
        List<UUID> theirs = List.of(
                insertMaster(foreign.salonId(), "theirs-1"),
                insertMaster(foreign.salonId(), "theirs-2"),
                insertMaster(foreign.salonId(), "theirs-3"));
        seedIntervalTemplate(mine, LocalTime.of(9, 0), LocalTime.of(18, 0));
        theirs.forEach(id -> seedIntervalTemplate(id, LocalTime.of(8, 0), LocalTime.of(20, 0)));
        LocalDate date = today.plusDays(2);

        // Act
        List<SalonMasterEffectiveScheduleResponse> body =
                getOk(target.token(), target.salonId(), date, date);

        // Assert
        assertThat(body).extracting(SalonMasterEffectiveScheduleResponse::masterId)
                .as("tenant isolation is a CONTENT property of the roster query, not a statement-count "
                        + "property — case 10's ledger must never be the only red test for it")
                .containsExactly(mine)
                .doesNotContainAnyElementsOf(theirs);
    }

    // ── 4-8: authorization ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("4 — SALON_MASTER of THIS salon gets 403 (the role half of the gate)")
    void should_return403_when_callerIsSalonMaster() throws Exception {
        Fixture f = salonWithOwner("role-master");
        insertMaster(f.salonId(), "m");
        UUID staffId = fixtures.insertSalonMasterUser(
                "staff-" + uniq("role-master") + "@beautica.test", f.salonId());
        String token = fixtures.loginAndGetToken(fixtures.emailOf(staffId));

        assertThat(get(token, f.salonId(), today.plusDays(2), today.plusDays(2)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("4b — a SALON_ADMIN OF THIS salon gets 200 with the roster (the gate's positive)")
    void should_return200_when_callerIsSalonAdminOfThisSalon() throws Exception {
        // Arrange — cases 4-8 are ALL negatives, so narrowing the gate from
        // hasAnyRole('SALON_OWNER','SALON_ADMIN') to hasRole('SALON_OWNER') would lock every salon
        // admin out of the «Записи» board without turning a single test red. This is the positive
        // that closes that hole, and it asserts the BODY rather than the status: a gate that admits
        // the admin but resolves an empty roster for them is the same outage with a 200 on it.
        Fixture f = salonWithOwner("admin-positive");
        UUID master = insertMaster(f.salonId(), "m");
        seedIntervalTemplate(master, LocalTime.of(9, 0), LocalTime.of(18, 0));
        UUID adminId = fixtures.insertAdmin(
                "admin-" + uniq("here") + "@beautica.test", f.salonId());
        String token = fixtures.loginAndGetToken(fixtures.emailOf(adminId));
        LocalDate date = today.plusDays(2);

        // Act
        List<SalonMasterEffectiveScheduleResponse> body = getOk(token, f.salonId(), date, date);

        // Assert
        assertThat(body).extracting(SalonMasterEffectiveScheduleResponse::masterId)
                .as("an assigned admin reads the same board the owner does")
                .containsExactly(master);
    }

    @Test
    @DisplayName("5 — an owner of a DIFFERENT salon gets 403 (the per-salon half of the gate)")
    void should_return403_when_ownerOwnsADifferentSalon() throws Exception {
        Fixture target = salonWithOwner("target");
        Fixture foreign = salonWithOwner("foreign");
        insertMaster(target.salonId(), "m");
        // The foreign salon is staffed too, so this 403 is refused against a NON-EMPTY foreign
        // roster — i.e. there is real staffing data behind the gate, not an empty read that would
        // look identical whether the gate held or not. That the foreign roster stays out of a
        // SUCCESSFUL read is case 3b's job; it needs a 200 body, which this case by definition
        // cannot produce.
        insertMaster(foreign.salonId(), "foreign-m");

        assertThat(get(foreign.token(), target.salonId(), today.plusDays(2), today.plusDays(2))
                .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("6 — a SALON_ADMIN assigned to a DIFFERENT salon gets 403")
    void should_return403_when_adminIsAssignedElsewhere() throws Exception {
        Fixture target = salonWithOwner("admin-target");
        Fixture other = salonWithOwner("admin-other");
        insertMaster(target.salonId(), "m");
        UUID adminId = fixtures.insertAdmin(
                "admin-" + uniq("elsewhere") + "@beautica.test", other.salonId());
        String token = fixtures.loginAndGetToken(fixtures.emailOf(adminId));

        assertThat(get(token, target.salonId(), today.plusDays(2), today.plusDays(2)).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("6b — an authenticated CLIENT gets 403: the role gate must deny the platform's "
            + "largest population, not only the staff roles that sit next to it")
    void should_return403_when_callerIsClient() throws Exception {
        // Arrange — cases 4-6 deny only STAFF roles, and case 7 denies anonymity. Between them sits
        // the role that actually outnumbers every other: an ordinary logged-in CLIENT. A gate
        // widened to `isAuthenticated()` — the single likeliest way this endpoint regresses — would
        // leave all of 4-8 green while exposing every salon's staffing roster to anybody with an
        // account. The salon is staffed so the refusal is measured against a NON-EMPTY roster.
        Fixture f = salonWithOwner("client-caller");
        insertMaster(f.salonId(), "m");
        UUID clientId = fixtures.insertUser(
                "client-" + uniq("caller") + "@beautica.test", "CLIENT");
        String token = fixtures.loginAndGetToken(fixtures.emailOf(clientId));

        // Act
        ResponseEntity<String> response =
                get(token, f.salonId(), today.plusDays(2), today.plusDays(2));

        // Assert
        assertThat(response.getStatusCode())
                .as("a CLIENT is authenticated but has no business reading a salon's master roster")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("7 — anonymous gets 401")
    void should_return401_when_anonymous() throws Exception {
        Fixture f = salonWithOwner("anon");

        ResponseEntity<String> response = restTemplate.exchange(
                url(f.salonId(), today.plusDays(2), today.plusDays(2)),
                HttpMethod.GET, HttpEntity.EMPTY, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("8 — a salon id that does not exist gets 403, not 404 — no existence oracle")
    void should_return403_when_salonDoesNotExist() throws Exception {
        Fixture f = salonWithOwner("ghost");

        assertThat(get(f.token(), UUID.randomUUID(), today.plusDays(2), today.plusDays(2))
                .getStatusCode())
                .as("a 404 here would tell any owner which salon ids exist")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── 9: range guards ────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("9a — a missing 'to' bound is 400, never an implicit default")
    void should_return400_when_toBoundIsMissing() throws Exception {
        Fixture f = salonWithOwner("missing-bound");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + f.salonId() + "/masters/effective-schedule?from=" + today.plusDays(2),
                HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(f.token())), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("9b — the span boundary pair: 62 inclusive days is accepted, 63 is 400")
    void should_rejectSpan_when_rangeExceeds62Days() throws Exception {
        // Arrange — an empty roster would still have to be rejected, so the guard must run BEFORE
        // the roster query; this salon deliberately has no masters at all.
        Fixture f = salonWithOwner("span");
        LocalDate from = today.plusDays(2);

        // Act + Assert — N accepted, N+1 rejected.
        assertThat(get(f.token(), f.salonId(), from, from.plusDays(61)).getStatusCode())
                .as("62 inclusive days is the documented ceiling and must pass")
                .isEqualTo(HttpStatus.OK);
        assertThat(get(f.token(), f.salonId(), from, from.plusDays(62)).getStatusCode())
                .as("63 inclusive days must be rejected even for a salon with an EMPTY roster")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("9c — an inverted range (from AFTER to) is 400, never a silent empty 200")
    void should_return400_when_rangeIsInverted() throws Exception {
        // Arrange — a staffed salon, so a 200 here would be a 200 with an empty day list per master
        // rather than an empty roster: expandInclusive yields zero dates for from > to, and the board
        // would paint every column blank with nothing to distinguish it from "nobody works that week".
        Fixture f = salonWithOwner("inverted");
        UUID master = insertMaster(f.salonId(), "m");
        seedIntervalTemplate(master, LocalTime.of(9, 0), LocalTime.of(18, 0));
        LocalDate from = today.plusDays(9);

        // Act + Assert
        assertThat(get(f.token(), f.salonId(), from, from.minusDays(7)).getStatusCode())
                .as("ScheduleDateMath#assertExpandable's ordering guard must reach the wire as a 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("9d — an unparseable date is 400, not the 500 an unhandled bind failure would give")
    void should_return400_when_fromDateIsMalformed() throws Exception {
        Fixture f = salonWithOwner("malformed");

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/salons/" + f.salonId() + "/masters/effective-schedule"
                        + "?from=2026-13-45&to=" + today.plusDays(2),
                HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(f.token())), String.class);

        assertThat(response.getStatusCode())
                .as("a LocalDate bind failure is a client error; body=%s", response.getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── 10-11: the statement ledgers ───────────────────────────────────────────────────────

    @Test
    @NotATimedTest(reason = "The 70-master fixture IS the experiment: the ledger only means "
            + "anything above hibernate.default_batch_fetch_size (50), so the seeding cost this "
            + "test pays is the property under test, not overhead that could be trimmed away. "
            + "The wall clock here measures how fast Postgres ingests 70 masters, never whether "
            + "the read path regressed — the statement differential is what does that.")
    @DisplayName("10 — statement count is flat in roster size: 10 -> 60 masters adds exactly one "
            + "statement (the second batch-fetch chunk), not fifty")
    void should_keepStatementCountFlat_when_rosterGrowsAcrossTheBatchFetchBoundary() throws Exception {
        // Arrange — two salons, one below and one above hibernate.default_batch_fetch_size (50).
        // Templates only, no overrides: this isolates the ONE statement in the ledger that is
        // legitimately N-dependent (the chunked discreteTimes batch-fetch) from every other.
        Fixture small = salonWithOwner("ledger-small");
        Fixture large = salonWithOwner("ledger-large");
        seedRoster(small.salonId(), SMALL_ROSTER);
        seedRoster(large.salonId(), LARGE_ROSTER);
        LocalDate from = today.plusDays(2);
        LocalDate to = from.plusDays(6);

        // Act — measure each request's statements in isolation.
        CAPTURED_SQL.clear();
        assertThat(get(small.token(), small.salonId(), from, to).getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> smallSql = List.copyOf(CAPTURED_SQL);

        CAPTURED_SQL.clear();
        assertThat(get(large.token(), large.salonId(), from, to).getStatusCode()).isEqualTo(HttpStatus.OK);
        List<String> largeSql = List.copyOf(CAPTURED_SQL);

        // The measured ledger, printed so CI output carries it without a failure being required —
        // this is the table the phase doc's D4 quotes.
        System.out.println("PHASE321_LEDGER small(" + SMALL_ROSTER + ")=" + byTable(smallSql)
                + " large(" + LARGE_ROSTER + ")=" + byTable(largeSql));

        // Assert — the differential is the load-bearing claim; the enumeration names every statement.
        assertThat((long) largeSql.size() - smallSql.size())
                .as("6x the roster must add at most the extra batch-fetch chunk. "
                        + "small(%d)=%s%nlarge(%d)=%s",
                        SMALL_ROSTER, byTable(smallSql), LARGE_ROSTER, byTable(largeSql))
                .isEqualTo(ALLOWED_STATEMENT_GROWTH);

        assertThat(byTable(largeSql))
                .as("the enumerated ledger at N=%d — full capture:%n%s",
                        LARGE_ROSTER, String.join("\n", largeSql))
                .containsEntry("masters", 1L)
                .containsEntry("schedule_exceptions", 1L)
                .containsEntry("weekly_schedules", 1L)
                .containsEntry("working_interval_times", 2L);
        // Scoped to THIS fixture's tables. It cannot speak for schedule_exception_times, which is never
        // issued here because nothing seeds an override — that dimension is case 11's.
        assertThat(byTable(largeSql))
                .as("no statement may scale with N — full capture:%n%s", String.join("\n", largeSql))
                .allSatisfy((table, count) -> assertThat(count)
                        .as("table %s", table)
                        .isLessThanOrEqualTo(2L));
    }

    @Test
    @NotATimedTest(reason = "Same reasoning as case 10: the 120-master, 660-override fixture IS the "
            + "experiment. The override dimension only means anything above "
            + "hibernate.default_batch_fetch_size (50), so the seeding cost is the property under "
            + "test rather than trimmable overhead. The wall clock measures Postgres ingest, never "
            + "the read path — the statement differential does that.")
    @DisplayName("11 — statement count is flat in OVERRIDE count: 60 -> 600 CUSTOM_HOURS rows in the "
            + "window adds only the extra schedule_exception_times chunks, not 540 statements")
    void should_keepStatementCountFlat_when_overrideCountGrowsAcrossTheBatchFetchBoundary()
            throws Exception {
        // Arrange — case 10 seeds TEMPLATES ONLY, which leaves the whole override dimension
        // unmeasured: its `no statement may scale with N` sweep passes vacuously on
        // schedule_exception_times because that table is never read there. This case supplies the
        // missing dimension. ScheduleException.discreteTimes is LAZY and batch-fetched, and
        // ScheduleMapper#toOverrideDiscreteTimes touches it for EVERY non-DAY_OFF override, so the
        // cost of this endpoint is ceil(O/50) statements in O = CUSTOM_HOURS rows inside the window
        // — NOT flat, and the javadoc that used to claim "1 roster query + 2 schedule queries, flat
        // in roster size" was wrong on exactly this term.
        //
        // Both salons carry the SAME roster size and the SAME 62-day span, so every statement except
        // the schedule_exception_times chunking is held constant and the differential isolates it.
        Fixture sparse = salonWithOwner("override-sparse");
        Fixture dense = salonWithOwner("override-dense");
        List<UUID> sparseRoster = seedRoster(sparse.salonId(), LARGE_ROSTER);
        List<UUID> denseRoster = seedRoster(dense.salonId(), LARGE_ROSTER);
        LocalDate from = today.plusDays(2);
        LocalDate to = from.plusDays(LEDGER_SPAN_DAYS);
        seedExplicitTimesOverrides(sparseRoster, from, 1);
        seedExplicitTimesOverrides(denseRoster, from, OVERRIDES_PER_DENSE_MASTER);

        // Act — measure each request's statements in isolation.
        CAPTURED_SQL.clear();
        assertThat(get(sparse.token(), sparse.salonId(), from, to).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        List<String> sparseSql = List.copyOf(CAPTURED_SQL);

        CAPTURED_SQL.clear();
        assertThat(get(dense.token(), dense.salonId(), from, to).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        List<String> denseSql = List.copyOf(CAPTURED_SQL);

        System.out.println("PHASE321_OVERRIDE_LEDGER sparse(" + LARGE_ROSTER + "x1)="
                + byTable(sparseSql) + " dense(" + LARGE_ROSTER + "x" + OVERRIDES_PER_DENSE_MASTER
                + ")=" + byTable(denseSql));

        // Assert — the differential is exactly the extra chunking, never a per-override statement.
        long sparseOverrides = LARGE_ROSTER;
        long denseOverrides = (long) LARGE_ROSTER * OVERRIDES_PER_DENSE_MASTER;
        assertThat((long) denseSql.size() - sparseSql.size())
                .as("10x the overrides must add exactly ceil(%d/%d) - ceil(%d/%d) statements. "
                        + "sparse=%s%ndense=%s",
                        denseOverrides, BATCH_FETCH_SIZE, sparseOverrides, BATCH_FETCH_SIZE,
                        byTable(sparseSql), byTable(denseSql))
                .isEqualTo(chunks(denseOverrides) - chunks(sparseOverrides));

        assertThat(byTable(denseSql))
                .as("the enumerated override ledger at O=%d — full capture:%n%s",
                        denseOverrides, String.join("\n", denseSql))
                .containsEntry("masters", 1L)
                .containsEntry("schedule_exceptions", 1L)
                .containsEntry("weekly_schedules", 1L)
                .containsEntry("schedule_exception_times", chunks(denseOverrides));
    }

    // ── 12: the batch path is the WINDOW-FREE resolver ─────────────────────────────────────

    @Test
    @DisplayName("12 — windowStart/windowEnd are present-and-null on the batch path, while the "
            + "per-master route returns them NON-null for the same master on the same date")
    void should_omitWorkingWindow_when_daysComeFromTheBatchResolver() throws Exception {
        // Arrange — SalonMasterEffectiveScheduleResponse's javadoc promises this endpoint is the
        // window-free variant (Phase 15.12): the board derives ONE shared timeline from the union of
        // every master's intervals and never the day-editor's per-master break reconstruction. That
        // promise was documented and entirely unasserted — switching this path to
        // resolveEffectiveRangeForDisplay would leave the whole suite green while the mobile board
        // started receiving per-master windows it does not model.
        //
        // The fixture is the substance of this test. A master with NO weekly_schedule_day_windows row
        // projects a null window on BOTH routes, so the batch assertion below would pass while pinning
        // nothing at all. This template therefore carries a 09:00–18:00 window against a 10:00–18:00
        // interval — a break flush against the start of the day, the one shape where the window is not
        // derivable from the intervals — and step 1 below proves the two routes genuinely disagree.
        Fixture f = salonWithOwner("window-free");
        UUID master = insertMaster(f.salonId(), "windowed");
        seedTemplate(master, LocalTime.of(10, 0), LocalTime.of(18, 0),
                LocalTime.of(9, 0), LocalTime.of(18, 0));
        LocalDate date = today.plusDays(2);

        // Act — the same master, the same date, through both routes.
        JsonNode perMasterDay = perMasterDays(f.token(), master, date).get(0);
        JsonNode batchDay = rawBatchDaysByMaster(f.token(), f.salonId(), date, date)
                .get(master.toString()).get(0);

        // Assert — 1. FIXTURE LIVENESS: the per-master display route does project the window.
        assertThat(perMasterDay.path("windowStart").isTextual())
                .as("without a non-null window HERE the batch assertion below is vacuous; "
                        + "per-master day=%s", perMasterDay)
                .isTrue();
        assertThat(perMasterDay.path("windowStart").asText()).isEqualTo("09:00:00");
        assertThat(perMasterDay.path("windowEnd").asText()).isEqualTo("18:00:00");

        // 2. ...and the batch route, for that very day, does not — present key, null value.
        assertThat(batchDay.has("windowStart") && batchDay.has("windowEnd"))
                .as("the key set stays constant across both routes; batch day=%s", batchDay)
                .isTrue();
        assertThat(batchDay.get("windowStart").isNull() && batchDay.get("windowEnd").isNull())
                .as("the salon board must be handed availability only — a per-master working window "
                        + "here means the batch path was switched to the windowed resolver. "
                        + "batch day=%s", batchDay)
                .isTrue();
    }

    // ── 13: Bean-Validation predicates are not wire fields ─────────────────────────────────

    @Test
    @DisplayName("13 — a serialized interval is startTime/endTime and nothing else: the @AssertTrue "
            + "predicate WorkIntervalDto#isOrdered never reaches the wire")
    void should_keepValidatorPredicatesOffTheWire_when_intervalsAreSerialized() throws Exception {
        // Arrange — WorkIntervalDto#isOrdered is a cross-field @AssertTrue that happens to follow the
        // JavaBean isXxx() convention, so without @JsonIgnore Jackson publishes a constant
        // "ordered": true on EVERY interval of every response embedding the record — and, because that
        // record is a REQUEST shape too, documents `ordered` as an input property on every embedding
        // request schema in the OpenAPI document the mobile Dart client is generated from. That is
        // exactly how it reached the committed mobile snapshot unreviewed.
        //
        // WHY THIS READS RAW JSON — do NOT "clean this up" onto getOk()'s TypeReference form.
        // Deserialization into the record silently DISCARDS unknown keys, so a typed read is blind to
        // the leak in precisely the way that let it ship; converted, this assertion would stay green
        // with the annotation deleted and close nothing.
        Fixture f = salonWithOwner("wire-shape");
        UUID master = insertMaster(f.salonId(), "interval-bearing");
        seedIntervalTemplate(master, LocalTime.of(10, 0), LocalTime.of(18, 0));
        LocalDate date = today.plusDays(2);

        // Act — the raw day node, straight off the wire.
        JsonNode day = rawBatchDaysByMaster(f.token(), f.salonId(), date, date)
                .get(master.toString()).get(0);
        JsonNode intervals = day.path("intervals");

        // Assert — 1. FIXTURE LIVENESS. "Key X is absent" passes for free over an empty array, so the
        // template seeded above has to genuinely project an interval on this date first.
        assertThat(intervals.isArray() && !intervals.isEmpty())
                .as("a key-absence assertion over ZERO intervals proves nothing — this fixture must "
                        + "carry working hours on %s; day=%s", date, day)
                .isTrue();

        // 2. ...and no interval it returns carries the validator predicate.
        for (JsonNode interval : intervals) {
            assertThat(interval.has("ordered"))
                    .as("WorkIntervalDto#isOrdered is a validator, never a wire field — restore its "
                            + "@JsonIgnore; interval=%s", interval)
                    .isFalse();
            assertThat(interval.has("startTime") && interval.has("endTime") && interval.size() == 2)
                    .as("an interval on the wire is startTime/endTime and nothing else; interval=%s",
                            interval)
                    .isTrue();
        }

        // 3. The sibling predicate on the enclosing day node carries the identical treatment.
        assertThat(day.has("workingDay"))
                .as("EffectiveDayResponse#isWorkingDay is an internal helper, never a wire field — "
                        + "restore its @JsonIgnore; day=%s", day)
                .isFalse();
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    /** Raw {@code GET /masters/{masterId}/effective-schedule} days as JSON — the windowed sibling route. */
    private JsonNode perMasterDays(String token, UUID masterId, LocalDate date) throws Exception {
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/effective-schedule?from=" + date + "&to=" + date,
                HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(response.getBody()).path("data");
    }

    /**
     * The batch response as {@code masterId -> days} raw JSON.
     *
     * <p>Deliberately NOT the {@link TypeReference} form {@link #getOk} uses. Deserialization into a
     * record is blind in BOTH directions — it silently tolerates a missing key and silently discards an
     * extra one — which is exactly how a serialized validator method ({@code "ordered"} on
     * {@code WorkIntervalDto}) rode this wire unnoticed into the committed mobile OpenAPI snapshot. A
     * wire-SHAPE assertion has to read the wire.
     */
    private Map<String, JsonNode> rawBatchDaysByMaster(
            String token, UUID salonId, LocalDate from, LocalDate to) throws Exception {
        ResponseEntity<String> response = get(token, salonId, from, to);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, JsonNode> byMaster = new LinkedHashMap<>();
        for (JsonNode entry : objectMapper.readTree(response.getBody()).path("data")) {
            byMaster.put(entry.path("masterId").asText(), entry.path("days"));
        }
        return byMaster;
    }

    /** Hibernate batch-fetch chunk count for {@code rows} uninitialised proxies of one type. */
    private static long chunks(long rows) {
        return (rows + BATCH_FETCH_SIZE - 1) / BATCH_FETCH_SIZE;
    }

    /** Groups captured statements by the table in their {@code FROM} clause. */
    private static Map<String, Long> byTable(List<String> sql) {
        return sql.stream()
                .map(statement -> {
                    var matcher = FROM_TABLE.matcher(statement.toLowerCase(Locale.ROOT));
                    return matcher.find() ? matcher.group(1) : "?";
                })
                .collect(Collectors.groupingBy(table -> table, Collectors.counting()));
    }

    private List<EffectiveDayResponse> daysOf(
            List<SalonMasterEffectiveScheduleResponse> body, UUID masterId) {
        return body.stream()
                .filter(entry -> entry.masterId().equals(masterId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("master " + masterId + " missing from response"))
                .days();
    }

    private String url(UUID salonId, LocalDate from, LocalDate to) {
        return "/api/v1/salons/" + salonId + "/masters/effective-schedule?from=" + from + "&to=" + to;
    }

    private ResponseEntity<String> get(String token, UUID salonId, LocalDate from, LocalDate to) {
        return restTemplate.exchange(url(salonId, from, to), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    private List<SalonMasterEffectiveScheduleResponse> getOk(
            String token, UUID salonId, LocalDate from, LocalDate to) throws Exception {
        ResponseEntity<String> response = get(token, salonId, from, to);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(response.getBody(),
                new TypeReference<ApiResponse<List<SalonMasterEffectiveScheduleResponse>>>() {}).data();
    }

    private Fixture salonWithOwner(String tag) throws Exception {
        UUID ownerId = fixtures.insertUser("owner-" + uniq(tag) + "@beautica.test", "SALON_OWNER");
        UUID salonId = fixtures.insertSalon(ownerId, "Phase 321 " + tag);
        return new Fixture(ownerId, salonId, fixtures.loginAndGetToken(fixtures.emailOf(ownerId)));
    }

    private static String uniq(String tag) {
        return tag + "-" + UUID.randomUUID();
    }

    private UUID insertMaster(UUID salonId, String tag) {
        return insertMaster(salonId, tag, true);
    }

    private UUID insertMaster(UUID salonId, String tag, boolean isActive) {
        UUID userId = fixtures.insertUserWithSalon(
                "master-" + uniq(tag) + "@beautica.test", "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', ?, NOW(), NOW())",
                masterId, userId, salonId, isActive);
        return masterId;
    }

    /** One open-ended INTERVAL template covering every ISO weekday, with no recorded working window. */
    private void seedIntervalTemplate(UUID masterId, LocalTime start, LocalTime end) {
        seedTemplate(masterId, start, end, null, null);
    }

    /**
     * The same template, plus a {@code weekly_schedule_day_windows} row per weekday.
     *
     * <p>Case 12 needs the window to be WIDER than the interval — the edge-flush-break shape (window
     * 09:00–18:00, interval 10:00–18:00) where the bounds are not reconstructible from the intervals.
     * Without such a row a master projects a null window on BOTH routes, and case 12's "the batch path
     * is window-free" assertion would pass for the wrong reason.
     */
    private void seedTemplate(UUID masterId, LocalTime start, LocalTime end,
            LocalTime windowStart, LocalTime windowEnd) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, NOW(), NOW())",
                scheduleId, masterId, today.minusDays(1));
        for (int isoDow = 1; isoDow <= 7; isoDow++) {
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), scheduleId, isoDow, start, end);
            if (windowStart != null) {
                jdbcTemplate.update(
                        "INSERT INTO weekly_schedule_day_windows (id, schedule_id, day_of_week, "
                                + "window_start, window_end) VALUES (?, ?, ?, ?, ?)",
                        UUID.randomUUID(), scheduleId, isoDow, windowStart, windowEnd);
            }
        }
    }

    private void seedDayOffOverride(UUID masterId, LocalDate date) {
        jdbcTemplate.update(
                "INSERT INTO schedule_exceptions (id, master_id, date, kind, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'DAY_OFF', NOW(), NOW())",
                UUID.randomUUID(), masterId, date);
    }

    /**
     * Bulk roster seeder for the ledger case — 70 masters, each with one open-ended INTERVAL
     * template over all seven weekdays.
     *
     * <p>Deliberately NOT {@code insertMaster} + {@code seedIntervalTemplate} in a loop, for one
     * reason that dominates everything else: those run the application's real BCrypt
     * {@link PasswordEncoder} once per user (§M-5 forbids fake hash literals), which at production
     * cost factor is ~100 ms × 70 = most of this test's wall clock, spent producing 70 hashes no
     * assertion here ever reads — the ledger request authenticates as the OWNER, and none of these
     * staff accounts ever logs in. The hash is therefore encoded ONCE, still by the real encoder,
     * and reused across the roster; the four inserts become four {@code batchUpdate}s.
     */
    private List<UUID> seedRoster(UUID salonId, int size) {
        String sharedHash = passwordEncoder.encode(SalonItFixtures.TEST_PASSWORD);
        List<UUID> masterIds = new java.util.ArrayList<>();
        List<Object[]> users = new java.util.ArrayList<>();
        List<Object[]> masters = new java.util.ArrayList<>();
        List<Object[]> schedules = new java.util.ArrayList<>();
        List<Object[]> intervals = new java.util.ArrayList<>();
        for (int i = 0; i < size; i++) {
            UUID userId = UUID.randomUUID();
            UUID masterId = UUID.randomUUID();
            masterIds.add(masterId);
            UUID scheduleId = UUID.randomUUID();
            users.add(new Object[] {userId, "ledger-" + uniq(String.valueOf(i)) + "@beautica.test",
                    sharedHash, salonId});
            masters.add(new Object[] {masterId, userId, salonId});
            schedules.add(new Object[] {scheduleId, masterId, today.minusDays(1)});
            for (int isoDow = 1; isoDow <= 7; isoDow++) {
                intervals.add(new Object[] {UUID.randomUUID(), scheduleId, isoDow,
                        LocalTime.of(9, 0), LocalTime.of(18, 0)});
            }
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', ?, true, true)", users);
        jdbcTemplate.batchUpdate(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())", masters);
        jdbcTemplate.batchUpdate(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, NOW(), NOW())", schedules);
        jdbcTemplate.batchUpdate(
                "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                        + "VALUES (?, ?, ?, ?, ?)", intervals);
        return List.copyOf(masterIds);
    }

    /**
     * Seeds {@code perMaster} consecutive-day {@code CUSTOM_HOURS} overrides per master, each with one
     * discrete start time.
     *
     * <p>{@code CUSTOM_HOURS} rather than {@code DAY_OFF} on purpose: {@code DAY_OFF} short-circuits in
     * {@code MasterScheduleService#resolveFromOverride} before it reads {@code discreteTimes}, so a
     * day-off fixture would never touch the lazy collection whose batch-fetch chunking is the whole
     * subject of case 11.
     */
    private void seedExplicitTimesOverrides(List<UUID> masterIds, LocalDate start, int perMaster) {
        List<Object[]> overrides = new java.util.ArrayList<>();
        List<Object[]> times = new java.util.ArrayList<>();
        for (UUID masterId : masterIds) {
            for (int day = 0; day < perMaster; day++) {
                UUID overrideId = UUID.randomUUID();
                overrides.add(new Object[] {overrideId, masterId, start.plusDays(day)});
                times.add(new Object[] {UUID.randomUUID(), overrideId, LocalTime.of(11, 0)});
            }
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO schedule_exceptions (id, master_id, date, kind, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'CUSTOM_HOURS', NOW(), NOW())", overrides);
        jdbcTemplate.batchUpdate(
                "INSERT INTO schedule_exception_times (id, exception_id, slot_time) VALUES (?, ?, ?)",
                times);
    }

    private record Fixture(UUID ownerId, UUID salonId, String token) {
    }

    /** Pure-observer {@link StatementInspector} on the Hibernate {@code SessionFactory}. */
    @TestConfiguration
    static class SqlCaptureConfig {
        @Bean
        HibernatePropertiesCustomizer rosterScheduleSqlCaptureCustomizer() {
            return (Map<String, Object> props) ->
                    props.put("hibernate.session_factory.statement_inspector",
                            new CapturingStatementInspector());
        }
    }

    static final class CapturingStatementInspector implements StatementInspector {
        @Override
        public String inspect(String sql) {
            if (sql != null) {
                CAPTURED_SQL.add(sql);
            }
            return sql;
        }
    }
}
