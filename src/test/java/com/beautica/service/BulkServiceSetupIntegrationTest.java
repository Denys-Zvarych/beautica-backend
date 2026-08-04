package com.beautica.service;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.ApiResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.DuplicateServiceException;
import com.beautica.config.TestSecurityConfig;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.DuplicateServiceResponse;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.entity.PriceType;
import com.beautica.service.service.ServiceCatalogService;
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
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * Full-stack integration tests for the bulk service-create flow
 * (HTTP → controller → service → real PostgreSQL).
 *
 * <p>The flow is <em>additive</em>: one multi-select screen serves both initial catalogue
 * setup and later "add more services" passes, so there is no menu-emptiness precondition.
 * The only state-conflict a caller can hit is a per-service {@code DUPLICATE_SERVICE} 409.
 *
 * <p>These tests pin the behaviours that only a real transaction + database can prove:
 * <ul>
 *   <li>Both endpoints persist the whole batch and derive name/category from the seeded
 *       service types server-side.</li>
 *   <li>Additive: a second bulk call for a master who already has services succeeds and
 *       appends to the catalogue rather than being rejected — leaving the pre-existing
 *       definitions untouched (same ids, still active, price unchanged), on BOTH entry
 *       points.</li>
 *   <li>Append bookkeeping: {@code masters.min_effective_price} (the search-facing V58
 *       column) moves down for a cheaper appended service, holds for a dearer one, and
 *       follows a RANGE item's priceMin floor; the master's cached browse list is evicted.</li>
 *   <li>Append conflict: a second batch mixing a fresh type with an ALREADY-OWNED one is
 *       rejected whole, with the first batch surviving intact.</li>
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
@DisplayName("Additive bulk service create — full-flow integration")
class BulkServiceSetupIntegrationTest extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(BulkServiceSetupIntegrationTest.class);

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private ServiceCatalogService serviceCatalogService;
    @Autowired private org.springframework.cache.CacheManager cacheManager;

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

    /** POSTs a batch to the authenticated master's own bulk endpoint. */
    private ResponseEntity<String> postSelfBulk(String token, BulkCreateServicesRequest request) {
        return restTemplate.exchange(
                "/api/v1/independent-masters/me/services/bulk", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
    }

    /** POSTs a batch to the salon on-behalf bulk endpoint. */
    private ResponseEntity<String> postSalonBulk(
            String token, UUID salonId, UUID masterId, BulkCreateServicesRequest request) {
        return restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/masters/" + masterId + "/services/bulk", HttpMethod.POST,
                new HttpEntity<>(request, fixtures.bearerHeaders(token)), String.class);
    }

    private List<MasterServiceResponse> createdFrom(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data();
    }

    private DuplicateServiceResponse duplicateBodyFrom(ResponseEntity<String> resp) throws Exception {
        return objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<DuplicateServiceResponse>>() {}).data();
    }

    /** Active {@code service_definitions} ids owned by the master, so append tests can prove identity, not just count. */
    private List<UUID> activeDefinitionIdsForMaster(UUID masterId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM service_definitions WHERE owner_id = ? AND is_active = TRUE ORDER BY created_at",
                UUID.class, masterId);
    }

    private long activeAssignmentCountForMaster(UUID masterId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ? AND is_active = TRUE",
                Long.class, masterId);
        return count == null ? 0L : count;
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
        ResponseEntity<String> resp = postSelfBulk(token, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<MasterServiceResponse> created = createdFrom(resp);

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

    // ── Additive: repeat calls append to the catalogue ─────────────────────────

    /**
     * The bulk endpoint used to be first-time-only: a second call for a master who already had
     * an active service was rejected 409, which is why the app needed a separate single-service
     * form for "add one more". That precondition is gone, so the multi-select screen is now the
     * single way to add services at any point in a master's life.
     *
     * <p>Asserting the 201 alone would be weak — it would pass even if the second batch silently
     * persisted nothing — so the decisive assertion is the row count: the second batch must
     * APPEND, leaving both services in the catalogue.
     */
    @Test
    @DisplayName("second bulk call for a master who already has services succeeds and appends to the catalogue")
    void should_appendToCatalogue_when_masterAlreadyHasServices() throws Exception {
        String email = "indep-twice-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        var firstRequest = new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(0).id(), 60, "350.00")));
        ResponseEntity<String> first = postSelfBulk(token, firstRequest);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Second bulk call — the master now has an active service, and a DIFFERENT service type
        // is requested, so nothing collides with the V121 (owner, service_type) uniqueness.
        var secondRequest = new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(1).id(), 90, "500.00")));

        log.debug("Act: POST /api/v1/independent-masters/me/services/bulk a SECOND time — must be 201");
        ResponseEntity<String> second = postSelfBulk(token, secondRequest);

        assertThat(second.getStatusCode())
                .as("bulk create is additive — an existing catalogue no longer blocks the call")
                .isEqualTo(HttpStatus.CREATED);

        assertThat(createdFrom(second))
                .extracting(r -> r.serviceDefinition().name())
                .as("the response describes the newly added service, not the pre-existing one")
                .containsExactly(seededTypes.get(1).nameUk());

        assertThat(fixtures.countServiceDefinitionsForMaster(masterId))
                .as("the second batch APPENDS: both the original and the added service persist")
                .isEqualTo(2L);
    }

    /**
     * Row count alone proves the appended service arrived; it does NOT prove the pre-existing
     * ones survived <em>as they were</em>. A "replace the catalogue" regression (deactivate the
     * old rows, insert the new batch) would keep a plausible count while silently wiping the
     * master's menu — so this pins IDENTITY: the exact definition ids created by the first batch
     * are still there, still active, still carrying an active assignment, still priced the same.
     */
    @Test
    @DisplayName("appending leaves the pre-existing definitions untouched — same ids, still active, price unchanged")
    void should_leavePreExistingServicesUntouched_when_secondBatchAppends() throws Exception {
        String email = "indep-untouched-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        ResponseEntity<String> first = postSelfBulk(token, new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(0).id(), 60, "350.00"))));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID originalDefId = createdFrom(first).get(0).serviceDefinition().id();

        log.debug("Act: append a second, different service type to a catalogue that already holds one");
        ResponseEntity<String> second = postSelfBulk(token, new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(1).id(), 90, "500.00"))));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID appendedDefId = createdFrom(second).get(0).serviceDefinition().id();

        assertThat(activeDefinitionIdsForMaster(masterId))
                .as("the ORIGINAL definition id survives alongside the appended one — an append "
                        + "that replaced the catalogue would keep the count but change the ids")
                .containsExactlyInAnyOrder(originalDefId, appendedDefId);
        assertThat(activeAssignmentCountForMaster(masterId))
                .as("both definitions still carry an ACTIVE master_services assignment — a "
                        + "deactivated assignment would hide the original from the master's menu "
                        + "while leaving its definition row in place")
                .isEqualTo(2L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT base_price FROM service_definitions WHERE id = ?", BigDecimal.class, originalDefId))
                .as("the appended batch must not rewrite the pre-existing service's price")
                .isEqualByComparingTo("350.00");
    }

    // ── Append: masters.min_effective_price (search-facing denormalised column) ─
    //
    // min_effective_price (V58) is read by search/browse ordering, and appending is a BRAND-NEW
    // way to change it — under the old first-time-only precondition the column could only ever be
    // written once per master by this endpoint. refreshMinEffectivePrice recomputes it from
    // MIN(COALESCE(price_override, base_price)) over the master's ACTIVE services, so the append
    // must move the floor DOWN when the new service is cheaper and leave it alone when it is not.

    @Test
    @DisplayName("appending a CHEAPER service lowers masters.min_effective_price to the new floor")
    void should_lowerMinEffectivePrice_when_appendedServiceIsCheaper() throws Exception {
        String email = "indep-minprice-down-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        postSelfBulk(token, new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(0).id(), 60, "500.00"))));
        assertThat(fixtures.minEffectivePriceForMaster(masterId))
                .as("precondition: the first batch established 500.00 as the master's floor")
                .isEqualByComparingTo("500.00");

        log.debug("Act: append a 150.00 service to a catalogue whose current floor is 500.00");
        ResponseEntity<String> resp = postSelfBulk(token, new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(1).id(), 30, "150.00"))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(fixtures.minEffectivePriceForMaster(masterId))
                .as("the cheaper appended service becomes the new search-facing floor; a stale "
                        + "500.00 would rank this master above their real starting price")
                .isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("appending a MORE EXPENSIVE service leaves masters.min_effective_price at the existing floor")
    void should_keepMinEffectivePrice_when_appendedServiceIsMoreExpensive() throws Exception {
        String email = "indep-minprice-up-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        postSelfBulk(token, new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(0).id(), 60, "200.00"))));
        assertThat(fixtures.minEffectivePriceForMaster(masterId))
                .as("precondition: the first batch established 200.00 as the master's floor")
                .isEqualByComparingTo("200.00");

        log.debug("Act: append a 900.00 service to a catalogue whose current floor is 200.00");
        ResponseEntity<String> resp = postSelfBulk(token, new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(1).id(), 90, "900.00"))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(fixtures.minEffectivePriceForMaster(masterId))
                .as("min_effective_price is a MIN, not a last-write-wins column — the dearer "
                        + "append must not raise the master's advertised starting price")
                .isEqualByComparingTo("200.00");
    }

    /**
     * A RANGE service's {@code base_price} IS its {@code priceMin} (the canonical floor — locked
     * product decision), so appending a RANGE whose MIN undercuts the current floor must lower
     * {@code min_effective_price} to that min, never to its max. Distinct from the FIXED case
     * above: this is the branch where the column's input is the range floor, and getting it wrong
     * yields a master whose advertised "from" price is their most expensive range ceiling.
     */
    @Test
    @DisplayName("appending a RANGE service lowers masters.min_effective_price to its priceMin, not its priceMax")
    void should_useAppendedRangeFloor_when_appendedRangeMinUndercutsExistingFloor() throws Exception {
        String email = "indep-minprice-range-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        postSelfBulk(token, new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(0).id(), 60, "400.00"))));

        log.debug("Act: append a RANGE 250.00–1200.00 service to a catalogue whose floor is 400.00");
        ResponseEntity<String> resp = postSelfBulk(token, new BulkCreateServicesRequest(List.of(
                range(seededTypes.get(1).id(), 120, "250.00", "1200.00"))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(fixtures.minEffectivePriceForMaster(masterId))
                .as("RANGE base_price = priceMin, so the appended range's FLOOR (250.00) is the "
                        + "new minimum — picking priceMax would advertise 1200.00 instead")
                .isEqualByComparingTo("250.00");
    }

    // ── Append: masterServices cache eviction ──────────────────────────────────

    /**
     * {@code getMasterServices} is {@code @Cacheable("masterServices")} and backs the permitAll
     * browse route, so a catalogue read after the FIRST batch parks a one-entry list in the
     * cache. The append then has to evict it (after commit), otherwise a client browsing this
     * master keeps seeing the pre-append menu for the whole 10-minute TTL.
     *
     * <p>Asserted behaviourally, not by counting annotations: the cache entry is proven present
     * before the append, absent immediately after, and the next read returns BOTH services.
     */
    @Test
    @DisplayName("appending evicts the master's cached services list — the next browse sees both services")
    void should_evictMasterServicesCache_when_secondBatchAppends() throws Exception {
        String email = "indep-cache-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        postSelfBulk(token, new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(0).id(), 60, "350.00"))));

        // Populate the cache through the real @Cacheable proxy.
        assertThat(serviceCatalogService.getMasterServices(masterId))
                .as("precondition: the browse read returns the first batch's single service")
                .hasSize(1);
        var cache = cacheManager.getCache("masterServices");
        assertThat(cache).as("the masterServices cache must be configured for this test to mean anything").isNotNull();
        assertThat(cache.get(masterId))
                .as("precondition: the browse read populated the cache for this master")
                .isNotNull();

        log.debug("Act: append a second service while the master's browse list is sitting in the cache");
        ResponseEntity<String> resp = postSelfBulk(token, new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(1).id(), 90, "500.00"))));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(cache.get(masterId))
                .as("the append's afterCommit eviction must clear the stale one-service entry; "
                        + "without it a browsing client sees the pre-append menu until the TTL expires")
                .isNull();
        assertThat(serviceCatalogService.getMasterServices(masterId))
                .as("the repopulated browse list carries both the original and the appended service")
                .hasSize(2);
    }

    // ── Append: partial collision rolls the WHOLE second batch back ────────────

    /**
     * The append-specific rollback case, and the one the old first-time-only precondition made
     * unreachable: a second batch mixing a NEW service type with one the master ALREADY offers.
     * The whole second batch must be rejected 409 {@code DUPLICATE_SERVICE} — and, critically,
     * the FIRST batch must survive intact. A rollback that reached too far (or an append
     * implemented as replace-then-insert) would leave the master with a broken or empty menu
     * after a request that only ever should have been a no-op.
     *
     * <p>Unlike the existing duplicate tests, the collision here is with a NORMAL service created
     * through the endpoint (definition + active assignment), not a hand-seeded assignmentless
     * definition — i.e. the exact shape a real "add more services" mis-tap produces.
     */
    @Test
    @DisplayName("a second batch mixing a new type with an ALREADY-OWNED one is fully rejected 409 — the first batch survives intact")
    void should_rollBackWholeSecondBatch_when_appendPartiallyCollidesWithExistingCatalogue() throws Exception {
        List<ServiceTestFixtures.SeededServiceType> types = fixtures.activeSelectableServiceTypes(3);
        assertThat(types)
                .as("this test needs three selectable types: two for the first batch, one fresh "
                        + "type to pair with the colliding one in the second")
                .hasSize(3);

        String email = "indep-append-collide-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        ResponseEntity<String> first = postSelfBulk(token, new BulkCreateServicesRequest(List.of(
                fixed(types.get(0).id(), 60, "350.00"),
                fixed(types.get(1).id(), 45, "275.00"))));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<UUID> firstBatchDefIds = activeDefinitionIdsForMaster(masterId);
        UUID collidingDefId = createdFrom(first).stream()
                .filter(r -> r.serviceDefinition().name().equals(types.get(0).nameUk()))
                .findFirst().orElseThrow()
                .serviceDefinition().id();
        BigDecimal floorBeforeAppend = fixtures.minEffectivePriceForMaster(masterId);

        // Fresh type FIRST, already-owned type SECOND: a guard that stopped at the first item
        // would let the fresh one through.
        var secondRequest = new BulkCreateServicesRequest(List.of(
                fixed(types.get(2).id(), 90, "500.00"),
                fixed(types.get(0).id(), 30, "199.00")));

        log.debug("Act: append a 2-item batch whose second item re-requests a service type the master already offers");
        ResponseEntity<String> second = postSelfBulk(token, secondRequest);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicateBodyFrom(second))
                .extracting(DuplicateServiceResponse::code, DuplicateServiceResponse::existingServiceDefId)
                .as("the client branches on DUPLICATE_SERVICE and deep-links to the service it "
                        + "already owns — here the definition the FIRST batch created")
                .containsExactly("DUPLICATE_SERVICE", collidingDefId);

        assertThat(activeDefinitionIdsForMaster(masterId))
                .as("all-or-nothing across the append: the fresh item must NOT slip through, and "
                        + "the first batch's services must remain exactly as they were")
                .containsExactlyInAnyOrderElementsOf(firstBatchDefIds);
        assertThat(activeAssignmentCountForMaster(masterId))
                .as("no assignment leaks from the rejected batch either")
                .isEqualTo(2L);
        assertThat(fixtures.minEffectivePriceForMaster(masterId))
                .as("the rejected batch's cheaper 199.00 item must not move the search-facing "
                        + "floor — a floor written before the rollback would survive the rollback "
                        + "only if it escaped the transaction")
                .isEqualByComparingTo(floorBeforeAppend);
    }

    // ── Append parity: salon on-behalf entry point ─────────────────────────────

    /**
     * {@code bulkCreateSalonMasterServices} is the second entry point into the same additive core,
     * and it carries its own authorization prologue. Parity is not free: the removed precondition
     * lived in the shared core, but only the independent path had append coverage. This pins that
     * an owner can keep adding to a salon master's menu, and that the shared post-write bookkeeping
     * (min_effective_price) runs on this path too.
     */
    @Test
    @DisplayName("salon on-behalf bulk create is additive too — a second owner batch appends to the salon master's catalogue")
    void should_appendToSalonMasterCatalogue_when_ownerBulkCreatesTwice() throws Exception {
        String ownerEmail = "owner-append-" + System.nanoTime() + "@beautica.test";
        String ownerToken = fixtures.createSalonOwnerAndGetToken(ownerEmail);
        UUID salonId = fixtures.createSalon(ownerToken, "Append Bulk Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);

        ResponseEntity<String> first = postSalonBulk(ownerToken, salonId, masterId,
                new BulkCreateServicesRequest(List.of(fixed(seededTypes.get(0).id(), 60, "600.00"))));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID originalDefId = createdFrom(first).get(0).serviceDefinition().id();

        log.debug("Act: owner POSTs a SECOND on-behalf batch for the same salon master — must append, not 409");
        ResponseEntity<String> second = postSalonBulk(ownerToken, salonId, masterId,
                new BulkCreateServicesRequest(List.of(fixed(seededTypes.get(1).id(), 30, "180.00"))));

        assertThat(second.getStatusCode())
                .as("the on-behalf path shares the additive core — an existing catalogue no longer blocks it")
                .isEqualTo(HttpStatus.CREATED);
        UUID appendedDefId = createdFrom(second).get(0).serviceDefinition().id();

        assertThat(activeDefinitionIdsForMaster(masterId))
                .as("the salon master's menu keeps both batches")
                .containsExactlyInAnyOrder(originalDefId, appendedDefId);
        assertThat(fixtures.minEffectivePriceForMaster(masterId))
                .as("the shared post-write bookkeeping runs on the on-behalf path too — the "
                        + "cheaper appended service is the salon master's new floor")
                .isEqualByComparingTo("180.00");
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
        ResponseEntity<String> resp = postSelfBulk(token, request);

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
        ResponseEntity<String> resp = postSalonBulk(adminToken, salonId, masterId, request);

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
        ResponseEntity<String> resp = postSalonBulk(ownerAToken, salonAId, masterInSalonB, request);

        assertThat(resp.getStatusCode())
                .as("masterBelongsToSalon(masterInSalonB, salonA) is false → 403")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(fixtures.countServiceDefinitionsForMaster(masterInSalonB))
                .as("the denied request must not persist anything for the salon-B master")
                .isEqualTo(0L);
    }

    // ── V121 DUPLICATE_SERVICE on the bulk path, full stack ────────────────────
    //
    // Until these landed, the bulk path's V121 behaviour was proven ONLY by Mockito
    // (ServiceCatalogServiceBulkCreateTest), which stubs serviceRepository.flush() to throw. A
    // stub cannot prove that flush() emits the queued batch, that Hibernate reports THIS index,
    // that the exception survives GlobalExceptionHandler as a branchable body, or that the real
    // transaction rolls back — the four things a client actually depends on. This class was
    // untouched by the V121 branch.
    //
    // SCOPE, stated honestly: both tests below take the PRE-CHECK route
    // (assertNoActiveDuplicatesInBatch). The flush route inside flushTranslatingDuplicateViolation
    // is reachable only when a concurrent transaction wins the race between the pre-check and the
    // flush — and that window is exactly what pg_advisory_xact_lock (see the TOCTOU test below)
    // exists to close for this endpoint, so it has no deterministic full-stack trigger. It stays
    // mock-covered by design, not by omission.

    /**
     * The awkward state {@code ServiceCatalogService#assertNoActiveDuplicatesInBatch} exists to
     * catch: an ACTIVE definition owned by this master that carries no active assignment. It is
     * invisible in the master's menu, yet the definition-level V121 index still rejects a batch
     * re-requesting its service type. The guard turns that into a clean 409 instead of a 500 at
     * flush. It is reachable in production whenever an assignment is deactivated without its
     * definition (the two are separate rows with separate lifecycles).
     *
     * <p>The decisive assertion is not merely "409" but that the body carries
     * {@code code = DUPLICATE_SERVICE} <em>and</em> names the surviving definition in
     * {@code existingServiceDefId}. Those two fields are what the mobile client branches and
     * deep-links on, and they distinguish this guard from the flush-time index translation, which
     * cannot name the row it lost to (its {@code existingServiceDefId} is null).
     *
     * <p>Historical note: this endpoint used to have a second 409 source — a "master already has
     * services" precondition — and this test's original job was proving the two apart. Bulk create
     * is additive now, so {@code DUPLICATE_SERVICE} is the only 409 the endpoint emits; the
     * assertion is kept unchanged because it still pins the payload the client depends on.
     */
    @Test
    @DisplayName("bulk create returns 409 DUPLICATE_SERVICE naming the existing definition "
            + "when an item collides with an ACTIVE definition that carries no active assignment")
    void should_return409_when_bulkCollidesWithAnAssignmentlessActiveDefinition() throws Exception {
        String email = "indep-orphan-def-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        // An ACTIVE definition with NO master_services row at all: nothing shows in the master's
        // menu, yet the definition-level V121 index would still reject an insert for this type.
        UUID orphanDefId = insertActiveDefinitionWithoutAssignment(masterId, seededTypes.get(0).id());

        var request = new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(0).id(), 60, "350.00")));

        log.debug("Act: bulk setup for a master whose only existing service is an ACTIVE definition "
                + "with no active assignment, re-requesting that same service type");
        ResponseEntity<String> resp = postSelfBulk(token, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        assertThat(duplicateBodyFrom(resp))
                .extracting(DuplicateServiceResponse::code, DuplicateServiceResponse::existingServiceDefId)
                .as("a DUPLICATE_SERVICE code plus a named existingServiceDefId proves "
                        + "assertNoActiveDuplicatesInBatch caught this, not the flush-time index "
                        + "translation (which cannot name the surviving row, so its "
                        + "existingServiceDefId would be null)")
                .containsExactly("DUPLICATE_SERVICE", orphanDefId);

        assertThat(fixtures.countServiceDefinitionsForMaster(masterId))
                .as("only the pre-existing orphan definition remains — the rejected batch added nothing")
                .isEqualTo(1L);
    }

    /**
     * All-or-nothing across a REAL transaction when one item of a multi-item batch duplicates an
     * existing active definition: the valid items must not survive, and the client must still
     * receive the branchable {@code DUPLICATE_SERVICE} body rather than the generic 409.
     *
     * <p>The three-item shape matters. The single-item test above cannot distinguish "the batch
     * rolled back" from "there was nothing to roll back"; here two items are perfectly valid and
     * would persist if the transaction leaked. It also puts the colliding item LAST, so a
     * pre-check that stopped scanning the whole batch would let the first two through.
     */
    @Test
    @DisplayName("a bulk batch whose LAST item duplicates an existing active service returns 409 "
            + "DUPLICATE_SERVICE and persists ZERO of the batch's rows")
    void should_return409AndPersistNothing_when_oneBulkItemDuplicatesAnExistingService() throws Exception {
        List<ServiceTestFixtures.SeededServiceType> types = fixtures.activeSelectableServiceTypes(3);
        assertThat(types)
                .as("this test needs three distinct selectable service types to put the colliding "
                        + "item last behind two valid ones")
                .hasSize(3);

        String email = "indep-bulk-dup-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        // Assignmentless again, mirroring the test above; the property under test here is the
        // ROLLBACK, not the route into the duplicate check.
        UUID existingDefId = insertActiveDefinitionWithoutAssignment(masterId, types.get(2).id());

        var request = new BulkCreateServicesRequest(List.of(
                fixed(types.get(0).id(), 60, "350.00"),
                range(types.get(1).id(), 120, "800.00", "1500.00"),
                fixed(types.get(2).id(), 45, "275.00")));

        log.debug("Act: POST a 3-item bulk batch whose THIRD item duplicates an existing active "
                + "definition — expect 409 DUPLICATE_SERVICE and a full rollback");
        ResponseEntity<String> resp = postSelfBulk(token, request);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        var body = objectMapper.readValue(
                resp.getBody(), new TypeReference<ApiResponse<DuplicateServiceResponse>>() {});
        assertThat(body.success()).isFalse();
        assertThat(body.data())
                .extracting(DuplicateServiceResponse::code, DuplicateServiceResponse::existingServiceDefId)
                .as("the mobile setup screen branches on code and deep-links via existingServiceDefId; "
                        + "a generic DataIntegrityViolation 409 would carry neither")
                .containsExactly("DUPLICATE_SERVICE", existingDefId);

        assertThat(fixtures.countServiceDefinitionsForMaster(masterId))
                .as("all-or-nothing: the two VALID items must not survive the rejected batch — only "
                        + "the pre-existing definition remains")
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services ms JOIN service_definitions sd "
                        + "ON sd.id = ms.service_def_id WHERE sd.owner_id = ?", Long.class, masterId))
                .as("the assignments queued alongside the definitions must roll back too — the "
                        + "definition count alone would not catch a leaked master_services row")
                .isEqualTo(0L);
    }

    /**
     * Inserts an ACTIVE {@code service_definitions} row owned by {@code masterId} with NO
     * {@code master_services} assignment, via JDBC.
     *
     * <p>Direct SQL is required, not a bug: no endpoint can produce this state in one call, because
     * every create path writes the definition and its assignment together. It arises in production
     * over time — an assignment deactivated while its definition stays active — and it is precisely
     * the state where a master's visible menu disagrees with the definition-level V121 index.
     *
     * <p>{@code owner_type = 'INDEPENDENT_MASTER'} with {@code owner_id = masters.id} mirrors what
     * {@code bulkCreateForMaster} itself persists for BOTH entry points ("services are owned by the
     * master row regardless of how the master was created"), so the seeded row lands in the same
     * V121 key space the batch is about to insert into.
     */
    private UUID insertActiveDefinitionWithoutAssignment(UUID masterId, UUID serviceTypeId) {
        UUID defId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, price_type, base_price, buffer_minutes_after, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Orphaned Active Service', ?, 60, "
                        + "'FIXED', 400.00, 0, true, NOW(), NOW())",
                defId, masterId, serviceTypeId);
        return defId;
    }

    // ── TOCTOU concurrency regression (Step 2.7 Rule 3) ────────────────────────

    /**
     * True-concurrency regression for the {@code pg_advisory_xact_lock} TOCTOU guard.
     *
     * <p>The lock outlived the "first-time only" precondition it was originally added for, because
     * the hazard it closes is not that precondition but
     * {@code assertNoActiveDuplicatesInBatch} — still a read-then-write check. Two concurrent
     * batches naming the SAME service types can both read "this type is free" and both proceed;
     * without serialization one of them reaches the V121 index and the menu doubles or the loser
     * gets an unhelpful constraint error.
     *
     * <p>This test fires two {@link ServiceCatalogService#bulkCreateIndependentMasterServices}
     * calls on the Spring proxy — each runs in its OWN {@code @Transactional}, so each holds a
     * per-transaction advisory lock keyed by the master id. A {@link CyclicBarrier} releases both
     * threads into lock acquisition together (no {@code Thread.sleep}). The lock serializes them:
     * the winner commits its batch, and the loser — now running its duplicate guard against
     * COMMITTED rows — rejects with the clean 409 {@link DuplicateServiceException}.
     *
     * <p>Both assertions matter. The exception type proves the loser took the guarded path rather
     * than tripping the index at flush (which is what an unserialized race produces); the final DB
     * count proves exactly ONE batch survives. Calling the service bean (not HTTP) is what gives
     * each thread its own transaction-scoped lock with deterministic barrier coordination.
     */
    @Test
    @DisplayName("two concurrent bulk creates for one master serialize on the advisory lock — exactly one 201, one 409 DUPLICATE_SERVICE, NO menu doubling")
    void should_serializeAndRejectSecond_when_twoConcurrentBulkSetupsRaceForSameMaster() throws Exception {
        String email = "indep-race-" + System.nanoTime() + "@beautica.test";
        fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", UUID.class, email);

        // Each thread requests a DIFFERENT 2-item batch so that, whichever wins, the survivor's
        // batch size (2) is unambiguous — a doubled menu would yield 4 rows.
        var batchA = new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(0).id(), 60, "350.00"),
                range(seededTypes.get(1).id(), 120, "800.00", "1500.00")));
        var batchB = new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(0).id(), 45, "275.00"),
                range(seededTypes.get(1).id(), 90, "600.00", "1200.00")));

        CyclicBarrier startLine = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            log.debug("Act: two threads call bulkCreateIndependentMasterServices for the same master, released together by a barrier");
            Future<Throwable> a = pool.submit(() -> attemptBulk(startLine, userId, batchA, successCount, conflictCount));
            Future<Throwable> b = pool.submit(() -> attemptBulk(startLine, userId, batchB, successCount, conflictCount));

            Throwable errA = a.get(30, TimeUnit.SECONDS);
            Throwable errB = b.get(30, TimeUnit.SECONDS);

            // Whichever thread lost must have failed with a 409 DUPLICATE_SERVICE — never a
            // deadlock, lock-timeout or raw DataIntegrityViolation.
            //
            // The decisive field is existingServiceDefId. It is NON-NULL only on the pre-check
            // route (assertNoActiveDuplicatesInBatch, which knows which row it collided with) and
            // NULL on the flush route (the V121 index reports a constraint, not a row). Remove the
            // advisory lock and this test still sees one success + one CONFLICT + two rows — the
            // unique index alone delivers that — but the loser arrives via the flush and its
            // existingServiceDefId is null. So this assertion, and only this assertion, is what
            // proves the lock actually serialized the two transactions.
            assertThat(List.of(java.util.Optional.ofNullable(errA), java.util.Optional.ofNullable(errB)))
                    .filteredOn(java.util.Optional::isPresent)
                    .extracting(java.util.Optional::get)
                    .as("the only allowed failure is the guarded 409 DUPLICATE_SERVICE; got %s / %s", errA, errB)
                    .allSatisfy(t -> assertThat(t)
                            .isInstanceOf(DuplicateServiceException.class)
                            .satisfies(ex -> {
                                assertThat(((BusinessException) ex).getStatus()).isEqualTo(HttpStatus.CONFLICT);
                                assertThat(((DuplicateServiceException) ex).getExistingServiceDefId())
                                        .as("the loser must reach the duplicate PRE-CHECK against the "
                                                + "winner's committed rows (named row), not the flush-time "
                                                + "index translation (null row) — i.e. the lock serialized them")
                                        .isNotNull();
                            }));
        } finally {
            pool.shutdownNow();
        }

        assertThat(successCount.get())
                .as("exactly one concurrent caller wins the lock and commits its batch")
                .isEqualTo(1);
        assertThat(conflictCount.get())
                .as("the loser's duplicate guard, running under the lock against the winner's "
                        + "COMMITTED rows, gets a clean 409")
                .isEqualTo(1);
        assertThat(fixtures.countServiceDefinitionsForMaster(masterId))
                .as("the decisive guard: only ONE batch (2 rows) survives — the race can no longer double the menu")
                .isEqualTo(2L);
    }

    /**
     * Runs one bulk-setup attempt after meeting the other thread at the barrier. Returns the
     * thrown exception (or {@code null} on success) instead of letting it escape, so the test
     * thread can assert on BOTH outcomes deterministically. Bumps the matching counter.
     */
    private Throwable attemptBulk(CyclicBarrier startLine, UUID userId,
                                  BulkCreateServicesRequest request,
                                  AtomicInteger successCount, AtomicInteger conflictCount) {
        try {
            startLine.await(10, TimeUnit.SECONDS); // both threads cross together — no sleep
            serviceCatalogService.bulkCreateIndependentMasterServices(userId, request);
            successCount.incrementAndGet();
            return null;
        } catch (BusinessException e) {
            if (e.getStatus() == HttpStatus.CONFLICT) {
                conflictCount.incrementAndGet();
            }
            return e;
        } catch (Throwable t) {
            return t;
        }
    }
}
