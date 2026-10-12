package com.beautica.master.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
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
 * Phase 362 D6 — {@link MasterRepository#lockIdForUpdate} is a native query, which Spring Data does not
 * validate at startup; this pins that it parses and maps against the real schema (the {@code lock_timeout}
 * config subquery, {@code FOR NO KEY UPDATE OF m}, the scalar id mapping — audit cycle 1 F4: no entity load).
 */
@DisplayName("MasterRepository#lockIdForUpdate — native row lock (Phase 362)")
class MasterRepositoryForUpdateTest extends AbstractDataJpaTest {

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    @Autowired private MasterRepository masterRepository;
    @Autowired private TestEntityManager em;

    @Test
    @DisplayName("returns the locked master's id when it exists")
    void should_returnMasterId_when_idExists() {
        UUID masterId = persistIndependentMaster();

        Optional<UUID> locked = masterRepository.lockIdForUpdate(masterId);

        assertThat(locked).contains(masterId);
    }

    @Test
    @DisplayName("returns empty for an unknown id")
    void should_returnEmpty_when_idUnknown() {
        Optional<UUID> locked = masterRepository.lockIdForUpdate(UUID.randomUUID());

        assertThat(locked).isEmpty();
    }

    private UUID persistIndependentMaster() {
        User user = new User("lock-" + UUID.randomUUID() + "@beautica.test", PASSWORD_HASH,
                Role.INDEPENDENT_MASTER, "Test", "Master", "+380501234567");
        em.persist(user);
        Master master = Master.builder()
                .user(user)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .bookingSlug("lock-" + UUID.randomUUID().toString().substring(0, 8))
                .build();
        em.persist(master);
        em.flush();
        em.clear();
        return master.getId();
    }
}
