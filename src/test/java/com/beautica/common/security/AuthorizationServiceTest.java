package com.beautica.common.security;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingCompletionAccess;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.BookingViewAccess;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorizationService — unit")
class AuthorizationServiceTest {

    @Mock
    private SalonRepository salonRepository;

    @Mock
    private MasterRepository masterRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ServiceRepository serviceRepository;

    @Mock
    private BookingRepository bookingRepository;

    @InjectMocks
    private AuthorizationService authorizationService;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    // Principal is set to an email string (not UUID) to match JwtAuthenticationFilter behaviour.
    // AuthorizationService reads the actor ID from token.getDetails(), not getPrincipal().
    // If that ever changes to use getPrincipal(), these tests would silently pass with a wrong
    // UUID — update this helper to set principal=actorId and remove this comment.
    private Authentication mockAuth(UUID actorId, String roleName) {
        var token = new UsernamePasswordAuthenticationToken(
                "user@example.com",
                null,
                List.of(new SimpleGrantedAuthority(roleName))
        );
        token.setDetails(actorId);
        return token;
    }

    // ── canManageSalon ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("canManageSalon returns true when actor is the salon owner")
    void should_returnTrue_when_actorIsTheSalonOwner() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageSalon(auth, salonId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canManageSalon returns false when actor is not the salon owner")
    void should_returnFalse_when_actorIsNotTheSalonOwner() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(false);

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageSalon(auth, salonId);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("canManageSalon returns true when actor is a SALON_ADMIN belonging to the salon")
    void should_returnTrue_when_actorIsSalonAdminOfSalon() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        // Fix MEDIUM-7: AuthorizationService now calls findSalonIdById (projection) instead of
        // findById (full entity) so only the salonId column is fetched.
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_ADMIN");

        boolean result = authorizationService.canManageSalon(auth, salonId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canManageSalon returns false when actor is a SALON_ADMIN of a different salon")
    void should_returnFalse_when_actorIsSalonAdminOfDifferentSalon() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID otherSalonId = UUID.randomUUID();

        // Fix MEDIUM-7: AuthorizationService now calls findSalonIdById (projection) instead of
        // findById (full entity) so only the salonId column is fetched.
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(otherSalonId));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_ADMIN");

        boolean result = authorizationService.canManageSalon(auth, salonId);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("canManageSalon returns false without DB hit when actor has ROLE_CLIENT")
    void should_returnFalse_without_DB_when_actorIsClient() {
        UUID salonId = UUID.randomUUID();
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_CLIENT");

        boolean result = authorizationService.canManageSalon(auth, salonId);

        assertThat(result).isFalse();
        verify(userRepository, never()).findById(any());
        verify(salonRepository, never()).findById(any());
    }

    @Test
    @DisplayName("canManageSalon returns false without DB hit when actor has ROLE_INDEPENDENT_MASTER")
    void should_returnFalse_without_DB_when_actorIsIndependentMaster() {
        UUID salonId = UUID.randomUUID();
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_INDEPENDENT_MASTER");

        boolean result = authorizationService.canManageSalon(auth, salonId);

        assertThat(result).isFalse();
        verify(userRepository, never()).findById(any());
        verify(salonRepository, never()).findById(any());
    }

    @Test
    @DisplayName("canManageSalon returns false without DB hit when actor has ROLE_SALON_MASTER")
    void should_returnFalse_without_DB_when_actorIsSalonMaster() {
        UUID salonId = UUID.randomUUID();
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_SALON_MASTER");

        boolean result = authorizationService.canManageSalon(auth, salonId);

        assertThat(result).isFalse();
        verify(userRepository, never()).findById(any());
        verify(salonRepository, never()).findById(any());
    }

    // ── canManageMasterSchedule ────────────────────────────────────────────────

    @Test
    @DisplayName("canManageMasterSchedule returns true when independent master manages their own schedule")
    void should_returnTrue_when_independentMasterManagesOwnSchedule() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        User masterUser = mock(User.class);
        when(masterUser.getId()).thenReturn(actorId);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        when(master.getUser()).thenReturn(masterUser);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_INDEPENDENT_MASTER");

        boolean result = authorizationService.canManageMasterSchedule(auth, masterId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canManageMasterSchedule returns true when salon owner manages a salon master's schedule")
    void should_returnTrue_when_salonOwnerManagesSalonMasterSchedule() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageMasterSchedule(auth, masterId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canManageMasterSchedule returns false when actor has SALON_MASTER role")
    void should_returnFalse_when_salonMasterTriesToManageSchedule() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        boolean result = authorizationService.canManageMasterSchedule(auth, masterId);

        assertThat(result).isFalse();
        verify(masterRepository, never()).findByIdWithUserAndSalon(masterId);
    }

    @Test
    @DisplayName("canManageMasterSchedule returns false when salon master record has a null salon reference")
    void should_returnFalse_when_salonMasterHasNullSalon() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(null);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageMasterSchedule(auth, masterId);

        assertThat(result).isFalse();
    }

    // ── enforceCanManageSalon ──────────────────────────────────────────────────

    @Test
    @DisplayName("enforceCanManageSalon throws ForbiddenException when actor does not own the salon")
    void should_throwForbidden_when_enforceCanManageSalonCalledWithWrongActor() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        // Role is read from SecurityContext — no userRepository call for SALON_OWNER
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(false);

        assertThatThrownBy(() -> authorizationService.enforceCanManageSalon(actorId, salon))
                .isInstanceOf(ForbiddenException.class);

        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("enforceCanManageSalon does not throw when actor is the correct salon owner")
    void should_notThrow_when_enforceCanManageSalonCalledWithCorrectOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        // Role is read from SecurityContext — no userRepository call for SALON_OWNER
        SecurityContextHolder.getContext().setAuthentication(mockAuth(ownerId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, ownerId)).thenReturn(true);

        authorizationService.enforceCanManageSalon(ownerId, salon);

        verify(userRepository, never()).findById(any());
    }

    // ── enforceCanManageMaster ─────────────────────────────────────────────────

    @Test
    @DisplayName("enforceCanManageMaster throws ForbiddenException when actor does not own the independent master record")
    void should_throwForbidden_when_enforceCanManageMasterCalledWithWrongActorOnIndependentMaster() {
        UUID actorId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();

        User masterUser = mock(User.class);
        when(masterUser.getId()).thenReturn(masterUserId);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        when(master.getUser()).thenReturn(masterUser);

        assertThatThrownBy(() -> authorizationService.enforceCanManageMaster(actorId, master))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("enforceCanManageMaster throws ForbiddenException when actor does not own the salon that employs the master")
    void should_throwForbidden_when_enforceCanManageMasterCalledWithWrongActorOnSalonMaster() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);

        // Role is read from SecurityContext — no userRepository call for SALON_OWNER
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(false);

        assertThatThrownBy(() -> authorizationService.enforceCanManageMaster(actorId, master))
                .isInstanceOf(ForbiddenException.class);

        verify(userRepository, never()).findById(any());
    }

    // ── null-guard regression tests ────────────────────────────────────────────

    @Test
    @DisplayName("hasManagementAccess returns false when salonId is null")
    void should_returnFalse_when_salonIdIsNull() {
        UUID actorId = UUID.randomUUID();

        boolean result = authorizationService.hasManagementAccess(null, actorId);

        assertThat(result).isFalse();
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("hasManagementAccess returns false when SALON_ADMIN has a null salonId on their user record")
    void should_returnFalse_when_salonAdminHasNullSalonId() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        // Fix MEDIUM-7: AuthorizationService now calls findSalonIdById (projection) instead of
        // findById (full entity). When the SALON_ADMIN's salonId is null the projection returns
        // Optional.empty() — map(salonId::equals) short-circuits to false.
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.empty());

        // SALON_ADMIN role is read from SecurityContext; the userRepository.findSalonIdById call
        // still fires for SALON_ADMIN because the assigned salonId lives on the User record.
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_ADMIN"));

        boolean result = authorizationService.hasManagementAccess(salonId, actorId);

        assertThat(result).isFalse();
    }

    // ── hasManagementAccess (2-arg) — direct role-branch isolation ─────────────
    // The 2-arg public overload reads the role from SecurityContext, then delegates to the
    // 3-arg private overload. These tests pin each role branch directly (previously covered
    // only transitively through canManageSalon).

    @Test
    @DisplayName("hasManagementAccess returns true via SALON_OWNER fast-path (existsByIdAndOwnerId only — no findSalonIdById round-trip)")
    void should_returnTrue_when_salonOwnerFastPathHitsOwnershipQueryOnly() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);

        boolean result = authorizationService.hasManagementAccess(salonId, actorId);

        assertThat(result)
                .as("SALON_OWNER must be granted via the ownership query alone")
                .isTrue();
        verify(salonRepository).existsByIdAndOwnerId(salonId, actorId);
        // SALON_OWNER fast-path must NOT consult the user record for an assigned salonId.
        verify(userRepository, never()).findSalonIdById(any());
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("hasManagementAccess returns false for SALON_OWNER who does not own the salon (still no findSalonIdById round-trip)")
    void should_returnFalse_when_salonOwnerFastPathOwnershipQueryFails() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(false);

        boolean result = authorizationService.hasManagementAccess(salonId, actorId);

        assertThat(result)
                .as("a non-owning SALON_OWNER must be rejected by the ownership query")
                .isFalse();
        verify(userRepository, never()).findSalonIdById(any());
    }

    @Test
    @DisplayName("hasManagementAccess returns true for SALON_ADMIN whose assigned salonId matches (via findSalonIdById, not the ownership query)")
    void should_returnTrue_when_salonAdminAssignedSalonMatchesViaFindSalonIdById() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_ADMIN"));
        // SALON_ADMIN's assigned salonId lives on the User record — resolved via the projection.
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));

        boolean result = authorizationService.hasManagementAccess(salonId, actorId);

        assertThat(result)
                .as("SALON_ADMIN whose assigned salonId matches must be granted")
                .isTrue();
        verify(userRepository).findSalonIdById(actorId);
        // SALON_ADMIN must NOT use the owner-equality query — it is not a salon owner.
        verify(salonRepository, never()).existsByIdAndOwnerId(any(), any());
    }

    // ── masterBelongsToSalon ───────────────────────────────────────────────────

    @Test
    @DisplayName("masterBelongsToSalon returns true when master belongs to the salon")
    void should_returnTrue_when_masterBelongsToSalon() {
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(masterRepository.existsByIdAndSalonId(masterId, salonId)).thenReturn(true);

        boolean result = authorizationService.masterBelongsToSalon(masterId, salonId);

        assertThat(result).isTrue();
        verify(masterRepository).existsByIdAndSalonId(masterId, salonId);
    }

    @Test
    @DisplayName("masterBelongsToSalon returns false when master does not belong to the salon")
    void should_returnFalse_when_masterDoesNotBelongToSalon() {
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(masterRepository.existsByIdAndSalonId(masterId, salonId)).thenReturn(false);

        boolean result = authorizationService.masterBelongsToSalon(masterId, salonId);

        assertThat(result).isFalse();
        verify(masterRepository).existsByIdAndSalonId(masterId, salonId);
    }

    @Test
    @DisplayName("masterBelongsToSalon returns false immediately when masterId is null")
    void should_returnFalse_when_masterIdIsNull() {
        UUID salonId = UUID.randomUUID();

        boolean result = authorizationService.masterBelongsToSalon(null, salonId);

        assertThat(result).isFalse();
        verify(masterRepository, never()).existsByIdAndSalonId(any(), any());
    }

    // ── adminBelongsToSalon ────────────────────────────────────────────────────

    @Test
    @DisplayName("adminBelongsToSalon returns true when the user is a SALON_ADMIN of the salon")
    void should_returnTrue_when_adminBelongsToSalon() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(userRepository.existsByIdAndSalonIdAndRole(userId, salonId, Role.SALON_ADMIN)).thenReturn(true);

        boolean result = authorizationService.adminBelongsToSalon(userId, salonId);

        assertThat(result).isTrue();
        verify(userRepository).existsByIdAndSalonIdAndRole(userId, salonId, Role.SALON_ADMIN);
    }

    @Test
    @DisplayName("adminBelongsToSalon returns false when the user is not a SALON_ADMIN of the salon")
    void should_returnFalse_when_adminDoesNotBelongToSalon() {
        UUID userId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(userRepository.existsByIdAndSalonIdAndRole(userId, salonId, Role.SALON_ADMIN)).thenReturn(false);

        boolean result = authorizationService.adminBelongsToSalon(userId, salonId);

        assertThat(result).isFalse();
        verify(userRepository).existsByIdAndSalonIdAndRole(userId, salonId, Role.SALON_ADMIN);
    }

    @Test
    @DisplayName("adminBelongsToSalon returns false immediately when userId is null")
    void should_returnFalse_when_adminUserIdIsNull() {
        UUID salonId = UUID.randomUUID();

        boolean result = authorizationService.adminBelongsToSalon(null, salonId);

        assertThat(result).isFalse();
        verify(userRepository, never()).existsByIdAndSalonIdAndRole(any(), any(), any());
    }

    @Test
    @DisplayName("adminBelongsToSalon returns false immediately when salonId is null")
    void should_returnFalse_when_adminSalonIdIsNull() {
        UUID userId = UUID.randomUUID();

        boolean result = authorizationService.adminBelongsToSalon(userId, null);

        assertThat(result).isFalse();
        verify(userRepository, never()).existsByIdAndSalonIdAndRole(any(), any(), any());
    }

    // ── salonsShareOwner (Phase 21.3) ──────────────────────────────────────────

    @Test
    @DisplayName("salonsShareOwner returns true when both salons are owned by the same owner")
    void should_returnTrue_when_bothSalonsShareTheSameOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID sourceSalonId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();

        when(salonRepository.findOwnerIdById(sourceSalonId)).thenReturn(Optional.of(ownerId));
        when(salonRepository.existsByIdAndOwnerId(destSalonId, ownerId)).thenReturn(true);

        boolean result = authorizationService.salonsShareOwner(sourceSalonId, destSalonId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("salonsShareOwner returns false when the destination salon is owned by someone else")
    void should_returnFalse_when_destinationSalonIsOwnedByAnotherOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID sourceSalonId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();

        when(salonRepository.findOwnerIdById(sourceSalonId)).thenReturn(Optional.of(ownerId));
        when(salonRepository.existsByIdAndOwnerId(destSalonId, ownerId)).thenReturn(false);

        boolean result = authorizationService.salonsShareOwner(sourceSalonId, destSalonId);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("salonsShareOwner returns false when the source salon does not exist")
    void should_returnFalse_when_sourceSalonDoesNotExist() {
        UUID sourceSalonId = UUID.randomUUID();
        UUID destSalonId = UUID.randomUUID();

        when(salonRepository.findOwnerIdById(sourceSalonId)).thenReturn(Optional.empty());

        boolean result = authorizationService.salonsShareOwner(sourceSalonId, destSalonId);

        assertThat(result).isFalse();
        verify(salonRepository, never()).existsByIdAndOwnerId(any(), any());
    }

    @Test
    @DisplayName("salonsShareOwner returns false immediately when sourceSalonId is null")
    void should_returnFalse_when_sourceSalonIdIsNull() {
        UUID destSalonId = UUID.randomUUID();

        boolean result = authorizationService.salonsShareOwner(null, destSalonId);

        assertThat(result).isFalse();
        verify(salonRepository, never()).findOwnerIdById(any());
    }

    @Test
    @DisplayName("salonsShareOwner returns false immediately when destSalonId is null")
    void should_returnFalse_when_destSalonIdIsNull() {
        UUID sourceSalonId = UUID.randomUUID();

        boolean result = authorizationService.salonsShareOwner(sourceSalonId, null);

        assertThat(result).isFalse();
        verify(salonRepository, never()).findOwnerIdById(any());
    }

    // ── canManageServiceDefinition ─────────────────────────────────────────────

    @Test
    @DisplayName("canManageServiceDefinition returns true via SALON path when actor owns the salon")
    void should_returnTrue_when_actorOwnsSalonServiceDefinition() {
        UUID actorId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        when(serviceRepository.findOwnerUserId(serviceDefId)).thenReturn(Optional.of(actorId));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageServiceDefinition(auth, serviceDefId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canManageServiceDefinition returns true via INDEPENDENT_MASTER path when actor is the master's user")
    void should_returnTrue_when_actorIsIndependentMasterOwner() {
        UUID actorId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        when(serviceRepository.findOwnerUserId(serviceDefId)).thenReturn(Optional.of(actorId));

        Authentication auth = mockAuth(actorId, "ROLE_INDEPENDENT_MASTER");

        boolean result = authorizationService.canManageServiceDefinition(auth, serviceDefId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canManageServiceDefinition returns false when service definition does not exist")
    void should_returnFalse_when_serviceDefinitionDoesNotExist() {
        UUID actorId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        when(serviceRepository.findOwnerUserId(serviceDefId)).thenReturn(Optional.empty());

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageServiceDefinition(auth, serviceDefId);

        assertThat(result).isFalse();
        verify(salonRepository, never()).findById(any());
        verify(masterRepository, never()).findById(any());
    }

    @Test
    @DisplayName("canManageServiceDefinition returns false without DB hit when actor has ROLE_CLIENT (MEDIUM-1 role fast-path)")
    void should_returnFalse_withoutDbHit_when_clientCallsCanManageServiceDefinition() {
        UUID serviceDefId = UUID.randomUUID();
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_CLIENT");

        boolean result = authorizationService.canManageServiceDefinition(auth, serviceDefId);

        assertThat(result).isFalse();
        verify(serviceRepository, never()).findOwnerUserId(any());
    }

    @Test
    @DisplayName("canManageServiceDefinition returns false without DB hit when actor has ROLE_SALON_MASTER (MEDIUM-1 role fast-path)")
    void should_returnFalse_withoutDbHit_when_salonMasterCallsCanManageServiceDefinition() {
        UUID serviceDefId = UUID.randomUUID();
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_SALON_MASTER");

        boolean result = authorizationService.canManageServiceDefinition(auth, serviceDefId);

        assertThat(result).isFalse();
        verify(serviceRepository, never()).findOwnerUserId(any());
    }

    @Test
    @DisplayName("canManageServiceDefinition returns false without DB hit when actor has ROLE_SALON_ADMIN (MEDIUM-1 role fast-path)")
    void should_returnFalse_withoutDbHit_when_salonAdminCallsCanManageServiceDefinition() {
        UUID serviceDefId = UUID.randomUUID();
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_SALON_ADMIN");

        boolean result = authorizationService.canManageServiceDefinition(auth, serviceDefId);

        assertThat(result).isFalse();
        verify(serviceRepository, never()).findOwnerUserId(any());
    }

    // ── enforceCanManageServiceDefinition (B14 service-layer guard) ────────────

    @Test
    @DisplayName("enforceCanManageServiceDefinition does not throw when actor owns the service definition")
    void should_notThrow_when_actorOwnsServiceDefinition() {
        UUID actorId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        when(serviceRepository.findOwnerUserId(serviceDefId)).thenReturn(Optional.of(actorId));

        assertThatCode(() -> authorizationService.enforceCanManageServiceDefinition(actorId, serviceDefId))
                .as("owner of the service definition must pass the B14 guard")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanManageServiceDefinition throws ForbiddenException when actor is NOT the owner")
    void should_throwForbidden_when_actorIsNotServiceDefinitionOwner() {
        UUID actorId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        when(serviceRepository.findOwnerUserId(serviceDefId)).thenReturn(Optional.of(ownerId));

        assertThatThrownBy(() -> authorizationService.enforceCanManageServiceDefinition(actorId, serviceDefId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    @DisplayName("enforceCanManageServiceDefinition throws ForbiddenException when the service definition does not exist (no existence oracle)")
    void should_throwForbidden_when_serviceDefinitionDoesNotExistOnEnforce() {
        UUID actorId = UUID.randomUUID();
        UUID missing = UUID.randomUUID();

        when(serviceRepository.findOwnerUserId(missing)).thenReturn(Optional.empty());

        // Unknown id is treated as access-denied (403), consistent with canManageServiceDefinition —
        // never a distinct 404 that would leak existence (anti-bug §B/§D).
        assertThatThrownBy(() -> authorizationService.enforceCanManageServiceDefinition(actorId, missing))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied");
    }

    // ── canManageBooking ───────────────────────────────────────────────────────

    @Test
    @DisplayName("canManageBooking returns false immediately when actor has ROLE_SALON_MASTER without touching repository")
    void should_returnFalse_when_salonMasterTriesToManageBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        boolean result = authorizationService.canManageBooking(auth, bookingId);

        assertThat(result).isFalse();
        verify(bookingRepository, never()).findViewAccessById(any());
    }

    @Test
    @DisplayName("canManageBooking returns true when salon owner's ID matches the booking's salonOwnerUserId")
    void should_returnTrue_when_salonOwnerManagesOwnSalonsBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();

        // Salon booking: salonOwnerUserId is non-null and equals actorId
        when(bookingRepository.findViewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingViewAccess(UUID.randomUUID(), masterUserId, actorId)));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageBooking(auth, bookingId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canManageBooking returns false when owner B tries to manage a booking from owner A's salon")
    void should_returnFalse_when_ownerBTriesToManageOwnerASalonsBooking() {
        UUID ownerAId = UUID.randomUUID();
        UUID ownerBId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();

        // Salon booking: salonOwnerUserId is ownerA — ownerB must be rejected
        when(bookingRepository.findViewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingViewAccess(UUID.randomUUID(), masterUserId, ownerAId)));

        Authentication auth = mockAuth(ownerBId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageBooking(auth, bookingId);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("canManageBooking returns true when independent master manages their own booking")
    void should_returnTrue_when_independentMasterManagesOwnBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        // salonOwnerUserId is null — this is an independent master booking
        when(bookingRepository.findViewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingViewAccess(UUID.randomUUID(), actorId, null)));

        Authentication auth = mockAuth(actorId, "ROLE_INDEPENDENT_MASTER");

        assertThat(authorizationService.canManageBooking(auth, bookingId)).isTrue();
    }

    @Test
    @DisplayName("canManageBooking returns false when independent master tries to manage another master's booking")
    void should_returnFalse_when_independentMasterManagesAnotherMastersBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        // salonOwnerUserId is null — independent master booking, but masterUserId is a different master
        when(bookingRepository.findViewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingViewAccess(UUID.randomUUID(), UUID.randomUUID(), null)));

        Authentication auth = mockAuth(actorId, "ROLE_INDEPENDENT_MASTER");

        assertThat(authorizationService.canManageBooking(auth, bookingId)).isFalse();
    }

    // ── enforceCanCompleteBooking (Phase 18.4 — admits SALON_ADMIN) ────────────

    /** Builds a Booking whose master is a salon master bound to the given salon. */
    private Booking salonBooking(UUID salonId) {
        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);
        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);
        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);
        return booking;
    }

    /** Builds a Booking whose master is an independent master owned by masterUserId. */
    private Booking independentBooking(UUID masterUserId) {
        User masterUser = mock(User.class);
        when(masterUser.getId()).thenReturn(masterUserId);
        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        when(master.getUser()).thenReturn(masterUser);
        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);
        return booking;
    }

    @Test
    @DisplayName("enforceCanCompleteBooking does not throw when the salon owner completes a salon booking")
    void should_notThrow_when_salonOwnerCompletesSalonBooking() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Booking booking = salonBooking(salonId);

        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);

        assertThatCode(() -> authorizationService.enforceCanCompleteBooking(actorId, booking))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanCompleteBooking does not throw when an assigned SALON_ADMIN completes a salon booking (new capability)")
    void should_notThrow_when_assignedSalonAdminCompletesSalonBooking() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Booking booking = salonBooking(salonId);

        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_ADMIN"));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));

        assertThatCode(() -> authorizationService.enforceCanCompleteBooking(actorId, booking))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanCompleteBooking does not throw when an independent master completes their own booking")
    void should_notThrow_when_independentMasterCompletesOwnBooking() {
        UUID actorId = UUID.randomUUID();
        Booking booking = independentBooking(actorId);

        assertThatCode(() -> authorizationService.enforceCanCompleteBooking(actorId, booking))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanCompleteBooking throws ForbiddenException when a SALON_MASTER tries to complete a salon booking")
    void should_throwForbidden_when_salonMasterCompletesSalonBooking() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Booking booking = salonBooking(salonId);

        // SALON_MASTER is neither owner nor assigned admin → hasManagementAccess returns false.
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_MASTER"));

        assertThatThrownBy(() -> authorizationService.enforceCanCompleteBooking(actorId, booking))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("enforceCanCompleteBooking throws ForbiddenException when a CLIENT tries to complete a salon booking")
    void should_throwForbidden_when_clientCompletesSalonBooking() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Booking booking = salonBooking(salonId);

        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_CLIENT"));

        assertThatThrownBy(() -> authorizationService.enforceCanCompleteBooking(actorId, booking))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("enforceCanCompleteBooking throws ForbiddenException when an independent master completes another master's booking")
    void should_throwForbidden_when_independentMasterCompletesAnotherMastersBooking() {
        UUID actorId = UUID.randomUUID();
        UUID otherMasterUserId = UUID.randomUUID();
        Booking booking = independentBooking(otherMasterUserId);

        assertThatThrownBy(() -> authorizationService.enforceCanCompleteBooking(actorId, booking))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── enforceCanRescheduleBooking (Phase 27.2 — provider reschedule) ─────────

    @Test
    @DisplayName("enforceCanRescheduleBooking does not throw when the salon owner reschedules a salon booking")
    void should_notThrow_when_salonOwnerReschedulesSalonBooking() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Booking booking = salonBooking(salonId);

        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);

        assertThatCode(() -> authorizationService.enforceCanRescheduleBooking(actorId, booking))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanRescheduleBooking does not throw when an assigned SALON_ADMIN reschedules a salon booking")
    void should_notThrow_when_assignedSalonAdminReschedulesSalonBooking() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Booking booking = salonBooking(salonId);

        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_ADMIN"));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));

        assertThatCode(() -> authorizationService.enforceCanRescheduleBooking(actorId, booking))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanRescheduleBooking does not throw when an independent master reschedules their own booking")
    void should_notThrow_when_independentMasterReschedulesOwnBooking() {
        UUID actorId = UUID.randomUUID();
        Booking booking = independentBooking(actorId);

        assertThatCode(() -> authorizationService.enforceCanRescheduleBooking(actorId, booking))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanRescheduleBooking throws ForbiddenException when a SALON_MASTER tries to reschedule a salon booking")
    void should_throwForbidden_when_salonMasterReschedulesSalonBooking() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Booking booking = salonBooking(salonId);

        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_MASTER"));

        assertThatThrownBy(() -> authorizationService.enforceCanRescheduleBooking(actorId, booking))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("enforceCanRescheduleBooking throws ForbiddenException when an independent master reschedules another master's booking")
    void should_throwForbidden_when_independentMasterReschedulesAnotherMastersBooking() {
        UUID actorId = UUID.randomUUID();
        UUID otherMasterUserId = UUID.randomUUID();
        Booking booking = independentBooking(otherMasterUserId);

        assertThatThrownBy(() -> authorizationService.enforceCanRescheduleBooking(actorId, booking))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── enforceCanReviewClient (Phase 27.5 — master reviews client) ────────────

    @Test
    @DisplayName("enforceCanReviewClient does not throw when the salon owner reviews the client of a salon booking")
    void should_notThrow_when_salonOwnerReviewsClientOfSalonBooking() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Booking booking = salonBooking(salonId);

        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);

        assertThatCode(() -> authorizationService.enforceCanReviewClient(actorId, booking))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanReviewClient does not throw when an independent master reviews the client of their own booking")
    void should_notThrow_when_independentMasterReviewsOwnBookingClient() {
        UUID actorId = UUID.randomUUID();
        Booking booking = independentBooking(actorId);

        assertThatCode(() -> authorizationService.enforceCanReviewClient(actorId, booking))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanReviewClient throws ForbiddenException when a SALON_MASTER tries to review a salon booking's client")
    void should_throwForbidden_when_salonMasterReviewsSalonBookingClient() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Booking booking = salonBooking(salonId);

        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_MASTER"));

        assertThatThrownBy(() -> authorizationService.enforceCanReviewClient(actorId, booking))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("enforceCanReviewClient throws ForbiddenException when an independent master reviews another master's booking client")
    void should_throwForbidden_when_independentMasterReviewsAnotherMastersBookingClient() {
        UUID actorId = UUID.randomUUID();
        UUID otherMasterUserId = UUID.randomUUID();
        Booking booking = independentBooking(otherMasterUserId);

        assertThatThrownBy(() -> authorizationService.enforceCanReviewClient(actorId, booking))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── enforceCanManageAppointment (LOW finding fix — no longer trusts the ─────
    // ── single-master invariant; checks EVERY chained item's authority) ────────

    @Test
    @DisplayName("enforceCanManageAppointment does not throw when the actor has provider authority "
            + "over every chained item")
    void should_notThrow_when_actorHasAuthorityOverEveryItem_whenEnforcingCanManageAppointment() {
        UUID actorId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterUserId1 = UUID.randomUUID();
        UUID masterUserId2 = UUID.randomUUID();

        when(bookingRepository.findAllCompletionAccessByAppointmentId(appointmentId))
                .thenReturn(List.of(
                        new BookingCompletionAccess(masterUserId1, salonId),
                        new BookingCompletionAccess(masterUserId2, salonId)));
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);

        assertThatCode(() -> authorizationService.enforceCanManageAppointment(actorId, appointmentId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanManageAppointment throws ForbiddenException when the appointment has no "
            + "items (fail-closed, no existence oracle)")
    void should_throwForbidden_when_appointmentHasNoItems_whenEnforcingCanManageAppointment() {
        UUID actorId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();

        when(bookingRepository.findAllCompletionAccessByAppointmentId(appointmentId)).thenReturn(List.of());

        assertThatThrownBy(() -> authorizationService.enforceCanManageAppointment(actorId, appointmentId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("enforceCanManageAppointment throws ForbiddenException when the appointment's items "
            + "resolve to DIFFERENT masters and the actor has authority over only one of them — "
            + "regression for the LOW finding where the old Limit.of(1) read authorized the whole "
            + "visit off a single arbitrary item")
    void should_throwForbidden_when_itemsResolveToDifferentMastersAndActorAuthorizedForOnlyOne() {
        UUID actorId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID salonId1 = UUID.randomUUID();
        UUID salonId2 = UUID.randomUUID();
        UUID masterUserId1 = UUID.randomUUID();
        UUID masterUserId2 = UUID.randomUUID();
        BookingCompletionAccess authorizedItem = new BookingCompletionAccess(masterUserId1, salonId1);
        BookingCompletionAccess foreignItem = new BookingCompletionAccess(masterUserId2, salonId2);

        // The old Limit.of(1) query (deterministically ordered by b.id, so its single row would
        // have been this same first item) has been deleted from BookingRepository entirely — it
        // would have driven the decision and the actor (authorized for item 1 only) would have
        // been WRONGLY granted access to the whole visit. That is the exact regression this test
        // guards against, now that enforceCanManageAppointment only ever reads the all-rows form.
        when(bookingRepository.findAllCompletionAccessByAppointmentId(appointmentId))
                .thenReturn(List.of(authorizedItem, foreignItem));

        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId1, actorId)).thenReturn(true);
        when(salonRepository.existsByIdAndOwnerId(salonId2, actorId)).thenReturn(false);

        assertThatThrownBy(() -> authorizationService.enforceCanManageAppointment(actorId, appointmentId))
                .isInstanceOf(ForbiddenException.class);
    }

    // ── canRescheduleAppointment (Phase 27.2 SpEL predicate, visit-level — no ──
    // ── longer trusts the single-master invariant; checks EVERY chained item) ──

    @Test
    @DisplayName("canRescheduleAppointment returns false without DB hit when actor has ROLE_SALON_MASTER")
    void should_returnFalseWithoutDbHit_when_salonMasterCallsCanRescheduleAppointment() {
        UUID appointmentId = UUID.randomUUID();
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_SALON_MASTER");

        boolean result = authorizationService.canRescheduleAppointment(auth, appointmentId);

        assertThat(result).isFalse();
        verify(bookingRepository, never()).findAllCompletionAccessByAppointmentId(any());
    }

    @Test
    @DisplayName("canRescheduleAppointment returns true when the actor has provider authority "
            + "over every chained item")
    void should_returnTrue_when_actorHasAuthorityOverEveryItem_whenCallingCanRescheduleAppointment() {
        UUID actorId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterUserId1 = UUID.randomUUID();
        UUID masterUserId2 = UUID.randomUUID();

        when(bookingRepository.findAllCompletionAccessByAppointmentId(appointmentId))
                .thenReturn(List.of(
                        new BookingCompletionAccess(masterUserId1, salonId),
                        new BookingCompletionAccess(masterUserId2, salonId)));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canRescheduleAppointment(auth, appointmentId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canRescheduleAppointment returns false when the appointment has no items "
            + "(fail-closed, no existence oracle)")
    void should_returnFalse_when_appointmentHasNoItems_whenCallingCanRescheduleAppointment() {
        UUID appointmentId = UUID.randomUUID();

        when(bookingRepository.findAllCompletionAccessByAppointmentId(appointmentId)).thenReturn(List.of());

        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_SALON_OWNER");

        boolean result = authorizationService.canRescheduleAppointment(auth, appointmentId);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("canRescheduleAppointment returns false when the appointment's items resolve to "
            + "DIFFERENT masters and the actor has authority over only one of them — regression for "
            + "the LOW finding where the old Limit.of(1) read authorized the whole visit off a "
            + "single arbitrary item")
    void should_returnFalse_when_itemsResolveToDifferentMastersAndActorAuthorizedForOnlyOne_whenCallingCanRescheduleAppointment() {
        UUID actorId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID salonId1 = UUID.randomUUID();
        UUID salonId2 = UUID.randomUUID();
        UUID masterUserId1 = UUID.randomUUID();
        UUID masterUserId2 = UUID.randomUUID();
        BookingCompletionAccess authorizedItem = new BookingCompletionAccess(masterUserId1, salonId1);
        BookingCompletionAccess foreignItem = new BookingCompletionAccess(masterUserId2, salonId2);

        // The old Limit.of(1) query (deterministically ordered by b.id, so its single row would
        // have been this same first item) has been deleted from BookingRepository entirely — it
        // would have driven the decision and the actor (authorized for item 1 only) would have
        // been WRONGLY granted access to reschedule the whole visit. That is the exact regression
        // this test guards against, now that canRescheduleAppointment only ever reads the
        // all-rows form and requires authority over every item.
        when(bookingRepository.findAllCompletionAccessByAppointmentId(appointmentId))
                .thenReturn(List.of(authorizedItem, foreignItem));
        when(salonRepository.existsByIdAndOwnerId(salonId1, actorId)).thenReturn(true);
        when(salonRepository.existsByIdAndOwnerId(salonId2, actorId)).thenReturn(false);

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canRescheduleAppointment(auth, appointmentId);

        assertThat(result).isFalse();
    }

    // ── canCompleteBooking (Phase 18.4 SpEL predicate) ─────────────────────────

    @Test
    @DisplayName("canCompleteBooking returns false without DB hit when actor has ROLE_SALON_MASTER")
    void should_returnFalseWithoutDbHit_when_salonMasterCallsCanCompleteBooking() {
        UUID bookingId = UUID.randomUUID();
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_SALON_MASTER");

        boolean result = authorizationService.canCompleteBooking(auth, bookingId);

        assertThat(result).isFalse();
        verify(bookingRepository, never()).findCompletionAccessById(any());
    }

    @Test
    @DisplayName("canCompleteBooking returns false without DB hit when actor has ROLE_CLIENT")
    void should_returnFalseWithoutDbHit_when_clientCallsCanCompleteBooking() {
        UUID bookingId = UUID.randomUUID();
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_CLIENT");

        boolean result = authorizationService.canCompleteBooking(auth, bookingId);

        assertThat(result).isFalse();
        verify(bookingRepository, never()).findCompletionAccessById(any());
    }

    @Test
    @DisplayName("canCompleteBooking returns true when an assigned SALON_ADMIN completes a salon booking")
    void should_returnTrue_when_assignedSalonAdminCallsCanCompleteBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();

        when(bookingRepository.findCompletionAccessById(bookingId))
                .thenReturn(Optional.of(new BookingCompletionAccess(masterUserId, salonId)));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_ADMIN");

        boolean result = authorizationService.canCompleteBooking(auth, bookingId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canCompleteBooking returns true when an independent master completes their own booking (salonId null)")
    void should_returnTrue_when_independentMasterCallsCanCompleteBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        when(bookingRepository.findCompletionAccessById(bookingId))
                .thenReturn(Optional.of(new BookingCompletionAccess(actorId, null)));

        Authentication auth = mockAuth(actorId, "ROLE_INDEPENDENT_MASTER");

        boolean result = authorizationService.canCompleteBooking(auth, bookingId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canCompleteBooking returns false when the booking does not exist (no existence oracle)")
    void should_returnFalse_when_completionBookingMissing() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        when(bookingRepository.findCompletionAccessById(bookingId)).thenReturn(Optional.empty());

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canCompleteBooking(auth, bookingId);

        assertThat(result).isFalse();
    }

    // ── canRescheduleBooking (Phase 27.2 SpEL predicate) ────────────────────────

    @Test
    @DisplayName("canRescheduleBooking returns false without DB hit when actor has ROLE_SALON_MASTER")
    void should_returnFalseWithoutDbHit_when_salonMasterCallsCanRescheduleBooking() {
        UUID bookingId = UUID.randomUUID();
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_SALON_MASTER");

        boolean result = authorizationService.canRescheduleBooking(auth, bookingId);

        assertThat(result).isFalse();
        verify(bookingRepository, never()).findCompletionAccessById(any());
    }

    @Test
    @DisplayName("canRescheduleBooking returns false without DB hit when actor has ROLE_CLIENT "
            + "(defensive only — the client arm of the union @PreAuthorize never reaches this method)")
    void should_returnFalseWithoutDbHit_when_clientCallsCanRescheduleBooking() {
        UUID bookingId = UUID.randomUUID();
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_CLIENT");

        boolean result = authorizationService.canRescheduleBooking(auth, bookingId);

        assertThat(result).isFalse();
        verify(bookingRepository, never()).findCompletionAccessById(any());
    }

    @Test
    @DisplayName("canRescheduleBooking returns true when an assigned SALON_ADMIN reschedules a salon booking")
    void should_returnTrue_when_assignedSalonAdminCallsCanRescheduleBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();

        when(bookingRepository.findCompletionAccessById(bookingId))
                .thenReturn(Optional.of(new BookingCompletionAccess(masterUserId, salonId)));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_ADMIN");

        boolean result = authorizationService.canRescheduleBooking(auth, bookingId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canRescheduleBooking returns true when an independent master reschedules their own booking (salonId null)")
    void should_returnTrue_when_independentMasterCallsCanRescheduleBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        when(bookingRepository.findCompletionAccessById(bookingId))
                .thenReturn(Optional.of(new BookingCompletionAccess(actorId, null)));

        Authentication auth = mockAuth(actorId, "ROLE_INDEPENDENT_MASTER");

        boolean result = authorizationService.canRescheduleBooking(auth, bookingId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canRescheduleBooking returns false when the booking does not exist (no existence oracle)")
    void should_returnFalse_when_rescheduleBookingMissing() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        when(bookingRepository.findCompletionAccessById(bookingId)).thenReturn(Optional.empty());

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canRescheduleBooking(auth, bookingId);

        assertThat(result).isFalse();
    }

    // ── canReviewClient (Phase 27.5 SpEL predicate) ─────────────────────────────

    @Test
    @DisplayName("canReviewClient returns false without DB hit when actor has ROLE_SALON_MASTER")
    void should_returnFalseWithoutDbHit_when_salonMasterCallsCanReviewClient() {
        UUID bookingId = UUID.randomUUID();
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_SALON_MASTER");

        boolean result = authorizationService.canReviewClient(auth, bookingId);

        assertThat(result).isFalse();
        verify(bookingRepository, never()).findCompletionAccessById(any());
    }

    @Test
    @DisplayName("canReviewClient returns false without DB hit when actor has ROLE_CLIENT")
    void should_returnFalseWithoutDbHit_when_clientCallsCanReviewClient() {
        UUID bookingId = UUID.randomUUID();
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_CLIENT");

        boolean result = authorizationService.canReviewClient(auth, bookingId);

        assertThat(result).isFalse();
        verify(bookingRepository, never()).findCompletionAccessById(any());
    }

    @Test
    @DisplayName("canReviewClient returns true when an assigned SALON_ADMIN reviews a salon booking's client")
    void should_returnTrue_when_assignedSalonAdminCallsCanReviewClient() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();

        when(bookingRepository.findCompletionAccessById(bookingId))
                .thenReturn(Optional.of(new BookingCompletionAccess(masterUserId, salonId)));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_ADMIN");

        boolean result = authorizationService.canReviewClient(auth, bookingId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canReviewClient returns true when an independent master reviews their own booking's client (salonId null)")
    void should_returnTrue_when_independentMasterCallsCanReviewClient() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        when(bookingRepository.findCompletionAccessById(bookingId))
                .thenReturn(Optional.of(new BookingCompletionAccess(actorId, null)));

        Authentication auth = mockAuth(actorId, "ROLE_INDEPENDENT_MASTER");

        boolean result = authorizationService.canReviewClient(auth, bookingId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canReviewClient returns false when the booking does not exist (no existence oracle)")
    void should_returnFalse_when_reviewClientBookingMissing() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        when(bookingRepository.findCompletionAccessById(bookingId)).thenReturn(Optional.empty());

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canReviewClient(auth, bookingId);

        assertThat(result).isFalse();
    }

    // ── canViewBooking ─────────────────────────────────────────────────────────
    // After Finding 2 fix: canViewBooking calls findViewAccessById(bookingId) (no actorId arg)
    // and derives the actor's role from the Authentication object (SecurityContext).

    @Test
    @DisplayName("canViewBooking returns true when client views their own booking")
    void should_returnTrue_when_clientViewsOwnBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID salonOwnerUserId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();

        // Client is the booking owner — clientUserId matches actorId
        when(bookingRepository.findViewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingViewAccess(actorId, masterUserId, salonOwnerUserId)));

        Authentication auth = mockAuth(actorId, "ROLE_CLIENT");

        boolean result = authorizationService.canViewBooking(auth, bookingId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canViewBooking returns false when client B tries to view client A's booking")
    void should_returnFalse_when_clientBViewsClientABooking() {
        UUID clientAId = UUID.randomUUID();
        UUID clientBId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();
        UUID salonOwnerUserId = UUID.randomUUID();

        // Booking belongs to clientA, actor is clientB
        when(bookingRepository.findViewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingViewAccess(clientAId, masterUserId, salonOwnerUserId)));

        Authentication auth = mockAuth(clientBId, "ROLE_CLIENT");

        boolean result = authorizationService.canViewBooking(auth, bookingId);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("canViewBooking returns true when salon master views a booking assigned to them")
    void should_returnTrue_when_salonMasterViewsOwnBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID clientUserId = UUID.randomUUID();
        UUID salonOwnerUserId = UUID.randomUUID();

        // masterUserId matches actorId — the master is viewing their own booking
        when(bookingRepository.findViewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingViewAccess(clientUserId, actorId, salonOwnerUserId)));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        boolean result = authorizationService.canViewBooking(auth, bookingId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canViewBooking returns false when salon master views another master's booking")
    void should_returnFalse_when_salonMasterViewsAnotherMastersBooking() {
        UUID actorId = UUID.randomUUID();
        UUID otherMasterUserId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID clientUserId = UUID.randomUUID();
        UUID salonOwnerUserId = UUID.randomUUID();

        // masterUserId is otherMasterUserId — actor is a different salon master
        when(bookingRepository.findViewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingViewAccess(clientUserId, otherMasterUserId, salonOwnerUserId)));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        boolean result = authorizationService.canViewBooking(auth, bookingId);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("canViewBooking throws ForbiddenException when authentication carries an unrecognised role authority")
    void should_throwForbidden_when_authHasUnknownAuthority() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        // roleFromAuthentication throws ForbiddenException before the repository is ever
        // consulted, so no booking stub is needed.
        Authentication auth = new UsernamePasswordAuthenticationToken(
                "user@example.com",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_UNKNOWN")));
        ((UsernamePasswordAuthenticationToken) auth).setDetails(actorId);

        assertThatThrownBy(() -> authorizationService.canViewBooking(auth, bookingId))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("canViewBooking returns false when a SALON_ADMIN tries to view a booking (no owner/master/client match → fall-through)")
    void should_returnFalse_when_salonAdminViewsBooking() {
        UUID adminId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID clientUserId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();
        UUID salonOwnerUserId = UUID.randomUUID();

        // SALON_ADMIN's id matches none of the ownership fields. SALON_ADMIN is neither
        // CLIENT nor SALON_MASTER, so canViewBooking reaches the final `return false`.
        when(bookingRepository.findViewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingViewAccess(clientUserId, masterUserId, salonOwnerUserId)));

        Authentication auth = mockAuth(adminId, "ROLE_SALON_ADMIN");

        boolean result = authorizationService.canViewBooking(auth, bookingId);

        assertThat(result)
                .as("SALON_ADMIN must fall through to false on canViewBooking — no view path for individual bookings")
                .isFalse();
    }

    @Test
    @DisplayName("canViewBooking returns true when an INDEPENDENT_MASTER views their own booking (masterUserId == actorId, salonOwnerUserId null)")
    void should_returnTrue_when_independentMasterViewsOwnBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID clientUserId = UUID.randomUUID();

        // Independent master booking: salonOwnerUserId is null, masterUserId == actorId.
        // The salon-owner branch is skipped (null guard); the masterUserId branch fires
        // because the actor's role (INDEPENDENT_MASTER) is not SALON_MASTER.
        when(bookingRepository.findViewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingViewAccess(clientUserId, actorId, null)));

        Authentication auth = mockAuth(actorId, "ROLE_INDEPENDENT_MASTER");

        boolean result = authorizationService.canViewBooking(auth, bookingId);

        assertThat(result)
                .as("INDEPENDENT_MASTER must be able to view their own booking via the masterUserId branch")
                .isTrue();
    }

    // ── enforceCanViewBooking ──────────────────────────────────────────────────
    // After Finding 3 fix: enforceCanViewBooking reads role from SecurityContextHolder
    // instead of calling userRepository.findById — the SecurityContext must be populated.

    @Test
    @DisplayName("enforceCanViewBooking throws ForbiddenException when wrong client tries to view another client's booking")
    void should_throwForbidden_when_enforceCanViewBookingCalledWithWrongClient() {
        UUID clientAId = UUID.randomUUID();
        UUID clientBId = UUID.randomUUID();

        User clientA = mock(User.class);
        when(clientA.getId()).thenReturn(clientAId);

        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(UUID.randomUUID());

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);
        when(booking.getClient()).thenReturn(clientA);

        // Populate SecurityContext with CLIENT role for clientB
        SecurityContextHolder.getContext().setAuthentication(mockAuth(clientBId, "ROLE_CLIENT"));

        assertThatThrownBy(() -> authorizationService.enforceCanViewBooking(clientBId, booking))
                .isInstanceOf(ForbiddenException.class);
    }

    /**
     * Phase-242 audit, finding 1 (MEDIUM) — the owning client is admitted WITHOUT the salon walk.
     *
     * <p>{@code isAuthorizedToManageBooking} reads {@code master.getSalon().getOwner()}, and
     * {@code getOwner()} is a PROPERTY read that INITIALISES the {@code Salon} proxy. Since phase
     * 242 stopped fetch-joining {@code m.salon}, running it ahead of the role branches charged the
     * owning CLIENT — the highest-volume viewer of {@code GET /bookings/{id}} and
     * {@code GET /appointments/{id}} — a standalone {@code SELECT ... FROM salons} for the
     * master's LIVE salon on any booking whose master has since rotated. The client fast path now
     * runs first.
     *
     * <p>The {@code verifyNoInteractions(salon)} below is the gate, not decoration: the salon and
     * master mocks are wired {@code lenient()} precisely so a regression that reinstates the
     * manage-check-first ordering finds them ready to answer, and is caught by the verification
     * rather than by an unrelated NPE. Deleting them would make this test unable to notice.
     */
    @Test
    @DisplayName("enforceCanViewBooking does not throw when correct client views their own booking, "
            + "and settles it without touching master/salon at all — no proxy-initialising "
            + "getSalon().getOwner() walk on the highest-volume viewer path")
    void should_notThrow_when_enforceCanViewBookingCalledWithCorrectClient() {
        UUID actorId = UUID.randomUUID();

        User client = mock(User.class);
        when(client.getId()).thenReturn(actorId);

        User salonOwner = mock(User.class);
        lenient().when(salonOwner.getId()).thenReturn(UUID.randomUUID());

        Salon salon = mock(Salon.class);
        lenient().when(salon.getOwner()).thenReturn(salonOwner);

        Master master = mock(Master.class);
        lenient().when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        lenient().when(master.getSalon()).thenReturn(salon);

        Booking booking = mock(Booking.class);
        lenient().when(booking.getMaster()).thenReturn(master);
        when(booking.getClient()).thenReturn(client);

        // Populate SecurityContext with CLIENT role for the correct client
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_CLIENT"));

        assertThatCode(() -> authorizationService.enforceCanViewBooking(actorId, booking))
                .doesNotThrowAnyException();

        verifyNoInteractions(salon, salonOwner);
        verify(booking, never()).getMaster();
    }

    /**
     * Phase-242 QA audit, finding 2 — the client fast path's ROLE conjunct is load-bearing on its
     * own, and nothing pinned it.
     *
     * <p>The audit-fix batch hoisted the client probe above
     * {@code isAuthorizedToManageBooking} and rests its "reordering cannot change any
     * authorization decision" argument entirely on the conjunct that survives:
     * <em>"a viewer who happens to sit in {@code bookings.client_id} but does not carry
     * {@code ROLE_CLIENT} is still not admitted by this branch"</em>. That claim had no test.
     * Deleting {@code && roleFromCurrentAuthentication() == Role.CLIENT} left the whole
     * {@code com.beautica.common.security} scope green, silently degrading a role-gated probe into
     * a bare id comparison — i.e. turning {@code bookings.client_id} into a standalone capability
     * that no longer cares what the presented token says.
     *
     * <p>The fixture is an ordinary account shape, not a contrived one: a provider who also books
     * services as a customer has their own user id sitting in {@code bookings.client_id} on those
     * rows, while their JWT carries {@code ROLE_SALON_MASTER}. The established contract (unchanged
     * by phase 242 — the pre-reorder code gated the same branch on {@code actorRole == CLIENT}) is
     * that such an actor is NOT admitted here and must earn access through the manage /
     * {@code SALON_MASTER} predicates below, which in this fixture also deny them.
     */
    @Test
    @DisplayName("enforceCanViewBooking throws ForbiddenException when the actor's id sits in "
            + "bookings.client_id but their token carries ROLE_SALON_MASTER — the client fast path "
            + "admits on id AND role, never on the id match alone")
    void should_throwForbidden_when_actorIsTheBookingsClientButAuthenticatedAsSalonMaster() {
        UUID actorId = UUID.randomUUID();

        // The actor IS bookings.client_id — the id half of the fast path matches.
        User client = mock(User.class);
        when(client.getId()).thenReturn(actorId);

        // ...but the booking belongs to a salon they neither own nor are the assigned master of,
        // so neither predicate below the fast path can rescue them either.
        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(UUID.randomUUID());

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        User assignedMasterUser = mock(User.class);
        when(assignedMasterUser.getId()).thenReturn(UUID.randomUUID());

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);
        when(master.getUser()).thenReturn(assignedMasterUser);

        Booking booking = mock(Booking.class);
        when(booking.getClient()).thenReturn(client);
        when(booking.getMaster()).thenReturn(master);

        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_MASTER"));

        assertThatThrownBy(() -> authorizationService.enforceCanViewBooking(actorId, booking))
                .as("the client branch must not admit on the id match alone — dropping its role "
                        + "conjunct makes bookings.client_id a capability in its own right, "
                        + "independent of the role the presented token actually carries")
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");
    }

    @Test
    @DisplayName("enforceCanViewBooking throws ForbiddenException (not a 500 NPE) when a CLIENT views a "
            + "guest (LINK / null-client) booking — regression for the missing null guard on "
            + "booking.getClient().getId() at enforceCanViewBooking L482")
    void should_return403NotCrash_when_clientViewsGuestBooking() {
        UUID actorId = UUID.randomUUID();

        // Guest (LINK) booking: no client account (V89 chk_bookings_guest_fields), so
        // booking.getClient() is null. The master is salon-bound so isAuthorizedToManageBooking
        // (checked first) resolves via the salon-owner id, which never matches this CLIENT actor,
        // and falls through to the actorRole == CLIENT branch — the one that dereferences
        // getClient().getId() unconditionally before the fix, NPEing into an unhandled 500
        // instead of the correct "not this actor's booking" 403.
        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(UUID.randomUUID());

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);

        Booking guestBooking = mock(Booking.class);
        when(guestBooking.getMaster()).thenReturn(master);
        when(guestBooking.getClient()).thenReturn(null);

        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_CLIENT"));

        assertThatThrownBy(() -> authorizationService.enforceCanViewBooking(actorId, guestBooking))
                .as("a null-client (guest) booking viewed by a CLIENT must yield 403, never an "
                        + "unhandled NullPointerException/500")
                .isInstanceOf(ForbiddenException.class);
    }

    // Cross-master denial on the ENFORCE twin. GET /bookings/{id} calls
    // enforceCanViewBooking(actorUserId, booking) — NOT the canViewBooking SpEL predicate tested
    // above. The two are independently maintained and nothing in the type system keeps them in
    // step, so the cross-master cases must be pinned on BOTH. The stakes rose with the booking
    // detail enrichment: this response carries a THIRD party's name, guest name and arrival
    // address, so drift here leaks other people's PII, not the caller's own.

    @Test
    @DisplayName("enforceCanViewBooking throws ForbiddenException when an INDEPENDENT_MASTER views "
            + "another independent master's booking — the entity-path twin of "
            + "canViewBooking's cross-master denial, exercising the "
            + "isAuthorizedToManageBooking INDEPENDENT_MASTER id-equality branch")
    void should_throwForbidden_when_independentMasterViewsAnotherMastersBooking() {
        UUID actorMasterUserId = UUID.randomUUID();
        UUID otherMasterUserId = UUID.randomUUID();

        User otherMasterUser = mock(User.class);
        when(otherMasterUser.getId()).thenReturn(otherMasterUserId);

        // Independent-master booking: no salon at all, so authority rests entirely on the
        // master-user id equality inside isAuthorizedToManageBooking. INDEPENDENT_MASTER is
        // neither CLIENT nor SALON_MASTER, so there is no second branch to fall through to.
        Master otherMaster = mock(Master.class);
        when(otherMaster.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        when(otherMaster.getUser()).thenReturn(otherMasterUser);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(otherMaster);

        SecurityContextHolder.getContext()
                .setAuthentication(mockAuth(actorMasterUserId, "ROLE_INDEPENDENT_MASTER"));

        assertThatThrownBy(() -> authorizationService.enforceCanViewBooking(actorMasterUserId, booking))
                .as("an INDEPENDENT_MASTER must never read a booking belonging to a different "
                        + "independent master — that response carries the other master's client "
                        + "name and arrival address")
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");
    }

    @Test
    @DisplayName("enforceCanViewBooking throws ForbiddenException when a SALON_MASTER views a "
            + "booking belonging to a master at a different salon — neither the salon-owner "
            + "branch nor the SALON_MASTER own-booking branch may admit them, and no DB "
            + "round-trip is made")
    void should_throwForbidden_when_salonMasterViewsAnotherSalonsBooking() {
        UUID actorMasterUserId = UUID.randomUUID();
        UUID otherMasterUserId = UUID.randomUUID();
        UUID otherSalonOwnerId = UUID.randomUUID();

        User otherSalonOwner = mock(User.class);
        when(otherSalonOwner.getId()).thenReturn(otherSalonOwnerId);

        Salon otherSalon = mock(Salon.class);
        when(otherSalon.getOwner()).thenReturn(otherSalonOwner);

        User otherMasterUser = mock(User.class);
        when(otherMasterUser.getId()).thenReturn(otherMasterUserId);

        Master otherMaster = mock(Master.class);
        when(otherMaster.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(otherMaster.getSalon()).thenReturn(otherSalon);
        when(otherMaster.getUser()).thenReturn(otherMasterUser);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(otherMaster);

        SecurityContextHolder.getContext()
                .setAuthentication(mockAuth(actorMasterUserId, "ROLE_SALON_MASTER"));

        assertThatThrownBy(() -> authorizationService.enforceCanViewBooking(actorMasterUserId, booking))
                .as("a SALON_MASTER must only ever read bookings assigned to them — not another "
                        + "salon's, and (per fix M1) not a same-salon colleague's either")
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");

        // Guard assertion (not decoration): the entity graph answers this in memory. A regression
        // that re-broadened the check to "any master at a salon I can manage" would reach
        // hasManagementAccess and hit the DB — this fails the moment that happens.
        verify(userRepository, never()).findSalonIdById(any());
        verify(salonRepository, never()).existsByIdAndOwnerId(any(), any());
    }

    // ── canManageMaster — role fast-path (no DB hit) ──────────────────────────

    @Test
    @DisplayName("canManageMaster returns false without DB hit when actor has ROLE_CLIENT")
    void should_returnFalseWithoutDbHit_when_clientTriesToManageMaster() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Authentication auth = mockAuth(actorId, "ROLE_CLIENT");

        boolean result = authorizationService.canManageMaster(auth, masterId);

        assertThat(result).isFalse();
        verify(masterRepository, never()).findByIdWithUserAndSalon(masterId);
    }

    @Test
    @DisplayName("canManageMaster returns false without DB hit when actor has ROLE_SALON_MASTER")
    void should_returnFalseWithoutDbHit_when_salonMasterTriesToManageMaster() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        boolean result = authorizationService.canManageMaster(auth, masterId);

        assertThat(result).isFalse();
        verify(masterRepository, never()).findByIdWithUserAndSalon(masterId);
    }

    // ── canManageMaster — SALON_OWNER branch (Phase 12.1) ─────────────────────

    @Test
    @DisplayName("canManageMaster — returns true when actor is the salon owner and master type is SALON_OWNER")
    void should_returnTrue_when_salonOwnerTypeMasterAndActorOwnsSalon() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(actorId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageMaster(auth, masterId);

        assertThat(result)
                .as("SALON_OWNER actor must be allowed to manage a SALON_OWNER-type master in their own salon")
                .isTrue();
    }

    @Test
    @DisplayName("canManageMaster — returns false when actor does not own the salon of a SALON_OWNER-type master")
    void should_returnFalse_when_differentOwnerTriesToManageSalonOwnerTypeMaster() {
        UUID actorId = UUID.randomUUID();
        UUID realOwnerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        User realOwner = mock(User.class);
        when(realOwner.getId()).thenReturn(realOwnerId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(realOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageMaster(auth, masterId);

        assertThat(result)
                .as("a different owner must not be allowed to manage a SALON_OWNER-type master")
                .isFalse();
    }

    @Test
    @DisplayName("canManageMaster — returns false when SALON_OWNER-type master has a null salon reference")
    void should_returnFalse_when_salonOwnerTypeMasterHasNullSalon() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(null);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageMaster(auth, masterId);

        assertThat(result)
                .as("canManageMaster must return false when the SALON_OWNER-type master has no salon reference")
                .isFalse();
    }

    @Test
    @DisplayName("canManageMaster — returns false when SALON_OWNER-type master has salon with null owner")
    void should_returnFalse_when_salonOwnerTypeMasterHasNullOwnerOnSalon() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(null);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageMaster(auth, masterId);

        assertThat(result)
                .as("canManageMaster must return false when the salon has a null owner reference")
                .isFalse();
    }

    // ── canManageMasterSchedule — SALON_OWNER branch (Phase 12.1) ─────────────

    @Test
    @DisplayName("canManageMasterSchedule — returns true when actor is the salon owner and master type is SALON_OWNER")
    void should_returnTrue_when_salonOwnerCanManageOwnTypeMasterSchedule() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(actorId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageMasterSchedule(auth, masterId);

        assertThat(result)
                .as("a SALON_OWNER actor must be able to manage the schedule of their SALON_OWNER-type master")
                .isTrue();
    }

    @Test
    @DisplayName("canManageMasterSchedule — returns false when a different owner tries to manage SALON_OWNER-type master schedule")
    void should_returnFalse_when_differentOwnerTriesToManageSalonOwnerTypeMasterSchedule() {
        UUID actorId = UUID.randomUUID();
        UUID realOwnerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        User realOwner = mock(User.class);
        when(realOwner.getId()).thenReturn(realOwnerId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(realOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageMasterSchedule(auth, masterId);

        assertThat(result)
                .as("a different owner must not be allowed to manage a SALON_OWNER-type master schedule")
                .isFalse();
    }

    @Test
    @DisplayName("canManageMasterSchedule — returns false when SALON_OWNER-type master has null salon")
    void should_returnFalse_when_salonOwnerTypeMasterScheduleHasNullSalon() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(null);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageMasterSchedule(auth, masterId);

        assertThat(result)
                .as("canManageMasterSchedule must return false when SALON_OWNER-type master has no salon")
                .isFalse();
    }

    // ── enforceCanManageMaster — SALON_OWNER branch (Phase 12.1) ──────────────

    @Test
    @DisplayName("enforceCanManageMaster — does not throw when actor is the correct salon owner for a SALON_OWNER-type master")
    void should_notThrow_when_correctSalonOwnerEnforcesOnSalonOwnerTypeMaster() {
        UUID actorId = UUID.randomUUID();

        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(actorId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);

        authorizationService.enforceCanManageMaster(actorId, master);
        // No exception = pass.
    }

    @Test
    @DisplayName("enforceCanManageMaster — throws ForbiddenException when wrong actor enforces on SALON_OWNER-type master")
    void should_throwForbidden_when_wrongActorEnforcesOnSalonOwnerTypeMaster() {
        UUID actorId = UUID.randomUUID();
        UUID realOwnerId = UUID.randomUUID();

        User realOwner = mock(User.class);
        when(realOwner.getId()).thenReturn(realOwnerId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(realOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);

        assertThatThrownBy(() -> authorizationService.enforceCanManageMaster(actorId, master))
                .isInstanceOf(ForbiddenException.class)
                .as("enforceCanManageMaster must throw ForbiddenException for wrong actor on SALON_OWNER-type master");
    }

    @Test
    @DisplayName("enforceCanManageMaster — throws ForbiddenException when SALON_OWNER-type master has null salon")
    void should_throwForbidden_when_salonOwnerTypeMasterHasNullSalonOnEnforce() {
        UUID actorId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(null);

        assertThatThrownBy(() -> authorizationService.enforceCanManageMaster(actorId, master))
                .isInstanceOf(ForbiddenException.class)
                .as("null salon on SALON_OWNER-type master must result in ForbiddenException");
    }

    // ── enforceCanManageMasterSchedule — SALON_OWNER branch (Phase 12.1) ──────

    @Test
    @DisplayName("enforceCanManageMasterSchedule — does not throw when actor is the correct owner for SALON_OWNER-type master")
    void should_notThrow_when_correctOwnerEnforcesScheduleOnSalonOwnerTypeMaster() {
        UUID actorId = UUID.randomUUID();

        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(actorId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);

        authorizationService.enforceCanManageMasterSchedule(actorId, master);
        // No exception = pass.
    }

    @Test
    @DisplayName("enforceCanManageMasterSchedule — throws ForbiddenException when wrong actor enforces schedule on SALON_OWNER-type master")
    void should_throwForbidden_when_wrongActorEnforcesScheduleOnSalonOwnerTypeMaster() {
        UUID actorId = UUID.randomUUID();
        UUID realOwnerId = UUID.randomUUID();

        User realOwner = mock(User.class);
        when(realOwner.getId()).thenReturn(realOwnerId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(realOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);

        assertThatThrownBy(() -> authorizationService.enforceCanManageMasterSchedule(actorId, master))
                .isInstanceOf(ForbiddenException.class)
                .as("wrong actor must get ForbiddenException on SALON_OWNER-type master schedule");
    }

    @Test
    @DisplayName("enforceCanManageMasterSchedule — throws ForbiddenException when SALON_OWNER-type master has null salon")
    void should_throwForbidden_when_salonOwnerTypeMasterHasNullSalonOnScheduleEnforce() {
        UUID actorId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(null);

        assertThatThrownBy(() -> authorizationService.enforceCanManageMasterSchedule(actorId, master))
                .isInstanceOf(ForbiddenException.class)
                .as("null salon on SALON_OWNER-type master must yield ForbiddenException on schedule enforce");
    }

    // TODO(24.7): AuthorizationService.enforceCanManageBooking was deleted in Phase 24.2 — it had
    // zero remaining production callers once declineBooking/notCompleteBooking moved to
    // enforceCanCancelBooking (which also admits SALON_ADMIN via hasProviderAuthorityOverBooking,
    // unlike the deleted owner-only predicate). Former coverage here (SALON_OWNER-type-master
    // ownership match/mismatch, null-salon guard, SALON_MASTER-type-master owner match/mismatch)
    // has no 1:1 replacement — isAuthorizedToManageBooking (the private predicate that backed it)
    // is now exercised only indirectly via canViewBooking/enforceCanViewBooking below. backend-qa
    // should add equivalent coverage for enforceCanCancelBooking/canCancelBooking instead (see the
    // provider-cancel authz matrix in the 24.2/24.7 plan).

    // ── Phase 12.3 — Owner-as-master: authorization explicit tests ────────────

    // canManageBooking (SpEL projection path) still exists unchanged — the in-memory
    // enforceCanManageBooking twin was deleted in Phase 24.2 (see the TODO(24.7) above).
    @Test
    @DisplayName("Phase 12.3: canManageBooking allows the owner to confirm their own SALON_OWNER-type master booking (projection path)")
    void should_allowOwner_toConfirmOwnMasterBooking_viaCanManageBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID masterUserId = actorId; // owner is the master's user

        // canManageBooking path: salonOwnerUserId == actorId in the lightweight projection
        when(bookingRepository.findViewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingViewAccess(UUID.randomUUID(), masterUserId, actorId)));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean canManage = authorizationService.canManageBooking(auth, bookingId);

        assertThat(canManage)
                .as("SALON_OWNER actor must be able to manage their own SALON_OWNER-type master booking via canManageBooking")
                .isTrue();
    }

    @Test
    @DisplayName("Phase 12.3: canViewBooking returns true for the owner viewing their own SALON_OWNER-type master booking via salon-owner branch")
    void should_allowOwner_toViewOwnMasterBooking_withFullData() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID clientUserId = UUID.randomUUID();
        UUID masterUserId = actorId; // owner is also the master's user

        // salonOwnerUserId == actorId triggers the first (salon-owner) branch, returning true
        // with full client data — the masterUserId == actorId branch would also match,
        // but the salon-owner branch fires first. Both grant; no contradiction.
        when(bookingRepository.findViewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingViewAccess(clientUserId, masterUserId, actorId)));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canViewBooking(auth, bookingId);

        assertThat(result)
                .as("SALON_OWNER actor must be able to view their own SALON_OWNER-type master booking with full data")
                .isTrue();
    }

    // ── principalId / roleFromAuthentication — fail-closed delegation (LOW hardening) ──────────
    // AuthorizationService.principalId and roleFromAuthentication are now thin delegates to
    // AuthenticationUtils.userId / AuthenticationUtils.role (B14 dedup). Both are private, so they
    // are pinned through the nearest public predicates that call them:
    //   • canManageSalon(auth, id) calls principalId(auth) once the OWNER/ADMIN authority check passes.
    //   • canViewBooking(auth, id) calls principalId(auth) THEN roleFromAuthentication(auth) before
    //     any repository access, so a bad role fails closed with no stub needed.
    // The contract these pin: a malformed principal/role surfaces as ForbiddenException (403),
    // NEVER the raw IllegalStateException/ClassCastException that used to become a 500.

    @Test
    @DisplayName("principalId — a non-UUID token principal fails closed with ForbiddenException (403), "
            + "not the pre-hardening IllegalStateException (500), via canManageSalon")
    void should_throwForbiddenNotIllegalState_when_principalDetailsAreNotUuid() {
        UUID salonId = UUID.randomUUID();

        // OWNER authority so canManageSalon's role fast-path passes and it reaches principalId(auth);
        // details is a String, not a UUID — AuthenticationUtils.userId must reject it.
        var token = new UsernamePasswordAuthenticationToken(
                "user@example.com", null, List.of(new SimpleGrantedAuthority("ROLE_SALON_OWNER")));
        token.setDetails("not-a-uuid");

        assertThatThrownBy(() -> authorizationService.canManageSalon(token, salonId))
                .as("a non-UUID principal must fail closed as 403 (ForbiddenException), never leak as a 500")
                .isInstanceOf(ForbiddenException.class)
                .isNotInstanceOf(IllegalStateException.class);
        // Fail-closed means no ownership query is ever issued for a malformed principal.
        verify(salonRepository, never()).existsByIdAndOwnerId(any(), any());
    }

    @Test
    @DisplayName("roleFromAuthentication — a principal carrying NO recognised role authority fails "
            + "closed with ForbiddenException (403) before any DB access, via canViewBooking")
    void should_throwForbidden_when_noRoleAuthorityPresent() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        // Valid UUID principal (principalId succeeds) but no ROLE_* authority — roleFromAuthentication
        // must throw before findViewAccessById is consulted, so no repository stub is required.
        var token = new UsernamePasswordAuthenticationToken(
                "user@example.com", null, List.of(new SimpleGrantedAuthority("SCOPE_read")));
        token.setDetails(actorId);

        assertThatThrownBy(() -> authorizationService.canViewBooking(token, bookingId))
                .isInstanceOf(ForbiddenException.class);
        verify(bookingRepository, never()).findViewAccessById(any());
    }

    @Test
    @DisplayName("roleFromAuthentication — a principal carrying TWO distinct role authorities fails "
            + "closed (\"Ambiguous role\") with ForbiddenException, never silently over-granting, via canViewBooking")
    void should_throwForbidden_when_roleIsAmbiguous() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        // Two valid roles present — resolving by precedence would silently over-grant scope
        // (e.g. {CLIENT, SALON_OWNER} → owner-wide access), so AuthenticationUtils.role rejects it.
        var token = new UsernamePasswordAuthenticationToken(
                "user@example.com", null,
                List.of(new SimpleGrantedAuthority("ROLE_CLIENT"),
                        new SimpleGrantedAuthority("ROLE_SALON_OWNER")));
        token.setDetails(actorId);

        assertThatThrownBy(() -> authorizationService.canViewBooking(token, bookingId))
                .as("an ambiguous multi-role principal must be rejected, not resolved to the most-privileged role")
                .isInstanceOf(ForbiddenException.class);
        verify(bookingRepository, never()).findViewAccessById(any());
    }

    @Test
    @DisplayName("principalId + roleFromAuthentication — happy path (UUID principal + single ROLE_*) "
            + "still threads the actor id and role through unchanged after the AuthenticationUtils delegation")
    void should_resolvePrincipalAndRoleUnchanged_onHappyPath() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);

        // A well-formed SALON_OWNER token: principalId must resolve exactly actorId and the role must
        // resolve to SALON_OWNER, so the ownership query is issued with the resolved actor id.
        boolean result = authorizationService.canManageSalon(mockAuth(actorId, "ROLE_SALON_OWNER"), salonId);

        assertThat(result)
                .as("the delegation must be behaviour-identical on the happy path — owner is granted")
                .isTrue();
        verify(salonRepository).existsByIdAndOwnerId(salonId, actorId);
    }
}
