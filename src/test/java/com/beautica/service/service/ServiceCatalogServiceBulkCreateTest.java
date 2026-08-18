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
 *   <li>Advisory lock: taken per master to serialise concurrent additive batches against the
 *       read-then-write duplicate guard. The serialised window is narrow — global
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
    @DisplayName("salon on-behalf — batch persists with ownerType=INDEPENDENT_MASTER + ownerId=master.id (services owned by the master row)")
    void should_createBatchOwnedByMasterRow_when_salonOnBehalfBulkSetup() {
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
                .as("salon-bound master services are owned by the master row, not the salon")
                .isEqualTo(OwnerType.INDEPENDENT_MASTER);
        assertThat(defCaptor.getValue().getOwnerId()).isEqualTo(masterId);
        assertThat(result).hasSize(1);

        // Parity with the self path: the shared additive core's post-write bookkeeping must run
        // for the on-behalf entry point too. Now that the endpoint is additive, this is reachable
        // on every later "add more services" pass — a stale min_effective_price would misprice the
        // salon master in search on every one of them, not just at first setup.
        verify(masterRepository).refreshMinEffectivePrice(masterId);
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
