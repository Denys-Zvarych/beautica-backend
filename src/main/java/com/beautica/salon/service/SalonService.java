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
import com.beautica.location.repository.CityRepository;
import com.beautica.location.service.LocationQueryService;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.PendingInviteResponse;
import com.beautica.salon.dto.PublicSalonResponse;
import com.beautica.salon.dto.SalonAdminResponse;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.dto.SalonStaffMemberResponse;
import com.beautica.salon.dto.SiblingSalonOption;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.search.service.SearchCacheNames;
import com.beautica.service.repository.MasterServiceCountProjection;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.User;
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

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SalonService {

    private final SalonRepository salonRepository;
    private final UserRepository userRepository;
    private final InviteService inviteService;
    private final InviteTokenRepository inviteTokenRepository;
    private final MasterRepository masterRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final LocalityWriteValidator localityWriteValidator;
    private final MasterService masterService;
    private final CityRepository cityRepository;
    private final LocationQueryService locationQueryService;
    private final CacheManager cacheManager;
    private final AuthorizationService authorizationService;
    private final Clock clock;

    /**
     * Ceiling on how many ACTIVE salons one {@code SALON_OWNER} may hold (Perf LOW-3).
     *
     * <p>Nothing bounded the portfolio before, and three unbounded reads hang off it:
     * {@code GET /salons/mine}, {@code GET /{salonId}/sibling-salons}, and — worst — the
     * {@code ownerSalons} cache, whose 1000 entries each hold a whole portfolio in heap. The fix
     * belongs on the WRITE side: paginating the reads would push a picker/hub UI into
     * infinite-scroll for a list that is realistically single-digit, and would not shrink the
     * cached value at all.
     *
     * <p><b>Why 50.</b> The largest real salon chains in the Ukrainian market run on the order of
     * 20–30 branches, and this platform's owner-per-chain model means one {@code users} row per
     * chain, not per branch. 50 sits comfortably above the largest plausible legitimate
     * portfolio — roughly double it — so no real owner reaches it, while still capping the
     * sibling payload, the
     * {@code /mine} response and each cache entry at a bounded size. It is a business ceiling, not
     * a schema constraint: raising it is a one-constant change requiring no migration.
     *
     * <p>Counts ACTIVE salons only, so deactivating a salon frees its slot — a chain that closes a
     * branch can open another.
     */
    public static final int MAX_ACTIVE_SALONS_PER_OWNER = 50;

    @Transactional
    public SalonResponse createSalon(UUID ownerId, CreateSalonRequest request) {
        // PESSIMISTIC_WRITE, not a plain findById (Perf LOW-A). The portfolio cap below is a
        // read-then-write check, and nothing in the schema backs it: N concurrent POST /salons for
        // the same owner each read `count < 50` and each insert, landing the owner arbitrarily far
        // above the ceiling and voiding the bound that findActiveSiblingsBySalonId's plan relies on.
        // Locking the OWNER row first serialises every create for that owner on a row this method
        // already had to load, so the count is taken under the lock and the whole check-then-insert
        // is atomic. The lock is released at commit/rollback; two DIFFERENT owners never contend.
        // See lockOwnerForCreate for why this shape (and not a DB constraint) was chosen.
        var owner = lockOwnerForCreate(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found: " + ownerId));

        if (owner.getRole() != Role.SALON_OWNER) {
            throw new ForbiddenException("Only SALON_OWNER may create a salon");
        }

        // Portfolio cap (Perf LOW-3) — checked before anything is validated or persisted, so a
        // capped owner never writes a row. 409, not 400: the request is well-formed; it conflicts
        // with the owner's current state. See MAX_ACTIVE_SALONS_PER_OWNER for the limit's rationale.
        // Runs under the owner row lock taken above, so the count cannot go stale before the insert.
        if (salonRepository.countByOwnerIdAndIsActiveTrue(owner.getId()) >= MAX_ACTIVE_SALONS_PER_OWNER) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "Salon limit reached: an owner may hold at most "
                            + MAX_ACTIVE_SALONS_PER_OWNER + " active salons");
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
        //
        // Keyed off the persisted ROW, not off the `ownerId` parameter (Phase 283). The two are
        // provably equal here — the SALON_OWNER role check above rejects any other caller and the
        // row is built with `.owner(owner)` — but deriving every ownerSalons eviction in this class
        // from `salon.getOwner()` means the key cannot silently diverge from the @Cacheable key if
        // this method's gate is ever widened the way updateSalon's was.
        evictOwnerSalonsCacheAfterCommit(ownerIdOf(savedSalon));

        // Auto-create the owner's SALON_OWNER-type master profile on first-salon creation.
        // Passes already-loaded entities to avoid redundant DB round-trips (Finding 3/4).
        // Runs inside the same @Transactional boundary: if master creation fails, the
        // salon row is rolled back too (atomicity per Phase 12.2 architecture decision).
        if (isFirstSalon) {
            masterService.createMasterForOwner(owner, savedSalon);
        }

        return SalonResponse.from(savedSalon, resolveOblastId(savedSalon.getCityId()));
    }

    /**
     * Resolves the parent oblast id of a single city by its id, for the {@code oblastId}
     * surfaced on {@link SalonResponse}. Delegates to the SHARED cached resolver
     * {@link LocationQueryService#resolveCityOblastId(UUID)} (Phase 240 perf MEDIUM finding)
     * rather than querying {@link CityRepository} directly — that method mirrors
     * {@code MasterService#resolveOblastId(UUID)}, which delegates to the exact same shared
     * resolver (REUSE-FIRST: one cached implementation, not two private per-service copies).
     * Single-row by PK — the create/update paths touch exactly one salon, so this is not the §E
     * "per-row in a collection" concern; {@link #getOwnerSalons} uses the batch
     * {@link CityRepository#findOblastIdsByIdIn(java.util.Collection)} instead.
     *
     * <p>The {@code cityId == null} guard MUST stay here, in front of the call: the shared
     * resolver's {@code @Cacheable} proxy cannot accept a {@code null} Caffeine key.
     *
     * @param cityId the salon's {@code cityId}, possibly {@code null}
     * @return the resolved oblast id, or {@code null} when {@code cityId} is {@code null}
     *         or does not resolve to a known city
     */
    private UUID resolveOblastId(UUID cityId) {
        if (cityId == null) {
            return null;
        }
        return locationQueryService.resolveCityOblastId(cityId);
    }

    // Eviction helpers are registered as post-commit callbacks rather than via @CacheEvict.
    // @CacheEvict fires before the transaction commits, allowing a concurrent reader
    // to repopulate the cache with stale data within the commit window (Anti-Bug §F rule 2).

    /**
     * The {@code ownerSalons} cache key for a salon write — <b>always the salon's OWNER</b>,
     * never the actor who performed the write (Phase 283).
     *
     * <p>{@link #getOwnerSalons} is {@code @Cacheable(value = "ownerSalons", key = "#ownerId")},
     * so an eviction keyed on anything else evicts nothing at all. {@link #createSalon} and
     * {@link #deactivateSalon} are structurally safe (both reject a non-{@code SALON_OWNER}
     * caller, so their {@code actorId} <em>is</em> the owner), but {@link #updateSalon} is gated
     * by {@code @authz.canManageSalon}, which also admits the {@code SALON_ADMIN} assigned to the
     * salon. Passing that actor's id evicted a key nobody reads, and {@code GET /salons/mine}
     * kept serving the owner the pre-edit name/address for the full 5-minute TTL.
     *
     * <p>Costs no extra statement: {@code salons.owner_id} is {@code NOT NULL REFERENCES users(id)}
     * (V3, never relaxed since), and Hibernate reads the identifier straight off the uninitialised
     * {@code @ManyToOne} proxy without a {@code users} select — the same read {@code
     * SalonResponse.from} already performs on every one of these paths. Never {@code null} for a
     * persisted row; a broken invariant fails the write loudly rather than silently under-evicting.
     */
    private static UUID ownerIdOf(Salon salon) {
        return salon.getOwner().getId();
    }

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
        //
        // PATCH semantics: a null cityId means "locality not included in this update", NOT
        // "clear my city". Validating/writing the FK pair unconditionally against the raw
        // (possibly-omitted) request reproduced the reported bug: a PATCH that only touches
        // name/description/phone/etc. and omits cityId (the mobile client does not always
        // resend it) got a 400 BusinessException ("City is required") even though the salon
        // already has a valid city — V150/V151 guarantee every existing salon row does. Unlike
        // CLIENT (optional locality), a salon's city is mandatory, so there is always a valid
        // existing value to fall back to; only (re)validate and (re)write cityId/districtId when
        // the caller actually supplies a cityId. Mirrors the CLIENT/INDEPENDENT_MASTER fix in
        // UserService#writeLocalityFields (commit 9ed0559).
        // Same-shape PATCH-drop bug as locationNote below, but at the FK-pair level:
        // districtId without cityId can never be validated (the taxonomy lookup needs
        // both) nor safely written (writing districtId alone could orphan it against the
        // salon's EXISTING city). Silently dropping it would return a 200 while the
        // caller believes their district change applied — fail loud instead.
        if (request.cityId() == null && request.districtId() != null) {
            throw new BusinessException(
                    "Changing districtId requires supplying cityId in the same request");
        }
        if (request.cityId() != null) {
            localityWriteValidator.validateProviderLocality(request.toLocalityInput());
            salon.setCityId(request.cityId());
            salon.setDistrictId(request.districtId());
        }

        if (request.name() != null) {
            salon.setName(request.name());
        }
        if (request.description() != null) {
            salon.setDescription(request.description());
        }
        // street/buildingNo are @NotBlank on UpdateSalonRequest, so they can never be
        // absent from a validated request — the locationNote bug below is unreachable here,
        // no null-guard needed.
        salon.setStreet(request.street());
        salon.setBuildingNo(request.buildingNo());
        // locationNote is OPTIONAL on the DTO: null means "not included in this PATCH,
        // leave unchanged" (mirrors the cityId contract above), while "" is the explicit
        // clear signal — see UpdateSalonRequest#locationNote javadoc. An unconditional
        // write here previously destroyed a saved note on any PATCH that omitted it
        // (e.g. a name-only edit, or an address edit that only touched street/building).
        if (request.locationNote() != null) {
            salon.setLocationNote(request.locationNote());
        }
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
        SalonResponse result = SalonResponse.from(salon, resolveOblastId(salon.getCityId()));

        // Evict after commit so a concurrent reader cannot repopulate stale data within the
        // commit window. Replaces the @CacheEvict annotations that fired pre-commit (PERF-MEDIUM-2).
        //
        // Keyed on the SALON'S OWNER, never on `actorId` — see ownerIdOf. This gate
        // (@authz.canManageSalon) admits the assigned SALON_ADMIN as well as the owner, so
        // `actorId` was the wrong cache key on every admin PATCH (Phase 283).
        evictOwnerSalonsCacheAfterCommit(ownerIdOf(salon));
        evictSalonDetailCacheAfterCommit(salonId);

        return result;
    }

    /**
     * Plain (uncached) entity fetch — the graph query behind {@link #getPublicSalon(UUID)} and
     * a reusable load point for any future internal caller that needs the raw {@link Salon}
     * entity with its owner association initialised.
     *
     * <p><b>Deliberately NOT {@code @Cacheable} (Phase 240 CRITICAL fix).</b> It previously
     * carried {@code @Cacheable(value = "salon-detail", ...)}, but its only production caller was
     * {@link #getPublicSalon(UUID)} calling it as a plain in-class {@code this.getSalonEntity(...)}
     * — a Spring self-invocation. {@code CacheConfig} wires proxy-based {@code @EnableCaching}
     * (no AspectJ mode, see {@code CacheConfig:54}), so a self-invoked call never crosses the
     * CGLIB proxy and the cache annotation was silently inert on the one path that mattered:
     * {@code GET /salons/{salonId}} ran {@code findByIdAndIsActiveTrueWithOwner} on EVERY request,
     * and the {@code sync = true} thundering-herd guard never engaged. It stayed green because
     * {@code SalonServiceCacheTest}/{@code ReviewCacheEvictionIT} called this method directly
     * through the injected (proxied) bean reference — exactly the one call shape that still
     * worked, and exactly the shape production traffic never takes.
     *
     * <p>Fix: the {@code @Cacheable} boundary moved to {@link #getPublicSalon(UUID)} itself (see
     * its Javadoc) rather than (a) pushing DTO assembly into {@code SalonController} — every
     * controller in this codebase stays a thin HTTP-only shim with zero {@code Response.from(...)}
     * calls, and moving it here would be the first exception — or (c) self-injecting a {@code @Lazy}
     * proxy of this same bean — no self-injection precedent exists anywhere in this codebase, and
     * it was unnecessary once caching could just live on the method that is actually called
     * externally. Eviction is untouched: {@link #evictSalonDetailCacheAfterCommit(UUID)},
     * {@code ReviewEventListener}, and {@code SalonStaffRatingListener} all evict the
     * {@code "salon-detail"} cache by name + {@code salonId} key, agnostic of whether the cached
     * value is a {@link Salon} entity or a {@link PublicSalonResponse} DTO.
     */
    @Transactional(readOnly = true)
    public Salon getSalonEntity(UUID salonId) {
        return salonRepository.findByIdAndIsActiveTrueWithOwner(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found: " + salonId));
    }

    /**
     * Builds the unauthenticated public view of a salon for {@code GET /salons/{salonId}} — the
     * highest-traffic {@code permitAll} endpoint in this set.
     *
     * <p>DTO assembly is kept in the service (not the controller) precisely because it needs a
     * second collaborator call ({@link #resolveOblastId}) beyond the entity fetch — mirrors
     * {@link #createSalon}/{@link #updateSalon}, which likewise resolve {@code oblastId} before
     * handing back the response. Single-row: this endpoint returns exactly one salon, so the
     * per-row {@link #resolveOblastId(UUID)} is correct here — {@link #getOwnerSalons} is the
     * only caller that needs the batch {@link #resolveOblastIdsByCityIds(Set)} sibling.
     *
     * <p>{@code @Cacheable} lives HERE, not on {@link #getSalonEntity(UUID)} (Phase 240 CRITICAL
     * fix — see that method's Javadoc for the self-invocation bug this replaces). This is the
     * method the controller actually calls through the Spring proxy, so caching the whole DTO here
     * — rather than the entity one level down — needs no self-injection and introduces no new
     * pattern: it is just an ordinary {@code @Cacheable} on the method an external caller invokes.
     * {@code sync = true} (unchanged from the prior placement) collapses the thundering herd on a
     * popular salon when {@code ReviewEventListener#onReviewCreated} evicts this entry by key
     * (Anti-Bug §F-7); {@code unless}/{@code condition} are absent, which {@code sync = true}
     * requires.
     */
    @Transactional(readOnly = true)
    @Cacheable(value = "salon-detail", key = "#salonId", sync = true)
    public PublicSalonResponse getPublicSalon(UUID salonId) {
        Salon salon = getSalonEntity(salonId);
        return PublicSalonResponse.from(salon, resolveOblastId(salon.getCityId()));
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

    /**
     * Management-scoped staff roster for {@code GET /{salonId}/staff} (Phase 21.5) — masters
     * (any type bound to the salon) AND {@code SALON_ADMIN}s in one read, so the mobile Персонал
     * tab and staff-detail screen never need two round trips or two response shapes.
     *
     * <p>REUSE, not a parallel read path: masters are sourced via the SAME
     * {@link MasterRepository#findBySalonIdAndIsActiveTrueWithUser} query
     * {@link #getMastersBySalon} already uses (called with {@link Pageable#unpaged()} — a salon's
     * staff roster is bounded by the salon's actual headcount, never the unbounded-collection
     * concern §E-3 guards against; this is the same reasoning that already lets
     * {@link #listPendingInvites} return an unbounded {@code List} for one salon). Admins are
     * sourced via {@link UserRepository#findBySalonIdAndRole}. {@code serviceCount} per master
     * comes from {@link MasterServiceRepository#countActiveByMasterIdIn} — one batch
     * {@code GROUP BY} query for the whole roster, never a per-master count (Anti-Bug §E-3).
     */
    @Transactional(readOnly = true)
    public List<SalonStaffMemberResponse> getSalonStaff(UUID salonId) {
        List<Master> masters = masterRepository
                .findBySalonIdAndIsActiveTrueWithUser(salonId, Pageable.unpaged())
                .getContent();

        Map<UUID, Long> serviceCountByMasterId = resolveServiceCounts(masters);

        List<SalonStaffMemberResponse> staff = masters.stream()
                .map(master -> SalonStaffMemberResponse.fromMaster(
                        master, serviceCountByMasterId.getOrDefault(master.getId(), 0L)))
                .collect(Collectors.toCollection(ArrayList::new));

        userRepository.findBySalonIdAndRole(salonId, Role.SALON_ADMIN).stream()
                .map(SalonStaffMemberResponse::fromAdmin)
                .forEach(staff::add);

        return staff;
    }

    /**
     * Batch-resolves {@link #getSalonStaff}'s per-master {@code serviceCount} in ONE query
     * (Anti-Bug §E-3) — never a per-master {@code COUNT} call inside the mapping loop above.
     */
    private Map<UUID, Long> resolveServiceCounts(List<Master> masters) {
        if (masters.isEmpty()) {
            return Map.of();
        }
        List<UUID> masterIds = masters.stream().map(Master::getId).toList();
        return masterServiceRepository.countActiveByMasterIdIn(masterIds).stream()
                .collect(Collectors.toMap(
                        MasterServiceCountProjection::getMasterId,
                        MasterServiceCountProjection::getServiceCount));
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "ownerSalons", key = "#ownerId")
    public List<SalonResponse> getOwnerSalons(UUID ownerId) {
        return toSalonResponses(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId));
    }

    /**
     * Active salons sharing {@code salonId}'s owner, <b>excluding {@code salonId} itself</b>
     * (Phase 21.3b — {@code GET /salons/{salonId}/sibling-salons}). Backs the mobile
     * "rotate an admin to another salon" destination picker (mobile Phase 21.6): without it the
     * client has no way to enumerate the legal destinations of
     * {@code PATCH /salons/{salonId}/admins/{userId}/salon} — {@code GET /salons/mine} is
     * {@code SALON_OWNER}-only (403 for the admin who may legitimately perform the rotation) and
     * is scoped to the <em>caller's</em> portfolio, not to a particular salon's owner.
     *
     * <p><b>Self is EXCLUDED, by design.</b> The picker chooses a rotation <em>destination</em>,
     * and {@link #rotateAdmin} rejects {@code destinationSalonId.equals(salonId)} with a 400
     * ("Already assigned to this salon"). Returning the source salon would render an option that
     * is guaranteed to fail. The exclusion lives in the query predicate
     * ({@link SalonRepository#findActiveSiblingsBySalonId}), not in a caller-side filter,
     * so it cannot be skipped by a future second caller.
     *
     * <p><b>Authorization is the controller's, not this method's.</b> The identical
     * {@code hasAnyRole('SALON_OWNER','SALON_ADMIN') and canManageSalon(authentication, #salonId)}
     * {@code @PreAuthorize} gate that guards
     * {@code GET /{salonId}/staff} is reused verbatim, so a denied caller never reaches here
     * (§E-4 — this method is deliberately unscoped and must never be called from an ungated path).
     * That gate places the caller inside exactly the trust boundary {@link #rotateAdmin} already
     * operates in: an actor able to move an admin into a sibling salon can already learn that
     * salon's identity by attempting the mutation.
     *
     * <p><b>The read is coupled to self-rotation staying legal.</b> The paragraph above justifies
     * the disclosure by "the caller could learn this by attempting the mutation" — and for the
     * {@code SALON_ADMIN} half of the gate that argument rests on ONE specific shipped behaviour:
     * {@link #rotateAdmin} has <b>no self-guard</b>, unlike its sibling {@link #removeAdmin} (which
     * bans {@code actorId.equals(userId)} outright), so an admin may move their <em>own</em>
     * assignment into a sibling salon. That is intentional and pinned by
     * {@code SalonAdminRotationIntegrationTest#should_return200_when_adminRotatesOwnSalonToAnotherSalonOfSameOwner};
     * it is also the invariant {@code ActorSalonAssignmentMemo} (see its class Javadoc, "No
     * authorization decision is taken after the value could change within a request") documents.
     * <b>If self-rotation is ever restricted</b> — a self-guard added to {@code rotateAdmin}, or
     * the admin arm dropped from its {@code @PreAuthorize} — this endpoint stops being a view onto
     * something the caller could already reach and becomes a real portfolio-enumeration leak for an
     * admin assigned to a single salon. Revisit the admin arm of this endpoint's gate in the same
     * change; nothing else points at that dependency.
     *
     * <p><b>Deliberately NOT {@code @Cacheable}</b>, unlike {@link #getOwnerSalons}. Its natural key
     * is {@code salonId}, but the result changes on a write to <em>any</em> sibling
     * (create/update/deactivate), so a correct {@code @CacheEvict} would have to fan out across the
     * whole portfolio on every salon write (§F-1). One index-served query per picker open is
     * cheaper than that eviction surface.
     *
     * <p><b>Nor is it served off the {@code ownerSalons} cache</b> (re-assessed and rejected a
     * second time, Phase 283). {@link #getOwnerSalons}'s cached value IS a strict superset of this
     * result, and resolving the owner from {@code salonId} is sound for the admin caller too (the
     * owner is a column on the salon row the caller has already been authorized against).
     *
     * <p>Staleness — the original objection — is no longer the blocker. It used to be:
     * {@link #updateSalon} evicted {@code ownerSalons} under the <b>actor's</b> id, and the actor
     * may be a {@code SALON_ADMIN} whose id is not the cache key, so an admin renaming a salon left
     * the owner's entry stale for the full 5-minute TTL. Phase 283 fixed that at the source — every
     * {@code salons} write in this class now evicts under {@code ownerIdOf(salon)} — and those three
     * methods ({@link #createSalon}, {@link #updateSalon}, {@link #deactivateSalon}) are the ONLY
     * writers of the table: no {@code delete} path exists, and no other service holds a
     * {@code Salon} setter call. Every field {@code SiblingSalonOption} carries
     * ({@code name}/{@code street}/{@code buildingNo}) plus row membership ({@code isActive}) is
     * therefore fully covered.
     *
     * <p><b>What blocks it now is that it is not actually cheaper for THIS endpoint's caller.</b>
     * The reuse shape is "resolve owner ({@link SalonRepository#findOwnerIdById}), then read the
     * cached portfolio" — one statement on a cache HIT, three on a MISS
     * ({@code findOwnerIdById} + {@code findAllByOwnerIdAndIsActiveTrue} + the batch oblast
     * resolution inside {@link #toSalonResponses}). Today's shape is unconditionally ONE statement.
     * The hit rate decides it, and it is structurally poor here: {@code ownerSalons} is populated
     * almost exclusively by {@code GET /salons/mine}, which is {@code SALON_OWNER}-only — while the
     * caller this picker exists for is the {@code SALON_ADMIN} (see the gate paragraph above), for
     * whom the owner's entry is cold unless that owner happened to open their own hub inside the
     * last 5 minutes. So the primary caller would trade one query for three. It would also move the
     * {@code s.id <> :salonId} exclusion out of the query predicate into a caller-side filter — the
     * exact coupling {@link SalonRepository#findActiveSiblingsBySalonId} and
     * {@code SalonSiblingRotationParityIT} exist to keep tight — and require an {@code @Lazy self}
     * reference to cross the caching proxy, the self-invocation hazard that already produced one
     * CRITICAL cache bug on this class (see {@link #getSalonEntity}).
     */
    @Transactional(readOnly = true)
    public List<SiblingSalonOption> getSiblingSalons(UUID salonId) {
        // No mapping step: the repository query is a JPQL constructor projection that builds
        // SiblingSalonOption in the database round trip itself (Perf LOW-B), so there is no Salon
        // entity here to map from — and therefore no association a future edit could dereference.
        return salonRepository.findActiveSiblingsBySalonId(salonId);
    }

    /**
     * {@code Salon -> SalonResponse} mapping for {@link #getOwnerSalons} ({@code GET /salons/mine}).
     *
     * <p>Batch-resolves {@code oblastId} for the whole list in ONE query rather than one
     * {@code findByIdWithOblast} per salon (§E — never a per-element repository call in a loop).
     *
     * <p>Kept as a named method rather than inlined back into its single caller: it also documents
     * the batch-vs-per-row contract in one place. {@link #getSiblingSalons} no longer routes
     * through it — its response narrowed to {@link SiblingSalonOption}, which carries no
     * {@code oblastId}, so that path now issues NO oblast query at all (one statement per picker
     * open instead of two).
     */
    private List<SalonResponse> toSalonResponses(List<Salon> salons) {
        Set<UUID> cityIds = salons.stream()
                .map(Salon::getCityId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, UUID> oblastIdByCityId = resolveOblastIdsByCityIds(cityIds);

        return salons.stream()
                .map(salon -> SalonResponse.from(salon, oblastIdByCityId.get(salon.getCityId())))
                .toList();
    }

    /**
     * Batch sibling of {@link #resolveOblastId(UUID)} — resolves every distinct city id in
     * one {@code IN (...)} query via {@link CityRepository#findOblastIdsByIdIn(java.util.Collection)}
     * instead of one {@code findByIdWithOblast} per salon, so {@link #getOwnerSalons} issues
     * exactly one oblast-resolution query regardless of how many salons the owner has.
     *
     * @param cityIds distinct, non-null city ids appearing across the owner's salons
     * @return a {@code cityId -> oblastId} map; empty when {@code cityIds} is empty
     */
    private Map<UUID, UUID> resolveOblastIdsByCityIds(Set<UUID> cityIds) {
        // A plain HashMap throughout — including the empty-input short-circuit — is deliberate:
        // Map.of() rejects a null get() key with an NPE (Objects.requireNonNull on lookup), and
        // salon.getCityId() is null for any salon with no locality set, so the caller's
        // oblastIdByCityId.get(salon.getCityId()) must tolerate a null key.
        Map<UUID, UUID> result = new HashMap<>();
        if (cityIds.isEmpty()) {
            return result;
        }
        for (Object[] row : cityRepository.findOblastIdsByIdIn(cityIds)) {
            result.put((UUID) row[0], (UUID) row[1]);
        }
        return result;
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
        //
        // Keyed off the loaded ROW, not off the `ownerId` parameter (Phase 283) — equal here by
        // construction (findByIdAndOwnerId scopes the load, and only a SALON_OWNER reaches this
        // line), but uniform with createSalon/updateSalon so no eviction in this class is ever
        // keyed on an actor again.
        evictOwnerSalonsCacheAfterCommit(ownerIdOf(salon));
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
     * Lists pending (not-yet-accepted, unexpired) invites for a salon (Phase 23.1
     * {@code GET /salons/{salonId}/invites/pending}). Salon-scoping — the caller must be the
     * SALON_OWNER of {@code salonId} or a SALON_ADMIN assigned to it — is already enforced by
     * {@code @PreAuthorize("... and @authz.canManageSalon(authentication, #salonId)")} on the
     * controller (mirrors {@link #updateSalon}/{@link #inviteMaster}); a denied caller never
     * reaches this method, and {@code AuthorizationDeniedException} is logged at WARN by
     * {@code GlobalExceptionHandler#handleAuthorizationDenied} (method + path + authorities +
     * non-PII subject — no redundant WARN needed here).
     *
     * <p>Never exposes the token value or its hash — {@link PendingInviteResponse} carries only
     * the recipient email, role, and timestamps (Anti-Bug §I). "Pending" excludes both
     * already-used and expired tokens, via {@link InviteTokenRepository
     * #findBySalonIdAndIsUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc}.
     */
    @Transactional(readOnly = true)
    public List<PendingInviteResponse> listPendingInvites(UUID salonId) {
        return inviteTokenRepository
                .findBySalonIdAndIsUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(salonId, clock.instant())
                .stream()
                .map(PendingInviteResponse::from)
                .collect(Collectors.toList());
    }

    /**
     * Cancels (revokes) a pending invite (Phase 23.1 {@code DELETE
     * /salons/{salonId}/invites/{inviteId}}). Salon-scoping is enforced by {@code @PreAuthorize}
     * on the controller exactly as in {@link #listPendingInvites} — a caller without management
     * access to {@code salonId} never reaches this method.
     *
     * <p>Marks the token {@code used = true} rather than deleting the row (per the phase doc —
     * avoids FK-cascade surprises and leaves an audit trail of a cancelled, never-accepted
     * invite) — mirrors {@link #removeAdmin}/{@link #rotateAdmin}, which likewise mutate a
     * managed entity loaded in this transaction and rely on Hibernate dirty-checking to flush on
     * commit rather than an explicit {@code save()}.
     *
     * <p>Defense-in-depth cross-salon check: re-verifies {@code token.getSalonId().equals
     * (salonId)} even though the controller's {@code @PreAuthorize} already scopes the salon —
     * a caller with management access to salon A must never cancel a token that happens to
     * belong to salon B just because it knows the token's id. A token belonging to a different
     * salon, already used, or simply missing all collapse to the same {@link NotFoundException}
     * (404) — distinguishing "belongs to another salon" from "does not exist" via a different
     * status would let an authorized caller probe arbitrary invite ids and learn which ones exist
     * at OTHER salons (IDOR oracle — same rationale as {@code AuthorizationService
     * #salonsShareOwner}'s collapsed denial reasons).
     *
     * <p>QA audit Phase 23.1 (Security MEDIUM): the token is loaded via {@link #lockInviteForCancel}
     * — a {@code PESSIMISTIC_WRITE} row lock, the SAME lock {@code InviteService#acceptInvite}
     * takes via {@code findByTokenForUpdate} — rather than a plain {@code findById}. A cancel racing
     * an accept now serialises against it: whichever side's lock is granted first wins, and the
     * loser's grant always returns the freshly-committed row, so this method's {@code isUsed()}
     * check below can never act on a stale, pre-accept snapshot. See
     * {@code PendingInviteCancelAcceptRaceIT}.
     *
     * @throws NotFoundException if {@code inviteId} does not resolve to a pending (unused) invite
     *                            for {@code salonId}
     */
    @Transactional
    public void cancelInvite(UUID actorId, UUID salonId, UUID inviteId) {
        InviteToken token = lockInviteForCancel(inviteId)
                .orElseThrow(() -> new NotFoundException("Invite not found: " + inviteId));

        if (token.isUsed() || !salonId.equals(token.getSalonId())) {
            throw new NotFoundException("Invite not found: " + inviteId);
        }

        token.markUsed();

        // Audit trail (mirrors rotateAdmin/removeAdmin's INFO line) — UUIDs only, never the
        // recipient email or any token material (Anti-Bug §I).
        log.info("Invite {} for salon {} cancelled by actor {}", inviteId, salonId, actorId);
    }

    /**
     * Seam for {@link #cancelInvite} — isolated into its own method (public, not {@code private})
     * so a concurrency test can {@code @SpyBean} + spy on it and pause the calling thread here,
     * immediately after the row lock is acquired but before the transaction commits (mirrors the
     * lock-seam pattern {@code AppointmentTransitionService} uses, e.g.
     * {@code lockAppointmentHeaderBeforeClientItemCancel}). Delegates to {@link
     * InviteTokenRepository#findByIdForUpdate}, which takes a {@code PESSIMISTIC_WRITE} row lock —
     * see that method's Javadoc for why this replaced a plain {@code findById} (Phase 23.1 QA audit
     * Security MEDIUM).
     */
    public Optional<InviteToken> lockInviteForCancel(UUID inviteId) {
        return inviteTokenRepository.findByIdForUpdate(inviteId);
    }

    /**
     * Owner-row lock seam for {@link #createSalon} — takes a {@code PESSIMISTIC_WRITE}
     * ({@code SELECT ... FOR UPDATE}) lock on the {@code users} row of the prospective owner, so
     * that the {@link #MAX_ACTIVE_SALONS_PER_OWNER} check-then-insert runs atomically per owner
     * (Perf LOW-A).
     *
     * <p><b>The race it closes.</b> {@code createSalon} counts the owner's active salons and then
     * inserts one. With a plain {@code findById} the two statements are unserialised: an owner
     * firing N concurrent {@code POST /salons} has every request read {@code count < 50} and every
     * one insert, so the portfolio lands arbitrarily above the ceiling. The cap is not decoration —
     * {@code SalonRepository#findActiveSiblingsBySalonId}, {@code GET /salons/mine} and the
     * {@code ownerSalons} cache entry are all sized by it.
     *
     * <p><b>Why a row lock and not a DB constraint.</b> "At most N rows per {@code owner_id}" is
     * not expressible as a Postgres {@code CHECK}, {@code UNIQUE} or {@code EXCLUDE} constraint:
     * all three are per-row (or per-key) predicates and none can count sibling rows. Making it a
     * constraint would mean introducing a denormalised counter column plus a backfill migration and
     * a second source of truth that can drift from the {@code salons} table. A per-owner row lock
     * needs no migration at all, and is the shape this codebase already uses for exactly this
     * problem: {@code PasswordResetService} ({@code UserRepository#findByEmailForUpdate} /
     * {@link UserRepository#findByIdForUpdate}) and {@link #lockInviteForCancel}
     * ({@code InviteTokenRepository#findByIdForUpdate}) both close a check-then-write TOCTOU this
     * way. Reusing it keeps one pattern rather than inventing a third.
     *
     * <p><b>Cost.</b> None on the happy path: {@code createSalon} had to load the owner row
     * regardless, so this replaces that read rather than adding a statement. Contention is
     * per-owner and salon creation is a rare, human-paced write.
     *
     * <p>Isolated into its own (public, not {@code private}) method so a concurrency test can
     * {@code @SpyBean} it and pause the calling thread here — after the lock is granted, before the
     * transaction commits — the lock-seam pattern already proven by {@link #lockInviteForCancel}
     * and {@code AppointmentTransitionService}. See
     * {@code SalonCreateCapConcurrencyIT#should_rejectBeyondCap_when_createsIssuedConcurrently}.
     */
    public Optional<User> lockOwnerForCreate(UUID ownerId) {
        return userRepository.findByIdForUpdate(ownerId);
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
