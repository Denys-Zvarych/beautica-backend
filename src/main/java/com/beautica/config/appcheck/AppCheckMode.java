package com.beautica.config.appcheck;

/**
 * B514 (AUTH-54) — how the shared App Check guard treats SMS-sending endpoints.
 * <ul>
 *   <li>{@link #OFF} — no check (local, test).</li>
 *   <li>{@link #LOG_ONLY} — verify, log and count, never block (staging until verified; prod incident lever).</li>
 *   <li>{@link #ENFORCE} — a missing/invalid token is 403 {@code APP_CHECK_FAILED}, a key-fetch outage with no
 *       cached keys is 503 {@code APP_CHECK_UNAVAILABLE} (prod).</li>
 * </ul>
 */
public enum AppCheckMode {
    OFF,
    LOG_ONLY,
    ENFORCE
}
