package com.beautica.config;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.CoreErrorCode;
import org.flywaydb.core.api.output.ValidateOutput;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Self-healing Flyway migration strategy for applied-migration checksum drift, scoped to the
 * {@code prod} profile (which {@code prod,staging} also activates).
 *
 * <p>Before any of that, a read-only environment-stamp guard (see {@link DeploymentEnvironmentStore}) refuses to
 * touch a database stamped for another environment, or an unstamped one without a matching
 * {@code BEAUTICA_DB_STAMP_INIT}, so a mis-pointed {@code DATABASE_URL} never receives migrations. When
 * validation passes {@code migrate()} runs on a same-configuration Flyway with
 * {@code validateOnMigrate(false)}, avoiding a second validate; after a repair the normal validating migrate runs.
 *
 * <p>Validation runs once on a same-configuration Flyway with {@code ignoreMigrationPatterns("*:pending", "*:future")},
 * so pending and future migrations pass while ignored/out-of-order, missing, failed and checksum problems still fail.
 * The outcome is classified from Flyway's structured {@link ValidateResult#invalidMigrations} error codes (never
 * from message text):
 * <ul>
 *   <li><b>Valid</b> (pending/future migrations allowed) &rarr; no repair; {@code migrate()} applies the pending
 *       migrations without re-validating. A pending migration is the normal deploy.</li>
 *   <li><b>Checksum mismatch on an applied migration</b> &rarr; in prod-only: ERROR naming the version(s)
 *       (never SQL), one-shot {@code repair()} realigning {@code flyway_schema_history} (the behaviour added
 *       for the #43/#45 crash-loop), then {@code migrate()}. When {@code staging} is also active: NO repair,
 *       boot fails loudly &mdash; staging is the drift canary, so an edit to an applied migration must
 *       surface there instead of being silently re-checksummed.</li>
 *   <li><b>Anything else</b> (out-of-order, applied-not-resolved, failed, type/description mismatch, or a failure
 *       with no per-migration detail) &rarr; no repair;
 *       {@code migrate()} re-runs Flyway's own {@code validateOnMigrate} and fails fast.</li>
 * </ul>
 *
 * <p>{@code repair()} never re-runs migration bodies, only rewrites history checksums. It is intentionally
 * <em>not</em> env-flag-gated: gating it behind an unset Railway variable previously left production in an
 * infinite crash-loop on the very deploy that needed it. CI's {@code migration-immutability.yml} remains the
 * primary guard against editing an applied migration.
 */
@Configuration
@Profile("prod")
class FlywayRepairConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayRepairConfig.class);

    @Bean
    FlywayMigrationStrategy flywayMigrationStrategy(Environment environment, DeploymentProperties properties) {
        return strategy(environment, properties.environment(),
                flyway -> DeploymentEnvironmentStore.read(new JdbcTemplate(flyway.getConfiguration().getDataSource())),
                flyway -> Flyway.configure().configuration(flyway.getConfiguration())
                        .ignoreMigrationPatterns("*:pending", "*:future").load(),
                flyway -> Flyway.configure().configuration(flyway.getConfiguration()).validateOnMigrate(false).load());
    }

    /**
     * @param stampReader      read-only lookup of the stamp row via the Flyway datasource (empty = table or row absent)
     * @param tolerantFor      builds a Flyway from the same configuration with
     *                         {@code ignoreMigrationPatterns("*:pending", "*:future")}: pending and future migrations
     *                         are allowed, everything else (ignored/out-of-order, missing, failed, checksum) still fails
     * @param nonValidatingFor builds a Flyway from the same configuration with {@code validateOnMigrate(false)},
     *                         used only after the tolerant validation succeeded (avoids a second validate)
     */
    static FlywayMigrationStrategy strategy(Environment environment, DeployEnvironment deployEnvironment,
                                            Function<Flyway, Optional<String>> stampReader,
                                            UnaryOperator<Flyway> tolerantFor,
                                            UnaryOperator<Flyway> nonValidatingFor) {
        boolean staging = environment.acceptsProfiles(Profiles.of("staging"));
        return flyway -> {
            // Read-only, BEFORE validate/repair/migrate: a container pointed at another environment's
            // database must not apply a single migration to it.
            if (deployEnvironment != DeployEnvironment.LOCAL) {
                Optional<String> stamp = stampReader.apply(flyway);
                if (stamp.isPresent()) {
                    DeploymentEnvironmentStore.requireMatch(deployEnvironment, stamp.get());
                } else {
                    DeploymentEnvironmentStore.requireInitAuthorisation(environment, deployEnvironment);
                }
            }
            ValidateResult result = tolerantFor.apply(flyway).validateWithResult();
            if (result.validationSuccessful) {
                nonValidatingFor.apply(flyway).migrate();
                return;
            }
            List<ValidateOutput> invalid = result.invalidMigrations == null ? List.<ValidateOutput>of()
                    : result.invalidMigrations;
            // repair() also drops failed entries and marks missing ones deleted, which would hide a real
            // applied-not-resolved problem: repair only when EVERY invalid entry is a checksum mismatch.
            boolean pureDrift = !invalid.isEmpty() && invalid.stream()
                    .allMatch(o -> o.errorDetails != null && o.errorDetails.errorCode == CoreErrorCode.CHECKSUM_MISMATCH);
            if (!pureDrift) {
                log.info("Flyway validation reported problems other than pure checksum drift; no repair, handing off to migrate() which fails fast.");
                flyway.migrate();
                return;
            }
            List<String> driftVersions = invalid.stream().map(o -> String.valueOf(o.version)).toList();
            if (staging) {
                log.error("FLYWAY CHECKSUM DRIFT on applied migration version(s) {} in staging; refusing to repair.",
                        driftVersions);
                throw new IllegalStateException("Flyway checksum drift on applied migration version(s) "
                        + driftVersions + " in staging; repair is disabled because staging is the drift canary");
            }
            log.error("FLYWAY CHECKSUM DRIFT on applied migration version(s) {}. Running one-shot flyway.repair() "
                    + "to realign flyway_schema_history checksums, then migrate().", driftVersions);
            flyway.repair();
            log.warn("FLYWAY REPAIR COMPLETE: history realigned; handing off to migrate().");
            flyway.migrate();
        };
    }
}
