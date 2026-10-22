package com.beautica.booking.domain;

import java.time.Instant;

/**
 * Proof that a guest accepted the privacy policy when booking through a public link (phase 381).
 *
 * <p>{@code acceptedAt} is the SERVER clock at creation, never client-supplied; {@code policyVersion} is
 * the policy's effective date exactly as the website published it (format-checked at the boundary, no
 * server allow-list). Both values are persisted together or not at all (DB pair CHECK) and are never
 * logged, never put in the notification outbox and never rendered in any response DTO.
 */
public record GuestConsent(Instant acceptedAt, String policyVersion) {

    public GuestConsent {
        if (acceptedAt == null) {
            throw new IllegalArgumentException("acceptedAt must not be null");
        }
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalArgumentException("policyVersion must not be blank");
        }
    }
}
