package com.beautica.favorite.service;

import com.beautica.auth.Role;
import com.beautica.common.security.AuthenticationUtils;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.salon.dto.PublicSalonResponse;
import com.beautica.search.dto.MasterSearchResult;
import com.beautica.search.dto.SalonSearchResult;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Decorates the master/salon public reads with the CALLER's own {@code isFavorite} flag — per
 * request, AFTER the cache read. The provider-level sibling of the Phase 243 service decorators
 * ({@code MasterServiceFavoriteDecorator}, {@code SalonServiceFavoriteDecorator}); same shape,
 * same reasons.
 *
 * <h2>Why this is a separate bean, invoked from the controller</h2>
 * Every response it decorates comes out of a cache shared by all callers, anonymous guests
 * included: {@code master-detail} (key {@code masterId}), {@code salon-detail} (key
 * {@code salonId}) and the {@code search:*} page caches (key = filters + page). Setting
 * {@code isFavorite} inside those cached methods would store one client's favourites under a
 * shared key and serve them to every other caller for the TTL. So the cached methods always
 * return {@code isFavorite = false}, and the controller composes this bean on top of the cached
 * value. It must never be called from inside {@code MasterService}, {@code SalonService} or
 * {@code SearchService}: a self-invoked wrapper would also bypass the cache proxy.
 *
 * <h2>Cost</h2>
 * Only a CLIENT can own {@code favorites} rows, so anonymous and non-CLIENT callers cost ZERO
 * queries and get the input back untouched ({@code isFavorite = false}). A CLIENT costs one
 * indexed lookup per request: an index-only {@code EXISTS} for a profile
 * ({@code FavoriteRepository#isFavorited}), and ONE {@code IN (...)} query for
 * a whole search page (backed by {@code uq_favorite (client_id, target_type, target_id)}) — never
 * one query per row.
 */
@Component
@RequiredArgsConstructor
public class ProviderFavoriteDecorator {

    private final FavoriteRepository favoriteRepository;

    /** The master public profile, flagged for the caller. */
    public MasterDetailResponse decorate(MasterDetailResponse detail, Authentication authentication) {
        UUID clientId = resolveClientId(authentication);
        if (clientId == null) {
            return detail;
        }
        // Not favourited → the cached input already says false; no copy needed.
        return favoriteRepository.isFavorited(clientId, FavoriteTargetType.MASTER, detail.masterId())
                ? detail.withIsFavorite(true)
                : detail;
    }

    /** The salon public profile, flagged for the caller. */
    public PublicSalonResponse decorate(PublicSalonResponse salon, Authentication authentication) {
        UUID clientId = resolveClientId(authentication);
        if (clientId == null) {
            return salon;
        }
        return favoriteRepository.isFavorited(clientId, FavoriteTargetType.SALON, salon.id())
                ? salon.withIsFavorite(true)
                : salon;
    }

    /** A master search page, flagged for the caller with one batched query. */
    public List<MasterSearchResult> decorateMasters(List<MasterSearchResult> results,
                                                    Authentication authentication) {
        return decorateAll(results, authentication, FavoriteTargetType.MASTER,
                MasterSearchResult::masterId, MasterSearchResult::withIsFavorite);
    }

    /** A salon search page, flagged for the caller with one batched query. */
    public List<SalonSearchResult> decorateSalons(List<SalonSearchResult> results,
                                                  Authentication authentication) {
        return decorateAll(results, authentication, FavoriteTargetType.SALON,
                SalonSearchResult::salonId, SalonSearchResult::withIsFavorite);
    }

    /**
     * Returns {@code items} untouched for an empty page (no query with an empty {@code IN} list),
     * an anonymous/non-CLIENT caller, or a page with no favourited card; otherwise a NEW list of
     * NEW records, flagged from ONE
     * {@code IN (...)} query over the page's ids. The input records are never mutated, so the
     * cached page stays caller-agnostic.
     */
    private <T> List<T> decorateAll(List<T> items, Authentication authentication,
                                    FavoriteTargetType targetType, Function<T, UUID> idOf,
                                    BiFunction<T, Boolean, T> withIsFavorite) {
        if (items.isEmpty()) {
            return items;
        }
        UUID clientId = resolveClientId(authentication);
        if (clientId == null) {
            return items;
        }
        List<UUID> ids = items.stream().map(idOf).toList();
        // Despite its name, this finder is target-type-generic (Phase 243): the same
        // `targetId IN :targetIds` lookup, here for MASTER / SALON rows.
        Set<UUID> favoritedIds = favoriteRepository.findFavoritedTargetIds(clientId, targetType, ids);
        if (favoritedIds.isEmpty()) {
            // Nothing on this page is favourited: every cached card already says false.
            return items;
        }
        return items.stream()
                .map(item -> withIsFavorite.apply(item, favoritedIds.contains(idOf.apply(item))))
                .toList();
    }

    /**
     * The caller's user id only when they are an authenticated CLIENT; {@code null} for
     * anonymous, unauthenticated or any other role, so no {@code favorites} query runs for them.
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
