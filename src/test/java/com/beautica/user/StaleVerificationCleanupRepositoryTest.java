package com.beautica.user;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-layer test for the scheduled stale-OTP sweep
 * ({@link UserRepository#nullifyStaleVerificationCodes}).
 *
 * <p>QA MEDIUM (stale-row cleanup): asserts the bounded UPDATE nulls ONLY rows
 * with {@code email_verified = false AND verification_code_expires_at < cutoff}
 * and leaves verified / fresh / no-code rows untouched.
 */
@DisplayName("Stale verification cleanup query — repository")
class StaleVerificationCleanupRepositoryTest extends AbstractDataJpaTest {

    private static final Instant NOW = Instant.parse("2026-05-18T00:00:00Z");
    private static final Instant CUTOFF = NOW.minus(24, ChronoUnit.HOURS);

    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("should_nullOnlyStaleUnverifiedRows_when_sweepRuns")
    void should_nullOnlyStaleUnverifiedRows_when_sweepRuns() {
        // (1) stale + unverified — MUST be cleared (expired 1h before cutoff)
        User stale = persistUser("stale@beautica.test", false,
                "a".repeat(64), CUTOFF.minus(1, ChronoUnit.HOURS));

        // (2) unverified but FRESH (expires after cutoff) — MUST be untouched
        User fresh = persistUser("fresh@beautica.test", false,
                "b".repeat(64), CUTOFF.plus(1, ChronoUnit.HOURS));

        // (3) verified, with an old code still on the row — MUST be untouched
        User verified = persistUser("verified@beautica.test", true,
                "c".repeat(64), CUTOFF.minus(5, ChronoUnit.HOURS));

        // (4) unverified, no code at all — must not error, stays null
        User noCode = persistUser("nocode@beautica.test", false, null, null);

        em.flush();

        int cleared = userRepository.nullifyStaleVerificationCodes(CUTOFF);

        // Bulk JPQL bypasses the persistence context — clear so reloads hit DB.
        em.clear();

        assertThat(cleared)
                .as("only the single stale unverified row may be swept")
                .isEqualTo(1);

        User staleAfter = userRepository.findById(stale.getId()).orElseThrow();
        assertThat(staleAfter.getVerificationCodeHash()).isNull();
        assertThat(staleAfter.getVerificationCodeExpiresAt()).isNull();
        assertThat(staleAfter.isEmailVerified()).isFalse();

        User freshAfter = userRepository.findById(fresh.getId()).orElseThrow();
        assertThat(freshAfter.getVerificationCodeHash())
                .as("a fresh unverified code must NOT be swept")
                .isEqualTo("b".repeat(64));

        User verifiedAfter = userRepository.findById(verified.getId()).orElseThrow();
        assertThat(verifiedAfter.getVerificationCodeHash())
                .as("a verified account's row must NOT be swept")
                .isEqualTo("c".repeat(64));

        User noCodeAfter = userRepository.findById(noCode.getId()).orElseThrow();
        assertThat(noCodeAfter.getVerificationCodeHash()).isNull();
    }

    private User persistUser(String email, boolean verified, String codeHash, Instant expiresAt) {
        var user = new User(email, encoder.encode("test-password"), Role.CLIENT,
                "First", "Last", null);
        if (verified) {
            user.setEmailVerified(true);
        }
        user.setVerificationCodeHash(codeHash);
        user.setVerificationCodeExpiresAt(expiresAt);
        em.persist(user);
        assertThat(user.getId()).isInstanceOf(UUID.class);
        return user;
    }
}
