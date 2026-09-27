package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.common.BookingWindow;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.service.MasterScheduleService;
import com.beautica.master.service.ScheduleMapper;
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
import com.beautica.support.HibernateStatistics;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.assertj.core.api.SoftAssertions;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@Import({TestSecurityConfig.class, SalonCatalogueBatchLoadIT.FrozenKyivClockConfig.class,
        SalonCatalogueBatchLoadIT.FoldCountingScheduleMapperConfig.class})
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

    /**
     * The fold's only allocator of {@link EffectiveDayResponse}: every resolved day — override,
     * template or {@code NO_SCHEDULE} gap — is built by one of {@code ScheduleMapper.toEffectiveDay}'s
     * overloads. Counting those calls therefore COUNTS FOLDED DAYS directly, which is the quantity
     * cases 15/15b/15c bound; a statement counter cannot see this at all (the fold issues no
     * statements, which is exactly why the D8 ledger stayed flat while the fold was 181x larger than
     * it needed to be).
     *
     * <p><b>Explicitly ARMED, not a class-level spy (2026-09-15 perf LOW, test-infra).</b> This used
     * to be a {@code @MockitoSpyBean ScheduleMapper} declared at class level, which instrumented the
     * hottest inner loop for all sixteen cases while only the three fold-count cases ever read it —
     * cases 9/12/14 seed 20-60 masters and therefore paid ByteBuddy interception plus an
     * argument-capturing {@code Invocation} allocation for up to ~11 000 folds each, on tests this
     * class's own javadoc already flags as timing-fragile (they carry {@link NotATimedTest} against
     * {@code SlowTestExtension}'s wall-clock ceiling). {@link FoldCountingScheduleMapper} is a plain
     * subclass that delegates to the real mapper and records nothing until a test calls
     * {@link FoldCountingScheduleMapper#arm()}, so the other thirteen cases pay one predictable
     * branch instead.
     *
     * <p><b>Why not a {@code @Nested} block owning the spy</b> — the other option on the table.
     * {@code @MockitoSpyBean} is a bean override, so declaring it on a nested class puts that class
     * in a DIFFERENT {@code ApplicationContext} from its enclosing one: this class would boot two
     * Spring contexts instead of one (it already has a unique context key via
     * {@link FrozenKyivClockConfig}), and the nested tests would have to re-{@code @Autowired}
     * {@code SlotCalculationService} because the outer instance's fields are injected from the OUTER
     * context — a spy in one context cannot observe a service from the other. That trades a few tens
     * of milliseconds of Mockito overhead for a whole extra context boot, which is the wrong way
     * round for a finding whose whole subject is cost. Per-test {@code reset} was not an option
     * either: {@code @MockitoSpyBean} already resets after every test method, so it buys nothing.
     */
    @Autowired
    private FoldCountingScheduleMapper foldCounter;

    /**
     * Replaces the real {@link ScheduleMapper} bean (by {@code @Primary}) with a counting subclass —
     * see {@link #foldCounter}. {@code ScheduleMapper} is a dependency-free {@code @Component}, so
     * the subclass needs no constructor of its own and inherits every mapping method unchanged;
     * only the two {@code toEffectiveDay} overloads are intercepted, which is exactly the set the
     * old spy filtered for by method name.
     */
    @TestConfiguration
    static class FoldCountingScheduleMapperConfig {
        @Bean
        @Primary
        FoldCountingScheduleMapper foldCountingScheduleMapper() {
            return new FoldCountingScheduleMapper();
        }
    }

    /**
     * Delegating {@link ScheduleMapper} that tallies folded days BY DATE once {@link #arm()} is
     * called. The by-date histogram is what makes case 15c's per-master attribution possible: the
     * mapper never sees a master id, but three masters walking prefixes of the SAME date list leave
     * a strictly decreasing step function whose steps are the per-master walk lengths.
     */
    static final class FoldCountingScheduleMapper extends ScheduleMapper {

        private final Map<LocalDate, Integer> foldsByDate = new LinkedHashMap<>();
        private boolean armed;

        /** Starts counting from zero. Call AFTER fixture seeding, which also folds. */
        void arm() {
            foldsByDate.clear();
            armed = true;
        }

        void disarm() {
            armed = false;
            foldsByDate.clear();
        }

        long totalFolds() {
            return foldsByDate.values().stream().mapToLong(Integer::longValue).sum();
        }

        long foldsOn(LocalDate date) {
            return foldsByDate.getOrDefault(date, 0);
        }

        private void record(LocalDate date) {
            if (armed) {
                foldsByDate.merge(date, 1, Integer::sum);
            }
        }

        @Override
        public EffectiveDayResponse toEffectiveDay(LocalDate date,
                com.beautica.master.dto.EffectiveDaySource source,
                List<com.beautica.master.dto.WorkIntervalDto> intervals) {
            record(date);
            return super.toEffectiveDay(date, source, intervals);
        }

        @Override
        public EffectiveDayResponse toEffectiveDay(LocalDate date,
                com.beautica.master.dto.EffectiveDaySource source,
                List<com.beautica.master.dto.WorkIntervalDto> intervals,
                List<LocalTime> times) {
            record(date);
            return super.toEffectiveDay(date, source, intervals, times);
        }
    }

    private ServiceTestFixtures fixtures;

    @BeforeEach
    void setUp() {
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
     * Case 12 (D8's caveat): the {@code discreteTimes} surcharge tracks the schedule rows the fold
     * WALKS ({@code ceil(walked / 50)}), not master count — and 51 masters x 1 explicit day
     * therefore costs one statement MORE than 1 master x 51 explicit days, never fewer.
     *
     * <p><b>Re-derived 2026-09-15 (the fold bound, perf MEDIUM-3).</b> This case used to assert the
     * two sides were EQUAL, on the reasoning that both load 51 rows. That equality was a property of
     * the eager fold: every one of a master's covering templates got walked because every date got
     * folded. Now that the gate stops at the first bookable day
     * ({@code MasterScheduleService#reduceEffectiveRangeBatch}), the one-master side walks only the
     * template covering days 0-1 and leaves the other 50 rows' {@code discreteTimes} proxies
     * untouched — so it costs 5 where the many-masters side, which must fold all 51 masters to
     * produce 51 verdicts, costs 6. Both sides' VERDICTS are unchanged; only the one-master side's
     * statement count fell, which is the improvement showing through at the statement layer. The
     * anti-N+1 claim this case contributes is unaffected and is pinned twice over by case 9 (flat
     * across 1..20 masters) and case 14 (50 vs 51 masters costs +1, not +51).
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
    @DisplayName("Case 12 (D8 caveat): the discreteTimes surcharge is O(walked rows / 50) — 51 "
            + "masters x 1 row costs one statement MORE than 1 master x 51 rows, never fewer")
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
                .as("51 masters must each be folded, so all 51 schedule rows are walked and cross "
                        + "the batch_fetch_size=50 ceiling -> ceil(51/50)+4 = 6, the SAME named "
                        + "formula case 14 pins by master count")
                .isEqualTo(ONE_MASTER_STATEMENT_COUNT + 1);
        assertThat(oneMasterStatements)
                .as("ONE master's 51 rows cost ceil(1/50)+4 = 5, NOT 51/50+4: the fold stops at "
                        + "that master's first bookable day (day 1 — 09:00 on TODAY is already past "
                        + "the 09:15 cutoff), which lies inside the FIRST 3-day template, so the "
                        + "other 50 rows are loaded but never walked and their discreteTimes "
                        + "proxies are never initialised. 51x the ROWS on one master therefore "
                        + "costs LESS than 51 masters, never more — the surcharge tracks neither "
                        + "master count NOR loaded rows, but WALKED rows")
                .isEqualTo(ONE_MASTER_STATEMENT_COUNT);
        assertThat(oneMasterStatements)
                .as("the direction is the claim: piling rows onto one master may only ever cost "
                        + "LESS than spreading them across masters. Strictly more would mean the "
                        + "surcharge had started tracking loaded rows per master — a per-row N+1")
                .isLessThan(manyMastersStatements);
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
    // Case 15 (2026-09-15 perf MEDIUM-3 / security MEDIUM) — the FOLD bound.
    // ══════════════════════════════════════════════════════════════════════════════════════

    /**
     * The horizon the gate folds over: {@code today … today + 180}, inclusive — 181 days, which is
     * what a bookable master used to cost regardless of how early the answer was known.
     */
    private static final int HORIZON_DAYS = BookingWindow.MAX_DAYS_AHEAD + 1;

    /**
     * Generous ceiling for "the gate stopped almost immediately". The fixture below is bookable on
     * day 0 (frozen 09:00 Kyiv, a 09:00-17:00 interval every weekday, cutoff 09:15), so the true
     * count is 1; the slack absorbs a fixture or lead-time tweak without letting a regression to the
     * 181-day fold through — any accidental re-materialisation lands two orders of magnitude above.
     */
    private static final long MAX_FOLDED_DAYS_FOR_AN_EARLY_BOOKABLE_MASTER = 10L;

    @Test
    @NotATimedTest(reason = "asserts a FOLD COUNT measured by a counting mapper, not wall-clock time")
    @DisplayName("Case 15: a master bookable on the first day costs a handful of folded days, not "
            + "the whole 181-day horizon — while the verdict stays bookable")
    void should_foldOnlyTheDaysWalked_when_theMasterIsBookableEarlyInTheHorizon() {
        UUID masterId = seedMaster();
        seedUsableSchedule(masterId);
        MasterServiceAssignment msa = assignment(masterId, 30, 0);
        // The fixture seeding above also folds; arming here counts only the gate's own folds.
        foldCounter.arm();

        Map<UUID, List<MasterServiceAssignment>> result =
                slotCalculationService.filterBookableAssignmentsBatch(Map.of(masterId, List.of(msa)));

        long foldedDays = foldCounter.totalFolds();
        log.info("Case 15 folded days for a day-0-bookable master: {} (horizon = {})",
                foldedDays, HORIZON_DAYS);

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(result.get(masterId))
                    .as("THE VERDICT IS THE INVARIANT: bounding the fold may not change who is "
                            + "bookable — this master has a free 09:00-17:00 day and must survive "
                            + "the gate exactly as before the bound existed")
                    .containsExactly(msa);
            softly.assertThat(foldedDays)
                    .as("the gate answers a BOOLEAN and returns at the first bookable day, so it "
                            + "must fold only the prefix it walked. Before the 2026-09-15 bound it "
                            + "folded all %s days per master first — measured=%s",
                            HORIZON_DAYS, foldedDays)
                    .isLessThanOrEqualTo(MAX_FOLDED_DAYS_FOR_AN_EARLY_BOOKABLE_MASTER);
            softly.assertThat(foldedDays)
                    .as("premise: the gate really did fold SOMETHING — a zero would mean the spy "
                            + "missed the fold and the bound above is vacuous")
                    .isGreaterThanOrEqualTo(1L);
        });
    }

    @Test
    @NotATimedTest(reason = "asserts a FOLD COUNT measured by a counting mapper, not wall-clock time")
    @DisplayName("Case 15b: a master bookable on NO day still folds the whole horizon — the bound "
            + "is an early exit, never a truncated search")
    void should_foldTheWholeHorizon_when_noDayIsBookable() {
        UUID masterId = seedMaster();
        // No schedule row at all -> every one of the 181 days resolves NO_SCHEDULE, and the gate
        // can only prove "not bookable" by looking at all of them.
        MasterServiceAssignment msa = assignment(masterId, 30, 0);
        foldCounter.arm();

        Map<UUID, List<MasterServiceAssignment>> result =
                slotCalculationService.filterBookableAssignmentsBatch(Map.of(masterId, List.of(msa)));

        long foldedDays = foldCounter.totalFolds();

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(result.get(masterId))
                    .as("no schedule -> not bookable, exactly as before the bound")
                    .isEmpty();
            softly.assertThat(foldedDays)
                    .as("a negative verdict has no shorter proof: every day must be folded, so the "
                            + "count is the full horizon. This is the other half of 'the verdict is "
                            + "unchanged' — the bound never stops the search early on a NEGATIVE")
                    .isEqualTo(HORIZON_DAYS);
        });
    }

    /**
     * Case 15c — the MULTI-master arm of the fold bound (2026-09-15 perf LOW).
     *
     * <p><b>The blind spot this closes.</b> Every other fold-count pin — cases 15 and 15b above, and
     * all three {@code MasterScheduleServiceTest} arms — calls the gate with ONE master. A regression
     * where one master's early exit leaked into another's view, or where the memo array were hoisted
     * and shared across masters, changes NOTHING a statement counter or a verdict assertion can see:
     * it shows up only as a fold count, and only when more than one master is in flight. Exactly the
     * blind-spot class the statement-count ledger had before it was widened.
     *
     * <p><b>Per-master attribution, from a mapper that never sees a master id.</b> The counting
     * mapper tallies folds BY DATE. All three masters walk PREFIXES of the same 181-date list, so the
     * histogram is a step function whose steps are the walk lengths:
     *
     * <pre>
     *   master          fixture                                   first bookable   days walked
     *   A  seedUsableSchedule (valid from today-1, 09:00-17:00)    day 0            1
     *   B  interval schedule valid from today+90, 09:00-17:00      day 90           91
     *   C  no weekly_schedules row at all                          never            181 (whole horizon)
     *
     *   folds on today+0            = A + B + C = 3
     *   folds on today+1 … today+90 = B + C     = 2   (90 dates)
     *   folds on today+91 … +180    = C         = 1   (90 dates)
     *   total                       = 3 + 180 + 90   = 273 = 1 + 91 + 181
     * </pre>
     *
     * The step positions are the walk lengths, so asserting the three bands IS asserting 1 / 91 / 181
     * per master — exactly, not as a range. A shared memo would collapse every band to 1 (total 181);
     * a leaked early exit would truncate C's band; a lost early exit would flatten every band to 3
     * (total 543).
     *
     * <p>Day 90 is {@code 2026-12-13}, a Sunday, and the fixture seeds all seven ISO weekdays, so
     * B's first covered date really is its {@code valid_from} and not some later weekday.
     */
    @Test
    @NotATimedTest(reason = "asserts a FOLD COUNT measured by a counting mapper, not wall-clock time")
    @DisplayName("Case 15c: three masters in ONE batch fold exactly 1 / 91 / 181 days — each early "
            + "exit is private to its own master, and all three verdicts are correct")
    void should_boundTheFoldPerMaster_when_theBatchHoldsThreeMastersWithDifferentFirstBookableDays() {
        UUID bookableOnDayZero = seedMaster();
        seedUsableSchedule(bookableOnDayZero);
        UUID bookableOnDayNinety = seedMaster();
        seedIntervalSchedule(bookableOnDayNinety, TODAY.plusDays(FIRST_BOOKABLE_DAY_OF_THE_LATE_MASTER),
                null, LocalTime.of(9, 0), LocalTime.of(17, 0));
        UUID neverBookable = seedMaster();

        MasterServiceAssignment earlyMsa = assignment(bookableOnDayZero, 30, 0);
        MasterServiceAssignment lateMsa = assignment(bookableOnDayNinety, 30, 0);
        MasterServiceAssignment neverMsa = assignment(neverBookable, 30, 0);
        Map<UUID, List<MasterServiceAssignment>> input = new LinkedHashMap<>();
        input.put(bookableOnDayZero, List.of(earlyMsa));
        input.put(bookableOnDayNinety, List.of(lateMsa));
        input.put(neverBookable, List.of(neverMsa));
        // The fixture seeding above also folds; arming here counts only the gate's own folds.
        foldCounter.arm();

        Map<UUID, List<MasterServiceAssignment>> result =
                slotCalculationService.filterBookableAssignmentsBatch(input);

        log.info("Case 15c total folded days across three masters: {} (expected {} = 1 + {} + {})",
                foldCounter.totalFolds(), EXPECTED_TOTAL_FOLDS_FOR_THE_THREE_MASTERS,
                FIRST_BOOKABLE_DAY_OF_THE_LATE_MASTER + 1, HORIZON_DAYS);

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(result.get(bookableOnDayZero))
                    .as("THE VERDICT IS THE INVARIANT: a master free from 09:00 today is bookable")
                    .containsExactly(earlyMsa);
            softly.assertThat(result.get(bookableOnDayNinety))
                    .as("a master whose template only starts on day %s is still bookable — the "
                            + "early exit is an optimisation, not a shorter horizon",
                            FIRST_BOOKABLE_DAY_OF_THE_LATE_MASTER)
                    .containsExactly(lateMsa);
            softly.assertThat(result.get(neverBookable))
                    .as("a master with no schedule at all is not bookable, and neither neighbour's "
                            + "early exit may make it look bookable")
                    .isEmpty();

            softly.assertThat(foldCounter.foldsOn(TODAY))
                    .as("day 0 is walked by ALL THREE masters — one fold each, never one shared "
                            + "fold reused across masters (a hoisted memo would read 1 here)")
                    .isEqualTo(3L);
            for (int i = 1; i <= FIRST_BOOKABLE_DAY_OF_THE_LATE_MASTER; i++) {
                LocalDate date = TODAY.plusDays(i);
                softly.assertThat(foldCounter.foldsOn(date))
                        .as("day %s: master A exited at day 0, so only B and C reach here", i)
                        .isEqualTo(2L);
            }
            for (int i = FIRST_BOOKABLE_DAY_OF_THE_LATE_MASTER + 1; i < HORIZON_DAYS; i++) {
                LocalDate date = TODAY.plusDays(i);
                softly.assertThat(foldCounter.foldsOn(date))
                        .as("day %s: A and B have both exited, so only C — the never-bookable "
                                + "master — still walks", i)
                        .isEqualTo(1L);
            }
            softly.assertThat(foldCounter.foldsOn(TODAY.plusDays(HORIZON_DAYS)))
                    .as("nothing is folded past the horizon's last day")
                    .isZero();
            softly.assertThat(foldCounter.totalFolds())
                    .as("the three bands sum to 1 + 91 + 181; before the bound existed this batch "
                            + "cost 3 x %s = %s folds", HORIZON_DAYS, 3L * HORIZON_DAYS)
                    .isEqualTo(EXPECTED_TOTAL_FOLDS_FOR_THE_THREE_MASTERS);
        });
    }

    /** Case 15c's middle master: its template's {@code valid_from} is {@code today + 90}. */
    private static final int FIRST_BOOKABLE_DAY_OF_THE_LATE_MASTER = 90;

    /** 1 (day-0 master) + 91 (day-90 master) + 181 (never-bookable master) — see case 15c. */
    private static final long EXPECTED_TOTAL_FOLDS_FOR_THE_THREE_MASTERS =
            1L + (FIRST_BOOKABLE_DAY_OF_THE_LATE_MASTER + 1) + HORIZON_DAYS;

    /** The counting mapper is a context singleton; leaving it armed would leak into the next test. */
    @AfterEach
    void disarmFoldCounter() {
        foldCounter.disarm();
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
        return HibernateStatistics.enabledOn(emf);
    }
}
