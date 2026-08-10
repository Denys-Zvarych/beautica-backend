package com.beautica.service.service;

import com.beautica.auth.Role;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.SalonServiceCategoryGroup;
import com.beautica.service.dto.ServiceDefinitionResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link SalonServiceFavoriteDecorator} — the per-request, post-cache
 * decoration bean for {@code GET /salons/{salonId}/services} (salon-service-favourites track,
 * Phase C).
 *
 * <p>No Spring context — pure {@code @ExtendWith(MockitoExtension.class)}, {@link
 * FavoriteRepository} mocked. This is the fast-unit sibling missing before this audit: the
 * decorator's own decision table (who gets queried, who gets {@code null}, what gets rebuilt)
 * was previously exercised ONLY via {@code SalonCatalogueFavoriteDecorationIT} — a real-Postgres,
 * real-HTTP, real-cache integration test. That class is the right place to prove the cross-client
 * cache-leak property end to end, but it is the wrong (slow, IT-tier) place to pin the decorator's
 * pure branching logic — exactly the asymmetry {@code MasterServiceFavoriteDecoratorTest} already
 * avoids for the sibling MASTER-arm decorator. This class closes that gap.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalonServiceFavoriteDecorator")
class SalonServiceFavoriteDecoratorTest {

    @Mock
    private FavoriteRepository favoriteRepository;

    private SalonServiceFavoriteDecorator decorator;

    @BeforeEach
    void setUp() {
        // Built here, not as a field initializer: @Mock fields are injected by MockitoExtension
        // AFTER the test instance is constructed, so a field-initializer constructor call would
        // capture a still-null favoriteRepository.
        decorator = new SalonServiceFavoriteDecorator(favoriteRepository);
    }

    @Test
    @DisplayName("returns the catalog untouched (every isFavorite stays null) when Authentication is null")
    void should_returnUntouched_when_authenticationIsNull() {
        SalonServiceCatalogResponse catalog = catalogWith(group("MANICURE", stub(UUID.randomUUID())));

        SalonServiceCatalogResponse result = decorator.decorate(catalog, null);

        assertThat(result).isSameAs(catalog);
        verifyNoInteractions(favoriteRepository);
    }

    @Test
    @DisplayName("returns the catalog untouched when the caller is Spring Security's anonymous principal")
    void should_returnUntouched_when_authenticationIsAnonymous() {
        SalonServiceCatalogResponse catalog = catalogWith(group("MANICURE", stub(UUID.randomUUID())));
        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        SalonServiceCatalogResponse result = decorator.decorate(catalog, anonymous);

        assertThat(result).isSameAs(catalog);
        verifyNoInteractions(favoriteRepository);
    }

    @Test
    @DisplayName("returns the catalog untouched and issues ZERO queries when the principal is "
            + "authenticated but not a CLIENT — providers must pay nothing for a flag they can never have")
    void should_returnUntouched_when_principalIsNotClient() {
        SalonServiceCatalogResponse catalog = catalogWith(group("MANICURE", stub(UUID.randomUUID())));
        Authentication owner = authenticatedAs(UUID.randomUUID(), Role.SALON_OWNER);

        SalonServiceCatalogResponse result = decorator.decorate(catalog, owner);

        assertThat(result).isSameAs(catalog);
        verifyNoInteractions(favoriteRepository);
    }

    @Test
    @DisplayName("returns the catalog untouched when there are no category groups at all — "
            + "no query with an empty target set")
    void should_returnUntouched_when_noCategoryGroups() {
        SalonServiceCatalogResponse catalog = new SalonServiceCatalogResponse(List.of());
        Authentication client = authenticatedAs(UUID.randomUUID(), Role.CLIENT);

        SalonServiceCatalogResponse result = decorator.decorate(catalog, client);

        assertThat(result).isSameAs(catalog);
        verifyNoInteractions(favoriteRepository);
    }

    @Test
    @DisplayName("returns the catalog untouched when every group is present but empty of services — "
            + "the second, service-id-level short-circuit, distinct from the empty-groups one above")
    void should_returnUntouched_when_groupsPresentButNoServices() {
        SalonServiceCategoryGroup emptyGroup = new SalonServiceCategoryGroup("MANICURE", "Манікюр", 0, List.of());
        SalonServiceCatalogResponse catalog = new SalonServiceCatalogResponse(List.of(emptyGroup));
        Authentication client = authenticatedAs(UUID.randomUUID(), Role.CLIENT);

        SalonServiceCatalogResponse result = decorator.decorate(catalog, client);

        assertThat(result).isSameAs(catalog);
        verifyNoInteractions(favoriteRepository);
    }

    @Test
    @DisplayName("flags only the ids the CLIENT has favourited under SALON_SERVICE — the rest come "
            + "back false, never null — and queries the SALON_SERVICE arm, not SERVICE")
    void should_flagOnlyFavouritedIds_when_clientHasSubsetFavourited() {
        UUID clientId = UUID.randomUUID();
        UUID favourited = UUID.randomUUID();
        UUID notFavourited = UUID.randomUUID();
        SalonServiceCatalogResponse catalog =
                catalogWith(group("MANICURE", stub(favourited), stub(notFavourited)));
        Authentication client = authenticatedAs(clientId, Role.CLIENT);
        when(favoriteRepository.findFavoritedServiceIds(
                eq(clientId), eq(FavoriteTargetType.SALON_SERVICE), any()))
                .thenReturn(Set.of(favourited));

        SalonServiceCatalogResponse result = decorator.decorate(catalog, client);

        List<ServiceDefinitionResponse> services = result.categories().get(0).services();
        assertThat(services)
                .filteredOn(r -> r.id().equals(favourited))
                .singleElement()
                .extracting(ServiceDefinitionResponse::isFavorite)
                .isEqualTo(true);
        assertThat(services)
                .filteredOn(r -> r.id().equals(notFavourited))
                .singleElement()
                .extracting(ServiceDefinitionResponse::isFavorite)
                .isEqualTo(false);
    }

    @Test
    @DisplayName("decorates across MULTIPLE category groups in one pass, preserving each group's "
            + "category/displayName/count")
    void should_decorateAcrossMultipleGroups_when_catalogHasSeveralCategories() {
        UUID clientId = UUID.randomUUID();
        UUID favouritedInGroup2 = UUID.randomUUID();
        SalonServiceCategoryGroup group1 = group("MANICURE", stub(UUID.randomUUID()));
        SalonServiceCategoryGroup group2 = group("PEDICURE", stub(favouritedInGroup2));
        SalonServiceCatalogResponse catalog = new SalonServiceCatalogResponse(List.of(group1, group2));
        Authentication client = authenticatedAs(clientId, Role.CLIENT);
        when(favoriteRepository.findFavoritedServiceIds(
                eq(clientId), eq(FavoriteTargetType.SALON_SERVICE), any()))
                .thenReturn(Set.of(favouritedInGroup2));

        SalonServiceCatalogResponse result = decorator.decorate(catalog, client);

        assertThat(result.categories()).hasSize(2);
        assertThat(result.categories().get(0).category()).isEqualTo("MANICURE");
        assertThat(result.categories().get(0).services().get(0).isFavorite()).isFalse();
        assertThat(result.categories().get(1).category()).isEqualTo("PEDICURE");
        assertThat(result.categories().get(1).services().get(0).isFavorite()).isTrue();
        assertThat(result.categories().get(1).displayName()).isEqualTo(group2.displayName());
        assertThat(result.categories().get(1).count()).isEqualTo(group2.count());
    }

    @Test
    @DisplayName("returns NEW record instances for a CLIENT caller — the input catalog, its groups "
            + "and their service rows are never mutated, since a record cannot be")
    void should_returnNewInstances_when_decorating() {
        UUID clientId = UUID.randomUUID();
        ServiceDefinitionResponse original = stub(UUID.randomUUID());
        SalonServiceCategoryGroup originalGroup = new SalonServiceCategoryGroup(
                "MANICURE", "Манікюр", 1, List.of(original));
        SalonServiceCatalogResponse catalog = new SalonServiceCatalogResponse(List.of(originalGroup));
        Authentication client = authenticatedAs(clientId, Role.CLIENT);
        when(favoriteRepository.findFavoritedServiceIds(
                eq(clientId), eq(FavoriteTargetType.SALON_SERVICE), any()))
                .thenReturn(Set.of());

        SalonServiceCatalogResponse result = decorator.decorate(catalog, client);

        assertThat(result).isNotSameAs(catalog);
        assertThat(result.categories().get(0)).isNotSameAs(originalGroup);
        assertThat(result.categories().get(0).services().get(0)).isNotSameAs(original);
        assertThat(original.isFavorite())
                .as("the original record must be left exactly as it was — records cannot be mutated, "
                        + "but this pins the invariant explicitly")
                .isNull();
    }

    @Test
    @DisplayName("queries findFavoritedServiceIds with exactly the SALON_SERVICE target type — "
            + "never SERVICE, the sibling MASTER-arm's target type")
    void should_queryWithSalonServiceTargetType_never_serviceTargetType() {
        UUID clientId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        SalonServiceCatalogResponse catalog = catalogWith(group("MANICURE", stub(serviceDefId)));
        Authentication client = authenticatedAs(clientId, Role.CLIENT);
        when(favoriteRepository.findFavoritedServiceIds(any(), any(), any())).thenReturn(Set.of());

        decorator.decorate(catalog, client);

        verify(favoriteRepository).findFavoritedServiceIds(
                eq(clientId), eq(FavoriteTargetType.SALON_SERVICE), eq(List.of(serviceDefId)));
        verify(favoriteRepository, never())
                .findFavoritedServiceIds(any(), eq(FavoriteTargetType.SERVICE), any());
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static Authentication authenticatedAs(UUID userId, Role role) {
        var authority = new SimpleGrantedAuthority(role.springRole);
        var token = new UsernamePasswordAuthenticationToken("user@beautica.test", null, List.of(authority));
        token.setDetails(userId);
        return token;
    }

    private static SalonServiceCatalogResponse catalogWith(SalonServiceCategoryGroup group) {
        return new SalonServiceCatalogResponse(List.of(group));
    }

    private static SalonServiceCategoryGroup group(String category, ServiceDefinitionResponse... services) {
        List<ServiceDefinitionResponse> list = List.of(services);
        return new SalonServiceCategoryGroup(category, category, list.size(), list);
    }

    private static ServiceDefinitionResponse stub(UUID id) {
        return new ServiceDefinitionResponse(
                id, "Manicure", null, "MANICURE", 60, 0, true, null, null, null, null,
                com.beautica.service.entity.PriceType.FIXED, new BigDecimal("350.00"), null, "350 ₴", null);
    }
}
