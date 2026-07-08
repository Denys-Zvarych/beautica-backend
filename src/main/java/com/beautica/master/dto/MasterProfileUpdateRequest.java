package com.beautica.master.dto;

import com.beautica.common.validation.NoDigits;
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
 *       {@code @Pattern} ({@code ^[^\x00-\x08\x0B\x0C\x0E-\x1F\x7F]*$}) already
 *       matches {@code ""}, and {@code instagram}'s pattern carries an explicit
 *       {@code ^$} alternation. Unlike the single-line fields, {@code bio} is
 *       long-form multi-line text, so its pattern deliberately permits tab,
 *       newline ({@code \n}) and carriage return ({@code \r}) while still
 *       rejecting NUL and the remaining C0/DEL control characters — the explicit
 *       ranges replace {@code \p{Cntrl}}, which would otherwise reject line breaks.
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
        @NoDigits(message = "First name must not contain a number")
        String firstName,

        @Size(max = 100, message = "Last name must not exceed 100 characters")
        @Pattern(
                regexp = "^[^\\p{Cntrl}]*$",
                message = "Last name must not contain control characters"
        )
        @NoDigits(message = "Last name must not contain a number")
        String lastName,

        @Size(max = 20, message = "Phone number must not exceed 20 characters")
        @Pattern(
                regexp = "^\\+?[0-9][0-9\\s\\-()]{6,19}$",
                message = "Phone number must be 7–20 digits and contain only digits, spaces, +, -, ( or )"
        )
        String phoneNumber,

        @Size(max = 2000, message = "Bio must not exceed 2000 characters")
        @Pattern(
                // Bio is genuine long-form, multi-line free text (TEXT column). Tab (0x09),
                // LF (0x0A) and CR (0x0D) are permitted so users can write multi-paragraph
                // bios; every other C0 control char (0x00–0x08, 0x0B, 0x0C, 0x0E–0x1F) and
                // DEL (0x7F) is still rejected to preserve the original NUL/control-injection
                // guard. Java's default \p{Cntrl} also matches tab/LF/CR, hence the explicit
                // ranges instead.
                regexp = "^[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*$",
                message = "Bio must not contain control characters other than line breaks and tabs"
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
        String instagram,

        // Custom free-text professional headline/label (e.g. "Майстер манікюру").
        // Optional clearable single-line field: the mobile edit screen sends "" to
        // clear a previously stored title, and updateMasterProfile treats any non-null
        // value (including "") as an overwrite (blank → null). The empty-string arm of
        // ^[^\p{Cntrl}]*$ makes "" valid; the control-char guard still fires for any
        // non-empty value. @Size(max = 100) mirrors User.professionalTitle's
        // @Column(length = 100) / V110 VARCHAR(100) so an oversized payload yields a
        // clean 400, not a 500 (§A). This endpoint is gated to INDEPENDENT_MASTER /
        // SALON_MASTER, so no CLIENT can reach this write path.
        //
        // Because professionalTitle renders UNMASKED on PUBLIC master cards, the guard is
        // stricter than the plain \p{Cntrl} used on firstName: it also rejects visual-spoofing
        // invisibles that \p{Cntrl} misses — bidi override/isolate (U+202A–U+202E,
        // U+2066–U+2069), zero-width & bidi marks (U+200B–U+200F, U+FEFF) and line/paragraph
        // separators (U+2028, U+2029). Kept byte-for-byte identical to
        // UpdateProfileRequest#professionalTitle so path A (/users/me) and path B
        // (/independent-masters/me/profile) validate the public title identically. The
        // empty-string arm still makes "" valid (clear-field intent → UserService null).
        @Size(max = 100, message = "Professional title must not exceed 100 characters")
        @Pattern(
                regexp = "^[^\\p{Cntrl}\\u200B-\\u200F\\u2028\\u2029\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]*$",
                message = "Professional title must not contain control, zero-width or bidirectional characters"
        )
        String professionalTitle

) {}
