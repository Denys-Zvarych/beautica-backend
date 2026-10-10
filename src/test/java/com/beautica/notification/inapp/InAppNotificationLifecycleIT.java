package com.beautica.notification.inapp;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.booking.BookingTestFixtures;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 336 — the closing end-to-end walk of the whole in-app feed. Every step drives a REAL HTTP
 * transition and then reads EVERY party's feed and unread count back through the REAL read API
 * ({@code GET /notifications}, {@code /unread-count}); rows are never asserted off the table here
 * (that lens belongs to {@code InAppNotificationWritePathIT}, which owns the per-matrix-row proof).
 * What this class adds over the two sibling suites is the whole-lifecycle, per-recipient,
 * per-step cumulative view: it is the only place a recipient's feed is asserted to GROW exactly by
 * the right items, in order, across a multi-step journey — and that the actor is absent at each step.
 *
 * <p>REUSE-FIRST: {@link BookingTestFixtures} for every salon/master/user/token fixture.
 */
@Import(TestSecurityConfig.class)
@DisplayName("InAppNotificationLifecycleIT — phase 336 whole-feed lifecycle over real HTTP, read via the real API")
class InAppNotificationLifecycleIT extends AbstractIntegrationTest {

    private static final String NOTIFICATIONS_URL = "/api/v1/notifications";
    private static final String BOOKINGS_URL = "/api/v1/bookings";

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;

    private BookingTestFixtures fixtures;

    @BeforeEach
    void setUp() {
        fixtures = new BookingTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    private record SalonRig(UUID salonId, UUID ownerId, String ownerEmail, UUID masterId, UUID masterUserId,
                             String masterEmail, UUID adminId, String adminEmail, UUID masterServiceId) {
    }

    private SalonRig seedSalonRig() {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("lc-owner-" + System.nanoTime() + "@beautica.test");
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salon.salonId());
        UUID masterUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, salon.masterId());
        String adminEmail = "lc-admin-" + System.nanoTime() + "@beautica.test";
        UUID adminId = fixtures.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID masterServiceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        fixtures.addWorkingHoursForEveryDay(salon.masterId());
        return new SalonRig(salon.salonId(), ownerId, salon.ownerEmail(), salon.masterId(), masterUserId,
                emailOf(masterUserId), adminId, adminEmail, masterServiceId);
    }

    private String createClientEmail() {
        String email = "lc-client-" + System.nanoTime() + "@beautica.test";
        fixtures.createUser(email, "CLIENT", null);
        return email;
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private ZonedDateTime day2At(int hour) {
        return ZonedDateTime.now(TimeZones.KYIV).plusDays(2).withHour(hour).withMinute(0).withSecond(0).withNano(0);
    }

    private String token(String email) throws Exception {
        return fixtures.tokenFor(email);
    }

    private HttpHeaders headers(String token) {
        return fixtures.bearerHeaders(token);
    }

    // ── HTTP helpers ────────────────────────────────────────────────────────────────────────

    private UUID createBooking(String clientToken, UUID masterId, UUID masterServiceId, ZonedDateTime startsAt)
            throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "masterId", masterId.toString(),
                "masterServiceId", masterServiceId.toString(),
                "startsAt", startsAt.toOffsetDateTime().toString()));
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL, HttpMethod.POST, new HttpEntity<>(body, headers(clientToken)), String.class);
        assertThat(resp.getStatusCode()).as("booking create — body=%s", resp.getBody()).isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(resp.getBody()).path("data").path("id").asText());
    }

    private ResponseEntity<String> patch(String path, String token, String body) {
        return restTemplate.exchange(path, HttpMethod.PATCH,
                new HttpEntity<>(body == null ? "" : body, headers(token)), String.class);
    }

    private void expectStatus(ResponseEntity<String> resp, HttpStatus expected) {
        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(expected);
    }

    private JsonNode feed(String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                NOTIFICATIONS_URL + "?size=50", HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
        assertThat(resp.getStatusCode()).as("feed fetch — body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private List<String> types(String token) throws Exception {
        List<String> out = new ArrayList<>();
        feed(token).forEach(n -> out.add(n.path("type").asText()));
        return out;
    }

    private int unread(String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                NOTIFICATIONS_URL + "/unread-count", HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data").path("count").asInt();
    }

    /** One party's expected cumulative feed: the item types (any order) and the unread count. */
    private void assertFeed(String who, String token, int expectedUnread, String... expectedTypes) throws Exception {
        assertThat(types(token)).as("%s feed", who).containsExactlyInAnyOrder(expectedTypes);
        assertThat(unread(token)).as("%s unread-count", who).isEqualTo(expectedUnread);
    }

    private static boolean absent(JsonNode node) {
        return node.isMissingNode() || node.isNull();
    }

    // ── 1. salon: create -> provider reschedule -> client cancel ───────────────────────────

    @Test
    @DisplayName("salon booking: create -> owner reschedules -> client cancels — every party's feed and "
            + "unread count is exactly right after each step, and the actor of each step is never notified")
    void should_growEachPartysFeedExactly_when_salonBookingIsCreatedRescheduledAndCancelled() throws Exception {
        SalonRig rig = seedSalonRig();
        String clientEmail = createClientEmail();
        String client = token(clientEmail);
        String owner = token(rig.ownerEmail());
        String admin = token(rig.adminEmail());
        String master = token(rig.masterEmail());

        UUID bookingId = createBooking(client, rig.masterId(), rig.masterServiceId(), day2At(12));

        assertFeed("owner", owner, 1, "BOOKING_CREATED");
        assertFeed("admin", admin, 1, "BOOKING_CREATED");
        assertFeed("master", master, 1, "BOOKING_CREATED");
        assertFeed("client (actor)", client, 0);

        String rescheduleBody = objectMapper.writeValueAsString(
                Map.of("newStartsAt", day2At(14).toOffsetDateTime().toString()));
        expectStatus(patch(BOOKINGS_URL + "/" + bookingId + "/reschedule", owner, rescheduleBody), HttpStatus.OK);

        assertFeed("client", client, 1, "BOOKING_RESCHEDULED");
        assertFeed("master", master, 2, "BOOKING_CREATED", "BOOKING_RESCHEDULED");
        assertFeed("owner (actor)", owner, 1, "BOOKING_CREATED");
        assertFeed("admin", admin, 1, "BOOKING_CREATED");
        JsonNode rescheduled = feed(client).get(0);
        assertThat(Instant.parse(rescheduled.path("params").path("startsAt").asText()))
                .as("the client's item must show the NEW start, resolved at read time")
                .isEqualTo(day2At(14).toInstant());

        String cancelBody = objectMapper.writeValueAsString(Map.of("cancellationReason", "CLIENT_CANCELLED"));
        expectStatus(patch(BOOKINGS_URL + "/" + bookingId + "/cancel", client, cancelBody), HttpStatus.NO_CONTENT);

        assertFeed("owner", owner, 2, "BOOKING_CREATED", "BOOKING_CANCELLED_BY_CLIENT");
        assertFeed("admin", admin, 2, "BOOKING_CREATED", "BOOKING_CANCELLED_BY_CLIENT");
        assertFeed("master", master, 3, "BOOKING_CREATED", "BOOKING_RESCHEDULED", "BOOKING_CANCELLED_BY_CLIENT");
        assertFeed("client (actor of cancel)", client, 1, "BOOKING_RESCHEDULED");
        for (JsonNode item : feed(owner)) {
            assertThat(item.path("target").path("bookingId").asText()).isEqualTo(bookingId.toString());
        }
    }

    // ── 2. independent master: create -> decline ─────────────────────────────────────────────

    @Test
    @DisplayName("independent master: client books -> master declines — master sees the create, client sees "
            + "the decline, the declining master (actor) gets nothing extra, no salon name is rendered")
    void should_notifyMasterThenClient_when_independentMasterBookingIsCreatedThenDeclined() throws Exception {
        String masterEmail = "lc-indep-" + System.nanoTime() + "@beautica.test";
        UUID masterId = fixtures.createIndependentMaster(masterEmail);
        UUID serviceId = fixtures.createIndependentMasterService(masterId);
        fixtures.addWorkingHoursForEveryDay(masterId);
        String client = token(createClientEmail());
        String master = token(masterEmail);

        UUID bookingId = createBooking(client, masterId, serviceId, day2At(12));

        assertFeed("independent master", master, 1, "BOOKING_CREATED");
        assertFeed("client (actor)", client, 0);
        assertThat(absent(feed(master).get(0).path("target").path("salonId")))
                .as("an independent-master event carries no salon id").isTrue();

        String declineBody = objectMapper.writeValueAsString(Map.of("cancellationReason", "PROVIDER_UNAVAILABLE"));
        expectStatus(patch(BOOKINGS_URL + "/" + bookingId + "/decline", master, declineBody), HttpStatus.NO_CONTENT);

        assertFeed("client", client, 1, "BOOKING_DECLINED");
        assertFeed("independent master (actor)", master, 1, "BOOKING_CREATED");
        JsonNode declined = feed(client).get(0);
        assertThat(declined.path("target").path("kind").asText()).isEqualTo("BOOKING");
        assertThat(absent(declined.path("params").path("salonName")))
                .as("no salon name for an independent-master booking").isTrue();
    }

    // ── 3. complete -> review requested -> review -> review received ─────────────────────────

    @Test
    @DisplayName("complete -> REVIEW_REQUESTED (client only, review-CTA target) -> client reviews -> "
            + "REVIEW_RECEIVED for master + owner, never the admin")
    void should_requestThenReceiveReview_when_bookingCompletedAndClientReviews() throws Exception {
        SalonRig rig = seedSalonRig();
        String client = token(createClientEmail());
        String owner = token(rig.ownerEmail());
        String admin = token(rig.adminEmail());
        String master = token(rig.masterEmail());
        UUID bookingId = createBooking(client, rig.masterId(), rig.masterServiceId(), day2At(12));
        // The SAME time-shift InAppNotificationWritePathIT uses: 27.1 unlocks complete once startsAt has passed.
        jdbcTemplate.update("UPDATE bookings SET starts_at = NOW() - interval '1 hour' WHERE id = ?", bookingId);

        expectStatus(patch(BOOKINGS_URL + "/" + bookingId + "/complete", owner, ""), HttpStatus.NO_CONTENT);

        assertFeed("client", client, 1, "REVIEW_REQUESTED");
        assertThat(feed(client).get(0).path("target").path("kind").asText())
                .as("a fresh review request opens the booking with the review CTA")
                .isEqualTo("BOOKING_REVIEW");
        assertFeed("owner (actor)", owner, 1, "BOOKING_CREATED");
        assertFeed("admin", admin, 1, "BOOKING_CREATED");
        assertFeed("master", master, 1, "BOOKING_CREATED");

        String reviewBody = objectMapper.writeValueAsString(
                Map.of("bookingId", bookingId.toString(), "rating", 5, "comment", "Чудово!"));
        ResponseEntity<String> review = restTemplate.exchange(
                "/api/v1/reviews", HttpMethod.POST, new HttpEntity<>(reviewBody, headers(client)), String.class);
        expectStatus(review, HttpStatus.CREATED);

        assertFeed("master", master, 2, "BOOKING_CREATED", "REVIEW_RECEIVED");
        assertFeed("owner", owner, 2, "BOOKING_CREATED", "REVIEW_RECEIVED");
        assertFeed("admin (never a review recipient)", admin, 1, "BOOKING_CREATED");
        assertFeed("client (actor of review)", client, 1, "REVIEW_REQUESTED");
        assertThat(feed(client).get(0).path("target").path("kind").asText())
                .as("once reviewed, the request item degrades to a plain booking target")
                .isEqualTo("BOOKING");
    }

    // ── 4. no-show ───────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("no-show: provider marks not-completed — only the client gets BOOKING_NOT_COMPLETED; "
            + "the provider set's feeds do not change")
    void should_notifyOnlyClient_when_providerMarksNoShow() throws Exception {
        SalonRig rig = seedSalonRig();
        String client = token(createClientEmail());
        String owner = token(rig.ownerEmail());
        String admin = token(rig.adminEmail());
        String master = token(rig.masterEmail());
        UUID bookingId = createBooking(client, rig.masterId(), rig.masterServiceId(), day2At(12));

        String body = objectMapper.writeValueAsString(Map.of("cancellationReason", "CLIENT_NO_SHOW"));
        expectStatus(patch(BOOKINGS_URL + "/" + bookingId + "/not-complete", owner, body), HttpStatus.NO_CONTENT);

        assertFeed("client", client, 1, "BOOKING_NOT_COMPLETED");
        assertFeed("owner (actor)", owner, 1, "BOOKING_CREATED");
        assertFeed("admin", admin, 1, "BOOKING_CREATED");
        assertFeed("master", master, 1, "BOOKING_CREATED");
    }

    // ── 5. walk-in E2E ───────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("walk-in: admin creates a 2-service visit -> reschedules -> declines — the performing master "
            + "gets exactly one item per step, owner/admin none, and the outbox stays untouched by CREATE")
    void should_notifyOnlyPerformingMasterOncePerStep_when_adminWalksInReschedulesAndDeclines() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID secondService = fixtures.createSalonService(rig.salonId(), rig.masterId());
        String admin = token(rig.adminEmail());
        String owner = token(rig.ownerEmail());
        String master = token(rig.masterEmail());
        Integer outboxBefore = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_outbox", Integer.class);

        String createBody = """
                {"masterServiceIds":["%s","%s"],"startsAt":"%s",
                 "guest":{"name":"Оксана","surname":"Гончар","phone":"+380501234567"}}
                """.formatted(rig.masterServiceId(), secondService, day2At(12).toOffsetDateTime());
        ResponseEntity<String> created = restTemplate.exchange("/api/v1/masters/" + rig.masterId() + "/bookings",
                HttpMethod.POST, new HttpEntity<>(createBody, headers(admin)), String.class);
        expectStatus(created, HttpStatus.CREATED);
        UUID appointmentId = UUID.fromString(objectMapper.readTree(created.getBody()).path("data").path("id").asText());

        assertThat(types(master)).as("master after create").containsExactly("BOOKING_CREATED");
        JsonNode createdItem = feed(master).get(0);
        assertThat(createdItem.path("target").path("appointmentId").asText()).isEqualTo(appointmentId.toString());
        assertThat(createdItem.path("params").path("serviceCount").asInt()).isEqualTo(2);
        assertThat(createdItem.path("params").path("counterpartName").asText()).contains("Оксана");
        assertThat(unread(master)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_outbox", Integer.class))
                .as("walk-in CREATE stays completely dark — no outbox row (push/email/SMS)")
                .isEqualTo(outboxBefore);
        assertThat(types(owner)).as("owner after create").isEmpty();
        assertThat(types(admin)).as("admin (actor) after create").isEmpty();

        String rescheduleBody = objectMapper.writeValueAsString(
                Map.of("newStartsAt", day2At(15).toOffsetDateTime().toString()));
        expectStatus(patch("/api/v1/appointments/" + appointmentId + "/reschedule", admin, rescheduleBody),
                HttpStatus.OK);

        assertThat(types(master)).as("master after reschedule").containsExactlyInAnyOrder(
                "BOOKING_CREATED", "BOOKING_RESCHEDULED");
        assertThat(unread(master)).isEqualTo(2);
        assertThat(types(owner)).isEmpty();
        assertThat(types(admin)).isEmpty();

        expectStatus(patch("/api/v1/appointments/" + appointmentId + "/decline", admin, "{}"), HttpStatus.NO_CONTENT);

        assertThat(types(master)).as("master after decline").containsExactlyInAnyOrder(
                "BOOKING_CREATED", "BOOKING_RESCHEDULED", "BOOKING_DECLINED");
        assertThat(unread(master)).isEqualTo(3);
        assertThat(types(owner)).as("owner never hears about walk-ins").isEmpty();
        assertThat(types(admin)).as("admin (actor) never hears about walk-ins").isEmpty();
        // NOTE (phase 336 finding): unlike CREATE, the pre-existing appointment reschedule/decline
        // paths (AppointmentTransitionService#enqueueBookingRescheduled / #enqueueStatusChanged) DO
        // enqueue one outbox row each, walk-in or not — one per step here. The outbox is dark at the
        // delivery layer until release; this pins the current count so a change is deliberate.
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification_outbox", Integer.class))
                .as("reschedule + decline each enqueue exactly one (pre-existing) outbox row")
                .isEqualTo(outboxBefore + 2);
    }

    // ── 6. invite acceptance ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("invite accepted: owner + existing admin see INVITE_ACCEPTED with the teammate's name via "
            + "the read API; once that admin is rotated out of the salon their item renders params=null / NONE")
    void should_renderInviteAcceptedThenLoseParams_when_recipientAdminIsRotatedOutOfTheSalon() throws Exception {
        SalonRig rig = seedSalonRig();
        String rawToken = "raw-invite-" + UUID.randomUUID();
        String inviteeEmail = "lc-invitee-" + System.nanoTime() + "@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO invite_tokens (id, token, email, salon_id, role, expires_at, is_used, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'SALON_ADMIN', ?, false, NOW(), NOW())",
                UUID.randomUUID(), sha256Hex(rawToken), inviteeEmail, rig.salonId(),
                Timestamp.from(Instant.now().plus(2, ChronoUnit.DAYS)));
        ResponseEntity<String> accepted = restTemplate.postForEntity("/api/v1/auth/invite/accept",
                new InviteAcceptRequest(rawToken, "Str0ngP@ss1!", "Invited", "Admin", "+380501234567"), String.class);
        expectStatus(accepted, HttpStatus.CREATED);
        String owner = token(rig.ownerEmail());
        String admin = token(rig.adminEmail());

        for (String recipient : List.of(owner, admin)) {
            assertThat(types(recipient)).containsExactly("INVITE_ACCEPTED");
            assertThat(unread(recipient)).isEqualTo(1);
            JsonNode item = feed(recipient).get(0);
            assertThat(item.path("target").path("kind").asText()).isEqualTo("SALON_TEAM");
            assertThat(item.path("target").path("salonId").asText()).isEqualTo(rig.salonId().toString());
            assertThat(item.path("params").path("subjectName").asText()).isEqualTo("Invited Admin");
            assertThat(item.path("params").path("subjectRole").asText()).isEqualTo("SALON_ADMIN");
        }

        UUID secondSalonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "SELECT ?, owner_id, ?, true, NOW(), NOW(), city_id FROM salons WHERE id = ?",
                secondSalonId, "Second-" + secondSalonId, rig.salonId());
        ResponseEntity<String> rotated = patch(
                "/api/v1/salons/" + rig.salonId() + "/admins/" + rig.adminId() + "/salon", owner,
                objectMapper.writeValueAsString(Map.of("destinationSalonId", secondSalonId.toString())));
        expectStatus(rotated, HttpStatus.OK);

        String rotatedAdmin = token(rig.adminEmail());
        JsonNode lostAccess = feed(rotatedAdmin).get(0);
        assertThat(lostAccess.path("type").asText()).isEqualTo("INVITE_ACCEPTED");
        assertThat(lostAccess.path("target").path("kind").asText())
                .as("a recipient who no longer manages the salon must not get a team target").isEqualTo("NONE");
        assertThat(absent(lostAccess.path("params")))
                .as("...nor the new teammate's name/role").isTrue();
        JsonNode ownerStill = feed(owner).get(0);
        assertThat(ownerStill.path("target").path("kind").asText()).isEqualTo("SALON_TEAM");
        assertThat(ownerStill.path("params").path("subjectName").asText()).isEqualTo("Invited Admin");
    }

    private static String sha256Hex(String input) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── 7. one owner, two salons ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("an OWNER of TWO salons gets ONE merged feed (one item per salon, unread 2, each item "
            + "identifying its salon), while each salon's admin and master see only their own salon's item")
    void should_mergeAllOwnedSalonsIntoOneOwnerFeed_andScopeAdminsToTheirOwnSalon() throws Exception {
        SalonRig salonA = seedSalonRig();
        SalonRig salonB = seedSalonRig();
        // Salon B was seeded with its own owner; hand it to salon A's owner so ONE account owns both.
        jdbcTemplate.update("UPDATE salons SET owner_id = ? WHERE id = ?", salonA.ownerId(), salonB.salonId());
        String owner = token(salonA.ownerEmail());
        String adminA = token(salonA.adminEmail());
        String adminB = token(salonB.adminEmail());
        String masterA = token(salonA.masterEmail());
        String masterB = token(salonB.masterEmail());
        String client = token(createClientEmail());

        createBooking(client, salonA.masterId(), salonA.masterServiceId(), day2At(12));
        createBooking(client, salonB.masterId(), salonB.masterServiceId(), day2At(15));

        JsonNode ownerFeed = feed(owner);
        assertThat(ownerFeed).as("one merged owner feed: one item per owned salon").hasSize(2);
        assertThat(unread(owner)).as("owner unread-count spans both salons").isEqualTo(2);
        Map<String, String> salonNameBySalonId = new java.util.HashMap<>();
        ownerFeed.forEach(item -> {
            assertThat(item.path("type").asText()).isEqualTo("BOOKING_CREATED");
            salonNameBySalonId.put(item.path("target").path("salonId").asText(),
                    item.path("params").path("salonName").asText());
        });
        assertThat(salonNameBySalonId.keySet())
                .containsExactlyInAnyOrder(salonA.salonId().toString(), salonB.salonId().toString());
        assertThat(salonNameBySalonId.get(salonA.salonId().toString())).isEqualTo("Salon-" + salonA.salonId());
        assertThat(salonNameBySalonId.get(salonB.salonId().toString())).isEqualTo("Salon-" + salonB.salonId());

        assertScopedToSalon("admin A", adminA, salonA.salonId());
        assertScopedToSalon("admin B", adminB, salonB.salonId());
        assertScopedToSalon("master A", masterA, salonA.salonId());
        assertScopedToSalon("master B", masterB, salonB.salonId());
    }

    private void assertScopedToSalon(String who, String token, UUID salonId) throws Exception {
        JsonNode items = feed(token);
        assertThat(items).as("%s feed", who).hasSize(1);
        assertThat(items.get(0).path("target").path("salonId").asText()).as("%s item salon", who)
                .isEqualTo(salonId.toString());
        assertThat(items.get(0).path("params").path("salonName").asText()).as("%s item salon name", who)
                .isEqualTo("Salon-" + salonId);
        assertThat(unread(token)).as("%s unread-count", who).isEqualTo(1);
    }
}
