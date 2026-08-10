package com.beautica.service.service;

import com.beautica.auth.Role;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.SalonServiceCategoryGroup;
import com.beautica.service.dto.ServiceDefinitionResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Decorates a {@code salon-service-catalog} read with the CALLER's own {@code isFavorite}
 * flag — per request, AFTER the cache read (salon-service-favourites track, Phase C).
 *
 * <h2>Why this is a separate bean, invoked from the controller</h2>
 * {@code ServiceCatalogService.getSalonServiceCatalog} is {@code @Cacheable(value =
 * "salon-service-catalog", key = "#salonId", sync = true)} on a {@code permitAll} route, keyed by
 * {@code salonId} ALONE and shared across every caller including anonymous guests. Populating
 * {@code isFavorite} inside that method would store one client's wish list under the salon's
 * shared key and serve it, for up to the cache TTL, to every other client and guest — a
 * cross-client data leak.
 *
 * <p>This class is composed in the CONTROLLER, lexically outside the {@code @Cacheable} method
 * (see {@code ServiceController#getSalonServiceCatalog}), so the cached call always returns the
 * un-decorated (all-{@code null}) shape and this bean builds the caller-specific view on top of
 * it — exactly the pattern {@code MasterServiceFavoriteDecorator} already established for
 * {@code GET /masters/{masterId}/services}. A wrapper method INSIDE {@code ServiceCatalogService}
 * was deliberately rejected for the identical reason that decorator's javadoc gives: it would
 * self-invoke the cached method, bypassing the Spring AOP proxy and silently turning off caching
 * on a hot public route. See {@code SalonCatalogueFavoriteDecorationIT} for the build-failing
 * proof of the cross-client leak this class exists to prevent.
 *
 * <h2>Cost for non-CLIENT callers</h2>
 * Only a CLIENT principal can own {@code favorites} rows, so a SALON_OWNER/SALON_ADMIN/
 * SALON_MASTER/INDEPENDENT_MASTER — or an anonymous guest — browsing this route costs ZERO extra
 * queries: {@link #decorate} returns the input response untouched (every {@code isFavorite}
 * stays {@code null}), never a response rebuilt with {@code false}.
 */
@Component
@RequiredArgsConstructor
public class SalonServiceFavoriteDecorator {

    private final FavoriteRepository favoriteRepository;

    /**
     * Returns {@code catalog} untouched for an anonymous/non-CLIENT caller or a catalogue with no
     * services (short-circuit — no query with an empty {@code IN} list). For an authenticated
     * CLIENT, returns a NEW {@link SalonServiceCatalogResponse} — every group and every
     * {@link ServiceDefinitionResponse} inside it is a record and is never mutated — with
     * {@code isFavorite} set to whether each service definition's id is in that client's wish
     * list under {@link FavoriteTargetType#SALON_SERVICE}.
     */
    public SalonServiceCatalogResponse decorate(SalonServiceCatalogResponse catalog,
                                                 Authentication authentication) {
        List<SalonServiceCategoryGroup> groups = catalog.categories();
        if (groups.isEmpty()) {
            return catalog;
        }

        UUID clientId = resolveClientId(authentication);
        if (clientId == null) {
            return catalog;
        }

        List<UUID> serviceDefIds = groups.stream()
                .flatMap(group -> group.services().stream())
                .map(ServiceDefinitionResponse::id)
                .toList();
        if (serviceDefIds.isEmpty()) {
            return catalog;
        }

        Set<UUID> favoritedIds = favoriteRepository.findFavoritedServiceIds(
                clientId, FavoriteTargetType.SALON_SERVICE, serviceDefIds);

        List<SalonServiceCategoryGroup> decorated = groups.stream()
                .map(group -> decorateGroup(group, favoritedIds))
                .toList();
        return new SalonServiceCatalogResponse(decorated);
    }

    private SalonServiceCategoryGroup decorateGroup(SalonServiceCategoryGroup group,
                                                      Set<UUID> favoritedIds) {
        List<ServiceDefinitionResponse> services = group.services().stream()
                .map(response -> response.withIsFavorite(favoritedIds.contains(response.id())))
                .toList();
        return new SalonServiceCategoryGroup(
                group.category(), group.displayName(), group.count(), services);
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
