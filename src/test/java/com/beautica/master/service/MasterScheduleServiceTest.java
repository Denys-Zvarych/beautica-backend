package com.beautica.master.service;

import com.beautica.common.security.AuthorizationService;
import com.beautica.common.cache.MasterCachePrefixEvictor;
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

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private MasterCachePrefixEvictor cacheEvictor;

    @Mock
    private com.beautica.service.service.SalonCatalogCacheEvictor salonCatalogCacheEvictor;

    private MasterScheduleService spyService() {
        return spy(new MasterScheduleService(weeklyScheduleRepository, scheduleExceptionRepository,
                masterRepository, dateMath, authz, scheduleMapper, cacheEvictor, salonCatalogCacheEvictor));
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

    // ── reduceEffectiveRangeBatch — the 2026-09-15 fold bound (perf MEDIUM-3 / security MEDIUM) ──

    /**
     * The gate answers a boolean and stops at the first bookable day, but until the bound existed it
     * was handed a fully materialised {@code MAX_DAYS_AHEAD + 1} = 181-day projection PER MASTER
     * first. This pins the primitive that fixed it: the reducer's day list folds on access, so a
     * reducer that reads element 0 and returns costs exactly ONE
     * {@code ScheduleMapper#toEffectiveDay} — the fold's sole allocator of an
     * {@link EffectiveDayResponse} — not 181.
     */
    @Test
    @DisplayName("should_foldLazily_when_theReducerStopsAtTheFirstDay")
    void should_foldLazily_when_theReducerStopsAtTheFirstDay() {
        MasterScheduleService service = stubbedHorizon();

        Map<UUID, LocalDate> firstDates = service.reduceEffectiveRangeBatch(
                List.of(MASTER_ID), DATE, DATE.plusDays(HORIZON_DAYS - 1),
                (masterId, days) -> days.get(0).date());

        assertThat(firstDates).containsExactly(Map.entry(MASTER_ID, DATE));
        verify(scheduleMapper, times(1)).toEffectiveDay(any(), any(), anyList());
    }

    /**
     * The other half of the same claim: a reducer that walks EVERY day still sees the whole range,
     * in order, with one fold per date — laziness is a materialisation strategy, never a truncated
     * search. {@link MasterScheduleService#resolveEffectiveRangeBatch} is exactly that reducer, which
     * is why its long-standing contract is unchanged.
     */
    @Test
    @DisplayName("should_foldEveryDayExactlyOnce_when_theReducerReadsTheWholeRange")
    void should_foldEveryDayExactlyOnce_when_theReducerReadsTheWholeRange() {
        MasterScheduleService service = stubbedHorizon();

        Map<UUID, List<EffectiveDayResponse>> days = service.resolveEffectiveRangeBatch(
                List.of(MASTER_ID), DATE, DATE.plusDays(HORIZON_DAYS - 1));

        assertThat(days.get(MASTER_ID)).hasSize(HORIZON_DAYS);
        assertThat(days.get(MASTER_ID))
                .extracting(EffectiveDayResponse::date)
                .containsExactlyElementsOf(horizon());
        verify(scheduleMapper, times(HORIZON_DAYS)).toEffectiveDay(any(), any(), anyList());
    }

    /**
     * A second walk of the same master's list — the gate does one per distinct effective duration —
     * must re-READ the folded days, never re-FOLD them. Without the memo, two durations would double
     * the fold cost the bound exists to remove.
     */
    @Test
    @DisplayName("should_memoiseFoldedDays_when_theReducerWalksTheListTwice")
    void should_memoiseFoldedDays_when_theReducerWalksTheListTwice() {
        MasterScheduleService service = stubbedHorizon();

        service.reduceEffectiveRangeBatch(List.of(MASTER_ID), DATE, DATE.plusDays(HORIZON_DAYS - 1),
                (masterId, days) -> days.get(0).date().equals(days.get(0).date()));

        verify(scheduleMapper, times(1)).toEffectiveDay(any(), any(), anyList());
    }

    /**
     * The view's escape contract used to be javadoc-only. A reducer that returned {@code days}
     * itself handed the caller a list whose already-folded prefix reads fine and whose unfolded tail
     * dereferences detached proxies once the transaction closes — a {@code
     * LazyInitializationException} at an arbitrary, data-dependent index, far from the reducer that
     * caused it. This pins the 2026-09-15 enforcement: the view is marked SPENT the moment its
     * reducer returns, so the very first post-return read fails loudly and deterministically, naming
     * the rule it broke.
     *
     * <p>Index 0 is deliberately read INSIDE the reducer first, so the day it asks for afterwards is
     * already memoised: the guard is about the contract, not about whether that particular element
     * happens to be cached — otherwise the failure would still be data-dependent.
     */
    @Test
    @DisplayName("should_throwIllegalState_when_aReducerStoresTheViewAndReadsItAfterReturning")
    void should_throwIllegalState_when_aReducerStoresTheViewAndReadsItAfterReturning() {
        MasterScheduleService service = stubbedHorizon();
        List<List<EffectiveDayResponse>> escaped = new ArrayList<>();

        Map<UUID, LocalDate> insideTheReducer = service.reduceEffectiveRangeBatch(
                List.of(MASTER_ID), DATE, DATE.plusDays(HORIZON_DAYS - 1),
                (masterId, days) -> {
                    escaped.add(days);
                    return days.get(0).date();
                });

        assertThat(insideTheReducer)
                .as("premise: the same read was legal INSIDE the reducer, so the throw below is "
                        + "about escape, not about a broken view")
                .containsExactly(Map.entry(MASTER_ID, DATE));
        assertThat(escaped.get(0))
                .as("size() folds nothing and therefore stays legal after the view is spent")
                .hasSize(HORIZON_DAYS);
        assertThatThrownBy(() -> escaped.get(0).get(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPENT")
                .hasMessageContaining("List.copyOf");
    }

    /**
     * The other side of the same flag: {@link MasterScheduleService#resolveEffectiveRangeBatch} IS a
     * reducer that forces the whole view ({@code List.copyOf(days)}) from inside the callback, so the
     * flag must flip AFTER the reducer returns, never during. A day read back off the returned copy —
     * i.e. after the view was spent — must still be the folded value.
     */
    @Test
    @DisplayName("should_stillMaterialiseEveryDay_when_theReducerCopiesTheViewBeforeReturning")
    void should_stillMaterialiseEveryDay_when_theReducerCopiesTheViewBeforeReturning() {
        MasterScheduleService service = stubbedHorizon();

        Map<UUID, List<EffectiveDayResponse>> days = service.resolveEffectiveRangeBatch(
                List.of(MASTER_ID), DATE, DATE.plusDays(HORIZON_DAYS - 1));

        assertThat(days.get(MASTER_ID)).hasSize(HORIZON_DAYS);
        assertThat(days.get(MASTER_ID).get(HORIZON_DAYS - 1).date())
                .isEqualTo(DATE.plusDays(HORIZON_DAYS - 1));
    }

    /** {@code BookingWindow.MAX_DAYS_AHEAD + 1} — the window the batched gate folds over. */
    private static final int HORIZON_DAYS = 181;

    private List<LocalDate> horizon() {
        return IntStream.range(0, HORIZON_DAYS).mapToObj(DATE::plusDays).toList();
    }

    /**
     * A master with NO override and NO template over the whole horizon: every date resolves through
     * {@code resolveFromTemplate(date, null)} to a single {@code NO_SCHEDULE} day, so the number of
     * {@code toEffectiveDay} calls IS the number of days folded — the quantity these tests bound.
     */
    private MasterScheduleService stubbedHorizon() {
        LocalDate to = DATE.plusDays(HORIZON_DAYS - 1);
        when(dateMath.expandInclusive(DATE, to)).thenReturn(horizon());
        when(scheduleExceptionRepository.findByMasterIdsAndDateBetweenWithIntervals(
                List.of(MASTER_ID), DATE, to)).thenReturn(List.of());
        when(weeklyScheduleRepository.findOverlappingRangeWithIntervalsByMasterIds(
                List.of(MASTER_ID), DATE, to)).thenReturn(List.of());
        when(scheduleMapper.toEffectiveDay(any(), any(), anyList()))
                .thenAnswer(invocation -> new EffectiveDayResponse(
                        invocation.getArgument(0), invocation.getArgument(1), List.of()));
        return spyService();
    }
}
