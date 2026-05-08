package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationOutboxService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
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

import com.beautica.booking.repository.BookingRepository;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestSecurityConfig.class)
@DisplayName("Booking — full-flow integration")
class BookingIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BookingIntegrationTest.class);
    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String TEST_PASSWORD = "password123";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BookingRepository bookingRepository;

    @MockBean
    private NotificationOutboxService notificationOutboxService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @Test
    @DisplayName("POST /bookings — 201 with booking ID when client submits a valid request")
    void should_createBooking_and_return201_when_clientSubmitsValidRequest() throws Exception {
        String clientToken = createClientAndGetToken("integ-client-create-" + System.nanoTime() + "@beautica.test");
        UUID masterId = createSalonOwnerSalonAndMaster("integ-owner-create-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        var request = new CreateBookingRequest(masterId, masterServiceId, startsAt, null, null);

        log.debug("Act: POST {} with valid CLIENT token — must return 201", BOOKINGS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(clientToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("valid booking creation must return 201")
                .isEqualTo(HttpStatus.CREATED);

        var body = objectMapper.readValue(response.getBody(), new TypeReference<ApiResponse<BookingResponse>>() {});
        assertThat(body.success()).isTrue();
        assertThat(body.data().id()).isNotNull();
        assertThat(body.data().masterId()).isEqualTo(masterId);
        assertThat(body.data().masterServiceId()).isEqualTo(masterServiceId);
    }

    @Test
    @DisplayName("POST /bookings — 401 when no Authorization header is present")
    void should_return401_when_noTokenOnCreateBooking() {
        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        var request = new CreateBookingRequest(UUID.randomUUID(), UUID.randomUUID(), startsAt, null, null);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        log.debug("Act: POST {} with no token — must return 401", BOOKINGS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, headers),
                String.class);

        assertThat(response.getStatusCode())
                .as("missing authorization must return 401")
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("POST /bookings — 403 when salon owner attempts to create a booking")
    void should_return403_when_ownerAttemptsToCreateBooking() throws Exception {
        String ownerEmail = "integ-owner-403-" + System.nanoTime() + "@beautica.test";
        createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);

        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        var request = new CreateBookingRequest(UUID.randomUUID(), UUID.randomUUID(), startsAt, null, null);

        log.debug("Act: POST {} with SALON_OWNER token — must return 403", BOOKINGS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(ownerToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("salon owner creating booking must return 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("PATCH /bookings/{id}/confirm — 204 when salon owner confirms a pending booking")
    void should_confirmBooking_and_return204_when_salonOwnerConfirms() throws Exception {
        String ownerEmail = "integ-owner-confirm-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);

        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("integ-client-confirm-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId);

        log.debug("Act: PATCH {}/{}/confirm with SALON_OWNER token — must return 204", BOOKINGS_URL, bookingId);
        ResponseEntity<Void> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/confirm", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                Void.class);

        assertThat(response.getStatusCode())
                .as("owner confirming booking must return 204, bookingId=%s", bookingId)
                .isEqualTo(HttpStatus.NO_CONTENT);

        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbStatus)
                .as("booking status in DB must be CONFIRMED after owner confirm, bookingId=%s", bookingId)
                .isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("PATCH /bookings/{id}/decline — PENDING booking transitions to DECLINED with reason stored in DB")
    void should_declineBooking_and_return204_when_pendingBookingDeclined() throws Exception {
        String ownerEmail = "integ-decline-owner-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("integ-decline-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId);

        String body = "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\"}";
        log.debug("Act: PATCH {}/{}/decline as SALON_OWNER — must return 204", BOOKINGS_URL, bookingId);
        ResponseEntity<Void> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(ownerToken)),
                Void.class);

        assertThat(response.getStatusCode())
                .as("owner declining PENDING booking must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);

        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbStatus)
                .as("booking status in DB must be DECLINED after decline")
                .isEqualTo("DECLINED");

        String dbReason = jdbcTemplate.queryForObject(
                "SELECT cancellation_reason FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbReason)
                .as("cancellation_reason must be stored as PROVIDER_UNAVAILABLE")
                .isEqualTo("PROVIDER_UNAVAILABLE");
    }

    @Test
    @DisplayName("PATCH /bookings/{id}/not-complete — CONFIRMED booking transitions to NOT_COMPLETED with reason stored in DB")
    void should_markNotCompleted_and_return204_when_confirmedBookingMarkedNotCompleted() throws Exception {
        String ownerEmail = "integ-notcomplete-owner-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("integ-notcomplete-client-" + System.nanoTime() + "@beautica.test");
        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(3).withHour(14).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId, startsAt);

        // First confirm the booking
        restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/confirm", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                Void.class);

        String dbStatusAfterConfirm = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbStatusAfterConfirm)
                .as("booking must be CONFIRMED before not-complete transition")
                .isEqualTo("CONFIRMED");

        String body = "{\"cancellationReason\":\"CLIENT_NO_SHOW\"}";
        log.debug("Act: PATCH {}/{}/not-complete as SALON_OWNER — must return 204", BOOKINGS_URL, bookingId);
        ResponseEntity<Void> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/not-complete", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(ownerToken)),
                Void.class);

        assertThat(response.getStatusCode())
                .as("owner marking CONFIRMED booking not-completed must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);

        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbStatus)
                .as("booking status in DB must be NOT_COMPLETED after not-complete transition")
                .isEqualTo("NOT_COMPLETED");

        String dbReason = jdbcTemplate.queryForObject(
                "SELECT cancellation_reason FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbReason)
                .as("cancellation_reason must be stored as CLIENT_NO_SHOW")
                .isEqualTo("CLIENT_NO_SHOW");
    }

    @Test
    @DisplayName("GET /masters/{masterId}/slots — booked slot absent when booking is PENDING")
    void should_excludeCreatedBookingFromSlots_when_bookingIsPending() throws Exception {
        String clientToken = createClientAndGetToken("integ-slots-client-" + System.nanoTime() + "@beautica.test");
        UUID masterId = createSalonOwnerSalonAndMaster("integ-slots-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        // Use a date at least 2 days ahead so the @Future constraint is satisfied and
        // the slot has never been cached (fresh date per test run).
        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(2).withHour(11).withMinute(0).withSecond(0).withNano(0);
        LocalDate slotDate = startsAt.toLocalDate();

        // Verify the slot is available before booking
        String slotsUrl = "/api/v1/masters/" + masterId + "/slots"
                + "?date=" + slotDate + "&serviceId=" + masterServiceId;
        ResponseEntity<String> beforeResponse = restTemplate.exchange(
                slotsUrl, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(clientToken)),
                String.class);
        assertThat(beforeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode beforeSlots = objectMapper.readTree(beforeResponse.getBody())
                .path("data").path("slots");
        assertThat(beforeSlots.isArray()).isTrue();
        boolean slotPresentBefore = false;
        for (JsonNode slot : beforeSlots) {
            if (slot.path("startsAt").asText().startsWith(startsAt.toOffsetDateTime().toString().substring(0, 16))) {
                slotPresentBefore = true;
                break;
            }
        }
        assertThat(slotPresentBefore)
                .as("slot at %s must be available before booking", startsAt)
                .isTrue();

        // Create the booking
        createBooking(clientToken, masterId, masterServiceId, startsAt);

        // Evict the cache entry so the next query hits the DB (same eviction the service performs)
        jdbcTemplate.execute("SELECT 1"); // no-op — cache eviction already happened via afterCommit hook

        // Fetch slots again — the booked slot must no longer appear
        ResponseEntity<String> afterResponse = restTemplate.exchange(
                slotsUrl, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(clientToken)),
                String.class);
        assertThat(afterResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode afterSlots = objectMapper.readTree(afterResponse.getBody())
                .path("data").path("slots");
        assertThat(afterSlots.isArray()).isTrue();
        for (JsonNode slot : afterSlots) {
            String slotStart = slot.path("startsAt").asText();
            assertThat(slotStart)
                    .as("booked slot at %s must not appear in available slots response", startsAt)
                    .doesNotStartWith(startsAt.toOffsetDateTime().toString().substring(0, 16));
        }
    }

    @Test
    @DisplayName("POST /bookings — 409 when same slot booked twice sequentially, DB has exactly 1 row")
    void should_return409_when_sameSlotBookedTwiceSequentially() throws Exception {
        String clientAToken = createClientAndGetToken("integ-conflict-a-" + System.nanoTime() + "@beautica.test");
        String clientBToken = createClientAndGetToken("integ-conflict-b-" + System.nanoTime() + "@beautica.test");
        UUID masterId = createSalonOwnerSalonAndMaster("integ-conflict-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(2).withHour(12).withMinute(0).withSecond(0).withNano(0);

        log.debug("Act: first booking by clientA at {}", startsAt);
        createBooking(clientAToken, masterId, masterServiceId, startsAt);

        log.debug("Act: second booking by clientB at same slot — must return 409");
        var request = new CreateBookingRequest(masterId, masterServiceId, startsAt, null, null);
        ResponseEntity<String> secondResponse = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(clientBToken)),
                String.class);

        assertThat(secondResponse.getStatusCode())
                .as("second booking for the same slot must return 409")
                .isEqualTo(HttpStatus.CONFLICT);

        JsonNode conflictBody = objectMapper.readTree(secondResponse.getBody());
        assertThat(conflictBody.path("success").asBoolean())
                .as("conflict response body must have success=false")
                .isFalse();
        String conflictMessage = conflictBody.path("message").asText("").toLowerCase();
        assertThat(conflictMessage)
                .as("conflict message must not expose internal SQL or stack details")
                .doesNotContainIgnoringCase("sql")
                .doesNotContainIgnoringCase("constraint")
                .doesNotContainIgnoringCase("duplicate key")
                .doesNotContainIgnoringCase("violat")
                .doesNotContainIgnoringCase("exception")
                .doesNotContainIgnoringCase("stack");

        long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings", Long.class);
        assertThat(count)
                .as("exactly one booking row must exist in the database after a conflict")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("PATCH /bookings/{id}/confirm and /complete — full PENDING→CONFIRMED→COMPLETED flow with DB assertions")
    void should_confirmAndCompleteBooking_when_fullStatusFlow() throws Exception {
        String ownerEmail = "integ-flow-owner-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("integ-flow-client-" + System.nanoTime() + "@beautica.test");
        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(2).withHour(13).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId, startsAt);

        // Step 2: SALON_OWNER confirms — expect 204
        log.debug("Act: PATCH {}/{}/confirm as SALON_OWNER", BOOKINGS_URL, bookingId);
        ResponseEntity<Void> confirmResponse = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/confirm", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                Void.class);
        assertThat(confirmResponse.getStatusCode())
                .as("confirm must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Step 3: DB must show CONFIRMED
        String statusAfterConfirm = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(statusAfterConfirm)
                .as("DB status must be CONFIRMED after confirm, bookingId=%s", bookingId)
                .isEqualTo("CONFIRMED");

        // Step 4: SALON_OWNER completes — expect 204
        log.debug("Act: PATCH {}/{}/complete as SALON_OWNER", BOOKINGS_URL, bookingId);
        ResponseEntity<Void> completeResponse = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/complete", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                Void.class);
        assertThat(completeResponse.getStatusCode())
                .as("complete must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Step 5: DB must show COMPLETED
        String statusAfterComplete = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(statusAfterComplete)
                .as("DB status must be COMPLETED after complete, bookingId=%s", bookingId)
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("GET /masters/me/calendar — master B sees 0 bookings when only master A has a booking")
    void should_returnOnlyOwnBookings_when_masterCallsCalendar() throws Exception {
        // Arrange: salon A with master A + one booking
        String ownerAEmail = "integ-cal-owner-a-" + System.nanoTime() + "@beautica.test";
        UUID masterAId = createSalonOwnerSalonAndMaster(ownerAEmail);
        UUID masterAServiceId = createMasterService(masterAId);
        addWorkingHoursForEveryDay(masterAId);

        String clientToken = createClientAndGetToken("integ-cal-client-" + System.nanoTime() + "@beautica.test");
        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0);
        createBooking(clientToken, masterAId, masterAServiceId, startsAt);

        // Arrange: salon B with master B — no bookings
        String ownerBEmail = "integ-cal-owner-b-" + System.nanoTime() + "@beautica.test";
        createSalonOwnerSalonAndMaster(ownerBEmail);

        // Obtain SALON_MASTER user ID for master B so we can log in as that master
        String masterBEmail = getMasterEmailForOwner(ownerBEmail);
        String masterBToken = loginAndGetToken(masterBEmail);

        LocalDate from = startsAt.toLocalDate().minusDays(1);
        LocalDate to = startsAt.toLocalDate().plusDays(1);
        String calendarUrl = "/api/v1/masters/me/calendar?from=" + from + "&to=" + to;

        log.debug("Act: GET {} as master B — must return 0 bookings", calendarUrl);
        ResponseEntity<String> response = restTemplate.exchange(
                calendarUrl, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(masterBToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("master B calendar request must return 200")
                .isEqualTo(HttpStatus.OK);

        JsonNode data = objectMapper.readTree(response.getBody()).path("data");
        long totalElements = data.path("totalElements").asLong(-1);
        assertThat(totalElements)
                .as("master B must see 0 bookings — master A's booking must not be visible")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("PATCH /bookings/{id}/cancel — 403 when a different client attempts the cancellation")
    void should_return403_when_differentClientCancels() throws Exception {
        String clientAToken = createClientAndGetToken("integ-cancel-a-" + System.nanoTime() + "@beautica.test");
        String clientBToken = createClientAndGetToken("integ-cancel-b-" + System.nanoTime() + "@beautica.test");
        UUID masterId = createSalonOwnerSalonAndMaster("integ-cancel-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        UUID bookingId = createBooking(clientAToken, masterId, masterServiceId);

        log.debug("Act: PATCH {}/{}/cancel with clientB token — must return 403", BOOKINGS_URL, bookingId);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/cancel", HttpMethod.PATCH,
                new HttpEntity<>("{\"cancellationReason\":\"CLIENT_CANCELLED\"}", bearerHeaders(clientBToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("different client cancelling another client's booking must return 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String createClientAndGetToken(String email) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'CLIENT', true)",
                UUID.randomUUID(), email, hash);
        return loginAndGetToken(email);
    }

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private UUID createSalonOwnerSalonAndMaster(String ownerEmail) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active) VALUES (?, ?, ?, 'SALON_OWNER', true)",
                ownerId, ownerEmail, hash);

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + ownerId);

        UUID masterUserId = UUID.randomUUID();
        String masterEmail = "master-" + System.nanoTime() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active) VALUES (?, ?, ?, 'SALON_MASTER', ?, true)",
                masterUserId, masterEmail, hash, salonId);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);

        return masterId;
    }

    private UUID createMasterService(UUID masterId) {
        UUID salonId = jdbcTemplate.queryForObject(
                "SELECT salon_id FROM masters WHERE id = ?", UUID.class, masterId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) VALUES (?, 'SALON', ?, 'Test Service', 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId);

        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        return masterServiceId;
    }

    private void addWorkingHoursForEveryDay(UUID masterId) {
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_hours (id, master_id, day_of_week, start_time, end_time, is_active) VALUES (?, ?, ?, '08:00', '20:00', true)",
                    UUID.randomUUID(), masterId, day);
        }
    }

    private UUID createBooking(String clientToken, UUID masterId, UUID masterServiceId) throws Exception {
        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0);
        return createBooking(clientToken, masterId, masterServiceId, startsAt);
    }

    private UUID createBooking(String clientToken, UUID masterId, UUID masterServiceId,
                               ZonedDateTime startsAt) throws Exception {
        var request = new CreateBookingRequest(masterId, masterServiceId, startsAt, null, null);

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(clientToken)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<BookingResponse>>() {});
        return body.data().id();
    }

    /**
     * Returns the email of the SALON_MASTER user that was created under the given salon owner.
     * {@link #createSalonOwnerSalonAndMaster(String)} inserts the master user with
     * role=SALON_MASTER and email starting with "master-". This query finds that user.
     */
    private String getMasterEmailForOwner(String ownerEmail) {
        return jdbcTemplate.queryForObject(
                """
                SELECT u.email FROM users u
                JOIN users owner ON owner.email = ?
                JOIN salons s ON s.owner_id = owner.id
                WHERE u.salon_id = s.id AND u.role = 'SALON_MASTER'
                LIMIT 1
                """,
                String.class, ownerEmail);
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
