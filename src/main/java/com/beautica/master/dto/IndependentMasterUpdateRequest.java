package com.beautica.master.dto;

import com.beautica.location.LocalityWriteInput;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Locality-only update request for {@code PATCH /api/v1/independent-masters/me}.
 *
 * <p>Sent immediately after OTP verification to persist the independent master's
 * working address on their {@code User} row. The Phase 10.6 locality columns
 * ({@code city_id}, {@code district_id}, {@code street}, {@code building_no},
 * {@code location_note}) already exist on the {@code users} table (V54).
 *
 * <p>Validation mirrors {@link com.beautica.user.UpdateProfileRequest} — same
 * {@code @Size} caps and control-character {@code @Pattern} to prevent a 500
 * on oversized or malformed payloads (§A).
 */
public record IndependentMasterUpdateRequest(

        @NotNull(message = "cityId is required")
        UUID cityId,

        UUID districtId,   // nullable — cities without urban districts

        @Size(max = 255)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String street,

        @Size(max = 50)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String buildingNo,

        @Size(max = 1000)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String locationNote   // nullable — free-text landmark / how-to-find

) {
    /** Projects this request into the locality validator's input shape. */
    public LocalityWriteInput toLocalityInput() {
        return LocalityWriteInput.of(cityId, districtId);
    }
}
