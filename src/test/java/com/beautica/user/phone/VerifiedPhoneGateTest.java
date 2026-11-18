package com.beautica.user.phone;

import com.beautica.auth.Role;
import com.beautica.common.exception.IdentityException;
import com.beautica.config.AuthConsentProperties;
import com.beautica.user.PhoneGateRow;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VerifiedPhoneGate — 409 PHONE_NOT_VERIFIED behind APP_AUTH_REQUIRE_VERIFIED_PHONE (B516, AUTH-45)")
class VerifiedPhoneGateTest {

    private static final UUID USER_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String PAST_DEADLINE = "2026-11-01";
    private static final String FUTURE_DEADLINE = "2026-12-31";

    @Mock private UserRepository userRepository;

    private final AuthConsentProperties properties = new AuthConsentProperties();
    private VerifiedPhoneGate gate;

    @BeforeEach
    void setUp() {
        properties.setRequireVerifiedPhone(true);
        properties.setPhoneProviderDeadline(PAST_DEADLINE);
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
        gate = new VerifiedPhoneGate(userRepository, new PhoneRequirementPolicy(properties, clock), properties);
    }

    static Authentication token(Role role) {
        var token = new UsernamePasswordAuthenticationToken("principal", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        token.setDetails(USER_ID);
        return token;
    }

    private void storedAccount(Role role, boolean verified) {
        when(userRepository.findPhoneGateRow(USER_ID)).thenReturn(Optional.of(new PhoneGateRow(role, verified)));
    }

    @Test
    @DisplayName("with the flag off the gate is inactive and reads nothing")
    void should_readNothing_when_flagOff() {
        properties.setRequireVerifiedPhone(false);

        gate.check(token(Role.CLIENT), true, true, "/api/v1/bookings");

        assertThat(gate.isActive()).isFalse();
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("the gate opens no transaction of its own (no pooled connection per request)")
    void should_declareNoTransaction_when_inspected() throws NoSuchMethodException {
        var check = VerifiedPhoneGate.class.getMethod(
                "check", Authentication.class, boolean.class, boolean.class, String.class);

        assertThat(check.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(VerifiedPhoneGate.class.isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("an unverified client creating a booking gets 409 PHONE_NOT_VERIFIED")
    void should_reject409_when_unverifiedClientCreatesBooking() {
        storedAccount(Role.CLIENT, false);

        assertThatThrownBy(() -> gate.check(token(Role.CLIENT), true, true, "/api/v1/bookings"))
                .isInstanceOf(IdentityException.class)
                .hasMessage(IdentityException.Code.PHONE_NOT_VERIFIED.clientMessage());
    }

    @Test
    @DisplayName("a client's other writes are never gated and read nothing")
    void should_readNothing_when_clientWritesSomethingElse() {
        assertThatCode(() -> gate.check(token(Role.CLIENT), false, true, "/api/v1/favorites/x"))
                .doesNotThrowAnyException();

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("an unverified provider writing past the deadline gets 409 PHONE_NOT_VERIFIED")
    void should_reject409_when_unverifiedProviderWritesPastDeadline() {
        storedAccount(Role.INDEPENDENT_MASTER, false);

        assertThatThrownBy(() -> gate.check(token(Role.INDEPENDENT_MASTER), false, true,
                "/api/v1/independent-masters/me/services"))
                .isInstanceOf(IdentityException.class);
    }

    @Test
    @DisplayName("a provider write before the deadline reads nothing")
    void should_readNothing_when_providerWritesBeforeDeadline() {
        properties.setPhoneProviderDeadline(FUTURE_DEADLINE);

        assertThatCode(() -> gate.check(token(Role.SALON_OWNER), false, true, "/api/v1/salons/x"))
                .doesNotThrowAnyException();

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("a provider's self-service routes stay open past the deadline and read nothing")
    void should_readNothing_when_providerUsesExemptSelfServiceRoute() {
        assertThatCode(() -> gate.check(token(Role.SALON_OWNER), false, true, "/api/v1/users/me/phone/otp/send"))
                .doesNotThrowAnyException();
        assertThatCode(() -> gate.check(token(Role.SALON_OWNER), false, true, "/api/v1/auth/logout"))
                .doesNotThrowAnyException();

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("a role changed since the token was minted is honoured: the stored row decides")
    void should_passVerifiedProvider_when_storedRowVerified() {
        storedAccount(Role.SALON_OWNER, true);

        assertThatCode(() -> gate.check(token(Role.SALON_OWNER), false, true, "/api/v1/salons/x"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("reads and anonymous callers are never gated and read nothing")
    void should_pass_when_readOrAnonymous() {
        assertThatCode(() -> gate.check(token(Role.CLIENT), false, false, "/api/v1/salons/x"))
                .doesNotThrowAnyException();
        assertThatCode(() -> gate.check(null, true, true, "/api/v1/bookings")).doesNotThrowAnyException();

        verifyNoInteractions(userRepository);
    }
}
