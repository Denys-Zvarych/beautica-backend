package com.beautica.location.service;

import com.beautica.location.dto.CityDistrictResponse;
import com.beautica.location.dto.CityResponse;
import com.beautica.location.dto.OblastResponse;
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

    private final OblastRepository oblastRepository;
    private final CityRepository cityRepository;
    private final CityDistrictRepository cityDistrictRepository;

    /**
     * All serviced oblasts ordered by Ukrainian name. Occupied territories are
     * already excluded at the data layer (V53), so this naturally returns only
     * serviced oblasts — no territory logic lives here.
     */
    @Cacheable(CACHE_OBLASTS)
    @Transactional(readOnly = true)
    public List<OblastResponse> listOblasts() {
        return oblastRepository.findAllByOrderByNameUkAsc().stream()
                .map(OblastResponse::from)
                .toList();
    }

    /**
     * Cities in the given oblast, ordered by Ukrainian name, each carrying a
     * set-based {@code hasDistricts} flag.
     *
     * <p>Exactly two queries regardless of city count: the ordered city list
     * and the distinct set of city ids that have urban districts.
     */
    @Cacheable(value = CACHE_CITIES_BY_OBLAST, key = "#oblastId")
    @Transactional(readOnly = true)
    public List<CityResponse> listCitiesByOblast(UUID oblastId) {
        Set<UUID> cityIdsWithDistricts =
                cityDistrictRepository.findCityIdsWithDistrictsByOblastId(oblastId);

        return cityRepository.findByOblastIdOrderByNameUkAsc(oblastId).stream()
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
    @Cacheable(value = CACHE_DISTRICTS_BY_CITY, key = "#cityId")
    @Transactional(readOnly = true)
    public List<CityDistrictResponse> listDistrictsByCity(UUID cityId) {
        return cityDistrictRepository.findByCityIdOrderByNameUkAsc(cityId).stream()
                .map(district -> CityDistrictResponse.from(district, cityId))
                .toList();
    }
}
