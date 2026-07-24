package com.beautica.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice tests for {@link S3Config} — verifies the {@link S3Client} bean activation policy.
 *
 * <p>Three scenarios are exercised:
 * <ol>
 *   <li>Enabled + all credentials present → bean is registered.</li>
 *   <li>Disabled → bean is absent.</li>
 *   <li>Enabled + blank {@code account-id} → context refresh fails fast.</li>
 * </ol>
 *
 * <p>Scenarios 1 and 2 use {@link Nested} {@link SpringBootTest} classes so each property
 * set produces its own context — this avoids {@code @DirtiesContext}, which is banned by
 * project conventions. The runtime cost is two extra context starts; this is acceptable
 * because no DB, no web environment, and no auto-configuration sweep are loaded — only
 * {@link S3Config} itself.
 *
 * <p>Scenario 3 uses {@link ApplicationContextRunner} so the {@link IllegalStateException}
 * thrown inside the bean factory is captured as {@code context.getStartupFailure()} instead
 * of aborting the JUnit harness — {@code @SpringBootTest} would abort the test before the
 * test body runs when the context refresh blows up.
 */
@DisplayName("S3Config — S3Client bean activation policy")
class S3ConfigTest {

    @Nested
    @DisplayName("when R2 is enabled with all credentials present")
    @SpringBootTest(
            classes = S3Config.class,
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                    "app.cloudflare-r2.enabled=true",
                    "app.cloudflare-r2.account-id=test-account",
                    "app.cloudflare-r2.access-key-id=test-key",
                    "app.cloudflare-r2.secret-access-key=test-secret"
            }
    )
    class EnabledWithCredentials {

        @Autowired
        private Optional<S3Client> s3Client;

        @Test
        @DisplayName("registers S3Client bean when R2 is enabled and all credentials are present")
        void should_registerS3ClientBean_when_r2EnabledAndAllCredentialsPresent() {
            assertThat(s3Client)
                    .as("S3Client bean must be registered when feature flag is on and credentials are configured")
                    .isPresent()
                    .get()
                    .isNotNull();
        }
    }

    @Nested
    @DisplayName("when R2 is disabled")
    @SpringBootTest(
            classes = S3Config.class,
            webEnvironment = SpringBootTest.WebEnvironment.NONE,
            properties = {
                    "app.cloudflare-r2.enabled=false"
            }
    )
    class Disabled {

        @Autowired
        private Optional<S3Client> s3Client;

        @Test
        @DisplayName("does not register S3Client bean when R2 is disabled")
        void should_notRegisterS3ClientBean_when_r2Disabled() {
            assertThat(s3Client)
                    .as("S3Client bean must be absent so R2StorageService injects Optional.empty()")
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("fails startup with IllegalStateException naming the missing property when R2 enabled and account-id is blank")
    void should_failStartup_when_r2EnabledAndAccountIdBlank() {
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withUserConfiguration(S3Config.class)
                .withPropertyValues(
                        "app.cloudflare-r2.enabled=true",
                        // account-id intentionally omitted — defaults to blank via @Value("${...:}")
                        "app.cloudflare-r2.access-key-id=test-key",
                        "app.cloudflare-r2.secret-access-key=test-secret"
                );

        contextRunner.run(context -> assertThat(context)
                .as("Bean factory must surface the misconfiguration as a startup failure")
                .hasFailed()
                .getFailure()
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.cloudflare-r2.account-id"));
    }
}
