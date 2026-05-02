package com.beautica.common.security;

import com.beautica.auth.Role;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
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
            case SALON_ADMIN -> masterRepository.existsBySalonIdAndUserIdAndIsActiveTrue(salonId, actorId);
            default -> false;
        };
    }

    public boolean isOwnerOf(UUID salonId, UUID actorId) {
        if (salonId == null) return false;
        return salonRepository.existsByIdAndOwnerId(salonId, actorId);
    }

    public boolean canManageSalon(Authentication auth, UUID salonId) {
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

    public boolean canManageMasterSchedule(Authentication auth, UUID masterId) {
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

    private UUID principalId(Authentication auth) {
        // JwtAuthenticationFilter sets the UUID as authentication.getDetails()
        // and the email string as the principal.
        return (UUID) auth.getDetails();
    }
}
