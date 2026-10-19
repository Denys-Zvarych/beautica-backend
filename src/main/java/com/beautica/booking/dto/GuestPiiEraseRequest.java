package com.beautica.booking.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Internal guest-PII erase request (phase 373 D9). Any Ukrainian phone format; normalised to E.164
 * by {@code UkrainianPhoneNormalizer} in the service (32 = the normaliser's raw-length cap).
 */
public record GuestPiiEraseRequest(
        @NotBlank @Size(max = 32)
        String phone
) {
}
