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
        vars.put("BREVO_SMTP_LOGIN", "brevo-login-fixture");
        vars.put("BREVO_SMTP_KEY", "brevo-key-fixture");
        vars.put("SENTRY_DSN", SENTRY_DSN);
        vars.put("TURBOSMS_TOKEN", "turbosms-fixture-token");
        vars.put("ADMIN_EMAIL", "admin@beautica.ua");
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
    @DisplayName("control — the full prod variable set boots with real Brevo creds and logs no prod-brevo-unconfigured WARN")
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
    @DisplayName("prod-brevo-unconfigured — BREVO creds = PENDING still boot (D9) and the deferred WARN is flushed exactly once")
    void should_bootWithOneWarn_when_prodBrevoCredentialsPending(CapturedOutput output) {
        Map<String, String> vars = passingProdVars();
        vars.put("BREVO_SMTP_LOGIN", "PENDING");
        vars.put("BREVO_SMTP_KEY", "PENDING");

        try (ConfigurableApplicationContext ctx = prodApp(vars).run()) {
            assertThat(ctx.isActive()).as("Brevo PENDING must not block a prod boot").isTrue();
        }

        assertThat(occurrences(output.getAll(), "rule prod-brevo-unconfigured"))
                .as("exactly one WARN, flushed from the DeferredLog after logging init; output=%s", output.getAll())
                .isEqualTo(1);
    }
}
