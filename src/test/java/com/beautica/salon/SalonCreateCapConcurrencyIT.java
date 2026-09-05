package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.salon.dto.CreateSalonRequest;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * Perf LOW-A — concurrency regression for the per-owner active-salon cap
 * ({@link SalonService#MAX_ACTIVE_SALONS_PER_OWNER}) on {@code POST /api/v1/salons}.
 *
 * <p><b>The bug this guards.</b> {@code createSalon} counts the owner's active salons and then
 * inserts one. Before the fix the owner row was loaded with a plain, unlocked {@code findById}, so
 * the count and the insert were not serialised: an owner firing N concurrent creates had every
 * request read {@code count < 50} and every one insert, landing the portfolio arbitrarily far above
 * the ceiling. Nothing in the schema backs the cap — "at most N rows per {@code owner_id}" is not
 * expressible as a Postgres {@code CHECK}/{@code UNIQUE}/{@code EXCLUDE} constraint — so the check
 * itself was the only bound, and it was a TOCTOU. The overshoot is not cosmetic: it voids the row
 * bound that {@code SalonRepository#findActiveSiblingsBySalonId}, {@code GET /salons/mine} and each
 * {@code ownerSalons} cache entry are all sized by.
 *
 * <p><b>The fix.</b> {@code createSalon} now loads the owner through
 * {@link SalonService#lockOwnerForCreate} → {@code UserRepository#findByIdForUpdate}, a
 * {@code PESSIMISTIC_WRITE} ({@code SELECT ... FOR UPDATE}) row lock on the owner's {@code users}
 * row, held for the whole transaction. Concurrent creates for the SAME owner serialise on it, so
 * the loser's count is taken after the winner's insert has committed and correctly sees the cap.
 * This is the same lock-the-row shape the codebase already uses for a check-then-write TOCTOU in
 * {@code PasswordResetService} and {@link SalonService#lockInviteForCancel} — it needs no migration
 * and adds no statement (the owner row had to be read regardless).
 *
 * <p><b>Test technique.</b> One-sided gate, the pattern proven by
 * {@link PendingInviteCancelAcceptRaceIT}: {@code @SpyBean} on {@link SalonService#lockOwnerForCreate}
 * pauses the FIRST create immediately after its real (locked, in the fixed code) read, before its
 * transaction commits; a second create is then fired on another thread. The decisive assertion runs
 * BEFORE the first thread is released — the second create must NOT have completed inside a generous
 * 3s window. Only a genuinely held row lock can block it; nothing else in this test would. Merely
 * asserting the final status codes would be worthless, because with 49 salons pre-seeded the second
 * request could just as easily lose on scheduling. See that class's Javadoc for the trap in full.
 *
 * <p>The owner is seeded with {@code MAX_ACTIVE_SALONS_PER_OWNER - 1} active salons, so exactly one
 * of the two racers may legally succeed: the outcome is 201 + 409 and a final headcount of exactly
 * the cap. Under the pre-fix code both commit and the headcount is cap + 1.
 */
@Import(TestSecurityConfig.class)
@DisplayName("POST /salons — active-salon cap holds under concurrency (Perf LOW-A)")
class SalonCreateCapConcurrencyIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SalonCreateCapConcurrencyIT.class);

    private static final String SALONS_URL = "/api/v1/salons";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @SpyBean
    private SalonService salonService;

    private SalonItFixtures fixtures;

    @BeforeEach
    void configureHttpClient() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new SalonItFixtures(
                restTemplate, jdbcTemplate, objectMapper, passwordEncoder, this::testCityId);
    }

    @Test
    @DisplayName("two creates issued concurrently at cap - 1 yield exactly one 201 and one 409, "
            + "never two salons past the ceiling")
    void should_rejectBeyondCap_when_createsIssuedConcurrently() throws Exception {
        // Arrange — the owner sits exactly one salon below the cap, so precisely one of the two
        // racing creates may legally win. Seeded via raw SQL so the seeding itself does not go
        // through the code under test.
        UUID ownerId = fixtures.insertUser(
                "owner-cap-race-" + System.nanoTime() + "@beautica.test", "SALON_OWNER");
        for (int i = 0; i < SalonService.MAX_ACTIVE_SALONS_PER_OWNER - 1; i++) {
            fixtures.insertSalon(ownerId, "Cap Race Salon " + i);
        }
        assertThat(activeSalonCount(ownerId))
                .as("premise — the owner must start exactly one salon below the cap, or the race "
                        + "cannot distinguish a held lock from an unheld one")
                .isEqualTo(SalonService.MAX_ACTIVE_SALONS_PER_OWNER - 1);
        String ownerToken = fixtures.loginAndGetToken(fixtures.emailOf(ownerId));

        // One-sided gate: the FIRST create's lock seam performs the real (locked) read and then
        // pauses, still holding the owner row lock and still uncommitted. The SECOND create's own
        // seam is left untouched — it will block inside callRealMethod() on the DB lock itself,
        // which is precisely what this test measures.
        CountDownLatch firstHasLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger locksGranted = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (locksGranted.incrementAndGet() == 1) {
                firstHasLocked.countDown();
                if (!releaseFirst.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test never released the first thread — setup is broken");
                }
            }
            return result;
        }).when(salonService).lockOwnerForCreate(eq(ownerId));

        CountDownLatch firstDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> firstResponse = new AtomicReference<>();
        log.debug("Act: launch create #1 — POST {} as SALON_OWNER at cap - 1", SALONS_URL);
        Thread.ofVirtual().start(() -> {
            try {
                firstResponse.set(post(ownerToken, "Cap Race Winner"));
            } finally {
                firstDone.countDown();
            }
        });

        boolean firstReachedLock = firstHasLocked.await(10, TimeUnit.SECONDS);
        assertThat(firstReachedLock)
                .as("create #1 must reach (and, in the fixed code, hold) the owner row lock within 10s")
                .isTrue();

        CountDownLatch secondDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> secondResponse = new AtomicReference<>();
        log.debug("Act: launch create #2 — POST {} while create #1 holds the owner row lock", SALONS_URL);
        Thread.ofVirtual().start(() -> {
            try {
                secondResponse.set(post(ownerToken, "Cap Race Loser"));
            } finally {
                secondDone.countDown();
            }
        });

        // THE ASSERTION WITH TEETH. Do NOT release create #1 yet. If the owner load takes no lock
        // (the pre-fix plain findById), nothing blocks create #2: it reads count = 49, passes the
        // cap check and commits its own row entirely inside this window, and the two requests
        // together push the owner to 51 active salons. If the lock is genuinely taken and held,
        // create #2 cannot get past its own SELECT ... FOR UPDATE while #1 sits paused — no other
        // actor exists in this test that could release it.
        boolean secondFinishedWhileFirstPaused = secondDone.await(3, TimeUnit.SECONDS);
        assertThat(secondFinishedWhileFirstPaused)
                .as("FALSIFIABLE ASSERTION: the racing create must still be BLOCKED on the owner row "
                        + "lock while create #1 holds it uncommitted — if this is true the cap check "
                        + "is the unserialised read-then-write it used to be, and N concurrent "
                        + "creates can each read `count < %s` and each insert",
                        SalonService.MAX_ACTIVE_SALONS_PER_OWNER)
                .isFalse();

        releaseFirst.countDown();
        assertThat(firstDone.await(15, TimeUnit.SECONDS))
                .as("create #1 must finish within 15s once released").isTrue();
        assertThat(secondDone.await(15, TimeUnit.SECONDS))
                .as("create #2 must finish within 15s of create #1's release").isTrue();

        // Assert — exactly one winner, and the ceiling actually held.
        List<HttpStatus> statuses = List.of(
                (HttpStatus) firstResponse.get().getStatusCode(),
                (HttpStatus) secondResponse.get().getStatusCode());
        assertThat(statuses)
                .as("one create must be accepted and the other rejected with the cap conflict — "
                        + "bodies: %s | %s", firstResponse.get().getBody(), secondResponse.get().getBody())
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);

        assertThat(activeSalonCount(ownerId))
                .as("THE decisive assertion: the owner must end at exactly the cap, never above it — "
                        + "the pre-fix code committed BOTH rows and landed on %s",
                        SalonService.MAX_ACTIVE_SALONS_PER_OWNER + 1)
                .isEqualTo(SalonService.MAX_ACTIVE_SALONS_PER_OWNER);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private ResponseEntity<String> post(String token, String name) {
        var request = new CreateSalonRequest(
                name, null, "Kyiv", null, null, null, null,
                testCityId(), null, "вул. Тестова", "1", null);
        return restTemplate.exchange(
                SALONS_URL, HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
    }

    private int activeSalonCount(UUID ownerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM salons WHERE owner_id = ? AND is_active = true",
                Integer.class, ownerId);
        return count == null ? 0 : count;
    }
}
