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
import com.beautica.common.exception.EmailAlreadyRegisteredException;
import com.beautica.common.exception.EmailNotVerifiedException;
import com.beautica.common.exception.ResendThrottledException;
import com.beautica.common.exception.VerificationException;
import com.beautica.config.VerificationPolicyConfig;
import com.beautica.master.service.MasterService;
import com.beautica.notification.service.EmailNotificationService;
import com.beautica.user.RefreshTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.task.TaskExecutor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;


@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

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
    private final EmailVerificationProcessor emailVerificationProcessor;
    private final VerificationPolicyConfig verificationPolicyConfig;
    private final JwtTokenProvider jwtTokenProvider;
    private final AccessTokenDenylist accessTokenDenylist;

    /**
     * Self-proxy reference so {@link #revokeFamilyIndependently(UUID)} runs through the Spring
     * AOP proxy and its {@code REQUIRES_NEW} propagation is honoured — a direct
     * {@code this.revokeFamilyIndependently(...)} call bypasses the proxy and would run inside
     * {@link #refresh(RefreshRequest)}'s own transaction, which is exactly the bug this method
     * exists to avoid (see {@link #refresh(RefreshRequest)} for the full rationale).
     *
     * <p>This is a deliberate, documented exception to the project's no-field-injection rule —
     * mirrors {@code NotificationOutboxDrainWorker#self}. Self-proxy injection cannot be
     * expressed as a constructor parameter (circular dependency at construction time), so
     * {@code @Lazy @Autowired} field injection is the only viable pattern without a full class
     * split. Field injection here (rather than splitting into a second bean) also keeps
     * {@code AuthServiceTest}'s existing {@code new AuthService(...)} constructor calls intact.
     */
    @Autowired
    @Lazy
    private AuthService self;

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
            EmailVerificationProcessor emailVerificationProcessor,
            VerificationPolicyConfig verificationPolicyConfig,
            JwtTokenProvider jwtTokenProvider,
            AccessTokenDenylist accessTokenDenylist
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
        this.verificationPolicyConfig = verificationPolicyConfig;
        this.jwtTokenProvider = jwtTokenProvider;
        this.accessTokenDenylist = accessTokenDenylist;
    }

    @Transactional
    public RegistrationResponse register(RegisterRequest request) {
        String email = request.email().toLowerCase(Locale.ROOT).strip();

        // Honest 409 on duplicate email. The prior anti-enumeration silent-200 was
        // dropped — it created an undebuggable "we sent a code, but it never comes"
        // footgun for any caller who hit a duplicate (the OTP path was bypassed but
        // the response was indistinguishable from a fresh signup). The 409 path is
        // rate-limited per-IP via AuthRateLimitFilter, so the enumeration surface
        // is bounded; the trade-off is intentional.
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
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

        // Honest 409 on duplicate email. See register() for the rationale.
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
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
                            submitVerificationEmail(email, rawOtp);
                        }
                    }
            );
        } else {
            submitVerificationEmail(email, rawOtp);
        }
    }

    /**
     * Submits the verification email task to the executor. Catches
     * {@link RejectedExecutionException} (queue saturation under AbortPolicy) and
     * logs an error using only the userId — never the OTP or email address — so
     * operators can investigate without PII appearing in logs.
     * The user will be unable to log in until they trigger a resend after the
     * 60-second cooldown expires.
     */
    private void submitVerificationEmail(String email, String rawOtp) {
        try {
            emailExecutor.execute(() ->
                    emailNotificationService.sendVerificationEmail(email, rawOtp));
        } catch (RejectedExecutionException e) {
            log.error("Verification email task rejected — executor queue saturated; " +
                      "user with email={} will be unable to log in until resend cooldown expires",
                      maskEmail(email));
        }
    }

    /**
     * Masks an email address for log output, retaining only the first character
     * and the domain to allow correlation while minimising PII exposure.
     * Example: {@code john.doe@example.com} → {@code j***@example.com}
     */
    private static String maskEmail(String email) {
        if (email == null) return "<null>";
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
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
            // Reuse detection: an already-revoked refresh token being presented again means
            // this rotation chain has been compromised — either the token was stolen and both
            // the legitimate holder and an attacker have rotated from it, or it is a straight
            // replay after the legitimate holder already rotated. Either way, a single-token
            // revoke is not enough: the attacker may be holding a later, still-valid token from
            // the same chain. Revoke every token in the family so the whole chain is dead.
            //
            // Must commit independently of this method's own transaction: refresh() is
            // @Transactional with default rollback rules, and the very next line throws a
            // BusinessException (a RuntimeException) to return the 401. That marks THIS
            // transaction rollback-only, so if the revoke ran inside it, Spring's
            // TransactionInterceptor would roll the bulk UPDATE back before commit — the
            // caller still gets a 401, but every sibling token in the family silently
            // survives (including one an attacker may have already rotated to), making the
            // whole feature a no-op. Deliberately NOT `noRollbackFor = BusinessException.class`
            // on refresh(): that would also spare rollback for any OTHER BusinessException
            // thrown later in this method (expired token, inactive/unverified user), which
            // is a correctness hazard unrelated to reuse detection. A dedicated REQUIRES_NEW
            // method scopes the no-rollback behaviour to exactly this one statement. Called
            // via `self` — see the field javadoc for why the proxy indirection is required.
            self.revokeFamilyIndependently(storedToken.getFamilyId());
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

        // The replacement token inherits this chain's familyId — see
        // AuthResponseBuilder#buildAuthResponse(User, UUID) — so reuse detection above can
        // trace and revoke the whole chain, not just whichever single token gets replayed.
        return authResponseBuilder.buildAuthResponse(user, storedToken.getFamilyId());
    }

    /**
     * Revokes every non-revoked token in {@code familyId} in its own transaction, committed
     * independently of the caller. See the reuse-detection comment inside
     * {@link #refresh(RefreshRequest)} for why this cannot simply run inside that method's
     * transaction. Must be called through the {@code self} proxy, never as {@code this.}, or
     * the {@code REQUIRES_NEW} propagation below is silently skipped (self-invocation bypasses
     * the Spring AOP proxy).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revokeFamilyIndependently(UUID familyId) {
        refreshTokenRepository.revokeAllByFamilyId(familyId);
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
            Instant nextAllowed = issuedAt.plus(verificationPolicyConfig.resendCooldown());
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

    /**
     * Logs a user out: revokes every refresh token for the user, and denylists the
     * {@code jti} of the access token used to authenticate this logout request so it
     * cannot be replayed for the remainder of its TTL. Access tokens are otherwise
     * stateless JWTs verified purely by signature + expiry — see
     * {@link AccessTokenDenylist} for why that previously left a compromised or
     * just-logged-out access token fully usable until it naturally expired.
     *
     * @param accessToken the raw Bearer token from the logout request, or {@code null}
     *                     if it could not be extracted (defensive only — the endpoint
     *                     requires authentication, so this is not expected in
     *                     practice). When {@code null} or unparseable, only the
     *                     refresh-token revocation takes effect.
     */
    @Transactional
    public void logout(UUID userId, String accessToken) {
        refreshTokenRepository.deleteByUserId(userId);
        denylistAccessToken(accessToken);
    }

    /**
     * Parses {@code accessToken} and denylists its {@code jti}. Widened from {@code private} to
     * {@code public} in Phase 300 (REUSE-FIRST: a private helper is promoted, never copied) so
     * {@code ClientAccountDeletionService} (a different package: {@code com.beautica.user}) can
     * revoke the deleting CLIENT's own bearer token exactly as {@link #logout} does, without a
     * second copy of this try/catch. Unlike {@link #logout}, callers of THIS method are
     * responsible for their own refresh-token cleanup (a hard-deleted user's {@code
     * refresh_tokens} rows are removed by {@code ON DELETE CASCADE} instead).
     *
     * <p>No-op for a {@code null} token (defensive only — every caller's endpoint requires
     * authentication) and swallows an unparseable one at {@code DEBUG}, exactly as {@link #logout}
     * always has: a token that cannot be parsed cannot be replayed as itself either.
     */
    public void denylistAccessToken(String accessToken) {
        if (accessToken == null) {
            return;
        }
        try {
            Claims claims = jwtTokenProvider.parseAllClaims(accessToken);
            accessTokenDenylist.revoke(jwtTokenProvider.getJti(claims));
        } catch (JwtException ex) {
            log.debug("Unable to parse access token during logout: {}", ex.getClass().getSimpleName());
        }
    }

    private AuthResponse buildAuthResponse(User user) {
        return authResponseBuilder.buildAuthResponse(user);
    }
}
