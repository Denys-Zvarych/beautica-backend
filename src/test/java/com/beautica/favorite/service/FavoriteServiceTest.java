package com.beautica.favorite.service;

import com.beautica.auth.Role;
import com.beautica.booking.domain.MasterBookability;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.favorite.dto.FavoriteMasterResponse;
import com.beautica.favorite.dto.FavoriteResponse;
import com.beautica.favorite.dto.FavoriteSalonResponse;
import com.beautica.favorite.dto.FavoriteServiceResponse;
import com.beautica.favorite.entity.Favorite;
import com.beautica.favorite.entity.FavoriteTargetType;
import com.beautica.favorite.repository.FavoriteRepository;
import com.beautica.location.DiscoveryLocationResolver;
import com.beautica.location.DiscoveryLocationResolver.DiscoveryLabels;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.repository.MasterServiceRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
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
import static org.mockito.ArgumentMatchers.eq;
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
    private MasterServiceRepository masterServiceRepository;

    @Mock
    private DiscoveryLocationResolver discoveryLocationResolver;

    @InjectMocks
    private FavoriteService favoriteService;

    private final UUID clientId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    // ── helpers ────────────────────────────────────────────────────────────────

    private static Master masterOwnedBy(Role ownerRole) {
        return masterOwnedBy(ownerRole, true);
    }

    /**
     * {@code masterActive = false} models a provider who deactivated: the 2026-08 security audit
     * made that a rejection on BOTH favorite arms, because a booking against such a master 404s.
     */
    private static Master masterOwnedBy(Role ownerRole, boolean masterActive) {
        User owner = new User(
                "master-" + UUID.randomUUID() + "@beautica.test", "hash", ownerRole,
                "Марія", "Левченко", "+380501234567");
        return Master.builder()
                .id(UUID.randomUUID())
                .user(owner)
                .isActive(masterActive)
                .build();
    }

    /**
     * An assignment whose owning master's user carries {@code ownerRole} — used to prove the
     * SERVICE arm applies NO role check (a SALON_MASTER's service IS wish-listable), unlike the
     * MASTER arm.
     */
    private static MasterServiceAssignment assignmentOwnedBy(Role ownerRole,
                                                             boolean assignmentActive,
                                                             boolean definitionActive) {
        return assignmentOwnedBy(ownerRole, assignmentActive, definitionActive, true);
    }

    private static MasterServiceAssignment assignmentOwnedBy(Role ownerRole,
                                                             boolean assignmentActive,
                                                             boolean definitionActive,
                                                             boolean masterActive) {
        return assignmentOwnedBy(ownerRole, assignmentActive, definitionActive, masterActive, null);
    }

    /**
     * {@code salon} models a SALON-employed master's owning salon (2026-08 security re-audit
     * MEDIUM). {@code null} is the {@code INDEPENDENT_MASTER} shape — no salon at all — and MUST
     * remain wish-listable, so it is the default every other overload passes.
     */
    private static MasterServiceAssignment assignmentOwnedBy(Role ownerRole,
                                                             boolean assignmentActive,
                                                             boolean definitionActive,
                                                             boolean masterActive,
                                                             Salon salon) {
        ServiceDefinition sd = ServiceDefinition.builder()
                .id(UUID.randomUUID())
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(UUID.randomUUID())
                .name("Manicure")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("600.00"))
                .isActive(definitionActive)
                .build();

        Master master = masterOwnedBy(ownerRole, masterActive);
        master.setSalon(salon);

        return MasterServiceAssignment.builder()
                .id(UUID.randomUUID())
                .master(master)
                .serviceDefinition(sd)
                .isActive(assignmentActive)
                .build();
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
        @DisplayName("rejects a deactivated MASTER target with 400 and writes no row")
        void should_throwBadRequest_when_masterIsInactive() {
            // Independent (so the role gate passes) but deactivated — the only failing flag.
            when(masterRepository.findByIdWithUserAndSalon(targetId))
                    .thenReturn(Optional.of(masterOwnedBy(Role.INDEPENDENT_MASTER, false)));

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, targetId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));

            verify(favoriteRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("throws 404 when the SALON target is deactivated — indistinguishable from missing")
        void should_throwNotFound_when_salonIsInactive() {
            // existsByIdAndIsActiveTrue answers false for a soft-deleted salon exactly as it does
            // for an absent one; the client must not be able to tell the two apart.
            when(salonRepository.existsByIdAndIsActiveTrue(targetId)).thenReturn(false);

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, targetId))
                    .isInstanceOf(NotFoundException.class);

            verify(favoriteRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("throws 404 when the SALON target does not exist")
        void should_throwNotFound_when_salonMissing() {
            when(salonRepository.existsByIdAndIsActiveTrue(targetId)).thenReturn(false);

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
            when(salonRepository.existsByIdAndIsActiveTrue(targetId)).thenReturn(true);
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
            when(salonRepository.existsByIdAndIsActiveTrue(targetId)).thenReturn(true);
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
            when(salonRepository.existsByIdAndIsActiveTrue(targetId)).thenReturn(true);
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

    // ── addFavorite — SERVICE target (Phase 31.3) ───────────────────────────────

    @Nested
    @DisplayName("addFavorite — SERVICE target")
    class AddServiceFavorite {

        @Test
        @DisplayName("inserts when the target is an active master_services row")
        void should_persist_when_targetIsActiveService() {
            when(masterServiceRepository.findByIdWithServiceDefinitionAndMaster(targetId))
                    .thenReturn(Optional.of(
                            assignmentOwnedBy(Role.INDEPENDENT_MASTER, true, true)));
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.SERVICE, targetId))
                    .thenReturn(Optional.empty());
            Favorite saved = existingFavorite(clientId, FavoriteTargetType.SERVICE, targetId);
            when(favoriteRepository.saveAndFlush(any(Favorite.class))).thenReturn(saved);

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, targetId);

            assertThat(response.targetType()).isEqualTo(FavoriteTargetType.SERVICE);
            assertThat(response.targetId()).isEqualTo(targetId);
            verify(favoriteRepository).saveAndFlush(any());
        }

        @Test
        @DisplayName("ALLOWS a salon-employed master's service — deliberate asymmetry with the MASTER rule")
        void should_persist_when_targetIsSalonMasterService() {
            // Locked user decision (2026-08-07): a wish-listed service is a rebook shortcut,
            // not an endorsement of a person. Copying validateMasterTarget's SALON_MASTER
            // rejection here would make most of the catalogue un-wish-listable.
            when(masterServiceRepository.findByIdWithServiceDefinitionAndMaster(targetId))
                    .thenReturn(Optional.of(assignmentOwnedBy(Role.SALON_MASTER, true, true)));
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.SERVICE, targetId))
                    .thenReturn(Optional.empty());
            when(favoriteRepository.saveAndFlush(any(Favorite.class)))
                    .thenReturn(existingFavorite(clientId, FavoriteTargetType.SERVICE, targetId));

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, targetId);

            assertThat(response.targetType()).isEqualTo(FavoriteTargetType.SERVICE);
            verify(favoriteRepository).saveAndFlush(any());
        }

        @Test
        @DisplayName("throws 404 when the master_services id is unknown")
        void should_throwNotFound_when_masterServiceIdUnknown() {
            when(masterServiceRepository.findByIdWithServiceDefinitionAndMaster(targetId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, targetId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Service not found");

            verify(favoriteRepository, never()).saveAndFlush(any());
            verifyNoInteractions(masterRepository, salonRepository);
        }

        @Test
        @DisplayName("rejects with 400 when the assignment itself is deactivated")
        void should_throwBadRequest_when_assignmentInactive() {
            when(masterServiceRepository.findByIdWithServiceDefinitionAndMaster(targetId))
                    .thenReturn(Optional.of(
                            assignmentOwnedBy(Role.INDEPENDENT_MASTER, false, true)));

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, targetId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));

            verify(favoriteRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("rejects with 400 when the service definition was soft-deleted but the assignment is still active")
        void should_throwBadRequest_when_serviceDefinitionInactive() {
            // deactivateServiceDefinition soft-deletes the definition without touching the
            // assignment row, so this combination is a real state, not a synthetic one.
            when(masterServiceRepository.findByIdWithServiceDefinitionAndMaster(targetId))
                    .thenReturn(Optional.of(
                            assignmentOwnedBy(Role.INDEPENDENT_MASTER, true, false)));

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, targetId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));

            verify(favoriteRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("rejects with 400 when the performing master deactivated, even though both "
                + "service flags are still active")
        void should_reject_when_masterIsInactive() {
            // 2026-08 security audit. The assignment AND the definition are both active — the
            // ONLY inactive flag is the master's. Without the master predicate this favorite is
            // written and the wish list then offers a «Записатись» that doCreateBooking 404s.
            when(masterServiceRepository.findByIdWithServiceDefinitionAndMaster(targetId))
                    .thenReturn(Optional.of(
                            assignmentOwnedBy(Role.INDEPENDENT_MASTER, true, true, false)));

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, targetId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));

            verify(favoriteRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("rejects with 400 when the master's SALON was deactivated, even though the "
                + "assignment, the definition and the master row are all still active")
        void should_reject_when_masterSalonIsInactive() {
            // 2026-08 security re-audit MEDIUM. SalonService.deactivateSalon does NOT cascade to
            // masters.is_active, so all three pre-existing flags stay true and only the salon's is
            // false — the exact state that let a closed salon's services keep a live «Записатись».
            MasterServiceAssignment assignment = assignmentOwnedBy(
                    Role.SALON_MASTER, true, true, true,
                    Salon.builder().id(UUID.randomUUID()).isActive(false).build());
            // The rejection must be the CANONICAL rule's verdict, not a look-alike hand-rolled
            // predicate: validateServiceTarget delegates to MasterBookability, so pin the two
            // together here. If someone re-inlines the check and the two drift, this fails.
            assertThat(MasterBookability.isBookable(assignment.getMaster()))
                    .as("precondition: MasterBookability — the single canonical rule — must itself "
                            + "call this master unbookable, so the 400 below is that rule's verdict")
                    .isFalse();
            when(masterServiceRepository.findByIdWithServiceDefinitionAndMaster(targetId))
                    .thenReturn(Optional.of(assignment));

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, targetId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));

            verify(favoriteRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("ALLOWS a salon master's service while the salon is still ACTIVE — the salon "
                + "guard must not reject every salon-employed master")
        void should_persist_when_masterSalonIsActive() {
            MasterServiceAssignment assignment = assignmentOwnedBy(
                    Role.SALON_MASTER, true, true, true,
                    Salon.builder().id(UUID.randomUUID()).isActive(true).build());
            // The other half of the MasterBookability pin (see should_reject_when_masterSalonIsInactive):
            // without this, a helper that always answered false would leave that test green while
            // silently locking every salon-employed master out of the wish list.
            assertThat(MasterBookability.isBookable(assignment.getMaster()))
                    .as("precondition: an active master of an OPEN salon IS bookable")
                    .isTrue();
            when(masterServiceRepository.findByIdWithServiceDefinitionAndMaster(targetId))
                    .thenReturn(Optional.of(assignment));
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.SERVICE, targetId))
                    .thenReturn(Optional.empty());
            when(favoriteRepository.saveAndFlush(any(Favorite.class)))
                    .thenReturn(existingFavorite(clientId, FavoriteTargetType.SERVICE, targetId));

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, targetId);

            assertThat(response.targetType()).isEqualTo(FavoriteTargetType.SERVICE);
            verify(favoriteRepository).saveAndFlush(any());
        }

        @Test
        @DisplayName("returns the existing row on a duplicate — never inserts again (idempotent)")
        void should_beIdempotent_when_sameServiceFavoritedTwice() {
            when(masterServiceRepository.findByIdWithServiceDefinitionAndMaster(targetId))
                    .thenReturn(Optional.of(
                            assignmentOwnedBy(Role.INDEPENDENT_MASTER, true, true)));
            Favorite existing = existingFavorite(clientId, FavoriteTargetType.SERVICE, targetId);
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.SERVICE, targetId))
                    .thenReturn(Optional.of(existing));

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, targetId);

            assertThat(response.id()).isEqualTo(existing.getId());
            verify(favoriteRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("resolves a concurrent unique-violation on the SERVICE path by re-reading")
        void should_returnRacedRow_when_concurrentServiceInsertHitsUniqueIndex() {
            when(masterServiceRepository.findByIdWithServiceDefinitionAndMaster(targetId))
                    .thenReturn(Optional.of(
                            assignmentOwnedBy(Role.INDEPENDENT_MASTER, true, true)));
            Favorite raced = existingFavorite(clientId, FavoriteTargetType.SERVICE, targetId);
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.SERVICE, targetId))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(raced));
            when(favoriteRepository.saveAndFlush(any(Favorite.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_favorite"));

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, targetId);

            assertThat(response.id()).isEqualTo(raced.getId());
        }
    }

    // ── listServiceFavorites ────────────────────────────────────────────────────

    @Nested
    @DisplayName("listServiceFavorites")
    class ListServiceFavorites {

        @Test
        @DisplayName("maps every projection row to a wish-list row, sort stripped")
        void should_mapAssignmentsToWishListRows_when_clientHasServiceFavorites() {
            MasterServiceAssignment msa = assignmentOwnedBy(Role.SALON_MASTER, true, true);
            // Row layout: [assignment, firstName, lastName, avatarUrl] — the master's identity
            // arrives as SCALARS off the query, never via msa.getMaster().getUser().
            Object[] row = {msa, "Марія", "Левченко", "https://cdn/avatar.png"};
            when(favoriteRepository.findFavoriteServiceRows(eq(clientId), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.<Object[]>of(row)));

            Page<FavoriteServiceResponse> page =
                    favoriteService.listServiceFavorites(clientId, PageRequest.of(0, 20));

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0))
                    .extracting(FavoriteServiceResponse::masterServiceId,
                            FavoriteServiceResponse::masterId,
                            FavoriteServiceResponse::serviceName,
                            FavoriteServiceResponse::masterFirstName,
                            FavoriteServiceResponse::masterLastName,
                            FavoriteServiceResponse::masterAvatarUrl,
                            FavoriteServiceResponse::durationMinutes,
                            FavoriteServiceResponse::priceDisplay)
                    .containsExactly(msa.getId(), msa.getMaster().getId(), "Manicure",
                            "Марія", "Левченко", "https://cdn/avatar.png", 60, "600 ₴");
        }

        @Test
        @DisplayName("carries a null avatar straight through from the scalar column")
        void should_returnNullAvatar_when_projectionAvatarIsNull() {
            MasterServiceAssignment msa = assignmentOwnedBy(Role.INDEPENDENT_MASTER, true, true);
            Object[] row = {msa, "Олена", "Коваль", null};
            when(favoriteRepository.findFavoriteServiceRows(eq(clientId), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.<Object[]>of(row)));

            Page<FavoriteServiceResponse> page =
                    favoriteService.listServiceFavorites(clientId, PageRequest.of(0, 20));

            assertThat(page.getContent().get(0).masterAvatarUrl()).isNull();
        }

        @Test
        @DisplayName("returns an empty page when the client has wish-listed nothing")
        void should_returnEmptyPage_when_noServiceFavorites() {
            when(favoriteRepository.findFavoriteServiceRows(eq(clientId), any(Pageable.class)))
                    .thenReturn(Page.empty());

            Page<FavoriteServiceResponse> page =
                    favoriteService.listServiceFavorites(clientId, PageRequest.of(0, 20));

            assertThat(page).isEmpty();
            verifyNoInteractions(discoveryLocationResolver);
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
