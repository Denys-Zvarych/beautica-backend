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
 * Logs resource kinds only, never hostnames or credentials.
 */
@Slf4j
@Component
public class EnvironmentBannerRunner implements ApplicationRunner {

    private final Environment env;
    private final DeploymentProperties properties;

    public EnvironmentBannerRunner(Environment env, DeploymentProperties properties) {
        this.env = env;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("beautica_environment={} mail={} sms={} r2_bucket={}",
                properties.environment().token(), EnvironmentFingerprintGuard.mailKind(env),
                properties.sms().mode().name().toLowerCase(Locale.ROOT), EnvironmentFingerprintGuard.r2Bucket(env));
    }
}
