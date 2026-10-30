package com.beautica.notification.telegram;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Phase 432 D3 — the staging outbound gate: only allow-listed chats reach the live client. */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("AllowListTelegramMessenger — staging tester allow-list, outbound")
class AllowListTelegramMessengerTest {

    private static final long ALLOWED_CHAT = 111_222_333L;
    private static final long OTHER_CHAT = 987_654_321L;

    private final TelegramMessenger delegate = mock(TelegramMessenger.class);
    private final AllowListTelegramMessenger messenger =
            new AllowListTelegramMessenger(delegate, new StagingTelegramChatAllowList(List.of(ALLOWED_CHAT)));

    @Test
    @DisplayName("allowed chat → delegates exactly once")
    void should_delegateOnce_when_chatAllowed() {
        messenger.send(ALLOWED_CHAT, "Нагадування");

        verify(delegate, times(1)).send(ALLOWED_CHAT, "Нагадування");
    }

    @Test
    @DisplayName("chat not on the list → TelegramDeliveryException, delegate never called, no chat id or text in logs")
    void should_throwDeliveryAndSkipDelegate_when_chatNotAllowed(CapturedOutput output) {
        assertThatThrownBy(() -> messenger.send(OTHER_CHAT, "Нагадування"))
                .isExactlyInstanceOf(TelegramDeliveryException.class)
                .message().doesNotContain(Long.toString(OTHER_CHAT));

        verify(delegate, never()).send(anyLong(), anyString());
        assertThat(output).contains("status=not_allow_listed")
                .doesNotContain(Long.toString(OTHER_CHAT)).doesNotContain("Нагадування");
    }

    @Test
    @DisplayName("blank text → IllegalArgumentException before the allow-list (same contract as every messenger)")
    void should_rejectBlankText_when_anyChat() {
        assertThatThrownBy(() -> messenger.send(OTHER_CHAT, " ")).isInstanceOf(IllegalArgumentException.class);

        verify(delegate, never()).send(anyLong(), anyString());
    }

    @Test
    @DisplayName("delegate failure propagates unchanged for an allowed chat")
    void should_propagateDelegateFailure_when_chatAllowed() {
        doThrow(new TelegramRecipientGoneException("gone"))
                .when(delegate).send(ALLOWED_CHAT, "hi");

        assertThatThrownBy(() -> messenger.send(ALLOWED_CHAT, "hi")).isInstanceOf(TelegramRecipientGoneException.class);
        verify(delegate, times(1)).send(ALLOWED_CHAT, "hi");
    }
}
