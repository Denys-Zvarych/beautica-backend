package com.beautica.booking.service;

import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.repository.BookableAssignmentDurationRow;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.ServiceRepository;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the master-scoped strict verdict added for the detail {@code bookable} flags and
 * the client view of {@code GET /masters/{id}/services}. The free-slot walk itself is
 * {@link SlotCalculationService#filterBookableDurationsBatch} (mocked here; covered by its own
 * suites) — these tests pin the candidate loading, grouping and projection around it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingMasterService — master-scoped strict verdict")
class BookingMasterServiceTest {

    @Mock private SalonRepository salonRepository;
    @Mock private ServiceRepository serviceRepository;
    @Mock private MasterServiceRepository masterServiceRepository;
    @Mock private SlotCalculationService slotCalculationService;

    private BookingMasterService service;

    @BeforeEach
    void setUp() {
        service = new BookingMasterService(salonRepository, serviceRepository, masterServiceRepository,
                slotCalculationService, Clock.fixed(Instant.parse("2026-10-05T10:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    @DisplayName("getBookableAssignmentIds — no candidate assignment means no batch call, empty result")
    void should_skipBatch_when_noCandidateAssignments() {
        UUID masterId = UUID.randomUUID();
        when(masterServiceRepository.findBookableAssignmentDurationsByMasterIds(onlyMaster(masterId))).thenReturn(List.of());

        Set<UUID> result = service.getBookableAssignmentIds(masterId);

        assertThat(result).isEmpty();
        verifyNoInteractions(slotCalculationService);
    }

    @Test
    @DisplayName("getBookableAssignmentIds — a master the batch gates out entirely yields an empty set")
    void should_returnEmpty_when_batchGatesOutEveryAssignment() {
        UUID masterId = UUID.randomUUID();
        BookableAssignmentDurationRow only = assignment(UUID.randomUUID(), masterId);
        when(masterServiceRepository.findBookableAssignmentDurationsByMasterIds(onlyMaster(masterId)))
                .thenReturn(List.of(only));
        when(slotCalculationService.filterBookableDurationsBatch(any()))
                .thenReturn(Map.of(masterId, List.of()));

        Set<UUID> result = service.getBookableAssignmentIds(masterId);

        assertThat(result).isEmpty();
        verify(slotCalculationService).filterBookableDurationsBatch(Map.of(masterId, List.of(only)));
    }

    @Test
    @DisplayName("getBookableAssignmentIds — returns the ids of the assignments the batch gate keeps")
    void should_returnSurvivingAssignmentIds_when_batchGates() {
        UUID masterId = UUID.randomUUID();
        UUID keptId = UUID.randomUUID();
        BookableAssignmentDurationRow kept = assignment(keptId, masterId);
        BookableAssignmentDurationRow dropped = assignment(UUID.randomUUID(), masterId);
        when(masterServiceRepository.findBookableAssignmentDurationsByMasterIds(onlyMaster(masterId)))
                .thenReturn(List.of(kept, dropped));
        when(slotCalculationService.filterBookableDurationsBatch(any()))
                .thenReturn(Map.of(masterId, List.of(kept)));

        Set<UUID> result = service.getBookableAssignmentIds(masterId);

        assertThat(result).containsExactly(keptId);
    }

    /** Matches the one-element {@code uuid[]} bind for {@code masterId}. */
    private static UUID[] onlyMaster(UUID masterId) {
        return argThat(ids -> java.util.Arrays.equals(ids, new UUID[] {masterId}));
    }

    private static BookableAssignmentDurationRow assignment(UUID assignmentId, UUID masterId) {
        BookableAssignmentDurationRow row = mock(BookableAssignmentDurationRow.class);
        org.mockito.Mockito.lenient().when(row.getAssignmentId()).thenReturn(assignmentId);
        when(row.getMasterId()).thenReturn(masterId);
        return row;
    }
}
