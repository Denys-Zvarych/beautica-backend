package com.beautica.notification.repository;

import com.beautica.notification.entity.NotificationOutboxEntry;
import com.beautica.notification.entity.OutboxStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Validated
public interface NotificationOutboxRepository extends JpaRepository<NotificationOutboxEntry, UUID> {

    /**
     * Atomically claims up to {@code limit} rows whose {@code status} is {@code PENDING} and
     * flips them to {@link OutboxStatus#PROCESSING} in the SAME statement, ordered by
     * {@code created_at} ascending (oldest-first fairness).
     *
     * <p><strong>Why the status flip must be atomic with the claim (MEDIUM concurrency fix).</strong>
     * The previous version of this query was a plain {@code SELECT ... FOR UPDATE SKIP LOCKED} that
     * never changed {@code status}. The row lock it took is released the instant the caller's short
     * {@code REQUIRES_NEW} transaction commits — which happens long before dispatch (SMTP/FCM/SMS,
     * up to ~40s per entry) and the eventual status write complete. Between claim-commit and
     * status-write, the row was still {@code PENDING} and unlocked, so a second claimer — on
     * <em>another application instance</em>, not just another thread — could re-claim and
     * re-dispatch the same notification. This is a real production risk on Railway, which performs
     * rolling deploys: two instances run concurrently, each with its own live
     * {@code @Scheduled} timer, for the duration of every deploy. Flipping the row to
     * {@code PROCESSING} inside the claiming {@code UPDATE} closes that window: the row is
     * unavailable to any other claimer (via the {@code WHERE status = 'PENDING'} predicate) from
     * the instant this transaction commits, not merely while it is open.
     *
     * <p><strong>Why the locking SELECT is a separate {@code MATERIALIZED} CTE (batch-cap
     * concurrency fix).</strong> The earlier form was a single
     * {@code UPDATE ... WHERE id IN (SELECT id ... LIMIT :limit FOR UPDATE SKIP LOCKED)}. Under
     * PostgreSQL {@code READ COMMITTED}, when the driving {@code UPDATE} meets a row that a
     * concurrent transaction has updated in the meantime it performs an EvalPlanQual re-check — and
     * that re-check can <em>re-evaluate the {@code IN}-sublink</em>, i.e. re-run the
     * {@code LIMIT ... FOR UPDATE SKIP LOCKED} select against the now-changed lock landscape. The
     * re-evaluated sublink can yield rows beyond the original {@code LIMIT}, so a
     * {@code claimPendingBatch(1)} could flip (and return) TWO rows while a racing worker got zero —
     * the exact intermittent failure in
     * {@code NotificationOutboxIntegrationTest#should_skipLockedEntries_when_twoWorkersRunConcurrently}
     * (reproduced with {@code pg_backend_pid} logging: one connection, {@code limit=1},
     * {@code count=2}). Disabling pgjdbc server-side prepared statements
     * ({@code prepareThreshold=0}) and inlining the cap as a SQL literal both left it reproducing —
     * proving the cause is the sublink re-evaluation, not the parameter binding. Splitting the
     * locking SELECT into its own {@code WITH candidates AS MATERIALIZED (...)} CTE forces PostgreSQL
     * to evaluate the {@code LIMIT}/{@code FOR UPDATE SKIP LOCKED} exactly once; the {@code UPDATE}
     * then only joins the already-fixed, already-locked id set, so it can never touch more than
     * {@code limit} rows regardless of concurrent activity.
     *
     * <p>The outer {@code SELECT ... ORDER BY created_at ASC} re-sorts the result, because
     * PostgreSQL does not guarantee that an {@code UPDATE ... RETURNING} preserves the order of the
     * driving CTE.
     *
     * <p><strong>Crash safety.</strong> If an instance dies between this claim committing and the
     * eventual status write, the row is stranded in {@code PROCESSING} forever unless something
     * resets it — that "something" is
     * {@link com.beautica.notification.service.NotificationOutboxReclaimJob}, a separate low-frequency
     * sweep (mirrors {@code StaleVerificationCleanupJob} / {@code PasswordResetTokenCleanupJob})
     * that resets {@code PROCESSING} rows older than a configurable threshold back to
     * {@code PENDING} (or straight to {@code DEAD} once retries are exhausted). The partial index
     * {@code idx_outbox_stuck} (V34, {@code WHERE status = 'PROCESSING'}) makes that sweep's lookup
     * cheap. Both the {@code PROCESSING} status value and this index pre-date this fix (V32/V34) —
     * they were prepared but never wired up.
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
     * @return claimed rows — now in {@link OutboxStatus#PROCESSING} — in oldest-first order; never {@code null}, may be empty
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
        WITH candidates AS MATERIALIZED (
            SELECT id
              FROM notification_outbox
             WHERE status = 'PENDING'
             ORDER BY created_at ASC
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
        ),
        claimed AS (
            UPDATE notification_outbox n
               SET status = 'PROCESSING',
                   updated_at = now()
              FROM candidates c
             WHERE n.id = c.id
            RETURNING n.*
        )
        SELECT * FROM claimed ORDER BY created_at ASC
        """, nativeQuery = true)
    List<NotificationOutboxEntry> claimPendingBatch(@Param("limit") @Min(1) @Max(500) int limit);

    /**
     * Rescues rows stranded in {@link OutboxStatus#PROCESSING} by an instance that claimed them
     * (via {@link #claimPendingBatch(int)}) and then crashed or was killed before writing the
     * dispatch outcome — e.g. mid-rollout on Railway, or an OOM during SMTP dispatch.
     *
     * <p>A single bounded {@code UPDATE}, no per-row materialisation — mirrors
     * {@code deleteByStatusInAndUpdatedAtBefore}. Counts as a delivery attempt: {@code attempts}
     * is incremented exactly as a failed dispatch would, and if that reaches {@code maxAttempts}
     * the row goes straight to {@code DEAD} instead of back to {@code PENDING} — otherwise a
     * "poison" entry that crashes the worker every time it is dispatched would loop
     * claim → crash → reclaim forever instead of ever reaching the dead-letter state.
     *
     * <p>No {@code FOR UPDATE SKIP LOCKED} needed here: this is a single {@code UPDATE} statement,
     * and PostgreSQL re-evaluates each matched row's {@code WHERE} clause after acquiring its row
     * lock (EvalPlanQual) — so a second, truly-concurrent reclaimer naturally finds zero rows for
     * anything the first one already committed, without an explicit lock hint.
     *
     * @param staleBefore    rows whose {@code updated_at} is before this instant are reclaimed
     * @param maxAttempts    attempt count at or above which a reclaimed row is dead-lettered instead of retried
     * @param reclaimMessage message recorded in {@code last_error} for every reclaimed row
     * @return number of rows reclaimed
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
        UPDATE notification_outbox
           SET status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'DEAD' ELSE 'PENDING' END,
               attempts = attempts + 1,
               last_error = :reclaimMessage,
               updated_at = now()
         WHERE status = 'PROCESSING'
           AND updated_at < :staleBefore
        """, nativeQuery = true)
    int reclaimStaleProcessingRows(@Param("staleBefore") Instant staleBefore,
                                    @Param("maxAttempts") int maxAttempts,
                                    @Param("reclaimMessage") String reclaimMessage);

    /**
     * Counts rows in a given status — used by health-check and monitoring endpoints
     * to surface queue depth without loading full entities.
     *
     * @param status the status to count
     * @return number of rows with that status
     */
    long countByStatus(OutboxStatus status);

    /**
     * Deletes terminal rows (SENT or DEAD) whose {@code updated_at} is before the
     * given cutoff. Called by the daily TTL purge job in
     * {@link com.beautica.notification.service.NotificationOutboxDrainWorker#purgeStaleOutboxRows()}.
     *
     * <p>The partial index {@code idx_outbox_terminal_updated} (V59) covers
     * {@code (updated_at) WHERE status IN ('SENT','DEAD')} — this delete uses that
     * index and avoids a sequential scan on a large table.
     *
     * <p>Spring Data derives this as a {@code DELETE FROM notification_outbox WHERE
     * status IN (?) AND updated_at < ?} — no custom {@code @Query} needed.
     *
     * <p>Fix MEDIUM-8 PERF.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void deleteByStatusInAndUpdatedAtBefore(List<OutboxStatus> statuses, Instant cutoff);
}
