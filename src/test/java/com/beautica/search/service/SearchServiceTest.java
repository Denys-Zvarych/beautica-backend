package com.beautica.search.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.location.DiscoveryLocationKey;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.search.dto.LocationFilter;
import com.beautica.search.dto.MasterSearchRequest;
import com.beautica.search.dto.MasterSearchResult;
import com.beautica.search.dto.SalonSearchRequest;
import com.beautica.search.dto.SalonSearchResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SearchService} (Phase 10.5 — FK location filter).
 *
 * <p>The {@link EntityManager}, the data/count {@link Query}s and the
 * {@link DiscoveryLocationResolver} M2 seam are all mocked: the tests verify
 * the FK-based SQL shape, parameter binding and label stamping (the surface
 * most likely to regress under copy-paste edits) without booting Hibernate.
 * End-to-end query correctness lives in {@code SearchIntegrationTest}.
 *
 * <p>{@code SearchService} obtains its EntityManager via
 * {@code @PersistenceContext} (field injection — Spring's documented
 * exception). The service is instantiated manually and the field wired with
 * {@link ReflectionTestUtils}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SearchService — unit")
class SearchServiceTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query dataQuery;

    @Mock
    private Query countQuery;

    @Mock
    private SalonRepository salonRepository;

    @Mock
    private DiscoveryLocationResolver discoveryLocationResolver;

    private SearchService service;

    private ArgumentCaptor<String> sqlCaptor;

    @BeforeEach
    void setUp() {
        service = new SearchService(salonRepository, discoveryLocationResolver);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        sqlCaptor = ArgumentCaptor.forClass(String.class);
        // The seam passes through the (cityId, districtId) pair by default;
        // label resolution returns empty maps unless a test overrides it.
        lenient().when(discoveryLocationResolver.resolveFilter(any(), any()))
                .thenAnswer(inv -> {
                    UUID c = inv.getArgument(0);
                    UUID d = inv.getArgument(1);
                    return (c == null && d == null) ? null : new DiscoveryLocationKey(c, d);
                });
        lenient().when(discoveryLocationResolver.resolveLabels(any(), any()))
                .thenReturn(new DiscoveryLabels(Map.of(), Map.of()));
    }

    private void stubNativeQueries(List<Object[]> rows, long total) {
        when(entityManager.createNativeQuery(sqlCaptor.capture()))
                .thenReturn(dataQuery, countQuery);
        lenient().when(dataQuery.setParameter(anyString(), any())).thenReturn(dataQuery);
        lenient().when(dataQuery.setParameter(eq("limit"), anyInt())).thenReturn(dataQuery);
        lenient().when(dataQuery.setParameter(eq("offset"), anyLong())).thenReturn(dataQuery);
        when(dataQuery.getResultList()).thenReturn((List) rows);
        lenient().when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(total);
    }

    private static final UUID CITY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DISTRICT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static MasterSearchRequest emptyRequest() {
        return new MasterSearchRequest(null, null, null, null, null, 0, 20);
    }

    private static MasterSearchRequest cityRequest() {
        return new MasterSearchRequest(
                new LocationFilter(CITY_ID, null), null, null, null, null, 0, 20);
    }

    private static MasterSearchRequest districtRequest() {
        return new MasterSearchRequest(
                new LocationFilter(CITY_ID, DISTRICT_ID), null, null, null, null, 0, 20);
    }

    private static MasterSearchRequest fullRequest() {
        return new MasterSearchRequest(
                new LocationFilter(CITY_ID, DISTRICT_ID),
                "manicure",
                new BigDecimal("100.00"),
                new BigDecimal("500.00"),
                new BigDecimal("4.5"),
                0,
                20
        );
    }

    // ── FK location filter — district-primary ────────────────────────────────

    @Test
    @DisplayName("binds :districtId and uses the discovery-district expression when a district is supplied")
    void should_bindDistrictId_when_districtProvided() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(districtRequest(), PageRequest.of(0, 20));

        verify(dataQuery).setParameter("districtId", DISTRICT_ID);
        verify(countQuery).setParameter("districtId", DISTRICT_ID);
        String dataSql = sqlCaptor.getAllValues().get(0);
        assertThat(dataSql)
                .as("district-primary: filter on the salon-or-user discovery district")
                .contains("COALESCE(sal.district_id, u.district_id) = :districtId")
                .doesNotContain(":cityId");
    }

    @Test
    @DisplayName("binds :cityId (city-level widen) when a city is supplied without a district")
    void should_bindCityId_when_onlyCityProvided() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(cityRequest(), PageRequest.of(0, 20));

        verify(dataQuery).setParameter("cityId", CITY_ID);
        String dataSql = sqlCaptor.getAllValues().get(0);
        assertThat(dataSql)
                .contains("COALESCE(sal.city_id, u.city_id) = :cityId")
                .doesNotContain(":districtId");
    }

    @Test
    @DisplayName("omits both location parameters entirely when no location filter is provided")
    void should_omitLocationParams_when_noLocationFilter() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        verify(dataQuery, never()).setParameter(eq("cityId"), any());
        verify(dataQuery, never()).setParameter(eq("districtId"), any());
        assertThat(sqlCaptor.getAllValues().get(0))
                .doesNotContain(":cityId")
                .doesNotContain(":districtId");
    }

    @Test
    @DisplayName("no string-equality city/region filter remains in any generated SQL (Phase 10.5 bug fixed)")
    void should_notContainFreeTextCityRegionFilter_inAnySql() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(fullRequest(), PageRequest.of(0, 20));

        for (String sql : sqlCaptor.getAllValues()) {
            assertThat(sql)
                    .as("the broken free-text equality filter must be gone")
                    .doesNotContain("u.city = :city")
                    .doesNotContain("u.region = :region");
        }
    }

    @Test
    @DisplayName("excludes SALON_ADMIN accounts from master search via a role predicate")
    void should_excludeSalonAdmin_inGeneratedSql() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        for (String sql : sqlCaptor.getAllValues()) {
            assertThat(sql)
                    .as("SALON_ADMIN must never surface in public master discovery")
                    .contains("u.role <> :excludedRole");
        }
        verify(dataQuery).setParameter("excludedRole", "SALON_ADMIN");
    }

    @Test
    @DisplayName("always LEFT JOINs salons so an employed SALON_MASTER's locality resolves via the salon link")
    void should_joinSalonForSalonMasterLocality_inGeneratedSql() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        for (String sql : sqlCaptor.getAllValues()) {
            assertThat(sql)
                    .as("salon link is resolved at query time — never denormalised onto the master")
                    .contains("LEFT JOIN salons sal ON sal.id = m.salon_id");
        }
    }

    // ── label resolution via the M2 seam (no N+1) ────────────────────────────

    @Test
    @DisplayName("stamps resolved cityLabel/districtLabel from the batched M2 seam onto each result")
    void should_stampResolvedLabels_when_rowsReturned() {
        UUID masterId = UUID.randomUUID();
        Object[] row = new Object[]{
                masterId, "Olena", "Kovalenko",
                new BigDecimal("4.85"), 42, null,
                CITY_ID, DISTRICT_ID, new BigDecimal("250.00")
        };
        stubNativeQueries(List.<Object[]>of(row), 1L);
        when(discoveryLocationResolver.resolveLabels(any(), any()))
                .thenReturn(new DiscoveryLabels(
                        Map.of(CITY_ID, "Київ"),
                        Map.of(DISTRICT_ID, "Голосіївський район")));

        Page<MasterSearchResult> result = service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        MasterSearchResult mapped = result.getContent().get(0);
        assertThat(mapped.masterId()).isEqualTo(masterId);
        assertThat(mapped.cityLabel()).isEqualTo("Київ");
        assertThat(mapped.districtLabel()).isEqualTo("Голосіївський район");
        assertThat(mapped.minEffectivePrice()).isEqualTo(new BigDecimal("250.00"));
        assertThat(mapped.avgRating()).isEqualTo(4.85);
        assertThat(mapped.reviewCount()).isEqualTo(42);
        // Exactly one batched resolve for the whole page — never per-row (§E).
        verify(discoveryLocationResolver, times(1)).resolveLabels(any(), any());
    }

    @Test
    @DisplayName("returns an empty page with totalElements 0 when no rows match")
    void should_returnEmptyPage_when_noResultsFound() {
        stubNativeQueries(List.of(), 0L);

        Page<MasterSearchResult> result = service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.getTotalElements()).isZero();
    }

    // ── Phase 10.8 MEDIUM-1 — SARGable salon-location dispatch precedence ─────

    private static SalonSearchRequest salonRequest(UUID cityId, UUID districtId) {
        LocationFilter filter =
                (cityId == null && districtId == null) ? null : new LocationFilter(cityId, districtId);
        return new SalonSearchRequest(filter, 0, 20);
    }

    private static Page<Salon> oneSalonPage() {
        Salon salon = Salon.builder()
                .name("Test Salon")
                .cityId(CITY_ID)
                .districtId(DISTRICT_ID)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(salon, "id", UUID.randomUUID());
        return new PageImpl<>(List.of(salon), PageRequest.of(0, 20), 1);
    }

    @Test
    @DisplayName("salon search dispatches to findActiveByDistrictId when a district is resolved (district-primary)")
    void should_dispatchToDistrictRepoMethod_when_districtResolved() {
        when(salonRepository.findActiveByDistrictId(eq(DISTRICT_ID), any(Pageable.class)))
                .thenReturn(oneSalonPage());

        service.searchSalons(salonRequest(CITY_ID, DISTRICT_ID), PageRequest.of(0, 20));

        verify(salonRepository, times(1)).findActiveByDistrictId(eq(DISTRICT_ID), any(Pageable.class));
        verify(salonRepository, never()).findActiveByCityId(any(), any());
        verify(salonRepository, never()).findByIsActiveTrue(any());
    }

    @Test
    @DisplayName("salon search dispatches to findActiveByCityId when only a city is resolved")
    void should_dispatchToCityRepoMethod_when_onlyCityResolved() {
        when(salonRepository.findActiveByCityId(eq(CITY_ID), any(Pageable.class)))
                .thenReturn(oneSalonPage());

        service.searchSalons(salonRequest(CITY_ID, null), PageRequest.of(0, 20));

        verify(salonRepository, times(1)).findActiveByCityId(eq(CITY_ID), any(Pageable.class));
        verify(salonRepository, never()).findActiveByDistrictId(any(), any());
        verify(salonRepository, never()).findByIsActiveTrue(any());
    }

    @Test
    @DisplayName("salon search dispatches to findByIsActiveTrue when no locality filter is supplied")
    void should_dispatchToActiveOnlyRepoMethod_when_noLocalityFilter() {
        when(salonRepository.findByIsActiveTrue(any(Pageable.class)))
                .thenReturn(oneSalonPage());

        service.searchSalons(salonRequest(null, null), PageRequest.of(0, 20));

        verify(salonRepository, times(1)).findByIsActiveTrue(any(Pageable.class));
        verify(salonRepository, never()).findActiveByDistrictId(any(), any());
        verify(salonRepository, never()).findActiveByCityId(any(), any());
    }

    @Test
    @DisplayName("salon search resolves locality labels exactly ONCE per page, never one call per row (§E, N+1 contract — MEDIUM-4)")
    void should_resolveSalonLabelsOncePerPage_when_pageHasManyRows() {
        Salon a = Salon.builder().name("A").cityId(CITY_ID).districtId(DISTRICT_ID).isActive(true).build();
        Salon b = Salon.builder().name("B").cityId(CITY_ID).districtId(DISTRICT_ID).isActive(true).build();
        Salon c = Salon.builder().name("C").cityId(CITY_ID).districtId(DISTRICT_ID).isActive(true).build();
        ReflectionTestUtils.setField(a, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(b, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(c, "id", UUID.randomUUID());
        when(salonRepository.findActiveByCityId(eq(CITY_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(a, b, c), PageRequest.of(0, 20), 3));

        Page<SalonSearchResult> page =
                service.searchSalons(salonRequest(CITY_ID, null), PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(3);
        // EXACTLY ONE batched resolveLabels for the whole 3-row page — a
        // per-row regression would make this times(1) become times(3).
        verify(discoveryLocationResolver, times(1)).resolveLabels(any(), any());
    }

    // ── Phase 6.2 carry-over LOWs (still enforced) ───────────────────────────

    @Test
    @DisplayName("throws BusinessException without hitting the DB when minPrice exceeds maxPrice")
    void should_throwBusinessException_when_minPriceExceedsMaxPrice() {
        MasterSearchRequest request = new MasterSearchRequest(
                null, null,
                new BigDecimal("500.00"),
                new BigDecimal("100.00"),
                null, 0, 20
        );

        assertThatThrownBy(() -> service.searchMasters(request, PageRequest.of(0, 20)))
                .isInstanceOf(BusinessException.class)
                .hasMessage("minPrice must not exceed maxPrice");

        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("upper-cases category so the bound value matches the EnumType.STRING storage form")
    void should_normalizeCategoryCase_before_bindingParameter() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, "manicure", null, null, null, 0, 20);

        service.searchMasters(request, PageRequest.of(0, 20));

        verify(dataQuery).setParameter("category", "MANICURE");
        verify(countQuery).setParameter("category", "MANICURE");
    }

    @Test
    @DisplayName("normalises BigDecimal minRating to scale 2 before binding")
    void should_convertMinRatingToScaleTwo_before_binding() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, null, null, null, new BigDecimal("4.5"), 0, 20);

        service.searchMasters(request, PageRequest.of(0, 20));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(dataQuery, times(1)).setParameter(eq("minRating"), captor.capture());
        Object bound = captor.getValue();
        assertThat(bound).isInstanceOf(BigDecimal.class);
        assertThat(((BigDecimal) bound).scale()).isEqualTo(2);
        assertThat((BigDecimal) bound).isEqualTo(new BigDecimal("4.50"));
    }

    // ── Phase 6.3 active-flag filtering ──────────────────────────────────────

    @Test
    @DisplayName("master-search SQL filters on m.is_active = true and u.is_active = true")
    void should_filterByIsActiveTrue_inGeneratedSql() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        List<String> sqls = sqlCaptor.getAllValues();
        assertThat(sqls).hasSize(2);
        for (String sql : sqls) {
            assertThat(sql)
                    .contains("m.is_active = true")
                    .contains("u.is_active = true");
        }
    }

    // ── Phase 6.5 dynamic SQL — JOIN elision and count branching ─────────────

    @Test
    @DisplayName("omits master_services / service_definitions JOIN when no category or price filter")
    void should_omitServiceJoin_when_noCategoryOrPriceFilter() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(cityRequest(), PageRequest.of(0, 20));

        for (String sql : sqlCaptor.getAllValues()) {
            assertThat(sql)
                    .doesNotContain("master_services")
                    .doesNotContain("service_definitions");
        }
    }

    @Test
    @DisplayName("includes master_services / service_definitions JOIN when category filter is set")
    void should_includeServiceJoin_when_categoryFilterSet() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(
                new MasterSearchRequest(null, "MANICURE", null, null, null, 0, 20),
                PageRequest.of(0, 20));

        String dataSql = sqlCaptor.getAllValues().get(0);
        assertThat(dataSql)
                .contains("master_services")
                .contains("service_definitions")
                .contains("sd.category = :category");
    }

    @Test
    @DisplayName("count query is a flat COUNT(DISTINCT m.id) when no price filter is set")
    void should_emitFlatCountQuery_when_noPriceFilter() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        String countSql = sqlCaptor.getAllValues().get(1);
        assertThat(countSql)
                .contains("COUNT(DISTINCT m.id)")
                .doesNotContain("HAVING")
                .doesNotContain("FROM (");
    }

    @Test
    @DisplayName("count query wraps the GROUP BY + HAVING in a subquery when a price filter is set")
    void should_emitWrappedCountQuery_when_priceFilterSet() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(
                new MasterSearchRequest(null, null,
                        new BigDecimal("100.00"),
                        new BigDecimal("500.00"),
                        null, 0, 20),
                PageRequest.of(0, 20));

        String countSql = sqlCaptor.getAllValues().get(1);
        assertThat(countSql)
                .contains("SELECT COUNT(*) FROM (")
                .contains("GROUP BY m.id")
                .contains("HAVING")
                .endsWith(") AS filtered");
    }

    @Test
    @DisplayName("does NOT use CAST(:p AS VARCHAR) workarounds in any generated SQL")
    void should_notUseCastWorkaround_inAnyGeneratedSql() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(fullRequest(), PageRequest.of(0, 20));

        for (String sql : sqlCaptor.getAllValues()) {
            assertThat(sql).doesNotContain("CAST(:");
        }
    }

    @Test
    @DisplayName("ORDER BY includes m.id as the deterministic tie-breaker")
    void should_orderByRatingThenId_when_dataQueryIssued() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        String dataSql = sqlCaptor.getAllValues().get(0);
        assertThat(dataSql).contains("ORDER BY m.avg_rating DESC NULLS LAST, m.id");
    }
}
