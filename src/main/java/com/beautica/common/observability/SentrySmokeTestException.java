package com.beautica.common.observability;

/**
 * Deliberately unhandled exception thrown by {@link SentrySmokeController} (Phase 371, step 9). It has no
 * {@code @ExceptionHandler}, so it reaches the generic handler's {@code log.error} and becomes a Sentry event.
 */
public class SentrySmokeTestException extends RuntimeException {

    public SentrySmokeTestException(String message) {
        super(message);
    }
}
