package com.beautica.booking.service;

import com.beautica.auth.Role;
import com.beautica.booking.dto.StaffBookingScope;
import com.beautica.common.exception.ForbiddenException;
import com.beautica.common.security.ActorSalonAssignmentMemo;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Phase 22.4 — {@link StaffBookingScopeResolver}, the class that carries security's ONE hard
 * precondition on this phase.
 *
 * <h2>What this suite exists to falsify</h2>
 * {@code StaffBookingService#assertMasterInScope}'s {@code InSalon} arm compares the loaded master's
 * salon against {@code InSalon.salonId}. If 22.4 populated that id from {@code master.getSalon()},
 * the arm would compare a value against itself — a tautology that can never fail — and the whole
 * salon path's trust would rest on the {@code @PreAuthorize} annotation alone.
 *
 * <p>Two independent assertions pin the fix, and BOTH go red under the mutation
 * "derive the salon from the target master instead of the caller":
 * <ol>
 *   <li><b>Value</b> — with a caller-managed salon A and a target master in salon B, the resolver
 *       emits {@code InSalon(A)}. The mutation emits {@code InSalon(B)}.</li>
 *   <li><b>Structure</b> — on the {@code SALON_ADMIN} and single-salon {@code SALON_OWNER} branches
 *       the {@link MasterRepository} is never touched at all. The mutation cannot read the master's
 *       salon without touching it, so {@code verifyNoInteractions} goes red however the value
 *       happens to land.</li>
 * </ol>
 * The complementary half — that the arm being fed can still FAIL — is
 * {@code StaffBookingServiceTest#should_reject403_when_masterBelongsToAnotherSalon}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.STRICT_STUBS)
@DisplayName("StaffBookingScopeResolver — the scope is the CALLER's authority, never the target's attribute")
class StaffBookingScopeResolverTest {

    private static final UUID CALLER_SALON = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID FOREIGN_SALON = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private SalonRepository salonRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private MasterRepository masterRepository;

    /**
     * A REAL instance, not a mock: the request-scoped memo the admin branch reads
     * {@code users.salon_id} through is transparent on a first read, so every
     * {@code userRepository} stub and {@code verifyNoInteractions} assertion below still means what
     * it says. See {@code AuthorizationServiceCanBookForMasterTest} for the cross-class test that
     * pins the memo actually collapsing the gate's read and this resolver's into one.
     */
    @Spy
    private ActorSalonAssignmentMemo actorSalonAssignmentMemo = new ActorSalonAssignmentMemo();

    @InjectMocks
    private StaffBookingScopeResolver resolver;

    private final UUID actorId = UUID.randomUUID();
    private final UUID masterId = UUID.randomUUID();

    // ════════════════════════════════════════════════════════════════════════════════
    // THE FALSIFICATION SET — mutate the derivation to master.getSalon() and these go red
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("InSalon is derived from the caller (mutation-verified)")
    class InSalonProvenance {

        /**
         * The owner manages salon A. The target master works at salon B — a fact this resolver is
         * never told and must never consult. The emitted scope must be A, so that 22.2's
         * {@code InSalon} arm has something real to compare B against and can reject.
         *
         * <p>Mutation-verified: replacing the derivation with the target master's salon makes this
         * assert {@code InSalon(B)} and fail.
         */
        @Test
        @DisplayName("a SALON_OWNER gets their OWN salon, not the salon the target master works at")
        void should_emitTheCallersSalon_when_targetMasterBelongsElsewhere() {
            when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId))
                    .thenReturn(List.of(CALLER_SALON));

            StaffBookingScope scope = resolver.resolve(authAs(Role.SALON_OWNER), masterId);

            assertThat(scope).isEqualTo(new StaffBookingScope.InSalon(CALLER_SALON));
            assertThat(scope)
                    .as("the target master's salon must never be able to become the scope")
                    .isNotEqualTo(new StaffBookingScope.InSalon(FOREIGN_SALON));
        }

        /**
         * The structural half of the same proof: the single-salon owner branch resolves without the
         * target master being read AT ALL. A derivation that consults the master cannot satisfy
         * this, whatever value it produces.
         */
        @Test
        @DisplayName("a single-salon SALON_OWNER resolves without reading the target master at all")
        void should_neverReadTheTargetMaster_when_ownerManagesExactlyOneSalon() {
            when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId))
                    .thenReturn(List.of(CALLER_SALON));

            resolver.resolve(authAs(Role.SALON_OWNER), masterId);

            verifyNoInteractions(masterRepository);
        }

        /**
         * The admin branch reads {@code users.salon_id} — the admin's own assignment — and is
         * unambiguous by construction, so it never reads the master either.
         */
        @Test
        @DisplayName("a SALON_ADMIN gets their assigned salon, without reading the target master")
        void should_emitTheAssignedSalon_when_callerIsSalonAdmin() {
            when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.of(CALLER_SALON));

            StaffBookingScope scope = resolver.resolve(authAs(Role.SALON_ADMIN), masterId);

            assertThat(scope).isEqualTo(new StaffBookingScope.InSalon(CALLER_SALON));
            verifyNoInteractions(masterRepository);
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // The multi-salon owner — the only branch that reads the master, and only as a selector
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Multi-salon owner")
    class MultiSalonOwner {

        @Test
        @DisplayName("selects the owned salon the master actually works at")
        void should_selectTheOwnedSalonEmployingTheMaster_when_ownerHasSeveral() {
            when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId))
                    .thenReturn(List.of(CALLER_SALON, FOREIGN_SALON));
            when(masterRepository.findSalonIdByIdAndSalonOwnerId(masterId, actorId))
                    .thenReturn(Optional.of(FOREIGN_SALON));

            StaffBookingScope scope = resolver.resolve(authAs(Role.SALON_OWNER), masterId);

            assertThat(scope).isEqualTo(new StaffBookingScope.InSalon(FOREIGN_SALON));
        }

        /**
         * The owner-scoped finder returns empty for a master at a salon the caller does not own —
         * the ownership predicate lives inside the query — so no scope can be minted for a foreign
         * master even on the one branch that consults him.
         */
        @Test
        @DisplayName("rejects with 403 when the master works at no salon this owner owns")
        void should_reject403_when_multiSalonOwnerTargetsAForeignMaster() {
            when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId))
                    .thenReturn(List.of(CALLER_SALON, FOREIGN_SALON));
            when(masterRepository.findSalonIdByIdAndSalonOwnerId(masterId, actorId))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> resolver.resolve(authAs(Role.SALON_OWNER), masterId))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Access denied");
        }

        /**
         * <b>The belt behind the braces, and until now unpinned.</b> {@code ownedSalonId} does not
         * trust {@code findSalonIdByIdAndSalonOwnerId} on its own: it re-checks the returned id
         * against the actor-keyed set with {@code .filter(ownedSalonIds::contains)}. Every other
         * test on this branch stubs the finder to return a salon that IS in the set, so deleting
         * that {@code .filter} left the whole suite green — the class javadoc claims the re-check
         * ("the result is additionally required to be a member of the actor-keyed set") but nothing
         * made the claim fail.
         *
         * <p>The divergence is reachable, not hypothetical: the two queries carry DIFFERENT activity
         * predicates in the same direction as the {@code isBookable} precondition documented on
         * {@code #ownedSalonId}. Should either predicate ever be relaxed, the finder can hand back a
         * salon the actor owns but which the actor-keyed set excludes, and without the re-check that
         * salon would become a {@code StaffBookingScope.InSalon} the caller was never proven to hold
         * authority over.
         *
         * <p>Mutation-verified: remove {@code .filter(ownedSalonIds::contains)} and this test — and
         * only this test — goes red.
         */
        @Test
        @DisplayName("rejects a salon the owner-scoped finder returned but the actor-keyed set excludes")
        void should_reject403_when_theFinderReturnsASalonOutsideTheActorKeyedSet() {
            UUID excludedButOwned = UUID.fromString("33333333-3333-3333-3333-333333333333");
            when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId))
                    .thenReturn(List.of(CALLER_SALON, FOREIGN_SALON));
            when(masterRepository.findSalonIdByIdAndSalonOwnerId(masterId, actorId))
                    .thenReturn(Optional.of(excludedButOwned));

            assertThatThrownBy(() -> resolver.resolve(authAs(Role.SALON_OWNER), masterId))
                    .as("a salon absent from the actor-keyed set must never become a scope")
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessage("Access denied");
        }
    }

    // ════════════════════════════════════════════════════════════════════════════════
    // Self scope, and the roles that get none
    // ════════════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Self and the denied roles")
    class SelfAndDenials {

        /**
         * {@code Self} names the ACTOR, never the target master's user. That is what makes 22.2's
         * {@code master.getUser().getId()} comparison the self-booking constraint rather than a
         * restatement of the request.
         */
        @Test
        @DisplayName("an INDEPENDENT_MASTER is scoped to their own user id, with no repository read")
        void should_emitSelfScopeNamingTheActor_when_callerIsIndependentMaster() {
            StaffBookingScope scope = resolver.resolve(authAs(Role.INDEPENDENT_MASTER), masterId);

            assertThat(scope).isEqualTo(new StaffBookingScope.Self(actorId));
            verifyNoInteractions(masterRepository, salonRepository, userRepository);
        }

        @Test
        @DisplayName("a SALON_OWNER who owns no active salon gets 403, not a null scope")
        void should_reject403_when_ownerHasNoActiveSalon() {
            when(salonRepository.findIdsByOwnerIdAndIsActiveTrue(actorId)).thenReturn(List.of());

            assertThatThrownBy(() -> resolver.resolve(authAs(Role.SALON_OWNER), masterId))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("a SALON_ADMIN with no salon assignment gets 403")
        void should_reject403_when_adminHasNoSalonAssignment() {
            when(userRepository.findSalonIdById(actorId)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> resolver.resolve(authAs(Role.SALON_ADMIN), masterId))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("a SALON_MASTER gets 403 — the read-only calendar role has no scope to hold")
        void should_reject403_when_callerIsSalonMaster() {
            assertThatThrownBy(() -> resolver.resolve(authAs(Role.SALON_MASTER), masterId))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("a CLIENT gets 403")
        void should_reject403_when_callerIsClient() {
            assertThatThrownBy(() -> resolver.resolve(authAs(Role.CLIENT), masterId))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        @DisplayName("an unauthenticated call gets 403 rather than a 500 from a bad cast")
        void should_reject403_when_authenticationCarriesNoUuidDetails() {
            Authentication anonymous = new UsernamePasswordAuthenticationToken(
                    "nobody", null, List.of(new SimpleGrantedAuthority("ROLE_SALON_OWNER")));

            assertThatThrownBy(() -> resolver.resolve(anonymous, masterId))
                    .isInstanceOf(ForbiddenException.class);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /**
     * Mirrors what {@code JwtAuthenticationFilter} builds: the user id lives in {@code details},
     * never in the principal — the shape {@code AuthenticationUtils} reads.
     */
    private Authentication authAs(Role role) {
        var token = new UsernamePasswordAuthenticationToken(
                "staff@beautica.test", null,
                List.of(new SimpleGrantedAuthority("ROLE_" + role.name())));
        token.setDetails(actorId);
        return token;
    }

    /** Kept honest: no test may pass by the resolver silently accepting a null Authentication. */
    @Test
    @DisplayName("a null Authentication is 403, never an NPE")
    void should_reject403_when_authenticationIsNull() {
        assertThatThrownBy(() -> resolver.resolve(null, masterId))
                .isInstanceOf(ForbiddenException.class);
        verifyNoInteractions(salonRepository, userRepository, masterRepository);
    }
}
