package com.beautica.user;

import com.beautica.auth.Role;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.location.LocalityWriteInput;
import com.beautica.location.LocalityWriteValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
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

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository, localityWriteValidator);
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

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        var request = new UpdateProfileRequest(null, null, null,
                cityId, districtId, "Lesi Ukrainky", "7", "Blue door");

        userService.updateProfile(userId, request);

        verify(localityWriteValidator).validateProviderLocality(new LocalityWriteInput(cityId, districtId));
        assertThat(user.getCityId()).isEqualTo(cityId);
        assertThat(user.getDistrictId()).isEqualTo(districtId);
        assertThat(user.getStreet()).isEqualTo("Lesi Ukrainky");
    }

    @Test
    @DisplayName("updateProfile (CLIENT) validates client locality and writes only city_id/district_id")
    void should_writeClientLocality_when_client() {
        UUID userId = UUID.randomUUID();
        UUID cityId = UUID.randomUUID();
        User user = buildUser(userId, "c@example.com", Role.CLIENT, "Cli", "Ent", "+380501111111");

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        // street is supplied but a CLIENT must not have it persisted (discovery default only).
        var request = new UpdateProfileRequest(null, null, null,
                cityId, null, "Ignored St", "99", "ignored");

        userService.updateProfile(userId, request);

        verify(localityWriteValidator).validateClientLocality(new LocalityWriteInput(cityId, null));
        verify(localityWriteValidator, never()).validateProviderLocality(any());
        assertThat(user.getCityId()).isEqualTo(cityId);
        assertThat(user.getStreet()).isNull();
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
