package com.beautica.notification.inapp.push;

import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.service.dto.CreateCategoryRequestRequest;
import com.beautica.service.service.CategoryRequestService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Phase 339 D4 — prod stays dark: with {@code FIREBASE_ENABLED} unset/false (the default, which this
 * context leaves untouched) a real booking still writes its feed rows but ZERO {@code INAPP_PUSH}
 * outbox rows, so there is no backlog to flush on ship day and the sender is never called.
 */
@DisplayName("Phase 339 D4 — FIREBASE_ENABLED=false writes no push outbox rows")
class InAppPushDisabledIT extends AbstractInAppPushFlowIT {

    @Value("${FIREBASE_ENABLED:false}")
    private boolean firebaseEnabled;
    @Autowired
    private NotificationOutboxDrainWorker drainWorker;
    @Autowired
    private CategoryRequestService categoryRequestService;

    @Test
    @DisplayName("client books over HTTP with device tokens registered: 3 feed rows, 0 INAPP_PUSH rows, "
            + "drain never reaches FCM")
    void should_writeFeedRowsButNoPushOutbox_when_firebaseDisabled() throws Exception {
        assertThat(firebaseEnabled).as("premise: this context must run with the flag off").isFalse();
        Rig rig = seedRigWithProviderDeviceTokens();

        clientBooks(rig, startsAt());
        drainWorker.drain();

        assertThat(feedRowIds()).as("the feed is unaffected by the flag").hasSize(3);
        assertThat(countOutbox("INAPP_PUSH")).as("dark prod: no push outbox churn").isZero();
        verify(firebaseSender, never()).send(any());
    }

    @Test
    @DisplayName("phase 372: admin rejects a category request with FIREBASE_ENABLED=false — the requester's feed item "
            + "is written but ZERO INAPP_PUSH rows, and the drain never reaches FCM (push copy stays dark)")
    void should_writeDecisionItemButNoPushOutbox_when_categoryRequestRejectedWithFirebaseDisabled() throws Exception {
        assertThat(firebaseEnabled).as("premise: this context must run with the flag off").isFalse();
        UUID requester = fixtures.createUser("push-dark-372-" + System.nanoTime() + "@beautica.test",
                "INDEPENDENT_MASTER", null);
        jdbcTemplate.update("INSERT INTO device_tokens (user_id, token, platform) VALUES (?, ?, 'ANDROID')",
                requester, "fcm-dark-372-" + requester);
        categoryRequestService.submitRequest(
                new CreateCategoryRequestRequest("PUSHDARK372_CAT", "Темна категорія", null), requester);
        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendCategoryRequestNotification(
                anyString(), anyString(), anyString(), anyString(), url.capture());
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", url.getValue().substring(url.getValue().lastIndexOf('/') + 1));
        form.add("reason", "dark reason");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

        try {
            ResponseEntity<String> decision = restTemplate.postForEntity(
                    "/api/v1/service-categories/requests/reject", new HttpEntity<>(form, headers), String.class);
            drainWorker.drain();

            assertThat(decision.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(feedRowIds()).as("the decision item is still written").hasSize(1);
            assertThat(countOutbox("INAPP_PUSH")).as("dark prod: no push outbox row for the decision").isZero();
            verify(firebaseSender, never()).send(any());
        } finally {
            jdbcTemplate.update("DELETE FROM in_app_notification");
            jdbcTemplate.update("DELETE FROM platform_categories WHERE name = 'PUSHDARK372_CAT'");
        }
    }
}
