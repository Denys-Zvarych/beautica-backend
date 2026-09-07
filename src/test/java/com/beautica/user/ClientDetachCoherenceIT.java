package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the V162/V163 {@code chk_bookings_guest_fields} / {@code chk_appointment_guest_fields}
 * CHECK constraint's fourth (DETACHED) arm against a real Postgres — referenced by name in
 * V162's own header comment ("Pinned by ClientDetachCoherenceIT").
 *
 * <p>Two things a Mockito unit test cannot prove: (1) the DB actually accepts the detached shape
 * the service writes, for BOTH a fully-childless header (deleted) and a partially-elapsed header
 * (detached, not deleted) — the set-based survivorship partition in
 * {@code ClientAccountDeletionService} — and (2) the CHECK genuinely rejects a half-detached row,
 * which is the entire reason the service must write the sentinel/null/stamp together in ONE
 * UPDATE rather than three.
 */
@DisplayName("V162/V163 client-detach CHECK constraints — Phase 300 coherence")
class ClientDetachCoherenceIT extends AbstractIntegrationTest {

    private static final OffsetDateTime FUTURE = OffsetDateTime.now().plusDays(7);
    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(2);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
    }

    @Test
    @DisplayName("a partially-elapsed visit (one COMPLETED leg, one future CONFIRMED leg) leaves "
            + "the appointment header DETACHED and SURVIVING, not deleted")
    void should_detachAppointmentHeader_when_oneLegSurvivesAndOneIsCancelled() throws Exception {
        UUID clientId = csd.createClient();
        String token = fixtures.tokenFor(emailOf(clientId));
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID appointmentId = csd.insertAppointmentHeader(clientId, salon.salonId(), "CONFIRMED");
        UUID completedLeg = csd.insertBooking(clientId, salon, "COMPLETED", PAST, appointmentId);
        UUID futureLeg = csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE, appointmentId);

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(csd.appointmentExists(appointmentId))
                .as("a header with at least one surviving child is DETACHED, never deleted")
                .isTrue();
        assertThat(appointmentClientIdOf(appointmentId)).isNull();
        assertThat(appointmentDetachedAtOf(appointmentId)).isNotNull();
        assertThat(csd.bookingExists(completedLeg))
                .as("the surviving (past) leg is detached, not deleted")
                .isTrue();
        assertThat(csd.bookingExists(futureLeg))
                .as("the future CONFIRMED leg was cancelled then physically deleted (D4)")
                .isFalse();
    }

    @Test
    @DisplayName("a visit whose every leg was future-CONFIRMED (all cancelled + deleted) leaves "
            + "the appointment header DELETED, not detached")
    void should_deleteAppointmentHeader_when_everyLegWasFutureConfirmed() throws Exception {
        UUID clientId = csd.createClient();
        String token = fixtures.tokenFor(emailOf(clientId));
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID appointmentId = csd.insertAppointmentHeader(clientId, salon.salonId(), "CONFIRMED");
        UUID legA = csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE, appointmentId);
        UUID legB = csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE.plusHours(1), appointmentId);

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(csd.appointmentExists(appointmentId))
                .as("a CHILDLESS header (every leg deleted) is itself deleted, never left detached")
                .isFalse();
        assertThat(csd.bookingExists(legA)).isFalse();
        assertThat(csd.bookingExists(legB)).isFalse();
    }

    @Test
    @DisplayName("the widened chk_bookings_guest_fields still REJECTS a half-detached row — "
            + "client_id nulled without the sentinel and the stamp")
    void should_rejectHalfDetachedBooking_underWidenedCheck() {
        UUID clientId = csd.createClient();
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID bookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);

        // A bare client_id=NULL with NO sentinel and NO stamp satisfies none of the CHECK's four
        // arms — this is precisely what forces the service to write all three columns together.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE bookings SET client_id = NULL WHERE id = ?", bookingId))
                .as("a half-detached row (no sentinel, no stamp) must be UNWRITABLE")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("the widened chk_bookings_guest_fields ACCEPTS a fully-formed detached row "
            + "written directly (proves the DETACHED arm itself, independent of the service code)")
    void should_acceptFullyFormedDetachedBooking_underWidenedCheck() {
        UUID clientId = csd.createClient();
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID bookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);

        jdbcTemplate.update(
                "UPDATE bookings SET client_id = NULL, guest_name = 'Видалений клієнт', "
                        + "guest_surname = NULL, client_detached_at = NOW() WHERE id = ?",
                bookingId);

        assertThat(clientIdOf(bookingId)).isNull();
        assertThat(guestNameOf(bookingId)).isEqualTo("Видалений клієнт");
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private UUID clientIdOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT client_id FROM bookings WHERE id = ?", UUID.class, bookingId);
    }

    private String guestNameOf(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT guest_name FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private UUID appointmentClientIdOf(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT client_id FROM appointments WHERE id = ?", UUID.class, appointmentId);
    }

    private java.sql.Timestamp appointmentDetachedAtOf(UUID appointmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT client_detached_at FROM appointments WHERE id = ?", java.sql.Timestamp.class, appointmentId);
    }
}
