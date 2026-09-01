package com.beautica.auth;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.salon.dto.SalonInviteHistoryResponse;
import com.beautica.salon.dto.SalonInviteResponse;
import com.beautica.salon.service.SalonService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Re-inviting an address whose previous invite lapsed must RETIRE the old row, not delete it.
 *
 * <p><strong>REGRESSION GUARD — every assertion in {@link #should_supersedeTheLapsedRow_when_the
 * SameAddressIsReInvited()} fails against the pre-change code.</strong> Before B4,
 * {@code InvitePersistenceService} ran {@code inviteTokenRepository.delete(expired)} +
 * {@code flush()}: the lapsed row was gone from the database by the time the replacement was
 * inserted. So the row count for {@code (salon, email)} was 1 rather than 2, there was no older
 * row on which to read {@code revoked_reason = 'SUPERSEDED'} (the column did not exist either),
 * and the history endpoint could return only the newest invite. The owner's record that they had
 * already tried to reach this person once was destroyed on every retry.
 *
 * <p><strong>Second guard —
 * {@link #should_notThrowIncorrectResultSize_when_theAddressIsInvitedAThirdTime()}.</strong> Once
 * rows stop being deleted, two {@code is_used = false} rows can exist for one
 * {@code (salon, lower(email))}. The dispatch-idempotency finder returns an {@code Optional}, so
 * had B3 not narrowed it with {@code AndRevokedAtIsNull} the SECOND re-invite of any address would
 * raise {@code IncorrectResultSizeDataAccessException} — a hard 500 on
 * {@code POST /salons/{id}/invite}, reproducible on demand, for every salon. B3 and B4 are only
 * safe together and this test is the gate on that pairing. (It is not a guard against the OLD
 * code, which deleted rather than kept — it is the guard that B4 cannot ship without B3.)
 *
 * <p>Dispatch goes through {@code SalonService#inviteMaster} — the real service seam the controller
 * calls — rather than HTTP, so the per-IP {@code salonInviteBuckets} throttle (15/min, not
 * property-overridable in the test profile) cannot make this class flaky. The history read is real
 * HTTP, because the wire ORDER is part of what is being asserted.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Invite re-dispatch — the lapsed row is SUPERSEDED and kept, never deleted")
class InviteRecycleSupersedesIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(InviteRecycleSupersedesIntegrationTest.class);

    private static final String HISTORY_URL = "/api/v1/salons/%s/invites";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SalonService salonService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @Test
    @DisplayName("re-inviting an address whose invite lapsed keeps BOTH rows — the older marked "
            + "SUPERSEDED with is_used still false, the newer active — and the history endpoint "
            + "returns both, newest first")
    void should_supersedeTheLapsedRow_when_theSameAddressIsReInvited() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-recycle-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Recycle Salon");
        String inviteeEmail = "recycle-invitee-" + System.nanoTime() + "@beautica.test";

        salonService.inviteMaster(ownerId, salonId, inviteeEmail, Role.SALON_MASTER);
        UUID firstInviteId = onlyInviteIdFor(salonId, inviteeEmail);
        // Lapse the first invite: an invite sent a week ago that nobody opened. Backdating
        // created_at as well keeps the newest-first assertion below deterministic instead of
        // resting on two same-request timestamps differing by microseconds.
        lapse(firstInviteId, 7);

        // Act — the owner tries again, exactly as they would from the app
        log.debug("Act: re-invite the same address a week after its first invite lapsed");
        salonService.inviteMaster(ownerId, salonId, inviteeEmail, Role.SALON_MASTER);

        // Assert — the DB keeps both rows with distinct, coherent revocation state
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, is_used, revoked_at, revoked_reason FROM invite_tokens "
                        + "WHERE salon_id = ? AND lower(email) = lower(?) ORDER BY created_at DESC",
                salonId, inviteeEmail);
        assertThat(rows)
                .as("the lapsed invite must SURVIVE the re-invite — hard-deleting it (the pre-change "
                        + "behaviour) erases the owner's record that they already tried this address")
                .hasSize(2);

        Map<String, Object> newer = rows.get(0);
        Map<String, Object> older = rows.get(1);
        assertThat(older.get("id")).isEqualTo(firstInviteId);

        assertThat(older.get("revoked_reason"))
                .as("the displaced row must be marked SUPERSEDED so the history endpoint can class "
                        + "it EXPIRED rather than leaving it looking live")
                .isEqualTo("SUPERSEDED");
        assertThat(older.get("revoked_at"))
                .as("ck_invite_tokens_revoked_pair requires revoked_at and revoked_reason to be set "
                        + "together")
                .isNotNull();
        assertThat(older.get("is_used"))
                .as("a superseded invite was never accepted — flipping is_used would make it read "
                        + "ACCEPTED in the history and would silently change the accept-path guards")
                .isEqualTo(false);

        assertThat(newer.get("revoked_at"))
                .as("the replacement invite is live and must hold the ux_invite_tokens_active slot")
                .isNull();
        assertThat(newer.get("is_used")).isEqualTo(false);

        // Assert — and both are visible to the owner, newest first
        String ownerToken = loginAndGetToken(emailOf(ownerId));
        ResponseEntity<String> response = restTemplate.exchange(
                String.format(HISTORY_URL, salonId), HttpMethod.GET,
                new HttpEntity<>(bearerHeaders(ownerToken)), String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(parseInvites(response.getBody()))
                .extracting(SalonInviteResponse::inviteId, SalonInviteResponse::status)
                .as("the history must show the retry above the lapsed original — body: %s",
                        response.getBody())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple((UUID) newer.get("id"), "PENDING"),
                        org.assertj.core.groups.Tuple.tuple(firstInviteId, "EXPIRED"));
    }

    @Test
    @DisplayName("a THIRD invite to the same address does not raise IncorrectResultSizeDataAccessException "
            + "(the dispatch finder must be narrowed by revoked_at IS NULL, or two kept rows collide)")
    void should_notThrowIncorrectResultSize_when_theAddressIsInvitedAThirdTime() {
        // Arrange — two retired rows for one (salon, email), which is only reachable at all
        // because supersede keeps them. This is the exact state B3 exists to survive.
        UUID ownerId = insertUser("owner-third-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Third Invite Salon");
        String inviteeEmail = "third-invitee-" + System.nanoTime() + "@beautica.test";

        salonService.inviteMaster(ownerId, salonId, inviteeEmail, Role.SALON_MASTER);
        lapse(onlyInviteIdFor(salonId, inviteeEmail), 14);

        salonService.inviteMaster(ownerId, salonId, inviteeEmail, Role.SALON_MASTER);
        lapse(activeInviteIdFor(salonId, inviteeEmail), 7);

        // Act + Assert — the third dispatch now sees TWO is_used = false rows for this pair
        log.debug("Act: invite the same address a third time, with two superseded rows already kept");
        assertThatCode(() -> salonService.inviteMaster(ownerId, salonId, inviteeEmail, Role.SALON_MASTER))
                .as("an Optional-returning finder that still matched superseded rows would raise "
                        + "IncorrectResultSizeDataAccessException here — a 500 on every second "
                        + "re-invite of any address, for every salon")
                .doesNotThrowAnyException();

        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM invite_tokens WHERE salon_id = ? AND lower(email) = lower(?)",
                        Integer.class, salonId, inviteeEmail))
                .as("all three dispatches must be preserved as history")
                .isEqualTo(3);
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM invite_tokens WHERE salon_id = ? AND lower(email) = lower(?) "
                                + "AND is_used = false AND revoked_at IS NULL",
                        Integer.class, salonId, inviteeEmail))
                .as("ux_invite_tokens_active's predicate must still match exactly one row — that is "
                        + "what makes the narrowed Optional finder single-valued by construction")
                .isEqualTo(1);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Backdates an invite so it reads as one dispatched {@code daysAgo} days ago and long lapsed. */
    private void lapse(UUID inviteId, int daysAgo) {
        Instant sentAt = Instant.now().minus(daysAgo, ChronoUnit.DAYS);
        jdbcTemplate.update(
                "UPDATE invite_tokens SET created_at = ?, expires_at = ? WHERE id = ?",
                Timestamp.from(sentAt), Timestamp.from(sentAt.plus(2, ChronoUnit.DAYS)), inviteId);
    }

    private UUID onlyInviteIdFor(UUID salonId, String email) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM invite_tokens WHERE salon_id = ? AND lower(email) = lower(?)",
                UUID.class, salonId, email);
    }

    private UUID activeInviteIdFor(UUID salonId, String email) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM invite_tokens WHERE salon_id = ? AND lower(email) = lower(?) "
                        + "AND is_used = false AND revoked_at IS NULL",
                UUID.class, salonId, email);
    }

    /**
     * The payload is a {@link SalonInviteHistoryResponse} object, not a bare array — the list
     * alone could not say whether it had been truncated at the 200-row cap.
     */
    private List<SalonInviteResponse> parseInvites(String body) throws Exception {
        var parsed = objectMapper.readValue(
                body, new TypeReference<ApiResponse<SalonInviteHistoryResponse>>() {});
        assertThat(parsed.success()).as("envelope must report success — body: %s", body).isTrue();
        return parsed.data().invites();
    }

    private UUID insertUser(String email, String role) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, true, true)",
                id, email, hash, role);
        return id;
    }

    private UUID insertSalon(UUID ownerId, String name) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, name, testCityId());
        return salonId;
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {});
        return body.data().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
