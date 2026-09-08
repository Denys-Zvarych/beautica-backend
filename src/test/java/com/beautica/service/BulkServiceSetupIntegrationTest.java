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
import com.beautica.service.dto.SalonServiceCatalogResponse;
import com.beautica.service.dto.ServicePriceShapeMismatchResponse;
import com.beautica.service.entity.PriceType;
import com.beautica.service.service.ServiceCatalogService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManagerFactory;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
    /** Source of the Hibernate {@link Statistics} the batch-size statement gate counts on. */
    @Autowired private EntityManagerFactory emf;

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

    private ServicePriceShapeMismatchResponse shapeMismatchBodyFrom(ResponseEntity<String> resp)
            throws Exception {
        return objectMapper.readValue(
                resp.getBody(),
                new TypeReference<ApiResponse<ServicePriceShapeMismatchResponse>>() {}).data();
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

        assertThat(fixtures.activeDefinitionIdsAssignedToMaster(masterId))
                .as("the salon master's menu keeps both batches — resolved through master_services, "
                        + "because since Phase 302 the definitions are SALON-owned, not master-owned")
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

    /**
     * Pins the SALON_ADMIN boundary on the bulk endpoint as a DECIDED contract, not an accident
     * (phase-302 audit MEDIUM-5).
     *
     * <p><b>Why it needs pinning.</b> {@code ServiceController#bulkCreateMasterServices} gates on
     * {@code @authz.canManageSalon}, which admits SALON_ADMIN, while the sibling
     * {@code POST /salons/&#123;salonId&#125;/services} gates on {@code hasRole('SALON_OWNER')}.
     * Since Phase 302 D1 the bulk endpoint writes exactly the {@code (SALON, salonId)} rows the
     * sibling restricts, so an admin can now create salon-owned, publicly visible catalogue
     * definitions. Read cold, that looks like an authorization widening smuggled in by a data
     * -ownership change.
     *
     * <p><b>It is intended.</b> The locked product requirement is "the salon owner/admin only can
     * set the services", and on <b>2026-09-08</b> the user explicitly chose FULL owner/admin parity
     * for service management — recorded in the resolved-decision block of
     * {@code docs/backend-phases/phase-306-salon-admin-parity-on-service-management.md}. Phase 306
     * aligns the sibling endpoints ({@code PATCH}/{@code DELETE}/single-assign); it is not this
     * phase's job, and this test must NOT be "fixed" by tightening the guard.
     *
     * <p>Parity is per SALON, never global: the sibling test below proves an admin of a DIFFERENT
     * salon is still refused, so what phase 306 widens is the role, not the tenancy boundary.
     */
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
        assertThat(fixtures.countServiceDefinitionsForOwner("SALON", salonId))
                .as("the on-behalf batch is persisted SALON-owned (Phase 302 D1)")
                .isEqualTo(1L);
        assertThat(fixtures.activeDefinitionIdsAssignedToMaster(masterId))
                .as("and the master performs it via a master_services assignment")
                .hasSize(1);
    }

    /**
     * The tenancy half of the SALON_ADMIN decision pinned above (phase-302 audit MEDIUM-5): the
     * 2026-09-08 owner/admin parity decision widens the ROLE, never the salon boundary.
     *
     * <p>An admin of salon B addressing salon A's bulk path must be refused by
     * {@code canManageSalon(auth, salonAId)} before {@code masterBelongsToSalon} is even reached —
     * so an admin cannot mint {@code (SALON, salonAId)} catalogue rows in a salon they do not
     * administer. Without this, "SALON_ADMIN may bulk-create" would be pinned as an unqualified
     * capability, and a future SpEL edit that dropped the salon argument would keep the sibling
     * allow-test green.
     */
    @Test
    @DisplayName("SALON_ADMIN of ANOTHER salon cannot bulk-create in this salon — 403, nothing persisted")
    void should_denySalonAdminOnBehalf_when_adminBelongsToADifferentSalon() throws Exception {
        String ownerAToken = fixtures.createSalonOwnerAndGetToken(
                "owner-302-adminidor-" + System.nanoTime() + "@beautica.test");
        UUID salonAId = fixtures.createSalon(ownerAToken, "Phase 302 Admin IDOR Target");
        UUID masterInSalonA = fixtures.createSalonMaster(salonAId);

        String ownerBToken = fixtures.createSalonOwnerAndGetToken(
                "owner-302-adminidor-b-" + System.nanoTime() + "@beautica.test");
        UUID salonBId = fixtures.createSalon(ownerBToken, "Phase 302 Admin IDOR Home");
        String foreignAdminToken = fixtures.createSalonAdminAndGetToken(
                salonBId, "admin-302-idor-" + System.nanoTime() + "@beautica.test");

        var request = new BulkCreateServicesRequest(List.of(
                fixed(seededTypes.get(0).id(), 60, "350.00")));

        log.debug("Act: SALON_ADMIN of salon {} POSTs the bulk path of salon {} — must be 403",
                salonBId, salonAId);
        ResponseEntity<String> resp = postSalonBulk(foreignAdminToken, salonAId, masterInSalonA, request);

        assertThat(resp.getStatusCode())
                .as("owner/admin parity (user decision 2026-09-08, phase 306) is scoped to the "
                        + "admin's OWN salon — canManageSalon(auth, salonA) is false here. Body: %s",
                        resp.getBody())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(countActiveDefinitionsForOwner("SALON", salonAId))
                .as("the denied request must not create a salon-A catalogue definition")
                .isZero();
        assertThat(fixtures.activeDefinitionIdsAssignedToMaster(masterInSalonA))
                .as("nor an assignment for salon A's master")
                .isEmpty();
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
     * {@code bulkCreateForMaster} persists on the INDEPENDENT_MASTER branch — the only branch whose
     * callers use this helper — so the seeded row lands in the same V121 key space the batch is
     * about to insert into. Since Phase 302 D1 the SALON branch persists {@code (SALON, salonId)}
     * instead; a salon-arm variant of this fixture would have to seed that key space.
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

    // ══ Phase 302 — a salon master's services are SALON-owned and REUSE the salon's definition ══
    //
    // The defect these pin: bulkCreateSalonMasterServices used to persist
    // (INDEPENDENT_MASTER, master.id), while findBookableAssignmentsBySalon admits only
    // (SALON, salonId). Every service created through this endpoint was therefore permanently
    // invisible in GET /salons/{salonId}/services. Reuse is not tidiness either — V121's
    // ux_service_def_owner_service_type_active makes a per-master duplicate physically impossible
    // once the owner is the salon.
    //
    // Salon-catalogue cache eviction on these write paths is Phase 304, NOT this phase, so every
    // catalogue read below explicitly clears the 60s salon-service-catalog entry first. That is a
    // documented gap being worked around, not an assertion crutch.

    private static final String SALON_CATALOG_CACHE = "salon-service-catalog";

    /** Drops the 60s salon-catalogue cache entry — see the Phase 304 note above. */
    private void evictSalonCatalogue(UUID salonId) {
        var cache = cacheManager.getCache(SALON_CATALOG_CACHE);
        if (cache != null) {
            cache.evict(salonId);
        }
    }

    /** GETs the public salon catalogue, bypassing the Phase 304 cache gap. */
    private SalonServiceCatalogResponse getSalonCatalogue(UUID salonId) throws Exception {
        evictSalonCatalogue(salonId);
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/salons/" + salonId + "/services", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(
                resp.getBody(),
                new TypeReference<ApiResponse<SalonServiceCatalogResponse>>() {}).data();
    }

    private List<UUID> catalogueServiceIds(UUID salonId) throws Exception {
        return getSalonCatalogue(salonId).categories().stream()
                .flatMap(group -> group.services().stream())
                .map(com.beautica.service.dto.ServiceDefinitionResponse::id)
                .toList();
    }

    /** One row of {@code service_definitions}, read raw so assertions pin the table, not a DTO. */
    private java.util.Map<String, Object> definitionRow(UUID defId) {
        return jdbcTemplate.queryForMap(
                "SELECT owner_type, owner_id, name, base_price, base_duration_minutes, price_type "
                        + "FROM service_definitions WHERE id = ?", defId);
    }

    /**
     * The master's PUBLIC menu, read over the wire from {@code GET /masters/{id}/services} — the
     * surface a client actually renders a price from.
     *
     * <p>Re-audit cycle 2: the shape-mismatch guard exists because a reshaped reuse shipped a wrong
     * CLIENT-FACING price ({@code FIXED 600} reusing a {@code RANGE 400–900} definition rendered
     * {@code 600–900}). Every other assertion on that path pins an HTTP status, a
     * {@code master_services} column or the create-response — none of which is the band a client
     * sees. {@code priceType}/{@code priceMin}/{@code priceMax} come off the SHARED definition
     * while {@code effectivePrice} comes off the per-master override, so the rendered band is a
     * COMBINATION of the two rows and can be wrong even when both rows are individually correct.
     * That combination is only observable here.
     */
    private List<MasterServiceResponse> publicMenuOf(UUID masterId) throws Exception {
        ResponseEntity<String> resp = restTemplate.getForEntity(
                "/api/v1/masters/" + masterId + "/services", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(
                resp.getBody(),
                new TypeReference<ApiResponse<List<MasterServiceResponse>>>() {}).data();
    }

    private long countActiveDefinitionsForOwner(String ownerType, UUID ownerId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM service_definitions "
                        + "WHERE owner_type = ? AND owner_id = ? AND is_active = TRUE",
                Long.class, ownerType, ownerId);
        return count == null ? 0L : count;
    }

    /** Case 1 + 2 — the whole point of the phase. */
    @Test
    @DisplayName("salon master's bulk-created services appear in GET /salons/{salonId}/services, "
            + "persisted owner_type='SALON' + owner_id=salonId (D1)")
    void should_appearInSalonCatalogueAsSalonOwned_when_ownerBulkCreatesForSalonMaster() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-302-visible-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 302 Visible Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);
        // Without a schedule the free-slot gate hides everything — deliberate contract (Phase 305 D1).
        fixtures.seedUsableSchedule(masterId);

        log.debug("Act: owner bulk-creates two services for a salon master");
        ResponseEntity<String> resp = postSalonBulk(ownerToken, salonId, masterId,
                new BulkCreateServicesRequest(List.of(
                        fixed(seededTypes.get(0).id(), 60, "350.00"),
                        range(seededTypes.get(1).id(), 90, "800.00", "1500.00"))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<UUID> createdDefIds = createdFrom(resp).stream()
                .map(r -> r.serviceDefinition().id()).toList();
        assertThat(createdDefIds).hasSize(2);

        // Case 1 FIRST, deliberately. This is the product claim, and reverting D1 must report it
        // as the symptom a user would see — an EMPTY catalogue, not a 500 and not the
        // implementation-level ownership assertion below, which would otherwise shadow it.
        assertThat(catalogueServiceIds(salonId))
                .as("both bulk-created services are visible in the salon's public catalogue")
                .containsExactlyInAnyOrderElementsOf(createdDefIds);

        // Case 2 — assert the ROW, not the response: ownership is what the catalogue query joins on.
        for (UUID defId : createdDefIds) {
            assertThat(definitionRow(defId))
                    .as("a salon-bound master's definition is SALON-owned, keyed on the salon id")
                    .containsEntry("owner_type", "SALON")
                    .containsEntry("owner_id", salonId);
        }
    }

    /** Cases 3, 4 and 5 — reuse, no duplicate row, no mutation of the shared definition. */
    @Test
    @DisplayName("a second master in the same salon offering the SAME type reuses the one definition — "
            + "no new row, catalogue lists it once, the shared row is unchanged (D2/D3)")
    void should_reuseOneDefinition_when_twoMastersInASalonOfferTheSameServiceType() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-302-reuse-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 302 Reuse Salon");
        UUID firstMasterId = fixtures.createSalonMaster(salonId);
        UUID secondMasterId = fixtures.createSalonMaster(salonId);
        fixtures.seedUsableSchedule(firstMasterId);
        fixtures.seedUsableSchedule(secondMasterId);

        UUID typeId = seededTypes.get(0).id();

        ResponseEntity<String> first = postSalonBulk(ownerToken, salonId, firstMasterId,
                new BulkCreateServicesRequest(List.of(fixed(typeId, 60, "350.00"))));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID sharedDefId = createdFrom(first).get(0).serviceDefinition().id();

        // Case 5's "before" snapshot, taken from the table.
        java.util.Map<String, Object> before = definitionRow(sharedDefId);

        // The second master deliberately names a DIFFERENT price. A reuse path that copied the
        // batch item onto the found definition would look correct on ids and counts while silently
        // repricing the first master's service — the multi-master data loss D3 exists to prevent —
        // so case 5's byte-identical row assertion below is what catches it.
        log.debug("Act: a SECOND master in the same salon bulk-creates the SAME service type — "
                + "must reuse, because V121 forbids a second active (SALON, salonId, type) row");
        ResponseEntity<String> second = postSalonBulk(ownerToken, salonId, secondMasterId,
                new BulkCreateServicesRequest(List.of(fixed(typeId, 60, "420.00"))));

        assertThat(second.getStatusCode())
                .as("the salon already offering the type is the REUSE path, not a 409 (D4)")
                .isEqualTo(HttpStatus.CREATED);
        assertThat(createdFrom(second).get(0).serviceDefinition().id())
                .as("the second master's assignment points at the SAME definition")
                .isEqualTo(sharedDefId);

        // Case 3 — service_definitions gained no row.
        assertThat(countActiveDefinitionsForOwner("SALON", salonId))
                .as("one definition, two masters — a second row would have violated "
                        + "ux_service_def_owner_service_type_active")
                .isEqualTo(1L);
        assertThat(fixtures.activeDefinitionIdsAssignedToMaster(firstMasterId))
                .containsExactly(sharedDefId);
        assertThat(fixtures.activeDefinitionIdsAssignedToMaster(secondMasterId))
                .containsExactly(sharedDefId);

        // Case 4 — the catalogue lists it once, not twice.
        assertThat(catalogueServiceIds(salonId))
                .as("the catalogue is keyed by definition, so two performing masters list it ONCE")
                .containsExactly(sharedDefId);

        // Case 5 — the shared row is byte-identical.
        assertThat(definitionRow(sharedDefId))
                .as("reuse must never rewrite the salon's shared definition")
                .isEqualTo(before);
    }

    /** Case 6 — divergence lands on the assignment, never on the shared definition. */
    @Test
    @DisplayName("reuse with a DIFFERENT price writes master_services.price_override and leaves the "
            + "shared definition untouched (D3)")
    void should_writePriceOverride_when_reusingMasterPricesDifferently() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-302-override-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 302 Override Salon");
        UUID firstMasterId = fixtures.createSalonMaster(salonId);
        UUID secondMasterId = fixtures.createSalonMaster(salonId);

        UUID typeId = seededTypes.get(0).id();

        ResponseEntity<String> first = postSalonBulk(ownerToken, salonId, firstMasterId,
                new BulkCreateServicesRequest(List.of(fixed(typeId, 60, "350.00"))));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID sharedDefId = createdFrom(first).get(0).serviceDefinition().id();
        java.util.Map<String, Object> before = definitionRow(sharedDefId);

        log.debug("Act: the second master takes the same type at a different price and duration");
        ResponseEntity<String> second = postSalonBulk(ownerToken, salonId, secondMasterId,
                new BulkCreateServicesRequest(List.of(fixed(typeId, 90, "420.00"))));
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        java.util.Map<String, Object> assignment = jdbcTemplate.queryForMap(
                "SELECT price_override, duration_override_minutes FROM master_services "
                        + "WHERE master_id = ? AND service_def_id = ?", secondMasterId, sharedDefId);
        assertThat((BigDecimal) assignment.get("price_override"))
                .as("the diverging price is a PER-MASTER fact — master_services.price_override")
                .isEqualByComparingTo("420.00");
        assertThat(assignment.get("duration_override_minutes"))
                .as("and so is the diverging duration")
                .isEqualTo(90);

        assertThat(definitionRow(sharedDefId))
                .as("the shared definition keeps the salon's own price and duration — rewriting "
                        + "them would change what the FIRST master offers")
                .isEqualTo(before);

        java.util.Map<String, Object> firstAssignment = jdbcTemplate.queryForMap(
                "SELECT price_override, duration_override_minutes FROM master_services "
                        + "WHERE master_id = ? AND service_def_id = ?", firstMasterId, sharedDefId);
        assertThat(firstAssignment.get("price_override"))
                .as("the creating master matched the definition, so it carries no override")
                .isNull();
        assertThat(firstAssignment.get("duration_override_minutes")).isNull();
    }

    /**
     * ── Re-audit MEDIUM-1 — the reuse branch REJECTS an unrepresentable price shape ──
     *
     * <p>{@code master_services} carries a {@code price_override} (a FLOOR) and a
     * {@code duration_override_minutes}; it has NO per-master price type and NO per-master
     * ceiling. So when a batch item's price shape disagrees with the salon definition it reuses,
     * the difference has nowhere faithful to land, and the reuse branch — which returns before
     * {@code applyPriceMode} — used to answer {@code 201} with a body that did not match the
     * request, shipping a wrong CLIENT-FACING price:
     *
     * <pre>
     *   FIXED 500     definition + RANGE 400–900 item → stored FIXED 400   (band discarded)
     *   RANGE 400–900 definition + FIXED 600     item → renders 600–900    (ceiling nobody set)
     *   RANGE 400–900 definition + RANGE 500–800 item → renders 500–900    (ceiling 800 dropped)
     * </pre>
     *
     * <p>Phase 302 D3 routed diverging price/duration VALUES to the overrides and said NOTHING
     * about shape, so this was undecided, not an accepted trade-off. All three rows are exercised
     * here end-to-end, over the wire, against the real database — the unit test can prove the
     * guard fires, only this can prove the transaction leaves nothing behind.
     *
     * <p>Rejecting breaks no existing caller: the reuse branch is new in Phase 302 and has never
     * shipped, so no client has ever seen the reshaped 201.
     */
    @Test
    @DisplayName("a reused item whose price SHAPE differs from the salon definition → 400 "
            + "SERVICE_PRICE_SHAPE_MISMATCH, nothing persisted (re-audit MEDIUM-1)")
    void should_return400_when_reusingItemPriceShapeDiffersFromSalonDefinition() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-302-shape-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 302 Shape Salon");
        UUID firstMasterId = fixtures.createSalonMaster(salonId);
        UUID secondMasterId = fixtures.createSalonMaster(salonId);

        UUID fixedTypeId = seededTypes.get(0).id();
        UUID rangeTypeId = seededTypes.get(1).id();

        // The first master mints the salon's governing definitions: one FIXED 500, one RANGE 400–900.
        ResponseEntity<String> seed = postSalonBulk(ownerToken, salonId, firstMasterId,
                new BulkCreateServicesRequest(List.of(
                        fixed(fixedTypeId, 60, "500.00"),
                        range(rangeTypeId, 60, "400.00", "900.00"))));
        assertThat(seed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(countActiveDefinitionsForOwner("SALON", salonId)).isEqualTo(2L);

        log.debug("Act row 1: RANGE 400–900 item against the salon's FIXED 500 definition");
        ResponseEntity<String> rangeOverFixed = postSalonBulk(ownerToken, salonId, secondMasterId,
                new BulkCreateServicesRequest(List.of(range(fixedTypeId, 60, "400.00", "900.00"))));

        assertThat(rangeOverFixed.getStatusCode())
                .as("a band cannot be stored as a floor — refuse rather than flatten it to FIXED 400")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ServicePriceShapeMismatchResponse body1 = shapeMismatchBodyFrom(rangeOverFixed);
        assertThat(body1.code())
                .as("a machine-readable code, not handleBusiness's payload-less \"Invalid request\"")
                .isEqualTo("SERVICE_PRICE_SHAPE_MISMATCH");
        assertThat(body1.salonPriceType())
                .as("the payload names the SALON's governing shape so the owner can be told why")
                .isEqualTo(PriceType.FIXED);
        assertThat(body1.salonPriceMin()).isEqualByComparingTo("500.00");
        assertThat(body1.salonPriceMax()).as("a FIXED definition has no ceiling").isNull();
        assertThat(body1.existingServiceDefId()).as("deep-linkable to the salon's row").isNotNull();

        log.debug("Act row 2: FIXED 600 item against the salon's RANGE 400–900 definition");
        ResponseEntity<String> fixedOverRange = postSalonBulk(ownerToken, salonId, secondMasterId,
                new BulkCreateServicesRequest(List.of(fixed(rangeTypeId, 60, "600.00"))));

        assertThat(fixedOverRange.getStatusCode())
                .as("600 would have rendered 600–900 — a public ceiling nobody set for this master")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ServicePriceShapeMismatchResponse body2 = shapeMismatchBodyFrom(fixedOverRange);
        assertThat(body2.code()).isEqualTo("SERVICE_PRICE_SHAPE_MISMATCH");
        assertThat(body2.salonPriceType()).isEqualTo(PriceType.RANGE);
        assertThat(body2.salonPriceMin()).isEqualByComparingTo("400.00");
        assertThat(body2.salonPriceMax()).isEqualByComparingTo("900.00");

        log.debug("Act row 3: RANGE 500–800 item against the salon's RANGE 400–900 definition");
        ResponseEntity<String> narrowerBand = postSalonBulk(ownerToken, salonId, secondMasterId,
                new BulkCreateServicesRequest(List.of(range(rangeTypeId, 60, "500.00", "800.00"))));

        assertThat(narrowerBand.getStatusCode())
                .as("only the ceiling differs — the case a floor-only comparison waves through, "
                        + "and it would have rendered 500–900")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(shapeMismatchBodyFrom(narrowerBand).salonPriceMax()).isEqualByComparingTo("900.00");

        // ── nothing persisted, by any of the three rejected calls ──
        assertThat(activeAssignmentCountForMaster(secondMasterId))
                .as("the rejected batches leave the second master with no assignment at all")
                .isZero();
        assertThat(countActiveDefinitionsForOwner("SALON", salonId))
                .as("and mint no definition — the salon still has exactly the two it started with")
                .isEqualTo(2L);

        // ── and nothing was PUBLISHED (re-audit cycle 2) ──
        // The status assertions above prove the guard fires; this proves the consequence the guard
        // exists for. Without the guard, row 2 stores a 600 price_override against the salon's
        // RANGE 400–900 definition and this endpoint renders the master at 600–900 — a public
        // ceiling nobody set. That band is a COMBINATION of the assignment's override and the
        // shared definition's price_max, so neither the DB assertions above nor the create-response
        // can see it; only the rendered menu can.
        assertThat(publicMenuOf(secondMasterId))
                .as("a refused shape must publish NOTHING — not a silently reshaped band on the "
                        + "master's public menu")
                .isEmpty();
    }

    /**
     * The accept side of the same guard, so it cannot over-reject the two shapes that ARE
     * representable: a FIXED item at any amount against a FIXED definition (the amount IS the
     * floor), and a RANGE item whose ceiling MATCHES the salon band (only the floor differs, and
     * the floor is exactly what {@code price_override} stores).
     *
     * <p>Without this test the MEDIUM-1 guard could be tightened to "reject any divergence at all"
     * and every rejection test above would still pass — while the phase's whole point, per-master
     * price divergence, silently stopped working.
     */
    @Test
    @DisplayName("a reused item whose price shape MATCHES the salon definition is accepted — the "
            + "floor lands on price_override, the shared definition is untouched")
    void should_accept_when_reusedItemPriceShapeMatchesSalonDefinition() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-302-shape-ok-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 302 Shape OK Salon");
        UUID firstMasterId = fixtures.createSalonMaster(salonId);
        UUID secondMasterId = fixtures.createSalonMaster(salonId);

        UUID fixedTypeId = seededTypes.get(0).id();
        UUID rangeTypeId = seededTypes.get(1).id();

        ResponseEntity<String> seed = postSalonBulk(ownerToken, salonId, firstMasterId,
                new BulkCreateServicesRequest(List.of(
                        fixed(fixedTypeId, 60, "500.00"),
                        range(rangeTypeId, 60, "400.00", "900.00"))));
        assertThat(seed.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        List<MasterServiceResponse> seeded = createdFrom(seed);
        UUID fixedDefId = seeded.get(0).serviceDefinition().id();
        UUID rangeDefId = seeded.get(1).serviceDefinition().id();
        java.util.Map<String, Object> fixedBefore = definitionRow(fixedDefId);
        java.util.Map<String, Object> rangeBefore = definitionRow(rangeDefId);

        log.debug("Act: FIXED 600 over FIXED 500, and RANGE 500–900 over RANGE 400–900 — both "
                + "representable as a price_override floor");
        ResponseEntity<String> accepted = postSalonBulk(ownerToken, salonId, secondMasterId,
                new BulkCreateServicesRequest(List.of(
                        fixed(fixedTypeId, 60, "600.00"),
                        range(rangeTypeId, 60, "500.00", "900.00"))));

        assertThat(accepted.getStatusCode())
                .as("the guard must refuse only what the assignment cannot store")
                .isEqualTo(HttpStatus.CREATED);

        assertThat((BigDecimal) jdbcTemplate.queryForMap(
                "SELECT price_override FROM master_services WHERE master_id = ? AND service_def_id = ?",
                secondMasterId, fixedDefId).get("price_override"))
                .as("FIXED against FIXED: any amount rides on price_override")
                .isEqualByComparingTo("600.00");
        assertThat((BigDecimal) jdbcTemplate.queryForMap(
                "SELECT price_override FROM master_services WHERE master_id = ? AND service_def_id = ?",
                secondMasterId, rangeDefId).get("price_override"))
                .as("RANGE with a matching ceiling: the floor rides on price_override, and the "
                        + "ceiling is already what the salon band says")
                .isEqualByComparingTo("500.00");

        assertThat(definitionRow(fixedDefId))
                .as("neither shared definition is rewritten by the accepted reuse")
                .isEqualTo(fixedBefore);
        assertThat(definitionRow(rangeDefId)).isEqualTo(rangeBefore);
        assertThat(countActiveDefinitionsForOwner("SALON", salonId))
                .as("reuse mints no definition")
                .isEqualTo(2L);

        // ── the RENDERED band is what was submitted (re-audit cycle 2) ──
        // The whole point of the MEDIUM-1 guard is that a reused row's PUBLIC price must equal the
        // request. The band a client sees is a COMBINATION of two rows — priceType/priceMax come
        // off the SHARED definition while effectivePrice comes off the per-master override — so it
        // is invisible to the master_services and service_definitions assertions above and to the
        // create-response. It is asserted here, on the permitAll browse route, or nowhere.
        // (priceDisplay/priceMin are deliberately NOT asserted: ServicePricing.derive ignoring
        // price_override is a separately backlogged defect, not this phase's contract.)
        List<MasterServiceResponse> menu = publicMenuOf(secondMasterId);
        assertThat(menu).hasSize(2);
        MasterServiceResponse renderedFixed = menu.stream()
                .filter(row -> row.serviceDefinition().id().equals(fixedDefId))
                .findFirst().orElseThrow();
        MasterServiceResponse renderedRange = menu.stream()
                .filter(row -> row.serviceDefinition().id().equals(rangeDefId))
                .findFirst().orElseThrow();

        assertThat(renderedFixed)
                .as("FIXED 600 was submitted, so the master must render as a single price — a "
                        + "ceiling appearing here is the exact defect the shape guard exists for")
                .extracting(MasterServiceResponse::priceType, MasterServiceResponse::priceMax)
                .containsExactly(PriceType.FIXED, null);
        assertThat(renderedFixed.effectivePrice())
                .as("and at the master's own 600, not the salon definition's 500")
                .isEqualByComparingTo("600.00");

        assertThat(renderedRange)
                .as("RANGE 500–900 was submitted, and 900 is exactly the salon band's ceiling")
                .extracting(MasterServiceResponse::priceType)
                .isEqualTo(PriceType.RANGE);
        assertThat(renderedRange.priceMax()).isEqualByComparingTo("900.00");
        assertThat(renderedRange.effectivePrice())
                .as("the floor rendered is the master's own 500, not the salon's 400")
                .isEqualByComparingTo("500.00");
    }

    /** Case 7 — the 409 that survives, re-scoped to the master. */
    @Test
    @DisplayName("the SAME master re-submitting a service type they already offer → 409 DUPLICATE_SERVICE (D4)")
    void should_return409_when_salonMasterResubmitsAServiceTypeTheyAlreadyOffer() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-302-dup-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 302 Duplicate Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);

        UUID typeId = seededTypes.get(0).id();

        ResponseEntity<String> first = postSalonBulk(ownerToken, salonId, masterId,
                new BulkCreateServicesRequest(List.of(fixed(typeId, 60, "350.00"))));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID defId = createdFrom(first).get(0).serviceDefinition().id();

        log.debug("Act: the SAME master re-submits the same service type — expect 409, not a reuse");
        ResponseEntity<String> second = postSalonBulk(ownerToken, salonId, masterId,
                new BulkCreateServicesRequest(List.of(fixed(typeId, 45, "400.00"))));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(duplicateBodyFrom(second))
                .extracting(DuplicateServiceResponse::code, DuplicateServiceResponse::existingServiceDefId)
                .as("the per-master conflict still carries a branchable code and a deep-linkable id")
                .containsExactly("DUPLICATE_SERVICE", defId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM master_services WHERE master_id = ?", Long.class, masterId))
                .as("no second assignment leaks from the rejected batch")
                .isEqualTo(1L);
    }

    /** Case 8 — the retained INDEPENDENT_MASTER branch is byte-identical. */
    @Test
    @DisplayName("independent-master bulk create still persists owner_type='INDEPENDENT_MASTER' "
            + "with owner_id=master.id — regression (D1's retained branch)")
    void should_stayMasterOwned_when_independentMasterBulkCreates() throws Exception {
        String email = "indep-302-regression-" + System.nanoTime() + "@beautica.test";
        String token = fixtures.createIndependentMasterAndGetToken(email);
        UUID masterId = fixtures.resolveMasterIdForUserEmail(email);

        log.debug("Act: independent master bulk-creates on their own behalf");
        ResponseEntity<String> resp = postSelfBulk(token,
                new BulkCreateServicesRequest(List.of(fixed(seededTypes.get(0).id(), 60, "350.00"))));

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID defId = createdFrom(resp).get(0).serviceDefinition().id();

        assertThat(definitionRow(defId))
                .as("an independent master has salon_id IS NULL — nothing about their ownership moves")
                .containsEntry("owner_type", "INDEPENDENT_MASTER")
                .containsEntry("owner_id", masterId);
    }

    /** Case 9 — owner-as-master takes the salon branch with no special-casing (D5). */
    @Test
    @DisplayName("an owner-as-master row's bulk-created services appear in that owner's OWN salon catalogue (D5)")
    void should_appearInOwnCatalogue_when_ownerAsMasterBulkCreates() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-302-selfmaster-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 302 Owner-Master Salon");

        // The Phase 12.4 endpoint that materialises the owner-operated master row. Its salon_id is
        // the owner's own salon, so master.getSalon() != null and the salon branch applies.
        ResponseEntity<String> enable = restTemplate.exchange(
                "/api/v1/salons/" + salonId + "/master", HttpMethod.POST,
                new HttpEntity<>(fixtures.bearerHeaders(ownerToken)), String.class);
        assertThat(enable.getStatusCode()).isEqualTo(HttpStatus.OK);
        UUID ownerMasterId = objectMapper.readValue(enable.getBody(),
                new TypeReference<ApiResponse<com.beautica.master.dto.MasterDetailResponse>>() {})
                .data().masterId();
        fixtures.seedUsableSchedule(ownerMasterId);

        log.debug("Act: owner bulk-creates for their OWN master row");
        ResponseEntity<String> resp = postSalonBulk(ownerToken, salonId, ownerMasterId,
                new BulkCreateServicesRequest(List.of(fixed(seededTypes.get(0).id(), 60, "350.00"))));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID defId = createdFrom(resp).get(0).serviceDefinition().id();

        assertThat(definitionRow(defId))
                .as("an owner-operated master row is salon-bound, so its services are SALON-owned too")
                .containsEntry("owner_type", "SALON")
                .containsEntry("owner_id", salonId);
        assertThat(catalogueServiceIds(salonId))
                .as("an owner who performs services has them in their own salon's catalogue")
                .containsExactly(defId);
    }

    /** Case 10 — in-batch duplicate rejection is unchanged. */
    @Test
    @DisplayName("a salon batch with two identical serviceTypeIds → 400, nothing written (unchanged)")
    void should_return400AndWriteNothing_when_salonBatchRepeatsAServiceTypeId() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-302-inbatch-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 302 In-Batch Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);

        UUID typeId = seededTypes.get(0).id();

        log.debug("Act: one batch toggles the same serviceTypeId twice");
        ResponseEntity<String> resp = postSalonBulk(ownerToken, salonId, masterId,
                new BulkCreateServicesRequest(List.of(
                        fixed(typeId, 60, "350.00"),
                        fixed(typeId, 90, "450.00"))));

        assertThat(resp.getStatusCode())
                .as("a self-inconsistent PAYLOAD is a 400, never the state-conflict 409")
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(countActiveDefinitionsForOwner("SALON", salonId)).isEqualTo(0L);
        assertThat(fixtures.activeDefinitionIdsAssignedToMaster(masterId)).isEmpty();
    }

    /** Cases 11 + 12 — D7's side-effect repair, and the boundary Phase 306 will move. */
    @Test
    @DisplayName("the salon owner can now PATCH a definition created for their salon master (200), "
            + "while a SALON_ADMIN still gets 403 (D7 — 306's subject)")
    void should_allowOwnerPatchAndDenyAdmin_when_definitionWasCreatedForASalonMaster() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-302-patch-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 302 Patch Salon");
        UUID masterId = fixtures.createSalonMaster(salonId);

        ResponseEntity<String> created = postSalonBulk(ownerToken, salonId, masterId,
                new BulkCreateServicesRequest(List.of(fixed(seededTypes.get(0).id(), 60, "350.00"))));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID defId = createdFrom(created).get(0).serviceDefinition().id();

        // findOwnerUserId resolves s.owner.id for a SALON definition, which the owner's actor id
        // matches. Before this phase it resolved the salon MASTER's user id and every role got 403.
        log.debug("Act: owner PATCHes the definition created for their salon master");
        ResponseEntity<String> ownerPatch = restTemplate.exchange(
                "/api/v1/services/" + defId, HttpMethod.PATCH,
                new HttpEntity<>(java.util.Map.of("name", "Перейменовано"),
                        fixtures.bearerHeaders(ownerToken)),
                String.class);

        assertThat(ownerPatch.getStatusCode())
                .as("403-today becomes 200: the definition is no longer orphaned on creation (D7)")
                .isEqualTo(HttpStatus.OK);
        assertThat(definitionRow(defId)).containsEntry("name", "Перейменовано");

        String adminToken = fixtures.createSalonAdminAndGetToken(
                salonId, "admin-302-patch-" + System.nanoTime() + "@beautica.test");

        log.debug("Act: SALON_ADMIN PATCHes the same definition — still denied until Phase 306");
        ResponseEntity<String> adminPatch = restTemplate.exchange(
                "/api/v1/services/" + defId, HttpMethod.PATCH,
                new HttpEntity<>(java.util.Map.of("name", "Адмін"),
                        fixtures.bearerHeaders(adminToken)),
                String.class);

        assertThat(adminPatch.getStatusCode())
                .as("canManageServiceDefinition's role gate still excludes SALON_ADMIN — pinned "
                        + "here so Phase 306's change is provably a change")
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(definitionRow(defId))
                .as("and the denied PATCH wrote nothing")
                .containsEntry("name", "Перейменовано");
    }

    // ══ Phase 302 QA — gaps the implementation batch's own tests do not reach ══════════════════

    /**
     * QA/perf gate — the D2/D4 guards must cost the SAME number of JDBC statements at batch size
     * 20 as at batch size 2.
     *
     * <p><b>What it protects.</b> The phase's central performance claim is that both salon-branch
     * guards are BATCHED: {@code assertMasterDoesNotAlreadyOffer} is one
     * {@code findActiveAssignedServiceTypeIds} for the whole batch and
     * {@code findReusableSalonDefinitions} is one {@code findActiveDuplicateTypeIds} for the whole
     * batch. Nothing else pinned that. A refactor back to a per-item {@code existsActive} /
     * {@code findActiveDuplicateId} loop — the shape this endpoint carried before the batching
     * work — keeps every behavioural test in this class green while issuing N extra serialized
     * SELECTs, each forcing a Hibernate AUTO flush that also destroys JDBC insert batching. The
     * bulk endpoint accepts up to 100 items, so that is a 100× round-trip regression that no
     * assertion in this repository would notice.
     *
     * <p><b>Why equality and not a bound.</b> {@code hibernate.jdbc.batch_size=50} +
     * {@code order_inserts=true} (application.yml) mean 2 and 20 definitions flush through the
     * SAME number of prepared statements — one per entity type per flush. So every non-constant
     * term is zero and the two counts must be IDENTICAL. The absolute value is deliberately NOT
     * pinned: it is derived from the run and only its INVARIANCE is asserted, so an unrelated new
     * query on the path is a matter for {@code ServicesIntegrationTest}'s absolute gate, not a
     * false failure here.
     *
     * <p>Both batches are fresh types for a fresh master in the same salon, so both take the
     * CREATE arm — comparing create-to-create keeps the insert count the only variable, and it is
     * exactly the term batching flattens.
     *
     * <p>Driven through the service bean, not HTTP: an HTTP request folds in the JWT filter's
     * user lookup and the authz guard's ownership query, neither of which is part of the claim.
     */
    @Test
    @DisplayName("salon bulk-create issues the SAME statement count at batch size 20 as at 2 — "
            + "the D2/D4 guards are batched, not per-item")
    void should_keepStatementCountConstant_when_salonBulkCreateBatchSizeGrows() throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-302-stmtgate-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 302 Statement Gate Salon");

        List<ServiceTestFixtures.SeededServiceType> types = fixtures.activeSelectableServiceTypes(22);
        assertThat(types)
                .as("the statement gate needs 22 distinct selectable service types to build a "
                        + "2-item and a disjoint 20-item batch")
                .hasSize(22);

        UUID smallBatchMaster = fixtures.createSalonMaster(salonId);
        UUID largeBatchMaster = fixtures.createSalonMaster(salonId);

        var smallBatch = new BulkCreateServicesRequest(types.subList(0, 2).stream()
                .map(t -> fixed(t.id(), 60, "350.00")).toList());
        // Disjoint types, so this master also takes the CREATE arm — never the reuse arm — and the
        // only thing that differs between the two measurements is the number of rows inserted.
        var largeBatch = new BulkCreateServicesRequest(types.subList(2, 22).stream()
                .map(t -> fixed(t.id(), 60, "350.00")).toList());

        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);

        statistics.clear();
        log.debug("Act: salon bulk-create of a 2-item batch, counting prepared statements");
        serviceCatalogService.bulkCreateSalonMasterServices(salonId, smallBatchMaster, smallBatch);
        long twoItemStatements = statistics.getPrepareStatementCount();

        statistics.clear();
        log.debug("Act: salon bulk-create of a 20-item batch for a second master in the same salon");
        serviceCatalogService.bulkCreateSalonMasterServices(salonId, largeBatchMaster, largeBatch);
        long twentyItemStatements = statistics.getPrepareStatementCount();

        log.debug("Observed prepareStatementCount: n=2 -> {}, n=20 -> {}",
                twoItemStatements, twentyItemStatements);

        assertThat(twentyItemStatements)
                .as("O(1) in batch size. A rise of ~N means a guard went per-item: 20 items cost "
                        + "%s statements against %s for 2. Both guards and the reuse lookup must "
                        + "stay one query each for the WHOLE batch, and the inserts must stay "
                        + "batched. Re-derive, never bump.",
                        twentyItemStatements, twoItemStatements)
                .isEqualTo(twoItemStatements);

        // A batch that wrote nothing would trivially satisfy the gate above.
        assertThat(fixtures.activeDefinitionIdsAssignedToMaster(largeBatchMaster))
                .as("the measured 20-item batch actually persisted 20 assignments")
                .hasSize(20);
        assertThat(countActiveDefinitionsForOwner("SALON", salonId))
                .as("and 22 SALON-owned definitions exist across both batches")
                .isEqualTo(22L);
    }

    /**
     * QA/security gate — a master ROTATED between two of one owner's salons must be able to
     * bulk-create, in the DESTINATION salon, a service type they already perform in the SOURCE
     * salon.
     *
     * <p><b>The defect.</b> {@code MasterService.rotateMasterToSalon} moves {@code masters.salon_id}
     * and never touches {@code master_services}, so a rotated master keeps ACTIVE assignments to the
     * SOURCE salon's definitions. Phase 302's new per-master conflict finder,
     * {@code MasterServiceRepository.findActiveAssignedServiceTypeIds}, is scoped by master id
     * ALONE — it cannot see which salon owns the definition it matched. So the destination salon's
     * first bulk-create for that type is rejected {@code 409 DUPLICATE_SERVICE} carrying
     * {@code existingServiceDefId} = a definition belonging to a DIFFERENT salon: both a wrong
     * answer (the master offers nothing in this salon yet) and a cross-salon id disclosure to an
     * actor who is only authorised for the destination.
     *
     * <p><b>The contract pinned here.</b> The conflict is per-master <em>within this salon</em>:
     * the destination bulk-create returns {@code 201}, mints the destination salon's own
     * {@code (SALON, destinationSalonId, typeId)} definition, and no source-salon id is returned.
     *
     * <p>Same owner for both salons because rotation is legal only inside one owner's portfolio
     * ({@code AuthorizationService.salonsShareOwner}) — an actor who could not reach the source
     * salon at all cannot even reach this state.
     */
    @Test
    @DisplayName("a rotated master bulk-creating in the DESTINATION salon gets 201 with that "
            + "salon's own definition — never a 409 leaking the SOURCE salon's definition id")
    void should_return201AndNotLeakSourceSalonDefId_when_rotatedMasterBulkCreatesInDestinationSalon()
            throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-302-rotate-" + System.nanoTime() + "@beautica.test");
        UUID sourceSalonId = fixtures.createSalon(ownerToken, "Phase 302 Rotation Source");
        UUID destinationSalonId = fixtures.createSalon(ownerToken, "Phase 302 Rotation Destination");
        UUID masterId = fixtures.createSalonMaster(sourceSalonId);

        UUID typeId = seededTypes.get(0).id();

        ResponseEntity<String> inSource = postSalonBulk(ownerToken, sourceSalonId, masterId,
                new BulkCreateServicesRequest(List.of(fixed(typeId, 60, "350.00"))));
        assertThat(inSource.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID sourceDefId = createdFrom(inSource).get(0).serviceDefinition().id();

        log.debug("Act: rotate the master from the source salon to the sibling destination salon");
        ResponseEntity<String> rotation = restTemplate.exchange(
                "/api/v1/masters/" + masterId + "/salon", HttpMethod.PATCH,
                new HttpEntity<>(java.util.Map.of("destinationSalonId", destinationSalonId),
                        fixtures.bearerHeaders(ownerToken)),
                String.class);
        assertThat(rotation.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The precondition the defect rests on — stated as an assertion so the test cannot quietly
        // stop exercising the hazard if rotation ever starts cascading to master_services.
        assertThat(fixtures.activeDefinitionIdsAssignedToMaster(masterId))
                .as("rotation does not touch master_services, so the stale SOURCE-salon assignment "
                        + "survives — this is what the per-master conflict finder can see")
                .containsExactly(sourceDefId);

        log.debug("Act: the owner bulk-creates the SAME service type for the master in the "
                + "DESTINATION salon, where the master offers nothing yet");
        ResponseEntity<String> inDestination = postSalonBulk(ownerToken, destinationSalonId, masterId,
                new BulkCreateServicesRequest(List.of(fixed(typeId, 60, "350.00"))));

        assertThat(inDestination.getStatusCode())
                .as("the master offers nothing in the DESTINATION salon; a stale assignment to "
                        + "ANOTHER salon's definition must not read as a per-master conflict here. "
                        + "Body: %s", inDestination.getBody())
                .isEqualTo(HttpStatus.CREATED);

        UUID destinationDefId = createdFrom(inDestination).get(0).serviceDefinition().id();
        assertThat(destinationDefId)
                .as("the destination salon gets its OWN definition — the source salon's row is "
                        + "neither reused nor disclosed")
                .isNotEqualTo(sourceDefId);
        assertThat(definitionRow(destinationDefId))
                .containsEntry("owner_type", "SALON")
                .containsEntry("owner_id", destinationSalonId);
        assertThat(countActiveDefinitionsForOwner("SALON", destinationSalonId))
                .as("exactly one definition in the destination salon")
                .isEqualTo(1L);
        assertThat(countActiveDefinitionsForOwner("SALON", sourceSalonId))
                .as("and the source salon's catalogue is untouched by the destination write")
                .isEqualTo(1L);
    }

    /**
     * QA/security gate — TRUE concurrency for the reuse path's read-then-write window.
     *
     * <p><b>The defect.</b> {@code acquireBulkSetupLockWithTimeout} keys the advisory lock on the
     * MASTER id. Under Phase 302 the contended resource is no longer per-master: it is the SALON's
     * one active definition per service type, enforced by V121's
     * {@code ux_service_def_owner_service_type_active}. Two DIFFERENT masters in one salon
     * therefore take two DIFFERENT locks, both run {@code findReusableSalonDefinitions} before
     * either commits, both miss, and both INSERT {@code (SALON, salonId, typeId)}. The loser trips
     * the index at flush, where {@code flushBulkBatch} can only translate a constraint name — so
     * the client gets a {@code 409 DUPLICATE_SERVICE} whose {@code serviceName} and
     * {@code existingServiceDefId} are BOTH null: an error the setup screen cannot act on, for a
     * request that should simply have reused.
     *
     * <p><b>The contract pinned here.</b> Concurrency is invisible: both callers get their
     * assignment, the salon holds ONE definition, and both assignments point at it. This is the
     * ordinary two-masters-same-type outcome the sequential test already pins — the point is that
     * interleaving must not change it.
     *
     * <p>Deliberately the SAME service type and the same salon: disjoint types would take
     * disjoint V121 keys and could never race. Threads are released together by a
     * {@link CyclicBarrier} (never {@code Thread.sleep}) and call the Spring proxy so each gets
     * its own {@code @Transactional} and its own transaction-scoped advisory lock.
     */
    @Test
    @DisplayName("two masters in ONE salon bulk-creating the same NEW type concurrently both "
            + "succeed and share one definition — never a null-bodied V121 409")
    void should_reuseNotConflict_when_twoMastersInOneSalonBulkCreateSameTypeConcurrently()
            throws Exception {
        String ownerToken = fixtures.createSalonOwnerAndGetToken(
                "owner-302-race-" + System.nanoTime() + "@beautica.test");
        UUID salonId = fixtures.createSalon(ownerToken, "Phase 302 Race Salon");
        UUID firstMasterId = fixtures.createSalonMaster(salonId);
        UUID secondMasterId = fixtures.createSalonMaster(salonId);

        UUID typeId = seededTypes.get(0).id();
        // Different prices so the surviving definition's provenance stays readable, and so a
        // last-writer-wins mutation of the shared row would also be visible.
        var firstBatch = new BulkCreateServicesRequest(List.of(fixed(typeId, 60, "350.00")));
        var secondBatch = new BulkCreateServicesRequest(List.of(fixed(typeId, 90, "420.00")));

        CyclicBarrier startLine = new CyclicBarrier(2);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Throwable firstError;
        Throwable secondError;
        try {
            log.debug("Act: two masters of ONE salon call bulkCreateSalonMasterServices for the "
                    + "SAME service type, released together by a barrier");
            Future<Throwable> first = pool.submit(() -> attemptSalonBulk(
                    startLine, salonId, firstMasterId, firstBatch, successCount, conflictCount));
            Future<Throwable> second = pool.submit(() -> attemptSalonBulk(
                    startLine, salonId, secondMasterId, secondBatch, successCount, conflictCount));

            firstError = first.get(30, TimeUnit.SECONDS);
            secondError = second.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(List.of(java.util.Optional.ofNullable(firstError),
                        java.util.Optional.ofNullable(secondError)))
                .filteredOn(java.util.Optional::isPresent)
                .extracting(java.util.Optional::get)
                .as("neither concurrent caller may fail: the salon already offering the type is "
                        + "the REUSE path (D2/D4), and interleaving must not turn it into a "
                        + "conflict. A DuplicateServiceException with a null existingServiceDefId "
                        + "here is the V121 index reporting a constraint the reuse lookup should "
                        + "have prevented. Got %s / %s", firstError, secondError)
                .isEmpty();

        assertThat(successCount.get())
                .as("both masters take the service; only one of them creates the definition")
                .isEqualTo(2);
        assertThat(conflictCount.get()).isZero();

        assertThat(countActiveDefinitionsForOwner("SALON", salonId))
                .as("ONE definition survives — the whole point of D2 under contention")
                .isEqualTo(1L);

        List<UUID> firstMenu = fixtures.activeDefinitionIdsAssignedToMaster(firstMasterId);
        List<UUID> secondMenu = fixtures.activeDefinitionIdsAssignedToMaster(secondMasterId);
        assertThat(firstMenu).hasSize(1);
        assertThat(secondMenu)
                .as("both masters perform the ONE shared definition")
                .isEqualTo(firstMenu);
    }

    /**
     * Runs one salon on-behalf bulk attempt after meeting the other thread at the barrier.
     * Mirrors {@link #attemptBulk}: returns the thrown exception (or {@code null}) rather than
     * letting it escape, so the test thread can assert on BOTH outcomes deterministically.
     */
    private Throwable attemptSalonBulk(CyclicBarrier startLine, UUID salonId, UUID masterId,
                                       BulkCreateServicesRequest request,
                                       AtomicInteger successCount, AtomicInteger conflictCount) {
        try {
            startLine.await(10, TimeUnit.SECONDS); // both threads cross together — no sleep
            serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);
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
