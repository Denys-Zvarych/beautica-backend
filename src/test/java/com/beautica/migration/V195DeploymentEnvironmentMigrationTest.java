package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for V195__create_deployment_environment_stamp.sql. Raw JDBC: the singleton and value
 * guards must hold when application code is bypassed. {@code cleanDb()} clears the table.
 */
@DisplayName("V195 migration — deployment_environment singleton stamp")
class V195DeploymentEnvironmentMigrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("a row can be inserted with defaults; stamped_at defaults to now()")
    void should_insertStamp_when_validEnvironment() {
        jdbcTemplate.update("INSERT INTO deployment_environment (environment) VALUES ('staging')");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT stamped_at IS NOT NULL AND id FROM deployment_environment", Boolean.class)).isTrue();
    }

    @Test
    @DisplayName("a second row is rejected (singleton PK) and ON CONFLICT DO NOTHING keeps the first value")
    void should_keepSingleRow_when_secondInsertAttempted() {
        jdbcTemplate.update("INSERT INTO deployment_environment (environment) VALUES ('staging')");

        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO deployment_environment (environment) VALUES ('prod')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbcTemplate.update("INSERT INTO deployment_environment (environment) VALUES ('prod') ON CONFLICT DO NOTHING");

        assertThat(jdbcTemplate.queryForObject("SELECT environment FROM deployment_environment", String.class))
                .isEqualTo("staging");
    }

    @Test
    @DisplayName("id=false and unknown environment values are rejected by CHECK")
    void should_rejectRow_when_idFalseOrEnvironmentUnknown() {
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO deployment_environment (id, environment) VALUES (false, 'prod')"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO deployment_environment (environment) VALUES ('local')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
