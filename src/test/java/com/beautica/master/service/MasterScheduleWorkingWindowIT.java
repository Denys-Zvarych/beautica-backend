package com.beautica.master.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.BusinessException;
import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.dto.EffectiveDaySource;
import com.beautica.master.dto.ScheduleOverrideRequest;
import com.beautica.master.dto.ScheduleOverrideResponse;
import com.beautica.master.dto.WeeklyScheduleDayRequest;
import com.beautica.master.dto.WeeklyScheduleDayResponse;
import com.beautica.master.dto.WeeklyScheduleRequest;
import com.beautica.master.dto.WeeklyScheduleResponse;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 15.12 — the optional, display-only working-window bounds ({@code windowStart}/{@code windowEnd})
 * on both schedule surfaces: the weekly template and the per-date override.
 *
 * <h2>The bug this feature exists to fix</h2>
 * The mobile editor presents a day as ONE window (від–до) with breaks carved out, stores only the resulting
 * working INTERVALS, and rebuilds breaks from the GAPS BETWEEN them. A break flush against an edge of the
 * window leaves no gap: window 09:00–18:00 minus a 09:00–10:00 break == the single interval
 * {@code [10:00–18:00]}, which reads back as "window 10:00–18:00, no breaks". Recording the window lets the
 * client derive breaks as {@code window MINUS intervals} and recover the flush one — the round trip pinned
 * by {@link RoundTrip#should_preserveEdgeFlushBreak_when_windowRecordedOnWeeklyDay()}.
 *
 * <h2>What is deliberately NOT tested here</h2>
 * That the window never changes availability. That safety property belongs to the slot engine and is pinned
 * end-to-end in {@code SlotCalculationScheduleIT#should_produceIdenticalSlots_when_workingWindowIsStored}.
 */
@DisplayName("MasterScheduleService — 15.12 display-only working-window bounds (real Postgres)")
class MasterScheduleWorkingWindowIT extends AbstractIntegrationTest {

    @Autowired
    private MasterScheduleService scheduleService;

    @Autowired
    private JdbcTemplate jdbc;

    /** Safe-margin future date: well clear of "today" so the no-past-edit guard never trips by accident. */
    private static final LocalDate FUTURE_FROM = LocalDate.now().plusDays(30);

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

    private static WorkIntervalDto iv(String start, String end) {
        return new WorkIntervalDto(LocalTime.parse(start), LocalTime.parse(end));
    }

    private static LocalTime at(String hhmmss) {
        return LocalTime.parse(hhmmss);
    }

    /** An INTERVAL weekday carrying the given window bounds (either may be null). */
    private static WeeklyScheduleDayRequest dayWithWindow(
            int dow, LocalTime windowStart, LocalTime windowEnd, WorkIntervalDto... intervals) {
        return new WeeklyScheduleDayRequest(
                dow, WeekdayMode.INTERVAL, List.of(intervals), null, windowStart, windowEnd);
    }

    private static WeeklyScheduleRequest weekly(WeeklyScheduleDayRequest... days) {
        return new WeeklyScheduleRequest(FUTURE_FROM, null, List.of(days));
    }

    private static WeeklyScheduleDayResponse dayOf(WeeklyScheduleResponse response, int dow) {
        return response.days().stream()
                .filter(d -> d.dayOfWeek() == dow)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no day " + dow + " in response"));
    }

    /**
     * One date's projection as the {@code GET /effective-schedule} endpoint serves it — i.e. through
     * {@code resolveEffectiveRangeForDisplay}, the only resolver variant that projects the Phase 15.12
     * display-only window. The window-free {@code resolveEffectiveDay}/{@code resolveEffectiveRange} are
     * what {@code SlotCalculationService} and the boolean day-gating paths call.
     */
    private EffectiveDayResponse displayDay(UUID masterId, LocalDate date) {
        return scheduleService.resolveEffectiveRangeForDisplay(masterId, date, date).get(0);
    }

    private ScheduleOverrideRequest overrideWithWindow(
            LocalDate date, LocalTime windowStart, LocalTime windowEnd, WorkIntervalDto... intervals) {
        return new ScheduleOverrideRequest(date, ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL,
                List.of(intervals), null, false, windowStart, windowEnd);
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Round trip — the edge-flush break the feature exists to preserve
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Round trip")
    class RoundTrip {

        @Test
        @DisplayName("weekly day: a break flush against the window start survives the round trip")
        void should_preserveEdgeFlushBreak_when_windowRecordedOnWeeklyDay() {
            SeededMaster m = seedIndependentMaster();
            // Window 09:00–18:00 with a 09:00–10:00 break collapses to ONE interval [10:00–18:00] —
            // no gap, so gap reconstruction alone reports "window 10:00–18:00, no breaks".
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("09:00:00"), at("18:00:00"), iv("10:00:00", "18:00:00"))));

            WeeklyScheduleResponse read = scheduleService.listWeeklySchedules(m.masterId()).get(0);

            WeeklyScheduleDayResponse monday = dayOf(read, 1);
            assertThat(monday.windowStart()).isEqualTo(at("09:00:00"));
            assertThat(monday.windowEnd()).isEqualTo(at("18:00:00"));
            assertThat(monday.intervals())
                    .as("availability is unchanged — still the single [10:00–18:00] interval")
                    .containsExactly(iv("10:00:00", "18:00:00"));
        }

        @Test
        @DisplayName("weekly day: a window is replaced, not accumulated, when the template is re-saved")
        void should_replaceWindow_when_templateReSaved() {
            SeededMaster m = seedIndependentMaster();
            UUID scheduleId = scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("09:00:00"), at("18:00:00"), iv("10:00:00", "18:00:00"))))
                    .id();

            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), scheduleId,
                    weekly(dayWithWindow(1, at("08:00:00"), at("20:00:00"), iv("10:00:00", "18:00:00"))));

            WeeklyScheduleDayResponse monday =
                    dayOf(scheduleService.listWeeklySchedules(m.masterId()).get(0), 1);
            assertThat(monday.windowStart()).isEqualTo(at("08:00:00"));
            assertThat(monday.windowEnd()).isEqualTo(at("20:00:00"));
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM weekly_schedule_day_windows WHERE schedule_id = ?",
                    Integer.class, scheduleId))
                    .as("the old row is replaced, never accumulated (uq_day_window_per_day)")
                    .isEqualTo(1);
        }

        @Test
        @DisplayName("weekly day: dropping the window from the payload clears the stored bounds")
        void should_clearWindow_when_reSavedWithoutIt() {
            SeededMaster m = seedIndependentMaster();
            UUID scheduleId = scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("09:00:00"), at("18:00:00"), iv("10:00:00", "18:00:00"))))
                    .id();

            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), scheduleId,
                    weekly(dayWithWindow(1, null, null, iv("10:00:00", "18:00:00"))));

            WeeklyScheduleDayResponse monday =
                    dayOf(scheduleService.listWeeklySchedules(m.masterId()).get(0), 1);
            assertThat(monday.windowStart()).isNull();
            assertThat(monday.windowEnd()).isNull();
        }

        @Test
        @DisplayName("override: a break flush against the window end survives the round trip")
        void should_preserveEdgeFlushBreak_when_windowRecordedOnOverride() {
            SeededMaster m = seedIndependentMaster();
            LocalDate date = FUTURE_FROM.plusDays(3);
            // Window 09:00–18:00 with a 17:00–18:00 break collapses to ONE interval [09:00–17:00].
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    overrideWithWindow(date, at("09:00:00"), at("18:00:00"), iv("09:00:00", "17:00:00")));

            ScheduleOverrideResponse read = scheduleService
                    .listOverrides(m.masterId(), date, date).get(0);

            assertThat(read.windowStart()).isEqualTo(at("09:00:00"));
            assertThat(read.windowEnd()).isEqualTo(at("18:00:00"));
            assertThat(read.intervals()).containsExactly(iv("09:00:00", "17:00:00"));
        }

        @Test
        @DisplayName("weekly day: an edge-flush break AND a mid-day break both survive the round trip")
        void should_preserveBothBreaks_when_dayHasEdgeFlushAndMidDayBreak() {
            SeededMaster m = seedIndependentMaster();
            // The realistic production shape the single-interval tests above do not reach: window
            // 09:00–18:00 with a 09:00–10:00 break flush against the start AND a 13:00–14:00 lunch break.
            // The lunch break survives as a GAP between two intervals; only the flush one needs the window.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("09:00:00"), at("18:00:00"),
                            iv("10:00:00", "13:00:00"), iv("14:00:00", "18:00:00"))));

            WeeklyScheduleDayResponse monday =
                    dayOf(scheduleService.listWeeklySchedules(m.masterId()).get(0), 1);

            assertThat(monday.windowStart()).isEqualTo(at("09:00:00"));
            assertThat(monday.windowEnd()).isEqualTo(at("18:00:00"));
            assertThat(monday.intervals())
                    .as("window MINUS intervals yields BOTH breaks: 09:00–10:00 (flush) and 13:00–14:00 (gap)")
                    .containsExactly(iv("10:00:00", "13:00:00"), iv("14:00:00", "18:00:00"));
        }

        @Test
        @DisplayName("seconds supplied on the window bounds are zeroed, exactly as on intervals")
        void should_zeroSeconds_when_windowCarriesThem() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("09:00:45"), at("18:00:59"), iv("10:00:00", "18:00:00"))));

            WeeklyScheduleDayResponse monday =
                    dayOf(scheduleService.listWeeklySchedules(m.masterId()).get(0), 1);

            assertThat(monday.windowStart()).isEqualTo(at("09:00:00"));
            assertThat(monday.windowEnd()).isEqualTo(at("18:00:00"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Backward compatibility — a null window must behave exactly as before 15.12
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Backward compatibility")
    class BackwardCompatibility {

        @Test
        @DisplayName("a pre-15.12 payload (no window) round-trips with null bounds and no stored row")
        void should_reportNullWindow_when_clientOmitsIt() {
            SeededMaster m = seedIndependentMaster();
            UUID scheduleId = scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    // the pre-15.12 2-arg convenience constructor — the exact legacy call shape
                    weekly(new WeeklyScheduleDayRequest(1, List.of(iv("10:00:00", "18:00:00"))))).id();

            WeeklyScheduleDayResponse monday =
                    dayOf(scheduleService.listWeeklySchedules(m.masterId()).get(0), 1);

            assertThat(monday.windowStart()).isNull();
            assertThat(monday.windowEnd()).isNull();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM weekly_schedule_day_windows WHERE schedule_id = ?",
                    Integer.class, scheduleId))
                    .as("no window is ever synthesized from min(start)..max(end) — legacy rows stay null")
                    .isZero();
        }

        @Test
        @DisplayName("a day off never stores a window, even when the client supplies one")
        void should_ignoreWindow_when_dayHasNoIntervals() {
            SeededMaster m = seedIndependentMaster();
            UUID scheduleId = scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("09:00:00"), at("18:00:00")),
                            dayWithWindow(2, null, null, iv("10:00:00", "18:00:00")))).id();

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM weekly_schedule_day_windows WHERE schedule_id = ?",
                    Integer.class, scheduleId))
                    .as("a day with no intervals carries no working window — the value is ignored, not stored")
                    .isZero();
        }

        @Test
        @DisplayName("a DAY_OFF override never stores a window, even when the client supplies one")
        void should_ignoreWindow_when_overrideIsDayOff() {
            SeededMaster m = seedIndependentMaster();
            LocalDate date = FUTURE_FROM.plusDays(5);

            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(date, ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL,
                            null, null, false, at("09:00:00"), at("18:00:00")));

            ScheduleOverrideResponse read = scheduleService.listOverrides(m.masterId(), date, date).get(0);
            assertThat(read.kind()).isEqualTo(ScheduleExceptionKind.DAY_OFF);
            assertThat(read.windowStart()).isNull();
            assertThat(read.windowEnd()).isNull();
        }

        @Test
        @DisplayName("flipping a CUSTOM_HOURS override to DAY_OFF clears a previously stored window")
        void should_clearStoredWindow_when_overrideFlipsToDayOff() {
            SeededMaster m = seedIndependentMaster();
            LocalDate date = FUTURE_FROM.plusDays(7);
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    overrideWithWindow(date, at("09:00:00"), at("18:00:00"), iv("09:00:00", "17:00:00")));

            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(date, ScheduleExceptionKind.DAY_OFF, null));

            ScheduleOverrideResponse read = scheduleService.listOverrides(m.masterId(), date, date).get(0);
            assertThat(read.windowStart())
                    .as("chk_exc_window_kind forbids a window on a DAY_OFF row — it must be cleared")
                    .isNull();
            assertThat(read.windowEnd()).isNull();
        }

        @Test
        @DisplayName("an EXPLICIT_TIMES override never stores a window, even when the client supplies one")
        void should_ignoreWindow_when_overrideIsExplicitTimes() {
            SeededMaster m = seedIndependentMaster();
            LocalDate date = FUTURE_FROM.plusDays(11);

            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(date, ScheduleExceptionKind.CUSTOM_HOURS,
                            WeekdayMode.EXPLICIT_TIMES, null, List.of(at("10:00:00"), at("12:00:00")),
                            false, at("09:00:00"), at("18:00:00")));

            ScheduleOverrideResponse read = scheduleService.listOverrides(m.masterId(), date, date).get(0);
            assertThat(read.mode()).isEqualTo(WeekdayMode.EXPLICIT_TIMES);
            assertThat(read.windowStart())
                    .as("discrete times have no window-with-breaks affordance — the value is ignored")
                    .isNull();
            // The response mapper takes an EXPLICIT_TIMES early-return branch that never READS the columns,
            // so it would report null even if a value had been persisted. Assert the COLUMN, not the echo:
            // the row is CUSTOM_HOURS, so chk_exc_window_kind would happily allow a stored window, and the
            // response and the database would silently disagree.
            assertThat(jdbc.queryForObject(
                    "SELECT window_start FROM schedule_exceptions WHERE master_id = ? AND date = ?",
                    String.class, m.masterId(), date))
                    .as("nothing may be persisted either — the response mapper cannot be the only guard")
                    .isNull();
        }

        @Test
        @DisplayName("an EXPLICIT_TIMES weekday never carries a window")
        void should_reportNullWindow_when_dayIsExplicitTimes() {
            SeededMaster m = seedIndependentMaster();
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(new WeeklyScheduleDayRequest(1, WeekdayMode.EXPLICIT_TIMES, null,
                            List.of(at("10:00:00"), at("12:00:00")), at("09:00:00"), at("18:00:00"))));

            WeeklyScheduleDayResponse monday =
                    dayOf(scheduleService.listWeeklySchedules(m.masterId()).get(0), 1);

            assertThat(monday.mode()).isEqualTo(WeekdayMode.EXPLICIT_TIMES);
            assertThat(monday.windowStart())
                    .as("discrete times have no window-with-breaks affordance — no window is stored")
                    .isNull();
            assertThat(monday.windowEnd()).isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Effective-day projection — the surface the mobile DAY editor actually hydrates from
    // ════════════════════════════════════════════════════════════════════════════════

    /**
     * The mobile day-override editor seeds itself from {@code GET /masters/{id}/effective-schedule}
     * ({@code master_schedule_screen.dart} → {@code DayHours.fromIntervals}), NOT from
     * {@code GET /overrides}. So the window has to reach {@link EffectiveDayResponse} or the edge-flush
     * break stays invisible on that surface no matter what the override endpoint returns.
     *
     * <p>The projected window always describes the SAME source as the intervals beside it.
     */
    @Nested
    @DisplayName("Effective-day projection")
    class EffectiveDayProjection {

        @Test
        @DisplayName("TEMPLATE day projects the template weekday's stored window")
        void should_projectTemplateWindow_when_sourceIsTemplate() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = FUTURE_FROM.with(java.time.DayOfWeek.MONDAY).plusWeeks(1);
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("09:00:00"), at("18:00:00"), iv("10:00:00", "18:00:00"))));

            EffectiveDayResponse day = displayDay(m.masterId(), monday);

            assertThat(day.source()).isEqualTo(EffectiveDaySource.TEMPLATE);
            assertThat(day.windowStart()).isEqualTo(at("09:00:00"));
            assertThat(day.windowEnd()).isEqualTo(at("18:00:00"));
            assertThat(day.intervals())
                    .as("the derived break is window MINUS intervals == 09:00–10:00")
                    .containsExactly(iv("10:00:00", "18:00:00"));
        }

        @Test
        @DisplayName("OVERRIDE_CUSTOM day projects the override row's window, not the template's")
        void should_projectOverrideWindow_when_sourceIsOverrideCustom() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = FUTURE_FROM.with(java.time.DayOfWeek.MONDAY).plusWeeks(1);
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("09:00:00"), at("18:00:00"), iv("10:00:00", "18:00:00"))));
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    overrideWithWindow(monday, at("12:00:00"), at("16:00:00"), iv("12:00:00", "15:00:00")));

            EffectiveDayResponse day = displayDay(m.masterId(), monday);

            assertThat(day.source()).isEqualTo(EffectiveDaySource.OVERRIDE_CUSTOM);
            assertThat(day.windowStart())
                    .as("override beats template for the window exactly as it does for the intervals")
                    .isEqualTo(at("12:00:00"));
            assertThat(day.windowEnd()).isEqualTo(at("16:00:00"));
        }

        @Test
        @DisplayName("OVERRIDE_DAY_OFF projects a null window")
        void should_projectNullWindow_when_sourceIsDayOff() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = FUTURE_FROM.with(java.time.DayOfWeek.MONDAY).plusWeeks(1);
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("09:00:00"), at("18:00:00"), iv("10:00:00", "18:00:00"))));
            scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    new ScheduleOverrideRequest(monday, ScheduleExceptionKind.DAY_OFF, null));

            EffectiveDayResponse day = displayDay(m.masterId(), monday);

            assertThat(day.source()).isEqualTo(EffectiveDaySource.OVERRIDE_DAY_OFF);
            assertThat(day.windowStart()).isNull();
            assertThat(day.windowEnd()).isNull();
        }

        @Test
        @DisplayName("a legacy template day (no window stored) projects null — never a synthesized window")
        void should_projectNullWindow_when_legacyDayHasNoneStored() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = FUTURE_FROM.with(java.time.DayOfWeek.MONDAY).plusWeeks(1);
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(new WeeklyScheduleDayRequest(1, List.of(iv("10:00:00", "18:00:00")))));

            EffectiveDayResponse day = displayDay(m.masterId(), monday);

            assertThat(day.intervals()).containsExactly(iv("10:00:00", "18:00:00"));
            assertThat(day.windowStart())
                    .as("min(start)..max(end) is NEVER synthesized — that would assert 'no edge-flush break'")
                    .isNull();
            assertThat(day.windowEnd()).isNull();
        }

        @Test
        @DisplayName("NO_SCHEDULE and an uncovered weekday both project a null window")
        void should_projectNullWindow_when_noIntervalsResolve() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = FUTURE_FROM.with(java.time.DayOfWeek.MONDAY).plusWeeks(1);
            // Template covers the date range but defines Tuesday only — Monday is an uncovered weekday.
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(2, at("09:00:00"), at("18:00:00"), iv("10:00:00", "18:00:00"))));

            EffectiveDayResponse uncoveredWeekday = displayDay(m.masterId(), monday);
            EffectiveDayResponse noSchedule = displayDay(m.masterId(), FUTURE_FROM.minusDays(10));

            assertThat(uncoveredWeekday.intervals()).isEmpty();
            assertThat(uncoveredWeekday.windowStart()).isNull();
            assertThat(noSchedule.source()).isEqualTo(EffectiveDaySource.NO_SCHEDULE);
            assertThat(noSchedule.windowStart()).isNull();
        }

        @Test
        @DisplayName("an EXPLICIT_TIMES day projects discrete times and a null window")
        void should_projectNullWindow_when_dayIsExplicitTimes() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = FUTURE_FROM.with(java.time.DayOfWeek.MONDAY).plusWeeks(1);
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(new WeeklyScheduleDayRequest(1, WeekdayMode.EXPLICIT_TIMES, null,
                            List.of(at("10:00:00"), at("12:00:00")))));

            EffectiveDayResponse day = displayDay(m.masterId(), monday);

            assertThat(day.times()).containsExactly(at("10:00:00"), at("12:00:00"));
            assertThat(day.windowStart())
                    .as("discrete times already ARE the slot set — no window-with-breaks affordance")
                    .isNull();
            assertThat(day.windowEnd()).isNull();
        }

        @Test
        @DisplayName("the range resolver projects the window identically to the single-date resolver")
        void should_projectSameWindow_when_resolvedViaRange() {
            SeededMaster m = seedIndependentMaster();
            LocalDate monday = FUTURE_FROM.with(java.time.DayOfWeek.MONDAY).plusWeeks(1);
            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("09:00:00"), at("18:00:00"), iv("10:00:00", "18:00:00"))));

            List<EffectiveDayResponse> range =
                    scheduleService.resolveEffectiveRangeForDisplay(m.masterId(), monday, monday.plusDays(6));

            EffectiveDayResponse mondayFromRange = range.stream()
                    .filter(d -> d.date().equals(monday)).findFirst().orElseThrow();
            assertThat(mondayFromRange.windowStart())
                    .as("the bulk path must not lose the batch-fetched dayWindows collection")
                    .isEqualTo(at("09:00:00"));
            assertThat(mondayFromRange.windowEnd()).isEqualTo(at("18:00:00"));
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Service-layer validation (the rules Bean Validation cannot express)
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Validation")
    class Validation {

        @Test
        @DisplayName("rejects a window that starts after the earliest interval")
        void should_reject_when_windowStartsAfterFirstInterval() {
            SeededMaster m = seedIndependentMaster();

            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("11:00:00"), at("18:00:00"), iv("10:00:00", "18:00:00")))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("must contain every working interval");
        }

        @Test
        @DisplayName("rejects a window that ends before the latest interval")
        void should_reject_when_windowEndsBeforeLastInterval() {
            SeededMaster m = seedIndependentMaster();

            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("09:00:00"), at("17:00:00"), iv("10:00:00", "18:00:00")))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("must contain every working interval");
        }

        @Test
        @DisplayName("accepts a window exactly equal to the interval span (degenerate, no breaks)")
        void should_accept_when_windowEqualsIntervalSpan() {
            SeededMaster m = seedIndependentMaster();

            scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("10:00:00"), at("18:00:00"), iv("10:00:00", "18:00:00"))));

            WeeklyScheduleDayResponse monday =
                    dayOf(scheduleService.listWeeklySchedules(m.masterId()).get(0), 1);
            assertThat(monday.windowStart()).isEqualTo(at("10:00:00"));
        }

        @Test
        @DisplayName("rejects a half-specified window (defence in depth behind the DTO @AssertTrue)")
        void should_reject_when_onlyOneBoundSupplied() {
            SeededMaster m = seedIndependentMaster();

            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("09:00:00"), null, iv("10:00:00", "18:00:00")))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("must both be provided or both omitted");
        }

        @Test
        @DisplayName("rejects a zero-length window (end == start) — the strict-ordering boundary")
        void should_reject_when_windowEndEqualsStart() {
            SeededMaster m = seedIndependentMaster();
            // The exact boundary of `!end.isAfter(start)`. The cross-midnight case below exercises the
            // same branch from far away; only this pins that EQUAL is rejected rather than accepted, which
            // an `end.isBefore(start)` typo would flip while every other window test still passed.
            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("10:00:00"), at("10:00:00"), iv("10:00:00", "18:00:00")))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("end must be after its start");
        }

        @Test
        @DisplayName("rejects a window that excludes the LAST interval of a multi-interval day")
        void should_reject_when_windowExcludesLaterIntervalOfMultiIntervalDay() {
            SeededMaster m = seedIndependentMaster();
            // Containment reduces over min(start)/max(end) across ALL intervals. Every other containment
            // test uses a single interval, so a regression to `intervals.get(0)` would pass them all: here
            // the window contains the FIRST interval exactly and misses the second by two hours.
            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("09:00:00"), at("13:00:00"),
                            iv("10:00:00", "13:00:00"), iv("14:00:00", "18:00:00")))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("must contain every working interval");
        }

        @Test
        @DisplayName("rejects a cross-midnight window — the no-wraparound contract holds for windows too")
        void should_reject_when_windowWrapsPastMidnight() {
            SeededMaster m = seedIndependentMaster();

            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("22:00:00"), at("06:00:00"), iv("22:00:00", "23:30:00")))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("end must be after its start");
        }

        @Test
        @DisplayName("rejects a containment violation on the override surface too")
        void should_reject_when_overrideWindowDoesNotContainIntervals() {
            SeededMaster m = seedIndependentMaster();
            LocalDate date = FUTURE_FROM.plusDays(9);

            assertThatThrownBy(() -> scheduleService.upsertOverride(m.actorId(), m.masterId(),
                    overrideWithWindow(date, at("11:00:00"), at("18:00:00"), iv("10:00:00", "18:00:00"))))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("must contain every working interval");
        }

        @Test
        @DisplayName("rejects a null interval element with a 400-shaped error, not an NPE (500)")
        void should_reject_when_intervalListContainsNullElement() {
            SeededMaster m = seedIndependentMaster();
            // Service-layer mirror of the DTO's element-level @NotNull, for callers that reach the service
            // without passing through @Valid (the ITs, and ScheduleOverrideConflictService's forwarding).
            List<WorkIntervalDto> withNull = new java.util.ArrayList<>();
            withNull.add(iv("09:00:00", "17:00:00"));
            withNull.add(null);

            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    new WeeklyScheduleRequest(FUTURE_FROM, null, List.of(
                            new WeeklyScheduleDayRequest(1, WeekdayMode.INTERVAL, withNull, null, null, null)))))
                    .as("a null element must surface as a business validation error, never an NPE")
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("An interval must not be null");
        }

        @Test
        @DisplayName("a rejected window leaves no partial write behind")
        void should_persistNothing_when_windowRejected() {
            SeededMaster m = seedIndependentMaster();

            assertThatThrownBy(() -> scheduleService.upsertWeeklySchedule(m.actorId(), m.masterId(), null,
                    weekly(dayWithWindow(1, at("11:00:00"), at("18:00:00"), iv("10:00:00", "18:00:00")))))
                    .isInstanceOf(BusinessException.class);

            assertThat(scheduleService.listWeeklySchedules(m.masterId()))
                    .as("validation runs before any collection is mutated")
                    .isEmpty();
        }
    }
}
