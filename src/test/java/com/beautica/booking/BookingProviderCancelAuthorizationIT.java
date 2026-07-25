package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxStatus;
import com.beautica.notification.repository.NotificationOutboxRepository;
import com.beautica.notification.service.NotificationOutboxDrainWorker;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 24.7 — the provider-cancellation authorization matrix for {@code PATCH /decline} and
 * {@code PATCH /not-complete} (track 24.x, decisions D1/D2), plus the two adjacent regressions
 * the phase doc calls out explicitly: a guest-booking decline must drain the outbox cleanly (no
 * DEAD row — Risk R4 closed in 24.4), and the {@code assertTransition} state-machine guard must
 * reject a decline/complete attempted from a terminal, non-{@code CONFIRMED} source status.
 *
 * <p><b>Deliberately NOT re-covered here</b> (already asserted elsewhere — no duplication):
 * SALON_OWNER ✅ on {@code /decline} ({@link BookingIntegrationTest}) and on {@code /not-complete}
 * ({@link BookingIntegrationTest}); assigned SALON_ADMIN ✅ on both endpoints
 * ({@link BookingCompletionSecurityIT}, Decisions D1/D2); SALON_MASTER ❌ on {@code /decline}
 * ({@link BookingSecurityTest}); cross-tenant SALON_OWNER ❌ on {@code /decline}
 * ({@link BookingSecurityTest}).
 *
 * <p>{@code NotificationOutboxService} and {@code NotificationService} are intentionally left
 * REAL (unlike the sibling completion suites, which mock the outbox to keep the assertions to
 * HTTP status only) — the guest-decline test needs the real enqueue-then-drain path to prove the
 * null-client guard actually fires. {@code EmailNotificationService}/{@code EmailService} stay
 * mocked (inherited from {@link AbstractIntegrationTest}); push notifications are a documented
 * no-op under the test profile ({@code FIREBASE_ENABLED} defaults to false), so draining never
 * performs real network I/O.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Booking provider-cancellation — /decline + /not-complete authorization matrix (Phase 24.7)")
class BookingProviderCancelAuthorizationIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private NotificationOutboxRepository outboxRepository;

    @Autowired
    private NotificationOutboxDrainWorker drainWorker;

    // Phase 25.7: declining a guest booking now sends an SMS (see
    // should_return204AndDrainWithoutDeadRow_when_providerDeclinesGuestBooking below). Mocked so
    // this class never performs real network I/O against the Turbosms API.
    @MockBean
    private SmsService smsService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    // ── /decline — actors missing from the existing matrix ───────────────────────

    @Test
    @DisplayName("PATCH /decline — INDEPENDENT_MASTER declining their own CONFIRMED booking returns 204")
    void should_return204_when_independentMasterDeclinesOwnConfirmedBooking() {
        String masterEmail = "provcancel-decl-im-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createIndependentMaster(masterEmail);
        UUID clientId = createUser("provcancel-decl-im-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterServiceId = createIndependentMasterService(masterId);
        UUID bookingId = insertConfirmedBooking(clientId, masterId, masterServiceId, null);

        ResponseEntity<Void> resp = patchDecline(bookingId, tokenFor(masterEmail));

        assertThat(resp.getStatusCode())
                .as("independent master declining own booking must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(bookingId)).isEqualTo("DECLINED");
    }

    @Test
    @DisplayName("PATCH /decline — SALON_ADMIN assigned to a DIFFERENT salon is denied with 403")
    void should_return403_when_unassignedAdminDeclinesBooking() {
        Salon salonA = createSalon("provcancel-decl-unassigned-a-" + System.nanoTime() + "@beautica.test");
        Salon salonB = createSalon("provcancel-decl-unassigned-b-" + System.nanoTime() + "@beautica.test");
        String unassignedAdminEmail = "provcancel-decl-unassigned-admin-" + System.nanoTime() + "@beautica.test";
        createUser(unassignedAdminEmail, "SALON_ADMIN", salonB.salonId); // assigned to B, not A
        UUID bookingId = insertConfirmedSalonBooking(salonA);

        ResponseEntity<Void> resp = patchDecline(bookingId, tokenFor(unassignedAdminEmail));

        assertThat(resp.getStatusCode())
                .as("SALON_ADMIN assigned to salon B must be denied declining salon A's booking")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(bookingId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("PATCH /decline — CLIENT is denied with 403 (this is the provider-side action, not /cancel)")
    void should_return403_when_clientAttemptsToDecline() {
        Salon salon = createSalon("provcancel-decl-client-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedSalonBooking(salon);
        String clientEmail = "provcancel-decl-client-" + System.nanoTime() + "@beautica.test";
        createUser(clientEmail, "CLIENT", null);

        ResponseEntity<Void> resp = patchDecline(bookingId, tokenFor(clientEmail));

        assertThat(resp.getStatusCode())
                .as("CLIENT must be denied /decline — provider-side action")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(bookingId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("PATCH /decline — INDEPENDENT_MASTER declining another master's booking is denied with 403")
    void should_return403_when_independentMasterDeclinesAnotherMastersBooking() {
        UUID masterA = createIndependentMaster("provcancel-decl-im-a-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceA = createIndependentMasterService(masterA);
        UUID clientId = createUser("provcancel-decl-im-client-b-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingA = insertConfirmedBooking(clientId, masterA, masterServiceA, null);

        String masterBEmail = "provcancel-decl-im-b-" + System.nanoTime() + "@beautica.test";
        createIndependentMaster(masterBEmail);

        ResponseEntity<Void> resp = patchDecline(bookingA, tokenFor(masterBEmail));

        assertThat(resp.getStatusCode())
                .as("independent master B must not decline master A's booking — 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(bookingA)).isEqualTo("CONFIRMED");
    }

    // ── /not-complete — actors missing from the existing matrix ──────────────────

    @Test
    @DisplayName("PATCH /not-complete — INDEPENDENT_MASTER marking their own CONFIRMED booking not-complete returns 204")
    void should_return204_when_independentMasterMarksOwnConfirmedBookingNotComplete() {
        String masterEmail = "provcancel-nc-im-" + System.nanoTime() + "@beautica.test";
        UUID masterId = createIndependentMaster(masterEmail);
        UUID clientId = createUser("provcancel-nc-im-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterServiceId = createIndependentMasterService(masterId);
        UUID bookingId = insertConfirmedBooking(clientId, masterId, masterServiceId, null);

        ResponseEntity<Void> resp = patchNotComplete(bookingId, tokenFor(masterEmail));

        assertThat(resp.getStatusCode())
                .as("independent master marking own booking not-complete must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(bookingId)).isEqualTo("NOT_COMPLETED");
    }

    @Test
    @DisplayName("PATCH /not-complete — SALON_ADMIN assigned to a DIFFERENT salon is denied with 403")
    void should_return403_when_unassignedAdminMarksAnotherSalonsBookingNotComplete() {
        Salon salonA = createSalon("provcancel-nc-unassigned-a-" + System.nanoTime() + "@beautica.test");
        Salon salonB = createSalon("provcancel-nc-unassigned-b-" + System.nanoTime() + "@beautica.test");
        String unassignedAdminEmail = "provcancel-nc-unassigned-admin-" + System.nanoTime() + "@beautica.test";
        createUser(unassignedAdminEmail, "SALON_ADMIN", salonB.salonId);
        UUID bookingId = insertConfirmedSalonBooking(salonA);

        ResponseEntity<Void> resp = patchNotComplete(bookingId, tokenFor(unassignedAdminEmail));

        assertThat(resp.getStatusCode())
                .as("SALON_ADMIN assigned to salon B must be denied marking salon A's booking not-complete")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(bookingId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("PATCH /not-complete — CLIENT is denied with 403")
    void should_return403_when_clientAttemptsNotComplete() {
        Salon salon = createSalon("provcancel-nc-client-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedSalonBooking(salon);
        String clientEmail = "provcancel-nc-client-" + System.nanoTime() + "@beautica.test";
        createUser(clientEmail, "CLIENT", null);

        ResponseEntity<Void> resp = patchNotComplete(bookingId, tokenFor(clientEmail));

        assertThat(resp.getStatusCode())
                .as("CLIENT must be denied /not-complete")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(bookingId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("PATCH /not-complete — SALON_MASTER (read-only role) is denied with 403")
    void should_return403_when_salonMasterAttemptsNotComplete() {
        Salon salon = createSalon("provcancel-nc-sm-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedSalonBooking(salon);

        ResponseEntity<Void> resp = patchNotComplete(bookingId, tokenFor(salon.masterEmail));

        assertThat(resp.getStatusCode())
                .as("SALON_MASTER must be denied /not-complete — 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(bookingId)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("PATCH /not-complete — INDEPENDENT_MASTER marking another master's booking not-complete is denied with 403")
    void should_return403_when_independentMasterMarksAnotherMastersBookingNotComplete() {
        UUID masterA = createIndependentMaster("provcancel-nc-im-a-" + System.nanoTime() + "@beautica.test");
        UUID masterServiceA = createIndependentMasterService(masterA);
        UUID clientId = createUser("provcancel-nc-im-client-b-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID bookingA = insertConfirmedBooking(clientId, masterA, masterServiceA, null);

        String masterBEmail = "provcancel-nc-im-b-" + System.nanoTime() + "@beautica.test";
        createIndependentMaster(masterBEmail);

        ResponseEntity<Void> resp = patchNotComplete(bookingA, tokenFor(masterBEmail));

        assertThat(resp.getStatusCode())
                .as("independent master B must not mark master A's booking not-complete — 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(dbStatus(bookingA)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("Phase 25.9: PATCH /not-complete — 204 when comment is absent from the request body "
            + "(the note is optional for all roles)")
    void should_return204_when_notCompleteHasNoComment() {
        Salon salon = createSalon("provcancel-nc-nocomment-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedSalonBooking(salon);

        ResponseEntity<Void> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/not-complete", HttpMethod.PATCH,
                new HttpEntity<>("{\"cancellationReason\":\"CLIENT_NO_SHOW\"}", bearerHeaders(tokenFor(salon.ownerEmail))),
                Void.class);

        assertThat(resp.getStatusCode())
                .as("marking not-complete with no comment must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(bookingId)).isEqualTo("NOT_COMPLETED");
    }

    // ── guest (LINK) booking decline — Risk R4 regression ─────────────────────────

    /**
     * RED as of this writing — NOT for the reason Risk R4 anticipated. R4 worried about a
     * {@code NullPointerException} inside {@code NotificationService.notifyBookingStatusChanged}
     * (guarded in 24.4, see {@code AbstractIntegrationTest}-based coverage of that guard). This
     * test never gets that far: {@code BookingService.loadBookingOrThrow} calls
     * {@code BookingRepository.findByIdWithFullGraph}, whose JPQL does {@code JOIN FETCH b.client}
     * — an INNER join. {@code Booking.client} is legitimately nullable for a LINK booking
     * ({@code @ManyToOne(fetch = LAZY) @JoinColumn(name = "client_id")}, no {@code nullable = false},
     * per the class javadoc "Nullable since V89: a guest (LINK) booking has no registered
     * account"), so the INNER JOIN FETCH silently excludes every guest booking row and
     * {@code findByIdWithFullGraph} returns empty — {@code loadBookingOrThrow} then throws
     * {@code NotFoundException} → 404, NOT 500. The {@code @PreAuthorize} gate passes correctly
     * first ({@code canCancelBooking} resolves via {@code findCompletionAccessById}, which never
     * touches {@code b.client}), so this is not an authorization bug — it is a data-access bug
     * that 404s the ENTIRE provider-side lifecycle for a guest booking: {@code GET /bookings/{id}}
     * ({@code BookingService.getBooking} uses the same query), {@code /complete}, {@code /decline},
     * and {@code /not-complete} all go through {@code loadBookingOrThrow} and are equally broken.
     * Reported as a CRITICAL finding — do not fix the query in this file (test-only scope); route
     * to {@code backend-dev} for a {@code LEFT JOIN FETCH b.client} fix.
     */
    @Test
    @DisplayName("PATCH /decline on a GUEST (LINK, null-client) booking returns 204 and the outbox drains "
            + "cleanly — no DEAD row (regression for the 24.4 null-client guard in "
            + "NotificationService.notifyBookingStatusChanged)")
    void should_return204AndDrainWithoutDeadRow_when_providerDeclinesGuestBooking() {
        Salon salon = createSalon("provcancel-guest-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedGuestSalonBooking(salon);

        assertThat(outboxRepository.count())
                .as("outbox must be empty before the decline — AbstractIntegrationTest.cleanDb() ran")
                .isZero();

        ResponseEntity<Void> resp = patchDecline(bookingId, tokenFor(salon.ownerEmail));

        assertThat(resp.getStatusCode())
                .as("provider declining a guest booking must return 204 — NOT 404. A 404 here means "
                        + "BookingRepository.findByIdWithFullGraph's INNER `JOIN FETCH b.client` is "
                        + "excluding this null-client LINK booking (see method javadoc for the full "
                        + "root-cause writeup); the fix is LEFT JOIN FETCH")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(bookingId)).isEqualTo("DECLINED");

        List<NotificationOutboxEntry> pending = outboxRepository.findAll();
        assertThat(pending)
                .as("declineBooking must enqueue exactly one STATUS_CHANGED row for the guest booking")
                .hasSize(1);

        // Drain for real — NotificationService/NotificationOutboxService are NOT mocked in this
        // class, so this exercises the real guard: without it, getClient().getEmail() NPEs inside
        // dispatch and the drain worker marks the row DEAD after MAX_ATTEMPTS.
        drainWorker.drain();

        NotificationOutboxEntry drained = outboxRepository.findById(pending.get(0).getId()).orElseThrow();
        assertThat(drained.getStatus())
                .as("the null-client guard must let the guest-booking STATUS_CHANGED entry drain to "
                        + "SENT (a clean no-op dispatch), never DEAD")
                .isEqualTo(OutboxStatus.SENT);
        assertThat(drained.getLastError()).isNull();

        long deadRows = outboxRepository.findAll().stream()
                .filter(e -> e.getStatus() == OutboxStatus.DEAD)
                .count();
        assertThat(deadRows)
                .as("zero DEAD rows must exist anywhere in the outbox after draining a guest-booking decline")
                .isZero();

        // Phase 25.7: a declined guest booking is told via SMS — the one notification channel
        // available for an account-less LINK booking.
        org.mockito.Mockito.verify(smsService, org.mockito.Mockito.times(1))
                .send(org.mockito.ArgumentMatchers.eq("+380509998877"), org.mockito.ArgumentMatchers.anyString());
    }

    /**
     * Phase 25.9 — the provider's note is now optional for all roles. Mirrors
     * {@link #should_return204AndDrainWithoutDeadRow_when_providerDeclinesGuestBooking} exactly,
     * but omits {@code comment} from the request body entirely: a guest (LINK, null-client)
     * booking has no email/push channel, so the decline SMS is the ONLY notification this actor
     * ever receives — it must still send, drain cleanly (no DEAD row), and read coherently with
     * no dangling "Причина: " label.
     */
    @Test
    @DisplayName("Phase 25.9: PATCH /decline on a GUEST (LINK, null-client) booking with NO comment "
            + "returns 204, sends a coherent SMS with no dangling reason clause, and drains the "
            + "outbox cleanly — no DEAD row")
    void should_return204AndDrainWithoutDeadRow_when_providerDeclinesGuestBookingWithNoComment() {
        Salon salon = createSalon("provcancel-guest-nocomment-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertConfirmedGuestSalonBooking(salon);

        ResponseEntity<Void> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>("{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\"}", bearerHeaders(tokenFor(salon.ownerEmail))),
                Void.class);

        assertThat(resp.getStatusCode())
                .as("provider declining a guest booking with no comment must return 204")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(bookingId)).isEqualTo("DECLINED");

        List<NotificationOutboxEntry> pending = outboxRepository.findAll();
        assertThat(pending).hasSize(1);

        drainWorker.drain();

        NotificationOutboxEntry drained = outboxRepository.findById(pending.get(0).getId()).orElseThrow();
        assertThat(drained.getStatus())
                .as("a guest decline with no note must still drain to SENT, never DEAD")
                .isEqualTo(OutboxStatus.SENT);
        assertThat(drained.getLastError()).isNull();

        long deadRows = outboxRepository.findAll().stream()
                .filter(e -> e.getStatus() == OutboxStatus.DEAD)
                .count();
        assertThat(deadRows)
                .as("zero DEAD rows after draining a no-comment guest decline")
                .isZero();

        ArgumentCaptor<String> textCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(smsService, org.mockito.Mockito.times(1))
                .send(org.mockito.ArgumentMatchers.eq("+380509998877"), textCaptor.capture());
        assertThat(textCaptor.getValue())
                .as("a null note must never leave a dangling \"Причина: \" label in the SMS")
                .doesNotContain("Причина");
    }

    // ── state-machine bypass (assertTransition) over HTTP ─────────────────────────

    @Test
    @DisplayName("PATCH /decline — 400 when the booking is already COMPLETED (terminal, non-CONFIRMED source)")
    void should_return400_when_decliningAlreadyCompletedBooking() {
        Salon salon = createSalon("provcancel-bypass-decl-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertSalonBookingWithStatus(salon, "COMPLETED");

        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>(
                        "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\",\"comment\":\"Спроба скасувати завершений запис\"}",
                        bearerHeaders(tokenFor(salon.ownerEmail))),
                String.class);

        assertThat(resp.getStatusCode())
                .as("declining an already-COMPLETED booking must be rejected with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(dbStatus(bookingId))
                .as("status must remain COMPLETED after a rejected decline")
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("PATCH /complete — 400 when the booking is already DECLINED (terminal, non-CONFIRMED source)")
    void should_return400_when_completingAlreadyDeclinedBooking() {
        Salon salon = createSalon("provcancel-bypass-compl-owner-" + System.nanoTime() + "@beautica.test");
        UUID bookingId = insertSalonBookingWithStatus(salon, "DECLINED");

        ResponseEntity<Void> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/complete", HttpMethod.PATCH,
                new HttpEntity<>(bearerHeaders(tokenFor(salon.ownerEmail))), Void.class);

        assertThat(resp.getStatusCode())
                .as("completing an already-DECLINED booking must be rejected with 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(dbStatus(bookingId))
                .as("status must remain DECLINED after a rejected completion")
                .isEqualTo("DECLINED");
    }

    // ── HTTP helpers ─────────────────────────────────────────────────────────────

    private ResponseEntity<Void> patchDecline(UUID bookingId, String token) {
        return restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>(
                        "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\",\"comment\":\"Провайдер недоступний\"}",
                        bearerHeaders(token)),
                Void.class);
    }

    private ResponseEntity<Void> patchNotComplete(UUID bookingId, String token) {
        return restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/not-complete", HttpMethod.PATCH,
                new HttpEntity<>(
                        "{\"cancellationReason\":\"CLIENT_NO_SHOW\",\"comment\":\"Клієнт не з'явився\"}",
                        bearerHeaders(token)),
                Void.class);
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
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    // ── seeding fixtures (local by house convention — mirrors BookingCompletionSecurityIT) ───────

    private record Salon(UUID salonId, String ownerEmail, UUID masterId, String masterEmail) {}

    private Salon createSalon(String ownerEmail) {
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);

        String masterEmail = "provcancel-master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return new Salon(salonId, ownerEmail, masterId, masterEmail);
    }

    private UUID createIndependentMaster(String email) {
        UUID userId = createUser(email, "INDEPENDENT_MASTER", null);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
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

    private UUID createSalonService(UUID salonId) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        UUID masterId = jdbcTemplate.queryForObject("SELECT id FROM masters WHERE salon_id = ? LIMIT 1", UUID.class, salonId);
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    private UUID createIndependentMasterService(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
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

    private UUID insertConfirmedSalonBooking(Salon salon) {
        UUID clientId = createUser("provcancel-book-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterServiceId = createSalonService(salon.salonId);
        return insertConfirmedBooking(clientId, salon.masterId, masterServiceId, salon.salonId);
    }

    /**
     * Phase 27.1: {@code starts_at} is in the FUTURE, not the past. This fixture backs
     * {@code /decline} tests in this class (never {@code /complete}), and {@code declineBooking}
     * gained a new {@code assertFutureForProviderCancel} guard (now &lt; startsAt) — an elapsed
     * booking would now 409 before reaching the authorization assertions this class exists to
     * pin. {@code /not-complete} does not care either way (that endpoint is untouched by track 27).
     */
    private UUID insertConfirmedBooking(UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, " +
                        "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, " +
                        "booking_source, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', NOW() + interval '2 hours', NOW() + interval '3 hours', " +
                        "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId);
        return bookingId;
    }

    private UUID insertSalonBookingWithStatus(Salon salon, String status) {
        UUID clientId = createUser("provcancel-bypass-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
        UUID masterServiceId = createSalonService(salon.salonId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, " +
                        "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, " +
                        "booking_source, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, NOW() - interval '2 hours', NOW() - interval '1 hour', " +
                        "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, salon.masterId, masterServiceId, salon.salonId, status);
        return bookingId;
    }

    /**
     * Seeds a CONFIRMED guest (LINK) booking directly against the salon's master: {@code client_id}
     * NULL, {@code booking_source = 'LINK'}, guest fields populated, and a non-null
     * {@code cancel_token} (V91 CHECK requires it for any ACTIVE — i.e. CONFIRMED — LINK row).
     */
    private UUID insertConfirmedGuestSalonBooking(Salon salon) {
        UUID masterServiceId = createSalonService(salon.salonId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, " +
                        "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, " +
                        "booking_source, guest_name, guest_surname, guest_phone, cancel_token, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, 'CONFIRMED', NOW() + interval '1 day', NOW() + interval '1 day 1 hour', " +
                        "500.00, 60, 0, 'LINK', 'Гість', 'Тестовий', '+380509998877', ?, NOW(), NOW())",
                bookingId, salon.masterId, masterServiceId, salon.salonId, UUID.randomUUID());
        return bookingId;
    }

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject("SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }
}
