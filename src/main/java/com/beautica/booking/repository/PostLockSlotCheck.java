package com.beautica.booking.repository;

/**
 * One-row native-query projection backing {@link BookingRepository#findPostLockBookabilityAndOverlap}
 * — the single post-advisory-lock statement every booking-CREATE path runs instead of the two it used
 * to (Phase 337 QA follow-up, LOW perf).
 *
 * <p><b>Why one statement instead of two.</b> The create paths used to run
 * {@code MasterRepository#isBookableFresh} (a scalar bookability re-check) immediately followed by
 * {@code BookingRepository#existsOverlap} (the overlap re-check), both AFTER the per-master advisory
 * lock and BEFORE the insert. Both queries key off the same {@code masterId}, run in the same
 * transaction, at the same point in the lock window, and are never used independently of each other on
 * any create path — so they are fused into one round trip here. The DB does the same total amount of
 * work (one index probe on {@code masters}/{@code salons}, one on {@code bookings}); only the JDBC
 * round-trip count drops, which is what the per-master advisory lock's HOLD TIME is billed on.
 *
 * <p>Getters follow Spring Data's native-query projection matching (column label, underscores and
 * case stripped):
 * <ul>
 *   <li>{@link #getMasterBookable()} → the {@code master_bookable} column — mirrors
 *       {@code MasterBookability.isBookable(Master)}'s predicate exactly: {@code is_active = true AND
 *       (salon_id IS NULL OR salon.is_active = true)}.</li>
 *   <li>{@link #getOverlapExists()} → the {@code overlap_exists} column — the same
 *       {@code status = 'CONFIRMED' AND starts_at < :end AND ends_at > :start} half-open-interval
 *       predicate the deleted {@code existsOverlap} query used.</li>
 * </ul>
 *
 * <p>An empty {@link java.util.Optional} from that finder means no {@code masters} row with the given
 * id exists at all (a hard delete rather than a detach) — the caller treats that the same as
 * {@code getMasterBookable() == false}.
 *
 * <p><b>Boxed {@link Boolean}, not primitive {@code boolean}.</b> {@code master_bookable} is
 * {@code m.is_active AND (m.salon_id IS NULL OR s.is_active)} over a {@code LEFT JOIN salons} — for an
 * independent master with no salon the right-hand side of the {@code OR} never runs (short-circuit,
 * matching the JPQL predicate this mirrors), but a driver or query-plan variant that DOES evaluate
 * {@code s.is_active} against the LEFT JOIN's all-NULL row would read SQL {@code NULL}, and Spring
 * Data's projection proxy would then try to unbox a primitive {@code boolean} getter and throw a
 * {@code NullPointerException} on an otherwise-successful create request. Boxing removes that failure
 * mode entirely; {@code PostLockSlotGuard} treats either getter returning {@code null} as fail-closed.
 */
public interface PostLockSlotCheck {

    Boolean getMasterBookable();

    Boolean getOverlapExists();
}
