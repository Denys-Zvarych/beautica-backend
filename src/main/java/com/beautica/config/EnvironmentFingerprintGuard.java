package com.beautica.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Refuses to boot a container whose independent environment signals disagree (Phase 368, D2).
 *
 * <p><b>When it runs.</b> As an {@link EnvironmentPostProcessor} ordered last, i.e. after config data
 * (profiles, {@code application-*.yml}) is loaded and before the application context, the web server or
 * any {@code @Scheduled}/outbox job exists. A mis-wired container therefore never serves a request or
 * sends a message before it fails. The DB-side check ({@link DeploymentEnvironmentStamp}) needs a
 * datasource and runs during singleton initialisation, still before the web server starts. The startup
 * banner is a separate runner ({@link EnvironmentBannerRunner}).
 *
 * <p><b>Signals that must agree</b> for {@code staging} / {@code prod}: the active Spring profiles, the
 * resolved {@code beautica.environment} (relaxed binding, so {@code BEAUTICA_ENVIRONMENT=local} cannot
 * hide a {@code prod}/{@code staging} profile), the Railway-injected {@code RAILWAY_ENVIRONMENT_NAME},
 * and the resource names (exact R2 bucket, mail host allowlist, SMS mode, provider credentials). The
 * guard is skipped only for a genuinely local container: environment {@code LOCAL}, no
 * {@code prod}/{@code staging} profile and no Railway variable.
 *
 * <p><b>Messages name the rule and the property, never a value</b>: several of these properties are
 * secrets, and the rest (hostnames, bucket) are one hop from them.
 *
 * <p><b>Operator duty (Phase 369):</b> this guard cannot see secrets it must not read — staging and prod
 * must be provisioned with <em>different</em> {@code JWT_SECRET} and {@code OUTBOX_PAYLOAD_KEY} values,
 * so a token or outbox row from one environment is worthless in the other.
 */
public class EnvironmentFingerprintGuard implements EnvironmentPostProcessor, Ordered {

    static final String RAILWAY_ENV_VAR = "RAILWAY_ENVIRONMENT_NAME";
    static final String STAGING_BUCKET = "beautica-media-staging";
    static final String PROD_BUCKET = "beautica-media-prod";

    private static final java.util.regex.Pattern EMAIL_SHAPE =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[a-z]{2,}$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final List<String> RESERVED_TEST_TLDS = List.of(".test", ".example", ".localhost", ".invalid", ".local", ".internal", ".home.arpa");

    private static final List<String> RESERVED_EXAMPLE_DOMAINS = List.of("example.com", "example.net", "example.org");

    private static final Profiles PROD = Profiles.of("prod");
    private static final Profiles STAGING = Profiles.of("staging");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        verify(environment);
    }

    /** Last, so config data (and the DB-URL normaliser registered before it) has already run. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * @throws IllegalStateException when any rule is violated; the message lists every violated rule
     */
    public static void verify(Environment env) {
        DeployEnvironment environment = environmentOf(env);
        boolean prodProfile = env.acceptsProfiles(PROD);
        boolean stagingProfile = env.acceptsProfiles(STAGING);
        boolean onRailway = !isBlank(env.getProperty(RAILWAY_ENV_VAR));
        if (environment == DeployEnvironment.LOCAL && !prodProfile && !stagingProfile && !onRailway) {
            return;
        }
        List<String> violations = new ArrayList<>();
        checkProfiles(environment, prodProfile, stagingProfile, violations);
        checkRailwayName(env, environment, violations);
        if (environment != DeployEnvironment.LOCAL) {
            checkR2Bucket(env, environment, violations);
            checkLinkBaseUrls(env, environment, violations);
            if (environment == DeployEnvironment.PROD) {
                checkProd(env, violations);
            } else {
                checkStaging(env, violations);
            }
        }
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Environment fingerprint mismatch (beautica.environment="
                    + environment.token() + "): " + String.join("; ", violations));
        }
    }

    static DeployEnvironment environmentOf(Environment env) {
        return Binder.get(env).bind("beautica.environment", DeployEnvironment.class).orElse(DeployEnvironment.LOCAL);
    }

    static SmsMode smsModeOf(Environment env) {
        return Binder.get(env).bind("beautica.sms.mode", SmsMode.class).orElse(SmsMode.TURBOSMS);
    }

    private static void checkProfiles(DeployEnvironment environment, boolean prodProfile, boolean stagingProfile,
                                      List<String> violations) {
        if ((prodProfile || stagingProfile) && environment == DeployEnvironment.LOCAL) {
            violations.add("rule profile-environment: prod/staging profile active but beautica.environment is local");
        } else if (stagingProfile && environment != DeployEnvironment.STAGING) {
            violations.add("rule profile-environment: staging profile active but beautica.environment is not staging");
        } else if (prodProfile && !stagingProfile && environment != DeployEnvironment.PROD) {
            violations.add("rule profile-environment: prod profile active but beautica.environment is not prod");
        }
    }

    private static void checkRailwayName(Environment env, DeployEnvironment environment, List<String> violations) {
        String railway = env.getProperty(RAILWAY_ENV_VAR);
        if (isBlank(railway)) {
            return;
        }
        String normalised = railway.strip().toLowerCase(Locale.ROOT);
        boolean matches = switch (environment) {
            case PROD -> normalised.equals("production") || normalised.equals("prod");
            case STAGING -> normalised.equals("staging");
            case LOCAL -> false;
        };
        if (!matches) {
            violations.add("rule railway-environment-name: " + RAILWAY_ENV_VAR + " does not match beautica.environment");
        }
    }

    private static void checkR2Bucket(Environment env, DeployEnvironment environment, List<String> violations) {
        if (!r2Enabled(env)) {
            if (environment == DeployEnvironment.PROD) {
                violations.add("rule prod-r2-enabled: app.cloudflare-r2.enabled must be true in prod");
            }
            return;
        }
        String expected = environment == DeployEnvironment.PROD ? PROD_BUCKET : STAGING_BUCKET;
        if (!expected.equals(env.getProperty("app.cloudflare-r2.bucket", ""))) {
            violations.add("rule r2-bucket-name: app.cloudflare-r2.bucket must be the exact "
                    + environment.token() + " bucket name");
        }
    }

    /**
     * Link-building URLs must be real, public https origins outside LOCAL (fail closed): a missing value or a
     * localhost default would otherwise ship e-mail links nobody can open. Messages name the rule and the
     * property only, never the value.
     */
    private static void checkLinkBaseUrls(Environment env, DeployEnvironment environment, List<String> violations) {
        boolean prod = environment == DeployEnvironment.PROD;
        String publicHost = checkLinkUrl(env, "app.public-base-url", "public-base-url", true, prod, violations);
        if (!prod && publicHost != null && !hasStagingLabel(publicHost)) {
            violations.add("rule staging-public-base-url-name: app.public-base-url host must have a staging DNS label in staging");
        }
        checkLinkUrl(env, "app.frontend.base-url", "frontend-base-url", true, prod, violations);
        if (r2Enabled(env)) {
            checkLinkUrl(env, "app.cloudflare-r2.public-url", "r2-public-url", false, prod, violations);
        }
    }

    /** @return the normalised host when the URL passed the https/host rules, else {@code null} */
    private static String checkLinkUrl(Environment env, String property, String ruleKey, boolean checkShape,
                                       boolean prod, List<String> violations) {
        String raw = readOrNull(env, property);
        String host = null;
        URI uri = null;
        if (!isBlank(raw)) {
            try {
                uri = new URI(raw.strip());
                host = uri.getHost() == null ? null : normaliseHost(uri.getHost());
            } catch (URISyntaxException e) {
                uri = null;
            }
        }
        if (uri == null || host == null || host.isEmpty() || !"https".equalsIgnoreCase(uri.getScheme()) || isInternalHost(host)
                || isIpLiteralOrSingleLabel(host)) {
            violations.add("rule " + ruleKey + "-https: " + property + " must be https and non-loopback");
            return null;
        }
        // The link builders use the raw value, so surrounding whitespace and userinfo must be rejected outright.
        if (!raw.equals(raw.strip())) {
            violations.add("rule " + ruleKey + "-shape: " + property + " must have no surrounding whitespace");
        }
        if (uri.getRawUserInfo() != null) {
            violations.add("rule " + ruleKey + "-userinfo: " + property + " must not contain userinfo");
        }
        String trimmed = raw.strip();
        if (checkShape && (uri.getRawQuery() != null || uri.getRawFragment() != null || trimmed.contains("?")
                || trimmed.contains("#") || trimmed.endsWith("/"))) {
            violations.add("rule " + ruleKey + "-shape: " + property
                    + " must have no query, fragment or trailing slash");
        }
        // A CORS origin has no path, so a base URL with one (https://api.x.ua/api) can never match it.
        if ("public-base-url".equals(ruleKey) && uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
            violations.add("rule " + ruleKey + "-path: " + property + " must have no path (origin only)");
        }
        if (prod && (host.contains("staging") || isPlaceholderDomain(host))) {
            violations.add("rule prod-" + ruleKey + "-host: " + property
                    + " must not be a staging or reserved/placeholder host in prod");
        }
        return host;
    }

    /** An unresolvable placeholder (no default, env var absent) counts as unset. */
    private static String readOrNull(Environment env, String property) {
        try {
            return env.getProperty(property);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String normaliseHost(String host) {
        String h = host.strip().toLowerCase(Locale.ROOT);
        return h.endsWith(".") ? h.substring(0, h.length() - 1) : h;
    }

    /**
     * IP literals (bracketed IPv6, or any host whose last label is numeric / {@code 0x..}, which covers decimal,
     * octal, hex and dotted IPv4 forms) and single-label hosts are never a public DNS origin. A real TLD is never
     * numeric, so this does not reject hex-looking domains such as {@code cafe.de}.
     */
    private static boolean isIpLiteralOrSingleLabel(String host) {
        if (host.startsWith("[") || host.indexOf('.') < 0) {
            return true;
        }
        String last = host.substring(host.lastIndexOf('.') + 1);
        return last.matches("[0-9]+|0x[0-9a-f]*");
    }

    /** A whole DNS label equal to {@code staging}, or starting with {@code staging-} / ending with {@code -staging}. */
    private static boolean hasStagingLabel(String host) {
        for (String label : host.split("\\.")) {
            if (label.equals("staging") || label.startsWith("staging-") || label.endsWith("-staging")) {
                return true;
            }
        }
        return false;
    }

    /** Reserved test TLDs and the RFC 2606 example domains, including their subdomains. Input is lower-case. */
    private static boolean isPlaceholderDomain(String domain) {
        return RESERVED_TEST_TLDS.stream().anyMatch(domain::endsWith)
                || RESERVED_EXAMPLE_DOMAINS.stream().anyMatch(d -> domain.equals(d) || domain.endsWith("." + d));
    }

    private static boolean isInternalHost(String host) {
        return isLoopback(host) || host.endsWith(".localhost") || host.equals("10.0.2.2")
                || host.equals("railway.internal") || host.endsWith(".railway.internal");
    }

    private static void checkProd(Environment env, List<String> violations) {
        String host = mailHost(env);
        if (isLoopback(host) || host.startsWith("mailpit")) {
            violations.add("rule prod-mail-host: spring.mail.host must not be loopback or a mailpit host in prod");
        }
        if (smsModeOf(env) != SmsMode.TURBOSMS) {
            violations.add("rule prod-sms-mode: beautica.sms.mode must be turbosms in prod");
        }
        String support = supportEmail(env);
        if (!isEmailShaped(support) || isPlaceholderDomain(support.substring(support.lastIndexOf('@') + 1))) {
            violations.add("rule prod-support-email: app.support.email must be a real address (local@domain.tld, not a reserved test domain) in prod");
        }
    }

    private static void checkStaging(Environment env, List<String> violations) {
        String host = mailHost(env);
        boolean captureHost = isCaptureHost(host);
        if (!captureHost) {
            violations.add("rule staging-mail-host: spring.mail.host must be loopback, a mailpit host or a "
                    + ".railway.internal host in staging");
        }
        if (!isBlank(env.getProperty("TURBOSMS_TOKEN")) || !isBlank(env.getProperty("app.sms.turbosms.token"))) {
            violations.add("rule staging-turbosms-token-blank: TURBOSMS_TOKEN must be blank in staging");
        }
        if (!isBlank(env.getProperty("BREVO_SMTP_KEY")) || !isBlank(env.getProperty("spring.mail.password"))) {
            violations.add("rule staging-brevo-key-blank: BREVO_SMTP_KEY must be blank in staging");
        }
        String support = supportEmail(env);
        if (!isEmailShaped(support) || !support.endsWith(".invalid")) {
            violations.add("rule staging-support-email: app.support.email must be set to a .invalid address in staging");
        }
        if (smsModeOf(env) != SmsMode.CAPTURE) {
            violations.add("rule staging-sms-mode: beautica.sms.mode must be capture in staging");
        }
    }

    private static String supportEmail(Environment env) {
        String v = readOrNull(env, "app.support.email");
        String e = v == null ? "" : v.strip().toLowerCase(Locale.ROOT);
        return e.endsWith(".") ? e.substring(0, e.length() - 1) : e;
    }

    private static boolean isEmailShaped(String email) {
        return EMAIL_SHAPE.matcher(email).matches();
    }

    static String mailHost(Environment env) {
        String host = env.getProperty("spring.mail.host", "").strip().toLowerCase(Locale.ROOT);
        return host.endsWith(".") ? host.substring(0, host.length() - 1) : host;
    }

    static String mailKind(Environment env) {
        String host = mailHost(env);
        if (host.contains("brevo")) {
            return "brevo";
        }
        return host.isEmpty() || isCaptureHost(host) ? "capture" : "other";
    }

    static boolean r2Enabled(Environment env) {
        return Boolean.parseBoolean(env.getProperty("app.cloudflare-r2.enabled", "false"));
    }

    static String r2Bucket(Environment env) {
        return r2Enabled(env) ? env.getProperty("app.cloudflare-r2.bucket", "") : "off";
    }

    /** Staging capture hosts: loopback, exactly {@code mailpit}, or a Railway private-network host. Input is normalized by {@link #mailHost}. */
    private static boolean isCaptureHost(String host) {
        return (!host.isEmpty() && isLoopback(host)) || host.equals("mailpit") || host.endsWith(".railway.internal");
    }

    private static boolean isLoopback(String host) {
        return host.isEmpty() || host.equals("localhost") || host.startsWith("127.")
                || host.equals("::1") || host.equals("[::1]") || host.equals("0.0.0.0");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
