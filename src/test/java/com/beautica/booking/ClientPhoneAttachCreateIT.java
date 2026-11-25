package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.phoneotp.GuestTokenProvider;
import com.beautica.booking.PublicBookingPageFixtures.IndependentMaster;
import com.beautica.booking.enums.AttachReason;
import com.beautica.client.service.ClientPhoneAttachService;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.sms.SmsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * B527 (CLI-62, GST-82, GST-83) — a guest (LINK) or walk-in (STAFF) booking made with the verified phone of a CLIENT
 * account is created ATTACHED to it, over the real HTTP stack and real Postgres: cases 1, 2, 3, 13, 14, 16, 21, 22.
 * Notification routing follows the registered-client path (no SMS, no Telegram, in-app feed), and a block of the account
 * or of its verified-phone hash refuses the booking (MOD-29) — except a walk-in, which ignores blocks (MOD-28).
 */
@Import(TestSecurityConfig.class)
@TestPropertySource(properties = "FIREBASE_ENABLED=true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Client phone attach at booking create — B527")
class ClientPhoneAttachCreateIT extends AbstractIntegrationTest {

    private static final ZoneId KYIV = ZoneId.of("Europe/Kyiv");
    private static final String PHONE = "+380671234501";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private GuestTokenProvider guestTokenProvider;
    @Autowired private PhoneHasher phoneHasher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ClientPhoneAttachService attachService;
    @Autowired private jakarta.persistence.EntityManagerFactory emf;
    @MockBean private SmsService smsService;
    /** The one external edge of the push chain; FIREBASE_ENABLED=true so the feed write also enqueues INAPP_PUSH. */
    @MockBean private com.beautica.config.FirebaseConfig.FirebaseSender firebaseSender;

    private PublicBookingPageFixtures pages;
    private final Map<UUID, String> providerTokens = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach
    void setUp() {
        pages = new PublicBookingPageFixtures(jdbcTemplate, restTemplate, objectMapper, passwordEncoder);
    }

    /**
     * Pays the cold-start cost (first link + walk-in HTTP round trips, login, Hibernate/Jackson warm-up on a context
     * that may have just started) once, outside any timed test. Without it whichever test JUnit orders first (2d) took
     * ~2.6 s alone and 10.5 s under batch load against SlowTestExtension's 10 s ceiling while its siblings take ~0.4 s.
     * Mirrors MultiServiceNotificationIT#warmUpColdStart: the real attached link + walk-in create path, then a full DB
     * wipe and the mock interactions cleared, so nothing leaks into the first test's assertions.
     */
    @BeforeAll
    void warmUpColdStart() throws Exception {
        setUp();
        IndependentMaster master = pages.independentMaster(true);
        verifiedClient(PHONE);
        created(linkBook(master, PHONE, slot(3, 12)));
        created(walkIn(master, "0671234501", slot(4, 12)));
        cleanDb();
        providerTokens.clear();
        org.mockito.Mockito.clearInvocations(smsService, firebaseSender);
    }

    // ── seeding ──────────────────────────────────────────────────────────────

    private UUID verifiedClient(String phone) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, phone_number, "
                        + "phone_verified_at, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', 'Ірина', 'Кравчук', ?, NOW(), true, true)",
                id, "att-" + id + "@beautica.test", passwordEncoder.encode(PublicBookingPageFixtures.PASSWORD), phone);
        return id;
    }

    private String clientToken(UUID clientId) throws Exception {
        String email = jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, clientId);
        ResponseEntity<String> resp = restTemplate.postForEntity("/api/v1/auth/login",
                Map.of("identifier", email, "password", PublicBookingPageFixtures.PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).at("/data/accessToken").asText();
    }

    private UUID masterServiceOf(IndependentMaster master) {
        return jdbcTemplate.queryForObject("SELECT id FROM master_services WHERE service_def_id = ?", UUID.class,
                master.serviceDefId());
    }

    private ZonedDateTime slot(int daysAhead, int hour) {
        return ZonedDateTime.now(KYIV).plusDays(daysAhead).withHour(hour).withMinute(0).withSecond(0).withNano(0);
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private ResponseEntity<String> linkBook(IndependentMaster master, String phone, ZonedDateTime startsAt,
                                            String idsField) {
        var random = ThreadLocalRandom.current();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(guestTokenProvider.generate(phone));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Forwarded-For", "10." + random.nextInt(1, 255) + "." + random.nextInt(1, 255) + "."
                + random.nextInt(1, 255));
        String body = "{" + idsField + ",\"startsAt\":\"" + startsAt.toOffsetDateTime()
                + "\",\"name\":\"Олена\",\"surname\":\"Коваль\",\"privacyAccepted\":true,"
                + "\"privacyPolicyVersion\":\"2025-02-03\"}";
        return restTemplate.exchange("/api/v1/book/" + master.slug() + "/booking", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private ResponseEntity<String> linkBook(IndependentMaster master, String phone, ZonedDateTime startsAt) {
        return linkBook(master, phone, startsAt, "\"serviceId\":\"" + masterServiceOf(master) + "\"");
    }

    private ResponseEntity<String> walkIn(IndependentMaster master, String phone, ZonedDateTime startsAt)
            throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(providerTokens.computeIfAbsent(master.masterId(), id -> {
            try {
                return pages.tokenOf(master);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }));
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"masterServiceIds\":[\"" + masterServiceOf(master) + "\"],\"startsAt\":\""
                + startsAt.toOffsetDateTime() + "\",\"guest\":{\"name\":\"Оксана\",\"surname\":\"Гончар\","
                + "\"phone\":\"" + phone + "\"}}";
        return restTemplate.exchange("/api/v1/masters/" + master.masterId() + "/bookings", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    private JsonNode created(ResponseEntity<String> response) throws Exception {
        assertThat(response.getStatusCode()).as("body=%s", response.getBody()).isEqualTo(HttpStatus.CREATED);
        return objectMapper.readTree(response.getBody());
    }

    private Map<String, Object> bookingRow(UUID id) {
        return jdbcTemplate.queryForMap("SELECT client_id, client_link_reason, client_linked_at, guest_phone, "
                + "guest_name, cancel_token_hash, booking_source FROM bookings WHERE id = ?", id);
    }

    private int feedRows(UUID recipient, String type) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM in_app_notification WHERE recipient_user_id = ? "
                + "AND type = ?", Integer.class, recipient, type);
    }

    private void blockGuestHash(IndependentMaster master, String phone) {
        PhoneHasher.HashedPhone hashed = phoneHasher.hash(phone);
        jdbcTemplate.update("INSERT INTO client_blocks (id, blocker_master_id, phone_hash, phone_last4, display_name) "
                + "VALUES (?, ?, ?, ?, 'Гість')", UUID.randomUUID(), master.masterId(), hashed.hash(), hashed.last4());
    }

    private void blockAccount(IndependentMaster master, UUID clientId) {
        jdbcTemplate.update("INSERT INTO client_blocks (id, blocker_master_id, blocked_user_id, display_name) "
                + "VALUES (?, ?, ?, 'Клієнт')", UUID.randomUUID(), master.masterId(), clientId);
    }

    // ── statement-count pin (audit LOW, B527) ─────────────────────────────────

    /**
     * The link create's whole-request statement count (single-service, one master). The walk-in side is pinned in
     * StaffBookingIT#StatementCount. {@code ATTACHED} is {@code PLAIN} + the attach's own cost; a rise in either means
     * a query joined the create path, a fall means one was removed.
     */
    private static final long LINK_CREATE_PLAIN_STATEMENTS = 15L;
    private static final long LINK_CREATE_ATTACHED_STATEMENTS = 16L;

    private long linkCreateStatements(IndependentMaster master, String phone) throws Exception {
        created(linkBook(master, phone, slot(3, 10)));
        org.hibernate.stat.Statistics statistics = com.beautica.support.HibernateStatistics.enabledOn(emf);
        statistics.clear();
        created(linkBook(master, phone, slot(3, 13)));
        return statistics.getPrepareStatementCount();
    }

    @Test
    @DisplayName("statement count: a plain guest link create costs the pinned number")
    void should_issuePinnedStatementCount_when_linkBookingForAPlainGuest() throws Exception {
        IndependentMaster master = pages.independentMaster(true);

        long statements = linkCreateStatements(master, PHONE);

        assertThat(statements).as("plain link create statements").isEqualTo(LINK_CREATE_PLAIN_STATEMENTS);
    }

    @Test
    @DisplayName("statement count: an attached link create costs the pinned number (account lookup + one combined block probe)")
    void should_issuePinnedStatementCount_when_linkBookingIsAttached() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        verifiedClient(PHONE);

        long statements = linkCreateStatements(master, PHONE);

        assertThat(statements).as("attached link create statements").isEqualTo(LINK_CREATE_ATTACHED_STATEMENTS);
    }

    // ── cases ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("1: a link guest booking with a client account's verified phone is created attached; /bookings/me "
            + "lists it; the response says so and offers no Telegram")
    void should_attachLinkBooking_when_phoneIsAVerifiedClientPhone() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID client = verifiedClient(PHONE);

        JsonNode body = created(linkBook(master, PHONE, slot(3, 12)));

        UUID bookingId = UUID.fromString(body.path("bookingId").asText());
        Map<String, Object> row = bookingRow(bookingId);
        assertThat(row.get("client_id")).isEqualTo(client);
        assertThat(row.get("client_link_reason")).isEqualTo("BOOKING");
        assertThat(row.get("client_linked_at")).isNotNull();
        assertThat(row.get("guest_phone")).isNull();
        assertThat(row.get("guest_name")).as("name kept").isEqualTo("Олена");
        assertThat(row.get("cancel_token_hash")).as("the cancel link keeps working").isNotNull();
        assertThat(body.path("attachedToAccount").asBoolean()).isTrue();
        assertThat(body.path("telegram").path("available").asBoolean()).isFalse();
        assertThat(body.path("telegram").path("linked").asBoolean()).isFalse();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(clientToken(client));
        ResponseEntity<String> mine = restTemplate.exchange("/api/v1/bookings/me", HttpMethod.GET,
                new HttpEntity<>(headers), String.class);
        assertThat(mine.getStatusCode()).as(mine.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(mine.getBody()).contains(bookingId.toString());
    }

    @Test
    @DisplayName("1b: a link booking with a phone no account owns stays a guest row (attachedToAccount false)")
    void should_keepGuestBooking_when_noAccountOwnsThePhone() throws Exception {
        IndependentMaster master = pages.independentMaster(true);

        JsonNode body = created(linkBook(master, PHONE, slot(3, 12)));

        Map<String, Object> row = bookingRow(UUID.fromString(body.path("bookingId").asText()));
        assertThat(row.get("client_id")).isNull();
        assertThat(row.get("client_link_reason")).isNull();
        assertThat(row.get("guest_phone")).isEqualTo(PHONE);
        assertThat(body.path("attachedToAccount").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("2: attached link booking — no SMS, no review invite, the client gets a feed item, the provider feed "
            + "is unchanged")
    void should_followTheRegisteredPath_when_linkBookingIsAttached() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID client = verifiedClient(PHONE);

        JsonNode body = created(linkBook(master, PHONE, slot(3, 12)));

        UUID bookingId = UUID.fromString(body.path("bookingId").asText());
        verify(smsService, never()).send(any(), any());
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM review_invites", Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM in_app_notification WHERE recipient_user_id = ? "
                + "AND type = 'BOOKING_CREATED' AND booking_id = ?", Integer.class, client, bookingId)).isOne();
        assertThat(feedRows(master.userId(), "BOOKING_CREATED")).as("provider feed unchanged").isOne();
    }

    @Test
    @DisplayName("3: a walk-in with a client account's verified phone after the account exists is attached; the "
            + "client gets a feed item and no SMS")
    void should_attachWalkIn_when_accountAlreadyExists() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID client = verifiedClient(PHONE);

        JsonNode data = created(walkIn(master, "0671234501", slot(3, 12))).path("data");

        UUID bookingId = UUID.fromString(data.path("items").get(0).path("bookingId").asText());
        Map<String, Object> row = bookingRow(bookingId);
        assertThat(row.get("client_id")).isEqualTo(client);
        assertThat(row.get("client_link_reason")).isEqualTo("BOOKING");
        assertThat(row.get("guest_phone")).isNull();
        assertThat(row.get("booking_source")).isEqualTo("STAFF");
        assertThat(jdbcTemplate.queryForObject("SELECT client_id FROM appointments WHERE id = ?", UUID.class,
                UUID.fromString(data.path("id").asText()))).as("header attached too").isEqualTo(client);
        assertThat(feedRows(client, "BOOKING_CREATED")).isOne();
        verify(smsService, never()).send(any(), any());
    }

    private List<UUID> clientReceiptRows() {
        return jdbcTemplate.queryForList(
                "SELECT aggregate_id FROM notification_outbox WHERE event_type = 'STATUS_CHANGED'", UUID.class);
    }

    /** A verified-phone CLIENT with no email at all (phone-registered, AUTH-D11) — NTF-D15: no receipt email. */
    private void verifiedClientWithoutEmail(String phone) {
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, last_name, phone_number, "
                        + "phone_verified_at, is_active, email_verified) "
                        + "VALUES (?, NULL, ?, 'CLIENT', 'Ірина', 'Кравчук', ?, NOW(), true, false)",
                UUID.randomUUID(), passwordEncoder.encode(PublicBookingPageFixtures.PASSWORD), phone);
    }

    @Test
    @DisplayName("2c (NTF-67): an attached link booking and an attached walk-in for an account with a confirmed email "
            + "each enqueue ONE client receipt row (STATUS_CHANGED, the APP create's confirmation e-mail + .ics)")
    void should_enqueueOneClientReceiptEach_when_attachedAccountHasConfirmedEmail() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        verifiedClient(PHONE);

        UUID linkId = UUID.fromString(created(linkBook(master, PHONE, slot(3, 12))).path("bookingId").asText());
        UUID walkInId = UUID.fromString(created(walkIn(master, "0671234501", slot(4, 12)))
                .path("data").path("items").get(0).path("bookingId").asText());

        assertThat(clientReceiptRows()).as("one receipt row per attached visit, keyed to its booking")
                .containsExactlyInAnyOrder(linkId, walkInId);
    }

    @Test
    @DisplayName("2d (NTF-D15): an attached link booking and walk-in for an account with no email enqueue no client "
            + "receipt row")
    void should_enqueueNoClientReceipt_when_attachedAccountHasNoEmail() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        verifiedClientWithoutEmail(PHONE);

        JsonNode link = created(linkBook(master, PHONE, slot(3, 12)));
        created(walkIn(master, "0671234501", slot(4, 12)));

        assertThat(link.path("attachedToAccount").asBoolean()).as("precondition: attached").isTrue();
        assertThat(clientReceiptRows()).isEmpty();
    }

    @Test
    @DisplayName("2f (NTF-67): a two-service attached link visit enqueues exactly ONE client receipt row, keyed to the "
            + "visit's first item — never one per service")
    void should_enqueueOneClientReceipt_when_attachedLinkVisitHasTwoServices() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        verifiedClient(PHONE);
        String ids = "\"masterServiceIds\":[\"" + masterServiceOf(master) + "\",\"" + secondService(master) + "\"]";

        UUID appointmentId = UUID.fromString(created(linkBook(master, PHONE, slot(3, 12), ids))
                .path("appointmentId").asText());

        UUID firstItem = jdbcTemplate.queryForObject("SELECT id FROM bookings WHERE appointment_id = ? "
                + "ORDER BY starts_at LIMIT 1", UUID.class, appointmentId);
        assertThat(clientReceiptRows()).as("one receipt row per visit").containsExactly(firstItem);
    }

    @Test
    @DisplayName("2e: a link booking and a walk-in for a phone no account owns enqueue no client receipt row "
            + "(guest path unchanged)")
    void should_enqueueNoClientReceipt_when_bookingIsNotAttached() throws Exception {
        IndependentMaster master = pages.independentMaster(true);

        created(linkBook(master, PHONE, slot(3, 12)));
        created(walkIn(master, "0671234501", slot(4, 12)));

        assertThat(clientReceiptRows()).isEmpty();
    }

    @Test
    @DisplayName("2b: attached link booking and attached walk-in each enqueue one INAPP_PUSH outbox row keyed by the "
            + "client's feed row; no SMS")
    void should_enqueuePushForTheAccount_when_bookingIsAttached() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID client = verifiedClient(PHONE);
        jdbcTemplate.update("INSERT INTO device_tokens (user_id, token, platform) VALUES (?, ?, 'ANDROID')", client,
                "fcm-" + client);

        created(linkBook(master, PHONE, slot(3, 12)));
        created(walkIn(master, "0671234501", slot(4, 12)));

        List<UUID> feedIds = jdbcTemplate.queryForList("SELECT id FROM in_app_notification WHERE recipient_user_id = ? "
                + "AND type = 'BOOKING_CREATED'", UUID.class, client);
        assertThat(feedIds).as("one feed row per attached booking").hasSize(2);
        List<UUID> pushAggregates = jdbcTemplate.queryForList(
                "SELECT aggregate_id FROM notification_outbox WHERE event_type = 'INAPP_PUSH'", UUID.class);
        assertThat(pushAggregates).as("client's push rows reference the client's feed rows").containsAll(feedIds);
        verify(smsService, never()).send(any(), any());
    }

    @Test
    @DisplayName("22: a multi-service link visit with the verified phone attaches the header and every row together")
    void should_attachHeaderAndAllRows_when_multiServiceVisit() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID client = verifiedClient(PHONE);
        UUID second = secondService(master);
        String ids = "\"masterServiceIds\":[\"" + masterServiceOf(master) + "\",\"" + second + "\"]";

        JsonNode body = created(linkBook(master, PHONE, slot(3, 12), ids));

        UUID appointmentId = UUID.fromString(body.path("appointmentId").asText());
        assertThat(jdbcTemplate.queryForObject("SELECT client_id FROM appointments WHERE id = ?", UUID.class,
                appointmentId)).isEqualTo(client);
        assertThat(jdbcTemplate.queryForObject("SELECT client_link_reason FROM appointments WHERE id = ?",
                String.class, appointmentId)).isEqualTo("BOOKING");
        assertThat(jdbcTemplate.queryForObject("SELECT guest_phone FROM appointments WHERE id = ?", String.class,
                appointmentId)).isNull();
        assertThat(jdbcTemplate.queryForList("SELECT client_id FROM bookings WHERE appointment_id = ?", UUID.class,
                appointmentId)).hasSize(2).containsOnly(client);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings WHERE appointment_id = ? "
                + "AND client_link_reason = 'BOOKING' AND guest_phone IS NULL", Integer.class, appointmentId))
                .isEqualTo(2);
    }

    private UUID secondService(IndependentMaster master) {
        UUID defId = com.beautica.support.BookableMasterSeeder.assignNewIndependentService(jdbcTemplate, master.masterId());
        return jdbcTemplate.queryForObject("SELECT id FROM master_services WHERE service_def_id = ?", UUID.class, defId);
    }

    @Test
    @DisplayName("13: a phone-hash block on the verified phone refuses the account's in-app booking (409)")
    void should_refuseAppBooking_when_verifiedPhoneHashIsBlocked() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID client = verifiedClient(PHONE);
        blockGuestHash(master, PHONE);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(clientToken(client));
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = "{\"masterId\":\"" + master.masterId() + "\",\"masterServiceId\":\"" + masterServiceOf(master)
                + "\",\"startsAt\":\""
                + slot(4, 12).toOffsetDateTime() + "\"}";

        ResponseEntity<String> response = restTemplate.exchange("/api/v1/bookings", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("BOOKING_UNAVAILABLE");
    }

    @Test
    @DisplayName("14: an account block refuses the same person booking through the link with the verified phone (409)")
    void should_refuseLinkBooking_when_accountIsBlocked() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID client = verifiedClient(PHONE);
        blockAccount(master, client);

        ResponseEntity<String> response = linkBook(master, PHONE, slot(3, 12));

        assertThat(response.getStatusCode()).as(response.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).contains("BOOKING_UNAVAILABLE");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM bookings", Integer.class)).isZero();
    }

    @Test
    @DisplayName("14b: a block of the verified phone's hash refuses the link booking too, and another phone is not caught")
    void should_refuseLinkBooking_when_phoneHashBlocked_andNotAnotherPhone() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        verifiedClient(PHONE);
        blockGuestHash(master, PHONE);

        ResponseEntity<String> refused = linkBook(master, PHONE, slot(3, 12));
        ResponseEntity<String> other = linkBook(master, "+380671234599", slot(3, 14));

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(other.getStatusCode()).as(other.getBody()).isEqualTo(HttpStatus.CREATED);
    }

    @Test
    @DisplayName("16: a walk-in for a blocked person is created and attached (walk-ins ignore blocks, MOD-28)")
    void should_createAndAttachWalkIn_when_personIsBlocked() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        UUID client = verifiedClient(PHONE);
        blockAccount(master, client);
        blockGuestHash(master, PHONE);

        ResponseEntity<String> response = walkIn(master, PHONE, slot(3, 12));

        JsonNode data = created(response).path("data");
        UUID bookingId = UUID.fromString(data.path("items").get(0).path("bookingId").asText());
        assertThat(bookingRow(bookingId).get("client_id")).isEqualTo(client);
    }

    /** Blocks until some session waits on an advisory lock it was not granted (the other side of the race). */
    private void awaitSomeoneWaitingOnTheAdvisoryLock() {
        org.awaitility.Awaitility.await().atMost(10, TimeUnit.SECONDS).pollInterval(java.time.Duration.ofMillis(25))
                .until(() -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted", Integer.class) > 0);
    }

    @Test
    @DisplayName("21a: the verification holds the per-phone lock first — a walk-in create WAITS for it, then sees the "
            + "verified account and is attached")
    void should_endAttached_when_walkInRacesAVerificationThatHoldsTheLock() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        String phone = "+380501234502";
        UUID client = unverifiedClient(phone);
        providerTokens.put(master.masterId(), pages.tokenOf(master));
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> verification = pool.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.update("UPDATE users SET phone_verified_at = NOW() WHERE id = ?", client);
                attachService.lockAndFindAccount(phone);
                lockHeld.countDown();
                await(release);
                return attachService.attachByPhone(phone, AttachReason.PHONE_VERIFIED);
            }));
            assertThat(lockHeld.await(30, TimeUnit.SECONDS)).isTrue();
            Future<ResponseEntity<String>> create = pool.submit(() -> walkIn(master, phone, slot(3, 12)));

            awaitSomeoneWaitingOnTheAdvisoryLock();
            release.countDown();
            verification.get(30, TimeUnit.SECONDS);
            ResponseEntity<String> response = create.get(30, TimeUnit.SECONDS);

            JsonNode data = created(response).path("data");
            UUID bookingId = UUID.fromString(data.path("items").get(0).path("bookingId").asText());
            assertThat(bookingRow(bookingId).get("client_id")).isEqualTo(client);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("21b: a walk-in holds the per-phone lock first — the verification WAITS for its commit, then attaches "
            + "the row the walk-in inserted")
    void should_endAttached_when_verificationRacesAWalkInThatHoldsTheLock() throws Exception {
        IndependentMaster master = pages.independentMaster(true);
        String phone = "+380501234503";
        UUID client = unverifiedClient(phone);
        UUID bookingId = UUID.randomUUID();
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Object> walkIn = pool.submit(() -> transactionTemplate.execute(status -> {
                assertThat(attachService.lockAndFindAccount(phone)).as("not verified yet").isEmpty();
                insertGuestRow(bookingId, master, phone);
                lockHeld.countDown();
                await(release);
                return null;
            }));
            assertThat(lockHeld.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Object> verification = pool.submit(() -> transactionTemplate.execute(status -> {
                jdbcTemplate.update("UPDATE users SET phone_verified_at = NOW() WHERE id = ?", client);
                return attachService.attachByPhone(phone, AttachReason.PHONE_VERIFIED);
            }));

            awaitSomeoneWaitingOnTheAdvisoryLock();
            release.countDown();
            walkIn.get(30, TimeUnit.SECONDS);
            verification.get(30, TimeUnit.SECONDS);

            assertThat(bookingRow(bookingId).get("client_id")).isEqualTo(client);
            assertThat(bookingRow(bookingId).get("guest_phone")).isNull();
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void insertGuestRow(UUID bookingId, IndependentMaster master, String phone) {
        ZonedDateTime start = slot(5, 12);
        jdbcTemplate.update("INSERT INTO bookings (id, master_id, master_service_id, salon_id, status, starts_at, "
                        + "ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_phone, cancel_token_hash, created_at, updated_at) "
                        + "VALUES (?, ?, ?, NULL, 'CONFIRMED', ?, ?, 350.00, 60, 0, 'LINK', 'Олена', ?, ?, NOW(), NOW())",
                bookingId, master.masterId(), masterServiceOf(master),
                java.sql.Timestamp.from(start.toInstant()), java.sql.Timestamp.from(start.plusHours(1).toInstant()),
                phone, bookingId.toString().replace("-", "").repeat(2));
    }

    private UUID unverifiedClient(String phone) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, first_name, phone_number, "
                        + "is_active, email_verified) VALUES (?, ?, ?, 'CLIENT', 'Гонка', ?, true, true)",
                id, "race-" + id + "@beautica.test", passwordEncoder.encode(PublicBookingPageFixtures.PASSWORD), phone);
        return id;
    }
}
