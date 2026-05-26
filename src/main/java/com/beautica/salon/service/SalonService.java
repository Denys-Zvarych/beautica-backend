package com.beautica.salon.service;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SalonService {

    private final SalonRepository salonRepository;
    private final UserRepository userRepository;
    private final InviteService inviteService;
    private final MasterRepository masterRepository;
    private final LocalityWriteValidator localityWriteValidator;
    private final MasterService masterService;
    private final CacheManager cacheManager;

    @Transactional
    public SalonResponse createSalon(UUID ownerId, CreateSalonRequest request) {
        var owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found: " + ownerId));

        if (owner.getRole() != Role.SALON_OWNER) {
            throw new ForbiddenException("Only SALON_OWNER may create a salon");
        }

        boolean isFirstSalon = !salonRepository.existsByOwnerId(owner.getId());

        var salon = Salon.builder()
                .owner(owner)
                .name(request.name())
                .description(request.description())
                .city(request.city())
                .region(request.region())
                .address(request.address())
                .cityId(request.cityId())
                .districtId(request.districtId())
                .street(request.street())
                .buildingNo(request.buildingNo())
                .locationNote(request.locationNote())
                .phone(request.phone())
                .instagramUrl(request.instagramUrl())
                .isActive(true)
                .isPrimary(isFirstSalon)
                .build();

        Salon savedSalon = salonRepository.save(salon);

        // Phase 10.3: validate locality when cityId is provided (nullable on creation —
        // the mobile flow always sends it, but the field is optional for backwards compat).
        // Sync location to owner's User row so /users/me reflects the salon address.
        // userRepository.save is intentionally scoped inside this guard: when no structured
        // location is provided there is nothing to sync, and the multi-salon test asserts
        // that save(owner) is never called unconditionally.
        if (request.cityId() != null) {
            localityWriteValidator.validateProviderLocality(request.toLocalityInput());
            owner.setCityId(request.cityId());
            owner.setDistrictId(request.districtId());
            owner.setStreet(request.street());
            owner.setBuildingNo(request.buildingNo());
            owner.setLocationNote(request.locationNote());
            userRepository.save(owner);
        }

        // Evict ownerSalons cache after commit so a concurrent reader cannot repopulate
        // with stale data inside the commit window (Anti-Bug Playbook §F rule 2).
        evictOwnerSalonsCacheAfterCommit(ownerId);

        // Auto-create the owner's SALON_OWNER-type master profile on first-salon creation.
        // Passes already-loaded entities to avoid redundant DB round-trips (Finding 3/4).
        // Runs inside the same @Transactional boundary: if master creation fails, the
        // salon row is rolled back too (atomicity per Phase 12.2 architecture decision).
        if (isFirstSalon) {
            masterService.createMasterForOwner(owner, savedSalon);
        }

        return SalonResponse.from(savedSalon);
    }

    // Eviction helpers are registered as post-commit callbacks rather than via @CacheEvict.
    // @CacheEvict fires before the transaction commits, allowing a concurrent reader
    // to repopulate the cache with stale data within the commit window (Anti-Bug §F rule 2).

    private void evictOwnerSalonsCacheAfterCommit(UUID ownerId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Cache cache = cacheManager.getCache("ownerSalons");
                if (cache != null) {
                    cache.evict(ownerId);
                }
            }
        });
    }

    private void evictSalonDetailCacheAfterCommit(UUID salonId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Cache cache = cacheManager.getCache("salon-detail");
                if (cache != null) {
                    cache.evict(salonId);
                }
            }
        });
    }

    /**
     * Evicts the entire {@code search:salons} cache after commit.
     *
     * <p>Blanket eviction (not per-key) is intentional: search results are a filtered subset of
     * all active salons. When a salon is deactivated the cached page may contain it, and the only
     * safe invalidation strategy without re-querying is to drop the whole cache so the next
     * request rebuilds it from the DB. The cache TTL is short and this path is write-rare,
     * so thundering-herd risk is negligible (PERF-HIGH-2).</p>
     */
    private void evictSearchSalonsCacheAfterCommit() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Cache cache = cacheManager.getCache("search:salons");
                if (cache != null) {
                    cache.clear();
                }
            }
        });
    }

    @Transactional
    public SalonResponse updateSalon(UUID actorId, UUID salonId, UpdateSalonRequest request) {
        var salon = salonRepository.findById(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found: " + salonId));

        // Ownership already enforced by @PreAuthorize("... @authz.canManageSalon(...)") on
        // the controller — no redundant DB round-trip needed here.

        // Phase 10.6: a salon is a discoverable provider — its persisted
        // locality must satisfy the most-specific-node rule (city mandatory;
        // district mandatory iff the city has urban districts; district a child
        // of the city). The legacy free-text city/region/address are NO LONGER
        // written (kept nullable per Phase 10.3, no longer the source of truth).
        localityWriteValidator.validateProviderLocality(request.toLocalityInput());

        if (request.name() != null) {
            salon.setName(request.name());
        }
        if (request.description() != null) {
            salon.setDescription(request.description());
        }
        salon.setCityId(request.cityId());
        salon.setDistrictId(request.districtId());
        salon.setStreet(request.street());
        salon.setBuildingNo(request.buildingNo());
        salon.setLocationNote(request.locationNote());
        if (request.phone() != null) {
            salon.setPhone(request.phone());
        }
        if (request.instagramUrl() != null) {
            salon.setInstagramUrl(request.instagramUrl());
        }

        SalonResponse result = SalonResponse.from(salonRepository.save(salon));

        // Evict after commit so a concurrent reader cannot repopulate stale data within the
        // commit window. Replaces the @CacheEvict annotations that fired pre-commit (PERF-MEDIUM-2).
        evictOwnerSalonsCacheAfterCommit(actorId);
        evictSalonDetailCacheAfterCommit(salonId);

        return result;
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

        // Evict after commit — replaces pre-commit @CacheEvict annotations (PERF-MEDIUM-2).
        // Also evicts search:salons because a deactivated salon must not appear in discovery
        // results for the remaining TTL window (PERF-HIGH-2).
        evictOwnerSalonsCacheAfterCommit(ownerId);
        evictSalonDetailCacheAfterCommit(salonId);
        evictSearchSalonsCacheAfterCommit();
    }
}
