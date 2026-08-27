package com.beautica.service.service;

import com.beautica.service.repository.MasterServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public seam exposing "what platform categories does this provider actually OFFER?" to features
 * outside {@code service} — the offering-derived counterpart of
 * {@link PlatformCategoryLabelResolver} for the favourites category axis, and the direct
 * replacement for the {@code booking} feature's client-booking-history lookup on that read
 * path. Cross-feature access goes through this public service type, never through
 * {@link MasterServiceRepository} directly.
 *
 * <h3>Why it exists</h3>
 * The favourites screen filters saved providers by service category. That used to be derived
 * from client booking history; it is now derived from what the provider actually performs — see
 * {@code com.beautica.favorite.service.FavoriteCategoryResolver}'s class javadoc for the full
 * product-decision rationale. Deriving "what a provider offers" needs {@code master_services} and
 * {@code service_definitions}, which the {@code favorite} feature has no business reading
 * directly. This component is the whole of that dependency: two methods, both batched, both
 * returning plain scalars.
 *
 * <h3>Batched by contract, not by accident</h3>
 * Each method issues <b>exactly one</b> statement for a whole page and is bounded by the page
 * size the caller passes (§E-3). Neither ever runs per row. See
 * {@link MasterServiceRepository#findDistinctOfferedCategoriesByMasterIds} and
 * {@link MasterServiceRepository#findDistinctOfferedCategoriesBySalonIds} for the query text, the
 * indexes involved, and the measured plans.
 *
 * <h3>Absence, not null</h3>
 * A provider with no active categorisable service is simply <b>missing</b> from the returned map
 * (never present with an empty list) — callers read the absence with {@link Map#getOrDefault}.
 *
 * <h3>Zero entity hydration (§I)</h3>
 * Both queries project scalars only. Nothing here loads a {@code MasterServiceAssignment} or
 * {@code ServiceDefinition} into the persistence context, so no association can lazily escape
 * into a response DTO.
 *
 * <h3>Not client-scoped</h3>
 * Unlike the booking-history derivation it replaced, "what a provider offers" is not a fact about
 * any one client — it is the same answer for every caller. Neither method here takes a
 * {@code clientId}.
 */
@Component
@RequiredArgsConstructor
public class OfferedCategoryLookup {

    private final MasterServiceRepository masterServiceRepository;

    /**
     * The distinct platform category codes ({@code platform_categories.name}, e.g.
     * {@code MANICURE}) of every ACTIVE service each of {@code masterIds} performs via an ACTIVE
     * {@link com.beautica.service.entity.MasterServiceAssignment}.
     *
     * @param masterIds the page's master ids; an empty collection short-circuits to an empty map
     *                  without touching the database (an empty {@code IN ()} is a SQL syntax
     *                  error, not an empty result)
     * @return master id → distinct category codes, for those masters that offer at least one;
     *         never {@code null}
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<String>> offeredCategoriesByMaster(Collection<UUID> masterIds) {
        if (masterIds == null || masterIds.isEmpty()) {
            return Map.of();
        }
        return toCategoryListMap(
                masterServiceRepository.findDistinctOfferedCategoriesByMasterIds(masterIds));
    }

    /**
     * Salon counterpart of {@link #offeredCategoriesByMaster(Collection)}, scoped to the LOCKED
     * "salon offering = master-performed only" domain rule: only categories of an active,
     * salon-owned service that at least one currently ACTIVE master of that salon performs.
     *
     * @param salonIds the page's salon ids
     * @return salon id → distinct category codes, for those salons that offer at least one; never
     *         {@code null}
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<String>> offeredCategoriesBySalon(Collection<UUID> salonIds) {
        if (salonIds == null || salonIds.isEmpty()) {
            return Map.of();
        }
        return toCategoryListMap(
                masterServiceRepository.findDistinctOfferedCategoriesBySalonIds(salonIds));
    }

    /**
     * Folds the {@code [providerId, categoryCode]} projection into a provider → codes map,
     * preserving the order rows arrived in (query order, not caller order) — the resolver above
     * re-orders by display label anyway, so this is just a stable, deterministic fold.
     */
    private static Map<UUID, List<String>> toCategoryListMap(List<Object[]> rows) {
        Map<UUID, List<String>> byProvider = new LinkedHashMap<>();
        for (Object[] row : rows) {
            UUID providerId = (UUID) row[0];
            String category = (String) row[1];
            byProvider.computeIfAbsent(providerId, id -> new ArrayList<>()).add(category);
        }
        return byProvider;
    }
}
