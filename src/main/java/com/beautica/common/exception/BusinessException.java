package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Status-carrying domain rejection, translated by {@code GlobalExceptionHandler#handleBusiness}.
 *
 * <p>Phase 500: that handler logs a 4xx business rejection at INFO (409 at WARN), message included, so the
 * message must stay a developer-facing constant. A throw site whose message interpolates caller input (a
 * name, a sort property, a category) is built with {@link #withUserInput} instead; the handler then keeps
 * it at DEBUG, as before.
 */
public class BusinessException extends RuntimeException {

    private final HttpStatus status;
    private final boolean messageCarriesUserInput;

    public BusinessException(String message) {
        this(HttpStatus.BAD_REQUEST, message);
    }

    public BusinessException(HttpStatus status, String message) {
        this(status, message, false);
    }

    private BusinessException(HttpStatus status, String message, boolean messageCarriesUserInput) {
        super(message);
        this.status = status;
        this.messageCarriesUserInput = messageCarriesUserInput;
    }

    /**
     * A rejection whose message interpolates caller-supplied input. The wire body is unchanged; the
     * handler only keeps the message out of INFO/WARN logs.
     */
    public static BusinessException withUserInput(HttpStatus status, String message) {
        return new BusinessException(status, message, true);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public boolean messageCarriesUserInput() {
        return messageCarriesUserInput;
    }
}
