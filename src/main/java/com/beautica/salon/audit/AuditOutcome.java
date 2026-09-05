package com.beautica.salon.audit;

/**
 * The explicit, first-class signal carried by {@link StaffClientReferenceAuditResult} — a caller
 * reads {@code result.outcome() == AuditOutcome.CLEAN}, never {@code violations().isEmpty()}.
 *
 * <p>Deliberately an enum, not a {@code boolean}: a {@code boolean} field defaults to {@code false}
 * on any accidental zero-value construction, which for a safety audit would silently read as "not
 * clean" at best or, worse, could be inverted to mean "clean" and default to a false negative. An
 * enum with two explicitly-named values has no such default — see
 * {@link StaffClientReferenceAuditResult}'s compact constructor for the invariant that keeps this
 * field consistent with {@code violations()}.
 */
public enum AuditOutcome {
    CLEAN,
    VIOLATIONS_FOUND
}
