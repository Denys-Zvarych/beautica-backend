package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.auth.registration.RegistrationTicketService;
import com.beautica.common.exception.IdentityException;
import com.beautica.master.service.MasterService;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PhoneRegistrationService — phone-only account from a registration ticket (B514, AUTH-02)")
class PhoneRegistrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");
    private static final String PHONE = "+380501234567";

    @Mock
    private RegistrationTicketService ticketService;
    @Mock
    private UserRepository userRepository;
    @Mock
    private MasterService masterService;
    @Mock
    private AuthResponseBuilder authResponseBuilder;

    private final PasswordEncoder passwordEncoder = spy(new BCryptPasswordEncoder(4));
    private PhoneRegistrationService service;

    @BeforeEach
    void setUp() {
        service = new PhoneRegistrationService(ticketService, userRepository, passwordEncoder,
                masterService, authResponseBuilder, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static PhoneRegistrationService.PhoneAccount account(Role role) {
        RegisterRequest consent = new RegisterRequest(null, "pw", SelfRegistrationRole.CLIENT, "Olena", null, null,
                null, true, "2026-10-20", "2026-10-20", "ticket");
        return new PhoneRegistrationService.PhoneAccount("ticket", "Str0ng!Passw0rd#", role, "Olena", " ", null,
                consent);
    }

    @Test
    @DisplayName("should_createVerifiedPhoneOnlyUserAndSignIn_when_ticketValid")
    void should_createVerifiedPhoneOnlyUserAndSignIn_when_ticketValid() {
        when(ticketService.consume("ticket")).thenReturn(PHONE);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        AuthResponse auth = AuthResponse.of("a", "r", UUID.randomUUID(), null, Role.CLIENT);
        when(authResponseBuilder.buildAuthResponse(any())).thenReturn(auth);

        AuthResponse response = service.register(account(Role.CLIENT));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(saved.capture());
        assertThat(response).isSameAs(auth);
        assertThat(saved.getValue().getEmail()).isNull();
        assertThat(saved.getValue().getPhoneNumber()).isEqualTo(PHONE);
        assertThat(saved.getValue().getPhoneVerifiedAt()).isEqualTo(NOW);
        assertThat(saved.getValue().getLastName()).isNull();
        assertThat(saved.getValue().getTermsAcceptedAt()).isEqualTo(NOW);
        verify(masterService, never()).createMasterForIndependentUser(any());
    }

    @Test
    @DisplayName("should_hashPasswordBeforeConsumingTicket_when_registering")
    void should_hashPasswordBeforeConsumingTicket_when_registering() {
        when(ticketService.consume("ticket")).thenReturn(PHONE);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.register(account(Role.CLIENT));

        InOrder order = inOrder(passwordEncoder, ticketService);
        order.verify(passwordEncoder).encode("Str0ng!Passw0rd#");
        order.verify(ticketService).consume("ticket");
    }

    @Test
    @DisplayName("should_createMasterProfile_when_independentMaster")
    void should_createMasterProfile_when_independentMaster() {
        when(ticketService.consume("ticket")).thenReturn(PHONE);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        service.register(account(Role.INDEPENDENT_MASTER));

        verify(masterService).createMasterForIndependentUser(any());
    }

    @Test
    @DisplayName("should_throwPhoneAlreadyRegistered_when_verifiedAccountHoldsPhone")
    void should_throwPhoneAlreadyRegistered_when_verifiedAccountHoldsPhone() {
        when(ticketService.consume("ticket")).thenReturn(PHONE);
        when(userRepository.existsByVerifiedPhone(PHONE)).thenReturn(true);

        assertThatThrownBy(() -> service.register(account(Role.CLIENT)))
                .isInstanceOfSatisfying(IdentityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IdentityException.Code.PHONE_ALREADY_REGISTERED));
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("should_mapUniqueIndexRace_to_phoneAlreadyRegistered")
    void should_mapUniqueIndexRace_to_phoneAlreadyRegistered() {
        when(ticketService.consume("ticket")).thenReturn(PHONE);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("dup",
                new SQLException("duplicate key value violates unique constraint \"ux_users_verified_phone\"")));

        assertThatThrownBy(() -> service.register(account(Role.CLIENT)))
                .isInstanceOfSatisfying(IdentityException.class,
                        e -> assertThat(e.getCode()).isEqualTo(IdentityException.Code.PHONE_ALREADY_REGISTERED));
    }

    @Test
    @DisplayName("should_rethrow_when_otherIntegrityViolation")
    void should_rethrow_when_otherIntegrityViolation() {
        when(ticketService.consume("ticket")).thenReturn(PHONE);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("other",
                new SQLException("violates check constraint \"ck_users_terms_consent_triple\"")));

        assertThatThrownBy(() -> service.register(account(Role.CLIENT)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
