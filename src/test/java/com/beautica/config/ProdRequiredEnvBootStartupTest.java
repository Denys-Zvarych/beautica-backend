package com.beautica.config;

import com.beautica.support.TestSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Phase 367 D5/D9 — every new PROD rule of {@link EnvironmentFingerprintGuard}, exercised through a REAL
 * {@link org.springframework.boot.SpringApplication} startup with the committed {@code application*.yml} and
 * the guard loaded from {@code META-INF/spring.factories} (single {@code DeferredLogFactory} constructor).
 * {@code EnvironmentFingerprintGuardTest} / {@code ProdProfileFingerprintTest} call {@code verify} directly
 * and cannot prove the rules actually abort a boot, nor that the deferred WARN is flushed. No database is
 * contacted: the context holds one empty configuration class.
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Phase 367 prod required-env rules — inside a real Spring Boot startup")
class ProdRequiredEnvBootStartupTest {

    private static final String SENTRY_DSN = "https://0123456789abcdef@o42.ingest.de.sentry.io/4500000000000001";

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {
    }

    /** A complete, passing prod variable set; each test overrides or removes exactly one entry. */
    private static Map<String, String> passingProdVars() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("DATABASE_URL", "postgresql://postgres:pw@postgres.railway.internal:5432/railway");
        vars.put("R2_ENABLED", "true");
        vars.put("CLOUDFLARE_R2_BUCKET", "beautica-media-prod");
        vars.put("CLOUDFLARE_R2_PUBLIC_URL", "https://media.beautica.ua");
        vars.put("FRONTEND_BASE_URL", "https://app.beautica.ua");
        vars.put("PUBLIC_BASE_URL", "https://api.beautica.ua");
        vars.put("SUPPORT_EMAIL", "help@beautica.ua");
        vars.put("INTERNAL_API_KEY", TestSecrets.INTERNAL_API_KEY);
        // Phase 374 D6: prod sends over the Brevo HTTPS API; BREVO_SMTP_* are no longer set in prod.
        vars.put("BREVO_API_KEY", "xkeysib-fixture-api-key");
        vars.put("SENTRY_DSN", SENTRY_DSN);
        vars.put("TURBOSMS_TOKEN", "turbosms-fixture-token");
        vars.put("ADMIN_EMAIL", "admin@beautica.ua");
        // Phase 382 D7: prod requires a REAL (non-Cloudflare-test) Turnstile secret.
        vars.put("TURNSTILE_SECRET_KEY", "0x4AAAAAAAfixtureNotARealSecretZZ9");
        return vars;
    }

    private static SpringApplicationBuilder prodApp(Map<String, String> vars) {
        List<String> props = new ArrayList<>();
        props.add("spring.main.banner-mode=off");
        vars.forEach((k, v) -> props.add(k + "=" + v));
        return new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .properties(props.toArray(String[]::new));
    }

    private static List<Throwable> chain(Throwable t) {
        List<Throwable> all = new ArrayList<>();
        for (Throwable c = t; c != null && !all.contains(c); c = c.getCause()) {
            all.add(c);
        }
        return all;
    }

    /** Boots, asserts the boot aborted on the guard, and returns the guard's message. */
    private static String guardMessageOfFailedBoot(Map<String, String> vars, String... args) {
        Throwable thrown = catchThrowable(() -> prodApp(vars).run(args).close());
        assertThat(thrown).as("prod startup must abort, not boot").isNotNull();
        Throwable guard = chain(thrown).stream()
                .filter(t -> t instanceof IllegalStateException
                        && t.getMessage() != null && t.getMessage().startsWith("Environment fingerprint mismatch"))
                .findFirst().orElse(null);
        assertThat(guard).as("the fingerprint guard must be in the cause chain, actual chain=%s", chain(thrown))
                .isNotNull();
        return guard.getMessage();
    }

    private static int occurrences(String haystack, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("control — the full prod variable set boots with a Brevo API key, no SMTP creds, and no prod-brevo-unconfigured WARN")
    void should_boot_when_everyRequiredProdVariableIsReal(CapturedOutput output) {
        try (ConfigurableApplicationContext ctx = prodApp(passingProdVars()).run()) {
            assertThat(ctx.isActive()).as("prod context must be up").isTrue();
        }
        assertThat(output.getAll()).doesNotContain("rule prod-brevo-unconfigured");
    }

    @Test
    @DisplayName("sentry-dsn-required — prod boot without SENTRY_DSN aborts, the message carries no DSN fragment")
    void should_abortBoot_when_prodSentryDsnMissing() {
        Map<String, String> vars = passingProdVars();
        vars.remove("SENTRY_DSN");

        String message = guardMessageOfFailedBoot(vars);

        assertThat(message).contains("rule sentry-dsn-required").doesNotContain("sentry.io");
    }

    @Test
    @DisplayName("prod-turbosms-token-blank — prod boot without TURBOSMS_TOKEN aborts")
    void should_abortBoot_when_prodTurbosmsTokenMissing() {
        Map<String, String> vars = passingProdVars();
        vars.remove("TURBOSMS_TOKEN");

        assertThat(guardMessageOfFailedBoot(vars)).contains("rule prod-turbosms-token-blank");
    }

    @Test
    @DisplayName("prod-turnstile-secret-blank — prod boot without TURNSTILE_SECRET_KEY aborts")
    void should_abortBoot_when_prodTurnstileSecretMissing() {
        Map<String, String> vars = passingProdVars();
        vars.remove("TURNSTILE_SECRET_KEY");

        assertThat(guardMessageOfFailedBoot(vars)).contains("rule prod-turnstile-secret-blank");
    }

    @Test
    @DisplayName("prod-turnstile-secret-test-key — prod boot with a Cloudflare test secret aborts, value not echoed")
    void should_abortBoot_when_prodTurnstileSecretIsCloudflareTestKey() {
        Map<String, String> vars = passingProdVars();
        vars.put("TURNSTILE_SECRET_KEY", "1x0000000000000000000000000000000AA");

        assertThat(guardMessageOfFailedBoot(vars)).contains("rule prod-turnstile-secret-test-key")
                .doesNotContain("1x0000000000000000000000000000000AA");
    }

    @Test
    @DisplayName("prod-turbosms-token-placeholder — prod boot with a too-short TURBOSMS_TOKEN aborts, value not echoed")
    void should_abortBoot_when_prodTurbosmsTokenIsPlaceholder() {
        Map<String, String> vars = passingProdVars();
        vars.put("TURBOSMS_TOKEN", "zq77-short-tok"); // 14 chars < MIN_SMS_TOKEN_LENGTH

        String message = guardMessageOfFailedBoot(vars);

        assertThat(message).contains("rule prod-turbosms-token-placeholder")
                .doesNotContain("rule prod-turbosms-token-blank")
                .doesNotContain("zq77-short-tok");
    }

    @Test
    @DisplayName("prod-admin-email-unset — prod boot without ADMIN_EMAIL aborts although the yml default would apply")
    void should_abortBoot_when_prodAdminEmailMissing() {
        Map<String, String> vars = passingProdVars();
        vars.remove("ADMIN_EMAIL");

        assertThat(guardMessageOfFailedBoot(vars)).contains("rule prod-admin-email-unset");
    }

    @Test
    @DisplayName("prod-admin-email-placeholder — prod boot with ADMIN_EMAIL on a reserved example domain aborts, address not echoed")
    void should_abortBoot_when_prodAdminEmailIsOnExampleDomain() {
        Map<String, String> vars = passingProdVars();
        vars.put("ADMIN_EMAIL", "ops-zq77@example.com");

        String message = guardMessageOfFailedBoot(vars);

        assertThat(message).contains("rule prod-admin-email-placeholder").doesNotContain("ops-zq77");
    }

    @Test
    @DisplayName("prod-admin-email-effective — a command-line app.admin-email override to a test domain aborts the boot "
            + "even though ADMIN_EMAIL itself is real")
    void should_abortBoot_when_effectiveAdminEmailOverrideIsPlaceholder() {
        String message = guardMessageOfFailedBoot(passingProdVars(), "--app.admin-email=override-zq77@beautica.test");

        assertThat(message).contains("rule prod-admin-email-effective")
                .doesNotContain("rule prod-admin-email-unset")
                .doesNotContain("override-zq77");
    }

    @Test
    @DisplayName("Phase 374 — leftover BREVO_SMTP_* = PENDING are irrelevant on the API transport: boots, no SMTP WARN")
    void should_bootWithoutSmtpWarn_when_prodSmtpCredentialsPendingOnApiTransport(CapturedOutput output) {
        Map<String, String> vars = passingProdVars();
        vars.put("BREVO_SMTP_LOGIN", "PENDING");
        vars.put("BREVO_SMTP_KEY", "PENDING");

        try (ConfigurableApplicationContext ctx = prodApp(vars).run()) {
            assertThat(ctx.isActive()).isTrue();
        }

        assertThat(output.getAll()).doesNotContain("rule prod-brevo-unconfigured");
    }

    @Test
    @DisplayName("Phase 374 D8 — prod-brevo-api-key-missing: prod boot without BREVO_API_KEY aborts")
    void should_abortBoot_when_prodBrevoApiKeyMissing() {
        Map<String, String> vars = passingProdVars();
        vars.remove("BREVO_API_KEY");

        assertThat(guardMessageOfFailedBoot(vars)).contains("rule prod-brevo-api-key-missing").contains("BREVO_API_KEY");
    }

    @Test
    @DisplayName("Phase 374 D8 — prod-brevo-api-key-missing: BREVO_API_KEY=PENDING aborts the boot (was a WARN for SMTP)")
    void should_abortBoot_when_prodBrevoApiKeyPending() {
        Map<String, String> vars = passingProdVars();
        vars.put("BREVO_API_KEY", "PENDING");

        assertThat(guardMessageOfFailedBoot(vars)).contains("rule prod-brevo-api-key-missing");
    }

    @Test
    @DisplayName("Phase 374 audit — prod-mail-transport: an app.mail.transport=smtp override aborts the prod boot")
    void should_failProdBoot_when_mailTransportIsSmtp() {
        Map<String, String> vars = passingProdVars();
        vars.put("BREVO_SMTP_LOGIN", "PENDING");
        vars.put("BREVO_SMTP_KEY", "PENDING");

        String message = guardMessageOfFailedBoot(vars, "--app.mail.transport=smtp");

        assertThat(message).contains("rule prod-mail-transport").doesNotContain("prod-brevo-api-key-missing");
    }

    @Test
    @DisplayName("Phase 374 audit — prod-brevo-api-base-url: a base-url override away from https://api.brevo.com aborts the boot")
    void should_failProdBoot_when_brevoApiBaseUrlNotBrevoHttps() {
        String message = guardMessageOfFailedBoot(passingProdVars(),
                "--app.mail.brevo-api.base-url=https://brevo-zq374.example.net");

        assertThat(message).contains("rule prod-brevo-api-base-url").doesNotContain("brevo-zq374");
    }

    @Test
    @DisplayName("Phase 374 audit — prod-mail-logo-url: a logo-url outside https://beautica.app/ aborts the boot")
    void should_failProdBoot_when_logoUrlHostNotBeautica() {
        String message = guardMessageOfFailedBoot(passingProdVars(),
                "--app.mail.logo-url=https://cdn.example.org/logo.png");

        assertThat(message).contains("rule prod-mail-logo-url");
    }
}
