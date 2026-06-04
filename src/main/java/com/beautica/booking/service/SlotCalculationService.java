package com.beautica.booking.service;

import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.common.TimeZones;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.util.TimeSlotCalculator;
import com.beautica.common.util.TimeSlotCalculator.TimeRange;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.service.MasterScheduleService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class SlotCalculationService {

    private static final Duration SLOT_STEP = Duration.ofMinutes(30);

    private final BookingRepository bookingRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final MasterScheduleService masterScheduleService;
    private final TimeSlotCalculator timeSlotCalculator;
    private final Clock kyivClock;

    public SlotCalculationService(
            BookingRepository bookingRepository,
            MasterServiceRepository masterServiceRepository,
            MasterScheduleService masterScheduleService,
            TimeSlotCalculator timeSlotCalculator,
            Clock clock) {
        this.bookingRepository = bookingRepository;
        this.masterServiceRepository = masterServiceRepository;
        this.masterScheduleService = masterScheduleService;
        this.timeSlotCalculator = timeSlotCalculator;
        this.kyivClock = clock.withZone(TimeZones.KYIV);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "available-slots", key = "{#masterId, #date, #masterServiceId}", sync = true)
    public List<AvailableSlotResponse> getAvailableSlots(UUID masterId, LocalDate date, UUID masterServiceId) {
        // Step 1: date range validation — cheapest guard, no DB
        LocalDate today = LocalDate.now(kyivClock);
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

        // Guard: master must be active to expose any bookable slots.
        // deactivateOwnerMaster (and the general deactivateMaster) sets masters.is_active = false
        // but leaves master_services rows intact — check the master entity itself here.
        if (!msa.getMaster().isActive()) {
            return List.of();
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

        // Slot calculation is master-type agnostic: the effective-availability resolver
        // (weekly templates + per-date overrides) and bookings are keyed by master_id alone.
        // A SALON_OWNER master with a weekly template and an active master_services row is
        // bookable identically to any other master type. Master liveness (masters.is_active)
        // is checked above before reaching this point — do not remove that guard.

        // Step 5: resolve the effective availability for this date via the Phase 15.4 resolver.
        // This replaces the legacy single-WorkingHours window + schedule-exception closure check with
        // the unified model: multi-interval days, validity windows, custom-hours overrides, and
        // day-offs. NO_SCHEDULE (gap) or OVERRIDE_DAY_OFF resolve to empty intervals — no slots.
        EffectiveDayResponse effective = masterScheduleService.resolveEffectiveDay(masterId, date);
        List<WorkIntervalDto> intervals = effective.intervals();
        if (intervals == null || intervals.isEmpty()) {
            return List.of();
        }

        // Step 6: compute day window in OffsetDateTime for the booking query
        OffsetDateTime dayStart = date.atStartOfDay(TimeZones.KYIV).toOffsetDateTime();
        OffsetDateTime dayEnd   = date.plusDays(1).atStartOfDay(TimeZones.KYIV).toOffsetDateTime();

        // Step 7: load existing bookings that overlap the day window (PENDING + CONFIRMED only).
        // Loaded once for the whole day and subtracted from every interval below.
        List<TimeRange> occupied = bookingRepository
                .findOverlappingByMaster(masterId, dayStart, dayEnd)
                .stream()
                .map(b -> new TimeRange(b.getStartsAt().toInstant(), b.getEndsAt().toInstant()))
                .toList();

        // Step 8: generate candidate slots per resolved interval and union the results. Calling
        // TimeSlotCalculator once per interval is the multi-interval generalization of the legacy
        // single-window call — gaps between intervals (lunch breaks) naturally yield no slots. The
        // DST/midnight rules inside TimeSlotCalculator are untouched and still apply per interval.
        List<AvailableSlotResponse> result = new ArrayList<>();
        for (WorkIntervalDto interval : intervals) {
            List<TimeRange> free = timeSlotCalculator.calculateAvailableSlots(
                    date,
                    interval.startTime(),
                    interval.endTime(),
                    totalDuration,
                    SLOT_STEP,
                    occupied);
            for (TimeRange r : free) {
                result.add(new AvailableSlotResponse(
                        r.start().atZone(TimeZones.KYIV),
                        r.end().atZone(TimeZones.KYIV)));
            }
        }
        return result;
    }

    // NOT_SUPPORTED: eviction must not run inside the caller's transaction — it fires after the
    // surrounding transaction suspends so the cache is only invalidated independently of commit/rollback.
    // BookingService must call this from a TransactionSynchronization.afterCommit() callback.
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    @CacheEvict(value = "available-slots", key = "{#masterId, #date, #masterServiceId}")
    public void evictAvailableSlots(UUID masterId, LocalDate date, UUID masterServiceId) {}
}
