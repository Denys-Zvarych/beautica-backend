package com.beautica.booking.repository;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingPartition;
import com.beautica.booking.enums.BookingStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Predicate-SHAPE unit test for Phase 29.3's {@code AWAITING_CLOSURE} arm of {@link
 * BookingSpecifications#partition}. A NEW file, not an extension of {@code
 * BookingSpecificationsTest} — that class is a protected backwards-compatibility suite for this
 * track (its {@code UPCOMING}/{@code PAST}/{@code CANCELLED} shape tests, including the mechanical
 * V130-conjunct guard on {@code PAST}, must stay byte-for-byte unedited) — so the new arm's
 * coverage lives here instead, mirroring that class's exact Mockito-mocked {@code
 * jakarta.persistence.criteria} posture.
 */
@DisplayName("BookingSpecifications — Phase 29.3 partition(AWAITING_CLOSURE) predicate shape")
class BookingSpecificationsAwaitingClosureTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-01T12:00:00Z");

    @SuppressWarnings("unchecked")
    private final Root<Booking> root = mock(Root.class);
    @SuppressWarnings("unchecked")
    private final CriteriaQuery<Object> query = mock(CriteriaQuery.class);
    private final CriteriaBuilder cb = mock(CriteriaBuilder.class);

    @SuppressWarnings("unchecked")
    private final Path<Object> statusPath = mock(Path.class);
    @SuppressWarnings("unchecked")
    private final Path<OffsetDateTime> endsAtPath = mock(Path.class);

    @BeforeEach
    void wireCommonPaths() {
        doReturn(statusPath).when(root).get("status");
        doReturn(endsAtPath).when(root).get("endsAt");
    }

    @Test
    @DisplayName("partition(AWAITING_CLOSURE) is status IN (CONFIRMED) AND endsAt < now — cb.or is "
            + "NEVER invoked, unlike partition(PAST)'s nested OR/AND shape")
    void should_composeConfirmedAndElapsed_when_partitionIsAwaitingClosure() {
        Predicate confirmedIn = mock(Predicate.class);
        Predicate endsAtLt = mock(Predicate.class);
        Predicate anded = mock(Predicate.class);
        doReturn(confirmedIn).when(statusPath).in(EnumSet.of(BookingStatus.CONFIRMED));
        doReturn(endsAtLt).when(cb).lessThan(endsAtPath, NOW);
        doReturn(anded).when(cb).and(confirmedIn, endsAtLt);

        Predicate result = BookingSpecifications.partition(BookingPartition.AWAITING_CLOSURE, NOW)
                .toPredicate(root, query, cb);

        assertThat(result).isSameAs(anded);
        verify(statusPath).in(EnumSet.of(BookingStatus.CONFIRMED));
        verify(cb).lessThan(endsAtPath, NOW);
        verify(cb).and(confirmedIn, endsAtLt);
        verify(cb, never()).or(any(Predicate.class), any(Predicate.class));
    }
}
