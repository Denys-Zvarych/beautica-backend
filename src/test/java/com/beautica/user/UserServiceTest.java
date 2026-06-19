package com.beautica.user;

import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.location.LocalityWriteInput;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.location.entity.City;
import com.beautica.location.entity.Oblast;
import com.beautica.location.repository.CityDistrictRepository;
import com.beautica.location.repository.CityRepository;
import com.beautica.master.dto.MasterProfileUpdateRequest;
import com.beautica.master.dto.MasterPublicProfileResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — unit")
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private LocalityWriteValidator localityWriteValidator;

    @Mock
    private CityRepository cityRepository;

    @Mock
    private CityDistrictRepository cityDistrictRepository;

    @Mock
    private CacheManager cacheManager;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(
                userRepository, localityWriteValidator, cityRepository, cityDistrictRepository, cacheManager);
    }

    @Test
    @DisplayName("getProfile returns profile when user exists")
    void should_returnProfile_when_userExists() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "alice@example.com", Role.CLIENT, "Alice", "Smith", "+380671234567");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserProfileResponse response = userService.getProfile(userId);

        assertThat(response.id()).isEqualTo(userId);
        assertThat(response.email()).isEqualTo("alice@example.com");
        assertThat(response.role()).isEqualTo("CLIENT");
        assertThat(response.firstName()).isEqualTo("Alice");
        assertThat(response.lastName()).isEqualTo("Smith");
        assertThat(response.phoneNumber()).isEqualTo("+380671234567");
        assertThat(response.isActive()).isTrue();
    }

    @Test
    @DisplayName("getProfile resolves districtName and never queries the district repo when districtId is null")
    void should_notQueryDistrict_when_districtIdNull() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "noloc@example.com", Role.CLIENT, "No", "District", "+380501111111");
        // districtId left null; cityName/oblastName read off denormalised columns.
        user.setCity("Київ");
        user.setRegion("Київська область");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserProfileResponse response = userService.getProfile(userId);

        assertThat(response.cityName())
                .as("cityName is read off the denormalised users.city column (zero query)")
                .isEqualTo("Київ");
        assertThat(response.oblastName())
                .as("oblastName is read off the denormalised users.region column (zero query)")
                .isEqualTo("Київська область");
        assertThat(response.districtName())
                .as("no districtId set → districtName stays null")
                .isNull();
        verify(cityDistrictRepository, never()).findNameUkById(any());
    }

    @Test
    @DisplayName("getProfile resolves districtName via findNameUkById exactly once when districtId is set")
    void should_resolveDistrictName_when_districtIdSet() {
        UUID userId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        User user = buildUser(userId, "withdist@example.com", Role.CLIENT, "Has", "District", "+380501111111");
        user.setCity("Дніпро");
        user.setRegion("Дніпропетровська область");
        user.setDistrictId(districtId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(cityDistrictRepository.findNameUkById(districtId)).thenReturn(Optional.of("Соборний район"));

        UserProfileResponse response = userService.getProfile(userId);

        assertThat(response.cityName()).isEqualTo("Дніпро");
        assertThat(response.oblastName()).isEqualTo("Дніпропетровська область");
        assertThat(response.districtName())
                .as("districtName is the name_uk resolved by findNameUkById for the set districtId")
                .isEqualTo("Соборний район");
        verify(cityDistrictRepository, times(1)).findNameUkById(districtId);
    }

    @Test
    @DisplayName("getProfile returns null districtName when the district lookup resolves empty (orElse(null) arm)")
    void should_returnNullDistrictName_when_lookupEmpty() {
        UUID userId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        User user = buildUser(userId, "staledist@example.com", Role.CLIENT, "Stale", "District", "+380501111111");
        user.setDistrictId(districtId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(cityDistrictRepository.findNameUkById(districtId)).thenReturn(Optional.empty());

        UserProfileResponse response = userService.getProfile(userId);

        assertThat(response.districtName())
                .as("an unresolved districtId falls back to null via orElse(null), never throws")
                .isNull();
        verify(cityDistrictRepository, times(1)).findNameUkById(districtId);
    }

    @Test
    @DisplayName("getProfile resolves oblastId via findOblastIdById exactly once when cityId is set")
    void should_resolveOblastId_when_cityIdSet() {
        UUID userId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        UUID oblastId = UUID.randomUUID();
        User user = buildUser(userId, "withcity@example.com", Role.CLIENT, "Has", "City", "+380501111111");
        user.setCityId(cityId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(cityRepository.findOblastIdById(cityId)).thenReturn(Optional.of(oblastId));

        UserProfileResponse response = userService.getProfile(userId);

        assertThat(response.oblastId())
                .as("oblastId is the parent oblast id resolved by findOblastIdById for the set cityId")
                .isEqualTo(oblastId);
        verify(cityRepository, times(1)).findOblastIdById(cityId);
    }

    @Test
    @DisplayName("getProfile returns null oblastId and never queries the city repo when cityId is null")
    void should_notQueryOblast_when_cityIdNull() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "nocity@example.com", Role.CLIENT, "No", "City", "+380501111111");
        // cityId left null — the home-hub/Location-edit pre-select has nothing to resolve.

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UserProfileResponse response = userService.getProfile(userId);

        assertThat(response.oblastId())
                .as("no cityId set → oblastId stays null and no query is issued")
                .isNull();
        verify(cityRepository, never()).findOblastIdById(any());
    }

    @Test
    @DisplayName("getProfile returns null oblastId when the city lookup resolves empty (orElse(null) arm)")
    void should_returnNullOblastId_when_lookupEmpty() {
        UUID userId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        User user = buildUser(userId, "stalecity@example.com", Role.CLIENT, "Stale", "City", "+380501111111");
        user.setCityId(cityId);

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(cityRepository.findOblastIdById(cityId)).thenReturn(Optional.empty());

        UserProfileResponse response = userService.getProfile(userId);

        assertThat(response.oblastId())
                .as("an unresolved cityId falls back to null via orElse(null), never throws")
                .isNull();
        verify(cityRepository, times(1)).findOblastIdById(cityId);
    }

    @Test
    @DisplayName("getProfile throws NotFoundException when user does not exist")
    void should_throwNotFoundException_when_userNotFound() {
        UUID userId = UUID.randomUUID();

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getProfile(userId))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateProfile applies non-null firstName and lastName")
    void should_updateProfileFields_when_patchApplied() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "bob@example.com", Role.INDEPENDENT_MASTER, "Bob", "Old", "+380631111111");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UpdateProfileRequest request = new UpdateProfileRequest("Robert", "New", null,
                null, null, null, null, null, null);

        UserProfileResponse response = userService.updateProfile(userId, request);

        assertThat(response.firstName()).isEqualTo("Robert");
        assertThat(response.lastName()).isEqualTo("New");
        assertThat(response.phoneNumber()).isEqualTo("+380631111111");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateProfile leaves fields unchanged when all patch fields are null")
    void should_notOverwriteFields_when_patchFieldsAreNull() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "carol@example.com", Role.SALON_OWNER, "Carol", "Jones", "+380661234567");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UpdateProfileRequest request = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null, null);

        UserProfileResponse response = userService.updateProfile(userId, request);

        assertThat(response.firstName()).isEqualTo("Carol");
        assertThat(response.lastName()).isEqualTo("Jones");
        assertThat(response.phoneNumber()).isEqualTo("+380661234567");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateProfile patches phoneNumber when non-null phoneNumber provided")
    void should_updatePhoneNumber_when_phoneNumberPatched() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "master@example.com", Role.INDEPENDENT_MASTER,
                "Ivan", "Kovalenko", "+380631111111");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        UpdateProfileRequest request = new UpdateProfileRequest(null, null, "+380991234567",
                null, null, null, null, null, null);

        UserProfileResponse response = userService.updateProfile(userId, request);

        assertThat(response.phoneNumber()).isEqualTo("+380991234567");
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateProfile (INDEPENDENT_MASTER) validates provider locality and writes full address")
    void should_writeProviderLocality_when_independentMaster() {
        UUID userId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        User user = buildUser(userId, "im@example.com", Role.INDEPENDENT_MASTER, "Ira", "M", "+380631111111");

        City mockCity = mock(City.class);
        Oblast mockOblast = mock(Oblast.class);
        when(mockOblast.getNameUk()).thenReturn("Київська область");
        when(mockCity.getNameUk()).thenReturn("Київ");
        when(mockCity.getOblast()).thenReturn(mockOblast);
        when(cityRepository.findByIdWithOblast(cityId)).thenReturn(Optional.of(mockCity));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var request = new UpdateProfileRequest(null, null, null,
                cityId, districtId, "Lesi Ukrainky", "7", "Blue door", null);

        userService.updateProfile(userId, request);

        verify(localityWriteValidator).validateProviderLocality(new LocalityWriteInput(cityId, districtId));
        assertThat(user.getCityId()).isEqualTo(cityId);
        assertThat(user.getDistrictId()).isEqualTo(districtId);
        assertThat(user.getStreet()).isEqualTo("Lesi Ukrainky");
        assertThat(user.getBuildingNo()).isEqualTo("7");
        assertThat(user.getLocationNote()).isEqualTo("Blue door");
        assertThat(user.getCity()).isEqualTo("Київ");
        assertThat(user.getRegion()).isEqualTo("Київська область");
    }

    @Test
    @DisplayName("updateProfile (CLIENT) persists all 5 locality fields including street/buildingNo/locationNote")
    void should_writeAllLocalityFields_when_client() {
        UUID userId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        UUID districtId = UUID.randomUUID();
        User user = buildUser(userId, "c@example.com", Role.CLIENT, "Cli", "Ent", "+380501111111");

        City mockCity = mock(City.class);
        Oblast mockOblast = mock(Oblast.class);
        when(mockOblast.getNameUk()).thenReturn("Київська область");
        when(mockCity.getNameUk()).thenReturn("Київ");
        when(mockCity.getOblast()).thenReturn(mockOblast);
        when(cityRepository.findByIdWithOblast(cityId)).thenReturn(Optional.of(mockCity));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var request = new UpdateProfileRequest(null, null, null,
                cityId, districtId, "Shevchenka", "12A", "ring twice", null);

        userService.updateProfile(userId, request);

        verify(localityWriteValidator).validateClientLocality(new LocalityWriteInput(cityId, districtId));
        verify(localityWriteValidator, never()).validateProviderLocality(any());
        assertThat(user.getCityId()).isEqualTo(cityId);
        assertThat(user.getDistrictId()).isEqualTo(districtId);
        assertThat(user.getStreet()).isEqualTo("Shevchenka");
        assertThat(user.getBuildingNo()).isEqualTo("12A");
        assertThat(user.getLocationNote()).isEqualTo("ring twice");
        assertThat(user.getCity()).isEqualTo("Київ");
        assertThat(user.getRegion()).isEqualTo("Київська область");
    }

    @Test
    @DisplayName("updateProfile (CLIENT) persists street, buildingNo, locationNote when all three are supplied")
    void should_persist_street_buildingNo_locationNote_when_CLIENT_calls_updateProfile() {
        UUID userId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        User user = buildUser(userId, "c6@example.com", Role.CLIENT, "Test", "Client", "+380501111111");

        City mockCity = mock(City.class);
        when(mockCity.getNameUk()).thenReturn("Одеса");
        when(mockCity.getOblast()).thenReturn(null);
        when(cityRepository.findByIdWithOblast(cityId)).thenReturn(Optional.of(mockCity));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var request = new UpdateProfileRequest(null, null, null,
                cityId, null, "вул. Дерибасівська", "5", "yellow building", null);

        userService.updateProfile(userId, request);

        assertThat(user.getStreet()).isEqualTo("вул. Дерибасівська");
        assertThat(user.getBuildingNo()).isEqualTo("5");
        assertThat(user.getLocationNote()).isEqualTo("yellow building");
    }

    @Test
    @DisplayName("updateProfile (CLIENT) with null cityId clears cityId/districtId but retains pre-existing street fields")
    void should_clearAllLocalityFields_when_clientSendsNullCityId() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "c3@example.com", Role.CLIENT, "Null", "City", "+380501111111");
        // Pre-populate locality so we can confirm referential IDs are cleared while
        // free-text fields (street/buildingNo/locationNote) are retained via the null-guard.
        user.setCityId(UUID.randomUUID());
        user.setDistrictId(UUID.randomUUID());
        user.setStreet("Old Street");
        user.setBuildingNo("1");
        user.setLocationNote("old note");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var request = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null, null);

        userService.updateProfile(userId, request);

        verify(localityWriteValidator).validateClientLocality(new LocalityWriteInput(null, null));
        assertThat(user.getCityId()).isNull();
        assertThat(user.getDistrictId()).isNull();
        // street/buildingNo/locationNote are null in the request — null-guard skips them → retained.
        assertThat(user.getStreet()).isEqualTo("Old Street");
        assertThat(user.getBuildingNo()).isEqualTo("1");
        assertThat(user.getLocationNote()).isEqualTo("old note");
    }

    @Test
    @DisplayName("updateProfile (CLIENT) does not overwrite street when street is omitted from the PATCH")
    void should_notOverwriteStreet_when_clientOmitsStreetInPatch() {
        UUID userId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        User user = buildUser(userId, "c4@example.com", Role.CLIENT, "Oksana", "P", "+380671234567");
        user.setStreet("вул. Науки");

        when(cityRepository.findByIdWithOblast(cityId)).thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // PATCH sends only cityId — street, buildingNo, locationNote all null.
        var request = new UpdateProfileRequest(null, null, null,
                cityId, null, null, null, null, null);

        userService.updateProfile(userId, request);

        assertThat(user.getStreet()).isEqualTo("вул. Науки");
    }

    @Test
    @DisplayName("updateProfile (INDEPENDENT_MASTER) does not overwrite buildingNo when buildingNo is omitted from the PATCH")
    void should_notOverwriteBuildingNo_when_independentMasterOmitsBuildingNoInPatch() {
        UUID userId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        User user = buildUser(userId, "im3@example.com", Role.INDEPENDENT_MASTER, "Ira", "M", "+380631111111");
        user.setBuildingNo("5B");

        when(cityRepository.findByIdWithOblast(cityId)).thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // PATCH sends cityId and street but omits buildingNo — null-guard must retain the pre-existing value.
        var request = new UpdateProfileRequest(null, null, null,
                cityId, null, "Lesi Ukrainky", null, null, null);

        userService.updateProfile(userId, request);

        assertThat(user.getBuildingNo()).isEqualTo("5B");
    }

    @Test
    @DisplayName("updateProfile (SALON_MASTER) writes no personal locality and never calls the validator")
    void should_notWriteLocality_when_salonMaster() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "sm@example.com", Role.SALON_MASTER, "Sal", "M", "+380501111111");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var request = new UpdateProfileRequest("Sal", null, null,
                UUID.randomUUID(), UUID.randomUUID(), "St", "1", "note", null);

        userService.updateProfile(userId, request);

        verify(localityWriteValidator, never()).validateProviderLocality(any());
        verify(localityWriteValidator, never()).validateClientLocality(any());
        assertThat(user.getCityId()).isNull();
    }

    @Test
    @DisplayName("updateProfile (CLIENT) with no locality succeeds and never blocks the save")
    void should_succeed_when_clientHasNoLocality() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "c2@example.com", Role.CLIENT, "No", "Loc", "+380501111111");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var request = new UpdateProfileRequest("No", "Loc", null,
                null, null, null, null, null, null);

        userService.updateProfile(userId, request);

        verify(localityWriteValidator).validateClientLocality(new LocalityWriteInput(null, null));
        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("writeCityDisplayStrings — sets city name but leaves region null when city has no oblast association")
    void should_setOnlyCityName_when_oblastAssociationIsNull() {
        UUID userId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        User user = buildUser(userId, "im4@example.com", Role.INDEPENDENT_MASTER, "Vira", "K", "+380631111111");

        City mockCity = mock(City.class);
        when(mockCity.getNameUk()).thenReturn("Харків");
        when(mockCity.getOblast()).thenReturn(null);
        when(cityRepository.findByIdWithOblast(cityId)).thenReturn(Optional.of(mockCity));

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var request = new UpdateProfileRequest(null, null, null,
                cityId, null, null, null, null, null);

        userService.updateProfile(userId, request);

        assertThat(user.getCity())
                .as("city display string must be set even when oblast is absent")
                .isEqualTo("Харків");
        assertThat(user.getRegion())
                .as("region must remain null when the city has no oblast association")
                .isNull();
    }

    @Test
    @DisplayName("writeCityDisplayStrings — leaves city and region null when city is not found in the repository")
    void should_leaveCityAndRegionNull_when_cityNotFoundInRepository() {
        UUID userId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        User user = buildUser(userId, "c5@example.com", Role.CLIENT, "Empty", "City", "+380501111111");

        when(cityRepository.findByIdWithOblast(cityId)).thenReturn(Optional.empty());
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var request = new UpdateProfileRequest(null, null, null,
                cityId, null, null, null, null, null);

        userService.updateProfile(userId, request);

        assertThat(user.getCity())
                .as("city display string must remain null when cityRepository returns empty")
                .isNull();
        assertThat(user.getRegion())
                .as("region display string must remain null when cityRepository returns empty")
                .isNull();
    }

    @Test
    @DisplayName("updateProfile (SALON_OWNER) writes no personal locality and never calls the validator")
    void should_notWriteLocality_when_salonOwner() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "owner@example.com", Role.SALON_OWNER, "Own", "Er", "+380501111111");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var request = new UpdateProfileRequest("Own", null, null,
                UUID.randomUUID(), UUID.randomUUID(), "St", "1", "note", null);

        userService.updateProfile(userId, request);

        verify(localityWriteValidator, never()).validateProviderLocality(any());
        verify(localityWriteValidator, never()).validateClientLocality(any());
        verify(cityRepository, never()).findByIdWithOblast(any());
        assertThat(user.getCityId()).isNull();
        assertThat(user.getCity()).isNull();
        assertThat(user.getRegion()).isNull();
    }

    @Test
    @DisplayName("updateProfile (SALON_ADMIN) writes no personal locality and never calls the validator or city repository")
    void should_notWriteLocality_when_salonAdmin() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "admin@example.com", Role.SALON_ADMIN, "Adm", "In", "+380501111111");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var request = new UpdateProfileRequest("Adm", null, null,
                UUID.randomUUID(), UUID.randomUUID(), "St", "1", "note", null);

        userService.updateProfile(userId, request);

        verify(localityWriteValidator, never()).validateProviderLocality(any());
        verify(localityWriteValidator, never()).validateClientLocality(any());
        verify(cityRepository, never()).findByIdWithOblast(any());
        assertThat(user.getCityId()).isNull();
        assertThat(user.getCity()).isNull();
        assertThat(user.getRegion()).isNull();
    }

    // ── updateMasterProfile ───────────────────────────────────────────────────

    @Test
    @DisplayName("updateMasterProfile sets phone, bio, and instagram from request")
    void should_updatePhoneAndBioAndInstagram_when_updateMasterProfileCalled() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "master@example.com", Role.INDEPENDENT_MASTER,
                "Olena", "Koval", "+380630000000");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        MasterProfileUpdateRequest request =
                new MasterProfileUpdateRequest(null, null, "+380671112233", "bio text", "@instagram");

        MasterPublicProfileResponse response = userService.updateMasterProfile(userId, request);

        assertThat(user.getPhoneNumber()).isEqualTo("+380671112233");
        assertThat(user.getBio()).isEqualTo("bio text");
        assertThat(user.getInstagram()).isEqualTo("@instagram");
        assertThat(response.phoneNumber()).isEqualTo("+380671112233");
        assertThat(response.bio()).isEqualTo("bio text");
        assertThat(response.instagram()).isEqualTo("@instagram");
    }

    @Test
    @DisplayName("updateMasterProfile throws ForbiddenException when user role is CLIENT")
    void should_throwForbiddenException_when_clientRoleCallsUpdateMasterProfile() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "client@example.com", Role.CLIENT, "Test", "User", "+380671234567");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.updateMasterProfile(
                userId, new MasterProfileUpdateRequest(null, null, "+380671112233", null, null)))
                .isInstanceOf(ForbiddenException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateMasterProfile throws NotFoundException when user does not exist")
    void should_throwNotFoundException_when_userNotFoundInUpdateMasterProfile() {
        UUID userId = UUID.randomUUID();

        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateMasterProfile(
                userId, new MasterProfileUpdateRequest(null, null, "+380671112233", null, null)))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("User not found");

        verify(userRepository, never()).save(any());
    }

    @Test
    @DisplayName("updateMasterProfile does not overwrite bio when bio is null in request")
    void should_notOverwriteBio_when_bioIsNullInRequest() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "master2@example.com", Role.INDEPENDENT_MASTER,
                "Iryna", "M", "+380671111111");
        user.setBio("existing bio");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.updateMasterProfile(userId,
                new MasterProfileUpdateRequest(null, null, "+380671111111", null, null));

        assertThat(user.getBio()).isEqualTo("existing bio");
    }

    @Test
    @DisplayName("updateMasterProfile does not overwrite instagram when instagram is null in request")
    void should_notOverwriteInstagram_when_instagramIsNullInRequest() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "master3@example.com", Role.INDEPENDENT_MASTER,
                "Vira", "K", "+380671111111");
        user.setInstagram("@old_handle");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.updateMasterProfile(userId,
                new MasterProfileUpdateRequest(null, null, "+380671111111", null, null));

        assertThat(user.getInstagram()).isEqualTo("@old_handle");
    }

    @Test
    @DisplayName("updateMasterProfile updates only phone when bio and instagram are null in request")
    void should_updateOnlyPhone_when_bioAndInstagramAreNull() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "master4@example.com", Role.INDEPENDENT_MASTER,
                "Natalia", "P", "+380670000000");
        user.setBio("original bio");
        user.setInstagram("@original");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.updateMasterProfile(userId,
                new MasterProfileUpdateRequest(null, null, "+380679999999", null, null));

        assertThat(user.getPhoneNumber()).isEqualTo("+380679999999");
        assertThat(user.getBio()).isEqualTo("original bio");
        assertThat(user.getInstagram()).isEqualTo("@original");
    }

    @Test
    @DisplayName("updateMasterProfile updates firstName and lastName when provided")
    void should_updateFirstNameAndLastName_when_provided() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "master5@example.com", Role.INDEPENDENT_MASTER,
                "Старе", "Ім'я", "+380670000000");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        MasterProfileUpdateRequest request =
                new MasterProfileUpdateRequest("Нова", "Назва", null, null, null);

        MasterPublicProfileResponse response = userService.updateMasterProfile(userId, request);

        assertThat(user.getFirstName()).isEqualTo("Нова");
        assertThat(user.getLastName()).isEqualTo("Назва");
        assertThat(response.firstName()).isEqualTo("Нова");
        assertThat(response.lastName()).isEqualTo("Назва");
    }

    @Test
    @DisplayName("updateMasterProfile does not overwrite firstName when firstName is null")
    void should_notOverwriteFirstName_when_firstNameIsNullInRequest() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "master6@example.com", Role.INDEPENDENT_MASTER,
                "Існуюче", "Ім'я", "+380670000000");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.updateMasterProfile(userId,
                new MasterProfileUpdateRequest(null, null, null, null, null));

        assertThat(user.getFirstName()).isEqualTo("Існуюче");
    }

    @Test
    @DisplayName("updateMasterProfile does not overwrite firstName when firstName is blank")
    void should_notOverwriteFirstName_when_firstNameIsBlankInRequest() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "master7@example.com", Role.INDEPENDENT_MASTER,
                "Існуюче", "Ім'я", "+380670000000");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.updateMasterProfile(userId,
                new MasterProfileUpdateRequest("   ", null, null, null, null));

        assertThat(user.getFirstName()).isEqualTo("Існуюче");
    }

    @Test
    @DisplayName("updateMasterProfile does not overwrite lastName when lastName is null")
    void should_notOverwriteLastName_when_lastNameIsNullInRequest() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "master8@example.com", Role.INDEPENDENT_MASTER,
                "Існуюче", "Прізвище", "+380670000000");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.updateMasterProfile(userId,
                new MasterProfileUpdateRequest(null, null, null, null, null));

        assertThat(user.getLastName()).isEqualTo("Прізвище");
    }

    @Test
    @DisplayName("updateMasterProfile does not overwrite lastName when lastName is blank")
    void should_notOverwriteLastName_when_lastNameIsBlankInRequest() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "master9@example.com", Role.INDEPENDENT_MASTER,
                "Існуюче", "Прізвище", "+380670000000");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.updateMasterProfile(userId,
                new MasterProfileUpdateRequest(null, "   ", null, null, null));

        assertThat(user.getLastName()).isEqualTo("Прізвище");
    }

    @Test
    @DisplayName("updateMasterProfile does not overwrite phoneNumber when phoneNumber is null")
    void should_notOverwritePhone_when_phoneIsNullInRequest() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "master10@example.com", Role.INDEPENDENT_MASTER,
                "Іван", "Коваль", "+380670000000");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        userService.updateMasterProfile(userId,
                new MasterProfileUpdateRequest(null, null, null, null, null));

        assertThat(user.getPhoneNumber()).isEqualTo("+380670000000");
    }

    // ── updateProfile — Phase 19.6 instagram normalisation ────────────────────
    // The service mutates the real User entity in place (no save() — Hibernate
    // dirty-checking flushes on commit), so user.getInstagram() reflects the exact
    // value handed to User#setInstagram. That is a stronger assertion than an
    // ArgumentCaptor on a mocked setter: it pins the normalised form actually stored.

    @Test
    @DisplayName("updateProfile persists a valid instagram handle verbatim when no leading at-sign")
    void should_persistInstagramHandle_when_validHandleWithoutAtSign() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "ig1@example.com", Role.CLIENT, "Iga", "Han", "+380501111111");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var request = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null, "beauty_studio");

        UserProfileResponse response = userService.updateProfile(userId, request);

        assertThat(user.getInstagram())
                .as("a bare handle is stored verbatim (already canonical)")
                .isEqualTo("beauty_studio");
        assertThat(response.instagram())
                .as("the response echoes the persisted handle")
                .isEqualTo("beauty_studio");
    }

    @Test
    @DisplayName("updateProfile normalises an at-prefixed instagram handle by stripping the leading at-sign and trimming")
    void should_normaliseInstagram_when_handleHasLeadingAtSignAndSpaces() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "ig2@example.com", Role.INDEPENDENT_MASTER, "Iga", "Han", "+380501111111");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // Surrounding spaces + a single leading @ — normalizeInstagram strips both.
        var request = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null, "  @beauty.master  ");

        userService.updateProfile(userId, request);

        assertThat(user.getInstagram())
                .as("leading @ stripped and surrounding whitespace trimmed → canonical handle")
                .isEqualTo("beauty.master");
    }

    @Test
    @DisplayName("updateProfile leaves an existing instagram unchanged when instagram is null in the patch")
    void should_notOverwriteInstagram_when_instagramIsNullInPatch() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "ig3@example.com", Role.CLIENT, "Iga", "Han", "+380501111111");
        user.setInstagram("kept_handle");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var request = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null, null);

        userService.updateProfile(userId, request);

        assertThat(user.getInstagram())
                .as("a null instagram in the patch must leave the stored handle untouched")
                .isEqualTo("kept_handle");
    }

    @Test
    @DisplayName("updateProfile clears instagram to null when the patch supplies a blank value")
    void should_clearInstagram_when_blankValueSupplied() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "ig4@example.com", Role.CLIENT, "Iga", "Han", "+380501111111");
        user.setInstagram("old_handle");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // Blank (whitespace-only) is the mobile client's CLEAR signal — normalises to null.
        var request = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null, "   ");

        userService.updateProfile(userId, request);

        assertThat(user.getInstagram())
                .as("a blank instagram normalises to null and clears the stored column")
                .isNull();
    }

    @Test
    @DisplayName("updateProfile clears instagram to null when the patch supplies an at-sign-only value")
    void should_clearInstagram_when_atSignOnlyValueSupplied() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "ig5@example.com", Role.CLIENT, "Iga", "Han", "+380501111111");
        user.setInstagram("old_handle");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        // "@" alone → strip the @ → blank → null. This is the boundary between
        // "clear" and a one-char handle, and the V61 CHECK never sees an empty string.
        var request = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null, "@");

        userService.updateProfile(userId, request);

        assertThat(user.getInstagram())
                .as("an @-only instagram normalises to null (strip @ → blank → null)")
                .isNull();
    }

    @Test
    @DisplayName("updateProfile persists a full instagram.com URL verbatim (URL arm of the pattern)")
    void should_persistInstagramUrl_when_fullUrlSupplied() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "ig6@example.com", Role.CLIENT, "Iga", "Han", "+380501111111");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));

        var request = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null, "https://www.instagram.com/beauty.studio/");

        userService.updateProfile(userId, request);

        assertThat(user.getInstagram())
                .as("a full instagram.com URL has no leading @ — stored verbatim")
                .isEqualTo("https://www.instagram.com/beauty.studio/");
    }

    // ── updateProfile — propagate from validator ──────────────────────────────

    @Test
    @DisplayName("updateProfile propagates BusinessException from the locality validator and does not save")
    void should_propagate_when_validatorRejects() {
        UUID userId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        User user = buildUser(userId, "im2@example.com", Role.INDEPENDENT_MASTER, "I", "M", "+380631111111");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        var request = new UpdateProfileRequest(null, null, null,
                cityId, null, null, null, null, null);
        doThrow(new BusinessException("District is required for the selected city"))
                .when(localityWriteValidator).validateProviderLocality(new LocalityWriteInput(cityId, null));

        assertThatThrownBy(() -> userService.updateProfile(userId, request))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    // ── Cache eviction — requires Spring context ──────────────────────────────

    /**
     * Documents why cache-eviction correctness cannot be verified under
     * {@link org.junit.jupiter.api.extension.ExtendWith} (MockitoExtension) alone.
     *
     * <p>{@link UserService#evictUserCachesAfterCommit} registers a callback via
     * {@link TransactionSynchronizationManager#registerSynchronization}, which is a
     * no-op when no transaction synchronisation is active. Under
     * {@code MockitoExtension} there is no active transaction, so the callback is
     * never registered and the {@link org.springframework.cache.CacheManager} mock
     * is never invoked — asserting on it would give a false-negative (always passes,
     * even if the eviction logic is broken).
     *
     * <p>The correct coverage level for this behaviour is a Spring slice or full
     * integration test where a real {@link org.springframework.transaction.PlatformTransactionManager}
     * and a {@link org.springframework.cache.CacheManager} bean are in scope, so that
     * the {@code afterCommit} callback actually fires and cache eviction can be
     * observed on the real cache.
     */
    @Test
    @Disabled("Requires Spring context — cache eviction is tested via integration test")
    void should_evictBothCaches_afterCommit_onProfileUpdate() {
        // Not implemented at unit-test level. See Javadoc above for the full rationale.
        // Coverage lives in the @SpringBootTest integration test for UserService /
        // IndependentMasterController where the TransactionSynchronizationManager
        // callback fires after the real commit boundary.
    }

    private User buildUser(UUID id, String email, Role role,
                           String firstName, String lastName, String phoneNumber) {
        var user = new User(email, "hashed-password", role, firstName, lastName, phoneNumber);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
