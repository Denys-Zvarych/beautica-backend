package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("DatabaseServerVersionLogger")
class DatabaseServerVersionLoggerTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DatabaseServerVersionLogger logger =
            new DatabaseServerVersionLogger(jdbcTemplate, new DatabaseVersionProperties(18));

    @Test
    void should_logInfoOnly_when_majorMatchesExpected(CapturedOutput output) {
        when(jdbcTemplate.queryForObject(eq("SHOW server_version_num"), eq(Integer.class))).thenReturn(180004);

        logger.run(null);

        assertThat(output.getAll())
                .contains("INFO")
                .contains("PostgreSQL server major version 18")
                .doesNotContain("WARN");
    }

    @Test
    void should_warnNamingBothMajors_when_majorDiffersFromExpected(CapturedOutput output) {
        when(jdbcTemplate.queryForObject(eq("SHOW server_version_num"), eq(Integer.class))).thenReturn(170006);

        logger.run(null);

        assertThat(output.getAll())
                .contains("WARN")
                .contains("major version 17 differs from expected 18");
    }

    @Test
    void should_warnAndNotThrow_when_versionQueryFails(CapturedOutput output) {
        when(jdbcTemplate.queryForObject(eq("SHOW server_version_num"), eq(Integer.class)))
                .thenThrow(new DataAccessResourceFailureException("db down"));

        assertThatCode(() -> logger.run(null)).doesNotThrowAnyException();

        assertThat(output.getAll())
                .contains("WARN")
                .contains("version check failed: DataAccessResourceFailureException");
        Mockito.verify(jdbcTemplate).queryForObject("SHOW server_version_num", Integer.class);
    }
}
