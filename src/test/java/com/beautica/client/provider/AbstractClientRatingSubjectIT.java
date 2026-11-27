package com.beautica.client.provider;

import com.beautica.auth.registration.AbstractPhoneIdentityIT;
import com.beautica.common.security.PhoneHasher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B546 — shared seeding and HTTP helpers for the guest / walk-in client-rating ITs, with the guest rating flag ON
 * ({@code APP_GUEST_CLIENT_RATING_ENABLED}, REV-41). Subclasses share one Spring context.
 */
@TestPropertySource(properties = "app.client-rating.guest-enabled=true")
abstract class AbstractClientRatingSubjectIT extends AbstractPhoneIdentityIT {

    protected static final String CLIENT_REVIEWS_URL = "/api/v1/client-reviews";
    private static final String REGISTER_URL = "/api/v1/auth/register";
    private static final String PHONE_SEND_URL = "/api/v1/users/me/phone/otp/send";
    private static final String PHONE_VERIFY_URL = "/api/v1/users/me/phone/otp/verify";

    @Autowired protected PhoneHasher phoneHasher;

    protected ProviderClientFixtures fx;

    @BeforeEach
    void setUpRatingFixtures() {
        fx = new ProviderClientFixtures(jdbcTemplate, restTemplate, objectMapper, phoneHasher, this::testCityId,
                this::resolveUnusedServiceTypeId);
    }

    /** A COMPLETED guest (LINK) or walk-in (STAFF) booking of {@code phone} at the salon, in the past. */
    protected UUID completedGuest(ProviderClientFixtures.Salon s, String source, String phone) {
        return fx.guestBooking(s.masterId(), s.salonId(), source, phone, "Іванна", "Коваль", "COMPLETED", fx.at(-5));
    }

    /** {@code POST /client-reviews} as {@code token}. */
    protected ResponseEntity<String> rate(String token, UUID bookingId, int stars, String comment) {
        Map<String, Object> body = new HashMap<>();
        body.put("bookingId", bookingId.toString());
        body.put("rating", stars);
        if (comment != null) {
            body.put("comment", comment);
        }
        return postAs(token, CLIENT_REVIEWS_URL, body);
    }

    protected void rateOk(String token, UUID bookingId, int stars, String comment) {
        ResponseEntity<String> resp = rate(token, bookingId, stars, comment);
        assertThat(resp.getStatusCode()).as("rate body: %s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    /** subject_client_id, subject_guest_phone, comment of the booking's client_reviews row. */
    protected Map<String, Object> reviewRow(UUID bookingId) {
        return jdbcTemplate.queryForMap("SELECT subject_client_id, subject_guest_phone, comment, rating "
                + "FROM client_reviews WHERE booking_id = ?", bookingId);
    }

    protected int reviewCount(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM client_reviews WHERE booking_id = ?",
                Integer.class, bookingId);
    }

    protected ResponseEntity<String> register(String phone) {
        Map<String, Object> body = new HashMap<>();
        body.put("registrationTicket", ticketFor(phone));
        body.put("password", PASSWORD);
        body.put("firstName", "Олена");
        body.put("role", "CLIENT");
        ResponseEntity<String> resp = post(REGISTER_URL, consent(body));
        assertThat(resp.getStatusCode()).as("register body: %s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return resp;
    }

    protected UUID userIdOfPhone(String phone) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE phone_number = ?", UUID.class, phone);
    }

    /** UserPhoneService add / change phone over HTTP (send code, verify code). */
    protected void verifyPhone(String token, String phone, String currentPassword) {
        assertThat(postAs(token, PHONE_SEND_URL, Map.of("phone", phone)).getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<String, Object> body = new HashMap<>(Map.of("phone", phone, "code", lastCodeSentTo(phone)));
        if (currentPassword != null) {
            body.put("currentPassword", currentPassword);
        }
        ResponseEntity<String> verified = postAs(token, PHONE_VERIFY_URL, body);
        assertThat(verified.getStatusCode()).as("verify body: %s", verified.getBody()).isEqualTo(HttpStatus.OK);
    }

    /** {@code GET /users/me/rating} data node. */
    protected JsonNode myRating(String token) throws Exception {
        ResponseEntity<String> resp = fx.get(token, "/api/v1/users/me/rating");
        assertThat(resp.getStatusCode()).as(resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    protected String guestCardUrl(UUID salonId, UUID anchorBookingId) {
        return "/api/v1/salons/" + salonId + "/clients/guests/" + anchorBookingId;
    }
}
