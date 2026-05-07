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
import com.beautica.service.repository.CatalogCategoryRepository;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ServiceCatalogService {

    private final ServiceRepository serviceRepository;
    private final MasterServiceRepository masterServiceRepository;
    private final SalonRepository salonRepository;
    private final MasterRepository masterRepository;
    private final ServiceTypeRepository serviceTypeRepository;
    private final CatalogCategoryRepository catalogCategoryRepository;
    private final EmailService emailService;

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
    public MasterServiceResponse assignServiceToMaster(
            UUID salonId,
            UUID masterId,
            AssignServiceToMasterRequest request) {

        Master master = masterRepository.findById(masterId)
                .orElseThrow(() -> new NotFoundException("Master not found: " + masterId));

        if (master.getSalon() == null || !master.getSalon().getId().equals(salonId)) {
            throw new ForbiddenException("Master does not belong to salon: " + salonId);
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
    public List<MasterServiceResponse> getMasterServices(UUID masterId) {
        if (!masterRepository.existsById(masterId)) {
            throw new NotFoundException("Master not found: " + masterId);
        }

        return masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(masterId)
                .stream()
                .map(MasterServiceResponse::from)
                .toList();
    }

    @Transactional
    public void deactivateServiceDefinition(UUID ownerId, UUID serviceDefId) {
        ServiceDefinition definition = serviceRepository.findById(serviceDefId)
                .orElseThrow(() -> new NotFoundException("Service definition not found: " + serviceDefId));

        if (definition.getOwnerType() == OwnerType.SALON) {
            Salon salon = salonRepository.findById(definition.getOwnerId())
                    .orElseThrow(() -> new NotFoundException("Salon not found: " + definition.getOwnerId()));
            if (!salon.getOwner().getId().equals(ownerId)) {
                throw new ForbiddenException("You do not own this service definition");
            }
        } else {
            // INDEPENDENT_MASTER: ownerId on definition is master.id; resolve back to user
            Master master = masterRepository.findById(definition.getOwnerId())
                    .orElseThrow(() -> new NotFoundException("Master not found: " + definition.getOwnerId()));
            if (!master.getUser().getId().equals(ownerId)) {
                throw new ForbiddenException("You do not own this service definition");
            }
        }

        definition.setActive(false);
        serviceRepository.save(definition);
    }

    @Cacheable("service-categories")
    @Transactional(readOnly = true)
    public List<CatalogCategoryResponse> getCategories() {
        return catalogCategoryRepository.findAllByOrderBySortOrderAsc()
                .stream()
                .map(CatalogCategoryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ServiceTypeResponse> searchServiceTypes(@Nullable UUID categoryId, @Nullable String q) {
        boolean useSearch = q != null && q.strip().length() >= 2;

        if (useSearch) {
            return searchServiceTypesByName(q.strip(), categoryId);
        }
        return getCachedServiceTypes(categoryId);
    }

    @Cacheable(value = "service-types", key = "#categoryId")
    @Transactional(readOnly = true)
    public List<ServiceTypeResponse> getCachedServiceTypes(@Nullable UUID categoryId) {
        List<ServiceType> types = (categoryId != null)
                ? serviceTypeRepository.findByCategoryWithCategory(categoryId)
                : serviceTypeRepository.findAllActiveWithCategory();
        return types.stream()
                .map(ServiceTypeResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ServiceTypeResponse> searchServiceTypesByName(String q, @Nullable UUID categoryId) {
        List<ServiceType> types = serviceTypeRepository.searchByName(q);
        if (categoryId != null) {
            types = types.stream()
                    .filter(t -> t.getCategory().getId().equals(categoryId))
                    .toList();
        }
        return types.stream()
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
        ServiceType type = serviceTypeRepository.findById(request.serviceTypeId())
                .orElseThrow(() -> new NotFoundException("Service type not found: " + request.serviceTypeId()));
        definition.setServiceType(type);
    }
}
