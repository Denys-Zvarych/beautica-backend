package com.beautica.config;

import org.flywaydb.core.api.exception.FlywayValidateException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Self-healing Flyway migration strategy that automatically recovers from
 * applied-migration checksum drift, scoped to the {@code prod} profile only.
 *
 * <p>Recovers from the "Migration checksum mismatch" startup crash that occurs
 * when an <em>already-applied</em> {@code V*.sql} is later edited (even a
 * comment-only change shifts Flyway's checksum). The strategy first calls
 * {@code flyway.validate()}: on success it falls straight through to
 * {@code migrate()}, which is byte-for-byte the Spring Boot default behaviour —
 * a normal deploy is completely unaffected (true no-op). Only when
 * {@code validate()} throws {@link FlywayValidateException} does it run a
 * one-shot {@code flyway.repair()} to realign
 * {@code flyway_schema_history.checksum} with the current script content, then
 * hand off to {@code migrate()}.
 *
 * <p>The catch block deliberately does <em>not</em> re-validate after
 * {@code repair()}. {@code validate()} also throws for a legitimately
 * <em>pending</em> migration ("Detected resolved migration not applied to
 * database"), which {@code repair()} cannot resolve (it never runs migration
 * bodies). A second {@code validate()} inside the catch block would re-throw
 * that same exception and escape, making {@code migrate()} unreachable and
 * crash-looping production on every deploy that ships a new migration. Instead,
 * {@code migrate()} is the single, always-reached terminal step for both the
 * healthy path and the recovered path: it applies any legitimately pending
 * migrations and re-runs Flyway's own {@code validateOnMigrate}, which still
 * fails fast on a genuinely unrecoverable history.
 *
 * <p>{@code flyway.repair()} does NOT re-run migration bodies — it only
 * rewrites the history-row checksums for already-applied migrations, so it is
 * safe for the checksum-drift case this guards. It is intentionally <em>not</em>
 * env-flag-gated: gating it behind an unset Railway variable previously left
 * production in an infinite crash-loop on the very deploy that needed the
 * repair. The protection against a genuinely dangerous edit to an applied
 * migration remains CI's {@code migration-immutability.yml} gate, which blocks
 * such a change before it ever reaches a deploy.
 */
@Configuration
@Profile("prod")
class FlywayRepairConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayRepairConfig.class);

    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy() {
        return flyway -> {
            try {
                flyway.validate();
            } catch (FlywayValidateException e) {
                log.warn("FLYWAY VALIDATE FAILED ({}). Running one-shot flyway.repair() to realign "
                        + "flyway_schema_history checksums for already-applied migrations whose script "
                        + "content drifted, then proceeding to migrate(). repair() does NOT re-run migration "
                        + "bodies; migrate() applies legitimately pending migrations and re-runs Flyway's own "
                        + "validateOnMigrate.", e.getMessage());
                flyway.repair();
                log.warn("FLYWAY REPAIR COMPLETE: history realigned; handing off to migrate().");
            }
            flyway.migrate();
        };
    }
}
