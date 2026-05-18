package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.auth.dto.RefreshRequest;
import com.beautica.auth.dto.RegisterIndependentMasterRequest;
import com.beautica.auth.dto.RegisterRequest;
import com.beautica.auth.dto.RegistrationResponse;
import com.beautica.auth.dto.SelfRegistrationRole;
import com.beautica.common.exception.BusinessException;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;


@Service
public class AuthService {

    private static final Duration OTP_TTL = Duration.ofMinutes(15);

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
        // Return the same 200 response for already-registered emails to prevent
        // enumeration attacks — callers cannot distinguish new from existing registrations.
        if (userRepository.existsByEmail(request.email())) {
            return RegistrationResponse.of(request.email());
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
                request.email(),
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
        // Return the same 200 response for already-registered emails to prevent
        // enumeration attacks — callers cannot distinguish new from existing registrations.
        if (userRepository.existsByEmail(request.email())) {
            return RegistrationResponse.of(request.email());
        }

        String rawOtp = tokenGenerator.generateOtp();

        var user = new User(
                request.email(),
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
        var user = userRepository.findByEmail(request.email())
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
    public void logout(UUID userId) {
        refreshTokenRepository.deleteByUserId(userId);
    }

    private AuthResponse buildAuthResponse(User user) {
        return authResponseBuilder.buildAuthResponse(user);
    }
}
