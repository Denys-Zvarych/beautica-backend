package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.registration.RegistrationTicketService;
import com.beautica.common.exception.IdentityException;
import com.beautica.common.validation.TermsConsentPayload;
import com.beautica.master.service.MasterService;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

/**
 * B514 (AUTH-02, AUTH-D11) — creates a self-registered account from a registration ticket: the phone the ticket
 * proves becomes the account's verified phone, no email is stored, and the session starts at once.
 *
 * <p>Called by {@link AuthService#register} / {@link AuthService#registerIndependentMaster} inside their
 * transaction ({@code MANDATORY}): ticket consumption, the user row and (for an independent master) the master
 * profile commit or roll back together, so a failed registration leaves the ticket redeemable.
 */
@Service
public class PhoneRegistrationService {

    /** V244 partial unique index on verified phones. */
    static final String VERIFIED_PHONE_INDEX = "ux_users_verified_phone";

    private final RegistrationTicketService ticketService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MasterService masterService;
    private final AuthResponseBuilder authResponseBuilder;
    private final Clock clock;

    public PhoneRegistrationService(RegistrationTicketService ticketService,
                                    UserRepository userRepository,
                                    PasswordEncoder passwordEncoder,
                                    MasterService masterService,
                                    AuthResponseBuilder authResponseBuilder,
                                    Clock clock) {
        this.ticketService = ticketService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.masterService = masterService;
        this.authResponseBuilder = authResponseBuilder;
        this.clock = clock;
    }

    /** Self-registration fields of a phone account; {@code businessName} only for SALON_OWNER. */
    public record PhoneAccount(String registrationTicket, String password, Role role, String firstName,
                               String lastName, String businessName, TermsConsentPayload consent) {

        /** Carries the password — never print it. */
        @Override
        public String toString() {
            return "PhoneAccount[role=" + role + "]";
        }
    }

    /**
     * @throws IdentityException 400 {@code REGISTRATION_TICKET_INVALID}; 409 {@code PHONE_ALREADY_REGISTERED} when a
     *                           verified account took the phone after the ticket was issued
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public AuthResponse register(PhoneAccount account) {
        // BCrypt before consume: consume row-locks the ticket, so hashing first keeps the lock and connection short.
        String passwordHash = passwordEncoder.encode(account.password());
        String phone = ticketService.consume(account.registrationTicket());
        assertPhoneFree(phone);
        Instant now = clock.instant();
        User user = User.registerWithVerifiedPhone(phone, passwordHash,
                account.role(), account.firstName(), blankToNull(account.lastName()), account.businessName(), now);
        TermsConsentStamp.apply(user, account.consent(), now);
        User saved = saveClaimingPhone(userRepository, user);
        if (account.role() == Role.INDEPENDENT_MASTER) {
            masterService.createMasterForIndependentUser(saved.getId());
        }
        return authResponseBuilder.buildAuthResponse(saved);
    }

    /** AUTH-42 — fast path; the partial unique index is the race-proof backstop ({@link #saveClaimingPhone}). */
    void assertPhoneFree(String phoneE164) {
        if (userRepository.existsByVerifiedPhone(phoneE164)) {
            throw phoneTaken();
        }
    }

    /**
     * Flushes so a concurrent claim of the same phone hits {@code ux_users_verified_phone} here, as a clean
     * 409 {@code PHONE_ALREADY_REGISTERED}, instead of a 500 at commit. The transaction is rollback-only afterwards
     * either way. The ONE save path for every flow that persists a user with a verified phone (self-registration,
     * invite accept — {@link InviteService}); never re-implement the index-name match.
     */
    static User saveClaimingPhone(UserRepository userRepository, User user) {
        try {
            return userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            String cause = String.valueOf(e.getMostSpecificCause().getMessage());
            if (cause.contains(VERIFIED_PHONE_INDEX)) {
                throw phoneTaken();
            }
            throw e;
        }
    }

    private static IdentityException phoneTaken() {
        return new IdentityException(IdentityException.Code.PHONE_ALREADY_REGISTERED);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
