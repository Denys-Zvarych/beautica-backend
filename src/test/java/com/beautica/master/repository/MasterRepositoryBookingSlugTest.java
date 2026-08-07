package com.beautica.master.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the salon-active predicate on {@link MasterRepository#findByBookingSlugWithUser}
 * (2026-08 security re-audit HIGH + MEDIUM).
 *
 * <p><b>Why this is a repository test and not a service unit test.</b> The guard lives in the
 * JPQL, because BOTH public slug surfaces share this one finder — {@code GET /book/{slug}/info}
 * ({@code BookingSlugService#findBySlug}, which previously filtered NOTHING) and the
 * {@code permitAll} {@code POST /book/{slug}/booking}
 * ({@code GuestBookingService#createGuestBooking}, which filtered only {@code Master::isActive}).
 * A Mockito test that stubs {@code findByBookingSlugWithUser} returns whatever it is told and
 * therefore cannot observe a WHERE clause at all — removing the predicate would leave such a test
 * green. Only a real query against a real schema can fail when the guard is deleted.
 *
 * <p>The third case is the {@code LEFT}-join regression test: an {@code INDEPENDENT_MASTER} has a
 * NULL salon, and writing the predicate as the implicit path {@code m.salon.isActive = true}
 * compiles to an INNER join on Hibernate 6, which would silently delete every independent
 * master's own booking page. That failure mode is invisible to the two salon cases.
 */
@DisplayName("MasterRepository#findByBookingSlugWithUser — salon-active guard (public slug surfaces)")
class MasterRepositoryBookingSlugTest extends AbstractDataJpaTest {

    @Autowired
    private MasterRepository masterRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("resolves the master when the employing salon is ACTIVE")
    void should_resolveMaster_when_salonIsActive() {
        String slug = "olena-active-ab12";
        persistSalonMaster(slug, true, true);

        Optional<Master> found = masterRepository.findByBookingSlugWithUser(slug);

        assertThat(found)
                .as("an active master of an OPEN salon must keep a working public booking page")
                .isPresent();
    }

    @Test
    @DisplayName("returns empty when the employing salon was deactivated, even though the master row is still active")
    void should_returnEmpty_when_salonIsDeactivated() {
        String slug = "olena-closed-cd34";
        // The ONLY false flag is the salon's. masters.is_active stays TRUE — exactly the state
        // SalonService.deactivateSalon leaves behind, since it does not cascade to masters.
        Master master = persistSalonMaster(slug, true, false);
        assertThat(master.isActive())
                .as("precondition: the master row itself is NOT deactivated, otherwise this test "
                        + "would merely be re-testing the pre-existing isActive filter")
                .isTrue();

        Optional<Master> found = masterRepository.findByBookingSlugWithUser(slug);

        assertThat(found)
                .as("MasterService#createMasterFromInvite mints a booking slug for every "
                        + "SALON_MASTER, so without this predicate a closed salon's staff keep "
                        + "live, publicly bookable links")
                .isEmpty();
    }

    @Test
    @DisplayName("returns empty when the MASTER row itself is deactivated, salon still open")
    void should_returnEmpty_when_masterIsDeactivated() {
        String slug = "olena-inactive-ef56";
        // The ONLY false flag is the master's; the salon stays OPEN, so this isolates the
        // `AND m.isActive = true` term from the salon term the two tests above cover.
        //
        // Pinned here because GuestBookingService dropped its redundant `.filter(Master::isActive)`
        // (it was a strictly weaker duplicate of this predicate). With that gone, this JPQL term is
        // the ONLY thing keeping a deactivated master's public booking page and permitAll
        // POST /book/{slug}/booking closed — so deleting it must fail a test, not just a comment.
        persistSalonMaster(slug, false, true);

        Optional<Master> found = masterRepository.findByBookingSlugWithUser(slug);

        assertThat(found)
                .as("a deactivated master must resolve to nothing on BOTH public slug surfaces; "
                        + "this finder is now their single enforcement point")
                .isEmpty();
    }

    @Test
    @DisplayName("still resolves an INDEPENDENT_MASTER, who has no salon at all (LEFT-join regression)")
    void should_resolveMaster_when_masterHasNoSalon() {
        String slug = "solo-master-ef56";
        persistIndependentMaster(slug);

        Optional<Master> found = masterRepository.findByBookingSlugWithUser(slug);

        assertThat(found)
                .as("an INDEPENDENT_MASTER has a NULL salon; an INNER join (which the implicit "
                        + "m.salon.isActive path would compile to) would drop every one of them")
                .isPresent();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────

    private Master persistSalonMaster(String slug, boolean masterActive, boolean salonActive) {
        User owner = persistUser(Role.SALON_OWNER);
        Salon salon = Salon.builder()
                .owner(owner)
                .name("Test Salon")
                .isActive(salonActive)
                .build();
        em.persist(salon);

        User staff = persistUser(Role.SALON_MASTER);
        Master master = Master.builder()
                .user(staff)
                .salon(salon)
                .masterType(MasterType.SALON_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(masterActive)
                .bookingSlug(slug)
                .build();
        em.persist(master);
        em.flush();
        em.clear();
        return master;
    }

    private void persistIndependentMaster(String slug) {
        User user = persistUser(Role.INDEPENDENT_MASTER);
        Master master = Master.builder()
                .user(user)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .bookingSlug(slug)
                .build();
        em.persist(master);
        em.flush();
        em.clear();
    }

    /** Real (cheap-cost) BCrypt rather than a literal hash string — Anti-Bug §M5. */
    private static final String PASSWORD_HASH =
            new BCryptPasswordEncoder(4).encode("test-password");

    private User persistUser(Role role) {
        User user = new User(
                "slug-" + UUID.randomUUID() + "@beautica.test",
                PASSWORD_HASH,
                role,
                "Test",
                "Master",
                "+380501234567");
        em.persist(user);
        return user;
    }
}
