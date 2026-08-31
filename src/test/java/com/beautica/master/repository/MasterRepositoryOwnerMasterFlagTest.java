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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards {@link MasterRepository#existsByUserIdAndMasterTypeAndIsActiveTrue}, the single query
 * behind {@code UserProfileResponse.hasMasterProfile} (Phase 265) — the owner-as-master toggle
 * state that {@code GET /api/v1/users/me} derives on every read.
 *
 * <h2>Why a real query test and not a Mockito stub</h2>
 * Both predicates under test are WHERE terms of a Spring Data derived query. A stub of this method
 * returns whatever the test told it to and can therefore never observe that
 * {@code AND is_active = true} or {@code AND master_type = 'SALON_OWNER'} was dropped from the
 * method NAME — the mutation would compile, every unit test would stay green, and the flag would
 * quietly start reporting row existence instead of toggle state. Same argument, same shape as
 * {@link MasterRepositoryBookingSlugTest}.
 *
 * <h2>The mutation the {@code isActive} case exists to catch</h2>
 * {@code DELETE /api/v1/salons/&#123;salonId&#125;/master} <em>deactivates</em> the owner-master
 * row rather than hard-deleting it ({@code MasterService#deactivateOwnerMaster} sets
 * {@code is_active = false}). Rename the finder to {@code existsByUserIdAndMasterType} — i.e. drop
 * the active predicate, leaving mere row existence — and
 * {@link #should_returnFalse_when_ownerMasterRowIsInactive} goes RED while everything else stays
 * green. Without that case, an owner who toggled OFF would read {@code true} forever.
 */
@DisplayName("MasterRepository#existsByUserIdAndMasterTypeAndIsActiveTrue — owner-as-master flag (Phase 265)")
class MasterRepositoryOwnerMasterFlagTest extends AbstractDataJpaTest {

    @Autowired
    private MasterRepository masterRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("true when the owner has an ACTIVE SALON_OWNER-type master row")
    void should_returnTrue_when_ownerHasActiveOwnerMasterRow() {
        User owner = persistOwnerWithMaster(MasterType.SALON_OWNER, true);

        boolean hasMasterProfile = masterRepository.existsByUserIdAndMasterTypeAndIsActiveTrue(
                owner.getId(), MasterType.SALON_OWNER);

        assertThat(hasMasterProfile)
                .as("SalonService.createSalon auto-creates this row on first-salon registration, "
                        + "so every owner registered to date must read true (Phase 265 D2)")
                .isTrue();
    }

    @Test
    @DisplayName("false when the SALON_OWNER has no master row at all")
    void should_returnFalse_when_salonOwnerHasNoMasterRow() {
        User owner = persistUser(Role.SALON_OWNER);
        em.flush();
        em.clear();

        boolean hasMasterProfile = masterRepository.existsByUserIdAndMasterTypeAndIsActiveTrue(
                owner.getId(), MasterType.SALON_OWNER);

        assertThat(hasMasterProfile).isFalse();
    }

    @Test
    @DisplayName("false when the owner-master row exists but was DEACTIVATED (mutation guard)")
    void should_returnFalse_when_ownerMasterRowIsInactive() {
        // The ONLY false flag is is_active — the row exists, and its master_type is still
        // SALON_OWNER. This isolates the active predicate from the type predicate, so dropping
        // `AndIsActiveTrue` from the finder name fails HERE and nowhere else.
        User owner = persistOwnerWithMaster(MasterType.SALON_OWNER, false);

        boolean hasMasterProfile = masterRepository.existsByUserIdAndMasterTypeAndIsActiveTrue(
                owner.getId(), MasterType.SALON_OWNER);

        assertThat(hasMasterProfile)
                .as("the DELETE toggle deactivates rather than deletes, so a bare existence check "
                        + "would report every owner who ever opted in as still opted in")
                .isFalse();
    }

    @Test
    @DisplayName("false when the user's active master row is of a DIFFERENT type")
    void should_returnFalse_when_masterRowIsOfADifferentType() {
        // An INDEPENDENT_MASTER's row is active and belongs to this user, but is not the
        // owner-as-master row the toggle represents. Isolates the master_type predicate.
        User user = persistOwnerWithMaster(MasterType.INDEPENDENT_MASTER, true);

        boolean hasMasterProfile = masterRepository.existsByUserIdAndMasterTypeAndIsActiveTrue(
                user.getId(), MasterType.SALON_OWNER);

        assertThat(hasMasterProfile).isFalse();
    }

    @Test
    @DisplayName("false for a different user — the userId predicate scopes the row to its own account")
    void should_returnFalse_when_theActiveOwnerMasterRowBelongsToSomeoneElse() {
        persistOwnerWithMaster(MasterType.SALON_OWNER, true);
        User other = persistUser(Role.SALON_OWNER);
        em.flush();
        em.clear();

        boolean hasMasterProfile = masterRepository.existsByUserIdAndMasterTypeAndIsActiveTrue(
                other.getId(), MasterType.SALON_OWNER);

        assertThat(hasMasterProfile)
                .as("repository finders are unscoped by default (§E-4) — this one must at least "
                        + "key on the user it was asked about")
                .isFalse();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────────────

    /**
     * Persists a {@code SALON_OWNER} user, their salon, and one master row of {@code type} with
     * {@code masterActive}. The user's role stays {@code SALON_OWNER} even when the master row is
     * {@code INDEPENDENT_MASTER}-typed: this class tests the query's predicates, and the role
     * short-circuit that fronts it lives in {@code UserService}, not here.
     */
    private User persistOwnerWithMaster(MasterType type, boolean masterActive) {
        User owner = persistUser(Role.SALON_OWNER);
        Salon salon = Salon.builder()
                .cityId(testCityId())
                .owner(owner)
                .name("Test Salon")
                .isActive(true)
                .build();
        em.persist(salon);

        Master master = Master.builder()
                .user(owner)
                .salon(salon)
                .masterType(type)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(masterActive)
                .build();
        em.persist(master);
        em.flush();
        em.clear();
        return owner;
    }

    /** Real (cheap-cost) BCrypt rather than a literal hash string — Anti-Bug §M5. */
    private static final String PASSWORD_HASH =
            new BCryptPasswordEncoder(4).encode("test-password");

    private User persistUser(Role role) {
        User user = new User(
                "owner-flag-" + UUID.randomUUID() + "@beautica.test",
                PASSWORD_HASH,
                role,
                "Test",
                "Owner",
                "+380501234567");
        em.persist(user);
        return user;
    }
}
