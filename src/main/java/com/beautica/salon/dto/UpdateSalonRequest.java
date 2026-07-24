package com.beautica.salon.dto;

import com.beautica.location.LocalityWriteInput;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Salon profile-update request.
 *
 * <p>Phase 10.6: locality is now expressed via the taxonomy FK pair
 * ({@code cityId} / {@code districtId}) plus the structured address
 * ({@code street} / {@code buildingNo} / {@code locationNote}). The
 * most-specific-node rule (city mandatory; district mandatory iff the city has
 * urban districts; district must be a child of the city) is enforced by
 * {@code LocalityWriteValidator} in the service layer.
 *
 * <p><strong>Address contract:</strong> {@code street} and {@code buildingNo}
 * are <em>required</em> ({@code @NotBlank}) — a deliberate reversal of the
 * Phase 10.6 "provider address optional" model (city/district-only was the old
 * model); a blank/absent value now yields a clean 400 at the DTO boundary. Only
 * {@code locationNote} (and {@code districtId}, per the rule above) remains
 * optional.
 *
 * <p>The legacy free-text {@code city} / {@code region} / {@code address}
 * fields are retained on the wire for backward-compatible clients but are
 * <em>no longer the source of truth</em> — the service stops persisting them
 * (Phase 10.3 kept the columns nullable; Phase 10.6 stops writing them).
 *
 * <p>{@code cityId}/{@code districtId} carry no Bean Validation annotation by
 * design: the UUID type already rejects malformed values at deserialisation
 * (generic 400 via {@code GlobalExceptionHandler}), and "is this a known
 * city/district?" is a referential-integrity concern owned by the validator,
 * not a syntactic constraint. {@code @Size} caps mirror the V54
 * {@code @Column(length = …)} so an oversized payload yields a clean 400
 * rather than a {@code DataIntegrityViolationException} 500 (§A).
 */
public record UpdateSalonRequest(
        @Size(max = 255, message = "Name must be at most 255 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Name must not contain control characters")
        String name,
        @Size(max = 2000, message = "Description must be at most 2000 characters")
        @Pattern(regexp = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$",
                message = "Description must not contain control characters other than line breaks and tabs")
        String description,

        // ---- Legacy free-text locality (deprecated; no longer persisted) ----
        @Size(max = 100, message = "City must be at most 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "City must not contain control characters")
        String city,
        @Size(max = 100, message = "Region must be at most 100 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Region must not contain control characters")
        String region,
        @Size(max = 500, message = "Address must be at most 500 characters")
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "Address must not contain control characters")
        String address,

        // ---- Phase 10.6 taxonomy locality + light structured address -------
        // Control-char @Pattern alongside @Size (MasterSearchRequest.q
        // precedent): @Size caps length only — an embedded NUL/newline would
        // otherwise reach the DB and yield a 500 instead of a clean 400 (§A).
        UUID cityId,
        UUID districtId,
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
        String locationNote,

        @Pattern(regexp = "^[+\\d\\s\\-()/]*$", message = "Invalid phone format")
        @Size(max = 20, message = "Phone must be at most 20 characters") String phone,
        // Accepts a bare handle (@username), an @-less handle, or a full instagram.com
        // URL — mirrors MasterProfileUpdateRequest.instagram so salon and master
        // Instagram fields share one contract.
        @Pattern(regexp = "^$|^@?[A-Za-z0-9._]{1,30}$|^https://(www\\.)?instagram\\.com/[A-Za-z0-9._]+/?$",
                message = "Instagram must be a handle (e.g. @username) or a full instagram.com URL")
        @Size(max = 500, message = "Instagram URL must be at most 500 characters") String instagramUrl
) {

    /** Projects the taxonomy FK pair into the validator's input shape. */
    public LocalityWriteInput toLocalityInput() {
        return LocalityWriteInput.of(cityId, districtId);
    }
}
