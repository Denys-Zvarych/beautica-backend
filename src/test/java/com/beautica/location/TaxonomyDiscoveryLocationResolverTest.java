package com.beautica.location;

import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import com.beautica.location.repository.CityDistrictRepository;
import com.beautica.location.repository.CityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the M2 seam's Part-A implementation
 * ({@link TaxonomyDiscoveryLocationResolver}).
 *
 * <p>This is the <b>swappable class</b> Part B replaces. {@code SearchService}
 * only sees the {@link DiscoveryLocationResolver} interface (and stubs it in its
 * own unit test), so the district-primary {@code resolveFilter} contract and the
 * batched, empty-set-short-circuiting {@code resolveLabels} behaviour are
 * <em>not</em> exercised anywhere else at the unit level. A Part-B swap that
 * silently inverted the district-primary rule, or reintroduced a per-row label
 * lookup, would pass every {@code SearchServiceTest} mock — this test pins the
 * seam's own behaviour so that drift fails fast.</p>
 *
 * <p>Pure Mockito, no Spring context (playbook Q3 — slice/unit over
 * {@code @SpringBootTest}); the two taxonomy repositories are mocked so the
 * resolver's branching/batching logic is the only thing under test.</p>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TaxonomyDiscoveryLocationResolver — M2 seam unit")
class TaxonomyDiscoveryLocationResolverTest {

    @Mock
    private CityRepository cityRepository;

    @Mock
    private CityDistrictRepository cityDistrictRepository;

    @InjectMocks
    private TaxonomyDiscoveryLocationResolver resolver;

    private static final UUID CITY_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DISTRICT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    /** {@code [id, name_uk]} projection row, typed as the repository returns it. */
    private static List<Object[]> labelRow(UUID id, String nameUk) {
        return List.<Object[]>of(new Object[]{id, nameUk});
    }

    @Nested
    @DisplayName("resolveFilter — district-primary, read side")
    class ResolveFilter {

        @Test
        @DisplayName("returns a district-primary key (district wins) when both city and district are supplied")
        void should_returnDistrictPrimaryKey_when_bothCityAndDistrictSupplied() {
            DiscoveryLocationKey key = resolver.resolveFilter(CITY_ID, DISTRICT_ID);

            assertThat(key).isNotNull();
            assertThat(key.cityId()).isEqualTo(CITY_ID);
            assertThat(key.districtId()).isEqualTo(DISTRICT_ID);
            assertThat(key.isDistrictPrimary())
                    .as("a supplied districtId is the discovery unit")
                    .isTrue();
            // Read-side rule: no taxonomy lookup needed to decide the filter —
            // resolveFilter never touches the repositories (it only carries the
            // caller's selection; FK validity is the data layer's job).
            verifyNoInteractions(cityRepository, cityDistrictRepository);
        }

        @Test
        @DisplayName("widens to city-level (districtId null) when only a city is supplied — districted city without a district is not an error here")
        void should_widenToCityLevel_when_onlyCitySupplied() {
            DiscoveryLocationKey key = resolver.resolveFilter(CITY_ID, null);

            assertThat(key).isNotNull();
            assertThat(key.cityId()).isEqualTo(CITY_ID);
            assertThat(key.districtId())
                    .as("write-side 'districted city requires a district' is Phase 10.6, not here")
                    .isNull();
            assertThat(key.isDistrictPrimary()).isFalse();
        }

        @Test
        @DisplayName("returns a district-primary key even when cityId is null (districtId alone still wins)")
        void should_returnDistrictPrimaryKey_when_onlyDistrictSupplied() {
            DiscoveryLocationKey key = resolver.resolveFilter(null, DISTRICT_ID);

            assertThat(key).isNotNull();
            assertThat(key.cityId()).isNull();
            assertThat(key.districtId()).isEqualTo(DISTRICT_ID);
            assertThat(key.isDistrictPrimary()).isTrue();
        }

        @Test
        @DisplayName("returns null (no location filter) when both ids are null")
        void should_returnNull_when_bothIdsNull() {
            DiscoveryLocationKey key = resolver.resolveFilter(null, null);

            assertThat(key)
                    .as("both ids null means 'no location filter' — caller must omit the predicate")
                    .isNull();
            verifyNoInteractions(cityRepository, cityDistrictRepository);
        }
    }

    @Nested
    @DisplayName("resolveLabels — batched, no N+1 (§E)")
    class ResolveLabels {

        @Test
        @DisplayName("issues exactly one city query and one district query for the whole page and maps name_uk")
        void should_batchResolveBothDimensions_when_idsPresent() {
            Set<UUID> cityIds = Set.of(CITY_ID);
            Set<UUID> districtIds = Set.of(DISTRICT_ID);
            when(cityRepository.findNameUkByIdIn(cityIds))
                    .thenReturn(labelRow(CITY_ID, "Київ"));
            when(cityDistrictRepository.findNameUkByIdIn(districtIds))
                    .thenReturn(labelRow(DISTRICT_ID, "Голосіївський район"));

            DiscoveryLabels labels = resolver.resolveLabels(cityIds, districtIds);

            assertThat(labels.cityLabel(CITY_ID)).isEqualTo("Київ");
            assertThat(labels.districtLabel(DISTRICT_ID)).isEqualTo("Голосіївський район");
            // Exactly one batched query per dimension — never per-row (§E).
            verify(cityRepository).findNameUkByIdIn(cityIds);
            verify(cityDistrictRepository).findNameUkByIdIn(districtIds);
        }

        @Test
        @DisplayName("skips the city query entirely when the city id set is empty (no wasted round-trip)")
        void should_skipCityQuery_when_cityIdsEmpty() {
            when(cityDistrictRepository.findNameUkByIdIn(any()))
                    .thenReturn(labelRow(DISTRICT_ID, "Голосіївський район"));

            DiscoveryLabels labels = resolver.resolveLabels(Set.of(), Set.of(DISTRICT_ID));

            assertThat(labels.cityLabel(CITY_ID)).isNull();
            assertThat(labels.districtLabel(DISTRICT_ID)).isEqualTo("Голосіївський район");
            verify(cityRepository, never()).findNameUkByIdIn(any());
        }

        @Test
        @DisplayName("skips the district query entirely when the district id set is empty (non-districted page)")
        void should_skipDistrictQuery_when_districtIdsEmpty() {
            when(cityRepository.findNameUkByIdIn(any()))
                    .thenReturn(labelRow(CITY_ID, "Львів"));

            DiscoveryLabels labels = resolver.resolveLabels(Set.of(CITY_ID), Set.of());

            assertThat(labels.cityLabel(CITY_ID)).isEqualTo("Львів");
            assertThat(labels.districtLabel(DISTRICT_ID))
                    .as("non-districted city → districtLabel resolves to null")
                    .isNull();
            verify(cityDistrictRepository, never()).findNameUkByIdIn(any());
        }

        @Test
        @DisplayName("touches no repository at all when both id sets are empty")
        void should_touchNoRepository_when_bothIdSetsEmpty() {
            DiscoveryLabels labels = resolver.resolveLabels(Set.of(), Set.of());

            assertThat(labels.cityLabel(CITY_ID)).isNull();
            assertThat(labels.districtLabel(DISTRICT_ID)).isNull();
            verifyNoInteractions(cityRepository, cityDistrictRepository);
        }

        @Test
        @DisplayName("yields a null label for an id absent from the resolved rows (FK-less / legacy row surfaces as null)")
        void should_returnNullLabel_when_idNotInResolvedRows() {
            UUID unknownCity = UUID.randomUUID();
            when(cityRepository.findNameUkByIdIn(any()))
                    .thenReturn(labelRow(CITY_ID, "Київ"));

            DiscoveryLabels labels = resolver.resolveLabels(Set.of(CITY_ID, unknownCity), Set.of());

            assertThat(labels.cityLabel(CITY_ID)).isEqualTo("Київ");
            assertThat(labels.cityLabel(unknownCity))
                    .as("a row whose FK resolves to nothing yields a null label, surfaced as-is on the DTO")
                    .isNull();
        }

        @Test
        @DisplayName("null id lookups return null without consulting the resolved map (DiscoveryLabels guard)")
        void should_returnNull_when_lookupIdIsNull() {
            DiscoveryLabels labels = resolver.resolveLabels(Set.of(), Set.of());

            assertThat(labels.cityLabel(null)).isNull();
            assertThat(labels.districtLabel(null)).isNull();
        }
    }
}
