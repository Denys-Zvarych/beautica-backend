package com.beautica.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;

/**
 * Shared test configuration that replaces the async {@code emailExecutor} thread-pool
 * with a synchronous {@link TaskExecutor} ({@code Runnable::run}).
 *
 * <p>Importing this via {@link AbstractIntegrationTest} ensures every full-context
 * integration test shares the same Spring context fingerprint instead of each test
 * class that previously declared an identical inner {@code SyncEmailConfig} causing
 * a separate context boot.
 *
 * <p>The synchronous executor guarantees that {@code emailNotificationService.sendVerificationEmail}
 * is called on the same thread, within the same call stack, before any
 * {@link org.mockito.ArgumentCaptor#getValue()} assertion — no races, no
 * {@link Thread#sleep}.
 *
 * <p>{@code allow-bean-definition-overriding: true} is set in {@code application-test.yml},
 * so the {@code @Primary} qualifier is sufficient to win the contest against the production
 * {@code emailExecutor} bean.
 */
@TestConfiguration
public class TestAsyncConfig {

    @Bean(name = "emailExecutor")
    @Primary
    public TaskExecutor emailExecutor() {
        return Runnable::run;
    }
}
