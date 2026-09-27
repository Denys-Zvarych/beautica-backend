package com.beautica.location;

import com.beautica.location.repository.CityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Cached, single-query resolution seam for the Phase 10.6 most-specific-node
 * write rule.
 *
 * <p><strong>Why this exists (perf):</strong> every provider profile save
 * ({@code SalonService.updateSalon},
 * {@code UserService.applyLocality} for {@code INDEPENDENT_MASTER}) used to
 * fire <em>three</em> sequential, uncached existence round-trips against the
 * fully-static KATOTTH taxonomy ({@code existsById},
 * {@code existsByCityId}, {@code existsByIdAndCityId}). This seam collapses
 * those three into:
 * <ul>
 *   <li><strong>one</strong> fused query
 *       ({@link CityRepository#resolveTaxonomyFacts}) on a cold cache, and</li>
 *   <li><strong>zero</strong> queries on a warm cache.</li>
 * </ul>
 *
 * <p><strong>Caching contract (identical to the {@code location*} read
 * caches, §F):</strong> the taxonomy is static reference data — rows are
 * written exclusively by Flyway seed migrations and never mutate at runtime.
 * The cache therefore has a long 24-hour TTL and <em>no eviction path</em>:
 * the only invalidation is JVM restart (a redeploy — also the only time the
 * seed can change). There is no write path to register a {@code @CacheEvict}
 * against, so §F's "every {@code @Cacheable} needs a matching evict" rule is
 * satisfied by the documented absence of any mutation.
 *
 * <p>The cache key is the full {@code (cityId, districtId)} pair: the same
 * city resolved once with a {@code null} district and once with a concrete
 * district are distinct, correct cache entries.
 *
 * <p><strong>{@code maximumSize = 600} is an admission FENCE, not a capacity
 * estimate.</strong> This Javadoc used to justify it as "the taxonomy is small
 * (~600 cities + districts)"; Phase 325's V170/V171 widened {@code cities} to
 * 25 698 rows and killed that premise. The size is deliberately NOT raised to
 * match, and that is the whole point: unlike the {@code location*} caches,
 * whose keys come from stored FKs, this key comes straight off a CLIENT
 * REQUEST BODY on the profile/salon write paths, and the "city does not exist"
 * verdict is itself a cacheable result — so a caller posting random UUIDs
 * mints a fresh entry on every request. A cap sized to the data would be a cap
 * sized to the attacker. Exceeding the fence costs one 0.06 ms Index Only Scan
 * (6 shared buffers) on the next write, which is why a tight fence is the
 * right trade here.
 *
 * <p>This class is a pure read-only lookup: no {@code save}/{@code delete},
 * and the validator semantics (which exception, which message, which
 * condition) live entirely in {@link LocalityWriteValidator} — this seam only
 * supplies the booleans.
 */
@Service
@RequiredArgsConstructor
public class LocalityTaxonomyLookup {

    static final String CACHE_TAXONOMY_FACTS = "localityTaxonomyFacts";

    private final CityRepository cityRepository;

    /**
     * Resolves, in at most one query (zero when warm-cached), the three facts
     * the most-specific-node rule needs about a submitted {@code (cityId,
     * districtId)} pair.
     *
     * <p>Callers must pass a non-{@code null} {@code cityId}
     * ({@link LocalityWriteValidator} short-circuits a {@code null} city
     * before reaching this seam). {@code districtId} may be {@code null}, in
     * which case {@link LocalityFacts#districtBelongsToCity()} is
     * {@code false} by construction (no district was supplied to check).
     *
     * @param cityId     submitted city PK (non-{@code null})
     * @param districtId submitted district PK, or {@code null}
     * @return the resolved taxonomy facts; {@link LocalityFacts#cityExists()}
     *         is {@code false} when no city row matched {@code cityId}
     */
    // sync = true: on the 24h-TTL cold/expiry miss for a popular (city, district) pair, only
    // ONE thread runs the fused taxonomy query while concurrent provider saves wait for it,
    // instead of a herd of identical round-trips (Anti-Bug §F-7).
    @Cacheable(value = CACHE_TAXONOMY_FACTS, key = "{#cityId, #districtId}", sync = true)
    @Transactional(readOnly = true)
    public LocalityFacts resolve(UUID cityId, UUID districtId) {
        return cityRepository.resolveTaxonomyFacts(cityId, districtId)
                .map(row -> new LocalityFacts(
                        true,
                        Boolean.TRUE.equals(row.getCityHasDistricts()),
                        Boolean.TRUE.equals(row.getDistrictBelongsToCity())))
                .orElse(LocalityFacts.CITY_ABSENT);
    }

    /**
     * Immutable result of {@link #resolve(UUID, UUID)} — the three booleans
     * the most-specific-node rule branches on.
     *
     * @param cityExists            whether a city row matched the submitted
     *                              {@code cityId}
     * @param cityHasDistricts      whether that city defines any urban
     *                              districts (drives "district required iff
     *                              the city has districts")
     * @param districtBelongsToCity whether the submitted district both exists
     *                              and is a child of the submitted city;
     *                              always {@code false} when no district was
     *                              submitted
     */
    public record LocalityFacts(boolean cityExists,
                                boolean cityHasDistricts,
                                boolean districtBelongsToCity) {

        /** Sentinel for "the submitted city does not exist". */
        static final LocalityFacts CITY_ABSENT = new LocalityFacts(false, false, false);
    }
}
