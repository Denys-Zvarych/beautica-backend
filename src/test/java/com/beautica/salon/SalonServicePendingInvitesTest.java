package com.beautica.salon;

import com.beautica.auth.InviteService;
import com.beautica.auth.Role;
import com.beautica.common.exception.NotFoundException;
import com.beautica.common.security.AuthorizationService;
import com.beautica.location.LocalityWriteValidator;
import com.beautica.location.repository.CityRepository;
import com.beautica.location.service.LocationQueryService;
import com.beautica.master.repository.MasterRepository;
import com.beautica.master.service.MasterService;
import com.beautica.salon.dto.PendingInviteResponse;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.user.InviteToken;
import com.beautica.user.InviteTokenRepository;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SalonService#listPendingInvites} and {@link SalonService#cancelInvite}
 * (Phase 23.1).
 *
 * <p>Salon-scoping ("is the caller the owner or an admin of {@code salonId}") is already
 * enforced by {@code @PreAuthorize("... and @authz.canManageSalon(authentication, #salonId)")}
 * on {@code SalonController} — mirrors {@code SalonServiceRemoveAdminTest}'s rationale. These
 * tests cover the behaviour that IS expressible at the service layer: the repository query
 * wiring, DTO mapping (never leaking the token), the used/expired exclusion contract, and the
 * defense-in-depth cross-salon + already-used checks in {@code cancelInvite}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SalonService — pending invites (Phase 23.1) — unit")
class SalonServicePendingInvitesTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-08-29T12:00:00Z");

    @Mock
    private SalonRepository salonRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private InviteService inviteService;

    @Mock
    private InviteTokenRepository inviteTokenRepository;

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private MasterServiceRepository masterServiceRepository;

    @Mock
    private LocalityWriteValidator localityWriteValidator;

    @Mock
    private MasterService masterService;

    @Mock
    private CityRepository cityRepository;

    @Mock
    private LocationQueryService locationQueryService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private AuthorizationService authorizationService;

    private SalonService salonService;

    @BeforeEach
    void setUp() {
        Clock fixedClock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        salonService = new SalonService(
                salonRepository, userRepository, inviteService, inviteTokenRepository, masterRepository,
                masterServiceRepository, localityWriteValidator, masterService, cityRepository,
                locationQueryService, cacheManager, authorizationService, fixedClock);
    }

    @Test
    @DisplayName("listPendingInvites maps repository rows to PendingInviteResponse without exposing the token")
    void should_returnMappedResponses_when_pendingInvitesExist() {
        UUID salonId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        InviteToken token = buildToken(inviteId, "invitee@beautica.test", salonId, Role.SALON_MASTER,
                FIXED_NOW.plusSeconds(3600), false);

        when(inviteTokenRepository.findBySalonIdAndIsUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(salonId), any(Instant.class)))
                .thenReturn(List.of(token));

        List<PendingInviteResponse> result = salonService.listPendingInvites(salonId);

        assertThat(result).hasSize(1);
        PendingInviteResponse response = result.get(0);
        assertThat(response.inviteId()).isEqualTo(inviteId);
        assertThat(response.recipientEmail()).isEqualTo("invitee@beautica.test");
        assertThat(response.role()).isEqualTo("SALON_MASTER");
        assertThat(response.expiresAt()).isEqualTo(FIXED_NOW.plusSeconds(3600));
    }

    @Test
    @DisplayName("listPendingInvites passes clock.instant() as the expiry cutoff — not a bare Instant.now()")
    void should_queryUsingInjectedClock_when_listingPendingInvites() {
        UUID salonId = UUID.randomUUID();
        when(inviteTokenRepository.findBySalonIdAndIsUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(salonId), eq(FIXED_NOW)))
                .thenReturn(List.of());

        List<PendingInviteResponse> result = salonService.listPendingInvites(salonId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("listPendingInvites returns an empty list when no pending invites exist")
    void should_returnEmptyList_when_noPendingInvitesExist() {
        UUID salonId = UUID.randomUUID();
        when(inviteTokenRepository.findBySalonIdAndIsUsedFalseAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(salonId), any(Instant.class)))
                .thenReturn(List.of());

        assertThat(salonService.listPendingInvites(salonId)).isEmpty();
    }

    @Test
    @DisplayName("cancelInvite marks the token used=true rather than deleting the row")
    void should_markTokenUsed_when_cancellingAPendingInvite() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        InviteToken token = buildToken(inviteId, "invitee@beautica.test", salonId, Role.SALON_MASTER,
                FIXED_NOW.plusSeconds(3600), false);

        when(inviteTokenRepository.findByIdForUpdate(inviteId)).thenReturn(Optional.of(token));

        salonService.cancelInvite(actorId, salonId, inviteId);

        assertThat(token.isUsed())
                .as("cancelInvite must mark the token used, never delete it (avoids FK-cascade surprises)")
                .isTrue();
    }

    @Test
    @DisplayName("cancelInvite throws NotFoundException when the invite id does not exist")
    void should_throwNotFound_when_inviteDoesNotExist() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID missingInviteId = UUID.randomUUID();

        when(inviteTokenRepository.findByIdForUpdate(missingInviteId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> salonService.cancelInvite(actorId, salonId, missingInviteId))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("cancelInvite throws NotFoundException (defense-in-depth) when the token belongs to a different salon")
    void should_throwNotFound_when_tokenBelongsToDifferentSalon() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID otherSalonId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        InviteToken token = buildToken(inviteId, "invitee@beautica.test", otherSalonId, Role.SALON_MASTER,
                FIXED_NOW.plusSeconds(3600), false);

        when(inviteTokenRepository.findByIdForUpdate(inviteId)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> salonService.cancelInvite(actorId, salonId, inviteId))
                .isInstanceOf(NotFoundException.class);
        assertThat(token.isUsed())
                .as("a cross-salon token must never be mutated")
                .isFalse();
    }

    @Test
    @DisplayName("cancelInvite throws NotFoundException when the token is already used")
    void should_throwNotFound_when_tokenAlreadyUsed() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID inviteId = UUID.randomUUID();
        InviteToken token = buildToken(inviteId, "invitee@beautica.test", salonId, Role.SALON_MASTER,
                FIXED_NOW.plusSeconds(3600), true);

        when(inviteTokenRepository.findByIdForUpdate(inviteId)).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> salonService.cancelInvite(actorId, salonId, inviteId))
                .isInstanceOf(NotFoundException.class);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private InviteToken buildToken(UUID id, String email, UUID salonId, Role role, Instant expiresAt,
            boolean used) {
        InviteToken token = new InviteToken("raw-token-" + id, email, salonId, role, expiresAt);
        ReflectionTestUtils.setField(token, "id", id);
        ReflectionTestUtils.setField(token, "createdAt", FIXED_NOW);
        if (used) {
            token.markUsed();
        }
        return token;
    }
}
