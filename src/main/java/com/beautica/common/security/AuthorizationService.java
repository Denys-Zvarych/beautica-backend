package com.beautica.common.security;

import com.beautica.common.exception.ForbiddenException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component("authz")
@RequiredArgsConstructor
public class AuthorizationService {

    private final SalonRepository salonRepository;
    private final MasterRepository masterRepository;

    public boolean canManageSalon(Authentication auth, UUID salonId) {
        UUID actorId = principalId(auth);
        return salonRepository.findById(salonId)
                .map(s -> s.getOwner().getId().equals(actorId))
                .orElse(false);
    }

    public boolean canManageMasterSchedule(Authentication auth, UUID masterId) {
        boolean hasSalonMasterRole = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SALON_MASTER"));
        if (hasSalonMasterRole) {
            return false;
        }
        UUID actorId = principalId(auth);
        return masterRepository.findById(masterId).map(m -> {
            if (m.getMasterType() == MasterType.INDEPENDENT_MASTER) {
                return m.getUser().getId().equals(actorId);
            }
            return m.getSalon() != null && m.getSalon().getOwner().getId().equals(actorId);
        }).orElse(false);
    }

    public void enforceCanManageSalon(UUID actorId, Salon salon) {
        if (!salon.getOwner().getId().equals(actorId)) {
            throw new ForbiddenException("Access denied");
        }
    }

    public void enforceCanManageMaster(UUID actorId, Master master) {
        boolean allowed = master.getMasterType() == MasterType.INDEPENDENT_MASTER
                ? master.getUser().getId().equals(actorId)
                : master.getSalon() != null && master.getSalon().getOwner().getId().equals(actorId);
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
