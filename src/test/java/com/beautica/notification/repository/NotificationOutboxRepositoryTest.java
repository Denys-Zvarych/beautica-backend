package com.beautica.notification.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Repository-layer tests for {@link NotificationOutboxRepository}.
 *
 * <p>Covers the three MEDIUM and two LOW QA findings from Phase 5.4:
 * <ul>
 *   <li>MEDIUM-1: {@code claimPendingBatch} must throw when called without an active transaction.</li>
 *   <li>MEDIUM-2: {@code claimPendingBatch} must return only PENDING rows in {@code created_at ASC} order.</li>
 *   <li>MEDIUM-3: {@code claimPendingBatch} must honour the caller-supplied limit.</li>
 *   <li>LOW-1: default {@code status} and {@code attempts} values are applied at persist time.</li>
 *   <li>LOW-2: {@code countByStatus} returns accurate per-status counts.</li>
 * </ul>
 *
 * <p>Extends {@link AbstractDataJpaTest} so the PostgreSQL container is shared across
 * {@code @DataJpaTest} slice tests within the JVM. Slice annotations
 * ({@code @DataJpaTest}, {@code @AutoConfigureTestDatabase}, {@code @Testcontainers},
 * {@code @ActiveProfiles}) live on the base class.
 *
 * <p>Uses a real PostgreSQL container so that {@code FOR UPDATE SKIP LOCKED} and
 * {@code CHECK} constraints execute exactly as in production.
 */
class NotificationOutboxRepositoryTest extends AbstractDataJpaTest {

    @Autowired
    private NotificationOutboxRepository repo;

    @Autowired
    private TestEntityManager em;

    /**
     * {@code @DataJpaTest} auto-configures {@link JdbcTemplate} pointing at the same DataSource.
     * Used to insert rows with explicit {@code created_at} values, bypassing
     * Hibernate's {@code @CreationTimestamp} so that ordering tests are deterministic.
     */
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanOutbox() {
        jdbcTemplate.execute("DELETE FROM notification_outbox");
    }

    // ── MEDIUM-1 ──────────────────────────────────────────────────────────────

    /**
     * MEDIUM-1: Verifies that {@code Propagation.MANDATORY} on {@code claimPendingBatch}
     * is enforced at runtime.
     *
     * <p>{@code @DataJpaTest} wraps every test in a transaction by default.
     * {@code @Transactional(propagation = NOT_SUPPORTED)} suspends that transaction for
     * this test method only, so the call to {@code claimPendingBatch} is made with no
     * active transaction present. Spring's transaction interceptor on the repository proxy
     * must throw {@link IllegalTransactionStateException} immediately.
     *
     * <p>Rows are seeded via {@link JdbcTemplate} before suspending the transaction;
     * the JDBC insert is committed by the test framework's per-test rollback mechanism.
     */
    @Test
    @DisplayName("should_throwIllegalTransactionStateException_when_claimCalledWithoutTransaction")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void should_throwIllegalTransactionStateException_when_claimCalledWithoutTransaction() {
        // Arrange — seed one PENDING row via raw JDBC (no JPA/Hibernate involvement needed;
        // the row just needs to exist so the query is meaningful).
        jdbcTemplate.update(
                "INSERT INTO notification_outbox (id, event_type, aggregate_id, status, attempts) "
                        + "VALUES (gen_random_uuid(), 'NEW_BOOKING', gen_random_uuid(), 'PENDING', 0)"
        );

        // Act + Assert — must throw because no active transaction is present.
        assertThatThrownBy(() -> repo.claimPendingBatch(5))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ── MEDIUM-2 ──────────────────────────────────────────────────────────────

    /**
     * MEDIUM-2: Verifies that {@code claimPendingBatch} returns only PENDING rows
     * and that they arrive in {@code created_at ASC} (oldest-first) order.
     *
     * <p>Rows are inserted with explicit {@code created_at} values one second apart via
     * {@link JdbcTemplate} to guarantee deterministic ordering regardless of clock
     * resolution inside the container.
     */
    @Test
    @DisplayName("should_returnOnlyPendingRows_inCreatedAtAscOrder_when_mixedStatusRowsExist")
    void should_returnOnlyPendingRows_inCreatedAtAscOrder_when_mixedStatusRowsExist() {
        // Arrange — explicit timestamps guarantee strict ordering.
        Instant t0 = Instant.parse("2026-01-01T10:00:00Z");
        Instant t1 = t0.plusSeconds(1);
        Instant t2 = t1.plusSeconds(1);
        Instant t3 = t2.plusSeconds(1);
        Instant t4 = t3.plusSeconds(1);

        UUID pendingId1 = UUID.randomUUID();
        UUID pendingId2 = UUID.randomUUID();
        UUID pendingId3 = UUID.randomUUID();

        // 1 SENT row (t0) — must be excluded from results.
        insertOutboxRow(UUID.randomUUID(), OutboxEventType.NEW_BOOKING, "SENT", t0);
        // 1 DEAD row (t1) — must be excluded from results.
        insertOutboxRow(UUID.randomUUID(), OutboxEventType.STATUS_CHANGED, "DEAD", t1);
        // 3 PENDING rows at t2, t3, t4 — must all appear in ascending order.
        insertOutboxRow(pendingId1, OutboxEventType.NEW_BOOKING, "PENDING", t2);
        insertOutboxRow(pendingId2, OutboxEventType.STATUS_CHANGED, "PENDING", t3);
        insertOutboxRow(pendingId3, OutboxEventType.CLIENT_CANCELLED, "PENDING", t4);

        // Act
        List<NotificationOutboxEntry> result = repo.claimPendingBatch(10);

        // Assert — count, status, and ascending order.
        assertThat(result).hasSize(3);
        assertThat(result).allMatch(e -> e.getStatus() == OutboxStatus.PENDING);
        assertThat(result)
                .extracting(NotificationOutboxEntry::getId)
                .containsExactly(pendingId1, pendingId2, pendingId3);
        assertThat(result)
                .extracting(e -> e.getCreatedAt().toEpochMilli())
                .isSorted();
    }

    // ── MEDIUM-3 ──────────────────────────────────────────────────────────────

    /**
     * MEDIUM-3: Verifies that {@code claimPendingBatch} respects the {@code LIMIT} clause
     * when the number of eligible PENDING rows exceeds the caller-supplied limit.
     */
    @Test
    @DisplayName("should_respectLimit_when_pendingRowsExceedLimit")
    void should_respectLimit_when_pendingRowsExceedLimit() {
        // Arrange — 5 PENDING rows, limit = 2.
        Instant base = Instant.parse("2026-02-01T08:00:00Z");
        for (int i = 0; i < 5; i++) {
            insertOutboxRow(UUID.randomUUID(), OutboxEventType.NEW_BOOKING, "PENDING",
                    base.plusSeconds(i));
        }

        // Act
        List<NotificationOutboxEntry> result = repo.claimPendingBatch(2);

        // Assert
        assertThat(result).hasSize(2);
    }

    // ── LOW-1 ─────────────────────────────────────────────────────────────────

    /**
     * LOW-1: Verifies that {@code @Builder.Default} fields ({@code status} and
     * {@code attempts}) are populated with their expected defaults when a builder
     * caller omits those fields.
     */
    @Test
    @DisplayName("should_persistEntry_with_defaultStatusAndAttempts")
    void should_persistEntry_with_defaultStatusAndAttempts() {
        // Arrange — only mandatory field set; rely on @Builder.Default for the rest.
        NotificationOutboxEntry entry = NotificationOutboxEntry.builder()
                .eventType(OutboxEventType.NEW_BOOKING)
                .aggregateId(UUID.randomUUID())
                .build();

        // Act
        NotificationOutboxEntry persisted = em.persistAndFlush(entry);
        em.clear();

        NotificationOutboxEntry reloaded = em.find(NotificationOutboxEntry.class, persisted.getId());

        // Assert
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(reloaded.getAttempts()).isZero();
    }

    // ── LOW-2 ─────────────────────────────────────────────────────────────────

    /**
     * LOW-2: Verifies that {@code countByStatus} returns accurate per-status counts
     * when the outbox contains rows in multiple states.
     */
    @Test
    @DisplayName("should_countByStatus_when_mixedStatusRowsExist")
    void should_countByStatus_when_mixedStatusRowsExist() {
        // Arrange — 2 PENDING + 1 SENT + 1 DEAD.
        Instant base = Instant.parse("2026-03-01T09:00:00Z");
        insertOutboxRow(UUID.randomUUID(), OutboxEventType.NEW_BOOKING, "PENDING", base);
        insertOutboxRow(UUID.randomUUID(), OutboxEventType.STATUS_CHANGED, "PENDING", base.plusSeconds(1));
        insertOutboxRow(UUID.randomUUID(), OutboxEventType.CLIENT_CANCELLED, "SENT", base.plusSeconds(2));
        insertOutboxRow(UUID.randomUUID(), OutboxEventType.NEW_BOOKING, "DEAD", base.plusSeconds(3));

        // Act + Assert
        assertThat(repo.countByStatus(OutboxStatus.PENDING)).isEqualTo(2L);
        assertThat(repo.countByStatus(OutboxStatus.SENT)).isEqualTo(1L);
        assertThat(repo.countByStatus(OutboxStatus.DEAD)).isEqualTo(1L);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Inserts a row with an explicit {@code created_at} timestamp via raw JDBC.
     * This bypasses Hibernate's {@code @CreationTimestamp} so that ordering and
     * status-filter tests are fully deterministic.
     *
     * <p>The {@code aggregate_id} column is required for all non-INVITE event types
     * by the {@code chk_outbox_booking_aggregate} CHECK constraint.
     *
     * @param id        the UUID to use as the primary key
     * @param eventType the domain event type (determines CHECK constraint applicability)
     * @param status    raw string value — must satisfy {@code chk_outbox_status}
     * @param createdAt the timestamp to stamp on the row
     */
    private void insertOutboxRow(UUID id, OutboxEventType eventType, String status, Instant createdAt) {
        jdbcTemplate.update(
                """
                INSERT INTO notification_outbox
                    (id, event_type, aggregate_id, status, attempts, created_at, updated_at)
                VALUES (?, ?, gen_random_uuid(), ?, 0, ?, ?)
                """,
                id,
                eventType.name(),
                status,
                java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(createdAt)
        );
    }
}
