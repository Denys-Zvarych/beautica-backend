package com.beautica.salon.repository;

import com.beautica.auth.Role;
import com.beautica.user.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Read-only audit repository backing the salon-deletion safety precondition (Phase 289) — see
 * {@code docs/backend-phases/phase-289-staff-as-client-safety-audit.md} D1 for why this is a
 * dedicated repository rather than a widening of {@code BookingRepository}, {@code
 * ReviewRepository}, {@code ClientReviewRepository}, or {@code UserRepository}.
 *
 * <p>Parameterised over {@link User} because the audit's subject is the user account, not any one
 * of the three tables it reads from. Spring Data does not constrain a {@code @Query}'s root to the
 * interface's entity type (the same reasoning {@code ClientAggregationRepository} documents for its
 * own cross-aggregate reads) — each method below queries a DIFFERENT table
 * ({@code bookings}, {@code reviews}, {@code client_reviews}) for the same shape of answer: which
 * {@code SALON_MASTER}/{@code SALON_ADMIN} users are referenced as that table's client, and how
 * many rows.
 *
 * <h3>Two callers, two different scopes — do not conflate them (2026 perf audit)</h3>
 *
 * This audit has exactly two callers with opposite performance requirements, and this repository
 * now serves both explicitly instead of forcing one shape onto both:
 *
 * <ol>
 *   <li><b>Platform-wide, offline/one-off.</b> {@code findBookingClientViolations},
 *       {@code findReviewClientViolations}, {@code findClientReviewSubjectViolations} — a
 *       system-wide sweep run manually/offline to discover whether a violating row already exists
 *       ANYWHERE (this repo has precedent for seed/fixture scripts writing unguarded data). Slow
 *       is fine here — nothing waits on it. <b>Never call these three from a request path</b> —
 *       in particular, never from the per-salon-delete precondition Phase 290 wires up. A
 *       platform-wide sweep run per delete would force a full scan of `bookings`/`reviews`/
 *       `client_reviews` for every deletion, unscoped to the salon actually being deleted.</li>
 *   <li><b>Per-delete precondition, salon-scoped.</b> {@code findSalonStaffUserIds} plus
 *       {@code findBookingClientViolationsForSalon}, {@code findReviewClientViolationsForSalon},
 *       {@code findClientReviewSubjectViolationsForSalon} — scoped to one salon's own staff via
 *       {@code masters.salon_id} (for {@code SALON_MASTER}, the authoritative per-master salon
 *       association) and {@code users.salon_id} (for {@code SALON_ADMIN}, which has no
 *       {@code masters} row at all). This is what Phase 290's {@code DELETE /salons/{salonId}}
 *       guard must call — it pays only for the salon being deleted, never the whole platform.
 *       See the phase doc's {@code ## Decisions} for why a single unscoped query cannot serve
 *       both callers: measured against a 100k-row local `bookings` table, the platform query
 *       necessarily Seq Scans `bookings` (there is no salon predicate to index against), which is
 *       acceptable for an offline sweep and unacceptable inside a user-facing DELETE.</li>
 * </ol>
 *
 * <p>The three {@code *ForSalon} finders take an already-resolved {@code staffUserIds} list
 * (from {@link #findSalonStaffUserIds}) rather than re-deriving it per call — resolving it once
 * and reusing it across all three lets each finder drive off `client_id`/`subject_client_id`'s
 * existing index (`idx_bookings_client_starts_at` etc.) via a plain {@code IN} predicate, instead
 * of forcing the planner to correlate a per-salon subquery against every candidate row. The
 * {@code roles} predicate is kept on the {@code *ForSalon} finders too, even though every id in
 * {@code staffUserIds} is already role-eligible by construction — cheap defense in depth against
 * a future caller passing a stale or wrongly-scoped id list.
 */
public interface StaffClientReferenceAuditRepository extends JpaRepository<User, UUID> {

    // ── Platform-wide (offline/one-off only — see class javadoc) ──────────────────────────────

    /**
     * {@code bookings.client_id} referencing a staff-role user, ACROSS THE WHOLE PLATFORM. {@code
     * b.client IS NOT NULL} excludes guest (LINK) bookings, which always have a null
     * {@code client_id} (V89 {@code chk_bookings_guest_fields}) and can never be a violation.
     *
     * <p><b>Offline/one-off only — never call this from a request path.</b> Unscoped by salon: on
     * a 100k-row local `bookings` table this plans as a Seq Scan on `bookings` (~4,100 buffer
     * reads, ~26-100ms depending on cache warmth) joined against a Seq Scan on `users` filtered by
     * role. Acceptable for a manual/offline sweep; use {@link #findBookingClientViolationsForSalon}
     * for the per-delete precondition.
     */
    @Query("""
            SELECT b.client.id AS userId, b.client.role AS role, COUNT(b) AS rowCount
            FROM Booking b
            WHERE b.client IS NOT NULL AND b.client.role IN :roles
            GROUP BY b.client.id, b.client.role
            """)
    List<StaffClientReferenceRowProjection> findBookingClientViolations(@Param("roles") List<Role> roles);

    /**
     * {@code reviews.client_id} referencing a staff-role user, ACROSS THE WHOLE PLATFORM. Column
     * is {@code NOT NULL}.
     *
     * <p><b>Offline/one-off only — never call this from a request path.</b> See
     * {@link #findBookingClientViolations}'s javadoc for the full two-caller rationale; use
     * {@link #findReviewClientViolationsForSalon} for the per-delete precondition.
     */
    @Query("""
            SELECT r.client.id AS userId, r.client.role AS role, COUNT(r) AS rowCount
            FROM Review r
            WHERE r.client.role IN :roles
            GROUP BY r.client.id, r.client.role
            """)
    List<StaffClientReferenceRowProjection> findReviewClientViolations(@Param("roles") List<Role> roles);

    /**
     * {@code client_reviews.subject_client_id} referencing a staff-role user, ACROSS THE WHOLE
     * PLATFORM. Column is {@code NOT NULL}.
     *
     * <p><b>Offline/one-off only — never call this from a request path.</b> See
     * {@link #findBookingClientViolations}'s javadoc for the full two-caller rationale; use
     * {@link #findClientReviewSubjectViolationsForSalon} for the per-delete precondition.
     */
    @Query("""
            SELECT cr.subjectClient.id AS userId, cr.subjectClient.role AS role, COUNT(cr) AS rowCount
            FROM ClientReview cr
            WHERE cr.subjectClient.role IN :roles
            GROUP BY cr.subjectClient.id, cr.subjectClient.role
            """)
    List<StaffClientReferenceRowProjection> findClientReviewSubjectViolations(@Param("roles") List<Role> roles);

    // ── Salon-scoped (the per-delete precondition — Phase 290's future caller) ────────────────

    /**
     * Every {@code SALON_MASTER}/{@code SALON_ADMIN} user id belonging to one salon's staff —
     * the id list the three {@code *ForSalon} finders below scope on.
     *
     * <p>Two different tables back the two roles, because that is what the schema actually
     * supports for each: a {@code SALON_MASTER} always has exactly one {@code masters} row
     * (unique {@code user_id}), and {@code masters.salon_id} is that master's authoritative salon
     * — set once at {@code createMasterFromInvite} and never reassigned. A {@code SALON_ADMIN}
     * has NO {@code masters} row at all; {@code users.salon_id} is the only place their salon
     * assignment lives, and it IS mutated (see {@code SalonService} transfer/detach). Reading the
     * wrong table for either role would silently miss real staff or pick up a stale assignment.
     *
     * <p>Native, not JPQL: a plain {@code UNION} of two single-column selects. Standard JPQL
     * (through JPA 3.1 / Hibernate's supported subset) does not admit {@code UNION} inside a
     * scalar subquery/IN-list position, only as a top-level query — a native query is the direct,
     * unambiguous way to express this rather than working around the grammar with two round trips
     * merged in Java.
     *
     * <p><b>{@code m.master_type = 'SALON_MASTER'} on the first arm is load-bearing (Phase 290
     * fix).</b> A salon can also hold a {@code SALON_OWNER}-type {@code masters} row for its own
     * owner ("I also work as a master", track 12.x) sharing the exact same {@code salon_id}. That
     * row's {@code user_id} is the OWNER, whose {@code users.role} is {@code SALON_OWNER} — not
     * staff by any definition this method's own name and javadoc promise. Without this filter the
     * first arm returned every master row for the salon regardless of type, so an owner-as-master
     * salon's own owner leaked into "the salon's staff" here. Phase 289's own audit correctness
     * was unaffected (the three {@code *ForSalon} finders independently re-filter
     * {@code u.role IN :roles}, so a leaked owner id could never produce a false violation), but
     * Phase 290's staff-deactivation cascade calls this method DIRECTLY to decide which
     * {@code users} rows to deactivate — an unfiltered owner id here would have deactivated the
     * salon's own owner account, violating the locked owner-exemption rule. Caught by
     * {@code SalonStaffHardDeleteIT.should_leaveOwnerAccountIntact_when_salonDeleted}.
     *
     * <p><b>{@code m.user_id IS NOT NULL} is load-bearing since V157 (phase 294/295).</b>
     * {@code masters.user_id} became nullable so a DETACHED master — one whose staff account the
     * phase 295 cascade already hard-deleted — can survive as a name-only stub while still
     * carrying its original {@code salon_id}. Without this predicate the first arm would return a
     * {@code NULL} "staff user id" for every such stub, which then flows into
     * {@code userRepository.findAllById(...)}, the {@code IN (:staffUserIds)} arms of the three
     * audit finders, and the phase 295 delete itself. Not reachable through the cascade today
     * (its own {@code !salon.isActive()} idempotency guard returns before a second pass), which is
     * precisely why it must be stated here rather than left to that guard to enforce at a
     * distance.
     *
     * <p><b>The {@code users.role = 'SALON_MASTER'} join on the first arm (phase 295 audit,
     * MEDIUM-5).</b> The three {@code *ForSalon} finders below each re-assert
     * {@code role IN :roles} and this class's own javadoc calls that "cheap defense in depth
     * against a future caller passing a stale or wrongly-scoped id list" — yet the ONE caller that
     * irreversibly {@code DELETE}s the rows this method names,
     * {@code SalonService#deleteSalonStaff}, ran without any such re-assert. The first arm alone
     * decided a hard delete from {@code masters.salon_id} + {@code masters.master_type} +
     * {@code user_id IS NOT NULL}, never touching {@code users.role}. A {@code SALON_MASTER}-typed
     * masters row whose user is in fact a {@code SALON_OWNER} — reachable through a hand-written
     * fixture, a seed script, or any future role transition that does not rewrite
     * {@code master_type} — would have taken that owner's account with it. The join makes the
     * destructive path's scoping identical to the read paths' rather than weaker than them.
     */
    @Query(value = """
            SELECT m.user_id AS user_id
            FROM masters m
            JOIN users u ON u.id = m.user_id AND u.role = 'SALON_MASTER'
            WHERE m.salon_id = :salonId
              AND m.master_type = 'SALON_MASTER'
              AND m.user_id IS NOT NULL
            UNION
            SELECT u.id AS user_id
            FROM users u
            WHERE u.role = 'SALON_ADMIN' AND u.salon_id = :salonId
            """, nativeQuery = true)
    List<UUID> findSalonStaffUserIds(@Param("salonId") UUID salonId);

    /**
     * {@code bookings.client_id} referencing one of {@code staffUserIds}. Scoped via a plain
     * {@code IN} predicate on {@code client_id} so the planner drives off
     * {@code idx_bookings_client_starts_at}/{@code idx_bookings_client_status_starts_at} (both
     * lead with {@code client_id}) instead of Seq Scanning `bookings` — verified via
     * {@code EXPLAIN (ANALYZE, BUFFERS)} against a 100k-row local dataset (see the phase doc).
     * Caller MUST NOT invoke this with an empty {@code staffUserIds} — a salon with no staff has
     * no violation to find and should skip the call entirely (see
     * {@code StaffClientReferenceAuditService#runAuditForSalon}).
     */
    @Query("""
            SELECT b.client.id AS userId, b.client.role AS role, COUNT(b) AS rowCount
            FROM Booking b
            WHERE b.client IS NOT NULL
              AND b.client.role IN :roles
              AND b.client.id IN :staffUserIds
            GROUP BY b.client.id, b.client.role
            """)
    List<StaffClientReferenceRowProjection> findBookingClientViolationsForSalon(
            @Param("roles") List<Role> roles, @Param("staffUserIds") List<UUID> staffUserIds);

    /**
     * {@code reviews.client_id} referencing one of {@code staffUserIds}. See
     * {@link #findBookingClientViolationsForSalon}'s javadoc for the scoping/index rationale and
     * the empty-list caller contract.
     */
    @Query("""
            SELECT r.client.id AS userId, r.client.role AS role, COUNT(r) AS rowCount
            FROM Review r
            WHERE r.client.role IN :roles
              AND r.client.id IN :staffUserIds
            GROUP BY r.client.id, r.client.role
            """)
    List<StaffClientReferenceRowProjection> findReviewClientViolationsForSalon(
            @Param("roles") List<Role> roles, @Param("staffUserIds") List<UUID> staffUserIds);

    /**
     * {@code client_reviews.subject_client_id} referencing one of {@code staffUserIds}. See
     * {@link #findBookingClientViolationsForSalon}'s javadoc for the scoping/index rationale and
     * the empty-list caller contract.
     */
    @Query("""
            SELECT cr.subjectClient.id AS userId, cr.subjectClient.role AS role, COUNT(cr) AS rowCount
            FROM ClientReview cr
            WHERE cr.subjectClient.role IN :roles
              AND cr.subjectClient.id IN :staffUserIds
            GROUP BY cr.subjectClient.id, cr.subjectClient.role
            """)
    List<StaffClientReferenceRowProjection> findClientReviewSubjectViolationsForSalon(
            @Param("roles") List<Role> roles, @Param("staffUserIds") List<UUID> staffUserIds);

    /**
     * {@code appointments.client_id} referencing one of {@code staffUserIds} — the FOURTH
     * reference site, added by the phase 295 audit (LOW-8). Same fail-closed contract, same salon
     * scoping and the same empty-list caller precondition as the three finders above; see
     * {@link #findBookingClientViolationsForSalon} for the shared rationale.
     *
     * <p><b>Why it is not redundant with the {@code bookings} finder.</b>
     * {@code appointments.client_id} is nullable and {@code NO ACTION} (V124/V139) — the same
     * shape as {@code bookings.client_id}, and just as capable of blocking
     * {@code DELETE FROM users}. Phase 289 checked only three tables, so an appointment header
     * whose {@code client_id} is a staff user with NO sibling {@code bookings} row carrying the
     * same id slipped past the audit entirely and turned the deliberate fail-closed 409
     * ({@code SalonDeletionBlockedException}) into an FK-violation 500 out of
     * {@code SalonService#deleteSalonStaff}'s {@code deleteAllByIdInBatch}. A multi-service visit
     * normally has both rows, but nothing in the schema requires it.
     *
     * <p>{@code a.client IS NOT NULL} excludes guest (LINK) and staff walk-in visits, which always
     * carry a null {@code client_id} (V126 / V139's CHECKs) and can never be a violation —
     * mirroring {@link #findBookingClientViolations}'s guard on the same column shape.
     *
     * <p>No platform-wide sibling is added: the offline sweep's three queries exist to discover
     * pre-existing violations before the cascade shipped, and are run manually. This finder serves
     * the per-delete precondition, which is the path that fails closed.
     */
    @Query("""
            SELECT a.client.id AS userId, a.client.role AS role, COUNT(a) AS rowCount
            FROM Appointment a
            WHERE a.client IS NOT NULL
              AND a.client.role IN :roles
              AND a.client.id IN :staffUserIds
            GROUP BY a.client.id, a.client.role
            """)
    List<StaffClientReferenceRowProjection> findAppointmentClientViolationsForSalon(
            @Param("roles") List<Role> roles, @Param("staffUserIds") List<UUID> staffUserIds);
}
