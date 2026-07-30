package com.beautica.salon.service;

import org.springframework.data.domain.Sort;
import java.util.Set;
import com.beautica.common.web.SortWhitelist;
import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.SalonAdminResponse;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.search.service.SearchCacheNames;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

@Slf4j
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
    private final AuthorizationService authorizationService;

    @Transactional
    public SalonResponse createSalon(UUID ownerId, CreateSalonRequest request) {
        var owner = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found: " + ownerId));

        if (owner.getRole() != Role.SALON_OWNER) {
            throw new ForbiddenException("Only SALON_OWNER may create a salon");
        }

        boolean isFirstSalon = !salonRepository.existsByOwnerId(owner.getId());

        // Phase 12.1: a salon is a discoverable provider — its locality must satisfy the
        // most-specific-node rule before anything is persisted. Validate UNCONDITIONALLY
        // (mirroring updateSalon): an absent cityId is itself a violation (CITY_REQUIRED),
        // so the previous cityId != null guard let an invalid/absent locality slip through
        // at creation only to be blocked later at update. Runs before save() so nothing is
        // persisted on rejection.
        localityWriteValidator.validateProviderLocality(request.toLocalityInput());

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
                .instagramUrl(normalizeInstagram(request.instagramUrl()))
                .isActive(true)
                .isPrimary(isFirstSalon)
                .build();

        Salon savedSalon = salonRepository.save(salon);

        // Phase 10.3: sync location to owner's User row so /users/me reflects the salon
        // address. Locality validation already ran unconditionally above (Phase 12.1) —
        // this guard now only governs the User-row sync. userRepository.save is intentionally
        // scoped inside the guard: when no structured location is provided there is nothing to
        // sync, and the multi-salon test asserts that save(owner) is never called unconditionally.
        if (request.cityId() != null) {
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
     * Evicts <b>both halves</b> of the salon discovery cache after commit —
     * {@code search:salons:browse} and {@code search:salons:q}.
     *
     * <p>Iterates {@link SearchCacheNames#SALONS_ALL} rather than naming the caches
     * inline: the population split (browse vs free-text) is a caching-layer concern
     * and clearing only one half would leave the deactivated salon reachable through
     * the other, which no test on this path would notice.</p>
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
                for (String cacheName : SearchCacheNames.SALONS_ALL) {
                    Cache cache = cacheManager.getCache(cacheName);
                    if (cache != null) {
                        cache.clear();
                    }
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
            salon.setInstagramUrl(normalizeInstagram(request.instagramUrl()));
        }

        // `salon` was loaded via findById in THIS @Transactional, so it is a managed entity —
        // Hibernate dirty-checking flushes the setter mutations on commit. The explicit save()
        // was a redundant no-op write (PERF-LOW); save() returned the same managed instance, so
        // mapping the in-memory `salon` is equivalent. The findById load is retained (existence).
        SalonResponse result = SalonResponse.from(salon);

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

    /**
     * Properties a caller may sort a salon's public master list by. Shares a repository method
     * (and therefore a {@code Master} root with a {@code JOIN FETCH}ed {@code user}) with
     * {@code MasterService#getMastersByPage}, so the two whitelists are deliberately identical —
     * an unguarded dotted sort like {@code user.passwordHash} would otherwise resolve here and
     * order rows by a credential hash (see {@link SortWhitelist}).
     */
    private static final Set<String> SORTABLE_MASTER_PROPERTIES =
            Set.of("avgRating", "reviewCount", "createdAt");

    /** Applied when the caller supplies no {@code sort}; the query itself has no {@code ORDER BY}. */
    private static final Sort DEFAULT_MASTER_SORT = Sort.by(Sort.Direction.DESC, "avgRating");

    /** Mandatory unique trailing column, so OFFSET paging cannot duplicate or skip tied rows. */
    private static final Sort MASTER_ID_TIEBREAKER = Sort.by(Sort.Direction.ASC, "id");

    @Transactional(readOnly = true)
    public Page<MasterSummaryResponse> getMastersBySalon(UUID salonId, Pageable pageable) {
        Pageable safePageable = SortWhitelist.apply(
                pageable, SORTABLE_MASTER_PROPERTIES, DEFAULT_MASTER_SORT, MASTER_ID_TIEBREAKER);
        return masterRepository.findBySalonIdAndIsActiveTrueWithUser(salonId, safePageable)
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

        // `salon` was loaded via findByIdAndOwnerId in THIS @Transactional, so it is a managed
        // entity — Hibernate dirty-checking flushes the isActive mutation on commit. The explicit
        // save() was a redundant no-op write (PERF-LOW). The findByIdAndOwnerId load is retained:
        // it enforces existence + ownership scoping and cannot be dropped.
        salon.setActive(false);

        // Evict after commit — replaces pre-commit @CacheEvict annotations (PERF-MEDIUM-2).
        // Also evicts search:salons because a deactivated salon must not appear in discovery
        // results for the remaining TTL window (PERF-HIGH-2).
        evictOwnerSalonsCacheAfterCommit(ownerId);
        evictSalonDetailCacheAfterCommit(salonId);
        evictSearchSalonsCacheAfterCommit();
    }

    /**
     * Unassigns a {@code SALON_ADMIN} from a salon (Phase 21.2). {@code actorId} is a
     * SALON_OWNER acting on any salon they own, or a SALON_ADMIN acting on their own salon —
     * both halves already enforced by {@code @PreAuthorize} on the controller
     * ({@code canManageSalon} for salon scoping, {@code adminBelongsToSalon} for confirming
     * {@code userId} is actually an admin of {@code salonId}).
     *
     * <p>This only clears the admin's {@code salon_id} — it is NOT an account deactivation.
     * {@code role} stays {@code SALON_ADMIN} and {@code isActive} stays {@code true}, so the
     * user can be invited to (and reassigned to) a salon again later.
     *
     * <p>Removing the last admin from a salon is intentionally unguarded: owner access is
     * ownership-based, never admin-count-based, so a salon is never left "unmanageable."
     *
     * @throws NotFoundException   if {@code userId} does not resolve to a user
     * @throws ForbiddenException  if the reloaded user is not a SALON_ADMIN assigned to
     *                             {@code salonId} (defense-in-depth re-check of the
     *                             {@code @PreAuthorize} gate — see {@link
     *                             com.beautica.common.security.AuthorizationService#adminBelongsToSalon}),
     *                             or if the actor attempts to remove themselves
     */
    @Transactional
    public void removeAdmin(UUID actorId, UUID salonId, UUID userId) {
        if (actorId.equals(userId)) {
            throw new ForbiddenException("Cannot remove yourself — ask another admin or the owner");
        }

        var admin = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        // Defense-in-depth: redundant with @authz.adminBelongsToSalon on the controller,
        // but matches the existing pattern of service-layer re-validation (e.g.
        // enforceCanManageMaster) rather than trusting the SpEL gate alone.
        if (admin.getRole() != Role.SALON_ADMIN || !salonId.equals(admin.getSalonId())) {
            throw new ForbiddenException("User is not an admin of this salon");
        }

        // `admin` was loaded via findById in THIS @Transactional, so it is a managed entity —
        // Hibernate dirty-checking flushes the salonId mutation on commit (mirrors
        // deactivateSalon/updateSalon — no redundant explicit save()).
        admin.setSalonId(null);
    }

    /**
     * Rotates (reassigns) a {@code SALON_ADMIN} from {@code salonId} to
     * {@code destinationSalonId} (Phase 21.3). {@code actorId} is a SALON_OWNER acting on any
     * salon they own, or a SALON_ADMIN acting on their own salon — both halves already enforced
     * by {@code @PreAuthorize} on the controller ({@code canManageSalon} for source-salon
     * scoping, {@code adminBelongsToSalon} for confirming {@code userId} is actually an admin of
     * {@code salonId}).
     *
     * <p>The destination salon MUST belong to the SAME owner as the source salon — an admin has
     * no visibility into other owners' salons, and this must never allow a cross-owner transfer.
     * That check is NOT expressible in the controller's {@code @PreAuthorize} SpEL (it depends
     * on the request body, not a path variable), so it is enforced here via
     * {@link AuthorizationService#salonsShareOwner}.
     *
     * <p>Destination validation (Perf MEDIUM-4 / Sec LOW-1): the destination's active-flag is
     * checked via the lightweight {@link SalonRepository#existsByIdAndIsActiveTrue} projection —
     * no full {@code Salon} entity is ever loaded here, since only the raw
     * {@code destinationSalonId} UUID is written onto {@code User.salonId}. All three destination
     * denial reasons — cross-owner, inactive, and not-found — collapse to the SAME
     * {@link ForbiddenException} (403). Distinguishing them via status code would let an actor
     * with legitimate authority over the source salon probe an arbitrary owner's full salon
     * portfolio (including inactive salons a public endpoint would 404 on) by observing
     * 404 vs 403 vs 400 responses. The same-salon no-op check stays a distinct 400 — the actor
     * already knows the source salon's identity, so it carries no oracle risk.
     *
     * @throws NotFoundException  if {@code userId} does not resolve to a user
     * @throws ForbiddenException if the reloaded user is not a SALON_ADMIN assigned to
     *                            {@code salonId} (defense-in-depth re-check of the
     *                            {@code @PreAuthorize} gate), or if the destination salon does not
     *                            exist, is not owned by the same owner as the source salon, or is
     *                            inactive (soft-deleted)
     * @throws BusinessException  (400) if {@code destinationSalonId} equals {@code salonId}
     *                            (no-op rotation)
     */
    @Transactional
    public SalonAdminResponse rotateAdmin(UUID actorId, UUID salonId, UUID userId, UUID destinationSalonId) {
        var admin = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));

        // Defense-in-depth: redundant with @authz.adminBelongsToSalon on the controller,
        // but matches the existing pattern of service-layer re-validation (mirrors removeAdmin).
        if (admin.getRole() != Role.SALON_ADMIN || !salonId.equals(admin.getSalonId())) {
            throw new ForbiddenException("User is not an admin of this salon");
        }

        if (destinationSalonId.equals(salonId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Already assigned to this salon");
        }

        // Same-owner requirement (product decision 2026-07-06): an admin/owner may only rotate
        // staff within a single owner's portfolio of salons — never across owners. salonsShareOwner
        // also returns false when destinationSalonId does not resolve to a salon at all, so
        // "not found" collapses into this same 403 (Sec LOW-1 status-code oracle fix).
        //
        // Both checks below are evaluated unconditionally (not short-circuited) — Sec LOW-2:
        // without this, the "same-owner but inactive" branch would run one extra DB round-trip
        // (the active-check query) beyond what "cross-owner" and "not-found" execute, since those
        // already deny inside salonsShareOwner. That asymmetry is itself a timing oracle letting an
        // actor distinguish "in my owner's portfolio but soft-deleted" from "not in my portfolio at
        // all" by sampling latency. Assigning to locals before the branch forces identical query
        // shape/count across all three denial reasons.
        boolean sameOwner = authorizationService.salonsShareOwner(salonId, destinationSalonId);
        // Lightweight existence+active check (Perf MEDIUM-4) — no full Salon entity load. Inactive
        // destinations also deny with 403, not 400 (Sec LOW-1): distinguishing "inactive" from
        // "cross-owner" via status code would leak which salons an owner's portfolio contains.
        boolean destinationActive = salonRepository.existsByIdAndIsActiveTrue(destinationSalonId);
        if (!sameOwner || !destinationActive) {
            throw new ForbiddenException("Destination salon is not available for rotation");
        }

        // `admin` was loaded via findById in THIS @Transactional, so it is a managed entity —
        // Hibernate dirty-checking flushes the salonId mutation on commit (mirrors removeAdmin —
        // no redundant explicit save()).
        admin.setSalonId(destinationSalonId);

        // Audit trail (LOW-fix): no dedicated audit-log subsystem exists in this codebase yet —
        // a structured INFO log line is the established minimal pattern for sensitive mutations
        // (mirrors the debug-level trail in InviteService). UUIDs only, no PII.
        log.info("Salon rotation: admin {} moved from salon {} to {} by actor {}",
                userId, salonId, destinationSalonId, actorId);

        return SalonAdminResponse.from(admin);
    }

    /**
     * Normalises a raw Instagram value before persistence: trims surrounding
     * whitespace and strips a single leading {@code @} so the stored form is the
     * bare handle (or full URL) — mirroring {@code UserService.normalizeInstagram},
     * which does the same for the independent-master {@code users.instagram} column.
     * A blank or {@code @}-only value normalises to {@code null}. The DTO
     * {@code @Pattern} has already constrained the shape at the boundary; this
     * method only canonicalises an already-valid value.
     *
     * <p>Unlike {@code UserService.normalizeInstagram}, {@code raw} may be
     * {@code null} here: {@code instagramUrl} is an optional field on
     * {@code CreateSalonRequest}, so the create path calls this unconditionally.
     *
     * @param raw the validated request value, possibly {@code null}
     * @return the canonical handle/URL, or {@code null} when the value is
     *         {@code null} or blank
     */
    private String normalizeInstagram(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.strip();
        if (trimmed.startsWith("@")) {
            trimmed = trimmed.substring(1).strip();
        }
        return trimmed.isBlank() ? null : trimmed;
    }
}
