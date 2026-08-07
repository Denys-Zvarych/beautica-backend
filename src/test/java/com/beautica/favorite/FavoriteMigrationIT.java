package com.beautica.favorite;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.BusinessException;
import com.beautica.favorite.dto.FavoriteMasterResponse;
import com.beautica.favorite.dto.FavoriteResponse;
import com.beautica.favorite.dto.FavoriteServiceResponse;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.favorite.service.FavoriteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers integration test for the favorites persistence contract — exercises the
 * {@code V92} schema (CHECK + UNIQUE), the {@code V134} widening that admits
 * {@code SERVICE} (Phase 31.3), the two native read projections and the JPA wish-list
 * page (Phase 31.4) against a real PostgreSQL, plus the service-layer idempotency and
 * per-client {@code lastServiceName} resolution. ASCII-only seed data.
 *
 * <p>Validation runs at the repository/service layer (not over HTTP) so the test
 * stays focused on the persistence contract the migration introduces; the HTTP
 * contract is covered by {@code FavoriteControllerTest}.
 */
@DisplayName("Favorites — V92/V134 migrations + read projections (Testcontainers)")
class FavoriteMigrationIT extends AbstractIntegrationTest {

    @Autowired
    private FavoriteService favoriteService;

    @Autowired
    private FavoriteRepository favoriteRepository;

    // ── V92 constraints ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("chk_favorite_target_type rejects a third target_type value")
    void should_rejectInsert_when_targetTypeNotMasterOrSalon() {
        UUID clientId = createClient("check-client@beautica.test");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO favorites (client_id, target_type, target_id) VALUES (?, 'OTHER', ?)",
                clientId, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("uq_favorite rejects a duplicate (client_id, target_type, target_id) tuple")
    void should_rejectInsert_when_duplicateTuple() {
        UUID clientId = createClient("uq-client@beautica.test");
        UUID targetId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO favorites (client_id, target_type, target_id) VALUES (?, 'SALON', ?)",
                clientId, targetId);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO favorites (client_id, target_type, target_id) VALUES (?, 'SALON', ?)",
                clientId, targetId))
                .isInstanceOf(DataAccessException.class);
    }

    // ── idempotent add (POST twice → one row, same favorite) ─────────────────────

    @Test
    @DisplayName("addFavorite twice with the same target is idempotent — one row, same id")
    void should_persistSingleRow_when_addFavoriteTwice() {
        UUID clientId = createClient("idempotent-client@beautica.test");
        UUID salonId = createSalon("owner-idem@beautica.test");

        FavoriteResponse first = favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, salonId);
        FavoriteResponse second = favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, salonId);

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(favoriteRepository.count()).isEqualTo(1);
        assertThat(favoriteRepository.existsByClientIdAndTargetTypeAndTargetId(
                clientId, FavoriteTargetType.SALON, salonId)).isTrue();
    }

    // ── per-client lastServiceName resolution ────────────────────────────────────

    @Test
    @DisplayName("listMasterFavorites returns this client's latest booking service name, null when never booked")
    void should_resolveLastServiceName_perClient() {
        UUID clientId = createClient("booked-client@beautica.test");

        // Master A — this client has a completed booking → lastServiceName populated.
        UUID masterA = createIndependentMaster("master-a@beautica.test");
        UUID msA = createIndependentMasterService(masterA);
        createCompletedBooking(clientId, masterA, msA);
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, masterA);

        // Master B — favorited but never booked by this client → lastServiceName null.
        UUID masterB = createIndependentMaster("master-b@beautica.test");
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, masterB);

        List<FavoriteMasterResponse> masters =
                favoriteService.listMasterFavorites(clientId, Pageable.unpaged()).getContent();

        assertThat(masters).hasSize(2);
        FavoriteMasterResponse withBooking = masters.stream()
                .filter(m -> m.masterId().equals(masterA)).findFirst().orElseThrow();
        FavoriteMasterResponse withoutBooking = masters.stream()
                .filter(m -> m.masterId().equals(masterB)).findFirst().orElseThrow();

        assertThat(withBooking.lastServiceName()).isEqualTo("Test Service");
        assertThat(withoutBooking.lastServiceName()).isNull();
    }

    @Test
    @DisplayName("lastServiceName is scoped to the asking client — another client's booking does not leak")
    void should_notLeakOtherClientsBooking_inLastServiceName() {
        UUID asking = createClient("asking-client@beautica.test");
        UUID other = createClient("other-client@beautica.test");

        UUID master = createIndependentMaster("shared-master@beautica.test");
        UUID ms = createIndependentMasterService(master);
        // Only the OTHER client booked this master.
        createCompletedBooking(other, master, ms);

        favoriteService.addFavorite(asking, FavoriteTargetType.MASTER, master);

        List<FavoriteMasterResponse> masters =
                favoriteService.listMasterFavorites(asking, Pageable.unpaged()).getContent();

        assertThat(masters).hasSize(1);
        assertThat(masters.get(0).lastServiceName())
                .as("the asking client never booked this master")
                .isNull();
    }

    // ── V134: SERVICE target_type (Phase 31.3) ──────────────────────────────────

    @Test
    @DisplayName("V134 chk_favorite_target_type admits SERVICE while still rejecting an unknown value")
    void should_acceptServiceTargetType_when_v134Applied() {
        UUID clientId = createClient("v134-client@beautica.test");

        jdbcTemplate.update(
                "INSERT INTO favorites (client_id, target_type, target_id) VALUES (?, 'SERVICE', ?)",
                clientId, UUID.randomUUID());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM favorites WHERE client_id = ? AND target_type = 'SERVICE'",
                Integer.class, clientId)).isEqualTo(1);

        // The widening is additive — a bogus value is still rejected by the same constraint.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO favorites (client_id, target_type, target_id) VALUES (?, 'PROCEDURE', ?)",
                clientId, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("MASTER and SALON favorites still insert after the V134 widening")
    void should_stillAcceptPriorValues_when_v134Applied() {
        UUID clientId = createClient("v134-prior-client@beautica.test");

        jdbcTemplate.update(
                "INSERT INTO favorites (client_id, target_type, target_id) VALUES (?, 'MASTER', ?)",
                clientId, UUID.randomUUID());
        jdbcTemplate.update(
                "INSERT INTO favorites (client_id, target_type, target_id) VALUES (?, 'SALON', ?)",
                clientId, UUID.randomUUID());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM favorites WHERE client_id = ?", Integer.class, clientId))
                .isEqualTo(2);
    }

    // ── SERVICE favorites: the salon-master asymmetry (locked user decision) ─────

    @Test
    @DisplayName("a SALON-employed master's service IS wish-listable, unlike the master themself")
    void should_allowServiceFavorite_when_masterIsSalonEmployed() {
        UUID clientId = createClient("wish-salon-client@beautica.test");
        UUID salonId = createSalon("wish-salon-owner@beautica.test");
        UUID salonMaster = createSalonMaster(salonId, "wish-salon-master@beautica.test");
        UUID masterServiceId = createSalonMasterService(salonMaster, salonId, true, true);

        FavoriteResponse favorite =
                favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, masterServiceId);

        assertThat(favorite.targetType()).isEqualTo(FavoriteTargetType.SERVICE);

        // The same master as a MASTER target is still rejected — the asymmetry is deliberate.
        assertThatThrownBy(() ->
                favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, salonMaster))
                .isInstanceOf(BusinessException.class);

        List<FavoriteServiceResponse> wishList =
                favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent();

        assertThat(wishList).hasSize(1);
        assertThat(wishList.get(0))
                .extracting(FavoriteServiceResponse::masterServiceId,
                        FavoriteServiceResponse::masterId,
                        FavoriteServiceResponse::serviceName,
                        FavoriteServiceResponse::durationMinutes)
                .containsExactly(masterServiceId, salonMaster, "Test Service", 60);
    }

    // ── deactivation: the row survives, the list hides it ───────────────────────

    @Test
    @DisplayName("deactivating the assignment removes the row from the wish list but keeps the favorite")
    void should_hideButKeepFavorite_when_assignmentDeactivated() {
        UUID clientId = createClient("deact-assignment-client@beautica.test");
        UUID master = createIndependentMaster("deact-assignment-master@beautica.test");
        UUID masterServiceId = createIndependentMasterService(master);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, masterServiceId);

        jdbcTemplate.update("UPDATE master_services SET is_active = false WHERE id = ?", masterServiceId);

        assertThat(favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent())
                .as("a dead CTA must not be offered")
                .isEmpty();
        assertThat(favoriteRepository.existsByClientIdAndTargetTypeAndTargetId(
                clientId, FavoriteTargetType.SERVICE, masterServiceId))
                .as("the favorite row itself survives — no cleanup job, consistent with MASTER/SALON")
                .isTrue();
    }

    @Test
    @DisplayName("soft-deleting the service definition removes the row from the wish list but keeps the favorite")
    void should_hideButKeepFavorite_when_serviceDefinitionSoftDeleted() {
        UUID clientId = createClient("deact-def-client@beautica.test");
        UUID master = createIndependentMaster("deact-def-master@beautica.test");
        UUID masterServiceId = createIndependentMasterService(master);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, masterServiceId);

        // deactivateServiceDefinition soft-deletes the definition and leaves the assignment active.
        jdbcTemplate.update(
                "UPDATE service_definitions SET is_active = false WHERE id = "
                        + "(SELECT service_def_id FROM master_services WHERE id = ?)",
                masterServiceId);

        assertThat(favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent())
                .isEmpty();
        assertThat(favoriteRepository.existsByClientIdAndTargetTypeAndTargetId(
                clientId, FavoriteTargetType.SERVICE, masterServiceId))
                .isTrue();
    }

    @Test
    @DisplayName("deactivating the MASTER removes the row from the wish list but keeps the favorite")
    void should_hideButKeepFavorite_when_masterDeactivated() {
        UUID clientId = createClient("deact-master-client@beautica.test");
        UUID master = createIndependentMaster("deact-master-master@beautica.test");
        UUID masterServiceId = createIndependentMasterService(master);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, masterServiceId);

        // Only the MASTER goes inactive — master_services.is_active and
        // service_definitions.is_active both stay true, so the two pre-existing predicates
        // cannot explain this row disappearing. Removing `AND m.isActive = true` from
        // findFavoriteServiceRows turns this test red and nothing else.
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", master);

        assertThat(favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent())
                .as("a deactivated master must not keep a live «Записатись» that booking would 404")
                .isEmpty();
        assertThat(favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getTotalElements())
                .as("the count query must agree with the content query, or paging metadata lies")
                .isZero();
        assertThat(favoriteRepository.existsByClientIdAndTargetTypeAndTargetId(
                clientId, FavoriteTargetType.SERVICE, masterServiceId))
                .as("the favorite row itself survives — no cleanup job, consistent with MASTER/SALON")
                .isTrue();
    }

    @Test
    @DisplayName("deactivating the master removes them from /favorites/masters but keeps the favorite")
    void should_hideButKeepFavorite_when_favoritedMasterDeactivated() {
        UUID clientId = createClient("deact-fav-master-client@beautica.test");
        UUID master = createIndependentMaster("deact-fav-master-master@beautica.test");
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, master);

        assertThat(favoriteService.listMasterFavorites(clientId, Pageable.ofSize(20)).getContent())
                .as("precondition: an active master IS listed")
                .hasSize(1);

        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", master);

        assertThat(favoriteService.listMasterFavorites(clientId, Pageable.ofSize(20)).getContent())
                .as("pre-existing gap closed by the 2026-08 audit: a deactivated master kept a "
                        + "live card in every client's favourites")
                .isEmpty();
        assertThat(favoriteRepository.existsByClientIdAndTargetTypeAndTargetId(
                clientId, FavoriteTargetType.MASTER, master))
                .as("the favorite row itself survives")
                .isTrue();
    }

    // ── the salon-cascade gap (2026-08 security re-audit MEDIUM) ─────────────────

    @Test
    @DisplayName("deactivating a SALON removes its masters' services from the wish list, even "
            + "though masters.is_active is untouched")
    void should_hideWishListedService_when_owningSalonDeactivated() {
        UUID clientId = createClient("deact-salon-wish-client@beautica.test");
        UUID salonId = createSalon("deact-salon-wish-owner@beautica.test");
        UUID salonMaster = createSalonMaster(salonId, "deact-salon-wish-master@beautica.test");
        UUID masterServiceId = createSalonMasterService(salonMaster, salonId, true, true);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, masterServiceId);

        assertThat(favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent())
                .as("precondition: an active salon's service IS wish-listed")
                .hasSize(1);

        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salonId);

        // The gap this closes: deactivateSalon does NOT cascade, so the master still reads active.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_active FROM masters WHERE id = ?", Boolean.class, salonMaster))
                .as("SalonService.deactivateSalon does not cascade to masters.is_active — if this "
                        + "ever becomes false, the test below stops proving the SALON predicate")
                .isTrue();

        assertThat(favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent())
                .as("a closed salon's «Записатись» must not survive in the wish list — the booking "
                        + "path now 404s it")
                .isEmpty();
        assertThat(favoriteRepository.existsByClientIdAndTargetTypeAndTargetId(
                clientId, FavoriteTargetType.SERVICE, masterServiceId))
                .as("the favorite row itself survives — no cleanup job, consistent with MASTER/SALON")
                .isTrue();
    }

    @Test
    @DisplayName("the wish-list page COUNT drops with the content when the owning salon is "
            + "deactivated — metadata and content must agree")
    void should_dropTotalElements_when_owningSalonDeactivated() {
        UUID clientId = createClient("deact-salon-count-client@beautica.test");
        UUID salonId = createSalon("deact-salon-count-owner@beautica.test");
        UUID salonMaster = createSalonMaster(salonId, "deact-salon-count-master@beautica.test");
        UUID masterServiceId = createSalonMasterService(salonMaster, salonId, true, true);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, masterServiceId);

        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salonId);

        // The predicate lives in BOTH the paged query and the countQuery; a one-sided fix yields a
        // page reporting totalElements=1 with zero rows, which paginates into an empty page 0.
        assertThat(favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20))
                .getTotalElements())
                .isZero();
    }

    @Test
    @DisplayName("an INDEPENDENT_MASTER's service stays wish-listed — the salon predicate's "
            + "IS NULL branch must not drop salon-less masters")
    void should_keepWishListedService_when_masterHasNoSalon() {
        // The regression this guards: writing the predicate as a bare `sal.isActive = true`, or
        // reaching the salon by implicit path (which compiles to an INNER join in Hibernate 6),
        // silently empties the wish list for every independent master on the platform.
        UUID clientId = createClient("no-salon-wish-client@beautica.test");
        UUID master = createIndependentMaster("no-salon-wish-master@beautica.test");
        UUID masterServiceId = createIndependentMasterService(master);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, masterServiceId);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM masters WHERE id = ? AND salon_id IS NULL",
                Integer.class, master))
                .as("precondition: an INDEPENDENT_MASTER genuinely has no salon row")
                .isEqualTo(1);

        var page = favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalElements())
                .as("the count query carries the same disjunction, so it must agree")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("addFavorite rejects a service whose owning SALON is deactivated, before any row "
            + "is written")
    void should_rejectServiceFavorite_when_owningSalonInactive() {
        UUID clientId = createClient("inactive-salon-add-client@beautica.test");
        UUID salonId = createSalon("inactive-salon-add-owner@beautica.test");
        UUID salonMaster = createSalonMaster(salonId, "inactive-salon-add-master@beautica.test");
        UUID masterServiceId = createSalonMasterService(salonMaster, salonId, true, true);
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salonId);

        assertThatThrownBy(() ->
                favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, masterServiceId))
                .isInstanceOf(BusinessException.class);

        assertThat(favoriteRepository.count()).isZero();
    }

    @Test
    @DisplayName("deactivating a salon removes it from /favorites/salons but keeps the favorite")
    void should_hideButKeepFavorite_when_favoritedSalonDeactivated() {
        UUID clientId = createClient("deact-fav-salon-client@beautica.test");
        UUID salonId = createSalon("deact-fav-salon-owner@beautica.test");
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, salonId);

        assertThat(favoriteService.listSalonFavorites(clientId, Pageable.ofSize(20)).getContent())
                .as("precondition: an active salon IS listed")
                .hasSize(1);

        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salonId);

        assertThat(favoriteService.listSalonFavorites(clientId, Pageable.ofSize(20)).getContent())
                .as("salons carry a real is_active soft-delete flag, so the same gap existed here")
                .isEmpty();
        assertThat(favoriteRepository.existsByClientIdAndTargetTypeAndTargetId(
                clientId, FavoriteTargetType.SALON, salonId))
                .as("the favorite row itself survives")
                .isTrue();
    }

    @Test
    @DisplayName("addFavorite rejects a deactivated master with 400 before any row is written")
    void should_rejectMasterFavorite_when_masterAlreadyInactive() {
        UUID clientId = createClient("inactive-master-add-client@beautica.test");
        UUID master = createIndependentMaster("inactive-master-add-master@beautica.test");
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", master);

        assertThatThrownBy(() ->
                favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, master))
                .isInstanceOf(BusinessException.class);

        assertThat(favoriteRepository.count()).isZero();
    }

    @Test
    @DisplayName("addFavorite rejects a service whose master is deactivated, before any row is written")
    void should_rejectServiceFavorite_when_masterAlreadyInactive() {
        UUID clientId = createClient("inactive-svc-master-client@beautica.test");
        UUID master = createIndependentMaster("inactive-svc-master-master@beautica.test");
        UUID masterServiceId = createIndependentMasterService(master);
        // Both service flags stay active — only the master is deactivated.
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", master);

        assertThatThrownBy(() ->
                favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, masterServiceId))
                .isInstanceOf(BusinessException.class);

        assertThat(favoriteRepository.count()).isZero();
    }

    @Test
    @DisplayName("a hard-deleted master_services row silently disappears from the wish list (INNER JOIN)")
    void should_dropStaleRow_when_masterServiceHardDeleted() {
        UUID clientId = createClient("stale-client@beautica.test");
        UUID master = createIndependentMaster("stale-master@beautica.test");
        UUID masterServiceId = createIndependentMasterService(master);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, masterServiceId);

        jdbcTemplate.update("DELETE FROM master_services WHERE id = ?", masterServiceId);

        assertThat(favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent())
                .isEmpty();
    }

    @Test
    @DisplayName("the wish list is ordered newest-favorite-first, matching /favorites/masters")
    void should_orderNewestFavoriteFirst_when_listingServiceFavorites() {
        UUID clientId = createClient("order-client@beautica.test");
        UUID master = createIndependentMaster("order-master@beautica.test");
        UUID first = createIndependentMasterService(master);
        UUID second = createIndependentMasterService(master);

        favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, first);
        // created_at is DB-assigned; pin an older timestamp on the first row so the ordering
        // assertion cannot pass by insertion accident on a coarse clock.
        jdbcTemplate.update(
                "UPDATE favorites SET created_at = created_at - interval '1 hour' WHERE target_id = ?",
                first);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, second);

        List<FavoriteServiceResponse> wishList =
                favoriteService.listServiceFavorites(clientId, Pageable.ofSize(20)).getContent();

        assertThat(wishList).extracting(FavoriteServiceResponse::masterServiceId)
                .containsExactly(second, first);
    }

    @Test
    @DisplayName("the wish list is scoped to the asking client — another client's favorites do not leak")
    void should_notLeakOtherClientsWishList() {
        UUID asking = createClient("asking-wish-client@beautica.test");
        UUID other = createClient("other-wish-client@beautica.test");
        UUID master = createIndependentMaster("shared-wish-master@beautica.test");
        UUID masterServiceId = createIndependentMasterService(master);

        favoriteService.addFavorite(other, FavoriteTargetType.SERVICE, masterServiceId);

        assertThat(favoriteService.listServiceFavorites(asking, Pageable.ofSize(20)).getContent())
                .isEmpty();
    }

    @Test
    @DisplayName("addFavorite rejects an already-inactive service with 400 before any row is written")
    void should_rejectServiceFavorite_when_assignmentAlreadyInactive() {
        UUID clientId = createClient("inactive-add-client@beautica.test");
        UUID master = createIndependentMaster("inactive-add-master@beautica.test");
        UUID masterServiceId = createIndependentMasterService(master);
        jdbcTemplate.update("UPDATE master_services SET is_active = false WHERE id = ?", masterServiceId);

        assertThatThrownBy(() ->
                favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, masterServiceId))
                .isInstanceOf(BusinessException.class);

        assertThat(favoriteRepository.count()).isZero();
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

    private UUID createIndependentMaster(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'INDEPENDENT_MASTER', true, true)",
                userId, email);
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, avg_rating, review_count, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', 0.00, 0, true, NOW(), NOW())",
                masterId, userId);
        return masterId;
    }

    private UUID createIndependentMasterService(UUID masterId) {
        UUID userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions "
                        + "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, "
                        + "buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId, resolveUnusedServiceTypeId("INDEPENDENT_MASTER", userId));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    /**
     * A salon-employed master ({@code users.role = SALON_MASTER}, {@code masters.salon_id} set)
     * — the target the MASTER favorite rule rejects and the SERVICE favorite rule allows.
     */
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

    /**
     * A salon-owned service definition assigned to a salon master. {@code owner_type = 'SALON'}
     * with the salon as owner, mirroring how the catalogue actually stores salon services.
     */
    private UUID createSalonMasterService(UUID masterId, UUID salonId,
                                          boolean assignmentActive, boolean definitionActive) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions "
                        + "(id, owner_type, owner_id, name, service_type_id, base_duration_minutes, base_price, "
                        + "buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, ?, NOW(), NOW())",
                serviceDefId, salonId, resolveUnusedServiceTypeId("SALON", salonId), definitionActive);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId, assignmentActive);
        return masterServiceId;
    }

    private void createCompletedBooking(UUID clientId, UUID masterId, UUID masterServiceId) {
        jdbcTemplate.update(
                "INSERT INTO bookings "
                        + "(id, client_id, master_id, master_service_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 'COMPLETED', "
                        + "NOW() - interval '2 hours', NOW() - interval '1 hour', "
                        + "500.00, 60, 0, NOW(), NOW())",
                UUID.randomUUID(), clientId, masterId, masterServiceId);
    }
}
