package com.beautica.notification.service;

import com.beautica.config.FirebaseConfig;
import com.beautica.notification.repository.DeviceTokenRepository;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.AndroidNotification;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class PushNotificationService {

    private static final Duration PUSH_TTL = Duration.ofHours(24);

    private final FirebaseConfig.FirebaseSender firebaseSender;
    private final DeviceTokenRepository deviceTokenRepository;

    /**
     * Fans a push out to {@code tokens}, the recipient's active tokens as the outbox drainer
     * batch-loaded them at prepare time (no per-push lookup on the drain thread).
     *
     * <p><b>Ownership re-check (phase 339 audit cycle 2, S4).</b> Those tokens can be stale by the time
     * this {@code @Async} task runs: {@code POST /devices/token} rebinds a token to whoever registers it
     * (shared device, account switch), so the captured list could now point at ANOTHER user's session.
     * Right before sending, ONE query ({@link DeviceTokenRepository#findActiveTokenSummaryByUserId}) on
     * this executor thread — never the drain thread — reads the recipient's CURRENT active tokens, and
     * only captured tokens still in that set are sent to. That query also requires the USER to still be
     * active, so an account deactivated between prepare and send gets no push. If the check itself
     * fails, nothing is sent (fail closed).
     *
     * <p><b>Query bound:</b> exactly one indexed statement per call, i.e. per outbox entry, so a recipient
     * with k rows in one batch costs k identical ownership queries (batch size is capped, and each is an
     * index lookup on {@code idx_device_tokens_user_active}). Deliberately NOT deduplicated: each call is
     * an independent {@code @Async} task for its own outbox entry (individually SENT or re-queued on a
     * rejected hand-off), so sharing one result across them would need cross-task state/ordering for a
     * saving of a few cheap lookups, and a shared snapshot would be staler than a per-send check.
     *
     * <p><b>Delivery guarantee: at-most-once after the executor hand-off (phase 339, D2).</b> Once
     * this {@code @Async} call has been accepted by {@code pushExecutor}, the outbox entry that
     * triggered it is marked {@code SENT}; an FCM failure (or a crash/shutdown with the task still
     * queued) after that point is logged and never retried. The in-app feed row is the source of
     * truth — the push is a best-effort nudge toward it. Only a rejected hand-off (executor
     * saturated) is retried, because the entry is then re-queued before any send was attempted.
     */
    @Async("pushExecutor")
    public void sendToDevices(UUID userId, List<? extends DeviceTokenRepository.DeviceTokenSummary> tokens,
                              String title, String body, Map<String, String> data) {
        Objects.requireNonNull(userId, "userId must not be null");
        deliver(userId, stillOwnedBy(userId, tokens), title, body, data);
    }

    /** The subset of {@code tokens} that is still an ACTIVE token of {@code userId}; empty on failure. */
    private List<? extends DeviceTokenRepository.DeviceTokenSummary> stillOwnedBy(
            UUID userId, List<? extends DeviceTokenRepository.DeviceTokenSummary> tokens) {
        if (tokens.isEmpty()) {
            return tokens;
        }
        try {
            Set<String> current = deviceTokenRepository.findActiveTokenSummaryByUserId(userId).stream()
                    .map(DeviceTokenRepository.DeviceTokenSummary::getToken)
                    .collect(Collectors.toSet());
            List<? extends DeviceTokenRepository.DeviceTokenSummary> owned =
                    tokens.stream().filter(t -> current.contains(t.getToken())).toList();
            if (owned.size() < tokens.size()) {
                log.debug("push skipped {} token(s) no longer owned/active", tokens.size() - owned.size());
            }
            return owned;
        } catch (RuntimeException e) {
            log.warn("push token ownership check failed, not sending: {}", e.getClass().getSimpleName());
            return List.of();
        }
    }

    private void deliver(UUID userId, List<? extends DeviceTokenRepository.DeviceTokenSummary> tokens,
                         String title, String body, Map<String, String> data) {
        // Never log the title/body (they carry names) or a token value — id + count only.
        log.debug("push fan-out notificationId={} tokens={}",
                data != null ? data.get("notificationId") : null, tokens.size());
        if (tokens.isEmpty()) return;
        List<String> staleTokens = new ArrayList<>();
        AndroidConfig androidConfig = androidConfig(data);
        for (DeviceTokenRepository.DeviceTokenSummary token : tokens) {
            try {
                Message message = Message.builder()
                        .setNotification(Notification.builder()
                                .setTitle(truncate(title, 100))
                                .setBody(truncate(body, 500))
                                .build())
                        .setAndroidConfig(androidConfig)
                        .putAllData(data != null ? data : Map.of())
                        .setToken(token.getToken())
                        .build();
                firebaseSender.send(message);
            } catch (FirebaseMessagingException e) {
                MessagingErrorCode errorCode = e.getMessagingErrorCode();
                if (errorCode == MessagingErrorCode.UNREGISTERED
                        || errorCode == MessagingErrorCode.INVALID_ARGUMENT) {
                    staleTokens.add(token.getToken());
                } else {
                    log.warn("Firebase send failed for device [{}]: {}", token.getId(), e.getClass().getSimpleName());
                }
            } catch (Exception e) {
                log.warn("Unexpected push dispatch error for device [{}]: {}", token.getId(), e.getClass().getSimpleName());
            }
        }
        if (!staleTokens.isEmpty()) {
            deviceTokenRepository.deleteByUserIdAndTokenIn(userId, staleTokens);
        }
    }

    /**
     * D7 (phase 339): high priority so a killed/dozing device wakes for it, a 24h time-to-live so a
     * stale reminder is dropped rather than delivered days late, and the notification id as the
     * Android notification tag so a re-delivery replaces instead of stacking. No custom channel —
     * FCM's fallback channel is used; a named channel is deferred.
     */
    private static AndroidConfig androidConfig(Map<String, String> data) {
        AndroidConfig.Builder builder = AndroidConfig.builder()
                .setPriority(AndroidConfig.Priority.HIGH)
                .setTtl(PUSH_TTL.toMillis());
        String tag = data != null ? data.get("notificationId") : null;
        if (tag != null) {
            builder.setNotification(AndroidNotification.builder().setTag(tag).build());
        }
        return builder.build();
    }

    /** Truncates by code point so a surrogate pair (emoji) is never split into a lone surrogate. */
    private static String truncate(String s, int max) {
        if (s == null) return null;
        if (s.codePointCount(0, s.length()) <= max) return s;
        return s.substring(0, s.offsetByCodePoints(0, max));
    }
}
