package com.beautica.config;

import jakarta.mail.MessagingException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("BrevoConnectionProbe — prod SMTP auth probe (Phase 367 audit)")
class BrevoConnectionProbeTest {

    @SuppressWarnings("unchecked")
    private static BrevoConnectionProbe probeWith(MailSender sender) {
        ObjectProvider<MailSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return new BrevoConnectionProbe(provider);
    }

    @Test
    @DisplayName("SMTP testConnection succeeds — probe reports the relay reachable")
    void should_returnReachable_when_testConnectionSucceeds() throws Exception {
        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);

        boolean unreachable = probeWith(sender).isUnreachable();

        assertThat(unreachable).isFalse();
        verify(sender).testConnection();
    }

    @Test
    @DisplayName("SMTP auth fails — probe reports unreachable and logs the rule with the exception class only")
    void should_returnUnreachableAndLogRuleWithClassOnly_when_authFails(CapturedOutput output) throws Exception {
        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        doThrow(new MessagingException("535 auth failed for secret-login")).when(sender).testConnection();

        boolean unreachable = probeWith(sender).isUnreachable();

        assertThat(unreachable).isTrue();
        assertThat(output.getOut()).contains("prod-brevo-auth-failed").contains("MessagingException")
                .doesNotContain("secret-login");
    }

    @Test
    @DisplayName("relay does not answer within the wait — probe reports unreachable instead of blocking boot")
    void should_returnUnreachable_when_relayDoesNotAnswerWithinWait() throws Exception {
        JavaMailSenderImpl sender = mock(JavaMailSenderImpl.class);
        Object gate = new Object();
        org.mockito.Mockito.doAnswer(inv -> {
            synchronized (gate) {
                gate.wait(10_000);
            }
            return null;
        }).when(sender).testConnection();

        boolean unreachable = probeWith(sender).isUnreachable();

        assertThat(unreachable).isTrue();
        synchronized (gate) {
            gate.notifyAll();
        }
    }

    @Test
    @DisplayName("no JavaMailSenderImpl bean — probe is skipped")
    void should_skip_when_noJavaMailSenderImplBean() {
        assertThat(probeWith(null).isUnreachable()).isFalse();
    }
}
