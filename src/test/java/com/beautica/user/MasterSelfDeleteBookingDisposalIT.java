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

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-DB coverage for {@code BookingService#disposeFutureConfirmedForMasterSelfDelete} (Phase 301
 * Q3) — the master-role booking-disposal cascade a {@code SALON_MASTER}/{@code INDEPENDENT_MASTER}
 * self-delete triggers. Complements {@code StaffAccountSelfDeletionServiceTest}'s mocked-collaborator
 * coverage with what only a real Postgres schema and real cascades can prove: the appointment-header
 * survivorship split (mixed-master headers survive, single-master headers collapse), that past
 * bookings and BOTH review directions are left untouched, and the D7 asymmetry — {@code
 * client_reviews} authored by the departing master survive and the SUBJECT CLIENT's own rating never
 * moves — is the single most important behavioural difference from the CLIENT self-delete track and
 * is asserted here, not assumed.
 */
@DisplayName("DELETE /api/v1/users/me (SALON_MASTER) — future-booking disposal cascade (Phase 301 Q3)")
class MasterSelfDeleteBookingDisposalIT extends AbstractIntegrationTest {

    private static final OffsetDateTime FUTURE = OffsetDateTime.now().plusDays(5);
    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(3);

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
    @DisplayName("every future CONFIRMED booking of the departing master is gone after self-delete")
    void should_deleteFutureConfirmedBookings_belongingToTheDepartingMaster() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID futureBookingId = csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingExists(futureBookingId))
                .as("declined-then-hard-deleted in the same transaction — never left as a row")
                .isFalse();
    }

    @Test
    @DisplayName("a multi-master appointment header SURVIVES when only one of its two masters "
            + "self-deletes — the other master's leg keeps the header alive")
    void should_surviveAppointmentHeader_when_siblingMasterLegRemains() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        ClientSelfDeleteTestFixtures.SecondMaster masterB = csd.addSecondMaster(salon);
        UUID clientId = csd.createClient();
        UUID appointmentId = csd.insertAppointmentHeader(clientId, salon.salonId(), "CONFIRMED");
        UUID masterALegId = csd.insertBooking(
                clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(),
                "CONFIRMED", FUTURE, appointmentId);
        UUID masterBLegId = csd.insertBooking(
                clientId, masterB.masterId(), masterB.masterServiceId(), salon.salonId(),
                "CONFIRMED", FUTURE, appointmentId);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingExists(masterALegId))
                .as("masterA's own leg is disposed of")
                .isFalse();
        assertThat(csd.bookingExists(masterBLegId))
                .as("masterB never self-deleted — their leg is untouched")
                .isTrue();
        assertThat(csd.appointmentExists(appointmentId))
                .as("the header still has a surviving leg (masterB's) — it must NOT be collapsed")
                .isTrue();
    }

    @Test
    @DisplayName("an appointment header whose ONLY leg belonged to the departing master is deleted "
            + "once it becomes childless")
    void should_deleteAppointmentHeader_when_allLegsBelongedToTheDepartingMaster() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID appointmentId = csd.insertAppointmentHeader(clientId, salon.salonId(), "CONFIRMED");
        UUID onlyLegId = csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE, appointmentId);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingExists(onlyLegId)).isFalse();
        assertThat(csd.appointmentExists(appointmentId))
                .as("a fully-emptied header must be collapsed, never left as a dangling shell")
                .isFalse();
    }

    @Test
    @DisplayName("a past booking of the departing master survives untouched — only FUTURE CONFIRMED "
            + "bookings are disposed of")
    void should_leavePastBookingsUntouched() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID pastBookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.bookingExists(pastBookingId)).isTrue();
    }

    @Test
    @DisplayName("client→provider reviews about the departing master survive untouched — "
            + "reviews.master_id is NO ACTION, a predicate that forces DETACH, and the aggregate "
            + "freezes on the detached stub (mirrors phase 300's D3 for the master side)")
    void should_leaveClientToProviderReviewsUntouched() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID pastBookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);
        csd.insertReview(pastBookingId, clientId, salon.masterId(), salon.salonId(), 5, "Чудово!");
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.count("SELECT COUNT(*) FROM reviews WHERE master_id = ?", salon.masterId()))
                .as("the review a client left ABOUT this master must survive — this is the very "
                        + "predicate that forces the DETACH branch, not deletion")
                .isEqualTo(1);
        assertThat(csd.masterIsActive(salon.masterId())).isFalse();
    }

    @Test
    @DisplayName("D7 — client_reviews AUTHORED by the departing master survive, and the SUBJECT "
            + "CLIENT's own rating is UNCHANGED — deleting them would silently drop a living "
            + "client's rating, the exact data-loss bug D7 exists to prevent")
    void should_keepAuthoredClientReviews_andLeaveSubjectClientRatingUnchanged() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID subjectClientId = csd.createClient();
        UUID pastBookingId = csd.insertBooking(subjectClientId, salon, "COMPLETED", PAST);
        csd.insertClientReview(pastBookingId, subjectClientId, salon.masterId(), salon.salonId(), 5);
        // Simulate the rating aggregate this client_review contributed to, exactly as the real
        // rating-recompute path would have written it — the fixture must genuinely name a
        // non-default value so a silent reset back to a zero/null default cannot pass this test.
        jdbcTemplate.update(
                "UPDATE users SET avg_rating = ?, review_count = 1 WHERE id = ?",
                new BigDecimal("5.00"), subjectClientId);
        String token = fixtures.tokenFor(emailOf(salon.masterUserId()));

        ResponseEntity<Void> response = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(csd.count(
                "SELECT COUNT(*) FROM client_reviews WHERE author_master_id = ?", salon.masterId()))
                .as("D7 — authored client_reviews are KEPT, the opposite of the CLIENT self-delete "
                        + "flow's D3")
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT avg_rating FROM users WHERE id = ?", BigDecimal.class, subjectClientId))
                .as("the subject client's own rating must not move — that client is still using the "
                        + "app and did nothing")
                .isEqualByComparingTo("5.00");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT review_count FROM users WHERE id = ?", Integer.class, subjectClientId))
                .isEqualTo(1);
        assertThat(csd.userExists(subjectClientId))
                .as("the subject client's own account is completely unrelated to the master's "
                        + "self-delete")
                .isTrue();
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
