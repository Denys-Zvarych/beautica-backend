package com.beautica.config;

import jakarta.mail.MessagingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Prod-only SMTP auth probe (Phase 367 audit). A real-but-wrong Brevo login/key passes every boot rule
 * (only blank/{@code PENDING} is detected), so outbound mail would fail silently. After the context is up,
 * {@link JavaMailSenderImpl#testConnection()} opens the SMTP session and authenticates once; nothing is sent.
 *
 * <p><b>Async with a short wait, WARN only.</b> The probe runs on its own daemon thread so a slow relay can
 * never hold a Hikari connection, block the main thread or the web server (already accepting traffic by the
 * time runners execute). The banner waits at most {@link #WAIT} so the result can be shown on the single banner
 * line ({@code mail=brevo-unreachable}); a probe still running after that is reported as unreachable here and
 * logs its own outcome when it finishes. Never fatal: a Brevo outage must not take the API down.
 *
 * <p>Logs the exception class only — SMTP error text can echo the login.
 */
@Slf4j
@Component
public class BrevoConnectionProbe {

    static final Duration WAIT = Duration.ofSeconds(3);

    private final ObjectProvider<MailSender> mailSender;

    public BrevoConnectionProbe(ObjectProvider<MailSender> mailSender) {
        this.mailSender = mailSender;
    }

    /**
     * @return {@code true} when the relay could not be reached/authenticated (or did not answer within
     *         {@link #WAIT}); {@code false} when it authenticated or there is nothing to probe.
     */
    public boolean isUnreachable() {
        if (!(mailSender.getIfAvailable() instanceof JavaMailSenderImpl impl)) {
            return false;
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            try {
                impl.testConnection();
                result.complete(false);
            } catch (MessagingException | RuntimeException e) {
                log.warn("rule prod-brevo-auth-failed: SMTP connection/authentication to the mail relay failed: {}",
                        e.getClass().getSimpleName());
                result.complete(true);
            }
        }, "brevo-connection-probe");
        thread.setDaemon(true);
        thread.start();
        try {
            return result.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("rule prod-brevo-auth-failed: SMTP connection/authentication to the mail relay did not answer "
                    + "within {} s: {}", WAIT.toSeconds(), e.getClass().getSimpleName());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException e) {
            // Never thrown at runtime: the future is only ever completed normally (the worker thread
            // catches everything and completes with true/false). Kept solely because the checked
            // CompletableFuture#get declares it; removing the catch does not compile.
            return true;
        }
    }
}
