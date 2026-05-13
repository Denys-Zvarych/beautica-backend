package com.beautica.dashboard.service;

import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.dashboard.dto.RevenueByDateDto;
import com.beautica.dashboard.dto.RevenueByMasterDto;
import com.beautica.dashboard.dto.RevenueByServiceDto;
import com.beautica.dashboard.dto.RevenueResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.salon.repository.SalonRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    // 2026-05-13T10:00:00Z → 2026-05-13T13:00:00+03:00 (Kyiv) → today = 2026-05-13
    private static final Instant   FIXED_INSTANT = Instant.parse("2026-05-13T10:00:00Z");
    private static final LocalDate TODAY_KYIV    = LocalDate.of(2026, 5, 13);
    private static final ZoneId    UTC           = ZoneId.of("UTC");

    @Mock EntityManager            em;
    @Mock Query                    query;
    @Mock SalonRepository          salonRepository;
    @Mock MasterRepository         masterRepository;
    @Mock MasterServiceRepository  masterServiceRepository;

    // ── 1. Date defaulting ────────────────────────────────────────────────

    @Test
    @DisplayName("null from/to — defaults to last 30 days from today (Kyiv)")
    void should_defaultDateRange_when_fromAndToAreNull() {
        // Arrange
        Clock            fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
        DashboardService svc        = newService(fixedClock);

        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        stubEmptyQuery(em, query);

        // Act
        svc.getRevenueSummary(actorId, Role.SALON_OWNER, null, null, null, null, Optional.empty());

        // Assert — fromDate param starts on today-30, toDate param on today+1 (exclusive upper bound)
        LocalDate expectedFrom = TODAY_KYIV.minusDays(30);
        LocalDate expectedTo   = TODAY_KYIV.plusDays(1);
        verify(query).setParameter(eq("fromDate"), argStartsWith(expectedFrom.toString()));
        verify(query).setParameter(eq("toDate"),   argStartsWith(expectedTo.toString()));
    }

    // ── 2. Range validation ───────────────────────────────────────────────

    @Test
    @DisplayName("range >365 days — throws BusinessException(400)")
    void should_throw400_when_dateRangeExceeds365Days() {
        // Arrange
        DashboardService svc = newService(Clock.fixed(FIXED_INSTANT, UTC));

        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to   = LocalDate.of(2026, 2, 1); // 396 days apart

        // Act & Assert
        assertThatThrownBy(() ->
                svc.getRevenueSummary(UUID.randomUUID(), Role.SALON_OWNER, from, to, null, null, Optional.empty()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── 3. Salon scope binding ────────────────────────────────────────────

    @Test
    @DisplayName("SALON_OWNER — binds salonIds array and null actorMasterId to query")
    void should_bindSalonIds_when_actorIsSalonOwner() {
        // Arrange
        DashboardService svc = newService(Clock.fixed(FIXED_INSTANT, UTC));

        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        stubEmptyQuery(em, query);

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act
        svc.getRevenueSummary(actorId, Role.SALON_OWNER, from, to, null, null, Optional.empty());

        // Assert — salonIds array must be set; actorMasterId must be null
        verify(query).setParameter(eq("salonIds"),       any(String[].class));
        verify(query).setParameter(eq("actorMasterId"), isNull());
    }

    // ── 4. Master scope binding ───────────────────────────────────────────

    @Test
    @DisplayName("INDEPENDENT_MASTER — binds actorMasterId and empty salonIds to query")
    void should_bindActorMasterId_when_actorIsIndependentMaster() {
        // Arrange
        DashboardService svc      = newService(Clock.fixed(FIXED_INSTANT, UTC));
        UUID             actorId  = UUID.randomUUID();
        UUID             masterId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(masterRepository.findByUserId(actorId)).thenReturn(Optional.of(master));
        stubEmptyQuery(em, query);

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act
        svc.getRevenueSummary(actorId, Role.INDEPENDENT_MASTER, from, to, null, null, Optional.empty());

        // Assert
        verify(query).setParameter(eq("actorMasterId"), eq(masterId.toString()));
        verify(query).setParameter(eq("salonIds"),       any(String[].class));
    }

    // ── 5. Aggregate by master ────────────────────────────────────────────

    @Test
    @DisplayName("same master in 2 rows — collapses to 1 byMaster entry with summed totals")
    void should_aggregateRevenueByMaster_when_multipleRowsWithSameMaster() {
        // Arrange
        UUID masterId = UUID.randomUUID();
        UUID svcDefId = UUID.randomUUID();
        Object[] row1 = buildRow(LocalDate.of(2026, 5, 1), masterId, "Anna K", svcDefId, "Cut",
                2L, new BigDecimal("200.00"));
        Object[] row2 = buildRow(LocalDate.of(2026, 5, 2), masterId, "Anna K", svcDefId, "Cut",
                3L, new BigDecimal("300.00"));

        DashboardService svc = serviceWithRows(List.of(row1, row2));

        // Act
        RevenueResponse result = svc.getRevenueSummary(
                UUID.randomUUID(), Role.SALON_OWNER,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10),
                null, null, Optional.empty());

        // Assert
        assertThat(result.byMaster()).hasSize(1);
        RevenueByMasterDto m = result.byMaster().get(0);
        assertThat(m.masterId()).isEqualTo(masterId);
        assertThat(m.bookingCount()).isEqualTo(5L);
        assertThat(m.revenue()).isEqualByComparingTo("500.00");
    }

    // ── 6. Aggregate by service ───────────────────────────────────────────

    @Test
    @DisplayName("same service in 2 rows — collapses to 1 byService entry with summed totals")
    void should_aggregateRevenueByService_when_multipleRowsWithSameService() {
        // Arrange
        UUID svcDefId = UUID.randomUUID();
        Object[] row1  = buildRow(LocalDate.of(2026, 5, 1), UUID.randomUUID(), "Anna K",
                svcDefId, "Manicure", 1L, new BigDecimal("150.00"));
        Object[] row2  = buildRow(LocalDate.of(2026, 5, 2), UUID.randomUUID(), "Olha P",
                svcDefId, "Manicure", 2L, new BigDecimal("300.00"));

        DashboardService svc = serviceWithRows(List.of(row1, row2));

        // Act
        RevenueResponse result = svc.getRevenueSummary(
                UUID.randomUUID(), Role.SALON_OWNER,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10),
                null, null, Optional.empty());

        // Assert
        assertThat(result.byService()).hasSize(1);
        RevenueByServiceDto s = result.byService().get(0);
        assertThat(s.serviceDefId()).isEqualTo(svcDefId);
        assertThat(s.bookingCount()).isEqualTo(3L);
        assertThat(s.revenue()).isEqualByComparingTo("450.00");
    }

    // ── 7. Aggregate by date ──────────────────────────────────────────────

    @Test
    @DisplayName("same date in 2 rows — collapses to 1 byDate entry with summed count and revenue")
    void should_aggregateRevenueByDate_when_multipleRowsWithSameDate() {
        // Arrange
        LocalDate date = LocalDate.of(2026, 5, 5);
        Object[] row1  = buildRow(date, UUID.randomUUID(), "A", UUID.randomUUID(), "Cut",
                1L, new BigDecimal("100.00"));
        Object[] row2  = buildRow(date, UUID.randomUUID(), "B", UUID.randomUUID(), "Dye",
                2L, new BigDecimal("200.00"));

        DashboardService svc = serviceWithRows(List.of(row1, row2));

        // Act
        RevenueResponse result = svc.getRevenueSummary(
                UUID.randomUUID(), Role.SALON_OWNER,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10),
                null, null, Optional.empty());

        // Assert
        assertThat(result.byDate()).hasSize(1);
        RevenueByDateDto d = result.byDate().get(0);
        assertThat(d.date()).isEqualTo(date);
        assertThat(d.bookingCount()).isEqualTo(3L);
        assertThat(d.revenue()).isEqualByComparingTo("300.00");
    }

    // ── 8. Total computation ──────────────────────────────────────────────

    @Test
    @DisplayName("2 rows — grand totals match sum of all booking counts and revenue")
    void should_computeTotals_when_multipleRowsReturned() {
        // Arrange
        Object[] row1 = buildRow(LocalDate.of(2026, 5, 1), UUID.randomUUID(), "A",
                UUID.randomUUID(), "X", 3L, new BigDecimal("300.00"));
        Object[] row2 = buildRow(LocalDate.of(2026, 5, 2), UUID.randomUUID(), "B",
                UUID.randomUUID(), "Y", 2L, new BigDecimal("200.00"));

        DashboardService svc = serviceWithRows(List.of(row1, row2));

        // Act
        RevenueResponse result = svc.getRevenueSummary(
                UUID.randomUUID(), Role.SALON_OWNER,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10),
                null, null, Optional.empty());

        // Assert
        assertThat(result.totalCompletedBookings()).isEqualTo(5L);
        assertThat(result.estimatedRevenue()).isEqualByComparingTo("500.00");
    }

    // ── 9. Empty result ───────────────────────────────────────────────────

    @Test
    @DisplayName("no completed bookings — returns zeros and empty breakdowns")
    void should_returnZeroTotals_when_noCompletedBookings() {
        // Arrange
        DashboardService svc = serviceWithRows(List.of());

        // Act
        RevenueResponse result = svc.getRevenueSummary(
                UUID.randomUUID(), Role.SALON_OWNER,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10),
                null, null, Optional.empty());

        // Assert
        assertThat(result.totalCompletedBookings()).isZero();
        assertThat(result.estimatedRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.byMaster()).isEmpty();
        assertThat(result.byService()).isEmpty();
        assertThat(result.byDate()).isEmpty();
    }

    // ── 10. resolveScope — forbidden roles ───────────────────────────────────

    @Test
    @DisplayName("CLIENT role — throws ForbiddenException with 'Access denied'")
    void should_throw403_when_actorRoleIsClient() {
        DashboardService svc = newService(Clock.fixed(FIXED_INSTANT, UTC));
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        assertThatThrownBy(() ->
                svc.getRevenueSummary(UUID.randomUUID(), Role.CLIENT, from, to, null, null, Optional.empty()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");
    }

    @Test
    @DisplayName("SALON_ADMIN role — throws ForbiddenException with 'Access denied'")
    void should_throw403_when_actorRoleIsSalonAdmin() {
        DashboardService svc = newService(Clock.fixed(FIXED_INSTANT, UTC));
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        assertThatThrownBy(() ->
                svc.getRevenueSummary(UUID.randomUUID(), Role.SALON_ADMIN, from, to, null, null, Optional.empty()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");
    }

    @Test
    @DisplayName("SALON_MASTER role — throws ForbiddenException with 'Access denied'")
    void should_throw403_when_actorRoleIsSalonMaster() {
        DashboardService svc = newService(Clock.fixed(FIXED_INSTANT, UTC));
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        assertThatThrownBy(() ->
                svc.getRevenueSummary(UUID.randomUUID(), Role.SALON_MASTER, from, to, null, null, Optional.empty()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");
    }

    // ── 11. validateRange — inverted dates ────────────────────────────────────

    @Test
    @DisplayName("from after to — throws BusinessException(400)")
    void should_throw400_when_fromIsAfterTo() {
        DashboardService svc = newService(Clock.fixed(FIXED_INSTANT, UTC));
        LocalDate from = TODAY_KYIV;
        LocalDate to   = TODAY_KYIV.minusDays(1);

        assertThatThrownBy(() ->
                svc.getRevenueSummary(UUID.randomUUID(), Role.SALON_OWNER, from, to, null, null, Optional.empty()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── 12. filterMasterId ownership ─────────────────────────────────────────

    @Test
    @DisplayName("SALON_OWNER: filterMasterId from other salon — throws ForbiddenException")
    void should_throw403_when_filterMasterIdBelongsToOtherSalon() {
        // Arrange
        DashboardService svc = newService(Clock.fixed(FIXED_INSTANT, UTC));

        UUID actorId       = UUID.randomUUID();
        UUID salonId       = UUID.randomUUID();
        UUID otherMasterId = UUID.randomUUID();

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        when(masterRepository.existsByIdAndSalonIdIn(otherMasterId, List.of(salonId))).thenReturn(false);

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act & Assert
        assertThatThrownBy(() ->
                svc.getRevenueSummary(actorId, Role.SALON_OWNER, from, to, otherMasterId, null, Optional.empty()))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── 13. INDEPENDENT_MASTER filterMasterId IDOR guard ─────────────────────

    @Test
    @DisplayName("INDEPENDENT_MASTER: filterMasterId != own master — throws ForbiddenException")
    void should_throw403_when_independentMasterFiltersOtherMasterId() {
        // Arrange
        DashboardService svc = newService(Clock.fixed(FIXED_INSTANT, UTC));

        UUID actorId       = UUID.randomUUID();
        UUID actorMasterId = UUID.randomUUID();
        UUID otherMasterId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(actorMasterId);
        when(masterRepository.findByUserId(actorId)).thenReturn(Optional.of(master));

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act & Assert
        assertThatThrownBy(() ->
                svc.getRevenueSummary(actorId, Role.INDEPENDENT_MASTER, from, to, otherMasterId, null, Optional.empty()))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── 14. resolveScope — not-found paths ───────────────────────────────────

    @Test
    @DisplayName("SALON_OWNER with no active salon — throws ForbiddenException (not 404)")
    void should_throwForbidden_when_salonOwnerHasNoActiveSalon() {
        // Arrange — resolveScope now throws ForbiddenException instead of NotFoundException
        // to prevent authenticated callers from distinguishing "absent resource" from "access denied".
        DashboardService svc = newService(Clock.fixed(FIXED_INSTANT, UTC));

        UUID actorId = UUID.randomUUID();
        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of());

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act & Assert
        assertThatThrownBy(() ->
                svc.getRevenueSummary(actorId, Role.SALON_OWNER, from, to, null, null, Optional.empty()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER with no master record — throws ForbiddenException (not 404)")
    void should_throwForbidden_when_independentMasterHasNoMasterRecord() {
        // Arrange — resolveScope now throws ForbiddenException instead of NotFoundException
        // to prevent authenticated callers from distinguishing "absent resource" from "access denied".
        DashboardService svc = newService(Clock.fixed(FIXED_INSTANT, UTC));

        UUID actorId = UUID.randomUUID();
        when(masterRepository.findByUserId(actorId)).thenReturn(Optional.empty());

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act & Assert
        assertThatThrownBy(() ->
                svc.getRevenueSummary(actorId, Role.INDEPENDENT_MASTER, from, to, null, null, Optional.empty()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");
    }

    // ── 15. serviceDefId filter binding ──────────────────────────────────────

    @Test
    @DisplayName("serviceDefId provided — binds :serviceDefId parameter to query")
    void should_bindServiceDefIdFilter_when_serviceDefIdPassed() {
        // Arrange
        DashboardService svc = newService(Clock.fixed(FIXED_INSTANT, UTC));

        UUID actorId  = UUID.randomUUID();
        UUID salonId  = UUID.randomUUID();
        UUID svcDefId = UUID.randomUUID();

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of(salonId));
        // serviceDefId ownership check — the service def belongs to this salon
        when(masterServiceRepository.existsByServiceDefIdAndSalonIdIn(svcDefId, List.of(salonId)))
                .thenReturn(true);
        stubEmptyQuery(em, query);

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act
        svc.getRevenueSummary(actorId, Role.SALON_OWNER, from, to, null, svcDefId, Optional.empty());

        // Assert
        verify(query).setParameter(eq("serviceDefId"), eq(svcDefId.toString()));
    }

    // ── 16. Multi-salon: includes revenue from all salons (FIX 1) ────────────

    @Test
    @DisplayName("SALON_OWNER with 2 salons — binds both salon IDs in salonIds array")
    void should_include_revenue_from_all_salons_when_owner_has_multiple_salons() {
        // Arrange
        DashboardService svc = newService(Clock.fixed(FIXED_INSTANT, UTC));

        UUID actorId = UUID.randomUUID();
        UUID salonId1 = UUID.randomUUID();
        UUID salonId2 = UUID.randomUUID();

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId))
                .thenReturn(List.of(salonId1, salonId2));
        stubEmptyQuery(em, query);

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act
        svc.getRevenueSummary(actorId, Role.SALON_OWNER, from, to, null, null, Optional.empty());

        // Assert — the salonIds array must contain both salon IDs
        org.mockito.ArgumentCaptor<String[]> captor = org.mockito.ArgumentCaptor.forClass(String[].class);
        verify(query).setParameter(eq("salonIds"), captor.capture());

        String[] boundIds = captor.getValue();
        assertThat(boundIds).containsExactlyInAnyOrder(salonId1.toString(), salonId2.toString());
    }

    @Test
    @DisplayName("SALON_OWNER filters by salon not in their scope — throws ForbiddenException")
    void should_return403_when_owner_filters_by_salon_they_do_not_own() {
        // Arrange
        DashboardService svc = newService(Clock.fixed(FIXED_INSTANT, UTC));

        UUID actorId       = UUID.randomUUID();
        UUID ownedSalonId  = UUID.randomUUID();
        UUID foreignSalonId = UUID.randomUUID();

        when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId))
                .thenReturn(List.of(ownedSalonId));

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act & Assert — requesting a salon not in the scope must throw
        assertThatThrownBy(() ->
                svc.getRevenueSummary(actorId, Role.SALON_OWNER, from, to, null, null,
                        Optional.of(foreignSalonId)))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── test helpers ──────────────────────────────────────────────────────

    /** Creates a {@link DashboardService} with the given clock and shared mocks. */
    private DashboardService newService(Clock clock) {
        return new DashboardService(em, masterRepository, salonRepository, masterServiceRepository, clock);
    }

    /**
     * Builds a fresh {@link DashboardService} whose {@link EntityManager} stub returns
     * the given rows and whose {@link SalonRepository} stub resolves a dummy salon for
     * any SALON_OWNER actor. Use this for aggregation-focused tests.
     */
    @SuppressWarnings("unchecked")
    private DashboardService serviceWithRows(List<Object[]> rows) {
        Clock                fixedClock    = Clock.fixed(FIXED_INSTANT, UTC);
        EntityManager        localEm       = mock(EntityManager.class);
        Query                localQuery    = mock(Query.class);
        SalonRepository      localSalon    = mock(SalonRepository.class);
        MasterRepository     localMaster   = mock(MasterRepository.class);
        MasterServiceRepository localMsr   = mock(MasterServiceRepository.class);

        UUID salonId = UUID.randomUUID();
        when(localSalon.findIdsByOwnerIdAndIsActiveTrue(any())).thenReturn(List.of(salonId));

        when(localEm.createNativeQuery(anyString())).thenReturn(localQuery);
        when(localQuery.setParameter(anyString(), any())).thenReturn(localQuery);
        when(localQuery.getResultList()).thenReturn((List) rows);

        return new DashboardService(localEm, localMaster, localSalon, localMsr, fixedClock);
    }

    /** Stubs em → query → empty result list. */
    private void stubEmptyQuery(EntityManager localEm, Query localQuery) {
        when(localEm.createNativeQuery(anyString())).thenReturn(localQuery);
        when(localQuery.setParameter(anyString(), any())).thenReturn(localQuery);
        when(localQuery.getResultList()).thenReturn(List.of());
    }

    /**
     * Builds a raw SQL Object[] row with the column order expected by {@code REVENUE_SQL}.
     */
    private Object[] buildRow(LocalDate date, UUID masterId, String masterName,
                               UUID serviceDefId, String serviceName,
                               long count, BigDecimal revenue) {
        return new Object[]{
                Date.valueOf(date),
                masterId,
                masterName,
                serviceDefId,
                serviceName,
                count,
                revenue
        };
    }

    /**
     * Mockito {@code argThat} helper: matches an {@code OffsetDateTime} whose
     * {@code toString()} starts with the given date prefix (e.g. "2026-04-13").
     */
    private Object argStartsWith(String prefix) {
        return org.mockito.ArgumentMatchers.argThat(v -> v != null && v.toString().startsWith(prefix));
    }
}
