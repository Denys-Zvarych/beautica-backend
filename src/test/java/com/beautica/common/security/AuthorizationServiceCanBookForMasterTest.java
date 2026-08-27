package com.beautica.common.security;

import com.beautica.booking.dto.StaffBookingScope;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.service.StaffBookingScopeResolver;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 22.4 — the three-role authorization matrix behind
 * {@code POST /masters/&#123;masterId&#125;/bookings}, pinned row by row on
 * {@link AuthorizationService#canBookForMaster}.
 *
 * <p>Kept in its own class rather than appended to the 2 000-line {@code AuthorizationServiceTest}:
 * the matrix is the deliverable of this phase and reads as one table here.
 *
 * <p><b>The invariant that is easy to get wrong.</b> The predicate splits on the TARGET's
 * {@code masterType}, not on the caller's role (amendment A3). Two rows below exist only to pin
 * that: a {@code SALON_OWNER} cannot reach an independent master through the salon branch, and an
 * {@code INDEPENDENT_MASTER} cannot reach a salon master through the identity branch.
 *
 * <p><b>And the one that leaks if forgotten:</b> an unknown, inactive or closed-salon master is
 * {@code false} — a 403 — never a 404, so {@code masterId} cannot be probed for existence.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorizationService#canBookForMaster — the staff-booking authorization matrix")
class AuthorizationServiceCanBookForMasterTest {

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

    /**
     * A REAL instance, not a mock. In production it is a plain singleton {@code @Component} that
     * keeps its one entry in the CURRENT REQUEST's attributes — deliberately <b>not</b> a
     * {@code @RequestScope} bean, whose CGLIB proxy would throw {@code IllegalStateException: No
     * thread-bound request found} on the off-request call paths {@code hasManagementAccess} also
     * serves (see {@link ActorSalonAssignmentMemo}'s "Why request ATTRIBUTES" section). The gate and
     * the handler therefore share the memo through the bound request, not through bean scope, which
     * is why {@link RequestScopedSalonAssignmentMemo} below has to bind a request before its
     * assertions mean anything — and why the tests outside it, which bind none, exercise the
     * uncached fall-through.
     */
    @Spy
    private ActorSalonAssignmentMemo actorSalonAssignmentMemo = new ActorSalonAssignmentMemo();

    @InjectMocks
    private AuthorizationService authorizationService;

    private final UUID callerId = UUID.randomUUID();
    private final UUID masterId = UUID.randomUUID();
    private final UUID salonId = UUID.randomUUID();

    // ════════════════════════════════════════════════════════════════════════════════
    // Row 1-2 — SALON_OWNER / SALON_ADMIN may book any master of the salon they manage
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Salon-bound target")
    class SalonBoundTarget {

        @Test
        @DisplayName("SALON_OWNER of that master's salon → allowed")
        void should_allow_when_callerOwnsTheMastersSalon() {
            salonMaster(salonId);
            when(salonRepository.existsByIdAndOwnerId(salonId, callerId)).thenReturn(true);

            assertThat(authorizationService.canBookForMaster(authAs("SALON_OWNER"), masterId)).isTrue();
        }

        @Test
        @DisplayName("SALON_OWNER of a DIFFERENT salon → denied (403, and no 404 oracle)")
        void should_deny_when_callerOwnsAnotherSalon() {
            salonMaster(salonId);
            when(salonRepository.existsByIdAndOwnerId(salonId, callerId)).thenReturn(false);

            assertThat(authorizationService.canBookForMaster(authAs("SALON_OWNER"), masterId)).isFalse();
        }

        @Test
        @DisplayName("SALON_ADMIN assigned to that salon → allowed (admin = owner, product-confirmed)")
        void should_allow_when_callerAdministersTheMastersSalon() {
            salonMaster(salonId);
            when(userRepository.findSalonIdById(callerId)).thenReturn(Optional.of(salonId));

            assertThat(authorizationService.canBookForMaster(authAs("SALON_ADMIN"), masterId)).isTrue();
        }

        @Test
        @DisplayName("SALON_ADMIN assigned to another salon → denied")
        void should_deny_when_callerAdministersAnotherSalon() {
            salonMaster(salonId);
            when(userRepository.findSalonIdById(callerId)).thenReturn(Optional.of(UUID.randomUUID()));

            assertThat(authorizationService.canBookForMaster(authAs("SALON_ADMIN"), masterId)).isFalse();
        }

        /**
         * The identity branch must not leak into the salon branch: an independent master holds no
         * management access over anyone else's salon, so {@code hasManagementAccess} answers false
         * for their role unconditionally.
         */
        @Test
        @DisplayName("INDEPENDENT_MASTER targeting a salon master → denied")
        void should_deny_when_independentMasterTargetsASalonMaster() {
            salonMaster(salonId);

            assertThat(authorizationService.canBookForMaster(authAs("INDEPENDENT_MASTER"), masterId))
                    .isFalse();
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Row 3 — INDEPENDENT_MASTER may book ONLY themselves. The phase's key assertion.
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Independent target")
    class IndependentTarget {

        @Test
        @DisplayName("INDEPENDENT_MASTER booking their OWN master profile → allowed")
        void should_allow_when_independentMasterBooksThemselves() {
            independentMaster(callerId);

            assertThat(authorizationService.canBookForMaster(authAs("INDEPENDENT_MASTER"), masterId))
                    .isTrue();
        }

        /**
         * The self-booking constraint, and the single most important assertion in this phase
         * (amendment A6): the target is another independent master, everything else is identical.
         */
        @Test
        @DisplayName("INDEPENDENT_MASTER booking ANOTHER independent master → denied")
        void should_deny_when_independentMasterBooksSomeoneElse() {
            independentMaster(UUID.randomUUID());

            assertThat(authorizationService.canBookForMaster(authAs("INDEPENDENT_MASTER"), masterId))
                    .isFalse();
        }

        /**
         * The branch keys on {@code masterType}, not on the caller's role — so a salon owner cannot
         * borrow the salon branch to reach a salon-less independent master. No salon lookup is even
         * attempted.
         */
        @Test
        @DisplayName("SALON_OWNER targeting an independent master → denied, with no salon lookup")
        void should_deny_when_salonOwnerTargetsAnIndependentMaster() {
            independentMaster(UUID.randomUUID());

            assertThat(authorizationService.canBookForMaster(authAs("SALON_OWNER"), masterId)).isFalse();
            verify(salonRepository, never()).existsByIdAndOwnerId(salonId, callerId);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Rows 4-5 — the two roles that are always denied, plus the no-oracle rows
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Always denied, and never an existence oracle")
    class DeniedAndOpaque {

        @Test
        @DisplayName("SALON_MASTER → denied with no DB round-trip, even for their own profile")
        void should_denyWithoutDbHit_when_callerIsSalonMaster() {
            assertThat(authorizationService.canBookForMaster(authAs("SALON_MASTER"), masterId)).isFalse();

            verify(masterRepository, never()).findByIdWithUserAndSalon(masterId);
        }

        @Test
        @DisplayName("CLIENT → denied with no DB round-trip")
        void should_denyWithoutDbHit_when_callerIsClient() {
            assertThat(authorizationService.canBookForMaster(authAs("CLIENT"), masterId)).isFalse();

            verify(masterRepository, never()).findByIdWithUserAndSalon(masterId);
        }

        @Test
        @DisplayName("unknown masterId → denied (403), NOT a 404 — the id must not be probeable")
        void should_deny_when_masterDoesNotExist() {
            when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.empty());

            assertThat(authorizationService.canBookForMaster(authAs("SALON_OWNER"), masterId)).isFalse();
        }

        @Test
        @DisplayName("inactive master → denied, indistinguishably from unknown")
        void should_deny_when_masterIsInactive() {
            Master master = mock(Master.class);
            when(master.isActive()).thenReturn(false);
            when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

            assertThat(authorizationService.canBookForMaster(authAs("SALON_OWNER"), masterId)).isFalse();
        }

        /**
         * {@code MasterBookability}, not a bare {@code isActive()} check: deactivating a salon does
         * not cascade to {@code masters.is_active}, so an owner must not be able to keep booking
         * into a salon they have closed. Same rule the service's own 404 filter applies.
         */
        @Test
        @DisplayName("master of a CLOSED salon → denied, even for that salon's own owner")
        void should_deny_when_theMastersSalonIsDeactivated() {
            Salon salon = mock(Salon.class);
            lenient().when(salon.getId()).thenReturn(salonId);
            when(salon.isActive()).thenReturn(false);

            Master master = mock(Master.class);
            when(master.isActive()).thenReturn(true);
            when(master.getSalon()).thenReturn(salon);
            when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));

            assertThat(authorizationService.canBookForMaster(authAs("SALON_OWNER"), masterId)).isFalse();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // The gate and the resolver share ONE users.salon_id read (perf MEDIUM, 2026-08-18)
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * A {@code SALON_ADMIN} {@code POST /masters/&#123;masterId&#125;/bookings} used to issue the
     * byte-identical {@code findSalonIdById(actorId)} twice: once here in the {@code @PreAuthorize}
     * gate, once in {@code StaffBookingScopeResolver} in the handler. Both now read through the
     * request-scoped {@link ActorSalonAssignmentMemo}, which in production is one bean instance per
     * request — modelled here by handing the SAME instance to both collaborators.
     *
     * <p>This is the {@code Master} double-read's opposite: that one is deliberate and must stay
     * (TOCTOU narrowing across a detachment boundary — see
     * {@link AuthorizationService#canBookForMaster}'s javadoc); this one is a request-immutable
     * scalar with no such role.
     */
    @Nested
    @DisplayName("Request-scoped salon-assignment memo")
    class RequestScopedSalonAssignmentMemo {

        /**
         * The memo holds its entry in the CURRENT request's attributes, so a unit test must bind one
         * — exactly as {@code DispatcherServlet} does in production. Binding it here rather than in
         * the outer class is deliberate: every other test in this file runs with NO bound request,
         * which is the memo's fail-soft path, so those tests also prove the memo cannot change any
         * existing behaviour.
         */
        @BeforeEach
        void bindRequest() {
            RequestContextHolder.setRequestAttributes(
                    new ServletRequestAttributes(new MockHttpServletRequest()));
        }

        @AfterEach
        void unbindRequest() {
            RequestContextHolder.resetRequestAttributes();
        }

        private StaffBookingScopeResolver resolverSharingTheMemo() {
            return new StaffBookingScopeResolver(
                    salonRepository, userRepository, masterRepository, actorSalonAssignmentMemo);
        }

        @Test
        @DisplayName("a full authorize+resolve cycle for one SALON_ADMIN reads users.salon_id ONCE")
        void should_readTheAdminsSalonAssignmentOnce_when_gateAndResolverBothRunForOneRequest() {
            salonMaster(salonId);
            when(userRepository.findSalonIdById(callerId)).thenReturn(Optional.of(salonId));

            boolean authorized =
                    authorizationService.canBookForMaster(authAs("SALON_ADMIN"), masterId);
            StaffBookingScope scope =
                    resolverSharingTheMemo().resolve(authAs("SALON_ADMIN"), masterId);

            assertThat(authorized).isTrue();
            assertThat(scope).isEqualTo(new StaffBookingScope.InSalon(salonId));
            verify(userRepository, times(1)).findSalonIdById(callerId);
        }

        /**
         * The memo is keyed on the actor, not merely "first read wins" — so it can never hand one
         * actor's salon assignment to another. Two distinct admins in one instance's lifetime must
         * produce two reads.
         */
        @Test
        @DisplayName("a second, DIFFERENT actor is never served the first actor's assignment")
        void should_reloadTheAssignment_when_aDifferentActorIsResolved() {
            UUID otherAdminId = UUID.randomUUID();
            UUID otherSalonId = UUID.randomUUID();
            when(userRepository.findSalonIdById(callerId)).thenReturn(Optional.of(salonId));
            when(userRepository.findSalonIdById(otherAdminId)).thenReturn(Optional.of(otherSalonId));
            StaffBookingScopeResolver resolver = resolverSharingTheMemo();

            StaffBookingScope first = resolver.resolve(authAs("SALON_ADMIN"), masterId);
            StaffBookingScope second = resolver.resolve(adminAuth(otherAdminId), masterId);

            assertThat(first).isEqualTo(new StaffBookingScope.InSalon(salonId));
            assertThat(second).isEqualTo(new StaffBookingScope.InSalon(otherSalonId));
            verify(userRepository, times(1)).findSalonIdById(callerId);
            verify(userRepository, times(1)).findSalonIdById(otherAdminId);
        }

        /**
         * <b>The miss path memoises the ABSENCE too, and nothing pinned that.</b> An admin with no
         * {@code users.salon_id} makes the loader return {@code Optional.empty()}, and the memo
         * stores that empty result as a real entry — so the second consumer in the same request
         * still issues zero queries. The obvious "tidier" rewrite ({@code loaded.ifPresent(v ->
         * setAttribute(...))}) silently reinstates the duplicate read for exactly the callers who
         * are about to be rejected, and every existing test in this class stays green because they
         * all stub a PRESENT assignment.
         *
         * <p>Both consumers are exercised for real: the gate answers false, then the resolver throws
         * 403 — the production sequence for this actor. One read total.
         *
         * <p>Mutation-verified: guard the {@code setAttribute} in
         * {@code ActorSalonAssignmentMemo#salonIdOf} on {@code loaded.isPresent()} and this test —
         * and only this test — goes red at {@code times(1)}.
         */
        @Test
        @DisplayName("an admin with NO assignment is still read only once — the empty result is memoised")
        void should_memoiseTheEmptyAssignment_when_theAdminHasNoSalonAtAll() {
            salonMaster(salonId);
            when(userRepository.findSalonIdById(callerId)).thenReturn(Optional.empty());
            StaffBookingScopeResolver resolver = resolverSharingTheMemo();

            boolean authorized = authorizationService.canBookForMaster(authAs("SALON_ADMIN"), masterId);

            assertThat(authorized).as("an unassigned admin manages no salon").isFalse();
            assertThatThrownBy(() -> resolver.resolve(authAs("SALON_ADMIN"), masterId))
                    .isInstanceOf(ForbiddenException.class);
            verify(userRepository, times(1)).findSalonIdById(callerId);
        }

        /**
         * The memo is a pure optimisation and must never be able to turn a working call into a 500.
         * {@code hasManagementAccess} is reached off-request too (direct service calls with a primed
         * {@code SecurityContext} — several ITs do exactly that), which is why the memo degrades to
         * an uncached read instead of being a {@code @RequestScope} proxy that would throw
         * {@code IllegalStateException: No thread-bound request found}.
         */
        @Test
        @DisplayName("with no request bound, the memo degrades to a plain read rather than throwing")
        void should_readUncached_when_noRequestIsBoundToTheThread() {
            RequestContextHolder.resetRequestAttributes();
            when(userRepository.findSalonIdById(callerId)).thenReturn(Optional.of(salonId));
            StaffBookingScopeResolver resolver = resolverSharingTheMemo();

            StaffBookingScope first = resolver.resolve(authAs("SALON_ADMIN"), masterId);
            StaffBookingScope second = resolver.resolve(authAs("SALON_ADMIN"), masterId);

            assertThat(first).isEqualTo(new StaffBookingScope.InSalon(salonId));
            assertThat(second).isEqualTo(first);
            verify(userRepository, times(2)).findSalonIdById(callerId);
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────────────────

    /** An active, salon-bound master at an open salon. */
    private void salonMaster(UUID salonId) {
        Salon salon = mock(Salon.class);
        when(salon.isActive()).thenReturn(true);
        lenient().when(salon.getId()).thenReturn(salonId);

        Master master = mock(Master.class);
        when(master.isActive()).thenReturn(true);
        when(master.getSalon()).thenReturn(salon);
        lenient().when(master.getMasterType()).thenReturn(MasterType.SALON_MASTER);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
    }

    /** An active independent master owned by {@code masterUserId}, with no salon. */
    private void independentMaster(UUID masterUserId) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(masterUserId);

        Master master = mock(Master.class);
        when(master.isActive()).thenReturn(true);
        when(master.getSalon()).thenReturn(null);
        when(master.getMasterType()).thenReturn(MasterType.INDEPENDENT_MASTER);
        when(master.getUser()).thenReturn(user);

        when(masterRepository.findByIdWithUserAndSalon(masterId)).thenReturn(Optional.of(master));
    }

    /** A {@code SALON_ADMIN} token for an actor other than {@link #callerId}. */
    private Authentication adminAuth(UUID actorId) {
        var token = new UsernamePasswordAuthenticationToken(
                "other-admin@beautica.test", null,
                List.of(new SimpleGrantedAuthority("ROLE_SALON_ADMIN")));
        token.setDetails(actorId);
        return token;
    }

    /** Mirrors {@code JwtAuthenticationFilter}: the user id lives in {@code details}. */
    private Authentication authAs(String role) {
        var token = new UsernamePasswordAuthenticationToken(
                "caller@beautica.test", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        token.setDetails(callerId);
        return token;
    }
}
