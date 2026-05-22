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
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.dto.ScheduleExceptionRequest;
import com.beautica.master.dto.WorkingHoursRequest;
import com.beautica.master.dto.WorkingHoursResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.entity.ScheduleException;
import com.beautica.master.entity.WorkingHours;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.ScheduleExceptionRepository;
import com.beautica.master.repository.WorkingHoursRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MasterService {

    private final MasterRepository masterRepository;
    private final UserRepository userRepository;
    private final SalonRepository salonRepository;
    private final WorkingHoursRepository workingHoursRepository;
    private final ScheduleExceptionRepository scheduleExceptionRepository;
    private final BookingRepository bookingRepository;
    private final CacheManager cacheManager;

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

        return masterRepository.save(master);
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

        return masterRepository.save(master);
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
        var existing = masterRepository.findByUserId(owner.getId());
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
        return masterRepository.save(master);
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

    // Fix 6: use findByIdWithSalonAndOwner to eliminate 2-4 lazy SELECTs per request
    @Transactional(readOnly = true)
    public MasterDetailResponse getMasterDetail(UUID masterId) {
        var master = masterRepository.findByIdWithSalonAndOwner(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));

        var hours = workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId);
        return MasterDetailResponse.from(master, hours);
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
        return MasterDetailResponse.from(master, hours);
    }

    // Fix 3 + Fix 7: use shared authorizationService, batch-load all days, saveAll
    @Transactional
    public List<WorkingHoursResponse> upsertWorkingHours(
            UUID actorId, UUID masterId, List<WorkingHoursRequest> requests) {

        // Ownership already enforced by @PreAuthorize("@authz.canManageMasterSchedule(...)") on
        // the controller — no redundant DB round-trip needed here.
        var master = masterRepository.findByIdWithSalonAndOwner(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));

        Map<Integer, WorkingHours> byDay = workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId)
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

    @Transactional
    public ScheduleException addScheduleException(
            UUID actorId, UUID masterId, ScheduleExceptionRequest request) {

        // Ownership already enforced by @PreAuthorize("@authz.canManageMasterSchedule(...)") on
        // the controller — no redundant DB round-trip needed here.
        var master = masterRepository.findByIdWithSalonAndOwner(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));

        var existing = scheduleExceptionRepository.findByMasterIdAndDate(masterId, request.date());
        ScheduleException result;
        if (existing.isPresent()) {
            var ex = existing.get();
            ex.setReason(request.reason());
            ex.setNote(request.note());
            result = scheduleExceptionRepository.save(ex);
        } else {
            var exception = ScheduleException.builder()
                    .master(master)
                    .date(request.date())
                    .reason(request.reason())
                    .note(request.note())
                    .build();
            result = scheduleExceptionRepository.save(exception);
        }

        evictMasterCalendarAfterCommit(masterId);
        return result;
    }

    @Transactional
    public void removeScheduleException(UUID actorId, UUID masterId, LocalDate date) {
        // Ownership already enforced by @PreAuthorize("@authz.canManageMasterSchedule(...)") on
        // the controller — no redundant DB round-trip needed here.
        masterRepository.findByIdWithSalonAndOwner(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));

        scheduleExceptionRepository.findByMasterIdAndDate(masterId, date)
                .ifPresent(scheduleExceptionRepository::delete);
        evictMasterCalendarAfterCommit(masterId);
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
        var master = masterRepository.findByUserId(actorUserId)
                .filter(m -> m.getMasterType() == MasterType.SALON_OWNER)
                .filter(m -> m.getSalon() != null && m.getSalon().getId().equals(salonId))
                .orElseThrow(() -> new NotFoundException("Owner master profile not found"));

        master.setActive(false);
        // Hibernate dirty-checking flushes the mutation on commit; no explicit save() needed.
        evictMasterCalendarAfterCommit(master.getId());

        // Evict stale master-by-user entry so the deactivated master fails isActive guards for
        // callers using the cache. actorUserId is available directly without a JOIN FETCH because
        // findByUserId already filtered on it — no lazy-load risk.
        final UUID masterUserId = actorUserId;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Cache c = cacheManager.getCache("master-by-user");
                    if (c != null) {
                        c.evict(masterUserId);
                    }
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
                }
            });
        }
    }

    // Eviction is registered as a post-commit callback rather than via @CacheEvict.
    // @CacheEvict fires before the transaction commits, allowing a concurrent reader
    // to repopulate the cache with stale data within the commit window.
    // Registering afterCommit() ensures the cache is cleared only after the write is durable.
    // Guard: synchronization must be active (i.e. called within a @Transactional context).
    //
    // Why cache.clear() instead of per-key eviction:
    // The @Cacheable key is a compound SimpleKey{masterId, from, to, pageNumber, pageSize}.
    // SimpleKey.toString() produces "[uuid, from, to, ...]" (leading bracket), so a
    // prefix match on masterId.toString() never fires. Reconstructing every possible
    // (from, to, page) combination for targeted eviction is not feasible. cache.clear()
    // is safe here because it is scoped exclusively to the "master-calendar" cache — no
    // other data lives there — and the 30-second TTL already bounds the worst-case
    // stale window for unaffected masters.
    private void evictMasterCalendarAfterCommit(UUID masterId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Cache cache = cacheManager.getCache("master-calendar");
                if (cache != null) {
                    cache.clear();
                }
            }
        });
    }

    // Fix 8: use JOIN FETCH query to eliminate per-master user lazy-loads
    @Transactional(readOnly = true)
    public Page<MasterSummaryResponse> getMastersByPage(UUID salonId, Pageable pageable) {
        return masterRepository.findBySalonIdAndIsActiveTrueWithUser(salonId, pageable)
                .map(MasterSummaryResponse::from);
    }

    @Cacheable(value = "master-by-user", key = "#userId")
    @Transactional(readOnly = true)
    public Master getMasterByUserId(UUID userId) {
        return masterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Master not found"));
    }

    @Cacheable(value = "master-calendar", key = "{#masterId, #from, #to, #pageable.pageNumber, #pageable.pageSize}")
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
