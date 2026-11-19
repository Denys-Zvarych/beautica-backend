package com.beautica.auth.registration;

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

@ExtendWith(MockitoExtension.class)
@DisplayName("RegistrationTicketCleanupJob — unit")
class RegistrationTicketCleanupJobTest {

    private static final Instant NOW = Instant.parse("2026-11-19T03:52:00Z");

    @Mock
    private RegistrationTicketRepository ticketRepository;

    @Test
    @DisplayName("should_deleteTicketsExpiredMoreThanADayAgo_when_sweepRuns")
    void should_deleteTicketsExpiredMoreThanADayAgo_when_sweepRuns() {
        var job = new RegistrationTicketCleanupJob(ticketRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        when(ticketRepository.deleteByExpiresAtBefore(NOW.minus(Duration.ofDays(1)))).thenReturn(3);

        int deleted = job.sweep();

        assertThat(deleted).isEqualTo(3);
        assertThat(RegistrationTicketCleanupJob.RETENTION).isEqualTo(Duration.ofDays(1));
    }

    @Test
    @DisplayName("should_runTheSameSweep_when_scheduled")
    void should_runTheSameSweep_when_scheduled() {
        var job = new RegistrationTicketCleanupJob(ticketRepository, Clock.fixed(NOW, ZoneOffset.UTC));

        job.sweepExpiredTickets();

        verify(ticketRepository).deleteByExpiresAtBefore(NOW.minus(Duration.ofDays(1)));
    }
}
