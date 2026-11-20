package com.beautica.auth.registration;

import com.beautica.auth.TokenGenerator;
import com.beautica.common.exception.IdentityException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * B514 (D2, AUTH-41) — mints and redeems {@link RegistrationTicket}s. Reused by B520 (invite accept by phone).
 *
 * <p>Every redeem failure — unknown, used, expired, blank — is one {@code 400 REGISTRATION_TICKET_INVALID}, so the
 * endpoint is no oracle for which tickets exist.
 */
@Service
public class RegistrationTicketService {

    /** AUTH-41 — the ticket lives 15 minutes (the SMS code itself lives 10). */
    static final Duration TICKET_TTL = Duration.ofMinutes(15);

    private final RegistrationTicketRepository ticketRepository;
    private final TokenGenerator tokenGenerator;
    private final Clock clock;

    public RegistrationTicketService(RegistrationTicketRepository ticketRepository,
                                     TokenGenerator tokenGenerator,
                                     Clock clock) {
        this.ticketRepository = ticketRepository;
        this.tokenGenerator = tokenGenerator;
        this.clock = clock;
    }

    /**
     * Persists a fresh ticket for an SMS-proven phone. {@code MANDATORY}: runs inside the verify transaction, so the
     * consumed OTP and the new ticket commit together.
     *
     * @return the RAW ticket — handed to the client exactly once, never persisted or logged
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String issue(String phoneE164) {
        String rawTicket = tokenGenerator.generateToken();
        Instant expiresAt = clock.instant().plus(TICKET_TTL);
        ticketRepository.save(RegistrationTicket.issue(tokenGenerator.hash(rawTicket), phoneE164, expiresAt));
        return rawTicket;
    }

    /**
     * Redeems {@code rawTicket} under a row lock and marks it used. {@code MANDATORY}: the used flag commits (or
     * rolls back) together with the account the caller creates, so a failed registration leaves the ticket usable.
     *
     * @return the {@code +380…} phone the ticket proves
     * @throws IdentityException {@code 400 REGISTRATION_TICKET_INVALID} when unknown, used or expired
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String consume(String rawTicket) {
        if (rawTicket == null || rawTicket.isBlank()) {
            throw invalid();
        }
        RegistrationTicket ticket = ticketRepository.findByTicketHashForUpdate(tokenGenerator.hash(rawTicket))
                .orElseThrow(RegistrationTicketService::invalid);
        if (!ticket.isRedeemableAt(clock.instant())) {
            throw invalid();
        }
        ticket.markUsed();
        return ticket.getPhone();
    }

    private static IdentityException invalid() {
        return new IdentityException(IdentityException.Code.REGISTRATION_TICKET_INVALID);
    }
}
