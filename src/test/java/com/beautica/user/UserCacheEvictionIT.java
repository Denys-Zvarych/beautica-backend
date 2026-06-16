package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.dto.MasterProfileUpdateRequest;
import com.beautica.master.service.MasterService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Cache-eviction integration test for {@link UserService#updateMasterProfile}'s
 * {@code evictUserCachesAfterCommit(...)} post-commit synchronization.
 *
 * <h2>Why this test exists (the gap it closes)</h2>
 * {@code evictUserCachesAfterCommit} registers a {@link org.springframework.transaction.support.TransactionSynchronization}
 * whose {@code afterCommit()} hook evicts {@code master-detail-by-user}. Under
 * {@code @ExtendWith(MockitoExtension.class)} unit tests there is NO active
 * transaction, so {@code TransactionSynchronizationManager.isSynchronizationActive()}
 * returns {@code false} and the whole eviction body is skipped — the unit test
 * proves the guard, never the eviction. A refactor that broke the eviction (wrong
 * cache name, eviction moved to {@code beforeCommit}, or the key changed) would pass
 * every unit test green.
 *
 * <p>This IT boots the full context against Testcontainers Postgres with the REAL
 * {@link com.beautica.config.CacheConfig} (Caffeine) and drives the real services
 * inside real transactions via {@link TransactionTemplate}:
 * <ol>
 *   <li><b>Warm</b> — call {@link MasterService#getMyMasterDetail} so the
 *       {@code @Cacheable} value (DTO with the OLD bio) is stored under {@code userId}.</li>
 *   <li><b>Mutate + commit</b> — call {@link UserService#updateMasterProfile} inside a
 *       committed transaction so the {@code afterCommit} synchronization actually fires.</li>
 *   <li><b>Re-read</b> — call {@code getMyMasterDetail} again. If eviction fired, the
 *       cache misses, the DB is re-queried, and the FRESH bio is returned. If eviction
 *       silently failed, the stale cached DTO (OLD bio) would come back.</li>
 * </ol>
 * The FRESH bio on the second read is the proof the after-commit eviction ran.
 */
@DisplayName("UserService.updateMasterProfile — master-detail-by-user cache is evicted after commit (real tx, real CacheConfig)")
class UserCacheEvictionIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(UserCacheEvictionIT.class);

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    private static final String OLD_BIO = "Old bio — cached value";
    private static final String NEW_BIO = "New bio — must invalidate the cache";

    @Autowired
    private UserService userService;

    @Autowired
    private MasterService masterService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    @DisplayName("returns the FRESH bio on the second read — the cached DTO with the old bio was evicted afterCommit")
    void should_returnFreshBioAfterUpdate_when_afterCommitEvictionFires() {
        log.debug("Arrange: seed an active INDEPENDENT_MASTER with a known starting bio");
        UUID userId = UUID.randomUUID();
        seedIndependentMaster(userId, "cache-evict@beautica.test", "Olena", "Koval", OLD_BIO);

        // ── 1. Warm the cache — store the DTO carrying the OLD bio under #userId ──
        MasterDetailResponse warmed =
                transactionTemplate.execute(status -> masterService.getMyMasterDetail(userId));
        assertThat(warmed)
                .as("warm read must return the seeded master")
                .isNotNull();
        assertThat(warmed.bio())
                .as("warm read must carry the OLD bio so a later stale hit would be detectable")
                .isEqualTo(OLD_BIO);

        Cache detailCache = cacheManager.getCache("master-detail-by-user");
        assertThat(detailCache)
                .as("the master-detail-by-user cache must be registered by the real CacheConfig")
                .isNotNull();
        assertThat(detailCache.get(userId))
                .as("after the warm read the entry must be present under the userId key — otherwise the eviction test would be vacuous")
                .isNotNull();

        // ── 2. Mutate the bio in a COMMITTED transaction so afterCommit fires ──
        log.debug("Act: update the master profile bio inside a committed transaction — registers the afterCommit eviction");
        transactionTemplate.executeWithoutResult(status ->
                userService.updateMasterProfile(
                        userId,
                        new MasterProfileUpdateRequest(null, null, null, NEW_BIO, null)));

        // ── 3. Re-read — a fresh DB query (not the stale cache) must surface NEW_BIO ──
        log.debug("Assert: re-read getMyMasterDetail — fresh bio proves the cache entry was evicted after commit");
        MasterDetailResponse afterUpdate =
                transactionTemplate.execute(status -> masterService.getMyMasterDetail(userId));

        assertThat(afterUpdate)
                .as("re-read must still return the master")
                .isNotNull();
        assertThat(afterUpdate.bio())
                .as("second read MUST return the FRESH bio (%s); the stale cached bio (%s) would mean afterCommit eviction did not fire",
                        NEW_BIO, OLD_BIO)
                .isEqualTo(NEW_BIO);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Seeds an email-verified {@code INDEPENDENT_MASTER} user plus the matching active
     * {@code masters} row (no salon) so {@link MasterService#getMyMasterDetail} resolves.
     * Uses the production {@link PasswordEncoder} for a real BCrypt hash (§M — no fake
     * password hashes).
     */
    private void seedIndependentMaster(UUID userId, String email, String firstName,
            String lastName, String bio) {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "bio, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', ?, ?, ?, true, true)",
                userId, email, passwordEncoder.encode(TEST_PASSWORD), firstName, lastName, bio);
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, null, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                UUID.randomUUID(), userId);
    }
}
