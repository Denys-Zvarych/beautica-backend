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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.net.URI;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 354 — {@code GET /bookings/me?asMaster=true} and {@code GET /bookings/me/booked-days?asMaster=true}
 * over the full HTTP stack against real Postgres.
 *
 * <p>Fixture: one salon whose OWNER also performs services (a {@code SALON_OWNER}-type master
 * row) plus one invited {@code SALON_MASTER}. The owner row carries an upcoming booking on service
 * A and a past COMPLETED booking on service B; the invited master carries one upcoming booking.
 * Without the flag the owner keeps the salon-wide view (regression pin); with it the owner sees
 * exactly their own master-row bookings, filtered by {@code partition}/{@code serviceId} the way
 * an {@code INDEPENDENT_MASTER} list is.
 */
@Import(TestSecurityConfig.class)
@DisplayName("GET /bookings/me?asMaster=true — Phase 354 owner-as-master own-bookings scope")
class OwnerAsMasterBookingsIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final LocalDate JULY_FIRST = LocalDate.of(2031, 7, 1);
    private static final LocalDate JULY_LAST = LocalDate.of(2031, 7, 31);
    private static final LocalDate OWNER_DAY = LocalDate.of(2031, 7, 10);
    private static final LocalDate INVITED_DAY = LocalDate.of(2031, 7, 12);

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @LocalServerPort
    private int port;

    private BookingTestFixtures fixtures;
    private Seed seed;

    private record Seed(
            BookingTestFixtures.SalonFixture salon,
            UUID ownerServiceA, UUID ownerServiceB, UUID invitedServiceId,
            UUID ownerUpcomingId, UUID ownerPastId, UUID invitedUpcomingId,
            String clientEmail) {}

    @BeforeEach
    void seed() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        String ownerEmail = "oamb-owner-" + System.nanoTime() + "@beautica.test";
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon(ownerEmail);
        UUID ownerUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", UUID.class, ownerEmail);
        UUID ownerMasterId = fixtures.createOwnerAsMaster(salon.salonId(), ownerUserId);

        String clientEmail = "oamb-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);

        UUID ownerServiceA = fixtures.createSalonService(salon.salonId(), ownerMasterId);
        UUID ownerServiceB = fixtures.createSalonService(salon.salonId(), ownerMasterId);
        UUID invitedServiceId = fixtures.createSalonService(salon.salonId(), salon.masterId());

        UUID ownerUpcoming = insertBooking(clientId, ownerMasterId, ownerServiceA, salon.salonId(),
                kyiv(OWNER_DAY, 12), "CONFIRMED");
        UUID ownerPast = insertBooking(clientId, ownerMasterId, ownerServiceB, salon.salonId(),
                kyiv(LocalDate.of(2020, 3, 5), 12), "COMPLETED");
        UUID invitedUpcoming = insertBooking(clientId, salon.masterId(), invitedServiceId, salon.salonId(),
                kyiv(INVITED_DAY, 12), "CONFIRMED");

        seed = new Seed(salon, ownerServiceA, ownerServiceB, invitedServiceId,
                ownerUpcoming, ownerPast, invitedUpcoming, clientEmail);
    }

    @Test
    @DisplayName("owner without asMaster keeps the salon-wide view — every booking in the salon (regression pin)")
    void should_returnAllSalonBookings_when_ownerListsWithoutAsMaster() throws Exception {
        ResponseEntity<String> resp = get(ownerToken(), "/me");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(resp)).containsExactlyInAnyOrder(
                seed.ownerUpcomingId(), seed.ownerPastId(), seed.invitedUpcomingId());
    }

    @Test
    @DisplayName("owner with asMaster=false is identical to the flag being absent")
    void should_returnAllSalonBookings_when_ownerListsWithAsMasterFalse() throws Exception {
        ResponseEntity<String> resp = get(ownerToken(), "/me?asMaster=false");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(resp)).containsExactlyInAnyOrder(
                seed.ownerUpcomingId(), seed.ownerPastId(), seed.invitedUpcomingId());
    }

    @Test
    @DisplayName("owner with asMaster=true sees only their own master-row bookings")
    void should_returnOnlyOwnRowBookings_when_ownerListsAsMaster() throws Exception {
        ResponseEntity<String> resp = get(ownerToken(), "/me?asMaster=true");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(resp)).containsExactlyInAnyOrder(seed.ownerUpcomingId(), seed.ownerPastId());
    }

    @Test
    @DisplayName("owner with asMaster=true&partition=UPCOMING sees only the own upcoming booking")
    void should_returnOwnUpcoming_when_ownerListsAsMasterWithUpcomingPartition() throws Exception {
        ResponseEntity<String> resp = get(ownerToken(), "/me?asMaster=true&partition=UPCOMING");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(resp)).containsExactly(seed.ownerUpcomingId());
    }

    @Test
    @DisplayName("owner with asMaster=true&partition=PAST sees only the own past booking")
    void should_returnOwnPast_when_ownerListsAsMasterWithPastPartition() throws Exception {
        ResponseEntity<String> resp = get(ownerToken(), "/me?asMaster=true&partition=PAST");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(resp)).containsExactly(seed.ownerPastId());
    }

    @Test
    @DisplayName("owner with asMaster=true&serviceId= narrows to that own service only")
    void should_filterByService_when_ownerListsAsMasterWithServiceId() throws Exception {
        ResponseEntity<String> resp = get(ownerToken(), "/me?asMaster=true&serviceId=" + seed.ownerServiceB());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(resp)).containsExactly(seed.ownerPastId());
    }

    @Test
    @DisplayName("owner with asMaster=true&serviceId=<invited master's service> matches nothing")
    void should_returnEmpty_when_ownerListsAsMasterWithForeignServiceId() throws Exception {
        ResponseEntity<String> resp = get(ownerToken(), "/me?asMaster=true&serviceId=" + seed.invitedServiceId());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(resp)).isEmpty();
    }

    @Test
    @DisplayName("owner booked-days with asMaster=true dots only the own-row day")
    void should_returnOnlyOwnRowDay_when_ownerRequestsBookedDaysAsMaster() throws Exception {
        String range = "/me/booked-days?from=" + JULY_FIRST + "&to=" + JULY_LAST;

        ResponseEntity<String> resp = get(ownerToken(), range + "&asMaster=true");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(days(resp)).containsExactly(OWNER_DAY);
    }

    @Test
    @DisplayName("owner booked-days without asMaster dots every owned salon's days (pre-354 behaviour)")
    void should_returnAllSalonDays_when_ownerRequestsBookedDaysWithoutAsMaster() throws Exception {
        String range = "/me/booked-days?from=" + JULY_FIRST + "&to=" + JULY_LAST;

        ResponseEntity<String> resp = get(ownerToken(), range);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(days(resp)).containsExactly(OWNER_DAY, INVITED_DAY);
    }

    @Test
    @DisplayName("invited SALON_MASTER with asMaster=true still sees only their own booking (no-op)")
    void should_returnOwnBookingOnly_when_invitedMasterListsAsMaster() throws Exception {
        String token = fixtures.tokenFor(seed.salon().masterEmail());

        ResponseEntity<String> resp = get(token, "/me?asMaster=true");
        ResponseEntity<String> bookedDays = get(token,
                "/me/booked-days?from=" + JULY_FIRST + "&to=" + JULY_LAST + "&asMaster=true");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ids(resp)).containsExactly(seed.invitedUpcomingId());
        assertThat(days(bookedDays)).containsExactly(INVITED_DAY);
    }

    @Test
    @DisplayName("CLIENT with asMaster=true is a 400 on both endpoints")
    void should_return400_when_clientUsesAsMaster() throws Exception {
        String token = fixtures.tokenFor(seed.clientEmail());

        ResponseEntity<String> list = get(token, "/me?asMaster=true");
        ResponseEntity<String> bookedDays = get(token,
                "/me/booked-days?from=" + JULY_FIRST + "&to=" + JULY_LAST + "&asMaster=true");

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(bookedDays.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("SALON_ADMIN with asMaster=true keeps the existing 403 on both endpoints")
    void should_return403_when_salonAdminUsesAsMaster() throws Exception {
        String adminEmail = "oamb-admin-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(adminEmail, "SALON_ADMIN", seed.salon().salonId());
        String token = fixtures.tokenFor(adminEmail);

        ResponseEntity<String> list = get(token, "/me?asMaster=true");
        ResponseEntity<String> bookedDays = get(token,
                "/me/booked-days?from=" + JULY_FIRST + "&to=" + JULY_LAST + "&asMaster=true");

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bookedDays.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("owner with asMaster=true and a DEACTIVATED own master row gets 403")
    void should_return403_when_ownerMasterRowInactive() throws Exception {
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE master_type = 'SALON_OWNER' AND salon_id = ?",
                seed.salon().salonId());

        ResponseEntity<String> resp = get(ownerToken(), "/me?asMaster=true");
        ResponseEntity<String> bookedDays = get(ownerToken(),
                "/me/booked-days?from=" + JULY_FIRST + "&to=" + JULY_LAST + "&asMaster=true");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bookedDays.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("owner with NO master row at all gets 404 on both endpoints with asMaster=true — never a "
            + "fallback to the salon-wide view")
    void should_return404_when_ownerHasNoMasterRowAndAsMaster() throws Exception {
        // A second salon whose owner never got a SALON_OWNER-type master row.
        String bareOwnerEmail = "oamb-bare-owner-" + System.nanoTime() + "@beautica.test";
        fixtures.createSalon(bareOwnerEmail);
        String token = fixtures.tokenFor(bareOwnerEmail);

        ResponseEntity<String> list = get(token, "/me?asMaster=true");
        ResponseEntity<String> bookedDays = get(token,
                "/me/booked-days?from=" + JULY_FIRST + "&to=" + JULY_LAST + "&asMaster=true");

        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(objectMapper.readTree(list.getBody()).path("message").asText())
                .as("404 body is the redacted generic envelope, body=%s", list.getBody())
                .isEqualTo("Resource not found");
        assertThat(bookedDays.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("after DELETE /salons/{id} the owner's own master row is deactivated too, so asMaster=true is "
            + "403 — pins why the master view may skip the default view's salon isActive filter")
    void should_return403_when_ownerListsAsMasterAfterSalonDeactivated() throws Exception {
        String token = ownerToken();
        ResponseEntity<Void> delete = restTemplate.exchange(
                URI.create("http://localhost:" + port + "/api/v1/salons/" + seed.salon().salonId()),
                HttpMethod.DELETE, new HttpEntity<>(fixtures.bearerHeaders(token)), Void.class);
        assertThat(delete.getStatusCode().is2xxSuccessful())
                .as("salon deactivation precondition, status=%s", delete.getStatusCode())
                .isTrue();

        ResponseEntity<String> list = get(token, "/me?asMaster=true");
        ResponseEntity<String> bookedDays = get(token,
                "/me/booked-days?from=" + JULY_FIRST + "&to=" + JULY_LAST + "&asMaster=true");

        Boolean ownerRowActive = jdbcTemplate.queryForObject(
                "SELECT is_active FROM masters WHERE id = (SELECT master_id FROM bookings WHERE id = ?)",
                Boolean.class, seed.ownerUpcomingId());
        assertThat(ownerRowActive).as("owner's SALON_OWNER master row is_active after salon delete").isFalse();
        assertThat(list.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(bookedDays.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("CLIENT 400 is the standard redacted error envelope (success=false, generic message)")
    void should_return400WithMessage_when_clientListsAsMaster() throws Exception {
        ResponseEntity<String> resp = get(fixtures.tokenFor(seed.clientEmail()), "/me?asMaster=true");

        JsonNode body = objectMapper.readTree(resp.getBody());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(body.path("success").asBoolean(true)).as("success flag, body=%s", resp.getBody()).isFalse();
        assertThat(body.path("message").asText()).isEqualTo("Invalid request");
    }

    @Test
    @DisplayName("a non-boolean asMaster value is a 400, not a 500 or a silent salon-wide list")
    void should_return400_when_asMasterIsNotBoolean() throws Exception {
        ResponseEntity<String> resp = get(ownerToken(), "/me?asMaster=maybe");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private String ownerToken() throws Exception {
        return fixtures.tokenFor(seed.salon().ownerEmail());
    }

    private ResponseEntity<String> get(String token, String pathAndQuery) {
        URI uri = URI.create("http://localhost:" + port + BOOKINGS_URL + pathAndQuery);
        return restTemplate.exchange(uri, HttpMethod.GET, new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
    }

    private List<UUID> ids(ResponseEntity<String> resp) throws Exception {
        return fixtures.extractIds(objectMapper.readTree(resp.getBody()));
    }

    private List<LocalDate> days(ResponseEntity<String> resp) throws Exception {
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        List<LocalDate> result = new ArrayList<>();
        data.forEach(node -> result.add(LocalDate.parse(node.asText())));
        return result;
    }

    private UUID insertBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId,
                               OffsetDateTime startsAt, String status) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId, status,
                startsAt, startsAt.plusMinutes(60));
        return bookingId;
    }

    private static OffsetDateTime kyiv(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(TimeZones.KYIV).toOffsetDateTime();
    }
}
