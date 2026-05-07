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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("JwtAuthenticationFilter — unit")
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Mock
    private Claims mockClaims;

    @InjectMocks
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
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
