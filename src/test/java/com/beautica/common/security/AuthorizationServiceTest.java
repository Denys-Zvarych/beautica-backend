package com.beautica.common.security;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

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

        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.SALON_OWNER);

        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
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

        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.SALON_OWNER);

        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
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

        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.SALON_ADMIN);
        when(actor.getSalonId()).thenReturn(salonId);

        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));

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

        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.SALON_ADMIN);
        when(actor.getSalonId()).thenReturn(otherSalonId);

        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));

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

        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.SALON_OWNER);

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);

        when(masterRepository.findByIdWithSalonAndOwner(masterId)).thenReturn(Optional.of(master));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
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

        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.SALON_OWNER);

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(false);

        assertThatThrownBy(() -> authorizationService.enforceCanManageSalon(actorId, salon))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("enforceCanManageSalon does not throw when actor is the correct salon owner")
    void should_notThrow_when_enforceCanManageSalonCalledWithCorrectOwner() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        User owner = mock(User.class);
        when(owner.getRole()).thenReturn(Role.SALON_OWNER);

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        when(userRepository.findById(ownerId)).thenReturn(Optional.of(owner));
        when(salonRepository.existsByIdAndOwnerId(salonId, ownerId)).thenReturn(true);

        authorizationService.enforceCanManageSalon(ownerId, salon);
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

        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.SALON_OWNER);

        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);

        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(false);

        assertThatThrownBy(() -> authorizationService.enforceCanManageMaster(actorId, master))
                .isInstanceOf(ForbiddenException.class);
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

        User admin = mock(User.class);
        when(admin.getRole()).thenReturn(Role.SALON_ADMIN);
        when(admin.getSalonId()).thenReturn(null);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(admin));

        boolean result = authorizationService.hasManagementAccess(salonId, actorId);

        assertThat(result).isFalse();
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

    // ── canManageBooking ───────────────────────────────────────────────────────

    @Test
    @DisplayName("canManageBooking returns false immediately when actor has ROLE_SALON_MASTER without touching repository")
    void should_returnFalse_when_salonMasterTriesToManageBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        boolean result = authorizationService.canManageBooking(auth, bookingId);

        assertThat(result).isFalse();
        verify(bookingRepository, never()).findByIdWithFullGraph(any());
    }

    @Test
    @DisplayName("canManageBooking returns true when salon owner's ID matches the booking master's salon owner")
    void should_returnTrue_when_salonOwnerManagesOwnSalonsBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(actorId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);

        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

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

        User ownerA = mock(User.class);
        when(ownerA.getId()).thenReturn(ownerAId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(ownerA);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);

        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        Authentication auth = mockAuth(ownerBId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canManageBooking(auth, bookingId);

        assertThat(result).isFalse();
    }

    // ── canViewBooking ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("canViewBooking returns true when client views their own booking")
    void should_returnTrue_when_clientViewsOwnBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

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

        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.CLIENT);

        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));

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

        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.CLIENT);

        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(clientBId)).thenReturn(Optional.of(actor));

        Authentication auth = mockAuth(clientBId, "ROLE_CLIENT");

        boolean result = authorizationService.canViewBooking(auth, bookingId);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("canViewBooking returns true when salon master views a booking assigned to them")
    void should_returnTrue_when_salonMasterViewsOwnBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        User masterUser = mock(User.class);
        when(masterUser.getId()).thenReturn(actorId);

        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(UUID.randomUUID());

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);
        when(master.getUser()).thenReturn(masterUser);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);

        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.SALON_MASTER);

        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));

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

        User otherMasterUser = mock(User.class);
        when(otherMasterUser.getId()).thenReturn(otherMasterUserId);

        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(UUID.randomUUID());

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);
        when(master.getUser()).thenReturn(otherMasterUser);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);

        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.SALON_MASTER);

        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        boolean result = authorizationService.canViewBooking(auth, bookingId);

        assertThat(result).isFalse();
    }

    // ── canCancelBooking ───────────────────────────────────────────────────────

    @Test
    @DisplayName("canCancelBooking returns false when client B tries to cancel client A's booking")
    void should_returnFalse_when_clientBCancelsClientABooking() {
        UUID clientAId = UUID.randomUUID();
        UUID clientBId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        User clientA = mock(User.class);
        when(clientA.getId()).thenReturn(clientAId);

        Booking booking = mock(Booking.class);
        when(booking.getClient()).thenReturn(clientA);

        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        Authentication auth = mockAuth(clientBId, "ROLE_CLIENT");

        boolean result = authorizationService.canCancelBooking(auth, bookingId);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("canCancelBooking returns true when client cancels their own booking")
    void should_returnTrue_when_clientCancelsOwnBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        User client = mock(User.class);
        when(client.getId()).thenReturn(actorId);

        Booking booking = mock(Booking.class);
        when(booking.getClient()).thenReturn(client);

        when(bookingRepository.findByIdWithFullGraph(bookingId)).thenReturn(Optional.of(booking));

        Authentication auth = mockAuth(actorId, "ROLE_CLIENT");

        boolean result = authorizationService.canCancelBooking(auth, bookingId);

        assertThat(result).isTrue();
    }

    // ── enforceCanViewBooking ──────────────────────────────────────────────────

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

        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.CLIENT);
        when(userRepository.findById(clientBId)).thenReturn(Optional.of(actor));

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

        User actor = mock(User.class);
        when(actor.getRole()).thenReturn(Role.CLIENT);
        when(userRepository.findById(actorId)).thenReturn(Optional.of(actor));

        assertThatCode(() -> authorizationService.enforceCanViewBooking(actorId, booking))
                .doesNotThrowAnyException();
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
}
