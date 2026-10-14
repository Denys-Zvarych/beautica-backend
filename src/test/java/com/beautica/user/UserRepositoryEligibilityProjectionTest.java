package com.beautica.user;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import org.hibernate.Session;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-Postgres contract for the two column-only pre-read projections that gate the row lock in
 * {@code AuthService.resendVerification} and {@code PasswordResetService.requestReset}:
 * {@link UserRepository#findResendVerificationEligibleByEmail} (unverified AND active) and
 * {@link UserRepository#findPasswordResetEligibleByEmail} (verified AND active).
 *
 * <p>The service unit tests stub these queries, so a JPQL regression (dropped {@code isActive}
 * predicate, inverted {@code emailVerified}) would pass every mock-based test green. This slice
 * pins the exact boolean the database returns for every relevant account state.
 */
@DisplayName("UserRepository eligibility projections — repository (real Postgres)")
class UserRepositoryEligibilityProjectionTest extends AbstractDataJpaTest {

    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TestEntityManager em;

    /**
     * {@code (label, persisted, verified, active, expectedResend, expectedReset)}.
     * An empty Optional means "no row" — the callers treat it like {@code false}, but the query
     * itself must not invent a row for an unknown email.
     */
    static Stream<Arguments> userStates() {
        return Stream.of(
                Arguments.of("unknown email", false, false, false, Optional.empty(), Optional.empty()),
                Arguments.of("verified + active", true, true, true, Optional.of(false), Optional.of(true)),
                Arguments.of("unverified + active", true, false, true, Optional.of(true), Optional.of(false)),
                Arguments.of("unverified + inactive", true, false, false, Optional.of(false), Optional.of(false)),
                Arguments.of("verified + inactive", true, true, false, Optional.of(false), Optional.of(false)));
    }

    @ParameterizedTest(name = "{0} -> resend={4}, reset={5}")
    @MethodSource("userStates")
    @DisplayName("should return the exact eligibility from BOTH projections for every user state")
    void should_returnExpectedEligibility_when_userStateVaries(String label, boolean persisted,
                                                                boolean verified, boolean active,
                                                                Optional<Boolean> expectedResend,
                                                                Optional<Boolean> expectedReset) {
        String email = "elig." + UUID.randomUUID() + "@beautica.test";
        if (persisted) {
            persistUser(email, verified, active);
        }

        Optional<Boolean> resend = userRepository.findResendVerificationEligibleByEmail(email);
        Optional<Boolean> reset = userRepository.findPasswordResetEligibleByEmail(email);

        assertThat(resend)
                .as("resend-verification eligibility for '%s' (verified=%s, active=%s)", label, verified, active)
                .isEqualTo(expectedResend);
        assertThat(reset)
                .as("password-reset eligibility for '%s' (verified=%s, active=%s)", label, verified, active)
                .isEqualTo(expectedReset);
    }

    @Test
    @DisplayName("should match the stored (lower-case, stripped) email only — callers own the normalisation")
    void should_returnEmpty_when_emailNotNormalised() {
        String email = "norm." + UUID.randomUUID() + "@beautica.test";
        persistUser(email, false, true);
        String mixedCase = email.toUpperCase(java.util.Locale.ROOT);
        String padded = "  " + email + " ";

        assertThat(userRepository.findResendVerificationEligibleByEmail(email))
                .as("exact stored email resolves").contains(true);
        assertThat(userRepository.findResendVerificationEligibleByEmail(mixedCase))
                .as("query is an exact match; mixed case must be lower-cased by the caller").isEmpty();
        assertThat(userRepository.findResendVerificationEligibleByEmail(padded))
                .as("query is an exact match; whitespace must be stripped by the caller").isEmpty();
        assertThat(userRepository.findPasswordResetEligibleByEmail(mixedCase)).isEmpty();
        assertThat(userRepository.findPasswordResetEligibleByEmail(padded)).isEmpty();
    }

    @Test
    @DisplayName("should not load a User entity into the persistence context (scalar projection, not entity)")
    void should_notManageUserEntity_when_eligibilityProjectionRuns() {
        String email = "scalar." + UUID.randomUUID() + "@beautica.test";
        persistUser(email, false, true);
        em.clear();
        Session session = em.getEntityManager().unwrap(Session.class);

        userRepository.findResendVerificationEligibleByEmail(email);
        userRepository.findPasswordResetEligibleByEmail(email);

        // An entity left in the context would be handed back by the later findByEmailForUpdate,
        // hiding a concurrent writer's committed code (the stale-read-under-row-lock bug).
        assertThat(session.getStatistics().getEntityCount())
                .as("pre-read projections must leave the persistence context empty")
                .isZero();
    }

    private void persistUser(String email, boolean verified, boolean active) {
        var user = new User(email, ENCODER.encode("test-password"), Role.CLIENT, "First", "Last", null);
        user.setEmailVerified(verified);
        user.setActive(active);
        em.persist(user);
        em.flush();
    }
}
