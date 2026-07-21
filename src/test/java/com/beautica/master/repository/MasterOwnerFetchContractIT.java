package com.beautica.master.repository;

import com.beautica.AbstractIntegrationTest;
import com.beautica.master.entity.Master;
import jakarta.persistence.EntityManagerFactory;
import org.assertj.core.api.SoftAssertions;
import org.hibernate.Hibernate;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the {@code salon.owner} fetch drop on
 * {@link MasterRepository#findByIdWithUserAndSalon} — the fourth and final removal of a dead
 * {@code LEFT JOIN FETCH s.owner}, after {@code BookingRepository}'s three.
 *
 * <h2>Why this gate exists at all</h2>
 * The three earlier removals on {@code BookingRepository} shipped with no gate and one of them was
 * silently re-added, which is what forced {@code BookingPriceRangeContractIT}'s
 * {@code OWNER_DETAIL_ENTITIES} into existence. That gate is bound to
 * {@code BookingRepository#findByIdWithFullGraph} and is structurally blind to this query, so it
 * transfers no protection here. This method is also the highest-blast-radius member of the class:
 * ~10 callers spanning {@code AuthorizationService}, {@code MasterService},
 * {@code MasterScheduleService}, {@code FavoriteService} and {@code BookingService}, including the
 * cached, unauthenticated {@code GET /api/v1/masters/{masterId}}.
 *
 * <h2>Why not a statement count</h2>
 * Restoring {@code LEFT JOIN FETCH s.owner} widens an existing join rather than issuing a
 * statement, so every statement-based metric is by construction blind to it — the same argument
 * that forced the sibling gate to count entities. This gate uses two independent signals that do
 * see it: proxy initialisation state, and the absolute hydrated-entity count.
 *
 * <h2>Why the read runs OUTSIDE a transaction</h2>
 * Deliberate. The javadoc this change corrected justified the fetch as necessary for
 * "authorization checks that run outside an active JPA session". Reading the detached result proves
 * the opposite claim directly: an identifier resolves off an uninitialised proxy with no session at
 * all, and without a {@code LazyInitializationException}. In-transaction callers are strictly
 * easier than this.
 */
@DisplayName("MasterRepository#findByIdWithUserAndSalon — does not hydrate Salon.owner")
class MasterOwnerFetchContractIT extends AbstractIntegrationTest {

    /**
     * Master + master {@code User} + {@code Salon}. The salon owner's {@code User} is
     * conspicuously NOT among them.
     *
     * <p>A rise to 4 means {@code s.owner} (or another association) was fetch-joined back on.
     * Do not adjust this constant to make a failing run pass without first establishing which
     * association the extra entity belongs to.
     */
    private static final long MASTER_BY_ID_ENTITIES = 3L;

    @Autowired
    private MasterRepository masterRepository;

    @Autowired
    private EntityManagerFactory emf;

    @Test
    @DisplayName("returns the Salon.owner as an UNINITIALISED proxy whose id still resolves while "
            + "detached, and hydrates no User row for it — pins a fetch a statement count cannot see")
    void should_notHydrateTheSalonOwner_when_loadingAMasterById() {
        UUID ownerId = insertUser("SALON_OWNER");
        UUID salonId = insertSalon(ownerId);
        UUID masterUserId = insertUser("SALON_MASTER");
        UUID masterId = insertMaster(masterUserId, salonId);

        Statistics statistics = statistics();
        statistics.clear();
        // No surrounding transaction: the result is detached the moment this call returns.
        Master master = masterRepository.findByIdWithUserAndSalon(masterId).orElseThrow();
        long entities = statistics.getEntityLoadCount();

        assertThat(master.getSalon())
                .as("premise — the row must carry a real salon, or there is no Salon.owner proxy "
                        + "for this gate to be about")
                .isNotNull();

        // Soft, so a regression reports BOTH signals rather than only whichever fires first —
        // the entity count is meant to stand on its own if the proxy assertion is ever weakened.
        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(Hibernate.isInitialized(master.getSalon().getOwner()))
                    .as("PRIMARY signal — Salon.owner must come back as an untouched proxy. True here "
                            + "means LEFT JOIN FETCH s.owner was restored onto findByIdWithUserAndSalon")
                    .isFalse();
            softly.assertThat(master.getSalon().getOwner().getId())
                    .as("the identifier every production caller actually reads must resolve off the "
                            + "uninitialised, DETACHED proxy — this is the whole basis for dropping the fetch")
                    .isEqualTo(ownerId);
            softly.assertThat(Hibernate.isInitialized(master.getSalon().getOwner()))
                    .as("reading getOwner().getId() must not have initialised the proxy — an id is served "
                            + "from the FK the proxy was built with, not from a SELECT")
                    .isFalse();
            softly.assertThat(entities)
                    .as("SECONDARY signal — absolute HYDRATED-ENTITY count for one by-id master load. A "
                            + "rise to %s means the salon owner's User row (password_hash included) is "
                            + "being materialised again.", MASTER_BY_ID_ENTITIES + 1)
                    .isEqualTo(MASTER_BY_ID_ENTITIES);
        });
    }

    // ── fixtures ──────────────────────────────────────────────────────────────

    private Statistics statistics() {
        Statistics statistics = emf.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        return statistics;
    }

    private UUID insertUser(String role) {
        UUID id = UUID.randomUUID();
        // Real BCrypt at cost 4 rather than a fake hash string (test-infra rule §M5).
        String hash = new BCryptPasswordEncoder(4).encode("test-password");
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) VALUES (?, ?, ?, ?, 'Fn', 'Ln', true, true)",
                id, "mofc-" + id + "@beautica.test", hash, role);
        return id;
    }

    private UUID insertSalon(UUID ownerId) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                salonId, ownerId, "MOFC Salon");
        return salonId;
    }

    private UUID insertMaster(UUID userId, UUID salonId) {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, review_count, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, 'SALON_MASTER', 0, true, NOW(), NOW())",
                masterId, userId, salonId);
        return masterId;
    }
}
