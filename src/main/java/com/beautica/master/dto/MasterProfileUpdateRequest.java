package com.beautica.master.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /api/v1/independent-masters/me/profile}.
 *
 * <p>Covers the three editable public-profile fields: phone number, bio, and
 * Instagram handle.
 *
 * <h2>Validation notes (§A)</h2>
 * <ul>
 *   <li>{@code phoneNumber} is {@code @NotBlank}: phone was collected at
 *       registration and must not be cleared via this endpoint.</li>
 *   <li>{@code bio} and {@code instagram} are optional (null-safe in the service),
 *       but when supplied they are capped by {@code @Size} and guarded by a
 *       control-character {@code @Pattern} so that malformed payloads produce a
 *       clean 400 rather than a {@code DataIntegrityViolationException} 500.</li>
 *   <li>{@code @Size(max = N)} values match {@code @Column(length = N)} / TEXT
 *       on the entity exactly — the DB column is never the backstop.</li>
 * </ul>
 */
public record MasterProfileUpdateRequest(

        @NotBlank(message = "Phone number is required")
        @Size(max = 20, message = "Phone number must not exceed 20 characters")
        @Pattern(
                regexp = "^\\+?[0-9][0-9\\s\\-()]{6,19}$",
                message = "Phone number must be 7–20 digits and contain only digits, spaces, +, -, ( or )"
        )
        String phoneNumber,

        @Size(max = 2000, message = "Bio must not exceed 2000 characters")
        @Pattern(
                regexp = "^[^\\p{Cntrl}]*$",
                message = "Bio must not contain control characters"
        )
        String bio,

        @Size(max = 100, message = "Instagram handle must not exceed 100 characters")
        @Pattern(
                regexp = "^@?[A-Za-z0-9._]{1,30}$|^https://(www\\.)?instagram\\.com/[A-Za-z0-9._]+/?$",
                message = "Instagram must be a handle (e.g. @username) or a full instagram.com URL"
        )
        String instagram

) {}
