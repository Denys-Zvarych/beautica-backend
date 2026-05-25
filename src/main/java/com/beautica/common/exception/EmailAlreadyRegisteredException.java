package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown by {@code AuthService} when a registration request targets an email that
 * already exists in the {@code users} table AND the
 * {@code app.security.disclose-duplicate-registration} toggle is on.
 *
 * <p>The toggle is {@code false} in every profile that is not explicitly opted in
 * (prod / default), so production callers still receive the silent-200
 * anti-enumeration response described in {@code AuthService#register}. Under the
 * local-dev profile the toggle is {@code true} and this exception surfaces as a
 * {@code 409 Conflict} with the error code {@code EMAIL_ALREADY_REGISTERED},
 * which the developer-facing client uses to route to a "this email is already
 * registered — sign in instead" prompt without leaving the developer guessing
 * why the verification email never arrived.
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
