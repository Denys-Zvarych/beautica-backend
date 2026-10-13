package com.beautica.config;

/**
 * Which deployment this container believes it is (Phase 368). Bound from {@code beautica.environment};
 * each profile pins its own value. {@link EnvironmentFingerprintGuard} cross-checks it against
 * independent signals (Railway's injected environment name, resource names).
 */
public enum DeployEnvironment {
    LOCAL,
    STAGING,
    PROD;

    /** Lower-case token used in resource-name suffixes ({@code beautica-media-staging}). */
    public String token() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
