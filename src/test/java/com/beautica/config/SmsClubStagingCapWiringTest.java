package com.beautica.config;

import com.beautica.notification.sms.OtpSmsSender;
import com.beautica.notification.sms.SmsService;
import com.beautica.notification.sms.StagingSmsDailyCap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 439 audit-fix — the staging SMSClub daily cap is wired only on staging in smsclub mode, and one instance is
 * shared by the booking and the OTP adapter. {@link ApplicationContextRunner} slice over {@link SmsConfig} (§M1).
 */
@DisplayName("SmsConfig — staging-only SMSClub daily cap wiring (phase 439 audit-fix)")
class SmsClubStagingCapWiringTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Dependencies.class, SmsConfig.class)
            .withPropertyValues("app.booking.sms.enabled=true");

    @Test
    @DisplayName("staging + smsclub: one cap bean, shared by the booking SmsService and the OTP sender")
    void should_shareOneCap_when_stagingRunsSmsClub() {
        runner.withPropertyValues("spring.profiles.active=prod,staging", "beautica.sms.mode=smsclub",
                "app.sms.smsclub.staging-daily-cap=7").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(StagingSmsDailyCap.class);
            StagingSmsDailyCap cap = context.getBean(StagingSmsDailyCap.class);
            Object booking = ReflectionTestUtils.getField(context.getBean(SmsService.class), "stagingDailyCap");
            Object otpDelegate = ReflectionTestUtils.getField(context.getBean(OtpSmsSender.class), "delegate");
            Object otp = ReflectionTestUtils.getField(otpDelegate, "stagingDailyCap");

            assertThat(booking).isSameAs(cap);
            assertThat(otp).isSameAs(cap);
            assertThat(ReflectionTestUtils.getField(cap, "dailyCap")).isEqualTo(7);
        });
    }

    @Test
    @DisplayName("prod + smsclub: no cap bean, so prod SMSClub sends are never capped")
    void should_notWireCap_when_prodRunsSmsClub() {
        runner.withPropertyValues("spring.profiles.active=prod", "beautica.sms.mode=smsclub").run(context -> {
            assertThat(context).hasNotFailed().doesNotHaveBean(StagingSmsDailyCap.class);

            assertThat(ReflectionTestUtils.getField(context.getBean(SmsService.class), "stagingDailyCap")).isNull();
        });
    }

    @Test
    @DisplayName("staging + capture: no cap bean, nothing real is sent")
    void should_notWireCap_when_stagingRunsCapture() {
        runner.withPropertyValues("spring.profiles.active=prod,staging", "beautica.sms.mode=capture")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(StagingSmsDailyCap.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class Dependencies {

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

        @Bean
        JavaMailSender javaMailSender() {
            return Mockito.mock(JavaMailSender.class);
        }

        @Bean
        TurbosmsProperties turbosmsProperties() {
            return new TurbosmsProperties();
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
