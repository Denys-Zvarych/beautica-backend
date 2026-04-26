package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InvitePreviewResponse;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.service.MasterService;
import com.beautica.notification.EmailService;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
public class InviteService {

    private final InviteTokenRepository inviteTokenRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final TokenGenerator tokenGenerator;
    private final MasterService masterService;
    private final AuthResponseBuilder authResponseBuilder;
    private final String frontendBaseUrl;
    private final long tokenExpirationHours;

    public InviteService(
            InviteTokenRepository inviteTokenRepository,
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            EmailService emailService,
            TokenGenerator tokenGenerator,
            MasterService masterService,
            AuthResponseBuilder authResponseBuilder,
            @Value("${app.frontend.base-url}") String frontendBaseUrl,
            @Value("${app.invite.token-expiration-hours:72}") long tokenExpirationHours
    ) {
        this.inviteTokenRepository = inviteTokenRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
        this.tokenGenerator = tokenGenerator;
        this.masterService = masterService;
        this.authResponseBuilder = authResponseBuilder;
        this.frontendBaseUrl = frontendBaseUrl;
        this.tokenExpirationHours = tokenExpirationHours;
    }

    @Transactional
    public InviteResponse sendInvite(InviteRequest request, UUID callerId) {
        if (userRepository.existsByEmail(request.email())) {
            throw new BusinessException(HttpStatus.CONFLICT, "Email is already registered");
        }

        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> new NotFoundException("Caller not found"));
        if (!request.salonId().equals(caller.getSalonId())) {
            throw new ForbiddenException("You do not own the specified salon");
        }

        inviteTokenRepository.findByEmailAndIsUsedFalse(request.email()).ifPresent(existing -> {
            if (existing.getExpiresAt().isAfter(Instant.now())) {
                throw new BusinessException(HttpStatus.CONFLICT, "An active invite already exists for this email");
            }
            inviteTokenRepository.delete(existing);
        });

        String rawToken = tokenGenerator.generateToken();
        String hashedToken = tokenGenerator.hash(rawToken);
        Instant expiresAt = Instant.now().plus(tokenExpirationHours, ChronoUnit.HOURS);

        var inviteToken = new InviteToken(hashedToken, request.email(), request.salonId(), Role.SALON_MASTER, expiresAt);
        inviteTokenRepository.save(inviteToken);

        emailService.sendInviteEmail(request.email(), buildInviteLink(rawToken));

        return new InviteResponse(request.email(), expiresAt);
    }

    @Transactional(readOnly = true)
    public InvitePreviewResponse previewInvite(String rawToken) {
        InviteToken token = inviteTokenRepository.findByToken(tokenGenerator.hash(rawToken))
                .orElseThrow(() -> new BusinessException("Invalid or expired invite token"));

        if (token.isUsed() || token.getExpiresAt().isBefore(Instant.now())) {
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

        if (token.getExpiresAt().isBefore(Instant.now())) {
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
                Role.SALON_MASTER,
                request.firstName(),
                request.lastName(),
                request.phoneNumber(),
                token.getSalonId()
        );
        var savedUser = userRepository.save(user);

        masterService.createMasterFromInvite(savedUser.getId(), token.getSalonId());

        return buildAuthResponse(savedUser);
    }

    private String buildInviteLink(String rawToken) {
        return frontendBaseUrl + "/invite/accept?token=" + rawToken;
    }

    private AuthResponse buildAuthResponse(User user) {
        return authResponseBuilder.buildAuthResponse(user);
    }

}
