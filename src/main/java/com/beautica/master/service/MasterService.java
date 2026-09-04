package com.beautica.master.service;

import org.springframework.data.domain.Sort;
import com.beautica.common.web.SortWhitelist;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.auth.Role;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ConflictException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.location.service.LocationQueryService;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.dto.WorkingHoursRequest;
import com.beautica.master.dto.WorkingHoursResponse;
import com.beautica.master.event.SalonStaffChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.entity.WorkingHours;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.WorkingHoursRepository;
import com.beautica.booking.service.SlotCalculationService;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.service.SalonCatalogCacheEvictor;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class MasterService {

    private final MasterRepository masterRepository;
    private final UserRepository userRepository;
    private final SalonRepository salonRepository;
    private final WorkingHoursRepository workingHoursRepository;
    private final BookingRepository bookingRepository;
    private final CacheManager cacheManager;
    private final com.beautica.common.cache.MasterCachePrefixEvictor cachePrefixEvictor;
    private final LocationQueryService locationQueryService;
    private final com.beautica.booking.service.BookingSlugService bookingSlugService;
    private final AuthorizationService authorizationService;
    private final SlotCalculationService slotCalculationService;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;
    // Audit-fix cycle 2 (LOW — GET /users/me caching). Every method in this class that
    // creates, reactivates or deactivates a `masters` row also flips
    // UserProfileResponse.hasMasterProfile, which is derived from that row — so a `masters`
    // write stales a `users`-package cache. Routed through the shared evictor rather than an
    // inline cacheManager.getCache("user-profile") so the cross-aggregate writer set stays
    // enumerable from one file; see UserProfileCacheEvictor's javadoc.
    private final com.beautica.common.cache.UserProfileCacheEvictor userProfileCacheEvictor;
    // Mobile Phase 111: a salon's rating is now the equal-weighted mean of its ACTIVE masters'
    // salon-scoped ratings (ReviewRepository#recalculateSalonRating), so it is a function of the
    // staff set and must be recomputed whenever this class changes that set — with no review
    // involved. Published as a SalonStaffChangedEvent rather than called directly so this feature
    // never reaches into ReviewRepository; com.beautica.review.event.SalonStaffRatingListener
    // owns the recalculation AFTER_COMMIT (it filters on the committed salon_id/is_active).
    private final ApplicationEventPublisher eventPublisher;
    // Phase 29.2 fallout: BookingResponse.from now needs an absolute-instant "now" to compute the
    // derived awaitingClosure flag (see that record's javadoc). This cached endpoint is the one
    // pre-existing BookingResponse.from caller outside the booking feature — its "now" is
    // therefore subject to the SAME bounded ~30s master-calendar cache TTL as every other
    // time-sensitive field already on this response (e.g. status, which the write paths evict
    // on every actual mutation via cachePrefixEvictor; awaitingClosure's purely time-driven
    // transition has no write to evict on, so it can lag by up to that TTL — a materially smaller
    // and bounded gap than the "never cached" concern Phase 29.2 raises for the uncached
    // GET /bookings/me family this DTO also backs).
    private final Clock clock;

    @Transactional
    public Master createMasterForIndependentUser(UUID userId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        var master = Master.builder()
                .user(user)
                .salon(null)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();

        Master saved = masterRepository.save(master);
        // Phase 13.1: allocate the public booking slug on creation (one extra UPDATE
        // per registration — acceptable). getOrCreateSlug sets it on the managed entity.
        bookingSlugService.getOrCreateSlug(saved.getId());
        // Audit-fix cycle 2 — CREATE is now an eviction trigger for master-detail-by-user, which
        // memoises the negative answer since findMyMasterDetail returns Optional. `userId` here is
        // a row AuthService persisted moments ago in this same transaction, so no entry can
        // actually exist under it and this is provably a no-op TODAY. It is fired anyway, for the
        // same reason publishSalonStaffChanged is: the alternative is a comment asserting
        // "unreachable" that every future caller of this method must re-derive and that silently
        // becomes false the first time an existing user is converted into an independent master.
        evictUserKeyedMasterCachesAfterCommit(user.getId());
        return saved;
    }

    @Transactional
    public Master createMasterFromInvite(UUID userId, UUID salonId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        var salon = salonRepository.findById(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found"));

        // Phase 286: defence in depth, redundant with InviteService.acceptInvite's own
        // salon-liveness guard. This method is public and @Transactional, so the invariant
        // belongs here rather than on one caller's discipline. Same exception type + message
        // as createMasterForOwner's identical check below so the two paths cannot drift.
        if (!salon.isActive()) {
            throw new BusinessException("Salon is not active");
        }

        var master = Master.builder()
                .user(user)
                .salon(salon)
                .masterType(MasterType.SALON_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();

        Master saved = masterRepository.save(master);
        // Phase 13.1: allocate the public booking slug on creation.
        bookingSlugService.getOrCreateSlug(saved.getId());
        // Mobile Phase 111 — a master JOINED this salon's staff set. Provably a no-op for the
        // average today (a brand-new master row carries no reviews), fired anyway per
        // publishSalonStaffChanged's javadoc.
        publishSalonStaffChanged(salonId);
        // Audit-fix cycle 2 — same reasoning as createMasterForIndependentUser: InviteService
        // mints this user row in the same transaction (acceptInvite rejects an email that already
        // exists), so no cached entry is reachable today. Fired unconditionally so the invariant
        // in deactivateOwnerMaster can say "every create path evicts" without a per-path exemption
        // list that has to be re-verified on every future edit.
        evictUserKeyedMasterCachesAfterCommit(user.getId());
        return saved;
    }

    /**
     * Creates (or reactivates) a {@code SALON_OWNER}-type master row for the given owner
     * inside their primary salon. Called automatically from {@code SalonService.createSalon}
     * on first-salon creation (entity overload), and from the manual re-enable endpoint
     * (Phase 12.4, UUID overload).
     *
     * <ul>
     *   <li>Idempotent: returns the existing active row if already present.</li>
     *   <li>Reactivates: flips {@code is_active = true} if the row exists but was disabled.</li>
     *   <li>Throws {@link ConflictException} if the owner already has a master row of a different
     *       type, or a {@code SALON_OWNER} row already exists in a different salon.</li>
     * </ul>
     *
     * <p>Entity overload — accepts already-loaded entities to avoid redundant DB round-trips
     * when called from {@code SalonService.createSalon} where both are already in memory
     * (Findings 3 and 4).
     */
    @Transactional
    public Master createMasterForOwner(User owner, Salon salon) {
        if (owner.getRole() != Role.SALON_OWNER) {
            throw new ForbiddenException("Only a SALON_OWNER may operate as a master");
        }

        if (!salon.isActive()) {
            throw new BusinessException("Salon is not active");
        }

        if (salon.getOwner() == null || !salon.getOwner().getId().equals(owner.getId())) {
            throw new ForbiddenException("Salon is not owned by the actor");
        }

        // user_id is UNIQUE on masters — a user has at most one master row.
        // Decide: create new / return existing active / reactivate inactive / conflict.
        // findByUserIdWithSalon JOIN FETCH-es salon so that getSalon().getId() below does not
        // fire an extra SELECT * FROM salons (MEDIUM F3).
        var existing = masterRepository.findByUserIdWithSalon(owner.getId());
        if (existing.isPresent()) {
            Master m = existing.get();
            if (m.getMasterType() != MasterType.SALON_OWNER) {
                throw new ConflictException(
                        "User already holds a master profile of a different type");
            }
            if (m.getSalon() == null || !m.getSalon().getId().equals(salon.getId())) {
                throw new ConflictException(
                        "Owner master profile already exists in a different salon");
            }
            if (!m.isActive()) {
                m.setActive(true); // reactivate — Hibernate dirty-check flushes on commit
                // Replace the lazy proxies loaded by findByUserId with the already-loaded
                // entities held by the caller. This guarantees that when getMasterDetail(Master)
                // opens a new read-only session after this transaction commits (entity detached),
                // master.getUser() and master.getSalon() are initialized Java references and
                // cannot trigger LazyInitializationException (Anti-Bug §E, MEDIUM fix).
                m.setUser(owner);
                m.setSalon(salon);
                // Evict stale master-by-user entry so the re-enabled master passes isActive
                // guards for callers using the cache (LOW F4 / Anti-Bug §F rule 1).
                final UUID ownerUserId = owner.getId();
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            Cache c = cacheManager.getCache("master-by-user");
                            if (c != null) {
                                c.evict(ownerUserId);
                            }
                        }
                    });
                }

                // Evict available-slots entries for the reactivated master only (PERF-MEDIUM-3).
                // Stale "empty" cache entries from the deactivation period must not persist
                // for the remaining TTL window after the master is re-enabled.
                final UUID reactivatedMasterId = m.getId();
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            doEvictAvailableSlotsByMaster(reactivatedMasterId);
                        }
                    });
                }

                // Audit-fix cycle 2, THE load-bearing one. master-detail-by-user now memoises the
                // NEGATIVE answer (findMyMasterDetail returns Optional), so the window this
                // reactivation reopens is real and user-visible: the owner toggled
                // «Я також працюю як майстер» OFF, deactivateOwnerMaster evicted, some read then
                // re-cached Optional.empty(), and now they toggle it back ON. Without this evict
                // GET /masters/me answers 404 for their own just-reactivated profile for the rest
                // of the 10-minute TTL, while GET /users/me's hasMasterProfile already reads true —
                // the exact same two-values-in-one-screen split the deactivate side was fixed for,
                // pointing the other way. Cycle 1 documented this branch as needing no counterpart
                // evict; that was correct ONLY while a miss could not be cached, and negative
                // caching retired the premise. It also covers user-profile, since is_active
                // flipping true is precisely what hasMasterProfile reads.
                evictUserKeyedMasterCachesAfterCommit(ownerUserId);

                // Evict the public master-detail cache so the re-enabled master's updated
                // is_active state is reflected immediately on GET /api/v1/masters/{masterId}.
                if (TransactionSynchronizationManager.isSynchronizationActive()) {
                    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            Cache detail = cacheManager.getCache("master-detail");
                            if (detail != null) {
                                detail.evict(reactivatedMasterId);
                            }
                        }
                    });
                }

                // Reactivation flips is_active TRUE — a sole-performer's SALON service can now
                // (re)appear in the booking master-list and the salon catalogue. Evict both
                // bookability caches. salon is the already-loaded caller entity (never a lazy
                // proxy); capture its id synchronously inside the tx (Anti-Bug §E / §F rule 2).
                evictBookabilityCachesAfterCommit(reactivatedMasterId, salon.getId());

                // Mobile Phase 111 — reactivation puts this owner-as-master BACK into the salon's
                // contributing set. Unlike the create branches this genuinely moves the number:
                // the master's prior reviews at this salon start counting again. Inside the
                // !isActive() branch only — the idempotent "already active" return changes no
                // staff set and must not fire.
                publishSalonStaffChanged(salon.getId());

                // Audit trail — the INVERSE of deactivateOwnerMaster's line, same shape and
                // fields as rotateMasterSalon's. Logged for the same reason: this is the half of
                // the pair that puts a suppressed 1-star back, so an audit that recorded only the
                // deactivation would show a one-way move that never came back. Inside the
                // !isActive() branch, so the idempotent no-op return emits nothing.
                log.info("Master reactivation: master {} (salon {}) reactivated by actor {}",
                        reactivatedMasterId, salon.getId(), owner.getId());
            }
            return m; // idempotent
        }

        var master = Master.builder()
                .user(owner)
                .salon(salon)
                .masterType(MasterType.SALON_OWNER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        Master saved = masterRepository.save(master);
        // Phase 13.1: allocate the public booking slug on creation.
        bookingSlugService.getOrCreateSlug(saved.getId());
        // Mobile Phase 111 — the owner joined their own salon's staff set as a master.
        publishSalonStaffChanged(salon.getId());
        // Audit-fix cycle 2 — unlike the other two create paths this one is NOT provably a no-op.
        // It is reached from POST /api/v1/salons/{salonId}/master by an EXISTING, long-lived owner
        // account (the Phase 12.4 re-enable endpoint routes here whenever the row was hard-absent
        // rather than merely deactivated), so that owner's userId can already hold a cached
        // Optional.empty() from any earlier GET /masters/me. Missing this evict would answer 404
        // for a master profile that demonstrably exists. It also flips hasMasterProfile from false
        // to true, staling user-profile.
        evictUserKeyedMasterCachesAfterCommit(owner.getId());
        return saved;
    }

    /**
     * UUID-based overload retained for Phase 12.4 standalone re-enable endpoint.
     * Loads both entities then delegates to the entity overload to avoid code duplication.
     */
    @Transactional
    public Master createMasterForOwner(UUID actorUserId, UUID salonId) {
        var user = userRepository.findById(actorUserId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        var salon = salonRepository.findById(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found"));
        return createMasterForOwner(user, salon);
    }

    /**
     * Returns the publicly-visible {@link MasterDetailResponse} for the given master.
     *
     * <p>Cached under {@code master-detail} with a 5-minute TTL so the
     * {@code findByIdWithUserAndSalon} JOIN FETCH and the follow-up
     * {@code findByMasterIdAndIsActiveTrue} query do not fire on every unauthenticated
     * {@code GET /api/v1/masters/{masterId}} request. {@code sync = true} prevents the
     * thundering-herd on TTL expiry (Anti-Bug §F-7 / HIGH §F rule 7).
     *
     * <p>Eviction: explicit per-key eviction runs after commit in
     * {@link #deactivateMaster}, {@link #deactivateOwnerMaster}, and the reactivation
     * branch of {@link #createMasterForOwner}. Profile-text write paths (bio, phone,
     * instagram, locality) do not evict {@code master-detail} because those callers
     * ({@link com.beautica.user.UserService}) hold only the {@code userId}, not the
     * {@code masterId} key. The 5-minute TTL bounds the staleness window for those
     * writes — an acceptable trade-off for a public discovery view.
     *
     * <p>Do NOT remove the entity overload {@link #getMasterDetail(Master)} — it is
     * used by internal callers that already hold a loaded entity.
     */
    // Fix 6: use findByIdWithUserAndSalon to eliminate 2-4 lazy SELECTs per request
    @Cacheable(value = "master-detail", key = "#masterId", sync = true)
    @Transactional(readOnly = true)
    public MasterDetailResponse getMasterDetail(UUID masterId) {
        var master = masterRepository.findByIdWithUserAndSalon(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));
        requireAttached(master);

        var hours = workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId);
        UUID masterCityId = master.getUser().getCityId();
        UUID oblastId = resolveOblastId(masterCityId);
        UUID salonOblastId = resolveSalonOblastId(master, masterCityId, oblastId);
        return MasterDetailResponse.from(master, hours, oblastId, salonOblastId);
    }

    /**
     * Entity overload — avoids a redundant {@code findByIdWithUserAndSalon} graph-fetch when
     * the caller already holds the {@link Master} entity in the Hibernate first-level cache
     * (MEDIUM-2). The entity must have its {@code salon} and {@code user} associations
     * reachable (i.e. created via {@link #createMasterForOwner(UUID, UUID)} within the same
     * transaction, or loaded via a graph query).
     *
     * <p>Do NOT remove the {@link #getMasterDetail(UUID)} overload — other callers depend on it.
     */
    @Transactional(readOnly = true)
    public MasterDetailResponse getMasterDetail(Master master) {
        requireAttached(master);
        var hours = workingHoursRepository.findByMasterIdAndIsActiveTrue(master.getId());
        UUID masterCityId = master.getUser().getCityId();
        UUID oblastId = resolveOblastId(masterCityId);
        UUID salonOblastId = resolveSalonOblastId(master, masterCityId, oblastId);
        return MasterDetailResponse.from(master, hours, oblastId, salonOblastId);
    }

    // Fix 3 + Fix 7: use shared authorizationService, batch-load all days, saveAll
    @Transactional
    public List<WorkingHoursResponse> upsertWorkingHours(
            UUID actorId, UUID masterId, List<WorkingHoursRequest> requests) {

        // Reject a payload carrying two entries for the same weekday before any DB work.
        // Without this guard the byDay.getOrDefault(...) loop below would silently collapse
        // duplicates to the last-wins entry instead of surfacing the client error. Empty list
        // stays a no-op (the check never trips). Surfaces as a clean 400 (§A) via handleBusiness.
        Set<Integer> seenDays = new HashSet<>(requests.size());
        for (WorkingHoursRequest req : requests) {
            if (!seenDays.add(req.dayOfWeek())) {
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST, "Duplicate working-hours entry for the same day");
            }
        }

        // Ownership already enforced by @PreAuthorize("@authz.canManageMasterSchedule(...)") on
        // the controller — no redundant DB round-trip needed here.
        var master = masterRepository.findByIdWithUserAndSalon(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));

        // Merge against ALL existing rows (incl. inactive). The DB unique key is
        // (master_id, day_of_week) unconditionally, so matching only active rows would miss a
        // pre-existing inactive row for the same weekday and emit a duplicate INSERT (23505).
        // Matching all rows lets us UPDATE the existing row in place — preserving its @Id and
        // toggling is_active back on via setActive(...) below.
        Map<Integer, WorkingHours> byDay = workingHoursRepository.findByMasterId(masterId)
                .stream()
                .collect(Collectors.toMap(WorkingHours::getDayOfWeek, wh -> wh));

        List<WorkingHours> toSave = new ArrayList<>(requests.size());
        for (WorkingHoursRequest req : requests) {
            WorkingHours wh = byDay.getOrDefault(req.dayOfWeek(), WorkingHours.builder()
                    .master(master)
                    .dayOfWeek(req.dayOfWeek())
                    .build());
            wh.setStartTime(req.startTime());
            wh.setEndTime(req.endTime());
            wh.setActive(req.isActive());
            toSave.add(wh);
        }

        List<WorkingHoursResponse> saved = workingHoursRepository.saveAll(toSave).stream()
                .map(WorkingHoursResponse::from)
                .toList();
        evictMasterCalendarAfterCommit(masterId);
        return saved;
    }

    /**
     * Soft-deletes the {@code SALON_OWNER}-type master profile for the given actor in the
     * given salon. Guards:
     * <ul>
     *   <li>The master row must exist, belong to {@code actorUserId}, have type
     *       {@code SALON_OWNER}, and be associated with {@code salonId}.</li>
     * </ul>
     * Ownership is already enforced by {@code @PreAuthorize("@authz.canManageSalon(...)")}
     * on the controller layer — this method trusts that guard and only validates
     * that the row structure matches expectations.
     *
     * <p>Performance (MEDIUM-1): inlines the deactivation logic instead of delegating to
     * {@link #deactivateMaster(UUID, UUID)} to avoid a redundant {@code findByIdWithUserAndSalon}
     * graph-fetch. The master loaded via {@code findByUserId} is already in the Hibernate
     * first-level cache; Hibernate dirty-checking flushes {@code is_active = false} on commit
     * without a separate {@code save()} call. Both cache evictions from {@code deactivateMaster}
     * are replicated here: {@code master-calendar} (via {@link #evictMasterCalendarAfterCommit})
     * and {@code master-by-user} (via an {@code afterCommit} synchronization).
     */
    @Transactional
    public void deactivateOwnerMaster(UUID actorUserId, UUID salonId) {
        // findByUserIdWithSalon JOIN FETCH-es salon to avoid the extra SELECT * FROM salons
        // fired when getSalon().getId() is dereferenced in the filter below (MEDIUM F2).
        var master = masterRepository.findByUserIdWithSalon(actorUserId)
                .filter(m -> m.getMasterType() == MasterType.SALON_OWNER)
                .filter(m -> m.getSalon() != null && m.getSalon().getId().equals(salonId))
                .orElseThrow(() -> new NotFoundException("Owner master profile not found"));

        master.setActive(false);
        // Hibernate dirty-checking flushes the mutation on commit; no explicit save() needed.
        evictMasterCalendarAfterCommit(master.getId());

        // Audit-fix cycle 2 — is_active going FALSE flips UserProfileResponse.hasMasterProfile to
        // false, and GET /users/me is cached as of this cycle. The master-detail-by-user half of
        // the pair is evicted in the afterCommit block below (kept there, beside its invariant
        // block); this line adds the user-profile half. Both are idempotent per-key evicts, so the
        // partial overlap with that block is harmless.
        userProfileCacheEvictor.evictAfterCommit(actorUserId);

        // Deactivation flips is_active FALSE — a sole-performer's SALON service must vanish from
        // the booking master-list and the salon catalogue immediately, not after the 60s TTL.
        // salon is JOIN-FETCHed by findByUserIdWithSalon and non-null (filtered above); capture
        // its id synchronously inside the tx (Anti-Bug §E / §F rule 2).
        evictBookabilityCachesAfterCommit(
                master.getId(),
                master.getSalon() != null ? master.getSalon().getId() : null);

        // Evict stale master-by-user entry so the deactivated master fails isActive guards for
        // callers using the cache. actorUserId is available directly without a JOIN FETCH because
        // findByUserIdWithSalon already filtered on it — no lazy-load risk.
        final UUID masterUserId = actorUserId;
        final UUID deactivatedOwnerMasterId = master.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Cache c = cacheManager.getCache("master-by-user");
                    if (c != null) {
                        c.evict(masterUserId);
                    }
                    // Evict the public master-detail cache so deactivated owners are no longer
                    // served from cache on GET /api/v1/masters/{masterId}.
                    Cache detail = cacheManager.getCache("master-detail");
                    if (detail != null) {
                        detail.evict(deactivatedOwnerMasterId);
                    }
                    // Phase 265 — evict the userId-keyed twin behind GET /masters/me (10-min TTL).
                    // That endpoint is now reachable by SALON_OWNER, so an owner toggling
                    // «Я також працюю як майстер» OFF would otherwise keep being served their own
                    // cached master profile for the rest of the TTL, while GET /users/me's
                    // hasMasterProfile already reads false — the two would disagree inside the same
                    // screen. Mirrors the eviction rotateMasterToSalon performs (Anti-Bug §F-1).
                    //
                    // ════════════════════════════════════════════════════════════════════════════
                    // INVARIANT — master-detail-by-user (audit-fix cycle 2; SUPERSEDES the
                    // deactivate-only invariant written here in cycle 1).
                    // ════════════════════════════════════════════════════════════════════════════
                    // THE RULE, in full:
                    //
                    //   EVERY path that CREATES, REACTIVATES or DEACTIVATES a `masters` row must
                    //   evict `master-detail-by-user` — and `user-profile` — for that row's user,
                    //   after commit.
                    //
                    // WHY IT IS BIDIRECTIONAL NOW. Cycle 1 asserted the reactivation branch of
                    // createMasterForOwner needed no counterpart evict, resting on premise (i):
                    // "no value is EVER cached for a user with no active master row, because
                    // getMyMasterDetail throws NotFoundException and @Cacheable never stores an
                    // exception". That premise was true when written and is now FALSE by design:
                    // findMyMasterDetail returns Optional<MasterDetailResponse>, Spring unwraps the
                    // empty case to null, and Caffeine stores NullValue.INSTANCE. The MISS is
                    // memoised. So "no row" is a cached fact that a create or reactivate makes
                    // stale, exactly as a deactivate makes a cached row stale — and the
                    // create-side bug is the nastier one: a 404 on a profile that provably exists,
                    // with nothing logged and no error to explain it, until the TTL expires.
                    //
                    // THE PATHS THIS RULE DEPENDS ON — all seven, each named, each evicting.
                    // Found by grepping src/main for `masterRepository.save`, `Master.builder()`
                    // and every `setActive(` on a Master; that grep returns nothing else, and
                    // `masters` has no @Modifying bulk UPDATE anywhere in the codebase.
                    //
                    //   CREATE
                    //   1. createMasterForIndependentUser — AuthService registration. Evicts via
                    //      evictUserKeyedMasterCachesAfterCommit. No-op today (the user row is
                    //      minted in the same tx), fired anyway.
                    //   2. createMasterFromInvite — InviteService.acceptInvite. Same: evicts,
                    //      no-op today, fired anyway.
                    //   3. createMasterForOwner(User, Salon), create branch — SalonService
                    //      .createSalon on first salon, AND the Phase 12.4 re-enable endpoint
                    //      POST /salons/{salonId}/master when no row exists at all. NOT a no-op:
                    //      an existing owner reaches it with a warm cache. Evicts.
                    //      (The UUID overload createMasterForOwner(UUID, UUID) delegates here and
                    //      needs no evict of its own.)
                    //
                    //   REACTIVATE
                    //   4. createMasterForOwner(User, Salon), !isActive() branch — the toggle back
                    //      ON. The single most likely key to hold a cached Optional.empty(), since
                    //      the matching toggle-OFF evicted this very key moments earlier and any
                    //      read in between re-cached the miss. Evicts.
                    //
                    //   DEACTIVATE
                    //   5. deactivateOwnerMaster — this method; DELETE /salons/{salonId}/master.
                    //   6. deactivateMaster — DELETE /masters/{masterId}. Reachable for an
                    //      owner-master row: AuthorizationService#canManageMaster authorizes
                    //      MasterType.SALON_OWNER rows. Cycle 1 added its mirrored evict.
                    //
                    //   MUTATE (not a lifecycle change, but it rewrites the cached DTO's `salon`)
                    //   7. rotateMasterToSalon — evicts both caches in its own afterCommit block.
                    //
                    // IF YOU ADD AN EIGHTH PATH: call evictUserKeyedMasterCachesAfterCommit and
                    // add it to this list. There is no compile-time or startup check that will
                    // catch you; the failure mode is a silent, self-healing-in-10-minutes 404 that
                    // no test outside OwnerMasterCacheTest will reproduce.
                    // ════════════════════════════════════════════════════════════════════════════
                    Cache detailByUser = cacheManager.getCache("master-detail-by-user");
                    if (detailByUser != null) {
                        detailByUser.evict(masterUserId);
                    }
                }
            });
        }

        // Evict available-slots entries for the deactivated master only (PERF-MEDIUM-3).
        // Security: a client within the 60-second TTL window would otherwise receive real slot
        // data for a deactivated master and could submit a booking against it.
        final UUID deactivatedMasterId = master.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    doEvictAvailableSlotsByMaster(deactivatedMasterId);
                }
            });
        }

        // Mobile Phase 111 — the owner LEFT their salon's contributing set: their reviews at this
        // salon must stop counting toward its rating. salonId is the caller-supplied id already
        // matched against the loaded master above, so it is this master's actual salon.
        publishSalonStaffChanged(salonId);

        // Audit trail — same shape and fields as the rotation log in rotateMasterSalon. See
        // deactivateMaster for why a staff deactivation is an auditable event: it moves the
        // salon's public rating, and this path is the one an OWNER can drive against their own
        // salon (the 1-star-review scenario), so it is the more sensitive of the two.
        log.info("Owner-master deactivation: master {} (salon {}) deactivated by actor {}",
                deactivatedOwnerMasterId, salonId, actorUserId);
    }

    /**
     * Single-master deactivation — the {@code DELETE /masters/{masterId}} controller path.
     * {@code @PreAuthorize("@authz.canManageMaster(...)")} on the controller is the PRIMARY
     * gate; {@link #assertCanManageMaster} below is defense-in-depth (Phase 290 finding #5) for
     * the OTHER caller of this method, {@link #deactivateMasters}, which reaches it
     * service-to-service with no {@code Authentication} to hand a SpEL predicate.
     */
    @Transactional
    public void deactivateMaster(UUID actorId, UUID masterId) {
        var master = masterRepository.findByIdWithUserAndSalon(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));
        assertCanManageMaster(actorId, master);
        deactivateMasterInternal(actorId, master, true);
    }

    /**
     * Batch sibling of {@link #deactivateMaster(UUID, UUID)} for a caller that already holds
     * every {@link Master} row it needs to deactivate — {@code SalonService}'s salon-deletion
     * cascade ({@code SalonService#deactivateSalonStaff}), the only caller today. Two Phase 290
     * perf findings, fixed together because the second is a direct consequence of the first:
     *
     * <ul>
     *   <li><b>Finding #3</b> — no redundant {@code findByIdWithUserAndSalon} SELECT. The caller's
     *       {@code findBySalonIdAndIsActiveTrueWithUser(salonId, …)} already JOIN-FETCHed
     *       {@code user} for every row in {@code masters}; this method deactivates them in place
     *       rather than re-fetching each one by id.</li>
     *   <li><b>Finding #2</b> — exactly ONE {@link SalonStaffChangedEvent} for the whole batch,
     *       not one per master. {@link #deactivateMaster(UUID, UUID)} publishes on every call, so
     *       N single-master calls would fire N {@code AFTER_COMMIT} rating recalculations
     *       ({@code SalonStaffRatingListener} → {@code RatingRecalculationService
     *       #recalculateSalonRating}, {@code REQUIRES_NEW}) for the identical salon aggregate,
     *       each also re-running the {@code reviews-by-salon} Caffeine keyset prefix scan
     *       (finding #4) — collapsing the publish to one collapses that scan to one too.</li>
     * </ul>
     *
     * <p>Every master in {@code masters} is deactivated with its own per-row cache evictions
     * (master-detail, master-by-user, master-detail-by-user, user-profile, bookability) —
     * finding #4's javadoc note that those are "correctly proportionate" applies here unchanged;
     * only the salon-level rating recalculation and its two dependent evictions
     * ({@code salon-detail}, {@code reviews-by-salon}) are collapsed.
     *
     * @param actorId the verified {@code SALON_OWNER} driving the cascade
     * @param masters every currently-active master row for {@code salonId}, JOIN-FETCHed with
     *                {@code user} (never {@code null}; an empty list is a no-op — no assertion,
     *                no event)
     * @param salonId the salon every entry in {@code masters} belongs to (by construction of the
     *                caller's query)
     */
    @Transactional
    public void deactivateMasters(UUID actorId, List<Master> masters, UUID salonId) {
        if (masters.isEmpty()) {
            return;
        }
        assertCanManageSalonStaff(actorId, salonId);
        for (Master master : masters) {
            deactivateMasterInternal(actorId, master, false);
        }
        publishSalonStaffChanged(salonId);
    }

    /**
     * Defense-in-depth ownership guard for {@link #deactivateMaster(UUID, UUID)} (Phase 290
     * finding #5), mirroring {@code AuthorizationService#canManageMaster(Authentication, UUID)}'s
     * three branches — but answered entirely from data already on {@code master} (no repeated
     * {@code findByIdWithUserAndSalon}) plus, for an invited {@code SALON_MASTER} row, one
     * role-gated ownership/admin-assignment check ({@link #canManageSalonStaff}). Deliberately NOT
     * routed through {@code AuthorizationService#hasManagementAccess(UUID, UUID)} (the 2-arg
     * overload): that reads the actor's role off {@code SecurityContextHolder}, which is empty for
     * a plain service-to-service call with no web request in play — see
     * {@link #assertCanManageSalonStaff} for the same reasoning applied to the batch path.
     *
     * <p><b>Corrected (Phase 290 audit, security MEDIUM):</b> this javadoc previously claimed
     * parity with {@code AuthorizationService#hasManagementAccess(UUID, UUID, Role)} while
     * {@link #canManageSalonStaff} never actually checked the actor's role — it admitted any actor
     * whose {@code users.salon_id} matched, which {@code User.createFromInvite} populates for an
     * invited {@code SALON_MASTER} (read-only) exactly as it does for {@code SALON_ADMIN}. The
     * role gate now lives in {@link #canManageSalonStaff} itself; see its javadoc.
     *
     * @throws ForbiddenException if {@code actorId} may not manage {@code master}
     */
    private void assertCanManageMaster(UUID actorId, Master master) {
        boolean authorized = switch (master.getMasterType()) {
            case INDEPENDENT_MASTER -> master.getUser() != null
                    && master.getUser().getId().equals(actorId);
            case SALON_OWNER -> master.getSalon() != null
                    && master.getSalon().getOwner() != null
                    && master.getSalon().getOwner().getId().equals(actorId);
            case SALON_MASTER -> master.getSalon() != null
                    && canManageSalonStaff(actorId, master.getSalon().getId());
        };
        if (!authorized) {
            throw new ForbiddenException("Actor is not authorized to manage this master");
        }
    }

    /**
     * Defense-in-depth ownership guard for {@link #deactivateMasters} (Phase 290 finding #5) —
     * checked ONCE for the whole batch, not once per master, since every entry in the caller's
     * list is already known to belong to {@code salonId} (see that method's javadoc).
     *
     * <p><b>Ordering hazard, checked and cleared</b>: the cascade caller
     * ({@code SalonService#deactivateSalon}) flips {@code salon.isActive} to {@code false} BEFORE
     * calling {@link #deactivateMasters}. Neither branch of {@link #canManageSalonStaff} filters
     * on {@code is_active} — {@code SalonRepository#existsByIdAndOwnerId}'s javadoc documents
     * "No is_active predicate ... a deactivated salon still answers true for its owner", and
     * {@code UserRepository#findSalonIdById} reads a {@code SALON_ADMIN}'s assignment, which has
     * no relationship to the SALON's active flag at all — so the already-flipped salon still
     * authorizes correctly.
     *
     * <p><b>Not routed through {@code AuthorizationService}'s SecurityContext-reading
     * {@code hasManagementAccess(UUID, UUID)}</b>: that overload calls
     * {@code roleFromCurrentAuthentication()}, which throws {@code ForbiddenException("Not
     * authenticated")} when {@code SecurityContextHolder} carries no {@code Authentication} —
     * exactly the case for {@code SalonStaffDeactivationCascadeIT}, which calls
     * {@code SalonService.deactivateSalon} directly with no web request in play. A check that
     * required an authenticated context would fail that legitimate direct-service caller, not
     * just an attacker — so this guard is self-contained instead, resolving the actor's persisted
     * {@link Role} directly and gating on it, exactly like the two branches
     * {@code AuthorizationService#hasManagementAccess(UUID, UUID, Role)} covers (see
     * {@link #canManageSalonStaff}).
     *
     * @throws ForbiddenException if {@code actorId} may not manage {@code salonId}'s staff
     */
    private void assertCanManageSalonStaff(UUID actorId, UUID salonId) {
        if (!canManageSalonStaff(actorId, salonId)) {
            throw new ForbiddenException("Actor is not authorized to manage this salon's staff");
        }
    }

    /**
     * Data-only mirror of {@code AuthorizationService#hasManagementAccess(UUID, UUID, Role)} for
     * callers with no {@code Authentication} to read a role from (see the two javadocs above for
     * why the SecurityContext-reading overloads cannot be used here). Resolves {@code actorId}'s
     * persisted {@link Role} first and short-circuits to {@code false} for any role other than
     * {@code SALON_OWNER} / {@code SALON_ADMIN} — the same restriction the 3-arg overload enforces
     * — before running that role's single branch.
     *
     * <p><b>Security fix (Phase 290 audit, MEDIUM):</b> the previous version of this method
     * answered {@code salonRepository.existsByIdAndOwnerId(...) ||
     * userRepository.findSalonIdById(...).map(salonId::equals)} with NO role check at all. Because
     * {@code User.createFromInvite} populates {@code users.salon_id} for an invited
     * {@code SALON_MASTER} exactly as it does for {@code SALON_ADMIN} (both branches of
     * {@code InviteService}'s invite-acceptance set it from the invite token regardless of
     * {@code token.getRole()}), a {@code SALON_MASTER} — a read-only role — satisfied the second
     * OR-branch and was admitted as if they could manage salon staff. Not exploitable through any
     * current caller ({@code MasterController}'s {@code @PreAuthorize} and
     * {@code SalonService.deactivateSalon}'s own {@code SALON_OWNER} requirement both reject a
     * {@code SALON_MASTER} first) but this method exists precisely as the defense-in-depth layer
     * behind those gates, so it must not itself be role-blind.
     *
     * <p>One extra query ({@link UserRepository#findRoleById}) per call — for
     * {@link #assertCanManageSalonStaff}'s batch caller ({@link #deactivateMasters}) that is once
     * per BATCH, not once per master, same as before this fix.
     */
    private boolean canManageSalonStaff(UUID actorId, UUID salonId) {
        Role actorRole = userRepository.findRoleById(actorId).orElse(null);
        if (actorRole == Role.SALON_OWNER) {
            return salonRepository.existsByIdAndOwnerId(salonId, actorId);
        }
        if (actorRole == Role.SALON_ADMIN) {
            return userRepository.findSalonIdById(actorId).map(salonId::equals).orElse(false);
        }
        return false;
    }

    /**
     * The deactivation body shared by {@link #deactivateMaster(UUID, UUID)} (one master, one
     * publish) and {@link #deactivateMasters} (N masters, one publish for the whole batch —
     * {@code publishEvent} is {@code false} on every call inside that loop). Authorization is the
     * CALLER's responsibility ({@link #assertCanManageMaster} / {@link #assertCanManageSalonStaff}
     * respectively) — this method trusts {@code master} is already cleared.
     */
    private void deactivateMasterInternal(UUID actorId, Master master, boolean publishEvent) {
        final UUID masterId = master.getId();

        master.setActive(false);
        // Hibernate dirty-checking flushes the mutation on commit; no explicit save() needed.
        evictMasterCalendarAfterCommit(masterId);

        // Deactivation flips is_active FALSE — a sole-performer's SALON service must vanish from
        // the booking master-list and the salon catalogue immediately, not after the 60s TTL.
        // salon may be null (INDEPENDENT_MASTER, a no-op for the catalogue evict); capture its id
        // synchronously inside the tx (§E / §F-2).
        evictBookabilityCachesAfterCommit(
                masterId,
                master.getSalon() != null ? master.getSalon().getId() : null);

        // Capture the user UUID while the transaction is still open (user is JOIN FETCH-ed by
        // both findByIdWithUserAndSalon and findBySalonIdAndIsActiveTrueWithUser, so getUser() is
        // initialized on every caller's Master). A stale master-by-user entry would allow the
        // deactivated master to pass the isActive guard for up to the cache TTL.
        final UUID masterUserId = master.getUser().getId();
        // Audit-fix cycle 2 — the user-profile (GET /users/me) half of the pair; the
        // master-detail-by-user half stays in the afterCommit block below. Reached for an
        // owner-master row too (canManageMaster authorizes MasterType.SALON_OWNER), which is
        // precisely the row hasMasterProfile is derived from.
        userProfileCacheEvictor.evictAfterCommit(masterUserId);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Cache c = cacheManager.getCache("master-by-user");
                    if (c != null) {
                        c.evict(masterUserId);
                    }
                    // Evict the public master-detail cache so deactivated masters are no longer
                    // served from cache on GET /api/v1/masters/{masterId}.
                    Cache detail = cacheManager.getCache("master-detail");
                    if (detail != null) {
                        detail.evict(masterId);
                    }
                    // Audit fix (finding 3) — mirror of the eviction deactivateOwnerMaster
                    // performs on the userId-keyed twin behind GET /masters/me (10-min TTL).
                    // This is the SECOND reachable deactivation path for an owner-master row:
                    // AuthorizationService#canManageMaster authorizes MasterType.SALON_OWNER
                    // rows, so DELETE /masters/{masterId} deactivates the same row the
                    // «Я також працюю як майстер» toggle owns. Without this evict the owner
                    // kept being served their own cached master profile for the rest of the TTL
                    // while GET /users/me's hasMasterProfile (derived on read, never cached)
                    // already read false — and the no-counterpart invariant documented in
                    // deactivateOwnerMaster held on only one of the two paths (§F rule 1).
                    // Also correct for SALON_MASTER/INDEPENDENT_MASTER rows, whose /masters/me
                    // entry must likewise disappear the moment they are deactivated.
                    Cache detailByUser = cacheManager.getCache("master-detail-by-user");
                    if (detailByUser != null) {
                        detailByUser.evict(masterUserId);
                    }
                }
            });
        }

        // Mobile Phase 111 — the master LEFT this salon's contributing set. Captured from the
        // salon association while the transaction is open; null for an INDEPENDENT_MASTER, which
        // publishSalonStaffChanged treats as a no-op. Suppressed entirely (publishEvent == false)
        // for a deactivateMasters() batch entry — the caller publishes ONCE for the whole batch
        // instead (Phase 290 finding #2).
        final UUID leftSalonId = master.getSalon() != null ? master.getSalon().getId() : null;
        if (publishEvent) {
            publishSalonStaffChanged(leftSalonId);
        }

        // Audit trail — same shape and fields as the rotation log in rotateMasterSalon (UUIDs
        // only, no PII). Deactivation is not merely a calendar change since mobile Phase 111: the
        // salon's PUBLIC avg_rating, review_count and star histogram are all computed over its
        // CURRENTLY-ACTIVE masters, so removing a master moves the number a client sees, within
        // one commit and reversibly (immediately for a single deactivation; after the ONE batch
        // publish for a deactivateMasters() cascade). A privileged mutation with a visible public
        // effect and no record of who made it is exactly what an audit line exists for.
        log.info("Master deactivation: master {} (salon {}) deactivated by actor {}",
                masterId, leftSalonId, actorId);
    }

    /**
     * Rotates (reassigns) a {@code SALON_MASTER} from their current salon to
     * {@code destinationSalonId} (Phase 21.3). {@code actorId}'s management authority over the
     * master's CURRENT salon is already enforced by
     * {@code @PreAuthorize("@authz.canManageMaster(...)")} on the controller.
     *
     * <p>Restricted to {@code masterType == SALON_MASTER}: an {@code INDEPENDENT_MASTER} has no
     * salon to rotate from, and rotating the owner's own {@code SALON_OWNER}-type master row
     * (moving the owner's personal service-provider profile between their salons) is a separate,
     * unaddressed product question left out of scope here.
     *
     * <p>The destination salon MUST belong to the SAME owner as the master's current salon — this
     * mirrors {@code SalonService.rotateAdmin} and is enforced via the same
     * {@link AuthorizationService#salonsShareOwner} primitive.
     *
     * <p><b>Known limitation (documented, not fixed here):</b> this only reassigns
     * {@code master.salon_id}. There is no booking-conflict guard — a master with existing
     * future {@code CONFIRMED} bookings will silently appear under the new salon
     * everywhere post-rotation, exactly like the already-deferred "block deactivation until
     * futureBookingCount is exposed" item for {@link #deactivateMaster}. Do not block callers on
     * this; track it as a follow-up once {@code futureBookingCount} exists.
     *
     * <p>Destination validation (Sec LOW-1): all destination-related denial reasons — cross-owner,
     * inactive, and not-found — collapse to the SAME {@link ForbiddenException} (403).
     * Distinguishing them via status code would let an actor with legitimate authority over the
     * current salon probe an arbitrary owner's full salon portfolio (including inactive salons a
     * public endpoint would 404 on) by observing 404 vs 403 vs 400 responses. The same-salon
     * no-op check stays a distinct 400 — the actor already knows the current salon's identity, so
     * it carries no oracle risk. The destination {@code Salon} entity is still loaded via
     * {@code findById} (not a projection) — {@link Master#setSalon} needs the actual managed
     * entity, unlike {@code SalonService.rotateAdmin} which only ever writes a raw UUID.
     *
     * @throws NotFoundException  if {@code masterId} does not resolve to a master
     * @throws ForbiddenException if the master is not a {@code SALON_MASTER} (i.e. is
     *                            {@code INDEPENDENT_MASTER} or {@code SALON_OWNER}-type), or if
     *                            the destination salon does not exist, is not owned by the same
     *                            owner as the master's current salon, or is inactive
     *                            (soft-deleted)
     * @throws BusinessException  (400) if {@code destinationSalonId} equals the master's current
     *                            salon (no-op rotation)
     */
    @Transactional
    public MasterSummaryResponse rotateMasterToSalon(UUID actorId, UUID masterId, UUID destinationSalonId) {
        // actorId's management authority over the CURRENT salon is already enforced by
        // @PreAuthorize("@authz.canManageMaster(...)") on the controller — no redundant
        // re-derivation needed here (mirrors upsertWorkingHours / deactivateMaster).
        var master = masterRepository.findByIdWithUserAndSalon(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));

        if (master.getMasterType() != MasterType.SALON_MASTER) {
            throw new ForbiddenException(
                    "Only a SALON_MASTER may be rotated between salons — "
                            + "INDEPENDENT_MASTER has no salon, and the owner's own master profile "
                            + "is a separate, unsupported operation");
        }

        UUID currentSalonId = master.getSalon() != null ? master.getSalon().getId() : null;
        if (currentSalonId == null) {
            // Defensive — a SALON_MASTER row should always carry a salon FK. Treated as
            // not-found rather than a 500 if data ever drifts.
            throw new NotFoundException("Master has no current salon assignment");
        }

        if (destinationSalonId.equals(currentSalonId)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Already assigned to this salon");
        }

        // Same-owner requirement (product decision 2026-07-06): rotation is legal only within a
        // single owner's portfolio of salons — never across owners. salonsShareOwner also returns
        // false when destinationSalonId does not resolve to a salon at all, so "not found"
        // collapses into this same 403 (Sec LOW-1 status-code oracle fix).
        //
        // Both lookups below are evaluated unconditionally (not short-circuited) — Sec LOW-2:
        // without this, the "same-owner but inactive" branch would run one extra DB round-trip
        // (the destination-salon load) beyond what "cross-owner" and "not-found" execute, since
        // those already deny inside salonsShareOwner. That asymmetry is itself a timing oracle
        // letting an actor distinguish "in my owner's portfolio but soft-deleted" from "not in my
        // portfolio at all" by sampling latency. Assigning to locals before the branch forces
        // identical query shape/count across all three denial reasons.
        boolean sameOwner = authorizationService.salonsShareOwner(currentSalonId, destinationSalonId);
        // Full-entity load retained here (unlike SalonService.rotateAdmin's projection-based
        // check) because master.setSalon(...) below needs the actual managed Salon entity, not
        // just its UUID. A missing OR inactive destination denies with the SAME 403 as the
        // cross-owner case above (Sec LOW-1) — never a distinct 404/400 that would let the actor
        // fingerprint which specific denial reason applied.
        var destinationSalonLookup = salonRepository.findById(destinationSalonId).filter(Salon::isActive);
        if (!sameOwner || destinationSalonLookup.isEmpty()) {
            throw new ForbiddenException("Destination salon is not available for rotation");
        }
        var destinationSalon = destinationSalonLookup.get();

        // `master` was loaded via findByIdWithUserAndSalon in THIS @Transactional, so it is a
        // managed entity — Hibernate dirty-checking flushes the salon mutation on commit.
        master.setSalon(destinationSalon);

        // Evict the public master-detail cache (mirrors deactivateMaster) so GET
        // /api/v1/masters/{masterId} reflects the new salon immediately instead of serving the
        // stale salon for up to the cache TTL (Anti-Bug §F rule 2).
        final UUID rotatedMasterId = master.getId();
        // Captured while the transaction is still open — master.getUser() is JOIN FETCH-ed by
        // findByIdWithUserAndSalon, so this is an initialized reference, not a lazy proxy.
        final UUID rotatedMasterUserId = master.getUser().getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Cache detail = cacheManager.getCache("master-detail");
                    if (detail != null) {
                        detail.evict(rotatedMasterId);
                    }
                    // Evict the userId-keyed twin of master-detail (backs GET /masters/me, 10-min
                    // TTL) — both caches store a MasterDetailResponse embedding `salon`, the exact
                    // field this method mutates (HIGH fix). Without this a rotated SALON_MASTER
                    // calling GET /masters/me within the TTL would see their pre-rotation salon.
                    Cache detailByUser = cacheManager.getCache("master-detail-by-user");
                    if (detailByUser != null) {
                        detailByUser.evict(rotatedMasterUserId);
                    }
                    // Evict the raw-entity master-by-user cache too (MEDIUM fix) — mirrors the
                    // eviction deactivateMaster/deactivateOwnerMaster/createMasterForOwner already
                    // perform on every other write path that mutates this row.
                    Cache byUser = cacheManager.getCache("master-by-user");
                    if (byUser != null) {
                        byUser.evict(rotatedMasterUserId);
                    }
                }
            });
        }
        evictMasterCalendarAfterCommit(masterId);

        // Audit trail (LOW-fix): no dedicated audit-log subsystem exists in this codebase yet —
        // a structured INFO log line is the established minimal pattern for sensitive mutations.
        // UUIDs only, no PII.
        log.info("Salon rotation: master {} moved from salon {} to {} by actor {}",
                masterId, currentSalonId, destinationSalonId, actorId);

        // Mobile Phase 111 — a rotation changes TWO staff sets, so it publishes TWICE. The source
        // salon loses a contributor (its rating must drop this master's scores) and the
        // destination gains one. currentSalonId is non-null (guarded above) and
        // destinationSalonId is a resolved, active salon.
        publishSalonStaffChanged(currentSalonId);
        publishSalonStaffChanged(destinationSalonId);

        return MasterSummaryResponse.from(master);
    }

    /**
     * Resolves the {@code oblastId} for the given city. Delegates to the SHARED cached resolver
     * {@link LocationQueryService#resolveCityOblastId(UUID)} (Phase 240 perf MEDIUM finding)
     * rather than querying {@code CityRepository} directly — {@code SalonService#resolveOblastId}
     * delegates to the exact same shared resolver (REUSE-FIRST: one cached implementation, not two
     * private per-service copies).
     *
     * <p>The {@code cityId == null} guard MUST stay here, in front of the call: the shared
     * resolver's {@code @Cacheable} proxy cannot accept a {@code null} Caffeine key.
     *
     * @param cityId raw city FK from {@code users.city_id}; {@code null} when the user
     *               has no location set
     * @return the PK of the parent {@link com.beautica.location.entity.Oblast},
     *         or {@code null} when {@code cityId} is {@code null} or the city row is missing
     */
    private UUID resolveOblastId(UUID cityId) {
        if (cityId == null) {
            return null;
        }
        return locationQueryService.resolveCityOblastId(cityId);
    }

    /**
     * Resolves the affiliated salon's {@code oblastId} for {@link #getMasterDetail(UUID)},
     * {@link #getMasterDetail(Master)}, and {@link #getMyMasterDetail(UUID)} (Phase 240 perf LOW
     * finding — free rider on {@link #resolveOblastId(UUID)}'s Finding 2 fix; builds nothing
     * bespoke of its own).
     *
     * <p>Short-circuits the common case — a salon-affiliated master whose own city IS the salon's
     * city — by reusing the already-resolved {@code masterOblastId} instead of running a second
     * {@link #resolveOblastId(UUID)} round-trip (still cached post-Finding-2, but every avoided
     * call is one fewer cache lookup on this per-request path). A different salon city (the
     * uncommon case — e.g. an admin editing before the master's profile address is synced) still
     * resolves independently, so the two oblastIds never collapse into one when the cities differ.
     *
     * <p>The {@code master.getSalon() == null} guard is preserved exactly: no salon means no
     * salon {@code oblastId} to resolve, full stop — {@link #resolveOblastId(UUID)} is never
     * invoked with a salon cityId in that case.
     *
     * @param master        the master entity (salon association must be initialised)
     * @param masterCityId  {@code master.getUser().getCityId()}, already extracted by the caller
     * @param masterOblastId the already-resolved {@code oblastId} for {@code masterCityId}
     * @return the affiliated salon's {@code oblastId}, or {@code null} when the master has no
     *         affiliated salon or the salon has no city set
     */
    /**
     * Fail-closed guard for every path that builds a {@link MasterDetailResponse} (2026-09 audit
     * finding 4).
     *
     * <p>{@code MasterDetailResponse#from} reads ~12 columns straight off {@code master.getUser()}
     * — phone number, city, street, building, location note, bio, Instagram, avatar. For a DETACHED
     * master (V157 / phase 294 — the staff {@code users} row hard-deleted) that association is
     * {@code null}, so the unguarded read was an NPE reachable ANONYMOUSLY: {@code
     * GET /api/v1/masters/&#123;masterId&#125;} is {@code permitAll()} ({@code SecurityConfig}) and
     * {@code findByIdWithUserAndSalon} carries no {@code isActive} predicate, so a detached row is
     * still fetched — it just cannot be rendered. That is a 500 on an unauthenticated endpoint.
     *
     * <p><b>404, deliberately not a masked/partial profile.</b> Patching the two name fields to
     * {@code displayFirstName()}/{@code displayLastName()} would render the ERASED person's whole
     * public profile to anonymous callers, which is the opposite of what the delete was for. A
     * detached master is {@code is_active = false} and unreachable from search, the salon roster and
     * the catalogue; the profile endpoint must agree with them. The 2026-09-04 product decision that
     * a deleted master's NAME survives is scoped to a client's OWN past receipt, not to a public
     * profile page.
     *
     * <p>No existence oracle either — {@code NotFoundException} is the same response a never-existent
     * id gets, so an anonymous caller cannot distinguish "deleted" from "never was".
     */
    private static void requireAttached(Master master) {
        if (master.isDetached()) {
            throw new NotFoundException("Master not found");
        }
    }

    private UUID resolveSalonOblastId(Master master, UUID masterCityId, UUID masterOblastId) {
        if (master.getSalon() == null) {
            return null;
        }
        UUID salonCityId = master.getSalon().getCityId();
        return Objects.equals(salonCityId, masterCityId)
                ? masterOblastId
                : resolveOblastId(salonCityId);
    }

    /**
     * Evicts the two user-keyed caches that every {@code masters}-row lifecycle change invalidates,
     * after the current transaction commits. Called from EVERY create, reactivate and deactivate
     * path in this class — see the invariant block in {@link #deactivateOwnerMaster} for the
     * enumerated list and why it is exhaustive.
     *
     * <ul>
     *   <li>{@code master-detail-by-user} — {@code GET /masters/me}. Since audit-fix cycle 2 this
     *       cache memoises the NEGATIVE answer too ({@link #findMyMasterDetail} returns
     *       {@code Optional}), so a create or reactivate that does not evict leaves the user
     *       served a cached 404 for their own freshly-created profile.</li>
     *   <li>{@code user-profile} — {@code GET /users/me}. {@code UserProfileResponse
     *       .hasMasterProfile} is derived from exactly this row, in a different aggregate; the
     *       coupling is documented at the cache registration and in
     *       {@code UserProfileCacheEvictor}.</li>
     * </ul>
     *
     * <p>Both are per-key evicts, never {@code clear()} (Anti-Bug §F-6), and both run
     * {@code afterCommit} so no parallel reader can repopulate them with the pre-write state
     * (§F-2). Safe to call in addition to an existing eviction block for the same key — a second
     * evict of an already-empty key is a no-op, and registering two synchronizations is
     * order-independent.
     *
     * @param userId the master row's owning user; the key shape both caches use
     */
    private void evictUserKeyedMasterCachesAfterCommit(UUID userId) {
        if (userId == null) {
            return;
        }
        userProfileCacheEvictor.evictAfterCommit(userId);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Cache detailByUser = cacheManager.getCache("master-detail-by-user");
                if (detailByUser != null) {
                    detailByUser.evict(userId);
                }
            }
        });
    }

    // Eviction is registered as a post-commit callback rather than via @CacheEvict.
    // @CacheEvict fires before the transaction commits, allowing a concurrent reader
    // to repopulate the cache with stale data within the commit window.
    // Registering afterCommit() ensures the cache is cleared only after the write is durable.
    // Guard: synchronization must be active (i.e. called within a @Transactional context).
    //
    // Eviction is registered as a post-commit callback rather than via @CacheEvict.
    // @CacheEvict fires before the transaction commits, allowing a concurrent reader
    // to repopulate the cache with stale data within the commit window.
    // Registering afterCommit() ensures the cache is cleared only after the write is durable.
    // Guard: synchronization must be active (i.e. called within a @Transactional context).
    //
    // Per-master key eviction (PERF-MEDIUM-4): the @Cacheable key is a SimpleKey{masterId, from, to, page, size}.
    // SimpleKey.toString() renders as "[masterId, from, to, page, size]" via Arrays.deepToString.
    // We filter by the "[masterId," prefix to evict only affected master entries, avoiding a
    // blanket clear() that would evict ALL masters on every single schedule change (thundering herd).
    // Falls back to cache.clear() for non-Caffeine caches (e.g. ConcurrentMapCache in tests).
    private void evictMasterCalendarAfterCommit(UUID masterId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                doEvictMasterCalendarByMaster(masterId);
            }
        });
    }

    /**
     * Registers an {@code afterCommit} callback that evicts the two "new bookability verdict"
     * caches whenever a master's {@code is_active} state flips (PERF/consistency MEDIUM):
     * <ul>
     *   <li>{@code master-service-bookable} for this master (short-circuits on
     *       {@code !msa.getMaster().isActive()} in {@code SlotCalculationService#hasBookableFutureSlot}),
     *       and</li>
     *   <li>{@code salon-service-catalog} for the owning salon (the catalogue query gates on
     *       {@code m.isActive = true}) — a no-op when {@code salonId} is {@code null} (independent
     *       master, no salon catalogue entry).</li>
     * </ul>
     *
     * <p>Both {@code masterId} and {@code salonId} MUST be captured synchronously by the caller
     * inside the open transaction — never lazy-loaded via {@code master.getSalon()} after commit
     * (Anti-Bug §E / §F rule 2). Mirrors the wiring already in {@code BookingService} /
     * {@code ServiceCatalogService}.
     */
    private void evictBookabilityCachesAfterCommit(UUID masterId, UUID salonId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                slotCalculationService.evictMasterAvailabilityCaches(masterId);
                if (salonId != null) {
                    salonCatalogCacheEvictor.evict(salonId);
                }
            }
        });
    }

    private void doEvictMasterCalendarByMaster(UUID masterId) {
        cachePrefixEvictor.evictByKeyPrefixNow(masterId, "master-calendar");
    }

    /**
     * Evicts only the {@code available-slots} entries that belong to the given master.
     *
     * <p>{@code SlotCalculationService} declares {@code @Cacheable(key = "{#masterId, #date,
     * #masterServiceId}")}, so the runtime key is an {@link java.util.List} whose first element is the
     * masterId — never a {@code SimpleKey}. Matching is delegated to
     * {@link com.beautica.common.cache.MasterCachePrefixEvictor#evictByKeyPrefixNow} so that key-shape
     * contract has a single implementation; this method previously carried its own
     * {@code instanceof SimpleKey} copy, which never matched and made the eviction a silent no-op.
     * Scoped to the one master rather than a blanket clear() (Anti-Bug §F rule 6 / PERF-MEDIUM-3).</p>
     */
    private void doEvictAvailableSlotsByMaster(UUID masterId) {
        cachePrefixEvictor.evictByKeyPrefixNow(masterId, "available-slots");
    }

    /**
     * Properties a caller may sort a salon's master list by.
     *
     * <p>All are scalar columns on the {@code Master} root. Deliberately excludes any path through
     * {@code m.user} — that association is {@code JOIN FETCH}ed by the query, so a dotted sort such
     * as {@code user.passwordHash} would resolve as valid JPQL and turn this endpoint into an
     * ordering oracle over credentials (see {@link SortWhitelist}).
     */
    private static final Set<String> SORTABLE_MASTER_PROPERTIES =
            Set.of("avgRating", "reviewCount", "createdAt");

    /** Applied when the caller supplies no {@code sort}; the query itself has no {@code ORDER BY}. */
    private static final Sort DEFAULT_MASTER_SORT = Sort.by(Sort.Direction.DESC, "avgRating");

    /** Mandatory unique trailing column, so OFFSET paging cannot duplicate or skip tied rows. */
    private static final Sort MASTER_ID_TIEBREAKER = Sort.by(Sort.Direction.ASC, "id");

    /**
     * The only property {@code GET /masters/me/calendar} may be sorted by. Its ID-page query is
     * rooted on {@code Booking}, so this mirrors {@code BookingService.SORTABLE_BOOKING_PROPERTIES}
     * exactly — the calendar must not expose a wider sort surface than {@code GET /bookings/me}.
     */
    private static final Set<String> SORTABLE_CALENDAR_PROPERTIES = Set.of("startsAt");

    // Fix 8: use JOIN FETCH query to eliminate per-master user lazy-loads
    @Transactional(readOnly = true)
    public Page<MasterSummaryResponse> getMastersByPage(UUID salonId, Pageable pageable) {
        Pageable safePageable = SortWhitelist.apply(
                pageable, SORTABLE_MASTER_PROPERTIES, DEFAULT_MASTER_SORT, MASTER_ID_TIEBREAKER);
        return masterRepository.findBySalonIdAndIsActiveTrueWithUser(salonId, safePageable)
                .map(MasterSummaryResponse::from);
    }

    /**
     * Returns the fully-mapped {@link MasterDetailResponse} for the authenticated master
     * identified by {@code userId}.
     *
     * <p>Combines the cache, the JOIN FETCH query, and the DTO mapping in a single
     * {@code @Transactional(readOnly = true)} boundary so all three happen inside one
     * database session (MEDIUM — two-transaction fix). The cached value is a DTO, never
     * a detached JPA entity, which eliminates {@code LazyInitializationException} on cache
     * hits (CRITICAL §E). {@code sync = true} prevents the thundering-herd on TTL expiry
     * by allowing only one thread to populate the cache entry (HIGH §F-7).
     *
     * <p>Callers in {@link com.beautica.master.controller.MasterController#getMyProfile}
     * must use this method. {@link #getMasterByUserId} is retained for other callers
     * (e.g. calendar endpoint) that only need the {@link Master} entity.
     *
     * <p><b>Why this returns {@code Optional} instead of throwing (audit-fix cycle 2, MEDIUM —
     * negative caching).</b> {@code @Cacheable} never stores an exception, so while this method
     * threw {@code NotFoundException} for "no active master row" that answer was recomputed on
     * every single request: an opted-out {@code SALON_OWNER} polling {@code GET /masters/me} paid
     * an uncached DB round-trip per hit, with the cache present but structurally unable to help.
     * Returning {@code Optional} lets Spring's cache abstraction unwrap the empty case to
     * {@code null} and store {@code NullValue.INSTANCE} (CaffeineCacheManager's default
     * {@code allowNullValues = true}, which {@code CacheConfig} deliberately leaves alone), so the
     * miss is memoised like any hit. {@code MasterController#getMyProfile} translates the empty
     * Optional into the same {@code NotFoundException} it always raised — the HTTP contract is
     * byte-identical, 404 for an opted-out owner and 403 for a {@code CLIENT}, and
     * {@code MasterControllerTest} pins both.
     *
     * <p>The {@code find*} name is not cosmetic. The signature change from
     * {@code MasterDetailResponse} to {@code Optional<MasterDetailResponse>} is a silent one for a
     * caller that only ever used the value — the compiler catches it, but a future reader would not
     * see why. Renaming forces every call site through review and matches this codebase's
     * convention that {@code find*} may be empty and {@code get*} throws.
     *
     * <p><b>Consequence for eviction — read this before adding a write path.</b> Because "no row"
     * is now a cached fact, this cache's invalidation contract is BIDIRECTIONAL. Deactivation
     * staling a positive entry was always true; creation and reactivation now stale a NEGATIVE
     * entry just as badly, and a missed evict there is the worse bug of the two — an owner who
     * enables «Я також працюю як майстер» would keep getting 404 on their own brand-new profile
     * for the rest of the 10-minute TTL, with no error anywhere to explain it. The complete,
     * named path list is the invariant block in {@link #deactivateOwnerMaster}.
     *
     * <p><b>Phase 265 — owners resolve here too, with no logic change.</b>
     * {@code findActiveByUserIdWithUserAndSalon} filters on {@code user.id} and
     * {@code isActive} only — it carries NO {@code masterType} predicate — so a
     * {@code SALON_OWNER} who has opted in as a master (an active row with
     * {@code masterType = SALON_OWNER}) is served by exactly the same lookup as a
     * {@code SALON_MASTER} or {@code INDEPENDENT_MASTER}. Widening the controller's
     * {@code @PreAuthorize} was the whole of that change.
     *
     * <p>The {@link java.util.Optional} return cannot raise {@code NonUniqueResultException}:
     * {@code masters.user_id} is {@code unique = true} ({@link Master} line ~56), and V56 adds a
     * partial unique index {@code idx_masters_user_owner_type WHERE master_type = 'SALON_OWNER'}
     * on top (mirrored on {@link Master}'s {@code @Table(indexes = ...)}, which cannot express the
     * partial {@code WHERE}). At most one owner-master row per user exists by construction.
     *
     * <p><b>This is NOT multi-salon owner-master support.</b> An owner has exactly one master row,
     * pinned to one salon by {@code createMasterForOwner}'s "already exists in a different salon"
     * {@code ConflictException}. Backend phases 271–281 (the {@code master_salons} join table and
     * everything built on it) are BLOCKED on a reversed shared-schedule premise — do not pull any
     * of that work in through this method.
     */
    @Cacheable(value = "master-detail-by-user", key = "#userId", sync = true)
    @Transactional(readOnly = true)
    public Optional<MasterDetailResponse> findMyMasterDetail(UUID userId) {
        return masterRepository.findActiveByUserIdWithUserAndSalon(userId)
                .map(master -> {
                    requireAttached(master);
                    var hours = workingHoursRepository.findByMasterIdAndIsActiveTrue(master.getId());
                    UUID masterCityId = master.getUser().getCityId();
                    UUID oblastId = resolveOblastId(masterCityId);
                    UUID salonOblastId = resolveSalonOblastId(master, masterCityId, oblastId);
                    return MasterDetailResponse.from(master, hours, oblastId, salonOblastId);
                });
    }

    @Cacheable(value = "master-by-user", key = "#userId", sync = true)
    @Transactional(readOnly = true)
    public Master getMasterByUserId(UUID userId) {
        return masterRepository.findActiveByUserIdWithUserAndSalon(userId)
                .orElseThrow(() -> new NotFoundException("Master not found"));
    }

    // sync = true (§F-7): the master's app polls this key and the TTL is only 30s, so without it
    // every expiry admits N concurrent threads into the full two-query hydrate below. Compatible
    // with BookingService#doEvictMasterCalendarEntries, which evicts by scanning the native
    // Caffeine keySet for this master's prefix — Caffeine's sync loader holds a per-key lock
    // during load only, and never blocks or is blocked by a concurrent removal.
    @Cacheable(value = "master-calendar",
            key = "{#masterId, #from, #to, #pageable.pageNumber, #pageable.pageSize}",
            sync = true)
    @Transactional(readOnly = true)
    public Page<BookingResponse> getMasterCalendar(UUID masterId, LocalDate from, LocalDate to, Pageable pageable) {
        OffsetDateTime fromOdt = from.atStartOfDay(TimeZones.KYIV).toOffsetDateTime();
        OffsetDateTime toOdt = to.plusDays(1).atStartOfDay(TimeZones.KYIV).toOffsetDateTime();

        // The ID-page query's root is Booking, whose `client`/`master` associations reach User —
        // so an unguarded sort here resolves `client.passwordHash` / `client.email` as valid JPQL.
        // Whitelisted to the single property the calendar actually orders by, matching
        // BookingService.SORTABLE_BOOKING_PROPERTIES. No tiebreaker: the query hardcodes
        // `ORDER BY b.startsAt ASC` and Spring appends the caller's sort after it.
        Pageable safePageable = SortWhitelist.apply(
                pageable, SORTABLE_CALENDAR_PROPERTIES, Sort.unsorted(), null);

        // Two-query pattern (Fix H1 — HHH90003004): paginate on IDs only so the DB
        // applies LIMIT/OFFSET correctly, then hydrate the full graph for those IDs.
        Page<UUID> idPage = bookingRepository.findActiveIdsByMasterIdAndStartsAtBetween(
                masterId, fromOdt, toOdt, safePageable);

        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, idPage.getTotalElements());
        }

        List<Booking> hydrated = bookingRepository.findAllByIdsWithGraph(idPage.getContent());
        Map<UUID, Booking> byId = hydrated.stream()
                .collect(Collectors.toMap(Booking::getId, Function.identity()));
        OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        List<BookingResponse> ordered = idPage.getContent().stream()
                .map(byId::get)
                .map(b -> BookingResponse.from(b, now))
                .toList();
        return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
    }

    /**
     * Publishes a {@link SalonStaffChangedEvent} so the salon's rating is recomputed after this
     * transaction commits (mobile Phase 111). No-op for a {@code null} salon — an
     * {@code INDEPENDENT_MASTER} belongs to none, and the event forbids a null id.
     *
     * <p>Fired unconditionally from every staff-set mutation, including ones that provably cannot
     * move the number today (a freshly created master row has no reviews anywhere). The recalc is
     * an idempotent single UPDATE on a path that runs at most once per invite/deactivation, so
     * the uniform "any staff change publishes" invariant is worth more than saving that write —
     * it removes the need for each call site to re-derive whether it is a no-op, which is exactly
     * the kind of reasoning that silently rots.
     */
    private void publishSalonStaffChanged(UUID salonId) {
        if (salonId == null) {
            return;
        }
        eventPublisher.publishEvent(new SalonStaffChangedEvent(salonId));
    }
}
