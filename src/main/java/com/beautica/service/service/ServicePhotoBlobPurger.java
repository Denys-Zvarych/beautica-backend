package com.beautica.service.service;

import com.beautica.media.service.AfterCommitBlobPurger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Single home for "delete these service-photo R2 blobs after the current transaction commits"
 * (Phase 342 D6, Anti-Bug Playbook §O8). Service definitions are soft-deleted, so nothing cascades their
 * {@code photo_r2_key}; every deactivation path pre-reads the keys, nulls the DB pointers inside its own
 * transaction and hands the keys here.
 *
 * <p>This class owns only the service-photo OWNERSHIP check ({@code services/<definitionId>/}); the
 * after-commit scheduling, the off-thread R2 delete on {@code blobPurgeExecutor} (perf P-M1: an
 * {@code afterCommit} callback still holds its JDBC connection, so R2 must not run on it), and the
 * best-effort failure policy (WARN with counts only + orphan metrics) are delegated to
 * {@link AfterCommitBlobPurger}. A rolled-back transaction purges nothing.
 *
 * <p>Deliberately depends on {@link AfterCommitBlobPurger} only (not {@code MediaService}) so
 * {@code ServiceCatalogService} can use it while {@code MediaService} depends on
 * {@code ServiceCatalogService} — no bean cycle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServicePhotoBlobPurger {

    private static final String KEY_PREFIX = "services/";
    private static final String PURGE_CONTEXT = "service-photo";

    private final AfterCommitBlobPurger afterCommitBlobPurger;

    /**
     * A blob to purge together with the id of the definition that owned it. The id is the key-prefix
     * authority: a service-photo key is always {@code services/<definitionId>/...} (server-generated), so a
     * key outside that prefix is never deleted — a corrupted pointer must not turn the purger into an
     * arbitrary-object delete.
     */
    public record ServicePhotoBlob(UUID serviceDefId, String key) {}

    /** Registers the sweep after commit. Must run inside a transaction (skipped with a WARN otherwise). No-op for no blobs. */
    public void purgeAfterCommit(List<ServicePhotoBlob> blobs) {
        if (blobs == null || blobs.isEmpty()) {
            return;
        }
        afterCommitBlobPurger.purgeAfterCommit(verifiedKeys(blobs), PURGE_CONTEXT);
    }

    /** Convenience for the single-blob paths (replace / delete / deactivate). */
    public void purgeAfterCommit(UUID serviceDefId, String key) {
        if (key == null) {
            return;
        }
        purgeAfterCommit(List.of(new ServicePhotoBlob(serviceDefId, key)));
    }

    /** Keeps only keys under {@code services/<id>/} with no {@code ..} segment; mismatches are skipped with a WARN that omits the key. */
    private static List<String> verifiedKeys(List<ServicePhotoBlob> blobs) {
        List<String> keys = new ArrayList<>(blobs.size());
        for (ServicePhotoBlob blob : blobs) {
            if (blob.serviceDefId() != null && blob.key() != null
                    && blob.key().startsWith(KEY_PREFIX + blob.serviceDefId() + "/")
                    && !blob.key().contains("..")) {
                keys.add(blob.key());
            } else {
                log.warn("Service photo purge skipped: key outside the owning definition's prefix "
                        + "(definition={}, key=[key omitted])", blob.serviceDefId());
            }
        }
        return keys;
    }
}
