package com.beautica.booking.entity;

import com.beautica.booking.enums.BookingSource;
import com.beautica.common.exception.BusinessException;
import com.beautica.booking.enums.BookingStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 22.1 — unit coverage for {@link Booking#staffBooking}, the application-layer mirror of the
 * walk-in branch of {@code chk_bookings_guest_fields} (V137).
 *
 * <p>Plain JUnit, no Spring: the factory is pure construction logic with no collaborators, so a
 * container would add seconds and prove nothing extra. The DB-level half of the same invariant is
 * covered by {@code V137StaffBookingSourceMigrationTest}.
 */
@DisplayName("Booking.staffBooking — STAFF walk-in factory")
class BookingStaffFactoryTest {

    private static final OffsetDateTime STARTS_AT =
            OffsetDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime ENDS_AT = STARTS_AT.plusMinutes(60);
    private static final UUID STAFF_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("should build a CONFIRMED STAFF booking when the full walk-in identity is supplied")
    void should_buildConfirmedStaffBooking_when_walkInIdentityComplete() {
        Booking booking = staffBooking("Олена", "Коваль", "+380501234567", STAFF_ID);

        assertThat(booking.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(booking.getBookingSource()).isEqualTo(BookingSource.STAFF);
        assertThat(booking.getGuestName()).isEqualTo("Олена");
        assertThat(booking.getGuestSurname()).isEqualTo("Коваль");
        assertThat(booking.getGuestPhone()).isEqualTo("+380501234567");
        assertThat(booking.getCreatedByUserId()).isEqualTo(STAFF_ID);
    }

    /**
     * A STAFF booking has no self-service guest cancel link — only the owner/admin cancels it via a
     * management endpoint. The V137 CHECK requires {@code cancel_token IS NULL} for both STAFF
     * identity modes, so a non-null token here would be an unconditional insert failure.
     */
    @Test
    @DisplayName("should leave cancelToken and client null when a STAFF walk-in booking is built")
    void should_leaveCancelTokenAndClientNull_when_walkInBookingBuilt() {
        Booking booking = staffBooking("Олена", "Коваль", "+380501234567", STAFF_ID);

        assertThat(booking.getCancelToken()).isNull();
        assertThat(booking.getClient()).isNull();
    }

    @Test
    @DisplayName("should freeze the price band and duration snapshot passed by the caller")
    void should_freezeSnapshotFields_when_walkInBookingBuilt() {
        Booking booking = staffBooking("Олена", "Коваль", "+380501234567", STAFF_ID);

        assertThat(booking.getPriceAtBooking()).isEqualByComparingTo("350.00");
        assertThat(booking.getPriceMaxAtBooking()).isEqualByComparingTo("500.00");
        assertThat(booking.getDurationMinutesAtBooking()).isEqualTo(60);
        assertThat(booking.getBufferMinutesAtBooking()).isEqualTo(15);
    }

    /**
     * {@code reminder_sent} is NOT NULL with no default on the entity side, and a booking built
     * with it already true would silently never get its reminder — a miss no assertion elsewhere
     * would catch, because the field is invisible in every response DTO.
     */
    @Test
    @DisplayName("should not pre-mark the reminder as sent when a STAFF walk-in booking is built")
    void should_leaveReminderUnsent_when_walkInBookingBuilt() {
        Booking booking = staffBooking("Олена", "Коваль", "+380501234567", STAFF_ID);

        assertThat(booking.isReminderSent())
                .as("a brand-new booking must still be eligible for its reminder")
                .isFalse();
    }

    /**
     * A single-price service books with a null ceiling ({@code priceMaxAtBooking} is the frozen
     * RANGE ceiling, null when there is no range). Rejecting it here would make the factory
     * unusable for the majority of services while every fixed-band test stayed green.
     */
    @Test
    @DisplayName("should accept a null priceMaxAtBooking (single-price, non-RANGE service)")
    void should_acceptNullPriceMax_when_serviceHasSinglePrice() {
        Booking booking = Booking.staffBooking(
                null, null, null, STARTS_AT, ENDS_AT,
                new BigDecimal("350.00"), null, 60, 15,
                "Олена", "Коваль", "+380501234567", STAFF_ID);

        assertThat(booking.getPriceMaxAtBooking()).isNull();
        assertThat(booking.getPriceAtBooking()).isEqualByComparingTo("350.00");
    }

    @Test
    @DisplayName("should reject a blank guestName")
    void should_reject_when_guestNameBlank() {
        assertThatThrownBy(() -> staffBooking("  ", "Коваль", "+380501234567", STAFF_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("guestName")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Null and blank are separate rejections in the guard and are asserted separately: a guard
     * written as {@code isBlank()} alone NPEs on null, and one written as {@code == null} alone
     * lets the empty string through to the DB. The three identity fields each get both cases so
     * neither half can be dropped silently.
     */
    @Test
    @DisplayName("should reject a null guestName")
    void should_reject_when_guestNameNull() {
        assertThatThrownBy(() -> staffBooking(null, "Коваль", "+380501234567", STAFF_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("guestName")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should reject a blank guestSurname")
    void should_reject_when_guestSurnameBlank() {
        assertThatThrownBy(() -> staffBooking("Олена", "   ", "+380501234567", STAFF_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("guestSurname")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should reject a null guestPhone")
    void should_reject_when_guestPhoneNull() {
        assertThatThrownBy(() -> staffBooking("Олена", "Коваль", null, STAFF_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("guestPhone")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * Deliberately stricter than {@link Booking#guestBooking}, where a surname is optional: the
     * staff walk-in flow requires first name, last name and phone (locked product decision), and
     * the V137 CHECK's STAFF branch encodes the same asymmetry.
     */
    @Test
    @DisplayName("should reject a null guestSurname (unlike the LINK guest factory)")
    void should_reject_when_guestSurnameNull() {
        assertThatThrownBy(() -> staffBooking("Олена", null, "+380501234567", STAFF_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("guestSurname")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should reject a blank guestPhone")
    void should_reject_when_guestPhoneBlank() {
        assertThatThrownBy(() -> staffBooking("Олена", "Коваль", "", STAFF_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("guestPhone")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should reject a null createdByUserId")
    void should_reject_when_createdByUserIdNull() {
        assertThatThrownBy(() -> staffBooking("Олена", "Коваль", "+380501234567", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("createdByUserId")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * <b>The type is the assertion</b> (security LOW, 2026-08-18). This guard used to throw
     * {@link IllegalArgumentException}, for which {@code GlobalExceptionHandler} has no handler — so
     * every one of these rejections would have surfaced as a <b>500 with a full ERROR stack
     * trace</b> via the {@code Exception.class} catch-all, for a missing required input the Phase
     * 22.2 command records already reject as a 400. Asserting only "it throws" let that pass; the
     * status is what a caller and an on-call engineer actually see.
     *
     * <p><b>Every guard branch now pins the status</b> (QA 2026-08-18), not just this one. This case
     * used to be the sole 400 assertion in the class while the other seven asserted type + message
     * only, so a status regression on the {@code createdByUserId} or name/surname branches — the
     * shape a 22.4 controller mapping is most likely to hit — would have shipped green. It survives
     * as the named blank-phone case; the {@code .extracting(getStatus())} chain on each sibling is
     * what closes the rest.
     */
    @Test
    @DisplayName("should carry a 400 status on the blank-phone branch, not the handler's 500 catch-all")
    void should_reject400_when_walkInIdentityIncomplete() {
        assertThatThrownBy(() -> staffBooking("Олена", "Коваль", "  ", STAFF_ID))
                .isInstanceOfSatisfying(BusinessException.class,
                        ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private static Booking staffBooking(String name, String surname, String phone, UUID staffId) {
        return Booking.staffBooking(
                null,   // master / masterService / salon are opaque FKs to this factory —
                null,   // it validates identity only, so null keeps the test free of a
                null,   // persistence graph it would otherwise never touch.
                STARTS_AT,
                ENDS_AT,
                new BigDecimal("350.00"),
                new BigDecimal("500.00"),
                60,
                15,
                name,
                surname,
                phone,
                staffId);
    }
}
