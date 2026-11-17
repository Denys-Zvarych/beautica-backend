package com.beautica.auth.appcheck;

/** B514 — Google's App Check JWKS could not be fetched and no keys are cached; the guard maps it to 503. */
class AppCheckKeysUnavailableException extends RuntimeException {

    AppCheckKeysUnavailableException(String reason) {
        super(reason, null, false, false);
    }
}
