package com.beautica.master.service;

import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.TimeZones;
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
import com.beautica.salon.repository.SalonRepository;
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

    // Fix 6: use findByIdWithSalonAndOwner to eliminate 2-4 lazy SELECTs per request
    @Transactional(readOnly = true)
    public MasterDetailResponse getMasterDetail(UUID masterId) {
        var master = masterRepository.findByIdWithSalonAndOwner(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));

        var hours = workingHoursRepository.findByMasterIdAndIsActiveTrue(masterId);
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
        evictMasterCalendarAfterCommit();
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

        evictMasterCalendarAfterCommit();
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
        evictMasterCalendarAfterCommit();
    }

    @Transactional
    public void deactivateMaster(UUID actorId, UUID masterId) {
        // Ownership already enforced by @PreAuthorize("@authz.canManageMaster(...)") on
        // the controller — no redundant DB round-trip needed here.
        var master = masterRepository.findByIdWithSalonAndOwner(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));

        master.setActive(false);
        // Hibernate dirty-checking flushes the mutation on commit; no explicit save() needed.
        evictMasterCalendarAfterCommit();

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
    private void evictMasterCalendarAfterCommit() {
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
