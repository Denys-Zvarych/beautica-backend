package com.beautica.salon.audit;

import com.beautica.auth.Role;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit test for {@link StaffClientReferenceAuditResult}'s compact-constructor invariant
 * (Phase 289 QA audit) — {@code outcome == CLEAN ⇔ violations.isEmpty()}. No Spring context, no
 * database: this is a record invariant, and the whole "clean vs. not run cannot be conflated"
 * safety claim documented on the class javadoc and in the phase doc's D2 rests entirely on this
 * constructor actually throwing when the two fields disagree. Before this test, nothing exercised
 * that failure path directly — the existing {@code StaffClientReferenceAuditServiceIT} suite only
 * ever goes through the correct {@link StaffClientReferenceAuditResult#of} factory, which can never
 * produce an inconsistent pair, so it can never reach the branch this test pins.
 */
@DisplayName("StaffClientReferenceAuditResult — CLEAN/VIOLATIONS_FOUND invariant")
class StaffClientReferenceAuditResultTest {

    private static final Instant RAN_AT = Instant.parse("2026-09-03T10:00:00Z");

    @Test
    @DisplayName("of() with no violations yields CLEAN")
    void should_yieldClean_when_ofCalledWithNoViolations() {
        StaffClientReferenceAuditResult result = StaffClientReferenceAuditResult.of(List.of(), RAN_AT);

        assertThat(result.outcome()).isEqualTo(AuditOutcome.CLEAN);
        assertThat(result.violations()).isEmpty();
    }

    @Test
    @DisplayName("of() with at least one violation yields VIOLATIONS_FOUND")
    void should_yieldViolationsFound_when_ofCalledWithAtLeastOneViolation() {
        StaffClientReferenceAuditResult result =
                StaffClientReferenceAuditResult.of(List.of(oneViolation()), RAN_AT);

        assertThat(result.outcome()).isEqualTo(AuditOutcome.VIOLATIONS_FOUND);
        assertThat(result.violations()).hasSize(1);
    }

    /**
     * The load-bearing case. If a future edit weakens or removes the compact constructor's
     * consistency check, this is the ONLY test in the suite that would catch it — every other test
     * touching this record goes through {@link StaffClientReferenceAuditResult#of}, which cannot
     * construct this state to begin with.
     */
    @Test
    @DisplayName("hand-constructing CLEAN with a non-empty violation list throws, never silently accepted")
    void should_throwIllegalState_when_outcomeCleanButViolationsNonEmpty() {
        assertThatThrownBy(() -> new StaffClientReferenceAuditResult(
                        AuditOutcome.CLEAN, List.of(oneViolation()), RAN_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CLEAN")
                .hasMessageContaining("inconsistent");
    }

    @Test
    @DisplayName("hand-constructing VIOLATIONS_FOUND with an empty violation list throws, never silently accepted")
    void should_throwIllegalState_when_outcomeViolationsFoundButListEmpty() {
        assertThatThrownBy(() -> new StaffClientReferenceAuditResult(
                        AuditOutcome.VIOLATIONS_FOUND, List.of(), RAN_AT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VIOLATIONS_FOUND")
                .hasMessageContaining("inconsistent");
    }

    private StaffClientReferenceViolation oneViolation() {
        return new StaffClientReferenceViolation(
                UUID.randomUUID(), Role.SALON_MASTER, StaffClientReferenceType.BOOKING_CLIENT, 1);
    }
}
