package com.beautica.notification.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxEventType;
import com.beautica.notification.entity.OutboxStatus;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
 *
 * <p>{@code MethodValidationPostProcessor} is imported by {@link AbstractDataJpaTest} so
 * the {@code @Validated} guard on
 * {@link NotificationOutboxRepository#claimPendingBatch(int)} fires inside the slice
 * context. Without that bean the {@code @DataJpaTest} slice would silently mask Bean
 * Validation regressions in this test, because Spring Boot's full
 * {@code ValidationAutoConfiguration} is not loaded by the slice.
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

    // ── Bean Validation regression ────────────────────────────────────────────

    /**
     * Regression test for the {@code @Validated} guard on {@link NotificationOutboxRepository}.
     *
     * <p>Asserts that {@link MethodValidationPostProcessor}-driven Bean Validation rejects
     * out-of-range {@code limit} values via {@link ConstraintViolationException} before the
     * JPQL fires. Without this test a future refactor (e.g. removing {@code @Validated} from
     * the repository interface, switching to a non-proxied repository implementation, or
     * dropping {@code @Min}/{@code @Max}) would silently neutralise the guard with no
     * green/red signal.
     *
     * <p>Tested values:
     * <ul>
     *   <li>{@code 0} — fails {@code @Min(1)}; would have returned an empty list silently.</li>
     *   <li>{@code -1} — fails {@code @Min(1)}; PostgreSQL would have rejected with a parse error,
     *       leaking implementation detail.</li>
     *   <li>{@code 501} — fails {@code @Max(500)}; first value above the cap.</li>
     *   <li>{@link Integer#MAX_VALUE} — fails {@code @Max(500)}; sanity check on the upper bound.</li>
     * </ul>
     *
     * <p>The {@code @Transactional} on this test is required because
     * {@link NotificationOutboxRepository#claimPendingBatch(int)} declares
     * {@link Propagation#MANDATORY}; the validation interceptor runs on the proxy
     * <strong>before</strong> the transactional interceptor delegates to the JPQL,
     * so an active transaction is needed for the repository proxy to be entered.
     * (Even without a transaction the validation would still fire for
     * {@code IllegalTransactionStateException} sequencing reasons — we want to assert the
     * validation specifically, so we run it inside an active transaction.)
     */
    @ParameterizedTest
    @ValueSource(ints = {0, -1, 501, Integer.MAX_VALUE})
    @DisplayName("Rejects out-of-range limit values with ConstraintViolationException (Bean Validation guard)")
    void should_throwConstraintViolationException_when_claimPendingBatchLimitOutOfRange(int badLimit) {
        // Service-layer validation runs through MethodValidationPostProcessor.
        // The repository proxy must reject before the JPQL fires.
        assertThatThrownBy(() -> repo.claimPendingBatch(badLimit))
                .isInstanceOf(ConstraintViolationException.class);
    }

    // ── Phase 18.1 — REVIEW_REQUESTED event type + chk_outbox_event (V109) ──────

    /**
     * V109 widened {@code chk_outbox_event} to admit {@code REVIEW_REQUESTED}. This test proves
     * a {@code REVIEW_REQUESTED} row with a non-null {@code aggregate_id} both satisfies the CHECK
     * and round-trips through the {@code @Enumerated(STRING)} mapping unchanged.
     */
    @Test
    @DisplayName("should_persistReviewRequestedEntry_when_eventTypeIsReviewRequested")
    void should_persistReviewRequestedEntry_when_eventTypeIsReviewRequested() {
        // Arrange
        UUID bookingId = UUID.randomUUID();
        NotificationOutboxEntry entry = NotificationOutboxEntry.builder()
                .eventType(OutboxEventType.REVIEW_REQUESTED)
                .aggregateId(bookingId)
                .build();

        // Act
        NotificationOutboxEntry persisted = em.persistAndFlush(entry);
        em.clear();

        NotificationOutboxEntry reloaded = em.find(NotificationOutboxEntry.class, persisted.getId());

        // Assert
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getEventType()).isEqualTo(OutboxEventType.REVIEW_REQUESTED);
        assertThat(reloaded.getAggregateId()).isEqualTo(bookingId);
    }

    /**
     * The {@code chk_outbox_event} CHECK must still reject an unknown event_type value — proving
     * V109 replaced the constraint rather than dropping it. A raw JDBC insert with a bogus value
     * must fail with a data-integrity violation.
     */
    @Test
    @DisplayName("should_rejectBogusEventType_when_insertViolatesChkOutboxEvent")
    void should_rejectBogusEventType_when_insertViolatesChkOutboxEvent() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO notification_outbox (id, event_type, aggregate_id, status, attempts) "
                        + "VALUES (gen_random_uuid(), 'BOGUS_EVENT', gen_random_uuid(), 'PENDING', 0)"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
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

    // ── Purge query ───────────────────────────────────────────────────────────

    /**
     * Verifies {@link NotificationOutboxRepository#deleteByStatusInAndUpdatedAtBefore}:
     * <ul>
     *   <li>Deletes SENT rows whose {@code updated_at} is before the cutoff.</li>
     *   <li>Deletes DEAD rows whose {@code updated_at} is before the cutoff.</li>
     *   <li>Preserves a recent SENT row whose {@code updated_at} is after the cutoff.</li>
     *   <li>Preserves a PENDING row regardless of age (status not in the delete set).</li>
     * </ul>
     *
     * <p>The {@code insertOutboxRow} helper sets {@code updated_at} equal to the supplied
     * {@code createdAt} timestamp via raw JDBC. Because
     * {@link NotificationOutboxRepository#deleteByStatusInAndUpdatedAtBefore} is annotated
     * with {@code @Transactional(propagation = REQUIRES_NEW)}, it opens a new database
     * transaction that must be able to read the test rows. The {@code @DataJpaTest}
     * default outer transaction would keep the JDBC inserts uncommitted, making them
     * invisible to the {@code REQUIRES_NEW} inner transaction. Therefore this test
     * suspends the outer transaction with
     * {@code @Transactional(propagation = NOT_SUPPORTED)} so that all JDBC inserts are
     * auto-committed before the purge call. Post-purge verification uses
     * {@link JdbcTemplate} (which does not require an active transaction) rather than
     * {@link TestEntityManager}.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("should_deleteSentAndDeadRowsOlderThanCutoff_andPreservePendingRows")
    void should_deleteSentAndDeadRowsOlderThanCutoff_andPreservePendingRows() {
        // Arrange
        Instant cutoff = Instant.now().minus(java.time.Duration.ofDays(30));
        Instant old    = cutoff.minusSeconds(3600);   // 1 hour before cutoff — must be deleted
        Instant recent = cutoff.plusSeconds(3600);    // 1 hour after cutoff  — must survive

        UUID oldSentId    = UUID.randomUUID();
        UUID oldDeadId    = UUID.randomUUID();
        UUID recentSentId = UUID.randomUUID();
        UUID oldPendingId = UUID.randomUUID();

        insertOutboxRow(oldSentId,    OutboxEventType.NEW_BOOKING,      "SENT",    old);
        insertOutboxRow(oldDeadId,    OutboxEventType.STATUS_CHANGED,   "DEAD",    old);
        insertOutboxRow(recentSentId, OutboxEventType.NEW_BOOKING,      "SENT",    recent);
        insertOutboxRow(oldPendingId, OutboxEventType.CLIENT_CANCELLED, "PENDING", old);

        // Act — REQUIRES_NEW inner transaction; rows are now auto-committed so they are visible.
        repo.deleteByStatusInAndUpdatedAtBefore(List.of(OutboxStatus.SENT, OutboxStatus.DEAD), cutoff);

        // Assert — verify via JDBC so no transaction boundary issues with the persistence context.
        assertThat(rowExists(oldSentId))
                .as("old SENT row must be deleted by the purge query")
                .isFalse();
        assertThat(rowExists(oldDeadId))
                .as("old DEAD row must be deleted by the purge query")
                .isFalse();
        assertThat(rowExists(recentSentId))
                .as("recent SENT row must survive (updated_at is after the cutoff)")
                .isTrue();
        assertThat(rowExists(oldPendingId))
                .as("old PENDING row must survive (status not in the delete set)")
                .isTrue();

        // Cleanup — NOT_SUPPORTED means no rollback; we must delete manually.
        jdbcTemplate.update("DELETE FROM notification_outbox WHERE id IN (?, ?, ?, ?)",
                recentSentId, oldPendingId, oldSentId, oldDeadId);
    }

    private boolean rowExists(UUID id) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification_outbox WHERE id = ?",
                Integer.class, id);
        return count != null && count > 0;
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
