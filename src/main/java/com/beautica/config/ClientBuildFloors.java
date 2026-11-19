package com.beautica.config;

/**
 * AUTH-D29 — the first Android {@code versionCode}s that carry a client behaviour the backend may later REQUIRE. Code
 * constants on purpose: raised only by a reviewed backend change, never by an env var. Read by
 * {@link EnvironmentFingerprintGuard} (rule {@code phone-first-rollout}), which refuses a staging/prod boot that would
 * switch a requirement on while {@code APP_ANDROID_MIN_VERSION_CODE} still lets older builds in.
 */
public final class ClientBuildFloors {

    /**
     * First build with the phone-first flow (M515 phone registration + M517 phone capture): the floor for
     * {@code APP_AUTH_REQUIRE_PHONE_REGISTRATION} / {@code APP_AUTH_REQUIRE_VERIFIED_PHONE} = true.
     */
    public static final int PHONE_FIRST_MIN_VERSION_CODE = 14;

    /**
     * First build sending a limited-use App Check token on every SMS-sending request: the floor for
     * {@code APP_AUTH_APP_CHECK_REPLAY=ENFORCE}.
     */
    public static final int APP_CHECK_LIMITED_USE_MIN_VERSION_CODE = 14;

    private ClientBuildFloors() {
    }
}
