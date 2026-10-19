package com.beautica.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Internal guest-PII erase request (phase 373 D9). Any Ukrainian phone format; normalised to E.164
 * by {@code UkrainianPhoneNormalizer} in the service (32 = the normaliser's raw-length cap).
 *
 * @param phone     the guest's phone, any Ukrainian format
 * @param ticketRef optional support ticket reference (privacy@ thread / helpdesk id), logged with
 *                  the erase counts for accountability. Restricted to {@code [A-Za-z0-9-]{1,32}} so
 *                  it can never smuggle PII, whitespace or log-forging control characters into the
 *                  log line; {@code null} is allowed.
 */
public record GuestPiiEraseRequest(
        @NotBlank @Size(max = 32)
        String phone,

        @Pattern(regexp = "[A-Za-z0-9-]{1,32}")
        String ticketRef
) {
}
