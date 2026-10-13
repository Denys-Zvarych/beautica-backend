package com.beautica.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the SMTP timeouts. A missing/0 value means an infinite hang on a silent relay, so the prod
 * {@code application.yml} keys are asserted present, and the JavaMail read timeout is proven to
 * fire against a socket that accepts but never sends the 220 greeting.
 */
@DisplayName("Mail — SMTP timeouts pinned and firing")
class MailTimeoutConfigTest {

    private static final String PREFIX = "spring.mail.properties.mail.smtp.";

    @Test
    @DisplayName("should_defineAllThreeSmtpTimeouts_when_loadingProdApplicationYml")
    void should_defineAllThreeSmtpTimeouts_when_loadingProdApplicationYml() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties props = yaml.getObject();

        for (String key : new String[] {"connectiontimeout", "timeout", "writetimeout"}) {
            String raw = props.getProperty(PREFIX + key);
            assertThat(raw).as(PREFIX + key).isNotNull();
            assertThat(Integer.parseInt(raw)).as(PREFIX + key).isBetween(1, 10_000);
        }
    }

    @Test
    @Timeout(5)
    @DisplayName("should_throwMailException_when_smtpServerNeverSendsGreeting")
    void should_throwMailException_when_smtpServerNeverSendsGreeting() throws Exception {
        try (ServerSocket silent = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost("127.0.0.1");
            sender.setPort(silent.getLocalPort());
            Properties javaMail = sender.getJavaMailProperties();
            javaMail.put("mail.smtp.connectiontimeout", "300");
            javaMail.put("mail.smtp.timeout", "300");
            javaMail.put("mail.smtp.writetimeout", "300");
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom("a@example.com");
            message.setTo("b@example.com");
            message.setSubject("s");
            message.setText("t");

            long start = System.nanoTime();
            assertThatThrownBy(() -> sender.send(message)).isInstanceOf(MailException.class);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(elapsed).isLessThan(Duration.ofMillis(1500));
        }
    }
}
