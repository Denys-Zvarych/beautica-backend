package com.beautica.service.service;

import com.beautica.common.exception.BusinessException;
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
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
 * Unit tests for the first-time bulk service-setup paths in {@link ServiceCatalogService}
 * ({@code bulkCreateIndependentMasterServices} + {@code bulkCreateSalonMasterServices}).
 *
 * <p>Covers — at the service layer, with all collaborators mocked — the behaviour the
 * controller slice and integration test cannot observe cheaply:
 * <ul>
 *   <li>Happy path: a mixed FIXED + RANGE batch persists one ServiceDefinition +
 *       MasterServiceAssignment per item, with name/category derived from the ServiceType
 *       and {@code base_price = priceMin} for RANGE items.</li>
 *   <li>409 first-time precondition: an existing active service aborts the batch before
 *       anything is resolved or persisted.</li>
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
@DisplayName("ServiceCatalogService — bulk first-time setup")
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

    /** Echoes the saved definition back with a generated id so MasterServiceResponse.from can map it. */
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
        when(masterServiceRepository.existsActiveServiceForMaster(masterId)).thenReturn(false);
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
        when(masterServiceRepository.existsActiveServiceForMaster(masterId)).thenReturn(false);
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
    }

    // ── 409 first-time precondition ────────────────────────────────────────────

    @Test
    @DisplayName("409 + nothing persisted when the master already has an active service (self path)")
    void should_throwConflictAndPersistNothing_when_masterAlreadyHasActiveService() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Master master = independentMaster(masterId);

        var request = new BulkCreateServicesRequest(List.of(fixedItem(UUID.randomUUID(), 60, "350.00")));

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(masterServiceRepository.existsActiveServiceForMaster(masterId)).thenReturn(true);

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getStatus())
                .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);

        verify(serviceRepository, never()).save(any());
        verify(masterServiceRepository, never()).save(any());
        verify(serviceTypeRepository, never()).findAllById(any());
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
        when(masterServiceRepository.existsActiveServiceForMaster(masterId)).thenReturn(false);

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Duplicate service type");

        verify(serviceTypeRepository, never()).findAllById(any());
        verify(serviceRepository, never()).save(any());
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
        when(masterServiceRepository.existsActiveServiceForMaster(masterId)).thenReturn(false);
        // findAllById returns only the existing type — the missing one is absent from the map.
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(goodType));

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("ServiceType not found");

        verify(serviceRepository, never()).save(any());
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
        when(masterServiceRepository.existsActiveServiceForMaster(masterId)).thenReturn(false);
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(activeType, inactiveType));

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Service type is not active");

        verify(serviceRepository, never()).save(any());
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
        when(masterServiceRepository.existsActiveServiceForMaster(masterId)).thenReturn(false);
        when(serviceTypeRepository.findAllById(anyList())).thenReturn(List.of(type));
        // Category lookup returns empty — the requested category is unknown/inactive.
        when(platformCategoryRepository.findSelectableNamesIn(any())).thenReturn(List.of());

        assertThatThrownBy(() -> serviceCatalogService.bulkCreateIndependentMasterServices(userId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown category");

        verify(serviceRepository, never()).save(any());
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

        verify(masterServiceRepository, never()).existsActiveServiceForMaster(any());
        verify(serviceRepository, never()).save(any());
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

        verify(masterServiceRepository, never()).existsActiveServiceForMaster(any());
        verify(serviceRepository, never()).save(any());
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

        verify(serviceRepository, never()).save(any());
    }
}
