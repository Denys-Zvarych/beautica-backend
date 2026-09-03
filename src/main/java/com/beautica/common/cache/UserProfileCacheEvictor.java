package com.beautica.common.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.UUID;

/**
 * The single home for "evict this user's {@code GET /api/v1/users/me} entry after the current
 * transaction commits".
 *
 * <p><b>Why this exists as a shared component rather than four inline blocks.</b> The
 * {@code user-profile} cache is keyed by {@code userId} and backs {@code UserService#getProfile},
 * but the DTO it stores is <em>not</em> derived from the {@code users} row alone:
 * {@code UserProfileResponse.hasMasterProfile} is resolved from an entirely different aggregate —
 * an active {@code masters} row of type {@code SALON_OWNER}. That makes every writer of the
 * {@code masters} table a writer of this cache, even though none of them is in
 * {@code com.beautica.user}. A cache name duplicated as a string literal across four feature
 * packages is exactly the shape that rots: the fifth writer never learns the cache exists. Owning
 * the name and the post-commit mechanic here means {@code grep UserProfileCacheEvictor} enumerates
 * the complete writer set.
 *
 * <p><b>Complete writer set</b> (audit-fix cycle 2, verified by grep over {@code src/main} for every
 * mutator of a field carried by {@code UserProfileResponse}):
 * <ul>
 *   <li>{@code UserService#updateProfile} / {@code #updateMasterProfile} — via
 *       {@code evictUserCachesAfterCommit}, which also covers {@code applyLocality},
 *       {@code writeLocalityFields} and {@code writeCityDisplayStrings} (all run inside those two
 *       transactions, none is a separate entry point).</li>
 *   <li>{@code MasterService} — every path that creates, reactivates or deactivates a
 *       {@code masters} row, because each flips {@code hasMasterProfile}:
 *       {@code createMasterForIndependentUser}, {@code createMasterFromInvite},
 *       {@code createMasterForOwner} (both the create and the reactivate branch),
 *       {@code deactivateOwnerMaster}, {@code deactivateMaster}.</li>
 *   <li>{@code SalonService#createSalon} — syncs the owner's locality columns onto their
 *       {@code users} row, and on first-salon registration auto-creates the owner-master row.</li>
 *   <li>{@code SalonService#removeAdmin} / {@code #rotateAdmin} — mutate {@code users.salon_id},
 *       which {@code UserProfileResponse.salonId} surfaces.</li>
 *   <li>{@code EmailVerificationProcessor#verifyAndReturnUserId} — flips
 *       {@code users.email_verified}, which {@code UserProfileResponse.emailVerified} surfaces.</li>
 *   <li>{@code SalonService#deactivateSalonStaff} (Phase 290, private helper of
 *       {@code deactivateSalon}) — flips {@code isActive} FALSE for a deleted salon's
 *       {@code SALON_MASTER}/{@code SALON_ADMIN} accounts, which {@code UserProfileResponse
 *       .isActive} surfaces. This is the FIRST writer of that field on this DTO — no prior code
 *       path ever set {@code User.isActive = false} (see {@code UserRepository
 *       .findBySalonIdAndRoleAndIsActiveTrue}'s javadoc for the sibling gap this same phase
 *       closed).</li>
 * </ul>
 *
 * <p><b>Deliberately NOT writers</b>, each verified against the field list on
 * {@code UserProfileResponse} rather than assumed:
 * <ul>
 *   <li>Avatar / media writes ({@code User#setAvatarUrl}, {@code #setAvatarR2Key}) — neither field
 *       appears on {@code UserProfileResponse}; the avatar is served by the media endpoints.</li>
 *   <li>Password reset and session invalidation ({@code User#setTokensValidAfter},
 *       {@code #setPasswordHash}, every {@code passwordReset*} / {@code verificationCode*}
 *       column) — none is on the DTO, and several are secrets that must never be.</li>
 *   <li>{@code ClientReviewRepository#recalculateClientRating} — writes
 *       {@code users.avg_rating}/{@code review_count}, which belong to
 *       {@code UserRatingResponse} ({@code GET /users/me/rating}), an uncached endpoint.</li>
 *   <li>{@code UserRepository#nullifyStaleVerificationCodes} — touches only OTP columns.</li>
 *   <li>User <em>creation</em> ({@code AuthService}, {@code InviteService}) — the row's UUID is
 *       minted in the same transaction, so no entry can exist under that key.</li>
 * </ul>
 *
 * <p><b>Ordering.</b> Eviction is registered as an {@code afterCommit} callback, never inline and
 * never via {@code @CacheEvict}: an inline evict fires BEFORE the DB sees the write, letting a
 * parallel reader repopulate the entry with the pre-write row for the full TTL (Anti-Bug §F-2).
 * Outside an active transaction the registration is skipped and the evict runs immediately, which
 * is correct for callers whose write already committed in a nested bean's transaction.
 */
@Component
public class UserProfileCacheEvictor {

    /**
     * Cache backing {@code GET /api/v1/users/me}. Declared here rather than on {@code UserService}
     * so the cross-aggregate writers in {@code master} and {@code salon} do not have to import a
     * {@code user}-package service just to name it.
     */
    public static final String USER_PROFILE_CACHE = "user-profile";

    private final CacheManager cacheManager;

    public UserProfileCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    /**
     * Evicts {@code userId}'s {@code user-profile} entry once the current transaction commits.
     * Per-key, never {@code clear()} (Anti-Bug §F-6).
     *
     * @param userId the account owner's UUID — the {@code @Cacheable(key = "#userId")} key shape
     *               used by {@code UserService#getProfile}; {@code null} is a no-op
     */
    public void evictAfterCommit(UUID userId) {
        if (userId == null) {
            return;
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            // No transaction to hang the callback on. The only callers that reach this branch are
            // ones whose write has already been committed by an inner bean's @Transactional proxy,
            // so evicting now is still strictly post-commit.
            evict(userId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                evict(userId);
            }
        });
    }

    private void evict(UUID userId) {
        Cache cache = cacheManager.getCache(USER_PROFILE_CACHE);
        if (cache != null) {
            cache.evict(userId);
        }
    }
}
