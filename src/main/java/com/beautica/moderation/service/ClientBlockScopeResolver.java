package com.beautica.moderation.service;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.common.security.ActorSalonAssignmentMemo;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Phase 397 D1 — who may block, and in which scope.
 * <ul>
 *   <li>{@code INDEPENDENT_MASTER} → their own master row, only for a booking they perform;</li>
 *   <li>{@code SALON_OWNER} / {@code SALON_ADMIN} → the whole salon: the booking's own salon snapshot
 *       ({@code bookings.salon_id}), which must be active and which the caller owns or is the assigned admin
 *       of;</li>
 *   <li>every other role → no scope.</li>
 * </ul>
 * The booking form is free for an owner or an independent master (in-memory id reads off the full graph) and
 * costs at most the request-memoised admin-assignment projection for an admin.
 */
@Component
@RequiredArgsConstructor
public class ClientBlockScopeResolver {

    private final MasterRepository masterRepository;
    private final SalonRepository salonRepository;
    private final UserRepository userRepository;
    private final ActorSalonAssignmentMemo actorSalonAssignmentMemo;

    /** Every scope an actor's blocks may live in — the list/unblock form of the rule. */
    public record ActorScopes(UUID masterId, List<UUID> salonIds) {

        public static final ActorScopes NONE = new ActorScopes(null, List.of());

        public ActorScopes {
            salonIds = List.copyOf(salonIds);
        }
    }

    public static boolean isBlockerRole(Role role) {
        return role == Role.INDEPENDENT_MASTER || role == Role.SALON_OWNER || role == Role.SALON_ADMIN;
    }

    /** The scope a block on this booking lands in, or empty when the booking is outside the actor's scope. */
    public Optional<BlockScope> forBooking(UUID actorId, Role role, Booking booking) {
        if (role == Role.INDEPENDENT_MASTER) {
            return independentMasterScope(actorId, booking.getMaster());
        }
        if (role == Role.SALON_OWNER || role == Role.SALON_ADMIN) {
            Salon salon = booking.getSalon();
            if (salon != null && managesSalon(actorId, role, salon)) {
                return Optional.of(BlockScope.salon(salon.getId()));
            }
        }
        return Optional.empty();
    }

    public ActorScopes forActor(UUID actorId, Role role) {
        return switch (role) {
            case INDEPENDENT_MASTER -> masterRepository.findIdByUserId(actorId)
                    .map(id -> new ActorScopes(id, List.of())).orElse(ActorScopes.NONE);
            case SALON_OWNER -> new ActorScopes(null, salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId));
            case SALON_ADMIN -> adminSalonId(actorId)
                    .map(id -> new ActorScopes(null, List.of(id))).orElse(ActorScopes.NONE);
            default -> ActorScopes.NONE;
        };
    }

    private static Optional<BlockScope> independentMasterScope(UUID actorId, Master master) {
        User user = master.getUser();
        boolean own = master.getMasterType() == MasterType.INDEPENDENT_MASTER
                && user != null && user.getId().equals(actorId);
        return own ? Optional.of(BlockScope.master(master.getId())) : Optional.empty();
    }

    /** An inactive (deleted) salon is no block scope for anyone — same as {@link #forActor}'s active-only list. */
    private boolean managesSalon(UUID actorId, Role role, Salon salon) {
        if (!salon.isActive()) {
            return false;
        }
        if (role == Role.SALON_OWNER) {
            return salon.getOwner() != null && salon.getOwner().getId().equals(actorId);
        }
        return adminSalonId(actorId).map(salon.getId()::equals).orElse(false);
    }

    private Optional<UUID> adminSalonId(UUID actorId) {
        return actorSalonAssignmentMemo.salonIdOf(actorId, () -> userRepository.findSalonIdById(actorId));
    }
}
