package com.beautica.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Logs the connected PostgreSQL server version at startup and warns when its major differs from
 * {@code beautica.db.expected-major}. This is what surfaces a managed-database major swap (prod
 * floats on a minor-only tag) that CI, pinned to one image, could never see. Log-only: it never
 * fails boot, and a failing version query is itself only a WARN.
 */
@Slf4j
@Component
@EnableConfigurationProperties(DatabaseVersionProperties.class)
public class DatabaseServerVersionLogger implements ApplicationRunner {

    private static final String VERSION_NUM_SQL = "SHOW server_version_num";
    private static final int MAJOR_DIVISOR = 10_000;

    private final JdbcTemplate jdbcTemplate;
    private final DatabaseVersionProperties properties;

    public DatabaseServerVersionLogger(JdbcTemplate jdbcTemplate, DatabaseVersionProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            Integer versionNum = jdbcTemplate.queryForObject(VERSION_NUM_SQL, Integer.class);
            if (versionNum == null) {
                log.warn("PostgreSQL server version could not be determined (empty result)");
                return;
            }
            int major = versionNum / MAJOR_DIVISOR;
            if (major == properties.expectedMajor()) {
                log.info("PostgreSQL server major version {} (server_version_num={})", major, versionNum);
            } else {
                log.warn("PostgreSQL server major version {} differs from expected {} (server_version_num={})",
                        major, properties.expectedMajor(), versionNum);
            }
        } catch (RuntimeException ex) {
            log.warn("PostgreSQL server version check failed: {}", ex.getClass().getSimpleName());
        }
    }
}
