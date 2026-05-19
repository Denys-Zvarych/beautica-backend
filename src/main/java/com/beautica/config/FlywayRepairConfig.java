package com.beautica.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * One-shot, env-guarded {@code flyway repair} before the normal migrate, scoped
 * to the {@code prod} profile only.
 *
 * <p>Recovers from the "Migration checksum mismatch" startup crash that occurs
 * when an <em>already-applied</em> {@code V*.sql} is later edited (even a
 * comment-only change shifts Flyway's checksum). {@code flyway.repair()}
 * realigns {@code flyway_schema_history.checksum} with the current script
 * content; it does NOT re-run the migration body, so it is safe for the
 * checksum-drift case this guards.
 *
 * <p>Default is {@code false}: the strategy then calls only {@code migrate()},
 * which is byte-for-byte the Spring Boot default behaviour — a normal deploy is
 * completely unaffected.
 *
 * <p><strong>Operator runbook (one boot only):</strong> set
 * {@code FLYWAY_REPAIR_ON_MIGRATE=true} on Railway, redeploy so the app
 * self-repairs on the next boot, then <em>remove the variable</em> and redeploy
 * again. Never leave it permanently enabled: a standing {@code repair()} would
 * silently rubber-stamp a genuinely dangerous future edit to an applied
 * migration (the exact class of change CI's
 * {@code migration-immutability.yml} gate is meant to block), turning a loud
 * fail-fast into silent schema drift.
 */
@Configuration
@Profile("prod")
class FlywayRepairConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayRepairConfig.class);

    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy(
            @Value("${beautica.flyway.repair-on-migrate:false}") boolean repair) {
        return flyway -> {
            if (repair) {
                log.warn("FLYWAY REPAIR ENABLED: flyway.repair() will realign "
                        + "flyway_schema_history checksums before migrate(). This is a "
                        + "ONE-SHOT recovery lever and weakens the migration-immutability "
                        + "runtime guarantee while active. Remove FLYWAY_REPAIR_ON_MIGRATE "
                        + "from the environment and redeploy immediately after this boot.");
                flyway.repair();
                log.warn("FLYWAY REPAIR COMPLETE: schema-history checksums realigned. "
                        + "If FLYWAY_REPAIR_ON_MIGRATE is still set on the next boot, "
                        + "the immutability guard remains DISABLED.");
            }
            flyway.migrate();
        };
    }
}
