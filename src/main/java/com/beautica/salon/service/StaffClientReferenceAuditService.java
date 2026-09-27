package com.beautica.salon.service;

import com.beautica.auth.Role;
import com.beautica.salon.audit.StaffClientReferenceAuditResult;
import com.beautica.salon.audit.StaffClientReferenceType;
import com.beautica.salon.audit.StaffClientReferenceViolation;
import com.beautica.salon.repository.StaffClientReferenceAuditRepository;
import com.beautica.salon.repository.StaffClientReferenceRowProjection;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Runs the salon-deletion safety audit (Phase 289) — see
 * {@code docs/backend-phases/phase-289-staff-as-client-safety-audit.md}.
 *
 * <p>Read-only, and deliberately not wired into {@code SalonService.deactivateSalon} in this
 * phase — that fail-closed guard is Phase 290's job, once the destructive scrub it protects
 * actually exists. This service only proves the capability and is exercised directly by its own
 * integration tests.
 *
 * <p>Never catches or swallows an exception from {@link StaffClientReferenceAuditRepository} — a
 * failed query must propagate rather than silently degrade into a falsely "clean"
 * {@link StaffClientReferenceAuditResult}. See that result type's javadoc for the full "clean vs.
 * not run" contract.
 *
 * <h3>Three callers — {@link #runAudit()}, {@link #runAuditForSalon(UUID)}, {@link
 * #runAuditForStaffUserIds(List)}</h3>
 *
 * {@link #runAudit()} is the platform-wide, offline/one-off sweep: run manually to discover
 * whether a violating row already exists ANYWHERE, before Phase 290's cascade exists at all.
 * Slow is fine — nothing waits on it, and it must never be called from a request path.
 *
 * <p>{@link #runAuditForStaffUserIds(List)} is the actual query body, scoped to an explicit staff
 * user id list (Phase 297 D4). {@link #runAuditForSalon(UUID)} is a thin resolve-then-delegate
 * wrapper over it for {@code DELETE /salons/{salonId}} (Phase 290), scoped to exactly the salon
 * being deleted; {@code SalonService#removeMaster} (Phase 297) calls {@link
 * #runAuditForStaffUserIds(List)} directly with a one-element list, since a salon-wide resolve
 * would audit staff the single-master removal never touches. See
 * {@link StaffClientReferenceAuditRepository}'s class javadoc for the full rationale and the
 * measured {@code EXPLAIN (ANALYZE, BUFFERS)} evidence for why the platform-wide and per-salon
 * shapes cannot share one query shape.
 */
@Service
@RequiredArgsConstructor
public class StaffClientReferenceAuditService {

    /** The only roles this audit is concerned with — see the phase doc's `## Background`. */
    private static final List<Role> AUDITED_STAFF_ROLES = List.of(Role.SALON_MASTER, Role.SALON_ADMIN);

    private final StaffClientReferenceAuditRepository auditRepository;
    private final Clock clock;

    /**
     * Platform-wide, offline/one-off sweep — see class javadoc. Never call this from a request
     * path; use {@link #runAuditForSalon(UUID)} for that.
     *
     * <p>{@code @Transactional(readOnly = true)} wraps all three repository calls in one
     * transaction (same shape as {@code ClientPassportService.getPassport}/{@code getTimeline},
     * {@code ClientPassportService.java:119,194}) — without it each call ran in its own
     * transaction, so a write racing between call 1 and call 3 could yield a torn, internally
     * inconsistent snapshot. A precondition that can read a torn snapshot is worse than one that
     * has not run at all.
     */
    @Transactional(readOnly = true)
    public StaffClientReferenceAuditResult runAudit() {
        List<StaffClientReferenceViolation> violations = new ArrayList<>();

        appendViolations(
                violations,
                auditRepository.findBookingClientViolations(AUDITED_STAFF_ROLES),
                StaffClientReferenceType.BOOKING_CLIENT);
        appendViolations(
                violations,
                auditRepository.findReviewClientViolations(AUDITED_STAFF_ROLES),
                StaffClientReferenceType.REVIEW_CLIENT);
        appendViolations(
                violations,
                auditRepository.findClientReviewSubjectViolations(AUDITED_STAFF_ROLES),
                StaffClientReferenceType.CLIENT_REVIEW_SUBJECT);

        return StaffClientReferenceAuditResult.of(violations, clock.instant());
    }

    /**
     * Per-delete precondition — scoped to {@code salonId}'s own staff only. Phase 290's
     * {@code DELETE /salons/{salonId}} guard is the intended caller.
     *
     * <p>Resolves the salon's staff user ids and delegates to {@link
     * #runAuditForStaffUserIds(List)} (Phase 297 D4 extraction) — see that method's javadoc for
     * the rest of the contract, including the empty-list short-circuit.
     */
    @Transactional(readOnly = true)
    public StaffClientReferenceAuditResult runAuditForSalon(UUID salonId) {
        return runAuditForStaffUserIds(auditRepository.findSalonStaffUserIds(salonId));
    }

    /**
     * The same fail-closed client-reference audit as {@link #runAuditForSalon(UUID)}, run against
     * an explicit staff user id list rather than resolving one from a salon (Phase 297 D4).
     *
     * <p>{@code runAuditForSalon(salonId)} resolves *every* staff user of the salon, which is the
     * wrong shape for {@code SalonService#removeMaster} — that endpoint removes ONE master, and
     * auditing the whole salon's staff would abort a legitimate single-master removal because some
     * OTHER master at the salon has a stray client row. Extracted here so the query bodies below
     * are written once and both the bulk (salon-wide) and single-master callers reuse them; the
     * four {@link StaffClientReferenceAuditRepository} finders already accept a {@code
     * staffUserIds} list, so this is a seam extraction, not a new query.
     *
     * <p>Resolves the given ids ONCE and reuses that list across all four finder queries, rather
     * than re-deriving a per-salon join inside each one — see {@link
     * StaffClientReferenceAuditRepository}'s javadoc for the measured reason (a correlated
     * per-row subquery cannot be pushed down onto `bookings`' client-id index; a pre-resolved
     * {@code IN} list can). An empty list short-circuits to an empty, clean result without issuing
     * any of the four finder queries — an empty {@code IN} list has no violation to find, and
     * skipping the calls avoids relying on how Hibernate happens to translate an empty collection
     * bind.
     *
     * <p>Same {@code @Transactional(readOnly = true)} torn-snapshot rationale as {@link
     * #runAudit()} — all reads (the four finders) share one transaction.
     */
    @Transactional(readOnly = true)
    public StaffClientReferenceAuditResult runAuditForStaffUserIds(List<UUID> staffUserIds) {
        if (staffUserIds.isEmpty()) {
            return StaffClientReferenceAuditResult.of(List.of(), clock.instant());
        }

        List<StaffClientReferenceViolation> violations = new ArrayList<>();

        appendViolations(
                violations,
                auditRepository.findBookingClientViolationsForSalon(AUDITED_STAFF_ROLES, staffUserIds),
                StaffClientReferenceType.BOOKING_CLIENT);
        appendViolations(
                violations,
                auditRepository.findReviewClientViolationsForSalon(AUDITED_STAFF_ROLES, staffUserIds),
                StaffClientReferenceType.REVIEW_CLIENT);
        appendViolations(
                violations,
                auditRepository.findClientReviewSubjectViolationsForSalon(AUDITED_STAFF_ROLES, staffUserIds),
                StaffClientReferenceType.CLIENT_REVIEW_SUBJECT);
        // Fourth reference site (phase 295 audit, LOW-8). appointments.client_id is nullable and
        // NO ACTION exactly like bookings.client_id, so an appointment header naming a staff user
        // with no sibling booking row of the same client blocks DELETE FROM users just as hard —
        // and, unchecked, converted this guard's deliberate 409 into an FK-violation 500 further
        // down the cascade. Salon-scoped only; the platform-wide sweep above keeps its three arms.
        appendViolations(
                violations,
                auditRepository.findAppointmentClientViolationsForSalon(AUDITED_STAFF_ROLES, staffUserIds),
                StaffClientReferenceType.APPOINTMENT_CLIENT);

        return StaffClientReferenceAuditResult.of(violations, clock.instant());
    }

    /**
     * Resolves {@code salonId}'s staff user ids (Phase 290) — the exact same resolution
     * {@link #runAuditForSalon(UUID)} uses internally
     * ({@link StaffClientReferenceAuditRepository#findSalonStaffUserIds}), exposed separately so
     * the salon-deletion staff-deactivation cascade can determine WHICH users to deactivate
     * without re-deriving the query. Two independent calls into the same repository method within
     * one {@code deactivateSalon} transaction (one via {@link #runAuditForSalon(UUID)}, one via
     * this method) rather than widening {@link StaffClientReferenceAuditResult} to also carry the
     * id list — that result type's whole contract (see its javadoc) is "clean vs. violations
     * found", and stuffing an unrelated id list onto it for one caller's convenience would blur
     * that.
     *
     * <p>{@code SALON_MASTER} via {@code masters.salon_id}, {@code SALON_ADMIN} via
     * {@code users.salon_id} — never the salon's owner, whose role is {@code SALON_OWNER} and so
     * never satisfies either predicate. This is what makes the owner-account exemption (Phase 290
     * D3) structural rather than a branch the caller has to remember: the id list handed back here
     * cannot contain the owner's own user id.
     *
     * <p>No {@code @Transactional} here — this is a plain read with no torn-snapshot risk on its
     * own, and its caller ({@code SalonService.deactivateSalon}) already runs inside a wider
     * {@code @Transactional} boundary that this call joins.
     */
    public List<UUID> resolveSalonStaffUserIds(UUID salonId) {
        return auditRepository.findSalonStaffUserIds(salonId);
    }

    private void appendViolations(
            List<StaffClientReferenceViolation> target,
            List<StaffClientReferenceRowProjection> rows,
            StaffClientReferenceType referenceType) {
        for (StaffClientReferenceRowProjection row : rows) {
            target.add(new StaffClientReferenceViolation(
                    row.getUserId(), row.getRole(), referenceType, row.getRowCount()));
        }
    }
}
