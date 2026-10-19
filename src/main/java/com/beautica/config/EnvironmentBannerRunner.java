package com.beautica.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * One-line wiring banner (Phase 368, D5). Pure logging: the fail-closed checks live in
 * {@link EnvironmentFingerprintGuard} (pre-context) and {@link DeploymentEnvironmentStamp} (DB).
 * Logs resource kinds only, never hostnames or credentials ({@code sentry=on|off}, never the DSN — Phase 371).
 *
 * <p>Both probes are optional (Phase 374): exactly one exists, chosen by {@code app.mail.transport}.
 */
@Slf4j
@Component
public class EnvironmentBannerRunner implements ApplicationRunner {

    private static final String BREVO_API = "brevo-api";

    private final Environment env;
    private final DeploymentProperties properties;
    private final ObjectProvider<BrevoConnectionProbe> brevoProbe;
    private final ObjectProvider<BrevoApiConnectionProbe> brevoApiProbe;

    public EnvironmentBannerRunner(Environment env, DeploymentProperties properties,
                                   ObjectProvider<BrevoConnectionProbe> brevoProbe,
                                   ObjectProvider<BrevoApiConnectionProbe> brevoApiProbe) {
        this.env = env;
        this.properties = properties;
        this.brevoProbe = brevoProbe;
        this.brevoApiProbe = brevoApiProbe;
    }

    @Override
    public void run(ApplicationArguments args) {
        String mail = EnvironmentFingerprintGuard.mailKind(env);
        boolean prod = properties.environment() == DeployEnvironment.PROD;
        if (prod && BREVO_API.equals(mail)) {
            mail = brevoApiToken();
        } else if (prod && "brevo".equals(mail) && EnvironmentFingerprintGuard.brevoPending(env)) {
            // Phase 367 D9: prod with a missing/PENDING Brevo login or key is visibly degraded.
            mail = "brevo-pending";
        } else if (prod && "brevo".equals(mail) && smtpProbeUnreachable()) {
            // Credentials are set but the relay refused/did not answer (wrong key, outage). WARN only.
            mail = "brevo-unreachable";
        }
        log.info("beautica_environment={} mail={} sms={} r2_bucket={} sentry={} push={}",
                properties.environment().token(), mail,
                properties.sms().mode().name().toLowerCase(Locale.ROOT), EnvironmentFingerprintGuard.r2Bucket(env),
                EnvironmentFingerprintGuard.sentryState(env), EnvironmentFingerprintGuard.pushState(env));
    }

    private boolean smtpProbeUnreachable() {
        BrevoConnectionProbe probe = brevoProbe.getIfAvailable();
        return probe != null && probe.isUnreachable();
    }

    /** Phase 374 D9: {@code brevo-api-pending | brevo-api-unreachable | brevo-api-rejected | brevo-api}. */
    private String brevoApiToken() {
        if (EnvironmentFingerprintGuard.brevoApiKeyPending(env)) {
            return BREVO_API + "-pending";
        }
        BrevoApiConnectionProbe probe = brevoApiProbe.getIfAvailable();
        if (probe == null) {
            return BREVO_API;
        }
        return switch (probe.result()) {
            case OK -> BREVO_API;
            case REJECTED -> BREVO_API + "-rejected";
            case UNREACHABLE -> BREVO_API + "-unreachable";
        };
    }
}
