package com.beautica.service.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.EmailService;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.SalonServiceCategoryGroup;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PlatformCategoryStatus;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.service.CatalogCategoryLookup;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceCatalogService — unit")
class ServiceCatalogServiceTest {

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private MasterServiceRepository masterServiceRepository;

    @Mock
    private SalonRepository salonRepository;

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private ServiceTypeLookup serviceTypeLookup;

    @Mock
    private ServiceTypeSearchService serviceTypeSearchService;

    @Mock
    private CatalogCategoryLookup catalogCategoryLookup;

    @Mock
    private PlatformCategoryRepository platformCategoryRepository;

    @Mock
    private PlatformCategoryOrderLookup platformCategoryOrderLookup;

    @Mock
    private EmailService emailService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private com.beautica.common.security.AuthorizationService authz;

    @InjectMocks
    private ServiceCatalogService serviceCatalogService;

    // ── helpers ────────────────────────────────────────────────────────────────

    private CreateServiceDefinitionRequest buildCreateRequest() {
        return new CreateServiceDefinitionRequest(
                "Manicure",
                "Classic manicure",
                null,
                60,
                10,
                PriceType.FIXED,
                new BigDecimal("350.00"),
                null,
                null,
                null
        );
    }

    private ServiceDefinition buildSavedDefinition(UUID salonId) {
        return ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(salonId)
                .name("Manicure")
                .baseDurationMinutes(60)
                .bufferMinutesAfter(10)
                .isActive(true)
                .build();
    }

    // ── addServiceToSalon ──────────────────────────────────────────────────────

    @Test
    @DisplayName("creates ServiceDefinition when owner adds a salon service")
    void should_createServiceDefinition_when_ownerAddsSalonService() {
        UUID salonId = UUID.randomUUID();

        ServiceDefinition savedDef = buildSavedDefinition(salonId);

        when(salonRepository.existsById(salonId)).thenReturn(true);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(savedDef);

        ServiceDefinitionResponse result = serviceCatalogService.addServiceToSalon(
                salonId, buildCreateRequest());

        assertThat(result).isNotNull();
        assertThat(result.id()).as("id must be populated from the saved entity").isNotNull();
        assertThat(result.name()).as("name must match the request input").isEqualTo("Manicure");
        assertThat(result.isActive()).as("newly created service definition must be active").isTrue();

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getOwnerType()).isEqualTo(OwnerType.SALON);
        assertThat(captor.getValue().getOwnerId()).isEqualTo(salonId);
    }

    @Test
    @DisplayName("throws NotFoundException when salon does not exist on service add")
    void should_throwNotFound_when_salonDoesNotExistOnServiceAdd() {
        UUID salonId = UUID.randomUUID();

        when(salonRepository.existsById(salonId)).thenReturn(false);

        assertThatThrownBy(() ->
                serviceCatalogService.addServiceToSalon(salonId, buildCreateRequest()))
                .isInstanceOf(NotFoundException.class);

        verify(serviceRepository, never()).save(any());
    }

    // ── assignServiceToMaster ──────────────────────────────────────────────────

    @Test
    @DisplayName("creates MasterServiceAssignment when request is valid")
    void should_createMasterServiceAssignment_when_validAssignRequest() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getSalon()).thenReturn(salon);

        ServiceDefinition serviceDef = mock(ServiceDefinition.class);
        when(serviceDef.getId()).thenReturn(serviceDefId);
        when(serviceDef.getOwnerType()).thenReturn(OwnerType.SALON);
        when(serviceDef.getOwnerId()).thenReturn(salonId);
        when(serviceDef.getBasePrice()).thenReturn(new BigDecimal("350.00"));
        when(serviceDef.getBaseDurationMinutes()).thenReturn(60);

        MasterServiceAssignment savedAssignment = mock(MasterServiceAssignment.class);
        when(savedAssignment.getId()).thenReturn(UUID.randomUUID());
        when(savedAssignment.getMaster()).thenReturn(master);
        when(savedAssignment.getServiceDefinition()).thenReturn(serviceDef);
        when(savedAssignment.isActive()).thenReturn(true);

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(serviceDef));
        when(masterServiceRepository.existsByMasterIdAndServiceDefinitionId(masterId, serviceDefId))
                .thenReturn(false);
        when(masterServiceRepository.save(any(MasterServiceAssignment.class))).thenReturn(savedAssignment);

        AssignServiceToMasterRequest request = new AssignServiceToMasterRequest(serviceDefId, null, null);

        MasterServiceResponse result = serviceCatalogService.assignServiceToMaster(
                salonId, masterId, request);

        assertThat(result).isNotNull();
        assertThat(result.masterId())
                .as("masterId on response must match the master used in the request")
                .isEqualTo(masterId);
        assertThat(result.serviceDefinition().id())
                .as("serviceDefinitionId on response must match the serviceDefId used in the request")
                .isEqualTo(serviceDefId);
        assertThat(result.isActive())
                .as("isActive on response must be true as returned by the saved assignment")
                .isTrue();
        verify(masterServiceRepository).save(any(MasterServiceAssignment.class));
        // MEDIUM-1: service must refresh the pre-computed min_effective_price index after saving the assignment.
        verify(masterRepository).refreshMinEffectivePrice(masterId);
    }

    @Test
    @DisplayName("does not throw LazyInitializationException and populates serviceTypeNameUk when the "
            + "assigned service definition has a serviceType (regression for the findById/findByIdWithServiceType bug)")
    void should_populateServiceTypeNameUk_when_assignedServiceDefinitionHasServiceType() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getSalon()).thenReturn(salon);

        // Mirrors production: ServiceDefinition.serviceType is a lazy @ManyToOne. In production this
        // would be an uninitialized Hibernate proxy unless the repository eagerly fetches it via
        // findByIdWithServiceType (LEFT JOIN FETCH). Mockito mocks can't reproduce the proxy/session
        // detachment itself, but this test pins the service to call findByIdWithServiceType (not the
        // plain findById) and asserts the resulting DTO is fully populated — if the service regresses
        // to findById, this stub is never hit, findByIdWithServiceType returns empty by default, and
        // the call throws NotFoundException instead of succeeding.
        ServiceType serviceType = mock(ServiceType.class);
        when(serviceType.getId()).thenReturn(serviceTypeId);
        when(serviceType.getNameUk()).thenReturn("Манікюр");

        ServiceDefinition serviceDef = mock(ServiceDefinition.class);
        when(serviceDef.getId()).thenReturn(serviceDefId);
        when(serviceDef.getOwnerType()).thenReturn(OwnerType.SALON);
        when(serviceDef.getOwnerId()).thenReturn(salonId);
        when(serviceDef.getBasePrice()).thenReturn(new BigDecimal("350.00"));
        when(serviceDef.getBaseDurationMinutes()).thenReturn(60);
        when(serviceDef.getServiceType()).thenReturn(serviceType);

        MasterServiceAssignment savedAssignment = mock(MasterServiceAssignment.class);
        when(savedAssignment.getId()).thenReturn(UUID.randomUUID());
        when(savedAssignment.getMaster()).thenReturn(master);
        when(savedAssignment.getServiceDefinition()).thenReturn(serviceDef);
        when(savedAssignment.isActive()).thenReturn(true);

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(serviceDef));
        when(masterServiceRepository.existsByMasterIdAndServiceDefinitionId(masterId, serviceDefId))
                .thenReturn(false);
        when(masterServiceRepository.save(any(MasterServiceAssignment.class))).thenReturn(savedAssignment);

        AssignServiceToMasterRequest request = new AssignServiceToMasterRequest(serviceDefId, null, null);

        MasterServiceResponse result = serviceCatalogService.assignServiceToMaster(
                salonId, masterId, request);

        assertThat(result).isNotNull();
        assertThat(result.serviceDefinition().serviceTypeId())
                .as("serviceTypeId must be lifted from the assigned service definition's serviceType")
                .isEqualTo(serviceTypeId);
        assertThat(result.serviceDefinition().serviceTypeNameUk())
                .as("serviceTypeNameUk must be populated, not null, when the service definition has a serviceType")
                .isEqualTo("Манікюр");
        assertThat(result.serviceTypeNameUk())
                .as("serviceTypeNameUk must also be lifted onto the top-level MasterServiceResponse")
                .isEqualTo("Манікюр");

        verify(serviceRepository).findByIdWithServiceType(serviceDefId);
        verify(serviceRepository, never()).findById(serviceDefId);
    }

    @Test
    @DisplayName("throws ForbiddenException when master belongs to a different salon")
    void should_throwForbidden_when_masterBelongsToAnotherSalon() {
        UUID salonId = UUID.randomUUID();
        UUID differentSalonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        Salon differentSalon = mock(Salon.class);
        when(differentSalon.getId()).thenReturn(differentSalonId);

        Master master = mock(Master.class);
        when(master.getSalon()).thenReturn(differentSalon);

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));

        AssignServiceToMasterRequest request = new AssignServiceToMasterRequest(serviceDefId, null, null);

        assertThatThrownBy(() ->
                serviceCatalogService.assignServiceToMaster(salonId, masterId, request))
                .isInstanceOf(ForbiddenException.class);

        verify(masterServiceRepository, never()).save(any());
    }

    @Test
    @DisplayName("throws ForbiddenException when serviceDefId belongs to a different salon (cross-salon injection)")
    void should_throwForbidden_when_serviceDefBelongsToDifferentSalon() {
        UUID salonId = UUID.randomUUID();
        UUID attackerSalonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        Master master = mock(Master.class);
        when(master.getSalon()).thenReturn(salon);

        // serviceDefId belongs to a *different* salon — the cross-salon injection vector
        ServiceDefinition foreignServiceDef = mock(ServiceDefinition.class);
        when(foreignServiceDef.getOwnerType()).thenReturn(OwnerType.SALON);
        when(foreignServiceDef.getOwnerId()).thenReturn(attackerSalonId);

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(foreignServiceDef));

        AssignServiceToMasterRequest request = new AssignServiceToMasterRequest(serviceDefId, null, null);

        assertThatThrownBy(() ->
                serviceCatalogService.assignServiceToMaster(salonId, masterId, request))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("does not belong to this salon");

        verify(masterServiceRepository, never()).save(any());
    }

    @Test
    @DisplayName("throws BusinessException with 409 when service already assigned to master")
    void should_throw409_when_serviceAlreadyAssignedToMaster() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        Master master = mock(Master.class);
        when(master.getSalon()).thenReturn(salon);

        ServiceDefinition serviceDef = mock(ServiceDefinition.class);
        when(serviceDef.getOwnerType()).thenReturn(OwnerType.SALON);
        when(serviceDef.getOwnerId()).thenReturn(salonId);

        when(masterRepository.findById(masterId)).thenReturn(Optional.of(master));
        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(serviceDef));
        when(masterServiceRepository.existsByMasterIdAndServiceDefinitionId(masterId, serviceDefId))
                .thenReturn(true);

        AssignServiceToMasterRequest request = new AssignServiceToMasterRequest(serviceDefId, null, null);

        assertThatThrownBy(() ->
                serviceCatalogService.assignServiceToMaster(salonId, masterId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(masterServiceRepository, never()).save(any());
    }

    // ── addIndependentMasterService ────────────────────────────────────────────

    @Test
    @DisplayName("creates both ServiceDefinition and MasterServiceAssignment when independent master adds a service")
    void should_createBothDefinitionAndAssignment_when_independentMasterAddsService() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID savedDefId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);

        ServiceDefinition savedDef = ServiceDefinition.builder()
                .id(savedDefId)
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(masterId)
                .name("Manicure")
                .baseDurationMinutes(60)
                .bufferMinutesAfter(10)
                .isActive(true)
                .build();

        MasterServiceAssignment savedAssignment = mock(MasterServiceAssignment.class);
        when(savedAssignment.getId()).thenReturn(UUID.randomUUID());
        when(savedAssignment.getMaster()).thenReturn(master);
        when(savedAssignment.getServiceDefinition()).thenReturn(savedDef);
        when(savedAssignment.isActive()).thenReturn(true);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(savedDef);
        when(masterServiceRepository.save(any(MasterServiceAssignment.class))).thenReturn(savedAssignment);

        MasterServiceResponse result = serviceCatalogService.addIndependentMasterService(
                userId, buildCreateRequest());

        assertThat(result).isNotNull();
        assertThat(result.masterId())
                .as("masterId on response must match the independent master's id")
                .isEqualTo(masterId);
        assertThat(result.serviceDefinition().id())
                .as("serviceDefinitionId on response must match the saved ServiceDefinition id")
                .isEqualTo(savedDefId);
        assertThat(result.isActive())
                .as("isActive on response must be true as returned by the saved assignment")
                .isTrue();
        verify(serviceRepository).save(any(ServiceDefinition.class));
        verify(masterServiceRepository).save(any(MasterServiceAssignment.class));
        // MEDIUM-2: service must refresh the pre-computed min_effective_price index for the independent master.
        verify(masterRepository).refreshMinEffectivePrice(masterId);
    }

    @Test
    @DisplayName("throws ForbiddenException when a salon master attempts to add an independent master service")
    void should_throwForbidden_when_salonMasterAddsIndependentMasterService() {
        UUID userId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));

        assertThatThrownBy(() ->
                serviceCatalogService.addIndependentMasterService(userId, buildCreateRequest()))
                .isInstanceOf(ForbiddenException.class);

        verify(serviceRepository, never()).save(any());
        verify(masterServiceRepository, never()).save(any());
    }

    // ── getMasterServices ──────────────────────────────────────────────────────

    @Test
    @DisplayName("returns empty list when master does not exist")
    void should_returnEmptyList_when_masterDoesNotExistOnGetMasterServices() {
        UUID masterId = UUID.randomUUID();

        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(any(), any(Pageable.class)))
                .thenReturn(List.of());

        List<MasterServiceResponse> result = serviceCatalogService.getMasterServices(masterId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("returns mapped list with correct fields when master exists and has active assignments")
    void should_returnMappedList_when_masterExistsWithActiveAssignments() {
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);

        ServiceDefinition serviceDef = ServiceDefinition.builder()
                .id(serviceDefId)
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(masterId)
                .name("Manicure")
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        MasterServiceAssignment assignment = mock(MasterServiceAssignment.class);
        when(assignment.getId()).thenReturn(assignmentId);
        when(assignment.getMaster()).thenReturn(master);
        when(assignment.getServiceDefinition()).thenReturn(serviceDef);
        when(assignment.isActive()).thenReturn(true);
        when(assignment.getPriceOverride()).thenReturn(null);
        when(assignment.getDurationOverrideMinutes()).thenReturn(null);

        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(any(), any(Pageable.class)))
                .thenReturn(List.of(assignment));

        List<MasterServiceResponse> result = serviceCatalogService.getMasterServices(masterId);

        assertThat(result).hasSize(1);
        MasterServiceResponse item = result.get(0);
        assertThat(item.masterId()).isEqualTo(masterId);
        assertThat(item.serviceDefinition().id()).isEqualTo(serviceDefId);
        assertThat(item.isActive()).isTrue();
    }

    // ── getMyServices ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("getMyServices resolves the master from userId and returns the master's active services")
    void should_returnActiveServices_when_getMyServicesForResolvedMaster() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        UUID assignmentId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));

        ServiceDefinition serviceDef = ServiceDefinition.builder()
                .id(serviceDefId)
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(masterId)
                .name("Manicure")
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        MasterServiceAssignment assignment = mock(MasterServiceAssignment.class);
        when(assignment.getId()).thenReturn(assignmentId);
        when(assignment.getMaster()).thenReturn(master);
        when(assignment.getServiceDefinition()).thenReturn(serviceDef);
        when(assignment.isActive()).thenReturn(true);
        when(assignment.getPriceOverride()).thenReturn(null);
        when(assignment.getDurationOverrideMinutes()).thenReturn(null);

        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(eq(masterId), any(Pageable.class)))
                .thenReturn(List.of(assignment));

        List<MasterServiceResponse> result = serviceCatalogService.getMyServices(userId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).masterId()).isEqualTo(masterId);
        assertThat(result.get(0).serviceDefinition().id()).isEqualTo(serviceDefId);
        assertThat(result.get(0).isActive()).isTrue();
    }

    @Test
    @DisplayName("getMyServices throws NotFoundException when no master exists for the userId")
    void should_throwNotFound_when_getMyServicesForUnknownUser() {
        UUID userId = UUID.randomUUID();
        when(masterRepository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceCatalogService.getMyServices(userId))
                .isInstanceOf(NotFoundException.class);

        verify(masterServiceRepository, never())
                .findByMasterIdAndIsActiveTrueWithGraph(any(), any(Pageable.class));
    }

    // ── deactivateServiceDefinition ────────────────────────────────────────────

    @Test
    @DisplayName("deactivates ServiceDefinition when actor is the owner and service definition exists")
    void should_deactivateServiceDefinition_when_serviceDefinitionExists() {
        UUID actorId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        UUID masterA = UUID.randomUUID();
        UUID masterB = UUID.randomUUID();

        // Owner passes the B14 service-layer ownership guard (void → default no-op).
        // MEDIUM-3: the service loads affected master IDs before the bulk UPDATE so it can
        // refresh their min_effective_price after deactivation — stub must be present.
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of(masterA, masterB));
        when(serviceRepository.deactivateById(serviceDefId)).thenReturn(1);

        serviceCatalogService.deactivateServiceDefinition(actorId, serviceDefId);

        // B14: ownership guard is invoked at method entry with the actor + target id.
        verify(authz).enforceCanManageServiceDefinition(actorId, serviceDefId);
        verify(serviceRepository).deactivateById(serviceDefId);
        // Fix MEDIUM-6: refreshMinEffectivePriceForAll collapses N round-trips into one bulk UPDATE.
        verify(masterRepository).refreshMinEffectivePriceForAll(List.of(masterA, masterB));
        // Single-ID variant must NOT be called — verifies the loop was replaced.
        verify(masterRepository, never()).refreshMinEffectivePrice(any());
    }

    @Test
    @DisplayName("throws NotFoundException when owner deactivates a service definition that does not exist")
    void should_throwNotFoundException_when_serviceDefinitionDoesNotExist() {
        UUID actorId = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(serviceRepository.deactivateById(missing)).thenReturn(0);

        assertThatThrownBy(() -> serviceCatalogService.deactivateServiceDefinition(actorId, missing))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(missing.toString());
    }

    @Test
    @DisplayName("uses deactivateById (bulk UPDATE) rather than save — deactivation must not trigger a full entity replace")
    void should_useDeactivateById_not_save_when_deactivating() {
        UUID actorId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        when(serviceRepository.deactivateById(serviceDefId)).thenReturn(1);

        serviceCatalogService.deactivateServiceDefinition(actorId, serviceDefId);

        verify(serviceRepository).deactivateById(serviceDefId);
        verify(serviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("B14: propagates ForbiddenException and performs no UPDATE or cache eviction when actor is NOT the owner")
    void should_throwForbiddenAndSkipUpdate_when_actorIsNotTheOwner() {
        UUID actorId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        // The B14 defense-in-depth guard rejects a non-owner actor at method entry, before any
        // cache-eviction registration or the deactivation UPDATE.
        org.mockito.Mockito.doThrow(new ForbiddenException("Access denied"))
                .when(authz).enforceCanManageServiceDefinition(actorId, serviceDefId);

        assertThatThrownBy(() -> serviceCatalogService.deactivateServiceDefinition(actorId, serviceDefId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied");

        // No state change: the UPDATE never runs, no affected-master lookup, no price refresh.
        verify(serviceRepository, never()).deactivateById(any());
        verify(masterServiceRepository, never()).findMasterIdsByServiceDefinitionId(any());
        verify(masterRepository, never()).refreshMinEffectivePriceForAll(any());
        verify(masterRepository, never()).refreshMinEffectivePrice(any());
        // No cache eviction registered/performed (guard throws before evict wiring).
        verify(cacheManager, never()).getCache(any());
    }

    // ── serviceType linkage ────────────────────────────────────────────────────

    @Test
    @DisplayName("links ServiceType when serviceTypeId is present in the request")
    void should_setServiceType_when_serviceTypeIdIsProvided() {
        UUID salonId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();

        ServiceType serviceType = mock(ServiceType.class);
        when(serviceType.getId()).thenReturn(serviceTypeId);
        when(serviceType.getNameUk()).thenReturn("Манікюр");
        when(serviceType.isActive()).thenReturn(true);
        when(serviceType.getPlatformCategoryName()).thenReturn("MANICURE");

        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "Manicure", "Classic manicure", "MANICURE", 60, 10, PriceType.FIXED, new BigDecimal("350.00"), null, null, serviceTypeId);

        ServiceDefinition savedDef = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(salonId)
                .name("Manicure")
                .baseDurationMinutes(60)
                .bufferMinutesAfter(10)
                .isActive(true)
                .build();
        savedDef.setServiceType(serviceType);

        when(salonRepository.existsById(salonId)).thenReturn(true);
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                "MANICURE", PlatformCategoryStatus.APPROVED)).thenReturn(true);
        when(serviceTypeLookup.getById(serviceTypeId)).thenReturn(serviceType);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(savedDef);

        ServiceDefinitionResponse result = serviceCatalogService.addServiceToSalon(salonId, request);

        assertThat(result.serviceTypeId()).isEqualTo(serviceTypeId);
        assertThat(result.serviceTypeNameUk()).isEqualTo("Манікюр");
        verify(serviceTypeLookup).getById(serviceTypeId);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getServiceType()).isSameAs(serviceType);
    }

    @Test
    @DisplayName("does not call ServiceTypeRepository when serviceTypeId is null")
    void should_notSetServiceType_when_serviceTypeIdIsNull() {
        UUID salonId = UUID.randomUUID();

        ServiceDefinition savedDef = buildSavedDefinition(salonId);

        when(salonRepository.existsById(salonId)).thenReturn(true);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(savedDef);

        serviceCatalogService.addServiceToSalon(salonId, buildCreateRequest());

        verify(serviceTypeLookup, never()).getById(any());
    }

    @Test
    @DisplayName("throws NotFoundException when serviceTypeId does not match any service type")
    void should_throwNotFoundException_when_serviceTypeIdNotFound() {
        UUID salonId = UUID.randomUUID();
        UUID unknownTypeId = UUID.randomUUID();

        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "Manicure", "Classic manicure", null, 60, 10, PriceType.FIXED, new BigDecimal("350.00"), null, null, unknownTypeId);

        when(salonRepository.existsById(salonId)).thenReturn(true);
        when(serviceTypeLookup.getById(unknownTypeId)).thenThrow(new NotFoundException("Service type not found: " + unknownTypeId));

        assertThatThrownBy(() -> serviceCatalogService.addServiceToSalon(salonId, request))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Service type not found");

        verify(serviceRepository, never()).save(any());
    }

    // ── serviceType linkage via addIndependentMasterService ───────────────────
    // applyServiceType() is also called from this path — these tests verify the
    // shared private helper is exercised through the independent master entry point.

    @Test
    @DisplayName("links ServiceType when serviceTypeId is present in addIndependentMasterService request")
    void should_setServiceType_when_independentMasterServiceCreatedWithServiceTypeId() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);

        ServiceType serviceType = mock(ServiceType.class);
        when(serviceType.getId()).thenReturn(serviceTypeId);
        when(serviceType.getNameUk()).thenReturn("Педикюр");
        when(serviceType.isActive()).thenReturn(true);
        when(serviceType.getPlatformCategoryName()).thenReturn("PEDICURE");

        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "Pedicure", "Basic pedicure", "PEDICURE", 60, 10, PriceType.FIXED, new BigDecimal("400.00"), null, null, serviceTypeId);

        ServiceDefinition savedDef = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(masterId)
                .name("Pedicure")
                .baseDurationMinutes(60)
                .bufferMinutesAfter(10)
                .isActive(true)
                .build();
        savedDef.setServiceType(serviceType);

        MasterServiceAssignment savedAssignment = mock(MasterServiceAssignment.class);
        when(savedAssignment.getId()).thenReturn(UUID.randomUUID());
        when(savedAssignment.getMaster()).thenReturn(master);
        when(savedAssignment.getServiceDefinition()).thenReturn(savedDef);
        when(savedAssignment.isActive()).thenReturn(true);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                "PEDICURE", PlatformCategoryStatus.APPROVED)).thenReturn(true);
        when(serviceTypeLookup.getById(serviceTypeId)).thenReturn(serviceType);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(savedDef);
        when(masterServiceRepository.save(any(MasterServiceAssignment.class))).thenReturn(savedAssignment);

        MasterServiceResponse result = serviceCatalogService.addIndependentMasterService(userId, request);

        assertThat(result).isNotNull();
        assertThat(result.masterId()).as("masterId must match the independent master").isEqualTo(masterId);
        assertThat(result.serviceDefinition().id())
                .as("serviceDefinitionId must be populated from the saved entity").isNotNull();
        assertThat(result.isActive()).as("newly assigned service must be active").isTrue();
        verify(serviceTypeLookup).getById(serviceTypeId);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getServiceType())
                .as("serviceType must be set on the ServiceDefinition before save")
                .isSameAs(serviceType);
    }

    @Test
    @DisplayName("throws NotFoundException when serviceTypeId not found via addIndependentMasterService")
    void should_throwNotFoundException_when_serviceTypeIdNotFound_viaIndependentMaster() {
        UUID userId = UUID.randomUUID();
        UUID unknownTypeId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);

        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "Pedicure", null, null, 60, 0, PriceType.FIXED, new BigDecimal("400.00"), null, null, unknownTypeId);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeLookup.getById(unknownTypeId)).thenThrow(new NotFoundException("Service type not found: " + unknownTypeId));

        assertThatThrownBy(() -> serviceCatalogService.addIndependentMasterService(userId, request))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Service type not found");

        verify(serviceRepository, never()).save(any());
        verify(masterServiceRepository, never()).save(any());
    }

    @Test
    @DisplayName("throws 400 when linked ServiceType is inactive — addServiceToSalon")
    void should_throw400_when_linkedServiceTypeIsInactive_addServiceToSalon() {
        UUID salonId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();

        ServiceType inactiveType = mock(ServiceType.class);
        when(inactiveType.isActive()).thenReturn(false);

        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "Manicure", "Classic manicure", null, 60, 10, PriceType.FIXED, new BigDecimal("350.00"), null, null, serviceTypeId);

        when(salonRepository.existsById(salonId)).thenReturn(true);
        when(serviceTypeLookup.getById(serviceTypeId)).thenReturn(inactiveType);

        assertThatThrownBy(() -> serviceCatalogService.addServiceToSalon(salonId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(serviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("throws 400 when linked ServiceType is inactive — addIndependentMasterService")
    void should_throw400_when_linkedServiceTypeIsInactive_addIndependentMasterService() {
        UUID userId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);

        ServiceType inactiveType = mock(ServiceType.class);
        when(inactiveType.isActive()).thenReturn(false);

        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "Lash Lift", "Full lash lift", null, 90, 15, PriceType.FIXED, new BigDecimal("600.00"), null, null, serviceTypeId);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeLookup.getById(serviceTypeId)).thenReturn(inactiveType);

        assertThatThrownBy(() -> serviceCatalogService.addIndependentMasterService(userId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(serviceRepository, never()).save(any());
        verify(masterServiceRepository, never()).save(any());
    }

    // ── Phase 16.3 cross-field create validation ─────────────────────────────────
    // A present serviceTypeId whose platform-category slug differs from the request
    // category must be rejected with a 400 BusinessException — the service type and the
    // selected top-level category must agree. validateCategoryActive runs BEFORE the
    // guard, so the REQUEST category must be stubbed active for the flow to reach it.

    @Test
    @DisplayName("throws 400 when serviceTypeId category differs from request category — addServiceToSalon")
    void should_throw400_when_serviceTypeCategoryMismatch_addServiceToSalon() {
        UUID salonId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();

        // Type belongs to HAIRCUT but the request selects MANICURE — cross-field mismatch.
        ServiceType serviceType = mock(ServiceType.class);
        when(serviceType.isActive()).thenReturn(true);
        when(serviceType.getPlatformCategoryName()).thenReturn("HAIRCUT");

        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "Manicure", "Classic manicure", "MANICURE", 60, 10,
                PriceType.FIXED, new BigDecimal("350.00"), null, null, serviceTypeId);

        when(salonRepository.existsById(salonId)).thenReturn(true);
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                "MANICURE", PlatformCategoryStatus.APPROVED)).thenReturn(true);
        when(serviceTypeLookup.getById(serviceTypeId)).thenReturn(serviceType);

        assertThatThrownBy(() -> serviceCatalogService.addServiceToSalon(salonId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .as("category mismatch must surface as 400 BAD_REQUEST")
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("does not belong to the selected category");

        verify(serviceRepository, never()).save(any());
    }

    // ── Phase 16.4: serviceTypeNameUk lifted onto the MasterServiceResponse create path ─────
    // 16.3 already proves the type is resolved/set on the entity; these focus on the response
    // SHAPE — the top-level serviceTypeId + serviceTypeNameUk fields the client reads back.

    @Test
    @DisplayName("addIndependentMasterService response carries serviceTypeId + serviceTypeNameUk when a type is chosen")
    void should_liftServiceTypeFieldsToResponse_when_independentMasterCreatesWithServiceType() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);

        ServiceType serviceType = mock(ServiceType.class);
        when(serviceType.getId()).thenReturn(serviceTypeId);
        when(serviceType.getNameUk()).thenReturn("Манікюр");
        when(serviceType.isActive()).thenReturn(true);
        when(serviceType.getPlatformCategoryName()).thenReturn("MANICURE");

        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "Manicure", "Classic manicure", "MANICURE", 60, 10,
                PriceType.FIXED, new BigDecimal("350.00"), null, null, serviceTypeId);

        // The saved ServiceDefinition carries the linked ServiceType — from() lifts it to the response.
        ServiceDefinition savedDef = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(masterId)
                .name("Manicure")
                .baseDurationMinutes(60)
                .bufferMinutesAfter(10)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("350.00"))
                .isActive(true)
                .build();
        savedDef.setServiceType(serviceType);

        MasterServiceAssignment savedAssignment = mock(MasterServiceAssignment.class);
        when(savedAssignment.getId()).thenReturn(UUID.randomUUID());
        when(savedAssignment.getMaster()).thenReturn(master);
        when(savedAssignment.getServiceDefinition()).thenReturn(savedDef);
        when(savedAssignment.isActive()).thenReturn(true);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                "MANICURE", PlatformCategoryStatus.APPROVED)).thenReturn(true);
        when(serviceTypeLookup.getById(serviceTypeId)).thenReturn(serviceType);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(savedDef);
        when(masterServiceRepository.save(any(MasterServiceAssignment.class))).thenReturn(savedAssignment);

        MasterServiceResponse result = serviceCatalogService.addIndependentMasterService(userId, request);

        assertThat(result.serviceTypeId())
                .as("top-level serviceTypeId must be lifted onto the create response")
                .isEqualTo(serviceTypeId);
        assertThat(result.serviceTypeNameUk())
                .as("top-level serviceTypeNameUk must carry the type's Ukrainian display name")
                .isEqualTo("Манікюр");
    }

    @Test
    @DisplayName("addIndependentMasterService response has null serviceTypeId + serviceTypeNameUk when no type is chosen")
    void should_nullServiceTypeFieldsOnResponse_when_independentMasterCreatesWithoutServiceType() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);

        // buildCreateRequest() has a null serviceTypeId — the picker was not used.
        ServiceDefinition savedDef = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(masterId)
                .name("Manicure")
                .baseDurationMinutes(60)
                .bufferMinutesAfter(10)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("350.00"))
                .isActive(true)
                .build();

        MasterServiceAssignment savedAssignment = mock(MasterServiceAssignment.class);
        when(savedAssignment.getId()).thenReturn(UUID.randomUUID());
        when(savedAssignment.getMaster()).thenReturn(master);
        when(savedAssignment.getServiceDefinition()).thenReturn(savedDef);
        when(savedAssignment.isActive()).thenReturn(true);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(savedDef);
        when(masterServiceRepository.save(any(MasterServiceAssignment.class))).thenReturn(savedAssignment);

        MasterServiceResponse result = serviceCatalogService.addIndependentMasterService(
                userId, buildCreateRequest());

        assertThat(result.serviceTypeId())
                .as("serviceTypeId must be null when no service type was chosen")
                .isNull();
        assertThat(result.serviceTypeNameUk())
                .as("serviceTypeNameUk must be null when no service type was chosen")
                .isNull();
        verify(serviceTypeLookup, never()).getById(any());
    }

    @Test
    @DisplayName("throws 400 when serviceTypeId category differs from request category — addIndependentMasterService")
    void should_throw400_when_serviceTypeCategoryMismatch_addIndependentMasterService() {
        UUID userId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);

        // Type belongs to HAIRCUT but the request selects MANICURE — cross-field mismatch.
        ServiceType serviceType = mock(ServiceType.class);
        when(serviceType.isActive()).thenReturn(true);
        when(serviceType.getPlatformCategoryName()).thenReturn("HAIRCUT");

        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "Manicure", "Classic manicure", "MANICURE", 60, 10,
                PriceType.FIXED, new BigDecimal("350.00"), null, null, serviceTypeId);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                "MANICURE", PlatformCategoryStatus.APPROVED)).thenReturn(true);
        when(serviceTypeLookup.getById(serviceTypeId)).thenReturn(serviceType);

        assertThatThrownBy(() -> serviceCatalogService.addIndependentMasterService(userId, request))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .as("category mismatch must surface as 400 BAD_REQUEST")
                        .isEqualTo(HttpStatus.BAD_REQUEST))
                .hasMessageContaining("does not belong to the selected category");

        verify(serviceRepository, never()).save(any());
        verify(masterServiceRepository, never()).save(any());
    }

    // ── Phase 16.4 blank-name defaulting on create ───────────────────────────────
    // name no longer @NotBlank: a null/blank name defaults to the selected service type's
    // nameUk; a blank name with no service type → 400 "Name or service type is required".
    // Blank is never persisted.

    @Test
    @DisplayName("defaults a blank name to the service type's nameUk on create — addServiceToSalon")
    void should_defaultBlankNameToTypeNameUk_onCreate() {
        UUID salonId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();

        ServiceType serviceType = mock(ServiceType.class);
        when(serviceType.isActive()).thenReturn(true);
        when(serviceType.getPlatformCategoryName()).thenReturn("MANICURE");
        when(serviceType.getNameUk()).thenReturn("Манікюр");

        // Whitespace-only name + a valid serviceTypeId — name must default to the type's nameUk.
        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "   ", "Classic manicure", "MANICURE", 60, 10,
                PriceType.FIXED, new BigDecimal("350.00"), null, null, serviceTypeId);

        when(salonRepository.existsById(salonId)).thenReturn(true);
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                "MANICURE", PlatformCategoryStatus.APPROVED)).thenReturn(true);
        when(serviceTypeLookup.getById(serviceTypeId)).thenReturn(serviceType);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        serviceCatalogService.addServiceToSalon(salonId, request);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getName())
                .as("blank name on create must default to the type's Ukrainian name — never persist blank")
                .isEqualTo("Манікюр");
    }

    @Test
    @DisplayName("defaults a null name to the service type's nameUk on create — addServiceToSalon")
    void should_defaultNullNameToTypeNameUk_onCreate() {
        UUID salonId = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();

        ServiceType serviceType = mock(ServiceType.class);
        when(serviceType.isActive()).thenReturn(true);
        when(serviceType.getPlatformCategoryName()).thenReturn("MANICURE");
        when(serviceType.getNameUk()).thenReturn("Манікюр");

        // name null + a valid serviceTypeId — name must default to the type's nameUk.
        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                null, "Classic manicure", "MANICURE", 60, 10,
                PriceType.FIXED, new BigDecimal("350.00"), null, null, serviceTypeId);

        when(salonRepository.existsById(salonId)).thenReturn(true);
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                "MANICURE", PlatformCategoryStatus.APPROVED)).thenReturn(true);
        when(serviceTypeLookup.getById(serviceTypeId)).thenReturn(serviceType);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));

        serviceCatalogService.addServiceToSalon(salonId, request);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getName())
                .as("null name on create must default to the type's Ukrainian name")
                .isEqualTo("Манікюр");
    }

    @Test
    @DisplayName("throws 400 'Name or service type is required' when name is blank and no service type is supplied on create")
    void should_throw400_when_blankNameAndNoServiceType_onCreate() {
        UUID salonId = UUID.randomUUID();

        // Blank name + null serviceTypeId — nothing to derive a name from.
        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "   ", "Classic manicure", "MANICURE", 60, 10,
                PriceType.FIXED, new BigDecimal("350.00"), null, null, null);

        when(salonRepository.existsById(salonId)).thenReturn(true);
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                "MANICURE", PlatformCategoryStatus.APPROVED)).thenReturn(true);

        assertThatThrownBy(() -> serviceCatalogService.addServiceToSalon(salonId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Name or service type is required")
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(serviceRepository, never()).save(any());
    }

    // ── getSalonServiceCatalog (Phase 13.6 — Public Salon Profile) ──────────────

    private ServiceDefinition buildCatalogDefinition(String category, String name) {
        return ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .name(name)
                .category(category)
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("300.00"))
                .build();
    }

    private com.beautica.service.entity.PlatformCategory approvedCategory(String name) {
        return com.beautica.service.entity.PlatformCategory.ofApproved(name, name);
    }

    private com.beautica.service.entity.PlatformCategory approvedCategory(String name, String displayName) {
        return com.beautica.service.entity.PlatformCategory.ofApproved(name, displayName);
    }

    @Test
    @DisplayName("groups services by category, one group per distinct category")
    void should_groupServicesByCategory_when_multipleCategoriesPresent() {
        UUID salonId = UUID.randomUUID();

        ServiceDefinition manicure1 = buildCatalogDefinition("MANICURE", "Classic manicure");
        ServiceDefinition manicure2 = buildCatalogDefinition("MANICURE", "Gel manicure");
        ServiceDefinition haircut = buildCatalogDefinition("HAIRCUT", "Women's haircut");

        when(serviceRepository.findBookableServicesBySalon(eq(salonId), any(Pageable.class)))
                .thenReturn(List.of(manicure1, manicure2, haircut));
        when(platformCategoryOrderLookup.getApprovedActive()).thenReturn(List.of());

        var result = serviceCatalogService.getSalonServiceCatalog(salonId);

        assertThat(result.categories()).hasSize(2);
        var manicureGroup = result.categories().stream()
                .filter(g -> g.category().equals("MANICURE")).findFirst().orElseThrow();
        assertThat(manicureGroup.count()).isEqualTo(2);
        assertThat(manicureGroup.services()).hasSize(2);
    }

    @Test
    @DisplayName("orders category groups using the approved+active platform-category display order")
    void should_orderCategories_byApprovedActiveDisplayOrder() {
        UUID salonId = UUID.randomUUID();

        ServiceDefinition haircut = buildCatalogDefinition("HAIRCUT", "Women's haircut");
        ServiceDefinition manicure = buildCatalogDefinition("MANICURE", "Classic manicure");

        // findApprovedActive is ORDER BY displayName — MANICURE listed before HAIRCUT here
        // to prove the group order follows THIS list's index, not alphabetical category order.
        when(serviceRepository.findBookableServicesBySalon(eq(salonId), any(Pageable.class)))
                .thenReturn(List.of(haircut, manicure));
        when(platformCategoryOrderLookup.getApprovedActive())
                .thenReturn(List.of(approvedCategory("MANICURE"), approvedCategory("HAIRCUT")));

        var result = serviceCatalogService.getSalonServiceCatalog(salonId);

        assertThat(result.categories())
                .extracting(SalonServiceCategoryGroup::category)
                .as("MANICURE must sort first — it has the lower index in findApprovedActive")
                .containsExactly("MANICURE", "HAIRCUT");
    }

    @Test
    @DisplayName("sorts a category with no match in the approved list alphabetically AFTER every known category")
    void should_sortUnknownCategoryAlphabeticallyAfterKnownCategories() {
        UUID salonId = UUID.randomUUID();

        ServiceDefinition legacy = buildCatalogDefinition("ZZZ_LEGACY", "Old-style service");
        ServiceDefinition manicure = buildCatalogDefinition("MANICURE", "Classic manicure");

        when(serviceRepository.findBookableServicesBySalon(eq(salonId), any(Pageable.class)))
                .thenReturn(List.of(legacy, manicure));
        // ZZZ_LEGACY is intentionally absent from the approved+active list.
        when(platformCategoryOrderLookup.getApprovedActive())
                .thenReturn(List.of(approvedCategory("MANICURE")));

        var result = serviceCatalogService.getSalonServiceCatalog(salonId);

        assertThat(result.categories())
                .extracting(SalonServiceCategoryGroup::category)
                .as("the known MANICURE category must sort before the unmatched ZZZ_LEGACY category")
                .containsExactly("MANICURE", "ZZZ_LEGACY");
    }

    @Test
    @DisplayName("resolves displayName from the matching approved+active platform category, not the raw category slug")
    void should_resolveDisplayName_fromMatchingApprovedActivePlatformCategory() {
        UUID salonId = UUID.randomUUID();

        ServiceDefinition haircut = buildCatalogDefinition("HAIRDRESSING", "Women's haircut");

        when(serviceRepository.findBookableServicesBySalon(eq(salonId), any(Pageable.class)))
                .thenReturn(List.of(haircut));
        // displayName deliberately differs from the category slug, so an accidental
        // "displayName == category" implementation (e.g. reusing the map key) would fail this.
        when(platformCategoryOrderLookup.getApprovedActive())
                .thenReturn(List.of(approvedCategory("HAIRDRESSING", "Перукарські послуги")));

        var result = serviceCatalogService.getSalonServiceCatalog(salonId);

        assertThat(result.categories()).hasSize(1);
        assertThat(result.categories().get(0).displayName())
                .as("HAIRDRESSING must resolve to its approved+active PlatformCategory.displayName")
                .isEqualTo("Перукарські послуги");
    }

    @Test
    @DisplayName("falls back to the raw category slug as displayName when the category has no approved+active match")
    void should_fallBackToRawCategorySlugAsDisplayName_when_categoryHasNoApprovedActiveMatch() {
        UUID salonId = UUID.randomUUID();

        ServiceDefinition legacy = buildCatalogDefinition("ZZZ_LEGACY", "Old-style service");

        when(serviceRepository.findBookableServicesBySalon(eq(salonId), any(Pageable.class)))
                .thenReturn(List.of(legacy));
        // ZZZ_LEGACY is intentionally absent from the approved+active list.
        when(platformCategoryOrderLookup.getApprovedActive())
                .thenReturn(List.of(approvedCategory("MANICURE", "Манікюр")));

        var result = serviceCatalogService.getSalonServiceCatalog(salonId);

        assertThat(result.categories()).hasSize(1);
        assertThat(result.categories().get(0).displayName())
                .as("an unmatched category must fall back to its own raw slug, never null/blank")
                .isEqualTo("ZZZ_LEGACY");
    }

    @Test
    @DisplayName("returns an empty category list when the salon has no bookable services")
    void should_returnEmptyCategories_when_salonHasNoBookableServices() {
        UUID salonId = UUID.randomUUID();

        when(serviceRepository.findBookableServicesBySalon(eq(salonId), any(Pageable.class)))
                .thenReturn(List.of());

        var result = serviceCatalogService.getSalonServiceCatalog(salonId);

        assertThat(result.categories()).isEmpty();
        verify(platformCategoryOrderLookup, never()).getApprovedActive();
    }

    @Test
    @DisplayName("reuses ServiceDefinitionResponse as the leaf DTO with fields mapped through")
    void should_mapServiceDefinitionFields_intoLeafDto() {
        UUID salonId = UUID.randomUUID();
        ServiceDefinition manicure = buildCatalogDefinition("MANICURE", "Classic manicure");

        when(serviceRepository.findBookableServicesBySalon(eq(salonId), any(Pageable.class)))
                .thenReturn(List.of(manicure));
        when(platformCategoryOrderLookup.getApprovedActive()).thenReturn(List.of());

        var result = serviceCatalogService.getSalonServiceCatalog(salonId);

        var leaf = result.categories().get(0).services().get(0);
        assertThat(leaf.id()).isEqualTo(manicure.getId());
        assertThat(leaf.name()).isEqualTo("Classic manicure");
        assertThat(leaf.category()).isEqualTo("MANICURE");
    }
}
