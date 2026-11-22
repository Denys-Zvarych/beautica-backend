package com.beautica.moderation.service;

import com.beautica.common.exception.BookingUnavailableException;
import com.beautica.common.security.PhoneHasher;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Phase 398 — the booking-create gate for provider-to-client blocks (phase 397). One call per create path:
 * <ul>
 *   <li>APP (CLIENT account) — {@code BookingService#doCreateBooking} and
 *       {@code AppointmentService#doCreateAppointment} → {@link #requireClientMayBook}: CLIENT rows by user id
 *       (D1);</li>
 *   <li>LINK guest — {@code GuestBookingService#createFor} (master link + salon link, single service and visit)
 *       → {@link #requireGuestMayBook}: GUEST rows by the HMAC of the OTP-verified token phone (D2).</li>
 * </ul>
 * B527 (D7) — one person, one answer: a CLIENT account counts as blocked when its account row OR the phone-hash row of its
 * VERIFIED phone is blocked ({@link ClientBlockService#isClientBlocked}); a LINK guest whose phone is a client account's
 * verified phone is first resolved to that account by {@code GuestBookingService} and refused through
 * {@link #requireClientMayBook} AND {@link #requireGuestMayBook} (either refuses, MOD-29). STAFF walk-ins never reach
 * this gate (D8, MOD-28).
 *
 * <p><b>Scope comes from the TARGET master, never the caller</b> (397 re-audit): a master with a salon is booked in
 * that salon's scope (every salon master, the owner-as-master included); a master without one is an independent
 * master's own scope. This mirrors {@code bookings.salon_id = master.getSalon()} set by every create path and the
 * scope {@link ClientBlockScopeResolver#forBooking} writes the block under.
 *
 * <p>Refusal = {@link BookingUnavailableException} (409 {@code BOOKING_UNAVAILABLE}, neutral wording). Costs one
 * {@code LIMIT 1} indexed id probe per create. Neither the phone nor its hash is logged.
 */
@Component
@RequiredArgsConstructor
public class BookingBlockGuard {

    private final ClientBlockService clientBlockService;
    private final PhoneHasher phoneHasher;

    /** The block scope a booking with this master lands in. */
    public static BlockScope targetScopeOf(Master master) {
        Salon salon = master.getSalon();
        return salon != null ? BlockScope.salon(salon.getId()) : BlockScope.master(master.getId());
    }

    /** D1 + B527 D7 — refuses a booking when the client's account (or its verified-phone hash) is blocked in the target's scope. */
    public void requireClientMayBook(Master target, User client) {
        BlockScope scope = targetScopeOf(target);
        if (clientBlockService.isClientBlocked(scope, client)) {
            throw unavailable(scope);
        }
    }

    /**
     * D2 — refuses a LINK guest booking when the OTP-verified phone is blocked in the target's scope. A phone that
     * does not normalise was never hashable, so it can carry no GUEST block (lenient, never a new 400 here).
     */
    public void requireGuestMayBook(Master target, String verifiedPhone) {
        BlockScope scope = targetScopeOf(target);
        boolean blocked = phoneHasher.tryHash(verifiedPhone)
                .map(phone -> clientBlockService.isGuestPhoneBlocked(scope, phone))
                .orElse(false);
        if (blocked) {
            throw unavailable(scope);
        }
    }

    private static BookingUnavailableException unavailable(BlockScope scope) {
        return scope.salonId() != null
                ? BookingUnavailableException.forSalon()
                : BookingUnavailableException.forMaster();
    }
}
