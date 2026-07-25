package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.dto.BookingResponse;
import com.beautica.booking.dto.CreateBookingRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.notification.sms.SmsService;
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

    @Autowired
    private GuestTokenProvider guestTokenProvider;

    @MockBean
    private NotificationOutboxService notificationOutboxService;

    // Guest-booking regression test (Phase 19.4 out-of-scope guard) drives the real
    // POST /api/v1/book/{slug}/booking flow, which sends a confirmation SMS after commit —
    // mock the provider so no real SMS call is attempted (mirrors GuestBookingConcurrencyIT).
    @MockBean
    private SmsService smsService;

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

    // NOTE(24.x): the standalone "/confirm 204 when owner confirms" coverage was removed — the
    // route no longer exists (bookings are auto-confirmed at creation). Auto-confirm status is
    // asserted inline wherever a booking is created (see should_completeBooking_when_confirmedStatusFlow).

    @Test
    @DisplayName("PATCH /bookings/{id}/decline — CONFIRMED booking transitions to DECLINED with reason stored in DB")
    void should_declineBooking_and_return204_when_confirmedBookingDeclined() throws Exception {
        String ownerEmail = "integ-decline-owner-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("integ-decline-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId);

        String body = "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\",\"comment\":\"Майстер захворів сьогодні\"}";
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
    @DisplayName("Phase 25.9: PATCH /bookings/{id}/decline — 204 with no comment in the body; "
            + "the note is optional for all roles (reverses Phase 25.2's required-comment decision)")
    void should_declineBooking_and_return204_when_noCommentProvided() throws Exception {
        String ownerEmail = "integ-decline-nocomment-owner-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("integ-decline-nocomment-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId);

        String body = "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\"}";
        ResponseEntity<Void> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(ownerToken)),
                Void.class);

        assertThat(response.getStatusCode())
                .as("owner declining with no comment must return 204 — the note is optional")
                .isEqualTo(HttpStatus.NO_CONTENT);

        String dbProviderComment = jdbcTemplate.queryForObject(
                "SELECT provider_comment FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbProviderComment)
                .as("no comment supplied must persist as NULL, never an empty string")
                .isNull();
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

        // Bookings are auto-confirmed at creation (track 24.x) — no /confirm hop exists any more.
        String dbStatusAfterCreate = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbStatusAfterCreate)
                .as("booking must be CONFIRMED immediately after creation, before not-complete transition")
                .isEqualTo("CONFIRMED");
        // Phase 27.x: notCompleteBooking now requires now >= startsAt (assertElapsedForNotComplete)
        // — POST /bookings only accepts a future start, so backdate directly after creation.
        backdateBooking(bookingId);

        String body = "{\"cancellationReason\":\"CLIENT_NO_SHOW\",\"comment\":\"Клієнт не з'явився вчасно\"}";
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
    @DisplayName("Phase 25.9: PATCH /bookings/{id}/not-complete — 204 with no comment in the body; "
            + "the note is optional for all roles (reverses Phase 25.2's required-comment decision)")
    void should_markNotCompleted_and_return204_when_noCommentProvided() throws Exception {
        String ownerEmail = "integ-notcomplete-nocomment-owner-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("integ-notcomplete-nocomment-client-" + System.nanoTime() + "@beautica.test");
        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(3).withHour(15).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId, startsAt);
        // Phase 27.x: notCompleteBooking now requires now >= startsAt (assertElapsedForNotComplete)
        // — POST /bookings only accepts a future start, so backdate directly after creation.
        backdateBooking(bookingId);

        String body = "{\"cancellationReason\":\"CLIENT_NO_SHOW\"}";
        ResponseEntity<Void> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/not-complete", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(ownerToken)),
                Void.class);

        assertThat(response.getStatusCode())
                .as("owner marking not-complete with no comment must return 204 — the note is optional")
                .isEqualTo(HttpStatus.NO_CONTENT);

        String dbProviderComment = jdbcTemplate.queryForObject(
                "SELECT provider_comment FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbProviderComment)
                .as("no comment supplied must persist as NULL, never an empty string")
                .isNull();
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
    @DisplayName("PATCH /bookings/{id}/complete — full CONFIRMED→COMPLETED flow with DB assertions (track 24.x auto-confirm, no /confirm hop)")
    void should_completeBooking_when_confirmedStatusFlow() throws Exception {
        String ownerEmail = "integ-flow-owner-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createSalonOwnerSalonAndMaster(ownerEmail);
        String ownerToken = loginAndGetToken(ownerEmail);
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("integ-flow-client-" + System.nanoTime() + "@beautica.test");
        ZonedDateTime startsAt = ZonedDateTime.now().plusDays(2).withHour(13).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId, startsAt);

        // Step 1: DB must show CONFIRMED — bookings are auto-confirmed at creation (track 24.x).
        String statusAfterCreate = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(statusAfterCreate)
                .as("DB status must be CONFIRMED immediately after creation, bookingId=%s", bookingId)
                .isEqualTo("CONFIRMED");

        // Phase 27.1: completeBooking now requires now >= startsAt (assertElapsedForComplete).
        // The booking above was legitimately created 2 days in the future (through the real
        // create flow, to also prove auto-confirm), so time-shift it into the past directly —
        // simulating the appointment having begun — rather than waiting 2 real days.
        jdbcTemplate.update(
                "UPDATE bookings SET starts_at = NOW() - interval '1 hour' WHERE id = ?", bookingId);

        // Step 2: SALON_OWNER completes — expect 204
        log.debug("Act: PATCH {}/{}/complete as SALON_OWNER", BOOKINGS_URL, bookingId);
        ResponseEntity<Void> completeResponse = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/complete", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(ownerToken)),
                Void.class);
        assertThat(completeResponse.getStatusCode())
                .as("complete must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);

        // Step 3: DB must show COMPLETED
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

    @Test
    @DisplayName("GET /bookings/{id} — an authenticated actor probing a NON-EXISTENT booking id gets the "
            + "SAME 403 as a foreign booking (no 403-vs-404 existence oracle — mirrors the /cancel & "
            + "/complete convention)")
    void should_return403_when_authenticatedUserProbesNonexistentBookingId() throws Exception {
        String clientToken = createClientAndGetToken("integ-getprobe-client-" + System.nanoTime() + "@beautica.test");
        UUID nonExistentBookingId = UUID.randomUUID();

        log.debug("Act: GET {}/{} (random, non-existent id) with a CLIENT token — must return 403, not 404",
                BOOKINGS_URL, nonExistentBookingId);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + nonExistentBookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(clientToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("probing a non-existent booking id must return 403 (identical to a foreign booking) "
                        + "so the endpoint cannot be used as an existence oracle — never a distinguishing 404")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("PATCH /bookings/{id}/cancel — the OWNING client cancels a CONFIRMED booking → 204 and the "
            + "DB status is specifically CANCELLED, never DECLINED (pins the client half of the load-bearing "
            + "CANCELLED-vs-DECLINED distinction at the API level — the provider /decline half is asserted in "
            + "should_declineBooking_and_return204_when_confirmedBookingDeclined)")
    void should_transitionToCancelled_when_owningClientCancelsConfirmedBooking() throws Exception {
        UUID masterId = createSalonOwnerSalonAndMaster("integ-cancel-pos-owner-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("integ-cancel-pos-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId);

        // Guard: track 24.x auto-confirm — the booking is CONFIRMED at creation, so /cancel
        // acts on a real CONFIRMED source state (not a leftover PENDING).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId))
                .as("booking must be CONFIRMED immediately after creation, before the client cancels")
                .isEqualTo("CONFIRMED");

        String body = "{\"cancellationReason\":\"CLIENT_CANCELLED\",\"comment\":\"Захворіла, перенесу пізніше\"}";
        log.debug("Act: PATCH {}/{}/cancel as the OWNING CLIENT — must return 204", BOOKINGS_URL, bookingId);
        ResponseEntity<Void> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/cancel", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(clientToken)),
                Void.class);

        assertThat(response.getStatusCode())
                .as("owning client cancelling own CONFIRMED booking must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);

        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
        // The distinction is load-bearing: the client's "Мої записи" must render "ви скасували"
        // (CANCELLED) separately from "салон скасував" (DECLINED). A regression that routed the
        // client /cancel through the provider /decline status would pass every existing
        // 204 + note-persistence assertion — only this explicit terminal-status pin catches it.
        assertThat(dbStatus)
                .as("client /cancel must yield CANCELLED — the client-initiated terminal status")
                .isEqualTo("CANCELLED");
        assertThat(dbStatus)
                .as("client /cancel must NOT produce DECLINED — that is the PROVIDER-initiated status")
                .isNotEqualTo("DECLINED");

        String dbReason = jdbcTemplate.queryForObject(
                "SELECT cancellation_reason FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbReason)
                .as("cancellation_reason must be stored as CLIENT_CANCELLED")
                .isEqualTo("CLIENT_CANCELLED");
    }

    // ── PATCH /bookings/{id}/reschedule (Phase 19.2) ─────────────────────────

    @Test
    @DisplayName("PATCH /bookings/{id}/reschedule — CONFIRMED booking stays CONFIRMED at the new time (track 24.x — reschedule no longer touches status)")
    void should_stayConfirmed_when_clientReschedulesConfirmedBooking() throws Exception {
        String ownerEmail = "integ-resched-confirm-owner-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createSalonOwnerSalonAndMaster(ownerEmail);
        UUID masterServiceId = createMasterService(masterId);
        addWorkingHoursForEveryDay(masterId);

        String clientToken = createClientAndGetToken("integ-resched-confirm-client-" + System.nanoTime() + "@beautica.test");
        ZonedDateTime startsAt = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2).withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID bookingId = createBooking(clientToken, masterId, masterServiceId, startsAt);

        // Booking is auto-confirmed at creation — no provider /confirm hop required or possible.
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
                .as("reschedule must not touch status — booking stays CONFIRMED")
                .isEqualTo("CONFIRMED");
        String dbStartsAfterReschedule = jdbcTemplate.queryForObject(
                "SELECT to_char(starts_at AT TIME ZONE 'Europe/Kyiv', 'HH24:MI') FROM bookings WHERE id = ?",
                String.class, bookingId);
        assertThat(dbStartsAfterReschedule)
                .as("starts_at must move to the new 11:00 Kyiv time")
                .isEqualTo("11:00");
    }

    @Test
    @DisplayName("PATCH /bookings/{id}/reschedule then /decline — CONFIRMED booking stays CONFIRMED after reschedule, then provider decline ⇒ DECLINED (standard path)")
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
                .as("reschedule must not touch status — booking stays CONFIRMED")
                .isEqualTo("CONFIRMED");

        // Provider declines the rescheduled CONFIRMED booking → DECLINED via the unchanged path.
        ResponseEntity<Void> declineResponse = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>(
                        "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\",\"comment\":\"Новий час теж не підходить\"}",
                        bearerHeaders(ownerToken)),
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

    // ── Client-scoped cross-master booking conflict (Phase 19.4) ────────────────

    @Test
    @DisplayName("POST /bookings — 409 CLIENT_BOOKING_CONFLICT when the client already holds an "
            + "overlapping booking with a DIFFERENT master (core new behaviour, real DB)")
    void should_return409ClientBookingConflict_when_clientHasOverlappingBookingWithDifferentMaster() throws Exception {
        String clientToken = createClientAndGetToken("integ-cbc-client-" + System.nanoTime() + "@beautica.test");
        UUID masterAId = createSalonOwnerSalonAndMaster("integ-cbc-ownerA-" + System.nanoTime() + "@beautica.test");
        UUID masterAServiceId = createMasterService(masterAId);
        addWorkingHoursForEveryDay(masterAId);
        UUID masterBId = createSalonOwnerSalonAndMaster("integ-cbc-ownerB-" + System.nanoTime() + "@beautica.test");
        UUID masterBServiceId = createMasterService(masterBId);
        addWorkingHoursForEveryDay(masterBId);

        ZonedDateTime startsAt = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID existingBookingId = createBooking(clientToken, masterAId, masterAServiceId, startsAt);

        // Same client, same time window, a completely DIFFERENT master/salon.
        var request = new CreateBookingRequest(masterBId, masterBServiceId, startsAt, null, null);
        log.debug("Act: POST {} — client already holds a booking with master A at this time, "
                + "now booking master B at the same time", BOOKINGS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(clientToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("overlapping booking with a different master must return 409")
                .isEqualTo(HttpStatus.CONFLICT);

        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("success").asBoolean())
                .as("response envelope must have success=false")
                .isFalse();
        assertThat(body.path("data").path("code").asText())
                .as("conflict code must be CLIENT_BOOKING_CONFLICT, distinguishing this from the "
                        + "generic master-busy 409 (data:null)")
                .isEqualTo("CLIENT_BOOKING_CONFLICT");
        assertThat(body.path("data").path("conflictingBookingId").asText())
                .as("conflict detail must reference the client's EXISTING booking with master A")
                .isEqualTo(existingBookingId.toString());
        assertThat(body.path("message").asText())
                .isEqualTo("Client already has an overlapping booking");

        long bookingCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings", Long.class);
        assertThat(bookingCount)
                .as("only the original booking with master A must exist — the conflicting attempt "
                        + "was never persisted")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("PATCH /bookings/{id}/reschedule — 409 CLIENT_BOOKING_CONFLICT when the new time "
            + "overlaps another booking the client holds with a DIFFERENT master")
    void should_return409ClientBookingConflict_when_rescheduleOverlapsClientsOtherBookingWithDifferentMaster()
            throws Exception {
        String clientToken = createClientAndGetToken("integ-cbc-resched-client-" + System.nanoTime() + "@beautica.test");
        UUID masterAId = createSalonOwnerSalonAndMaster("integ-cbc-resched-ownerA-" + System.nanoTime() + "@beautica.test");
        UUID masterAServiceId = createMasterService(masterAId);
        addWorkingHoursForEveryDay(masterAId);
        UUID masterBId = createSalonOwnerSalonAndMaster("integ-cbc-resched-ownerB-" + System.nanoTime() + "@beautica.test");
        UUID masterBServiceId = createMasterService(masterBId);
        addWorkingHoursForEveryDay(masterBId);

        ZonedDateTime slotA = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2)
                .withHour(9).withMinute(0).withSecond(0).withNano(0);
        ZonedDateTime slotB = slotA.withHour(15);
        UUID bookingAId = createBooking(clientToken, masterAId, masterAServiceId, slotA);
        UUID bookingBId = createBooking(clientToken, masterBId, masterBServiceId, slotB);

        // Client reschedules booking B onto booking A's occupied window — different master,
        // but the SAME client already holds that time.
        String body = "{\"newStartsAt\":\"" + slotA.toOffsetDateTime() + "\"}";
        log.debug("Act: PATCH {}/{}/reschedule onto the client's OWN other-master booking window",
                BOOKINGS_URL, bookingBId);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingBId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(clientToken)), String.class);

        assertThat(response.getStatusCode())
                .as("rescheduling into the client's own other-master booking window must return 409")
                .isEqualTo(HttpStatus.CONFLICT);

        JsonNode responseBody = objectMapper.readTree(response.getBody());
        assertThat(responseBody.path("data").path("code").asText())
                .isEqualTo("CLIENT_BOOKING_CONFLICT");
        assertThat(responseBody.path("data").path("conflictingBookingId").asText())
                .isEqualTo(bookingAId.toString());

        // Booking B is unchanged — still on its original 15:00 slot.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_char(starts_at AT TIME ZONE 'Europe/Kyiv', 'HH24:MI') FROM bookings WHERE id = ?",
                String.class, bookingBId))
                .as("booking B must remain on its original slot after a rejected reschedule")
                .isEqualTo("15:00");
    }

    // NOTE: an IT-level "reschedule to a time overlapping the booking's own CURRENT window"
    // test was deliberately NOT added here. SlotCalculationService.getAvailableSlots (the
    // assertStartsOnAvailableSlot oracle) sources occupied windows from
    // BookingRepository.findActiveByMasterInRange WITHOUT excluding the booking being
    // rescheduled, so any reschedule request targeting a time that overlaps the booking's own
    // still-stored window is rejected as 409 "Slot not available" BEFORE the client-conflict
    // exclusion logic is ever reached — this is a separate, pre-existing constraint of the slot
    // oracle, not a defect in the client-conflict feature. The own-row exclusion contract of
    // findFirstConflictingClientBookingIdExcluding IS correctly implemented and is proven
    // directly against real Postgres in BookingRepositoryTest
    // (should_excludeOwnRow_when_findFirstConflictingClientBookingIdExcluding), which calls the
    // repository method directly and bypasses the slot oracle. See the QA report's residual-gaps
    // section for the full writeup.

    @Test
    @DisplayName("POST /bookings — half-open boundary: a new booking starting exactly when the "
            + "client's existing (different-master) booking ends is ALLOWED, no off-by-one")
    void should_allowNewBooking_when_startsExactlyWhenClientsOtherBookingEnds() throws Exception {
        String clientToken = createClientAndGetToken("integ-cbc-boundary-client-" + System.nanoTime() + "@beautica.test");
        UUID masterAId = createSalonOwnerSalonAndMaster("integ-cbc-boundary-ownerA-" + System.nanoTime() + "@beautica.test");
        UUID masterAServiceId = createMasterService(masterAId);
        addWorkingHoursForEveryDay(masterAId);
        UUID masterBId = createSalonOwnerSalonAndMaster("integ-cbc-boundary-ownerB-" + System.nanoTime() + "@beautica.test");
        UUID masterBServiceId = createMasterService(masterBId);
        addWorkingHoursForEveryDay(masterBId);

        // Master A booking: 10:00–11:00 (60-minute service, no buffer).
        ZonedDateTime startsAt = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        createBooking(clientToken, masterAId, masterAServiceId, startsAt);

        // Master B booking starts exactly at 11:00 — the half-open interval means this must
        // NOT be treated as a conflict with the client's own 10:00–11:00 master-A booking.
        ZonedDateTime backToBackStart = startsAt.plusHours(1);
        var request = new CreateBookingRequest(masterBId, masterBServiceId, backToBackStart, null, null);
        log.debug("Act: POST {} — new booking starts exactly when the client's other booking ends", BOOKINGS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(clientToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("back-to-back booking starting exactly when the client's other booking ends must be allowed")
                .isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("POST /bookings — 409 CLIENT_BOOKING_CONFLICT when the EXISTING booking's "
            + "service-duration-derived end extends past the newly requested start (duration-awareness)")
    void should_return409ClientBookingConflict_when_existingBookingDurationExtendsPastNewRequestedStart()
            throws Exception {
        String clientToken = createClientAndGetToken("integ-cbc-duration-client-" + System.nanoTime() + "@beautica.test");
        UUID masterAId = createSalonOwnerSalonAndMaster("integ-cbc-duration-ownerA-" + System.nanoTime() + "@beautica.test");
        // 90-minute service on master A: a 10:00 start ends at 11:30, not 11:00 — the
        // conflict must be derived from the ACTUAL service duration, not a naive same-start check.
        UUID masterAServiceId = createMasterServiceWithDuration(masterAId, 90);
        addWorkingHoursForEveryDay(masterAId);
        UUID masterBId = createSalonOwnerSalonAndMaster("integ-cbc-duration-ownerB-" + System.nanoTime() + "@beautica.test");
        UUID masterBServiceId = createMasterService(masterBId);
        addWorkingHoursForEveryDay(masterBId);

        ZonedDateTime startsAt = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0);
        UUID existingBookingId = createBooking(clientToken, masterAId, masterAServiceId, startsAt);

        // 11:00 is BEFORE the existing booking's duration-derived end (11:30) but AFTER its
        // start (10:00) — only a duration-aware overlap check catches this.
        ZonedDateTime overlappingStart = startsAt.plusHours(1);
        var request = new CreateBookingRequest(masterBId, masterBServiceId, overlappingStart, null, null);
        log.debug("Act: POST {} — requested start falls inside the existing booking's "
                + "duration-derived window, not at its literal start time", BOOKINGS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(clientToken)),
                String.class);

        assertThat(response.getStatusCode())
                .as("a request falling inside the existing booking's duration-derived window must conflict")
                .isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("data").path("code").asText()).isEqualTo("CLIENT_BOOKING_CONFLICT");
        assertThat(body.path("data").path("conflictingBookingId").asText())
                .isEqualTo(existingBookingId.toString());
    }

    @Test
    @DisplayName("POST /bookings — CLIENT_BOOKING_CONFLICT (not the generic master-busy conflict) "
            + "wins when BOTH the client-conflict and the master-busy checks would fire")
    void should_returnClientBookingConflictNotGenericConflict_when_bothClientConflictAndMasterBusyApply()
            throws Exception {
        String clientAToken = createClientAndGetToken("integ-cbc-precedence-a-" + System.nanoTime() + "@beautica.test");
        String clientBToken = createClientAndGetToken("integ-cbc-precedence-b-" + System.nanoTime() + "@beautica.test");
        UUID masterAId = createSalonOwnerSalonAndMaster("integ-cbc-precedence-ownerA-" + System.nanoTime() + "@beautica.test");
        UUID masterAServiceId = createMasterService(masterAId);
        addWorkingHoursForEveryDay(masterAId);
        UUID masterCId = createSalonOwnerSalonAndMaster("integ-cbc-precedence-ownerC-" + System.nanoTime() + "@beautica.test");
        UUID masterCServiceId = createMasterService(masterCId);
        addWorkingHoursForEveryDay(masterCId);

        ZonedDateTime slot = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2)
                .withHour(13).withMinute(0).withSecond(0).withNano(0);

        // Master A is BUSY at `slot` — a different client (B) already holds it.
        createBooking(clientBToken, masterAId, masterAServiceId, slot);
        // Client A separately holds an unrelated booking (master C) at the SAME `slot`.
        createBooking(clientAToken, masterCId, masterCServiceId, slot);

        // Client A now requests master A at `slot`: master A is busy AND client A has a
        // conflict — the client-conflict check must win (it runs first, before the master
        // lock is even acquired).
        var request = new CreateBookingRequest(masterAId, masterAServiceId, slot, null, null);
        log.debug("Act: POST {} — both master-busy and client-conflict conditions hold simultaneously",
                BOOKINGS_URL);
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST,
                new HttpEntity<>(request, bearerHeaders(clientAToken)),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        JsonNode body = objectMapper.readTree(response.getBody());
        assertThat(body.path("data").path("code").asText())
                .as("the structured CLIENT_BOOKING_CONFLICT code must win over the generic "
                        + "master-busy conflict (which carries data:null) when both apply")
                .isEqualTo("CLIENT_BOOKING_CONFLICT");
    }

    @Test
    @DisplayName("POST /book/{slug}/booking — a GUEST booking is NOT subject to the client-conflict "
            + "check, even when its window overlaps an authenticated client's existing booking")
    void should_allowGuestBooking_when_windowOverlapsAnAuthenticatedClientsExistingBooking() throws Exception {
        String clientToken = createClientAndGetToken("integ-cbc-guest-client-" + System.nanoTime() + "@beautica.test");
        UUID masterAId = createSalonOwnerSalonAndMaster("integ-cbc-guest-ownerA-" + System.nanoTime() + "@beautica.test");
        UUID masterAServiceId = createMasterService(masterAId);
        addWorkingHoursForEveryDay(masterAId);

        ZonedDateTime slot = ZonedDateTime.now(ZoneId.of("Europe/Kyiv")).plusDays(2)
                .withHour(9).withMinute(0).withSecond(0).withNano(0);
        createBooking(clientToken, masterAId, masterAServiceId, slot);

        // Master B is bookable via the public guest link, entirely unrelated to master A.
        UUID masterBId = createSalonOwnerSalonAndMaster("integ-cbc-guest-ownerB-" + System.nanoTime() + "@beautica.test");
        UUID masterBServiceId = createMasterService(masterBId);
        addWorkingHoursForEveryDay(masterBId);
        String slug = "cbc-guest-exempt-" + Long.toString(System.nanoTime(), 36);
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", slug, masterBId);

        String guestToken = guestTokenProvider.generate("+380509998877");
        String body = "{\"serviceId\":\"" + masterBServiceId + "\",\"startsAt\":\""
                + slot.toOffsetDateTime() + "\",\"name\":\"Гість\",\"surname\":\"Тестовий\"}";

        HttpHeaders guestHeaders = new HttpHeaders();
        guestHeaders.setBearerAuth(guestToken);
        guestHeaders.setContentType(MediaType.APPLICATION_JSON);

        log.debug("Act: POST /book/{}/booking — window overlaps an authenticated client's booking, "
                + "but the guest flow has no client identity to conflict-check against", slug);
        ResponseEntity<String> response = restTemplate.exchange(
                "/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, guestHeaders), String.class);

        assertThat(response.getStatusCode())
                .as("a guest booking must succeed even when its window overlaps an authenticated "
                        + "client's own booking — the guest path is out of scope for the "
                        + "client-conflict check")
                .isEqualTo(HttpStatus.CREATED);
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
     * Same as {@link #createMasterService(UUID)} but with a caller-supplied base duration —
     * used by the duration-awareness client-conflict test where a 60-minute default would not
     * exercise the distinction between "same start time" and "duration-derived end".
     */
    private UUID createMasterServiceWithDuration(UUID masterId, int durationMinutes) {
        UUID salonId = jdbcTemplate.queryForObject(
                "SELECT salon_id FROM masters WHERE id = ?", UUID.class, masterId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) VALUES (?, 'SALON', ?, 'Test Service', ?, ?, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId(), durationMinutes);

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
     * Overwrites a booking's {@code starts_at}/{@code ends_at} to an ABSOLUTE window that already
     * ended an hour ago, so a booking created via the future-only {@code POST /bookings} create
     * path can still be used to exercise a provider guard that requires the slot to have already
     * begun (Phase 27.x's {@code assertElapsedForNotComplete}). Deliberately absolute, not a
     * relative subtraction off the booking's own (fixture-specific) future offset — this class's
     * fixtures anchor at anywhere from {@code now+1day} to {@code now+3days}, so a fixed relative
     * shift would silently under-shoot for the larger offsets and leave the booking still future
     * (exactly the bug a first attempt at this helper had: subtracting 2 days from a
     * {@code now+3days} booking lands at {@code now+1day} — still future, guard still 409s).
     */
    private void backdateBooking(UUID bookingId) {
        jdbcTemplate.update(
                "UPDATE bookings SET starts_at = NOW() - interval '2 hours', "
                        + "ends_at = NOW() - interval '1 hour' WHERE id = ?",
                bookingId);
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
