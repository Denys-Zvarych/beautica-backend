package com.beautica.common;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DateRangeTest {

    private static final LocalDate JAN_10 = LocalDate.of(2024, 1, 10);
    private static final LocalDate JAN_20 = LocalDate.of(2024, 1, 20);

    @Test
    void should_rejectNullFrom_when_constructing() {
        assertThatThrownBy(() -> new DateRange(null, JAN_20))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void should_rejectToBeforeFrom_when_constructing() {
        assertThatThrownBy(() -> new DateRange(JAN_20, JAN_10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_allowOpenEnded_when_toIsNull() {
        DateRange range = new DateRange(JAN_10, null);

        assertThat(range.to()).isNull();
    }

    @Test
    void should_includeEndpointsInclusive_when_contains() {
        DateRange range = new DateRange(JAN_10, JAN_20);

        assertThat(range.contains(JAN_10)).isTrue();
        assertThat(range.contains(JAN_20)).isTrue();
        assertThat(range.contains(LocalDate.of(2024, 1, 15))).isTrue();
    }

    @Test
    void should_excludeOutside_when_contains() {
        DateRange range = new DateRange(JAN_10, JAN_20);

        assertThat(range.contains(LocalDate.of(2024, 1, 9))).isFalse();
        assertThat(range.contains(LocalDate.of(2024, 1, 21))).isFalse();
    }

    @Test
    void should_containAnyFutureDate_when_openEnded() {
        DateRange range = new DateRange(JAN_10, null);

        assertThat(range.contains(JAN_10)).isTrue();
        assertThat(range.contains(LocalDate.of(2030, 1, 1))).isTrue();
        assertThat(range.contains(LocalDate.of(2024, 1, 9))).isFalse();
    }

    @Test
    void should_overlap_when_rangesTouchAtEndpoint() {
        DateRange a = new DateRange(JAN_10, JAN_20);
        DateRange b = new DateRange(JAN_20, LocalDate.of(2024, 1, 25));

        assertThat(a.overlaps(b)).isTrue();
        assertThat(b.overlaps(a)).isTrue();
    }

    @Test
    void should_overlap_when_nested() {
        DateRange outer = new DateRange(JAN_10, JAN_20);
        DateRange inner = new DateRange(LocalDate.of(2024, 1, 12), LocalDate.of(2024, 1, 15));

        assertThat(outer.overlaps(inner)).isTrue();
        assertThat(inner.overlaps(outer)).isTrue();
    }

    @Test
    void should_notOverlap_when_disjoint() {
        DateRange a = new DateRange(JAN_10, JAN_20);
        DateRange b = new DateRange(LocalDate.of(2024, 2, 1), LocalDate.of(2024, 2, 10));

        assertThat(a.overlaps(b)).isFalse();
        assertThat(b.overlaps(a)).isFalse();
    }

    @Test
    void should_overlap_when_openEndedRangeMeetsFutureRange() {
        DateRange open = new DateRange(JAN_10, null);
        DateRange future = new DateRange(LocalDate.of(2030, 6, 1), LocalDate.of(2030, 6, 30));

        assertThat(open.overlaps(future)).isTrue();
        assertThat(future.overlaps(open)).isTrue();
    }

    @Test
    void should_notOverlap_when_openEndedStartsAfterOtherEnds() {
        DateRange open = new DateRange(LocalDate.of(2024, 2, 1), null);
        DateRange past = new DateRange(JAN_10, JAN_20);

        assertThat(open.overlaps(past)).isFalse();
        assertThat(past.overlaps(open)).isFalse();
    }

    // ── 15.3 audit pins: open-ended (null `to`) edge coverage ─────────────────────

    /**
     * Two open-ended ranges both extend to +∞, so they always share the tail beyond the later start —
     * they overlap regardless of which starts first. Pins the both-null-`to` branch of {@code overlaps}.
     */
    @Test
    void should_overlap_when_bothRangesOpenEnded() {
        DateRange earlier = new DateRange(JAN_10, null);
        DateRange later = new DateRange(LocalDate.of(2030, 1, 1), null);

        assertThat(earlier.overlaps(later)).isTrue();
        assertThat(later.overlaps(earlier)).isTrue();
    }

    /**
     * Open-ended containment has no upper bound: the start is included and every far-future date is
     * contained, but a date before the start is not. Pins {@code contains} boundary inclusivity on the
     * null-`to` branch (start inclusive, start-minus-one excluded).
     */
    @Test
    void should_includeStartButExcludeBeforeStart_when_openEndedContains() {
        DateRange open = new DateRange(JAN_10, null);

        assertThat(open.contains(JAN_10)).isTrue();
        assertThat(open.contains(LocalDate.of(2024, 1, 9))).isFalse();
        assertThat(open.contains(LocalDate.of(9999, 12, 31))).isTrue();
    }

    /** {@code overlaps(null)} is a programming error — pins the explicit null-guard contract. */
    @Test
    void should_rejectNullOther_when_overlaps() {
        DateRange range = new DateRange(JAN_10, JAN_20);

        assertThatThrownBy(() -> range.overlaps(null))
                .isInstanceOf(NullPointerException.class);
    }

    /** {@code contains(null)} is a programming error — pins the explicit null-guard contract. */
    @Test
    void should_rejectNullDate_when_contains() {
        DateRange range = new DateRange(JAN_10, JAN_20);

        assertThatThrownBy(() -> range.contains(null))
                .isInstanceOf(NullPointerException.class);
    }
}
