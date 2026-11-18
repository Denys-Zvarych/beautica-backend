package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

/**
 * B514 — phone-first identity and App Check rejections that the app branches on by {@code data.code}
 * ({@link IdentityErrorResponse}). Each {@link Code} fixes its HTTP status and its client message, so a throw site
 * can never put a phone, token or other value on the wire ({@code GlobalExceptionHandler#handleIdentity}).
 * B516 / B520 add their codes here (e.g. {@code PHONE_CHANGE_REQUIRES_OTP}, {@code INVITE_PHONE_MISMATCH}).
 *
 * <p>Stack-trace capture is suppressed: flow-control exception translated straight to a response.
 */
public class IdentityException extends BusinessException {

    /** Stable codes; the message is human prose only, never parsed by a client. */
    public enum Code {
        /** AUTH-D15 — not a Ukrainian mobile number; checked before any SMS is sent. */
        PHONE_NOT_UA_MOBILE(HttpStatus.BAD_REQUEST, "Only a Ukrainian mobile number is accepted"),
        /** AUTH-42 — a verified account already holds the phone (answered only after the code proved ownership). */
        PHONE_ALREADY_REGISTERED(HttpStatus.CONFLICT, "This phone number is already registered"),
        /** AUTH-41 — the registration ticket is unknown, used or expired. */
        REGISTRATION_TICKET_INVALID(HttpStatus.BAD_REQUEST, "Invalid or expired registration ticket"),
        /** AUTH-54 — missing or invalid Firebase App Check token in ENFORCE mode. */
        APP_CHECK_FAILED(HttpStatus.FORBIDDEN, "App verification failed"),
        /** AUTH-54 — Google's App Check keys could not be fetched and none are cached. */
        APP_CHECK_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "App verification is temporarily unavailable, please retry"),
        /** B516 (CLI-04, AUTH-44) — {@code PATCH /users/me} (or the master profile update) tried to set a different phone. */
        PHONE_CHANGE_REQUIRES_OTP(HttpStatus.BAD_REQUEST, "The phone number can only be changed with an SMS code"),
        /** B516 (AUTH-45) — {@code APP_AUTH_REQUIRE_VERIFIED_PHONE}: the action needs a verified phone first. */
        PHONE_NOT_VERIFIED(HttpStatus.CONFLICT, "Confirm your phone number to continue"),
        /** B520 (AUTH-18) — an invite-create body without a phone (an old build still sending only {@code email}). */
        INVITE_PHONE_REQUIRED(HttpStatus.BAD_REQUEST, "Invites are sent to a phone number; update the app"),
        /** B520 (AUTH-52) — the registration ticket proves a phone other than the invited one; no account is made. */
        INVITE_PHONE_MISMATCH(HttpStatus.BAD_REQUEST, "This invite was sent to a different phone number"),
        /** B520 (AUTH-52) — 10 invite SMS per inviter per hour or 30 per salon per day reached. */
        INVITE_SMS_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "Too many invites sent, please try again later");

        private final HttpStatus status;
        private final String clientMessage;

        Code(HttpStatus status, String clientMessage) {
            this.status = status;
            this.clientMessage = clientMessage;
        }

        public HttpStatus status() {
            return status;
        }

        public String clientMessage() {
            return clientMessage;
        }
    }

    private final Code code;

    public IdentityException(Code code) {
        super(code.status(), code.clientMessage());
        this.code = code;
    }

    public Code getCode() {
        return code;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
