package com.beautica.moderation.service;

import com.beautica.auth.Role;
import com.beautica.booking.service.BookingService;
import com.beautica.moderation.dto.BlockKind;
import com.beautica.moderation.dto.BlockResponse;
import com.beautica.moderation.service.ClientBlockService.CreateBlockResult;
import com.beautica.moderation.service.ClientBlockService.NewBlock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ClientBlockFacade — phase 398 block cancels future bookings")
class ClientBlockFacadeTest {

    private static final String PHONE = "+380501234567";

    @Mock private ClientBlockService clientBlockService;
    @Mock private BookingService bookingService;
    @InjectMocks private ClientBlockFacade facade;

    private final UUID actorId = UUID.randomUUID();
    private final UUID bookingId = UUID.randomUUID();
    private final UUID masterId = UUID.randomUUID();
    private final UUID salonId = UUID.randomUUID();
    private final UUID clientId = UUID.randomUUID();

    @Test
    @DisplayName("new CLIENT block in an independent master's scope → declines by account, surfaces the visit count")
    void should_declineClientBookingsInMasterScope_when_clientBlockCreated() {
        when(clientBlockService.block(actorId, Role.INDEPENDENT_MASTER, bookingId)).thenReturn(
                new CreateBlockResult(row(), true, new NewBlock(BlockScope.master(masterId), clientId, null)));
        when(bookingService.declineFutureConfirmedForBlockedClient(actorId, masterId, null, clientId)).thenReturn(3);

        CreateBlockResult result = facade.block(actorId, Role.INDEPENDENT_MASTER, bookingId);

        assertThat(result.created()).isTrue();
        assertThat(result.block().cancelledBookings())
                .as("the cascade's VISIT count is surfaced unchanged (orchestrator decision 2026-10-28)")
                .isEqualTo(3);
        verify(bookingService, never()).declineFutureConfirmedForBlockedGuest(any(), any(), any(), any());
    }

    @Test
    @DisplayName("new GUEST block in a salon scope → declines by the OTP phone, never by account")
    void should_declineGuestBookingsInSalonScope_when_guestBlockCreated() {
        when(clientBlockService.block(actorId, Role.SALON_OWNER, bookingId)).thenReturn(
                new CreateBlockResult(row(), true, new NewBlock(BlockScope.salon(salonId), null, PHONE)));
        when(bookingService.declineFutureConfirmedForBlockedGuest(actorId, null, salonId, PHONE)).thenReturn(2);

        CreateBlockResult result = facade.block(actorId, Role.SALON_OWNER, bookingId);

        assertThat(result.created()).isTrue();
        assertThat(result.block().cancelledBookings()).isEqualTo(2);
        verify(bookingService).declineFutureConfirmedForBlockedGuest(actorId, null, salonId, PHONE);
        verify(bookingService, never()).declineFutureConfirmedForBlockedClient(any(), any(), any(), any());
    }

    @Test
    @DisplayName("idempotent repeat (created=false) → no cascade at all, count 0")
    void should_cancelNothing_when_repeatBlock() {
        when(clientBlockService.block(actorId, Role.SALON_ADMIN, bookingId))
                .thenReturn(new CreateBlockResult(row(), false));

        CreateBlockResult result = facade.block(actorId, Role.SALON_ADMIN, bookingId);

        assertThat(result.created()).isFalse();
        assertThat(result.block().cancelledBookings()).isZero();
        verifyNoInteractions(bookingService);
    }

    @Test
    @DisplayName("defensive guard: created=true without a NewBlock identity → no cascade, the row is returned as is")
    void should_skipCascade_when_createdWithoutNewBlock() {
        CreateBlockResult stored = new CreateBlockResult(row(), true, null);
        when(clientBlockService.block(actorId, Role.SALON_OWNER, bookingId)).thenReturn(stored);

        CreateBlockResult result = facade.block(actorId, Role.SALON_OWNER, bookingId);

        assertThat(result).isSameAs(stored);
        assertThat(result.block().cancelledBookings()).isZero();
        verifyNoInteractions(bookingService);
    }

    @Test
    @DisplayName("new block, nothing in the future to decline → created, count 0")
    void should_returnZero_when_noFutureBookings() {
        when(clientBlockService.block(any(), any(), any())).thenReturn(
                new CreateBlockResult(row(), true, new NewBlock(BlockScope.master(masterId), clientId, null)));
        when(bookingService.declineFutureConfirmedForBlockedClient(actorId, masterId, null, clientId)).thenReturn(0);

        CreateBlockResult result = facade.block(actorId, Role.INDEPENDENT_MASTER, bookingId);

        assertThat(result.created()).isTrue();
        assertThat(result.block().cancelledBookings()).isZero();
    }

    private static BlockResponse row() {
        return new BlockResponse(UUID.randomUUID(), BlockKind.CLIENT, "Олена К.", null, null,
                Instant.parse("2026-10-28T10:00:00Z"), 0);
    }
}
