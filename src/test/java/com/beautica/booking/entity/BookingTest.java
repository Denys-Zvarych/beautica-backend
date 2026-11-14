package com.beautica.booking.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 493 (GST-77) — {@link Booking#reschedule} re-arms both reminder flags when the start moves,
 * and leaves them alone when it does not.
 */
@DisplayName("Booking entity — reminder re-arm on reschedule")
class BookingTest {

    private static final OffsetDateTime STARTS_AT =
            OffsetDateTime.of(2032, 3, 10, 8, 0, 0, 0, ZoneOffset.UTC);
    private static final int DURATION_MINUTES = 60;

    private static Booking remindedGuestBooking() {
        Booking booking = Booking.guestBooking(
                null, null, null,
                STARTS_AT, STARTS_AT.plusMinutes(DURATION_MINUTES),
                new BigDecimal("300.00"), null,
                DURATION_MINUTES, 0,
                "Оксана", "Мельник", "+380509998877", null);
        booking.setReminderSent(true);
        // reminder2hSent has no setter by design (only the native claim writes it).
        ReflectionTestUtils.setField(booking, "reminder2hSent", true);
        return booking;
    }

    @Test
    @DisplayName("a move to a new start resets both the 24 h and the 2 h reminder flags")
    void should_resetBothReminderFlags_when_startsAtChanges() {
        Booking booking = remindedGuestBooking();
        OffsetDateTime newStartsAt = STARTS_AT.plusDays(2);

        booking.reschedule(newStartsAt, newStartsAt.plusMinutes(DURATION_MINUTES));

        assertThat(booking.getStartsAt()).isEqualTo(newStartsAt);
        assertThat(booking.isReminderSent()).as("24 h reminder re-armed").isFalse();
        assertThat(booking.isReminder2hSent()).as("2 h reminder re-armed").isFalse();
    }

    @Test
    @DisplayName("the same start instant (even in another offset) keeps both reminder flags")
    void should_keepReminderFlags_when_startsAtUnchanged() {
        Booking booking = remindedGuestBooking();
        OffsetDateTime sameInstantOtherOffset = STARTS_AT.withOffsetSameInstant(ZoneOffset.ofHours(2));

        booking.reschedule(sameInstantOtherOffset, sameInstantOtherOffset.plusMinutes(DURATION_MINUTES));

        assertThat(booking.isReminderSent()).as("24 h flag unchanged").isTrue();
        assertThat(booking.isReminder2hSent()).as("2 h flag unchanged").isTrue();
        assertThat(booking.isReminder2hRearmed()).as("no re-arm marker on an unchanged start").isFalse();
    }

    @Test
    @DisplayName("a move to a new start sets the 2 h re-arm marker; an unchanged start does not")
    void should_setRearmMarker_when_startsAtChanges() {
        Booking moved = remindedGuestBooking();
        Booking unchanged = remindedGuestBooking();
        OffsetDateTime newStartsAt = STARTS_AT.plusDays(2);

        moved.reschedule(newStartsAt, newStartsAt.plusMinutes(DURATION_MINUTES));
        unchanged.reschedule(STARTS_AT, STARTS_AT.plusMinutes(DURATION_MINUTES));

        assertThat(moved.isReminder2hRearmed()).as("moved row re-armed").isTrue();
        assertThat(unchanged.isReminder2hRearmed()).as("unchanged row not re-armed").isFalse();
    }
}
