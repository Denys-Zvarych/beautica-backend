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
import com.beautica.service.service.PlatformCategoryLabel;
import com.beautica.service.service.PlatformCategoryLabelResolver;
import com.beautica.service.service.ServiceTypeMatch;
import com.beautica.service.service.ServiceTypeSlugResolver;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Mock
    private ServiceTypeSlugResolver serviceTypeSlugResolver;

    // Category half of free-text search. Stubbed empty by default in setUp(), so every
    // pre-existing test below generates byte-identical SQL to before the feature — the
    // category disjunct only appears once a test supplies a matching display name.
    @Mock
    private PlatformCategoryLabelResolver platformCategoryLabelResolver;

    // Filter-scoped total memo (perf follow-up): getCache(...) returns null (no cache bean
    // registered under that name) by default, so readMemoizedTotal/writeMemoizedTotal are
    // no-ops and every pre-existing test below exercises the exact same code path as before
    // this feature — the memo never activates unless a test explicitly stubs a real Cache.
    @Mock
    private CacheManager cacheManager;

    private SearchService service;

    private ArgumentCaptor<String> sqlCaptor;

    @BeforeEach
    void setUp() {
        service = new SearchService(
                salonRepository, discoveryLocationResolver, serviceTypeSlugResolver,
                platformCategoryLabelResolver, cacheManager);
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
        lenient().when(cacheManager.getCache(anyString())).thenReturn(null);
        // No selectable categories by default → resolveQueryCategories() returns empty
        // → every category disjunct collapses to the empty string, so the generated SQL
        // is byte-identical to the pre-feature form for all existing assertions.
        lenient().when(platformCategoryLabelResolver.selectableLabels()).thenReturn(List.of());
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
        // Phase 20.3 widened the wrapped master projection to 16 columns (indices
        // 0–15): matched_names landed at index 14 (empty here — no service filter)
        // and total_count (COUNT(*) OVER()) moved to index 15 (TOTAL_COUNT_IDX).
        List<Object[]> rowsWithCount = rows.stream()
                .map(row -> {
                    Object[] extended = java.util.Arrays.copyOf(row, 16);
                    extended[15] = total;   // TOTAL_COUNT_IDX in SearchService
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

    // MasterSearchRequest field order: (location, q, category, sort, minPrice, maxPrice, minRating, page, size, serviceTypeSlugs)
    private static MasterSearchRequest emptyRequest() {
        return new MasterSearchRequest(null, null, null, null, null, null, null, 0, 20, null);
    }

    private static MasterSearchRequest cityRequest() {
        return new MasterSearchRequest(
                new LocationFilter(CITY_ID, null), null, null, null, null, null, null, 0, 20, null);
    }

    private static MasterSearchRequest districtRequest() {
        return new MasterSearchRequest(
                new LocationFilter(CITY_ID, DISTRICT_ID), null, null, null, null, null, null, 0, 20, null);
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
                20,
                null
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

    // ── zero-review rating normalisation (Phase 240 audit, Finding 3) ─────────
    //
    // masters.avg_rating is NOT NULL DEFAULT 0.00 (V4), so the native projection hands this
    // mapper a literal 0.00 for a master nobody has rated. Search used to pass that straight
    // through while GET /masters/{id} and the booking payload served null for the SAME master —
    // the discovery list rendered «0.0» beside a profile that said «no reviews yet».

    @Test
    @DisplayName("nulls avgRating for a zero-review master rather than passing the stored 0.00 through")
    void should_nullAvgRating_when_rowCarriesZeroReviewCount() {
        Object[] row = new Object[]{
                UUID.randomUUID(), "Nova", "Maistrynia",
                new BigDecimal("0.00"), 0, null,
                CITY_ID, DISTRICT_ID, new BigDecimal("250.00")
        };
        stubNativeQueries(List.<Object[]>of(row), 1L);

        Page<MasterSearchResult> result = service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        MasterSearchResult mapped = result.getContent().get(0);
        assertThat(mapped.avgRating())
                .as("a brand-new master must not be rendered as a 0.0-star provider in discovery")
                .isNull();
        assertThat(mapped.reviewCount())
                .as("zero reviews is a true fact and stays 0 — only the average is suppressed, "
                    + "actual=%s", mapped.reviewCount())
                .isZero();
    }

    @Test
    @DisplayName("keeps a genuine 1.00 average when the master has exactly one review")
    void should_keepAvgRating_when_rowCarriesOneReview() {
        // The boundary that separates the suppressed storage artefact from a real bad rating.
        // A suppression keyed off the VALUE (0.00 / null) instead of the COUNT would be
        // indetectable at 4.85 above but would wrongly hide this genuine one-star provider.
        Object[] row = new Object[]{
                UUID.randomUUID(), "Odna", "Zirka",
                new BigDecimal("1.00"), 1, null,
                CITY_ID, DISTRICT_ID, new BigDecimal("250.00")
        };
        stubNativeQueries(List.<Object[]>of(row), 1L);

        Page<MasterSearchResult> result = service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        assertThat(result.getContent().get(0).avgRating())
                .as("a real one-star average must survive the zero-review normalisation")
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("passes the raw average through when review_count is null — unknown is not zero")
    void should_passRawAvgRatingThrough_when_rowCarriesNullReviewCount() {
        // Defensive branch, pinned because the two claims differ: "this master has zero reviews"
        // licenses suppressing the average; "the count is unknown" does not. A future refactor
        // that folded null into the zero case would silently blank real ratings.
        Object[] row = new Object[]{
                UUID.randomUUID(), "Nevidoma", "Kilkist",
                new BigDecimal("3.50"), null, null,
                CITY_ID, DISTRICT_ID, new BigDecimal("250.00")
        };
        stubNativeQueries(List.<Object[]>of(row), 1L);

        Page<MasterSearchResult> result = service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        MasterSearchResult mapped = result.getContent().get(0);
        assertThat(mapped.avgRating())
                .as("an unknown review count must not blank a stored average, actual=%s",
                        mapped.avgRating())
                .isEqualTo(3.5);
        assertThat(mapped.reviewCount())
                .as("the unknown count is reported as null, not coerced to 0")
                .isNull();
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
        // SalonSearchRequest: (location, q, category, sort, minPrice, maxPrice, page, size, serviceTypeSlugs)
        return new SalonSearchRequest(filter, null, null, null, null, null, 0, 20, null);
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
        // COUNT(*) OVER() rides on every row — the service reads the page total off
        // the FIRST projection only, so on a multi-row stub the later rows' stubs are
        // legitimately unused. lenient() rather than dropping the stub: every real row
        // does carry the column, and a strict stub here would fail every multi-row test.
        lenient().when(proj.getTotalCount()).thenReturn(1L);
        return proj;
    }

    private static List<SalonSearchProjection> oneSalonProjectionList() {
        return List.of(stubProjection(UUID.randomUUID(), "Test Salon", CITY_ID, DISTRICT_ID));
    }

    @Test
    @DisplayName("salon search dispatches to the no-price district variant when a district is resolved and no price bounds are supplied (HIGH PERF gate: plain COUNT(*), no lateral)")
    void should_dispatchToDistrictRepoMethod_when_districtResolved() {
        // Build the stub page BEFORE the when(...) call — stubProjection calls
        // when(mock.getX()) internally, which Mockito would misread as an
        // unfinished stub if nested inside when(salonRepository...).
        List<SalonSearchProjection> stubRows = oneSalonProjectionList();
        when(salonRepository.findActiveByDistrictIdNoPriceAsProjection(
                eq(DISTRICT_ID), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong()))
                .thenReturn(stubRows);

        service.searchSalons(salonRequest(CITY_ID, DISTRICT_ID), PageRequest.of(0, 20));

        // No price bounds → no-price variant (no COUNT lateral).
        verify(salonRepository, times(1)).findActiveByDistrictIdNoPriceAsProjection(
                eq(DISTRICT_ID), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong());
        verify(salonRepository, never()).findActiveByDistrictIdAsProjection(any(), any(), any(), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong());
        verify(salonRepository, never()).findActiveByCityIdNoPriceAsProjection(any(), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong());
        verify(salonRepository, never()).findByIsActiveTrueNoPriceAsProjection(any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong());
        // Must NOT touch the full-entity variants — they hydrate unnecessary columns.
        verify(salonRepository, never()).findActiveByDistrictId(any(), any());
        verify(salonRepository, never()).findActiveByCityId(any(), any());
        verify(salonRepository, never()).findByIsActiveTrue(any());
    }

    @Test
    @DisplayName("salon search dispatches to the no-price city variant when only a city is resolved and no price bounds are supplied (HIGH PERF gate)")
    void should_dispatchToCityRepoMethod_when_onlyCityResolved() {
        List<SalonSearchProjection> stubRows = oneSalonProjectionList();
        when(salonRepository.findActiveByCityIdNoPriceAsProjection(
                eq(CITY_ID), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong()))
                .thenReturn(stubRows);

        service.searchSalons(salonRequest(CITY_ID, null), PageRequest.of(0, 20));

        verify(salonRepository, times(1)).findActiveByCityIdNoPriceAsProjection(
                eq(CITY_ID), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong());
        verify(salonRepository, never()).findActiveByCityIdAsProjection(any(), any(), any(), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong());
        verify(salonRepository, never()).findActiveByDistrictIdNoPriceAsProjection(any(), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong());
        verify(salonRepository, never()).findByIsActiveTrueNoPriceAsProjection(any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong());
        verify(salonRepository, never()).findActiveByDistrictId(any(), any());
        verify(salonRepository, never()).findActiveByCityId(any(), any());
        verify(salonRepository, never()).findByIsActiveTrue(any());
    }

    @Test
    @DisplayName("salon search dispatches to the no-price active-only variant when no locality filter and no price bounds are supplied (HIGH PERF gate)")
    void should_dispatchToActiveOnlyRepoMethod_when_noLocalityFilter() {
        List<SalonSearchProjection> stubRows = oneSalonProjectionList();
        when(salonRepository.findByIsActiveTrueNoPriceAsProjection(any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong()))
                .thenReturn(stubRows);

        service.searchSalons(salonRequest(null, null), PageRequest.of(0, 20));

        verify(salonRepository, times(1)).findByIsActiveTrueNoPriceAsProjection(any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong());
        verify(salonRepository, never()).findByIsActiveTrueAsProjection(any(), any(), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong());
        verify(salonRepository, never()).findActiveByDistrictIdNoPriceAsProjection(any(), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong());
        verify(salonRepository, never()).findActiveByCityIdNoPriceAsProjection(any(), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong());
        verify(salonRepository, never()).findActiveByDistrictId(any(), any());
        verify(salonRepository, never()).findActiveByCityId(any(), any());
        verify(salonRepository, never()).findByIsActiveTrue(any());
    }

    @Test
    @DisplayName("salon search with a price bound keeps the price-lateral variant (no-price gate applies only when both bounds are null)")
    void should_dispatchToPriceVariant_when_priceBoundSupplied() {
        List<SalonSearchProjection> stubRows = oneSalonProjectionList();
        when(salonRepository.findActiveByCityIdAsProjection(
                eq(CITY_ID), any(), any(), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong()))
                .thenReturn(stubRows);
        SalonSearchRequest request = new SalonSearchRequest(
                new LocationFilter(CITY_ID, null), null, null, null,
                new BigDecimal("100.00"), null, 0, 20, null);

        service.searchSalons(request, PageRequest.of(0, 20));

        verify(salonRepository, times(1)).findActiveByCityIdAsProjection(
                eq(CITY_ID), any(), any(), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong());
        verify(salonRepository, never()).findActiveByCityIdNoPriceAsProjection(any(), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("salon search maps projection fields to SalonSearchResult correctly, stamping resolved labels (LOW PERF fix verification)")
    void should_mapProjectionToSalonSearchResult_with_resolvedLabels() {
        UUID salonId = UUID.randomUUID();
        SalonSearchProjection proj = stubProjection(salonId, "Glow Studio", CITY_ID, DISTRICT_ID);
        when(proj.getAvatarUrl()).thenReturn("https://cdn.example.com/avatar.jpg");

        when(salonRepository.findActiveByCityIdNoPriceAsProjection(
                eq(CITY_ID), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong()))
                .thenReturn(List.of(proj));
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
                eq(CITY_ID), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong()))
                .thenReturn(List.of(a, b, c));

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
                null, 0, 20, null
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
                null, null, "manicure", null, null, null, null, 0, 20, null);

        service.searchMasters(request, PageRequest.of(0, 20));

        verify(dataQuery).setParameter("category", "MANICURE");
        // countQuery removed — PERF-M1: single query with COUNT(*) OVER(), no separate count query.
    }

    @Test
    @DisplayName("normalises BigDecimal minRating to scale 2 before binding")
    void should_convertMinRatingToScaleTwo_before_binding() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, null, null, null, null, null, new BigDecimal("4.5"), 0, 20, null);

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
                null, 0, 20, null);

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

    // ── price band-overlap EXISTS (scoped) — generated-SQL guards ────────────

    @Test
    @DisplayName("price filter emits a correlated EXISTS with HAVING (ceiling >= :minPrice, floor <= :maxPrice); the min bound is NEVER a bare column predicate, but the max bound ALSO emits a coarse index-friendly m.min_effective_price <= :maxPrice pre-filter")
    void should_emitScopedPriceBandExists_when_priceBoundsSet() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, null, null, null,
                new BigDecimal("200.00"), new BigDecimal("500.00"), null, 0, 20, null);

        service.searchMasters(request, PageRequest.of(0, 20));

        String sql = sqlCaptor.getAllValues().get(0);
        assertThat(sql)
                .as("price band-overlap is a correlated EXISTS over the master's active services")
                .contains("AND EXISTS (")
                .contains("WHERE ms.master_id = m.id AND ms.is_active = true HAVING ")
                // min bound drives off the CEILING (RANGE-aware MAX), not the floor —
                // this is the inverted-min defect the fix corrects.
                .contains("MAX(COALESCE(ms.price_override, "
                        + "CASE WHEN sd.price_type = 'RANGE' THEN sd.price_max ELSE sd.base_price END)) >= :minPrice")
                // max bound drives off the FLOOR.
                .contains("MIN(COALESCE(ms.price_override, sd.base_price)) <= :maxPrice");
        assertThat(sql)
                .as("the inverted-min defect must stay gone — the min bound is ONLY the scoped EXISTS ceiling, never a bare whole-catalogue column predicate")
                .doesNotContain("m.min_effective_price >= :minPrice")
                .doesNotContain("CAST(:");
        assertThat(sql)
                .as("the max bound ALSO emits a coarse, index-friendly pre-filter on the ordering "
                        + "column (logically redundant with the EXISTS, range-bounds the PRICE_* sort index)")
                .contains("AND m.min_effective_price <= :maxPrice");
        verify(dataQuery).setParameter("minPrice", new BigDecimal("200.00"));
        verify(dataQuery).setParameter("maxPrice", new BigDecimal("500.00"));
    }

    @Test
    @DisplayName("with a category filter the price EXISTS is scoped to sd.category = :category (band agrees with the displayed scoped card)")
    void should_scopePriceBandExistsToCategory_when_categoryAndPriceSet() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, null, "MANICURE", null,
                new BigDecimal("100.00"), new BigDecimal("300.00"), null, 0, 20, null);

        service.searchMasters(request, PageRequest.of(0, 20));

        String sql = sqlCaptor.getAllValues().get(0);
        // The trailing "HAVING" disambiguates the price-band EXISTS from the
        // standalone category-filter EXISTS (which ends "= :category) ").
        assertThat(sql)
                .as("the price-band EXISTS narrows to the searched category before aggregating the band")
                .contains("AND sd.category = :category HAVING ")
                .doesNotContain("CAST(:");
        verify(dataQuery).setParameter("category", "MANICURE");
        verify(dataQuery).setParameter("minPrice", new BigDecimal("100.00"));
        verify(dataQuery).setParameter("maxPrice", new BigDecimal("300.00"));
    }

    @Test
    @DisplayName("no price EXISTS / HAVING is emitted when neither price bound is supplied")
    void should_omitPriceBandExists_when_noPriceBounds() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        String sql = sqlCaptor.getAllValues().get(0);
        assertThat(sql)
                .as("the price band predicate must not appear without a price bound")
                .doesNotContain("HAVING ");
        verify(dataQuery, never()).setParameter(eq("minPrice"), any());
        verify(dataQuery, never()).setParameter(eq("maxPrice"), any());
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
                new MasterSearchRequest(null, null, "MANICURE", null, null, null, null, 0, 20, null),
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

    // ── Phase 20.2 — salon per-service-filter SQL shape guard ────────────────

    @Test
    @DisplayName("salon per-service-filter SQL binds :stId0 as a typed param and uses no CAST(: workaround (mirrors the master guard)")
    void should_notUseCastWorkaround_inSalonServiceFilterSql() {
        // A non-empty serviceTypeSlugs routes searchSalons into the dynamically-
        // assembled native query (buildSalonSearchSql via EntityManager). Stub the
        // resolver so the slug resolves to a typed (id, nameUk) match.
        stubNativeQueries(List.of(), 0L);
        UUID typeId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(serviceTypeSlugResolver.resolve(List.of("nail-service-manicure")))
                .thenReturn(List.of(Optional.of(new ServiceTypeMatch(typeId, "Манікюр"))));
        SalonSearchRequest request = new SalonSearchRequest(
                null, null, null, null, null, null, 0, 20, List.of("nail-service-manicure"));

        service.searchSalons(request, PageRequest.of(0, 20));

        String salonSql = sqlCaptor.getAllValues().get(0);
        assertThat(salonSql)
                .as("the salon per-service EXISTS binds a typed UUID — no CAST(:p …) idiom")
                .doesNotContain("CAST(:")
                .contains(":stId0")
                .doesNotContain(":stName0");
        // The FK target binds as a plain UUID object (no CAST), mirroring the master path.
        verify(dataQuery).setParameter("stId0", typeId);
    }

    // ── free-text search by CATEGORY display name ────────────────────────────
    //
    // «Нарощення вій» is a platform_categories.display_name and appears in NO
    // service_definitions.name and NO service_types.name_uk, so before this feature
    // the q predicate — which searched provider names + service names only — returned
    // zero rows for it. These guards pin the two halves of the fix: the resolved
    // category set becomes a bound `category IN (…)` disjunct, and a query that
    // resolves to NOTHING leaves the SQL byte-identical to its pre-feature form.

    /** «Нарощення вій» — the LASH_EXTENSIONS label from the V74 seed. */
    private static final PlatformCategoryLabel LASH_EXTENSIONS =
            new PlatformCategoryLabel("LASH_EXTENSIONS", "Нарощення вій");

    @Test
    @DisplayName("q matching a category display name ORs sd.category IN (:qcat0) into the master q predicate and binds the CANONICAL name (not the typed text)")
    void should_emitCategoryDisjunct_when_qMatchesCategoryDisplayName() {
        stubNativeQueries(List.of(), 0L);
        when(platformCategoryLabelResolver.selectableLabels()).thenReturn(List.of(LASH_EXTENSIONS));
        MasterSearchRequest request = new MasterSearchRequest(
                null, "Нарощення вій", null, null, null, null, null, 0, 20, null);

        service.searchMasters(request, PageRequest.of(0, 20));

        String sql = sqlCaptor.getAllValues().get(0);
        assertThat(sql)
                .as("the index-servable pre-filter widens to name-or-category")
                .contains("AND (sd.name ILIKE :q0 OR sd.category IN (:qcat0))")
                .contains("AND (sd.name ILIKE :q1 OR sd.category IN (:qcat0))");
        assertThat(sql)
                .as("the exact group predicate widens the SAME way, so the two cannot diverge")
                .contains("OR sdg.name ILIKE :q0 OR sdg.category IN (:qcat0)")
                .contains("OR sdg.name ILIKE :q1 OR sdg.category IN (:qcat0)");
        assertThat(sql)
                .as("the category set is bound, never interpolated")
                .doesNotContain("LASH_EXTENSIONS")
                .doesNotContain("CAST(:");
        // The BOUND value is the canonical wire name, not the Ukrainian text the user
        // typed — that text only ever selected which category rows matched.
        verify(dataQuery).setParameter("qcat0", "LASH_EXTENSIONS");
    }

    @Test
    @DisplayName("the category disjunct sits INSIDE the active-assignment EXISTS — a category match cannot bypass the active-master gate")
    void should_keepCategoryDisjunctInsideActiveAssignmentExists_when_qMatchesCategory() {
        stubNativeQueries(List.of(), 0L);
        when(platformCategoryLabelResolver.selectableLabels()).thenReturn(List.of(LASH_EXTENSIONS));
        MasterSearchRequest request = new MasterSearchRequest(
                null, "Нарощення вій", null, null, null, null, null, 0, 20, null);

        service.searchMasters(request, PageRequest.of(0, 20));

        String sql = sqlCaptor.getAllValues().get(0);
        // The disjunct must never be lifted to the master level: it has to stay behind
        // the active master_services / active service_definitions / owner gate, or a
        // master would become discoverable by a category they no longer actually offer.
        assertThat(sql)
                .as("pre-filter: the category disjunct is downstream of the active-assignment join and the owner gate")
                .contains("JOIN service_definitions sd ON sd.id = ms.service_def_id AND sd.is_active = true "
                        + "WHERE ms.master_id = m.id AND ms.is_active = true "
                        + "AND sd.owner_type = 'INDEPENDENT_MASTER' AND sd.owner_id = ms.master_id "
                        + "AND (sd.name ILIKE :q0 OR sd.category IN (:qcat0))");
        assertThat(sql)
                .as("no bare master-level category predicate escaped the EXISTS")
                .doesNotContain("AND m.id IN (:qcat0)");
    }

    @Test
    @DisplayName("q that matches NO category leaves the generated SQL free of any :qcat slot (hot path unchanged, plan protected)")
    void should_omitCategoryDisjunct_when_qMatchesNoCategoryDisplayName() {
        stubNativeQueries(List.of(), 0L);
        when(platformCategoryLabelResolver.selectableLabels()).thenReturn(List.of(LASH_EXTENSIONS));
        MasterSearchRequest request = new MasterSearchRequest(
                null, "кова", null, null, null, null, null, 0, 20, null);

        service.searchMasters(request, PageRequest.of(0, 20));

        String sql = sqlCaptor.getAllValues().get(0);
        assertThat(sql)
                .as("no category matched, so the disjunct collapses away entirely")
                .doesNotContain(":qcat")
                .doesNotContain(".category IN (");
        assertThat(sql)
                .as("the pre-filter keeps its original un-parenthesised shape — the same SQL "
                        + "string, hence the same server-side plan-cache entry")
                .contains("AND sd.name ILIKE :q0))");
        verify(dataQuery, never()).setParameter(eq("qcat0"), any());
    }

    @Test
    @DisplayName("only categories matching ALL tokens are bound — a label carrying just one token is excluded (group semantics preserved)")
    void should_bindOnlyAllTokenCategories_when_qHasMultipleTokens() {
        stubNativeQueries(List.of(), 0L);
        when(platformCategoryLabelResolver.selectableLabels()).thenReturn(List.of(
                LASH_EXTENSIONS,
                // Shares «вій» but not «нарощення» — must NOT be bound, or the query
                // would silently widen to a category the user did not ask for.
                new PlatformCategoryLabel("LASH_LAMINATION", "Ламінування вій"),
                // Shares «нарощ…» only as a different word stem — no token containment.
                new PlatformCategoryLabel("HAIR_EXTENSIONS", "Нарощування волосся")));
        MasterSearchRequest request = new MasterSearchRequest(
                null, "нарощення вій", null, null, null, null, null, 0, 20, null);

        service.searchMasters(request, PageRequest.of(0, 20));

        verify(dataQuery).setParameter("qcat0", "LASH_EXTENSIONS");
        verify(dataQuery, never()).setParameter(eq("qcat1"), any());
        assertThat(sqlCaptor.getAllValues().get(0))
                .as("exactly one category slot is emitted")
                .contains("sd.category IN (:qcat0)")
                .doesNotContain(":qcat1");
    }

    @Test
    @DisplayName("the matched_names lateral is widened by the category disjunct so a category match explains itself instead of falling back to an arbitrary catalogue slice")
    void should_widenMatchedNamesLateral_when_qMatchesCategoryDisplayName() {
        stubNativeQueries(List.of(), 0L);
        when(platformCategoryLabelResolver.selectableLabels()).thenReturn(List.of(LASH_EXTENSIONS));
        MasterSearchRequest request = new MasterSearchRequest(
                null, "Нарощення вій", null, null, null, null, null, 0, 20, null);

        service.searchMasters(request, PageRequest.of(0, 20));

        String sql = sqlCaptor.getAllValues().get(0);
        // Condition (2) of the lateral — "contributes at least one token through its own
        // name" — would reject EVERY service on a category-name query (no service name
        // contains any token), leaving matchedServiceNames empty.
        assertThat(sql)
                .as("belonging to a matched category counts as an explanation")
                .contains("AND (sd.name ILIKE :q0 OR sd.name ILIKE :q1 OR sd.category IN (:qcat0))");
    }

    @Test
    @DisplayName("a salon q that resolves to a category is routed to the DYNAMIC builder — the static repository projections cannot express a variable-length IN list")
    void should_routeSalonSearchToDynamicBuilder_when_qMatchesCategoryDisplayName() {
        stubNativeQueries(List.of(), 0L);
        when(platformCategoryLabelResolver.selectableLabels()).thenReturn(List.of(LASH_EXTENSIONS));
        SalonSearchRequest request = new SalonSearchRequest(
                null, "Нарощення вій", null, null, null, null, 0, 20, null);

        service.searchSalons(request, PageRequest.of(0, 20));

        // If this dispatch is ever narrowed back to "slugs only", salon category search
        // silently returns zero rows again — hence an explicit no-interaction assertion
        // on the static path rather than only a positive one on the dynamic path.
        verifyNoInteractions(salonRepository);
        String salonSql = sqlCaptor.getAllValues().get(0);
        assertThat(salonSql)
                .as("the bookable-master gate still guards the category disjunct — a salon's "
                        + "client-visible offering stays master-performed only")
                .contains("JOIN masters mmq ON mmq.id = msq.master_id AND mmq.is_active = true")
                .contains("sdq.name ILIKE :q0 OR sdq.category IN (:qcat0)")
                .doesNotContain("CAST(:");
        verify(dataQuery).setParameter("qcat0", "LASH_EXTENSIONS");
    }

    @Test
    @DisplayName("a salon q that matches NO category keeps using the tuned static repository projection (no dynamic-builder regression for ordinary traffic)")
    void should_keepSalonStaticProjection_when_qMatchesNoCategory() {
        when(platformCategoryLabelResolver.selectableLabels()).thenReturn(List.of(LASH_EXTENSIONS));
        SalonSearchRequest request = new SalonSearchRequest(
                null, "кова", null, null, null, null, 0, 20, null);

        service.searchSalons(request, PageRequest.of(0, 20));

        verify(entityManager, never()).createNativeQuery(anyString());
        verify(salonRepository).findByIsActiveTrueNoPriceAsProjection(
                any(), any(), any(), any(), isNull(), anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("ORDER BY includes m.id as the deterministic tie-breaker")
    void should_orderByRatingThenId_when_dataQueryIssued() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        String dataSql = sqlCaptor.getAllValues().get(0);
        assertThat(dataSql).contains("ORDER BY m.avg_rating DESC NULLS LAST, m.id");
    }

    // ── master-side owner gate — owner_type AND owner_id on EVERY service surface ──
    //
    // appendMasterOwnedServiceGate is the SOLE emitter of the
    // `owner_type = 'INDEPENDENT_MASTER' AND owner_id = <master>` pair, reached from
    // four surfaces (q pre-filter, exact group predicate, serviceNames lateral,
    // matched-names lateral). A perf fix once silently dropped the owner_id half on
    // the single-token path and was caught only by manual review — before these
    // tests, deleting `AND sd.owner_id = ms.master_id` left the whole suite green.
    //
    // The load-bearing assertion is the PAIRING one: every owner_type gate must be
    // accompanied by an owner_id gate. That is what fails when either half is
    // dropped from any single surface, without hard-coding a brittle surface count.

    @Test
    @DisplayName("every owner_type gate is paired with an owner_id gate — SINGLE-token q (the path where the pre-filter is the ONLY owner enforcement)")
    void should_pairOwnerTypeWithOwnerId_onEveryServiceSurface_when_singleTokenQ() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, "кератин", null, null, null, null, null, 0, 20, null);

        service.searchMasters(request, PageRequest.of(0, 20));

        String sql = sqlCaptor.getAllValues().get(0);
        assertThat(countOccurrences(sql, ".owner_id = "))
                .as("owner_type without owner_id is a HALF gate: an active cross-owner "
                        + "master_services row would then make a master discoverable by another "
                        + "owner's service name. Every gate must carry BOTH halves.")
                .isEqualTo(countOccurrences(sql, ".owner_type = 'INDEPENDENT_MASTER'"))
                .isPositive();
        assertThat(sql)
                .as("the pre-filter spells the owner as the sub-query's own ms.master_id (inner-only "
                        + "equality), and both post-LIMIT laterals as the derived table's t.master_id")
                .contains("sd.owner_type = 'INDEPENDENT_MASTER' AND sd.owner_id = ms.master_id")
                .contains("sd.owner_type = 'INDEPENDENT_MASTER' AND sd.owner_id = t.master_id");
    }

    @Test
    @DisplayName("every owner_type gate is paired with an owner_id gate — TWO-token q (adds the exact group predicate's sdg surface)")
    void should_pairOwnerTypeWithOwnerId_onEveryServiceSurface_when_twoTokenQ() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, "ботокс волосся", null, null, null, null, null, 0, 20, null);

        service.searchMasters(request, PageRequest.of(0, 20));

        String sql = sqlCaptor.getAllValues().get(0);
        assertThat(countOccurrences(sql, ".owner_id = "))
                .isEqualTo(countOccurrences(sql, ".owner_type = 'INDEPENDENT_MASTER'"))
                .isPositive();
        assertThat(sql)
                .as("the exact group predicate (aliases msg/sdg) is emitted for >1 token and "
                        + "carries the gate spelled against the outer m.id")
                .contains("sdg.owner_type = 'INDEPENDENT_MASTER' AND sdg.owner_id = m.id");
    }

    @Test
    @DisplayName("the exact group-scoped predicate is emitted for TWO tokens and short-circuited away for ONE (the pre-filter is identical to it there)")
    void should_emitGroupScopedPredicate_onlyWhenMoreThanOneToken() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(
                new MasterSearchRequest(null, "кератин", null, null, null, null, null, 0, 20, null),
                PageRequest.of(0, 20));
        service.searchMasters(
                new MasterSearchRequest(null, "ботокс волосся", null, null, null, null, null, 0, 20, null),
                PageRequest.of(0, 20));

        assertThat(sqlCaptor.getAllValues().get(0))
                .as("single token: the pre-filter IS the group predicate (proved identity), so "
                        + "emitting both would evaluate the same condition twice")
                .doesNotContain("master_services msg");
        assertThat(sqlCaptor.getAllValues().get(1))
                .as("two tokens: the group predicate is required — it is what stops tokens being "
                        + "satisfied by two DIFFERENT services of the same master")
                .contains("master_services msg")
                .contains("service_definitions sdg");
    }

    @Test
    @DisplayName("the matched-names lateral is emitted for a free-text q even with NO serviceTypeSlugs filter")
    void should_emitMatchedNamesLateral_when_qSuppliedWithoutSlugFilter() {
        stubNativeQueries(List.of(), 0L);

        service.searchMasters(
                new MasterSearchRequest(null, "кератин", null, null, null, null, null, 0, 20, null),
                PageRequest.of(0, 20));

        assertThat(sqlCaptor.getAllValues().get(0))
                .as("matched_names now explains a free-text match too, not only a slug filter — so "
                        + "it must come from the mn lateral, never the typed-NULL placeholder")
                .contains("mn.matched_names")
                .doesNotContain("CAST(NULL AS text[])");
    }

    // ── short `q` — the zero-round-trip half of the contract ──────────────────
    //
    // The HTTP 200 + «Введіть щонайменше 3 символи» half is pinned in the
    // controller/IT suites. That the service issues NO statement at all is
    // structurally true (an early return before any query) but was asserted nowhere.

    @Test
    @DisplayName("a below-minimum-length q on /search/masters issues NO database statement at all")
    void should_notTouchDatabase_when_masterQBelowMinimumLength() {
        // Deliberately NOT calling stubNativeQueries: any createNativeQuery call would
        // return null and NPE, so this test fails loudly rather than silently if the
        // early return is ever removed.
        MasterSearchRequest request = new MasterSearchRequest(
                null, "Ру", null, null, null, null, null, 0, 20, null);

        Page<MasterSearchResult> result = service.searchMasters(request, PageRequest.of(0, 20));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements())
                .as("an explicit empty page, never the unfiltered set (defect B)")
                .isZero();
        verify(entityManager, never()).createNativeQuery(anyString());
        verifyNoInteractions(salonRepository);
    }

    @Test
    @DisplayName("a below-minimum-length q on /search/salons issues NO database statement and never reaches the repository")
    void should_notTouchDatabase_when_salonQBelowMinimumLength() {
        SalonSearchRequest request = new SalonSearchRequest(
                null, "Ру", null, null, null, null, 0, 20, null);

        Page<SalonSearchResult> result = service.searchSalons(request, PageRequest.of(0, 20));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
        verify(entityManager, never()).createNativeQuery(anyString());
        verifyNoInteractions(salonRepository);
    }

    // ── out-of-range page probe — statement COUNT, not just the recovered total ──
    //
    // The masters and static-salon probe paths have integration coverage of the
    // recovered total. searchSalonsWithServiceFilter had none, and nothing anywhere
    // asserted the probe costs exactly TWO statements rather than three or more.

    @Test
    @DisplayName("an out-of-range page on the per-service-FILTERED salon path recovers the true total in exactly TWO statements")
    void should_recoverTotalInTwoStatements_when_serviceFilteredSalonPageOutOfRange() {
        UUID typeId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(serviceTypeSlugResolver.resolve(List.of("nail-service-manicure")))
                .thenReturn(List.of(Optional.of(new ServiceTypeMatch(typeId, "Манікюр"))));

        // Salon dynamic projection: SALON_TOTAL_COUNT_IDX == 12.
        Object[] probeRow = new Object[13];
        probeRow[0] = UUID.randomUUID();
        probeRow[1] = "Aura";
        probeRow[12] = 137L;

        when(entityManager.createNativeQuery(sqlCaptor.capture())).thenReturn(dataQuery);
        lenient().when(dataQuery.setParameter(anyString(), any())).thenReturn(dataQuery);
        // First statement = the out-of-range data page (empty); second = the first-page probe.
        when(dataQuery.getResultList())
                .thenReturn(List.of())
                .thenReturn(List.<Object[]>of(probeRow));

        SalonSearchRequest request = new SalonSearchRequest(
                null, null, null, null, null, null, 9, 20, List.of("nail-service-manicure"));

        Page<SalonSearchResult> result = service.searchSalons(request, PageRequest.of(9, 20));

        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements())
                .as("the window count has no row to ride on when the page is empty, so the total is "
                        + "recovered from a page-0/size-1 probe — a client on a restored deep link "
                        + "must be told 'no results ON THIS PAGE', not 'no results'")
                .isEqualTo(137L);
        verify(entityManager, times(2))
                .createNativeQuery(anyString());
        assertThat(sqlCaptor.getAllValues().get(1))
                .as("the probe re-runs the SAME builder at page 0 size 1 — never a second "
                        + "hand-maintained countQuery copy of every predicate")
                .contains("LIMIT :limit OFFSET :offset");
        verify(dataQuery).setParameter("limit", 1);
        verify(dataQuery).setParameter("offset", 0L);
    }

    @Test
    @DisplayName("a genuinely empty FIRST page costs exactly ONE statement — the probe is skipped, not merely reporting 0")
    void should_issueExactlyOneStatement_when_firstPageGenuinelyEmpty() {
        stubNativeQueries(List.of(), 0L);

        Page<MasterSearchResult> result = service.searchMasters(emptyRequest(), PageRequest.of(0, 20));

        assertThat(result.getTotalElements()).isZero();
        // The pre-existing "genuinely empty first page" coverage asserted only total==0,
        // which passed before the offset guard existed too. The statement count is what
        // actually discriminates: without `pageable.getOffset() == 0` the common no-match
        // request would silently cost a second probe statement.
        verify(entityManager, times(1)).createNativeQuery(anyString());
    }

    /** Counts non-overlapping occurrences of {@code needle} in {@code haystack}. */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = haystack.indexOf(needle);
        while (idx >= 0) {
            count++;
            idx = haystack.indexOf(needle, idx + needle.length());
        }
        return count;
    }

    // ── name / service-name search (q) ───────────────────────────────────────

    @Test
    @DisplayName("adds a case-insensitive ILIKE over first/last name plus a service-name EXISTS when q is supplied (each index-served, no join fan-out)")
    void should_addIlikePredicate_when_qProvided() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, "olena", null, null, null, null, null, 0, 20, null);

        service.searchMasters(request, PageRequest.of(0, 20));

        String dataSql = sqlCaptor.getAllValues().get(0);
        // Name predicates hit u.first_name/u.last_name directly; the service-name
        // match is a correlated EXISTS on sd.name (not a main-query join), so each
        // ILIKE is served by its own trigram index with no fan-out / GROUP BY.
        // Tokenised: one ANDed group per whitespace token, each bound to :qN.
        assertThat(dataSql)
                .contains("u.first_name ILIKE :q0 OR u.last_name ILIKE :q0 OR EXISTS (")
                .contains("sd.name ILIKE :q0")
                .doesNotContain("GROUP BY");
    }

    @Test
    @DisplayName("binds :q0 as an escaped %term% pattern (LIKE wildcards in the term are neutralised)")
    void should_bindEscapedContainsPattern_when_qHasLikeWildcards() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, "50%_off", null, null, null, null, null, 0, 20, null);

        service.searchMasters(request, PageRequest.of(0, 20));

        verify(dataQuery).setParameter("q0", "%50\\%\\_off%");
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
                null, null, null, SearchSort.PRICE_ASC, null, null, null, 0, 20, null);

        service.searchMasters(request, PageRequest.of(0, 20));

        assertThat(sqlCaptor.getAllValues().get(0))
                .contains("ORDER BY m.min_effective_price ASC NULLS LAST, m.id");
    }

    @Test
    @DisplayName("PRICE_DESC maps to ORDER BY m.min_effective_price DESC NULLS LAST, m.id")
    void should_orderByPriceDesc_when_sortPriceDesc() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, null, null, SearchSort.PRICE_DESC, null, null, null, 0, 20, null);

        service.searchMasters(request, PageRequest.of(0, 20));

        assertThat(sqlCaptor.getAllValues().get(0))
                .contains("ORDER BY m.min_effective_price DESC NULLS LAST, m.id");
    }

    @Test
    @DisplayName("REVIEWS_DESC maps to ORDER BY m.review_count DESC NULLS LAST, m.id")
    void should_orderByReviewsDesc_when_sortReviewsDesc() {
        stubNativeQueries(List.of(), 0L);
        MasterSearchRequest request = new MasterSearchRequest(
                null, null, null, SearchSort.REVIEWS_DESC, null, null, null, 0, 20, null);

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
    @DisplayName("forwards q (escaped) into the :q0 token slot and price bounds to the salon projection query; unused token slots bind null")
    void should_forwardQAndPriceBounds_toSalonRepo() {
        List<SalonSearchProjection> stubRows = oneSalonProjectionList();
        when(salonRepository.findActiveByCityIdAsProjection(
                eq(CITY_ID), any(), any(), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong()))
                .thenReturn(stubRows);
        SalonSearchRequest request = new SalonSearchRequest(
                new LocationFilter(CITY_ID, null), "glow", null, null,
                new BigDecimal("100.00"), new BigDecimal("500.00"), 0, 20, null);

        service.searchSalons(request, PageRequest.of(0, 20));

        // Signature: (cityId, category, q0, q1, q2, q3, minPrice, maxPrice, sortMode, limit, offset).
        // A single-token query fills q0 and leaves q1..q3 null — their null-gated
        // branch short-circuits to TRUE, reproducing the historical one-term shape.
        verify(salonRepository).findActiveByCityIdAsProjection(
                eq(CITY_ID), any(),
                eq("%glow%"), isNull(), isNull(), isNull(),
                eq(new BigDecimal("100.00")),
                eq(new BigDecimal("500.00")),
                anyString(), anyInt(), anyLong());
    }

    @Test
    @DisplayName("salon PRICE_ASC binds the allow-listed SearchSort name as :sortMode (caller text never reaches ORDER BY)")
    void should_bindPriceAscSortMode_when_salonSortPriceAsc() {
        List<SalonSearchProjection> stubRows = oneSalonProjectionList();
        ArgumentCaptor<String> sortModeCaptor = ArgumentCaptor.forClass(String.class);
        // No price bounds → no-price city variant (HIGH PERF gate).
        when(salonRepository.findActiveByCityIdNoPriceAsProjection(
                eq(CITY_ID), any(), any(), any(), any(), any(),
                sortModeCaptor.capture(), anyInt(), anyLong()))
                .thenReturn(stubRows);
        SalonSearchRequest request = new SalonSearchRequest(
                new LocationFilter(CITY_ID, null), null, null, SearchSort.PRICE_ASC,
                null, null, 0, 20, null);

        service.searchSalons(request, PageRequest.of(0, 20));

        // The static queries no longer take a Pageable: Spring Data cannot express an
        // inner LIMIT, so ordering is selected by a CASE on this bound enum NAME
        // (SalonSearchSql.STATIC_ORDER_LIMIT_TAIL) and paging by :limit/:offset.
        assertThat(sortModeCaptor.getValue()).isEqualTo(SearchSort.PRICE_ASC.name());
    }

    @Test
    @DisplayName("salon search binds :limit/:offset from the Pageable so the LIMIT lands INSIDE the derived table (post-LIMIT name laterals)")
    void should_bindLimitAndOffsetFromPageable_when_salonSearchPaged() {
        List<SalonSearchProjection> stubRows = oneSalonProjectionList();
        ArgumentCaptor<Integer> limitCaptor = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Long> offsetCaptor = ArgumentCaptor.forClass(Long.class);
        when(salonRepository.findActiveByCityIdNoPriceAsProjection(
                eq(CITY_ID), any(), any(), any(), any(), any(), anyString(),
                limitCaptor.capture(), offsetCaptor.capture()))
                .thenReturn(stubRows);

        service.searchSalons(salonRequest(CITY_ID, null), PageRequest.of(2, 15));

        assertThat(limitCaptor.getValue()).isEqualTo(15);
        assertThat(offsetCaptor.getValue()).isEqualTo(30L);
    }

    @Test
    @DisplayName("salon page total comes from the COUNT(*) OVER() column, not a second countQuery statement")
    void should_readTotalFromWindowCount_when_salonSearchReturnsRows() {
        SalonSearchProjection proj = stubProjection(UUID.randomUUID(), "Glow", CITY_ID, DISTRICT_ID);
        when(proj.getTotalCount()).thenReturn(137L);
        when(salonRepository.findActiveByCityIdNoPriceAsProjection(
                eq(CITY_ID), any(), any(), any(), any(), any(), anyString(), anyInt(), anyLong()))
                .thenReturn(List.of(proj));

        Page<SalonSearchResult> page =
                service.searchSalons(salonRequest(CITY_ID, null), PageRequest.of(0, 20));

        assertThat(page.getTotalElements()).isEqualTo(137L);
        assertThat(page.getContent()).hasSize(1);
    }
}
