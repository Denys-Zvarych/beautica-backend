package com.beautica.config.appcheck;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * B514 (AUTH-54) — prod may run App Check in {@code LOG_ONLY} as the incident lever, but it must never be left on
 * silently: while the mode is {@code LOG_ONLY} this logs {@link #LOG_ONLY_ALERT} at ERROR right after start-up and
 * then once per hour (an ERROR line is a Sentry event = an alert). {@code EnvironmentFingerprintGuard} logs the same
 * line at boot, before the context (and Sentry) is up. Prod only — staging may run any mode quietly.
 */
@Component
@Profile("prod & !staging")
public class AppCheckLogOnlyAlarm {

    /** Names the rule and the env var only — never a token or a phone. */
    public static final String LOG_ONLY_ALERT = "rule prod-app-check-log-only: app.auth.app-check.mode "
            + "(APP_AUTH_APP_CHECK_MODE) is LOG_ONLY in prod — SMS endpoints accept requests without a valid App Check "
            + "token; switch back to ENFORCE once the incident is over";

    private static final Logger log = LoggerFactory.getLogger(AppCheckLogOnlyAlarm.class);

    private final AppCheckProperties properties;

    public AppCheckLogOnlyAlarm(AppCheckProperties properties) {
        this.properties = properties;
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.HOURS)
    public void alertWhileLogOnly() {
        if (properties.getMode() == AppCheckMode.LOG_ONLY) {
            log.error(LOG_ONLY_ALERT);
        }
    }
}
