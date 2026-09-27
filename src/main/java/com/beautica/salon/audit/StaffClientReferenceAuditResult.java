package com.beautica.salon.audit;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Structured outcome of one {@code StaffClientReferenceAuditService} run (Phase 289) — the
 * precondition the future salon-deletion cascade (Phase 290+) will gate on.
 *
 * <p><b>"Clean" and "not run" are structurally impossible to conflate.</b> The only public
 * construction path is {@link #of(List, Instant)}; there is no public no-args or default-value
 * path, so an instance of this record can only exist once the three underlying queries actually
 * ran and their rows were folded in. The compact constructor enforces
 * {@code outcome == CLEAN ⇔ violations.isEmpty()}, so the two fields can never disagree — there is
 * no way to hand-construct a {@code CLEAN} result that secretly carries violations, or vice versa.
 * A caller that never received a result at all (an exception propagated, or the service was never
 * invoked) has no instance to inspect — "not run" is the ABSENCE of this object, never a
 * zero-value instance of it. {@code StaffClientReferenceAuditService} never catches or swallows an
 * exception from its repository calls; a failed query propagates instead of silently degrading to
 * a falsely "clean" result.
 *
 * <p>Read {@link #outcome()}, never {@code violations().isEmpty()} — see {@link AuditOutcome}'s
 * javadoc for why the explicit enum exists at all.
 */
public record StaffClientReferenceAuditResult(
        AuditOutcome outcome,
        List<StaffClientReferenceViolation> violations,
        Instant ranAt) {

    public StaffClientReferenceAuditResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(violations, "violations");
        Objects.requireNonNull(ranAt, "ranAt");
        violations = List.copyOf(violations);
        boolean clean = violations.isEmpty();
        if ((outcome == AuditOutcome.CLEAN) != clean) {
            throw new IllegalStateException(
                    "AuditOutcome.%s is inconsistent with %d violation(s) — this indicates a bug in the caller building this result, not a legitimate audit outcome"
                            .formatted(outcome, violations.size()));
        }
    }

    /**
     * The only public way to build a result. {@code violations} must be the COMPLETE set found by
     * one audit run (never a partial list assembled across separate calls) — {@link #ranAt} is
     * stamped once, for the run as a whole.
     */
    public static StaffClientReferenceAuditResult of(
            List<StaffClientReferenceViolation> violations, Instant ranAt) {
        List<StaffClientReferenceViolation> copy = List.copyOf(violations);
        AuditOutcome outcome = copy.isEmpty() ? AuditOutcome.CLEAN : AuditOutcome.VIOLATIONS_FOUND;
        return new StaffClientReferenceAuditResult(outcome, copy, ranAt);
    }
}
