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

    // ---------------------------------------------------------- SEC-2 sweeper

    /**
     * Purge every R2 blob owned by the given user, then drop the DB pointers to them.
     * Must be called BEFORE a {@code users} row is deleted — otherwise the
     * {@code ON DELETE CASCADE} on {@code media_files.uploader_id} fires first and leaves
     * R2 objects orphaned with no DB pointer to recover them.
     *
     * <p><b>Covers BOTH blob families a user owns</b> (Anti-Bug Playbook §O8):
     * <ul>
     *   <li>every {@code media_files} row uploaded by them (portfolio photos), and</li>
     *   <li>their avatar — which lives in {@code users.avatar_r2_key}, NOT in
     *       {@code media_files}: {@link #uploadAvatar} writes the key straight onto the
     *       {@code users} row and never inserts a {@code MediaFile}. A media-rows-only
     *       sweep therefore left the avatar blob publicly retrievable forever after
     *       account deletion, at a URL every provider the client ever booked with holds
     *       (it is emitted as {@code clientAvatarUrl} on {@code BookingDetailResponse}).</li>
     * </ul>
     *
     * <p>R2 deletion is best-effort throughout — mirroring the pre-existing per-row policy
     * rather than introducing a second one: a failure is logged at WARN and the sweep
     * continues, so a transient R2 outage cannot abort it and strand the remaining blobs.
     * The DB pointer is dropped either way, and that carries an honest downside: when the R2
     * delete FAILS, this method still nulls the pointer, leaving the blob in R2 with no DB
     * key left to reconcile against — an ORPHANED blob. That is the accepted cost of a
     * teardown that always completes and is safe to re-run; the alternative (keep the pointer
     * on R2 failure) is worse, because it re-exposes a deleted user's photo at a live,
     * retrievable URL. Ordering is always R2-first-then-DB, so aside from that accepted orphan
     * the only other outcome is a crash landing between the two steps, which leaves a pointer
     * to an already-gone blob (harmless) — never a live pointer to a deleted blob. Durable
     * orphan reconciliation (a delete log or a periodic bucket-vs-DB sweep) is intentionally
     * out of scope until an account-deletion flow actually calls this method.
     *
     * <p>The avatar sweep runs FIRST, before the {@code rows.isEmpty()} short-circuit below —
     * a user with an avatar but no portfolio photos is the common case for a {@code CLIENT}
     * and must not fall through the early return.
     *
     * <p><b>Wiring contract (out of scope for Phase 7.5):</b> a future user-deletion
     * flow must call this method before deleting the {@code users} row.
     */
    public void deleteByUploader(UUID uploaderId) {
        // Step 0 — avatar blob (users.avatar_r2_key), which is NOT a media_files row.
        sweepAvatar(uploaderId);

        // Step 1 — read tx: collect the rows. Connection released before any R2 call.
        List<MediaFile> rows = txRead(() -> mediaRepo.findByUploaderId(uploaderId));

        // Steps 2-4 (R2 deletes, batch DB delete, post-commit cache eviction) are the
        // shared sweep body — see sweepBlobs. No extra (non-media_files) keys on this path,
        // and no single explicit entity to evict beyond whatever the rows themselves carry
        // (a user may have contributed to more than one entity historically).
        sweepBlobs(rows, List.of(), null, null);
    }

    /**
     * Shared R2-plus-DB sweep body, promoted (REUSE-FIRST, Phase 268 D3) from what used to be
     * {@link #deleteByUploader}'s steps 1-4 alone. Both {@link #deleteByUploader} and
     * {@link #deleteBySalon} call this — no second implementation of the ordering/failure/eviction
     * policy documented on {@link #deleteByUploader}'s own Javadoc, which still governs this method
     * verbatim (R2-first-then-DB, per-row best-effort, orphans accepted on R2 failure, keys never
     * logged).
     *
     * <p><b>Order:</b> {@code extraKeys} (blobs that live OUTSIDE {@code media_files} — a user's
     * avatar-adjacent columns are handled by {@link #sweepAvatar} instead, but a salon's
     * {@code avatar_url}/{@code cover_image_url} are exactly this shape) are deleted from R2
     * FIRST, then every {@code rows} entry, THEN one batched DB delete of {@code rows}, THEN
     * post-commit portfolio-cache eviction.
     *
     * <p><b>Deliberately does NOT short-circuit on {@code rows.isEmpty()}</b> the way the
     * pre-promotion code did — a salon with an avatar/cover but zero portfolio photos (or the
     * reverse) must still have its {@code extraKeys} swept. The only true no-op case is BOTH lists
     * empty.
     *
     * @param rows      {@code media_files} rows to purge from R2 and the DB, collected under a read
     *                  transaction by the caller (connection already released before this runs)
     * @param extraKeys R2 keys for blobs that are not {@code media_files} rows (e.g. a salon's
     *                  avatar/cover) — deleted from R2 but never touch {@code mediaRepo}
     * @param evictType together with {@code evictId}, an explicit portfolio-cache entry to evict in
     *                  addition to whatever {@code rows} themselves resolve to — needed so a
     *                  rows-empty, extraKeys-only sweep still evicts the right entity. Either may be
     *                  {@code null} when the caller has no single entity to name beyond {@code rows}
     *                  (e.g. {@link #deleteByUploader}, whose rows may span more than one entity).
     */
    private void sweepBlobs(List<MediaFile> rows, List<String> extraKeys, EntityType evictType, UUID evictId) {
        if (rows.isEmpty() && extraKeys.isEmpty()) {
            return;
        }

        // R2 deletes OUTSIDE any transaction, batched into as few DeleteObjects round-trips as
        // R2StorageService#deleteFiles allows (Phase 268 perf follow-up — was one HTTP round-trip
        // per key). Extra keys first, then rows, matching the pre-batching order.
        List<String> allKeys = new ArrayList<>(extraKeys.size() + rows.size());
        allKeys.addAll(extraKeys);
        for (MediaFile row : rows) {
            allKeys.add(row.getR2Key());
        }

        // r2.deleteFiles never throws for a delete failure, partial or total (see its Javadoc) —
        // it returns the subset of keys it could not delete. A single object's failure must not
        // abort the sweep, so that returned set is only logged, never re-thrown; the DB rows are
        // still dropped below regardless of which keys failed — same accepted-orphan policy the
        // per-key loop this replaced already had.
        Set<String> failedKeys = r2.deleteFiles(allKeys);
        for (String ignored : failedKeys) {
            // Key may encode an entity UUID — omit from WARN log to avoid PII in log aggregators.
            log.warn("R2 delete failed during media sweep (key=[key omitted])");
        }

        // Write tx: batch delete every media_files row. Skipped when there are none — extraKeys
        // alone never touch mediaRepo.
        if (!rows.isEmpty()) {
            txWrite.execute(status -> {
                mediaRepo.deleteAll(rows);
                return null;
            });
        }

        // Post-commit portfolio-cache eviction — every distinct (entityType, entityId) the rows
        // themselves carry, PLUS the caller's explicit (evictType, evictId) when given (covers the
        // rows-empty, extraKeys-only case, where nothing in `rows` would otherwise name the entity).
        Set<String> distinctKeys = new HashSet<>();
        for (MediaFile row : rows) {
            distinctKeys.add(portfolioCacheKey(row.getEntityType(), row.getEntityId()));
        }
        if (evictType != null && evictId != null) {
            distinctKeys.add(portfolioCacheKey(evictType, evictId));
        }
        if (!distinctKeys.isEmpty()) {
            Cache cache = cacheManager.getCache(PORTFOLIO_CACHE);
            if (cache != null) {
                for (String key : distinctKeys) {
                    cache.evictIfPresent(key);
                }
            }
        }
    }

    /**
     * Purges a salon's imagery from R2 permanently — the Phase 268 D2/D3 sweep called by
     * {@code SalonService} AFTER its deletion transaction commits (D4/D8: never inline inside that
     * transaction, since {@link #txRead}/{@link #txWrite} would otherwise join it and hold a
     * connection across dozens of sequential R2 round-trips).
     *
     * <p>Covers all three imagery sets a deleted salon can carry (phase doc D2 table):
     * <ul>
     *   <li>every {@code media_files} row with {@code entity_type = SALON, entity_id = salonId}
     *       (the portfolio) — {@code preReadRows}, already collected by the caller INSIDE the
     *       deletion transaction, before {@code deleteSalonStaff} could hard-delete a staff
     *       uploader and cascade the row away (the gap the phase doc did not cover — see
     *       {@code SalonService.deactivateSalon}'s Javadoc);</li>
     *   <li>{@code salons.avatar_url} — recovered to a key via {@link
     *       R2StorageService#extractKeyFromPublicUrl}, since (unlike a user) a salon stores only
     *       the public URL, never the raw key;</li>
     *   <li>{@code salons.cover_image_url} — same recovery.</li>
     * </ul>
     *
     * <p>A {@code null} or foreign-prefixed URL yields no key (D2 safety guard) and is silently
     * skipped — never guessed at. The DB pointer columns themselves are nulled by the caller
     * ({@code SalonRepository#nullImageUrls}) AFTER this method returns, preserving the D4
     * R2-first-then-DB ordering at the SalonService level exactly as this method preserves it
     * internally for {@code media_files} rows.
     *
     * @param salonId       the deleted salon's id, used only to name the explicit cache-eviction
     *                      entry (D3) — every row in {@code preReadRows} is already scoped to it
     * @param avatarUrl     the salon's {@code avatar_url} at the moment it was deactivated, or
     *                      {@code null}
     * @param coverImageUrl the salon's {@code cover_image_url} at the moment it was deactivated, or
     *                      {@code null}
     * @param preReadRows   the salon's {@code media_files} rows, read by the caller before any
     *                      staff hard-delete could cascade one away
     */
    public void deleteBySalon(UUID salonId, String avatarUrl, String coverImageUrl, List<MediaFile> preReadRows) {
        List<String> extraKeys = new ArrayList<>(2);
        r2.extractKeyFromPublicUrl(avatarUrl).ifPresent(extraKeys::add);
        r2.extractKeyFromPublicUrl(coverImageUrl).ifPresent(extraKeys::add);

        sweepBlobs(preReadRows, extraKeys, EntityType.SALON, salonId);
    }

    /**
     * Permanently purges a self-deleted CLIENT's R2 blobs — the Phase 300 D4/§9 counterpart of
     * {@link #deleteBySalon}, called by {@code ClientAccountDeletionService} AFTER its deletion
     * transaction commits (mirroring {@code SalonService}'s {@code purgeSalonMediaAfterCommit}
     * registration shape exactly).
     *
     * <p><b>R2-ONLY — deliberately does NOT reuse {@link #sweepBlobs}.</b> {@code sweepBlobs} also
     * issues a DB {@code mediaRepo.deleteAll(rows)}, which is exactly right for {@link
     * #deleteBySalon} (a salon's OWN portfolio rows do not automatically cascade away — only a
     * hard-deleted STAFF uploader's rows do) but is WRONG here: {@code media_files.uploader_id}
     * {@code ON DELETE CASCADE}s directly off {@code users.id} (V37:8), so by the time this
     * {@code afterCommit} callback runs, {@code preReadRows} and the caller's own avatar-column
     * update have ALREADY vanished from the database — {@code preReadRows} are DETACHED entities
     * pointing at rows that no longer exist. Calling {@code mediaRepo.deleteAll} on them would
     * either no-op or throw ({@code merge()} tries to reload a row that is gone), for zero benefit:
     * the only thing actually left to clean up is the R2 BLOBS themselves, which are outside the
     * transaction and were never touched by the CASCADE.
     *
     * <p><b>Do NOT call {@link #deleteByUploader} inline instead</b> — see that method's own
     * Javadoc and this class's caller's Javadoc: it opens its OWN {@code PROPAGATION_REQUIRES_NEW}
     * transactions, so calling it from inside {@code ClientAccountDeletionService}'s own {@code
     * @Transactional} would let an outer rollback leave the blobs already destroyed.
     *
     * <p>Best-effort throughout, same policy as every other sweep in this class: a partial or
     * total R2 failure is logged at WARN (key omitted) and never re-thrown — the {@code users} row
     * is already gone by the time this runs, so there is nothing left to roll back to.
     *
     * @param clientUserId  the deleted client's id, used only to name the portfolio-cache eviction
     *                      entry for each distinct {@code (entityType, entityId)} in {@code
     *                      preReadRows} — a CLIENT never legitimately owns a portfolio entry today
     *                      (only SALON_OWNER/INDEPENDENT_MASTER/SALON_ADMIN upload one), but this
     *                      stays correct if that ever changes
     * @param avatarR2Key   the deleted client's {@code users.avatar_r2_key} at the moment of
     *                      deletion, pre-read by the caller before the row vanished, or {@code
     *                      null} if the client never had an avatar
     * @param preReadRows   the client's {@code media_files} rows, pre-read by the caller (via
     *                      {@code mediaRepo.findByUploaderId}) before the {@code users} DELETE
     *                      cascaded them away
     */
    public void purgeUserBlobsAfterCommit(UUID clientUserId, String avatarR2Key, List<MediaFile> preReadRows) {
        List<String> keys = new ArrayList<>(preReadRows.size() + 1);
        if (avatarR2Key != null) {
            keys.add(avatarR2Key);
        }
        for (MediaFile row : preReadRows) {
            keys.add(row.getR2Key());
        }
        if (keys.isEmpty()) {
            return;
        }

        Set<String> failedKeys = r2.deleteFiles(keys);
        for (String ignored : failedKeys) {
            // Key may encode an entity UUID — omit from WARN log to avoid PII in log aggregators.
            log.warn("R2 delete failed during client self-delete blob purge (client={}, key=[key omitted])",
                    clientUserId);
        }

        Set<String> distinctCacheKeys = new HashSet<>();
        for (MediaFile row : preReadRows) {
            distinctCacheKeys.add(portfolioCacheKey(row.getEntityType(), row.getEntityId()));
        }
        if (!distinctCacheKeys.isEmpty()) {
            Cache cache = cacheManager.getCache(PORTFOLIO_CACHE);
            if (cache != null) {
                for (String key : distinctCacheKeys) {
                    cache.evictIfPresent(key);
                }
            }
        }
    }

    /**
     * Avatar half of {@link #deleteByUploader}: purge the {@code users.avatar_r2_key} blob
     * and null both avatar columns.
     *
     * <p>Deliberately tolerant where {@link #deleteAvatar} is strict. {@code deleteAvatar}
     * serves an authenticated request and 404s on a missing user; this runs inside a
     * teardown sweep that must be safe to re-run and safe to call when the {@code users} row
     * has already gone (e.g. a retried deletion), so an absent user is a no-op. Same
     * tolerance the media-rows sweep already applies via its {@code rows.isEmpty()} return.
     */
    private void sweepAvatar(UUID uploaderId) {
        // Read tx: capture the key, release the connection before the R2 round-trip.
        String key = txRead(() -> userRepo.findById(uploaderId)
                .map(User::getAvatarR2Key)
                .orElse(null));
        if (key == null) {
            return;
        }

        // R2 delete OUTSIDE any transaction, best-effort — same policy as the row loop.
        try {
            r2.deleteFile(key);
        } catch (RuntimeException ex) {
            // Key embeds the user UUID — omit it from the log to keep PII out of aggregators.
            log.warn("R2 delete failed during deleteByUploader avatar sweep (uploader={}, key=[key omitted]): {}",
                    uploaderId, ex.getClass().getSimpleName());
        }

        // Write tx: clear the pointers on a fresh load, so no detached entity crosses the
        // network round-trip above.
        txWrite.execute(status -> {
            userRepo.findById(uploaderId).ifPresent(u -> {
                u.setAvatarR2Key(null);
                u.setAvatarUrl(null);
                userRepo.save(u);
            });
            return null;
        });
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
