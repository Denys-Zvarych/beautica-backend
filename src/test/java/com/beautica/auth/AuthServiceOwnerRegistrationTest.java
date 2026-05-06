package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.common.exception.BusinessException;
import com.beautica.master.service.MasterService;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthService — SALON_OWNER registration unit")
class AuthServiceOwnerRegistrationTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private TokenGenerator tokenGenerator;

    @Mock
    private MasterService masterService;

    @Mock
    private AuthResponseBuilder authResponseBuilder;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
        authService = new AuthService(
                userRepository,
                refreshTokenRepository,
                passwordEncoder,
                tokenGenerator,
                masterService,
                authResponseBuilder
        );
    }

    // -------------------------------------------------------------------------
    // businessName is required for SALON_OWNER
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("register — throws BusinessException when SALON_OWNER omits businessName")
    void should_requireBusinessName_when_registeringAsSalonOwner() {
        var request = new RegisterRequest(
                "owner@beautica.test", "password123",
                SelfRegistrationRole.SALON_OWNER, "Olena", "Koval", null, null);

        when(userRepository.existsByEmail("owner@beautica.test")).thenReturn(false);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("businessName is required for SALON_OWNER");

        verify(userRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // firstName and lastName are optional for SALON_OWNER
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("register — accepts null firstName and lastName when SALON_OWNER provides businessName")
    void should_allowNullFirstAndLastName_when_registeringAsSalonOwner() {
        var request = new RegisterRequest(
                "owner@beautica.test", "password123",
                SelfRegistrationRole.SALON_OWNER, null, null, null, "Beauty Studio");

        var stubResponse = AuthResponse.of("access-tok", "refresh-tok",
                UUID.randomUUID(), "owner@beautica.test", Role.SALON_OWNER);

        when(userRepository.existsByEmail("owner@beautica.test")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });
        when(authResponseBuilder.buildAuthResponse(any(User.class))).thenReturn(stubResponse);

        var response = authService.register(request);

        assertThat(response.role()).isEqualTo(Role.SALON_OWNER);
        verify(userRepository).save(any(User.class));
    }

    // -------------------------------------------------------------------------
    // blank businessName (whitespace-only) is rejected
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("register — throws BusinessException when SALON_OWNER provides whitespace-only businessName")
    void should_rejectEmptyBusinessName_when_registeringAsSalonOwner() {
        var request = new RegisterRequest(
                "owner@beautica.test", "password123",
                SelfRegistrationRole.SALON_OWNER, "Olena", "Koval", null, "   ");

        when(userRepository.existsByEmail("owner@beautica.test")).thenReturn(false);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("businessName is required for SALON_OWNER");

        verify(userRepository, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // businessName is persisted on the created User
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("register — persists businessName on User entity when SALON_OWNER registers")
    void should_storeBusinessName_on_createdUser() {
        var request = new RegisterRequest(
                "owner@beautica.test", "password123",
                SelfRegistrationRole.SALON_OWNER, "Olena", "Koval", null, "Lviv Beauty Hub");

        var stubResponse = AuthResponse.of("access-tok", "refresh-tok",
                UUID.randomUUID(), "owner@beautica.test", Role.SALON_OWNER);

        when(userRepository.existsByEmail("owner@beautica.test")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });
        when(authResponseBuilder.buildAuthResponse(any(User.class))).thenReturn(stubResponse);

        authService.register(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());

        assertThat(captor.getValue().getBusinessName())
                .as("businessName stored on User must match the value from the registration request")
                .isEqualTo("Lviv Beauty Hub");
    }
}
