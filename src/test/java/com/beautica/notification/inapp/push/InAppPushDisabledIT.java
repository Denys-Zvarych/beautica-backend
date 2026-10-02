package com.beautica.notification.inapp.push;

import com.beautica.notification.service.NotificationOutboxDrainWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
}
