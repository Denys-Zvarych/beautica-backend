package com.beautica.auth;

import com.beautica.common.security.BearerTokenExtractor;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenDenylist accessTokenDenylist;
    private final TokensValidAfterCache tokensValidAfterCache;

    public JwtAuthenticationFilter(
            JwtTokenProvider jwtTokenProvider,
            AccessTokenDenylist accessTokenDenylist,
            TokensValidAfterCache tokensValidAfterCache) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.accessTokenDenylist = accessTokenDenylist;
        this.tokensValidAfterCache = tokensValidAfterCache;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String token = extractToken(request);

        if (token != null) {
            try {
                Claims claims = jwtTokenProvider.parseAllClaims(token);

                if (!jwtTokenProvider.isAccessToken(claims)) {
                    filterChain.doFilter(request, response);
                    return;
                }

                String jti = jwtTokenProvider.getJti(claims);
                if (accessTokenDenylist.isRevoked(jti)) {
                    log.debug("JWT jti is denylisted — skipping authentication");
                    filterChain.doFilter(request, response);
                    return;
                }

                UUID userId = jwtTokenProvider.getUserIdFromToken(claims);

                Optional<Instant> tokensValidAfter = tokensValidAfterCache.get(userId);
                if (tokensValidAfter.isPresent()) {
                    Instant issuedAt = jwtTokenProvider.getIssuedAt(claims);
                    if (issuedAt == null || issuedAt.isBefore(tokensValidAfter.get())) {
                        log.debug("JWT issued before the user's tokensValidAfter — skipping "
                                + "authentication (password reset since token was issued)");
                        filterChain.doFilter(request, response);
                        return;
                    }
                }

                String email = jwtTokenProvider.getEmailFromToken(claims);
                if (email == null) {
                    log.debug("JWT is missing required email claim — skipping authentication");
                    filterChain.doFilter(request, response);
                    return;
                }
                Role role = jwtTokenProvider.getRoleFromToken(claims);

                var authority = new SimpleGrantedAuthority(role.springRole);
                var authentication = new UsernamePasswordAuthenticationToken(
                        email,
                        null,
                        List.of(authority)
                );
                authentication.setDetails(userId);

                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException ex) {
                log.debug("JWT validation failed: {}", ex.getClass().getSimpleName());
            }
        }

        filterChain.doFilter(request, response);
    }

    private String extractToken(HttpServletRequest request) {
        return BearerTokenExtractor.extract(request);
    }
}
