package com.beautica.salon;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * SAL-04 audit-fix cycle 3 — concurrent deletes of two salons by the SAME owner must serialise on
 * the owner row, so the owner-master re-home never acts on a stale portfolio.
 *
 * <p><b>The bug this guards.</b> {@code deactivateSalon} loaded the owner with a plain
 * {@code findById}. Two concurrent deletes (A carrying the owner-master, and B) each read a
 * pre-commit snapshot: deleting A re-homed the owner-master onto B while B was being deleted, so the
 * row ended ACTIVE on a deleted salon — not "stranded" by the old predicate — and every later
 * {@code createSalon} answered 409, locking the owner out.
 *
 * <p><b>The fix.</b> {@code deactivateSalon} takes {@link SalonService#lockOwnerForCreate} (the
 * owner {@code SELECT ... FOR UPDATE} that {@code createSalon} already takes) as its first statement.
 *
 * <p><b>Technique.</b> Same one-sided gate as {@link SalonCreateCapConcurrencyIT}: the first delete
 * pauses right after its owner lock is granted; the second delete must be OBSERVED blocked on the
 * {@code users} row lock (falsifiable — the pre-fix code takes no lock and runs straight through);
 * then the first is released and both complete.
 */
@Import(TestSecurityConfig.class)
@DisplayName("DELETE /salons — concurrent deletes by one owner serialise; owner-master stays re-homable (SAL-04)")
class SalonDeleteRehomeConcurrencyIT extends AbstractIntegrationTest {

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private ObjectMapper objectMapper;

    @SpyBean
    private SalonService salonService;

    @Test
    @DisplayName("owner with A (primary, master), B, C deletes A and B concurrently → master active on C; createSalon → /masters/me 200")
    void should_keepOwnerMasterOnActiveSalon_when_twoSalonsDeletedConcurrently() throws Exception {
        UUID ownerId = insertOwner();
        String token = tokenOf(ownerId);
        UUID salonA = createSalon(token, "Race A");
        UUID salonB = createSalon(token, "Race B");
        UUID salonC = createSalon(token, "Race C");

        deleteConcurrently(ownerId, token, salonA, salonB);

        assertThat(masterSalonId(ownerId)).as("owner-master lands on the one surviving salon").isEqualTo(salonC);
        assertOwnerMasterConsistent(ownerId);
        createSalon(token, "Race D");
        assertMastersMeOk(token, ownerId);
    }

    @Test
    @DisplayName("owner with A (primary, master), B deletes A and B concurrently → master inactive & re-homable; createSalon → /masters/me 200")
    void should_leaveOwnerMasterRehomable_when_bothSalonsDeletedConcurrently() throws Exception {
        UUID ownerId = insertOwner();
        String token = tokenOf(ownerId);
        UUID salonA = createSalon(token, "Race Only A");
        UUID salonB = createSalon(token, "Race Only B");

        deleteConcurrently(ownerId, token, salonA, salonB);

        assertThat(masterIsActive(ownerId)).as("no active salon left → owner-master inactive").isFalse();
        assertOwnerMasterConsistent(ownerId);
        UUID salonNew = createSalon(token, "Race New");
        assertMastersMeOk(token, ownerId);
        assertThat(masterSalonId(ownerId)).as("re-homed onto the new salon").isEqualTo(salonNew);
    }

    // ── race driver ────────────────────────────────────────────────────────────

    private void deleteConcurrently(UUID ownerId, String token, UUID first, UUID second) throws Exception {
        // Installed AFTER the API seeding: createSalon takes the same seam.
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
                firstResponse.set(delete(token, first));
            } finally {
                firstDone.countDown();
            }
        });
        assertThat(firstHasLocked.await(10, TimeUnit.SECONDS))
                .as("delete #1 must reach the owner row lock within 10s").isTrue();

        CountDownLatch secondDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> secondResponse = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                secondResponse.set(delete(token, second));
            } finally {
                secondDone.countDown();
            }
        });

        // Released the moment #2 is SEEN waiting (its owner lock is bounded by the 3s lock_timeout).
        boolean secondBlockedOnOwnerLock = LockWaiters.awaitBlockedOnLock(
                jdbcTemplate, "from users", secondDone, Duration.ofSeconds(10));
        assertThat(secondBlockedOnOwnerLock)
                .as("FALSIFIABLE ASSERTION: delete #2 must be observed BLOCKED on the owner row lock while "
                        + "delete #1 holds it uncommitted — otherwise the re-home reads a stale portfolio")
                .isTrue();
        assertThat(secondDone.getCount()).as("delete #2 still in flight while #1 holds the lock").isEqualTo(1L);

        releaseFirst.countDown();
        assertThat(firstDone.await(15, TimeUnit.SECONDS)).as("delete #1 finishes once released").isTrue();
        assertThat(secondDone.await(15, TimeUnit.SECONDS)).as("delete #2 finishes after #1").isTrue();

        assertThat(firstResponse.get().getStatusCode()).as("delete #1 — body: %s", firstResponse.get().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(secondResponse.get().getStatusCode()).as("delete #2 — body: %s", secondResponse.get().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(locksGranted.get()).as("both deletes obtained the owner lock (no lock_timeout)").isEqualTo(2);
    }

    // ── assertions ─────────────────────────────────────────────────────────────

    /** Either active on an ACTIVE salon, or inactive on a deleted salon (re-homable) — never active on a dead salon. */
    private void assertOwnerMasterConsistent(UUID ownerId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT m.is_active AS master_active, s.is_active AS salon_active "
                        + "FROM masters m JOIN salons s ON s.id = m.salon_id WHERE m.user_id = ?", ownerId);
        boolean masterActive = Boolean.TRUE.equals(row.get("master_active"));
        boolean salonActive = Boolean.TRUE.equals(row.get("salon_active"));
        assertThat(masterActive == salonActive)
                .as("owner-master active=%s on salon active=%s — an active row on a deleted salon locks the owner out",
                        masterActive, salonActive)
                .isTrue();
    }

    private void assertMastersMeOk(String token, UUID ownerId) throws Exception {
        ResponseEntity<String> me = restTemplate.exchange("/api/v1/masters/me", HttpMethod.GET,
                new HttpEntity<>(headers(token)), String.class);
        assertThat(me.getStatusCode()).as("GET /masters/me — body: %s", me.getBody()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(me.getBody()).path("data").path("salon").path("id").asText())
                .isEqualTo(masterSalonId(ownerId).toString());
        assertOwnerMasterConsistent(ownerId);
        assertThat(masterIsActive(ownerId)).as("owner-master active after createSalon").isTrue();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

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
        return "delete-race-" + id + "@beautica.test";
    }

    private UUID createSalon(String token, String name) throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "name", name, "cityId", testCityId(), "street", "Shevchenka St", "buildingNo", "7"));
        ResponseEntity<String> response = restTemplate.exchange("/api/v1/salons", HttpMethod.POST,
                new HttpEntity<>(body, headers(token)), String.class);
        assertThat(response.getStatusCode()).as("POST /salons — body: %s", response.getBody())
                .isEqualTo(HttpStatus.CREATED);
        return UUID.fromString(objectMapper.readTree(response.getBody()).path("data").path("id").asText());
    }

    private ResponseEntity<String> delete(String token, UUID salonId) {
        return restTemplate.exchange("/api/v1/salons/" + salonId, HttpMethod.DELETE,
                new HttpEntity<>(headers(token)), String.class);
    }

    private boolean masterIsActive(UUID ownerId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM masters WHERE user_id = ?", Boolean.class, ownerId));
    }

    private UUID masterSalonId(UUID ownerId) {
        return jdbcTemplate.queryForObject("SELECT salon_id FROM masters WHERE user_id = ?", UUID.class, ownerId);
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
