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
    @DisplayName("findAllByOwnerIdAndIsActiveTrue — returns only active salons for the target owner")
    void should_returnOnlyActiveSalons_when_ownerHasActiveAndInactiveSalons() {
        User owner = new User(
                "owner-active-" + UUID.randomUUID() + "@beautica.test",
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

        Salon activeSalon = Salon.builder()
                .owner(owner)
                .name("Active Salon")
                .isActive(true)
                .build();
        Salon inactiveSalon = Salon.builder()
                .owner(owner)
                .name("Inactive Salon")
                .isActive(false)
                .build();
        Salon otherSalon = Salon.builder()
                .owner(otherOwner)
                .name("Other Owner Salon")
                .isActive(true)
                .build();

        em.persist(activeSalon);
        em.persist(inactiveSalon);
        em.persist(otherSalon);
        em.flush();
        em.clear();

        List<Salon> results = salonRepository.findAllByOwnerIdAndIsActiveTrue(owner.getId());

        assertThat(results)
                .as("only active salons belonging to the target owner must be returned")
                .hasSize(1);
        assertThat(results)
                .extracting(Salon::getName)
                .as("only the active salon must be present; the inactive one must be excluded")
                .containsExactly("Active Salon");
        assertThat(results)
                .extracting(s -> s.getOwner().getId())
                .as("every returned salon must belong to the target owner")
                .containsOnly(owner.getId());
    }

    @Test
    @DisplayName("existsByIdAndOwnerId — returns true when salon belongs to the given owner")
    void should_returnTrue_when_salonExistsByIdAndOwnerId() {
        User owner = new User(
                "owner-exists-" + UUID.randomUUID() + "@beautica.test",
                "$2a$10$hashedpassword",
                Role.SALON_OWNER,
                "Olena",
                "Kovalenko",
                "+380501234567"
        );
        em.persist(owner);

        Salon salon = Salon.builder()
                .owner(owner)
                .name("My Salon")
                .isActive(true)
                .build();
        em.persist(salon);
        em.flush();
        em.clear();

        boolean result = salonRepository.existsByIdAndOwnerId(salon.getId(), owner.getId());

        assertThat(result)
                .as("existsByIdAndOwnerId must return true when owner matches the persisted salon")
                .isTrue();
    }

    @Test
    @DisplayName("existsByIdAndOwnerId — returns false when ownerId does not match the salon")
    void should_returnFalse_when_ownerIdDoesNotMatchSalon() {
        User owner = new User(
                "owner-real-" + UUID.randomUUID() + "@beautica.test",
                "$2a$10$hashedpassword",
                Role.SALON_OWNER,
                "Olena",
                "Kovalenko",
                "+380501111111"
        );
        em.persist(owner);

        Salon salon = Salon.builder()
                .owner(owner)
                .name("Real Owner Salon")
                .isActive(true)
                .build();
        em.persist(salon);
        em.flush();
        em.clear();

        UUID wrongOwnerId = UUID.randomUUID();

        boolean result = salonRepository.existsByIdAndOwnerId(salon.getId(), wrongOwnerId);

        assertThat(result)
                .as("existsByIdAndOwnerId must return false when ownerId does not match the salon")
                .isFalse();
    }

    @Test
    @DisplayName("findByIdAndOwnerId — returns empty when owner B tries to fetch owner A's salon")
    void should_returnOptionalEmpty_when_findByIdAndOwnerIdWithWrongOwner() {
        User ownerA = new User(
                "owner-a-" + UUID.randomUUID() + "@beautica.test",
                "$2a$10$hashedpassword",
                Role.SALON_OWNER,
                "Anna",
                "Shevchenko",
                "+380502222222"
        );
        User ownerB = new User(
                "owner-b-" + UUID.randomUUID() + "@beautica.test",
                "$2a$10$hashedpassword",
                Role.SALON_OWNER,
                "Bohdan",
                "Kravchenko",
                "+380503333333"
        );
        em.persist(ownerA);
        em.persist(ownerB);

        Salon salonA = Salon.builder()
                .owner(ownerA)
                .name("Salon A")
                .isActive(true)
                .build();
        em.persist(salonA);
        em.flush();
        em.clear();

        var result = salonRepository.findByIdAndOwnerId(salonA.getId(), ownerB.getId());

        assertThat(result)
                .as("findByIdAndOwnerId must return empty when owner B requests owner A's salon")
                .isEmpty();
    }
}
