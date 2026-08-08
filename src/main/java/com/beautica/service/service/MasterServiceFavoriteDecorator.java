package com.beautica.service.service;

import com.beautica.auth.Role;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.service.dto.MasterServiceResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Decorates a {@code masterServices} read with the CALLER's own {@code isFavorite} flag —
 * per request, AFTER the cache read (Phase 32.1).
 *
 * <h2>Why this is a separate bean, invoked from the controller</h2>
 * {@code ServiceCatalogService.getMasterServices} is {@code @Cacheable(value = "masterServices",
 * key = "#masterId", sync = true)} on a {@code permitAll} route, keyed by {@code masterId} ALONE
 * and shared across every caller including anonymous guests. Populating {@code isFavorite} inside
 * that method would store one client's wish list under the master's shared key and serve it, for
 * up to the 10-minute TTL, to every other client and guest — a cross-client data leak.
 *
 * <p>This class is composed in the CONTROLLER, lexically outside the {@code @Cacheable} method,
 * so the cached call always returns the un-decorated (all-{@code null}) shape and this bean builds
 * the caller-specific view on top of it. A {@code getMasterServicesForCaller(...)} wrapper INSIDE
 * {@code ServiceCatalogService} was deliberately rejected: it would self-invoke
 * {@code getMasterServices}, bypassing the Spring AOP proxy and silently turning off caching on a
 * hot public route — failing open on performance while looking correct, the worst available
 * outcome. See {@code ServiceCatalogFavoriteCacheIT} for the build-failing proof of the leak this
 * class exists to prevent.
 *
 * <h2>Cost for non-CLIENT callers</h2>
 * Only a CLIENT principal can own {@code favorites} rows, so a SALON_OWNER/SALON_ADMIN/
 * SALON_MASTER/INDEPENDENT_MASTER — or an anonymous guest — browsing this route costs ZERO extra
 * queries: {@link #decorate} returns the input list untouched (every {@code isFavorite} stays
 * {@code null}), never a list rebuilt with {@code false}.
 */
@Component
@RequiredArgsConstructor
public class MasterServiceFavoriteDecorator {

    private final FavoriteRepository favoriteRepository;

    /**
     * Returns {@code services} untouched for an anonymous/non-CLIENT caller or an empty list
     * (D5 short-circuit — no query with an empty {@code IN} list). For an authenticated CLIENT,
     * returns a NEW list of NEW {@link MasterServiceResponse} instances — the input list and its
     * elements are records and are never mutated — with {@code isFavorite} set to whether each
     * row's id is in that client's wish list.
     */
    public List<MasterServiceResponse> decorate(List<MasterServiceResponse> services,
                                                 Authentication authentication) {
        if (services.isEmpty()) {
            return services;
        }

        UUID clientId = resolveClientId(authentication);
        if (clientId == null) {
            return services;
        }

        List<UUID> targetIds = services.stream().map(MasterServiceResponse::id).toList();
        Set<UUID> favoritedIds = favoriteRepository.findFavoritedServiceIds(clientId, targetIds);

        return services.stream()
                .map(response -> response.withIsFavorite(favoritedIds.contains(response.id())))
                .toList();
    }

    /**
     * Returns the caller's user id only when they are an authenticated CLIENT — {@code null} for
     * anonymous, unauthenticated, or any other role, so {@link #decorate} never queries
     * {@code favorites} on their behalf (only CLIENTs can own a row there).
     */
    private UUID resolveClientId(Authentication authentication) {
        if (authentication == null || !isClient(authentication)) {
            return null;
        }
        return AuthenticationUtils.userIdOrNull(authentication);
    }

    private boolean isClient(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> Role.CLIENT.springRole.equals(authority.getAuthority()));
    }
}
