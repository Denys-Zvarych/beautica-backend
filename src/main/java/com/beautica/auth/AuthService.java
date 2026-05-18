package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RefreshRequest;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.RegistrationResponse;
import com.beautica.auth.dto.ResendVerificationRequest;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.auth.dto.VerifyEmailRequest;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.EmailNotVerifiedException;
import com.beautica.common.exception.ResendThrottledException;
import com.beautica.common.exception.VerificationException;
import com.beautica.master.service.MasterService;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;


@Service
public class AuthService {

    private static final Duration OTP_TTL = Duration.ofMinutes(15);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenGenerator tokenGenerator;
    private final MasterService masterService;
    private final AuthResponseBuilder authResponseBuilder;
    private final Clock clock;
    private final EmailNotificationService emailNotificationService;
    private final TaskExecutor emailExecutor;
    private final EmailVerificationProcessor emailVerificationProcessor;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            TokenGenerator tokenGenerator,
            MasterService masterService,
            AuthResponseBuilder authResponseBuilder,
            Clock clock,
            EmailNotificationService emailNotificationService,
            @Qualifier("emailExecutor") TaskExecutor emailExecutor,
            EmailVerificationProcessor emailVerificationProcessor
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenGenerator = tokenGenerator;
        this.masterService = masterService;
        this.authResponseBuilder = authResponseBuilder;
        this.clock = clock;
        this.emailNotificationService = emailNotificationService;
        this.emailExecutor = emailExecutor;
        this.emailVerificationProcessor = emailVerificationProcessor;
    }

    @Transactional
    public RegistrationResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT).strip();

        // Return the same 200 response for already-registered emails to prevent
        // enumeration attacks — callers cannot distinguish new from existing registrations.
        if (userRepository.existsByEmail(email)) {
            return RegistrationResponse.of(email);
        }

        if (request.role() == SelfRegistrationRole.SALON_OWNER) {
            if (request.businessName() == null || request.businessName().isBlank()) {
                throw new BusinessException(HttpStatus.BAD_REQUEST,
                        "businessName is required for SALON_OWNER");
            }
        }

        String businessName = request.role() == SelfRegistrationRole.SALON_OWNER
                ? request.businessName()
                : null;

        String rawOtp = tokenGenerator.generateOtp();

        var user = new User(
                email,
                passwordEncoder.encode(request.password()),
                request.role().toRole(),
                request.firstName(),
                request.lastName(),
                request.phoneNumber(),
                businessName
        );
        user.setVerificationCodeHash(tokenGenerator.hashOtp(rawOtp));
        user.setVerificationCodeExpiresAt(clock.instant().plus(OTP_TTL));

        var savedUser = userRepository.save(user);

        scheduleVerificationEmail(savedUser.getEmail(), rawOtp);

        return RegistrationResponse.of(savedUser.getEmail());
    }

    @Transactional
    public RegistrationResponse registerIndependentMaster(RegisterIndependentMasterRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT).strip();

        // Return the same 200 response for already-registered emails to prevent
        // enumeration attacks — callers cannot distinguish new from existing registrations.
        if (userRepository.existsByEmail(email)) {
            return RegistrationResponse.of(email);
        }

        String rawOtp = tokenGenerator.generateOtp();

        var user = new User(
                email,
                passwordEncoder.encode(request.password()),
                Role.INDEPENDENT_MASTER,
                request.firstName(),
                request.lastName(),
                request.phoneNumber()
        );
        user.setVerificationCodeHash(tokenGenerator.hashOtp(rawOtp));
        user.setVerificationCodeExpiresAt(clock.instant().plus(OTP_TTL));

        var savedUser = userRepository.save(user);

        // Master profile requires the persisted user ID — created after save.
        masterService.createMasterForIndependentUser(savedUser.getId());

        scheduleVerificationEmail(savedUser.getEmail(), rawOtp);

        return RegistrationResponse.of(savedUser.getEmail());
    }

    /**
     * Schedules a verification email to be sent after the current transaction commits.
     * When no active transaction synchronization exists (e.g. in unit tests where the
     * {@code @Transactional} proxy is bypassed), the email is sent immediately so
     * tests can still verify the call without standing up a transaction manager.
     */
    private void scheduleVerificationEmail(String email, String rawOtp) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            emailExecutor.execute(() ->
                                    emailNotificationService.sendVerificationEmail(email, rawOtp));
                        }
                    }
            );
        } else {
            emailExecutor.execute(() ->
                    emailNotificationService.sendVerificationEmail(email, rawOtp));
        }
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT).strip();
        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        if (!user.isActive()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
        }

        // By design: the 403 response reveals that the password was correct and the account is
        // unverified. The caller (the account owner) needs this signal to route to the
        // verification screen. An attacker reaching this branch already knows the correct
        // password — the verification state is not the secret being protected here.
        if (!user.isEmailVerified()) {
            throw new EmailNotVerifiedException(user.getEmail());
        }

        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        var storedToken = refreshTokenRepository.findByToken(tokenGenerator.hash(request.refreshToken()))
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "Refresh token not found"));

        if (storedToken.isRevoked()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Refresh token has been revoked");
        }

        if (storedToken.getExpiresAt().isBefore(clock.instant())) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Refresh token has expired");
        }

        storedToken.revoke();
        refreshTokenRepository.save(storedToken);

        var user = userRepository.findById(storedToken.getUserId())
                .orElseThrow(() -> new BusinessException(HttpStatus.UNAUTHORIZED, "User not found"));

        if (!user.isActive()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Refresh token not found");
        }

        if (!user.isEmailVerified()) {
            throw new BusinessException(HttpStatus.UNAUTHORIZED, "Refresh token not found");
        }

        return buildAuthResponse(user);
    }

    /**
     * Verifies the submitted OTP and, on success, issues an auth response.
     *
     * <p>Performance: the pessimistic row lock is held ONLY for the attempt /
     * cumulative-lock / constant-time-match critical section
     * ({@link #runVerificationCriticalSection}). JWT signing and the
     * refresh-token INSERT happen AFTER that transaction commits and the lock
     * is released, so concurrent verifies are no longer serialised across the
     * full token round-trip.
     *
     * <p>Anti-enumeration: unknown-email, already-verified, exhausted, locked
     * and wrong-code all surface as the generic {@code INVALID_CODE} /
     * {@code CODE_EXPIRED} shapes — no status, body, exception-code or timing
     * difference. The unknown-email branch performs a decoy hash + constant-time
     * compare so it is time-equivalent to a wrong-code attempt on a real user.
     */
    public AuthResponse verifyEmail(VerifyEmailRequest request) {
        // The locked critical section runs in EmailVerificationProcessor's
        // @Transactional proxy. Token issuance happens AFTER that transaction
        // commits and the PESSIMISTIC_WRITE lock is released — so concurrent
        // verifies are no longer serialised across JWT signing + the
        // refresh-token INSERT (a separate bean is required because a this.-call
        // to a @Transactional method bypasses the proxy — Anti-Bug §F3).
        UUID verifiedUserId = emailVerificationProcessor.verifyAndReturnUserId(request);
        User user = userRepository.findById(verifiedUserId)
                .orElseThrow(() -> new VerificationException(VerificationException.Code.INVALID_CODE));
        return buildAuthResponse(user);
    }

    /**
     * Resends a verification OTP to the given email address, subject to a 60-second
     * per-account cooldown.
     *
     * <p>Lock minimisation: a non-locking {@link UserRepository#findByEmail} read
     * runs first. Unknown-email and already-verified branches take NO row lock
     * (they short-circuit). The {@code PESSIMISTIC_WRITE} lock is escalated only
     * on the real write path (user exists, unverified, code present).
     *
     * <p>Anti-enumeration: unknown emails, already-verified accounts and locked
     * accounts all return the same success-shaped {@link RegistrationResponse}
     * without sending mail. The response is wire-identical to a real resend.
     *
     * <p>Throttle derivation: we do not store a dedicated {@code sent_at} column.
     * {@code issuedAt} is derived as {@code verificationCodeExpiresAt - OTP_TTL}.
     * If {@code clock.now} has not passed {@code issuedAt + RESEND_COOLDOWN},
     * {@link ResendThrottledException} is thrown.
     */
    @Transactional
    public RegistrationResponse resendVerification(ResendVerificationRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT).strip();

        // Non-locking pre-read — unknown / verified take NO row lock.
        var preReadOpt = userRepository.findByEmail(email);
        if (preReadOpt.isEmpty()
                || preReadOpt.get().isEmailVerified()
                || preReadOpt.get().getVerificationCodeHash() == null) {
            return RegistrationResponse.of(email);
        }

        // Real write path — escalate to the pessimistic lock now (closes the
        // TOCTOU window between the cooldown read and the OTP write).
        var user = userRepository.findByEmailForUpdate(email)
                .orElse(null);
        if (user == null || user.isEmailVerified() || user.getVerificationCodeHash() == null) {
            return RegistrationResponse.of(email);
        }

        // Locked accounts: respect the cumulative lock. Wire-identical generic
        // success shape — no oracle that resend is being rejected for lock state.
        if (emailVerificationProcessor.isLocked(user)) {
            return RegistrationResponse.of(email);
        }

        // Throttle check: derive issuedAt from expiresAt - OTP_TTL.
        if (user.getVerificationCodeExpiresAt() != null) {
            Instant issuedAt = user.getVerificationCodeExpiresAt().minus(OTP_TTL);
            Instant nextAllowed = issuedAt.plus(RESEND_COOLDOWN);
            if (clock.instant().isBefore(nextAllowed)) {
                long retryAfter = Duration.between(clock.instant(), nextAllowed).getSeconds() + 1;
                throw new ResendThrottledException(retryAfter);
            }
        }

        String rawOtp = tokenGenerator.generateOtp();
        user.setVerificationCodeHash(tokenGenerator.hashOtp(rawOtp));
        user.setVerificationCodeExpiresAt(clock.instant().plus(OTP_TTL));
        // Reset the per-OTP attempt window only. verificationFailedTotal is
        // deliberately NOT reset here — that is the resend-surviving bound.
        user.setVerificationAttempts((short) 0);

        scheduleVerificationEmail(user.getEmail(), rawOtp);

        return RegistrationResponse.of(user.getEmail());
    }

    @Transactional
    public void logout(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    private AuthResponse buildAuthResponse(User user) {
        return authResponseBuilder.buildAuthResponse(user);
    }
}
