package com.beautica.auth.registration;

import com.beautica.auth.LoginFailureLimiter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phone-first hardening B10 (AUTH-D27 e) — the login lockout keys an IPv6 client on its /64, through the real HTTP
 * path ({@code AuthController.login} → {@code ClientIpResolver.prefixKey} → {@link LoginFailureLimiter}). Rotating
 * the interface identifier inside one /64 must not mint fresh lockout budget; another /64 keeps its own.
 */
@DisplayName("Login lockout — counted per IPv6 /64 (AUTH-D27 e, B10)")
class LoginLockoutPerSlash64IT extends AbstractPhoneIdentityIT {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String WRONG_PASSWORD = "Wr0ng!Passw0rd#";

    /** A /64 prefix ({@code 2001:db8:<a>:<b>}) unique in this JVM and distinct from other ITs' (offset 0x5000). */
    private static String nextIpv6Prefix() {
        int n = 0x5000 + SEQUENCE.incrementAndGet();
        return "2001:db8:" + Integer.toHexString(n) + ":" + Integer.toHexString(n * 3 + 1);
    }

    private ResponseEntity<String> loginFrom(String ip, String email, String password) {
        return postFrom(ip, LOGIN_URL, Map.of("identifier", email, "password", password));
    }

    @Test
    @DisplayName("10 wrong passwords from rotating hosts of one /64 → the right password from a NEW host of that /64 is "
            + "401; from another /64 it is 200")
    void should_lockWholeSlash64_when_maxFailuresFromRotatingHostsInIt() {
        String email = newEmail();
        seedUser("CLIENT", email, true, null, null);
        String lockedPrefix = nextIpv6Prefix();
        String otherPrefix = nextIpv6Prefix();
        for (int i = 1; i <= LoginFailureLimiter.MAX_FAILURES; i++) {
            ResponseEntity<String> wrong = loginFrom(lockedPrefix + ":0:0:0:" + Integer.toHexString(i), email,
                    WRONG_PASSWORD);
            assertThat(wrong.getStatusCode()).as("wrong password %d, body=%s", i, wrong.getBody())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> sameNetworkNewHost = loginFrom(lockedPrefix + ":a1b2:c3d4:e5f6:7788", email, PASSWORD);
        ResponseEntity<String> otherNetwork = loginFrom(otherPrefix + "::1", email, PASSWORD);

        assertThat(sameNetworkNewHost.getStatusCode())
                .as("a never-seen host of the locked /64 inherits the lock, body=%s", sameNetworkNewHost.getBody())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(otherNetwork.getStatusCode())
                .as("another /64 keeps its own budget, body=%s", otherNetwork.getBody())
                .isEqualTo(HttpStatus.OK);
        assertThat(json(otherNetwork).at("/data/accessToken").asText()).isNotBlank();
    }

    @Test
    @DisplayName("9 wrong passwords from one /64 (one under the cap) → the right password from a new host of it is 200")
    void should_notLock_when_oneFailureUnderCapFromSlash64() {
        String email = newEmail();
        seedUser("CLIENT", email, true, null, null);
        String prefix = nextIpv6Prefix();
        for (int i = 1; i < LoginFailureLimiter.MAX_FAILURES; i++) {
            assertThat(loginFrom(prefix + ":0:0:0:" + Integer.toHexString(i), email, WRONG_PASSWORD).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        ResponseEntity<String> rightPassword = loginFrom(prefix + "::beef", email, PASSWORD);

        assertThat(rightPassword.getStatusCode()).as("body=%s", rightPassword.getBody()).isEqualTo(HttpStatus.OK);
    }
}
