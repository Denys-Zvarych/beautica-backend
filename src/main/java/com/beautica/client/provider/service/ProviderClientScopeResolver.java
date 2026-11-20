package com.beautica.client.provider.service;

import com.beautica.auth.Role;
import com.beautica.client.provider.service.ClientScope.MasterScope;
import com.beautica.client.provider.service.ClientScope.SalonScope;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * B507 (D2) — role to {@link ClientScope}. The caller's salon access ({@code @authz.canManageSalon}) is already
 * checked on the controller; this resolves only what the list reads.
 * <ul>
 *   <li>salon endpoint: the salon, optionally one of ITS masters (any other master id is 404, no oracle);</li>
 *   <li>{@code /masters/me}: SALON_MASTER / INDEPENDENT_MASTER with an ACTIVE master row, same row
 *       {@code GET /masters/me/calendar} resolves ({@code findActiveByUserIdWithUserAndSalon}); a deactivated or
 *       missing row, or any other role (the owner's master mode uses the salon endpoint with {@code masterId}), is
 *       403.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class ProviderClientScopeResolver {

    private final MasterRepository masterRepository;

    @Transactional(readOnly = true)
    public ClientScope forSalon(UUID salonId, @Nullable UUID masterId) {
        if (masterId != null && !masterRepository.existsByIdAndSalonId(masterId, salonId)) {
            throw new NotFoundException("Master not found");
        }
        return new SalonScope(salonId, masterId);
    }

    @Transactional(readOnly = true)
    public ClientScope forMasterMe(UUID actorId, Role role) {
        if (role != Role.SALON_MASTER && role != Role.INDEPENDENT_MASTER) {
            throw new ForbiddenException("Access denied");
        }
        Master master = masterRepository.findActiveByUserIdWithUserAndSalon(actorId)
                .orElseThrow(() -> new ForbiddenException("Access denied"));
        return new MasterScope(master.getId(), master.getSalon() != null ? master.getSalon().getId() : null);
    }
}
