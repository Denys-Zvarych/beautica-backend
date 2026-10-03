package com.beautica.salon;

import com.beautica.TestConstants;
import static org.mockito.Mockito.verifyNoInteractions;
import com.beautica.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.data.domain.Sort;
import org.springframework.data.domain.PageRequest;
import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.dto.MasterSummaryResponse;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
import com.beautica.common.exception.SalonDeletionBlockedException;
import com.beautica.salon.audit.StaffClientReferenceAuditResult;
import com.beautica.salon.audit.StaffClientReferenceType;
import com.beautica.salon.audit.StaffClientReferenceViolation;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SalonService — unit")
class SalonServiceTest {

    /**
     * {@code deactivateSalon}'s transaction timeout was purely declarative — no test read it, so a
     * refactor that dropped or defaulted it would have shipped silently. It is not decoration: this
     * one transaction runs the phase 289 audit, the phase 293 decline cascade, N master
     * detach/delete statements and a bulk {@code DELETE FROM users}, and it is the only method in
     * this service that holds row locks across all of them. Without a bound, a pathological salon
     * pins a Neon connection indefinitely; with it, the client gets a failure and the destructive
     * half rolls back whole.
     *
     * <p>Reflection on the annotation rather than a behavioural test on purpose — Spring's
     * declarative timeout is applied by the proxy, and any test that could observe it firing would
     * have to actually stall a real transaction for 30 seconds.
     */
    @Test
    @DisplayName("should_boundTheDeletionTransaction_when_deactivateSalonIsDeclared")
    void should_boundTheDeletionTransaction_when_deactivateSalonIsDeclared() throws Exception {
        var annotation = SalonService.class
                .getMethod("deactivateSalon", UUID.class, UUID.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class);

        assertThat(annotation)
                .as("deactivateSalon must stay @Transactional — the audit, the decline cascade "
                        + "and the users delete are all-or-nothing")
                .isNotNull();
        assertThat(annotation.timeout())
                .as("the salon-deletion transaction must stay explicitly bounded at 30s, not fall "
                        + "back to the -1 platform default")
                .isEqualTo(30);
        assertThat(annotation.readOnly())
                .as("this is the most destructive mutation in the service — readOnly would make "
                        + "the whole cascade a silent no-op on some drivers")
                .isFalse();
    }

    @Mock
    private SalonRepository salonRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private InviteService inviteService;

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private com.beautica.location.LocalityWriteValidator localityWriteValidator;

    @Mock
    private MasterService masterService;

    @Mock
    // CacheManager: post-commit eviction uses TransactionSynchronizationManager,
    // which is inactive under MockitoExtension — tested via integration test.
    private CacheManager cacheManager;

    // CRITICAL: must be declared so @InjectMocks can satisfy the CityRepository constructor
    // parameter — without it the field receives null (used by the batch
    // resolveOblastIdsByCityIds sibling for getOwnerSalons; the single-row resolveOblastId path
    // no longer touches this mock — see locationQueryService below).
    @Mock
    private com.beautica.location.repository.CityRepository cityRepository;

    // Shared city/region label lookup (salon + owner denorm). Declared so @InjectMocks does not
    // pass null; the default Optional.empty() stub is a no-op resolution.
    @Mock
    private com.beautica.location.SettlementDisplayNameResolver settlementDisplayNameResolver;

    // Phase 240 perf MEDIUM fix: resolveOblastId now delegates to the shared cached resolver
    // (LocationQueryService#resolveCityOblastId) instead of calling CityRepository directly —
    // must be declared so @InjectMocks can satisfy the constructor parameter (mirrors
    // MasterServiceTest).
    @Mock
    private com.beautica.location.service.LocationQueryService locationQueryService;

    // Audit-fix cycle 2: SalonService evicts the affected user's cached profile after commit
    // (createSalon, removeAdmin, rotateAdmin all mutate a `users` row). @InjectMocks passes null
    // for an UNDECLARED collaborator silently, so compileTestJava stays green and the omission
    // only surfaces as an NPE at runtime — this field must exist even when no test here reaches
    // an evict call.
    @Mock
    private com.beautica.common.cache.UserProfileCacheEvictor userProfileCacheEvictor;

    // Phase 290: SalonService now constructor-depends on the salon-deletion staff-deactivation
    // cascade's five collaborators. @InjectMocks passes null for an UNDECLARED collaborator
    // silently, so compileTestJava stays green and the omission only surfaces as an NPE at
    // runtime — declared even though only two tests below actually exercise deactivateSalon's
    // new code (every other test's deactivateSalon call throws before reaching it).
    @Mock
    private com.beautica.salon.service.StaffClientReferenceAuditService staffClientReferenceAuditService;

    @Mock
    private com.beautica.auth.TokensValidAfterCache tokensValidAfterCache;

    // Phase 293: SalonService now constructor-depends on BookingService for the salon-closure
    // booking cascade (deactivateSalon calls declineFutureConfirmedBookingsForSalonClosure).
    // @InjectMocks passes null for an UNDECLARED collaborator silently, so compileTestJava stays
    // green and the omission only surfaces as an NPE at runtime — declared even though the call
    // is a void no-op here (every test that reaches it asserts on the surrounding behaviour).
    @Mock
    private com.beautica.booking.service.BookingService bookingService;

    // Phase 268: SalonService now constructor-depends on the salon-deletion
    // catalogue/favourites/media cascade's five collaborators. @InjectMocks passes null for an
    // UNDECLARED collaborator silently, so compileTestJava stays green and the omission only
    // surfaces as an NPE at runtime (as it did for deactivateSalon here before these were added).
    @Mock
    private com.beautica.service.repository.ServiceRepository serviceRepository;

    @Mock
    private com.beautica.favorite.repository.FavoriteRepository favoriteRepository;

    @Mock
    private com.beautica.media.repository.MediaRepository mediaRepository;

    @Mock
    private com.beautica.media.service.MediaService mediaService;

    // Phase 342: deactivateSalon hands the uploaded-photo keys (read from the row-locked catalogue)
    // to the shared purger after commit.
    @Mock
    private com.beautica.service.service.ServicePhotoBlobPurger servicePhotoBlobPurger;

    @Mock
    private org.springframework.transaction.PlatformTransactionManager transactionManager;

    // Phase 301: SalonService now constructor-depends on the promoted staff-account
    // hard-delete seam. None of these tests exercise a path that dereferences it, so a plain
    // mock satisfies the constructor without any stubbing.
    @Mock
    private com.beautica.salon.service.StaffAccountDisposalService staffAccountDisposalService;

    @InjectMocks
    private SalonService salonService;

    @Test
    @DisplayName("createSalon — saves and returns SalonResponse when owner already has one salon")
    void should_createSalon_when_ownerAlreadyHasOneSalon() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        var request = new CreateSalonRequest("Second Salon", null, "Kyiv", null, null, null, null, null, null, null, null, null);
        var savedSalon = buildSalon(UUID.randomUUID(), owner, "Second Salon");

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(true);
        when(salonRepository.save(any(Salon.class))).thenReturn(savedSalon);

        SalonResponse response = salonService.createSalon(ownerId, request);

        assertThat(response.name()).isEqualTo("Second Salon");
        assertThat(response.ownerId()).isEqualTo(ownerId);
        verify(salonRepository).save(any(Salon.class));
        // cityId is null in the request above — the locality sync guard must not fire
        assertThat(owner.getCityId()).isNull();
        verify(userRepository, never()).save(any(User.class));
        // second-salon path must NOT trigger master auto-creation
        verify(masterService, never()).createMasterForOwner(any(User.class), any(Salon.class));
    }

    @Test
    @DisplayName("createSalon — syncs locality fields to owner and saves owner when cityId is provided")
    void should_syncLocationToOwner_when_createSalonWithCityId() {
        // Arrange
        UUID ownerId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        // Field order: name, description, city, region, address, phone, instagramUrl,
        //              cityId, districtId, street, buildingNo, locationNote
        var request = new CreateSalonRequest(
                "Geo Salon", null, null, null, null, null, null,
                cityId, districtId, "Shevchenka St", "5A", "2nd floor"
        );
        var savedSalon = buildSalon(UUID.randomUUID(), owner, "Geo Salon");

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(false);
        when(salonRepository.save(any(Salon.class))).thenReturn(savedSalon);

        // Act
        salonService.createSalon(ownerId, request);

        // Assert — locality fields mirrored onto the owner entity
        assertThat(owner.getCityId()).isEqualTo(cityId);
        assertThat(owner.getStreet()).isEqualTo("Shevchenka St");
        assertThat(owner.getBuildingNo()).isEqualTo("5A");
        assertThat(owner.getLocationNote()).isEqualTo("2nd floor");
        assertThat(owner.getDistrictId()).isEqualTo(districtId);
        // No save(): `owner` is managed (loaded under the create lock in this transaction), so
        // dirty-checking flushes the sync — an explicit save was a redundant write (INFO-3).
        verify(userRepository, never()).save(any(User.class));
        // Locality validation must have been invoked
        verify(localityWriteValidator).validateProviderLocality(request.toLocalityInput());
        // first-salon path (existsByOwnerId=false) must trigger master auto-creation
        verify(masterService).createMasterForOwner(owner, savedSalon);
    }

    // ── createSalon / updateSalon — resolveOblastId (mirrors MasterServiceTest) ────────
    // The oblastId surfaced on SalonResponse is DERIVED from savedSalon.getCityId() at read
    // time (never stored) — see SalonResponse#from(Salon, UUID) and
    // SalonService#resolveOblastId(UUID). Neither direction was previously value-asserted:
    // every prior test in this package either left cityId null or never read
    // response.oblastId(), so a broken resolveOblastId (e.g. always null, or wired to the
    // wrong repository method) passed every existing test.

    @Test
    @DisplayName("createSalon — resolves oblastId from the saved salon's cityId")
    void should_resolveOblastId_when_createSalonWithCityId() {
        UUID ownerId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        UUID oblastId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        var request = new CreateSalonRequest("Geo Salon", null, null, null, null, null, null,
                cityId, null, null, null, null);
        var savedSalon = Salon.builder()
                .owner(owner)
                .name("Geo Salon")
                .isActive(true)
                .cityId(cityId)
                .build();
        ReflectionTestUtils.setField(savedSalon, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(savedSalon, "createdAt", Instant.now());

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(true);
        when(salonRepository.save(any(Salon.class))).thenReturn(savedSalon);
        when(locationQueryService.resolveCityOblastId(cityId)).thenReturn(oblastId);

        SalonResponse response = salonService.createSalon(ownerId, request);

        assertThat(response.cityId()).isEqualTo(cityId);
        assertThat(response.oblastId())
                .as("oblastId must resolve to the real parent oblast of the salon's cityId")
                .isEqualTo(oblastId);
        verify(locationQueryService).resolveCityOblastId(cityId);
    }

    @Test
    @DisplayName("createSalon — leaves oblastId null when the saved salon has no cityId")
    void should_returnNullOblastId_when_createSalonWithoutCityId() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        var request = new CreateSalonRequest("No Geo Salon", null, null, null, null, null, null,
                null, null, null, null, null);
        var savedSalon = buildSalonNoCity(UUID.randomUUID(), owner, "No Geo Salon");

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(true);
        when(salonRepository.save(any(Salon.class))).thenReturn(savedSalon);

        SalonResponse response = salonService.createSalon(ownerId, request);

        assertThat(response.oblastId()).isNull();
        // CRITICAL guard-branch assertion (Q6): the cityId-null fast path must never hit the DB.
        verify(locationQueryService, never()).resolveCityOblastId(any());
    }

    @Test
    @DisplayName("updateSalon — resolves oblastId from the patched salon's cityId")
    void should_resolveOblastId_when_updateSalonSetsCityId() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        UUID oblastId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Old Name");

        var request = new UpdateSalonRequest("Old Name", null, null, null, null,
                cityId, null, null, null, null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(locationQueryService.resolveCityOblastId(cityId)).thenReturn(oblastId);

        SalonResponse response = salonService.updateSalon(ownerId, salonId, request);

        assertThat(response.oblastId())
                .as("oblastId must resolve to the real parent oblast of the patched cityId")
                .isEqualTo(oblastId);
        verify(locationQueryService).resolveCityOblastId(cityId);
    }

    @Test
    @DisplayName("updateSalon — leaves oblastId null when the patched cityId does not resolve to a known city")
    void should_returnNullOblastId_when_updateSalonCityIdUnresolvable() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Old Name");

        var request = new UpdateSalonRequest("Old Name", null, null, null, null,
                cityId, null, null, null, null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(locationQueryService.resolveCityOblastId(cityId)).thenReturn(null);

        SalonResponse response = salonService.updateSalon(ownerId, salonId, request);

        assertThat(response.oblastId())
                .as("an orphaned/unresolvable cityId must degrade to null oblastId, never throw")
                .isNull();
        verify(locationQueryService).resolveCityOblastId(cityId);
    }

    // ── createSalon — Phase 20.x instagram widened validation + normalisation ──
    // The @Pattern on instagramUrl was widened to accept a bare handle (@username)
    // in addition to the pre-existing full instagram.com URL. SalonService.normalizeInstagram
    // strips a leading @ before persistence, mirroring UserService.normalizeInstagram for the
    // equivalent independent-master case. Before this pair of tests, no test in the salon
    // package ever set instagramUrl to a non-null value — the widen had zero app-level coverage.

    @Test
    @DisplayName("createSalon — strips the leading at-sign from a bare instagram handle before persisting")
    void should_normaliseInstagramHandle_when_createSalonWithAtPrefixedHandle() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        // Field order: name, description, city, region, address, phone, instagramUrl,
        //              cityId, districtId, street, buildingNo, locationNote
        var request = new CreateSalonRequest(
                "Handle Salon", null, null, null, null, null, "@some.handle",
                null, null, null, null, null
        );
        var savedSalon = buildSalon(UUID.randomUUID(), owner, "Handle Salon");

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(true);
        when(salonRepository.save(any(Salon.class))).thenReturn(savedSalon);

        salonService.createSalon(ownerId, request);

        ArgumentCaptor<Salon> captor = ArgumentCaptor.forClass(Salon.class);
        verify(salonRepository).save(captor.capture());
        assertThat(captor.getValue().getInstagramUrl())
                .as("leading @ must be stripped before persistence, mirroring UserService.normalizeInstagram")
                .isEqualTo("some.handle");
    }

    @Test
    @DisplayName("createSalon — persists a full instagram.com URL verbatim (regression guard for pre-widen behavior)")
    void should_persistFullInstagramUrlVerbatim_when_createSalonWithFullUrl() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        var request = new CreateSalonRequest(
                "URL Salon", null, null, null, null, null, "https://instagram.com/some.handle",
                null, null, null, null, null
        );
        var savedSalon = buildSalon(UUID.randomUUID(), owner, "URL Salon");

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(true);
        when(salonRepository.save(any(Salon.class))).thenReturn(savedSalon);

        salonService.createSalon(ownerId, request);

        ArgumentCaptor<Salon> captor = ArgumentCaptor.forClass(Salon.class);
        verify(salonRepository).save(captor.capture());
        assertThat(captor.getValue().getInstagramUrl())
                .as("a full instagram.com URL has no leading @ to strip and must be stored unchanged")
                .isEqualTo("https://instagram.com/some.handle");
    }

    @Test
    @DisplayName("getOwnerSalons — returns all salons mapped to SalonResponse when owner has multiple salons")
    void should_getOwnerSalons_when_ownerHasMultipleSalons() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        var salon1 = buildSalon(UUID.randomUUID(), owner, "Salon Alpha");
        var salon2 = buildSalon(UUID.randomUUID(), owner, "Salon Beta");

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(List.of(salon1, salon2));

        List<SalonResponse> responses = salonService.getOwnerSalons(ownerId);

        assertThat(responses).hasSize(2);
        assertThat(responses).extracting(SalonResponse::name)
                .containsExactlyInAnyOrder("Salon Alpha", "Salon Beta");
        assertThat(responses).allMatch(r -> ownerId.equals(r.ownerId()));
        verify(salonRepository).findAllByOwnerIdAndIsActiveTrue(ownerId);
    }

    @Test
    @DisplayName("createSalon — throws ForbiddenException when user is not SALON_OWNER")
    void should_throwForbidden_when_userIsNotSalonOwner() {
        UUID userId = UUID.randomUUID();
        User client = buildUser(userId, "client@beautica.com", Role.CLIENT);
        var request = new CreateSalonRequest("My Salon", null, null, null, null, null, null, null, null, null, null, null);

        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(client));

        assertThatThrownBy(() -> salonService.createSalon(userId, request))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only SALON_OWNER may create a salon");

        verify(salonRepository, never()).save(any());
    }

    @Test
    @DisplayName("getSalonEntity — throws NotFoundException when salonId does not exist")
    void should_throwNotFound_when_getSalonEntityWithUnknownId() {
        UUID salonId = UUID.randomUUID();

        when(salonRepository.findByIdAndIsActiveTrueWithOwner(salonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salonService.getSalonEntity(salonId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Salon not found");
    }

    // ── getPublicSalon — oblastId resolution on the permitAll GET /salons/{salonId} path ──
    // Follow-up to the createSalon/updateSalon coverage above: PublicSalonResponse#oblastId is
    // stranded unless THIS resolution path (the one endpoint the mobile owner/admin management
    // screen actually loads through) is independently value-pinned in both directions.

    @Test
    @DisplayName("getPublicSalon — resolves oblastId from the salon's cityId")
    void should_resolveOblastId_when_getPublicSalonWithCityId() {
        UUID salonId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        UUID oblastId = UUID.randomUUID();
        User owner = buildUser(UUID.randomUUID(), "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = Salon.builder()
                .owner(owner)
                .name("Geo Salon")
                .isActive(true)
                .cityId(cityId)
                .build();
        ReflectionTestUtils.setField(salon, "id", salonId);
        ReflectionTestUtils.setField(salon, "createdAt", Instant.now());

        when(salonRepository.findByIdAndIsActiveTrueWithOwner(salonId)).thenReturn(Optional.of(salon));
        when(locationQueryService.resolveCityOblastId(cityId)).thenReturn(oblastId);

        var response = salonService.getPublicSalon(salonId);

        assertThat(response.cityId()).isEqualTo(cityId);
        assertThat(response.oblastId())
                .as("oblastId must resolve to the real parent oblast of the salon's cityId")
                .isEqualTo(oblastId);
        verify(locationQueryService).resolveCityOblastId(cityId);
    }

    @Test
    @DisplayName("getPublicSalon — leaves oblastId null when the salon has no cityId")
    void should_returnNullOblastId_when_getPublicSalonWithoutCityId() {
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(UUID.randomUUID(), "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalonNoCity(salonId, owner, "No Geo Salon");

        when(salonRepository.findByIdAndIsActiveTrueWithOwner(salonId)).thenReturn(Optional.of(salon));

        var response = salonService.getPublicSalon(salonId);

        assertThat(response.oblastId()).isNull();
        // CRITICAL guard-branch assertion (Q6, mirrored): the cityId-null fast path must never
        // hit the DB.
        verify(locationQueryService, never()).resolveCityOblastId(any());
    }

    @Test
    @DisplayName("updateSalon — writes taxonomy locality (city_id/district_id/address) and stops writing legacy city/region/address")
    void should_updateSalon_when_ownerPatchesSalon() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Old Name");

        // Legacy free-text "Lviv" is supplied but Phase 10.6 must NOT persist it.
        var request = new UpdateSalonRequest("New Name", null, "Lviv", null, null,
                cityId, districtId, "Shevchenka St", "12", "Near the park", null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        // No save() stub: `salon` is a managed entity in-tx; dirty-checking flushes on commit,
        // so updateSalon no longer calls salonRepository.save() (PERF-LOW redundant-write drop).
        // localityWriteValidator is a mock — validateProviderLocality is a no-op (valid input).

        SalonResponse response = salonService.updateSalon(ownerId, salonId, request);

        assertThat(response.name()).isEqualTo("New Name");
        assertThat(response.cityId()).isEqualTo(cityId);
        assertThat(response.districtId()).isEqualTo(districtId);
        assertThat(response.street()).isEqualTo("Shevchenka St");
        assertThat(salon.getCity()).isNull();
        verify(localityWriteValidator).validateProviderLocality(request.toLocalityInput());
    }

    // ── salons.city/region + owner denormalisation (Phase 346 blocker) ─────────────────
    // SalonResponse.city/region read the salon columns verbatim and mobile seeds «Населений
    // пункт» from city, so every cityId write must re-derive the labels from the taxonomy —
    // never from the request's legacy free text. The owner row synced on create gets the same
    // labels UserService writes on a profile cityId write (shared SettlementDisplayNameResolver).

    @Test
    @DisplayName("createSalon — persists settlement city + oblast region on the salon, ignoring the request's free text")
    void should_setCityAndRegionFromSettlement_when_createSalonWithCityId() {
        UUID ownerId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        var request = new CreateSalonRequest("Geo Salon", null, "Stale city", "Stale region", null, null, null,
                cityId, null, "Shevchenka St", "5A", null);
        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(true);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> {
            Salon s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(s, "createdAt", Instant.now());
            return s;
        });
        when(settlementDisplayNameResolver.resolve(cityId)).thenReturn(Optional.of(VINNYTSIA));
        ArgumentCaptor<Salon> saved = ArgumentCaptor.forClass(Salon.class);

        SalonResponse response = salonService.createSalon(ownerId, request);

        verify(salonRepository).save(saved.capture());
        assertThat(saved.getValue().getCity()).isEqualTo("Вінниця");
        assertThat(saved.getValue().getRegion()).isEqualTo("Вінницька");
        assertThat(response.city()).isEqualTo("Вінниця");
        assertThat(response.region()).isEqualTo("Вінницька");
    }

    @Test
    @DisplayName("createSalon — does NOT persist the deprecated free-text address (served publicly, uneditable)")
    void should_notPersistFreeTextAddress_when_createSalonRequestCarriesOne() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        var request = new CreateSalonRequest("Geo Salon", null, null, null, "вул. Домашня 1, кв. 5", null, null,
                UUID.randomUUID(), null, "Shevchenka St", "5A", null);
        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(true);
        when(salonRepository.save(any(Salon.class))).thenAnswer(inv -> {
            Salon s = inv.getArgument(0);
            ReflectionTestUtils.setField(s, "id", UUID.randomUUID());
            ReflectionTestUtils.setField(s, "createdAt", Instant.now());
            return s;
        });
        ArgumentCaptor<Salon> saved = ArgumentCaptor.forClass(Salon.class);

        salonService.createSalon(ownerId, request);

        verify(salonRepository).save(saved.capture());
        assertThat(saved.getValue().getAddress()).isNull();
        assertThat(saved.getValue().getStreet()).isEqualTo("Shevchenka St");
    }

    @Test
    @DisplayName("createSalon — a NON-first salon never moves the owner's own locality onto itself")
    void should_notSyncOwnerLocality_when_nonFirstSalonCreated() {
        UUID ownerId = UUID.randomUUID();
        UUID primaryCityId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        // The owner's row mirrors the PRIMARY salon (created first).
        owner.setCityId(primaryCityId);
        owner.setCity("Київ");
        owner.setRegion("Київ");
        owner.setStreet("Primary St");
        owner.setBuildingNo("1");
        owner.setLocationNote("primary note");
        var request = new CreateSalonRequest("Salon B", null, null, null, null, null, null,
                UUID.randomUUID(), UUID.randomUUID(), "Second St", "2", "second note");
        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(true);
        when(salonRepository.save(any(Salon.class)))
                .thenReturn(buildSalon(UUID.randomUUID(), owner, "Salon B"));

        salonService.createSalon(ownerId, request);

        assertThat(owner.getCityId()).isEqualTo(primaryCityId);
        assertThat(owner.getDistrictId()).isNull();
        assertThat(owner.getCity()).isEqualTo("Київ");
        assertThat(owner.getRegion()).isEqualTo("Київ");
        assertThat(owner.getStreet()).isEqualTo("Primary St");
        assertThat(owner.getBuildingNo()).isEqualTo("1");
        assertThat(owner.getLocationNote()).isEqualTo("primary note");
    }

    @Test
    @DisplayName("createSalon — the owner sync writes the settlement city + region next to the owner's new cityId")
    void should_syncSettlementLabelsToOwner_when_createSalonWithCityId() {
        UUID ownerId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        owner.setCity("Kyiv");
        owner.setRegion("Kyiv oblast");
        var request = new CreateSalonRequest("Geo Salon", null, null, null, null, null, null,
                cityId, null, "Shevchenka St", "5A", null);
        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        // FIRST salon: only the first (primary) salon syncs onto the owner row — a later salon
        // must not move the owner's locality (see should_notSyncOwnerLocality_when_nonFirstSalonCreated).
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(false);
        when(salonRepository.save(any(Salon.class))).thenReturn(buildSalon(UUID.randomUUID(), owner, "Geo Salon"));
        when(settlementDisplayNameResolver.resolve(cityId)).thenReturn(Optional.of(VINNYTSIA));

        salonService.createSalon(ownerId, request);

        assertThat(owner.getCityId()).isEqualTo(cityId);
        assertThat(owner.getCity()).as("owner's previous 'Kyiv' must not survive").isEqualTo("Вінниця");
        assertThat(owner.getRegion()).isEqualTo("Вінницька");
        verify(settlementDisplayNameResolver).resolve(cityId);
    }

    @Test
    @DisplayName("updateSalon — overwrites stale legacy city + region with the new settlement's labels when cityId changes")
    void should_setCityAndRegionFromSettlement_when_updateSalonChangesCityId() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID newCityId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Salon");
        salon.setCity("Kyiv");
        salon.setRegion("Kyiv oblast");
        var request = new UpdateSalonRequest(null, null, "Lviv", "Lviv oblast", null,
                newCityId, null, "Shevchenka St", "12", null, null, null);
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(settlementDisplayNameResolver.resolve(newCityId)).thenReturn(Optional.of(VINNYTSIA));

        SalonResponse response = salonService.updateSalon(ownerId, salonId, request);

        assertThat(salon.getCity())
                .as("neither the PREVIOUS free text ('Kyiv') nor the request's ('Lviv') may survive")
                .isEqualTo("Вінниця");
        assertThat(salon.getRegion()).isEqualTo("Вінницька");
        assertThat(response.city()).isEqualTo("Вінниця");
        assertThat(response.region()).isEqualTo("Вінницька");
    }

    @Test
    @DisplayName("updateSalon — clears city + region rather than keep stale text when the settlement does not resolve")
    void should_clearCityAndRegion_when_settlementDoesNotResolve() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID newCityId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Salon");
        salon.setCity("Kyiv");
        salon.setRegion("Kyiv oblast");
        var request = new UpdateSalonRequest(null, null, null, null, null,
                newCityId, null, "Shevchenka St", "12", null, null, null);
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(settlementDisplayNameResolver.resolve(newCityId)).thenReturn(Optional.empty());

        salonService.updateSalon(ownerId, salonId, request);

        assertThat(salon.getCity()).isNull();
        assertThat(salon.getRegion()).isNull();
    }

    @Test
    @DisplayName("updateSalon — leaves city + region untouched when cityId is omitted, even though the response resolves label parts")
    void should_keepCityAndRegion_when_updateSalonOmitsCityId() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Salon");
        salon.setCity("Вінниця");
        salon.setRegion("Вінницька");
        var request = new UpdateSalonRequest("Renamed", null, "Lviv", "Lviv oblast", null,
                null, null, "Shevchenka St", "12", null, null, null);
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        // The response now resolves the settlement's label parts on every update. A DIFFERENT
        // settlement is returned here, so a write path that wrongly re-applied the resolution
        // would overwrite the labels below — strictly stronger than the old never()-resolve check.
        when(settlementDisplayNameResolver.resolve(any())).thenReturn(Optional.of(
                new com.beautica.location.SettlementDisplayNames("Львів", "Львівська",
                        com.beautica.location.entity.SettlementType.CITY, null)));

        salonService.updateSalon(ownerId, salonId, request);

        assertThat(salon.getCity()).isEqualTo("Вінниця");
        assertThat(salon.getRegion()).isEqualTo("Вінницька");
    }

    private static final com.beautica.location.SettlementDisplayNames VINNYTSIA =
            new com.beautica.location.SettlementDisplayNames("Вінниця", "Вінницька",
                    com.beautica.location.entity.SettlementType.CITY, null);

    @Test
    @DisplayName("updateSalon — applies patch when salon exists (authorization delegated to @PreAuthorize on controller)")
    void should_updateSalon_when_salonExists_regardlessOfOwner() {
        // Authorization is exclusively enforced by @PreAuthorize on SalonController.updateSalon.
        // The service applies the patch to whichever salon is found — ownership is not re-checked here.
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(UUID.randomUUID(), "real@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Salon");

        var request = new UpdateSalonRequest("Updated Name", null, null, null, null,
                UUID.randomUUID(), null, null, null, null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        // No save() stub: managed entity flushes via dirty-checking (PERF-LOW redundant-write drop).

        SalonResponse response = salonService.updateSalon(actorId, salonId, request);

        assertThat(response.name()).isEqualTo("Updated Name");
    }

    // ── updateSalon — owner-row locality sync on a PRIMARY salon's cityId change ────────
    // updateSalon never mirrored createSalon's owner sync (see that method's Phase 10.3
    // comment), so /users/me kept the owner's PRE-move city/region/citySettlementType after a
    // PATCH moved the primary salon to a new locality — see SalonLocalityChangeLabelPartsIT.

    @Test
    @DisplayName("updateSalon — moving the PRIMARY salon re-syncs the owner's users row (locality, labels, address) and evicts /users/me")
    void should_syncOwnerLocality_when_primarySalonCityIdChanges() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID newCityId = UUID.randomUUID();
        UUID newDistrictId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Salon");
        ReflectionTestUtils.setField(salon, "isPrimary", true);
        var request = new UpdateSalonRequest(null, null, null, null, null,
                newCityId, newDistrictId, "Hrushevskoho St", "12", "2nd floor", null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(settlementDisplayNameResolver.resolve(newCityId)).thenReturn(Optional.of(VINNYTSIA));

        salonService.updateSalon(ownerId, salonId, request);

        assertThat(owner.getCityId()).isEqualTo(newCityId);
        assertThat(owner.getDistrictId()).isEqualTo(newDistrictId);
        assertThat(owner.getCity()).isEqualTo("Вінниця");
        assertThat(owner.getRegion()).isEqualTo("Вінницька");
        assertThat(owner.getStreet()).isEqualTo("Hrushevskoho St");
        assertThat(owner.getBuildingNo()).isEqualTo("12");
        assertThat(owner.getLocationNote()).isEqualTo("2nd floor");
        // The users row stores no type/hromada — GET /users/me derives them per read from the
        // owner's cityId through the same resolver (UserService#resolveSettlement). So what must
        // hold is: the SYNCED cityId resolves to VINNYTSIA's parts on the owner's own response.
        var ownerView = com.beautica.user.UserProfileResponse.from(owner, null, null, false,
                settlementDisplayNameResolver.resolve(owner.getCityId()).orElse(null));
        assertThat(ownerView.citySettlementType()).isEqualTo(VINNYTSIA.settlementType());
        assertThat(ownerView.cityHromadaNameUk()).isEqualTo(VINNYTSIA.hromadaNameUk());
        verify(userProfileCacheEvictor).evictAfterCommit(ownerId);
    }

    @Test
    @DisplayName("updateSalon — moving a NON-primary salon leaves the owner's users row and /users/me cache alone")
    void should_leaveOwnerRowUntouched_when_nonPrimarySalonCityIdChanges() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID newCityId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Salon");
        // buildSalon defaults isPrimary to false (Lombok @Builder default) — a second/third
        // salon, never the one the owner's users row mirrors.
        var request = new UpdateSalonRequest(null, null, null, null, null,
                newCityId, null, "Hrushevskoho St", "12", null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(settlementDisplayNameResolver.resolve(newCityId)).thenReturn(Optional.of(VINNYTSIA));

        salonService.updateSalon(ownerId, salonId, request);

        assertThat(owner.getCityId()).isNull();
        assertThat(owner.getStreet()).isNull();
        verify(userProfileCacheEvictor, never()).evictAfterCommit(any());
    }

    @Test
    @DisplayName("updateSalon — a street-only change on the PRIMARY salon (same city/district) syncs the owner's address and evicts /users/me")
    void should_syncOwnerAddress_when_primarySalonStreetChangesSameCity() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        owner.setCityId(TestConstants.DEFAULT_TEST_CITY_ID);
        owner.setStreet("Old St");
        owner.setBuildingNo("1");
        Salon salon = buildSalon(salonId, owner, "Salon");
        ReflectionTestUtils.setField(salon, "isPrimary", true);
        salon.setStreet("Old St");
        salon.setBuildingNo("1");
        // Same cityId (buildSalon → DEFAULT_TEST_CITY_ID) and same (absent) district — only the
        // street and building move. localityChanged is false; the owner's address still changed.
        var request = new UpdateSalonRequest(null, null, null, null, null,
                TestConstants.DEFAULT_TEST_CITY_ID, null, "Hrushevskoho St", "12", null, null, null);
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        salonService.updateSalon(ownerId, salonId, request);

        assertThat(owner.getStreet()).isEqualTo("Hrushevskoho St");
        assertThat(owner.getBuildingNo()).isEqualTo("12");
        assertThat(owner.getCityId()).isEqualTo(TestConstants.DEFAULT_TEST_CITY_ID);
        verify(userProfileCacheEvictor).evictAfterCommit(ownerId);
        // The owner-master key eviction (and search caches staying intact) is observed against a
        // real cache + transaction in SalonServiceCacheTest — a unit test has no synchronization.
    }

    @Test
    @DisplayName("updateSalon — resending the primary salon's SAME city, district, street and building does not re-sync the owner row")
    void should_leaveOwnerRowUntouched_when_primarySalonAddressResentUnchanged() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Salon");
        ReflectionTestUtils.setField(salon, "isPrimary", true);
        salon.setStreet("Hrushevskoho St");
        salon.setBuildingNo("12");
        salon.setLocationNote("2nd floor");
        var request = new UpdateSalonRequest(null, null, null, null, null,
                TestConstants.DEFAULT_TEST_CITY_ID, null, "Hrushevskoho St", "12", "2nd floor",
                null, null);
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        salonService.updateSalon(ownerId, salonId, request);

        assertThat(owner.getCityId()).isNull();
        assertThat(owner.getStreet()).isNull();
        verify(userProfileCacheEvictor, never()).evictAfterCommit(any());
    }

    @Test
    @DisplayName("updateSalon — moving the PRIMARY salon with locationNote omitted keeps the owner's existing note")
    void should_keepOwnerLocationNote_when_primaryMovePatchOmitsNote() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID newCityId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        owner.setLocationNote("Owner's own landmark");
        Salon salon = buildSalon(salonId, owner, "Salon");
        ReflectionTestUtils.setField(salon, "isPrimary", true);
        var request = new UpdateSalonRequest(null, null, null, null, null,
                newCityId, null, "Hrushevskoho St", "12", null, null, null);
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(settlementDisplayNameResolver.resolve(newCityId)).thenReturn(Optional.of(VINNYTSIA));

        salonService.updateSalon(ownerId, salonId, request);

        assertThat(owner.getCityId()).as("the move itself is synced").isEqualTo(newCityId);
        assertThat(owner.getLocationNote())
                .as("an omitted locationNote is 'not in this update', never 'clear it'")
                .isEqualTo("Owner's own landmark");
    }

    @Test
    @DisplayName("updateSalon — a rename-only PATCH (cityId omitted) on the primary salon does not touch the owner row")
    void should_leaveOwnerRowUntouched_when_primarySalonPatchOmitsCityId() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Salon");
        ReflectionTestUtils.setField(salon, "isPrimary", true);
        // A rename-only PATCH: cityId omitted means "locality not in this update" (PATCH
        // semantics pinned above at should_notValidateOrTouchLocality_when_updateSalonOmitsCityId).
        // street/buildingNo are @NotBlank so the client always resends them — here UNCHANGED,
        // which is what makes this a rename-only PATCH rather than an address edit.
        salon.setStreet("Hrushevskoho St");
        salon.setBuildingNo("12");
        var request = new UpdateSalonRequest("Renamed", null, null, null, null,
                null, null, "Hrushevskoho St", "12", null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        salonService.updateSalon(ownerId, salonId, request);

        assertThat(owner.getCityId()).isNull();
        assertThat(owner.getStreet()).isNull();
        verify(userProfileCacheEvictor, never()).evictAfterCommit(any());
    }

    @Test
    @DisplayName("updateSalon — a SALON_ADMIN moving the primary salon syncs the OWNER's row, never the acting admin's")
    void should_syncTheOwnersRowNotTheActors_when_salonAdminPatchesPrimarySalon() {
        UUID ownerId = UUID.randomUUID();
        UUID adminActorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID newCityId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Salon");
        ReflectionTestUtils.setField(salon, "isPrimary", true);
        // @PreAuthorize("... @authz.canManageSalon(...)") also admits the salon's assigned
        // SALON_ADMIN — actorId here is that admin, deliberately distinct from salon.getOwner().
        var request = new UpdateSalonRequest(null, null, null, null, null,
                newCityId, null, "Hrushevskoho St", "12", null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(settlementDisplayNameResolver.resolve(newCityId)).thenReturn(Optional.of(VINNYTSIA));

        salonService.updateSalon(adminActorId, salonId, request);

        assertThat(owner.getCityId())
                .as("the OWNER's row (salon.getOwner()), never the acting admin's")
                .isEqualTo(newCityId);
        verify(userProfileCacheEvictor).evictAfterCommit(ownerId);
        verify(userProfileCacheEvictor, never()).evictAfterCommit(adminActorId);
    }

    // ── updateSalon — Phase 20.x instagram widened validation + normalisation ──

    @Test
    @DisplayName("updateSalon — normalises an at-prefixed instagram handle by stripping the leading at-sign and trimming")
    void should_normaliseInstagram_when_updateSalonWithAtPrefixedHandleAndSpaces() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Salon");

        // Field order: name, description, city, region, address, cityId, districtId,
        //              street, buildingNo, locationNote, phone, instagramUrl
        var request = new UpdateSalonRequest("Salon", null, null, null, null,
                null, null, null, null, null, null, "  @beauty.master  ");

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        salonService.updateSalon(ownerId, salonId, request);

        assertThat(salon.getInstagramUrl())
                .as("leading @ stripped and surrounding whitespace trimmed → canonical handle")
                .isEqualTo("beauty.master");
    }

    @Test
    @DisplayName("updateSalon — persists a full instagram.com URL verbatim (regression guard for pre-widen behavior)")
    void should_persistFullInstagramUrlVerbatim_when_updateSalonWithFullUrl() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Salon");

        var request = new UpdateSalonRequest("Salon", null, null, null, null,
                null, null, null, null, null, null, "https://www.instagram.com/some.handle/");

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        salonService.updateSalon(ownerId, salonId, request);

        assertThat(salon.getInstagramUrl())
                .as("a full instagram.com URL has no leading @ to strip and must be stored unchanged")
                .isEqualTo("https://www.instagram.com/some.handle/");
    }

    @Test
    @DisplayName("updateSalon — leaves the existing instagram value unchanged when instagramUrl is null in the patch")
    void should_notOverwriteInstagram_when_updateSalonInstagramIsNullInPatch() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Salon");
        salon.setInstagramUrl("kept_handle");

        var request = new UpdateSalonRequest("Salon", null, null, null, null,
                null, null, null, null, null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        salonService.updateSalon(ownerId, salonId, request);

        assertThat(salon.getInstagramUrl())
                .as("a null instagramUrl in the patch must leave the stored handle untouched (PATCH semantics)")
                .isEqualTo("kept_handle");
    }

    @Test
    @DisplayName("updateSalon — propagates BusinessException from LocalityWriteValidator when a supplied cityId is rejected, and does not save")
    void should_rejectUpdateSalon_when_suppliedCityIdFailsLocalityValidation() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID unknownCityId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Old Name");

        // cityId IS supplied (not omitted) but the (mocked) validator rejects it — e.g. the
        // taxonomy id does not exist. This is distinct from the omitted-cityId PATCH case
        // (see should_notValidateOrTouchLocality_when_updateSalonOmitsCityId below): here the
        // caller explicitly asked for a locality change, so validation must still run and its
        // rejection must still propagate and abort the save.
        var request = new UpdateSalonRequest("New Name", null, null, null, null,
                unknownCityId, null, null, null, null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        org.mockito.Mockito.doThrow(new com.beautica.common.exception.BusinessException("Selected city does not exist"))
                .when(localityWriteValidator).validateProviderLocality(request.toLocalityInput());

        assertThatThrownBy(() -> salonService.updateSalon(ownerId, salonId, request))
                .isInstanceOf(com.beautica.common.exception.BusinessException.class)
                .hasMessageContaining("Selected city does not exist");

        verify(salonRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateSalon — description-only PATCH with cityId omitted skips locality validation and leaves the salon's existing city/district untouched")
    void should_notValidateOrTouchLocality_when_updateSalonOmitsCityId() {
        // Regression test for the actual user-reported bug: a PATCH that only changes
        // description (mobile's notifier does not resend cityId) used to throw
        // BusinessException("City is required") because validateProviderLocality ran
        // unconditionally against the raw (null) request.cityId(). A salon's city is
        // guaranteed non-null (V150/V151), so an omitted cityId in a PATCH must mean
        // "keep the existing locality", not "reject the request".
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID existingCityId = UUID.randomUUID();
        UUID existingDistrictId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Old Name");
        salon.setCityId(existingCityId);
        salon.setDistrictId(existingDistrictId);
        salon.setDescription("Old description");

        // Field order: name, description, city, region, address, cityId, districtId,
        //              street, buildingNo, locationNote, phone, instagramUrl.
        var request = new UpdateSalonRequest(null, "New description", null, null, null,
                null, null, null, null, null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        SalonResponse response = salonService.updateSalon(ownerId, salonId, request);

        assertThat(response.description()).isEqualTo("New description");
        assertThat(response.cityId())
                .as("existing cityId must survive a description-only PATCH unchanged")
                .isEqualTo(existingCityId);
        assertThat(response.districtId())
                .as("existing districtId must survive a description-only PATCH unchanged")
                .isEqualTo(existingDistrictId);
        verify(localityWriteValidator, never()).validateProviderLocality(any());
    }

    @Test
    @DisplayName("updateSalon — a name-only PATCH that omits locationNote must not wipe the previously saved note")
    void should_notWipeLocationNote_when_updateSalonOmitsIt() {
        // Regression for the sibling bug to should_notValidateOrTouchLocality_when_updateSalonOmitsCityId
        // above: locationNote is OPTIONAL on UpdateSalonRequest (unlike street/buildingNo, which
        // are @NotBlank and therefore always present), so an unconditional
        // salon.setLocationNote(request.locationNote()) silently wiped a saved note on ANY PATCH
        // that didn't resend it — e.g. this name-only edit. A null locationNote in the patch must
        // mean "not included in this update", matching the cityId PATCH contract.
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Old Name");
        salon.setLocationNote("Ring the back doorbell");

        // Field order: name, description, city, region, address, cityId, districtId,
        //              street, buildingNo, locationNote, phone, instagramUrl.
        var request = new UpdateSalonRequest("Updated Name", null, null, null, null,
                null, null, null, null, null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        salonService.updateSalon(ownerId, salonId, request);

        assertThat(salon.getLocationNote())
                .as("a null locationNote in the patch must leave the stored note untouched (PATCH semantics)")
                .isEqualTo("Ring the back doorbell");
    }

    @Test
    @DisplayName("updateSalon — an explicit empty-string locationNote clears the previously saved note")
    void should_clearLocationNote_when_updateSalonSendsEmptyString() {
        // Pins the other half of the locationNote contract: null means "leave unchanged" (see
        // should_notWipeLocationNote_when_updateSalonOmitsIt above), but the mobile client's
        // clear-the-note action sends "" explicitly (salon_management_profile_notifier.dart's
        // saveAddress() diff-then-omit formula), so "" must still reach the entity and must NOT
        // be treated the same as null.
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Old Name");
        salon.setLocationNote("Ring the back doorbell");

        var request = new UpdateSalonRequest(null, null, null, null, null,
                null, null, null, null, "", null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        salonService.updateSalon(ownerId, salonId, request);

        assertThat(salon.getLocationNote())
                .as("an explicit empty string must clear the note, distinct from a null (unchanged) patch")
                .isEmpty();
    }

    @Test
    @DisplayName("updateSalon — a name-only PATCH that omits phone must not wipe the previously saved phone")
    void should_notWipePhone_when_updateSalonOmitsIt() {
        // Same contract as should_notWipeLocationNote_when_updateSalonOmitsIt above, pinned for
        // `phone` because PublicSalonResponse now serves it on the public GET — the mobile
        // «Контакти» block reads it there, so a PATCH that silently nulled it would blank the
        // block for every unauthenticated visitor. phone is OPTIONAL on UpdateSalonRequest, so a
        // null means "not included in this PATCH", never "clear it".
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Old Name");
        salon.setPhone("+380509998877");

        // Field order: name, description, city, region, address, cityId, districtId,
        //              street, buildingNo, locationNote, phone, instagramUrl.
        var request = new UpdateSalonRequest("Updated Name", null, null, null, null,
                null, null, null, null, null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        salonService.updateSalon(ownerId, salonId, request);

        assertThat(salon.getPhone())
                .as("a null phone in the patch must leave the stored phone untouched (PATCH semantics)")
                .isEqualTo("+380509998877");
    }

    @Test
    @DisplayName("updateSalon — an explicit empty-string phone clears the previously saved phone verbatim")
    void should_clearPhone_when_updateSalonSendsEmptyString() {
        // Pins the other half of the phone contract, mirroring
        // should_clearLocationNote_when_updateSalonSendsEmptyString above: "" is the mobile
        // client's explicit clear signal and must reach the entity, distinct from null. It is
        // stored and served VERBATIM as "" — locked product decision, do NOT normalise blank to
        // null here or in PublicSalonResponse (see PublicSalonResponseTest
        // #should_servePhoneVerbatim_when_salonPhoneIsEmptyString); the client treats null and ""
        // alike as "no phone".
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Old Name");
        salon.setPhone("+380509998877");

        var request = new UpdateSalonRequest(null, null, null, null, null,
                null, null, null, null, null, "", null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        salonService.updateSalon(ownerId, salonId, request);

        assertThat(salon.getPhone())
                .as("an explicit empty string must clear the phone, distinct from a null (unchanged) patch")
                .isEmpty();
    }

    @Test
    @DisplayName("updateSalon — rejects a districtId supplied without cityId instead of silently dropping it")
    void should_rejectUpdate_when_districtIdSuppliedWithoutCityId() {
        // Regression for the LOW finding sibling to the cityId-omitted fix above: the locality
        // write is gated on cityId != null, so a PATCH supplying districtId but NOT cityId used to
        // be a silent no-op — the district was neither validated, nor written, nor rejected, and
        // the caller got a 200 believing their change applied. Fail loud with a clean 400 instead.
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID existingCityId = UUID.randomUUID();
        UUID orphanDistrictId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Old Name");
        salon.setCityId(existingCityId);

        var request = new UpdateSalonRequest(null, null, null, null, null,
                null, orphanDistrictId, null, null, null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        assertThatThrownBy(() -> salonService.updateSalon(ownerId, salonId, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cityId");

        verify(salonRepository, never()).save(any());
        verify(localityWriteValidator, never()).validateProviderLocality(any());
        assertThat(salon.getDistrictId())
                .as("the salon's district must remain unset — the rejected districtId must not be written")
                .isNull();
    }

    @Test
    @DisplayName("createSalon — propagates BusinessException from localityWriteValidator and does not save owner")
    void should_throwBusinessException_when_localityValidatorRejectsOnCreateSalon() {
        // Arrange
        UUID ownerId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        // Non-null cityId ensures the locality-sync guard is entered and the validator is called.
        var request = new CreateSalonRequest(
                "Bad Geo Salon", null, null, null, null, null, null,
                cityId, null, null, null, null
        );

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        org.mockito.Mockito.doThrow(new com.beautica.common.exception.BusinessException("City is required"))
                .when(localityWriteValidator).validateProviderLocality(request.toLocalityInput());

        // Act + Assert
        assertThatThrownBy(() -> salonService.createSalon(ownerId, request))
                .isInstanceOf(com.beautica.common.exception.BusinessException.class)
                .hasMessageContaining("City is required");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    @DisplayName("createSalon — propagates BusinessException from localityWriteValidator and persists nothing when locality is absent (cityId null)")
    void should_throwBusinessException_when_localityAbsentOnCreateSalon() {
        // Regression (Phase 12.1): createSalon must validate locality UNCONDITIONALLY,
        // exactly like updateSalon. Previously an absent cityId skipped validation and a
        // salon was persisted with no locality, only to be blocked later at updateSalon.
        // Arrange
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        // cityId == null — the validator's most-specific-node rule rejects this (CITY_REQUIRED).
        var request = new CreateSalonRequest(
                "No Geo Salon", null, null, null, null, null, null,
                null, null, null, null, null
        );

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        org.mockito.Mockito.doThrow(new com.beautica.common.exception.BusinessException("City is required"))
                .when(localityWriteValidator).validateProviderLocality(request.toLocalityInput());

        // Act + Assert — same exception type updateSalon throws for the same case.
        assertThatThrownBy(() -> salonService.createSalon(ownerId, request))
                .isInstanceOf(com.beautica.common.exception.BusinessException.class)
                .hasMessageContaining("City is required");

        // No salon persisted, no owner location sync, no master auto-creation on rejection.
        verify(salonRepository, never()).save(any());
        verify(userRepository, never()).save(any(User.class));
        verify(masterService, never()).createMasterForOwner(any(User.class), any(Salon.class));
    }

    @Test
    @DisplayName("createSalon — validates locality before persisting and succeeds when locality is valid")
    void should_validateLocalityBeforeSave_when_createSalonWithValidLocality() {
        // Happy path: a valid locality passes the validator (mock no-op) and the salon is saved.
        // Asserts validation runs BEFORE save (ordering) so nothing persists on rejection.
        UUID ownerId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        var request = new CreateSalonRequest(
                "Valid Geo Salon", null, null, null, null, null, null,
                cityId, districtId, "Main St", "1", null
        );
        var savedSalon = buildSalon(UUID.randomUUID(), owner, "Valid Geo Salon");

        when(userRepository.findByIdForUpdate(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(true);
        when(salonRepository.save(any(Salon.class))).thenReturn(savedSalon);

        SalonResponse response = salonService.createSalon(ownerId, request);

        assertThat(response.name()).isEqualTo("Valid Geo Salon");
        var inOrder = org.mockito.Mockito.inOrder(localityWriteValidator, salonRepository);
        inOrder.verify(localityWriteValidator).validateProviderLocality(request.toLocalityInput());
        inOrder.verify(salonRepository).save(any(Salon.class));
    }

    @Test
    @DisplayName("deactivateSalon — sets isActive to false on the managed entity when owner requests")
    void should_deactivateSalon_when_ownerRequests() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Active Salon");

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
        stubCleanEmptyStaffCascade(salonId);
        // No save() stub: `salon` is a managed entity in-tx; the isActive mutation flushes via
        // Hibernate dirty-checking on commit, so deactivateSalon no longer calls save()
        // (PERF-LOW redundant-write drop). The behavioural contract is the isActive flip below.

        salonService.deactivateSalon(ownerId, salonId);

        assertThat(salon.isActive()).isFalse();
        verify(salonRepository, never()).save(any());
        verify(userRepository).findById(ownerId);
    }

    @Test
    @DisplayName("deactivateSalon — throws NotFoundException when salonId and ownerId do not match")
    void should_throwNotFound_when_salonNotOwnedByRequester() {
        UUID attackerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User attacker = buildUser(attackerId, "attacker@beautica.com", Role.SALON_OWNER);

        when(userRepository.findById(attackerId)).thenReturn(Optional.of(attacker));
        when(salonRepository.findByIdAndOwnerId(salonId, attackerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salonService.deactivateSalon(attackerId, salonId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Salon not found or access denied");

        verify(salonRepository, never()).save(any());
        verify(userRepository).findById(attackerId);
    }

    @Test
    @DisplayName("deactivateSalon — loads via findByIdAndOwnerId and findById(user), uses no other salon-repository methods (no save: dirty-checking flushes)")
    void should_makeExactlyOneRepositoryCall_when_deactivateSalon() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Active Salon");

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
        stubCleanEmptyStaffCascade(salonId);
        // No save() stub: managed entity flushes via dirty-checking (PERF-LOW redundant-write drop).

        salonService.deactivateSalon(ownerId, salonId);

        verify(userRepository).findById(ownerId);
        verify(salonRepository).findByIdAndOwnerId(salonId, ownerId);
        verify(salonRepository, never()).save(any());
        verify(salonRepository, never()).findById(any());
        verify(salonRepository, never()).existsByIdAndOwnerId(any(), any());
        // Phase 342 D6: the catalogue is row-locked, pointers cleared, then blobs purged — nothing else.
        verify(serviceRepository).lockAllByOwnerOrderById(com.beautica.service.entity.OwnerType.SALON, salonId);
        verify(serviceRepository).clearPhotosByOwner(com.beautica.service.entity.OwnerType.SALON, salonId);
        verify(serviceRepository).deactivateAllByOwner(com.beautica.service.entity.OwnerType.SALON, salonId);
        verify(servicePhotoBlobPurger).purgeAfterCommit(List.of());
        org.mockito.Mockito.verifyNoMoreInteractions(serviceRepository, servicePhotoBlobPurger);
    }

    @Test
    @DisplayName("deactivateSalon — row-locks the service catalogue BEFORE the booking decline cascade, the first masters read of the staff cascade, and the photo clear / deactivate (deadlock lock order)")
    void should_lockServiceCatalogueBeforeAnyMastersWrite_when_deactivateSalon() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Active Salon");
        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
        stubCleanEmptyStaffCascade(salonId);

        salonService.deactivateSalon(ownerId, salonId);

        var order = org.mockito.Mockito.inOrder(serviceRepository, bookingService, masterRepository);
        order.verify(serviceRepository).lockAllByOwnerOrderById(com.beautica.service.entity.OwnerType.SALON, salonId);
        order.verify(bookingService).declineFutureConfirmedBookingsForSalonClosure(ownerId, salonId);
        order.verify(masterRepository).findBySalonIdAndIsActiveTrueWithUser(eq(salonId), any());
        order.verify(serviceRepository).clearPhotosByOwner(com.beautica.service.entity.OwnerType.SALON, salonId);
        order.verify(serviceRepository).deactivateAllByOwner(com.beautica.service.entity.OwnerType.SALON, salonId);
    }

    @Test
    @DisplayName("deactivateSalon — purges exactly the photo keys read from the locked catalogue rows, skipping rows without a photo")
    void should_purgeLockedPhotoKeys_when_deactivateSalon() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Active Salon");
        UUID withPhotoId = UUID.randomUUID();
        var withPhoto = org.mockito.Mockito.mock(com.beautica.service.entity.ServiceDefinition.class);
        when(withPhoto.getId()).thenReturn(withPhotoId);
        when(withPhoto.getPhotoR2Key()).thenReturn("services/abc/photo.jpg");
        var withoutPhoto = org.mockito.Mockito.mock(com.beautica.service.entity.ServiceDefinition.class);
        when(withoutPhoto.getPhotoR2Key()).thenReturn(null);

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
        stubCleanEmptyStaffCascade(salonId);
        when(serviceRepository.lockAllByOwnerOrderById(com.beautica.service.entity.OwnerType.SALON, salonId))
                .thenReturn(List.of(withPhoto, withoutPhoto));

        salonService.deactivateSalon(ownerId, salonId);

        verify(servicePhotoBlobPurger).purgeAfterCommit(List.of(
                new com.beautica.service.service.ServicePhotoBlobPurger.ServicePhotoBlob(
                        withPhotoId, "services/abc/photo.jpg")));
    }

    @Test
    @DisplayName("deactivateSalon — throws ForbiddenException when caller is not SALON_OWNER")
    void should_throwForbidden_when_nonOwnerCallsDeactivateSalon() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User client = buildUser(userId, "client@beautica.com", Role.CLIENT);

        when(userRepository.findById(userId)).thenReturn(Optional.of(client));

        assertThatThrownBy(() -> salonService.deactivateSalon(userId, salonId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only SALON_OWNER may deactivate a salon");

        verify(salonRepository, never()).save(any());
    }

    @Test
    @DisplayName("deactivateSalon — throws NotFoundException when user not found")
    void should_throwNotFoundException_when_deactivateSalonAndUserNotFound() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        when(userRepository.findById(ownerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salonService.deactivateSalon(ownerId, salonId))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("User not found");

        verify(salonRepository, never()).findByIdAndOwnerId(any(), any());
    }

    /**
     * QA audit (2026-09-03) gap fix — {@code should_abortWithoutMutating_when_auditFindsViolation}
     * (the Testcontainers IT) proves a violation aborts the deletion, but no test anywhere
     * asserted the actual VALUE {@code deactivateSalon} computes for
     * {@link SalonDeletionBlockedException#getAffectedStaffCount()}. Phase 289's own finding is
     * that violations are CORRELATED, not independent — one bad booking row trips
     * {@code BOOKING_CLIENT} plus any review/client-review built on that same booking, for the
     * SAME staff member. A naive {@code violations.size()} (no {@code .distinct()} on the mapped
     * user ids) would report 3 incidents for 1 person. This pins the collapsing behaviour
     * directly, at the unit level, without a real DB.
     */
    @Test
    @DisplayName("deactivateSalon — throws SalonDeletionBlockedException with affectedStaffCount "
            + "collapsed to DISTINCT staff, not a per-violation-row count, and mutates nothing")
    void should_reportDistinctStaffCount_when_auditFindsCorrelatedViolationsForSameUser() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Active Salon");

        UUID correlatedStaffId = UUID.randomUUID();
        UUID otherStaffId = UUID.randomUUID();
        StaffClientReferenceAuditResult violating = StaffClientReferenceAuditResult.of(
                List.of(
                        // Same staff member, tripped at THREE reference sites by one underlying
                        // booking — this must collapse to ONE incident, not three.
                        new StaffClientReferenceViolation(
                                correlatedStaffId, Role.SALON_MASTER,
                                StaffClientReferenceType.BOOKING_CLIENT, 1),
                        new StaffClientReferenceViolation(
                                correlatedStaffId, Role.SALON_MASTER,
                                StaffClientReferenceType.REVIEW_CLIENT, 1),
                        new StaffClientReferenceViolation(
                                correlatedStaffId, Role.SALON_MASTER,
                                StaffClientReferenceType.CLIENT_REVIEW_SUBJECT, 1),
                        // A genuinely different staff member — must still count as a second.
                        new StaffClientReferenceViolation(
                                otherStaffId, Role.SALON_ADMIN,
                                StaffClientReferenceType.BOOKING_CLIENT, 1)),
                Instant.now());

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.findByIdAndOwnerId(salonId, ownerId)).thenReturn(Optional.of(salon));
        when(staffClientReferenceAuditService.runAuditForSalon(salonId)).thenReturn(violating);

        assertThatThrownBy(() -> salonService.deactivateSalon(ownerId, salonId))
                .isInstanceOf(SalonDeletionBlockedException.class)
                .satisfies(ex -> assertThat(((SalonDeletionBlockedException) ex).getAffectedStaffCount())
                        .as("4 violation rows across 2 distinct staff ids must report 2, not 4")
                        .isEqualTo(2));

        // Fail-closed: the abort happens before ANY mutation — salon stays active, and the staff
        // cascade (masters lookup, staff-id resolution) never runs.
        assertThat(salon.isActive()).isTrue();
        verify(masterRepository, never()).findBySalonIdAndIsActiveTrueWithUser(any(), any());
        verify(staffClientReferenceAuditService, never()).resolveSalonStaffUserIds(any());
        verify(salonRepository, never()).save(any());
    }

    @Test
    @DisplayName("inviteMaster — throws NotFoundException when salon does not exist")
    void should_throwNotFound_when_salonNotFoundForInvite() {
        // Authorization is exclusively enforced by @PreAuthorize on SalonController.inviteMaster.
        // The service only validates that the salon exists before delegating to inviteService.
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(salonRepository.findById(salonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salonService.inviteMaster(actorId, salonId, "master@test.com", Role.SALON_MASTER))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Salon not found");

        verify(inviteService, never()).sendInvite(any(), any());
    }

    @Test
    @DisplayName("getOwnerSalons — returns empty list when owner has no active salons")
    void should_returnEmptyList_when_noSalonsExist() {
        UUID ownerId = UUID.randomUUID();

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId)).thenReturn(List.of());

        List<SalonResponse> result = salonService.getOwnerSalons(ownerId);

        assertThat(result).isNotNull();
        assertThat(result).isEmpty();
        verify(salonRepository).findAllByOwnerIdAndIsActiveTrue(ownerId);
    }

    @Test
    @DisplayName("inviteMaster — delegates to inviteService when salon exists")
    void should_delegateToInviteService_when_inviteMaster() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Salon salon = buildSalon(salonId, buildUser(actorId, "owner@test.com", Role.SALON_OWNER), "Test");
        var expected = new InviteResponse("master@test.com", Instant.now().plusSeconds(3600));

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(inviteService.sendInvite(any(InviteRequest.class), eq(actorId))).thenReturn(expected);

        InviteResponse result = salonService.inviteMaster(actorId, salonId, "master@test.com", Role.SALON_MASTER);

        assertThat(result.invitedEmail()).isEqualTo("master@test.com");
        verify(inviteService).sendInvite(any(InviteRequest.class), eq(actorId));
    }

    @Test
    @DisplayName("getMastersBySalon — maps Page<Master> to Page<MasterSummaryResponse> via from() factory")
    void should_returnMasterSummaries_when_getMastersBySalon() {
        UUID salonId = UUID.randomUUID();
        Pageable pageable = Pageable.ofSize(10);

        UUID masterId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "master@beautica.test", Role.SALON_MASTER);
        Master master = Master.builder()
                .masterType(MasterType.SALON_MASTER)
                .user(user)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(master, "id", masterId);

        // The service no longer forwards the caller's Pageable verbatim: SortWhitelist.apply
        // validates it against SORTABLE_MASTER_PROPERTIES and, because the incoming Pageable is
        // unsorted and the underlying query has no ORDER BY of its own, substitutes the default
        // sort plus the mandatory unique `id` tiebreaker. Stubbing/verifying with this exact
        // normalized instance (rather than any(Pageable.class)) is deliberate — it pins that
        // contract, so silently dropping the whitelist or the tiebreaker reddens this test.
        Pageable expectedNormalized = PageRequest.of(0, 10,
                Sort.by(Sort.Direction.DESC, "avgRating").and(Sort.by(Sort.Direction.ASC, "id")));

        Page<Master> pageOfMasters = new PageImpl<>(List.of(master), expectedNormalized, 1);
        when(masterRepository.findBySalonIdAndIsActiveTrueWithUser(salonId, expectedNormalized))
                .thenReturn(pageOfMasters);

        var result = salonService.getMastersBySalon(salonId, pageable);

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent().get(0).masterId()).isEqualTo(masterId);
        verify(masterRepository).findBySalonIdAndIsActiveTrueWithUser(salonId, expectedNormalized);
    }

    @Test
    @DisplayName("getMastersBySalon rejects a dotted sort path with a 400 before touching the repository")
    void should_throwBadRequest_when_getMastersBySalonSortIsDottedPath() {
        UUID salonId = UUID.randomUUID();
        Pageable oracleAttempt = PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "user.passwordHash"));

        assertThatThrownBy(() -> salonService.getMastersBySalon(salonId, oracleAttempt))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);

        verifyNoInteractions(masterRepository);
    }

    /**
     * Wires the Phase 290 staff-deactivation cascade to a clean no-op for {@code salonId}: audit
     * CLEAN, no active masters, no staff user ids. The two {@code deactivateSalon} tests that
     * reach this new code assert something else entirely (the {@code isActive} flip, the
     * salon-repository call shape) — without these stubs the fail-closed audit call and the
     * masters/staff loops would NPE on Mockito's default {@code null} return for the unstubbed
     * {@link StaffClientReferenceAuditResult}/{@link Page} types.
     */
    private void stubCleanEmptyStaffCascade(UUID salonId) {
        when(staffClientReferenceAuditService.runAuditForSalon(salonId))
                .thenReturn(StaffClientReferenceAuditResult.of(List.of(), Instant.now()));
        when(masterRepository.findBySalonIdAndIsActiveTrueWithUser(salonId, Pageable.unpaged()))
                .thenReturn(Page.empty());
        when(staffClientReferenceAuditService.resolveSalonStaffUserIds(salonId)).thenReturn(List.of());
    }

    private User buildUser(UUID id, String email, Role role) {
        var user = new User(email, "hashed", role, null, null, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Salon buildSalon(UUID id, User owner, String name) {
        var salon = Salon.builder()
                .cityId(TestConstants.DEFAULT_TEST_CITY_ID)
                .owner(owner)
                .name(name)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(salon, "id", id);
        ReflectionTestUtils.setField(salon, "createdAt", Instant.now());
        return salon;
    }

    /**
     * Same as {@link #buildSalon(UUID, User, String)} but WITHOUT a cityId — a real salon can
     * never reach this state going forward (DB-level {@code NOT NULL} as of V150, plus the
     * unconditional {@code LocalityWriteValidator} guard on every write path), but the defensive
     * null-handling in {@code SalonService#resolveOblastId} stays in place for legacy rows and is
     * exactly what these tests exist to cover — do NOT "fix" them onto {@link #buildSalon} by
     * giving this salon a real cityId.
     */
    private Salon buildSalonNoCity(UUID id, User owner, String name) {
        var salon = Salon.builder()
                .owner(owner)
                .name(name)
                .isActive(true)
                .build();
        ReflectionTestUtils.setField(salon, "id", id);
        ReflectionTestUtils.setField(salon, "createdAt", Instant.now());
        return salon;
    }
}
