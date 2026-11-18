package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.auth.registration.RegistrationTicketService;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.IdentityException;
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
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * B520 — who may invite whom, by phone (salon.md: owner and admin invite admins and masters; an admin only into their own
 * salon; nobody else). The controller role gate is pinned by the ITs; these pin the service-side branches, and that the
 * 409 for a registered phone never overtakes a 403 (AUTH-18). Accept: the token's role decides the account and whether
 * a master row is made.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InviteService — owner / admin invite permissions (B520)")
class InviteServiceAdminTest {

    private static final Instant NOW = Instant.parse("2026-11-18T10:00:00Z");
    private static final String PHONE = "+380501234567";

    @Mock private InviteTokenRepository inviteTokenRepository;
    @Mock private UserRepository userRepository;
    @Mock private SalonRepository salonRepository;
    @Mock private InvitePersistenceService invitePersistenceService;
    @Mock private TokenGenerator tokenGenerator;
    @Mock private MasterService masterService;
    @Mock private AuthResponseBuilder authResponseBuilder;
    @Mock private InAppNotificationService inAppNotificationService;
    @Mock private RegistrationTicketService registrationTicketService;

    private InviteService inviteService;
    private final UUID salonId = UUID.randomUUID();
    private final UUID callerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        inviteService = new InviteService(inviteTokenRepository, userRepository, salonRepository,
                new BCryptPasswordEncoder(4), tokenGenerator, masterService, authResponseBuilder,
                invitePersistenceService, "https://beautica.app", 48L, Clock.fixed(NOW, ZoneOffset.UTC),
                inAppNotificationService, registrationTicketService,
                new InviteConflictProbeBudget(Clock.fixed(NOW, ZoneOffset.UTC)));
    }

    @Test
    @DisplayName("owner invites a SALON_ADMIN into their salon → the delegate gets role SALON_ADMIN")
    void should_sendAdminInvite_when_ownerInvitesAdmin() {
        callerIs(Role.SALON_OWNER, salonId);
        Salon salon = salon();
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(salon));
        when(tokenGenerator.generateToken()).thenReturn("raw");

        inviteService.sendInvite(new InviteRequest(PHONE, salonId, Role.SALON_ADMIN), callerId);

        assertThat(persistedInvite().role()).isEqualTo(Role.SALON_ADMIN);
    }

    @Test
    @DisplayName("admin invites a SALON_MASTER into their own salon → findById, never the owner lookup")
    void should_sendMasterInvite_when_adminInvitesIntoOwnSalon() {
        callerIs(Role.SALON_ADMIN, salonId);
        Salon salon = salon();
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(tokenGenerator.generateToken()).thenReturn("raw");

        inviteService.sendInvite(new InviteRequest(PHONE, salonId, Role.SALON_MASTER), callerId);

        var invite = persistedInvite();
        assertThat(invite.role()).isEqualTo(Role.SALON_MASTER);
        assertThat(invite.inviterId()).isEqualTo(callerId);
        verify(salonRepository, never()).findByIdAndOwnerId(any(), any());
    }

    @Test
    @DisplayName("admin invites a co-admin into their own salon (phase 21.1) → allowed")
    void should_sendAdminInvite_when_adminInvitesCoAdminIntoOwnSalon() {
        callerIs(Role.SALON_ADMIN, salonId);
        Salon salon = salon();
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salon));
        when(tokenGenerator.generateToken()).thenReturn("raw");

        inviteService.sendInvite(new InviteRequest(PHONE, salonId, Role.SALON_ADMIN), callerId);

        assertThat(persistedInvite().role()).isEqualTo(Role.SALON_ADMIN);
    }

    @Test
    @DisplayName("admin invites into ANOTHER salon → 403, even when the phone is registered (no 409 leak)")
    void should_throwForbidden_when_adminInvitesIntoOtherSalon() {
        callerIs(Role.SALON_ADMIN, UUID.randomUUID());
        when(userRepository.existsByVerifiedPhone(PHONE)).thenReturn(true);

        assertThatThrownBy(() -> inviteService.sendInvite(new InviteRequest(PHONE, salonId, null), callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("SALON_ADMIN may only invite to their own assigned salon");
        verifyNoInteractions(invitePersistenceService);
    }

    @Test
    @DisplayName("owner of ANOTHER salon → 403 'do not own'")
    void should_throwForbidden_when_ownerDoesNotOwnSalon() {
        callerIs(Role.SALON_OWNER, UUID.randomUUID());
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inviteService.sendInvite(new InviteRequest(PHONE, salonId, null), callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("do not own");
        verifyNoInteractions(invitePersistenceService);
    }

    @Test
    @DisplayName("a SALON_MASTER caller may not invite at all (master invite → 403)")
    void should_throwForbidden_when_salonMasterInvitesMaster() {
        callerIs(Role.SALON_MASTER, salonId);

        assertThatThrownBy(() -> inviteService.sendInvite(new InviteRequest(PHONE, salonId, null), callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("cannot send invites");
        verifyNoInteractions(invitePersistenceService);
    }

    @Test
    @DisplayName("a SALON_MASTER caller may not invite an admin (403 before any salon lookup)")
    void should_throwForbidden_when_salonMasterInvitesAdmin() {
        callerIs(Role.SALON_MASTER, salonId);

        assertThatThrownBy(() -> inviteService.sendInvite(
                new InviteRequest(PHONE, salonId, Role.SALON_ADMIN), callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Only SALON_OWNER or SALON_ADMIN may invite a SALON_ADMIN");
        verifyNoInteractions(salonRepository, invitePersistenceService);
    }

    @Test
    @DisplayName("roles other than SALON_ADMIN / SALON_MASTER cannot be invited (CLIENT → 403)")
    void should_throwForbidden_when_roleNotInvitable() {
        callerIs(Role.SALON_OWNER, salonId);

        assertThatThrownBy(() -> inviteService.sendInvite(new InviteRequest(PHONE, salonId, Role.CLIENT), callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("cannot be assigned via invite");
        verifyNoInteractions(invitePersistenceService);
    }

    @Test
    @DisplayName("admin caller + registered phone → the same 409 PHONE_ALREADY_REGISTERED an owner gets")
    void should_throwPhoneAlreadyRegistered_when_adminCallerAndPhoneRegistered() {
        callerIs(Role.SALON_ADMIN, salonId);
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(activeSalon()));
        when(userRepository.existsByVerifiedPhone(PHONE)).thenReturn(true);

        var thrown = catchThrowableOfType(
                () -> inviteService.sendInvite(new InviteRequest(PHONE, salonId, null), callerId),
                IdentityException.class);

        assertThat(thrown.getCode()).isEqualTo(IdentityException.Code.PHONE_ALREADY_REGISTERED);
        verifyNoInteractions(invitePersistenceService);
    }

    @Test
    @DisplayName("accept of a SALON_ADMIN invite → account role SALON_ADMIN, no master row")
    void should_createAdminWithoutMasterRow_when_acceptingAdminInvite() {
        UUID userId = stubAccept(Role.SALON_ADMIN);

        inviteService.acceptInvite(acceptRequest());

        var captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(Role.SALON_ADMIN);
        verify(masterService, never()).createMasterFromInvite(any(), any());
        verify(inAppNotificationService).notifyInviteAccepted(salonId, userId);
    }

    @Test
    @DisplayName("accept of a SALON_MASTER invite → exactly one master row for the new user")
    void should_createMasterRow_when_acceptingMasterInvite() {
        UUID userId = stubAccept(Role.SALON_MASTER);

        inviteService.acceptInvite(acceptRequest());

        verify(masterService).createMasterFromInvite(userId, salonId);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private InvitePersistenceService.PhoneInvite persistedInvite() {
        var captor = ArgumentCaptor.forClass(InvitePersistenceService.PhoneInvite.class);
        verify(invitePersistenceService).persistInviteAndEnqueue(captor.capture());
        return captor.getValue();
    }

    private UUID stubAccept(Role role) {
        UUID userId = UUID.randomUUID();
        var invite = InviteToken.forPhone("hashed", PHONE, salonId, role, NOW.plusSeconds(3600), callerId);
        var activeSalon = mock(Salon.class);
        when(activeSalon.isActive()).thenReturn(true);
        when(tokenGenerator.hash("raw")).thenReturn("hashed");
        when(inviteTokenRepository.findByTokenForUpdate("hashed")).thenReturn(Optional.of(invite));
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(activeSalon));
        when(registrationTicketService.consume("ticket")).thenReturn(PHONE);
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", userId);
            return u;
        });
        when(authResponseBuilder.buildAuthResponse(any(User.class)))
                .thenReturn(AuthResponse.of("a", "r", userId, null, role, salonId));
        return userId;
    }

    private static InviteAcceptRequest acceptRequest() {
        return new InviteAcceptRequest("raw", "Str0ngP@ss1!", "Ірина", "Мельник", null, null, null, null, "ticket");
    }

    private Salon salon() {
        var salon = activeSalon();
        when(salon.getName()).thenReturn("Salon A");
        return salon;
    }

    /** A salon that passes the B520 audit-fix live-salon check of {@code sendInvite}. */
    private static Salon activeSalon() {
        // A default answer, not a when(...): this helper is called inside other thenReturn(...) stubbings.
        return mock(Salon.class, invocation -> "isActive".equals(invocation.getMethod().getName())
                ? Boolean.TRUE : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation));
    }

    private void callerIs(Role role, UUID callerSalonId) {
        var caller = new User("caller@beautica.test", "hash", role, null, null, null, callerSalonId);
        ReflectionTestUtils.setField(caller, "id", callerId);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
    }
}
