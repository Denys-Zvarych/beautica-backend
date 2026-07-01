package com.beautica.auth;

import com.beautica.AbstractDataJpaTest;
import com.beautica.config.PasswordResetPolicyConfig;
import com.beautica.user.PasswordResetToken;
import com.beautica.user.PasswordResetTokenRepository;
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
 * Repository-slice test for {@link PasswordResetTokenCleanupJob} against a real PostgreSQL
 * container — pins the cutoff-boundary contract of {@code deleteByExpiresAtBefore(cutoff)}
 * end-to-end (injected {@link Clock} → {@code now - cleanupRetention} → {@code expires_at < cutoff}).
 *
 * <p>The job's package-private {@link PasswordResetTokenCleanupJob#sweep()} seam is exercised with
 * a fixed clock and a real {@link PasswordResetPolicyConfig}, driving the live JPQL DELETE so the
 * strictly-less-than boundary is verified against actual Postgres semantics — the
 * {@link StaleVerificationCleanupJobTest} Mockito unit test asserts only the captured cutoff
 * argument and cannot prove the {@code < cutoff} predicate the row scan applies.
 *
 * <p>Lives in {@code com.beautica.auth} (same package as the job) because {@code sweep()} is the
 * package-private test seam; extends {@link AbstractDataJpaTest}, which owns the shared singleton
 * {@code PostgreSQLContainer}.
 *
 * <p>Boundary cases seeded (single sweep):
 * <ul>
 *   <li><b>well-past-retention</b> — {@code expires_at} far older than the cutoff → DELETED.</li>
 *   <li><b>just-past-cutoff</b> — {@code expires_at = cutoff - 1ms} → DELETED (proves the
 *       boundary is inclusive on the delete side; 1ms is representable in Postgres
 *       {@code TIMESTAMPTZ} microsecond precision, a sub-microsecond delta would round to
 *       the cutoff and survive).</li>
 *   <li><b>exactly-at-cutoff</b> — {@code expires_at = cutoff} → SURVIVES (strict {@code <}).</li>
 *   <li><b>within-retention</b> — expired but cutoff has not yet reached it → SURVIVES.</li>
 *   <li><b>live / not-yet-expired</b> — {@code expires_at} in the future → SURVIVES.</li>
 * </ul>
 */
@DisplayName("PasswordResetTokenCleanupJob — @DataJpaTest cutoff boundary")
class PasswordResetTokenCleanupJobDataJpaTest extends AbstractDataJpaTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-06-17T03:42:00Z");
    private static final Duration RETENTION = Duration.ofDays(7);
    /** Tokens with {@code expires_at < CUTOFF} are deleted; {@code == CUTOFF} survives. */
    private static final Instant CUTOFF = FIXED_NOW.minus(RETENTION);

    @Autowired
    private PasswordResetTokenRepository repo;

    @Autowired
    private TestEntityManager em;

    private PasswordResetTokenCleanupJob job;
    private UUID userId;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        PasswordResetPolicyConfig policy = new PasswordResetPolicyConfig(RETENTION);
        job = new PasswordResetTokenCleanupJob(repo, policy, clock);

        // user_id has a real FK (V55: REFERENCES users ON DELETE CASCADE) — persist an owner.
        User owner = new User(
                "reset-cleanup-" + UUID.randomUUID() + "@test.com",
                "$2a$10$hash",
                Role.CLIENT,
                "Reset",
                "Owner",
                "+380501234567"
        );
        em.persist(owner);
        em.flush();
        userId = owner.getId();
    }

    @Test
    @DisplayName("sweep deletes only rows expired before cutoff (now - retention); boundary and live rows survive")
    void should_deleteOnlyRowsPastRetention_when_sweepRuns() {
        UUID wellPast = persistTokenExpiring(CUTOFF.minus(Duration.ofDays(30)));
        UUID justPast = persistTokenExpiring(CUTOFF.minus(Duration.ofMillis(1)));
        UUID exactlyAtCutoff = persistTokenExpiring(CUTOFF);
        UUID withinRetention = persistTokenExpiring(FIXED_NOW.minus(Duration.ofDays(1))); // expired, but cutoff not reached
        UUID live = persistTokenExpiring(FIXED_NOW.plus(Duration.ofHours(1)));            // not yet expired
        em.clear();

        int deleted = job.sweep();
        em.clear();

        assertThat(deleted)
                .as("only the two rows with expires_at strictly before cutoff are removed")
                .isEqualTo(2);

        assertThat(em.find(PasswordResetToken.class, wellPast))
                .as("well-past-retention row must be deleted")
                .isNull();
        assertThat(em.find(PasswordResetToken.class, justPast))
                .as("expires_at = cutoff - 1ms must be deleted (delete side is inclusive)")
                .isNull();
        assertThat(em.find(PasswordResetToken.class, exactlyAtCutoff))
                .as("expires_at = cutoff must survive (predicate is strict <, not <=)")
                .isNotNull();
        assertThat(em.find(PasswordResetToken.class, withinRetention))
                .as("expired-but-within-retention row must survive")
                .isNotNull();
        assertThat(em.find(PasswordResetToken.class, live))
                .as("not-yet-expired (live) row must survive")
                .isNotNull();
    }

    @Test
    @DisplayName("sweep is a no-op (returns 0) when every token is within retention or still live")
    void should_deleteNothing_when_noRowsPastRetention() {
        persistTokenExpiring(CUTOFF);                              // boundary — survives
        persistTokenExpiring(FIXED_NOW.minus(Duration.ofDays(2))); // within retention
        persistTokenExpiring(FIXED_NOW.plus(Duration.ofDays(1)));  // live
        em.clear();

        int deleted = job.sweep();

        assertThat(deleted)
                .as("no row has expires_at strictly before cutoff")
                .isZero();
        assertThat(repo.count())
                .as("all three rows remain")
                .isEqualTo(3);
    }

    private UUID persistTokenExpiring(Instant expiresAt) {
        // SHA-256-hex-shaped unique token; raw value is never persisted in production.
        PasswordResetToken token = new PasswordResetToken(
                UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""),
                userId,
                expiresAt
        );
        em.persist(token);
        em.flush();
        return token.getId();
    }
}
