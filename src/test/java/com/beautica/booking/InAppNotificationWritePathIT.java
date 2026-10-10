package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 333 — end-to-end proof of the in-app feed write path, one test per matrix row of
 * {@code docs/backend-phases/phase-333-inapp-notification-write-path.md}, against a real Postgres
 * and the real {@link InAppNotificationService} bean (never mocked — every assertion reads the
 * {@code in_app_notification} table directly, the same lens {@code InAppNotificationRepositoryIT}
 * and {@code StaffBookingEndpointIT$InAppFeed} use).
 *
 * <p>Walk-in (row 1w) coverage and the owner-as-performing-master actor-exclusion case for CREATE
 * live in {@code StaffBookingEndpointIT$InAppFeed} — the walk-in endpoint's own suite, which already
 * carries the fixtures for an invited {@code SALON_MASTER} and a real HTTP staff-create round trip.
 * This class covers every OTHER matrix row plus the cross-cutting rules (actor exclusion, visit
 * granularity, same-transaction atomicity) that are not row-specific.
 */
@Import(TestSecurityConfig.class)
@DisplayName("InAppNotificationWritePathIT — phase 333 event -> recipient matrix, over real HTTP + Postgres")
class InAppNotificationWritePathIT extends AbstractIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private GuestTokenProvider guestTokenProvider;

    @Autowired
    private InAppNotificationService inAppNotificationService;

    @Autowired
    private BookingRepository bookingRepository;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ── fixture: one salon with an owner, an invited SALON_MASTER, an admin and a client ──────

    private record SalonRig(UUID salonId, UUID ownerId, String ownerEmail, UUID masterId, UUID masterUserId,
                             String masterEmail, UUID adminId, String adminEmail, UUID masterServiceId) {
    }

    private SalonRig seedSalonRig() {
        BookingTestFixtures.SalonFixture salon = fixtures.createSalon("rig-owner-" + System.nanoTime() + "@beautica.test");
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salon.salonId());
        UUID masterUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, salon.masterId());
        String adminEmail = "rig-admin-" + System.nanoTime() + "@beautica.test";
        UUID adminId = fixtures.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID masterServiceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        fixtures.addWorkingHoursForEveryDay(salon.masterId());
        return new SalonRig(salon.salonId(), ownerId, salon.ownerEmail(), salon.masterId(), masterUserId,
                salon.masterEmail(), adminId, adminEmail, masterServiceId);
    }

    private UUID createClient() throws Exception {
        return fixtures.createUser("rig-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null);
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────────────────────

    private static final String BOOKINGS_URL = "/api/v1/bookings";

    private HttpHeaders headers(String token) {
        return fixtures.bearerHeaders(token);
    }

    private UUID createBooking(String clientToken, UUID masterId, UUID masterServiceId, ZonedDateTime startsAt)
            throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "masterId", masterId.toString(),
                "masterServiceId", masterServiceId.toString(),
                "startsAt", startsAt.toOffsetDateTime().toString()));
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST, new HttpEntity<>(body, headers(clientToken)), String.class);
        assertThat(resp.getStatusCode()).as("booking create must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(resp.getBody()).path("data").path("id").asText());
    }

    private ZonedDateTime tomorrowAtNoon() {
        return ZonedDateTime.now().plusDays(2).withHour(12).withMinute(0).withSecond(0).withNano(0);
    }

    /**
     * A walk-in ({@code BookingSource.STAFF}) booking via the real {@code POST
     * /masters/{masterId}/bookings} endpoint, returning its APPOINTMENT id — the counterpart of
     * {@link #createBooking} for the matrix's walk-in decline/reschedule rows (create itself is
     * covered by {@code StaffBookingEndpointIT$InAppFeed}, which owns that endpoint's own suite).
     *
     * <p><b>Appointment id, never a booking id.</b> {@code StaffBookingService#createStaffBooking}
     * ALWAYS creates a visit header, even at N = 1 (no size-based short-circuit — see that class's
     * own javadoc), so every walk-in is an appointment CHILD. {@code PATCH
     * /bookings/{id}/decline}/{@code /reschedule} therefore 409s any walk-in via {@code
     * BookingService#assertNotAppointmentChild} ("use /appointments/{id}"); the walk-in
     * decline/reschedule tests below correctly use the {@code /appointments/{appointmentId}/...}
     * endpoints instead, exactly as a real caller must.
     */
    private UUID createWalkIn(String staffToken, UUID masterId, UUID masterServiceId, ZonedDateTime startsAt) {
        String body = """
                {"masterServiceIds":["%s"],"startsAt":"%s",
                 "guest":{"name":"Оксана","surname":"Гончар","phone":"+380501234567"}}
                """.formatted(masterServiceId, startsAt.toOffsetDateTime());
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/bookings", HttpMethod.POST,
                new HttpEntity<>(body, headers(staffToken)), String.class);
        assertThat(resp.getStatusCode()).as("walk-in create must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        try {
            return UUID.fromString(objectMapper.readTree(resp.getBody()).path("data").path("id").asText());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse walk-in response: " + resp.getBody(), e);
        }
    }

    private ResponseEntity<String> patch(String path, String token, String body) {
        HttpHeaders h = headers(token);
        return restTemplate.exchange(BOOKINGS_URL + path, HttpMethod.PATCH,
                new HttpEntity<>(body == null ? "" : body, h), String.class);
    }

    private List<Map<String, Object>> feedRows() {
        return jdbcTemplate.queryForList("SELECT * FROM in_app_notification");
    }

    private List<UUID> recipientsOf(List<Map<String, Object>> rows) {
        return rows.stream().map(r -> (UUID) r.get("recipient_user_id")).toList();
    }

    private List<Map<String, Object>> rowsOfType(String type) {
        return jdbcTemplate.queryForList("SELECT * FROM in_app_notification WHERE type = ?", type);
    }

    // ── row 1 — BOOKING_CREATED, client APP booking ────────────────────────────────────────

    @Test
    @DisplayName("row 1 — client creates an APP booking: provider set (owner+admin+master) notified, "
            + "client excluded as actor")
    void should_notifyProviderSet_when_clientCreatesAppBooking() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createClient();

        createBooking(fixtures.tokenFor(emailOf(clientId)), rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r -> assertThat(r.get("type")).isEqualTo("BOOKING_CREATED"));
        assertThat(recipientsOf(rows))
                .containsExactlyInAnyOrder(rig.ownerId(), rig.adminId(), rig.masterUserId());
    }

    // ── row 2 — BOOKING_CANCELLED_BY_CLIENT ────────────────────────────────────────────────

    @Test
    @DisplayName("row 2 — client cancels: provider set notified, client excluded as actor")
    void should_notifyProviderSet_when_clientCancels() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        UUID bookingId = createBooking(clientToken, rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification"); // isolate the CANCEL event from the CREATE rows

        String body = objectMapper.writeValueAsString(Map.of("cancellationReason", "CLIENT_CANCELLED"));
        ResponseEntity<String> resp = patch("/" + bookingId + "/cancel", clientToken, body);
        assertThat(resp.getStatusCode()).as("cancel must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r -> assertThat(r.get("type")).isEqualTo("BOOKING_CANCELLED_BY_CLIENT"));
        assertThat(recipientsOf(rows))
                .containsExactlyInAnyOrder(rig.ownerId(), rig.adminId(), rig.masterUserId());
    }

    // ── row 3 — BOOKING_DECLINED ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("row 3 — owner declines a client's booking: client + performing master notified, "
            + "owner excluded as actor, admin gets nothing")
    void should_notifyClientAndPerformingMaster_when_ownerDeclinesClientBooking() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createClient();
        UUID bookingId = createBooking(
                fixtures.tokenFor(emailOf(clientId)), rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        String declineBody = objectMapper.writeValueAsString(Map.of("cancellationReason", "PROVIDER_UNAVAILABLE"));
        ResponseEntity<String> resp = patch("/" + bookingId + "/decline",
                fixtures.tokenFor(rig.ownerEmail()), declineBody);
        assertThat(resp.getStatusCode()).as("decline must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(r.get("type")).isEqualTo("BOOKING_DECLINED"));
        assertThat(recipientsOf(rows)).containsExactlyInAnyOrder(clientId, rig.masterUserId());
    }

    @Test
    @DisplayName("row 3 rule 6 — the performing (INDEPENDENT_MASTER) provider declines their OWN "
            + "booking: only the client is notified, the master gets nothing (actor exclusion)")
    void should_notNotifyPerformingMaster_when_theyDeclineTheirOwnBooking() throws Exception {
        // SALON_MASTER cannot decline at all (read-only calendar — CLAUDE.md domain rule, and the
        // controller's role gate excludes it), so the only reachable "performing provider declines
        // their own booking" shape is an INDEPENDENT_MASTER.
        String masterEmail = "self-decline-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        fixtures.addWorkingHoursForEveryDay(masterId);
        UUID masterUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID clientId = createClient();
        UUID bookingId = createBooking(
                fixtures.tokenFor(emailOf(clientId)), masterId, masterServiceId, tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        String declineBody = objectMapper.writeValueAsString(Map.of("cancellationReason", "PROVIDER_UNAVAILABLE"));
        ResponseEntity<String> resp = patch("/" + bookingId + "/decline", fixtures.tokenFor(masterEmail), declineBody);
        assertThat(resp.getStatusCode()).as("decline must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("recipient_user_id")).isEqualTo(clientId);
        assertThat(rows.get(0).get("type")).isEqualTo("BOOKING_DECLINED");
        assertThat(recipientsOf(rows)).doesNotContain(masterUserId);
    }

    // ── row 6 — BOOKING_NOT_COMPLETED ──────────────────────────────────────────────────────

    @Test
    @DisplayName("row 6 — provider marks a no-show: client notified, provider (actor) excluded")
    void should_notifyClient_when_notCompleted() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createClient();
        UUID bookingId = createBooking(
                fixtures.tokenFor(emailOf(clientId)), rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        String body = objectMapper.writeValueAsString(Map.of("cancellationReason", "CLIENT_NO_SHOW"));
        ResponseEntity<String> resp = patch("/" + bookingId + "/not-complete", fixtures.tokenFor(rig.ownerEmail()), body);
        assertThat(resp.getStatusCode()).as("not-complete must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("recipient_user_id")).isEqualTo(clientId);
        assertThat(rows.get(0).get("type")).isEqualTo("BOOKING_NOT_COMPLETED");
    }

    // ── row 4 — BOOKING_RESCHEDULED ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("row 4 — provider reschedules: client + performing master notified (provider excluded "
            + "if it is the master; here the OWNER acts, so the master's own row IS present)")
    void should_notifyCounterpart_when_rescheduledByProvider() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createClient();
        UUID bookingId = createBooking(
                fixtures.tokenFor(emailOf(clientId)), rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        String body = objectMapper.writeValueAsString(
                Map.of("newStartsAt", tomorrowAtNoon().plusHours(2).toOffsetDateTime().toString()));
        ResponseEntity<String> resp = patch("/" + bookingId + "/reschedule", fixtures.tokenFor(rig.ownerEmail()), body);
        assertThat(resp.getStatusCode()).as("reschedule must succeed — body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(r.get("type")).isEqualTo("BOOKING_RESCHEDULED"));
        assertThat(recipientsOf(rows)).containsExactlyInAnyOrder(clientId, rig.masterUserId());
    }

    @Test
    @DisplayName("row 4 — client reschedules: owner + admin + performing master notified, client excluded")
    void should_notifyCounterpart_when_rescheduledByClient() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        UUID bookingId = createBooking(clientToken, rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        String body = objectMapper.writeValueAsString(
                Map.of("newStartsAt", tomorrowAtNoon().plusHours(2).toOffsetDateTime().toString()));
        ResponseEntity<String> resp = patch("/" + bookingId + "/reschedule", clientToken, body);
        assertThat(resp.getStatusCode()).as("reschedule must succeed — body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r -> assertThat(r.get("type")).isEqualTo("BOOKING_RESCHEDULED"));
        assertThat(recipientsOf(rows))
                .containsExactlyInAnyOrder(rig.ownerId(), rig.adminId(), rig.masterUserId());
    }

    // ── walk-in decline / reschedule — matrix rule "Walk-in / STAFF source: only the performing
    // salon master, only when someone else acted" (rows 3/4), complementing row 1w's create-only
    // coverage in StaffBookingEndpointIT$InAppFeed ─────────────────────────────────────────────

    @Test
    @DisplayName("walk-in decline — admin declines an invited SALON_MASTER's walk-in: only the "
            + "performing master is notified; no client row (walk-ins have none), owner/admin excluded")
    void should_notifyPerformingMaster_when_adminDeclinesWalkIn() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID appointmentId = createWalkIn(
                fixtures.tokenFor(rig.adminEmail()), rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        // Whole-visit decline — a walk-in is ALWAYS an appointment child (see createWalkIn's own
        // javadoc), so PATCH /bookings/{id}/decline is the wrong endpoint here and 409s.
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/appointments/" + appointmentId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>("{}", headers(fixtures.tokenFor(rig.ownerEmail()))), String.class);
        assertThat(resp.getStatusCode()).as("decline must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("recipient_user_id")).isEqualTo(rig.masterUserId());
        assertThat(rows.get(0).get("type")).isEqualTo("BOOKING_DECLINED");
        assertThat(rows.get(0).get("appointment_id")).isEqualTo(appointmentId);
    }

    @Test
    @DisplayName("walk-in reschedule — admin reschedules an invited SALON_MASTER's walk-in: only "
            + "the performing master is notified")
    void should_notifyPerformingMaster_when_adminReschedulesWalkIn() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID appointmentId = createWalkIn(
                fixtures.tokenFor(rig.adminEmail()), rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        String body = objectMapper.writeValueAsString(
                Map.of("newStartsAt", tomorrowAtNoon().plusHours(2).toOffsetDateTime().toString()));
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/appointments/" + appointmentId + "/reschedule", HttpMethod.PATCH,
                new HttpEntity<>(body, headers(fixtures.tokenFor(rig.ownerEmail()))), String.class);
        assertThat(resp.getStatusCode()).as("reschedule must succeed — body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("recipient_user_id")).isEqualTo(rig.masterUserId());
        assertThat(rows.get(0).get("type")).isEqualTo("BOOKING_RESCHEDULED");
        assertThat(rows.get(0).get("appointment_id")).isEqualTo(appointmentId);
    }

    // ── row 7 — REVIEW_REQUESTED ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("row 7 — booking completed: client notified with a review prompt")
    void should_notifyClient_when_completedReviewRequested() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createClient();
        UUID bookingId = createBooking(
                fixtures.tokenFor(emailOf(clientId)), rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        // Simulate the appointment having begun (27.1: complete unlocks once now >= startsAt) — the
        // SAME time-shift trick BookingIntegrationTest#should_completeBooking_when_confirmedStatusFlow uses,
        // rather than waiting 2 real days.
        jdbcTemplate.update("UPDATE bookings SET starts_at = NOW() - interval '1 hour' WHERE id = ?", bookingId);
        jdbcTemplate.update("DELETE FROM in_app_notification");

        ResponseEntity<String> resp = patch("/" + bookingId + "/complete", fixtures.tokenFor(rig.ownerEmail()), "");
        assertThat(resp.getStatusCode()).as("complete must succeed — body=%s", resp.getBody()).isEqualTo(HttpStatus.NO_CONTENT);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("recipient_user_id")).isEqualTo(clientId);
        assertThat(rows.get(0).get("type")).isEqualTo("REVIEW_REQUESTED");
    }

    // ── row 8 — REVIEW_RECEIVED ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("row 8 — client leaves a review: rated master + owner notified, admin gets NOTHING, "
            + "client excluded as actor")
    void should_notifyMasterAndOwner_when_reviewReceived() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        UUID bookingId = createBooking(clientToken, rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("UPDATE bookings SET starts_at = NOW() - interval '1 hour' WHERE id = ?", bookingId);
        ResponseEntity<String> completeResp =
                patch("/" + bookingId + "/complete", fixtures.tokenFor(rig.ownerEmail()), "");
        assertThat(completeResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        jdbcTemplate.update("DELETE FROM in_app_notification");

        String reviewBody = objectMapper.writeValueAsString(
                Map.of("bookingId", bookingId.toString(), "rating", 5, "comment", "Great!"));
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/reviews", HttpMethod.POST, new HttpEntity<>(reviewBody, headers(clientToken)), String.class);
        assertThat(resp.getStatusCode()).as("review create must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(r.get("type")).isEqualTo("REVIEW_RECEIVED"));
        assertThat(recipientsOf(rows)).containsExactlyInAnyOrder(rig.ownerId(), rig.masterUserId());
    }

    // ── row 5 — INVITE_ACCEPTED ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("row 5 — invite accepted: owner + every OTHER admin notified, the new teammate excluded")
    void should_notifyOwnerAndAdmins_notInvitee_when_inviteAccepted() throws Exception {
        SalonRig rig = seedSalonRig(); // owner + one existing admin already seeded

        String rawToken = "raw-invite-" + UUID.randomUUID();
        String inviteeEmail = "rig-invitee-" + System.nanoTime() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO invite_tokens (id, token, email, salon_id, role, expires_at, is_used, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'SALON_ADMIN', ?, false, NOW(), NOW())",
                UUID.randomUUID(), sha256Hex(rawToken), inviteeEmail, rig.salonId(),
                Timestamp.from(Instant.now().plus(2, ChronoUnit.DAYS)));

        ResponseEntity<String> resp = restTemplate.postForEntity("/api/v1/auth/invite/accept",
                new InviteAcceptRequest(rawToken, "Str0ngP@ss1!", "Invited", "Admin", "+380501234567"),
                String.class);
        assertThat(resp.getStatusCode()).as("invite accept must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        UUID newAdminId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", UUID.class, inviteeEmail);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.get("type")).isEqualTo("INVITE_ACCEPTED");
            assertThat(r.get("subject_user_id")).isEqualTo(newAdminId);
        });
        assertThat(recipientsOf(rows)).containsExactlyInAnyOrder(rig.ownerId(), rig.adminId());
    }

    // ── row 9 / row 10 — deletion cascades ──────────────────────────────────────────────────

    @Test
    @DisplayName("row 9 — salon closed: the client's future booking notifies them, actor (owner) excluded")
    void should_notifyClient_when_salonClosed() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createClient();
        createBooking(fixtures.tokenFor(emailOf(clientId)), rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        ResponseEntity<Void> resp = restTemplate.exchange(
                "/api/v1/salons/" + rig.salonId(), HttpMethod.DELETE,
                new HttpEntity<>(headers(fixtures.tokenFor(rig.ownerEmail()))), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("recipient_user_id")).isEqualTo(clientId);
        assertThat(rows.get(0).get("type")).isEqualTo("BOOKING_CANCELLED_SALON_CLOSED");
    }

    @Test
    @DisplayName("row 10 — owner removes the salon master: the client's future booking notifies them")
    void should_notifyClient_when_ownerRemovesSalonMaster() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createClient();
        createBooking(fixtures.tokenFor(emailOf(clientId)), rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        ResponseEntity<Void> resp = restTemplate.exchange(
                "/api/v1/salons/" + rig.salonId() + "/masters/" + rig.masterId(), HttpMethod.DELETE,
                new HttpEntity<>(headers(fixtures.tokenFor(rig.ownerEmail()))), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("recipient_user_id")).isEqualTo(clientId);
        assertThat(rows.get(0).get("type")).isEqualTo("BOOKING_CANCELLED_MASTER_REMOVED");
    }

    @Test
    @DisplayName("row 10 — independent master self-deletes: the client's future booking notifies them")
    void should_notifyClient_when_independentMasterDeletesAccount() throws Exception {
        String masterEmail = "self-del-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID masterServiceId = fixtures.createIndependentMasterService(masterId);
        fixtures.addWorkingHoursForEveryDay(masterId);
        UUID clientId = createClient();
        createBooking(fixtures.tokenFor(emailOf(clientId)), masterId, masterServiceId, tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        ResponseEntity<Void> resp = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(headers(fixtures.tokenFor(masterEmail))), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("recipient_user_id")).isEqualTo(clientId);
        assertThat(rows.get(0).get("type")).isEqualTo("BOOKING_CANCELLED_MASTER_REMOVED");
    }

    // ── audit-fix cycle 1, finding 7 — two doc-listed tests missing from this suite ────────────

    @Test
    @DisplayName("row 1 (guest/LINK) — a guest creates a booking via the public /book/{slug} link: "
            + "the provider set is notified, never the guest (no account to notify)")
    void should_notifyProviderSetNotClient_when_guestLinkBooking() throws Exception {
        SalonRig rig = seedSalonRig();
        String slug = "guest-link-" + System.nanoTime();
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", slug, rig.masterId());
        String guestToken = guestTokenProvider.generate("+380501234567");
        String body = objectMapper.writeValueAsString(Map.of(
                "serviceId", rig.masterServiceId().toString(),
                "startsAt", tomorrowAtNoon().toOffsetDateTime().toString(),
                "name", "Оксана",
                "surname", "Гончар"));

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/book/" + slug + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, headers(guestToken)), String.class);
        assertThat(resp.getStatusCode()).as("guest booking create must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        UUID bookingId = UUID.fromString(objectMapper.readTree(resp.getBody()).path("bookingId").asText());

        String clientIdColumn = jdbcTemplate.queryForObject(
                "SELECT client_id FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(clientIdColumn)
                .as("premise — a guest/LINK booking has no client_id; this test would silently degrade "
                        + "into the already-covered row-1 APP case otherwise")
                .isNull();

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r -> assertThat(r.get("type")).isEqualTo("BOOKING_CREATED"));
        assertThat(recipientsOf(rows))
                .as("provider set only — owner, admin, performing master; no guest row exists to omit, "
                        + "but the assertion pins the set is exactly these three, never a fourth row")
                .containsExactlyInAnyOrder(rig.ownerId(), rig.adminId(), rig.masterUserId());
    }

    @Test
    @DisplayName("master self-delete cascade — a GUEST and a WALK-IN future booking are both excluded "
            + "from the client-only fan-out (both have client_id = NULL): nobody is notified")
    void should_notifyNobody_when_deletedMastersFutureBookingIsGuestOrWalkIn() throws Exception {
        // Leg 1 — a guest (LINK) booking on a self-deleting independent master.
        String guestMasterEmail = "self-del-guest-master-" + System.nanoTime() + "@beautica.test";
        UUID guestMasterId = fixtures.createIndependentMaster(guestMasterEmail);
        UUID guestMasterServiceId = fixtures.createIndependentMasterService(guestMasterId);
        fixtures.addWorkingHoursForEveryDay(guestMasterId);
        String guestSlug = "self-del-guest-" + System.nanoTime();
        jdbcTemplate.update("UPDATE masters SET booking_slug = ? WHERE id = ?", guestSlug, guestMasterId);
        String guestToken = guestTokenProvider.generate("+380509999999");
        String guestBody = objectMapper.writeValueAsString(Map.of(
                "serviceId", guestMasterServiceId.toString(),
                "startsAt", tomorrowAtNoon().toOffsetDateTime().toString(),
                "name", "Ірина",
                "surname", "Бондар"));
        ResponseEntity<String> guestResp = restTemplate.exchange(
                "/api/v1/book/" + guestSlug + "/booking", HttpMethod.POST,
                new HttpEntity<>(guestBody, headers(guestToken)), String.class);
        assertThat(guestResp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Leg 2 — a walk-in (STAFF) booking on a DIFFERENT self-deleting independent master (Self
        // scope: an INDEPENDENT_MASTER may walk-in book their own calendar).
        String walkInMasterEmail = "self-del-walkin-master-" + System.nanoTime() + "@beautica.test";
        UUID walkInMasterId = fixtures.createIndependentMaster(walkInMasterEmail);
        UUID walkInMasterServiceId = fixtures.createIndependentMasterService(walkInMasterId);
        fixtures.addWorkingHoursForEveryDay(walkInMasterId);
        createWalkIn(fixtures.tokenFor(walkInMasterEmail), walkInMasterId, walkInMasterServiceId, tomorrowAtNoon());

        jdbcTemplate.update("DELETE FROM in_app_notification");

        ResponseEntity<Void> guestMasterDelete = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(headers(fixtures.tokenFor(guestMasterEmail))), Void.class);
        assertThat(guestMasterDelete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<Void> walkInMasterDelete = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(headers(fixtures.tokenFor(walkInMasterEmail))), Void.class);
        assertThat(walkInMasterDelete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(feedRows())
                .as("insertClientOnlyBulk requires client_id IS NOT NULL — a guest AND a walk-in "
                        + "booking both have a null client_id, so BOTH self-delete cascades must write "
                        + "zero feed rows, never a NullPointerException or a row with a null recipient")
                .isEmpty();
    }

    @Test
    @DisplayName("row 2 (client self-delete) — the provider set is notified once per visit, the "
            + "deleting client is the actor and is excluded")
    void should_notifyProviderSetOncePerVisit_when_clientDeletesAccount() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        createBooking(clientToken, rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        ResponseEntity<Void> resp = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE, new HttpEntity<>(headers(clientToken)), Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(r -> assertThat(r.get("type")).isEqualTo("BOOKING_CANCELLED_BY_CLIENT"));
        assertThat(recipientsOf(rows))
                .containsExactlyInAnyOrder(rig.ownerId(), rig.adminId(), rig.masterUserId());
    }

    // ── visit granularity ───────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("visit granularity — a 3-service visit cancel writes exactly ONE row per provider-set "
            + "member, never one per chained booking")
    void should_writeOneRowPerRecipient_when_multiServiceVisitCancelled() throws Exception {
        String masterEmail = "visit-master-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID s1 = fixtures.createIndependentMasterService(masterId, "Service A");
        UUID s2 = fixtures.createIndependentMasterService(masterId, "Service B");
        UUID s3 = fixtures.createIndependentMasterService(masterId, "Service C");
        fixtures.addWorkingHoursForEveryDay(masterId);
        UUID masterUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        String clientEmail = "visit-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = fixtures.createUser(clientEmail, "CLIENT", null);
        String clientToken = fixtures.tokenFor(clientEmail);

        String createBody = objectMapper.writeValueAsString(Map.of(
                "masterId", masterId.toString(),
                "masterServiceIds", List.of(s1.toString(), s2.toString(), s3.toString()),
                "startsAt", tomorrowAtNoon().toOffsetDateTime().toString()));
        ResponseEntity<String> created = restTemplate.exchange(
                "/api/v1/appointments", HttpMethod.POST, new HttpEntity<>(createBody, headers(clientToken)), String.class);
        assertThat(created.getStatusCode()).as("visit create must succeed — body=%s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        UUID appointmentId = UUID.fromString(objectMapper.readTree(created.getBody()).path("data").path("id").asText());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        ResponseEntity<String> cancelResp = restTemplate.exchange(
                "/api/v1/appointments/" + appointmentId + "/cancel", HttpMethod.PATCH,
                new HttpEntity<>("{}", headers(clientToken)), String.class);
        assertThat(cancelResp.getStatusCode()).as("visit cancel must succeed — body=%s", cancelResp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows)
                .as("ONE row for the master, never one per chained booking — a 3-service visit must not "
                        + "become 3 bell items")
                .hasSize(1);
        assertThat(rows.get(0).get("recipient_user_id")).isEqualTo(masterUserId);
        assertThat(rows.get(0).get("appointment_id")).isEqualTo(appointmentId);
        assertThat(rows.get(0).get("booking_id")).isNull();
    }

    @Test
    @DisplayName("visit granularity — a 3-service visit CREATE (salon rig) writes exactly ONE "
            + "BOOKING_CREATED row PER recipient (owner+admin+master), never one per chained service")
    void should_writeOneRowPerRecipient_when_multiServiceVisitCreatedInSalon() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID s2 = fixtures.createSalonService(rig.salonId(), rig.masterId());
        UUID s3 = fixtures.createSalonService(rig.salonId(), rig.masterId());
        UUID clientId = createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));

        String createBody = objectMapper.writeValueAsString(Map.of(
                "masterId", rig.masterId().toString(),
                "masterServiceIds", List.of(rig.masterServiceId().toString(), s2.toString(), s3.toString()),
                "startsAt", tomorrowAtNoon().toOffsetDateTime().toString()));
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/appointments", HttpMethod.POST, new HttpEntity<>(createBody, headers(clientToken)),
                String.class);
        assertThat(resp.getStatusCode()).as("visit create must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows)
                .as("3 services chained into ONE visit must still write exactly one row PER recipient "
                        + "(owner+admin+master), never 3x that — a per-service loop would land at 9")
                .hasSize(3);
        assertThat(rows).allSatisfy(r -> assertThat(r.get("type")).isEqualTo("BOOKING_CREATED"));
        assertThat(recipientsOf(rows))
                .containsExactlyInAnyOrder(rig.ownerId(), rig.adminId(), rig.masterUserId());
    }

    @Test
    @DisplayName("visit granularity — a 3-service visit whole-visit DECLINE (salon rig) writes exactly "
            + "ONE BOOKING_DECLINED row per applicable recipient (client + performing master), never one "
            + "per chained service")
    void should_writeOneRowPerRecipient_when_multiServiceVisitDeclinedInSalon() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID s2 = fixtures.createSalonService(rig.salonId(), rig.masterId());
        UUID s3 = fixtures.createSalonService(rig.salonId(), rig.masterId());
        UUID clientId = createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));

        String createBody = objectMapper.writeValueAsString(Map.of(
                "masterId", rig.masterId().toString(),
                "masterServiceIds", List.of(rig.masterServiceId().toString(), s2.toString(), s3.toString()),
                "startsAt", tomorrowAtNoon().toOffsetDateTime().toString()));
        ResponseEntity<String> created = restTemplate.exchange(
                "/api/v1/appointments", HttpMethod.POST, new HttpEntity<>(createBody, headers(clientToken)),
                String.class);
        assertThat(created.getStatusCode()).as("visit create must succeed — body=%s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        UUID appointmentId = UUID.fromString(objectMapper.readTree(created.getBody()).path("data").path("id").asText());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        ResponseEntity<String> declineResp = restTemplate.exchange(
                "/api/v1/appointments/" + appointmentId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>("{}", headers(fixtures.tokenFor(rig.ownerEmail()))), String.class);
        assertThat(declineResp.getStatusCode()).as("visit decline must succeed — body=%s", declineResp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows)
                .as("3 chained services declined as ONE visit must write exactly one row per recipient, "
                        + "never 3x that — a per-service loop would land at 6, and admin must get NOTHING "
                        + "(row 3's non-acting-owner/admin rule)")
                .hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(r.get("type")).isEqualTo("BOOKING_DECLINED"));
        assertThat(recipientsOf(rows)).containsExactlyInAnyOrder(clientId, rig.masterUserId());
        assertThat(rows.get(0).get("appointment_id")).isEqualTo(appointmentId);
    }

    // ── idempotency ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("idempotency — replaying notifyBookingEvent for the SAME booking+type+actor TWICE "
            + "writes only ONE row per recipient (dedup_key unique constraint's ON CONFLICT DO NOTHING)")
    void should_writeOneRowPerRecipient_when_sameBookingEventReplayed() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createClient();
        UUID bookingId = createBooking(
                fixtures.tokenFor(emailOf(clientId)), rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            Booking booking = bookingRepository.findByIdWithFullGraph(bookingId).orElseThrow();
            inAppNotificationService.notifyBookingEvent(
                    InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, booking, clientId);
            inAppNotificationService.notifyBookingEvent(
                    InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, booking, clientId);
            return null;
        });

        List<Map<String, Object>> rows = feedRows();
        assertThat(rows)
                .as("a retried transition (or a re-entrant call inside one whole-visit loop) must not "
                        + "double every recipient's feed row — the SAME (recipient, dedup_key) pair was "
                        + "written twice here, on purpose")
                .hasSize(3);
        assertThat(recipientsOf(rows))
                .containsExactlyInAnyOrder(rig.ownerId(), rig.adminId(), rig.masterUserId());
    }

    @Test
    @DisplayName("idempotency — a reschedule REPLAYED to the SAME new time collapses to one row per "
            + "recipient; a SECOND reschedule to a DIFFERENT time adds one more distinct row (epoch-"
            + "second dedup suffix)")
    void should_collapseReplayedReschedule_butAddDistinctRow_when_rescheduledToADifferentTime()
            throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createClient();
        UUID bookingId = createBooking(
                fixtures.tokenFor(emailOf(clientId)), rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        Instant t1 = tomorrowAtNoon().plusHours(2).toInstant();
        Instant t2 = tomorrowAtNoon().plusHours(4).toInstant();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        tx.execute(status -> {
            Booking booking = bookingRepository.findByIdWithFullGraph(bookingId).orElseThrow();
            inAppNotificationService.notifyRescheduled(booking, true, t1, rig.ownerId());
            inAppNotificationService.notifyRescheduled(booking, true, t1, rig.ownerId()); // exact replay
            inAppNotificationService.notifyRescheduled(booking, true, t2, rig.ownerId()); // different time
            return null;
        });

        List<Map<String, Object>> clientRows = feedRows().stream()
                .filter(r -> clientId.equals(r.get("recipient_user_id")))
                .toList();
        assertThat(clientRows)
                .as("replay to the SAME time must collapse via ON CONFLICT DO NOTHING; a SECOND "
                        + "reschedule to a DIFFERENT time must add a distinct row (the epoch-second dedup "
                        + "suffix), so 3 calls (2 identical + 1 different) must leave exactly 2 rows, "
                        + "never 1 (over-collapsed) or 3 (not deduped at all)")
                .hasSize(2);
        assertThat(clientRows.stream().map(r -> r.get("dedup_key")).distinct().count())
                .as("the two surviving rows must carry two DIFFERENT dedup_key values (different epoch "
                        + "suffixes), not the same key inserted twice by accident")
                .isEqualTo(2L);
    }

    // ── full client journey (create -> reschedule -> cancel) ───────────────────────────────────

    @Test
    @DisplayName("full client journey — create (with clientComment) -> provider reschedule -> client "
            + "cancel (with a cancellation note): each stage's row set is exactly right, the per-"
            + "recipient total across the whole lifecycle is exactly right, and no row column EVER "
            + "holds either note's text")
    void should_traceFullLifecycle_when_clientBooksProviderReschedulesThenClientCancels() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createClient();
        String clientToken = fixtures.tokenFor(emailOf(clientId));

        String secretCreateNote = "SECRET_CREATE_NOTE_" + UUID.randomUUID();
        String createBody = objectMapper.writeValueAsString(Map.of(
                "masterId", rig.masterId().toString(),
                "masterServiceId", rig.masterServiceId().toString(),
                "startsAt", tomorrowAtNoon().toOffsetDateTime().toString(),
                "clientComment", secretCreateNote));
        ResponseEntity<String> createResp = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST, new HttpEntity<>(createBody, headers(clientToken)), String.class);
        assertThat(createResp.getStatusCode()).as("create must succeed — body=%s", createResp.getBody())
                .isEqualTo(HttpStatus.CREATED);
        UUID bookingId = UUID.fromString(objectMapper.readTree(createResp.getBody()).path("data").path("id").asText());

        List<Map<String, Object>> createdRows = rowsOfType("BOOKING_CREATED");
        assertThat(createdRows).hasSize(3);
        assertThat(recipientsOf(createdRows))
                .containsExactlyInAnyOrder(rig.ownerId(), rig.adminId(), rig.masterUserId());

        String rescheduleBody = objectMapper.writeValueAsString(
                Map.of("newStartsAt", tomorrowAtNoon().plusHours(2).toOffsetDateTime().toString()));
        ResponseEntity<String> rescheduleResp =
                patch("/" + bookingId + "/reschedule", fixtures.tokenFor(rig.ownerEmail()), rescheduleBody);
        assertThat(rescheduleResp.getStatusCode()).as("reschedule must succeed — body=%s", rescheduleResp.getBody())
                .isEqualTo(HttpStatus.OK);

        List<Map<String, Object>> rescheduledRows = rowsOfType("BOOKING_RESCHEDULED");
        assertThat(rescheduledRows).hasSize(2);
        assertThat(recipientsOf(rescheduledRows)).containsExactlyInAnyOrder(clientId, rig.masterUserId());

        String secretCancelNote = "SECRET_CANCEL_NOTE_" + UUID.randomUUID();
        String cancelBody = objectMapper.writeValueAsString(
                Map.of("cancellationReason", "CLIENT_CANCELLED", "comment", secretCancelNote));
        ResponseEntity<String> cancelResp = patch("/" + bookingId + "/cancel", clientToken, cancelBody);
        assertThat(cancelResp.getStatusCode()).as("cancel must succeed — body=%s", cancelResp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        List<Map<String, Object>> cancelledRows = rowsOfType("BOOKING_CANCELLED_BY_CLIENT");
        assertThat(cancelledRows).hasSize(3);
        assertThat(recipientsOf(cancelledRows))
                .containsExactlyInAnyOrder(rig.ownerId(), rig.adminId(), rig.masterUserId());

        List<Map<String, Object>> allRows = feedRows();
        assertThat(allRows).as("3 (create) + 2 (reschedule) + 3 (cancel) across the whole lifecycle")
                .hasSize(8);

        Map<UUID, Long> perRecipient = allRows.stream().collect(java.util.stream.Collectors.groupingBy(
                r -> (UUID) r.get("recipient_user_id"), java.util.stream.Collectors.counting()));
        assertThat(perRecipient)
                .as("owner+admin: CREATE + CANCEL only (excluded as actor from RESCHEDULE, which THEY "
                        + "performed); master: all three stages (never the actor); client: RESCHEDULE "
                        + "only (excluded as actor from CREATE and CANCEL, which THEY performed)")
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        rig.ownerId(), 2L, rig.adminId(), 2L, rig.masterUserId(), 3L, clientId, 1L));

        for (Map<String, Object> row : allRows) {
            for (Object value : row.values()) {
                if (value != null) {
                    assertThat(value.toString())
                            .as("a feed row must never carry note free text (booking notes rule) — "
                                    + "row=%s", row)
                            .doesNotContain(secretCreateNote)
                            .doesNotContain(secretCancelNote);
                }
            }
        }
    }

    // ── same-transaction atomicity ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("MANDATORY propagation — a transition that rolls back writes NO feed row, even though "
            + "the service method itself ran to completion")
    void should_writeNothing_when_transitionRollsBack() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createClient();
        UUID bookingId = createBooking(
                fixtures.tokenFor(emailOf(clientId)), rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification");

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.execute(status -> {
            bookingService().declineBooking(rig.ownerId(), bookingId,
                    new com.beautica.booking.dto.StatusUpdateRequest(
                            com.beautica.booking.enums.CancellationReason.PROVIDER_UNAVAILABLE, null));
            throw new RuntimeException("force rollback — simulates a later failure in the SAME transaction");
        })).isInstanceOf(RuntimeException.class).hasMessageContaining("force rollback");

        assertThat(feedRows())
                .as("the in-app INSERT ran inside the same transaction as the decline — a later rollback "
                        + "must discard both together")
                .isEmpty();
        String statusAfterRollback = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(statusAfterRollback)
                .as("the booking mutation itself must also have rolled back")
                .isEqualTo("CONFIRMED");
    }

    @Autowired
    private com.beautica.booking.service.BookingService bookingServiceBean;

    private com.beautica.booking.service.BookingService bookingService() {
        return bookingServiceBean;
    }

    // Booking-notes rule ("a feed row never carries free text") is pinned schema-level by
    // BookingNoteVisibilityIT#should_neverPersistNote_inFeedRow (phase 333 companion to that
    // class's locked "notes are mutually visible" decision) — not duplicated here.

    // ── helpers ─────────────────────────────────────────────────────────────────────────────

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
