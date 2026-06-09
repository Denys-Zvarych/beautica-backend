package com.beautica.service.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.repository.MasterRepository;
import com.beautica.notification.EmailService;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.dto.UpdateServiceDefinitionRequest;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the PATCH-semantics methods added to {@link ServiceCatalogService}:
 * {@code updateServiceDefinition} and {@code updateServicePhoto}.
 *
 * <p>Each test follows Arrange / Act / Assert with blank lines between sections.
 * Collaborators (repositories, cache manager) are Mockito mocks — no real DB required.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ServiceCatalogService — updateServiceDefinition and updateServicePhoto")
class ServiceCatalogServiceUpdateTest {

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

    // ── helpers ────────────────────────────────────────────────────────────────

    /**
     * Creates a minimal active {@link ServiceDefinition} with known field values
     * so tests can assert specific fields are (or are not) changed.
     */
    private ServiceDefinition buildDefinition(UUID id, UUID ownerId) {
        return ServiceDefinition.builder()
                .id(id)
                .ownerType(OwnerType.SALON)
                .ownerId(ownerId)
                .name("Original Name")
                .description("Original desc")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("350.00"))
                .priceMax(null)
                .bufferMinutesAfter(10)
                .isActive(true)
                .build();
    }

    // ── updateServiceDefinition — happy path ───────────────────────────────────

    @Test
    @DisplayName("updates name and price when both are provided in the request")
    void should_updateNameAndPrice_when_bothProvided() {
        UUID serviceDefId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ServiceDefinition existing = buildDefinition(serviceDefId, ownerId);

        ServiceDefinition saved = buildDefinition(serviceDefId, ownerId);
        saved.setName("New Manicure");
        saved.setBasePrice(new BigDecimal("400.00"));

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(saved);
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of());

        // PATCH: switch to FIXED 400.00 (name + price block together)
        var request = new UpdateServiceDefinitionRequest(
                "New Manicure", null, null, null, null, PriceType.FIXED, new BigDecimal("400.00"), null, null, null);

        ServiceDefinitionResponse result = serviceCatalogService.updateServiceDefinition(serviceDefId, request);

        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("New Manicure");
        assertThat(result.priceMin()).isEqualByComparingTo("400.00");

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("New Manicure");
        assertThat(captor.getValue().getBasePrice()).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("preserves unchanged fields when only duration is provided (partial PATCH semantics)")
    void should_preserveUnchangedFields_when_onlyDurationProvided() {
        UUID serviceDefId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ServiceDefinition existing = buildDefinition(serviceDefId, ownerId);

        ServiceDefinition saved = buildDefinition(serviceDefId, ownerId);
        saved.setBaseDurationMinutes(90);

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(saved);
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of());

        // Only baseDurationMinutes is non-null — all other fields must remain unchanged (price block absent)
        var request = new UpdateServiceDefinitionRequest(null, null, null, 90, null, null, null, null, null, null);

        serviceCatalogService.updateServiceDefinition(serviceDefId, request);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());

        ServiceDefinition submitted = captor.getValue();
        assertThat(submitted.getBaseDurationMinutes())
                .as("duration must be updated to 90").isEqualTo(90);
        assertThat(submitted.getName())
                .as("name must be unchanged — null field in PATCH request means no change")
                .isEqualTo("Original Name");
        assertThat(submitted.getBasePrice())
                .as("price must be unchanged").isEqualByComparingTo("350.00");
        assertThat(submitted.getDescription())
                .as("description must be unchanged").isEqualTo("Original desc");
        assertThat(submitted.getCategory())
                .as("category must be unchanged").isEqualTo("MANICURE");
        assertThat(submitted.getBufferMinutesAfter())
                .as("buffer must be unchanged").isEqualTo(10);
    }

    @Test
    @DisplayName("updates category when a new active category string is provided")
    void should_updateCategory_when_categoryProvided() {
        UUID serviceDefId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ServiceDefinition existing = buildDefinition(serviceDefId, ownerId);

        ServiceDefinition saved = buildDefinition(serviceDefId, ownerId);
        saved.setCategory("HAIRCUT");

        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                "HAIRCUT", com.beautica.service.entity.PlatformCategoryStatus.APPROVED)).thenReturn(true);
        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(saved);
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of());

        var request = new UpdateServiceDefinitionRequest(null, null, "HAIRCUT", null, null, null, null, null, null, null);

        ServiceDefinitionResponse result = serviceCatalogService.updateServiceDefinition(serviceDefId, request);

        assertThat(result.category()).isEqualTo("HAIRCUT");

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isEqualTo("HAIRCUT");
    }

    @Test
    @DisplayName("evicts masterServices cache for affected masters after update")
    void should_evictMasterServicesCache_when_serviceDefinitionUpdated() {
        UUID serviceDefId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        ServiceDefinition existing = buildDefinition(serviceDefId, ownerId);

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(existing);
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of(masterId));

        var mockCache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("masterServices")).thenReturn(mockCache);

        var request = new UpdateServiceDefinitionRequest("New Name", null, null, null, null, null, null, null, null, null);

        serviceCatalogService.updateServiceDefinition(serviceDefId, request);

        // findMasterIdsByServiceDefinitionId must be called to identify affected masters
        verify(masterServiceRepository).findMasterIdsByServiceDefinitionId(serviceDefId);
    }

    // ── validateCategoryActive — catalog-poisoning gate (negative branches) ───

    @Test
    @DisplayName("rejects a PENDING category on update — a self-service request must not be selectable")
    void should_throwBusinessException_when_categoryIsPending() {
        UUID serviceDefId = UUID.randomUUID();
        ServiceDefinition existing = buildDefinition(serviceDefId, UUID.randomUUID());

        // A PENDING row is APPROVED=false in the predicate, so the exact-case APPROVED
        // existence check returns false — same wire as an unknown name.
        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                "NAIL_ART", com.beautica.service.entity.PlatformCategoryStatus.APPROVED)).thenReturn(false);

        var request = new UpdateServiceDefinitionRequest(null, null, "NAIL_ART", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> serviceCatalogService.updateServiceDefinition(serviceDefId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown category")
                .extracting("status").hasToString("400 BAD_REQUEST");

        verify(serviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejects an APPROVED-but-inactive category on update — deactivated categories are not selectable")
    void should_throwBusinessException_when_categoryApprovedButInactive() {
        UUID serviceDefId = UUID.randomUUID();
        ServiceDefinition existing = buildDefinition(serviceDefId, UUID.randomUUID());

        // active=false means the active-true-and-APPROVED predicate is false.
        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                "RETIRED", com.beautica.service.entity.PlatformCategoryStatus.APPROVED)).thenReturn(false);

        var request = new UpdateServiceDefinitionRequest(null, null, "RETIRED", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> serviceCatalogService.updateServiceDefinition(serviceDefId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown category")
                .extracting("status").hasToString("400 BAD_REQUEST");

        verify(serviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("rejects an unknown category name on update")
    void should_throwBusinessException_when_categoryUnknown() {
        UUID serviceDefId = UUID.randomUUID();
        ServiceDefinition existing = buildDefinition(serviceDefId, UUID.randomUUID());

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                "DOES_NOT_EXIST", com.beautica.service.entity.PlatformCategoryStatus.APPROVED)).thenReturn(false);

        var request = new UpdateServiceDefinitionRequest(null, null, "DOES_NOT_EXIST", null, null, null, null, null, null, null);

        assertThatThrownBy(() -> serviceCatalogService.updateServiceDefinition(serviceDefId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Unknown category")
                .extracting("status").hasToString("400 BAD_REQUEST");

        verify(serviceRepository, never()).save(any());
    }

    // ── updateServiceDefinition — error cases ─────────────────────────────────

    @Test
    @DisplayName("throws NotFoundException when service definition does not exist on update")
    void should_throwNotFoundException_when_serviceDefinitionNotFoundOnUpdate() {
        UUID nonExistentId = UUID.randomUUID();

        when(serviceRepository.findByIdWithServiceType(nonExistentId)).thenReturn(Optional.empty());

        var request = new UpdateServiceDefinitionRequest("X", null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() ->
                serviceCatalogService.updateServiceDefinition(nonExistentId, request))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(nonExistentId.toString());

        verify(serviceRepository, never()).save(any());
    }

    // ── updateServicePhoto — happy path ───────────────────────────────────────

    @Test
    @DisplayName("sets photoUrl and returns updated response when service definition exists")
    void should_setPhotoUrl_when_serviceDefinitionExists() {
        UUID serviceDefId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String photoUrl = "https://pub-abc123.r2.dev/services/photo.jpg";
        ServiceDefinition existing = buildDefinition(serviceDefId, ownerId);

        ServiceDefinition saved = buildDefinition(serviceDefId, ownerId);
        saved.setPhotoUrl(photoUrl);

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(saved);
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of());

        ServiceDefinitionResponse result = serviceCatalogService.updateServicePhoto(serviceDefId, photoUrl);

        assertThat(result).isNotNull();
        assertThat(result.photoUrl()).isEqualTo(photoUrl);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getPhotoUrl()).isEqualTo(photoUrl);
    }

    @Test
    @DisplayName("evicts masterServices cache for affected masters after photo update")
    void should_evictMasterServicesCache_when_photoUpdated() {
        UUID serviceDefId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        String photoUrl = "https://example.com/photo.jpg";
        ServiceDefinition existing = buildDefinition(serviceDefId, ownerId);

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenReturn(existing);
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of(masterId));

        var mockCache = org.mockito.Mockito.mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache("masterServices")).thenReturn(mockCache);

        serviceCatalogService.updateServicePhoto(serviceDefId, photoUrl);

        verify(masterServiceRepository).findMasterIdsByServiceDefinitionId(serviceDefId);
    }

    // ── updateServicePhoto — error cases ──────────────────────────────────────

    @Test
    @DisplayName("throws NotFoundException when service definition does not exist on photo update")
    void should_throwNotFoundException_when_serviceDefinitionNotFoundOnPhotoUpdate() {
        UUID nonExistentId = UUID.randomUUID();

        when(serviceRepository.findByIdWithServiceType(nonExistentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() ->
                serviceCatalogService.updateServicePhoto(nonExistentId, "https://example.com/photo.jpg"))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(nonExistentId.toString());

        verify(serviceRepository, never()).save(any());
    }

    // ── updateServiceDefinition — serviceTypeId PATCH (Phase 16.3) ─────────────
    // PATCH semantics: a present serviceTypeId resolves + active-check + cross-category
    // consistency against the EFFECTIVE category (new category if the same request also
    // changes it, otherwise the existing one). A null serviceTypeId leaves the existing
    // type untouched — it must never clear an already-set type.

    @Test
    @DisplayName("changes serviceType when serviceTypeId is provided and matches the existing category — persisted on the entity")
    void should_changeServiceType_when_serviceTypeIdProvidedAndCategoryMatches() {
        UUID serviceDefId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID newTypeId = UUID.randomUUID();
        ServiceDefinition existing = buildDefinition(serviceDefId, ownerId); // category = MANICURE

        ServiceType newType = mock(ServiceType.class);
        when(newType.isActive()).thenReturn(true);
        when(newType.getPlatformCategoryName()).thenReturn("MANICURE");

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceTypeLookup.getById(newTypeId)).thenReturn(newType);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of());

        // Only serviceTypeId is set — category absent, so the EFFECTIVE category is the
        // existing MANICURE the type must match.
        var request = new UpdateServiceDefinitionRequest(
                null, null, null, null, null, null, null, null, null, newTypeId);

        serviceCatalogService.updateServiceDefinition(serviceDefId, request);

        verify(serviceTypeLookup).getById(newTypeId);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getServiceType())
                .as("the resolved service type must be set on the entity before save")
                .isSameAs(newType);
    }

    @Test
    @DisplayName("validates serviceType against a NEW category supplied in the SAME request (effective-category check)")
    void should_validateServiceTypeAgainstNewCategory_when_categoryAndTypeChangeTogether() {
        UUID serviceDefId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID newTypeId = UUID.randomUUID();
        ServiceDefinition existing = buildDefinition(serviceDefId, ownerId); // existing category = MANICURE

        // The request moves the definition to HAIRCUT and links a type that belongs to HAIRCUT.
        // The consistency check must run against the NEW category, not the stale MANICURE.
        ServiceType hairType = mock(ServiceType.class);
        when(hairType.isActive()).thenReturn(true);
        when(hairType.getPlatformCategoryName()).thenReturn("HAIRCUT");

        when(platformCategoryRepository.existsByNameAndActiveTrueAndStatus(
                "HAIRCUT", com.beautica.service.entity.PlatformCategoryStatus.APPROVED)).thenReturn(true);
        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceTypeLookup.getById(newTypeId)).thenReturn(hairType);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of());

        var request = new UpdateServiceDefinitionRequest(
                null, null, "HAIRCUT", null, null, null, null, null, null, newTypeId);

        serviceCatalogService.updateServiceDefinition(serviceDefId, request);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getCategory())
                .as("category must be updated to the new HAIRCUT value").isEqualTo("HAIRCUT");
        assertThat(captor.getValue().getServiceType())
                .as("type matching the NEW category must be accepted and set").isSameAs(hairType);
    }

    @Test
    @DisplayName("throws 400 when the provided serviceType is inactive — update")
    void should_throw400_when_serviceTypeInactive_onUpdate() {
        UUID serviceDefId = UUID.randomUUID();
        UUID inactiveTypeId = UUID.randomUUID();
        ServiceDefinition existing = buildDefinition(serviceDefId, UUID.randomUUID());

        ServiceType inactiveType = mock(ServiceType.class);
        when(inactiveType.isActive()).thenReturn(false);

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceTypeLookup.getById(inactiveTypeId)).thenReturn(inactiveType);

        var request = new UpdateServiceDefinitionRequest(
                null, null, null, null, null, null, null, null, null, inactiveTypeId);

        assertThatThrownBy(() -> serviceCatalogService.updateServiceDefinition(serviceDefId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Service type is not active")
                .extracting("status").hasToString("400 BAD_REQUEST");

        verify(serviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("throws 400 when the provided serviceType belongs to a different category — update")
    void should_throw400_when_serviceTypeCategoryMismatch_onUpdate() {
        UUID serviceDefId = UUID.randomUUID();
        UUID mismatchedTypeId = UUID.randomUUID();
        ServiceDefinition existing = buildDefinition(serviceDefId, UUID.randomUUID()); // category = MANICURE

        // Type belongs to HAIRCUT but the definition's effective category is MANICURE.
        ServiceType hairType = mock(ServiceType.class);
        when(hairType.isActive()).thenReturn(true);
        when(hairType.getPlatformCategoryName()).thenReturn("HAIRCUT");

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceTypeLookup.getById(mismatchedTypeId)).thenReturn(hairType);

        var request = new UpdateServiceDefinitionRequest(
                null, null, null, null, null, null, null, null, null, mismatchedTypeId);

        assertThatThrownBy(() -> serviceCatalogService.updateServiceDefinition(serviceDefId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("does not belong to the selected category")
                .extracting("status").hasToString("400 BAD_REQUEST");

        verify(serviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("leaves the existing serviceType untouched when serviceTypeId is null (PATCH never clears)")
    void should_leaveServiceTypeUntouched_when_serviceTypeIdIsNull() {
        UUID serviceDefId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        ServiceType existingType = mock(ServiceType.class);
        ServiceDefinition existing = buildDefinition(serviceDefId, ownerId);
        existing.setServiceType(existingType);

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of());

        // serviceTypeId null, only duration changes — the existing type must survive.
        var request = new UpdateServiceDefinitionRequest(
                null, null, null, 75, null, null, null, null, null, null);

        serviceCatalogService.updateServiceDefinition(serviceDefId, request);

        // The lookup must never be hit when serviceTypeId is null — no resolve, no clear.
        verify(serviceTypeLookup, never()).getById(any());

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getServiceType())
                .as("the pre-existing service type must remain set — null serviceTypeId means no change")
                .isSameAs(existingType);
    }

    // ── updateServiceDefinition — blank-name defaulting (Phase 16.4) ───────────
    // name absent (null) → unchanged; name present non-blank → overwrite;
    // name present but blank → default to the EFFECTIVE service type's nameUk;
    // blank name + no effective type → 400.

    @Test
    @DisplayName("defaults a blank name to the EXISTING service type's nameUk on update")
    void should_defaultBlankNameToExistingTypeNameUk_onUpdate() {
        UUID serviceDefId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();

        ServiceType existingType = mock(ServiceType.class);
        when(existingType.getNameUk()).thenReturn("Манікюр");
        ServiceDefinition existing = buildDefinition(serviceDefId, ownerId);
        existing.setServiceType(existingType);

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of());

        // Whitespace-only name + no serviceTypeId — defaults to the existing type's nameUk.
        var request = new UpdateServiceDefinitionRequest(
                "   ", null, null, null, null, null, null, null, null, null);

        serviceCatalogService.updateServiceDefinition(serviceDefId, request);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getName())
                .as("blank name must default to the existing service type's Ukrainian name — never persist blank")
                .isEqualTo("Манікюр");
    }

    @Test
    @DisplayName("defaults a blank name to the NEW service type's nameUk when both change in the same request")
    void should_defaultBlankNameToNewTypeNameUk_when_typeAlsoChanges_onUpdate() {
        UUID serviceDefId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID newTypeId = UUID.randomUUID();
        ServiceDefinition existing = buildDefinition(serviceDefId, ownerId); // category = MANICURE

        ServiceType newType = mock(ServiceType.class);
        when(newType.isActive()).thenReturn(true);
        when(newType.getPlatformCategoryName()).thenReturn("MANICURE");
        when(newType.getNameUk()).thenReturn("Френч");

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceTypeLookup.getById(newTypeId)).thenReturn(newType);
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of());

        // Blank name + a serviceTypeId in the SAME request — the name patch runs AFTER the
        // type patch, so the effective type is the newly-linked one.
        var request = new UpdateServiceDefinitionRequest(
                " ", null, null, null, null, null, null, null, null, newTypeId);

        serviceCatalogService.updateServiceDefinition(serviceDefId, request);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getName())
                .as("blank name must default to the newly-linked type's nameUk, not the old name")
                .isEqualTo("Френч");
    }

    @Test
    @DisplayName("leaves the existing name unchanged when name is absent (null) on update")
    void should_leaveNameUnchanged_when_nameIsNull_onUpdate() {
        UUID serviceDefId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        ServiceDefinition existing = buildDefinition(serviceDefId, ownerId); // name = "Original Name"

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));
        when(serviceRepository.save(any(ServiceDefinition.class))).thenAnswer(inv -> inv.getArgument(0));
        when(masterServiceRepository.findMasterIdsByServiceDefinitionId(serviceDefId))
                .thenReturn(List.of());

        // name null (absent) — only buffer changes; the name must be preserved verbatim.
        var request = new UpdateServiceDefinitionRequest(
                null, null, null, null, 20, null, null, null, null, null);

        serviceCatalogService.updateServiceDefinition(serviceDefId, request);

        ArgumentCaptor<ServiceDefinition> captor = ArgumentCaptor.forClass(ServiceDefinition.class);
        verify(serviceRepository).save(captor.capture());
        assertThat(captor.getValue().getName())
                .as("absent (null) name must leave the existing name unchanged")
                .isEqualTo("Original Name");
    }

    @Test
    @DisplayName("throws 400 'Name or service type is required' when name is blank and no effective service type exists — update")
    void should_throw400_when_blankNameAndNoEffectiveServiceType_onUpdate() {
        UUID serviceDefId = UUID.randomUUID();
        ServiceDefinition existing = buildDefinition(serviceDefId, UUID.randomUUID());
        // No serviceType set on the entity and none supplied in the request → no effective type.

        when(serviceRepository.findByIdWithServiceType(serviceDefId)).thenReturn(Optional.of(existing));

        var request = new UpdateServiceDefinitionRequest(
                "   ", null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> serviceCatalogService.updateServiceDefinition(serviceDefId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Name or service type is required")
                .extracting("status").hasToString("400 BAD_REQUEST");

        verify(serviceRepository, never()).save(any());
    }
}
