package com.beautica.favorite;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.BusinessException;
import com.beautica.favorite.dto.FavoriteResponse;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.favorite.service.FavoriteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real-Postgres coverage for the {@code SALON_SERVICE} favourite arm (salon-service-favourites
 * track, Phase A) — {@link FavoriteService#addFavorite}/{@link FavoriteService#removeFavorite}
 * against a {@code service_definitions} row where {@code owner_type = 'SALON'}, run through the
 * real V136-widened schema.
 *
 * <p>Unit coverage for the validation branching itself (mocked collaborators) lives in
 * {@code FavoriteServiceTest.AddSalonServiceFavorite}; this class exists to prove the write path
 * against the real {@code uq_favorite}/{@code chk_favorite_target_type} constraints and the real
 * "master-performed" join, including under genuine concurrency.
 */
@DisplayName("FavoriteService — SALON_SERVICE target (salon-service-favourites track, Testcontainers)")
class FavoriteSalonServiceIT extends AbstractIntegrationTest {

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private FavoriteRepository favoriteRepository;

    // ── add/remove idempotency ───────────────────────────────────────────────────

    @Test
    @DisplayName("addFavorite twice with the same salon service is idempotent — one row, same id")
    void should_persistSingleRow_when_addFavoriteTwice() {
        UUID clientId = createClient("salon-svc-idem-client@beautica.test");
        UUID salonId = createSalon("salon-svc-idem-owner@beautica.test");
        UUID masterId = createSalonMaster(salonId, "salon-svc-idem-master@beautica.test");
        UUID serviceDefId = createSalonServiceDefinition(salonId, true);
        assignMasterToService(masterId, serviceDefId, true);

        FavoriteResponse first =
                favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId);
        FavoriteResponse second =
                favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(favoriteRepository.count()).isEqualTo(1);
        assertThat(favoriteRepository.existsByClientIdAndTargetTypeAndTargetId(
                clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId)).isTrue();
    }

    @Test
    @DisplayName("removeFavorite is idempotent — succeeds whether or not a row existed")
    void should_succeed_when_removeFavoriteCalledTwice() {
        UUID clientId = createClient("salon-svc-remove-client@beautica.test");
        UUID salonId = createSalon("salon-svc-remove-owner@beautica.test");
        UUID masterId = createSalonMaster(salonId, "salon-svc-remove-master@beautica.test");
        UUID serviceDefId = createSalonServiceDefinition(salonId, true);
        assignMasterToService(masterId, serviceDefId, true);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId);

        favoriteService.removeFavorite(clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId);
        favoriteService.removeFavorite(clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId);

        assertThat(favoriteRepository.existsByClientIdAndTargetTypeAndTargetId(
                clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId)).isFalse();
    }

    // ── rejection: definition owned by an INDEPENDENT_MASTER ────────────────────

    @Test
    @DisplayName("rejects a definition owned by an INDEPENDENT_MASTER — only a SALON-owned definition "
            + "is a valid SALON_SERVICE target")
    void should_reject_when_definitionOwnedByIndependentMaster() {
        UUID clientId = createClient("salon-svc-indep-client@beautica.test");
        UUID masterId = createIndependentMaster("salon-svc-indep-master@beautica.test");
        UUID serviceDefId = createIndependentMasterServiceDefinition(masterId);

        assertThatThrownBy(() -> favoriteService.addFavorite(
                clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId))
                .isInstanceOf(BusinessException.class);

        assertThat(favoriteRepository.count()).isZero();
    }

    // ── rejection: no active master performs it ─────────────────────────────────

    @Test
    @DisplayName("rejects a salon-owned definition no active master performs")
    void should_reject_when_noActiveMasterPerformsService() {
        UUID clientId = createClient("salon-svc-noperform-client@beautica.test");
        UUID salonId = createSalon("salon-svc-noperform-owner@beautica.test");
        // Active salon, active definition, but NO master_services row at all.
        UUID serviceDefId = createSalonServiceDefinition(salonId, true);

        assertThatThrownBy(() -> favoriteService.addFavorite(
                clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId))
                .isInstanceOf(BusinessException.class);

        assertThat(favoriteRepository.count()).isZero();
    }

    @Test
    @DisplayName("rejects a salon-owned definition whose only assignment is INACTIVE")
    void should_reject_when_onlyAssignmentIsInactive() {
        UUID clientId = createClient("salon-svc-inactive-assign-client@beautica.test");
        UUID salonId = createSalon("salon-svc-inactive-assign-owner@beautica.test");
        UUID masterId = createSalonMaster(salonId, "salon-svc-inactive-assign-master@beautica.test");
        UUID serviceDefId = createSalonServiceDefinition(salonId, true);
        assignMasterToService(masterId, serviceDefId, false);

        assertThatThrownBy(() -> favoriteService.addFavorite(
                clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId))
                .isInstanceOf(BusinessException.class);

        assertThat(favoriteRepository.count()).isZero();
    }

    // ── rejection: owning salon is inactive ──────────────────────────────────────

    @Test
    @DisplayName("rejects a salon-owned definition whose owning salon has been deactivated, even "
            + "though the assignment and the master both stay active (SalonService.deactivateSalon "
            + "does not cascade to masters.is_active)")
    void should_reject_when_owningSalonInactive() {
        UUID clientId = createClient("salon-svc-inactive-salon-client@beautica.test");
        UUID salonId = createSalon("salon-svc-inactive-salon-owner@beautica.test");
        UUID masterId = createSalonMaster(salonId, "salon-svc-inactive-salon-master@beautica.test");
        UUID serviceDefId = createSalonServiceDefinition(salonId, true);
        assignMasterToService(masterId, serviceDefId, true);
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salonId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_active FROM masters WHERE id = ?", Boolean.class, masterId))
                .as("precondition: deactivating the salon does not cascade to the master")
                .isTrue();

        assertThatThrownBy(() -> favoriteService.addFavorite(
                clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId))
                .isInstanceOf(BusinessException.class);

        assertThat(favoriteRepository.count()).isZero();
    }

    @Test
    @DisplayName("rejects a soft-deleted (inactive) service definition")
    void should_reject_when_definitionInactive() {
        UUID clientId = createClient("salon-svc-inactive-def-client@beautica.test");
        UUID salonId = createSalon("salon-svc-inactive-def-owner@beautica.test");
        UUID masterId = createSalonMaster(salonId, "salon-svc-inactive-def-master@beautica.test");
        UUID serviceDefId = createSalonServiceDefinition(salonId, false);
        assignMasterToService(masterId, serviceDefId, true);

        assertThatThrownBy(() -> favoriteService.addFavorite(
                clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId))
                .isInstanceOf(BusinessException.class);

        assertThat(favoriteRepository.count()).isZero();
    }

    // ── uq_favorite dedupe under GENUINE concurrency ────────────────────────────

    /**
     * Two threads race {@code addFavorite} for the SAME (client, SALON_SERVICE, serviceDefId)
     * tuple. This is a real-thread proof against a real Postgres, not the mocked
     * {@code DataIntegrityViolationException} unit test — it is a genuinely stronger check than
     * that mock and it caught something the mock could not.
     *
     * <p><b>Finding surfaced by writing this as a REAL concurrency test (pre-existing, not
     * introduced by this track — reproduces identically for MASTER/SALON/SERVICE targets, since
     * {@code insertFavorite} is one shared method).</b> {@code FavoriteService#addFavorite} is
     * {@code @Transactional} end-to-end. When the loser's {@code saveAndFlush} hits
     * {@code uq_favorite}, Postgres marks that session's transaction ABORTED (protocol-level, not
     * just the one statement) — so the catch block's fallback
     * {@code findByClientIdAndTargetTypeAndTargetId} re-read, running in the SAME now-aborted
     * transaction, itself fails with {@code 25P02 current transaction is aborted}, surfacing as a
     * {@code JpaSystemException} instead of the intended graceful idempotent return. The mocked
     * unit-test sibling ({@code FavoriteServiceTest#should_returnRacedRow_when_...}) cannot see
     * this: Mockito's fake repository has no real transaction to abort, so the re-read mock always
     * "succeeds". <b>uq_favorite itself is never at risk</b> — Postgres still allows exactly one
     * row for the tuple — only the graceful-idempotent-return CONTRACT can currently fail for the
     * loser under a true race. Flagged as a pre-existing gap for a dedicated follow-up (a
     * {@code Propagation.REQUIRES_NEW} re-read after marking the outer transaction rollback-only);
     * fixing the shared four-arm transaction boundary is out of scope for this migration task.
     *
     * <p>This test therefore asserts the guarantee that DOES unconditionally hold — the DB-level
     * dedupe — while tolerating (and logging) the pre-existing exception on the losing side,
     * rather than asserting a "neither caller observes an exception" contract the code does not
     * currently deliver.
     */
    @Test
    @DisplayName("two concurrent addFavorite calls for the same target persist exactly one row "
            + "(uq_favorite dedupe holds under genuine concurrency)")
    void should_persistExactlyOneRow_when_twoThreadsRaceTheSameSalonServiceFavorite() throws Exception {
        UUID clientId = createClient("salon-svc-race-client@beautica.test");
        UUID salonId = createSalon("salon-svc-race-owner@beautica.test");
        UUID masterId = createSalonMaster(salonId, "salon-svc-race-master@beautica.test");
        UUID serviceDefId = createSalonServiceDefinition(salonId, true);
        assignMasterToService(masterId, serviceDefId, true);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<FavoriteResponse> race = () ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, serviceDefId);
            Future<FavoriteResponse> f1 = pool.submit(race);
            Future<FavoriteResponse> f2 = pool.submit(race);

            int successes = 0;
            int failures = 0;
            for (Future<FavoriteResponse> f : List.of(f1, f2)) {
                try {
                    f.get(10, TimeUnit.SECONDS);
                    successes++;
                } catch (Exception e) {
                    // See the pre-existing-gap note above: the loser MAY currently surface a
                    // transactional error instead of a graceful idempotent return. Tolerated here;
                    // the row-count assertion below is the guarantee this test actually pins.
                    failures++;
                }
            }

            assertThat(successes)
                    .as("at least one of the two racing callers must succeed")
                    .isGreaterThanOrEqualTo(1);
            assertThat(successes + failures).isEqualTo(2);
            assertThat(favoriteRepository.count())
                    .as("uq_favorite must allow exactly one row for this tuple, no matter how many "
                            + "concurrent callers raced to create it — this is the guarantee that must "
                            + "never break, independent of the graceful-return gap noted above")
                    .isEqualTo(1);
        } finally {
            pool.shutdown();
        }
    }

    // ── seed helpers (ASCII data) ────────────────────────────────────────────────

    private UUID createClient(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'CLIENT', true, true)",
                id, email);
        return id;
    }

    private UUID createSalon(String ownerEmail) {
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'SALON_OWNER', true, true)",
                ownerId, ownerEmail);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'Test Salon', true, NOW(), NOW())",
                salonId, ownerId);
        return salonId;
    }

    private UUID createSalonMaster(UUID salonId, String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'SALON_MASTER', true, true)",
                userId, email);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId, salonId);
        return masterId;
    }

    private UUID createIndependentMaster(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', true, true)",
                userId, email);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID createIndependentMasterServiceDefinition(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions "
                        + "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, "
                        + "buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId, resolveUnusedServiceTypeId("INDEPENDENT_MASTER", userId));
        return serviceDefId;
    }

    /** A salon-owned service definition — {@code owner_type = 'SALON'}, no assignment yet. */
    private UUID createSalonServiceDefinition(UUID salonId, boolean active) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions "
                        + "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, "
                        + "buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, ?, NOW(), NOW())",
                serviceDefId, salonId, resolveUnusedServiceTypeId("SALON", salonId), active);
        return serviceDefId;
    }

    private void assignMasterToService(UUID masterId, UUID serviceDefId, boolean assignmentActive) {
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), masterId, serviceDefId, assignmentActive);
    }
}
