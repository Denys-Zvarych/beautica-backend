package com.beautica.service.service;

import com.beautica.service.entity.ServiceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ServiceTypeSlugResolver} — the OR/union per-service
 * filter seam (Phase 20.x). The contract under test: each input slug resolves
 * independently, an unknown slug yields an <em>absent</em> {@link Optional} at
 * its position (it is neither fatal nor does it collapse the batch), and valid
 * neighbours still resolve. Position is preserved so the caller
 * ({@code SearchService.resolveServiceTypes}) can drop the absent positions and
 * OR the survivors.
 */
@ExtendWith(MockitoExtension.class)
class ServiceTypeSlugResolverTest {

    private static final String SLUG_A = "hair-treatment-keratin";
    private static final String SLUG_B = "injection-mesotherapy";
    private static final String UNKNOWN_SLUG = "nonexistent-slug-xyz";

    @Mock
    private ServiceTypeLookup serviceTypeLookup;

    @InjectMocks
    private ServiceTypeSlugResolver resolver;

    private static ServiceType activeType(String slug, String nameUk) {
        return ServiceType.builder()
                .id(UUID.randomUUID())
                .slug(slug)
                .nameUk(nameUk)
                .build();
    }

    @Test
    @DisplayName("resolve — empty input returns an empty list without touching the lookup")
    void should_returnEmptyList_when_noSlugsGiven() {
        List<Optional<ServiceTypeMatch>> result = resolver.resolve(List.of());

        assertThat(result).as("empty slug input short-circuits to an empty result").isEmpty();
    }

    @Test
    @DisplayName("resolve — all slugs valid: every position resolves to its (id, nameUk) match")
    void should_resolveEveryPosition_when_allSlugsValid() {
        ServiceType typeA = activeType(SLUG_A, "Кератин");
        ServiceType typeB = activeType(SLUG_B, "Мезотерапія");
        when(serviceTypeLookup.getByCategory(null)).thenReturn(List.of(typeA, typeB));

        List<Optional<ServiceTypeMatch>> result = resolver.resolve(List.of(SLUG_A, SLUG_B));

        assertThat(result).hasSize(2);
        assertThat(result.get(0))
                .as("slug A resolves to its service-type id + Ukrainian name")
                .contains(new ServiceTypeMatch(typeA.getId(), "Кератин"));
        assertThat(result.get(1))
                .as("slug B resolves to its service-type id + Ukrainian name")
                .contains(new ServiceTypeMatch(typeB.getId(), "Мезотерапія"));
    }

    @Test
    @DisplayName("resolve — unknown slug yields an absent Optional at its position while valid neighbours resolve")
    void should_yieldAbsentOptional_forUnknownSlug_whileNeighboursResolve() {
        ServiceType typeA = activeType(SLUG_A, "Кератин");
        ServiceType typeB = activeType(SLUG_B, "Мезотерапія");
        when(serviceTypeLookup.getByCategory(null)).thenReturn(List.of(typeA, typeB));

        // Unknown slug is sandwiched between two valid slugs — position must be preserved.
        List<Optional<ServiceTypeMatch>> result =
                resolver.resolve(List.of(SLUG_A, UNKNOWN_SLUG, SLUG_B));

        assertThat(result).as("one Optional per input slug, in order").hasSize(3);
        assertThat(result.get(0))
                .as("the valid slug before the unknown still resolves")
                .contains(new ServiceTypeMatch(typeA.getId(), "Кератин"));
        assertThat(result.get(1))
                .as("the unknown slug resolves to an absent Optional, not an error")
                .isEmpty();
        assertThat(result.get(2))
                .as("the valid slug after the unknown still resolves")
                .contains(new ServiceTypeMatch(typeB.getId(), "Мезотерапія"));
    }

    @Test
    @DisplayName("resolve — every slug unknown: all positions absent (caller turns this into an explicit empty page)")
    void should_yieldAllAbsent_when_everySlugUnknown() {
        when(serviceTypeLookup.getByCategory(null))
                .thenReturn(List.of(activeType(SLUG_A, "Кератин")));

        List<Optional<ServiceTypeMatch>> result =
                resolver.resolve(List.of(UNKNOWN_SLUG, "another-missing-slug"));

        assertThat(result).hasSize(2);
        assertThat(result)
                .as("no input slug maps to an active type → every position is absent")
                .allMatch(Optional::isEmpty);
    }
}
