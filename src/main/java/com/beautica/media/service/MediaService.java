package com.beautica.media.service;

import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.media.dto.AvatarResponse;
import com.beautica.media.dto.MediaFileResponse;
import com.beautica.media.entity.EntityType;
import com.beautica.media.entity.MediaFile;
import com.beautica.media.entity.MediaType;
import com.beautica.media.repository.MediaRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
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
 * <p><b>Content-type spoofing defense.</b> {@link #detectMimeType(MultipartFile)} reads
 * the first 12 bytes of the file and matches against the magic-byte signatures for
 * JPEG, PNG, and WebP. SVG is intentionally rejected (it is XML and can carry
 * {@code <script>}). The detected MIME — never {@code file.getContentType()} — is
 * what gets passed to {@link R2StorageService#uploadFile} and stored as the extension.
 *
 * <p><b>Stream lifecycle.</b> {@code MultipartFile.getInputStream()} returns a fresh
 * stream on each call. The AWS SDK's {@code RequestBody.fromInputStream} does not
 * close the caller's stream, so every call site in this class uses try-with-resources.
 *
 * <p><b>Transaction scoping (Perf MEDIUM #1 + #2).</b> There is no class-level
 * {@link Transactional} annotation — R2 HTTP calls (which can block up to the 30 s
 * socket timeout) must never run while a HikariCP connection is held. Each public
 * method uses two {@link TransactionTemplate}s: {@code txRead} for short read-only
 * lookups and {@code txWrite} for the persistence step. R2 calls always happen
 * <b>between</b> the two transactions, never inside one.
 *
 * <p><b>SEC-2 (BLOCKER carry-forward from Phase 7.1 audit).</b>
 * {@link #deleteByUploader(UUID)} purges every R2 blob owned by a user before the
 * caller deletes the {@code users} row — the {@code ON DELETE CASCADE} on
 * {@code media_files.uploader_id} would otherwise leave R2 objects orphaned because
 * the DB has no hook into R2. Any future user-deletion flow MUST call this BEFORE
 * deleting the row.
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

    /** Cache name for the public portfolio listing — must match {@code CacheConfig.cacheManager()}. */
    static final String PORTFOLIO_CACHE = "portfolio";

    private final R2StorageService r2;
    private final MediaRepository mediaRepo;
    private final UserRepository userRepo;
    private final SalonRepository salonRepo;
    private final MasterRepository masterRepo;
    private final Clock clock;
    private final TransactionTemplate txRead;
    private final TransactionTemplate txWrite;
    private final CacheManager cacheManager;

    @Autowired
    public MediaService(R2StorageService r2,
                        MediaRepository mediaRepo,
                        UserRepository userRepo,
                        SalonRepository salonRepo,
                        MasterRepository masterRepo,
                        Clock clock,
                        PlatformTransactionManager transactionManager,
                        CacheManager cacheManager) {
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
                 CacheManager cacheManager) {
        this.r2 = r2;
        this.mediaRepo = mediaRepo;
        this.userRepo = userRepo;
        this.salonRepo = salonRepo;
        this.masterRepo = masterRepo;
        this.clock = clock;
        this.txRead = txRead;
        this.txWrite = txWrite;
        this.cacheManager = cacheManager;
    }

    // ------------------------------------------------------------------ avatar

    public AvatarResponse uploadAvatar(UUID userId, MultipartFile file) {
        String detectedMime = detectMimeType(file);

        // Step 1 — short read tx: capture the existing avatar key (if any). The
        // connection is released before any R2 call is made.
        String oldKey = txRead(() -> userRepo.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId))
                .getAvatarR2Key());

        // Step 2 — R2 work runs OUTSIDE any transaction (Perf MEDIUM #1). SEC-2: delete
        // the old blob BEFORE uploading the new one so a failure here cannot leave two
        // blobs in R2.
        if (oldKey != null) {
            r2.deleteFile(oldKey);
        }

        String newKey = buildKey("avatars/" + userId + "/", detectedMime);
        try (InputStream in = file.getInputStream()) {
            r2.uploadFile(newKey, in, file.getSize(), detectedMime);
        } catch (IOException ex) {
            log.error("Failed to read avatar upload stream for user={}: {}", userId, ex.getClass().getSimpleName());
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Failed to read uploaded file");
        }
        String newUrl = r2.buildPublicUrl(newKey);

        // Step 3 — short write tx: persist the new key/url. Re-load the user inside the
        // tx so we never carry a detached entity across a network round-trip.
        txWrite.execute(status -> {
            User u = userRepo.findById(userId)
                    .orElseThrow(() -> new NotFoundException("User not found: " + userId));
            u.setAvatarR2Key(newKey);
            u.setAvatarUrl(newUrl);
            userRepo.save(u);
            return null;
        });

        return new AvatarResponse(newUrl);
    }

    public void deleteAvatar(UUID userId) {
        // Step 1 — read tx: capture the current key. Releases the connection before R2.
        String key = txRead(() -> userRepo.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId))
                .getAvatarR2Key());

        if (key == null) {
            return;
        }

        // Step 2 — R2 delete OUTSIDE any transaction. SEC-2 ordering: R2 first, DB second.
        r2.deleteFile(key);

        // Step 3 — write tx: clear the fields on a fresh load.
        txWrite.execute(status -> {
            User u = userRepo.findById(userId)
                    .orElseThrow(() -> new NotFoundException("User not found: " + userId));
            u.setAvatarR2Key(null);
            u.setAvatarUrl(null);
            userRepo.save(u);
            return null;
        });
    }

    // --------------------------------------------------------------- portfolio

    public MediaFileResponse uploadPortfolioPhoto(UUID actorId, Role actorRole, MultipartFile file) {
        String detectedMime = detectMimeType(file);

        // Step 1 — read tx: resolve the owning entity from the authenticated principal.
        // SEC-1: never trust a UUID from the request body — the salon/master is resolved
        // server-side from the actor's role.
        PortfolioTarget target = txRead(() -> resolvePortfolioTarget(actorId, actorRole));

        // Step 2 — R2 upload OUTSIDE any transaction.
        String key = buildKey(target.prefix(), detectedMime);
        try (InputStream in = file.getInputStream()) {
            r2.uploadFile(key, in, file.getSize(), detectedMime);
        } catch (IOException ex) {
            log.error("Failed to read portfolio upload stream for user={}: {}", actorId, ex.getClass().getSimpleName());
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Failed to read uploaded file");
        }
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

    public void deletePortfolioPhoto(UUID actorId, UUID mediaId) {
        // Step 1 — read tx: load the row, enforce ownership, capture the R2 key and the
        // owning (entityType, entityId) pair needed for post-commit cache eviction.
        DeleteTarget target = txRead(() -> {
            MediaFile mf = mediaRepo.findById(mediaId)
                    .orElseThrow(() -> new NotFoundException("Media not found: " + mediaId));
            if (!mf.getUploader().getId().equals(actorId)) {
                throw new ForbiddenException("Not allowed to delete this media");
            }
            return new DeleteTarget(mf.getR2Key(), mf.getEntityType(), mf.getEntityId());
        });

        // Step 2 — R2 delete OUTSIDE any transaction. SEC-2 ordering: R2 first; an
        // orphaned DB row can be cleaned up by a re-run, but a leaked R2 blob cannot.
        r2.deleteFile(target.r2Key());

        // Step 3 — write tx: delete the row by id (avoids carrying the detached entity).
        txWrite.execute(status -> {
            mediaRepo.deleteById(mediaId);
            return null;
        });

        // Step 4 — Phase 7.7: post-commit cache eviction. txWrite.execute() returns only
        // after commit, so this runs strictly after the row has been removed.
        evictPortfolioCache(target.entityType(), target.entityId());
    }

    /** Resolved data for a portfolio delete — captured during the read tx. */
    private record DeleteTarget(String r2Key, EntityType entityType, UUID entityId) {}

    /**
     * Public unauthenticated read path. Annotated with {@link Cacheable} on the
     * {@code portfolio} cache. With no explicit {@code key}, Spring's
     * {@code SimpleKeyGenerator} builds a {@link SimpleKey} from the method's
     * two arguments — {@code SimpleKey(entityType, entityId)} — which the
     * programmatic eviction sites construct identically. (A SpEL
     * {@code key = "{#entityType, #entityId}"} would produce a {@code List}
     * instead, breaking the eviction match.)
     */
    @Cacheable(PORTFOLIO_CACHE)
    @Transactional(readOnly = true)
    public List<MediaFileResponse> getPortfolio(EntityType entityType, UUID entityId) {
        // No need to touch getUploader() — MediaFileResponse.from does not access it,
        // so the LAZY association stays untouched and the listing avoids N+1.
        return mediaRepo.findByEntityTypeAndEntityId(entityType, entityId).stream()
                .map(MediaFileResponse::from)
                .toList();
    }

    /**
     * Narrow eviction of a single {@code portfolio} cache entry. Called from the
     * write paths AFTER {@link TransactionTemplate#execute} has returned (and the
     * transaction has therefore committed), so a parallel reader cannot repopulate
     * stale data between the commit and the eviction. The {@link SimpleKey}
     * mirrors Spring's materialisation of {@code @Cacheable(key = "{...}")}.
     */
    private void evictPortfolioCache(EntityType entityType, UUID entityId) {
        Cache cache = cacheManager.getCache(PORTFOLIO_CACHE);
        if (cache != null) {
            cache.evictIfPresent(new SimpleKey(entityType, entityId));
        }
    }

    // ---------------------------------------------------------- SEC-2 sweeper

    /**
     * Purge every R2 blob uploaded by the given user, then delete the corresponding
     * {@code media_files} rows. Must be called BEFORE a {@code users} row is deleted —
     * otherwise the {@code ON DELETE CASCADE} on {@code media_files.uploader_id}
     * fires first and leaves R2 objects orphaned with no DB pointer to recover them.
     *
     * <p>R2 deletion is best-effort per row: a failure on one row is logged at WARN
     * and the loop continues, so a transient R2 outage does not abort the whole sweep.
     * After every R2 attempt, all rows are deleted from the DB in a single batch.
     *
     * <p><b>Wiring contract (out of scope for Phase 7.5):</b> a future user-deletion
     * flow must call this method before deleting the {@code users} row.
     */
    public void deleteByUploader(UUID uploaderId) {
        // Step 1 — read tx: collect the rows. Connection released before any R2 call.
        List<MediaFile> rows = txRead(() -> mediaRepo.findByUploaderId(uploaderId));
        if (rows.isEmpty()) {
            return;
        }

        // Step 2 — R2 deletes OUTSIDE any transaction. A single row's failure must not
        // abort the sweep — log and continue.
        for (MediaFile row : rows) {
            try {
                r2.deleteFile(row.getR2Key());
            } catch (RuntimeException ex) {
                // Key encodes the user UUID — omit from WARN log to avoid PII in log aggregators.
                log.warn("R2 delete failed during deleteByUploader sweep (uploader={}, key=[key omitted]): {}",
                        uploaderId, ex.getClass().getSimpleName());
            }
        }

        // Step 3 — write tx: batch delete every row.
        txWrite.execute(status -> {
            mediaRepo.deleteAll(rows);
            return null;
        });

        // Step 4 — Phase 7.8/7.9: evict every distinct (entityType, entityId) portfolio
        // cache entry AFTER the write tx commits. Without this, a future user-deletion
        // flow would leave deleted portfolio entries readable from the cache for up to
        // the 5-min TTL.
        Set<SimpleKey> distinctKeys = new HashSet<>();
        for (MediaFile row : rows) {
            distinctKeys.add(new SimpleKey(row.getEntityType(), row.getEntityId()));
        }
        Cache cache = cacheManager.getCache(PORTFOLIO_CACHE);
        if (cache != null) {
            for (SimpleKey key : distinctKeys) {
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
                    "portfolio/salons/" + salon.getId() + "/");
        }
        if (actorRole == Role.INDEPENDENT_MASTER) {
            Master master = masterRepo.findByUserId(actorId)
                    .orElseThrow(() -> new ForbiddenException("No master profile found for user"));
            return new PortfolioTarget(EntityType.MASTER, master.getId(),
                    "portfolio/independent/" + master.getId() + "/");
        }
        throw new ForbiddenException("Role not allowed to upload portfolio photos");
    }

    /** Resolved owning entity for a portfolio upload — value object, no identity. */
    private record PortfolioTarget(EntityType entityType, UUID entityId, String prefix) {}

    /**
     * Inspect the first {@value #HEADER_BYTES} bytes of the upload and resolve the
     * canonical MIME type. Validates size and emptiness up-front. Never trusts
     * {@code file.getContentType()} or {@code file.getOriginalFilename()}.
     *
     * @throws BusinessException with HTTP 400 for every rejection path
     */
    private String detectMimeType(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "File must not be empty");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "File exceeds 5 MB limit");
        }

        byte[] header = new byte[HEADER_BYTES];
        try (InputStream in = file.getInputStream()) {
            int read = in.read(header, 0, HEADER_BYTES);
            if (read < 4) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, "File too small");
            }
        } catch (IOException ex) {
            log.warn("Failed to read upload header: {}", ex.getClass().getSimpleName());
            throw new BusinessException(HttpStatus.BAD_REQUEST, "Failed to read uploaded file");
        }

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
