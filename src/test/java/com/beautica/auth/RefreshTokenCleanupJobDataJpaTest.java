package com.beautica.auth;

import com.beautica.AbstractDataJpaTest;
import com.beautica.config.RefreshTokenPolicyConfig;
import com.beautica.user.RefreshToken;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-slice test for {@link RefreshTokenCleanupJob} against a real PostgreSQL
 * container — pins the cutoff-boundary contract of
 * {@code RefreshTokenRepository#deleteAllByExpiresAtBefore(cutoff)} end-to-end (injected
 * {@link Clock} -> {@code now - cleanupRetention} -> {@code expires_at < cutoff}). Mirrors
 * {@code PasswordResetTokenCleanupJobDataJpaTest}.
 *
 * <p>Lives in {@code com.beautica.auth} (same package as the job) because {@code sweep()} is
 * the package-private test seam; extends {@link AbstractDataJpaTest}, which owns the shared
 * singleton {@code PostgreSQLContainer}.
 *
 * <p>Boundary cases seeded (single sweep):
 * <ul>
 *   <li><b>well-past-retention</b> — {@code expires_at} far older than the cutoff -> DELETED.</li>
 *   <li><b>just-past-cutoff</b> — {@code expires_at = cutoff - 1ms} -> DELETED.</li>
 *   <li><b>exactly-at-cutoff</b> — {@code expires_at = cutoff} -> SURVIVES (strict {@code <}).</li>
 *   <li><b>within-retention</b> — expired but cutoff has not yet reached it -> SURVIVES.</li>
 *   <li><b>live / not-yet-expired</b> — {@code expires_at} in the future -> SURVIVES.</li>
 * </ul>
 *
 * <p>A revoked-but-not-yet-expired row (the state a reuse-detection family revocation leaves
 * behind) is included too, proving the sweep never deletes on {@code is_revoked} alone — only
 * on the expiry cutoff, per the "do not delete unexpired rows" contract.
 */
@DisplayName("RefreshTokenCleanupJob — @DataJpaTest cutoff boundary")
class RefreshTokenCleanupJobDataJpaTest extends AbstractDataJpaTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-06-17T03:57:00Z");
    private static final Duration RETENTION = Duration.ofDays(7);
    /** Rows with {@code expires_at < CUTOFF} are deleted; {@code == CUTOFF} survives. */
    private static final Instant CUTOFF = FIXED_NOW.minus(RETENTION);

    @Autowired
    private RefreshTokenRepository repo;

    @Autowired
    private TestEntityManager em;

    private RefreshTokenCleanupJob job;
    private UUID userId;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        RefreshTokenPolicyConfig policy = new RefreshTokenPolicyConfig(RETENTION);
        job = new RefreshTokenCleanupJob(repo, policy, clock);

        // user_id has a real FK (V1: REFERENCES users(id) ON DELETE CASCADE) — persist an owner.
        User owner = new User(
                "refresh-cleanup-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.CLIENT,
                "Refresh",
                "Owner",
                "+380501234567"
        );
        em.persist(owner);
        em.flush();
        userId = owner.getId();
    }

    @Test
    @DisplayName("sweep deletes only rows expired before cutoff (now - retention); boundary, live, and revoked-but-live rows survive")
    void should_deleteOnlyRowsPastRetention_when_sweepRuns() {
        UUID wellPast = persistTokenExpiring(CUTOFF.minus(Duration.ofDays(30)), false);
        UUID justPast = persistTokenExpiring(CUTOFF.minus(Duration.ofMillis(1)), false);
        UUID exactlyAtCutoff = persistTokenExpiring(CUTOFF, false);
        UUID withinRetention = persistTokenExpiring(FIXED_NOW.minus(Duration.ofDays(1)), false);
        UUID live = persistTokenExpiring(FIXED_NOW.plus(Duration.ofHours(1)), false);
        // Revoked (e.g. by reuse-detection family revocation) but not yet expired — must survive.
        // The sweep is expiry-driven only; revoke-don't-delete on the rotation path is deliberate.
        UUID revokedButLive = persistTokenExpiring(FIXED_NOW.plus(Duration.ofDays(10)), true);
        em.clear();

        int deleted = job.sweep();
        em.clear();

        assertThat(deleted)
                .as("only the two rows with expires_at strictly before cutoff are removed")
                .isEqualTo(2);

        assertThat(em.find(RefreshToken.class, wellPast))
                .as("well-past-retention row must be deleted")
                .isNull();
        assertThat(em.find(RefreshToken.class, justPast))
                .as("expires_at = cutoff - 1ms must be deleted (delete side is inclusive)")
                .isNull();
        assertThat(em.find(RefreshToken.class, exactlyAtCutoff))
                .as("expires_at = cutoff must survive (predicate is strict <, not <=)")
                .isNotNull();
        assertThat(em.find(RefreshToken.class, withinRetention))
                .as("expired-but-within-retention row must survive")
                .isNotNull();
        assertThat(em.find(RefreshToken.class, live))
                .as("not-yet-expired (live) row must survive")
                .isNotNull();
        assertThat(em.find(RefreshToken.class, revokedButLive))
                .as("revoked-but-not-yet-expired row must survive — sweep never deletes on is_revoked alone")
                .isNotNull();
    }

    @Test
    @DisplayName("sweep is a no-op (returns 0) when every row is within retention or still live")
    void should_deleteNothing_when_noRowsPastRetention() {
        persistTokenExpiring(CUTOFF, false);
        persistTokenExpiring(FIXED_NOW.minus(Duration.ofDays(2)), false);
        persistTokenExpiring(FIXED_NOW.plus(Duration.ofDays(1)), false);
        em.clear();

        int deleted = job.sweep();

        assertThat(deleted)
                .as("no row has expires_at strictly before cutoff")
                .isZero();
        assertThat(repo.count())
                .as("all three rows remain")
                .isEqualTo(3);
    }

    private UUID persistTokenExpiring(Instant expiresAt, boolean revoked) {
        RefreshToken token = RefreshToken.startNewFamily(
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
                userId,
                expiresAt
        );
        if (revoked) {
            token.revoke();
        }
        em.persist(token);
        em.flush();
        return token.getId();
    }
}
