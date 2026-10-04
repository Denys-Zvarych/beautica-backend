package com.beautica.media.service;

import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.exception.ServiceUnavailableMessages;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.media.dto.AvatarResponse;
import com.beautica.media.dto.MediaFileResponse;
import com.beautica.media.entity.EntityType;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.entity.MediaType;
import com.beautica.media.repository.MediaFileKey;
import com.beautica.media.repository.MediaRepository;
import com.beautica.media.repository.UploaderMediaKey;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.dto.ServiceDefinitionResponse;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.service.service.ServiceCatalogService;
import com.beautica.service.service.ServicePhotoBlobPurger;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Media service for avatar and portfolio uploads/deletes.
 *
 * <p><b>Key contract — server-generated R2 keys only.</b> Keys are always built from
 * the detected MIME type (never the client-supplied filename or Content-Type header).
 * The resulting key matches the pattern {@code [A-Za-z0-9/_\-.]{1,256}}, so the
 * defense-in-depth key validator in {@code R2StorageService} is not required here.
 *
 * <p><b>Content-type spoofing defense.</b> {@link #openAndSniff(MultipartFile)} reads
 * the first 12 bytes of the file and matches against the magic-byte signatures for
 * JPEG, PNG, and WebP. SVG is intentionally rejected (it is XML and can carry
 * {@code <script>}). The detected MIME — never {@code file.getContentType()} — is
 * what gets passed to {@link R2StorageService#uploadFile} and stored as the extension.
 *
 * <p><b>Stream lifecycle.</b> The upload stream is opened ONCE per upload
 * ({@code getInputStream()} is called a single time): it is wrapped in a {@code BufferedInputStream},
 * sniffed with mark/reset and passed on, rewound, to R2. The AWS SDK's
 * {@code RequestBody.fromInputStream} does not close the caller's stream, so the upload methods
 * close it via try-with-resources on {@code SniffedUpload}.
 *
 * <p><b>Transaction scoping (Perf MEDIUM #1 + #2).</b> There is no class-level
 * {@link Transactional} annotation — R2 HTTP calls (which can block up to the 30 s
 * socket timeout) must never run while a HikariCP connection is held. Each public
 * method uses two {@link TransactionTemplate}s: {@code txRead} for short read-only
 * lookups and {@code txWrite} for the persistence step. R2 calls never run inside one:
 * uploads happen before the write transaction, and every delete of a blob a live row
 * pointed at runs strictly AFTER that row's transaction commits, on {@code blobPurgeExecutor}
 * ({@link AfterCommitBlobPurger}) — a failed or rolled-back write never touches R2 (M1).
 *
 * <p><b>SEC-2 / §O8 — account deletion.</b> The {@code ON DELETE CASCADE} on
 * {@code media_files.uploader_id} has no hook into R2, so every user-deletion flow captures the
 * account's blob pointers BEFORE the delete and registers {@link #purgeUserBlobsAfterCommit}
 * via {@code AccountBlobPurgeRegistrar} (client/staff self-delete, staff disposal).
 *
 * <p><b>Phase 7.7 — portfolio cache.</b> {@link #getPortfolio} is the public
 * unauthenticated read path; it is annotated with {@link Cacheable} on the
 * {@code portfolio} cache keyed by {@code (entityType, entityId)}. Eviction on
 * writes is programmatic — not {@link org.springframework.cache.annotation.CacheEvict} —
 * so the cache is only invalidated AFTER the write transaction commits. The
 * existing {@link TransactionTemplate#execute} returns post-commit, so calling
 * {@link Cache#evictIfPresent} right after that block is naturally post-commit.
 */
@Slf4j
@Service
public class MediaService {

    /** Recognized image MIME types and their canonical extensions (used in R2 keys). */
    private static final Map<String, String> MIME_TO_EXT = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp"
    );

    /** 5 MB upload cap — applied before reading the magic bytes. */
    private static final long MAX_FILE_BYTES = 5L * 1024L * 1024L;

    /** Number of bytes inspected for the magic-byte check. WebP's signature needs the 9th–12th. */
    private static final int HEADER_BYTES = 12;
    private static final int SNIFF_BUFFER_BYTES = 8192;

    /** Cache name for the public portfolio listing — must match {@code CacheConfig.cacheManager()}. */
    static final String PORTFOLIO_CACHE = "portfolio";

    /** Log-only labels for {@link AfterCommitBlobPurger} (never a key). */
    private static final String AVATAR_PURGE_CONTEXT = "avatar";
    private static final String ACCOUNT_PURGE_CONTEXT = "account-delete";
    private static final String PORTFOLIO_PURGE_CONTEXT = "portfolio-delete";

    /** Phase 343 D4 key root for salon logo/cover blobs: {@code salons/<salonId>/{logo,cover}/...}. */
    private static final String SALON_IMAGE_KEY_ROOT = "salons/";
    /** Portfolio key roots written by {@link #resolvePortfolioTarget} — the ONLY {@code media_files} writer. */
    private static final String SALON_PORTFOLIO_KEY_ROOT = "portfolio/salons/";
    private static final String MASTER_PORTFOLIO_KEY_ROOT = "portfolio/independent/";

    private final R2StorageService r2;
    private final MediaRepository mediaRepo;
    private final UserRepository userRepo;
    private final SalonRepository salonRepo;
    private final MasterRepository masterRepo;
    private final Clock clock;
    private final TransactionTemplate txRead;
    private final TransactionTemplate txWrite;
    private final CacheManager cacheManager;
    private final ServiceRepository serviceRepo;
    private final ServiceCatalogService serviceCatalogService;
    private final ServicePhotoBlobPurger servicePhotoBlobPurger;
    private final AfterCommitBlobPurger afterCommitBlobPurger;

    @Autowired
    public MediaService(R2StorageService r2,
                        MediaRepository mediaRepo,
                        UserRepository userRepo,
                        SalonRepository salonRepo,
                        MasterRepository masterRepo,
                        Clock clock,
                        PlatformTransactionManager transactionManager,
                        CacheManager cacheManager,
                        ServiceRepository serviceRepo,
                        ServiceCatalogService serviceCatalogService,
                        ServicePhotoBlobPurger servicePhotoBlobPurger,
                        AfterCommitBlobPurger afterCommitBlobPurger) {
        this.r2 = r2;
        this.mediaRepo = mediaRepo;
        this.userRepo = userRepo;
        this.salonRepo = salonRepo;
        this.masterRepo = masterRepo;
        this.clock = clock;
        this.txRead = new TransactionTemplate(transactionManager);
        this.txRead.setReadOnly(true);
        this.txRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.txWrite = new TransactionTemplate(transactionManager);
        this.txWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.cacheManager = cacheManager;
        this.serviceRepo = serviceRepo;
        this.serviceCatalogService = serviceCatalogService;
        this.servicePhotoBlobPurger = servicePhotoBlobPurger;
        this.afterCommitBlobPurger = afterCommitBlobPurger;
    }

    /**
     * Test-only constructor that accepts pre-built {@link TransactionTemplate}s so the
     * Spring infrastructure does not need to be stood up in unit tests. Production code
     * uses the {@link PlatformTransactionManager} constructor above.
     */
    MediaService(R2StorageService r2,
                 MediaRepository mediaRepo,
                 UserRepository userRepo,
                 SalonRepository salonRepo,
                 MasterRepository masterRepo,
                 Clock clock,
                 TransactionTemplate txRead,
                 TransactionTemplate txWrite,
                 CacheManager cacheManager,
                 ServiceRepository serviceRepo,
                 ServiceCatalogService serviceCatalogService,
                 ServicePhotoBlobPurger servicePhotoBlobPurger,
                 AfterCommitBlobPurger afterCommitBlobPurger) {
        this.r2 = r2;
        this.mediaRepo = mediaRepo;
        this.userRepo = userRepo;
        this.salonRepo = salonRepo;
        this.masterRepo = masterRepo;
        this.clock = clock;
        this.txRead = txRead;
        this.txWrite = txWrite;
        this.cacheManager = cacheManager;
        this.serviceRepo = serviceRepo;
        this.serviceCatalogService = serviceCatalogService;
        this.servicePhotoBlobPurger = servicePhotoBlobPurger;
        this.afterCommitBlobPurger = afterCommitBlobPurger;
    }

    /**
     * Uploads must fail loudly when storage is off — otherwise an empty URL and a key for a
     * non-existent blob would be persisted. Deletes stay no-ops (account/salon sweeps).
     */
    private void requireStorageEnabled() {
        if (!r2.isEnabled()) {
            throw new BusinessException(HttpStatus.SERVICE_UNAVAILABLE, ServiceUnavailableMessages.MEDIA_STORAGE_NOT_CONFIGURED);
        }
    }

    // ------------------------------------------------------------------ avatar

    public AvatarResponse uploadAvatar(UUID userId, MultipartFile file) {
        requireStorageEnabled();
        try (SniffedUpload upload = openAndSniff(file)) {
            return uploadAvatarSniffed(userId, file, upload);
        }
    }

    private AvatarResponse uploadAvatarSniffed(UUID userId, MultipartFile file, SniffedUpload upload) {
        String detectedMime = upload.mime();

        // Step 1 — read-gate (404 for an unknown user); no lock held, connection released before R2.
        // existsById: a COUNT-style probe, no User entity hydrated just to be discarded.
        if (!txRead(() -> userRepo.existsById(userId))) {
            throw new NotFoundException("User not found: " + userId);
        }

        // Step 2 — upload the NEW blob first, under a fresh unique key, outside any transaction. The old
        // blob is untouched, so a failed upload leaves the old avatar fully intact (same flow as the
        // service photo — see uploadServicePhoto).
        String newKey = buildKey(avatarPrefix(userId), detectedMime);
        r2.uploadFile(newKey, upload.stream(), file.getSize(), detectedMime);
        String newUrl = r2.buildPublicUrl(newKey);

        // Step 3 — short write tx: row-lock the user, read the CURRENT pointers (whatever a concurrent
        // replace committed), write the new ones, and register the after-commit delete of exactly the
        // superseded blob. If the write fails, delete the NEW blob — but only if it is not referenced.
        try {
            txWrite.execute(status -> replaceAvatarLocked(userId, newKey, newUrl));
        } catch (RuntimeException ex) {
            discardAvatarBlobUnlessCommitted(userId, newKey);
            throw ex;
        }

        return new AvatarResponse(newUrl);
    }

    /** Locked write step of the avatar replace: runs inside {@code txWrite}. */
    private Void replaceAvatarLocked(UUID userId, String newKey, String newUrl) {
        User u = userRepo.findByIdForUpdate(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        String supersededKey = resolveAvatarKey(userId, u.getAvatarR2Key(), u.getAvatarUrl());
        u.setAvatarR2Key(newKey);
        u.setAvatarUrl(newUrl);
        // No save(): u is MANAGED (loaded by findByIdForUpdate in this tx) — dirty checking flushes it on commit.
        // afterCommit hook (we are inside txWrite): a rolled-back write never fires it.
        purgeAvatarKeyAfterCommit(userId, supersededKey);
        return null;
    }

    /**
     * Failure cleanup for the avatar replace — same "discard unless committed" rule as
     * {@link #discardBlobUnlessCommitted}: a failure from the COMMIT acknowledgement may have committed, so the
     * row is re-read and the new blob deleted ONLY when positively known not to be referenced.
     */
    private void discardAvatarBlobUnlessCommitted(UUID userId, String newKey) {
        boolean referenced;
        try {
            referenced = txRead(() -> userRepo.findById(userId)
                    .map(User::getAvatarR2Key)
                    .filter(newKey::equals)
                    .isPresent());
        } catch (RuntimeException readEx) {
            log.warn("Could not verify avatar commit state; keeping new blob (key=[key omitted]): {}",
                    readEx.getClass().getSimpleName());
            return;
        }
        if (!referenced) {
            discardUnreferencedBlob(newKey);
        }
    }

    /**
     * Removes the avatar. DB pointers are cleared FIRST under the row lock (both, whenever either is set —
     * a legacy row carries only {@code avatar_url}); the blob is deleted after commit, so an R2 failure never
     * leaves a live pointer to a deleted object. A legacy row's key is recovered from its URL.
     */
    public void deleteAvatar(UUID userId) {
        txWrite.execute(status -> {
            User u = userRepo.findByIdForUpdate(userId)
                    .orElseThrow(() -> new NotFoundException("User not found: " + userId));
            if (u.getAvatarR2Key() == null && u.getAvatarUrl() == null) {
                return null;
            }
            String key = resolveAvatarKey(userId, u.getAvatarR2Key(), u.getAvatarUrl());
            u.setAvatarR2Key(null);
            u.setAvatarUrl(null);
            // No save(): u is MANAGED (findByIdForUpdate in this tx) — dirty checking flushes it on commit.
            purgeAvatarKeyAfterCommit(userId, key);
            return null;
        });
    }

    private static String avatarPrefix(UUID userId) {
        return "avatars/" + userId + "/";
    }

    /**
     * The single place that decides which R2 object an avatar owns. Prefers the stored key; for a legacy row
     * (URL set, key null) recovers it from the public URL via {@link R2StorageService#extractKeyFromPublicUrl}
     * (host/prefix verified, no traversal). Either way the key is accepted ONLY under {@code avatars/<userId>/}
     * — a foreign or corrupted pointer must never become an arbitrary-object delete. Returns {@code null}
     * when there is nothing safe to delete.
     */
    public String resolveAvatarKey(UUID userId, String avatarR2Key, String avatarUrl) {
        String prefix = avatarPrefix(userId);
        String candidate = avatarR2Key != null
                ? avatarR2Key
                : r2.extractKeyFromPublicUrl(avatarUrl).orElse(null);
        return candidate != null && candidate.startsWith(prefix) && !candidate.contains("..")
                ? candidate
                : null;
    }

    /**
     * After-commit best-effort delete of a superseded/cleared avatar blob. The R2 round-trip runs on the
     * bounded {@code blobPurgeExecutor} (P-M2), never on the committing thread; a rollback purges nothing.
     */
    private void purgeAvatarKeyAfterCommit(UUID userId, String key) {
        if (key == null || !key.startsWith(avatarPrefix(userId))) {
            return;
        }
        afterCommitBlobPurger.purgeAfterCommit(List.of(key), AVATAR_PURGE_CONTEXT);
    }

    // ----------------------------------------------------------- service photo

    /**
     * Sets or replaces the single photo of a service definition (Phase 342). Authorization
     * ({@code canManageServiceDefinition}) is enforced by the controller's {@code @PreAuthorize}; an
     * inactive definition (or one whose salon is inactive) answers 404 like every other service endpoint.
     *
     * <p><b>Deliberately NOT the avatar flow's "SEC-2 ordering"</b> (delete old blob, upload new, write
     * row). That order loses the old photo when the upload fails, and two concurrent uploads both read the
     * same old key so one orphans a blob. This flow is instead:
     * <ol>
     *   <li>read-gate (404 when inactive), no lock held;</li>
     *   <li>upload the NEW blob under a fresh unique key, outside any transaction;</li>
     *   <li>{@code txWrite}: row-lock the definition ({@code FOR UPDATE}), re-check it is still active
     *       (race with deactivate), read the CURRENT key (whatever a concurrent upload committed), write
     *       the new key/url;</li>
     *   <li>after commit, delete exactly that superseded key via {@link ServicePhotoBlobPurger}
     *       (best-effort — a failure is an accepted orphan, never a lost photo);</li>
     *   <li>if the write fails or the definition went inactive, delete the NEW blob best-effort and
     *       rethrow, so the old photo and its pointer stay intact — but ONLY after re-reading the row and
     *       confirming it does not reference the new key (see {@link #discardBlobUnlessCommitted}): a
     *       failure surfacing from the COMMIT acknowledgement may have actually committed.</li>
     * </ol>
     * Because unique keys are never reused and every committed writer deletes the key it replaced under
     * the row lock, N concurrent uploads leave exactly one live blob.
     */
    public ServiceDefinitionResponse uploadServicePhoto(UUID serviceDefId, MultipartFile file) {
        requireStorageEnabled();
        try (SniffedUpload upload = openAndSniff(file)) {
            return uploadServicePhotoSniffed(serviceDefId, file, upload);
        }
    }

    private ServiceDefinitionResponse uploadServicePhotoSniffed(
            UUID serviceDefId, MultipartFile file, SniffedUpload upload) {
        requireActiveServiceDefinition(serviceDefId);

        String newKey = buildKey("services/" + serviceDefId + "/", upload.mime());
        r2.uploadFile(newKey, upload.stream(), file.getSize(), upload.mime());
        String newUrl = r2.buildPublicUrl(newKey);

        PhotoResult result;
        try {
            result = txWrite.execute(status -> replacePhotoLocked(serviceDefId, newKey, newUrl));
        } catch (RuntimeException ex) {
            discardBlobUnlessCommitted(serviceDefId, newKey);
            throw ex;
        }

        // Post-commit by construction (txWrite.execute returned) — same cache set the former PATCH evicted.
        serviceCatalogService.evictServicePhotoCaches(serviceDefId, result.ownerType(), result.ownerId());
        return result.body();
    }

    /** Locked write step of the replace: runs inside {@code txWrite}. */
    private PhotoResult replacePhotoLocked(UUID serviceDefId, String newKey, String newUrl) {
        ServiceDefinition definition = lockActiveDefinition(serviceDefId);
        String supersededKey = definition.getPhotoR2Key();
        definition.setPhotoR2Key(newKey);
        definition.setPhotoUrl(newUrl);
        ServiceDefinition saved = serviceRepo.save(definition);
        // Registered as an afterCommit hook (we are inside txWrite); a rolled-back write never fires it.
        servicePhotoBlobPurger.purgeAfterCommit(serviceDefId, supersededKey);
        return toPhotoResult(saved);
    }

    /**
     * Removes a service definition's photo. Idempotent: a definition with no photo is a no-op (204, no R2
     * call). DB pointers are cleared FIRST under the row lock; the blob (by stored KEY) is deleted after
     * commit via {@link ServicePhotoBlobPurger}, so an R2 failure still leaves the DB cleared (accepted
     * orphan) and never a live pointer to a deleted blob. A legacy row (URL, no key) has its URL cleared
     * and nothing deleted in R2.
     */
    public void deleteServicePhoto(UUID serviceDefId) {
        requireActiveServiceDefinition(serviceDefId);

        PhotoCleared cleared = txWrite.execute(status -> {
            ServiceDefinition definition = lockActiveDefinition(serviceDefId);
            if (definition.getPhotoR2Key() == null && definition.getPhotoUrl() == null) {
                return null;
            }
            String key = definition.getPhotoR2Key();
            definition.setPhotoR2Key(null);
            definition.setPhotoUrl(null);
            serviceRepo.save(definition);
            servicePhotoBlobPurger.purgeAfterCommit(serviceDefId, key);
            return new PhotoCleared(definition.getOwnerType(), definition.getOwnerId());
        });

        if (cleared != null) {
            serviceCatalogService.evictServicePhotoCaches(serviceDefId, cleared.ownerType(), cleared.ownerId());
        }
    }

    /** Read-gate: 404 unless the definition AND (for a salon-owned one) its salon are active. */
    private void requireActiveServiceDefinition(UUID serviceDefId) {
        txRead(() -> serviceRepo.findIdIfDefinitionAndOwnerActive(serviceDefId)
                .orElseThrow(() -> new NotFoundException("Service definition not found: " + serviceDefId)));
    }

    /**
     * Row-locks the definition and re-checks it is still active — the in-lock half of the 404 rule (a
     * deactivate that committed between the read-gate and here). An owning salon's deactivation
     * deactivates all its definitions in the same transaction, so the definition's own flag covers it.
     */
    private ServiceDefinition lockActiveDefinition(UUID serviceDefId) {
        return serviceRepo.findByIdForUpdate(serviceDefId)
                .filter(ServiceDefinition::isActive)
                .orElseThrow(() -> new NotFoundException("Service definition not found: " + serviceDefId));
    }

    /**
     * Failure cleanup for the replace. A {@link RuntimeException} out of {@code txWrite.execute} does not
     * prove the write rolled back: a failure on the COMMIT acknowledgement (connection drop, timeout) can
     * leave the new key committed, and deleting that blob would leave the row pointing at a deleted object.
     * So rather than classify exceptions as pre- or post-commit, re-read the row's current key in a fresh
     * read transaction and delete the new blob ONLY when the row is positively known NOT to reference it.
     * If the re-read itself fails the outcome is unknown, so the blob is kept: an orphaned blob is an
     * accepted, harmless leak, a dangling pointer is user-visible data loss.
     */
    private void discardBlobUnlessCommitted(UUID serviceDefId, String newKey) {
        boolean referenced;
        try {
            referenced = txRead(() -> serviceRepo.findById(serviceDefId)
                    .map(ServiceDefinition::getPhotoR2Key)
                    .filter(newKey::equals)
                    .isPresent());
        } catch (RuntimeException readEx) {
            log.warn("Could not verify service photo commit state; keeping new blob (key=[key omitted]): {}",
                    readEx.getClass().getSimpleName());
            return;
        }
        if (!referenced) {
            discardUnreferencedBlob(newKey);
        }
    }

    /** Best-effort delete of a freshly uploaded blob that no committed row references. */
    private void discardUnreferencedBlob(String key) {
        try {
            r2.deleteFile(key);
        } catch (RuntimeException cleanupEx) {
            // Key embeds the definition UUID — omit it from the log.
            log.warn("Failed to discard unreferenced blob (key=[key omitted]): {}",
                    cleanupEx.getClass().getSimpleName());
        }
    }

    private record PhotoCleared(OwnerType ownerType, UUID ownerId) {}

    private record PhotoResult(ServiceDefinitionResponse body,
                               OwnerType ownerType, UUID ownerId) {}

    private static PhotoResult toPhotoResult(ServiceDefinition saved) {
        return new PhotoResult(ServiceDefinitionResponse.from(saved), saved.getOwnerType(), saved.getOwnerId());
    }

    // --------------------------------------------------------------- portfolio

    public MediaFileResponse uploadPortfolioPhoto(UUID actorId, Role actorRole, MultipartFile file) {
        requireStorageEnabled();
        try (SniffedUpload upload = openAndSniff(file)) {
            return uploadPortfolioSniffed(actorId, actorRole, file, upload);
        }
    }

    private MediaFileResponse uploadPortfolioSniffed(
            UUID actorId, Role actorRole, MultipartFile file, SniffedUpload upload) {
        String detectedMime = upload.mime();

        // Step 1 — read tx: resolve the owning entity from the authenticated principal.
        // SEC-1: never trust a UUID from the request body — the salon/master is resolved
        // server-side from the actor's role.
        PortfolioTarget target = txRead(() -> resolvePortfolioTarget(actorId, actorRole));

        // Step 2 — R2 upload OUTSIDE any transaction.
        String key = buildKey(target.prefix(), detectedMime);
        r2.uploadFile(key, upload.stream(), file.getSize(), detectedMime);
        String publicUrl = r2.buildPublicUrl(key);

        // Step 3 — write tx: insert the row.
        MediaFile saved = txWrite.execute(status -> {
            User uploader = userRepo.getReferenceById(actorId);
            return mediaRepo.save(MediaFile.builder()
                    .uploader(uploader)
                    .entityType(target.entityType())
                    .entityId(target.entityId())
                    .mediaType(MediaType.PORTFOLIO)
                    .r2Key(key)
                    .r2Url(publicUrl)
                    .build());
        });

        // Step 4 — Phase 7.7: eviction is post-commit by construction — txWrite.execute()
        // returns only after the surrounding transaction has committed.
        evictPortfolioCache(target.entityType(), target.entityId());

        return MediaFileResponse.from(saved);
    }

    /**
     * Removes one portfolio photo. Same M1 invariant as {@link #deleteAvatar}: the DB row is deleted FIRST, inside
     * {@code txWrite}, and the blob is purged only AFTER that transaction commits (via
     * {@link AfterCommitBlobPurger#purgeAfterCommit}, on {@code blobPurgeExecutor}). A failed or rolled-back row
     * delete therefore never touches R2 — the listing can never point at a deleted object. An R2 failure after
     * commit is an accepted orphan blob (WARN + counter, key omitted).
     *
     * <p>Security SEC-N1 (same S-L5 gate as every sweep): the key reaches R2 ONLY when it sits under its own
     * row's portfolio root — a corrupted / raw-SQL-written {@code r2_key} must never become an arbitrary-object
     * delete. A mismatch skips R2 (counted WARN, key omitted) but the row is still removed.
     */
    public void deletePortfolioPhoto(UUID actorId, UUID mediaId) {
        DeleteTarget target = txWrite.execute(status -> deletePortfolioRowLocked(actorId, mediaId));

        // Phase 7.7: post-commit cache eviction. txWrite.execute() returns only after commit.
        evictPortfolioCache(target.entityType(), target.entityId());
    }

    /** Write step of {@link #deletePortfolioPhoto}: runs inside {@code txWrite}; registers the after-commit purge. */
    private DeleteTarget deletePortfolioRowLocked(UUID actorId, UUID mediaId) {
        MediaFile mf = mediaRepo.findById(mediaId)
                .orElseThrow(() -> new NotFoundException("Media not found: " + mediaId));
        if (!mf.getUploader().getId().equals(actorId)) {
            throw new ForbiddenException("Not allowed to delete this media");
        }
        DeleteTarget target = new DeleteTarget(mf.getR2Key(), mf.getEntityType(), mf.getEntityId());
        mediaRepo.delete(mf);
        if (isOwnPortfolioKey(target.entityType(), target.entityId(), target.r2Key())) {
            // afterCommit hook (we are inside txWrite): a rolled-back delete never fires it.
            afterCommitBlobPurger.purgeAfterCommit(List.of(target.r2Key()), PORTFOLIO_PURGE_CONTEXT);
        } else {
            logSkippedPortfolioKeys(1);
        }
        return target;
    }

    /** Resolved data for a portfolio delete — captured inside the write tx, scalars only. */
    private record DeleteTarget(String r2Key, EntityType entityType, UUID entityId) {}

    /**
     * Key builder shared by {@link Cacheable} SpEL expressions and the programmatic
     * {@link #evictPortfolioCache} helper. Using a plain {@code String} key avoids the
     * {@link SimpleKey} vs {@code List} mismatch that arises when mixing annotation-based
     * and programmatic cache access (Anti-Bug Playbook §F).
     */
    static String portfolioCacheKey(EntityType entityType, UUID entityId) {
        return entityType.name() + '_' + entityId;
    }

    /**
     * Non-paginated portfolio listing — kept for internal callers and tests.
     *
     * <p><b>Not cached.</b> The paginated overload
     * {@link #getPortfolio(EntityType, UUID, PageRequest)} owns the
     * {@code portfolio} cache slot for {@code (entityType, entityId)}. Caching
     * this overload with the same key caused a {@code ClassCastException} when
     * Caffeine returned a cached {@code List} to a caller expecting
     * {@code Page<MediaFileResponse>} (MEDIUM cache-key collision fix,
     * Anti-Bug Playbook §F). Internal callers that do not need pagination bear
     * the cost of an uncached read; the paginated controller path is the hot
     * public code path and remains cached.
     */
    @Transactional(readOnly = true)
    public List<MediaFileResponse> getPortfolio(EntityType entityType, UUID entityId) {
        // No need to touch getUploader() — MediaFileResponse.from does not access it,
        // so the LAZY association stays untouched and the listing avoids N+1.
        return mediaRepo.findByEntityTypeAndEntityId(entityType, entityId).stream()
                .map(MediaFileResponse::from)
                .toList();
    }

    /**
     * Paginated portfolio listing for the public GET portfolio controller endpoints
     * (PERF-M3).
     *
     * <p><b>This is the sole cached overload.</b> The non-paginated sibling
     * {@link #getPortfolio(EntityType, UUID)} is intentionally uncached to
     * prevent a {@code ClassCastException} from the shared cache key
     * {@code (entityType, entityId)}: Caffeine stores the first result and
     * returns it verbatim on the next hit, so a cached {@code List} returned to
     * a {@code Page}-expecting caller caused a 500 (MEDIUM fix, §F).
     *
     * <p>The controller always passes a fixed {@link PageRequest} (50 items,
     * sorted by {@code createdAt} DESC), ensuring the key is stable per entity
     * and avoids unbounded per-page cache entries. {@code sync = true} prevents
     * a thundering herd on popular public keys (Anti-Bug Playbook §F rule 7).
     * The inline SpEL string expression avoids the CGLIB-proxy visibility issue
     * that arises when referencing a non-public static method via
     * {@code T(ClassName).method()} (Anti-Bug §F).
     */
    /** Fixed sort applied regardless of the caller-supplied {@link PageRequest} sort. */
    private static final Sort PORTFOLIO_SORT = Sort.by(Sort.Direction.DESC, "createdAt");

    @Cacheable(value = PORTFOLIO_CACHE, sync = true,
               key = "#entityType.name() + '_' + #entityId")
    @Transactional(readOnly = true)
    public Page<MediaFileResponse> getPortfolio(EntityType entityType, UUID entityId, PageRequest pageable) {
        // Strip any caller-supplied sort and enforce createdAt DESC. The controller always
        // passes PORTFOLIO_PAGE (fixed sort), but this guard prevents a future call site
        // or test from accidentally injecting a different sort order, which would both
        // corrupt the deterministic cache key and allow arbitrary column enumeration.
        PageRequest sortedPageable = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                PORTFOLIO_SORT
        );
        return mediaRepo.findByEntityTypeAndEntityId(entityType, entityId, sortedPageable)
                .map(MediaFileResponse::from);
    }

    /**
     * Narrow eviction of a single {@code portfolio} cache entry. Called from the
     * write paths AFTER {@link TransactionTemplate#execute} has returned (and the
     * transaction has therefore committed), so a parallel reader cannot repopulate
     * stale data between the commit and the eviction.
     *
     * <p>Uses the same String key as the {@link Cacheable} annotation on
     * {@link #getPortfolio(EntityType, UUID, PageRequest)} — the sole cached
     * read path since the non-paginated overload was decached to fix the
     * MEDIUM cache-key collision (§F).
     */
    private void evictPortfolioCache(EntityType entityType, UUID entityId) {
        Cache cache = cacheManager.getCache(PORTFOLIO_CACHE);
        if (cache != null) {
            cache.evictIfPresent(portfolioCacheKey(entityType, entityId));
        }
    }

    // ---------------------------------------------------------- salon sweeper

    /**
     * Shared R2-plus-DB sweep body (REUSE-FIRST, Phase 268 D3), called only by {@link #deleteBySalon} — which
     * itself runs strictly after the salon-deletion transaction has committed, so this method is NOT bound by the
     * after-commit M1 rule that governs live writes (there is no live pointer left to protect: the salon is gone).
     *
     * <p><b>Order:</b> R2 FIRST — {@code extraKeys} (blobs that live OUTSIDE {@code media_files}, i.e. a salon's
     * logo/cover), then every own-prefix {@code rows} key in one batched {@code DeleteObjects} — THEN one batched
     * DB delete of {@code rows}, THEN post-commit portfolio-cache eviction. R2 deletion is best-effort: a failed
     * key is WARN-logged (key omitted) and the DB rows are still dropped — an accepted orphan blob, never a live
     * pointer to a deleted one.
     *
     * <p><b>Deliberately does NOT short-circuit on {@code rows.isEmpty()}</b> — a salon with a logo/cover but
     * zero portfolio photos (or the reverse) must still be swept. The only no-op is BOTH lists empty.
     *
     * @param rows      scalar {@code media_files} pointers (no entities), pre-read by the caller
     * @param extraKeys already-verified R2 keys for blobs that are not {@code media_files} rows
     * @param evictType together with {@code evictId}, an explicit portfolio-cache entry to evict in addition to
     *                  whatever {@code rows} themselves resolve to (covers the rows-empty case)
     */
    private void sweepBlobs(List<MediaFileKey> rows, List<String> extraKeys, EntityType evictType, UUID evictId) {
        if (rows.isEmpty() && extraKeys.isEmpty()) {
            return;
        }

        // R2 deletes OUTSIDE any transaction, batched into as few DeleteObjects round-trips as
        // R2StorageService#deleteFiles allows. Extra keys first, then rows.
        List<String> allKeys = new ArrayList<>(extraKeys.size() + rows.size());
        allKeys.addAll(extraKeys);
        int skipped = 0;
        for (MediaFileKey row : rows) {
            if (isOwnPortfolioKey(row.entityType(), row.entityId(), row.r2Key())) {
                allKeys.add(row.r2Key());
            } else {
                skipped++;
            }
        }
        logSkippedPortfolioKeys(skipped);

        // r2.deleteFiles never throws for a delete failure, partial or total (see its Javadoc) — it returns the
        // keys it could not delete. Only logged, never re-thrown; the DB rows are still dropped below.
        Set<String> failedKeys = allKeys.isEmpty() ? Set.of() : r2.deleteFiles(allKeys);
        for (String ignored : failedKeys) {
            // Key may encode an entity UUID — omit from WARN log to avoid PII in log aggregators.
            log.warn("R2 delete failed during media sweep (key=[key omitted])");
        }

        if (!rows.isEmpty()) {
            // deleteAllByIdInBatch: ONE `DELETE ... WHERE id IN (...)` (perf PERF-1) — no per-row merge SELECT.
            // Safe because MediaFile has no cascades, no orphanRemoval and no removal listener.
            List<UUID> ids = rows.stream().map(MediaFileKey::id).toList();
            txWrite.execute(status -> {
                mediaRepo.deleteAllByIdInBatch(ids);
                return null;
            });
        }

        Set<String> distinctKeys = new HashSet<>();
        for (MediaFileKey row : rows) {
            distinctKeys.add(portfolioCacheKey(row.entityType(), row.entityId()));
        }
        if (evictType != null && evictId != null) {
            distinctKeys.add(portfolioCacheKey(evictType, evictId));
        }
        evictPortfolioCacheKeys(distinctKeys);
    }

    /**
     * Purges a salon's imagery from R2 permanently — the Phase 268 D2/D3 sweep that {@code SalonService} runs on
     * {@code blobPurgeExecutor} AFTER its deletion transaction commits (D4/D8: never inline inside that
     * transaction, since {@link #txWrite} would otherwise hold a connection across R2 round-trips).
     *
     * <p>Covers all three imagery sets a deleted salon can carry (phase doc D2 table): the {@code media_files}
     * portfolio rows ({@code preReadRows}, captured as scalars INSIDE the deletion transaction, before a staff
     * hard-delete could cascade one away), and the logo/cover keys the caller resolved from
     * {@code salons.avatar_url}/{@code cover_image_url} via {@link #resolveSalonImageKey}.
     *
     * <p>Every {@code salonImageKeys} entry is RE-VERIFIED here against {@code salons/<salonId>/} (security S-M1,
     * defence in depth — no caller can hand this method an unchecked key); a mismatch is skipped and counted. The
     * {@code salons} pointer columns are nulled by the caller AFTER this returns (D4 R2-first-then-DB).
     *
     * @param salonId        the deleted salon's id — scopes the key check and names the explicit cache eviction
     * @param salonImageKeys logo/cover keys already resolved by {@link #resolveSalonImageKey} (never URLs)
     * @param preReadRows    the salon's {@code media_files} pointers, read before any staff hard-delete
     */
    public void deleteBySalon(UUID salonId, List<String> salonImageKeys, List<MediaFileKey> preReadRows) {
        List<String> extraKeys = new ArrayList<>(salonImageKeys.size());
        int skipped = 0;
        for (String key : salonImageKeys) {
            if (isOwnSalonImageKey(salonId, key)) {
                extraKeys.add(key);
            } else {
                skipped++;
            }
        }
        if (skipped > 0) {
            log.warn("Salon image purge skipped keys outside the salon's own prefix (salon={}, skippedCount={}, "
                    + "keys=[omitted])", salonId, skipped);
        }

        sweepBlobs(preReadRows, extraKeys, EntityType.SALON, salonId);
    }

    /**
     * The single place that decides which R2 object a salon logo/cover URL owns (security S-M1, mirrors
     * {@link #resolveAvatarKey(UUID, String, String)}). The key is recovered from the public URL via
     * {@link R2StorageService#extractKeyFromPublicUrl} (host verified, no traversal) and accepted ONLY under
     * the salon's own phase-343 root {@code salons/<salonId>/} — a foreign or corrupted pointer (another
     * salon's object, a user avatar, a portfolio key) must never become an arbitrary-object delete. A
     * mismatch is skipped with a WARN that omits the URL/key. Returns {@code null} when nothing is safe.
     */
    public String resolveSalonImageKey(UUID salonId, String url) {
        if (url == null) {
            return null;
        }
        String candidate = r2.extractKeyFromPublicUrl(url).orElse(null);
        if (isOwnSalonImageKey(salonId, candidate)) {
            return candidate;
        }
        log.warn("Salon image purge skipped: URL outside the salon's own key prefix (salon={}, url=[omitted])",
                salonId);
        return null;
    }

    /** S-M1 shape check shared by {@link #resolveSalonImageKey} and {@link #deleteBySalon}'s re-verification. */
    static boolean isOwnSalonImageKey(UUID salonId, String key) {
        return salonId != null && key != null && key.startsWith(SALON_IMAGE_KEY_ROOT + salonId + "/")
                && !key.contains("..");
    }

    private static void addIfPresent(List<String> keys, String key) {
        if (key != null) {
            keys.add(key);
        }
    }

    /**
     * The {@code media_files.r2_key} root a portfolio blob of {@code (entityType, entityId)} must live under —
     * derived from {@link #resolvePortfolioTarget}, the only writer: {@code portfolio/salons/<salonId>/} for
     * {@code SALON}, {@code portfolio/independent/<masterId>/} for {@code MASTER}. {@code USER} (and a null
     * type/id) has no portfolio root, so returns {@code null}.
     */
    static String portfolioKeyPrefix(EntityType entityType, UUID entityId) {
        if (entityType == null || entityId == null) {
            return null;
        }
        return switch (entityType) {
            case SALON -> SALON_PORTFOLIO_KEY_ROOT + entityId + "/";
            case MASTER -> MASTER_PORTFOLIO_KEY_ROOT + entityId + "/";
            case USER -> null;
        };
    }

    /**
     * Security S-L5: a {@code media_files.r2_key} reaches an R2 delete only when it sits under its OWN row's
     * portfolio root (plus V39's no-traversal shape), so a corrupted or raw-SQL-written row can never turn an
     * account/salon sweep into an arbitrary-object delete.
     */
    static boolean isOwnPortfolioKey(EntityType entityType, UUID entityId, String key) {
        String prefix = portfolioKeyPrefix(entityType, entityId);
        return prefix != null && key != null && key.startsWith(prefix) && !key.contains("..");
    }

    private static void logSkippedPortfolioKeys(int skipped) {
        if (skipped > 0) {
            log.warn("Media purge skipped keys outside their entity's portfolio prefix (skippedCount={}, keys=[omitted])",
                    skipped);
        }
    }

    /**
     * Permanently purges the R2 blobs of one or more hard-deleted accounts — called from the single
     * {@code afterCommit} that {@code AccountBlobPurgeRegistrar} registers (client/staff self-delete, and ONE
     * registration for a whole staff-disposal batch — P-M1). By then the {@code users} rows and their
     * cascaded {@code media_files} rows are gone, so this is R2-only plus portfolio-cache eviction (never
     * {@link #sweepBlobs}, whose {@code mediaRepo.deleteAllByIdInBatch} would target vanished rows).
     *
     * <p>The avatar pointer is RE-VERIFIED here against {@code avatars/<userId>/} (S-L1); the R2 round-trip
     * is handed to {@code blobPurgeExecutor} via {@link AfterCommitBlobPurger#dispatch} so the committing
     * thread does not hold its JDBC connection across R2 (P-M2). Best-effort: failures are WARN-logged
     * (keys omitted) and never re-thrown.
     *
     * @param accounts scalar pointers captured BEFORE the delete (no entities — P-L3)
     */
    public void purgeUserBlobsAfterCommit(List<AccountBlobPointers> accounts) {
        List<String> keys = new ArrayList<>();
        Set<String> distinctCacheKeys = new HashSet<>();
        int skipped = 0;
        for (AccountBlobPointers account : accounts) {
            addIfPresent(keys, verifiedAccountAvatarKey(account));
            for (UploaderMediaKey media : account.media()) {
                if (isOwnPortfolioKey(media.entityType(), media.entityId(), media.r2Key())) {
                    keys.add(media.r2Key());
                } else {
                    skipped++;
                }
                distinctCacheKeys.add(portfolioCacheKey(media.entityType(), media.entityId()));
            }
        }
        logSkippedPortfolioKeys(skipped);
        evictPortfolioCacheKeys(distinctCacheKeys);
        // ONE batched DeleteObjects hand-off for every account (P-M1; deleteFiles chunks at 1000), run on
        // blobPurgeExecutor so the committing thread releases its JDBC connection without waiting on R2 (P-M2).
        afterCommitBlobPurger.dispatch(keys, ACCOUNT_PURGE_CONTEXT);
    }

    /**
     * Re-checks the avatar pointer against {@code avatars/<userId>/} at the purge boundary (security S-L1):
     * no caller can hand this method an unchecked key. A non-null pointer that fails the check is skipped
     * with a WARN (key/url omitted).
     */
    private String verifiedAccountAvatarKey(AccountBlobPointers account) {
        if (account.avatarR2Key() == null && account.avatarUrl() == null) {
            return null;
        }
        String key = resolveAvatarKey(account.userId(), account.avatarR2Key(), account.avatarUrl());
        if (key == null) {
            log.warn("Account avatar purge skipped: pointer outside avatars/<userId>/ (user={}, key=[omitted])",
                    account.userId());
        }
        return key;
    }

    private void evictPortfolioCacheKeys(Set<String> cacheKeys) {
        if (cacheKeys.isEmpty()) {
            return;
        }
        Cache cache = cacheManager.getCache(PORTFOLIO_CACHE);
        if (cache != null) {
            for (String key : cacheKeys) {
                cache.evictIfPresent(key);
            }
        }
    }

    // -------------------------------------------------------------- internals

    /** Run a read-only supplier inside {@link #txRead}. */
    private <T> T txRead(Supplier<T> work) {
        return txRead.execute(status -> work.get());
    }

    /** Resolve which entity a portfolio upload belongs to from the authenticated actor. */
    private PortfolioTarget resolvePortfolioTarget(UUID actorId, Role actorRole) {
        if (actorRole == Role.SALON_OWNER) {
            // Perf MEDIUM F6: use findTop...OrderByCreatedAtAsc to pick the oldest active salon
            // deterministically. The former findAllByOwnerIdAndIsActiveTrue + salons.get(0)
            // had no ORDER BY, so the choice was non-deterministic when an owner has >1 salon.
            Salon salon = salonRepo.findTopByOwnerIdAndIsActiveTrueOrderByCreatedAtAsc(actorId)
                    .orElseThrow(() -> new ForbiddenException("No active salon found for owner"));
            return new PortfolioTarget(EntityType.SALON, salon.getId(),
                    portfolioKeyPrefix(EntityType.SALON, salon.getId()));
        }
        if (actorRole == Role.INDEPENDENT_MASTER) {
            Master master = masterRepo.findByUserId(actorId)
                    .orElseThrow(() -> new ForbiddenException("No master profile found for user"));
            return new PortfolioTarget(EntityType.MASTER, master.getId(),
                    portfolioKeyPrefix(EntityType.MASTER, master.getId()));
        }
        throw new ForbiddenException("Role not allowed to upload portfolio photos");
    }

    /** Resolved owning entity for a portfolio upload — value object, no identity. */
    private record PortfolioTarget(EntityType entityType, UUID entityId, String prefix) {}

    /**
     * The upload stream, opened exactly once, positioned at byte 0 after the magic-byte sniff,
     * together with the detected MIME. The caller passes {@link #stream()} straight to R2 and
     * closes this holder (which closes the stream).
     */
    private record SniffedUpload(String mime, InputStream stream) implements AutoCloseable {
        @Override
        public void close() {
            try {
                stream.close();
            } catch (IOException ex) {
                log.warn("Failed to close upload stream: {}", ex.getClass().getSimpleName());
            }
        }
    }

    /**
     * Opens the upload stream once, sniffs the magic bytes (mark/reset on a
     * {@link BufferedInputStream}) and returns the stream rewound to byte 0 with the detected MIME.
     * The buffer is larger than {@link #HEADER_BYTES}, which is also the mark limit, so
     * {@code reset()} can never fail.
     */
    private SniffedUpload openAndSniff(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "File must not be empty");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "File exceeds 5 MB limit");
        }

        InputStream in = null;
        try {
            in = new BufferedInputStream(file.getInputStream(), SNIFF_BUFFER_BYTES);
            in.mark(HEADER_BYTES);
            byte[] header = new byte[HEADER_BYTES];
            int read = in.readNBytes(header, 0, HEADER_BYTES);
            if (read < 4) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "File too small");
            }
            in.reset();
            return new SniffedUpload(detectMimeType(header), in);
        } catch (IOException ex) {
            closeQuietly(in);
            log.warn("Failed to read upload header: {}", ex.getClass().getSimpleName());
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Failed to read uploaded file");
        } catch (BusinessException ex) {
            closeQuietly(in);
            throw ex;
        }
    }

    private static void closeQuietly(InputStream in) {
        if (in == null) {
            return;
        }
        try {
            in.close();
        } catch (IOException ignored) {
            // best effort — already failing the request
        }
    }

    private String detectMimeType(byte[] header) {
        // JPEG: FF D8 FF
        if ((header[0] & 0xFF) == 0xFF
                && (header[1] & 0xFF) == 0xD8
                && (header[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        // PNG: 89 50 4E 47
        if ((header[0] & 0xFF) == 0x89
                && header[1] == 0x50
                && header[2] == 0x4E
                && header[3] == 0x47) {
            return "image/png";
        }
        // WebP: RIFF....WEBP — 'RIFF' at 0..3 and 'WEBP' at 8..11
        if (header[0] == 0x52
                && header[1] == 0x49
                && header[2] == 0x46
                && header[3] == 0x46
                && header[8] == 0x57
                && header[9] == 0x45
                && header[10] == 0x42
                && header[11] == 0x50) {
            return "image/webp";
        }

        throw new BusinessException(
                HttpStatus.BAD_REQUEST,
                "Unsupported image format — JPEG, PNG, and WebP are accepted"
        );
    }

    /**
     * Build a server-generated R2 key from a stable prefix + millis + UUID + the
     * canonical extension for the detected MIME. Client filenames are never used.
     */
    private String buildKey(String prefix, String detectedMime) {
        String ext = MIME_TO_EXT.get(detectedMime);
        return prefix + Instant.now(clock).toEpochMilli() + "-" + UUID.randomUUID() + "." + ext;
    }
}
