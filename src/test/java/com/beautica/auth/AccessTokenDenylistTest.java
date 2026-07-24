package com.beautica.auth;

import com.beautica.config.JwtConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Dedicated unit tests for {@link AccessTokenDenylist} — the in-memory Caffeine cache
 * that backs the revoked-token-rejected guarantee. Previously this class was only
 * exercised indirectly: through {@code JwtAuthenticationFilterTest} (which mocks the
 * denylist entirely) and through {@code AuthControllerIT} (which exercises a real
 * instance end-to-end, but only along one happy path). Neither proves the class's own
 * null-safety or membership-set contract in isolation.
 */
@DisplayName("AccessTokenDenylist — unit")
class AccessTokenDenylistTest {

    private static final Logger log = LoggerFactory.getLogger(AccessTokenDenylistTest.class);

    private AccessTokenDenylist denylist;

    @BeforeEach
    void setUp() {
        // A real JwtConfig (not mocked) sized with a realistic 1-hour access-token TTL —
        // AccessTokenDenylist derives its Caffeine expireAfterWrite directly from this.
        JwtConfig jwtConfig = new JwtConfig("a".repeat(32), 3_600_000L, 2_592_000_000L);
        denylist = new AccessTokenDenylist(jwtConfig);
    }

    @Test
    @DisplayName("isRevoked returns false for a jti that was never revoked")
    void should_returnFalse_when_jtiNeverRevoked() {
        log.debug("Act: isRevoked on a fresh jti with no prior revoke() call");

        assertThat(denylist.isRevoked("never-revoked-jti"))
                .as("a jti that was never denylisted must not be reported as revoked")
                .isFalse();
    }

    @Test
    @DisplayName("isRevoked returns true after revoke is called for the same jti")
    void should_returnTrue_when_jtiWasRevoked() {
        String jti = "revoked-jti-xyz";
        log.debug("Arrange: revoke jti={}", jti);

        denylist.revoke(jti);

        assertThat(denylist.isRevoked(jti))
                .as("a jti passed to revoke() must be reported as revoked on the very next check")
                .isTrue();
    }

    @Test
    @DisplayName("revoke and isRevoked are keyed independently per jti (no cross-contamination)")
    void should_notAffectOtherJtis_when_oneJtiRevoked() {
        log.debug("Arrange: revoke only jti-a, leave jti-b untouched");

        denylist.revoke("jti-a");

        assertThat(denylist.isRevoked("jti-a")).isTrue();
        assertThat(denylist.isRevoked("jti-b"))
                .as("revoking one jti must never mark an unrelated jti as revoked")
                .isFalse();
    }

    @Test
    @DisplayName("revoke(null) is a no-op — does not throw and does not revoke a literal null key")
    void should_doNothing_when_revokingNullJti() {
        log.debug("Act: revoke(null) must not throw");

        denylist.revoke(null);

        assertThat(denylist.isRevoked(null))
                .as("isRevoked(null) must always be false, even after revoke(null)")
                .isFalse();
    }

    @Test
    @DisplayName("isRevoked(null) always returns false, independent of any other revoked jti")
    void should_returnFalse_when_checkingNullJtiAfterUnrelatedRevocation() {
        log.debug("Arrange: revoke an unrelated real jti first");
        denylist.revoke("some-other-real-jti");

        assertThat(denylist.isRevoked(null))
                .as("a null jti (e.g. a token minted without one) must never be treated as revoked")
                .isFalse();
    }
}
