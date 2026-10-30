package com.beautica.notification.telegram.link;

import com.beautica.booking.BookingTestFixtures;
import com.beautica.support.MutableTestClock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared fixtures for the phase 405 ITs and the phase 406 webhook ITs: a hand-moved clock bean (bean name {@code systemClock} overrides
 * {@code ClockConfig}, test profile allows overriding) and guest LINK bookings to hang tokens on. One shared
 * {@code @TestConfiguration} so every Telegram-link IT reuses the same cached Spring context.
 */
public final class TelegramLinkTestSupport {

    public static final Instant T0 = Instant.parse("2026-10-30T10:00:00Z");
    public static final String BOT_USERNAME = "beautica_test_bot";
    public static final String BOT_USERNAME_PROPERTY = "app.telegram.bot-username=" + BOT_USERNAME;
    public static final String GUEST_PHONE = "+380501234567";
    public static final String OTHER_PHONE = "+380671112233";

    private static final AtomicInteger HOUR_OFFSET = new AtomicInteger(10);

    @TestConfiguration
    public static class ClockConfig {
        @Bean
        public MutableTestClock systemClock() {
            return new MutableTestClock(T0);
        }
    }

    private final JdbcTemplate jdbcTemplate;
    private final BookingTestFixtures fixtures;
    private UUID masterId;
    private UUID masterServiceId;

    public TelegramLinkTestSupport(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.fixtures = new BookingTestFixtures(null, jdbcTemplate, null, passwordEncoder);
    }

    /** A COMPLETED guest LINK booking in the past (distinct hour per call, so no overlap guard can fire). */
    public UUID guestBooking(String guestPhone) {
        if (masterId == null) {
            masterId = fixtures.createIndependentMaster("tg-link-" + System.nanoTime() + "@beautica.test");
            masterServiceId = fixtures.createIndependentMasterService(masterId);
        }
        UUID bookingId = UUID.randomUUID();
        int hours = HOUR_OFFSET.getAndAdd(2);
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, ?, NULL, 'COMPLETED', NOW() - make_interval(hours => ?), "
                        + "NOW() - make_interval(hours => ?) + interval '1 hour', 500.00, 60, 0, 'LINK', "
                        + "'Гість', 'Тестовий', ?, NOW(), NOW())",
                bookingId, masterId, masterServiceId, hours, hours, guestPhone);
        return bookingId;
    }

    /** The raw start token out of {@code https://t.me/<bot>?start=<token>}. */
    public static String rawToken(IssuedLink link) {
        return link.url().substring(link.url().indexOf("?start=") + "?start=".length());
    }
}
