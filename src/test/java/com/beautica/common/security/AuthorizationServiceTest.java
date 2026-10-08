package com.beautica.common.security;

import com.beautica.auth.Role;
import com.beautica.booking.entity.Booking;
import com.beautica.booking.repository.AppointmentCompletionAccess;
import com.beautica.booking.repository.BookingCompletionAccess;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.BookingReviewAccess;
import com.beautica.booking.repository.BookingViewAccess;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.repository.MasterServiceRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    /** Phase 345 catalogue gate — {@code existsActiveOwnerRowAssignment} only. */
    @Mock
    private MasterServiceRepository masterServiceRepository;

    /**
     * A REAL instance, not a mock (perf MEDIUM, 2026-08-18): in production this is a
     * {@code @RequestScope} bean that memoises {@code users.salon_id} for one request. Spying the
     * real thing keeps every {@code verify(userRepository).findSalonIdById(...)} assertion in this
     * class meaningful — the memo is transparent on a first read and only suppresses a SECOND,
     * identical read of the same actor within one instance's lifetime (here: one test method, since
     * JUnit 5 builds a fresh test instance per test).
     */
    @Spy
    private ActorSalonAssignmentMemo actorSalonAssignmentMemo = new ActorSalonAssignmentMemo();

    /**
     * A REAL instance for the same reason as the memo above (cycle-2 audit, B5): with no request
     * bound to the thread — which is every test in this class — it degrades to a plain uncached
     * read, so every {@code verify(salonRepository).existsByIdAndOwnerId(...)} and
     * {@code verify(masterRepository).existsByIdAndSalonId(...)} assertion here stays exact. A mock
     * would return {@code false} for every fact and silently invert the gates.
     */
    @Spy
    private SalonScopeFactMemo salonScopeFactMemo = new SalonScopeFactMemo();

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

    /**
     * Test double for {@link ServiceRepository.ServiceOwnerAccess} (Phase 306 D3) — the
     * projection {@code findOwnerUserId} now returns instead of a bare {@code UUID}. {@code
     * salonId} is null for an INDEPENDENT_MASTER-owned definition, matching production (the
     * projection's {@code s.id} rides on a LEFT JOIN that is null off ownerType != SALON).
     *
     * <p>{@code salonOwnerId} (Phase 306 audit fix #1) mirrors production's {@code s.owner.id}
     * column: equal to {@code ownerUserId} when {@code salonId} is non-null (both are literally
     * {@code s.owner.id} in the JPQL projection), and null otherwise — never set independently,
     * so this double cannot drift from what the real query would return.
     */
    private record TestServiceOwnerAccess(UUID ownerUserId, UUID salonId)
            implements ServiceRepository.ServiceOwnerAccess {
        @Override
        public UUID getOwnerUserId() {
            return ownerUserId;
        }

        @Override
        public UUID getSalonId() {
            return salonId;
        }

        @Override
        public UUID getSalonOwnerId() {
            return salonId != null ? ownerUserId : null;
        }
    }

    private ServiceRepository.ServiceOwnerAccess salonOwnerAccess(UUID salonOwnerId, UUID salonId) {
        return new TestServiceOwnerAccess(salonOwnerId, salonId);
    }

    private ServiceRepository.ServiceOwnerAccess independentMasterOwnerAccess(UUID masterUserId) {
        return new TestServiceOwnerAccess(masterUserId, null);
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

    // ── Shared fixture for the two Phase 311 salon-master gates (2026-09-13 audit, P2/S2) ──
    //
    // Both gates now answer "does this masters row belong to salonId?" IN MEMORY off the salon
    // that findByIdWithUserAndSalon already LEFT JOIN FETCHes, instead of issuing a second
    // existsByIdAndSalonId round trip per conjunct. The fixtures below therefore stub
    // master.getSalon() rather than that finder — which is also why an INDEPENDENT_MASTER
    // (salon == null) is now denied by an explicit rule instead of incidentally.

    /** A masters row owned by {@code ownerUserId}, sitting in {@code salonId} (null = solo). */
    private static Master masterRow(UUID ownerUserId, UUID salonId) {
        User masterUser = mock(User.class);
        when(masterUser.getId()).thenReturn(ownerUserId);

        Master master = mock(Master.class);
        when(master.getUser()).thenReturn(masterUser);
        if (salonId != null) {
            Salon salon = mock(Salon.class);
            // lenient: ownsMasterRowInSalon short-circuits on the user-id comparison, so a PEER-row
            // fixture legitimately never reaches the salon half. Stubbing it unconditionally keeps
            // ONE fixture for both the positive and negative cases; making it strict would force a
            // second near-identical helper whose only difference is what it omits.
            lenient().when(salon.getId()).thenReturn(salonId);
            lenient().when(master.getSalon()).thenReturn(salon);
        }
        return master;
    }

    // ── canReadSalonMasterServices (Phase 310) ────────────────────────────────
    // GET /salons/{salonId}/masters/{masterId}/services. Modelled on canReadMasterSchedule
    // above; widens Phase 309's owner/admin-only gate to also admit a SALON_MASTER reading
    // their OWN row. Fixtures deliberately use THREE distinct UUIDs (actor/user id, masters
    // row id, salon id) — a predicate that confuses the user id with the masters row id would
    // pass every test where they happen to coincide (anti-bug playbook §fixture values).

    @Test
    @DisplayName("canReadSalonMasterServices returns true when a SALON_MASTER reads their OWN row "
            + "(D2) — actor id, masters row id and salon id are all distinct")
    void should_returnTrue_when_salonMasterReadsOwnServices() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        Master master = masterRow(actorId, salonId);
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        boolean result = authorizationService.canReadSalonMasterServices(auth, salonId, masterId);

        assertThat(result).isTrue();
        verify(masterRepository, never())
                .existsByIdAndSalonId(any(), any());
    }

    @Test
    @DisplayName("canReadSalonMasterServices returns false when a SALON_MASTER reads a PEER "
            + "master's row — the single most important negative case (D2)")
    void should_returnFalse_when_salonMasterReadsPeerMastersServices() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID peerUserId = UUID.randomUUID();

        Master master = masterRow(peerUserId, salonId);
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        boolean result = authorizationService.canReadSalonMasterServices(auth, salonId, masterId);

        assertThat(result)
                .as("a SALON_MASTER must never read a peer master's services")
                .isFalse();
        // SALON_MASTER can never satisfy hasManagementAccess — must fail without consulting
        // either management-access query.
        verify(salonRepository, never()).existsByIdAndOwnerId(any(), any());
        verify(userRepository, never()).findSalonIdById(any());
    }

    @Test
    @DisplayName("canReadSalonMasterServices returns false when a SALON_MASTER's own masterId sits "
            + "behind a FOREIGN salonId in the path (D2.4)")
    void should_returnFalse_when_salonMasterOwnRowButForeignSalonId() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID foreignSalonId = UUID.randomUUID();

        // The master's REAL salon, which is not the one in the path.
        Master master = masterRow(actorId, UUID.randomUUID());
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        boolean result = authorizationService.canReadSalonMasterServices(auth, foreignSalonId, masterId);

        assertThat(result)
                .as("D2.4 — the path's salonId must actually own this master row, even for the "
                        + "actor's own masterId")
                .isFalse();
    }

    @Test
    @DisplayName("canReadSalonMasterServices returns true when the SALON_OWNER manages the PATH's "
            + "salonId — Phase 309 behaviour preserved, and the master row is never looked up "
            + "(D3 — an unknown or cross-salon masterId must still reach the service layer's 404)")
    void should_returnTrue_when_salonOwnerReadsAnyMasterInSalon() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canReadSalonMasterServices(auth, salonId, masterId);

        assertThat(result).isTrue();
        verify(masterRepository, never()).findByIdWithUserAndSalon(any());
    }

    @Test
    @DisplayName("canReadSalonMasterServices returns true when the SALON_ADMIN manages the PATH's "
            + "salonId — Phase 309 behaviour preserved, and the master row is never looked up "
            + "(D3 — an unknown or cross-salon masterId must still reach the service layer's 404)")
    void should_returnTrue_when_salonAdminReadsAnyMasterInSalon() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_ADMIN");

        boolean result = authorizationService.canReadSalonMasterServices(auth, salonId, masterId);

        assertThat(result).isTrue();
        verify(masterRepository, never()).findByIdWithUserAndSalon(any());
    }

    @Test
    @DisplayName("canReadSalonMasterServices returns false WITHOUT a DB hit when actor has "
            + "ROLE_CLIENT (mutation guard: dropping this fast path must turn this test red, not "
            + "just any test asserting the boolean alone)")
    void should_returnFalse_withoutDbHit_when_actorIsClientReadingSalonMasterServices() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_CLIENT");

        boolean result = authorizationService.canReadSalonMasterServices(auth, salonId, masterId);

        assertThat(result).isFalse();
        verify(masterRepository, never()).findByIdWithUserAndSalon(any());
        verifyNoInteractions(salonRepository, userRepository);
    }

    // ── canEditMasterServiceBand (Phase 311 D5) ───────────────────────────────
    // PATCH .../masters/{masterId}/services/{serviceDefId}. Same audience as
    // canReadSalonMasterServices, via the shared isOwnerAdminOrSelfMaster helper — this block
    // mirrors that one's matrix EXACTLY, one test per branch, so a future edit that inlines
    // different logic into either method (instead of sharing the helper) is caught here rather
    // than only at the controller/IT layer.

    @Test
    @DisplayName("canEditMasterServiceBand returns true when a SALON_MASTER edits their OWN row "
            + "(D5) — actor id, masters row id and salon id are all distinct")
    void should_returnTrue_when_salonMasterEditsOwnBand() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        Master master = masterRow(actorId, salonId);
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        boolean result = authorizationService.canEditMasterServiceBand(auth, salonId, masterId);

        assertThat(result).isTrue();
        verify(masterRepository, never())
                .existsByIdAndSalonId(any(), any());
    }

    @Test
    @DisplayName("canEditMasterServiceBand returns false when a SALON_MASTER edits a PEER master's "
            + "row — mutation 10's pin (comparing against m.getId() instead of m.getUser().getId() "
            + "would make this pass)")
    void should_returnFalse_when_salonMasterEditsPeerBand() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID peerUserId = UUID.randomUUID();

        Master master = masterRow(peerUserId, salonId);
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        boolean result = authorizationService.canEditMasterServiceBand(auth, salonId, masterId);

        assertThat(result)
                .as("a SALON_MASTER must never edit a peer master's band — the single most "
                        + "important negative case for D5, case 19")
                .isFalse();
        verify(salonRepository, never()).existsByIdAndOwnerId(any(), any());
        verify(userRepository, never()).findSalonIdById(any());
    }

    @Test
    @DisplayName("canEditMasterServiceBand returns false when a SALON_MASTER's own masterId sits "
            + "behind a FOREIGN salonId in the path")
    void should_returnFalse_when_salonMasterOwnBandButForeignSalonId() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID foreignSalonId = UUID.randomUUID();

        Master master = masterRow(actorId, UUID.randomUUID());
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        boolean result = authorizationService.canEditMasterServiceBand(auth, foreignSalonId, masterId);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("canEditMasterServiceBand returns true when the SALON_OWNER manages the PATH's salonId")
    void should_returnTrue_when_salonOwnerEditsAnyMasterBandInSalon() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);
        when(masterRepository.existsByIdAndSalonId(masterId, salonId)).thenReturn(true);

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canEditMasterServiceBand(auth, salonId, masterId);

        assertThat(result).isTrue();
        verify(masterRepository, never()).findByIdWithUserAndSalon(any());
    }

    @Test
    @DisplayName("canEditMasterServiceBand returns true when the SALON_ADMIN manages the PATH's salonId")
    void should_returnTrue_when_salonAdminEditsAnyMasterBandInSalon() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));
        when(masterRepository.existsByIdAndSalonId(masterId, salonId)).thenReturn(true);

        Authentication auth = mockAuth(actorId, "ROLE_SALON_ADMIN");

        boolean result = authorizationService.canEditMasterServiceBand(auth, salonId, masterId);

        assertThat(result).isTrue();
        verify(masterRepository, never()).findByIdWithUserAndSalon(any());
    }

    @Test
    @DisplayName("canEditMasterServiceBand returns false WITHOUT a DB hit when actor has ROLE_CLIENT")
    void should_returnFalse_withoutDbHit_when_actorIsClientEditingBand() {
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_CLIENT");

        boolean result = authorizationService.canEditMasterServiceBand(auth, salonId, masterId);

        assertThat(result).isFalse();
        verify(masterRepository, never()).findByIdWithUserAndSalon(any());
        verifyNoInteractions(salonRepository, userRepository);
    }

    @Test
    @DisplayName("canEditMasterServiceBand returns false when the SALON_OWNER manages the PATH's "
            + "salonId but masterId belongs to a DIFFERENT salon — unlike canReadSalonMasterServices, "
            + "this WRITE gate has no service-layer 404 fallback so it must reject here, not just at "
            + "the controller's separate masterBelongsToSalon conjunct")
    void should_returnFalse_when_ownerManagesSalonButMasterBelongsToDifferentSalon() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID foreignMasterId = UUID.randomUUID();

        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);
        when(masterRepository.existsByIdAndSalonId(foreignMasterId, salonId)).thenReturn(false);

        Authentication auth = mockAuth(actorId, "ROLE_SALON_OWNER");

        boolean result = authorizationService.canEditMasterServiceBand(auth, salonId, foreignMasterId);

        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("Phase 311 D5, mutation 9: canReadSalonMasterServices and canEditMasterServiceBand "
            + "are genuinely SEPARATE entry points — both delegate to the identical private helper, "
            + "so they agree on this peer-row negative case by construction, not by the read gate "
            + "widening to cover the write gate")
    void should_agree_betweenReadAndEditPredicates_onPeerRowNegativeCase() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID peerUserId = UUID.randomUUID();

        Master master = masterRow(peerUserId, salonId);
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        assertThat(authorizationService.canReadSalonMasterServices(auth, salonId, masterId)).isFalse();
        assertThat(authorizationService.canEditMasterServiceBand(auth, salonId, masterId)).isFalse();
    }

    // ── enforceCanEditMasterServiceBand (Phase 311 D5 service-layer guard) ────

    @Test
    @DisplayName("enforceCanEditMasterServiceBand does not throw when the actor manages the salon")
    void should_notThrow_when_ownerEnforcesEditMasterServiceBand() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);
        when(masterRepository.existsByIdAndSalonId(masterId, salonId)).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));

        assertThatCode(() -> authorizationService.enforceCanEditMasterServiceBand(actorId, salonId, masterId))
                .doesNotThrowAnyException();
        verify(masterRepository, never()).findByIdWithUserAndSalon(any());
    }

    @Test
    @DisplayName("enforceCanEditMasterServiceBand throws ForbiddenException when the actor manages "
            + "the PATH's salonId but masterId belongs to a DIFFERENT salon — the cross-tenant gap "
            + "closed post-311: management access alone used to be enough")
    void should_throwForbidden_when_ownerManagesSalonButMasterBelongsToDifferentSalon() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID foreignMasterId = UUID.randomUUID();

        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);
        when(masterRepository.existsByIdAndSalonId(foreignMasterId, salonId)).thenReturn(false);
        when(masterRepository.findByIdWithUserAndSalon(foreignMasterId)).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));

        assertThatThrownBy(() -> authorizationService.enforceCanEditMasterServiceBand(actorId, salonId, foreignMasterId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    @DisplayName("enforceCanEditMasterServiceBand does not throw when the actor is the SALON_MASTER "
            + "of masterId, within salonId")
    void should_notThrow_when_selfMasterEnforcesEditOwnBand() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        // Actor role SALON_MASTER short-circuits hasManagementAccess to false without any
        // repository call (AuthorizationService#hasManagementAccess), so no salonRepository stub
        // is needed here — one would be an UnnecessaryStubbingException under strict Mockito.
        Master master = masterRow(actorId, salonId);
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_MASTER"));

        assertThatCode(() -> authorizationService.enforceCanEditMasterServiceBand(actorId, salonId, masterId))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanEditMasterServiceBand throws ForbiddenException for a peer master or a "
            + "non-managing actor")
    void should_throwForbidden_when_actorNeitherManagesSalonNorOwnsMasterRow() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID peerUserId = UUID.randomUUID();

        // No salonRepository stub needed — SALON_MASTER short-circuits hasManagementAccess (see
        // the sibling happy-path test above).
        Master master = masterRow(peerUserId, salonId);
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_MASTER"));

        assertThatThrownBy(() -> authorizationService.enforceCanEditMasterServiceBand(actorId, salonId, masterId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied");
    }

    /**
     * A2 (2026-09-13 cycle-3 audit) — the same split-identity defect B6 closed on
     * {@code enforceCanManageSalon}: {@code actorId} arrives as a parameter while the actor's ROLE
     * comes from {@code SecurityContextHolder}. BOTH users here are genuine SALON_OWNERs of the
     * SAME salon and the master row genuinely belongs to it, so every other reason to deny is
     * removed — the split identity is the only one left. Deleting the principal assertion turns
     * this green.
     */
    @Test
    @DisplayName("A2: enforceCanEditMasterServiceBand refuses an actorId that is not the "
            + "authenticated principal, even when that actor genuinely owns the salon")
    void should_throwForbidden_when_bandEnforceActorIsNotTheAuthenticatedPrincipal() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        SecurityContextHolder.getContext().setAuthentication(mockAuth(userA, "ROLE_SALON_OWNER"));
        // Both A and B own the salon — the ownership predicate itself would say YES for either.
        lenient().when(salonRepository.existsByIdAndOwnerId(salonId, userA)).thenReturn(true);
        lenient().when(salonRepository.existsByIdAndOwnerId(salonId, userB)).thenReturn(true);
        // ...and the master really is in that salon, so the cross-tenant conjunct cannot be what denies.
        lenient().when(masterRepository.existsByIdAndSalonId(masterId, salonId)).thenReturn(true);

        assertThatThrownBy(() ->
                authorizationService.enforceCanEditMasterServiceBand(userB, salonId, masterId))
                .as("A's context must never authorize a band edit made on B's behalf")
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("A2 non-vacuity: the SAME enforceCanEditMasterServiceBand call SUCCEEDS once "
            + "actorId IS the authenticated principal — the guard rejects the identity mismatch, "
            + "not the fixture")
    void should_notThrow_when_bandEnforceActorIsTheAuthenticatedPrincipal() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        SecurityContextHolder.getContext().setAuthentication(mockAuth(ownerId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, ownerId)).thenReturn(true);
        when(masterRepository.existsByIdAndSalonId(masterId, salonId)).thenReturn(true);

        assertThatCode(() ->
                authorizationService.enforceCanEditMasterServiceBand(ownerId, salonId, masterId))
                .doesNotThrowAnyException();
    }

    // ── INDEPENDENT_MASTER + SALON_ADMIN + CLIENT coverage (2026-09-13 audit, Q5/Q6) ─────────
    //
    // Q5: an INDEPENDENT_MASTER was never exercised against these three methods, in EITHER
    // direction. They were denied only incidentally — masterBelongsToSalon's existsByIdAndSalonId
    // can never match a NULL salon_id — which is a mechanism, not a rule, and the P2/S2 fix
    // replaced that mechanism with an explicit `m.getSalon() != null` conjunct. These tests pin
    // the INTENDED behaviour (a solo master has no salon-scoped catalogue, so both gates deny)
    // so it survives whichever mechanism implements it.
    //
    // Q6: SALON_ADMIN's grant on enforceCanEditMasterServiceBand (D5 parity with the SpEL gate)
    // was unproven at the service layer, and CLIENT/INDEPENDENT_MASTER denials were absent there
    // entirely.

    @Test
    @DisplayName("Q5: canReadSalonMasterServices DENIES an INDEPENDENT_MASTER reading their own "
            + "masters row — a solo master's row has salon_id NULL, so no salonId can own it")
    void should_returnFalse_when_independentMasterReadsOwnRowUnderASalonPath() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        Master master = masterRow(actorId, null);   // salon_id IS NULL — the solo-master shape
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_INDEPENDENT_MASTER");

        assertThat(authorizationService.canReadSalonMasterServices(auth, salonId, masterId))
                .as("the row is genuinely the actor's own; it is the ABSENT salon that denies")
                .isFalse();
    }

    @Test
    @DisplayName("Q5: canEditMasterServiceBand DENIES an INDEPENDENT_MASTER on their own row — "
            + "the write gate agrees with the read gate for the solo-master shape")
    void should_returnFalse_when_independentMasterEditsOwnRowUnderASalonPath() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        Master master = masterRow(actorId, null);
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_INDEPENDENT_MASTER");

        assertThat(authorizationService.canEditMasterServiceBand(auth, salonId, masterId)).isFalse();
        verify(salonRepository, never()).existsByIdAndOwnerId(any(), any());
        verify(userRepository, never()).findSalonIdById(any());
    }

    @Test
    @DisplayName("Q5: an INDEPENDENT_MASTER whose row DOES sit in a salon (a rotated staff member "
            + "whose JWT role is stale) is still admitted on the own-row branch — the gates key on "
            + "the masters row, not the token role, and this pins that boundary deliberately")
    void should_returnTrue_when_actorOwnsASalonBoundMasterRowRegardlessOfTokenRole() {
        UUID actorId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        Master master = masterRow(actorId, salonId);
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

        Authentication auth = mockAuth(actorId, "ROLE_INDEPENDENT_MASTER");

        assertThat(authorizationService.canReadSalonMasterServices(auth, salonId, masterId))
                .as("non-vacuity for the two denials above: what rejects a solo master is the NULL "
                        + "salon on their row, NOT the INDEPENDENT_MASTER authority on their token")
                .isTrue();
    }

    @Test
    @DisplayName("Q6: enforceCanEditMasterServiceBand does not throw for a SALON_ADMIN assigned to "
            + "the salon — D5 parity with the SpEL gate, at the service layer")
    void should_notThrow_when_salonAdminEnforcesEditMasterServiceBand() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));
        when(masterRepository.existsByIdAndSalonId(masterId, salonId)).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_ADMIN"));

        assertThatCode(() -> authorizationService.enforceCanEditMasterServiceBand(actorId, salonId, masterId))
                .doesNotThrowAnyException();
        verify(masterRepository, never()).findByIdWithUserAndSalon(any());
    }

    @Test
    @DisplayName("Q6: enforceCanEditMasterServiceBand throws for a SALON_ADMIN assigned to a "
            + "DIFFERENT salon — the admin grant is salon-scoped, not role-scoped")
    void should_throwForbidden_when_salonAdminOfAnotherSalonEnforcesEditMasterServiceBand() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        // B2 (cycle-2 audit) — every OTHER reason to deny is removed, so the admin's assignment
        // to a different salon is the ONLY one left. Previously findByIdWithUserAndSalon returned
        // empty and existsByIdAndSalonId was unstubbed (Mockito default false), so the throw came
        // from the MISSING master row: granting any SALON_ADMIN in hasManagementAccess still left
        // this green and the DisplayName's claim unproven.
        // Built BEFORE the when(...) call: masterRow() stubs its own mocks, and Mockito forbids
        // stubbing a mock inside an unfinished when(...) argument list.
        Master foreignMaster = masterRow(UUID.randomUUID(), salonId);
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(UUID.randomUUID()));
        // The master row EXISTS and DOES belong to salonId — it simply is not this actor's.
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(foreignMaster));
        // lenient: unreached while the gate is correct (`&&` short-circuits after the management
        // arm denies), and reached only by the mutant this test exists to catch.
        lenient().when(masterRepository.existsByIdAndSalonId(masterId, salonId)).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_ADMIN"));

        assertThatThrownBy(() -> authorizationService.enforceCanEditMasterServiceBand(actorId, salonId, masterId))
                .as("the row is in THIS salon and the actor is a real SALON_ADMIN — what must "
                        + "refuse them is that they administer a DIFFERENT salon")
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("Q6: enforceCanEditMasterServiceBand throws for a CLIENT actor")
    void should_throwForbidden_when_clientEnforcesEditMasterServiceBand() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        // B2 (cycle-2 audit) — same non-vacuity fix as the SALON_ADMIN test above: the master row
        // exists and belongs to salonId, so the CLIENT role is the only thing denying, not an
        // absent row. verifyNoInteractions(salonRepository) below still proves no ownership query
        // was even attempted.
        Master foreignMaster = masterRow(UUID.randomUUID(), salonId);
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(foreignMaster));
        lenient().when(masterRepository.existsByIdAndSalonId(masterId, salonId)).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_CLIENT"));

        assertThatThrownBy(() -> authorizationService.enforceCanEditMasterServiceBand(actorId, salonId, masterId))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(salonRepository);
    }

    @Test
    @DisplayName("Q6: enforceCanEditMasterServiceBand throws for an INDEPENDENT_MASTER on their "
            + "OWN row — the service-layer twin agrees with the SpEL gate's solo-master denial")
    void should_throwForbidden_when_independentMasterEnforcesEditOwnBand() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();

        Master master = masterRow(actorId, null);
        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_INDEPENDENT_MASTER"));

        assertThatThrownBy(() -> authorizationService.enforceCanEditMasterServiceBand(actorId, salonId, masterId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied");
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

    /**
     * B6 (2026-09-13 cycle-2 audit) — {@code enforceCanManageSalon(UUID, UUID)} takes the actor as
     * a parameter but resolves the actor's ROLE from {@code SecurityContextHolder}. A non-HTTP
     * caller running under principal A's context could therefore pass a different {@code actorId}
     * B and have <em>A's role</em> checked against <em>B's ownership</em>. Here BOTH users are
     * genuine SALON_OWNERs of the SAME salon, so the split identity is the only thing left to
     * refuse — removing the principal assertion turns this green.
     */
    @Test
    @DisplayName("B6: enforceCanManageSalon refuses an actorId that is not the authenticated "
            + "principal, even when that actor genuinely owns the salon")
    void should_throwForbidden_when_actorIdDiffersFromSecurityContextPrincipal() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        SecurityContextHolder.getContext().setAuthentication(mockAuth(userA, "ROLE_SALON_OWNER"));
        // Both A and B own the salon — the ownership predicate itself would say YES for either.
        lenient().when(salonRepository.existsByIdAndOwnerId(salonId, userA)).thenReturn(true);
        lenient().when(salonRepository.existsByIdAndOwnerId(salonId, userB)).thenReturn(true);

        assertThatThrownBy(() -> authorizationService.enforceCanManageSalon(userB, salonId))
                .as("A's context must never authorize a call made on B's behalf")
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("B6 non-vacuity: the SAME call SUCCEEDS once actorId IS the authenticated "
            + "principal — the guard rejects the identity mismatch, not the id-only overload")
    void should_notThrow_when_actorIdMatchesSecurityContextPrincipal() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();

        SecurityContextHolder.getContext().setAuthentication(mockAuth(ownerId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, ownerId)).thenReturn(true);

        assertThatCode(() -> authorizationService.enforceCanManageSalon(ownerId, salonId))
                .doesNotThrowAnyException();
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

    // ── enforceCanManageServiceDefinition (B14 service-layer guard, Phase 306 D3) ─

    @Test
    @DisplayName("enforceCanManageServiceDefinition does not throw when actor is the INDEPENDENT_MASTER owner of the service definition")
    void should_notThrow_when_actorOwnsServiceDefinition() {
        UUID actorId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(independentMasterOwnerAccess(actorId)));
        // A2 (cycle-3 audit): the guard now asserts actorId IS the authenticated principal on
        // BOTH branches, so even the INDEPENDENT_MASTER-owned branch needs a bound context.
        SecurityContextHolder.getContext()
                .setAuthentication(mockAuth(actorId, "ROLE_INDEPENDENT_MASTER"));

        assertThatCode(() -> authorizationService.enforceCanManageServiceDefinition(actorId, serviceDefId))
                .as("owner of the service definition must pass the B14 guard")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanManageServiceDefinition throws ForbiddenException when actor is NOT the INDEPENDENT_MASTER owner")
    void should_throwForbidden_when_actorIsNotServiceDefinitionOwner() {
        UUID actorId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();

        when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(independentMasterOwnerAccess(ownerId)));
        // actorId IS the principal here — so what denies is NOT the A2 identity assertion but the
        // ownership predicate, which is the property this test is about.
        SecurityContextHolder.getContext()
                .setAuthentication(mockAuth(actorId, "ROLE_INDEPENDENT_MASTER"));

        assertThatThrownBy(() -> authorizationService.enforceCanManageServiceDefinition(actorId, serviceDefId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied");
    }

    @Test
    @DisplayName("enforceCanManageServiceDefinition does not throw for a SALON_ADMIN of the owning salon (D3/D5 — DELETE's defense-in-depth)")
    void should_notThrow_when_salonAdminOfOwningSalonEnforces() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        UUID salonOwnerUserId = UUID.randomUUID();

        when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(salonOwnerAccess(salonOwnerUserId, salonId)));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));
        // enforceCanManageServiceDefinition(actorId, ...) has no Authentication parameter — the
        // SALON branch resolves the actor's role via hasManagementAccess(salonId, actorId), which
        // reads SecurityContextHolder (roleFromCurrentAuthentication), same as enforceCanManageSalon.
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_ADMIN"));

        assertThatCode(() -> authorizationService.enforceCanManageServiceDefinition(actorId, serviceDefId))
                .as("SALON_ADMIN of the owning salon must pass the B14 guard")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanManageServiceDefinition throws ForbiddenException for a SALON_ADMIN of a DIFFERENT salon")
    void should_throwForbidden_when_salonAdminOfDifferentSalonEnforces() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID otherSalonId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        UUID salonOwnerUserId = UUID.randomUUID();

        when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(salonOwnerAccess(salonOwnerUserId, salonId)));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(otherSalonId));
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_ADMIN"));

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
        SecurityContextHolder.getContext()
                .setAuthentication(mockAuth(actorId, "ROLE_INDEPENDENT_MASTER"));

        // Unknown id is treated as access-denied (403) — never a distinct 404 that would leak
        // existence (anti-bug §B/§D).
        assertThatThrownBy(() -> authorizationService.enforceCanManageServiceDefinition(actorId, missing))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Access denied");
    }

    /**
     * A2 (2026-09-13 cycle-3 audit) — the sibling instance of B6's split-identity hole, closed in
     * the same pass rather than left as a known survivor of the class. BOTH users are genuine
     * SALON_ADMINs of the SAME salon that owns the definition, so the ownership predicate says YES
     * for either and only the identity mismatch is left to refuse.
     */
    @Test
    @DisplayName("A2: enforceCanManageServiceDefinition refuses an actorId that is not the "
            + "authenticated principal, even when that actor genuinely administers the owning salon")
    void should_throwForbidden_when_serviceDefinitionEnforceActorIsNotTheAuthenticatedPrincipal() {
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        UUID salonOwnerUserId = UUID.randomUUID();

        SecurityContextHolder.getContext().setAuthentication(mockAuth(userA, "ROLE_SALON_ADMIN"));
        lenient().when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(salonOwnerAccess(salonOwnerUserId, salonId)));
        // Both A and B administer the owning salon — the predicate itself would allow either.
        lenient().when(userRepository.findSalonIdById(userA)).thenReturn(Optional.of(salonId));
        lenient().when(userRepository.findSalonIdById(userB)).thenReturn(Optional.of(salonId));

        assertThatThrownBy(() ->
                authorizationService.enforceCanManageServiceDefinition(userB, serviceDefId))
                .as("A's context must never authorize a definition mutation made on B's behalf")
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("A2 non-vacuity: the SAME enforceCanManageServiceDefinition call SUCCEEDS once "
            + "actorId IS the authenticated principal")
    void should_notThrow_when_serviceDefinitionEnforceActorIsTheAuthenticatedPrincipal() {
        UUID adminId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        UUID salonOwnerUserId = UUID.randomUUID();

        SecurityContextHolder.getContext().setAuthentication(mockAuth(adminId, "ROLE_SALON_ADMIN"));
        when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(salonOwnerAccess(salonOwnerUserId, salonId)));
        when(userRepository.findSalonIdById(adminId)).thenReturn(Optional.of(salonId));

        assertThatCode(() ->
                authorizationService.enforceCanManageServiceDefinition(adminId, serviceDefId))
                .doesNotThrowAnyException();
    }

    // ── Phase 345 catalogue gate — admin vs owner-performed definitions ─────────────────────
    // Architect decision 2026-10-06: a SALON_ADMIN cannot write a SALON definition with an
    // ACTIVE assignment on the owner's SALON_OWNER row (owner-only OR shared). The EXISTS runs
    // only for a SALON_ADMIN on a salon-scoped definition.

    @Test
    @DisplayName("Phase 345: enforceCanManageServiceDefinition refuses a SALON_ADMIN of the owning salon "
            + "when the definition is assigned to the owner's row")
    void should_throwForbidden_when_adminEnforcesOnOwnerPerformedDefinition() {
        UUID adminId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(mockAuth(adminId, "ROLE_SALON_ADMIN"));
        when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(salonOwnerAccess(UUID.randomUUID(), salonId)));
        when(userRepository.findSalonIdById(adminId)).thenReturn(Optional.of(salonId));
        when(masterServiceRepository.existsActiveOwnerRowAssignment(serviceDefId)).thenReturn(true);

        assertThatThrownBy(() -> authorizationService.enforceCanManageServiceDefinition(adminId, serviceDefId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(AuthorizationService.OWNER_PERFORMED_SERVICE_MESSAGE);
    }

    @Test
    @DisplayName("Phase 345: a SALON_ADMIN of the owning salon is still admitted when the "
            + "definition has no active owner-row assignment")
    void should_allowAdmin_when_definitionNotAssignedToOwnerRow() {
        UUID adminId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(mockAuth(adminId, "ROLE_SALON_ADMIN"));
        when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(salonOwnerAccess(UUID.randomUUID(), salonId)));
        when(userRepository.findSalonIdById(adminId)).thenReturn(Optional.of(salonId));
        when(masterServiceRepository.existsActiveOwnerRowAssignment(serviceDefId)).thenReturn(false);

        assertThatCode(() -> authorizationService.enforceCanManageServiceDefinition(adminId, serviceDefId))
                .doesNotThrowAnyException();
        verify(masterServiceRepository).existsActiveOwnerRowAssignment(serviceDefId);
    }

    @Test
    @DisplayName("Phase 345: the salon OWNER passes and the owner-row EXISTS is never issued")
    void should_allowOwnerWithoutExistsQuery_when_ownerManagesDefinition() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(mockAuth(ownerId, "ROLE_SALON_OWNER"));
        when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(salonOwnerAccess(ownerId, salonId)));

        assertThatCode(() -> authorizationService.enforceCanManageServiceDefinition(ownerId, serviceDefId))
                .doesNotThrowAnyException();
        verify(masterServiceRepository, never()).existsActiveOwnerRowAssignment(any());
    }

    @Test
    @DisplayName("Phase 345: an INDEPENDENT_MASTER definition never issues the owner-row EXISTS")
    void should_neverQueryOwnerRowAssignment_when_definitionIsIndependentMasterOwned() {
        UUID masterUserId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        SecurityContextHolder.getContext()
                .setAuthentication(mockAuth(masterUserId, "ROLE_INDEPENDENT_MASTER"));
        when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(independentMasterOwnerAccess(masterUserId)));

        assertThatCode(() -> authorizationService.enforceCanManageServiceDefinition(masterUserId, serviceDefId))
                .doesNotThrowAnyException();
        verifyNoInteractions(masterServiceRepository);
    }

    @Test
    @DisplayName("Phase 345: an admin who is NOT granted (foreign salon) is denied by the salon gate first — "
            + "the owner-row EXISTS is never issued")
    void should_notQueryOwnerRowAssignment_when_adminAlreadyDeniedBySalonGate() {
        UUID adminId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(mockAuth(adminId, "ROLE_SALON_ADMIN"));
        when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(salonOwnerAccess(UUID.randomUUID(), UUID.randomUUID())));
        when(userRepository.findSalonIdById(adminId)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThatThrownBy(() -> authorizationService.enforceCanManageServiceDefinition(adminId, serviceDefId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");
        verifyNoInteractions(masterServiceRepository);
    }

    // ── enforceCanManageServiceDefinition — coverage carried over from the removed SpEL twin ──
    // canManageServiceDefinition was deleted (dead since every service-definition write moved to a
    // role-only @PreAuthorize + this service-layer guard). The behaviours its tests pinned that no
    // enforce test above already covered are re-proved here against the surviving gate.

    @Test
    @DisplayName("enforceCanManageServiceDefinition admits the SALON_OWNER of the owning salon with "
            + "exactly ONE query (in-memory salonOwnerId compare, Phase 306 audit fix #1)")
    void should_notThrow_withSingleQuery_when_salonOwnerEnforces() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(mockAuth(ownerId, "ROLE_SALON_OWNER"));
        when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(salonOwnerAccess(ownerId, salonId)));

        assertThatCode(() -> authorizationService.enforceCanManageServiceDefinition(ownerId, serviceDefId))
                .doesNotThrowAnyException();

        verify(serviceRepository, times(1)).findOwnerUserId(serviceDefId);
        verify(salonRepository, never()).existsByIdAndOwnerId(any(), any());
    }

    @Test
    @DisplayName("enforceCanManageServiceDefinition refuses a SALON_OWNER who does not own the "
            + "definition's salon, without a salonRepository round-trip")
    void should_throwForbidden_when_differentSalonOwnerEnforces() {
        UUID actorId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(salonOwnerAccess(UUID.randomUUID(), UUID.randomUUID())));

        assertThatThrownBy(() -> authorizationService.enforceCanManageServiceDefinition(actorId, serviceDefId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");

        verify(salonRepository, never()).existsByIdAndOwnerId(any(), any());
    }

    @Test
    @DisplayName("enforceCanManageServiceDefinition refuses an orphaned definition (salonId=null AND "
            + "ownerUserId=null — Phase 306 audit fix #2): clean 403, never an NPE")
    void should_throwForbidden_when_serviceDefinitionIsOrphanedOnEnforce() {
        UUID actorId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(salonOwnerAccess(null, null)));

        assertThatThrownBy(() -> authorizationService.enforceCanManageServiceDefinition(actorId, serviceDefId))
                .as("an orphaned definition belongs to nobody — fail closed with 403, never a 500")
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");
    }

    @Test
    @DisplayName("enforceCanManageServiceDefinition refuses a SALON_MASTER on a salon definition "
            + "(Phase 306 D6 / Phase 311 D5 — the service layer never widens to SALON_MASTER)")
    void should_throwForbidden_when_salonMasterEnforcesOnSalonDefinition() {
        UUID masterUserId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        SecurityContextHolder.getContext()
                .setAuthentication(mockAuth(masterUserId, "ROLE_SALON_MASTER"));
        when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(salonOwnerAccess(UUID.randomUUID(), salonId)));

        assertThatThrownBy(() ->
                authorizationService.enforceCanManageServiceDefinition(masterUserId, serviceDefId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");

        verifyNoInteractions(salonRepository, userRepository, masterServiceRepository);
    }

    @Test
    @DisplayName("enforceCanManageServiceDefinition refuses a CLIENT on a salon definition")
    void should_throwForbidden_when_clientEnforcesOnSalonDefinition() {
        UUID clientId = UUID.randomUUID();
        UUID serviceDefId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(mockAuth(clientId, "ROLE_CLIENT"));
        when(serviceRepository.findOwnerUserId(serviceDefId))
                .thenReturn(Optional.of(salonOwnerAccess(UUID.randomUUID(), UUID.randomUUID())));

        assertThatThrownBy(() -> authorizationService.enforceCanManageServiceDefinition(clientId, serviceDefId))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");

        verifyNoInteractions(salonRepository, userRepository, masterServiceRepository);
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
                .thenReturn(Optional.of(new BookingViewAccess(UUID.randomUUID(), masterUserId, true, actorId)));

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
                .thenReturn(Optional.of(new BookingViewAccess(UUID.randomUUID(), masterUserId, true, ownerAId)));

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
                .thenReturn(Optional.of(new BookingViewAccess(UUID.randomUUID(), actorId, true, null)));

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
                .thenReturn(Optional.of(new BookingViewAccess(UUID.randomUUID(), UUID.randomUUID(), true, null)));

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

    /**
     * Builds a Booking whose master is an independent master owned by masterUserId.
     *
     * <p>{@code getMasterType()} and {@code isActive()} are {@code lenient()}: not every consumer of
     * this shared helper reaches them.
     */
    private Booking independentBooking(UUID masterUserId) {
        User masterUser = mock(User.class);
        lenient().when(masterUser.getId()).thenReturn(masterUserId);
        Master master = mock(Master.class);
        lenient().when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        lenient().when(master.isActive()).thenReturn(true);
        lenient().when(master.getUser()).thenReturn(masterUser);
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

    // ── enforceCanReviewClient / canProviderReviewClient (Phase 355 — owner/admin rate the client) ──
    //
    // Phase 355 reverses phase 320: at a salon only the booking's SALON_OWNER / SALON_ADMIN may rate
    // the client (same kernel as completion: hasProviderAuthorityOverBooking); SALON_MASTER never
    // may, even on a booking they performed. The independent master is unchanged.

    @Test
    @DisplayName("enforceCanReviewClient does not throw when the salon owner rates the client of a salon booking (phase 355)")
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
    @DisplayName("enforceCanReviewClient does not throw when the assigned SALON_ADMIN rates the client of a salon booking (phase 355)")
    void should_notThrow_when_assignedSalonAdminReviewsClientOfSalonBooking() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Booking booking = salonBooking(salonId);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_ADMIN"));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));

        assertThatCode(() -> authorizationService.enforceCanReviewClient(actorId, booking))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanReviewClient does not throw for an owner who is ALSO the performing master (phase 355)")
    void should_notThrow_when_ownerWhoPerformedTheBookingReviewsClient() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Booking booking = salonBooking(salonId);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);

        assertThatCode(() -> authorizationService.enforceCanReviewClient(actorId, booking))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanReviewClient THROWS for a SALON_MASTER, even on the booking they performed — "
            + "rejected by role before any repository call (phase 355, reverses 316/320)")
    void should_throwForbidden_when_salonMasterReviewsClientOfOwnPerformedBooking() {
        UUID actorId = UUID.randomUUID();
        Booking booking = mock(Booking.class);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_MASTER"));

        assertThatThrownBy(() -> authorizationService.enforceCanReviewClient(actorId, booking))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");
        verifyNoInteractions(salonRepository, userRepository);
    }

    @Test
    @DisplayName("enforceCanReviewClient THROWS for an owner/admin of a DIFFERENT salon (phase 355)")
    void should_throwForbidden_when_ownerOfAnotherSalonReviewsClient() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Booking booking = salonBooking(salonId);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(false);

        assertThatThrownBy(() -> authorizationService.enforceCanReviewClient(actorId, booking))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");
    }

    @Test
    @DisplayName("enforceCanReviewClient THROWS for a SALON_ADMIN assigned to a DIFFERENT salon (phase 355)")
    void should_throwForbidden_when_adminOfAnotherSalonReviewsClient() {
        UUID actorId = UUID.randomUUID();
        Booking booking = salonBooking(UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_ADMIN"));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThatThrownBy(() -> authorizationService.enforceCanReviewClient(actorId, booking))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("enforceCanReviewClient THROWS for a CLIENT (phase 355)")
    void should_throwForbidden_when_clientReviewsClient() {
        UUID actorId = UUID.randomUUID();
        Booking booking = mock(Booking.class);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_CLIENT"));

        assertThatThrownBy(() -> authorizationService.enforceCanReviewClient(actorId, booking))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("canProviderReviewClient fails closed (false) with no authenticated principal")
    void should_returnFalse_when_noAuthenticationForCanProviderReviewClient() {
        Booking booking = mock(Booking.class);

        assertThat(authorizationService.canProviderReviewClient(UUID.randomUUID(), booking)).isFalse();
    }

    @Test
    @DisplayName("enforceCanReviewClient does not throw when an independent master reviews the client of their own booking")
    void should_notThrow_when_independentMasterReviewsOwnBookingClient() {
        UUID actorId = UUID.randomUUID();
        Booking booking = independentBooking(actorId);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_INDEPENDENT_MASTER"));

        assertThatCode(() -> authorizationService.enforceCanReviewClient(actorId, booking))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanReviewClient throws ForbiddenException when an independent master reviews another master's booking client")
    void should_throwForbidden_when_independentMasterReviewsAnotherMastersBookingClient() {
        UUID actorId = UUID.randomUUID();
        Booking booking = independentBooking(UUID.randomUUID());
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_INDEPENDENT_MASTER"));

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

    // ── enforceCanManageAppointment(actor, appointment, memo) — cascade-scoped ─
    // ── memo overload (perf finding 2, 2026-09 re-audit) ───────────────────────

    @Test
    @DisplayName("enforceCanManageAppointment(actor, appointment, memo) — the SALON_OWNER "
            + "existsByIdAndOwnerId check is issued AT MOST ONCE across multiple calls sharing the "
            + "same memo instance: a salon-wide cascade calling this overload once per appointment-"
            + "visit must not re-issue the identical (salonId, actorId) EXISTS statement for every "
            + "visit — the bug the 2-arg overload's own per-visit AppointmentAuthorityKey dedup "
            + "cannot catch, since it only collapses duplicates WITHIN one appointment's items")
    void should_issueExistsByIdAndOwnerIdOnlyOnce_when_sameMemoSharedAcrossTwoAppointmentVisits() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID appointmentId1 = UUID.randomUUID();
        UUID appointmentId2 = UUID.randomUUID();
        UUID masterUserId1 = UUID.randomUUID();
        UUID masterUserId2 = UUID.randomUUID();

        when(bookingRepository.findAllCompletionAccessByAppointmentId(appointmentId1))
                .thenReturn(List.of(new BookingCompletionAccess(masterUserId1, salonId)));
        when(bookingRepository.findAllCompletionAccessByAppointmentId(appointmentId2))
                .thenReturn(List.of(new BookingCompletionAccess(masterUserId2, salonId)));
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);
        Map<AuthorizationService.MemoKey, Boolean> managementAccessMemo = new HashMap<>();

        assertThatCode(() -> {
            authorizationService.enforceCanManageAppointment(actorId, appointmentId1, managementAccessMemo);
            authorizationService.enforceCanManageAppointment(actorId, appointmentId2, managementAccessMemo);
        }).doesNotThrowAnyException();

        verify(salonRepository, times(1)).existsByIdAndOwnerId(salonId, actorId);
    }

    @Test
    @DisplayName("enforceCanManageAppointment(actor, appointment, memo) — still throws "
            + "ForbiddenException for an unauthorized actor: the memo speeds up a repeated TRUE "
            + "answer, it must never manufacture a false one")
    void should_stillThrowForbidden_when_actorUnauthorizedViaMemoOverload() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();

        when(bookingRepository.findAllCompletionAccessByAppointmentId(appointmentId))
                .thenReturn(List.of(new BookingCompletionAccess(masterUserId, salonId)));
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(false);

        assertThatThrownBy(() -> authorizationService.enforceCanManageAppointment(
                actorId, appointmentId, new HashMap<>()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("enforceCanManageAppointment(actor, appointment, memo) — security finding, "
            + "2026-09 re-audit, Finding B: two DIFFERENT actors sharing ONE memo instance must "
            + "never leak the first actor's cached ownership answer to the second actor for the "
            + "SAME salonId — the memo is keyed on (actorId, salonId) together, never on salonId "
            + "alone, so an unrelated actor's lookup can never hit a stale TRUE entry seeded by a "
            + "different actor")
    void should_notLeakOwnershipAnswer_when_twoActorsShareOneMemoInstance() {
        UUID actorA = UUID.randomUUID();
        UUID actorB = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID appointmentIdA = UUID.randomUUID();
        UUID appointmentIdB = UUID.randomUUID();
        UUID masterUserIdA = UUID.randomUUID();
        UUID masterUserIdB = UUID.randomUUID();
        Map<AuthorizationService.MemoKey, Boolean> managementAccessMemo = new HashMap<>();

        when(bookingRepository.findAllCompletionAccessByAppointmentId(appointmentIdA))
                .thenReturn(List.of(new BookingCompletionAccess(masterUserIdA, salonId)));
        when(bookingRepository.findAllCompletionAccessByAppointmentId(appointmentIdB))
                .thenReturn(List.of(new BookingCompletionAccess(masterUserIdB, salonId)));

        // actorA genuinely owns salonId — this call populates the shared memo with a TRUE entry.
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorA, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorA)).thenReturn(true);
        authorizationService.enforceCanManageAppointment(actorA, appointmentIdA, managementAccessMemo);

        // actorB does NOT own salonId. If the memo were keyed on bare salonId, actorB's lookup
        // would hit actorA's cached TRUE entry and wrongly succeed without ever calling
        // existsByIdAndOwnerId for actorB.
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorB, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorB)).thenReturn(false);

        assertThatThrownBy(() -> authorizationService.enforceCanManageAppointment(
                actorB, appointmentIdB, managementAccessMemo))
                .as("actorB does not own salonId — sharing actorA's memo instance must not let "
                        + "actorB inherit actorA's cached TRUE answer for the same salonId")
                .isInstanceOf(ForbiddenException.class);
        verify(salonRepository, times(1)).existsByIdAndOwnerId(salonId, actorB);
    }

    // ── enforceCanManageAppointments (actor, appointmentIds, memo) — batched ───
    // ── sibling of enforceCanManageAppointment (perf MEDIUM, phase 337 cycle-2 ─
    // ── audit): authorizes a WHOLE cascade of appointment-visits with a single ─
    // ── findAllCompletionAccessByAppointmentIds statement instead of one ───────
    // ── findAllCompletionAccessByAppointmentId statement per visit. ────────────

    @Test
    @DisplayName("enforceCanManageAppointments does not throw when every appointment in the "
            + "batch is authorized, and issues exactly ONE batched projection statement for the "
            + "whole set")
    void should_notThrow_when_everyAppointmentInBatchIsAuthorized() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID appointmentId1 = UUID.randomUUID();
        UUID appointmentId2 = UUID.randomUUID();
        UUID masterUserId1 = UUID.randomUUID();
        UUID masterUserId2 = UUID.randomUUID();
        List<UUID> appointmentIds = List.of(appointmentId1, appointmentId2);

        when(bookingRepository.findAllCompletionAccessByAppointmentIds(appointmentIds))
                .thenReturn(List.of(
                        new AppointmentCompletionAccess(appointmentId1, masterUserId1, salonId),
                        new AppointmentCompletionAccess(appointmentId2, masterUserId2, salonId)));
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);
        Map<AuthorizationService.MemoKey, Boolean> managementAccessMemo = new HashMap<>();

        assertThatCode(() -> authorizationService.enforceCanManageAppointments(
                actorId, appointmentIds, managementAccessMemo))
                .doesNotThrowAnyException();

        verify(bookingRepository, times(1)).findAllCompletionAccessByAppointmentIds(appointmentIds);
        verify(bookingRepository, never()).findAllCompletionAccessByAppointmentId(any());
        // the memo collapses the two visits' identical (actorId, salonId) ownership question to
        // ONE existsByIdAndOwnerId statement — same contract as the single-id memo overload.
        verify(salonRepository, times(1)).existsByIdAndOwnerId(salonId, actorId);
    }

    @Test
    @DisplayName("enforceCanManageAppointments throws ForbiddenException when ANY appointment in "
            + "the batch belongs to a salon the actor does not manage — a single foreign-salon "
            + "appointment fails the WHOLE batch, exactly as calling the single-id overload for "
            + "that one appointment would have")
    void should_throwForbidden_when_oneAppointmentInBatchIsForeignSalon() {
        UUID actorId = UUID.randomUUID();
        UUID ownedSalonId = UUID.randomUUID();
        UUID foreignSalonId = UUID.randomUUID();
        UUID appointmentIdOwned = UUID.randomUUID();
        UUID appointmentIdForeign = UUID.randomUUID();
        UUID masterUserIdOwned = UUID.randomUUID();
        UUID masterUserIdForeign = UUID.randomUUID();
        List<UUID> appointmentIds = List.of(appointmentIdOwned, appointmentIdForeign);

        when(bookingRepository.findAllCompletionAccessByAppointmentIds(appointmentIds))
                .thenReturn(List.of(
                        new AppointmentCompletionAccess(appointmentIdOwned, masterUserIdOwned, ownedSalonId),
                        new AppointmentCompletionAccess(appointmentIdForeign, masterUserIdForeign, foreignSalonId)));
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(ownedSalonId, actorId)).thenReturn(true);
        when(salonRepository.existsByIdAndOwnerId(foreignSalonId, actorId)).thenReturn(false);

        assertThatThrownBy(() -> authorizationService.enforceCanManageAppointments(
                actorId, appointmentIds, new HashMap<>()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("enforceCanManageAppointments throws ForbiddenException for an appointment id "
            + "that returns NO rows from the batched projection (missing/foreign/itemless) — never "
            + "silently dropped, same fail-closed contract as the single-id overload's "
            + "access.isEmpty() branch")
    void should_throwForbidden_when_oneAppointmentIdIsMissingFromBatchedResult() {
        UUID actorId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID appointmentIdMissing = UUID.randomUUID();
        UUID appointmentIdPresent = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();
        // appointmentIdMissing ordered FIRST so the failure is provably driven by the missing id
        // itself, never by a later foreign-salon row masking it.
        List<UUID> appointmentIds = List.of(appointmentIdMissing, appointmentIdPresent);

        when(bookingRepository.findAllCompletionAccessByAppointmentIds(appointmentIds))
                .thenReturn(List.of(new AppointmentCompletionAccess(appointmentIdPresent, masterUserId, salonId)));
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));

        assertThatThrownBy(() -> authorizationService.enforceCanManageAppointments(
                actorId, appointmentIds, new HashMap<>()))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(salonRepository);
    }

    @Test
    @DisplayName("enforceCanManageAppointments returns without any DB call for an empty "
            + "appointmentIds collection — never issues the batched projection statement for "
            + "nothing to authorize")
    void should_notQuery_when_appointmentIdsIsEmpty() {
        UUID actorId = UUID.randomUUID();

        assertThatCode(() -> authorizationService.enforceCanManageAppointments(
                actorId, List.of(), new HashMap<>()))
                .doesNotThrowAnyException();

        verifyNoInteractions(bookingRepository);
    }

    // ── enforceCanManageAppointments equivalence with the single-id overload ───
    // ── (perf MEDIUM, phase 337 cycle-2 audit, Finding d) — same actor, same ───
    // ── visit data, both methods must agree bit-for-bit: pass together or ──────
    // ── throw together. ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("enforceCanManageAppointments agrees with enforceCanManageAppointment(actor, "
            + "appointment) for a SALON_OWNER actor authorized over the visit's salon — both pass")
    void should_agreeWithSingleIdOverload_when_actorIsAuthorizedSalonOwner() {
        UUID actorId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();

        when(bookingRepository.findAllCompletionAccessByAppointmentId(appointmentId))
                .thenReturn(List.of(new BookingCompletionAccess(masterUserId, salonId)));
        when(bookingRepository.findAllCompletionAccessByAppointmentIds(List.of(appointmentId)))
                .thenReturn(List.of(new AppointmentCompletionAccess(appointmentId, masterUserId, salonId)));
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);

        assertThatCode(() -> authorizationService.enforceCanManageAppointment(actorId, appointmentId))
                .as("single-id overload")
                .doesNotThrowAnyException();
        assertThatCode(() -> authorizationService.enforceCanManageAppointments(
                actorId, List.of(appointmentId), new HashMap<>()))
                .as("batched overload — must agree with the single-id overload above")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanManageAppointments agrees with enforceCanManageAppointment(actor, "
            + "appointment) for a SALON_ADMIN assigned to the visit's own salon — both pass")
    void should_agreeWithSingleIdOverload_when_actorIsAuthorizedSalonAdmin() {
        UUID actorId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();

        when(bookingRepository.findAllCompletionAccessByAppointmentId(appointmentId))
                .thenReturn(List.of(new BookingCompletionAccess(masterUserId, salonId)));
        when(bookingRepository.findAllCompletionAccessByAppointmentIds(List.of(appointmentId)))
                .thenReturn(List.of(new AppointmentCompletionAccess(appointmentId, masterUserId, salonId)));
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_ADMIN"));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));

        assertThatCode(() -> authorizationService.enforceCanManageAppointment(actorId, appointmentId))
                .as("single-id overload")
                .doesNotThrowAnyException();
        assertThatCode(() -> authorizationService.enforceCanManageAppointments(
                actorId, List.of(appointmentId), new HashMap<>()))
                .as("batched overload — must agree with the single-id overload above")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanManageAppointments agrees with enforceCanManageAppointment(actor, "
            + "appointment) for a SALON_ADMIN assigned to a DIFFERENT salon than the visit's own — "
            + "both throw")
    void should_agreeWithSingleIdOverload_when_actorIsAdminOfAnotherSalon() {
        UUID actorId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID visitSalonId = UUID.randomUUID();
        UUID actorsOwnSalonId = UUID.randomUUID();
        UUID masterUserId = UUID.randomUUID();

        when(bookingRepository.findAllCompletionAccessByAppointmentId(appointmentId))
                .thenReturn(List.of(new BookingCompletionAccess(masterUserId, visitSalonId)));
        when(bookingRepository.findAllCompletionAccessByAppointmentIds(List.of(appointmentId)))
                .thenReturn(List.of(new AppointmentCompletionAccess(appointmentId, masterUserId, visitSalonId)));
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_ADMIN"));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(actorsOwnSalonId));

        assertThatThrownBy(() -> authorizationService.enforceCanManageAppointment(actorId, appointmentId))
                .as("single-id overload")
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> authorizationService.enforceCanManageAppointments(
                actorId, List.of(appointmentId), new HashMap<>()))
                .as("batched overload — must agree with the single-id overload above")
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("enforceCanManageAppointments agrees with enforceCanManageAppointment(actor, "
            + "appointment) for a SALON_MASTER actor (read-only, never a management-access role) — "
            + "both throw, even though the visit's own master account matches nothing here")
    void should_agreeWithSingleIdOverload_when_actorIsSalonMaster() {
        UUID actorId = UUID.randomUUID();
        UUID appointmentId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        UUID otherMasterUserId = UUID.randomUUID();

        when(bookingRepository.findAllCompletionAccessByAppointmentId(appointmentId))
                .thenReturn(List.of(new BookingCompletionAccess(otherMasterUserId, salonId)));
        when(bookingRepository.findAllCompletionAccessByAppointmentIds(List.of(appointmentId)))
                .thenReturn(List.of(new AppointmentCompletionAccess(appointmentId, otherMasterUserId, salonId)));
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_MASTER"));

        assertThatThrownBy(() -> authorizationService.enforceCanManageAppointment(actorId, appointmentId))
                .as("single-id overload")
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> authorizationService.enforceCanManageAppointments(
                actorId, List.of(appointmentId), new HashMap<>()))
                .as("batched overload — must agree with the single-id overload above")
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(salonRepository, userRepository);
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

    // ── canReviewClient (SpEL predicate; Phase 355 — owner/admin/independent, never SALON_MASTER) ──

    @Test
    @DisplayName("canReviewClient returns false without DB hit for a SALON_MASTER, even on a booking they performed (phase 355)")
    void should_returnFalseWithoutDbHit_when_salonMasterCallsCanReviewClient() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        boolean result = authorizationService.canReviewClient(auth, bookingId);

        assertThat(result).isFalse();
        verifyNoInteractions(bookingRepository, salonRepository, userRepository);
    }

    @Test
    @DisplayName("canReviewClient returns false without DB hit when actor has ROLE_CLIENT")
    void should_returnFalseWithoutDbHit_when_clientCallsCanReviewClient() {
        Authentication auth = mockAuth(UUID.randomUUID(), "ROLE_CLIENT");

        boolean result = authorizationService.canReviewClient(auth, UUID.randomUUID());

        assertThat(result).isFalse();
        verifyNoInteractions(bookingRepository);
    }

    @Test
    @DisplayName("canReviewClient returns TRUE for the owner of the booking's salon (phase 355)")
    void should_returnTrue_when_salonOwnerCallsCanReviewClient() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        when(bookingRepository.findReviewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingReviewAccess(UUID.randomUUID(), true, salonId)));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);

        boolean result = authorizationService.canReviewClient(mockAuth(actorId, "ROLE_SALON_OWNER"), bookingId);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("canReviewClient returns TRUE for the owner who is also the performing master (phase 355)")
    void should_returnTrue_when_ownerWhoPerformedCallsCanReviewClient() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        when(bookingRepository.findReviewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingReviewAccess(actorId, true, salonId)));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(true);

        assertThat(authorizationService.canReviewClient(mockAuth(actorId, "ROLE_SALON_OWNER"), bookingId)).isTrue();
    }

    @Test
    @DisplayName("canReviewClient returns TRUE for the SALON_ADMIN assigned to the booking's salon (phase 355)")
    void should_returnTrue_when_assignedSalonAdminCallsCanReviewClient() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        when(bookingRepository.findReviewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingReviewAccess(UUID.randomUUID(), true, salonId)));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(salonId));

        assertThat(authorizationService.canReviewClient(mockAuth(actorId, "ROLE_SALON_ADMIN"), bookingId)).isTrue();
    }

    @Test
    @DisplayName("canReviewClient returns FALSE for an owner of a DIFFERENT salon")
    void should_returnFalse_when_ownerOfAnotherSalonCallsCanReviewClient() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        when(bookingRepository.findReviewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingReviewAccess(UUID.randomUUID(), true, salonId)));
        when(salonRepository.existsByIdAndOwnerId(salonId, actorId)).thenReturn(false);

        assertThat(authorizationService.canReviewClient(mockAuth(actorId, "ROLE_SALON_OWNER"), bookingId)).isFalse();
    }

    @Test
    @DisplayName("canReviewClient returns FALSE for a SALON_ADMIN assigned to a DIFFERENT salon")
    void should_returnFalse_when_adminOfAnotherSalonCallsCanReviewClient() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        when(bookingRepository.findReviewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingReviewAccess(UUID.randomUUID(), true, UUID.randomUUID())));
        when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(UUID.randomUUID()));

        assertThat(authorizationService.canReviewClient(mockAuth(actorId, "ROLE_SALON_ADMIN"), bookingId)).isFalse();
    }

    @Test
    @DisplayName("canReviewClient returns true when an independent master reviews their own booking's client (salonId null)")
    void should_returnTrue_when_independentMasterCallsCanReviewClient() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        when(bookingRepository.findReviewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingReviewAccess(actorId, true, null)));

        assertThat(authorizationService.canReviewClient(mockAuth(actorId, "ROLE_INDEPENDENT_MASTER"), bookingId)).isTrue();
    }

    @Test
    @DisplayName("canReviewClient returns false when an independent master reviews ANOTHER master's booking")
    void should_returnFalse_when_independentMasterCallsCanReviewClientForOthersBooking() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        when(bookingRepository.findReviewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingReviewAccess(UUID.randomUUID(), true, null)));

        assertThat(authorizationService.canReviewClient(mockAuth(actorId, "ROLE_INDEPENDENT_MASTER"), bookingId)).isFalse();
    }

    @Test
    @DisplayName("canReviewClient returns FALSE for a DEACTIVATED independent master on their own booking (phase 355 liveness)")
    void should_returnFalse_when_deactivatedIndependentMasterCallsCanReviewClient() {
        UUID actorId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        when(bookingRepository.findReviewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingReviewAccess(actorId, false, null)));

        assertThat(authorizationService.canReviewClient(mockAuth(actorId, "ROLE_INDEPENDENT_MASTER"), bookingId)).isFalse();
    }

    @Test
    @DisplayName("canProviderReviewClient / enforceCanReviewClient deny a DEACTIVATED independent master (phase 355 liveness)")
    void should_deny_when_deactivatedIndependentMasterReviewsOwnBookingClient() {
        UUID actorId = UUID.randomUUID();
        Booking booking = independentBooking(actorId);
        Master deactivated = booking.getMaster();
        lenient().when(deactivated.isActive()).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_INDEPENDENT_MASTER"));

        assertThat(authorizationService.canProviderReviewClient(actorId, booking)).isFalse();
        assertThatThrownBy(() -> authorizationService.enforceCanReviewClient(actorId, booking))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("canReviewClient returns false when the booking does not exist (no existence oracle)")
    void should_returnFalse_when_reviewClientBookingMissing() {
        UUID bookingId = UUID.randomUUID();
        when(bookingRepository.findReviewAccessById(bookingId)).thenReturn(Optional.empty());

        assertThat(authorizationService.canReviewClient(mockAuth(UUID.randomUUID(), "ROLE_SALON_OWNER"), bookingId)).isFalse();
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
                .thenReturn(Optional.of(new BookingViewAccess(actorId, masterUserId, true, salonOwnerUserId)));

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
                .thenReturn(Optional.of(new BookingViewAccess(clientAId, masterUserId, true, salonOwnerUserId)));

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
                .thenReturn(Optional.of(new BookingViewAccess(clientUserId, actorId, true, salonOwnerUserId)));

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
                .thenReturn(Optional.of(new BookingViewAccess(clientUserId, otherMasterUserId, true, salonOwnerUserId)));

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
                .thenReturn(Optional.of(new BookingViewAccess(clientUserId, masterUserId, true, salonOwnerUserId)));

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
                .thenReturn(Optional.of(new BookingViewAccess(clientUserId, actorId, true, null)));

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
        // ACTIVE on purpose: the liveness conjunct leads the SALON_MASTER branch, so leaving
        // isActive() at Mockito's default false would deny for the wrong reason and stop this
        // test saying anything about the role/id conjuncts it exists to pin.
        when(master.isActive()).thenReturn(true);

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
        // ACTIVE on purpose — see the identical note in
        // should_throwForbidden_when_actorIsTheBookingsClientButAuthenticatedAsSalonMaster: the
        // denial under test here is the cross-master identity mismatch, not deactivation.
        when(otherMaster.isActive()).thenReturn(true);

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

    // ── enforceCanViewBooking / canViewBooking — the DEACTIVATED performer ────
    // The read half of the liveness posture phase 316 gave the WRITE grant. DELETE
    // /masters/{masterId} (MasterService#deactivateMasterInternal) flips masters.is_active and
    // NOTHING else: the staff users row, its SALON_MASTER role and its login all survive, because
    // AuthService gates on user.isActive(). Until this conjunct existed, a fired stylist with an
    // unexpired JWT kept full READ access to every booking they had performed — and
    // BookingDetailResponse carries the client's name, phone, price and service.
    //
    // The admit/deny PAIR is the point. Each denial below is accompanied by the identical fixture
    // with isActive() true, so a mutant that deletes the conjunct turns the denial red while the
    // control stays green, and a mutant that hard-denies every salon master turns the control red.
    // Neither half is meaningful alone.

    @Test
    @DisplayName("enforceCanViewBooking ADMITS an ACTIVE SALON_MASTER on the booking they perform "
            + "— the non-vacuity control for the liveness conjunct below")
    void should_notThrow_when_activeSalonMasterViewsOwnPerformedBooking() {
        UUID actorMasterUserId = UUID.randomUUID();

        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(UUID.randomUUID());

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        User masterUser = mock(User.class);
        when(masterUser.getId()).thenReturn(actorMasterUserId);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);
        when(master.getUser()).thenReturn(masterUser);
        when(master.isActive()).thenReturn(true);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);

        SecurityContextHolder.getContext()
                .setAuthentication(mockAuth(actorMasterUserId, "ROLE_SALON_MASTER"));

        assertThatCode(() -> authorizationService.enforceCanViewBooking(actorMasterUserId, booking))
                .as("an EMPLOYED salon master must keep reading their own bookings exactly as "
                        + "before — the liveness conjunct narrows the deactivated case only")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforceCanViewBooking throws ForbiddenException when a DEACTIVATED SALON_MASTER "
            + "views a booking they themselves performed while active — masters.is_active is a "
            + "conjunct of the view leg, not only of the phase-316 write leg")
    void should_throwForbidden_when_deactivatedSalonMasterViewsOwnPerformedBooking() {
        UUID actorMasterUserId = UUID.randomUUID();

        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(UUID.randomUUID());

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        User masterUser = mock(User.class);
        // Same identity the admitted control above uses: the ONLY difference between the two
        // fixtures is masters.is_active, so nothing but the conjunct can explain the two results.
        lenient().when(masterUser.getId()).thenReturn(actorMasterUserId);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);
        lenient().when(master.getUser()).thenReturn(masterUser);
        when(master.isActive()).thenReturn(false);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);

        SecurityContextHolder.getContext()
                .setAuthentication(mockAuth(actorMasterUserId, "ROLE_SALON_MASTER"));

        assertThatThrownBy(() -> authorizationService.enforceCanViewBooking(actorMasterUserId, booking))
                .as("a deactivated stylist keeps their login (AuthService gates on user.isActive(), "
                        + "which deactivateMasterInternal never touches) — the booking detail they "
                        + "would read carries a third party's name, phone and price")
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("Access denied");
    }

    @Test
    @DisplayName("enforceCanViewBooking still ADMITS the SALON_OWNER of a booking whose performing "
            + "master has been DEACTIVATED — the liveness conjunct is scoped to the SALON_MASTER "
            + "leg and must never reach isAuthorizedToManageBooking")
    void should_notThrow_when_salonOwnerViewsBookingOfDeactivatedMaster() {
        UUID ownerUserId = UUID.randomUUID();

        User salonOwner = mock(User.class);
        when(salonOwner.getId()).thenReturn(ownerUserId);

        Salon salon = mock(Salon.class);
        when(salon.getOwner()).thenReturn(salonOwner);

        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);
        // DEACTIVATED — and irrelevant to this actor: the owner is admitted by the manage leg,
        // which runs before the SALON_MASTER branch and carries no liveness term.
        lenient().when(master.isActive()).thenReturn(false);

        Booking booking = mock(Booking.class);
        when(booking.getMaster()).thenReturn(master);

        SecurityContextHolder.getContext()
                .setAuthentication(mockAuth(ownerUserId, "ROLE_SALON_OWNER"));

        assertThatCode(() -> authorizationService.enforceCanViewBooking(ownerUserId, booking))
                .as("an owner must keep reading the history of a stylist they just fired — that is "
                        + "their own salon's book, and the same asymmetry the completion projection pins "
                        + "for complete/decline/reschedule")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("canViewBooking (the SpEL twin) returns false for a DEACTIVATED SALON_MASTER on "
            + "their own booking, and true on the identical row with masterIsActive true")
    void should_returnFalse_when_deactivatedSalonMasterCallsCanViewBooking() {
        UUID bookingId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID clientUserId = UUID.randomUUID();
        UUID salonOwnerUserId = UUID.randomUUID();
        Authentication auth = mockAuth(actorId, "ROLE_SALON_MASTER");

        when(bookingRepository.findViewAccessById(bookingId))
                .thenReturn(Optional.of(new BookingViewAccess(clientUserId, actorId, false, salonOwnerUserId)))
                .thenReturn(Optional.of(new BookingViewAccess(clientUserId, actorId, true, salonOwnerUserId)));

        boolean deactivated = authorizationService.canViewBooking(auth, bookingId);
        boolean active = authorizationService.canViewBooking(auth, bookingId);

        assertThat(deactivated)
                .as("the projection twin must reach the same verdict enforceCanViewBooking reaches "
                        + "on the hydrated entity, or the SpEL gate and the service guard drift")
                .isFalse();
        assertThat(active)
                .as("non-vacuity on the SAME row — only masterIsActive differs between the two "
                        + "calls, so a hard-deny mutant cannot satisfy both assertions")
                .isTrue();
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

    // ── enforceOwnerMasterRowWritableByOwnerOnly — Phase 345 (2026-10-05 decision) ──────
    //
    // Audit fixes: the actor must BE the authenticated principal (asserted before the type check),
    // and the owner-row proof reads the memoised existsByIdAndOwnerId off the master's salon FK
    // instead of lazy-loading salon.owner. No request is bound in these unit tests, so the memo
    // degrades to a direct repository read and the verify(...) counts below stay exact.

    /** A SALON_OWNER-typed master row sitting in {@code salonId} — salon is an id-only proxy. */
    private static Master ownerRowIn(UUID salonId) {
        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);
        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(salon);
        return master;
    }

    @Test
    @DisplayName("enforceOwnerMasterRowWritableByOwnerOnly — the salon owner may write their own SALON_OWNER row "
            + "(one memoised ownership read, no salon.owner traversal)")
    void should_notThrow_when_ownerWritesOwnSalonOwnerRow() {
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Master master = ownerRowIn(salonId);
        when(salonRepository.existsByIdAndOwnerId(salonId, ownerId)).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(ownerId, "ROLE_SALON_OWNER"));

        authorizationService.enforceOwnerMasterRowWritableByOwnerOnly(ownerId, master);

        verify(salonRepository).existsByIdAndOwnerId(salonId, ownerId);
        verify(master.getSalon(), never()).getOwner();
        verifyNoInteractions(masterRepository, userRepository);
    }

    @Test
    @DisplayName("enforceOwnerMasterRowWritableByOwnerOnly — a SALON_ADMIN (any non-owner actor) is 403 on the owner's SALON_OWNER row")
    void should_throwForbidden_when_nonOwnerWritesSalonOwnerRow() {
        UUID adminId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        Master master = ownerRowIn(salonId);
        when(salonRepository.existsByIdAndOwnerId(salonId, adminId)).thenReturn(false);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(adminId, "ROLE_SALON_ADMIN"));

        assertThatThrownBy(() -> authorizationService.enforceOwnerMasterRowWritableByOwnerOnly(adminId, master))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    @DisplayName("enforceOwnerMasterRowWritableByOwnerOnly — a regular SALON_MASTER row is a no-op that "
            + "never touches the (lazy) salon — zero statements on the common write path")
    void should_notThrowNorReadSalon_when_actorWritesRegularSalonMasterRow() {
        UUID actorId = UUID.randomUUID();
        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_ADMIN"));

        authorizationService.enforceOwnerMasterRowWritableByOwnerOnly(actorId, master);

        verify(master, never()).getSalon();
        verifyNoInteractions(masterRepository, salonRepository, userRepository);
    }

    @Test
    @DisplayName("enforceOwnerMasterRowWritableByOwnerOnly — fails CLOSED on a salon-less SALON_OWNER row")
    void should_throwForbidden_when_salonOwnerRowHasNullSalon() {
        UUID actorId = UUID.randomUUID();
        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        when(master.getSalon()).thenReturn(null);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(actorId, "ROLE_SALON_OWNER"));

        assertThatThrownBy(() -> authorizationService.enforceOwnerMasterRowWritableByOwnerOnly(actorId, master))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(salonRepository);
    }

    @Test
    @DisplayName("enforceOwnerMasterRowWritableByOwnerOnly — refuses an actorId that is not the authenticated "
            + "principal, even when that actorId genuinely owns the row's salon")
    void should_throwForbidden_when_ownerRowActorIsNotTheAuthenticatedPrincipal() {
        UUID principalAdminId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID salonId = UUID.randomUUID();
        // Lenient: the principal assertion refuses before the row is inspected at all — the stubs
        // describe a row the impersonated owner genuinely owns, so only the identity check denies.
        Salon salon = mock(Salon.class);
        lenient().when(salon.getId()).thenReturn(salonId);
        Master master = mock(Master.class);
        lenient().when(master.getMasterType()).thenReturn(MasterType.SALON_OWNER);
        lenient().when(master.getSalon()).thenReturn(salon);
        lenient().when(salonRepository.existsByIdAndOwnerId(salonId, ownerId)).thenReturn(true);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(principalAdminId, "ROLE_SALON_ADMIN"));

        assertThatThrownBy(() -> authorizationService.enforceOwnerMasterRowWritableByOwnerOnly(ownerId, master))
                .as("an admin's context must never authorize an owner-row write made on the owner's behalf")
                .isInstanceOf(ForbiddenException.class);
        verify(master, never()).getMasterType();
        verifyNoInteractions(salonRepository);
    }

    @Test
    @DisplayName("enforceOwnerMasterRowWritableByOwnerOnly — principal assertion fires BEFORE the type check "
            + "(a mismatched actor is refused even on a regular SALON_MASTER row)")
    void should_throwForbidden_when_salonMasterRowActorIsNotTheAuthenticatedPrincipal() {
        Master master = mock(Master.class);
        lenient().when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        SecurityContextHolder.getContext().setAuthentication(mockAuth(UUID.randomUUID(), "ROLE_SALON_OWNER"));

        assertThatThrownBy(() -> authorizationService.enforceOwnerMasterRowWritableByOwnerOnly(UUID.randomUUID(), master))
                .isInstanceOf(ForbiddenException.class);
        verify(master, never()).getMasterType();
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
                .thenReturn(Optional.of(new BookingViewAccess(UUID.randomUUID(), masterUserId, true, actorId)));

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
                .thenReturn(Optional.of(new BookingViewAccess(clientUserId, masterUserId, true, actorId)));

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

    // ── filterBookingIdsWithProviderAuthority — page-scoped twin of canProviderReviewClient (Phase 355) ──

    private Booking pageBooking(UUID bookingId, Master master) {
        Booking b = mock(Booking.class);
        lenient().when(b.getId()).thenReturn(bookingId);
        when(b.getMaster()).thenReturn(master);
        return b;
    }

    private Master salonStaffMaster(UUID salonId, UUID userId) {
        Salon salon = mock(Salon.class);
        when(salon.getId()).thenReturn(salonId);
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(userId);
        Master master = mock(Master.class);
        when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);
        when(master.getSalon()).thenReturn(salon);
        lenient().when(master.getUser()).thenReturn(user);
        return master;
    }

    @Test
    @DisplayName("filterBookingIdsWithProviderAuthority gives the owner every row of their salons in ONE ownership query")
    void should_returnEveryOwnedRow_when_ownerFiltersPageInOneQuery() {
        UUID ownerId = UUID.randomUUID();
        UUID salonA = UUID.randomUUID();
        UUID salonB = UUID.randomUUID();
        UUID foreign = UUID.randomUUID();
        List<Booking> page = new java.util.ArrayList<>();
        Set<UUID> expected = new java.util.HashSet<>();
        for (int i = 0; i < 20; i++) {
            UUID id = UUID.randomUUID();
            page.add(pageBooking(id, salonStaffMaster(i % 2 == 0 ? salonA : salonB, UUID.randomUUID())));
            expected.add(id);
        }
        page.add(pageBooking(UUID.randomUUID(), salonStaffMaster(foreign, UUID.randomUUID())));
        when(salonRepository.findIdsByIdInAndOwnerId(any(), eq(ownerId))).thenReturn(List.of(salonA, salonB));

        Set<UUID> result = authorizationService.filterBookingIdsWithProviderAuthority(Role.SALON_OWNER, ownerId, page);

        assertThat(result).isEqualTo(expected);
        verify(salonRepository, times(1)).findIdsByIdInAndOwnerId(any(), eq(ownerId));
    }

    @Test
    @DisplayName("filterBookingIdsWithProviderAuthority gives the admin only rows of their assigned salon")
    void should_returnAssignedSalonRows_when_adminFiltersPage() {
        UUID adminId = UUID.randomUUID();
        UUID mine = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        UUID mineBooking = UUID.randomUUID();
        List<Booking> page = List.of(
                pageBooking(mineBooking, salonStaffMaster(mine, UUID.randomUUID())),
                pageBooking(UUID.randomUUID(), salonStaffMaster(other, UUID.randomUUID())));
        when(userRepository.findSalonIdById(adminId)).thenReturn(Optional.of(mine));

        Set<UUID> result = authorizationService.filterBookingIdsWithProviderAuthority(Role.SALON_ADMIN, adminId, page);

        assertThat(result).containsExactly(mineBooking);
        verify(salonRepository, never()).findIdsByIdInAndOwnerId(any(), any());
    }

    @Test
    @DisplayName("filterBookingIdsWithProviderAuthority (board overload) fails closed — empty set, no statement — with no SecurityContext")
    void should_returnEmptySet_when_noSecurityContext() {
        Set<UUID> result = authorizationService.filterBookingIdsWithProviderAuthority(
                UUID.randomUUID(), List.of(mock(Booking.class)));

        assertThat(result).isEmpty();
        verifyNoInteractions(salonRepository, userRepository);
    }

    @Test
    @DisplayName("filterBookingIdsWithProviderAuthority gives a SALON_MASTER nothing and issues no statement, even for own rows")
    void should_returnEmptyWithoutStatements_when_salonMasterFiltersPage() {
        UUID actorId = UUID.randomUUID();
        List<Booking> page = List.of(mock(Booking.class), mock(Booking.class));

        Set<UUID> result = authorizationService.filterBookingIdsWithProviderAuthority(Role.SALON_MASTER, actorId, page);

        assertThat(result).isEmpty();
        verifyNoInteractions(salonRepository, userRepository);
    }

    @Test
    @DisplayName("filterBookingIdsWithProviderAuthority keeps an independent master's own rows only and asks no repository")
    void should_returnOwnRowsOnly_when_independentMasterFiltersPage() {
        UUID actorId = UUID.randomUUID();
        UUID ownBooking = UUID.randomUUID();
        List<Booking> page = List.of(
                pageBooking(ownBooking, independentMaster(actorId)),
                pageBooking(UUID.randomUUID(), independentMaster(UUID.randomUUID())));

        Set<UUID> result = authorizationService.filterBookingIdsWithProviderAuthority(
                Role.INDEPENDENT_MASTER, actorId, page);

        assertThat(result).containsExactly(ownBooking);
        verifyNoInteractions(salonRepository, userRepository);
    }

    @Test
    @DisplayName("filterBookingIdsWithProviderAuthority drops a DEACTIVATED independent master's own rows (phase 355 liveness)")
    void should_dropOwnRows_when_deactivatedIndependentMasterFiltersPage() {
        UUID actorId = UUID.randomUUID();
        Master master = independentMaster(actorId);
        lenient().when(master.isActive()).thenReturn(false);
        List<Booking> page = List.of(pageBooking(UUID.randomUUID(), master));

        assertThat(authorizationService.filterBookingIdsWithProviderAuthority(
                Role.INDEPENDENT_MASTER, actorId, page)).isEmpty();
    }

    private Master independentMaster(UUID userId) {
        User user = mock(User.class);
        lenient().when(user.getId()).thenReturn(userId);
        Master master = mock(Master.class);
        lenient().when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        lenient().when(master.isActive()).thenReturn(true);
        lenient().when(master.getUser()).thenReturn(user);
        return master;
    }

    // ── Phase 343 — OWNER-only salon image gate (isOwnerOf) ─────────────────────────────────────

    @Test
    @DisplayName("Phase 343 TC-12: isOwnerOf is false for the SALON_ADMIN assigned to the salon — "
            + "ownership only, the admin arm of hasManagementAccess is never consulted")
    void should_returnFalse_when_isOwnerOfCalledByAssignedAdmin() {
        UUID salonId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        SecurityContextHolder.getContext().setAuthentication(mockAuth(adminId, "ROLE_SALON_ADMIN"));
        lenient().when(userRepository.findSalonIdById(adminId)).thenReturn(java.util.Optional.of(salonId));
        when(salonRepository.existsByIdAndOwnerId(salonId, adminId)).thenReturn(false);

        boolean owner = authorizationService.isOwnerOf(mockAuth(adminId, "ROLE_SALON_ADMIN"), salonId);

        assertThat(owner).isFalse();
        verify(userRepository, never()).findSalonIdById(any());
    }

    @Test
    @DisplayName("Phase 343: isOwnerOf(Authentication, salonId) delegates to the ownership query with the principal id")
    void should_returnOwnership_when_isOwnerOfCalledWithAuthentication() {
        UUID salonId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        when(salonRepository.existsByIdAndOwnerId(salonId, ownerId)).thenReturn(true);

        assertThat(authorizationService.isOwnerOf(mockAuth(ownerId, "ROLE_SALON_OWNER"), salonId)).isTrue();
    }

    @Test
    @DisplayName("Phase 343: isOwnerOf(Authentication, salonId) is false (never a throw, never a query) for a "
            + "missing or non-UUID principal")
    void should_returnFalse_when_isOwnerOfPrincipalMalformed() {
        UUID salonId = UUID.randomUUID();
        var noDetails = new UsernamePasswordAuthenticationToken("u", null,
                List.of(new SimpleGrantedAuthority("ROLE_SALON_OWNER")));
        noDetails.setDetails("not-a-uuid");

        assertThat(authorizationService.isOwnerOf((Authentication) null, salonId)).isFalse();
        assertThat(authorizationService.isOwnerOf(noDetails, salonId)).isFalse();
        verifyNoInteractions(salonRepository);
    }

}
