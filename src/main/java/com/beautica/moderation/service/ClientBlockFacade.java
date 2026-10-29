package com.beautica.moderation.service;

import com.beautica.auth.Role;
import com.beautica.booking.service.BookingService;
import com.beautica.moderation.service.ClientBlockService.CreateBlockResult;
import com.beautica.moderation.service.ClientBlockService.NewBlock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Phase 398 (user decision 2026-10-28) — {@code POST /blocks} entry point: stores the block
 * ({@link ClientBlockService#block}) and, when THIS call created it, declines every future {@code CONFIRMED}
 * booking / visit of the blocked client in the block scope, in the same transaction.
 *
 * <ul>
 *   <li>registered client → by account id; LINK guest → by the OTP-verified phone (canonical E.164, the pre-image of
 *       the block's {@code phone_hash}) — per-type identity, never cross-matched;</li>
 *   <li>independent-master scope → that master; salon scope → every master of the salon;</li>
 *   <li>{@code DECLINED} / no provider comment through the shared provider-decline cascade
 *       ({@link BookingService#declineFutureConfirmedForBlockedClient} /
 *       {@link BookingService#declineFutureConfirmedForBlockedGuest}): neutral provider-cancel notice (email + push
 *       for an app client, SMS for a guest), slots freed after commit. Started / past / completed bookings are
 *       untouched;</li>
 *   <li>{@code cancelledBookings} counts VISITS (orchestrator decision 2026-10-28): a standalone booking = 1, a
 *       multi-service appointment = 1 however many items it had;</li>
 *   <li>the performing master gets the same in-app / push {@code BOOKING_DECLINED} an ordinary provider decline
 *       sends them (orchestrator decision 2026-10-28) — unless they are the actor; no copy mentions the block;</li>
 *   <li>an idempotent repeat (200) cancels nothing — it already happened when the block was created.</li>
 * </ul>
 *
 * <p>A facade rather than a {@link ClientBlockService} dependency: {@link BookingService} already depends on
 * {@link ClientBlockService} (booking-detail block state, create gate), so the reverse edge would be a bean cycle.
 */
@Service
@RequiredArgsConstructor
public class ClientBlockFacade {

    private final ClientBlockService clientBlockService;
    private final BookingService bookingService;

    @Transactional
    public CreateBlockResult block(UUID actorId, Role role, UUID bookingId) {
        CreateBlockResult result = clientBlockService.block(actorId, role, bookingId);
        if (!result.created() || result.newBlock() == null) {
            return result;
        }
        int cancelled = cancelFutureBookings(actorId, result.newBlock());
        return new CreateBlockResult(result.block().withCancelledBookings(cancelled), true, result.newBlock());
    }

    private int cancelFutureBookings(UUID actorId, NewBlock newBlock) {
        BlockScope scope = newBlock.scope();
        if (newBlock.clientId() != null) {
            return bookingService.declineFutureConfirmedForBlockedClient(
                    actorId, scope.masterId(), scope.salonId(), newBlock.clientId());
        }
        return bookingService.declineFutureConfirmedForBlockedGuest(
                actorId, scope.masterId(), scope.salonId(), newBlock.guestPhone());
    }
}
