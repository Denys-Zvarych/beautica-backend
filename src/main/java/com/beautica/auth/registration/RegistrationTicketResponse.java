package com.beautica.auth.registration;

/**
 * B514 — {@code data} of {@code POST /auth/register/phone/otp/verify}: the single-use, 15-minute ticket the client
 * submits as {@code registrationTicket} to register / register-independent-master / invite accept.
 */
public record RegistrationTicketResponse(String registrationTicket) {

    /** The ticket is a credential — never in a log line. */
    @Override
    public String toString() {
        return "RegistrationTicketResponse[]";
    }
}
