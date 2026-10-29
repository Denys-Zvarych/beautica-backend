package com.beautica.config;

import com.beautica.notification.mail.MailTransportKind;
import com.beautica.review.invite.config.ReviewInviteProperties;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
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
    static final String DEV_INTERNAL_API_KEY = "changeme-dev-only";
    static final int MIN_INTERNAL_API_KEY_LENGTH = 32;
    static final String SENTRY_EU_HOST_SUFFIX = ".de.sentry.io";

    private static final java.util.regex.Pattern EMAIL_SHAPE =
            java.util.regex.Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[a-z]{2,}$", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final List<String> RESERVED_TEST_TLDS = List.of(".test", ".example", ".localhost", ".invalid", ".local", ".internal", ".home.arpa");

    private static final List<String> RESERVED_EXAMPLE_DOMAINS = List.of("example.com", "example.net", "example.org");

    private static final Profiles PROD = Profiles.of("prod");
    private static final Profiles STAGING = Profiles.of("staging");

    /** Placeholder an operator sets for {@code BREVO_SMTP_LOGIN}/{@code BREVO_SMTP_KEY} until Brevo exists (Phase 367 D9). */
    static final String BREVO_PENDING = "PENDING";

    /** Effective Brevo HTTPS API key (Phase 374); the yml resolves it from {@code BREVO_API_KEY}. */
    static final String BREVO_API_KEY_PROPERTY = "app.mail.brevo-api.api-key";
    static final String BREVO_API_BASE_URL_PROPERTY = "app.mail.brevo-api.base-url";
    static final String BREVO_API_ORIGIN = "https://api.brevo.com";
    static final String LOGO_URL_PROPERTY = "app.mail.logo-url";
    /** Prod e-mail logo must be served from our own site (same domain as the sender). */
    static final String PROD_LOGO_URL_PREFIX = "https://beautica.app/";

    /** Conservative floor for {@code TURBOSMS_TOKEN}; the provider documents no length (see {@link #isPlaceholderSecret}). */
    static final int MIN_SMS_TOKEN_LENGTH = 16;
    private static final List<String> SECRET_PLACEHOLDERS = List.of("pending", "changeme", "todo", "xxx", "tbd");

    private final Log log;

    /**
     * The ONLY public constructor, so Spring Boot's factory loader can only choose the
     * {@link DeferredLogFactory} path: warnings are buffered until the logging system is initialised.
     * (A second public no-arg constructor made the choice ambiguous and could silently log to a
     * not-yet-configured system.) Tests use the package-private {@link #verify(Environment, Log)}.
     */
    public EnvironmentFingerprintGuard(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(EnvironmentFingerprintGuard.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        verify(environment, log);
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
        verify(env, LogFactory.getLog(EnvironmentFingerprintGuard.class));
    }

    static void verify(Environment env, Log log) {
        DeployEnvironment environment = environmentOf(env);
        boolean prodProfile = env.acceptsProfiles(PROD);
        boolean stagingProfile = env.acceptsProfiles(STAGING);
        boolean onRailway = !isBlank(env.getProperty(RAILWAY_ENV_VAR));
        List<String> violations = new ArrayList<>();
        // Sentry rules run for every environment, including a genuinely local one (Phase 371, D6).
        checkSentry(env, environment, violations);
        if (environment == DeployEnvironment.LOCAL && !prodProfile && !stagingProfile && !onRailway) {
            failOnViolations(environment, violations);
            return;
        }
        checkProfiles(environment, prodProfile, stagingProfile, violations);
        checkRailwayName(env, environment, violations);
        if (environment != DeployEnvironment.LOCAL) {
            checkR2Bucket(env, environment, violations);
            checkLinkBaseUrls(env, environment, violations);
            checkInternalApiKey(env, violations);
            if (environment == DeployEnvironment.PROD) {
                checkProd(env, violations);
                if (brevoApiTransport(env)) {
                    checkBrevoApi(env, violations);
                } else {
                    // Phase 374: SMTP cannot leave Railway, so prod on smtp is a violation; the legacy WARN still
                    // prints so an operator sees both facts in one boot log.
                    violations.add("rule prod-mail-transport: app.mail.transport must be brevo-api in prod");
                    warnBrevoUnconfigured(env, log);
                }
            } else {
                checkStaging(env, violations);
            }
        }
        failOnViolations(environment, violations);
    }

    private static void failOnViolations(DeployEnvironment environment, List<String> violations) {
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Environment fingerprint mismatch (beautica.environment="
                    + environment.token() + "): " + String.join("; ", violations));
        }
    }

    /**
     * Phase 371 (D6). Messages name the rule and property only — never the DSN (it carries the client key).
     * <ul>
     *   <li>{@code sentry-local}: LOCAL (incl. test) may enable Sentry only with a loopback DSN (WireMock ITs).</li>
     *   <li>{@code sentry-region}: a staging/prod DSN must point at the EU ingest ({@value #SENTRY_EU_HOST_SUFFIX}).</li>
     *   <li>{@code sentry-debug} / {@code sentry-send-default-pii}: outside LOCAL both must be unset or false
 *       ({@code SENTRY_DEBUG=true} prints the DSN; default PII would attach IPs and user data).</li>
 *   <li>{@code sentry-environment}: when enabled, {@code sentry.environment} must equal the lower-case
     *       {@code beautica.environment} token (blocks a stray {@code SENTRY_ENVIRONMENT}).</li>
     *   <li>{@code sentry-dsn-required} (Phase 367 D5): a blank DSN is a violation in PROD only.</li>
     * </ul>
     * A blank DSN is allowed in LOCAL and STAGING (SDK disabled, banner {@code sentry=off}).
     */
    private static void checkSentry(Environment env, DeployEnvironment environment, List<String> violations) {
        boolean enabled = sentryEnabledFlag(env);
        String dsn = readOrNull(env, "sentry.dsn");
        if (isBlank(dsn)) {
            if (environment == DeployEnvironment.PROD) {
                violations.add("rule sentry-dsn-required: sentry.dsn (SENTRY_DSN) must be set in prod");
            }
        } else {
            String host = dsnHost(dsn);
            if (environment == DeployEnvironment.LOCAL) {
                if (enabled && (host == null || !isLoopback(host))) {
                    violations.add("rule sentry-local: sentry.enabled must be false in local unless sentry.dsn is loopback");
                }
            } else if (host == null || !host.endsWith(SENTRY_EU_HOST_SUFFIX)) {
                violations.add("rule sentry-region: sentry.dsn host must end with " + SENTRY_EU_HOST_SUFFIX
                        + " in " + environment.token());
            }
        }
        if (environment != DeployEnvironment.LOCAL) {
            // SENTRY_DEBUG=true makes the SDK print its options, DSN included, to stdout.
            if (!isUnsetOrFalse(readOrNull(env, "sentry.debug"))) {
                violations.add("rule sentry-debug: sentry.debug must be false in " + environment.token());
            }
            if (!isUnsetOrFalse(readOrNull(env, "sentry.send-default-pii"))) {
                violations.add("rule sentry-send-default-pii: sentry.send-default-pii must be false in "
                        + environment.token());
            }
        }
        if (enabled && !environment.token().equals(readOrNull(env, "sentry.environment"))) {
            violations.add("rule sentry-environment: sentry.environment must equal beautica.environment");
        }
    }

    /** Unset = the SDK default (false). Anything but a literal false (true, garbage) fails closed. */
    private static boolean isUnsetOrFalse(String raw) {
        return isBlank(raw) || "false".equalsIgnoreCase(raw.strip());
    }

    private static boolean sentryEnabledFlag(Environment env) {
        String raw = readOrNull(env, "sentry.enabled");
        return raw != null && Boolean.parseBoolean(raw.strip());
    }

    /** @return the normalised DSN host, or {@code null} when the DSN does not parse or has no host */
    private static String dsnHost(String dsn) {
        try {
            String host = new URI(dsn.strip()).getHost();
            return host == null || host.isBlank() ? null : normaliseHost(host);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** {@code on} only when Sentry is enabled and a DSN is set — the SDK is a no-op otherwise. Never the DSN. */
    static String sentryState(Environment env) {
        return sentryEnabledFlag(env) && !isBlank(readOrNull(env, "sentry.dsn")) ? "on" : "off";
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

    /**
     * The key guarding {@code /api/v1/internal/**} must be a real secret outside LOCAL: not blank, not the
     * well-known dev default, and at least {@value #MIN_INTERNAL_API_KEY_LENGTH} chars. Messages name the rule and
     * property only, never the value.
     */
    private static void checkInternalApiKey(Environment env, List<String> violations) {
        String key = readOrNull(env, "app.internal-api-key");
        if (isBlank(key)) {
            violations.add("rule internal-api-key-blank: app.internal-api-key (INTERNAL_API_KEY) must be set");
        } else if (DEV_INTERNAL_API_KEY.equals(key.strip())) {
            violations.add("rule internal-api-key-default: app.internal-api-key must not be the dev default");
        } else if (key.strip().length() < MIN_INTERNAL_API_KEY_LENGTH) {
            violations.add("rule internal-api-key-length: app.internal-api-key must be at least "
                    + MIN_INTERNAL_API_KEY_LENGTH + " characters");
        }
    }

    /**
     * The value the {@code @ConfigurationProperties} bean will actually bind (Phase 374 audit): {@link Binder} applies
     * relaxed binding, so an env-var spelling such as {@code APP_MAIL_BREVOAPI_BASEURL} — which
     * {@link Environment#getProperty} does not map back to {@code app.mail.brevo-api.base-url} — is seen here too.
     * An unbound value or an unresolvable placeholder counts as unset.
     */
    private static String boundOrNull(Environment env, String property) {
        try {
            return Binder.get(env).bind(property, String.class).orElse(null);
        } catch (org.springframework.boot.context.properties.bind.BindException e) {
            return null;
        }
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
        // Phase 367 D5: without these the app boots "healthy" but guest OTP 503s / admin mail goes to the yml default.
        String smsToken = firstNonBlank(readOrNull(env, "TURBOSMS_TOKEN"), readOrNull(env, "app.sms.turbosms.token"));
        if (isBlank(smsToken)) {
            violations.add("rule prod-turbosms-token-blank: TURBOSMS_TOKEN (app.sms.turbosms.token) must be set in prod");
        } else if (isPlaceholderSecret(smsToken)) {
            violations.add("rule prod-turbosms-token-placeholder: TURBOSMS_TOKEN (app.sms.turbosms.token) must be a real "
                    + "token (at least " + MIN_SMS_TOKEN_LENGTH + " characters, not a placeholder) in prod");
        }
        // The raw env var, not app.admin-email: that property carries the silent yml default.
        String adminEmail = readOrNull(env, "ADMIN_EMAIL");
        if (isBlank(adminEmail) || !isEmailShaped(adminEmail.strip())) {
            violations.add("rule prod-admin-email-unset: ADMIN_EMAIL must be explicitly set to an e-mail address in prod");
        } else if (isPlaceholderDomain(domainOf(adminEmail))) {
            violations.add("rule prod-admin-email-placeholder: ADMIN_EMAIL must not use a reserved test/example domain in prod");
        }
        checkProdTurnstile(env, violations);
        checkProdReviewInviteLink(env, violations);
        // The EFFECTIVE recipient: APP_ADMIN_EMAIL (relaxed binding of app.admin-email) overrides ADMIN_EMAIL.
        String effective = readOrNull(env, "app.admin-email");
        if (!isBlank(effective) && !effective.strip().equalsIgnoreCase(adminEmail == null ? "" : adminEmail.strip())
                && (!isEmailShaped(effective.strip()) || isPlaceholderDomain(domainOf(effective)))) {
            violations.add("rule prod-admin-email-effective: app.admin-email (APP_ADMIN_EMAIL override) must be a real "
                    + "address (not a reserved test/example domain) in prod");
        }
    }

    /**
     * Phase 400 audit cycle 1. The review-invite SMS carries a single-use review token; in PROD its link must point at
     * {@code beautica.app/r/{token}} (the website that serves the review page), so an
     * {@code APP_BOOKING_REVIEW_INVITE_SMS_TEXT} override cannot ship tokens to another host. Unset resolves to the
     * yml/binding default, which does. Names the rule and property only — never the configured text.
     */
    private static void checkProdReviewInviteLink(Environment env, List<String> violations) {
        if (!ReviewInviteProperties.linksToProdHost(
                boundOrNull(env, "app.booking.review-invite.sms-text"))) {
            violations.add("rule prod-review-invite-sms-link: app.booking.review-invite.sms-text must link to "
                    + ReviewInviteProperties.PROD_LINK + " in prod");
        }
    }

    /**
     * Phase 382 D7. Cloudflare's published always-pass / always-fail / forced-interaction TEST secrets
     * ({@code 1x}/{@code 2x}/{@code 3x} + 31 zeros + {@code AA}, 35 chars). The phase doc spells the
     * quantifier {@code {33}}, which does not match its own staging default; {@code {31}} is the real shape.
     */
    private static final java.util.regex.Pattern TURNSTILE_TEST_SECRET =
            java.util.regex.Pattern.compile("^[123]x0{31}AA$");

    private static final String TURNSTILE_SITEVERIFY_URL = "https://challenges.cloudflare.com/turnstile/v0/siteverify";

    private static void checkProdTurnstile(Environment env, List<String> violations) {
        if (!Boolean.parseBoolean(boundOrNull(env, "app.turnstile.enabled"))) {
            violations.add("rule prod-turnstile-disabled: app.turnstile.enabled must be true in prod");
        }
        // Phase 382 audit: a redirected siteverify URL would send the real secret and every visitor token to a
        // third party (or to a stub that always says success). Prod must call exactly Cloudflare.
        String siteverifyUrl = boundOrNull(env, "app.turnstile.siteverify-url");
        if (siteverifyUrl == null || !TURNSTILE_SITEVERIFY_URL.equals(siteverifyUrl.strip())) {
            violations.add("rule prod-turnstile-siteverify-url: app.turnstile.siteverify-url must be exactly "
                    + TURNSTILE_SITEVERIFY_URL + " in prod");
        }
        String secret = boundOrNull(env, "app.turnstile.secret-key");
        if (isBlank(secret)) {
            violations.add("rule prod-turnstile-secret-blank: TURNSTILE_SECRET_KEY (app.turnstile.secret-key) must be set in prod");
        } else if (TURNSTILE_TEST_SECRET.matcher(secret.strip()).matches()) {
            violations.add("rule prod-turnstile-secret-test-key: TURNSTILE_SECRET_KEY must not be a Cloudflare test secret in prod");
        }
    }

    /** Lower-cased domain of an e-mail address, trailing dot removed. */
    private static String domainOf(String email) {
        String e = email.strip().toLowerCase(Locale.ROOT);
        String domain = e.substring(e.lastIndexOf('@') + 1);
        return domain.endsWith(".") ? domain.substring(0, domain.length() - 1) : domain;
    }

    private static String firstNonBlank(String a, String b) {
        return isBlank(a) ? b : a;
    }

    /**
     * Too short to be a real provider token, or an obvious operator placeholder. TurboSMS does not document a
     * token length (its API page only calls it the HTTP API key from the cabinet), so the floor is deliberately
     * conservative: real keys are long random strings, never a short word.
     */
    private static boolean isPlaceholderSecret(String value) {
        String v = value.strip().toLowerCase(Locale.ROOT);
        return v.length() < MIN_SMS_TOKEN_LENGTH || SECRET_PLACEHOLDERS.contains(v);
    }

    /**
     * Phase 367 D9: prod may boot before Brevo exists ({@code BREVO_SMTP_LOGIN}/{@code BREVO_SMTP_KEY} set to
     * {@value #BREVO_PENDING}); the degraded mail state is logged, never fatal. Names the rule only, no values.
     */
    private static void warnBrevoUnconfigured(Environment env, Log log) {
        if (brevoPending(env)) {
            log.warn("rule prod-brevo-unconfigured: spring.mail.username/password (BREVO_SMTP_LOGIN/BREVO_SMTP_KEY) "
                    + "are blank or PENDING; outbound e-mail is not delivered until Brevo is configured");
        }
    }

    /**
     * Phase 374 D8: with {@code app.mail.transport=brevo-api} a blank or {@value #BREVO_PENDING} {@code BREVO_API_KEY}
     * fails the prod boot (replaces the {@code prod-brevo-unconfigured} WARN for this transport). Names the env var only.
     */
    private static void checkBrevoApi(Environment env, List<String> violations) {
        if (brevoApiKeyPending(env)) {
            violations.add("rule prod-brevo-api-key-missing: BREVO_API_KEY (app.mail.brevo-api.api-key) must be set "
                    + "in prod when app.mail.transport is brevo-api");
        }
        // The key is sent to this origin on every request: only the real Brevo API may receive it in prod. The
        // override exists for WireMock outside prod. Unset resolves to the binding default (the Brevo origin).
        String baseUrl = boundOrNull(env, BREVO_API_BASE_URL_PROPERTY);
        if (baseUrl != null && !BREVO_API_ORIGIN.equals(baseUrl)) {
            violations.add("rule prod-brevo-api-base-url: " + BREVO_API_BASE_URL_PROPERTY + " must be exactly "
                    + BREVO_API_ORIGIN + " in prod");
        }
        String logoUrl = boundOrNull(env, LOGO_URL_PROPERTY);
        if (logoUrl == null || !logoUrl.startsWith(PROD_LOGO_URL_PREFIX)) {
            violations.add("rule prod-mail-logo-url: " + LOGO_URL_PROPERTY + " must start with "
                    + PROD_LOGO_URL_PREFIX + " in prod");
        }
    }

    /** {@code true} when the effective Brevo API key is blank or the {@value #BREVO_PENDING} placeholder. */
    static boolean brevoApiKeyPending(Environment env) {
        return isBrevoPlaceholder(boundOrNull(env, BREVO_API_KEY_PROPERTY));
    }

    /** Phase 374 D2: the transport, resolved by the same rule as the bean condition. */
    static boolean brevoApiTransport(Environment env) {
        return MailTransportKind.of(env) == MailTransportKind.BREVO_API;
    }

    /** {@code true} when the mail login or key is blank or the {@value #BREVO_PENDING} placeholder (case-insensitive). */
    static boolean brevoPending(Environment env) {
        return isBrevoPlaceholder(readOrNull(env, "spring.mail.username"))
                || isBrevoPlaceholder(readOrNull(env, "spring.mail.password"));
    }

    private static boolean isBrevoPlaceholder(String value) {
        return isBlank(value) || BREVO_PENDING.equalsIgnoreCase(value.strip());
    }

    /**
     * {@code on} when push is switched on, else {@code off}. Resolves {@code FIREBASE_ENABLED} exactly as its
     * consumers do ({@code FirebaseConfig}/{@code InAppNotificationService}: {@code @Value("${FIREBASE_ENABLED:false}")}
     * through the Environment's boolean conversion, which also accepts {@code 1}/{@code yes}/{@code on}), not a
     * stricter {@code Boolean.parseBoolean} that would print {@code off} while push is actually on.
     */
    static String pushState(Environment env) {
        try {
            return Boolean.TRUE.equals(env.getProperty("FIREBASE_ENABLED", Boolean.class, false)) ? "on" : "off";
        } catch (IllegalArgumentException e) {
            return "off"; // unconvertible: the consumers fail boot on it, the banner must not throw
        }
    }

    private static void checkStaging(Environment env, List<String> violations) {
        if (brevoApiTransport(env)) {
            violations.add("rule staging-mail-transport: app.mail.transport must be smtp in staging");
        }
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
        if (!isBlank(readOrNull(env, "BREVO_API_KEY")) || !isBlank(readOrNull(env, BREVO_API_KEY_PROPERTY))) {
            violations.add("rule staging-brevo-key-blank: BREVO_API_KEY must be blank in staging");
        }
        String support = supportEmail(env);
        if (!isEmailShaped(support) || !support.endsWith(".invalid")) {
            violations.add("rule staging-support-email: app.support.email must be set to a .invalid address in staging");
        }
        if (smsModeOf(env) != SmsMode.CAPTURE) {
            violations.add("rule staging-sms-mode: beautica.sms.mode must be capture in staging");
        }
        // Phase 382 D7: a REAL secret on staging is a leak risk and would reject the test widget's dummy token.
        String turnstileSecret = boundOrNull(env, "app.turnstile.secret-key");
        if (isBlank(turnstileSecret) || !TURNSTILE_TEST_SECRET.matcher(turnstileSecret.strip()).matches()) {
            violations.add("rule staging-turnstile-secret-not-test-key: TURNSTILE_SECRET_KEY must be a Cloudflare "
                    + "test secret (or unset, for the default) in staging");
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
        if (brevoApiTransport(env)) {
            return "brevo-api";
        }
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
