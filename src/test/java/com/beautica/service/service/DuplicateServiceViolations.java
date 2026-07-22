package com.beautica.service.service;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;

/**
 * Shared builder for the constraint-violation shape Spring hands
 * {@code ServiceCatalogService} when the V121 partial unique index fires (QA pattern Q4 — the
 * same fixture had been re-declared in {@code ServiceCatalogServiceTest},
 * {@code ServiceCatalogServiceUpdateTest} and {@code ServiceCatalogServiceBulkCreateTest}).
 *
 * <p><b>The nesting is the point, not boilerplate.</b> {@code isDuplicateServiceViolation} walks
 * the cause chain for a Hibernate {@link ConstraintViolationException} and reads its
 * <em>structured</em> {@code getConstraintName()}. A bare
 * {@link DataIntegrityViolationException} whose message merely mentions the index would classify
 * as NOT-a-duplicate, so a test that faked one would assert the opposite of production behaviour
 * while looking correct. Centralising the shape here means a future change to how violations are
 * classified has exactly one fixture to update, and no test can quietly drift onto a weaker fake.
 *
 * <p>Deliberately NOT a place to put the index name: each caller passes the constraint it means
 * to simulate, so the negative tests (a foreign-key violation that must be rethrown untouched)
 * use the same builder as the positive ones.
 */
final class DuplicateServiceViolations {

    /** The partial unique index behind {@code DUPLICATE_SERVICE} (V121). */
    static final String DUPLICATE_SERVICE_INDEX = "ux_service_def_owner_service_type_active";

    private DuplicateServiceViolations() {
    }

    /** Violation of {@code constraintName} with a plausible Postgres-style message. */
    static DataIntegrityViolationException violationOf(String constraintName) {
        return violationOf(constraintName,
                "could not execute statement [ERROR: duplicate key value violates unique constraint \""
                        + constraintName + "\"]");
    }

    /**
     * Violation of {@code constraintName} carrying an arbitrary {@code message} — for the tests
     * that prove classification follows the structured field even when the message has been
     * steered by caller-supplied text (PgJDBC renders {@code Detail: Failing row contains (…)},
     * echoing the request's own {@code name}/{@code description}).
     */
    static DataIntegrityViolationException violationOf(String constraintName, String message) {
        return new DataIntegrityViolationException(message,
                new ConstraintViolationException(
                        message, new SQLException(message, "23505"), constraintName));
    }
}
