package com.beautica.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.JdbcConnectionDetails;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Phone-first hardening audit-fix — a tiny dedicated Hikari pool for the phone-OTP attempt bookkeeping
 * ({@code PhoneOtpAttemptRecorder}): the per-code compare reservation and the mismatch record must commit
 * independently of the verify transaction, which already holds a connection of the main pool. Taking a SECOND main
 * connection for that (REQUIRES_NEW) let ten concurrent verifies hold all ten main connections while each waited for an
 * eleventh — a pool stall until {@code connection-timeout}. This pool is separate, so the verify never waits on its own
 * pool: at most 2 bookkeeping statements run at once, each a single autocommit UPDATE of milliseconds, with a 1 s wait.
 *
 * <p>Modelled on {@link FlywayDataSourceConfig}: both beans are {@code defaultCandidate = false}, so Boot still builds
 * the application pool and JPA / unqualified {@code JdbcTemplate} never see this one. Unlike Flyway's pool it is live in
 * every profile, tests included (the recorder needs it): connection details come from the active
 * {@link JdbcConnectionDetails} (properties in prod/local, Testcontainers {@code @ServiceConnection} in tests), every
 * other setting from {@code spring.datasource.hikari.*} (so the same {@code connection-init-sql} statement timeout,
 * keepalive and leak detection apply), then size and wait are overridden.
 */
@Configuration
public class OtpAttemptDataSourceConfig {

    static final int MAX_POOL_SIZE = 2;
    static final long CONNECTION_TIMEOUT_MS = 1_000L;
    static final String POOL_NAME = "otp-attempt-pool";

    /** {@code spring.datasource.hikari.*} as the main pool binds it; overridden below where this pool differs. */
    @Bean(defaultCandidate = false)
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariConfig otpAttemptHikariConfig() {
        return new HikariConfig();
    }

    @Bean(defaultCandidate = false)
    public DataSource otpAttemptDataSource(@Qualifier("otpAttemptHikariConfig") HikariConfig config,
                                           JdbcConnectionDetails connectionDetails,
                                           MeterRegistry meterRegistry) {
        config.setJdbcUrl(connectionDetails.getJdbcUrl());
        config.setUsername(connectionDetails.getUsername());
        config.setPassword(connectionDetails.getPassword());
        config.setDriverClassName(connectionDetails.getDriverClassName());
        config.setMaximumPoolSize(MAX_POOL_SIZE);
        config.setMinimumIdle(1); // one warm connection: the first wrong code after idle pays no connect
        config.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        config.setAutoCommit(true);
        config.setPoolName(POOL_NAME);
        // hikaricp.* meters tagged pool=otp-attempt-pool (saturation of this pool is visible, not inferred).
        config.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(meterRegistry));
        return new HikariDataSource(config);
    }

    @Bean(defaultCandidate = false)
    public JdbcTemplate otpAttemptJdbcTemplate(@Qualifier("otpAttemptDataSource") DataSource otpAttemptDataSource) {
        return new JdbcTemplate(otpAttemptDataSource);
    }
}
