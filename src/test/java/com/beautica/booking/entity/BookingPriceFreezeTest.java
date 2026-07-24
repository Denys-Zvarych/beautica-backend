package com.beautica.booking.entity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the two entity-level halves of the Phase 26.9 price-band freeze that no other test asserts
 * directly.
 *
 * <p><b>1. {@link Booking#guestBooking} maps its two adjacent {@code BigDecimal} parameters to the
 * right columns.</b> {@code priceAtBooking} and {@code priceMaxAtBooking} sit side by side, are the
 * same type, and are populated by an all-positional 12-argument factory — the classic silent-swap
 * shape. A swapped pair would charge the guest the CEILING and record the floor as the band's top:
 * a money defect, invisible to the compiler, and (for a FIXED service) invisible to any test that
 * only checks the ceiling, since both values would then be the same number. Asserting BOTH ends
 * against DIFFERENT values is what makes the swap detectable.
 *
 * <p><b>2. {@link Booking#reschedule} re-prices nothing.</b> Its javadoc claims the freeze survives
 * a time move; before this test that claim rested on inspection alone. Moving a booking must never
 * re-derive the band from the service's CURRENT {@code price_type}/{@code price_max} — that is
 * exactly the retroactive-band-edit defect this phase eliminates, and a reschedule is the one
 * mutation that already rewrites entity state and so is the likeliest place for a future "refresh
 * the derived fields" line to land.
 */
@DisplayName("Booking entity — the frozen price band")
class BookingPriceFreezeTest {

    private static final BigDecimal FLOOR = new BigDecimal("300.00");
    private static final BigDecimal CEILING = new BigDecimal("500.00");
    private static final OffsetDateTime STARTS_AT =
            OffsetDateTime.of(2032, 3, 10, 8, 0, 0, 0, ZoneOffset.UTC);

    private static Booking guestBookingWithBand(BigDecimal floor, BigDecimal ceiling) {
        return Booking.guestBooking(
                null, null, null,
                STARTS_AT, STARTS_AT.plusMinutes(60),
                floor, ceiling,
                60, 0,
                "Оксана", "Мельник", "+380509998877");
    }

    @Test
    @DisplayName("guestBooking writes the floor to priceAtBooking and the ceiling to "
            + "priceMaxAtBooking — never the other way round")
    void should_mapBandEndsToTheirOwnColumns_when_guestBookingIsBuilt() {
        Booking booking = guestBookingWithBand(FLOOR, CEILING);

        assertThat(booking.getPriceAtBooking())
                .as("the CHARGED price is the floor — a swap here silently overcharges every guest "
                        + "who books a RANGE service, actual=%s", booking.getPriceAtBooking())
                .isEqualByComparingTo(FLOOR);
        assertThat(booking.getPriceMaxAtBooking())
                .as("the frozen ceiling, actual=%s", booking.getPriceMaxAtBooking())
                .isEqualByComparingTo(CEILING);
    }

    @Test
    @DisplayName("guestBooking leaves priceMaxAtBooking null for a single-price service, without "
            + "borrowing the floor")
    void should_leaveCeilingNull_when_guestBookingHasNoBand() {
        Booking booking = guestBookingWithBand(FLOOR, null);

        assertThat(booking.getPriceAtBooking()).isEqualByComparingTo(FLOOR);
        assertThat(booking.getPriceMaxAtBooking())
                .as("null means 'single price'; copying the floor here would render «300–300 ₴»")
                .isNull();
    }

    @Test
    @DisplayName("reschedule moves the time window and re-prices nothing — neither the frozen floor "
            + "nor the frozen ceiling may move")
    void should_leaveFrozenBandUntouched_when_bookingIsRescheduled() {
        Booking booking = guestBookingWithBand(FLOOR, CEILING);
        OffsetDateTime newStartsAt = STARTS_AT.plusDays(3).withHour(14);

        booking.reschedule(newStartsAt, newStartsAt.plusMinutes(60));

        assertThat(booking.getStartsAt())
                .as("premise of this test — the reschedule must actually have happened")
                .isEqualTo(newStartsAt);
        assertThat(booking.getPriceAtBooking())
                .as("moving a booking in time must never re-price it, actual=%s",
                        booking.getPriceAtBooking())
                .isEqualByComparingTo(FLOOR);
        assertThat(booking.getPriceMaxAtBooking())
                .as("nor re-derive the band from the service's CURRENT price_type/price_max, "
                        + "actual=%s", booking.getPriceMaxAtBooking())
                .isEqualByComparingTo(CEILING);
    }

    @Test
    @DisplayName("reschedule does not grow a ceiling onto a booking that never had one")
    void should_leaveCeilingNull_when_singlePriceBookingIsRescheduled() {
        Booking booking = guestBookingWithBand(FLOOR, null);
        OffsetDateTime newStartsAt = STARTS_AT.plusDays(3).withHour(14);

        booking.reschedule(newStartsAt, newStartsAt.plusMinutes(60));

        assertThat(booking.getPriceMaxAtBooking())
                .as("a band must never appear on a booking the client agreed at a single price")
                .isNull();
    }
}
