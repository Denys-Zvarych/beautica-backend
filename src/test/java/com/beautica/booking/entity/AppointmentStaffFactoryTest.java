package com.beautica.booking.entity;

import com.beautica.TestConstants;
import com.beautica.booking.enums.BookingSource;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.common.exception.BusinessException;
import com.beautica.salon.entity.Salon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 22.9 — unit coverage for {@link Appointment#staffAppointment}, the visit-header mirror of
 * {@link Booking#staffBooking} and the application-layer twin of the STAFF branch of
 * {@code chk_appointment_guest_fields} (V139).
 *
 * <p>Plain JUnit, no Spring: the factory is pure construction logic with no collaborators, so a
 * container would add seconds and prove nothing extra. The DB-level half of the same invariant is
 * covered by {@code V139AppointmentsStaffSourceMigrationTest}.
 */
@DisplayName("Appointment.staffAppointment — STAFF walk-in visit header factory")
class AppointmentStaffFactoryTest {

    private static final UUID STAFF_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("should build a CONFIRMED STAFF header when the full walk-in identity is supplied")
    void should_buildConfirmedStaffHeader_when_allWalkInFieldsPresent() {
        Appointment appointment = Appointment.staffAppointment(
                null, "Олена", "Коваль", "+380501234567", STAFF_ID);

        assertThat(appointment.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(appointment.getBookingSource()).isEqualTo(BookingSource.STAFF);
        assertThat(appointment.getClient()).isNull();
        assertThat(appointment.getCancelToken()).isNull();
        assertThat(appointment.getGuestName()).isEqualTo("Олена");
        assertThat(appointment.getGuestSurname()).isEqualTo("Коваль");
        assertThat(appointment.getGuestPhone()).isEqualTo("+380501234567");
        assertThat(appointment.getCreatedByUserId()).isEqualTo(STAFF_ID);
    }

    @Test
    @DisplayName("should throw BusinessException(BAD_REQUEST) when guestName is blank")
    void should_throwBusinessExceptionBadRequest_when_guestNameBlank() {
        assertThatThrownBy(() -> Appointment.staffAppointment(
                null, "  ", "Коваль", "+380501234567", STAFF_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("guestName")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * The LINK/STAFF asymmetry: {@code guestAppointment} leaves surname optional, but a STAFF
     * walk-in header requires it — the same asymmetry {@code Booking.staffBooking} enforces on the
     * child row, via the very helper this factory reuses.
     */
    @Test
    @DisplayName("should throw BusinessException(BAD_REQUEST) when guestSurname is blank")
    void should_throwBusinessExceptionBadRequest_when_guestSurnameBlank() {
        assertThatThrownBy(() -> Appointment.staffAppointment(
                null, "Олена", "   ", "+380501234567", STAFF_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("guestSurname")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should throw BusinessException(BAD_REQUEST) when guestPhone is blank")
    void should_throwBusinessExceptionBadRequest_when_guestPhoneBlank() {
        assertThatThrownBy(() -> Appointment.staffAppointment(
                null, "Олена", "Коваль", "", STAFF_ID))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("guestPhone")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should throw BusinessException(BAD_REQUEST) when createdByUserId is null")
    void should_throwBusinessExceptionBadRequest_when_createdByUserIdNull() {
        assertThatThrownBy(() -> Appointment.staffAppointment(
                null, "Олена", "Коваль", "+380501234567", null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("createdByUserId")
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * <b>The type is the assertion</b> (security LOW, 2026-08-18 — same finding as
     * {@code BookingStaffFactoryTest}). Pins that the shared helper's {@code BusinessException} type
     * survived being called from a second site: a future "tidy-up" that swaps it for an
     * {@code IllegalArgumentException} would turn every bad walk-in header into a 500, since
     * {@code GlobalExceptionHandler} has no handler for that type.
     */
    @Test
    @DisplayName("should never surface IllegalArgumentException for an invalid walk-in identity")
    void should_notThrowIllegalArgumentException_when_identityInvalid() {
        assertThatThrownBy(() -> Appointment.staffAppointment(
                null, null, "Коваль", "+380501234567", STAFF_ID))
                .isInstanceOf(BusinessException.class)
                .isNotInstanceOf(IllegalArgumentException.class)
                .extracting(e -> ((BusinessException) e).getStatus())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should accept a null salon for an independent-master visit")
    void should_acceptNullSalon_when_independentMaster() {
        Appointment appointment = Appointment.staffAppointment(
                null, "Олена", "Коваль", "+380501234567", STAFF_ID);

        assertThat(appointment.getSalon()).isNull();
    }

    /**
     * The half the null-salon case above cannot prove. It passes the SAME {@code null} the happy
     * path at line 33 passes, so between them nothing ever observed a salon actually reaching the
     * header — deleting {@code .salon(salon)} from {@code Appointment.staffAppointment} was green
     * across the whole class. A salon-bound walk-in whose header lost its {@code salon_id} is a
     * visit that vanishes from every salon-scoped query, so this is not cosmetic.
     *
     * <p>Mutation-check RED by dropping {@code salon} from the factory's builder chain. Its DB twin
     * is {@code StaffBookingIT#should_linkEveryBookingToOneHeader_when_staffVisitPersisted}, which
     * asserts the persisted {@code appointments.salon_id}.
     */
    @Test
    @DisplayName("should carry the salon onto the header when the master is salon-bound")
    void should_carryTheSalon_when_masterBelongsToASalon() {
        Salon salon = Salon.builder()
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID).id(UUID.randomUUID()).isActive(true).build();

        Appointment appointment = Appointment.staffAppointment(
                salon, "Олена", "Коваль", "+380501234567", STAFF_ID);

        assertThat(appointment.getSalon())
                .as("the header must carry the booked salon, not drop it — a salon-less header is "
                        + "invisible to every salon-scoped query")
                .isSameAs(salon);
    }
}
