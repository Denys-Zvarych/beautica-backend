package com.beautica.user;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-slice test for {@link PasswordResetTokenRepository} against a real PostgreSQL
 * container — pins the persistence contract of the {@code password_reset_tokens} side table
 * (V55) at the JPQL/SQL boundary, where the Mockito service unit test
 * ({@code PasswordResetServiceTest}) cannot reach: the {@code unique} constraint on
 * {@code token}, the DB-side {@code ON DELETE CASCADE} FK to {@code users}, and the live
 * JPQL of {@link PasswordResetTokenRepository#markAllUsedByUserId(UUID)} /
 * {@link PasswordResetTokenRepository#findByTokenForUpdate(String)}.
 *
 * <p>Extends {@link AbstractDataJpaTest}, which owns the shared singleton
 * {@code PostgreSQLContainer} and the {@code @DataJpaTest} slice annotations
 * (transactional rollback per test, repository beans only).
 *
 * <p>Contracts pinned here:
 * <ul>
 *   <li><b>save / findByToken</b> — round-trips a row by its (hashed) token value and reloads
 *       every business field, proving the entity↔column mapping, not just {@code isNotNull}.</li>
 *   <li><b>findByTokenForUpdate</b> — the pessimistic-write lookup returns the same row as the
 *       non-locking finder (the lock acquisition itself is covered by
 *       {@code PasswordResetConcurrencyTest}; here we assert the query selects correctly).</li>
 *   <li><b>markAllUsedByUserId</b> — flips every unused row for the user to {@code is_used = true},
 *       leaves already-used rows untouched, and never touches another user's rows.</li>
 *   <li><b>UNIQUE(token)</b> — a second row with the same token value is rejected by the DB as a
 *       {@link DataIntegrityViolationException} (Spring's translation of the PG unique violation).</li>
 *   <li><b>FK ON DELETE CASCADE</b> — deleting the owning user (DB-side) removes the token row;
 *       the FK is enforced (an unknown {@code user_id} is rejected).</li>
 * </ul>
 */
@DisplayName("PasswordResetTokenRepository — @DataJpaTest persistence contract (V55)")
class PasswordResetTokenRepositoryDataJpaTest extends AbstractDataJpaTest {

    /** SHA-256-hex-shaped 64-char value; production stores only the hash, never the raw token. */
    private static final String TOKEN_HASH =
            "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

    @Autowired
    private PasswordResetTokenRepository repo;

    @Autowired
    private TestEntityManager em;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = persistUser("reset-repo-" + UUID.randomUUID() + "@test.com");
    }

    @Test
    @DisplayName("save then findByToken round-trips the row with every business field reloaded")
    void should_roundTripByToken_when_saved() {
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS);
        PasswordResetToken saved = repo.saveAndFlush(new PasswordResetToken(TOKEN_HASH, userId, expiresAt));
        em.clear();

        Optional<PasswordResetToken> found = repo.findByToken(TOKEN_HASH);

        assertThat(found).isPresent();
        assertThat(found.get())
                .extracting(
                        PasswordResetToken::getId,
                        PasswordResetToken::getToken,
                        PasswordResetToken::getUserId,
                        PasswordResetToken::isUsed)
                .containsExactly(saved.getId(), TOKEN_HASH, userId, false);
        assertThat(found.get().getExpiresAt())
                .as("expires_at survives the TIMESTAMPTZ round-trip at microsecond precision")
                .isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("findByToken returns empty when no row matches the supplied hash")
    void should_returnEmpty_when_tokenUnknown() {
        repo.saveAndFlush(new PasswordResetToken(TOKEN_HASH, userId, future()));
        em.clear();

        assertThat(repo.findByToken("no-such-token-hash")).isEmpty();
    }

    @Test
    @DisplayName("findByTokenForUpdate (pessimistic-write) selects the same row as the non-locking finder")
    void should_returnRow_when_findByTokenForUpdate() {
        PasswordResetToken saved = repo.saveAndFlush(new PasswordResetToken(TOKEN_HASH, userId, future()));
        em.clear();

        Optional<PasswordResetToken> locked = repo.findByTokenForUpdate(TOKEN_HASH);

        assertThat(locked).isPresent();
        assertThat(locked.get().getId())
                .as("the FOR UPDATE finder must resolve the same row id as save")
                .isEqualTo(saved.getId());
        assertThat(locked.get().getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("markAllUsedByUserId flips all of the user's unused tokens to used and leaves other users untouched")
    void should_flipAllUnusedTokens_when_markAllUsedByUserId() {
        UUID firstId = repo.saveAndFlush(new PasswordResetToken(hash("u1-a"), userId, future())).getId();
        UUID secondId = repo.saveAndFlush(new PasswordResetToken(hash("u1-b"), userId, future())).getId();

        UUID otherUserId = persistUser("reset-other-" + UUID.randomUUID() + "@test.com");
        UUID otherTokenId = repo.saveAndFlush(new PasswordResetToken(hash("u2-a"), otherUserId, future())).getId();
        em.clear();

        repo.markAllUsedByUserId(userId);
        em.clear();

        assertThat(em.find(PasswordResetToken.class, firstId).isUsed())
                .as("first token of the target user must be marked used")
                .isTrue();
        assertThat(em.find(PasswordResetToken.class, secondId).isUsed())
                .as("second token of the target user must be marked used")
                .isTrue();
        assertThat(em.find(PasswordResetToken.class, otherTokenId).isUsed())
                .as("another user's token must NOT be affected by the per-user sweep")
                .isFalse();
    }

    @Test
    @DisplayName("markAllUsedByUserId only touches rows where is_used = false (already-used rows stay used, no churn)")
    void should_skipAlreadyUsedRows_when_markAllUsedByUserId() {
        PasswordResetToken alreadyUsed = new PasswordResetToken(hash("used"), userId, future());
        alreadyUsed.markUsed();
        UUID usedId = repo.saveAndFlush(alreadyUsed).getId();
        UUID freshId = repo.saveAndFlush(new PasswordResetToken(hash("fresh"), userId, future())).getId();
        em.clear();

        repo.markAllUsedByUserId(userId);
        em.clear();

        assertThat(em.find(PasswordResetToken.class, usedId).isUsed())
                .as("already-used row remains used")
                .isTrue();
        assertThat(em.find(PasswordResetToken.class, freshId).isUsed())
                .as("previously-unused row is now used")
                .isTrue();
    }

    @Test
    @DisplayName("UNIQUE(token) rejects a second row with the same token hash as DataIntegrityViolationException")
    void should_rejectDuplicate_when_sameTokenHash() {
        repo.saveAndFlush(new PasswordResetToken(TOKEN_HASH, userId, future()));

        // Same hash for a different user must still collide — the UNIQUE is on token alone, not (user_id, token).
        UUID otherUserId = persistUser("reset-dup-" + UUID.randomUUID() + "@test.com");
        PasswordResetToken duplicate = new PasswordResetToken(TOKEN_HASH, otherUserId, future());

        assertThatThrownBy(() -> repo.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("ON DELETE CASCADE removes the token row when the owning user is deleted")
    void should_cascadeDeleteToken_when_ownerDeleted() {
        UUID tokenId = repo.saveAndFlush(new PasswordResetToken(TOKEN_HASH, userId, future())).getId();
        em.flush();
        em.clear();

        // user_id is a plain UUID column (no @ManyToOne), so JPA cannot cascade — the DB-side
        // ON DELETE CASCADE (V55) is the contract under test. Delete the parent via native SQL
        // so the cascade fires at the database, then assert the child row is gone.
        em.getEntityManager()
                .createNativeQuery("DELETE FROM users WHERE id = :id")
                .setParameter("id", userId)
                .executeUpdate();
        em.clear();

        assertThat(em.find(PasswordResetToken.class, tokenId))
                .as("token row must be cascade-deleted with its owning user")
                .isNull();
    }

    @Test
    @DisplayName("FK is enforced: a token referencing an unknown user_id is rejected as DataIntegrityViolationException")
    void should_rejectUnknownUser_when_fkViolated() {
        PasswordResetToken orphan = new PasswordResetToken(TOKEN_HASH, UUID.randomUUID(), future());

        assertThatThrownBy(() -> repo.saveAndFlush(orphan))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private UUID persistUser(String email) {
        User user = new User(
                email,
                "$2a$10$hash",
                Role.CLIENT,
                "Reset",
                "Owner",
                "+380501234567"
        );
        em.persist(user);
        em.flush();
        return user.getId();
    }

    private static Instant future() {
        return Instant.now().plus(1, ChronoUnit.HOURS);
    }

    /** SHA-256-hex-shaped unique 64-char token derived from a short label. */
    private static String hash(String label) {
        String base = (label + "-" + UUID.randomUUID()).replace("-", "");
        return (base + "0".repeat(64)).substring(0, 64);
    }
}
