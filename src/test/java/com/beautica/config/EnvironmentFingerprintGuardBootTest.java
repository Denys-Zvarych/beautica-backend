package com.beautica.config;

import com.beautica.support.TestSecrets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Phase 368 audit F1: the fingerprint guard must abort BEFORE any context, web server or scheduler exists.
 * Boots a real {@code SpringApplication} (servlet web type, the committed {@code prod,staging} YAML) with a
 * deliberately hostile {@code beautica.sms.mode} and records the lifecycle events that fired.
 */
@DisplayName("EnvironmentFingerprintGuard — fails during environment preparation, before context and web server")
class EnvironmentFingerprintGuardBootTest {

    @Configuration(proxyBeanMethods = false)
    static class EmptyConfig {
    }

    @Test
    @DisplayName("staging with sms.mode=turbosms — startup aborts at environment-prepared; no context, no web server")
    void should_abortBeforeContextAndWebServer_when_fingerprintMismatched() {
        List<String> events = new ArrayList<>();
        ApplicationListener<ApplicationEvent> recorder = e -> events.add(e.getClass().getSimpleName());

        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod", "staging")
                .listeners(recorder)
                .run("--beautica.sms.mode=turbosms", "--server.port=0", "--spring.main.banner-mode=off",
                        "--JWT_SECRET=x", "--INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "--FRONTEND_BASE_URL=https://x.invalid",
                        "--PUBLIC_BASE_URL=https://api.staging.beautica.test", "--SUPPORT_EMAIL=support@test.invalid"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("staging-sms-mode").hasMessageContaining("beautica.sms.mode");
        // The guard is itself a listener of the environment-prepared event, so only starting + failed are observable.
        assertThat(events).containsExactly("ApplicationStartingEvent", "ApplicationFailedEvent");
        assertThat(events).doesNotContain("ApplicationContextInitializedEvent", "ApplicationPreparedEvent",
                "ContextRefreshedEvent", "ApplicationStartedEvent", "ApplicationReadyEvent",
                WebServerInitializedEvent.class.getSimpleName(), "ServletWebServerInitializedEvent");
    }

    @Test
    @DisplayName("prod with sms.mode=capture — startup aborts before any context; message names the rule, no values")
    void should_abortBeforeContext_when_prodConfiguredWithCaptureSms() {
        List<String> events = new ArrayList<>();
        ApplicationListener<ApplicationEvent> recorder = e -> events.add(e.getClass().getSimpleName());

        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .listeners(recorder)
                .run("--beautica.sms.mode=capture", "--server.port=0", "--spring.main.banner-mode=off",
                        "--JWT_SECRET=x", "--INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "--FRONTEND_BASE_URL=https://app.beautica.ua",
                        "--PUBLIC_BASE_URL=https://api.beautica.ua", "--SUPPORT_EMAIL=help@beautica.ua"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod-sms-mode").hasMessageContaining("beautica.sms.mode");
        assertThat(events).containsExactly("ApplicationStartingEvent", "ApplicationFailedEvent");
    }

    @Test
    @DisplayName("RAILWAY_ENVIRONMENT_NAME=staging on a prod-only profile — startup aborts naming the railway rule")
    void should_abortBeforeContext_when_railwayStagingButProdOnlyProfile() {
        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .run("--RAILWAY_ENVIRONMENT_NAME=staging", "--server.port=0", "--spring.main.banner-mode=off",
                        "--JWT_SECRET=x", "--INTERNAL_API_KEY=" + TestSecrets.INTERNAL_API_KEY, "--FRONTEND_BASE_URL=https://app.beautica.ua",
                        "--PUBLIC_BASE_URL=https://api.beautica.ua", "--SUPPORT_EMAIL=help@beautica.ua"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("railway-environment-name").hasMessageContaining("RAILWAY_ENVIRONMENT_NAME");
    }

    @Test
    @DisplayName("prod with the dev default INTERNAL_API_KEY — startup aborts before any context; no value in message")
    void should_abortBeforeContext_when_prodUsesDevDefaultInternalApiKey() {
        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .run("--INTERNAL_API_KEY=changeme-dev-only", "--server.port=0", "--spring.main.banner-mode=off",
                        "--JWT_SECRET=x", "--FRONTEND_BASE_URL=https://app.beautica.ua",
                        "--PUBLIC_BASE_URL=https://api.beautica.ua", "--SUPPORT_EMAIL=help@beautica.ua"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal-api-key-default").hasMessageContaining("app.internal-api-key");
        assertThat(thrown.getMessage()).doesNotContain("changeme-dev-only");
    }

    @Test
    @DisplayName("prod,staging with a short INTERNAL_API_KEY — startup aborts naming the length rule")
    void should_abortBeforeContext_when_stagingInternalApiKeyTooShort() {
        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod", "staging")
                .run("--INTERNAL_API_KEY=short", "--server.port=0", "--spring.main.banner-mode=off",
                        "--JWT_SECRET=x", "--FRONTEND_BASE_URL=https://x.invalid",
                        "--PUBLIC_BASE_URL=https://api.staging.beautica.test", "--SUPPORT_EMAIL=support@test.invalid"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal-api-key-length");
    }

    @Test
    @DisplayName("prod with INTERNAL_API_KEY unset — startup aborts naming the blank rule")
    void should_abortBeforeContext_when_prodInternalApiKeyUnset() {
        Throwable thrown = catchThrowable(() -> new SpringApplicationBuilder(EmptyConfig.class)
                .web(WebApplicationType.SERVLET)
                .profiles("prod")
                .run("--server.port=0", "--spring.main.banner-mode=off",
                        "--JWT_SECRET=x", "--FRONTEND_BASE_URL=https://app.beautica.ua",
                        "--PUBLIC_BASE_URL=https://api.beautica.ua", "--SUPPORT_EMAIL=help@beautica.ua"));

        assertThat(thrown).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("internal-api-key-blank");
    }
}
