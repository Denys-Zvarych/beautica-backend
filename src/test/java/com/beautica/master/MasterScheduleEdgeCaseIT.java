package com.beautica.master;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.dto.EffectiveDaySource;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.WeeklyScheduleDayRequest;
import com.beautica.master.dto.WeeklyScheduleRequest;
import com.beautica.master.dto.WeeklyScheduleResponse;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.repository.ScheduleExceptionRepository;
import com.beautica.master.repository.WeeklyScheduleRepository;
import com.beautica.master.service.MasterScheduleService;
import com.beautica.master.service.ScheduleDateMath;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Phase 15.7 — Master Schedule date/time edge-case hardening, end-to-end through the autowired
 * {@link MasterScheduleService} (resolver + invariants) over a real Testcontainers Postgres.
 *
 * <h2>Why a frozen clock</h2>
 * The whole leap-year / past-future / boundary surface only resolves deterministically against a
 * pinned "today". This class installs a {@link #FROZEN_CLOCK} via {@link FrozenKyivClockConfig}
 * (overriding {@code ClockConfig.systemClock()}, which is {@code @ConditionalOnMissingBean}) so:
 * <ul>
 *   <li>"today" is <b>2024-01-01</b> Kyiv civil time → the {@code today+2y} cap is <b>2026-01-01</b>;</li>
 *   <li>the leap day <b>2024-02-29</b> (a Thursday, ISO weekday 4) is in the future and therefore an
 *       acceptable {@code validFrom} / override date;</li>
 *   <li>the 2024 Kyiv DST dates (spring 2024-03-31, fall 2024-10-27) sit inside the bounded window.</li>
 * </ul>
 * The distinct bean set gives this class its own Spring context (it does not pollute the shared
 * {@link AbstractIntegrationTest} cache) — an intentional, one-off cost for the frozen-clock gate.
 *
 * <p><b>tzdata pin.</b> The DST and leap-day expectations below are derived from {@code Europe/Kyiv}
 * tz rules at authoring time (tzdata 2024a+): spring-forward gap {@code 2024-03-31 03:00→04:00},
 * fall-back overlap {@code 2024-10-27 04:00→03:00}, {@code 2024-02-29} = Thursday. These tests assert
 * via the resolver's own ISO-weekday/date math rather than hard-coding "Ukraine observes DST", so they
 * stay correct if the tz policy ever changes — only the literal transition dates would move.
 *
 * <p>Slot-level DST effects (skipped/duplicated hour producing/avoiding slots) live in
 * {@code com.beautica.booking.SlotCalculationScheduleIT}; this class proves the resolver/date-math layer.
 */
@DisplayName("MasterScheduleEdgeCaseIT — leap year / DST / boundary / overlap-gap / past-future / precedence (frozen Kyiv clock)")
@Import(MasterScheduleEdgeCaseIT.FrozenKyivClockConfig.class)
@ActiveProfiles("test")
class MasterScheduleEdgeCaseIT extends AbstractIntegrationTest {

    /** Kyiv civil "today" = 2024-01-01. Noon Kyiv in winter (+02:00) = 10:00Z. */
    private static final Instant FROZEN_INSTANT = Instant.parse("2024-01-01T10:00:00Z");
    private static final Clock FROZEN_CLOCK = Clock.fixed(FROZEN_INSTANT, TimeZones.KYIV);

    private static final LocalDate TODAY = LocalDate.of(2024, 1, 1);
    private static final LocalDate CAP = LocalDate.of(2026, 1, 1); // today + 2y
    private static final LocalDate LEAP_DAY = LocalDate.of(2024, 2, 29); // Thursday, ISO 4

    /**
     * Overrides {@code ClockConfig.systemClock()} (a {@code @ConditionalOnMissingBean}) with a fixed
     * Kyiv clock so every clock-dependent guard (past-edit, far-future cap) and {@link ScheduleDateMath}
     * preset resolves against a deterministic "today".
     */
    @TestConfiguration
    static class FrozenKyivClockConfig {
        @Bean
        Clock systemClock() {
            return FROZEN_CLOCK;
        }
    }

    @Autowired
    private MasterScheduleService scheduleService;

    @Autowired
    private ScheduleDateMath dateMath;

    @Autowired
    private WeeklyScheduleRepository weeklyScheduleRepository;

    @Autowired
    private ScheduleExceptionRepository scheduleExceptionRepository;

    @Autowired
    private JdbcTemplate jdbc;

    // ── fixtures: an INDEPENDENT_MASTER (owns itself) ────────────────────────────────

    private record SeededMaster(UUID masterId, UUID actorId) {
    }

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
        return new SeededMaster(masterId, userId);
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

    private static EffectiveDayResponse byDate(List<EffectiveDayResponse> days, LocalDate date) {
        return days.stream().filter(d -> d.date().equals(date)).findFirst().orElseThrow();
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 0. Frozen-clock self-check — proves the bean override took effect
    // ════════════════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("frozen clock — ScheduleDateMath.today() resolves to 2024-01-01 Kyiv and cap to 2026-01-01")
    void should_pinTodayAndCap_when_frozenKyivClockInstalled() {
        assertThat(dateMath.today()).as("frozen Kyiv today").isEqualTo(TODAY);
        assertThat(dateMath.cap()).as("today + 2y far-future cap").isEqualTo(CAP);
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 1. Leap year «Високосний рік»
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Leap year")
    class LeapYear {

        @Test
        @DisplayName("resolveEffectiveRange spanning 2024-02-29 maps the leap day to ISO weekday 4 (Thursday)")
        void should_mapLeapDayToThursday_when_rangeSpansFeb29() {
            SeededMaster m = seedIndependentMaster();
            // Open-ended template whose ONLY working weekday is Thursday (ISO 4). If the resolver maps
            // 2024-02-29 to any other weekday it would surface an empty TEMPLATE day instead.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(LocalDate.of(2024, 2, 1), null, day(4, iv(9, 17))));

            assertThat(dateMath.isoDow(LEAP_DAY))
                    .as("2024-02-29 is a Thursday (ISO 4) per the tz/ISO calendar")
                    .isEqualTo(4);

            List<EffectiveDayResponse> days = scheduleService.resolveEffectiveRange(
                    m.masterId(), LocalDate.of(2024, 2, 28), LocalDate.of(2024, 3, 1));

            EffectiveDayResponse leap = byDate(days, LEAP_DAY);
            assertThat(leap.source())
                    .as("the leap day is covered by the open-ended template")
                    .isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(leap.intervals())
                    .as("leap day resolves to the Thursday interval — proving ISO-weekday 4 mapping")
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(LocalTime.of(9, 0), LocalTime.of(17, 0)));
            // The neighbouring non-Thursday dates carry no template interval (Wed / Fri).
            assertThat(byDate(days, LocalDate.of(2024, 2, 28)).intervals())
                    .as("2024-02-28 is a Wednesday — not a working weekday").isEmpty();
            assertThat(byDate(days, LocalDate.of(2024, 3, 1)).intervals())
                    .as("2024-03-01 is a Friday — not a working weekday").isEmpty();
        }

        @Test
        @DisplayName("a weekly window with validFrom = 2024-02-29 is accepted and covers the leap day")
        void should_acceptAndCover_when_validFromIsLeapDay() {
            SeededMaster m = seedIndependentMaster();

            WeeklyScheduleResponse resp = scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(LEAP_DAY, null, day(4, iv(10, 16)))); // Thursday interval

            assertThat(resp.validFrom())
                    .as("validFrom on the leap day persists exactly").isEqualTo(LEAP_DAY);

            EffectiveDayResponse leap = scheduleService.resolveEffectiveDay(m.masterId(), LEAP_DAY);
            assertThat(leap.source()).isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(leap.intervals())
                    .as("the window starting on the leap day covers the leap day itself (validFrom inclusive)")
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(LocalTime.of(10, 0), LocalTime.of(16, 0)));
        }

        @Test
        @DisplayName("end-to-end — 'Весь рік' year preset from a 2024 leap-year today ends 2024-12-31 (no Feb-29 / plusYears wrap)")
        void should_endOnDec31_when_yearPresetWindowUsedThroughResolver() {
            // The frozen today is 2024-01-01 (a leap year). The year preset must end on Dec 31 of the
            // SAME year — a plusYears(1) bug would roll into 2025 and could wrap a Feb-29 origin.
            var yearRange = dateMath.wholeYear();
            assertThat(yearRange.from()).isEqualTo(TODAY);
            assertThat(yearRange.to())
                    .as("year preset ends Dec 31 of the current (leap) year, never next year")
                    .isEqualTo(LocalDate.of(2024, 12, 31));

            SeededMaster m = seedIndependentMaster();
            // Persist a template over exactly that preset window and resolve its end date end-to-end:
            // Dec 31 must be inside the window (TEMPLATE), Jan 1 of the NEXT year must be a gap.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(yearRange.from(), yearRange.to(), day(2, iv(9, 17)))); // Tuesday (2024-12-31 is Tue)

            assertThat(dateMath.isoDow(LocalDate.of(2024, 12, 31)))
                    .as("2024-12-31 is a Tuesday (ISO 2)").isEqualTo(2);
            EffectiveDayResponse dec31 = scheduleService.resolveEffectiveDay(
                    m.masterId(), LocalDate.of(2024, 12, 31));
            assertThat(dec31.source())
                    .as("Dec 31 of the leap year is the inclusive end of the preset window")
                    .isEqualTo(EffectiveDaySource.TEMPLATE);

            EffectiveDayResponse jan1Next = scheduleService.resolveEffectiveDay(
                    m.masterId(), LocalDate.of(2025, 1, 1));
            assertThat(jan1Next.source())
                    .as("the day after the year preset window is uncovered — proves no plusYears overshoot")
                    .isEqualTo(EffectiveDaySource.NO_SCHEDULE);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 2. Year / month boundaries
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Year / month boundaries")
    class Boundaries {

        @Test
        @DisplayName("two abutting validity windows (…2024-12-31 / 2025-01-01…) — no gap, no overlap, each date resolves to its own window")
        void should_resolveEachWindow_when_windowsAbutAcrossYearBoundary() {
            SeededMaster m = seedIndependentMaster();
            // Window A ends 2024-12-31 (a Tuesday → ISO 2 working). Window B starts 2025-01-01
            // (a Wednesday → ISO 3 working). They touch but do not overlap.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(LocalDate.of(2024, 12, 1), LocalDate.of(2024, 12, 31), day(2, iv(9, 17))));
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31), day(3, iv(8, 12))));

            assertThat(weeklyScheduleRepository.findByMasterIdOrderByValidFromAsc(m.masterId()))
                    .as("both abutting windows coexist").hasSize(2);

            // Dec 31 → window A's Tuesday interval.
            EffectiveDayResponse dec31 = scheduleService.resolveEffectiveDay(
                    m.masterId(), LocalDate.of(2024, 12, 31));
            assertThat(dec31.source()).isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(dec31.intervals())
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .as("Dec 31 resolves to window A (09–17)")
                    .containsExactly(tuple(LocalTime.of(9, 0), LocalTime.of(17, 0)));

            // Jan 1 → window B's Wednesday interval — no gap between the windows.
            EffectiveDayResponse jan1 = scheduleService.resolveEffectiveDay(
                    m.masterId(), LocalDate.of(2025, 1, 1));
            assertThat(jan1.source()).isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(jan1.intervals())
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .as("Jan 1 resolves to window B (08–12) — windows abut with no gap")
                    .containsExactly(tuple(LocalTime.of(8, 0), LocalTime.of(12, 0)));
        }

        @Test
        @DisplayName("a window ending Dec 31 with a gap into January → January dates resolve NO_SCHEDULE (OQ-3)")
        void should_resolveNoSchedule_when_gapAfterDec31IntoJanuary() {
            SeededMaster m = seedIndependentMaster();
            // Window ends 2024-12-31; the next window only starts 2025-02-01 → all of January is a gap.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(LocalDate.of(2024, 12, 1), LocalDate.of(2024, 12, 31), day(2, iv(9, 17))));
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(LocalDate.of(2025, 2, 1), LocalDate.of(2025, 2, 28), day(6, iv(9, 17))));

            List<EffectiveDayResponse> jan = scheduleService.resolveEffectiveRange(
                    m.masterId(), LocalDate.of(2025, 1, 1), LocalDate.of(2025, 1, 31));

            assertThat(jan)
                    .as("every January date falls in the gap between the two windows")
                    .hasSize(31)
                    .allSatisfy(d -> {
                        assertThat(d.source()).isEqualTo(EffectiveDaySource.NO_SCHEDULE);
                        assertThat(d.intervals()).isEmpty();
                    });
        }

        @Test
        @DisplayName("month-boundary preset — 'Весь поточний місяць' on the 31st of a 31-day month returns [31st..31st]")
        void should_returnSingleDay_when_wholeCurrentMonthOnLastDayOf31DayMonth() {
            // A clock pinned to 2024-01-31 (31-day month) drives a fresh ScheduleDateMath; the preset
            // must clamp 'from' to today (the 31st) and 'to' to the last day of the month (also the 31st).
            ScheduleDateMath jan31 = new ScheduleDateMath(
                    Clock.fixed(LocalDate.of(2024, 1, 31).atTime(12, 0)
                            .atZone(TimeZones.KYIV).toInstant(), ZoneOffset.UTC));

            var range = jan31.wholeCurrentMonth();

            assertThat(range.from()).isEqualTo(LocalDate.of(2024, 1, 31));
            assertThat(range.to())
                    .as("last day of a 31-day month equals today when today is the 31st")
                    .isEqualTo(LocalDate.of(2024, 1, 31));
        }

        @Test
        @DisplayName("month-boundary preset — 'Весь поточний місяць' on Feb 29 (leap) ends Feb 29; on Feb 28 (non-leap) ends Feb 28")
        void should_clampToLastFebDay_when_wholeCurrentMonthInFebruary() {
            // Leap February: today 2024-02-29 → month ends 2024-02-29.
            ScheduleDateMath leapFeb = new ScheduleDateMath(
                    Clock.fixed(LEAP_DAY.atTime(12, 0).atZone(TimeZones.KYIV).toInstant(), ZoneOffset.UTC));
            assertThat(leapFeb.wholeCurrentMonth().to())
                    .as("leap February ends on the 29th").isEqualTo(LEAP_DAY);

            // Non-leap February: today 2023-02-28 → month ends 2023-02-28.
            ScheduleDateMath nonLeapFeb = new ScheduleDateMath(
                    Clock.fixed(LocalDate.of(2023, 2, 28).atTime(12, 0)
                            .atZone(TimeZones.KYIV).toInstant(), ZoneOffset.UTC));
            assertThat(nonLeapFeb.wholeCurrentMonth().to())
                    .as("non-leap February ends on the 28th").isEqualTo(LocalDate.of(2023, 2, 28));
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 3. Overlap / gap — all geometries
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Overlap / gap geometries")
    class OverlapGap {

        private SeededMaster withBaseWindow() {
            SeededMaster m = seedIndependentMaster();
            // Base bounded window: 2024-06-01 .. 2024-06-30.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30), day(1, iv(9, 17))));
            return m;
        }

        private void assertRejected(SeededMaster m, LocalDate from, LocalDate to) {
            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null, weekly(from, to, day(2, iv(9, 17)))))
                    .isInstanceOf(BusinessException.class);
            assertThat(weeklyScheduleRepository.findByMasterIdOrderByValidFromAsc(m.masterId()))
                    .as("the rejected overlapping window must not be persisted").hasSize(1);
        }

        @Test
        @DisplayName("nested overlap (candidate strictly inside the base window) is rejected")
        void should_reject_when_candidateNestedInsideBase() {
            assertRejected(withBaseWindow(), LocalDate.of(2024, 6, 10), LocalDate.of(2024, 6, 20));
        }

        @Test
        @DisplayName("partial-leading overlap (candidate starts before, ends inside) is rejected")
        void should_reject_when_candidatePartialLeading() {
            assertRejected(withBaseWindow(), LocalDate.of(2024, 5, 15), LocalDate.of(2024, 6, 15));
        }

        @Test
        @DisplayName("partial-trailing overlap (candidate starts inside, ends after) is rejected")
        void should_reject_when_candidatePartialTrailing() {
            assertRejected(withBaseWindow(), LocalDate.of(2024, 6, 15), LocalDate.of(2024, 7, 15));
        }

        @Test
        @DisplayName("identical window (same validFrom/validTo as the base) is rejected")
        void should_reject_when_candidateIdenticalToBase() {
            assertRejected(withBaseWindow(), LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30));
        }

        @Test
        @DisplayName("open-ended candidate that swallows a later bounded window is rejected (validTo=null = +infinity)")
        void should_reject_when_openEndedCandidateOverlapsBounded() {
            SeededMaster m = seedIndependentMaster();
            // Bounded base far in the future.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(LocalDate.of(2024, 9, 1), LocalDate.of(2024, 9, 30), day(1, iv(9, 17))));
            // Open-ended candidate starting before it → extends to +infinity and overlaps.
            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(LocalDate.of(2024, 8, 1), null, day(2, iv(9, 17)))))
                    .as("an open-ended candidate covering an existing bounded window overlaps it")
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("bounded-vs-open-ended reverse geometry — a bounded candidate overlapping an existing open-ended window is rejected")
        void should_reject_when_boundedCandidateOverlapsOpenEnded() {
            SeededMaster m = seedIndependentMaster();
            // Existing open-ended window from 2024-08-01.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(LocalDate.of(2024, 8, 1), null, day(1, iv(9, 17))));
            // Bounded candidate well after its start overlaps the +infinity tail.
            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null,
                    weekly(LocalDate.of(2024, 10, 1), LocalDate.of(2024, 10, 31), day(2, iv(9, 17)))))
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("adjacent (touching) windows are allowed — base ends day N, candidate starts day N+1")
        void should_allow_when_windowsTouchButDoNotOverlap() {
            SeededMaster m = withBaseWindow(); // 2024-06-01 .. 2024-06-30
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(LocalDate.of(2024, 7, 1), LocalDate.of(2024, 7, 31), day(2, iv(9, 17))));

            assertThat(weeklyScheduleRepository.findByMasterIdOrderByValidFromAsc(m.masterId()))
                    .as("adjacent windows (Jun ends 30th, Jul starts 1st) both persist").hasSize(2);
        }

        @Test
        @DisplayName("gap date between two windows resolves to NO_SCHEDULE with zero intervals")
        void should_resolveNoSchedule_when_dateFallsInGapBetweenWindows() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 15), day(1, iv(9, 17))));
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(LocalDate.of(2024, 7, 1), LocalDate.of(2024, 7, 15), day(1, iv(9, 17))));

            EffectiveDayResponse gap = scheduleService.resolveEffectiveDay(
                    m.masterId(), LocalDate.of(2024, 6, 20));
            assertThat(gap.source()).isEqualTo(EffectiveDaySource.NO_SCHEDULE);
            assertThat(gap.intervals()).isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 4. Past / future bounds — frozen-clock midnight boundary
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Past / future bounds")
    class PastFutureBounds {

        @Test
        @DisplayName("override edit on yesterday (2023-12-31) is rejected — proves the Kyiv civil-date past guard at the year boundary")
        void should_rejectPastOverride_when_dateIsYesterdayInKyiv() {
            SeededMaster m = seedIndependentMaster();
            // Frozen Kyiv today = 2024-01-01; yesterday = 2023-12-31 (across the year boundary).
            assertThatThrownBy(() -> scheduleService.upsertOverride(
                    m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(LocalDate.of(2023, 12, 31),
                            ScheduleExceptionKind.DAY_OFF, null)))
                    .as("a past override (yesterday in Kyiv) is rejected")
                    .isInstanceOf(BusinessException.class);

            assertThat(scheduleExceptionRepository.findByMasterIdAndDate(
                    m.masterId(), LocalDate.of(2023, 12, 31)))
                    .as("no past override is persisted").isEmpty();
        }

        @Test
        @DisplayName("weekly template edit with validFrom = today (2024-01-01) is accepted — the Kyiv boundary day itself is editable")
        void should_acceptTodayValidFrom_when_atKyivMidnightBoundary() {
            SeededMaster m = seedIndependentMaster();
            WeeklyScheduleResponse resp = scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null, weekly(TODAY, null, day(1, iv(9, 17))));
            assertThat(resp.validFrom())
                    .as("today (Kyiv civil) is editable — not classified as past").isEqualTo(TODAY);
        }

        @Test
        @DisplayName("validTo exactly at the today+2y cap is accepted; one day past the cap is rejected")
        void should_acceptValidToAtCap_butRejectBeyond() {
            SeededMaster m = seedIndependentMaster();

            WeeklyScheduleResponse atCap = scheduleService.upsertWeeklySchedule(
                    m.actorId(), m.masterId(), null, weekly(TODAY, CAP, day(1, iv(9, 17))));
            assertThat(atCap.validTo())
                    .as("validTo exactly at today+2y is the inclusive far-future boundary").isEqualTo(CAP);

            SeededMaster m2 = seedIndependentMaster();
            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(
                    m2.actorId(), m2.masterId(), null,
                    weekly(TODAY, CAP.plusDays(1), day(1, iv(9, 17)))))
                    .as("validTo one day past the cap is rejected")
                    .isInstanceOf(BusinessException.class);
        }

        @Test
        @DisplayName("resolveEffectiveRange of exactly 365 days between endpoints (366 inclusive) is accepted; 366 days between is rejected")
        void should_accept365DaySpan_butReject366() {
            SeededMaster m = seedIndependentMaster();
            // 365 days between → 366 inclusive dates — the documented maximum (a full leap year).
            List<EffectiveDayResponse> days = scheduleService.resolveEffectiveRange(
                    m.masterId(), TODAY, TODAY.plusDays(365));
            assertThat(days)
                    .as("a 365-days-between span materialises 366 inclusive dates")
                    .hasSize(366);

            assertThatThrownBy(() -> scheduleService.resolveEffectiveRange(
                    m.masterId(), TODAY, TODAY.plusDays(366)))
                    .as("a 366-days-between span exceeds the 366-date cap and is rejected")
                    .isInstanceOf(BusinessException.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // 5. Precedence — override > template > gap; DAY_OFF/CUSTOM_HOURS cannot coexist
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Precedence")
    class Precedence {

        @Test
        @DisplayName("override beats template beats gap, end-to-end through the resolver on a single date")
        void should_applyOverrideThenTemplateThenGap_when_resolving() {
            SeededMaster m = seedIndependentMaster();
            // 2024-06-03 is a Monday (ISO 1). Template covers June with a Monday interval.
            LocalDate monday = LocalDate.of(2024, 6, 3);
            assertThat(dateMath.isoDow(monday)).isEqualTo(1);
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30), day(1, iv(9, 17))));

            // Before override: TEMPLATE wins over the gap.
            EffectiveDayResponse beforeOverride = scheduleService.resolveEffectiveDay(m.masterId(), monday);
            assertThat(beforeOverride.source()).isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(beforeOverride.intervals())
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(LocalTime.of(9, 0), LocalTime.of(17, 0)));

            // Apply a CUSTOM_HOURS override → it beats the template.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday, ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(iv(12, 14))));
            EffectiveDayResponse afterOverride = scheduleService.resolveEffectiveDay(m.masterId(), monday);
            assertThat(afterOverride.source()).isEqualTo(EffectiveDaySource.OVERRIDE_CUSTOM);
            assertThat(afterOverride.intervals())
                    .as("the custom override replaces the template interval")
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(LocalTime.of(12, 0), LocalTime.of(14, 0)));

            // A date outside the template window with no override → gap.
            EffectiveDayResponse gapDay = scheduleService.resolveEffectiveDay(
                    m.masterId(), LocalDate.of(2024, 7, 5));
            assertThat(gapDay.source()).isEqualTo(EffectiveDaySource.NO_SCHEDULE);
        }

        @Test
        @DisplayName("re-upserting an override for the same date replaces it (one row per date) — DAY_OFF and CUSTOM_HOURS cannot coexist")
        void should_keepSingleRow_when_overrideReupsertedWithDifferentKind() {
            SeededMaster m = seedIndependentMaster();
            LocalDate date = LocalDate.of(2024, 6, 10);

            // First a DAY_OFF closure.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(date, ScheduleExceptionKind.DAY_OFF, null));
            // Then a CUSTOM_HOURS for the same date — must REPLACE, not add a second row.
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(date, ScheduleExceptionKind.CUSTOM_HOURS,
                            List.of(iv(13, 15))));

            Integer rowCount = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM schedule_exceptions WHERE master_id = ? AND date = ?",
                    Integer.class, m.masterId(), date);
            assertThat(rowCount)
                    .as("UNIQUE(master_id, date) — exactly one override row survives per date")
                    .isEqualTo(1);

            // The surviving row is the CUSTOM_HOURS one.
            EffectiveDayResponse resolved = scheduleService.resolveEffectiveDay(m.masterId(), date);
            assertThat(resolved.source())
                    .as("the second upsert replaced the DAY_OFF with CUSTOM_HOURS")
                    .isEqualTo(EffectiveDaySource.OVERRIDE_CUSTOM);
            assertThat(resolved.intervals())
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(LocalTime.of(13, 0), LocalTime.of(15, 0)));
        }

        @Test
        @DisplayName("clearing an override reverts the date to the underlying template (override removed, template re-emerges)")
        void should_revertToTemplate_when_overrideCleared() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = LocalDate.of(2024, 6, 3); // ISO 1
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(LocalDate.of(2024, 6, 1), LocalDate.of(2024, 6, 30), day(1, iv(9, 17))));
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday, ScheduleExceptionKind.DAY_OFF, null));

            // Confirm the override is in effect, then clear it.
            assertThat(scheduleService.resolveEffectiveDay(m.masterId(), monday).source())
                    .isEqualTo(EffectiveDaySource.OVERRIDE_DAY_OFF);
            scheduleService.clearOverride(m.actorId(), m.masterId(), monday);

            EffectiveDayResponse reverted = scheduleService.resolveEffectiveDay(m.masterId(), monday);
            assertThat(reverted.source())
                    .as("after clearing the override the template re-emerges").isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(reverted.intervals())
                    .extracting(WorkIntervalDto::startTime, WorkIntervalDto::endTime)
                    .containsExactly(tuple(LocalTime.of(9, 0), LocalTime.of(17, 0)));
        }
    }
}
