package com.beautica.salon.service;

import org.springframework.data.domain.Sort;
import java.util.Set;
import com.beautica.common.web.SortWhitelist;
import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.auth.TokensValidAfterCache;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.exception.SalonDeletionBlockedException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.location.repository.CityRepository;
import com.beautica.location.service.LocationQueryService;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
import com.beautica.media.entity.EntityType;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.repository.MediaRepository;
import com.beautica.media.service.MediaService;
import com.beautica.salon.audit.AuditOutcome;
import com.beautica.salon.audit.StaffClientReferenceAuditResult;
import com.beautica.salon.audit.StaffClientReferenceViolation;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.PublicSalonResponse;
import com.beautica.salon.dto.SalonAdminResponse;
import com.beautica.salon.dto.SalonInviteHistoryResponse;
import com.beautica.salon.dto.SalonInviteResponse;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.dto.SalonStaffMemberResponse;
import com.beautica.salon.dto.SiblingSalonOption;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.search.service.SearchCacheNames;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.repository.MasterServiceCountProjection;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.user.InviteHistoryRow;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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
    // Audit-fix cycle 2 (LOW — GET /users/me caching). Three methods in this class mutate a
    // `users` row this service does not own: createSalon syncs the owner's locality columns and
    // (on first salon) creates the owner-master row that hasMasterProfile is derived from;
    // removeAdmin and rotateAdmin rewrite users.salon_id. All three stale the user-profile cache.
    private final com.beautica.common.cache.UserProfileCacheEvictor userProfileCacheEvictor;

    // ── Phase 290/295 — salon-deletion staff cascade ──────────────────────────────────────────
    // StaffClientReferenceAuditService backs both the fail-closed precondition
    // (runAuditForSalon) and staff-id resolution (resolveSalonStaffUserIds) that
    // deactivateSalon's cascade uses — see deleteSalonStaff's javadoc. TokensValidAfterCache is
    // the read-through cache JwtAuthenticationFilter checks; the filter has no is_active test and,
    // after phase 295, no users row left to read either, so an un-evicted entry would keep a
    // deleted staff member's already-issued access token working for the cache's whole TTL.
    //
    // RefreshTokenRepository / DeviceTokenRepository / PasswordResetTicketRepository were injected
    // here from phase 290 solely to purge those three tables per staff member, because the account
    // row SURVIVED the cascade. Phase 295 deletes it, and all three tables carry ON DELETE CASCADE
    // on users (V1:17, V29:3, V55:22) — so the rows cannot outlive the delete, and marking a
    // password-reset ticket "used" microseconds before destroying it was pure work. Removed rather
    // than kept as belt-and-braces: unlike a test-cleanup CASCADE (§O-7), a production FK is
    // fail-LOUD — if one of those clauses is ever dropped, DELETE FROM users raises a foreign-key
    // violation out of DELETE /salons/{id} instead of silently leaking a session.
    private final StaffClientReferenceAuditService staffClientReferenceAuditService;
    private final TokensValidAfterCache tokensValidAfterCache;

    // ── Phase 269/293 — salon-deletion booking cascade ────────────────────────────────────────
    // BookingService#declineFutureConfirmedBookingsForSalonClosure is the REUSE-FIRST seam this
    // cascade calls into — it decides which future CONFIRMED bookings need declining and routes
    // each through the existing declineBookingForBatch / AppointmentTransitionService
    // #declineAppointmentItems paths, then enqueues one SALON_CLOSED notification per visit. This
    // is a NEW bean edge (salon.service -> booking.service) but not a circular one: nothing in
    // BookingService's own dependency graph injects SalonService (verified — grep for
    // "salon.service.SalonService" under booking/, common/, master/, notification/ before adding
    // this field turned up only SalonController).
    private final com.beautica.booking.service.BookingService bookingService;

    // ── Phase 297 — single-master removal ─────────────────────────────────────────────────────
    // BookingRepository#countConfirmedFutureByMasterId backs removeMaster's D3 guard — a master
    // with a future CONFIRMED booking cannot be removed via this endpoint (refused with 409;
    // Phase 298 will replace the refusal with a cancel-and-notify cascade). A read-only count, not
    // BookingService — no cascade behaviour is needed on this path, only the check.
    private final BookingRepository bookingRepository;

    // ── Phase 268 — salon-deletion catalogue/favourites/media cascade ────────────────────────
    // ServiceRepository/FavoriteRepository back the two pure-DB steps that run INSIDE the same
    // deletion transaction as deleteSalonStaff (deactivateAllByOwner, deleteAllByTargetTypeAndTargetId
    // — no network, so they roll back with everything else on failure). MediaRepository is read
    // directly (not through MediaService) so the salon's media_files rows can be captured BEFORE
    // deleteSalonStaff runs — a staff-uploaded salon photo's row carries ON DELETE CASCADE on
    // media_files.uploader_id and would otherwise vanish the moment that method hard-deletes the
    // uploader's users row, orphaning the R2 blob with no row left to name it (a gap the phase doc
    // itself did not cover — see deactivateSalon's javadoc). MediaService owns the actual R2 sweep
    // (D3 REUSE-FIRST — the promoted deleteByUploader body) and runs strictly AFTER commit (D8):
    // its txRead/txWrite are PROPAGATION_REQUIRES_NEW TransactionTemplates, so calling it from
    // inside deactivateSalon's own @Transactional would join that transaction and hold a DB
    // connection across dozens of sequential R2 round-trips. transactionManager backs the tiny
    // standalone transaction purgeSalonMediaAfterCommit opens to null the salon's image-URL
    // columns AFTER the R2 sweep — D4's R2-first-then-DB ordering, enforced one level up from
    // MediaService's own internal R2-then-mediaRepo ordering.
    private final ServiceRepository serviceRepository;
    private final FavoriteRepository favoriteRepository;
    private final MediaRepository mediaRepository;
    private final MediaService mediaService;
    private final PlatformTransactionManager transactionManager;

    /**
     * Hard ceiling on rows returned by {@link #listSalonInvites}.
     *
     * <p>The invite-history endpoint is deliberately NOT paginated — a salon's realistic invite
     * count is single- to low-double-digit, so a page cursor would be ceremony the client has to
     * carry for no benefit. But {@code invite_tokens} has no cleanup job, so the underlying table
     * only grows; returning it unbounded would be exactly the unbounded-collection-at-a-public-
     * service-boundary defect Anti-Bug §E3 forbids. This cap is the bound.
     *
     * <p>Trade-off, stated plainly: invite number {@value #MAX_INVITE_HISTORY}+ is dropped from
     * the response. It is NOT dropped silently — {@code SalonInviteHistoryResponse.truncated} is
     * set whenever that happens, so the client can tell an incomplete audit trail from a complete
     * one. Acceptable at current scale; the fix, if a salon ever approaches the cap, is real
     * pagination, not a bigger number.
     */
    private static final int MAX_INVITE_HISTORY = 200;

    /**
     * Wall-clock ceiling on {@link #deactivateSalon}'s single transaction (phase 295 audit,
     * MEDIUM-4).
     *
     * <p>That method runs two unbounded cascades on a request thread — the phase 269/293 decline
     * of every future CONFIRMED booking at the salon, then the phase 295 staff hard-delete — while
     * holding one of {@code maximum-pool-size: 10} connections. Realistic worst case for 20-50
     * staff with a busy forward book is 5-15 s, which also trips Hikari's
     * {@code leak-detection-threshold: 10000} and logs a false leak alert. With no ceiling at all,
     * a pathological salon parks a tenth of the pool indefinitely.
     *
     * <p>30 s is deliberately well above the realistic worst case and well below "forever": the
     * point is that the request fails LOUDLY (a rolled-back transaction and a 500, with the salon
     * still active and re-deletable) rather than silently starving the pool. It is NOT a
     * performance fix — moving this cascade off the request thread is a separate, later decision
     * and explicitly out of scope for this phase.
     */
    private static final int DEACTIVATE_SALON_TIMEOUT_SECONDS = 30;

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

        // Audit-fix cycle 2 — this method writes the owner's own `users` row twice over, and
        // GET /users/me is cached as of this cycle:
        //   1. the locality sync above (cityId/districtId/street/buildingNo/locationNote — five
        //      fields carried verbatim by UserProfileResponse), and
        //   2. on first salon, createMasterForOwner, which flips hasMasterProfile to true.
        // Unconditional rather than mirroring the `request.cityId() != null` / `isFirstSalon`
        // guards: an evict that fires when nothing changed costs one recompute of a 5-minute key,
        // whereas re-deriving those two guards on every future edit of this method is how the
        // eviction goes missing. (createMasterForOwner also evicts on its own path — both are
        // idempotent per-key evicts.)
        userProfileCacheEvictor.evictAfterCommit(ownerIdOf(savedSalon));

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
    /**
     * Phase 268 D2-D4/D8 — permanently purges a deleted salon's R2 imagery and nulls its two
     * image-URL columns, strictly AFTER the deletion transaction commits.
     *
     * <p>Registered as an {@code afterCommit} synchronization, following the EXACT same shape as
     * {@link #evictSalonDetailCacheAfterCommit(UUID)} — guarded on
     * {@link TransactionSynchronizationManager#isSynchronizationActive()}, callback runs on the
     * request thread once the transaction has committed.
     *
     * <p><b>Why after commit, never inline (D8).</b> {@link MediaService#deleteBySalon} routes
     * through {@code MediaService}'s own {@code txRead}/{@code txWrite}
     * {@code TransactionTemplate}s, both {@code PROPAGATION_REQUIRES_NEW}. Calling it from inside
     * {@code deactivateSalon}'s own {@code @Transactional(timeout = 30)} would still JOIN that
     * outer transaction for every statement in between the R2 calls (REQUIRES_NEW only affects the
     * DB read/write steps, not the R2 network calls sandwiched between them), holding a HikariCP
     * connection across dozens of sequential R2 round-trips and defeating the very timeout that
     * exists to bound this method (see {@code DEACTIVATE_SALON_TIMEOUT_SECONDS}'s javadoc).
     * After-commit makes that timeout structurally safe again: the R2 sweep runs with no
     * transaction — and therefore no held connection — open at all.
     *
     * <p><b>Ordering: R2 first, THEN the DB pointer (D4).</b> {@code mediaService.deleteBySalon}
     * runs first; only once it returns does this method open its OWN short-lived
     * {@code PROPAGATION_REQUIRES_NEW} transaction (via {@code transactionManager}, not this
     * class's own {@code @Transactional} — there is none active here, the outer one already
     * committed) to null {@code salons.avatar_url}/{@code cover_image_url}. The DB pointer is
     * dropped whether or not the R2 deletes succeeded — D4 accepts the resulting orphan as the
     * lesser cost against re-publishing a deleted salon's photo at a live public URL.
     *
     * <p><b>Never lets a failure surface as a 500 on an already-committed deletion.</b> The whole
     * body is wrapped in {@code try/catch (RuntimeException)} + WARN: an exception escaping
     * {@code afterCommit} propagates out of the synchronization machinery, and the owner's
     * {@code DELETE /salons/{id}} has already succeeded from their point of view by the time this
     * callback runs.
     *
     * <p><b>Registered LAST</b> (after every cache-eviction registration in {@link
     * #deactivateSalon}) so a problem in this method's own registration can never prevent the
     * cheap, purely in-memory cache evictions from being registered first.
     */
    private void purgeSalonMediaAfterCommit(
            UUID salonId, String avatarUrl, String coverImageUrl, List<MediaFile> salonMediaRows) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mediaService.deleteBySalon(salonId, avatarUrl, coverImageUrl, salonMediaRows);

                    TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
                    txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    txTemplate.executeWithoutResult(status -> salonRepository.nullImageUrls(salonId));
                } catch (RuntimeException ex) {
                    log.warn("Salon media purge failed after commit for salon {}: {}",
                            salonId, ex.getClass().getSimpleName());
                }
            }
        });
    }

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
     * {@link #listSalonInvites} returns a capped {@code List} for one salon). Admins are
     * sourced via {@link UserRepository#findBySalonIdAndRoleAndIsActiveTrue}. {@code serviceCount} per master
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

        userRepository.findBySalonIdAndRoleAndIsActiveTrue(salonId, Role.SALON_ADMIN).stream()
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

    /**
     * Deactivates a salon and destroys its staff, catalogue, favourites and imagery (Phase 290,
     * reversed by Phase 295; catalogue/favourites/media closed by Phase 268). Beyond the
     * {@code salons} row itself this performs four cascades, in this order and no other:
     * <ol>
     *   <li>every future {@code CONFIRMED} booking at the salon is declined and the affected
     *       clients are notified, one {@code SALON_CLOSED} notice per visit — see
     *       {@link com.beautica.booking.service.BookingService
     *       #declineFutureConfirmedBookingsForSalonClosure} (Phase 269/293, D1-D12);</li>
     *   <li>the salon's own staff accounts are HARD-DELETED — see
     *       {@link #deleteSalonStaff(UUID, UUID)} for the full scope, including why the ordering
     *       between the two is load-bearing (Phase 295 D6);</li>
     *   <li>the salon's own {@code service_definitions} catalogue is deactivated (never deleted —
     *       historical bookings and {@code master_service_assignments} still reference these rows)
     *       and every client's {@code favorites} row pointing at this salon is hard-deleted (Phase
     *       268 D1/D5) — both pure DB work, inside this same transaction;</li>
     *   <li>AFTER commit, the salon's R2 imagery (portfolio photos, avatar, cover) is permanently
     *       swept and the two image-URL columns are nulled — see
     *       {@link #purgeSalonMediaAfterCommit(UUID, String, String, List)} for why this step runs
     *       outside the transaction (Phase 268 D2-D4/D8).</li>
     * </ol>
     * The salon row itself is only deactivated, never deleted: {@code reviews.salon_id} is
     * {@code NOT NULL … NO ACTION} (V41:3) and the 2026-09-04 reversal named <i>staff</i>.
     *
     * <p><b>GAP the Phase 268 doc did not cover, closed here.</b> Phase 295's
     * {@link #deleteSalonStaff(UUID, UUID)} hard-deletes staff {@code users} rows, and
     * {@code media_files.uploader_id} carries {@code ON DELETE CASCADE} — so a staff-uploaded
     * salon photo's ROW would vanish before any later entity-keyed read could see it, permanently
     * orphaning its R2 blob. The fix: this method reads the salon's {@code media_files} rows
     * (see the local variable block below {@code salon.setActive(false)}) BEFORE
     * {@link #deleteSalonStaff(UUID, UUID)} runs, and threads that pre-read list all the way into
     * {@link #purgeSalonMediaAfterCommit(UUID, String, String, List)}.
     *
     * @throws NotFoundException             if {@code ownerId} does not resolve to a user, or if
     *                                        {@code salonId} does not resolve to a salon owned by
     *                                        {@code ownerId}
     * @throws ForbiddenException             if the caller is not a {@code SALON_OWNER}
     * @throws SalonDeletionBlockedException if Phase 289's salon-scoped staff-as-client safety
     *                                        audit finds a violation for this salon — the whole
     *                                        deletion aborts before any mutation runs
     */
    @Transactional(timeout = DEACTIVATE_SALON_TIMEOUT_SECONDS)
    public void deactivateSalon(UUID ownerId, UUID salonId) {
        var caller = userRepository.findById(ownerId)
                .orElseThrow(() -> new NotFoundException("User not found: " + ownerId));

        if (caller.getRole() != Role.SALON_OWNER) {
            throw new ForbiddenException("Only SALON_OWNER may deactivate a salon");
        }

        var salon = salonRepository.findByIdAndOwnerId(salonId, ownerId)
                .orElseThrow(() -> new NotFoundException("Salon not found or access denied"));

        // Idempotency guard (Phase 290, pulled forward from the future Phase 298 transaction
        // contract because double-scrubbing becomes POSSIBLE the moment this phase ships a
        // second mutation beyond the salon flag). A second DELETE on an already-inactive salon
        // must be a no-op: without this guard, every repeat call would re-run the staff cascade
        // below against already-deactivated masters/users — re-purging refresh/device tokens and
        // re-stamping tokensValidAfter for no behavioural change, plus a wasted audit query.
        if (!salon.isActive()) {
            return;
        }

        // Fail-closed precondition (Phase 290) — Phase 289's SALON-SCOPED audit only, never the
        // platform-wide sweep (StaffClientReferenceAuditRepository's class javadoc: the
        // platform-wide shape Seq Scans `bookings` and must never run on a request path). A
        // VIOLATIONS_FOUND outcome aborts the ENTIRE deletion before any mutation below runs.
        StaffClientReferenceAuditResult audit = staffClientReferenceAuditService.runAuditForSalon(salonId);
        if (audit.outcome() == AuditOutcome.VIOLATIONS_FOUND) {
            // Correlated, not independent (phase 289 finding): reviews/client_reviews FK back to
            // the same booking, so one bad row can trip more than one reference-type check for
            // the SAME staff member. Report distinct staff members implicated, not violation rows.
            long affectedStaffCount = audit.violations().stream()
                    .map(StaffClientReferenceViolation::userId)
                    .distinct()
                    .count();
            throw new SalonDeletionBlockedException((int) affectedStaffCount);
        }

        // `salon` was loaded via findByIdAndOwnerId in THIS @Transactional, so it is a managed
        // entity — Hibernate dirty-checking flushes the isActive mutation on commit. The explicit
        // save() was a redundant no-op write (PERF-LOW). The findByIdAndOwnerId load is retained:
        // it enforces existence + ownership scoping and cannot be dropped.
        salon.setActive(false);

        // Phase 268 — capture the salon's image URLs and pre-read its media_files rows NOW, while
        // `salon` is still managed and every uploader's `users` row is still whole. Both are needed
        // by the R2 sweep this method registers after commit (purgeSalonMediaAfterCommit), and both
        // MUST be captured before deleteSalonStaff below: a staff-uploaded salon photo's row carries
        // ON DELETE CASCADE on media_files.uploader_id, so reading it after that hard-delete would
        // silently lose the row — and with it the only pointer left to reconcile its R2 blob against.
        final String avatarUrlAtDeletion = salon.getAvatarUrl();
        final String coverImageUrlAtDeletion = salon.getCoverImageUrl();
        final List<MediaFile> salonMediaRows =
                mediaRepository.findByEntityTypeAndEntityId(EntityType.SALON, salonId);

        // Phase 269/293 — decline every future CONFIRMED booking at this salon and notify the
        // affected clients (one SALON_CLOSED entry per VISIT, D12). Runs inside THIS transaction,
        // after the idempotency guard and the fail-closed Phase 289 audit precondition above — a
        // second DELETE on an already-inactive salon never reaches this line, and neither does a
        // salon a VIOLATIONS_FOUND audit blocked.
        //
        // !! ORDERING (phase 295 D6) — THIS RUNS BEFORE deleteSalonStaff. !!
        // It was the other way round through phase 293, when the staff cascade only flipped
        // is_active. The rule is: the closure cascade reads the salon's bookings and their
        // masters, so it runs while those rows are still whole. Nothing here may depend on a
        // masters row that deleteSalonStaff may have just DELETED or DETACHED.
        //
        // Honest scope, measured 2026-09-04 (see SalonStaffHardDeleteIT case 11): reversing this
        // order is currently OBSERVATIONALLY BENIGN, because the SALON_CLOSED notice never reads
        // the master (it names the client and the visit's services) and phase 294 already made
        // every read path on this cascade detach-safe. D6's phase doc claims the notice would be
        // built "from a null master"; it would not. This ordering is therefore DEFENSIVE — the
        // direction that stays correct without depending on a whole subsystem remaining
        // detach-safe — not a bug fix. Keep it anyway; the cost is zero and the alternative
        // couples this method to that invariant forever.
        //
        // Phase 293's own contract is otherwise untouched by phase 295: same transition, same
        // one-notice-per-visit shape, same recipients.
        bookingService.declineFutureConfirmedBookingsForSalonClosure(ownerId, salonId);

        deleteSalonStaff(ownerId, salonId);

        // Phase 268 D1/D5 — close the two remaining polymorphic-reference tables that carry no FK
        // to `salons` and therefore never clean themselves up on any hard delete: the salon's own
        // service catalogue (deactivated, never deleted — historical bookings and
        // master_service_assignments still reference these rows, see D1) and every client's
        // favourite pointing at this salon (hard-deleted — D5, a favourite is a preference, not a
        // record of anything that happened). Both are pure DB work, no network, so they run INSIDE
        // this transaction and roll back with everything else on failure — unlike the R2 sweep
        // below, which is deliberately outside it (D8).
        serviceRepository.deactivateAllByOwner(OwnerType.SALON, salonId);
        favoriteRepository.deleteAllByTargetTypeAndTargetId(FavoriteTargetType.SALON, salonId);

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

        // Phase 268 D2-D4/D8 — registered LAST, after the cache evictions, so a synchronization
        // ordering hiccup among the cheap in-memory evictions above can never prevent the R2 sweep
        // from being registered. Runs the actual R2 deletes + DB pointer null AFTER commit, on the
        // request thread but outside this transaction — see the field-block comment above and this
        // method's own javadoc.
        purgeSalonMediaAfterCommit(salonId, avatarUrlAtDeletion, coverImageUrlAtDeletion, salonMediaRows);
    }

    /**
     * HARD-DELETES {@code salonId}'s own staff — every currently-active master row for the salon
     * is deactivated, then every {@code SALON_MASTER}/{@code SALON_ADMIN} account of the salon is
     * disposed of via {@link #disposeStaffAccounts(UUID, UUID, List)} (Phase 297 D1 extraction —
     * see that method's javadoc for the disposal itself, the binding statement order, and every
     * invariant it protects). This method is only the salon-wide resolve-then-delegate shell:
     * {@link MasterService#deactivateMasters} MUST run first (it dereferences
     * {@code master.getUser().getId()} to key its cache evictions, only possible while the row is
     * still ATTACHED), and {@link StaffClientReferenceAuditService#resolveSalonStaffUserIds}
     * resolves the exact staff id list the seam then disposes of.
     */
    private void deleteSalonStaff(UUID ownerId, UUID salonId) {
        // Phase 290 perf pass (findings #2/#3), unchanged by phase 295: the masters here are
        // already JOIN-FETCHed with `user` by findBySalonIdAndIsActiveTrueWithUser — passed
        // straight to the batch overload rather than re-fetched one-by-one, and that overload
        // fires exactly ONE SalonStaffChangedEvent for the whole list instead of one per master.
        //
        // This MUST stay ahead of the detach/delete below: deactivateMasterInternal dereferences
        // master.getUser().getId() to key the master-by-user / master-detail-by-user / user-profile
        // evictions, and a detached row has no user to read that from.
        List<Master> salonMasters = masterRepository
                .findBySalonIdAndIsActiveTrueWithUser(salonId, Pageable.unpaged())
                .getContent();
        masterService.deactivateMasters(ownerId, salonMasters, salonId);

        List<UUID> staffUserIds = staffClientReferenceAuditService.resolveSalonStaffUserIds(salonId);
        disposeStaffAccounts(ownerId, salonId, staffUserIds);
    }

    /**
     * HARD-DELETES the given {@code staffUserIds}' accounts, and the {@code masters} rows behind
     * them wherever nothing historical still points at one (phase 295, the 2026-09-04 reversal
     * recorded in phase 294 § <i>Decisions — CLOSED</i>; extracted into this standalone seam by
     * phase 297 D1). Replaces phase 290's {@code deactivateSalonStaff} and deletes phase 291's
     * PII-scrub apparatus outright rather than leaving it as an unreachable second erasure policy
     * (D2).
     *
     * <p><b>Two callers, one body (Phase 297 D1 — REUSE-FIRST).</b> {@link
     * #deleteSalonStaff(UUID, UUID)} calls this with the whole salon's resolved staff list;
     * {@code removeMaster(UUID, UUID, UUID)} calls it with a single-element
     * {@code List.of(masterUserId)}. Not a copy, not a variant — the disposal, its binding
     * statement order, the {@code chk_masters_detachment_coherent} interaction, the invite-token
     * cleanup and both cache evictions below are a property of the CHECK constraint and the cache
     * contracts, not of the salon-deletion caller, so they live in exactly one place regardless of
     * how many masters are being removed at once.
     *
     * <p>Idempotent by construction (D4 below) even for a one-element call: a caller that has
     * already resolved an empty or already-disposed id list writes nothing.
     *
     * <h3>The order is not a preference — it is the only representable one</h3>
     * <pre>
     *   1. UPDATE masters SET user_id = NULL, detached_first_name = …, detached_last_name = …,
     *                         detached_at = now(), is_active = false   -- Master#detach(...)
     *   2. DELETE FROM users
     * </pre>
     * A bare {@code DELETE FROM users} cannot work, and no transaction ordering rescues it.
     * {@code fk_masters_user_id} is {@code ON DELETE SET NULL} (V157), so the delete writes
     * {@code masters.user_id = NULL} and nothing else — and that row then satisfies NEITHER arm of
     * {@code chk_masters_detachment_coherent}: the ATTACHED arm needs {@code detached_at IS NULL}
     * with a non-null {@code user_id}, the DETACHED arm needs the name snapshot AND
     * {@code detached_at}. {@code detached_at} cannot be pre-set while the row is still attached
     * (the ATTACHED arm forbids it), and Postgres cannot defer a CHECK — only UNIQUE / PK / FK /
     * EXCLUDE are deferrable. So the snapshot and the null MUST land in one statement, before the
     * account delete. Pinned by {@code MasterDetachmentContractIT} case 5, which is marked
     * <i>BINDING ON PHASE 295</i>; do NOT weaken the CHECK to make a one-step delete work.
     *
     * <h3>Masters — deleted when they can be, detached when they cannot (D1)</h3>
     * The caller is responsible for deactivating every affected master row FIRST (see
     * {@link #deleteSalonStaff(UUID, UUID)}'s use of {@link MasterService#deactivateMasters}, or
     * {@code removeMaster}'s use of {@link MasterService#deactivateMaster}) — this method only
     * disposes of the account and the master row behind it, it does not deactivate.
     *
     * <p>For the staff master rows AS A BATCH:
     * {@link MasterRepository#findIdsWithHistoricalReferences} asks — in ONE query, never per
     * master (phase 295 audit HIGH-2) — which of them still have a {@code bookings.master_id} /
     * {@code reviews.master_id} / {@code client_reviews.author_master_id} row pointing at them.
     * The loop that follows is a pure in-memory branch over that id set.
     * <ul>
     *   <li><b>absent from the set</b> → {@code DELETE FROM masters}. The common case: an invited
     *       master who never
     *       took a booking. {@code master_services}, {@code weekly_schedules},
     *       {@code schedule_exceptions} and {@code working_hours} all cascade.</li>
     *   <li><b>present in the set</b> → {@link Master#detach} — name snapshot, {@code user_id = NULL},
     *       {@code is_active = false}. The account is still deleted; what survives is a name label
     *       on a record belonging to somebody else (a client's own past receipt, a review a client
     *       wrote). Accepted by the user on 2026-09-04 (phase 294 R2); deleting that third-party
     *       history to erase the label was offered and REJECTED.</li>
     * </ul>
     *
     * <h3>Users — deleted unconditionally (D1)</h3>
     * {@code staffUserIds} is the caller's resolved {@code SALON_MASTER}/{@code SALON_ADMIN}
     * account set — for {@link #deleteSalonStaff(UUID, UUID)} via
     * {@link StaffClientReferenceAuditService#resolveSalonStaffUserIds} (phase 289's
     * {@code findSalonStaffUserIds} reused, not re-derived; that resolution structurally can
     * NEVER include the salon's owner — the owner-account exemption, phase 290 D3 / 270 D4, is a
     * structural property of that query), for {@code removeMaster} the one already-validated
     * master's user id.
     *
     * <p>Everything with {@code ON DELETE CASCADE} on {@code users} goes with the row: refresh
     * tokens, device tokens, password-reset tickets, media rows, favourites.
     * {@code created_by_user_id} on {@code bookings}/{@code appointments} nulls out (phase 294
     * D5), so a walk-in the deleted staff member rang up survives with its attribution cleared.
     *
     * <h3>Idempotency comes from row absence, not a flag (D4)</h3>
     * Deleting a row is idempotent by construction — an empty {@code staffUserIds} writes nothing.
     * The phase 291 scrub marker column and its guard are gone with the column (V158).
     *
     * <h3>Known limit — R2 avatar blobs</h3>
     * {@code media_files} cascades on {@code uploader_id} and {@code users.avatar_r2_key} vanishes
     * with the row, so the R2 objects behind both are orphaned (§O-8). That is phase 268's media
     * purge, explicitly out of scope here — recorded, not forgotten.
     */
    private void disposeStaffAccounts(UUID actorId, UUID salonId, List<UUID> staffUserIds) {
        if (staffUserIds.isEmpty()) {
            return;
        }

        // Phase 295 (replaces phase 291 D9's tombstone REDACTION of the same rows). MUST run
        // BEFORE the users delete below: the statement joins invite_tokens.email against the staff
        // member's live users.email, so once the account row is gone the join matches nothing and
        // the address is stranded in this salon's invite history forever — and a stale PENDING row
        // would collide with the phase 296 re-invite. One bulk statement for the whole list.
        inviteTokenRepository.deleteBySalonIdAndStaffUserIds(salonId, staffUserIds);

        // Cache evictions stay per-user — each key is a different person, so the fan-out is
        // correctly proportionate to N. Both caches are read AFTER this transaction commits by
        // paths that would otherwise serve a deleted account: TokensValidAfterCache is what
        // JwtAuthenticationFilter consults, and the user-profile cache backs GET /users/me.
        //
        // !! LOAD-BEARING FOR SECURITY, not a perf nicety (phase 295 audit HIGH-1). !!
        // The row this cascade deletes is exactly what that cache reads, and the filter's guard is
        // driven by its answer. Until the audit fix, "no row" and "row with a null
        // tokens_valid_after" were the SAME Optional.empty() to the filter, so a deleted account's
        // access token stayed valid for the rest of its 3600s TTL. TokensValidAfterCache now
        // answers TokenValidityState.ABSENT for a missing row and the filter refuses to
        // authenticate on it; this eviction is what makes that effective on the very NEXT request
        // instead of after the cache's 60s TTL, which is only the fallback bound. Do not drop it,
        // and do not move it inline — evictTokensValidAfterCacheAfterCommit is afterCommit for the
        // read-through-race reason its own javadoc gives.
        for (UUID staffUserId : staffUserIds) {
            evictTokensValidAfterCacheAfterCommit(staffUserId);
            userProfileCacheEvictor.evictAfterCommit(staffUserId);
        }

        // ── step 1 of the binding order: settle every masters row that references a staff
        // account, so that DELETE FROM users below has nothing left pointing at it.
        //
        // Deliberately re-fetched by user id rather than trusting a caller-supplied master list:
        // an is_active-scoped list (e.g. deleteSalonStaff's now-deactivated salonMasters) or a
        // master deactivated by an earlier operation still owns an ATTACHED masters row whose FK
        // would fire ON DELETE SET NULL and violate chk_masters_detachment_coherent if skipped.
        Instant now = clock.instant();
        int detached = 0;
        int deleted = 0;
        List<Master> staffMasters = masterRepository.findAllByUserIdInWithUser(staffUserIds);

        // ONE set-based probe for the whole batch, resolved BEFORE the loop (phase 295 audit
        // HIGH-2). The loop below is then a pure in-memory branch and issues no query of its own.
        // The predecessor asked per master, and because that finder is a native query with no
        // declared query spaces Hibernate flushed the whole session before each call — so every
        // iteration flushed the previous iteration's detach UPDATE alone and re-dirty-checked a
        // persistence context still holding everything the phase 293 decline cascade loaded.
        // Removing it also makes masterRepository.flush() below the ONLY flush in this method,
        // which is what finally lets the detach UPDATEs and the master DELETEs batch.
        //
        // A salon whose only staff are SALON_ADMINs (no masters rows at all) reaches here with an
        // empty list — pinned by SalonStaffHardDeleteIT case 5b.
        //
        // CORRECTION, measured 2026-09-04 (phase 295 QA). An earlier version of this comment
        // claimed the guard was load-bearing because "an empty bind renders `IN ()`, which is a
        // syntax error". That is FALSE on this stack: Hibernate 6 rewrites an empty list bind for
        // an IN predicate into an always-false form, and calling
        // findIdsWithHistoricalReferences(List.of()) directly returns an empty list without
        // throwing (probed against the Testcontainers Postgres, and confirmed by deleting this
        // guard and watching all 17 cases stay green). The guard is kept because skipping a
        // pointless round trip is worth one branch — NOT because the query would fail. Do not
        // "restore" a correctness rationale here.
        Set<UUID> mastersWithHistory = staffMasters.isEmpty()
                ? Set.of()
                : Set.copyOf(masterRepository.findIdsWithHistoricalReferences(
                        staffMasters.stream().map(Master::getId).toList()));

        for (Master staffMaster : staffMasters) {
            if (!mastersWithHistory.contains(staffMaster.getId())) {
                masterRepository.delete(staffMaster);
                deleted++;
            } else {
                // ONE Hibernate UPDATE writing all five columns together — the whole-row CHECK is
                // evaluated against the result, so the snapshot and the null must not be split.
                // The name is read off the live users row that is about to be destroyed; this is
                // the last moment it exists.
                staffMaster.detach(
                        staffMaster.getUser().getFirstName(),
                        staffMaster.getUser().getLastName(),
                        now);
                detached++;
            }
        }

        // ── step 2 of the binding order. The explicit flush is MANDATORY, not defensive:
        // deleteAllByIdInBatch is a bulk JPQL DELETE, and Hibernate's AUTO flush only flushes
        // pending work whose query space overlaps the statement's — `users`, not `masters`. Without
        // this the detach UPDATEs would still be sitting in the persistence context when the
        // account rows disappear, the FK's ON DELETE SET NULL would fire first, and the flush that
        // followed would try to update rows that no longer satisfy the coherence CHECK. flush() on
        // any repository flushes the whole EntityManager, which is exactly what is wanted here.
        masterRepository.flush();
        userRepository.deleteAllByIdInBatch(staffUserIds);

        // Audit trail — one line per BATCH, ids and counts only, never an email or any other
        // scrubbed value (this repo's PII-in-logs convention). A hard delete of N accounts is the
        // single most consequential mutation this service performs; it must leave a record of who
        // ordered it even though the rows it names are gone.
        log.info("Salon deletion staff hard-delete: {} account(s) deleted, {} master row(s) deleted, "
                        + "{} master row(s) detached for salon {} by actor {}",
                staffUserIds.size(), deleted, detached, salonId, actorId);
    }

    /**
     * Removes ONE invited master from {@code salonId} (Phase 297) — the SALON_OWNER's way to
     * dispose of a single master exactly the way {@link #deleteSalonStaff(UUID, UUID)} disposes
     * of every master when the WHOLE salon is deleted, via the shared {@link
     * #disposeStaffAccounts(UUID, UUID, List)} seam (D1). Not the same operation as {@code DELETE
     * /masters/{masterId}} ({@link MasterService#deactivateMaster}) — that flips
     * {@code is_active = false} globally and leaves the account and the row intact; this
     * hard-deletes the account and deletes-or-detaches the {@code masters} row behind it,
     * identically to a salon deletion.
     *
     * <p><b>Checks run in this exact order</b> — a caller-supplied {@code masterId} needs its own
     * guards where the salon-wide deletion path had structural invariants to lean on instead:
     * <ol>
     *   <li>{@code masterId} resolves to a master row at all → {@link NotFoundException}</li>
     *   <li>the row IS a {@code SALON_MASTER}-type master (D6) — a positive assertion, not merely
     *       an exclusion of {@code SALON_OWNER}. Phase 295's owner exemption is structural for the
     *       salon-wide deletion path — {@code resolveSalonStaffUserIds} can never select an
     *       owner's row — but that guarantee does not survive a caller-supplied id:
     *       {@code masterBelongsToSalon} is true for the owner's own row too, so left unguarded
     *       this endpoint could hard-delete the salon's owner. A positive assertion also closes
     *       {@code INDEPENDENT_MASTER}: today that type never carries a non-null {@code salon}, so
     *       the D5 belongs-to-salon check below happens to catch it too, but that is a DB-unenforced
     *       cross-file invariant (no CHECK constraint ties {@code master_type} to {@code salon_id}
     *       nullability) — {@link MasterService#deactivateMaster(UUID, Master)}, which this method
     *       calls, skips its own {@code assertCanManageMaster} (whose {@code INDEPENDENT_MASTER}
     *       branch requires {@code master.getUser().getId().equals(actorId)}) entirely, so this
     *       method must not rely on D5 alone to keep an independent master's account safe from an
     *       unrelated salon owner</li>
     *   <li>the row is not already {@link Master#isDetached() detached} — a detached row has no
     *       account left to delete, and re-detaching it would overwrite the name snapshot with
     *       {@code null} and trip {@code chk_masters_detachment_coherent}</li>
     *   <li>{@code salonId} re-check against the loaded row's own salon — defense-in-depth
     *       re-check of {@code @authz.masterBelongsToSalon} on the controller, mirroring {@link
     *       #removeAdmin(UUID, UUID, UUID)}'s re-check of {@code adminBelongsToSalon}</li>
     *   <li>self-removal — {@code actorId} cannot remove their own master row, mirroring {@link
     *       #removeAdmin(UUID, UUID, UUID)}'s self-removal guard</li>
     *   <li>{@link StaffClientReferenceAuditService#runAuditForStaffUserIds(List)} against the
     *       ONE master's user id (D4) — fail-closed: a hard-delete of a user who is also
     *       referenced as a client elsewhere (a booking, a review, an appointment) is refused,
     *       never silently degraded to a false CLEAN result</li>
     *   <li>{@link BookingRepository#countConfirmedFutureByMasterId} (D3) — a future
     *       {@code CONFIRMED} booking refuses the removal with {@code 409} naming the count and
     *       writes nothing; {@code Phase 298} replaces this refusal with a cancel-and-notify
     *       cascade, it does not delete this check</li>
     * </ol>
     * Only once every check passes does anything write. {@link MasterService#deactivateMaster}
     * MUST run BEFORE {@link #disposeStaffAccounts(UUID, UUID, List)} — it dereferences {@code
     * master.getUser().getId()} to key its cache evictions ({@code MasterService:790-868}), and a
     * detached row has no user left to read that from.
     *
     * @throws NotFoundException  if {@code masterId} does not resolve to a master row
     * @throws BusinessException  ({@code 409}) if the row is not a {@code SALON_MASTER} (the
     *                            salon's own owner-master or, defense-in-depth, an
     *                            {@code INDEPENDENT_MASTER}), is already detached, the master's
     *                            user is referenced as a client elsewhere, or the master has a
     *                            future CONFIRMED booking
     * @throws ForbiddenException ({@code 403}) if the loaded row does not actually belong to
     *                            {@code salonId}, or if the actor targets their own master row
     */
    @Transactional
    public void removeMaster(UUID actorId, UUID salonId, UUID masterId) {
        Master master = masterRepository.findByIdWithUserAndSalon(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found: " + masterId));

        if (master.getMasterType() != MasterType.SALON_MASTER) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    master.getMasterType() == MasterType.SALON_OWNER
                            ? "Use DELETE /salons/{salonId}/master to disable your own master profile"
                            : "Only an invited SALON_MASTER may be removed here");
        }

        if (master.isDetached()) {
            throw new BusinessException(HttpStatus.CONFLICT, "Master is already detached");
        }

        if (master.getSalon() == null || !salonId.equals(master.getSalon().getId())) {
            throw new ForbiddenException("Master does not belong to this salon");
        }

        UUID masterUserId = master.getUser().getId();
        if (actorId.equals(masterUserId)) {
            throw new ForbiddenException("Cannot remove yourself");
        }

        // Fail-closed precondition (D4), scoped to the ONE user being hard-deleted — never the
        // whole-salon resolution runAuditForSalon(salonId) uses, which would abort a legitimate
        // single removal over some OTHER master's stray client row.
        StaffClientReferenceAuditResult audit =
                staffClientReferenceAuditService.runAuditForStaffUserIds(List.of(masterUserId));
        if (audit.outcome() == AuditOutcome.VIOLATIONS_FOUND) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "This master is also referenced as a client and cannot be removed");
        }

        long futureConfirmedCount =
                bookingRepository.countConfirmedFutureByMasterId(masterId, OffsetDateTime.now(clock));
        if (futureConfirmedCount > 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "Master has " + futureConfirmedCount
                            + " future confirmed booking(s) — cancel or reschedule them first");
        }

        // MUST precede disposeStaffAccounts (see javadoc above): deactivateMaster dereferences
        // master.getUser().getId() to key its cache evictions, and a detached row has no user to
        // read that from. Also fires the single-master rating-recalculation / SalonStaffChangedEvent
        // path this master's removal must trigger, exactly as deleteSalonStaff's batch does.
        //
        // Perf (Phase 297 audit, MEDIUM): calls the already-loaded-row overload
        // MasterService#deactivateMaster(UUID, Master) — reusing `master` fetched above via
        // findByIdWithUserAndSalon — instead of MasterService#deactivateMaster(UUID, UUID), which
        // would re-run that same LEFT JOIN FETCH query plus MasterService's own defense-in-depth
        // assertCanManageMaster (2 more queries for a SALON_MASTER target). The D5/D6 checks above
        // in this method, together with the controller's @PreAuthorize(canManageSalon +
        // masterBelongsToSalon), already prove the identical actor/salon/master triple that
        // assertCanManageMaster would otherwise re-derive.
        masterService.deactivateMaster(actorId, master);

        disposeStaffAccounts(actorId, salonId, List.of(masterUserId));

        log.info("Master removal: master {} (user {}) removed from salon {} by actor {}",
                masterId, masterUserId, salonId, actorId);
    }

    /**
     * Evicts {@code userId}'s {@link TokensValidAfterCache} entry once the current transaction
     * commits, never before — mirrors {@code PasswordResetService.evictTokensValidAfterCache}
     * exactly, and for the identical reason: that cache is a read-through cache backed by the
     * {@code users.tokens_valid_after} row this method just updated, so an eviction that fires
     * before commit reopens the stale-read race window {@link TokensValidAfterCache#invalidate}
     * documents.
     */
    private void evictTokensValidAfterCacheAfterCommit(UUID userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            tokensValidAfterCache.invalidate(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                tokensValidAfterCache.invalidate(userId);
            }
        });
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
        // Audit-fix cycle 2 — users.salon_id is surfaced as UserProfileResponse.salonId, so the
        // removed admin's cached GET /users/me would keep naming the salon they no longer belong
        // to for the full 5-minute TTL. Keyed on the ADMIN's id, never the actor's.
        userProfileCacheEvictor.evictAfterCommit(userId);
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
        // Audit-fix cycle 2 — same field, same reasoning as removeAdmin: the rotated admin's
        // cached GET /users/me would keep reporting the SOURCE salon. Keyed on the admin.
        userProfileCacheEvictor.evictAfterCommit(userId);

        // Audit trail (LOW-fix): no dedicated audit-log subsystem exists in this codebase yet —
        // a structured INFO log line is the established minimal pattern for sensitive mutations
        // (mirrors the debug-level trail in InviteService). UUIDs only, no PII.
        log.info("Salon rotation: admin {} moved from salon {} to {} by actor {}",
                userId, salonId, destinationSalonId, actorId);

        return SalonAdminResponse.from(admin);
    }

    /**
     * Lists the salon's FULL invite history — pending, accepted, expired and cancelled alike —
     * newest-first ({@code GET /salons/{salonId}/invites}). Replaces the earlier pending-only
     * listing: an owner needs to see that an invite was accepted or that they cancelled it, not
     * just what is still outstanding.
     *
     * <p>Salon-scoping — the caller must be the SALON_OWNER of {@code salonId} or a SALON_ADMIN
     * assigned to it — is already enforced by
     * {@code @PreAuthorize("... and @authz.canManageSalon(authentication, #salonId)")} on the
     * controller (mirrors {@link #updateSalon}/{@link #inviteMaster}); a denied caller never
     * reaches this method, and {@code AuthorizationDeniedException} is logged at WARN by
     * {@code GlobalExceptionHandler#handleAuthorizationDenied} (method + path + authorities +
     * non-PII subject — no redundant WARN needed here).
     *
     * <p>Never exposes the token value or its hash — {@link SalonInviteResponse} carries only the
     * recipient email, role, derived status and timestamps (Anti-Bug §I).
     *
     * <p><strong>Truncation is OBSERVABLE, not silent.</strong> The listing is capped at
     * {@link #MAX_INVITE_HISTORY} rows — the cap is what keeps this a bounded collection return
     * (Anti-Bug §E3) rather than an unbounded {@code List} that grows forever, since
     * {@code invite_tokens} has no cleanup job. A bare {@code List} made a truncated history
     * indistinguishable from a complete one, which is unacceptable on an endpoint that IS the
     * audit trail, so the response carries
     * {@link SalonInviteHistoryResponse#truncated()}.
     *
     * <p>The flag is derived by asking for {@code MAX_INVITE_HISTORY + 1} rows and checking
     * whether the extra one came back — a probe, not a second {@code COUNT(*)} round trip. The
     * surplus row is dropped before mapping, so the caller still never sees more than the cap.
     *
     * <p>{@code clock.instant()} is hoisted to a local so every row in one response is classified
     * against ONE instant. Reading the clock inside the map would let a page straddle an
     * {@code expiresAt} boundary and return two rows whose PENDING/EXPIRED split disagrees.
     */
    @Transactional(readOnly = true)
    public SalonInviteHistoryResponse listSalonInvites(UUID salonId) {
        Instant now = clock.instant();
        List<InviteHistoryRow> rows = inviteTokenRepository
                .findSalonInviteHistory(salonId, PageRequest.of(0, MAX_INVITE_HISTORY + 1));

        List<SalonInviteResponse> invites = rows.stream()
                .limit(MAX_INVITE_HISTORY)
                .map(row -> SalonInviteResponse.from(row, now))
                .toList();

        return new SalonInviteHistoryResponse(invites, rows.size() > MAX_INVITE_HISTORY);
    }

    /**
     * Cancels (revokes) a pending invite (Phase 23.1 {@code DELETE
     * /salons/{salonId}/invites/{inviteId}}). Salon-scoping is enforced by {@code @PreAuthorize}
     * on the controller exactly as in {@link #listSalonInvites} — a caller without management
     * access to {@code salonId} never reaches this method.
     *
     * <p>Marks the token revoked via {@link InviteToken#markCancelled(Instant)} rather than
     * deleting the row (avoids FK-cascade surprises and leaves an audit trail of a cancelled,
     * never-accepted invite) — mirrors {@link #removeAdmin}/{@link #rotateAdmin}, which likewise
     * mutate a managed entity loaded in this transaction and rely on Hibernate dirty-checking to
     * flush on commit rather than an explicit {@code save()}.
     *
     * <p>{@code markCancelled} keeps {@code isUsed = true} — exactly what the previous
     * {@code markUsed()} wrote — so the {@code isUsed()} guard below, {@code acceptInvite} and
     * {@code previewInvite} all behave identically. What it ADDS is
     * {@code revokedReason = CANCELLED}, without which {@link #listSalonInvites} could not tell a
     * cancelled invite from an accepted one: both paths set the same single flag. The status
     * ladder in {@link SalonInviteResponse#from} therefore tests CANCELLED before {@code isUsed}.
     *
     * <p><strong>Only a PENDING invite is cancellable</strong> — the guard rejects all four
     * non-pending shapes, which is exactly what the endpoint's OpenAPI description promises. This
     * matters more since the row became history rather than a transient: {@code markCancelled}
     * OVERWRITES {@code revoked_at} and {@code revoked_reason}, so without the
     * {@code getRevokedAt() != null} rung a SUPERSEDED row — which keeps {@code is_used = false}
     * and therefore sailed past the original {@code isUsed()}-only guard — could be rewritten
     * SUPERSEDED &rarr; CANCELLED, retroactively falsifying the audit trail this endpoint exists
     * to serve. The expiry rung closes the same hole for a row that simply lapsed
     * ({@code revoked_at} null, {@code expires_at} past): cancelling a dead invite is a no-op that
     * would nonetheless stamp it CANCELLED and hide the fact it was never acted on. Both use
     * {@link InviteToken#isExpiredAt(Instant)} / the shared predicate rather than an open-coded
     * comparison, so the cancel boundary can never drift from the accept and display boundaries.
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
     * @throws NotFoundException if {@code inviteId} does not resolve to a PENDING invite for
     *                            {@code salonId} — accepted, cancelled, superseded, lapsed,
     *                            cross-salon and missing all collapse to the same 404
     */
    @Transactional
    public void cancelInvite(UUID actorId, UUID salonId, UUID inviteId) {
        Instant now = clock.instant();
        InviteToken token = lockInviteForCancel(inviteId)
                .orElseThrow(() -> new NotFoundException("Invite not found: " + inviteId));

        if (token.isUsed()
                || token.getRevokedAt() != null
                || token.isExpiredAt(now)
                || !salonId.equals(token.getSalonId())) {
            throw new NotFoundException("Invite not found: " + inviteId);
        }

        token.markCancelled(now);

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
