package com.beautica.service;

import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.entity.PriceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Phase 342 QA — tenant isolation of the service-photo feature over real HTTP + Postgres: cross-tenant
 * writes against an EXISTING photo leave it intact, public-read cache is evicted on replace/delete, and every
 * removal sweep deletes only the removed owner's blobs (a control owner's blob must survive).
 */
@DisplayName("Service photo — tenant isolation, cache visibility and sweep scoping (Phase 342 QA)")
class ServicePhotoIsolationIT extends AbstractServicePhotoIT {

    private static String email(String tag) {
        return email("sphiso", tag);
    }

    // ── cross-tenant against an EXISTING photo ───────────────────────────────────

    @Test
    @DisplayName("POST/DELETE — owner of salon B cannot touch salon A's existing photo: 403, key+url intact, no R2 call")
    void should_leaveExistingPhotoIntact_when_ownerOfAnotherSalonUploadsOrDeletes() throws Exception {
        String tokenA = fixtures.createSalonOwnerAndGetToken(email("ownerA"));
        UUID salonA = fixtures.createSalon(tokenA, "Iso Salon A " + System.nanoTime());
        UUID serviceA = fixtures.createServiceDefinition(tokenA, salonA, "Манікюр A");
        String tokenB = fixtures.createSalonOwnerAndGetToken(email("ownerB"));
        fixtures.createSalon(tokenB, "Iso Salon B " + System.nanoTime());
        upload(serviceA, tokenA);
        String keyBefore = photoKey(serviceA);
        String urlBefore = photoUrl(serviceA);
        clearInvocations(r2);

        ResponseEntity<String> up = upload(serviceA, tokenB);
        ResponseEntity<String> del = deletePhoto(serviceA, tokenB);

        assertThat(up.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(photoKey(serviceA)).isEqualTo(keyBefore);
        assertThat(photoUrl(serviceA)).isEqualTo(urlBefore);
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString());
        assertThat(deletedKeys()).isEmpty();
    }

    @Test
    @DisplayName("POST/DELETE — SALON_ADMIN of salon B cannot touch salon A's existing photo: 403, key intact")
    void should_leaveExistingPhotoIntact_when_adminOfAnotherSalonUploadsOrDeletes() throws Exception {
        String tokenA = fixtures.createSalonOwnerAndGetToken(email("ownerAA"));
        UUID salonA = fixtures.createSalon(tokenA, "Iso Salon AA " + System.nanoTime());
        UUID serviceA = fixtures.createServiceDefinition(tokenA, salonA, "Манікюр AA");
        UUID salonB = fixtures.insertSalonWithOwner("Iso Salon BB " + System.nanoTime());
        String adminB = fixtures.createSalonAdminAndGetToken(salonB, email("adminBB"));
        upload(serviceA, tokenA);
        String keyBefore = photoKey(serviceA);
        clearInvocations(r2);

        ResponseEntity<String> up = upload(serviceA, adminB);
        ResponseEntity<String> del = deletePhoto(serviceA, adminB);

        assertThat(up.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(photoKey(serviceA)).isEqualTo(keyBefore);
        assertThat(deletedKeys()).isEmpty();
    }

    @Test
    @DisplayName("POST/DELETE — an unauthenticated caller gets 401 and the existing photo is untouched")
    void should_return401_when_noToken() throws Exception {
        String token = fixtures.createIndependentMasterAndGetToken(email("im401"));
        UUID serviceDefId = fixtures.createIndependentMasterService(token, "Anon target");
        upload(serviceDefId, token);
        String keyBefore = photoKey(serviceDefId);
        clearInvocations(r2);

        ResponseEntity<String> del = restTemplate.exchange("/api/v1/services/" + serviceDefId + "/photo",
                HttpMethod.DELETE, new HttpEntity<>(new HttpHeaders()), String.class);

        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(photoKey(serviceDefId)).isEqualTo(keyBefore);
        assertThat(deletedKeys()).isEmpty();
    }

    // ── public-read cache visibility ─────────────────────────────────────────────

    @Test
    @DisplayName("GET /masters/{id}/services — replace shows the new URL and drops the old one (cache evicted)")
    void should_serveReplacedUrlOnly_when_photoReplacedAfterCachedRead() throws Exception {
        String email = email("imrep");
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID serviceDefId = fixtures.createIndependentMasterService(token, "Replace me");
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        String listUrl = "/api/v1/masters/" + masterId + "/services";
        upload(serviceDefId, token);
        String firstKey = photoKey(serviceDefId);
        assertThat(restTemplate.getForEntity(listUrl, String.class).getBody()).contains(firstKey);

        upload(serviceDefId, token);
        String secondKey = photoKey(serviceDefId);
        String after = restTemplate.getForEntity(listUrl, String.class).getBody();

        assertThat(secondKey).isNotEqualTo(firstKey);
        assertThat(after).contains(secondKey).doesNotContain(firstKey);
    }

    @Test
    @DisplayName("GET /masters/{id}/services — after DELETE photo the cached URL disappears (photoUrl null)")
    void should_dropUrlFromPublicRead_when_photoDeletedAfterCachedRead() throws Exception {
        String email = email("imdelc");
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID serviceDefId = fixtures.createIndependentMasterService(token, "Delete me");
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        String listUrl = "/api/v1/masters/" + masterId + "/services";
        upload(serviceDefId, token);
        String key = photoKey(serviceDefId);
        assertThat(restTemplate.getForEntity(listUrl, String.class).getBody()).contains(key);

        ResponseEntity<String> del = deletePhoto(serviceDefId, token);
        String after = restTemplate.getForEntity(listUrl, String.class).getBody();

        assertThat(del.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(after).doesNotContain(key);
    }

    // ── sweeps delete only the removed owner's blobs ─────────────────────────────

    @Test
    @DisplayName("DELETE /users/me — purges only the deleting master's photo; a second master's photo survives")
    void should_purgeOnlyOwnBlobs_when_independentMasterDeletesAccountWithControlMaster() throws Exception {
        String tokenA = fixtures.createIndependentMasterAndGetToken(email("selfA"));
        UUID serviceA = fixtures.createIndependentMasterService(tokenA, "A service");
        String tokenB = fixtures.createIndependentMasterAndGetToken(email("selfB"));
        UUID serviceB = fixtures.createIndependentMasterService(tokenB, "B service");
        upload(serviceA, tokenA);
        upload(serviceB, tokenB);
        String keyA = photoKey(serviceA);
        String keyB = photoKey(serviceB);
        String urlB = photoUrl(serviceB);
        clearInvocations(r2);

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(tokenA)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deletedKeys()).containsExactly(keyA).doesNotContain(keyB);
        assertThat(photoKey(serviceB)).isEqualTo(keyB);
        assertThat(photoUrl(serviceB)).isEqualTo(urlB);
    }

    @Test
    @DisplayName("DELETE /services/{id} — purges only that service's blob; a sibling service's photo survives")
    void should_purgeOnlyTargetBlob_when_oneOfTwoServicesDeleted() throws Exception {
        String token = fixtures.createSalonOwnerAndGetToken(email("sib"));
        UUID salon = fixtures.createSalon(token, "Sibling Salon " + System.nanoTime());
        var types = fixtures.activeSelectableServiceTypes(2);
        UUID target = fixtures.createServiceDefinition(token, salon, new CreateServiceDefinitionRequest(
                "Target", null, types.get(0).platformCategoryName(), 60, 0,
                PriceType.FIXED, new BigDecimal("500.00"), null, null, types.get(0).id()));
        UUID sibling = fixtures.createServiceDefinition(token, salon, new CreateServiceDefinitionRequest(
                "Sibling", null, types.get(1).platformCategoryName(), 60, 0,
                PriceType.FIXED, new BigDecimal("500.00"), null, null, types.get(1).id()));
        upload(target, token);
        upload(sibling, token);
        String targetKey = photoKey(target);
        String siblingKey = photoKey(sibling);
        clearInvocations(r2);

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/services/" + target, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deletedKeys()).containsExactly(targetKey).doesNotContain(siblingKey);
        assertThat(photoKey(sibling)).isEqualTo(siblingKey);
    }

    @Test
    @DisplayName("DELETE /salons/{id} — purges only that salon's catalogue blobs; another salon's photo survives")
    void should_purgeOnlyOwnSalonBlobs_when_salonDeletedWithControlSalon() throws Exception {
        String tokenA = fixtures.createSalonOwnerAndGetToken(email("salA"));
        UUID salonA = fixtures.createSalon(tokenA, "Sweep Salon A " + System.nanoTime());
        UUID serviceA = fixtures.createServiceDefinition(tokenA, salonA, "Sweep A");
        String tokenB = fixtures.createSalonOwnerAndGetToken(email("salB"));
        UUID salonB = fixtures.createSalon(tokenB, "Sweep Salon B " + System.nanoTime());
        UUID serviceB = fixtures.createServiceDefinition(tokenB, salonB, "Sweep B");
        upload(serviceA, tokenA);
        upload(serviceB, tokenB);
        String keyA = photoKey(serviceA);
        String keyB = photoKey(serviceB);
        clearInvocations(r2);

        ResponseEntity<String> resp = restTemplate.exchange("/api/v1/salons/" + salonA, HttpMethod.DELETE,
                new HttpEntity<>(fixtures.bearerHeaders(tokenA)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(deletedKeys()).containsExactly(keyA).doesNotContain(keyB);
        assertThat(photoKey(serviceB)).isEqualTo(keyB);
    }
}
