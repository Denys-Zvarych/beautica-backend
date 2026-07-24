package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 25.3 — pins the locked "notes are visible to all sides" product decision with tests, so
 * a future "privacy fix" cannot silently reintroduce audience-based suppression of
 * {@code providerComment}. This phase adds NO production suppression logic — {@code
 * providerComment} was already correctly returned to both the client and the provider before
 * this phase; these tests exist purely to lock that behaviour down.
 *
 * <p>A prior architect review flagged the client seeing a {@code NOT_COMPLETED} (no-show) note
 * as a privacy leak ("finding D1") and recommended suppressing it. That recommendation was
 * explicitly REJECTED by the product owner — see {@code docs/backend-phases} track 25.x. The
 * test below asserting the note IS present on a {@code NOT_COMPLETED} booking is therefore the
 * INTENDED assertion, not an oversight.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Booking note visibility — mutual, by locked product decision (Phase 25.3)")
class BookingNoteVisibilityIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("client GET /bookings/{id} on a DECLINED booking returns the provider's note")
    void should_returnProviderNote_when_clientViewsDeclinedBooking() {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertBookingWithStatus(fx, "DECLINED", "Майстер захворів і не може прийняти");

        JsonNode data = getBookingData(bookingId, tokenFor(fx.clientEmail));

        assertThat(data.get("providerComment").asText())
                .isEqualTo("Майстер захворів і не може прийняти");
    }

    @Test
    @DisplayName("client GET /bookings/{id} on a NOT_COMPLETED (no-show) booking returns the "
            + "provider's note — INTENDED, not a leak (D1 rejected)")
    void should_returnProviderNote_when_clientViewsNotCompletedBooking() {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertBookingWithStatus(fx, "NOT_COMPLETED", "Клієнт не з'явився на прийом");

        JsonNode data = getBookingData(bookingId, tokenFor(fx.clientEmail));

        assertThat(data.get("providerComment").asText())
                .as("D1 (audience-based suppression on NOT_COMPLETED) is REJECTED — the client "
                        + "must see this note, by locked product decision")
                .isEqualTo("Клієнт не з'явився на прийом");
    }

    @Test
    @DisplayName("provider GET /bookings/{id} sees their own note on both DECLINED and NOT_COMPLETED")
    void should_returnProviderNote_when_providerViewsOwnDeclinedAndNotCompletedBookings() {
        Fixture fx = seedSalonBooking();
        UUID declined = insertBookingWithStatus(fx, "DECLINED", "Салон закритий на ремонт");
        UUID notCompleted = insertBookingWithStatus(fx, "NOT_COMPLETED", "Клієнт скасував телефоном пізно");

        assertThat(getBookingData(declined, tokenFor(fx.ownerEmail)).get("providerComment").asText())
                .isEqualTo("Салон закритий на ремонт");
        assertThat(getBookingData(notCompleted, tokenFor(fx.ownerEmail)).get("providerComment").asText())
                .isEqualTo("Клієнт скасував телефоном пізно");
    }

    @Test
    @DisplayName("guest (LINK, null-client) DECLINED booking — provider view returns the note with no NPE")
    void should_returnProviderNoteWithNoNpe_when_providerViewsGuestDeclinedBooking() {
        // Guest (LINK) bookings have client_id = NULL (V89 chk_bookings_guest_fields). Track
        // 24.x fixed seven separate null-derefs in exactly this area — this test exercises the
        // combination the earlier fixes did not: a null-client booking that ALSO carries a
        // provider_comment, viewed through the same enrichment path client-owned bookings use.
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertGuestBookingWithStatus(fx, "DECLINED", "Майстер захворів");

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(bearerHeaders(tokenFor(fx.ownerEmail))),
                String.class);

        assertThat(resp.getStatusCode())
                .as("provider viewing a guest booking's detail must return 200, never a 500 NPE")
                .isEqualTo(HttpStatus.OK);
        JsonNode data = parseData(resp.getBody());
        assertThat(data.get("clientId").isNull()).isTrue();
        assertThat(data.get("providerComment").asText()).isEqualTo("Майстер захворів");
    }

    @ParameterizedTest(name = "guest booking status={0}")
    @ValueSource(strings = {"CONFIRMED", "COMPLETED", "CANCELLED"})
    @DisplayName("QA-authored: provider GET /bookings/{id} on a guest (null-client) booking never "
            + "NPEs across the remaining statuses — DECLINED is covered above, NOT_COMPLETED is "
            + "covered by NotificationServiceTest at the notification layer; this closes the gap "
            + "at the response-mapping layer for the three statuses neither test exercised. "
            + "provider_comment must be NULL on these three statuses (chk_provider_comment_status), "
            + "so this also doubles as a CHECK-constraint-compatible fixture sweep.")
    void should_return200NoNpe_when_providerViewsGuestBookingAcrossRemainingStatuses(String status) {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertGuestBookingWithStatus(fx, status, null);

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(bearerHeaders(tokenFor(fx.ownerEmail))),
                String.class);

        assertThat(resp.getStatusCode())
                .as("provider viewing a guest booking's detail on status %s must return 200, never a 500 NPE", status)
                .isEqualTo(HttpStatus.OK);
        JsonNode data = parseData(resp.getBody());
        assertThat(data.get("clientId").isNull())
                .as("guest booking must keep clientId null in the response")
                .isTrue();
        assertThat(data.get("providerComment").isNull())
                .as("no provider_comment was written for status %s — must round-trip as null, not blow up", status)
                .isTrue();
    }

    @Test
    @DisplayName("QA-authored: provider GET /bookings/{id} on a guest NOT_COMPLETED booking never NPEs "
            + "at the response-mapping layer (complements the DECLINED test above and "
            + "NotificationServiceTest's notification-layer no-op coverage for this status)")
    void should_return200NoNpe_when_providerViewsGuestNotCompletedBooking() {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertGuestBookingWithStatus(fx, "NOT_COMPLETED", "Клієнт не з'явився");

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(bearerHeaders(tokenFor(fx.ownerEmail))),
                String.class);

        assertThat(resp.getStatusCode())
                .as("provider viewing a guest NOT_COMPLETED booking's detail must return 200, never a 500 NPE")
                .isEqualTo(HttpStatus.OK);
        JsonNode data = parseData(resp.getBody());
        assertThat(data.get("clientId").isNull()).isTrue();
        assertThat(data.get("providerComment").asText()).isEqualTo("Клієнт не з'явився");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private JsonNode getBookingData(UUID bookingId, String token) {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(bearerHeaders(token)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return parseData(resp.getBody());
    }

    private JsonNode parseData(String body) {
        try {
            return objectMapper.readTree(body).get("data");
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse response body: " + body, e);
        }
    }

    private record Fixture(UUID salonId, String ownerEmail, UUID masterId, UUID masterServiceId, String clientEmail, UUID clientId) {}

    private Fixture seedSalonBooking() {
        String ownerEmail = "note-vis-owner-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);

        String masterEmail = "note-vis-master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        String clientEmail = "note-vis-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = createUser(clientEmail, "CLIENT", null);

        return new Fixture(salonId, ownerEmail, masterId, masterServiceId, clientEmail, clientId);
    }

    private UUID insertBookingWithStatus(Fixture fx, String status, String providerComment) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, " +
                        "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, " +
                        "booking_source, provider_comment, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, NOW() - interval '2 hours', NOW() - interval '1 hour', " +
                        "500.00, 60, 0, 'APP', ?, NOW(), NOW())",
                bookingId, fx.clientId, fx.masterId, fx.masterServiceId, fx.salonId, status, providerComment);
        return bookingId;
    }

    private UUID insertGuestBookingWithStatus(Fixture fx, String status, String providerComment) {
        UUID bookingId = UUID.randomUUID();
        // chk_bookings_guest_fields (V91) requires a non-null cancel_token on a LINK row while
        // it is CONFIRMED (an "active" cancellation link); CANCELLED/COMPLETED/NOT_COMPLETED/
        // DECLINED are terminal and may have it NULL. Setting a token unconditionally here
        // satisfies both branches — a leftover token on a terminal row is harmless (it is
        // never consumable again once the row is out of CONFIRMED).
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, " +
                        "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, " +
                        "booking_source, guest_name, guest_surname, guest_phone, cancel_token, provider_comment, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, NOW() - interval '2 hours', NOW() - interval '1 hour', " +
                        "500.00, 60, 0, 'LINK', 'Гість', 'Тестовий', '+380509998877', ?, ?, NOW(), NOW())",
                bookingId, fx.masterId, fx.masterServiceId, fx.salonId, status, UUID.randomUUID(), providerComment);
        return bookingId;
    }

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    private String tokenFor(String email) {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login must succeed for %s", email).isEqualTo(HttpStatus.OK);
        try {
            return objectMapper.readValue(resp.getBody(),
                    new TypeReference<ApiResponse<AuthResponse>>() {}).data().accessToken();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse login response for " + email, e);
        }
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
