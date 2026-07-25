package com.beautica.booking;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA-authored (track 25.x audit, 2026-07-14) — closes the biggest gap found in the dev-authored
 * suite: NOTHING proved that the four comment write paths in {@code BookingService} actually
 * persist the note text through the real HTTP endpoints. Every existing decline/not-complete/
 * cancel integration test asserted status + cancellation_reason only, never the comment column —
 * so a regression that deleted {@code booking.setProviderComment(...)} or
 * {@code booking.setClientCancellationNote(...)} (i.e. exactly D2, the live prod bug this track
 * fixed — "the client's cancel comment was silently discarded") would NOT fail a single test.
 *
 * <p>Also closes: (1) end-to-end normalization — {@code BookingComments.normalize} had a
 * thorough table-driven unit test, but nothing proved a CRLF/zero-width note submitted over real
 * HTTP actually lands normalized in the database; (2) the comment CONTRACT on
 * {@code StatusUpdateRequest.comment} (below) — Phase 25.2 originally made it required (
 * {@code @NotBlank} + {@code @Size(min=10,max=1000)}), then Phase 25.9, shipped the same day,
 * reversed that: the note is now optional for all roles on both {@code /decline} and
 * {@code /not-complete}. Only the raw {@code @Size(max=1000)} DoS ceiling and the
 * control-character {@code @Pattern} guard remain — this class asserts both directions: an
 * absent/blank/short comment is accepted and persists NULL/verbatim, while the surviving
 * ceiling/pattern guards still 400.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Booking comment persistence — D2 regression guard + optional-note contract (Phase 25.9)")
class BookingCommentPersistenceIT extends AbstractIntegrationTest {

    private static final String BOOKINGS_URL = "/api/v1/bookings";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ── D2 regression: client cancel note must actually persist ──────────────────

    @Test
    @DisplayName("Fix D2 regression guard: PATCH /cancel persists the client's own note to "
            + "client_cancellation_note (DB) and returns it on the client's own GET afterwards — "
            + "the exact prod bug where the comment was validated but silently discarded")
    void should_persistClientCancellationNote_when_clientCancelsConfirmedBooking() throws Exception {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertBookingWithStatus(fx, "CONFIRMED", null);
        String clientToken = tokenFor(fx.clientEmail);

        String body = "{\"cancellationReason\":\"CLIENT_CANCELLED\",\"comment\":\"Змінились плани, буду пізніше\"}";
        ResponseEntity<Void> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/cancel", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(clientToken)),
                Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String dbNote = jdbcTemplate.queryForObject(
                "SELECT client_cancellation_note FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbNote)
                .as("D2: client's /cancel comment must be persisted to client_cancellation_note, not discarded")
                .isEqualTo("Змінились плани, буду пізніше");

        JsonNode data = getBookingData(bookingId, clientToken);
        assertThat(data.get("clientCancellationNote").asText())
                .as("the persisted note must round-trip back through GET /bookings/{id}")
                .isEqualTo("Змінились плани, буду пізніше");
    }

    @Test
    @DisplayName("PATCH /cancel with no comment leaves client_cancellation_note NULL (optional field, no crash)")
    void should_leaveClientCancellationNoteNull_when_cancelCommentOmitted() {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertBookingWithStatus(fx, "CONFIRMED", null);

        ResponseEntity<Void> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/cancel", HttpMethod.PATCH,
                new HttpEntity<>("{\"cancellationReason\":\"CLIENT_CANCELLED\"}", bearerHeaders(tokenFor(fx.clientEmail))),
                Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String dbNote = jdbcTemplate.queryForObject(
                "SELECT client_cancellation_note FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbNote).isNull();
    }

    // ── provider_comment must actually persist on /decline and /not-complete ─────

    @Test
    @DisplayName("PATCH /decline persists the provider's note to provider_comment (DB) — "
            + "existing suites only asserted status + cancellation_reason, never this column")
    void should_persistProviderComment_when_providerDeclinesConfirmedBooking() {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertBookingWithStatus(fx, "CONFIRMED", null);

        String body = "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\",\"comment\":\"Майстер захворів, перенесемо на інший день\"}";
        ResponseEntity<Void> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(tokenFor(fx.ownerEmail))),
                Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String dbNote = jdbcTemplate.queryForObject(
                "SELECT provider_comment FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbNote)
                .as("provider's /decline comment must be persisted to provider_comment")
                .isEqualTo("Майстер захворів, перенесемо на інший день");
    }

    @Test
    @DisplayName("PATCH /not-complete persists the provider's note to provider_comment (DB) — "
            + "existing suites only asserted status + cancellation_reason, never this column")
    void should_persistProviderComment_when_providerMarksNotComplete() {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertElapsedBookingWithStatus(fx, "CONFIRMED", null);

        String body = "{\"cancellationReason\":\"CLIENT_NO_SHOW\",\"comment\":\"Клієнт не з'явився, чекали 20 хвилин\"}";
        ResponseEntity<Void> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/not-complete", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(tokenFor(fx.ownerEmail))),
                Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String dbNote = jdbcTemplate.queryForObject(
                "SELECT provider_comment FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbNote)
                .as("provider's /not-complete comment must be persisted to provider_comment")
                .isEqualTo("Клієнт не з'явився, чекали 20 хвилин");
    }

    // ── normalization end-to-end (not just the BookingComments unit test) ────────

    @Test
    @DisplayName("normalization end-to-end: a /cancel comment with CRLF + zero-width + bidi chars "
            + "persists CRLF-collapsed-to-LF and stripped of invisible characters, not the raw payload")
    void should_persistNormalizedNote_when_cancelCommentContainsCrlfAndInvisibleChars() throws Exception {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertBookingWithStatus(fx, "CONFIRMED", null);

        // \r\n between the two sentences, plus a zero-width space (U+200B) inside "плани" and a
        // bidi override (U+202E) before "буду" — all four write paths funnel through
        // BookingComments.normalize before persisting; this proves the /cancel endpoint's request
        // body actually reaches it, end to end, over real HTTP + real Postgres.
        String rawComment = "Особисті причини.\r\nЗмінились​ плани, ‮буду пізніше";
        String body = objectMapper.writeValueAsString(new CancelPayload("CLIENT_CANCELLED", rawComment));

        ResponseEntity<Void> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/cancel", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(tokenFor(fx.clientEmail))),
                Void.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        String dbNote = jdbcTemplate.queryForObject(
                "SELECT client_cancellation_note FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbNote)
                .as("CRLF must collapse to LF and invisible/bidi characters must be stripped in the persisted value")
                .isEqualTo("Особисті причини.\nЗмінились плани, буду пізніше");
        assertThat(dbNote).doesNotContain("\r").doesNotContain("​").doesNotContain("‮");
    }

    private record CancelPayload(String cancellationReason, String comment) {}

    // ── optional-note contract (Phase 25.9 — reverses Phase 25.2's "required, ≥10-char" ─────
    // decision, made and shipped earlier the same day): comment is now optional for all roles
    // on both /decline and /not-complete. @NotBlank and the normalized-length floor
    // (@NormalizedSize) were removed from StatusUpdateRequest.comment; only the raw
    // @Size(max=1000) DoS ceiling and the control-character @Pattern guard remain.

    @Test
    @DisplayName("Phase 25.9: PATCH /decline — 204 when comment field is entirely absent from the "
            + "JSON body, and provider_comment persists NULL")
    void should_return204WithNullProviderComment_when_declineCommentFieldAbsent() {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertBookingWithStatus(fx, "CONFIRMED", null);

        ResponseEntity<Void> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>("{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\"}", bearerHeaders(tokenFor(fx.ownerEmail))),
                Void.class);

        assertThat(resp.getStatusCode())
                .as("an absent comment must now be accepted — the note is optional")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(bookingId)).isEqualTo("DECLINED");
        assertThat(dbProviderComment(bookingId))
                .as("no comment supplied must persist as NULL, never an empty string")
                .isNull();
    }

    @Test
    @DisplayName("Phase 25.9: PATCH /decline — 204 when comment is blank (whitespace only), and "
            + "provider_comment persists NULL (blank normalizes to null)")
    void should_return204WithNullProviderComment_when_declineCommentIsBlank() {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertBookingWithStatus(fx, "CONFIRMED", null);

        ResponseEntity<String> resp = patchDecline(bookingId, tokenFor(fx.ownerEmail), "        ");

        assertThat(resp.getStatusCode())
                .as("a whitespace-only comment must now be accepted — @NotBlank was removed")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbProviderComment(bookingId)).isNull();
    }

    @Test
    @DisplayName("Phase 25.9: PATCH /decline — 204 when comment is 9 characters (below the old, "
            + "now-removed 10-char floor), persisted verbatim")
    void should_return204_when_declineCommentIsNineChars() {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertBookingWithStatus(fx, "CONFIRMED", null);

        ResponseEntity<String> resp = patchDecline(bookingId, tokenFor(fx.ownerEmail), "123456789");

        assertThat(resp.getStatusCode())
                .as("a 9-char comment must be accepted — the min-length floor was removed, not lowered")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbProviderComment(bookingId)).isEqualTo("123456789");
    }

    @Test
    @DisplayName("PATCH /decline — 400 when comment exceeds 1000 raw characters (the DoS-guard "
            + "ceiling survives the note becoming optional)")
    void should_return400_when_declineCommentExceedsRawMaxLength() {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertBookingWithStatus(fx, "CONFIRMED", null);

        ResponseEntity<String> resp = patchDecline(bookingId, tokenFor(fx.ownerEmail), "a".repeat(1001));

        assertThat(resp.getStatusCode())
                .as("an over-1000-char comment must still fail the raw @Size ceiling")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertUnchanged(bookingId, "CONFIRMED");
    }

    @Test
    @DisplayName("Phase 25.9: PATCH /not-complete — 204 when comment field is entirely absent, "
            + "and provider_comment persists NULL")
    void should_return204WithNullProviderComment_when_notCompleteCommentFieldAbsent() {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertElapsedBookingWithStatus(fx, "CONFIRMED", null);

        String body = "{\"cancellationReason\":\"CLIENT_NO_SHOW\"}";
        ResponseEntity<Void> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/not-complete", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(tokenFor(fx.ownerEmail))),
                Void.class);

        assertThat(resp.getStatusCode())
                .as("an absent comment must now be accepted on /not-complete too")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbStatus(bookingId)).isEqualTo("NOT_COMPLETED");
        assertThat(dbProviderComment(bookingId)).isNull();
    }

    @Test
    @DisplayName("Phase 25.9: PATCH /not-complete — 204 when comment is blank (whitespace only), "
            + "and provider_comment persists NULL")
    void should_return204WithNullProviderComment_when_notCompleteCommentIsBlank() {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertElapsedBookingWithStatus(fx, "CONFIRMED", null);

        String body = "{\"cancellationReason\":\"CLIENT_NO_SHOW\",\"comment\":\"   \"}";
        ResponseEntity<Void> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/not-complete", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(tokenFor(fx.ownerEmail))),
                Void.class);

        assertThat(resp.getStatusCode())
                .as("a blank comment must now be accepted on /not-complete too")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbProviderComment(bookingId)).isNull();
    }

    @Test
    @DisplayName("Phase 25.9: PATCH /not-complete — 204 when comment is 9 characters (below the "
            + "old, now-removed 10-char floor), persisted verbatim")
    void should_return204_when_notCompleteCommentIsNineChars() {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertElapsedBookingWithStatus(fx, "CONFIRMED", null);

        String body = "{\"cancellationReason\":\"CLIENT_NO_SHOW\",\"comment\":\"123456789\"}";
        ResponseEntity<Void> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/not-complete", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(tokenFor(fx.ownerEmail))),
                Void.class);

        assertThat(resp.getStatusCode())
                .as("a 9-char comment must be accepted on /not-complete too")
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(dbProviderComment(bookingId)).isEqualTo("123456789");
    }

    @Test
    @DisplayName("PATCH /not-complete — 400 when comment contains a forbidden control character "
            + "(NUL byte) — the @Pattern guard survives the note becoming optional")
    void should_return400_when_notCompleteCommentContainsControlCharacter() {
        Fixture fx = seedSalonBooking();
        UUID bookingId = insertBookingWithStatus(fx, "CONFIRMED", null);

        String body = "{\"cancellationReason\":\"CLIENT_NO_SHOW\",\"comment\":\"legit note\u0000embedded\"}";
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/not-complete", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(tokenFor(fx.ownerEmail))),
                String.class);

        assertThat(resp.getStatusCode())
                .as("an embedded NUL byte must still fail the control-character @Pattern")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertUnchanged(bookingId, "CONFIRMED");
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private ResponseEntity<String> patchDecline(UUID bookingId, String token, String comment) {
        String escaped = comment.replace("\"", "\\\"");
        String body = "{\"cancellationReason\":\"PROVIDER_UNAVAILABLE\",\"comment\":\"" + escaped + "\"}";
        return restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId + "/decline", HttpMethod.PATCH,
                new HttpEntity<>(body, bearerHeaders(token)),
                String.class);
    }

    private void assertUnchanged(UUID bookingId, String expectedStatus) {
        String dbStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
        assertThat(dbStatus)
                .as("a rejected (400) request must never mutate booking status")
                .isEqualTo(expectedStatus);
    }

    private String dbStatus(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private String dbProviderComment(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT provider_comment FROM bookings WHERE id = ?", String.class, bookingId);
    }

    private JsonNode getBookingData(UUID bookingId, String token) throws Exception {
        ResponseEntity<String> resp = restTemplate.exchange(
                BOOKINGS_URL + "/" + bookingId, HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(token)),
                String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readTree(resp.getBody()).get("data");
    }

    private record Fixture(UUID salonId, String ownerEmail, UUID masterId, UUID masterServiceId, String clientEmail, UUID clientId) {}

    private Fixture seedSalonBooking() {
        String ownerEmail = "comment-persist-owner-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = createUser(ownerEmail, "SALON_OWNER", null);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + salonId);

        String masterEmail = "comment-persist-master-" + System.nanoTime() + "@beautica.test";
        UUID masterUserId = createUser(masterEmail, "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) " +
                        "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) " +
                        "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveServiceTypeId());
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        String clientEmail = "comment-persist-client-" + System.nanoTime() + "@beautica.test";
        UUID clientId = createUser(clientEmail, "CLIENT", null);

        return new Fixture(salonId, ownerEmail, masterId, masterServiceId, clientEmail, clientId);
    }

    private UUID insertBookingWithStatus(Fixture fx, String status, String providerComment) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, " +
                        "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, " +
                        "booking_source, provider_comment, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, NOW() + interval '1 day', NOW() + interval '1 day 1 hour', " +
                        "500.00, 60, 0, 'APP', ?, NOW(), NOW())",
                bookingId, fx.clientId, fx.masterId, fx.masterServiceId, fx.salonId, status, providerComment);
        return bookingId;
    }

    /**
     * ELAPSED variant of {@link #insertBookingWithStatus} — this track's guard
     * ({@code BookingTemporalGuard#assertElapsedForNotComplete}) now requires {@code now >=
     * startsAt} for {@code /not-complete}, so the note-persistence/optional-comment tests that
     * exercise that endpoint need a booking whose slot has already begun, unlike the FUTURE default
     * (which still backs every {@code /decline} test in this class — decline is future-only).
     */
    private UUID insertElapsedBookingWithStatus(Fixture fx, String status, String providerComment) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, " +
                        "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, " +
                        "booking_source, provider_comment, created_at, updated_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, NOW() - interval '2 hours', NOW() - interval '1 hour', " +
                        "500.00, 60, 0, 'APP', ?, NOW(), NOW())",
                bookingId, fx.clientId, fx.masterId, fx.masterServiceId, fx.salonId, status, providerComment);
        return bookingId;
    }

    private UUID resolveServiceTypeId() {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }

    private UUID createUser(String email, String role, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified) VALUES (?, ?, ?, ?, ?, true, true)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return id;
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
}
