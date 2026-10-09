package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.PendingBookingActionsCountResponse;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("PendingBookingActionsService — Phase 357 role -> scope routing")
class PendingBookingActionsServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-09T12:00:00Z");

    @Mock
    private BookingRepository bookingRepository;
    @Mock
    private BookingService bookingService;
    @InjectMocks
    private PendingBookingActionsService service;

    private final UUID actorId = UUID.randomUUID();

    @BeforeEach
    void pinNow() {
        org.mockito.Mockito.lenient().when(bookingService.resolveNow()).thenReturn(NOW);
    }

    @Test
    @DisplayName("INDEPENDENT_MASTER resolves via resolveProviderMasterScope and sums two counts")
    @SuppressWarnings("unchecked")
    void should_sumTwoCounts_when_independentMaster() {
        Master master = master(MasterType.INDEPENDENT_MASTER, true);
        when(bookingService.resolveProviderMasterScope(Role.INDEPENDENT_MASTER, actorId)).thenReturn(master);
        when(bookingRepository.count(any(Specification.class))).thenReturn(3L, 2L);

        PendingBookingActionsCountResponse result =
                service.countForMe(actorId, auth(Role.INDEPENDENT_MASTER), false);

        assertThat(result).isEqualTo(new PendingBookingActionsCountResponse(5L, 3L, 2L));
        verify(bookingRepository, times(2)).count(any(Specification.class));
    }

    @Test
    @DisplayName("SALON_OWNER with asMaster=true resolves via resolveOwnerMasterScope")
    @SuppressWarnings("unchecked")
    void should_useOwnerMasterScope_when_ownerAsMaster() {
        Master master = master(MasterType.SALON_OWNER, true);
        when(bookingService.resolveOwnerMasterScope(actorId)).thenReturn(master);
        when(bookingRepository.count(any(Specification.class))).thenReturn(1L, 1L);

        PendingBookingActionsCountResponse result = service.countForMe(actorId, auth(Role.SALON_OWNER), true);

        assertThat(result.count()).isEqualTo(2L);
        verify(bookingService, never()).resolveProviderMasterScope(any(), any());
    }

    @Test
    @DisplayName("inactive INDEPENDENT_MASTER: toRateClient is 0 and only one COUNT runs")
    @SuppressWarnings("unchecked")
    void should_skipRateCount_when_independentMasterInactive() {
        Master master = master(MasterType.INDEPENDENT_MASTER, false);
        when(bookingService.resolveProviderMasterScope(Role.INDEPENDENT_MASTER, actorId)).thenReturn(master);
        when(bookingRepository.count(any(Specification.class))).thenReturn(4L);

        PendingBookingActionsCountResponse result =
                service.countForMe(actorId, auth(Role.INDEPENDENT_MASTER), false);

        assertThat(result).isEqualTo(new PendingBookingActionsCountResponse(4L, 4L, 0L));
        verify(bookingRepository, times(1)).count(any(Specification.class));
    }

    @Test
    @DisplayName("SALON_OWNER without asMaster is 403 and touches nothing")
    void should_throwForbidden_when_ownerWithoutAsMaster() {
        assertThatThrownBy(() -> service.countForMe(actorId, auth(Role.SALON_OWNER), false))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("SALON_MASTER, SALON_ADMIN and CLIENT are 403 on /me")
    void should_throwForbidden_when_nonScopedRole() {
        for (Role role : List.of(Role.SALON_MASTER, Role.SALON_ADMIN, Role.CLIENT)) {
            assertThatThrownBy(() -> service.countForMe(actorId, auth(role), true))
                    .as(role.name()).isInstanceOf(ForbiddenException.class);
        }

        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("salon scope runs exactly two COUNT queries and returns count = toClose + toRateClient")
    @SuppressWarnings("unchecked")
    void should_runTwoCounts_when_salonScope() {
        when(bookingRepository.count(any(Specification.class))).thenReturn(7L, 5L);

        PendingBookingActionsCountResponse result = service.countForSalon(UUID.randomUUID());

        assertThat(result).isEqualTo(new PendingBookingActionsCountResponse(12L, 7L, 5L));
        verify(bookingRepository, times(2)).count(any(Specification.class));
    }

    private Master master(MasterType type, boolean active) {
        Master master = mock(Master.class);
        org.mockito.Mockito.lenient().when(master.getId()).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.lenient().when(master.getMasterType()).thenReturn(type);
        org.mockito.Mockito.lenient().when(master.isActive()).thenReturn(active);
        return master;
    }

    private Authentication auth(Role role) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                actorId.toString(), null, List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        token.setDetails(actorId);
        return token;
    }
}
