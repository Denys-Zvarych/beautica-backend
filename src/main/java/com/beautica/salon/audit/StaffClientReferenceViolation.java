package com.beautica.salon.audit;

import com.beautica.auth.Role;

import java.util.Objects;
import java.util.UUID;

/**
 * One offending (user, reference type) pair found by the salon-deletion safety audit (Phase 289):
 * a user whose {@code role} is {@code SALON_MASTER} or {@code SALON_ADMIN} is referenced as a
 * client at {@link #referenceType()}.
 *
 * <p>{@link #rowCount()} is a {@code GROUP BY} aggregate, not one violation per offending row — a
 * staff account with 40 offending bookings produces one violation with {@code rowCount == 40}. This
 * is a safety signal ("which accounts, how badly"), not a data export; see
 * {@code phase-289-staff-as-client-safety-audit.md} D3.
 */
public record StaffClientReferenceViolation(
        UUID userId,
        Role role,
        StaffClientReferenceType referenceType,
        long rowCount) {

    public StaffClientReferenceViolation {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(referenceType, "referenceType");
        if (rowCount <= 0) {
            throw new IllegalArgumentException("rowCount must be positive, was " + rowCount);
        }
    }
}
