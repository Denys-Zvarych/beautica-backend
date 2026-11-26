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
import com.beautica.media.service.AfterCommitBlobPurger;
import com.beautica.media.service.R2StorageService;
import com.beautica.service.entity.PlatformCategory;
import com.beautica.service.repository.PlatformCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CategoryIconService — set / replace a category icon (B539)")
class CategoryIconServiceTest {

    private static final String CATEGORY = "MANICURE";
    private static final AdminActor ACTOR = new AdminActor("beautica.ua@gmail.com");
    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");
    private static final String SVG =
            "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\"><path d=\"M0 0L24 24\"/></svg>";
    private static final SanitizedIcon CANONICAL =
            CategoryIconSanitizer.sanitize(SVG.getBytes(StandardCharsets.UTF_8));
    private static final String NEW_KEY =
            "catalog/category-icons/MANICURE/" + CANONICAL.sha256().substring(0, 16) + ".svg";
    private static final String OLD_KEY = "catalog/category-icons/MANICURE/0123456789abcdef.svg";
    private static final String OLD_SHA = "0123456789abcdef".repeat(4);

    @Mock private PlatformCategoryRepository repository;
    @Mock private R2StorageService r2;
    @Mock private AfterCommitBlobPurger blobPurger;
    @Mock private CatalogAdminAuditService auditService;
    @Mock private CatalogCacheEvictor cacheEvictor;

    private final RecordingTx tx = new RecordingTx();
    private CategoryIconReferenceGuard referenceGuard;
    private CategoryIconService service;

    /** Runs the callback inline and records whether a "transaction" is open, so tests can pin the R2 PUT outside it. */
    private static final class RecordingTx implements TransactionOperations {
        private boolean active;

        @Override
        public <T> T execute(TransactionCallback<T> action) {
            active = true;
            try {
                return action.doInTransaction(null);
            } finally {
                active = false;
            }
        }
    }

    @BeforeEach
    void setUp() {
        referenceGuard = new CategoryIconReferenceGuard(repository);
        service = new CategoryIconService(repository, r2, blobPurger, auditService, cacheEvictor,
                new CategoryIconUrls(r2), Clock.fixed(NOW, ZoneOffset.UTC), tx, referenceGuard);
    }

    private PlatformCategory approvedWithIcon() {
        PlatformCategory category = PlatformCategory.ofApproved(CATEGORY, "Манікюр");
        category.changeIcon(OLD_KEY, OLD_SHA, OffsetDateTime.ofInstant(NOW.minusSeconds(60), ZoneOffset.UTC));
        return category;
    }

    private void storageOn() {
        when(r2.isEnabled()).thenReturn(true);
        when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> "https://cdn.example/" + inv.getArgument(0));
    }

    private void rowIs(PlatformCategory category) {
        when(repository.findByName(CATEGORY)).thenReturn(Optional.of(category));
        when(repository.findByNameForUpdate(CATEGORY)).thenReturn(Optional.of(category));
    }

    @Test
    @DisplayName("first icon: canonical bytes uploaded as image/svg+xml with the 1-year immutable header, row set, "
            + "ICON_SET audited with null before, PLATFORM-catalog caches evicted (never the per-master ones)")
    void should_uploadAuditAndEvict_when_firstIconSet() throws Exception {
        storageOn();
        PlatformCategory category = PlatformCategory.ofApproved(CATEGORY, "Манікюр");
        rowIs(category);

        CategoryIconResponse response = service.setIcon(CATEGORY, SVG, ACTOR);

        ArgumentCaptor<InputStream> body = ArgumentCaptor.forClass(InputStream.class);
        verify(r2).uploadFile(eq(NEW_KEY), body.capture(), eq((long) CANONICAL.bytes().length),
                eq("image/svg+xml"), eq("public, max-age=31536000, immutable"));
        assertThat(body.getValue().readAllBytes()).isEqualTo(CANONICAL.bytes());
        assertThat(category.getIconKey()).isEqualTo(NEW_KEY);
        assertThat(category.getIconSha256()).isEqualTo(CANONICAL.sha256());
        assertThat(category.getIconUpdatedAt()).isEqualTo(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("iconKey", null);
        before.put("sha256", null);
        verify(auditService).record(ACTOR, "ICON_SET", CatalogAuditTargetType.CATEGORY, CATEGORY, before,
                Map.of("iconKey", NEW_KEY, "sha256", CANONICAL.sha256()));
        verify(cacheEvictor).evictPlatformCatalog();
        verify(cacheEvictor, never()).evictAll();
        verify(blobPurger, never()).purgeAfterCommit(any(), anyString());
        assertThat(response).isEqualTo(new CategoryIconResponse(CATEGORY, "https://cdn.example/" + NEW_KEY));
    }

    @Test
    @DisplayName("the R2 PUT runs BEFORE the write transaction and the row lock (plain pre-read → PUT → lock)")
    void should_uploadOutsideTransaction_when_iconSet() {
        storageOn();
        rowIs(approvedWithIcon());
        doAnswer(inv -> {
            assertThat(tx.active).as("R2 PUT inside the write transaction").isFalse();
            return null;
        }).when(r2).uploadFile(anyString(), any(), anyLong(), anyString(), anyString());

        service.setIcon(CATEGORY, SVG, ACTOR);

        InOrder order = inOrder(repository, r2);
        order.verify(repository).findByName(CATEGORY);
        order.verify(r2).uploadFile(eq(NEW_KEY), any(), anyLong(), anyString(), anyString());
        order.verify(repository).findByNameForUpdate(CATEGORY);
    }

    @Test
    @DisplayName("replace: the previous object goes to the AFTER-COMMIT (reference-checked) purger, never deleted "
            + "inline; the uploaded object is released after the attempt (kept because the row references it)")
    void should_purgePreviousKeyAfterCommitOnly_when_iconReplaced() {
        storageOn();
        rowIs(approvedWithIcon());

        service.setIcon(CATEGORY, SVG, ACTOR);

        InOrder order = inOrder(r2, auditService, blobPurger);
        order.verify(r2).uploadFile(eq(NEW_KEY), any(), anyLong(), anyString(), anyString());
        order.verify(auditService).record(ACTOR, "ICON_SET", CatalogAuditTargetType.CATEGORY, CATEGORY,
                Map.of("iconKey", OLD_KEY, "sha256", OLD_SHA),
                Map.of("iconKey", NEW_KEY, "sha256", CANONICAL.sha256()));
        order.verify(blobPurger).purgeAfterCommit(List.of(OLD_KEY), "category-icon");
        order.verify(blobPurger).purgeAfterCompletion(List.of(NEW_KEY), "category-icon-upload");
        verify(r2, never()).deleteFiles(any());
        verify(r2, never()).deleteFile(anyString());
        verify(blobPurger, never()).dispatch(any(), anyString());
    }

    @Test
    @DisplayName("the uploaded key is claimed from before the PUT until the write transaction ends: a purge check in "
            + "that window sees it referenced with no committed row; released afterwards")
    void should_claimUploadedKeyUntilTransactionEnds_when_iconSet() {
        storageOn();
        rowIs(PlatformCategory.ofApproved(CATEGORY, "Манікюр"));
        List<Boolean> referencedDuring = new ArrayList<>();
        doAnswer(inv -> referencedDuring.add(referenceGuard.isReferenced(NEW_KEY)))
                .when(r2).uploadFile(eq(NEW_KEY), any(), anyLong(), anyString(), anyString());
        doAnswer(inv -> referencedDuring.add(referenceGuard.isReferenced(NEW_KEY)))
                .when(auditService).record(any(), anyString(), any(), anyString(), any(), any());

        service.setIcon(CATEGORY, SVG, ACTOR);

        assertThat(referencedDuring).containsExactly(true, true);
        assertThat(referenceGuard.isReferenced(NEW_KEY)).isFalse();
    }

    @Test
    @DisplayName("a failed PUT drops the claim (no write transaction follows to release it)")
    void should_releaseClaim_when_uploadFails() {
        when(r2.isEnabled()).thenReturn(true);
        when(repository.findByName(CATEGORY)).thenReturn(Optional.of(approvedWithIcon()));
        doThrow(new IllegalStateException("r2 down"))
                .when(r2).uploadFile(eq(NEW_KEY), any(), anyLong(), anyString(), anyString());

        Throwable thrown = catchThrowable(() -> service.setIcon(CATEGORY, SVG, ACTOR));

        assertThat(thrown).isInstanceOf(IllegalStateException.class);
        assertThat(referenceGuard.isReferenced(NEW_KEY)).isFalse();
    }

    @Test
    @DisplayName("failure inside the write transaction after the PUT: the uploaded object is released (purged — no "
            + "committed row references it), the previous one is left alone, nothing audited")
    void should_releaseUploadAndKeepOldObject_when_writeTransactionFails() {
        when(r2.isEnabled()).thenReturn(true);
        when(repository.findByName(CATEGORY)).thenReturn(Optional.of(approvedWithIcon()));
        when(repository.findByNameForUpdate(CATEGORY)).thenThrow(new CannotAcquireLockException("lock timeout"));

        Throwable thrown = catchThrowable(() -> service.setIcon(CATEGORY, SVG, ACTOR));

        assertThat(thrown).isInstanceOf(CannotAcquireLockException.class);
        verify(blobPurger).purgeAfterCompletion(List.of(NEW_KEY), "category-icon-upload");
        verify(blobPurger, never()).purgeAfterCommit(any(), anyString());
        verifyNoInteractions(auditService, cacheEvictor);
    }

    @Test
    @DisplayName("same canonical sha256 as the stored icon: 200 no-op — no upload, no lock, no audit, no eviction")
    void should_doNothing_when_sameIconResent() {
        storageOn();
        PlatformCategory category = PlatformCategory.ofApproved(CATEGORY, "Манікюр");
        category.changeIcon(NEW_KEY, CANONICAL.sha256(), OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(repository.findByName(CATEGORY)).thenReturn(Optional.of(category));

        CategoryIconResponse response = service.setIcon(CATEGORY, "<!-- same icon -->" + SVG, ACTOR);

        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString(), anyString());
        verify(repository, never()).findByNameForUpdate(anyString());
        verifyNoInteractions(auditService, cacheEvictor, blobPurger);
        assertThat(response.iconUrl()).isEqualTo("https://cdn.example/" + NEW_KEY);
    }

    @Test
    @DisplayName("same icon committed by a concurrent write between pre-read and lock: no-op under the lock, the "
            + "uploaded object released (the guard keeps it — the row references it)")
    void should_beNoOpUnderLock_when_concurrentWriteSetSameIcon() {
        storageOn();
        PlatformCategory locked = PlatformCategory.ofApproved(CATEGORY, "Манікюр");
        locked.changeIcon(NEW_KEY, CANONICAL.sha256(), OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(repository.findByName(CATEGORY)).thenReturn(Optional.of(approvedWithIcon()));
        when(repository.findByNameForUpdate(CATEGORY)).thenReturn(Optional.of(locked));

        service.setIcon(CATEGORY, SVG, ACTOR);

        verifyNoInteractions(auditService, cacheEvictor);
        verify(blobPurger, never()).purgeAfterCommit(any(), anyString());
        verify(blobPurger).purgeAfterCompletion(List.of(NEW_KEY), "category-icon-upload");
    }

    @Test
    @DisplayName("PENDING request: takes an icon (attachable before approval)")
    void should_setIcon_when_categoryIsPending() {
        storageOn();
        PlatformCategory pending = PlatformCategory.ofPendingRequest(CATEGORY, "Манікюр", UUID.randomUUID(),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), null);
        rowIs(pending);

        service.setIcon(CATEGORY, SVG, ACTOR);

        assertThat(pending.getIconKey()).isEqualTo(NEW_KEY);
    }

    @Test
    @DisplayName("REJECTED request: 409 CATEGORY_REJECTED, nothing uploaded or audited")
    void should_throwCategoryRejected_when_categoryIsRejected() {
        when(r2.isEnabled()).thenReturn(true);
        PlatformCategory rejected = PlatformCategory.ofPendingRequest(CATEGORY, "Манікюр", UUID.randomUUID(),
                OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), null);
        rejected.reject(OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC));
        when(repository.findByName(CATEGORY)).thenReturn(Optional.of(rejected));

        Throwable thrown = catchThrowable(() -> service.setIcon(CATEGORY, SVG, ACTOR));

        assertThat(thrown).isInstanceOfSatisfying(CatalogRequestConflictException.class,
                ex -> assertThat(ex.getCode()).isEqualTo(CatalogRequestConflictException.Code.CATEGORY_REJECTED));
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString(), anyString());
        verifyNoInteractions(auditService, cacheEvictor, blobPurger);
    }

    @Test
    @DisplayName("unknown category: 404 before any upload")
    void should_throwNotFound_when_categoryUnknown() {
        when(r2.isEnabled()).thenReturn(true);
        when(repository.findByName(CATEGORY)).thenReturn(Optional.empty());

        Throwable thrown = catchThrowable(() -> service.setIcon(CATEGORY, SVG, ACTOR));

        assertThat(thrown).isInstanceOf(NotFoundException.class);
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString(), anyString());
    }

    @Test
    @DisplayName("invalid SVG: ICON_INVALID before the row is locked")
    void should_rejectBeforeLocking_when_svgInvalid() {
        when(r2.isEnabled()).thenReturn(true);
        String script = SVG.replace("<path", "<script>alert(1)</script><path");

        Throwable thrown = catchThrowable(() -> service.setIcon(CATEGORY, script, ACTOR));

        assertThat(thrown).isInstanceOfSatisfying(CategoryIconInvalidException.class,
                ex -> assertThat(ex.getReason()).isEqualTo(CategoryIconInvalidException.Reason.FORBIDDEN_ELEMENT));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("over-long text: TOO_LARGE without encoding or locking")
    void should_rejectTooLarge_when_textOverCap() {
        when(r2.isEnabled()).thenReturn(true);

        Throwable thrown = catchThrowable(() -> service.setIcon(CATEGORY, "x".repeat(16 * 1024 + 1), ACTOR));

        assertThat(thrown).isInstanceOfSatisfying(CategoryIconInvalidException.class,
                ex -> assertThat(ex.getReason()).isEqualTo(CategoryIconInvalidException.Reason.TOO_LARGE));
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("R2 disabled: 503, nothing read or written")
    void should_throwServiceUnavailable_when_storageDisabled() {
        when(r2.isEnabled()).thenReturn(false);

        Throwable thrown = catchThrowable(() -> service.setIcon(CATEGORY, SVG, ACTOR));

        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(repository, auditService, cacheEvictor, blobPurger);
    }

    @Test
    @DisplayName("B540 setIconIfAbsent: no icon yet → set + audited, returns true, no per-call eviction")
    void should_setAndReturnTrue_when_absent() {
        when(r2.isEnabled()).thenReturn(true);
        PlatformCategory category = PlatformCategory.ofApproved(CATEGORY, "Манікюр");
        rowIs(category);

        boolean set = service.setIconIfAbsent(CATEGORY, SVG, ACTOR);

        assertThat(set).isTrue();
        assertThat(category.getIconKey()).isEqualTo(NEW_KEY);
        verify(auditService).record(any(), eq("ICON_SET"), any(), eq(CATEGORY), any(), any());
        verifyNoInteractions(cacheEvictor); // the backfill evicts once after its whole run
    }

    @Test
    @DisplayName("B540 setIconIfAbsent: an existing (different) icon is never overwritten — false, nothing written")
    void should_returnFalseAndKeepIcon_when_iconPresent() {
        when(r2.isEnabled()).thenReturn(true);
        PlatformCategory category = approvedWithIcon();
        when(repository.findByName(CATEGORY)).thenReturn(Optional.of(category));

        boolean set = service.setIconIfAbsent(CATEGORY, SVG, ACTOR);

        assertThat(set).isFalse();
        assertThat(category.getIconKey()).isEqualTo(OLD_KEY);
        verify(r2, never()).uploadFile(anyString(), any(), anyLong(), anyString(), anyString());
        verifyNoInteractions(auditService, cacheEvictor, blobPurger);
    }

    @Test
    @DisplayName("malformed wire name: 400 before anything else")
    void should_throwBadRequest_when_wireNameMalformed() {
        Throwable thrown = catchThrowable(() -> service.setIcon("manicure/../x", SVG, ACTOR));

        assertThat(thrown).isInstanceOfSatisfying(BusinessException.class,
                ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verifyNoInteractions(r2, repository);
    }

    @Test
    @DisplayName("B540 setIconIfAbsent: an icon set between pre-read and lock wins — false, nothing audited, the "
            + "uploaded object released")
    void should_returnFalseAndReleaseUpload_when_iconAppearsBeforeLock() {
        when(r2.isEnabled()).thenReturn(true);
        when(repository.findByName(CATEGORY)).thenReturn(Optional.of(PlatformCategory.ofApproved(CATEGORY, "Манікюр")));
        when(repository.findByNameForUpdate(CATEGORY)).thenReturn(Optional.of(approvedWithIcon()));

        boolean set = service.setIconIfAbsent(CATEGORY, SVG, ACTOR);

        assertThat(set).isFalse();
        verifyNoInteractions(auditService, cacheEvictor);
        verify(blobPurger).purgeAfterCompletion(List.of(NEW_KEY), "category-icon-upload");
    }

    @Test
    @DisplayName("B541 createWithIcon: PUT first, outside the transaction; then the row and the icon in one transaction")
    void should_uploadThenCreateAndSetInOneTransaction_when_createWithIcon() {
        when(r2.isEnabled()).thenReturn(true);
        PlatformCategory fresh = PlatformCategory.ofApproved(CATEGORY, CATEGORY);
        when(repository.findByNameForUpdate(CATEGORY)).thenReturn(Optional.of(fresh));
        doAnswer(inv -> {
            assertThat(tx.active).as("R2 PUT inside the create transaction").isFalse();
            return null;
        }).when(r2).uploadFile(anyString(), any(), anyLong(), anyString(), anyString());

        String created = service.createWithIcon(CATEGORY, SVG, ACTOR, () -> {
            assertThat(tx.active).isTrue();
            repository.save(fresh);
            return "created";
        });

        assertThat(created).isEqualTo("created");
        assertThat(fresh.getIconKey()).isEqualTo(NEW_KEY);
        InOrder order = inOrder(r2, repository, blobPurger);
        order.verify(r2).uploadFile(eq(NEW_KEY), any(), anyLong(), anyString(), anyString());
        order.verify(repository).save(fresh);
        order.verify(repository).findByNameForUpdate(CATEGORY);
        order.verify(blobPurger).purgeAfterCompletion(List.of(NEW_KEY), "category-icon-upload");
    }

    @Test
    @DisplayName("B541 createWithIcon: the create fails → the exception propagates and the uploaded object is released")
    void should_releaseUpload_when_createRowFails() {
        when(r2.isEnabled()).thenReturn(true);

        Throwable thrown = catchThrowable(() -> service.createWithIcon(CATEGORY, SVG, ACTOR, () -> {
            throw new DataIntegrityViolationException("duplicate");
        }));

        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        verify(blobPurger).purgeAfterCompletion(List.of(NEW_KEY), "category-icon-upload");
        verifyNoInteractions(auditService, cacheEvictor);
    }

    @Test
    @DisplayName("B541 createWithIcon evicts NO cache itself — InternalCategoryService.create's @CacheEvict is the one "
            + "eviction (perf INFO: was evicted twice)")
    void should_notEvictCaches_when_createWithIconCommits() {
        when(r2.isEnabled()).thenReturn(true);
        PlatformCategory fresh = PlatformCategory.ofApproved(CATEGORY, CATEGORY);
        when(repository.findByNameForUpdate(CATEGORY)).thenReturn(Optional.of(fresh));

        service.createWithIcon(CATEGORY, SVG, ACTOR, () -> "created");

        assertThat(fresh.getIconKey()).as("icon set in the create transaction").isEqualTo(NEW_KEY);
        verify(auditService).record(eq(ACTOR), eq("ICON_SET"), any(), eq(CATEGORY), any(), any());
        verifyNoInteractions(cacheEvictor);
    }
}
