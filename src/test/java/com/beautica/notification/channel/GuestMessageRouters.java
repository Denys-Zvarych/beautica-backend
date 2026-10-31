package com.beautica.notification.channel;

import com.beautica.config.BookingSmsProperties;
import com.beautica.config.TelegramMode;
import com.beautica.config.TelegramProperties;
import com.beautica.notification.sms.SmsService;
import com.beautica.notification.telegram.TelegramMessenger;
import com.beautica.notification.telegram.link.TelegramLinkService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.time.Duration;
import java.util.List;

import static org.mockito.Mockito.mock;

/**
 * Test support — a {@link GuestMessageRouter} with {@code app.telegram.enabled=false}, i.e. exactly the pre-408
 * {@code smsService.send} call. Lets every pre-408 unit test of a routed caller keep asserting on its
 * {@link SmsService} mock unchanged.
 */
public final class GuestMessageRouters {

    private GuestMessageRouters() {
    }

    public static GuestMessageRouter smsOnly(SmsService smsService) {
        return new GuestMessageRouter(smsService, mock(TelegramMessenger.class), mock(TelegramLinkService.class),
                telegramProperties(false), bookingSms(true), new SimpleMeterRegistry());
    }

    /** {@code app.booking.sms.enabled} as given. */
    public static BookingSmsProperties bookingSms(boolean enabled) {
        BookingSmsProperties properties = new BookingSmsProperties();
        properties.getSms().setEnabled(enabled);
        return properties;
    }

    public static TelegramProperties telegramProperties(boolean enabled) {
        return new TelegramProperties(enabled, TelegramMode.CAPTURE, "", "", "", "https://api.telegram.org",
                Duration.ofMinutes(30), List.of());
    }
}
