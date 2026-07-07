package com.beautica.common.security;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));

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

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
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
        verify(masterRepository, never()).findByIdWithSalonAndOwner(masterId);
    }

    @Test
    @DisplayName("canManageMasterSchedule returns false when salon master record has a null salon reference")
    void should_returnFalse_when_salonMasterHasNullSalon() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(null);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));

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

    @Test
    @DisplayName("enforceCanViewBooking does not throw when correct client views their own booking")
    void should_notThrow_when_enforceCanViewBookingCalledWithCorrectClient() {
        UUID actorId = UUID.randomUUID();

        User client = mock(User.class);
        when(client.getId()).thenReturn(actorId);

        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(UUID.randomUUID());

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);
        when(booking.getClient()).thenReturn(client);

        // Populate SecurityContext with CLIENT role for the correct client
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_CLIENT"));

        assertThatCode(() -> authorizationService.enforceCanViewBooking(actorId, booking))
                .doesNotThrowAnyException();
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
        verify(masterRepository, never()).findByIdWithSalonAndOwner(masterId);
    }

    @Test
    @DisplayName("canManageMaster returns false without DB hit when actor has ROLE_SALON_MASTER")
    void should_returnFalseWithoutDbHit_when_salonMasterTriesToManageMaster() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        boolean result = authorizationService.canManageMaster(auth, masterId);

        assertThat(result).isFalse();
        verify(masterRepository, never()).findByIdWithSalonAndOwner(masterId);
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

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));

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

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));

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

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));

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

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));

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

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));

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

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));

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

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));

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

    // ── isAuthorizedToManageBooking — SALON_OWNER master type (Phase 12.1) ────

    @Test
    @DisplayName("enforceCanManageBooking — does not throw when SALON_OWNER-type master's salon owner matches actor")
    void should_notThrow_when_enforceCanManageBookingCalledWithSalonOwnerTypeAndCorrectOwner() {
        UUID actorId = UUID.randomUUID();

        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(actorId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);

        authorizationService.enforceCanManageBooking(actorId, booking);
        // No exception = pass.
    }

    @Test
    @DisplayName("enforceCanManageBooking — throws ForbiddenException when booking master is SALON_OWNER type but actor is a different owner")
    void should_throwForbidden_when_wrongActorTriesToManageBookingWithSalonOwnerTypeMaster() {
        UUID actorId = UUID.randomUUID();
        UUID realOwnerId = UUID.randomUUID();

        User realOwner = mock(User.class);
        when(realOwner.getId()).thenReturn(realOwnerId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(realOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);

        assertThatThrownBy(() -> authorizationService.enforceCanManageBooking(actorId, booking))
                .isInstanceOf(ForbiddenException.class)
                .as("a different owner must not be allowed to manage a booking whose master is SALON_OWNER type");
    }

    @Test
    @DisplayName("enforceCanManageBooking — throws ForbiddenException when SALON_OWNER-type master has null salon on booking")
    void should_throwForbidden_when_salonOwnerTypeMasterHasNullSalonOnBookingEnforce() {
        UUID actorId = UUID.randomUUID();

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(null);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);

        assertThatThrownBy(() -> authorizationService.enforceCanManageBooking(actorId, booking))
                .isInstanceOf(ForbiddenException.class)
                .as("null salon on SALON_OWNER-type master must yield ForbiddenException on booking enforce");
    }

    // ── enforceCanManageBooking ────────────────────────────────────────────────

    @Test
    @DisplayName("enforceCanManageBooking throws ForbiddenException when owner B tries to manage owner A's salon booking")
    void should_throwForbidden_when_enforceCanManageBookingCalledWithWrongOwner() {
        UUID ownerAId = UUID.randomUUID();
        UUID ownerBId = UUID.randomUUID();

        User ownerA = mock(User.class);
        when(ownerA.getId()).thenReturn(ownerAId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(ownerA);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);

        assertThatThrownBy(() -> authorizationService.enforceCanManageBooking(ownerBId, booking))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("enforceCanManageBooking does not throw when correct salon owner manages their own salon's booking")
    void should_notThrow_when_enforceCanManageBookingCalledWithCorrectSalonOwner() {
        UUID actorId = UUID.randomUUID();

        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(actorId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);

        assertThatCode(() -> authorizationService.enforceCanManageBooking(actorId, booking))
                .doesNotThrowAnyException();
    }

    // ── Phase 12.3 — Owner-as-master: authorization explicit tests ────────────

    // Split from the former dual-behaviour should_allowOwner_toConfirmOwnMasterBooking,
    // which exercised both the SpEL projection path (canManageBooking) and the in-memory
    // entity path (enforceCanManageBooking) in one method. One behaviour per test now.
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
    @DisplayName("Phase 12.3: enforceCanManageBooking allows the owner to confirm their own SALON_OWNER-type master booking (in-memory entity path)")
    void should_allowOwner_toConfirmOwnMasterBooking_viaEnforceCanManageBooking() {
        UUID actorId = UUID.randomUUID();

        // isAuthorizedToManageBooking path: in-memory entity check via enforceCanManageBooking
        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(actorId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);

        assertThatCode(() -> authorizationService.enforceCanManageBooking(actorId, booking))
                .as("enforceCanManageBooking must not throw for SALON_OWNER-type master booking owned by actor")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Phase 12.3: SALON_ADMIN cannot confirm/complete a SALON_OWNER-type master booking — owner-level boundary preserved")
    void should_denySalonAdmin_toCompleteOwnerMasterBooking() {
        UUID adminId = UUID.randomUUID();
        UUID realOwnerId = UUID.randomUUID(); // the actual salon owner — not the admin

        User realOwner = mock(User.class);
        when(realOwner.getId()).thenReturn(realOwnerId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(realOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);

        // adminId != realOwnerId → isAuthorizedToManageBooking returns false → throws
        assertThatThrownBy(() -> authorizationService.enforceCanManageBooking(adminId, booking))
                .as("SALON_ADMIN (distinct userId) must not be able to complete a SALON_OWNER-type master booking")
                .isInstanceOf(ForbiddenException.class);
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
}
