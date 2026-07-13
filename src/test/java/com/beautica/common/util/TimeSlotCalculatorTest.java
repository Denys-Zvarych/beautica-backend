package com.beautica.common.util;

import com.beautica.common.util.TimeSlotCalculator.TimeRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("TimeSlotCalculator — unit")
class TimeSlotCalculatorTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final LocalDate TEST_DATE = LocalDate.of(2026, 5, 7);

    private TimeSlotCalculator calculator;

    // ── helpers ────────────────────────────────────────────────────────────────

    private static Clock fixedClock(String utcInstant) {
        return Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC);
    }

    private static TimeRange kyivRange(LocalDate date, LocalTime start, LocalTime end) {
        Instant s = date.atTime(start).atZone(KYIV).toInstant();
        Instant e = date.atTime(end).atZone(KYIV).toInstant();
        return new TimeRange(s, e);
    }

    private static LocalTime localStart(TimeRange range) {
        return range.start().atZone(KYIV).toLocalTime();
    }

    // ── tests ──────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("returns all slots when there are no existing bookings")
    void should_returnAllSlots_when_noExistingBookings() {
        // 05:45Z = 08:45 Kyiv (+03). The bookable cutoff is now + BookingWindow.MIN_MINUTES_AHEAD (15),
        // i.e. exactly 09:00 Kyiv — so the 09:00 slot is at (not before) the cutoff and is kept.
        calculator = new TimeSlotCalculator(fixedClock("2026-05-07T05:45:00Z"));

        List<TimeRange> result = calculator.calculateAvailableSlots(
                TEST_DATE,
                LocalTime.of(9, 0),
                LocalTime.of(13, 0),
                Duration.ofHours(1),
                Duration.ofMinutes(30),
                List.of()
        );

        assertThat(result).hasSize(7);
        assertThat(result.stream().map(TimeSlotCalculatorTest::localStart)).containsExactly(
                LocalTime.of(9, 0),
                LocalTime.of(9, 30),
                LocalTime.of(10, 0),
                LocalTime.of(10, 30),
                LocalTime.of(11, 0),
                LocalTime.of(11, 30),
                LocalTime.of(12, 0)
        );
    }

    @Test
    @DisplayName("excludes slots that overlap an existing booking")
    void should_excludeSlotOverlappingBooking_when_bookingExists() {
        // 05:45Z = 08:45 Kyiv (+03) — the 15-min bookable cutoff lands exactly on 09:00, so the first
        // slot is still generated and only the booking overlap is under test here.
        calculator = new TimeSlotCalculator(fixedClock("2026-05-07T05:45:00Z"));

        TimeRange booking = kyivRange(TEST_DATE, LocalTime.of(10, 0), LocalTime.of(11, 0));

        List<TimeRange> result = calculator.calculateAvailableSlots(
                TEST_DATE,
                LocalTime.of(9, 0),
                LocalTime.of(13, 0),
                Duration.ofHours(1),
                Duration.ofMinutes(30),
                List.of(booking)
        );

        // booking 10:00–11:00; service duration 1h:
        //   09:00–10:00 → no overlap → included
        //   09:30–10:30 → overlaps booking → excluded
        //   10:00–11:00 → occupied → excluded
        //   10:30–11:30 → overlaps booking → excluded
        //   11:00–12:00, 11:30–12:30, 12:00–13:00 → no overlap → included
        List<LocalTime> starts = result.stream().map(TimeSlotCalculatorTest::localStart).toList();
        assertThat(starts).doesNotContain(LocalTime.of(9, 30));
        assertThat(starts).doesNotContain(LocalTime.of(10, 0));
        assertThat(starts).doesNotContain(LocalTime.of(10, 30));
        assertThat(starts).contains(
                LocalTime.of(9, 0),
                LocalTime.of(11, 0),
                LocalTime.of(11, 30),
                LocalTime.of(12, 0)
        );
    }

    @Test
    @DisplayName("returns empty list when clock is past the entire work window")
    void should_returnEmpty_when_allSlotsInPast() {
        // 2026-05-07T21:00:00Z = 00:00 Kyiv next day (+03) — well past 17:00 Kyiv window
        calculator = new TimeSlotCalculator(fixedClock("2026-05-07T21:00:00Z"));

        List<TimeRange> result = calculator.calculateAvailableSlots(
                TEST_DATE,
                LocalTime.of(9, 0),
                LocalTime.of(17, 0),
                Duration.ofHours(1),
                Duration.ofMinutes(30),
                List.of()
        );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("handles midnight-crossing schedule correctly")
    void should_handleMidnightCrossingSchedule_when_workEndBeforeWorkStart() {
        // 2026-05-07T18:00:00Z = 21:00 Kyiv — before 22:00 start
        calculator = new TimeSlotCalculator(fixedClock("2026-05-07T18:00:00Z"));

        List<TimeRange> result = calculator.calculateAvailableSlots(
                TEST_DATE,
                LocalTime.of(22, 0),
                LocalTime.of(2, 0),
                Duration.ofHours(1),
                Duration.ofMinutes(30),
                List.of()
        );

        assertThat(result).hasSize(7);
        assertThat(result.stream().map(TimeSlotCalculatorTest::localStart)).containsExactly(
                LocalTime.of(22, 0),
                LocalTime.of(22, 30),
                LocalTime.of(23, 0),
                LocalTime.of(23, 30),
                LocalTime.of(0, 0),
                LocalTime.of(0, 30),
                LocalTime.of(1, 0)
        );
    }

    @Test
    @DisplayName("cross-midnight window on a DST fall-back day uses civil end (25h day) — keeps the late slot")
    void should_useCivilEnd_when_crossMidnightWindowOnFallBackDay() {
        // Europe/Kyiv fall-back 2024-10-27: clock turns 04:00→03:00 (25h day). A 22:00→02:00 window ends
        // at the CIVIL 02:00 on 2024-10-28 = 4 real hours after 22:00. The previous flat +24h bug computed
        // the end from the same-day 02:00 instant + 24h = only 3 real hours, dropping the final slot.
        LocalDate fallBackDate = LocalDate.of(2024, 10, 27);
        // 2024-10-27T18:00:00Z = 20:00 Kyiv (+02 after the fall-back) — before the 22:00 window start.
        calculator = new TimeSlotCalculator(fixedClock("2024-10-27T18:00:00Z"));

        List<TimeRange> result = calculator.calculateAvailableSlots(
                fallBackDate,
                LocalTime.of(22, 0),
                LocalTime.of(2, 0),
                Duration.ofHours(1),
                Duration.ofMinutes(30),
                List.of()
        );

        // 4 real hours, 1h service, 30m step → 7 slots; the final 01:00 start (ends 02:00) survives.
        assertThat(result).hasSize(7);
        assertThat(result.stream().map(TimeSlotCalculatorTest::localStart)).containsExactly(
                LocalTime.of(22, 0), LocalTime.of(22, 30), LocalTime.of(23, 0), LocalTime.of(23, 30),
                LocalTime.of(0, 0), LocalTime.of(0, 30), LocalTime.of(1, 0));
        // The civil 02:00 on 2024-10-28 is the real end instant (4h after the 20:00Z start).
        assertThat(result.get(result.size() - 1).end())
                .isEqualTo(Instant.parse("2024-10-28T00:00:00Z"));
    }

    @Test
    @DisplayName("cross-midnight window on a DST spring-forward day uses civil end (23h day) — no invented slot")
    void should_useCivilEnd_when_crossMidnightWindowOnSpringForwardDay() {
        // Europe/Kyiv spring-forward 2024-03-31: 03:00→04:00 (23h day). A 22:00→02:00 window still ends at
        // the CIVIL 02:00 on 2024-04-01 = 4 real hours after 22:00. The flat +24h bug computed 5 real hours
        // (same-day 02:00 instant + 24h), inventing an extra non-bookable slot.
        LocalDate springDate = LocalDate.of(2024, 3, 31);
        // 2024-03-31T18:00:00Z = 21:00 Kyiv (+03 after the spring-forward) — before the 22:00 window start.
        calculator = new TimeSlotCalculator(fixedClock("2024-03-31T18:00:00Z"));

        List<TimeRange> result = calculator.calculateAvailableSlots(
                springDate,
                LocalTime.of(22, 0),
                LocalTime.of(2, 0),
                Duration.ofHours(1),
                Duration.ofMinutes(30),
                List.of()
        );

        // 4 real hours → 7 slots (not 9). No slot starts at or after the 01:30 mark that the bug invented.
        assertThat(result).hasSize(7);
        assertThat(result.stream().map(TimeSlotCalculatorTest::localStart)).containsExactly(
                LocalTime.of(22, 0), LocalTime.of(22, 30), LocalTime.of(23, 0), LocalTime.of(23, 30),
                LocalTime.of(0, 0), LocalTime.of(0, 30), LocalTime.of(1, 0));
        assertThat(result.get(result.size() - 1).end())
                .isEqualTo(Instant.parse("2024-03-31T23:00:00Z"));
    }

    @Test
    @DisplayName("returns empty list when service duration exceeds the work window")
    void should_returnEmpty_when_serviceDurationLongerThanWorkWindow() {
        calculator = new TimeSlotCalculator(fixedClock("2026-05-07T06:00:00Z"));

        List<TimeRange> result = calculator.calculateAvailableSlots(
                TEST_DATE,
                LocalTime.of(9, 0),
                LocalTime.of(9, 30),
                Duration.ofHours(1),
                Duration.ofMinutes(30),
                List.of()
        );

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("does not return a slot whose start is in the past")
    void should_notReturnPastSlot_when_clockIsAfterSlotStart() {
        // 2026-05-07T07:01:00Z = 10:01 Kyiv (+03) — after 10:00 slot start, before 10:30
        calculator = new TimeSlotCalculator(fixedClock("2026-05-07T07:01:00Z"));

        List<TimeRange> result = calculator.calculateAvailableSlots(
                TEST_DATE,
                LocalTime.of(9, 0),
                LocalTime.of(13, 0),
                Duration.ofHours(1),
                Duration.ofMinutes(30),
                List.of()
        );

        List<LocalTime> starts = result.stream().map(TimeSlotCalculatorTest::localStart).toList();
        assertThat(starts).doesNotContain(LocalTime.of(10, 0));
        assertThat(starts).contains(LocalTime.of(10, 30));
    }

    @Test
    @DisplayName("handles DST spring-forward correctly — no slot start falls in the missing hour")
    void should_correctlyHandleDstSpringForward_when_slotStraddlesGap() {
        // 2026-03-29: Europe/Kyiv spring-forward at 03:00 local (+02) → 04:00 local (+03)
        // i.e., the gap hour is [03:00, 04:00) Kyiv local — instants [01:00Z, 02:00Z) on that date.
        // Clock frozen to 2026-03-28T22:00:00Z — before workStart (01:00 Kyiv = 23:00Z prev day).
        LocalDate dstDate = LocalDate.of(2026, 3, 29);
        calculator = new TimeSlotCalculator(fixedClock("2026-03-28T22:00:00Z"));

        List<TimeRange> result = calculator.calculateAvailableSlots(
                dstDate,
                LocalTime.of(1, 0),
                LocalTime.of(4, 0),
                Duration.ofHours(1),
                Duration.ofMinutes(30),
                List.of()
        );

        // workEnd: LocalTime(04:00).atZone(Kyiv) on 2026-03-29 resolves to the same instant as
        // LocalTime(03:00).atZone(Kyiv) because 03:xx doesn't exist — both map to 2026-03-29T01:00:00Z.
        // The walk produces 3 slots: 01:00, 01:30, 02:00 (all under +02:00 offset).
        // The slot starting at 02:00 Kyiv ends at 2026-03-29T01:00:00Z = workEndInst exactly,
        // so it is included (condition: candidateEnd.isAfter(workEndInst) is false → include).
        assertThat(result).hasSize(3);

        // No slot start should fall in the DST gap: [2026-03-29T01:00Z, 2026-03-29T02:00Z)
        Instant gapOpen  = Instant.parse("2026-03-29T01:00:00Z");
        Instant gapClose = Instant.parse("2026-03-29T02:00:00Z");
        assertThat(result)
                .noneMatch(r -> !r.start().isBefore(gapOpen) && r.start().isBefore(gapClose));

        // Verify the three expected starts in Kyiv local time
        List<LocalTime> starts = result.stream().map(TimeSlotCalculatorTest::localStart).toList();
        assertThat(starts).containsExactly(
                LocalTime.of(1, 0),
                LocalTime.of(1, 30),
                LocalTime.of(2, 0)
        );
    }

    @Test
    @DisplayName("throws IllegalArgumentException when step is zero")
    void should_throwIllegalArgument_when_stepIsZero() {
        TimeSlotCalculator calc = new TimeSlotCalculator(Clock.fixed(Instant.parse("2026-05-07T05:59:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> calc.calculateAvailableSlots(LocalDate.of(2026, 5, 7), LocalTime.of(9, 0), LocalTime.of(13, 0),
                Duration.ofMinutes(60), Duration.ZERO, List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("step must be positive");
    }

    @Test
    @DisplayName("throws IllegalArgumentException when serviceDuration is zero")
    void should_throwIllegalArgument_when_serviceDurationIsZero() {
        TimeSlotCalculator calc = new TimeSlotCalculator(Clock.fixed(Instant.parse("2026-05-07T05:59:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> calc.calculateAvailableSlots(LocalDate.of(2026, 5, 7), LocalTime.of(9, 0), LocalTime.of(13, 0),
                Duration.ZERO, Duration.ofMinutes(30), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("serviceDuration must be positive");
    }

    @Test
    @DisplayName("throws IllegalArgumentException when serviceDuration exceeds 24 hours")
    void should_throwIllegalArgument_when_serviceDurationExceedsOneDay() {
        TimeSlotCalculator calc = new TimeSlotCalculator(Clock.fixed(Instant.parse("2026-05-07T05:59:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> calc.calculateAvailableSlots(LocalDate.of(2026, 5, 7), LocalTime.of(9, 0), LocalTime.of(13, 0),
                Duration.ofDays(1).plusMinutes(1), Duration.ofMinutes(30), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("serviceDuration must not exceed 24 hours");
    }

    @Test
    @DisplayName("throws IllegalArgumentException when step exceeds 24 hours")
    void should_throwIllegalArgument_when_stepExceedsOneDay() {
        TimeSlotCalculator calc = new TimeSlotCalculator(Clock.fixed(Instant.parse("2026-05-07T05:59:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> calc.calculateAvailableSlots(LocalDate.of(2026, 5, 7), LocalTime.of(9, 0), LocalTime.of(13, 0),
                Duration.ofMinutes(60), Duration.ofDays(1).plusMinutes(1), List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("step must not exceed 24 hours");
    }

    @Test
    @DisplayName("should return available slots when occupied list is null (null treated as empty)")
    void should_returnAvailableSlots_when_occupiedIsNull() {
        // 05:45Z = 08:45 Kyiv — bookable cutoff exactly 09:00, so the single 09:00 candidate survives.
        TimeSlotCalculator calc = new TimeSlotCalculator(Clock.fixed(Instant.parse("2026-05-07T05:45:00Z"), ZoneOffset.UTC));

        List<TimeSlotCalculator.TimeRange> result = calc.calculateAvailableSlots(
            LocalDate.of(2026, 5, 7), LocalTime.of(9, 0), LocalTime.of(10, 0),
            Duration.ofMinutes(60), Duration.ofMinutes(30), null);

        assertThat(result).isNotEmpty();
    }

    @Test
    @DisplayName("excludes a slot starting inside the 15-minute booking lead time — it would 400 on create")
    void should_excludeSlot_when_startIsWithinBookingLeadTime() {
        // 06:50Z = 09:50 Kyiv (+03). Bookable cutoff = 10:05 Kyiv.
        //   10:00 start → 10 min away → INSIDE the lead time → must NOT be offered (create would 400).
        //   10:30 start → 40 min away → offered.
        TimeSlotCalculator calc = new TimeSlotCalculator(fixedClock("2026-05-07T06:50:00Z"));

        List<TimeRange> result = calc.calculateAvailableSlots(
                TEST_DATE,
                LocalTime.of(9, 0),
                LocalTime.of(13, 0),
                Duration.ofHours(1),
                Duration.ofMinutes(30),
                List.of()
        );

        List<LocalTime> starts = result.stream().map(TimeSlotCalculatorTest::localStart).toList();
        assertThat(starts).doesNotContain(LocalTime.of(10, 0));
        assertThat(starts).startsWith(LocalTime.of(10, 30));
    }

    @Test
    @DisplayName("keeps a slot starting exactly at the 15-minute lead-time cutoff — offered == accepted")
    void should_keepSlot_when_startIsExactlyAtLeadTimeCutoff() {
        // 06:45Z = 09:45 Kyiv (+03). Bookable cutoff = exactly 10:00 Kyiv — the boundary is inclusive,
        // matching BookingStartsAtValidator (a startsAt exactly 15 min out is accepted on create).
        TimeSlotCalculator calc = new TimeSlotCalculator(fixedClock("2026-05-07T06:45:00Z"));

        List<TimeRange> result = calc.calculateAvailableSlots(
                TEST_DATE,
                LocalTime.of(9, 0),
                LocalTime.of(13, 0),
                Duration.ofHours(1),
                Duration.ofMinutes(30),
                List.of()
        );

        assertThat(result.stream().map(TimeSlotCalculatorTest::localStart))
                .startsWith(LocalTime.of(10, 0));
    }

    @Test
    @DisplayName("should return empty list when workStart equals workEnd (no working hours)")
    void should_returnEmpty_when_workStartEqualsWorkEnd() {
        TimeSlotCalculator calc = new TimeSlotCalculator(
            Clock.fixed(Instant.parse("2026-05-07T05:59:00Z"), ZoneOffset.UTC));
        List<TimeSlotCalculator.TimeRange> result = calc.calculateAvailableSlots(
            LocalDate.of(2026, 5, 7),
            LocalTime.of(9, 0),
            LocalTime.of(9, 0),
            Duration.ofMinutes(60),
            Duration.ofMinutes(30),
            List.of()
        );
        assertThat(result).isEmpty();
    }

    // ── hasAvailableSlot — existence-only, short-circuiting (Perf MEDIUM-4 / LOW-2) ──────────────
    //
    // The day-availability callers only ever ask "is there ONE bookable slot?", so they use this instead
    // of building (and discarding) the whole list. It must agree with calculateAvailableSlots exactly:
    // same cutoff floor, same overlap test — only the termination differs.

    @Test
    @DisplayName("hasAvailableSlot — true when at least one candidate is free and at/after the cutoff")
    void should_returnTrue_when_aBookableCandidateExists() {
        // 05:45Z = 08:45 Kyiv; cutoff = 09:00 Kyiv exactly.
        calculator = new TimeSlotCalculator(fixedClock("2026-05-07T05:45:00Z"));
        Instant cutoff = calculator.bookableCutoff();

        boolean hasSlot = calculator.hasAvailableSlot(
                TEST_DATE, LocalTime.of(9, 0), LocalTime.of(13, 0),
                Duration.ofHours(1), Duration.ofMinutes(30), List.of(), cutoff);

        assertThat(hasSlot).isTrue();
    }

    @Test
    @DisplayName("hasAvailableSlot — false when the day's only candidates all start before the cutoff")
    void should_returnFalse_when_everyCandidateIsBeforeTheCutoff() {
        // 14:00Z = 17:00 Kyiv; cutoff = 17:15 Kyiv. A 09:00-13:00 window is entirely in the past.
        calculator = new TimeSlotCalculator(fixedClock("2026-05-07T14:00:00Z"));
        Instant cutoff = calculator.bookableCutoff();

        boolean hasSlot = calculator.hasAvailableSlot(
                TEST_DATE, LocalTime.of(9, 0), LocalTime.of(13, 0),
                Duration.ofHours(1), Duration.ofMinutes(30), List.of(), cutoff);

        assertThat(hasSlot).isFalse();
        assertThat(calculator.calculateAvailableSlots(
                TEST_DATE, LocalTime.of(9, 0), LocalTime.of(13, 0),
                Duration.ofHours(1), Duration.ofMinutes(30), List.of(), cutoff))
                .as("the list variant agrees — the two share one walk")
                .isEmpty();
    }

    @Test
    @DisplayName("hasAvailableSlot — false when every candidate is blocked by an existing booking")
    void should_returnFalse_when_everyCandidateIsOccupied() {
        calculator = new TimeSlotCalculator(fixedClock("2026-05-07T05:45:00Z"));
        Instant cutoff = calculator.bookableCutoff();
        // The whole 09:00-11:00 window is booked; a 2-hour service fits nowhere else.
        List<TimeRange> occupied = List.of(kyivRange(TEST_DATE, LocalTime.of(9, 0), LocalTime.of(11, 0)));

        boolean hasSlot = calculator.hasAvailableSlot(
                TEST_DATE, LocalTime.of(9, 0), LocalTime.of(11, 0),
                Duration.ofHours(2), Duration.ofMinutes(30), occupied, cutoff);

        assertThat(hasSlot).isFalse();
    }

    @Test
    @DisplayName("hasAvailableSlot — agrees with calculateAvailableSlots for the same cutoff (caller-supplied)")
    void should_agreeWithSlotList_when_sameCutoffSupplied() {
        calculator = new TimeSlotCalculator(fixedClock("2026-05-07T05:45:00Z"));
        // A cutoff the caller derived once for the request — 11:00 Kyiv (08:00Z), stricter than "now".
        Instant cutoff = TEST_DATE.atTime(LocalTime.of(11, 0)).atZone(KYIV).toInstant();

        List<TimeRange> slots = calculator.calculateAvailableSlots(
                TEST_DATE, LocalTime.of(9, 0), LocalTime.of(13, 0),
                Duration.ofHours(1), Duration.ofMinutes(30), List.of(), cutoff);
        boolean hasSlot = calculator.hasAvailableSlot(
                TEST_DATE, LocalTime.of(9, 0), LocalTime.of(13, 0),
                Duration.ofHours(1), Duration.ofMinutes(30), List.of(), cutoff);

        assertThat(hasSlot).isEqualTo(!slots.isEmpty());
        assertThat(slots.stream().map(TimeSlotCalculatorTest::localStart))
                .as("the caller-supplied cutoff — not the clock — is the floor")
                .containsExactly(LocalTime.of(11, 0), LocalTime.of(11, 30), LocalTime.of(12, 0));
    }
}
