package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Profile-gated security toggles.
 *
 * <p>{@code discloseDuplicateRegistration} controls the registration response for an
 * email that already exists in the {@code users} table:
 *
 * <ul>
 *   <li><b>{@code false} (prod default)</b> — return the same generic
 *       {@code 200 RegistrationResponse} regardless of whether the email is new or
 *       already taken. This is the OWASP-recommended anti-enumeration posture: an
 *       attacker probing the endpoint cannot tell a fresh signup from a duplicate.</li>
 *   <li><b>{@code true} (local-dev only)</b> — throw
 *       {@code EmailAlreadyRegisteredException} so the API returns a {@code 409 Conflict}
 *       with the {@code EMAIL_ALREADY_REGISTERED} error code. This unblocks the
 *       "we sent a code, but it never comes" footgun that bites developers who
 *       re-register an already-verified email during local testing.</li>
 * </ul>
 *
 * <p>The flag is profile-driven (not env-driven) and defaults to {@code false} so any
 * deployment that does not explicitly opt in keeps the silent-success behaviour.
 *
 * <p>Co-located with {@link OtpPepperConfig} on the {@code app.security} prefix —
 * Spring Boot binds each {@code @ConfigurationProperties} record independently from
 * the property tree, so the two records can share a prefix without conflict.
 */
@ConfigurationProperties(prefix = "app.security")
public record SecurityPolicyConfig(
        boolean discloseDuplicateRegistration
) {
}
