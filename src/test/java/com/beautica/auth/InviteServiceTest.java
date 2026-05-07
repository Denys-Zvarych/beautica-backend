package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InvitePreviewResponse;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ConflictException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.JwtConfig;
import com.beautica.master.service.MasterService;
import com.beautica.notification.EmailService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("InviteService — unit")
class InviteServiceTest {

    private static final Logger log = LoggerFactory.getLogger(InviteServiceTest.class);

    private static final String SECRET =
            "test-secret-that-is-long-enough-for-hs256-ok-padding-here";
    private static final long ACCESS_MS = 900_000L;
    private static final long REFRESH_MS = 2_592_000_000L;

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

    private PasswordEncoder passwordEncoder;
    private InviteService inviteService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
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
                48L
        );
    }

    @Test
    @DisplayName("sendInvite stores hashed token, not the raw token")
    void should_storeHashedToken_when_sendInviteSucceeds() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("master@example.com", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);
        var rawToken = "raw-invite-token";
        var hashedToken = "hashed-invite-token";
        log.debug("Arrange: caller owns salon salonId={}", salonId);

        var salonStub = mock(Salon.class);
        when(salonStub.getName()).thenReturn("Test Salon");

        when(tokenGenerator.generateToken()).thenReturn(rawToken);
        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(salonStub));
        when(inviteTokenRepository.findByEmailAndIsUsedFalse("master@example.com"))
                .thenReturn(Optional.empty());

        ArgumentCaptor<InviteToken> captor = ArgumentCaptor.forClass(InviteToken.class);
        when(inviteTokenRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        log.debug("Act: sendInvite with tokenGenerator returning raw='{}' hashed='{}'", rawToken, hashedToken);
        inviteService.sendInvite(request, callerId);

        InviteToken saved = captor.getValue();
        assertThat(saved.getToken())
                .as("persisted token must equal the hashed value, not raw='%s'", rawToken)
                .isEqualTo(hashedToken);
        assertThat(saved.getToken()).isNotEqualTo(rawToken);

        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendInviteEmail(anyString(), linkCaptor.capture(), anyString());
        assertThat(linkCaptor.getValue()).endsWith(rawToken);
    }

    @Test
    @DisplayName("sendInvite saves token and sends email on happy path")
    void should_saveTokenAndSendEmail_when_sendInviteSucceeds() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("master@example.com", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);
        log.debug("Arrange: email={} salonId={}", request.email(), salonId);

        var salonStub = mock(Salon.class);
        when(salonStub.getName()).thenReturn("Test Salon");

        when(tokenGenerator.generateToken()).thenReturn("raw-token");
        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(salonStub));
        when(inviteTokenRepository.findByEmailAndIsUsedFalse("master@example.com"))
                .thenReturn(Optional.empty());
        when(inviteTokenRepository.save(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));

        log.debug("Act: sendInvite for email={} salonId={} on happy path", request.email(), salonId);
        var response = inviteService.sendInvite(request, callerId);

        assertThat(response.invitedEmail())
                .as("response must carry the invited email address")
                .isEqualTo("master@example.com");
        assertThat(response.expiresAt()).isAfter(Instant.now());

        verify(inviteTokenRepository).save(any(InviteToken.class));
        verify(emailService).sendInviteEmail(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("sendInvite throws BusinessException when email already registered")
    void should_throwBusinessException_when_emailAlreadyRegistered() {
        var request = new InviteRequest("taken@example.com", UUID.randomUUID(), null);
        log.debug("Arrange: email={} already registered", request.email());

        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        log.debug("Act: sendInvite for already-registered email={} — must throw BusinessException", request.email());
        assertThatThrownBy(() -> inviteService.sendInvite(request, UUID.randomUUID()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already registered");

        verify(inviteTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("sendInvite throws ForbiddenException when caller does not own the salon")
    void should_throwForbiddenException_when_callerDoesNotOwnSalon() {
        var callerId = UUID.randomUUID();
        var requestedSalonId = UUID.randomUUID();
        var callerOwnedSalonId = UUID.randomUUID();
        var request = new InviteRequest("master@example.com", requestedSalonId, null);
        var caller = buildCallerWithSalon(callerId, callerOwnedSalonId);
        log.debug("Arrange: caller salonId={} != requested salonId={}", callerOwnedSalonId, requestedSalonId);

        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(requestedSalonId, callerId)).thenReturn(Optional.empty());

        log.debug("Act: sendInvite where caller salonId={} differs from requested salonId={} — must throw ForbiddenException", callerOwnedSalonId, requestedSalonId);
        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("do not own");

        verify(salonRepository).findByIdAndOwnerId(requestedSalonId, callerId);
        verify(inviteTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("sendInvite throws ForbiddenException when salon does not exist")
    void should_throwForbiddenException_when_salonNotFound() {
        var callerId = UUID.randomUUID();
        var nonExistentSalonId = UUID.randomUUID();
        var request = new InviteRequest("master@example.com", nonExistentSalonId, null);
        var caller = buildCallerWithSalon(callerId, UUID.randomUUID());
        log.debug("Arrange: salon salonId={} does not exist in DB", nonExistentSalonId);

        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(nonExistentSalonId, callerId)).thenReturn(Optional.empty());

        log.debug("Act: sendInvite for salonId={} that does not exist — must throw ForbiddenException", nonExistentSalonId);
        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("You do not own the specified salon");

        verify(salonRepository).findByIdAndOwnerId(nonExistentSalonId, callerId);
        verify(inviteTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("sendInvite throws ForbiddenException when salon exists but is owned by another user")
    void should_throwForbiddenException_when_salonExistsButOwnedByOther() {
        var callerId = UUID.randomUUID();
        var salonOwnedByOther = UUID.randomUUID();
        var request = new InviteRequest("master@example.com", salonOwnedByOther, null);
        var caller = buildCallerWithSalon(callerId, UUID.randomUUID());
        log.debug("Arrange: salon salonId={} exists but belongs to a different owner", salonOwnedByOther);

        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        // Salon exists but the combined id+ownerId query returns empty — same result as non-existent.
        when(salonRepository.findByIdAndOwnerId(salonOwnedByOther, callerId)).thenReturn(Optional.empty());

        log.debug("Act: sendInvite for salonId={} owned by another user — must throw ForbiddenException", salonOwnedByOther);
        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("You do not own the specified salon");

        verify(salonRepository).findByIdAndOwnerId(salonOwnedByOther, callerId);
        verify(inviteTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("sendInvite throws BusinessException when active invite already exists")
    void should_throwBusinessException_when_activeInviteExists() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("pending@example.com", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);
        var existing = buildInviteToken("pending@example.com", Instant.now().plusSeconds(3600));
        log.debug("Arrange: active invite exists for email={}", request.email());

        when(userRepository.existsByEmail("pending@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(mock(Salon.class)));
        when(inviteTokenRepository.findByEmailAndIsUsedFalse("pending@example.com"))
                .thenReturn(Optional.of(existing));

        log.debug("Act: sendInvite for email={} that already has an active invite — must throw BusinessException", request.email());
        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("active invite");

        verify(inviteTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("sendInvite deletes expired invite and creates a new one")
    void should_deleteExpiredAndCreateNew_when_expiredInviteExists() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("expired@example.com", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);
        var expired = buildInviteToken("expired@example.com", Instant.now().minusSeconds(1));
        log.debug("Arrange: expired invite exists for email={}", request.email());

        var salonStub = mock(Salon.class);
        when(salonStub.getName()).thenReturn("Test Salon");

        when(tokenGenerator.generateToken()).thenReturn("new-raw-token");
        when(userRepository.existsByEmail("expired@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(salonStub));
        when(inviteTokenRepository.findByEmailAndIsUsedFalse("expired@example.com"))
                .thenReturn(Optional.of(expired));
        when(inviteTokenRepository.save(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));

        log.debug("Act: sendInvite for email={} — expired invite exists and must be replaced", request.email());
        var response = inviteService.sendInvite(request, callerId);

        verify(inviteTokenRepository).delete(expired);
        verify(inviteTokenRepository).save(any(InviteToken.class));
        verify(emailService).sendInviteEmail(anyString(), anyString(), anyString());
        assertThat(response.invitedEmail()).isEqualTo("expired@example.com");
    }

    @Test
    @DisplayName("acceptInvite creates SALON_MASTER user and returns auth response")
    void should_createSalonMasterAndReturnAuthResponse_when_acceptInviteSucceeds() {
        var rawToken = "raw-accept-token";
        var hashedToken = "hashed-accept-token";
        var salonId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var invite = buildInviteToken("new@example.com", Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(invite, "salonId", salonId);
        var request = new InviteAcceptRequest(rawToken, "password123", "Jane", "Doe", null);
        log.debug("Arrange: valid unused token for email=new@example.com salonId={}", salonId);

        var stubResponse = AuthResponse.of("access-tok", "refresh-tok",
                userId, "new@example.com", Role.SALON_MASTER, salonId);
        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(inviteTokenRepository.findByTokenForUpdate(hashedToken)).thenReturn(Optional.of(invite));
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", userId);
            return u;
        });
        when(inviteTokenRepository.save(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(authResponseBuilder.buildAuthResponse(any(User.class))).thenReturn(stubResponse);

        log.debug("Act: acceptInvite with valid SALON_MASTER token for email=new@example.com salonId={}", salonId);
        var response = inviteService.acceptInvite(request);

        assertThat(response.role())
                .as("response role must be SALON_MASTER for a master invite token")
                .isEqualTo(Role.SALON_MASTER);
        assertThat(response.email()).isEqualTo("new@example.com");
        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.salonId()).isEqualTo(salonId);
        assertThat(invite.isUsed()).isTrue();

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole()).isEqualTo(Role.SALON_MASTER);
        assertThat(userCaptor.getValue().getSalonId()).isEqualTo(salonId);
    }

    @Test
    @DisplayName("acceptInvite throws NotFoundException when token does not exist")
    void should_throwNotFoundException_when_tokenNotFound() {
        var rawToken = "nonexistent";
        var hashedToken = "hashed-nonexistent";
        var request = new InviteAcceptRequest(rawToken, "password123", null, null, null);
        log.debug("Arrange: no invite token found");

        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(inviteTokenRepository.findByTokenForUpdate(hashedToken)).thenReturn(Optional.empty());

        log.debug("Act: acceptInvite with non-existent token='{}' — must throw NotFoundException", rawToken);
        assertThatThrownBy(() -> inviteService.acceptInvite(request))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("acceptInvite throws BusinessException when token is already used")
    void should_throwBusinessException_when_tokenAlreadyUsed() {
        var rawToken = "raw-used-token";
        var hashedToken = "hashed-used-token";
        var invite = buildInviteToken("used@example.com", Instant.now().plusSeconds(3600));
        invite.markUsed();
        var request = new InviteAcceptRequest(rawToken, "password123", null, null, null);
        log.debug("Arrange: invite token already marked used");

        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(inviteTokenRepository.findByTokenForUpdate(hashedToken)).thenReturn(Optional.of(invite));

        log.debug("Act: acceptInvite with a token already marked used — must throw BusinessException");
        assertThatThrownBy(() -> inviteService.acceptInvite(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    @DisplayName("acceptInvite throws BusinessException when token is expired")
    void should_throwBusinessException_when_tokenExpired() {
        var rawToken = "raw-expired-token";
        var hashedToken = "hashed-expired-token";
        var invite = buildInviteToken("expired2@example.com", Instant.now().minusSeconds(1));
        var request = new InviteAcceptRequest(rawToken, "password123", null, null, null);
        log.debug("Arrange: invite token is expired");

        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(inviteTokenRepository.findByTokenForUpdate(hashedToken)).thenReturn(Optional.of(invite));

        log.debug("Act: acceptInvite with an expired token — must throw BusinessException");
        assertThatThrownBy(() -> inviteService.acceptInvite(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("acceptInvite throws BusinessException when email already registered")
    void should_throwBusinessException_when_emailAlreadyRegisteredOnAccept() {
        var rawToken = "raw-collision-token";
        var hashedToken = "hashed-collision-token";
        var invite = buildInviteToken("collision@example.com", Instant.now().plusSeconds(3600));
        var request = new InviteAcceptRequest(rawToken, "password123", null, null, null);
        log.debug("Arrange: invite token valid but email collision detected");

        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(inviteTokenRepository.findByTokenForUpdate(hashedToken)).thenReturn(Optional.of(invite));
        when(userRepository.existsByEmail("collision@example.com")).thenReturn(true);

        log.debug("Act: acceptInvite where email=collision@example.com is already registered — must throw BusinessException");
        assertThatThrownBy(() -> inviteService.acceptInvite(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    @DisplayName("previewInvite returns InvitePreviewResponse for a valid token")
    void should_return_previewResponse_when_validToken() {
        var rawToken = "raw-preview-token";
        var hashedToken = "hashed-preview-token";
        var expiresAt = Instant.now().plusSeconds(3600);
        var invite = buildInviteToken("preview@example.com", expiresAt);
        log.debug("Arrange: valid unused token for email=preview@example.com");

        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(inviteTokenRepository.findByToken(hashedToken)).thenReturn(Optional.of(invite));

        log.debug("Act: previewInvite with valid token for email=preview@example.com");
        InvitePreviewResponse response = inviteService.previewInvite(rawToken);

        assertThat(response.invitedEmail())
                .as("preview response must return the email stored in the invite token")
                .isEqualTo("preview@example.com");
        assertThat(response.role()).isEqualTo(Role.SALON_MASTER);
        assertThat(response.expiresAt()).isEqualTo(invite.getExpiresAt());
    }

    @Test
    @DisplayName("previewInvite throws BusinessException when token does not exist")
    void should_throwNotFound_when_previewTokenNotFound() {
        var rawToken = "unknown-raw-token";
        var hashedToken = "hashed-unknown-token";
        log.debug("Arrange: no matching token in repository");

        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(inviteTokenRepository.findByToken(hashedToken)).thenReturn(Optional.empty());

        log.debug("Act: previewInvite with unknown token='{}' — must throw BusinessException", rawToken);
        assertThatThrownBy(() -> inviteService.previewInvite(rawToken))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired invite token");
    }

    @Test
    @DisplayName("previewInvite throws BusinessException when token is already used")
    void should_throw400_when_previewTokenAlreadyUsed() {
        var rawToken = "raw-used-preview-token";
        var hashedToken = "hashed-used-preview-token";
        var invite = buildInviteToken("used@example.com", Instant.now().plusSeconds(3600));
        invite.markUsed();
        log.debug("Arrange: invite token already marked used");

        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(inviteTokenRepository.findByToken(hashedToken)).thenReturn(Optional.of(invite));

        log.debug("Act: previewInvite with an already-used token for email=used@example.com — must throw BusinessException");
        assertThatThrownBy(() -> inviteService.previewInvite(rawToken))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired invite token");
    }

    @Test
    @DisplayName("previewInvite throws BusinessException when token is expired")
    void should_throw400_when_previewTokenExpired() {
        var rawToken = "raw-expired-preview-token";
        var hashedToken = "hashed-expired-preview-token";
        var invite = buildInviteToken("expired@example.com", Instant.now().minusSeconds(1));
        log.debug("Arrange: invite token is expired");

        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(inviteTokenRepository.findByToken(hashedToken)).thenReturn(Optional.of(invite));

        log.debug("Act: previewInvite with an expired token for email=expired@example.com — must throw BusinessException");
        assertThatThrownBy(() -> inviteService.previewInvite(rawToken))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired invite token");
    }

    // ── Phase 2.8 — SALON_ADMIN invite flow ──────────────────────────────────

    @Test
    @DisplayName("sendInvite stores token with SALON_ADMIN role when SALON_OWNER requests admin role")
    void should_inviteSalonAdmin_when_salonOwnerRequestsAdminRole() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("admin@example.com", salonId, Role.SALON_ADMIN);
        var caller = buildCallerWithSalon(callerId, salonId);
        log.debug("Arrange: SALON_OWNER invites SALON_ADMIN for salonId={}", salonId);

        var salonStub = mock(Salon.class);
        when(salonStub.getName()).thenReturn("Test Salon");

        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(salonStub));
        when(userRepository.existsBySalonIdAndRole(salonId, Role.SALON_ADMIN)).thenReturn(false);
        when(inviteTokenRepository.findByEmailAndIsUsedFalse("admin@example.com"))
                .thenReturn(Optional.empty());
        when(tokenGenerator.generateToken()).thenReturn("raw-admin-token");
        ArgumentCaptor<InviteToken> tokenCaptor = ArgumentCaptor.forClass(InviteToken.class);
        when(inviteTokenRepository.save(tokenCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        log.debug("Act: sendInvite with SALON_ADMIN role for email={} salonId={}", request.email(), salonId);
        var response = inviteService.sendInvite(request, callerId);

        assertThat(response.invitedEmail()).isEqualTo("admin@example.com");
        assertThat(tokenCaptor.getValue().getRole()).isEqualTo(Role.SALON_ADMIN);
        verify(userRepository).existsBySalonIdAndRole(salonId, Role.SALON_ADMIN);
    }

    @Test
    @DisplayName("sendInvite throws ForbiddenException when non-owner tries to invite SALON_ADMIN")
    void should_throwForbiddenException_when_nonOwnerInvitesSalonAdmin() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("admin@example.com", salonId, Role.SALON_ADMIN);
        var masterCaller = new User("master@beautica.test", "hash", Role.SALON_MASTER, null, null, null, salonId);
        ReflectionTestUtils.setField(masterCaller, "id", callerId);
        log.debug("Arrange: SALON_MASTER caller tries to invite SALON_ADMIN for salonId={}", salonId);

        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(masterCaller));

        log.debug("Act: sendInvite with SALON_ADMIN role by non-owner callerId={} — must throw ForbiddenException", callerId);
        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("SALON_OWNER");

        verify(inviteTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("sendInvite throws ConflictException when salon already has a SALON_ADMIN")
    void should_throwConflictException_when_salonAlreadyHasAdmin() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("admin2@example.com", salonId, Role.SALON_ADMIN);
        var caller = buildCallerWithSalon(callerId, salonId);
        log.debug("Arrange: salon already has a SALON_ADMIN, salonId={}", salonId);

        when(userRepository.existsByEmail("admin2@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(mock(Salon.class)));
        when(userRepository.existsBySalonIdAndRole(salonId, Role.SALON_ADMIN)).thenReturn(true);

        log.debug("Act: sendInvite with SALON_ADMIN role for salonId={} that already has an admin — must throw ConflictException", salonId);
        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already has a SALON_ADMIN");

        verify(inviteTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("acceptInvite creates user with SALON_ADMIN role and does not create a Master record")
    void should_createUserWithTokenRole_when_acceptInviteWithSalonAdminToken() {
        var rawToken = "raw-admin-accept-token";
        var hashedToken = "hashed-admin-accept-token";
        var salonId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var invite = new InviteToken(hashedToken, "admin@example.com", salonId, Role.SALON_ADMIN,
                Instant.now().plusSeconds(3600));
        var request = new InviteAcceptRequest(rawToken, "Password1!", "Admin", "User", null);
        log.debug("Arrange: SALON_ADMIN invite token for email=admin@example.com salonId={}", salonId);

        var stubResponse = AuthResponse.of("access-tok", "refresh-tok",
                userId, "admin@example.com", Role.SALON_ADMIN, salonId);
        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(inviteTokenRepository.findByTokenForUpdate(hashedToken)).thenReturn(Optional.of(invite));
        when(userRepository.existsByEmail("admin@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", userId);
            return u;
        });
        when(inviteTokenRepository.save(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(authResponseBuilder.buildAuthResponse(any(User.class))).thenReturn(stubResponse);

        log.debug("Act: acceptInvite with SALON_ADMIN token for email=admin@example.com salonId={}", salonId);
        inviteService.acceptInvite(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole()).isEqualTo(Role.SALON_ADMIN);
        verify(masterService, never()).createMasterFromInvite(any(), any());
    }

    @Test
    @DisplayName("acceptInvite creates Master record when token role is SALON_MASTER")
    void should_createMasterRecord_when_acceptInviteWithSalonMasterToken() {
        var rawToken = "raw-master-accept-token";
        var hashedToken = "hashed-master-accept-token";
        var salonId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var invite = buildInviteToken("newmaster@example.com", Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(invite, "salonId", salonId);
        var request = new InviteAcceptRequest(rawToken, "Password1!", "New", "Master", null);
        log.debug("Arrange: SALON_MASTER invite token for email=newmaster@example.com salonId={}", salonId);

        var stubResponse = AuthResponse.of("access-tok", "refresh-tok",
                userId, "newmaster@example.com", Role.SALON_MASTER, salonId);
        when(tokenGenerator.hash(rawToken)).thenReturn(hashedToken);
        when(inviteTokenRepository.findByTokenForUpdate(hashedToken)).thenReturn(Optional.of(invite));
        when(userRepository.existsByEmail("newmaster@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", userId);
            return u;
        });
        when(inviteTokenRepository.save(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(authResponseBuilder.buildAuthResponse(any(User.class))).thenReturn(stubResponse);

        log.debug("Act: acceptInvite with SALON_MASTER token for email=newmaster@example.com salonId={}", salonId);
        inviteService.acceptInvite(request);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole()).isEqualTo(Role.SALON_MASTER);
        verify(masterService).createMasterFromInvite(eq(userId), eq(salonId));
    }

    @Test
    @DisplayName("sendInvite throws ForbiddenException when owner tries to invite CLIENT role")
    void should_throwForbiddenException_when_ownerInvitesClientRole() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("client@example.com", salonId, Role.CLIENT);
        var caller = buildCallerWithSalon(callerId, salonId);
        log.debug("Arrange: SALON_OWNER tries to invite CLIENT role for salonId={}", salonId);

        when(userRepository.existsByEmail("client@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));

        log.debug("Act: sendInvite with CLIENT role for email={} — must throw ForbiddenException", request.email());
        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("cannot be assigned via invite");

        verify(inviteTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("sendInvite throws ForbiddenException when owner tries to invite INDEPENDENT_MASTER role")
    void should_throwForbiddenException_when_ownerInvitesIndependentMasterRole() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("indie@example.com", salonId, Role.INDEPENDENT_MASTER);
        var caller = buildCallerWithSalon(callerId, salonId);
        log.debug("Arrange: SALON_OWNER tries to invite INDEPENDENT_MASTER role for salonId={}", salonId);

        when(userRepository.existsByEmail("indie@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));

        log.debug("Act: sendInvite with INDEPENDENT_MASTER role for email={} — must throw ForbiddenException", request.email());
        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("cannot be assigned via invite");

        verify(inviteTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("sendInvite passes salon name to emailService when salon is found")
    void should_passSalonNameToEmailService_when_salonFoundDuringSendInvite() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("master@example.com", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);
        log.debug("Arrange: salon 'Glamour Studio' found for salonId={}", salonId);

        var salon = mock(Salon.class);
        when(salon.getName()).thenReturn("Glamour Studio");

        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(salon));
        when(inviteTokenRepository.findByEmailAndIsUsedFalse("master@example.com"))
                .thenReturn(Optional.empty());
        when(tokenGenerator.generateToken()).thenReturn("raw-tok");
        when(inviteTokenRepository.save(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));

        log.debug("Act: sendInvite for salon 'Glamour Studio' salonId={} — salon name must be forwarded to emailService", salonId);
        inviteService.sendInvite(request, callerId);

        ArgumentCaptor<String> salonNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendInviteEmail(anyString(), anyString(), salonNameCaptor.capture());
        assertThat(salonNameCaptor.getValue()).isEqualTo("Glamour Studio");
    }

    @Test
    @DisplayName("sendInvite registers afterCommit callback — emailService not called before commit fires")
    void should_notCallEmailDirectly_when_sendInviteRegistersAfterCommitCallback() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("master@example.com", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);
        var salonStub = mock(Salon.class);
        when(salonStub.getName()).thenReturn("Test Salon");

        when(tokenGenerator.generateToken()).thenReturn("raw-token");
        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(salonStub));
        when(inviteTokenRepository.findByEmailAndIsUsedFalse("master@example.com")).thenReturn(Optional.empty());
        when(inviteTokenRepository.save(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionSynchronizationManager.initSynchronization();
        try {
            inviteService.sendInvite(request, callerId);

            verify(emailService, never()).sendInviteEmail(anyString(), anyString(), anyString());

            List<TransactionSynchronization> synchronizations =
                    TransactionSynchronizationManager.getSynchronizations();
            assertThat(synchronizations).hasSize(1);

            synchronizations.forEach(TransactionSynchronization::afterCommit);

            verify(emailService).sendInviteEmail(eq("master@example.com"), anyString(), eq("Test Salon"));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private InviteToken buildInviteToken(String email, Instant expiresAt) {
        return new InviteToken(UUID.randomUUID().toString(), email, UUID.randomUUID(), Role.SALON_MASTER, expiresAt);
    }

    private User buildCallerWithSalon(UUID callerId, UUID salonId) {
        var caller = new User("owner@beautica.test", "hash", Role.SALON_OWNER, null, null, null, salonId);
        ReflectionTestUtils.setField(caller, "id", callerId);
        return caller;
    }
}
