package com.beautica.common.security;

import com.beautica.auth.Role;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component("authz")
@RequiredArgsConstructor
public class AuthorizationService {

    private final SalonRepository salonRepository;
    private final MasterRepository masterRepository;
    private final UserRepository userRepository;
    private final ServiceRepository serviceRepository;

    /**
     * Returns true when actorId has management access to the given salon.
     * Grants access to SALON_OWNER (by ownership) and SALON_ADMIN (by salon assignment).
     *
     * Use for: update, invite, schedule management operations.
     * Do NOT use for: delete/deactivate or admin-invite operations — those must also
     * check hasRole('SALON_OWNER') at the call site (e.g., @PreAuthorize annotation).
     */
    public boolean hasManagementAccess(UUID salonId, UUID actorId) {
        if (salonId == null) return false;
        User actor = userRepository.findById(actorId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        return switch (actor.getRole()) {
            case SALON_OWNER -> salonRepository.existsByIdAndOwnerId(salonId, actorId);
            case SALON_ADMIN -> salonId.equals(actor.getSalonId());
            default -> false;
        };
    }

    public boolean isOwnerOf(UUID salonId, UUID actorId) {
        if (salonId == null) return false;
        return salonRepository.existsByIdAndOwnerId(salonId, actorId);
    }

    /**
     * Role-aware fast path: if the JWT-derived role cannot possibly grant salon management
     * access (i.e. it is not SALON_OWNER or SALON_ADMIN), return false immediately without
     * any DB round-trip. Only SALON_OWNER and SALON_ADMIN proceed to the ownership query.
     */
    public boolean canManageSalon(Authentication auth, UUID salonId) {
        if (salonId == null) return false;
        boolean mayManage = auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_SALON_OWNER")
                        || a.getAuthority().equals("ROLE_SALON_ADMIN"));
        if (!mayManage) return false;
        UUID actorId = principalId(auth);
        return hasManagementAccess(salonId, actorId);
    }

    public boolean canManageMaster(Authentication auth, UUID masterId) {
        boolean hasSalonMasterRole = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SALON_MASTER"));
        if (hasSalonMasterRole) {
            return false;
        }
        UUID actorId = principalId(auth);
        return masterRepository.findByIdWithSalonAndOwner(masterId).map(m -> {
            if (m.getMasterType() == MasterType.INDEPENDENT_MASTER) {
                return m.getUser().getId().equals(actorId);
            }
            return m.getSalon() != null && hasManagementAccess(m.getSalon().getId(), actorId);
        }).orElse(false);
    }

    /**
     * Role-aware fast path: CLIENT and SALON_MASTER roles can never manage a schedule,
     * so return false immediately without any DB round-trip. Only SALON_OWNER, SALON_ADMIN,
     * and INDEPENDENT_MASTER proceed to the master ownership query.
     */
    public boolean canManageMasterSchedule(Authentication auth, UUID masterId) {
        boolean cannotManage = auth.getAuthorities().stream().anyMatch(a ->
                a.getAuthority().equals("ROLE_SALON_MASTER")
                        || a.getAuthority().equals("ROLE_CLIENT"));
        if (cannotManage) return false;
        UUID actorId = principalId(auth);
        return masterRepository.findByIdWithSalonAndOwner(masterId).map(m -> {
            if (m.getMasterType() == MasterType.INDEPENDENT_MASTER) {
                return m.getUser().getId().equals(actorId);
            }
            return m.getSalon() != null && hasManagementAccess(m.getSalon().getId(), actorId);
        }).orElse(false);
    }

    public void enforceCanManageSalon(UUID actorId, Salon salon) {
        if (!hasManagementAccess(salon.getId(), actorId)) {
            throw new ForbiddenException("Access denied");
        }
    }

    public void enforceCanManageMaster(UUID actorId, Master master) {
        boolean allowed = master.getMasterType() == MasterType.INDEPENDENT_MASTER
                ? master.getUser().getId().equals(actorId)
                : master.getSalon() != null && hasManagementAccess(master.getSalon().getId(), actorId);
        if (!allowed) {
            throw new ForbiddenException("Access denied");
        }
    }

    public void enforceCanManageMasterSchedule(UUID actorId, Master master) {
        boolean allowed = master.getMasterType() == MasterType.INDEPENDENT_MASTER
                ? master.getUser().getId().equals(actorId)
                : master.getSalon() != null && hasManagementAccess(master.getSalon().getId(), actorId);
        if (!allowed) {
            throw new ForbiddenException("Access denied");
        }
    }

    /**
     * Returns true iff the given master is a member of the given salon.
     * Used in @PreAuthorize on assignServiceToMaster to prevent a timing-oracle
     * IDOR where a caller with a valid token for Salon B could probe whether a
     * master UUID belongs to Salon A by observing 403 vs 404 responses.
     *
     * Returns false immediately when either argument is null.
     */
    public boolean masterBelongsToSalon(UUID masterId, UUID salonId) {
        if (masterId == null || salonId == null) return false;
        return masterRepository.existsByIdAndSalonId(masterId, salonId);
    }

    /**
     * Returns true iff the authenticated actor owns the parent entity of the given
     * ServiceDefinition:
     *   ownerType == SALON              → actor must own the salon (ownerId is salonId)
     *   ownerType == INDEPENDENT_MASTER → actor must be the master's own user (ownerId is masterId)
     *
     * Returns false — causing 403 — when the service definition does not exist.
     *
     * A single JPQL projection query resolves the owner's user UUID directly,
     * eliminating the two-query chain used previously.
     */
    public boolean canManageServiceDefinition(Authentication auth, UUID serviceDefId) {
        UUID actorId = principalId(auth);
        return serviceRepository.findOwnerUserId(serviceDefId)
                .map(ownerUserId -> ownerUserId.equals(actorId))
                .orElse(false);
    }

    private UUID principalId(Authentication auth) {
        // JwtAuthenticationFilter sets the UUID as authentication.getDetails()
        // and the email string as the principal.
        return (UUID) auth.getDetails();
    }
}
