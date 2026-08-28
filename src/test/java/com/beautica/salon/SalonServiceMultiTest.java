package com.beautica.salon;

import com.beautica.auth.Role;
import com.beautica.common.exception.NotFoundException;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.location.repository.CityRepository;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.SalonResponse;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import com.beautica.auth.InviteService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SalonService — multi-salon unit")
class SalonServiceMultiTest {

    @Mock
    private SalonRepository salonRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private InviteService inviteService;

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private LocalityWriteValidator localityWriteValidator;

    @Mock
    private MasterService masterService;

    @Mock
    private CacheManager cacheManager;

    // CRITICAL: must be declared so @InjectMocks can satisfy the CityRepository constructor
    // parameter — without it the field receives null and resolveOblastId throws NPE whenever
    // getCityId() returns a non-null value (mirrors MasterServiceTest).
    @Mock
    private CityRepository cityRepository;

    @InjectMocks
    private SalonService salonService;

    // -------------------------------------------------------------------------
    // createSalon — second salon for owner who already has one
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createSalon — saves second salon when owner already has one")
    void should_createSecondSalon_when_ownerAlreadyHasOne() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.test", Role.SALON_OWNER);
        var request = new CreateSalonRequest("Second Salon", null, "Lviv", null, null, null, null, null, null, null, null, null);
        var savedSalon = buildSalon(UUID.randomUUID(), owner, "Second Salon");

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.save(any(Salon.class))).thenReturn(savedSalon);

        SalonResponse response = salonService.createSalon(ownerId, request);

        assertThat(response.name()).isEqualTo("Second Salon");
        assertThat(response.ownerId()).isEqualTo(ownerId);
        verify(salonRepository).save(any(Salon.class));
    }

    // -------------------------------------------------------------------------
    // getOwnerSalons — multiple salons returned
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getOwnerSalons — returns all salons when owner has multiple")
    void should_returnAllSalons_when_ownerHasMultiple() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.test", Role.SALON_OWNER);
        var salon1 = buildSalon(UUID.randomUUID(), owner, "Salon Alpha");
        var salon2 = buildSalon(UUID.randomUUID(), owner, "Salon Beta");
        var salon3 = buildSalon(UUID.randomUUID(), owner, "Salon Gamma");

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId))
                .thenReturn(List.of(salon1, salon2, salon3));

        List<SalonResponse> responses = salonService.getOwnerSalons(ownerId);

        assertThat(responses).hasSize(3);
        assertThat(responses).extracting(SalonResponse::name)
                .containsExactlyInAnyOrder("Salon Alpha", "Salon Beta", "Salon Gamma");
        assertThat(responses).allMatch(r -> ownerId.equals(r.ownerId()));
        verify(salonRepository).findAllByOwnerIdAndIsActiveTrue(ownerId);
    }

    // -------------------------------------------------------------------------
    // getOwnerSalons — empty list when owner has no salons
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getOwnerSalons — returns empty list when owner has no salons")
    void should_returnEmptyList_when_ownerHasNoSalons() {
        UUID ownerId = UUID.randomUUID();

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId))
                .thenReturn(Collections.emptyList());

        List<SalonResponse> responses = salonService.getOwnerSalons(ownerId);

        assertThat(responses).isEmpty();
        verify(salonRepository).findAllByOwnerIdAndIsActiveTrue(ownerId);
    }

    // -------------------------------------------------------------------------
    // getOwnerSalons — batch oblastId resolution (§E — exactly ONE findOblastIdsByIdIn
    // call regardless of salon count; this is the whole point of the batch method over
    // the per-salon resolveOblastId used by createSalon/updateSalon).
    //
    // Fixture uses TWO DIFFERENT city/oblast pairs deliberately (Anti-Bug §"fixture values
    // can defang the assertion"): if resolveOblastIdsByCityIds ever built its map backwards,
    // dropped a key, or returned one shared oblastId for every city, a single-city fixture
    // would still pass by coincidence. Two distinct pairs prove the per-salon lookup, not
    // just "a value came back".
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getOwnerSalons — resolves each salon's OWN oblastId in exactly one batch query")
    void should_resolveDistinctOblastIdsPerSalon_when_ownerHasSalonsInDifferentCities() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.test", Role.SALON_OWNER);

        UUID cityA = UUID.randomUUID();
        UUID oblastA = UUID.randomUUID();
        UUID cityB = UUID.randomUUID();
        UUID oblastB = UUID.randomUUID();

        Salon salonA = Salon.builder().owner(owner).name("Salon A").isActive(true).cityId(cityA).build();
        ReflectionTestUtils.setField(salonA, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(salonA, "createdAt", Instant.now());
        Salon salonB = Salon.builder().owner(owner).name("Salon B").isActive(true).cityId(cityB).build();
        ReflectionTestUtils.setField(salonB, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(salonB, "createdAt", Instant.now());

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId))
                .thenReturn(List.of(salonA, salonB));
        // Row order deliberately reversed vs. salon list order — a map-based lookup must not
        // depend on the repository returning rows in salon-list order.
        when(cityRepository.findOblastIdsByIdIn(Set.of(cityA, cityB)))
                .thenReturn(List.of(
                        new Object[] {cityB, oblastB},
                        new Object[] {cityA, oblastA}));

        List<SalonResponse> responses = salonService.getOwnerSalons(ownerId);

        assertThat(responses)
                .as("each salon must carry its OWN oblastId, not a swapped/shared one")
                .extracting(SalonResponse::cityId, SalonResponse::oblastId)
                .containsExactlyInAnyOrder(tuple(cityA, oblastA), tuple(cityB, oblastB));
        // Gap 2 (verifier/perf-flagged): the batch method is the entire point of
        // resolveOblastIdsByCityIds — a regression back to a per-salon N+1 call must fail this.
        // any() (not the exact combined set) is deliberate: an N+1 regression calls the method
        // once PER SALON with a smaller, DIFFERENT argument each time (e.g. Set.of(cityA) then
        // Set.of(cityB)) — an exact-args verify would not even see those extra calls, since
        // Mockito counts invocations per distinct argument match, not total calls to the method.
        verify(cityRepository, times(1)).findOblastIdsByIdIn(any());
        // The one call that does happen must carry the full combined city-id set.
        verify(cityRepository).findOblastIdsByIdIn(Set.of(cityA, cityB));
    }

    @Test
    @DisplayName("getOwnerSalons — leaves oblastId null for a salon with no cityId, without breaking siblings")
    void should_returnNullOblastId_when_oneSalonHasNoCityIdInBatch() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.test", Role.SALON_OWNER);

        UUID cityA = UUID.randomUUID();
        UUID oblastA = UUID.randomUUID();

        Salon salonWithCity = Salon.builder().owner(owner).name("Salon A").isActive(true).cityId(cityA).build();
        ReflectionTestUtils.setField(salonWithCity, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(salonWithCity, "createdAt", Instant.now());
        Salon salonNoCity = buildSalon(UUID.randomUUID(), owner, "Salon No City");

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId))
                .thenReturn(List.of(salonWithCity, salonNoCity));
        // Only the non-null cityId may appear in the batch query's input set.
        when(cityRepository.findOblastIdsByIdIn(Set.of(cityA)))
                .thenReturn(List.<Object[]>of(new Object[] {cityA, oblastA}));

        List<SalonResponse> responses = salonService.getOwnerSalons(ownerId);

        assertThat(responses)
                .extracting(SalonResponse::name, SalonResponse::oblastId)
                .containsExactlyInAnyOrder(
                        tuple("Salon A", oblastA),
                        tuple("Salon No City", null));
    }

    @Test
    @DisplayName("getOwnerSalons — never calls the batch resolver when no salon has a cityId")
    void should_notCallCityRepository_when_noSalonHasCityId() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.test", Role.SALON_OWNER);
        Salon salon1 = buildSalon(UUID.randomUUID(), owner, "Salon Alpha");
        Salon salon2 = buildSalon(UUID.randomUUID(), owner, "Salon Beta");

        when(salonRepository.findAllByOwnerIdAndIsActiveTrue(ownerId))
                .thenReturn(List.of(salon1, salon2));

        List<SalonResponse> responses = salonService.getOwnerSalons(ownerId);

        assertThat(responses).extracting(SalonResponse::oblastId).containsOnlyNulls();
        // CRITICAL guard-branch assertion (Q6): the empty-cityIds short-circuit must never
        // reach the DB — verifies resolveOblastIdsByCityIds' cityIds.isEmpty() branch.
        verify(cityRepository, never()).findOblastIdsByIdIn(any());
    }

    // -------------------------------------------------------------------------
    // createSalon — owner.setSalonId() must NOT be called
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("createSalon — does not call setSalonId on owner after salon is created")
    void should_notSetSalonIdOnOwner_when_salonCreated() {
        UUID ownerId = UUID.randomUUID();
        User owner = buildUser(ownerId, "owner@beautica.test", Role.SALON_OWNER);
        var request = new CreateSalonRequest("My Salon", null, "Kyiv", null, null, null, null, null, null, null, null, null);
        var savedSalon = buildSalon(UUID.randomUUID(), owner, "My Salon");

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.save(any(Salon.class))).thenReturn(savedSalon);

        salonService.createSalon(ownerId, request);

        // owner.setSalonId() was removed in multi-salon refactor; salonId on User must remain null
        assertThat(owner.getSalonId())
                .as("owner.salonId must not be set by createSalon in multi-salon model")
                .isNull();
        // userRepository.save must never be called just to update the owner's salonId
        verify(userRepository, never()).save(any(User.class));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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
