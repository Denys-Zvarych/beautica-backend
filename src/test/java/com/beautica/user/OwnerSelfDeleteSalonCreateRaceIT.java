package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.JwtTokenProvider;
import com.beautica.auth.Role;
import com.beautica.config.TestSecurityConfig;
import com.beautica.salon.service.SalonService;
import com.beautica.support.LockWaiters;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * Phase 488 Scope 4 / B490 lock order — a {@code POST /salons} racing the owner's {@code DELETE /users/me} must
 * serialise on the owner row ({@link SalonService#lockOwnerForCreate}, taken FIRST by both), so exactly one
 * wins and no ACTIVE salon is ever left with {@code owner_id NULL}.
 *
 * <p><b>Technique</b> (same one-sided gate as {@code SalonDeleteRehomeConcurrencyIT}): the first request pauses
 * right after its owner lock is granted; the second must be OBSERVED blocked on the {@code users} row lock —
 * falsifiable: without the lock the delete runs straight through its precondition and the observation fails —
 * then the first is released and both complete.
 */
@Import(TestSecurityConfig.class)
@DisplayName("POST /salons vs DELETE /users/me — serialised on the owner row (Phase 488)")
class OwnerSelfDeleteSalonCreateRaceIT extends AbstractIntegrationTest {

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private ObjectMapper objectMapper;

    @SpyBean private SalonService salonService;

    @Test
    @DisplayName("delete locks first → create waits, then 404 (owner gone); no salon exists, none ownerless+active")
    void should_refuseCreate_when_deleteHoldsOwnerLockFirst() throws Exception {
        UUID ownerId = insertOwner();
        String token = tokenOf(ownerId);

        Outcome outcome = race(ownerId, () -> deleteMe(token), () -> createSalon(token));

        assertThat(outcome.first().getStatusCode()).as("delete — body: %s", outcome.first().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(outcome.second().getStatusCode()).as("create — body: %s", outcome.second().getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(count("SELECT COUNT(*) FROM users WHERE id = ?", ownerId)).isZero();
        assertNoOwnerlessActiveSalon();
    }

    @Test
    @DisplayName("create locks first → delete waits, then 409 OWNER_HAS_ACTIVE_SALONS; salon stays owned and active")
    void should_refuseDelete_when_createHoldsOwnerLockFirst() throws Exception {
        UUID ownerId = insertOwner();
        String token = tokenOf(ownerId);

        Outcome outcome = race(ownerId, () -> createSalon(token), () -> deleteMe(token));

        assertThat(outcome.first().getStatusCode()).as("create — body: %s", outcome.first().getBody())
                .isEqualTo(HttpStatus.CREATED);
        assertThat(outcome.second().getStatusCode()).as("delete — body: %s", outcome.second().getBody())
                .isEqualTo(HttpStatus.CONFLICT);
        assertThat(objectMapper.readTree(outcome.second().getBody()).path("data").path("code").asText())
                .isEqualTo("OWNER_HAS_ACTIVE_SALONS");
        assertThat(count("SELECT COUNT(*) FROM salons WHERE owner_id = ? AND is_active", ownerId)).isEqualTo(1);
        assertNoOwnerlessActiveSalon();
    }

    // ── race driver ────────────────────────────────────────────────────────────

    private record Outcome(ResponseEntity<String> first, ResponseEntity<String> second) {}

    private Outcome race(UUID ownerId, Supplier<ResponseEntity<String>> first,
                         Supplier<ResponseEntity<String>> second) throws Exception {
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
        Thread.ofVirtual().start(() -> {
            try {
                firstResponse.set(first.get());
            } finally {
                firstDone.countDown();
            }
        });
        assertThat(firstHasLocked.await(10, TimeUnit.SECONDS)).as("request #1 reaches the owner lock").isTrue();

        CountDownLatch secondDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> secondResponse = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                secondResponse.set(second.get());
            } finally {
                secondDone.countDown();
            }
        });

        boolean secondBlocked = LockWaiters.awaitBlockedOnLock(
                jdbcTemplate, "from users", secondDone, Duration.ofSeconds(10));
        assertThat(secondBlocked)
                .as("FALSIFIABLE ASSERTION: request #2 must be observed BLOCKED on the owner row lock while #1 "
                        + "holds it uncommitted")
                .isTrue();
        assertThat(secondDone.getCount()).as("request #2 still in flight while #1 holds the lock").isEqualTo(1L);

        releaseFirst.countDown();
        assertThat(firstDone.await(15, TimeUnit.SECONDS)).as("request #1 finishes once released").isTrue();
        assertThat(secondDone.await(15, TimeUnit.SECONDS)).as("request #2 finishes after #1").isTrue();
        return new Outcome(firstResponse.get(), secondResponse.get());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void assertNoOwnerlessActiveSalon() {
        assertThat(count("SELECT COUNT(*) FROM salons WHERE owner_id IS NULL AND is_active", null))
                .as("no ACTIVE salon may ever be ownerless").isZero();
    }

    private long count(String sql, UUID arg) {
        return arg == null
                ? jdbcTemplate.queryForObject(sql, Long.class)
                : jdbcTemplate.queryForObject(sql, Long.class, arg);
    }

    private UUID insertOwner() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', 'Fn', 'Ln', true, true)",
                id, emailOf(id), PASSWORD_HASH);
        return id;
    }

    private String tokenOf(UUID ownerId) {
        return jwtTokenProvider.generateAccessToken(ownerId, emailOf(ownerId), Role.SALON_OWNER);
    }

    private static String emailOf(UUID id) {
        return "owner-race-" + id + "@beautica.test";
    }

    private ResponseEntity<String> createSalon(String token) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "name", "Race Salon", "cityId", testCityId(), "street", "Shevchenka St", "buildingNo", "7"));
            return restTemplate.exchange("/api/v1/salons", HttpMethod.POST,
                    new HttpEntity<>(body, headers(token)), String.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<String> deleteMe(String token) {
        return restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(headers(token)), String.class);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
