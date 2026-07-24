package com.beautica.booking.entity;

import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Booking entity — field mutability contract")
class BookingEntityImmutabilityTest {

    @Test
    @DisplayName("priceAtBooking has no public setter")
    void should_haveNoPublicSetter_when_fieldIsPriceAtBooking() {
        assertThatThrownBy(() -> Booking.class.getMethod("setPriceAtBooking", BigDecimal.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    /**
     * The frozen RANGE ceiling (V119, Phase 26.9) is the companion snapshot to
     * {@code priceAtBooking} and must be as unwritable after creation as the floor is. A public
     * setter is the single cheapest way to reintroduce the defect this phase exists to eliminate:
     * any post-creation write path — a "refresh the band" helper, a reschedule that re-prices, a
     * mapper that round-trips — would let a provider's later service edit rewrite a band the client
     * already agreed to, on an even {@code COMPLETED} booking.
     */
    @Test
    @DisplayName("priceMaxAtBooking has no public setter — the frozen ceiling is as immutable as the floor")
    void should_haveNoPublicSetter_when_fieldIsPriceMaxAtBooking() {
        assertThatThrownBy(() -> Booking.class.getMethod("setPriceMaxAtBooking", BigDecimal.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    @DisplayName("durationMinutesAtBooking has no public setter")
    void should_haveNoPublicSetter_when_fieldIsDurationMinutesAtBooking() {
        assertThatThrownBy(() -> Booking.class.getMethod("setDurationMinutesAtBooking", int.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    @DisplayName("bufferMinutesAtBooking has no public setter")
    void should_haveNoPublicSetter_when_fieldIsBufferMinutesAtBooking() {
        assertThatThrownBy(() -> Booking.class.getMethod("setBufferMinutesAtBooking", int.class))
                .isInstanceOf(NoSuchMethodException.class);
    }

    @Test
    @DisplayName("status has a public setter")
    void should_havePublicSetter_when_fieldIsStatus() throws NoSuchMethodException {
        assertThat(Booking.class.getMethod("setStatus", BookingStatus.class)).isNotNull();
    }

    @Test
    @DisplayName("cancellationReason has a public setter")
    void should_havePublicSetter_when_fieldIsCancellationReason() throws NoSuchMethodException {
        assertThat(Booking.class.getMethod("setCancellationReason", CancellationReason.class)).isNotNull();
    }

    @Test
    @DisplayName("providerComment has a public setter")
    void should_havePublicSetter_when_fieldIsProviderComment() throws NoSuchMethodException {
        assertThat(Booking.class.getMethod("setProviderComment", String.class)).isNotNull();
    }
}
