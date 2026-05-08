package com.beautica.salon.service;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
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

    @Transactional
    @CacheEvict(value = "ownerSalons", key = "#ownerId")
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

    @Caching(evict = {
            @CacheEvict(value = "ownerSalons", key = "#actorId"),
            @CacheEvict(value = "salon-detail", key = "#salonId")
    })
    @Transactional
    public SalonResponse updateSalon(UUID actorId, UUID salonId, UpdateSalonRequest request) {
        var salon = salonRepository.findById(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found: " + salonId));

        // Ownership already enforced by @PreAuthorize("... @authz.canManageSalon(...)") on
        // the controller — no redundant DB round-trip needed here.

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

        return SalonResponse.from(salonRepository.save(salon));
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "salon-detail", key = "#salonId")
    public Salon getSalonEntity(UUID salonId) {
        return salonRepository.findByIdAndIsActiveTrueWithOwner(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found: " + salonId));
    }

    @Transactional
    public InviteResponse inviteMaster(UUID actorId, UUID salonId, String email, Role role) {
        // Ownership already enforced by @PreAuthorize("... @authz.canManageSalon(...)") on
        // the controller — no redundant DB round-trip needed here.
        salonRepository.findById(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found: " + salonId));

        var inviteRequest = new InviteRequest(email, salonId, role);
        return inviteService.sendInvite(inviteRequest, actorId);
    }

    @Transactional(readOnly = true)
    public Page<MasterSummaryResponse> getMastersBySalon(UUID salonId, Pageable pageable) {
        return masterRepository.findBySalonIdAndIsActiveTrueWithUser(salonId, pageable)
                .map(MasterSummaryResponse::from);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "ownerSalons", key = "#ownerId")
    public List<SalonResponse> getOwnerSalons(UUID ownerId) {
        return salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId)
                .stream()
                .map(SalonResponse::from)
                .toList();
    }

    @Caching(evict = {
            @CacheEvict(value = "ownerSalons", key = "#ownerId"),
            @CacheEvict(value = "salon-detail", key = "#salonId")
    })
    @Transactional
    public void deactivateSalon(UUID ownerId, UUID salonId) {
        var caller = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found: " + ownerId));

        if (caller.getRole() != Role.SALON_OWNER) {
            throw new ForbiddenException("Only SALON_OWNER may deactivate a salon");
        }

        var salon = salonRepository.findByIdAndOwnerId(salonId, ownerId)
                .orElseThrow(() -> new NotFoundException("Salon not found or access denied"));

        salon.setActive(false);
        salonRepository.save(salon);
    }
}
