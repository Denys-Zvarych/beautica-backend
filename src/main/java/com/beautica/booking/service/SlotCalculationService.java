package com.beautica.booking.service;

import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.util.TimeSlotCalculator;
import com.beautica.common.util.TimeSlotCalculator.TimeRange;
import com.beautica.master.entity.WorkingHours;
import com.beautica.master.repository.ScheduleExceptionRepository;
import com.beautica.master.repository.WorkingHoursRepository;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.repository.MasterServiceRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class SlotCalculationService {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final Duration SLOT_STEP = Duration.ofMinutes(30);

    private final WorkingHoursRepository workingHoursRepository;
    private final BookingRepository bookingRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final ScheduleExceptionRepository scheduleExceptionRepository;
    private final TimeSlotCalculator timeSlotCalculator;
    private final Clock clock;

    public SlotCalculationService(
            WorkingHoursRepository workingHoursRepository,
            BookingRepository bookingRepository,
            MasterServiceRepository masterServiceRepository,
            ScheduleExceptionRepository scheduleExceptionRepository,
            TimeSlotCalculator timeSlotCalculator,
            Clock clock) {
        this.workingHoursRepository = workingHoursRepository;
        this.bookingRepository = bookingRepository;
        this.masterServiceRepository = masterServiceRepository;
        this.scheduleExceptionRepository = scheduleExceptionRepository;
        this.timeSlotCalculator = timeSlotCalculator;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "available-slots", key = "#masterId + ':' + #date + ':' + #masterServiceId")
    public List<AvailableSlotResponse> getAvailableSlots(UUID masterId, LocalDate date, UUID masterServiceId) {
        // Step 1: date range validation — cheapest guard, no DB
        LocalDate today = LocalDate.now(clock.withZone(KYIV));
        if (date.isBefore(today)) {
            throw new BusinessException("date is in the past");
        }
        if (date.isAfter(today.plusDays(180))) {
            throw new BusinessException("date too far ahead");
        }

        // Step 2: load master service — validated first to close the working-hours oracle
        MasterServiceAssignment msa = masterServiceRepository
                .findByMasterIdAndIdWithGraph(masterId, masterServiceId)
                .orElseThrow(() -> new NotFoundException("masterService not found"));

        if (!msa.isActive()) {
            throw new BusinessException("master service is inactive");
        }

        // Step 3: compute effective duration (override takes precedence over base)
        int durationMinutes = msa.getDurationOverrideMinutes() != null
                ? msa.getDurationOverrideMinutes()
                : msa.getServiceDefinition().getBaseDurationMinutes();
        int bufferMinutes = msa.getServiceDefinition().getBufferMinutesAfter();
        Duration totalDuration = Duration.ofMinutes(durationMinutes + bufferMinutes);

        // Step 4: upper-bound guard — durationOverride max 480 min + bufferMinutesAfter max 120 min
        if (totalDuration.toMinutes() > 600) {
            throw new BusinessException("total service duration exceeds maximum allowed");
        }

        // Step 5: check working hours for the requested day of week
        int dayOfWeek = date.getDayOfWeek().getValue(); // 1=Mon..7=Sun
        Optional<WorkingHours> workingHoursOpt =
                workingHoursRepository.findByMasterIdAndDayOfWeek(masterId, dayOfWeek);
        if (workingHoursOpt.isEmpty()) {
            return List.of();
        }
        WorkingHours workingHours = workingHoursOpt.get();

        // Step 6: check schedule exception for this specific date
        boolean hasException = scheduleExceptionRepository
                .findByMasterIdAndDate(masterId, date)
                .isPresent();
        if (hasException) {
            return List.of();
        }

        // Step 7: compute day window in OffsetDateTime for the booking query
        OffsetDateTime dayStart = date.atStartOfDay(KYIV).toOffsetDateTime();
        OffsetDateTime dayEnd   = date.plusDays(1).atStartOfDay(KYIV).toOffsetDateTime();

        // Step 8: load existing bookings that overlap the day window (PENDING + CONFIRMED only)
        List<TimeRange> occupied = bookingRepository
                .findOverlappingByMaster(masterId, dayStart, dayEnd)
                .stream()
                .map(b -> new TimeRange(b.getStartsAt().toInstant(), b.getEndsAt().toInstant()))
                .toList();

        // Step 9: delegate to TimeSlotCalculator
        List<TimeRange> free = timeSlotCalculator.calculateAvailableSlots(
                date,
                workingHours.getStartTime(),
                workingHours.getEndTime(),
                totalDuration,
                SLOT_STEP,
                occupied);

        // Step 10: map to response DTOs with Kyiv zone
        return free.stream()
                .map(r -> new AvailableSlotResponse(
                        r.start().atZone(KYIV),
                        r.end().atZone(KYIV)))
                .toList();
    }

    // NOT_SUPPORTED: eviction must not run inside the caller's transaction — it fires after the
    // surrounding transaction suspends so the cache is only invalidated independently of commit/rollback.
    // BookingService must call this from a TransactionSynchronization.afterCommit() callback.
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @CacheEvict(value = "available-slots", key = "#masterId + ':' + #date + ':' + #masterServiceId")
    public void evictAvailableSlots(UUID masterId, LocalDate date, UUID masterServiceId) {}
}
