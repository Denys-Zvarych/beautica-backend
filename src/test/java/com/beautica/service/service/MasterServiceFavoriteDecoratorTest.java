package com.beautica.service.service;

import com.beautica.auth.Role;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.service.dto.MasterServiceResponse;
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
 * Unit coverage for {@link MasterServiceFavoriteDecorator} — the per-request, post-cache
 * decoration bean that is the whole point of Phase 32.1's structural fix (see the class javadoc
 * for why this must never be folded back into {@code ServiceCatalogService.getMasterServices}).
 *
 * <p>No Spring context — pure {@code @ExtendWith(MockitoExtension.class)}, {@link
 * FavoriteRepository} mocked. The cross-client leak proof itself (a real cache, a real HTTP round
 * trip) is {@code ServiceCatalogFavoriteCacheIT}; this class only pins the decorator's own
 * decision table (who gets queried, who gets {@code null}, what gets rebuilt).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("MasterServiceFavoriteDecorator")
class MasterServiceFavoriteDecoratorTest {

    @Mock
    private FavoriteRepository favoriteRepository;

    private MasterServiceFavoriteDecorator decorator;

    @BeforeEach
    void setUp() {
        // Built here, not as a field initializer: @Mock fields are injected by MockitoExtension
        // AFTER the test instance is constructed, so a field-initializer constructor call would
        // capture a still-null favoriteRepository.
        decorator = new MasterServiceFavoriteDecorator(favoriteRepository);
    }

    @Test
    @DisplayName("returns the list untouched (every isFavorite stays null) when Authentication is null")
    void should_returnNullFlag_when_authenticationIsNull() {
        List<MasterServiceResponse> services = List.of(stub(UUID.randomUUID()));

        List<MasterServiceResponse> result = decorator.decorate(services, null);

        assertThat(result).isSameAs(services);
        assertThat(result).allSatisfy(r -> assertThat(r.isFavorite()).isNull());
        verifyNoInteractions(favoriteRepository);
    }

    @Test
    @DisplayName("returns the list untouched when the caller is Spring Security's anonymous principal")
    void should_returnNullFlag_when_authenticationIsAnonymous() {
        List<MasterServiceResponse> services = List.of(stub(UUID.randomUUID()));
        Authentication anonymous = new AnonymousAuthenticationToken(
                "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS")));

        List<MasterServiceResponse> result = decorator.decorate(services, anonymous);

        assertThat(result).isSameAs(services);
        verifyNoInteractions(favoriteRepository);
    }

    @Test
    @DisplayName("returns the list untouched and issues ZERO queries when the principal is authenticated "
            + "but not a CLIENT — providers must pay nothing for a flag they can never have")
    void should_returnNullFlag_when_principalIsNotClient() {
        List<MasterServiceResponse> services = List.of(stub(UUID.randomUUID()));
        Authentication owner = authenticatedAs(UUID.randomUUID(), Role.SALON_OWNER);

        List<MasterServiceResponse> result = decorator.decorate(services, owner);

        assertThat(result).isSameAs(services);
        verifyNoInteractions(favoriteRepository);
    }

    @Test
    @DisplayName("flags only the ids the CLIENT has favourited — the rest come back false, never null")
    void should_flagOnlyFavouritedIds_when_clientHasSubsetFavourited() {
        UUID clientId = UUID.randomUUID();
        UUID favourited = UUID.randomUUID();
        UUID notFavourited = UUID.randomUUID();
        List<MasterServiceResponse> services = List.of(stub(favourited), stub(notFavourited));
        Authentication client = authenticatedAs(clientId, Role.CLIENT);
        when(favoriteRepository.findFavoritedServiceIds(eq(clientId), any()))
                .thenReturn(Set.of(favourited));

        List<MasterServiceResponse> result = decorator.decorate(services, client);

        assertThat(result)
                .filteredOn(r -> r.id().equals(favourited))
                .singleElement()
                .extracting(MasterServiceResponse::isFavorite)
                .isEqualTo(true);
        assertThat(result)
                .filteredOn(r -> r.id().equals(notFavourited))
                .singleElement()
                .extracting(MasterServiceResponse::isFavorite)
                .isEqualTo(false);
    }

    @Test
    @DisplayName("does not query the repository when the service list is empty (D5 short-circuit)")
    void should_notQuery_when_serviceListEmpty() {
        Authentication client = authenticatedAs(UUID.randomUUID(), Role.CLIENT);

        List<MasterServiceResponse> result = decorator.decorate(List.of(), client);

        assertThat(result).isEmpty();
        verify(favoriteRepository, never()).findFavoritedServiceIds(any(), any());
    }

    @Test
    @DisplayName("returns NEW record instances for a CLIENT caller — the input list and its elements "
            + "are never mutated, since a record cannot be")
    void should_returnNewInstances_when_decorating() {
        UUID clientId = UUID.randomUUID();
        MasterServiceResponse original = stub(UUID.randomUUID());
        List<MasterServiceResponse> services = List.of(original);
        Authentication client = authenticatedAs(clientId, Role.CLIENT);
        when(favoriteRepository.findFavoritedServiceIds(eq(clientId), any())).thenReturn(Set.of());

        List<MasterServiceResponse> result = decorator.decorate(services, client);

        assertThat(result).isNotSameAs(services);
        assertThat(result.get(0)).isNotSameAs(original);
        assertThat(original.isFavorite())
                .as("the original record must be left exactly as it was — records cannot be mutated, "
                        + "but this pins the invariant explicitly")
                .isNull();
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private static Authentication authenticatedAs(UUID userId, Role role) {
        var authority = new SimpleGrantedAuthority(role.springRole);
        var token = new UsernamePasswordAuthenticationToken("user@beautica.test", null, List.of(authority));
        token.setDetails(userId);
        return token;
    }

    private static MasterServiceResponse stub(UUID id) {
        return new MasterServiceResponse(
                id, UUID.randomUUID(), null, null, null,
                new BigDecimal("350.00"), 60, true, null, null,
                null, "350 ₴", null, null, null, null);
    }
}
