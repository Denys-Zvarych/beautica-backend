package com.beautica.booking.service;

import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.repository.MasterServiceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link VisitPlanner} — the resolution + chaining contract, directly.
 *
 * <p><b>Why this class exists.</b> The planner is the ONE piece of visit-create machinery shared
 * verbatim by the APP, LINK and STAFF paths, and until now it had no test artifact of its own: its
 * behaviour was proved only indirectly, through whichever caller's suite happened to exercise it.
 * That was survivable while the resolution was a trivial per-id lookup; it stopped being survivable
 * when the resolution was rewritten to batch-load (perf LOW, 2026-08-22), because the three
 * properties that rewrite MUST NOT change — list ORDER, DUPLICATE occurrences, and the UNIFORM,
 * id-free 404 — are each properties of this class alone, and each is silently destroyed by an
 * obvious-looking implementation (walking the map instead of the list; de-duplicating the chain;
 * naming the missing id in the message).
 *
 * <p>Plain Mockito over the repository seam — no Spring. The planner is clock-free, lock-free and
 * has exactly one collaborator.
 *
 * <p><b>Fixture asymmetry is deliberate</b>: every service gets a DIFFERENT duration and price, and
 * the stubbed batch finder returns rows in an order UNLIKE the requested one. A fixture whose
 * services were interchangeable, or whose query echoed the request order back, could not fail an
 * ordering assertion at all.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VisitPlanner — batch resolution, ordering, duplicates and the uniform 404")
class VisitPlannerTest {

    private static final OffsetDateTime START = OffsetDateTime.parse("2026-06-10T12:00:00+03:00");

    @Mock private MasterServiceRepository masterServiceRepository;

    private VisitPlanner planner;
    private Master master;

    private final UUID masterId = UUID.randomUUID();
    private final UUID serviceA = UUID.randomUUID();
    private final UUID serviceB = UUID.randomUUID();
    private final UUID serviceC = UUID.randomUUID();

    /** Distinct per service, so a mis-ordered chain produces mis-sized windows, not identical ones. */
    private static final int DURATION_A = 30;
    private static final int DURATION_B = 45;
    private static final int DURATION_C = 60;
    private static final int BUFFER_A = 5;
    private static final int BUFFER_B = 10;
    private static final int BUFFER_C = 15;

    @BeforeEach
    void setUp() {
        planner = new VisitPlanner(masterServiceRepository);
        master = Master.builder().id(masterId).isActive(true).build();
    }

    @Nested
    @DisplayName("Ordering")
    class Ordering {

        /**
         * <b>The chain follows {@code serviceIds}, never the query's result order.</b> The stub
         * returns C, A, B for a request of A, B, C — so a planner that iterated the batch result (or
         * the map built from it) would chain the windows in the wrong sequence and this test would
         * fail on the very first item's duration.
         *
         * <p>Asserted through the WINDOWS rather than the assignment ids, because the windows are
         * what the booking rows freeze: a wrongly-ordered chain writes the wrong duration against
         * the wrong service, which is a data defect, not a cosmetic one.
         */
        @Test
        @DisplayName("should_chainInRequestOrder_when_theBatchFinderReturnsADifferentOrder")
        void should_chainInRequestOrder_when_theBatchFinderReturnsADifferentOrder() {
            stubBatch(List.of(serviceA, serviceB, serviceC), shuffledAssignments());

            List<VisitPlanner.PlannedItem> items =
                    planner.planChainedItems(master, List.of(serviceA, serviceB, serviceC), START);

            assertThat(items)
                    .extracting(VisitPlanner.PlannedItem::masterService)
                    .extracting(MasterServiceAssignment::getId)
                    .as("index i must be serviceIds[i]'s assignment — the parallel-index contract "
                            + "VisitPlanner#assignmentsOf and the slot-fit gate both depend on")
                    .containsExactly(serviceA, serviceB, serviceC);

            OffsetDateTime endA = START.plusMinutes(DURATION_A + BUFFER_A);
            OffsetDateTime endB = endA.plusMinutes(DURATION_B + BUFFER_B);
            assertThat(items.get(0).startsAt()).isEqualTo(START);
            assertThat(items.get(0).endsAt()).isEqualTo(endA);
            assertThat(items.get(1).startsAt()).as("item i starts exactly at item i-1's end").isEqualTo(endA);
            assertThat(items.get(1).endsAt()).isEqualTo(endB);
            assertThat(items.get(2).startsAt()).isEqualTo(endB);
            assertThat(items.get(2).endsAt())
                    .isEqualTo(endB.plusMinutes(DURATION_C + BUFFER_C));
        }

        /**
         * The same list, reversed. Two orderings of one id set are what separate "the planner honours
         * the request" from "the planner happens to agree with the fixture" — a single ordering case
         * can be satisfied by an implementation that sorts, or that echoes the query.
         */
        @Test
        @DisplayName("should_chainInRequestOrder_when_theSameIdsAreRequestedInReverse")
        void should_chainInRequestOrder_when_theSameIdsAreRequestedInReverse() {
            stubBatch(List.of(serviceC, serviceB, serviceA), shuffledAssignments());

            List<VisitPlanner.PlannedItem> items =
                    planner.planChainedItems(master, List.of(serviceC, serviceB, serviceA), START);

            assertThat(items)
                    .extracting(i -> i.masterService().getId())
                    .containsExactly(serviceC, serviceB, serviceA);
            assertThat(items.get(0).endsAt())
                    .as("the FIRST window is now C's, not A's")
                    .isEqualTo(START.plusMinutes(DURATION_C + BUFFER_C));
        }
    }

    @Nested
    @DisplayName("Duplicates")
    class Duplicates {

        /**
         * Duplicates are legal input (locked decision — the visit block sums the repeats, matching
         * what BE-2 availability offered). Each OCCURRENCE must produce its own chain item with its
         * own window; de-duplicating the chain would silently shorten the visit and double-book the
         * tail.
         *
         * <p>Note the query side is de-duplicated and the CHAIN side is not — the two must not be
         * confused, which is exactly why both are asserted in this one test.
         */
        @Test
        @DisplayName("should_produceOneItemPerOccurrence_when_anIdIsRepeated")
        void should_produceOneItemPerOccurrence_when_anIdIsRepeated() {
            stubBatch(List.of(serviceA, serviceB), List.of(assignment(serviceB), assignment(serviceA)));

            List<VisitPlanner.PlannedItem> items = planner.planChainedItems(
                    master, List.of(serviceA, serviceB, serviceA), START);

            assertThat(items)
                    .as("three requested occurrences must yield three chain items, not two")
                    .hasSize(3);
            assertThat(items)
                    .extracting(i -> i.masterService().getId())
                    .containsExactly(serviceA, serviceB, serviceA);

            OffsetDateTime endFirstA = START.plusMinutes(DURATION_A + BUFFER_A);
            OffsetDateTime endB = endFirstA.plusMinutes(DURATION_B + BUFFER_B);
            assertThat(items.get(2).startsAt())
                    .as("the repeat occupies its OWN window at the end of the chain, not the first "
                            + "occurrence's window")
                    .isEqualTo(endB);
            assertThat(items.get(2).endsAt()).isEqualTo(endB.plusMinutes(DURATION_A + BUFFER_A));
        }

        /**
         * The query side of the same rule: ten occurrences of one service must ask the database about
         * ONE id, not ten. Captured rather than matched, so the assertion is on what was actually
         * asked — a set of exactly {@code {serviceA}} — instead of a wildcard that any argument
         * satisfies.
         */
        @Test
        @DisplayName("should_queryEachDistinctIdOnce_when_theSameServiceIsBookedTenTimes")
        void should_queryEachDistinctIdOnce_when_theSameServiceIsBookedTenTimes() {
            stubBatch(List.of(serviceA), List.of(assignment(serviceA)));
            List<UUID> tenTimes = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                tenTimes.add(serviceA);
            }

            List<VisitPlanner.PlannedItem> items = planner.planChainedItems(master, tenTimes, START);

            assertThat(items).hasSize(10);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
            verify(masterServiceRepository, times(1))
                    .findByMasterIdAndIdInWithGraph(eq(masterId), captor.capture());
            assertThat(captor.getValue())
                    .as("ten identical ids must be de-duplicated into ONE query argument")
                    .containsExactly(serviceA);
        }
    }

    @Nested
    @DisplayName("Anti-N+1")
    class AntiNPlusOne {

        /**
         * <b>The anti-N+1 guard.</b> One round trip for the whole chain, and the retired per-id
         * finder never touched. Both halves matter: a planner that batch-loads AND then "verifies"
         * each id individually would satisfy the first assertion alone.
         *
         * <p>The IT-tier twin is {@code StaffBookingIT.StatementCount}, which measures the same
         * property against real SQL; this row is what fails FIRST, in milliseconds, when the loop
         * comes back.
         */
        @Test
        @DisplayName("should_issueExactlyOneResolutionQuery_when_planningATenServiceVisit")
        void should_issueExactlyOneResolutionQuery_when_planningATenServiceVisit() {
            List<UUID> ids = new ArrayList<>();
            List<MasterServiceAssignment> rows = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                rows.add(assignment(id, DURATION_A, BUFFER_A, new BigDecimal("100.00")));
            }
            stubBatch(ids, rows);

            planner.planChainedItems(master, ids, START);

            verify(masterServiceRepository, times(1))
                    .findByMasterIdAndIdInWithGraph(eq(masterId), anyCollection());
            verify(masterServiceRepository, org.mockito.Mockito.never())
                    .findByMasterIdAndIdWithGraph(any(), any());
        }

        /**
         * The query is MASTER-SCOPED, and that scoping is the entire service-eligibility guard: an
         * unscoped batch finder would resolve another provider's assignment and book it. Asserted on
         * the captured argument, not merely on {@code any()}.
         */
        @Test
        @DisplayName("should_scopeTheResolutionToTheGivenMaster_when_planning")
        void should_scopeTheResolutionToTheGivenMaster_when_planning() {
            stubBatch(List.of(serviceA), List.of(assignment(serviceA)));

            planner.planChainedItems(master, List.of(serviceA), START);

            verify(masterServiceRepository).findByMasterIdAndIdInWithGraph(eq(masterId), anyCollection());
        }
    }

    @Nested
    @DisplayName("Uniform 404")
    class Uniform404 {

        /**
         * A shortfall in the batch result is a 404 — and the message must NOT name the id. Unknown,
         * foreign (another master's) and inactive are deliberately indistinguishable: a message that
         * named the offender would turn this endpoint into an enumeration oracle over other
         * providers' catalogues.
         */
        @Test
        @DisplayName("should_throw404WithoutNamingTheId_when_oneRequestedServiceIsMissing")
        void should_throw404WithoutNamingTheId_when_oneRequestedServiceIsMissing() {
            UUID missing = UUID.randomUUID();
            stubBatch(List.of(serviceA, missing), List.of(assignment(serviceA)));

            assertThatThrownBy(() -> planner.planChainedItems(master, List.of(serviceA, missing), START))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Master service not found")
                    .as("naming the id would leak WHICH of unknown/foreign/inactive it was")
                    .hasMessageNotContaining(missing.toString())
                    .hasMessageNotContaining(serviceA.toString());
        }

        /**
         * An INACTIVE assignment answers the identical 404. The row exists and belongs to this
         * master, so only the {@code isActive} filter can produce the refusal — and the batch form
         * must apply it with the same semantics the per-id {@code .filter(isActive)} had.
         */
        @Test
        @DisplayName("should_throw404WithTheSameMessage_when_theAssignmentIsInactive")
        void should_throw404WithTheSameMessage_when_theAssignmentIsInactive() {
            MasterServiceAssignment inactive = assignment(serviceA);
            inactive.setActive(false);
            stubBatch(List.of(serviceA), List.of(inactive));

            assertThatThrownBy(() -> planner.planChainedItems(master, List.of(serviceA), START))
                    .isInstanceOf(NotFoundException.class)
                    .as("an inactive assignment must be indistinguishable from an unknown one")
                    .hasMessage("Master service not found");
        }

        /**
         * A {@code null} element is short-circuited into the same 404 rather than reaching the
         * {@code IN} list. The old per-id finder answered 404 for it; letting it through would turn a
         * malformed request into a 500 (or, worse, an {@code IN (NULL)} that silently matches
         * nothing while the shortfall check reports the same thing anyway).
         */
        @Test
        @DisplayName("should_throw404WithoutQueryingAtAll_when_anIdIsNull")
        void should_throw404WithoutQueryingAtAll_when_anIdIsNull() {
            List<UUID> withNull = new ArrayList<>();
            withNull.add(serviceA);
            withNull.add(null);

            assertThatThrownBy(() -> planner.planChainedItems(master, withNull, START))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Master service not found");

            verify(masterServiceRepository, org.mockito.Mockito.never())
                    .findByMasterIdAndIdInWithGraph(any(), anyCollection());
        }

        /**
         * ORDER of the two guards: the 404 wins over the &Sigma;-duration cap, as it did before the
         * batch rewrite (resolution runs before the accumulating loop). A request that is BOTH
         * over-cap and names an unknown service must answer 404, never 400 — otherwise a prober
         * learns that every named id resolved simply by choosing a long enough list.
         */
        @Test
        @DisplayName("should_answer404NotTheDurationCap_when_theRequestIsBothOverCapAndUnknown")
        void should_answer404NotTheDurationCap_when_theRequestIsBothOverCapAndUnknown() {
            UUID missing = UUID.randomUUID();
            List<UUID> ids = new ArrayList<>();
            List<MasterServiceAssignment> rows = new ArrayList<>();
            // 9 × 120 min = 1080 > MAX_TOTAL_DURATION_MINUTES (600), plus one id that resolves to
            // nothing — so both guards are armed and only their ORDER decides the answer.
            for (int i = 0; i < 9; i++) {
                UUID id = UUID.randomUUID();
                ids.add(id);
                rows.add(assignment(id, 120, 0, new BigDecimal("100.00")));
            }
            ids.add(missing);
            stubBatch(ids, rows);

            assertThatThrownBy(() -> planner.planChainedItems(master, ids, START))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessage("Master service not found");
        }
    }

    @Nested
    @DisplayName("List-size guard")
    class ListSizeGuard {

        @Test
        @DisplayName("should_reject400WithoutQuerying_when_theListIsEmpty")
        void should_reject400WithoutQuerying_when_theListIsEmpty() {
            assertThatThrownBy(() -> planner.planChainedItems(master, List.of(), START))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("At least one service is required");

            verify(masterServiceRepository, org.mockito.Mockito.never())
                    .findByMasterIdAndIdInWithGraph(any(), anyCollection());
        }

        /**
         * The size guard runs BEFORE the resolution, so an over-cap list never becomes an over-cap
         * {@code IN} list. {@code MAX_SERVICES_PER_VISIT + 1} distinct ids, so the guard cannot be
         * satisfied by de-duplication.
         */
        @Test
        @DisplayName("should_reject400WithoutQuerying_when_theListExceedsMaxServicesPerVisit")
        void should_reject400WithoutQuerying_when_theListExceedsMaxServicesPerVisit() {
            List<UUID> tooMany = new ArrayList<>();
            for (int i = 0; i <= SlotCalculationService.MAX_SERVICES_PER_VISIT; i++) {
                tooMany.add(UUID.randomUUID());
            }

            assertThatThrownBy(() -> planner.planChainedItems(master, tooMany, START))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("At most " + SlotCalculationService.MAX_SERVICES_PER_VISIT);

            verify(masterServiceRepository, org.mockito.Mockito.never())
                    .findByMasterIdAndIdInWithGraph(any(), anyCollection());
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────

    /**
     * Stubs the batch finder for the EXACT distinct id set the planner is expected to ask for — a
     * wildcard would let a planner that narrowed, widened or mis-scoped the set pass unnoticed.
     */
    private void stubBatch(List<UUID> expectedDistinctIds, List<MasterServiceAssignment> returned) {
        when(masterServiceRepository.findByMasterIdAndIdInWithGraph(
                eq(masterId), eq(new java.util.LinkedHashSet<>(expectedDistinctIds))))
                .thenReturn(returned);
    }

    /** A, B and C in an order deliberately unlike any request this suite makes. */
    private List<MasterServiceAssignment> shuffledAssignments() {
        return List.of(assignment(serviceC), assignment(serviceA), assignment(serviceB));
    }

    private MasterServiceAssignment assignment(UUID id) {
        if (id.equals(serviceA)) {
            return assignment(id, DURATION_A, BUFFER_A, new BigDecimal("100.00"));
        }
        if (id.equals(serviceB)) {
            return assignment(id, DURATION_B, BUFFER_B, new BigDecimal("200.00"));
        }
        return assignment(id, DURATION_C, BUFFER_C, new BigDecimal("300.00"));
    }

    private MasterServiceAssignment assignment(UUID id, int duration, int buffer, BigDecimal price) {
        return MasterServiceAssignment.builder()
                .id(id)
                .master(master)
                .serviceDefinition(ServiceDefinition.builder()
                        .name("Манікюр")
                        .baseDurationMinutes(duration)
                        .bufferMinutesAfter(buffer)
                        .basePrice(price)
                        .build())
                .isActive(true)
                .build();
    }
}
