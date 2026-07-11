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
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestSecurityConfig.class)
@DisplayName("Booking — full-flow integration")
class BookingIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BookingIntegrationTest.class);
    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

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

        // Use Kyiv zone explicitly so the slot prefix comparison is stable regardless of CI server TZ.
        ZonedDateTime startsAt = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2).withHour(11).withMinute(0).withSecond(0).withNano(0);
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

    // ── PATCH /bookings/{id}/reschedule (Phase 19.2) ─────────────────────────

    @Test
    @DisplayName("PATCH /bookings/{id}/reschedule then /confirm — CONFIRMED booking reverts to PENDING at the new time, then provider confirm ⇒ CONFIRMED at the new time")
    void should_rescheduleThenConfirm_when_clientMovesConfirmedBooking() throws Exception {
        String ownerEmail = "integ-resched-confirm-owner-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("integ-resched-confirm-client-" + System.nanoTime() + "@beautica.test");
        ZonedDateTime startsAt = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId, startsAt);

        // Provider confirms the original booking → CONFIRMED.
        restTemplate.exchange(BOOKINGS_URL + "/" + bookingId + "/confirm", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(ownerToken)), Void.class);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId))
                .isEqualTo("CONFIRMED");

        // Client reschedules to a new on-schedule slot the next day.
        ZonedDateTime newStartsAt = startsAt.plusDays(1).withHour(11);
        String body = "{\"newStartsAt\":\"" + newStartsAt.toOffsetDateTime() + "\"}";
        log.debug("Act: PATCH {}/{}/reschedule as CLIENT to {}", BOOKINGS_URL, bookingId, newStartsAt);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(clientToken)), String.class);

        assertThat(response.getStatusCode())
                .as("client rescheduling own CONFIRMED booking must return 200")
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId))
                .as("CONFIRMED booking must revert to PENDING after reschedule")
                .isEqualTo("PENDING");
        String dbStartsAfterReschedule = jdbcTemplate.queryForObject(
                "SELECT to_char(starts_at AT TIME ZONE 'Europe/Kyiv', 'HH24:MI') FROM bookings WHERE id = ?",
                String.class, bookingId);
        assertThat(dbStartsAfterReschedule)
                .as("starts_at must move to the new 11:00 Kyiv time")
                .isEqualTo("11:00");

        // Provider re-confirms at the new time → CONFIRMED at the new time.
        ResponseEntity<Void> confirmResponse = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/confirm", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(ownerToken)), Void.class);
        assertThat(confirmResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId))
                .as("re-confirm after reschedule must yield CONFIRMED")
                .isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_char(starts_at AT TIME ZONE 'Europe/Kyiv', 'HH24:MI') FROM bookings WHERE id = ?",
                String.class, bookingId))
                .as("confirmed time must still be the new 11:00 slot")
                .isEqualTo("11:00");
    }

    @Test
    @DisplayName("PATCH /bookings/{id}/reschedule then /decline — rescheduled booking is PENDING, then provider decline ⇒ DECLINED (standard path)")
    void should_rescheduleThenDecline_when_providerRejectsNewTime() throws Exception {
        String ownerEmail = "integ-resched-decline-owner-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("integ-resched-decline-client-" + System.nanoTime() + "@beautica.test");
        ZonedDateTime startsAt = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2).withHour(12).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId, startsAt);

        ZonedDateTime newStartsAt = startsAt.plusDays(1).withHour(13);
        String body = "{\"newStartsAt\":\"" + newStartsAt.toOffsetDateTime() + "\"}";
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(clientToken)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId))
                .as("PENDING booking stays PENDING after reschedule")
                .isEqualTo("PENDING");

        // Provider declines the rescheduled (PENDING) booking → DECLINED via the unchanged path.
        ResponseEntity<Void> declineResponse = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>("{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\"}", bearerHeaders(ownerToken)),
                Void.class);
        assertThat(declineResponse.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId))
                .as("decline after reschedule must yield the standard DECLINED state")
                .isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("PATCH /bookings/{id}/reschedule — 409 when the new time overlaps another booking on the same master (own row excluded under the advisory lock)")
    void should_return409_when_rescheduleOverlapsAnotherBooking() throws Exception {
        UUID masterId = createSalonOwnerSalonAndMaster("integ-resched-overlap-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientAToken = createClientAndGetToken("integ-resched-overlap-a-" + System.nanoTime() + "@beautica.test");
        String clientBToken = createClientAndGetToken("integ-resched-overlap-b-" + System.nanoTime() + "@beautica.test");

        ZonedDateTime slotA = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0);
        ZonedDateTime slotB = slotA.withHour(15);
        UUID bookingA = createBooking(clientAToken, masterId, masterServiceId, slotA);
        createBooking(clientBToken, masterId, masterServiceId, slotB);

        // Client A tries to move booking A onto B's occupied 15:00 slot → 409.
        String body = "{\"newStartsAt\":\"" + slotB.toOffsetDateTime() + "\"}";
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingA + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(clientAToken)), String.class);

        assertThat(response.getStatusCode())
                .as("reschedule onto an occupied slot must return 409")
                .isEqualTo(HttpStatus.CONFLICT);
        // Booking A is unchanged — still on its original 09:00 slot.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_char(starts_at AT TIME ZONE 'Europe/Kyiv', 'HH24:MI') FROM bookings WHERE id = ?",
                String.class, bookingA))
                .as("booking A must remain on its original slot after a rejected reschedule")
                .isEqualTo("09:00");
    }

    @Test
    @DisplayName("PATCH /bookings/{id}/reschedule — booking's own row is excluded so it can move to an adjacent free slot")
    void should_reschedule_when_targetSlotFreeAndOwnRowExcluded() throws Exception {
        UUID masterId = createSalonOwnerSalonAndMaster("integ-resched-self-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("integ-resched-self-client-" + System.nanoTime() + "@beautica.test");
        ZonedDateTime startsAt = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2).withHour(16).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId, startsAt);

        // Move to an adjacent free slot — overlap check excludes the booking's own row.
        ZonedDateTime newStartsAt = startsAt.withHour(17);
        String body = "{\"newStartsAt\":\"" + newStartsAt.toOffsetDateTime() + "\"}";
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(clientToken)), String.class);

        assertThat(response.getStatusCode())
                .as("rescheduling to a free slot must return 200 (own row excluded from overlap)")
                .isEqualTo(HttpStatus.OK);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_char(starts_at AT TIME ZONE 'Europe/Kyiv', 'HH24:MI') FROM bookings WHERE id = ?",
                String.class, bookingId))
                .isEqualTo("17:00");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private String createClientAndGetToken(String email) throws Exception {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        // email_verified = true so Phase 1.7 login gate does not return 403 EMAIL_NOT_VERIFIED
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'CLIENT', true, true)",
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
        // email_verified = true so Phase 1.7 login gate does not return 403 EMAIL_NOT_VERIFIED
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, 'SALON_OWNER', true, true)",
                ownerId, ownerEmail, hash);

        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + ownerId);

        UUID masterUserId = UUID.randomUUID();
        String masterEmail = "master-" + System.nanoTime() + "@beautica.test";
        // email_verified = true — master does not log in here but follows the same contract
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, 'SALON_MASTER', ?, true, true)",
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
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId());

        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        return masterServiceId;
    }

    /**
     * Resolves a real, selectable {@code service_types.id} (V111 made this column NOT NULL).
     */
    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    private void addWorkingHoursForEveryDay(UUID masterId) {
        // Phase 15.5 rewired SlotCalculationService to read ONLY from the new schedule model
        // (weekly_schedules / working_intervals). Mirror the V72 backfill shape: ONE open-ended
        // weekly_schedules row (valid_from pinned to the documented epoch, valid_to NULL) plus
        // SEVEN working_intervals rows (ISO day_of_week 1..7) so availability is not weekday-dependent.
        UUID scheduleId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO weekly_schedules (id, master_id, valid_from, valid_to) VALUES (?, ?, DATE '2020-01-01', NULL)",
                scheduleId, masterId);
        for (int day = 1; day <= 7; day++) {
            jdbcTemplate.update(
                    "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) VALUES (?, ?, ?, '08:00', '20:00')",
                    UUID.randomUUID(), scheduleId, day);
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
