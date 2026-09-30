package com.beautica.notification.inapp.controller;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.enums.BookingStatus;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.ApiResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.inapp.entity.InAppNotificationType;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.CatalogCategoryRepository;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import com.beautica.support.LocalityTestLookup;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 334 — {@code NotificationFeedController}'s HTTP contract: pagination bounds, ownership
 * (IDOR), idempotency, read-all cutoff semantics, the unread-count cap, and target/params
 * resolution for a couple of representative event shapes.
 */
class NotificationFeedControllerIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    private static final AtomicInteger SORT_ORDER_SEQ = new AtomicInteger(710_000);

    @Autowired
    private TestRestTemplate restTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private MasterRepository masterRepository;
    @Autowired
    private SalonRepository salonRepository;
    @Autowired
    private CatalogCategoryRepository catalogCategoryRepository;
    @Autowired
    private ServiceTypeRepository serviceTypeRepository;
    @Autowired
    private ServiceRepository serviceRepository;
    @Autowired
    private MasterServiceRepository masterServiceRepository;
    @Autowired
    private BookingRepository bookingRepository;

    private User client;
    private User otherClient;
    private Master master;
    private MasterServiceAssignment masterService;

    @BeforeEach
    void setUp() {
        client = persistUser(Role.CLIENT);
        otherClient = persistUser(Role.CLIENT);
        User masterUser = persistUser(Role.INDEPENDENT_MASTER);

        master = masterRepository.save(Master.builder()
                .user(masterUser)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build());

        CatalogCategory category = catalogCategoryRepository.save(CatalogCategory.builder()
                .nameUk("Нігті")
                .nameEn("Nails")
                .sortOrder(SORT_ORDER_SEQ.getAndIncrement())
                .build());
        ServiceType serviceType = serviceTypeRepository.save(ServiceType.builder()
                .category(category)
                .nameUk("Манікюр")
                .nameEn("Manicure")
                .slug("feed-it-type-" + UUID.randomUUID())
                .platformCategoryName("NAIL_SERVICE")
                .build());
        ServiceDefinition serviceDefinition = serviceRepository.save(ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Gel Manicure")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("450.00"))
                .serviceType(serviceType)
                .isActive(true)
                .build());
        masterService = masterServiceRepository.save(MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build());
    }

    private User persistUser(Role role) {
        User user = new User(
                "feed-it-" + UUID.randomUUID() + "@example.com", passwordEncoder.encode(TEST_PASSWORD),
                role, "Anna", "Kovalenko", "+380501111111");
        user.setEmailVerified(true);
        return userRepository.save(user);
    }

    private Booking newBooking(User forClient, OffsetDateTime startsAt) {
        return bookingRepository.save(Booking.builder()
                .client(forClient)
                .master(master)
                .masterService(masterService)
                .status(BookingStatus.CONFIRMED)
                .startsAt(startsAt)
                .endsAt(startsAt.plusHours(1))
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .idempotencyKey("feed-it-idem-" + UUID.randomUUID())
                .build());
    }

    /**
     * Raw SQL, not {@code InAppNotificationRepository#insertIgnoringDuplicate} — that
     * {@code @Modifying} query requires an already-open transaction, which a plain
     * {@code @SpringBootTest} fixture method (this class, via {@link AbstractIntegrationTest})
     * does not provide.
     */
    private UUID insertNotification(UUID recipientId, InAppNotificationType type, UUID bookingId, int seq) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO in_app_notification (id, recipient_user_id, type, booking_id, dedup_key)
                VALUES (?, ?, ?, ?, ?)
                """, id, recipientId, type.name(), bookingId, type.name() + ":" + bookingId + ":" + seq);
        return id;
    }

    private UUID insertInviteAcceptedNotification(UUID recipientId, UUID salonId, UUID subjectUserId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO in_app_notification (id, recipient_user_id, type, salon_id, subject_user_id, dedup_key)
                VALUES (?, ?, 'INVITE_ACCEPTED', ?, ?, ?)
                """, id, recipientId, salonId, subjectUserId, "INVITE_ACCEPTED:" + salonId + ":" + subjectUserId);
        return id;
    }

    private String tokenFor(String email) {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login must succeed for %s", email).isEqualTo(HttpStatus.OK);
        try {
            return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                    .data().accessToken();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> getFeed(String token, String query) {
        return restTemplate.exchange("/api/v1/notifications" + query, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)), String.class);
    }

    // ── list ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_listOwnItemsNewestFirst")
    void should_listOwnItemsNewestFirst() throws Exception {
        Booking older = newBooking(client, OffsetDateTime.of(2026, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC));
        Booking newer = newBooking(client, OffsetDateTime.of(2026, 6, 2, 9, 0, 0, 0, ZoneOffset.UTC));
        UUID olderId = insertNotification(client.getId(), InAppNotificationType.BOOKING_CREATED, older.getId(), 1);
        // A tiny sleep-free ordering guarantee: insert the "newer" row via a second dedup key —
        // created_at DEFAULT now() advances monotonically enough for the assertion below since the
        // two inserts are two separate statements.
        UUID newerId = insertNotification(client.getId(), InAppNotificationType.BOOKING_CREATED, newer.getId(), 2);
        String token = tokenFor(client.getEmail());

        ResponseEntity<String> resp = getFeed(token, "?page=0&size=20");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode data = objectMapper.readTree(resp.getBody()).path("data");
        assertThat(data).hasSize(2);
        assertThat(data.get(0).path("id").asText()).as("newest first").isEqualTo(newerId.toString());
        assertThat(data.get(1).path("id").asText()).isEqualTo(olderId.toString());
    }

    @Test
    @DisplayName("should_return400_when_sizeOutOfBounds")
    void should_return400_when_sizeOutOfBounds() {
        String token = tokenFor(client.getEmail());

        assertThat(getFeed(token, "?size=0").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getFeed(token, "?size=51").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getFeed(token, "?size=50").getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getFeed(token, "?size=1").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("should_return400_when_pageAboveUpperBound")
    void should_return400_when_pageAboveUpperBound() {
        String token = tokenFor(client.getEmail());

        assertThat(getFeed(token, "?page=10001").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getFeed(token, "?page=-1").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(getFeed(token, "?page=10000").getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("should_return401_when_anonymous")
    void should_return401_when_anonymous() {
        ResponseEntity<String> resp = restTemplate.getForEntity("/api/v1/notifications", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("should_neverSerializeNoteText")
    void should_neverSerializeNoteText() throws Exception {
        // chk_client_cancellation_note_status (V114) requires status = CANCELLED whenever this
        // column is non-null.
        String secretNote = "very-secret-cancellation-note-xyz";
        Booking booking = newBooking(client, OffsetDateTime.of(2026, 6, 3, 9, 0, 0, 0, ZoneOffset.UTC));
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setClientCancellationNote(secretNote);
        bookingRepository.save(booking);
        insertNotification(client.getId(), InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, booking.getId(), 1);
        String token = tokenFor(client.getEmail());

        ResponseEntity<String> resp = getFeed(token, "?size=20");

        assertThat(resp.getBody()).doesNotContain(secretNote);
        assertThat(resp.getBody().toLowerCase(java.util.Locale.ROOT))
                .doesNotContain("clientcomment").doesNotContain("providercomment")
                .doesNotContain("clientcancellationnote");
    }

    // ── unread-count ─────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_capUnreadCountAt99")
    void should_capUnreadCountAt99() {
        for (int i = 0; i < 105; i++) {
            Booking booking = newBooking(client, OffsetDateTime.of(2026, 1, 1, 9, 0, 0, 0, ZoneOffset.UTC).plusDays(i));
            insertNotification(client.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), i);
        }
        String token = tokenFor(client.getEmail());

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/notifications/unread-count",
                HttpMethod.GET, new HttpEntity<>(bearerHeaders(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"count\":99");
    }

    // ── mark read ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_return204_when_markingOwnItem_andIdempotentOnReplay")
    void should_return204_when_markingOwnItem_andIdempotentOnReplay() {
        Booking booking = newBooking(client, OffsetDateTime.of(2026, 6, 4, 9, 0, 0, 0, ZoneOffset.UTC));
        UUID id = insertNotification(client.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), 1);
        String token = tokenFor(client.getEmail());

        ResponseEntity<String> first = restTemplate.exchange("/api/v1/notifications/" + id + "/read",
                HttpMethod.PATCH, new HttpEntity<>(bearerHeaders(token)), String.class);
        ResponseEntity<String> replay = restTemplate.exchange("/api/v1/notifications/" + id + "/read",
                HttpMethod.PATCH, new HttpEntity<>(bearerHeaders(token)), String.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(replay.getStatusCode()).as("a replay against an already-read row is still 204").isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("should_return404_when_markingOthersItem")
    void should_return404_when_markingOthersItem() {
        Booking booking = newBooking(otherClient, OffsetDateTime.of(2026, 6, 5, 9, 0, 0, 0, ZoneOffset.UTC));
        UUID otherId = insertNotification(otherClient.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), 1);
        String token = tokenFor(client.getEmail());

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/notifications/" + otherId + "/read",
                HttpMethod.PATCH, new HttpEntity<>(bearerHeaders(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("should_return404_when_idUnknown")
    void should_return404_when_idUnknown() {
        String token = tokenFor(client.getEmail());

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/notifications/" + UUID.randomUUID() + "/read",
                HttpMethod.PATCH, new HttpEntity<>(bearerHeaders(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // ── mark all read ────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_markAllReadUpToCutoff_leavingNewer")
    void should_markAllReadUpToCutoff_leavingNewer() throws Exception {
        Booking bookingA = newBooking(client, OffsetDateTime.of(2026, 6, 6, 9, 0, 0, 0, ZoneOffset.UTC));
        UUID beforeId = insertNotification(client.getId(), InAppNotificationType.BOOKING_CREATED, bookingA.getId(), 1);
        java.sql.Timestamp cutoffTs = jdbcTemplate.queryForObject(
                "SELECT created_at FROM in_app_notification WHERE id = ?", java.sql.Timestamp.class, beforeId);
        Instant cutoff = cutoffTs.toInstant();
        Instant afterCutoff = cutoff.plusSeconds(60);
        jdbcTemplate.update("UPDATE in_app_notification SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(afterCutoff), insertAfterCutoffRow());

        String token = tokenFor(client.getEmail());
        HttpHeaders headers = bearerHeaders(token);
        String body = "{\"upTo\":\"" + cutoff + "\"}";
        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/notifications/read-all",
                HttpMethod.PATCH, new HttpEntity<>(body, headers), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.path("data").path("updated").asInt()).isEqualTo(1);

        JsonNode feed = objectMapper.readTree(getFeed(token, "?size=20").getBody()).path("data");
        boolean afterRowStillUnread = false;
        for (JsonNode row : feed) {
            if (!row.path("id").asText().equals(beforeId.toString())) {
                afterRowStillUnread = !row.path("read").asBoolean();
            }
        }
        assertThat(afterRowStillUnread)
                .as("the row created AFTER the cutoff must remain unread")
                .isTrue();
    }

    /** Inserts a second row for {@link #client} and returns its id, for the cutoff test above. */
    private UUID insertAfterCutoffRow() {
        Booking booking = newBooking(client, OffsetDateTime.of(2026, 6, 6, 10, 0, 0, 0, ZoneOffset.UTC));
        return insertNotification(client.getId(), InAppNotificationType.BOOKING_CREATED, booking.getId(), 2);
    }

    /**
     * Audit-fix cycle 1, finding 3 (LOW, security) — {@code upTo} was unbounded; now rejected with
     * 400 more than 5 minutes ahead of the server clock ({@code NotificationFeedService#markAllRead}).
     */
    @Test
    @DisplayName("should_return400_when_upToIsFarInTheFuture")
    void should_return400_when_upToIsFarInTheFuture() {
        String token = tokenFor(client.getEmail());
        String body = "{\"upTo\":\"" + Instant.now().plusSeconds(600) + "\"}";

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/notifications/read-all",
                HttpMethod.PATCH, new HttpEntity<>(body, bearerHeaders(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should_return200_when_upToIsJustInsideTheAllowedFutureSkew")
    void should_return200_when_upToIsJustInsideTheAllowedFutureSkew() {
        String token = tokenFor(client.getEmail());
        String body = "{\"upTo\":\"" + Instant.now().plusSeconds(60) + "\"}";

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/notifications/read-all",
                HttpMethod.PATCH, new HttpEntity<>(body, bearerHeaders(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── target / params resolution ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("should_targetSalonTeam_withSubjectName_when_inviteAcceptedAndStillOwner")
    void should_targetSalonTeam_withSubjectName_when_inviteAcceptedAndStillOwner() throws Exception {
        User owner = persistUser(Role.SALON_OWNER);
        Salon salon = salonRepository.save(Salon.builder()
                .cityId(LocalityTestLookup.majorCityIdByName(jdbcTemplate, "Вінниця"))
                .owner(owner)
                .name("Feed IT Salon " + UUID.randomUUID())
                .isActive(true)
                .build());
        User newTeammate = persistUser(Role.SALON_MASTER);

        insertInviteAcceptedNotification(owner.getId(), salon.getId(), newTeammate.getId());
        String token = tokenFor(owner.getEmail());

        JsonNode data = objectMapper.readTree(getFeed(token, "?size=20").getBody()).path("data");
        assertThat(data).hasSize(1);
        JsonNode row = data.get(0);
        assertThat(row.path("target").path("kind").asText()).isEqualTo("SALON_TEAM");
        assertThat(row.path("target").path("salonId").asText()).isEqualTo(salon.getId().toString());
        assertThat(row.path("params").path("subjectName").asText()).isEqualTo("Anna Kovalenko");
        assertThat(row.path("params").path("subjectRole").asText()).isEqualTo("SALON_MASTER");
    }

    /**
     * Audit-fix cycle 1, finding 1 (HIGH, security) — {@code resolveInviteAccepted} used to build
     * {@code params} (the new teammate's name/role) from the batch-loaded subject row even when the
     * recipient had since lost management access to the salon; only {@code target} was nulled. An
     * admin removed from the salon after the notification was written must get params=null AND
     * target.kind=NONE, matching every other "no longer visible" notification shape.
     */
    @Test
    @DisplayName("should_nullParams_when_inviteAcceptedRecipientNoLongerManagesSalon")
    void should_nullParams_when_inviteAcceptedRecipientNoLongerManagesSalon() throws Exception {
        User owner = persistUser(Role.SALON_OWNER);
        Salon salon = salonRepository.save(Salon.builder()
                .cityId(LocalityTestLookup.majorCityIdByName(jdbcTemplate, "Вінниця"))
                .owner(owner)
                .name("Feed IT Salon " + UUID.randomUUID())
                .isActive(true)
                .build());
        User admin = persistUser(Role.SALON_ADMIN);
        jdbcTemplate.update("UPDATE users SET salon_id = ? WHERE id = ?", salon.getId(), admin.getId());
        User newTeammate = persistUser(Role.SALON_MASTER);
        insertInviteAcceptedNotification(admin.getId(), salon.getId(), newTeammate.getId());

        // The admin is removed from the salon AFTER the notification was written — the recipient no
        // longer manages it by the time the feed is read.
        jdbcTemplate.update("UPDATE users SET salon_id = NULL WHERE id = ?", admin.getId());
        String token = tokenFor(admin.getEmail());

        JsonNode data = objectMapper.readTree(getFeed(token, "?size=20").getBody()).path("data");

        assertThat(data).hasSize(1);
        JsonNode row = data.get(0);
        assertThat(row.path("target").path("kind").asText()).isEqualTo("NONE");
        assertThat(row.path("params").isNull())
                .as("params must be null once the recipient no longer manages the salon")
                .isTrue();
    }

    @Test
    @DisplayName("should_renderDetachedClientSentinel_when_clientSelfDeletedAfterNotifying")
    void should_renderDetachedClientSentinel_when_clientSelfDeletedAfterNotifying() throws Exception {
        // Recipient = the performing master — notifyBookingEvent's masterOnly recipient set is the
        // simplest shape to exercise here, and master visibility never depends on the client leg.
        Booking booking = newBooking(client, OffsetDateTime.of(2026, 6, 7, 9, 0, 0, 0, ZoneOffset.UTC));
        UUID masterUserId = master.getUser().getId();
        insertNotification(masterUserId, InAppNotificationType.BOOKING_CANCELLED_BY_CLIENT, booking.getId(), 1);

        // Client self-deletes — Booking#detachClient replaces the client association with a
        // guestName sentinel; simulate the DB effect directly (out of scope of this phase to drive
        // the real self-delete cascade). chk_bookings_guest_fields' DETACHED arm (V162) requires
        // client_detached_at to be set too, or the UPDATE itself violates the CHECK.
        jdbcTemplate.update("UPDATE bookings SET client_id = NULL, guest_name = 'Видалений клієнт', "
                + "client_detached_at = NOW() WHERE id = ?", booking.getId());

        String token = tokenFor(master.getUser().getEmail());
        JsonNode data = objectMapper.readTree(getFeed(token, "?size=20").getBody()).path("data");

        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("params").path("counterpartName").asText())
                .as("the detached-client sentinel must still render — never NONE for a detached "
                        + "(rather than deleted) booking")
                .isEqualTo("Видалений клієнт");
    }

    @Test
    @DisplayName("should_targetReview_when_reviewRequestedAndReviewable")
    void should_targetReview_when_reviewRequestedAndReviewable() throws Exception {
        OffsetDateTime pastStart = OffsetDateTime.now(ZoneOffset.UTC).minus(3, ChronoUnit.HOURS);
        Booking booking = bookingRepository.save(Booking.builder()
                .client(client)
                .master(master)
                .masterService(masterService)
                .status(BookingStatus.COMPLETED)
                .startsAt(pastStart)
                .endsAt(pastStart.plusHours(1))
                .priceAtBooking(new BigDecimal("450.00"))
                .durationMinutesAtBooking(60)
                .bufferMinutesAtBooking(0)
                .idempotencyKey("feed-it-review-idem-" + UUID.randomUUID())
                .build());
        insertNotification(client.getId(), InAppNotificationType.REVIEW_REQUESTED, booking.getId(), 1);
        String token = tokenFor(client.getEmail());

        JsonNode data = objectMapper.readTree(getFeed(token, "?size=20").getBody()).path("data");

        assertThat(data).hasSize(1);
        assertThat(data.get(0).path("target").path("kind").asText()).isEqualTo("BOOKING_REVIEW");
    }
}
