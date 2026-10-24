package com.beautica.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.util.Optional;

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

    /**
     * @param mode      {@code turbosms} (default) or {@code capture}
     * @param otpDomain Phase 389 — bare website host the guest OTP SMS is bound to (last line
     *                  {@code @host #code}, WebOTP / Apple domain-bound codes). Blank = no domain line.
     *                  Validated at boot: a scheme, port, path, upper case or a host longer than
     *                  {@link OtpDomainRules#MAX_HOST_LENGTH} (one SMS segment) fails startup.
     */
    public record Sms(
            @NotNull @DefaultValue("TURBOSMS") SmsMode mode,
            @Size(max = OtpDomainRules.MAX_HOST_LENGTH)
            @Pattern(regexp = "^$|" + OtpDomainRules.HOST_REGEX,
                    message = "must be a bare lower-case host (no scheme, port or path), or blank")
            String otpDomain) {

        /** The OTP domain, empty when unset or blank. */
        public Optional<String> otpDomainHost() {
            return Optional.ofNullable(otpDomain).filter(host -> !host.isBlank());
        }
    }
}
