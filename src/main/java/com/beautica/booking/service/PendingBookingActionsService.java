package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.domain.BookingClosureRule;
import com.beautica.booking.dto.PendingBookingActionsCountResponse;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.BookingSpecifications;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Phase 357 — the «Архів» badge count: bookings that still need a provider action, as exactly two
 * fixed {@code COUNT(*)} queries (no entity loading, no cache).
 *
 * <ul>
 *   <li><b>toClose</b> — {@link BookingClosureRule#awaitingClosure} (CONFIRMED, {@code ends_at < now}).</li>
 *   <li><b>toRateClient</b> — {@link BookingClosureRule#awaitingProviderClientReview}, the SQL twin of
 *       {@code BookingService#computeProviderCanReviewClient}.</li>
 * </ul>
 *
 * <p>Scope resolution reuses {@link BookingService}'s own master-scope resolvers so this can never
 * drift from {@code GET /bookings/me}. Salon scope applies BOTH {@code b.salon = salon} and {@code
 * b.master.salon = salon}: the authority kernel ({@code BookingCompletionAccess}) reads the
 * MASTER's live salon, so a master rotated elsewhere drops out — the complete gate would deny it.
 */
@Service
@RequiredArgsConstructor
public class PendingBookingActionsService {

    private final BookingRepository bookingRepository;
    private final BookingService bookingService;

    /**
     * {@code INDEPENDENT_MASTER} (own row) or {@code SALON_OWNER} with {@code asMaster=true} (own
     * owner-master row). Every other combination is 403 — a salon master can neither complete nor
     * rate, owner/admin use the salon endpoint.
     */
    @Transactional(readOnly = true)
    public PendingBookingActionsCountResponse countForMe(UUID actorId, Authentication auth, boolean asMaster) {
        Role role = AuthenticationUtils.role(auth);
        Master master = switch (role) {
            case INDEPENDENT_MASTER -> bookingService.resolveProviderMasterScope(role, actorId);
            case SALON_OWNER -> {
                if (!asMaster) {
                    throw new ForbiddenException("Access denied");
                }
                yield bookingService.resolveOwnerMasterScope(actorId);
            }
            default -> throw new ForbiddenException("Access denied");
        };
        Specification<Booking> scope = BookingSpecifications.masterIdEquals(master.getId());
        // Mirrors AuthorizationService#isLiveIfIndependent: a deactivated independent master cannot rate.
        boolean mayRate = master.getMasterType() != MasterType.INDEPENDENT_MASTER || master.isActive();
        return count(scope, mayRate);
    }

    /** Caller must already have passed the controller's {@code canManageSalon} gate (owner/admin only). */
    @Transactional(readOnly = true)
    public PendingBookingActionsCountResponse countForSalon(UUID salonId) {
        Specification<Booking> scope = BookingSpecifications.bookingSalonIdEquals(salonId)
                .and(BookingSpecifications.salonIdIn(List.of(salonId)));
        return count(scope, true);
    }

    private PendingBookingActionsCountResponse count(Specification<Booking> scope, boolean mayRate) {
        OffsetDateTime now = bookingService.resolveNow();
        long toClose = bookingRepository.count(scope.and(BookingClosureRule.awaitingClosure(now)));
        long toRate = mayRate
                ? bookingRepository.count(scope.and(BookingClosureRule.awaitingProviderClientReview()))
                : 0L;
        return PendingBookingActionsCountResponse.of(toClose, toRate);
    }
}
