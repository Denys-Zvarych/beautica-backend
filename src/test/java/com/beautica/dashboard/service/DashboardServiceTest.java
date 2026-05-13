package com.beautica.dashboard.service;

import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.dashboard.dto.RevenueByDateDto;
import com.beautica.dashboard.dto.RevenueByMasterDto;
import com.beautica.dashboard.dto.RevenueByServiceDto;
import com.beautica.dashboard.dto.RevenueResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    // 2026-05-13T10:00:00Z → 2026-05-13T13:00:00+03:00 (Kyiv) → today = 2026-05-13
    private static final Instant   FIXED_INSTANT = Instant.parse("2026-05-13T10:00:00Z");
    private static final LocalDate TODAY_KYIV    = LocalDate.of(2026, 5, 13);
    private static final ZoneId    UTC           = ZoneId.of("UTC");

    @Mock EntityManager    em;
    @Mock Query            query;
    @Mock SalonRepository  salonRepository;
    @Mock MasterRepository masterRepository;
    @InjectMocks DashboardService dashboardService;

    // ── 1. Date defaulting ────────────────────────────────────────────────

    @Test
    @DisplayName("null from/to — defaults to last 30 days from today (Kyiv)")
    void should_defaultDateRange_when_fromAndToAreNull() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
        DashboardService svc = new DashboardService(em, masterRepository, salonRepository, fixedClock);

        UUID  actorId = UUID.randomUUID();
        Salon salon   = stubSalon(salonRepository, actorId);
        stubEmptyQuery(em, query);

        // Act
        svc.getRevenueSummary(actorId, Role.SALON_OWNER, null, null, null, null);

        // Assert — fromDate param starts on today-30, toDate param starts on today+1 (exclusive upper bound)
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
        Clock     fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
        DashboardService svc = new DashboardService(em, masterRepository, salonRepository, fixedClock);

        LocalDate from = LocalDate.of(2025, 1, 1);
        LocalDate to   = LocalDate.of(2026, 2, 1); // 396 days apart

        // Act & Assert
        assertThatThrownBy(() ->
                svc.getRevenueSummary(UUID.randomUUID(), Role.SALON_OWNER, from, to, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── 3. Salon scope binding ────────────────────────────────────────────

    @Test
    @DisplayName("SALON_OWNER — binds salonId and null actorMasterId to query")
    void should_bindSalonId_when_actorIsSalonOwner() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
        DashboardService svc = new DashboardService(em, masterRepository, salonRepository, fixedClock);

        UUID  actorId = UUID.randomUUID();
        UUID  salonId = UUID.randomUUID();
        Salon salon   = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);
        when(salonRepository.findTopByOwnerIdAndIsActiveTrueOrderByCreatedAtAsc(actorId))
                .thenReturn(Optional.of(salon));
        stubEmptyQuery(em, query);

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act
        svc.getRevenueSummary(actorId, Role.SALON_OWNER, from, to, null, null);

        // Assert
        verify(query).setParameter("salonId",       salonId);
        verify(query).setParameter("actorMasterId", null);
    }

    // ── 4. Master scope binding ───────────────────────────────────────────

    @Test
    @DisplayName("INDEPENDENT_MASTER — binds actorMasterId and null salonId to query")
    void should_bindActorMasterId_when_actorIsIndependentMaster() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
        DashboardService svc = new DashboardService(em, masterRepository, salonRepository, fixedClock);

        UUID   actorId  = UUID.randomUUID();
        UUID   masterId = UUID.randomUUID();
        Master master   = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(masterRepository.findByUserId(actorId)).thenReturn(Optional.of(master));
        stubEmptyQuery(em, query);

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act
        svc.getRevenueSummary(actorId, Role.INDEPENDENT_MASTER, from, to, null, null);

        // Assert
        verify(query).setParameter("actorMasterId", masterId);
        verify(query).setParameter("salonId",       null);
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
                null, null);

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
        UUID svcDefId  = UUID.randomUUID();
        Object[] row1  = buildRow(LocalDate.of(2026, 5, 1), UUID.randomUUID(), "Anna K",
                svcDefId, "Manicure", 1L, new BigDecimal("150.00"));
        Object[] row2  = buildRow(LocalDate.of(2026, 5, 2), UUID.randomUUID(), "Olha P",
                svcDefId, "Manicure", 2L, new BigDecimal("300.00"));

        DashboardService svc = serviceWithRows(List.of(row1, row2));

        // Act
        RevenueResponse result = svc.getRevenueSummary(
                UUID.randomUUID(), Role.SALON_OWNER,
                LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 10),
                null, null);

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
                null, null);

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
                null, null);

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
                null, null);

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
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
        DashboardService svc = new DashboardService(em, masterRepository, salonRepository, fixedClock);

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act & Assert
        assertThatThrownBy(() ->
                svc.getRevenueSummary(UUID.randomUUID(), Role.CLIENT, from, to, null, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");
    }

    @Test
    @DisplayName("SALON_ADMIN role — throws ForbiddenException with 'Access denied'")
    void should_throw403_when_actorRoleIsSalonAdmin() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
        DashboardService svc = new DashboardService(em, masterRepository, salonRepository, fixedClock);

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act & Assert
        assertThatThrownBy(() ->
                svc.getRevenueSummary(UUID.randomUUID(), Role.SALON_ADMIN, from, to, null, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");
    }

    @Test
    @DisplayName("SALON_MASTER role — throws ForbiddenException with 'Access denied'")
    void should_throw403_when_actorRoleIsSalonMaster() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
        DashboardService svc = new DashboardService(em, masterRepository, salonRepository, fixedClock);

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act & Assert
        assertThatThrownBy(() ->
                svc.getRevenueSummary(UUID.randomUUID(), Role.SALON_MASTER, from, to, null, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");
    }

    // ── 11. validateRange — inverted dates ────────────────────────────────────

    @Test
    @DisplayName("from after to — throws BusinessException(400)")
    void should_throw400_when_fromIsAfterTo() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
        DashboardService svc = new DashboardService(em, masterRepository, salonRepository, fixedClock);

        LocalDate from = TODAY_KYIV;
        LocalDate to   = TODAY_KYIV.minusDays(1);

        // Act & Assert
        assertThatThrownBy(() ->
                svc.getRevenueSummary(UUID.randomUUID(), Role.SALON_OWNER, from, to, null, null))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── 12. filterMasterId ownership ─────────────────────────────────────────

    @Test
    @DisplayName("SALON_OWNER: filterMasterId from other salon — throws ForbiddenException")
    void should_throw403_when_filterMasterIdBelongsToOtherSalon() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
        DashboardService svc = new DashboardService(em, masterRepository, salonRepository, fixedClock);

        UUID actorId       = UUID.randomUUID();
        UUID salonId       = UUID.randomUUID();
        UUID otherMasterId = UUID.randomUUID();

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);
        when(salonRepository.findTopByOwnerIdAndIsActiveTrueOrderByCreatedAtAsc(actorId))
                .thenReturn(Optional.of(salon));
        when(masterRepository.existsByIdAndSalonId(otherMasterId, salonId)).thenReturn(false);

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act & Assert
        assertThatThrownBy(() ->
                svc.getRevenueSummary(actorId, Role.SALON_OWNER, from, to, otherMasterId, null))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── 13. INDEPENDENT_MASTER filterMasterId IDOR guard ─────────────────────

    @Test
    @DisplayName("INDEPENDENT_MASTER: filterMasterId != own master — throws ForbiddenException")
    void should_throw403_when_independentMasterFiltersOtherMasterId() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
        DashboardService svc = new DashboardService(em, masterRepository, salonRepository, fixedClock);

        UUID actorId       = UUID.randomUUID();
        UUID actorMasterId = UUID.randomUUID();
        UUID otherMasterId = UUID.randomUUID(); // different from actor's own master

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(actorMasterId);
        when(masterRepository.findByUserId(actorId)).thenReturn(Optional.of(master));

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act & Assert — must reject without executing any SQL
        assertThatThrownBy(() ->
                svc.getRevenueSummary(actorId, Role.INDEPENDENT_MASTER, from, to, otherMasterId, null))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── 14. resolveScope — not-found paths ───────────────────────────────────

    @Test
    @DisplayName("SALON_OWNER with no active salon — throws NotFoundException")
    void should_throwNotFound_when_salonOwnerHasNoActiveSalon() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
        DashboardService svc = new DashboardService(em, masterRepository, salonRepository, fixedClock);

        UUID actorId = UUID.randomUUID();
        when(salonRepository.findTopByOwnerIdAndIsActiveTrueOrderByCreatedAtAsc(actorId))
                .thenReturn(Optional.empty());

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act & Assert
        assertThatThrownBy(() ->
                svc.getRevenueSummary(actorId, Role.SALON_OWNER, from, to, null, null))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER with no master record — throws NotFoundException")
    void should_throwNotFound_when_independentMasterHasNoMasterRecord() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
        DashboardService svc = new DashboardService(em, masterRepository, salonRepository, fixedClock);

        UUID actorId = UUID.randomUUID();
        when(masterRepository.findByUserId(actorId))
                .thenReturn(Optional.empty());

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act & Assert
        assertThatThrownBy(() ->
                svc.getRevenueSummary(actorId, Role.INDEPENDENT_MASTER, from, to, null, null))
                .isInstanceOf(NotFoundException.class);
    }

    // ── 15. serviceDefId filter binding ──────────────────────────────────────

    @Test
    @DisplayName("serviceDefId provided — binds :serviceDefId parameter to query")
    void should_bindServiceDefIdFilter_when_serviceDefIdPassed() {
        // Arrange
        Clock fixedClock = Clock.fixed(FIXED_INSTANT, UTC);
        DashboardService svc = new DashboardService(em, masterRepository, salonRepository, fixedClock);

        UUID actorId  = UUID.randomUUID();
        UUID svcDefId = UUID.randomUUID();

        stubSalon(salonRepository, actorId);
        stubEmptyQuery(em, query);

        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to   = LocalDate.of(2026, 5, 1);

        // Act
        svc.getRevenueSummary(actorId, Role.SALON_OWNER, from, to, null, svcDefId);

        // Assert
        verify(query).setParameter("serviceDefId", svcDefId);
    }

    // ── test helpers ──────────────────────────────────────────────────────

    /**
     * Builds a fresh {@link DashboardService} whose {@link EntityManager} stub returns
     * the given rows and whose {@link SalonRepository} stub resolves a dummy salon for
     * any SALON_OWNER actor. Use this for aggregation-focused tests that do not need
     * fine-grained mock verification.
     */
    @SuppressWarnings("unchecked")
    private DashboardService serviceWithRows(List<Object[]> rows) {
        Clock          fixedClock  = Clock.fixed(FIXED_INSTANT, UTC);
        EntityManager  localEm     = mock(EntityManager.class);
        Query          localQuery  = mock(Query.class);
        SalonRepository localSalon = mock(SalonRepository.class);
        MasterRepository localMaster = mock(MasterRepository.class);

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(UUID.randomUUID());
        when(localSalon.findTopByOwnerIdAndIsActiveTrueOrderByCreatedAtAsc(any()))
                .thenReturn(Optional.of(salon));

        when(localEm.createNativeQuery(anyString())).thenReturn(localQuery);
        when(localQuery.setParameter(anyString(), any())).thenReturn(localQuery);
        when(localQuery.getResultList()).thenReturn((List) rows);

        return new DashboardService(localEm, localMaster, localSalon, fixedClock);
    }

    /** Stubs the salon repo to return a salon with a random ID for the given actorId. */
    private Salon stubSalon(SalonRepository repo, UUID actorId) {
        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(UUID.randomUUID());
        when(repo.findTopByOwnerIdAndIsActiveTrueOrderByCreatedAtAsc(actorId))
                .thenReturn(Optional.of(salon));
        return salon;
    }

    /** Stubs em → query → empty result list. */
    private void stubEmptyQuery(EntityManager localEm, Query localQuery) {
        when(localEm.createNativeQuery(anyString())).thenReturn(localQuery);
        when(localQuery.setParameter(anyString(), any())).thenReturn(localQuery);
        when(localQuery.getResultList()).thenReturn(List.of());
    }

    /**
     * Builds a raw SQL Object[] row with the same column order as {@code REVENUE_SQL}:
     * [booking_date, master_id, master_name, service_def_id, service_name, booking_count, total_revenue].
     *
     * <p>{@code count} is passed as {@code long}; the service maps it via
     * {@code ((Number) row[5]).longValue()} which handles both {@code Long} and
     * {@code BigInteger} (the type Postgres JDBC returns for COUNT).
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
