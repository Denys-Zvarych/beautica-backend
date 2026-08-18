package com.beautica.master.dto;

import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.WeekdayMode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 15.2 — Bean Validation unit coverage for the schedule wire DTOs.
 *
 * <p>Pure Jakarta Bean Validation: no Spring context, no Testcontainers. Pins every {@code @AssertTrue}
 * cross-field rule, the leaf constraints, and the {@code @Valid} cascade. Also pins two KNOWN LOW gaps
 * flagged by security/perf (null {@code days} passes; {@code [null]} day would NPE) so the future
 * backlog fix is a deliberate, test-visible behavior change.
 */
@DisplayName("Schedule DTOs — Bean Validation unit")
class ScheduleDtoValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────
    private static final LocalDate FUTURE = LocalDate.now().plusDays(7);

    private static WorkIntervalDto interval(String start, String end) {
        return new WorkIntervalDto(LocalTime.parse(start), LocalTime.parse(end));
    }

    /** Top-level wall-clock helper for the 15.12 window nests (the older nests carry their own {@code time}). */
    private static LocalTime at(String hhmmss) {
        return LocalTime.parse(hhmmss);
    }

    private static boolean hasViolationOn(Set<? extends ConstraintViolation<?>> v, String path) {
        return v.stream().anyMatch(c -> c.getPropertyPath().toString().equals(path));
    }

    // ════════════════════════════════════════════════════════════════════════
    // WorkIntervalDto
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("WorkIntervalDto")
    class WorkIntervalDtoTests {

        @Test
        @DisplayName("accepts interval when endTime is strictly after startTime")
        void should_accept_when_endAfterStart() {
            assertThat(validator.validate(interval("09:00:00", "17:00:00"))).isEmpty();
        }

        @Test
        @DisplayName("rejects interval (isOrdered) when endTime equals startTime")
        void should_reject_when_endEqualsStart() {
            Set<ConstraintViolation<WorkIntervalDto>> violations =
                    validator.validate(interval("09:00:00", "09:00:00"));

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "ordered"))
                    .as("end==start must trip @AssertTrue isOrdered()")
                    .isTrue();
        }

        @Test
        @DisplayName("rejects interval (isOrdered) when endTime is before startTime")
        void should_reject_when_endBeforeStart() {
            Set<ConstraintViolation<WorkIntervalDto>> violations =
                    validator.validate(interval("17:00:00", "09:00:00"));

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "ordered")).isTrue();
        }

        @Test
        @DisplayName("rejects interval when startTime is null (and isOrdered short-circuits true)")
        void should_reject_when_startNull() {
            var dto = new WorkIntervalDto(null, LocalTime.of(17, 0));

            Set<ConstraintViolation<WorkIntervalDto>> violations = validator.validate(dto);

            assertThat(hasViolationOn(violations, "startTime"))
                    .as("@NotNull on startTime fires")
                    .isTrue();
            assertThat(hasViolationOn(violations, "ordered"))
                    .as("isOrdered() guards null and must NOT report a spurious order error")
                    .isFalse();
        }

        @Test
        @DisplayName("rejects interval when endTime is null")
        void should_reject_when_endNull() {
            var dto = new WorkIntervalDto(LocalTime.of(9, 0), null);

            Set<ConstraintViolation<WorkIntervalDto>> violations = validator.validate(dto);

            assertThat(hasViolationOn(violations, "endTime")).isTrue();
            assertThat(hasViolationOn(violations, "ordered")).isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // WeeklyScheduleDayRequest
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("WeeklyScheduleDayRequest")
    class WeeklyScheduleDayRequestTests {

        @ParameterizedTest(name = "dayOfWeek={0} rejected")
        @ValueSource(ints = {0, 8})
        @DisplayName("rejects dayOfWeek when out of ISO [1,7] range")
        void should_reject_when_dayOfWeekOutOfRange(int day) {
            var req = new WeeklyScheduleDayRequest(day, List.of());

            Set<ConstraintViolation<WeeklyScheduleDayRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "dayOfWeek")).isTrue();
        }

        @ParameterizedTest(name = "dayOfWeek={0} valid")
        @ValueSource(ints = {1, 7})
        @DisplayName("accepts dayOfWeek at ISO boundaries 1 (Mon) and 7 (Sun)")
        void should_accept_when_dayOfWeekAtBoundary(int day) {
            var req = new WeeklyScheduleDayRequest(day, List.of(interval("09:00:00", "12:00:00")));

            assertThat(validator.validate(req)).isEmpty();
        }

        @Test
        @DisplayName("accepts empty intervals (the day-off representation)")
        void should_accept_when_intervalsEmpty() {
            var req = new WeeklyScheduleDayRequest(3, List.of());

            assertThat(validator.validate(req)).isEmpty();
        }

        @Test
        @DisplayName("rejects intervals (@Size) when more than 6 intervals supplied")
        void should_reject_when_moreThanSixIntervals() {
            var seven = List.of(
                    interval("08:00:00", "08:30:00"), interval("09:00:00", "09:30:00"),
                    interval("10:00:00", "10:30:00"), interval("11:00:00", "11:30:00"),
                    interval("12:00:00", "12:30:00"), interval("13:00:00", "13:30:00"),
                    interval("14:00:00", "14:30:00"));
            var req = new WeeklyScheduleDayRequest(1, seven);

            Set<ConstraintViolation<WeeklyScheduleDayRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "intervals")).isTrue();
        }

        @Test
        @DisplayName("cascades (@Valid) — a malformed nested interval fails the parent day")
        void should_cascade_when_nestedIntervalInvalid() {
            var badNested = new WeeklyScheduleDayRequest(1, List.of(interval("17:00:00", "09:00:00")));

            Set<ConstraintViolation<WeeklyScheduleDayRequest>> violations = validator.validate(badNested);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "intervals[0].ordered"))
                    .as("@Valid must cascade into the nested WorkIntervalDto isOrdered() rule")
                    .isTrue();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // WeeklyScheduleDayRequest — Phase 15.8 mode exclusivity (isModeConsistent) + @Size(times)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("WeeklyScheduleDayRequest — EXPLICIT_TIMES mode (15.8)")
    class WeeklyScheduleDayRequestExplicitTimes {

        private static LocalTime time(String hhmmss) {
            return LocalTime.parse(hhmmss);
        }

        private WeeklyScheduleDayRequest day(WeekdayMode mode, List<WorkIntervalDto> intervals,
                                             List<LocalTime> times) {
            return new WeeklyScheduleDayRequest(1, mode, intervals, times);
        }

        @Test
        @DisplayName("accepts an EXPLICIT_TIMES day with a non-empty times list and no intervals")
        void should_accept_when_explicitTimesWithTimesOnly() {
            var req = day(WeekdayMode.EXPLICIT_TIMES, null,
                    List.of(time("09:00:00"), time("11:00:00")));

            assertThat(validator.validate(req))
                    .as("EXPLICIT_TIMES carrying only times is the canonical valid shape").isEmpty();
        }

        @Test
        @DisplayName("rejects (isModeConsistent) an INTERVAL day that also carries discrete times")
        void should_reject_when_intervalDayCarriesTimes() {
            var req = day(WeekdayMode.INTERVAL,
                    List.of(interval("09:00:00", "17:00:00")),
                    List.of(time("10:00:00")));

            Set<ConstraintViolation<WeeklyScheduleDayRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "modeConsistent"))
                    .as("an INTERVAL day carrying times must trip the mode-exclusivity rule").isTrue();
        }

        @Test
        @DisplayName("rejects (isModeConsistent) an EXPLICIT_TIMES day that also carries intervals")
        void should_reject_when_explicitTimesDayCarriesIntervals() {
            var req = day(WeekdayMode.EXPLICIT_TIMES,
                    List.of(interval("09:00:00", "17:00:00")),
                    List.of(time("10:00:00")));

            Set<ConstraintViolation<WeeklyScheduleDayRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "modeConsistent"))
                    .as("an EXPLICIT_TIMES day carrying intervals must trip the mode-exclusivity rule")
                    .isTrue();
        }

        @Test
        @DisplayName("rejects (isModeConsistent) an EXPLICIT_TIMES day with an empty times list")
        void should_reject_when_explicitTimesEmpty() {
            var req = day(WeekdayMode.EXPLICIT_TIMES, null, List.of());

            Set<ConstraintViolation<WeeklyScheduleDayRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "modeConsistent"))
                    .as("an EXPLICIT_TIMES day must carry a non-empty times list").isTrue();
        }

        @Test
        @DisplayName("rejects (isModeConsistent) an EXPLICIT_TIMES day with null times")
        void should_reject_when_explicitTimesNull() {
            var req = day(WeekdayMode.EXPLICIT_TIMES, null, null);

            Set<ConstraintViolation<WeeklyScheduleDayRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "modeConsistent"))
                    .as("a null times list is not a valid EXPLICIT_TIMES day").isTrue();
        }

        @Test
        @DisplayName("rejects (isModeConsistent) an EXPLICIT_TIMES day that carries interval breaks (no breaks allowed)")
        void should_reject_when_explicitTimesCarriesBreaks() {
            // "breaks" are an interval-only affordance — modelled here as a second interval window. An
            // EXPLICIT_TIMES day must not carry ANY intervals (breaks included), so this trips the rule.
            var req = day(WeekdayMode.EXPLICIT_TIMES,
                    List.of(interval("09:00:00", "12:00:00"), interval("13:00:00", "17:00:00")),
                    List.of(time("10:00:00")));

            Set<ConstraintViolation<WeeklyScheduleDayRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "modeConsistent"))
                    .as("breaks (intervals) on an EXPLICIT_TIMES day must trip the mode-exclusivity rule")
                    .isTrue();
        }

        @Test
        @DisplayName("accepts exactly 24 discrete times (the @Size upper boundary)")
        void should_accept_when_exactly24Times() {
            // 24 distinct times on the hour: 00:00 .. 23:00.
            List<LocalTime> twentyFour = java.util.stream.IntStream.range(0, 24)
                    .mapToObj(h -> LocalTime.of(h, 0)).toList();
            var req = day(WeekdayMode.EXPLICIT_TIMES, null, twentyFour);

            assertThat(validator.validate(req))
                    .as("24 times is exactly at the @Size(max=24) cap and must pass").isEmpty();
        }

        @Test
        @DisplayName("rejects times (@Size) when more than 24 discrete times supplied")
        void should_reject_when_moreThan24Times() {
            // 25 distinct times: 00:00 .. 23:00 plus 23:30.
            List<LocalTime> twentyFive = new java.util.ArrayList<>(java.util.stream.IntStream.range(0, 24)
                    .mapToObj(h -> LocalTime.of(h, 0)).toList());
            twentyFive.add(LocalTime.of(23, 30));
            var req = day(WeekdayMode.EXPLICIT_TIMES, null, twentyFive);

            Set<ConstraintViolation<WeeklyScheduleDayRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "times"))
                    .as("@Size(max=24) on times must fire for 25 entries").isTrue();
        }

        @Test
        @DisplayName("rejects times when an element is null (@NotNull on the list element)")
        void should_reject_when_nullTimeElement() {
            var withNull = new java.util.ArrayList<LocalTime>();
            withNull.add(time("09:00:00"));
            withNull.add(null);
            var req = day(WeekdayMode.EXPLICIT_TIMES, null, withNull);

            Set<ConstraintViolation<WeeklyScheduleDayRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(violations.stream().anyMatch(c -> c.getPropertyPath().toString().startsWith("times")))
                    .as("a null discrete-time element must trip the @NotNull list-element constraint").isTrue();
        }

        @Test
        @DisplayName("null mode defaults to INTERVAL — a day with intervals only validates clean (back-compat)")
        void should_defaultToInterval_when_modeNull() {
            var req = new WeeklyScheduleDayRequest(1, null,
                    List.of(interval("09:00:00", "17:00:00")), null);

            assertThat(validator.validate(req))
                    .as("a pre-15.8 (null-mode) interval day is INTERVAL and validates clean").isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // WeeklyScheduleRequest
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("WeeklyScheduleRequest")
    class WeeklyScheduleRequestTests {

        private WeeklyScheduleDayRequest day(int d) {
            return new WeeklyScheduleDayRequest(d, List.of(interval("09:00:00", "17:00:00")));
        }

        @Test
        @DisplayName("accepts a fully valid request (clean baseline)")
        void should_accept_when_fullyValid() {
            var req = new WeeklyScheduleRequest(FUTURE, FUTURE.plusMonths(1),
                    List.of(day(1), day(2), day(3)));

            assertThat(validator.validate(req)).isEmpty();
        }

        @Test
        @DisplayName("rejects validFrom when null (@NotNull)")
        void should_reject_when_validFromNull() {
            var req = new WeeklyScheduleRequest(null, null, List.of(day(1)));

            Set<ConstraintViolation<WeeklyScheduleRequest>> violations = validator.validate(req);

            assertThat(hasViolationOn(violations, "validFrom")).isTrue();
        }

        @Test
        @DisplayName("rejects validFrom when in the past (@FutureOrPresent)")
        void should_reject_when_validFromPast() {
            var req = new WeeklyScheduleRequest(LocalDate.now().minusDays(1), null, List.of(day(1)));

            Set<ConstraintViolation<WeeklyScheduleRequest>> violations = validator.validate(req);

            assertThat(hasViolationOn(violations, "validFrom")).isTrue();
        }

        @Test
        @DisplayName("rejects window (isWindowOrdered) when validTo is before validFrom")
        void should_reject_when_validToBeforeValidFrom() {
            var req = new WeeklyScheduleRequest(FUTURE, FUTURE.minusDays(1), List.of(day(1)));

            Set<ConstraintViolation<WeeklyScheduleRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "windowOrdered")).isTrue();
        }

        @Test
        @DisplayName("accepts single-day window when validTo equals validFrom")
        void should_accept_when_validToEqualsValidFrom() {
            var req = new WeeklyScheduleRequest(FUTURE, FUTURE, List.of(day(1)));

            assertThat(validator.validate(req)).isEmpty();
        }

        @Test
        @DisplayName("accepts open-ended window when validTo is null")
        void should_accept_when_validToNull() {
            var req = new WeeklyScheduleRequest(FUTURE, null, List.of(day(1)));

            assertThat(validator.validate(req)).isEmpty();
        }

        @Test
        @DisplayName("rejects days (isDaysUnique) when a weekday is duplicated")
        void should_reject_when_duplicateWeekday() {
            var req = new WeeklyScheduleRequest(FUTURE, null, List.of(day(2), day(2)));

            Set<ConstraintViolation<WeeklyScheduleRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "daysUnique")).isTrue();
        }

        @Test
        @DisplayName("rejects days (@Size) when more than 7 days supplied")
        void should_reject_when_moreThanSevenDays() {
            // 8 entries, distinct dayOfWeek values to isolate the @Size failure from isDaysUnique
            var eight = List.of(day(1), day(2), day(3), day(4), day(5), day(6), day(7),
                    new WeeklyScheduleDayRequest(1, List.of(interval("08:00:00", "08:30:00"))));
            var req = new WeeklyScheduleRequest(FUTURE, null, eight);

            Set<ConstraintViolation<WeeklyScheduleRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "days")).isTrue();
        }

        @Test
        @DisplayName("binds the leap day 2024-02-29 as a valid LocalDate (no LocalDate parse error)")
        void should_bind_when_leapDay() {
            // 2024-02-29 is a real date; constructing it must not throw, and a future window passes.
            LocalDate leap = LocalDate.of(2024, 2, 29);
            assertThat(leap.getDayOfMonth()).isEqualTo(29);

            // 2023-02-29 is NOT a real date — LocalDate.of rejects it at the boundary (Jackson maps this to a 400).
            assertThatThrownBy(() -> LocalDate.of(2023, 2, 29))
                    .isInstanceOf(java.time.DateTimeException.class);
        }

        // ── KNOWN LOW GAP (backlog) — pin CURRENT behavior, do NOT change prod code ──

        @Test
        @DisplayName("PINS CURRENT BEHAVIOR: null days currently PASSES (no @NotNull/@NotEmpty) — backlog gap")
        void should_pinCurrentBehavior_when_daysNull() {
            var req = new WeeklyScheduleRequest(FUTURE, null, null);

            // Documented LOW: `days` lacks @NotNull/@NotEmpty, so an omitted/null list validates clean.
            // When the backlog fix lands, this assertion flips and the change becomes test-visible.
            assertThat(validator.validate(req))
                    .as("null days currently produces no violation (known LOW gap)")
                    .isEmpty();
        }

        @Test
        @DisplayName("rejects days when an element is null — a violation, NOT an NPE inside isDaysUnique")
        void should_reject_when_daysContainsNullElement() {
            var withNull = new java.util.ArrayList<WeeklyScheduleDayRequest>();
            withNull.add(day(1));
            withNull.add(null);
            var req = new WeeklyScheduleRequest(FUTURE, null, withNull);

            Set<ConstraintViolation<WeeklyScheduleRequest>> violations = validator.validate(req);

            // This previously PINNED THE BUG: isDaysUnique() mapped dayOfWeek over every element with no
            // null guard, so validation itself threw. Hibernate Validator wraps an exception escaping an
            // @AssertTrue getter in a ValidationException — a 500 raised before GlobalExceptionHandler
            // could render a 400. Both halves of the fix are load-bearing and are asserted here: the
            // element-level @NotNull produces the violation, and isDaysUnique()'s Objects::nonNull filter
            // stops the getter (which runs regardless of sibling constraint order) from throwing first.
            assertThat(violations)
                    .as("a null day element must be a Bean Validation rejection (400), never an NPE")
                    .isNotEmpty();
            assertThat(violations.stream().anyMatch(c -> c.getPropertyPath().toString().startsWith("days")))
                    .as("the violation must be reported on the days path").isTrue();
            assertThat(violations.stream().anyMatch(c -> c.getPropertyPath().toString().equals("daysUnique")))
                    .as("uniqueness is not the complaint — the surviving element list has no duplicate")
                    .isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ScheduleOverrideRequest
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("ScheduleOverrideRequest")
    class ScheduleOverrideRequestTests {

        // V83 removed reason + note entirely. The wire shape is now (date, kind, intervals) and
        // isKindConsistent() pins exactly: DAY_OFF -> no intervals; CUSTOM_HOURS -> has intervals.

        @Test
        @DisplayName("accepts DAY_OFF when intervals empty (V83 — no reason/note exist)")
        void should_accept_when_dayOffWithNoIntervals() {
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.DAY_OFF, List.of());

            assertThat(validator.validate(req))
                    .as("DAY_OFF with an empty interval list is valid")
                    .isEmpty();
        }

        @Test
        @DisplayName("accepts DAY_OFF when intervals list is null (bare {date, kind:DAY_OFF} body)")
        void should_accept_when_dayOffWithNullIntervals() {
            // hasIntervals derives from intervals==null||isEmpty(); a null list is the wire shape for the
            // bare `{date, kind:DAY_OFF}` body and must validate clean.
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.DAY_OFF, null);

            assertThat(validator.validate(req))
                    .as("DAY_OFF with a null interval list is valid")
                    .isEmpty();
        }

        @Test
        @DisplayName("rejects DAY_OFF (isKindConsistent) when intervals are present")
        void should_reject_when_dayOffWithIntervals() {
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.DAY_OFF,
                    List.of(interval("09:00:00", "12:00:00")));

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "kindConsistent"))
                    .as("a DAY_OFF carrying intervals must trip the cross-field rule")
                    .isTrue();
        }

        @Test
        @DisplayName("accepts CUSTOM_HOURS when intervals present")
        void should_accept_when_customHoursWithIntervals() {
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.CUSTOM_HOURS,
                    List.of(interval("10:00:00", "14:00:00")));

            assertThat(validator.validate(req)).isEmpty();
        }

        @Test
        @DisplayName("rejects CUSTOM_HOURS (isKindConsistent) when intervals are empty")
        void should_reject_when_customHoursWithEmptyIntervals() {
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.CUSTOM_HOURS, List.of());

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "kindConsistent"))
                    .as("CUSTOM_HOURS with an empty interval list must trip the cross-field rule")
                    .isTrue();
        }

        @Test
        @DisplayName("rejects CUSTOM_HOURS (isKindConsistent) when intervals list is null")
        void should_reject_when_customHoursWithNullIntervals() {
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.CUSTOM_HOURS, null);

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "kindConsistent"))
                    .as("CUSTOM_HOURS with a null interval list must trip the cross-field rule")
                    .isTrue();
        }

        @Test
        @DisplayName("rejects intervals (@Size) when CUSTOM_HOURS supplies more than 6 intervals")
        void should_reject_when_moreThanSixIntervals() {
            var seven = List.of(
                    interval("08:00:00", "08:30:00"), interval("09:00:00", "09:30:00"),
                    interval("10:00:00", "10:30:00"), interval("11:00:00", "11:30:00"),
                    interval("12:00:00", "12:30:00"), interval("13:00:00", "13:30:00"),
                    interval("14:00:00", "14:30:00"));
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.CUSTOM_HOURS, seven);

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "intervals"))
                    .as("@Size(max=6) on intervals must fire")
                    .isTrue();
        }

        @Test
        @DisplayName("cascades (@Valid) — a malformed nested interval fails the parent override")
        void should_cascade_when_nestedIntervalInvalid() {
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.CUSTOM_HOURS,
                    List.of(interval("17:00:00", "09:00:00")));

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(hasViolationOn(violations, "intervals[0].ordered"))
                    .as("@Valid must cascade into the nested WorkIntervalDto isOrdered() rule")
                    .isTrue();
        }

        @Test
        @DisplayName("rejects date when null (@NotNull)")
        void should_reject_when_dateNull() {
            var req = new ScheduleOverrideRequest(null, ScheduleExceptionKind.DAY_OFF, List.of());

            assertThat(hasViolationOn(validator.validate(req), "date")).isTrue();
        }

        @Test
        @DisplayName("rejects kind when null (@NotNull; isKindConsistent short-circuits true)")
        void should_reject_when_kindNull() {
            var req = new ScheduleOverrideRequest(FUTURE, null, List.of());

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(hasViolationOn(violations, "kind"))
                    .as("@NotNull reports the missing kind")
                    .isTrue();
            assertThat(hasViolationOn(violations, "kindConsistent"))
                    .as("isKindConsistent() guards null kind and must not pile on a spurious error")
                    .isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ScheduleOverrideRequest — Phase 15.9 EXPLICIT_TIMES mode exclusivity + @Size(times)
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("ScheduleOverrideRequest — EXPLICIT_TIMES mode (15.9)")
    class ScheduleOverrideRequestExplicitTimes {

        private static LocalTime time(String hhmmss) {
            return LocalTime.parse(hhmmss);
        }

        /** Full 5-arg override request: (date, kind, mode, intervals, times). */
        private ScheduleOverrideRequest override(ScheduleExceptionKind kind, WeekdayMode mode,
                                                 List<WorkIntervalDto> intervals, List<LocalTime> times) {
            return new ScheduleOverrideRequest(FUTURE, kind, mode, intervals, times);
        }

        @Test
        @DisplayName("accepts a CUSTOM_HOURS EXPLICIT_TIMES override with a non-empty times list and no intervals")
        void should_accept_when_explicitTimesWithTimesOnly() {
            var req = override(ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.EXPLICIT_TIMES,
                    null, List.of(time("09:00:00"), time("11:00:00")));

            assertThat(validator.validate(req))
                    .as("EXPLICIT_TIMES carrying only times is the canonical valid override shape").isEmpty();
        }

        @Test
        @DisplayName("rejects (isKindConsistent) a CUSTOM_HOURS INTERVAL override that also carries discrete times")
        void should_reject_when_intervalOverrideCarriesTimes() {
            var req = override(ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL,
                    List.of(interval("09:00:00", "17:00:00")), List.of(time("10:00:00")));

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "kindConsistent"))
                    .as("an INTERVAL override carrying times must trip the mode-exclusivity rule").isTrue();
        }

        @Test
        @DisplayName("rejects (isKindConsistent) a CUSTOM_HOURS EXPLICIT_TIMES override that also carries intervals")
        void should_reject_when_explicitTimesOverrideCarriesIntervals() {
            var req = override(ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.EXPLICIT_TIMES,
                    List.of(interval("09:00:00", "17:00:00")), List.of(time("10:00:00")));

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "kindConsistent"))
                    .as("an EXPLICIT_TIMES override carrying intervals must trip the mode-exclusivity rule")
                    .isTrue();
        }

        @Test
        @DisplayName("rejects (isKindConsistent) a CUSTOM_HOURS EXPLICIT_TIMES override with an empty times list")
        void should_reject_when_explicitTimesOverrideEmpty() {
            var req = override(ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.EXPLICIT_TIMES,
                    null, List.of());

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "kindConsistent"))
                    .as("an EXPLICIT_TIMES override must carry a non-empty times list").isTrue();
        }

        @Test
        @DisplayName("rejects (isKindConsistent) a CUSTOM_HOURS EXPLICIT_TIMES override with null times")
        void should_reject_when_explicitTimesOverrideNull() {
            var req = override(ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.EXPLICIT_TIMES, null, null);

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "kindConsistent"))
                    .as("a null times list is not a valid EXPLICIT_TIMES override").isTrue();
        }

        @Test
        @DisplayName("rejects (isKindConsistent) a DAY_OFF override that carries discrete times")
        void should_reject_when_dayOffOverrideCarriesTimes() {
            var req = override(ScheduleExceptionKind.DAY_OFF, WeekdayMode.INTERVAL,
                    null, List.of(time("10:00:00")));

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "kindConsistent"))
                    .as("a DAY_OFF carrying discrete times must trip the cross-field rule").isTrue();
        }

        @Test
        @DisplayName("accepts exactly 24 discrete times (the @Size upper boundary)")
        void should_accept_when_exactly24Times() {
            List<LocalTime> twentyFour = java.util.stream.IntStream.range(0, 24)
                    .mapToObj(h -> LocalTime.of(h, 0)).toList();
            var req = override(ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.EXPLICIT_TIMES,
                    null, twentyFour);

            assertThat(validator.validate(req))
                    .as("24 override times is exactly at the @Size(max=24) cap and must pass").isEmpty();
        }

        @Test
        @DisplayName("rejects times (@Size) when more than 24 discrete times supplied")
        void should_reject_when_moreThan24Times() {
            List<LocalTime> twentyFive = new java.util.ArrayList<>(java.util.stream.IntStream.range(0, 24)
                    .mapToObj(h -> LocalTime.of(h, 0)).toList());
            twentyFive.add(LocalTime.of(23, 30));
            var req = override(ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.EXPLICIT_TIMES,
                    null, twentyFive);

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "times"))
                    .as("@Size(max=24) on override times must fire for 25 entries").isTrue();
        }

        @Test
        @DisplayName("rejects times when an element is null (@NotNull on the list element)")
        void should_reject_when_nullTimeElement() {
            var withNull = new java.util.ArrayList<LocalTime>();
            withNull.add(time("09:00:00"));
            withNull.add(null);
            var req = override(ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.EXPLICIT_TIMES, null, withNull);

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(violations.stream().anyMatch(c -> c.getPropertyPath().toString().startsWith("times")))
                    .as("a null discrete-time element must trip the @NotNull list-element constraint").isTrue();
        }

        @Test
        @DisplayName("null mode defaults to INTERVAL — the 3-arg convenience override validates clean (back-compat)")
        void should_defaultToInterval_when_modeNull() {
            // The pre-15.9 3-arg constructor sets mode=INTERVAL and times=null.
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.CUSTOM_HOURS,
                    List.of(interval("09:00:00", "17:00:00")));

            assertThat(validator.validate(req))
                    .as("a pre-15.9 (INTERVAL) override validates clean").isEmpty();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // OverrideConflictQueryRequest — the conflict-preview twin of ScheduleOverrideRequest
    // ════════════════════════════════════════════════════════════════════════

    /**
     * This record has NO service-layer backstop for a malformed interval list:
     * {@code ScheduleOverrideConflictService#previewConflicts} hands {@code intervals} straight to
     * {@code ScheduleConflictCalculator}, never through
     * {@code MasterScheduleService#assertIntervalsNonOverlapping}. Bean Validation is the only gate, so
     * the element-level constraint has to hold here on its own.
     */
    @Nested
    @DisplayName("OverrideConflictQueryRequest")
    class OverrideConflictQueryRequestTests {

        private OverrideConflictQueryRequest query(List<WorkIntervalDto> intervals) {
            return new OverrideConflictQueryRequest(FUTURE, FUTURE.plusDays(3),
                    ScheduleExceptionKind.CUSTOM_HOURS, WeekdayMode.INTERVAL, intervals, null);
        }

        @Test
        @DisplayName("accepts a well-formed CUSTOM_HOURS preview query (clean baseline)")
        void should_accept_when_intervalsWellFormed() {
            assertThat(validator.validate(query(List.of(interval("09:00:00", "17:00:00"))))).isEmpty();
        }

        @Test
        @DisplayName("rejects intervals when an element is null (@NotNull on the list element)")
        void should_reject_when_intervalListContainsNullElement() {
            // isKindConsistent only asks whether the list is NON-EMPTY, so `[null]` satisfies it; without
            // the element-level @NotNull this reaches fullyCovers and NPEs → 500 for an authenticated
            // master POSTing /masters/{id}/overrides/conflicts.
            var withNull = new java.util.ArrayList<WorkIntervalDto>();
            withNull.add(interval("09:00:00", "17:00:00"));
            withNull.add(null);

            Set<ConstraintViolation<OverrideConflictQueryRequest>> violations = validator.validate(query(withNull));

            assertThat(violations)
                    .as("a null interval element must be a Bean Validation rejection (400), never an NPE")
                    .isNotEmpty();
            assertThat(violations.stream()
                    .anyMatch(c -> c.getPropertyPath().toString().startsWith("intervals")))
                    .as("the violation must be reported on the intervals path").isTrue();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // Phase 15.12 — optional working-window bounds (windowStart / windowEnd)
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Shape rules only ({@code isWindowConsistent}): both-or-neither + strict ordering. Containment
     * (window ⊇ every interval) needs the interval list AND the resolved mode, so it is a service-layer
     * rule — pinned in {@code MasterScheduleWorkingWindowIT}.
     */
    @Nested
    @DisplayName("Working-window bounds (15.12) — WeeklyScheduleDayRequest")
    class WeeklyDayWindowBounds {

        private WeeklyScheduleDayRequest dayWithWindow(LocalTime windowStart, LocalTime windowEnd) {
            return new WeeklyScheduleDayRequest(1, WeekdayMode.INTERVAL,
                    List.of(interval("10:00:00", "18:00:00")), null, windowStart, windowEnd);
        }

        @Test
        @DisplayName("accepts a day with no window at all — the legacy shape stays valid")
        void should_accept_when_windowOmitted() {
            assertThat(validator.validate(dayWithWindow(null, null)))
                    .as("both bounds null is the pre-15.12 wire shape and must keep validating clean")
                    .isEmpty();
        }

        @Test
        @DisplayName("accepts a well-formed window that is wider than the intervals")
        void should_accept_when_windowOrderedAndPresent() {
            assertThat(validator.validate(dayWithWindow(at("09:00:00"), at("18:00:00")))).isEmpty();
        }

        @Test
        @DisplayName("rejects (isWindowConsistent) when only windowStart is supplied")
        void should_reject_when_onlyWindowStartSupplied() {
            Set<ConstraintViolation<WeeklyScheduleDayRequest>> violations =
                    validator.validate(dayWithWindow(at("09:00:00"), null));

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "windowConsistent"))
                    .as("a half-specified window must trip @AssertTrue isWindowConsistent()").isTrue();
        }

        @Test
        @DisplayName("rejects (isWindowConsistent) when only windowEnd is supplied")
        void should_reject_when_onlyWindowEndSupplied() {
            Set<ConstraintViolation<WeeklyScheduleDayRequest>> violations =
                    validator.validate(dayWithWindow(null, at("18:00:00")));

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "windowConsistent")).isTrue();
        }

        @Test
        @DisplayName("rejects (isWindowConsistent) a zero-length window")
        void should_reject_when_windowEndEqualsStart() {
            Set<ConstraintViolation<WeeklyScheduleDayRequest>> violations =
                    validator.validate(dayWithWindow(at("09:00:00"), at("09:00:00")));

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "windowConsistent")).isTrue();
        }

        @Test
        @DisplayName("rejects (isWindowConsistent) a cross-midnight window — no wraparound, ever")
        void should_reject_when_windowWrapsPastMidnight() {
            // 22:00 → 06:00 is exactly the night-shift shape the locked Phase 15.x contract forbids:
            // it must be modelled as two ISO-weekday rows, never one wrapping window.
            Set<ConstraintViolation<WeeklyScheduleDayRequest>> violations =
                    validator.validate(dayWithWindow(at("22:00:00"), at("06:00:00")));

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "windowConsistent")).isTrue();
        }

        @Test
        @DisplayName("rejects intervals when an element is null (@NotNull on the list element)")
        void should_reject_when_intervalListContainsNullElement() {
            // Hibernate Validator's @Valid cascade SKIPS null elements, so without the element-level
            // @NotNull this payload validates clean and then NPEs in the service → 500 instead of 400.
            var withNull = new java.util.ArrayList<WorkIntervalDto>();
            withNull.add(interval("09:00:00", "17:00:00"));
            withNull.add(null);
            var day = new WeeklyScheduleDayRequest(1, WeekdayMode.INTERVAL, withNull, null, null, null);

            Set<ConstraintViolation<WeeklyScheduleDayRequest>> violations = validator.validate(day);

            assertThat(violations)
                    .as("a null interval element must be a Bean Validation rejection (400), never an NPE")
                    .isNotEmpty();
            assertThat(violations.stream()
                    .anyMatch(c -> c.getPropertyPath().toString().startsWith("intervals")))
                    .as("the violation must be reported on the intervals path").isTrue();
        }

        @Test
        @DisplayName("the pre-15.12 4-arg convenience constructor yields a null window (back-compat)")
        void should_yieldNullWindow_when_preWindowConstructorUsed() {
            var day = new WeeklyScheduleDayRequest(1, WeekdayMode.INTERVAL,
                    List.of(interval("09:00:00", "17:00:00")), null);

            assertThat(day.windowStart()).isNull();
            assertThat(day.windowEnd()).isNull();
            assertThat(validator.validate(day)).isEmpty();
        }
    }

    @Nested
    @DisplayName("Working-window bounds (15.12) — ScheduleOverrideRequest")
    class OverrideWindowBounds {

        private ScheduleOverrideRequest overrideWithWindow(LocalTime windowStart, LocalTime windowEnd) {
            return new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.CUSTOM_HOURS,
                    WeekdayMode.INTERVAL, List.of(interval("10:00:00", "18:00:00")), null,
                    false, windowStart, windowEnd);
        }

        @Test
        @DisplayName("accepts an override with no window at all — the legacy shape stays valid")
        void should_accept_when_windowOmitted() {
            assertThat(validator.validate(overrideWithWindow(null, null))).isEmpty();
        }

        @Test
        @DisplayName("accepts a well-formed override window that is wider than the intervals")
        void should_accept_when_windowOrderedAndPresent() {
            assertThat(validator.validate(overrideWithWindow(at("09:00:00"), at("18:00:00")))).isEmpty();
        }

        @Test
        @DisplayName("rejects (isWindowConsistent) when only one bound is supplied")
        void should_reject_when_onlyOneBoundSupplied() {
            Set<ConstraintViolation<ScheduleOverrideRequest>> violations =
                    validator.validate(overrideWithWindow(null, at("18:00:00")));

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "windowConsistent"))
                    .as("both editor surfaces must reject a half-specified window identically").isTrue();
        }

        @Test
        @DisplayName("rejects (isWindowConsistent) a cross-midnight override window")
        void should_reject_when_windowWrapsPastMidnight() {
            Set<ConstraintViolation<ScheduleOverrideRequest>> violations =
                    validator.validate(overrideWithWindow(at("22:00:00"), at("06:00:00")));

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "windowConsistent")).isTrue();
        }

        @Test
        @DisplayName("rejects intervals when an element is null (@NotNull on the list element)")
        void should_reject_when_intervalListContainsNullElement() {
            var withNull = new java.util.ArrayList<WorkIntervalDto>();
            withNull.add(interval("09:00:00", "17:00:00"));
            withNull.add(null);
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.CUSTOM_HOURS,
                    WeekdayMode.INTERVAL, withNull, null, false, null, null);

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations)
                    .as("a null interval element must be a Bean Validation rejection (400), never an NPE")
                    .isNotEmpty();
            assertThat(violations.stream()
                    .anyMatch(c -> c.getPropertyPath().toString().startsWith("intervals")))
                    .as("the violation must be reported on the intervals path").isTrue();
        }

        @Test
        @DisplayName("the pre-15.12 6-arg convenience constructor yields a null window (back-compat)")
        void should_yieldNullWindow_when_preWindowConstructorUsed() {
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.CUSTOM_HOURS,
                    WeekdayMode.INTERVAL, List.of(interval("09:00:00", "17:00:00")), null, true);

            assertThat(req.windowStart()).isNull();
            assertThat(req.windowEnd()).isNull();
            assertThat(req.cancelOverlapping()).isTrue();
            assertThat(validator.validate(req)).isEmpty();
        }
    }
}
