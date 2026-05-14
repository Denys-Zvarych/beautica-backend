package com.beautica.notification.service;

import com.beautica.config.FirebaseConfig;
import com.beautica.notification.repository.DeviceTokenRepository;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PushNotificationServiceTest {

    @Mock
    private FirebaseConfig.FirebaseSender firebaseSender;

    @Mock
    private DeviceTokenRepository deviceTokenRepository;

    @InjectMocks
    private PushNotificationService pushNotificationService;

    @Test
    @DisplayName("should send to all devices when user has multiple tokens")
    void should_sendToAllDevices_when_userHasMultipleTokens() throws Exception {
        UUID userId = UUID.randomUUID();
        DeviceTokenRepository.DeviceTokenSummary tokenA = buildSummary(UUID.randomUUID(), "fcm-token-a");
        DeviceTokenRepository.DeviceTokenSummary tokenB = buildSummary(UUID.randomUUID(), "fcm-token-b");
        when(deviceTokenRepository.findActiveTokenSummaryByUserId(userId)).thenReturn(List.of(tokenA, tokenB));

        pushNotificationService.sendToUser(userId, "Title", "Body", Map.of());

        verify(firebaseSender, times(2)).send(any(Message.class));
    }

    @Test
    @DisplayName("should do nothing when user has no active tokens")
    void should_doNothing_when_userHasNoActiveTokens() throws Exception {
        UUID userId = UUID.randomUUID();
        when(deviceTokenRepository.findActiveTokenSummaryByUserId(userId)).thenReturn(List.of());

        pushNotificationService.sendToUser(userId, "Title", "Body", Map.of());

        verify(firebaseSender, never()).send(any(Message.class));
    }

    @Test
    @DisplayName("should delete token when Firebase returns UNREGISTERED")
    void should_deleteToken_when_firebaseReturnsUnregistered() throws Exception {
        UUID userId = UUID.randomUUID();
        DeviceTokenRepository.DeviceTokenSummary token = buildSummary(UUID.randomUUID(), "the-stale-token");
        when(deviceTokenRepository.findActiveTokenSummaryByUserId(userId)).thenReturn(List.of(token));

        FirebaseMessagingException mockEx = mock(FirebaseMessagingException.class);
        when(mockEx.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        doThrow(mockEx).when(firebaseSender).send(any(Message.class));

        pushNotificationService.sendToUser(userId, "Title", "Body", null);

        verify(deviceTokenRepository).deleteByUserIdAndTokenIn(
                eq(userId),
                argThat(c -> c.contains("the-stale-token")));
    }

    @Test
    @DisplayName("should delete token when Firebase returns INVALID_ARGUMENT")
    void should_deleteToken_when_firebaseReturnsInvalidArgument() throws Exception {
        UUID userId = UUID.randomUUID();
        DeviceTokenRepository.DeviceTokenSummary token = buildSummary(UUID.randomUUID(), "the-stale-token");
        when(deviceTokenRepository.findActiveTokenSummaryByUserId(userId)).thenReturn(List.of(token));

        FirebaseMessagingException mockEx = mock(FirebaseMessagingException.class);
        when(mockEx.getMessagingErrorCode()).thenReturn(MessagingErrorCode.INVALID_ARGUMENT);
        doThrow(mockEx).when(firebaseSender).send(any(Message.class));

        pushNotificationService.sendToUser(userId, "Title", "Body", null);

        verify(deviceTokenRepository).deleteByUserIdAndTokenIn(
                eq(userId),
                argThat(c -> c.contains("the-stale-token")));
    }

    @Test
    @DisplayName("should not throw and log warn when Firebase throws other error")
    void should_notThrowAndLogWarn_when_firebaseThrowsOtherError() throws Exception {
        UUID userId = UUID.randomUUID();
        DeviceTokenRepository.DeviceTokenSummary token = buildSummary(UUID.randomUUID(), "fcm-error-token");
        when(deviceTokenRepository.findActiveTokenSummaryByUserId(userId)).thenReturn(List.of(token));

        FirebaseMessagingException mockEx = mock(FirebaseMessagingException.class);
        when(mockEx.getMessagingErrorCode()).thenReturn(MessagingErrorCode.INTERNAL);
        doThrow(mockEx).when(firebaseSender).send(any(Message.class));

        assertThatCode(() -> pushNotificationService.sendToUser(userId, "Title", "Body", null))
                .doesNotThrowAnyException();
        verify(deviceTokenRepository, never()).deleteByUserIdAndTokenIn(any(), any());
    }

    @Test
    @DisplayName("should truncate title when title exceeds limit")
    void should_truncateTitle_when_titleExceedsLimit() throws Exception {
        // Arrange
        UUID userId = UUID.randomUUID();
        DeviceTokenRepository.DeviceTokenSummary token = buildSummary(UUID.randomUUID(), "fcm-token-a");
        when(deviceTokenRepository.findActiveTokenSummaryByUserId(userId)).thenReturn(List.of(token));
        String longTitle = "A".repeat(200);
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);

        // Act
        pushNotificationService.sendToUser(userId, longTitle, "Body", Map.of());

        // Assert
        verify(firebaseSender).send(captor.capture());
        String capturedTitle = getTitleFromMessage(captor.getValue());
        assertThat(capturedTitle).isNotNull();
        assertThat(capturedTitle.length()).isLessThanOrEqualTo(100);
        verify(deviceTokenRepository, never()).deleteByUserIdAndTokenIn(any(), any());
    }

    @Test
    @DisplayName("NullPointerException thrown when userId is null")
    void should_throwNullPointerException_when_userIdIsNull() {
        // Act + Assert
        assertThatThrownBy(() -> pushNotificationService.sendToUser(null, "title", "body", Map.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("userId must not be null");
    }

    private static String getTitleFromMessage(Message message) throws Exception {
        Field notifField = Message.class.getDeclaredField("notification");
        notifField.setAccessible(true);
        Object notification = notifField.get(message);

        Field titleField = notification.getClass().getDeclaredField("title");
        titleField.setAccessible(true);
        return (String) titleField.get(notification);
    }

    private static String getBodyFromMessage(Message message) throws Exception {
        Field notifField = Message.class.getDeclaredField("notification");
        notifField.setAccessible(true);
        Object notification = notifField.get(message);

        Field bodyField = notification.getClass().getDeclaredField("body");
        bodyField.setAccessible(true);
        return (String) bodyField.get(notification);
    }

    @Test
    @DisplayName("should truncate body when body exceeds limit")
    void should_truncateBody_when_bodyExceedsLimit() throws Exception {
        UUID userId = UUID.randomUUID();
        DeviceTokenRepository.DeviceTokenSummary token = buildSummary(UUID.randomUUID(), "fcm-token-body");
        when(deviceTokenRepository.findActiveTokenSummaryByUserId(userId)).thenReturn(List.of(token));
        String longBody = "B".repeat(600);
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);

        pushNotificationService.sendToUser(userId, "Title", longBody, Map.of());

        verify(firebaseSender).send(captor.capture());
        String capturedBody = getBodyFromMessage(captor.getValue());
        assertThat(capturedBody).isNotNull();
        assertThat(capturedBody.length()).isLessThanOrEqualTo(500);
    }

    @Test
    @DisplayName("should not throw and should not delete token when unexpected RuntimeException occurs")
    void should_notThrowAndNotDeleteToken_when_unexpectedRuntimeException() throws Exception {
        UUID userId = UUID.randomUUID();
        DeviceTokenRepository.DeviceTokenSummary token = buildSummary(UUID.randomUUID(), "fcm-runtime-err-token");
        when(deviceTokenRepository.findActiveTokenSummaryByUserId(userId)).thenReturn(List.of(token));
        doThrow(new RuntimeException("unexpected")).when(firebaseSender).send(any(Message.class));

        assertThatCode(() -> pushNotificationService.sendToUser(userId, "T", "B", Map.of()))
                .doesNotThrowAnyException();
        verify(deviceTokenRepository, never()).deleteByUserIdAndTokenIn(any(), any());
    }

    @Test
    @DisplayName("should batch-delete both stale tokens when two tokens return UNREGISTERED")
    void should_batchDeleteBothStaleTokens_when_twoTokensReturnUnregistered() throws Exception {
        UUID userId = UUID.randomUUID();
        DeviceTokenRepository.DeviceTokenSummary token1 = buildSummary(UUID.randomUUID(), "stale-token-1");
        DeviceTokenRepository.DeviceTokenSummary token2 = buildSummary(UUID.randomUUID(), "stale-token-2");
        when(deviceTokenRepository.findActiveTokenSummaryByUserId(userId)).thenReturn(List.of(token1, token2));

        FirebaseMessagingException mockEx = mock(FirebaseMessagingException.class);
        when(mockEx.getMessagingErrorCode()).thenReturn(MessagingErrorCode.UNREGISTERED);
        doThrow(mockEx).when(firebaseSender).send(any(Message.class));

        pushNotificationService.sendToUser(userId, "Title", "Body", Map.of());

        ArgumentCaptor<Collection<String>> tokensCaptor = ArgumentCaptor.forClass(Collection.class);
        verify(deviceTokenRepository, times(1)).deleteByUserIdAndTokenIn(eq(userId), tokensCaptor.capture());
        assertThat(tokensCaptor.getValue()).containsExactlyInAnyOrder("stale-token-1", "stale-token-2");
    }

    private DeviceTokenRepository.DeviceTokenSummary buildSummary(UUID id, String rawToken) {
        DeviceTokenRepository.DeviceTokenSummary summary = mock(DeviceTokenRepository.DeviceTokenSummary.class);
        // getId() is only accessed in log.warn on the exception path — lenient avoids UnnecessaryStubbingException
        // in happy-path tests where no exception is thrown
        lenient().when(summary.getId()).thenReturn(id);
        when(summary.getToken()).thenReturn(rawToken);
        return summary;
    }
}
