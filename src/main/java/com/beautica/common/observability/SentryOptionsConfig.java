package com.beautica.common.observability;

import io.sentry.Sentry;
import io.sentry.SentryOptions;
import org.apache.catalina.connector.ClientAbortException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;

/**
 * Sentry options that are class- or list-typed and have no setter on {@link SentryOptions}, so they cannot be
 * bound from YAML (Phase 371, D3). The Sentry starter applies every {@code Sentry.OptionsConfiguration} bean
 * before {@code Sentry.init}. Present only when the Sentry auto-configuration is (a {@code sentry.dsn}
 * property exists — prod/staging, or an exported {@code SENTRY_DSN}).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "sentry.dsn")
public class SentryOptionsConfig {

    static final String IN_APP_PACKAGE = "com.beautica";

    /** Client disconnects are not server faults: they never become events. */
    @Bean
    public Sentry.OptionsConfiguration<SentryOptions> beauticaSentryOptions() {
        return options -> {
            options.addInAppInclude(IN_APP_PACKAGE);
            options.addIgnoredExceptionForType(ClientAbortException.class);
            options.addIgnoredExceptionForType(AsyncRequestNotUsableException.class);
        };
    }
}
