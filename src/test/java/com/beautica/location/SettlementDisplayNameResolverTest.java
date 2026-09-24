package com.beautica.location;

import com.beautica.location.entity.SettlementType;
import com.beautica.location.repository.CityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementDisplayNameResolver — the shared city/region label lookup")
class SettlementDisplayNameResolverTest {

    @Mock
    private CityRepository cityRepository;

    @InjectMocks
    private SettlementDisplayNameResolver resolver;

    private static final UUID POLTAVA_OBLAST = UUID.randomUUID();
    private static final UUID LVIV_OBLAST = UUID.randomUUID();

    @Test
    @DisplayName("resolves both labels through the string-only projection")
    void should_returnCityAndRegion_when_cityExists() {
        UUID cityId = UUID.randomUUID();
        var names = new SettlementDisplayNames("Вінниця", "Вінницька", SettlementType.CITY, null);
        when(cityRepository.findDisplayNamesById(cityId)).thenReturn(Optional.of(names));

        Optional<SettlementDisplayNames> resolved = resolver.resolve(cityId);

        assertThat(resolved).contains(names);
    }

    @Test
    @DisplayName("empty — and no query — for a null cityId")
    void should_returnEmptyWithoutQuery_when_cityIdIsNull() {
        Optional<SettlementDisplayNames> resolved = resolver.resolve(null);

        assertThat(resolved).isEmpty();
        verify(cityRepository, never()).findDisplayNamesById(any());
    }

    @Test
    @DisplayName("empty for an unknown cityId")
    void should_returnEmpty_when_cityNotFound() {
        UUID cityId = UUID.randomUUID();
        when(cityRepository.findDisplayNamesById(cityId)).thenReturn(Optional.empty());

        Optional<SettlementDisplayNames> resolved = resolver.resolve(cityId);

        assertThat(resolved).isEmpty();
    }

    @Test
    @DisplayName("the record refuses a missing region — the projection's inner join guarantees one")
    void should_rejectNullRegion_when_recordConstructed() {
        assertThatThrownBy(() -> new SettlementDisplayNames("Харків", null, SettlementType.CITY, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("region");
    }

    // ── resolveAll — the batch path behind GET /salons/mine ─────────────────────────

    @Test
    @DisplayName("resolveAll keys each settlement's parts by its own city id, in ONE query")
    void should_returnPartsKeyedByCityId_when_resolvingMany() {
        UUID villageId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        when(cityRepository.findDisplayNamesByIdIn(anyCollection())).thenReturn(List.of(
                new KeyedSettlementDisplayNames(villageId, POLTAVA_OBLAST, "Іванівка", "Полтавська",
                        SettlementType.VILLAGE, "Шишацька"),
                new KeyedSettlementDisplayNames(cityId, LVIV_OBLAST, "Львів", "Львівська",
                        SettlementType.CITY, null)));

        Map<UUID, KeyedSettlementDisplayNames> resolved =
                resolver.resolveAll(Set.of(villageId, cityId));

        assertThat(resolved).containsOnlyKeys(villageId, cityId);
        assertThat(resolved.get(villageId).oblastId()).isEqualTo(POLTAVA_OBLAST);
        assertThat(resolved.get(villageId).names()).isEqualTo(new SettlementDisplayNames(
                "Іванівка", "Полтавська", SettlementType.VILLAGE, "Шишацька"));
        assertThat(resolved.get(cityId).oblastId()).isEqualTo(LVIV_OBLAST);
        assertThat(resolved.get(cityId).names()).isEqualTo(new SettlementDisplayNames(
                "Львів", "Львівська", SettlementType.CITY, null));
        verify(cityRepository, times(1)).findDisplayNamesByIdIn(anyCollection());
        verify(cityRepository, never()).findDisplayNamesById(any());
    }

    @Test
    @DisplayName("resolveAll issues no query for an empty or all-null id collection")
    void should_returnEmptyWithoutQuery_when_noNonNullIds() {
        Map<UUID, KeyedSettlementDisplayNames> resolved = resolver.resolveAll(Arrays.asList(null, null));

        assertThat(resolved).isEmpty();
        verify(cityRepository, never()).findDisplayNamesByIdIn(anyCollection());
    }

    @Test
    @DisplayName("resolveAll drops null ids from the query and tolerates a null-key lookup")
    void should_skipNullIds_when_collectionMixesNullAndReal() {
        UUID cityId = UUID.randomUUID();
        when(cityRepository.findDisplayNamesByIdIn(List.of(cityId))).thenReturn(List.of(
                new KeyedSettlementDisplayNames(cityId, LVIV_OBLAST, "Львів", "Львівська",
                        SettlementType.CITY, null)));

        Map<UUID, KeyedSettlementDisplayNames> resolved = resolver.resolveAll(Arrays.asList(cityId, null));

        assertThat(resolved).containsOnlyKeys(cityId);
        assertThat(resolved.get(null)).as("a salon with no cityId looks up null — must not NPE").isNull();
    }

    @Test
    @DisplayName("the record refuses a missing settlementType — the column is NOT NULL")
    void should_rejectNullSettlementType_when_recordConstructed() {
        assertThatThrownBy(() -> new SettlementDisplayNames("Харків", "Харківська", null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("settlementType");
    }
}
