package com.beautica.notification.mail;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * Mail transport selection (Phase 374, D2). Exactly one {@link JavaMailSender} exists:
 * <ul>
 *   <li>{@code brevo-api}: this class declares {@link BrevoApiMailSender}; Boot's {@code MailSenderAutoConfiguration}
 *       is {@code @ConditionalOnMissingBean(MailSender.class)}, so no {@code JavaMailSenderImpl} is built — no SMTP
 *       socket can open.</li>
 *   <li>{@code smtp}: nothing is declared here; auto-configuration builds {@code JavaMailSenderImpl} exactly as
 *       before.</li>
 * </ul>
 * A blank {@code BREVO_API_KEY} still builds the bean: the fingerprint guard owns the prod fatality (D8).
 *
 * <p>The API transport cannot carry the inline CID logo (D4), so {@code brevo-api} without a hosted
 * {@code app.mail.logo-url} would fail every templated e-mail permanently at send time; that misconfiguration is
 * refused here, at startup, instead.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MailTransportProperties.class, BrevoApiProperties.class})
public class MailTransportConfig {

    @Bean
    @Conditional(MailTransportCondition.OnBrevoApi.class)
    public JavaMailSender brevoApiMailSender(ObjectProvider<ObservationRegistry> observationRegistry,
                                             BrevoApiProperties properties, MailTransportProperties mailProperties,
                                             MeterRegistry meterRegistry) {
        if (!mailProperties.hostedLogo()) {
            throw new IllegalStateException(
                    "app.mail.logo-url must be an https URL when app.mail.transport is brevo-api (no inline CID parts)");
        }
        return new BrevoApiMailSender(BrevoRestClients.cleanBuilder(observationRegistry), properties, meterRegistry);
    }
}
