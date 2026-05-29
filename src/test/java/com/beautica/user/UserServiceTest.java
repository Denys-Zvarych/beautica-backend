package com.beautica.user;

import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.location.LocalityWriteInput;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.location.entity.City;
import com.beautica.location.entity.Oblast;
import com.beautica.location.repository.CityRepository;
import org.junit.jupiter.api.BeforeEach;
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
    private CacheManager cacheManager;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, localityWriteValidator, cityRepository, cacheManager);
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
        when(userRepository.save(user)).thenReturn(user);

        UpdateProfileRequest request = new UpdateProfileRequest("Robert", "New", null,
                null, null, null, null, null);

        UserProfileResponse response = userService.updateProfile(userId, request);

        assertThat(response.firstName()).isEqualTo("Robert");
        assertThat(response.lastName()).isEqualTo("New");
        assertThat(response.phoneNumber()).isEqualTo("+380631111111");
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("updateProfile leaves fields unchanged when all patch fields are null")
    void should_notOverwriteFields_when_patchFieldsAreNull() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "carol@example.com", Role.SALON_OWNER, "Carol", "Jones", "+380661234567");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        UpdateProfileRequest request = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null);

        UserProfileResponse response = userService.updateProfile(userId, request);

        assertThat(response.firstName()).isEqualTo("Carol");
        assertThat(response.lastName()).isEqualTo("Jones");
        assertThat(response.phoneNumber()).isEqualTo("+380661234567");
        verify(userRepository).save(user);
    }

    @Test
    @DisplayName("updateProfile patches phoneNumber when non-null phoneNumber provided")
    void should_updatePhoneNumber_when_phoneNumberPatched() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "master@example.com", Role.INDEPENDENT_MASTER,
                "Ivan", "Kovalenko", "+380631111111");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        UpdateProfileRequest request = new UpdateProfileRequest(null, null, "+380991234567",
                null, null, null, null, null);

        UserProfileResponse response = userService.updateProfile(userId, request);

        assertThat(response.phoneNumber()).isEqualTo("+380991234567");
        verify(userRepository).save(user);
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
        when(userRepository.save(user)).thenReturn(user);

        var request = new UpdateProfileRequest(null, null, null,
                cityId, districtId, "Lesi Ukrainky", "7", "Blue door");

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
        when(userRepository.save(user)).thenReturn(user);

        var request = new UpdateProfileRequest(null, null, null,
                cityId, districtId, "Shevchenka", "12A", "ring twice");

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
        when(userRepository.save(user)).thenReturn(user);

        var request = new UpdateProfileRequest(null, null, null,
                cityId, null, "вул. Дерибасівська", "5", "yellow building");

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
        when(userRepository.save(user)).thenReturn(user);

        var request = new UpdateProfileRequest(null, null, null,
                null, null, null, null, null);

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
        when(userRepository.save(user)).thenReturn(user);

        // PATCH sends only cityId — street, buildingNo, locationNote all null.
        var request = new UpdateProfileRequest(null, null, null,
                cityId, null, null, null, null);

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
        when(userRepository.save(user)).thenReturn(user);

        // PATCH sends cityId and street but omits buildingNo — null-guard must retain the pre-existing value.
        var request = new UpdateProfileRequest(null, null, null,
                cityId, null, "Lesi Ukrainky", null, null);

        userService.updateProfile(userId, request);

        assertThat(user.getBuildingNo()).isEqualTo("5B");
    }

    @Test
    @DisplayName("updateProfile (SALON_MASTER) writes no personal locality and never calls the validator")
    void should_notWriteLocality_when_salonMaster() {
        UUID userId = UUID.randomUUID();
        User user = buildUser(userId, "sm@example.com", Role.SALON_MASTER, "Sal", "M", "+380501111111");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        var request = new UpdateProfileRequest("Sal", null, null,
                UUID.randomUUID(), UUID.randomUUID(), "St", "1", "note");

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
        when(userRepository.save(user)).thenReturn(user);

        var request = new UpdateProfileRequest("No", "Loc", null,
                null, null, null, null, null);

        userService.updateProfile(userId, request);

        verify(localityWriteValidator).validateClientLocality(new LocalityWriteInput(null, null));
        verify(userRepository).save(user);
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
        when(userRepository.save(user)).thenReturn(user);

        var request = new UpdateProfileRequest(null, null, null,
                cityId, null, null, null, null);

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
        when(userRepository.save(user)).thenReturn(user);

        var request = new UpdateProfileRequest(null, null, null,
                cityId, null, null, null, null);

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
        when(userRepository.save(user)).thenReturn(user);

        var request = new UpdateProfileRequest("Own", null, null,
                UUID.randomUUID(), UUID.randomUUID(), "St", "1", "note");

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
        when(userRepository.save(user)).thenReturn(user);

        var request = new UpdateProfileRequest("Adm", null, null,
                UUID.randomUUID(), UUID.randomUUID(), "St", "1", "note");

        userService.updateProfile(userId, request);

        verify(localityWriteValidator, never()).validateProviderLocality(any());
        verify(localityWriteValidator, never()).validateClientLocality(any());
        verify(cityRepository, never()).findByIdWithOblast(any());
        assertThat(user.getCityId()).isNull();
        assertThat(user.getCity()).isNull();
        assertThat(user.getRegion()).isNull();
    }

    @Test
    @DisplayName("updateProfile propagates BusinessException from the locality validator and does not save")
    void should_propagate_when_validatorRejects() {
        UUID userId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        User user = buildUser(userId, "im2@example.com", Role.INDEPENDENT_MASTER, "I", "M", "+380631111111");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        var request = new UpdateProfileRequest(null, null, null,
                cityId, null, null, null, null);
        doThrow(new BusinessException("District is required for the selected city"))
                .when(localityWriteValidator).validateProviderLocality(new LocalityWriteInput(cityId, null));

        assertThatThrownBy(() -> userService.updateProfile(userId, request))
                .isInstanceOf(BusinessException.class);

        verify(userRepository, never()).save(any());
    }

    private User buildUser(UUID id, String email, Role role,
                           String firstName, String lastName, String phoneNumber) {
        var user = new User(email, "hashed-password", role, firstName, lastName, phoneNumber);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
