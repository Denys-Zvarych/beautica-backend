package com.beautica.salon.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.TestConstants;
import com.beautica.auth.Role;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 362 audit cycle 1 (F5) — {@link SalonRepository#lockIsActiveForUpdate} is a native query, which Spring
 * Data does not validate at startup; this pins that it parses and maps against the real schema (the fused
 * {@code lock_timeout} config subquery, {@code FOR NO KEY UPDATE OF s}, the scalar {@code is_active} mapping).
 */
@DisplayName("SalonRepository#lockIsActiveForUpdate — native scalar row lock (Phase 362 F5)")
class SalonRepositoryLockIsActiveTest extends AbstractDataJpaTest {

    @Autowired private SalonRepository salonRepository;
    @Autowired private TestEntityManager em;

    @Test
    @DisplayName("returns TRUE for an active salon")
    void should_returnTrue_when_salonActive() {
        UUID salonId = persistSalon(true);

        Optional<Boolean> locked = salonRepository.lockIsActiveForUpdate(salonId);

        assertThat(locked).contains(true);
    }

    @Test
    @DisplayName("returns FALSE (row present, still locked) for an inactive salon")
    void should_returnFalse_when_salonInactive() {
        UUID salonId = persistSalon(false);

        Optional<Boolean> locked = salonRepository.lockIsActiveForUpdate(salonId);

        assertThat(locked).contains(false);
    }

    @Test
    @DisplayName("returns empty for an unknown id")
    void should_returnEmpty_when_idUnknown() {
        Optional<Boolean> locked = salonRepository.lockIsActiveForUpdate(UUID.randomUUID());

        assertThat(locked).isEmpty();
    }

    private UUID persistSalon(boolean active) {
        User owner = new User("lock-owner-" + UUID.randomUUID() + "@beautica.test",
                TestConstants.HASHED_TEST_PASSWORD, Role.SALON_OWNER, "Olena", "Kovalenko", "+380501234567");
        em.persist(owner);
        Salon salon = Salon.builder()
                .cityId(testCityId())
                .owner(owner)
                .name("Lock Salon")
                .isActive(active)
                .build();
        em.persist(salon);
        em.flush();
        em.clear();
        return salon.getId();
    }
}
