package com.beautica.booking.service;

import com.beautica.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Phase 27.1 — pass/throw branches for the remaining anti-tamper temporal guards (complete,
 * provider reschedule), pinned against a fixed {@link Clock} so the boundary (exactly
 * {@code now}) is deterministic. Decline and not-complete are deliberately unguarded — see
 * {@link BookingTemporalGuard}'s class javadoc.
 */
@DisplayName("BookingTemporalGuard — unit")
class BookingTemporalGuardTest {

    private static final Instant NOW = Instant.parse("2026-07-25T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static OffsetDateTime future() {
        return OffsetDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC);
    }

    private static OffsetDateTime past() {
        return OffsetDateTime.ofInstant(NOW.minusSeconds(3600), ZoneOffset.UTC);
    }

    private static OffsetDateTime exactlyNow() {
        return OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC);
    }

    // ── assertElapsedForComplete ─────────────────────────────────────────────────

    @Test
    @DisplayName("assertElapsedForComplete — passes when startsAt is in the past")
    void should_pass_when_completeStartsAtIsPast() {
        assertThatCode(() -> BookingTemporalGuard.assertElapsedForComplete(past(), CLOCK))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertElapsedForComplete — passes when startsAt equals now exactly (begun this instant)")
    void should_pass_when_completeStartsAtEqualsNow() {
        assertThatCode(() -> BookingTemporalGuard.assertElapsedForComplete(exactlyNow(), CLOCK))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertElapsedForComplete — throws 409 when startsAt is in the future")
    void should_throw409_when_completeStartsAtIsFuture() {
        assertThatThrownBy(() -> BookingTemporalGuard.assertElapsedForComplete(future(), CLOCK))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ── assertCurrentNotElapsedForReschedule (provider reschedule) ──────────────

    @Test
    @DisplayName("assertCurrentNotElapsedForReschedule — passes when the current booking's startsAt is in the future")
    void should_pass_when_rescheduleCurrentStartsAtIsFuture() {
        assertThatCode(() -> BookingTemporalGuard.assertCurrentNotElapsedForReschedule(future(), CLOCK))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("assertCurrentNotElapsedForReschedule — throws 409 when the current booking's startsAt is in the past")
    void should_throw409_when_rescheduleCurrentStartsAtIsPast() {
        assertThatThrownBy(() -> BookingTemporalGuard.assertCurrentNotElapsedForReschedule(past(), CLOCK))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }
}
