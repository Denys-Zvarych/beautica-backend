package com.beautica.booking.service;

import com.beautica.booking.dto.BookingLinkKind;
import com.beautica.common.exception.GoneException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.entity.Master;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SalonGuestMasterResolver — (salonSlug, masterId) → bookable master, 404/410 miss path (phases 392/393)")
class SalonGuestMasterResolverTest {

    private static final String SLUG = "studiia-krasa-ab12";
    private static final UUID MASTER_ID = UUID.randomUUID();

    @Mock private MasterRepository masterRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private RetiredBookingSlugGuard retiredBookingSlugGuard;
    @InjectMocks private SalonGuestMasterResolver resolver;

    @Test
    @DisplayName("hit → the scalar id; the miss probes never run")
    void should_returnId_when_masterBookableInSalon() {
        when(masterRepository.findBookableIdBySalonSlug(MASTER_ID, SLUG)).thenReturn(Optional.of(MASTER_ID));

        UUID id = resolver.resolveBookableMasterId(SLUG, MASTER_ID);

        assertThat(id).isEqualTo(MASTER_ID);
        verifyNoInteractions(salonRepository, retiredBookingSlugGuard);
    }

    @Test
    @DisplayName("hit → the fetched master entity for the booking create")
    void should_returnMaster_when_masterBookableInSalon() {
        Master master = mock(Master.class);
        when(masterRepository.findBookableBySalonSlugWithUser(MASTER_ID, SLUG)).thenReturn(Optional.of(master));

        assertThat(resolver.resolveBookableMaster(SLUG, MASTER_ID)).isSameAs(master);
        verifyNoInteractions(salonRepository, retiredBookingSlugGuard);
    }

    @Test
    @DisplayName("miss, salon live → 404 (foreign / inactive master), the retired guard is NOT consulted")
    void should_throw404WithoutRetiredCheck_when_salonLiveButMasterNotInIt() {
        when(masterRepository.findBookableIdBySalonSlug(MASTER_ID, SLUG)).thenReturn(Optional.empty());
        when(salonRepository.existsByBookingSlugAndIsActiveTrue(SLUG)).thenReturn(true);

        assertThatThrownBy(() -> resolver.resolveBookableMasterId(SLUG, MASTER_ID))
                .isInstanceOf(NotFoundException.class).hasMessage("Booking page not found");
        verify(retiredBookingSlugGuard, never()).throwGoneIfRetired(any(), anyString());
    }

    @Test
    @DisplayName("miss, salon slug not live and retired → 410 from the guard (SALON kind)")
    void should_throwGone_when_salonSlugRetired() {
        when(masterRepository.findBookableBySalonSlugWithUser(MASTER_ID, SLUG)).thenReturn(Optional.empty());
        when(salonRepository.existsByBookingSlugAndIsActiveTrue(SLUG)).thenReturn(false);
        doThrow(GoneException.bookingLinkRetired()).when(retiredBookingSlugGuard)
                .throwGoneIfRetired(BookingLinkKind.SALON, SLUG);

        assertThatThrownBy(() -> resolver.resolveBookableMaster(SLUG, MASTER_ID)).isInstanceOf(GoneException.class);
    }

    @Test
    @DisplayName("miss, salon slug not live and never retired (unknown / closed salon) → 404")
    void should_throw404_when_salonSlugUnknownOrClosed() {
        when(masterRepository.findBookableIdBySalonSlug(MASTER_ID, SLUG)).thenReturn(Optional.empty());
        when(salonRepository.existsByBookingSlugAndIsActiveTrue(SLUG)).thenReturn(false);

        assertThatThrownBy(() -> resolver.resolveBookableMasterId(SLUG, MASTER_ID))
                .isInstanceOf(NotFoundException.class);
        verify(retiredBookingSlugGuard).throwGoneIfRetired(BookingLinkKind.SALON, SLUG);
    }
}
