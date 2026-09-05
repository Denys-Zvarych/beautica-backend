package com.beautica.user;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.EmailVerificationProcessor;
import com.beautica.auth.TokenGenerator;
import com.beautica.auth.dto.VerifyEmailRequest;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.dto.MasterDetailResponse;
import com.beautica.master.dto.MasterProfileUpdateRequest;
import com.beautica.master.service.MasterService;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.search.dto.MasterSearchRequest;
import com.beautica.search.service.SearchService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 *   <li><b>Warm</b> — call {@link MasterService#findMyMasterDetail} so the
 *       {@code @Cacheable} value (DTO with the OLD bio) is stored under {@code userId}.</li>
 *   <li><b>Mutate + commit</b> — call {@link UserService#updateMasterProfile} inside a
 *       committed transaction so the {@code afterCommit} synchronization actually fires.</li>
 *   <li><b>Re-read</b> — call {@code findMyMasterDetail} again. If eviction fired, the
 *       cache misses, the DB is re-queried, and the FRESH bio is returned. If eviction
 *       silently failed, the stale cached DTO (OLD bio) would come back.</li>
 * </ol>
 * The FRESH bio on the second read is the proof the after-commit eviction ran.
 */
@DisplayName("UserService cache eviction — master-detail-by-user, search:masters and user-profile (real tx, real CacheConfig)")
class UserCacheEvictionIT extends AbstractIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(UserCacheEvictionIT.class);

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";
    private static final String OLD_BIO = "Old bio — cached value";
    private static final String NEW_BIO = "New bio — must invalidate the cache";
    private static final String OLD_STREET = "вул. Стара";
    private static final String NEW_STREET = "вул. Нова";
    private static final String VERIFICATION_OTP = "123456";

    @Autowired
    private UserService userService;

    @Autowired
    private MasterService masterService;

    @Autowired
    private SearchService searchService;

    @Autowired
    private com.beautica.salon.service.SalonService salonService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EmailVerificationProcessor emailVerificationProcessor;

    @Autowired
    private TokenGenerator tokenGenerator;

    @Autowired
    private Clock clock;

    @Test
    @DisplayName("returns the FRESH bio on the second read — the cached DTO with the old bio was evicted afterCommit")
    void should_returnFreshBioAfterUpdate_when_afterCommitEvictionFires() {
        log.debug("Arrange: seed an active INDEPENDENT_MASTER with a known starting bio");
        UUID userId = UUID.randomUUID();
        seedIndependentMaster(userId, "cache-evict@beautica.test", "Olena", "Koval", OLD_BIO);

        // ── 1. Warm the cache — store the DTO carrying the OLD bio under #userId ──
        MasterDetailResponse warmed = transactionTemplate
                .execute(status -> masterService.findMyMasterDetail(userId))
                .orElse(null);
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
                        new MasterProfileUpdateRequest(null, null, null, NEW_BIO, null, null)));

        // ── 3. Re-read — a fresh DB query (not the stale cache) must surface NEW_BIO ──
        log.debug("Assert: re-read getMyMasterDetail — fresh bio proves the cache entry was evicted after commit");
        MasterDetailResponse afterUpdate = transactionTemplate
                .execute(status -> masterService.findMyMasterDetail(userId))
                .orElse(null);

        assertThat(afterUpdate)
                .as("re-read must still return the master")
                .isNotNull();
        assertThat(afterUpdate.bio())
                .as("second read MUST return the FRESH bio (%s); the stale cached bio (%s) would mean afterCommit eviction did not fire",
                        NEW_BIO, OLD_BIO)
                .isEqualTo(NEW_BIO);
    }

    @Test
    @DisplayName("clears the entire search:masters:browse cache after a name change commits for an INDEPENDENT_MASTER — the discovery eviction branch fired")
    void should_clearSearchMastersCache_when_independentMasterNameChangeCommits() {
        log.debug("Arrange: seed an active INDEPENDENT_MASTER who is searchable in the discovery cache");
        UUID userId = UUID.randomUUID();
        seedIndependentMaster(userId, "search-evict@beautica.test", "Iryna", "Bondarenko", OLD_BIO);

        // ── 1. Warm the discovery cache — an unfiltered page-0 search stores one
        //        entry under the real @Cacheable key in search:masters:browse ──────────────
        log.debug("Arrange: run an unfiltered discovery search to populate search:masters:browse");
        MasterSearchRequest searchRequest =
                new MasterSearchRequest(null, null, null, null, null, null, null, null, null, null);
        transactionTemplate.execute(status ->
                searchService.searchMasters(searchRequest, PageRequest.of(0, 20)));

        // …and a FREE-TEXT search, which SearchCacheResolver routes to the other half of the
        // split cache (search:masters:q). A renamed master must disappear from BOTH, so the
        // eviction has to clear both — warming only the browse half would let a
        // one-cache-only regression pass.
        MasterSearchRequest freeTextRequest =
                new MasterSearchRequest(null, "Bondarenko", null, null, null, null, null, null, null, null);
        transactionTemplate.execute(status ->
                searchService.searchMasters(freeTextRequest, PageRequest.of(0, 20)));

        Cache searchCache = cacheManager.getCache("search:masters:browse");
        assertThat(searchCache)
                .as("the search:masters:browse cache must be registered by the real CacheConfig")
                .isNotNull();
        Cache searchQueryCache = cacheManager.getCache("search:masters:q");
        assertThat(searchQueryCache)
                .as("the search:masters:q cache must be registered by the real CacheConfig")
                .isNotNull();
        com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeSearch =
                ((CaffeineCache) searchCache).getNativeCache();
        com.github.benmanes.caffeine.cache.Cache<Object, Object> nativeSearchQuery =
                ((CaffeineCache) searchQueryCache).getNativeCache();
        nativeSearch.cleanUp();
        nativeSearchQuery.cleanUp();
        assertThat(nativeSearch.estimatedSize())
                .as("after the warm search the discovery cache must hold at least one entry — otherwise the eviction assertion would be vacuous")
                .isPositive();
        assertThat(nativeSearchQuery.estimatedSize())
                .as("the free-text search must have landed in search:masters:q — otherwise the routing is wrong and the second eviction assertion would be vacuous")
                .isPositive();

        // ── 2. Change the master's DISPLAY NAME in a COMMITTED transaction. firstName
        //        is a searchAffected field (UserService.updateMasterProfile:162), so the
        //        afterCommit callback hits the `searchAffected && INDEPENDENT_MASTER`
        //        branch (UserService.evictUserCachesAfterCommit:215) and calls clear(). ──
        log.debug("Act: update firstName (a searchAffected field) inside a committed transaction — registers the afterCommit search:masters:browse clear()");
        transactionTemplate.executeWithoutResult(status ->
                userService.updateMasterProfile(
                        userId,
                        new MasterProfileUpdateRequest("Iryna-Renamed", null, null, null, null, null)));

        // ── 3. The whole discovery cache must now be empty. If the eviction branch were
        //        removed (or its role/searchAffected gate broke), this entry would survive
        //        and estimatedSize() would still be positive → the test fails. ───────────
        log.debug("Assert: search:masters:browse is empty after commit — proves the discovery-cache eviction branch fired");
        nativeSearch.cleanUp();
        nativeSearchQuery.cleanUp();
        assertThat(nativeSearch.estimatedSize())
                .as("search:masters:browse MUST be cleared after an INDEPENDENT_MASTER name change commits; a surviving entry means the searchAffected eviction branch did not fire")
                .isZero();
        assertThat(nativeSearchQuery.estimatedSize())
                .as("search:masters:q MUST be cleared too — the eviction iterates SearchCacheNames.MASTERS_ALL; a surviving entry means only one half of the split cache was cleared")
                .isZero();
    }

    // ── user-profile cache (audit-fix cycle 2, LOW) ───────────────────────────

    @Test
    @DisplayName("GET /users/me is served from the user-profile cache, and a users-side write evicts it")
    void should_returnFreshProfile_when_updateProfileCommits() {
        // Arrange
        UUID userId = UUID.randomUUID();
        seedIndependentMaster(userId, "profile-evict@beautica.test", "Olena", "Koval", OLD_BIO);

        UserProfileResponse warmed =
                transactionTemplate.execute(status -> userService.getProfile(userId));
        assertThat(warmed.firstName()).isEqualTo("Olena");

        Cache profileCache = cacheManager.getCache(UserProfileCacheEvictor.USER_PROFILE_CACHE);
        assertThat(profileCache)
                .as("user-profile must be registered by the real CacheConfig")
                .isNotNull();
        assertThat(profileCache.get(userId))
                .as("the warm read must have populated the key — otherwise this whole test is vacuous "
                        + "and would pass with no cache at all")
                .isNotNull();

        // Act
        transactionTemplate.executeWithoutResult(status ->
                userService.updateMasterProfile(
                        userId,
                        new MasterProfileUpdateRequest("Olena-Renamed", null, null, null, null, null)));

        // Assert
        UserProfileResponse afterUpdate =
                transactionTemplate.execute(status -> userService.getProfile(userId));
        assertThat(afterUpdate.firstName())
                .as("the second read must reflect the committed write; the old name would mean the "
                        + "afterCommit user-profile eviction did not fire")
                .isEqualTo("Olena-Renamed");
    }

    /**
     * THE cross-aggregate guard — the actual risk in caching {@code GET /users/me}, and the one a
     * users-side-only cache test would leave entirely uncovered.
     *
     * <p>{@code UserProfileResponse.hasMasterProfile} is derived from an active {@code masters} row
     * of type {@code SALON_OWNER}. {@code UserService} neither owns nor writes that row, so a
     * {@code MasterService} write stales a {@code user}-package cache with nothing in the type
     * system, the call graph, or the package structure to say so. This test drives the flip in BOTH
     * directions through real services, real transactions and the real Caffeine caches.
     */
    @Test
    @DisplayName("a masters-side write invalidates the user-profile cache — hasMasterProfile flips both ways")
    void should_invalidateUserProfileCache_when_masterRowIsDeactivatedAndReactivated() {
        // Arrange — an owner opted in as a master.
        UUID ownerId = UUID.randomUUID();
        UUID salonId = seedSalonOwnerWithMasterRow(ownerId, "owner-xagg@beautica.test");

        UserProfileResponse optedIn =
                transactionTemplate.execute(status -> userService.getProfile(ownerId));
        assertThat(optedIn.hasMasterProfile())
                .as("the seeded owner has an ACTIVE SALON_OWNER-type master row, so the warm read "
                        + "must report true — otherwise the flip below proves nothing")
                .isTrue();

        // Act 1 — toggle «Я також працюю як майстер» OFF through MasterService.
        transactionTemplate.executeWithoutResult(status ->
                masterService.deactivateOwnerMaster(ownerId, salonId));

        // Assert 1 — a `masters` write must have invalidated a `users` cache.
        assertThat(transactionTemplate.execute(status -> userService.getProfile(ownerId))
                .hasMasterProfile())
                .as("hasMasterProfile MUST read false after deactivation. A surviving `true` here is "
                        + "the cross-aggregate staleness this cache's registration comment warns "
                        + "about: the app would keep rendering a master section fed by GET "
                        + "/masters/me, which now 404s, for the full 5-minute TTL")
                .isFalse();

        // Act 2 — toggle back ON, exercising the reactivation branch.
        transactionTemplate.executeWithoutResult(status ->
                masterService.createMasterForOwner(ownerId, salonId));

        // Assert 2 — the inverse direction, which the deactivate-only contract of cycle 1 missed.
        assertThat(transactionTemplate.execute(status -> userService.getProfile(ownerId))
                .hasMasterProfile())
                .as("hasMasterProfile MUST read true again after reactivation; a stale `false` would "
                        + "keep the master section hidden from an owner who just enabled it")
                .isTrue();
    }

    /**
     * The {@code SalonService} half of the writer set, which nothing else observed.
     *
     * <p>{@code UserProfileCacheEvictor}'s javadoc names four {@code SalonService}/
     * {@code EmailVerificationProcessor} evict sites alongside the {@code MasterService} ones.
     * The {@code MasterService} sites are all proved against the real evictor and the real
     * {@code CacheConfig} (this class's cross-aggregate test above, plus {@code OwnerMasterCacheTest}).
     * The {@code SalonService} sites were not: the nine {@code @InjectMocks} unit classes declare
     * the collaborator only so constructor injection does not hand {@code @InjectMocks} a
     * {@code null}, and none of them stubs or verifies it — so deleting
     * {@code userProfileCacheEvictor.evictAfterCommit(userId)} from {@code removeAdmin} left the
     * entire suite green.
     *
     * <p>This pins it behaviourally rather than with a {@code verify()} on a mock: a mock verify
     * would restate the source line and still pass if the evictor evicted the wrong key, the
     * wrong cache, or before the commit instead of after it.
     *
     * <p><b>Phase 299 changed what "removed" means, and therefore what this test asserts.</b>
     * {@code removeAdmin} used to null {@code users.salon_id} and leave the account alive, so the
     * observable stale value was a salonId naming a salon the admin had left. It now routes through
     * {@code SalonService#disposeStaffAccounts} and HARD-DELETES the {@code users} row, so the
     * stale value would be an entire profile DTO for an account that no longer exists — a strictly
     * worse leak, and one that {@code getProfile} itself surfaces: a surviving cache entry short-
     * circuits the repository lookup and returns the pre-deletion DTO instead of raising
     * {@link NotFoundException}. That throw is the behavioural assertion below; the cache-key
     * assertion beside it is what distinguishes a real eviction from a coincidental miss.
     */
    @Test
    @DisplayName("SalonService.removeAdmin evicts the REMOVED ADMIN's user-profile entry — the "
            + "stale DTO would otherwise outlive the hard-deleted account")
    void should_invalidateUserProfileCache_when_removeAdminCommits() {
        // Arrange — an owner with a salon, and a SALON_ADMIN assigned to it. The admin, not the
        // actor, is the cache key under test: removeAdmin nulls the ADMIN's users.salon_id while
        // the owner's row is untouched, so an implementation that evicted actorId would look
        // correct to a careless assertion.
        UUID ownerId = UUID.randomUUID();
        UUID salonId = seedSalonOwnerWithMasterRow(ownerId, "owner-removeadmin@beautica.test");
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_ADMIN', 'Adm', 'Adminenko', ?, true, true)",
                adminId, "admin-removeadmin@beautica.test",
                passwordEncoder.encode(TEST_PASSWORD), salonId);

        UserProfileResponse warmed =
                transactionTemplate.execute(status -> userService.getProfile(adminId));
        assertThat(warmed.salonId())
                .as("precondition: the warm read must report the admin's salon, otherwise the "
                        + "assertion below would pass against an empty cache and prove nothing")
                .isEqualTo(salonId);
        assertThat(cacheManager.getCache(UserProfileCacheEvictor.USER_PROFILE_CACHE).get(adminId))
                .as("precondition: the warm read must have POPULATED the admin's key — a miss here "
                        + "makes the whole test vacuous")
                .isNotNull();

        // Act
        log.debug("Act: removeAdmin strips the admin off the salon — the admin's cached "
                + "GET /users/me must not survive the commit");
        transactionTemplate.executeWithoutResult(status ->
                salonService.removeAdmin(ownerId, salonId, adminId));

        // Assert
        assertThat(cacheManager.getCache(UserProfileCacheEvictor.USER_PROFILE_CACHE).get(adminId))
                .as("the admin's key MUST be gone from user-profile after the commit — this is the "
                        + "eviction itself, asserted where a coincidental miss cannot be mistaken "
                        + "for one (the precondition above proved the key was populated)")
                .isNull();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> userService.getProfile(adminId)))
                .as("getProfile MUST fall through to the repository and raise NotFoundException for "
                        + "the hard-deleted account. A surviving cache entry would short-circuit "
                        + "that lookup and hand the removed admin's app a full pre-deletion profile "
                        + "for the whole 5-minute TTL")
                .isInstanceOf(NotFoundException.class);
    }

    /**
     * Closes the second of the three remaining unproven {@code user-profile} evict sites
     * ({@code SalonService#rotateAdmin}, {@link com.beautica.salon.service.SalonService#rotateAdmin
     * SalonService.java:850}).
     *
     * <p>Same reasoning as the {@code removeAdmin} test above, and the same trap avoided: the
     * key under test is the ROTATED ADMIN, never the acting owner. {@code rotateAdmin} rewrites
     * {@code users.salon_id} on the admin's row only — the actor's row is untouched — so an
     * implementation that evicted {@code actorId} would leave the admin serving the SOURCE salon's
     * id out of cache while looking correct to an assertion keyed on the actor.
     *
     * <p>Deliberately NOT a {@code verify()} on a mocked evictor: with no active transaction
     * {@code evictAfterCommit} takes its {@code isSynchronizationActive() == false} branch, so a
     * mock verify passes even when the evictor hits the wrong key, the wrong cache, or fires
     * before the commit.
     */
    @Test
    @DisplayName("SalonService.rotateAdmin evicts the ROTATED ADMIN's user-profile entry — the "
            + "stale salonId would otherwise name the SOURCE salon they were just moved off")
    void should_invalidateUserProfileCache_when_rotateAdminCommits() {
        // Arrange — one owner, two ACTIVE salons of theirs (rotation is same-owner only), and a
        // SALON_ADMIN currently assigned to the source salon.
        UUID ownerId = UUID.randomUUID();
        UUID sourceSalonId = seedSalonOwnerWithMasterRow(ownerId, "owner-rotateadmin@beautica.test");
        UUID destinationSalonId = seedExtraSalon(ownerId, "Rotation destination");
        UUID adminId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_ADMIN', 'Adm', 'Rotatenko', ?, true, true)",
                adminId, "admin-rotateadmin@beautica.test",
                passwordEncoder.encode(TEST_PASSWORD), sourceSalonId);

        UserProfileResponse warmed =
                transactionTemplate.execute(status -> userService.getProfile(adminId));
        assertThat(warmed.salonId())
                .as("precondition: the warm read must report the SOURCE salon, otherwise the "
                        + "assertion below could not distinguish a stale hit from a fresh read")
                .isEqualTo(sourceSalonId);
        assertThat(cacheManager.getCache(UserProfileCacheEvictor.USER_PROFILE_CACHE).get(adminId))
                .as("precondition: the warm read must have POPULATED the admin's key — a miss here "
                        + "makes the whole test vacuous")
                .isNotNull();

        // Act
        log.debug("Act: rotateAdmin moves the admin between two salons of the same owner — the "
                + "admin's cached GET /users/me must not survive the commit");
        transactionTemplate.executeWithoutResult(status ->
                salonService.rotateAdmin(ownerId, sourceSalonId, adminId, destinationSalonId));

        // Assert
        assertThat(transactionTemplate.execute(status -> userService.getProfile(adminId)).salonId())
                .as("salonId MUST read the DESTINATION salon after rotation. The source id "
                        + "surviving is the cached pre-rotation DTO: the rotated admin's app would "
                        + "keep addressing the salon they were just moved off for the 5-minute TTL")
                .isEqualTo(destinationSalonId);
    }

    /**
     * Closes the third remaining unproven {@code user-profile} evict site
     * ({@code SalonService#createSalon}, {@code SalonService.java:208}).
     *
     * <p><b>Why the SECOND salon, and why {@code street} rather than {@code hasMasterProfile}.</b>
     * {@code createSalon} writes the owner's own {@code users} row through two distinct channels:
     * the locality sync (five {@code UserProfileResponse} fields, guarded by
     * {@code request.cityId() != null}) and, on FIRST-salon registration only,
     * {@code MasterService#createMasterForOwner}, which flips {@code hasMasterProfile}. That second
     * channel registers its OWN evict. So a test that created a first salon and asserted on
     * {@code hasMasterProfile} would stay green with {@code SalonService}'s evict deleted — it
     * would be proving {@code MasterService}'s guard a second time. Seeding a pre-existing salon
     * makes {@code isFirstSalon} false, which removes the master-creation channel entirely and
     * leaves the locality sync as the only writer and this line as the only evict.
     */
    @Test
    @DisplayName("SalonService.createSalon evicts the OWNER's user-profile entry — the stale "
            + "locality would otherwise report the address of the previous salon")
    void should_invalidateUserProfileCache_when_createSalonCommits() {
        // Arrange — an owner who ALREADY has a salon (so isFirstSalon is false and no owner-master
        // row is created by this call), with a known starting street on their users row.
        UUID ownerId = UUID.randomUUID();
        seedSalonOwnerWithMasterRow(ownerId, "owner-createsalon@beautica.test");
        jdbcTemplate.update("UPDATE users SET city_id = ?, street = ?, building_no = ? WHERE id = ?",
                testCityId(), OLD_STREET, "1", ownerId);

        UserProfileResponse warmed =
                transactionTemplate.execute(status -> userService.getProfile(ownerId));
        assertThat(warmed.street())
                .as("precondition: the warm read must carry the OLD street so a later stale hit "
                        + "would be detectable")
                .isEqualTo(OLD_STREET);
        assertThat(cacheManager.getCache(UserProfileCacheEvictor.USER_PROFILE_CACHE).get(ownerId))
                .as("precondition: the warm read must have POPULATED the owner's key — a miss here "
                        + "makes the whole test vacuous")
                .isNotNull();

        // Act — a SECOND salon at a different street; the cityId is non-null so the locality sync
        // onto the owner's users row actually runs.
        log.debug("Act: createSalon syncs the new salon's locality onto the owner's users row — "
                + "the owner's cached GET /users/me must not survive the commit");
        transactionTemplate.executeWithoutResult(status ->
                salonService.createSalon(ownerId, new CreateSalonRequest(
                        "Second salon", null, null, null, null, null, null,
                        testCityId(), null, NEW_STREET, "7", null)));

        // Assert
        assertThat(transactionTemplate.execute(status -> userService.getProfile(ownerId)).street())
                .as("street MUST read the NEW salon's street (%s). The old value (%s) surviving is "
                        + "the cached pre-create DTO: GET /users/me would keep reporting the "
                        + "previous address for the full 5-minute TTL", NEW_STREET, OLD_STREET)
                .isEqualTo(NEW_STREET);
    }

    /**
     * Closes the last remaining unproven {@code user-profile} evict site
     * ({@link com.beautica.auth.EmailVerificationProcessor#verifyAndReturnUserId}, the
     * {@code evictAfterCommit} on the success path).
     *
     * <p>Keyed on {@code emailVerified}, the only one of the six columns that method writes which
     * {@code UserProfileResponse} carries (the other five are OTP bookkeeping and are deliberately
     * not on the DTO). The processor is driven DIRECTLY rather than through
     * {@code AuthService#verifyEmail} because the eviction is registered on THIS bean's
     * {@code @Transactional} proxy — that placement is the point of the site, and going through the
     * caller would leave a passing test that no longer distinguishes the two.
     *
     * <p>The stale entry is user-visible: the app polls {@code /users/me} straight after verifying
     * and would keep being told to verify an account that just verified successfully.
     */
    @Test
    @DisplayName("EmailVerificationProcessor.verifyAndReturnUserId evicts the verifier's "
            + "user-profile entry — a stale emailVerified=false re-prompts a verified account")
    void should_invalidateUserProfileCache_when_emailVerificationCommits() {
        // Arrange — an UNVERIFIED client holding a live OTP, stored as the production hash.
        UUID userId = UUID.randomUUID();
        String email = "verify-cache@beautica.test";
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified, verification_code_hash, "
                        + "verification_code_expires_at) "
                        + "VALUES (?, ?, ?, 'CLIENT', 'Ver', 'Verenko', true, false, ?, ?)",
                userId, email, passwordEncoder.encode(TEST_PASSWORD),
                tokenGenerator.hashOtp(VERIFICATION_OTP),
                java.sql.Timestamp.from(clock.instant().plusSeconds(600)));

        UserProfileResponse warmed =
                transactionTemplate.execute(status -> userService.getProfile(userId));
        assertThat(warmed.emailVerified())
                .as("precondition: the warm read must carry emailVerified=false so a later stale "
                        + "hit would be detectable")
                .isFalse();
        assertThat(cacheManager.getCache(UserProfileCacheEvictor.USER_PROFILE_CACHE).get(userId))
                .as("precondition: the warm read must have POPULATED the user's key — a miss here "
                        + "makes the whole test vacuous")
                .isNotNull();

        // Act — no TransactionTemplate here on purpose: the evict hangs off the processor's own
        // @Transactional proxy, which is exactly the boundary under test.
        log.debug("Act: verifyAndReturnUserId flips users.email_verified — the caller's cached "
                + "GET /users/me must not survive the commit");
        emailVerificationProcessor.verifyAndReturnUserId(
                new VerifyEmailRequest(email, VERIFICATION_OTP));

        // Assert
        assertThat(transactionTemplate.execute(status -> userService.getProfile(userId))
                .emailVerified())
                .as("emailVerified MUST read true after verification. A surviving false is the "
                        + "cached pre-verification DTO: the app would keep asking a verified "
                        + "account to verify for the full 5-minute TTL")
                .isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Seeds one extra ACTIVE salon for an existing owner — the rotation DESTINATION.
     * {@code rotateAdmin} requires the destination to share the source's owner and to be active.
     */
    private UUID seedExtraSalon(UUID ownerId, String name) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, name, testCityId());
        return salonId;
    }

    /**
     * Seeds an email-verified {@code INDEPENDENT_MASTER} user plus the matching active
     * {@code masters} row (no salon) so {@link MasterService#findMyMasterDetail} resolves.
     * Uses the production {@link PasswordEncoder} for a real BCrypt hash (§M — no fake
     * password hashes).
     */
    /**
     * Seeds a {@code SALON_OWNER} with a salon and an ACTIVE {@code SALON_OWNER}-type
     * {@code masters} row — the shape {@code UserService#resolveHasMasterProfile} reads
     * {@code true} for. {@code salons.city_id} is NOT NULL since V150/V151, hence
     * {@code testCityId()}.
     *
     * @return the seeded salon's id, which the owner-master row is pinned to
     */
    private UUID seedSalonOwnerWithMasterRow(UUID ownerId, String email) {
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', 'Owner', 'Ownerenko', true, true)",
                ownerId, email, passwordEncoder.encode(TEST_PASSWORD));
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Cross-aggregate salon", testCityId());
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, NOW(), NOW())",
                UUID.randomUUID(), ownerId, salonId);
        return salonId;
    }

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
