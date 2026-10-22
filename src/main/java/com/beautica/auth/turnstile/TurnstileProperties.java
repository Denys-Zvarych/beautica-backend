package com.beautica.auth.turnstile;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Typed config for the Cloudflare Turnstile check on {@code POST /api/v1/book/otp/send} (phase 382).
 *
 * <p>Binding prefix {@code app.turnstile}. {@code enabled} is profile-driven (prod and staging {@code true};
 * local, test and the default {@code false}) and deliberately NOT a dedicated env var; the secret comes from
 * {@code TURNSTILE_SECRET_KEY} only. {@link #isSecretConfiguredWhenEnabled()} refuses to bind an enabled
 * verifier without a secret, so a misconfigured environment fails at boot instead of silently rejecting (or,
 * worse, skipping) every verification.
 *
 * <p>The secret is never logged and never part of an exception message; its setter strips surrounding
 * whitespace (a pasted trailing newline would otherwise make every siteverify call fail).
 */
@Validated
@ConfigurationProperties(prefix = "app.turnstile")
public class TurnstileProperties {

    private boolean enabled = false;
    private String secretKey = "";
    private String siteverifyUrl = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(3);
    private List<String> expectedHostnames = new ArrayList<>();
    private String expectedAction = "";

    @AssertTrue(message = "app.turnstile.secret-key (TURNSTILE_SECRET_KEY) must be set when app.turnstile.enabled is true")
    public boolean isSecretConfiguredWhenEnabled() {
        return !enabled || !secretKey.isBlank();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecretKey() {
        return secretKey;
    }

    public void setSecretKey(String secretKey) {
        this.secretKey = secretKey == null ? "" : secretKey.strip();
    }

    @NotNull
    public String getSiteverifyUrl() {
        return siteverifyUrl;
    }

    public void setSiteverifyUrl(String siteverifyUrl) {
        this.siteverifyUrl = siteverifyUrl;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout() {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout) {
        this.readTimeout = readTimeout;
    }

    public List<String> getExpectedHostnames() {
        return expectedHostnames;
    }

    public void setExpectedHostnames(List<String> expectedHostnames) {
        this.expectedHostnames = expectedHostnames == null ? new ArrayList<>() : new ArrayList<>(expectedHostnames);
    }

    public String getExpectedAction() {
        return expectedAction;
    }

    public void setExpectedAction(String expectedAction) {
        this.expectedAction = expectedAction == null ? "" : expectedAction.strip();
    }
}
