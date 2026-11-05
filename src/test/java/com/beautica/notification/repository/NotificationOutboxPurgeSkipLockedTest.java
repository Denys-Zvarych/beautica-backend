package com.beautica.notification.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hotfix fix/app-datasource-pool — the {@code FOR UPDATE SKIP LOCKED} half of
 * {@link NotificationOutboxRepository#deleteTerminalBatchBefore}. Siblings in
 * {@link NotificationOutboxRepositoryTest} pin the predicate and the batch limit; nothing pinned that the daily
 * purge never WAITS on a terminal row another transaction holds. Without {@code SKIP LOCKED} the batch would
 * queue behind the holder until the 5s {@code statement_timeout} cancels it, and the purge run would die.
 *
 * <p>{@code NOT_SUPPORTED}: the purge is {@code REQUIRES_NEW}, so seed rows must be committed to be visible
 * to it (same rationale as the sibling purge tests). Rows are removed in {@link #cleanOutbox()} (one table,
 * no FK dependants).
 */
@DisplayName("NotificationOutboxRepository — purge batch skips locked rows instead of waiting")
class NotificationOutboxPurgeSkipLockedTest extends AbstractDataJpaTest {

    /** Well under the 5s statement_timeout a blocked (non-SKIP LOCKED) batch would wait for. */
    private static final Duration NO_WAIT_BUDGET = Duration.ofSeconds(2);

    @Autowired
    private NotificationOutboxRepository repo;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    @AfterEach
    void cleanOutbox() {
        jdbcTemplate.execute("DELETE FROM notification_outbox");
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("a terminal row locked by another transaction is skipped, the rest are deleted, and the batch does not wait")
    void should_skipLockedRowWithoutWaiting_when_anotherTransactionHoldsATerminalRow() throws Exception {
        // Arrange — three old SENT rows, one row-locked on a separate connection
        Instant cutoff = Instant.now().minus(Duration.ofDays(30));
        UUID locked = UUID.randomUUID();
        UUID freeA = UUID.randomUUID();
        UUID freeB = UUID.randomUUID();
        insertSentRow(locked, cutoff.minusSeconds(3 * 3600));
        insertSentRow(freeA, cutoff.minusSeconds(2 * 3600));
        insertSentRow(freeB, cutoff.minusSeconds(3600));

        int deleted;
        long elapsedNanos;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (PreparedStatement lock = holder.prepareStatement(
                    "SELECT id FROM notification_outbox WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, locked);
                lock.executeQuery().close();
            }

            // Act
            long start = System.nanoTime();
            deleted = repo.deleteTerminalBatchBefore(List.of(OutboxStatus.SENT.name()), cutoff, 100);
            elapsedNanos = System.nanoTime() - start;

            holder.rollback();
        }

        // Assert
        assertThat(deleted).as("only the two unlocked rows are deleted").isEqualTo(2);
        assertThat(rowExists(locked)).as("the row held by another transaction survives for a later run").isTrue();
        assertThat(rowExists(freeA)).isFalse();
        assertThat(rowExists(freeB)).isFalse();
        assertThat(Duration.ofNanos(elapsedNanos))
                .as("SKIP LOCKED must not queue behind the holder, elapsed=%s", Duration.ofNanos(elapsedNanos))
                .isLessThan(NO_WAIT_BUDGET);
    }

    private void insertSentRow(UUID id, Instant updatedAt) {
        jdbcTemplate.update("""
                INSERT INTO notification_outbox
                    (id, event_type, aggregate_id, status, attempts, created_at, updated_at)
                VALUES (?, ?, gen_random_uuid(), 'SENT', 0, ?, ?)
                """, id, OutboxEventType.NEW_BOOKING.name(), Timestamp.from(updatedAt), Timestamp.from(updatedAt));
    }

    private boolean rowExists(UUID id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE id = ?", Integer.class, id);
        return count != null && count > 0;
    }
}
