package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InvitePreviewResponse;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.dto.InviteResponse;
import com.beautica.common.exception.EmailAlreadyRegisteredException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.InviteTokenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.util.SchemeGuard;
import com.beautica.master.service.MasterService;
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
import java.util.Locale;
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
            Clock clock
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
     * Sends a salon invite for the target email.
     *
     * <p><strong>Phase 287 — anti-enumeration reasoning REVERSED for the already-registered
     * branch.</strong> This method used to swallow an already-registered target into the same
     * non-distinguishing generic 201 as a brand-new invite, on the theory that a distinguishing
     * 4xx here would turn this endpoint into an enumeration oracle (an authenticated caller
     * could probe arbitrary emails for registration status). That trade-off is reversed as of
     * phase 287, on the identical precedent {@link AuthService#register}'s duplicate-email 409
     * already established: {@code AuthService#register} is {@code permitAll} and already returns
     * an honest 409 {@code EMAIL_ALREADY_REGISTERED} <em>to anyone on the internet</em>, rejecting
     * the silent-200 as "an undebuggable 'we sent a code, but it never comes' footgun" — the bit
     * this endpoint was hiding is therefore already public to anonymous callers, so silence here
     * was paying the footgun cost to protect nothing. This endpoint's caller is, on top of that,
     * authenticated, role-gated to {@code SALON_OWNER}/{@code SALON_ADMIN}, attributable to a
     * named principal, and rate-limited on two dedicated Bucket4j buckets ({@code inviteBuckets}
     * for {@code POST /api/v1/auth/invite}, {@code salonInviteBuckets} for
     * {@code POST /api/v1/salons/{salonId}/invite}) — it leaks strictly less than
     * {@code register} already does. See the phase-287 doc for the full ruling.
     *
     * <p><strong>Where the throw sits matters.</strong> {@code alreadyRegistered} is computed
     * up front, before the caller/role/salon authorization branches below, but the
     * {@link EmailAlreadyRegisteredException} is thrown only AFTER every authorization check has
     * passed — at the site of what used to be the synthetic-success early return. Throwing at the
     * computation site instead would leak registration status to a caller who is not even
     * authorized to invite into that salon; such a caller must still receive their unchanged
     * {@link ForbiddenException}, regardless of the target's registration status.
     *
     * <p>The duplicate-active-invite branch further down keeps its idempotent 201 — this reversal
     * does not touch it; see its own comment for why.
     *
     * <p><strong>Scope:</strong> this reversal covers only the invite-send path. It does not
     * license changing {@code /auth/forgot-password}, which keeps its deliberate uniform response
     * — that endpoint is keyed by a guessable email from an anonymous caller and is genuinely
     * enumerable.
     */
    @Transactional(readOnly = true)
    public InviteResponse sendInvite(InviteRequest request, UUID callerId) {
        // Normalise e-mail (lower-case + strip) exactly as AuthService does on every write path,
        // so this pre-check and the persisted row agree with the case-insensitive
        // ux_invite_tokens_active index (lower(email), salon_id). Without this, re-inviting
        // "a@x" while an active "A@x" token exists would miss the salon-scoped pre-check yet
        // still collide on the lower(email) guard — silently dropping the invite (no token, no
        // e-mail). Normalising once here keeps existsByEmail, the finder, the persisted token,
        // the outbox enqueue and the response all on the same canonical value.
        String email = request.email().toLowerCase(Locale.ROOT).strip();

        // Phase 287: computed here, BEFORE the authorization branches below, purely so the
        // slow DB round-trip happens once regardless of outcome — the resulting boolean is NOT
        // acted on until after every authorization check passes (see the throw at the site of
        // the former synthetic-success early return, further down). Acting on it here would let
        // an unauthorized caller learn a target's registration status via a ForbiddenException
        // that never fires; see the method javadoc for the full reversal.
        boolean alreadyRegistered = userRepository.existsByEmail(email);

        User caller = userRepository.findById(callerId)
                .orElseThrow(() -> new NotFoundException("Caller not found"));

        Role targetRole = request.effectiveRole();

        if (targetRole != Role.SALON_MASTER && targetRole != Role.SALON_ADMIN) {
            throw new ForbiddenException("Role " + targetRole + " cannot be assigned via invite");
        }

        // Phase 21.1 (multi-admin relaxation): an existing SALON_ADMIN may now also invite
        // a new SALON_ADMIN into their own salon. The salon-scoping branch below (SALON_ADMIN
        // callers verify request.salonId() == caller.getSalonId()) still fully applies, so a
        // SALON_ADMIN can never invite an admin into a salon other than their own.
        if (targetRole == Role.SALON_ADMIN
                && caller.getRole() != Role.SALON_OWNER
                && caller.getRole() != Role.SALON_ADMIN) {
            throw new ForbiddenException("Only SALON_OWNER or SALON_ADMIN may invite a SALON_ADMIN");
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

        Instant expiresAt = clock.instant().plus(tokenExpirationHours, ChronoUnit.HOURS);

        // Phase 287: honest 409, thrown HERE — after every authorization/ownership branch above
        // has already passed — never at the `alreadyRegistered` computation site up top. Reusing
        // the EXISTING EmailAlreadyRegisteredException/EMAIL_ALREADY_REGISTERED code (same type
        // AuthService#register throws for the same wire code) rather than minting a second
        // spelling of it: one code, one handler, no divergence. See the method javadoc for the
        // full reversal and why this is safe on this endpoint specifically.
        if (alreadyRegistered) {
            throw new EmailAlreadyRegisteredException();
        }

        // A pre-existing *active* (unused, unexpired) invite for THIS salon is an idempotent
        // success — return the same generic response WITHOUT issuing a second token or e-mail.
        // UNCHANGED by phase 287: this branch's justification was never anti-enumeration secrecy
        // in the first place, it is correctness — re-inviting the same person to the same salon
        // should not mint a second token or send a second e-mail, independent of the target's
        // registration status. The comment that used to live here argued raising a 409 would
        // re-open a 200-vs-409 registration-status oracle when combined with the (now-removed)
        // already-registered silent-201 above; that argument is void now that the already-
        // registered branch throws its own honest 409 directly two paragraphs up — there is no
        // longer a silent branch for a 409 here to be compared against. This branch keeps its 201
        // because it is the correct idempotent response, not because silence protects anything.
        // An expired prior token is retired (marked SUPERSEDED, not deleted) before a fresh one
        // is issued.
        //
        // SECURITY/CORRECTNESS (cross-salon silent-drop): the lookup is salon-scoped via
        // salonId. An email-global lookup let salon A's pending invite short-circuit salon B's
        // dispatch — B's owner saw a false success while no invite for B was ever created. Each
        // salon now decides idempotency independently against its own pending invite.
        Optional<InviteToken> existingInvite =
                inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalseAndRevokedAtIsNull(
                        email, request.salonId());
        // Negation of the SAME canonical predicate the recycle filter uses (InviteToken#isExpired):
        // "still live" here and "recyclable" in InvitePersistenceService must partition the
        // timeline with no gap and no overlap. Two open-coded comparisons could leave a token
        // that is neither — short-circuiting as idempotent while the row is never retired.
        if (existingInvite.isPresent() && !existingInvite.get().isExpiredAt(clock.instant())) {
            log.debug("Invite idempotent: active invite already exists for this salon (salonId={})", request.salonId());
            return new InviteResponse(email, expiresAt);
        }
        // An expired-but-unused token still carries is_used = false; it occupies the
        // ux_invite_tokens_active slot for (lower(email), salon_id) until revoked_at is set. It is
        // retired (marked SUPERSEDED — kept as history, never deleted) atomically inside
        // persistInviteAndEnqueue, in the same transaction as the new INSERT, so the update and
        // insert never deadlock across transaction boundaries.

        String rawToken = tokenGenerator.generateToken();
        String hashedToken = tokenGenerator.hash(rawToken);
        String inviteLink = buildInviteLink(rawToken);

        // CORRECTNESS (race path): the recycle + INSERT + outbox enqueue run in a SEPARATE
        // physical transaction (REQUIRES_NEW on InvitePersistenceService). A concurrent
        // same-(salon, lower(email)) request that raced past the pre-check above trips the
        // ux_invite_tokens_active guard, throwing DataIntegrityViolationException. Because that
        // violation poisons only the inner transaction (the caller's — possibly
        // SalonService.inviteMaster's — transaction is suspended), we can catch it here and
        // return the generic success WITHOUT triggering UnexpectedRollbackException. The loser
        // gets a generic 201, no second token, and no second outbox row (both roll back together).
        try {
            invitePersistenceService.persistInviteAndEnqueue(
                    email, request.salonId(), targetRole, expiresAt, hashedToken, inviteLink, salon.getName());
        } catch (DataIntegrityViolationException e) {
            log.debug("Invite idempotent: concurrent same-salon invite raced the unique guard (salonId={})", request.salonId());
            return new InviteResponse(email, expiresAt);
        }

        return new InviteResponse(email, expiresAt);
    }

    /**
     * Previews an invite by token, without accepting it — the mobile "who invited you" landing
     * screen calls this before the invitee sets a password.
     *
     * <p><strong>Phase 285 — anti-enumeration reasoning REVERSED.</strong> This method used to
     * collapse every failure (not-found / used / expired / revoked) into one identical
     * {@code BusinessException} message, on the theory that distinguishing "revoked" from "never
     * existed" would turn this endpoint into an invite-id oracle. That reasoning is reversed as of
     * phase 285, on the same precedent {@link AuthService#register}'s duplicate-email 409 already
     * established: a uniform failure is an undebuggable "why didn't my invite work?" footgun for
     * the invitee, and the anti-enumeration argument for silence does not actually hold on THIS
     * endpoint — it is keyed by possession of a high-entropy single-use token, not a guessable
     * identifier like an email, so there is no enumeration surface to close. A caller without a
     * valid token learns only {@code INVITE_NOT_FOUND}, which they already knew by definition; a
     * caller with a valid token learns the state of an invite that was mailed to them. See
     * phase-285's doc for the full two-part ruling (user + architect).
     *
     * <p>Scope note: this reasoning covers ONLY this token-keyed pair of endpoints
     * ({@code previewInvite} / {@link #acceptInvite}). It does not license distinguishing failures
     * on any email-keyed endpoint — {@code /auth/forgot-password} keeps its deliberate uniform
     * response.
     */
    @Transactional(readOnly = true)
    public InvitePreviewResponse previewInvite(String rawToken) {
        InviteToken token = inviteTokenRepository.findByToken(tokenGenerator.hash(rawToken))
                .orElseThrow(() -> new InviteTokenException(
                        InviteTokenException.Code.INVITE_NOT_FOUND, "Invalid or expired invite token"));

        // InviteToken.isExpiredAt is THE canonical expiry predicate — shared with acceptInvite,
        // InvitePersistenceService's recycle filter, SalonService#cancelInvite and the history
        // status ladder, so no two of them can drift at the boundary instant. See its Javadoc.
        //
        // Order: revoked, then used, then expired. InviteToken#markCancelled sets BOTH revokedAt
        // AND isUsed, so revokedAt must be checked FIRST to report the more specific
        // INVITE_REVOKED rather than INVITE_USED; InviteToken#markSuperseded leaves isUsed =
        // false, so a superseded token is reachable ONLY through this first check (see
        // InviteAcceptRejectsRevokedIntegrationTest — "a revoked token is dead regardless of what
        // retired it").
        if (token.getRevokedAt() != null) {
            throw new InviteTokenException(InviteTokenException.Code.INVITE_REVOKED, "This invite is no longer valid");
        }
        if (token.isUsed()) {
            throw new InviteTokenException(InviteTokenException.Code.INVITE_USED, "This invite has already been used");
        }
        if (token.isExpiredAt(clock.instant())) {
            throw new InviteTokenException(InviteTokenException.Code.INVITE_EXPIRED, "This invite has expired");
        }

        return new InvitePreviewResponse(token.getEmail(), token.getRole(), token.getExpiresAt());
    }

    @Transactional
    public AuthResponse acceptInvite(InviteAcceptRequest request) {
        // Status stays 404 here (NOT the 400 previewInvite uses for the same case) — a deliberate,
        // pre-existing asymmetry phase 285 preserves rather than "tidies" (see InviteTokenException's
        // javadoc and the phase doc's backward-compatibility gate).
        InviteToken token = inviteTokenRepository.findByTokenForUpdate(tokenGenerator.hash(request.token()))
                .orElseThrow(() -> new InviteTokenException(
                        InviteTokenException.Code.INVITE_NOT_FOUND, HttpStatus.NOT_FOUND, "Invite token not found"));

        // Same ordering rationale as previewInvite: revoked before used, so a CANCELLED token
        // (which sets both) reports INVITE_REVOKED, not the less specific INVITE_USED. A SUPERSEDED
        // token leaves isUsed = false and is reachable only through this first check.
        if (token.getRevokedAt() != null) {
            throw new InviteTokenException(InviteTokenException.Code.INVITE_REVOKED, "This invite is no longer valid");
        }

        if (token.isUsed()) {
            throw new InviteTokenException(InviteTokenException.Code.INVITE_USED, "This invite has already been used");
        }

        // Same canonical predicate as previewInvite / the history ladder (InviteToken#isExpired).
        if (token.isExpiredAt(clock.instant())) {
            throw new InviteTokenException(InviteTokenException.Code.INVITE_EXPIRED, "This invite has expired");
        }

        // Phase 285: reuse the EXISTING EmailAlreadyRegisteredException/EMAIL_ALREADY_REGISTERED
        // code (AuthService#register throws the same type for the same wire code) rather than
        // minting a second spelling of it as an InviteTokenException.Code — one code, one handler,
        // no divergence. See InviteTokenException's class javadoc.
        if (userRepository.existsByEmail(token.getEmail())) {
            throw new EmailAlreadyRegisteredException();
        }

        // Phase 286: a salon-bound invite must not be redeemable once its salon has been
        // soft-deactivated (SalonService.deactivateSalon). Placed AFTER the
        // used/revoked/expired/email checks (those are cheaper and are properties of the token
        // itself — a used token must still report "used", not "salon inactive") and BEFORE
        // markUsed() (a rejected redemption must not burn the single-use token;
        // findByTokenForUpdate's row lock means throwing here rolls the whole transaction back
        // with no partial state).
        //
        // SECURITY (fail-closed on null salonId): InviteRequest.salonId() is @NotNull and
        // InviteService#sendInvite only ever mints tokens for SALON_MASTER/SALON_ADMIN
        // (InviteRequest#isRoleAllowed) — both always salon-bound at creation, so a live token
        // for either role NEVER has a legitimately null salonId. The one way salon_id can go
        // null on a live row is invite_tokens.salon_id's ON DELETE SET NULL FK
        // (V5__Fix_invite_tokens_cascade.sql) firing when the referenced salons row is hard-
        // deleted — currently unreachable (SalonService.deactivateSalon only soft-deletes,
        // nothing hard-deletes a Salon) but must fail closed, not silently skip, in case a
        // future admin/cleanup tool arms it: skipping would let acceptInvite proceed and
        // MasterService#createMasterFromInvite would then call
        // salonRepository.findById(null), which Spring Data rejects with an uncaught
        // IllegalArgumentException (500) instead of a clean 409. SALON_BOUND_ROLES is therefore
        // the set of roles for which sendInvite requires a resolved Salon before issuing a
        // token; a role outside that set (none exist today — see the set's Javadoc) is
        // genuinely salon-less and skips this guard entirely, exactly as before.
        if (SALON_BOUND_ROLES.contains(token.getRole())) {
            Salon salon = token.getSalonId() == null
                    ? null
                    : salonRepository.findById(token.getSalonId()).orElse(null);
            if (salon == null || !salon.isActive()) {
                throw new InviteTokenException(
                        InviteTokenException.Code.INVITE_SALON_INACTIVE, "This salon is no longer active");
            }
        }

        token.markUsed();
        inviteTokenRepository.save(token);

        // Accepting the invite IS the email verification: the token was single-use and
        // emailed to token.getEmail(); User.createFromInvite marks the account verified
        // structurally so this path can never regress to the locked-out-forever bug.
        var user = User.createFromInvite(
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
