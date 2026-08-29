package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.salon.service.SalonService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * QA audit Phase 23.1 (Security MEDIUM) — deterministic concurrency regression for
 * {@code SalonService#cancelInvite} racing {@code InviteService#acceptInvite} on the SAME invite
 * token.
 *
 * <p><b>The bug this guards.</b> Before the fix, {@code cancelInvite} loaded the token via a plain,
 * unlocked {@code findById} and never re-read it before flushing its {@code markUsed()} mutation.
 * If an {@code acceptInvite} call for the SAME token read-modified-committed (provisioning a real
 * user account) strictly AFTER {@code cancelInvite}'s own unlocked read but BEFORE its commit,
 * {@code cancelInvite}'s in-memory {@code isUsed()} check never saw the change — it would blindly
 * mark the (already-consumed) token used again and return {@code 204} to the admin, who would
 * believe they had prevented the invite from ever being accepted. In reality the account had
 * ALREADY been provisioned by the racing accept.
 *
 * <p><b>The fix.</b> {@code cancelInvite} now loads the token via {@code SalonService
 * #lockInviteForCancel} → {@code InviteTokenRepository#findByIdForUpdate}, a {@code
 * PESSIMISTIC_WRITE} row lock — the SAME lock {@code InviteService#acceptInvite} takes via {@code
 * findByTokenForUpdate}. Whichever side's lock is granted first now serialises the other; the
 * loser's grant (once unblocked) always returns Postgres's freshly-committed row, so a losing
 * {@code acceptInvite} correctly rejects an already-cancelled token, and a losing {@code
 * cancelInvite} would correctly 404 on an already-accepted one (not exercised by this test — see
 * {@code PendingInvitesIntegrationTest#should_return404_when_ownerCancelsPendingInvite_and...}
 * sibling coverage for the reverse ordering via plain sequential calls).
 *
 * <p><b>Test technique.</b> One-sided gate (the same pattern proven in {@code
 * AppointmentCrossPathTransitionConcurrencyIT} / {@code BookingCancelRescheduleConcurrencyIT}):
 * {@code @SpyBean} on {@link SalonService#lockInviteForCancel}, pausing the CANCEL thread
 * immediately AFTER its real (locked, in the fixed code) read but BEFORE {@code cancelInvite}
 * checks {@code isUsed()}/commits. While cancel is paused (holding the row lock in the fixed code),
 * the ACCEPT call is fired on a second thread; only once BOTH have been dispatched is cancel
 * released to finish. This reproduces the exact defect window regardless of which racer's own
 * {@code SELECT ... FOR UPDATE} statement Postgres happens to service first — it is the DB lock
 * itself, not thread scheduling, that determines the outcome once the fix is in place.
 *
 * <p><b>Falsification (required by the QA audit protocol) — and a trap this test's first draft
 * fell into.</b> The obvious version of this test releases {@code cancel} immediately after
 * dispatching the {@code accept} thread and then only checks the FINAL response codes. That
 * version is worthless: reverting {@link SalonService#lockInviteForCancel} to a plain {@code
 * inviteTokenRepository.findById(inviteId)} and rerunning it STILL PASSED, because cancel — once
 * released — has almost no remaining work (the entity is already loaded; it is just a boolean flip
 * and a flush) and reliably commits before accept's much heavier HTTP round trip (validation,
 * hashing, its own DB round trip) even reaches the database — pure thread-scheduling luck, not the
 * lock, produced the "safe" result. The fix below is the assertion actually in this test: BEFORE
 * releasing cancel, assert that accept has NOT completed within a generous 3s window. Only a
 * genuinely held row lock can force that; nothing else in this test blocks accept for any
 * meaningful time. Verified against a throwaway revert of the production line: with {@code
 * findById} (no lock), {@code acceptDone.await(3, SECONDS)} returned {@code true} (accept finished
 * well inside the window) and the {@code isFalse()} assertion failed — RED, confirming the test has
 * teeth. Restored to {@code findByIdForUpdate}, rerun with {@code --rerun-tasks} — GREEN.
 */
@Import(TestSecurityConfig.class)
@DisplayName("cancelInvite vs acceptInvite — concurrency regression (Phase 23.1 Security MEDIUM, fixed)")
class PendingInviteCancelAcceptRaceIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(PendingInviteCancelAcceptRaceIT.class);

    private static final String CANCEL_URL = "/api/v1/salons/%s/invites/%s";
    private static final String ACCEPT_URL = "/api/v1/auth/invite/accept";
    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @SpyBean
    private SalonService salonService;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
    }

    @Test
    @DisplayName("a cancel that reads (and locks) the token FIRST must win the race: accept is "
            + "rejected with 400 and NO account is provisioned, never a 204-cancel alongside a "
            + "successfully-provisioned account")
    void should_rejectAccept_when_cancelAcquiresTheRowLockBeforeAcceptCommits() throws Exception {
        // Arrange
        UUID ownerId = insertUser("owner-race-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        UUID salonId = insertSalon(ownerId, "Cancel-Accept Race Salon");
        String raceEmail = "race-invitee-" + System.nanoTime() + "@beautica.test";
        String rawToken = "raw-race-token-" + UUID.randomUUID();
        UUID inviteId = insertInviteTokenWithRawToken(raceEmail, salonId, "SALON_MASTER",
                Instant.now().plus(7, ChronoUnit.DAYS), rawToken);
        String ownerToken = loginAndGetToken(emailOf(ownerId));

        // One-sided gate: cancel's own lock-acquisition seam performs the REAL (locked) read, then
        // pauses BEFORE returning to cancelInvite's isUsed()/markUsed() logic — i.e. it is paused
        // WHILE HOLDING the row lock in the fixed code. Only once BOTH racers have been dispatched
        // is cancel released to finish its transaction.
        CountDownLatch cancelHasLocked = new CountDownLatch(1);
        CountDownLatch releaseCancel = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            cancelHasLocked.countDown();
            boolean released = releaseCancel.await(15, TimeUnit.SECONDS);
            if (!released) {
                throw new IllegalStateException("test never released the cancel thread — setup is broken");
            }
            return result;
        }).when(salonService).lockInviteForCancel(eq(inviteId));

        CountDownLatch cancelDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> respCancel = new AtomicReference<>();
        log.debug("Act: launch cancel thread — DELETE {} as SALON_OWNER", String.format(CANCEL_URL, salonId, inviteId));
        Thread.ofVirtual().start(() -> {
            try {
                respCancel.set(restTemplate.exchange(
                        String.format(CANCEL_URL, salonId, inviteId), HttpMethod.DELETE,
                        new HttpEntity<>(bearerHeaders(ownerToken)), String.class));
            } finally {
                cancelDone.countDown();
            }
        });

        boolean cancelReachedLock = cancelHasLocked.await(10, TimeUnit.SECONDS);
        assertThat(cancelReachedLock)
                .as("the cancel thread must reach (and, in the fixed code, hold) its row lock within 10s")
                .isTrue();

        // While cancel is paused holding the lock, fire the racing accept on a second thread. In
        // the FIXED code this call blocks on its own SELECT ... FOR UPDATE until cancel commits and
        // releases the lock (verified below by simply awaiting its completion with a generous
        // timeout, rather than asserting anything about ITS internal blocking state directly).
        CountDownLatch acceptDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> respAccept = new AtomicReference<>();
        log.debug("Act: launch accept thread — POST {} for the SAME invite's raw token while cancel holds the lock",
                ACCEPT_URL);
        var acceptRequest = new InviteAcceptRequest(rawToken, TEST_PASSWORD, "Race", "Invitee", "+380501234567");
        Thread.ofVirtual().start(() -> {
            try {
                respAccept.set(restTemplate.postForEntity(ACCEPT_URL, acceptRequest, String.class));
            } finally {
                acceptDone.countDown();
            }
        });

        // THE ASSERTION WITH TEETH. Do NOT release cancel yet. Give accept a generous bounded
        // window to complete on its own — if cancel's lock-seam is a no-op (the pre-fix, unlocked
        // findById), NOTHING blocks accept and it always finishes well inside this window (measured
        // ~1.5s round-trip end-to-end in this suite). If cancel's seam genuinely takes and HOLDS the
        // PESSIMISTIC_WRITE row lock (the fix), accept's own findByTokenForUpdate MUST block on that
        // lock and cannot possibly finish while cancel is still paused — there is no other actor
        // that could unblock it. This is what makes the earlier attempt at this test (releasing
        // cancel immediately, then just checking final response codes) worthless: relative thread
        // scheduling alone made cancel finish before accept even without the lock, so the final
        // assertions passed regardless of whether the fix was present — verified empirically (see
        // this class's own falsification note below). Asserting non-completion under contention,
        // rather than only the eventual outcome, is the only way to prove the LOCK itself — not
        // just favourable scheduling — is what produces the result.
        boolean acceptFinishedWhileCancelStillPaused = acceptDone.await(3, TimeUnit.SECONDS);
        assertThat(acceptFinishedWhileCancelStillPaused)
                .as("FALSIFIABLE ASSERTION: the racing accept must still be BLOCKED on the row lock "
                        + "while cancel holds it uncommitted — if this is true, cancel's read took no "
                        + "lock at all (the pre-fix defect) and accept was free to run/commit "
                        + "unconditionally the whole time, which is exactly the vulnerable window "
                        + "that let an already-cancelled invite still provision an account")
                .isFalse();

        releaseCancel.countDown();
        boolean cancelFinished = cancelDone.await(15, TimeUnit.SECONDS);
        assertThat(cancelFinished).as("the cancel thread must finish within 15s once released").isTrue();
        boolean acceptFinished = acceptDone.await(15, TimeUnit.SECONDS);
        assertThat(acceptFinished).as("the accept thread must finish within 15s of cancel's release").isTrue();

        // Assert — the deterministic, security-relevant outcome
        assertThat(respCancel.get().getStatusCode())
                .as("the cancel, having locked the row first, must always succeed — body: %s",
                        respCancel.get().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(respAccept.get().getStatusCode())
                .as("the racing accept must be rejected once it observes the FRESH (cancelled) row via "
                        + "its own lock grant — never silently succeed after the admin was told the "
                        + "invite was cancelled — body: %s", respAccept.get().getBody())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        var acceptBody = objectMapper.readValue(
                respAccept.get().getBody(), new com.fasterxml.jackson.core.type.TypeReference<ApiResponse<Void>>() {});
        assertThat(acceptBody.success()).isFalse();

        Integer provisionedAccounts = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, raceEmail);
        assertThat(provisionedAccounts)
                .as("THE decisive security assertion: no account must ever be provisioned for an "
                        + "invite the admin was told (204) had been cancelled — this is exactly what the "
                        + "pre-fix code allowed")
                .isZero();
        assertThat(readIsUsed(inviteId))
                .as("the token must be marked used exactly by the winning cancel")
                .isTrue();
    }

    // ── helpers (mirrors PendingInvitesIntegrationTest) ────────────────────────

    private UUID insertUser(String email, String role) {
        String hash = passwordEncoder.encode(TEST_PASSWORD);
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) VALUES (?, ?, ?, ?, true, true)",
                id, email, hash, role);
        return id;
    }

    private UUID insertSalon(UUID ownerId, String name) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, name);
        return salonId;
    }

    private UUID insertInviteTokenWithRawToken(String email, UUID salonId, String role, Instant expiresAt,
            String rawToken) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO invite_tokens (id, token, email, salon_id, role, expires_at, is_used, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, false, NOW(), NOW())",
                id, sha256Hex(rawToken), email, salonId, role, Timestamp.from(expiresAt));
        return id;
    }

    private String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String emailOf(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT email FROM users WHERE id = ?", String.class, userId);
    }

    private Boolean readIsUsed(UUID inviteId) {
        return jdbcTemplate.queryForObject("SELECT is_used FROM invite_tokens WHERE id = ?", Boolean.class, inviteId);
    }

    private String loginAndGetToken(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login",
                new com.beautica.auth.dto.LoginRequest(email, TEST_PASSWORD), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        var body = objectMapper.readValue(resp.getBody(),
                new com.fasterxml.jackson.core.type.TypeReference<ApiResponse<com.beautica.auth.dto.AuthResponse>>() {});
        return body.data().accessToken();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
