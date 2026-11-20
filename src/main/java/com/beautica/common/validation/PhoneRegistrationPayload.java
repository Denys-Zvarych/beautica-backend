package com.beautica.common.validation;

/**
 * B514 — an account-creating request that may carry a {@code registrationTicket} (phone proven by SMS code, AUTH-41)
 * instead of the legacy fields. Checked as a whole by {@link PhoneRegistrationShape}.
 */
public interface PhoneRegistrationPayload {

    /** The raw ticket from {@code /auth/register/phone/otp/verify}, or {@code null} on the legacy shape. */
    String registrationTicket();

    /** Legacy free-text phone; required on the legacy shape, ignored with a ticket (the ticket's phone wins). */
    String phoneNumber();

    /** Legacy email; {@code null} for a payload that has none (invite accept: the email comes from the invite). */
    default String email() {
        return null;
    }

    /** {@code true} for self-registration, where the legacy shape needs an email and a last name. */
    default boolean legacyShapeNeedsEmailAndLastName() {
        return false;
    }

    /** Last name; required on the legacy self-registration shape, optional with a ticket. */
    default String lastName() {
        return null;
    }

    default boolean hasRegistrationTicket() {
        String ticket = registrationTicket();
        return ticket != null && !ticket.isBlank();
    }
}
