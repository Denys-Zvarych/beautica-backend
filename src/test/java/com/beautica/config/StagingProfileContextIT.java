package com.beautica.config;

import com.beautica.notification.sms.CapturingOtpSmsSender;
import com.beautica.notification.sms.OtpSmsSender;
import com.beautica.notification.sms.SmsService;
import com.beautica.notification.sms.CapturingSmsService;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 368 — the real {@code prod,staging} profile pair boots, wires the capture senders, and a guest
 * OTP request is mailed to the capture inbox with zero calls to Turbosms. Mail transport is a
 * {@code @MockBean JavaMailSender} (no SMTP test server on the classpath); what is asserted is the
 * exact MIME message handed to the transport.
 *
 * <p>{@code inheritProfiles = false}: the {@code test} profile would disable {@code FlywayDataSourceConfig}
 * and hide the prod wiring this class exists to exercise. {@code EnvironmentFingerprintGuard} runs for
 * real here (staging is not skipped) and must pass with the staging overlay's own values.
 */
@DisplayName("prod,staging profile — capture wiring (Phase 368)")
class StagingProfileContextIT extends AbstractStagingProfileIT {

    private static final Pattern CODE = Pattern.compile("(\\d{6})");

    @Autowired
    private OtpSmsSender otpSmsSender;

    @Autowired
    private SmsService smsService;

    @Autowired
    private DeploymentProperties deployment;

    @Autowired
    private Environment environment;

    @Autowired
    private TestRestTemplate restTemplate;

    @MockBean
    private JavaMailSender mailSender;

    @Test
    @DisplayName("context boots with the capture beans, environment=staging, mode=capture")
    void should_wireCaptureSenders_when_prodAndStagingProfilesActive() {
        assertThat(deployment.environment()).isEqualTo(DeployEnvironment.STAGING);
        assertThat(deployment.sms().mode()).isEqualTo(SmsMode.CAPTURE);
        assertThat(otpSmsSender).isInstanceOf(CapturingOtpSmsSender.class);
        assertThat(smsService).isInstanceOf(CapturingSmsService.class);
        assertThat(environment.getProperty("spring.mail.host")).isEqualTo("mailpit.railway.internal");
    }

    @Autowired
    private org.springframework.context.ApplicationContext context;

    @Test
    @DisplayName("Phase 374 — staging overrides prod's brevo-api: SMTP transport, inline logo, SMTP probe only")
    void should_resolveSmtpTransport_when_prodAndStagingProfilesActive() {
        var mail = context.getBean(com.beautica.notification.mail.MailTransportProperties.class);

        assertThat(mail.transport()).isEqualTo(com.beautica.notification.mail.MailTransportKind.SMTP);
        assertThat(mail.hostedLogo()).isFalse();
        assertThat(context.getBeansOfType(com.beautica.notification.mail.BrevoApiMailSender.class)).isEmpty();
        assertThat(context.getBeansOfType(BrevoApiConnectionProbe.class)).isEmpty();
        assertThat(context.getBeansOfType(BrevoConnectionProbe.class)).hasSize(1);
    }

    @Test
    @DisplayName("guest OTP request — one mail to sms+380…@sms.staging.invalid carrying the code; Turbosms untouched")
    void should_mailOtpToCaptureInbox_when_guestRequestsOtp() throws Exception {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/book/otp/send",
                new HttpEntity<>("{\"phone\":\"+380671234567\"}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(mailSender).send(sent.capture());
        assertThat(sent.getValue().getRecipients(Message.RecipientType.TO)[0].toString())
                .isEqualTo("sms+380671234567@sms.staging.invalid");
        Matcher code = CODE.matcher(sent.getValue().getContent().toString());
        assertThat(code.find()).as("six-digit OTP in the mail body").isTrue();
        assertThat(turbosmsStub().getAllServeEvents()).as("no call reached the Turbosms stub").isEmpty();
    }

    @Test
    @DisplayName("mail failure is not swallowed — the guest gets 503 (existing burn-on-failure flow)")
    void should_return503_when_captureMailTransportFails() {
        when(mailSender.createMimeMessage()).thenReturn(new MimeMessage((Session) null));
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("down"))
                .when(mailSender).send(org.mockito.ArgumentMatchers.any(MimeMessage.class));
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> response = restTemplate.postForEntity("/api/v1/book/otp/send",
                new HttpEntity<>("{\"phone\":\"+380671234568\"}", headers), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(turbosmsStub().getAllServeEvents()).isEmpty();
    }
}
