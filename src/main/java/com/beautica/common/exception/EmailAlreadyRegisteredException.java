package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown by {@code AuthService} when a registration request targets an email that
 * already exists in the {@code users} table.
 *
 * <p>Surfaces as a {@code 409 Conflict} with the error code
 * {@code EMAIL_ALREADY_REGISTERED}, which the client uses to route to a
 * "this email is already registered — sign in instead" prompt. This is the only
 * duplicate-email path; the previous anti-enumeration silent-200 branch was
 * removed because it produced an undebuggable "we sent a code, but it never
 * comes" footgun. The enumeration surface is bounded by the per-IP rate limit
 * on {@code /auth/*}.
 *
 * <p>Stack-trace capture is suppressed — like {@link EmailNotVerifiedException},
 * this is a flow-control exception translated directly to an HTTP response. The
 * full trace is never logged or inspected, so the per-throw allocation cost is
 * pure waste.
 */
public class EmailAlreadyRegisteredException extends BusinessException {

    /**
     * Stable error code echoed in the response body. The mobile client translates
     * this code to the localised Ukrainian copy; the API never ships natural-language
     * messages for client routing.
     */
    public static final String ERROR_CODE = "EMAIL_ALREADY_REGISTERED";

    public EmailAlreadyRegisteredException() {
        super(HttpStatus.CONFLICT, "Email already registered");
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
