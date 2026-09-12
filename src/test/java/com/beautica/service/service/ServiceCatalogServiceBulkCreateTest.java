package com.beautica.service.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.DuplicateServiceException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.dto.BulkCreateServicesRequest;
import com.beautica.service.dto.BulkServiceItemRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.ActiveDuplicateProjection;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.SalonBulkSetupCandidate;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the bulk service-create paths in {@link ServiceCatalogService}
 * ({@code bulkCreateIndependentMasterServices} + {@code bulkCreateSalonMasterServices}).
 *
 * <p>The flow is <em>additive</em>: it is callable whether or not the master already has
 * services, so one multi-select screen covers both initial catalogue setup and later
 * additions. There is no menu-emptiness precondition; the only state-conflict left is a
 * per-service {@code DUPLICATE_SERVICE} 409.
 *
 * <p>Covers — at the service layer, with all collaborators mocked — the behaviour the
 * controller slice and integration test cannot observe cheaply:
 * <ul>
 *   <li>Happy path: a mixed FIXED + RANGE batch persists one ServiceDefinition +
 *       MasterServiceAssignment per item, with name/category derived from the ServiceType
 *       and {@code base_price = priceMin} for RANGE items.</li>
 *   <li>Additive contract: a master with an existing catalogue is not blocked, and no
 *       menu-emptiness predicate is consulted at all.</li>
 *   <li>Advisory lock: taken on the contended V121 key space — the SALON on the on-behalf branch,
 *       the master row on the independent branch (phase-302 audit HIGH-2) — to serialise
 *       concurrent additive batches against the read-then-write duplicate guard. The serialised window is narrow — global
 *       reference-data reads (type resolution, category validation) run BEFORE it; the
 *       duplicate guard and the inserts run inside it. A failed acquisition aborts the batch
 *       (500), and a wait exceeding the fused 3s {@code lock_timeout} becomes a retryable
 *       503 rather than a raw data-access 500.</li>
 *   <li>Duplicate {@code serviceTypeId} in the batch → 400, nothing persisted.</li>
 *   <li>Unknown {@code serviceTypeId} → 404; inactive type → 400; both abort the batch.</li>
 *   <li>Unknown derived category → 400, nothing persisted.</li>
 *   <li>Authorization: non-INDEPENDENT_MASTER on the self path → 403; a master not in the
 *       salon on the on-behalf path → 403 (the {@code masterBelongsToSalon} guard half).</li>
 * </ul>
 *
 * <p>The transactional all-or-nothing guarantee itself is a DB property and is pinned by
 * {@code BulkServiceSetupIntegrationTest}; here the negative-path tests assert the
 * pre-persist guards fire and {@code serviceRepository.save} is never reached.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceCatalogService — additive bulk service create")
class ServiceCatalogServiceBulkCreateTest {

    @Mock private ServiceRepository serviceRepository;
    @Mock private MasterServiceRepository masterServiceRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private MasterRepository masterRepository;
    @Mock private CatalogCategoryLookup catalogCategoryLookup;
    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private ServiceTypeSuggestionService serviceTypeSuggestionService;
    @Mock private ServiceTypeLookup serviceTypeLookup;
    @Mock private ServiceTypeSearchService serviceTypeSearchService;
    @Mock private ServiceTypeRepository serviceTypeRepository;
    @Mock private CacheManager cacheManager;
    // Phase 304 D1: the salon on-behalf branch now evicts the salon-service-catalog cache via
    // this collaborator (evictSalonCatalogAfterCommit -> salonCatalogCacheEvictor.evict). Mocked
    // (rather than left null) so that call is a no-op default-Mockito-stub instead of an NPE.
    @Mock private SalonCatalogCacheEvictor salonCatalogCacheEvictor;

    @InjectMocks private ServiceCatalogService serviceCatalogService;

    // ── helpers ────────────────────────────────────────────────────────────────

    private Master independentMaster(UUID masterId) {
        Master master = org.mockito.Mockito.mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        return master;
    }

    private ServiceType serviceType(UUID id, String nameUk, String categoryName, boolean active) {
        return ServiceType.builder()
                .id(id)
                .nameUk(nameUk)
                .nameEn(nameUk)
                .slug("slug-" + id)
                .platformCategoryName(categoryName)
                .active(active)
                .build();
    }

    private BulkServiceItemRequest fixedItem(UUID serviceTypeId, int duration, String price) {
        return new BulkServiceItemRequest(
                serviceTypeId, duration, PriceType.FIXED, new BigDecimal(price), null, null);
    }

    private BulkServiceItemRequest rangeItem(UUID serviceTypeId, int duration, String min, String max) {
        return new BulkServiceItemRequest(
                serviceTypeId, duration, PriceType.RANGE, null, new BigDecimal(min), new BigDecimal(max));
    }

    /**
     * Echoes the saved definition back with a generated id so MasterServiceResponse.from can map it.
     *
     * <p>Stubs {@code save}, NOT {@code saveAndFlush}: the bulk path deliberately queues plain
     * saves and flushes the batch ONCE at the end, so per-item flushing cannot defeat
     * {@code hibernate.jdbc.batch_size}. Mirrors production — {@code GenerationType.UUID} means
     * the id exists before any flush.
     */
    private void stubSaveEchoesEntities() {
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> {
            ServiceDefinition def = inv.getArgument(0);
            if (def.getId() == null) {
                def.setId(UUID.randomUUID());
            }
            return def;
        });
        when(masterServiceRepository.save(any(MasterServiceAssignment.class))).thenAnswer(inv -> {
            MasterServiceAssignment msa = inv.getArgument(0);
            if (msa.getId() == null) {
                msa.setId(UUID.randomUUID());
            }
            return msa;
        });
    }

    /**
     * Assignment-only echo stub for the Phase 302 reuse path, where NO ServiceDefinition is
     * saved. Stubbing {@code serviceRepository.save} here would be an unnecessary stubbing under
     * strict stubs — and, more usefully, the fact that it is not needed is itself the contract.
     */
    private void stubAssignmentSaveEchoesEntity() {
        when(masterServiceRepository.save(any(MasterServiceAssignment.class))).thenAnswer(inv -> {
            MasterServiceAssignment msa = inv.getArgument(0);
            if (msa.getId() == null) {
                msa.setId(UUID.randomUUID());
            }
            return msa;
        });
    }

    // ── Happy path (self endpoint) ─────────────────────────────────────────────

    @Test
    @DisplayName("independent master — mixed FIXED + RANGE batch creates 2 services, derives name + category, base_price=priceMin for RANGE")
    void should_createWholeBatch_when_independentMasterBulkSetupWithMixedPricing() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID fixedTypeId = UUID.randomUUID();
        UUID rangeTypeId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        ServiceType fixedType = serviceType(fixedTypeId, "Манікюр", "NAIL_SERVICE", true);
        ServiceType rangeType = serviceType(rangeTypeId, "Фарбування волосся", "HAIR", true);

        var request = new BulkCreateServicesRequest(List.of(
                fixedItem(fixedTypeId, 60, "350.00"),
                rangeItem(rangeTypeId, 120, "800.00", "1500.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(fixedType, rangeType));
        when(platformCategoryRepository.findSelectableNamesIn(any()))
                .thenReturn(List.of("NAIL_SERVICE", "HAIR"));
        stubSaveEchoesEntities();

        List<MasterServiceResponse> result =
                serviceCatalogService.bulkCreateIndependentMasterServices(userId, request);

        // Exactly one ServiceDefinition saved per item.
        ArgumentCaptor<ServiceDefinition> defCaptor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository, times(2)).save(defCaptor.capture());
        verify(masterServiceRepository, times(2)).save(any(MasterServiceAssignment.class));

        // PERF: the batch reaches the DB in ONE flush, not one per item — per-item flushing
        // would defeat hibernate.jdbc.batch_size=50 + order_inserts=true. Never saveAndFlush here.
        verify(serviceRepository, never()).saveAndFlush(any(ServiceDefinition.class));
        verify(serviceRepository, times(1)).flush();

        // PERF: and the V121 duplicate guard is ONE query for the whole batch, not one per item.
        verify(serviceRepository, times(1)).findActiveDuplicateTypeIds(
                OwnerType.INDEPENDENT_MASTER, masterId, java.util.Set.of(fixedTypeId, rangeTypeId));
        verify(serviceRepository, never()).findActiveDuplicateId(any(), any(), any(), any());

        List<ServiceDefinition> savedDefs = defCaptor.getAllValues();

        ServiceDefinition fixedDef = savedDefs.get(0);
        assertThat(fixedDef.getName())
                .as("name derived from ServiceType.nameUk, not client-supplied")
                .isEqualTo("Манікюр");
        assertThat(fixedDef.getCategory())
                .as("category derived from ServiceType.platformCategoryName")
                .isEqualTo("NAIL_SERVICE");
        assertThat(fixedDef.getOwnerType()).isEqualTo(OwnerType.INDEPENDENT_MASTER);
        assertThat(fixedDef.getOwnerId()).isEqualTo(masterId);
        assertThat(fixedDef.getBasePrice())
                .as("FIXED base_price = price")
                .isEqualByComparingTo("350.00");
        assertThat(fixedDef.getPriceMax()).as("FIXED price_max is null").isNull();

        ServiceDefinition rangeDef = savedDefs.get(1);
        assertThat(rangeDef.getName()).isEqualTo("Фарбування волосся");
        assertThat(rangeDef.getCategory()).isEqualTo("HAIR");
        assertThat(rangeDef.getBasePrice())
                .as("RANGE base_price = priceMin (canonical floor)")
                .isEqualByComparingTo("800.00");
        assertThat(rangeDef.getPriceMax())
                .as("RANGE price_max = priceMax")
                .isEqualByComparingTo("1500.00");

        assertThat(result)
                .as("response carries one entry per created service")
                .hasSize(2);
        assertThat(result).extracting(MasterServiceResponse::priceType)
                .containsExactly(PriceType.FIXED, PriceType.RANGE);

        // Search index + cache kept in sync for the master.
        verify(masterRepository).refreshMinEffectivePrice(masterId);
    }

    @Test
    @DisplayName("salon on-behalf — batch persists with ownerType=SALON + ownerId=salonId (Phase 302 D1)")
    void should_createBatchOwnedBySalon_when_salonOnBehalfBulkSetup() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();

        Salon salon = org.mockito.Mockito.mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);
        Master master = org.mockito.Mockito.mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getSalon()).thenReturn(salon);

        ServiceType type = serviceType(typeId, "Стрижка", "HAIR", true);
        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 45, "250.00")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("HAIR"));
        stubSaveEchoesEntities();

        List<MasterServiceResponse> result =
                serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);

        ArgumentCaptor<ServiceDefinition> defCaptor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(defCaptor.capture());

        assertThat(defCaptor.getValue().getOwnerType())
                .as("a salon-bound master's services are SALON-owned — the ownership the salon "
                        + "catalogue query requires (Phase 302 D1)")
                .isEqualTo(OwnerType.SALON);
        assertThat(defCaptor.getValue().getOwnerId())
                .as("ownerId is the SALON id, not the master row id")
                .isEqualTo(salonId);
        assertThat(result).hasSize(1);

        // ONE salon-scoped query answers BOTH the reuse lookup (the salon's definitions) and the
        // conflict (this master's assignments) — audit LOW-3. It is asked about THIS salon and
        // THIS master, never about another owner's definitions, and the owner-level definition
        // guard plus its findAllById re-fetch are gone from this branch entirely.
        verify(serviceRepository).findSalonBulkSetupCandidates(
                salonId, masterId, java.util.Set.of(typeId));
        verify(serviceRepository, never()).findActiveDuplicateTypeIds(any(), any(), any());
        verify(serviceRepository, never()).findAllById(any());

        // Parity with the self path: the shared additive core's post-write bookkeeping must run
        // for the on-behalf entry point too. Now that the endpoint is additive, this is reachable
        // on every later "add more services" pass — a stale min_effective_price would misprice the
        // salon master in search on every one of them, not just at first setup.
        verify(masterRepository).refreshMinEffectivePrice(masterId);

        // ── phase-302 audit HIGH-2, re-audit LOW-2: THE LOCK KEY IS THE SALON ──
        // V121 keys on (owner_type, owner_id, service_type_id), so on this branch the contended
        // resource is the SALON's definition set, shared by every master in it. A master-keyed
        // lock let two DIFFERENT masters of one salon take two DIFFERENT locks, both miss
        // findSalonBulkSetupCandidates' reuse arm, and both INSERT (SALON, salonId, typeId) — the
        // loser then tripping V121 at flush, where flushBulkBatch can only translate a constraint
        // NAME, so the client got a DUPLICATE_SERVICE 409 with serviceName AND existingServiceDefId
        // both null.
        //
        // The two-thread race IT (BulkServiceSetupIntegrationTest) is NOT a guard for this: it
        // goes false-green whenever the two transactions happen not to interleave. This assertion
        // is deterministic, and the sibling `never()` is what actually fails if the key regresses
        // to masterId — a bare verify(salonId) would pass on a mock that took BOTH.
        verify(masterServiceRepository).acquireBulkSetupLockWithTimeout(salonId);
        verify(masterServiceRepository, never()).acquireBulkSetupLockWithTimeout(masterId);

        // …and it is taken at the right point: after the global reference-data reads (which need
        // no serialization) and before the salon-scoped read-then-write candidate query, which is
        // exactly the span the lock exists to serialize.
        InOrder lockOrder = org.mockito.Mockito.inOrder(
                serviceTypeRepository, masterServiceRepository, serviceRepository);
        lockOrder.verify(serviceTypeRepository).findAllById(anyList());
        lockOrder.verify(masterServiceRepository).acquireBulkSetupLockWithTimeout(salonId);
        lockOrder.verify(serviceRepository).findSalonBulkSetupCandidates(any(), any(), any());
    }

    /**
     * Phase 304 gap (perf INFO): both {@code ServiceCatalogServiceCacheTest} cases 5-8 use a
     * ONE-item bulk request, so nothing pins that an N-item batch evicts the salon catalogue
     * ONCE, not N times. The eviction call sits AFTER {@code request.items().stream()...toList()}
     * in {@code bulkCreateForMaster} — outside the per-item loop — so today it cannot thrash. A
     * future refactor moving it into {@code createSingleFromBulkItem} (called once per item)
     * would silently turn every bulk-onboarding call into N cache evictions instead of 1, and
     * nothing before this test would catch it.
     *
     * <p><b>Mutation-red:</b> moving {@code evictSalonCatalogAfterCommit(ownerId)} out of
     * {@code bulkCreateForMaster} and into the body of {@code createSingleFromBulkItem} (so it
     * runs once per stream element) turns {@code times(1)} into an observed {@code times(3)} for
     * this 3-item batch — this test goes red on a real behavioural regression, not a mock wiring
     * change.
     */
    @Test
    @DisplayName("Phase 304 — a 3-item salon on-behalf batch evicts the salon catalogue exactly "
            + "ONCE, not once per item")
    void should_evictSalonCatalogExactlyOnce_when_salonOnBehalfBatchHasMultipleItems() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId1 = UUID.randomUUID();
        UUID typeId2 = UUID.randomUUID();
        UUID typeId3 = UUID.randomUUID();

        Salon salon = org.mockito.Mockito.mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);
        Master master = org.mockito.Mockito.mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getSalon()).thenReturn(salon);

        ServiceType type1 = serviceType(typeId1, "Стрижка", "HAIR", true);
        ServiceType type2 = serviceType(typeId2, "Манікюр", "NAIL_SERVICE", true);
        ServiceType type3 = serviceType(typeId3, "Фарбування волосся", "HAIR", true);

        var request = new BulkCreateServicesRequest(List.of(
                fixedItem(typeId1, 45, "250.00"),
                fixedItem(typeId2, 60, "350.00"),
                rangeItem(typeId3, 120, "800.00", "1500.00")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type1, type2, type3));
        when(platformCategoryRepository.findSelectableNamesIn(any()))
                .thenReturn(List.of("HAIR", "NAIL_SERVICE"));
        stubSaveEchoesEntities();

        List<MasterServiceResponse> result =
                serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);

        assertThat(result).as("all three items persisted").hasSize(3);
        verify(serviceRepository, times(3)).save(any(ServiceDefinition.class));

        // No active Spring transaction in this pure-Mockito unit test, so
        // evictSalonCatalogAfterCommit takes its synchronous else-branch and calls the evictor
        // directly — exactly once for the whole batch, never once per item.
        verify(salonCatalogCacheEvictor, times(1)).evict(salonId);
    }

    /**
     * The removed precondition lived in the shared core, so the on-behalf path became additive at
     * the same moment the self path did — but only the self path had an explicit pin. Consulting
     * the menu-emptiness predicate at all is what the old behaviour did, so its absence is the
     * contract.
     */
    @Test
    @DisplayName("salon on-behalf adds to an existing catalogue — no menu-emptiness precondition is consulted")
    void should_createBatch_when_salonMasterAlreadyHasActiveServices() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();

        Salon salon = org.mockito.Mockito.mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);
        Master master = org.mockito.Mockito.mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getSalon()).thenReturn(salon);

        ServiceType type = serviceType(typeId, "Стрижка", "HAIR", true);
        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 45, "250.00")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("HAIR"));
        stubSaveEchoesEntities();

        List<MasterServiceResponse> result =
                serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);

        assertThat(result).hasSize(1);
        verify(masterServiceRepository, never()).existsActiveServiceForMaster(any());
    }

    // ── Phase 302 D2/D3/D4 — salon-owned definitions are REUSED, never duplicated ──

    /** A master row already bound to {@code salonId}, as the on-behalf path resolves it. */
    private Master salonMaster(UUID masterId, UUID salonId) {
        Salon salon = org.mockito.Mockito.mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);
        Master master = org.mockito.Mockito.mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getSalon()).thenReturn(salon);
        return master;
    }

    /** The salon's already-persisted ACTIVE definition for {@code type} — the reuse target. */
    private ServiceDefinition existingSalonDefinition(
            UUID defId, UUID salonId, ServiceType type, String basePrice, int durationMinutes) {
        ServiceDefinition definition = ServiceDefinition.builder()
                .id(defId)
                .ownerType(OwnerType.SALON)
                .ownerId(salonId)
                .name(type.getNameUk())
                .category(type.getPlatformCategoryName())
                .baseDurationMinutes(durationMinutes)
                .bufferMinutesAfter(0)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal(basePrice))
                .isActive(true)
                .build();
        definition.setServiceType(type);
        return definition;
    }

    /**
     * The salon's already-persisted ACTIVE definition priced as a RANGE band — the reuse target
     * for the shape cases {@link #existingSalonDefinition}'s FIXED shape cannot express.
     */
    private ServiceDefinition existingSalonRangeDefinition(
            UUID defId, UUID salonId, ServiceType type, String priceMin, String priceMax,
            int durationMinutes) {
        ServiceDefinition definition = ServiceDefinition.builder()
                .id(defId)
                .ownerType(OwnerType.SALON)
                .ownerId(salonId)
                .name(type.getNameUk())
                .category(type.getPlatformCategoryName())
                .baseDurationMinutes(durationMinutes)
                .bufferMinutesAfter(0)
                .priceType(PriceType.RANGE)
                .basePrice(new BigDecimal(priceMin))
                .priceMax(new BigDecimal(priceMax))
                .isActive(true)
                .build();
        definition.setServiceType(type);
        return definition;
    }

    /**
     * Stubs the salon branch's SINGLE candidate query. The phase-302 audit (LOW-3) collapsed the
     * former three round-trips — per-master assignment lookup, owner-level definition lookup and a
     * bare {@code findAllById} re-fetch — into
     * {@code ServiceRepository#findSalonBulkSetupCandidates}, which is also where the salon
     * scoping that closes the rotated-master leak (HIGH-1) lives.
     *
     * <p>A candidate's {@code masterAssignmentId} is the whole signal: non-null means "this master
     * already performs it" (the 409 conflict), null means "the salon offers it, this master does
     * not" (the reuse path).
     */
    private void stubSalonCandidates(UUID salonId, UUID masterId, SalonBulkSetupCandidate... candidates) {
        when(serviceRepository.findSalonBulkSetupCandidates(
                org.mockito.ArgumentMatchers.eq(salonId),
                org.mockito.ArgumentMatchers.eq(masterId),
                any()))
                .thenReturn(List.of(candidates));
    }

    /**
     * D2 — the core of the phase. A second master in the same salon toggling a service type the
     * salon already offers must NOT mint a second definition: V121's
     * {@code ux_service_def_owner_service_type_active} would refuse it. The write degenerates to
     * the assignment alone, pointing at the salon's existing definition.
     */
    @Test
    @DisplayName("salon on-behalf — a type the salon already offers REUSES the existing definition; no second ServiceDefinition is saved (D2)")
    void should_reuseSalonDefinition_when_salonAlreadyOffersServiceType() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        UUID existingDefId = UUID.randomUUID();

        Master master = salonMaster(masterId, salonId);
        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        ServiceDefinition existing = existingSalonDefinition(existingDefId, salonId, type, "350.00", 60);

        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 60, "350.00")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        // The salon offers the type but this master does not perform it → the REUSE row.
        stubSalonCandidates(salonId, masterId, new SalonBulkSetupCandidate(typeId, existing, null, null));
        stubAssignmentSaveEchoesEntity();

        List<MasterServiceResponse> result =
                serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);

        verify(serviceRepository, never()).save(any(ServiceDefinition.class));

        ArgumentCaptor<MasterServiceAssignment> msaCaptor =
                ArgumentCaptor.forClass(MasterServiceAssignment.class);
        verify(masterServiceRepository).save(msaCaptor.capture());
        assertThat(msaCaptor.getValue().getServiceDefinition())
                .as("the assignment points at the salon's EXISTING definition")
                .isSameAs(existing);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).serviceDefinition().id()).isEqualTo(existingDefId);
    }

    /**
     * Phase 307 D6 (bulk path) — the QA gap this test closes: {@code
     * ServiceCatalogServiceBulkCreateTest} covered the fresh-insert reuse row ({@code
     * masterAssignmentId=null}) and the ACTIVE-conflict row ({@code masterAssignmentActive=true})
     * but never the third {@link SalonBulkSetupCandidate} shape — a master's own INACTIVE {@code
     * master_services} row for the salon's reused definition, i.e. a service this master
     * unassigned earlier via {@code unassignServiceFromMaster} and is now re-adding through the
     * bulk screen. {@code reactivatableAssignmentIdByTypeId} (:1742) and {@code
     * createSingleFromBulkItem}'s reactivation sub-branch (:776-787) existed in the diff with zero
     * unit coverage — only {@code MasterServiceUnassignIT} case 8 exercised D6, and only through
     * the SINGLE-assign endpoint, never the bulk one.
     *
     * <p>Proves the bulk path REACTIVATES the existing row (mutates the SAME managed entity,
     * dirty-checked — no {@code masterServiceRepository.save} call) rather than inserting a
     * second {@code master_services} row for the pair, which would trip the non-partial {@code
     * UNIQUE (master_id, service_def_id)} at flush.
     */
    @Test
    @DisplayName("salon on-behalf — a master's own INACTIVE assignment for the reused definition is "
            + "REACTIVATED, not re-inserted (Phase 307 D6, bulk path)")
    void should_reactivateInactiveAssignment_when_bulkReAddingPreviouslyUnassignedService() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        UUID existingDefId = UUID.randomUUID();
        UUID inactiveAssignmentId = UUID.randomUUID();

        Master master = salonMaster(masterId, salonId);
        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        ServiceDefinition existing = existingSalonDefinition(existingDefId, salonId, type, "350.00", 60);
        MasterServiceAssignment inactiveAssignment = MasterServiceAssignment.builder()
                .id(inactiveAssignmentId)
                .master(master)
                .serviceDefinition(existing)
                .isActive(false)
                .build();

        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 60, "350.00")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        // masterAssignmentId non-null + masterAssignmentActive=false => hasInactiveAssignment(),
        // the D6 reactivation candidate — distinct from both the null-id reuse row and the
        // active=true conflict row already covered elsewhere in this class.
        stubSalonCandidates(salonId, masterId,
                new SalonBulkSetupCandidate(typeId, existing, inactiveAssignmentId, false));
        when(masterServiceRepository.findById(inactiveAssignmentId)).thenReturn(Optional.of(inactiveAssignment));

        List<MasterServiceResponse> result =
                serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);

        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
        verify(masterServiceRepository, never())
                .save(any(MasterServiceAssignment.class));
        verify(masterServiceRepository).findById(inactiveAssignmentId);

        assertThat(inactiveAssignment.isActive())
                .as("D6 — the existing row is reactivated in place, not left inactive")
                .isTrue();
        assertThat(result).hasSize(1);
        assertThat(result.get(0).id())
                .as("the response carries the SAME assignment id — no duplicate row was created")
                .isEqualTo(inactiveAssignmentId);
    }

    /**
     * D3 — reuse must never rewrite the shared definition. Name/price/duration on a SALON
     * definition are salon-level facts; overwriting them from one master's batch item would
     * change what every other master in the salon offers.
     */
    @Test
    @DisplayName("salon on-behalf — reuse leaves the shared definition's name, price and duration untouched (D3)")
    void should_notMutateSharedDefinition_when_reusingWithDifferentValues() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        UUID existingDefId = UUID.randomUUID();

        Master master = salonMaster(masterId, salonId);
        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        ServiceDefinition existing = existingSalonDefinition(existingDefId, salonId, type, "350.00", 60);

        // Deliberately different from the definition on BOTH price and duration.
        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 90, "420.00")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        // The salon offers the type but this master does not perform it → the REUSE row.
        stubSalonCandidates(salonId, masterId, new SalonBulkSetupCandidate(typeId, existing, null, null));
        stubAssignmentSaveEchoesEntity();

        serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);

        assertThat(existing.getBasePrice())
                .as("the shared definition's base price is a salon-level fact — untouched by reuse")
                .isEqualByComparingTo("350.00");
        assertThat(existing.getBaseDurationMinutes())
                .as("the shared definition's base duration is untouched by reuse")
                .isEqualTo(60);

        ArgumentCaptor<MasterServiceAssignment> msaCaptor =
                ArgumentCaptor.forClass(MasterServiceAssignment.class);
        verify(masterServiceRepository).save(msaCaptor.capture());
        assertThat(msaCaptor.getValue().getPriceOverride())
                .as("the per-master divergence lands on master_services.price_override")
                .isEqualByComparingTo("420.00");
        assertThat(msaCaptor.getValue().getDurationOverrideMinutes())
                .as("and on master_services.duration_override_minutes")
                .isEqualTo(90);
    }

    /**
     * D3's other half: an item that MATCHES the reused definition must not manufacture an
     * override row. A spurious override would make the master look like a price outlier to
     * {@code fromPublic}'s masking rule and to every "does this master deviate" read.
     */
    @Test
    @DisplayName("salon on-behalf — reuse with matching price/duration writes NO overrides (D3)")
    void should_writeNoOverrides_when_reusedItemMatchesDefinition() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        UUID existingDefId = UUID.randomUUID();

        Master master = salonMaster(masterId, salonId);
        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        ServiceDefinition existing = existingSalonDefinition(existingDefId, salonId, type, "350.00", 60);

        // Same money, different scale — 350 vs 350.00 must compare equal, not produce an override.
        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 60, "350")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        // The salon offers the type but this master does not perform it → the REUSE row.
        stubSalonCandidates(salonId, masterId, new SalonBulkSetupCandidate(typeId, existing, null, null));
        stubAssignmentSaveEchoesEntity();

        serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);

        ArgumentCaptor<MasterServiceAssignment> msaCaptor =
                ArgumentCaptor.forClass(MasterServiceAssignment.class);
        verify(masterServiceRepository).save(msaCaptor.capture());
        assertThat(msaCaptor.getValue().getPriceOverride())
                .as("350 and 350.00 are the same money — compareTo, not equals")
                .isNull();
        assertThat(msaCaptor.getValue().getDurationOverrideMinutes()).isNull();
    }

    /**
     * D4 — the 409 is re-scoped to THIS master. The owner-level guard is not consulted for a
     * conflict on the salon branch at all: were it still in play, this test's sibling
     * ({@link #should_reuseSalonDefinition_when_salonAlreadyOffersServiceType}) would 409.
     */
    @Test
    @DisplayName("salon on-behalf — 409 DUPLICATE_SERVICE when THIS MASTER already offers the type, nothing persisted (D4)")
    void should_throwDuplicateService_when_salonMasterAlreadyOffersServiceType() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        UUID assignedDefId = UUID.randomUUID();

        Master master = salonMaster(masterId, salonId);
        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);

        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 60, "350.00")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        // This master ALREADY performs the type — a non-null ACTIVE assignment id is the conflict signal.
        stubSalonCandidates(salonId, masterId, new SalonBulkSetupCandidate(
                typeId,
                existingSalonDefinition(assignedDefId, salonId, type, "350.00", 60),
                UUID.randomUUID(), true));

        assertThatThrownBy(() ->
                serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request))
                .isInstanceOf(DuplicateServiceException.class)
                .satisfies(ex -> {
                    DuplicateServiceException dup = (DuplicateServiceException) ex;
                    assertThat(dup.getServiceName()).isEqualTo("Манікюр");
                    assertThat(dup.getExistingServiceDefId())
                            .as("carries the definition the master is already assigned, for the deep-link")
                            .isEqualTo(assignedDefId);
                });

        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
        verify(masterServiceRepository, never()).save(any(MasterServiceAssignment.class));
    }

    /**
     * D4 reports the FIRST collision in REQUEST order, not in result order — the query's row order
     * is unspecified, and blaming a later item would make the error non-deterministic across
     * identical requests. Mirrors the owner-level guard's own ordering contract.
     */
    @Test
    @DisplayName("salon on-behalf — 409 names the FIRST already-offered item in request order (D4)")
    void should_reportFirstCollidingItemInRequestOrder_when_salonMasterOffersSeveral() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID firstTypeId = UUID.randomUUID();
        UUID secondTypeId = UUID.randomUUID();
        UUID firstDefId = UUID.randomUUID();
        UUID secondDefId = UUID.randomUUID();

        Master master = salonMaster(masterId, salonId);
        ServiceType firstType = serviceType(firstTypeId, "Манікюр", "NAIL_SERVICE", true);
        ServiceType secondType = serviceType(secondTypeId, "Педикюр", "NAIL_SERVICE", true);

        var request = new BulkCreateServicesRequest(List.of(
                fixedItem(firstTypeId, 60, "350.00"),
                fixedItem(secondTypeId, 90, "450.00")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(firstType, secondType));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        // Result order deliberately REVERSED relative to the request.
        stubSalonCandidates(salonId, masterId,
                new SalonBulkSetupCandidate(
                        secondTypeId,
                        existingSalonDefinition(secondDefId, salonId, secondType, "450.00", 90),
                        UUID.randomUUID(), true),
                new SalonBulkSetupCandidate(
                        firstTypeId,
                        existingSalonDefinition(firstDefId, salonId, firstType, "350.00", 60),
                        UUID.randomUUID(), true));

        assertThatThrownBy(() ->
                serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request))
                .isInstanceOf(DuplicateServiceException.class)
                .satisfies(ex -> assertThat(((DuplicateServiceException) ex).getServiceName())
                        .as("the FIRST item in request order is blamed, regardless of row order")
                        .isEqualTo("Манікюр"));

        // Parity with the single-collision sibling: naming the right item is only half the
        // contract — the batch is all-or-nothing, so the NON-colliding second item must not leak
        // a partial row either. Without this, a guard that threw AFTER persisting would still
        // satisfy the ordering assertion above.
        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
        verify(masterServiceRepository, never()).save(any(MasterServiceAssignment.class));
    }

    /**
     * The retained INDEPENDENT_MASTER branch (D1). Its master rows have {@code salon_id IS NULL},
     * so nothing about them moves — and the assignment-level per-master guard must NOT displace
     * the owner-level definition guard there, which additionally catches an active definition
     * carrying no assignment.
     */
    @Test
    @DisplayName("independent master — keeps the OWNER-level definition guard, never the per-master assignment one (D1 retained branch)")
    void should_keepOwnerLevelGuard_when_independentMasterBulkCreates() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 60, "350.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        stubSaveEchoesEntities();

        ArgumentCaptor<ServiceDefinition> defCaptor = ArgumentCaptor.forClass(ServiceDefinition.class);
        serviceCatalogService.bulkCreateIndependentMasterServices(userId, request);
        verify(serviceRepository).save(defCaptor.capture());

        assertThat(defCaptor.getValue().getOwnerType()).isEqualTo(OwnerType.INDEPENDENT_MASTER);
        assertThat(defCaptor.getValue().getOwnerId()).isEqualTo(masterId);

        verify(serviceRepository).findActiveDuplicateTypeIds(
                OwnerType.INDEPENDENT_MASTER, masterId, java.util.Set.of(typeId));
        verify(serviceRepository, never()).findSalonBulkSetupCandidates(any(), any(), any());
        verify(serviceRepository, never()).findAllById(any());
    }

    /**
     * ── Phase 312 D3 — the reuse branch STORES a differing price shape instead of rejecting it ──
     *
     * <p>{@code master_services} used to carry only a {@code price_override} (a FLOOR) and a
     * {@code duration_override_minutes}, with no per-master price TYPE and no per-master ceiling.
     * So when a batch item's price shape disagreed with the salon definition it reused, the
     * Phase 302 re-audit MEDIUM-1 guard ({@code assertReusableShapesAreRepresentable} /
     * {@code isShapeRepresentableOnAssignment}) rejected the batch with {@code 400
     * SERVICE_PRICE_SHAPE_MISMATCH} rather than silently reshaping it.
     *
     * <p>Phase 311's V165 gives {@code master_services} its own {@code price_type_override} and
     * {@code price_max_override}, so every shape the item can express is now representable — the
     * guard is retired (Phase 312 D3) and this branch stores the master's OWN band instead:
     *
     * <pre>
     *   FIXED 500     definition + RANGE 800–1500 item → OWN band RANGE 800–1500 stored
     *   RANGE 400–900 definition + FIXED 600     item → OWN band FIXED 600 stored
     *   RANGE 400–900 definition + RANGE 500–800 item → OWN band RANGE 500–800 stored (ceiling KEPT)
     * </pre>
     *
     * <p>The three tests below are the INVERTED {@code should_return400_when_...} cases — same
     * fixtures, opposite outcome — kept as the same cases (not deleted) per Phase 312's mandate
     * that Phase 302's rejection tests be inverted, not dropped. The shared definition must stay
     * byte-identical in every case: reuse never mutates it (Phase 311 D3 unchanged).
     */
    @Test
    @DisplayName("salon on-behalf — 201, a RANGE item reusing a FIXED salon definition stores its "
            + "OWN RANGE band on the assignment (Phase 312 D3, inverts the retired 400)")
    void should_storeOwnBand_when_reusedRangeItemMeetsAFixedSalonDefinition() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        UUID existingDefId = UUID.randomUUID();

        Master master = salonMaster(masterId, salonId);
        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        ServiceDefinition existing = existingSalonDefinition(existingDefId, salonId, type, "350.00", 60);

        // Duration deliberately MATCHES, isolating the band assertion from a duration override.
        var request = new BulkCreateServicesRequest(List.of(rangeItem(typeId, 60, "800.00", "1500.00")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        stubSalonCandidates(salonId, masterId, new SalonBulkSetupCandidate(typeId, existing, null, null));
        stubAssignmentSaveEchoesEntity();

        List<MasterServiceResponse> result =
                serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);

        assertThat(result)
                .as("a band the assignment can now store must be accepted, not rejected")
                .hasSize(1);

        ArgumentCaptor<MasterServiceAssignment> msaCaptor =
                ArgumentCaptor.forClass(MasterServiceAssignment.class);
        verify(masterServiceRepository).save(msaCaptor.capture());
        MasterServiceAssignment saved = msaCaptor.getValue();
        assertThat(saved.getPriceTypeOverride())
                .as("the master's OWN shape, independent of the FIXED salon definition")
                .isEqualTo(PriceType.RANGE);
        assertThat(saved.getPriceOverride()).isEqualByComparingTo("800.00");
        assertThat(saved.getPriceMaxOverride()).isEqualByComparingTo("1500.00");

        assertThat(existing.getPriceType())
                .as("the shared definition is not reshaped on the way out")
                .isEqualTo(PriceType.FIXED);
        assertThat(existing.getPriceMax()).isNull();
        assertThat(existing.getBasePrice()).isEqualByComparingTo("350.00");
        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
    }

    /** Table row 2 (inverted) — a FIXED item under a RANGE salon band now stores its own FIXED price. */
    @Test
    @DisplayName("salon on-behalf — 201, a FIXED item reusing a RANGE salon definition stores its "
            + "OWN FIXED price on the assignment (Phase 312 D3, inverts the retired 400)")
    void should_storeOwnBand_when_reusedFixedItemMeetsARangeSalonDefinition() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        UUID existingDefId = UUID.randomUUID();

        Master master = salonMaster(masterId, salonId);
        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        ServiceDefinition existing =
                existingSalonRangeDefinition(existingDefId, salonId, type, "400.00", "900.00", 60);

        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 60, "600.00")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        stubSalonCandidates(salonId, masterId, new SalonBulkSetupCandidate(typeId, existing, null, null));
        stubAssignmentSaveEchoesEntity();

        List<MasterServiceResponse> result =
                serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);

        assertThat(result).hasSize(1);

        ArgumentCaptor<MasterServiceAssignment> msaCaptor =
                ArgumentCaptor.forClass(MasterServiceAssignment.class);
        verify(masterServiceRepository).save(msaCaptor.capture());
        MasterServiceAssignment saved = msaCaptor.getValue();
        assertThat(saved.getPriceTypeOverride())
                .as("600 renders as a SINGLE price, never 600–900 — a ceiling nobody set")
                .isEqualTo(PriceType.FIXED);
        assertThat(saved.getPriceOverride()).isEqualByComparingTo("600.00");
        assertThat(saved.getPriceMaxOverride()).isNull();

        assertThat(existing.getPriceType()).isEqualTo(PriceType.RANGE);
        assertThat(existing.getBasePrice()).isEqualByComparingTo("400.00");
        assertThat(existing.getPriceMax()).isEqualByComparingTo("900.00");
        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
    }

    /** Table row 3 (inverted) — a diverging RANGE ceiling is now the master's own, kept verbatim. */
    @Test
    @DisplayName("salon on-behalf — 201, a RANGE item whose ceiling differs from the salon band "
            + "stores its OWN ceiling (Phase 312 D3, inverts the retired 400)")
    void should_storeOwnBand_when_reusedRangeItemCeilingDiffersFromTheSalonBand() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        UUID existingDefId = UUID.randomUUID();

        Master master = salonMaster(masterId, salonId);
        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        ServiceDefinition existing =
                existingSalonRangeDefinition(existingDefId, salonId, type, "400.00", "900.00", 60);

        // Floor 500 AND ceiling 800 both diverge from the salon's 400-900 band.
        var request = new BulkCreateServicesRequest(List.of(rangeItem(typeId, 60, "500.00", "800.00")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        stubSalonCandidates(salonId, masterId, new SalonBulkSetupCandidate(typeId, existing, null, null));
        stubAssignmentSaveEchoesEntity();

        List<MasterServiceResponse> result =
                serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);

        assertThat(result).hasSize(1);

        ArgumentCaptor<MasterServiceAssignment> msaCaptor =
                ArgumentCaptor.forClass(MasterServiceAssignment.class);
        verify(masterServiceRepository).save(msaCaptor.capture());
        MasterServiceAssignment saved = msaCaptor.getValue();
        assertThat(saved.getPriceTypeOverride()).isEqualTo(PriceType.RANGE);
        assertThat(saved.getPriceOverride()).isEqualByComparingTo("500.00");
        assertThat(saved.getPriceMaxOverride())
                .as("the SUBMITTED ceiling is kept, not silently replaced by the salon's 900")
                .isEqualByComparingTo("800.00");

        assertThat(existing.getPriceMax())
                .as("the shared band's ceiling is untouched")
                .isEqualByComparingTo("900.00");
        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
    }

    /**
     * The Inherited case: a RANGE item whose FULL band (floor AND ceiling) matches the salon's
     * writes NO override at all (Phase 311 D2 all-or-nothing) — the assignment keeps tracking the
     * shared definition rather than freezing a redundant copy of its own values.
     *
     * <p>Renamed from {@code should_overrideWithRangeFloor_when_reusedRangeItemMatchesTheSalonCeiling}:
     * that test's fixture had a DIVERGING floor (800 vs 350) and a matching ceiling (1500), which
     * under Phase 311 D2's all-or-nothing rule is an OWN band (any divergence in the triple means
     * the whole triple is written), not a floor-only override — see
     * {@link #should_storeOwnBand_when_reusedRangeItemFloorDivergesButCeilingMatches} below, which
     * carries that fixture forward. This test instead proves the genuinely-matching case stays
     * Inherited, which is what the {@code overridePriceFor} floor-comparison logic it inherited
     * from used to guard.
     */
    @Test
    @DisplayName("salon on-behalf — a RANGE item whose full band MATCHES the salon's stays "
            + "Inherited (no override written at all)")
    void should_stayInherited_when_reusedRangeItemFullyMatchesTheSalonBand() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        UUID existingDefId = UUID.randomUUID();

        Master master = salonMaster(masterId, salonId);
        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        ServiceDefinition existing =
                existingSalonRangeDefinition(existingDefId, salonId, type, "350.00", "1500.00", 60);

        var request = new BulkCreateServicesRequest(List.of(rangeItem(typeId, 60, "350.00", "1500.00")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        stubSalonCandidates(salonId, masterId, new SalonBulkSetupCandidate(typeId, existing, null, null));
        stubAssignmentSaveEchoesEntity();

        List<MasterServiceResponse> result =
                serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);

        assertThat(result).hasSize(1);

        ArgumentCaptor<MasterServiceAssignment> msaCaptor =
                ArgumentCaptor.forClass(MasterServiceAssignment.class);
        verify(masterServiceRepository).save(msaCaptor.capture());
        MasterServiceAssignment saved = msaCaptor.getValue();
        assertThat(saved.getPriceTypeOverride())
                .as("a fully-matching band needs no override — Inherited, tracking the definition")
                .isNull();
        assertThat(saved.getPriceOverride()).isNull();
        assertThat(saved.getPriceMaxOverride()).isNull();

        assertThat(result.get(0).effectivePrice())
                .as("effective price still resolves off the (matching) shared definition")
                .isEqualByComparingTo("350.00");
    }

    /**
     * The accept case whose floor DIVERGES (so it must write an own band even though its ceiling
     * happens to equal the salon's) — carries forward the fixture and floor-sourcing coverage from
     * the pre-312 {@code should_overrideWithRangeFloor_when_reusedRangeItemMatchesTheSalonCeiling}.
     *
     * <p>{@code resolveBulkReuseBand} sources the item's floor from {@code priceMin} for a RANGE
     * item and from {@code price} for a FIXED one, because Bean Validation makes the OTHER field
     * null in each mode. Every OTHER reuse test in this class submits a FIXED item, so all of them
     * stay green if that ternary is flattened to {@code item.price()}: a RANGE item would then
     * yield a null floor and either NPE or silently price this master at the salon definition's
     * own base price. This test is the only thing standing in the way of that mutant.
     */
    @Test
    @DisplayName("salon on-behalf — a RANGE item whose floor diverges but ceiling MATCHES the "
            + "salon band still stores a full OWN band (Phase 311 D2 all-or-nothing)")
    void should_storeOwnBand_when_reusedRangeItemFloorDivergesButCeilingMatches() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        UUID existingDefId = UUID.randomUUID();

        Master master = salonMaster(masterId, salonId);
        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        ServiceDefinition existing =
                existingSalonRangeDefinition(existingDefId, salonId, type, "350.00", "1500.00", 60);

        // RANGE, so item.price() is null by validation and only priceMin carries the floor.
        // Duration deliberately MATCHES, isolating the price assertion from the duration one.
        var request = new BulkCreateServicesRequest(List.of(rangeItem(typeId, 60, "800.00", "1500.00")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        stubSalonCandidates(salonId, masterId, new SalonBulkSetupCandidate(typeId, existing, null, null));
        stubAssignmentSaveEchoesEntity();

        List<MasterServiceResponse> result =
                serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);

        ArgumentCaptor<MasterServiceAssignment> msaCaptor =
                ArgumentCaptor.forClass(MasterServiceAssignment.class);
        verify(masterServiceRepository).save(msaCaptor.capture());
        MasterServiceAssignment saved = msaCaptor.getValue();
        assertThat(saved.getPriceTypeOverride())
                .as("floor diverges, so D2's all-or-nothing rule writes the WHOLE triple, even "
                        + "though the ceiling happens to equal the salon's")
                .isEqualTo(PriceType.RANGE);
        assertThat(saved.getPriceOverride())
                .as("a RANGE item's floor is priceMin — reading the (null) FIXED price field would "
                        + "drop the override and price this master at the salon's 350.00")
                .isEqualByComparingTo("800.00");
        assertThat(saved.getPriceMaxOverride()).isEqualByComparingTo("1500.00");
        assertThat(saved.getDurationOverrideMinutes())
                .as("the duration matched the definition, so no duration override is manufactured")
                .isNull();

        assertThat(existing.getPriceMax())
                .as("the shared band's ceiling is untouched — writing the item's copy onto the "
                        + "SHARED definition would rewrite the band for every other master")
                .isEqualByComparingTo("1500.00");
        assertThat(existing.getBasePrice())
                .as("and the shared floor is untouched")
                .isEqualByComparingTo("350.00");
        assertThat(existing.getPriceType()).isEqualTo(PriceType.RANGE);
        verify(serviceRepository, never()).save(any(ServiceDefinition.class));

        assertThat(result.get(0).effectivePrice())
                .as("the override is what the master's menu actually charges — the resolved band's "
                        + "own floor")
                .isEqualByComparingTo("800.00");
    }

    /**
     * The guard's compareTo-not-equals property, re-targeted at the Inherited decision now that
     * there is no rejection to over-trigger: {@code 1500} and {@code 1500.00} are the same money,
     * and a scale-only difference must still resolve to Inherited, not a spuriously-written own
     * band. Compared with {@code equals} instead of {@code compareTo}, this identical band would
     * be misread as diverging and would needlessly freeze a copy of the salon's own numbers.
     */
    @Test
    @DisplayName("salon on-behalf — a RANGE band matching the salon's only in SCALE (1500 vs "
            + "1500.00) still resolves to Inherited (compareTo, not equals)")
    void should_stayInherited_when_reusedRangeItemMatchesOnlyInScale() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        UUID existingDefId = UUID.randomUUID();

        Master master = salonMaster(masterId, salonId);
        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        ServiceDefinition existing =
                existingSalonRangeDefinition(existingDefId, salonId, type, "350.00", "1500.00", 60);

        var request = new BulkCreateServicesRequest(List.of(rangeItem(typeId, 60, "350", "1500")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        stubSalonCandidates(salonId, masterId, new SalonBulkSetupCandidate(typeId, existing, null, null));
        stubAssignmentSaveEchoesEntity();

        List<MasterServiceResponse> result =
                serviceCatalogService.bulkCreateSalonMasterServices(salonId, masterId, request);

        assertThat(result)
                .as("1500 and 1500.00 are the same band — must not be misread as diverging")
                .hasSize(1);

        ArgumentCaptor<MasterServiceAssignment> msaCaptor =
                ArgumentCaptor.forClass(MasterServiceAssignment.class);
        verify(masterServiceRepository).save(msaCaptor.capture());
        MasterServiceAssignment saved = msaCaptor.getValue();
        assertThat(saved.getPriceTypeOverride())
                .as("a scale-only difference must still resolve to Inherited, via compareTo")
                .isNull();
        assertThat(saved.getPriceOverride()).isNull();
        assertThat(saved.getPriceMaxOverride()).isNull();

        assertThat(existing.getPriceMax().toPlainString())
                .as("the shared definition keeps its own stored scale verbatim — untouched either way")
                .isEqualTo("1500.00");
    }


    // ── Additive: no "first-time only" precondition ────────────────────────────

    /**
     * Pins the additive contract. The bulk path used to reject any master who already had an
     * active service with a 409, which forced a second single-create screen in the app; that
     * precondition is gone, so a master with a populated menu can keep adding through the same
     * multi-select screen. Only a per-service {@code DUPLICATE_SERVICE} collision can 409 now.
     *
     * <p>The assertion is deliberately at the collaborator level — the service must never ask
     * whether the master already has services, because merely consulting that predicate is what
     * the removed precondition did.
     */
    @Test
    @DisplayName("adds to an existing catalogue — no menu-emptiness precondition is consulted")
    void should_createBatch_when_masterAlreadyHasActiveServices() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 60, "350.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        stubSaveEchoesEntities();

        List<MasterServiceResponse> result =
                serviceCatalogService.bulkCreateIndependentMasterServices(userId, request);

        assertThat(result).hasSize(1);
        verify(masterServiceRepository, never()).existsActiveServiceForMaster(any());
    }

    /**
     * The advisory lock survived the precondition removal, and its purpose shifted rather than
     * lapsed: {@code assertNoActiveDuplicatesInBatch} is still read-then-write, so two concurrent
     * additive batches for one master must serialize or both can read "type is free" and race to
     * the V121 index.
     *
     * <p><b>Ordering was deliberately INVERTED (backend-perf finding 2).</b> Type resolution and
     * category validation read GLOBAL reference data ({@code service_types},
     * {@code platform_categories}) — no owner-scoped state — so serializing them bought nothing
     * while holding the contended per-master lock across ~2 extra DB round-trips on every call.
     * They now run BEFORE the lock. What must stay inside the lock is the read-then-write span
     * the lock exists for, which starts at {@code assertNoActiveDuplicatesInBatch} — that is the
     * boundary this test now pins.
     */
    @Test
    @DisplayName("resolves service types BEFORE the lock, but takes the lock before the duplicate guard reads owner state")
    void should_acquireAdvisoryLockAfterResolvingTypesButBeforeDuplicateGuard_when_bulkCreating() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 60, "350.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        stubSaveEchoesEntities();

        serviceCatalogService.bulkCreateIndependentMasterServices(userId, request);

        InOrder inOrder = org.mockito.Mockito.inOrder(
                serviceTypeRepository, masterServiceRepository, serviceRepository);
        // Global reference-data reads happen OUTSIDE the lock…
        inOrder.verify(serviceTypeRepository).findAllById(anyList());
        // …then the lock opens the serialized window…
        inOrder.verify(masterServiceRepository).acquireBulkSetupLockWithTimeout(masterId);
        // …and the read-then-write duplicate guard runs strictly inside it. This last edge is the
        // one that must never regress: the guard reads owner-scoped state and then inserts, so it
        // is exactly what the lock has to serialize.
        inOrder.verify(serviceRepository).findActiveDuplicateTypeIds(any(), any(), any());
    }

    /**
     * A lock the DB refuses to confirm must abort the batch rather than proceed unserialized —
     * otherwise the failure mode is silent loss of the concurrency guarantee.
     */
    @Test
    @DisplayName("500 + nothing persisted when the advisory lock cannot be acquired")
    void should_abortBatch_when_advisoryLockAcquisitionFails() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 60, "350.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        when(masterServiceRepository.acquireBulkSetupLockWithTimeout(masterId)).thenReturn(null);

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);

        // Type resolution now precedes the lock (perf finding 2), so it is no longer a "never".
        // What must still hold is that a lock the DB refuses to confirm stops the batch BEFORE
        // the guarded critical section — no duplicate guard, no inserts.
        verify(serviceRepository, never()).findActiveDuplicateTypeIds(any(), any(), any());
        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
    }

    /**
     * A lock wait that blows past the fused 3s {@code lock_timeout} aborts with Postgres
     * {@code 55P03 lock_not_available}, which Spring/Hibernate exception translation surfaces as
     * {@link org.springframework.dao.CannotAcquireLockException}. It must become a clean,
     * retryable 503 rather than escaping as a raw data-access exception (which the generic
     * handler would render a 500).
     *
     * <p>503 — not the 409 {@code GlobalExceptionHandler#handlePessimisticLockingFailure} gives
     * every other lock site — is deliberate HERE: on this endpoint 409 is semantically reserved
     * for {@code DUPLICATE_SERVICE}, whose body carries {@code existingServiceDefId} for the
     * mobile deep-link. Surfacing "master is busy" as a second, payload-less 409 would make the
     * two indistinguishable to the client by status alone.
     */
    @Test
    @DisplayName("503 + nothing persisted when the lock wait exceeds lock_timeout (Postgres 55P03)")
    void should_return503AndPersistNothing_when_lockWaitTimesOut() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 60, "350.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        when(masterServiceRepository.acquireBulkSetupLockWithTimeout(masterId))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException(
                        "could not obtain lock on row",
                        new java.sql.SQLException("ERROR: canceling statement due to lock timeout", "55P03")));

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE);

        verify(serviceRepository, never()).findActiveDuplicateTypeIds(any(), any(), any());
        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
    }

    /**
     * The 503 body must not leak the SQL state, the driver's cause text, or the timeout value —
     * an operational detail oracle (§I/§N). Pins the sanitised message.
     */
    @Test
    @DisplayName("the lock-timeout 503 never echoes the SQL state, timeout value, or driver cause")
    void should_notEchoDriverDetail_when_lockWaitTimesOut() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 60, "350.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        when(masterServiceRepository.acquireBulkSetupLockWithTimeout(masterId))
                .thenThrow(new org.springframework.dao.CannotAcquireLockException(
                        "could not obtain lock on row",
                        new java.sql.SQLException("ERROR: canceling statement due to lock timeout", "55P03")));

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageNotContaining("55P03")
                .hasMessageNotContaining("lock_timeout")
                .hasMessageNotContaining("3s")
                .hasMessageNotContaining("canceling statement");
    }

    // ── Duplicate serviceTypeId in batch ───────────────────────────────────────

    @Test
    @DisplayName("400 + nothing persisted when the batch toggles the same serviceTypeId twice")
    void should_throwBadRequestAndPersistNothing_when_duplicateServiceTypeIdInBatch() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID dupTypeId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        var request = new BulkCreateServicesRequest(List.of(
                fixedItem(dupTypeId, 60, "350.00"),
                fixedItem(dupTypeId, 90, "500.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Duplicate service type");

        verify(serviceTypeRepository, never()).findAllById(any());
        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
    }

    // ── Duplicate against ALREADY-EXISTING services (V121 guard) ───────────────

    @Test
    @DisplayName("409 DUPLICATE_SERVICE + nothing persisted when a batch item duplicates a service the owner already has")
    void should_throwDuplicateServiceAndPersistNothing_when_bulkItemDuplicatesExistingService() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID freshTypeId = UUID.randomUUID();
        UUID takenTypeId = UUID.randomUUID();
        UUID existingDefId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        ServiceType freshType = serviceType(freshTypeId, "Манікюр", "NAIL_SERVICE", true);
        ServiceType takenType = serviceType(takenTypeId, "Класичне нарощення", "NAIL_SERVICE", true);

        var request = new BulkCreateServicesRequest(List.of(
                fixedItem(freshTypeId, 60, "350.00"),
                fixedItem(takenTypeId, 90, "500.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        // Bulk create is additive, so nothing about the master's existing menu blocks the call —
        // this DEFINITION-level guard is the only thing standing between the batch and the V121
        // index, including for an active definition that carries no active assignment (invisible
        // in the menu, still a collision at INSERT).
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(freshType, takenType));
        when(platformCategoryRepository.findSelectableNamesIn(any()))
                .thenReturn(List.of("NAIL_SERVICE"));
        // ONE batched lookup covering both items: item 1's type is free, item 2's is taken, so
        // only the taken one comes back. This is the whole point of the batched guard — a
        // per-item guard would have issued two round-trips here (and up to 100 on a full batch).
        when(serviceRepository.findActiveDuplicateTypeIds(
                OwnerType.INDEPENDENT_MASTER, masterId, java.util.Set.of(freshTypeId, takenTypeId)))
                .thenReturn(List.of(new ActiveDuplicateProjection(takenTypeId, existingDefId)));

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(DuplicateServiceException.class)
                .satisfies(ex -> {
                    DuplicateServiceException dup = (DuplicateServiceException) ex;
                    assertThat(dup.getExistingServiceDefId()).isEqualTo(existingDefId);
                    assertThat(dup.getServiceName()).isEqualTo("Класичне нарощення");
                });

        // The guard is consulted EXACTLY ONCE for the whole batch, before the persist loop —
        // this assertion is what a regression back to per-item findActiveDuplicateId would break.
        verify(serviceRepository, times(1)).findActiveDuplicateTypeIds(any(), any(), any());
        verify(serviceRepository, never()).findActiveDuplicateId(any(), any(), any(), any());

        // Running before the loop also means the colliding batch never reaches the DB at all:
        // strictly better than the old per-item behaviour, which persisted item 1 and relied on
        // the @Transactional rollback to discard it.
        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
        verify(serviceRepository, never()).flush();
        verify(masterServiceRepository, never()).save(any(MasterServiceAssignment.class));
    }

    @Test
    @DisplayName("409 DUPLICATE_SERVICE naming the FIRST colliding item in request order when several collide")
    void should_reportFirstCollidingItemInRequestOrder_when_multipleBulkItemsDuplicate() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID firstTakenTypeId = UUID.randomUUID();
        UUID secondTakenTypeId = UUID.randomUUID();
        UUID firstDefId = UUID.randomUUID();
        UUID secondDefId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        ServiceType firstTaken = serviceType(firstTakenTypeId, "Манікюр", "NAIL_SERVICE", true);
        ServiceType secondTaken = serviceType(secondTakenTypeId, "Класичне нарощення", "NAIL_SERVICE", true);

        var request = new BulkCreateServicesRequest(List.of(
                fixedItem(firstTakenTypeId, 60, "350.00"),
                fixedItem(secondTakenTypeId, 90, "500.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(firstTaken, secondTaken));
        when(platformCategoryRepository.findSelectableNamesIn(any()))
                .thenReturn(List.of("NAIL_SERVICE"));
        // The query's row order is unspecified — return the SECOND item's collision first to
        // prove the reported item is chosen by request order, not by result order. Otherwise two
        // identical requests could blame different items.
        when(serviceRepository.findActiveDuplicateTypeIds(any(), any(), any()))
                .thenReturn(List.of(
                        new ActiveDuplicateProjection(secondTakenTypeId, secondDefId),
                        new ActiveDuplicateProjection(firstTakenTypeId, firstDefId)));

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(DuplicateServiceException.class)
                .satisfies(ex -> {
                    DuplicateServiceException dup = (DuplicateServiceException) ex;
                    assertThat(dup.getServiceName()).isEqualTo("Манікюр");
                    assertThat(dup.getExistingServiceDefId()).isEqualTo(firstDefId);
                });
    }

    @Test
    @DisplayName("409 DUPLICATE_SERVICE when the batch flush loses the race the pre-check won")
    void should_translateIndexViolation_when_bulkFlushRaces() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 60, "350.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        stubSaveEchoesEntities();
        // Batched pre-check passes (default empty list), then a concurrent transaction commits
        // and the V121 index catches it at the single batch flush. The translation must survive
        // the move off per-item saveAndFlush, or the caller would get a generic 409 with no
        // data.code to branch on.
        org.mockito.Mockito.doThrow(DuplicateServiceViolations.violationOf(
                        DuplicateServiceViolations.DUPLICATE_SERVICE_INDEX))
                .when(serviceRepository).flush();

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(DuplicateServiceException.class)
                .satisfies(ex -> assertThat(((DuplicateServiceException) ex).getExistingServiceDefId())
                        .as("the index reports the constraint, not which queued row lost")
                        .isNull());
    }

    @Test
    @DisplayName("rethrows an unrelated integrity violation from the batch flush untouched")
    void should_rethrowUnrelatedIntegrityViolation_when_bulkFlushViolatesOtherConstraint() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        ServiceType type = serviceType(typeId, "Манікюр", "NAIL_SERVICE", true);
        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 60, "350.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of("NAIL_SERVICE"));
        stubSaveEchoesEntities();
        // Same builder, a DIFFERENT constraint: the negative case must differ from the positive
        // one only in the constraint name, or it would not isolate the classification branch.
        org.mockito.Mockito.doThrow(DuplicateServiceViolations.violationOf(
                        "service_definitions_service_type_id_fkey"))
                .when(serviceRepository).flush();

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .isNotInstanceOf(DuplicateServiceException.class);
    }

    // ── All-or-nothing: unknown / inactive serviceTypeId aborts the whole batch ─

    @Test
    @DisplayName("404 + nothing persisted when one item references a non-existent serviceTypeId (all-or-nothing)")
    void should_throwNotFoundAndPersistNothing_when_oneServiceTypeMissing() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID goodTypeId = UUID.randomUUID();
        UUID missingTypeId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        ServiceType goodType = serviceType(goodTypeId, "Манікюр", "NAIL_SERVICE", true);
        var request = new BulkCreateServicesRequest(List.of(
                fixedItem(goodTypeId, 60, "350.00"),
                fixedItem(missingTypeId, 90, "500.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        // findAllById returns only the existing type — the missing one is absent from the map.
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(goodType));

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("ServiceType not found");

        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
        verify(masterServiceRepository, never()).save(any());
    }

    @Test
    @DisplayName("400 + nothing persisted when one item references an inactive serviceTypeId (all-or-nothing)")
    void should_throwBadRequestAndPersistNothing_when_oneServiceTypeInactive() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID activeTypeId = UUID.randomUUID();
        UUID inactiveTypeId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        ServiceType activeType = serviceType(activeTypeId, "Манікюр", "NAIL_SERVICE", true);
        ServiceType inactiveType = serviceType(inactiveTypeId, "Старе", "NAIL_SERVICE", false);
        var request = new BulkCreateServicesRequest(List.of(
                fixedItem(activeTypeId, 60, "350.00"),
                fixedItem(inactiveTypeId, 90, "500.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(activeType, inactiveType));

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Service type is not active");

        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
    }

    @Test
    @DisplayName("400 + nothing persisted when a derived category is not APPROVED+active")
    void should_throwBadRequestAndPersistNothing_when_derivedCategoryNotSelectable() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        // Type resolves and is active, but its parent category is not in the selectable set.
        ServiceType type = serviceType(typeId, "Манікюр", "RETIRED_CATEGORY", true);
        var request = new BulkCreateServicesRequest(List.of(fixedItem(typeId, 60, "350.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        // Category lookup returns empty — the requested category is unknown/inactive.
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of());

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown category");

        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
    }

    // ── Authorization ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("403 when a non-INDEPENDENT_MASTER user calls the self bulk endpoint")
    void should_throwForbidden_when_selfBulkCalledByNonIndependentMaster() {
        UUID userId = UUID.randomUUID();
        Master salonMaster = org.mockito.Mockito.mock(Master.class);
        when(salonMaster.getMasterType()).thenReturn(MasterType.SALON_MASTER);

        var request = new BulkCreateServicesRequest(List.of(fixedItem(UUID.randomUUID(), 60, "350.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(salonMaster));

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(ForbiddenException.class);

        // The role check precedes the advisory lock: an unauthorized caller must not be able to
        // hold a per-master lock and stall the legitimate owner's batch.
        verify(masterServiceRepository, never()).acquireBulkSetupLockWithTimeout(any());
        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
    }

    @Test
    @DisplayName("403 when the on-behalf path targets a master who does not belong to the salon (IDOR guard half)")
    void should_throwForbidden_when_onBehalfTargetsMasterInAnotherSalon() {
        UUID salonAId = UUID.randomUUID();
        UUID salonBId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Salon salonB = org.mockito.Mockito.mock(Salon.class);
        when(salonB.getId()).thenReturn(salonBId);
        Master masterInSalonB = org.mockito.Mockito.mock(Master.class);
        when(masterInSalonB.getSalon()).thenReturn(salonB);

        var request = new BulkCreateServicesRequest(List.of(fixedItem(UUID.randomUUID(), 60, "350.00")));

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(masterInSalonB));

        // Owner of salon A tries to bulk-create for a master that lives in salon B.
        assertThatThrownBy(() -> serviceCatalogService.bulkCreateSalonMasterServices(salonAId, masterId, request))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied");

        // Salon-membership is checked before the advisory lock, so a cross-salon probe cannot
        // stall the real owner's batch on a lock it had no right to take.
        verify(masterServiceRepository, never()).acquireBulkSetupLockWithTimeout(any());
        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
    }

    @Test
    @DisplayName("404 when the on-behalf path targets a non-existent master")
    void should_throwNotFound_when_onBehalfTargetsMissingMaster() {
        UUID salonId = UUID.randomUUID();
        UUID missingMasterId = UUID.randomUUID();
        var request = new BulkCreateServicesRequest(List.of(fixedItem(UUID.randomUUID(), 60, "350.00")));

        when(masterRepository.findById(missingMasterId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateSalonMasterServices(salonId, missingMasterId, request))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Master not found");

        verify(serviceRepository, never()).save(any(ServiceDefinition.class));
    }
}
