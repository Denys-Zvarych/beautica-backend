package com.beautica.notification.inapp;

import com.beautica.AbstractIntegrationTest;
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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 334 QA follow-up — closes the gap between the two suites that already exist: {@code
 * InAppNotificationWritePathIT} (phase 333, real HTTP write path, asserts ROW SHAPE — recipient set
 * and {@code type} column only) and {@code NotificationFeedControllerIT} (phase 334, HTTP contract,
 * but every fixture row is a raw {@code jdbcTemplate.update} INSERT). Neither proves the two layers
 * COMPOSE: that a row a real booking/visit/review/self-delete/salon-closure event actually WRITES is
 * then correctly RENDERED — target kind, ids, and every {@code NotificationParams} field — when the
 * real recipient calls {@code GET /api/v1/notifications} for it.
 *
 * <p>REUSE-FIRST: every fixture below is {@link BookingTestFixtures} (salon/visit/working-hours) or
 * the plain {@code jdbcTemplate}/{@code restTemplate} helpers this package's sibling ITs already use
 * — no parallel builder.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Notification feed — real write path (333) composed with the real read API (334)")
class NotificationFeedRealWritePathIT extends AbstractIntegrationTest {

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

    // ── fixture: one salon with an owner, an invited SALON_MASTER, an admin and a named client ──

    private record SalonRig(UUID salonId, UUID ownerId, String ownerEmail, UUID masterId, UUID masterUserId,
                             UUID adminId, String adminEmail, UUID masterServiceId) {
    }

    private SalonRig seedSalonRig() {
        BookingTestFixtures.SalonFixture salon =
                fixtures.createSalon("rwp-owner-" + System.nanoTime() + "@beautica.test");
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, salon.salonId());
        UUID masterUserId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, salon.masterId());
        String adminEmail = "rwp-admin-" + System.nanoTime() + "@beautica.test";
        UUID adminId = fixtures.createUser(adminEmail, "SALON_ADMIN", salon.salonId());
        UUID masterServiceId = fixtures.createSalonService(salon.salonId(), salon.masterId());
        fixtures.addWorkingHoursForEveryDay(salon.masterId());
        return new SalonRig(salon.salonId(), ownerId, salon.ownerEmail(), salon.masterId(), masterUserId,
                adminId, adminEmail, masterServiceId);
    }

    /** A CLIENT with a deliberately distinctive first/last name — see fixture-value memory note:
     * a null or generic name could never move the {@code counterpartName} assertion below. */
    private UUID createNamedClient(String firstName, String lastName) {
        String email = "rwp-client-" + System.nanoTime() + "@beautica.test";
        UUID id = fixtures.createUser(email, "CLIENT", null);
        jdbcTemplate.update("UPDATE users SET first_name = ?, last_name = ? WHERE id = ?", firstName, lastName, id);
        return id;
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private ZonedDateTime tomorrowAtNoon() {
        return ZonedDateTime.now(TimeZones.KYIV).plusDays(2).withHour(12).withMinute(0).withSecond(0).withNano(0);
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

    private HttpHeaders headers(String token) {
        return fixtures.bearerHeaders(token);
    }

    private JsonNode feedFor(String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                NOTIFICATIONS_URL + "?size=20", HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
        assertThat(resp.getStatusCode()).as("feed fetch must succeed — body=%s", resp.getBody())
                .isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private int unreadCountFor(String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                NOTIFICATIONS_URL + "/unread-count", HttpMethod.GET, new HttpEntity<>(headers(token)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).path("data").path("count").asInt();
    }

    // ── gap 1 — real event -> real read round trip ─────────────────────────────────────────────

    @Test
    @DisplayName("client books a salon master over real HTTP: owner, admin and master each see exactly "
            + "one BOOKING_CREATED item with the right target/params; unread-count is 1 for each; the "
            + "client (the actor) sees nothing")
    void should_renderBookingCreatedForProviderSet_when_clientBooksSalonMasterOverRealHttp() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createNamedClient("Оксана", "Гончарук");
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        ZonedDateTime startsAt = tomorrowAtNoon();

        UUID bookingId = createBooking(clientToken, rig.masterId(), rig.masterServiceId(), startsAt);

        for (Map.Entry<String, UUID> recipient : Map.of(
                rig.ownerEmail(), rig.ownerId(),
                rig.adminEmail(), rig.adminId(),
                emailOf(rig.masterUserId()), rig.masterUserId()).entrySet()) {
            String token = fixtures.tokenFor(recipient.getKey());
            JsonNode data = feedFor(token);
            assertThat(data).as("recipient %s must see exactly one item", recipient.getValue()).hasSize(1);
            JsonNode row = data.get(0);
            assertThat(row.path("type").asText()).isEqualTo("BOOKING_CREATED");
            assertThat(row.path("read").asBoolean()).isFalse();
            assertThat(row.path("target").path("kind").asText()).isEqualTo("BOOKING");
            assertThat(row.path("target").path("bookingId").asText()).isEqualTo(bookingId.toString());
            assertThat(row.path("target").path("salonId").asText()).isEqualTo(rig.salonId().toString());
            assertThat(row.path("params").path("counterpartName").asText())
                    .as("provider params must show the CLIENT's display name")
                    .isEqualTo("Оксана Гончарук");
            assertThat(row.path("params").path("serviceName").asText()).isEqualTo("Test Service");
            assertThat(row.path("params").path("serviceCount").asInt()).isEqualTo(1);
            assertThat(Instant.parse(row.path("params").path("startsAt").asText()))
                    .as("params.startsAt must be the booking's real start instant")
                    .isEqualTo(startsAt.toInstant());
            assertThat(unreadCountFor(token)).as("unread-count must match the single item").isEqualTo(1);
        }

        JsonNode clientFeed = feedFor(clientToken);
        assertThat(clientFeed).as("the booking client is the actor — never a recipient of their own create").isEmpty();
        assertThat(unreadCountFor(clientToken)).isZero();
    }

    // ── gap 2 — a visit (multi-service) item ────────────────────────────────────────────────────

    @Test
    @DisplayName("a 3-service visit created in one salon booking: the master's feed item targets the "
            + "visit's EARLIEST booking and carries serviceCount=3")
    void should_targetEarliestBookingWithServiceCount_when_multiServiceVisitCreated() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID s2 = fixtures.createSalonService(rig.salonId(), rig.masterId());
        UUID s3 = fixtures.createSalonService(rig.salonId(), rig.masterId());
        UUID clientId = createNamedClient("Марія", "Ковальська");
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        ZonedDateTime startsAt = tomorrowAtNoon();

        String createBody = objectMapper.writeValueAsString(Map.of(
                "masterId", rig.masterId().toString(),
                "masterServiceIds", List.of(rig.masterServiceId().toString(), s2.toString(), s3.toString()),
                "startsAt", startsAt.toOffsetDateTime().toString()));
        ResponseEntity<String> created = restTemplate.exchange(
                "/api/v1/appointments", HttpMethod.POST, new HttpEntity<>(createBody, headers(clientToken)),
                String.class);
        assertThat(created.getStatusCode()).as("visit create must succeed — body=%s", created.getBody())
                .isEqualTo(HttpStatus.CREATED);
        UUID appointmentId = UUID.fromString(
                objectMapper.readTree(created.getBody()).path("data").path("id").asText());
        UUID earliestBookingId = jdbcTemplate.queryForObject(
                "SELECT id FROM bookings WHERE appointment_id = ? ORDER BY starts_at, id LIMIT 1",
                UUID.class, appointmentId);

        JsonNode data = feedFor(fixtures.tokenFor(emailOf(rig.masterUserId())));
        assertThat(data).hasSize(1);
        JsonNode row = data.get(0);
        assertThat(row.path("type").asText()).isEqualTo("BOOKING_CREATED");
        assertThat(row.path("target").path("kind").asText()).isEqualTo("BOOKING");
        assertThat(row.path("target").path("appointmentId").asText()).isEqualTo(appointmentId.toString());
        assertThat(row.path("target").path("bookingId").asText())
                .as("a visit's target.bookingId must be the earliest chained booking, never an arbitrary leg")
                .isEqualTo(earliestBookingId.toString());
        assertThat(row.path("params").path("serviceCount").asInt())
                .as("serviceCount must reflect all 3 chained services, not just the lead one")
                .isEqualTo(3);
    }

    // ── gap 3 — mark-read flow drops unread-count; read-all respects the upTo cutoff ──────────────

    @Test
    @DisplayName("marking a real event's feed item read drops unread-count from 1 to 0")
    void should_dropUnreadCountToZero_when_markingRealBookingCreatedItemRead() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createNamedClient("Іван", "Петренко");
        createBooking(fixtures.tokenFor(emailOf(clientId)), rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        String masterToken = fixtures.tokenFor(emailOf(rig.masterUserId()));
        assertThat(unreadCountFor(masterToken)).isEqualTo(1);
        UUID itemId = UUID.fromString(feedFor(masterToken).get(0).path("id").asText());

        ResponseEntity<String> markResp = restTemplate.exchange(
                NOTIFICATIONS_URL + "/" + itemId + "/read", HttpMethod.PATCH,
                new HttpEntity<>(headers(masterToken)), String.class);

        assertThat(markResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(unreadCountFor(masterToken))
                .as("unread-count must reflect the just-marked item immediately")
                .isZero();
        assertThat(feedFor(masterToken).get(0).path("read").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("read-all with upTo = the feed-open instant marks the first real event read but leaves "
            + "a SECOND real event created after that instant unread")
    void should_leaveLaterRealEvent_unread_when_readAllCutoffPrecedesIt() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientA = createNamedClient("Ганна", "Мельник");
        UUID clientB = createNamedClient("Софія", "Бондаренко");
        String masterToken = fixtures.tokenFor(emailOf(rig.masterUserId()));

        UUID bookingA = createBooking(
                fixtures.tokenFor(emailOf(clientA)), rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        Instant cutoff = jdbcTemplate.queryForObject(
                "SELECT MAX(created_at) FROM in_app_notification WHERE recipient_user_id = ?",
                java.sql.Timestamp.class, rig.masterUserId()).toInstant();
        // A second, DISTINCT service so the second booking's dedup_key differs from the first —
        // both are independently real BOOKING_CREATED events, not a replay of the same one.
        UUID s2 = fixtures.createSalonService(rig.salonId(), rig.masterId());
        UUID bookingB = createBooking(
                fixtures.tokenFor(emailOf(clientB)), rig.masterId(), s2, tomorrowAtNoon().plusHours(3));

        String body = objectMapper.writeValueAsString(Map.of("upTo", cutoff.toString()));
        ResponseEntity<String> resp = restTemplate.exchange(NOTIFICATIONS_URL + "/read-all", HttpMethod.PATCH,
                new HttpEntity<>(body, headers(masterToken)), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(resp.getBody()).path("data").path("updated").asInt()).isEqualTo(1);

        JsonNode feed = feedFor(masterToken);
        assertThat(feed).hasSize(2);
        for (JsonNode row : feed) {
            UUID rowBookingId = UUID.fromString(row.path("target").path("bookingId").asText());
            boolean read = row.path("read").asBoolean();
            if (rowBookingId.equals(bookingA)) {
                assertThat(read).as("the event BEFORE the cutoff must be read").isTrue();
            } else if (rowBookingId.equals(bookingB)) {
                assertThat(read).as("the event AFTER the cutoff must remain unread").isFalse();
            } else {
                throw new AssertionError("unexpected row bookingId " + rowBookingId);
            }
        }
    }

    // ── gap 4 — deleted referents, via the REAL cascade endpoints ──────────────────────────────

    @Test
    @DisplayName("client self-deletes via the real DELETE /users/me endpoint: the provider's item "
            + "renders the detached-client sentinel, never a name, never a 500")
    void should_renderDetachedClientSentinel_when_clientSelfDeletesViaRealEndpoint() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createNamedClient("Тетяна", "Шевченко");
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        createBooking(clientToken, rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        String masterToken = fixtures.tokenFor(emailOf(rig.masterUserId()));
        assertThat(feedFor(masterToken).get(0).path("params").path("counterpartName").asText())
                .as("sanity — before self-delete the real name is rendered")
                .isEqualTo("Тетяна Шевченко");
        // Isolate the self-delete's own BOOKING_CANCELLED_BY_CLIENT event from the earlier CREATE
        // row — self-delete notifies the provider set too (row 2 of the phase 333 matrix), so
        // without this the master would legitimately end up with TWO rows, not one.
        jdbcTemplate.update("DELETE FROM in_app_notification");

        ResponseEntity<Void> deleteResp = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE, new HttpEntity<>(headers(clientToken)), Void.class);
        assertThat(deleteResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode data = feedFor(masterToken);
        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("params").path("counterpartName").asText())
                .as("phase 338 keeps the (now cancelled) booking, detached — the sentinel must render, "
                        + "never the deleted client's real name and never a 500")
                .isEqualTo("Видалений клієнт");
        assertThat(data.toString())
                .as("no PII of the deleted client may leak into the response")
                .doesNotContain("Тетяна").doesNotContain("Шевченко");
    }

    @Test
    @DisplayName("salon closed via the real DELETE /salons/{id} endpoint: the client's item renders "
            + "with no error, target still points at the booking")
    void should_renderClientItemWithoutError_when_salonClosedViaRealEndpoint() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createNamedClient("Дарина", "Кравець");
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        UUID bookingId = createBooking(clientToken, rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification"); // isolate the closure event from the CREATE rows

        String salonName = jdbcTemplate.queryForObject(
                "SELECT name FROM salons WHERE id = ?", String.class, rig.salonId());

        ResponseEntity<Void> closeResp = restTemplate.exchange(
                "/api/v1/salons/" + rig.salonId(), HttpMethod.DELETE,
                new HttpEntity<>(headers(fixtures.tokenFor(rig.ownerEmail()))), Void.class);
        assertThat(closeResp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode data = feedFor(clientToken);
        assertThat(data).hasSize(1);
        JsonNode row = data.get(0);
        assertThat(row.path("type").asText()).isEqualTo("BOOKING_CANCELLED_SALON_CLOSED");
        assertThat(row.path("target").path("bookingId").asText())
                .as("the client keeps view over their own booking even after the salon closes")
                .isEqualTo(bookingId.toString());
        assertThat(row.path("params").path("salonName").asText())
                .as("the salon row itself is never hard-deleted by closure — its real name must still "
                        + "render, not a null/blank placeholder")
                .isEqualTo(salonName);
    }

    @Test
    @DisplayName("REVIEW_REQUESTED degrades from BOOKING_REVIEW to BOOKING once the client actually "
            + "submits the review — never NONE, since the booking itself is still visible")
    void should_degradeReviewTargetToBooking_when_reviewAlreadySubmitted() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createNamedClient("Юлія", "Савченко");
        String clientToken = fixtures.tokenFor(emailOf(clientId));
        UUID bookingId = createBooking(clientToken, rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("UPDATE bookings SET starts_at = NOW() - interval '1 hour' WHERE id = ?", bookingId);
        ResponseEntity<String> completeResp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/complete", HttpMethod.PATCH,
                new HttpEntity<>("", headers(fixtures.tokenFor(rig.ownerEmail()))), String.class);
        assertThat(completeResp.getStatusCode()).as("complete must succeed — body=%s", completeResp.getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode beforeReview = feedFor(clientToken);
        JsonNode reviewRow = findByType(beforeReview, "REVIEW_REQUESTED");
        assertThat(reviewRow.path("target").path("kind").asText())
                .as("sanity — reviewable before the review is submitted")
                .isEqualTo("BOOKING_REVIEW");

        String reviewBody = objectMapper.writeValueAsString(
                Map.of("bookingId", bookingId.toString(), "rating", 5, "comment", "Great!"));
        ResponseEntity<String> reviewResp = restTemplate.exchange(
                "/api/v1/reviews", HttpMethod.POST, new HttpEntity<>(reviewBody, headers(clientToken)), String.class);
        assertThat(reviewResp.getStatusCode()).as("review create must succeed — body=%s", reviewResp.getBody())
                .isEqualTo(HttpStatus.CREATED);

        JsonNode afterReview = feedFor(clientToken);
        JsonNode reviewRowAfter = findByType(afterReview, "REVIEW_REQUESTED");
        assertThat(reviewRowAfter.path("target").path("kind").asText())
                .as("once reviewed, the same REVIEW_REQUESTED row must degrade to plain BOOKING — the "
                        + "booking is still visible, so NONE would be wrong")
                .isEqualTo("BOOKING");
        assertThat(reviewRowAfter.path("target").path("bookingId").asText()).isEqualTo(bookingId.toString());
    }

    private JsonNode findByType(JsonNode data, String type) {
        for (JsonNode row : data) {
            if (row.path("type").asText().equals(type)) {
                return row;
            }
        }
        throw new AssertionError("no row of type " + type + " in " + data);
    }

    // ── gap 5 — pagination across two pages, newest first, id tiebreak on equal createdAt ────────

    @Test
    @DisplayName("25 items: page 0 size 20 returns 20, page 1 size 20 returns 5, newest first, and two "
            + "rows sharing the SAME createdAt break the tie by id DESC")
    void should_paginateNewestFirst_withIdTiebreakOnEqualCreatedAt() throws Exception {
        SalonRig rig = seedSalonRig();
        UUID clientId = createNamedClient("Олена", "Бойко");
        UUID bookingId = createBooking(
                fixtures.tokenFor(emailOf(clientId)), rig.masterId(), rig.masterServiceId(), tomorrowAtNoon());
        jdbcTemplate.update("DELETE FROM in_app_notification WHERE recipient_user_id = ?", rig.masterUserId());

        List<UUID> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            ids.add(insertRawFeedRow(rig.masterUserId(), bookingId, i));
        }
        // Force two rows to share the EXACT same created_at so the id-tiebreak branch is actually
        // exercised — without this, Postgres' monotonic clock would make every timestamp distinct
        // and the ordering assertion below would pass vacuously even if id DESC were dropped.
        java.sql.Timestamp sharedTs = jdbcTemplate.queryForObject(
                "SELECT created_at FROM in_app_notification WHERE id = ?", java.sql.Timestamp.class, ids.get(24));
        jdbcTemplate.update("UPDATE in_app_notification SET created_at = ? WHERE id = ?", sharedTs, ids.get(23));
        // Independently-derived expected order, via the SAME (created_at DESC, id DESC) predicate
        // production uses — Postgres' uuid type orders by raw byte value, which is NOT what
        // java.util.UUID#compareTo implements (that compares mostSigBits/leastSigBits as SIGNED
        // longs), so deriving "expected" via Java UUID comparison would silently assert the WRONG
        // thing here; asking the database directly is the only comparison that cannot drift from
        // what the production query actually does.
        List<UUID> tiedPair = jdbcTemplate.queryForList(
                "SELECT id FROM in_app_notification WHERE id IN (?, ?) ORDER BY created_at DESC, id DESC",
                UUID.class, ids.get(23), ids.get(24));
        UUID expectedFirst = tiedPair.get(0);
        UUID expectedSecond = tiedPair.get(1);

        String masterToken = fixtures.tokenFor(emailOf(rig.masterUserId()));
        ResponseEntity<String> page0Resp = restTemplate.exchange(NOTIFICATIONS_URL + "?page=0&size=20",
                HttpMethod.GET, new HttpEntity<>(headers(masterToken)), String.class);
        ResponseEntity<String> page1Resp = restTemplate.exchange(NOTIFICATIONS_URL + "?page=1&size=20",
                HttpMethod.GET, new HttpEntity<>(headers(masterToken)), String.class);
        JsonNode page0 = objectMapper.readTree(page0Resp.getBody());
        JsonNode page1 = objectMapper.readTree(page1Resp.getBody());

        assertThat(page0.path("data")).hasSize(20);
        assertThat(page0.path("totalElements").asLong()).isEqualTo(25);
        assertThat(page0.path("totalPages").asInt()).isEqualTo(2);
        assertThat(page1.path("data")).hasSize(5);

        assertThat(page0.path("data").get(0).path("id").asText())
                .as("the two rows sharing created_at must sort by id DESC, newest-tiebreak-wins first")
                .isEqualTo(expectedFirst.toString());
        assertThat(page0.path("data").get(1).path("id").asText()).isEqualTo(expectedSecond.toString());

        List<String> allIds = new java.util.ArrayList<>();
        page0.path("data").forEach(r -> allIds.add(r.path("id").asText()));
        page1.path("data").forEach(r -> allIds.add(r.path("id").asText()));
        assertThat(allIds).as("no row skipped or duplicated across the two pages")
                .hasSize(25).doesNotHaveDuplicates()
                .containsExactlyInAnyOrderElementsOf(ids.stream().map(UUID::toString).toList());
    }

    /** Raw insert mirroring {@code InAppNotificationRepository#insertIgnoringDuplicate} — see
     * {@code NotificationFeedControllerIT#insertNotification}'s identical convention/rationale for
     * why a plain-fixture {@code @SpringBootTest} method uses {@code jdbcTemplate} rather than the
     * repository's own {@code @Modifying} method here. */
    private UUID insertRawFeedRow(UUID recipientId, UUID bookingId, int seq) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO in_app_notification (id, recipient_user_id, type, booking_id, dedup_key)
                VALUES (?, ?, 'BOOKING_CREATED', ?, ?)
                """, id, recipientId, bookingId, "BOOKING_CREATED:" + bookingId + ":" + seq);
        return id;
    }

    // ── gap 6 — security regressions ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH /notifications/{id}/read with a malformed (non-UUID) id returns 400, never 404 "
            + "or 500")
    void should_return400_when_markReadIdIsMalformedUuid() throws Exception {
        UUID recipientId = createNamedClient("Назар", "Кузьменко");
        String token = fixtures.tokenFor(emailOf(recipientId));

        ResponseEntity<String> resp = restTemplate.exchange(
                NOTIFICATIONS_URL + "/not-a-uuid/read", HttpMethod.PATCH,
                new HttpEntity<>(headers(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("two independent recipients never see each other's real-event items — cross-user "
            + "isolation over the real write path, not just a raw-SQL fixture")
    void should_notLeakBetweenRecipients_when_twoIndependentSalonsBookInParallel() throws Exception {
        SalonRig rigA = seedSalonRig();
        SalonRig rigB = seedSalonRig();
        UUID clientA = createNamedClient("Артем", "Литвин");
        UUID clientB = createNamedClient("Ірина", "Гуменюк");

        UUID bookingA = createBooking(
                fixtures.tokenFor(emailOf(clientA)), rigA.masterId(), rigA.masterServiceId(), tomorrowAtNoon());
        UUID bookingB = createBooking(
                fixtures.tokenFor(emailOf(clientB)), rigB.masterId(), rigB.masterServiceId(), tomorrowAtNoon());

        JsonNode feedA = feedFor(fixtures.tokenFor(emailOf(rigA.masterUserId())));
        JsonNode feedB = feedFor(fixtures.tokenFor(emailOf(rigB.masterUserId())));

        assertThat(feedA).hasSize(1);
        assertThat(feedA.get(0).path("target").path("bookingId").asText()).isEqualTo(bookingA.toString());
        assertThat(feedB).hasSize(1);
        assertThat(feedB.get(0).path("target").path("bookingId").asText()).isEqualTo(bookingB.toString());
        assertThat(feedA.toString()).doesNotContain(bookingB.toString());
        assertThat(feedB.toString()).doesNotContain(bookingA.toString());
    }
}
