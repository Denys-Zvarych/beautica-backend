package com.beautica.favorite.service;

import com.beautica.booking.service.LastBookedCategoryLookup;
import com.beautica.favorite.service.FavoriteCategoryResolver.FavoriteCategories;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FavoriteCategoryResolver} — the join between a page's booked-history
 * category CODES and their Ukrainian display LABELS.
 *
 * <p>Both collaborators are mocked. The booked-history side
 * ({@link LastBookedCategoryLookup}) is exercised for real against Postgres in
 * {@code FavoriteMigrationIT}; what is under test here is the pairing logic — the
 * both-or-neither rule, the round-trip budget, and the empty short-circuit.
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
    private LastBookedCategoryLookup lastBookedCategoryLookup;

    @Mock
    private PlatformCategoryLabelResolver platformCategoryLabelResolver;

    @InjectMocks
    private FavoriteCategoryResolver resolver;

    private final UUID clientId = UUID.randomUUID();

    private static final List<PlatformCategoryLabel> SELECTABLE = List.of(
            new PlatformCategoryLabel("MANICURE", "Манікюр"),
            new PlatformCategoryLabel("HAIRCUT", "Стрижка"),
            new PlatformCategoryLabel("EYELASH", "Вії"));

    @Nested
    @DisplayName("pairing code to label")
    class Pairing {

        @Test
        @DisplayName("pairs each provider's code with ITS OWN label, never the first one found")
        void should_pairCodeWithOwnLabel_when_pageSpansSeveralCategories() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            when(lastBookedCategoryLookup.lastBookedCategoryByMaster(eq(clientId), anyCollection()))
                    .thenReturn(Map.of(a, "MANICURE", b, "HAIRCUT", c, "EYELASH"));
            when(platformCategoryLabelResolver.selectableLabels()).thenReturn(SELECTABLE);

            FavoriteCategories result = resolver.resolveForMasters(clientId, Set.of(a, b, c));

            assertThat(result.code(a)).isEqualTo("MANICURE");
            assertThat(result.label(a)).isEqualTo("Манікюр");
            assertThat(result.code(b)).isEqualTo("HAIRCUT");
            assertThat(result.label(b)).isEqualTo("Стрижка");
            assertThat(result.code(c)).isEqualTo("EYELASH");
            assertThat(result.label(c)).isEqualTo("Вії");
        }

        @Test
        @DisplayName("returns null for BOTH fields for a provider with no booked history")
        void should_returnNulls_when_providerAbsentFromHistory() {
            UUID booked = UUID.randomUUID();
            UUID neverBooked = UUID.randomUUID();
            when(lastBookedCategoryLookup.lastBookedCategoryByMaster(eq(clientId), anyCollection()))
                    .thenReturn(Map.of(booked, "MANICURE"));
            when(platformCategoryLabelResolver.selectableLabels()).thenReturn(SELECTABLE);

            FavoriteCategories result = resolver.resolveForMasters(clientId, Set.of(booked, neverBooked));

            assertThat(result.code(neverBooked)).isNull();
            assertThat(result.label(neverBooked)).isNull();
            assertThat(result.code(booked))
                    .as("the absent provider must not suppress the present one")
                    .isEqualTo("MANICURE");
        }

        /**
         * The both-or-neither rule. {@code service_definitions.category} carries no FK to
         * {@code platform_categories} (V64), so a code CAN outlive its category being
         * deactivated or never having been approved. Emitting that code with a {@code null}
         * label would hand the client a chip identity it cannot draw and cannot match against
         * its own approved-category vocabulary.
         *
         * <p>The fixture pins that the suppression is per-PROVIDER, not per-page: the
         * companion row's resolvable category must survive. A resolver that bailed out of the
         * whole page on one unresolvable code would pass a single-row version of this test.
         */
        @Test
        @DisplayName("drops both fields when the code is not a currently selectable category")
        void should_dropBothFields_when_categoryNotSelectable() {
            UUID stale = UUID.randomUUID();
            UUID live = UUID.randomUUID();
            when(lastBookedCategoryLookup.lastBookedCategoryByMaster(eq(clientId), anyCollection()))
                    .thenReturn(Map.of(stale, "DEACTIVATED_CATEGORY", live, "HAIRCUT"));
            when(platformCategoryLabelResolver.selectableLabels()).thenReturn(SELECTABLE);

            FavoriteCategories result = resolver.resolveForMasters(clientId, Set.of(stale, live));

            assertThat(result.code(stale))
                    .as("a code with no selectable label is not emitted half-resolved")
                    .isNull();
            assertThat(result.label(stale)).isNull();
            assertThat(result.code(live))
                    .as("suppression is per provider, not per page")
                    .isEqualTo("HAIRCUT");
            assertThat(result.label(live)).isEqualTo("Стрижка");
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
            when(lastBookedCategoryLookup.lastBookedCategoryByMaster(eq(clientId), anyCollection()))
                    .thenReturn(Map.of(a, "MANICURE", b, "HAIRCUT", c, "EYELASH"));
            when(platformCategoryLabelResolver.selectableLabels()).thenReturn(SELECTABLE);

            resolver.resolveForMasters(clientId, Set.of(a, b, c));

            verify(platformCategoryLabelResolver, times(1)).selectableLabels();
        }

        @Test
        @DisplayName("issues exactly one history lookup per page, carrying every provider id")
        void should_issueOneHistoryLookup_when_resolvingMasters() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            when(lastBookedCategoryLookup.lastBookedCategoryByMaster(eq(clientId), anyCollection()))
                    .thenReturn(Map.of(a, "MANICURE", b, "HAIRCUT"));
            when(platformCategoryLabelResolver.selectableLabels()).thenReturn(SELECTABLE);

            resolver.resolveForMasters(clientId, Set.of(a, b));

            verify(lastBookedCategoryLookup, times(1))
                    .lastBookedCategoryByMaster(eq(clientId), eq(Set.of(a, b)));
        }

        /**
         * When nobody on the page has booked history there is nothing to label, so the cached
         * list is not even read. Cheap, but it also pins that the label read is downstream of
         * the history read rather than an unconditional preamble.
         */
        @Test
        @DisplayName("skips label resolution entirely when no provider has booked history")
        void should_skipLabelResolution_when_historyIsEmpty() {
            when(lastBookedCategoryLookup.lastBookedCategoryByMaster(eq(clientId), anyCollection()))
                    .thenReturn(Map.of());

            FavoriteCategories result = resolver.resolveForMasters(clientId, Set.of(UUID.randomUUID()));

            assertThat(result.byProvider()).isEmpty();
            verifyNoInteractions(platformCategoryLabelResolver);
        }
    }

    @Nested
    @DisplayName("arm separation")
    class ArmSeparation {

        /**
         * The two arms key on different columns ({@code bookings.master_id} vs.
         * {@code bookings.salon_id}) and must not be routed through each other — a salon arm
         * that called the master lookup would resolve nothing, and a master arm that called the
         * salon lookup would attribute a salon's history to one of its employees.
         */
        @Test
        @DisplayName("routes the salon arm to the salon lookup and never to the master one")
        void should_useSalonLookup_when_resolvingSalons() {
            UUID salonId = UUID.randomUUID();
            when(lastBookedCategoryLookup.lastBookedCategoryBySalon(eq(clientId), anyCollection()))
                    .thenReturn(Map.of(salonId, "EYELASH"));
            when(platformCategoryLabelResolver.selectableLabels()).thenReturn(SELECTABLE);

            FavoriteCategories result = resolver.resolveForSalons(clientId, Set.of(salonId));

            assertThat(result.code(salonId)).isEqualTo("EYELASH");
            assertThat(result.label(salonId)).isEqualTo("Вії");
            verify(lastBookedCategoryLookup, times(1)).lastBookedCategoryBySalon(eq(clientId), anyCollection());
            verify(lastBookedCategoryLookup, times(0)).lastBookedCategoryByMaster(any(), anyCollection());
        }
    }
}
