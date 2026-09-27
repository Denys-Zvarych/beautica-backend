package com.beautica.location.service;

import com.beautica.location.dto.CityDistrictResponse;
import com.beautica.location.dto.CityResponse;
import com.beautica.location.dto.OblastResponse;
import com.beautica.location.entity.City;
import com.beautica.location.entity.Oblast;
import com.beautica.location.entity.SettlementType;
import com.beautica.location.repository.CityDistrictRepository;
import com.beautica.location.repository.CityRepository;
import com.beautica.location.repository.OblastRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Read-only query service for the KATOTTH locality taxonomy that backs the
 * (future) mobile cascading picker: oblast → city → urban district.
 *
 * <p><strong>Caching:</strong> the taxonomy is static reference data — rows
 * are written exclusively by Flyway seed migrations (Phase 10.2) and never
 * mutate at runtime. Each read is therefore {@code @Cacheable} with a
 * long-lived TTL and <em>no eviction path</em>: the only invalidation is JVM
 * restart (a fresh deploy, which is also the only time the data can change).
 * This is intentional — there is no write path to register a
 * {@code @CacheEvict} against (§F is satisfied: no write path exists, so no
 * matching eviction is required), and the locality repositories expose no
 * mutation methods.
 *
 * <p>The three picker reads are {@code @Cacheable(sync = true)}: these back the
 * unauthenticated cascading-picker endpoint, so on a cold start (or once the
 * 24-hour TTL lapses) a popular key — e.g. the single {@code listOblasts}
 * entry, or Kyiv's districts — would otherwise let every concurrent request
 * run the same DB query simultaneously. {@code sync = true} collapses that
 * per-key stampede to a single loader (§F.7 — the matching gap to the
 * {@code search:*} caches, which already use {@code sync = true}). It changes
 * no result, only loader concurrency. {@link #resolveCityOblastId(UUID)} is the
 * deliberate exception — see its own Javadoc.
 *
 * <p><strong>{@link #resolveCityOblastId(UUID)}</strong> (Phase 240 perf follow-up) is the
 * SHARED {@code cityId → oblastId} resolver for {@code SalonService} and {@code MasterService}
 * (REUSE-FIRST — one cached implementation, not two private per-service copies). Same static
 * KATOTTH reference data as the three reads above, so it follows the identical no-eviction, 24h
 * TTL contract.
 *
 * <p><strong>No N+1 (§E):</strong> {@code listCitiesByOblast} computes
 * {@code hasDistricts} from a single set-based query
 * ({@code findCityIdsWithDistrictsByOblastId}) plus in-memory
 * {@link Set#contains} — never a per-row {@code existsByCityId} loop. Parent
 * ids ({@code oblastId} / {@code cityId}) are taken from the request path and
 * passed into the DTO factories, so the LAZY {@code City#oblast} and
 * {@code CityDistrict#city} associations are never traversed.
 *
 * <p><strong>Read-only surface:</strong> only finder/query methods are used;
 * no {@code save}/{@code delete}. Locality writes remain Flyway-only.
 */
@Service
@RequiredArgsConstructor
public class LocationQueryService {

    static final String CACHE_OBLASTS = "locationOblasts";
    static final String CACHE_CITIES_BY_OBLAST = "locationCitiesByOblast";
    static final String CACHE_DISTRICTS_BY_CITY = "locationDistrictsByCity";
    static final String CACHE_CITY_OBLAST_ID = "cityOblastId";

    private final OblastRepository oblastRepository;
    private final CityRepository cityRepository;
    private final CityDistrictRepository cityDistrictRepository;

    /**
     * The oblasts the cascading picker offers, ordered by Ukrainian name.
     *
     * <p><b>Occupied territory (corrected in the Phase 325 follow-up).</b> This Javadoc used to
     * say occupied territories are "already excluded at the data layer (V53)". V170 falsified
     * that: V53 filtered at OBLAST level, which both dropped the free Донецька/Луганська
     * settlements and kept seventeen genuinely occupied cities under the wholesale-kept
     * Запорізька/Херсонська. The ban is now held at SETTLEMENT level and at the import SOURCE —
     * V170 deletes those 17 rows and V171 loads a list Phase 324's 2 958-code exclusion set was
     * already subtracted from. There is no {@code occupation_status} column and no runtime
     * predicate, so the guarantee is an ABSENCE invariant, not something this method enforces.
     * Still no territory logic here — but for a different and stronger reason than the old
     * comment claimed.
     *
     * <p><b>Bounded to oblasts that hold at least one {@link SettlementType#CITY}.</b> Tier 2
     * ({@link #listCitiesByOblast(UUID)}) is CITY-only, and Луганська's 13 free settlements are
     * all villages/селища — so before this bound the picker offered an oblast whose next screen
     * was unconditionally {@code []}, with no way forward and nothing explaining why. The
     * cascade is a transitional surface (Phase 326's settlement search replaces it); until then
     * an oblast that leads nowhere is strictly worse than one not offered. Costs one row:
     * 24 oblasts instead of 25.
     */
    @Cacheable(value = CACHE_OBLASTS, sync = true)
    @Transactional(readOnly = true)
    public List<OblastResponse> listOblasts() {
        return oblastRepository.findWithSettlementTypeOrderByNameUkAsc(SettlementType.CITY).stream()
                .map(OblastResponse::from)
                .toList();
    }

    /**
     * Cities in the given oblast, ordered by Ukrainian name, each carrying a
     * set-based {@code hasDistricts} flag.
     *
     * <p>Exactly two queries regardless of city count: the ordered city list
     * and the distinct set of city ids that have urban districts.
     *
     * <p><b>Bounded to {@link SettlementType#CITY} (Phase 325 follow-up).</b> This is the second
     * tier of the legacy oblast → city → district cascade, and its contract has always been
     * "cities". V170/V171 widened the underlying table from 356 category-M cities to 25 698
     * settlements, so the unfiltered finder silently began shipping every village too — 1 928 rows
     * for the largest oblast, a ~100× payload growth on a {@code permitAll} endpoint. Restoring the
     * type predicate returns the response to its pre-V170 magnitude (44 rows at worst) without
     * truncating it, and the repository's non-predicate variant is deleted so the bound cannot be
     * bypassed by reaching for the shorter finder (§E-1). The full-settlement surface is Phase
     * 326's dedicated search endpoint, not this one.
     */
    @Cacheable(value = CACHE_CITIES_BY_OBLAST, key = "#oblastId", sync = true)
    @Transactional(readOnly = true)
    public List<CityResponse> listCitiesByOblast(UUID oblastId) {
        Set<UUID> cityIdsWithDistricts =
                cityDistrictRepository.findCityIdsWithDistrictsByOblastId(oblastId);

        return cityRepository
                .findByOblastIdAndSettlementTypeOrderByNameUkAsc(oblastId, SettlementType.CITY)
                .stream()
                .map(city -> CityResponse.from(
                        city,
                        oblastId,
                        cityIdsWithDistricts.contains(city.getId())))
                .toList();
    }

    /**
     * Urban districts in the given city, ordered by Ukrainian name. Cities
     * without urban districts return an empty list.
     */
    @Cacheable(value = CACHE_DISTRICTS_BY_CITY, key = "#cityId", sync = true)
    @Transactional(readOnly = true)
    public List<CityDistrictResponse> listDistrictsByCity(UUID cityId) {
        return cityDistrictRepository.findByCityIdOrderByNameUkAsc(cityId).stream()
                .map(district -> CityDistrictResponse.from(district, cityId))
                .toList();
    }

    /**
     * Resolves the parent oblast id of a single city by its id. SHARED cached resolver for
     * {@code SalonService#resolveOblastId}, {@code MasterService#resolveOblastId} and
     * {@code UserService#getProfile} — all three previously ran an uncached per-call lookup
     * (Phase 240 perf MEDIUM finding; {@code UserService} was the Phase 325 straggler), even
     * though this is the exact same class of static reference data the three reads above already
     * cache with a 24h TTL and no eviction path: Flyway-seed-only, never mutated at runtime.
     *
     * <p><b>The key space is NOT the settlement table.</b> Every caller keys on a STORED
     * {@code salons.city_id} / {@code users.city_id}, so the reachable keys are
     * cities-that-host-a-provider — a number in the hundreds that tracks provider growth, not the
     * 25 698 rows V170+V171 loaded. {@code CacheConfig} is sized against that, not against the
     * table.
     *
     * <p>Callers MUST guard {@code cityId == null} themselves before invoking this method — the
     * underlying Caffeine cache cannot hold a {@code null} key, so a null-cityId call must never
     * reach the {@code @Cacheable} proxy in the first place (mirrors the pre-existing guard both
     * callers already had in front of their own {@code findByIdWithOblast} call).
     *
     * <p><b>{@code unless = "#result == null"} — negative lookups are NOT cached</b> (Phase 325
     * security LOW). Without it Spring stores a {@code NullValue} for every id that resolves to
     * nothing, so an authenticated caller submitting random UUIDs could fill the whole bounded
     * cache with misses and evict the live entries this cache exists to hold. A miss is also the
     * cheap case: every production caller has already established the city exists
     * ({@code LocalityWriteValidator} on the write paths, a stored {@code city_id} on the read
     * paths), so {@code null} means "stale or forged id", never a warm-path result worth keeping.
     *
     * <p><b>Why {@code sync = true} is absent here and present on the three reads above:</b>
     * Spring rejects the combination outright — {@code CacheAspectSupport} throws
     * {@code "@Cacheable(sync=true) does not support unless attribute"} at first invocation, so
     * this is a hard either/or, not a preference. The three reads above keep {@code sync} because
     * they back the {@code permitAll} picker endpoint where one popular key (the oblast list) is
     * a genuine stampede surface (§F-7).
     *
     * <p>This resolver does not need {@code sync}, and the reason is <em>not</em> reachability:
     * it IS reached unauthenticated, from {@code GET /api/v1/salons/{salonId}} and
     * {@code GET /api/v1/masters/{masterId}}, both {@code permitAll} in {@code SecurityConfig}.
     * The reason is NESTING. Every read path that reaches this method is already inside an OUTER
     * {@code sync = true} cache, which collapses the herd one level up —
     * {@code SalonService#getPublicSalon} ({@code salon-detail}),
     * {@code MasterService#getMasterDetail(UUID)} ({@code master-detail}),
     * {@code MasterService#findMyMasterDetail} ({@code master-detail-by-user}) and
     * {@code UserService#getProfile} ({@code userProfile}). On a cold key exactly one thread per
     * salonId / masterId / userId gets past that outer loader, so at most one call per outer key
     * ever arrives here; every other concurrent request is handed the finished DTO and never
     * touches this cache at all. The only callers NOT so nested are authenticated single-actor
     * writes (salon create/update, master create/reactivate through the
     * {@code getMasterDetail(Master)} entity overload) — one request mutating one row, which is
     * not a stampede shape.
     *
     * <p>So dropping {@code sync} here is a net positive, not a tolerated cost. Per
     * {@code CacheConfig}'s class Javadoc, {@code spring.threads.virtual.enabled: true} plus a
     * virtual thread blocking inside {@code ConcurrentHashMap.compute}'s {@code synchronized} bin
     * lock PINS its carrier on Java 21 — a JDK-level issue fixed only by JEP 491 / JDK 24 — which
     * is why {@code sync = true} "should stay reserved for genuinely hot public keys". Not taking
     * it on a per-city key retires that documented hazard rather than merely tolerating its
     * absence.
     *
     * <p>Before reopening this trade-off, check BOTH halves: the {@code unless} guard above is
     * what FORBIDS {@code sync}, and the four outer caches are what make it UNNECESSARY. Adding a
     * caller that is not nested inside one of them invalidates this argument.
     *
     * @param cityId a non-null city id
     * @return the PK of the parent {@link Oblast}, or {@code null} when {@code cityId} does not
     *         resolve to a known city
     */
    @Cacheable(value = CACHE_CITY_OBLAST_ID, key = "#cityId", unless = "#result == null")
    @Transactional(readOnly = true)
    public UUID resolveCityOblastId(UUID cityId) {
        return cityRepository.findByIdWithOblast(cityId)
                .map(City::getOblast)
                .map(Oblast::getId)
                .orElse(null);
    }
}
