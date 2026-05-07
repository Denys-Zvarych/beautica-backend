package com.beautica.service.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
    private ServiceTypeRepository serviceTypeRepository;

    @InjectMocks
    private ServiceCatalogService serviceCatalogService;

    // ── helpers ────────────────────────────────────────────────────────────────

    private CreateServiceDefinitionRequest buildCreateRequest() {
        return new CreateServiceDefinitionRequest(
                "Manicure",
                "Classic manicure",
                null,
                60,
                new BigDecimal("350.00"),
                10,
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
        when(master.getSalon()).thenReturn(salon);

        ServiceDefinition serviceDef = mock(ServiceDefinition.class);
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
        when(serviceRepository.findById(serviceDefId)).thenReturn(Optional.of(serviceDef));
        when(masterServiceRepository.existsByMasterIdAndServiceDefinitionId(masterId, serviceDefId))
                .thenReturn(false);
        when(masterServiceRepository.save(any(MasterServiceAssignment.class))).thenReturn(savedAssignment);

        AssignServiceToMasterRequest request = new AssignServiceToMasterRequest(serviceDefId, null, null);

        MasterServiceResponse result = serviceCatalogService.assignServiceToMaster(
                salonId, masterId, request);

        assertThat(result).isNotNull();
        verify(masterServiceRepository).save(any(MasterServiceAssignment.class));
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
        when(serviceRepository.findById(serviceDefId)).thenReturn(Optional.of(foreignServiceDef));

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
        when(serviceRepository.findById(serviceDefId)).thenReturn(Optional.of(serviceDef));
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

        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);

        ServiceDefinition savedDef = ServiceDefinition.builder()
                .id(UUID.randomUUID())
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
        verify(serviceRepository).save(any(ServiceDefinition.class));
        verify(masterServiceRepository).save(any(MasterServiceAssignment.class));
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

    // ── deactivateServiceDefinition ────────────────────────────────────────────

    @Test
    @DisplayName("deactivates ServiceDefinition when requested")
    void should_deactivateServiceDefinition_when_ownerRequests() {
        UUID serviceDefId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        ServiceDefinition definition = ServiceDefinition.builder()
                .id(serviceDefId)
                .ownerType(OwnerType.SALON)
                .ownerId(salonId)
                .name("Manicure")
                .baseDurationMinutes(60)
                .bufferMinutesAfter(0)
                .isActive(true)
                .build();

        when(serviceRepository.findById(serviceDefId)).thenReturn(Optional.of(definition));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(definition);

        serviceCatalogService.deactivateServiceDefinition(serviceDefId);

        assertThat(definition.isActive()).isFalse();
        verify(serviceRepository).save(definition);
    }

    @Test
    @DisplayName("throws NotFoundException when service definition does not exist")
    void should_throwNotFoundException_when_serviceDefinitionDoesNotExist() {
        UUID missing = UUID.randomUUID();
        when(serviceRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceCatalogService.deactivateServiceDefinition(missing))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(missing.toString());
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

        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "Manicure", "Classic manicure", null, 60, new BigDecimal("350.00"), 10, serviceTypeId);

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
        when(serviceTypeRepository.findById(serviceTypeId)).thenReturn(Optional.of(serviceType));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(savedDef);

        ServiceDefinitionResponse result = serviceCatalogService.addServiceToSalon(salonId, request);

        assertThat(result.serviceTypeId()).isEqualTo(serviceTypeId);
        assertThat(result.serviceTypeNameUk()).isEqualTo("Манікюр");
        verify(serviceTypeRepository).findById(serviceTypeId);

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

        verify(serviceTypeRepository, never()).findById(any());
    }

    @Test
    @DisplayName("throws NotFoundException when serviceTypeId does not match any service type")
    void should_throwNotFoundException_when_serviceTypeIdNotFound() {
        UUID salonId = UUID.randomUUID();
        UUID unknownTypeId = UUID.randomUUID();

        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "Manicure", "Classic manicure", null, 60, new BigDecimal("350.00"), 10, unknownTypeId);

        when(salonRepository.existsById(salonId)).thenReturn(true);
        when(serviceTypeRepository.findById(unknownTypeId)).thenReturn(Optional.empty());

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

        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "Pedicure", "Basic pedicure", null, 60, new BigDecimal("400.00"), 10, serviceTypeId);

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
        when(serviceTypeRepository.findById(serviceTypeId)).thenReturn(Optional.of(serviceType));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(savedDef);
        when(masterServiceRepository.save(any(MasterServiceAssignment.class))).thenReturn(savedAssignment);

        MasterServiceResponse result = serviceCatalogService.addIndependentMasterService(userId, request);

        assertThat(result).isNotNull();
        verify(serviceTypeRepository).findById(serviceTypeId);

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
                "Pedicure", null, null, 60, new BigDecimal("400.00"), 0, unknownTypeId);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceTypeRepository.findById(unknownTypeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> serviceCatalogService.addIndependentMasterService(userId, request))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Service type not found");

        verify(serviceRepository, never()).save(any());
        verify(masterServiceRepository, never()).save(any());
    }
}
