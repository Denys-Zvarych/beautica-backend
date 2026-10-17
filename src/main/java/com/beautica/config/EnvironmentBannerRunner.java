package com.beautica.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * One-line wiring banner (Phase 368, D5). Pure logging: the fail-closed checks live in
 * {@link EnvironmentFingerprintGuard} (pre-context) and {@link DeploymentEnvironmentStamp} (DB).
 * Logs resource kinds only, never hostnames or credentials ({@code sentry=on|off}, never the DSN — Phase 371).
 */
@Slf4j
@Component
public class EnvironmentBannerRunner implements ApplicationRunner {

    private final Environment env;
    private final DeploymentProperties properties;
    private final BrevoConnectionProbe brevoProbe;

    public EnvironmentBannerRunner(Environment env, DeploymentProperties properties, BrevoConnectionProbe brevoProbe) {
        this.env = env;
        this.properties = properties;
        this.brevoProbe = brevoProbe;
    }

    @Override
    public void run(ApplicationArguments args) {
        String mail = EnvironmentFingerprintGuard.mailKind(env);
        // Phase 367 D9: prod with a missing/PENDING Brevo login or key is visibly degraded.
        if (properties.environment() == DeployEnvironment.PROD && "brevo".equals(mail)
                && EnvironmentFingerprintGuard.brevoPending(env)) {
            mail = "brevo-pending";
        } else if (properties.environment() == DeployEnvironment.PROD && "brevo".equals(mail)
                && brevoProbe.isUnreachable()) {
            // Credentials are set but the relay refused/did not answer (wrong key, outage). WARN only.
            mail = "brevo-unreachable";
        }
        log.info("beautica_environment={} mail={} sms={} r2_bucket={} sentry={} push={}",
                properties.environment().token(), mail,
                properties.sms().mode().name().toLowerCase(Locale.ROOT), EnvironmentFingerprintGuard.r2Bucket(env),
                EnvironmentFingerprintGuard.sentryState(env), EnvironmentFingerprintGuard.pushState(env));
    }
}
