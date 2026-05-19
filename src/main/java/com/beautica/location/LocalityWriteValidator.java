package com.beautica.location;

import com.beautica.common.exception.BusinessException;
import com.beautica.location.repository.CityDistrictRepository;
import com.beautica.location.repository.CityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Enforces the Phase 10.6 <em>most-specific-available-node</em> write rule and
 * the per-role locality model when a profile is saved.
 *
 * <h3>Per-role contract (locked — Phase 10.6 matrix)</h3>
 * <ul>
 *   <li><b>Providers</b> (the salon for {@code SALON_OWNER}; the {@code User}
 *       row for {@code INDEPENDENT_MASTER}) — {@code city_id} is mandatory; a
 *       {@code district_id} is mandatory when (and only when) the chosen city
 *       defines urban districts. Validate via
 *       {@link #validateProviderLocality(LocalityWriteInput)}.</li>
 *   <li><b>CLIENT</b> — locality is an optional <em>discovery-filter
 *       default</em>, never a physical address; absence never blocks the save
 *       (and OTP registration is unaffected). Only referential integrity is
 *       checked, and only when values are supplied. Validate via
 *       {@link #validateClientLocality(LocalityWriteInput)}.</li>
 *   <li><b>SALON_MASTER / SALON_ADMIN</b> — no personal locality write path;
 *       these roles never call this validator.</li>
 * </ul>
 *
 * <p>All failures are surfaced as {@link BusinessException} (HTTP 400 via the
 * existing {@code GlobalExceptionHandler}) for a consistent error shape. The
 * messages are intentionally generic (no enum constants, no SQL, no internal
 * IDs) — they only name the offending field.
 *
 * <p>This rule is enforced here in the service/validation layer, <em>not</em>
 * by a NOT&nbsp;NULL constraint: cities without urban districts legitimately
 * persist a {@code null} {@code district_id}, and {@code city_id} is promoted
 * to "required going forward for providers" without any data-altering
 * migration (Phase 10.3 throwaway-cleanup note).
 */
@Component
@RequiredArgsConstructor
public class LocalityWriteValidator {

    private static final String CITY_REQUIRED =
            "City is required";
    private static final String CITY_UNKNOWN =
            "Selected city does not exist";
    private static final String DISTRICT_REQUIRED =
            "District is required for the selected city";
    private static final String DISTRICT_NOT_IN_CITY =
            "Selected district does not belong to the selected city";
    private static final String DISTRICT_NOT_ALLOWED =
            "The selected city has no districts; district must be omitted";
    private static final String DISTRICT_WITHOUT_CITY =
            "District cannot be set without a city";

    private final CityRepository cityRepository;
    private final CityDistrictRepository cityDistrictRepository;

    /**
     * Validates the locality of a discoverable provider (salon or
     * independent master) on profile-save.
     *
     * <ul>
     *   <li>{@code city_id} is mandatory.</li>
     *   <li>The city must exist.</li>
     *   <li>If the city defines urban districts, {@code district_id} is
     *       mandatory and must be a child of that city.</li>
     *   <li>If the city defines no urban districts, the city is the leaf —
     *       {@code district_id} must be {@code null}.</li>
     * </ul>
     *
     * @param input the submitted city / district FK pair
     * @throws BusinessException (HTTP 400) on any violation of the
     *                           most-specific-node rule or referential
     *                           integrity
     */
    public void validateProviderLocality(LocalityWriteInput input) {
        UUID cityId = input.cityId();
        if (cityId == null) {
            throw new BusinessException(CITY_REQUIRED);
        }
        if (!cityRepository.existsById(cityId)) {
            throw new BusinessException(CITY_UNKNOWN);
        }

        boolean cityHasDistricts = cityDistrictRepository.existsByCityId(cityId);
        UUID districtId = input.districtId();

        if (cityHasDistricts) {
            if (districtId == null) {
                throw new BusinessException(DISTRICT_REQUIRED);
            }
            requireDistrictBelongsToCity(districtId, cityId);
        } else if (districtId != null) {
            throw new BusinessException(DISTRICT_NOT_ALLOWED);
        }
    }

    /**
     * Validates the optional discovery-filter locality of a CLIENT.
     *
     * <p>Locality is optional and never blocks the save: a fully-null input is
     * accepted. When values are supplied, only referential integrity is
     * enforced — the city must exist, and a supplied district must be a child
     * of the supplied city. A district without a city is rejected (an orphan
     * district cannot define a discovery default).
     *
     * @param input the submitted city / district FK pair (either may be null)
     * @throws BusinessException (HTTP 400) only when a supplied value fails a
     *                           referential-integrity check
     */
    public void validateClientLocality(LocalityWriteInput input) {
        UUID cityId = input.cityId();
        UUID districtId = input.districtId();

        if (cityId == null) {
            if (districtId != null) {
                throw new BusinessException(DISTRICT_WITHOUT_CITY);
            }
            return;
        }
        if (!cityRepository.existsById(cityId)) {
            throw new BusinessException(CITY_UNKNOWN);
        }
        if (districtId != null) {
            requireDistrictBelongsToCity(districtId, cityId);
        }
    }

    private void requireDistrictBelongsToCity(UUID districtId, UUID cityId) {
        if (!cityDistrictRepository.existsByIdAndCityId(districtId, cityId)) {
            throw new BusinessException(DISTRICT_NOT_IN_CITY);
        }
    }
}
