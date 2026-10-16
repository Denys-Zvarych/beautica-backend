package com.beautica.config;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;

/**
 * Shared SQL and decision helpers for the {@code deployment_environment} stamp (Phase 368, F5), used by the
 * read-only pre-Flyway guard in {@link FlywayRepairConfig} and the post-Flyway {@link DeploymentEnvironmentStamp}
 * so the queries and the fixed-text failures live in exactly one place. No message contains a stored or
 * expected environment value.
 */
final class DeploymentEnvironmentStore {

    /** First-deploy authorisation: must equal the container's environment token to stamp an unstamped DB. */
    static final String INIT_PROPERTY = "BEAUTICA_DB_STAMP_INIT";

    private DeploymentEnvironmentStore() {
    }

    /**
     * Read-only: empty when the table does not exist yet or holds no row. Two queries are kept on purpose: a
     * single statement referencing the table fails to parse when it is absent, and avoiding that needs dynamic
     * SQL. The name is schema-qualified so a hostile {@code search_path} cannot redirect the existence check.
     */
    static Optional<String> read(JdbcTemplate jdbc) {
        Boolean exists = jdbc.queryForObject("SELECT to_regclass('public.deployment_environment') IS NOT NULL", Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            return Optional.empty();
        }
        return jdbc.queryForList("SELECT environment FROM public.deployment_environment", String.class)
                .stream().findFirst();
    }

    /**
     * One round trip. An existing row is never changed ({@code SET id = EXCLUDED.id} is a no-op on the only
     * column in the conflict target), so a concurrent first boot or a later call just returns the owner.
     */
    static String stampIfAbsent(JdbcTemplate jdbc, String token) {
        return jdbc.queryForObject("INSERT INTO public.deployment_environment (environment) VALUES (?) "
                + "ON CONFLICT (id) DO UPDATE SET id = EXCLUDED.id RETURNING environment", String.class, token);
    }

    static void requireMatch(DeployEnvironment environment, String stored) {
        if (!environment.token().equals(stored)) {
            throw new IllegalStateException("Deployment environment stamp mismatch: this database belongs to a "
                    + "different environment than beautica.environment; check DATABASE_URL");
        }
    }

    static boolean initAuthorises(Environment env, DeployEnvironment environment) {
        String init = env.getProperty(INIT_PROPERTY);
        return init != null && environment.token().equals(init.trim());
    }

    static void requireInitAuthorisation(Environment env, DeployEnvironment environment) {
        if (!initAuthorises(env, environment)) {
            throw new IllegalStateException("Database is not stamped; set " + INIT_PROPERTY
                    + "=<env> (this container's own environment) for the first deploy of this environment");
        }
    }
}
