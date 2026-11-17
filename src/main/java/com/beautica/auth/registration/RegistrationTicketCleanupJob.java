package com.beautica.auth.registration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * B514 — daily hard-delete of registration tickets expired more than a day ago. Mirrors
 * {@code PasswordResetTokenCleanupJob}: one bounded {@code DELETE}, cutoff from the injected {@link Clock}. An
 * expired ticket can never be redeemed, so deletion is lossless.
 */
@Component
public class RegistrationTicketCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RegistrationTicketCleanupJob.class);
    static final Duration RETENTION = Duration.ofDays(1);

    private final RegistrationTicketRepository ticketRepository;
    private final Clock clock;

    public RegistrationTicketCleanupJob(RegistrationTicketRepository ticketRepository, Clock clock) {
        this.ticketRepository = ticketRepository;
        this.clock = clock;
    }

    /** Daily at 03:52 UTC — offset from the password-reset sweep at 03:42. */
    @Scheduled(cron = "${app.registration-ticket.cleanup-cron:0 52 3 * * *}", zone = "UTC")
    @Transactional
    public void sweepExpiredTickets() {
        int deleted = sweep();
        if (deleted > 0) {
            log.info("Registration ticket cleanup: deleted {} expired ticket row(s)", deleted);
        }
    }

    /** Package-private seam for tests. */
    @Transactional
    int sweep() {
        Instant cutoff = clock.instant().minus(RETENTION);
        return ticketRepository.deleteByExpiresAtBefore(cutoff);
    }
}
