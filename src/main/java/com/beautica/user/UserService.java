package com.beautica.user;

import com.beautica.auth.Role;
import com.beautica.common.exception.NotFoundException;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.location.entity.City;
import com.beautica.location.entity.Oblast;
import com.beautica.location.repository.CityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class UserService {

    private final UserRepository userRepository;
    private final LocalityWriteValidator localityWriteValidator;
    private final CityRepository cityRepository;
    private final CacheManager cacheManager;

    public UserService(UserRepository userRepository,
                       LocalityWriteValidator localityWriteValidator,
                       CityRepository cityRepository,
                       CacheManager cacheManager) {
        this.userRepository = userRepository;
        this.localityWriteValidator = localityWriteValidator;
        this.cityRepository = cityRepository;
        this.cacheManager = cacheManager;
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

        // Evict the master-detail-by-user cache after the write is durable so that
        // a parallel reader cannot repopulate stale address data mid-transaction.
        final UUID evictUserId = userId;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    Cache c = cacheManager.getCache("master-detail-by-user");
                    if (c != null) {
                        c.evict(evictUserId);
                    }
                }
            });
        }

        return UserProfileResponse.from(saved);
    }

    /**
     * Routes the locality fields per the Phase 10.6 per-role matrix.
     *
     * <ul>
     *   <li><b>INDEPENDENT_MASTER</b> — full personal address; the
     *       most-specific-node rule is enforced (city mandatory, district
     *       mandatory iff the city has urban districts).</li>
     *   <li><b>CLIENT</b> — all 5 locality fields ({@code cityId},
     *       {@code districtId}, {@code street}, {@code buildingNo},
     *       {@code locationNote}) are persisted. City and district serve as
     *       the discovery-filter default; the structured address fields allow
     *       clients to record a service-delivery address (e.g. for
     *       at-home appointments).</li>
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
