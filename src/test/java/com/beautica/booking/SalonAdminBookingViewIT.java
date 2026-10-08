package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.TimeZones;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 356 — an ASSIGNED, ACTIVE {@code SALON_ADMIN} may read {@code GET /bookings/{id}} for a
 * booking whose performing master's LIVE salon is the admin's salon. Every other actor keeps the
 * pre-existing answer.
 */
@Import(TestSecurityConfig.class)
@DisplayName("SALON_ADMIN views a booking of their salon (phase 356)")
class SalonAdminBookingViewIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String CLIENT_REVIEWS_URL = "/api/v1/client-reviews";

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
    @DisplayName("assigned admin, salon master's booking: 200 and the body equals the board row")
    void should_return200AndBoardRow_when_assignedAdminViewsSalonMasterBooking() throws Exception {
        var salon = fx.createSalon("sabv-1-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "sabv-1-admin-" + System.nanoTime() + "@beautica.test";
        fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID bookingId = insertBooking(salon, "COMPLETED");
        String token = fx.tokenFor(adminEmail);

        ResponseEntity<String> detail = get(BOOKINGS_URL + "/" + bookingId, token);
        JsonNode board = boardRow(salon.salonId(), bookingId, token);

        assertThat(detail.getStatusCode()).as("body=%s", detail.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(detail.getBody()).path("data");
        assertThat(data.path("id").asText()).isEqualTo(bookingId.toString());
        assertThat(data.path("providerCanReviewClient").asBoolean())
                .isEqualTo(board.path("providerCanReviewClient").asBoolean());
        assertThat(data.path("status").asText()).isEqualTo(board.path("status").asText());
        assertThat(data.path("clientName").asText()).isEqualTo(board.path("clientName").asText());
    }

    @Test
    @DisplayName("assigned admin, owner-performed booking at their salon: 200")
    void should_return200_when_assignedAdminViewsOwnerPerformedBooking() throws Exception {
        var salon = fx.createSalon("sabv-2-owner-" + System.nanoTime() + "@beautica.test");
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salon.salonId());
        UUID ownerMasterId = fx.createOwnerAsMaster(salon.salonId(), ownerId);
        String adminEmail = "sabv-2-admin-" + System.nanoTime() + "@beautica.test";
        fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID clientId = fx.createUser("sabv-2-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, ownerMasterId,
                fx.createSalonService(salon.salonId(), ownerMasterId), salon.salonId(), "CONFIRMED");

        assertThat(status(BOOKINGS_URL + "/" + bookingId, fx.tokenFor(adminEmail))).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("admin of salon A, booking at salon B: 403")
    void should_return403_when_adminOfAnotherSalonViewsBooking() throws Exception {
        var salonA = fx.createSalon("sabv-3-a-" + System.nanoTime() + "@beautica.test");
        var salonB = fx.createSalon("sabv-3-b-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "sabv-3-admin-" + System.nanoTime() + "@beautica.test";
        fx.createUser(adminEmail, "SALON_ADMIN", salonA.salonId());
        UUID bookingId = insertBooking(salonB, "CONFIRMED");

        assertThat(status(BOOKINGS_URL + "/" + bookingId, fx.tokenFor(adminEmail)))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("admin removed from the salon (live token): 403")
    void should_return403_when_adminIsRemovedFromSalon() throws Exception {
        var salon = fx.createSalon("sabv-4-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "sabv-4-admin-" + System.nanoTime() + "@beautica.test";
        UUID adminId = fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID bookingId = insertBooking(salon, "CONFIRMED");
        String token = fx.tokenFor(adminEmail);
        assertThat(status(BOOKINGS_URL + "/" + bookingId, token)).as("control").isEqualTo(HttpStatus.OK);

        jdbcTemplate.update("UPDATE users SET salon_id = NULL WHERE id = ?", adminId);

        assertThat(status(BOOKINGS_URL + "/" + bookingId, token)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("deactivated admin (live token): 403")
    void should_return403_when_adminIsDeactivated() throws Exception {
        var salon = fx.createSalon("sabv-4b-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "sabv-4b-admin-" + System.nanoTime() + "@beautica.test";
        UUID adminId = fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID bookingId = insertBooking(salon, "CONFIRMED");
        String token = fx.tokenFor(adminEmail);

        jdbcTemplate.update("UPDATE users SET is_active = false WHERE id = ?", adminId);

        assertThat(status(BOOKINGS_URL + "/" + bookingId, token)).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("inactive performing master: 403 for the admin, 200 for the owner")
    void should_return403_when_performingMasterIsInactive() throws Exception {
        var salon = fx.createSalon("sabv-5-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "sabv-5-admin-" + System.nanoTime() + "@beautica.test";
        fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID bookingId = insertBooking(salon, "CONFIRMED");
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", salon.masterId());

        HttpStatus admin = status(BOOKINGS_URL + "/" + bookingId, fx.tokenFor(adminEmail));
        HttpStatus owner = status(BOOKINGS_URL + "/" + bookingId, fx.tokenFor(salon.ownerEmail()));

        assertThat(admin).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(owner).as("owner keeps view").isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("rotated master: the old salon's admin is denied, the new salon's admin is denied on old-salon history")
    void should_return403_when_adminOfNewSalonReadsRotatedMastersOldSalonBooking() throws Exception {
        var oldSalon = fx.createSalon("sabv-rot-old-" + System.nanoTime() + "@beautica.test");
        var newSalon = fx.createSalon("sabv-rot-new-" + System.nanoTime() + "@beautica.test");
        String oldAdmin = "sabv-rot-oadm-" + System.nanoTime() + "@beautica.test";
        String newAdmin = "sabv-rot-nadm-" + System.nanoTime() + "@beautica.test";
        fx.createUser(oldAdmin, "SALON_ADMIN", oldSalon.salonId());
        fx.createUser(newAdmin, "SALON_ADMIN", newSalon.salonId());
        UUID bookingId = insertBooking(oldSalon, "CONFIRMED");
        assertThat(status(BOOKINGS_URL + "/" + bookingId, fx.tokenFor(oldAdmin))).as("control").isEqualTo(HttpStatus.OK);

        jdbcTemplate.update("UPDATE masters SET salon_id = ? WHERE id = ?", newSalon.salonId(), oldSalon.masterId());

        assertThat(status(BOOKINGS_URL + "/" + bookingId, fx.tokenFor(oldAdmin))).as("old admin")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(BOOKINGS_URL + "/" + bookingId, fx.tokenFor(newAdmin))).as("new admin")
                .isEqualTo(HttpStatus.FORBIDDEN);
        // The new salon's board never lists the old booking, so no rating flag is reachable either.
        assertThat(get(BOOKINGS_URL + "/salon/" + newSalon.salonId() + "?size=50", fx.tokenFor(newAdmin)).getBody())
                .doesNotContain(bookingId.toString());
    }

    @Test
    @DisplayName("COMPLETED unrated: flag true for the admin, false after the admin rates the client")
    void should_flipFlag_when_adminRatesTheClient() throws Exception {
        var salon = fx.createSalon("sabv-6-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "sabv-6-admin-" + System.nanoTime() + "@beautica.test";
        fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID bookingId = insertBooking(salon, "COMPLETED");
        String token = fx.tokenFor(adminEmail);

        boolean before = detailFlag(bookingId, token);
        ResponseEntity<String> rate = restTemplate.exchange(
                CLIENT_REVIEWS_URL, HttpMethod.POST,
                new HttpEntity<>(objectMapper.writeValueAsString(
                        Map.of("bookingId", bookingId.toString(), "rating", 5)), fx.bearerHeaders(token)),
                String.class);
        boolean after = detailFlag(bookingId, token);

        assertThat(before).as("before rating").isTrue();
        assertThat(rate.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(after).as("after rating").isFalse();
    }

    @Test
    @DisplayName("salon master cannot see another master's booking at the same salon: 403")
    void should_return403_when_salonMasterViewsAnotherMastersBooking() throws Exception {
        var salon = fx.createSalon("sabv-7-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertBooking(salon, "CONFIRMED");
        String otherMasterEmail = "sabv-7-other-" + System.nanoTime() + "@beautica.test";
        UUID otherUserId = fx.createUser(otherMasterEmail, "SALON_MASTER", salon.salonId());
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                UUID.randomUUID(), otherUserId, salon.salonId());

        assertThat(status(BOOKINGS_URL + "/" + bookingId, fx.tokenFor(otherMasterEmail)))
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(status(BOOKINGS_URL + "/" + bookingId, fx.tokenFor(salon.masterEmail())))
                .as("performing master control").isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("owning client unchanged: 200; an unrelated client: 403; admin's /bookings/me stays 403")
    void should_keepClientAndMeBehaviour_unchanged() throws Exception {
        var salon = fx.createSalon("sabv-8-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "sabv-8-admin-" + System.nanoTime() + "@beautica.test";
        fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        String clientEmail = "sabv-8-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fx.createUser(clientEmail, "CLIENT", null);
        String strangerEmail = "sabv-8-stranger-" + System.nanoTime() + "@beautica.test";
        fx.createUser(strangerEmail, "CLIENT", null);
        UUID bookingId = insertBooking(clientId, salon.masterId(),
                fx.createSalonService(salon.salonId(), salon.masterId()), salon.salonId(), "CONFIRMED");

        HttpStatus owning = status(BOOKINGS_URL + "/" + bookingId, fx.tokenFor(clientEmail));
        HttpStatus stranger = status(BOOKINGS_URL + "/" + bookingId, fx.tokenFor(strangerEmail));
        ResponseEntity<String> me = get(BOOKINGS_URL + "/me?size=50", fx.tokenFor(adminEmail));

        assertThat(owning).isEqualTo(HttpStatus.OK);
        assertThat(stranger).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(me.getStatusCode()).as("admin has no personal bookings list; body=%s", me.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("GET /appointments/{id}: assigned admin of the visit's salon 200, admin of another salon 403, outsider client 403")
    void should_gateAppointmentRead_byAdminsSalon() throws Exception {
        var salon = fx.createSalon("sabv-ap-owner-" + System.nanoTime() + "@beautica.test");
        var otherSalon = fx.createSalon("sabv-ap-other-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "sabv-ap-admin-" + System.nanoTime() + "@beautica.test";
        String otherAdminEmail = "sabv-ap-oadm-" + System.nanoTime() + "@beautica.test";
        fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        fx.createUser(otherAdminEmail, "SALON_ADMIN", otherSalon.salonId());
        UUID serviceId = fx.createSalonService(salon.salonId(), salon.masterId());
        fx.addWorkingHoursForEveryDay(salon.masterId());
        String clientEmail = "sabv-ap-client-" + System.nanoTime() + "@beautica.test";
        fx.createUser(clientEmail, "CLIENT", null);
        String clientToken = fx.tokenFor(clientEmail);

        ResponseEntity<String> created = postVisit(clientToken, salon.masterId(), List.of(serviceId));
        assertThat(created.getStatusCode()).as("body=%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        String appointmentId = objectMapper.readTree(created.getBody()).path("data").path("id").asText();
        String url = "/api/v1/appointments/" + appointmentId;

        ResponseEntity<String> admin = get(url, fx.tokenFor(adminEmail));
        HttpStatus otherAdmin = status(url, fx.tokenFor(otherAdminEmail));
        HttpStatus owningClient = status(url, clientToken);

        assertThat(admin.getStatusCode()).as("body=%s", admin.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(admin.getBody()).path("data").path("id").asText()).isEqualTo(appointmentId);
        assertThat(otherAdmin).as("admin of another salon").isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(owningClient).as("owning client control").isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("GET /appointments/{id}: multi-item visit (2 services, same master) gives the salon's admin 200 with all items, another salon's admin 403")
    void should_returnAllItemsToAdmin_when_visitHasMultipleServices() throws Exception {
        var salon = fx.createSalon("sabv-mi-owner-" + System.nanoTime() + "@beautica.test");
        var otherSalon = fx.createSalon("sabv-mi-other-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "sabv-mi-admin-" + System.nanoTime() + "@beautica.test";
        String otherAdminEmail = "sabv-mi-oadm-" + System.nanoTime() + "@beautica.test";
        fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        fx.createUser(otherAdminEmail, "SALON_ADMIN", otherSalon.salonId());
        UUID serviceA = fx.createSalonService(salon.salonId(), salon.masterId());
        UUID serviceB = fx.createSalonService(salon.salonId(), salon.masterId());
        fx.addWorkingHoursForEveryDay(salon.masterId());
        String clientEmail = "sabv-mi-client-" + System.nanoTime() + "@beautica.test";
        fx.createUser(clientEmail, "CLIENT", null);

        ResponseEntity<String> created = postVisit(fx.tokenFor(clientEmail), salon.masterId(), List.of(serviceA, serviceB));
        assertThat(created.getStatusCode()).as("body=%s", created.getBody()).isEqualTo(HttpStatus.CREATED);
        String appointmentId = objectMapper.readTree(created.getBody()).path("data").path("id").asText();
        String url = "/api/v1/appointments/" + appointmentId;

        ResponseEntity<String> admin = get(url, fx.tokenFor(adminEmail));
        HttpStatus otherAdmin = status(url, fx.tokenFor(otherAdminEmail));

        assertThat(admin.getStatusCode()).as("body=%s", admin.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode items = objectMapper.readTree(admin.getBody()).path("data").path("items");
        assertThat(items).as("all items returned").hasSize(2);
        assertThat(java.util.stream.StreamSupport.stream(items.spliterator(), false)
                .map(i -> i.path("masterServiceId").asText()).toList())
                .as("items=%s", items).containsExactlyInAnyOrder(serviceA.toString(), serviceB.toString());
        assertThat(otherAdmin).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("visit is single-master: a service of another master is rejected and no appointment/booking row is written")
    void should_rejectMixedMasterVisit_andPersistNothing() throws Exception {
        var salon = fx.createSalon("sabv-mm-owner-" + System.nanoTime() + "@beautica.test");
        UUID ownServiceId = fx.createSalonService(salon.salonId(), salon.masterId());
        UUID foreignMasterId = fx.createIndependentMaster("sabv-mm-ind-" + System.nanoTime() + "@beautica.test");
        UUID foreignServiceId = fx.createIndependentMasterService(foreignMasterId);
        fx.addWorkingHoursForEveryDay(salon.masterId());
        String clientEmail = "sabv-mm-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fx.createUser(clientEmail, "CLIENT", null);

        ResponseEntity<String> resp = rawPostVisit(
                fx.tokenFor(clientEmail), salon.masterId(), List.of(ownServiceId, foreignServiceId));

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(objectMapper.readTree(resp.getBody()).path("message").asText())
                .as("sanitised not-found body (detail deliberately not echoed), body=%s", resp.getBody())
                .isEqualTo("Resource not found");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM bookings WHERE client_id = ?", Integer.class, clientId))
                .as("no booking rows persisted").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM appointments WHERE client_id = ?", Integer.class, clientId))
                .as("no appointment row persisted").isZero();
    }

    @Test
    @DisplayName("admin reading an independent master's booking (no salon): 403")
    void should_return403_when_adminReadsIndependentMastersBooking() throws Exception {
        var salon = fx.createSalon("sabv-ind-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "sabv-ind-admin-" + System.nanoTime() + "@beautica.test";
        fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID masterId = fx.createIndependentMaster("sabv-ind-m-" + System.nanoTime() + "@beautica.test");
        UUID serviceId = fx.createIndependentMasterService(masterId);
        UUID clientId = fx.createUser("sabv-ind-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingId = insertBooking(clientId, masterId, serviceId, null, "CONFIRMED");

        assertThat(status(BOOKINGS_URL + "/" + bookingId, fx.tokenFor(adminEmail)))
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("guest (no client) salon booking: 200 for the admin, guest name shown, no client id, no phone leaked")
    void should_return200WithGuestFieldsOnly_when_adminViewsGuestBooking() throws Exception {
        var salon = fx.createSalon("sabv-g-owner-" + System.nanoTime() + "@beautica.test");
        String adminEmail = "sabv-g-admin-" + System.nanoTime() + "@beautica.test";
        fx.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID serviceId = fx.createSalonService(salon.salonId(), salon.masterId());
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, guest_name, guest_surname, guest_phone, "
                        + "created_at, updated_at) "
                        + "VALUES (?, NULL, ?, ?, ?, 'COMPLETED', NOW() - interval '2 hours', "
                        + "NOW() - interval '1 hour', 500.00, 60, 0, 'LINK', 'Guesta', 'Visitor', '+380501234567', "
                        + "NOW(), NOW())",
                bookingId, salon.masterId(), serviceId, salon.salonId());

        ResponseEntity<String> resp = get(BOOKINGS_URL + "/" + bookingId, fx.tokenFor(adminEmail));

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        assertThat(data.path("clientId").isNull()).as("clientId=%s", data.path("clientId")).isTrue();
        assertThat(data.path("clientFirstName").asText()).isEqualTo("Guesta");
        assertThat(data.path("clientLastName").asText()).isEqualTo("Visitor");
        assertThat(data.path("providerCanReviewClient").asBoolean()).as("guest is not rateable").isFalse();
        assertThat(resp.getBody()).doesNotContain("+380501234567");
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private ResponseEntity<String> rawPostVisit(String token, UUID masterId, List<UUID> serviceIds) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "masterId", masterId.toString(),
                "masterServiceIds", serviceIds.stream().map(UUID::toString).toList(),
                "startsAt", ZonedDateTime.now(TimeZones.KYIV).plusDays(2).withHour(10).withMinute(0)
                        .withSecond(0).withNano(0).toOffsetDateTime().toString()));
        HttpHeaders headers = fx.bearerHeaders(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange("/api/v1/appointments", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> postVisit(String token, UUID masterId, List<UUID> serviceIds) throws Exception {
        return rawPostVisit(token, masterId, serviceIds);
    }

    private ResponseEntity<String> get(String url, String token) {
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(fx.bearerHeaders(token)), String.class);
    }

    private HttpStatus status(String url, String token) {
        return HttpStatus.valueOf(get(url, token).getStatusCode().value());
    }

    private boolean detailFlag(UUID bookingId, String token) throws Exception {
        ResponseEntity<String> resp = get(BOOKINGS_URL + "/" + bookingId, token);
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data").path("providerCanReviewClient").asBoolean();
    }

    private JsonNode boardRow(UUID salonId, UUID bookingId, String token) throws Exception {
        ResponseEntity<String> resp = get(BOOKINGS_URL + "/salon/" + salonId + "?size=50", token);
        assertThat(resp.getStatusCode()).as("board body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        for (JsonNode row : objectMapper.readTree(resp.getBody()).path("data").path("data")) {
            if (bookingId.toString().equals(row.path("id").asText())) {
                return row;
            }
        }
        throw new AssertionError("booking " + bookingId + " absent from the salon board");
    }

    private UUID insertBooking(BookingTestFixtures.SalonFixture salon, String status) {
        UUID clientId = fx.createUser("sabv-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        return insertBooking(clientId, salon.masterId(),
                fx.createSalonService(salon.salonId(), salon.masterId()), salon.salonId(), status);
    }

    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId, String status) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW() - interval '2 hours', "
                        + "NOW() - interval '1 hour', 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status);
        return bookingId;
    }
}
