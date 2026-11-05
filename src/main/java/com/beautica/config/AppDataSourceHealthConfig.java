package com.beautica.config;

import org.springframework.boot.actuate.autoconfigure.health.ConditionalOnEnabledHealthIndicator;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.jdbc.DataSourceHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Points the actuator {@code db} health check at the application pool only.
 *
 * <p><strong>Why.</strong> Boot's {@code DataSourceHealthContributorAutoConfiguration} collects DataSources via
 * {@code SimpleAutowireCandidateResolver.resolveAutowireCandidates}, which still includes
 * {@code defaultCandidate = false} beans. With {@link FlywayDataSourceConfig}'s {@code flywayDataSource} present it
 * would build a composite that opens a connection on {@code flyway-pool} on every health poll, so readiness would
 * depend on a pool that serves no requests (max 2 connections, idle after migration).
 *
 * <p>Declaring {@code dbHealthContributor} here makes Boot's own bean of that name back off
 * ({@code @ConditionalOnMissingBean(name = "dbHealthContributor")}). The injected {@link DataSource} is the single
 * default candidate — the app pool. Kept out of {@code FlywayDataSourceConfig}: that class is also loaded in slices
 * without a default DataSource, where this bean could not be satisfied.
 */
@Configuration(proxyBeanMethods = false)
public class AppDataSourceHealthConfig {

    @Bean
    @ConditionalOnEnabledHealthIndicator("db")
    public HealthContributor dbHealthContributor(DataSource dataSource) {
        return new DataSourceHealthIndicator(dataSource);
    }
}
