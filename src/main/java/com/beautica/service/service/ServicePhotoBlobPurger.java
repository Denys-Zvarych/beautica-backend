package com.beautica.service.service;

import com.beautica.media.service.R2StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Single home for "delete these service-photo R2 blobs after the current transaction commits"
 * (Phase 342 D6, Anti-Bug Playbook §O8). Service definitions are soft-deleted, so nothing cascades their
 * {@code photo_r2_key}; every deactivation path pre-reads the keys, nulls the DB pointers inside its own
 * transaction and hands the keys here.
 *
 * <p>Best-effort by design: {@link R2StorageService#deleteFiles} never throws for a delete failure, and
 * any other runtime failure is swallowed with a WARN (key omitted — it embeds an entity UUID) because the
 * owning deletion has already committed. A failed delete leaves an accepted orphan, same policy as every
 * other sweep. Runs outside any transaction, so no connection is held across R2 round-trips.
 *
 * <p>Deliberately depends on {@link R2StorageService} only (not {@code MediaService}) so
 * {@code ServiceCatalogService} can use it while {@code MediaService} depends on
 * {@code ServiceCatalogService} — no bean cycle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServicePhotoBlobPurger {

    private static final String KEY_PREFIX = "services/";

    private final R2StorageService r2;

    /**
     * A blob to purge together with the id of the definition that owned it. The id is the key-prefix
     * authority: a service-photo key is always {@code services/<definitionId>/...} (server-generated), so a
     * key outside that prefix is never deleted — a corrupted pointer must not turn the purger into an
     * arbitrary-object delete.
     */
    public record ServicePhotoBlob(UUID serviceDefId, String key) {}

    /** Registers the sweep after commit; runs immediately when no transaction is active. No-op for no blobs. */
    public void purgeAfterCommit(List<ServicePhotoBlob> blobs) {
        if (blobs == null || blobs.isEmpty()) {
            return;
        }
        List<String> keys = verifiedKeys(blobs);
        if (keys.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    purge(keys);
                }
            });
        } else {
            purge(keys);
        }
    }

    /** Convenience for the single-blob paths (replace / delete / deactivate). */
    public void purgeAfterCommit(UUID serviceDefId, String key) {
        if (key == null) {
            return;
        }
        purgeAfterCommit(List.of(new ServicePhotoBlob(serviceDefId, key)));
    }

    /** Keeps only keys under {@code services/<id>/}; mismatches are skipped with a WARN that omits the key. */
    private static List<String> verifiedKeys(List<ServicePhotoBlob> blobs) {
        List<String> keys = new ArrayList<>(blobs.size());
        for (ServicePhotoBlob blob : blobs) {
            if (blob.serviceDefId() != null && blob.key() != null
                    && blob.key().startsWith(KEY_PREFIX + blob.serviceDefId() + "/")) {
                keys.add(blob.key());
            } else {
                log.warn("Service photo purge skipped: key outside the owning definition's prefix "
                        + "(definition={}, key=[key omitted])", blob.serviceDefId());
            }
        }
        return keys;
    }

    private void purge(List<String> keys) {
        try {
            Set<String> failed = r2.deleteFiles(keys);
            for (String ignored : failed) {
                log.warn("R2 delete failed during service photo purge (key=[key omitted])");
            }
        } catch (RuntimeException ex) {
            log.warn("Service photo purge failed after commit: {}", ex.getClass().getSimpleName());
        }
    }
}
