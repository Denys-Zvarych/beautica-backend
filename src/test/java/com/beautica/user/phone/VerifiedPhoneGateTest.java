package com.beautica.user.phone;

import com.beautica.auth.Role;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.common.exception.IdentityException;
import com.beautica.config.AuthConsentProperties;
import com.beautica.user.PhoneGateRow;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("VerifiedPhoneGate — 409 PHONE_NOT_VERIFIED behind APP_AUTH_REQUIRE_VERIFIED_PHONE (B516, AUTH-45)")
class VerifiedPhoneGateTest {

    private static final String POST = "POST";
    private static final String PATCH = "PATCH";
    private static final String GET = "GET";

    @Mock private UserRepository userRepository;

    /** A fresh id per test: no state (or cache entry) can leak between tests or into other test classes. */
    private final UUID userId = UUID.randomUUID();
    private final AuthConsentProperties properties = new AuthConsentProperties();
    private final CacheManager cacheManager = new ConcurrentMapCacheManager(UserProfileCacheEvictor.VERIFIED_PHONE_CACHE);
    private VerifiedPhoneGate gate;

    @BeforeEach
    void setUp() {
        properties.setRequireVerifiedPhone(true);
        Clock clock = Clock.fixed(Instant.parse("2026-11-20T10:00:00Z"), ZoneOffset.UTC);
        gate = new VerifiedPhoneGate(userRepository, new PhoneRequirementPolicy(properties, clock), properties,
                cacheManager);
    }

    static Authentication token(Role role, UUID userId) {
        var token = new UsernamePasswordAuthenticationToken("principal", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        token.setDetails(userId);
        return token;
    }

    private Authentication token(Role role) {
        return token(role, userId);
    }

    private void storedAccount(Role role, boolean verified) {
        when(userRepository.findPhoneGateRow(userId)).thenReturn(Optional.of(new PhoneGateRow(role, verified)));
    }

    @Test
    @DisplayName("with the flag off the gate is inactive and reads nothing")
    void should_readNothing_when_flagOff() {
        properties.setRequireVerifiedPhone(false);

        gate.check(token(Role.CLIENT), true, POST, "/api/v1/bookings");

        assertThat(gate.isActive()).isFalse();
        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("the gate opens no transaction of its own (no pooled connection per request)")
    void should_declareNoTransaction_when_inspected() throws NoSuchMethodException {
        var check = VerifiedPhoneGate.class.getMethod(
                "check", Authentication.class, boolean.class, String.class, String.class);

        assertThat(check.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(VerifiedPhoneGate.class.isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("an unverified client creating a booking gets 409 PHONE_NOT_VERIFIED")
    void should_reject409_when_unverifiedClientCreatesBooking() {
        storedAccount(Role.CLIENT, false);

        assertThatThrownBy(() -> gate.check(token(Role.CLIENT), true, POST, "/api/v1/bookings"))
                .isInstanceOf(IdentityException.class)
                .hasMessage(IdentityException.Code.PHONE_NOT_VERIFIED.clientMessage());
    }

    @Test
    @DisplayName("a client's other writes are never gated and read nothing")
    void should_readNothing_when_clientWritesSomethingElse() {
        assertThatCode(() -> gate.check(token(Role.CLIENT), false, POST, "/api/v1/favorites/x"))
                .doesNotThrowAnyException();

        verifyNoInteractions(userRepository);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Role.class, names = "CLIENT", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every unverified provider role gets 409 PHONE_NOT_VERIFIED on a write immediately (no deadline, AUTH-D12)")
    void should_reject409_when_unverifiedProviderWrites(Role provider) {
        storedAccount(provider, false);

        assertThatThrownBy(() -> gate.check(token(provider), false, POST, "/api/v1/salons/x"))
                .as("%s unverified write", provider)
                .isInstanceOf(IdentityException.class)
                .hasMessage(IdentityException.Code.PHONE_NOT_VERIFIED.clientMessage());
        verify(userRepository).findPhoneGateRow(userId);
    }

    @Test
    @DisplayName("an unverified provider creating a booking is gated as a provider write (409)")
    void should_reject409_when_unverifiedProviderCreatesBooking() {
        storedAccount(Role.SALON_OWNER, false);

        assertThatThrownBy(() -> gate.check(token(Role.SALON_OWNER), true, POST, "/api/v1/bookings"))
                .isInstanceOf(IdentityException.class)
                .hasMessage(IdentityException.Code.PHONE_NOT_VERIFIED.clientMessage());
    }

    @Test
    @DisplayName("a provider token whose stored account is now an unverified CLIENT is not gated on a plain write")
    void should_pass_when_providerTokenButStoredRowIsClient() {
        storedAccount(Role.CLIENT, false);

        assertThatCode(() -> gate.check(token(Role.SALON_OWNER), false, POST, "/api/v1/salons/x"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a provider whose account row no longer exists is not gated (the auth layer owns that case)")
    void should_pass_when_storedRowMissing() {
        when(userRepository.findPhoneGateRow(userId)).thenReturn(Optional.empty());

        assertThatCode(() -> gate.check(token(Role.SALON_OWNER), false, POST, "/api/v1/salons/x"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("with the flag off a provider write is never gated and reads nothing")
    void should_readNothing_when_flagOffAndProviderWrites() {
        properties.setRequireVerifiedPhone(false);

        assertThatCode(() -> gate.check(token(Role.SALON_OWNER), false, POST, "/api/v1/salons/x"))
                .doesNotThrowAnyException();

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("a provider read is never gated and reads nothing")
    void should_readNothing_when_providerReads() {
        assertThatCode(() -> gate.check(token(Role.SALON_OWNER), false, GET, "/api/v1/salons/x"))
                .doesNotThrowAnyException();

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("a provider whose stored phone is verified passes a write (the stored row decides)")
    void should_passVerifiedProvider_when_storedRowVerified() {
        storedAccount(Role.SALON_OWNER, true);

        assertThatCode(() -> gate.check(token(Role.SALON_OWNER), false, POST, "/api/v1/salons/x"))
                .doesNotThrowAnyException();
        verify(userRepository).findPhoneGateRow(userId);
    }

    @Test
    @DisplayName("a client read is never gated and reads nothing")
    void should_readNothing_when_clientReads() {
        assertThatCode(() -> gate.check(token(Role.CLIENT), false, GET, "/api/v1/salons/x"))
                .doesNotThrowAnyException();

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("an anonymous booking create is never gated and reads nothing")
    void should_readNothing_when_anonymousCreatesBooking() {
        assertThatCode(() -> gate.check(null, true, POST, "/api/v1/bookings")).doesNotThrowAnyException();

        verifyNoInteractions(userRepository);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "POST, /api/v1/auth/invite",
            "PATCH, /api/v1/users/me",
            "POST, /api/v1/users/me/email/request-code",
            "POST, /api/v1/users/me/email/verify",
            "POST, /api/v1/users/me/change-password/request-otp",
            "POST, /api/v1/users/meX",
            "POST, /api/v1/users/me/phone/otp/sendX",
            "POST, /api/v1/support/contact",
            "PUT, /api/v1/devices/token"
    })
    @DisplayName("a write outside the exact allow-list is gated for an unverified provider (409)")
    void should_reject409_when_unverifiedProviderWritesNonExemptRoute(String method, String path) {
        storedAccount(Role.SALON_OWNER, false);

        assertThatThrownBy(() -> gate.check(token(Role.SALON_OWNER), false, method, path))
                .as("%s %s", method, path)
                .isInstanceOf(IdentityException.class)
                .hasMessage(IdentityException.Code.PHONE_NOT_VERIFIED.clientMessage());
    }

    @Test
    @DisplayName("/api/v1/users/meX is not exempt: the users/me exemption is an exact match, not a prefix")
    void should_notExempt_when_pathOnlyStartsWithUsersMe() {
        storedAccount(Role.SALON_OWNER, false);

        assertThatThrownBy(() -> gate.check(token(Role.SALON_OWNER), false, "DELETE", "/api/v1/users/meX"))
                .isInstanceOf(IdentityException.class);
    }

    @Test
    @DisplayName("the provider allow-list is exactly the AUTH-45 set: adding or dropping an entry fails here")
    void should_matchPinnedSet_when_allowListInspected() {
        assertThat(VerifiedPhoneGate.PROVIDER_EXEMPT_WRITES).containsExactlyInAnyOrder(
                "POST /api/v1/auth/register",
                "POST /api/v1/auth/register/independent-master",
                "POST /api/v1/auth/register/phone/otp/send",
                "POST /api/v1/auth/register/phone/otp/verify",
                "POST /api/v1/auth/verify-email",
                "POST /api/v1/auth/resend-verification",
                "POST /api/v1/auth/login",
                "POST /api/v1/auth/refresh",
                "POST /api/v1/auth/logout",
                "POST /api/v1/auth/forgot-password",
                "POST /api/v1/auth/verify-password-reset-otp",
                "POST /api/v1/auth/reset-password",
                "POST /api/v1/auth/invite/accept",
                "POST /api/v1/users/me/phone/otp/send",
                "POST /api/v1/users/me/phone/otp/verify",
                "DELETE /api/v1/users/me",
                "POST /api/v1/devices/token",
                "DELETE /api/v1/devices/token");
    }

    static Stream<Arguments> providerExemptWrites() {
        return VerifiedPhoneGate.PROVIDER_EXEMPT_WRITES.stream()
                .map(entry -> entry.split(" ", 2))
                .map(parts -> Arguments.of(parts[0], parts[1]));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("providerExemptWrites")
    @DisplayName("every allow-listed write stays open for an unverified provider and reads nothing")
    void should_readNothing_when_allowListedWrite(String method, String path) {
        assertThatCode(() -> gate.check(token(Role.SALON_OWNER), false, method, path)).doesNotThrowAnyException();

        verifyNoInteractions(userRepository);
    }

    @Test
    @DisplayName("a verified provider's second write is served from the verified-phone cache (no row read)")
    void should_readRowOnce_when_verifiedProviderWritesTwice() {
        storedAccount(Role.SALON_OWNER, true);

        gate.check(token(Role.SALON_OWNER), false, PATCH, "/api/v1/salons/x");
        gate.check(token(Role.SALON_OWNER), false, PATCH, "/api/v1/salons/x");

        verify(userRepository, times(1)).findPhoneGateRow(userId);
    }

    @Test
    @DisplayName("an unverified answer is never cached: each write re-reads the row")
    void should_readRowEachTime_when_providerUnverified() {
        storedAccount(Role.SALON_OWNER, false);

        assertThatThrownBy(() -> gate.check(token(Role.SALON_OWNER), false, PATCH, "/api/v1/salons/x"))
                .isInstanceOf(IdentityException.class);
        assertThatThrownBy(() -> gate.check(token(Role.SALON_OWNER), false, PATCH, "/api/v1/salons/x"))
                .isInstanceOf(IdentityException.class);

        verify(userRepository, times(2)).findPhoneGateRow(userId);
    }

    @Test
    @DisplayName("UserProfileCacheEvictor drops only that user's verified-phone entry: another user's stays cached")
    void should_rereadOnlyEvictedUser_when_profileEvictorRan() {
        UUID otherUserId = UUID.randomUUID();
        storedAccount(Role.SALON_OWNER, true);
        when(userRepository.findPhoneGateRow(otherUserId))
                .thenReturn(Optional.of(new PhoneGateRow(Role.SALON_OWNER, true)));
        gate.check(token(Role.SALON_OWNER), false, PATCH, "/api/v1/salons/x");
        gate.check(token(Role.SALON_OWNER, otherUserId), false, PATCH, "/api/v1/salons/x");

        new UserProfileCacheEvictor(cacheManager).evictAfterCommit(userId);
        gate.check(token(Role.SALON_OWNER), false, PATCH, "/api/v1/salons/x");
        gate.check(token(Role.SALON_OWNER, otherUserId), false, PATCH, "/api/v1/salons/x");

        verify(userRepository, times(2)).findPhoneGateRow(userId);
        verify(userRepository, times(1)).findPhoneGateRow(otherUserId);
    }
}
