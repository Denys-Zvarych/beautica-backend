package com.beautica.auth;

import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.RevocationReason;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
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
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalseAndRevokedAtIsNull(email, salonId))
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
    @DisplayName("persistInviteAndEnqueue SUPERSEDES (never deletes) an expired token, flushing that "
            + "UPDATE before the new row is inserted")
    void should_supersedeExpiredTokenThenInsert_when_expiredTokenOccupiesSlot() {
        var expired = new InviteToken("old-hashed", email, salonId, Role.SALON_MASTER, NOW.minusSeconds(10));
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalseAndRevokedAtIsNull(email, salonId))
                .thenReturn(Optional.of(expired));
        when(inviteTokenRepository.saveAndFlush(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));

        service.persistInviteAndEnqueue(
                email, salonId, Role.SALON_MASTER, expiresAt, "hashed-token", inviteLink, salonName);

        // The expired occupant is KEPT as history. Hard-deleting it (the pre-V153 behaviour) erased
        // the invite from GET /salons/{salonId}/invites entirely.
        verify(inviteTokenRepository, never()).delete(any());
        assertThat(expired.getRevokedReason())
                .as("the displaced occupant must be marked SUPERSEDED, never removed")
                .isEqualTo(RevocationReason.SUPERSEDED);
        assertThat(expired.getRevokedAt())
                .as("revokedAt must come from the injected Clock, not a bare Instant.now()")
                .isEqualTo(NOW);
        assertThat(expired.isUsed())
                .as("a superseded invite was never accepted — flipping isUsed would make the history "
                        + "endpoint report it as ACCEPTED")
                .isFalse();

        // The supersede UPDATE must be flushed BEFORE the new row is inserted: Hibernate's
        // ActionQueue runs EntityInsertAction ahead of EntityUpdateAction, so an unflushed
        // supersede leaves the old row still holding ux_invite_tokens_active when the INSERT
        // lands, producing a spurious DataIntegrityViolationException and a silently-dropped
        // invite. saveAndFlush(expired) must therefore precede saveAndFlush(<new token>).
        InOrder inOrder = inOrder(inviteTokenRepository);
        inOrder.verify(inviteTokenRepository).saveAndFlush(same(expired));
        inOrder.verify(inviteTokenRepository).saveAndFlush(argThat(t -> t != expired));

        verify(outboxService).enqueueInvite(any(), eq(email), eq(inviteLink), eq(salonName));
    }

    @Test
    @DisplayName("persistInviteAndEnqueue leaves a still-active concurrent token untouched and still attempts the insert")
    void should_notDeleteActiveToken_when_concurrentActiveTokenPresent() {
        // A still-active occupant means a concurrent request won the race; it must NOT be deleted,
        // so the insert below trips the unique guard and the caller resolves idempotently.
        var active = new InviteToken("active-hashed", email, salonId, Role.SALON_MASTER, NOW.plusSeconds(3600));
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalseAndRevokedAtIsNull(email, salonId))
                .thenReturn(Optional.of(active));
        when(inviteTokenRepository.saveAndFlush(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));

        service.persistInviteAndEnqueue(
                email, salonId, Role.SALON_MASTER, expiresAt, "hashed-token", inviteLink, salonName);

        verify(inviteTokenRepository, never()).delete(any());
        assertThat(active.getRevokedAt())
                .as("the race winner's still-live invite must NOT be retired — superseding it would "
                        + "hand the slot to the loser and silently invalidate a link already e-mailed")
                .isNull();
        assertThat(active.getRevokedReason()).isNull();
        verify(inviteTokenRepository, never()).saveAndFlush(same(active));
        verify(inviteTokenRepository).saveAndFlush(argThat(t -> t != active));
    }

    @Test
    @DisplayName("persistInviteAndEnqueue propagates DataIntegrityViolationException and skips the enqueue when the insert trips the unique guard")
    void should_propagateDataIntegrityViolation_when_saveAndFlushTripsUniqueGuard() {
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalseAndRevokedAtIsNull(email, salonId))
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
