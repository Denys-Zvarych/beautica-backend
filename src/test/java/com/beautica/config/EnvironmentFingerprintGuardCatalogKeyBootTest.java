package com.beautica.config;

import com.beautica.support.TestSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * B501 — {@code INTERNAL_CATALOG_KEY} through a REAL {@code SpringApplication} boot on the committed {@code prod} and
 * {@code prod,staging} YAML (the env var reaches {@code app.internal-catalog-key} via {@code application.yml}): a short
 * key or one equal to {@code INTERNAL_API_KEY} aborts startup before any context; messages name the rule, never a value.
 */
@DisplayName("EnvironmentFingerprintGuard (boot) — INTERNAL_CATALOG_KEY length and reuse on prod and staging")
class EnvironmentFingerprintGuardCatalogKeyBootTest {

    private static final String SHORT_KEY = "catalog-short";
    private static final String VALID_KEY = "test-internal-catalog-key-bbbbbbbbbbbbbbbb";

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {
    }

    private static Throwable bootProd(String catalogKey) {
        return catchThrowable(() -> boot(List.of("prod"), List.of("--FRONTEND_BASE_URL=https://app.beautica.ua",
                "--PUBLIC_BASE_URL=https://api.beautica.ua", "--SUPPORT_EMAIL=help@beautica.ua",
                "--INTERNAL_CATALOG_KEY=" + catalogKey)).close());
    }

    private static Throwable bootStaging(String catalogKey) {
        return catchThrowable(() -> boot(List.of("prod", "staging"), List.of("--FRONTEND_BASE_URL=https://x.invalid",
                "--PUBLIC_BASE_URL=https://api.staging.beautica.test", "--SUPPORT_EMAIL=support@test.invalid",
                "--INTERNAL_CATALOG_KEY=" + catalogKey)).close());
    }

    private static ConfigurableApplicationContext boot(List<String> profiles, List<String> extra) {
        List<String> args = new ArrayList<>(List.of("--spring.main.banner-mode=off", "--JWT_SECRET=x",
                "--INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY));
        args.addAll(extra);
        return new SpringApplicationBuilder(EmptyConfig.class).web(WebApplicationType.NONE)
                .profiles(profiles.toArray(String[]::new)).run(args.toArray(String[]::new));
    }

    @Test
    @DisplayName("prod + short INTERNAL_CATALOG_KEY → startup aborts naming internal-catalog-key-length, no value")
    void should_abortBoot_when_prodCatalogKeyTooShort() {
        Throwable thrown = bootProd(SHORT_KEY);

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal-catalog-key-length").hasMessageContaining("app.internal-catalog-key");
        assertThat(thrown.getMessage()).doesNotContain(SHORT_KEY);
    }

    @Test
    @DisplayName("prod + INTERNAL_CATALOG_KEY equal to INTERNAL_API_KEY → startup aborts naming internal-catalog-key-reused")
    void should_abortBoot_when_prodCatalogKeyEqualsMasterKey() {
        Throwable thrown = bootProd(TestSecrets.INTERNAL_API_KEY);

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal-catalog-key-reused");
        assertThat(thrown.getMessage()).doesNotContain(TestSecrets.INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("prod,staging + short INTERNAL_CATALOG_KEY → startup aborts naming internal-catalog-key-length")
    void should_abortBoot_when_stagingCatalogKeyTooShort() {
        Throwable thrown = bootStaging(SHORT_KEY);

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal-catalog-key-length");
        assertThat(thrown.getMessage()).doesNotContain(SHORT_KEY);
    }

    @Test
    @DisplayName("prod,staging + INTERNAL_CATALOG_KEY equal to INTERNAL_API_KEY → startup aborts naming internal-catalog-key-reused")
    void should_abortBoot_when_stagingCatalogKeyEqualsMasterKey() {
        Throwable thrown = bootStaging(TestSecrets.INTERNAL_API_KEY);

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal-catalog-key-reused");
        assertThat(thrown.getMessage()).doesNotContain(TestSecrets.INTERNAL_API_KEY);
    }

    @Test
    @DisplayName("control — prod,staging + a distinct >= 32-char INTERNAL_CATALOG_KEY boots")
    void should_boot_when_stagingCatalogKeyValid() {
        assertThat(bootStaging(VALID_KEY)).isNull();
    }

    @Test
    @DisplayName("control — prod,staging with INTERNAL_CATALOG_KEY blank (disabled) boots")
    void should_boot_when_stagingCatalogKeyBlank() {
        assertThat(bootStaging("")).isNull();
    }
}
