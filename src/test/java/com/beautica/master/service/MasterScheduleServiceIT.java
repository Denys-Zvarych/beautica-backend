package com.beautica.master.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.dto.EffectiveDaySource;
import com.beautica.master.dto.MasterWorkingDayResponse;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.ScheduleOverrideResponse;
import com.beautica.master.dto.WeeklyScheduleDayRequest;
import com.beautica.master.dto.WeeklyScheduleDayResponse;
import com.beautica.master.dto.WeeklyScheduleRequest;
import com.beautica.master.dto.WeeklyScheduleResponse;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;
import com.beautica.master.repository.ScheduleExceptionRepository;
import com.beautica.master.repository.WeeklyScheduleRepository;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.jdbc.core.JdbcTemplate;

import jakarta.persistence.EntityManagerFactory;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Phase 15.4 — full-stack guard for {@link MasterScheduleService} (the security-critical schedule
 * layer) + {@link ScheduleMapper}, driven directly against the autowired service over a real
 * Testcontainers Postgres.
 *
 * <h2>Why this test exists</h2>
 * The 15.1 schema IT proved the table contract; the service IT proves the BUSINESS contract that the
 * repository finders deliberately do NOT enforce:
 * <ul>
 *   <li><b>Ownership / IDOR</b> — the unscoped finders trust the caller's {@code masterId}; this
 *       service is the only gate. Pinned on every write path + the foreign-{@code scheduleId} edit
 *       path (must 404, not leak existence).</li>
 *   <li><b>Invariants</b> — no past edit, far-future cap, per-master window overlap, intra-day
 *       interval overlap/ordering, seconds zeroing.</li>
 *   <li><b>Effective-availability resolver</b> — override beats template beats gap; multi-week range
 *       folds correctly without N+1 / lazy-init.</li>
 *   <li><b>Data exposure</b> — {@code EffectiveDayResponse} exposes only date/source/intervals (the
 *       former {@code reason}/{@code note} were removed in V83).</li>
 * </ul>
 *
 * <p>Dates are chosen relative to {@code LocalDate.now()} with a safe margin so the suite is
 * deterministic without overriding the {@code Clock} bean (which would fragment the context cache).
 * Deeper edge matrix (DST, leap-year boundary resolution, exhaustive OQ cases) is deferred to 15.7.
 */
@DisplayName("MasterScheduleService — ownership, invariants, resolver (full stack, real Postgres)")
class MasterScheduleServiceIT extends AbstractIntegrationTest {

    @Autowired
    private MasterScheduleService scheduleService;

    @Autowired
    private WeeklyScheduleRepository weeklyScheduleRepository;

    @Autowired
    private ScheduleExceptionRepository scheduleExceptionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private org.springframework.cache.CacheManager cacheManager;

    // Safe-margin future dates: well clear of "today" so no-past-edit never trips by accident.
    private static final LocalDate FUTURE_FROM = LocalDate.now().plusDays(30);

    /**
     * Upper bound on statements issued by one AVAILABILITY-path fold — {@code resolveEffectiveRange} and
     * {@code getClientWorkingDays} — SHARED by the 28-day and the 90-day guard so the two can never drift
     * apart. Span-independence (the same bound holding for a 3× longer span) is the actual no-N+1 property
     * being guarded; the absolute number is incidental.
     *
     * <p><b>Composition</b> (derived by measurement, not assumed — the previous version of this comment
     * claimed a spare "tolerance for read-only transaction bookkeeping" that does not exist, and omitted
     * statement 3 entirely):
     * <ol>
     *   <li>{@code findByMasterIdAndDateBetweenWithIntervals} — the override bulk load. Always.</li>
     *   <li>{@code findOverlappingRangeWithIntervals} — the template bulk load. Always.</li>
     *   <li>batched init of {@code ScheduleException.discreteTimes} — whenever ≥1 <em>non-DAY_OFF</em>
     *       override falls in range. Neither {@code ScheduleExceptionRepository} finder fetch-joins that
     *       collection (both take {@code LEFT JOIN FETCH se.intervals} only), so
     *       {@code MasterScheduleService#resolveFromOverride}'s {@code toOverrideDiscreteTimes} call
     *       lazy-initializes it.</li>
     *   <li>batched init of {@code WeeklySchedule.discreteTimes} (15.8) — whenever a template covers.</li>
     * </ol>
     *
     * <p><b>This ceiling therefore pins SPAN-INDEPENDENCE, not the window leak.</b> A fixture with no
     * override in range spends only 3 of the 4, and that one statement of slack is exactly the cost of a
     * {@code dayWindows} load — so a leak-back onto the availability path can hide underneath this bound.
     * Detecting that is {@code P1d}'s job (the differential guard); see it before assuming this number
     * protects anything it does not.
     *
     * <p><b>Back to 4 in Phase 15.12 (resolver split).</b> It was briefly 5 while the window projection ran
     * inside the shared resolver. The split moved that projection to a decoration step on
     * {@code resolveEffectiveRangeForDisplay} alone, so no availability caller loads {@code dayWindows} —
     * see {@link #MAX_DISPLAY_RESOLVER_QUERIES}. Keeping this tight is the point: it is what would catch a
     * future change quietly re-attaching the window to the availability path.
     */
    private static final long MAX_RESOLVER_QUERIES = 4L;

    /**
     * Upper bound for the DISPLAY fold ({@code resolveEffectiveRangeForDisplay}), which additionally
     * projects the Phase 15.12 display-only working window: the batched {@code dayWindows} hydration, once
     * for the whole fold regardless of span — never one per date.
     *
     * <p>Carries the same one-statement slack as {@link #MAX_RESOLVER_QUERIES} for the same reason (a
     * fixture with no override in range), so on its own it would tolerate the window costing <em>two</em>
     * extra statements while still reading as "exactly one". The "exactly one" half of the contract is
     * asserted relationally by {@code P1d}; this constant, like its sibling, pins span-independence only.
     */
    private static final long MAX_DISPLAY_RESOLVER_QUERIES = MAX_RESOLVER_QUERIES + 1L;

    /**
     * One fold's JDBC statement count, measured with Hibernate {@link Statistics}. Extracted because the
     * enable/clear/read/restore dance appeared verbatim in four guards (§Q4), and because {@code P1d} must
     * measure two folds under provably identical mechanics — an inlined copy that drifted would silently
     * invalidate the differential it asserts.
     *
     * <p>Restores the previous statistics-enabled state in a {@code finally} so a failing fold cannot leave
     * statistics switched on for the rest of the class.
     */
    private <T> Measured<T> measure(java.util.function.Supplier<T> fold) {
        Statistics stats = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        boolean wasEnabled = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        stats.clear();
        try {
            T result = fold.get();
            return new Measured<>(result, stats.getPrepareStatementCount());
        } finally {
            stats.setStatisticsEnabled(wasEnabled);
        }
    }

    /** A fold's result paired with the number of JDBC statements it cost. */
    private record Measured<T>(T result, long queries) {
    }

    // ── fixtures: seed an INDEPENDENT_MASTER (owns itself) + a SALON_OWNER salon/master ──

    /** A solo master + its user; {@code actorId == userId} is the rightful owner. */
    private SeededMaster seedIndependentMaster() {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', "
                        + "'Ind', 'Master', true, true)",
                userId, "ind-" + userId + "@beautica.test");
        UUID masterId = UUID.randomUUID();
        jdbc.update("INSERT INTO masters (id, user_id, master_type, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, 'INDEPENDENT_MASTER', 0, true, NOW(), NOW())",
                masterId, userId);
        return new SeededMaster(masterId, userId, null);
    }

    /** A salon owner + a SALON_OWNER-type master in that salon; the owner is the rightful actor. */
    private SeededMaster seedSalonOwnerMaster() {
        UUID ownerId = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, 'x', 'SALON_OWNER', 'Own', 'Er', true, true)",
                ownerId, "owner-" + ownerId + "@beautica.test");
        UUID salonId = UUID.randomUUID();
        jdbc.update("INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);
        UUID masterId = UUID.randomUUID();
        jdbc.update("INSERT INTO masters (id, user_id, salon_id, master_type, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'SALON_OWNER', 0, true, NOW(), NOW())",
                masterId, ownerId, salonId);
        return new SeededMaster(masterId, ownerId, salonId);
    }

    private record SeededMaster(UUID masterId, UUID actorId, UUID salonId) {
    }

    private static WeeklyScheduleRequest weekly(LocalDate from, LocalDate to, WeeklyScheduleDayRequest... days) {
        return new WeeklyScheduleRequest(from, to, List.of(days));
    }

    private static WeeklyScheduleDayRequest day(int dow, WorkIntervalDto... intervals) {
        return new WeeklyScheduleDayRequest(dow, List.of(intervals));
    }

    private static WorkIntervalDto iv(int startHour, int endHour) {
        return new WorkIntervalDto(LocalTime.of(startHour, 0), LocalTime.of(endHour, 0));
    }

    /** An EXPLICIT_TIMES weekday carrying the given discrete start times (Phase 15.8). */
    private static WeeklyScheduleDayRequest explicitDay(int dow, LocalTime... times) {
        return new WeeklyScheduleDayRequest(dow, WeekdayMode.EXPLICIT_TIMES, null, List.of(times));
    }

    private static LocalTime t(int h, int m) {
        return LocalTime.of(h, m);
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 1. Ownership / IDOR — the closed 15.1 risk, regression-pinned on every write path
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Ownership / IDOR")
    class Ownership {

        @Test
        @DisplayName("upsertWeeklySchedule (create) — ForbiddenException when an unrelated master is the actor")
        void should_rejectCreate_when_foreignActor() {
            SeededMaster victim = seedIndependentMaster();
            SeededMaster attacker = seedIndependentMaster();

            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    attacker.actorId(), victim.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17)))))
                    .as("attacker editing the victim's schedule must be forbidden")
                    .isInstanceOf(ForbiddenException.class);

            assertThat(weeklyScheduleRepository.findByMasterIdOrderByValidFromAsc(victim.masterId()))
                    .as("no schedule may be persisted for the victim").isEmpty();
        }

        @Test
        @DisplayName("upsertOverride — ForbiddenException when an unrelated actor writes a day-off")
        void should_rejectOverride_when_foreignActor() {
            SeededMaster victim = seedIndependentMaster();
            SeededMaster attacker = seedIndependentMaster();

            assertThatThrownBy(() -> scheduleService.upsertOverride(
                    attacker.actorId(), victim.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF, null)))
                    .isInstanceOf(ForbiddenException.class);

            assertThat(scheduleExceptionRepository.findByMasterIdAndDate(victim.masterId(), FUTURE_FROM))
                    .as("no override may be persisted for the victim").isEmpty();
        }

        @Test
        @DisplayName("clearOverride — ForbiddenException when an unrelated actor clears the victim's override")
        void should_rejectClear_when_foreignActor() {
            SeededMaster victim = seedIndependentMaster();
            SeededMaster attacker = seedIndependentMaster();
            // Victim legitimately owns a day-off override.
            scheduleService.upsertOverride(victim.actorId(), victim.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF, null));

            assertThatThrownBy(() -> scheduleService.clearOverride(
                    attacker.actorId(), victim.masterId(), FUTURE_FROM))
                    .isInstanceOf(ForbiddenException.class);

            assertThat(scheduleExceptionRepository.findByMasterIdAndDate(victim.masterId(), FUTURE_FROM))
                    .as("the victim's override must survive the rejected clear").isPresent();
        }

        @Test
        @DisplayName("edit with a foreign scheduleId → NotFoundException (no existence oracle) even when actor owns masterId")
        void should_return404_when_editingAnotherMastersScheduleId() {
            SeededMaster a = seedIndependentMaster();
            SeededMaster b = seedIndependentMaster();
            // 'a' creates a real schedule; capture its id from the DB.
            scheduleService.upsertWeeklySchedule(a.actorId(), a.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17))));
            UUID foreignScheduleId = weeklyScheduleRepository
                    .findByMasterIdOrderByValidFromAsc(a.masterId()).get(0).getId();

            // 'b' owns its own masterId but supplies 'a'-owned scheduleId on the edit path.
            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    b.actorId(), b.masterId(), foreignScheduleId,
                    weekly(FUTURE_FROM, null, day(2, iv(10, 16)))))
                    .as("cross-master edit must surface as 404, not 403, to avoid an existence oracle")
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        @DisplayName("happy path — INDEPENDENT_MASTER manages its own schedule; SALON_OWNER manages their salon's master")
        void should_succeed_when_rightfulOwner() {
            SeededMaster solo = seedIndependentMaster();
            WeeklyScheduleResponse soloResp = scheduleService.upsertWeeklySchedule(
                    solo.actorId(), solo.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17))));
            assertThat(soloResp.validFrom()).isEqualTo(FUTURE_FROM);

            SeededMaster salon = seedSalonOwnerMaster();
            WeeklyScheduleResponse salonResp = scheduleService.upsertWeeklySchedule(
                    salon.actorId(), salon.masterId(), null,
                    weekly(FUTURE_FROM, null, day(3, iv(8, 12))));
            assertThat(salonResp.days())
                    .as("the salon owner's write must persist the Wednesday window")
                    .singleElement()
                    .extracting(d -> d.dayOfWeek()).isEqualTo(3);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 2. Service invariants
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Invariants — temporal bounds")
    class TemporalBounds {

        @Test
        @DisplayName("weekly template with a past validFrom is rejected")
        void should_reject_when_weeklyValidFromInPast() {
            SeededMaster m = seedIndependentMaster();
            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(LocalDate.now().minusDays(1), null, day(1, iv(9, 17)))))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("override for a past date is rejected")
        void should_reject_when_overrideDateInPast() {
            SeededMaster m = seedIndependentMaster();
            assertThatThrownBy(() -> scheduleService.upsertOverride(
                    m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(LocalDate.now().minusDays(1),
                            ScheduleExceptionKind.DAY_OFF, null)))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("clearOverride for a past date is rejected")
        void should_reject_when_clearPastDate() {
            SeededMaster m = seedIndependentMaster();
            assertThatThrownBy(() -> scheduleService.clearOverride(
                    m.actorId(), m.masterId(), LocalDate.now().minusDays(1)))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("validTo beyond the today+2y cap is rejected; null validTo persists as open-ended")
        void should_rejectFarFuture_butAllowOpenEnded() {
            SeededMaster m = seedIndependentMaster();

            // +2 days (not +1): the service computes cap() from LocalDate.now(kyivClock) while this
            // test uses the default system clock (UTC on CI). Kyiv is always UTC+2/+3 ahead, so in the
            // UTC-evening window the two clocks land on different calendar days and a +1-day validTo
            // equals the cap exactly — the strict isAfter check then does not reject and the test flakes.
            // A 2-day margin guarantees validTo is strictly past the cap regardless of the 1-day skew.
            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, LocalDate.now().plusYears(2).plusDays(2), day(1, iv(9, 17)))))
                    .as("validTo past today+2y must be rejected")
                    .isInstanceOf(BusinessException.class);

            WeeklyScheduleResponse open = scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17))));
            assertThat(open.validTo()).as("null validTo persists as open-ended").isNull();
        }
    }

    @Nested
    @DisplayName("Invariants — window overlap")
    class WindowOverlap {

        @Test
        @DisplayName("two weekly windows that overlap for the same master are rejected")
        void should_reject_when_windowsOverlap() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, FUTURE_FROM.plusDays(30), day(1, iv(9, 17))));

            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM.plusDays(20), FUTURE_FROM.plusDays(50), day(2, iv(9, 17)))))
                    .as("the second window overlaps days 20..30 of the first")
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("adjacent (non-overlapping) windows are both allowed")
        void should_allow_when_windowsAdjacent() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, FUTURE_FROM.plusDays(30), day(1, iv(9, 17))));
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM.plusDays(31), FUTURE_FROM.plusDays(60), day(2, iv(9, 17))));

            assertThat(weeklyScheduleRepository.findByMasterIdOrderByValidFromAsc(m.masterId()))
                    .as("both adjacent windows coexist").hasSize(2);
        }

        @Test
        @DisplayName("an open-ended window blocks any later overlapping window (validTo=null = +infinity)")
        void should_reject_when_openEndedWindowOverlapsLater() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17))));

            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM.plusDays(100), FUTURE_FROM.plusDays(130), day(2, iv(9, 17)))))
                    .as("the open-ended window extends to +infinity and overlaps the later window")
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Nested
    @DisplayName("Invariants — intra-day intervals")
    class IntraDayIntervals {

        @Test
        @DisplayName("overlapping intervals within the same weekday are rejected")
        void should_reject_when_intraDayIntervalsOverlap() {
            SeededMaster m = seedIndependentMaster();
            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 13), iv(12, 18)))))
                    .as("09-13 and 12-18 overlap at 12-13")
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("non-overlapping split-shift intervals are accepted regardless of input order")
        void should_accept_when_splitShiftOutOfOrder() {
            SeededMaster m = seedIndependentMaster();
            // Afternoon listed before morning — the service sorts before checking.
            WeeklyScheduleResponse resp = scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(14, 18), iv(9, 13))));

            assertThat(resp.days())
                    .singleElement()
                    .extracting(d -> d.intervals())
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(WorkIntervalDto.class))
                    .as("intervals are emitted ordered by start time")
                    .extracting(WorkIntervalDto::startTime)
                    .containsExactly(LocalTime.of(9, 0), LocalTime.of(14, 0));
        }

        @Test
        @DisplayName("seconds on persisted interval times are zeroed")
        void should_zeroSeconds_when_persistingIntervals() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    new WeeklyScheduleRequest(FUTURE_FROM, null, List.of(
                            new WeeklyScheduleDayRequest(1, List.of(
                                    new WorkIntervalDto(LocalTime.of(9, 0, 45), LocalTime.of(17, 30, 59)))))));

            EffectiveDayResponse day = scheduleService.resolveEffectiveDay(
                    m.masterId(), nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY));
            assertThat(day.intervals())
                    .singleElement()
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .as("seconds and nanos are stripped on persist")
                    .containsExactly(LocalTime.of(9, 0), LocalTime.of(17, 30));
        }

        @Test
        @DisplayName("CUSTOM_HOURS override with overlapping intervals is rejected")
        void should_reject_when_overrideIntervalsOverlap() {
            SeededMaster m = seedIndependentMaster();
            assertThatThrownBy(() -> scheduleService.upsertOverride(
                    m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(iv(9, 13), iv(12, 15)))))
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 3. Effective-availability resolver
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Effective-availability resolver")
    class Resolver {

        @Test
        @DisplayName("range folds TEMPLATE, OVERRIDE_CUSTOM, OVERRIDE_DAY_OFF, NO_SCHEDULE — override beats template")
        void should_foldAllSources_when_resolvingRange() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);

            // Template covers [monday .. monday+6]; Mon+Tue working, the rest implicitly off.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, monday.plusDays(6),
                            day(1, iv(9, 17)),   // Monday
                            day(2, iv(10, 16)))); // Tuesday

            // Custom-hours override on Monday → wins over the template.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday, ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(iv(12, 14))));
            // Day-off override on Tuesday → wins over the template.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday.plusDays(1), ScheduleExceptionKind.DAY_OFF, null));

            // Resolve [monday-1 .. monday+7]: monday-1 and monday+7 are outside the template = NO_SCHEDULE.
            List<EffectiveDayResponse> days = scheduleService.resolveEffectiveRange(
                    m.masterId(), monday.minusDays(1), monday.plusDays(7));

            assertThat(days).hasSize(9);
            assertThat(byDate(days, monday.minusDays(1)).source())
                    .as("uncovered date before the window").isEqualTo(EffectiveDaySource.NO_SCHEDULE);
            // Monday: override beats template.
            EffectiveDayResponse mon = byDate(days, monday);
            assertThat(mon.source()).isEqualTo(EffectiveDaySource.OVERRIDE_CUSTOM);
            assertThat(mon.intervals())
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(LocalTime.of(12, 0), LocalTime.of(14, 0)));
            // Tuesday: day-off override beats template, empty intervals (V83 — no reason field).
            EffectiveDayResponse tue = byDate(days, monday.plusDays(1));
            assertThat(tue.source()).isEqualTo(EffectiveDaySource.OVERRIDE_DAY_OFF);
            assertThat(tue.intervals()).isEmpty();
            // Wednesday: covered by template, no working interval that weekday → TEMPLATE, empty intervals.
            EffectiveDayResponse wed = byDate(days, monday.plusDays(2));
            assertThat(wed.source()).isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(wed.intervals()).as("Wednesday has no template interval").isEmpty();
            // monday+7 is past validTo (monday+6) = NO_SCHEDULE.
            assertThat(byDate(days, monday.plusDays(7)).source())
                    .as("uncovered date after the window").isEqualTo(EffectiveDaySource.NO_SCHEDULE);
        }

        @Test
        @DisplayName("resolveEffectiveDay returns the covering template's intervals for that weekday")
        void should_resolveSingleDay_fromTemplate() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, null, day(1, iv(9, 17))));

            EffectiveDayResponse resp = scheduleService.resolveEffectiveDay(m.masterId(), monday);

            assertThat(resp.source()).isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(resp.intervals())
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(LocalTime.of(9, 0), LocalTime.of(17, 0)));
        }

        @Test
        @DisplayName("multi-week span resolves correctly with a bounded query count (no N+1 / no lazy-init)")
        void should_resolveMultiWeekSpan_withBoundedQueries() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);
            // Open-ended template + a scattering of overrides across the span.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, null, day(1, iv(9, 17)), day(3, iv(10, 14))));
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday.plusDays(10), ScheduleExceptionKind.DAY_OFF, null));
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday.plusDays(20), ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(iv(8, 9))));

            // A 28-day span across four weeks.
            Measured<List<EffectiveDayResponse>> measured = measure(() ->
                    scheduleService.resolveEffectiveRange(m.masterId(), monday, monday.plusDays(27)));
            List<EffectiveDayResponse> days = measured.result();

            assertThat(days).as("one entry per inclusive date").hasSize(28);
            assertThat(byDate(days, monday).source()).isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(byDate(days, monday.plusDays(10)).source()).isEqualTo(EffectiveDaySource.OVERRIDE_DAY_OFF);
            assertThat(byDate(days, monday.plusDays(20)).source()).isEqualTo(EffectiveDaySource.OVERRIDE_CUSTOM);
            assertThat(measured.queries())
                    .as("resolver must bulk-load (overrides + templates) — no per-date N+1 across a 28-day "
                            + "span, actual=%s", measured.queries())
                    .isLessThanOrEqualTo(MAX_RESOLVER_QUERIES);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 2c. Phase 15.8 — EXPLICIT_TIMES per-weekday discrete-time mode
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("EXPLICIT_TIMES discrete-time mode (15.8)")
    class ExplicitTimes {

        @Test
        @DisplayName("upsert + reload — an EXPLICIT_TIMES weekday persists times sorted, de-duplicated, mode derived")
        void should_persistSortedDedupedTimes_when_explicitTimesDay() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);

            // Unsorted, with a duplicate (11:00 twice) — the service must sort + dedupe on persist.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, null,
                            explicitDay(1, t(13, 0), t(9, 0), t(11, 0), t(11, 0))));

            // Reload from the DB (not the in-flight response) so this proves storage, not just mapping.
            WeeklyScheduleResponse reloaded =
                    scheduleService.listWeeklySchedules(m.masterId()).get(0);

            WeeklyScheduleDayResponse monDay = reloaded.days().stream()
                    .filter(d -> d.dayOfWeek() == 1).findFirst().orElseThrow();

            assertThat(monDay.mode())
                    .as("a weekday with discrete-time rows derives mode EXPLICIT_TIMES")
                    .isEqualTo(WeekdayMode.EXPLICIT_TIMES);
            assertThat(monDay.times())
                    .as("times are sorted ascending and de-duplicated (11:00 once)")
                    .containsExactly(t(9, 0), t(11, 0), t(13, 0));
            assertThat(monDay.intervals())
                    .as("an EXPLICIT_TIMES day carries no intervals").isEmpty();
        }

        @Test
        @DisplayName("resolveEffectiveDay — an EXPLICIT_TIMES day returns derived window [min..max] AND populated times")
        void should_returnDerivedWindowAndTimes_when_resolvingExplicitTimesDay() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, null, explicitDay(1, t(9, 0), t(12, 30), t(16, 0))));

            EffectiveDayResponse resp = scheduleService.resolveEffectiveDay(m.masterId(), monday);

            assertThat(resp.source()).isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(resp.times())
                    .as("the discrete slot chips are surfaced for the EXPLICIT_TIMES day")
                    .containsExactly(t(9, 0), t(12, 30), t(16, 0));
            assertThat(resp.intervals())
                    .as("the derived display window is the single [min..max] interval")
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(t(9, 0), t(16, 0)));
        }

        @Test
        @DisplayName("legacy INTERVAL-only schedule resolves to mode INTERVAL with null effective-day times")
        void should_resolveToIntervalMode_when_legacyIntervalOnly() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, null, day(1, iv(9, 17))));

            // Read projection: the day is INTERVAL with empty times (no discrete rows, no backfill).
            WeeklyScheduleResponse reloaded = scheduleService.listWeeklySchedules(m.masterId()).get(0);
            WeeklyScheduleDayResponse monDay = reloaded.days().stream()
                    .filter(d -> d.dayOfWeek() == 1).findFirst().orElseThrow();
            assertThat(monDay.mode()).isEqualTo(WeekdayMode.INTERVAL);
            assertThat(monDay.times()).as("an INTERVAL day carries no discrete times").isEmpty();
            assertThat(monDay.intervals())
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(t(9, 0), t(17, 0)));

            // Effective-day: an INTERVAL day leaves the additive `times` field null (15.8 contract).
            EffectiveDayResponse resp = scheduleService.resolveEffectiveDay(m.masterId(), monday);
            assertThat(resp.source()).isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(resp.times())
                    .as("an INTERVAL effective-day has null times (additive/nullable per 15.8)").isNull();
            assertThat(resp.intervals())
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(t(9, 0), t(17, 0)));
        }

        @Test
        @DisplayName("mode flip INTERVAL → EXPLICIT_TIMES clears the interval rows (full-replace upsert)")
        void should_clearIntervals_when_flippedToExplicitTimes() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);
            UUID scheduleId = scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, null, day(1, iv(9, 17)))).id();

            // Re-upsert the SAME schedule, flipping Monday to EXPLICIT_TIMES.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), scheduleId,
                    weekly(monday, null, explicitDay(1, t(10, 0), t(14, 0))));

            WeeklyScheduleResponse reloaded = scheduleService.listWeeklySchedules(m.masterId()).get(0);
            WeeklyScheduleDayResponse monDay = reloaded.days().stream()
                    .filter(d -> d.dayOfWeek() == 1).findFirst().orElseThrow();

            assertThat(monDay.mode()).isEqualTo(WeekdayMode.EXPLICIT_TIMES);
            assertThat(monDay.times()).containsExactly(t(10, 0), t(14, 0));
            assertThat(monDay.intervals())
                    .as("the prior INTERVAL rows must be cleared by the full-replace upsert").isEmpty();
            // Hard storage proof: no working_intervals rows survive for this schedule.
            Long intervalRows = jdbc.queryForObject(
                    "SELECT count(*) FROM working_intervals WHERE schedule_id = ?", Long.class, scheduleId);
            assertThat(intervalRows).as("interval rows physically deleted on the flip").isZero();
        }

        @Test
        @DisplayName("mode flip EXPLICIT_TIMES → INTERVAL clears the discrete-time rows (full-replace upsert)")
        void should_clearDiscreteTimes_when_flippedBackToInterval() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);
            UUID scheduleId = scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, null, explicitDay(1, t(10, 0), t(14, 0)))).id();

            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), scheduleId,
                    weekly(monday, null, day(1, iv(9, 17))));

            WeeklyScheduleResponse reloaded = scheduleService.listWeeklySchedules(m.masterId()).get(0);
            WeeklyScheduleDayResponse monDay = reloaded.days().stream()
                    .filter(d -> d.dayOfWeek() == 1).findFirst().orElseThrow();

            assertThat(monDay.mode()).isEqualTo(WeekdayMode.INTERVAL);
            assertThat(monDay.times()).as("discrete-time rows cleared on the flip back").isEmpty();
            assertThat(monDay.intervals())
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(t(9, 0), t(17, 0)));
            Long discreteRows = jdbc.queryForObject(
                    "SELECT count(*) FROM working_interval_times WHERE schedule_id = ?",
                    Long.class, scheduleId);
            assertThat(discreteRows).as("discrete-time rows physically deleted on the flip back").isZero();
        }

        @Test
        @DisplayName("an EXPLICIT_TIMES day with an empty times list is rejected")
        void should_reject_when_explicitTimesDayHasNoTimes() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);

            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(monday, null,
                            new WeeklyScheduleDayRequest(1, WeekdayMode.EXPLICIT_TIMES, null, List.of()))))
                    .as("an EXPLICIT_TIMES day must carry at least one discrete time")
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("IDOR — an EXPLICIT_TIMES upsert to a foreign master's schedule is forbidden (no persistence)")
        void should_rejectExplicitTimesUpsert_when_foreignActor() {
            SeededMaster victim = seedIndependentMaster();
            SeededMaster attacker = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);

            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    attacker.actorId(), victim.masterId(), null,
                    weekly(monday, null, explicitDay(1, t(9, 0), t(12, 0)))))
                    .as("an EXPLICIT_TIMES write follows the same ownership gate as INTERVAL")
                    .isInstanceOf(ForbiddenException.class);

            assertThat(weeklyScheduleRepository.findByMasterIdOrderByValidFromAsc(victim.masterId()))
                    .as("no schedule may be persisted for the victim").isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 2d. Phase 15.9 — EXPLICIT_TIMES per-DATE override (discrete times on schedule_exceptions)
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Phase 15.9 — extends the EXPLICIT_TIMES mode (15.8, weekly template) to a per-date CUSTOM_HOURS
     * override (a {@link com.beautica.master.entity.ScheduleException} carrying {@link OverrideDiscreteTime}
     * rows in {@code schedule_exception_times} / V85). Drives the full upsert → reload → resolve path
     * against the real Testcontainers Postgres so storage, derivation and override-precedence are proven
     * end-to-end, not just at the mapping layer.
     *
     * <p>The contract pinned here:
     * <ul>
     *   <li>An EXPLICIT_TIMES override persists its times sorted + de-duplicated, derives
     *       {@code mode=EXPLICIT_TIMES}, and carries NO intervals.</li>
     *   <li>{@code resolveEffectiveDay} for such a date returns {@code source=OVERRIDE_CUSTOM}, a populated
     *       {@code times} list AND a derived display window {@code [min..max]} as the single interval.</li>
     *   <li><b>Override wins over the weekday template</b> — an EXPLICIT_TIMES override on a concrete date
     *       beats a covering weekly INTERVAL day for that same weekday.</li>
     *   <li>Mode flip on the SAME override clears the opposite child collection (INTERVAL↔EXPLICIT_TIMES),
     *       and the physical {@code schedule_exception_*} rows are gone.</li>
     *   <li>A DAY_OFF override carries neither intervals nor discrete times.</li>
     * </ul>
     */
    @Nested
    @DisplayName("EXPLICIT_TIMES per-date override (15.9)")
    class OverrideExplicitTimes {

        /** An EXPLICIT_TIMES CUSTOM_HOURS override request carrying the given discrete start times. */
        private ScheduleOverrideRequest explicitOverride(LocalDate date, LocalTime... times) {
            return new ScheduleOverrideRequest(date, ScheduleExceptionKind.CUSTOM_HOURS,
                    WeekdayMode.EXPLICIT_TIMES, null, List.of(times));
        }

        @Test
        @DisplayName("upsert + reload — an EXPLICIT_TIMES override persists times sorted, de-duplicated, mode derived, no intervals")
        void should_persistSortedDedupedTimes_when_explicitTimesOverride() {
            SeededMaster m = seedIndependentMaster();

            // Unsorted, with a duplicate (11:00 twice) — the service must sort + dedupe on persist.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    explicitOverride(FUTURE_FROM, t(13, 0), t(9, 0), t(11, 0), t(11, 0)));

            // Reload via the list projection (re-reads from the DB), not the in-flight write response.
            ScheduleOverrideResponse reloaded = scheduleService
                    .listOverrides(m.masterId(), FUTURE_FROM, FUTURE_FROM).get(0);

            assertThat(reloaded.kind()).isEqualTo(ScheduleExceptionKind.CUSTOM_HOURS);
            assertThat(reloaded.mode())
                    .as("an override with discrete-time rows derives mode EXPLICIT_TIMES")
                    .isEqualTo(WeekdayMode.EXPLICIT_TIMES);
            assertThat(reloaded.times())
                    .as("override times are sorted ascending and de-duplicated (11:00 once)")
                    .containsExactly(t(9, 0), t(11, 0), t(13, 0));
            assertThat(reloaded.intervals())
                    .as("an EXPLICIT_TIMES override carries no intervals").isEmpty();

            // Hard storage proof: exactly the three deduped rows landed in schedule_exception_times.
            UUID exceptionId = scheduleExceptionRepository
                    .findByMasterIdAndDate(m.masterId(), FUTURE_FROM).orElseThrow().getId();
            Long timeRows = jdbc.queryForObject(
                    "SELECT count(*) FROM schedule_exception_times WHERE exception_id = ?",
                    Long.class, exceptionId);
            assertThat(timeRows).as("three discrete-time rows persisted (duplicate collapsed)").isEqualTo(3L);
            Long intervalRows = jdbc.queryForObject(
                    "SELECT count(*) FROM schedule_exception_intervals WHERE exception_id = ?",
                    Long.class, exceptionId);
            assertThat(intervalRows).as("no interval rows for an EXPLICIT_TIMES override").isZero();
        }

        @Test
        @DisplayName("resolveEffectiveDay — an EXPLICIT_TIMES override returns OVERRIDE_CUSTOM with derived window [min..max] AND populated times")
        void should_returnDerivedWindowAndTimes_when_resolvingExplicitTimesOverride() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    explicitOverride(FUTURE_FROM, t(9, 0), t(12, 30), t(16, 0)));

            EffectiveDayResponse resp = scheduleService.resolveEffectiveDay(m.masterId(), FUTURE_FROM);

            assertThat(resp.source())
                    .as("an EXPLICIT_TIMES override resolves as OVERRIDE_CUSTOM").isEqualTo(EffectiveDaySource.OVERRIDE_CUSTOM);
            assertThat(resp.times())
                    .as("the discrete slot chips are surfaced for the EXPLICIT_TIMES override")
                    .containsExactly(t(9, 0), t(12, 30), t(16, 0));
            assertThat(resp.intervals())
                    .as("the derived display window is the single [min..max] interval")
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(t(9, 0), t(16, 0)));
        }

        @Test
        @DisplayName("override WINS over the weekday template — an EXPLICIT_TIMES override on a date beats a covering weekly INTERVAL day")
        void should_overrideBeatTemplate_when_explicitTimesOverrideOnTemplateWeekday() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);

            // Weekly template: Monday is an INTERVAL working day 09:00-17:00.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, null, day(1, iv(9, 17))));
            // EXPLICIT_TIMES override on that concrete Monday → must win over the template.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    explicitOverride(monday, t(10, 0), t(13, 0), t(15, 30)));

            EffectiveDayResponse resp = scheduleService.resolveEffectiveDay(m.masterId(), monday);

            assertThat(resp.source())
                    .as("the override is consulted before the template for its date")
                    .isEqualTo(EffectiveDaySource.OVERRIDE_CUSTOM);
            assertThat(resp.times())
                    .as("the effective times are the override's discrete times, NOT the template window")
                    .containsExactly(t(10, 0), t(13, 0), t(15, 30));
            assertThat(resp.intervals())
                    .as("the derived window is [10:00..15:30], not the template's 09:00-17:00")
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(t(10, 0), t(15, 30)));
        }

        @Test
        @DisplayName("mode flip INTERVAL → EXPLICIT_TIMES on the same date clears the override's interval rows")
        void should_clearIntervals_when_overrideFlippedToExplicitTimes() {
            SeededMaster m = seedIndependentMaster();
            // First: an INTERVAL custom-hours override.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(iv(9, 13), iv(14, 18))));
            UUID exceptionId = scheduleExceptionRepository
                    .findByMasterIdAndDate(m.masterId(), FUTURE_FROM).orElseThrow().getId();

            // Re-upsert the SAME date, flipping to EXPLICIT_TIMES.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    explicitOverride(FUTURE_FROM, t(10, 0), t(14, 0)));

            ScheduleOverrideResponse reloaded = scheduleService
                    .listOverrides(m.masterId(), FUTURE_FROM, FUTURE_FROM).get(0);
            assertThat(reloaded.mode()).isEqualTo(WeekdayMode.EXPLICIT_TIMES);
            assertThat(reloaded.times()).containsExactly(t(10, 0), t(14, 0));
            assertThat(reloaded.intervals())
                    .as("the prior INTERVAL rows must be cleared by the full-replace upsert").isEmpty();
            Long intervalRows = jdbc.queryForObject(
                    "SELECT count(*) FROM schedule_exception_intervals WHERE exception_id = ?",
                    Long.class, exceptionId);
            assertThat(intervalRows).as("interval rows physically deleted on the flip").isZero();
        }

        @Test
        @DisplayName("mode flip EXPLICIT_TIMES → INTERVAL on the same date clears the override's discrete-time rows")
        void should_clearDiscreteTimes_when_overrideFlippedBackToInterval() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    explicitOverride(FUTURE_FROM, t(10, 0), t(14, 0)));
            UUID exceptionId = scheduleExceptionRepository
                    .findByMasterIdAndDate(m.masterId(), FUTURE_FROM).orElseThrow().getId();

            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(iv(9, 17))));

            ScheduleOverrideResponse reloaded = scheduleService
                    .listOverrides(m.masterId(), FUTURE_FROM, FUTURE_FROM).get(0);
            assertThat(reloaded.mode()).isEqualTo(WeekdayMode.INTERVAL);
            assertThat(reloaded.times()).as("discrete-time rows cleared on the flip back").isEmpty();
            assertThat(reloaded.intervals())
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(t(9, 0), t(17, 0)));
            Long timeRows = jdbc.queryForObject(
                    "SELECT count(*) FROM schedule_exception_times WHERE exception_id = ?",
                    Long.class, exceptionId);
            assertThat(timeRows).as("discrete-time rows physically deleted on the flip back").isZero();
        }

        @Test
        @DisplayName("a DAY_OFF override carries neither intervals nor discrete times (15.9 mode is INTERVAL, both empty)")
        void should_carryNeitherIntervalsNorTimes_when_dayOff() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF, null));

            ScheduleOverrideResponse reloaded = scheduleService
                    .listOverrides(m.masterId(), FUTURE_FROM, FUTURE_FROM).get(0);
            assertThat(reloaded.kind()).isEqualTo(ScheduleExceptionKind.DAY_OFF);
            assertThat(reloaded.mode())
                    .as("a DAY_OFF reports the default INTERVAL mode with both lists empty")
                    .isEqualTo(WeekdayMode.INTERVAL);
            assertThat(reloaded.intervals()).as("a DAY_OFF carries no intervals").isEmpty();
            assertThat(reloaded.times()).as("a DAY_OFF carries no discrete times").isEmpty();

            UUID exceptionId = scheduleExceptionRepository
                    .findByMasterIdAndDate(m.masterId(), FUTURE_FROM).orElseThrow().getId();
            Long timeRows = jdbc.queryForObject(
                    "SELECT count(*) FROM schedule_exception_times WHERE exception_id = ?",
                    Long.class, exceptionId);
            assertThat(timeRows).as("a DAY_OFF persists zero discrete-time rows").isZero();
        }

        @Test
        @DisplayName("IDOR — an EXPLICIT_TIMES override upsert to a foreign master is forbidden (no persistence)")
        void should_rejectExplicitTimesOverride_when_foreignActor() {
            SeededMaster victim = seedIndependentMaster();
            SeededMaster attacker = seedIndependentMaster();

            assertThatThrownBy(() -> scheduleService.upsertOverride(
                    attacker.actorId(), victim.masterId(),
                    explicitOverride(FUTURE_FROM, t(9, 0), t(12, 0))))
                    .as("an EXPLICIT_TIMES override follows the same ownership gate as INTERVAL/DAY_OFF")
                    .isInstanceOf(ForbiddenException.class);

            assertThat(scheduleExceptionRepository.findByMasterIdAndDate(victim.masterId(), FUTURE_FROM))
                    .as("no override may be persisted for the victim").isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 3b. Read identity — WeeklyScheduleResponse.id round-trips (regression: missing id)
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Read identity — WeeklyScheduleResponse.id round-trip")
    class ReadIdentity {

        /**
         * Regression for the missing-{@code id} gap: {@code WeeklyScheduleResponse} previously had no
         * {@code id}, so a client reloading the list could not target {@code PUT
         * /weekly-schedules/{scheduleId}} and re-POSTed a duplicate window — tripping the overlap guard.
         * The listed response's {@code id} must be non-null AND equal the persisted entity's id, so an
         * editor can round-trip it straight onto the update path.
         */
        @Test
        @DisplayName("listWeeklySchedules exposes a non-null id equal to the persisted entity's id")
        void should_exposePersistedId_when_listingWeeklySchedules() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17))));
            UUID persistedId = weeklyScheduleRepository
                    .findByMasterIdOrderByValidFromAsc(m.masterId()).get(0).getId();

            List<WeeklyScheduleResponse> listed = scheduleService.listWeeklySchedules(m.masterId());

            assertThat(listed).singleElement()
                    .extracting(WeeklyScheduleResponse::id)
                    .as("the listed window must carry the persisted id so editors can target PUT, "
                            + "not re-POST a duplicate that trips the overlap guard")
                    .isNotNull()
                    .isEqualTo(persistedId);
        }

        /**
         * End-to-end reproduction of the original failure mode at the service layer: create a window,
         * reload the list, then upsert using the id from the response. It must UPDATE in place (same id,
         * intervals replaced) with no {@code BusinessException} overlap — proving the round-trip id closes
         * the duplicate-window bug.
         */
        @Test
        @DisplayName("re-upsert using the listed id updates in place — no overlap, same id, intervals replaced")
        void should_updateInPlace_when_reUpsertingWithListedId() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17))));

            UUID listedId = scheduleService.listWeeklySchedules(m.masterId()).get(0).id();

            // Re-upsert the SAME window (overlapping itself) targeting the listed id — the original bug
            // was a re-POST (scheduleId == null) here, which tripped assertNoWindowOverlap.
            WeeklyScheduleResponse updated = scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), listedId,
                    weekly(FUTURE_FROM, null, day(2, iv(10, 16))));

            assertThat(updated.id())
                    .as("the update must reuse the same window id, not create a second one")
                    .isEqualTo(listedId);
            assertThat(weeklyScheduleRepository.findByMasterIdOrderByValidFromAsc(m.masterId()))
                    .as("the master must still have exactly one window — updated in place, not duplicated")
                    .singleElement()
                    .extracting(ws -> ws.getId()).isEqualTo(listedId);
            assertThat(updated.days())
                    .as("the targeted update replaced the intervals (Monday → Tuesday)")
                    .singleElement()
                    .extracting(d -> d.dayOfWeek()).isEqualTo(2);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 3c. Re-upsert duplicate-key collision — orphan DELETE must run before re-INSERT
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Regression for the working-intervals duplicate-key bug. Editing a day's working hours by adding a
     * pause that splits the day re-sent at least one interval whose
     * {@code (schedule_id, day_of_week, start_time, end_time)} (override: {@code (exception_id, start, end)})
     * was byte-identical to a row already persisted from the previous save. With
     * {@code hibernate.order_inserts=true} the {@code ActionQueue} runs every INSERT before every orphan
     * DELETE, so the re-sent identical interval collided with {@code uq_working_intervals_no_dup} /
     * {@code uq_exception_intervals_no_dup} and the write blew up with a
     * {@link org.springframework.dao.DataIntegrityViolationException} (SQLState 23505).
     *
     * <p>The fix is the {@code repository.flush()} in {@code replaceIntervals} /
     * {@code replaceOverrideIntervals} right after {@code getIntervals().clear()}, forcing the orphan
     * DELETEs to the DB <b>before</b> the re-INSERTs. These tests must FAIL with 23505 without that flush
     * and PASS with it. The collision only manifests against the live unique index — hence a real-DB IT.
     */
    @Nested
    @DisplayName("Re-upsert duplicate-key collision — delete-before-insert (23505 regression)")
    class ReUpsertDuplicateKey {

        @Test
        @DisplayName("weekly: re-upsert a day adding a pause (morning block unchanged) succeeds — no 23505")
        void should_notCollide_when_reUpsertingWeeklyDayWithIdenticalMorningBlock() {
            SeededMaster m = seedIndependentMaster();
            // First save: a single morning block on day 2 (Tuesday).
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null, day(2, iv(9, 13))));
            UUID listedId = scheduleService.listWeeklySchedules(m.masterId()).get(0).id();

            // Edit = "add a pause": keep the byte-identical 09:00-13:00 morning, add an afternoon block.
            // The re-sent 09:00-13:00 row equals the already-persisted one → would collide on the unique
            // index unless the orphan DELETE is flushed before the re-INSERT.
            WeeklyScheduleResponse updated = scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), listedId,
                    weekly(FUTURE_FROM, null, day(2, iv(9, 13), iv(14, 18))));

            assertThat(updated.id())
                    .as("the edit must update the same window in place").isEqualTo(listedId);

            // The persisted day 2 must hold exactly the two expected intervals — proving the re-INSERT
            // landed (the bug aborted the whole write before any interval could be written/replaced).
            EffectiveDayResponse tue = scheduleService.resolveEffectiveDay(
                    m.masterId(), nextDateForDow(FUTURE_FROM, DayOfWeek.TUESDAY));
            assertThat(tue.source()).isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(tue.intervals())
                    .as("day 2 must persist exactly the unchanged morning + the new afternoon block")
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(
                            tuple(LocalTime.of(9, 0), LocalTime.of(13, 0)),
                            tuple(LocalTime.of(14, 0), LocalTime.of(18, 0)));
        }

        @Test
        @DisplayName("override: re-upsert a CUSTOM_HOURS date adding a pause (morning block unchanged) succeeds — no 23505")
        void should_notCollide_when_reUpsertingOverrideWithIdenticalMorningBlock() {
            SeededMaster m = seedIndependentMaster();
            // First save: a CUSTOM_HOURS override on FUTURE_FROM with a single morning block.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(iv(9, 13))));

            // Edit = "add a pause": keep the byte-identical 09:00-13:00 morning, add an afternoon block.
            // The re-sent 09:00-13:00 row equals the already-persisted exception interval → would collide
            // unless replaceOverrideIntervals flushes the orphan DELETE before the re-INSERT.
            ScheduleOverrideResponse updated = scheduleService.upsertOverride(
                    m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(iv(9, 13), iv(14, 18))));

            assertThat(updated.kind()).isEqualTo(ScheduleExceptionKind.CUSTOM_HOURS);

            EffectiveDayResponse day = scheduleService.resolveEffectiveDay(m.masterId(), FUTURE_FROM);
            assertThat(day.source()).isEqualTo(EffectiveDaySource.OVERRIDE_CUSTOM);
            assertThat(day.intervals())
                    .as("the override must persist exactly the unchanged morning + the new afternoon block")
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(
                            tuple(LocalTime.of(9, 0), LocalTime.of(13, 0)),
                            tuple(LocalTime.of(14, 0), LocalTime.of(18, 0)));
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 3d. V83 — DAY_OFF persistence against the live schema (reason + note columns DROPPED)
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * Highest-value guard for the V83 contract change. V83 physically DROPPED the {@code reason} and
     * {@code note} columns (and the {@code chk_exc_reason} / {@code chk_exception_reason} CHECKs) from
     * {@code schedule_exceptions}, keeping only {@code kind} + {@code chk_exc_kind}.
     *
     * <p>Only a real INSERT against the live Testcontainers Postgres proves the migration actually
     * removed the columns: the entity no longer maps reason/note, so any Hibernate INSERT still
     * referencing those columns (a missed V83) would fail with a "column does not exist" error. A mock
     * would never catch a bad V83 — the schedule ITs run on a dedicated Flyway pool with the real schema.
     */
    @Nested
    @DisplayName("V83 — DAY_OFF persists against the live schema (no reason/note columns)")
    class DayOffPersistence {

        @Test
        @DisplayName("persists a DAY_OFF override and resolves it to OVERRIDE_DAY_OFF with empty intervals")
        void should_persistAndResolve_when_dayOff() {
            SeededMaster m = seedIndependentMaster();

            // A bare DAY_OFF override (no reason/note exist in the V83 wire shape or schema).
            ScheduleOverrideResponse mgmt = scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF, null));

            assertThat(mgmt.kind()).isEqualTo(ScheduleExceptionKind.DAY_OFF);
            assertThat(mgmt.intervals()).as("a day-off override carries no intervals").isEmpty();

            // The row really landed against the live V83 schema — verify it persisted with the right kind.
            assertThat(scheduleExceptionRepository.findByMasterIdAndDate(m.masterId(), FUTURE_FROM))
                    .as("the DAY_OFF override is persisted against the V83 schema (reason/note dropped)")
                    .isPresent()
                    .get()
                    .extracting(e -> e.getKind())
                    .isEqualTo(ScheduleExceptionKind.DAY_OFF);

            // And it resolves through the public effective-day projection as a closed day.
            EffectiveDayResponse pub = scheduleService.resolveEffectiveDay(m.masterId(), FUTURE_FROM);
            assertThat(pub.source()).isEqualTo(EffectiveDaySource.OVERRIDE_DAY_OFF);
            assertThat(pub.intervals()).as("a day-off carries no working intervals").isEmpty();
        }

        @Test
        @DisplayName("DAY_OFF response/effective-day records structurally carry no reason or note component (V83)")
        void should_notExposeReasonOrNote_inResponseShapes() {
            // Guards the response-shape contract change directly: the V83 records were re-shaped to
            // (date, kind, intervals) / (date, source, intervals). A reintroduced reason/note component
            // would fail this assertion before any serialization could leak it.
            assertThat(EffectiveDayResponse.class.getRecordComponents())
                    .as("EffectiveDayResponse must not carry a reason or note component (V83 removed both)")
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .doesNotContain("reason", "note");

            assertThat(ScheduleOverrideResponse.class.getRecordComponents())
                    .as("ScheduleOverrideResponse must not carry a reason or note component (V83 removed both)")
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .doesNotContain("reason", "note");
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 4. Data exposure — the public effective-day projection exposes only date/source/intervals
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Data exposure")
    class DataExposure {

        @Test
        @DisplayName("EffectiveDayResponse exposes exactly date/source/intervals/times/window — no private free-text leaks (V83 + 15.8 + 15.12)")
        void should_exposeOnlyDateSourceIntervalsTimesWindow_inEffectiveDay() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertOverride(
                    m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.DAY_OFF, null));

            EffectiveDayResponse pub = scheduleService.resolveEffectiveDay(m.masterId(), FUTURE_FROM);
            assertThat(pub.source()).isEqualTo(EffectiveDaySource.OVERRIDE_DAY_OFF);

            // Structural guard: no reason/note — no free-text a master wrote may ever reach this projection.
            // Phase 15.8 widened the contract with `times` (discrete EXPLICIT_TIMES slots); `mode` was
            // intentionally NOT added to EffectiveDayResponse (only the weekly-template DTOs carry mode).
            // Phase 15.12 widened it again with windowStart/windowEnd — a deliberate, reviewed addition:
            // two nullable wall-clock TIMEs (display-only working-window bounds the master's own editor
            // needs to rebuild an edge-flush break). They carry no free text, no identifier and no PII, so
            // the leak class this guard exists for is unaffected. A DAY_OFF like the one asserted above
            // projects them as null.
            assertThat(EffectiveDayResponse.class.getRecordComponents())
                    .as("EffectiveDayResponse exposes exactly date/source/intervals/times/window bounds "
                            + "(V83 removed reason/note; 15.8 added times; 15.12 added the window; NOT mode)")
                    .extracting(java.lang.reflect.RecordComponent::getName)
                    .containsExactlyInAnyOrder(
                            "date", "source", "intervals", "times", "windowStart", "windowEnd");
            assertThat(pub.windowStart())
                    .as("a DAY_OFF has no working window to describe").isNull();
            assertThat(pub.windowEnd()).isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 5. Phase 15.6 performance guards — bounded query count (P1) + scoped eviction (P3)
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Performance guards (15.6)")
    class PerformanceGuards {

        /**
         * P1 — extends the 28-day bounded-query assertion to a full ~90-day quarter span. The resolver
         * must still issue a constant number of statements (bulk-load overrides + templates, then fold
         * in memory) — no per-date N+1 as the span grows 3×.
         */
        @Test
        @DisplayName("P1 — a 90-day resolveEffectiveRange issues a constant query count (no N+1 as the span grows)")
        void should_resolve90DaySpan_withConstantQueryCount() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, null, day(1, iv(9, 17)), day(3, iv(10, 14))));
            // Scatter overrides across the quarter so the fold exercises both override + template branches.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday.plusDays(15), ScheduleExceptionKind.DAY_OFF, null));
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday.plusDays(60), ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(iv(8, 9))));

            // 90 inclusive days (monday .. monday+89).
            Measured<List<EffectiveDayResponse>> measured = measure(() ->
                    scheduleService.resolveEffectiveRange(m.masterId(), monday, monday.plusDays(89)));
            List<EffectiveDayResponse> days = measured.result();
            long queries = measured.queries();

            assertThat(days).as("one entry per inclusive date across the quarter").hasSize(90);
            assertThat(byDate(days, monday.plusDays(15)).source())
                    .isEqualTo(EffectiveDaySource.OVERRIDE_DAY_OFF);
            assertThat(byDate(days, monday.plusDays(60)).source())
                    .isEqualTo(EffectiveDaySource.OVERRIDE_CUSTOM);
            // Identical bound to the 28-day case (the SAME constant, so they cannot drift): the statement
            // count is span-independent — bulk loads + batched collection hydration, proving the fold is
            // in-memory. A 3× longer span costing the same number of statements is the real assertion.
            assertThat(queries)
                    .as("90-day fold must issue the same bounded query count as the 28-day fold — actual=%s", queries)
                    .isLessThanOrEqualTo(MAX_RESOLVER_QUERIES);
        }

        /**
         * P1b (Phase 15.12 resolver split) — the boolean-only CLIENT path folds through the shared
         * {@code foldRange} core WITHOUT the decoration step, so its cost stays span-independent on the
         * tight {@link #MAX_RESOLVER_QUERIES} availability bound even across a 90-day quarter.
         *
         * <p><b>What this does NOT prove</b> (corrected 2026-07-27 — the previous javadoc claimed the
         * opposite): this fixture seeds no override, so it spends 3 of the 4 permitted statements. A
         * leak-back — repointing {@code getClientWorkingDays} at the display variant, or refolding
         * {@code withWindow} into {@code resolveFromTemplate} — costs exactly one batched
         * {@code dayWindows} statement, landing on 4, which is still {@code <=} the bound. This guard
         * would stay GREEN. {@code P1d} is the one that catches it; do not read this test as protecting
         * against the window leaking onto the availability path.
         */
        @Test
        @DisplayName("P1b — getClientWorkingDays stays span-independent on the tight availability bound")
        void should_keepBoundedQueryCount_when_booleanPathFoldsQuarterSpan() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);
            // Every Monday and Wednesday in the span is a working day carrying a stored window, so a
            // hypothetical per-day dayWindows load would fire on ~26 of the 90 dates — the shape this
            // absolute ceiling genuinely does catch.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, null,
                            new WeeklyScheduleDayRequest(1, WeekdayMode.INTERVAL,
                                    List.of(iv(10, 17)), null, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                            new WeeklyScheduleDayRequest(3, WeekdayMode.INTERVAL,
                                    List.of(iv(10, 14)), null, LocalTime.of(9, 0), LocalTime.of(14, 0))));

            Measured<List<MasterWorkingDayResponse>> measured = measure(() ->
                    scheduleService.getClientWorkingDays(m.masterId(), monday, monday.plusDays(89)));

            assertThat(measured.result()).hasSize(90);
            assertThat(measured.result().stream().filter(MasterWorkingDayResponse::working).count())
                    .as("Mondays + Wednesdays across the quarter are working days").isGreaterThan(20L);
            assertThat(measured.queries())
                    .as("a 90-day boolean fold must stay bounded, not scale with the span — actual=%s",
                            measured.queries())
                    .isLessThanOrEqualTo(MAX_RESOLVER_QUERIES);
        }

        /**
         * P1c (Phase 15.12 resolver split) — the display path DOES project the window, and its cost stays
         * span-independent: one batched {@code dayWindows} load for the whole fold, never one per date.
         *
         * <p><b>Scope</b> (corrected 2026-07-27): this pins span-independence and that the projection
         * actually happens. It does NOT pin "exactly one extra statement" — this fixture seeds no override,
         * so it spends 4 of the 5 permitted, and the window could cost two statements without tripping the
         * bound. {@code P1d} asserts the "exactly one" half relationally.
         */
        @Test
        @DisplayName("P1c — resolveEffectiveRangeForDisplay projects the window and stays span-independent")
        void should_stayBounded_when_displayPathProjectsWindowAcrossQuarter() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, null,
                            new WeeklyScheduleDayRequest(1, WeekdayMode.INTERVAL,
                                    List.of(iv(10, 17)), null, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                            new WeeklyScheduleDayRequest(3, WeekdayMode.INTERVAL,
                                    List.of(iv(10, 14)), null, LocalTime.of(9, 0), LocalTime.of(14, 0))));

            Measured<List<EffectiveDayResponse>> measured = measure(() ->
                    scheduleService.resolveEffectiveRangeForDisplay(
                            m.masterId(), monday, monday.plusDays(89)));

            assertThat(measured.result()).hasSize(90);
            assertThat(byDate(measured.result(), monday).windowStart())
                    .as("the display path really does project the window").isEqualTo(LocalTime.of(9, 0));
            assertThat(measured.queries())
                    .as("one batched dayWindows load for the whole 90-day fold — actual=%s",
                            measured.queries())
                    .isLessThanOrEqualTo(MAX_DISPLAY_RESOLVER_QUERIES);
        }

        /**
         * P1d (2026-07-27 perf re-audit) — the DIFFERENTIAL guard, and the only one of the four that can
         * actually detect a window leak onto the availability path.
         *
         * <p>P1/P1b/P1c are three independent ceilings. Each carries up to one statement of slack depending
         * on whether an override happens to fall in the fixture's range, and a leak costs exactly one
         * statement — so a leak can hide inside the slack of any of them. This test removes the slack by
         * measuring BOTH folds over the SAME seeded fixture, in the same test, through the same
         * {@link #measure} mechanics, and asserting the RELATIONSHIP:
         *
         * <pre>display == availability + 1   (P1d)
         *  boolean == availability       (P1e)</pre>
         *
         * <p>That single equality pins both halves of the contract at once — the display path pays exactly
         * one extra statement, and the availability path pays none — and it does so without hardcoding an
         * absolute number that shifts with the fixture's override content or with a future
         * {@code LEFT JOIN FETCH} added to either finder. Both sides move together, so the differential
         * survives changes that would silently re-slacken the ceilings.
         *
         * <p>Verified to go RED against the exact regression it guards (repointing
         * {@code getClientWorkingDays}/{@code resolveEffectiveRange} at the display variant).
         */
        @Test
        @DisplayName("P1d — the display fold costs the availability fold + exactly one statement (same fixture)")
        void should_costExactlyOneExtraStatement_when_displayFoldComparedToAvailabilityFold() {
            SeededMaster m = seedQuarterFixtureWithWindows();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);

            Measured<List<EffectiveDayResponse>> availability = measure(() ->
                    scheduleService.resolveEffectiveRange(m.masterId(), monday, monday.plusDays(89)));
            Measured<List<EffectiveDayResponse>> display = measure(() ->
                    scheduleService.resolveEffectiveRangeForDisplay(
                            m.masterId(), monday, monday.plusDays(89)));

            assertThat(byDate(availability.result(), monday).windowStart())
                    .as("control: the availability fold must project NO window").isNull();
            assertThat(byDate(display.result(), monday).windowStart())
                    .as("control: the display fold must project the window").isEqualTo(LocalTime.of(9, 0));
            assertThat(display.queries())
                    .as("the window must cost EXACTLY one batched statement — availability=%s, display=%s. "
                            + "An EQUAL count means either the window was refolded into the shared core "
                            + "(availability now pays for it) or the display path stopped projecting it",
                            availability.queries(), display.queries())
                    .isEqualTo(availability.queries() + 1);
        }

        /**
         * P1e (2026-07-27 perf re-audit) — the second half of the differential, and the guard that covers
         * the regression form {@code P1d} structurally cannot see.
         *
         * <p>{@code P1d} compares {@code resolveEffectiveRange} against {@code resolveEffectiveRangeForDisplay}.
         * Repointing {@code getClientWorkingDays} at the display variant — the exact leak {@code P1b} was
         * written for — leaves BOTH of those folds untouched, so {@code P1d} would stay green, and
         * {@code P1b}'s ceiling has a statement of slack to absorb it. Neither catches it.
         *
         * <p>This test pins the CLIENT boolean path's wiring directly: over an identical fixture it must
         * cost exactly what the window-free fold costs — not one more. Verified to go RED against the
         * mutation.
         */
        @Test
        @DisplayName("P1e — getClientWorkingDays costs exactly what the window-free fold costs, not one more")
        void should_costSameAsAvailabilityFold_when_booleanPathResolvesQuarter() {
            SeededMaster m = seedQuarterFixtureWithWindows();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);

            Measured<List<MasterWorkingDayResponse>> booleanPath = measure(() ->
                    scheduleService.getClientWorkingDays(m.masterId(), monday, monday.plusDays(89)));
            Measured<List<EffectiveDayResponse>> availability = measure(() ->
                    scheduleService.resolveEffectiveRange(m.masterId(), monday, monday.plusDays(89)));

            assertThat(booleanPath.result()).hasSize(90);
            assertThat(booleanPath.result().stream().filter(MasterWorkingDayResponse::working).count())
                    .as("control: the fixture really does produce working days to fold")
                    .isGreaterThan(20L);
            assertThat(booleanPath.queries())
                    .as("the CLIENT boolean path must fold through the WINDOW-FREE variant — boolean=%s, "
                            + "availability=%s. One MORE than availability means getClientWorkingDays was "
                            + "repointed at resolveEffectiveRangeForDisplay and is now paying for a window "
                            + "it immediately discards",
                            booleanPath.queries(), availability.queries())
                    .isEqualTo(availability.queries());
        }

        /**
         * The shared fixture for the two differential guards: an open-ended template whose Monday and
         * Wednesday both carry a stored display-only window, plus a non-DAY_OFF override inside the span.
         *
         * <p>The override is deliberate — it makes the {@code ScheduleException.discreteTimes} batched init
         * fire on every fold, so the measured counts sit at their maximum rather than in the 1-statement
         * trough that let the absolute ceilings hide a leak. It cancels out of both differentials by
         * construction, which is the point: the relationships hold regardless of which optional statements
         * a fixture happens to trigger.
         */
        private SeededMaster seedQuarterFixtureWithWindows() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = nextDateForDow(FUTURE_FROM, DayOfWeek.MONDAY);
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(monday, null,
                            new WeeklyScheduleDayRequest(1, WeekdayMode.INTERVAL,
                                    List.of(iv(10, 17)), null, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                            new WeeklyScheduleDayRequest(3, WeekdayMode.INTERVAL,
                                    List.of(iv(10, 14)), null, LocalTime.of(9, 0), LocalTime.of(14, 0))));
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday.plusDays(30), ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(iv(8, 9))));
            return m;
        }

        /**
         * P3 — a schedule write evicts ONLY the writing master's available-slots keys (keyed by the
         * master prefix), never a blanket {@code cache.clear()}. A second, unrelated master's cached
         * slot entry must survive a write by the first master.
         */
        @Test
        @DisplayName("P3 — a write evicts only the affected master's slot-cache keys, leaving other masters' entries intact")
        void should_evictOnlyAffectedMasterSlots_onWrite() {
            SeededMaster writer = seedIndependentMaster();
            SeededMaster bystander = seedIndependentMaster();

            Cache slots = cacheManager.getCache("available-slots");
            assertThat(slots).as("available-slots cache must be configured").isNotNull();
            slots.clear();

            // Seed cache entries under the real runtime key shape: SlotCalculationService's @Cacheable
            // uses an explicit SpEL key ("{#masterId, #date, #masterServiceId}"), which Spring never
            // wraps in a SimpleKey — SimpleKey only comes from the default SimpleKeyGenerator when no
            // `key` attribute is given. The `{...}` inline-list literal evaluates to an ArrayList at
            // runtime, so that's the shape seeded here (see evictByMasterPrefix).
            UUID svc = UUID.randomUUID();
            LocalDate date = FUTURE_FROM;
            var writerKey = new ArrayList<>(List.of(writer.masterId(), date, svc));
            var bystanderKey = new ArrayList<>(List.of(bystander.masterId(), date, svc));
            slots.put(writerKey, List.of());
            slots.put(bystanderKey, List.of());

            // A real schedule write by the writer — eviction fires in the afterCommit synchronization.
            scheduleService.upsertWeeklySchedule(writer.actorId(), writer.masterId(), null,
                    weekly(FUTURE_FROM, null, day(1, iv(9, 17))));

            assertThat(slots.get(writerKey))
                    .as("the writing master's slot key must be evicted after the schedule write")
                    .isNull();
            assertThat(slots.get(bystanderKey))
                    .as("an unrelated master's slot key must survive — eviction is master-scoped, not a full clear")
                    .isNotNull();
        }

        /**
         * Regression guard for the {@code evictByMasterPrefix} key-shape bug: {@code master-working-days}
         * is {@code @Cacheable} with an explicit SpEL {@code key = "{#masterId, #from, #to}"}, so Spring
         * never wraps the entry in a {@link org.springframework.cache.interceptor.SimpleKey} (that type is
         * only produced by the default {@code SimpleKeyGenerator} when no {@code key} attribute is given).
         * The old {@code evictByMasterPrefix} checked {@code instanceof SimpleKey}, which never matched
         * these entries — eviction silently no-opped and {@code getClientWorkingDays} kept serving the
         * pre-write result for the full 60s TTL instead of being evicted after commit.
         *
         * <p>Unlike {@link #should_evictOnlyAffectedMasterSlots_onWrite}, this test drives the
         * <b>real {@code @Cacheable} proxy end-to-end</b> (no hand-seeded cache entry) so it exercises the
         * actual runtime key shape produced by the SpEL {@code {...}} inline-list literal (an
         * {@code ArrayList}), not an assumption about it.
         */
        @Test
        @DisplayName("Cache regression — master-working-days reflects a schedule write, not a stale pre-write result")
        void should_reflectFreshSchedule_when_readThroughRealCacheableProxy_afterWrite() {
            SeededMaster m = seedIndependentMaster();

            // First call through the real proxy — populates master-working-days under the true
            // ArrayList-shaped SpEL key, not a hand-seeded SimpleKey.
            List<MasterWorkingDayResponse> before =
                    scheduleService.getClientWorkingDays(m.masterId(), FUTURE_FROM, FUTURE_FROM);
            assertThat(before).hasSize(1);
            assertThat(before.get(0).working())
                    .as("no schedule yet — an uncovered date resolves to NO_SCHEDULE / not working")
                    .isFalse();

            // A real schedule write for the same master and date — must evict the cached entry above.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(iv(9, 17))));

            // Under the pre-fix `instanceof SimpleKey` check, eviction is a silent no-op for this cache,
            // so this second call would incorrectly replay the stale "false" from the first call.
            List<MasterWorkingDayResponse> after =
                    scheduleService.getClientWorkingDays(m.masterId(), FUTURE_FROM, FUTURE_FROM);
            assertThat(after.get(0).working())
                    .as("the cache must be evicted after commit — must reflect the new CUSTOM_HOURS "
                            + "override, not the stale pre-write NO_SCHEDULE result")
                    .isTrue();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // hasUsableSchedule — short-circuiting boolean gate (Phase 23.x perf follow-up)
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * {@link MasterScheduleService#hasUsableSchedule} must agree with
     * {@code getClientWorkingDays(...).anyMatch(...)} for every schedule shape, and must not
     * false-negative when the only working day sits at the far end of a wide range (proving the
     * early-return loop still walks every date rather than stopping too soon).
     */
    @Nested
    @DisplayName("hasUsableSchedule — short-circuit correctness (Phase 23.x)")
    class UsableScheduleShortCircuit {

        private boolean anyWorkingDay(UUID masterId, LocalDate from, LocalDate to) {
            return scheduleService.getClientWorkingDays(masterId, from, to).stream()
                    .anyMatch(MasterWorkingDayResponse::working);
        }

        @Test
        @DisplayName("returns true and agrees with getClientWorkingDays when the working day is the FIRST date in range")
        void should_returnTrue_when_workingDayIsFirstDateInRange() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null, day(FUTURE_FROM.getDayOfWeek().getValue(), iv(9, 17))));
            LocalDate to = FUTURE_FROM.plusDays(180);

            boolean result = scheduleService.hasUsableSchedule(m.masterId(), FUTURE_FROM, to);

            assertThat(result).isTrue();
            assertThat(result)
                    .as("must agree with the anyMatch(getClientWorkingDays) verdict")
                    .isEqualTo(anyWorkingDay(m.masterId(), FUTURE_FROM, to));
        }

        @Test
        @DisplayName("returns true and agrees with getClientWorkingDays when the only working day is the LAST date in range")
        void should_returnTrue_when_workingDayIsLastDateInRange() {
            SeededMaster m = seedIndependentMaster();
            LocalDate to = FUTURE_FROM.plusDays(180);
            // Only working weekday in the whole window is `to`'s ISO weekday, and the window is scoped
            // so no earlier date in [FUTURE_FROM, to] shares that weekday — proves the loop walks the
            // full range instead of stopping early on a false negative.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(to, null, day(to.getDayOfWeek().getValue(), iv(9, 17))));

            boolean result = scheduleService.hasUsableSchedule(m.masterId(), FUTURE_FROM, to);

            assertThat(result)
                    .as("a working day at the far end of the range must still be found, not skipped")
                    .isTrue();
            assertThat(result)
                    .as("must agree with the anyMatch(getClientWorkingDays) verdict")
                    .isEqualTo(anyWorkingDay(m.masterId(), FUTURE_FROM, to));
        }

        @Test
        @DisplayName("returns false and agrees with getClientWorkingDays when no date in range is a working day")
        void should_returnFalse_when_noWorkingDayInRange() {
            SeededMaster m = seedIndependentMaster();
            LocalDate to = FUTURE_FROM.plusDays(30);
            // No schedule at all → every date resolves to NO_SCHEDULE.

            boolean result = scheduleService.hasUsableSchedule(m.masterId(), FUTURE_FROM, to);

            assertThat(result).isFalse();
            assertThat(result)
                    .as("must agree with the anyMatch(getClientWorkingDays) verdict")
                    .isEqualTo(anyWorkingDay(m.masterId(), FUTURE_FROM, to));
        }

        @Test
        @DisplayName("returns true and agrees with getClientWorkingDays for an EXPLICIT_TIMES-only working day")
        void should_returnTrue_when_scheduleUsesExplicitTimesOnly() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(FUTURE_FROM, null,
                            explicitDay(FUTURE_FROM.getDayOfWeek().getValue(), t(10, 0), t(11, 30))));
            LocalDate to = FUTURE_FROM.plusDays(7);

            boolean result = scheduleService.hasUsableSchedule(m.masterId(), FUTURE_FROM, to);

            assertThat(result).isTrue();
            assertThat(result)
                    .as("must agree with the anyMatch(getClientWorkingDays) verdict")
                    .isEqualTo(anyWorkingDay(m.masterId(), FUTURE_FROM, to));
        }

        /**
         * Cache regression mirroring {@code PerformanceGuards#should_reflectFreshSchedule_when_
         * readThroughRealCacheableProxy_afterWrite}: {@code master-usable-schedule} must be evicted
         * after commit, not silently no-opped by a stale key-shape assumption.
         */
        @Test
        @DisplayName("Cache regression — master-usable-schedule reflects a schedule write, not a stale pre-write result")
        void should_reflectFreshSchedule_when_readThroughRealCacheableProxy_afterWrite() {
            SeededMaster m = seedIndependentMaster();
            LocalDate to = FUTURE_FROM.plusDays(7);

            boolean before = scheduleService.hasUsableSchedule(m.masterId(), FUTURE_FROM, to);
            assertThat(before).as("no schedule yet").isFalse();

            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(FUTURE_FROM, ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(iv(9, 17))));

            boolean after = scheduleService.hasUsableSchedule(m.masterId(), FUTURE_FROM, to);
            assertThat(after)
                    .as("the cache must be evicted after commit — must reflect the new CUSTOM_HOURS "
                            + "override, not the stale pre-write false result")
                    .isTrue();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────

    private static EffectiveDayResponse byDate(List<EffectiveDayResponse> days, LocalDate date) {
        return days.stream().filter(d -> d.date().equals(date)).findFirst().orElseThrow();
    }

    /** First date on/after {@code from} that falls on {@code dow}. */
    private static LocalDate nextDateForDow(LocalDate from, DayOfWeek dow) {
        return from.with(TemporalAdjusters.nextOrSame(dow));
    }
}
