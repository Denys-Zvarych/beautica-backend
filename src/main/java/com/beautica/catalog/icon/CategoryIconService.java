package com.beautica.catalog.icon;

import com.beautica.catalog.CatalogCacheEvictor;
import com.beautica.catalog.admin.AdminActor;
import com.beautica.catalog.admin.CatalogAdminAuditService;
import com.beautica.catalog.admin.CatalogAuditTargetType;
import com.beautica.catalog.icon.dto.CategoryIconResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.CatalogRequestConflictException;
import com.beautica.common.exception.CategoryIconInvalidException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.exception.ServiceUnavailableMessages;
import com.beautica.media.service.AfterCommitBlobPurger;
import com.beautica.media.service.R2StorageService;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.repository.PlatformCategoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * B539 (SVC-58) — the platform operator sets or replaces a category's icon (PENDING or APPROVED, B541).
 *
 * <p>Order: wire name (400), storage on (503), sanitise (400 {@code ICON_INVALID}), plain read (404, 409
 * {@code CATEGORY_REJECTED}; same canonical sha256 = no-op), then the R2 PUT under the content-addressed key with NO
 * transaction open (idempotent key). Then the write transaction: lock ({@code FOR UPDATE}, 3 s), re-check, set the
 * columns, audit {@value #ICON_SET_ACTION}, evict the platform-catalog caches and purge the previous object, both
 * after commit.
 *
 * <p>Once the attempt ends (commit, rollback or earlier failure) the uploaded key goes to the purger, which deletes
 * it only if no committed row references it ({@link CategoryIconReferenceGuard}); the previous object's purge takes
 * the same check, so A→B→A never deletes the live A. The key is {@link CategoryIconReferenceGuard#claim claimed}
 * before the PUT and released only when the write transaction has ended, so a purge of the same key that runs while
 * this write is uncommitted keeps the object.
 */
@Service
@RequiredArgsConstructor
public class CategoryIconService {

    static final String ICON_SET_ACTION = "ICON_SET";
    static final String KEY_PREFIX = "catalog/category-icons/";
    static final String CONTENT_TYPE = "image/svg+xml";
    /** The key carries the content hash, so the object never changes: one year in browsers and at the edge. */
    static final String CACHE_CONTROL = "public, max-age=31536000, immutable";
    static final String PURGE_CONTEXT = "category-icon";
    static final String UPLOAD_RELEASE_CONTEXT = "category-icon-upload";
    private static final int KEY_HASH_LENGTH = 16;

    /** Same rule as {@code chk_platform_categories_name}. */
    private static final Pattern WIRE_NAME = Pattern.compile("^[A-Z][A-Z0-9_]{0,99}$");

    private final PlatformCategoryRepository platformCategoryRepository;
    private final R2StorageService r2StorageService;
    private final AfterCommitBlobPurger blobPurger;
    private final CatalogAdminAuditService auditService;
    private final CatalogCacheEvictor cacheEvictor;
    private final CategoryIconUrls iconUrls;
    private final Clock clock;
    private final TransactionOperations writeTx;
    private final CategoryIconReferenceGuard referenceGuard;

    /** Deliberately not {@code @Transactional}: the R2 PUT runs before the write transaction opens. */
    public CategoryIconResponse setIcon(String categoryName, String svgText, AdminActor actor) {
        requireValidRequest(categoryName);
        SanitizedIcon icon = sanitize(svgText);
        prepare(categoryName, icon, false)
                .ifPresent(prepared -> inWriteTx(prepared, () -> apply(prepared, actor, false, true)));
        return new CategoryIconResponse(categoryName, iconUrls.urlFor(keyFor(categoryName, icon)));
    }

    /**
     * B540 — the backfill's variant: sets the icon only when the category has none (checked again under the row lock,
     * so a concurrent operator upload is never overwritten). Same validation and audit as {@link #setIcon}, but NO
     * cache eviction: the caller evicts once after its whole run ({@link CatalogCacheEvictor#evictPlatformCatalog}).
     *
     * @return {@code true} when the icon was set, {@code false} when the category already had one
     */
    public boolean setIconIfAbsent(String categoryName, String svgText, AdminActor actor) {
        requireValidRequest(categoryName);
        SanitizedIcon icon = sanitize(svgText);
        return prepare(categoryName, icon, true)
                .map(prepared -> inWriteTx(prepared, () -> apply(prepared, actor, true, false)))
                .orElse(false);
    }

    /**
     * B541 (SVC-60) — create a category WITH its icon: the icon is validated and uploaded first (no transaction), then
     * {@code createRow} and the icon set run in ONE transaction, so any failure rolls the create back too. NO cache
     * eviction here: the caller ({@code InternalCategoryService#create}) evicts once, after this transaction commits.
     */
    public <T> T createWithIcon(String categoryName, String svgText, AdminActor actor, Supplier<T> createRow) {
        requireValidRequest(categoryName);
        SanitizedIcon icon = sanitize(svgText);
        PreparedIcon prepared = upload(categoryName, icon);
        return inWriteTx(prepared, () -> {
            T created = createRow.get();
            apply(prepared, actor, false, false);
            return created;
        });
    }

    /** Runs {@code work} in the write transaction, then releases the pre-uploaded object (kept iff referenced). */
    private <T> T inWriteTx(PreparedIcon prepared, Supplier<T> work) {
        try {
            return writeTx.execute(status -> work.get());
        } finally {
            releaseClaimAfterCompletion(prepared.key());
            blobPurger.purgeAfterCompletion(List.of(prepared.key()), UPLOAD_RELEASE_CONTEXT);
        }
    }

    /**
     * Drops the upload's claim once the transaction that may reference the key has ended: now, or — when the write
     * joined an outer transaction — at that transaction's completion, registered BEFORE the upload release purge.
     */
    private void releaseClaimAfterCompletion(String key) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            referenceGuard.release(key);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                referenceGuard.release(key);
            }
        });
    }

    /** Pre-read (404 / 409) and upload, outside any transaction; empty = nothing to write. */
    private Optional<PreparedIcon> prepare(String categoryName, SanitizedIcon icon, boolean ifAbsent) {
        PlatformCategory current = requireIconable(platformCategoryRepository.findByName(categoryName));
        if (ifAbsent ? current.getIconKey() != null : icon.sha256().equals(current.getIconSha256())) {
            return Optional.empty();
        }
        return Optional.of(upload(categoryName, icon));
    }

    private void requireValidRequest(String categoryName) {
        if (categoryName == null || !WIRE_NAME.matcher(categoryName).matches()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Invalid category name");
        }
        if (!r2StorageService.isEnabled()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE,
                    ServiceUnavailableMessages.MEDIA_STORAGE_NOT_CONFIGURED);
        }
    }

    private static PlatformCategory requireIconable(Optional<PlatformCategory> found) {
        PlatformCategory category = found.orElseThrow(() -> new NotFoundException("Category not found"));
        if (category.isRejected()) {
            throw new CatalogRequestConflictException(CatalogRequestConflictException.Code.CATEGORY_REJECTED);
        }
        return category;
    }

    /** Claims the key, then PUTs; a failed PUT drops the claim (the caller's release never runs). */
    private PreparedIcon upload(String categoryName, SanitizedIcon icon) {
        String key = keyFor(categoryName, icon);
        referenceGuard.claim(key);
        try {
            r2StorageService.uploadFile(key, new ByteArrayInputStream(icon.bytes()), icon.bytes().length,
                    CONTENT_TYPE, CACHE_CONTROL);
        } catch (RuntimeException ex) {
            referenceGuard.release(key);
            throw ex;
        }
        return new PreparedIcon(categoryName, icon, key);
    }

    /**
     * In the write transaction: lock, re-check, write. The pre-read can be stale; a concurrent write that set this
     * very icon meanwhile makes it a no-op, one that set an icon makes {@code ifAbsent} skip.
     */
    private boolean apply(PreparedIcon prepared, AdminActor actor, boolean ifAbsent, boolean evict) {
        PlatformCategory category = requireIconable(platformCategoryRepository.findByNameForUpdate(prepared.name()));
        String previousKey = category.getIconKey();
        if ((ifAbsent && previousKey != null) || prepared.icon().sha256().equals(category.getIconSha256())) {
            return false;
        }
        Map<String, Object> before = iconFields(previousKey, category.getIconSha256());
        category.changeIcon(prepared.key(), prepared.icon().sha256(), OffsetDateTime.now(clock));
        auditService.record(actor, ICON_SET_ACTION, CatalogAuditTargetType.CATEGORY, category.getName(), before,
                iconFields(prepared.key(), prepared.icon().sha256()));
        if (evict) {
            // iconUrl lives only in the platform-catalog caches; masterServices / booking-slug-info hold no icon.
            cacheEvictor.evictPlatformCatalog();
        }
        if (previousKey != null && !previousKey.equals(prepared.key())) {
            blobPurger.purgeAfterCommit(List.of(previousKey), PURGE_CONTEXT);
        }
        return true;
    }

    private static String keyFor(String categoryName, SanitizedIcon icon) {
        return KEY_PREFIX + categoryName + "/" + icon.sha256().substring(0, KEY_HASH_LENGTH) + ".svg";
    }

    /** Over-long text is refused before it is encoded (chars ≤ UTF-8 bytes, so it is over the byte cap too). */
    private static SanitizedIcon sanitize(String svgText) {
        if (svgText == null) {
            throw new CategoryIconInvalidException(CategoryIconInvalidException.Reason.NOT_SVG);
        }
        if (svgText.length() > CategoryIconSanitizer.MAX_BYTES) {
            throw new CategoryIconInvalidException(CategoryIconInvalidException.Reason.TOO_LARGE);
        }
        return CategoryIconSanitizer.sanitize(svgText.getBytes(StandardCharsets.UTF_8));
    }

    /** {@link LinkedHashMap}, not {@code Map.of}: the first icon's {@code before} values are {@code null}. */
    private static Map<String, Object> iconFields(String iconKey, String sha256) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("iconKey", iconKey);
        fields.put("sha256", sha256);
        return fields;
    }

    /** An icon validated and already PUT under {@code key}, before any transaction. */
    private record PreparedIcon(String name, SanitizedIcon icon, String key) {}
}
