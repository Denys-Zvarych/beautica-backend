package com.beautica.booking.repository;

import com.beautica.booking.entity.Appointment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Data access for the multi-service single-visit aggregate header (BE-3). Overlap protection stays
 * per-child-row on {@code bookings} (the {@code no_overlapping_bookings} EXCLUDE constraint), so this
 * repository holds only the aggregate save + the idempotent-replay lookup.
 */
public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    /**
     * The client's live (CONFIRMED) appointment for a given idempotency key, if any — the
     * idempotent-replay lookup, aligned to the partial-unique index
     * {@code ux_appointments_client_idempotency_key_active} (V124,
     * {@code WHERE idempotency_key IS NOT NULL AND status = 'CONFIRMED'}). Filtering by
     * {@code status = CONFIRMED} lets the planner use that partial index rather than scanning all
     * rows, exactly as {@code BookingRepository#findActiveByClientIdAndIdempotencyKey} does for the
     * single-service path.
     *
     * <p>Returns only the header — the caller re-reads the chained bookings via
     * {@code BookingRepository#findByAppointmentIdWithGraph} to build the response.
     */
    @Query("""
            SELECT a FROM Appointment a
            WHERE a.client.id = :clientId
              AND a.idempotencyKey = :idempotencyKey
              AND a.status = com.beautica.booking.enums.BookingStatus.CONFIRMED
            """)
    Optional<Appointment> findActiveByClientIdAndIdempotencyKey(
            @Param("clientId") UUID clientId,
            @Param("idempotencyKey") String idempotencyKey);

    // ── Guest (LINK) visit cancel by link (BE-7) ──────────────────────────────
    /**
     * Resolves a guest (LINK) visit by its one-time {@code cancel_token} for the public cancel page —
     * the visit-level analogue of {@code BookingRepository#findByCancelTokenWithGraph}. Rides the V124
     * partial-unique index {@code ux_appointments_cancel_token} (UNIQUE over non-NULL rows only). A
     * consumed token is {@code NULL} (nulled by {@link #consumeCancelToken}), so a replayed link returns
     * empty. Header only — the caller reads the chained items via
     * {@code BookingRepository#findByAppointmentIdWithGraph} for the master/service labels + eviction.
     */
    @Query("SELECT a FROM Appointment a WHERE a.cancelToken = :cancelToken")
    Optional<Appointment> findByCancelToken(@Param("cancelToken") UUID cancelToken);

    /**
     * Atomically consumes a visit's cancel token: flips a still-{@code CONFIRMED} guest visit HEADER to
     * {@code CANCELLED}, stamps {@code CLIENT_CANCELLED}, and nulls the token, in a single conditional
     * UPDATE. Mirrors {@code BookingRepository#consumeCancelToken} lifted to the aggregate header: of N
     * concurrent {@code POST /cancel/{token}} requests, exactly ONE affects 1 row (the winner cancels
     * the visit's items + fires the side-effects); every other affects 0 → mapped to 404. The child
     * {@code bookings} rows are cancelled separately by {@code BookingRepository#cancelItemsByAppointmentId}.
     *
     * <p><b>{@code lock_timeout} (cycle-3 audit finding 2).</b> This writes the SAME {@code appointments}
     * header row the four whole-visit transitions lock via {@link #lockHeaderRegardlessOfStatus}, but
     * previously fused no {@code set_config('lock_timeout', '3s', true)} — the only header writer that
     * didn't, unlike {@link #lockHeaderIfConfirmed} and {@link #lockHeaderRegardlessOfStatus}. A guest
     * clicking their cancel link while a provider transition holds the header lock could park this
     * request's Hikari connection on an unbounded implicit UPDATE row-lock wait instead of failing fast
     * with the same clean 409 every other header path now gives. Fused via a {@code FROM (SELECT
     * set_config(...))} join source rather than the sibling lock methods' {@code SELECT ... FOR UPDATE}
     * shape — an {@code UPDATE} has no target-list slot to smuggle the {@code set_config} call into, so
     * the GUC is set via a 1-row join source instead; Postgres evaluates that source (and therefore the
     * {@code set_config} call) before attempting the implicit row lock the {@code UPDATE} itself takes on
     * its single matching row, identically bounding the wait to 3s. The table write order
     * ({@code appointments} then {@code bookings}, via {@code BookingRepository#cancelItemsByAppointmentId})
     * is unchanged — only the timeout was missing.
     *
     * @return the number of header rows updated — {@code 1} for the winner, {@code 0} otherwise
     */
    @Modifying
    @Query(value = """
            UPDATE appointments
               SET status = 'CANCELLED',
                   cancellation_reason = 'CLIENT_CANCELLED',
                   cancel_token = NULL
              FROM (SELECT set_config('lock_timeout', '3s', true)) AS lock_cfg
             WHERE appointments.cancel_token = :cancelToken
               AND appointments.status = 'CONFIRMED'
            """, nativeQuery = true)
    int consumeCancelToken(@Param("cancelToken") UUID cancelToken);

    // ── Pre-lock existence/ownership check (cycle-3 audit finding 1) ─────────────────────────────
    /**
     * Resolves a visit header's {@code client_id} column WITHOUT hydrating an {@link Appointment}
     * entity — the pre-lock ownership check {@code AppointmentTransitionService#cancelAppointment}
     * uses to authorize the caller BEFORE {@link #lockHeaderRegardlessOfStatus} runs.
     *
     * <p><b>Why native/scalar, not {@code findById}.</b> If this check loaded a managed
     * {@code Appointment} entity, the SUBSEQUENT {@link #lockHeaderRegardlessOfStatus} +
     * {@code AppointmentTransitionService#lockHeaderForWholeVisitTransition}'s post-lock
     * {@code findById} would return that SAME cached instance from the persistence-context identity
     * map instead of re-querying — Hibernate never overwrites an already-managed entity's fields from
     * a later query's resultset. That would silently defeat the "fresh snapshot taken AFTER the lock"
     * invariant {@code AppointmentCrossPathTransitionConcurrencyIT} depends on: a concurrently
     * committed status change (or the sibling items behind it) would go unseen by the very check that
     * exists to catch it. A scalar native query never touches the entity manager, so it cannot poison
     * the cache the later, genuinely-fresh entity load relies on.
     *
     * <p>Returns {@link Optional#empty()} for BOTH a missing appointment id AND an existing row whose
     * {@code client_id} column is {@code NULL} (a guest/LINK visit, which no CLIENT can own) — the
     * caller collapses both to the same uniform 403 (no existence oracle), matching the original
     * {@code appointment.getClient() == null} guard this replaces.
     */
    @Query(value = "SELECT client_id FROM appointments WHERE id = :appointmentId", nativeQuery = true)
    Optional<UUID> findClientIdById(@Param("appointmentId") UUID appointmentId);

    // ── Per-item header recompute (BE-4/track 27.x lost-update fix) ──────────────────────────────
    /**
     * Explicitly locks the visit HEADER row via {@code SELECT ... FOR UPDATE} if (and only if) it is
     * still {@code CONFIRMED} — a no-op read (0 rows, no lock taken) once it has already left
     * CONFIRMED. MUST be called, in the SAME transaction, before the caller writes its own child
     * row — see {@code AppointmentTransitionService#lockHeaderBeforeItemTransition} — and again
     * (in spirit; the actual lock is already held) before
     * {@link #collapseHeaderIfNoConfirmedSiblingsRemain} runs — see that method's Javadoc for why
     * this explicit lock is the load-bearing part of the fix, not a defensive extra.
     *
     * <p><b>{@code lock_timeout} (cycle-2 audit finding 2).</b> Fuses
     * {@code set_config('lock_timeout', '3s', true)} into the SAME statement/round-trip, mirroring
     * {@code BookingRepository#acquireAdvisoryLockWithTimeout}'s exact shape — this lock previously
     * had none, unlike every other lock path in this codebase, so a contended header could park a
     * Hikari connection for the full pool connection-timeout instead of failing fast with a 409. A
     * wait exceeding 3s aborts with Postgres {@code 55P03 lock_not_available}
     * ({@link org.springframework.dao.CannotAcquireLockException}), and a genuine deadlock aborts
     * with {@code 40P01} ({@link org.springframework.dao.DeadlockLoserDataAccessException}) — both
     * are {@link org.springframework.dao.PessimisticLockingFailureException} subtypes, mapped to the
     * same clean 409 by {@code GlobalExceptionHandler#handlePessimisticLockingFailure}. The
     * {@code SELECT id FROM (...) locked} outer wrapper exists only so the native query's scalar
     * result type stays a single {@code id} column despite the inner query's {@code set_config}
     * expression sharing its target list — the {@code set_config} call is guaranteed to run before
     * the inner query's {@code FOR UPDATE} lock attempt (verified against a real Postgres 16 before
     * landing this version: a concurrent holder of the row reliably aborts this statement at ~3.0s,
     * not the un-timed-out default — {@code lock_timeout} defaults to {@code 0}/disabled).
     *
     * @return the header's id if it was CONFIRMED and is now locked by this transaction, empty if it
     *         had already left CONFIRMED (nothing to lock, nothing to collapse)
     */
    @Query(value = """
            SELECT id FROM (
                SELECT set_config('lock_timeout', '3s', true) AS lock_cfg, a.id AS id
                  FROM appointments a
                 WHERE a.id = :appointmentId AND a.status = 'CONFIRMED'
                 FOR UPDATE
            ) locked
            """, nativeQuery = true)
    Optional<UUID> lockHeaderIfConfirmed(@Param("appointmentId") UUID appointmentId);

    /**
     * Unconditionally locks the visit HEADER row via {@code SELECT ... FOR UPDATE}, regardless of
     * its status — the status-agnostic counterpart of {@link #lockHeaderIfConfirmed}, used by the
     * four WHOLE-VISIT transition methods ({@code AppointmentTransitionService#cancelAppointment} /
     * {@code #declineAppointment} / {@code #completeAppointment} / {@code #notCompleteAppointment})
     * AFTER each method's own unlocked authorization check has already succeeded, but before any
     * chained item is loaded or mutated (cycle-3 audit finding 1 — a cycle-2 wording error had this
     * called at the literal top of each method, BEFORE authorization, letting a non-owner force a
     * real row lock with a guessed UUID; see {@code AppointmentTransitionService
     * #lockHeaderForWholeVisitTransition}'s Javadoc for the full account).
     *
     * <p><b>Why not reuse {@link #lockHeaderIfConfirmed}.</b> That method's CONFIRMED-only
     * precondition is wrong for these callers: they must still distinguish "already terminal" (a
     * 400/409, evaluated in Java against the entity loaded right after this lock) from "does not
     * exist" (the uniform 403 collapsed with "exists but foreign" — Finding 8, no existence oracle)
     * even once the header has already left CONFIRMED, so the row must be locked and returned
     * regardless of its current status; validating status stays a Java-side concern here, not a SQL
     * precondition.
     *
     * <p><b>Lock order (cycle-2 audit finding 1 — lock-order inversion / deadlock risk).</b>
     * Establishes the canonical {@code appointments}-row-before-{@code bookings}-rows lock order for
     * the whole-visit family: the per-item paths ({@code BookingService#cancelBooking},
     * {@code AppointmentTransitionService#declineAppointmentItem}) already lock the header before
     * writing their own single child row (via {@link #lockHeaderIfConfirmed}); this method gives the
     * whole-visit callers the identical ordering guarantee, taken explicitly and eagerly — a real,
     * immediately-executed statement — rather than relying on Hibernate's {@code order_updates}
     * flush ordering (transaction-scoped, sorts within a table, not coordinated across tables with
     * this native lock) to happen to emit the header UPDATE before the item UPDATEs at commit-time
     * flush.
     *
     * <p>{@code lock_timeout} fused exactly as {@link #lockHeaderIfConfirmed} — see that method's
     * Javadoc for the full rationale and the empirical verification.
     *
     * @return the header's id if it exists (and is now locked by this transaction), empty if the
     *         appointment id does not exist at all — the caller maps that to the SAME uniform 403 an
     *         existing-but-foreign visit gets
     */
    @Query(value = """
            SELECT id FROM (
                SELECT set_config('lock_timeout', '3s', true) AS lock_cfg, a.id AS id
                  FROM appointments a
                 WHERE a.id = :appointmentId
                 FOR UPDATE
            ) locked
            """, nativeQuery = true)
    Optional<UUID> lockHeaderRegardlessOfStatus(@Param("appointmentId") UUID appointmentId);

    /**
     * Collapses the visit HEADER to a terminal state in ONE conditional {@code UPDATE} — the
     * per-item counterpart of {@link #consumeCancelToken}, applied to BOTH callers that recompute the
     * header after a single chained item transitions (provider per-service decline AND the track
     * 27.x client per-leg cancel): {@code AppointmentTransitionService
     * #collapseHeaderAfterItemTransition} (both the internal provider-decline caller and the
     * package-private client-cancel facade, {@code #collapseAppointmentHeaderAfterClientItemCancel},
     * delegate here). Callers MUST hold the lock from {@link #lockHeaderIfConfirmed} — acquired
     * earlier in the SAME transaction, now BEFORE the caller's own child-row write (cycle-2 audit
     * finding 1) rather than immediately before this method — see "Concurrency" below for why the
     * lock is not optional.
     *
     * <p><b>Replaces a read-then-conditionally-write pair</b> that was a genuine lost-update race
     * under Postgres READ COMMITTED: the old code ran {@code SELECT} the sibling items, checked
     * "does any remain CONFIRMED" in Java, then conditionally {@code UPDATE}d the header in a second
     * statement. Two legs of the SAME visit transitioned truly concurrently could each run their
     * {@code SELECT} before either transaction committed the other's sibling write, so BOTH observed
     * the other sibling as still {@code CONFIRMED} and neither flipped the header — it stranded at
     * {@code CONFIRMED} with zero {@code CONFIRMED} children, permanently (no later event re-triggers
     * the recompute).
     *
     * <p><b>Concurrency — why {@link #lockHeaderIfConfirmed} is required, not a bare conditional
     * UPDATE.</b> The obvious-looking fix — folding {@code NOT EXISTS (SELECT 1 FROM bookings ...)}
     * straight into THIS statement's {@code WHERE} clause with no separate lock step — was tried and
     * measured NOT to close the race (verified against a real Postgres 16 harness before landing this
     * version): Postgres only takes a row lock on a candidate row once the row has PASSED the full
     * {@code WHERE} clause; a row whose {@code NOT EXISTS} subquery reads as false (because, under
     * each transaction's own READ COMMITTED snapshot, the OTHER leg's sibling row is still
     * {@code CONFIRMED} — neither has committed yet) is never locked at all, so two concurrent
     * {@code UPDATE}s can each independently and "correctly" (per their own snapshot) conclude "a
     * sibling remains" and both no-op — no blocking, no serialization, header stranded exactly as
     * before. {@code SELECT ... FOR UPDATE} on the header FIRST is what actually creates the
     * serialization point: it forces the second transaction to BLOCK until the first commits: once
     * unblocked, THIS statement runs as a fresh READ COMMITTED statement with a fresh snapshot that
     * correctly reflects the first transaction's now-committed sibling write, so the second
     * transaction's {@code NOT EXISTS} check is now evaluated against up-to-date data. (Postgres's
     * per-row {@code EvalPlanQual} recheck on a blocked {@code UPDATE} re-fetches only the
     * TARGET row itself — it does not reliably refresh an unrelated table's subquery baked into the
     * same statement's {@code WHERE}/{@code SET}, which is exactly why folding the check into one
     * statement without a preceding lock is insufficient here, unlike {@link #consumeCancelToken}'s
     * single self-contained-row condition, which needs no separate lock.)
     *
     * <p><b>Also removes the over-fetch</b> the old code paid on every call: it no longer loads the
     * visit's chained {@code Booking} rows — with their 5-way {@code JOIN FETCH} graph (master,
     * master.user, master.salon, masterService, serviceDefinition) — purely to read {@code
     * .getStatus()} on each. The "any sibling still CONFIRMED" predicate now lives entirely in SQL.
     *
     * <p><b>Naturally idempotent.</b> The {@code status = 'CONFIRMED'} precondition means a replay
     * (header already collapsed by the winning call) is a harmless 0-row {@code UPDATE}, not an
     * error — no distinct "already handled" branch is needed by the caller.
     *
     * <p><b>{@code note} is written unconditionally alongside {@code status}/{@code reason} in the
     * SAME statement</b>, so {@code chk_appointment_client_cancellation_note_status} (V124: the note
     * column is legal only when {@code status = 'CANCELLED'}) can never observe a partial write —
     * Postgres evaluates row {@code CHECK} constraints against a row's values AFTER the whole
     * {@code UPDATE} statement is applied, never against an intermediate per-column state. Callers
     * that never write a header note (the provider per-service decline path — its note lives on the
     * declined CHILD row, never the header) pass {@code note = null}, which trivially satisfies the
     * constraint regardless of the resulting status.
     *
     * <p>Native SQL, not JPQL: {@code target}/{@code reason} are passed as their enum {@code .name()}
     * strings (matching the {@code VARCHAR} columns via {@code @Enumerated(STRING)}) so there is no
     * ambiguity in how a bare Java enum would otherwise be bound to a native query parameter (Hibernate
     * has no {@code @Enumerated} metadata to consult outside an entity attribute path).
     *
     * <p>{@code flushAutomatically = true} flushes any pending change on the just-transitioned CHILD
     * row (persisted moments earlier in the SAME transaction via {@code bookingRepository.save(...)})
     * BEFORE this statement runs — without it the {@code NOT EXISTS} subquery below (plain SQL
     * against the DB, bypassing the Hibernate session) could still see the child's PRE-transition
     * status and wrongly conclude a CONFIRMED sibling remains. {@code clearAutomatically = true}
     * detaches the persistence context afterwards so an {@code Appointment} entity a caller loaded
     * EARLIER in the same transaction (e.g. {@code declineAppointmentItem}'s header load for
     * authz/target-lookup) cannot be left silently stale relative to the row this statement just
     * changed.
     *
     * @return 1 if this call collapsed the header, 0 if a CONFIRMED sibling remains or the header had
     *         already left CONFIRMED (harmless replay)
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE appointments
               SET status = :target,
                   cancellation_reason = :reason,
                   client_cancellation_note = :note
             WHERE id = :appointmentId
               AND status = 'CONFIRMED'
               AND NOT EXISTS (
                   SELECT 1 FROM bookings b
                    WHERE b.appointment_id = :appointmentId
                      AND b.status = 'CONFIRMED'
               )
            """, nativeQuery = true)
    int collapseHeaderIfNoConfirmedSiblingsRemain(
            @Param("appointmentId") UUID appointmentId,
            @Param("target") String target,
            @Param("reason") String reason,
            @Param("note") String note);
}
