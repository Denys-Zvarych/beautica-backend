package com.beautica.service.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.EmailService;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.dto.AssignServiceToMasterRequest;
import com.beautica.service.dto.CatalogCategoryResponse;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.MasterServiceResponse;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.dto.ServiceTypeResponse;
import com.beautica.service.dto.SuggestServiceTypeRequest;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ServiceCatalogService {

    private final ServiceRepository serviceRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final SalonRepository salonRepository;
    private final MasterRepository masterRepository;
    private final CatalogCategoryLookup catalogCategoryLookup;
    private final EmailService emailService;
    private final ServiceTypeLookup serviceTypeLookup;
    private final ServiceTypeSearchService serviceTypeSearchService;

    @Value("${app.admin-email}")
    private String adminEmail;

    @Transactional
    public ServiceDefinitionResponse addServiceToSalon(
            UUID salonId,
            CreateServiceDefinitionRequest request) {

        if (!salonRepository.existsById(salonId)) {
            throw new NotFoundException("Salon not found: " + salonId);
        }

        ServiceDefinition definition = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salonId)
                .name(request.name())
                .description(request.description())
                .category(request.category())
                .baseDurationMinutes(request.baseDurationMinutes())
                .basePrice(request.basePrice())
                .bufferMinutesAfter(request.bufferMinutesAfter())
                .isActive(true)
                .build();

        applyServiceType(definition, request);

        ServiceDefinition saved = serviceRepository.save(definition);
        return ServiceDefinitionResponse.from(saved);
    }

    @Transactional
    @CacheEvict(value = "masterServices", key = "#masterId")
    public MasterServiceResponse assignServiceToMaster(
            UUID salonId,
            UUID masterId,
            AssignServiceToMasterRequest request) {

        Master master = masterRepository.findById(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found: " + masterId));

        if (master.getSalon() == null || !master.getSalon().getId().equals(salonId)) {
            throw new ForbiddenException("Access denied");
        }

        ServiceDefinition serviceDef = serviceRepository.findById(request.serviceDefId())
                .orElseThrow(() -> new NotFoundException("Service definition not found: " + request.serviceDefId()));

        if (serviceDef.getOwnerType() != OwnerType.SALON || !serviceDef.getOwnerId().equals(salonId)) {
            throw new ForbiddenException("Service definition does not belong to this salon");
        }

        if (masterServiceRepository.existsByMasterIdAndServiceDefinitionId(masterId, request.serviceDefId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Service already assigned to this master");
        }

        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDef)
                .priceOverride(request.priceOverride())
                .durationOverrideMinutes(request.durationOverrideMinutes())
                .isActive(true)
                .build();

        MasterServiceAssignment saved = masterServiceRepository.save(assignment);
        return MasterServiceResponse.from(saved);
    }

    @Transactional
    @CacheEvict(value = "masterServices", allEntries = true)
    public MasterServiceResponse addIndependentMasterService(
            UUID userId,
            CreateServiceDefinitionRequest request) {

        Master master = masterRepository.findByUserId(userId)
                .orElseThrow(() -> new NotFoundException("Master not found for user: " + userId));

        if (master.getMasterType() != MasterType.INDEPENDENT_MASTER) {
            throw new ForbiddenException("Only independent masters can add their own services");
        }

        ServiceDefinition definition = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name(request.name())
                .description(request.description())
                .category(request.category())
                .baseDurationMinutes(request.baseDurationMinutes())
                .basePrice(request.basePrice())
                .bufferMinutesAfter(request.bufferMinutesAfter())
                .isActive(true)
                .build();

        applyServiceType(definition, request);

        ServiceDefinition savedDef = serviceRepository.save(definition);

        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(savedDef)
                .isActive(true)
                .build();

        MasterServiceAssignment savedAssignment = masterServiceRepository.save(assignment);
        return MasterServiceResponse.from(savedAssignment);
    }

    @Transactional(readOnly = true)
    @Cacheable(value = "masterServices", key = "#masterId")
    public List<MasterServiceResponse> getMasterServices(UUID masterId) {
        // An unknown masterId produces an empty list — the existsById check was a
        // redundant DB round-trip because the JOIN FETCH graph query already returns
        // nothing for a non-existent master.
        return masterServiceRepository
                .findByMasterIdAndIsActiveTrueWithGraph(masterId, PageRequest.of(0, 200))
                .stream()
                .map(MasterServiceResponse::from)
                .toList();
    }

    @Transactional
    @CacheEvict(value = "masterServices", allEntries = true)
    // Ownership verified by @PreAuthorize("@authz.canManageServiceDefinition") on the controller — any future caller must enforce the same guard.
    public void deactivateServiceDefinition(UUID serviceDefId) {
        int updated = serviceRepository.deactivateById(serviceDefId);
        if (updated == 0) {
            throw new NotFoundException("Service definition not found: " + serviceDefId);
        }
    }

    @Transactional(readOnly = true)
    public List<CatalogCategoryResponse> getCategories() {
        return catalogCategoryLookup.getAll();
    }

    @Transactional(readOnly = true)
    public List<ServiceTypeResponse> searchServiceTypes(@Nullable UUID categoryId, @Nullable String q) {
        if (categoryId != null) {
            boolean exists = catalogCategoryLookup.getAll().stream()
                    .anyMatch(c -> c.id().equals(categoryId));
            if (!exists) throw new NotFoundException("Category not found");
        }
        // Intentional duplication of the controller's @Size(min=3) constraint: this guard
        // defends non-HTTP callers (internal services, tests, future programmatic callers)
        // where the Bean Validation boundary is not active. Removing it would silently allow
        // short queries to exhaust cache slots via direct service invocation.
        boolean useSearch = q != null && q.strip().length() >= 3;

        if (useSearch) {
            // Delegate through serviceTypeSearchService (a separate Spring bean) so that
            // the @Cacheable proxy intercept is active. A direct this.method() call would
            // bypass the AOP proxy and make caching inert.
            return serviceTypeSearchService.searchByName(q.strip().toLowerCase(Locale.ROOT), categoryId);
        }
        return serviceTypeLookup.getByCategory(categoryId).stream()
                .map(ServiceTypeResponse::from)
                .toList();
    }

    public void suggestServiceType(SuggestServiceTypeRequest request, UUID requestedByUserId) {
        String safeName = sanitizeEmailField(request.name());
        String safeDescription = request.description() != null
                ? sanitizeEmailField(request.description()) : "—";

        String subject = "Beautica: Запит нового типу послуги — " + safeName;
        String body = String.format(
                "Від: %s (userId: %s)%nКатегорія ID: %s%nНазва: %s%nОпис: %s",
                requestedByUserId, requestedByUserId, request.categoryId(),
                safeName, safeDescription
        );
        emailService.sendAdminNotification(adminEmail, subject, body);
    }

    private static String sanitizeEmailField(String value) {
        if (value == null) return "";
        return value.replaceAll("[\r\n\t]", " ").strip();
    }

    private void applyServiceType(ServiceDefinition definition, CreateServiceDefinitionRequest request) {
        if (request.serviceTypeId() == null) {
            return;
        }
        ServiceType type = serviceTypeLookup.getById(request.serviceTypeId());
        if (!type.isActive()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Service type is not active");
        }
        definition.setServiceType(type);
    }
}
