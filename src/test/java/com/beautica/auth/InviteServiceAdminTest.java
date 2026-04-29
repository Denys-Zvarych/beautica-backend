package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ConflictException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.master.service.MasterService;
import com.beautica.notification.EmailService;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the SALON_ADMIN invite flow in InviteService (Phase 2.8 / Phase 2.9).
 *
 * Covers:
 * - sending admin invites (role guard, conflict guard)
 * - accepting admin invites (role assignment, no Master record created)
 * - accepting master invites (Master record created)
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("InviteService — SALON_ADMIN invite flow")
class InviteServiceAdminTest {

    @Mock
    private InviteTokenRepository inviteTokenRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private SalonRepository salonRepository;

    @Mock
    private EmailService emailService;

    @Mock
    private TokenGenerator tokenGenerator;

    @Mock
    private MasterService masterService;

    @Mock
    private AuthResponseBuilder authResponseBuilder;

    private InviteService inviteService;

    @BeforeEach
    void setUp() {
        PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
        inviteService = new InviteService(
                inviteTokenRepository,
                userRepository,
                salonRepository,
                passwordEncoder,
                emailService,
                tokenGenerator,
                masterService,
                authResponseBuilder,
                "http://localhost:3000",
                72L
        );
    }

    // ── sendInvite — admin role guard ─────────────────────────────────────────

    @Test
    @DisplayName("sendInvite stores token with SALON_ADMIN role when SALON_OWNER invites admin")
    void should_sendAdminInvite_when_ownerInvitesAdmin() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var owner = buildOwner(callerId, salonId);
        var request = new InviteRequest("admin@example.com", salonId, Role.SALON_ADMIN);

        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(owner));
        when(userRepository.existsBySalonIdAndRole(salonId, Role.SALON_ADMIN)).thenReturn(false);
        when(inviteTokenRepository.findByEmailAndIsUsedFalse("admin@example.com"))
                .thenReturn(Optional.empty());
        when(tokenGenerator.generateToken()).thenReturn("raw-admin-tok");
        when(salonRepository.findById(salonId)).thenReturn(Optional.empty());
        ArgumentCaptor<InviteToken> tokenCaptor = ArgumentCaptor.forClass(InviteToken.class);
        when(inviteTokenRepository.save(tokenCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        inviteService.sendInvite(request, callerId);

        assertThat(tokenCaptor.getValue().getRole()).isEqualTo(Role.SALON_ADMIN);
        verify(userRepository).existsBySalonIdAndRole(salonId, Role.SALON_ADMIN);
    }

    @Test
    @DisplayName("sendInvite throws ForbiddenException when SALON_ADMIN caller tries to invite another admin")
    void should_throwForbidden_when_adminTriesToInviteAnotherAdmin() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var adminCaller = buildSalonAdmin(callerId, salonId);
        var request = new InviteRequest("second-admin@example.com", salonId, Role.SALON_ADMIN);

        when(userRepository.existsByEmail("second-admin@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(adminCaller));

        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("SALON_OWNER");

        verify(inviteTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("sendInvite throws ConflictException when salon already has a SALON_ADMIN")
    void should_throwConflict_when_salonAlreadyHasAdmin() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var owner = buildOwner(callerId, salonId);
        var request = new InviteRequest("another-admin@example.com", salonId, Role.SALON_ADMIN);

        when(userRepository.existsByEmail("another-admin@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(owner));
        when(userRepository.existsBySalonIdAndRole(salonId, Role.SALON_ADMIN)).thenReturn(true);

        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already has a SALON_ADMIN");

        verify(inviteTokenRepository, never()).save(any());
    }

    // ── sendInvite — email conflict guard ─────────────────────────────────────

    @Test
    @DisplayName("sendInvite throws BusinessException when email is already registered")
    void should_throwConflict_when_emailAlreadyRegistered() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("existing@example.com", salonId, Role.SALON_MASTER);

        when(userRepository.existsByEmail("existing@example.com")).thenReturn(true);

        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already registered");

        verify(inviteTokenRepository, never()).save(any());
    }

    // ── sendInvite — caller-ownership guard ───────────────────────────────────

    @Test
    @DisplayName("sendInvite throws ForbiddenException when caller's salonId does not match request salonId")
    void should_throwForbidden_when_callerDoesNotBelongToSalon() {
        var callerId = UUID.randomUUID();
        var callerSalonId = UUID.randomUUID();
        var requestSalonId = UUID.randomUUID(); // different salon
        var caller = buildOwner(callerId, callerSalonId);
        var request = new InviteRequest("master@example.com", requestSalonId, Role.SALON_MASTER);

        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));

        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("own the specified salon");

        verify(inviteTokenRepository, never()).save(any());
    }

    // ── sendInvite — invalid role guard ───────────────────────────────────────

    @Test
    @DisplayName("sendInvite throws ForbiddenException when requested role cannot be assigned via invite")
    void should_throwForbidden_when_invalidRoleRequested() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var caller = buildOwner(callerId, salonId);
        var request = new InviteRequest("client@example.com", salonId, Role.CLIENT);

        when(userRepository.existsByEmail("client@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));

        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("cannot be assigned via invite");

        verify(inviteTokenRepository, never()).save(any());
    }

    // ── sendInvite — IDOR / cross-salon guard ─────────────────────────────────

    @Test
    @DisplayName("sendInvite throws ForbiddenException when SALON_ADMIN invites into a different salon")
    void should_throwForbidden_when_adminInvitesToDifferentSalon() {
        var callerId = UUID.randomUUID();
        var callerSalonId = UUID.randomUUID();
        var targetSalonId = UUID.randomUUID(); // salon B — admin does not belong here
        var adminCaller = buildSalonAdmin(callerId, callerSalonId);
        var request = new InviteRequest("master@example.com", targetSalonId, Role.SALON_MASTER);

        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(adminCaller));

        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("own the specified salon");

        verify(inviteTokenRepository, never()).save(any());
    }

    // ── acceptInvite — role assignment ────────────────────────────────────────

    @Test
    @DisplayName("acceptInvite assigns SALON_ADMIN role to the new user when token carries that role")
    void should_assignRoleFromToken_when_acceptingAdminInvite() {
        var rawToken = "raw-admin-accept";
        var hashedToken = "hashed-admin-accept";
        var salonId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var invite = new InviteToken(hashedToken, "admin@example.com", salonId,
                Role.SALON_ADMIN, Instant.now().plusSeconds(3600));
        var request = new InviteAcceptRequest(rawToken, "Password1!", "Admin", "User", null);

        stubAcceptInviteHappyPath(rawToken, hashedToken, userId, salonId, Role.SALON_ADMIN, invite);

        inviteService.acceptInvite(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole()).isEqualTo(Role.SALON_ADMIN);
    }

    @Test
    @DisplayName("acceptInvite never calls masterService when token role is SALON_ADMIN")
    void should_notCreateMasterRecord_when_acceptingAdminInvite() {
        var rawToken = "raw-admin-accept-no-master";
        var hashedToken = "hashed-admin-accept-no-master";
        var salonId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var invite = new InviteToken(hashedToken, "admin2@example.com", salonId,
                Role.SALON_ADMIN, Instant.now().plusSeconds(3600));
        var request = new InviteAcceptRequest(rawToken, "Password1!", "Admin", "Two", null);

        stubAcceptInviteHappyPath(rawToken, hashedToken, userId, salonId, Role.SALON_ADMIN, invite);

        inviteService.acceptInvite(request);

        verify(masterService, never()).createMasterFromInvite(any(), any());
    }

    @Test
    @DisplayName("acceptInvite calls masterService exactly once when token role is SALON_MASTER")
    void should_createMasterRecord_when_acceptingMasterInvite() {
        var rawToken = "raw-master-accept";
        var hashedToken = "hashed-master-accept";
        var salonId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var invite = new InviteToken(hashedToken, "master@example.com", salonId,
                Role.SALON_MASTER, Instant.now().plusSeconds(3600));
        var request = new InviteAcceptRequest(rawToken, "Password1!", "Master", "One", null);

        stubAcceptInviteHappyPath(rawToken, hashedToken, userId, salonId, Role.SALON_MASTER, invite);

        inviteService.acceptInvite(request);

        verify(masterService).createMasterFromInvite(eq(userId), eq(salonId));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private void stubAcceptInviteHappyPath(
            String rawToken, String hashedToken,
            UUID userId, UUID salonId, Role role, InviteToken invite) {

        var stubResponse = AuthResponse.of(
                "access-tok", "refresh-tok", userId,
                invite.getEmail(), role, salonId);

        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(inviteTokenRepository.findByTokenForUpdate(hashedToken)).thenReturn(Optional.of(invite));
        when(userRepository.existsByEmail(invite.getEmail())).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", userId);
            return u;
        });
        when(inviteTokenRepository.save(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(authResponseBuilder.buildAuthResponse(any(User.class))).thenReturn(stubResponse);
    }

    private User buildOwner(UUID id, UUID salonId) {
        var user = new User("owner@beautica.test", "hash", Role.SALON_OWNER, null, null, null, salonId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private User buildSalonAdmin(UUID id, UUID salonId) {
        var user = new User("admin@beautica.test", "hash", Role.SALON_ADMIN, null, null, null, salonId);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }
}
