package com.beautica.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter — unit")
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private AccessTokenDenylist accessTokenDenylist;

    @Mock
    private TokensValidAfterCache tokensValidAfterCache;

    @Mock
    private Claims mockClaims;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        // Default: no password reset has ever occurred for any user. Marked lenient()
        // because most tests below short-circuit (bad token, wrong type, jti denylisted,
        // etc.) before this cache is ever consulted, and MockitoExtension's strict-stubs
        // mode would otherwise flag the stub as unnecessary on those tests.
        lenient().when(tokensValidAfterCache.get(any(UUID.class))).thenReturn(Optional.empty());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("should_passRequestDownstreamWithNoAuthentication_when_noAuthorizationHeaderPresent")
    void should_passRequestDownstreamWithNoAuthentication_when_noAuthorizationHeaderPresent() throws Exception {
        var request  = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var chain    = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest())
                .as("chain must be called when no Authorization header is present")
                .isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("no authentication must be set when no Authorization header is present")
                .isNull();
    }

    @Test
    @DisplayName("should_passRequestWithoutAuthentication_when_refreshTokenPresentInsteadOfAccessToken")
    void should_passRequestWithoutAuthentication_when_refreshTokenPresentInsteadOfAccessToken() throws Exception {
        var request  = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer someToken");
        var response = new MockHttpServletResponse();
        var chain    = new MockFilterChain();

        when(jwtTokenProvider.parseAllClaims("someToken")).thenReturn(mockClaims);
        when(jwtTokenProvider.isAccessToken(mockClaims)).thenReturn(false);

        filter.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest())
                .as("chain must be called when a refresh token is presented instead of an access token")
                .isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("no authentication must be set when the token is not an access token")
                .isNull();
    }

    @Test
    @DisplayName("should_passRequestWithoutAuthentication_when_authorizationHeaderHasNoBearerPrefix")
    void should_passRequestWithoutAuthentication_when_authorizationHeaderHasNoBearerPrefix() throws Exception {
        var request  = new MockHttpServletRequest();
        request.addHeader("Authorization", "Token some-value");
        var response = new MockHttpServletResponse();
        var chain    = new MockFilterChain();

        filter.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest())
                .as("chain must be called when Authorization header has no Bearer prefix")
                .isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("no authentication must be set when Authorization header has no Bearer prefix")
                .isNull();
    }

    @Test
    @DisplayName("should_setAuthentication_when_validAccessTokenPresent")
    void should_setAuthentication_when_validAccessTokenPresent() throws Exception {
        var userId = UUID.randomUUID();
        var email  = "master@beautica.com";
        var role   = Role.INDEPENDENT_MASTER;

        var request  = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer validToken");
        var response = new MockHttpServletResponse();
        var chain    = new MockFilterChain();

        when(jwtTokenProvider.parseAllClaims("validToken")).thenReturn(mockClaims);
        when(jwtTokenProvider.isAccessToken(mockClaims)).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken(mockClaims)).thenReturn(userId);
        when(jwtTokenProvider.getEmailFromToken(mockClaims)).thenReturn(email);
        when(jwtTokenProvider.getRoleFromToken(mockClaims)).thenReturn(role);

        filter.doFilterInternal(request, response, chain);

        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication)
                .as("authentication must be set for a valid access token")
                .isNotNull();
        assertThat(authentication.getPrincipal())
                .as("principal must equal the email from the token")
                .isEqualTo(email);
        assertThat(authentication.getAuthorities())
                .extracting(a -> a.getAuthority())
                .as("authorities must contain the role from the token prefixed with ROLE_")
                .containsExactly("ROLE_INDEPENDENT_MASTER");
        assertThat(authentication.getDetails())
                .as("details must carry the userId UUID from the token")
                .isEqualTo(userId);

        verify(jwtTokenProvider, times(1)).parseAllClaims("validToken");
    }

    @Test
    @DisplayName("should_notAuthenticate_when_jtiIsDenylisted")
    void should_notAuthenticate_when_jtiIsDenylisted() throws Exception {
        var jti = "revoked-jti-abc123";

        var request  = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer revokedToken");
        var response = new MockHttpServletResponse();
        var chain    = new MockFilterChain();

        when(jwtTokenProvider.parseAllClaims("revokedToken")).thenReturn(mockClaims);
        when(jwtTokenProvider.isAccessToken(mockClaims)).thenReturn(true);
        when(jwtTokenProvider.getJti(mockClaims)).thenReturn(jti);
        when(accessTokenDenylist.isRevoked(jti)).thenReturn(true);

        filter.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest())
                .as("chain must be called even when the jti is denylisted (unauthenticated pass-through)")
                .isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("no authentication must be set for a denylisted jti — this is the guarantee "
                        + "the happy-path test alone cannot prove (Mockito defaults unstubbed "
                        + "isRevoked() to false)")
                .isNull();
        // The request must never progress past the denylist check to claim extraction.
        verify(jwtTokenProvider, times(0)).getUserIdFromToken(mockClaims);
    }

    @Test
    @DisplayName("should_notAuthenticate_when_tokenIssuedBeforeUsersTokensValidAfter")
    void should_notAuthenticate_when_tokenIssuedBeforeUsersTokensValidAfter() throws Exception {
        var userId = UUID.randomUUID();
        Instant issuedAt = Instant.parse("2025-01-01T00:00:00Z");
        Instant tokensValidAfter = Instant.parse("2025-06-01T00:00:00Z");

        var request  = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer preResetToken");
        var response = new MockHttpServletResponse();
        var chain    = new MockFilterChain();

        when(jwtTokenProvider.parseAllClaims("preResetToken")).thenReturn(mockClaims);
        when(jwtTokenProvider.isAccessToken(mockClaims)).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken(mockClaims)).thenReturn(userId);
        when(tokensValidAfterCache.get(userId)).thenReturn(Optional.of(tokensValidAfter));
        when(jwtTokenProvider.getIssuedAt(mockClaims)).thenReturn(issuedAt);

        filter.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest())
                .as("chain must be called even when the token predates a password reset")
                .isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("no authentication must be set when the token's iat predates tokensValidAfter")
                .isNull();
        // The request must never progress to role/email extraction once rejected.
        verify(jwtTokenProvider, times(0)).getRoleFromToken(mockClaims);
    }

    @Test
    @DisplayName("should_passRequestWithoutAuthentication_when_jwtExceptionThrownDuringValidation")
    void should_passRequestWithoutAuthentication_when_jwtExceptionThrownDuringValidation() throws Exception {
        var request  = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer badToken");
        var response = new MockHttpServletResponse();
        var chain    = new MockFilterChain();

        when(jwtTokenProvider.parseAllClaims("badToken")).thenThrow(new JwtException("invalid signature"));

        filter.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest())
                .as("chain must be called even when JWT validation throws")
                .isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("no authentication must be set when JWT validation throws")
                .isNull();
    }

    @Test
    @DisplayName("should_passRequestWithoutAuthentication_when_tokenContainsUnknownRole")
    void should_passRequestWithoutAuthentication_when_tokenContainsUnknownRole() throws Exception {
        var request  = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer tokenWithBogusRole");
        var response = new MockHttpServletResponse();
        var chain    = new MockFilterChain();

        when(jwtTokenProvider.parseAllClaims("tokenWithBogusRole")).thenReturn(mockClaims);
        when(jwtTokenProvider.isAccessToken(mockClaims)).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken(mockClaims)).thenReturn(UUID.randomUUID());
        when(jwtTokenProvider.getEmailFromToken(mockClaims)).thenReturn("attacker@example.com");
        when(jwtTokenProvider.getRoleFromToken(mockClaims))
                .thenThrow(new MalformedJwtException("Unknown role claim: SUPER_ADMIN"));

        filter.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest())
                .as("chain must be called even when role claim is unrecognised")
                .isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("no authentication must be set when role claim is unrecognised")
                .isNull();
    }

    @Test
    @DisplayName("should_passRequestWithoutAuthentication_when_getUserIdFromTokenThrowsMalformedJwtException")
    void should_passRequestWithoutAuthentication_when_getUserIdFromTokenThrowsMalformedJwtException() throws Exception {
        var request  = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer tokenWithBogusSubject");
        var response = new MockHttpServletResponse();
        var chain    = new MockFilterChain();

        when(jwtTokenProvider.parseAllClaims("tokenWithBogusSubject")).thenReturn(mockClaims);
        when(jwtTokenProvider.isAccessToken(mockClaims)).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken(mockClaims))
                .thenThrow(new MalformedJwtException("Invalid subject claim, expected UUID: not-a-uuid"));

        filter.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest())
                .as("chain must be called when getUserIdFromToken throws MalformedJwtException")
                .isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("no authentication must be set when subject claim is not a valid UUID")
                .isNull();
    }

    @Test
    @DisplayName("should_passRequestWithoutAuthentication_when_getRoleFromTokenThrowsMalformedJwtExceptionForMissingClaim")
    void should_passRequestWithoutAuthentication_when_getRoleFromTokenThrowsMalformedJwtExceptionForMissingClaim() throws Exception {
        var request  = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer tokenWithoutRole");
        var response = new MockHttpServletResponse();
        var chain    = new MockFilterChain();

        when(jwtTokenProvider.parseAllClaims("tokenWithoutRole")).thenReturn(mockClaims);
        when(jwtTokenProvider.isAccessToken(mockClaims)).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken(mockClaims)).thenReturn(UUID.randomUUID());
        when(jwtTokenProvider.getEmailFromToken(mockClaims)).thenReturn("user@example.com");
        when(jwtTokenProvider.getRoleFromToken(mockClaims))
                .thenThrow(new MalformedJwtException("Missing role claim"));

        filter.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest())
                .as("chain must be called when getRoleFromToken throws MalformedJwtException for missing role claim")
                .isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("no authentication must be set when role claim is absent from the token")
                .isNull();
    }

    @Test
    @DisplayName("should_passRequestWithoutAuthentication_when_getUserIdFromTokenThrowsMalformedJwtExceptionForNullSub")
    void should_passRequestWithoutAuthentication_when_getUserIdFromTokenThrowsMalformedJwtExceptionForNullSub() throws Exception {
        var request  = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer tokenWithNullSub");
        var response = new MockHttpServletResponse();
        var chain    = new MockFilterChain();

        when(jwtTokenProvider.parseAllClaims("tokenWithNullSub")).thenReturn(mockClaims);
        when(jwtTokenProvider.isAccessToken(mockClaims)).thenReturn(true);
        when(jwtTokenProvider.getUserIdFromToken(mockClaims))
                .thenThrow(new MalformedJwtException("Missing subject claim"));

        filter.doFilterInternal(request, response, chain);

        assertThat(chain.getRequest())
                .as("chain must be called when getUserIdFromToken throws MalformedJwtException for null sub")
                .isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("no authentication must be set when subject claim is absent from the token")
                .isNull();
    }
}
