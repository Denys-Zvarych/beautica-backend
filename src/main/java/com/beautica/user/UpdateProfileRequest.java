package com.beautica.user;

import com.beautica.location.LocalityWriteInput;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Self-service profile-update request for {@code PATCH /api/v1/users/me}.
 *
 * <p>This single endpoint serves every role whose profile <em>is</em> the
 * {@code User} row. Phase 10.6 routes the locality fields per role in
 * {@code UserService}:
 * <ul>
 *   <li><b>CLIENT</b> — only {@code cityId} (+ optional {@code districtId}) are
 *       consumed, as an optional discovery-filter default. Never blocks the
 *       save; the structured address fields are ignored for clients.</li>
 *   <li><b>INDEPENDENT_MASTER</b> — the full personal address
 *       ({@code cityId}, {@code districtId}, {@code street}, {@code buildingNo},
 *       {@code locationNote}) is consumed and the most-specific-node rule is
 *       enforced.</li>
 *   <li><b>SALON_OWNER / SALON_MASTER / SALON_ADMIN</b> — no personal locality
 *       write path; locality fields are ignored (owner locality lives on the
 *       salon, masters resolve via their salon link).</li>
 * </ul>
 *
 * <p>{@code cityId}/{@code districtId} carry no Bean Validation annotation by
 * design (UUID type guards format; existence is a validator concern). The
 * {@code @Size} caps mirror the V54 {@code @Column(length = …)} so oversized
 * payloads yield a clean 400, not a 500 (§A).
 */
public record UpdateProfileRequest(

        @Size(min = 1, max = 100, message = "First name must be 1–100 characters")
        String firstName,

        @Size(min = 1, max = 100, message = "Last name must be 1–100 characters")
        String lastName,

        @Pattern(
                regexp = "^\\+?[0-9\\s\\-()]{7,20}$",
                message = "Phone number must be 7–20 digits"
        )
        String phoneNumber,

        // ---- Phase 10.6 taxonomy locality + light structured address -------
        UUID cityId,
        UUID districtId,
        @Size(max = 255) String street,
        @Size(max = 50) String buildingNo,
        @Size(max = 1000) String locationNote
) {

    /** Projects the taxonomy FK pair into the validator's input shape. */
    public LocalityWriteInput toLocalityInput() {
        return LocalityWriteInput.of(cityId, districtId);
    }
}
