package com.beautica.support;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.mockito.Mockito;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Properties;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared test helper: a mocked {@link JavaMailSender} whose {@code createMimeMessage()} hands out a FRESH
 * {@link MimeMessage} per call (a single shared instance would let the second e-mail overwrite the first),
 * for ITs that build a real {@code EmailNotificationService} over a stubbed SMTP socket. A plain helper, not a
 * bean or {@code @MockBean}: it adds nothing to the Spring context, so users stay on the cached context.
 */
public final class MockedMailSender {

    private MockedMailSender() {
    }

    public static JavaMailSender create() {
        return stubMimeMessages(mock(JavaMailSender.class));
    }

    /** Re-applies the fresh-message stubbing, e.g. after {@code Mockito.reset(sender)}. */
    public static JavaMailSender resetAndStub(JavaMailSender sender) {
        Mockito.reset(sender);
        return stubMimeMessages(sender);
    }

    private static JavaMailSender stubMimeMessages(JavaMailSender sender) {
        Session session = Session.getInstance(new Properties());
        when(sender.createMimeMessage()).thenAnswer(inv -> new MimeMessage(session));
        return sender;
    }
}
