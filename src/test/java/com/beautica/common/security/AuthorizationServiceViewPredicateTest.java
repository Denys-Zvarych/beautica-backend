package com.beautica.common.security;

import com.beautica.auth.Role;
import com.beautica.booking.repository.BookingRepository;
import com.beautica.booking.repository.BookingViewAccess;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.service.repository.ServiceRepository;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Audit-fix cycle 1, phase 334 finding 2 — {@link AuthorizationService#isViewAuthorized} is the
 * pure predicate extracted out of {@link AuthorizationService#canViewBooking}. This suite proves
 * TWO things per scenario, over the SAME facts:
 * <ol>
 *   <li>{@code isViewAuthorized}, called directly with pre-loaded facts (the shape
 *       {@code NotificationViewAssembler} uses), returns the expected verdict.</li>
 *   <li>{@code canViewBooking}, called through its {@link BookingRepository#findViewAccessById}
 *       projection (the shape the {@code @PreAuthorize} SpEL gate uses), returns the IDENTICAL
 *       verdict for the identical facts — proving the two callers cannot drift, because they now
 *       share one implementation.</li>
 * </ol>
 *
 * <p>{@code SALON_ADMIN} is covered by two scenarios ("own salon" / "moved to another salon") to
 * document — not merely assert — the INTENDED divergence: neither caller in this suite admits
 * {@code SALON_ADMIN} at all (both return {@code false} unconditionally, regardless of any salon
 * membership fact), because {@code isViewAuthorized} has no admin branch. Real admin visibility is
 * granted separately, by {@code NotificationViewAssembler#managesSalon}, which is out of scope for
 * this predicate-equivalence suite.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuthorizationService#isViewAuthorized — equivalence with canViewBooking")
class AuthorizationServiceViewPredicateTest {

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
    @Spy
    private ActorSalonAssignmentMemo actorSalonAssignmentMemo = new ActorSalonAssignmentMemo();
    @Spy
    private SalonScopeFactMemo salonScopeFactMemo = new SalonScopeFactMemo();

    @InjectMocks
    private AuthorizationService authorizationService;

    private record Case(
            String label, Role actorRole, UUID actorId, UUID clientUserId, UUID masterUserId,
            boolean masterIsActive, UUID salonOwnerUserId, boolean expected) {
        @Override
        public String toString() {
            return label;
        }
    }

    static Stream<Case> cases() {
        UUID actor = UUID.randomUUID();
        UUID stranger = UUID.randomUUID();
        UUID anotherStranger = UUID.randomUUID();

        return Stream.of(
                new Case("CLIENT — own booking", Role.CLIENT, actor,
                        /* clientUserId */ actor, /* masterUserId */ stranger, true,
                        /* salonOwnerUserId */ anotherStranger, true),
                new Case("CLIENT — someone else's booking", Role.CLIENT, actor,
                        /* clientUserId */ stranger, /* masterUserId */ anotherStranger, true,
                        /* salonOwnerUserId */ UUID.randomUUID(), false),

                new Case("SALON_MASTER — performing master (active)", Role.SALON_MASTER, actor,
                        /* clientUserId */ stranger, /* masterUserId */ actor, true,
                        /* salonOwnerUserId */ anotherStranger, true),
                new Case("SALON_MASTER — colleague's booking (not the performer)", Role.SALON_MASTER, actor,
                        /* clientUserId */ stranger, /* masterUserId */ anotherStranger, true,
                        /* salonOwnerUserId */ UUID.randomUUID(), false),
                new Case("SALON_MASTER — performer but DEACTIVATED", Role.SALON_MASTER, actor,
                        /* clientUserId */ stranger, /* masterUserId */ actor, false,
                        /* salonOwnerUserId */ anotherStranger, false),

                new Case("INDEPENDENT_MASTER — own booking", Role.INDEPENDENT_MASTER, actor,
                        /* clientUserId */ stranger, /* masterUserId */ actor, true,
                        /* salonOwnerUserId */ null, true),
                new Case("INDEPENDENT_MASTER — own booking, master row DEACTIVATED "
                        + "(the predicate does NOT gate on masterIsActive for this arm — see its javadoc)",
                        Role.INDEPENDENT_MASTER, actor,
                        /* clientUserId */ stranger, /* masterUserId */ actor, false,
                        /* salonOwnerUserId */ null, true),

                new Case("SALON_OWNER — own salon", Role.SALON_OWNER, actor,
                        /* clientUserId */ stranger, /* masterUserId */ anotherStranger, true,
                        /* salonOwnerUserId */ actor, true),
                new Case("SALON_OWNER — someone else's salon", Role.SALON_OWNER, actor,
                        /* clientUserId */ stranger, /* masterUserId */ anotherStranger, true,
                        /* salonOwnerUserId */ anotherStranger, false),
                new Case("SALON_OWNER — own but DEACTIVATED salon "
                        + "(the predicate has no salon-active fact at all — see its javadoc)",
                        Role.SALON_OWNER, actor,
                        /* clientUserId */ stranger, /* masterUserId */ anotherStranger, false,
                        /* salonOwnerUserId */ actor, true),

                new Case("SALON_ADMIN — own salon (no admin branch — always false here)",
                        Role.SALON_ADMIN, actor,
                        /* clientUserId */ stranger, /* masterUserId */ anotherStranger, true,
                        /* salonOwnerUserId */ UUID.randomUUID(), false),
                new Case("SALON_ADMIN — moved to a DIFFERENT salon (still always false here)",
                        Role.SALON_ADMIN, actor,
                        /* clientUserId */ UUID.randomUUID(), /* masterUserId */ UUID.randomUUID(), true,
                        /* salonOwnerUserId */ UUID.randomUUID(), false)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("isViewAuthorized, called directly with pre-loaded facts")
    void should_matchExpected_when_calledDirectly(Case c) {
        boolean result = authorizationService.isViewAuthorized(
                c.actorRole(), c.actorId(), c.clientUserId(), c.masterUserId(), c.masterIsActive(),
                c.salonOwnerUserId());

        assertThat(result).as(c.label()).isEqualTo(c.expected());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("canViewBooking, called through the findViewAccessById projection, agrees with isViewAuthorized")
    void should_matchIsViewAuthorized_when_calledThroughCanViewBooking(Case c) {
        UUID bookingId = UUID.randomUUID();
        when(bookingRepository.findViewAccessById(bookingId)).thenReturn(Optional.of(
                new BookingViewAccess(c.clientUserId(), c.masterUserId(), c.masterIsActive(), c.salonOwnerUserId())));
        Authentication auth = mockAuth(c.actorId(), c.actorRole().springRole);

        boolean result = authorizationService.canViewBooking(auth, bookingId);

        assertThat(result).as(c.label()).isEqualTo(c.expected());
    }

    private Authentication mockAuth(UUID actorId, String roleName) {
        var token = new UsernamePasswordAuthenticationToken(
                "user@example.com", null, List.of(new SimpleGrantedAuthority(roleName)));
        token.setDetails(actorId);
        return token;
    }
}
