package com.beautica.booking.domain;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingSpecifications;
import org.springframework.data.jpa.domain.Specification;

import java.time.OffsetDateTime;
import java.util.EnumSet;

/**
 * Phase 29.1 — the SINGLE canonical, read-time-derived definition of "an elapsed {@code
 * CONFIRMED} booking is awaiting provider closure". No {@code @Scheduled} job ever transitions
 * such a booking to a terminal state — terminal transitions are explicit provider actions ({@code
 * PATCH /bookings/{id}/complete} / {@code /not-complete}, or the client/provider {@code /cancel}
 * / {@code /decline}) per the locked track 24.x state machine — so this rule exists purely to
 * answer "has the provider not yet closed this out?" at READ time. It NEVER mutates {@code
 * bookings.status}; there is no sweeper, no backfill, and this class must never grow a write
 * path.
 *
 * <p><b>The canonical rule (verbatim; this text is the source of truth):</b>
 *
 * <pre>
 * CLOSED           iff status &#8712; {COMPLETED, NOT_COMPLETED}
 * CANCELLED        iff status &#8712; {CANCELLED, DECLINED}
 * OPEN             iff status = CONFIRMED
 *
 * ELAPSED          iff ends_at &lt; now          (absolute instant, half-open, strict &lt;)
 * AWAITING_CLOSURE iff OPEN &#8743; ELAPSED         i.e. status = CONFIRMED AND ends_at &lt; now
 * </pre>
 *
 * <p>Three invariants carried forward verbatim from Phase 28.1 ({@link
 * BookingSpecifications#partition}) and restated here because this class makes them
 * platform-wide rather than partition-local:
 *
 * <ol>
 *   <li><b>Absolute-instant comparison, never a calendar-day comparison.</b> {@link
 *       com.beautica.common.TimeZones#KYIV} MUST NOT appear anywhere in the closure rule. Kyiv
 *       governs day-granular {@code from}/{@code to} params and wall-clock display, nothing else.
 *       Conflating instant-ordering with Kyiv-day derivation is this repo's live bug class.
 *   <li><b>"Now" is {@code clock.instant()}</b>, resolved once per request in {@code
 *       BookingService} and converted via {@code clock.instant().atOffset(ZoneOffset.UTC)} purely
 *       as the fixed-offset representation needed to bind against the {@code OffsetDateTime}-typed
 *       {@code endsAt} Criteria path.
 *   <li><b>Half-open boundary, strict {@code <}.</b> {@code ends_at == now} is <b>not</b> awaiting
 *       closure (it is {@code UPCOMING}, per Phase 28.1's {@code endsAt >= now} for {@code
 *       UPCOMING}). Disjoint and total by construction.
 * </ol>
 *
 * <p><b>{@code ends_at}, not {@code starts_at} — the Option-A hole stays open on purpose.</b>
 * {@code BookingTemporalGuard.assertElapsedForComplete} (Phase 27.1) gates <i>completion</i> on
 * {@code startsAt}, so a {@code COMPLETED} row can carry a FUTURE {@code endsAt}. That row is
 * {@code CLOSED} and therefore NOT {@code AWAITING_CLOSURE}, regardless of {@code endsAt} — the
 * {@code CLOSED} branch above carries no {@code endsAt} condition at all, exactly as {@code
 * BookingSpecifications#partition}'s {@code PAST} arm's {@code COMPLETED}/{@code NOT_COMPLETED}
 * leg does not. Do not "tidy" this into a single {@code elapsed AND status} conjunction — that
 * shape silently drops rows.
 *
 * <p><b>An unavoidable duality, not a duplication.</b> This class deliberately carries BOTH
 * representations the platform needs:
 *
 * <ul>
 *   <li>{@link #awaitingClosure(OffsetDateTime)} — the Criteria {@link Specification} form, for a
 *       list filter ({@code BookingSpecifications#partition}'s {@code PAST}/{@code
 *       AWAITING_CLOSURE} arms, {@code BookingService#getUnclosedCount}).
 *   <li>{@link #isAwaitingClosure(BookingStatus, OffsetDateTime, OffsetDateTime)} — the in-memory
 *       boolean form, for a single already-loaded entity/projection (the {@code awaitingClosure}
 *       response DTO field, Phase 29.2).
 * </ul>
 *
 * A SQL predicate cannot be evaluated inside a mapper and a Java boolean cannot be pushed into a
 * {@code WHERE} clause. Confining both forms to one file, adjacent, under one javadoc, with {@code
 * BookingClosureRuleEquivalenceIT} proving they agree over a full status &times; boundary matrix,
 * is the strongest available guarantee that they cannot drift apart. A hand-rolled {@code
 * CONFIRMED}-and-{@code endsAt}-vs-now comparison anywhere else in the codebase is a defect, not a
 * shortcut.
 */
public final class BookingClosureRule {

    private BookingClosureRule() {
    }

    /**
     * The Criteria form — {@code status = CONFIRMED AND ends_at < :now} — delegating to the
     * existing {@link BookingSpecifications} primitives rather than re-writing them. Composed by
     * {@link BookingSpecifications#partition} (the {@code PAST} branch's second OR-leg, and the
     * standalone {@code AWAITING_CLOSURE} arm) and by {@code BookingService#getUnclosedCount}.
     *
     * @param now an already-resolved absolute instant ({@code clock.instant()} as a fixed-offset
     *            {@link OffsetDateTime}) — never {@link com.beautica.common.TimeZones#KYIV}-zoned,
     *            never re-derived here.
     */
    public static Specification<Booking> awaitingClosure(OffsetDateTime now) {
        return BookingSpecifications.statusIn(EnumSet.of(BookingStatus.CONFIRMED))
                .and(BookingSpecifications.endsAtBefore(now));
    }

    /**
     * The in-memory form, for a single already-loaded entity/projection — needed by DTO mapping
     * (the {@code awaitingClosure} response flag, Phase 29.2), which cannot push a {@link
     * Specification} into a serializer.
     *
     * @param status the booking's current status
     * @param endsAt the booking's {@code endsAt} — an absolute instant ({@link OffsetDateTime}),
     *               never re-derived from {@code startsAt + duration} at read time
     * @param now    an already-resolved absolute instant, same contract as {@link
     *               #awaitingClosure(OffsetDateTime)}'s {@code now}
     */
    public static boolean isAwaitingClosure(BookingStatus status, OffsetDateTime endsAt, OffsetDateTime now) {
        return status == BookingStatus.CONFIRMED && endsAt.isBefore(now);
    }

    /**
     * The STATUS+TIME half of client review eligibility — locked product decision (see
     * {@code docs/backend-phases}, the "Past tab disagrees with review eligibility" fix): a
     * booking/visit that entered the client's "Past" tab BY ELAPSED TIME must be reviewable even
     * when the provider never closed it out. Before this rule, review eligibility was {@code
     * status == COMPLETED} only, while "Past" ({@link BookingSpecifications#partition}'s {@code
     * PAST} arm) is {@code COMPLETED OR NOT_COMPLETED OR (CONFIRMED AND elapsed)} — the mismatch
     * meant an unclosed {@code CONFIRMED} booking could sit in Past forever and never be
     * reviewable, since {@link #isAwaitingClosure} (Phase 29.1) intentionally never mutates
     * status and no {@code @Scheduled} sweep ever will (see this class's own header javadoc).
     *
     * <p>{@code COMPLETED} (closed) OR {@code isAwaitingClosure} (still nominally open but its
     * window already elapsed) — exactly the same two-disjunct shape as the {@code PAST} partition
     * arm's {@code COMPLETED}/{@code NOT_COMPLETED} vs {@code CONFIRMED}-and-elapsed split, reusing
     * {@link #isAwaitingClosure} rather than re-deriving {@code status == CONFIRMED &&
     * endsAt.isBefore(now)} a second time.
     *
     * <p><b>Deliberately NOT reviewable, and this predicate must never be widened to include
     * them:</b> {@code NOT_COMPLETED} (an EXPLICIT provider no-show marking, not a time-elapsed
     * state — it reaches Past by provider action, never by clock alone), {@code CANCELLED},
     * {@code DECLINED}.
     *
     * <p><b>This is the STATUS+TIME half only.</b> The HAS-CLIENT and NO-EXISTING-REVIEW checks
     * are review-domain concerns, not closure-domain ones, and stay in the caller ({@code
     * BookingService#canReview}, {@code ReviewService#createReview}) — every one of those call
     * sites composes {@code hasClient && !reviewExists && isReviewEligible(...)}, so this method
     * is the single canonical definition shared between the read-side {@code canReview} DTO flag
     * and the write-side gate, and the two can never disagree.
     *
     * @param status the booking's current status
     * @param endsAt the absolute-instant end of the booking
     * @param now    an already-resolved absolute instant, same contract as {@link
     *               #isAwaitingClosure}'s {@code now}
     */
    public static boolean isReviewEligible(BookingStatus status, OffsetDateTime endsAt, OffsetDateTime now) {
        return status == BookingStatus.COMPLETED || isAwaitingClosure(status, endsAt, now);
    }
}
