package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.entity.PriceType;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full-stack integration tests for the first-time bulk service-setup flow
 * (HTTP → controller → service → real PostgreSQL).
 *
 * <p>These tests pin the behaviours that only a real transaction + database can prove:
 * <ul>
 *   <li>Both endpoints persist the whole batch and derive name/category from the seeded
 *       service types server-side.</li>
 *   <li>409 first-time precondition: a second bulk call is rejected once the master has
 *       active services, and the existing rows are untouched.</li>
 *   <li>Transactional all-or-nothing: a batch containing one bad item rolls back ZERO
 *       rows — the failed item does not leak a partial definition.</li>
 *   <li>SALON_ADMIN may bulk-create on behalf of a salon master (intentional
 *       canManageSalon broadening), while a cross-salon target is denied with 403.</li>
 * </ul>
 *
 * <p>Service types are taken from the Flyway-seeded {@code service_types} (joined to an
 * APPROVED+active platform category) so the derived category passes the server-side gate.
 */
@Import(TestSecurityConfig.class)
@DisplayName("Bulk first-time service setup — full-flow integration")
class BulkServiceSetupIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BulkServiceSetupIntegrationTest.class);

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;

    private ServiceTestFixtures fixtures;
    private List<ServiceTestFixtures.SeededServiceType> seededTypes;

    @BeforeEach
    void setUp() {
        restTemplate.getRestTemplate().setRequestFactory(
                new HttpComponentsClientHttpRequestFactory(HttpClients.createDefault()));
        fixtures = new ServiceTestFixtures(restTemplate, jdbcTemplate, objectMapper, passwordEncoder);
        seededTypes = fixtures.activeSelectableServiceTypes(2);
        assertThat(seededTypes)
                .as("the seeded catalog must provide at least 2 selectable service types for the bulk flow")
                .hasSize(2);
    }

    private BulkServiceItemRequest fixed(UUID serviceTypeId, int duration, String price) {
        return new BulkServiceItemRequest(serviceTypeId, duration, PriceType.FIXED, new BigDecimal(price), null, null);
    }

    private BulkServiceItemRequest range(UUID serviceTypeId, int duration, String min, String max) {
        return new BulkServiceItemRequest(serviceTypeId, duration, PriceType.RANGE, null, new BigDecimal(min), new BigDecimal(max));
    }

    // ── Self endpoint happy path ───────────────────────────────────────────────

    @Test
    @DisplayName("independent master bulk-creates a mixed FIXED+RANGE batch — 201, both persisted, name+category derived, RANGE base_price=priceMin")
    void should_persistWholeBatch_when_independentMasterBulkSetup() throws Exception {
        String email = "indep-bulk-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        var fixedType = seededTypes.get(0);
        var rangeType = seededTypes.get(1);
        var request = new BulkCreateServicesRequest(List.of(
                fixed(fixedType.id(), 60, "350.00"),
                range(rangeType.id(), 120, "800.00", "1500.00")));

        log.debug("Act: POST /api/v1/independent-masters/me/services/bulk with a mixed 2-item batch");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/independent-masters/me/services/bulk", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        var parsed = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {});
        List<MasterServiceResponse> created = parsed.data();

        assertThat(created).as("one response entry per created service").hasSize(2);

        // Name derived from the seeded ServiceType.nameUk (no free-text name accepted).
        assertThat(created)
                .extracting(r -> r.serviceDefinition().name())
                .containsExactlyInAnyOrder(fixedType.nameUk(), rangeType.nameUk());

        // RANGE entry: base_price (priceMin) is the canonical floor.
        MasterServiceResponse rangeEntry = created.stream()
                .filter(r -> r.priceType() == PriceType.RANGE).findFirst().orElseThrow();
        assertThat(rangeEntry.priceMin()).isEqualByComparingTo("800.00");
        assertThat(rangeEntry.priceMax()).isEqualByComparingTo("1500.00");

        assertThat(fixtures.countServiceDefinitionsForMaster(masterId))
                .as("both service definitions are persisted to the DB")
                .isEqualTo(2L);
    }

    // ── 409 first-time precondition ────────────────────────────────────────────

    @Test
    @DisplayName("second bulk call returns 409 and leaves the existing services untouched")
    void should_return409AndNotMutate_when_masterAlreadyHasServices() throws Exception {
        String email = "indep-twice-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        var firstRequest = new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(0).id(), 60, "350.00")));
        ResponseEntity<String> first = restTemplate.exchange(
                "/api/v1/independent-masters/me/services/bulk", HttpMethod.POST,
                new HttpEntity<>(firstRequest, fixtures.bearerHeaders(token)), String.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Second bulk call — the master now has an active service.
        var secondRequest = new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(1).id(), 90, "500.00")));

        log.debug("Act: POST /api/v1/independent-masters/me/services/bulk a SECOND time — must be 409");
        ResponseEntity<String> second = restTemplate.exchange(
                "/api/v1/independent-masters/me/services/bulk", HttpMethod.POST,
                new HttpEntity<>(secondRequest, fixtures.bearerHeaders(token)), String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(fixtures.countServiceDefinitionsForMaster(masterId))
                .as("the rejected second batch must NOT add any rows — only the first service remains")
                .isEqualTo(1L);
    }

    // ── Transactional all-or-nothing ───────────────────────────────────────────

    @Test
    @DisplayName("a batch with one unknown serviceTypeId rolls back entirely — ZERO rows persisted")
    void should_persistNothing_when_oneItemHasUnknownServiceType() throws Exception {
        String email = "indep-rollback-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        // First item is valid; the second references a non-existent service type → whole batch fails.
        var request = new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(0).id(), 60, "350.00"),
                fixed(UUID.randomUUID(), 90, "500.00")));

        log.debug("Act: POST a 2-item bulk batch where item 2 has an unknown serviceTypeId — expect rollback");
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/independent-masters/me/services/bulk", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);

        assertThat(resp.getStatusCode())
                .as("a non-existent serviceTypeId surfaces as 404")
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(fixtures.countServiceDefinitionsForMaster(masterId))
                .as("all-or-nothing: the valid first item must NOT leak a partial row")
                .isEqualTo(0L);
    }

    // ── Salon on-behalf: SALON_ADMIN allowed within own salon ──────────────────

    @Test
    @DisplayName("SALON_ADMIN bulk-creates on behalf of a salon master in their own salon — 201")
    void should_allowSalonAdminOnBehalf_when_masterInSameSalon() throws Exception {
        String ownerEmail = "owner-admin-" + System.nanoTime() + "@beautica.test";
        String ownerToken = fixtures.createSalonOwnerAndGetToken(ownerEmail);
        UUID salonId = fixtures.createSalon(ownerToken, "Admin Bulk Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);

        String adminToken = fixtures.createSalonAdminAndGetToken(
                salonId, "admin-bulk-" + System.nanoTime() + "@beautica.test");

        var request = new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(0).id(), 60, "350.00")));

        log.debug("Act: SALON_ADMIN POST /api/v1/salons/{}/masters/{}/services/bulk — on-behalf within own salon", salonId, masterId);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(adminToken)), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(fixtures.countServiceDefinitionsForMaster(masterId))
                .as("the on-behalf batch is persisted, owned by the master row")
                .isEqualTo(1L);
    }

    // ── Salon on-behalf: cross-salon IDOR denied ───────────────────────────────

    @Test
    @DisplayName("owner of salon A cannot bulk-create for a master in salon B — 403, nothing persisted")
    void should_deny_when_ownerOfSalonATargetsMasterInSalonB() throws Exception {
        // Emails MUST be lowercase: AuthService.login lowercases the supplied email before
        // findByEmail, so a mixed-case stored email would never match (401, not a feature bug).
        String ownerAEmail = "owner-a-" + System.nanoTime() + "@beautica.test";
        String ownerAToken = fixtures.createSalonOwnerAndGetToken(ownerAEmail);
        UUID salonAId = fixtures.createSalon(ownerAToken, "Salon A");

        // Salon B + its master are inserted directly (no second login needed — this test only
        // acts as owner A). Owner B never authenticates, so the per-IP /auth bucket is spared.
        UUID salonBId = fixtures.insertSalonWithOwner("Salon B");
        UUID masterInSalonB = fixtures.createSalonMaster(salonBId);

        var request = new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(0).id(), 60, "350.00")));

        // Owner A targets a master that lives in salon B, but addresses it via salon A's path.
        log.debug("Act: owner A POST /api/v1/salons/{}/masters/{}/services/bulk targeting a salon-B master — must be 403", salonAId, masterInSalonB);
        ResponseEntity<String> resp = restTemplate.exchange(
                "/api/v1/salons/" + salonAId + "/masters/" + masterInSalonB + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(ownerAToken)), String.class);

        assertThat(resp.getStatusCode())
                .as("masterBelongsToSalon(masterInSalonB, salonA) is false → 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fixtures.countServiceDefinitionsForMaster(masterInSalonB))
                .as("the denied request must not persist anything for the salon-B master")
                .isEqualTo(0L);
    }
}
