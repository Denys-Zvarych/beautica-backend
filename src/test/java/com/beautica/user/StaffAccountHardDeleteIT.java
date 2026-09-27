package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB happy-path coverage for {@code DELETE /api/v1/users/me} for {@code SALON_ADMIN} and
 * {@code SALON_MASTER} (Phase 301) — mirrors {@link ClientAccountHardDeleteIT}'s structure and
 * raw-SQL fixture conventions for the analogous CLIENT track. {@code INDEPENDENT_MASTER} gets its
 * own dedicated class ({@code IndependentMasterSelfDeleteIT}) since its "no salon above me" delta
 * is the whole point of that suite.
 *
 * <p>Everything at the Mockito level is already covered by {@code StaffAccountSelfDeletionServiceTest}
 * — this class exists for what a mock cannot prove: that Postgres actually accepts the writes under
 * the real V157/V163 schema, that the promoted {@code StaffAccountDisposalService} CASCADE FKs
 * actually fire, and that the caller's OWN access token, obtained through a real login, really
 * stops authenticating afterwards.
 */
@DisplayName("DELETE /api/v1/users/me — Phase 301 SALON_ADMIN/SALON_MASTER self-deletion hard-delete cascade")
class StaffAccountHardDeleteIT extends AbstractIntegrationTest {

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
    @DisplayName("SALON_ADMIN: the users row is physically gone, and every CASCADE-FK'd "
            + "session/device-token row goes with it — no masters row is ever touched (D3 — an "
            + "admin has none)")
    void should_hardDeleteAdmin_withNoMastersRowInvolved() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID adminId = csd.createSalonAdmin(salon);
        String email = emailOf(adminId);
        String token = fixtures.tokenFor(email);
        csd.insertDeviceToken(adminId);
        assertThat(csd.count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", adminId))
                .isEqualTo(1);
        assertThat(csd.count("SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", adminId))
                .isEqualTo(1);
        // Fixture check — the salon's ORIGINAL master survives untouched; only the admin is gone.
        assertThat(csd.masterExists(salon.masterId())).isTrue();

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(adminId)).isFalse();
        assertThat(csd.count("SELECT COUNT(*) FROM refresh_tokens WHERE user_id = ?", adminId)).isZero();
        assertThat(csd.count("SELECT COUNT(*) FROM device_tokens WHERE user_id = ?", adminId)).isZero();
        assertThat(csd.masterExists(salon.masterId()))
                .as("the salon's own master row must be completely untouched by the admin's self-delete")
                .isTrue();
    }

    @Test
    @DisplayName("SALON_MASTER with NO booking/review history: both the users row AND the masters "
            + "row are physically DELETED — the common case (D2)")
    void should_hardDeleteMasterAndMastersRow_when_noHistoryExists() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        String email = emailOf(salon.masterUserId());
        String token = fixtures.tokenFor(email);

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(salon.masterUserId())).isFalse();
        assertThat(csd.masterExists(salon.masterId()))
                .as("no booking/review ever pointed at this master — DELETE, not detach")
                .isFalse();
        // master_services cascades with the deleted masters row.
        assertThat(csd.count("SELECT COUNT(*) FROM master_services WHERE master_id = ?", salon.masterId()))
                .isZero();
    }

    @Test
    @DisplayName("SALON_MASTER WITH a past-booking history: the users row is deleted, but the "
            + "masters row survives DETACHED — name snapshot, user_id NULL, is_active=false (D2)")
    void should_detachMastersRow_when_historyExists() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID pastBookingId = csd.insertBooking(clientId, salon, "COMPLETED",
                java.time.OffsetDateTime.now().minusDays(3));
        String email = emailOf(salon.masterUserId());
        String token = fixtures.tokenFor(email);

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.userExists(salon.masterUserId())).isFalse();
        assertThat(csd.masterExists(salon.masterId()))
                .as("a master with history is DETACHED, never physically deleted")
                .isTrue();
        assertThat(csd.masterIsActive(salon.masterId())).isFalse();
        assertThat(csd.masterUserId(salon.masterId())).isNull();
        assertThat(csd.masterDetachedFirstName(salon.masterId())).isEqualTo("Тест");
        assertThat(csd.masterDetachedAt(salon.masterId())).isNotNull();
        assertThat(csd.bookingExists(pastBookingId))
                .as("the past booking behind the history predicate is untouched")
                .isTrue();
    }

    @Test
    @DisplayName("a second DELETE /users/me with the SAME still-valid pre-delete access token is "
            + "rejected 401 — the account is gone and the token is denylisted (SALON_ADMIN)")
    void should_return401_when_sameAccessTokenReusedAfterAdminSelfDelete() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID adminId = csd.createSalonAdmin(salon);
        String token = fixtures.tokenFor(emailOf(adminId));
        HttpEntity<Void> authed = new HttpEntity<>(fixtures.bearerHeaders(token));

        ResponseEntity<Void> first = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE, authed, Void.class);
        assertThat(first.getStatusCode())
                .as("control — the very same token must work for the first call")
                .isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<String> second = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE, authed, String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a walk-in booking the departing SALON_MASTER rang up survives with "
            + "created_by_user_id cleared to NULL, never cascaded away (V157 phase 294)")
    void should_surviveWalkInBooking_withCreatedByUserIdCleared() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID appointmentId = csd.insertAppointmentHeader(clientId, salon.salonId(), "COMPLETED");
        UUID walkInBookingId = csd.insertBooking(clientId, salon, "COMPLETED",
                java.time.OffsetDateTime.now().minusDays(1), appointmentId);
        jdbcTemplate.update(
                "UPDATE bookings SET created_by_user_id = ? WHERE id = ?",
                salon.masterUserId(), walkInBookingId);
        jdbcTemplate.update(
                "UPDATE appointments SET created_by_user_id = ? WHERE id = ?",
                salon.masterUserId(), appointmentId);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingExists(walkInBookingId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_by_user_id FROM bookings WHERE id = ?", UUID.class, walkInBookingId))
                .isNull();
        assertThat(csd.appointmentExists(appointmentId)).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT created_by_user_id FROM appointments WHERE id = ?", UUID.class, appointmentId))
                .isNull();
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
