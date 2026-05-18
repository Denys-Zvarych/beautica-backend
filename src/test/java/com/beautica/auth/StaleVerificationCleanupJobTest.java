package com.beautica.auth;

import com.beautica.config.VerificationPolicyConfig;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link StaleVerificationCleanupJob}: the cutoff is derived from
 * the injected {@link Clock} (Anti-Bug Playbook §G — never bare
 * {@code Instant.now()}; tests must be able to pin the value).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("StaleVerificationCleanupJob — unit")
class StaleVerificationCleanupJobTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-18T03:17:00Z");
    private static final Duration RETENTION = Duration.ofHours(24);

    @Mock
    private UserRepository userRepository;

    @Test
    @DisplayName("should_passClockDerivedCutoff_when_sweepRuns")
    void should_passClockDerivedCutoff_when_sweepRuns() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        var policy = new VerificationPolicyConfig(10, Duration.ofMinutes(15), RETENTION);
        var job = new StaleVerificationCleanupJob(userRepository, policy, clock);
        when(userRepository.nullifyStaleVerificationCodes(FIXED_NOW.minus(RETENTION)))
                .thenReturn(7);

        int cleared = job.sweep();

        assertThat(cleared).isEqualTo(7);
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(userRepository).nullifyStaleVerificationCodes(cutoff.capture());
        assertThat(cutoff.getValue())
                .as("cutoff must be exactly clock.now - retention")
                .isEqualTo(FIXED_NOW.minus(RETENTION));
    }

    @Test
    @DisplayName("should_notFail_when_noStaleRows")
    void should_notFail_when_noStaleRows() {
        Clock clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        var policy = new VerificationPolicyConfig(10, Duration.ofMinutes(15), RETENTION);
        var job = new StaleVerificationCleanupJob(userRepository, policy, clock);
        when(userRepository.nullifyStaleVerificationCodes(FIXED_NOW.minus(RETENTION)))
                .thenReturn(0);

        // sweepStaleVerificationCodes() delegates to sweep(); a zero result must
        // not throw and must not log noise (asserted via no exception).
        job.sweepStaleVerificationCodes();

        verify(userRepository).nullifyStaleVerificationCodes(FIXED_NOW.minus(RETENTION));
    }
}
