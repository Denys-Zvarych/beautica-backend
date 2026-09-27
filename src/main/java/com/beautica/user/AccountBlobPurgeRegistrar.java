package com.beautica.user;

import com.beautica.media.entity.MediaFile;
import com.beautica.media.service.MediaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

/**
 * The single home for "sweep this deleted account's R2 blobs after the current transaction
 * commits" (Phase 301 — promoted out of {@code ClientAccountDeletionService
 * #registerBlobPurgeAfterCommit}, previously {@code private}, so the new staff/independent-master
 * self-delete flow can share the identical after-commit registration shape without re-typing the
 * {@link TransactionSynchronization} block — REUSE-FIRST: a private helper is promoted, never
 * copied, exactly as {@code SalonService}'s {@code evictTokensValidAfterCacheAfterCommit} was
 * promoted to {@code TokensValidAfterCache#invalidateAfterCommit} in Phase 300).
 *
 * <p>Never called inline: {@link MediaService#purgeUserBlobsAfterCommit} opens its own {@code
 * PROPAGATION_REQUIRES_NEW} transactions, so an outer rollback would leave blobs already
 * destroyed if this ran mid-transaction instead of strictly after commit.
 *
 * <p><b>Complete caller set</b> (grep {@code AccountBlobPurgeRegistrar} to keep this current):
 * {@code ClientAccountDeletionService#deleteOwnAccount} (Phase 300), {@code
 * StaffAccountSelfDeletionService#deleteOwnAccount} (Phase 301). Deliberately NOT called from
 * {@code StaffAccountDisposalService#dispose} — the two owner-initiated removal paths
 * (`SalonService#removeAdmin`/`#removeMaster`/`#deleteSalonStaff`) leak R2 blobs today (Phase 301
 * R6); fixing that is out of scope for this phase and stays a backlog row, not silently widened
 * here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountBlobPurgeRegistrar {

    private final MediaService mediaService;

    /**
     * Registers an after-commit R2 sweep for {@code userId}'s pre-read blob pointers. A no-op if
     * no transaction synchronization is active (defensive only — every production caller runs
     * inside a {@code @Transactional} method).
     *
     * @param userId       the deleted account's own id, for the warn-log line only if the purge
     *                     fails — never logged alongside any PII
     * @param avatarR2Key  the account's avatar R2 key at the moment of deletion, or {@code null}
     * @param mediaRows    every {@code media_files} row the account uploaded, pre-read BEFORE the
     *                     {@code users} delete cascaded them away
     */
    public void registerAfterCommit(UUID userId, String avatarR2Key, List<MediaFile> mediaRows) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mediaService.purgeUserBlobsAfterCommit(userId, avatarR2Key, mediaRows);
                } catch (RuntimeException ex) {
                    log.warn("Account self-delete blob purge failed after commit for user {}: {}",
                            userId, ex.getClass().getSimpleName());
                }
            }
        });
    }
}
