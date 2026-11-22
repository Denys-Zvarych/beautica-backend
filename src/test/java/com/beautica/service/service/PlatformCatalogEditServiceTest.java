package com.beautica.service.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PlatformCatalogEditService — B504 rename / hide / unhide of a live category or type")
class PlatformCatalogEditServiceTest {

    private static final String CATEGORY = "MASAZH_NIH";
    private static final long CATEGORY_ID = 42L;

    @Mock private PlatformCategoryRepository platformCategoryRepository;
    @Mock private ServiceTypeRepository serviceTypeRepository;

    @InjectMocks private PlatformCatalogEditService service;

    private PlatformCategory approvedCategory(String displayName, boolean active) {
        PlatformCategory category = PlatformCategory.ofApproved(CATEGORY, displayName);
        ReflectionTestUtils.setField(category, "id", CATEGORY_ID);
        category.changeActive(active);
        return category;
    }

    private static ServiceType type(UUID id, String nameUk, String nameEn, boolean active) {
        return ServiceType.builder().id(id).nameUk(nameUk).nameEn(nameEn).slug("masazh-nih")
                .platformCategoryName(CATEGORY).active(active).build();
    }

    private static CatalogRequestConflictException.Code conflictCode(Throwable thrown) {
        assertThat(thrown).isInstanceOf(CatalogRequestConflictException.class);
        return ((CatalogRequestConflictException) thrown).getCode();
    }

    // ── category ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("rename stores the canonical (NFKC, space-collapsed) display name; the wire name never changes")
    void should_renameDisplayNameToCanonicalForm_when_nameIsFree() {
        PlatformCategory category = approvedCategory("Масаж ніг", true);
        when(platformCategoryRepository.findByNameForUpdate(CATEGORY)).thenReturn(Optional.of(category));
        when(platformCategoryRepository.existsOtherLiveByDisplayNameIgnoreCase("Масаж стоп", CATEGORY_ID))
                .thenReturn(false);

        PlatformCatalogEditService.CategoryEdit edit = service.editCategory(CATEGORY, "  Масаж  стоп ", null);

        assertThat(category.getDisplayName()).isEqualTo("Масаж стоп");
        assertThat(category.getName()).as("wire name immutable").isEqualTo(CATEGORY);
        assertThat(edit.renamed()).isTrue();
        assertThat(edit.visibilityChanged()).isFalse();
    }

    @Test
    @DisplayName("rename onto another live category's display name is 409 CATEGORY_NAME_EXISTS and changes nothing")
    void should_throwCategoryNameExists_when_anotherLiveCategoryHasTheName() {
        PlatformCategory category = approvedCategory("Масаж ніг", true);
        when(platformCategoryRepository.findByNameForUpdate(CATEGORY)).thenReturn(Optional.of(category));
        when(platformCategoryRepository.existsOtherLiveByDisplayNameIgnoreCase("Манікюр", CATEGORY_ID))
                .thenReturn(true);

        Throwable thrown = catchThrowable(() -> service.editCategory(CATEGORY, "Манікюр", null));

        assertThat(conflictCode(thrown)).isEqualTo(CatalogRequestConflictException.Code.CATEGORY_NAME_EXISTS);
        assertThat(category.getDisplayName()).isEqualTo("Масаж ніг");
    }

    @Test
    @DisplayName("a name NFKC lengthens past 100 characters is a 400 (length re-checked after NFKC)")
    void should_rejectOverLongName_when_nfkcLengthensItPast100() {
        PlatformCategory category = approvedCategory("Масаж ніг", true);
        when(platformCategoryRepository.findByNameForUpdate(CATEGORY)).thenReturn(Optional.of(category));
        String ligatures = "ﬁ".repeat(51); // 51 chars raw, 102 after NFKC (ﬁ → fi)

        Throwable thrown = catchThrowable(() -> service.editCategory(CATEGORY, ligatures, null));

        assertThat(thrown).isInstanceOf(BusinessException.class).hasMessageContaining("at most 100");
        assertThat(category.getDisplayName()).isEqualTo("Масаж ніг");
    }

    @Test
    @DisplayName("a display name of only (NBSP) spaces is a 400 Name is required")
    void should_rejectBlankName_when_displayNameIsOnlySpaces() {
        PlatformCategory category = approvedCategory("Масаж ніг", true);
        when(platformCategoryRepository.findByNameForUpdate(CATEGORY)).thenReturn(Optional.of(category));

        Throwable thrown = catchThrowable(() -> service.editCategory(CATEGORY, "  ", null));

        assertThat(thrown).isInstanceOf(BusinessException.class).hasMessage("Name is required");
    }

    @Test
    @DisplayName("active=false alone hides the category without a name-uniqueness query")
    void should_hideWithoutNameCheck_when_onlyActiveIsSent() {
        PlatformCategory category = approvedCategory("Масаж ніг", true);
        when(platformCategoryRepository.findByNameForUpdate(CATEGORY)).thenReturn(Optional.of(category));

        PlatformCatalogEditService.CategoryEdit edit = service.editCategory(CATEGORY, null, false);

        assertThat(category.isActive()).isFalse();
        assertThat(edit.visibilityChanged()).isTrue();
        assertThat(edit.renamed()).isFalse();
        verify(platformCategoryRepository, never()).existsOtherLiveByDisplayNameIgnoreCase(any(), any());
    }

    @Test
    @DisplayName("values equal to the current ones report neither a rename nor a visibility change")
    void should_reportNoChange_when_valuesEqualCurrent() {
        PlatformCategory category = approvedCategory("Масаж ніг", true);
        when(platformCategoryRepository.findByNameForUpdate(CATEGORY)).thenReturn(Optional.of(category));

        PlatformCatalogEditService.CategoryEdit edit = service.editCategory(CATEGORY, "Масаж ніг", true);

        assertThat(edit.renamed()).isFalse();
        assertThat(edit.visibilityChanged()).isFalse();
    }

    @Test
    @DisplayName("a PENDING request row is 409 CATEGORY_NOT_APPROVED and stays inactive")
    void should_throwCategoryNotApproved_when_categoryIsPendingRequest() {
        PlatformCategory pending = PlatformCategory.ofPendingRequest(CATEGORY, "Масаж ніг", UUID.randomUUID(),
                "hash", OffsetDateTime.parse("2026-11-01T10:00:00Z"), OffsetDateTime.parse("2026-11-08T10:00:00Z"),
                null);
        when(platformCategoryRepository.findByNameForUpdate(CATEGORY)).thenReturn(Optional.of(pending));

        Throwable thrown = catchThrowable(() -> service.editCategory(CATEGORY, null, true));

        assertThat(conflictCode(thrown)).isEqualTo(CatalogRequestConflictException.Code.CATEGORY_NOT_APPROVED);
        assertThat(pending.isActive()).isFalse();
    }

    @Test
    @DisplayName("an unknown category wire name is a 404")
    void should_throwNotFound_when_categoryUnknown() {
        when(platformCategoryRepository.findByNameForUpdate(CATEGORY)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.editCategory(CATEGORY, "Нова", null));

        assertThat(thrown).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("an empty category body is a 400 Nothing to update, with no repository call")
    void should_throwBadRequest_when_categoryBodyIsEmpty() {
        Throwable thrown = catchThrowable(() -> service.editCategory(CATEGORY, null, null));

        assertThat(thrown).isInstanceOf(BusinessException.class).hasMessage("Nothing to update");
        verifyNoInteractions(platformCategoryRepository);
    }

    // ── service type ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("a type rename sets nameUk / nameEn and keeps the slug")
    void should_renameTypeAndKeepSlug_when_nameIsFree() {
        UUID id = UUID.randomUUID();
        ServiceType type = type(id, "Масаж ніг", null, true);
        when(serviceTypeRepository.findByIdForUpdate(id)).thenReturn(Optional.of(type));
        when(serviceTypeRepository.existsOtherInCategoryByNameUkIgnoreCase(CATEGORY, "Масаж стоп", id, false))
                .thenReturn(false);

        PlatformCatalogEditService.ServiceTypeEdit edit = service.editServiceType(id, "Масаж стоп", "Foot massage", null);

        assertThat(type.getNameUk()).isEqualTo("Масаж стоп");
        assertThat(type.getNameEn()).isEqualTo("Foot massage");
        assertThat(type.getSlug()).isEqualTo("masazh-nih");
        assertThat(edit.renamed()).isTrue();
    }

    @Test
    @DisplayName("a type rename onto another type's name in the category is 409 TYPE_EXISTS and changes nothing")
    void should_throwTypeExists_when_anotherTypeInCategoryHasTheName() {
        UUID id = UUID.randomUUID();
        ServiceType type = type(id, "Масаж ніг", null, true);
        when(serviceTypeRepository.findByIdForUpdate(id)).thenReturn(Optional.of(type));
        when(serviceTypeRepository.existsOtherInCategoryByNameUkIgnoreCase(CATEGORY, "Педикюр", id, false))
                .thenReturn(true);

        Throwable thrown = catchThrowable(() -> service.editServiceType(id, "Педикюр", null, null));

        assertThat(conflictCode(thrown)).isEqualTo(CatalogRequestConflictException.Code.TYPE_EXISTS);
        assertThat(type.getNameUk()).isEqualTo("Масаж ніг");
    }

    @Test
    @DisplayName("a case-only rename of the type's own name is allowed with no uniqueness query")
    void should_allowCaseOnlyRename_when_onlyTheTypesOwnNameMatches() {
        UUID id = UUID.randomUUID();
        ServiceType type = type(id, "масаж ніг", null, true);
        when(serviceTypeRepository.findByIdForUpdate(id)).thenReturn(Optional.of(type));

        service.editServiceType(id, "Масаж ніг", null, null);

        assertThat(type.getNameUk()).isEqualTo("Масаж ніг");
        verify(serviceTypeRepository, never()).existsOtherInCategoryByNameUkIgnoreCase(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("unhiding onto an ACTIVE type's name is 409 TYPE_EXISTS and the type stays hidden")
    void should_throwTypeExists_when_unhideCollidesWithActiveType() {
        UUID id = UUID.randomUUID();
        ServiceType type = type(id, "Масаж ніг", null, false);
        when(serviceTypeRepository.findByIdForUpdate(id)).thenReturn(Optional.of(type));
        when(serviceTypeRepository.existsOtherInCategoryByNameUkIgnoreCase(CATEGORY, "Масаж ніг", id, true))
                .thenReturn(true);

        Throwable thrown = catchThrowable(() -> service.editServiceType(id, null, null, true));

        assertThat(conflictCode(thrown)).isEqualTo(CatalogRequestConflictException.Code.TYPE_EXISTS);
        assertThat(type.isActive()).isFalse();
    }

    @Test
    @DisplayName("active=false alone hides the type without a name-uniqueness query")
    void should_hideTypeWithoutNameCheck_when_onlyActiveFalseIsSent() {
        UUID id = UUID.randomUUID();
        ServiceType type = type(id, "Масаж ніг", "Foot", true);
        when(serviceTypeRepository.findByIdForUpdate(id)).thenReturn(Optional.of(type));

        PlatformCatalogEditService.ServiceTypeEdit edit = service.editServiceType(id, null, null, false);

        assertThat(type.isActive()).isFalse();
        assertThat(edit.visibilityChanged()).isTrue();
        assertThat(edit.renamed()).isFalse();
        verify(serviceTypeRepository, never()).existsOtherInCategoryByNameUkIgnoreCase(any(), any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("a blank nameEn clears it to \"\" (name_en is NOT NULL DEFAULT '')")
    void should_clearEnglishName_when_nameEnIsBlank() {
        UUID id = UUID.randomUUID();
        ServiceType type = type(id, "Масаж ніг", "Foot", true);
        when(serviceTypeRepository.findByIdForUpdate(id)).thenReturn(Optional.of(type));

        PlatformCatalogEditService.ServiceTypeEdit edit = service.editServiceType(id, null, "  ", null);

        assertThat(type.getNameEn()).as("name_en is NOT NULL DEFAULT ''").isEmpty();
        assertThat(edit.renamed()).isTrue();
    }

    @Test
    @DisplayName("an unknown type id is a 404")
    void should_throwNotFound_when_typeUnknown() {
        UUID id = UUID.randomUUID();
        when(serviceTypeRepository.findByIdForUpdate(id)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.editServiceType(id, "Нова", null, null));

        assertThat(thrown).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("an empty type body is a 400 Nothing to update, with no repository call")
    void should_throwBadRequest_when_typeBodyIsEmpty() {
        Throwable thrown = catchThrowable(() -> service.editServiceType(UUID.randomUUID(), null, null, null));

        assertThat(thrown).isInstanceOf(BusinessException.class).hasMessage("Nothing to update");
        verifyNoInteractions(serviceTypeRepository);
    }
}
