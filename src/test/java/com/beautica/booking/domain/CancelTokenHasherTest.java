package com.beautica.booking.domain;

import com.beautica.booking.entity.Appointment;
import com.beautica.booking.entity.Booking;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CancelTokenHasher — the one stored form of a guest cancel token (V206)")
class CancelTokenHasherTest {

    private static final UUID TOKEN = UUID.fromString("3f2b8c1e-9a7d-4e21-8b6f-0c5d4e3a2b19");
    /** sha256("3f2b8c1e-9a7d-4e21-8b6f-0c5d4e3a2b19") — the same preimage V206's SQL backfill hashes. */
    private static final String TOKEN_SHA256 = "c923470eab6ae8c9d7205bb012aec6c4ecfd9b7bdbb0a9f9311b26938c1b0a6f";

    @Test
    @DisplayName("hash is the lower-hex SHA-256 of the UUID canonical text (same preimage as V206 SQL)")
    void should_returnLowerHexSha256OfCanonicalText_when_hashingToken() {
        String hash = CancelTokenHasher.hash(TOKEN);

        assertThat(hash).isEqualTo(TOKEN_SHA256).matches("^[0-9a-f]{64}$");
    }

    @Test
    @DisplayName("two tokens differing in one hex digit hash differently")
    void should_returnDifferentHashes_when_tokensDiffer() {
        UUID other = UUID.fromString("3f2b8c1e-9a7d-4e21-8b6f-0c5d4e3a2b1a");

        String first = CancelTokenHasher.hash(TOKEN);
        String second = CancelTokenHasher.hash(other);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("a null token is rejected, never hashed to a constant")
    void should_throw_when_tokenIsNull() {
        assertThatThrownBy(() -> CancelTokenHasher.hash(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("token");
    }

    @Test
    @DisplayName("Booking builder cancelToken(UUID) sets the raw token AND its hash together")
    void should_setHashTogetherWithRawToken_when_bookingBuiltWithCancelToken() {
        Booking booking = Booking.builder().cancelToken(TOKEN).build();

        assertThat(booking.getCancelToken()).isEqualTo(TOKEN);
        assertThat(booking.getCancelTokenHash()).isEqualTo(TOKEN_SHA256);
    }

    @Test
    @DisplayName("Booking builder cancelToken(null) leaves the stored hash NULL")
    void should_leaveHashNull_when_bookingBuiltWithNullCancelToken() {
        Booking booking = Booking.builder().cancelToken(null).build();

        assertThat(booking.getCancelTokenHash()).isNull();
    }

    @Test
    @DisplayName("Appointment builder cancelToken(UUID) sets the raw token AND its hash together")
    void should_setHashTogetherWithRawToken_when_appointmentBuiltWithCancelToken() {
        Appointment appointment = Appointment.builder().cancelToken(TOKEN).build();

        assertThat(appointment.getCancelToken()).isEqualTo(TOKEN);
        assertThat(appointment.getCancelTokenHash()).isEqualTo(TOKEN_SHA256);
    }
}
