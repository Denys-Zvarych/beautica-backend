package com.beautica.master.service;

import com.beautica.common.security.AuthorizationService;
import com.beautica.master.dto.EffectiveDayResponse;
import com.beautica.master.dto.EffectiveDaySource;
import com.beautica.master.dto.MasterWorkingDayResponse;
import com.beautica.master.dto.WorkIntervalDto;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.ScheduleExceptionRepository;
import com.beautica.master.repository.WeeklyScheduleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

/**
 * Unit tests for {@link MasterScheduleService#getClientWorkingDays}, the Phase 15.11 CLIENT-safe
 * calendar day-gating projection.
 *
 * <p>{@code getClientWorkingDays} is a thin reducer over {@link MasterScheduleService#resolveEffectiveRange}
 * (per the anti-bug playbook §E, the range-cap guard must not be duplicated). These tests spy the real
 * service — collaborators are all mocked, but {@code resolveEffectiveRange} itself is stubbed on the spy
 * so the test pins the boolean-mapping contract in isolation from the resolver's own (separately tested)
 * override/template/gap precedence logic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MasterScheduleService.getClientWorkingDays")
class MasterScheduleServiceTest {

    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final LocalDate DATE = LocalDate.of(2026, 7, 6);

    @Mock
    private WeeklyScheduleRepository weeklyScheduleRepository;

    @Mock
    private ScheduleExceptionRepository scheduleExceptionRepository;

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private ScheduleDateMath dateMath;

    @Mock
    private AuthorizationService authz;

    @Mock
    private ScheduleMapper scheduleMapper;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private com.beautica.service.service.SalonCatalogCacheEvictor salonCatalogCacheEvictor;

    private MasterScheduleService spyService() {
        return spy(new MasterScheduleService(weeklyScheduleRepository, scheduleExceptionRepository,
                masterRepository, dateMath, authz, scheduleMapper, cacheManager, salonCatalogCacheEvictor));
    }

    @Test
    @DisplayName("should_mapWorkingTrue_when_sourceIsTemplateWithIntervals")
    void should_mapWorkingTrue_when_sourceIsTemplateWithIntervals() {
        MasterScheduleService service = spyService();
        EffectiveDayResponse day = new EffectiveDayResponse(DATE, EffectiveDaySource.TEMPLATE,
                List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0))));
        doReturn(List.of(day)).when(service).resolveEffectiveRange(MASTER_ID, DATE, DATE);

        List<MasterWorkingDayResponse> result = service.getClientWorkingDays(MASTER_ID, DATE, DATE);

        assertThat(result).containsExactly(new MasterWorkingDayResponse(DATE, true));
    }

    @Test
    @DisplayName("should_mapWorkingFalse_when_sourceIsTemplateWithNoIntervalsForThatWeekday")
    void should_mapWorkingFalse_when_sourceIsTemplateWithNoIntervalsForThatWeekday() {
        // A weekly window can cover the date (source == TEMPLATE) while that specific ISO weekday has no
        // defined interval — intervals is empty AND times is null (the pre-15.8 3-arg constructor).
        // isWorkingDay() must null-check `times` here rather than assume a non-null list.
        MasterScheduleService service = spyService();
        EffectiveDayResponse day = new EffectiveDayResponse(DATE, EffectiveDaySource.TEMPLATE, List.of());
        doReturn(List.of(day)).when(service).resolveEffectiveRange(MASTER_ID, DATE, DATE);

        List<MasterWorkingDayResponse> result = service.getClientWorkingDays(MASTER_ID, DATE, DATE);

        assertThat(result).containsExactly(new MasterWorkingDayResponse(DATE, false));
    }

    @Test
    @DisplayName("should_mapWorkingTrue_when_sourceIsOverrideCustomWithIntervals")
    void should_mapWorkingTrue_when_sourceIsOverrideCustomWithIntervals() {
        MasterScheduleService service = spyService();
        EffectiveDayResponse day = new EffectiveDayResponse(DATE, EffectiveDaySource.OVERRIDE_CUSTOM,
                List.of(new WorkIntervalDto(LocalTime.of(10, 0), LocalTime.of(14, 0))));
        doReturn(List.of(day)).when(service).resolveEffectiveRange(MASTER_ID, DATE, DATE);

        List<MasterWorkingDayResponse> result = service.getClientWorkingDays(MASTER_ID, DATE, DATE);

        assertThat(result).containsExactly(new MasterWorkingDayResponse(DATE, true));
    }

    @Test
    @DisplayName("should_mapWorkingFalse_when_sourceIsOverrideDayOff")
    void should_mapWorkingFalse_when_sourceIsOverrideDayOff() {
        MasterScheduleService service = spyService();
        EffectiveDayResponse day =
                new EffectiveDayResponse(DATE, EffectiveDaySource.OVERRIDE_DAY_OFF, List.of());
        doReturn(List.of(day)).when(service).resolveEffectiveRange(MASTER_ID, DATE, DATE);

        List<MasterWorkingDayResponse> result = service.getClientWorkingDays(MASTER_ID, DATE, DATE);

        assertThat(result).containsExactly(new MasterWorkingDayResponse(DATE, false));
    }

    @Test
    @DisplayName("should_mapWorkingFalse_when_sourceIsNoSchedule")
    void should_mapWorkingFalse_when_sourceIsNoSchedule() {
        MasterScheduleService service = spyService();
        EffectiveDayResponse day = new EffectiveDayResponse(DATE, EffectiveDaySource.NO_SCHEDULE, List.of());
        doReturn(List.of(day)).when(service).resolveEffectiveRange(MASTER_ID, DATE, DATE);

        List<MasterWorkingDayResponse> result = service.getClientWorkingDays(MASTER_ID, DATE, DATE);

        assertThat(result).containsExactly(new MasterWorkingDayResponse(DATE, false));
    }

    @Test
    @DisplayName("should_preserveOrderAndDates_when_rangeSpansMultipleDays")
    void should_preserveOrderAndDates_when_rangeSpansMultipleDays() {
        MasterScheduleService service = spyService();
        LocalDate to = DATE.plusDays(1);
        EffectiveDayResponse day1 = new EffectiveDayResponse(DATE, EffectiveDaySource.TEMPLATE,
                List.of(new WorkIntervalDto(LocalTime.of(9, 0), LocalTime.of(17, 0))));
        EffectiveDayResponse day2 =
                new EffectiveDayResponse(to, EffectiveDaySource.OVERRIDE_DAY_OFF, List.of());
        doReturn(List.of(day1, day2)).when(service).resolveEffectiveRange(MASTER_ID, DATE, to);

        List<MasterWorkingDayResponse> result = service.getClientWorkingDays(MASTER_ID, DATE, to);

        assertThat(result).containsExactly(
                new MasterWorkingDayResponse(DATE, true),
                new MasterWorkingDayResponse(to, false));
    }
}
