package com.beautica.notification.repository;

import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.util.List;
import java.util.UUID;

@Validated
public interface NotificationOutboxRepository extends JpaRepository<NotificationOutboxEntry, UUID> {

    /**
     * Atomically claims up to {@code limit} rows whose {@code status} is {@code PENDING},
     * ordered by {@code created_at} ascending (oldest-first fairness).
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} ensures that concurrent scheduler instances
     * on multiple application pods each receive a distinct, non-overlapping batch —
     * rows already locked by another transaction are silently skipped rather than
     * blocking. The lock is released at the end of the caller's transaction.
     *
     * <p><strong>Must be called within an active {@code @Transactional} context.</strong>
     * Without a surrounding transaction the {@code FOR UPDATE} lock is held only for
     * the duration of the query itself, making {@code SKIP LOCKED} a no-op and
     * allowing multiple instances to claim the same rows.
     *
     * <p>{@code Propagation.MANDATORY} enforces this contract at call time — if no
     * active transaction exists, {@link org.springframework.transaction.IllegalTransactionStateException}
     * is thrown immediately, preventing silent duplicate delivery across concurrent drain workers.
     *
     * <p>The {@code limit} is bound to {@code [1, 500]} via Bean Validation
     * ({@code @Min}/{@code @Max}) — {@code 0} would silently return an empty list while
     * {@link Integer#MAX_VALUE} would issue an effectively uncapped {@code LIMIT} to
     * PostgreSQL. {@code @Validated} on the repository interface enables Spring's
     * {@code MethodValidationPostProcessor} to enforce these constraints at call time,
     * raising {@link jakarta.validation.ConstraintViolationException} on violation.
     *
     * @param limit maximum number of rows to claim in one batch (must be in {@code [1, 500]}; recommend 50–200)
     * @return claimed rows in oldest-first order; never {@code null}, may be empty
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
        SELECT *
          FROM notification_outbox
         WHERE status = 'PENDING'
         ORDER BY created_at ASC
         LIMIT :limit
         FOR UPDATE SKIP LOCKED
        """, nativeQuery = true)
    List<NotificationOutboxEntry> claimPendingBatch(@Param("limit") @Min(1) @Max(500) int limit);

    /**
     * Counts rows in a given status — used by health-check and monitoring endpoints
     * to surface queue depth without loading full entities.
     *
     * @param status the status to count
     * @return number of rows with that status
     */
    long countByStatus(OutboxStatus status);
}
