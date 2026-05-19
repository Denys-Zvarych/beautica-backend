package com.beautica.user;

import com.beautica.auth.Role;
import com.beautica.common.exception.NotFoundException;
import com.beautica.location.LocalityWriteValidator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final LocalityWriteValidator localityWriteValidator;

    public UserService(UserRepository userRepository,
                       LocalityWriteValidator localityWriteValidator) {
        this.userRepository = userRepository;
        this.localityWriteValidator = localityWriteValidator;
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
     *       not call this path at all). The structured street/building/note
     *       fields are not a client concern and are ignored.</li>
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
            user.setStreet(request.street());
            user.setBuildingNo(request.buildingNo());
            user.setLocationNote(request.locationNote());
        } else if (role == Role.CLIENT) {
            localityWriteValidator.validateClientLocality(request.toLocalityInput());
            user.setCityId(request.cityId());
            user.setDistrictId(request.districtId());
        }
        // SALON_OWNER / SALON_MASTER / SALON_ADMIN: no personal locality write.
    }
}
