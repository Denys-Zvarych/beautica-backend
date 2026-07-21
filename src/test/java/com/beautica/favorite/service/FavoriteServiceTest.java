package com.beautica.favorite.service;

import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.favorite.dto.FavoriteMasterResponse;
import com.beautica.favorite.dto.FavoriteResponse;
import com.beautica.favorite.dto.FavoriteSalonResponse;
import com.beautica.favorite.entity.Favorite;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FavoriteService} (Phase 19.1).
 *
 * <p>All collaborators (repository, master/salon repositories, the M2
 * locality-label seam) are mocked; the tests verify the favoriting/unfavoriting
 * business rules — idempotency, {@code SALON_MASTER} rejection, missing-target
 * {@code 404}, the concurrent-race fallback, {@code lastServiceName} resolution
 * and the no-N+1 label batching — without booting Hibernate. End-to-end query
 * correctness lives in {@code FavoriteMigrationIT}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("FavoriteService — unit")
class FavoriteServiceTest {

    @Mock
    private FavoriteRepository favoriteRepository;

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private SalonRepository salonRepository;

    @Mock
    private DiscoveryLocationResolver discoveryLocationResolver;

    @InjectMocks
    private FavoriteService favoriteService;

    private final UUID clientId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    // ── helpers ────────────────────────────────────────────────────────────────

    private static Master masterOwnedBy(Role ownerRole) {
        User owner = new User(
                "master-" + UUID.randomUUID() + "@beautica.test", "hash", ownerRole,
                "Марія", "Левченко", "+380501234567");
        return Master.builder().id(UUID.randomUUID()).user(owner).build();
    }

    private static Favorite existingFavorite(UUID clientId, FavoriteTargetType type, UUID targetId) {
        Favorite favorite = Favorite.of(clientId, type, targetId);
        ReflectionTestUtils.setField(favorite, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(favorite, "createdAt", Instant.parse("2026-06-18T10:00:00Z"));
        return favorite;
    }

    // ── addFavorite — MASTER target ─────────────────────────────────────────────

    @Nested
    @DisplayName("addFavorite")
    class AddFavorite {

        @Test
        @DisplayName("returns the existing row on a duplicate — never inserts again (idempotent)")
        void should_returnExistingRow_when_masterAlreadyFavorited() {
            when(masterRepository.findByIdWithUserAndSalon(targetId))
                    .thenReturn(Optional.of(masterOwnedBy(Role.INDEPENDENT_MASTER)));
            Favorite existing = existingFavorite(clientId, FavoriteTargetType.MASTER, targetId);
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.MASTER, targetId))
                    .thenReturn(Optional.of(existing));

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, targetId);

            assertThat(response)
                    .extracting(FavoriteResponse::id, FavoriteResponse::targetType,
                            FavoriteResponse::targetId, FavoriteResponse::createdAt)
                    .containsExactly(existing.getId(), FavoriteTargetType.MASTER, targetId,
                            existing.getCreatedAt());
            verify(favoriteRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("inserts and returns a new favorite when the master is not yet favorited")
        void should_insertNewFavorite_when_masterNotYetFavorited() {
            when(masterRepository.findByIdWithUserAndSalon(targetId))
                    .thenReturn(Optional.of(masterOwnedBy(Role.INDEPENDENT_MASTER)));
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.MASTER, targetId))
                    .thenReturn(Optional.empty());
            Favorite saved = existingFavorite(clientId, FavoriteTargetType.MASTER, targetId);
            when(favoriteRepository.saveAndFlush(any(Favorite.class))).thenReturn(saved);

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, targetId);

            assertThat(response.targetId()).isEqualTo(targetId);
            assertThat(response.targetType()).isEqualTo(FavoriteTargetType.MASTER);

            ArgumentCaptor<Favorite> captor = ArgumentCaptor.forClass(Favorite.class);
            verify(favoriteRepository).saveAndFlush(captor.capture());
            assertThat(captor.getValue())
                    .as("persisted favorite is scoped to the authenticated principal")
                    .extracting(Favorite::getClientId, Favorite::getTargetType, Favorite::getTargetId)
                    .containsExactly(clientId, FavoriteTargetType.MASTER, targetId);
        }

        @Test
        @DisplayName("rejects a SALON_MASTER target with 400 and writes no row")
        void should_throwBadRequest_when_targetIsSalonMaster() {
            when(masterRepository.findByIdWithUserAndSalon(targetId))
                    .thenReturn(Optional.of(masterOwnedBy(Role.SALON_MASTER)));

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, targetId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));

            verify(favoriteRepository, never()).saveAndFlush(any());
            verify(favoriteRepository, never())
                    .findByClientIdAndTargetTypeAndTargetId(any(), any(), any());
        }

        @Test
        @DisplayName("throws 404 when the MASTER target does not exist")
        void should_throwNotFound_when_masterMissing() {
            when(masterRepository.findByIdWithUserAndSalon(targetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, targetId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Master not found");

            verify(favoriteRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("throws 404 when the SALON target does not exist")
        void should_throwNotFound_when_salonMissing() {
            when(salonRepository.existsById(targetId)).thenReturn(false);

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, targetId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Salon not found");

            verify(favoriteRepository, never()).saveAndFlush(any());
            verifyNoInteractions(masterRepository);
        }

        @Test
        @DisplayName("inserts a SALON favorite when the salon exists")
        void should_insertSalonFavorite_when_salonExists() {
            when(salonRepository.existsById(targetId)).thenReturn(true);
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.SALON, targetId))
                    .thenReturn(Optional.empty());
            Favorite saved = existingFavorite(clientId, FavoriteTargetType.SALON, targetId);
            when(favoriteRepository.saveAndFlush(any(Favorite.class))).thenReturn(saved);

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, targetId);

            assertThat(response.targetType()).isEqualTo(FavoriteTargetType.SALON);
            verify(favoriteRepository).saveAndFlush(any());
        }

        @Test
        @DisplayName("resolves a concurrent unique-violation by re-reading the now-present row")
        void should_returnRacedRow_when_concurrentInsertHitsUniqueIndex() {
            when(salonRepository.existsById(targetId)).thenReturn(true);
            Favorite raced = existingFavorite(clientId, FavoriteTargetType.SALON, targetId);
            // First read (pre-check) sees nothing; second read (post-violation) sees the raced row.
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.SALON, targetId))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(raced));
            when(favoriteRepository.saveAndFlush(any(Favorite.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_favorite"));

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, targetId);

            assertThat(response.id()).isEqualTo(raced.getId());
        }

        @Test
        @DisplayName("surfaces 409 when the unique-violation cannot be resolved by a re-read")
        void should_throwConflict_when_racedRowVanishesBeforeReRead() {
            when(salonRepository.existsById(targetId)).thenReturn(true);
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.SALON, targetId))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.empty());
            when(favoriteRepository.saveAndFlush(any(Favorite.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_favorite"));

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, targetId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                            .isEqualTo(HttpStatus.CONFLICT));
        }
    }

    // ── removeFavorite ──────────────────────────────────────────────────────────

    @Nested
    @DisplayName("removeFavorite")
    class RemoveFavorite {

        @Test
        @DisplayName("delegates a scoped delete and succeeds when a row existed")
        void should_deleteScopedRow_when_favoriteExists() {
            when(favoriteRepository.deleteByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.MASTER, targetId)).thenReturn(1);

            favoriteService.removeFavorite(clientId, FavoriteTargetType.MASTER, targetId);

            verify(favoriteRepository).deleteByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.MASTER, targetId);
        }

        @Test
        @DisplayName("succeeds (idempotent) when no favorite row was present")
        void should_succeed_when_favoriteAbsent() {
            when(favoriteRepository.deleteByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.SALON, targetId)).thenReturn(0);

            favoriteService.removeFavorite(clientId, FavoriteTargetType.SALON, targetId);

            verify(favoriteRepository).deleteByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.SALON, targetId);
        }
    }

    // ── listMasterFavorites ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("listMasterFavorites")
    class ListMasterFavorites {

        @Test
        @DisplayName("maps every projection field and carries this client's lastServiceName")
        void should_mapMasterRow_when_clientHasBookingWithMaster() {
            UUID masterId = UUID.randomUUID();
            UUID cityId = UUID.randomUUID();
            UUID districtId = UUID.randomUUID();
            Object[] row = {
                    masterId, "Марія", "Левченко", "https://cdn/avatar.png",
                    cityId, districtId, new BigDecimal("4.75"), "Манікюр"
            };
            when(favoriteRepository.findFavoriteMasterRows(clientId))
                    .thenReturn(List.<Object[]>of(row));
            when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                    .thenReturn(new DiscoveryLabels(
                            Map.of(cityId, "Київ"), Map.of(districtId, "Печерський")));

            List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

            assertThat(result).hasSize(1);
            assertThat(result.get(0))
                    .extracting(FavoriteMasterResponse::masterId, FavoriteMasterResponse::firstName,
                            FavoriteMasterResponse::lastName, FavoriteMasterResponse::avatarUrl,
                            FavoriteMasterResponse::cityLabel, FavoriteMasterResponse::districtLabel,
                            FavoriteMasterResponse::avgRating, FavoriteMasterResponse::lastServiceName)
                    .containsExactly(masterId, "Марія", "Левченко", "https://cdn/avatar.png",
                            "Київ", "Печерський", 4.75, "Манікюр");
        }

        @Test
        @DisplayName("leaves lastServiceName null when this client never booked the master")
        void should_returnNullServiceName_when_clientNeverBookedMaster() {
            UUID masterId = UUID.randomUUID();
            Object[] row = {masterId, "Олена", "Коваль", null, null, null, null, null};
            when(favoriteRepository.findFavoriteMasterRows(clientId)).thenReturn(List.<Object[]>of(row));
            when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                    .thenReturn(new DiscoveryLabels(Map.of(), Map.of()));

            List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

            assertThat(result.get(0).lastServiceName()).isNull();
            assertThat(result.get(0).avgRating()).isNull();
            assertThat(result.get(0).cityLabel()).isNull();
        }

        @Test
        @DisplayName("short-circuits with no label resolution when the client has no favorites (no N+1)")
        void should_returnEmpty_when_noFavorites() {
            when(favoriteRepository.findFavoriteMasterRows(clientId)).thenReturn(List.of());

            List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

            assertThat(result).isEmpty();
            verifyNoInteractions(discoveryLocationResolver);
        }

        @Test
        @DisplayName("resolves locality labels exactly once for the whole page (batched, no N+1)")
        void should_resolveLabelsOnce_when_pageHasManyRows() {
            UUID cityId = UUID.randomUUID();
            Object[] r1 = {UUID.randomUUID(), "A", "A", null, cityId, null, null, null};
            Object[] r2 = {UUID.randomUUID(), "B", "B", null, cityId, null, null, null};
            Object[] r3 = {UUID.randomUUID(), "C", "C", null, cityId, null, null, null};
            when(favoriteRepository.findFavoriteMasterRows(clientId))
                    .thenReturn(List.of(r1, r2, r3));
            when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                    .thenReturn(new DiscoveryLabels(Map.of(cityId, "Львів"), Map.of()));

            List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

            assertThat(result).hasSize(3);
            // One batched resolve for all three rows — a per-row call would be 3 invocations.
            verify(discoveryLocationResolver).resolveLabels(anyCollection(), anyCollection());
        }
    }

    // ── listSalonFavorites ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("listSalonFavorites")
    class ListSalonFavorites {

        @Test
        @DisplayName("maps every salon projection field including the AVG(rating) aggregate")
        void should_mapSalonRow_when_salonReviewed() {
            UUID salonId = UUID.randomUUID();
            UUID cityId = UUID.randomUUID();
            UUID districtId = UUID.randomUUID();
            // AVG() returns a Postgres numeric → mapped via Number.doubleValue().
            Object[] row = {salonId, "Salon Bella", "https://cdn/s.png",
                    cityId, districtId, new BigDecimal("4.20")};
            when(favoriteRepository.findFavoriteSalonRows(clientId)).thenReturn(List.<Object[]>of(row));
            when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                    .thenReturn(new DiscoveryLabels(
                            Map.of(cityId, "Одеса"), Map.of(districtId, "Приморський")));

            List<FavoriteSalonResponse> result = favoriteService.listSalonFavorites(clientId);

            assertThat(result.get(0))
                    .extracting(FavoriteSalonResponse::salonId, FavoriteSalonResponse::name,
                            FavoriteSalonResponse::avatarUrl, FavoriteSalonResponse::cityLabel,
                            FavoriteSalonResponse::districtLabel, FavoriteSalonResponse::avgRating)
                    .containsExactly(salonId, "Salon Bella", "https://cdn/s.png",
                            "Одеса", "Приморський", 4.20);
        }

        @Test
        @DisplayName("leaves avgRating null when the salon has no reviews")
        void should_returnNullRating_when_salonNeverReviewed() {
            UUID salonId = UUID.randomUUID();
            Object[] row = {salonId, "New Salon", null, null, null, null};
            when(favoriteRepository.findFavoriteSalonRows(clientId)).thenReturn(List.<Object[]>of(row));
            when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                    .thenReturn(new DiscoveryLabels(Map.of(), Map.of()));

            List<FavoriteSalonResponse> result = favoriteService.listSalonFavorites(clientId);

            assertThat(result.get(0).avgRating()).isNull();
        }

        @Test
        @DisplayName("short-circuits with no label resolution when the client has no salon favorites")
        void should_returnEmpty_when_noSalonFavorites() {
            when(favoriteRepository.findFavoriteSalonRows(clientId)).thenReturn(List.of());

            List<FavoriteSalonResponse> result = favoriteService.listSalonFavorites(clientId);

            assertThat(result).isEmpty();
            verifyNoInteractions(discoveryLocationResolver);
        }
    }
}
