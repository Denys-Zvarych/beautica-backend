package com.beautica.service;

import com.beautica.service.service.ServicePhotoBlobPurger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 342 — service photo upload/replace/delete over the real stack (Testcontainers Postgres,
 * mocked {@link R2StorageService}): authorization matrix, validation, cache eviction, and the R2 blob
 * sweep on service delete, salon delete and independent-master self-delete.
 */
@DisplayName("Service photo — multipart upload / delete / cleanup (Phase 342)")
class ServicePhotoIT extends AbstractServicePhotoIT {

    private static final byte[] GIF = {'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0};

    /**
     * Spy (a concrete {@code @Component}, so real methods stay callable) used to force a deterministic
     * failure INSIDE the locked write transaction. Reset automatically after every test.
     */
    @MockitoSpyBean private ServicePhotoBlobPurger servicePhotoBlobPurger;

    private static String email(String tag) {
        return email("sphoto", tag);
    }

    private record SalonFixture(String ownerToken, UUID salonId, UUID serviceDefId) {}

    private SalonFixture salonWithService() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(email("owner"));
        UUID salonId = fixtures.createSalon(ownerToken, "Photo Salon " + System.nanoTime());
        UUID serviceDefId = fixtures.createServiceDefinition(ownerToken, salonId, "Педикюр");
        return new SalonFixture(ownerToken, salonId, serviceDefId);
    }

    // ── happy path + replace + delete ────────────────────────────────────────────

    @Test
    @DisplayName("POST — owner uploads a JPEG: 200, key+url persisted under services/<id>/")
    void should_persistKeyAndUrl_when_ownerUploadsJpeg() throws Exception {
        SalonFixture f = salonWithService();

        ResponseEntity<String> resp = upload(f.serviceDefId(), f.ownerToken(), JPEG);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String key = photoKey(f.serviceDefId());
        assertThat(key).startsWith("services/" + f.serviceDefId() + "/").endsWith(".jpg");
        assertThat(photoUrl(f.serviceDefId())).isEqualTo("https://cdn.example/" + key);
        assertThat(objectMapper.readTree(resp.getBody()).path("data").path("photoUrl").asText())
                .isEqualTo("https://cdn.example/" + key);
        assertThat(deletedKeys()).isEmpty();
    }

    @Test
    @DisplayName("POST twice — only the first blob is deleted (by key) and only after the new row committed")
    void should_deleteOldBlob_when_photoReplaced() throws Exception {
        SalonFixture f = salonWithService();
        upload(f.serviceDefId(), f.ownerToken(), JPEG);
        String firstKey = photoKey(f.serviceDefId());
        // Probe: read the row from INSIDE the R2 delete answer — proves the commit already happened.
        AtomicReference<String> keyInDbAtR2Delete = new AtomicReference<>("not-called");
        when(r2.deleteFiles(any())).thenAnswer(inv -> {
            keyInDbAtR2Delete.set(photoKey(f.serviceDefId()));
            return Set.of();
        });

        ResponseEntity<String> resp = upload(f.serviceDefId(), f.ownerToken(), JPEG);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deletedKeys()).containsExactly(firstKey);
        assertThat(photoKey(f.serviceDefId())).isNotEqualTo(firstKey).isNotNull();
        assertThat(keyInDbAtR2Delete.get())
                .as("the row already holds the NEW key when the old blob is deleted")
                .isEqualTo(photoKey(f.serviceDefId()))
                .isNotEqualTo(firstKey);
    }

    @Test
    @DisplayName("POST on a legacy row (photo_url, no key) deletes no blob and sets the new key")
    void should_notDeleteAnything_when_legacyUrlOnlyRowIsReplaced() throws Exception {
        SalonFixture f = salonWithService();
        jdbc.update("UPDATE service_definitions SET photo_url = 'https://legacy.example/p.jpg' WHERE id = ?",
                f.serviceDefId());

        ResponseEntity<String> resp = upload(f.serviceDefId(), f.ownerToken(), JPEG);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deletedKeys()).isEmpty();
        assertThat(photoKey(f.serviceDefId())).startsWith("services/" + f.serviceDefId() + "/");
    }

    @Test
    @DisplayName("DELETE — first call clears the pointers and deletes exactly the blob; the second is 204 with no R2 call")
    void should_beIdempotent_when_photoDeletedTwice() throws Exception {
        SalonFixture f = salonWithService();
        upload(f.serviceDefId(), f.ownerToken());
        String key = photoKey(f.serviceDefId());
        clearInvocations(r2);

        ResponseEntity<String> first = deletePhoto(f.serviceDefId(), f.ownerToken());

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(photoKey(f.serviceDefId())).isNull();
        assertThat(photoUrl(f.serviceDefId())).isNull();
        assertThat(deletedKeys()).containsExactly(key);

        clearInvocations(r2);
        ResponseEntity<String> second = deletePhoto(f.serviceDefId(), f.ownerToken());

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deletedKeys()).isEmpty();
        assertThat(photoKey(f.serviceDefId())).isNull();
    }

    // ── validation ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("POST — a GIF is rejected with 400 and the row is unchanged")
    void should_return400_when_unsupportedFormat() throws Exception {
        SalonFixture f = salonWithService();

        ResponseEntity<String> resp = upload(f.serviceDefId(), f.ownerToken(), GIF);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(photoKey(f.serviceDefId())).isNull();
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("POST — more than 5 MB is rejected with 413 at the multipart layer")
    void should_return413_when_fileExceedsLimit() throws Exception {
        SalonFixture f = salonWithService();
        byte[] big = new byte[5 * 1024 * 1024 + 1024];
        System.arraycopy(JPEG, 0, big, 0, JPEG.length);

        ResponseEntity<String> resp = upload(f.serviceDefId(), f.ownerToken(), big);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(photoKey(f.serviceDefId())).isNull();
    }

    @Test
    @DisplayName("POST — unknown service id is 403 (the @PreAuthorize ownership gate runs before any lookup)")
    void should_return403_when_serviceDoesNotExist() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(email("ghost"));

        ResponseEntity<String> resp = upload(UUID.randomUUID(), ownerToken, JPEG);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    // ── authorization matrix ─────────────────────────────────────────────────────

    @Test
    @DisplayName("POST/DELETE — SALON_ADMIN of the owning salon is allowed")
    void should_allow_when_salonAdminOfOwningSalon() throws Exception {
        SalonFixture f = salonWithService();
        String adminToken = fixtures.createSalonAdminAndGetToken(f.salonId(), email("admin"));

        assertThat(upload(f.serviceDefId(), adminToken, JPEG).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deletePhoto(f.serviceDefId(), adminToken).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("POST/DELETE — an INDEPENDENT_MASTER may manage their own service")
    void should_allow_when_independentMasterOwnsService() throws Exception {
        String token = fixtures.createIndependentMasterAndGetToken(email("im"));
        UUID serviceDefId = fixtures.createIndependentMasterService(token, "Own service");

        assertThat(upload(serviceDefId, token, JPEG).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deletePhoto(serviceDefId, token).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("POST/DELETE — a foreign INDEPENDENT_MASTER gets 403 (IDOR) and the row is unchanged")
    void should_return403_when_foreignIndependentMaster() throws Exception {
        String ownerToken = fixtures.createIndependentMasterAndGetToken(email("ima"));
        UUID serviceDefId = fixtures.createIndependentMasterService(ownerToken, "A's service");
        String foreignToken = fixtures.createIndependentMasterAndGetToken(email("imb"));
        upload(serviceDefId, ownerToken, JPEG);
        String key = photoKey(serviceDefId);
        clearInvocations(r2);

        ResponseEntity<String> up = upload(serviceDefId, foreignToken, JPEG);
        ResponseEntity<String> del = deletePhoto(serviceDefId, foreignToken);

        assertThat(up.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(photoKey(serviceDefId)).isEqualTo(key);
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
        assertThat(deletedKeys()).isEmpty();
    }

    @Test
    @DisplayName("POST/DELETE — a SALON_ADMIN and a SALON_OWNER of a DIFFERENT salon get 403")
    void should_return403_when_foreignSalonStaff() throws Exception {
        SalonFixture f = salonWithService();
        UUID otherSalon = fixtures.insertSalonWithOwner("Other Salon " + System.nanoTime());
        String foreignAdmin = fixtures.createSalonAdminAndGetToken(otherSalon, email("fadmin"));
        String foreignOwner = fixtures.createSalonOwnerAndGetToken(email("fowner"));

        assertThat(upload(f.serviceDefId(), foreignAdmin, JPEG).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(f.serviceDefId(), foreignOwner, JPEG).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(deletePhoto(f.serviceDefId(), foreignAdmin).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(photoKey(f.serviceDefId())).isNull();
    }

    @Test
    @DisplayName("POST/DELETE — SALON_MASTER (read-only) and CLIENT get 403")
    void should_return403_when_salonMasterOrClient() throws Exception {
        SalonFixture f = salonWithService();
        String masterToken = fixtures.createSalonMasterAndGetToken(f.salonId(), email("smaster"));
        String clientToken = fixtures.createClientAndGetToken(email("client"));

        assertThat(upload(f.serviceDefId(), masterToken, JPEG).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(upload(f.serviceDefId(), clientToken, JPEG).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(deletePhoto(f.serviceDefId(), masterToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(deletePhoto(f.serviceDefId(), clientToken).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("PATCH /services/{id}/photo no longer exists (405)")
    void should_return405_when_patchPhoto() throws Exception {
        SalonFixture f = salonWithService();

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/services/" + f.serviceDefId() + "/photo", HttpMethod.PATCH,
                new HttpEntity<>("{\"photoUrl\":\"https://x.example/p.jpg\"}", jsonHeaders(f.ownerToken())),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }

    private HttpHeaders jsonHeaders(String token) {
        HttpHeaders h = fixtures.bearerHeaders(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    // ── cache eviction ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("GET /masters/{id}/services after POST returns the new photo URL (masterServices evicted)")
    void should_serveNewUrl_when_photoUploadedAfterCachedRead() throws Exception {
        String email = email("imcache");
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID serviceDefId = fixtures.createIndependentMasterService(token, "Cached service");
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        String listUrl = "/api/v1/masters/" + masterId + "/services";
        ResponseEntity<String> before = restTemplate.getForEntity(listUrl, String.class);
        assertThat(before.getStatusCode()).isEqualTo(HttpStatus.OK);

        upload(serviceDefId, token, JPEG);
        String key = photoKey(serviceDefId);
        ResponseEntity<String> after = restTemplate.getForEntity(listUrl, String.class);

        assertThat(after.getBody()).contains("https://cdn.example/" + key);
    }

    // ── blob sweep on removal ────────────────────────────────────────────────────

    @Test
    @DisplayName("DELETE /services/{id} — the uploaded blob is purged after commit and the pointers are nulled")
    void should_purgeBlob_when_serviceDeleted() throws Exception {
        SalonFixture f = salonWithService();
        upload(f.serviceDefId(), f.ownerToken(), JPEG);
        String key = photoKey(f.serviceDefId());
        clearInvocations(r2);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/services/" + f.serviceDefId(), HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(f.ownerToken())), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deletedKeys()).containsExactly(key);
        assertThat(photoKey(f.serviceDefId())).isNull();
        assertThat(photoUrl(f.serviceDefId())).isNull();
    }

    @Test
    @DisplayName("DELETE /salons/{id} — every catalogue photo blob is purged after commit")
    void should_purgeAllServicePhotoBlobs_when_salonDeleted() throws Exception {
        SalonFixture f = salonWithService();
        upload(f.serviceDefId(), f.ownerToken(), JPEG);
        String key = photoKey(f.serviceDefId());
        clearInvocations(r2);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + f.salonId(), HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(f.ownerToken())), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deletedKeys()).containsExactly(key);
        assertThat(photoKey(f.serviceDefId())).isNull();
        assertThat(photoUrl(f.serviceDefId())).isNull();
    }

    @Test
    @DisplayName("DELETE /users/me (INDEPENDENT_MASTER) — their service photo blobs are purged after commit")
    void should_purgeServicePhotoBlobs_when_independentMasterDeletesAccount() throws Exception {
        String token = fixtures.createIndependentMasterAndGetToken(email("imdel"));
        UUID serviceDefId = fixtures.createIndependentMasterService(token, "To be deleted");
        upload(serviceDefId, token, JPEG);
        String key = photoKey(serviceDefId);
        clearInvocations(r2);

        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deletedKeys()).containsExactly(key);
        assertThat(photoKey(serviceDefId)).isNull();
    }

    @Test
    @DisplayName("POST — independent master self-deletes while the upload is parked in R2: 404, NEW blob discarded")
    void should_return404AndDeleteNewBlob_when_independentMasterSelfDeletesWhileUploadParkedInR2() throws Exception {
        String token = fixtures.createIndependentMasterAndGetToken(email("imrace"));
        UUID serviceDefId = fixtures.createIndependentMasterService(token, "Raced service");
        CountDownLatch inR2 = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Park the request inside the R2 call: it has passed the read-gate but not yet taken the row lock.
        doAnswer(inv -> {
            inR2.countDown();
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(r2).uploadFile(anyString(), any(), anyLong(), anyString());
        ExecutorService pool = Executors.newSingleThreadExecutor();

        ResponseEntity<String> resp;
        ResponseEntity<String> selfDelete;
        try {
            Future<ResponseEntity<String>> pending = pool.submit(() -> upload(serviceDefId, token, JPEG));
            assertThat(inR2.await(20, TimeUnit.SECONDS)).isTrue();
            selfDelete = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                    new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);
            release.countDown();
            resp = pending.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(selfDelete.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(uploadedKeys()).hasSize(1);
        assertThat(deletedKeys()).containsExactly(uploadedKeys().get(0));
        assertThat(photoKey(serviceDefId)).isNull();
    }

    // ── replace ordering / failure / concurrency (audit cycle 1, A) ──────────────

    @Test
    @DisplayName("POST — R2 upload fails: 5xx, the old photo, its pointers and its blob are untouched")
    void should_keepOldPhoto_when_r2UploadFailsOnReplace() throws Exception {
        SalonFixture f = salonWithService();
        upload(f.serviceDefId(), f.ownerToken());
        String oldKey = photoKey(f.serviceDefId());
        String oldUrl = photoUrl(f.serviceDefId());
        clearInvocations(r2);
        doThrow(new IllegalStateException("r2 down")).when(r2).uploadFile(anyString(), any(), anyLong(), anyString());

        ResponseEntity<String> resp = upload(f.serviceDefId(), f.ownerToken());

        assertThat(resp.getStatusCode().is5xxServerError()).isTrue();
        assertThat(photoKey(f.serviceDefId())).isEqualTo(oldKey);
        assertThat(photoUrl(f.serviceDefId())).isEqualTo(oldUrl);
        assertThat(deletedKeys()).isEmpty();
    }

    @Test
    @DisplayName("POST — DB write fails after the upload: the NEW blob is deleted, the old photo and blob stay")
    void should_deleteNewBlob_when_dbWriteFailsAfterUpload() throws Exception {
        SalonFixture f = salonWithService();
        upload(f.serviceDefId(), f.ownerToken());
        String oldKey = photoKey(f.serviceDefId());
        String oldUrl = photoUrl(f.serviceDefId());
        clearInvocations(r2);
        // Deterministic failure inside the locked write transaction, after the row mutation: the purger
        // hook is invoked from replacePhotoLocked, so the throw rolls the whole write back.
        doThrow(new IllegalStateException("forced write failure"))
                .when(servicePhotoBlobPurger).purgeAfterCommit(any(), any());

        ResponseEntity<String> resp = upload(f.serviceDefId(), f.ownerToken());

        assertThat(resp.getStatusCode().is5xxServerError()).isTrue();
        assertThat(uploadedKeys()).hasSize(1);
        assertThat(deletedKeys()).containsExactly(uploadedKeys().get(0));
        assertThat(photoKey(f.serviceDefId())).isEqualTo(oldKey);
        assertThat(photoUrl(f.serviceDefId())).isEqualTo(oldUrl);
    }

    @Test
    @DisplayName("POST x2 racing: both blobs are uploaded before either row write; every superseded blob is deleted, no orphan")
    void should_leaveExactlyOneLiveBlob_when_twoUploadsRace() throws Exception {
        SalonFixture f = salonWithService();
        upload(f.serviceDefId(), f.ownerToken());
        String originalKey = photoKey(f.serviceDefId());
        clearInvocations(r2);
        // Both requests must be inside R2 (past the read-gate, before any write) before either proceeds, which
        // forces the interleaving the row lock has to serialise.
        CountDownLatch bothUploading = new CountDownLatch(2);
        doAnswer(inv -> {
            bothUploading.countDown();
            assertThat(bothUploading.await(20, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(r2).uploadFile(anyString(), any(), anyLong(), anyString());
        ExecutorService pool = Executors.newFixedThreadPool(2);

        List<Future<ResponseEntity<String>>> results;
        try {
            results = pool.invokeAll(List.of(
                    () -> upload(f.serviceDefId(), f.ownerToken()),
                    () -> upload(f.serviceDefId(), f.ownerToken())));
        } finally {
            pool.shutdownNow();
        }

        for (Future<ResponseEntity<String>> r : results) {
            assertThat(r.get().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        String liveKey = photoKey(f.serviceDefId());
        Set<String> everyBlob = new HashSet<>(uploadedKeys());
        everyBlob.add(originalKey);
        Set<String> deleted = new HashSet<>(deletedKeys());
        assertThat(uploadedKeys()).hasSize(2);
        assertThat(everyBlob).contains(liveKey);
        assertThat(deleted).doesNotContain(liveKey);
        // Invariant: exactly one blob is live (the row's pointer); every other blob ever stored was deleted,
        // so stored = deleted + live — an orphan would appear in `everyBlob` but in neither set.
        assertThat(everyBlob).as("live blob = every blob ever stored minus every deleted one")
                .containsExactlyInAnyOrderElementsOf(concat(deleted, liveKey));
    }

    private static Set<String> concat(Set<String> deleted, String live) {
        Set<String> all = new HashSet<>(deleted);
        all.add(live);
        return all;
    }

    // ── deactivated service / salon → 404 (audit cycle 1, B) ─────────────────────

    @Test
    @DisplayName("POST/DELETE — a deactivated service definition is 404, nothing is uploaded or deleted")
    void should_return404_when_serviceDefinitionDeactivated() throws Exception {
        SalonFixture f = salonWithService();
        jdbc.update("UPDATE service_definitions SET is_active = FALSE WHERE id = ?", f.serviceDefId());

        ResponseEntity<String> up = upload(f.serviceDefId(), f.ownerToken());
        ResponseEntity<String> del = deletePhoto(f.serviceDefId(), f.ownerToken());

        assertThat(up.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
        assertThat(deletedKeys()).isEmpty();
        assertThat(photoKey(f.serviceDefId())).isNull();
    }

    @Test
    @DisplayName("POST — definition deactivated while the upload is parked in R2: 404, NEW blob deleted, row unchanged")
    void should_return404AndDeleteNewBlob_when_deactivatedWhileUploadParkedInR2() throws Exception {
        SalonFixture f = salonWithService();
        upload(f.serviceDefId(), f.ownerToken());
        String oldKey = photoKey(f.serviceDefId());
        String oldUrl = photoUrl(f.serviceDefId());
        clearInvocations(r2);
        CountDownLatch inR2 = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        // Park the request inside the R2 call: it has passed the read-gate but not yet taken the row lock.
        doAnswer(inv -> {
            inR2.countDown();
            assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(r2).uploadFile(anyString(), any(), anyLong(), anyString());
        ExecutorService pool = Executors.newSingleThreadExecutor();

        ResponseEntity<String> resp;
        try {
            Future<ResponseEntity<String>> pending = pool.submit(() -> upload(f.serviceDefId(), f.ownerToken()));
            assertThat(inR2.await(20, TimeUnit.SECONDS)).isTrue();
            jdbc.update("UPDATE service_definitions SET is_active = FALSE WHERE id = ?", f.serviceDefId());
            release.countDown();
            resp = pending.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(uploadedKeys()).hasSize(1);
        assertThat(deletedKeys()).containsExactly(uploadedKeys().get(0));
        assertThat(photoKey(f.serviceDefId())).isEqualTo(oldKey);
        assertThat(photoUrl(f.serviceDefId())).isEqualTo(oldUrl);
    }

    @Test
    @DisplayName("POST/DELETE — a still-active definition of a deactivated salon is 404")
    void should_return404_when_owningSalonDeactivated() throws Exception {
        SalonFixture f = salonWithService();
        upload(f.serviceDefId(), f.ownerToken());
        String key = photoKey(f.serviceDefId());
        jdbc.update("UPDATE salons SET is_active = FALSE WHERE id = ?", f.salonId());
        clearInvocations(r2);

        ResponseEntity<String> up = upload(f.serviceDefId(), f.ownerToken());
        ResponseEntity<String> del = deletePhoto(f.serviceDefId(), f.ownerToken());

        assertThat(up.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
        assertThat(deletedKeys()).isEmpty();
        assertThat(photoKey(f.serviceDefId())).isEqualTo(key);
    }

    @Test
    @DisplayName("POST/DELETE — after the salon is deleted through the API the service is 404")
    void should_return404_when_salonDeletedThroughApi() throws Exception {
        SalonFixture f = salonWithService();
        restTemplate.exchange("/api/v1/salons/" + f.salonId(), HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(f.ownerToken())), String.class);
        clearInvocations(r2);

        ResponseEntity<String> up = upload(f.serviceDefId(), f.ownerToken());
        ResponseEntity<String> del = deletePhoto(f.serviceDefId(), f.ownerToken());

        assertThat(up.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
    }

    // ── delete ordering and legacy rows (audit cycle 1, C/D) ─────────────────────

    @Test
    @DisplayName("DELETE — the DB pointers are already cleared when R2 is asked to delete the blob")
    void should_clearDbBeforeCallingR2_when_photoDeleted() throws Exception {
        SalonFixture f = salonWithService();
        upload(f.serviceDefId(), f.ownerToken());
        AtomicReference<String> keyInDbAtR2Time = new AtomicReference<>("not-called");
        when(r2.deleteFiles(any())).thenAnswer(inv -> {
            keyInDbAtR2Time.set(photoKey(f.serviceDefId()));
            return Set.of();
        });

        ResponseEntity<String> resp = deletePhoto(f.serviceDefId(), f.ownerToken());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(keyInDbAtR2Time.get()).isNull();
    }

    @Test
    @DisplayName("DELETE — an R2 failure still leaves the DB cleared and the response 204")
    void should_leaveDbCleared_when_r2DeleteFails() throws Exception {
        SalonFixture f = salonWithService();
        upload(f.serviceDefId(), f.ownerToken());
        when(r2.deleteFiles(any())).thenThrow(new IllegalStateException("r2 down"));

        ResponseEntity<String> resp = deletePhoto(f.serviceDefId(), f.ownerToken());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(photoKey(f.serviceDefId())).isNull();
        assertThat(photoUrl(f.serviceDefId())).isNull();
    }

    @Test
    @DisplayName("DELETE on a legacy row (photo_url, no key) clears photo_url, 204, no R2 call")
    void should_clearLegacyUrl_when_noKey() throws Exception {
        SalonFixture f = salonWithService();
        jdbc.update("UPDATE service_definitions SET photo_url = 'https://legacy.example/p.jpg' WHERE id = ?",
                f.serviceDefId());

        ResponseEntity<String> resp = deletePhoto(f.serviceDefId(), f.ownerToken());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(photoUrl(f.serviceDefId())).isNull();
        assertThat(photoKey(f.serviceDefId())).isNull();
        assertThat(deletedKeys()).isEmpty();
    }
}
