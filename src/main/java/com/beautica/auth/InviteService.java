package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InvitePreviewResponse;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ConflictException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.util.SchemeGuard;
import com.beautica.master.service.MasterService;
import com.beautica.notification.service.NotificationOutboxService;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
public class InviteService {

    private final InviteTokenRepository inviteTokenRepository;
    private final UserRepository userRepository;
    private final SalonRepository salonRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenGenerator tokenGenerator;
    private final MasterService masterService;
    private final AuthResponseBuilder authResponseBuilder;
    private final NotificationOutboxService outboxService;
    private final String frontendBaseUrl;
    private final long tokenExpirationHours;
    private final Clock clock;

    public InviteService(
            InviteTokenRepository inviteTokenRepository,
            UserRepository userRepository,
            SalonRepository salonRepository,
            PasswordEncoder passwordEncoder,
            TokenGenerator tokenGenerator,
            MasterService masterService,
            AuthResponseBuilder authResponseBuilder,
            NotificationOutboxService outboxService,
            @Value("${app.frontend.base-url}") String frontendBaseUrl,
            @Value("${app.invite.token-expiration-hours:48}") long tokenExpirationHours,
            Clock clock
    ) {
        this.inviteTokenRepository = inviteTokenRepository;
        this.userRepository = userRepository;
        this.salonRepository = salonRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenGenerator = tokenGenerator;
        this.masterService = masterService;
        this.authResponseBuilder = authResponseBuilder;
        this.outboxService = outboxService;
        this.frontendBaseUrl = frontendBaseUrl;
        this.tokenExpirationHours = tokenExpirationHours;
        this.clock = clock;
    }

    /**
     * Validates {@code app.frontend.base-url} at application startup.
     * A misconfigured HTTP (non-localhost) URL will cause context startup failure
     * rather than surfacing silently on the first invite dispatch.
     */
    @PostConstruct
    void validateConfig() {
        if (!SchemeGuard.isAllowedScheme(frontendBaseUrl)) {
            throw new IllegalStateException(
                    "app.frontend.base-url must use HTTPS scheme for non-localhost origins, got: " + frontendBaseUrl);
        }
    }

    /**
     * Sends a salon invite for the target email, or returns a non-distinguishing generic
     * success when an invite cannot or should not be created.
     *
     * <p><strong>Enumeration hardening:</strong> all three outcomes — target already
     * registered, target already has an active pending invite, and target is brand-new —
     * return a structurally identical {@link InviteResponse} (echoed email + a freshly
     * computed ~{@code tokenExpirationHours} expiry) with the same success status. None of
     * them surfaces a distinguishing 4xx, and the returned expiry is recomputed on every
     * call in every branch, so repeated invites cannot reveal registration status (the
     * earlier residual {@code 200-vs-409} oracle on the second call is closed). An active
     * pending invite is treated as an idempotent success — no second token is issued and no
     * second e-mail is dispatched. An expired prior token is recycled (deleted) before a
     * fresh one is created.
     *
     * <p><strong>Residual timing side-channel:</strong> the already-registered and
     * active-invite branches skip token generation, hashing, persistence and outbox
     * encryption, so they complete measurably faster than the brand-new branch. Full timing
     * equalization is intentionally not attempted here; the compensating control is the
     * per-IP rate limit on {@code POST /api/v1/auth/invite} (see {@code AuthRateLimitFilter}),
     * which bounds how many timing samples an attacker can collect. The endpoint also
     * requires an authenticated {@code SALON_OWNER}, so any abuse is attributable to a known
     * principal.
     */
    @Transactional
    public InviteResponse sendInvite(InviteRequest request, UUID callerId) {
        // SECURITY (email-enumeration): do NOT surface a distinct "already registered"
        // error here — that turns this endpoint into an enumeration oracle (an authenticated
        // SALON_OWNER could probe arbitrary emails for registration status). Instead we run
        // the same authorization flow regardless and, if the email is already registered,
        // silently skip invite creation while returning the same generic response shape.
        // The real reason is logged at debug only. Happy-path semantics are unchanged.
        boolean alreadyRegistered = userRepository.existsByEmail(request.email());

        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> new NotFoundException("Caller not found"));

        Role targetRole = request.effectiveRole();

        if (targetRole != Role.SALON_MASTER && targetRole != Role.SALON_ADMIN) {
            throw new ForbiddenException("Role " + targetRole + " cannot be assigned via invite");
        }

        if (targetRole == Role.SALON_ADMIN && caller.getRole() != Role.SALON_OWNER) {
            throw new ForbiddenException("Only SALON_OWNER may invite a SALON_ADMIN");
        }

        // Fix MEDIUM-2: SALON_ADMIN is assigned to a salon but is NOT its owner, so
        // findByIdAndOwnerId always returns empty for them — the invite feature was dead
        // for SALON_ADMIN callers. Use a role-based branch: SALON_OWNER verifies ownership
        // via the owner FK; SALON_ADMIN verifies their assigned salonId matches the request.
        Salon salon;
        if (caller.getRole() == Role.SALON_OWNER) {
            salon = salonRepository.findByIdAndOwnerId(request.salonId(), callerId)
                    .orElseThrow(() -> new ForbiddenException("You do not own the specified salon"));
        } else if (caller.getRole() == Role.SALON_ADMIN) {
            if (!request.salonId().equals(caller.getSalonId())) {
                throw new ForbiddenException("SALON_ADMIN may only invite to their own assigned salon");
            }
            salon = salonRepository.findById(request.salonId())
                    .orElseThrow(() -> new NotFoundException("Salon not found"));
        } else {
            throw new ForbiddenException("Role " + caller.getRole() + " cannot send invites");
        }

        if (targetRole == Role.SALON_ADMIN) {
            if (userRepository.existsBySalonIdAndRole(request.salonId(), Role.SALON_ADMIN)) {
                throw new ConflictException("This salon already has a SALON_ADMIN");
            }
        }

        Instant expiresAt = clock.instant().plus(tokenExpirationHours, ChronoUnit.HOURS);

        // Non-distinguishing outcome: an already-registered target gets the same generic
        // success response (same shape, plausible expiry) without an invite ever being
        // created or an e-mail dispatched. Only this debug line records the real reason.
        if (alreadyRegistered) {
            log.debug("Invite skipped: target email already registered (salonId={})", request.salonId());
            return new InviteResponse(request.email(), expiresAt);
        }

        // A pre-existing *active* (unused, unexpired) invite is an idempotent success — return
        // the same generic response WITHOUT issuing a second token or e-mail. Raising a 409
        // here (the old behaviour) re-opened the enumeration oracle: combined with the
        // already-registered early return above, a *second* identical call had a not-yet-
        // registered email hit the 409 while a registered email still returned 200, so the
        // 200-vs-409 split deterministically revealed registration status. An expired prior
        // token is recycled (deleted) before a fresh one is issued.
        Optional<InviteToken> existingInvite = inviteTokenRepository.findByEmailAndIsUsedFalse(request.email());
        if (existingInvite.isPresent()) {
            InviteToken existing = existingInvite.get();
            if (existing.getExpiresAt().isAfter(clock.instant())) {
                log.debug("Invite idempotent: active invite already exists (salonId={})", request.salonId());
                return new InviteResponse(request.email(), expiresAt);
            }
            inviteTokenRepository.delete(existing);
        }

        String rawToken = tokenGenerator.generateToken();
        String hashedToken = tokenGenerator.hash(rawToken);

        var inviteToken = new InviteToken(hashedToken, request.email(), request.salonId(), targetRole, expiresAt);
        var savedInviteToken = inviteTokenRepository.save(inviteToken);

        // Outbox pattern: write encrypted-payload row inside this @Transactional boundary
        // (NotificationOutboxService.enqueueInvite uses Propagation.MANDATORY). The drain
        // worker decrypts and dispatches the e-mail asynchronously after commit.
        String inviteLink = buildInviteLink(rawToken);
        outboxService.enqueueInvite(savedInviteToken.getId(), request.email(), inviteLink, salon.getName());

        return new InviteResponse(request.email(), expiresAt);
    }

    @Transactional(readOnly = true)
    public InvitePreviewResponse previewInvite(String rawToken) {
        InviteToken token = inviteTokenRepository.findByToken(tokenGenerator.hash(rawToken))
                .orElseThrow(() -> new BusinessException("Invalid or expired invite token"));

        if (token.isUsed() || token.getExpiresAt().isBefore(clock.instant())) {
            throw new BusinessException("Invalid or expired invite token");
        }

        return new InvitePreviewResponse(token.getEmail(), token.getRole(), token.getExpiresAt());
    }

    @Transactional
    public AuthResponse acceptInvite(InviteAcceptRequest request) {
        InviteToken token = inviteTokenRepository.findByTokenForUpdate(tokenGenerator.hash(request.token()))
                .orElseThrow(() -> new NotFoundException("Invite token not found"));

        if (token.isUsed()) {
            throw new BusinessException("Invite token has already been used");
        }

        if (token.getExpiresAt().isBefore(clock.instant())) {
            throw new BusinessException("Invite token has expired");
        }

        if (userRepository.existsByEmail(token.getEmail())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Email is already registered");
        }

        token.markUsed();
        inviteTokenRepository.save(token);

        var user = new User(
                token.getEmail(),
                passwordEncoder.encode(request.password()),
                token.getRole(),
                request.firstName(),
                request.lastName(),
                request.phoneNumber(),
                token.getSalonId()
        );
        var savedUser = userRepository.save(user);

        if (token.getRole() == Role.SALON_MASTER) {
            masterService.createMasterFromInvite(savedUser.getId(), token.getSalonId());
        }

        return buildAuthResponse(savedUser);
    }

    private String buildInviteLink(String rawToken) {
        if (!SchemeGuard.isAllowedScheme(frontendBaseUrl)) {
            throw new IllegalStateException(
                    "app.frontend.base-url must use HTTPS scheme for non-localhost origins, got: " + frontendBaseUrl);
        }
        return frontendBaseUrl + "/invite/accept?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }

    private AuthResponse buildAuthResponse(User user) {
        return authResponseBuilder.buildAuthResponse(user);
    }

}
