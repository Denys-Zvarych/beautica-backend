package com.beautica.notification.telegram.webhook;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.SECRET;
import static com.beautica.notification.telegram.webhook.TelegramWebhookTestSupport.text;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 406 D1 — with {@code app.telegram.enabled} off (the default context) the controller bean does not exist and
 * the webhook path answers 404, even with a plausible secret header; nor do the verifier and its reject buckets.
 */
@DisplayName("TelegramWebhookController — flag off (phase 406 D1)")
class TelegramWebhookDisabledIT extends AbstractIntegrationTest {

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("flag off: the webhook path is 404 and neither the controller nor the secret verifier bean exists")
    void should_return404AndRegisterNoControllerOrVerifier_when_flagOff() {
        ResponseEntity<String> response = new TelegramWebhookTestSupport(restTemplate)
                .post(text(7_000_000_001L, "/help"), SECRET, "198.51.100.200");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(context.getBeanNamesForType(TelegramWebhookController.class)).isEmpty();
        assertThat(context.getBeanNamesForType(TelegramWebhookSecretVerifier.class)).isEmpty();
        assertThat(context.containsBean("telegramWebhookRejectBuckets")).isFalse();
        assertThat(context.getBeanNamesForType(TelegramWebhookSecretVerifier.class))
                .as("verifier is conditional too: a dark deploy runs no reject-log flush job").isEmpty();
    }
}
