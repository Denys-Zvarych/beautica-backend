package com.beautica.auth.phoneotp;

import com.beautica.auth.JwtTokenProvider;
import com.beautica.config.JwtConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Token-confusion isolation regression (Phase 13.2). A guest token is signed with the
 * <b>same</b> {@code app.jwt.secret} as regular access/refresh tokens (it reuses the key
 * by design — no new env var). The ONLY thing separating a guest token from a privileged
 * access token is the {@code type} claim ({@code GUEST} vs {@code access}). This pins the
 * contract that {@link JwtAuthenticationFilter} relies on: a real guest token minted by
 * {@link GuestTokenProvider} is NOT accepted as an access token by
 * {@link JwtTokenProvider#isAccessToken}, so it can never authenticate a request on a
 * normal protected endpoint — it stays confined to the guest-booking flow.
 *
 * <p>Both providers are real (no mocks) and share the secret, so this would catch a
 * regression where the guest minting drifts to emit {@code type=access}, or where
 * {@code isAccessToken} is loosened to accept GUEST.
 */
@DisplayName("Guest-token confusion isolation — guest token rejected as access token")
class GuestTokenConfusionTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long-xx";
    private static final String PHONE = "+380671234567";

    private final JwtConfig jwtConfig = new JwtConfig(SECRET, 900_000L, 1_209_600_000L);
    // Real (system) clock so the freshly minted guest token is not expired when
    // JwtTokenProvider parses it — its parser reads the system clock, unlike
    // GuestTokenProvider's. Expiry is asserted elsewhere; here we pin the TYPE-claim
    // isolation, which must hold regardless of expiry.
    private final Clock clock = Clock.systemUTC();

    @Test
    @DisplayName("should_notBeAccessToken_when_guestTokenParsedByJwtTokenProvider")
    void should_notBeAccessToken_when_guestTokenParsedByJwtTokenProvider() {
        // Mint a genuine guest token (same secret the access-token provider verifies with).
        String guestToken = new GuestTokenProvider(jwtConfig, clock).generate(PHONE);

        JwtTokenProvider accessProvider = new JwtTokenProvider(jwtConfig, clock);

        // The signature verifies (shared key), but the type claim is GUEST, not access —
        // so JwtAuthenticationFilter's isAccessToken gate returns false and never sets an
        // Authentication. A guest token therefore cannot reach a protected endpoint.
        assertThat(accessProvider.isAccessToken(guestToken))
                .as("a GUEST token must NOT be treated as an access token — confusion would "
                        + "let a guest-booking token authenticate normal protected endpoints")
                .isFalse();
    }
}
