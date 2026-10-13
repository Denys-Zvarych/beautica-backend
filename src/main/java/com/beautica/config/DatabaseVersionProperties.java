package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Typed config for the PostgreSQL major version the application expects.
 *
 * <p>Binding key: {@code beautica.db.expected-major}. Informational only: a mismatch is logged by
 * {@link DatabaseServerVersionLogger}, it never fails boot.
 */
@ConfigurationProperties(prefix = "beautica.db")
public record DatabaseVersionProperties(@DefaultValue("18") int expectedMajor) {
}
