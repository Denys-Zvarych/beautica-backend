package com.beautica.auth;

import com.beautica.config.InviteSmsPolicyConfig;
import com.beautica.user.InviteTokenRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B520 (D5) — the retention cutoff is "now − 30 days" from the injected clock. */
@ExtendWith(MockitoExtension.class)
@DisplayName("InviteTokenCleanupJob — 30-day retention cutoff (B520)")
class InviteTokenCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-11-18T03:52:00Z");

    @Mock
    private InviteTokenRepository inviteTokenRepository;

    @Test
    @DisplayName("sweep deletes unaccepted phone invites ended before now − retention and returns the count")
    void should_deleteWithThirtyDayCutoff_when_sweepRuns() {
        var job = new InviteTokenCleanupJob(inviteTokenRepository,
                new InviteSmsPolicyConfig(10, 30, Duration.ofDays(30)), Clock.fixed(NOW, ZoneOffset.UTC));
        when(inviteTokenRepository.deleteUnacceptedPhoneInvitesEndedBefore(NOW.minus(Duration.ofDays(30))))
                .thenReturn(3);

        int deleted = job.sweep();

        assertThat(deleted).isEqualTo(3);
        verify(inviteTokenRepository).deleteUnacceptedPhoneInvitesEndedBefore(NOW.minus(Duration.ofDays(30)));
    }
}
