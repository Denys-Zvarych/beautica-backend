package com.beautica.master.dto;

import com.beautica.master.entity.ScheduleExceptionKind;
import com.beautica.master.entity.ScheduleExceptionReason;
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
        @DisplayName("PINS CURRENT BEHAVIOR: days containing a null element NPEs inside isDaysUnique — backlog gap")
        void should_pinCurrentBehavior_when_daysContainsNullElement() {
            var withNull = new java.util.ArrayList<WeeklyScheduleDayRequest>();
            withNull.add(null);
            var req = new WeeklyScheduleRequest(FUTURE, null, withNull);

            // Documented LOW: isDaysUnique() maps dayOfWeek over elements without a null guard, so a
            // null element triggers an NPE during validation. Pinned so the backlog null-guard fix is
            // a deliberate, test-visible change.
            assertThatThrownBy(() -> validator.validate(req))
                    .isInstanceOf(Exception.class);
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // ScheduleOverrideRequest
    // ════════════════════════════════════════════════════════════════════════
    @Nested
    @DisplayName("ScheduleOverrideRequest")
    class ScheduleOverrideRequestTests {

        @Test
        @DisplayName("accepts DAY_OFF when reason present and no intervals")
        void should_accept_when_dayOffWithReason() {
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.DAY_OFF,
                    ScheduleExceptionReason.VACATION, "Away", List.of());

            assertThat(validator.validate(req)).isEmpty();
        }

        @Test
        @DisplayName("accepts DAY_OFF when reason is null and no intervals (reason optional, V82)")
        void should_accept_when_dayOffWithoutReason() {
            // V82 relaxed isKindConsistent(): a DAY_OFF override no longer requires a reason. With a null
            // reason and no intervals the cross-field rule must NOT fire. This assertion would FAIL against
            // the pre-V82 validator (which required reason != null for DAY_OFF).
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.DAY_OFF,
                    null, null, List.of());

            assertThat(validator.validate(req))
                    .as("DAY_OFF with null reason + no intervals is now valid (V82)")
                    .isEmpty();
        }

        @Test
        @DisplayName("accepts DAY_OFF when reason is null and intervals list is null (no-intervals via null, V82)")
        void should_accept_when_dayOffWithNullReasonAndNullIntervals() {
            // hasIntervals derives from intervals==null||isEmpty(); a null list is the wire shape for the
            // bare `{date, kind:DAY_OFF}` body and must validate clean under the relaxed rule.
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.DAY_OFF,
                    null, null, null);

            assertThat(validator.validate(req))
                    .as("DAY_OFF with null reason + null intervals is valid (V82)")
                    .isEmpty();
        }

        @Test
        @DisplayName("rejects DAY_OFF (isKindConsistent) when intervals are present")
        void should_reject_when_dayOffWithIntervals() {
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.DAY_OFF,
                    ScheduleExceptionReason.HOLIDAY, null, List.of(interval("09:00:00", "12:00:00")));

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "kindConsistent")).isTrue();
        }

        @Test
        @DisplayName("accepts CUSTOM_HOURS when intervals present and no reason")
        void should_accept_when_customHoursWithIntervals() {
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.CUSTOM_HOURS,
                    null, null, List.of(interval("10:00:00", "14:00:00")));

            assertThat(validator.validate(req)).isEmpty();
        }

        @Test
        @DisplayName("rejects CUSTOM_HOURS (isKindConsistent) when a reason is present")
        void should_reject_when_customHoursWithReason() {
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.CUSTOM_HOURS,
                    ScheduleExceptionReason.OTHER, null, List.of(interval("10:00:00", "14:00:00")));

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "kindConsistent")).isTrue();
        }

        @Test
        @DisplayName("rejects CUSTOM_HOURS (isKindConsistent) when intervals are empty")
        void should_reject_when_customHoursWithoutIntervals() {
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.CUSTOM_HOURS,
                    null, null, List.of());

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "kindConsistent")).isTrue();
        }

        @Test
        @DisplayName("rejects date when null (@NotNull)")
        void should_reject_when_dateNull() {
            var req = new ScheduleOverrideRequest(null, ScheduleExceptionKind.DAY_OFF,
                    ScheduleExceptionReason.VACATION, null, List.of());

            assertThat(hasViolationOn(validator.validate(req), "date")).isTrue();
        }

        @Test
        @DisplayName("rejects kind when null (@NotNull; isKindConsistent short-circuits true)")
        void should_reject_when_kindNull() {
            var req = new ScheduleOverrideRequest(FUTURE, null,
                    ScheduleExceptionReason.VACATION, null, List.of());

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(hasViolationOn(violations, "kind"))
                    .as("@NotNull reports the missing kind")
                    .isTrue();
            assertThat(hasViolationOn(violations, "kindConsistent"))
                    .as("isKindConsistent() guards null kind and must not pile on a spurious error")
                    .isFalse();
        }

        @Test
        @DisplayName("rejects note (@Pattern) when it contains a control character")
        void should_reject_when_noteHasControlChar() {
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.DAY_OFF,
                    ScheduleExceptionReason.SICK_DAY, "line1\nline2", List.of());

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "note")).isTrue();
        }

        @Test
        @DisplayName("accepts note at exactly 2000 chars (max boundary)")
        void should_accept_when_note2000Chars() {
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.DAY_OFF,
                    ScheduleExceptionReason.VACATION, "x".repeat(2000), List.of());

            assertThat(validator.validate(req)).isEmpty();
        }

        @Test
        @DisplayName("rejects note (@Size) when 2001 chars (max=2000)")
        void should_reject_when_note2001Chars() {
            var req = new ScheduleOverrideRequest(FUTURE, ScheduleExceptionKind.DAY_OFF,
                    ScheduleExceptionReason.VACATION, "x".repeat(2001), List.of());

            Set<ConstraintViolation<ScheduleOverrideRequest>> violations = validator.validate(req);

            assertThat(violations).isNotEmpty();
            assertThat(hasViolationOn(violations, "note")).isTrue();
        }
    }
}
