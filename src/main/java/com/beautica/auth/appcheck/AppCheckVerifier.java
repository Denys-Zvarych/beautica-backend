package com.beautica.auth.appcheck;

import com.beautica.config.appcheck.AppCheckProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import org.springframework.stereotype.Component;

import java.security.Key;
import java.time.Clock;
import java.util.Date;
import java.util.Set;

/**
 * B514 (AUTH-54) — verifies a Firebase App Check token locally (no Admin SDK: {@code firebase-admin:9.3.0} has no
 * App Check API, and a local check keeps App Check independent of {@code FIREBASE_ENABLED}):
 * <ul>
 *   <li>signature RS256 only, key located by {@code kid} in Google's JWKS ({@link AppCheckJwks});</li>
 *   <li>{@code iss} = {@code https://firebaseappcheck.googleapis.com/<project-number>};</li>
 *   <li>{@code aud} contains {@code projects/<project-number>};</li>
 *   <li>{@code exp} in the future (injected {@link Clock});</li>
 *   <li>{@code sub} (the Firebase app id) is one of {@code app.auth.app-check.app-ids}.</li>
 * </ul>
 * No replay {@code consume} call (beta, one network call per send); the per-phone and per-IP limits cover replay
 * inside a token's lifetime. The parser is built once (Anti-Bug §C).
 */
@Component
public class AppCheckVerifier {

    /** App Check tokens are ~1 KB; anything far larger is rejected before parsing. */
    static final int MAX_TOKEN_LENGTH = 4096;
    private static final String RS256 = "RS256";

    /** Outcome of one verification; also the {@code result} tag of the {@code appcheck.verify} counter. */
    public enum Result {
        VALID("valid"), MISSING("missing"), INVALID("invalid"), UNAVAILABLE("unavailable");

        private final String tag;

        Result(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private final AppCheckJwks jwks;
    private final JwtParser parser;
    private final String expectedAudience;
    private final Set<String> allowedAppIds;

    public AppCheckVerifier(AppCheckJwks jwks, AppCheckProperties properties, Clock clock) {
        this.jwks = jwks;
        boolean configured = properties.getProjectNumber() != null && !properties.getProjectNumber().isBlank();
        this.expectedAudience = configured ? properties.expectedAudience() : null;
        this.allowedAppIds = Set.copyOf(properties.allowedAppIds());
        var builder = Jwts.parser()
                .keyLocator(new KeyByKid())
                .clock(() -> Date.from(clock.instant()));
        if (configured) {
            builder.requireIssuer(properties.expectedIssuer());
        }
        this.parser = builder.build();
    }

    /**
     * @param token the raw {@code X-Firebase-AppCheck} header value, possibly {@code null}
     * @return the outcome; never throws
     */
    public Result verify(String token) {
        if (token == null || token.isBlank()) {
            return Result.MISSING;
        }
        if (token.length() > MAX_TOKEN_LENGTH || expectedAudience == null) {
            return Result.INVALID;
        }
        try {
            Claims claims = parser.parseSignedClaims(token.strip()).getPayload();
            return audienceMatches(claims) && allowedAppIds.contains(claims.getSubject())
                    ? Result.VALID
                    : Result.INVALID;
        } catch (AppCheckKeysUnavailableException e) {
            return Result.UNAVAILABLE;
        } catch (JwtException | IllegalArgumentException e) {
            // Defensive: should the parser ever wrap a locator exception, the outage still reads as one.
            return e.getCause() instanceof AppCheckKeysUnavailableException ? Result.UNAVAILABLE : Result.INVALID;
        }
    }

    private boolean audienceMatches(Claims claims) {
        Set<String> audience = claims.getAudience();
        return audience != null && audience.contains(expectedAudience);
    }

    /** Locates Google's key by {@code kid}; refuses any algorithm but RS256 and any unsigned/encrypted token. */
    private final class KeyByKid extends LocatorAdapter<Key> {

        @Override
        protected Key locate(JwsHeader header) {
            if (!RS256.equals(header.getAlgorithm())) {
                throw new UnsupportedJwtException("unexpected algorithm");
            }
            return jwks.keyFor(header.getKeyId())
                    .orElseThrow(() -> new SignatureException("unknown key id"));
        }

        @Override
        protected Key doLocate(io.jsonwebtoken.Header header) {
            throw new UnsupportedJwtException("unsigned or encrypted token");
        }
    }
}
