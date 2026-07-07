package com.beautica.auth;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InvitePreviewResponse;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.master.service.MasterService;
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
import java.time.Clock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import static org.mockito.Mockito.doThrow;
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
    private InvitePersistenceService invitePersistenceService;

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
                tokenGenerator,
                masterService,
                authResponseBuilder,
                invitePersistenceService,
                "http://localhost:3000",
                48L,
                Clock.systemUTC()
        );
    }

    @Test
    @DisplayName("sendInvite passes the hashed token (not the raw token) to the persistence delegate")
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
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("master@example.com", salonId))
                .thenReturn(Optional.empty());

        log.debug("Act: sendInvite with tokenGenerator returning raw='{}' hashed='{}'", rawToken, hashedToken);
        inviteService.sendInvite(request, callerId);

        // Persistence (saveAndFlush) moved into InvitePersistenceService; assert the hashed value
        // and the link are forwarded to the delegate, not the raw token.
        ArgumentCaptor<String> hashedCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(invitePersistenceService).persistInviteAndEnqueue(
                any(), any(), any(), any(), hashedCaptor.capture(), linkCaptor.capture(), any());
        assertThat(hashedCaptor.getValue())
                .as("hashed token forwarded to persistence must equal the hashed value, not raw='%s'", rawToken)
                .isEqualTo(hashedToken);
        assertThat(hashedCaptor.getValue()).isNotEqualTo(rawToken);
        assertThat(linkCaptor.getValue()).endsWith(rawToken);
    }

    @Test
    @DisplayName("sendInvite delegates persist + enqueue to InvitePersistenceService on the happy path")
    void should_saveTokenAndEnqueueOutbox_when_sendInviteSucceeds() {
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
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("master@example.com", salonId))
                .thenReturn(Optional.empty());

        log.debug("Act: sendInvite for email={} salonId={} on happy path", request.email(), salonId);
        var response = inviteService.sendInvite(request, callerId);

        assertThat(response.invitedEmail())
                .as("response must carry the invited email address")
                .isEqualTo("master@example.com");
        assertThat(response.expiresAt()).isAfter(Instant.now());

        verify(invitePersistenceService).persistInviteAndEnqueue(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("sendInvite returns generic success (no delegate call) when target email already registered — enumeration hardening")
    void should_returnGenericSuccessNoToken_when_emailAlreadyRegistered() {
        // New contract: an already-registered target is NOT a distinguishing 409 (that was an
        // enumeration oracle). All authorization/ownership checks still run first, then the
        // already-registered branch returns the same generic InviteResponse WITHOUT creating a
        // token or enqueuing an e-mail. The flow now reaches findById(callerId), so the caller
        // and salon-ownership path must be stubbed.
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("taken@example.com", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);
        log.debug("Arrange: email={} already registered; caller owns salonId={}", request.email(), salonId);

        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(mock(Salon.class)));

        log.debug("Act: sendInvite for already-registered email={} — must return generic success, no token", request.email());
        var response = inviteService.sendInvite(request, callerId);

        assertThat(response.invitedEmail())
                .as("already-registered target must still echo the same generic invited email")
                .isEqualTo("taken@example.com");
        assertThat(response.expiresAt())
                .as("response must carry a plausible recomputed expiry, actual=%s", response.expiresAt())
                .isAfter(Instant.now());

        // The distinguishing side effects must be ABSENT, not merely hidden.
        verify(invitePersistenceService, never())
                .persistInviteAndEnqueue(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("sendInvite returns a structurally identical response for a brand-new vs already-registered target (no distinguishing field)")
    void should_returnIdenticallyShapedResponse_forNewAndRegisteredTargets() {
        // Indistinguishability proof at the service layer: the brand-new branch (token issued)
        // and the already-registered branch (no token) must yield the SAME response shape — same
        // invitedEmail field and a non-null expiry — so a caller cannot tell them apart by body.
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var caller = buildCallerWithSalon(callerId, salonId);
        var salonStub = mock(Salon.class);
        when(salonStub.getName()).thenReturn("Test Salon");
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(salonStub));
        when(tokenGenerator.generateToken()).thenReturn("raw-token");

        // Brand-new target → token issued.
        var newRequest = new InviteRequest("brandnew@example.com", salonId, null);
        when(userRepository.existsByEmail("brandnew@example.com")).thenReturn(false);
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("brandnew@example.com", salonId))
                .thenReturn(Optional.empty());
        var newResponse = inviteService.sendInvite(newRequest, callerId);

        // Already-registered target → no token, same shape.
        var registeredRequest = new InviteRequest("brandnew@example.com", salonId, null);
        when(userRepository.existsByEmail("brandnew@example.com")).thenReturn(true);
        var registeredResponse = inviteService.sendInvite(registeredRequest, callerId);

        assertThat(registeredResponse.invitedEmail())
                .as("both branches must echo the same invitedEmail")
                .isEqualTo(newResponse.invitedEmail());
        assertThat(newResponse.expiresAt())
                .as("brand-new branch must carry a non-null expiry")
                .isNotNull();
        assertThat(registeredResponse.expiresAt())
                .as("already-registered branch must carry a non-null expiry (no distinguishing null)")
                .isNotNull();
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
        verify(invitePersistenceService, never())
                .persistInviteAndEnqueue(any(), any(), any(), any(), any(), any(), any());
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
        verify(invitePersistenceService, never())
                .persistInviteAndEnqueue(any(), any(), any(), any(), any(), any(), any());
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
        verify(invitePersistenceService, never())
                .persistInviteAndEnqueue(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("sendInvite is idempotent (no delegate call) when an active unexpired invite already exists")
    void should_returnGenericSuccessNoNewToken_when_activeInviteExists() {
        // New contract: a pre-existing active (unused, unexpired) invite is an idempotent success.
        // The old 409 here re-opened the enumeration oracle (a second call to a pending email hit
        // 409 while a registered email kept returning 200). It now returns the same generic
        // InviteResponse WITHOUT delegating to persistInviteAndEnqueue — so no second token, no
        // second e-mail, and the still-valid existing token is left untouched.
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("pending@example.com", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);
        var existing = buildInviteToken("pending@example.com", Instant.now().plusSeconds(3600));
        log.debug("Arrange: active unexpired invite already exists for email={}", request.email());

        when(userRepository.existsByEmail("pending@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(mock(Salon.class)));
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("pending@example.com", salonId))
                .thenReturn(Optional.of(existing));

        log.debug("Act: sendInvite for email={} with an active invite — must be idempotent success", request.email());
        var response = inviteService.sendInvite(request, callerId);

        assertThat(response.invitedEmail())
                .as("idempotent active-invite path must echo the same generic invited email")
                .isEqualTo("pending@example.com");
        assertThat(response.expiresAt()).isAfter(Instant.now());

        // Active invite short-circuits before any persistence: the delegate is never called.
        verify(invitePersistenceService, never())
                .persistInviteAndEnqueue(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("sendInvite delegates to persistInviteAndEnqueue (recycle handled internally) when an expired invite occupies the slot")
    void should_callPersistInviteAndEnqueue_when_expiredInviteExists() {
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
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("expired@example.com", salonId))
                .thenReturn(Optional.of(expired));

        log.debug("Act: sendInvite for email={} — expired invite exists; recycle + insert delegated to persistence service", request.email());
        var response = inviteService.sendInvite(request, callerId);

        // Expired prior token does NOT short-circuit; the recycle (delete-before-insert) now happens
        // INSIDE InvitePersistenceService, so here we only assert the delegate was invoked.
        verify(invitePersistenceService).persistInviteAndEnqueue(
                any(), any(), any(), any(), any(), any(), any());
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
        var request = new InviteAcceptRequest(rawToken, "Str0ngP@ss1!", "Jane", "Doe", null);
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
        var request = new InviteAcceptRequest(rawToken, "Str0ngP@ss1!", null, null, null);
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
        var request = new InviteAcceptRequest(rawToken, "Str0ngP@ss1!", null, null, null);
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
        var request = new InviteAcceptRequest(rawToken, "Str0ngP@ss1!", null, null, null);
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
        var request = new InviteAcceptRequest(rawToken, "Str0ngP@ss1!", null, null, null);
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
    @DisplayName("sendInvite forwards SALON_ADMIN role to the delegate when SALON_OWNER requests admin role")
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
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("admin@example.com", salonId))
                .thenReturn(Optional.empty());
        when(tokenGenerator.generateToken()).thenReturn("raw-admin-token");

        log.debug("Act: sendInvite with SALON_ADMIN role for email={} salonId={}", request.email(), salonId);
        var response = inviteService.sendInvite(request, callerId);

        assertThat(response.invitedEmail()).isEqualTo("admin@example.com");
        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(invitePersistenceService).persistInviteAndEnqueue(
                any(), any(), roleCaptor.capture(), any(), any(), any(), any());
        assertThat(roleCaptor.getValue()).isEqualTo(Role.SALON_ADMIN);
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

        verify(invitePersistenceService, never())
                .persistInviteAndEnqueue(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Phase 21.1: sendInvite succeeds when the salon already has a SALON_ADMIN (uniqueness relaxed)")
    void should_sendAdminInvite_when_salonAlreadyHasAdmin() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("admin2@example.com", salonId, Role.SALON_ADMIN);
        var caller = buildCallerWithSalon(callerId, salonId);
        var salonStub = mock(Salon.class);
        when(salonStub.getName()).thenReturn("Test Salon");
        log.debug("Arrange: salon already has a SALON_ADMIN, salonId={}", salonId);

        when(userRepository.existsByEmail("admin2@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(salonStub));
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("admin2@example.com", salonId))
                .thenReturn(Optional.empty());
        when(tokenGenerator.generateToken()).thenReturn("raw-second-admin-token");

        log.debug("Act: sendInvite with SALON_ADMIN role for salonId={} that already has an admin — must now succeed", salonId);
        var response = inviteService.sendInvite(request, callerId);

        assertThat(response.invitedEmail()).isEqualTo("admin2@example.com");
        ArgumentCaptor<Role> roleCaptor = ArgumentCaptor.forClass(Role.class);
        verify(invitePersistenceService).persistInviteAndEnqueue(
                any(), any(), roleCaptor.capture(), any(), any(), any(), any());
        assertThat(roleCaptor.getValue()).isEqualTo(Role.SALON_ADMIN);
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

        verify(invitePersistenceService, never())
                .persistInviteAndEnqueue(any(), any(), any(), any(), any(), any(), any());
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

        verify(invitePersistenceService, never())
                .persistInviteAndEnqueue(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("sendInvite passes salon name to the persistence delegate when salon is found")
    void should_passSalonNameToOutbox_when_salonFoundDuringSendInvite() {
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
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("master@example.com", salonId))
                .thenReturn(Optional.empty());
        when(tokenGenerator.generateToken()).thenReturn("raw-tok");

        log.debug("Act: sendInvite for salon 'Glamour Studio' salonId={} — salon name must be forwarded to the delegate", salonId);
        inviteService.sendInvite(request, callerId);

        ArgumentCaptor<String> salonNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(invitePersistenceService).persistInviteAndEnqueue(
                any(), any(), any(), any(), any(), any(), salonNameCaptor.capture());
        assertThat(salonNameCaptor.getValue()).isEqualTo("Glamour Studio");
    }

    @Test
    @DisplayName("sendInvite delegates persist+enqueue with the invitee email, raw-token link and salon name")
    void should_writeOutboxRow_when_sendInviteSucceeds() {
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
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("master@example.com", salonId)).thenReturn(Optional.empty());

        inviteService.sendInvite(request, callerId);

        // The persist+enqueue delegation must run once with the normalized invitee email, the salon
        // id, the full URL ending in the raw token, and the salon name. The saveAndFlush-before-
        // enqueue ORDERING is now an internal detail of InvitePersistenceService (verified there).
        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(invitePersistenceService).persistInviteAndEnqueue(
                eq("master@example.com"),
                eq(salonId),
                any(),
                any(),
                any(),
                linkCaptor.capture(),
                eq("Test Salon"));
        assertThat(linkCaptor.getValue()).endsWith("raw-token");
    }

    // ── @PostConstruct startup validation ────────────────────────────────────

    @Test
    @DisplayName("validateConfig throws IllegalStateException at startup when frontendBaseUrl is plain HTTP")
    void should_throwIllegalState_when_validateConfig_withPlainHttp() {
        var service = new InviteService(
                inviteTokenRepository,
                userRepository,
                salonRepository,
                passwordEncoder,
                tokenGenerator,
                masterService,
                authResponseBuilder,
                invitePersistenceService,
                "http://example.com",
                48L,
                Clock.systemUTC()
        );

        assertThatThrownBy(service::validateConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use HTTPS scheme");
    }

    @Test
    @DisplayName("validateConfig throws IllegalStateException at startup when frontendBaseUrl is null")
    void should_throwIllegalState_when_validateConfig_withNull() {
        var service = new InviteService(
                inviteTokenRepository,
                userRepository,
                salonRepository,
                passwordEncoder,
                tokenGenerator,
                masterService,
                authResponseBuilder,
                invitePersistenceService,
                null,
                48L,
                Clock.systemUTC()
        );

        assertThatThrownBy(service::validateConfig)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use HTTPS scheme");
    }

    @Test
    @DisplayName("validateConfig passes silently when frontendBaseUrl is HTTPS")
    void should_notThrow_when_validateConfig_withHttps() {
        var service = new InviteService(
                inviteTokenRepository,
                userRepository,
                salonRepository,
                passwordEncoder,
                tokenGenerator,
                masterService,
                authResponseBuilder,
                invitePersistenceService,
                "https://beautica.app",
                48L,
                Clock.systemUTC()
        );

        // Must not throw
        service.validateConfig();
    }

    @Test
    @DisplayName("validateConfig passes silently when frontendBaseUrl is http://localhost with port")
    void should_notThrow_when_validateConfig_withLocalhostPort() {
        var service = new InviteService(
                inviteTokenRepository,
                userRepository,
                salonRepository,
                passwordEncoder,
                tokenGenerator,
                masterService,
                authResponseBuilder,
                invitePersistenceService,
                "http://localhost:3000",
                48L,
                Clock.systemUTC()
        );

        // Must not throw — localhost dev URLs are allowed
        service.validateConfig();
    }

    // ── Phase 5.9 — buildInviteLink HTTPS scheme guard ───────────────────────

    @Test
    @DisplayName("buildInviteLink throws IllegalStateException when frontendBaseUrl is plain HTTP")
    void should_throwIllegalState_when_frontendBaseUrlIsPlainHttp() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("master@example.com", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);

        var httpService = new InviteService(
                inviteTokenRepository,
                userRepository,
                salonRepository,
                passwordEncoder,
                tokenGenerator,
                masterService,
                authResponseBuilder,
                invitePersistenceService,
                "http://example.com",
                48L,
                Clock.systemUTC()
        );

        when(tokenGenerator.generateToken()).thenReturn("raw-token");
        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(mock(Salon.class)));
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("master@example.com", salonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> httpService.sendInvite(request, callerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must use HTTPS scheme");
    }

    @Test
    @DisplayName("buildInviteLink returns link starting with HTTPS base URL when frontendBaseUrl uses HTTPS")
    void should_buildInviteLink_withHttps_when_frontendBaseUrlIsHttps() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("master@example.com", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);
        var salonStub = mock(Salon.class);
        when(salonStub.getName()).thenReturn("Test Salon");

        var httpsService = new InviteService(
                inviteTokenRepository,
                userRepository,
                salonRepository,
                passwordEncoder,
                tokenGenerator,
                masterService,
                authResponseBuilder,
                invitePersistenceService,
                "https://beautica.app",
                48L,
                Clock.systemUTC()
        );

        when(tokenGenerator.generateToken()).thenReturn("raw-token");
        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(salonStub));
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("master@example.com", salonId)).thenReturn(Optional.empty());

        httpsService.sendInvite(request, callerId);

        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(invitePersistenceService).persistInviteAndEnqueue(
                any(), any(), any(), any(), any(), linkCaptor.capture(), any());
        assertThat(linkCaptor.getValue()).startsWith("https://beautica.app");
    }

    // ── Localhost prefix-spoof rejection (boundary-correct scheme guard) ──────

    @Test
    @DisplayName("buildInviteLink rejects http://localhost.attacker.com (prefix-spoof)")
    void should_throwIllegalState_when_frontendBaseUrlIsLocalhostSpoof() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("master@example.com", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);

        var spoofService = new InviteService(
                inviteTokenRepository,
                userRepository,
                salonRepository,
                passwordEncoder,
                tokenGenerator,
                masterService,
                authResponseBuilder,
                invitePersistenceService,
                "http://localhost.attacker.com",
                48L,
                Clock.systemUTC()
        );

        when(tokenGenerator.generateToken()).thenReturn("raw-token");
        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(mock(Salon.class)));
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("master@example.com", salonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spoofService.sendInvite(request, callerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS scheme");

        verify(invitePersistenceService, never())
                .persistInviteAndEnqueue(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("buildInviteLink rejects http://localhostXYZ (suffix-spoof, no separator)")
    void should_throwIllegalState_when_frontendBaseUrlIsLocalhostSuffixSpoof() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("master@example.com", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);

        var spoofService = new InviteService(
                inviteTokenRepository,
                userRepository,
                salonRepository,
                passwordEncoder,
                tokenGenerator,
                masterService,
                authResponseBuilder,
                invitePersistenceService,
                "http://localhostXYZ",
                48L,
                Clock.systemUTC()
        );

        when(tokenGenerator.generateToken()).thenReturn("raw-token");
        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(mock(Salon.class)));
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("master@example.com", salonId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> spoofService.sendInvite(request, callerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HTTPS scheme");

        verify(invitePersistenceService, never())
                .persistInviteAndEnqueue(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("buildInviteLink accepts http://localhost:3000 (positive boundary, with port)")
    void should_buildInviteLink_when_frontendBaseUrlIsLocalhostWithPort() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("master@example.com", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);
        var salonStub = mock(Salon.class);
        when(salonStub.getName()).thenReturn("Test Salon");

        var localhostService = new InviteService(
                inviteTokenRepository,
                userRepository,
                salonRepository,
                passwordEncoder,
                tokenGenerator,
                masterService,
                authResponseBuilder,
                invitePersistenceService,
                "http://localhost:3000",
                48L,
                Clock.systemUTC()
        );

        when(tokenGenerator.generateToken()).thenReturn("raw-token");
        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(salonStub));
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("master@example.com", salonId)).thenReturn(Optional.empty());

        localhostService.sendInvite(request, callerId);

        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        // Positive proof the URL passed validation: the delegate was actually invoked with the link.
        verify(invitePersistenceService).persistInviteAndEnqueue(
                any(), any(), any(), any(), any(), linkCaptor.capture(), any());
        assertThat(linkCaptor.getValue()).startsWith("http://localhost:3000/invite/accept?token=");
    }

    // ── Finding 2: SALON_ADMIN can invite masters ─────────────────────────────

    @Test
    @DisplayName("SALON_ADMIN can invite a SALON_MASTER to their own assigned salon")
    void should_allow_salonAdmin_to_invite_master() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("newmaster@example.com", salonId, null);

        // SALON_ADMIN caller — salonId is the assigned salon
        var adminCaller = new User("admin@beautica.test", "hash", Role.SALON_ADMIN, null, null, null, salonId);
        ReflectionTestUtils.setField(adminCaller, "id", callerId);

        var salonStub = mock(Salon.class);
        when(salonStub.getName()).thenReturn("Test Salon");

        when(tokenGenerator.generateToken()).thenReturn("raw-token");
        when(userRepository.existsByEmail("newmaster@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(adminCaller));
        // SALON_ADMIN branch: salonRepository.findById is called (not findByIdAndOwnerId)
        when(salonRepository.findById(salonId)).thenReturn(Optional.of(salonStub));
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("newmaster@example.com", salonId))
                .thenReturn(Optional.empty());

        var response = inviteService.sendInvite(request, callerId);

        assertThat(response.invitedEmail()).isEqualTo("newmaster@example.com");
        verify(salonRepository).findById(salonId);
        verify(invitePersistenceService).persistInviteAndEnqueue(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("SALON_ADMIN is rejected with ForbiddenException when inviting to a foreign salon")
    void should_reject_salonAdmin_inviting_to_foreign_salon() {
        var assignedSalonId = UUID.randomUUID();
        var foreignSalonId  = UUID.randomUUID();
        var callerId        = UUID.randomUUID();
        // Request targets a salon the admin is NOT assigned to
        var request = new InviteRequest("master@example.com", foreignSalonId, null);

        var adminCaller = new User("admin@beautica.test", "hash", Role.SALON_ADMIN, null, null, null, assignedSalonId);
        ReflectionTestUtils.setField(adminCaller, "id", callerId);

        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(adminCaller));

        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("SALON_ADMIN may only invite to their own assigned salon");

        verify(invitePersistenceService, never())
                .persistInviteAndEnqueue(any(), any(), any(), any(), any(), any(), any());
    }

    // ── Finding 3: weak password rejected on invite accept ───────────────────

    @Test
    @DisplayName("InviteAcceptRequest @StrongPassword rejects a weak password that only satisfies @Size(min=12)")
    void should_reject_weakPassword_on_inviteAccept() {
        // @StrongPassword requires uppercase + digit + special char; "aaaaaaaaaaaa" only passes @Size(min=12).
        // Validate directly using Jakarta Validation to confirm the annotation is in place.
        var request = new InviteAcceptRequest("valid-token", "aaaaaaaaaaaa", "First", "Last", null);

        var validator = jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
        var violations = validator.validate(request);

        assertThat(violations)
                .as("@StrongPassword should reject 'aaaaaaaaaaaa' (no uppercase/digit/special)")
                .anyMatch(v -> v.getPropertyPath().toString().equals("password"));
    }

    // ── Concurrency + cross-salon scope (delegate unique-guard fix) ───────────

    @Test
    @DisplayName("sendInvite returns generic success when the persistence delegate trips the unique guard (concurrent same-salon race)")
    void should_returnGenericSuccessAndNotEnqueue_when_concurrentInviteRacesUniqueGuard() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("racer@example.com", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);
        log.debug("Arrange: brand-new email, but the delegate hits the active-invite unique guard (salonId={})", salonId);

        when(tokenGenerator.generateToken()).thenReturn("raw-token");
        when(userRepository.existsByEmail("racer@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(mock(Salon.class)));
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("racer@example.com", salonId))
                .thenReturn(Optional.empty());
        doThrow(new org.springframework.dao.DataIntegrityViolationException("ux_invite_tokens_active"))
                .when(invitePersistenceService)
                .persistInviteAndEnqueue(any(), any(), any(), any(), any(), any(), any());

        log.debug("Act: sendInvite where a concurrent request already inserted the active invite — must resolve idempotently");
        var response = inviteService.sendInvite(request, callerId);

        assertThat(response)
                .as("a raced unique-guard violation must resolve idempotently, not by throwing")
                .isNotNull();
        assertThat(response.invitedEmail())
                .as("racing branch must echo the same generic invited email")
                .isEqualTo("racer@example.com");
        // The delegate WAS attempted (and threw); the caller swallowed it and returned generic success.
        verify(invitePersistenceService).persistInviteAndEnqueue(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("sendInvite creates this salon's own token even when another salon holds an active invite for the same email (cross-salon scope fix)")
    void should_createOwnToken_when_anotherSalonHoldsActiveInviteForSameEmail() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("shared@example.com", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);
        log.debug("Arrange: another salon holds an active invite for shared@example.com — invisible to this salon's scoped lookup (salonId={})", salonId);

        var salonStub = mock(Salon.class);
        when(salonStub.getName()).thenReturn("This Salon");

        when(tokenGenerator.generateToken()).thenReturn("raw-token");
        when(userRepository.existsByEmail("shared@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(salonStub));
        // Scoped to THIS salon → empty; the other salon's active invite is invisible here.
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("shared@example.com", salonId))
                .thenReturn(Optional.empty());

        log.debug("Act: sendInvite for this salon — a token must be created despite the other salon's invite");
        inviteService.sendInvite(request, callerId);

        verify(invitePersistenceService).persistInviteAndEnqueue(
                any(), any(), any(), any(), any(), any(), any());
    }

    // ── Email normalization (lower + strip) — salon-scoped pre-check must agree with lower(email) guard ──

    @Test
    @DisplayName("sendInvite normalizes email (lower+strip) so a re-invite in different case hits the salon-scoped active invite idempotently — no silent drop")
    void should_normalizeEmailAndResolveIdempotently_when_reInvitedWithDifferentCase() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        // Raw input carries mixed case + surrounding whitespace; the canonical form is master@example.com.
        var request = new InviteRequest("  MASTER@Example.COM  ", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);
        var existing = buildInviteToken("master@example.com", Instant.now().plusSeconds(3600));
        log.debug("Arrange: active invite stored under normalized email; re-invite arrives in different case+whitespace");

        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(mock(Salon.class)));
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("master@example.com", salonId))
                .thenReturn(Optional.of(existing));

        log.debug("Act: sendInvite with mixed-case/whitespace email — must normalize before the salon-scoped pre-check");
        var response = inviteService.sendInvite(request, callerId);

        // The scoped pre-check MUST run on the canonical value so the existing active invite is FOUND.
        // Without normalization the raw-case lookup misses it, then the INSERT silently collides on the
        // case-insensitive lower(email) guard — dropping the invite (no token, no e-mail).
        verify(inviteTokenRepository).findByEmailAndSalonIdAndIsUsedFalse("master@example.com", salonId);
        // Registration probe also runs on the canonical value (agrees with AuthService write path).
        verify(userRepository).existsByEmail("master@example.com");
        assertThat(response.invitedEmail())
                .as("response must echo the normalized email, actual=%s", response.invitedEmail())
                .isEqualTo("master@example.com");
        // Idempotent: an active invite already exists, so no second token and no second e-mail.
        verify(invitePersistenceService, never())
                .persistInviteAndEnqueue(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("sendInvite forwards the normalized (lower+strip) email to the persistence delegate for a brand-new mixed-case invite")
    void should_forwardNormalizedEmailToDelegate_when_brandNewInviteHasMixedCaseEmail() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("  NewMaster@Example.COM ", salonId, null);
        var caller = buildCallerWithSalon(callerId, salonId);
        var salonStub = mock(Salon.class);
        when(salonStub.getName()).thenReturn("Test Salon");
        log.debug("Arrange: brand-new invite arrives in mixed case + whitespace, canonical = newmaster@example.com");

        when(tokenGenerator.generateToken()).thenReturn("raw-token");
        when(userRepository.existsByEmail("newmaster@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(salonRepository.findByIdAndOwnerId(salonId, callerId)).thenReturn(Optional.of(salonStub));
        when(inviteTokenRepository.findByEmailAndSalonIdAndIsUsedFalse("newmaster@example.com", salonId))
                .thenReturn(Optional.empty());

        log.debug("Act: sendInvite for a brand-new mixed-case email — delegate must receive the canonical value");
        var response = inviteService.sendInvite(request, callerId);

        // The persisted token + outbox must use the canonical email so the row agrees with the
        // case-insensitive ux_invite_tokens_active index and the accept-time existsByEmail check.
        verify(invitePersistenceService).persistInviteAndEnqueue(
                eq("newmaster@example.com"), eq(salonId), any(), any(), any(), any(), any());
        assertThat(response.invitedEmail())
                .as("response must echo the normalized email, actual=%s", response.invitedEmail())
                .isEqualTo("newmaster@example.com");
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
