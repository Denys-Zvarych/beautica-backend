package com.beautica.booking.domain;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.OffsetDateTime;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for Phase 29.1's {@link BookingClosureRule} — the single canonical definition of
 * "awaiting closure". Mirrors {@code BookingSpecificationsTest}'s posture (Mockito-mocked {@code
 * jakarta.persistence.criteria} objects for the {@link org.springframework.data.jpa.domain.Specification}
 * half, plain calls for the in-memory boolean half) — plain JUnit 5, no Spring, no database.
 *
 * <p>{@code BookingSpecificationsTest} itself is a protected backwards-compatibility suite for
 * this track and is deliberately left UNEDITED (its own {@code should_composeClosedOrElapsedUnclosed_when_partitionIsPast}
 * test is the mechanical guard proving {@code BookingSpecifications#partition}'s {@code PAST} arm
 * still delegates correctly to {@link #awaitingClosure(OffsetDateTime)} — see this class's own
 * shape test below for the {@code AWAITING_CLOSURE} arm, added in
 * {@code BookingSpecificationsAwaitingClosureTest} instead of touching the protected file).
 */
@DisplayName("BookingClosureRule — Phase 29.1 canonical rule")
class BookingClosureRuleTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-01T12:00:00Z");

    // ── isAwaitingClosure — the in-memory form, 15-cell truth table ─────────────────────────────

    @Test
    @DisplayName("isAwaitingClosure — exactly one cell of the 5 statuses x 3 endsAt-positions "
            + "matrix is TRUE: (CONFIRMED, endsAt < now)")
    void should_returnTrueOnlyForConfirmedAndElapsed_when_evaluatingFullTruthTable() {
        OffsetDateTime elapsed = NOW.minusMinutes(1);
        OffsetDateTime boundary = NOW;
        OffsetDateTime future = NOW.plusMinutes(1);

        for (BookingStatus status : BookingStatus.values()) {
            for (OffsetDateTime endsAt : new OffsetDateTime[] {elapsed, boundary, future}) {
                boolean expected = status == BookingStatus.CONFIRMED && endsAt.equals(elapsed);
                boolean actual = BookingClosureRule.isAwaitingClosure(status, endsAt, NOW);

                assertThat(actual)
                        .as("status=%s, endsAt=%s (relative to now=%s)", status, endsAt, NOW)
                        .isEqualTo(expected);
            }
        }
    }

    @Test
    @DisplayName("isAwaitingClosure(CONFIRMED, elapsed) is TRUE")
    void should_returnTrue_when_confirmedAndElapsed() {
        assertThat(BookingClosureRule.isAwaitingClosure(
                BookingStatus.CONFIRMED, NOW.minusMinutes(1), NOW)).isTrue();
    }

    @Test
    @DisplayName("isAwaitingClosure(CONFIRMED, endsAt == now) is FALSE — strict '<', half-open boundary")
    void should_returnFalse_when_confirmedAndEndsAtEqualsNow() {
        assertThat(BookingClosureRule.isAwaitingClosure(BookingStatus.CONFIRMED, NOW, NOW)).isFalse();
    }

    @Test
    @DisplayName("isAwaitingClosure(CONFIRMED, future) is FALSE")
    void should_returnFalse_when_confirmedAndNotYetElapsed() {
        assertThat(BookingClosureRule.isAwaitingClosure(
                BookingStatus.CONFIRMED, NOW.plusMinutes(1), NOW)).isFalse();
    }

    @Test
    @DisplayName("isAwaitingClosure(COMPLETED, future endsAt) is FALSE — the Option-A hole: "
            + "closed is closed, regardless of endsAt")
    void should_returnFalse_when_completedWithFutureEndsAt() {
        assertThat(BookingClosureRule.isAwaitingClosure(
                BookingStatus.COMPLETED, NOW.plusYears(1), NOW)).isFalse();
    }

    @Test
    @DisplayName("isAwaitingClosure(COMPLETED, elapsed endsAt) is FALSE too — CLOSED carries no "
            + "endsAt condition at all")
    void should_returnFalse_when_completedWithElapsedEndsAt() {
        assertThat(BookingClosureRule.isAwaitingClosure(
                BookingStatus.COMPLETED, NOW.minusYears(1), NOW)).isFalse();
    }

    // ── isProviderReviewEligible — full-enum truth table (fix: PROVIDER(master)→CLIENT review ────
    // ── gate must require COMPLETED strictly; a CONFIRMED-but-elapsed booking must NOT qualify) ──

    @ParameterizedTest
    @EnumSource(BookingStatus.class)
    @DisplayName("isProviderReviewEligible — true iff status == COMPLETED; every other current or "
            + "future BookingStatus value must be false. Parameterised over the full enum on "
            + "purpose: a new status added later is picked up automatically instead of silently "
            + "defaulting to untested")
    void should_returnTrueOnlyForCompleted_when_evaluatingProviderReviewEligibilityAcrossAllStatuses(
            BookingStatus status) {
        boolean expected = status == BookingStatus.COMPLETED;

        assertThat(BookingClosureRule.isProviderReviewEligible(status))
                .as("status=%s", status)
                .isEqualTo(expected);
    }

    // ── the asymmetry itself — the load-bearing fact a future "consistency cleanup" would erase ──

    @Test
    @DisplayName("the CLIENT->PROVIDER and PROVIDER->CLIENT eligibility rules DISAGREE on the same "
            + "elapsed-but-unclosed CONFIRMED booking, on purpose — isReviewEligible admits it "
            + "(true), isProviderReviewEligible rejects it (false). Pinned so a future reader "
            + "cannot 'consistency-clean' isProviderReviewEligible into delegating to "
            + "isReviewEligible without this test going red — see BookingClosureRule"
            + "#isProviderReviewEligible's javadoc, which explicitly warns against that collapse")
    void should_disagreeOnElapsedUnclosedConfirmedBooking_when_comparingClientAndProviderEligibility() {
        OffsetDateTime elapsedEndsAt = NOW.minusMinutes(1);

        assertThat(BookingClosureRule.isReviewEligible(BookingStatus.CONFIRMED, elapsedEndsAt, NOW))
                .as("CLIENT->PROVIDER direction: an elapsed-but-unclosed CONFIRMED booking must "
                        + "stay reviewable by the client — the client has no lever over whether "
                        + "the provider ever closes the booking out")
                .isTrue();
        assertThat(BookingClosureRule.isProviderReviewEligible(BookingStatus.CONFIRMED))
                .as("PROVIDER->CLIENT direction: the SAME booking shape must NOT be reviewable by "
                        + "the provider — closing the booking is the provider's own action, and "
                        + "rating the client before performing it is exactly the bug this fix closes")
                .isFalse();
    }

    // ── awaitingClosure — the Criteria Specification form ───────────────────────────────────────

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
    @DisplayName("awaitingClosure(now) is status IN (CONFIRMED) AND endsAt < now — cb.or is NEVER "
            + "invoked; this is a plain conjunction, not a nested OR/AND")
    void should_composeConfirmedAndElapsed_when_buildingAwaitingClosureSpecification() {
        Predicate confirmedIn = mock(Predicate.class);
        Predicate endsAtLt = mock(Predicate.class);
        Predicate anded = mock(Predicate.class);
        doReturn(confirmedIn).when(statusPath).in(EnumSet.of(BookingStatus.CONFIRMED));
        doReturn(endsAtLt).when(cb).lessThan(endsAtPath, NOW);
        doReturn(anded).when(cb).and(confirmedIn, endsAtLt);

        Predicate result = BookingClosureRule.awaitingClosure(NOW).toPredicate(root, query, cb);

        assertThat(result).isSameAs(anded);
        verify(statusPath).in(EnumSet.of(BookingStatus.CONFIRMED));
        verify(cb).lessThan(endsAtPath, NOW);
        verify(cb).and(confirmedIn, endsAtLt);
        verify(cb, never()).or(any(Predicate.class), any(Predicate.class));
    }
}
