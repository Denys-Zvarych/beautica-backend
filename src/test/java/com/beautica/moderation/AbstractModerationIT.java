package com.beautica.moderation;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.moderation.dto.ContentReportEmailModel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;

/**
 * Shared seeding + HTTP helpers for the moderation integration tests (phases 395/396, §M-3): users, salons, masters,
 * bookings, reviews, client reviews and portfolio photos inserted with raw SQL, plus report / login / internal-API
 * calls. Stateless — every helper takes what it needs and returns ids.
 */
abstract class AbstractModerationIT extends AbstractIntegrationTest {

    /** Phase 414: matches the support-e-mail model of a report on {@code targetId} (type + reason by wire name). */
    protected static ContentReportEmailModel emailFor(String targetType, UUID targetId, String reason) {
        return argThat(m -> m != null && m.targetType().name().equals(targetType)
                && m.targetId().equals(targetId) && m.reason().name().equals(reason));
    }

    /** Phase 414: matches the support-e-mail model of a report filed by {@code reporterId}. */
    protected static ContentReportEmailModel emailFromReporter(UUID reporterId) {
        return argThat(m -> m != null && reporterId.equals(m.reporterUserId()));
    }


    static final String TEST_PASSWORD = "Str0ngP@ss1!";
    static final String REPORTS_URL = "/api/v1/reports";
    /** Phase 397 client blocks. */
    static final String BLOCKS_URL = "/api/v1/blocks";
    static final String INTERNAL_KEY_HEADER = "X-Internal-Key";
    /** {@code app.internal-api-key} in application-test.yml. */
    static final String INTERNAL_KEY = "test-internal-api-key";

    private static String encodedPassword;

    private final Map<UUID, UUID> masterServiceIds = new HashMap<>();

    @Autowired
    protected TestRestTemplate restTemplate;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected PasswordEncoder passwordEncoder;

    // ── HTTP helpers ─────────────────────────────────────────────────────────────

    protected ResponseEntity<String> postReport(String token, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(REPORTS_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** Phase 397 — {@code POST /blocks {bookingId}} as the token's user. */
    protected ResponseEntity<String> postBlock(String token, UUID bookingId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        String body = objectMapper.createObjectNode().put("bookingId", bookingId.toString()).toString();
        return restTemplate.exchange(BLOCKS_URL, HttpMethod.POST, new HttpEntity<>(body, headers), String.class);
    }

    /** A body-less authenticated call (GET / DELETE). */
    protected ResponseEntity<String> exchange(HttpMethod method, String path, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(path, method, new HttpEntity<>(headers), String.class);
    }

    protected ResponseEntity<String> reportReview(String reporterEmail, UUID reviewId) throws Exception {
        return postReport(tokenFor(reporterEmail), reportBody("REVIEW", reviewId, "OFFENSIVE", null));
    }

    protected ResponseEntity<String> internal(HttpMethod method, String path, String body, String key) {
        HttpHeaders headers = new HttpHeaders();
        if (key != null) {
            headers.set(INTERNAL_KEY_HEADER, key);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), String.class);
    }

    protected ResponseEntity<String> resolve(UUID reportId, String action) {
        String body = objectMapper.createObjectNode().put("action", action).put("note", "checked by support").toString();
        return internal(HttpMethod.POST, "/api/v1/internal/moderation/reports/" + reportId + "/resolve", body,
                INTERNAL_KEY);
    }

    protected ResponseEntity<String> getAnonymous(String path) {
        return restTemplate.exchange(path, HttpMethod.GET, HttpEntity.EMPTY, String.class);
    }

    protected String tokenFor(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login must succeed for %s", email).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    protected JsonNode data(ResponseEntity<String> resp) {
        try {
            return objectMapper.readTree(resp.getBody()).path("data");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected JsonNode withoutRequestId(ResponseEntity<String> resp) throws Exception {
        ObjectNode node = (ObjectNode) objectMapper.readTree(resp.getBody());
        node.remove("requestId");
        return node;
    }

    protected String reportBody(String targetType, UUID targetId, String reason, String comment) {
        ObjectNode node = objectMapper.createObjectNode()
                .put("targetType", targetType)
                .put("targetId", targetId.toString())
                .put("reason", reason);
        if (comment != null) {
            node.put("comment", comment);
        }
        return node.toString();
    }

    protected static String unique(String prefix) {
        return "report-" + prefix + "-" + UUID.randomUUID() + "@beautica.test";
    }

    // ── phase 414 e-mail action link helpers (shared by the action-link ITs) ──────────────────

    static final String ACTION_CONFIRM_URL = "/api/v1/moderation/actions/confirm";
    private static final java.util.regex.Pattern NONCE_FIELD =
            java.util.regex.Pattern.compile("name=\"nonce\" value=\"([A-Za-z0-9_-]+)\"");

    /** The confirm-page form POST ({@code token} / {@code nonce} omitted when {@code null}). */
    protected ResponseEntity<String> postConfirm(String token, String nonce) {
        org.springframework.util.MultiValueMap<String, String> form = new org.springframework.util.LinkedMultiValueMap<>();
        if (token != null) {
            form.add("token", token);
        }
        if (nonce != null) {
            form.add("nonce", nonce);
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        return restTemplate.exchange(ACTION_CONFIRM_URL, HttpMethod.POST, new HttpEntity<>(form, headers), String.class);
    }

    /** The single-use nonce of a 200 confirm page. */
    protected static String nonceOf(ResponseEntity<String> page) {
        assertThat(page.getStatusCode()).as("confirm page: %s", page.getBody()).isEqualTo(HttpStatus.OK);
        java.util.regex.Matcher matcher = NONCE_FIELD.matcher(page.getBody());
        assertThat(matcher.find()).as("confirm page carries a nonce").isTrue();
        return matcher.group(1);
    }

    protected int openReports(UUID targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_reports WHERE target_id = ? AND status = 'OPEN'", Integer.class,
                targetId);
    }

    /** Unused, unrevoked action-link rows of a target (expiry ignored). */
    protected int liveTokens(UUID targetId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM moderation_action_tokens WHERE target_id = ? "
                + "AND used_at IS NULL AND revoked_at IS NULL", Integer.class, targetId);
    }

    // ── queries ──────────────────────────────────────────────────────────────────

    protected int countReports(UUID reporterId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_reports WHERE reporter_user_id = ?", Integer.class, reporterId);
    }

    protected int countReportsOnTarget(String targetType, UUID targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_reports WHERE target_type = ? AND target_id = ?",
                Integer.class, targetType, targetId);
    }

    protected String moderationStatus(UUID reviewId) {
        return jdbcTemplate.queryForObject(
                "SELECT moderation_status FROM reviews WHERE id = ?", String.class, reviewId);
    }

    protected UUID reportIdOn(UUID reporterId, UUID targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM content_reports WHERE reporter_user_id = ? AND target_id = ?",
                UUID.class, reporterId, targetId);
    }

    protected boolean blockExists(UUID id) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM client_blocks WHERE id = ?)", Boolean.class, id));
    }

    protected int countBlocks() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM client_blocks", Integer.class);
    }

    // ── seeding fixtures ─────────────────────────────────────────────────────────

    record SalonFixture(UUID salonId, String ownerEmail, UUID masterId, String masterEmail) {}

    record IndependentMaster(UUID userId, UUID masterId) {}

    /** A CLIENT account that has a COMPLETED booking with the salon — an eligible auto-hide reporter. */
    record EligibleClient(UUID userId, String email) {}

    protected SalonFixture createSalon() {
        String ownerEmail = unique("owner");
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);
        UUID salonId = insertSalon(ownerId);
        String masterEmail = unique("master");
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);
        return new SalonFixture(salonId, ownerEmail, masterId, masterEmail);
    }

    protected UUID insertSalon(UUID ownerId) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());
        return salonId;
    }

    /**
     * Accounts are created ONE DAY in the past: the auto-hide eligibility rule (D19) only counts a reporter whose
     * account predates the review, and the tests seed reviews before their reporters. A test that needs a fresh
     * account moves {@code created_at} forward explicitly ({@link #registerAfter}).
     */
    protected UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, true, true, NOW() - interval '1 day')",
                id, email, encodedPassword(), role, salonId);
        return id;
    }

    /** Moves an account's registration to one minute after the review was written. */
    protected void registerAfter(UUID userId, UUID reviewId) {
        jdbcTemplate.update("UPDATE users SET created_at = (SELECT created_at FROM reviews WHERE id = ?) "
                + "+ interval '1 minute' WHERE id = ?", reviewId, userId);
    }

    /** One real BCrypt hash per test class (§M-5: real algorithm), not one per seeded user — keeps ITs fast. */
    private String encodedPassword() {
        if (encodedPassword == null) {
            encodedPassword = passwordEncoder.encode(TEST_PASSWORD);
        }
        return encodedPassword;
    }

    protected EligibleClient eligibleClient(SalonFixture salon) {
        String email = unique("eligible");
        UUID id = createUser(email, "CLIENT", null);
        insertCompletedBooking(id, salon);
        return new EligibleClient(id, email);
    }

    protected UUID insertCompletedBooking(UUID clientId, SalonFixture salon) {
        return insertBooking(clientId, salon, "COMPLETED");
    }

    protected UUID insertBooking(UUID clientId, SalonFixture salon, String status) {
        UUID masterServiceId = masterServiceIds.computeIfAbsent(salon.masterId(), id -> insertMasterService(salon));
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, salon.masterId(), masterServiceId, salon.salonId(), status);
        return bookingId;
    }

    /** One service per salon master, created on first use (JUnit builds a fresh instance per test method). */
    private UUID insertMasterService(SalonFixture salon) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salon.salonId(), resolveUnusedServiceTypeId("SALON", salon.salonId()));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, salon.masterId(), serviceDefId);
        return masterServiceId;
    }

    /**
     * Phase 397 — a LINK guest or STAFF walk-in booking (no account, guest «Марія Шевченко» with {@code phone}, E.164)
     * on the salon's master, reusing its seeded service.
     */
    protected UUID insertGuestBooking(SalonFixture salon, String source, String phone) {
        UUID seed = insertBooking(createUser(unique("seed"), "CLIENT", null), salon, "COMPLETED");
        UUID masterServiceId = jdbcTemplate.queryForObject(
                "SELECT master_service_id FROM bookings WHERE id = ?", UUID.class, seed);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, ?, ?, 'COMPLETED', NOW() - interval '4 hours', "
                        + "NOW() - interval '3 hours', 500.00, 60, 0, ?, 'Марія', 'Шевченко', ?, NOW(), NOW())",
                bookingId, salon.masterId(), masterServiceId, salon.salonId(), source, phone);
        return bookingId;
    }

    protected UUID insertReview(UUID clientId, SalonFixture salon, String comment) {
        return insertReview(clientId, salon, comment, 1);
    }

    protected UUID insertReview(UUID clientId, SalonFixture salon, String comment, int rating) {
        UUID bookingId = insertCompletedBooking(clientId, salon);
        UUID reviewId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, comment, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                reviewId, bookingId, clientId, salon.masterId(), salon.salonId(), rating, comment);
        return reviewId;
    }

    /** A review of an INDEPENDENT_MASTER (salon_id NULL) — exercises the null-salon arm of the provider predicates. */
    protected UUID insertIndependentReview(UUID clientId, UUID masterId) {
        UUID bookingId = insertIndependentBooking(clientId, masterId);
        UUID reviewId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, comment, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, NULL, 1, 'Погано', NOW(), NOW())",
                reviewId, bookingId, clientId, masterId);
        return reviewId;
    }

    protected UUID insertClientReview(UUID bookingId, UUID clientId, SalonFixture salon) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO client_reviews (id, booking_id, subject_client_id, author_master_id, salon_id, rating, "
                        + "comment, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 2, 'Запізнився', NOW(), NOW())",
                id, bookingId, clientId, salon.masterId(), salon.salonId());
        return id;
    }

    protected UUID insertMasterPortfolioPhoto(UUID masterId) {
        UUID id = UUID.randomUUID();
        String r2Key = "portfolio/independent/" + masterId + "/" + id + ".jpg";
        jdbcTemplate.update(
                "INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, r2_key, r2_url, "
                        + "created_at, updated_at) VALUES (?, NULL, 'MASTER', ?, 'PORTFOLIO', ?, ?, NOW(), NOW())",
                id, masterId, r2Key, "https://pub.example.r2.dev/" + r2Key);
        return id;
    }

    protected UUID insertSalonPortfolioPhoto(UUID salonId) {
        UUID id = UUID.randomUUID();
        String r2Key = "portfolio/salons/" + salonId + "/" + id + ".jpg";
        jdbcTemplate.update(
                "INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, r2_key, r2_url, "
                        + "created_at, updated_at) VALUES (?, NULL, 'SALON', ?, 'PORTFOLIO', ?, ?, NOW(), NOW())",
                id, salonId, r2Key, "https://pub.example.r2.dev/" + r2Key);
        return id;
    }

    protected IndependentMaster createIndependentMaster(String email) {
        UUID userId = createUser(email, "INDEPENDENT_MASTER", null);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                masterId, userId);
        return new IndependentMaster(userId, masterId);
    }

    protected UUID insertIndependentBooking(UUID clientId, UUID masterId) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Indep Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, masterId, resolveUnusedServiceTypeId("INDEPENDENT_MASTER", masterId));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 'COMPLETED', NOW() - interval '5 hours', NOW() - interval '4 hours', "
                        + "500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId);
        return bookingId;
    }
}
