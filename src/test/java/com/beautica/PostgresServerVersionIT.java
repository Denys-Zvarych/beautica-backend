package com.beautica;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PostgreSQL server version + required extensions")
class PostgresServerVersionIT extends AbstractIntegrationTest {

    @Test
    void should_runPostgres18_when_containerStarted() {
        Integer versionNum = jdbcTemplate.queryForObject("SHOW server_version_num", Integer.class);

        assertThat(versionNum).isGreaterThanOrEqualTo(180000).isLessThan(190000);
    }

    @Test
    void should_haveRequiredExtensions_when_migrationsApplied() {
        var extensions = jdbcTemplate.queryForList("SELECT extname FROM pg_extension", String.class);

        assertThat(extensions).contains("pg_trgm", "btree_gist");
    }
}
