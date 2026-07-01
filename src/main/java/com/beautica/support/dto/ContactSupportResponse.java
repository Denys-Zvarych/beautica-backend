package com.beautica.support.dto;

/**
 * Minimal acknowledgement for an accepted contact-us submission.
 *
 * <p>Carries no server internals — just a human-readable confirmation the mobile
 * client can surface. Wrapped in {@code ApiResponse<ContactSupportResponse>}.
 */
public record ContactSupportResponse(String message) {

    public static ContactSupportResponse accepted() {
        return new ContactSupportResponse("Your message has been sent to support");
    }
}
