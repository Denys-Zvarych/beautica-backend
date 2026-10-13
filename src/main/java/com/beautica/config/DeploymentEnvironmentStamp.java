package com.beautica.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Database environment stamp (Phase 368, F5). Host and database name cannot tell staging from prod (both
 * Railway environments are {@code postgres.railway.internal/railway}), so the database itself records
 * which environment owns it in the singleton {@code deployment_environment} table (V195).
 *
 * <p>For {@code staging}/{@code prod}: read the stamp; a value different from this container's environment
 * aborts startup with fixed text. An unstamped database is stamped ONLY when {@code BEAUTICA_DB_STAMP_INIT}
 * (read via {@link Environment}) equals this container's environment, so a container that merely boots first
 * cannot claim someone else's database; once stamped the variable is ignored (INFO asks the operator to remove
 * it). The stamp is one {@code INSERT ... ON CONFLICT ... RETURNING}, safe under concurrent first boots and
 * never overwriting an existing row. The pre-migration guard in {@link FlywayRepairConfig} applies the same
 * rules before any migration runs. Skipped for {@code local}/{@code test}.
 *
 * <p>Runs in {@link #afterPropertiesSet()} of a singleton that {@code @DependsOn} the Flyway initialiser
 * (the table must exist), i.e. during context refresh before {@code WebServerStartStopLifecycle} and any
 * scheduler start. The message never contains the stored or expected value.
 */
@Component
@DependsOn("flywayInitializer")
public class DeploymentEnvironmentStamp implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(DeploymentEnvironmentStamp.class);

    private final JdbcTemplate jdbc;
    private final DeploymentProperties properties;
    private final Environment env;

    public DeploymentEnvironmentStamp(JdbcTemplate jdbc, DeploymentProperties properties, Environment env) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.env = env;
    }

    @Override
    public void afterPropertiesSet() {
        DeployEnvironment environment = properties.environment();
        if (environment == DeployEnvironment.LOCAL) {
            return;
        }
        Optional<String> existing = DeploymentEnvironmentStore.read(jdbc);
        if (existing.isPresent()) {
            DeploymentEnvironmentStore.requireMatch(environment, existing.get());
            if (env.getProperty(DeploymentEnvironmentStore.INIT_PROPERTY) != null) {
                log.info("Database is already stamped; {} is ignored. Remove it from this environment's variables.",
                        DeploymentEnvironmentStore.INIT_PROPERTY);
            }
            return;
        }
        DeploymentEnvironmentStore.requireInitAuthorisation(env, environment);
        DeploymentEnvironmentStore.requireMatch(environment,
                DeploymentEnvironmentStore.stampIfAbsent(jdbc, environment.token()));
        log.info("Database stamped for this environment. Remove {} from this environment's variables.",
                DeploymentEnvironmentStore.INIT_PROPERTY);
    }
}
