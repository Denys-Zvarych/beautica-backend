package com.beautica.auth;

import com.beautica.config.JwtConfig;
import com.beautica.user.RefreshToken;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for {@link AuthResponseBuilder} — the single place that mints refresh tokens
 * for every "fresh session" path (login, register/verify, invite-accept) AND, via the
 * family-aware overload, for refresh-token rotation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthResponseBuilder — unit")
class AuthResponseBuilderTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private TokenGenerator tokenGenerator;

    private static final Instant FIXED_NOW = Instant.parse("2025-06-01T12:00:00Z");
    private static final long THIRTY_DAYS_MS = Duration.ofDays(30).toMillis();

    private Clock clock;
    private JwtConfig jwtConfig;
    private AuthResponseBuilder authResponseBuilder;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        // Mirrors the production value shipped in application.yml after the 7d -> 30d change.
        jwtConfig = new JwtConfig("a".repeat(32), 3_600_000L, THIRTY_DAYS_MS);
        authResponseBuilder = new AuthResponseBuilder(
                jwtTokenProvider, jwtConfig, tokenGenerator, refreshTokenRepository, clock);

        when(jwtTokenProvider.generateAccessToken(any(), any(), any())).thenReturn("access-tok");
        when(jwtTokenProvider.generateRefreshToken(any())).thenReturn("raw-refresh-tok");
        when(tokenGenerator.hash("raw-refresh-tok")).thenReturn("hashed-refresh-tok");
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @DisplayName("buildAuthResponse(user) — the newly issued refresh token's expiresAt lands exactly at now + configured TTL (30 days)")
    void should_setExpiresAtToConfiguredTtl_when_buildingFreshAuthResponse() {
        User user = buildActiveUser();

        authResponseBuilder.buildAuthResponse(user);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        org.mockito.Mockito.verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getExpiresAt())
                .as("expiresAt must be exactly clock.instant() + app.jwt.refresh-token-expiration (30d)")
                .isEqualTo(FIXED_NOW.plusMillis(THIRTY_DAYS_MS));
    }

    @Test
    @DisplayName("buildAuthResponse(user) — starts a brand-new family (no familyId argument)")
    void should_startNewFamily_when_noFamilyIdProvided() {
        User user = buildActiveUser();

        authResponseBuilder.buildAuthResponse(user);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        org.mockito.Mockito.verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getFamilyId()).isNotNull();
    }

    @Test
    @DisplayName("buildAuthResponse(user, familyId) — the new refresh token continues the SAME family")
    void should_continueGivenFamily_when_familyIdProvided() {
        User user = buildActiveUser();
        UUID existingFamilyId = UUID.randomUUID();

        authResponseBuilder.buildAuthResponse(user, existingFamilyId);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        org.mockito.Mockito.verify(refreshTokenRepository).save(captor.capture());
        assertThat(captor.getValue().getFamilyId())
                .as("rotated-in token must inherit the passed-in familyId, not mint its own")
                .isEqualTo(existingFamilyId);
    }

    // ── Distinct-family regression net ──────────────────────────────────────────
    //
    // Gap: nothing previously proved that two fresh sessions get DIFFERENT families.
    // The danger if they didn't: reuse detection revokes an ENTIRE family on replay, so a
    // shared/global family would let one attacker's replayed refresh token revoke an
    // unrelated user's live sessions too — a self-service denial-of-service against
    // strangers. AuthServiceTest/InviteServiceTest mock this class's buildAuthResponse(User)
    // wholesale (see e.g. AuthServiceTest:286, InviteServiceTest:396), so they can only prove
    // "the right overload was called" via strict-stub matching — never that the overload
    // actually mints a fresh UUID. This class is the one place that exercises the REAL
    // startNewFamily(...) (only jwtTokenProvider/tokenGenerator/refreshTokenRepository are
    // mocked, never AuthResponseBuilder itself), so it is the tier that can observe minting.
    //
    // login(), verifyEmail() and InviteService#acceptInvite ALL delegate to this exact
    // one-arg overload (AuthService.java:269,359; InviteService.java:290) with no
    // per-entry-point branching in between — proving it here covers all three call sites;
    // AuthServiceTest/InviteServiceTest's strict-stub verifications already pin that each
    // entry point calls THIS overload (not some other one), so the two facts compose.

    @Test
    @DisplayName("buildAuthResponse(user) — two calls for the SAME user mint DIFFERENT families")
    void should_mintDistinctFamilyIds_when_calledTwiceForSameUser() {
        User user = buildActiveUser();

        authResponseBuilder.buildAuthResponse(user);
        authResponseBuilder.buildAuthResponse(user);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        org.mockito.Mockito.verify(refreshTokenRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        UUID firstFamilyId = captor.getAllValues().get(0).getFamilyId();
        UUID secondFamilyId = captor.getAllValues().get(1).getFamilyId();

        assertThat(secondFamilyId)
                .as("two fresh logins for the same user must NOT share a family — a shared "
                        + "family would let logging out/replaying one session's token revoke the other")
                .isNotEqualTo(firstFamilyId);
    }

    @Test
    @DisplayName("buildAuthResponse(user) — two DIFFERENT users each mint their OWN family")
    void should_mintDistinctFamilyIds_when_calledForTwoDifferentUsers() {
        User userA = buildActiveUser();
        User userB = buildActiveUser();

        authResponseBuilder.buildAuthResponse(userA);
        authResponseBuilder.buildAuthResponse(userB);

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        org.mockito.Mockito.verify(refreshTokenRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        UUID familyIdA = captor.getAllValues().get(0).getFamilyId();
        UUID familyIdB = captor.getAllValues().get(1).getFamilyId();

        assertThat(familyIdB)
                .as("two different users must never share a family — a shared family would let "
                        + "one user's compromised-token replay revoke an unrelated stranger's sessions")
                .isNotEqualTo(familyIdA);
    }

    private User buildActiveUser() {
        User user = new User(
                "builder@example.com",
                new BCryptPasswordEncoder(4).encode("test-password"),
                Role.CLIENT, "Test", "User", null);
        ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
        return user;
    }
}
