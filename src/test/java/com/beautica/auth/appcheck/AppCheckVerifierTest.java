package com.beautica.auth.appcheck;

import com.beautica.config.appcheck.AppCheckMode;
import com.beautica.config.appcheck.AppCheckProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AppCheckVerifier — local verification of Firebase App Check tokens (B514, AUTH-54)")
class AppCheckVerifierTest {

    private static final Instant NOW = Instant.parse("2026-11-17T10:00:00Z");

    @Mock
    private AppCheckJwks jwks;

    private AppCheckVerifier verifier;

    @BeforeEach
    void setUp() {
        AppCheckProperties properties = new AppCheckProperties();
        properties.setMode(AppCheckMode.ENFORCE);
        properties.setProjectNumber(AppCheckTestTokens.PROJECT_NUMBER);
        properties.setAppIds(List.of(AppCheckTestTokens.APP_ID));
        lenient().when(jwks.keyFor(AppCheckTestTokens.KID)).thenReturn(Optional.of(AppCheckTestTokens.publicKey()));
        verifier = new AppCheckVerifier(jwks, properties, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("should_returnValid_when_tokenPassesEveryCheck")
    void should_returnValid_when_tokenPassesEveryCheck() {
        String token = AppCheckTestTokens.valid(NOW);

        AppCheckVerifier.Result result = verifier.verify(token);

        assertThat(result).isEqualTo(AppCheckVerifier.Result.VALID);
    }

    @Test
    @DisplayName("should_returnMissing_when_headerAbsentOrBlank")
    void should_returnMissing_when_headerAbsentOrBlank() {
        AppCheckVerifier.Result absent = verifier.verify(null);
        AppCheckVerifier.Result blank = verifier.verify("  ");

        assertThat(absent).isEqualTo(AppCheckVerifier.Result.MISSING);
        assertThat(blank).isEqualTo(AppCheckVerifier.Result.MISSING);
    }

    @Test
    @DisplayName("should_returnInvalid_when_signatureByForeignKey")
    void should_returnInvalid_when_signatureByForeignKey() {
        String token = AppCheckTestTokens.signedByForeignKey(NOW);

        AppCheckVerifier.Result result = verifier.verify(token);

        assertThat(result).isEqualTo(AppCheckVerifier.Result.INVALID);
    }

    @Test
    @DisplayName("should_returnInvalid_when_tokenExpired")
    void should_returnInvalid_when_tokenExpired() {
        String token = AppCheckTestTokens.token(NOW, b -> b.expiration(Date.from(NOW.minusSeconds(1))));

        AppCheckVerifier.Result result = verifier.verify(token);

        assertThat(result).isEqualTo(AppCheckVerifier.Result.INVALID);
    }

    @Test
    @DisplayName("should_returnInvalid_when_audienceIsAnotherProject")
    void should_returnInvalid_when_audienceIsAnotherProject() {
        String token = AppCheckTestTokens.token(NOW, b -> b.audience().single("projects/999"));

        AppCheckVerifier.Result result = verifier.verify(token);

        assertThat(result).isEqualTo(AppCheckVerifier.Result.INVALID);
    }

    @Test
    @DisplayName("should_returnInvalid_when_issuerIsAnotherProject")
    void should_returnInvalid_when_issuerIsAnotherProject() {
        String token = AppCheckTestTokens.token(NOW,
                b -> b.issuer("https://firebaseappcheck.googleapis.com/999"));

        AppCheckVerifier.Result result = verifier.verify(token);

        assertThat(result).isEqualTo(AppCheckVerifier.Result.INVALID);
    }

    @Test
    @DisplayName("should_returnInvalid_when_subjectNotAnAllowedAppId (mutation guard for the sub allow-list)")
    void should_returnInvalid_when_subjectNotAnAllowedAppId() {
        String token = AppCheckTestTokens.token(NOW, b -> b.subject(AppCheckTestTokens.OTHER_APP_ID));

        AppCheckVerifier.Result result = verifier.verify(token);

        assertThat(result).isEqualTo(AppCheckVerifier.Result.INVALID);
    }

    @Test
    @DisplayName("should_returnInvalid_when_tokenIsHs256 (algorithm pinned to RS256)")
    void should_returnInvalid_when_tokenIsHs256() {
        String token = Jwts.builder()
                .header().keyId(AppCheckTestTokens.KID).and()
                .issuer("https://firebaseappcheck.googleapis.com/" + AppCheckTestTokens.PROJECT_NUMBER)
                .audience().add("projects/" + AppCheckTestTokens.PROJECT_NUMBER).and()
                .subject(AppCheckTestTokens.APP_ID)
                .expiration(Date.from(NOW.plusSeconds(600)))
                .signWith(Keys.hmacShaKeyFor(new byte[32]), Jwts.SIG.HS256)
                .compact();

        AppCheckVerifier.Result result = verifier.verify(token);

        assertThat(result).isEqualTo(AppCheckVerifier.Result.INVALID);
    }

    @Test
    @DisplayName("should_returnInvalid_when_kidUnknown")
    void should_returnInvalid_when_kidUnknown() {
        when(jwks.keyFor("other-kid")).thenReturn(Optional.empty());
        String token = AppCheckTestTokens.token(NOW, b -> b.header().keyId("other-kid").and());

        AppCheckVerifier.Result result = verifier.verify(token);

        assertThat(result).isEqualTo(AppCheckVerifier.Result.INVALID);
    }

    @Test
    @DisplayName("should_returnUnavailable_when_keysCannotBeFetched")
    void should_returnUnavailable_when_keysCannotBeFetched() {
        when(jwks.keyFor(anyString())).thenThrow(new AppCheckKeysUnavailableException("down"));

        AppCheckVerifier.Result result = verifier.verify(AppCheckTestTokens.valid(NOW));

        assertThat(result).isEqualTo(AppCheckVerifier.Result.UNAVAILABLE);
    }

    @Test
    @DisplayName("should_returnInvalid_when_tokenIsGarbage")
    void should_returnInvalid_when_tokenIsGarbage() {
        AppCheckVerifier.Result result = verifier.verify("not-a-jwt");

        assertThat(result).isEqualTo(AppCheckVerifier.Result.INVALID);
    }
}
