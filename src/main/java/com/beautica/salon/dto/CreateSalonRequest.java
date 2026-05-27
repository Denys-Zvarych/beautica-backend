package com.beautica.salon.dto;

import com.beautica.location.LocalityWriteInput;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Salon creation request.
 *
 * <p>Phase 10.3: locality is expressed via the taxonomy FK pair
 * ({@code cityId} / {@code districtId}) plus the light structured address
 * ({@code street} / {@code buildingNo} / {@code locationNote}). All five
 * fields are nullable — the mobile flow always sends them, but they are
 * optional for backward-compatible clients.
 *
 * <p>The legacy free-text {@code city} / {@code region} / {@code address}
 * fields are retained nullable for forward-compat with geocoder Part B.
 * They are kept on the wire but are not the source of truth.
 *
 * <p>{@code cityId}/{@code districtId} carry no Bean Validation annotation by
 * design: the UUID type rejects malformed values at deserialisation (generic
 * 400 via {@code GlobalExceptionHandler}), and referential-integrity is
 * owned by {@code LocalityWriteValidator}, not a syntactic constraint.
 * {@code @Size} caps mirror {@code @Column(length = …)} so oversized payloads
 * yield a clean 400 rather than a {@code DataIntegrityViolationException}
 * 500 (§A).
 */
public record CreateSalonRequest(
        @NotBlank @Size(max = 255)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String name,
        @Size(max = 2000)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String description,
        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String city,
        @Size(max = 100)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String region,
        @Size(max = 500)
        @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
        String address,
        @Pattern(regexp = "^[+\\d\\s\\-()/]*$", message = "Invalid phone format")
        @Size(max = 20) String phone,
        @Pattern(regexp = "^$|^https://(www\\.)?instagram\\.com/[A-Za-z0-9._]+/?$",
                message = "Must be a valid Instagram URL or empty")
        @Size(max = 500) String instagramUrl,

        // ---- Phase 10.3 taxonomy locality + light structured address --------
        // Placed after the legacy fields so existing 7-arg test constructors
        // continue to compile (new fields default to null when omitted).
        // Control-char @Pattern alongside @Size (§A): @Size caps length only —
        // an embedded NUL/newline would reach the DB and yield a 500 instead
        // of a clean 400.
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
        String locationNote
) {

    /**
     * Projects the taxonomy FK pair into the locality validator's input shape.
     * Returns an input with {@code null} cityId when none was provided — the
     * service guards the validator call behind a {@code cityId != null} check.
     */
    public LocalityWriteInput toLocalityInput() {
        return LocalityWriteInput.of(cityId, districtId);
    }
}
