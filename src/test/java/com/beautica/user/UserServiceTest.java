package com.beautica.user;

import com.beautica.auth.Role;
import com.beautica.common.exception.NotFoundException;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserService — unit")
class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(userRepository);
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

        UpdateProfileRequest request = new UpdateProfileRequest("Robert", "New", null);

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

        UpdateProfileRequest request = new UpdateProfileRequest(null, null, null);

        UserProfileResponse response = userService.updateProfile(userId, request);

        assertThat(response.firstName()).isEqualTo("Carol");
        assertThat(response.lastName()).isEqualTo("Jones");
        assertThat(response.phoneNumber()).isEqualTo("+380661234567");
        verify(userRepository).save(user);
    }

    private User buildUser(UUID id, String email, Role role,
                           String firstName, String lastName, String phoneNumber) {
        var user = new User(email, "hashed-password", role, firstName, lastName, phoneNumber);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
