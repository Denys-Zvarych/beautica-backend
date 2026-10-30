package com.beautica.moderation.service;

import com.beautica.common.exception.BookingUnavailableException;
import com.beautica.common.security.PhoneHasher;
import com.beautica.common.security.PhoneHasher.HashedPhone;
import com.beautica.config.PhoneHashPepperConfig;
import com.beautica.master.entity.Master;
import com.beautica.salon.entity.Salon;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 398 — scope from the TARGET master (salon vs independent), per-type probes, neutral refusal wording.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BookingBlockGuard — phase 398")
class BookingBlockGuardTest {

    private static final String PEPPER = "test-phone-hash-pepper-0123456789abcdef";
    private static final String PHONE = "+380501234567";

    @Mock
    private ClientBlockService clientBlockService;

    private final PhoneHasher phoneHasher = new PhoneHasher(new PhoneHashPepperConfig(PEPPER));

    private final UUID masterId = UUID.randomUUID();
    private final UUID salonId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();

    private BookingBlockGuard guard() {
        return new BookingBlockGuard(clientBlockService, phoneHasher);
    }

    @Test
    @DisplayName("salon master (incl. the owner as master) → salon scope")
    void should_useSalonScope_when_targetMasterHasSalon() {
        BlockScope scope = BookingBlockGuard.targetScopeOf(salonMaster());

        assertThat(scope).isEqualTo(BlockScope.salon(salonId));
    }

    @Test
    @DisplayName("master without a salon → the independent master's own scope")
    void should_useMasterScope_when_targetMasterHasNoSalon() {
        BlockScope scope = BookingBlockGuard.targetScopeOf(independentMaster());

        assertThat(scope).isEqualTo(BlockScope.master(masterId));
    }

    @Test
    @DisplayName("blocked account at a salon master → BOOKING_UNAVAILABLE with salon wording")
    void should_reject_when_salonMasterBookingInBlockedSalon() {
        when(clientBlockService.isClientBlocked(BlockScope.salon(salonId), clientId)).thenReturn(true);

        assertThatThrownBy(() -> guard().requireClientMayBook(salonMaster(), clientId))
                .isInstanceOf(BookingUnavailableException.class)
                .hasMessage(BookingUnavailableException.SALON_MESSAGE);
        verify(clientBlockService, never()).isGuestPhoneBlocked(any(), any());
    }

    @Test
    @DisplayName("blocked account at an independent master → master wording")
    void should_rejectWithMasterWording_when_independentMasterBlockedClient() {
        when(clientBlockService.isClientBlocked(BlockScope.master(masterId), clientId)).thenReturn(true);

        assertThatThrownBy(() -> guard().requireClientMayBook(independentMaster(), clientId))
                .isInstanceOf(BookingUnavailableException.class)
                .hasMessage(BookingUnavailableException.MASTER_MESSAGE);
    }

    @Test
    @DisplayName("not blocked → passes")
    void should_pass_when_clientNotBlocked() {
        assertThatCode(() -> guard().requireClientMayBook(independentMaster(), clientId)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("guest: the token phone is hashed and probed against GUEST rows only")
    void should_rejectGuest_when_verifiedPhoneBlocked() {
        HashedPhone hashed = phoneHasher.hash(PHONE);
        when(clientBlockService.isGuestPhoneBlocked(BlockScope.salon(salonId), hashed)).thenReturn(true);

        assertThatThrownBy(() -> guard().requireGuestMayBook(salonMaster(), PHONE))
                .isInstanceOf(BookingUnavailableException.class)
                .hasMessage(BookingUnavailableException.SALON_MESSAGE);
        verify(clientBlockService, never()).isClientBlocked(any(), any());
    }

    @Test
    @DisplayName("guest: a phone that does not normalise was never blockable → passes without a probe")
    void should_passWithoutProbe_when_guestPhoneNotHashable() {
        assertThatCode(() -> guard().requireGuestMayBook(salonMaster(), "not-a-phone")).doesNotThrowAnyException();

        verify(clientBlockService, never()).isGuestPhoneBlocked(any(), any());
    }

    private Master salonMaster() {
        Master master = mock(Master.class);
        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);
        when(master.getSalon()).thenReturn(salon);
        return master;
    }

    private Master independentMaster() {
        Master master = mock(Master.class);
        when(master.getSalon()).thenReturn(null);
        when(master.getId()).thenReturn(masterId);
        return master;
    }
}
