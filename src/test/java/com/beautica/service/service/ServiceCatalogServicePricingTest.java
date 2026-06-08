package com.beautica.service.service;

import com.beautica.common.exception.ForbiddenException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.EmailService;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.dto.CreateServiceDefinitionRequest;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.dto.UpdateServiceDefinitionRequest;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the flexible service pricing paths in {@link ServiceCatalogService}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Create FIXED service — base_price = price, price_max = null</li>
 *   <li>Create RANGE service — base_price = priceMin, price_max = priceMax</li>
 *   <li>PATCH FIXED → RANGE switches all three columns atomically</li>
 *   <li>PATCH with absent price block leaves existing pricing unchanged</li>
 *   <li>PATCH RANGE — max > min invariant applied at service layer</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceCatalogService — flexible pricing")
class ServiceCatalogServicePricingTest {

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
    private EmailService emailService;

    @Mock
    private CacheManager cacheManager;

    @InjectMocks
    private ServiceCatalogService serviceCatalogService;

    // ── Create FIXED ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("creates FIXED service — base_price=price, price_max=null saved")
    void should_saveFixedPricing_when_createRequestIsFixed() {
        UUID salonId = UUID.randomUUID();
        var request = new CreateServiceDefinitionRequest(
                "Manicure", null, null, 60, 0,
                PriceType.FIXED, new BigDecimal("500.00"), null, null, null);

        ServiceDefinition saved = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(salonId)
                .name("Manicure")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("500.00"))
                .priceMax(null)
                .isActive(true)
                .build();

        when(salonRepository.existsById(salonId)).thenReturn(true);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(saved);

        ServiceDefinitionResponse result = serviceCatalogService.addServiceToSalon(salonId, request);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());

        ServiceDefinition persisted = captor.getValue();
        assertThat(persisted.getPriceType())
                .as("priceType must be FIXED")
                .isEqualTo(PriceType.FIXED);
        assertThat(persisted.getBasePrice())
                .as("base_price = price for FIXED mode")
                .isEqualByComparingTo("500.00");
        assertThat(persisted.getPriceMax())
                .as("price_max must be null for FIXED mode")
                .isNull();

        assertThat(result.priceType()).isEqualTo(PriceType.FIXED);
        assertThat(result.priceMin()).isEqualByComparingTo("500.00");
        assertThat(result.priceMax()).isNull();
        assertThat(result.priceDisplay()).isEqualTo("500 грн");
    }

    // ── Create RANGE ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("creates RANGE service — base_price=priceMin, price_max=priceMax saved")
    void should_saveRangePricing_when_createRequestIsRange() {
        UUID salonId = UUID.randomUUID();
        var request = new CreateServiceDefinitionRequest(
                "Highlights", null, null, 120, 15,
                PriceType.RANGE, null, new BigDecimal("800.00"), new BigDecimal("1500.00"), null);

        ServiceDefinition saved = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.SALON)
                .ownerId(salonId)
                .name("Highlights")
                .baseDurationMinutes(120)
                .priceType(PriceType.RANGE)
                .basePrice(new BigDecimal("800.00"))
                .priceMax(new BigDecimal("1500.00"))
                .isActive(true)
                .build();

        when(salonRepository.existsById(salonId)).thenReturn(true);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(saved);

        ServiceDefinitionResponse result = serviceCatalogService.addServiceToSalon(salonId, request);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());

        ServiceDefinition persisted = captor.getValue();
        assertThat(persisted.getPriceType())
                .as("priceType must be RANGE")
                .isEqualTo(PriceType.RANGE);
        assertThat(persisted.getBasePrice())
                .as("base_price = priceMin for RANGE mode (canonical floor)")
                .isEqualByComparingTo("800.00");
        assertThat(persisted.getPriceMax())
                .as("price_max = priceMax for RANGE mode")
                .isEqualByComparingTo("1500.00");

        assertThat(result.priceType()).isEqualTo(PriceType.RANGE);
        assertThat(result.priceMin()).isEqualByComparingTo("800.00");
        assertThat(result.priceMax()).isEqualByComparingTo("1500.00");
        assertThat(result.priceDisplay()).isEqualTo("від 800 до 1500 грн");
    }

    // ── PATCH FIXED → RANGE ───────────────────────────────────────────────────

    @Test
    @DisplayName("PATCH FIXED→RANGE switches all three price columns atomically")
    void should_switchAllThreePriceColumns_when_patchFromFixedToRange() {
        UUID serviceDefId = UUID.randomUUID();
        ServiceDefinition existing = ServiceDefinition.builder()
                .id(serviceDefId)
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Manicure")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("500.00"))
                .priceMax(null)
                .isActive(true)
                .build();

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId)).thenReturn(List.of());

        var request = new UpdateServiceDefinitionRequest(
                null, null, null, null, null,
                PriceType.RANGE, null, new BigDecimal("600.00"), new BigDecimal("1000.00"), null);

        serviceCatalogService.updateServiceDefinition(serviceDefId, request);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());

        ServiceDefinition saved = captor.getValue();
        assertThat(saved.getPriceType()).isEqualTo(PriceType.RANGE);
        assertThat(saved.getBasePrice())
                .as("base_price must be updated to priceMin=600.00")
                .isEqualByComparingTo("600.00");
        assertThat(saved.getPriceMax())
                .as("price_max must be set to priceMax=1000.00")
                .isEqualByComparingTo("1000.00");
    }

    // ── PATCH price block absent ───────────────────────────────────────────────

    @Test
    @DisplayName("PATCH with absent price block — all three price columns unchanged")
    void should_leaveExistingPricing_when_patchPriceBlockAbsent() {
        UUID serviceDefId = UUID.randomUUID();
        ServiceDefinition existing = ServiceDefinition.builder()
                .id(serviceDefId)
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Manicure")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("500.00"))
                .priceMax(null)
                .isActive(true)
                .build();

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId)).thenReturn(List.of());

        // Only update name — all four price fields null = price block absent
        var request = new UpdateServiceDefinitionRequest(
                "New Manicure", null, null, null, null, null, null, null, null, null);

        serviceCatalogService.updateServiceDefinition(serviceDefId, request);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());

        ServiceDefinition saved = captor.getValue();
        assertThat(saved.getPriceType())
                .as("priceType must remain FIXED — price block was absent")
                .isEqualTo(PriceType.FIXED);
        assertThat(saved.getBasePrice())
                .as("base_price must remain 500.00 — price block was absent")
                .isEqualByComparingTo("500.00");
        assertThat(saved.getPriceMax())
                .as("price_max must remain null — price block was absent")
                .isNull();
    }

    // ── PATCH RANGE — price block update ──────────────────────────────────────

    @Test
    @DisplayName("PATCH RANGE→RANGE updates priceMin and priceMax")
    void should_updateRangePricing_when_patchRangeWithNewValues() {
        UUID serviceDefId = UUID.randomUUID();
        ServiceDefinition existing = ServiceDefinition.builder()
                .id(serviceDefId)
                .ownerType(OwnerType.SALON)
                .ownerId(UUID.randomUUID())
                .name("Highlights")
                .baseDurationMinutes(120)
                .priceType(PriceType.RANGE)
                .basePrice(new BigDecimal("800.00"))
                .priceMax(new BigDecimal("1500.00"))
                .isActive(true)
                .build();

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId)).thenReturn(List.of());

        var request = new UpdateServiceDefinitionRequest(
                null, null, null, null, null,
                PriceType.RANGE, null, new BigDecimal("900.00"), new BigDecimal("1800.00"), null);

        serviceCatalogService.updateServiceDefinition(serviceDefId, request);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());

        ServiceDefinition saved = captor.getValue();
        assertThat(saved.getBasePrice()).isEqualByComparingTo("900.00");
        assertThat(saved.getPriceMax()).isEqualByComparingTo("1800.00");
    }

    // ── Independent master RANGE ───────────────────────────────────────────────

    @Test
    @DisplayName("independent master creates RANGE service — base_price=priceMin stored")
    void should_saveRangePricing_when_independentMasterCreatesRangeService() {
        UUID userId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Master master = org.mockito.Mockito.mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);

        var request = new CreateServiceDefinitionRequest(
                "Hair Coloring", null, null, 90, 15,
                PriceType.RANGE, null, new BigDecimal("600.00"), new BigDecimal("1200.00"), null);

        ServiceDefinition savedDef = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(masterId)
                .name("Hair Coloring")
                .baseDurationMinutes(90)
                .priceType(PriceType.RANGE)
                .basePrice(new BigDecimal("600.00"))
                .priceMax(new BigDecimal("1200.00"))
                .isActive(true)
                .build();

        var savedAssignment = org.mockito.Mockito.mock(com.beautica.service.entity.MasterServiceAssignment.class);
        when(savedAssignment.getId()).thenReturn(UUID.randomUUID());
        when(savedAssignment.getMaster()).thenReturn(master);
        when(savedAssignment.getServiceDefinition()).thenReturn(savedDef);
        when(savedAssignment.isActive()).thenReturn(true);

        when(masterRepository.findByUserId(userId)).thenReturn(Optional.of(master));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(savedDef);
        when(masterServiceRepository.save(any())).thenReturn(savedAssignment);

        var result = serviceCatalogService.addIndependentMasterService(userId, request);

        assertThat(result.priceType()).isEqualTo(PriceType.RANGE);
        assertThat(result.priceMin()).isEqualByComparingTo("600.00");
        assertThat(result.priceMax()).isEqualByComparingTo("1200.00");
        assertThat(result.priceDisplay()).isEqualTo("від 600 до 1200 грн");

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getPriceType()).isEqualTo(PriceType.RANGE);
        assertThat(captor.getValue().getBasePrice()).isEqualByComparingTo("600.00");
        assertThat(captor.getValue().getPriceMax()).isEqualByComparingTo("1200.00");
    }
}
