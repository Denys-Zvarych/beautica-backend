package com.beautica.master.service;

import com.beautica.common.cache.BookabilitySearchCacheEvictor;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.repository.MasterRepository.SearchBookabilityRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MasterSearchVisibilityGuard — clears discovery search only on an actual verdict flip")
class MasterSearchVisibilityGuardTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    private static final UUID MASTER_ID = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID SALON_ID = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private ScheduleDateMath scheduleDateMath;

    @Mock
    private BookabilitySearchCacheEvictor evictor;

    private MasterSearchVisibilityGuard guard;

    @BeforeEach
    void setUp() {
        guard = new MasterSearchVisibilityGuard(masterRepository, scheduleDateMath, evictor);
    }

    @Test
    @DisplayName("verdict unchanged (still bookable) — nothing is cleared")
    void should_notClear_when_verdictUnchanged() {
        when(scheduleDateMath.today()).thenReturn(TODAY);
        when(masterRepository.findSearchBookability(anyCollection(), eq(TODAY)))
                .thenReturn(List.of(row(MASTER_ID, null, true)), List.of(row(MASTER_ID, null, true)));

        guard.clearSearchCachesIfFlipped(guard.capture(List.of(MASTER_ID)));

        verifyNoInteractions(evictor);
    }

    @Test
    @DisplayName("independent master turns unbookable — master search (only) is cleared")
    void should_clearMasterSearchOnly_when_independentMasterFlips() {
        when(scheduleDateMath.today()).thenReturn(TODAY);
        when(masterRepository.findSearchBookability(anyCollection(), eq(TODAY)))
                .thenReturn(List.of(row(MASTER_ID, null, true)), List.of(row(MASTER_ID, null, false)));

        guard.clearSearchCachesIfFlipped(guard.capture(List.of(MASTER_ID)));

        verify(evictor).clearMasterSearch();
        verify(evictor, never()).clearSalonSearch();
    }

    @Test
    @DisplayName("salon master turns bookable — salon search (only) is cleared")
    void should_clearSalonSearchOnly_when_salonMasterFlips() {
        when(scheduleDateMath.today()).thenReturn(TODAY);
        when(masterRepository.findSearchBookability(anyCollection(), eq(TODAY)))
                .thenReturn(List.of(row(MASTER_ID, SALON_ID, false)), List.of(row(MASTER_ID, SALON_ID, true)));

        guard.clearSearchCachesIfFlipped(guard.capture(List.of(MASTER_ID)));

        verify(evictor).clearSalonSearch();
        verify(evictor, never()).clearMasterSearch();
    }

    @Test
    @DisplayName("several flipped salon masters clear the salon surface ONCE")
    void should_clearSurfaceOnce_when_manyMastersFlip() {
        UUID other = UUID.randomUUID();
        when(scheduleDateMath.today()).thenReturn(TODAY);
        when(masterRepository.findSearchBookability(anyCollection(), eq(TODAY)))
                .thenReturn(List.of(row(MASTER_ID, SALON_ID, true), row(other, SALON_ID, true)),
                        List.of(row(MASTER_ID, SALON_ID, false), row(other, SALON_ID, false)));

        guard.clearSearchCachesIfFlipped(guard.capture(List.of(MASTER_ID, other)));

        verify(evictor, times(1)).clearSalonSearch();
    }

    @Test
    @DisplayName("the write is flushed BEFORE the re-read, and the re-read reuses the captured date even "
            + "if Kyiv midnight passes in between")
    void should_flushThenReReadAtCapturedDate_when_checkingFlip() {
        when(scheduleDateMath.today()).thenReturn(TODAY);
        when(masterRepository.findSearchBookability(anyCollection(), any()))
                .thenReturn(List.of(row(MASTER_ID, null, true)));

        MasterSearchVisibilityGuard.Snapshot before = guard.capture(List.of(MASTER_ID));
        guard.clearSearchCachesIfFlipped(before);

        InOrder order = inOrder(masterRepository);
        order.verify(masterRepository).findSearchBookability(List.of(MASTER_ID), TODAY);
        order.verify(masterRepository).flush();
        order.verify(masterRepository).findSearchBookability(anyCollection(), eq(TODAY));
        verify(scheduleDateMath, times(1)).today();
    }

    @Test
    @DisplayName("no masters — no query, no flush, no clear")
    void should_doNothing_when_noMasters() {
        guard.clearSearchCachesIfFlipped(guard.capture(List.of()));

        verifyNoInteractions(masterRepository, scheduleDateMath, evictor);
    }

    private static SearchBookabilityRow row(UUID masterId, UUID salonId, boolean bookable) {
        return new SearchBookabilityRow() {
            @Override
            public UUID getMasterId() {
                return masterId;
            }

            @Override
            public UUID getSalonId() {
                return salonId;
            }

            @Override
            public Boolean getBookable() {
                return bookable;
            }
        };
    }
}
