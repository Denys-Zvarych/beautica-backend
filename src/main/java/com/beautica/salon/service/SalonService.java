package com.beautica.salon.service;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SalonService {

    private final SalonRepository salonRepository;
    private final UserRepository userRepository;
    private final InviteService inviteService;
    private final MasterRepository masterRepository;
    private final AuthorizationService authorizationService;

    @Transactional
    public SalonResponse createSalon(UUID ownerId, CreateSalonRequest request) {
        var owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found: " + ownerId));

        if (owner.getRole() != Role.SALON_OWNER) {
            throw new ForbiddenException("Only SALON_OWNER may create a salon");
        }

        var salon = Salon.builder()
                .owner(owner)
                .name(request.name())
                .description(request.description())
                .city(request.city())
                .region(request.region())
                .address(request.address())
                .phone(request.phone())
                .instagramUrl(request.instagramUrl())
                .isActive(true)
                .build();

        return SalonResponse.from(salonRepository.save(salon));
    }

    @Transactional
    public SalonResponse updateSalon(UUID actorId, UUID salonId, UpdateSalonRequest request) {
        var salon = salonRepository.findById(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found: " + salonId));

        authorizationService.enforceCanManageSalon(actorId, salon);

        if (request.name() != null) {
            salon.setName(request.name());
        }
        if (request.description() != null) {
            salon.setDescription(request.description());
        }
        if (request.city() != null) {
            salon.setCity(request.city());
        }
        if (request.region() != null) {
            salon.setRegion(request.region());
        }
        if (request.address() != null) {
            salon.setAddress(request.address());
        }
        if (request.phone() != null) {
            salon.setPhone(request.phone());
        }
        if (request.instagramUrl() != null) {
            salon.setInstagramUrl(request.instagramUrl());
        }

        var saved = salonRepository.save(salon);
        return SalonResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public Salon getSalonEntity(UUID salonId) {
        return salonRepository.findById(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found: " + salonId));
    }

    @Transactional
    public InviteResponse inviteMaster(UUID actorId, UUID salonId, String email, Role role) {
        var salon = salonRepository.findById(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found: " + salonId));

        authorizationService.enforceCanManageSalon(actorId, salon);

        var inviteRequest = new InviteRequest(email, salonId, role);
        return inviteService.sendInvite(inviteRequest, actorId);
    }

    @Transactional(readOnly = true)
    public Page<MasterSummaryResponse> getMastersBySalon(UUID salonId, Pageable pageable) {
        return masterRepository.findBySalonIdAndIsActiveTrue(salonId, pageable)
                .map(MasterSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    public List<SalonResponse> getOwnerSalons(UUID ownerId) {
        return salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId)
                .stream()
                .map(SalonResponse::from)
                .toList();
    }

    @Transactional
    public void deactivateSalon(UUID ownerId, UUID salonId) {
        var caller = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found: " + ownerId));
        if (caller.getRole() != Role.SALON_OWNER) {
            throw new ForbiddenException("Only SALON_OWNER may deactivate a salon");
        }

        if (!salonRepository.existsByIdAndOwnerId(salonId, ownerId)) {
            throw new ForbiddenException("Access denied");
        }

        var salon = salonRepository.findById(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found: " + salonId));

        salon.setActive(false);
        salonRepository.save(salon);
    }
}
