package com.beautica.salon.dto;

import com.beautica.location.LocalityWriteInput;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Salon profile-update request.
 *
 * <p>Phase 10.6: locality is now expressed via the taxonomy FK pair
 * ({@code cityId} / {@code districtId}) plus the light structured address
 * ({@code street} / {@code buildingNo} / {@code locationNote}). The
 * most-specific-node rule (city mandatory; district mandatory iff the city has
 * urban districts; district must be a child of the city) is enforced by
 * {@code LocalityWriteValidator} in the service layer.
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
        @Size(max = 255) String name,
        @Size(max = 2000) String description,

        // ---- Legacy free-text locality (deprecated; no longer persisted) ----
        @Size(max = 100) String city,
        @Size(max = 100) String region,
        @Size(max = 500) String address,

        // ---- Phase 10.6 taxonomy locality + light structured address -------
        // Control-char @Pattern alongside @Size (MasterSearchRequest.q
        // precedent): @Size caps length only — an embedded NUL/newline would
        // otherwise reach the DB and yield a 500 instead of a clean 400 (§A).
        UUID cityId,
        UUID districtId,
        @Size(max = 255)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String street,
        @Size(max = 50)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String buildingNo,
        @Size(max = 1000)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String locationNote,

        @Pattern(regexp = "^[+\\d\\s\\-()/]*$", message = "Invalid phone format")
        @Size(max = 20) String phone,
        @Pattern(regexp = "^$|^https://(www\\.)?instagram\\.com/[A-Za-z0-9._]+/?$",
                message = "Must be a valid Instagram URL or empty")
        @Size(max = 500) String instagramUrl
) {

    /** Projects the taxonomy FK pair into the validator's input shape. */
    public LocalityWriteInput toLocalityInput() {
        return LocalityWriteInput.of(cityId, districtId);
    }
}
