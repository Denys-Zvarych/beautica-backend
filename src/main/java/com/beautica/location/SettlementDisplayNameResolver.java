package com.beautica.location;

import com.beautica.location.repository.CityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a {@code city_id} to the display labels that are denormalised into the legacy
 * {@code city}/{@code region} text columns of {@code users} and {@code salons}, and to the
 * structured label parts ({@code settlementType}, ambiguous-only hromada) that user, salon and
 * master responses expose beside {@code cityId} so a client can compose the full settlement label.
 *
 * <p>The ONE place this lookup lives. It was private to {@code UserService}, and
 * {@code SalonService} wrote only {@code city_id} — so a salon (and the owner row synced from
 * it) kept a {@code null} or stale free-text city/region next to a new id, which mobile's
 * settlement field then displayed. Every write of a {@code city_id} must go through here, and
 * an EMPTY result must clear the labels (see {@code User}/{@code Salon}
 * {@code #applySettlementDisplayNames}) — never leave the old text beside the new id.
 *
 * <p><b>Caching.</b> Same static-reference-data rationale as the sibling {@code cityOblastId}
 * cache: KATOTTH rows are Flyway-seed-only, so a 24h TTL with no {@code @CacheEvict} path is
 * correct. Spring unwraps the {@link Optional}, so {@code unless = "#result == null"} skips an
 * unknown id (an enumeration of random UUIDs mints no entries), and {@code condition} keeps a
 * {@code null} id away from Caffeine, which rejects null keys. No {@code sync}: Spring forbids
 * it together with {@code unless}. The callers are single-row writes, the authenticated
 * {@code GET /users/me}, and the public salon/master detail reads — and those public reads run
 * inside their own {@code sync = true} {@code salon-detail}/{@code master-detail} entries, which
 * already collapse a stampede before it reaches this cache. Callers are other beans
 * ({@code UserService}, {@code SalonService}, {@code MasterService}), so the proxy is never
 * bypassed by self-invocation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementDisplayNameResolver {

    public static final String CACHE_SETTLEMENT_DISPLAY_NAMES = "settlementDisplayNames";

    private final CityRepository cityRepository;

    /**
     * @param cityId the settlement id being written, possibly {@code null}
     * @return the labels, or empty when {@code cityId} is {@code null} or unknown
     */
    @Cacheable(value = CACHE_SETTLEMENT_DISPLAY_NAMES, key = "#cityId",
            condition = "#cityId != null", unless = "#result == null")
    @Transactional(readOnly = true)
    public Optional<SettlementDisplayNames> resolve(UUID cityId) {
        if (cityId == null) {
            return Optional.empty();
        }
        Optional<SettlementDisplayNames> names = cityRepository.findDisplayNamesById(cityId);
        if (names.isEmpty()) {
            log.warn("Settlement not found for id={}, clearing city/region denorm", cityId);
        }
        return names;
    }

    /**
     * Batch form for list endpoints — ONE {@code IN (...)} query for every distinct id, never a
     * {@link #resolve(UUID)} per row (§E). Each value also carries the parent {@code oblastId}
     * ({@link KeyedSettlementDisplayNames#oblastId()}), so a list response needing both the
     * oblast id and the label parts ({@code GET /salons/mine}) pays one join, not two batches.
     *
     * <p>Uncached: a Spring {@code @Cacheable} cannot key a collection per element, and the one
     * query it issues is already bounded by the owner's salon list.
     *
     * <p>The returned map is a mutable {@link HashMap} on purpose: callers look it up with a
     * possibly-{@code null} {@code getCityId()}, which {@code Map.of()} would reject with an NPE.
     *
     * @param cityIds distinct city ids; {@code null} elements are ignored
     * @return a {@code cityId -> row} map; ids with no city row are absent
     */
    @Transactional(readOnly = true)
    public Map<UUID, KeyedSettlementDisplayNames> resolveAll(Collection<UUID> cityIds) {
        Map<UUID, KeyedSettlementDisplayNames> result = new HashMap<>();
        var nonNullIds = cityIds.stream().filter(Objects::nonNull).distinct().toList();
        if (nonNullIds.isEmpty()) {
            return result;
        }
        for (KeyedSettlementDisplayNames row : cityRepository.findDisplayNamesByIdIn(nonNullIds)) {
            result.put(row.cityId(), row);
        }
        return result;
    }
}
