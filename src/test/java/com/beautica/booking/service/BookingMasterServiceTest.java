package com.beautica.booking.service;

import com.beautica.master.entity.Master;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.entity.MasterServiceAssignment;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the master-scoped strict verdict added for the detail {@code bookable} flags and
 * the client view of {@code GET /masters/{id}/services}. The free-slot walk itself is
 * {@link SlotCalculationService#filterBookableAssignmentsBatch} (mocked here; covered by its own
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
        when(masterServiceRepository.findBookableAssignmentsByMasterIds(List.of(masterId))).thenReturn(List.of());

        Set<UUID> result = service.getBookableAssignmentIds(masterId);

        assertThat(result).isEmpty();
        verifyNoInteractions(slotCalculationService);
    }

    @Test
    @DisplayName("getBookableAssignmentIds — a master the batch gates out entirely yields an empty set")
    void should_returnEmpty_when_batchGatesOutEveryAssignment() {
        UUID masterId = UUID.randomUUID();
        MasterServiceAssignment only = assignment(UUID.randomUUID(), masterId);
        when(masterServiceRepository.findBookableAssignmentsByMasterIds(List.of(masterId)))
                .thenReturn(List.of(only));
        when(slotCalculationService.filterBookableAssignmentsBatch(any()))
                .thenReturn(Map.of(masterId, List.of()));

        Set<UUID> result = service.getBookableAssignmentIds(masterId);

        assertThat(result).isEmpty();
        verify(slotCalculationService).filterBookableAssignmentsBatch(Map.of(masterId, List.of(only)));
    }

    @Test
    @DisplayName("getBookableAssignmentIds — returns the ids of the assignments the batch gate keeps")
    void should_returnSurvivingAssignmentIds_when_batchGates() {
        UUID masterId = UUID.randomUUID();
        UUID keptId = UUID.randomUUID();
        MasterServiceAssignment kept = assignment(keptId, masterId);
        MasterServiceAssignment dropped = assignment(UUID.randomUUID(), masterId);
        when(masterServiceRepository.findBookableAssignmentsByMasterIds(List.of(masterId)))
                .thenReturn(List.of(kept, dropped));
        when(slotCalculationService.filterBookableAssignmentsBatch(any()))
                .thenReturn(Map.of(masterId, List.of(kept)));

        Set<UUID> result = service.getBookableAssignmentIds(masterId);

        assertThat(result).containsExactly(keptId);
    }

    private static MasterServiceAssignment assignment(UUID assignmentId, UUID masterId) {
        Master master = mock(Master.class);
        when(master.getId()).thenReturn(masterId);
        MasterServiceAssignment msa = mock(MasterServiceAssignment.class);
        org.mockito.Mockito.lenient().when(msa.getId()).thenReturn(assignmentId);
        when(msa.getMaster()).thenReturn(master);
        return msa;
    }
}
