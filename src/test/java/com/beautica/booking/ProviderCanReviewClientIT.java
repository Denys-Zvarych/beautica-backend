package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Extends track 27.x / Phase 27.5 — {@code GET /bookings/{id}}'s viewer-aware
 * {@code providerCanReviewClient} field, which lets the mobile app pre-gate the "Залишити відгук
 * про клієнта" CTA instead of always showing it and relying on a 409 from
 * {@code POST /client-reviews}.
 *
 * <p>The predicate under test, {@code BookingService#computeProviderCanReviewClient}, deliberately
 * reuses {@code AuthorizationService#hasProviderAuthorityOverBooking} — the exact same predicate
 * {@code ClientReviewService.create} enforces before persisting — so this suite's authority matrix
 * mirrors {@link com.beautica.review.ClientReviewIT}'s 403/400 matrix, just read through
 * {@code GET /bookings/{id}} instead of driving a write.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /bookings/{id} — providerCanReviewClient (extends Phase 27.5)")
class ProviderCanReviewClientIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String CLIENT_REVIEWS_URL = "/api/v1/client-reviews";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @Test
    @DisplayName("true — INDEPENDENT_MASTER views their own COMPLETED, non-guest, not-yet-reviewed booking")
    void should_returnTrue_when_independentMasterViewsOwnCompletedUnreviewedBooking() throws Exception {
        String masterEmail = "pcrc-im-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createIndependentMaster(masterEmail);
        UUID masterServiceId = createIndependentMasterService(masterId);
        UUID clientId = createUser("pcrc-im-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, masterId, masterServiceId, null, "COMPLETED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(masterEmail));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("an independent master viewing their own completed, unreviewed booking must "
                        + "be offered the client-review CTA")
                .isTrue();
    }

    @Test
    @DisplayName("true — SALON_OWNER views a COMPLETED booking for their salon (real client, not yet reviewed)")
    void should_returnTrue_when_salonOwnerViewsOwnSalonsCompletedUnreviewedBooking() throws Exception {
        Salon salon = createSalon("pcrc-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-owner-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("the owning SALON_OWNER must be offered the client-review CTA for a completed, "
                        + "unreviewed booking at their own salon")
                .isTrue();
    }

    /**
     * NOT the "true" case the track-27 spec originally asked for. Investigation found that
     * {@code GET /bookings/{id}} is gated by {@code AuthorizationService#enforceCanViewBooking}
     * — a SEPARATE, pre-existing, and deliberately owner-only predicate (see
     * {@code isAuthorizedToManageBooking}'s javadoc: "Do NOT broaden this predicate... the
     * divergence [from the provider-action predicate, which DOES admit SALON_ADMIN] is
     * intentional", locked at Phase 24.2) that NEVER admits {@code SALON_ADMIN} — assigned or
     * not. So an assigned admin 403s on the view gate before {@code providerCanReviewClient} is
     * ever computed, even though {@code hasProviderAuthorityOverBooking} (the predicate this
     * field reuses, and the one {@code POST /client-reviews} enforces) WOULD admit them. This
     * pins that real, pre-existing behaviour rather than asserting a 200+true outcome the system
     * cannot produce — flagged to the requester rather than silently widening the view gate,
     * which is a separate authorization-scope decision this task did not ask for.
     */
    @Test
    @DisplayName("403 (NOT the DTO) — an assigned SALON_ADMIN cannot reach GET /bookings/{id} at "
            + "all; the pre-existing owner-only view gate excludes SALON_ADMIN regardless of "
            + "assignment, so providerCanReviewClient is unreachable for this role via this endpoint")
    void should_return403_when_assignedSalonAdminViewsBookingDetail() throws Exception {
        Salon salon = createSalon("pcrc-admin-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "pcrc-admin-" + System.nanoTime() + "@beautica.test";
        createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID clientId = createUser("pcrc-admin-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenFor(adminEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("enforceCanViewBooking excludes SALON_ADMIN unconditionally — even an admin "
                        + "assigned to this exact salon — so this 403s before providerCanReviewClient "
                        + "is ever computed; body=%s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("false — after the provider has already left a client review for this booking")
    void should_returnFalse_when_providerAlreadyReviewedTheClient() throws Exception {
        Salon salon = createSalon("pcrc-dup-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-dup-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");
        String ownerToken = tokenFor(salon.ownerEmail());

        ResponseEntity<String> reviewResp = restTemplate.exchange(
                CLIENT_REVIEWS_URL, HttpMethod.POST,
                new HttpEntity<>("{\"bookingId\":\"" + bookingId + "\",\"rating\":5}", bearerHeaders(ownerToken)),
                String.class);
        assertThat(reviewResp.getStatusCode())
                .as("fixture sanity — the review must actually be persisted, body=%s", reviewResp.getBody())
                .isEqualTo(HttpStatus.CREATED);

        JsonNode detail = getBookingDetail(bookingId, ownerToken);

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("a booking that already has a client review must never re-offer the CTA")
                .isFalse();
    }

    @Test
    @DisplayName("false — the booking is CONFIRMED and its endsAt has NOT yet elapsed (not COMPLETED, not awaiting closure)")
    void should_returnFalse_when_bookingIsConfirmedAndNotYetElapsed() throws Exception {
        Salon salon = createSalon("pcrc-confirmed-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-confirmed-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertFutureBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "CONFIRMED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("a still-open, non-elapsed CONFIRMED booking can never be reviewed yet")
                .isFalse();
    }

    @Test
    @DisplayName("true — the booking is CONFIRMED but its endsAt has already ELAPSED, even though the "
            + "provider never marked it COMPLETED — the bug fix mirrored from the client-side "
            + "canReview widening")
    void should_returnTrue_when_bookingIsConfirmedButElapsed() throws Exception {
        Salon salon = createSalon("pcrc-elapsed-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-elapsed-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "CONFIRMED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("an elapsed-but-unclosed CONFIRMED booking must offer the provider-review CTA")
                .isTrue();
    }

    @Test
    @DisplayName("false — the booking is NOT_COMPLETED (no-show), even with an elapsed endsAt — "
            + "guards against over-widening the eligibility check beyond CONFIRMED")
    void should_returnFalse_when_bookingIsNotCompletedNoShow() throws Exception {
        Salon salon = createSalon("pcrc-noshow-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-noshow-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "NOT_COMPLETED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("NOT_COMPLETED is an explicit no-show marking, never reviewable regardless of endsAt")
                .isFalse();
    }

    @Test
    @DisplayName("false — guest (LINK, null-client) COMPLETED booking has no account to review")
    void should_returnFalse_when_bookingIsGuestWithNoClient() throws Exception {
        Salon salon = createSalon("pcrc-guest-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createSalonService(salon.salonId(), salon.masterId());
        UUID bookingId = insertGuestBooking(salon.masterId(), masterServiceId, salon.salonId(), "COMPLETED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(salon.ownerEmail()));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("a guest booking has no registered client account — never reviewable")
                .isFalse();
    }

    @Test
    @DisplayName("false — the CLIENT viewer of the same booking (and their own canReview is unaffected)")
    void should_returnFalseForClientViewer_whileCanReviewStaysUnaffected() throws Exception {
        Salon salon = createSalon("pcrc-client-owner-" + System.nanoTime() + "@beautica.test");
        String clientEmail = "pcrc-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = createUser(clientEmail, "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(clientEmail));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("CLIENT role must never be offered the provider-side client-review CTA")
                .isFalse();
        assertThat(detail.path("canReview").asBoolean())
                .as("the pre-existing CLIENT-side canReview field must be unaffected by this "
                        + "change — the client can still review the completed, unreviewed booking")
                .isTrue();
    }

    @Test
    @DisplayName("false — SALON_MASTER (read-only role) viewing their own salon's booking")
    void should_returnFalse_when_salonMasterViews() throws Exception {
        Salon salon = createSalon("pcrc-sm-owner-" + System.nanoTime() + "@beautica.test");
        UUID clientId = createUser("pcrc-sm-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(), createSalonService(salon.salonId(), salon.masterId()),
                salon.salonId(), "COMPLETED");

        JsonNode detail = getBookingDetail(bookingId, tokenFor(salon.masterEmail()));

        assertThat(detail.path("providerCanReviewClient").asBoolean())
                .as("SALON_MASTER is a read-only role and never admitted to provider-only actions "
                        + "like decline/complete/reschedule/review — same exclusion here")
                .isFalse();
    }

    /**
     * Also NOT the "false via the DTO" case the spec originally asked for — see
     * {@link #should_return403_when_assignedSalonAdminViewsBookingDetail}'s javadoc. Since
     * {@code enforceCanViewBooking} rejects {@code SALON_ADMIN} unconditionally by role, a
     * FOREIGN admin gets the exact same 403 an ASSIGNED admin gets, for the exact same
     * structural reason (never reaching {@code hasProviderAuthorityOverBooking} at all) — not a
     * distinguishable "wrong salon" 403 from the review-authority predicate. Kept as its own test
     * to pin that this really is a role-level exclusion, not a salon-scoped one: if
     * {@code enforceCanViewBooking} were ever widened to admit SOME admins, a foreign one must
     * still be denied by {@code hasProviderAuthorityOverBooking} — this test's fixture (two
     * salons, admin assigned to the wrong one) is what would catch a regression there.
     */
    @Test
    @DisplayName("403 (NOT the DTO) — a foreign SALON_ADMIN assigned to a DIFFERENT salon is also "
            + "denied at the view gate, for the same role-level reason as an assigned admin")
    void should_return403_when_foreignSalonAdminViewsBookingDetail() throws Exception {
        Salon salonA = createSalon("pcrc-foreign-a-" + System.nanoTime() + "@beautica.test");
        Salon salonB = createSalon("pcrc-foreign-b-" + System.nanoTime() + "@beautica.test");
        String foreignAdminEmail = "pcrc-foreign-admin-" + System.nanoTime() + "@beautica.test";
        createUser(foreignAdminEmail, "SALON_ADMIN", salonB.salonId()); // assigned to B, not A
        UUID clientId = createUser("pcrc-foreign-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salonA.masterId(), createSalonService(salonA.salonId(), salonA.masterId()),
                salonA.salonId(), "COMPLETED");

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(tokenFor(foreignAdminEmail))), String.class);

        assertThat(resp.getStatusCode())
                .as("a SALON_ADMIN assigned to a different salon must be denied — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────

    private String tokenFor(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login must succeed for %s", email).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private JsonNode getBookingDetail(UUID bookingId, String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
        assertThat(resp.getStatusCode())
                .as("GET /bookings/{id} must succeed for this fixture, body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).get("data");
    }

    // ── seeding fixtures (local by house convention — mirrors ClientReviewIT / ────
    // ── BookingProviderCancelAuthorizationIT) ─────────────────────────────────────

    private record Salon(UUID salonId, String ownerEmail, UUID masterId, String masterEmail) {}

    private Salon createSalon(String ownerEmail) {
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);

        String masterEmail = "pcrc-master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return new Salon(salonId, ownerEmail, masterId, masterEmail);
    }

    private UUID createIndependentMaster(String email) {
        UUID userId = createUser(email, "INDEPENDENT_MASTER", null);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
    }

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    private UUID createSalonService(UUID salonId, UUID masterId) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID createIndependentMasterService(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId, String status) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status);
        return bookingId;
    }

    /** Same shape as {@link #insertBooking}, but with a FUTURE starts_at/ends_at — for the
     * still-open, not-yet-elapsed CONFIRMED case that must stay unreviewable. */
    private UUID insertFutureBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId, String status) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW() + interval '1 hour', NOW() + interval '2 hours', "
                        + "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status);
        return bookingId;
    }

    /** Guest (LINK) booking, client_id NULL — mirrors ClientReviewIT/GuestBookingLifecycleContractIT's shape. */
    private UUID insertGuestBooking(UUID masterId, UUID masterServiceId, UUID salonId, String status) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, 'LINK', 'Гість', 'Тестовий', '+380509998877', NOW(), NOW())",
                bookingId, masterId, masterServiceId, salonId, status);
        return bookingId;
    }
}
