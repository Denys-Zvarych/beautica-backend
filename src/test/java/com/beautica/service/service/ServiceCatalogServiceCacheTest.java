package com.beautica.service.service;

import com.beautica.config.CacheConfig;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.EmailService;
import com.beautica.salon.repository.SalonRepository;
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

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = {ServiceCatalogService.class, CacheConfig.class},
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
    }

    @Test
    @DisplayName("second call to getMasterServices returns cached result without hitting repository")
    void should_notHitRepository_when_getMasterServicesCalledTwice() {
        UUID masterId = UUID.randomUUID();
        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(masterId)).thenReturn(List.of());

        serviceCatalogService.getMasterServices(masterId);
        serviceCatalogService.getMasterServices(masterId);

        verify(masterServiceRepository, times(1)).findByMasterIdAndIsActiveTrueWithGraph(masterId);
    }

    @Test
    @DisplayName("deactivateServiceDefinition evicts all masterServices cache entries so next getMasterServices re-queries")
    void should_evictCache_when_deactivateServiceDefinitionCalled() {
        UUID masterId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        when(masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(masterId)).thenReturn(List.of());
        when(serviceRepository.deactivateById(serviceDefId)).thenReturn(1);

        // Populate cache
        serviceCatalogService.getMasterServices(masterId);
        // Evict all masterServices entries
        serviceCatalogService.deactivateServiceDefinition(serviceDefId);
        // Cache was evicted — repository must be queried again
        serviceCatalogService.getMasterServices(masterId);

        verify(masterServiceRepository, times(2)).findByMasterIdAndIsActiveTrueWithGraph(masterId);
    }
}
