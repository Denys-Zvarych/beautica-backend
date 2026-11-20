package com.beautica.auth.appcheck;

import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Jwks;
import io.jsonwebtoken.security.PublicJwk;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.function.UnaryOperator;

/**
 * B514 test fixture — a throwaway RSA key standing in for Google's App Check signing key, its JWKS JSON, and
 * App Check-shaped tokens (RS256, {@code kid}, {@code iss}/{@code aud}/{@code sub}/{@code exp}) signed with it.
 * Shared by {@code AppCheckVerifierTest} and the App Check ITs (one key per JVM).
 */
public final class AppCheckTestTokens {

    public static final String PROJECT_NUMBER = "123456789012";
    public static final String APP_ID = "1:123456789012:android:0123456789abcdef";
    public static final String OTHER_APP_ID = "1:123456789012:android:ffffffffffffffff";
    public static final String KID = "test-kid-1";

    private static final KeyPair GOOGLE_KEY = newRsaKeyPair();
    private static final KeyPair FOREIGN_KEY = newRsaKeyPair();

    private AppCheckTestTokens() {
    }

    /** {@code {"keys":[<the test public key>]}} — what the JWKS stub serves. */
    public static String jwksJson() {
        PublicJwk<RSAPublicKey> jwk = Jwks.builder()
                .key((RSAPublicKey) GOOGLE_KEY.getPublic())
                .id(KID)
                .algorithm("RS256")
                .publicKeyUse("sig")
                .build();
        return "{\"keys\":[" + Jwks.json(jwk) + "]}";
    }

    /** The public half of the stand-in Google key. */
    public static java.security.PublicKey publicKey() {
        return GOOGLE_KEY.getPublic();
    }

    /** A token that passes every check at {@code now}. */
    public static String valid(Instant now) {
        return token(now, UnaryOperator.identity());
    }

    /** A valid token with {@code customise} applied last (override a claim to break one check). */
    public static String token(Instant now, UnaryOperator<JwtBuilder> customise) {
        JwtBuilder builder = Jwts.builder()
                .header().keyId(KID).type("JWT").and()
                .issuer("https://firebaseappcheck.googleapis.com/" + PROJECT_NUMBER)
                .audience().add("projects/" + PROJECT_NUMBER).add("projects/beautica-cb9fb").and()
                .subject(APP_ID)
                .issuedAt(Date.from(now.minusSeconds(60)))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(GOOGLE_KEY.getPrivate(), Jwts.SIG.RS256);
        return customise.apply(builder).compact();
    }

    /** A token signed by a key the JWKS does not hold (same kid) — a forged signature. */
    public static String signedByForeignKey(Instant now) {
        return token(now, b -> b.signWith(FOREIGN_KEY.getPrivate(), Jwts.SIG.RS256));
    }

    private static KeyPair newRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
