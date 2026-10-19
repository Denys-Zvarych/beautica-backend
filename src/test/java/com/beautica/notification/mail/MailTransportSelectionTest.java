package com.beautica.notification.mail;

import com.beautica.config.BrevoApiConnectionProbe;
import com.beautica.config.BrevoConnectionProbe;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailSender;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Mail transport selection — exactly one JavaMailSender (Phase 374 D2)")
class MailTransportSelectionTest {

    @Configuration(proxyBeanMethods = false)
    @Import({MailTransportConfig.class, BrevoConnectionProbe.class, BrevoApiConnectionProbe.class})
    static class Wiring {
        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(Wiring.class)
                .withPropertyValues("spring.mail.host=smtp-relay.brevo.com", "spring.mail.port=587",
                        "app.mail.logo-url=https://beautica.app/email/logo.png");
    }

    @Test
    @DisplayName("smtp → Boot's JavaMailSenderImpl, the SMTP probe, no API probe")
    void should_buildJavaMailSenderImpl_when_transportSmtp() {
        runner().withPropertyValues("app.mail.transport=smtp").run(ctx -> {
            assertThat(ctx).hasSingleBean(MailSender.class);
            assertThat(ctx.getBean(JavaMailSender.class)).isInstanceOf(JavaMailSenderImpl.class);
            assertThat(ctx).hasSingleBean(BrevoConnectionProbe.class).doesNotHaveBean(BrevoApiConnectionProbe.class);
        });
    }

    @Test
    @DisplayName("unset → smtp (local/test default)")
    void should_defaultToSmtp_when_transportUnset() {
        runner().run(ctx -> assertThat(ctx.getBean(JavaMailSender.class)).isInstanceOf(JavaMailSenderImpl.class));
    }

    @Test
    @DisplayName("brevo-api → BrevoApiMailSender only, never a JavaMailSenderImpl; API probe only")
    void should_buildOnlyBrevoApiMailSender_when_transportBrevoApi() {
        runner().withPropertyValues("app.mail.transport=brevo-api", "app.mail.brevo-api.api-key=xkeysib-x")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(MailSender.class);
                    assertThat(ctx.getBean(JavaMailSender.class)).isInstanceOf(BrevoApiMailSender.class);
                    assertThat(ctx).doesNotHaveBean(JavaMailSenderImpl.class);
                    assertThat(ctx).hasSingleBean(BrevoApiConnectionProbe.class)
                            .doesNotHaveBean(BrevoConnectionProbe.class);
                });
    }

    @Test
    @DisplayName("bogus transport value → the context fails to start")
    void should_failContext_when_transportBogus() {
        runner().withPropertyValues("app.mail.transport=bogus")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("brevo-api with a blank key → the bean still builds (the fingerprint guard owns the prod fatality)")
    void should_buildBean_when_brevoApiKeyBlank() {
        runner().withPropertyValues("app.mail.transport=brevo-api")
                .run(ctx -> assertThat(ctx.getBean(JavaMailSender.class)).isInstanceOf(BrevoApiMailSender.class));
    }

    @Test
    @DisplayName("logo-url must be blank or https — http fails the binding")
    void should_failContext_when_logoUrlNotHttps() {
        runner().withPropertyValues("app.mail.logo-url=http://beautica.app/email/logo.png")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("brevo-api without a hosted logo-url → startup fails (every templated mail would carry a CID part)")
    void should_failContext_when_brevoApiWithoutLogoUrl() {
        runner().withPropertyValues("app.mail.transport=brevo-api", "app.mail.logo-url=")
                .run(ctx -> assertThat(ctx).hasFailed().getFailure().rootCause()
                        .hasMessageContaining("app.mail.logo-url"));
    }

    @Test
    @DisplayName("audit — Boot's RestClient customizers (e.g. Sentry's interceptor, which buffers bodies) are NOT applied to the Brevo client")
    void should_ignoreRestClientCustomizers_when_buildingBrevoApiSender() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class,
                        org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration.class))
                .withUserConfiguration(Wiring.class)
                .withBean(org.springframework.boot.web.client.RestClientCustomizer.class,
                        () -> builder -> builder.requestInterceptor((req, body, exec) -> {
                            throw new IllegalStateException("customizer interceptor ran");
                        }))
                .withPropertyValues("app.mail.transport=brevo-api", "app.mail.brevo-api.api-key=xkeysib-x",
                        "app.mail.logo-url=https://beautica.app/email/logo.png",
                        "app.mail.brevo-api.base-url=http://127.0.0.1:1", "app.mail.brevo-api.connect-timeout=500ms")
                .run(ctx -> {
                    org.springframework.mail.SimpleMailMessage mail = new org.springframework.mail.SimpleMailMessage();
                    mail.setFrom("noreply@beautica.app");
                    mail.setTo("x@example.com");
                    mail.setSubject("s");
                    mail.setText("t");

                    org.assertj.core.api.Assertions.assertThatThrownBy(() -> ctx.getBean(JavaMailSender.class).send(mail))
                            .as("connection refused (transient) proves the request reached the network, not the interceptor")
                            .isInstanceOf(BrevoTransientMailException.class);
                });
    }
}
