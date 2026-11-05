package com.beautica.review.invite.service;

import com.beautica.auth.SecureTokenGenerator;
import com.beautica.config.OtpPepperConfig;
import com.beautica.review.invite.config.ReviewInviteProperties;
import com.beautica.review.invite.support.ReviewInviteTestProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 2026-11-05 staging regression: the guest review invite (SMS and, through {@code GuestMessageRouter}, Telegram)
 * carried only the link because {@code APP_BOOKING_REVIEW_INVITE_SMS_TEXT} overrode the copy. Proves in a real Spring
 * context that (a) a long staging origin over the two-segment budget still boots and renders the full thank-you copy,
 * and (b) the legacy property / env var — still set on staging — no longer changes the rendered text.
 */
@DisplayName("ReviewInviteSmsText in a Spring context — 2026-11-05 staging link-only regression")
class ReviewInviteSmsTextContextTest {

    /** The staging website origin shape that pushed the full copy past 134 chars. */
    private static final String STAGING_ORIGIN = "https://dev-beautica-site.beautica-ua.workers.dev";
    private static final String LEGACY_PROPERTY = "app.booking.review-invite.sms-text";
    private static final String LEGACY_ENV_VAR = "APP_BOOKING_REVIEW_INVITE_SMS_TEXT";
    private static final String LEGACY_LINK_ONLY = "only-link/{token}";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class))
            .withUserConfiguration(InviteTextConfig.class, ReviewInviteSmsText.class);
    private final SecureTokenGenerator tokens =
            new SecureTokenGenerator(new OtpPepperConfig("unit-test-otp-pepper-min-32-characters!"));

    @Test
    @DisplayName("staging origin over the 134-char budget: context starts and the invite carries the full thank-you copy")
    void should_bootAndRenderFullCopy_when_stagingOriginExceedsBudget() {
        String token = tokens.generateToken(ReviewInviteSendService.TOKEN_BYTES);
        int rendered = ReviewInviteProperties.SMS_COPY.length() + STAGING_ORIGIN.length()
                + ReviewInviteProperties.REVIEW_PATH.length() + token.length();
        assertThat(rendered).as("fixture must overrun two UCS-2 segments, rendered=%s", rendered)
                .isGreaterThan(ReviewInviteProperties.MAX_UCS2_CHARS);

        runner.withPropertyValues("app.frontend.base-url=" + STAGING_ORIGIN).run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ReviewInviteSmsText.class);

            String text = context.getBean(ReviewInviteSmsText.class).render(token);

            assertThat(text).isEqualTo(ReviewInviteProperties.SMS_COPY + STAGING_ORIGIN
                    + ReviewInviteProperties.REVIEW_PATH + token);
        });
    }

    @Test
    @DisplayName("legacy app.booking.review-invite.sms-text=only-link/{token} is ignored — copy stays, binding still starts")
    void should_ignoreLegacySmsTextProperty_when_set() {
        String token = tokens.generateToken(ReviewInviteSendService.TOKEN_BYTES);

        runner.withPropertyValues("app.frontend.base-url=" + STAGING_ORIGIN, LEGACY_PROPERTY + "=" + LEGACY_LINK_ONLY)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(ReviewInviteProperties.class);

                    String text = context.getBean(ReviewInviteSmsText.class).render(token);

                    assertThat(text).startsWith(ReviewInviteProperties.SMS_COPY)
                            .isEqualTo(ReviewInviteProperties.SMS_COPY + STAGING_ORIGIN
                                    + ReviewInviteProperties.REVIEW_PATH + token)
                            .doesNotContain("only-link");
                });
    }

    @Test
    @DisplayName("legacy APP_BOOKING_REVIEW_INVITE_SMS_TEXT env var (still set on staging) is ignored on the prod origin")
    void should_ignoreLegacySmsTextEnvVar_when_setWithProdOrigin() {
        String token = tokens.generateToken(ReviewInviteSendService.TOKEN_BYTES);

        runner.withPropertyValues("app.frontend.base-url=" + ReviewInviteProperties.PROD_ORIGIN)
                .withInitializer(context -> context.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource(
                                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                                Map.of(LEGACY_ENV_VAR, LEGACY_LINK_ONLY))))
                .run(context -> {
                    assertThat(context.getEnvironment().getProperty(LEGACY_PROPERTY))
                            .as("env var must resolve through relaxed binding, or the test proves nothing")
                            .isEqualTo(LEGACY_LINK_ONLY);
                    assertThat(context).hasNotFailed();

                    String text = context.getBean(ReviewInviteSmsText.class).render(token);

                    assertThat(text).isEqualTo(ReviewInviteTestProperties.PROD_SMS_PREFIX + token);
                });
    }

    @Test
    @DisplayName("audit cycle 3: an unbindable app.booking.review-invite.enabled=maybe fails context startup")
    void should_failStartup_when_reviewInviteEnabledFlagMalformed() {
        runner.withPropertyValues("app.frontend.base-url=" + ReviewInviteProperties.PROD_ORIGIN,
                        "app.booking.review-invite.enabled=maybe")
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().hasStackTraceContaining("app.booking.review-invite")
                        .hasRootCauseInstanceOf(IllegalArgumentException.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ReviewInviteProperties.class)
    static class InviteTextConfig {
    }
}
