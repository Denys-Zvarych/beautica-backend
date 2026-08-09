package com.beautica.favorite;

import com.beautica.AbstractIntegrationTest;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.repository.FavoriteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage for {@link FavoriteRepository#findFavoritedServiceIds(UUID,
 * java.util.Collection)} — the membership query backing the {@code isFavorite} decoration on
 * {@code GET /masters/{masterId}/services} (Phase 32.1 D5), run against real Postgres.
 *
 * <p>Deliberately narrow: the decorator's decision table (who gets queried at all) is pinned by
 * {@code MasterServiceFavoriteDecoratorTest} with a mocked repository. This class exists only to
 * prove the JPQL itself is correct against a real schema — in particular the {@code targetType}
 * predicate, which is the one collision the polymorphic, FK-less {@code target_id} column can hit.
 */
@DisplayName("FavoriteRepository.findFavoritedServiceIds — integration")
class FavoriteRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private FavoriteRepository favoriteRepository;

    @Test
    @DisplayName("returns only the SERVICE-favourited ids that are also in the requested id set — a "
            + "mixed in/out set proves the IN predicate, not just 'client has some favourite'")
    void should_returnOnlyMatchingIds_when_findFavoritedServiceIds() {
        UUID clientId = createClient("fav-repo-mixed-client@beautica.test");
        UUID master = createIndependentMaster("fav-repo-mixed-master@beautica.test");
        UUID favouritedInSet = createMasterService(master);
        UUID favouritedNotInSet = createMasterService(master);
        UUID notFavouritedInSet = createMasterService(master);
        insertFavorite(clientId, FavoriteTargetType.SERVICE, favouritedInSet);
        insertFavorite(clientId, FavoriteTargetType.SERVICE, favouritedNotInSet);

        Set<UUID> result = favoriteRepository.findFavoritedServiceIds(
                clientId, List.of(favouritedInSet, notFavouritedInSet));

        assertThat(result)
                .as("only the favourited id that was ALSO in the requested set may be returned — "
                        + "a favourite outside the id list must not leak in, and the unfavourited "
                        + "id in the set must not be invented")
                .containsExactly(favouritedInSet);
    }

    @Test
    @DisplayName("a MASTER favourite whose target_id collides with a queried master_services.id must "
            + "NOT be returned — targetType = SERVICE is load-bearing, not decorative "
            + "(mutation-checked: dropping it turns this test RED)")
    void should_ignoreOtherTargetTypes_when_masterAndServiceIdsCollide() {
        UUID clientId = createClient("fav-repo-collision-client@beautica.test");
        UUID master = createIndependentMaster("fav-repo-collision-master@beautica.test");
        UUID masterServiceId = createMasterService(master);
        // target_id has no FK — nothing stops a MASTER favourite from carrying the SAME uuid value
        // as an unrelated master_services row. This is the one collision the type predicate exists
        // to stop.
        insertFavorite(clientId, FavoriteTargetType.MASTER, masterServiceId);

        Set<UUID> result = favoriteRepository.findFavoritedServiceIds(clientId, List.of(masterServiceId));

        assertThat(result)
                .as("a MASTER-typed favourite must never be reported as a favourited SERVICE, even "
                        + "when its target_id value happens to equal a real master_services.id")
                .isEmpty();
    }

    @Test
    @DisplayName("returns an empty set, not null or an error, when the client has no favourites at all")
    void should_returnEmptySet_when_clientHasNoFavorites() {
        UUID clientId = createClient("fav-repo-none-client@beautica.test");
        UUID master = createIndependentMaster("fav-repo-none-master@beautica.test");
        UUID masterServiceId = createMasterService(master);

        Set<UUID> result = favoriteRepository.findFavoritedServiceIds(clientId, List.of(masterServiceId));

        assertThat(result).isNotNull().isEmpty();
    }

    // ── seed helpers ────────────────────────────────────────────────────────────

    private void insertFavorite(UUID clientId, FavoriteTargetType targetType, UUID targetId) {
        jdbcTemplate.update(
                "INSERT INTO favorites (id, client_id, target_type, target_id, created_at) "
                        + "VALUES (?, ?, ?, ?, NOW())",
                UUID.randomUUID(), clientId, targetType.name(), targetId);
    }

    private UUID createClient(String email) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, 'x', 'CLIENT', true, true)",
                id, email);
        return id;
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

    private UUID createMasterService(UUID masterId) {
        UUID ownerId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Test Service', ?, 60, 500.00, 0, true, "
                        + "NOW(), NOW())",
                serviceDefId, ownerId, resolveUnusedServiceTypeId("INDEPENDENT_MASTER", ownerId));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }
}
