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
 * Regression guard for the four {@code INNER JOIN FETCH b.client} -> {@code LEFT JOIN FETCH
 * b.client} conversions in {@code BookingRepository} that V162 made necessary (a detached booking
 * now carries {@code client_id = NULL}, which an inner join would silently drop from every result
 * set). Proves the PROVIDER-side read surfaces — the salon booking list and the single-booking
 * detail endpoint, the two read paths a provider actually uses to see a detached client's history
 * — render the sentinel instead of 500ing or silently omitting the row.
 */
@DisplayName("Provider-side booking list/detail render a detached client's booking without "
        + "500ing (Phase 300 — LEFT JOIN regression guard)")
class DetachedClientBookingRenderIT extends AbstractIntegrationTest {

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
    @DisplayName("GET /bookings/salon/{salonId} — the salon's provider-side booking list renders "
            + "the detached booking with the sentinel client name, not a 500 and not an omitted row")
    void should_renderSentinelInSalonBookingList_when_clientIsDetached() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        UUID bookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);
        String ownerToken = fixtures.tokenFor(emailOf(salon.ownerId()));

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), Void.class);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/bookings/salon/" + salon.salonId(), HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)), String.class);

        assertThat(response.getStatusCode())
                .as("an INNER join regression would silently DROP the row, never 500 here — the "
                        + "row-presence assertion below is what actually catches that")
                .isEqualTo(HttpStatus.OK);
        JsonNode rows = objectMapper.readTree(response.getBody()).path("data").path("data");
        JsonNode row = findById(rows, bookingId);
        assertThat(row)
                .as("the detached booking must still appear in the provider's own salon list")
                .isNotNull();
        assertThat(row.path("clientFirstName").asText()).isEqualTo("Видалений клієнт");
        assertThat(row.path("clientLastName").isNull()).isTrue();
    }

    @Test
    @DisplayName("GET /bookings/{id} — the provider's own booking-detail read renders the "
            + "detached booking with the sentinel client name")
    void should_renderSentinelInBookingDetail_when_clientIsDetached() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        UUID bookingId = csd.insertBooking(clientId, salon, "COMPLETED", PAST);
        String ownerToken = fixtures.tokenFor(emailOf(salon.ownerId()));

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), Void.class);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/bookings/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)), String.class);

        assertThat(response.getStatusCode())
                .as("findByIdWithFullGraph's LEFT JOIN FETCH b.client must not turn a detached "
                        + "booking into a 403 existence-oracle miss")
                .isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("clientId").isNull())
                .as("clientId is null on the wire — never a fabricated id")
                .isTrue();
        assertThat(data.path("clientFirstName").asText()).isEqualTo("Видалений клієнт");
    }

    /**
     * {@code DashboardService} never joins {@code bookings.client_id} at all (it groups completed
     * revenue by date/master/service only), so this is a lower-value SMOKE assertion, not a
     * join-conversion regression guard like the two cases above — included because the QA task
     * explicitly named the dashboard as a render surface to check. What it DOES prove: a detached
     * COMPLETED booking's price is not silently dropped from the owner's revenue total.
     */
    @Test
    @DisplayName("GET /dashboard/revenue — a detached COMPLETED booking's price still counts "
            + "toward the salon owner's revenue total (smoke check, no client join on this path)")
    void should_stillCountRevenue_when_completedBookingsClientIsDetached() throws Exception {
        ClientSelfDeleteTestFixtures.Salon salon = csd.createSalon();
        UUID clientId = csd.createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        csd.insertBooking(clientId, salon, "COMPLETED", PAST);
        String ownerToken = fixtures.tokenFor(emailOf(salon.ownerId()));

        restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(clientToken)), Void.class);

        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/dashboard/revenue?from=" + PAST.toLocalDate().minusDays(1)
                        + "&to=" + PAST.toLocalDate().plusDays(1),
                HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(ownerToken)), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        assertThat(data.path("totalCompletedBookings").asLong())
                .as("the detached booking must still be counted, not silently dropped")
                .isEqualTo(1L);
        assertThat(new java.math.BigDecimal(data.path("estimatedRevenue").asText()))
                .isEqualByComparingTo(new java.math.BigDecimal("500.00"));
    }

    private JsonNode findById(JsonNode rows, UUID id) {
        for (JsonNode row : rows) {
            if (id.toString().equals(row.path("id").asText())) {
                return row;
            }
        }
        return null;
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }
}
