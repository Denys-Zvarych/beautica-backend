package com.beautica.service.repository;

import com.beautica.auth.Role;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.ServiceCategory;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
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
class MasterServiceRepositoryTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MasterServiceRepository masterServiceRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private TestEntityManager em;

    private Master master;
    private ServiceDefinition serviceDefinition;

    @BeforeEach
    void setUp() {
        User user = new User(
                "master-" + UUID.randomUUID() + "@example.com",
                "$2a$10$hashedpassword",
                Role.INDEPENDENT_MASTER,
                "Test",
                "Master",
                "+380501234567"
        );
        em.persist(user);

        master = Master.builder()
                .user(user)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(master);

        serviceDefinition = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Gel Manicure")
                .category(ServiceCategory.MANICURE)
                .baseDurationMinutes(60)
                .basePrice(new BigDecimal("450.00"))
                .isActive(true)
                .build();
        em.persist(serviceDefinition);

        em.flush();
    }

    @Test
    @DisplayName("should_findActiveServicesByMasterId_when_masterHasServices")
    void should_findActiveServicesByMasterId_when_masterHasServices() {
        ServiceDefinition secondService = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Classic Manicure")
                .category(ServiceCategory.MANICURE)
                .baseDurationMinutes(45)
                .basePrice(new BigDecimal("300.00"))
                .isActive(true)
                .build();
        em.persist(secondService);

        MasterServiceAssignment activeOne = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build();

        MasterServiceAssignment activeTwo = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(secondService)
                .isActive(true)
                .build();

        ServiceDefinition inactiveServiceDef = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Pedicure")
                .category(ServiceCategory.PEDICURE)
                .baseDurationMinutes(90)
                .basePrice(new BigDecimal("500.00"))
                .isActive(true)
                .build();
        em.persist(inactiveServiceDef);

        MasterServiceAssignment inactiveAssignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(inactiveServiceDef)
                .isActive(false)
                .build();

        em.persist(activeOne);
        em.persist(activeTwo);
        em.persist(inactiveAssignment);
        em.flush();

        List<MasterServiceAssignment> results =
                masterServiceRepository.findByMasterIdAndIsActiveTrue(master.getId());

        assertThat(results).hasSize(2);
        assertThat(results).extracting(a -> a.getServiceDefinition().getId())
                .containsExactlyInAnyOrder(serviceDefinition.getId(), secondService.getId());
    }

    @Test
    @DisplayName("should_returnTrue_when_serviceAlreadyAssignedToMaster")
    void should_returnTrue_when_serviceAlreadyAssignedToMaster() {
        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build();
        em.persist(assignment);
        em.flush();

        boolean exists = masterServiceRepository.existsByMasterIdAndServiceDefinitionId(
                master.getId(), serviceDefinition.getId());

        assertThat(exists).isTrue();
    }

    @Test
    @DisplayName("should_returnFalse_when_serviceNotAssignedToMaster")
    void should_returnFalse_when_serviceNotAssignedToMaster() {
        User otherUser = new User(
                "other-master-" + UUID.randomUUID() + "@example.com",
                "$2a$10$hashedpassword",
                Role.INDEPENDENT_MASTER,
                "Other",
                "Master",
                "+380509876543"
        );
        em.persist(otherUser);

        Master otherMaster = Master.builder()
                .user(otherUser)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(otherMaster);
        em.flush();

        boolean exists = masterServiceRepository.existsByMasterIdAndServiceDefinitionId(
                otherMaster.getId(), serviceDefinition.getId());

        assertThat(exists).isFalse();
    }

    @Test
    @DisplayName("should_returnAssignment_when_masterIdAndIdMatch")
    void should_returnAssignment_when_masterIdAndIdMatch() {
        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build();
        em.persist(assignment);
        em.flush();

        var result = masterServiceRepository.findByMasterIdAndId(master.getId(), assignment.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(assignment.getId());
    }

    @Test
    @DisplayName("should_returnEmpty_when_masterIdDoesNotMatchAssignmentId")
    void should_returnEmpty_when_masterIdDoesNotMatchAssignmentId() {
        User otherUser = new User(
                "other-master-" + UUID.randomUUID() + "@example.com",
                "$2a$10$hashedpassword",
                Role.INDEPENDENT_MASTER,
                "Other",
                "Master",
                "+380509876543"
        );
        em.persist(otherUser);

        Master otherMaster = Master.builder()
                .user(otherUser)
                .masterType(MasterType.INDEPENDENT_MASTER)
                .avgRating(BigDecimal.ZERO)
                .reviewCount(0)
                .isActive(true)
                .build();
        em.persist(otherMaster);

        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build();
        em.persist(assignment);
        em.flush();

        var result = masterServiceRepository.findByMasterIdAndId(otherMaster.getId(), assignment.getId());

        assertThat(result).isEmpty();
    }

    // ── findByMasterIdAndIsActiveTrueWithGraph (phase 3.8: LEFT JOIN FETCH sd.serviceType) ──────

    @Test
    @DisplayName("should_initializeServiceTypeInGraph_when_serviceDefinitionHasServiceType")
    void should_initializeServiceTypeInGraph_when_serviceDefinitionHasServiceType() {
        // Arrange — persist a CatalogCategory and ServiceType, then link them to the service definition
        CatalogCategory category = CatalogCategory.builder()
                .nameUk("Нігті")
                .nameEn("Nails")
                .sortOrder(1)
                .build();
        em.persist(category);

        ServiceType serviceType = ServiceType.builder()
                .category(category)
                .nameUk("Манікюр")
                .nameEn("Manicure")
                .slug("manicure-" + UUID.randomUUID())
                .build();
        em.persist(serviceType);

        serviceDefinition.setServiceType(serviceType);
        em.persist(serviceDefinition);

        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build();
        em.persist(assignment);
        em.flush();
        em.clear(); // evict all entities to force JPA to use the query, not the first-level cache

        // Act
        List<MasterServiceAssignment> results =
                masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(master.getId());

        // Assert — serviceType must be initialized by the LEFT JOIN FETCH (no LazyInitializationException)
        assertThat(results).hasSize(1);
        ServiceDefinition loadedDef = results.get(0).getServiceDefinition();
        assertThat(loadedDef.getServiceType())
                .as("serviceType must be eagerly loaded by the LEFT JOIN FETCH — not null")
                .isNotNull();
        assertThat(loadedDef.getServiceType().getNameUk())
                .as("serviceType.nameUk must match the persisted value")
                .isEqualTo("Манікюр");
    }

    @Test
    @DisplayName("should_returnNullServiceType_when_serviceDefinitionHasNoServiceType")
    void should_returnNullServiceType_when_serviceDefinitionHasNoServiceType() {
        // Arrange — service definition has no service_type_id (nullable FK, legacy data scenario)
        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition) // no serviceType set — null FK
                .isActive(true)
                .build();
        em.persist(assignment);
        em.flush();
        em.clear();

        // Act
        List<MasterServiceAssignment> results =
                masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(master.getId());

        // Assert — LEFT JOIN means null serviceType is valid; query must still return the row
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getServiceDefinition().getServiceType())
                .as("serviceType must be null when no service_type_id is set on the definition")
                .isNull();
    }
}
