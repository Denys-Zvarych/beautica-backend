package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** V190 — the DB refuses a visit whose bookings span more than one master. */
@Import(TestSecurityConfig.class)
@DisplayName("appointments — single master per visit, enforced by the database (V190)")
class AppointmentSingleMasterConstraintIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fx;

    @BeforeEach
    void setUpFixtures() {
        fx = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    @Test
    @DisplayName("INSERT of a booking by a different master into an existing visit fails")
    void should_failWithDataIntegrityViolation_when_mixedMasterBookingInsertedIntoAppointment() throws Exception {
        var visit = fx.createConfirmedVisit("asm-ins", 1);
        var other = fx.createSalon("asm-ins-other-" + System.nanoTime() + "@beautica.test");

        assertThatThrownBy(() -> insertBooking(visit.id(), visit.clientId(), other.masterId(),
                fx.createSalonService(other.salonId(), other.masterId()), 30))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE appointment_id = ?", Integer.class, visit.id())).isEqualTo(1);
    }

    @Test
    @DisplayName("UPDATE re-pointing a sibling booking at another master fails")
    void should_failWithDataIntegrityViolation_when_siblingBookingMovedToAnotherMaster() throws Exception {
        var visit = fx.createConfirmedVisit("asm-upd", 2);
        var other = fx.createSalon("asm-upd-other-" + System.nanoTime() + "@beautica.test");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE bookings SET master_id = ? WHERE id = (SELECT id FROM bookings WHERE appointment_id = ? LIMIT 1)",
                other.masterId(), visit.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a second booking by the SAME master, and a standalone booking (NULL appointment_id), still insert")
    void should_allowInsert_when_sameMasterOrNoAppointment() throws Exception {
        var visit = fx.createConfirmedVisit("asm-ok", 1);
        UUID service = jdbcTemplate.queryForObject(
                "SELECT master_service_id FROM bookings WHERE appointment_id = ? LIMIT 1", UUID.class, visit.id());

        insertBooking(visit.id(), visit.clientId(), visit.masterId(), service, 40);
        insertBooking(null, visit.clientId(), visit.masterId(), service, 41);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE appointment_id = ?", Integer.class, visit.id())).isEqualTo(2);
    }

    private void insertBooking(UUID appointmentId, UUID clientId, UUID masterId, UUID masterServiceId, int daysAhead) {
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, appointment_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', NOW() + make_interval(days => ?), "
                        + "NOW() + make_interval(days => ?, hours => 1), 500.00, 60, 0, 'APP', NOW(), NOW())",
                UUID.randomUUID(), clientId, masterId, masterServiceId, appointmentId, daysAhead, daysAhead);
    }
}
