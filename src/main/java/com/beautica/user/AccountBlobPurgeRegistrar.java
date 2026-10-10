package com.beautica.user;

import com.beautica.media.repository.UploaderMediaKey;
import com.beautica.media.service.AccountBlobPointers;
import com.beautica.media.service.MediaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;

/**
 * The single home for "sweep this deleted account's R2 blobs after the current transaction
 * commits" (Phase 301 — promoted out of {@code ClientAccountDeletionService
 * #registerBlobPurgeAfterCommit}, previously {@code private}, so the new staff/independent-master
 * self-delete flow can share the identical after-commit registration shape without re-typing the
 * {@link TransactionSynchronization} block — REUSE-FIRST: a private helper is promoted, never
 * copied, exactly as {@code SalonService}'s {@code evictTokensValidAfterCacheAfterCommit} was
 * promoted to {@code TokensValidAfterCache#invalidateAfterCommit} in Phase 300).
 *
 * <p>Never purges inline: deleting blobs mid-transaction would leave a rolled-back account pointing at
 * destroyed objects, so {@link MediaService#purgeUserBlobsAfterCommit} runs strictly after commit and hands
 * the R2 work to the bounded {@code blobPurgeExecutor} (the committing thread never waits on R2).
 *
 * <p><b>Complete caller set</b> (grep {@code AccountBlobPurgeRegistrar} to keep this current):
 * {@code ClientAccountDeletionService#deleteOwnAccount} (Phase 300), {@code
 * StaffAccountSelfDeletionService#deleteOwnAccount} (Phase 301), and {@code
 * StaffAccountDisposalService#dispose} for the owner-initiated removal paths ({@code
 * SalonService#removeAdmin}/{@code #removeMaster}/{@code #deleteSalonStaff}). A self-delete reaches
 * {@code dispose} with {@code StaffDisposalReason.SELF_DELETE} and registers its own sweep, so
 * {@code dispose} skips registration for that reason to avoid a double purge.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccountBlobPurgeRegistrar {

    private final MediaService mediaService;

    /**
     * Single-account form for the self-delete flows: wraps the loaded user's avatar pointers + the pre-read
     * scalar media keys (P-L1 — {@link com.beautica.media.repository.MediaRepository#findMediaKeysByUploaderIdIn}
     * projection, never full entities) into an entity-free {@link AccountBlobPointers} snapshot NOW (P-L3 — the
     * closure never retains a {@link User}) and registers it.
     */
    public void registerAfterCommit(User user, List<UploaderMediaKey> media) {
        registerAfterCommit(List.of(
                new AccountBlobPointers(user.getId(), user.getAvatarR2Key(), user.getAvatarUrl(), media)));
    }

    /**
     * Registers ONE after-commit R2 sweep for every account in {@code accounts} (P-M1 — a salon delete of S
     * staff is one batched purge, not S). Avatar pointers are raw and are own-prefix-verified inside
     * {@link MediaService#purgeUserBlobsAfterCommit} (S-L1). A no-op for an empty list. With no active
     * transaction synchronization the purge is SKIPPED with a counts-only WARN (same rule as
     * {@code AfterCommitBlobPurger#purgeAfterCommit}) — defensive only, every production caller is
     * {@code @Transactional}.
     */
    public void registerAfterCommit(List<AccountBlobPointers> accounts) {
        if (accounts.isEmpty()) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            log.warn("Account blob purge skipped: no active transaction synchronization (accounts={}, "
                    + "mediaKeyCount={}, keys=[omitted])", accounts.size(), mediaKeyCount(accounts));
            return;
        }
        List<AccountBlobPointers> snapshot = List.copyOf(accounts);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mediaService.purgeUserBlobsAfterCommit(snapshot);
                } catch (RuntimeException ex) {
                    log.warn("Account blob purge failed after commit (accounts={}): {}",
                            snapshot.size(), ex.getClass().getSimpleName());
                }
            }
        });
    }

    private static int mediaKeyCount(List<AccountBlobPointers> accounts) {
        return accounts.stream().mapToInt(account -> account.media().size()).sum();
    }
}
