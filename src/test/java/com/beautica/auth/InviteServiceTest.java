package com.beautica.auth;

import com.beautica.auth.dto.InviteAcceptRequest;
import com.beautica.auth.dto.InvitePreviewResponse;
import com.beautica.auth.dto.InviteRequest;
import com.beautica.common.exception.BusinessException;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.exception.NotFoundException;
import com.beautica.config.JwtConfig;
import com.beautica.notification.EmailService;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.RefreshToken;
import com.beautica.user.RefreshTokenRepository;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private EmailService emailService;

    private JwtTokenProvider jwtTokenProvider;
    private JwtConfig jwtConfig;
    private PasswordEncoder passwordEncoder;
    private InviteService inviteService;

    @BeforeEach
    void setUp() {
        passwordEncoder = new BCryptPasswordEncoder(4);
        jwtConfig = new JwtConfig(SECRET, ACCESS_MS, REFRESH_MS);
        jwtTokenProvider = new JwtTokenProvider(jwtConfig);
        inviteService = new InviteService(
                inviteTokenRepository,
                userRepository,
                refreshTokenRepository,
                jwtTokenProvider,
                jwtConfig,
                passwordEncoder,
                emailService,
                "http://localhost:3000",
                72L
        );
    }

    @Test
    @DisplayName("sendInvite stores hashed token, not the raw UUID")
    void should_storeHashedToken_when_sendInviteSucceeds() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("master@example.com", salonId);
        var caller = buildCallerWithSalon(callerId, salonId);
        log.debug("Arrange: caller owns salon salonId={}", salonId);

        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(inviteTokenRepository.findByEmailAndIsUsedFalse("master@example.com"))
                .thenReturn(Optional.empty());

        ArgumentCaptor<InviteToken> captor = ArgumentCaptor.forClass(InviteToken.class);
        when(inviteTokenRepository.save(captor.capture())).thenAnswer(inv -> inv.getArgument(0));

        log.debug("Act: calling inviteService.sendInvite");
        inviteService.sendInvite(request, callerId);

        InviteToken saved = captor.getValue();
        log.trace("Assert: saved token is not the raw UUID");
        assertThat(saved.getToken()).isNotBlank();

        ArgumentCaptor<String> linkCaptor = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendInviteEmail(anyString(), linkCaptor.capture());
        String rawTokenFromLink = linkCaptor.getValue().substring(linkCaptor.getValue().lastIndexOf('=') + 1);

        assertThat(saved.getToken()).isNotEqualTo(rawTokenFromLink);
        assertThat(saved.getToken()).isEqualTo(sha256Hex(rawTokenFromLink));
    }

    @Test
    @DisplayName("sendInvite saves token and sends email on happy path")
    void should_saveTokenAndSendEmail_when_sendInviteSucceeds() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("master@example.com", salonId);
        var caller = buildCallerWithSalon(callerId, salonId);
        log.debug("Arrange: email={} salonId={}", request.email(), salonId);

        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(inviteTokenRepository.findByEmailAndIsUsedFalse("master@example.com"))
                .thenReturn(Optional.empty());
        when(inviteTokenRepository.save(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));

        log.debug("Act: calling inviteService.sendInvite");
        var response = inviteService.sendInvite(request, callerId);

        log.trace("Assert: response email={}", response.invitedEmail());
        assertThat(response.invitedEmail()).isEqualTo("master@example.com");
        assertThat(response.expiresAt()).isAfter(Instant.now());

        verify(inviteTokenRepository).save(any(InviteToken.class));
        verify(emailService).sendInviteEmail(anyString(), anyString());
    }

    @Test
    @DisplayName("sendInvite throws BusinessException when email already registered")
    void should_throwBusinessException_when_emailAlreadyRegistered() {
        var request = new InviteRequest("taken@example.com", UUID.randomUUID());
        log.debug("Arrange: email={} already registered", request.email());

        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        log.debug("Act: calling sendInvite expecting BusinessException");
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
        var request = new InviteRequest("master@example.com", requestedSalonId);
        var caller = buildCallerWithSalon(callerId, callerOwnedSalonId);
        log.debug("Arrange: caller salonId={} != requested salonId={}", callerOwnedSalonId, requestedSalonId);

        when(userRepository.existsByEmail("master@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));

        log.debug("Act: calling sendInvite expecting ForbiddenException");
        assertThatThrownBy(() -> inviteService.sendInvite(request, callerId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("do not own");

        verify(inviteTokenRepository, never()).save(any());
    }

    @Test
    @DisplayName("sendInvite throws BusinessException when active invite already exists")
    void should_throwBusinessException_when_activeInviteExists() {
        var salonId = UUID.randomUUID();
        var callerId = UUID.randomUUID();
        var request = new InviteRequest("pending@example.com", salonId);
        var caller = buildCallerWithSalon(callerId, salonId);
        var existing = buildInviteToken("pending@example.com", Instant.now().plusSeconds(3600));
        log.debug("Arrange: active invite exists for email={}", request.email());

        when(userRepository.existsByEmail("pending@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(inviteTokenRepository.findByEmailAndIsUsedFalse("pending@example.com"))
                .thenReturn(Optional.of(existing));

        log.debug("Act: calling sendInvite expecting BusinessException");
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
        var request = new InviteRequest("expired@example.com", salonId);
        var caller = buildCallerWithSalon(callerId, salonId);
        var expired = buildInviteToken("expired@example.com", Instant.now().minusSeconds(1));
        log.debug("Arrange: expired invite exists for email={}", request.email());

        when(userRepository.existsByEmail("expired@example.com")).thenReturn(false);
        when(userRepository.findById(callerId)).thenReturn(Optional.of(caller));
        when(inviteTokenRepository.findByEmailAndIsUsedFalse("expired@example.com"))
                .thenReturn(Optional.of(expired));
        when(inviteTokenRepository.save(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));

        log.debug("Act: calling sendInvite with expired existing invite");
        var response = inviteService.sendInvite(request, callerId);

        log.trace("Assert: old token deleted, new token saved");
        verify(inviteTokenRepository).delete(expired);
        verify(inviteTokenRepository).save(any(InviteToken.class));
        verify(emailService).sendInviteEmail(anyString(), anyString());
        assertThat(response.invitedEmail()).isEqualTo("expired@example.com");
    }

    @Test
    @DisplayName("acceptInvite creates SALON_MASTER user and returns auth response")
    void should_createSalonMasterAndReturnAuthResponse_when_acceptInviteSucceeds() {
        var rawToken = UUID.randomUUID().toString();
        var hashedToken = sha256Hex(rawToken);
        var salonId = UUID.randomUUID();
        var invite = buildInviteToken("new@example.com", Instant.now().plusSeconds(3600));
        ReflectionTestUtils.setField(invite, "salonId", salonId);
        var request = new InviteAcceptRequest(rawToken, "password123", "Jane", "Doe", null);
        log.debug("Arrange: valid unused token for email=new@example.com salonId={}", salonId);

        when(inviteTokenRepository.findByTokenForUpdate(hashedToken)).thenReturn(Optional.of(invite));
        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(userRepository.save(any(User.class))).thenAnswer(inv -> {
            var u = (User) inv.getArgument(0);
            ReflectionTestUtils.setField(u, "id", UUID.randomUUID());
            return u;
        });
        when(refreshTokenRepository.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inviteTokenRepository.save(any(InviteToken.class))).thenAnswer(inv -> inv.getArgument(0));

        log.debug("Act: calling acceptInvite");
        var response = inviteService.acceptInvite(request);

        log.trace("Assert: SALON_MASTER user created, auth tokens issued");
        assertThat(response.role()).isEqualTo(Role.SALON_MASTER);
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
        var hashedToken = sha256Hex(rawToken);
        var request = new InviteAcceptRequest(rawToken, "password123", null, null, null);
        log.debug("Arrange: no invite token found");

        when(inviteTokenRepository.findByTokenForUpdate(hashedToken)).thenReturn(Optional.empty());

        log.debug("Act: calling acceptInvite expecting NotFoundException");
        assertThatThrownBy(() -> inviteService.acceptInvite(request))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("acceptInvite throws BusinessException when token is already used")
    void should_throwBusinessException_when_tokenAlreadyUsed() {
        var rawToken = UUID.randomUUID().toString();
        var hashedToken = sha256Hex(rawToken);
        var invite = buildInviteToken("used@example.com", Instant.now().plusSeconds(3600));
        invite.markUsed();
        var request = new InviteAcceptRequest(rawToken, "password123", null, null, null);
        log.debug("Arrange: invite token already marked used");

        when(inviteTokenRepository.findByTokenForUpdate(hashedToken)).thenReturn(Optional.of(invite));

        log.debug("Act: calling acceptInvite expecting BusinessException");
        assertThatThrownBy(() -> inviteService.acceptInvite(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already been used");
    }

    @Test
    @DisplayName("acceptInvite throws BusinessException when token is expired")
    void should_throwBusinessException_when_tokenExpired() {
        var rawToken = UUID.randomUUID().toString();
        var hashedToken = sha256Hex(rawToken);
        var invite = buildInviteToken("expired2@example.com", Instant.now().minusSeconds(1));
        var request = new InviteAcceptRequest(rawToken, "password123", null, null, null);
        log.debug("Arrange: invite token is expired");

        when(inviteTokenRepository.findByTokenForUpdate(hashedToken)).thenReturn(Optional.of(invite));

        log.debug("Act: calling acceptInvite expecting BusinessException");
        assertThatThrownBy(() -> inviteService.acceptInvite(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("acceptInvite throws BusinessException when email already registered")
    void should_throwBusinessException_when_emailAlreadyRegisteredOnAccept() {
        var rawToken = UUID.randomUUID().toString();
        var hashedToken = sha256Hex(rawToken);
        var invite = buildInviteToken("collision@example.com", Instant.now().plusSeconds(3600));
        var request = new InviteAcceptRequest(rawToken, "password123", null, null, null);
        log.debug("Arrange: invite token valid but email collision detected");

        when(inviteTokenRepository.findByTokenForUpdate(hashedToken)).thenReturn(Optional.of(invite));
        when(userRepository.existsByEmail("collision@example.com")).thenReturn(true);

        log.debug("Act: calling acceptInvite expecting BusinessException");
        assertThatThrownBy(() -> inviteService.acceptInvite(request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    @DisplayName("previewInvite returns InvitePreviewResponse for a valid token")
    void should_return_previewResponse_when_validToken() {
        var rawToken = UUID.randomUUID().toString();
        var hashedToken = sha256Hex(rawToken);
        var expiresAt = Instant.now().plusSeconds(3600);
        var invite = buildInviteToken("preview@example.com", expiresAt);
        log.debug("Arrange: valid unused token for email=preview@example.com");

        when(inviteTokenRepository.findByToken(hashedToken)).thenReturn(Optional.of(invite));

        log.debug("Act: calling previewInvite");
        InvitePreviewResponse response = inviteService.previewInvite(rawToken);

        log.trace("Assert: response fields match invite token");
        assertThat(response.invitedEmail()).isEqualTo("preview@example.com");
        assertThat(response.role()).isEqualTo(Role.SALON_MASTER);
        assertThat(response.expiresAt()).isEqualTo(invite.getExpiresAt());
    }

    @Test
    @DisplayName("previewInvite throws BusinessException when token does not exist")
    void should_throwNotFound_when_previewTokenNotFound() {
        var rawToken = "unknown-raw-token";
        var hashedToken = sha256Hex(rawToken);
        log.debug("Arrange: no matching token in repository");

        when(inviteTokenRepository.findByToken(hashedToken)).thenReturn(Optional.empty());

        log.debug("Act: calling previewInvite expecting BusinessException");
        assertThatThrownBy(() -> inviteService.previewInvite(rawToken))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired invite token");
    }

    @Test
    @DisplayName("previewInvite throws BusinessException when token is already used")
    void should_throw400_when_previewTokenAlreadyUsed() {
        var rawToken = UUID.randomUUID().toString();
        var hashedToken = sha256Hex(rawToken);
        var invite = buildInviteToken("used@example.com", Instant.now().plusSeconds(3600));
        invite.markUsed();
        log.debug("Arrange: invite token already marked used");

        when(inviteTokenRepository.findByToken(hashedToken)).thenReturn(Optional.of(invite));

        log.debug("Act: calling previewInvite expecting BusinessException");
        assertThatThrownBy(() -> inviteService.previewInvite(rawToken))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired invite token");
    }

    @Test
    @DisplayName("previewInvite throws BusinessException when token is expired")
    void should_throw400_when_previewTokenExpired() {
        var rawToken = UUID.randomUUID().toString();
        var hashedToken = sha256Hex(rawToken);
        var invite = buildInviteToken("expired@example.com", Instant.now().minusSeconds(1));
        log.debug("Arrange: invite token is expired");

        when(inviteTokenRepository.findByToken(hashedToken)).thenReturn(Optional.of(invite));

        log.debug("Act: calling previewInvite expecting BusinessException");
        assertThatThrownBy(() -> inviteService.previewInvite(rawToken))
                .isInstanceOf(BusinessException.class)
                .hasMessage("Invalid or expired invite token");
    }

    private InviteToken buildInviteToken(String email, Instant expiresAt) {
        return new InviteToken(UUID.randomUUID().toString(), email, UUID.randomUUID(), Role.SALON_MASTER, expiresAt);
    }

    private User buildCallerWithSalon(UUID callerId, UUID salonId) {
        var caller = new User("owner@beautica.test", "hash", Role.SALON_OWNER, null, null, null, salonId);
        ReflectionTestUtils.setField(caller, "id", callerId);
        return caller;
    }

    private String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
