package com.beautica.booking.service;

import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.PostLockSlotCheck;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Post-advisory-lock re-check for every booking-CREATE path — closes the Phase 337 QA CRITICAL
 * race (a create that passes the pre-lock bookability check, then waits on the master's advisory
 * lock while a concurrent {@code StaffAccountSelfDeletionService} self-delete holds it, must not go
 * on to insert a {@code CONFIRMED} booking for the now-detached master once the lock is granted) AND
 * the overlap re-check the GIST EXCLUDE constraint backstops, in ONE round trip instead of two
 * (Phase 337 follow-up, LOW perf).
 *
 * <p><b>Formerly two statements, now one.</b> This class used to be {@code MasterBookabilityGuard},
 * calling {@code MasterRepository#isBookableFresh} and leaving the caller to run a separate
 * {@code BookingRepository#existsOverlap} afterwards. Both queries key off the same {@code masterId},
 * run in the same transaction at the same point in the lock window, and have no caller that needs one
 * without the other — so {@link BookingRepository#findPostLockBookabilityAndOverlap} now answers both
 * questions in a single native query, and this class turns the pair of booleans into the 404-vs-409
 * decision.
 *
 * <p><b>Why re-fetching the {@code Master} entity would silently do nothing</b> (the bookability
 * half). The pre-lock {@code Master} is already MANAGED in this transaction's persistence context.
 * Any {@code MasterRepository} finder that returns an entity for an id already in the identity map is
 * served the SAME Java object with its stale pre-lock {@code isActive}/{@code salon} snapshot,
 * regardless of what a concurrent, already-committed transaction wrote — no second SELECT is even
 * issued. {@link BookingRepository#findPostLockBookabilityAndOverlap} sidesteps this by returning a
 * plain projection that is never associated with any entity identity, so it always reflects the row's
 * current state in the database as seen by this transaction's session.
 *
 * <p><b>Why a static utility, not a bean.</b> Same shape as {@link BookingSlotLockGuard} and its
 * package siblings ({@code BookingSlotAvailabilityGuard}, {@code BookingTemporalGuard}) — a
 * package-private final class with a static method taking the caller's own already-injected
 * {@link BookingRepository} as a plain parameter needs no bean of its own, and adds no edge to the
 * bean graph.
 *
 * <p><b>Bookability is checked FIRST.</b> A gone-or-inactive master is reported as 404 even when the
 * requested window also happens to overlap an existing booking — the two facts arrive together in one
 * row, but the ORDER the two {@code if}s below run in is what preserves "no such master" as
 * indistinguishable from "this master stopped being bookable while queued for the lock", matching the
 * existing "Master not found or inactive" oracle-avoidance discipline documented on every pre-lock
 * filter site.
 *
 * <p><b>Keep the pre-lock check.</b> This is not a replacement for {@code MasterBookability.isBookable}
 * at the top of each create method — that fast-fails an already-unbookable master before any lock
 * contention, sparing every other request queued on a popular master's lock. This class is the
 * AUTHORITY; the pre-lock check is purely an optimisation on top of it.
 */
final class PostLockSlotGuard {

    private PostLockSlotGuard() {
    }

    /**
     * Re-validates {@code masterId}'s bookability AND the requested {@code [startsAt, endsAt)}
     * window's freedom from a {@code CONFIRMED} overlap, against the live database row, to be called
     * immediately after the per-master advisory lock is acquired and before any insert.
     *
     * @throws NotFoundException when the master row no longer exists, is no longer bookable
     *                            (deactivated directly, or detached by a concurrent self-delete that
     *                            won the race for this same lock), or the {@code master_bookable}
     *                            column came back SQL {@code NULL} — checked FIRST, and fail-closed
     *                            on the null case (see {@link PostLockSlotCheck}'s boxed-{@code Boolean}
     *                            javadoc)
     * @throws BusinessException (409) when the master is bookable but the window collides with an
     *                            existing {@code CONFIRMED} booking, or {@code overlap_exists} came
     *                            back {@code NULL} — an {@code EXISTS (...)} subquery is never
     *                            SQL-nullable in practice, but a {@code null} here still fails closed
     *                            as "assume a conflict"
     */
    static void assertStillFreeAfterLock(BookingRepository bookingRepository, UUID masterId,
                                          OffsetDateTime startsAt, OffsetDateTime endsAt) {
        PostLockSlotCheck check = bookingRepository
                .findPostLockBookabilityAndOverlap(masterId, startsAt, endsAt)
                .orElse(null);
        Boolean masterBookable = check == null ? null : check.getMasterBookable();
        if (!Boolean.TRUE.equals(masterBookable)) {
            throw new NotFoundException("Master not found or inactive");
        }
        Boolean overlapExists = check.getOverlapExists();
        if (!Boolean.FALSE.equals(overlapExists)) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }
    }
}
