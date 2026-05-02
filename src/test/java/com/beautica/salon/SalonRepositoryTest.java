package com.beautica.salon;

import com.beautica.auth.Role;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
@DisplayName("SalonRepository — data access layer")
class SalonRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private SalonRepository salonRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("findAllByOwnerIdFetchOwner — returns only the target owner's salons with owner initialised")
    void should_returnOwnerSalonsWithOwnerFetched_when_ownerHasMultipleSalons() {
        User owner = new User(
                "owner-fetch-" + UUID.randomUUID() + "@beautica.test",
                "$2a$10$hashedpassword",
                Role.SALON_OWNER,
                "Olena",
                "Kovalenko",
                "+380501234567"
        );
        em.persist(owner);

        User otherOwner = new User(
                "other-" + UUID.randomUUID() + "@beautica.test",
                "$2a$10$hashedpassword",
                Role.SALON_OWNER,
                "Ivan",
                "Petrenko",
                "+380509876543"
        );
        em.persist(otherOwner);

        Salon salonOne = Salon.builder()
                .owner(owner)
                .name("Salon One")
                .isActive(true)
                .build();
        Salon salonTwo = Salon.builder()
                .owner(owner)
                .name("Salon Two")
                .isActive(true)
                .build();
        Salon otherSalon = Salon.builder()
                .owner(otherOwner)
                .name("Other Owner Salon")
                .isActive(true)
                .build();

        em.persist(salonOne);
        em.persist(salonTwo);
        em.persist(otherSalon);
        em.flush();
        em.clear();

        List<Salon> results = salonRepository.findAllByOwnerIdFetchOwner(owner.getId());

        assertThat(results)
                .as("only salons belonging to the target owner must be returned")
                .hasSize(2);
        assertThat(results)
                .extracting(s -> s.getOwner().getId())
                .as("every returned salon must reference the correct owner (fetch join — no proxy)")
                .containsOnly(owner.getId());
        assertThat(results)
                .extracting(s -> s.getOwner().getEmail())
                .as("owner email must be accessible without LazyInitializationException (join fetch initialised proxy)")
                .allMatch(email -> email.startsWith("owner-fetch-"));
    }
}
