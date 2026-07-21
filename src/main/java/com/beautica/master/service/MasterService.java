package com.beautica.master.service;

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
import com.beautica.location.entity.City;
import com.beautica.location.entity.Oblast;
import com.beautica.location.repository.CityRepository;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.dto.WorkingHoursRequest;
import com.beautica.master.dto.WorkingHoursResponse;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final CityRepository cityRepository;
    private final com.beautica.booking.service.BookingSlugService bookingSlugService;
    private final AuthorizationService authorizationService;
    private final SlotCalculationService slotCalculationService;
    private final SalonCatalogCacheEvictor salonCatalogCacheEvictor;

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
        return saved;
    }

    @Transactional
    public Master createMasterFromInvite(UUID userId, UUID salonId) {
        var user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        var salon = salonRepository.findById(salonId)
                .orElseThrow(() -> new NotFoundException("Salon not found"));

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
     * {@code findByIdWithSalonAndOwner} JOIN FETCH and the follow-up
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
    // Fix 6: use findByIdWithSalonAndOwner to eliminate 2-4 lazy SELECTs per request
    @Cacheable(value = "master-detail", key = "#masterId", sync = true)
    @Transactional(readOnly = true)
    public MasterDetailResponse getMasterDetail(UUID masterId) {
        var master = masterRepository.findByIdWithSalonAndOwner(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));

        var hours = workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId);
        UUID oblastId = resolveOblastId(master.getUser().getCityId());
        return MasterDetailResponse.from(master, hours, oblastId);
    }

    /**
     * Entity overload — avoids a redundant {@code findByIdWithSalonAndOwner} graph-fetch when
     * the caller already holds the {@link Master} entity in the Hibernate first-level cache
     * (MEDIUM-2). The entity must have its {@code salon} and {@code user} associations
     * reachable (i.e. created via {@link #createMasterForOwner(UUID, UUID)} within the same
     * transaction, or loaded via a graph query).
     *
     * <p>Do NOT remove the {@link #getMasterDetail(UUID)} overload — other callers depend on it.
     */
    @Transactional(readOnly = true)
    public MasterDetailResponse getMasterDetail(Master master) {
        var hours = workingHoursRepository.findByMasterIdAndIsActiveTrue(master.getId());
        UUID oblastId = resolveOblastId(master.getUser().getCityId());
        return MasterDetailResponse.from(master, hours, oblastId);
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
        var master = masterRepository.findByIdWithSalonAndOwner(masterId)
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
     * {@link #deactivateMaster(UUID, UUID)} to avoid a redundant {@code findByIdWithSalonAndOwner}
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
    }

    @Transactional
    public void deactivateMaster(UUID actorId, UUID masterId) {
        // Ownership already enforced by @PreAuthorize("@authz.canManageMaster(...)") on
        // the controller — no redundant DB round-trip needed here.
        var master = masterRepository.findByIdWithSalonAndOwner(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));

        master.setActive(false);
        // Hibernate dirty-checking flushes the mutation on commit; no explicit save() needed.
        evictMasterCalendarAfterCommit(masterId);

        // Deactivation flips is_active FALSE — a sole-performer's SALON service must vanish from
        // the booking master-list and the salon catalogue immediately, not after the 60s TTL.
        // salon is JOIN-FETCHed by findByIdWithSalonAndOwner and may be null (INDEPENDENT_MASTER,
        // a no-op for the catalogue evict); capture its id synchronously inside the tx (§E / §F-2).
        evictBookabilityCachesAfterCommit(
                masterId,
                master.getSalon() != null ? master.getSalon().getId() : null);

        // Capture the user UUID while the transaction is still open (user is JOIN FETCH-ed by
        // findByIdWithSalonAndOwner, so getUser() is initialized). A stale master-by-user entry
        // would allow the deactivated master to pass the isActive guard for up to the cache TTL.
        final UUID masterUserId = master.getUser().getId();
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
                }
            });
        }
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
        var master = masterRepository.findByIdWithSalonAndOwner(masterId)
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

        // `master` was loaded via findByIdWithSalonAndOwner in THIS @Transactional, so it is a
        // managed entity — Hibernate dirty-checking flushes the salon mutation on commit.
        master.setSalon(destinationSalon);

        // Evict the public master-detail cache (mirrors deactivateMaster) so GET
        // /api/v1/masters/{masterId} reflects the new salon immediately instead of serving the
        // stale salon for up to the cache TTL (Anti-Bug §F rule 2).
        final UUID rotatedMasterId = master.getId();
        // Captured while the transaction is still open — master.getUser() is JOIN FETCH-ed by
        // findByIdWithSalonAndOwner, so this is an initialized reference, not a lazy proxy.
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

        return MasterSummaryResponse.from(master);
    }

    /**
     * Resolves the {@code oblastId} for the given city in a single JOIN FETCH round-trip.
     *
     * <p>Uses {@link CityRepository#findByIdWithOblast} so the {@code LAZY} oblast
     * association is initialised within the active transaction — no secondary SELECT
     * and no LazyInitializationException (Anti-Bug §E).
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
        return cityRepository.findByIdWithOblast(cityId)
                .map(City::getOblast)
                .map(Oblast::getId)
                .orElse(null);
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
                slotCalculationService.evictBookableFutureSlotsByMaster(masterId);
                if (salonId != null) {
                    salonCatalogCacheEvictor.evict(salonId);
                }
            }
        });
    }

    private void doEvictMasterCalendarByMaster(UUID masterId) {
        Cache springCache = cacheManager.getCache("master-calendar");
        if (springCache == null) {
            return;
        }
        Object nativeCache = springCache.getNativeCache();
        if (nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache) {
            String masterIdPrefix = "[" + masterId + ",";
            caffeineCache.asMap().keySet().removeIf(k ->
                    k instanceof org.springframework.cache.interceptor.SimpleKey
                            && k.toString().contains(masterIdPrefix));
        } else {
            // Fallback for non-Caffeine caches (e.g., ConcurrentMapCache in tests).
            springCache.clear();
        }
    }

    /**
     * Evicts only the {@code available-slots} entries that belong to the given master.
     *
     * <p>The {@code available-slots} cache key is a {@link org.springframework.cache.interceptor.SimpleKey}
     * whose first element is the masterId UUID (see {@code SlotCalculationService}).
     * SimpleKey.toString() renders as {@code "[masterId, date, masterServiceId]"} — we filter
     * on the {@code "[masterId,"} prefix so we touch only the affected master's entries,
     * avoiding a blanket clear() (Anti-Bug §F rule 6 / PERF-MEDIUM-3).</p>
     *
     * <p>Falls back to {@code cache.clear()} for non-Caffeine caches.</p>
     */
    private void doEvictAvailableSlotsByMaster(UUID masterId) {
        Cache springCache = cacheManager.getCache("available-slots");
        if (springCache == null) {
            return;
        }
        Object nativeCache = springCache.getNativeCache();
        if (nativeCache instanceof com.github.benmanes.caffeine.cache.Cache<?, ?> caffeineCache) {
            String masterIdPrefix = "[" + masterId + ",";
            caffeineCache.asMap().keySet().removeIf(k ->
                    k instanceof org.springframework.cache.interceptor.SimpleKey
                            && k.toString().contains(masterIdPrefix));
        } else {
            // Fallback for non-Caffeine caches (e.g., ConcurrentMapCache in tests).
            springCache.clear();
        }
    }

    // Fix 8: use JOIN FETCH query to eliminate per-master user lazy-loads
    @Transactional(readOnly = true)
    public Page<MasterSummaryResponse> getMastersByPage(UUID salonId, Pageable pageable) {
        return masterRepository.findBySalonIdAndIsActiveTrueWithUser(salonId, pageable)
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
     */
    @Cacheable(value = "master-detail-by-user", key = "#userId", sync = true)
    @Transactional(readOnly = true)
    public MasterDetailResponse getMyMasterDetail(UUID userId) {
        Master master = masterRepository.findActiveByUserIdWithUserAndSalon(userId)
                .orElseThrow(() -> new NotFoundException("Master not found"));
        var hours = workingHoursRepository.findByMasterIdAndIsActiveTrue(master.getId());
        UUID oblastId = resolveOblastId(master.getUser().getCityId());
        return MasterDetailResponse.from(master, hours, oblastId);
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

        // Two-query pattern (Fix H1 — HHH90003004): paginate on IDs only so the DB
        // applies LIMIT/OFFSET correctly, then hydrate the full graph for those IDs.
        Page<UUID> idPage = bookingRepository.findActiveIdsByMasterIdAndStartsAtBetween(
                masterId, fromOdt, toOdt, pageable);

        if (idPage.isEmpty()) {
            return new PageImpl<>(List.of(), pageable, idPage.getTotalElements());
        }

        List<Booking> hydrated = bookingRepository.findAllByIdsWithGraph(idPage.getContent());
        Map<UUID, Booking> byId = hydrated.stream()
                .collect(Collectors.toMap(Booking::getId, Function.identity()));
        List<BookingResponse> ordered = idPage.getContent().stream()
                .map(byId::get)
                .map(BookingResponse::from)
                .toList();
        return new PageImpl<>(ordered, pageable, idPage.getTotalElements());
    }
}
