package com.beautica.auth;

import com.beautica.common.exception.IdentityException;
import com.beautica.common.security.PhoneHasher;
import com.beautica.config.InviteSmsPolicyConfig;
import com.beautica.config.PhoneHashPepperConfig;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.user.InviteSmsPhoneSend;
import com.beautica.user.InviteSmsPhoneSendRepository;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.RevocationReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B520 — {@link InvitePersistenceService}: the caps (AUTH-52) under their locks, then recycle + insert + SMS enqueue,
 * all in one unit.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InvitePersistenceService — phone invite unit (B520)")
class InvitePersistenceServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
    private static final String PHONE = "+380501234567";
    private static final String LINK = "https://beautica.app/invite/accept?token=raw-token";

    @Mock private InviteTokenRepository inviteTokenRepository;
    @Mock private NotificationOutboxService outboxService;
    @Mock private InviteSmsPhoneSendRepository phoneSendRepository;

    private final PhoneHasher phoneHasher =
            new PhoneHasher(new PhoneHashPepperConfig("unit-test-phone-hash-pepper-min-32-chars"));

    private InvitePersistenceService service;
    private final UUID salonId = UUID.randomUUID();
    private final UUID inviterId = UUID.randomUUID();
    private InvitePersistenceService.PhoneInvite invite;

    @BeforeEach
    void setUp() {
        service = new InvitePersistenceService(inviteTokenRepository, outboxService, phoneSendRepository, phoneHasher,
                new InviteSmsPolicyConfig(10, 30, Duration.ofDays(30)), Clock.fixed(NOW, ZoneOffset.UTC));
        invite = new InvitePersistenceService.PhoneInvite(PHONE, salonId, Role.SALON_MASTER,
                NOW.plus(Duration.ofHours(48)), "hashed-token", LINK, "Студія", inviterId);
    }

    @Test
    @DisplayName("locks (inviter, salon, phone), counts all three caps, inserts the phone invite, then enqueues ONE SMS row")
    void should_lockCountInsertAndEnqueue_when_underBothCaps() {
        when(inviteTokenRepository.saveAndFlush(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));

        service.persistInviteAndEnqueue(invite);

        InOrder order = inOrder(inviteTokenRepository, phoneSendRepository, outboxService);
        order.verify(inviteTokenRepository).acquireInviteSmsCapLocks(inviterId, salonId);
        order.verify(inviteTokenRepository).acquireInvitePhoneCapLock(PHONE);
        order.verify(inviteTokenRepository).countPhoneInvitesByInviterSince(inviterId, NOW.minus(Duration.ofHours(1)));
        order.verify(inviteTokenRepository).countPhoneInvitesBySalonSince(salonId, NOW.minus(Duration.ofDays(1)));
        order.verify(phoneSendRepository).countByPhoneHashSince(eq(phoneHash()), eq(NOW.minus(Duration.ofDays(1))));
        var captor = ArgumentCaptor.forClass(InviteToken.class);
        order.verify(inviteTokenRepository).saveAndFlush(captor.capture());
        order.verify(outboxService).enqueueStaffInviteSms(any(), eq(PHONE), eq(LINK), eq("Студія"));
        var sendCaptor = ArgumentCaptor.forClass(InviteSmsPhoneSend.class);
        order.verify(phoneSendRepository).save(sendCaptor.capture());
        assertThat(sendCaptor.getValue().getPhoneHash()).as("durable log keyed by the HMAC, never the phone")
                .isEqualTo(phoneHash());
        assertThat(sendCaptor.getValue().getSentAt()).isEqualTo(NOW);
        InviteToken saved = captor.getValue();
        assertThat(saved.getPhone()).isEqualTo(PHONE);
        assertThat(saved.getEmail()).isNull();
        assertThat(saved.getInvitedBy()).isEqualTo(inviterId);
        assertThat(saved.getToken()).isEqualTo("hashed-token");
        assertThat(saved.getRole()).isEqualTo(Role.SALON_MASTER);
    }

    @Test
    @DisplayName("the inviter's 11th invite SMS within the hour → 429 INVITE_SMS_LIMIT, nothing inserted or enqueued")
    void should_throwSmsLimit_when_inviterHourlyCapReached() {
        when(inviteTokenRepository.countPhoneInvitesByInviterSince(any(), any())).thenReturn(10L);

        var thrown = catchThrowableOfType(() -> service.persistInviteAndEnqueue(invite), IdentityException.class);

        assertThat(thrown.getCode()).isEqualTo(IdentityException.Code.INVITE_SMS_LIMIT);
        verify(inviteTokenRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxService);
    }

    @Test
    @DisplayName("the salon's 31st invite SMS within 24 h → 429 INVITE_SMS_LIMIT, nothing inserted or enqueued")
    void should_throwSmsLimit_when_salonDailyCapReached() {
        when(inviteTokenRepository.countPhoneInvitesByInviterSince(any(), any())).thenReturn(9L);
        when(inviteTokenRepository.countPhoneInvitesBySalonSince(any(), any())).thenReturn(30L);

        var thrown = catchThrowableOfType(() -> service.persistInviteAndEnqueue(invite), IdentityException.class);

        assertThat(thrown.getCode()).isEqualTo(IdentityException.Code.INVITE_SMS_LIMIT);
        verify(inviteTokenRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxService);
    }

    @Test
    @DisplayName("AUTH-D14: the phone's 4th invite SMS within 24 h (any salon, any inviter) → 429, nothing inserted")
    void should_throwSmsLimit_when_phoneDailyCapReached() {
        when(phoneSendRepository.countByPhoneHashSince(eq(phoneHash()), eq(NOW.minus(Duration.ofDays(1)))))
                .thenReturn((long) InviteSmsPolicyConfig.DEFAULT_PER_PHONE_PER_DAY);

        var thrown = catchThrowableOfType(() -> service.persistInviteAndEnqueue(invite), IdentityException.class);

        assertThat(thrown.getCode()).isEqualTo(IdentityException.Code.INVITE_SMS_LIMIT);
        verify(inviteTokenRepository, never()).saveAndFlush(any());
        verify(phoneSendRepository, never()).save(any());
        verifyNoInteractions(outboxService);
    }

    @Test
    @DisplayName("just under both caps (9 / 29) still sends")
    void should_send_when_justUnderBothCaps() {
        when(inviteTokenRepository.countPhoneInvitesByInviterSince(any(), any())).thenReturn(9L);
        when(inviteTokenRepository.countPhoneInvitesBySalonSince(any(), any())).thenReturn(29L);
        when(inviteTokenRepository.saveAndFlush(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));

        service.persistInviteAndEnqueue(invite);

        verify(outboxService).enqueueStaffInviteSms(any(), eq(PHONE), eq(LINK), eq("Студія"));
    }

    @Test
    @DisplayName("an EXPIRED invite holding the (salon, phone) slot is superseded and flushed BEFORE the insert")
    void should_supersedeExpiredOccupant_when_reinvitingSamePhone() {
        var expired = InviteToken.forPhone("old", PHONE, salonId, Role.SALON_MASTER, NOW, inviterId);
        when(inviteTokenRepository.findByPhoneAndSalonIdAndIsUsedFalseAndRevokedAtIsNull(PHONE, salonId))
                .thenReturn(Optional.of(expired));
        when(inviteTokenRepository.saveAndFlush(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));

        service.persistInviteAndEnqueue(invite);

        assertThat(expired.getRevokedReason()).isEqualTo(RevocationReason.SUPERSEDED);
        assertThat(expired.getRevokedAt()).isEqualTo(NOW);
        InOrder order = inOrder(inviteTokenRepository);
        order.verify(inviteTokenRepository).saveAndFlush(expired);
        order.verify(inviteTokenRepository).saveAndFlush(any(InviteToken.class));
    }

    @Test
    @DisplayName("a still-LIVE occupant is left untouched — the insert trips the unique guard and nothing is enqueued")
    void should_propagateUniqueViolationWithoutEnqueue_when_liveOccupantExists() {
        var live = InviteToken.forPhone("live", PHONE, salonId, Role.SALON_MASTER, NOW.plusSeconds(60), inviterId);
        when(inviteTokenRepository.findByPhoneAndSalonIdAndIsUsedFalseAndRevokedAtIsNull(PHONE, salonId))
                .thenReturn(Optional.of(live));
        when(inviteTokenRepository.saveAndFlush(any(InviteToken.class)))
                .thenThrow(new DataIntegrityViolationException("ux_invite_tokens_active_phone"));

        assertThatThrownBy(() -> service.persistInviteAndEnqueue(invite))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(live.getRevokedAt()).isNull();
        verifyNoInteractions(outboxService);
    }

    @Test
    @DisplayName("PhoneInvite.toString never prints the phone or the link")
    void should_hidePhoneAndLink_when_phoneInvitePrinted() {
        assertThat(invite.toString()).doesNotContain(PHONE).doesNotContain(LINK).doesNotContain("hashed-token");
    }

    @Test
    @DisplayName("B520 audit-fix 2: the daily purge drops per-phone send-log rows older than two days (24 h window + slack)")
    void should_purgeSendLogPastRetention_when_purgeRuns() {
        service.purgeExpiredPhoneSends();

        verify(phoneSendRepository).deleteSentBefore(NOW.minus(Duration.ofDays(2)));
    }

    private byte[] phoneHash() {
        return phoneHasher.hash(PHONE).hash();
    }
}
