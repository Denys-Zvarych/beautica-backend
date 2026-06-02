package com.beautica.master.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PATCH /api/v1/independent-masters/me/profile}.
 *
 * <p>All fields are optional (nullable). The service applies a null-and-blank
 * guard before writing: a null value leaves the stored value unchanged; a
 * non-null non-blank value overwrites it. This allows the mobile edit-profile
 * screen to send only the fields it wishes to change.
 *
 * <h2>Validation notes (§A)</h2>
 * <ul>
 *   <li>{@code firstName} and {@code lastName} are optional free-text. When
 *       supplied, they are capped at 100 characters and may not contain control
 *       characters (§A — every supplied string field needs {@code @Size} + a
 *       control-character {@code @Pattern} so that malformed payloads produce a
 *       clean 400 rather than a {@code DataIntegrityViolationException} 500).</li>
 *   <li>{@code phoneNumber} is optional on this edit endpoint: the phone number
 *       was collected at registration and may already be stored. Removing
 *       {@code @NotBlank} allows the caller to omit it and leave the stored value
 *       intact. The {@code @Pattern} still enforces format when a value is
 *       provided (Bean Validation skips null, so the pattern fires only for
 *       non-null values).</li>
 *   <li>{@code bio} and {@code instagram} are optional <em>clearable</em> fields:
 *       the mobile edit screen sends an empty string ({@code ""}) to clear a
 *       previously stored value, and {@code UserService.updateMasterProfile}
 *       treats any non-null value (including {@code ""}) as an overwrite. Both
 *       validators therefore accept the empty string — {@code bio}'s control-char
 *       {@code @Pattern} ({@code ^[^\p{Cntrl}]*$}) already matches {@code ""}, and
 *       {@code instagram}'s pattern carries an explicit {@code ^$} alternation.
 *       {@code phoneNumber} is deliberately <em>not</em> clearable: a blank phone
 *       is a no-op (the service skips it), so its pattern stays strict.</li>
 *   <li>{@code @Size(max = N)} values match {@code @Column(length = N)} / TEXT
 *       on the entity exactly — the DB column is never the backstop.</li>
 * </ul>
 */
public record MasterProfileUpdateRequest(

        @Size(max = 100, message = "First name must not exceed 100 characters")
        @Pattern(
                regexp = "^[^\\p{Cntrl}]*$",
                message = "First name must not contain control characters"
        )
        String firstName,

        @Size(max = 100, message = "Last name must not exceed 100 characters")
        @Pattern(
                regexp = "^[^\\p{Cntrl}]*$",
                message = "Last name must not contain control characters"
        )
        String lastName,

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
                // The leading ^$ alternation makes an empty string valid: the mobile client
                // sends instagram="" to CLEAR the stored handle (UserService treats a non-null
                // value — including "" — as an overwrite). Without it, the clear-intent payload
                // was rejected with 400, which silently blocked firstName/lastName from persisting
                // (a single @Valid failure rejects the whole body). null remains valid because
                // Bean Validation skips null targets; the format check still fires for any
                // non-empty value.
                regexp = "^$|^@?[A-Za-z0-9._]{1,30}$|^https://(www\\.)?instagram\\.com/[A-Za-z0-9._]+/?$",
                message = "Instagram must be a handle (e.g. @username) or a full instagram.com URL"
        )
        String instagram

) {}
