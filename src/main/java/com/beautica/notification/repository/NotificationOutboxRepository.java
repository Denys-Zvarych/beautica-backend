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
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Validated
public interface NotificationOutboxRepository extends JpaRepository<NotificationOutboxEntry, UUID> {

    /**
     * Atomically claims up to {@code limit} rows whose {@code status} is {@code PENDING} and
     * flips them to {@link OutboxStatus#PROCESSING} in the SAME statement, ordered by
     * {@code created_at} ascending (oldest-first fairness WITHIN each class).
     *
     * <p><b>Push priority (phase 367 audit 3).</b> {@code INAPP_PUSH} rows are claimed first, e-mail
     * and other rows fill the remaining slots. Deferred e-mail rows (breaker open / Brevo outage)
     * keep their original {@code created_at}, so under plain oldest-first a backlog of
     * {@code >= limit} e-mails would starve every new push row for as long as e-mail keeps failing.
     * Two separate {@code FOR UPDATE SKIP LOCKED} CTEs (rather than {@code ORDER BY
     * (event_type = 'INAPP_PUSH') DESC, created_at}) keep each branch on the partial index
     * {@code idx_outbox_pending (created_at) WHERE status = 'PENDING'} (V32): a computed sort key
     * would defeat the index and sort every PENDING row on each 5 s tick. Push rows are tiny and
     * fast (no SMTP), so they cannot starve e-mail in turn: the other branch still gets
     * {@code limit - pushRowsClaimed} slots, and a full batch of pushes drains in one tick.
     *
     * <p><b>SMS-backpressure deferral (phase 390 D11).</b> The non-push branch skips rows whose
     * {@code not_before} (V203) is still in the future. Only {@link #deferPendingRow} sets it — on a
     * row the worker put back to {@code PENDING} because the SMS executor pushed back — so a burst of
     * {@code >= limit} deferred SMS rows (which keep their {@code created_at}) no longer occupies the
     * head of every batch and starves newer e-mail rows. {@code NULL} (every other row) is eligible
     * immediately, exactly as before. The predicate is a row filter on the same
     * {@code idx_outbox_pending} walk, not a new sort key, so the branch stays index-ordered. Push
     * rows are never deferred this way, so the push branch is unchanged.
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
     * <p>The outer {@code SELECT ... ORDER BY (event_type = 'INAPP_PUSH') DESC, created_at ASC} re-sorts (push first, so a hung e-mail provider cannot delay push) the result, because
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
        WITH push_candidates AS MATERIALIZED (
            SELECT id
              FROM notification_outbox
             WHERE status = 'PENDING'
               AND event_type = 'INAPP_PUSH'
             ORDER BY created_at ASC
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
        ),
        other_candidates AS MATERIALIZED (
            SELECT id
              FROM notification_outbox
             WHERE status = 'PENDING'
               AND event_type <> 'INAPP_PUSH'
               AND (not_before IS NULL OR not_before <= now())
             ORDER BY created_at ASC
             LIMIT GREATEST(:limit - (SELECT count(*) FROM push_candidates), 0)
               FOR UPDATE SKIP LOCKED
        ),
        candidates AS (
            SELECT id FROM push_candidates
            UNION ALL
            SELECT id FROM other_candidates
        ),
        claimed AS (
            UPDATE notification_outbox n
               SET status = 'PROCESSING',
                   updated_at = now()
              FROM candidates c
             WHERE n.id = c.id
            RETURNING n.*
        )
        SELECT * FROM claimed ORDER BY (event_type = 'INAPP_PUSH') DESC, created_at ASC
        """, nativeQuery = true)
    List<NotificationOutboxEntry> claimPendingBatch(@Param("limit") @Min(1) @Max(500) int limit);

    /**
     * Pushes one row's claim eligibility {@code deferSeconds} into the future (database clock, the
     * same {@code now()} {@link #claimPendingBatch} compares against — no app/DB clock skew), so the
     * next claims pick newer rows first (phase 390 D11). Touches only {@code not_before}: status,
     * attempts and {@code last_error} are written by the caller's entity save in the SAME
     * transaction, so the deferral is atomic with the row going back to {@code PENDING}.
     *
     * <p>{@code MANDATORY}: it must ride the per-entry {@code REQUIRES_NEW} transaction of
     * {@code NotificationOutboxDrainWorker#persistOne}, never commit on its own.
     *
     * @param id           the outbox row to defer
     * @param deferSeconds seconds from now before the row is claimable again ({@code [1, 300]})
     * @return number of rows updated (1, or 0 if the row no longer exists)
     */
    @Modifying
    @Transactional(propagation = Propagation.MANDATORY)
    @Query(value = """
        UPDATE notification_outbox
           SET not_before = now() + make_interval(secs => :deferSeconds)
         WHERE id = :id
        """, nativeQuery = true)
    int deferPendingRow(@Param("id") UUID id, @Param("deferSeconds") @Min(1) @Max(300) int deferSeconds);

    /**
     * Rescues rows stranded in {@link OutboxStatus#PROCESSING} by an instance that claimed them
     * (via {@link #claimPendingBatch(int)}) and then crashed or was killed before writing the
     * dispatch outcome — e.g. mid-rollout on Railway, or an OOM during SMTP dispatch.
     *
     * <p>A single bounded {@code UPDATE}, no per-row materialisation — mirrors
     * {@code deleteTerminalBatchBefore}. Counts as a delivery attempt: {@code attempts}
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
     * Deletes ONE bounded batch of terminal rows (status in {@code statuses}, i.e. SENT/DEAD) whose
     * {@code updated_at} is before {@code cutoff}, in its own {@code REQUIRES_NEW} transaction. Called in a
     * loop by the daily TTL purge job
     * {@link com.beautica.notification.service.NotificationOutboxDrainWorker#purgeStaleOutboxRows()} until a
     * batch deletes fewer than {@code limit} rows.
     *
     * <p><b>A real set-based DELETE.</b> The derived {@code deleteByStatusInAndUpdatedAtBefore} this replaces
     * was not one: Spring Data derived deletes SELECT the matching entities and {@code em.remove()} them one
     * by one, all inside a single transaction. This statement deletes in the database, at most {@code limit}
     * rows per short transaction, so a large backlog never becomes one long row-lock-holding transaction.
     *
     * <p>Predicate unchanged: {@code status IN (:statuses) AND updated_at < :cutoff}, served by the partial
     * index {@code idx_outbox_terminal_updated} (V59, {@code (updated_at) WHERE status IN ('SENT','DEAD')}).
     * The {@code MATERIALIZED} CTE evaluates {@code LIMIT}/{@code FOR UPDATE SKIP LOCKED} exactly once — the
     * shape and rationale of {@code InAppNotificationRepository#deleteCreatedBefore} (an {@code IN}-sublink can
     * delete more than {@code limit} rows under a nested-loop re-scan). {@code SKIP LOCKED} never waits.
     *
     * @param statuses {@link OutboxStatus} names to purge (the caller passes SENT and DEAD)
     * @return the number of rows deleted (never more than {@code limit})
     */
    @Modifying
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Query(value = """
            WITH doomed AS MATERIALIZED (
                SELECT id FROM notification_outbox
                 WHERE status IN (:statuses)
                   AND updated_at < :cutoff
                 ORDER BY updated_at, id
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
            )
            DELETE FROM notification_outbox o USING doomed WHERE o.id = doomed.id
            """, nativeQuery = true)
    int deleteTerminalBatchBefore(
            @Param("statuses") Collection<String> statuses,
            @Param("cutoff") Instant cutoff,
            @Param("limit") @Min(1) @Max(5000) int limit);

    /**
     * Deletes every outbox row addressed to one of the given {@code aggregateId}s (i.e.
     * {@code bookingId}s for booking-shaped events such as {@code STATUS_CHANGED}).
     *
     * <p>{@code notification_outbox.aggregate_id} carries no FK to {@code bookings} (V32) — it is a
     * raw UUID the drain worker re-hydrates at send time (see
     * {@link com.beautica.notification.service.NotificationOutboxService#enqueueStatusChanged}'s
     * "no client PII duplicated into the payload" convention). That means physically deleting a
     * booking with a still-{@code PENDING} outbox row pointing at it orphans the row: the drain
     * worker's {@code getBooking()} finds nothing, throws {@code IllegalStateException}, and the
     * row is retried into {@code DEAD}. A caller that hard-deletes a booking in the same
     * transaction MUST call this first (or in the same flush) for that booking's id — scoped to
     * EXACTLY the event type(s) its own write superseded, never a blanket sweep of every row on
     * that aggregate id (a scope-free call here would also destroy an unrelated, still-PENDING row
     * addressed to the same booking, e.g. an undrained {@code BOOKING_RESCHEDULED} — the security
     * finding that retired this method's own former caller, see below).
     *
     * <p><b>Currently unused in production code (Phase 338 perf/security audit, item 1/3).</b>
     * {@code ClientAccountDeletionService#deleteOwnAccount} was this method's sole production
     * caller, deleting every per-booking {@code STATUS_CHANGED} row a future-booking cancel cascade
     * had just enqueued, with no event-type filter, before physically deleting those same bookings.
     * Two changes retired that call site entirely: (1) future bookings are now CANCELLED and KEPT,
     * never hard-deleted (Phase 338 — REVERSES the earlier cancel-then-delete design this method
     * was written for), so there is no longer a hard-delete to protect against in the first place;
     * (2) the cancel cascade itself was rerouted through {@code
     * BookingService#cancelFutureConfirmedBookingsForClientSelfDelete} /
     * {@code #cancelBookingForBatch}, which never calls {@code enqueueStatusChanged} at all — so
     * there is no per-booking row to delete even if a hard-delete still existed. Retained (rather
     * than removed) as a general bulk-delete utility, with its own regression coverage in
     * {@code NotificationOutboxRepositoryTest} — any future caller MUST scope {@code aggregateIds}
     * to bookings it is ACTUALLY about to hard-delete, and should prefer filtering to the specific
     * event type(s) being superseded over a blanket per-aggregate delete.
     *
     * <p>Deliberately participates in the CALLER's transaction (unlike
     * {@link #deleteTerminalBatchBefore}, which is an isolated housekeeping sweep) — this
     * delete must commit or roll back atomically with the booking delete it is paired with, so no
     * explicit {@code @Transactional} propagation is declared here.
     *
     * <p><b>Bulk JPQL, not a derived load-then-remove (perf audit, 2026-09, staff self-delete
     * Finding B).</b> A plain {@code deleteByAggregateIdIn} with no {@code @Modifying}/{@code
     * @Query} compiles to Spring Data's {@code DeleteExecution}, which runs a {@code SELECT}
     * followed by one {@code entityManager.remove()} per matched row — never a single {@code
     * DELETE ... WHERE ... IN (...)} statement (confirmed by decompiling {@code
     * JpaQueryExecution$DeleteExecution#doExecute}: {@code getResultList()} then a {@code remove()}
     * loop). The explicit {@code @Modifying @Query} below restores the single-statement bulk delete
     * — the same shape {@code declineConfirmedBulk} and {@code deleteAllByIdInBatch} already use
     * elsewhere in this cascade family — for whichever future caller needs it.
     *
     * @param aggregateIds the aggregate ids (booking ids) whose pending outbox rows must go; the
     *                     caller guards the empty-collection case (an {@code IN ()} is wasted work)
     */
    @Modifying
    @Query("DELETE FROM NotificationOutboxEntry e WHERE e.aggregateId IN :aggregateIds")
    void deleteByAggregateIdIn(@Param("aggregateIds") Collection<UUID> aggregateIds);
}
