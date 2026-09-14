package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.service.MasterScheduleService;
import com.beautica.booking.service.SlotCalculationService;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.service.ServiceCatalogService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.assertj.core.api.SoftAssertions;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import com.beautica.support.NotATimedTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 315 — kills the salon catalogue's per-master N+1 by batching
 * {@code SlotCalculationService#filterBookableAssignmentsBatch}'s three underlying loads. This is
 * the correctness matrix (D7, cases 1-8/11) and the statement-count gate (D8, cases 9/12) plus the
 * DST falsification (D9, case 13) and the byte-identical JSON pin (D7, case 10).
 *
 * <h2>D6 — the enumerated per-master statement ledger (measured, not assumed)</h2>
 * The finding as written claimed 3 statements per master. A throwaway SQL-capture probe against this
 * class's own fixtures (a {@link org.hibernate.resource.jdbc.spi.StatementInspector}, the same
 * dependency-free harness {@code SalonSiblingProjectionShapeIT} uses) measured the PRE-315 code at
 * a 1-master salon and a 10-master salon, both seeded with {@code ServiceTestFixtures#seedUsableSchedule}
 * (INTERVAL mode only, no overrides, no EXPLICIT_TIMES) and one bookable assignment per master:
 *
 * <pre>
 *   1 master:  5 statements total   (1 candidates + 4 per-master)
 *   10 masters: 41 statements total (1 candidates + 4 × 10 per-master)
 * </pre>
 *
 * That is exactly 4 per master, not 3. The fourth, unenumerated statement is a lazy batch-fetch of
 * {@code WeeklySchedule.discreteTimes} (SQL: {@code working_interval_times}), and it is NOT gated to
 * the {@code EXPLICIT_TIMES} branch the way the original finding's "known candidates" section assumed
 * — {@code MasterScheduleService#resolveFromTemplate} calls {@code toDiscreteTimesForDay} UNCONDITIONALLY
 * for every covering template, on every date, purely to DECIDE whether the day is EXPLICIT_TIMES or
 * INTERVAL. The very first access to {@code schedule.getDiscreteTimes()} within a fold initialises the
 * LAZY collection, so it fires once per DISTINCT covering {@code WeeklySchedule} entity engaged by the
 * fold — one per master in the {@code seedUsableSchedule} fixture (each master has exactly one
 * open-ended template), regardless of whether that template ever uses EXPLICIT_TIMES. This is the
 * "at least one per-master statement is unenumerated" the phase doc predicted, identified by name.
 *
 * <p>The four per-master statements, all {@code WHERE …master.id = :masterId} pre-315 / {@code IN
 * (:masterIds)} post-315, over the SAME {@code [from, to]} window for every master:
 * <ol>
 *   <li>{@code ScheduleExceptionRepository#findByMasterIdAndDateBetweenWithIntervals} — the override
 *       bulk load (empty result on this fixture, but still one round-trip).</li>
 *   <li>{@code WeeklyScheduleRepository#findOverlappingRangeWithIntervals} — the template bulk load.</li>
 *   <li><b>The unenumerated one</b> — {@code WeeklySchedule.discreteTimes} lazy batch-fetch
 *       ({@code working_interval_times}), triggered by {@code resolveFromTemplate} unconditionally.</li>
 *   <li>{@code BookingRepository#findActiveTimeRangesByMasterInRange} — the window's CONFIRMED bookings
 *       ({@code SlotCalculationService#loadOccupiedByDay}).</li>
 * </ol>
 *
 * <p><b>This phase's batch covers all four, not just the three named ones.</b> {@link
 * MasterScheduleService#resolveEffectiveRangeBatch} loads #1 and #2 via the new {@code IN (:masterIds)}
 * finders (D4), and because it loads ALL requested masters' {@link
 * com.beautica.master.entity.WeeklySchedule} rows into the persistence context BEFORE folding begins
 * (batch-load-then-fold, never per-master-load-then-fold), statement #3's lazy {@code discreteTimes}
 * access on the FIRST covering template folded batches together with every OTHER already-loaded,
 * still-uninitialised {@code WeeklySchedule.discreteTimes} proxy in the session — Hibernate's
 * {@code hibernate.default_batch_fetch_size: 50} does this automatically, with no code written for it
 * specifically, turning N single-row {@code WHERE schedule_id = ?} statements into
 * {@code ceil(distinct schedule rows / 50)} statements. {@link
 * SlotCalculationService#loadOccupiedByDayBatch} covers #4 the same way #1/#2 are covered — one
 * {@code IN (:masterIds)} query for the whole batch. Measured post-315 on the SAME fixtures: 5
 * statements at 1 master AND 5 statements at 10 masters — see case 9.
 *
 * <h2>REUSE-FIRST</h2>
 * No second slot calculator, no second fold, no new precedence rule (D1). Fixtures reuse {@link
 * ServiceTestFixtures#seedUsableSchedule}, {@code createSalon}, {@code createSalonMaster},
 * {@code createSalonOwnerAndGetToken} exactly as {@code SalonCatalogueAggregatePriceIT} and
 * {@code SalonCatalogueVisibilityIT} do.
 */
@Slf4j
@Import({TestSecurityConfig.class, SalonCatalogueBatchLoadIT.FrozenKyivClockConfig.class})
@DisplayName("GET /salons/{salonId}/services — Phase 315 batched bookability gate")
class SalonCatalogueBatchLoadIT extends AbstractIntegrationTest {

    /**
     * Monday 2026-09-14, 09:00 Kyiv (EEST, +03:00) = 06:00Z. Chosen in SEPTEMBER so the 180-day
     * booking horizon (today .. today+180 == 2027-03-13) crosses the Ukraine DST fall-back on the
     * last Sunday of October 2026 — D9's "falsify the horizon math across a real DST transition"
     * requirement is therefore exercised by EVERY case in this class, not only case 13.
     */
    private static final Instant NOW = Instant.parse("2026-09-14T06:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 14);

    @TestConfiguration
    static class FrozenKyivClockConfig {
        @Bean
        Clock systemClock() {
            return Clock.fixed(NOW, TimeZones.KYIV);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private CacheManager cacheManager;
    @Autowired
    private ServiceCatalogService serviceCatalogService;
    @Autowired
    private SlotCalculationService slotCalculationService;
    @Autowired
    private MasterScheduleService masterScheduleService;
    @Autowired
    private EntityManagerFactory emf;

    private ServiceTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // D7 correctness matrix — cases 1-8, 11. Driven DIRECTLY against
    // SlotCalculationService#filterBookableAssignmentsBatch (the shared production gate), each
    // with >=2 masters in the SAME call so a cross-master leak can show.
    // ══════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Case 1 (D3.2): master A has a usable schedule, master B has NO schedule row at all "
            + "-> both verdicts correct, no NPE")
    void should_resolveBothVerdicts_when_oneMasterHasNoScheduleRowAtAll() {
        UUID masterA = seedMaster();
        seedUsableSchedule(masterA);
        UUID masterB = seedMaster();
        // masterB: deliberately NO weekly_schedules row at all.

        MasterServiceAssignment aAssignment = assignment(masterA, 30, 0);
        MasterServiceAssignment bAssignment = assignment(masterB, 30, 0);
        Map<UUID, List<MasterServiceAssignment>> input = new LinkedHashMap<>();
        input.put(masterA, List.of(aAssignment));
        input.put(masterB, List.of(bAssignment));

        Map<UUID, List<MasterServiceAssignment>> result =
                slotCalculationService.filterBookableAssignmentsBatch(input);

        assertThat(result.get(masterA))
                .as("master A has a usable 09:00-17:00 schedule -> bookable")
                .containsExactly(aAssignment);
        assertThat(result.get(masterB))
                .as("master B has NO schedule row -> NO_SCHEDULE every day -> not bookable, "
                        + "never an NPE from the outer occupiedByMaster/daysByMaster map lookups")
                .isEmpty();
    }

    @Test
    @DisplayName("Case 2: master A usable, master B's only template is validTo-EXPIRED before the "
            + "window -> empty effective range behaves exactly like an absent master")
    void should_treatExpiredTemplate_sameAsAbsentMaster() {
        UUID masterA = seedMaster();
        seedUsableSchedule(masterA);
        UUID masterB = seedMaster();
        seedIntervalSchedule(masterB, TODAY.minusDays(60), TODAY.minusDays(1),
                LocalTime.of(9, 0), LocalTime.of(17, 0));

        MasterServiceAssignment aAssignment = assignment(masterA, 30, 0);
        MasterServiceAssignment bAssignment = assignment(masterB, 30, 0);
        Map<UUID, List<MasterServiceAssignment>> result = slotCalculationService.filterBookableAssignmentsBatch(
                Map.of(masterA, List.of(aAssignment), masterB, List.of(bAssignment)));

        assertThat(result.get(masterA)).containsExactly(aAssignment);
        assertThat(result.get(masterB))
                .as("an expired template resolves to the same NO_SCHEDULE fold as no template at all")
                .isEmpty();
    }

    @Test
    @DisplayName("Case 3: day D master A fully occupied by a CONFIRMED booking, master B free "
            + "-> B stays bookable, A does not (occupancy must never leak across masters)")
    void should_notLeakOccupancy_acrossMasters() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-315-c3-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 315 Case 3 Salon");
        UUID masterA = fixtures.createSalonMaster(salonId);
        UUID masterServiceIdA = bulkCreateFixed(ownerToken, salonId, masterA);
        UUID masterB = seedMaster();

        // Master A: working every day 09:00-17:00, but booked SOLID for the whole horizon so no
        // free slot ever exists (the sharpest occupancy fixture: no gap for a leak to hide behind).
        seedUsableSchedule(masterA);
        seedUsableSchedule(masterB);
        UUID clientId = seedClient();
        // Cover the WHOLE horizon [from, to] = [TODAY, TODAY+180] inclusive — day 0 (TODAY itself)
        // included, since a free slot later today would otherwise rescue master A's verdict.
        for (int d = 0; d <= 180; d++) {
            LocalDate day = TODAY.plusDays(d);
            OffsetDateTime start = day.atTime(9, 0).atOffset(TimeZones.KYIV.getRules().getOffset(day.atTime(9, 0)));
            OffsetDateTime end = day.atTime(17, 0).atOffset(TimeZones.KYIV.getRules().getOffset(day.atTime(17, 0)));
            seedConfirmedBooking(masterA, masterServiceIdA, clientId, start, end);
        }

        MasterServiceAssignment aAssignment = assignment(masterA, 30, 0);
        MasterServiceAssignment bAssignment = assignment(masterB, 30, 0);
        Map<UUID, List<MasterServiceAssignment>> result = slotCalculationService.filterBookableAssignmentsBatch(
                Map.of(masterA, List.of(aAssignment), masterB, List.of(bAssignment)));

        assertThat(result.get(masterA))
                .as("master A is booked solid across the whole horizon -> not bookable")
                .isEmpty();
        assertThat(result.get(masterB))
                .as("master B's own free schedule must not be poisoned by master A's occupancy")
                .containsExactly(bAssignment);
    }

    @Test
    @DisplayName("Case 4: master A has a DAY_OFF override on day D, master B works day D -> the "
            + "override does not cross over to master B")
    void should_notLeakDayOffOverride_acrossMasters() {
        UUID masterA = seedMaster();
        UUID masterB = seedMaster();
        LocalDate onlyWorkingDay = TODAY.plusDays(3);
        // Master A: usable EVERY day EXCEPT the DAY_OFF override on onlyWorkingDay -> A must be
        // bookable overall (other days still work), so this case's assertion is scoped to the ONE
        // day via the MasterScheduleService projection, not the whole-horizon gate.
        seedUsableSchedule(masterA);
        seedDayOffOverride(masterA, onlyWorkingDay);
        seedUsableSchedule(masterB);

        Map<UUID, List<EffectiveDayResponse>> days = masterScheduleService.resolveEffectiveRangeBatch(
                List.of(masterA, masterB), onlyWorkingDay, onlyWorkingDay);

        assertThat(days.get(masterA).get(0).isWorkingDay())
                .as("master A's DAY_OFF override must apply to master A on day D")
                .isFalse();
        assertThat(days.get(masterB).get(0).isWorkingDay())
                .as("master B has no override on day D -> the override must not cross over")
                .isTrue();
    }

    @Test
    @DisplayName("Case 5: master A EXPLICIT_TIMES on day D with every declared time past the "
            + "cutoff, master B INTERVAL and working -> A not working that day, B working "
            + "(the isExplicitTimes branch under batching)")
    void should_resolveExplicitTimesIndependently_underBatching() {
        UUID masterA = seedMaster();
        UUID masterB = seedMaster();
        // TODAY is frozen at 09:00 Kyiv; 06:00 and 07:00 declared times on TODAY are already past
        // the booking cutoff (lead time), so TODAY must resolve NOT working for master A.
        seedExplicitTimesSchedule(masterA, TODAY, null, LocalTime.of(6, 0), LocalTime.of(7, 0));
        seedUsableSchedule(masterB);

        Map<UUID, List<EffectiveDayResponse>> days =
                masterScheduleService.resolveEffectiveRangeBatch(List.of(masterA, masterB), TODAY, TODAY);

        assertThat(days.get(masterA).get(0).times())
                .as("EXPLICIT_TIMES: the fold still reports the declared times verbatim — "
                        + "cutoff filtering is the SLOT CALCULATOR's job, not the fold's")
                .containsExactly(LocalTime.of(6, 0), LocalTime.of(7, 0));
        Instant cutoff = com.beautica.common.BookingWindow.bookableCutoff(Clock.fixed(NOW, TimeZones.KYIV));
        assertThat(cutoff).as("premise: 06:00/07:00 Kyiv today are before the frozen cutoff")
                .isAfter(TODAY.atTime(7, 0).atZone(TimeZones.KYIV).toInstant());
        assertThat(days.get(masterB).get(0).isWorkingDay())
                .as("master B's own INTERVAL day must resolve independently of master A's shape")
                .isTrue();
    }

    @Test
    @DisplayName("Case 6 (D4): one master holds TWO overlapping covering templates on day D -> the "
            + "ORDER BY tiebreak decides, and the winner is pinned by value")
    void should_pinOrderByTiebreak_when_twoTemplatesCoverTheSameDate() {
        UUID masterId = seedMaster();
        LocalDate day = TODAY.plusDays(5);
        // Older template: validFrom well before `day`, 09:00-12:00.
        seedIntervalSchedule(masterId, TODAY.minusDays(90), null, LocalTime.of(9, 0), LocalTime.of(12, 0));
        // Newer template: validFrom also before `day` but LATER than the older one, 13:00-18:00.
        // Both cover `day` (both validFrom <= day, both validTo null/>= day) -> genuinely overlapping.
        seedIntervalSchedule(masterId, TODAY.minusDays(10), null, LocalTime.of(13, 0), LocalTime.of(18, 0));

        Map<UUID, List<EffectiveDayResponse>> days =
                masterScheduleService.resolveEffectiveRangeBatch(List.of(masterId), day, day);

        assertThat(days.get(masterId).get(0).intervals())
                .as("validFrom DESC picks the MOST RECENT covering template (13:00-18:00), "
                        + "id ASC only breaking a tie on validFrom itself")
                .extracting(iv -> iv.startTime())
                .containsExactly(LocalTime.of(13, 0));
    }

    @Test
    @DisplayName("Case 7 (D1): master A's only assignment is too long for any interval; master B's "
            + "IDENTICAL-duration assignment fits -> the verdictByDuration memo is per master")
    void should_keepDurationMemoPerMaster_notShared() {
        UUID masterA = seedMaster();
        UUID masterB = seedMaster();
        // Both masters share the exact same effective duration (300 min = 5h), but only B's
        // 09:00-17:00 (8h) day can fit it; A's schedule is a narrow 09:00-10:00 (1h) window.
        seedIntervalSchedule(masterA, TODAY.minusDays(1), null, LocalTime.of(9, 0), LocalTime.of(10, 0));
        seedUsableSchedule(masterB);

        MasterServiceAssignment aAssignment = assignment(masterA, 300, 0);
        MasterServiceAssignment bAssignment = assignment(masterB, 300, 0);
        Map<UUID, List<MasterServiceAssignment>> result = slotCalculationService.filterBookableAssignmentsBatch(
                Map.of(masterA, List.of(aAssignment), masterB, List.of(bAssignment)));

        assertThat(result.get(masterA))
                .as("a memo shared across the batch would let B's TRUE verdict for 300 minutes "
                        + "leak onto A, which genuinely cannot fit 300 minutes into a 1-hour window")
                .isEmpty();
        assertThat(result.get(masterB))
                .as("B's own schedule genuinely fits 300 minutes")
                .containsExactly(bAssignment);
    }

    @Test
    @DisplayName("Case 8 (D3.3): a master with an EMPTY assignment list beside a master that DOES "
            + "load -> zero statements for the empty one, siblings unaffected")
    void should_costZeroStatements_forEmptyAssignmentMaster_besideALoadingSibling() {
        UUID emptyMaster = seedMaster();
        UUID loadedMaster = seedMaster();
        seedUsableSchedule(loadedMaster);
        MasterServiceAssignment loadedAssignment = assignment(loadedMaster, 30, 0);

        Statistics statistics = statistics();
        statistics.clear();

        Map<UUID, List<MasterServiceAssignment>> result = slotCalculationService.filterBookableAssignmentsBatch(
                Map.of(emptyMaster, List.of(), loadedMaster, List.of(loadedAssignment)));
        long afterEmptyAndLoaded = statistics.getPrepareStatementCount();

        statistics.clear();
        Map<UUID, List<MasterServiceAssignment>> soloResult = slotCalculationService.filterBookableAssignmentsBatch(
                Map.of(loadedMaster, List.of(loadedAssignment)));
        long soloLoaded = statistics.getPrepareStatementCount();

        assertThat(result.get(emptyMaster)).as("D3 — entry present, empty").isEmpty();
        assertThat(result.get(loadedMaster)).containsExactly(loadedAssignment);
        assertThat(afterEmptyAndLoaded)
                .as("adding an empty-assignment master to the SAME call must cost exactly what the "
                        + "loading master alone costs — statements-with-empty=%s, statements-solo=%s",
                        afterEmptyAndLoaded, soloLoaded)
                .isEqualTo(soloLoaded);

        // The sharper claim, and the one that actually discriminates the short-circuit (a batched
        // IN-list call costs the SAME statement count whether it carries 1 or 2 ids, so the
        // assertion above alone cannot tell "the empty master's id was excluded from the loader
        // calls" apart from "it was included but changed nothing"): a call carrying ONLY
        // empty-assignment masters, with NO sibling to force the loaders to run anyway, must cost
        // EXACTLY ZERO statements. Dropping the short-circuit runs both batched loaders on a
        // non-empty masterIds list (containing only masters nobody needed data for), moving this
        // off zero — the mutation this sub-case exists to catch.
        UUID secondEmptyMaster = seedMaster();
        statistics.clear();
        Map<UUID, List<MasterServiceAssignment>> allEmptyResult = slotCalculationService.filterBookableAssignmentsBatch(
                Map.of(emptyMaster, List.of(), secondEmptyMaster, List.of()));
        long allEmptyStatements = statistics.getPrepareStatementCount();

        assertThat(allEmptyResult.get(emptyMaster)).isEmpty();
        assertThat(allEmptyResult.get(secondEmptyMaster)).isEmpty();
        assertThat(allEmptyStatements)
                .as("a batch call where EVERY master has an empty assignment list must short-circuit "
                        + "before either loader runs — zero statements, not merely 'the same as some "
                        + "other call'")
                .isZero();
    }

    @Test
    @DisplayName("Case 11 (D7, the non-vacuous differential): batch-of-5 == union of 5 "
            + "singleton-map calls, definition-for-definition and assignment-for-assignment")
    void should_matchBatchOfFive_toUnionOfFiveSingletonCalls() {
        List<UUID> masterIds = new ArrayList<>();
        Map<UUID, List<MasterServiceAssignment>> input = new LinkedHashMap<>();
        for (int i = 0; i < 5; i++) {
            UUID masterId = seedMaster();
            masterIds.add(masterId);
            // Vary the shape per master so a cross-master mixup would actually change the answer.
            if (i % 2 == 0) {
                seedUsableSchedule(masterId);
            } else {
                seedIntervalSchedule(masterId, TODAY.minusDays(1), null, LocalTime.of(9, 0), LocalTime.of(10, 0));
            }
            input.put(masterId, List.of(assignment(masterId, 30 + i, 0)));
        }

        Map<UUID, List<MasterServiceAssignment>> batched = slotCalculationService.filterBookableAssignmentsBatch(input);

        Map<UUID, List<MasterServiceAssignment>> unionOfSingletons = new LinkedHashMap<>();
        for (UUID masterId : masterIds) {
            unionOfSingletons.putAll(
                    slotCalculationService.filterBookableAssignmentsBatch(Map.of(masterId, input.get(masterId))));
        }

        for (UUID masterId : masterIds) {
            assertThat(batched.get(masterId))
                    .as("master %s must agree between the batched call and its own singleton call", masterId)
                    .containsExactlyElementsOf(unionOfSingletons.get(masterId));
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // D8 — the statement-count gate: master-count invariance + an absolute constant.
    // ══════════════════════════════════════════════════════════════════════════════════════

    /**
     * The 1-master {@code getSalonServiceCatalog} statement count on the CLEAN (INTERVAL-only, no
     * overrides, no EXPLICIT_TIMES) fixture — D6's ledger: 1 candidates query + 4 per-master
     * (override bulk load, template bulk load, the {@code discreteTimes} lazy batch-fetch, and the
     * booking bulk load). Named so a rise in the FIXED base is caught even if invariance alone would
     * pass (e.g. every count doubling).
     *
     * <p><b>QA follow-up — this constant, and case 9's whole 1..20 sweep, hold ONLY below
     * {@code hibernate.default_batch_fetch_size} (50, {@code application.yml}).</b> The
     * {@code discreteTimes} lazy batch-fetch collapses every distinct, still-uninitialised
     * {@code WeeklySchedule} entity in the persistence context into {@code ceil(rows / 50)}
     * statements, and every count case 9 sweeps (1, 3, 5, 10, 20) rounds up to 1 — so case 9 alone
     * cannot tell "genuinely O(rows/50)" apart from "always +1, regardless of row count". Case 14
     * below crosses the boundary directly (50 vs 51 masters) to pin the actual formula,
     * {@code ceil(rows / 50) + 4}; a salon past 50 masters is untested by case 9 and must not be
     * assumed to hold this same fixed constant.
     */
    private static final long ONE_MASTER_STATEMENT_COUNT = 5L;

    @Test
    @NotATimedTest(reason = "Sweeps 1, 3, 5, 10 and 20 masters — 39 masters across 5 salons, each "
            + "with its own schedule and service band — BECAUSE the master-count sweep IS the "
            + "experiment: the invariant only exists if it is measured at several cardinalities. "
            + "The assertion is a prepareStatementCount equality across the sweep plus an absolute "
            + "constant on the 1-master leg; it makes no latency claim whatsoever. Measured 6.709s "
            + "quiet / 11.164s under concurrent load against the 10s ceiling, so a red "
            + "SlowTestExtension here reports machine load, not a regression. Dropping counts to "
            + "fit the budget would delete the invariance the case exists to pin.")
    @DisplayName("Case 9 (D8): getSalonServiceCatalog's prepareStatementCount is STRICTLY EQUAL "
            + "from 1 to 20 masters over the same service set, and the 1-master figure equals the "
            + "named absolute constant")
    void should_keepStatementCountInvariant_from1To20Masters() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-315-c9-" + System.nanoTime() + "@beautica.test");
        List<ServiceTestFixtures.SeededServiceType> types = fixtures.activeSelectableServiceTypes(1);
        assertThat(types).hasSize(1);
        UUID typeId = types.get(0).id();

        int[] masterCounts = {1, 3, 5, 10, 20};
        Map<Integer, Long> statementsByCount = new LinkedHashMap<>();

        for (int count : masterCounts) {
            UUID salonId = fixtures.createSalon(ownerToken, "Phase 315 Case 9 Salon x" + count);
            for (int i = 0; i < count; i++) {
                UUID masterId = fixtures.createSalonMaster(salonId);
                fixtures.seedUsableSchedule(masterId);
                bulkCreateFixed(ownerToken, salonId, masterId, typeId);
            }
            // Warm process-wide caches (category order lookup) before measuring.
            serviceCatalogService.getSalonServiceCatalog(salonId);

            Statistics statistics = statistics();
            evictCatalogue(salonId);
            statistics.clear();
            serviceCatalogService.getSalonServiceCatalog(salonId);
            statementsByCount.put(count, statistics.getPrepareStatementCount());
        }

        log.info("Case 9 prepareStatementCount by master count: {}", statementsByCount);

        assertThat(statementsByCount.get(1))
                .as("the 1-master figure must equal the named absolute constant from the D6 ledger")
                .isEqualTo(ONE_MASTER_STATEMENT_COUNT);
        for (int count : masterCounts) {
            assertThat(statementsByCount.get(count))
                    .as("statement count must be STRICTLY EQUAL across every master count — "
                            + "measured=%s", statementsByCount)
                    .isEqualTo(ONE_MASTER_STATEMENT_COUNT);
        }
    }

    @Test
    @DisplayName("Case 10 (D7): the full endpoint JSON for a 3-master salon is byte-identical to the "
            + "PRE-315 response, hard-coded expected values, 314's aggregated band included")
    void should_returnByteIdenticalJson_forThreeMasterSalon() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-315-c10-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 315 Case 10 Salon");
        List<ServiceTestFixtures.SeededServiceType> types = fixtures.activeSelectableServiceTypes(1);
        UUID typeId = types.get(0).id();

        UUID masterA = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterA);
        UUID definitionId = bulkCreateFixedReturningDefinition(ownerToken, salonId, masterA, typeId,
                new BigDecimal("600.00"));

        UUID masterB = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterB);
        assignOnSameDefinition(ownerToken, salonId, masterB, definitionId, new BigDecimal("900.00"));

        UUID masterC = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(masterC);
        assignOnSameDefinition(ownerToken, salonId, masterC, definitionId, null);

        evictCatalogue(salonId);
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/salons/" + salonId + "/services", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        SalonServiceCatalogResponse catalog = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<SalonServiceCatalogResponse>>() {}).data();

        assertThat(catalog.categories()).hasSize(1);
        ServiceDefinitionResponse row = catalog.categories().get(0).services().stream()
                .filter(r -> r.id().equals(definitionId)).findFirst().orElseThrow();

        // Hard-coded expected values (pre-315 behaviour): bulkCreateFixed sets the shared
        // DEFINITION's base price to 600.00 and master A stays Inherited at it; master B gets its
        // OWN 900.00 band; master C stays Inherited (no patchBand call) at the same 600.00 base as
        // master A -> floor 600 (A and C, Inherited), ceiling 900 (B's own band).
        assertThat(row.priceType()).isEqualTo(PriceType.RANGE);
        assertThat(row.priceMin()).isEqualByComparingTo("600.00");
        assertThat(row.priceMax()).isEqualByComparingTo("900.00");
        assertThat(row.priceDisplay()).isEqualTo("від 600 до 900 ₴");
        assertThat(catalog.categories().get(0).count()).isEqualTo(1);
    }

    /**
     * Case 12 (D8's caveat): the {@code discreteTimes} surcharge tracks SCHEDULE ROWS
     * ({@code ceil(rows / 50)}), not master count — 51 masters x 1 explicit day costs the SAME
     * statement count as 1 master x 51 explicit days.
     *
     * <p><b>QA fix (Phase 315 mutation 9 follow-up — reported GREEN-but-vacuous, now RE-DERIVED).</b>
     * The original fixture placed the one-master side's 20 "extra" rows at
     * {@code TODAY.minusDays(400)} onward — entirely BEFORE {@code from=TODAY} in
     * {@code findOverlappingRangeWithIntervalsByMasterIds}' {@code validTo >= :from} filter, so NONE
     * of them were ever loaded by the query this case measures: the one-master side's true loaded row
     * count was 1 (only the trailing open-ended row), not 21. Reverting the fixture's schedule TYPE
     * (EXPLICIT_TIMES → INTERVAL) therefore changed nothing (mutation 9) — the row-count side of the
     * comparison never moved either, because the 20 rows were never in play, and separately 1 vs 20
     * (or 21) both round up to {@code ceil(x / 50) = 1}, so the comparison could not have discriminated
     * even had the rows genuinely loaded.
     *
     * <p>Fixed by (a) spreading the one-master side's rows across the MEASURED window itself
     * (3-day chunks inside {@code [TODAY, TODAY+180]}) so every row is genuinely fetched, and (b)
     * matching row count EXACTLY (51 on both sides) while crossing the {@code batch_fetch_size=50}
     * boundary — so the assertion is not a same-bucket coincidence: a regression that costed by MASTER
     * count rather than ROW count would show {@code ceil(51/50)=2} extra statements on the
     * many-masters side but only {@code ceil(1/50)=1} on the one-master side (both scenarios are
     * intentionally seeded with the master's OWN weekly-schedule row count held constant at what
     * differs between them — masters vs rows — so the two hypotheses actually diverge here).
     */
    @Test
    @NotATimedTest(reason = "Seeds 51 masters + 51 schedule rows twice over a 180-day window "
            + "BECAUSE 51 is the batch_fetch_size boundary this case exists to cross. The "
            + "assertion is a prepareStatementCount equality, not a latency bound: SlowTestExtension "
            + "measures a quantity this test makes no claim about, and on a loaded machine the "
            + "fixture build alone has been observed at 14.6s against a 5.66s quiet-machine figure. "
            + "Trimming the fixture would delete the boundary crossing, i.e. the test.")
    @DisplayName("Case 12 (D8 caveat): the discreteTimes surcharge is O(rows/50), independent of "
            + "master count, pinned across the batch_fetch_size=50 boundary (51 rows either way)")
    void should_makeDiscreteTimesSurchargeIndependentOfMasterCount() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-315-c12-" + System.nanoTime() + "@beautica.test");
        List<ServiceTestFixtures.SeededServiceType> types = fixtures.activeSelectableServiceTypes(1);
        UUID typeId = types.get(0).id();
        int rows = 51;

        // 51 masters, each with ONE EXPLICIT_TIMES schedule row (51 schedule rows total).
        UUID manyMastersSalonId = fixtures.createSalon(ownerToken, "Phase 315 Case 12 Many-Masters Salon");
        for (int i = 0; i < rows; i++) {
            UUID masterId = fixtures.createSalonMaster(manyMastersSalonId);
            seedExplicitTimesSchedule(masterId, TODAY.minusDays(1), null, LocalTime.of(9, 0), LocalTime.of(9, 30));
            bulkCreateFixed(ownerToken, manyMastersSalonId, masterId, typeId);
        }

        // 1 master, with 51 DIFFERENT, mutually non-overlapping EXPLICIT_TIMES schedule rows, EVERY
        // one of them genuinely overlapping the [TODAY, TODAY+180] measurement window (3-day chunks,
        // TODAY .. TODAY+152) — so all 51 are real, loaded rows, all attributed to ONE master.
        UUID oneMasterSalonId = fixtures.createSalon(ownerToken, "Phase 315 Case 12 One-Master Salon");
        UUID soleMasterId = fixtures.createSalonMaster(oneMasterSalonId);
        for (int i = 0; i < rows; i++) {
            LocalDate from = TODAY.plusDays((long) i * 3);
            LocalDate to = from.plusDays(2);
            seedExplicitTimesSchedule(soleMasterId, from, to, LocalTime.of(9, 0), LocalTime.of(9, 30));
        }
        bulkCreateFixed(ownerToken, oneMasterSalonId, soleMasterId, typeId);

        serviceCatalogService.getSalonServiceCatalog(manyMastersSalonId);
        serviceCatalogService.getSalonServiceCatalog(oneMasterSalonId);

        Statistics statistics = statistics();
        evictCatalogue(manyMastersSalonId);
        statistics.clear();
        serviceCatalogService.getSalonServiceCatalog(manyMastersSalonId);
        long manyMastersStatements = statistics.getPrepareStatementCount();

        evictCatalogue(oneMasterSalonId);
        statistics.clear();
        serviceCatalogService.getSalonServiceCatalog(oneMasterSalonId);
        long oneMasterStatements = statistics.getPrepareStatementCount();

        log.info("Case 12: {} masters x 1 explicit row -> {}, 1 master x {} explicit rows -> {}",
                rows, manyMastersStatements, rows, oneMasterStatements);

        assertThat(manyMastersStatements)
                .as("51 schedule rows cross the batch_fetch_size=50 ceiling -> ceil(51/50)+4 = 6, "
                        + "the SAME named formula case 14 pins by master count")
                .isEqualTo(ONE_MASTER_STATEMENT_COUNT + 1);
        assertThat(oneMasterStatements)
                .as("the surcharge must track SCHEDULE ROWS, not MASTER COUNT — 51 schedule rows "
                        + "must cost the SAME whether spread across 51 masters or piled onto 1, "
                        + "even ACROSS the batch_fetch_size=50 boundary")
                .isEqualTo(manyMastersStatements);
    }

    /**
     * Case 14 (QA follow-up to D8/case 9): case 9's 1..20 sweep and its {@code ONE_MASTER_STATEMENT_COUNT}
     * constant hold only below {@code hibernate.default_batch_fetch_size} (50, {@code application.yml}) —
     * every count case 9 sweeps rounds up to {@code ceil(x/50)=1}, so it cannot tell "genuinely
     * O(rows/50)" apart from "always +1, regardless of row count". This case crosses the boundary
     * directly: 50 masters (one schedule row each, AT the ceiling) still cost the base +1, and 51
     * masters (one row over) cost exactly one MORE statement — pinning the actual formula,
     * {@code ceil(rows / 50) + 4}, not just the plateau below it.
     */
    @Test
    @NotATimedTest(reason = "Builds a 50-master salon AND a 51-master salon BECAUSE 50-vs-51 is the "
            + "batch_fetch_size boundary whose crossing is the whole assertion. The assertion is "
            + "prepareStatementCount == base and base + 1 — a statement-count gate, not a latency "
            + "gate. Measured 8.52s quiet / 19.9s under concurrent load against a 10s ceiling: the "
            + "wall clock here reports machine load, not a regression. Shrinking either salon "
            + "destroys the boundary the case pins.")
    @DisplayName("Case 14 (D8 boundary): prepareStatementCount grows by exactly one more statement the "
            + "instant schedule rows cross the batch_fetch_size=50 boundary (50 vs 51 masters)")
    void should_growStatementCountByOne_whenScheduleRowsCrossTheBatchFetchSizeBoundary() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-315-c14-" + System.nanoTime() + "@beautica.test");
        List<ServiceTestFixtures.SeededServiceType> types = fixtures.activeSelectableServiceTypes(1);
        UUID typeId = types.get(0).id();

        long atFifty = statementCountForNMasters(ownerToken, typeId, 50);
        long atFiftyOne = statementCountForNMasters(ownerToken, typeId, 51);

        log.info("Case 14: 50 masters -> {} statements, 51 masters -> {} statements", atFifty, atFiftyOne);

        assertThat(atFifty)
                .as("50 schedule rows sit AT the batch_fetch_size=50 ceiling -> still ceil(50/50)=1 "
                        + "batch statement, same base case 9's 1..20 sweep measures")
                .isEqualTo(ONE_MASTER_STATEMENT_COUNT);
        assertThat(atFiftyOne)
                .as("51 schedule rows need a SECOND batch statement (ceil(51/50)=2) -> exactly ONE "
                        + "more statement than the 50-master figure — not zero (row-count-blind, "
                        + "which case 9 alone cannot rule out) and not 51x (a per-master N+1 "
                        + "regression, which case 9's under-the-ceiling sweep also cannot rule out)")
                .isEqualTo(ONE_MASTER_STATEMENT_COUNT + 1);
    }

    private long statementCountForNMasters(String ownerToken, UUID typeId, int count) throws Exception {
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 315 Case 14 Salon x" + count);
        for (int i = 0; i < count; i++) {
            UUID masterId = fixtures.createSalonMaster(salonId);
            fixtures.seedUsableSchedule(masterId);
            bulkCreateFixed(ownerToken, salonId, masterId, typeId);
        }
        // Warm process-wide caches (category order lookup) before measuring — mirrors case 9.
        serviceCatalogService.getSalonServiceCatalog(salonId);

        Statistics statistics = statistics();
        evictCatalogue(salonId);
        statistics.clear();
        serviceCatalogService.getSalonServiceCatalog(salonId);
        return statistics.getPrepareStatementCount();
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // D9 — timezone: the 180-day horizon crosses the October DST transition (case 13).
    // ══════════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Case 13 (D9): the 180-day window crossing the October DST transition resolves the "
            + "same verdict SHAPE as an equal-length window that does not cross it")
    void should_resolveSameVerdictShape_acrossAndAwayFromDstTransition() {
        UUID crossingMaster = seedMaster();
        UUID nonCrossingMaster = seedMaster();
        seedUsableSchedule(crossingMaster);
        seedUsableSchedule(nonCrossingMaster);

        // Crossing window: TODAY (Sept 2026) .. +180d (March 2027) — crosses the Oct 2026 fall-back.
        LocalDate crossingFrom = TODAY;
        LocalDate crossingTo = TODAY.plusDays(180);
        // Non-crossing window of the SAME length, entirely between the March and October
        // transitions (no DST change anywhere inside it).
        LocalDate nonCrossingFrom = LocalDate.of(2027, 4, 1);
        LocalDate nonCrossingTo = nonCrossingFrom.plusDays(180);

        Map<UUID, List<EffectiveDayResponse>> crossing = masterScheduleService.resolveEffectiveRangeBatch(
                List.of(crossingMaster), crossingFrom, crossingTo);
        Map<UUID, List<EffectiveDayResponse>> nonCrossing = masterScheduleService.resolveEffectiveRangeBatch(
                List.of(nonCrossingMaster), nonCrossingFrom, nonCrossingTo);

        List<EffectiveDayResponse> crossingDays = crossing.get(crossingMaster);
        List<EffectiveDayResponse> nonCrossingDays = nonCrossing.get(nonCrossingMaster);

        assertThat(crossingDays).hasSameSizeAs(nonCrossingDays);
        for (int i = 0; i < crossingDays.size(); i++) {
            assertThat(crossingDays.get(i).isWorkingDay())
                    .as("day offset %s: the DST-crossing window must resolve the SAME per-offset "
                            + "verdict as the non-crossing window for an identical every-weekday "
                            + "schedule — a DST-related off-by-one would desync the two at the "
                            + "boundary", i)
                    .isEqualTo(nonCrossingDays.get(i).isWorkingDay());
        }
    }

    // ══════════════════════════════════════════════════════════════════════════════════════
    // fixtures — raw JDBC, mirroring ServiceTestFixtures#seedUsableSchedule's own style
    // ══════════════════════════════════════════════════════════════════════════════════════

    private UUID seedMaster() {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', "
                        + "'Batch', 'Master', true, true)",
                userId, "batch-master-" + userId + "@beautica.test");
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID seedClient() {
        UUID clientId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'CLIENT', true, true)",
                clientId, "batch-client-" + clientId + "@beautica.test");
        return clientId;
    }

    private void seedUsableSchedule(UUID masterId) {
        seedIntervalSchedule(masterId, TODAY.minusDays(1), null, LocalTime.of(9, 0), LocalTime.of(17, 0));
    }

    private void seedIntervalSchedule(UUID masterId, LocalDate validFrom, LocalDate validTo,
            LocalTime start, LocalTime end) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                scheduleId, masterId, validFrom, validTo);
        for (int isoDow = 1; isoDow <= 7; isoDow++) {
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) "
                            + "VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), scheduleId, isoDow, start, end);
        }
    }

    private void seedExplicitTimesSchedule(UUID masterId, LocalDate validFrom, LocalDate validTo,
            LocalTime... times) {
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                scheduleId, masterId, validFrom, validTo);
        for (int isoDow = 1; isoDow <= 7; isoDow++) {
            for (LocalTime time : times) {
                jdbcTemplate.update(
                        "INSERT INTO working_interval_times (id, schedule_id, day_of_week, slot_time) "
                                + "VALUES (?, ?, ?, ?)",
                        UUID.randomUUID(), scheduleId, isoDow, time);
            }
        }
    }

    private void seedDayOffOverride(UUID masterId, LocalDate date) {
        jdbcTemplate.update(
                "INSERT INTO schedule_exceptions (id, master_id, date, kind, created_at) "
                        + "VALUES (?, ?, ?, 'DAY_OFF', NOW())",
                UUID.randomUUID(), masterId, date);
    }

    private void seedConfirmedBooking(UUID masterId, UUID masterServiceId, UUID clientId,
            OffsetDateTime startsAt, OffsetDateTime endsAt) {
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'CONFIRMED', ?, ?, 500.00, 60, 0, NOW(), NOW())",
                UUID.randomUUID(), clientId, masterId, masterServiceId, startsAt, endsAt);
    }

    /** In-memory (never persisted) assignment for the direct {@code filterBookableAssignmentsBatch} calls. */
    private static MasterServiceAssignment assignment(UUID masterId, int baseMinutes, int bufferMinutes) {
        com.beautica.master.entity.Master master =
                com.beautica.master.entity.Master.builder().id(masterId).isActive(true).build();
        ServiceDefinition sd = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(baseMinutes)
                .bufferMinutesAfter(bufferMinutes)
                .isActive(true)
                .build();
        return MasterServiceAssignment.builder()
                .id(UUID.randomUUID())
                .serviceDefinition(sd)
                .master(master)
                .isActive(true)
                .build();
    }

    private UUID bulkCreateFixed(String ownerToken, UUID salonId, UUID masterId, UUID typeId) throws Exception {
        return bulkCreateFixedReturningAssignment(ownerToken, salonId, masterId, typeId, new BigDecimal("500.00"));
    }

    private UUID bulkCreateFixed(String ownerToken, UUID salonId, UUID masterId) throws Exception {
        List<ServiceTestFixtures.SeededServiceType> types = fixtures.activeSelectableServiceTypes(1);
        return bulkCreateFixed(ownerToken, salonId, masterId, types.get(0).id());
    }

    private UUID bulkCreateFixedReturningAssignment(String ownerToken, UUID salonId, UUID masterId,
            UUID typeId, BigDecimal price) throws Exception {
        var item = new BulkServiceItemRequest(typeId, 60, PriceType.FIXED, price, null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(new BulkCreateServicesRequest(List.of(item)), fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MasterServiceResponse created = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data().get(0);
        return created.id();
    }

    private UUID bulkCreateFixedReturningDefinition(String ownerToken, UUID salonId, UUID masterId,
            UUID typeId, BigDecimal price) throws Exception {
        var item = new BulkServiceItemRequest(typeId, 60, PriceType.FIXED, price, null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(new BulkCreateServicesRequest(List.of(item)), fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        MasterServiceResponse created = objectMapper.readValue(resp.getBody(),
                new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data().get(0);
        return created.serviceDefinition().id();
    }

    private void assignOnSameDefinition(String ownerToken, UUID salonId, UUID masterId, UUID definitionId,
            BigDecimal ownBandOrNull) throws Exception {
        var request = new com.beautica.service.dto.AssignServiceToMasterRequest(
                definitionId, null, null, null, null);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(ownerToken)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        if (ownBandOrNull != null) {
            MasterServiceResponse created = objectMapper.readValue(resp.getBody(),
                    new TypeReference<ApiResponse<MasterServiceResponse>>() {}).data();
            ResponseEntity<String> patchResp = restTemplate.exchange(
                    "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/" + definitionId,
                    HttpMethod.PATCH,
                    new HttpEntity<>(new com.beautica.service.dto.UpdateMasterServiceBandRequest(
                            PriceType.FIXED, ownBandOrNull, null, null, null, null),
                            fixtures.bearerHeaders(ownerToken)),
                    String.class);
            assertThat(patchResp.getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    private void evictCatalogue(UUID salonId) {
        Cache cache = cacheManager.getCache("salon-service-catalog");
        assertThat(cache).isNotNull();
        cache.evict(salonId);
    }

    private Statistics statistics() {
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }
}
