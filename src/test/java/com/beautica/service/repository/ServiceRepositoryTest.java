package com.beautica.service.repository;

import com.beautica.auth.Role;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceCategory;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ServiceRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private TestEntityManager em;

    private UUID salonOwnerId;

    @BeforeEach
    void setUp() {
        User owner = new User(
                "salon-owner-" + UUID.randomUUID() + "@example.com",
                "$2a$10$hashedpassword",
                Role.SALON_OWNER,
                "Salon",
                "Owner",
                "+380501234567"
        );
        em.persist(owner);

        Salon salon = Salon.builder()
                .owner(owner)
                .name("Beauty Studio")
                .isActive(true)
                .build();
        em.persist(salon);

        em.flush();

        salonOwnerId = salon.getId();
    }

    @Test
    @DisplayName("should_returnActiveServices_when_ownerTypeAndOwnerIdMatch")
    void should_returnActiveServices_when_ownerTypeAndOwnerIdMatch() {
        ServiceDefinition activeService = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salonOwnerId)
                .name("Gel Manicure")
                .category(ServiceCategory.MANICURE)
                .baseDurationMinutes(60)
                .basePrice(new BigDecimal("550.00"))
                .isActive(true)
                .build();
        em.persist(activeService);

        ServiceDefinition inactiveService = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salonOwnerId)
                .name("Classic Pedicure")
                .category(ServiceCategory.PEDICURE)
                .baseDurationMinutes(75)
                .basePrice(new BigDecimal("400.00"))
                .isActive(false)
                .build();
        em.persist(inactiveService);

        em.flush();

        List<ServiceDefinition> results =
                serviceRepository.findByOwnerTypeAndOwnerIdAndIsActiveTrue(OwnerType.SALON, salonOwnerId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(activeService.getId());
    }

    @Test
    @DisplayName("should_returnEmpty_when_ownerTypeDoesNotMatch")
    void should_returnEmpty_when_ownerTypeDoesNotMatch() {
        ServiceDefinition salonService = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salonOwnerId)
                .name("Eyebrow Shaping")
                .category(ServiceCategory.BROWS)
                .baseDurationMinutes(30)
                .basePrice(new BigDecimal("200.00"))
                .isActive(true)
                .build();
        em.persist(salonService);

        em.flush();

        List<ServiceDefinition> results =
                serviceRepository.findByOwnerTypeAndOwnerIdAndIsActiveTrue(OwnerType.INDEPENDENT_MASTER, salonOwnerId);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("should_notLeakAcrossOwnerTypes_when_ownerIdCoincides")
    void should_notLeakAcrossOwnerTypes_when_ownerIdCoincides() {
        ServiceDefinition salonService = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salonOwnerId)
                .name("Gel Manicure")
                .category(ServiceCategory.MANICURE)
                .baseDurationMinutes(60)
                .basePrice(new BigDecimal("550.00"))
                .isActive(true)
                .build();
        em.persist(salonService);

        ServiceDefinition independentMasterService = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(salonOwnerId)
                .name("Eyelash Extensions")
                .category(ServiceCategory.EYELASH)
                .baseDurationMinutes(120)
                .basePrice(new BigDecimal("900.00"))
                .isActive(true)
                .build();
        em.persist(independentMasterService);

        em.flush();

        List<ServiceDefinition> salonResults =
                serviceRepository.findByOwnerTypeAndOwnerIdAndIsActiveTrue(OwnerType.SALON, salonOwnerId);

        List<ServiceDefinition> masterResults =
                serviceRepository.findByOwnerTypeAndOwnerIdAndIsActiveTrue(OwnerType.INDEPENDENT_MASTER, salonOwnerId);

        assertThat(salonResults).hasSize(1);
        assertThat(salonResults).extracting(ServiceDefinition::getId).containsExactly(salonService.getId());

        assertThat(masterResults).hasSize(1);
        assertThat(masterResults).extracting(ServiceDefinition::getId).containsExactly(independentMasterService.getId());
    }
}
