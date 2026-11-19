package com.beautica.user.email;

import com.beautica.auth.TokenGenerator;
import com.beautica.common.cache.UserProfileCacheEvictor;
import com.beautica.common.exception.EmailAlreadyRegisteredException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.exception.ResendThrottledException;
import com.beautica.common.exception.VerificationException;
import com.beautica.config.AuthLimitsProperties;
import com.beautica.config.VerificationPolicyConfig;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.user.AfterCommitHandOff;
import com.beautica.user.CurrentPasswordRow;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import com.beautica.user.security.ContactChangeNotifier;
import com.beautica.user.security.CurrentPasswordCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * B516 (AUTH-50, CLI-50, AUTH-D11, AUTH-D17) — any account adds or changes its optional notifications email
 * («Email для сповіщень») with an emailed code. AUTH-03 code rules (6 digits, 15 min, 5 attempts per code, 60 s
 * cooldown) plus the {@code app.verification} cumulative lockout; the code mail is the existing verification template
 * ({@link EmailNotificationService#sendVerificationEmail}, no new copy).
 *
 * <p>NTF-56 / NTF-D15: the address is kept in {@link PendingEmail} until verify; only then {@link User#confirmEmail}
 * writes {@code users.email} together with {@code email_verified = true}, so business mail (which goes to
 * {@code users.email}) can never reach an unconfirmed address.
 *
 * <p>Whether another account holds the address is told only at verify (409), after the code proved possession and
 * after the 60 s cooldown — request-code never answers 409 (no registration oracle, every request pays the cooldown).
 *
 * <p>AUTH-D19: verify (add OR change) also needs the current password, checked before the code; a wrong / missing
 * one is 400 {@code CURRENT_PASSWORD_INVALID} and counts as a failed try on the code. After a successful verify the
 * account gets the security notice ({@link ContactChangeNotifier}; email to the PREVIOUS confirmed address only).
 * Wrong passwords also count against the account's lockout shared with the phone path ({@link CurrentPasswordCheck}).
 */
@Service
public class NotificationEmailService {

    private static final Logger log = LoggerFactory.getLogger(NotificationEmailService.class);

    /** AUTH-03. */
    static final Duration CODE_TTL = Duration.ofMinutes(15);
    /** AUTH-03 — after this many wrong codes the code counts as expired; default of app.auth-limits.email-code-max-verify-attempts. */
    public static final int MAX_ATTEMPTS_PER_CODE = 5;
    /** Postgres default name of {@code users.email UNIQUE} (V1). */
    static final String USERS_EMAIL_UNIQUE = "users_email_key";

    private final UserRepository userRepository;
    private final PendingEmailRepository pendingEmailRepository;
    private final TokenGenerator tokenGenerator;
    private final EmailNotificationService emailNotificationService;
    private final TaskExecutor emailExecutor;
    private final VerificationPolicyConfig verificationPolicy;
    private final UserProfileCacheEvictor userProfileCacheEvictor;
    private final CurrentPasswordCheck currentPasswordCheck;
    private final ContactChangeNotifier contactChangeNotifier;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final int maxAttemptsPerCode;

    /** Production default {@link #MAX_ATTEMPTS_PER_CODE}; kept for the tests that build it directly. */
    public NotificationEmailService(UserRepository userRepository,
                                    PendingEmailRepository pendingEmailRepository,
                                    TokenGenerator tokenGenerator,
                                    EmailNotificationService emailNotificationService,
                                    @Qualifier("emailExecutor") TaskExecutor emailExecutor,
                                    VerificationPolicyConfig verificationPolicy,
                                    UserProfileCacheEvictor userProfileCacheEvictor,
                                    CurrentPasswordCheck currentPasswordCheck,
                                    ContactChangeNotifier contactChangeNotifier,
                                    PlatformTransactionManager transactionManager,
                                    Clock clock) {
        this(userRepository, pendingEmailRepository, tokenGenerator, emailNotificationService, emailExecutor,
                verificationPolicy, userProfileCacheEvictor, currentPasswordCheck, contactChangeNotifier,
                transactionManager, clock, AuthLimitsProperties.defaults());
    }

    @Autowired
    public NotificationEmailService(UserRepository userRepository,
                                    PendingEmailRepository pendingEmailRepository,
                                    TokenGenerator tokenGenerator,
                                    EmailNotificationService emailNotificationService,
                                    @Qualifier("emailExecutor") TaskExecutor emailExecutor,
                                    VerificationPolicyConfig verificationPolicy,
                                    UserProfileCacheEvictor userProfileCacheEvictor,
                                    CurrentPasswordCheck currentPasswordCheck,
                                    ContactChangeNotifier contactChangeNotifier,
                                    PlatformTransactionManager transactionManager,
                                    Clock clock,
                                    AuthLimitsProperties limits) {
        this.maxAttemptsPerCode = limits.emailCodeMaxVerifyAttempts();
        this.userRepository = userRepository;
        this.pendingEmailRepository = pendingEmailRepository;
        this.tokenGenerator = tokenGenerator;
        this.emailNotificationService = emailNotificationService;
        this.emailExecutor = emailExecutor;
        this.verificationPolicy = verificationPolicy;
        this.userProfileCacheEvictor = userProfileCacheEvictor;
        this.currentPasswordCheck = currentPasswordCheck;
        this.contactChangeNotifier = contactChangeNotifier;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Stores the pending address with a fresh code and mails the code after commit. Never 409 (see class Javadoc).
     *
     * @throws ResendThrottledException 429 inside the 60 s cooldown
     */
    @Transactional
    public void requestCode(UUID userId, String rawEmail) {
        String email = normalise(rawEmail);
        lockUser(userId);
        Instant now = clock.instant();
        PendingEmail pending = pendingEmailRepository.findById(userId).orElseGet(() -> PendingEmail.forUser(userId));
        if (pending.getSentAt() != null) {
            Instant nextAllowed = pending.getSentAt().plus(verificationPolicy.resendCooldown());
            if (now.isBefore(nextAllowed)) {
                throw new ResendThrottledException(Duration.between(now, nextAllowed).getSeconds() + 1);
            }
        }
        String rawCode = tokenGenerator.generateOtp();
        pending.issue(email, tokenGenerator.hashOtp(rawCode), now, now.plus(CODE_TTL));
        pendingEmailRepository.save(pending);
        mailAfterCommit(email, rawCode);
    }

    /**
     * Confirms the code and stores the address on the account.
     *
     * <p>{@code NOT_SUPPORTED}: the password pre-read and BCrypt run with no transaction (no pooled connection held
     * for 80–150 ms); the locked part then runs in its own transaction ({@link TransactionTemplate}). A
     * {@link VerificationException} is carried out of that transaction so it COMMITS (the old
     * {@code noRollbackFor}): the attempt / cumulative counters must persist on a wrong code (anti-brute-force, as
     * {@code EmailVerificationProcessor}). Any other exception — the 409 — rolls back (the code stays usable).
     *
     * @param currentPassword the account's current password (AUTH-D19, add and change)
     * @throws VerificationException           400 {@code CURRENT_PASSWORD_INVALID} (wrong / missing password on a live
     *                                         code; one failed try) / {@code INVALID_CODE} (wrong code, other address,
     *                                         no request, locked) / {@code CODE_EXPIRED} (expired or 5 failed tries)
     * @throws EmailAlreadyRegisteredException 409 when another account holds the address
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void verifyCode(UUID userId, String rawEmail, String code, String currentPassword) {
        String email = normalise(rawEmail);
        boolean passwordOk = checkPasswordBeforeLock(userId, currentPassword);
        VerificationException failure = transactionTemplate.execute(status -> {
            try {
                verifyLocked(userId, email, code, passwordOk);
                return null;
            } catch (VerificationException e) {
                return e; // returned, not thrown: the transaction commits the failed-try counters
            }
        });
        if (failure != null) {
            throw failure;
        }
    }

    /** The row-locked part, inside the transaction {@link #verifyCode} opens. */
    private void verifyLocked(UUID userId, String email, String code, boolean passwordOk) {
        User user = lockUser(userId);
        Instant now = clock.instant();
        PendingEmail pending = pendingEmailRepository.findById(userId)
                .filter(row -> row.getEmail().equals(email))
                .orElseThrow(() -> invalid(code));
        if (pending.isLocked(now)) {
            throw invalid(code);
        }
        if (!pending.getExpiresAt().isAfter(now) || pending.getAttempts() >= maxAttemptsPerCode) {
            throw new VerificationException(VerificationException.Code.CODE_EXPIRED);
        }
        if (!passwordOk) {
            throw failedTry(pending, now, VerificationException.Code.CURRENT_PASSWORD_INVALID);
        }
        if (!matches(code, pending.getCodeHash())) {
            throw failedTry(pending, now, VerificationException.Code.INVALID_CODE);
        }
        if (userRepository.existsByEmailOnOtherUser(email, userId)) {
            throw new EmailAlreadyRegisteredException();
        }
        String previousConfirmedEmail = user.hasConfirmedEmail() ? user.getEmail() : null;
        user.confirmEmail(email);
        saveClaimingEmail(user);
        pendingEmailRepository.delete(pending);
        userProfileCacheEvictor.evictAfterCommit(userId);
        contactChangeNotifier.emailChanged(userId, previousConfirmedEmail, email);
    }

    /** One failed try on the code (per-code + cumulative counters; commits through {@code noRollbackFor}). */
    private VerificationException failedTry(PendingEmail pending, Instant now, VerificationException.Code code) {
        pending.recordFailure(verificationPolicy.cumulativeFailureThreshold(),
                now.plus(verificationPolicy.lockoutDuration()));
        return new VerificationException(code);
    }

    /**
     * BCrypt (80–150 ms) on a non-locking pre-read, outside any transaction and never under the row lock; the result counts against the
     * account's lockout shared with the phone path ({@link CurrentPasswordCheck}) whatever the code turns out to be.
     */
    private boolean checkPasswordBeforeLock(UUID userId, String currentPassword) {
        CurrentPasswordRow row = userRepository.findCurrentPasswordRow(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        return currentPasswordCheck.verify(userId, row.passwordHash(), currentPassword);
    }

    private User lockUser(UUID userId) {
        return userRepository.findByIdForUpdate(userId).orElseThrow(() -> new NotFoundException("User not found"));
    }

    /** A concurrent claim of the same address hits {@code users_email_key} here as a clean 409, never a 500. */
    private void saveClaimingEmail(User user) {
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            if (String.valueOf(e.getMostSpecificCause().getMessage()).contains(USERS_EMAIL_UNIQUE)) {
                throw new EmailAlreadyRegisteredException();
            }
            throw e;
        }
    }

    private boolean matches(String code, String storedHash) {
        return MessageDigest.isEqual(tokenGenerator.hashOtp(code).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));
    }

    /** Pays the same hash cost as a real comparison so "no pending row" is not faster than "wrong code". */
    private VerificationException invalid(String code) {
        tokenGenerator.hashOtp(code);
        return new VerificationException(VerificationException.Code.INVALID_CODE);
    }

    /** Saturated {@code emailExecutor} after commit → logged and dropped, never a 500 (the change is committed). */
    private void mailAfterCommit(String email, String rawCode) {
        AfterCommitHandOff.submit(emailExecutor,
                () -> emailNotificationService.sendVerificationEmail(email, rawCode), log, "notification-email code");
    }

    private static String normalise(String rawEmail) {
        return rawEmail.strip().toLowerCase(Locale.ROOT);
    }
}
