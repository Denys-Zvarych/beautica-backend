package com.beautica.favorite;

import com.beautica.AbstractIntegrationTest;
import com.beautica.favorite.dto.FavoriteMasterResponse;
import com.beautica.favorite.dto.FavoriteResponse;
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
 * Testcontainers integration test for Phase 19.1 favorites — exercises the
 * {@code V92} schema (CHECK + UNIQUE) and the two native read projections against a
 * real PostgreSQL, plus the service-layer idempotency and per-client
 * {@code lastServiceName} resolution. ASCII-only seed data.
 *
 * <p>Validation runs at the repository/service layer (not over HTTP) so the test
 * stays focused on the persistence contract the migration introduces; the HTTP
 * contract is covered by {@code FavoriteControllerTest}.
 */
@DisplayName("Favorites — V92 migration + native projections (Testcontainers)")
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
                        + "(id, owner_type, owner_id, name, base_duration_minutes, base_price, "
                        + "buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, userId);
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
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
