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

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 399 regression net for the D1 deviation recorded in V212: {@code chk_reviews_source_author} leaves
 * {@code client_id} unconstrained on APP rows because {@code reviews.client_id} is FK {@code ON DELETE SET NULL}
 * (V162, phase 300 D3). Had D1's literal {@code source = 'APP' AND client_id IS NOT NULL} shipped, the hard delete in
 * {@code DELETE /users/me} would abort on the FK action for every client who ever wrote a review. This drives the real
 * endpoint and asserts both the HTTP outcome and the surviving row's shape — a future migration that re-tightens the
 * APP arm turns this red.
 */
@DisplayName("DELETE /users/me with an existing APP review — FK SET NULL passes chk_reviews_source_author (phase 399)")
class AppReviewAuthorSelfDeleteSourceCheckIT extends AbstractIntegrationTest {

    private static final OffsetDateTime PAST = OffsetDateTime.now().minusDays(2);

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;
    private ClientSelfDeleteTestFixtures csd;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        csd = new ClientSelfDeleteTestFixtures(jdbcTemplate, passwordEncoder);
    }

    @Test
    @DisplayName("self-delete succeeds (204), the user row is gone, the review survives as APP with client_id NULL "
            + "and no guest name")
    void should_deleteAccountAndKeepAppReview_when_authorHasReviewUnderSourceCheck() throws Exception {
        UUID clientId = csd.createClient();
        String token = fixtures.tokenFor(emailOf(clientId));
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID bookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);
        csd.insertReview(bookingId, clientId, salon.masterId(), salon.salonId(), 4, "Все сподобалось");
        assertThat(reviewRow(bookingId).get("source")).as("fixture: written as an APP review").isEqualTo("APP");

        ResponseEntity<Void> response = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, clientId))
                .as("the hard delete committed").isZero();
        Map<String, Object> review = reviewRow(bookingId);
        assertThat(review.get("client_id")).as("FK ON DELETE SET NULL detached the author").isNull();
        assertThat(review.get("source")).isEqualTo("APP");
        assertThat(review.get("guest_display_name")).isNull();
        assertThat(((Number) review.get("rating")).intValue()).isEqualTo(4);
    }

    @Test
    @DisplayName("a GUEST_LINK review on the same master is untouched by an unrelated client's self-delete")
    void should_leaveGuestReviewIntact_when_otherClientSelfDeletes() throws Exception {
        UUID clientId = csd.createClient();
        String token = fixtures.tokenFor(emailOf(clientId));
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID appBookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);
        csd.insertReview(appBookingId, clientId, salon.masterId(), salon.salonId(), 5, "Супер");
        UUID guestBookingId = insertGuestBooking(salon);
        jdbcTemplate.update("INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, comment, "
                        + "source, guest_display_name, created_at, updated_at) "
                        + "VALUES (?, ?, NULL, ?, ?, 3, 'Нормально', 'GUEST_LINK', 'Марія Ш.', NOW(), NOW())",
                UUID.randomUUID(), guestBookingId, salon.masterId(), salon.salonId());

        ResponseEntity<Void> response = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        Map<String, Object> guest = reviewRow(guestBookingId);
        assertThat(guest.get("source")).isEqualTo("GUEST_LINK");
        assertThat(guest.get("guest_display_name")).isEqualTo("Марія Ш.");
        assertThat(guest.get("client_id")).isNull();
        assertThat(reviewRow(appBookingId).get("client_id")).isNull();
    }

    private UUID insertGuestBooking(ClientSelfDeleteTestFixtures.Salon salon) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, ?, ?, 'COMPLETED', ?, ?, 500.00, 60, 0, 'LINK', 'Марія', 'Шевченко', "
                        + "'+380675554433', NOW(), NOW())",
                id, salon.masterId(), salon.masterServiceId(), salon.salonId(), PAST.minusHours(3),
                PAST.minusHours(2));
        return id;
    }

    private Map<String, Object> reviewRow(UUID bookingId) {
        return jdbcTemplate.queryForMap(
                "SELECT client_id, source, guest_display_name, rating FROM reviews WHERE booking_id = ?", bookingId);
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
