package com.beautica.favorite.service;

import com.beautica.favorite.dto.FavoriteCategoryView;
import com.beautica.favorite.service.FavoriteCategoryResolver.FavoriteCategories;
import com.beautica.service.service.OfferedCategoryLookup;
import com.beautica.service.service.PlatformCategoryLabel;
import com.beautica.service.service.PlatformCategoryLabelResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FavoriteCategoryResolver} — the join between a page's OFFERED category
 * CODES and their Ukrainian display LABELS.
 *
 * <p>Both collaborators are mocked. The offering side ({@link OfferedCategoryLookup}) is
 * exercised for real against Postgres in {@code FavoriteMigrationIT}; what is under test here is
 * the pairing/ordering logic — the both-or-neither rule, the round-trip budget, and the empty
 * short-circuit.
 *
 * <p><b>Fixture discipline.</b> Every category code and every label in this class is a distinct
 * string, and no test uses a single-entry map where a multi-entry one would do. A resolver that
 * paired the wrong code with the wrong label, or collapsed the page onto one category, therefore
 * fails on a wrong VALUE rather than silently producing the fixture's only value.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FavoriteCategoryResolver — unit")
class FavoriteCategoryResolverTest {

    @Mock
    private OfferedCategoryLookup offeredCategoryLookup;

    @Mock
    private PlatformCategoryLabelResolver platformCategoryLabelResolver;

    @InjectMocks
    private FavoriteCategoryResolver resolver;

    private static final List<PlatformCategoryLabel> SELECTABLE = List.of(
            new PlatformCategoryLabel("MANICURE", "Манікюр"),
            new PlatformCategoryLabel("HAIRCUT", "Стрижка"),
            new PlatformCategoryLabel("EYELASH", "Вії"));

    @Nested
    @DisplayName("pairing codes to labels")
    class Pairing {

        @Test
        @DisplayName("pairs each provider's codes with ITS OWN labels, never another provider's")
        void should_pairCodesWithOwnLabels_when_pageSpansSeveralCategories() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            when(offeredCategoryLookup.offeredCategoriesByMaster(anyCollection()))
                    .thenReturn(Map.of(
                            a, List.of("MANICURE"), b, List.of("HAIRCUT"), c, List.of("EYELASH")));
            when(platformCategoryLabelResolver.selectableLabels()).thenReturn(SELECTABLE);

            FavoriteCategories result = resolver.resolveForMasters(Set.of(a, b, c));

            assertThat(result.categories(a)).containsExactly(new FavoriteCategoryView("MANICURE", "Манікюр"));
            assertThat(result.categories(b)).containsExactly(new FavoriteCategoryView("HAIRCUT", "Стрижка"));
            assertThat(result.categories(c)).containsExactly(new FavoriteCategoryView("EYELASH", "Вії"));
        }

        @Test
        @DisplayName("pairs EVERY code a provider offers, not just the first")
        void should_pairEveryCode_when_providerOffersSeveralCategories() {
            UUID multi = UUID.randomUUID();
            when(offeredCategoryLookup.offeredCategoriesByMaster(anyCollection()))
                    .thenReturn(Map.of(multi, List.of("HAIRCUT", "MANICURE")));
            when(platformCategoryLabelResolver.selectableLabels()).thenReturn(SELECTABLE);

            FavoriteCategories result = resolver.resolveForMasters(Set.of(multi));

            assertThat(result.categories(multi))
                    .as("ordered by display label — Манікюр before Стрижка — not by lookup row order")
                    .containsExactly(
                            new FavoriteCategoryView("MANICURE", "Манікюр"),
                            new FavoriteCategoryView("HAIRCUT", "Стрижка"));
        }

        @Test
        @DisplayName("returns an EMPTY list for a provider absent from the offering lookup")
        void should_returnEmptyList_when_providerOffersNothing() {
            UUID offering = UUID.randomUUID();
            UUID offeringNothing = UUID.randomUUID();
            when(offeredCategoryLookup.offeredCategoriesByMaster(anyCollection()))
                    .thenReturn(Map.of(offering, List.of("MANICURE")));
            when(platformCategoryLabelResolver.selectableLabels()).thenReturn(SELECTABLE);

            FavoriteCategories result = resolver.resolveForMasters(Set.of(offering, offeringNothing));

            assertThat(result.categories(offeringNothing)).isNotNull().isEmpty();
            assertThat(result.categories(offering))
                    .as("the absent provider must not suppress the present one")
                    .containsExactly(new FavoriteCategoryView("MANICURE", "Манікюр"));
        }

        /**
         * The both-or-neither rule. {@code service_definitions.category} carries no FK to
         * {@code platform_categories} (V64), so a code CAN outlive its category being
         * deactivated or never having been approved. Emitting that code with a {@code null}
         * label would hand the client a chip identity it cannot draw and cannot match against
         * its own approved-category vocabulary.
         *
         * <p>The fixture pins that the suppression is per-CATEGORY (and per-provider), not
         * per-page: the companion row's resolvable category, and the companion CODE on the same
         * provider, must both survive.
         */
        @Test
        @DisplayName("drops an entry when its code is not a currently selectable category")
        void should_dropEntry_when_categoryNotSelectable() {
            UUID stale = UUID.randomUUID();
            UUID live = UUID.randomUUID();
            when(offeredCategoryLookup.offeredCategoriesByMaster(anyCollection()))
                    .thenReturn(Map.of(
                            stale, List.of("DEACTIVATED_CATEGORY", "MANICURE"), live, List.of("HAIRCUT")));
            when(platformCategoryLabelResolver.selectableLabels()).thenReturn(SELECTABLE);

            FavoriteCategories result = resolver.resolveForMasters(Set.of(stale, live));

            assertThat(result.categories(stale))
                    .as("the unresolvable code is dropped; the provider's OTHER, resolvable code survives")
                    .containsExactly(new FavoriteCategoryView("MANICURE", "Манікюр"));
            assertThat(result.categories(live))
                    .as("suppression is per code, not per page")
                    .containsExactly(new FavoriteCategoryView("HAIRCUT", "Стрижка"));
        }

        @Test
        @DisplayName("a provider whose ONLY code is unselectable ends up with an empty list, not a missing entry")
        void should_returnEmptyList_when_onlyCodeIsUnselectable() {
            UUID stale = UUID.randomUUID();
            when(offeredCategoryLookup.offeredCategoriesByMaster(anyCollection()))
                    .thenReturn(Map.of(stale, List.of("DEACTIVATED_CATEGORY")));
            when(platformCategoryLabelResolver.selectableLabels()).thenReturn(SELECTABLE);

            FavoriteCategories result = resolver.resolveForMasters(Set.of(stale));

            assertThat(result.categories(stale)).isNotNull().isEmpty();
        }
    }

    @Nested
    @DisplayName("round-trip budget")
    class RoundTrips {

        /**
         * The label side must cost NO round trip. {@link PlatformCategoryLabelResolver} is
         * backed by the {@code platform-category-order} cache, so reading it once per page is
         * free; reading it once per PROVIDER would still be free in wall-clock terms but would
         * re-materialise the map per row, and — more importantly — is the shape that decays
         * into a per-row query the moment someone swaps the collaborator. Pinning ONE call per
         * page keeps the intent legible.
         */
        @Test
        @DisplayName("reads the cached label list once per page, not once per provider")
        void should_readLabelsOncePerPage_when_pageHasManyProviders() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            when(offeredCategoryLookup.offeredCategoriesByMaster(anyCollection()))
                    .thenReturn(Map.of(
                            a, List.of("MANICURE"), b, List.of("HAIRCUT"), c, List.of("EYELASH")));
            when(platformCategoryLabelResolver.selectableLabels()).thenReturn(SELECTABLE);

            resolver.resolveForMasters(Set.of(a, b, c));

            verify(platformCategoryLabelResolver, times(1)).selectableLabels();
        }

        @Test
        @DisplayName("issues exactly one offering lookup per page, carrying every provider id")
        void should_issueOneOfferingLookup_when_resolvingMasters() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(offeredCategoryLookup.offeredCategoriesByMaster(anyCollection()))
                    .thenReturn(Map.of(a, List.of("MANICURE"), b, List.of("HAIRCUT")));
            when(platformCategoryLabelResolver.selectableLabels()).thenReturn(SELECTABLE);

            resolver.resolveForMasters(Set.of(a, b));

            verify(offeredCategoryLookup, times(1)).offeredCategoriesByMaster(eq(Set.of(a, b)));
        }

        /**
         * When nobody on the page offers anything there is nothing to label, so the cached
         * list is not even read. Cheap, but it also pins that the label read is downstream of
         * the offering read rather than an unconditional preamble.
         */
        @Test
        @DisplayName("skips label resolution entirely when no provider offers anything")
        void should_skipLabelResolution_when_offeringIsEmpty() {
            when(offeredCategoryLookup.offeredCategoriesByMaster(anyCollection())).thenReturn(Map.of());

            FavoriteCategories result = resolver.resolveForMasters(Set.of(UUID.randomUUID()));

            assertThat(result.byProvider()).isEmpty();
            verifyNoInteractions(platformCategoryLabelResolver);
        }
    }

    @Nested
    @DisplayName("arm separation")
    class ArmSeparation {

        /**
         * The two arms key on different repository methods — a salon arm that called the master
         * lookup would resolve nothing, and a master arm that called the salon lookup would
         * attribute a salon's offering to one of its employees.
         */
        @Test
        @DisplayName("routes the salon arm to the salon lookup and never to the master one")
        void should_useSalonLookup_when_resolvingSalons() {
            UUID salonId = UUID.randomUUID();
            when(offeredCategoryLookup.offeredCategoriesBySalon(anyCollection()))
                    .thenReturn(Map.of(salonId, List.of("EYELASH")));
            when(platformCategoryLabelResolver.selectableLabels()).thenReturn(SELECTABLE);

            FavoriteCategories result = resolver.resolveForSalons(Set.of(salonId));

            assertThat(result.categories(salonId)).containsExactly(new FavoriteCategoryView("EYELASH", "Вії"));
            verify(offeredCategoryLookup, times(1)).offeredCategoriesBySalon(anyCollection());
            verify(offeredCategoryLookup, times(0)).offeredCategoriesByMaster(anyCollection());
        }
    }
}
