package com.beautica.notification.service;

import com.beautica.config.FirebaseConfig;
import com.beautica.notification.repository.DeviceTokenRepository;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PushNotificationService {

    private final FirebaseConfig.FirebaseSender firebaseSender;
    private final DeviceTokenRepository deviceTokenRepository;

    @Async("pushExecutor")
    public void sendToUser(UUID userId, String title, String body, Map<String, String> data) {
        Objects.requireNonNull(userId, "userId must not be null");
        List<DeviceTokenRepository.DeviceTokenSummary> tokens =
                deviceTokenRepository.findActiveTokenSummaryByUserId(userId);
        if (tokens.isEmpty()) return;
        List<String> staleTokens = new ArrayList<>();
        for (DeviceTokenRepository.DeviceTokenSummary token : tokens) {
            try {
                Message message = Message.builder()
                        .setNotification(Notification.builder()
                                .setTitle(truncate(title, 100))
                                .setBody(truncate(body, 500))
                                .build())
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

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
