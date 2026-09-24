package com.beautica.location;

import com.beautica.location.repository.CityRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SettlementDisplayNameResolver — the shared city/region label lookup")
class SettlementDisplayNameResolverTest {

    @Mock
    private CityRepository cityRepository;

    @InjectMocks
    private SettlementDisplayNameResolver resolver;

    @Test
    @DisplayName("resolves both labels through the string-only projection")
    void should_returnCityAndRegion_when_cityExists() {
        UUID cityId = UUID.randomUUID();
        var names = new SettlementDisplayNames("Вінниця", "Вінницька");
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
        assertThatThrownBy(() -> new SettlementDisplayNames("Харків", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("region");
    }
}
