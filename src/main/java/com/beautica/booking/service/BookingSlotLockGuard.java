package com.beautica.booking.service;

import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Shared double-book guard for the booking CREATE paths — the per-master advisory lock, the
 * {@code existsOverlap} check it makes atomic, and the {@code saveAndFlush} whose GIST
 * {@code no_overlapping_bookings} violation is the last line of defence behind both.
 *
 * <p><b>Why a static utility, not a bean.</b> Exactly the shape {@link BookingSlotAvailabilityGuard}
 * and {@link BookingTemporalGuard} already have in this package, and for the same reason: a package-
 * private final class with static methods that take the CALLER's own already-injected
 * {@link BookingRepository} as a plain parameter needs no bean of its own, so it can add no edge to
 * the bean graph (where {@code BookingService → AppointmentTransitionService} already exists and a
 * reverse edge would cycle).
 *
 * <p><b>Extracted in Phase 22.2</b> so {@code StaffBookingService} could not become a third
 * copy-paste of {@code GuestBookingService#persistBooking}'s body. The two now share one
 * implementation, so the lock variant, the 409 message and the constraint-violation translation
 * cannot drift between the LINK and STAFF create paths.
 *
 * <p><b>Why {@code BookingService#doCreateBooking} is NOT routed through
 * {@link #lockMasterAndAssertFree}.</b> That path takes the per-CLIENT advisory lock (salt 1) first,
 * and {@code BookingRepository#acquireClientAdvisoryLockWithTimeout} already fuses a
 * transaction-scoped {@code lock_timeout} that bounds every later lock wait in the same transaction
 * — including its master lock. It therefore deliberately uses the plain
 * {@code acquireAdvisoryLock(...)} and must keep doing so; re-arming a 3s {@code lock_timeout} on
 * the authenticated path would be a silent behaviour change on the system's hottest write. The
 * client-lock-free paths (guest/LINK and staff/STAFF) are the ones that need the fused-timeout
 * variant, and they are exactly the two callers here.
 */
final class BookingSlotLockGuard {

    private BookingSlotLockGuard() {
    }

    /**
     * Serialises this master's concurrent creates and rejects a window that collides with an
     * existing {@code CONFIRMED} booking.
     *
     * <p><b>Lock-wait bound.</b> {@link BookingRepository#acquireAdvisoryLockWithTimeout(UUID)}
     * fuses {@code set_config('lock_timeout', '3s', true)} into the SAME statement as the lock
     * acquisition, so the wait is bounded in one round trip rather than blocking indefinitely — the
     * advisory-lock DoS class documented on that repository method. It matters most for the guest
     * path ({@code permitAll}); the staff path inherits the same ceiling for free.
     *
     * <p>The lock is taken BEFORE the overlap read so check and insert are atomic — without it two
     * concurrent requests both read "free" and the GIST constraint decides the winner with a 500-
     * shaped {@code DataIntegrityViolationException} instead of a clean 409.
     *
     * <p>Callers must have already run the schedule-fit gate
     * ({@link BookingSlotAvailabilityGuard}) — an off-schedule request must never contend for the
     * lock every other client of a popular master is queued on.
     */
    static void lockMasterAndAssertFree(BookingRepository bookingRepository, UUID masterId,
                                        OffsetDateTime startsAt, OffsetDateTime endsAt) {
        Integer lock = bookingRepository.acquireAdvisoryLockWithTimeout(masterId);
        if (lock == null) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, "Advisory lock acquisition failed");
        }
        if (bookingRepository.existsOverlap(masterId, startsAt, endsAt)) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }
    }

    /**
     * {@code saveAndFlush} with the {@code no_overlapping_bookings} GIST EXCLUDE backstop translated
     * to the SAME {@code 409 "Slot not available"} {@link #lockMasterAndAssertFree} returns — so a
     * caller can never tell whether the application check or the database rejected it, and a race
     * that slips past the advisory lock still surfaces as a conflict rather than a 500.
     *
     * <p>The flush is what makes the constraint authoritative inside the transaction; deferring it
     * to commit would move the violation outside this translation.
     *
     * <p>Delegates to the list overload ({@link #saveOrConflict(BookingRepository, List)}) with a
     * single-element list — the ONE place that knows the constraint-violation → 409 mapping, kept
     * to one implementation rather than two (Phase 22.12). This signature is unchanged so every
     * existing single-row caller is untouched.
     */
    static Booking saveOrConflict(BookingRepository bookingRepository, Booking booking) {
        return saveOrConflict(bookingRepository, List.of(booking)).get(0);
    }

    /**
     * Multi-row counterpart of {@link #saveOrConflict(BookingRepository, Booking)} (Phase 22.12) —
     * {@code saveAll} + one {@code flush}, inside the same try/catch, so a whole chained visit is
     * saved and constraint-checked atomically: either every row survives the flush or none does, and
     * a GIST EXCLUDE violation on ANY row surfaces as the same {@code 409 "Slot not available"} the
     * single-row path returns. Never fork this method — {@link #saveOrConflict(BookingRepository, Booking)}
     * delegates here so the mapping cannot drift between the single-service and visit create paths.
     */
    static List<Booking> saveOrConflict(BookingRepository bookingRepository, List<Booking> bookings) {
        try {
            List<Booking> saved = bookingRepository.saveAll(bookings);
            bookingRepository.flush();
            return saved;
        } catch (DataIntegrityViolationException e) {
            throw new BusinessException(HttpStatus.CONFLICT, "Slot not available");
        }
    }
}
