package com.beautica.search.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.location.DiscoveryLocationKey;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.repository.SalonSearchProjection;
import com.beautica.search.dto.LocationFilter;
import com.beautica.search.dto.MasterSearchRequest;
import com.beautica.search.dto.MasterSearchResult;
import com.beautica.search.dto.SalonSearchRequest;
import com.beautica.search.dto.SalonSearchResult;
import com.beautica.search.dto.SearchSort;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
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

    // countQuery mock removed — PERF-M1 replaced the two-query pattern with a single
    // native query that embeds COUNT(*) OVER() as row[14] (TOTAL_COUNT_IDX) of every result row.

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
        // PERF-M1: a single native query replaces the old data+count two-query pattern.
        // Phase 19.x record-component additions widened the final wrapped projection to
        // 15 columns (indices 0–14):
        //   0 master_id, 1 first_name, 2 last_name, 3 avg_rating, 4 review_count,
        //   5 avatar_url, 6 discovery_city_id, 7 discovery_district_id,
        //   8 min_effective_price, 9 price_max, 10 service_names, 11 street,
        //   12 building_no, 13 location_note, 14 total_count (COUNT(*) OVER()).
        // Test rows are authored short (9–10 columns); this extends them so the
        // not-explicitly-set columns (price_max, service_names, street, building_no,
        // location_note) default to null and the window-function total lands at
        // TOTAL_COUNT_IDX (14).
        List<Object[]> rowsWithCount = rows.stream()
                .map(row -> {
                    Object[] extended = java.util.Arrays.copyOf(row, 15);
                    extended[14] = total;   // TOTAL_COUNT_IDX in SearchService
                    return extended;
                })
                .toList();
        when(entityManager.createNativeQuery(sqlCaptor.capture())).thenReturn(dataQuery);
        lenient().when(dataQuery.setParameter(anyString(), any())).thenReturn(dataQuery);
        // Portable pagination API — setMaxResults/setFirstResult replace the old
        // :limit/:offset named-parameter binding (LOW portability fix).
        lenient().when(dataQuery.setMaxResults(anyInt())).thenReturn(dataQuery);
        lenient().when(dataQuery.setFirstResult(anyInt())).thenReturn(dataQuery);
        when(dataQuery.getResultList()).thenReturn((List) rowsWithCount);
    }

    private static final UUID CITY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DISTRICT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    // MasterSearchRequest field order: (location, q, category, sort, minPrice, maxPrice, minRating, page, size)
    private static MasterSearchRequest emptyRequest() {
        return new MasterSearchRequest(null, null, null, null, null, null, null, 0, 20);
    }

    private static MasterSearchRequest cityRequest() {
        return new MasterSearchRequest(
                new LocationFilter(CITY_ID, null), null, null, null, null, null, null, 0, 20);
    }

    private static MasterSearchRequest districtRequest() {
        return new MasterSearchRequest(
                new LocationFilter(CITY_ID, DISTRICT_ID), null, null, null, null, null, null, 0, 20);
    }

    private static MasterSearchRequest fullRequest() {
        return new MasterSearchRequest(
                new LocationFilter(CITY_ID, DISTRICT_ID),
                null,
                "manicure",
                null,
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
        // countQuery removed — PERF-M1: single query with COUNT(*) OVER(), no separate count query.
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
    @DisplayName("restricts master search to INDEPENDENT_MASTER via an equality role predicate (Phase 19.7 — SALON_MASTER/SALON_ADMIN never surface)")
    void should_restrictToIndependentMaster_inGeneratedSql() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        for (String sql : sqlCaptor.getAllValues()) {
            assertThat(sql)
                    .as("public master discovery returns INDEPENDENT_MASTER only")
                    .contains("u.role = :includedRole");
        }
        verify(dataQuery).setParameter("includedRole", "INDEPENDENT_MASTER");
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
        // SalonSearchRequest: (location, q, category, sort, minPrice, maxPrice, page, size)
        return new SalonSearchRequest(filter, null, null, null, null, null, 0, 20);
    }

    /**
     * Builds a stub {@link SalonSearchProjection} using Mockito's interface-mock capability.
     * This is the correct approach since the service now requests a projection
     * rather than full entity hydration (LOW PERF fix — no Salon entity loading).
     */
    private static SalonSearchProjection stubProjection(UUID id, String name, UUID cityId, UUID districtId) {
        SalonSearchProjection proj = mock(SalonSearchProjection.class);
        when(proj.getId()).thenReturn(id);
        when(proj.getName()).thenReturn(name);
        when(proj.getCityId()).thenReturn(cityId);
        when(proj.getDistrictId()).thenReturn(districtId);
        when(proj.getAvatarUrl()).thenReturn(null);
        return proj;
    }

    private static Page<SalonSearchProjection> oneSalonProjectionPage() {
        SalonSearchProjection proj = stubProjection(UUID.randomUUID(), "Test Salon", CITY_ID, DISTRICT_ID);
        return new PageImpl<>(List.of(proj), PageRequest.of(0, 20), 1);
    }

    @Test
    @DisplayName("salon search dispatches to the no-price district variant when a district is resolved and no price bounds are supplied (HIGH PERF gate: plain COUNT(*), no lateral)")
    void should_dispatchToDistrictRepoMethod_when_districtResolved() {
        // Build the stub page BEFORE the when(...) call — stubProjection calls
        // when(mock.getX()) internally, which Mockito would misread as an
        // unfinished stub if nested inside when(salonRepository...).
        Page<SalonSearchProjection> stubPage = oneSalonProjectionPage();
        when(salonRepository.findActiveByDistrictIdNoPriceAsProjection(
                eq(DISTRICT_ID), any(), any(), any(Pageable.class)))
                .thenReturn(stubPage);

        service.searchSalons(salonRequest(CITY_ID, DISTRICT_ID), PageRequest.of(0, 20));

        // No price bounds → no-price variant (no COUNT lateral).
        verify(salonRepository, times(1)).findActiveByDistrictIdNoPriceAsProjection(
                eq(DISTRICT_ID), any(), any(), any(Pageable.class));
        verify(salonRepository, never()).findActiveByDistrictIdAsProjection(any(), any(), any(), any(), any(), any());
        verify(salonRepository, never()).findActiveByCityIdNoPriceAsProjection(any(), any(), any(), any());
        verify(salonRepository, never()).findByIsActiveTrueNoPriceAsProjection(any(), any(), any());
        // Must NOT touch the full-entity variants — they hydrate unnecessary columns.
        verify(salonRepository, never()).findActiveByDistrictId(any(), any());
        verify(salonRepository, never()).findActiveByCityId(any(), any());
        verify(salonRepository, never()).findByIsActiveTrue(any());
    }

    @Test
    @DisplayName("salon search dispatches to the no-price city variant when only a city is resolved and no price bounds are supplied (HIGH PERF gate)")
    void should_dispatchToCityRepoMethod_when_onlyCityResolved() {
        Page<SalonSearchProjection> stubPage = oneSalonProjectionPage();
        when(salonRepository.findActiveByCityIdNoPriceAsProjection(
                eq(CITY_ID), any(), any(), any(Pageable.class)))
                .thenReturn(stubPage);

        service.searchSalons(salonRequest(CITY_ID, null), PageRequest.of(0, 20));

        verify(salonRepository, times(1)).findActiveByCityIdNoPriceAsProjection(
                eq(CITY_ID), any(), any(), any(Pageable.class));
        verify(salonRepository, never()).findActiveByCityIdAsProjection(any(), any(), any(), any(), any(), any());
        verify(salonRepository, never()).findActiveByDistrictIdNoPriceAsProjection(any(), any(), any(), any());
        verify(salonRepository, never()).findByIsActiveTrueNoPriceAsProjection(any(), any(), any());
        verify(salonRepository, never()).findActiveByDistrictId(any(), any());
        verify(salonRepository, never()).findActiveByCityId(any(), any());
        verify(salonRepository, never()).findByIsActiveTrue(any());
    }

    @Test
    @DisplayName("salon search dispatches to the no-price active-only variant when no locality filter and no price bounds are supplied (HIGH PERF gate)")
    void should_dispatchToActiveOnlyRepoMethod_when_noLocalityFilter() {
        Page<SalonSearchProjection> stubPage = oneSalonProjectionPage();
        when(salonRepository.findByIsActiveTrueNoPriceAsProjection(any(), any(), any(Pageable.class)))
                .thenReturn(stubPage);

        service.searchSalons(salonRequest(null, null), PageRequest.of(0, 20));

        verify(salonRepository, times(1)).findByIsActiveTrueNoPriceAsProjection(any(), any(), any(Pageable.class));
        verify(salonRepository, never()).findByIsActiveTrueAsProjection(any(), any(), any(), any(), any());
        verify(salonRepository, never()).findActiveByDistrictIdNoPriceAsProjection(any(), any(), any(), any());
        verify(salonRepository, never()).findActiveByCityIdNoPriceAsProjection(any(), any(), any(), any());
        verify(salonRepository, never()).findActiveByDistrictId(any(), any());
        verify(salonRepository, never()).findActiveByCityId(any(), any());
        verify(salonRepository, never()).findByIsActiveTrue(any());
    }

    @Test
    @DisplayName("salon search with a price bound keeps the price-lateral variant (no-price gate applies only when both bounds are null)")
    void should_dispatchToPriceVariant_when_priceBoundSupplied() {
        Page<SalonSearchProjection> stubPage = oneSalonProjectionPage();
        when(salonRepository.findActiveByCityIdAsProjection(
                eq(CITY_ID), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(stubPage);
        SalonSearchRequest request = new SalonSearchRequest(
                new LocationFilter(CITY_ID, null), null, null, null,
                new BigDecimal("100.00"), null, 0, 20);

        service.searchSalons(request, PageRequest.of(0, 20));

        verify(salonRepository, times(1)).findActiveByCityIdAsProjection(
                eq(CITY_ID), any(), any(), any(), any(), any(Pageable.class));
        verify(salonRepository, never()).findActiveByCityIdNoPriceAsProjection(any(), any(), any(), any());
    }

    @Test
    @DisplayName("salon search maps projection fields to SalonSearchResult correctly, stamping resolved labels (LOW PERF fix verification)")
    void should_mapProjectionToSalonSearchResult_with_resolvedLabels() {
        UUID salonId = UUID.randomUUID();
        SalonSearchProjection proj = stubProjection(salonId, "Glow Studio", CITY_ID, DISTRICT_ID);
        when(proj.getAvatarUrl()).thenReturn("https://cdn.example.com/avatar.jpg");

        when(salonRepository.findActiveByCityIdNoPriceAsProjection(
                eq(CITY_ID), any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(proj), PageRequest.of(0, 20), 1));
        when(discoveryLocationResolver.resolveLabels(any(), any()))
                .thenReturn(new DiscoveryLabels(
                        Map.of(CITY_ID, "Київ"),
                        Map.of(DISTRICT_ID, "Шевченківський район")));

        Page<SalonSearchResult> result = service.searchSalons(salonRequest(CITY_ID, null), PageRequest.of(0, 20));

        assertThat(result.getContent()).hasSize(1);
        SalonSearchResult mapped = result.getContent().get(0);
        assertThat(mapped.salonId()).isEqualTo(salonId);
        assertThat(mapped.name()).isEqualTo("Glow Studio");
        assertThat(mapped.cityLabel()).isEqualTo("Київ");
        assertThat(mapped.districtLabel()).isEqualTo("Шевченківський район");
        assertThat(mapped.avatarUrl()).isEqualTo("https://cdn.example.com/avatar.jpg");
    }

    @Test
    @DisplayName("salon search resolves locality labels exactly ONCE per page, never one call per row (§E, N+1 contract — MEDIUM-4)")
    void should_resolveSalonLabelsOncePerPage_when_pageHasManyRows() {
        SalonSearchProjection a = stubProjection(UUID.randomUUID(), "A", CITY_ID, DISTRICT_ID);
        SalonSearchProjection b = stubProjection(UUID.randomUUID(), "B", CITY_ID, DISTRICT_ID);
        SalonSearchProjection c = stubProjection(UUID.randomUUID(), "C", CITY_ID, DISTRICT_ID);
        when(salonRepository.findActiveByCityIdNoPriceAsProjection(
                eq(CITY_ID), any(), any(), any(Pageable.class)))
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
                null, null, null, null,
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
                null, null, "manicure", null, null, null, null, 0, 20);

        service.searchMasters(request, PageRequest.of(0, 20));

        verify(dataQuery).setParameter("category", "MANICURE");
        // countQuery removed — PERF-M1: single query with COUNT(*) OVER(), no separate count query.
    }

    @Test
    @DisplayName("normalises BigDecimal minRating to scale 2 before binding")
    void should_convertMinRatingToScaleTwo_before_binding() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, null, null, null, null, null, new BigDecimal("4.5"), 0, 20);

        service.searchMasters(request, PageRequest.of(0, 20));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(dataQuery, times(1)).setParameter(eq("minRating"), captor.capture());
        Object bound = captor.getValue();
        assertThat(bound).isInstanceOf(BigDecimal.class);
        assertThat(((BigDecimal) bound).scale()).isEqualTo(2);
        assertThat((BigDecimal) bound).isEqualTo(new BigDecimal("4.50"));
    }

    @Test
    @DisplayName("normalises BigDecimal minPrice and maxPrice to scale 2 before binding (cache-key stability)")
    void should_normalizeMinPriceAndMaxPrice_to_scale2_before_binding() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, null, null, null,
                new BigDecimal("1.0"),      // scale 1 — must become 1.00
                new BigDecimal("500"),       // scale 0 — must become 500.00
                null, 0, 20);

        service.searchMasters(request, PageRequest.of(0, 20));

        ArgumentCaptor<Object> minCaptor = ArgumentCaptor.forClass(Object.class);
        ArgumentCaptor<Object> maxCaptor = ArgumentCaptor.forClass(Object.class);
        verify(dataQuery).setParameter(eq("minPrice"), minCaptor.capture());
        verify(dataQuery).setParameter(eq("maxPrice"), maxCaptor.capture());

        assertThat(((BigDecimal) minCaptor.getValue()).scale())
                .as("minPrice must be normalised to scale 2 for cache-key stability")
                .isEqualTo(2);
        assertThat((BigDecimal) minCaptor.getValue())
                .isEqualByComparingTo(new BigDecimal("1.00"));
        assertThat(((BigDecimal) maxCaptor.getValue()).scale())
                .as("maxPrice must be normalised to scale 2 for cache-key stability")
                .isEqualTo(2);
        assertThat((BigDecimal) maxCaptor.getValue())
                .isEqualByComparingTo(new BigDecimal("500.00"));
    }

    // ── Phase 6.3 active-flag filtering ──────────────────────────────────────

    @Test
    @DisplayName("master-search SQL filters on m.is_active = true and u.is_active = true")
    void should_filterByIsActiveTrue_inGeneratedSql() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        // PERF-M1: single query (window function replaces separate count query).
        List<String> sqls = sqlCaptor.getAllValues();
        assertThat(sqls).hasSize(1);
        assertThat(sqls.get(0))
                .contains("m.is_active = true")
                .contains("u.is_active = true");
    }

    // ── dynamic SQL — Top-N main query + post-LIMIT serviceNames lateral ─────

    @Test
    @DisplayName("the MAIN master query carries NO service join and NO GROUP BY; serviceNames comes from a post-LIMIT lateral (index-ordered Top-N preserved)")
    void should_keepMainQueryServiceJoinFreeAndUngrouped_with_serviceNamesLateral() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(cityRequest(), PageRequest.of(0, 20));

        String sql = sqlCaptor.getAllValues().get(0);
        // No GROUP BY anywhere — the refactor removed the district-wide grouping
        // that pipeline-broke the index-ordered Top-N.
        assertThat(sql)
                .as("no GROUP BY in the master query — it would break the index-ordered LIMIT")
                .doesNotContain("GROUP BY");
        // serviceNames is still populated on EVERY card — via a post-LIMIT
        // correlated lateral, not a main-query join.
        assertThat(sql)
                .as("serviceNames is computed by a post-LIMIT correlated lateral")
                .contains("LEFT JOIN LATERAL")
                // PERF-M1: array_agg now wraps a DISTINCT+ORDER+LIMIT derived table,
                // so "array_agg(x.name)" and "AS service_names" are no longer contiguous.
                .contains("array_agg(x.name)")
                .contains(") AS service_names")
                .contains("WHERE ms.master_id = t.master_id");
        // Inner Top-N is bounded by LIMIT/OFFSET so the lateral runs over the
        // paged rows only, not the whole district.
        assertThat(sql).contains("LIMIT :limit OFFSET :offset");
    }

    @Test
    @DisplayName("category filter is a correlated EXISTS over active services (no main-query service join)")
    void should_includeCategoryExists_when_categoryFilterSet() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(
                new MasterSearchRequest(null, null, "MANICURE", null, null, null, null, 0, 20),
                PageRequest.of(0, 20));

        String dataSql = sqlCaptor.getAllValues().get(0);
        assertThat(dataSql)
                .as("category is an EXISTS predicate, not a fan-out join + GROUP BY")
                .contains("AND EXISTS (")
                .contains("sd.category = :category")
                .doesNotContain("GROUP BY");
    }

    @Test
    @DisplayName("issues exactly one native query per call (PERF-M1: COUNT(*) OVER() window function)")
    void should_issueExactlyOneNativeQuery_when_searchPerformed() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        verify(entityManager, times(1)).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("total count is read from COUNT(*) OVER() in row[14], not a separate query (PERF-M1)")
    void should_readTotalFromWindowFunction_when_rowsReturned() {
        Object[] row = new Object[]{
                UUID.randomUUID(), "Anna", "Koval",
                new BigDecimal("4.20"), 5, null,
                CITY_ID, null, new BigDecimal("350.00")
        };
        stubNativeQueries(List.<Object[]>of(row), 7L);  // total=7, embedded as row[14] by stubNativeQueries

        // Use pageSize=5 so Spring Data's PageImpl last-page adjustment doesn't fire.
        // PageImpl adjusts total when offset+pageSize > total (last-page heuristic):
        //   PageRequest.of(0,20) → 0+20=20 > 7 → total becomes offset+content.size()=1 ✗
        //   PageRequest.of(0, 5) → 0+ 5= 5 ≤ 7 → total stays 7 ✓
        Page<MasterSearchResult> result = service.searchMasters(emptyRequest(), PageRequest.of(0, 5));

        assertThat(result.getTotalElements()).isEqualTo(7L);
        verify(entityManager, times(1)).createNativeQuery(anyString());
    }

    // ── inner-Top-N pagination — LIMIT/OFFSET bound on the inner derived table ──

    @Test
    @DisplayName("binds :limit/:offset on the inner Top-N so the serviceNames lateral runs above the LIMIT (paged rows only)")
    void should_bindLimitOffsetOnInnerTopN_for_postLimitLateral() {
        stubNativeQueries(List.of(), 0L);
        Pageable page = PageRequest.of(2, 15);

        service.searchMasters(emptyRequest(), page);

        // The LIMIT/OFFSET must sit on the INNER derived table (bound as named
        // params) so the post-LIMIT serviceNames lateral never expands over the
        // whole district. Hibernate's setMaxResults/setFirstResult would wrap the
        // OUTER lateral and re-expand the work, so they are NOT used here.
        String dataSql = sqlCaptor.getAllValues().get(0);
        assertThat(dataSql)
                .as("inner Top-N carries the LIMIT/OFFSET named params")
                .contains("LIMIT :limit OFFSET :offset");
        verify(dataQuery).setParameter("limit", 15);
        verify(dataQuery).setParameter("offset", 30L); // offset = page * size = 2 * 15
        verify(dataQuery, never()).setMaxResults(anyInt());
        verify(dataQuery, never()).setFirstResult(anyInt());
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

    // ── name / service-name search (q) ───────────────────────────────────────

    @Test
    @DisplayName("adds a case-insensitive ILIKE over first/last name plus a service-name EXISTS when q is supplied (each index-served, no join fan-out)")
    void should_addIlikePredicate_when_qProvided() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, "olena", null, null, null, null, null, 0, 20);

        service.searchMasters(request, PageRequest.of(0, 20));

        String dataSql = sqlCaptor.getAllValues().get(0);
        // Name predicates hit u.first_name/u.last_name directly; the service-name
        // match is a correlated EXISTS on sd.name (not a main-query join), so each
        // ILIKE is served by its own trigram index with no fan-out / GROUP BY.
        assertThat(dataSql)
                .contains("u.first_name ILIKE :q OR u.last_name ILIKE :q OR EXISTS (")
                .contains("sd.name ILIKE :q")
                .doesNotContain("GROUP BY");
    }

    @Test
    @DisplayName("binds :q as an escaped %term% pattern (LIKE wildcards in the term are neutralised)")
    void should_bindEscapedContainsPattern_when_qHasLikeWildcards() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, "50%_off", null, null, null, null, null, 0, 20);

        service.searchMasters(request, PageRequest.of(0, 20));

        verify(dataQuery).setParameter("q", "%50\\%\\_off%");
    }

    @Test
    @DisplayName("serviceNames is populated on EVERY card via a post-LIMIT lateral, never gated behind q/category — and the main query has no service join / GROUP BY")
    void should_populateServiceNamesViaPostLimitLateral_onEveryCard() {
        stubNativeQueries(List.of(), 0L);

        // Location-only search: no q, no category. serviceNames must STILL be
        // computed (the product constraint — every master card shows its
        // procedure name), and it must come from the post-LIMIT lateral.
        service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        String dataSql = sqlCaptor.getAllValues().get(0);
        assertThat(dataSql)
                .as("serviceNames lateral present even with no q/category filter")
                .contains("LEFT JOIN LATERAL")
                .contains("SELECT DISTINCT sd.name")
                // PERF-M1: array_agg wraps a DISTINCT+ORDER+LIMIT derived table, so
                // "array_agg(x.name)" and "AS service_names" are no longer contiguous.
                .contains("array_agg(x.name)")
                .contains(") AS service_names")
                .contains("ORDER BY sd.name LIMIT 3");
        assertThat(dataSql)
                .as("main query is service-join-free and ungrouped — index-ordered Top-N preserved")
                .doesNotContain("GROUP BY")
                .doesNotContain("LEFT JOIN master_services ms ON")
                .doesNotContain("LEFT JOIN service_definitions sd ON");
    }

    // ── custom-preferred procedure names (serviceNames) ──────────────────────

    @Test
    @DisplayName("maps the SQL service_names array to a List<String> on the result")
    void should_mapServiceNamesArray_toList() {
        UUID masterId = UUID.randomUUID();
        Object[] row = new Object[]{
                masterId, "Olena", "Kovalenko",
                new BigDecimal("4.85"), 42, null,
                CITY_ID, DISTRICT_ID, new BigDecimal("250.00"),
                new BigDecimal("250.00"),             // price_max at index 9
                new String[]{"Манікюр", "Педикюр"}    // service_names at index 10
        };
        stubNativeQueries(List.<Object[]>of(row), 1L);

        Page<MasterSearchResult> result = service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).serviceNames())
                .containsExactly("Манікюр", "Педикюр");
    }

    @Test
    @DisplayName("maps a null service_names array to an empty list (never null) for service-less masters")
    void should_mapNullServiceNames_toEmptyList() {
        UUID masterId = UUID.randomUUID();
        Object[] row = new Object[]{
                masterId, "Ivan", "Petrenko",
                new BigDecimal("4.00"), 1, null,
                CITY_ID, null, null   // 9-wide → price_max (9) + service_names (10) left null by stub extension
        };
        stubNativeQueries(List.<Object[]>of(row), 1L);

        Page<MasterSearchResult> result = service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).serviceNames()).isEmpty();
    }

    // ── allow-listed sort (master) ───────────────────────────────────────────

    @Test
    @DisplayName("PRICE_ASC maps to ORDER BY m.min_effective_price ASC NULLS LAST, m.id")
    void should_orderByPriceAsc_when_sortPriceAsc() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, null, null, SearchSort.PRICE_ASC, null, null, null, 0, 20);

        service.searchMasters(request, PageRequest.of(0, 20));

        assertThat(sqlCaptor.getAllValues().get(0))
                .contains("ORDER BY m.min_effective_price ASC NULLS LAST, m.id");
    }

    @Test
    @DisplayName("PRICE_DESC maps to ORDER BY m.min_effective_price DESC NULLS LAST, m.id")
    void should_orderByPriceDesc_when_sortPriceDesc() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, null, null, SearchSort.PRICE_DESC, null, null, null, 0, 20);

        service.searchMasters(request, PageRequest.of(0, 20));

        assertThat(sqlCaptor.getAllValues().get(0))
                .contains("ORDER BY m.min_effective_price DESC NULLS LAST, m.id");
    }

    @Test
    @DisplayName("REVIEWS_DESC maps to ORDER BY m.review_count DESC NULLS LAST, m.id")
    void should_orderByReviewsDesc_when_sortReviewsDesc() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, null, null, SearchSort.REVIEWS_DESC, null, null, null, 0, 20);

        service.searchMasters(request, PageRequest.of(0, 20));

        assertThat(sqlCaptor.getAllValues().get(0))
                .contains("ORDER BY m.review_count DESC NULLS LAST, m.id");
    }

    @Test
    @DisplayName("a null sort falls back to the rating-descending default")
    void should_defaultToRatingDesc_when_sortNull() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        assertThat(sqlCaptor.getAllValues().get(0))
                .contains("ORDER BY m.avg_rating DESC NULLS LAST, m.id");
    }

    // ── salon price filter + q forwarding ────────────────────────────────────

    @Test
    @DisplayName("forwards q (escaped) and price bounds to the salon projection query")
    void should_forwardQAndPriceBounds_toSalonRepo() {
        Page<SalonSearchProjection> stubPage = oneSalonProjectionPage();
        when(salonRepository.findActiveByCityIdAsProjection(
                eq(CITY_ID), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(stubPage);
        SalonSearchRequest request = new SalonSearchRequest(
                new LocationFilter(CITY_ID, null), "glow", null, null,
                new BigDecimal("100.00"), new BigDecimal("500.00"), 0, 20);

        service.searchSalons(request, PageRequest.of(0, 20));

        verify(salonRepository).findActiveByCityIdAsProjection(
                eq(CITY_ID), any(),
                eq("%glow%"),
                eq(new BigDecimal("100.00")),
                eq(new BigDecimal("500.00")),
                any(Pageable.class));
    }

    @Test
    @DisplayName("salon PRICE_ASC builds a Sort on the price_min select alias (caller text never reaches ORDER BY)")
    void should_buildPriceMinSort_when_salonSortPriceAsc() {
        Page<SalonSearchProjection> stubPage = oneSalonProjectionPage();
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        // No price bounds → no-price city variant (HIGH PERF gate).
        when(salonRepository.findActiveByCityIdNoPriceAsProjection(
                eq(CITY_ID), any(), any(), pageableCaptor.capture()))
                .thenReturn(stubPage);
        SalonSearchRequest request = new SalonSearchRequest(
                new LocationFilter(CITY_ID, null), null, null, SearchSort.PRICE_ASC,
                null, null, 0, 20);

        service.searchSalons(request, PageRequest.of(0, 20));

        org.springframework.data.domain.Sort sort = pageableCaptor.getValue().getSort();
        assertThat(sort.getOrderFor("price_min")).isNotNull();
        assertThat(sort.getOrderFor("price_min").isAscending()).isTrue();
    }
}
