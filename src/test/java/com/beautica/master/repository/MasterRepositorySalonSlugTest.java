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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phases 392/393 — the salon-path visibility rule lives ONLY in the JPQL of
 * {@link MasterRepository#findBookableBySalonSlugWithUser} / {@link MasterRepository#findBookableIdBySalonSlug}
 * (active master with a user, in the ACTIVE salon behind the slug), so only a real query can fail when a
 * predicate is dropped. Each case flips exactly one flag; both finders must agree on every case.
 */
@DisplayName("MasterRepository salon-slug finders — salon-scoped guest visibility (phases 392/393)")
class MasterRepositorySalonSlugTest extends AbstractDataJpaTest {

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    @Autowired private MasterRepository masterRepository;
    @Autowired private TestEntityManager em;
    @Autowired private JdbcTemplate jdbcTemplate;

    private record Seeded(UUID salonId, String salonSlug, UUID masterId) {
    }

    @Test
    @DisplayName("active master of the active salon behind the slug, WITHOUT a master booking slug → resolves (both finders)")
    void should_resolve_when_masterActiveInActiveSalon() {
        Seeded seeded = seed("salon-live-ab12", true, true);

        assertThat(masterRepository.findBookableIdBySalonSlug(seeded.masterId(), seeded.salonSlug()))
                .contains(seeded.masterId());
        assertThat(masterRepository.findBookableBySalonSlugWithUser(seeded.masterId(), seeded.salonSlug()))
                .hasValueSatisfying(m -> assertThat(m.getBookingSlug()).as("no personal slug needed").isNull());
    }

    @Test
    @DisplayName("salon closed (master row still active) → empty on both")
    void should_returnEmpty_when_salonInactive() {
        Seeded seeded = seed("salon-closed-cd34", true, false);

        assertBothEmpty(seeded.masterId(), seeded.salonSlug());
    }

    @Test
    @DisplayName("master deactivated, salon open → empty on both")
    void should_returnEmpty_when_masterInactive() {
        Seeded seeded = seed("salon-inactive-m-ef56", false, true);

        assertBothEmpty(seeded.masterId(), seeded.salonSlug());
    }

    @Test
    @DisplayName("master of ANOTHER live salon asked through this salon's slug → empty on both")
    void should_returnEmpty_when_masterBelongsToAnotherSalon() {
        Seeded mine = seed("salon-mine-gh78", true, true);
        Seeded other = seed("salon-other-ij90", true, true);

        assertBothEmpty(other.masterId(), mine.salonSlug());
    }

    @Test
    @DisplayName("detached master (user_id NULL) → empty on both (INNER user join)")
    void should_returnEmpty_when_masterDetached() {
        Seeded seeded = seed("salon-detached-kl12", true, true);
        jdbcTemplate.update("UPDATE masters SET user_id = NULL, detached_at = NOW(), detached_first_name = 'Test' WHERE id = ?",
                seeded.masterId());

        assertBothEmpty(seeded.masterId(), seeded.salonSlug());
    }

    private void assertBothEmpty(UUID masterId, String salonSlug) {
        assertThat(masterRepository.findBookableIdBySalonSlug(masterId, salonSlug)).isEmpty();
        assertThat(masterRepository.findBookableBySalonSlugWithUser(masterId, salonSlug)).isEmpty();
    }

    private Seeded seed(String salonSlug, boolean masterActive, boolean salonActive) {
        Salon salon = Salon.builder()
                .cityId(testCityId())
                .owner(persistUser(Role.SALON_OWNER))
                .name("Test Salon")
                .isActive(salonActive)
                .build();
        em.persist(salon);
        Master master = Master.builder()
                .user(persistUser(Role.SALON_MASTER))
                .salon(salon)
                .masterType(MasterType.SALON_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(masterActive)
                .build();
        em.persist(master);
        em.flush();
        jdbcTemplate.update("UPDATE salons SET booking_slug = ? WHERE id = ?", salonSlug, salon.getId());
        em.clear();
        return new Seeded(salon.getId(), salonSlug, master.getId());
    }

    private User persistUser(Role role) {
        User user = new User("ss-" + UUID.randomUUID() + "@beautica.test", PASSWORD_HASH, role, "Test", "Master",
                "+380501234567");
        em.persist(user);
        return user;
    }
}
