package com.beautica.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed config for the Help / Contact-us support inbox.
 *
 * <p>Binding key: {@code app.support.email}. This is the FIXED destination address
 * that every {@code POST /api/v1/support/contact} message is emailed to. It is
 * env-driven ({@code SUPPORT_EMAIL}) and deliberately NOT {@code @NotBlank} /
 * {@code @Validated}: the rest of the application boots and serves traffic even
 * when the support inbox is unconfigured, exactly like {@code app.firebase.*} and
 * the R2 keys, which degrade gracefully rather than failing fast.
 *
 * <p>The blank-default guard lives in {@code SupportService}: when {@link #email}
 * is blank the contact endpoint returns a clean {@code 503 ServiceUnavailable}
 * business error ("support channel is not configured") instead of crashing the
 * whole app or attempting to send to an empty recipient. This keeps a new,
 * non-critical feature from gating application startup.
 */
@ConfigurationProperties(prefix = "app.support")
public class SupportProperties {

    /** Fixed support-inbox recipient; blank when the feature is unconfigured. */
    private String email = "";

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
