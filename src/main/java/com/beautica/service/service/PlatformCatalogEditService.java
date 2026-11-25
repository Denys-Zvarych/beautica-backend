package com.beautica.service.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.entity.ServiceType;
import com.beautica.service.repository.PlatformCategoryRepository;
import com.beautica.service.repository.ServiceTypeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * B504 (SVC-56) — the domain half of the catalog-admin rename / hide / unhide of a LIVE category or service type. The
 * caller ({@code CatalogEntryAdminService}) joins this transaction, writes the audit rows and evicts the catalog caches;
 * this class owns only the validation and the entity change.
 *
 * <ul>
 *   <li>A category renames its display name only; the wire {@code name} is immutable (track D4). A type renames
 *       {@code nameUk} / {@code nameEn}; the {@code slug} never changes.</li>
 *   <li>Names go through the service-name validator {@link ServiceCatalogService#canonicalName} (NFKC, space-collapse,
 *       the 100-char cap re-checked AFTER NFKC, SVC-07 allow-list) — the same one a pending request's display name uses
 *       (B502).</li>
 *   <li>Hide is {@code active = false} (D1). It does not cascade from a category to its types, and services already
 *       using a hidden row keep working (D4); only new use is blocked.</li>
 * </ul>
 * A value equal to the current one is not a change, so an all-unchanged PATCH changes nothing. The B505 delete of an
 * unused row lives in {@link PlatformCatalogDeleteService}.
 */
@Service
@RequiredArgsConstructor
public class PlatformCatalogEditService {

    private static final String NO_ENGLISH_NAME = "";

    private final PlatformCategoryRepository platformCategoryRepository;
    private final ServiceTypeRepository serviceTypeRepository;

    /** A category's editable fields before and after the edit (for the caller's audit rows). */
    public record CategoryEdit(String name, String displayNameBefore, String displayNameAfter,
                               boolean activeBefore, boolean activeAfter) {
        public boolean renamed() {
            return !displayNameBefore.equals(displayNameAfter);
        }

        public boolean visibilityChanged() {
            return activeBefore != activeAfter;
        }
    }

    /** A type's editable fields before and after the edit (for the caller's audit rows); {@code nameEn} may be null. */
    public record ServiceTypeEdit(UUID id, String nameUkBefore, @Nullable String nameEnBefore, String nameUkAfter,
                                  @Nullable String nameEnAfter, boolean activeBefore, boolean activeAfter) {
        public boolean renamed() {
            return !nameUkBefore.equals(nameUkAfter) || !Objects.equals(nameEnBefore, nameEnAfter);
        }

        public boolean visibilityChanged() {
            return activeBefore != activeAfter;
        }
    }

    /**
     * Renames and / or hides / unhides the APPROVED category with this wire name ({@code null} = leave unchanged).
     *
     * @throws NotFoundException               no category with this wire name
     * @throws CatalogRequestConflictException {@code CATEGORY_NOT_APPROVED} for a pending / rejected request;
     *                                         {@code CATEGORY_NAME_EXISTS} when another approved or pending category
     *                                         has the display name (case-insensitive)
     * @throws BusinessException               (400) nothing to update, or a blank / invalid / over-long name
     */
    @Transactional
    public CategoryEdit editCategory(String name, @Nullable String rawDisplayName, @Nullable Boolean active) {
        if (rawDisplayName == null && active == null) {
            throw nothingToUpdate();
        }
        PlatformCategory category = platformCategoryRepository.findByNameForUpdate(name)
                .orElseThrow(() -> new NotFoundException("Category not found"));
        if (!category.isApproved()) {
            throw new CatalogRequestConflictException(CatalogRequestConflictException.Code.CATEGORY_NOT_APPROVED);
        }
        String displayBefore = category.getDisplayName();
        boolean activeBefore = category.isActive();
        String displayAfter = rawDisplayName == null ? displayBefore : requiredName(rawDisplayName);
        if (!displayAfter.equals(displayBefore)) {
            if (platformCategoryRepository.existsOtherLiveByDisplayNameIgnoreCase(displayAfter, category.getId())) {
                throw new CatalogRequestConflictException(CatalogRequestConflictException.Code.CATEGORY_NAME_EXISTS);
            }
            category.renameDisplayName(displayAfter);
        }
        boolean activeAfter = active == null ? activeBefore : active;
        if (activeAfter != activeBefore) {
            category.changeActive(activeAfter);
        }
        return new CategoryEdit(name, displayBefore, displayAfter, activeBefore, activeAfter);
    }

    /**
     * Renames and / or hides / unhides a service type ({@code null} = leave unchanged; a blank {@code nameEn} clears
     * it to {@code ""}, the column's "none" value).
     *
     * @throws NotFoundException               no type with this id
     * @throws CatalogRequestConflictException {@code TYPE_EXISTS} when the new {@code nameUk} equals another type's in
     *                                         the same category (hidden ones included), or an unhide would put a second
     *                                         active type with the name in the category
     * @throws BusinessException               (400) nothing to update, or a blank / invalid / over-long name
     */
    @Transactional
    public ServiceTypeEdit editServiceType(UUID id, @Nullable String rawNameUk, @Nullable String rawNameEn,
                                           @Nullable Boolean active) {
        if (rawNameUk == null && rawNameEn == null && active == null) {
            throw nothingToUpdate();
        }
        ServiceType type = serviceTypeRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new NotFoundException("Service type not found"));
        String ukBefore = type.getNameUk();
        String enBefore = type.getNameEn();
        boolean activeBefore = type.isActive();
        String ukAfter = rawNameUk == null ? ukBefore : requiredName(rawNameUk);
        String enAfter = rawNameEn == null ? enBefore : optionalName(rawNameEn);
        boolean activeAfter = active == null ? activeBefore : active;
        assertNameFree(type, ukBefore, ukAfter, activeBefore, activeAfter);
        if (!ukAfter.equals(ukBefore) || !Objects.equals(enAfter, enBefore)) {
            type.rename(ukAfter, enAfter);
        }
        if (activeAfter != activeBefore) {
            type.changeActive(activeAfter);
        }
        return new ServiceTypeEdit(id, ukBefore, enBefore, ukAfter, enAfter, activeBefore, activeAfter);
    }

    /**
     * A changed {@code nameUk} must be free among ALL other types of the category; a case-only change of the type's own
     * name is allowed. Without a rename, an unhide must not collide with an ACTIVE type of the same name (one may
     * have been promoted while this one was hidden).
     */
    private void assertNameFree(ServiceType type, String ukBefore, String ukAfter, boolean activeBefore,
                                boolean activeAfter) {
        boolean nameChanged = !ukAfter.toLowerCase(Locale.ROOT).equals(ukBefore.toLowerCase(Locale.ROOT));
        boolean unhidden = activeAfter && !activeBefore;
        if (!nameChanged && !unhidden) {
            return;
        }
        if (serviceTypeRepository.existsOtherInCategoryByNameUkIgnoreCase(
                type.getPlatformCategoryName(), ukAfter, type.getId(), !nameChanged)) {
            throw new CatalogRequestConflictException(CatalogRequestConflictException.Code.TYPE_EXISTS);
        }
    }

    private static String requiredName(String raw) {
        String canonical = ServiceCatalogService.canonicalName(raw);
        if (canonical == null || canonical.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Name is required");
        }
        return canonical;
    }

    /** {@code service_types.name_en} is NOT NULL DEFAULT '' (V17): "no English name" is stored as {@code ""}. */
    private static String optionalName(String raw) {
        String canonical = ServiceCatalogService.canonicalName(raw);
        return canonical == null ? NO_ENGLISH_NAME : canonical;
    }

    private static BusinessException nothingToUpdate() {
        return new BusinessException(HttpStatus.BAD_REQUEST, "Nothing to update");
    }
}
