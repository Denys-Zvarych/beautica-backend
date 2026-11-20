package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InvitePreviewResponse;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.auth.registration.RegistrationTicketService;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.IdentityException;
import com.beautica.common.exception.InviteTokenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.util.ContactMask;
import com.beautica.common.util.SchemeGuard;
import com.beautica.common.util.UaMobilePhone;
import com.beautica.master.service.MasterService;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Service
public class InviteService {

    /**
     * Roles that {@link #sendInvite} requires a live {@link Salon} to mint a token for — see
     * {@code InviteRequest#isRoleAllowed} (only {@code SALON_MASTER}/{@code SALON_ADMIN} are ever
     * assignable via invite) and {@code InviteRequest.salonId()}'s {@code @NotNull}. Every invite
     * token ever issued today carries one of these two roles, so every live token is salon-bound.
     * No currently-invitable role is genuinely salon-less; this set exists so a future role that
     * IS salon-less (and therefore legitimately carries a null {@code salonId}) can be added
     * without it here, rather than by loosening the null check in {@link #acceptInvite}.
     */
    private static final Set<Role> SALON_BOUND_ROLES = EnumSet.of(Role.SALON_MASTER, Role.SALON_ADMIN);

    private final InviteTokenRepository inviteTokenRepository;
    private final UserRepository userRepository;
    private final SalonRepository salonRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenGenerator tokenGenerator;
    private final MasterService masterService;
    private final AuthResponseBuilder authResponseBuilder;
    private final InvitePersistenceService invitePersistenceService;
    private final String frontendBaseUrl;
    private final long tokenExpirationHours;
    private final Clock clock;
    // Phase 333 — see InAppNotificationService's class javadoc for why this is a separate seam.
    private final InAppNotificationService inAppNotificationService;
    // B514 (AUTH-43) — the invitee's phone, proven by a registration ticket before accept.
    private final RegistrationTicketService registrationTicketService;
    // B520 audit-fix — bounds the free 409 PHONE_ALREADY_REGISTERED answers per inviter.
    private final InviteConflictProbeBudget conflictProbeBudget;

    public InviteService(
            InviteTokenRepository inviteTokenRepository,
            UserRepository userRepository,
            SalonRepository salonRepository,
            PasswordEncoder passwordEncoder,
            TokenGenerator tokenGenerator,
            MasterService masterService,
            AuthResponseBuilder authResponseBuilder,
            InvitePersistenceService invitePersistenceService,
            @Value("${app.frontend.base-url}") String frontendBaseUrl,
            @Value("${app.invite.token-expiration-hours:48}") long tokenExpirationHours,
            Clock clock,
            InAppNotificationService inAppNotificationService,
            RegistrationTicketService registrationTicketService,
            InviteConflictProbeBudget conflictProbeBudget
    ) {
        this.inviteTokenRepository = inviteTokenRepository;
        this.userRepository = userRepository;
        this.salonRepository = salonRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenGenerator = tokenGenerator;
        this.masterService = masterService;
        this.authResponseBuilder = authResponseBuilder;
        this.invitePersistenceService = invitePersistenceService;
        this.frontendBaseUrl = frontendBaseUrl;
        this.tokenExpirationHours = tokenExpirationHours;
        this.clock = clock;
        this.inAppNotificationService = inAppNotificationService;
        this.registrationTicketService = registrationTicketService;
        this.conflictProbeBudget = conflictProbeBudget;
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
                    "app.frontend.base-url must use HTTPS scheme for non-localhost origins");
        }
    }

    /**
     * Sends a salon staff invite to a Ukrainian mobile number (B520, AUTH-16 / AUTH-18 / AUTH-52): one SMS with the
     * salon name and the join link, enqueued in the invite's own commit.
     *
     * <p><strong>Order.</strong> Input first ({@code INVITE_PHONE_REQUIRED} for a body without a phone — an old build
     * still sending only {@code email}; {@code PHONE_NOT_UA_MOBILE} for anything else that is not a UA mobile) — these
     * reveal nothing about anyone. Then every authorization branch. Only then the honest 409
     * {@code PHONE_ALREADY_REGISTERED} for a phone verified on any account (AUTH-D02; phase 287's reasoning, now keyed
     * by phone): {@code alreadyRegistered} is computed up front so the round-trip happens once, but thrown only AFTER
     * authorization, so a caller who may not invite into that salon still gets an unchanged {@link ForbiddenException}
     * and learns nothing about the phone. A removed staff member is re-invitable once the account row is gone (AUTH-19,
     * staff removal hard-deletes it); no invite path reactivates or reassigns an account.
     *
     * <p>A live invite to the same phone for the same salon is an idempotent 201: no second token, no second SMS, not
     * counted against the caps. Each salon decides independently (the cross-salon silent-drop fix).
     *
     * <p>The caps (10 invite SMS per inviter per hour, 30 per salon per day, 3 per phone per day across all salons →
     * 429 {@code INVITE_SMS_LIMIT}) are enforced inside {@link InvitePersistenceService} under locks. The 409 itself is
     * budgeted per inviter ({@link InviteConflictProbeBudget}, 10 per hour → then 429 for every invite of theirs). An
     * inactive salon → 409 {@code INVITE_SALON_INACTIVE}, after authorization and before anything is written. App Check (AUTH-54) runs before this method
     * ({@code @RequireAppCheck} on both create endpoints).
     */
    @Transactional(readOnly = true)
    public InviteResponse sendInvite(InviteRequest request, UUID callerId) {
        if (request.phone() == null || request.phone().isBlank()) {
            throw new IdentityException(IdentityException.Code.INVITE_PHONE_REQUIRED);
        }
        String phone = UaMobilePhone.require(request.phone());

        // Computed BEFORE the authorization branches, acted on only after them (see the method javadoc).
        boolean alreadyRegistered = userRepository.existsByVerifiedPhone(phone);

        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> new NotFoundException("Caller not found"));
        Role targetRole = request.effectiveRole();
        Salon salon = authorizeInvite(caller, callerId, request.salonId(), targetRole);

        Instant expiresAt = clock.instant().plus(tokenExpirationHours, ChronoUnit.HOURS);

        // Honest 409, thrown HERE — after every authorization/ownership branch above has passed — but only while the
        // inviter's probe budget lasts; once spent, every invite of theirs is 429 so 429 vs 201 is no oracle either.
        if (alreadyRegistered) {
            if (!conflictProbeBudget.tryRecordConflict(callerId)) {
                throw new IdentityException(IdentityException.Code.INVITE_SMS_LIMIT);
            }
            throw new IdentityException(IdentityException.Code.PHONE_ALREADY_REGISTERED);
        }
        if (conflictProbeBudget.isExhausted(callerId)) {
            throw new IdentityException(IdentityException.Code.INVITE_SMS_LIMIT);
        }

        // Negation of the SAME canonical predicate the recycle filter uses (InviteToken#isExpired): "still live" here
        // and "recyclable" in InvitePersistenceService partition the timeline with no gap and no overlap.
        Optional<InviteToken> existingInvite =
                inviteTokenRepository.findByPhoneAndSalonIdAndIsUsedFalseAndRevokedAtIsNull(phone, request.salonId());
        if (existingInvite.isPresent() && !existingInvite.get().isExpiredAt(clock.instant())) {
            log.debug("Invite idempotent: active invite already exists for this salon (salonId={})", request.salonId());
            return new InviteResponse(phone, expiresAt);
        }

        String rawToken = tokenGenerator.generateToken();
        var invite = new InvitePersistenceService.PhoneInvite(phone, request.salonId(), targetRole, expiresAt,
                tokenGenerator.hash(rawToken), buildInviteLink(rawToken), salon.getName(), callerId);

        // CORRECTNESS (race path): recycle + INSERT + SMS enqueue run in a SEPARATE physical transaction (REQUIRES_NEW).
        // A concurrent same-(salon, phone) request that raced past the pre-check above trips
        // ux_invite_tokens_active_phone; the violation poisons only the inner transaction (the caller's — possibly
        // SalonService.inviteMaster's — is suspended), so it is caught here and answered with the generic 201: one
        // row, one SMS.
        try {
            invitePersistenceService.persistInviteAndEnqueue(invite);
        } catch (DataIntegrityViolationException e) {
            log.debug("Invite idempotent: concurrent same-salon invite raced the unique guard (salonId={})", request.salonId());
        }
        return new InviteResponse(phone, expiresAt);
    }

    /**
     * Every authorization branch of {@link #sendInvite}, in order; returns the target salon.
     *
     * <p>Phase 21.1 (multi-admin relaxation): an existing SALON_ADMIN may invite a new SALON_ADMIN into their own
     * salon. Fix MEDIUM-2: SALON_ADMIN is assigned to a salon but is NOT its owner, so SALON_OWNER verifies ownership
     * via the owner FK while SALON_ADMIN verifies their assigned salonId matches the request.
     */
    private Salon authorizeInvite(User caller, UUID callerId, UUID salonId, Role targetRole) {
        Salon salon = authorizedSalon(caller, callerId, salonId, targetRole);
        // Audit-fix: a deactivated salon cannot take staff (its invites could never be accepted, AUTH-E03) — refuse
        // before any SMS or outbox row; same code as accept's live-salon check.
        if (!salon.isActive()) {
            throw new InviteTokenException(
                    InviteTokenException.Code.INVITE_SALON_INACTIVE, "This salon is no longer active");
        }
        return salon;
    }

    private Salon authorizedSalon(User caller, UUID callerId, UUID salonId, Role targetRole) {
        if (targetRole != Role.SALON_MASTER && targetRole != Role.SALON_ADMIN) {
            throw new ForbiddenException("Role " + targetRole + " cannot be assigned via invite");
        }
        if (targetRole == Role.SALON_ADMIN
                && caller.getRole() != Role.SALON_OWNER
                && caller.getRole() != Role.SALON_ADMIN) {
            throw new ForbiddenException("Only SALON_OWNER or SALON_ADMIN may invite a SALON_ADMIN");
        }
        if (caller.getRole() == Role.SALON_OWNER) {
            return salonRepository.findByIdAndOwnerId(salonId, callerId)
                    .orElseThrow(() -> new ForbiddenException("You do not own the specified salon"));
        }
        if (caller.getRole() == Role.SALON_ADMIN) {
            if (!salonId.equals(caller.getSalonId())) {
                throw new ForbiddenException("SALON_ADMIN may only invite to their own assigned salon");
            }
            return salonRepository.findById(salonId)
                    .orElseThrow(() -> new NotFoundException("Salon not found"));
        }
        throw new ForbiddenException("Role " + caller.getRole() + " cannot send invites");
    }

    /**
     * Previews an invite by token, without accepting it — the accept screen calls this before sign-up (AUTH-21).
     *
     * <p><strong>Phase 285 — typed failures.</strong> Not-found / revoked / used / expired are distinguished: this
     * endpoint is keyed by possession of a high-entropy single-use token, so there is no enumeration surface to close,
     * and a uniform failure was an undebuggable "why didn't my invite work?" footgun. Scope: this token-keyed pair
     * ({@code previewInvite} / {@link #acceptInvite}) only.
     *
     * <p><strong>B520.</strong> A pre-deploy e-mail invite (no phone) is refused exactly like an unknown token
     * (AUTH-17 copy): e-mail invites stopped working at deploy (AUTH-D16). The response masks the phone
     * ({@code +380 •• ••• •• 45}, D3) and names the salon.
     */
    @Transactional(readOnly = true)
    public InvitePreviewResponse previewInvite(String rawToken) {
        InviteToken token = inviteTokenRepository.findByToken(tokenGenerator.hash(rawToken))
                .filter(found -> !found.isLegacyEmailInvite())
                .orElseThrow(() -> new InviteTokenException(
                        InviteTokenException.Code.INVITE_NOT_FOUND, "Invalid or expired invite token"));

        rejectDeadToken(token);

        String salonName = token.getSalonId() == null
                ? null
                : salonRepository.findById(token.getSalonId()).map(Salon::getName).orElse(null);
        return new InvitePreviewResponse(
                ContactMask.invitePhone(token.getPhone()), token.getRole(), salonName, token.getExpiresAt());
    }

    /**
     * Accepts a phone invite (B520, AUTH-52): the registration ticket must prove the INVITED phone, else 400
     * {@code INVITE_PHONE_MISMATCH} and no account. The account gets that phone verified and no email.
     *
     * <p>Every refusal throws before anything is written, and the token row is locked ({@code findByTokenForUpdate}),
     * so a rejected accept burns neither the single-use invite nor the ticket (consumed in this transaction).
     */
    @Transactional
    public AuthResponse acceptInvite(InviteAcceptRequest request) {
        // Status stays 404 here (NOT the 400 previewInvite uses for the same case) — a deliberate, pre-existing
        // asymmetry phase 285 preserves. A pre-deploy e-mail invite is refused like an unknown token (B520).
        InviteToken token = inviteTokenRepository.findByTokenForUpdate(tokenGenerator.hash(request.token()))
                .filter(found -> !found.isLegacyEmailInvite())
                .orElseThrow(() -> new InviteTokenException(
                        InviteTokenException.Code.INVITE_NOT_FOUND, HttpStatus.NOT_FOUND, "Invite token not found"));

        rejectDeadToken(token);
        requireLiveSalon(token);

        // D4 — the phone match is proven by the B514 ticket (purpose REGISTRATION), required for every phone invite
        // whatever app.auth.require-phone-registration says (that flag only shaped the retired e-mail accept).
        if (!request.hasRegistrationTicket()) {
            throw new IdentityException(IdentityException.Code.REGISTRATION_TICKET_INVALID);
        }
        String verifiedPhone = registrationTicketService.consume(request.registrationTicket());
        if (!verifiedPhone.equals(token.getPhone())) {
            throw new IdentityException(IdentityException.Code.INVITE_PHONE_MISMATCH);
        }
        if (userRepository.existsByVerifiedPhone(verifiedPhone)) {
            throw new IdentityException(IdentityException.Code.PHONE_ALREADY_REGISTERED);
        }

        token.markUsed();
        inviteTokenRepository.save(token);

        Instant now = clock.instant();
        var user = User.createFromPhoneInvite(
                verifiedPhone,
                passwordEncoder.encode(request.password()),
                token.getRole(),
                request.firstName(),
                request.lastName(),
                token.getSalonId(),
                now
        );
        TermsConsentStamp.apply(user, request, now);
        // A ticket-verified phone can lose a race to a concurrent claim: flush through the shared claim path so the
        // partial unique index surfaces as 409 PHONE_ALREADY_REGISTERED, not a 500 at commit.
        var savedUser = PhoneRegistrationService.saveClaimingPhone(userRepository, user);

        if (token.getRole() == Role.SALON_MASTER) {
            masterService.createMasterFromInvite(savedUser.getId(), token.getSalonId());
        }

        // Phase 333, matrix row 5 — after the membership row is written. Owner + every OTHER active admin; the new
        // teammate (savedUser) is the actor and is excluded. Both invitable roles are always salon-bound
        // (SALON_BOUND_ROLES, asserted by requireLiveSalon), so token.getSalonId() is never null here.
        inAppNotificationService.notifyInviteAccepted(token.getSalonId(), savedUser.getId());

        return buildAuthResponse(savedUser);
    }

    /**
     * Revoked, then used, then expired. {@code InviteToken#markCancelled} sets BOTH {@code revokedAt} and
     * {@code isUsed}, so {@code revokedAt} is checked FIRST to report the more specific {@code INVITE_REVOKED}; a
     * SUPERSEDED token leaves {@code isUsed = false} and is reachable only through that first check. Expiry is the
     * canonical {@link InviteToken#isExpiredAt} predicate shared with the recycle filter and the history ladder.
     */
    private void rejectDeadToken(InviteToken token) {
        if (token.getRevokedAt() != null) {
            throw new InviteTokenException(InviteTokenException.Code.INVITE_REVOKED, "This invite is no longer valid");
        }
        if (token.isUsed()) {
            throw new InviteTokenException(InviteTokenException.Code.INVITE_USED, "This invite has already been used");
        }
        if (token.isExpiredAt(clock.instant())) {
            throw new InviteTokenException(InviteTokenException.Code.INVITE_EXPIRED, "This invite has expired");
        }
    }

    /**
     * Phase 286: a salon-bound invite must not be redeemable once its salon has been soft-deactivated. Placed after the
     * token-state checks (a used token must still report "used") and before any write. Fails CLOSED on a null
     * {@code salonId} (only reachable through {@code invite_tokens.salon_id}'s ON DELETE SET NULL FK): skipping would
     * let {@code MasterService#createMasterFromInvite} call {@code findById(null)} — a 500 instead of a clean 409.
     */
    private void requireLiveSalon(InviteToken token) {
        if (!SALON_BOUND_ROLES.contains(token.getRole())) {
            return;
        }
        Salon salon = token.getSalonId() == null
                ? null
                : salonRepository.findById(token.getSalonId()).orElse(null);
        if (salon == null || !salon.isActive()) {
            throw new InviteTokenException(
                    InviteTokenException.Code.INVITE_SALON_INACTIVE, "This salon is no longer active");
        }
    }

    private String buildInviteLink(String rawToken) {
        if (!SchemeGuard.isAllowedScheme(frontendBaseUrl)) {
            throw new IllegalStateException(
                    "app.frontend.base-url must use HTTPS scheme for non-localhost origins");
        }
        return frontendBaseUrl + "/invite/accept?token=" + URLEncoder.encode(rawToken, StandardCharsets.UTF_8);
    }

    private AuthResponse buildAuthResponse(User user) {
        return authResponseBuilder.buildAuthResponse(user);
    }

}
