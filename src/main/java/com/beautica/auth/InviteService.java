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
import java.util.UUID;

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

    @Transactional
    public InviteResponse sendInvite(InviteRequest request, UUID callerId) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Email is already registered");
        }

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

        inviteTokenRepository.findByEmailAndIsUsedFalse(request.email()).ifPresent(existing -> {
            if (existing.getExpiresAt().isAfter(clock.instant())) {
                throw new BusinessException(HttpStatus.CONFLICT, "An active invite already exists for this email");
            }
            inviteTokenRepository.delete(existing);
        });

        String rawToken = tokenGenerator.generateToken();
        String hashedToken = tokenGenerator.hash(rawToken);
        Instant expiresAt = clock.instant().plus(tokenExpirationHours, ChronoUnit.HOURS);

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
