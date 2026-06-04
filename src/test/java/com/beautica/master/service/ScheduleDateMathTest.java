package com.beautica.master.service;

import com.beautica.common.DateRange;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleDateMathTest {

    /** Fixes the clock at a UTC instant; the component re-zones it to Kyiv internally. */
    private ScheduleDateMath at(String utcInstant) {
        Clock clock = Clock.fixed(Instant.parse(utcInstant), ZoneOffset.UTC);
        return new ScheduleDateMath(clock);
    }

    /** Fixes the clock so Kyiv civil date equals the given date at local noon. */
    private ScheduleDateMath atKyivDate(LocalDate kyivDate) {
        Instant noon = kyivDate.atTime(12, 0).atZone(TimeZones.KYIV).toInstant();
        return new ScheduleDateMath(Clock.fixed(noon, ZoneOffset.UTC));
    }

    @Test
    void should_returnKyivCivilDate_when_instantIsPreviousUtcDay() {
        ScheduleDateMath math = at("2024-05-21T22:30:00Z");

        LocalDate today = math.today();

        assertThat(today).isEqualTo(LocalDate.of(2024, 5, 22));
    }

    @Test
    void should_classifyYesterdayAsPast_when_checkingIsPast() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        assertThat(math.isPast(LocalDate.of(2024, 5, 21))).isTrue();
        assertThat(math.isPast(LocalDate.of(2024, 5, 22))).isFalse();
        assertThat(math.isPast(LocalDate.of(2024, 5, 23))).isFalse();
    }

    @Test
    void should_treatTodayAndFutureAsEditable_when_checkingIsEditable() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        assertThat(math.isEditable(LocalDate.of(2024, 5, 21))).isFalse();
        assertThat(math.isEditable(LocalDate.of(2024, 5, 22))).isTrue();
        assertThat(math.isEditable(LocalDate.of(2024, 5, 23))).isTrue();
    }

    @Test
    void should_clampStartToToday_when_wholeCurrentMonthAndTodayAfterFirst() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 21));

        DateRange range = math.wholeCurrentMonth();

        assertThat(range).isEqualTo(new DateRange(LocalDate.of(2024, 5, 21), LocalDate.of(2024, 5, 31)));
    }

    @Test
    void should_startAtFirstOfMonth_when_wholeCurrentMonthAndTodayIsFirst() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 1));

        DateRange range = math.wholeCurrentMonth();

        assertThat(range).isEqualTo(new DateRange(LocalDate.of(2024, 5, 1), LocalDate.of(2024, 5, 31)));
    }

    @Test
    void should_endOnDec31_when_wholeYearFromLeapDay() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 2, 29));

        DateRange range = math.wholeYear();

        assertThat(range).isEqualTo(new DateRange(LocalDate.of(2024, 2, 29), LocalDate.of(2024, 12, 31)));
    }

    @Test
    void should_clampToFeb28_when_nextNMonthsCrossesIntoNonLeapYear() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 11, 30));

        DateRange range = math.nextNMonths(3);

        assertThat(range.to()).isEqualTo(LocalDate.of(2025, 2, 28));
        assertThat(range.from()).isEqualTo(LocalDate.of(2024, 11, 30));
    }

    @Test
    void should_clampToFeb29_when_nextOneMonthFromJan31InLeapYear() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 1, 31));

        DateRange range = math.nextNMonths(1);

        assertThat(range.to()).isEqualTo(LocalDate.of(2024, 2, 29));
    }

    @Test
    void should_clampToFeb28_when_nextOneMonthFromJan31InNonLeapYear() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2023, 1, 31));

        DateRange range = math.nextNMonths(1);

        assertThat(range.to()).isEqualTo(LocalDate.of(2023, 2, 28));
    }

    @Test
    void should_rejectNonPositiveMonthCount_when_nextNMonths() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        assertThatThrownBy(() -> math.nextNMonths(0)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> math.nextNMonths(-1)).isInstanceOf(BusinessException.class);
    }

    @Test
    void should_clampLeapDayOrigin_when_computingCap() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 2, 29));

        assertThat(math.cap()).isEqualTo(LocalDate.of(2026, 2, 28));
    }

    @Test
    void should_passValidRange_when_assertWithinBounds() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        math.assertWithinBounds(LocalDate.of(2024, 5, 22), LocalDate.of(2024, 6, 22));
    }

    @Test
    void should_rejectPastStart_when_assertWithinBounds() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        assertThatThrownBy(() ->
                math.assertWithinBounds(LocalDate.of(2024, 5, 21), LocalDate.of(2024, 6, 22)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void should_rejectEndAfterCap_when_assertWithinBounds() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));
        LocalDate beyondCap = LocalDate.of(2026, 5, 23); // cap = 2026-05-22

        assertThatThrownBy(() ->
                math.assertWithinBounds(LocalDate.of(2024, 5, 22), beyondCap))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void should_rejectStartAfterEnd_when_assertWithinBounds() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        assertThatThrownBy(() ->
                math.assertWithinBounds(LocalDate.of(2024, 6, 1), LocalDate.of(2024, 5, 25)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void should_acceptStartAtPastFloor_when_assertExpandable() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));
        // from = today - 1y exactly (the past floor). A 30-day window stays within the 365-day span cap.
        LocalDate from = LocalDate.of(2023, 5, 22);
        LocalDate to = LocalDate.of(2023, 6, 21);

        math.assertExpandable(from, to);
    }

    @Test
    void should_rejectAbsurdlyFarPastStart_when_assertExpandable() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));
        LocalDate farPast = LocalDate.of(1000, 1, 1); // pastFloor = 2023-05-22

        assertThatThrownBy(() -> math.assertExpandable(farPast, LocalDate.of(2024, 5, 22)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void should_rejectStartJustBeyondPastFloor_when_assertExpandable() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));
        // pastFloor = 2023-05-22; one day earlier must be rejected.
        LocalDate justBeyond = LocalDate.of(2023, 5, 21);

        assertThatThrownBy(() -> math.assertExpandable(justBeyond, LocalDate.of(2024, 5, 22)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void should_returnSingleDate_when_expandInclusiveOverOneDay() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        List<LocalDate> dates = math.expandInclusive(LocalDate.of(2024, 5, 22), LocalDate.of(2024, 5, 22));

        assertThat(dates).containsExactly(LocalDate.of(2024, 5, 22));
    }

    @Test
    void should_includeLeapDay_when_expandInclusiveCrossesFeb29() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 2, 28));

        List<LocalDate> dates = math.expandInclusive(LocalDate.of(2024, 2, 28), LocalDate.of(2024, 3, 1));

        assertThat(dates).containsExactly(
                LocalDate.of(2024, 2, 28),
                LocalDate.of(2024, 2, 29),
                LocalDate.of(2024, 3, 1));
    }

    @Test
    void should_acceptMaxSpan_when_expandInclusiveOver365DaySpan() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 1, 1));
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = from.plusDays(365); // span = 365 days between -> 366 inclusive dates

        List<LocalDate> dates = math.expandInclusive(from, to);

        assertThat(dates).hasSize(366);
        assertThat(dates).contains(LocalDate.of(2024, 2, 29));
    }

    @Test
    void should_throw_when_expandInclusiveExceeds366DaySpan() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 1, 1));
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = from.plusDays(366);

        assertThatThrownBy(() -> math.expandInclusive(from, to))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void should_throw_when_expandInclusiveStartAfterEnd() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        assertThatThrownBy(() ->
                math.expandInclusive(LocalDate.of(2024, 5, 23), LocalDate.of(2024, 5, 22)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void should_returnIsoWeekday_when_isoDow() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        // 2024-05-20 is a Monday, 2024-05-26 is a Sunday.
        assertThat(math.isoDow(LocalDate.of(2024, 5, 20))).isEqualTo(1);
        assertThat(math.isoDow(LocalDate.of(2024, 5, 26))).isEqualTo(7);
        assertThat(LocalDate.of(2024, 5, 20).getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
    }

    // ── 15.3 audit pins (current behaviour; 15.4/15.7 will gate these) ────────────

    /**
     * BACKLOG/15.4 pin: {@code nextNMonths} has no {@code @Max} cap. Because {@code n} is an {@code int}
     * (≤ Integer.MAX_VALUE) and {@link LocalDate#plusMonths(long)} does NOT overflow at that magnitude,
     * a huge positive {@code n} does NOT throw today — it silently returns an absurd far-future range
     * (year ~166-million). This pins the un-gated behaviour so the future {@code @Max} cap surfaces as a
     * visible test diff. The earlier assumption that it "throws" is incorrect for an int parameter.
     */
    @Test
    void should_returnAbsurdFarFutureRangeWithoutThrowing_when_nextNMonthsGetsHugePositiveN() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        DateRange range = math.nextNMonths(2_000_000_000);

        assertThat(range.from()).isEqualTo(LocalDate.of(2024, 5, 22));
        assertThat(range.to())
                .as("un-gated nextNMonths produces a far-future end (year far beyond the 2y cap), to=%s", range.to())
                .isAfter(LocalDate.of(1_000_000, 1, 1));
        assertThat(range.to().getYear()).isGreaterThan(1_000_000);
    }

    /**
     * BACKLOG/15.4 pin: even {@code Integer.MAX_VALUE} months does not overflow {@code plusMonths}, so
     * {@code nextNMonths} still does not throw at the int ceiling. Pins the upper boundary of the int domain.
     */
    @Test
    void should_notThrow_when_nextNMonthsGetsIntegerMaxValue() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        DateRange range = math.nextNMonths(Integer.MAX_VALUE);

        assertThat(range.to()).isAfter(range.from());
        assertThat(range.to().getYear()).isGreaterThan(1_000_000);
    }

    /**
     * Pins the exact 365-day span boundary: a span of exactly {@code MAX_EXPANSION_SPAN_DAYS} (365 days
     * between endpoints) is ACCEPTED and materialises 366 inclusive dates with no off-by-one rejection.
     */
    @Test
    void should_materialise366Dates_when_expandInclusiveSpanIsExactly365Days() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 1, 1));
        LocalDate from = LocalDate.of(2023, 6, 1);
        LocalDate to = from.plusDays(365); // exactly the cap → 366 inclusive dates

        List<LocalDate> dates = math.expandInclusive(from, to);

        assertThat(dates).hasSize(366);
        assertThat(dates).first().isEqualTo(from);
        assertThat(dates).last().isEqualTo(to);
    }

    /**
     * Pins that the span guard is enforced BEFORE materialisation: a 366-day span is rejected with a
     * {@link BusinessException} (400), and crucially no giant list is allocated. Asserting the exception
     * type proves the guard fired rather than an OOM/loop. The future caller-side {@code @Max} gate that
     * turns this into a 400-not-500 lives in 15.4/15.5.
     */
    @Test
    void should_rejectWithBusinessException_when_expandInclusiveSpanIs366Days() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 1, 1));
        LocalDate from = LocalDate.of(2023, 6, 1);
        LocalDate to = from.plusDays(366);

        assertThatThrownBy(() -> math.expandInclusive(from, to))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("366");
    }

    /**
     * BACKLOG pin: {@code isPast(null)} dereferences {@code null} → NPE today (no {@code @NonNull} guard).
     * The future deliberate null-guard (15.4/15.7) will replace this; the pin makes that change a visible diff.
     */
    @Test
    void should_throwNpe_when_isPastGetsNullDate() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        assertThatThrownBy(() -> math.isPast(null))
                .isInstanceOf(NullPointerException.class);
    }

    /** BACKLOG pin: {@code isoDow(null)} → NPE today (no null-guard). Pins current behaviour. */
    @Test
    void should_throwNpe_when_isoDowGetsNullDate() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        assertThatThrownBy(() -> math.isoDow(null))
                .isInstanceOf(NullPointerException.class);
    }

    /**
     * BACKLOG pin: {@code assertWithinBounds} dereferences both args via {@code isPast}/{@code isAfter}
     * before any null-guard, so a null {@code from} OR a null {@code to} yields NPE (not a 400
     * BusinessException) today. Pins both null positions so the future {@code @NotNull} gate is a visible diff.
     */
    @Test
    void should_throwNpe_when_assertWithinBoundsGetsNullFrom() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        assertThatThrownBy(() -> math.assertWithinBounds(null, LocalDate.of(2024, 6, 1)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_throwNpe_when_assertWithinBoundsGetsNullTo() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        assertThatThrownBy(() -> math.assertWithinBounds(LocalDate.of(2024, 5, 22), null))
                .isInstanceOf(NullPointerException.class);
    }

    /** BACKLOG pin: {@code expandInclusive(null, to)} → NPE today (no null-guard). Pins current behaviour. */
    @Test
    void should_throwNpe_when_expandInclusiveGetsNullFrom() {
        ScheduleDateMath math = atKyivDate(LocalDate.of(2024, 5, 22));

        assertThatThrownBy(() -> math.expandInclusive(null, LocalDate.of(2024, 6, 1)))
                .isInstanceOf(NullPointerException.class);
    }
}
