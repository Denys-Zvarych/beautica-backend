package com.beautica.location;

import com.beautica.location.repository.CityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a {@code city_id} to the display labels that are denormalised into the legacy
 * {@code city}/{@code region} text columns of {@code users} and {@code salons}.
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
 * it together with {@code unless}, and every caller is an authenticated single-row write, not a
 * stampede shape. Callers are other beans ({@code UserService}, {@code SalonService}), so the
 * proxy is never bypassed by self-invocation.
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
}
