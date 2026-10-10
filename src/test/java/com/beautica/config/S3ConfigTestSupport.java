package com.beautica.config;

import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Shared {@link ApplicationContextRunner} boilerplate for the {@link S3Config} tests. */
final class S3ConfigTestSupport {

    static final String[] ENABLED_WITH_CREDENTIALS = {
            "app.cloudflare-r2.enabled=true",
            "app.cloudflare-r2.account-id=acct",
            "app.cloudflare-r2.access-key-id=key",
            "app.cloudflare-r2.secret-access-key=secret"
    };

    private S3ConfigTestSupport() {
    }

    /** Runner with only {@link S3Config} registered, R2 enabled and all credentials bound. */
    static ApplicationContextRunner enabledRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(S3Config.class)
                .withPropertyValues(ENABLED_WITH_CREDENTIALS);
    }
}
