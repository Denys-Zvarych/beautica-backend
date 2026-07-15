package com.beautica.master.dto;

import com.beautica.location.LocalityWriteInput;
import jakarta.validation.constraints.NotBlank;
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
 *
 * <p><strong>Address contract:</strong> {@code street} and {@code buildingNo}
 * are <em>required</em> ({@code @NotBlank}). This is a deliberate reversal of
 * the Phase 10.6 "provider address optional" model (city/district-only was the
 * old model); a blank/absent value now yields a clean 400 at the DTO boundary.
 * Only {@code locationNote} (and {@code districtId}, per the most-specific-node
 * rule) remains optional.
 */
public record IndependentMasterUpdateRequest(

        @NotNull(message = "cityId is required")
        UUID cityId,

        UUID districtId,   // nullable — cities without urban districts

        @NotBlank(message = "Street is required")
        @Size(max = 255, message = "Street must be at most 255 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Street must not contain control characters")
        String street,

        @NotBlank(message = "Building number is required")
        @Size(max = 50, message = "Building number must be at most 50 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Building number must not contain control characters")
        String buildingNo,

        @Size(max = 1000, message = "Location note must be at most 1000 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Location note must not contain control characters")
        String locationNote   // nullable — free-text landmark / how-to-find

) {
    /** Projects this request into the locality validator's input shape. */
    public LocalityWriteInput toLocalityInput() {
        return LocalityWriteInput.of(cityId, districtId);
    }
}
