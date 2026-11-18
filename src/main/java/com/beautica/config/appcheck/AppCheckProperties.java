package com.beautica.config.appcheck;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

/**
 * B514 (AUTH-54, D5) — Firebase App Check on endpoints that send an SMS. Prefix {@code app.auth.app-check}.
 *
 * <ul>
 *   <li>{@code mode} ({@code APP_AUTH_APP_CHECK_MODE}) — {@link AppCheckMode}. Defaults: {@code application.yml}
 *       ENFORCE, staging LOG_ONLY, local and test OFF. Prod without staging refuses OFF at boot
 *       ({@code EnvironmentFingerprintGuard}, rule {@code prod-app-check-off}).</li>
 *   <li>{@code project-number} ({@code APP_AUTH_APP_CHECK_PROJECT_NUMBER}) — Firebase project number of
 *       {@code beautica-cb9fb}; the token's {@code iss} and {@code aud} are derived from it. Not a secret.</li>
 *   <li>{@code app-ids} ({@code APP_AUTH_APP_CHECK_APP_IDS}) — comma list of allowed Firebase app ids; the token's
 *       {@code sub} must be one of them.</li>
 *   <li>{@code jwks-url} — Google's App Check JWKS; prod must use the default (fingerprint rule
 *       {@code prod-app-check-jwks-url}). ITs point it at a WireMock stub.</li>
 * </ul>
 *
 * <p>Boot validation ({@link #afterPropertiesSet}): a mode other than OFF needs a numeric project number and at
 * least one non-blank app id, so a half-configured environment fails at deploy, not on the first registration.
 */
@ConfigurationProperties(prefix = "app.auth.app-check")
public class AppCheckProperties implements InitializingBean {

    public static final String GOOGLE_JWKS_URL = "https://firebaseappcheck.googleapis.com/v1/jwks";
    /** Token issuer prefix; the full issuer is this + the project number. */
    public static final String ISSUER_PREFIX = "https://firebaseappcheck.googleapis.com/";

    private static final Pattern PROJECT_NUMBER = Pattern.compile("[0-9]{1,20}");

    private AppCheckMode mode = AppCheckMode.ENFORCE;
    private String projectNumber;
    private List<String> appIds = List.of();
    private String jwksUrl = GOOGLE_JWKS_URL;
    private Duration jwksCacheTtl = Duration.ofHours(6);
    /** Floor between two JWKS fetches triggered by an unknown {@code kid} (a forged kid must not drive refetches). */
    private Duration jwksMinRefetchInterval = Duration.ofMinutes(1);
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(3);

    @Override
    public void afterPropertiesSet() {
        if (mode == null) {
            throw new IllegalStateException("app.auth.app-check.mode must be OFF, LOG_ONLY or ENFORCE");
        }
        if (mode == AppCheckMode.OFF) {
            return;
        }
        if (projectNumber == null || !PROJECT_NUMBER.matcher(projectNumber.strip()).matches()) {
            throw new IllegalStateException("app.auth.app-check.project-number (APP_AUTH_APP_CHECK_PROJECT_NUMBER) "
                    + "must be the numeric Firebase project number when app.auth.app-check.mode is " + mode);
        }
        if (appIds == null || appIds.stream().allMatch(id -> id == null || id.isBlank())) {
            throw new IllegalStateException("app.auth.app-check.app-ids (APP_AUTH_APP_CHECK_APP_IDS) must list at "
                    + "least one Firebase app id when app.auth.app-check.mode is " + mode);
        }
        if (jwksUrl == null || jwksUrl.isBlank()) {
            throw new IllegalStateException("app.auth.app-check.jwks-url must be set when the mode is " + mode);
        }
    }

    /** {@code https://firebaseappcheck.googleapis.com/<project-number>}. */
    public String expectedIssuer() {
        return ISSUER_PREFIX + projectNumber.strip();
    }

    /** {@code projects/<project-number>} — must be one of the token's audiences. */
    public String expectedAudience() {
        return "projects/" + projectNumber.strip();
    }

    /** The configured app ids, blank entries dropped and surrounding spaces stripped. */
    public List<String> allowedAppIds() {
        return appIds.stream().filter(id -> id != null && !id.isBlank()).map(String::strip).toList();
    }

    public AppCheckMode getMode() {
        return mode;
    }

    public void setMode(AppCheckMode mode) {
        this.mode = mode;
    }

    public String getProjectNumber() {
        return projectNumber;
    }

    public void setProjectNumber(String projectNumber) {
        this.projectNumber = projectNumber;
    }

    public List<String> getAppIds() {
        return appIds;
    }

    public void setAppIds(List<String> appIds) {
        this.appIds = appIds == null ? List.of() : List.copyOf(appIds);
    }

    public String getJwksUrl() {
        return jwksUrl;
    }

    public void setJwksUrl(String jwksUrl) {
        this.jwksUrl = jwksUrl;
    }

    public Duration getJwksCacheTtl() {
        return jwksCacheTtl;
    }

    public void setJwksCacheTtl(Duration jwksCacheTtl) {
        this.jwksCacheTtl = jwksCacheTtl;
    }

    public Duration getJwksMinRefetchInterval() {
        return jwksMinRefetchInterval;
    }

    public void setJwksMinRefetchInterval(Duration jwksMinRefetchInterval) {
        this.jwksMinRefetchInterval = jwksMinRefetchInterval;
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
}
