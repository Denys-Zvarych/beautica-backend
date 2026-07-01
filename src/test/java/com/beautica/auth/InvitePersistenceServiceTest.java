package com.beautica.auth;

import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link InvitePersistenceService} — the atomic recycle + insert + enqueue unit
 * extracted from {@link InviteService}. These tests pin the internal ordering and the expired-token
 * recycle contract that used to live (and be tested) in InviteService before the REQUIRES_NEW split.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InvitePersistenceService — unit")
class InvitePersistenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-28T12:00:00Z");

    @Mock
    private InviteTokenRepository inviteTokenRepository;

    @Mock
    private NotificationOutboxService outboxService;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private InvitePersistenceService service;

    private UUID salonId;
    private String email;
    private String inviteLink;
    private String salonName;
    private Instant expiresAt;

    @BeforeEach
    void setUp() {
        service = new InvitePersistenceService(inviteTokenRepository, outboxService, clock);
        salonId = UUID.randomUUID();
        email = "master@example.com";
        inviteLink = "https://beautica.app/invite/accept?token=raw-token";
        salonName = "Test Salon";
        expiresAt = NOW.plusSeconds(48 * 3600);
    }

    @Test
    @DisplayName("persistInviteAndEnqueue inserts the token then enqueues the outbox row when no active token holds the slot")
    void should_insertTokenAndEnqueue_when_noExistingActiveToken() {
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse(email, salonId))
                .thenReturn(Optional.empty());
        when(inviteTokenRepository.saveAndFlush(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));

        service.persistInviteAndEnqueue(
                email, salonId, Role.SALON_MASTER, expiresAt, "hashed-token", inviteLink, salonName);

        // No occupant → nothing to recycle.
        verify(inviteTokenRepository, never()).delete(any());

        // The INSERT must precede the outbox enqueue (same transaction, deterministic ordering).
        InOrder inOrder = inOrder(inviteTokenRepository, outboxService);
        inOrder.verify(inviteTokenRepository).saveAndFlush(any(InviteToken.class));
        inOrder.verify(outboxService).enqueueInvite(any(), eq(email), eq(inviteLink), eq(salonName));
    }

    @Test
    @DisplayName("persistInviteAndEnqueue recycles (deletes + flushes) an expired token before inserting the new one")
    void should_recycleExpiredTokenThenInsert_when_expiredTokenOccupiesSlot() {
        var expired = new InviteToken("old-hashed", email, salonId, Role.SALON_MASTER, NOW.minusSeconds(10));
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse(email, salonId))
                .thenReturn(Optional.of(expired));
        when(inviteTokenRepository.saveAndFlush(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));

        service.persistInviteAndEnqueue(
                email, salonId, Role.SALON_MASTER, expiresAt, "hashed-token", inviteLink, salonName);

        // The expired occupant must be deleted + flushed BEFORE the new row is inserted, otherwise
        // Hibernate orders the INSERT ahead of the DELETE and collides on the unique slot.
        InOrder inOrder = inOrder(inviteTokenRepository);
        inOrder.verify(inviteTokenRepository).delete(expired);
        inOrder.verify(inviteTokenRepository).flush();
        inOrder.verify(inviteTokenRepository).saveAndFlush(any(InviteToken.class));

        verify(outboxService).enqueueInvite(any(), eq(email), eq(inviteLink), eq(salonName));
    }

    @Test
    @DisplayName("persistInviteAndEnqueue leaves a still-active concurrent token untouched and still attempts the insert")
    void should_notDeleteActiveToken_when_concurrentActiveTokenPresent() {
        // A still-active occupant means a concurrent request won the race; it must NOT be deleted,
        // so the insert below trips the unique guard and the caller resolves idempotently.
        var active = new InviteToken("active-hashed", email, salonId, Role.SALON_MASTER, NOW.plusSeconds(3600));
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse(email, salonId))
                .thenReturn(Optional.of(active));
        when(inviteTokenRepository.saveAndFlush(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));

        service.persistInviteAndEnqueue(
                email, salonId, Role.SALON_MASTER, expiresAt, "hashed-token", inviteLink, salonName);

        verify(inviteTokenRepository, never()).delete(any());
        verify(inviteTokenRepository).saveAndFlush(any(InviteToken.class));
    }

    @Test
    @DisplayName("persistInviteAndEnqueue propagates DataIntegrityViolationException and skips the enqueue when the insert trips the unique guard")
    void should_propagateDataIntegrityViolation_when_saveAndFlushTripsUniqueGuard() {
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse(email, salonId))
                .thenReturn(Optional.empty());
        when(inviteTokenRepository.saveAndFlush(any(InviteToken.class)))
                .thenThrow(new DataIntegrityViolationException("ux_invite_tokens_active"));

        assertThatThrownBy(() -> service.persistInviteAndEnqueue(
                email, salonId, Role.SALON_MASTER, expiresAt, "hashed-token", inviteLink, salonName))
                .isInstanceOf(DataIntegrityViolationException.class);

        // A losing race must NOT produce an outbox row — no second e-mail.
        verify(outboxService, never()).enqueueInvite(any(), any(), any(), any());
    }
}
