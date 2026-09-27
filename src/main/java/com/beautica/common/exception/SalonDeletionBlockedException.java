package com.beautica.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown by {@code SalonService.deactivateSalon} (Phase 290) when Phase 289's salon-scoped
 * staff-as-client safety audit ({@code StaffClientReferenceAuditService#runAuditForSalon})
 * reports {@code AuditOutcome.VIOLATIONS_FOUND} for the salon being deleted.
 *
 * <p>Fail-closed: the whole point of wiring the audit in as a precondition is that a violation
 * ABORTS the deletion before any mutation runs, rather than scrubbing a real client's booking or
 * review history alongside the staff account that happens to also be referenced as its client
 * (see the phase 289 doc's {@code ## Background} for how such a row could exist despite every
 * application write path rejecting it).
 *
 * <p><b>Reported as ONE incident, never per reference type.</b> A single offending booking can
 * trip {@code BOOKING_CLIENT} on its own, plus {@code REVIEW_CLIENT} and/or
 * {@code CLIENT_REVIEW_SUBJECT} on any review authored against that same booking — the violations
 * are correlated, not independent occurrences. {@link #getAffectedStaffCount()} is therefore the
 * count of DISTINCT staff user ids appearing anywhere in the audit result, not the number of
 * violation rows.
 *
 * <p>Surfaces as a {@code 409 Conflict} carrying the stable error code
 * {@code SALON_DELETION_BLOCKED} in the response body under {@code data.code}. No staff user id,
 * role, or reference type is echoed to the client — this is an operational/support signal, not a
 * self-service-resolvable one; the caller is the salon owner, who cannot fix a database-level rule
 * violation from the mobile app. Like the other flow-control exceptions translated directly to an
 * HTTP response (see {@link DuplicateServiceException}), stack-trace capture is suppressed — this
 * is an expected precondition failure, not a fault.
 */
public class SalonDeletionBlockedException extends BusinessException {

    /**
     * Stable error code echoed in the response body under {@code data.code}. The mobile client
     * maps it to localised copy directing the owner to contact support — the API never ships
     * natural-language messages for client routing.
     */
    public static final String ERROR_CODE = "SALON_DELETION_BLOCKED";

    private final int affectedStaffCount;

    public SalonDeletionBlockedException(int affectedStaffCount) {
        super(HttpStatus.CONFLICT,
                "Salon cannot be deleted: the staff-as-client safety audit found a rule violation "
                        + "that must be resolved before deletion");
        this.affectedStaffCount = affectedStaffCount;
    }

    /** Count of DISTINCT staff user ids implicated — never a per-violation-row count. */
    public int getAffectedStaffCount() {
        return affectedStaffCount;
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
