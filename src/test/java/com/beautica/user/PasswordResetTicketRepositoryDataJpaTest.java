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
 * Repository-slice test for {@link PasswordResetTicketRepository} against a real PostgreSQL
 * container — pins the persistence contract of the {@code password_reset_tickets} table
 * (V107, renamed from {@code password_reset_tokens} V55/V100) at the JPQL/SQL boundary, where
 * the Mockito service unit tests ({@code PasswordResetServiceTest} /
 * {@code PasswordResetOtpProcessorTest}) cannot reach: the {@code unique} constraint on
 * {@code ticket_hash}, the DB-side {@code ON DELETE CASCADE} FK to {@code users}, and the live
 * JPQL of {@link PasswordResetTicketRepository#markAllUsedByUserId(UUID)} /
 * {@link PasswordResetTicketRepository#findByTicketHashForUpdate(String)}.
 *
 * <p>Extends {@link AbstractDataJpaTest}, which owns the shared singleton
 * {@code PostgreSQLContainer} and the {@code @DataJpaTest} slice annotations
 * (transactional rollback per test, repository beans only).
 */
@DisplayName("PasswordResetTicketRepository — @DataJpaTest persistence contract (V107)")
class PasswordResetTicketRepositoryDataJpaTest extends AbstractDataJpaTest {

    /** SHA-256-hex-shaped 64-char value; production stores only the hash, never the raw ticket. */
    private static final String TICKET_HASH =
            "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90";

    @Autowired
    private PasswordResetTicketRepository repo;

    @Autowired
    private TestEntityManager em;

    private UUID userId;

    @BeforeEach
    void setUp() {
        userId = persistUser("reset-repo-" + UUID.randomUUID() + "@test.com");
    }

    @Test
    @DisplayName("save then findByTicketHash round-trips the row with every business field reloaded")
    void should_roundTripByTicketHash_when_saved() {
        Instant expiresAt = Instant.now().plus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS);
        PasswordResetTicket saved = repo.saveAndFlush(new PasswordResetTicket(TICKET_HASH, userId, expiresAt));
        em.clear();

        Optional<PasswordResetTicket> found = repo.findByTicketHash(TICKET_HASH);

        assertThat(found).isPresent();
        assertThat(found.get())
                .extracting(
                        PasswordResetTicket::getId,
                        PasswordResetTicket::getTicketHash,
                        PasswordResetTicket::getUserId,
                        PasswordResetTicket::isUsed)
                .containsExactly(saved.getId(), TICKET_HASH, userId, false);
        assertThat(found.get().getExpiresAt())
                .as("expires_at survives the TIMESTAMPTZ round-trip at microsecond precision")
                .isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("findByTicketHash returns empty when no row matches the supplied hash")
    void should_returnEmpty_when_ticketHashUnknown() {
        repo.saveAndFlush(new PasswordResetTicket(TICKET_HASH, userId, future()));
        em.clear();

        assertThat(repo.findByTicketHash("no-such-ticket-hash")).isEmpty();
    }

    @Test
    @DisplayName("findByTicketHashForUpdate (pessimistic-write) selects the same row as the non-locking finder")
    void should_returnRow_when_findByTicketHashForUpdate() {
        PasswordResetTicket saved = repo.saveAndFlush(new PasswordResetTicket(TICKET_HASH, userId, future()));
        em.clear();

        Optional<PasswordResetTicket> locked = repo.findByTicketHashForUpdate(TICKET_HASH);

        assertThat(locked).isPresent();
        assertThat(locked.get().getId())
                .as("the FOR UPDATE finder must resolve the same row id as save")
                .isEqualTo(saved.getId());
        assertThat(locked.get().getUserId()).isEqualTo(userId);
    }

    @Test
    @DisplayName("markAllUsedByUserId flips all of the user's unused tickets to used and leaves other users untouched")
    void should_flipAllUnusedTickets_when_markAllUsedByUserId() {
        UUID firstId = repo.saveAndFlush(new PasswordResetTicket(hash("u1-a"), userId, future())).getId();
        UUID secondId = repo.saveAndFlush(new PasswordResetTicket(hash("u1-b"), userId, future())).getId();

        UUID otherUserId = persistUser("reset-other-" + UUID.randomUUID() + "@test.com");
        UUID otherTicketId = repo.saveAndFlush(new PasswordResetTicket(hash("u2-a"), otherUserId, future())).getId();
        em.clear();

        repo.markAllUsedByUserId(userId);
        em.clear();

        assertThat(em.find(PasswordResetTicket.class, firstId).isUsed())
                .as("first ticket of the target user must be marked used")
                .isTrue();
        assertThat(em.find(PasswordResetTicket.class, secondId).isUsed())
                .as("second ticket of the target user must be marked used")
                .isTrue();
        assertThat(em.find(PasswordResetTicket.class, otherTicketId).isUsed())
                .as("another user's ticket must NOT be affected by the per-user sweep")
                .isFalse();
    }

    @Test
    @DisplayName("markAllUsedByUserId only touches rows where is_used = false (already-used rows stay used, no churn)")
    void should_skipAlreadyUsedRows_when_markAllUsedByUserId() {
        PasswordResetTicket alreadyUsed = new PasswordResetTicket(hash("used"), userId, future());
        alreadyUsed.markUsed();
        UUID usedId = repo.saveAndFlush(alreadyUsed).getId();
        UUID freshId = repo.saveAndFlush(new PasswordResetTicket(hash("fresh"), userId, future())).getId();
        em.clear();

        repo.markAllUsedByUserId(userId);
        em.clear();

        assertThat(em.find(PasswordResetTicket.class, usedId).isUsed())
                .as("already-used row remains used")
                .isTrue();
        assertThat(em.find(PasswordResetTicket.class, freshId).isUsed())
                .as("previously-unused row is now used")
                .isTrue();
    }

    @Test
    @DisplayName("UNIQUE(ticket_hash) rejects a second row with the same hash as DataIntegrityViolationException")
    void should_rejectDuplicate_when_sameTicketHash() {
        repo.saveAndFlush(new PasswordResetTicket(TICKET_HASH, userId, future()));

        UUID otherUserId = persistUser("reset-dup-" + UUID.randomUUID() + "@test.com");
        PasswordResetTicket duplicate = new PasswordResetTicket(TICKET_HASH, otherUserId, future());

        assertThatThrownBy(() -> repo.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("ON DELETE CASCADE removes the ticket row when the owning user is deleted")
    void should_cascadeDeleteTicket_when_ownerDeleted() {
        UUID ticketId = repo.saveAndFlush(new PasswordResetTicket(TICKET_HASH, userId, future())).getId();
        em.flush();
        em.clear();

        em.getEntityManager()
                .createNativeQuery("DELETE FROM users WHERE id = :id")
                .setParameter("id", userId)
                .executeUpdate();
        em.clear();

        assertThat(em.find(PasswordResetTicket.class, ticketId))
                .as("ticket row must be cascade-deleted with its owning user")
                .isNull();
    }

    @Test
    @DisplayName("FK is enforced: a ticket referencing an unknown user_id is rejected as DataIntegrityViolationException")
    void should_rejectUnknownUser_when_fkViolated() {
        PasswordResetTicket orphan = new PasswordResetTicket(TICKET_HASH, UUID.randomUUID(), future());

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
        return Instant.now().plus(10, ChronoUnit.MINUTES);
    }

    /** SHA-256-hex-shaped unique 64-char ticket hash derived from a short label. */
    private static String hash(String label) {
        String base = (label + "-" + UUID.randomUUID()).replace("-", "");
        return (base + "0".repeat(64)).substring(0, 64);
    }
}
