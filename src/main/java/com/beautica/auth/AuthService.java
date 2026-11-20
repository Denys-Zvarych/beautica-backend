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
import com.beautica.common.util.UaMobilePhone;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

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

    /**
     * BCrypt of a throw-away string at the SAME cost factor as {@code SecurityConfig#passwordEncoder} (10) — matched
     * against when the login identifier names no account, so an unknown identifier costs the same time as a wrong
     * password. {@code AuthServiceLoginIdentifierTest} pins the cost parity.
     */
    static final String DUMMY_PASSWORD_HASH = "$2a$10$HEoJYzvHn1rJuaMjlcFb/Od12QEDzO/iS8s.RO2KRYZKqVm6VJPsu";

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
    private final PhoneRegistrationService phoneRegistrationService;
    private final LoginFailureLimiter loginFailureLimiter;
    /**
     * Phone-first hardening B11/B12 — the short write transactions of login (token issue) and register (ticket consume
     * + insert), opened only AFTER BCrypt so no pooled connection waits on 80–150 ms of hashing. The pre-hardening
     * constructor (unit tests) runs the callbacks inline ({@link TransactionOperations#withoutTransaction()}).
     */
    private final TransactionOperations writeTx;

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
            AccessTokenDenylist accessTokenDenylist,
            PhoneRegistrationService phoneRegistrationService,
            LoginFailureLimiter loginFailureLimiter
    ) {
        this(userRepository, refreshTokenRepository, passwordEncoder, tokenGenerator, masterService,
                authResponseBuilder, clock, emailNotificationService, emailExecutor, emailVerificationProcessor,
                verificationPolicyConfig, jwtTokenProvider, accessTokenDenylist, phoneRegistrationService,
                loginFailureLimiter, TransactionOperations.withoutTransaction());
    }

    @Autowired
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
            AccessTokenDenylist accessTokenDenylist,
            PhoneRegistrationService phoneRegistrationService,
            LoginFailureLimiter loginFailureLimiter,
            PlatformTransactionManager transactionManager
    ) {
        this(userRepository, refreshTokenRepository, passwordEncoder, tokenGenerator, masterService,
                authResponseBuilder, clock, emailNotificationService, emailExecutor, emailVerificationProcessor,
                verificationPolicyConfig, jwtTokenProvider, accessTokenDenylist, phoneRegistrationService,
                loginFailureLimiter, (TransactionOperations) new TransactionTemplate(transactionManager));
    }

    private AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            PasswordEncoder passwordEncoder,
            TokenGenerator tokenGenerator,
            MasterService masterService,
            AuthResponseBuilder authResponseBuilder,
            Clock clock,
            EmailNotificationService emailNotificationService,
            TaskExecutor emailExecutor,
            EmailVerificationProcessor emailVerificationProcessor,
            VerificationPolicyConfig verificationPolicyConfig,
            JwtTokenProvider jwtTokenProvider,
            AccessTokenDenylist accessTokenDenylist,
            PhoneRegistrationService phoneRegistrationService,
            LoginFailureLimiter loginFailureLimiter,
            TransactionOperations writeTx
    ) {
        this.writeTx = writeTx;
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
        this.phoneRegistrationService = phoneRegistrationService;
        this.loginFailureLimiter = loginFailureLimiter;
    }

    /**
     * B514 (AUTH-02): with a {@code registrationTicket} the account is created phone-only and signed in at once
     * ({@link PhoneRegistrationService}); without one, the legacy email-code path below (until
     * {@code app.auth.require-phone-registration} is flipped, enforced by {@code @PhoneRegistrationShape}).
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RegistrationResponse register(RegisterRequest request) {
        if (request.hasRegistrationTicket()) {
            requireBusinessNameForOwner(request);
        }
        // B12: BCrypt (80–150 ms) before any transaction; the ticket consume / insert then run in one short one.
        String passwordHash = passwordEncoder.encode(request.password());
        return writeTx.execute(status -> registerHashed(request, passwordHash));
    }

    private RegistrationResponse registerHashed(RegisterRequest request, String passwordHash) {
        if (request.hasRegistrationTicket()) {
            return RegistrationResponse.signedIn(phoneRegistrationService.register(
                    new PhoneRegistrationService.PhoneAccount(request.registrationTicket(), passwordHash,
                            request.role().toRole(), request.firstName(), request.lastName(),
                            ownerBusinessName(request), request)));
        }
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

        requireBusinessNameForOwner(request);

        String businessName = ownerBusinessName(request);

        String rawOtp = tokenGenerator.generateOtp();

        var user = new User(
                email,
                passwordHash,
                request.role().toRole(),
                request.firstName(),
                request.lastName(),
                request.phoneNumber(),
                businessName
        );
        user.setVerificationCodeHash(tokenGenerator.hashOtp(rawOtp));
        user.setVerificationCodeExpiresAt(clock.instant().plus(OTP_TTL));
        TermsConsentStamp.apply(user, request, clock.instant());

        var savedUser = userRepository.save(user);

        scheduleVerificationEmail(savedUser.getEmail(), rawOtp);

        return RegistrationResponse.of(savedUser.getEmail());
    }

    private static void requireBusinessNameForOwner(RegisterRequest request) {
        if (request.role() == SelfRegistrationRole.SALON_OWNER
                && (request.businessName() == null || request.businessName().isBlank())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "businessName is required for SALON_OWNER");
        }
    }

    private static String ownerBusinessName(RegisterRequest request) {
        return request.role() == SelfRegistrationRole.SALON_OWNER ? request.businessName() : null;
    }

    /** B514 (AUTH-02): as {@link #register} — a {@code registrationTicket} creates a phone-only, signed-in master. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RegistrationResponse registerIndependentMaster(RegisterIndependentMasterRequest request) {
        String passwordHash = passwordEncoder.encode(request.password());
        return writeTx.execute(status -> registerIndependentMasterHashed(request, passwordHash));
    }

    private RegistrationResponse registerIndependentMasterHashed(RegisterIndependentMasterRequest request,
                                                                 String passwordHash) {
        if (request.hasRegistrationTicket()) {
            return RegistrationResponse.signedIn(phoneRegistrationService.register(
                    new PhoneRegistrationService.PhoneAccount(request.registrationTicket(), passwordHash,
                            Role.INDEPENDENT_MASTER, request.firstName(), request.lastName(), null, request)));
        }
        String email = request.email().toLowerCase(Locale.ROOT).strip();

        // Honest 409 on duplicate email. See register() for the rationale.
        if (userRepository.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }

        String rawOtp = tokenGenerator.generateOtp();

        var user = new User(
                email,
                passwordHash,
                Role.INDEPENDENT_MASTER,
                request.firstName(),
                request.lastName(),
                request.phoneNumber()
        );
        user.setVerificationCodeHash(tokenGenerator.hashOtp(rawOtp));
        user.setVerificationCodeExpiresAt(clock.instant().plus(OTP_TTL));
        TermsConsentStamp.apply(user, request, clock.instant());

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

    /**
     * AUTH-08 / AUTH-D09 (B514): one identifier — input with «@» is an email, anything else is normalised to
     * {@code +380…} and matched against VERIFIED phones only. Wrong password, unknown identifier, unverified phone,
     * inactive account and a locked identifier + client-key pair ({@link LoginFailureLimiter}) are the same 401. Only a
     * legacy account with neither a verified phone nor a confirmed email gets 403 {@code EMAIL_NOT_VERIFIED}. Never
     * sends an SMS.
     *
     * <p>Phone-first hardening B11 — no transaction around BCrypt: the lock is checked on the NORMALISED identifier
     * before any DB read (a locked pair costs no lookup), the account is read by a plain repository call, the password
     * is checked with no connection held, and only the token issue (refresh-token insert) runs in a short write
     * transaction.
     *
     * @param clientKey the client key of AUTH-D27 (e): an IPv6 client's /64, an IPv4 address as is
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public AuthResponse login(LoginRequest request, String clientKey) {
        String key = lockKey(request.loginIdentifier().strip());
        if (loginFailureLimiter.isLocked(key, clientKey)) {
            // AUTH-08: a locked reply costs the same BCrypt time as any other 401, so its speed is no oracle.
            passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
            throw invalidCredentials();
        }

        var user = findLoginAccount(key);
        if (user == null) {
            // Same BCrypt work as a known account, so response time is no account-existence oracle; garbage
            // identifiers are not counted (they would only flood the limiter's bounded cache).
            passwordEncoder.matches(request.password(), DUMMY_PASSWORD_HASH);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginFailureLimiter.recordFailure(key, clientKey);
            throw invalidCredentials();
        }

        if (!user.isActive()) {
            throw invalidCredentials();
        }

        // By design: the 403 response reveals that the password was correct and the account is
        // unverified. The caller (the account owner) needs this signal to route to the
        // verification screen. An attacker reaching this branch already knows the correct
        // password — the verification state is not the secret being protected here.
        // B514: only a legacy account (no verified phone) can reach it; its email is never null (V244 CHECK).
        if (!user.hasVerifiedIdentity()) {
            throw new EmailNotVerifiedException(user.getEmail());
        }

        loginFailureLimiter.reset(key, clientKey);
        return writeTx.execute(status -> buildAuthResponse(user));
    }

    /**
     * The lockout key of a login identifier, derived from the input alone (no DB): an email lower-cased, a phone in
     * any accepted notation normalised to {@code +380…}, anything else lower-cased (it names no account).
     */
    private static String lockKey(String identifier) {
        if (identifier.indexOf('@') >= 0) {
            return identifier.toLowerCase(Locale.ROOT);
        }
        return UaMobilePhone.parse(identifier).orElseGet(() -> identifier.toLowerCase(Locale.ROOT));
    }

    /** The account a normalised key names, or {@code null}: email by address, phone by VERIFIED phone only. */
    private User findLoginAccount(String key) {
        if (key.indexOf('@') >= 0) {
            return userRepository.findByEmail(key).orElse(null);
        }
        return key.startsWith("+380") && UaMobilePhone.parse(key).isPresent()
                ? userRepository.findByVerifiedPhone(key).orElse(null)
                : null;
    }

    private static BusinessException invalidCredentials() {
        return new BusinessException(HttpStatus.UNAUTHORIZED, "Invalid email or password");
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

        // B514: a phone-registered account has no email but a verified phone — both count as verified.
        if (!user.hasVerifiedIdentity()) {
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
     * <p>Lock minimisation: a non-locking column-only {@link UserRepository#findResendVerificationEligibleByEmail} read
     * runs first. Unknown-email and already-verified branches take NO row lock
     * (they short-circuit). The {@code PESSIMISTIC_WRITE} lock is escalated only
     * on the real write path (user exists, unverified; a stored code is NOT required - an expired/wiped code must be re-issuable).
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

        // Non-locking, column-only pre-read — unknown / verified / inactive take NO row lock. A scalar
        // projection (not an entity) on purpose: an entity loaded here would be returned again by
        // findByEmailForUpdate from the persistence context, hiding a concurrent resend's committed
        // verificationCodeExpiresAt and skipping the cooldown (two mails).
        if (!userRepository.findResendVerificationEligibleByEmail(email).orElse(false)) {
            return RegistrationResponse.of(email);
        }

        // Real write path — escalate to the pessimistic lock now (closes the
        // TOCTOU window between the cooldown read and the OTP write).
        var user = userRepository.findByEmailForUpdate(email)
                .orElse(null);
        if (user == null || user.isEmailVerified() || !user.isActive()) {
            return RegistrationResponse.of(email);
        }

        // Locked accounts: respect the cumulative lock. Wire-identical generic
        // success shape — no oracle that resend is being rejected for lock state.
        if (emailVerificationProcessor.isLocked(user)) {
            return RegistrationResponse.of(email);
        }

        // Throttle check: derive issuedAt from expiresAt - OTP_TTL. A user with NO stored code
        // (wiped by an expired-code verify attempt or the stale-cleanup job) has no issuedAt and
        // is NOT throttled: they must be able to get a fresh code, or they are stuck forever
        // (re-register returns 409). The lock check above still applies to them.
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
