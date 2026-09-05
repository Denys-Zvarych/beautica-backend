package com.beautica.salon.repository;

import com.beautica.auth.Role;

import java.util.UUID;

/**
 * Interface projection shared by the three {@code GROUP BY} queries in
 * {@link StaffClientReferenceAuditRepository} — one row per (user, role) pair found referenced as
 * a client at a given site, with the row count already aggregated in SQL. Mirrors
 * {@code UserRatingProjection}'s narrowness: never the full {@code User} entity, which carries
 * {@code passwordHash} and the rest of the PII surface this read-only audit has no reason to touch.
 */
public interface StaffClientReferenceRowProjection {

    UUID getUserId();

    Role getRole();

    long getRowCount();
}
