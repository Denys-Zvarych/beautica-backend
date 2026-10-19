package com.beautica.notification.mail;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * {@code app.mail.*} (Phase 374, D2/D4).
 *
 * @param transport which {@link org.springframework.mail.javamail.JavaMailSender} exists; profile-driven, no env var
 * @param logoUrl   hosted e-mail logo. Blank (default) keeps the inline CID logo, so SMTP output is unchanged; prod
 *                  sets an https URL because the Brevo API cannot carry a CID part
 */
@Validated
@ConfigurationProperties(prefix = "app.mail")
public record MailTransportProperties(
        @NotNull @DefaultValue("smtp") MailTransportKind transport,
        @Pattern(regexp = "^$|^https://[^\\s\"'<>]+$", message = "app.mail.logo-url must be blank or an https URL")
        @DefaultValue("") String logoUrl
) {

    /** @return {@code true} when a hosted logo URL replaces the inline CID part */
    public boolean hostedLogo() {
        return logoUrl != null && !logoUrl.isBlank();
    }
}
