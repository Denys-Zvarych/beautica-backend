package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InvitePreviewResponse;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.registration.RegistrationTicketService;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.IdentityException;
import com.beautica.common.exception.InviteTokenException;
import com.beautica.master.service.MasterService;
import com.beautica.notification.inapp.service.InAppNotificationService;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B520 — {@link InviteService} by phone: create (input, AUTH-18 ordering, idempotency, race), validate (masked phone,
 * pre-deploy email rows refused) and accept (the registration ticket must prove the invited phone, AUTH-52). The
 * owner/admin permission matrix lives in {@link InviteServiceAdminTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InviteService — phone invites (B520)")
class InviteServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final String PHONE = "+380501234567";
    private static final String OTHER_PHONE = "+380671112233";
    private static final String TICKET = "ticket-abc";
    private static final String RAW_TOKEN = "raw-invite-token";
    private static final String HASHED_TOKEN = "hashed-invite-token";

    @Mock private InviteTokenRepository inviteTokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private InvitePersistenceService invitePersistenceService;
    @Mock private TokenGenerator tokenGenerator;
    @Mock private MasterService masterService;
    @Mock private AuthResponseBuilder authResponseBuilder;
    @Mock private InAppNotificationService inAppNotificationService;
    @Mock private RegistrationTicketService registrationTicketService;

    private final InviteConflictProbeBudget probeBudget =
            new InviteConflictProbeBudget(Clock.fixed(NOW, ZoneOffset.UTC));
    private InviteService inviteService;
    private final UUID salonId = UUID.randomUUID();
    private final UUID callerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        inviteService = service("https://beautica.app");
    }

    private InviteService service(String frontendBaseUrl) {
        return new InviteService(inviteTokenRepository, userRepository, salonRepository, new BCryptPasswordEncoder(4),
                tokenGenerator, masterService, authResponseBuilder, invitePersistenceService, frontendBaseUrl, 48L,
                Clock.fixed(NOW, ZoneOffset.UTC), inAppNotificationService, registrationTicketService, probeBudget);
    }

    // ── create ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create: persists ONE phone invite with the hashed token, the join link, salon name and inviter")
    void should_persistPhoneInvite_when_ownerInvitesUnregisteredPhone() {
        stubOwnerOfSalon("Студія Аврора");
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);

        var response = inviteService.sendInvite(new InviteRequest(PHONE, salonId, null), callerId);

        var captor = ArgumentCaptor.forClass(InvitePersistenceService.PhoneInvite.class);
        verify(invitePersistenceService).persistInviteAndEnqueue(captor.capture());
        var invite = captor.getValue();
        assertThat(invite.phoneE164()).isEqualTo(PHONE);
        assertThat(invite.hashedToken()).isEqualTo(HASHED_TOKEN);
        assertThat(invite.inviteLink()).isEqualTo("https://beautica.app/invite/accept?token=" + RAW_TOKEN);
        assertThat(invite.salonName()).isEqualTo("Студія Аврора");
        assertThat(invite.inviterId()).isEqualTo(callerId);
        assertThat(invite.role()).isEqualTo(Role.SALON_MASTER);
        assertThat(invite.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(48)));
        assertThat(response.invitedPhone()).isEqualTo(PHONE);
        assertThat(response.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(48)));
    }

    @Test
    @DisplayName("create: any human spelling of a UA mobile is normalised to +380 before every lookup and the insert")
    void should_normalisePhone_when_requestUsesHumanSpelling() {
        stubOwnerOfSalon("S");
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);

        var response = inviteService.sendInvite(new InviteRequest("050 123-45-67", salonId, null), callerId);

        verify(userRepository).existsByVerifiedPhone(PHONE);
        verify(inviteTokenRepository).findByPhoneAndSalonIdAndIsUsedFalseAndRevokedAtIsNull(PHONE, salonId);
        assertThat(response.invitedPhone()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("create: a body without a phone (old build sending only email) → 400 INVITE_PHONE_REQUIRED, nothing read")
    void should_throwInvitePhoneRequired_when_phoneMissing() {
        var thrown = catchThrowableOfType(
                () -> inviteService.sendInvite(new InviteRequest(null, salonId, null), callerId),
                IdentityException.class);

        assertThat(thrown.getCode()).isEqualTo(IdentityException.Code.INVITE_PHONE_REQUIRED);
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(userRepository, salonRepository, inviteTokenRepository, invitePersistenceService);
    }

    @Test
    @DisplayName("create: a landline / foreign number → 400 PHONE_NOT_UA_MOBILE before any lookup or SMS")
    void should_throwPhoneNotUaMobile_when_phoneIsLandline() {
        var thrown = catchThrowableOfType(
                () -> inviteService.sendInvite(new InviteRequest("+380441234567", salonId, null), callerId),
                IdentityException.class);

        assertThat(thrown.getCode()).isEqualTo(IdentityException.Code.PHONE_NOT_UA_MOBILE);
        verifyNoInteractions(userRepository, salonRepository, inviteTokenRepository, invitePersistenceService);
    }

    @Test
    @DisplayName("create: a phone verified on any account → 409 PHONE_ALREADY_REGISTERED after authorization, no invite")
    void should_throwPhoneAlreadyRegistered_when_phoneVerifiedOnAnyAccount() {
        when(userRepository.existsByVerifiedPhone(PHONE)).thenReturn(true);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(owner()));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(activeSalon()));

        var thrown = catchThrowableOfType(
                () -> inviteService.sendInvite(new InviteRequest(PHONE, salonId, null), callerId),
                IdentityException.class);

        assertThat(thrown.getCode()).isEqualTo(IdentityException.Code.PHONE_ALREADY_REGISTERED);
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        verify(salonRepository).findByIdAndOwnerId(salonId, callerId);
        verify(inviteTokenRepository, never()).findByPhoneAndSalonIdAndIsUsedFalseAndRevokedAtIsNull(any(), any());
        verifyNoInteractions(invitePersistenceService);
    }

    @Test
    @DisplayName("create: the inviter's 11th 409 within the hour → 429 INVITE_SMS_LIMIT (probe budget), no invite")
    void should_throwSmsLimit_when_inviterSpentConflictProbeBudget() {
        when(userRepository.existsByVerifiedPhone(PHONE)).thenReturn(true);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(owner()));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(activeSalon()));
        var request = new InviteRequest(PHONE, salonId, null);
        for (int i = 0; i < InviteConflictProbeBudget.MAX_CONFLICTS; i++) {
            assertThat(catchThrowableOfType(() -> inviteService.sendInvite(request, callerId), IdentityException.class)
                    .getCode()).isEqualTo(IdentityException.Code.PHONE_ALREADY_REGISTERED);
        }

        var thrown = catchThrowableOfType(() -> inviteService.sendInvite(request, callerId), IdentityException.class);

        assertThat(thrown.getCode()).isEqualTo(IdentityException.Code.INVITE_SMS_LIMIT);
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verifyNoInteractions(invitePersistenceService);
    }

    @Test
    @DisplayName("create: once the probe budget is spent an UNREGISTERED phone is 429 too (no 429-vs-201 oracle)")
    void should_throwSmsLimitForUnregisteredPhone_when_conflictProbeBudgetSpent() {
        stubOwnerOfSalonWithoutName();
        for (int i = 0; i < InviteConflictProbeBudget.MAX_CONFLICTS; i++) {
            probeBudget.tryRecordConflict(callerId);
        }

        var thrown = catchThrowableOfType(
                () -> inviteService.sendInvite(new InviteRequest(PHONE, salonId, null), callerId),
                IdentityException.class);

        assertThat(thrown.getCode()).isEqualTo(IdentityException.Code.INVITE_SMS_LIMIT);
        verifyNoInteractions(invitePersistenceService);
    }

    @Test
    @DisplayName("create: a deactivated salon → 409 INVITE_SALON_INACTIVE after authorization, nothing persisted or sent")
    void should_throwSalonInactive_when_invitingIntoDeactivatedSalon() {
        var inactive = mock(Salon.class);
        when(inactive.isActive()).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(owner()));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(inactive));

        var thrown = catchThrowableOfType(
                () -> inviteService.sendInvite(new InviteRequest(PHONE, salonId, null), callerId),
                InviteTokenException.class);

        assertThat(thrown.getCode()).isEqualTo(InviteTokenException.Code.INVITE_SALON_INACTIVE);
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.CONFLICT);
        verifyNoInteractions(invitePersistenceService);
    }

    @Test
    @DisplayName("create: an unauthorized caller gets 403, never the 409 that would reveal a registered phone (AUTH-18)")
    void should_throwForbiddenNotConflict_when_callerDoesNotOwnSalonAndPhoneIsRegistered() {
        when(userRepository.existsByVerifiedPhone(PHONE)).thenReturn(true);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(owner()));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inviteService.sendInvite(new InviteRequest(PHONE, salonId, null), callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("do not own");
        verifyNoInteractions(invitePersistenceService);
    }

    @Test
    @DisplayName("create: a live invite to the same phone for this salon is an idempotent 201 — no second token, no SMS")
    void should_returnIdempotentSuccess_when_activeInviteExists() {
        stubOwnerOfSalonWithoutName();
        when(inviteTokenRepository.findByPhoneAndSalonIdAndIsUsedFalseAndRevokedAtIsNull(PHONE, salonId))
                .thenReturn(Optional.of(phoneInvite(NOW.plusSeconds(3600))));

        var response = inviteService.sendInvite(new InviteRequest(PHONE, salonId, null), callerId);

        assertThat(response.invitedPhone()).isEqualTo(PHONE);
        verifyNoInteractions(invitePersistenceService);
        verify(tokenGenerator, never()).generateToken();
    }

    @Test
    @DisplayName("create: an expired invite in the slot does not short-circuit — the delegate recycles it and sends")
    void should_delegate_when_expiredInviteExists() {
        stubOwnerOfSalon("S");
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);
        when(inviteTokenRepository.findByPhoneAndSalonIdAndIsUsedFalseAndRevokedAtIsNull(PHONE, salonId))
                .thenReturn(Optional.of(phoneInvite(NOW)));

        inviteService.sendInvite(new InviteRequest(PHONE, salonId, null), callerId);

        verify(invitePersistenceService).persistInviteAndEnqueue(any());
    }

    @Test
    @DisplayName("create: a same-phone race lost at the unique guard resolves to the generic 201 (one row, one SMS)")
    void should_returnGenericSuccess_when_persistenceRacesUniqueGuard() {
        stubOwnerOfSalon("S");
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);
        doThrow(new DataIntegrityViolationException("ux_invite_tokens_active_phone"))
                .when(invitePersistenceService).persistInviteAndEnqueue(any());

        var response = inviteService.sendInvite(new InviteRequest(PHONE, salonId, null), callerId);

        assertThat(response.invitedPhone()).isEqualTo(PHONE);
    }

    @Test
    @DisplayName("create: the SMS cap (429 INVITE_SMS_LIMIT) from the delegate reaches the caller")
    void should_propagateSmsLimit_when_delegateRefusesOverCap() {
        stubOwnerOfSalon("S");
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);
        doThrow(new IdentityException(IdentityException.Code.INVITE_SMS_LIMIT))
                .when(invitePersistenceService).persistInviteAndEnqueue(any());

        var thrown = catchThrowableOfType(
                () -> inviteService.sendInvite(new InviteRequest(PHONE, salonId, null), callerId),
                IdentityException.class);

        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("create: an http:// (non-localhost) frontend base URL never builds a link — fail closed")
    void should_throwIllegalState_when_frontendBaseUrlIsPlainHttp() {
        var httpService = service("http://example.com");
        stubOwnerOfSalonWithoutName();
        when(tokenGenerator.generateToken()).thenReturn(RAW_TOKEN);

        assertThatThrownBy(() -> httpService.sendInvite(new InviteRequest(PHONE, salonId, null), callerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use HTTPS scheme");
        verifyNoInteractions(invitePersistenceService);
    }

    @Test
    @DisplayName("validateConfig: plain http fails boot, https and http://localhost pass")
    void should_validateFrontendBaseUrlScheme_when_contextStarts() {
        assertThatThrownBy(() -> service("http://example.com").validateConfig())
                .isInstanceOf(IllegalStateException.class);
        service("https://beautica.app").validateConfig();
        service("http://localhost:3000").validateConfig();
    }

    // ── validate (preview) ───────────────────────────────────────────────────

    @Test
    @DisplayName("validate: returns the MASKED phone, role, salon name and expiry — never the full number (D3)")
    void should_returnMaskedPhoneAndSalonName_when_previewingPhoneInvite() {
        var invite = phoneInvite(NOW.plusSeconds(3600));
        var salon = mock(Salon.class);
        when(salon.getName()).thenReturn("Студія Аврора");
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(inviteTokenRepository.findByToken(HASHED_TOKEN)).thenReturn(Optional.of(invite));
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        InvitePreviewResponse preview = inviteService.previewInvite(RAW_TOKEN);

        assertThat(preview.phoneMasked()).isEqualTo("+380 •• ••• •• 67").doesNotContain("501234");
        assertThat(preview.role()).isEqualTo(Role.SALON_MASTER);
        assertThat(preview.salonName()).isEqualTo("Студія Аврора");
        assertThat(preview.expiresAt()).isEqualTo(invite.getExpiresAt());
    }

    @Test
    @DisplayName("validate: a pre-deploy EMAIL invite is refused exactly like an unknown token (400 INVITE_NOT_FOUND)")
    void should_refuseLegacyEmailInvite_when_previewing() {
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(inviteTokenRepository.findByToken(HASHED_TOKEN)).thenReturn(Optional.of(emailInvite()));

        var thrown = catchThrowableOfType(() -> inviteService.previewInvite(RAW_TOKEN), InviteTokenException.class);

        assertThat(thrown.getCode()).isEqualTo(InviteTokenException.Code.INVITE_NOT_FOUND);
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(thrown).hasMessage("Invalid or expired invite token");
    }

    @Test
    @DisplayName("validate: unknown token → 400 INVITE_NOT_FOUND")
    void should_throwNotFound_when_previewTokenUnknown() {
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(inviteTokenRepository.findByToken(HASHED_TOKEN)).thenReturn(Optional.empty());

        var thrown = catchThrowableOfType(() -> inviteService.previewInvite(RAW_TOKEN), InviteTokenException.class);

        assertThat(thrown.getCode()).isEqualTo(InviteTokenException.Code.INVITE_NOT_FOUND);
    }

    @Test
    @DisplayName("validate: revoked wins over used (a cancelled token sets both), then used, then expired")
    void should_reportTypedFailure_when_previewTokenIsDead() {
        when(tokenGenerator.hash(anyString())).thenAnswer(inv -> inv.getArgument(0));
        var cancelled = phoneInvite(NOW.plusSeconds(3600));
        cancelled.markCancelled(NOW);
        var used = phoneInvite(NOW.plusSeconds(3600));
        used.markUsed();
        var expired = phoneInvite(NOW);
        when(inviteTokenRepository.findByToken("c")).thenReturn(Optional.of(cancelled));
        when(inviteTokenRepository.findByToken("u")).thenReturn(Optional.of(used));
        when(inviteTokenRepository.findByToken("e")).thenReturn(Optional.of(expired));

        assertThat(codeOfPreview("c")).isEqualTo(InviteTokenException.Code.INVITE_REVOKED);
        assertThat(codeOfPreview("u")).isEqualTo(InviteTokenException.Code.INVITE_USED);
        assertThat(codeOfPreview("e")).isEqualTo(InviteTokenException.Code.INVITE_EXPIRED);
    }

    // ── accept ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("accept: a ticket for the INVITED phone creates the account with that phone verified and no email")
    void should_createAccountWithVerifiedPhoneAndNoEmail_when_ticketPhoneMatchesInvite() {
        var invite = stubLiveAcceptableInvite(Role.SALON_MASTER);
        when(registrationTicketService.consume(TICKET)).thenReturn(PHONE);
        var userId = UUID.randomUUID();
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", userId);
            return u;
        });
        var auth = AuthResponse.of("a", "r", userId, null, Role.SALON_MASTER, salonId);
        when(authResponseBuilder.buildAuthResponse(any(User.class))).thenReturn(auth);

        var response = inviteService.acceptInvite(acceptRequest(TICKET));

        var userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(userCaptor.capture());
        User created = userCaptor.getValue();
        assertThat(created.getEmail()).isNull();
        assertThat(created.getPhoneNumber()).isEqualTo(PHONE);
        assertThat(created.getPhoneVerifiedAt()).isEqualTo(NOW);
        assertThat(created.isEmailVerified()).isFalse();
        assertThat(created.getRole()).isEqualTo(Role.SALON_MASTER);
        assertThat(created.getSalonId()).isEqualTo(salonId);
        assertThat(invite.isUsed()).isTrue();
        verify(masterService).createMasterFromInvite(userId, salonId);
        verify(inAppNotificationService).notifyInviteAccepted(salonId, userId);
        assertThat(response).isSameAs(auth);
    }

    @Test
    @DisplayName("accept: a ticket for ANOTHER phone → 400 INVITE_PHONE_MISMATCH, no account, invite not burned "
            + "(mutation guard: dropping the equality check turns this red)")
    void should_refuseWithMismatch_when_ticketPhoneDiffersFromInvite() {
        var invite = stubLiveAcceptableInvite(Role.SALON_MASTER);
        when(registrationTicketService.consume(TICKET)).thenReturn(OTHER_PHONE);

        var thrown = catchThrowableOfType(() -> inviteService.acceptInvite(acceptRequest(TICKET)),
                IdentityException.class);

        assertThat(thrown.getCode()).isEqualTo(IdentityException.Code.INVITE_PHONE_MISMATCH);
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invite.isUsed()).isFalse();
        verify(userRepository, never()).saveAndFlush(any());
        verify(userRepository, never()).save(any());
        verifyNoInteractions(masterService);
    }

    @Test
    @DisplayName("accept: no registration ticket → 400 REGISTRATION_TICKET_INVALID whatever the B514 flag, no account")
    void should_refuse_when_ticketMissing() {
        var invite = stubLiveAcceptableInvite(Role.SALON_MASTER);

        var thrown = catchThrowableOfType(() -> inviteService.acceptInvite(acceptRequest(null)),
                IdentityException.class);

        assertThat(thrown.getCode()).isEqualTo(IdentityException.Code.REGISTRATION_TICKET_INVALID);
        assertThat(invite.isUsed()).isFalse();
        verifyNoInteractions(registrationTicketService);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("accept: the invited phone already verified on an account → 409 PHONE_ALREADY_REGISTERED, no account")
    void should_throwConflict_when_invitedPhoneAlreadyClaimed() {
        var invite = stubLiveAcceptableInvite(Role.SALON_MASTER);
        when(registrationTicketService.consume(TICKET)).thenReturn(PHONE);
        when(userRepository.existsByVerifiedPhone(PHONE)).thenReturn(true);

        var thrown = catchThrowableOfType(() -> inviteService.acceptInvite(acceptRequest(TICKET)),
                IdentityException.class);

        assertThat(thrown.getCode()).isEqualTo(IdentityException.Code.PHONE_ALREADY_REGISTERED);
        assertThat(invite.isUsed()).isFalse();
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("accept: a concurrent claim of the phone between pre-check and flush → 409, no master row")
    void should_throwConflict_when_verifiedPhoneIndexViolatedOnFlush() {
        stubLiveAcceptableInvite(Role.SALON_MASTER);
        when(registrationTicketService.consume(TICKET)).thenReturn(PHONE);
        when(userRepository.saveAndFlush(any(User.class))).thenThrow(new DataIntegrityViolationException("dup",
                new RuntimeException("duplicate key value violates unique constraint \"ux_users_verified_phone\"")));

        var thrown = catchThrowableOfType(() -> inviteService.acceptInvite(acceptRequest(TICKET)),
                IdentityException.class);

        assertThat(thrown.getCode()).isEqualTo(IdentityException.Code.PHONE_ALREADY_REGISTERED);
        verifyNoInteractions(masterService);
    }

    @Test
    @DisplayName("accept: a pre-deploy EMAIL invite token is refused like an unknown token (404), no ticket consumed")
    void should_refuseLegacyEmailInvite_when_accepting() {
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(inviteTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.of(emailInvite()));

        var thrown = catchThrowableOfType(() -> inviteService.acceptInvite(acceptRequest(TICKET)),
                InviteTokenException.class);

        assertThat(thrown.getCode()).isEqualTo(InviteTokenException.Code.INVITE_NOT_FOUND);
        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        verifyNoInteractions(registrationTicketService);
        verify(userRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("accept: unknown token keeps its 404 INVITE_NOT_FOUND (phase 285 backward-compat)")
    void should_throw404_when_acceptTokenUnknown() {
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(inviteTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.empty());

        var thrown = catchThrowableOfType(() -> inviteService.acceptInvite(acceptRequest(TICKET)),
                InviteTokenException.class);

        assertThat(thrown.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(thrown.getCode()).isEqualTo(InviteTokenException.Code.INVITE_NOT_FOUND);
    }

    @Test
    @DisplayName("accept: an expired phone invite → INVITE_EXPIRED, no ticket consumed")
    void should_throwExpired_when_acceptingExpiredPhoneInvite() {
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(inviteTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.of(phoneInvite(NOW)));

        var thrown = catchThrowableOfType(() -> inviteService.acceptInvite(acceptRequest(TICKET)),
                InviteTokenException.class);

        assertThat(thrown.getCode()).isEqualTo(InviteTokenException.Code.INVITE_EXPIRED);
        verifyNoInteractions(registrationTicketService);
    }

    @Test
    @DisplayName("accept: a cancelled token reports INVITE_REVOKED (revoked checked before used)")
    void should_throwRevoked_when_acceptingCancelledInvite() {
        var invite = phoneInvite(NOW.plusSeconds(3600));
        invite.markCancelled(NOW);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(inviteTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.of(invite));

        var thrown = catchThrowableOfType(() -> inviteService.acceptInvite(acceptRequest(TICKET)),
                InviteTokenException.class);

        assertThat(thrown.getCode()).isEqualTo(InviteTokenException.Code.INVITE_REVOKED);
    }

    @Test
    @DisplayName("accept: an inactive salon → 409 INVITE_SALON_INACTIVE before the ticket is consumed")
    void should_throwSalonInactive_when_salonDeactivated() {
        var invite = phoneInvite(NOW.plusSeconds(3600));
        var salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(false);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(inviteTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.of(invite));
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));

        var thrown = catchThrowableOfType(() -> inviteService.acceptInvite(acceptRequest(TICKET)),
                InviteTokenException.class);

        assertThat(thrown.getCode()).isEqualTo(InviteTokenException.Code.INVITE_SALON_INACTIVE);
        assertThat(invite.isUsed()).isFalse();
        verifyNoInteractions(registrationTicketService);
    }

    @Test
    @DisplayName("accept: a salon-bound token whose salonId went null fails closed (409) without a findById(null)")
    void should_failClosed_when_salonIdIsNull() {
        var invite = phoneInvite(NOW.plusSeconds(3600));
        ReflectionTestUtils.setField(invite, "salonId", null);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(inviteTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.of(invite));

        var thrown = catchThrowableOfType(() -> inviteService.acceptInvite(acceptRequest(TICKET)),
                InviteTokenException.class);

        assertThat(thrown.getCode()).isEqualTo(InviteTokenException.Code.INVITE_SALON_INACTIVE);
        verify(salonRepository, never()).findById(any());
    }

    @Test
    @DisplayName("accept DTO: @StrongPassword still rejects a weak password")
    void should_rejectWeakPassword_when_acceptRequestValidated() {
        var request = new InviteAcceptRequest("t", "aaaaaaaaaaaa", "First", "Last", null, null, null, null, TICKET);

        var violations = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator().validate(request);

        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private InviteTokenException.Code codeOfPreview(String token) {
        return catchThrowableOfType(() -> inviteService.previewInvite(token), InviteTokenException.class).getCode();
    }

    private InviteToken stubLiveAcceptableInvite(Role role) {
        var invite = InviteToken.forPhone(HASHED_TOKEN, PHONE, salonId, role, NOW.plusSeconds(3600), callerId);
        var salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(true);
        when(tokenGenerator.hash(RAW_TOKEN)).thenReturn(HASHED_TOKEN);
        when(inviteTokenRepository.findByTokenForUpdate(HASHED_TOKEN)).thenReturn(Optional.of(invite));
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        return invite;
    }

    private static InviteAcceptRequest acceptRequest(String ticket) {
        return new InviteAcceptRequest(RAW_TOKEN, "Str0ngP@ss1!", "Олена", "Коваль", null, null, null, null, ticket);
    }

    private InviteToken phoneInvite(Instant expiresAt) {
        return InviteToken.forPhone(HASHED_TOKEN, PHONE, salonId, Role.SALON_MASTER, expiresAt, callerId);
    }

    private InviteToken emailInvite() {
        return new InviteToken(HASHED_TOKEN, "legacy@example.com", salonId, Role.SALON_MASTER, NOW.plusSeconds(3600));
    }

    private void stubOwnerOfSalon(String salonName) {
        var salon = activeSalon();
        when(salon.getName()).thenReturn(salonName);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(owner()));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(salon));
    }

    private void stubOwnerOfSalonWithoutName() {
        when(userRepository.findById(callerId)).thenReturn(Optional.of(owner()));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(activeSalon()));
    }

    /** A salon that passes the B520 audit-fix live-salon check of {@code sendInvite}. */
    private static Salon activeSalon() {
        // A default answer, not a when(...): this helper is called inside other thenReturn(...) stubbings.
        return mock(Salon.class, invocation -> "isActive".equals(invocation.getMethod().getName())
                ? Boolean.TRUE : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
    }

    private User owner() {
        var owner = new User("owner@beautica.test", "hash", Role.SALON_OWNER, null, null, null, salonId);
        ReflectionTestUtils.setField(owner, "id", callerId);
        return owner;
    }
}
