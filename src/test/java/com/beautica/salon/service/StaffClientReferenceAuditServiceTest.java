package com.beautica.salon.service;

import com.beautica.auth.Role;
import com.beautica.salon.audit.AuditOutcome;
import com.beautica.salon.audit.StaffClientReferenceAuditResult;
import com.beautica.salon.audit.StaffClientReferenceType;
import com.beautica.salon.audit.StaffClientReferenceViolation;
import com.beautica.salon.repository.StaffClientReferenceAuditRepository;
import com.beautica.salon.repository.StaffClientReferenceRowProjection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link StaffClientReferenceAuditService} (Phase 289 QA audit) — the ONE property
 * that makes this safety audit trustworthy at all: a failed repository call must PROPAGATE, never
 * degrade into a falsely-CLEAN result. {@code StaffClientReferenceAuditServiceIT} (11 cases,
 * Testcontainers) proves the queries return the right rows against a real DB; nothing in that
 * suite exercises a repository FAILURE, because every fixture there is a normal, successful query
 * against a real schema. A {@code catch (Exception e) { return
 * StaffClientReferenceAuditResult.of(List.of(), clock.instant()); }} added to either audit method
 * would make every one of those 11 tests behave identically on the happy path and still pass —
 * this class is what would go RED.
 *
 * <p>Mockito unit test, not a Testcontainers IT — appropriate per this repo's pyramid (QA
 * playbook Q3): the property under test is pure service-layer control flow (does the exception
 * from a mocked dependency propagate), which needs no real database.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StaffClientReferenceAuditService — unit (fail-closed contract)")
class StaffClientReferenceAuditServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-09-03T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final List<Role> AUDITED_ROLES = List.of(Role.SALON_MASTER, Role.SALON_ADMIN);
    private static final UUID SALON_ID = UUID.randomUUID();

    @Mock
    private StaffClientReferenceAuditRepository auditRepository;

    private StaffClientReferenceAuditService service;

    @BeforeEach
    void setUp() {
        service = new StaffClientReferenceAuditService(auditRepository, CLOCK);
    }

    // ── runAudit(): exception propagation — the finding this class exists to close ────────────

    @Test
    @DisplayName("should_propagateException_when_bookingClientQueryThrows_inRunAudit")
    void should_propagateException_when_bookingClientQueryThrows_inRunAudit() {
        RuntimeException dbFailure = new RuntimeException("connection reset");
        when(auditRepository.findBookingClientViolations(anyList())).thenThrow(dbFailure);

        assertThatThrownBy(() -> service.runAudit())
                .as("a failed query must propagate — a caller silently receiving CLEAN here would "
                        + "clear a salon for deletion despite the audit never actually having run")
                .isSameAs(dbFailure);
        verify(auditRepository, never()).findReviewClientViolations(anyList());
        verify(auditRepository, never()).findClientReviewSubjectViolations(anyList());
    }

    @Test
    @DisplayName("should_propagateException_when_reviewClientQueryThrows_inRunAudit")
    void should_propagateException_when_reviewClientQueryThrows_inRunAudit() {
        when(auditRepository.findBookingClientViolations(anyList())).thenReturn(List.of());
        RuntimeException dbFailure = new RuntimeException("query timeout");
        when(auditRepository.findReviewClientViolations(anyList())).thenThrow(dbFailure);

        assertThatThrownBy(() -> service.runAudit()).isSameAs(dbFailure);
        verify(auditRepository, never()).findClientReviewSubjectViolations(anyList());
    }

    @Test
    @DisplayName("should_propagateException_when_clientReviewSubjectQueryThrows_inRunAudit")
    void should_propagateException_when_clientReviewSubjectQueryThrows_inRunAudit() {
        when(auditRepository.findBookingClientViolations(anyList())).thenReturn(List.of());
        when(auditRepository.findReviewClientViolations(anyList())).thenReturn(List.of());
        RuntimeException dbFailure = new RuntimeException("deadlock detected");
        when(auditRepository.findClientReviewSubjectViolations(anyList())).thenThrow(dbFailure);

        assertThatThrownBy(() -> service.runAudit()).isSameAs(dbFailure);
    }

    // ── runAuditForSalon(): same fail-closed contract, plus the empty-staff short-circuit ─────

    @Test
    @DisplayName("should_propagateException_when_staffIdResolutionThrows_inRunAuditForSalon")
    void should_propagateException_when_staffIdResolutionThrows_inRunAuditForSalon() {
        RuntimeException dbFailure = new RuntimeException("connection reset");
        when(auditRepository.findSalonStaffUserIds(SALON_ID)).thenThrow(dbFailure);

        assertThatThrownBy(() -> service.runAuditForSalon(SALON_ID)).isSameAs(dbFailure);
        verify(auditRepository, never()).findBookingClientViolationsForSalon(anyList(), anyList());
    }

    @Test
    @DisplayName("should_propagateException_when_salonScopedBookingQueryThrows_inRunAuditForSalon")
    void should_propagateException_when_salonScopedBookingQueryThrows_inRunAuditForSalon() {
        UUID staffId = UUID.randomUUID();
        when(auditRepository.findSalonStaffUserIds(SALON_ID)).thenReturn(List.of(staffId));
        RuntimeException dbFailure = new RuntimeException("connection reset");
        when(auditRepository.findBookingClientViolationsForSalon(anyList(), anyList())).thenThrow(dbFailure);

        assertThatThrownBy(() -> service.runAuditForSalon(SALON_ID)).isSameAs(dbFailure);
        verify(auditRepository, never()).findReviewClientViolationsForSalon(anyList(), anyList());
        verify(auditRepository, never()).findClientReviewSubjectViolationsForSalon(anyList(), anyList());
    }

    @Test
    @DisplayName("should_shortCircuitWithoutAnyFinderCall_when_salonHasNoStaff")
    void should_shortCircuitWithoutAnyFinderCall_when_salonHasNoStaff() {
        when(auditRepository.findSalonStaffUserIds(SALON_ID)).thenReturn(List.of());

        StaffClientReferenceAuditResult result = service.runAuditForSalon(SALON_ID);

        assertThat(result.outcome()).isEqualTo(AuditOutcome.CLEAN);
        assertThat(result.ranAt()).isEqualTo(FIXED_NOW);
        verify(auditRepository, never()).findBookingClientViolationsForSalon(anyList(), anyList());
        verify(auditRepository, never()).findReviewClientViolationsForSalon(anyList(), anyList());
        verify(auditRepository, never()).findClientReviewSubjectViolationsForSalon(anyList(), anyList());
    }

    // ── happy-path delegation — proves the result is actually built from the mocked rows ──────

    @Test
    @DisplayName("should_buildViolationsFromEachFinder_when_runAuditFindsRowsAtEverySite")
    void should_buildViolationsFromEachFinder_when_runAuditFindsRowsAtEverySite() {
        UUID bookingOffender = UUID.randomUUID();
        UUID reviewOffender = UUID.randomUUID();
        UUID clientReviewOffender = UUID.randomUUID();
        when(auditRepository.findBookingClientViolations(AUDITED_ROLES))
                .thenReturn(List.of(row(bookingOffender, Role.SALON_MASTER, 3)));
        when(auditRepository.findReviewClientViolations(AUDITED_ROLES))
                .thenReturn(List.of(row(reviewOffender, Role.SALON_ADMIN, 1)));
        when(auditRepository.findClientReviewSubjectViolations(AUDITED_ROLES))
                .thenReturn(List.of(row(clientReviewOffender, Role.SALON_MASTER, 2)));

        StaffClientReferenceAuditResult result = service.runAudit();

        assertThat(result.outcome()).isEqualTo(AuditOutcome.VIOLATIONS_FOUND);
        assertThat(result.ranAt()).isEqualTo(FIXED_NOW);
        assertThat(result.violations())
                .extracting(
                        StaffClientReferenceViolation::userId,
                        StaffClientReferenceViolation::role,
                        StaffClientReferenceViolation::referenceType,
                        StaffClientReferenceViolation::rowCount)
                .containsExactlyInAnyOrder(
                        tuple(
                                bookingOffender, Role.SALON_MASTER, StaffClientReferenceType.BOOKING_CLIENT, 3L),
                        tuple(
                                reviewOffender, Role.SALON_ADMIN, StaffClientReferenceType.REVIEW_CLIENT, 1L),
                        tuple(
                                clientReviewOffender,
                                Role.SALON_MASTER,
                                StaffClientReferenceType.CLIENT_REVIEW_SUBJECT,
                                2L));
    }

    @Test
    @DisplayName("should_returnCleanResult_when_everyFinderReturnsNoRows")
    void should_returnCleanResult_when_everyFinderReturnsNoRows() {
        when(auditRepository.findBookingClientViolations(any())).thenReturn(List.of());
        when(auditRepository.findReviewClientViolations(any())).thenReturn(List.of());
        when(auditRepository.findClientReviewSubjectViolations(any())).thenReturn(List.of());

        StaffClientReferenceAuditResult result = service.runAudit();

        assertThat(result.outcome()).isEqualTo(AuditOutcome.CLEAN);
        assertThat(result.violations()).isEmpty();
    }

    private static StaffClientReferenceRowProjection row(UUID userId, Role role, long rowCount) {
        return new StaffClientReferenceRowProjection() {
            @Override
            public UUID getUserId() {
                return userId;
            }

            @Override
            public Role getRole() {
                return role;
            }

            @Override
            public long getRowCount() {
                return rowCount;
            }
        };
    }
}
