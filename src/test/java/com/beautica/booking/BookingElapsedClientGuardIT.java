package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.booking.dto.BookingElapsedResponse;
import com.beautica.booking.dto.CancelBookingRequest;
import com.beautica.booking.dto.RescheduleBookingRequest;
import com.beautica.booking.dto.StatusUpdateRequest;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.enums.CancellationReason;
import com.beautica.booking.service.BookingService;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.BookingElapsedException;
import com.beautica.common.exception.BusinessException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationOutboxService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack regression net for the <b>track-24.x read-only-after-elapse client guard</b>
 * ({@code BookingService#assertNotElapsedForClient}, surfacing as
 * {@code 409 BOOKING_ALREADY_ELAPSED}). Locked product rule: once a booking's appointment window
 * has fully passed ({@code endsAt < now}) it becomes read-only for the CLIENT and awaits provider
 * resolution — a client may no longer cancel or reschedule it, while the provider
 * (complete / mark-no-show) still can.
 *
 * <p><b>Phase 27.1 update (REVERSES a claim this class used to pin).</b> {@code decline} gained
 * its OWN, opposite-direction temporal guard: a provider may only decline a booking that has NOT
 * yet started ({@code now < startsAt}). An elapsed booking (whose {@code startsAt} is necessarily
 * also in the past) can therefore no longer be declined — {@code complete}/{@code not-complete}
 * are the only remaining resolution paths once a booking elapses. See Matrix #5 below, which was
 * updated in place rather than left to silently document stale behaviour.
 *
 * <p><b>Threat model this pins — server-clock authority.</b> The elapsed verdict is computed
 * SOLELY from the injected server {@link java.time.Clock} and the persisted {@code endsAt}; no
 * request field carries a client "now". A device with a rolled-back clock therefore cannot revive
 * an elapsed booking — proven here by a reschedule carrying a perfectly valid <i>future</i>
 * {@code newStartsAt} (the one time value the client fully controls) still returning
 * {@code BOOKING_ALREADY_ELAPSED}, never a slot error. The nanosecond {@code isBefore} boundary and
 * the guard-after-status ordering are pinned deterministically at the service layer in
 * {@link com.beautica.booking.service.BookingServiceTest} against a fixed {@code Clock}.
 *
 * <p>Elapsed bookings are seeded via a 45-minute slot anchored to {@code NOW()} (starts 90 min ago,
 * ends 45 min ago) so {@code endsAt < NOW()} holds deterministically on every run under the real
 * {@code Clock.systemUTC()} bean — no fixed-clock plumbing needed at the HTTP layer. Provider
 * actions are exercised at the service layer (the provider-JWT-over-HTTP authorization matrix is
 * already pinned by {@link BookingCompletionSecurityIT} / {@link BookingProviderCancelAuthorizationIT});
 * this class adds only the elapsed dimension those do not cover.
 *
 * <p>{@link NotificationOutboxService} is mocked so the enqueue side effects of the provider paths
 * are no-ops and the always-on {@code @Scheduled} drain never fires real SMTP/push — mirroring
 * {@link BookingRescheduleGuardChainIT}. Assertions read booking status straight from the row.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Booking elapsed-client guard — full-stack (409 BOOKING_ALREADY_ELAPSED; provider paths still allowed; server-clock authority)")
class BookingElapsedClientGuardIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BookingService bookingService;

    // Mocked so provider enqueue calls are no-ops and the background @Scheduled drain never fires
    // real SMTP/push against seeded rows.
    @MockBean
    private NotificationOutboxService notificationOutboxService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── Matrix #1 — elapsed CONFIRMED → client cancel → 409 BOOKING_ALREADY_ELAPSED ──────────────

    @Test
    @DisplayName("PATCH /cancel — 409 with data.code=BOOKING_ALREADY_ELAPSED when the owning client cancels an elapsed CONFIRMED booking")
    void should_return409ElapsedCode_when_clientCancelsElapsedConfirmedBooking() throws Exception {
        Master master = createIndependentMaster("elapsed-cancel-master-" + System.nanoTime() + "@beautica.test");
        UUID serviceId = createMasterService(master.masterId);
        UUID clientId = createClient("elapsed-cancel-client-" + System.nanoTime() + "@beautica.test");
        String clientToken = loginAndGetToken(userEmail(clientId));
        UUID bookingId = insertElapsedConfirmedBooking(clientId, master.masterId, serviceId);

        String body = objectMapper.writeValueAsString(
                new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null));
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/cancel", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(clientToken)), String.class);

        assertElapsedConflict(response, "cancel of an elapsed booking");
        assertThat(bookingStatus(bookingId))
                .as("a rejected cancel must leave the booking CONFIRMED — the provider still owns resolution")
                .isEqualTo(BookingStatus.CONFIRMED.name());
    }

    // ── Matrix #2 — elapsed CONFIRMED → client reschedule → 409 BOOKING_ALREADY_ELAPSED ──────────

    @Test
    @DisplayName("PATCH /reschedule — 409 with data.code=BOOKING_ALREADY_ELAPSED when the owning client reschedules an elapsed CONFIRMED booking")
    void should_return409ElapsedCode_when_clientReschedulesElapsedConfirmedBooking() throws Exception {
        Master master = createIndependentMaster("elapsed-resched-master-" + System.nanoTime() + "@beautica.test");
        UUID serviceId = createMasterService(master.masterId);
        UUID clientId = createClient("elapsed-resched-client-" + System.nanoTime() + "@beautica.test");
        String clientToken = loginAndGetToken(userEmail(clientId));
        UUID bookingId = insertElapsedConfirmedBooking(clientId, master.masterId, serviceId);

        OffsetDateTime futureStart = ZonedDateTime.now(KYIV).plusDays(2)
                .withHour(10).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();
        String body = objectMapper.writeValueAsString(new RescheduleBookingRequest(futureStart));
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(clientToken)), String.class);

        assertElapsedConflict(response, "reschedule of an elapsed booking");
    }

    // ── Matrix #6 — server-clock authority (defeats a device-clock rollback) ─────────────────────

    @Test
    @DisplayName("PATCH /reschedule — a fully valid FUTURE newStartsAt still yields BOOKING_ALREADY_ELAPSED (guard fires before the slot check; no client field revives the booking)")
    void should_stillReturnElapsed_when_rescheduleCarriesAValidFutureTime() throws Exception {
        // The booking's master has NO working hours seeded. If the elapsed guard did NOT fire first,
        // the reschedule would fall through to the schedule oracle and fail as "Slot not available".
        // Asserting the code is specifically BOOKING_ALREADY_ELAPSED proves the guard short-circuits
        // ahead of slot resolution AND that the client-controlled newStartsAt cannot bypass it —
        // there is no client "now" to trust, so a rolled-back device clock changes nothing.
        Master master = createIndependentMaster("elapsed-authority-master-" + System.nanoTime() + "@beautica.test");
        UUID serviceId = createMasterService(master.masterId);
        UUID clientId = createClient("elapsed-authority-client-" + System.nanoTime() + "@beautica.test");
        String clientToken = loginAndGetToken(userEmail(clientId));
        UUID bookingId = insertElapsedConfirmedBooking(clientId, master.masterId, serviceId);

        OffsetDateTime perfectlyValidFuture = ZonedDateTime.now(KYIV).plusDays(3)
                .withHour(12).withMinute(0).withSecond(0).withNano(0).toOffsetDateTime();
        String body = objectMapper.writeValueAsString(new RescheduleBookingRequest(perfectlyValidFuture));
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(clientToken)), String.class);

        assertElapsedConflict(response, "reschedule with a valid future time on an elapsed booking");
    }

    // ── Matrix #9 — ownership still enforced; a non-owner CLIENT never observes elapsed state ────

    @Test
    @DisplayName("PATCH /cancel — 403 (IDOR) when a different CLIENT cancels another client's elapsed booking; body carries NO elapsed oracle")
    void should_return403WithoutElapsedOracle_when_differentClientCancelsElapsedBooking() throws Exception {
        Master master = createIndependentMaster("elapsed-idor-master-" + System.nanoTime() + "@beautica.test");
        UUID serviceId = createMasterService(master.masterId);
        UUID ownerClientId = createClient("elapsed-idor-owner-" + System.nanoTime() + "@beautica.test");
        UUID strangerClientId = createClient("elapsed-idor-stranger-" + System.nanoTime() + "@beautica.test");
        String strangerToken = loginAndGetToken(userEmail(strangerClientId));
        UUID bookingId = insertElapsedConfirmedBooking(ownerClientId, master.masterId, serviceId);

        String body = objectMapper.writeValueAsString(
                new CancelBookingRequest(CancellationReason.CLIENT_CANCELLED, null));
        ResponseEntity<String> response = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/cancel", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(strangerToken)), String.class);

        assertThat(response.getStatusCode())
                .as("ownership is checked before the elapsed guard — a non-owner must get a uniform 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody())
                .as("the additive elapsed guard must not create an oracle: a non-owner must not learn the "
                        + "booking is elapsed")
                .doesNotContain(BookingElapsedException.ERROR_CODE);
        assertThat(bookingStatus(bookingId))
                .as("a denied cross-client cancel must not mutate the booking")
                .isEqualTo(BookingStatus.CONFIRMED.name());
    }

    // ── Matrix #5 — provider resolution paths on an elapsed CONFIRMED booking ────────────────────
    // Phase 27.1: decline itself gained a temporal guard (future-only), so it FLIPPED from
    // "still allowed" to "now rejected" on an elapsed booking — complete/not-complete remain the
    // only resolution paths once a booking has started. The old "decline still succeeds" test is
    // replaced in place with its reversed expectation, not left stale beside a new one.

    @Test
    @DisplayName("Phase 27.1: provider decline on an ELAPSED CONFIRMED booking is now REJECTED with 409 "
            + "(decline is future-only — complete/not-complete are the only resolution paths left once "
            + "a booking has started)")
    void should_rejectProviderDecline_when_bookingElapsed() {
        Master master = createIndependentMaster("elapsed-decline-master-" + System.nanoTime() + "@beautica.test");
        UUID serviceId = createMasterService(master.masterId);
        UUID clientId = createClient("elapsed-decline-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertElapsedConfirmedBooking(clientId, master.masterId, serviceId);

        authenticateAs(master.userId);
        StatusUpdateRequest req = new StatusUpdateRequest(CancellationReason.PROVIDER_UNAVAILABLE, null);
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> bookingService.declineBooking(master.userId, bookingId, req)))
                .as("decline on an already-started booking must be rejected with a 409 BusinessException")
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(bookingStatus(bookingId))
                .as("a rejected decline must leave the elapsed booking CONFIRMED")
                .isEqualTo(BookingStatus.CONFIRMED.name());
    }

    @Test
    @DisplayName("provider mark-complete on an elapsed CONFIRMED booking still succeeds → COMPLETED (the one path into the past)")
    void should_allowProviderComplete_when_bookingElapsed() {
        Master master = createIndependentMaster("elapsed-complete-master-" + System.nanoTime() + "@beautica.test");
        UUID serviceId = createMasterService(master.masterId);
        UUID clientId = createClient("elapsed-complete-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertElapsedConfirmedBooking(clientId, master.masterId, serviceId);

        authenticateAs(master.userId);
        bookingService.completeBooking(master.userId, bookingId);

        assertThat(bookingStatus(bookingId))
                .as("the provider may mark an elapsed booking completed — the elapsed guard is client-only")
                .isEqualTo(BookingStatus.COMPLETED.name());
    }

    @Test
    @DisplayName("provider mark-no-show on an elapsed CONFIRMED booking still succeeds → NOT_COMPLETED (resolution path is NOT guarded)")
    void should_allowProviderMarkNoShow_when_bookingElapsed() {
        Master master = createIndependentMaster("elapsed-noshow-master-" + System.nanoTime() + "@beautica.test");
        UUID serviceId = createMasterService(master.masterId);
        UUID clientId = createClient("elapsed-noshow-client-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertElapsedConfirmedBooking(clientId, master.masterId, serviceId);

        authenticateAs(master.userId);
        bookingService.notCompleteBooking(master.userId, bookingId,
                new StatusUpdateRequest(CancellationReason.CLIENT_NO_SHOW, null));

        assertThat(bookingStatus(bookingId))
                .as("the provider may mark an elapsed booking as a no-show — the elapsed guard is client-only")
                .isEqualTo(BookingStatus.NOT_COMPLETED.name());
    }

    // ── shared assertion ─────────────────────────────────────────────────────────

    private void assertElapsedConflict(ResponseEntity<String> response, String context) throws Exception {
        assertThat(response.getStatusCode())
                .as("%s must map to 409 Conflict", context)
                .isEqualTo(HttpStatus.CONFLICT);
        ApiResponse<BookingElapsedResponse> parsed = objectMapper.readValue(
                response.getBody(), new TypeReference<ApiResponse<BookingElapsedResponse>>() {});
        assertThat(parsed.success())
                .as("%s — success must be false", context)
                .isFalse();
        assertThat(parsed.data().code())
                .as("%s — data.code must be the stable BOOKING_ALREADY_ELAPSED constant "
                        + "(distinguishes it from generic master-busy / slot-not-available 409s)", context)
                .isEqualTo(BookingElapsedException.ERROR_CODE)
                .isEqualTo("BOOKING_ALREADY_ELAPSED");
    }

    // ── fixtures (local by house convention — see BookingElapsedNoAutoCompleteIT class Javadoc) ──

    private record Master(UUID masterId, UUID userId) {}

    private void authenticateAs(UUID userId) {
        var auth = new UsernamePasswordAuthenticationToken(
                userId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_INDEPENDENT_MASTER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private Master createIndependentMaster(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', 'Майстер', 'Демо', true, true)",
                userId, email, passwordEncoder.encode(TEST_PASSWORD));
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return new Master(masterId, userId);
    }

    private UUID createClient(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD));
        return id;
    }

    private String userEmail(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private UUID createMasterService(UUID masterId) {
        UUID ownerUserId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, "
                        + "base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Експрес нарощення', ?, 45, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, ownerUserId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    /**
     * Inserts an APP booking whose 45-minute slot has already ended (starts 90 min ago, ends 45 min
     * ago) so {@code endsAt < NOW()} always holds — the "elapsed" condition the guard keys off.
     */
    private UUID insertElapsedConfirmedBooking(UUID clientId, UUID masterId, UUID masterServiceId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 'CONFIRMED', NOW() - interval '90 minutes', NOW() - interval '45 minutes', "
                        + "500.00, 45, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId);
        return bookingId;
    }

    private String bookingStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var parsed = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return parsed.data().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
