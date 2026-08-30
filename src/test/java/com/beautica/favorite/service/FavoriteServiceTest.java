package com.beautica.favorite.service;

import com.beautica.TestConstants;
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
import com.beautica.favorite.dto.FavoriteCategoryView;
import com.beautica.favorite.service.FavoriteCategoryResolver.FavoriteCategories;
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
import org.junit.jupiter.api.BeforeEach;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FavoriteService} (Phase 19.1).
 *
 * <p>All collaborators (repository, master/salon repositories, the M2
 * locality-label seam, {@link FavoritePersistenceService}) are mocked; the tests verify the
 * favoriting/unfavoriting business rules — idempotency, inactive-target rejection,
 * missing-target {@code 404}, the concurrent-race fallback, projection-row mapping
 * and the no-N+1 label batching — without booting Hibernate. Because
 * {@code favoritePersistenceService} is a mock here, it has no real transaction to poison, so
 * these tests cannot see the aborted-transaction gap a genuine two-thread race exposes; that
 * proof lives in {@code FavoriteSalonServiceIT}'s real-Postgres concurrency test. End-to-end
 * query correctness lives in {@code FavoriteMigrationIT}.
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
    private com.beautica.service.repository.ServiceRepository serviceRepository;

    @Mock
    private DiscoveryLocationResolver discoveryLocationResolver;

    @Mock
    private FavoritePersistenceService favoritePersistenceService;

    @Mock
    private FavoriteCategoryResolver favoriteCategoryResolver;

    @InjectMocks
    private FavoriteService favoriteService;

    private final UUID clientId = UUID.randomUUID();
    private final UUID targetId = UUID.randomUUID();

    /**
     * Default the category axis to "nobody on this page offers anything categorisable", so the
     * ~13 pre-existing list tests — which predate the axis and assert nothing about it — keep
     * exercising the mapping they were written for instead of NPE-ing on a null carrier.
     *
     * <p>{@code lenient()} because the majority of tests in this class never reach a list path
     * at all (they cover add/remove/validation), and because the category tests below REPLACE
     * these stubs with their own; under strict stubs either would fail the run for an
     * unnecessary stubbing rather than for the behaviour under test.
     *
     * <p>An empty list is the honest default, not a convenience: this axis is no longer
     * client-scoped, so {@link FavoriteCategoryResolver#resolveForMasters} /
     * {@code #resolveForSalons} no longer take a {@code clientId} argument.
     */
    @BeforeEach
    void defaultToNoOfferedCategories() {
        lenient().when(favoriteCategoryResolver.resolveForMasters(anyCollection()))
                .thenReturn(FavoriteCategories.empty());
        lenient().when(favoriteCategoryResolver.resolveForSalons(anyCollection()))
                .thenReturn(FavoriteCategories.empty());
    }

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
     * SERVICE arm applies NO role check (a SALON_MASTER's service IS wish-listable), matching the
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
            verifyNoInteractions(favoritePersistenceService);
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
            when(favoritePersistenceService.persistNew(any(Favorite.class))).thenReturn(saved);

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, targetId);

            assertThat(response.targetId()).isEqualTo(targetId);
            assertThat(response.targetType()).isEqualTo(FavoriteTargetType.MASTER);

            ArgumentCaptor<Favorite> captor = ArgumentCaptor.forClass(Favorite.class);
            verify(favoritePersistenceService).persistNew(captor.capture());
            assertThat(captor.getValue())
                    .as("persisted favorite is scoped to the authenticated principal")
                    .extracting(Favorite::getClientId, Favorite::getTargetType, Favorite::getTargetId)
                    .containsExactly(clientId, FavoriteTargetType.MASTER, targetId);
        }

        /**
         * INVERTED by mobile Phase 111 (was {@code should_throwBadRequest_when_targetIsSalonMaster},
         * which asserted a 400). The role predicate is gone: a client may heart any provider they
         * can book, so a salon-employed master is now a valid MASTER target. Kept — not deleted —
         * so the reversal is visible in history and a reintroduced role check goes red here.
         */
        @Test
        @DisplayName("persists a SALON_MASTER target — the role predicate was removed (Phase 111)")
        void should_persist_when_targetIsSalonMaster() {
            when(masterRepository.findByIdWithUserAndSalon(targetId))
                    .thenReturn(Optional.of(masterOwnedBy(Role.SALON_MASTER)));
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.MASTER, targetId)).thenReturn(Optional.empty());
            when(favoritePersistenceService.persistNew(any(Favorite.class)))
                    .thenReturn(existingFavorite(clientId, FavoriteTargetType.MASTER, targetId));

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, targetId);

            assertThat(response.targetId()).isEqualTo(targetId);
            verify(favoritePersistenceService).persistNew(any(Favorite.class));
        }

        /**
         * The second value the removed predicate rejected, and the subtler one: the check read
         * {@code master.getUser().getRole()}, so a salon owner working as a master — user role
         * {@code SALON_OWNER}, {@code MasterType.SALON_OWNER} — was rejected too, even though
         * they are bookable. Removing the single predicate admits all three MasterTypes.
         */
        @Test
        @DisplayName("persists a SALON_OWNER-as-master target (owner working as a master)")
        void should_persist_when_targetIsOwnerAsMaster() {
            when(masterRepository.findByIdWithUserAndSalon(targetId))
                    .thenReturn(Optional.of(masterOwnedBy(Role.SALON_OWNER)));
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.MASTER, targetId)).thenReturn(Optional.empty());
            when(favoritePersistenceService.persistNew(any(Favorite.class)))
                    .thenReturn(existingFavorite(clientId, FavoriteTargetType.MASTER, targetId));

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, targetId);

            assertThat(response.targetId()).isEqualTo(targetId);
            verify(favoritePersistenceService).persistNew(any(Favorite.class));
        }

        /**
         * The ACTIVE check is the ONE surviving 400 on this arm — pinned per role so removing the
         * role predicate cannot be mistaken for removing the guard entirely.
         */
        @Test
        @DisplayName("still rejects an INACTIVE master with 400 and writes no row, whatever the role")
        void should_throwBadRequest_when_masterInactive() {
            when(masterRepository.findByIdWithUserAndSalon(targetId))
                    .thenReturn(Optional.of(masterOwnedBy(Role.SALON_MASTER, false)));

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, targetId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));

            verifyNoInteractions(favoritePersistenceService);
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

            verifyNoInteractions(favoritePersistenceService);
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

            verifyNoInteractions(favoritePersistenceService);
        }

        @Test
        @DisplayName("rejects a MASTER of a DEACTIVATED salon with 400 — write-time and read-time "
                + "must agree on one rule")
        void should_throwBadRequest_when_masterSalonIsInactive() {
            // 2026-08 re-audit LOW. SalonService.deactivateSalon does NOT cascade to
            // masters.is_active, so the master row still reads active and only the salon's flag is
            // false. Before this fix the write path checked master.isActive() alone: the POST
            // returned 200 and stored a row findFavoriteMasterRows filters out for ever — a soft
            // "is this account still active" oracle for a salon withdrawn from public view.
            Master master = masterOwnedBy(Role.SALON_MASTER, true);
            master.setSalon(Salon.builder()
                    .cityId(TestConstants.DEFAULT_TEST_CITY_ID).id(UUID.randomUUID()).isActive(false).build());
            // Pin the verdict to the CANONICAL rule rather than a look-alike inline predicate:
            // validateMasterTarget delegates to MasterBookability, exactly as validateServiceTarget
            // does. Re-inline the check and let the two drift, and this precondition fails.
            assertThat(MasterBookability.isBookable(master))
                    .as("precondition: MasterBookability — the single canonical rule — must itself "
                            + "call this master unbookable, so the 400 below is that rule's verdict")
                    .isFalse();
            when(masterRepository.findByIdWithUserAndSalon(targetId)).thenReturn(Optional.of(master));

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, targetId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                            .as("same 400 as the deactivated-master case — a client must not be "
                                    + "able to tell 'master left' from 'salon closed'")
                            .isEqualTo(HttpStatus.BAD_REQUEST));

            verifyNoInteractions(favoritePersistenceService);
        }

        @Test
        @DisplayName("still ALLOWS a MASTER of an ACTIVE salon — the salon guard must not reject "
                + "every salon-employed master")
        void should_persist_when_masterSalonIsActive() {
            Master master = masterOwnedBy(Role.SALON_MASTER, true);
            master.setSalon(Salon.builder()
                    .cityId(TestConstants.DEFAULT_TEST_CITY_ID).id(UUID.randomUUID()).isActive(true).build());
            when(masterRepository.findByIdWithUserAndSalon(targetId)).thenReturn(Optional.of(master));
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.MASTER, targetId)).thenReturn(Optional.empty());
            when(favoritePersistenceService.persistNew(any(Favorite.class)))
                    .thenReturn(existingFavorite(clientId, FavoriteTargetType.MASTER, targetId));

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.MASTER, targetId);

            assertThat(response.targetId()).isEqualTo(targetId);
            verify(favoritePersistenceService).persistNew(any(Favorite.class));
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

            verifyNoInteractions(favoritePersistenceService);
        }

        @Test
        @DisplayName("throws 404 when the SALON target does not exist")
        void should_throwNotFound_when_salonMissing() {
            when(salonRepository.existsByIdAndIsActiveTrue(targetId)).thenReturn(false);

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, targetId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Salon not found");

            verifyNoInteractions(favoritePersistenceService);
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
            when(favoritePersistenceService.persistNew(any(Favorite.class))).thenReturn(saved);

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SALON, targetId);

            assertThat(response.targetType()).isEqualTo(FavoriteTargetType.SALON);
            verify(favoritePersistenceService).persistNew(any());
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
            when(favoritePersistenceService.persistNew(any(Favorite.class)))
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
            when(favoritePersistenceService.persistNew(any(Favorite.class)))
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
            when(favoritePersistenceService.persistNew(any(Favorite.class))).thenReturn(saved);

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, targetId);

            assertThat(response.targetType()).isEqualTo(FavoriteTargetType.SERVICE);
            assertThat(response.targetId()).isEqualTo(targetId);
            verify(favoritePersistenceService).persistNew(any());
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
            when(favoritePersistenceService.persistNew(any(Favorite.class)))
                    .thenReturn(existingFavorite(clientId, FavoriteTargetType.SERVICE, targetId));

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, targetId);

            assertThat(response.targetType()).isEqualTo(FavoriteTargetType.SERVICE);
            verify(favoritePersistenceService).persistNew(any());
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

            verifyNoInteractions(favoritePersistenceService);
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

            verifyNoInteractions(favoritePersistenceService);
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

            verifyNoInteractions(favoritePersistenceService);
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

            verifyNoInteractions(favoritePersistenceService);
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
                    Salon.builder()
                            .cityId(TestConstants.DEFAULT_TEST_CITY_ID).id(UUID.randomUUID()).isActive(false).build());
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

            verifyNoInteractions(favoritePersistenceService);
        }

        @Test
        @DisplayName("ALLOWS a salon master's service while the salon is still ACTIVE — the salon "
                + "guard must not reject every salon-employed master")
        void should_persist_when_masterSalonIsActive() {
            MasterServiceAssignment assignment = assignmentOwnedBy(
                    Role.SALON_MASTER, true, true, true,
                    Salon.builder()
                            .cityId(TestConstants.DEFAULT_TEST_CITY_ID).id(UUID.randomUUID()).isActive(true).build());
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
            when(favoritePersistenceService.persistNew(any(Favorite.class)))
                    .thenReturn(existingFavorite(clientId, FavoriteTargetType.SERVICE, targetId));

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, targetId);

            assertThat(response.targetType()).isEqualTo(FavoriteTargetType.SERVICE);
            verify(favoritePersistenceService).persistNew(any());
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
            verifyNoInteractions(favoritePersistenceService);
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
            when(favoritePersistenceService.persistNew(any(Favorite.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_favorite"));

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SERVICE, targetId);

            assertThat(response.id()).isEqualTo(raced.getId());
        }
    }

    // ── addFavorite — SALON_SERVICE target (salon-service-favourites track) ─────

    @Nested
    @DisplayName("addFavorite — SALON_SERVICE target")
    class AddSalonServiceFavorite {

        private ServiceDefinition salonServiceDefinition(UUID salonId, boolean active) {
            return ServiceDefinition.builder()
                    .id(targetId)
                    .ownerType(OwnerType.SALON)
                    .ownerId(salonId)
                    .name("Pedicure")
                    .baseDurationMinutes(45)
                    .priceType(PriceType.FIXED)
                    .basePrice(new BigDecimal("400.00"))
                    .isActive(active)
                    .build();
        }

        @Test
        @DisplayName("inserts when the definition is a salon-owned, active service an active master performs")
        void should_persist_when_targetIsBookableSalonService() {
            UUID salonId = UUID.randomUUID();
            when(serviceRepository.findById(targetId))
                    .thenReturn(Optional.of(salonServiceDefinition(salonId, true)));
            when(masterServiceRepository.existsBookableAssignmentForSalonService(salonId, targetId))
                    .thenReturn(true);
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.SALON_SERVICE, targetId))
                    .thenReturn(Optional.empty());
            Favorite saved = existingFavorite(clientId, FavoriteTargetType.SALON_SERVICE, targetId);
            when(favoritePersistenceService.persistNew(any(Favorite.class))).thenReturn(saved);

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, targetId);

            assertThat(response.targetType()).isEqualTo(FavoriteTargetType.SALON_SERVICE);
            assertThat(response.targetId()).isEqualTo(targetId);
            verify(favoritePersistenceService).persistNew(any());
        }

        @Test
        @DisplayName("throws 404 when the service_definitions id is unknown")
        void should_throwNotFound_when_serviceDefinitionUnknown() {
            when(serviceRepository.findById(targetId)).thenReturn(Optional.empty());

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, targetId))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("Service not found");

            verifyNoInteractions(favoritePersistenceService);
            verifyNoInteractions(masterServiceRepository);
        }

        @Test
        @DisplayName("rejects with 400 when the definition is soft-deleted")
        void should_throwBadRequest_when_definitionInactive() {
            UUID salonId = UUID.randomUUID();
            when(serviceRepository.findById(targetId))
                    .thenReturn(Optional.of(salonServiceDefinition(salonId, false)));

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, targetId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));

            verifyNoInteractions(favoritePersistenceService);
            verifyNoInteractions(masterServiceRepository);
        }

        @Test
        @DisplayName("rejects with 400 when the definition is owned by an INDEPENDENT_MASTER, not a SALON")
        void should_throwBadRequest_when_definitionOwnedByIndependentMaster() {
            ServiceDefinition definition = ServiceDefinition.builder()
                    .id(targetId)
                    .ownerType(OwnerType.INDEPENDENT_MASTER)
                    .ownerId(UUID.randomUUID())
                    .name("Manicure")
                    .baseDurationMinutes(60)
                    .priceType(PriceType.FIXED)
                    .basePrice(new BigDecimal("600.00"))
                    .isActive(true)
                    .build();
            when(serviceRepository.findById(targetId)).thenReturn(Optional.of(definition));

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, targetId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));

            verifyNoInteractions(favoritePersistenceService);
            verifyNoInteractions(masterServiceRepository);
        }

        @Test
        @DisplayName("rejects with 400 when no active master of the salon currently performs the service")
        void should_throwBadRequest_when_noActiveMasterPerformsService() {
            UUID salonId = UUID.randomUUID();
            when(serviceRepository.findById(targetId))
                    .thenReturn(Optional.of(salonServiceDefinition(salonId, true)));
            when(masterServiceRepository.existsBookableAssignmentForSalonService(salonId, targetId))
                    .thenReturn(false);

            assertThatThrownBy(() ->
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, targetId))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> assertThat(((BusinessException) ex).getStatus())
                            .isEqualTo(HttpStatus.BAD_REQUEST));

            verifyNoInteractions(favoritePersistenceService);
        }

        @Test
        @DisplayName("returns the existing row on a duplicate — never inserts again (idempotent)")
        void should_beIdempotent_when_sameSalonServiceFavoritedTwice() {
            UUID salonId = UUID.randomUUID();
            when(serviceRepository.findById(targetId))
                    .thenReturn(Optional.of(salonServiceDefinition(salonId, true)));
            when(masterServiceRepository.existsBookableAssignmentForSalonService(salonId, targetId))
                    .thenReturn(true);
            Favorite existing = existingFavorite(clientId, FavoriteTargetType.SALON_SERVICE, targetId);
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.SALON_SERVICE, targetId))
                    .thenReturn(Optional.of(existing));

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, targetId);

            assertThat(response.id()).isEqualTo(existing.getId());
            verifyNoInteractions(favoritePersistenceService);
        }

        @Test
        @DisplayName("resolves a concurrent unique-violation on the SALON_SERVICE path by re-reading")
        void should_returnRacedRow_when_concurrentSalonServiceInsertHitsUniqueIndex() {
            UUID salonId = UUID.randomUUID();
            when(serviceRepository.findById(targetId))
                    .thenReturn(Optional.of(salonServiceDefinition(salonId, true)));
            when(masterServiceRepository.existsBookableAssignmentForSalonService(salonId, targetId))
                    .thenReturn(true);
            Favorite raced = existingFavorite(clientId, FavoriteTargetType.SALON_SERVICE, targetId);
            when(favoriteRepository.findByClientIdAndTargetTypeAndTargetId(
                    clientId, FavoriteTargetType.SALON_SERVICE, targetId))
                    .thenReturn(Optional.empty())
                    .thenReturn(Optional.of(raced));
            when(favoritePersistenceService.persistNew(any(Favorite.class)))
                    .thenThrow(new DataIntegrityViolationException("uq_favorite"));

            FavoriteResponse response =
                    favoriteService.addFavorite(clientId, FavoriteTargetType.SALON_SERVICE, targetId);

            assertThat(response.id()).isEqualTo(raced.getId());
        }
    }

    // ── listServiceFavorites ────────────────────────────────────────────────────

    @Nested
    @DisplayName("listServiceFavorites")
    class ListServiceFavorites {

        /**
         * Builds a raw {@code Object[]} row matching the merged native projection's column
         * layout (indices 0-14 — see {@code FavoriteRepository.findFavoriteServiceRows}'s
         * javadoc); columns 15/16 (ordering-only) are omitted since
         * {@code FavoriteServiceResponse.fromRow} never reads them.
         */
        private static Object[] masterArmRow(MasterServiceAssignment msa, String firstName,
                                             String lastName, String avatarUrl) {
            return new Object[] {
                    "MASTER", msa.getId(), msa.getMaster().getId(),
                    msa.getServiceDefinition().getId(), msa.getServiceDefinition().getName(),
                    firstName, lastName, avatarUrl,
                    msa.getServiceDefinition().getBaseDurationMinutes(),
                    msa.getServiceDefinition().getPriceType().name(),
                    msa.getServiceDefinition().getBasePrice(),
                    msa.getServiceDefinition().getPriceMax(),
                    null, null, null
            };
        }

        @Test
        @DisplayName("maps every MASTER-arm projection row to a wish-list row, sort stripped")
        void should_mapAssignmentsToWishListRows_when_clientHasServiceFavorites() {
            MasterServiceAssignment msa = assignmentOwnedBy(Role.SALON_MASTER, true, true);
            Object[] row = masterArmRow(msa, "Марія", "Левченко", "https://cdn/avatar.png");
            when(favoriteRepository.findFavoriteServiceRows(eq(clientId), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.<Object[]>of(row)));

            Page<FavoriteServiceResponse> page =
                    favoriteService.listServiceFavorites(clientId, PageRequest.of(0, 20));

            assertThat(page.getContent()).hasSize(1);
            assertThat(page.getContent().get(0))
                    .extracting(FavoriteServiceResponse::sourceType,
                            FavoriteServiceResponse::masterServiceId,
                            FavoriteServiceResponse::masterId,
                            FavoriteServiceResponse::serviceDefId,
                            FavoriteServiceResponse::serviceName,
                            FavoriteServiceResponse::masterFirstName,
                            FavoriteServiceResponse::masterLastName,
                            FavoriteServiceResponse::masterAvatarUrl,
                            FavoriteServiceResponse::durationMinutes,
                            FavoriteServiceResponse::priceDisplay,
                            FavoriteServiceResponse::salonId)
                    .containsExactly(FavoriteServiceResponse.SourceType.MASTER,
                            msa.getId(), msa.getMaster().getId(), msa.getServiceDefinition().getId(),
                            "Manicure", "Марія", "Левченко", "https://cdn/avatar.png", 60, "600 ₴",
                            null);
        }

        @Test
        @DisplayName("carries a null avatar straight through from the scalar column")
        void should_returnNullAvatar_when_projectionAvatarIsNull() {
            MasterServiceAssignment msa = assignmentOwnedBy(Role.INDEPENDENT_MASTER, true, true);
            Object[] row = masterArmRow(msa, "Олена", "Коваль", null);
            when(favoriteRepository.findFavoriteServiceRows(eq(clientId), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.<Object[]>of(row)));

            Page<FavoriteServiceResponse> page =
                    favoriteService.listServiceFavorites(clientId, PageRequest.of(0, 20));

            assertThat(page.getContent().get(0).masterAvatarUrl()).isNull();
        }

        @Test
        @DisplayName("maps a SALON-arm projection row with null master identity fields and a populated salon")
        void should_mapSalonArmRow_when_clientHasSalonServiceFavorite() {
            UUID serviceDefId = UUID.randomUUID();
            UUID salonId = UUID.randomUUID();
            Object[] row = {
                    "SALON", null, null, serviceDefId, "Pedicure",
                    null, null, null,
                    45, "FIXED", new BigDecimal("400.00"), null,
                    salonId, "Salon Bella", "https://cdn/salon.png"
            };
            when(favoriteRepository.findFavoriteServiceRows(eq(clientId), any(Pageable.class)))
                    .thenReturn(new PageImpl<>(List.<Object[]>of(row)));

            Page<FavoriteServiceResponse> page =
                    favoriteService.listServiceFavorites(clientId, PageRequest.of(0, 20));

            assertThat(page.getContent()).hasSize(1);
            FavoriteServiceResponse response = page.getContent().get(0);
            assertThat(response.sourceType()).isEqualTo(FavoriteServiceResponse.SourceType.SALON);
            assertThat(response.masterServiceId()).isNull();
            assertThat(response.masterId()).isNull();
            assertThat(response.masterFirstName()).isNull();
            assertThat(response.masterLastName()).isNull();
            assertThat(response.masterAvatarUrl()).isNull();
            assertThat(response.serviceDefId()).isEqualTo(serviceDefId);
            assertThat(response.salonId()).isEqualTo(salonId);
            assertThat(response.salonName()).isEqualTo("Salon Bella");
            assertThat(response.salonAvatarUrl()).isEqualTo("https://cdn/salon.png");
            assertThat(response.durationMinutes()).isEqualTo(45);
            assertThat(response.priceDisplay()).isEqualTo("400 ₴");
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

        /**
         * Mobile Phase 111 reshaped this row: index 7 was {@code last_service_name}, it is now the
         * first of the three OWN address columns off {@code users}; the 2026-08 re-audit appended
         * index 10, {@code masters.master_type}, which drives the address/locality SOURCE choice
         * and never reaches the DTO, and then indices 11–12, {@code salons.city_id} /
         * {@code salons.district_id}, when the locality {@code COALESCE} was replaced by the same
         * type gate (indices 4–5 are now the master's OWN locality, no longer coalesced). The
         * favourites-affiliation fix appended indices 13–17 — {@code salons.id}, {@code name},
         * {@code street}, {@code building_no}, {@code location_note} — the salon-side counterpart
         * of 7–9. The projection layout is index-matched by hand in
         * {@code FavoriteService#mapMasterRow}, so this test's literal 18-element row IS the
         * contract with {@code FavoriteRepository#findFavoriteMasterRows}.
         */
        @Test
        @DisplayName("maps every projection field including an INDEPENDENT_MASTER's own street address")
        void should_mapMasterRow_when_projectionRowIsComplete() {
            UUID masterId = UUID.randomUUID();
            UUID cityId = UUID.randomUUID();
            UUID districtId = UUID.randomUUID();
            Object[] row = {
                    masterId, "Марія", "Левченко", "https://cdn/avatar.png",
                    cityId, districtId, new BigDecimal("4.75"),
                    "вул. Хрещатик", "12Б", "код 4321", "INDEPENDENT_MASTER",
                    // 11–17: salon locality, identity and address — an independent has no salon,
                    // so the LEFT JOIN yields NULL for every one of them.
                    null, null, null, null, null, null, null
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
                            FavoriteMasterResponse::avgRating,
                            FavoriteMasterResponse::salonId, FavoriteMasterResponse::salonName,
                            FavoriteMasterResponse::street,
                            FavoriteMasterResponse::buildingNo, FavoriteMasterResponse::locationNote)
                    .as("an independent master publishes their OWN address and carries no "
                            + "affiliation — a null salonId/salonName is what the client keys the "
                            + "«works at …» line off")
                    .containsExactly(masterId, "Марія", "Левченко", "https://cdn/avatar.png",
                            "Київ", "Печерський", 4.75, null, null,
                            "вул. Хрещатик", "12Б", "код 4321");
        }

        @Test
        @DisplayName("leaves every nullable projection column null rather than substituting a default")
        void should_returnNulls_when_projectionColumnsAreNull() {
            UUID masterId = UUID.randomUUID();
            Object[] row = {masterId, "Олена", "Коваль", null, null, null, null, null, null, null,
                    "INDEPENDENT_MASTER", null, null, null, null, null, null, null};
            when(favoriteRepository.findFavoriteMasterRows(clientId)).thenReturn(List.<Object[]>of(row));
            when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                    .thenReturn(new DiscoveryLabels(Map.of(), Map.of()));

            List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

            assertThat(result.get(0).avgRating()).isNull();
            assertThat(result.get(0).cityLabel()).isNull();
            assertThat(result.get(0).street()).isNull();
            assertThat(result.get(0).buildingNo()).isNull();
            assertThat(result.get(0).locationNote()).isNull();
            assertThat(result.get(0).salonId()).isNull();
            assertThat(result.get(0).salonName()).isNull();
        }

        /**
         * The locked per-role address matrix applies to THIS surface too (2026-08 re-audit
         * MEDIUM), and the favourites-affiliation fix pins the OTHER half of it: which address a
         * salon-affiliated master's card DOES show.
         *
         * <p>The rule is one predicate, {@code MasterType#disclosesOwnAddress}, shared with
         * {@code MasterDetailResponse#fromPublic}: an INDEPENDENT_MASTER discloses their own
         * address; a SALON_MASTER / SALON_OWNER discloses their SALON's — never the employee's
         * personal one. Before the fix this surface emitted {@code users.street} for every master
         * type and left the suppression to the mobile client (not a server-side control, and for
         * a multi-salon owner that column holds the WRONG salon's street). The fix after that
         * over-corrected to nothing at all, so the card could not tell a client where to go.
         *
         * <p><b>The fixture makes the two addresses DISJOINT on purpose.</b> Every row carries a
         * distinct {@code users} address AND a distinct {@code salons} address, so each assertion
         * can fail three ways: reading the wrong column emits the master's own street, dropping
         * the branch emits {@code null}, and crossing the rows emits another salon's. A fixture
         * that reused one string for both sources would pass whichever column the mapper read.
         *
         * <p>The locality LABELS are asserted alongside because the SAME predicate picks them —
         * a fix that resolved the address through the salon but the locality through the user (or
         * vice versa) would print a card whose street and city belong to different entities.
         *
         * <p>The third row pins the no-fall-through rule for BOTH field groups: its salon has
         * neither a recorded locality nor a recorded address ({@code salons.city_id} and the
         * street triple are all nullable — {@code V54} line 62, {@code Salon} lines 82–88), and
         * the master underneath has both. Salon-or-NOTHING: those cells must come out {@code null}
         * even though indices 4/5 and 7–9 carry perfectly resolvable values. Its
         * {@code salonId}/{@code salonName} still publish — knowing WHERE someone works does not
         * depend on that salon having filled in its street.
         */
        @Test
        @DisplayName("publishes the SALON's address, identity and locality — never the employee's "
                + "own — for a salon-affiliated master (SALON_MASTER / SALON_OWNER)")
        void should_publishSalonAddress_when_masterIsSalonAffiliated() {
            UUID salonCityId = UUID.randomUUID();
            UUID salonDistrictId = UUID.randomUUID();
            UUID ownCityId = UUID.randomUUID();
            UUID ownDistrictId = UUID.randomUUID();
            UUID salonAId = UUID.randomUUID();
            UUID salonBId = UUID.randomUUID();
            UUID salonCId = UUID.randomUUID();
            // Employed, salon HAS a locality AND an address → both are published, the master's
            // own «вул. Хрещатик» is not.
            Object[] salonMaster = {UUID.randomUUID(), "Ірина", "Бондар", null,
                    ownCityId, ownDistrictId,
                    new BigDecimal("4.50"), "вул. Хрещатик", "12Б", "код 4321", "SALON_MASTER",
                    salonCityId, salonDistrictId,
                    salonAId, "Салон Bella", "вул. Володимирська", "40", "3 поверх"};
            Object[] salonOwner = {UUID.randomUUID(), "Олег", "Гриценко", null,
                    ownCityId, ownDistrictId,
                    new BigDecimal("4.90"), "вул. Січових Стрільців", "7", "2 поверх", "SALON_OWNER",
                    salonCityId, salonDistrictId,
                    salonBId, "Салон Mocha", "просп. Свободи", "15", "вхід з двору"};
            // Employed, salon has NO recorded locality and NO recorded address → nothing for
            // either group, NOT the master's own.
            Object[] localitylessSalonMaster = {UUID.randomUUID(), "Ганна", "Мороз", null,
                    ownCityId, ownDistrictId,
                    new BigDecimal("4.10"), "вул. Лесі Українки", "3", "домофон", "SALON_MASTER",
                    null, null,
                    salonCId, "Салон Nord", null, null, null};
            when(favoriteRepository.findFavoriteMasterRows(clientId))
                    .thenReturn(List.of(salonMaster, salonOwner, localitylessSalonMaster));
            // BOTH ids resolve to a label — so a fall-through to the master's own locality would
            // print «Полтава» rather than silently yielding null and passing this test anyway.
            when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                    .thenReturn(new DiscoveryLabels(
                            Map.of(salonCityId, "Київ", ownCityId, "Полтава"),
                            Map.of(salonDistrictId, "Печерський", ownDistrictId, "Київський")));

            List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

            assertThat(result)
                    .as("no row may echo the employee's own users.street — for a multi-salon owner "
                            + "that column is not even the right salon's address")
                    .extracting(FavoriteMasterResponse::street)
                    .doesNotContain("вул. Хрещатик", "вул. Січових Стрільців", "вул. Лесі Українки");
            assertThat(result.get(0))
                    .as("the employing salon's identity AND address, so the card can say where to go")
                    .extracting(FavoriteMasterResponse::salonId, FavoriteMasterResponse::salonName,
                            FavoriteMasterResponse::street, FavoriteMasterResponse::buildingNo,
                            FavoriteMasterResponse::locationNote,
                            FavoriteMasterResponse::cityLabel,
                            FavoriteMasterResponse::districtLabel)
                    .containsExactly(salonAId, "Салон Bella", "вул. Володимирська", "40",
                            "3 поверх", "Київ", "Печерський");
            assertThat(result.get(1))
                    .as("a SALON_OWNER working as a master resolves through their salon exactly "
                            + "like a SALON_MASTER — one predicate, not two")
                    .extracting(FavoriteMasterResponse::salonId, FavoriteMasterResponse::salonName,
                            FavoriteMasterResponse::street, FavoriteMasterResponse::buildingNo,
                            FavoriteMasterResponse::locationNote,
                            FavoriteMasterResponse::cityLabel,
                            FavoriteMasterResponse::districtLabel)
                    .containsExactly(salonBId, "Салон Mocha", "просп. Свободи", "15",
                            "вхід з двору", "Київ", "Печерський");
            assertThat(result.get(2))
                    .as("salon-or-NOTHING for BOTH groups: a salon with no recorded address or "
                            + "city must not fall through to the employee's own «вул. Лесі "
                            + "Українки» / «Полтава»")
                    .extracting(FavoriteMasterResponse::street, FavoriteMasterResponse::buildingNo,
                            FavoriteMasterResponse::locationNote,
                            FavoriteMasterResponse::cityLabel,
                            FavoriteMasterResponse::districtLabel)
                    .containsOnlyNulls();
            assertThat(result.get(2))
                    .as("…but the affiliation still publishes — knowing WHERE someone works does "
                            + "not depend on that salon having filled in its street")
                    .extracting(FavoriteMasterResponse::salonId, FavoriteMasterResponse::salonName)
                    .containsExactly(salonCId, "Салон Nord");
            assertThat(result).extracting(FavoriteMasterResponse::avgRating)
                    .containsExactly(4.50, 4.90, 4.10);
        }

        /**
         * Fail-closed branch. A {@code NULL} or unrecognised {@code master_type} (an enum constant
         * an older instance does not know, mid-rolling-deploy) must take the SALON branch rather
         * than default to disclosing the master's own row — and must not throw
         * {@link IllegalArgumentException} out of a read endpoint as a 500.
         *
         * <p>Both fixture rows carry a populated {@code users} address and NULL salon columns, so
         * "fails closed" is observable as {@code null} on the wire while the own-row values sit
         * right there at indices 7–9 for a mis-branch to grab.
         */
        @Test
        @DisplayName("suppresses the address and does not throw when master_type is null or unknown")
        void should_suppressAddress_when_masterTypeIsUnrecognised() {
            Object[] nullType = {UUID.randomUUID(), "А", "А", null, null, null, null,
                    "вул. Тестова", "1", "нотатка", null,
                    null, null, null, null, null, null, null};
            Object[] unknownType = {UUID.randomUUID(), "Б", "Б", null, null, null, null,
                    "вул. Тестова", "2", "нотатка", "FUTURE_MASTER_TYPE",
                    null, null, null, null, null, null, null};
            when(favoriteRepository.findFavoriteMasterRows(clientId))
                    .thenReturn(List.of(nullType, unknownType));
            when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                    .thenReturn(new DiscoveryLabels(Map.of(), Map.of()));

            List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

            assertThat(result)
                    .as("unknown master type fails CLOSED — a missing street line is recoverable, "
                            + "a leaked one is not")
                    .allSatisfy(r -> assertThat(r)
                            .extracting(FavoriteMasterResponse::street,
                                    FavoriteMasterResponse::buildingNo,
                                    FavoriteMasterResponse::locationNote,
                                    FavoriteMasterResponse::salonId,
                                    FavoriteMasterResponse::salonName)
                            .containsOnlyNulls());
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
            Object[] r1 = {UUID.randomUUID(), "A", "A", null, cityId, null, null, null, null, null,
                    "INDEPENDENT_MASTER", null, null, null, null, null, null, null};
            Object[] r2 = {UUID.randomUUID(), "B", "B", null, null, null, null, null, null, null,
                    "SALON_MASTER", cityId, null,
                    UUID.randomUUID(), "Салон Bella", null, null, null};
            Object[] r3 = {UUID.randomUUID(), "C", "C", null, null, null, null, null, null, null,
                    "SALON_OWNER", cityId, null,
                    UUID.randomUUID(), "Салон Mocha", null, null, null};
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

        /**
         * Mobile Phase 111 extended this row from 6 to 9 columns (street/buildingNo/locationNote)
         * and swapped index 5's source from a live {@code AVG(reviews.rating)} aggregate to the
         * persisted {@code salons.avg_rating}. The mapper is index-matched by hand, so this
         * literal row IS the contract with {@code FavoriteRepository#findFavoriteSalonRows}.
         */
        @Test
        @DisplayName("maps every salon projection field including the salon's street address")
        void should_mapSalonRow_when_salonReviewed() {
            UUID salonId = UUID.randomUUID();
            UUID cityId = UUID.randomUUID();
            UUID districtId = UUID.randomUUID();
            // salons.avg_rating is a Postgres numeric → mapped via Number.doubleValue().
            Object[] row = {salonId, "Salon Bella", "https://cdn/s.png",
                    cityId, districtId, new BigDecimal("4.20"),
                    "вул. Дерибасівська", "7", "2-й поверх"};
            when(favoriteRepository.findFavoriteSalonRows(clientId)).thenReturn(List.<Object[]>of(row));
            when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                    .thenReturn(new DiscoveryLabels(
                            Map.of(cityId, "Одеса"), Map.of(districtId, "Приморський")));

            List<FavoriteSalonResponse> result = favoriteService.listSalonFavorites(clientId);

            assertThat(result.get(0))
                    .extracting(FavoriteSalonResponse::salonId, FavoriteSalonResponse::name,
                            FavoriteSalonResponse::avatarUrl, FavoriteSalonResponse::cityLabel,
                            FavoriteSalonResponse::districtLabel, FavoriteSalonResponse::avgRating,
                            FavoriteSalonResponse::street, FavoriteSalonResponse::buildingNo,
                            FavoriteSalonResponse::locationNote)
                    .containsExactly(salonId, "Salon Bella", "https://cdn/s.png",
                            "Одеса", "Приморський", 4.20,
                            "вул. Дерибасівська", "7", "2-й поверх");
        }

        /**
         * The query — not the mapper — is what turns a {@code review_count = 0} salon's persisted
         * {@code 0.00} into {@code NULL} (see the {@code CASE WHEN} in
         * {@code findFavoriteSalonRows}), so the row this test feeds already carries {@code null}
         * at index 5. What is pinned here is that the mapper does not substitute a default.
         */
        @Test
        @DisplayName("leaves avgRating and the address columns null when the projection sends null")
        void should_returnNullRating_when_salonNeverReviewed() {
            UUID salonId = UUID.randomUUID();
            Object[] row = {salonId, "New Salon", null, null, null, null, null, null, null};
            when(favoriteRepository.findFavoriteSalonRows(clientId)).thenReturn(List.<Object[]>of(row));
            when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                    .thenReturn(new DiscoveryLabels(Map.of(), Map.of()));

            List<FavoriteSalonResponse> result = favoriteService.listSalonFavorites(clientId);

            assertThat(result.get(0).avgRating()).isNull();
            assertThat(result.get(0).street()).isNull();
            assertThat(result.get(0).buildingNo()).isNull();
            assertThat(result.get(0).locationNote()).isNull();
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

    /**
     * The CATEGORY AXIS — the list the approved favourites design's chips filter on, present
     * identically on both DTOs so one chip row filters the whole screen. Reversed from a
     * single, client-booking-derived pair to a list of every category the provider actually
     * OFFERS; see {@link FavoriteCategoryResolver}'s class javadoc for the full rationale.
     *
     * <h4>Fixture discipline: every row gets a DIFFERENT category</h4>
     * Each test below gives its providers distinct {@code (code, label)} pairs and asserts each
     * row receives ITS OWN. That is what makes the assertions falsifiable: a mapper that keyed
     * the lookup off the wrong row, resolved the whole page to the first match, or crossed the
     * master and salon arms would emit a visibly different result rather than the same one
     * everywhere. A fixture that reused one category for the page would pass under all three of
     * those bugs.
     */
    @Nested
    @DisplayName("category axis (categories)")
    class CategoryAxis {

        /** A minimal 18-column masters projection row — only index 0 and the type gate matter here. */
        private Object[] masterRow(UUID masterId, String masterType) {
            return new Object[] {
                    masterId, "Марія", "Левченко", null, null, null, null,
                    null, null, null, masterType,
                    null, null, null, null, null, null, null
            };
        }

        /** A minimal 9-column salons projection row — only index 0 matters here. */
        private Object[] salonRow(UUID salonId) {
            return new Object[] {salonId, "Salon", null, null, null, null, null, null, null};
        }

        private void noLocalityLabels() {
            when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                    .thenReturn(new DiscoveryLabels(Map.of(), Map.of()));
        }

        @Test
        @DisplayName("stamps each master's OWN categories, never another row's")
        void should_stampPerMasterCategories_when_pageHasDistinctCategories() {
            UUID nails = UUID.randomUUID();
            UUID hair = UUID.randomUUID();
            UUID brows = UUID.randomUUID();
            when(favoriteRepository.findFavoriteMasterRows(clientId)).thenReturn(List.<Object[]>of(
                    masterRow(nails, "INDEPENDENT_MASTER"),
                    masterRow(hair, "INDEPENDENT_MASTER"),
                    masterRow(brows, "INDEPENDENT_MASTER")));
            noLocalityLabels();
            when(favoriteCategoryResolver.resolveForMasters(anyCollection()))
                    .thenReturn(new FavoriteCategories(Map.of(
                            nails, List.of(new FavoriteCategoryView("MANICURE", "Манікюр")),
                            hair, List.of(new FavoriteCategoryView("HAIRCUT", "Стрижка")),
                            brows, List.of(new FavoriteCategoryView("BROWS", "Брови")))));

            List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

            assertThat(result)
                    .as("each card carries the categories of ITS OWN offering; a lookup keyed "
                            + "off the wrong row would repeat one list down the page")
                    .extracting(FavoriteMasterResponse::masterId, FavoriteMasterResponse::categories)
                    .containsExactly(
                            tuple(nails, List.of(new FavoriteCategoryView("MANICURE", "Манікюр"))),
                            tuple(hair, List.of(new FavoriteCategoryView("HAIRCUT", "Стрижка"))),
                            tuple(brows, List.of(new FavoriteCategoryView("BROWS", "Брови"))));
        }

        @Test
        @DisplayName("stamps each salon's OWN categories")
        void should_stampPerSalonCategories_when_pageHasDistinctCategories() {
            UUID makeupSalon = UUID.randomUUID();
            UUID lashSalon = UUID.randomUUID();
            UUID emptySalon = UUID.randomUUID();
            when(favoriteRepository.findFavoriteSalonRows(clientId)).thenReturn(List.<Object[]>of(
                    salonRow(makeupSalon), salonRow(lashSalon), salonRow(emptySalon)));
            when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                    .thenReturn(new DiscoveryLabels(Map.of(), Map.of()));
            when(favoriteCategoryResolver.resolveForSalons(anyCollection()))
                    .thenReturn(new FavoriteCategories(Map.of(
                            makeupSalon, List.of(new FavoriteCategoryView("MAKEUP", "Макіяж")),
                            lashSalon, List.of(new FavoriteCategoryView("EYELASH", "Вії")))));

            List<FavoriteSalonResponse> result = favoriteService.listSalonFavorites(clientId);

            assertThat(result)
                    .as("the design puts BOTH kinds on one axis; the third salon offers nothing "
                            + "categorisable and gets an empty list, never null")
                    .extracting(FavoriteSalonResponse::salonId, FavoriteSalonResponse::categories)
                    .containsExactly(
                            tuple(makeupSalon, List.of(new FavoriteCategoryView("MAKEUP", "Макіяж"))),
                            tuple(lashSalon, List.of(new FavoriteCategoryView("EYELASH", "Вії"))),
                            tuple(emptySalon, List.of()));
        }

        /**
         * The empty case the list is EMPTY-FOR, not the high-null-rate case the old singular
         * contract had: a provider with no active categorisable service publishes an empty list,
         * never {@code null} — the client iterates directly with no null-check of its own.
         */
        @Test
        @DisplayName("categories is EMPTY, never null, when the master offers nothing categorisable")
        void should_returnEmptyCategories_when_masterOffersNothing() {
            UUID masterId = UUID.randomUUID();
            when(favoriteRepository.findFavoriteMasterRows(clientId))
                    .thenReturn(List.<Object[]>of(masterRow(masterId, "INDEPENDENT_MASTER")));
            noLocalityLabels();
            when(favoriteCategoryResolver.resolveForMasters(anyCollection()))
                    .thenReturn(FavoriteCategories.empty());

            List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

            assertThat(result.get(0).categories()).isNotNull().isEmpty();
        }

        /**
         * The category axis is keyed on the MASTER even when every other "where do I find them"
         * field on the card resolves through the employing salon. The two field groups answer
         * different questions — "what does this PERSON do" vs. "where are they" — and a mapper
         * that reused {@code disclosesOwnAddress} for the category, or keyed the lookup off
         * {@code row[13]} (salon id), would hand this card the salon's categories.
         *
         * <p>The fixture makes that bug visible: the salon id is ALSO present in the category
         * map, under a different category. Reading the wrong key returns «Макіяж» instead of
         * «Манікюр» rather than returning empty, so the test fails on a wrong value, not on an
         * absence that a null-safe mapper could mask.
         */
        @Test
        @DisplayName("keys the categories on the MASTER, not on the employing salon")
        void should_keyCategoriesOnMaster_when_masterIsSalonAffiliated() {
            UUID masterId = UUID.randomUUID();
            UUID salonId = UUID.randomUUID();
            Object[] row = masterRow(masterId, "SALON_MASTER");
            row[13] = salonId;
            row[14] = "Salon Bella";
            when(favoriteRepository.findFavoriteMasterRows(clientId)).thenReturn(List.<Object[]>of(row));
            noLocalityLabels();
            when(favoriteCategoryResolver.resolveForMasters(anyCollection()))
                    .thenReturn(new FavoriteCategories(Map.of(
                            masterId, List.of(new FavoriteCategoryView("MANICURE", "Манікюр")),
                            salonId, List.of(new FavoriteCategoryView("MAKEUP", "Макіяж")))));

            List<FavoriteMasterResponse> result = favoriteService.listMasterFavorites(clientId);

            assertThat(result.get(0))
                    .as("the card resolves its ADDRESS through the salon but its CATEGORIES "
                            + "through the master — reading row[13] instead would say Макіяж")
                    .extracting(FavoriteMasterResponse::salonId, FavoriteMasterResponse::categories)
                    .containsExactly(salonId, List.of(new FavoriteCategoryView("MANICURE", "Манікюр")));
        }

        /**
         * §E no N+1. The whole point of this axis being a separate statement is that it is
         * resolved ONCE for the page; the deleted {@code lastServiceName} {@code LATERAL} it
         * replaces cost 9.11 ms a page precisely because it ran per row.
         *
         * <p>Asserting the call COUNT alone would not catch a per-row implementation that
         * batched by accident, so the captured argument is asserted to contain EVERY id on the
         * page — a resolver invoked once with only the first id is the same bug wearing a
         * batch's clothes.
         */
        @Test
        @DisplayName("resolves the whole page's categories in ONE call carrying every provider id")
        void should_batchCategoryResolution_when_pageHasManyMasters() {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            UUID third = UUID.randomUUID();
            when(favoriteRepository.findFavoriteMasterRows(clientId)).thenReturn(List.<Object[]>of(
                    masterRow(first, "INDEPENDENT_MASTER"),
                    masterRow(second, "INDEPENDENT_MASTER"),
                    masterRow(third, "INDEPENDENT_MASTER")));
            noLocalityLabels();

            favoriteService.listMasterFavorites(clientId);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> ids = ArgumentCaptor.forClass(Collection.class);
            verify(favoriteCategoryResolver, times(1)).resolveForMasters(ids.capture());
            assertThat(ids.getValue())
                    .as("one call for the page, carrying every id on it — not one call per row, "
                            + "and not one call carrying a single id")
                    .containsExactlyInAnyOrder(first, second, third);
        }

        /**
         * Arm separation. The two arms key on different collaborator methods — a salon listing
         * must route to {@code resolveForSalons} and never to {@code resolveForMasters}, and
         * vice versa. Unlike the booking-history axis this replaced, there is no client-scoping
         * concern left to pin here: "what a provider offers" is not a fact about the asking
         * client, which is why {@code resolveForSalons}/{@code resolveForMasters} no longer take
         * a {@code clientId} argument at all.
         */
        @Test
        @DisplayName("routes a salon listing to the salon resolver method and never the master one")
        void should_useSalonResolverMethod_when_listingSalons() {
            UUID salonId = UUID.randomUUID();
            when(favoriteRepository.findFavoriteSalonRows(clientId))
                    .thenReturn(List.<Object[]>of(salonRow(salonId)));
            when(discoveryLocationResolver.resolveLabels(anyCollection(), anyCollection()))
                    .thenReturn(new DiscoveryLabels(Map.of(), Map.of()));

            favoriteService.listSalonFavorites(clientId);

            verify(favoriteCategoryResolver).resolveForSalons(anyCollection());
            verify(favoriteCategoryResolver, never()).resolveForMasters(anyCollection());
        }

        @Test
        @DisplayName("never touches the category resolver for an empty page")
        void should_skipCategoryResolution_when_pageIsEmpty() {
            when(favoriteRepository.findFavoriteMasterRows(clientId)).thenReturn(List.of());

            favoriteService.listMasterFavorites(clientId);

            verifyNoInteractions(favoriteCategoryResolver);
        }
    }
}
