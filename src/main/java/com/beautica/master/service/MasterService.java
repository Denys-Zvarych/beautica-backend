package com.beautica.master.service;

import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MasterService {

    private final MasterRepository masterRepository;
    private final UserRepository userRepository;
    private final SalonRepository salonRepository;
    private final WorkingHoursRepository workingHoursRepository;
    private final ScheduleExceptionRepository scheduleExceptionRepository;
    private final AuthorizationService authorizationService;

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

        var master = masterRepository.findByIdWithSalonAndOwner(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));

        authorizationService.enforceCanManageMasterSchedule(actorId, master);

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

        return workingHoursRepository.saveAll(toSave).stream()
                .map(WorkingHoursResponse::from)
                .toList();
    }

    @Transactional
    public ScheduleException addScheduleException(
            UUID actorId, UUID masterId, ScheduleExceptionRequest request) {

        var master = masterRepository.findByIdWithSalonAndOwner(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));

        authorizationService.enforceCanManageMasterSchedule(actorId, master);

        var existing = scheduleExceptionRepository.findByMasterIdAndDate(masterId, request.date());
        if (existing.isPresent()) {
            var ex = existing.get();
            ex.setReason(request.reason());
            ex.setNote(request.note());
            return scheduleExceptionRepository.save(ex);
        }

        var exception = ScheduleException.builder()
                .master(master)
                .date(request.date())
                .reason(request.reason())
                .note(request.note())
                .createdAt(OffsetDateTime.now())
                .build();

        return scheduleExceptionRepository.save(exception);
    }

    @Transactional
    public void removeScheduleException(UUID actorId, UUID masterId, LocalDate date) {
        var master = masterRepository.findByIdWithSalonAndOwner(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));

        authorizationService.enforceCanManageMasterSchedule(actorId, master);

        scheduleExceptionRepository.findByMasterIdAndDate(masterId, date)
                .ifPresent(scheduleExceptionRepository::delete);
    }

    @Transactional
    public void deactivateMaster(UUID actorId, UUID masterId) {
        var master = masterRepository.findByIdWithSalonAndOwner(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found"));

        authorizationService.enforceCanManageMaster(actorId, master);

        master.setActive(false);
        masterRepository.save(master);
    }

    // Fix 8: use JOIN FETCH query to eliminate per-master user lazy-loads
    @Transactional(readOnly = true)
    public Page<MasterSummaryResponse> getMastersByPage(UUID salonId, Pageable pageable) {
        return masterRepository.findBySalonIdAndIsActiveTrueWithUser(salonId, pageable)
                .map(MasterSummaryResponse::from);
    }
}
