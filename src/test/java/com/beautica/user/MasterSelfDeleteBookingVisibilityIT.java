package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.booking.BookingTestFixtures;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HTTP-level "who can see it, and what do they see" coverage for Phase 337's decline-and-KEEP
 * behaviour, complementing {@code MasterSelfDeleteBookingDisposalIT}/{@code
 * MasterSelfDeletionFutureBookingsIT}'s DB-level assertions with the two READ surfaces the phase
 * doc's requirement is actually ABOUT: the CLIENT's own booking list (the whole point of "keep,
 * don't delete" is that the client still sees what happened to their booking) and the SALON
 * OWNER's board read of a departed {@code SALON_MASTER}'s now-detached history row.
 */
@DisplayName("DELETE /api/v1/users/me (master self-delete) — kept-booking read surfaces (Phase 337 QA)")
class MasterSelfDeleteBookingVisibilityIT extends AbstractIntegrationTest {

    private static final OffsetDateTime FUTURE = OffsetDateTime.now().plusDays(5);
    private static final String USERS_ME_URL = "/api/v1/users/me";
    private static final String BOOKINGS_ME_URL = "/api/v1/bookings/me";

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
    @DisplayName("the client's own GET /bookings/me still renders the DECLINED booking by "
            + "snapshotted name after the master hard-deletes, and never leaks the deleted "
            + "account's email/phone anywhere in the response body")
    void should_renderDeclinedBookingToClient_withoutLeakingDeletedMasterContactInfo() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        String masterEmail = emailOf(salon.masterUserId());
        String masterPhone = "+380671234567";
        jdbcTemplate.update("UPDATE users SET phone_number = ? WHERE id = ?",
                masterPhone, salon.masterUserId());

        UUID clientId = csd.createClient();
        String clientEmail = emailOf(clientId);
        UUID bookingId = csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE);

        String masterToken = fixtures.tokenFor(masterEmail);
        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                USERS_ME_URL, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(masterToken)), Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String clientToken = fixtures.tokenFor(clientEmail);
        ResponseEntity<String> listResponse = restTemplate.exchange(
                BOOKINGS_ME_URL, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), String.class);

        assertThat(listResponse.getStatusCode())
                .as("the client's own booking list must render cleanly, never a 500, for a booking "
                        + "whose master row is now detached (user_id = NULL) — body: %s",
                        listResponse.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode root = objectMapper.readTree(listResponse.getBody());
        JsonNode row = findRowById(root, bookingId);
        assertThat(row)
                .as("the DECLINED booking must still be present in the client's list — body: %s",
                        listResponse.getBody())
                .isNotNull();
        assertThat(row.path("status").asText())
                .as("the kept booking must render as DECLINED, not silently vanish")
                .isEqualTo("DECLINED");
        assertThat(row.path("masterFirstName").asText())
                .as("the master's name must still render from the V157 detachment snapshot, never "
                        + "blank/null, so the client's receipt still says who the provider was")
                .isNotBlank();

        assertThat(listResponse.getBody())
                .as("the deleted master's email must never appear anywhere in the client's own "
                        + "booking list response — BookingDetailResponse carries no email/phone "
                        + "field by construction, but this proves it end-to-end against the real "
                        + "serialized payload, not just the DTO's declared shape")
                .doesNotContain(masterEmail);
        assertThat(listResponse.getBody())
                .as("the deleted master's phone number must never appear anywhere in the client's "
                        + "own booking list response")
                .doesNotContain(masterPhone);
    }

    @Test
    @DisplayName("the salon owner's own GET /bookings/salon/{salonId} still renders the kept "
            + "DECLINED booking after their SALON_MASTER self-deletes — no 500 on a booking whose "
            + "master row is now detached (user_id = NULL)")
    void should_renderDeclinedBookingOnOwnerBoard_afterSalonMasterSelfDeletes() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        UUID bookingId = csd.insertBooking(clientId, salon, "CONFIRMED", FUTURE);

        String masterToken = fixtures.tokenFor(emailOf(salon.masterUserId()));
        ResponseEntity<Void> deleteResponse = restTemplate.exchange(
                USERS_ME_URL, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(masterToken)), Void.class);
        assertThat(deleteResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String ownerToken = fixtures.tokenFor(emailOf(salon.ownerId()));
        ResponseEntity<String> boardResponse = restTemplate.exchange(
                "/api/v1/bookings/salon/" + salon.salonId(), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)), String.class);

        assertThat(boardResponse.getStatusCode())
                .as("the owner's salon board read must never 500 on a history row whose master is "
                        + "now a detached stub (user_id = NULL) — body: %s", boardResponse.getBody())
                .isEqualTo(HttpStatus.OK);

        JsonNode root = objectMapper.readTree(boardResponse.getBody());
        JsonNode row = findRowById(root, bookingId);
        assertThat(row)
                .as("the owner must still be able to see the DECLINED booking on their own salon "
                        + "board — body: %s", boardResponse.getBody())
                .isNotNull();
        assertThat(row.path("status").asText()).isEqualTo("DECLINED");
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private JsonNode findRowById(JsonNode root, UUID bookingId) {
        for (JsonNode row : root.path("data").path("data")) {
            if (bookingId.toString().equals(row.path("id").asText())) {
                return row;
            }
        }
        return null;
    }
}
