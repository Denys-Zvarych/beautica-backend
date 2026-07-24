package com.beautica.booking.service;

import com.beautica.booking.dto.AvailableSlotResponse;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.BookingTimeRange;
import com.beautica.common.cache.MasterCachePrefixEvictor;
import com.beautica.master.service.ScheduleDateMath;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.util.TimeSlotCalculator;
import com.beautica.common.util.TimeSlotCalculator.TimeRange;
import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.dto.EffectiveDaySource;
import com.beautica.master.dto.MasterWorkingDayResponse;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.entity.Master;
import com.beautica.master.service.MasterScheduleService;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.repository.MasterServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SlotCalculationService — unit")
class SlotCalculationServiceTest {

    // Fixed clock: 2026-05-07T00:00:00Z — Kyiv is UTC+3 so this is 2026-05-07T03:00 Kyiv
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-07T00:00:00Z"), ZoneOffset.UTC);

    // Shared active master stub — used in every builder-constructed MasterServiceAssignment
    // to satisfy the inactive-master guard added to getAvailableSlots().
    private static final Master ACTIVE_MASTER = Master.builder().isActive(true).build();

    @Mock
    private BookingRepository bookingRepository;

    @Mock
    private MasterServiceRepository masterServiceRepository;

    @Mock
    private MasterScheduleService masterScheduleService;

    @Mock
    private TimeSlotCalculator timeSlotCalculator;

    @Mock
    private ScheduleDateMath dateMath;

    @Mock
    private MasterCachePrefixEvictor cacheEvictor;

    private SlotCalculationService slotCalculationService;

    @BeforeEach
    void setUp() {
        slotCalculationService = new SlotCalculationService(
                bookingRepository,
                masterServiceRepository,
                masterScheduleService,
                dateMath,
                timeSlotCalculator,
                cacheEvictor,
                clock);
    }

    // --- helpers: build EffectiveDayResponse the resolver would return for a date ---

    private static EffectiveDayResponse templateDay(LocalDate date, LocalTime start, LocalTime end) {
        return new EffectiveDayResponse(
                date,
                EffectiveDaySource.TEMPLATE,
                List.of(new WorkIntervalDto(start, end)));
    }

    private static EffectiveDayResponse dayOff(LocalDate date) {
        return new EffectiveDayResponse(
                date,
                EffectiveDaySource.OVERRIDE_DAY_OFF,
                List.of());
    }

    private static EffectiveDayResponse noSchedule(LocalDate date) {
        return new EffectiveDayResponse(
                date,
                EffectiveDaySource.NO_SCHEDULE,
                List.of());
    }

    @Test
    @DisplayName("should return empty list when no working hours exist for the requested day")
    void should_returnEmpty_when_noWorkingHoursForRequestedDay() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        // Use today in Kyiv — 2026-05-07 (UTC+3, so midnight UTC = 03:00 Kyiv → still May 7)
        LocalDate date = LocalDate.of(2026, 5, 7);

        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(serviceDefinition)
                .master(ACTIVE_MASTER)
                .isActive(true)
                .build();

        // masterServiceRepository is called first — stub it before the schedule resolver
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        // No weekly template covers the date → NO_SCHEDULE with empty intervals
        when(masterScheduleService.resolveEffectiveDay(masterId, date))
                .thenReturn(noSchedule(date));

        List<AvailableSlotResponse> result =
                slotCalculationService.getAvailableSlots(masterId, date, masterServiceId);

        assertThat(result).isEmpty();
        verify(timeSlotCalculator, never()).calculateAvailableSlots(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("should return empty list when master has a schedule exception on the requested date")
    void should_returnEmpty_when_masterHasScheduleException() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 7);

        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(serviceDefinition)
                .master(ACTIVE_MASTER)
                .isActive(true)
                .build();

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        // A per-date DAY_OFF override closes the date → empty intervals
        when(masterScheduleService.resolveEffectiveDay(masterId, date))
                .thenReturn(dayOff(date));

        List<AvailableSlotResponse> result =
                slotCalculationService.getAvailableSlots(masterId, date, masterServiceId);

        assertThat(result).isEmpty();
        verify(timeSlotCalculator, never()).calculateAvailableSlots(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("should throw 400 BusinessException when the requested date is in the past")
    void should_throw400_when_dateIsInThePast() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        // Clock is fixed to 2026-05-07T00:00Z → today in Kyiv (UTC+3) is 2026-05-07
        // Pass yesterday: 2026-05-06
        LocalDate yesterday = LocalDate.of(2026, 5, 6);

        assertThatThrownBy(() ->
                slotCalculationService.getAvailableSlots(masterId, yesterday, masterServiceId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("past");
    }

    @Test
    @DisplayName("should throw 400 BusinessException when the requested date is more than 180 days ahead")
    void should_throw400_when_dateMoreThan180DaysAhead() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        // Clock is fixed to 2026-05-07T00:00Z → today in Kyiv is 2026-05-07
        // today + 181 days exceeds the 180-day limit
        LocalDate tooFar = LocalDate.now(clock.withZone(ZoneId.of("Europe/Kyiv"))).plusDays(181);

        assertThatThrownBy(() ->
                slotCalculationService.getAvailableSlots(masterId, tooFar, masterServiceId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("far ahead");
    }

    @Test
    @DisplayName("should delegate to calculator when working hours exist and no schedule exception is present")
    void should_delegateToCalculator_when_workingHoursExistAndNoException() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 7);

        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(serviceDefinition)
                .master(ACTIVE_MASTER)
                .isActive(true)
                .build();

        // One available slot returned by the calculator
        Instant slotStart = date.atTime(LocalTime.of(10, 0))
                .atZone(ZoneId.of("Europe/Kyiv")).toInstant();
        Instant slotEnd = slotStart.plus(Duration.ofMinutes(60));
        List<TimeRange> calculatorResult = List.of(new TimeRange(slotStart, slotEnd));

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        // TEMPLATE day 09:00–17:00, no override → equivalent to the legacy working-hours window
        when(masterScheduleService.resolveEffectiveDay(masterId, date))
                .thenReturn(templateDay(date, LocalTime.of(9, 0), LocalTime.of(17, 0)));
        when(bookingRepository.findOverlappingByMaster(eq(masterId), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of());
        when(timeSlotCalculator.calculateAvailableSlots(
                eq(date),
                eq(LocalTime.of(9, 0)),
                eq(LocalTime.of(17, 0)),
                eq(Duration.ofMinutes(60)),
                eq(Duration.ofMinutes(30)),
                eq(List.of()),
                any()))
                .thenReturn(calculatorResult);

        List<AvailableSlotResponse> result =
                slotCalculationService.getAvailableSlots(masterId, date, masterServiceId);

        assertThat(result).hasSize(1);
        verify(timeSlotCalculator).calculateAvailableSlots(
                eq(date),
                eq(LocalTime.of(9, 0)),
                eq(LocalTime.of(17, 0)),
                eq(Duration.ofMinutes(60)),
                eq(Duration.ofMinutes(30)),
                eq(List.of()),
                any());
    }

    @Test
    @DisplayName("should use the master service duration override when one is set, ignoring the base duration")
    void should_useOverrideDuration_when_masterServiceHasDurationOverride() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 7);

        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        // Override duration = 45 minutes (overrides the base 60)
        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(serviceDefinition)
                .durationOverrideMinutes(45)
                .master(ACTIVE_MASTER)
                .isActive(true)
                .build();

        Instant slotStart = date.atTime(LocalTime.of(10, 0))
                .atZone(ZoneId.of("Europe/Kyiv")).toInstant();
        Instant slotEnd = slotStart.plus(Duration.ofMinutes(45));
        List<TimeRange> calculatorResult = List.of(new TimeRange(slotStart, slotEnd));

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveDay(masterId, date))
                .thenReturn(templateDay(date, LocalTime.of(9, 0), LocalTime.of(17, 0)));
        when(bookingRepository.findOverlappingByMaster(eq(masterId), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of());
        when(timeSlotCalculator.calculateAvailableSlots(
                eq(date),
                eq(LocalTime.of(9, 0)),
                eq(LocalTime.of(17, 0)),
                eq(Duration.ofMinutes(45)),
                eq(Duration.ofMinutes(30)),
                eq(List.of()),
                any()))
                .thenReturn(calculatorResult);

        List<AvailableSlotResponse> result =
                slotCalculationService.getAvailableSlots(masterId, date, masterServiceId);

        assertThat(result).hasSize(1);
        // Verify that the calculator was called with the override duration (45), not the base (60)
        verify(timeSlotCalculator).calculateAvailableSlots(
                eq(date),
                eq(LocalTime.of(9, 0)),
                eq(LocalTime.of(17, 0)),
                eq(Duration.ofMinutes(45)),
                eq(Duration.ofMinutes(30)),
                eq(List.of()),
                any());
    }

    @Test
    @DisplayName("should throw 400 BusinessException when total service duration including buffer exceeds 600 minutes")
    void should_throw400_when_totalDurationExceedsMaximum() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 7);

        // durationOverrideMinutes=480, bufferMinutesAfter=121 → total=601 > 600
        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(121)
                .isActive(true)
                .build();

        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(serviceDefinition)
                .durationOverrideMinutes(480)
                .master(ACTIVE_MASTER)
                .isActive(true)
                .build();

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));

        assertThatThrownBy(() ->
                slotCalculationService.getAvailableSlots(masterId, date, masterServiceId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("exceeds maximum");

        // The duration guard fires before the schedule resolver is consulted.
        verifyNoInteractions(masterScheduleService);
        verify(timeSlotCalculator, never()).calculateAvailableSlots(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("should throwNotFound when masterService does not exist")
    void should_throwNotFound_when_masterServiceDoesNotExist() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        // Fixed clock → Kyiv today is 2026-05-07; tomorrow is valid
        LocalDate tomorrow = LocalDate.of(2026, 5, 8);

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                slotCalculationService.getAvailableSlots(masterId, tomorrow, masterServiceId))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(masterScheduleService);
    }

    /**
     * Security LOW-1 — {@code GET /slots} shares {@code loadBookableAssignment} with the working-days
     * projection, so the inactive case reports 404 here too (was 400 "master service is inactive"):
     * unknown, foreign and deactivated must be indistinguishable to a caller holding a stale id.
     */
    @Test
    @DisplayName("should throw NotFound (not 400) when masterService is inactive")
    void should_throwNotFound_when_masterServiceIsInactive() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate tomorrow = LocalDate.of(2026, 5, 8);

        MasterServiceAssignment inactiveMsa = mock(MasterServiceAssignment.class);
        when(inactiveMsa.isActive()).thenReturn(false);
        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(inactiveMsa));

        assertThatThrownBy(() ->
                slotCalculationService.getAvailableSlots(masterId, tomorrow, masterServiceId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("not found");

        verifyNoInteractions(masterScheduleService);
        verifyNoInteractions(timeSlotCalculator);
    }

    @Test
    @DisplayName("should passOccupiedRangesToCalculator when existing bookings present")
    void should_passOccupiedRangesToCalculator_when_existingBookingsPresent() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 9);

        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(serviceDefinition)
                .master(ACTIVE_MASTER)
                .isActive(true)
                .build();

        Booking mockBooking = mock(Booking.class);
        when(mockBooking.getStartsAt())
                .thenReturn(OffsetDateTime.parse("2026-05-09T09:00:00+03:00"));
        when(mockBooking.getEndsAt())
                .thenReturn(OffsetDateTime.parse("2026-05-09T10:00:00+03:00"));

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveDay(masterId, date))
                .thenReturn(templateDay(date, LocalTime.of(9, 0), LocalTime.of(18, 0)));
        when(bookingRepository.findOverlappingByMaster(any(), any(), any()))
                .thenReturn(List.of(mockBooking));
        when(timeSlotCalculator.calculateAvailableSlots(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        slotCalculationService.getAvailableSlots(masterId, date, masterServiceId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimeRange>> occupiedCaptor = ArgumentCaptor.forClass(List.class);
        verify(timeSlotCalculator).calculateAvailableSlots(
                any(), any(), any(), any(), any(), occupiedCaptor.capture(), any());

        List<TimeRange> captured = occupiedCaptor.getValue();
        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).start()).isEqualTo(Instant.parse("2026-05-09T06:00:00Z"));
        assertThat(captured.get(0).end()).isEqualTo(Instant.parse("2026-05-09T07:00:00Z"));
    }

    @Test
    @DisplayName("should include buffer in total duration when service has non-zero buffer")
    void should_includeBufferInTotalDuration_when_serviceHasNonZeroBuffer() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        // Clock is fixed to 2026-05-07T00:00Z → today in Kyiv is 2026-05-07; May 8 is tomorrow (valid)
        LocalDate date = LocalDate.of(2026, 5, 8);

        ServiceDefinition serviceDefinition = mock(ServiceDefinition.class);
        when(serviceDefinition.getBaseDurationMinutes()).thenReturn(60);
        when(serviceDefinition.getBufferMinutesAfter()).thenReturn(30);

        MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
        when(msa.isActive()).thenReturn(true);
        when(msa.getMaster()).thenReturn(ACTIVE_MASTER);
        when(msa.getDurationOverrideMinutes()).thenReturn(null);
        when(msa.getServiceDefinition()).thenReturn(serviceDefinition);

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(any(), any()))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveDay(any(), any()))
                .thenReturn(templateDay(date, LocalTime.of(9, 0), LocalTime.of(18, 0)));
        when(bookingRepository.findOverlappingByMaster(any(), any(), any()))
                .thenReturn(List.of());
        when(timeSlotCalculator.calculateAvailableSlots(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        slotCalculationService.getAvailableSlots(masterId, date, masterServiceId);

        ArgumentCaptor<Duration> totalDurationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(timeSlotCalculator).calculateAvailableSlots(
                any(), any(), any(), totalDurationCaptor.capture(), any(), any(), any());

        assertThat(totalDurationCaptor.getValue()).isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    @DisplayName("should exclude slot when buffer after previous booking fills it")
    void should_excludeSlot_when_bufferAfterPreviousBookingFillsIt() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 9);

        // Service: 60 min duration + 30 min buffer = 90 min totalDuration
        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(30)
                .isActive(true)
                .build();

        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(serviceDefinition)
                .master(ACTIVE_MASTER)
                .isActive(true)
                .build();

        // Existing booking occupies 09:00–10:00 Kyiv (06:00–07:00 UTC)
        Booking existingBooking = mock(Booking.class);
        when(existingBooking.getStartsAt())
                .thenReturn(OffsetDateTime.parse("2026-05-09T09:00:00+03:00"));
        when(existingBooking.getEndsAt())
                .thenReturn(OffsetDateTime.parse("2026-05-09T10:00:00+03:00"));

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveDay(masterId, date))
                .thenReturn(templateDay(date, LocalTime.of(9, 0), LocalTime.of(18, 0)));
        when(bookingRepository.findOverlappingByMaster(eq(masterId), any(OffsetDateTime.class), any(OffsetDateTime.class)))
                .thenReturn(List.of(existingBooking));
        // Calculator returns empty — simulates the 10:00 slot being blocked because the
        // 90-min candidate window [10:00, 11:30] overlaps the occupied range [09:00, 10:00]
        when(timeSlotCalculator.calculateAvailableSlots(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        List<AvailableSlotResponse> result =
                slotCalculationService.getAvailableSlots(masterId, date, masterServiceId);

        assertThat(result).isEmpty();

        // Confirm the calculator received totalDuration = 90 min (service 60 + buffer 30)
        ArgumentCaptor<Duration> durationCaptor = ArgumentCaptor.forClass(Duration.class);
        verify(timeSlotCalculator).calculateAvailableSlots(
                any(), any(), any(), durationCaptor.capture(), any(), any(), any());
        assertThat(durationCaptor.getValue()).isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    @DisplayName("should accept today when date is today (lower boundary)")
    void should_acceptToday_when_dateIsToday() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        // Clock fixed to 2026-05-07T00:00Z → Kyiv today is 2026-05-07
        LocalDate today = LocalDate.now(clock.withZone(ZoneId.of("Europe/Kyiv")));

        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(serviceDefinition)
                .master(ACTIVE_MASTER)
                .isActive(true)
                .build();

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveDay(any(), any()))
                .thenReturn(noSchedule(today));

        assertThatCode(() -> slotCalculationService.getAvailableSlots(masterId, today, masterServiceId))
                .as("today must be accepted as the lower boundary without throwing")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should accept max date when date is 180 days ahead (upper boundary)")
    void should_acceptMaxDate_when_dateIs180DaysAhead() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        // Clock fixed to 2026-05-07T00:00Z → Kyiv today is 2026-05-07; today+180 is the max valid date
        LocalDate maxDate = LocalDate.now(clock.withZone(ZoneId.of("Europe/Kyiv"))).plusDays(180);

        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(serviceDefinition)
                .master(ACTIVE_MASTER)
                .isActive(true)
                .build();

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveDay(any(), any()))
                .thenReturn(noSchedule(maxDate));

        assertThatCode(() -> slotCalculationService.getAvailableSlots(masterId, maxDate, masterServiceId))
                .as("today + 180 days must be accepted as the upper boundary without throwing")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should returnSlotsWithKyivZone when calculator returns time ranges")
    void should_returnSlotsWithKyivZone_when_calculatorReturnsTimeRanges() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 9);

        ServiceDefinition serviceDefinition = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(serviceDefinition)
                .master(ACTIVE_MASTER)
                .isActive(true)
                .build();

        Instant slotStart = Instant.parse("2026-05-09T06:00:00Z");
        Instant slotEnd   = Instant.parse("2026-05-09T07:00:00Z");

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveDay(masterId, date))
                .thenReturn(templateDay(date, LocalTime.of(9, 0), LocalTime.of(18, 0)));
        when(bookingRepository.findOverlappingByMaster(any(), any(), any()))
                .thenReturn(List.of());
        when(timeSlotCalculator.calculateAvailableSlots(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new TimeRange(slotStart, slotEnd)));

        List<AvailableSlotResponse> result =
                slotCalculationService.getAvailableSlots(masterId, date, masterServiceId);

        ZoneId kyiv = ZoneId.of("Europe/Kyiv");
        assertThat(result).hasSize(1);
        assertThat(result.get(0).startsAt()).isEqualTo(slotStart.atZone(kyiv));
        assertThat(result.get(0).endsAt()).isEqualTo(slotEnd.atZone(kyiv));
    }

    @Test
    @DisplayName("evictAvailableSlots — method compiles and does not throw (signature guard)")
    void should_notThrow_when_evictAvailableSlotsCalledDirectly() {
        // Compile/signature guard only — no AOP proxy active in this unit test context.
        // Cache eviction behaviour (@CacheEvict) is verified in SlotCalculationServiceCacheTest.
        assertThatCode(() -> slotCalculationService.evictAvailableSlots(
                UUID.randomUUID(), LocalDate.now(clock), UUID.randomUUID()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("should pass Kyiv day-boundary window to findOverlappingByMaster")
    void should_passDayBoundaryWindow_when_queryingOverlappingBookings() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 7);
        ZoneId kyiv = ZoneId.of("Europe/Kyiv");

        ServiceDefinition sd = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();
        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(sd)
                .master(ACTIVE_MASTER)
                .isActive(true)
                .build();

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveDay(masterId, date))
                .thenReturn(templateDay(date, LocalTime.of(9, 0), LocalTime.of(17, 0)));
        when(bookingRepository.findOverlappingByMaster(any(), any(), any()))
                .thenReturn(List.of());
        when(timeSlotCalculator.calculateAvailableSlots(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        slotCalculationService.getAvailableSlots(masterId, date, masterServiceId);

        ArgumentCaptor<OffsetDateTime> startCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        ArgumentCaptor<OffsetDateTime> endCaptor = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(bookingRepository).findOverlappingByMaster(eq(masterId), startCaptor.capture(), endCaptor.capture());

        // Service must query the full day in Kyiv time: midnight-to-midnight on the target date.
        OffsetDateTime expectedStart = date.atStartOfDay(kyiv).toOffsetDateTime();
        OffsetDateTime expectedEnd = date.plusDays(1).atStartOfDay(kyiv).toOffsetDateTime();
        assertThat(startCaptor.getValue()).isEqualTo(expectedStart);
        assertThat(endCaptor.getValue()).isEqualTo(expectedEnd);
    }

    @Test
    @DisplayName("SALON_OWNER master — getAvailableSlots returns slots from working hours when no exception")
    void should_returnSlots_forOwnerMaster() {
        // Arrange — the service is master-type agnostic; an owner-master with Mon–Fri hours
        // behaves identically to any other master type in slot calculation.
        UUID masterId        = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate date       = LocalDate.of(2026, 5, 7); // Thursday — working day

        ServiceDefinition sd = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(sd)
                .master(ACTIVE_MASTER)
                .isActive(true)
                .build();

        java.time.OffsetDateTime slotStart = date.atTime(LocalTime.of(9, 0))
                .atZone(ZoneId.of("Europe/Kyiv")).toOffsetDateTime();
        java.time.OffsetDateTime slotEnd   = slotStart.plusMinutes(60);

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        // TEMPLATE day 09:00–17:00, no override
        when(masterScheduleService.resolveEffectiveDay(masterId, date))
                .thenReturn(templateDay(date, LocalTime.of(9, 0), LocalTime.of(17, 0)));
        when(bookingRepository.findOverlappingByMaster(eq(masterId), any(), any()))
                .thenReturn(List.of());
        when(timeSlotCalculator.calculateAvailableSlots(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(new TimeRange(slotStart.toInstant(), slotEnd.toInstant())));

        // Act
        List<AvailableSlotResponse> slots = slotCalculationService.getAvailableSlots(masterId, date, masterServiceId);

        // Assert — slots returned; calculator was invoked (master-type-agnostic path taken)
        assertThat(slots).hasSize(1);
        verify(timeSlotCalculator).calculateAvailableSlots(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("SALON_OWNER master — schedule exception on target date returns empty slot list")
    void should_returnEmpty_forOwnerMaster_when_scheduleExceptionOnDate() {
        UUID masterId        = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate date       = LocalDate.of(2026, 5, 7);

        ServiceDefinition sd = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(sd)
                .master(ACTIVE_MASTER)
                .isActive(true)
                .build();

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        // A per-date DAY_OFF override closes the date → empty intervals
        when(masterScheduleService.resolveEffectiveDay(masterId, date))
                .thenReturn(dayOff(date));

        // Act
        List<AvailableSlotResponse> slots = slotCalculationService.getAvailableSlots(masterId, date, masterServiceId);

        // Assert — exception date → empty; calculator not called
        assertThat(slots).isEmpty();
        verify(timeSlotCalculator, never()).calculateAvailableSlots(any(), any(), any(), any(), any(), any(), any());
    }

    // ── Perf #3 — occupied bookings bucketed by civil day (end-exclusive) ──────────────────────
    //
    // hasBookableFutureSlot's entity core buckets the window's PENDING/CONFIRMED bookings by Kyiv-civil
    // date via loadOccupiedByDay, adding each booking to EVERY civil day it overlaps with an EXCLUSIVE
    // end ([dateOf(start) .. dateOf(end − ε)]). These two tests pin that bucketing directly by capturing
    // the occupied list handed to the calculator per walked day (the calculator is stubbed to return no
    // free slot, so the whole range is walked and every working day's bucket is observed).

    /** An active assignment on an active master with the given id and base duration + buffer. */
    private static MasterServiceAssignment activeAssignment(UUID masterId, int baseMinutes, int bufferMinutes) {
        Master master = Master.builder().id(masterId).isActive(true).build();
        ServiceDefinition sd = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(baseMinutes)
                .bufferMinutesAfter(bufferMinutes)
                .isActive(true)
                .build();
        return MasterServiceAssignment.builder()
                .id(UUID.randomUUID())
                .serviceDefinition(sd)
                .master(master)
                .isActive(true)
                .build();
    }

    /**
     * A booked interval as the availability path now sees it: a two-column projection
     * ({@link BookingTimeRange}), not a hydrated {@code Booking} entity (Perf MEDIUM-1).
     */
    private static BookingTimeRange bookingRange(String startIso, String endIso) {
        return new BookingTimeRange(OffsetDateTime.parse(startIso), OffsetDateTime.parse(endIso));
    }

    @Test
    @DisplayName("occupied bucketing — a booking ending exactly at midnight does NOT occupy the next civil day")
    void should_notOccupyNextDay_when_bookingEndsExactlyAtMidnight() {
        UUID masterId = UUID.randomUUID();
        LocalDate day1 = LocalDate.of(2026, 5, 10);
        LocalDate day2 = LocalDate.of(2026, 5, 11);
        MasterServiceAssignment msa = activeAssignment(masterId, 60, 0);

        // Booking [day1 22:00, day2 00:00) Kyiv — ends precisely at the day1→day2 civil midnight.
        BookingTimeRange midnightEnding = bookingRange("2026-05-10T22:00:00+03:00", "2026-05-11T00:00:00+03:00");

        when(masterScheduleService.resolveEffectiveRange(masterId, day1, day2))
                .thenReturn(List.of(
                        templateDay(day1, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                        templateDay(day2, LocalTime.of(9, 0), LocalTime.of(17, 0))));
        when(bookingRepository.findActiveTimeRangesByMasterInRange(eq(masterId), any(), any()))
                .thenReturn(List.of(midnightEnding));
        // No free slot on any day → the whole range is walked, so both days' buckets are handed to the calculator.
        when(timeSlotCalculator.hasAvailableSlot(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        boolean bookable = slotCalculationService.hasBookableFutureSlot(msa, day1, day2);

        assertThat(bookable)
                .as("calculator stubbed to no free slot → not bookable (verdict is incidental here)")
                .isFalse();

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimeRange>> occCaptor = ArgumentCaptor.forClass(List.class);
        verify(timeSlotCalculator, org.mockito.Mockito.times(2))
                .hasAvailableSlot(dateCaptor.capture(), any(), any(), any(), any(), occCaptor.capture(), any());

        List<LocalDate> dates = dateCaptor.getAllValues();
        List<List<TimeRange>> occupied = occCaptor.getAllValues();
        assertThat(dates).as("both civil days are walked in order").containsExactly(day1, day2);
        assertThat(occupied.get(0))
                .as("day1 (the booking's start day) receives the occupied range")
                .hasSize(1);
        assertThat(occupied.get(1))
                .as("day2 must NOT receive the midnight-ending booking (end is exclusive)")
                .isEmpty();
    }

    @Test
    @DisplayName("occupied bucketing — a multi-day booking occupies EVERY civil day it spans (including interior days)")
    void should_occupyEveryCivilDay_when_bookingSpansMultipleDays() {
        UUID masterId = UUID.randomUUID();
        LocalDate day1 = LocalDate.of(2026, 5, 10);
        LocalDate day2 = LocalDate.of(2026, 5, 11);
        LocalDate day3 = LocalDate.of(2026, 5, 12);
        MasterServiceAssignment msa = activeAssignment(masterId, 60, 0);

        // Booking [day1 22:00, day3 02:00) Kyiv — spans day1, the interior day2, and day3.
        BookingTimeRange multiDay = bookingRange("2026-05-10T22:00:00+03:00", "2026-05-12T02:00:00+03:00");
        TimeRange expected = new TimeRange(
                OffsetDateTime.parse("2026-05-10T22:00:00+03:00").toInstant(),
                OffsetDateTime.parse("2026-05-12T02:00:00+03:00").toInstant());

        when(masterScheduleService.resolveEffectiveRange(masterId, day1, day3))
                .thenReturn(List.of(
                        templateDay(day1, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                        templateDay(day2, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                        templateDay(day3, LocalTime.of(9, 0), LocalTime.of(17, 0))));
        when(bookingRepository.findActiveTimeRangesByMasterInRange(eq(masterId), any(), any()))
                .thenReturn(List.of(multiDay));
        when(timeSlotCalculator.hasAvailableSlot(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        slotCalculationService.hasBookableFutureSlot(msa, day1, day3);

        ArgumentCaptor<LocalDate> dateCaptor = ArgumentCaptor.forClass(LocalDate.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<TimeRange>> occCaptor = ArgumentCaptor.forClass(List.class);
        verify(timeSlotCalculator, org.mockito.Mockito.times(3))
                .hasAvailableSlot(dateCaptor.capture(), any(), any(), any(), any(), occCaptor.capture(), any());

        assertThat(dateCaptor.getAllValues())
                .as("all three civil days are walked in order")
                .containsExactly(day1, day2, day3);
        for (int i = 0; i < 3; i++) {
            assertThat(occCaptor.getAllValues().get(i))
                    .as("each spanned civil day (incl. interior day2) receives the multi-day booking range")
                    .containsExactly(expected);
        }
    }

    // ── Perf #4 — preloaded assignment reuse (no redundant reload on cache miss) ────────────────
    //
    // The @Cacheable wrapper hasBookableFutureSlot(masterId, masterServiceId, preloaded, from, to) must,
    // on a cache MISS (the AOP proxy is inactive in this unit context, so the body always runs), resolve
    // the assignment from `preloaded` when the caller supplies it — never re-issuing
    // findByMasterIdAndIdWithGraph. Only a caller passing null triggers the reload.

    @Test
    @DisplayName("hasBookableFutureSlot(preloaded) — a supplied assignment is reused; findByMasterIdAndIdWithGraph is NOT re-issued")
    void should_reusePreloadedAssignment_withoutReloading_when_preloadedSupplied() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 5, 10);
        LocalDate to = LocalDate.of(2026, 5, 17);
        MasterServiceAssignment preloaded = activeAssignment(masterId, 60, 0);

        // No schedule in the window → verdict false, but the reload-avoidance is what this test locks.
        when(masterScheduleService.resolveEffectiveRange(masterId, from, to)).thenReturn(List.of());
        when(bookingRepository.findActiveTimeRangesByMasterInRange(eq(masterId), any(), any())).thenReturn(List.of());

        boolean bookable = slotCalculationService.hasBookableFutureSlot(
                masterId, masterServiceId, preloaded, from, to);

        assertThat(bookable)
                .as("no usable schedule in the window → not bookable")
                .isFalse();
        verify(masterServiceRepository, never()).findByMasterIdAndIdWithGraph(any(), any());
    }

    @Test
    @DisplayName("hasBookableFutureSlot(null preloaded) — a null assignment triggers exactly one findByMasterIdAndIdWithGraph reload")
    void should_reloadAssignmentOnce_when_preloadedNull() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 5, 10);
        LocalDate to = LocalDate.of(2026, 5, 17);
        MasterServiceAssignment resolved = activeAssignment(masterId, 60, 0);

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(resolved));
        when(masterScheduleService.resolveEffectiveRange(masterId, from, to)).thenReturn(List.of());
        when(bookingRepository.findActiveTimeRangesByMasterInRange(eq(masterId), any(), any())).thenReturn(List.of());

        slotCalculationService.hasBookableFutureSlot(masterId, masterServiceId, null, from, to);

        verify(masterServiceRepository, org.mockito.Mockito.times(1))
                .findByMasterIdAndIdWithGraph(masterId, masterServiceId);
    }

    // ── getBookableWorkingDays — availability-aware calendar day-gating ─────────────────────────
    //
    // The serviceId-PRESENT mode of GET /masters/{masterId}/working-days. Unlike
    // MasterScheduleService#getClientWorkingDays (schedule shape only), a day is `working` ONLY when a
    // free range that fits the service duration survives the day's bookings AND starts at/after the
    // bookable cutoff AND falls inside the booking horizon. The free-range computation is the same
    // TimeSlotCalculator call getAvailableSlots makes (mocked here; the end-to-end agreement with the
    // slot list is pinned by the integration suite).
    //
    // Clock is fixed at 2026-05-07T00:00:00Z → today (Kyiv) = 2026-05-07, cutoff = 2026-05-07T00:15:00Z.

    @Test
    @DisplayName("should mark a day not-working when the service leaves no free range, and working when it does")
    void should_reflectFreeRanges_when_projectingBookableDays() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate fullDay = LocalDate.of(2026, 5, 20);
        LocalDate openDay = LocalDate.of(2026, 5, 21);
        MasterServiceAssignment msa = activeAssignment(masterId, 60, 0);

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveRange(masterId, fullDay, openDay))
                .thenReturn(List.of(
                        templateDay(fullDay, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                        templateDay(openDay, LocalTime.of(9, 0), LocalTime.of(17, 0))));
        when(bookingRepository.findActiveTimeRangesByMasterInRange(eq(masterId), any(), any()))
                .thenReturn(List.of());
        // fullDay: every candidate taken/below cutoff → no bookable slot. openDay: one survives.
        when(timeSlotCalculator.hasAvailableSlot(eq(fullDay), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);
        when(timeSlotCalculator.hasAvailableSlot(eq(openDay), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        List<MasterWorkingDayResponse> days = slotCalculationService.getBookableWorkingDays(
                masterId, fullDay, openDay, masterServiceId);

        assertThat(days).containsExactly(
                new MasterWorkingDayResponse(fullDay, false),
                new MasterWorkingDayResponse(openDay, true));
    }

    /**
     * The lead-time floor is now applied INSIDE the slot walk (Perf LOW-2 / MEDIUM-4): the service no
     * longer materialises free ranges and post-filters them, it hands ONE cutoff to
     * {@link TimeSlotCalculator#hasAvailableSlot} and trusts the boolean. This test therefore pins what
     * the service still owns — that the cutoff it passes is exactly {@code now + MIN_MINUTES_AHEAD},
     * derived from the injected clock, and that the SAME instant is used for every day of the projection.
     * That a candidate below the cutoff is actually rejected is pinned in {@code TimeSlotCalculatorTest}.
     */
    @Test
    @DisplayName("should pass one shared now+15min cutoff into the slot walk for every day of the projection")
    void should_passSharedBookableCutoff_when_projectingBookableDays() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate day1 = LocalDate.of(2026, 5, 20);
        LocalDate day2 = LocalDate.of(2026, 5, 21);
        MasterServiceAssignment msa = activeAssignment(masterId, 60, 0);

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveRange(masterId, day1, day2))
                .thenReturn(List.of(
                        templateDay(day1, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                        templateDay(day2, LocalTime.of(9, 0), LocalTime.of(17, 0))));
        when(bookingRepository.findActiveTimeRangesByMasterInRange(eq(masterId), any(), any()))
                .thenReturn(List.of());
        when(timeSlotCalculator.hasAvailableSlot(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        slotCalculationService.getBookableWorkingDays(masterId, day1, day2, masterServiceId);

        ArgumentCaptor<Instant> cutoffCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(timeSlotCalculator, org.mockito.Mockito.times(2))
                .hasAvailableSlot(any(), any(), any(), any(), any(), any(), cutoffCaptor.capture());

        assertThat(cutoffCaptor.getAllValues())
                .as("one cutoff — the fixed clock's now + MIN_MINUTES_AHEAD — shared by every walked day")
                .containsOnly(Instant.parse("2026-05-07T00:15:00Z"));
    }

    @Test
    @DisplayName("should mark a day not-working when it lies beyond the 180-day booking horizon")
    void should_markNotWorking_when_dayIsBeyondBookingHorizon() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate lastBookable = LocalDate.of(2026, 5, 7).plusDays(180);
        LocalDate pastHorizon = lastBookable.plusDays(1);
        MasterServiceAssignment msa = activeAssignment(masterId, 60, 0);

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveRange(masterId, lastBookable, pastHorizon))
                .thenReturn(List.of(
                        templateDay(lastBookable, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                        templateDay(pastHorizon, LocalTime.of(9, 0), LocalTime.of(17, 0))));
        when(bookingRepository.findActiveTimeRangesByMasterInRange(eq(masterId), any(), any()))
                .thenReturn(List.of());
        // Both days would be bookable — only the horizon separates them.
        when(timeSlotCalculator.hasAvailableSlot(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        List<MasterWorkingDayResponse> days = slotCalculationService.getBookableWorkingDays(
                masterId, lastBookable, pastHorizon, masterServiceId);

        assertThat(days).containsExactly(
                new MasterWorkingDayResponse(lastBookable, true),
                new MasterWorkingDayResponse(pastHorizon, false));
        verify(timeSlotCalculator, org.mockito.Mockito.times(1))
                .hasAvailableSlot(eq(lastBookable), any(), any(), any(), any(), any(), any());
        verify(timeSlotCalculator, never())
                .hasAvailableSlot(eq(pastHorizon), any(), any(), any(), any(), any(), any());
    }

    /**
     * Perf HIGH-2 — a past date is answered by date comparison alone. Before the fast path the
     * {@code &&} short-circuited only the FUTURE side (the horizon check), so a past date fell straight
     * into the full slot walk and generated every candidate for the day just to discover each one sits
     * below the cutoff. TODAY must still walk: it can be bookable later in the day.
     */
    @Test
    @DisplayName("should mark a past day not-working WITHOUT walking its slots (past-date fast path)")
    void should_markPastDayNotWorking_withoutWalkingSlots() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate yesterday = LocalDate.of(2026, 5, 6);
        LocalDate today = LocalDate.of(2026, 5, 7);
        MasterServiceAssignment msa = activeAssignment(masterId, 60, 0);

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveRange(masterId, yesterday, today))
                .thenReturn(List.of(
                        templateDay(yesterday, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                        templateDay(today, LocalTime.of(9, 0), LocalTime.of(17, 0))));
        when(bookingRepository.findActiveTimeRangesByMasterInRange(eq(masterId), any(), any()))
                .thenReturn(List.of());
        when(timeSlotCalculator.hasAvailableSlot(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(true);

        List<MasterWorkingDayResponse> days = slotCalculationService.getBookableWorkingDays(
                masterId, yesterday, today, masterServiceId);

        assertThat(days).containsExactly(
                new MasterWorkingDayResponse(yesterday, false),
                new MasterWorkingDayResponse(today, true));
        verify(timeSlotCalculator, never())
                .hasAvailableSlot(eq(yesterday), any(), any(), any(), any(), any(), any());
        verify(timeSlotCalculator, org.mockito.Mockito.times(1))
                .hasAvailableSlot(eq(today), any(), any(), any(), any(), any(), any());
    }

    // ── Window cap + guard ordering (Perf HIGH-1 / LOW-1) ───────────────────────────────────────

    @Test
    @DisplayName("should reject a serviceId-mode window wider than 63 inclusive dates (400, no DB hit)")
    void should_throwBusinessException_when_serviceIdWindowExceedsCap() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 5, 10);
        LocalDate to = from.plusDays(63);

        assertThatThrownBy(() -> slotCalculationService.getBookableWorkingDays(
                masterId, from, to, masterServiceId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("63");

        verifyNoInteractions(masterServiceRepository, masterScheduleService, bookingRepository);
    }

    @Test
    @DisplayName("should accept a serviceId-mode window of exactly 63 inclusive dates (the cap boundary)")
    void should_accept_when_serviceIdWindowIsExactlyAtCap() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 5, 10);
        LocalDate to = from.plusDays(62);
        MasterServiceAssignment msa = activeAssignment(masterId, 60, 0);

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveRange(masterId, from, to)).thenReturn(List.of());
        when(bookingRepository.findActiveTimeRangesByMasterInRange(eq(masterId), any(), any()))
                .thenReturn(List.of());

        assertThatCode(() -> slotCalculationService.getBookableWorkingDays(
                masterId, from, to, masterServiceId))
                .doesNotThrowAnyException();
    }

    /**
     * Perf LOW-1 — the range guard is pure in-memory arithmetic over the two caller-supplied dates, so it
     * runs BEFORE the assignment load: a malformed range must not cost a DB round-trip. It leaks nothing
     * about the master (its verdict is identical whether or not the master/service exists), and the
     * assignment is still resolved before any SCHEDULE read — so the working-hours oracle stays closed.
     */
    @Test
    @DisplayName("should validate the date range BEFORE loading the assignment (no DB round-trip on a bad range)")
    void should_validateRange_beforeAnyDbCall() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 5, 10);
        LocalDate to = LocalDate.of(2026, 5, 12);
        org.mockito.Mockito.doThrow(new BusinessException("Schedule range start must not be after its end"))
                .when(dateMath).assertExpandable(from, to);

        assertThatThrownBy(() -> slotCalculationService.getBookableWorkingDays(
                masterId, from, to, masterServiceId))
                .isInstanceOf(BusinessException.class);

        verifyNoInteractions(masterServiceRepository, masterScheduleService, bookingRepository);
    }

    @Test
    @DisplayName("should load the window's bookings in ONE query when projecting a multi-day range (no N+1)")
    void should_loadBookingsOnce_when_projectingMultiDayRange() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate from = LocalDate.of(2026, 5, 11);
        LocalDate to = LocalDate.of(2026, 5, 13);
        MasterServiceAssignment msa = activeAssignment(masterId, 60, 0);

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveRange(masterId, from, to))
                .thenReturn(List.of(
                        templateDay(from, LocalTime.of(9, 0), LocalTime.of(17, 0)),
                        templateDay(from.plusDays(1), LocalTime.of(9, 0), LocalTime.of(17, 0)),
                        templateDay(to, LocalTime.of(9, 0), LocalTime.of(17, 0))));
        when(bookingRepository.findActiveTimeRangesByMasterInRange(eq(masterId), any(), any()))
                .thenReturn(List.of());
        when(timeSlotCalculator.hasAvailableSlot(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);

        slotCalculationService.getBookableWorkingDays(masterId, from, to, masterServiceId);

        verify(bookingRepository, org.mockito.Mockito.times(1))
                .findActiveTimeRangesByMasterInRange(eq(masterId), any(), any());
    }

    @Test
    @DisplayName("should mark every day not-working when the master is inactive")
    void should_markAllDaysNotWorking_when_masterIsInactive() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 20);
        Master inactiveMaster = Master.builder().id(masterId).isActive(false).build();
        ServiceDefinition sd = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();
        MasterServiceAssignment msa = MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(sd)
                .master(inactiveMaster)
                .isActive(true)
                .build();

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(msa));
        when(masterScheduleService.resolveEffectiveRange(masterId, date, date))
                .thenReturn(List.of(templateDay(date, LocalTime.of(9, 0), LocalTime.of(17, 0))));

        List<MasterWorkingDayResponse> days = slotCalculationService.getBookableWorkingDays(
                masterId, date, date, masterServiceId);

        assertThat(days).containsExactly(new MasterWorkingDayResponse(date, false));
        verify(timeSlotCalculator, never()).hasAvailableSlot(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("should throw NotFound when the serviceId does not belong to the master")
    void should_throwNotFound_when_serviceDoesNotBelongToMaster() {
        UUID masterId = UUID.randomUUID();
        UUID foreignServiceId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 20);

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, foreignServiceId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> slotCalculationService.getBookableWorkingDays(
                masterId, date, date, foreignServiceId))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(masterScheduleService);
    }

    /**
     * Security LOW-1 — a DEACTIVATED assignment must be indistinguishable from an unknown / foreign one:
     * all three answer 404. The previous 400 ("master service is inactive") handed a caller holding a
     * stale id a two-valued oracle — 400 meant "soft-deleted but still on THIS master", 404 meant
     * "removed or never existed". {@link NotFoundException} is a subclass-independent assertion here:
     * {@code BusinessException} would NOT satisfy it.
     */
    @Test
    @DisplayName("should throw NotFound (not 400) when the master service assignment is inactive")
    void should_throwNotFound_when_assignmentIsInactive() {
        UUID masterId = UUID.randomUUID();
        UUID masterServiceId = UUID.randomUUID();
        LocalDate date = LocalDate.of(2026, 5, 20);
        ServiceDefinition sd = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();
        MasterServiceAssignment inactive = MasterServiceAssignment.builder()
                .id(masterServiceId)
                .serviceDefinition(sd)
                .master(ACTIVE_MASTER)
                .isActive(false)
                .build();

        when(masterServiceRepository.findByMasterIdAndIdWithGraph(masterId, masterServiceId))
                .thenReturn(Optional.of(inactive));

        assertThatThrownBy(() -> slotCalculationService.getBookableWorkingDays(
                masterId, date, date, masterServiceId))
                .isInstanceOf(NotFoundException.class);

        verifyNoInteractions(masterScheduleService);
    }
}
