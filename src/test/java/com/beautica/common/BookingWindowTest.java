package com.beautica.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link BookingWindow} — the single source of truth for the bookable-time floor
 * ({@code now + MIN_MINUTES_AHEAD}) and horizon ({@code MAX_DAYS_AHEAD}). Three call sites
 * (slot list, day projection, booking-create) derive their floor from here, so its two
 * {@code bookableCutoff} overloads MUST agree instant-for-instant and be zone-agnostic — a
 * disagreement was the exact defect fixed today (a slot offered by {@code /slots} then 400'd by create).
 */
@DisplayName("BookingWindow — bookable cutoff + window constants")
class BookingWindowTest {

    @Test
    @DisplayName("the lead-time and horizon constants are the documented 15 min / 180 days")
    void should_exposeDocumentedWindowConstants() {
        assertThat(BookingWindow.MIN_MINUTES_AHEAD)
                .as("minimum lead time before a booking may start")
                .isEqualTo(15);
        assertThat(BookingWindow.MAX_DAYS_AHEAD)
                .as("booking horizon in days")
                .isEqualTo(180);
    }

    @Test
    @DisplayName("bookableCutoff(Instant) is exactly now + 15 minutes")
    void should_returnNowPlusLeadTime_when_givenInstant() {
        Instant now = Instant.parse("2026-06-15T14:20:00Z");

        assertThat(BookingWindow.bookableCutoff(now))
                .as("cutoff is now + MIN_MINUTES_AHEAD, to the second")
                .isEqualTo(now.plus(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("bookableCutoff(Clock) and bookableCutoff(Instant) return the SAME instant for the same now")
    void should_agreeAcrossOverloads_forTheSameNow() {
        Instant now = Instant.parse("2026-06-15T14:20:00Z");
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);

        assertThat(BookingWindow.bookableCutoff(clock))
                .as("the Clock overload must equal the Instant overload for the clock's own now — "
                        + "the slot walk (Clock) and the day projection (Instant) cannot key off two "
                        + "different floors")
                .isEqualTo(BookingWindow.bookableCutoff(now));
    }

    @Test
    @DisplayName("the cutoff is zone-agnostic — a Kyiv clock and a UTC clock at the same instant yield the identical cutoff")
    void should_ignoreClockZone_when_computingCutoff() {
        Instant now = Instant.parse("2026-06-15T14:20:00Z");
        Clock utcClock = Clock.fixed(now, ZoneOffset.UTC);
        Clock kyivClock = Clock.fixed(now, ZoneId.of("Europe/Kyiv"));

        assertThat(BookingWindow.bookableCutoff(kyivClock))
                .as("comparison is on Instant, so the clock's zone is irrelevant — a UTC-zoned and a "
                        + "Kyiv-zoned clock at the same instant produce one cutoff")
                .isEqualTo(BookingWindow.bookableCutoff(utcClock));
    }
}
