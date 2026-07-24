package com.beautica.location.service;

import com.beautica.location.dto.CityDistrictResponse;
import com.beautica.location.dto.CityResponse;
import com.beautica.location.dto.OblastResponse;
import com.beautica.location.entity.City;
import com.beautica.location.entity.CityDistrict;
import com.beautica.location.entity.Oblast;
import com.beautica.location.repository.CityDistrictRepository;
import com.beautica.location.repository.CityRepository;
import com.beautica.location.repository.OblastRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link LocationQueryService} — the read-only KATOTTH locality
 * query service backing the (future) cascading picker.
 *
 * <p>Pure Mockito: the service has no Spring-managed behaviour worth a context
 * here (caching is pinned separately in {@link LocationQueryServiceCacheTest}
 * — Q7/Q19, behaviour not annotation-count). This class proves the three
 * contracts the phase-doc acceptance criteria depend on:
 *
 * <ul>
 *   <li><strong>Ordering</strong> — the {@code …OrderByNameUkAsc} finders are
 *       the delegated source of truth; the service preserves their order.</li>
 *   <li><strong>{@code hasDistricts} correctness</strong> — true for a city
 *       whose id is in the set-based result, false otherwise.</li>
 *   <li><strong>No N+1 (§E)</strong> — {@code hasDistricts} is computed from a
 *       single {@code findCityIdsWithDistrictsByOblastId} call;
 *       {@code existsByCityId} is <em>never</em> invoked per row.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LocationQueryService — KATOTTH read-only query unit")
class LocationQueryServiceTest {

    @Mock
    private OblastRepository oblastRepository;

    @Mock
    private CityRepository cityRepository;

    @Mock
    private CityDistrictRepository cityDistrictRepository;

    @InjectMocks
    private LocationQueryService service;

    // ── fixtures ──────────────────────────────────────────────────────────────

    private UUID kyivOblastId;
    private Oblast kyivOblast;
    private Oblast lvivOblast;

    @BeforeEach
    void setUp() {
        kyivOblastId = UUID.randomUUID();
        kyivOblast = Oblast.builder()
                .id(kyivOblastId)
                .katotthCode("UA80000000000093317")
                .nameUk("Київ")
                .nameEn("Kyiv")
                .build();
        lvivOblast = Oblast.builder()
                .id(UUID.randomUUID())
                .katotthCode("UA46000000000026241")
                .nameUk("Львівська")
                .nameEn("Lviv Oblast")
                .build();
    }

    private City city(UUID id, String katotth, String uk, String en) {
        return City.builder()
                .id(id)
                .oblast(kyivOblast)
                .katotthCode(katotth)
                .nameUk(uk)
                .nameEn(en)
                .build();
    }

    private CityDistrict district(UUID id, String katotth, String uk, String en) {
        return CityDistrict.builder()
                .id(id)
                .city(city(UUID.randomUUID(), "UA80", "Київ", "Kyiv"))
                .katotthCode(katotth)
                .nameUk(uk)
                .nameEn(en)
                .build();
    }

    // ── listOblasts ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("listOblasts — maps every field and preserves repository name_uk ordering")
    void should_mapAllFieldsAndPreserveOrder_when_listOblastsCalled() {
        when(oblastRepository.findAllByOrderByNameUkAsc())
                .thenReturn(List.of(kyivOblast, lvivOblast));

        List<OblastResponse> result = service.listOblasts();

        assertThat(result)
                .as("order must mirror findAllByOrderByNameUkAsc — service adds no re-sort")
                .extracting(OblastResponse::nameUk)
                .containsExactly("Київ", "Львівська");
        assertThat(result.get(0))
                .extracting(
                        OblastResponse::id,
                        OblastResponse::katotthCode,
                        OblastResponse::nameUk,
                        OblastResponse::nameEn)
                .containsExactly(kyivOblastId, "UA80000000000093317", "Київ", "Kyiv");
    }

    @Test
    @DisplayName("listOblasts — returns an empty list when no oblasts are seeded")
    void should_returnEmptyList_when_noOblastsExist() {
        when(oblastRepository.findAllByOrderByNameUkAsc()).thenReturn(List.of());

        assertThat(service.listOblasts()).isEmpty();
        verifyNoInteractions(cityRepository, cityDistrictRepository);
    }

    // ── listCitiesByOblast — hasDistricts correctness + no N+1 ─────────────────

    @Test
    @DisplayName("listCitiesByOblast — hasDistricts true for a city in the district set, false otherwise")
    void should_setHasDistrictsCorrectly_when_someCitiesHaveDistricts() {
        UUID kyivCityId = UUID.randomUUID();   // has districts (e.g. Kyiv)
        UUID brovaryCityId = UUID.randomUUID(); // no districts
        City kyivCity = city(kyivCityId, "UA80000000000093317", "Київ", "Kyiv");
        City brovaryCity = city(brovaryCityId, "UA32060030000044729", "Бровари", "Brovary");

        when(cityDistrictRepository.findCityIdsWithDistrictsByOblastId(kyivOblastId))
                .thenReturn(Set.of(kyivCityId));
        when(cityRepository.findByOblastIdOrderByNameUkAsc(kyivOblastId))
                .thenReturn(List.of(brovaryCity, kyivCity));

        List<CityResponse> result = service.listCitiesByOblast(kyivOblastId);

        assertThat(result)
                .as("order mirrors findByOblastIdOrderByNameUkAsc; flag set from the set")
                .extracting(CityResponse::nameUk, CityResponse::hasDistricts)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Бровари", false),
                        org.assertj.core.groups.Tuple.tuple("Київ", true));
    }

    @Test
    @DisplayName("listCitiesByOblast — oblastId comes from the path arg, not the LAZY City#oblast graph")
    void should_useParentIdFromArgument_when_buildingCityResponse() {
        UUID cityId = UUID.randomUUID();
        when(cityDistrictRepository.findCityIdsWithDistrictsByOblastId(kyivOblastId))
                .thenReturn(Set.of());
        when(cityRepository.findByOblastIdOrderByNameUkAsc(kyivOblastId))
                .thenReturn(List.of(city(cityId, "UA80", "Київ", "Kyiv")));

        CityResponse dto = service.listCitiesByOblast(kyivOblastId).get(0);

        assertThat(dto)
                .extracting(
                        CityResponse::id,
                        CityResponse::oblastId,
                        CityResponse::katotthCode,
                        CityResponse::nameUk,
                        CityResponse::nameEn,
                        CityResponse::hasDistricts)
                .containsExactly(cityId, kyivOblastId, "UA80", "Київ", "Kyiv", false);
    }

    @Test
    @DisplayName("listCitiesByOblast — uses the set-based query exactly once and NEVER existsByCityId per row (§E no N+1)")
    void should_notCallExistsByCityIdPerRow_when_listCitiesByOblast() {
        when(cityDistrictRepository.findCityIdsWithDistrictsByOblastId(kyivOblastId))
                .thenReturn(Set.of());
        when(cityRepository.findByOblastIdOrderByNameUkAsc(kyivOblastId))
                .thenReturn(List.of(
                        city(UUID.randomUUID(), "c1", "А", "A"),
                        city(UUID.randomUUID(), "c2", "Б", "B"),
                        city(UUID.randomUUID(), "c3", "В", "C")));

        service.listCitiesByOblast(kyivOblastId);

        // Exactly two queries regardless of city count: the set-based lookup once,
        // the ordered city list once. A per-row existsByCityId loop would be the
        // N+1 the picker contract forbids.
        verify(cityDistrictRepository, times(1)).findCityIdsWithDistrictsByOblastId(kyivOblastId);
        verify(cityRepository, times(1)).findByOblastIdOrderByNameUkAsc(kyivOblastId);
        verify(cityDistrictRepository, never()).existsByCityId(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("listCitiesByOblast — unknown oblastId yields an empty list (no exception)")
    void should_returnEmptyList_when_oblastIdUnknown() {
        UUID unknown = UUID.randomUUID();
        when(cityDistrictRepository.findCityIdsWithDistrictsByOblastId(unknown))
                .thenReturn(Set.of());
        when(cityRepository.findByOblastIdOrderByNameUkAsc(unknown))
                .thenReturn(List.of());

        assertThat(service.listCitiesByOblast(unknown)).isEmpty();
    }

    // ── listDistrictsByCity ───────────────────────────────────────────────────

    @Test
    @DisplayName("listDistrictsByCity — maps every field, parent cityId from the arg, order preserved")
    void should_mapDistrictsAndPreserveOrder_when_listDistrictsByCity() {
        UUID cityId = UUID.randomUUID();
        UUID d1 = UUID.randomUUID();
        UUID d2 = UUID.randomUUID();
        when(cityDistrictRepository.findByCityIdOrderByNameUkAsc(cityId))
                .thenReturn(List.of(
                        district(d1, "UA80B1", "Голосіївський район", "Holosiivskyi district"),
                        district(d2, "UA80B2", "Шевченківський район", "Shevchenkivskyi district")));

        List<CityDistrictResponse> result = service.listDistrictsByCity(cityId);

        assertThat(result)
                .extracting(CityDistrictResponse::nameUk)
                .containsExactly("Голосіївський район", "Шевченківський район");
        assertThat(result.get(0))
                .extracting(
                        CityDistrictResponse::id,
                        CityDistrictResponse::cityId,
                        CityDistrictResponse::katotthCode,
                        CityDistrictResponse::nameUk,
                        CityDistrictResponse::nameEn)
                .containsExactly(d1, cityId, "UA80B1", "Голосіївський район", "Holosiivskyi district");
    }

    @Test
    @DisplayName("listDistrictsByCity — city with no urban districts returns an empty list")
    void should_returnEmptyList_when_cityHasNoDistricts() {
        UUID cityId = UUID.randomUUID();
        when(cityDistrictRepository.findByCityIdOrderByNameUkAsc(cityId))
                .thenReturn(List.of());

        assertThat(service.listDistrictsByCity(cityId)).isEmpty();
        verifyNoInteractions(oblastRepository, cityRepository);
    }
}
