package com.beautica.salon;

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

    @InjectMocks
    private SalonService salonService;

    @Test
    @DisplayName("createSalon — saves and returns SalonResponse when owner already has one salon")
    void should_createSalon_when_ownerAlreadyHasOneSalon() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        var request = new CreateSalonRequest("Second Salon", null, "Kyiv", null, null, null, null, null, null, null, null, null);
        var savedSalon = buildSalon(UUID.randomUUID(), owner, "Second Salon");

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(true);
        when(salonRepository.save(any(Salon.class))).thenReturn(savedSalon);

        SalonResponse response = salonService.createSalon(ownerId, request);

        assertThat(response.name()).isEqualTo("Second Salon");
        assertThat(response.ownerId()).isEqualTo(ownerId);
        verify(salonRepository).save(any(Salon.class));
        // cityId is null in the request above — the locality sync guard must not fire
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

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByOwnerId(ownerId)).thenReturn(false);
        when(salonRepository.save(any(Salon.class))).thenReturn(savedSalon);
        when(userRepository.save(owner)).thenReturn(owner);

        // Act
        salonService.createSalon(ownerId, request);

        // Assert — locality fields mirrored onto the owner entity
        assertThat(owner.getCityId()).isEqualTo(cityId);
        assertThat(owner.getStreet()).isEqualTo("Shevchenka St");
        assertThat(owner.getBuildingNo()).isEqualTo("5A");
        assertThat(owner.getLocationNote()).isEqualTo("2nd floor");
        assertThat(owner.getDistrictId()).isEqualTo(districtId);
        // userRepository.save must be called exactly once (inside the cityId guard)
        verify(userRepository).save(owner);
        // Locality validation must have been invoked
        verify(localityWriteValidator).validateProviderLocality(request.toLocalityInput());
        // first-salon path (existsByOwnerId=false) must trigger master auto-creation
        verify(masterService).createMasterForOwner(owner, savedSalon);
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

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
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

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
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

        when(userRepository.findById(userId)).thenReturn(Optional.of(client));

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
    @DisplayName("updateSalon — propagates BusinessException from LocalityWriteValidator and does not save")
    void should_rejectUpdateSalon_when_localityValidationFails() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.com", Role.SALON_OWNER);
        Salon salon = buildSalon(salonId, owner, "Old Name");

        // City omitted — validator (the real one) would reject; here the mock is
        // configured to throw to assert the service propagates and aborts the save.
        var request = new UpdateSalonRequest("New Name", null, null, null, null,
                null, null, null, null, null, null, null);

        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        org.mockito.Mockito.doThrow(new com.beautica.common.exception.BusinessException("City is required"))
                .when(localityWriteValidator).validateProviderLocality(request.toLocalityInput());

        assertThatThrownBy(() -> salonService.updateSalon(ownerId, salonId, request))
                .isInstanceOf(com.beautica.common.exception.BusinessException.class)
                .hasMessageContaining("City is required");

        verify(salonRepository, never()).save(any());
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

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
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

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
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

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
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
        // No save() stub: managed entity flushes via dirty-checking (PERF-LOW redundant-write drop).

        salonService.deactivateSalon(ownerId, salonId);

        verify(userRepository).findById(ownerId);
        verify(salonRepository).findByIdAndOwnerId(salonId, ownerId);
        verify(salonRepository, never()).save(any());
        verify(salonRepository, never()).findById(any());
        verify(salonRepository, never()).existsByIdAndOwnerId(any(), any());
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

    private User buildUser(UUID id, String email, Role role) {
        var user = new User(email, "hashed", role, null, null, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private Salon buildSalon(UUID id, User owner, String name) {
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
