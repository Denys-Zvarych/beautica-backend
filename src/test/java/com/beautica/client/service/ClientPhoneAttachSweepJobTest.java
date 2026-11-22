package com.beautica.client.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ClientPhoneAttachSweepJob} (B527 audit cycle 2): a thin scheduled entry point. The
 * "one failing phone does not stop the run" contract lives in {@link ClientPhoneAttachService#sweep} and is pinned by
 * {@code ClientPhoneAttachServiceTest#should_continueTheSweep_when_onePhoneFails}; here we pin the delegation, the
 * property gate and the scheduling wiring.
 */
@DisplayName("ClientPhoneAttachSweepJob — unit")
class ClientPhoneAttachSweepJobTest {

    private static final String ENABLED_PROPERTY = "app.client-phone-attach.sweep.enabled";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(MockServiceConfig.class)
            .withBean(ClientPhoneAttachSweepJob.class)
            .withPropertyValues("app.client-phone-attach.sweep.max-phones=37");

    @Test
    @DisplayName("sweep — delegates to the service with the configured max-phones cap")
    void should_callServiceSweepWithConfiguredMaxPhones_when_jobRuns() {
        ClientPhoneAttachService service = mock(ClientPhoneAttachService.class);
        when(service.sweep(anyInt())).thenReturn(3);
        ClientPhoneAttachSweepJob job = new ClientPhoneAttachSweepJob(service, 37);

        job.sweep();

        verify(service).sweep(37);
        verifyNoMoreInteractions(service);
    }

    @Test
    @DisplayName("sweep — the max-phones cap defaults to 200 when the property is absent")
    void should_useDefaultCapOf200_when_propertyAbsent() {
        new ApplicationContextRunner()
                .withUserConfiguration(MockServiceConfig.class)
                .withBean(ClientPhoneAttachSweepJob.class)
                .run(ctx -> {
                    ctx.getBean(ClientPhoneAttachSweepJob.class).sweep();

                    verify(ctx.getBean(ClientPhoneAttachService.class)).sweep(200);
                });
    }

    @Test
    @DisplayName("job bean — is registered when the enabled property is true or missing")
    void should_registerJob_when_enabledPropertyTrueOrMissing() {
        runner.withPropertyValues(ENABLED_PROPERTY + "=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(ClientPhoneAttachSweepJob.class));
        runner.run(ctx -> assertThat(ctx).hasSingleBean(ClientPhoneAttachSweepJob.class));
    }

    @Test
    @DisplayName("job bean — is NOT registered when app.client-phone-attach.sweep.enabled=false")
    void should_notRegisterJob_when_enabledPropertyFalse() {
        runner.withPropertyValues(ENABLED_PROPERTY + "=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(ClientPhoneAttachSweepJob.class);
                    verify(ctx.getBean(ClientPhoneAttachService.class), never()).sweep(anyInt());
                });
    }

    @Test
    @DisplayName("sweep — is a property-driven @Scheduled method (30 min delay, 5 min initial delay by default)")
    void should_beScheduledWithPropertyDrivenDelays_when_annotationInspected() throws NoSuchMethodException {
        Method method = ClientPhoneAttachSweepJob.class.getMethod("sweep");

        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        assertThat(scheduled).as("sweep must be @Scheduled").isNotNull();
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${app.client-phone-attach.sweep.fixed-delay-ms:1800000}");
        assertThat(scheduled.initialDelayString())
                .isEqualTo("${app.client-phone-attach.sweep.initial-delay-ms:300000}");
    }

    @Configuration
    static class MockServiceConfig {
        @Bean
        ClientPhoneAttachService clientPhoneAttachService() {
            return mock(ClientPhoneAttachService.class);
        }
    }
}
