package com.beautica.service.service;

import com.beautica.config.CacheConfig;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.EmailService;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.CatalogCategoryRepository;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;

import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = {ServiceCatalogService.class, ServiceTypeLookup.class, ServiceTypeSearchService.class, CatalogCategoryLookup.class, CacheConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.NONE
)
@DisplayName("ServiceCatalogService — @Cacheable/@CacheEvict behaviour")
class ServiceCatalogServiceCacheTest {

    @MockBean ServiceRepository serviceRepository;
    @MockBean MasterServiceRepository masterServiceRepository;
    @MockBean SalonRepository salonRepository;
    @MockBean MasterRepository masterRepository;
    @MockBean ServiceTypeRepository serviceTypeRepository;
    @MockBean CatalogCategoryRepository catalogCategoryRepository;
    @MockBean EmailService emailService;

    @Autowired ServiceCatalogService serviceCatalogService;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCache() {
        cacheManager.getCache("masterServices").clear();
        cacheManager.getCache("service-type-by-id").clear();
        cacheManager.getCache("service-types").clear();
        cacheManager.getCache("service-type-search").clear();
        cacheManager.getCache("service-categories").clear();
    }

    @Test
    @DisplayName("second call to getMasterServices returns cached result without hitting repository")
    void should_notHitRepository_when_getMasterServicesCalledTwice() {
        UUID masterId = UUID.randomUUID();
        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(eq(masterId), any(Pageable.class)))
                .thenReturn(List.of());

        serviceCatalogService.getMasterServices(masterId);
        serviceCatalogService.getMasterServices(masterId);

        verify(masterServiceRepository, times(1))
                .findByMasterIdAndIsActiveTrueWithGraph(eq(masterId), any(Pageable.class));
    }

    @Test
    @DisplayName("deactivateServiceDefinition evicts all masterServices cache entries so next getMasterServices re-queries")
    void should_evictCache_when_deactivateServiceDefinitionCalled() {
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(eq(masterId), any(Pageable.class)))
                .thenReturn(List.of());
        when(serviceRepository.deactivateById(serviceDefId)).thenReturn(1);

        // Populate cache
        serviceCatalogService.getMasterServices(masterId);
        // Evict all masterServices entries
        serviceCatalogService.deactivateServiceDefinition(serviceDefId);
        // Cache was evicted — repository must be queried again
        serviceCatalogService.getMasterServices(masterId);

        verify(masterServiceRepository, times(2))
                .findByMasterIdAndIsActiveTrueWithGraph(eq(masterId), any(Pageable.class));
    }

    @Test
    @DisplayName("second addServiceToSalon with same serviceTypeId hits cache — serviceTypeRepository.findById called once")
    void should_hitCache_when_addServiceToSalonCalledTwiceWithSameServiceTypeId() {
        UUID salonId1 = UUID.randomUUID();
        UUID salonId2 = UUID.randomUUID();
        UUID serviceTypeId = UUID.randomUUID();

        ServiceType serviceType = ServiceType.builder()
                .id(serviceTypeId)
                .nameUk("Манікюр")
                .slug("manicure")
                .active(true)
                .build();

        ServiceDefinition savedDef1 = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(salonId1)
                .name("Manicure")
                .baseDurationMinutes(60)
                .bufferMinutesAfter(10)
                .isActive(true)
                .build();

        ServiceDefinition savedDef2 = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(salonId2)
                .name("Manicure")
                .baseDurationMinutes(60)
                .bufferMinutesAfter(10)
                .isActive(true)
                .build();

        CreateServiceDefinitionRequest request = new CreateServiceDefinitionRequest(
                "Manicure", "Classic manicure", null, 60, new BigDecimal("350.00"), 10, serviceTypeId);

        when(serviceTypeRepository.findById(serviceTypeId)).thenReturn(Optional.of(serviceType));
        when(salonRepository.existsById(salonId1)).thenReturn(true);
        when(salonRepository.existsById(salonId2)).thenReturn(true);
        when(serviceRepository.save(any(ServiceDefinition.class)))
                .thenReturn(savedDef1)
                .thenReturn(savedDef2);

        serviceCatalogService.addServiceToSalon(salonId1, request);
        serviceCatalogService.addServiceToSalon(salonId2, request);

        verify(serviceTypeRepository, times(1)).findById(serviceTypeId);
    }

    @Test
    @DisplayName("second searchServiceTypes(null, null) hits cache — findAllActiveWithCategory called once")
    void should_hitCache_when_searchServiceTypesCalledTwiceWithNullCategory() {
        when(serviceTypeRepository.findAllActiveWithCategory()).thenReturn(List.of());

        serviceCatalogService.searchServiceTypes(null, null);
        serviceCatalogService.searchServiceTypes(null, null);

        verify(serviceTypeRepository, times(1)).findAllActiveWithCategory();
    }

    @Test
    @DisplayName("second searchServiceTypes(categoryId, null) hits cache — category-scoped repo method called once")
    void should_hitCache_when_searchServiceTypesCalledTwiceWithSameCategoryId() {
        UUID categoryId = UUID.randomUUID();
        CatalogCategory category = CatalogCategory.builder()
                .id(categoryId)
                .nameUk("Test")
                .nameEn("Test")
                .sortOrder(1)
                .build();
        when(catalogCategoryRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(category));
        when(serviceTypeRepository.findByCategoryWithCategory(categoryId)).thenReturn(List.of());

        serviceCatalogService.searchServiceTypes(categoryId, null);
        serviceCatalogService.searchServiceTypes(categoryId, null);

        verify(serviceTypeRepository, times(1)).findByCategoryWithCategory(categoryId);
        verify(catalogCategoryRepository, times(1)).findAllByOrderBySortOrderAsc();
    }

    @Test
    @DisplayName("second call to searchServiceTypes with same q does not re-query repository — cache is effective")
    void should_hitCache_when_searchServiceTypesCalledTwice() {
        when(serviceTypeRepository.searchByName(eq("ман"), any())).thenReturn(List.of());

        serviceCatalogService.searchServiceTypes(null, "Ман");
        serviceCatalogService.searchServiceTypes(null, "Ман");

        verify(serviceTypeRepository, times(1)).searchByName(eq("ман"), any());
    }

    @Test
    @DisplayName("(q, categoryId) and (q, null) are cached independently — repo called once per distinct key")
    void should_cacheIndependently_when_queryWithAndWithoutCategoryId() {
        UUID categoryId = UUID.randomUUID();
        CatalogCategory category = CatalogCategory.builder()
                .id(categoryId)
                .nameUk("Test")
                .nameEn("Test")
                .sortOrder(1)
                .build();
        when(catalogCategoryRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(category));
        when(serviceTypeRepository.searchByName(eq("ман"), any())).thenReturn(List.of());
        when(serviceTypeRepository.searchByNameAndCategory(eq("ман"), eq(categoryId), any())).thenReturn(List.of());

        serviceCatalogService.searchServiceTypes(null, "Ман");     // cache key "ман:null"
        serviceCatalogService.searchServiceTypes(categoryId, "Ман"); // cache key "ман:<categoryId>"
        serviceCatalogService.searchServiceTypes(null, "Ман");     // cache hit for "ман:null"
        serviceCatalogService.searchServiceTypes(categoryId, "Ман"); // cache hit for "ман:<categoryId>"

        verify(serviceTypeRepository, times(1)).searchByName(eq("ман"), any());
        verify(serviceTypeRepository, times(1)).searchByNameAndCategory(eq("ман"), eq(categoryId), any());
    }

    @Test
    @DisplayName("second call to getCategories does not hit repository — service-categories cache is effective")
    void should_hitCache_when_getCategoriesCalledTwice() {
        when(catalogCategoryRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of());

        serviceCatalogService.getCategories();
        serviceCatalogService.getCategories();

        verify(catalogCategoryRepository, times(1)).findAllByOrderBySortOrderAsc();
    }
}
