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
 *                  the erase counts for accountability. Shape {@code PREFIX-NUMBER}
 *                  ({@code [A-Za-z]{2,10}-[0-9]{1,20}}, e.g. {@code SUP-1042}): the mandatory letter
 *                  prefix means it can never be a phone number, and it can never smuggle whitespace or
 *                  log-forging control characters into the log line; {@code null} is allowed.
 */
public record GuestPiiEraseRequest(
        @NotBlank @Size(max = 32)
        String phone,

        @Pattern(regexp = "[A-Za-z]{2,10}-[0-9]{1,20}")
        String ticketRef
) {
}
