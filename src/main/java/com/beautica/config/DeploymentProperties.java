package com.beautica.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Deploy-environment identity and SMS delivery mode (Phase 368). Binding prefix {@code beautica}
 * (sibling of {@code beautica.db}); registered through {@link AppConfigProperties} like every other
 * properties type.
 *
 * <p>An unknown enum value ({@code beautica.environment=qa}) fails binding at startup, which is the
 * intended fail-closed behaviour.
 *
 * @param environment which deployment this is; {@code LOCAL} when no profile pins it
 * @param sms         SMS delivery settings
 */
@Validated
@ConfigurationProperties(prefix = "beautica")
public record DeploymentProperties(
        @NotNull @DefaultValue("LOCAL") DeployEnvironment environment,
        @Valid @NotNull @DefaultValue Sms sms) {

    /** @param mode {@code turbosms} (default) or {@code capture} */
    public record Sms(@NotNull @DefaultValue("TURBOSMS") SmsMode mode) {
    }
}
