package com.beautica.search.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.search.dto.MasterSearchRequest;
import com.beautica.search.dto.MasterSearchResult;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SearchService}.
 *
 * <p>The {@link EntityManager}, the data {@link Query} and the count {@link Query}
 * are all mocked: tests verify parameter binding (the surface most likely to
 * regress under copy-paste edits) without booting Hibernate. End-to-end query
 * correctness lives in the repository / integration tests.
 *
 * <p>{@code SearchService} obtains its EntityManager via {@code @PersistenceContext}
 * (field injection — Spring's documented exception to constructor injection
 * because the framework supplies a transaction-aware proxy at runtime). The
 * service is therefore instantiated manually and the field is wired with
 * {@link ReflectionTestUtils} — {@code @InjectMocks} cannot target an
 * {@code @PersistenceContext} field reliably across Mockito versions.
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

    private SearchService service;

    @BeforeEach
    void setUp() {
        service = new SearchService(salonRepository);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
    }

    private void stubNativeQueries(List<Object[]> rows, long total) {
        when(entityManager.createNativeQuery(anyString()))
                .thenReturn(dataQuery, countQuery);
        when(dataQuery.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(dataQuery);
        when(dataQuery.setParameter(eq("limit"), anyInt())).thenReturn(dataQuery);
        when(dataQuery.setParameter(eq("offset"), anyLong())).thenReturn(dataQuery);
        when(dataQuery.getResultList()).thenReturn((List) rows);
        when(countQuery.setParameter(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(total);
    }

    private static MasterSearchRequest emptyRequest() {
        return new MasterSearchRequest(null, null, null, null, null, null, 0, 20);
    }

    private static MasterSearchRequest fullRequest() {
        return new MasterSearchRequest(
                "Київ",
                "Kyivska",
                "manicure",
                new BigDecimal("100.00"),
                new BigDecimal("500.00"),
                4.5,
                0,
                20
        );
    }

    @Test
    @DisplayName("binds the city query parameter when a city filter is supplied")
    void should_bindCityParam_when_cityProvided() {
        stubNativeQueries(List.of(), 0L);
        Pageable pageable = PageRequest.of(0, 20);

        service.searchMasters(
                new MasterSearchRequest("Київ", null, null, null, null, null, 0, 20),
                pageable
        );

        verify(dataQuery).setParameter("city", "Київ");
        verify(countQuery).setParameter("city", "Київ");
    }

    @Test
    @DisplayName("binds null for the city parameter when no city filter is provided")
    void should_bindNullCity_when_noCityFilter() {
        stubNativeQueries(List.of(), 0L);
        Pageable pageable = PageRequest.of(0, 20);

        service.searchMasters(emptyRequest(), pageable);

        verify(dataQuery).setParameter("city", (Object) null);
        verify(countQuery).setParameter("city", (Object) null);
    }

    @Test
    @DisplayName("binds every filter parameter when the full search request is provided")
    void should_bindAllFilterParams_when_fullSearchRequest() {
        stubNativeQueries(List.of(), 0L);
        Pageable pageable = PageRequest.of(0, 20);

        service.searchMasters(fullRequest(), pageable);

        verify(dataQuery).setParameter("city", "Київ");
        verify(dataQuery).setParameter("region", "Kyivska");
        verify(dataQuery).setParameter("category", "MANICURE");
        verify(dataQuery).setParameter(eq("minRating"), org.mockito.ArgumentMatchers.any(BigDecimal.class));
        verify(dataQuery).setParameter("minPrice", new BigDecimal("100.00"));
        verify(dataQuery).setParameter("maxPrice", new BigDecimal("500.00"));
        verify(dataQuery).setParameter("limit", 20);
        verify(dataQuery).setParameter("offset", 0L);
    }

    @Test
    @DisplayName("returns an empty page with totalElements 0 when no rows match")
    void should_returnEmptyPage_when_noResultsFound() {
        stubNativeQueries(List.of(), 0L);
        Pageable pageable = PageRequest.of(0, 20);

        Page<MasterSearchResult> result = service.searchMasters(emptyRequest(), pageable);

        assertThat(result.isEmpty()).isTrue();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("parses minEffectivePrice from the BigDecimal aggregate column on each row")
    void should_computeEffectivePriceFromRow_when_rowContainsBigDecimal() {
        UUID masterId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        Object[] row = new Object[]{
                masterId,
                userId,
                "Olena",
                "Kovalenko",
                "Київ",
                new BigDecimal("4.85"),
                42,
                "https://cdn.example/avatar.jpg",
                new BigDecimal("250.00")
        };
        stubNativeQueries(List.<Object[]>of(row), 1L);
        Pageable pageable = PageRequest.of(0, 20);

        Page<MasterSearchResult> result = service.searchMasters(emptyRequest(), pageable);

        assertThat(result.getContent()).hasSize(1);
        MasterSearchResult mapped = result.getContent().get(0);
        assertThat(mapped.minEffectivePrice()).isEqualTo(new BigDecimal("250.00"));
        assertThat(mapped.avgRating()).isEqualTo(4.85);
        assertThat(mapped.reviewCount()).isEqualTo(42);
        assertThat(mapped.masterId()).isEqualTo(masterId);
        assertThat(mapped.userId()).isEqualTo(userId);
    }

    // ── Phase 6.2 carry-over LOWs ──────────────────────────────────────────

    @Test
    @DisplayName("throws BusinessException without hitting the DB when minPrice exceeds maxPrice")
    void should_throwBusinessException_when_minPriceExceedsMaxPrice() {
        MasterSearchRequest request = new MasterSearchRequest(
                null, null, null,
                new BigDecimal("500.00"),
                new BigDecimal("100.00"),
                null, 0, 20
        );
        Pageable pageable = PageRequest.of(0, 20);

        assertThatThrownBy(() -> service.searchMasters(request, pageable))
                .isInstanceOf(BusinessException.class)
                .hasMessage("minPrice must not exceed maxPrice");

        verify(entityManager, never()).createNativeQuery(anyString());
    }

    @Test
    @DisplayName("upper-cases category so the bound value matches the EnumType.STRING storage form")
    void should_normalizeCategoryCase_before_bindingParameter() {
        stubNativeQueries(List.of(), 0L);
        Pageable pageable = PageRequest.of(0, 20);
        MasterSearchRequest request = new MasterSearchRequest(
                null, null, "manicure", null, null, null, 0, 20
        );

        service.searchMasters(request, pageable);

        verify(dataQuery).setParameter("category", "MANICURE");
        verify(countQuery).setParameter("category", "MANICURE");
    }

    @Test
    @DisplayName("converts Double minRating to BigDecimal with scale 2 before binding (avoids float drift on NUMERIC(3,2))")
    void should_convertDoubleMinRatingToBigDecimal_before_binding() {
        stubNativeQueries(List.of(), 0L);
        Pageable pageable = PageRequest.of(0, 20);
        MasterSearchRequest request = new MasterSearchRequest(
                null, null, null, null, null, 4.5, 0, 20
        );

        service.searchMasters(request, pageable);

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(dataQuery, times(1)).setParameter(eq("minRating"), captor.capture());
        Object bound = captor.getValue();
        assertThat(bound).isInstanceOf(BigDecimal.class);
        BigDecimal scaled = (BigDecimal) bound;
        assertThat(scaled.scale()).isEqualTo(2);
        assertThat(scaled).isEqualTo(BigDecimal.valueOf(4.5).setScale(2, RoundingMode.HALF_UP));
    }

    // ── Phase 6.3 active-flag filtering ────────────────────────────────────

    /**
     * Smoke test guarding against accidental removal of the active-flag predicates
     * from the master-search SQL constants. Both the data and count SQL must filter
     * out deactivated masters and disabled user accounts so a public search cannot
     * leak hidden profiles. Behavioural coverage (real Postgres) lives in the
     * Phase 6.4 controller integration test.
     */
    @Test
    @DisplayName("master-search SQL constants filter on m.is_active = true and u.is_active = true")
    void should_bindIsActiveTrue_implicitly_via_sql_when_searchMasters() {
        String dataSql = (String) ReflectionTestUtils.getField(SearchService.class, "MASTER_SEARCH_SQL");
        String countSql = (String) ReflectionTestUtils.getField(SearchService.class, "MASTER_SEARCH_COUNT_SQL");

        assertThat(dataSql)
                .as("data SQL must filter inactive masters and inactive users")
                .contains("m.is_active = true")
                .contains("u.is_active = true");
        assertThat(countSql)
                .as("count SQL must filter inactive masters and inactive users")
                .contains("m.is_active = true")
                .contains("u.is_active = true");
    }
}
