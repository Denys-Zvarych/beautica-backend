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
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * Lock-order inversion between {@code updateSalon} and {@code deactivateSalon} (security LOW).
 *
 * <p><b>The bug this guards.</b> {@code deactivateSalon} locks owner → salon
 * ({@link SalonService#lockOwnerForCreate}, then {@code lockImagePointers}). {@code updateSalon} on a
 * PRIMARY salon with an address change dirtied the salon AND the owner row without any lock; with
 * {@code order_updates: true} its commit flush wrote salons then users — salon → owner. A concurrent
 * same-owner update + delete deadlocked (40P01 → 500).
 *
 * <p><b>Technique.</b> The delete pauses right after its owner lock is granted. The update must be
 * OBSERVED blocked on the {@code users} row (pre-fix: its flush {@code UPDATE users}, having already
 * written the salon row; post-fix: its owner {@code SELECT ... FOR UPDATE}, before any write). Then the
 * delete is released: pre-fix it blocks on the salon row the update holds → deadlock; post-fix it
 * commits and the update, re-checking under the lock, answers 404 without resurrecting the salon.
 *
 * <p>Second case: the owner lock is taken on EVERY update, before the salon is loaded, so a PATCH of
 * salon B racing the delete of primary A reads B only after the delete re-homed {@code is_primary}
 * (and the owner-master) onto it — it can never write back a stale {@code is_primary=false}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("PATCH + DELETE /salons — concurrent primary-salon address update and delete lock owner → salon, no deadlock")
class SalonUpdateDeleteLockOrderConcurrencyIT extends AbstractIntegrationTest {

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
    @DisplayName("delete holds owner lock, primary-salon address PATCH races it → delete 204, PATCH 404, salon stays deleted")
    void should_notDeadlock_when_primarySalonAddressUpdatedWhileSalonDeleted() throws Exception {
        UUID ownerId = insertOwner();
        String token = tokenOf(ownerId);
        UUID salonId = createSalon(token, "Lock Order Primary");

        Race race = deleteRacingUpdate(ownerId, token, salonId,
                () -> patch(token, salonId, "Lock Order Primary", "Franka St", "12"));

        race.assertUpdateWaitedOnOwnerLock();
        assertThat(race.delete().getStatusCode())
                .as("FALSIFIABLE: delete must not be the 40P01 victim — body: %s", race.delete().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(race.update().getStatusCode())
                .as("FALSIFIABLE: the PATCH sees the committed delete (404), never a 40P01 500 — body: %s",
                        race.update().getBody())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(salonIsActive(salonId)).as("the PATCH must not resurrect the deleted salon").isFalse();
    }

    @Test
    @DisplayName("owner A (primary, master) + B: delete A while B's name is PATCHed → B ends primary and holds the owner-master")
    void should_keepRehomedPrimary_when_otherSalonUpdatedWhilePrimaryDeleted() throws Exception {
        UUID ownerId = insertOwner();
        String token = tokenOf(ownerId);
        UUID salonA = createSalon(token, "Rehome A");
        UUID salonB = createSalon(token, "Rehome B");

        Race race = deleteRacingUpdate(ownerId, token, salonA,
                () -> patch(token, salonB, "Rehome B Renamed", "Shevchenka St", "7"));

        race.assertUpdateWaitedOnOwnerLock();
        assertThat(race.delete().getStatusCode()).as("delete A — body: %s", race.delete().getBody())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(race.update().getStatusCode()).as("PATCH B — body: %s", race.update().getBody())
                .isEqualTo(HttpStatus.OK);
        Map<String, Object> b = jdbcTemplate.queryForMap(
                "SELECT name, is_primary, is_active FROM salons WHERE id = ?", salonB);
        assertThat(b.get("is_primary")).as("PATCH must not write back B's stale is_primary=false").isEqualTo(true);
        assertThat(b.get("is_active")).isEqualTo(true);
        assertThat(b.get("name")).isEqualTo("Rehome B Renamed");
        assertThat(jdbcTemplate.queryForObject("SELECT salon_id FROM masters WHERE user_id = ?", UUID.class, ownerId))
                .as("owner-master re-homed onto B").isEqualTo(salonB);
    }

    // ── race driver ────────────────────────────────────────────────────────────

    private record Race(ResponseEntity<String> delete, ResponseEntity<String> update,
                        boolean updateBlockedOnOwner, int locksGranted) {
        void assertUpdateWaitedOnOwnerLock() {
            assertThat(updateBlockedOnOwner)
                    .as("FALSIFIABLE: the PATCH must be observed waiting on the owner row the delete holds")
                    .isTrue();
            assertThat(locksGranted).as("both requests took the owner lock (no lock_timeout)").isEqualTo(2);
        }
    }

    /** DELETE pauses holding the owner lock; the PATCH is started and observed blocked; then both finish. */
    private Race deleteRacingUpdate(UUID ownerId, String token, UUID deleteSalonId,
                                    Supplier<ResponseEntity<String>> update) throws Exception {
        CountDownLatch deleteHasLocked = new CountDownLatch(1);
        CountDownLatch releaseDelete = new CountDownLatch(1);
        AtomicInteger locksGranted = pauseFirstOwnerLock(ownerId, deleteHasLocked, releaseDelete);
        CountDownLatch deleteDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> deleteResponse = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                deleteResponse.set(delete(token, deleteSalonId));
            } finally {
                deleteDone.countDown();
            }
        });
        assertThat(deleteHasLocked.await(10, TimeUnit.SECONDS))
                .as("delete must reach the owner row lock within 10s").isTrue();
        CountDownLatch updateDone = new CountDownLatch(1);
        AtomicReference<ResponseEntity<String>> updateResponse = new AtomicReference<>();
        Thread.ofVirtual().start(() -> {
            try {
                updateResponse.set(update.get());
            } finally {
                updateDone.countDown();
            }
        });
        boolean updateBlocked = LockWaiters.awaitBlockedOnLock(
                jdbcTemplate, "users", updateDone, Duration.ofSeconds(10));
        releaseDelete.countDown();
        assertThat(deleteDone.await(15, TimeUnit.SECONDS)).as("delete finishes").isTrue();
        assertThat(updateDone.await(15, TimeUnit.SECONDS)).as("PATCH finishes").isTrue();
        return new Race(deleteResponse.get(), updateResponse.get(), updateBlocked, locksGranted.get());
    }

    /** Installed AFTER the API seeding (createSalon takes the same seam); pauses only the first grant. */
    private AtomicInteger pauseFirstOwnerLock(UUID ownerId, CountDownLatch hasLocked, CountDownLatch release) {
        AtomicInteger locksGranted = new AtomicInteger();
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (locksGranted.incrementAndGet() == 1) {
                hasLocked.countDown();
                if (!release.await(15, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test never released the delete — setup is broken");
                }
            }
            return result;
        }).when(salonService).lockOwnerForCreate(eq(ownerId));
        return locksGranted;
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
        return "lock-order-" + id + "@beautica.test";
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

    private ResponseEntity<String> patch(String token, UUID salonId, String name, String street, String buildingNo) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "name", name, "cityId", testCityId(), "street", street, "buildingNo", buildingNo));
            return restTemplate.exchange("/api/v1/salons/" + salonId, HttpMethod.PATCH,
                    new HttpEntity<>(body, headers(token)), String.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private ResponseEntity<String> delete(String token, UUID salonId) {
        return restTemplate.exchange("/api/v1/salons/" + salonId, HttpMethod.DELETE,
                new HttpEntity<>(headers(token)), String.class);
    }

    private boolean salonIsActive(UUID salonId) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "SELECT is_active FROM salons WHERE id = ?", Boolean.class, salonId));
    }

    private static HttpHeaders headers(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }
}
