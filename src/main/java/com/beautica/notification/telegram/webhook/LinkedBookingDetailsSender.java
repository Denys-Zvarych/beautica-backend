package com.beautica.notification.telegram.webhook;

import com.beautica.booking.service.GuestBookingDetailsText;
import com.beautica.config.BookingSmsProperties;
import com.beautica.config.TelegramProperties;
import com.beautica.notification.telegram.TelegramMessenger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;

/**
 * The second message after a successful phone proof: the linked booking's details ({@link GuestBookingDetailsText}),
 * sent through the outbound {@link TelegramMessenger} (404), never in the webhook response.
 *
 * <p><b>Ordering.</b> The LINKED reply travels in the webhook HTTP response, which Telegram executes only once it
 * has received that response. The details are therefore scheduled {@link #SEND_DELAY} later on the shared
 * {@link TaskScheduler}, whose thread only hands the job to the dedicated, bounded {@code telegramDetailsExecutor}
 * (never the request-path {@code smsSendExecutor} budget) — no network call on a scheduler or webhook thread. The
 * caller invokes this after {@code proveContactForBooking} committed, so the job always sees the committed link.
 *
 * <p><b>Gates.</b> Nothing is scheduled unless {@code app.telegram.enabled} AND {@code app.booking.sms.enabled} are
 * both on — the same dark-launch rule as {@code GuestMessageRouter} (no guest message on any channel until release).
 * Both are re-checked when the job runs, so a flag flipped off during {@link #SEND_DELAY} still sends nothing.
 *
 * <p><b>Phone re-check.</b> The proven phone hash travels with the job; the details render only while the booking's
 * current guest phone still hashes to it ({@link GuestBookingDetailsText#render}).
 *
 * <p><b>Failure.</b> Best effort, at most once: a refused hand-off or a failed send is logged and dropped (the guest
 * already has the confirmation SMS). Logs carry the result and the exception class only — never the chat id, the
 * booking, the phone or the text.
 */
@Slf4j
@Component
public class LinkedBookingDetailsSender {

    /** Long enough for Telegram to execute the webhook-response LINKED reply first. */
    static final Duration SEND_DELAY = Duration.ofSeconds(2);
    private static final String GATED = "GATED";
    private static final String DROPPED = "DROPPED";

    private final GuestBookingDetailsText detailsText;
    private final TelegramMessenger telegramMessenger;
    private final TelegramProperties telegramProperties;
    private final BookingSmsProperties bookingSmsProperties;
    private final TaskScheduler taskScheduler;
    private final TaskExecutor sendExecutor;

    public LinkedBookingDetailsSender(GuestBookingDetailsText detailsText,
                                      TelegramMessenger telegramMessenger,
                                      TelegramProperties telegramProperties,
                                      BookingSmsProperties bookingSmsProperties,
                                      TaskScheduler taskScheduler,
                                      @Qualifier("telegramDetailsExecutor") TaskExecutor sendExecutor) {
        this.detailsText = detailsText;
        this.telegramMessenger = telegramMessenger;
        this.telegramProperties = telegramProperties;
        this.bookingSmsProperties = bookingSmsProperties;
        this.taskScheduler = taskScheduler;
        this.sendExecutor = sendExecutor;
    }

    /** Schedules the details message for {@code chatId}; returns at once and never throws. */
    public void scheduleAfterLink(long chatId, UUID bookingId, byte[] provenPhoneHash) {
        if (!gatesOpen()) {
            logResult(GATED);
            return;
        }
        byte[] phoneHash = provenPhoneHash.clone();
        try {
            taskScheduler.schedule(() -> handOff(chatId, bookingId, phoneHash),
                    taskScheduler.getClock().instant().plus(SEND_DELAY));
        } catch (RejectedExecutionException e) {
            logResult(DROPPED);
        }
    }

    private void handOff(long chatId, UUID bookingId, byte[] provenPhoneHash) {
        try {
            sendExecutor.execute(() -> send(chatId, bookingId, provenPhoneHash));
        } catch (RejectedExecutionException e) {
            logResult(DROPPED);
        }
    }

    void send(long chatId, UUID bookingId, byte[] provenPhoneHash) {
        if (!gatesOpen()) {
            logResult(GATED);
            return;
        }
        try {
            Optional<String> text = detailsText.render(bookingId, provenPhoneHash);
            if (text.isEmpty()) {
                logResult("NOTHING_TO_CONFIRM");
                return;
            }
            telegramMessenger.sendWaiting(chatId, text.get());
            logResult("SENT");
        } catch (RuntimeException e) {
            log.warn("event=telegram_booking_details result=FAILED cause={}", e.getClass().getSimpleName());
        }
    }

    private boolean gatesOpen() {
        return telegramProperties.enabled() && bookingSmsProperties.getSms().isEnabled();
    }

    private static void logResult(String result) {
        log.info("event=telegram_booking_details result={}", result);
    }
}
