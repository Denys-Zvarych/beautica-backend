package com.beautica.booking.enums;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for Phase 29.3's {@link BookingPartition#COVER} / {@link
 * BookingPartition#isCoverMember()} — the guard against double-counting {@code AWAITING_CLOSURE}
 * in a total-disjoint-cover sum. Deliberately a NEW file rather than an extension of {@code
 * BookingSpecificationsTest} (a protected backwards-compatibility suite for this track that must
 * stay byte-for-byte unedited — see that class's own package for the mechanical guard on {@code
 * partition(PAST)}'s shape).
 */
@DisplayName("BookingPartition — Phase 29.3 total-disjoint-cover guard")
class BookingPartitionTest {

    @Test
    @DisplayName("COVER contains exactly the three total-disjoint-cover members")
    void should_containExactlyThreeMembers_when_inspectingCover() {
        assertThat(BookingPartition.COVER)
                .containsExactlyInAnyOrder(
                        BookingPartition.UPCOMING, BookingPartition.PAST, BookingPartition.CANCELLED)
                .doesNotContain(BookingPartition.AWAITING_CLOSURE)
                .hasSize(3);
    }

    @Test
    @DisplayName("isCoverMember() is TRUE for UPCOMING/PAST/CANCELLED, FALSE only for AWAITING_CLOSURE")
    void should_returnCoverMembershipPerConstant_when_isCoverMemberCalled() {
        assertThat(BookingPartition.UPCOMING.isCoverMember()).isTrue();
        assertThat(BookingPartition.PAST.isCoverMember()).isTrue();
        assertThat(BookingPartition.CANCELLED.isCoverMember()).isTrue();
        assertThat(BookingPartition.AWAITING_CLOSURE.isCoverMember()).isFalse();
    }

    @Test
    @DisplayName("BookingPartition has exactly four constants — AWAITING_CLOSURE is additive, "
            + "not a replacement of any pre-29.3 member")
    void should_haveFourConstants_when_valuesInspected() {
        assertThat(BookingPartition.values()).hasSize(4);
    }
}
