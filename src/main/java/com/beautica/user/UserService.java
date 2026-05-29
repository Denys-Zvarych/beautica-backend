package com.beautica.user;

import com.beautica.auth.Role;
import com.beautica.common.exception.NotFoundException;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.location.entity.City;
import com.beautica.location.entity.Oblast;
import com.beautica.location.repository.CityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class UserService {

    private final UserRepository userRepository;
    private final LocalityWriteValidator localityWriteValidator;
    private final CityRepository cityRepository;

    public UserService(UserRepository userRepository,
                       LocalityWriteValidator localityWriteValidator,
                       CityRepository cityRepository) {
        this.userRepository = userRepository;
        this.localityWriteValidator = localityWriteValidator;
        this.cityRepository = cityRepository;
    }

    @Transactional(readOnly = true)
    public UserProfileResponse getProfile(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        return UserProfileResponse.from(user);
    }

    @Transactional
    public UserProfileResponse updateProfile(UUID userId, UpdateProfileRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));

        Optional.ofNullable(request.firstName()).ifPresent(user::setFirstName);
        Optional.ofNullable(request.lastName()).ifPresent(user::setLastName);
        Optional.ofNullable(request.phoneNumber()).ifPresent(user::setPhoneNumber);

        applyLocality(user, request);

        User saved = userRepository.save(user);
        return UserProfileResponse.from(saved);
    }

    /**
     * Routes the locality fields per the locked Phase 10.6 per-role matrix.
     *
     * <ul>
     *   <li><b>INDEPENDENT_MASTER</b> — full personal address; the
     *       most-specific-node rule is enforced (city mandatory, district
     *       mandatory iff the city has urban districts).</li>
     *   <li><b>CLIENT</b> — optional discovery-filter default; only
     *       referential integrity is checked, and only when supplied. Absence
     *       never blocks the save (OTP registration is unaffected — it does
     *       not call this path at all). All five locality fields
     *       ({@code cityId}, {@code districtId}, {@code street},
     *       {@code buildingNo}, {@code locationNote}) are persisted when
     *       present so clients can pre-fill their preferred location for
     *       appointment booking.</li>
     *   <li><b>SALON_OWNER / SALON_MASTER / SALON_ADMIN</b> — no personal
     *       locality write path. Owner locality lives on the salon
     *       ({@code SalonService}); SALON_MASTER discovery resolves via the
     *       salon link (Phase 10.5 M2 seam); SALON_ADMIN is search-excluded.
     *       Any submitted locality fields are ignored for these roles.</li>
     * </ul>
     */
    private void applyLocality(User user, UpdateProfileRequest request) {
        Role role = user.getRole();
        if (role == Role.INDEPENDENT_MASTER) {
            localityWriteValidator.validateProviderLocality(request.toLocalityInput());
            user.setCityId(request.cityId());
            user.setDistrictId(request.districtId());
            Optional.ofNullable(request.street()).ifPresent(user::setStreet);
            Optional.ofNullable(request.buildingNo()).ifPresent(user::setBuildingNo);
            Optional.ofNullable(request.locationNote()).ifPresent(user::setLocationNote);
            writeCityDisplayStrings(user, request.cityId());
        } else if (role == Role.CLIENT) {
            localityWriteValidator.validateClientLocality(request.toLocalityInput());
            user.setCityId(request.cityId());
            user.setDistrictId(request.districtId());
            Optional.ofNullable(request.street()).ifPresent(user::setStreet);
            Optional.ofNullable(request.buildingNo()).ifPresent(user::setBuildingNo);
            Optional.ofNullable(request.locationNote()).ifPresent(user::setLocationNote);
            writeCityDisplayStrings(user, request.cityId());
        }
        // SALON_OWNER / SALON_MASTER / SALON_ADMIN: no personal locality write.
    }

    /**
     * Denormalizes the human-readable city and region (oblast) display strings
     * into {@code users.city} and {@code users.region} so that read paths
     * (e.g. {@link com.beautica.master.dto.MasterDetailResponse}) can surface
     * them without a JOIN to the taxonomy tables.
     *
     * <p>Called only when {@code cityId} is non-null. If the city row is not
     * found (e.g. stale/invalid UUID slipped past validation), a WARN is logged
     * and both columns are left unchanged — the caller's transaction continues
     * normally.</p>
     *
     * <p>The {@link com.beautica.location.entity.City#getOblast()} association is
     * {@code FetchType.LAZY}; it is safe to traverse here because this method is
     * always called within an active {@code @Transactional} context.</p>
     */
    private void writeCityDisplayStrings(User user, UUID cityId) {
        if (cityId == null) {
            return;
        }
        Optional<City> cityOpt = cityRepository.findByIdWithOblast(cityId);
        if (cityOpt.isEmpty()) {
            log.warn("applyLocality: city not found for id={}, skipping city/region denorm", cityId);
            return;
        }
        City city = cityOpt.get();
        Oblast oblast = city.getOblast();
        if (oblast == null) {
            log.warn("applyLocality: city {} has no oblast association, skipping region denorm", cityId);
            user.setCity(city.getNameUk());
            return;
        }
        user.setCity(city.getNameUk());
        user.setRegion(oblast.getNameUk());
    }
}
