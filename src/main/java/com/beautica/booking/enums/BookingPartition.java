package com.beautica.booking.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Time-based partition for {@code GET /bookings/me?partition=} (Phase 28.1). Unlike {@code
 * status}, which filters by {@link BookingStatus} alone, {@code partition} also considers WHETHER
 * the booking's window has elapsed — because no scheduled job ever transitions an elapsed {@link
 * BookingStatus#CONFIRMED} booking to {@code COMPLETED}/{@code NOT_COMPLETED}; that only happens
 * via an explicit provider action ({@code PATCH /bookings/{id}/complete} or {@code
 * /not-complete}). Left unclosed, such a booking would otherwise sit at the top of a client's
 * "upcoming" list forever and never appear in "past" — this partition closes that gap.
 *
 * <pre>
 * UPCOMING    CONFIRMED                          AND ends_at &gt;= now
 * PAST        COMPLETED OR NOT_COMPLETED         (regardless of ends_at)
 *             OR (CONFIRMED                      AND ends_at &lt;  now)
 * CANCELLED   CANCELLED OR DECLINED              (regardless of ends_at)
 * </pre>
 *
 * <p><b>Total, disjoint cover.</b> Every {@link BookingStatus} value is assigned to EXACTLY one
 * partition branch above — {@code CONFIRMED} splits across {@code UPCOMING}/{@code PAST} on the
 * {@code ends_at} boundary, the other four statuses each belong to exactly one partition
 * unconditionally. {@code count(UPCOMING) + count(PAST) + count(CANCELLED) == count(unfiltered)}
 * for any booking set — see {@code BookingMyBookingsPartitionIT}'s total-cover test.
 *
 * <p>See {@link com.beautica.booking.repository.BookingSpecifications#partition} for the DB
 * predicate this enum drives, including the clock/timezone invariants that predicate MUST honour.
 *
 * <p><b>Phase 29.3 — {@code AWAITING_CLOSURE} is an explicit SUB-VIEW of {@code PAST}, not a
 * fourth member of the cover.</b> This is the single thing a future reader will get wrong:
 *
 * <pre>
 * The TOTAL, DISJOINT COVER is and remains exactly three members:
 *
 *     UPCOMING  &#8746;  PAST  &#8746;  CANCELLED  =  all bookings,  pairwise disjoint
 *     count(UPCOMING) + count(PAST) + count(CANCELLED) == count(unfiltered)
 *
 * AWAITING_CLOSURE is a NAMED SUBSET OF PAST, not a fourth member:
 *
 *     AWAITING_CLOSURE  &#8834;  PAST
 *     AWAITING_CLOSURE  &#8745;  UPCOMING   = &#8709;
 *     AWAITING_CLOSURE  &#8745;  CANCELLED  = &#8709;
 *
 * Including AWAITING_CLOSURE in a cover sum DOUBLE-COUNTS every row in it.
 * </pre>
 *
 * <p>Concretely: {@code PAST} = {@code (COMPLETED} &#8746; {@code NOT_COMPLETED)} &#8846; {@code
 * AWAITING_CLOSURE} — the second OR-leg of {@code partition(PAST)} <b>is</b> {@code
 * AWAITING_CLOSURE} (see {@link com.beautica.booking.domain.BookingClosureRule}, the single
 * canonical definition both arms delegate to); this constant merely gives that leg a name a
 * caller can request on its own via {@code ?partition=AWAITING_CLOSURE}.
 *
 * <p>{@link #COVER} is the total, disjoint cover — {@code {UPCOMING, PAST, CANCELLED}}. Every
 * cover-sum assertion, existing or future, MUST iterate {@link #COVER}, never {@link #values()}:
 * a cover assertion written as {@code Arrays.stream(BookingPartition.values())} would silently
 * double-count {@code AWAITING_CLOSURE} rows the moment this constant landed, so the enum itself
 * carries the guard rather than relying on reviewers to notice. {@link #isCoverMember()} is the
 * per-value form of the same guard.
 */
public enum BookingPartition {
    UPCOMING,
    PAST,
    CANCELLED,
    AWAITING_CLOSURE;

    /**
     * The total, disjoint cover — {@code {UPCOMING, PAST, CANCELLED}}. See this enum's class
     * javadoc. Immutable; iterate this for any cover-sum assertion, never {@link #values()}.
     */
    public static final Set<BookingPartition> COVER =
            Set.copyOf(EnumSet.of(UPCOMING, PAST, CANCELLED));

    /**
     * {@code true} for {@link #UPCOMING}/{@link #PAST}/{@link #CANCELLED} — the three members of
     * the total, disjoint cover ({@link #COVER}). {@code false} only for {@link
     * #AWAITING_CLOSURE}, which is a named subset of {@link #PAST}, not a fourth cover member.
     */
    public boolean isCoverMember() {
        return COVER.contains(this);
    }
}
