package com.beautica.favorite;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.BusinessException;
import com.beautica.favorite.dto.FavoriteCategoryView;
import com.beautica.favorite.dto.FavoriteMasterResponse;
import com.beautica.favorite.dto.FavoriteResponse;
import com.beautica.favorite.dto.FavoriteSalonResponse;
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
import static org.assertj.core.api.Assertions.tuple;

/**
 * Testcontainers integration test for the favorites persistence contract — exercises the
 * {@code V92} schema (CHECK + UNIQUE), the {@code V134} widening that admits
 * {@code SERVICE} (Phase 31.3), the two native read projections and the JPA wish-list
 * page (Phase 31.4) against a real PostgreSQL, plus the service-layer idempotency and
 * the mobile-Phase-111 address projection. ASCII-only seed data.
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

    // ── Phase 111: address projection + every MasterType is favouritable ─────────
    //
    // These two tests REPLACE should_resolveLastServiceName_perClient and
    // should_notLeakOtherClientsBooking_inLastServiceName. Both pinned the LATERAL
    // "latest booking for this (client, master) pair" subquery that fed lastServiceName;
    // mobile Phase 111 removed the field, the LATERAL and the only client-scoped term in
    // the projection, so there is no behaviour left for them to assert.

    @Test
    @DisplayName("listMasterFavorites projects an INDEPENDENT_MASTER's OWN street address off users")
    void should_projectMasterOwnAddress_when_listingMasterFavorites() {
        UUID clientId = createClient("addr-client@beautica.test");
        UUID master = createIndependentMaster("addr-independent-master@beautica.test");
        jdbcTemplate.update(
                "UPDATE users SET street = 'Master Street', building_no = '12B', "
                        + "location_note = 'master note' WHERE id = "
                        + "(SELECT user_id FROM masters WHERE id = ?)", master);

        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, master);

        List<FavoriteMasterResponse> masters =
                favoriteService.listMasterFavorites(clientId, Pageable.unpaged()).getContent();

        assertThat(masters).hasSize(1);
        // An INDEPENDENT_MASTER's address IS the discoverable location clients need — this is the
        // one MasterType the locked per-role address matrix lets through, and the values come from
        // `users`, never `salons`.
        assertThat(masters.get(0))
                .extracting(FavoriteMasterResponse::street, FavoriteMasterResponse::buildingNo,
                        FavoriteMasterResponse::locationNote)
                .containsExactly("Master Street", "12B", "master note");
        assertThat(masters.get(0))
                .as("an independent master has no employing salon, so the affiliation pair the "
                        + "card's «works at …» line keys off must be absent")
                .extracting(FavoriteMasterResponse::salonId, FavoriteMasterResponse::salonName)
                .containsOnlyNulls();
    }

    /**
     * The locked per-role address matrix, enforced END TO END against real SQL.
     * {@code FavoriteServiceTest} pins the branch against a hand-built projection row; only this
     * test proves the {@code masters.master_type} column the rule reads — and the five
     * {@code salons} columns it switches to — are actually SELECTed, and at the indices the
     * service reads them from.
     *
     * <p>Both address sources are populated with DISTINCT values so the assertion cannot be
     * satisfied accidentally: 'Salon Street' is what the card must now show, 'Master Street' is
     * what the pre-masking code returned and what a wrong-column read would return today, and
     * {@code null} is what the over-corrected intermediate version returned. All three are
     * distinguishable, so every wrong answer fails.
     */
    @Test
    @DisplayName("listMasterFavorites publishes the SALON's address and identity for a "
            + "SALON_MASTER — never the employee's own users row")
    void should_publishSalonAddress_when_masterIsSalonAffiliated() {
        UUID clientId = createClient("addr-mask-client@beautica.test");
        UUID salonId = createSalon("addr-mask-owner@beautica.test");
        jdbcTemplate.update(
                "UPDATE salons SET street = 'Salon Street', building_no = '99', "
                        + "location_note = 'salon note' WHERE id = ?", salonId);

        UUID salonMaster = createSalonMaster(salonId, "addr-mask-salon-master@beautica.test");
        jdbcTemplate.update(
                "UPDATE users SET street = 'Master Street', building_no = '12B', "
                        + "location_note = 'master note' WHERE id = "
                        + "(SELECT user_id FROM masters WHERE id = ?)", salonMaster);

        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, salonMaster);

        List<FavoriteMasterResponse> masters =
                favoriteService.listMasterFavorites(clientId, Pageable.unpaged()).getContent();

        assertThat(masters).hasSize(1);
        assertThat(masters.get(0))
                .as("the employing salon's business address — public data already returned "
                        + "unmasked by listSalonFavorites and by the public salon profile")
                .extracting(FavoriteMasterResponse::street, FavoriteMasterResponse::buildingNo,
                        FavoriteMasterResponse::locationNote)
                .containsExactly("Salon Street", "99", "salon note");
        assertThat(masters.get(0))
                .as("the affiliation line: the client navigates on salonId, never by parsing a name")
                .extracting(FavoriteMasterResponse::salonId, FavoriteMasterResponse::salonName)
                .containsExactly(salonId, "Test Salon");
    }

    @Test
    @DisplayName("listSalonFavorites projects the salon's street address")
    void should_projectSalonAddress_when_listingSalonFavorites() {
        UUID clientId = createClient("salon-addr-client@beautica.test");
        UUID salonId = createSalon("salon-addr-owner@beautica.test");
        jdbcTemplate.update(
                "UPDATE salons SET street = 'Derybasivska', building_no = '7', "
                        + "location_note = '2nd floor' WHERE id = ?", salonId);

        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, salonId);

        var salons = favoriteService.listSalonFavorites(clientId, Pageable.unpaged()).getContent();

        assertThat(salons).hasSize(1);
        assertThat(salons.get(0))
                .extracting(FavoriteSalonResponse::street, FavoriteSalonResponse::buildingNo,
                        FavoriteSalonResponse::locationNote)
                .containsExactly("Derybasivska", "7", "2nd floor");
    }

    /**
     * The end-to-end form of the inverted rule: a salon-employed master survives BOTH the write
     * guard and the read projection. The read query was already role-agnostic, but nothing could
     * previously prove it — no such favourite row could exist to read back.
     */
    @Test
    @DisplayName("a SALON_MASTER favourite round-trips through write validation AND the read projection")
    void should_roundTripSalonMasterFavorite_when_favoritingSalonEmployedMaster() {
        UUID clientId = createClient("roundtrip-client@beautica.test");
        UUID salonId = createSalon("roundtrip-owner@beautica.test");
        UUID salonMaster = createSalonMaster(salonId, "roundtrip-master@beautica.test");

        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, salonMaster);

        List<FavoriteMasterResponse> masters =
                favoriteService.listMasterFavorites(clientId, Pageable.unpaged()).getContent();

        assertThat(masters).extracting(FavoriteMasterResponse::masterId)
                .containsExactly(salonMaster);
    }

    /**
     * The predicate that mobile Phase 111 promoted from a documented no-op to a live one. Before
     * the role predicate was removed, no salon-affiliated master could be favourited, so
     * {@code (m.salon_id IS NULL OR sal.is_active = true)} was unreachable. It now carries real
     * weight: a closed salon's staff must not linger in a client's favourites.
     */
    @Test
    @DisplayName("deactivating the salon hides its master from favourites but keeps the favourite row")
    void should_hideSalonMasterFavorite_when_owningSalonDeactivated() {
        UUID clientId = createClient("closed-salon-client@beautica.test");
        UUID salonId = createSalon("closed-salon-owner@beautica.test");
        UUID salonMaster = createSalonMaster(salonId, "closed-salon-master@beautica.test");
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, salonMaster);

        // Note: deactivating the salon does NOT cascade to masters.is_active — that is precisely
        // why the salon term in the WHERE clause is needed alongside the master term.
        jdbcTemplate.update("UPDATE salons SET is_active = false WHERE id = ?", salonId);

        assertThat(favoriteService.listMasterFavorites(clientId, Pageable.unpaged()).getContent())
                .as("a closed salon's master must drop out of the list")
                .isEmpty();
        assertThat(favoriteRepository.existsByClientIdAndTargetTypeAndTargetId(
                clientId, FavoriteTargetType.MASTER, salonMaster))
                .as("the favourite row itself survives — no cleanup job, filtering is on read")
                .isTrue();
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

    // ── V136: SALON_SERVICE target_type (salon-service-favourites track) ────────

    @Test
    @DisplayName("V136 chk_favorite_target_type admits SALON_SERVICE while still rejecting an unknown value")
    void should_acceptSalonServiceTargetType_when_v136Applied() {
        UUID clientId = createClient("v136-client@beautica.test");

        jdbcTemplate.update(
                "INSERT INTO favorites (client_id, target_type, target_id) VALUES (?, 'SALON_SERVICE', ?)",
                clientId, UUID.randomUUID());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM favorites WHERE client_id = ? AND target_type = 'SALON_SERVICE'",
                Integer.class, clientId)).isEqualTo(1);

        // The widening is additive — a bogus value is still rejected by the same constraint.
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO favorites (client_id, target_type, target_id) VALUES (?, 'CATALOGUE_ITEM', ?)",
                clientId, UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    @DisplayName("MASTER, SALON and SERVICE favorites still insert after the V136 widening")
    void should_stillAcceptPriorValues_when_v136Applied() {
        UUID clientId = createClient("v136-prior-client@beautica.test");

        jdbcTemplate.update(
                "INSERT INTO favorites (client_id, target_type, target_id) VALUES (?, 'MASTER', ?)",
                clientId, UUID.randomUUID());
        jdbcTemplate.update(
                "INSERT INTO favorites (client_id, target_type, target_id) VALUES (?, 'SALON', ?)",
                clientId, UUID.randomUUID());
        jdbcTemplate.update(
                "INSERT INTO favorites (client_id, target_type, target_id) VALUES (?, 'SERVICE', ?)",
                clientId, UUID.randomUUID());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM favorites WHERE client_id = ?", Integer.class, clientId))
                .isEqualTo(3);
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

        // INVERTED by mobile Phase 111. The same master as a MASTER target used to be rejected
        // with a 400 — that asymmetry is GONE, both arms now admit a salon-employed master.
        // Asserted here rather than deleted so the reversal is visible at the exact site that
        // documented the old rule.
        assertThat(favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, salonMaster)
                .targetType())
                .isEqualTo(FavoriteTargetType.MASTER);

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

    // ── category axis: the batched OFFERED-categories derivation ─────────────────
    //
    // Reversed product decision: the axis used to be the single category of the client's most
    // recently BOOKED service with a provider. It is now every distinct platform category a
    // provider actually OFFERS (an ACTIVE service in an ACTIVE assignment) — not client-scoped
    // at all. See FavoriteCategoryResolver's class javadoc for the full rationale.

    /**
     * The core positive case, against real SQL: a master performing active services in TWO
     * distinct categories publishes BOTH, ordered by display label — not the last one created,
     * not an arbitrary DB order.
     */
    @Test
    @DisplayName("the master's categories are every distinct platform category an active service offers")
    void should_listEveryOfferedCategory_when_masterHasSeveralActiveServices() {
        UUID clientId = createClient("cat-offer-client@beautica.test");
        UUID masterId = createIndependentMaster("cat-offer-master@beautica.test");
        UUID nails = createIndependentMasterService(masterId);
        UUID lashes = createIndependentMasterService(masterId);
        setCategory(nails, "NAIL_SERVICE");
        setCategory(lashes, "LASH_EXTENSIONS");
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, masterId);

        List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categories())
                .extracting(FavoriteCategoryView::code, FavoriteCategoryView::label)
                .as("both offered categories appear, ordered by Ukrainian display label "
                        + "(\"Нарощення вій\" before \"Нігтьовий сервіс\"), not creation order")
                .containsExactly(
                        tuple("LASH_EXTENSIONS", "Нарощення вій"),
                        tuple("NAIL_SERVICE", "Нігтьовий сервіс"));
    }

    /**
     * A master performing the SAME category through two different services publishes ONE chip,
     * not two — {@code DISTINCT} in the repository query, proven against real Postgres rather
     * than assumed from the JPQL text.
     */
    @Test
    @DisplayName("two active services in the SAME category collapse to one chip, not two")
    void should_deduplicateCategory_when_twoServicesShareOneCategory() {
        UUID clientId = createClient("cat-dedupe-client@beautica.test");
        UUID masterId = createIndependentMaster("cat-dedupe-master@beautica.test");
        UUID first = createIndependentMasterService(masterId);
        UUID second = createIndependentMasterService(masterId);
        setCategory(first, "NAIL_SERVICE");
        setCategory(second, "NAIL_SERVICE");
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, masterId);

        List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

        assertThat(result.get(0).categories())
                .extracting(FavoriteCategoryView::code)
                .containsExactly("NAIL_SERVICE");
    }

    /**
     * The empty case the list is nullable-FOR under the old contract but is now simply empty —
     * never {@code null} — for a provider with no active categorisable service at all.
     */
    @Test
    @DisplayName("categories is an EMPTY list, never null, when the master has no active service")
    void should_returnEmptyCategories_when_masterHasNoActiveService() {
        UUID clientId = createClient("cat-none-client@beautica.test");
        UUID masterId = createIndependentMaster("cat-none-master@beautica.test");
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, masterId);

        List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categories()).isNotNull().isEmpty();
    }

    /**
     * Only ACTIVE assignments count. A master whose only categorised service is wired through a
     * DEACTIVATED {@code master_services} row must not surface that category — the master
     * "offers" only what is currently bookable, mirroring every other bookable-only predicate in
     * this codebase.
     */
    @Test
    @DisplayName("a category behind a DEACTIVATED assignment is excluded, its active sibling is not")
    void should_excludeCategory_when_assignmentIsInactive() {
        UUID clientId = createClient("cat-inactive-assign-client@beautica.test");
        UUID masterId = createIndependentMaster("cat-inactive-assign-master@beautica.test");
        UUID inactiveOne = createIndependentMasterService(masterId);
        UUID activeOne = createIndependentMasterService(masterId);
        setCategory(inactiveOne, "HAIRDRESSING");
        setCategory(activeOne, "NAIL_SERVICE");
        jdbcTemplate.update("UPDATE master_services SET is_active = false WHERE id = ?", inactiveOne);
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, masterId);

        List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

        assertThat(result.get(0).categories())
                .extracting(FavoriteCategoryView::code)
                .as("the deactivated assignment's HAIRDRESSING must not appear; the active "
                        + "NAIL_SERVICE assignment still does")
                .containsExactly("NAIL_SERVICE");
    }

    /**
     * Only ACTIVE service definitions count, independent of the assignment's own flag —
     * {@code ServiceCatalogService#deactivateServiceDefinition} soft-deletes the definition
     * without touching the assignment row, so this combination is a real reachable state, not a
     * synthetic one (mirrors the identical rule already pinned on the SERVICE favourite-target
     * validation).
     */
    @Test
    @DisplayName("a category behind a soft-deleted service DEFINITION is excluded, even with an active assignment")
    void should_excludeCategory_when_definitionIsInactive() {
        UUID clientId = createClient("cat-inactive-def-client@beautica.test");
        UUID masterId = createIndependentMaster("cat-inactive-def-master@beautica.test");
        UUID deleted = createIndependentMasterService(masterId);
        UUID live = createIndependentMasterService(masterId);
        setCategory(deleted, "HAIRDRESSING");
        setCategory(live, "NAIL_SERVICE");
        jdbcTemplate.update(
                "UPDATE service_definitions SET is_active = false WHERE id = "
                        + "(SELECT service_def_id FROM master_services WHERE id = ?)",
                deleted);
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, masterId);

        List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

        assertThat(result.get(0).categories())
                .extracting(FavoriteCategoryView::code)
                .containsExactly("NAIL_SERVICE");
    }

    /**
     * The salon arm, against the LOCKED "salon offering = master-performed only" domain rule: a
     * category surfaces for a salon when at least one of its currently active masters performs
     * an active, salon-owned service in it — contributed by TWO DIFFERENT masters of the same
     * salon here, so the derivation is proven to aggregate across the whole roster, not just one
     * master's menu.
     */
    @Test
    @DisplayName("the salon's categories are every distinct category an active master of it performs")
    void should_deriveSalonCategories_when_differentActiveMastersPerformDifferentCategories() {
        UUID clientId = createClient("cat-salon-offer-client@beautica.test");
        UUID salonId = createSalon("cat-salon-offer-owner@beautica.test");
        UUID nailMaster = createSalonMaster(salonId, "cat-salon-offer-m1@beautica.test");
        UUID lashMaster = createSalonMaster(salonId, "cat-salon-offer-m2@beautica.test");
        UUID nailService = createSalonMasterService(nailMaster, salonId, true, true);
        UUID lashService = createSalonMasterService(lashMaster, salonId, true, true);
        setCategory(nailService, "NAIL_SERVICE");
        setCategory(lashService, "LASH_EXTENSIONS");
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, salonId);

        List<FavoriteSalonResponse> result = favoriteService.listSalonFavorites(clientId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categories())
                .extracting(FavoriteCategoryView::code, FavoriteCategoryView::label)
                .containsExactly(
                        tuple("LASH_EXTENSIONS", "Нарощення вій"),
                        tuple("NAIL_SERVICE", "Нігтьовий сервіс"));
    }

    /**
     * The "master-performed only" rule's core assertion: a salon-owned, ACTIVE service with NO
     * assignment at all (nobody currently performs it) contributes NO chip, exactly as it is
     * absent from the public catalogue.
     */
    @Test
    @DisplayName("a salon-owned service nobody currently performs contributes no category")
    void should_excludeSalonCategory_when_noMasterPerformsTheService() {
        UUID clientId = createClient("cat-salon-unperformed-client@beautica.test");
        UUID salonId = createSalon("cat-salon-unperformed-owner@beautica.test");
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, category, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Unperformed Service', ?, 60, 500.00, 0, "
                        + "'NAIL_SERVICE', true, NOW(), NOW())",
                UUID.randomUUID(), salonId, resolveUnusedServiceTypeId("SALON", salonId));
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, salonId);

        List<FavoriteSalonResponse> result = favoriteService.listSalonFavorites(clientId);

        assertThat(result.get(0).categories()).isEmpty();
    }

    /**
     * The master-performed-only rule's other half: a DEACTIVATED master's still-active
     * assignment and still-active service definition must not contribute a chip.
     * {@code SalonService.deactivateSalon} style gaps aside, an inactive master is simply not
     * "currently performing" anything.
     */
    @Test
    @DisplayName("a category performed only by a DEACTIVATED master is excluded from the salon's chips")
    void should_excludeSalonCategory_when_performingMasterIsInactive() {
        UUID clientId = createClient("cat-salon-inactive-master-client@beautica.test");
        UUID salonId = createSalon("cat-salon-inactive-master-owner@beautica.test");
        UUID master = createSalonMaster(salonId, "cat-salon-inactive-master-m@beautica.test");
        UUID service = createSalonMasterService(master, salonId, true, true);
        setCategory(service, "NAIL_SERVICE");
        jdbcTemplate.update("UPDATE masters SET is_active = false WHERE id = ?", master);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, salonId);

        List<FavoriteSalonResponse> result = favoriteService.listSalonFavorites(clientId);

        assertThat(result.get(0).categories())
                .as("the master left, so the salon no longer offers NAIL_SERVICE through them")
                .isEmpty();
    }

    /**
     * Cross-salon leak guard — the mirror of what
     * {@code MasterServiceRepository#findBookableAssignmentsBySalon} already protects for the
     * catalogue. A master of salon A is (incorrectly, but this is exactly the state the
     * predicate must defend against) assigned to a service DEFINITION owned by salon B. Neither
     * salon may show the resulting category: salon A because the service it lists is not its
     * own, salon B because none of ITS active masters performs it.
     */
    @Test
    @DisplayName("an assignment pointing at a DIFFERENT salon's service leaks its category to NEITHER salon")
    void should_notLeakCategory_when_assignmentCrossesSalons() {
        UUID clientId = createClient("cat-cross-salon-client@beautica.test");
        UUID salonA = createSalon("cat-cross-salon-a@beautica.test");
        UUID salonB = createSalon("cat-cross-salon-b@beautica.test");
        UUID masterOfA = createSalonMaster(salonA, "cat-cross-salon-master@beautica.test");
        // Service DEFINITION owned by salon B, but the only assignment performing it belongs to
        // a master of salon A — the exact cross-salon shape the predicate must reject.
        UUID crossedService = createSalonMasterService(masterOfA, salonB, true, true);
        setCategory(crossedService, "NAIL_SERVICE");
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, salonA);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, salonB);

        List<FavoriteSalonResponse> result = favoriteService.listSalonFavorites(clientId);

        assertThat(result)
                .as("salon A: the performing master is theirs, but the service isn't. Salon B: "
                        + "the service is theirs, but no active master of theirs performs it")
                .extracting(FavoriteSalonResponse::salonId, r -> r.categories().isEmpty())
                .containsExactlyInAnyOrder(tuple(salonA, true), tuple(salonB, true));
    }

    /**
     * <b>The both-or-neither rule, end to end against the REAL category vocabulary.</b>
     *
     * <p>{@code service_definitions.category} is a denormalised {@code platform_categories.name}
     * with NO foreign key (V64), and V74 renamed three of V64's seven original seeds IN PLACE —
     * {@code MANICURE -> NAIL_SERVICE}, {@code HAIRCUT -> HAIRDRESSING},
     * {@code EYELASH -> LASH_EXTENSIONS}. Any {@code service_definitions} row still carrying a
     * pre-V74 slug therefore holds a code that resolves to no label at all — reachable because
     * the column has no FK to enforce currency.
     *
     * <p><b>Why the assertion cannot be defanged.</b> {@code MANICURE} is a real string the
     * database will happily store and the derivation will happily return; the entry is dropped
     * by {@code FavoriteCategoryResolver} alone. Emitting the code with a {@code null} label —
     * the obvious "simplification" — would hand the client a chip identity it cannot draw. The
     * companion service carries a CURRENT slug so the suppression is visibly per-CATEGORY, not
     * per-provider: a resolver that bailed out on one unresolvable code must not blank the whole
     * master's list.
     */
    @Test
    @DisplayName("a stale pre-V74 category code is dropped from the list; the master's other, current category survives")
    void should_dropStaleCategory_when_codeIsNoLongerSelectable() {
        UUID clientId = createClient("cat-stale-client@beautica.test");
        UUID masterId = createIndependentMaster("cat-stale-master@beautica.test");
        UUID staleOne = createIndependentMasterService(masterId);
        UUID liveOne = createIndependentMasterService(masterId);
        // V64's original slug for what V74 renamed to NAIL_SERVICE. No platform_categories row
        // answers to it any more, but the column has no FK so it persists happily.
        setCategory(staleOne, "MANICURE");
        setCategory(liveOne, "HAIRDRESSING");
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, masterId);

        List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

        assertThat(result.get(0).categories())
                .as("MANICURE resolves to no label and is dropped; HAIRDRESSING survives")
                .extracting(FavoriteCategoryView::code)
                .containsExactly("HAIRDRESSING");
    }


    /**
     * <b>The {@code SALON_OWNER} arm of the NEW salon-address behaviour, end to end.</b>
     *
     * <p>{@code MasterType.SALON_OWNER} takes the same non-independent branch as
     * {@code SALON_MASTER}, and {@code FavoriteServiceTest} covers it against a hand-built
     * projection row — but every Testcontainers fixture for the address rule used
     * {@code SALON_MASTER}, so no test proved a real {@code masters.master_type = 'SALON_OWNER'}
     * value survives the round trip through {@code MasterType#fromProjection} at index 10 and
     * lands on the salon branch. An owner working as a master is a first-class provider on this
     * screen and is the case the original defect was reported against.
     *
     * <p><b>The fixture reproduces the real bug, not an abstraction of it.</b> A multi-salon
     * owner's {@code users} row carries the MOST RECENTLY CREATED salon's address — that is WHY
     * the pre-fix code shipped the wrong street. So the owner here genuinely owns two salons,
     * works as a master at the FIRST, and has the SECOND's street stamped on their {@code users}
     * row exactly as production would leave it. Three distinct streets, three distinguishable
     * wrong answers: reading {@code users} emits the other salon's street, dropping the branch
     * emits {@code null}, and crossing to the wrong salon row emits the second salon's.
     */
    @Test
    @DisplayName("listMasterFavorites publishes the EMPLOYING salon's address for a SALON_OWNER "
            + "working as a master — never the other salon's street off their users row")
    void should_publishSalonAddress_when_masterIsSalonOwner() {
        UUID clientId = createClient("owner-addr-client@beautica.test");
        UUID employingSalon = createSalon("owner-addr-owner@beautica.test");
        jdbcTemplate.update(
                "UPDATE salons SET name = 'Employing Salon', street = 'Employing Street', "
                        + "building_no = '1', location_note = 'employing note' WHERE id = ?",
                employingSalon);
        UUID ownerUserId = jdbcTemplate.queryForObject(
                "SELECT owner_id FROM salons WHERE id = ?", UUID.class, employingSalon);
        // The SECOND salon, created later by the same owner — the one whose address production
        // leaves sitting on users.street, and the wrong answer the pre-fix code returned.
        createSecondSalonFor(ownerUserId, "Other Salon");
        jdbcTemplate.update(
                "UPDATE users SET street = 'Other Salon Street', building_no = '99', "
                        + "location_note = 'other note' WHERE id = ?", ownerUserId);

        UUID ownerMaster = createOwnerAsMaster(employingSalon, ownerUserId);
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, ownerMaster);

        List<FavoriteMasterResponse> masters =
                favoriteService.listMasterFavorites(clientId, Pageable.unpaged()).getContent();

        assertThat(masters).hasSize(1);
        assertThat(masters.get(0))
                .as("SALON_OWNER resolves through the EMPLOYING salon exactly as SALON_MASTER "
                        + "does — one predicate, not two — so the card sends the client to the "
                        + "salon this owner actually works at")
                .extracting(FavoriteMasterResponse::street, FavoriteMasterResponse::buildingNo,
                        FavoriteMasterResponse::locationNote,
                        FavoriteMasterResponse::salonId, FavoriteMasterResponse::salonName)
                .containsExactly("Employing Street", "1", "employing note",
                        employingSalon, "Employing Salon");
        assertThat(masters.get(0).street())
                .as("the reported defect verbatim: users.street holds the LAST-created salon's "
                        + "address, so echoing it sends the client to the wrong building")
                .isNotEqualTo("Other Salon Street");
    }

    // ── seed helpers (ASCII data) ────────────────────────────────────────────────

    /**
     * Stamps {@code service_definitions.category} on the definition behind a
     * {@code master_services} assignment. The column is a denormalised
     * {@code platform_categories.name} with no FK (V64), which is exactly why the value written
     * here has to be a real seeded category name for the label to resolve.
     *
     * <p><b>Use the CURRENT taxonomy, not V64's original seven seeds.</b> V74 renamed those in
     * place — {@code MANICURE -> NAIL_SERVICE}, {@code HAIRCUT -> HAIRDRESSING},
     * {@code EYELASH -> LASH_EXTENSIONS} — so writing a V64 name here produces a code that
     * resolves to no label, and {@code FavoriteCategoryResolver}'s both-or-neither rule then
     * (correctly) blanks the whole pair. A fixture written against the old names fails as a
     * silent {@code null}, which reads like a broken derivation rather than a stale fixture.
     */
    private void setCategory(UUID masterServiceId, String categoryName) {
        jdbcTemplate.update(
                "UPDATE service_definitions SET category = ? WHERE id = "
                        + "(SELECT service_def_id FROM master_services WHERE id = ?)",
                categoryName, masterServiceId);
    }

    /**
     * Clears {@code service_definitions.category} — the column is a plain nullable
     * {@code VARCHAR(100)} (V6), so an uncategorised service is a legitimate persisted state,
     * not a broken row. Written as literal SQL rather than {@code setCategory(id, null)} because
     * an untyped JDBC null on a {@code VARCHAR} assignment is a driver-dependent coin flip.
     */
    private void clearCategory(UUID masterServiceId) {
        jdbcTemplate.update(
                "UPDATE service_definitions SET category = NULL WHERE id = "
                        + "(SELECT service_def_id FROM master_services WHERE id = ?)",
                masterServiceId);
    }

    /**
     * A second salon under an EXISTING owner, so a multi-salon owner can be built. {@code
     * is_primary} defaults to {@code false} (V56) and is left alone, so the partial unique index
     * {@code idx_salons_owner_primary} is not tripped by a second row.
     */
    private UUID createSecondSalonFor(UUID ownerUserId, String name) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, street, building_no, location_note, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'Other Salon Street', '99', 'other note', true, NOW(), NOW())",
                salonId, ownerUserId, name);
        return salonId;
    }

    /**
     * A {@code SALON_OWNER} who also works as a master — {@code masters.master_type =
     * 'SALON_OWNER'} with {@code salon_id} set, hung off the salon's OWN owner user rather than
     * a fresh one, which is what makes the multi-salon {@code users.street} case reproducible.
     * The complement of {@link #createSalonMaster(UUID, String)}, which builds an invited
     * employee.
     */
    private UUID createOwnerAsMaster(UUID salonId, UUID ownerUserId) {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', 0.00, 0, true, NOW(), NOW())",
                masterId, ownerUserId, salonId);
        return masterId;
    }

    /**
     * A past {@code COMPLETED} booking {@code daysAgo} days back.
     *
     * <p>{@code COMPLETED} rather than {@code CONFIRMED} so several bookings for one master can
     * coexist without tripping the {@code no_overlapping_bookings} GiST exclusion constraint,
     * which applies only to {@code CONFIRMED} rows. The derivation carries no status predicate
     * — booked history is booked history — so the choice does not affect what is under test.
     *
     * @param salonId stamped on the booking for the salon arm; {@code null} for an independent
     *                master, mirroring what the booking paths actually write
     */
    private void createBooking(UUID clientId, UUID masterId, UUID masterServiceId,
                               UUID salonId, int daysAgo) {
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, created_at, updated_at, booking_source) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', NOW() - (? || ' days')::interval, "
                        + "NOW() - (? || ' days')::interval + interval '1 hour', 500.00, 60, 0, "
                        + "NOW(), NOW(), 'APP')",
                UUID.randomUUID(), clientId, masterId, masterServiceId, salonId, daysAgo, daysAgo);
    }

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

}
