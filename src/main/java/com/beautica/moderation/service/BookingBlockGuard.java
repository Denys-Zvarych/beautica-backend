package com.beautica.moderation.service;

import com.beautica.common.exception.BookingUnavailableException;
import com.beautica.common.security.PhoneHasher;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Phase 398 — the booking-create gate for provider-to-client blocks (phase 397). One call per create path:
 * <ul>
 *   <li>APP (CLIENT account) — {@code BookingService#doCreateBooking} and
 *       {@code AppointmentService#doCreateAppointment} → {@link #requireClientMayBook}: CLIENT rows by user id
 *       (D1);</li>
 *   <li>LINK guest — {@code GuestBookingService#createFor} (master link + salon link, single service and visit)
 *       → {@link #requireGuestMayBook}: GUEST rows by the HMAC of the OTP-verified token phone (D2).</li>
 * </ul>
 * No cross-type match (D2): a CLIENT block never refuses a guest booking and a GUEST block never refuses an APP
 * booking. STAFF walk-ins never reach this gate (D8).
 *
 * <p><b>Scope comes from the TARGET master, never the caller</b> (397 re-audit): a master with a salon is booked in
 * that salon's scope (every salon master, the owner-as-master included); a master without one is an independent
 * master's own scope. This mirrors {@code bookings.salon_id = master.getSalon()} set by every create path and the
 * scope {@link ClientBlockScopeResolver#forBooking} writes the block under.
 *
 * <p><b>Accepted residual (option A, 2026-10-28).</b> A blocked registered client can still book through a link as
 * a guest with an OTP-verified phone — identities are per type and the profile phone is never hashed. That is not a
 * bypass: the resulting guest booking is blockable with one tap, after which that phone is refused too.
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

    /** D1 — refuses an APP booking when the client's account is blocked in the target's scope. */
    public void requireClientMayBook(Master target, UUID clientId) {
        BlockScope scope = targetScopeOf(target);
        if (clientBlockService.isClientBlocked(scope, clientId)) {
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
