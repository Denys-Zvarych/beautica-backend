package com.beautica.favorite;

import com.beautica.AbstractIntegrationTest;
import com.beautica.common.exception.BusinessException;
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

    // ── category axis: the batched last-booked-service derivation ────────────────

    /**
     * The core rule, against real SQL: of several bookings with one master, the category comes
     * from the MOST RECENT one.
     *
     * <p><b>The fixture is built so a wrong answer is a DIFFERENT string, not an absence.</b>
     * The master performs two services in two different platform categories and the client has
     * booked both, so every plausible bug produces a visibly wrong value rather than a null a
     * lenient assertion could wave through:
     * <ul>
     *   <li>{@code ORDER BY starts_at ASC} (a flipped sort) → {@code HAIRDRESSING}</li>
     *   <li>dropping the {@code LIMIT 1} / picking an arbitrary row → non-deterministic between
     *       the two, so the test flaps rather than passing</li>
     *   <li>reading {@code service_types.platform_category_name} instead of
     *       {@code service_definitions.category} → whatever the seeded type carries, not
     *       {@code NAIL_SERVICE}</li>
     * </ul>
     * The label is asserted alongside the code because they resolve through different
     * mechanisms — the code off {@code service_definitions}, the label off the cached
     * {@code platform_categories} list — so a card can never show one without the other.
     */
    @Test
    @DisplayName("the master category comes from the client's MOST RECENT booking, not their first")
    void should_deriveMasterCategoryFromLatestBooking_when_clientBookedSeveralServices() {
        UUID clientId = createClient("cat-latest-client@beautica.test");
        UUID masterId = createIndependentMaster("cat-latest-master@beautica.test");
        UUID oldService = createIndependentMasterService(masterId);
        UUID newService = createIndependentMasterService(masterId);
        setCategory(oldService, "HAIRDRESSING");
        setCategory(newService, "NAIL_SERVICE");
        createBooking(clientId, masterId, oldService, null, 30);
        createBooking(clientId, masterId, newService, null, 1);
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, masterId);

        List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryCode())
                .as("yesterday's nail appointment, not last month's haircut")
                .isEqualTo("NAIL_SERVICE");
        assertThat(result.get(0).categoryLabel()).isEqualTo("Нігтьовий сервіс");
    }

    /**
     * §E-4 against real SQL. The derivation reads {@code bookings}, a table with rows for every
     * client, and the repository finder is unscoped by itself — the {@code client_id} term in
     * the {@code LATERAL} is the only thing keeping one client's history out of another's card.
     *
     * <p>The other client's booking is deliberately the MOST RECENT row for this master and
     * carries a DIFFERENT category, so dropping the {@code client_id} predicate does not merely
     * widen the result — it actively overwrites the asking client's answer with a stranger's.
     * A fixture where both clients booked the same category, or where the other client's
     * booking was older, would pass with the predicate removed.
     */
    @Test
    @DisplayName("the master category ignores OTHER clients' more recent bookings with that master")
    void should_scopeMasterCategoryToAskingClient_when_anotherClientBookedMoreRecently() {
        UUID clientId = createClient("cat-scope-client@beautica.test");
        UUID stranger = createClient("cat-scope-stranger@beautica.test");
        UUID masterId = createIndependentMaster("cat-scope-master@beautica.test");
        UUID mine = createIndependentMasterService(masterId);
        UUID theirs = createIndependentMasterService(masterId);
        setCategory(mine, "NAIL_SERVICE");
        setCategory(theirs, "HAIRDRESSING");
        createBooking(clientId, masterId, mine, null, 10);
        createBooking(stranger, masterId, theirs, null, 1);
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, masterId);

        List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

        assertThat(result.get(0).categoryCode())
                .as("the stranger's newer HAIRDRESSING booking must not reach this client's card")
                .isEqualTo("NAIL_SERVICE");
    }

    /**
     * The salon arm, keyed on {@code bookings.salon_id}. The design puts both kinds on one axis,
     * so this must resolve for a salon exactly as it does for a master — it is null only when
     * the client has no booked history at that salon, never null-by-design.
     */
    @Test
    @DisplayName("the salon category comes from the client's most recent booking AT that salon")
    void should_deriveSalonCategoryFromLatestBooking_when_clientBookedAtSalon() {
        UUID clientId = createClient("cat-salon-client@beautica.test");
        UUID salonId = createSalon("cat-salon-owner@beautica.test");
        UUID masterId = createSalonMaster(salonId, "cat-salon-master@beautica.test");
        UUID oldService = createSalonMasterService(masterId, salonId, true, true);
        UUID newService = createSalonMasterService(masterId, salonId, true, true);
        setCategory(oldService, "NAIL_SERVICE");
        setCategory(newService, "LASH_EXTENSIONS");
        createBooking(clientId, masterId, oldService, salonId, 20);
        createBooking(clientId, masterId, newService, salonId, 2);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, salonId);

        List<FavoriteSalonResponse> result = favoriteService.listSalonFavorites(clientId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryCode()).isEqualTo("LASH_EXTENSIONS");
        assertThat(result.get(0).categoryLabel()).isEqualTo("Нарощення вій");
    }

    /**
     * §E-4 for the SALON arm — the mirror of
     * {@link #should_scopeMasterCategoryToAskingClient_when_anotherClientBookedMoreRecently}.
     *
     * <p>This test exists because the salon arm's {@code b.client_id = :clientId} predicate was
     * otherwise unpinned: every other salon-category test uses a single client, so deleting that
     * term from the {@code LATERAL} in
     * {@link com.beautica.booking.repository.BookingRepository#findLastBookedCategoryBySalonIds}
     * leaves them all green while the master arm alone goes red. A future edit to one arm would
     * ship another client's booked category onto this client's salon card, unnoticed.
     *
     * <p>Fixture shape, as on the master arm: the stranger's booking is the MOST RECENT row for
     * this salon and carries a DIFFERENT category, so dropping the predicate does not merely
     * widen the result — it overwrites the asking client's answer with the stranger's. The
     * stranger books a DIFFERENT master of the same salon so the assertion turns purely on
     * {@code salon_id} + {@code client_id}, never on a master-scoped accident.
     */
    @Test
    @DisplayName("the salon category ignores OTHER clients' more recent bookings at that salon")
    void should_scopeSalonCategoryToAskingClient_when_anotherClientBookedMoreRecently() {
        UUID clientId = createClient("cat-salon-scope-client@beautica.test");
        UUID stranger = createClient("cat-salon-scope-stranger@beautica.test");
        UUID salonId = createSalon("cat-salon-scope-owner@beautica.test");
        UUID myMaster = createSalonMaster(salonId, "cat-salon-scope-m1@beautica.test");
        UUID theirMaster = createSalonMaster(salonId, "cat-salon-scope-m2@beautica.test");
        UUID mine = createSalonMasterService(myMaster, salonId, true, true);
        UUID theirs = createSalonMasterService(theirMaster, salonId, true, true);
        setCategory(mine, "NAIL_SERVICE");
        setCategory(theirs, "HAIRDRESSING");
        createBooking(clientId, myMaster, mine, salonId, 10);
        createBooking(stranger, theirMaster, theirs, salonId, 1);
        favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, salonId);

        List<FavoriteSalonResponse> result = favoriteService.listSalonFavorites(clientId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryCode())
                .as("the stranger's newer HAIRDRESSING booking at this salon must not reach "
                        + "this client's card")
                .isEqualTo("NAIL_SERVICE");
        assertThat(result.get(0).categoryLabel()).isEqualTo("Нігтьовий сервіс");
    }

    /**
     * The common case the pair is nullable FOR: hearting a provider comes BEFORE booking them,
     * so most favourites start with no derivable category. This is accepted design behaviour —
     * the client hides categories with no rows — and must not be papered over with a fallback
     * derived from the master's service menu or profile, which would file them under a category
     * this client has never actually booked.
     */
    @Test
    @DisplayName("both category fields are null when the client has never booked the favourited master")
    void should_returnNullCategory_when_favouritedBeforeEverBooking() {
        UUID clientId = createClient("cat-null-client@beautica.test");
        UUID masterId = createIndependentMaster("cat-null-master@beautica.test");
        UUID service = createIndependentMasterService(masterId);
        setCategory(service, "NAIL_SERVICE");
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, masterId);

        List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryCode())
                .as("the master OFFERS a NAIL_SERVICE, but this client has not booked it — the "
                        + "axis is booked history, not the service menu")
                .isNull();
        assertThat(result.get(0).categoryLabel()).isNull();
    }

    /**
     * <b>The both-or-neither rule, end to end against the REAL category vocabulary.</b>
     *
     * <p>{@code FavoriteCategoryResolverTest#should_dropBothFields_when_categoryNotSelectable}
     * pins the rule against a mocked label resolver and an invented
     * {@code "DEACTIVATED_CATEGORY"} code. That proves the branch exists; it cannot prove a
     * stale code is REACHABLE, because the mock decides what is selectable.
     *
     * <p>It is reachable, and this repository already contains a live example.
     * {@code service_definitions.category} is a denormalised {@code platform_categories.name}
     * with NO foreign key (V64), and V74 renamed three of V64's seven original seeds IN PLACE —
     * {@code MANICURE -> NAIL_SERVICE}, {@code HAIRCUT -> HAIRDRESSING},
     * {@code EYELASH -> LASH_EXTENSIONS}. Any {@code service_definitions} row still carrying a
     * pre-V74 slug therefore holds a code that resolves to no label at all. That is exactly what
     * happened during this feature's development: fixtures written against the old names came
     * back blank, and the blanking was diagnosed as correct — but only OBSERVED, never pinned.
     * This test pins it.
     *
     * <p><b>Why the assertion cannot be defanged.</b> {@code MANICURE} is a real string the
     * database will happily store and the derivation will happily return; the pair is blanked by
     * {@code FavoriteCategoryResolver} alone. Emitting the code with a {@code null} label — the
     * obvious "simplification" of the pairing loop — would hand the client a chip identity it
     * cannot draw and cannot match against its own approved-category vocabulary, and would pass
     * every other test in this class. The companion master carries a CURRENT slug so the
     * suppression is visibly per-PROVIDER: a resolver that bailed out of the whole page on one
     * unresolvable code would blank both rows and still satisfy a single-row version of this.
     */
    @Test
    @DisplayName("both category fields are null when the booked service carries a pre-V74 slug "
            + "that is no longer a selectable category — and only that provider is blanked")
    void should_returnNullCategory_when_bookedCategoryIsNoLongerSelectable() {
        UUID clientId = createClient("cat-stale-client@beautica.test");
        UUID staleMaster = createIndependentMaster("cat-stale-master@beautica.test");
        UUID liveMaster = createIndependentMaster("cat-live-master@beautica.test");
        UUID staleService = createIndependentMasterService(staleMaster);
        UUID liveService = createIndependentMasterService(liveMaster);
        // V64's original slug. V74 renamed it to NAIL_SERVICE in place, so no platform_categories
        // row answers to it any more — the code survives in service_definitions only because the
        // column has no FK.
        setCategory(staleService, "MANICURE");
        setCategory(liveService, "NAIL_SERVICE");
        createBooking(clientId, staleMaster, staleService, null, 3);
        createBooking(clientId, liveMaster, liveService, null, 3);
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, staleMaster);
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, liveMaster);

        List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

        assertThat(result)
                .as("a code outside the current vocabulary is dropped WITH its missing label — "
                        + "never emitted half-resolved — while the companion master's current "
                        + "slug still resolves, so the suppression is per provider, not per page")
                .extracting(FavoriteMasterResponse::masterId,
                        FavoriteMasterResponse::categoryCode,
                        FavoriteMasterResponse::categoryLabel)
                .containsExactlyInAnyOrder(
                        tuple(staleMaster, null, null),
                        tuple(liveMaster, "NAIL_SERVICE", "Нігтьовий сервіс"));
    }

    /**
     * <b>"The most recent booking", not "the most recent CATEGORISED booking".</b>
     *
     * <p>{@code sd.category IS NOT NULL} sits in the OUTER {@code WHERE} of both arms, AFTER the
     * {@code LATERAL} has already committed to a single booking — so a client whose latest
     * booking was of an uncategorised service drops out of the map and surfaces as {@code null}.
     * It deliberately does NOT skip backwards to an older booking that happens to carry a
     * category; that would be a different rule, and neither the design nor the DTO states it.
     *
     * <p>Moving that one predicate INSIDE the {@code LATERAL} is a plausible, one-line
     * "optimisation" — it reads like a filter that belongs next to the join it filters — and it
     * silently changes the answer. Nothing else in the suite can see it: every other category
     * test gives every booking a category, so the predicate never fires and both placements are
     * indistinguishable. This is the only fixture where the two disagree, and they disagree
     * loudly — {@code null} versus {@code NAIL_SERVICE}, not a shrug.
     *
     * <p>The categorised booking is deliberately the OLDER one. If it were newer the test would
     * pass under both placements.
     */
    @Test
    @DisplayName("both category fields are null when the client's MOST RECENT booking was an "
            + "uncategorised service — the derivation does not skip back to an older categorised one")
    void should_returnNullCategory_when_latestBookedServiceIsUncategorised() {
        UUID clientId = createClient("cat-uncat-client@beautica.test");
        UUID masterId = createIndependentMaster("cat-uncat-master@beautica.test");
        UUID categorised = createIndependentMasterService(masterId);
        UUID uncategorised = createIndependentMasterService(masterId);
        setCategory(categorised, "NAIL_SERVICE");
        clearCategory(uncategorised);
        createBooking(clientId, masterId, categorised, null, 30);
        createBooking(clientId, masterId, uncategorised, null, 1);
        favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, masterId);

        List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).categoryCode())
                .as("the definite article in \"THE most recent booking\" is honoured: yesterday's "
                        + "uncategorised visit wins and yields null. Returning NAIL_SERVICE here "
                        + "means `sd.category IS NOT NULL` moved inside the LATERAL and the rule "
                        + "silently became \"most recent CATEGORISED booking\"")
                .isNull();
        assertThat(result.get(0).categoryLabel()).isNull();
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
