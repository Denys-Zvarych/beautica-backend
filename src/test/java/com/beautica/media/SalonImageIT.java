package com.beautica.media;

import com.beautica.config.TestSecurityConfig;
import com.beautica.media.service.R2StorageService;
import com.beautica.salon.entity.SalonImageSlot;
import com.beautica.salon.service.SalonService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.beautica.support.R2DeleteLedger.purgedKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 343 — salon logo/cover upload, replace and delete by the salon's OWNER, against real Postgres with a
 * mocked R2 (the test profile runs {@code blobPurgeExecutor} synchronously, so after-commit purges are
 * observable on return). Authorization denials live in {@link SalonImageSecurityIT}.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Salon logo/cover — owner upload, replace, delete (Phase 343)")
class SalonImageIT extends AbstractMediaIntegrationTest {

    private static final String CDN = "https://cdn.example/";
    private static final long LATCH_SECONDS = 15;

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private SalonService salonService;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockBean private R2StorageService r2;

    @Override protected TestRestTemplate restTemplate() { return restTemplate; }
    @Override protected ObjectMapper objectMapper() { return objectMapper; }
    @Override protected PasswordEncoder passwordEncoder() { return passwordEncoder; }

    @BeforeEach
    void setUpR2() {
        reset(r2);
        when(r2.isEnabled()).thenReturn(true);
        when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> CDN + inv.getArgument(0));
        when(r2.extractKeyFromPublicUrl(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            return url.startsWith(CDN) ? Optional.of(url.substring(CDN.length())) : Optional.empty();
        });
        doNothing().when(r2).uploadFile(anyString(), any(), anyLong(), anyString());
    }

    // ── TC-1 / TC-2 ──────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TC-1: owner POST /media/logo → 200; avatarUrl on the response, GET /salons/{id} and GET "
            + "/salons/mine (both read once BEFORE the upload, so the after-commit eviction is exercised)")
    void should_setLogoEverywhere_when_ownerUploadsLogo() throws Exception {
        Owner o = owner("tc1");
        getData("/api/v1/salons/" + o.salonId(), null);
        getData("/api/v1/salons/mine", o.token());

        ResponseEntity<String> resp = upload(o, "logo");

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        String url = data(resp).path("avatarUrl").asText();
        assertThat(url).startsWith(CDN + "salons/" + o.salonId() + "/logo/").endsWith(".jpg");
        assertThat(columns(o.salonId()).get("avatar_r2_key")).isEqualTo(url.substring(CDN.length()));
        assertThat(getData("/api/v1/salons/" + o.salonId(), null).path("avatarUrl").asText()).isEqualTo(url);
        assertThat(getData("/api/v1/salons/mine", o.token()).get(0).path("avatarUrl").asText()).isEqualTo(url);
    }

    @Test
    @DisplayName("TC-2: owner POST /media/cover → 200; coverImageUrl on SalonResponse AND PublicSalonResponse")
    void should_setCoverOnBothResponses_when_ownerUploadsCover() throws Exception {
        Owner o = owner("tc2");
        getData("/api/v1/salons/" + o.salonId(), null);

        ResponseEntity<String> resp = upload(o, "cover");

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        String url = data(resp).path("coverImageUrl").asText();
        assertThat(url).startsWith(CDN + "salons/" + o.salonId() + "/cover/");
        assertThat(data(resp).path("avatarUrl").isNull()).as("logo untouched by a cover upload").isTrue();
        assertThat(getData("/api/v1/salons/" + o.salonId(), null).path("coverImageUrl").asText()).isEqualTo(url);
        assertThat(columns(o.salonId()).get("cover_r2_key")).isEqualTo(url.substring(CDN.length()));
    }

    @Test
    @DisplayName("D9: an affiliated master's cached GET /masters/{id} shows the new salon logo after upload")
    void should_refreshMasterDetailSalonLogo_when_ownerUploadsLogo() throws Exception {
        Owner o = owner("d9");
        UUID masterId = insertSalonMaster(o.salonId());
        getData("/api/v1/masters/" + masterId, null);

        String url = data(upload(o, "logo")).path("avatarUrl").asText();

        assertThat(getData("/api/v1/masters/" + masterId, null).path("salon").path("avatarUrl").asText())
                .isEqualTo(url);
    }

    // ── TC-3 / D6 ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TC-3: replace purges the FIRST key after commit and the row holds the second key")
    void should_purgeFirstKey_when_logoReplaced() throws Exception {
        Owner o = owner("tc3");
        String firstKey = data(upload(o, "logo")).path("avatarUrl").asText().substring(CDN.length());
        reset(r2);
        setUpR2();

        String secondUrl = data(upload(o, "logo")).path("avatarUrl").asText();

        assertThat(purgedKeys(r2)).containsExactly(firstKey);
        assertThat(columns(o.salonId()).get("avatar_r2_key")).isEqualTo(secondUrl.substring(CDN.length()));
    }

    @Test
    @DisplayName("D6: replacing a legacy cover (url, no key) purges the URL-derived key under the own slot prefix")
    void should_purgeUrlDerivedKey_when_legacyCoverReplaced() throws Exception {
        Owner o = owner("d6-legacy");
        String legacyKey = "salons/" + o.salonId() + "/cover/legacy.jpg";
        jdbcTemplate.update("UPDATE salons SET cover_image_url = ? WHERE id = ?", CDN + legacyKey, o.salonId());

        assertThat(upload(o, "cover").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(purgedKeys(r2)).containsExactly(legacyKey);
    }

    @Test
    @DisplayName("D6: a legacy logo URL under ANOTHER salon's prefix is not purged on replace")
    void should_notPurge_when_legacyLogoUrlIsUnderForeignSalon() throws Exception {
        Owner o = owner("d6-foreign");
        jdbcTemplate.update("UPDATE salons SET avatar_url = ? WHERE id = ?",
                CDN + "salons/" + UUID.randomUUID() + "/logo/x.jpg", o.salonId());

        assertThat(upload(o, "logo").getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(purgedKeys(r2)).isEmpty();
    }

    // ── D7 delete ────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("D7: DELETE /media/cover clears url + key, purges the blob after commit, 204")
    void should_clearPointersAndPurge_when_ownerDeletesCover() throws Exception {
        Owner o = owner("d7");
        String key = data(upload(o, "cover")).path("coverImageUrl").asText().substring(CDN.length());
        getData("/api/v1/salons/" + o.salonId(), null);

        ResponseEntity<Void> resp = delete(o, "cover");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(purgedKeys(r2)).containsExactly(key);
        Map<String, Object> cols = columns(o.salonId());
        assertThat(cols.get("cover_image_url")).isNull();
        assertThat(cols.get("cover_r2_key")).isNull();
        assertThat(getData("/api/v1/salons/" + o.salonId(), null).path("coverImageUrl").isNull()).isTrue();
    }

    @Test
    @DisplayName("D7: DELETE on an empty slot is an idempotent 204 with no R2 call")
    void should_return204WithoutR2_when_slotAlreadyEmpty() throws Exception {
        Owner o = owner("d7-empty");

        ResponseEntity<Void> resp = delete(o, "logo");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(purgedKeys(r2)).isEmpty();
        verify(r2, never()).deleteFile(anyString());
    }

    // ── TC-6 ─────────────────────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("TC-6: unknown slot /media/banner → 400 and no R2 upload")
    void should_return400_when_slotUnknown() throws Exception {
        Owner o = owner("tc6-slot");

        ResponseEntity<String> resp = upload(o, "banner");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).doesNotContain("LOGO").doesNotContain("COVER");
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("TC-6: the owner of a DEACTIVATED salon gets 404 on POST and DELETE, with no R2 upload")
    void should_return404_when_salonInactive() throws Exception {
        Owner o = owner("tc6-inactive");
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", o.salonId());

        assertThat(upload(o, "logo").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(delete(o, "cover").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("a non-image file → 400 before any R2 call")
    void should_return400_when_fileIsNotAnImage() throws Exception {
        Owner o = owner("bad-file");
        var body = new org.springframework.util.LinkedMultiValueMap<String, Object>();
        body.add("file", new org.springframework.core.io.ByteArrayResource("not an image".getBytes()) {
            @Override public String getFilename() { return "x.txt"; }
        });

        ResponseEntity<String> resp = restTemplate.exchange(url(o.salonId(), "logo"), HttpMethod.POST,
                new HttpEntity<>(body, bearerMultipartHeaders(o.token())), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
    }

    // ── row lock: strength + lock_timeout (perf audit cycle 1) ─────────────────────────────────

    @Test
    @DisplayName("perf: while an upload's locked write holds the salon row lock, an appointment INSERT that "
            + "FK-references the salon does NOT wait (FOR NO KEY UPDATE never blocks FOR KEY SHARE)")
    void should_notBlockAppointmentInsert_when_uploadHoldsSalonLock() throws Exception {
        Owner o = owner("lock-fk");
        UUID clientId = insertClient("p343-lock-client-" + System.nanoTime() + "@beautica.test");
        UUID appointmentId = UUID.randomUUID();

        Throwable failure;
        try (SalonLockHolder ignored = holdUploadLock(o)) {
            failure = catchThrowable(() -> tx().executeWithoutResult(status -> {
                // Bounded probe: a blocked FK check fails at 1s (55P03) instead of hanging the suite.
                jdbcTemplate.queryForObject("SELECT set_config('lock_timeout', '1s', true)", String.class);
                jdbcTemplate.update("INSERT INTO appointments (id, client_id, salon_id, status, booking_source, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'CONFIRMED', 'APP', NOW(), NOW())",
                        appointmentId, clientId, o.salonId());
            }));
        }

        assertThat(failure).as("FK insert must not wait on the upload's salon lock").isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM appointments WHERE id = ?", Integer.class,
                appointmentId)).isEqualTo(1);
    }

    @Test
    @DisplayName("perf: an upload that cannot get the salon row lock within the 3s lock_timeout → 409 "
            + "(CannotAcquireLock convention), its new blob discarded, the holder's pointer intact")
    void should_return409AndDiscardBlob_when_salonLockHeldPastLockTimeout() throws Exception {
        Owner o = owner("lock-timeout");
        String holderKey = "salons/" + o.salonId() + "/logo/holder.jpg";

        ResponseEntity<String> resp;
        try (SalonLockHolder ignored = holdUploadLock(o, holderKey)) {
            resp = upload(o, "logo");
        }

        assertThat(resp.getStatusCode()).as("body=%s", resp.getBody()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(purgedKeys(r2)).singleElement().asString()
                .startsWith("salons/" + o.salonId() + "/logo/").isNotEqualTo(holderKey);
        assertThat(columns(o.salonId()).get("avatar_r2_key")).isEqualTo(holderKey);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────────────

    /**
     * Runs the real locked write step of an upload ({@code SalonService#replaceSalonImageLocked}) on another
     * thread and parks its transaction — row lock held — until {@link #close()}, which lets it commit.
     */
    private final class SalonLockHolder implements AutoCloseable {
        private final ExecutorService executor = Executors.newSingleThreadExecutor();
        private final CountDownLatch held = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final Future<?> holder;

        SalonLockHolder(Owner o, String key) throws InterruptedException {
            holder = executor.submit(() -> tx().executeWithoutResult(status -> {
                salonService.replaceSalonImageLocked(o.ownerId(), o.salonId(), SalonImageSlot.LOGO, CDN + key, key);
                held.countDown();
                awaitRelease();
            }));
            assertThat(held.await(LATCH_SECONDS, TimeUnit.SECONDS)).as("holder took the salon lock").isTrue();
        }

        private void awaitRelease() {
            try {
                release.await(LATCH_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() throws Exception {
            release.countDown();
            try {
                holder.get(LATCH_SECONDS, TimeUnit.SECONDS);
            } finally {
                executor.shutdownNow();
            }
        }
    }

    private SalonLockHolder holdUploadLock(Owner o) throws InterruptedException {
        return holdUploadLock(o, "salons/" + o.salonId() + "/logo/holder.jpg");
    }

    private SalonLockHolder holdUploadLock(Owner o, String key) throws InterruptedException {
        return new SalonLockHolder(o, key);
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    private record Owner(UUID ownerId, UUID salonId, String token) {}

    private Owner owner(String tag) throws Exception {
        String email = "p343-" + tag + "-" + System.nanoTime() + "@beautica.test";
        UUID ownerId = insertSalonOwner(email);
        UUID salonId = insertSalon(ownerId, "P343 " + tag);
        return new Owner(ownerId, salonId, loginAndGetToken(email));
    }

    private UUID insertSalonMaster(UUID salonId) {
        UUID userId = insertUser("p343-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', 0, 0, true, NOW(), NOW())",
                masterId, userId, salonId);
        return masterId;
    }

    private static String url(UUID salonId, String slot) {
        return "/api/v1/salons/" + salonId + "/media/" + slot;
    }

    private ResponseEntity<String> upload(Owner o, String slot) {
        return restTemplate.exchange(url(o.salonId(), slot), HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(o.token())), String.class);
    }

    private ResponseEntity<Void> delete(Owner o, String slot) {
        return restTemplate.exchange(url(o.salonId(), slot), HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(o.token())), Void.class);
    }

    private JsonNode data(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readTree(resp.getBody()).path("data");
    }

    private JsonNode getData(String path, String token) throws Exception {
        HttpHeaders headers = token == null ? new HttpHeaders() : authHeaders(token);
        ResponseEntity<String> resp = restTemplate.exchange(path, HttpMethod.GET, new HttpEntity<>(headers),
                String.class);
        assertThat(resp.getStatusCode()).as("GET %s body=%s", path, resp.getBody()).isEqualTo(HttpStatus.OK);
        return data(resp);
    }

    private Map<String, Object> columns(UUID salonId) {
        return jdbcTemplate.queryForMap(
                "SELECT avatar_url, avatar_r2_key, cover_image_url, cover_r2_key FROM salons WHERE id = ?", salonId);
    }
}
