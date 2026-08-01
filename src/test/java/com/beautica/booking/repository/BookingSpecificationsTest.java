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
 * Predicate-SHAPE unit tests for the Phase 28.1 additions to {@link BookingSpecifications}:
 * {@link BookingSpecifications#endsAtOnOrAfter}, {@link BookingSpecifications#endsAtBefore}, and
 * {@link BookingSpecifications#partition}.
 *
 * <p>Pure Mockito-mocked {@code jakarta.persistence.criteria} objects — no Spring context, no
 * database (Anti-Bug §M-1, mirroring {@code BookingRepositoryCustomImplUnpagedTest}'s "plain
 * JUnit, no context" posture). These tests pin WHICH {@link CriteriaBuilder} calls happen and
 * WITH WHAT operands — never actual generated SQL, which {@code BookingMyBookingsPartitionIT}
 * (full HTTP stack over real Postgres) covers separately.
 */
@DisplayName("BookingSpecifications — Phase 28.1 partition predicate shape")
class BookingSpecificationsTest {

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

    // ── endsAtOnOrAfter / endsAtBefore — the two single-bound building blocks ──────────────────

    @Test
    @DisplayName("endsAtOnOrAfter emits cb.greaterThanOrEqualTo(endsAt, now)")
    void should_emitGreaterThanOrEqualOnEndsAt_when_endsAtOnOrAfterApplied() {
        Predicate marker = mock(Predicate.class);
        doReturn(marker).when(cb).greaterThanOrEqualTo(endsAtPath, NOW);

        Predicate result = BookingSpecifications.endsAtOnOrAfter(NOW).toPredicate(root, query, cb);

        assertThat(result).isSameAs(marker);
        verify(cb).greaterThanOrEqualTo(endsAtPath, NOW);
    }

    @Test
    @DisplayName("endsAtBefore emits cb.lessThan(endsAt, now) — the strict half of the half-open boundary")
    void should_emitLessThanOnEndsAt_when_endsAtBeforeApplied() {
        Predicate marker = mock(Predicate.class);
        doReturn(marker).when(cb).lessThan(endsAtPath, NOW);

        Predicate result = BookingSpecifications.endsAtBefore(NOW).toPredicate(root, query, cb);

        assertThat(result).isSameAs(marker);
        verify(cb).lessThan(endsAtPath, NOW);
    }

    // ── partition(UPCOMING) — AND only, never cb.or ─────────────────────────────────────────────

    @Test
    @DisplayName("partition(UPCOMING) is status IN (CONFIRMED) AND endsAt>=now — no cb.or")
    void should_composeConfirmedAndNotYetElapsed_when_partitionIsUpcoming() {
        Predicate confirmedIn = mock(Predicate.class);
        Predicate endsAtGe = mock(Predicate.class);
        Predicate anded = mock(Predicate.class);
        doReturn(confirmedIn).when(statusPath).in(EnumSet.of(BookingStatus.CONFIRMED));
        doReturn(endsAtGe).when(cb).greaterThanOrEqualTo(endsAtPath, NOW);
        doReturn(anded).when(cb).and(confirmedIn, endsAtGe);

        Predicate result = BookingSpecifications.partition(BookingPartition.UPCOMING, NOW)
                .toPredicate(root, query, cb);

        assertThat(result).isSameAs(anded);
        verify(statusPath).in(EnumSet.of(BookingStatus.CONFIRMED));
        verify(cb).greaterThanOrEqualTo(endsAtPath, NOW);
        verify(cb, never()).or(any(Predicate.class), any(Predicate.class));
    }

    // ── partition(PAST) — the codebase's first cb.or(...), plus the Phase 28.4 planner-hint AND ──

    @Test
    @DisplayName("partition(PAST) is statusIn(COMPLETED,NOT_COMPLETED,CONFIRMED) AND "
            + "((COMPLETED OR NOT_COMPLETED) OR (CONFIRMED AND endsAt<now)) — the outer AND is the "
            + "Phase 28.4 index-matching hint (see BookingSpecifications#partition's javadoc), the "
            + "inner OR is unchanged")
    void should_composeClosedOrElapsedUnclosed_when_partitionIsPast() {
        Predicate closedIn = mock(Predicate.class);
        Predicate confirmedIn = mock(Predicate.class);
        Predicate endsAtLt = mock(Predicate.class);
        Predicate elapsedAnded = mock(Predicate.class);
        Predicate ored = mock(Predicate.class);
        Predicate eligibleStatusesIn = mock(Predicate.class);
        Predicate outerAnded = mock(Predicate.class);
        doReturn(closedIn).when(statusPath)
                .in(EnumSet.of(BookingStatus.COMPLETED, BookingStatus.NOT_COMPLETED));
        doReturn(confirmedIn).when(statusPath).in(EnumSet.of(BookingStatus.CONFIRMED));
        doReturn(eligibleStatusesIn).when(statusPath).in(EnumSet.of(
                BookingStatus.COMPLETED, BookingStatus.NOT_COMPLETED, BookingStatus.CONFIRMED));
        doReturn(endsAtLt).when(cb).lessThan(endsAtPath, NOW);
        doReturn(elapsedAnded).when(cb).and(confirmedIn, endsAtLt);
        doReturn(ored).when(cb).or(closedIn, elapsedAnded);
        doReturn(outerAnded).when(cb).and(eligibleStatusesIn, ored);

        Predicate result = BookingSpecifications.partition(BookingPartition.PAST, NOW)
                .toPredicate(root, query, cb);

        assertThat(result)
                .as("the outer AND is a no-op query-planner hint (every OR disjunct already "
                        + "implies one of the three statuses) that lets Postgres match "
                        + "idx_bookings_master_past_partition_starts_at (V130) without proving "
                        + "implication through the nested OR/AND shape — see the migration + "
                        + "BookingSpecifications#partition javadoc")
                .isSameAs(outerAnded);
        // The Option-A hole guarantee — a COMPLETED/NOT_COMPLETED booking lands in PAST
        // unconditionally — is pinned by the verify() below: cb.or() must be invoked with
        // `closedIn` ITSELF (the bare statusIn(COMPLETED, NOT_COMPLETED) predicate, stubbed from a
        // single un-wrapped statusPath.in(...) call) as an operand, never a further-conditioned
        // predicate. If a future change ANDed an endsAt bound onto the closed-statuses branch, that
        // branch would stop being the exact `closedIn` mock instance, this stub would go unmatched,
        // cb.or(...) would fall through to Mockito's default null, and `result` would fail to equal
        // `outerAnded` above — so that assertion is the actual proof; do not rely on this comment
        // alone if editing either branch.
        verify(statusPath).in(EnumSet.of(BookingStatus.COMPLETED, BookingStatus.NOT_COMPLETED));
        verify(statusPath).in(EnumSet.of(BookingStatus.CONFIRMED));
        verify(statusPath).in(EnumSet.of(
                BookingStatus.COMPLETED, BookingStatus.NOT_COMPLETED, BookingStatus.CONFIRMED));
        verify(cb).lessThan(endsAtPath, NOW);
        verify(cb).and(confirmedIn, endsAtLt);
        verify(cb).or(closedIn, elapsedAnded);
        verify(cb).and(eligibleStatusesIn, ored);
    }

    // ── partition(CANCELLED) — a plain statusIn, no AND, no OR ──────────────────────────────────

    @Test
    @DisplayName("partition(CANCELLED) is status IN (CANCELLED, DECLINED) — no cb.and, no cb.or, no endsAt touch")
    void should_composePlainStatusIn_when_partitionIsCancelled() {
        Predicate marker = mock(Predicate.class);
        doReturn(marker).when(statusPath).in(EnumSet.of(BookingStatus.CANCELLED, BookingStatus.DECLINED));

        Predicate result = BookingSpecifications.partition(BookingPartition.CANCELLED, NOW)
                .toPredicate(root, query, cb);

        assertThat(result).isSameAs(marker);
        verify(statusPath).in(EnumSet.of(BookingStatus.CANCELLED, BookingStatus.DECLINED));
        verify(cb, never()).and(any(Predicate.class), any(Predicate.class));
        verify(cb, never()).or(any(Predicate.class), any(Predicate.class));
    }
}
