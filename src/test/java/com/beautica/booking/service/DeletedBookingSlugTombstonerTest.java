package com.beautica.booking.service;

import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.booking.repository.RetiredBookingSlugRepository;
import com.beautica.master.entity.Master;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeletedBookingSlugTombstoner (phase 379 D7)")
class DeletedBookingSlugTombstonerTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    @Mock private RetiredBookingSlugRepository retiredBookingSlugRepository;
    private DeletedBookingSlugTombstoner tombstoner;

    @BeforeEach
    void setUp() {
        tombstoner = new DeletedBookingSlugTombstoner(retiredBookingSlugRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("masters: one batched insert of MASTER hashes for the slugged masters only, paired by index")
    void should_insertHashesOfSluggedMastersOnly_when_mastersDisposed() {
        Master withSlug = master("olena-k-ab12");
        Master noSlug = master(null);

        tombstoner.tombstoneMasters(List.of(withSlug, noSlug));

        verify(retiredBookingSlugRepository).insertDeletedMasterTombstones(
                new String[] {RetiredSlugHasher.hash(BookingLinkKind.MASTER, "olena-k-ab12")},
                new String[] {withSlug.getId().toString()},
                NOW);
    }

    @Test
    @DisplayName("masters: none with a slug → no statement")
    void should_writeNothing_when_noMasterHasSlug() {
        tombstoner.tombstoneMasters(List.of(master(null)));

        verifyNoInteractions(retiredBookingSlugRepository);
    }

    @Test
    @DisplayName("salon: SALON hash of the live slug; null slug → no statement")
    void should_insertSalonHash_when_salonHasSlug() {
        UUID salonId = UUID.randomUUID();

        tombstoner.tombstoneSalon(salonId, "studio-a1b2");
        tombstoner.tombstoneSalon(UUID.randomUUID(), null);

        verify(retiredBookingSlugRepository).insertDeletedSalonTombstone(
                RetiredSlugHasher.hash(BookingLinkKind.SALON, "studio-a1b2"), salonId, NOW);
        verify(retiredBookingSlugRepository, org.mockito.Mockito.times(1))
                .insertDeletedSalonTombstone(any(), any(), any());
    }

    private static Master master(String slug) {
        Master master = Master.builder().id(UUID.randomUUID()).build();
        ReflectionTestUtils.setField(master, "bookingSlug", slug);
        return master;
    }
}
