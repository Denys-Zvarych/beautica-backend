package com.beautica.notification.telegram.webhook;

import com.beautica.booking.service.GuestBookingDetailsText;
import com.beautica.config.BookingSmsProperties;
import com.beautica.config.TelegramProperties;
import com.beautica.notification.channel.GuestMessageRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.TaskScheduler;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
@DisplayName("LinkedBookingDetailsSender — booking details after a fresh Telegram link")
class LinkedBookingDetailsSenderTest {

    private static final long CHAT = 7_123_456_789L;
    private static final UUID BOOKING_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant NOW = Instant.parse("2026-10-31T10:00:00Z");
    private static final String TEXT = "Запис підтверджено ✅\nПослуга: Манікюр";
    private static final byte[] PHONE_HASH = {1, 2, 3, 4};
    private static final String DROPPED_LINE = "event=telegram_booking_details result=DROPPED";
    private static final String FAILED_LINE =
            "event=telegram_booking_details result=FAILED cause=DataAccessResourceFailureException";

    @Mock private GuestBookingDetailsText detailsText;
    @Mock private GuestMessageRouter guestMessageRouter;
    @Mock private TaskScheduler taskScheduler;

    private final BookingSmsProperties smsProperties = new BookingSmsProperties();

    @BeforeEach
    void enableSms() {
        smsProperties.getSms().setEnabled(true);
    }

    private TelegramProperties telegram;

    private LinkedBookingDetailsSender sender(boolean telegramEnabled, TaskExecutor executor) {
        telegram = mock(TelegramProperties.class);
        lenient().when(telegram.enabled()).thenReturn(telegramEnabled);
        return new LinkedBookingDetailsSender(detailsText, guestMessageRouter, telegram, smsProperties, taskScheduler,
                executor);
    }

    private Runnable scheduledTask() {
        ArgumentCaptor<Runnable> task = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(task.capture(), any(Instant.class));
        return task.getValue();
    }

    @Test
    @DisplayName("schedule the send SEND_DELAY after now on the scheduler's clock")
    void should_scheduleSendAfterDelay_when_gatesOn() {
        when(taskScheduler.getClock()).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        LinkedBookingDetailsSender sender = sender(true, new SyncTaskExecutor());

        sender.scheduleAfterLink(CHAT, BOOKING_ID, PHONE_HASH);

        ArgumentCaptor<Instant> at = ArgumentCaptor.forClass(Instant.class);
        verify(taskScheduler).schedule(any(Runnable.class), at.capture());
        assertThat(at.getValue()).isEqualTo(NOW.plus(LinkedBookingDetailsSender.SEND_DELAY));
        verifyNoInteractions(detailsText, guestMessageRouter);
    }

    @Test
    @DisplayName("send the rendered details to the chat when the scheduled task runs")
    void should_sendRenderedDetails_when_scheduledTaskRuns() {
        when(taskScheduler.getClock()).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        when(detailsText.render(BOOKING_ID, PHONE_HASH)).thenReturn(Optional.of(TEXT));
        LinkedBookingDetailsSender sender = sender(true, new SyncTaskExecutor());
        sender.scheduleAfterLink(CHAT, BOOKING_ID, PHONE_HASH);

        scheduledTask().run();

        verify(guestMessageRouter).deliverTelegramOnly(CHAT, TEXT);
    }

    @Test
    @DisplayName("schedule nothing when the Telegram flag is off")
    void should_scheduleNothing_when_telegramDisabled() {
        LinkedBookingDetailsSender sender = sender(false, new SyncTaskExecutor());

        sender.scheduleAfterLink(CHAT, BOOKING_ID, PHONE_HASH);

        verifyNoInteractions(taskScheduler, detailsText, guestMessageRouter);
    }

    @Test
    @DisplayName("schedule nothing when booking SMS (the dark-launch gate) is off")
    void should_scheduleNothing_when_bookingSmsDisabled() {
        smsProperties.getSms().setEnabled(false);
        LinkedBookingDetailsSender sender = sender(true, new SyncTaskExecutor());

        sender.scheduleAfterLink(CHAT, BOOKING_ID, PHONE_HASH);

        verifyNoInteractions(taskScheduler, detailsText, guestMessageRouter);
    }

    @Test
    @DisplayName("send nothing when there is nothing to confirm")
    void should_sendNothing_when_nothingToConfirm() {
        when(detailsText.render(BOOKING_ID, PHONE_HASH)).thenReturn(Optional.empty());
        LinkedBookingDetailsSender sender = sender(true, new SyncTaskExecutor());

        sender.send(CHAT, BOOKING_ID, PHONE_HASH);

        verify(guestMessageRouter, never()).deliverTelegramOnly(anyLong(), anyString());
    }

    @Test
    @DisplayName("a send the router dropped is logged NOT_SENT, never SENT, and never throws")
    void should_logNotSent_when_routerDropsSend(CapturedOutput output) {
        when(detailsText.render(BOOKING_ID, PHONE_HASH)).thenReturn(Optional.of(TEXT));
        when(guestMessageRouter.deliverTelegramOnly(CHAT, TEXT)).thenReturn(false);
        LinkedBookingDetailsSender sender = sender(true, new SyncTaskExecutor());

        assertThatCode(() -> sender.send(CHAT, BOOKING_ID, PHONE_HASH)).doesNotThrowAnyException();

        assertThat(output.getAll()).contains("event=telegram_booking_details result=NOT_SENT")
                .doesNotContain("result=SENT").doesNotContain(Long.toString(CHAT));
    }

    @Test
    @DisplayName("send nothing, log the exception class only and not rethrow when rendering fails")
    void should_logClassOnlyAndNotThrow_when_renderFails(CapturedOutput output) {
        when(detailsText.render(BOOKING_ID, PHONE_HASH))
                .thenThrow(new DataAccessResourceFailureException("pool exhausted for chat " + CHAT));
        LinkedBookingDetailsSender sender = sender(true, new SyncTaskExecutor());

        assertThatCode(() -> sender.send(CHAT, BOOKING_ID, PHONE_HASH)).doesNotThrowAnyException();

        verifyNoInteractions(guestMessageRouter);
        assertThat(output.getAll()).contains(FAILED_LINE)
                .doesNotContain(Long.toString(CHAT)).doesNotContain(BOOKING_ID.toString())
                .doesNotContain("pool exhausted");
    }

    @Test
    @DisplayName("never throw when the scheduler refuses the task")
    void should_notThrow_when_schedulerRejects(CapturedOutput output) {
        when(taskScheduler.getClock()).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        when(taskScheduler.schedule(any(Runnable.class), any(Instant.class)))
                .thenThrow(new TaskRejectedException("full"));
        LinkedBookingDetailsSender sender = sender(true, new SyncTaskExecutor());

        assertThatCode(() -> sender.scheduleAfterLink(CHAT, BOOKING_ID, PHONE_HASH)).doesNotThrowAnyException();

        assertDroppedLoggedWithoutPii(output);
    }

    @Test
    @DisplayName("drop the send without throwing when the send pool refuses the hand-off")
    void should_dropWithoutThrowing_when_sendPoolRejects(CapturedOutput output) {
        when(taskScheduler.getClock()).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        TaskExecutor rejecting = task -> {
            throw new TaskRejectedException("full");
        };
        LinkedBookingDetailsSender sender = sender(true, rejecting);
        sender.scheduleAfterLink(CHAT, BOOKING_ID, PHONE_HASH);

        assertThatCode(() -> scheduledTask().run()).doesNotThrowAnyException();

        verifyNoInteractions(detailsText, guestMessageRouter);
        assertDroppedLoggedWithoutPii(output);
    }

    private static void assertDroppedLoggedWithoutPii(CapturedOutput output) {
        assertThat(output.getAll()).contains(DROPPED_LINE)
                .doesNotContain(Long.toString(CHAT)).doesNotContain(BOOKING_ID.toString());
    }

    @Test
    @DisplayName("send nothing when the Telegram flag is switched off between scheduling and the send")
    void should_notSend_when_gateFlipsBeforeSend() {
        when(taskScheduler.getClock()).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        LinkedBookingDetailsSender sender = sender(true, new SyncTaskExecutor());
        sender.scheduleAfterLink(CHAT, BOOKING_ID, PHONE_HASH);
        when(telegram.enabled()).thenReturn(false);

        scheduledTask().run();

        verifyNoInteractions(detailsText, guestMessageRouter);
    }

    @Test
    @DisplayName("send nothing when booking SMS (the dark-launch gate) is switched off between scheduling and the send")
    void should_notSend_when_bookingSmsGateFlipsBeforeSend() {
        when(taskScheduler.getClock()).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        LinkedBookingDetailsSender sender = sender(true, new SyncTaskExecutor());
        sender.scheduleAfterLink(CHAT, BOOKING_ID, PHONE_HASH);
        smsProperties.getSms().setEnabled(false);

        scheduledTask().run();

        verifyNoInteractions(detailsText, guestMessageRouter);
    }

    @Test
    @DisplayName("hand the proven phone hash to the renderer, insulated from later mutation of the caller's array")
    void should_passProvenPhoneHashToRenderer_when_scheduledTaskRuns() {
        when(taskScheduler.getClock()).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        byte[] callerHash = PHONE_HASH.clone();
        LinkedBookingDetailsSender sender = sender(true, new SyncTaskExecutor());
        sender.scheduleAfterLink(CHAT, BOOKING_ID, callerHash);
        callerHash[0] = 9;

        scheduledTask().run();

        verify(detailsText).render(BOOKING_ID, PHONE_HASH);
    }
}
