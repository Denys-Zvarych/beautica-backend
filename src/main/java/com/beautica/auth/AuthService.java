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
import com.beautica.common.exception.ResendThrottledException;
import com.beautica.common.exception.VerificationException;
import com.beautica.master.service.MasterService;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.user.RefreshToken;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;


@Service
public class AuthService {

    private static final Duration OTP_TTL = Duration.ofMinutes(15);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(60);
    private static final short MAX_VERIFICATION_ATTEMPTS = 5;

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenGenerator tokenGenerator;
    private final MasterService masterService;
    private final AuthResponseBuilder authResponseBuilder;
    private final Clock clock;
    private final EmailNotificationService emailNotificationService;
    private final TaskExecutor emailExecutor;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            TokenGenerator tokenGenerator,
            MasterService masterService,
            AuthResponseBuilder authResponseBuilder,
            Clock clock,
            EmailNotificationService emailNotificationService,
            @Qualifier("emailExecutor") TaskExecutor emailExecutor
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
        user.setVerificationCodeHash(tokenGenerator.hash(rawOtp));
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
        user.setVerificationCodeHash(tokenGenerator.hash(rawOtp));
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

        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse verifyEmail(VerifyEmailRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT).strip();
        var user = userRepository.findByEmail(email)
                .orElseThrow(() -> new VerificationException(VerificationException.Code.INVALID_CODE));

        // Fix 2: anti-enumeration — ALREADY_VERIFIED leaks account existence to probers.
        // Return INVALID_CODE so unknown-email, already-verified, and wrong-code are wire-identical.
        if (user.isEmailVerified()) {
            throw new VerificationException(VerificationException.Code.INVALID_CODE);
        }

        if (user.getVerificationCodeHash() == null || user.getVerificationCodeExpiresAt() == null) {
            throw new VerificationException(VerificationException.Code.CODE_EXPIRED);
        }

        // Fix 1: do not clear/save on expiry — the resend endpoint (Phase 1.6) overwrites the code.
        if (user.getVerificationCodeExpiresAt().isBefore(clock.instant())) {
            throw new VerificationException(VerificationException.Code.CODE_EXPIRED);
        }

        // Fix 3: enforce attempt cap before the hash comparison so brute-force is bounded.
        // Treat exhausted attempts identically to expiry (CODE_EXPIRED) to avoid leaking the limit.
        if (user.getVerificationAttempts() >= MAX_VERIFICATION_ATTEMPTS) {
            throw new VerificationException(VerificationException.Code.CODE_EXPIRED);
        }
        user.setVerificationAttempts((short) (user.getVerificationAttempts() + 1));
        userRepository.save(user);  // persist attempt increment; committed even when the next check fails

        String incomingHash = tokenGenerator.hash(request.code());
        boolean match = MessageDigest.isEqual(
                incomingHash.getBytes(StandardCharsets.UTF_8),
                user.getVerificationCodeHash().getBytes(StandardCharsets.UTF_8));

        if (!match) {
            throw new VerificationException(VerificationException.Code.INVALID_CODE);
        }

        user.setEmailVerified(true);
        user.setVerificationCodeHash(null);
        user.setVerificationCodeExpiresAt(null);
        user.setVerificationAttempts((short) 0);
        userRepository.save(user);
        return buildAuthResponse(user);
    }

    /**
     * Resends a verification OTP to the given email address, subject to a 60-second
     * per-account cooldown.
     *
     * <p>Anti-enumeration: unknown emails and already-verified accounts both return
     * the same success-shaped {@link RegistrationResponse} without sending any mail.
     * The response is wire-identical to a real resend, so probers cannot distinguish
     * the three states (unknown / already-verified / resent).
     *
     * <p>Throttle derivation: we do not store a dedicated {@code sent_at} column (see
     * Phase 1.6 spec). Instead, {@code issuedAt} is derived as
     * {@code verificationCodeExpiresAt - OTP_TTL}, since
     * {@code expiresAt = issuedAt + OTP_TTL} at issue time. The next allowed send is
     * {@code issuedAt + RESEND_COOLDOWN}. If the clock has not yet passed that instant,
     * {@link ResendThrottledException} is thrown with the remaining wait in seconds.
     */
    @Transactional
    public RegistrationResponse resendVerification(ResendVerificationRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT).strip();
        var userOpt = userRepository.findByEmailForUpdate(email);

        // Anti-enumeration: unknown email and already-verified both return the same
        // generic success shape without sending mail.
        if (userOpt.isEmpty() || userOpt.get().isEmailVerified()) {
            return RegistrationResponse.of(email);
        }

        var user = userOpt.get();

        // Throttle check: derive issuedAt from expiresAt - OTP_TTL
        // (avoids adding a verification_code_sent_at column; see Phase 1.6 spec).
        if (user.getVerificationCodeExpiresAt() != null) {
            Instant issuedAt = user.getVerificationCodeExpiresAt().minus(OTP_TTL);
            Instant nextAllowed = issuedAt.plus(RESEND_COOLDOWN);
            if (clock.instant().isBefore(nextAllowed)) {
                long retryAfter = Duration.between(clock.instant(), nextAllowed).getSeconds() + 1;
                throw new ResendThrottledException(retryAfter);
            }
        }

        String rawOtp = tokenGenerator.generateOtp();
        user.setVerificationCodeHash(tokenGenerator.hash(rawOtp));
        user.setVerificationCodeExpiresAt(clock.instant().plus(OTP_TTL));
        user.setVerificationAttempts((short) 0); // reset on fresh OTP
        userRepository.save(user);

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
