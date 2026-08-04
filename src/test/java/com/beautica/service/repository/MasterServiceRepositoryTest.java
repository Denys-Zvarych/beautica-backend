package com.beautica.service.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.master.entity.Master;
import com.beautica.master.entity.MasterType;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.MasterServiceAssignment;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MasterServiceRepositoryTest extends AbstractDataJpaTest {

    @Autowired
    private MasterServiceRepository masterServiceRepository;

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private TestEntityManager em;

    private Master master;
    private ServiceDefinition serviceDefinition;
    private CatalogCategory defaultCategory;
    private ServiceType defaultServiceType;

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

        // service_type_id is mandatory (NOT NULL). Persist one shared ServiceType and
        // attach it to every fixture ServiceDefinition via the builder.
        defaultServiceType = persistServiceType("Манікюр", "Manicure");

        serviceDefinition = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Gel Manicure")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("450.00"))
                .serviceType(defaultServiceType)
                .isActive(true)
                .build();
        em.persist(serviceDefinition);

        em.flush();
    }

    /**
     * Persists a ServiceType (creating the shared CatalogCategory on first use) so fixture
     * ServiceDefinitions satisfy the NOT NULL service_type_id FK.
     *
     * <p>Every call returns a DISTINCT ServiceType. That matters since V121: the partial unique
     * index {@code ux_service_def_owner_service_type_active} allows only ONE active
     * ServiceDefinition per {@code (owner_type, owner_id, service_type_id)}, so any fixture that
     * gives one master several active services must give each of them its own service type.
     * Reusing {@link #defaultServiceType} across active definitions is now a constraint violation.
     */
    private ServiceType persistServiceType(String nameUk, String nameEn) {
        if (defaultCategory == null) {
            // sort_order has a UNIQUE constraint; keep this value distinct from the 999 used by
            // the graph test's own category so both can coexist in one test. Created once and
            // reused so repeated calls cannot collide on sort_order.
            defaultCategory = CatalogCategory.builder()
                    .nameUk("Нігті")
                    .nameEn("Nails")
                    .sortOrder(500)
                    .build();
            em.persist(defaultCategory);
        }

        ServiceType serviceType = ServiceType.builder()
                .category(defaultCategory)
                .nameUk(nameUk)
                .nameEn(nameEn)
                .slug("type-" + UUID.randomUUID())
                .platformCategoryName("NAIL_SERVICE")
                .build();
        em.persist(serviceType);
        return serviceType;
    }

    @Test
    @DisplayName("should_findActiveServicesByMasterId_when_masterHasServices")
    void should_findActiveServicesByMasterId_when_masterHasServices() {
        ServiceDefinition secondService = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Classic Manicure")
                .category("MANICURE")
                .baseDurationMinutes(45)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("300.00"))
                // Distinct type: V121 allows only one ACTIVE definition per (owner, type).
                .serviceType(persistServiceType("Класичний манікюр", "Classic Manicure"))
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
                .category("PEDICURE")
                .baseDurationMinutes(90)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("500.00"))
                // Distinct type: V121 allows only one ACTIVE definition per (owner, type).
                // (The definition is active here — it is the ASSIGNMENT below that is inactive.)
                .serviceType(persistServiceType("Педикюр", "Pedicure"))
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
                masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(master.getId(), PageRequest.of(0, 200));

        assertThat(results).hasSize(2);
        assertThat(results).extracting(a -> a.getServiceDefinition().getId())
                .containsExactlyInAnyOrder(serviceDefinition.getId(), secondService.getId());
    }

    @Test
    @DisplayName("should_excludeService_when_serviceDefinitionIsSoftDeleted")
    void should_excludeService_when_serviceDefinitionIsSoftDeleted() {
        // Arrange — two active assignments; one definition is soft-deleted (isActive=false)
        // while its assignment row stays active, reproducing the soft-delete bug.
        ServiceDefinition deactivatedDef = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Classic Manicure")
                .category("MANICURE")
                .baseDurationMinutes(45)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("300.00"))
                .serviceType(defaultServiceType)
                .isActive(false)
                .build();
        em.persist(deactivatedDef);

        MasterServiceAssignment activeAssignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build();

        MasterServiceAssignment assignmentOnDeactivatedDef = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(deactivatedDef)
                .isActive(true)
                .build();

        em.persist(activeAssignment);
        em.persist(assignmentOnDeactivatedDef);
        em.flush();
        em.clear();

        // Act
        List<MasterServiceAssignment> results =
                masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(master.getId(), PageRequest.of(0, 200));

        // Assert — only the active-definition row is returned; the soft-deleted one is hidden
        assertThat(results).hasSize(1);
        assertThat(results).extracting(a -> a.getServiceDefinition().getId())
                .containsExactly(serviceDefinition.getId())
                .doesNotContain(deactivatedDef.getId());
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
                .sortOrder(999)
                .build();
        em.persist(category);

        ServiceType serviceType = ServiceType.builder()
                .category(category)
                .nameUk("Манікюр")
                .nameEn("Manicure")
                .slug("manicure-" + UUID.randomUUID())
                .platformCategoryName("NAIL_SERVICE")
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
                masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(master.getId(), PageRequest.of(0, 200));

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

    // NOTE (V111): the former test `should_returnNullServiceType_when_serviceDefinitionHasNoServiceType`
    // was deleted. service_definitions.service_type_id is now NOT NULL (JPA optional=false), so a
    // ServiceDefinition with a null serviceType can no longer be persisted — the scenario it asserted
    // (LEFT JOIN returning a row with a null type) is unreachable under the new contract.

    // ── ordering regression (V68 / ORDER BY msa.createdAt ASC, msa.id ASC) ───────────────────────
    //
    // Bug: the query previously ended with `ORDER BY msa.id`, where msa.id is a random
    // (v4) UUID. A master's services therefore came back in an arbitrary, non-deterministic
    // order instead of creation order. Fix: `ORDER BY msa.createdAt ASC, msa.id ASC`.
    //
    // These tests pin creation-order ordering: they FAIL against the old `ORDER BY msa.id`
    // (random UUID order) and PASS with the createdAt-based ordering.

    @Test
    @DisplayName("should_returnServicesInCreationOrder_when_masterHasMultipleServices")
    void should_returnServicesInCreationOrder_when_masterHasMultipleServices() {
        // Arrange — 5 assignments for the same master with strictly increasing createdAt
        // timestamps assigned in a known sequence. createdAt is @CreationTimestamp (not
        // builder-settable), so we persist each row then stamp an explicit, controlled
        // created_at via native SQL. The expected order is the creation sequence below,
        // which is independent of the random UUID primary key.
        List<UUID> expectedDefIdsInCreationOrder = new ArrayList<>();
        Instant base = Instant.parse("2026-01-01T08:00:00Z");

        for (int i = 0; i < 5; i++) {
            ServiceDefinition def = ServiceDefinition.builder()
                    .ownerType(OwnerType.INDEPENDENT_MASTER)
                    .ownerId(master.getId())
                    .name("Service-" + i)
                    .category("MANICURE")
                    .baseDurationMinutes(30 + i)
                    .priceType(PriceType.FIXED)
                    .basePrice(new BigDecimal("100.00"))
                    // Distinct type per iteration: V121 allows only one ACTIVE definition
                    // per (owner, type), and all 5 belong to the same master.
                    .serviceType(persistServiceType("Тип-" + i, "Type-" + i))
                    .isActive(true)
                    .build();
            em.persist(def);

            MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                    .master(master)
                    .serviceDefinition(def)
                    .isActive(true)
                    .build();
            em.persist(assignment);
            em.flush();

            // Stamp a deterministic, strictly increasing created_at so creation order is
            // unambiguous and decoupled from the id. We also overwrite the Hibernate-generated
            // UUID with a deterministic id whose lexicographic order is the REVERSE of the
            // creation order (i=0 -> highest id, i=4 -> lowest). This guarantees the guard below
            // ("id ordering differs from creation ordering") always holds instead of relying on
            // a 1/120 random-UUID coincidence, which made this test flaky (PR #78 CI).
            Instant createdAt = base.plus(i, ChronoUnit.MINUTES);
            UUID deterministicId = UUID.fromString(
                    String.format("%08d-0000-4000-8000-000000000000", 4 - i));
            em.getEntityManager()
                    .createNativeQuery("UPDATE master_services SET created_at = :ts, id = :newId WHERE id = :id")
                    .setParameter("ts", createdAt)
                    .setParameter("newId", deterministicId)
                    .setParameter("id", assignment.getId())
                    .executeUpdate();

            expectedDefIdsInCreationOrder.add(def.getId());
        }
        em.flush();
        em.clear(); // force the query to read from the DB, not the first-level cache

        // Sanity check: the random UUID order is NOT the creation order — otherwise this
        // test could pass under the buggy `ORDER BY msa.id` by coincidence. With 5 random
        // v4 UUIDs the chance of accidental coincidence is 1/120; assert it explicitly so a
        // flaky pass can never mask a real ordering regression.
        List<MasterServiceAssignment> rawById = masterServiceRepository
                .findByMasterIdAndIsActiveTrueWithGraph(master.getId(), PageRequest.of(0, 200))
                .stream()
                .sorted(Comparator.comparing(MasterServiceAssignment::getId))
                .toList();
        List<UUID> defIdsSortedByAssignmentId = rawById.stream()
                .map(a -> a.getServiceDefinition().getId())
                .toList();
        assertThat(defIdsSortedByAssignmentId)
                .as("guard: UUID-id ordering must differ from creation ordering, "
                        + "otherwise the buggy ORDER BY msa.id could pass by coincidence")
                .isNotEqualTo(expectedDefIdsInCreationOrder);

        // Act
        List<MasterServiceAssignment> results =
                masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(master.getId(), PageRequest.of(0, 200));

        // Assert — services come back in creation order (createdAt ASC), NOT random UUID order
        assertThat(results).hasSize(5);
        assertThat(results)
                .extracting(a -> a.getServiceDefinition().getId())
                .as("services must be returned in creation order (ORDER BY createdAt ASC), "
                        + "not by the random UUID primary key")
                .containsExactlyElementsOf(expectedDefIdsInCreationOrder);
    }

    @Test
    @DisplayName("should_breakTiesById_when_servicesShareSameCreatedAt")
    void should_breakTiesById_when_servicesShareSameCreatedAt() {
        // Arrange — 3 assignments with the IDENTICAL created_at value. The ordering is then
        // fully determined by the `, msa.id ASC` tiebreaker, making the result deterministic
        // even when timestamps collide (e.g. several rows inserted in the same millisecond).
        Instant sameTs = Instant.parse("2026-01-01T08:00:00Z");
        List<UUID> assignmentIds = new ArrayList<>();
        List<UUID> defIdByAssignment = new ArrayList<>();

        for (int i = 0; i < 3; i++) {
            ServiceDefinition def = ServiceDefinition.builder()
                    .ownerType(OwnerType.INDEPENDENT_MASTER)
                    .ownerId(master.getId())
                    .name("Tie-" + i)
                    .category("MANICURE")
                    .baseDurationMinutes(30)
                    .priceType(PriceType.FIXED)
                    .basePrice(new BigDecimal("100.00"))
                    // Distinct type per iteration: V121 allows only one ACTIVE definition
                    // per (owner, type), and all 3 belong to the same master.
                    .serviceType(persistServiceType("Звʼязка-" + i, "Tie-" + i))
                    .isActive(true)
                    .build();
            em.persist(def);

            MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                    .master(master)
                    .serviceDefinition(def)
                    .isActive(true)
                    .build();
            em.persist(assignment);
            em.flush();

            em.getEntityManager()
                    .createNativeQuery("UPDATE master_services SET created_at = :ts WHERE id = :id")
                    .setParameter("ts", sameTs)
                    .setParameter("id", assignment.getId())
                    .executeUpdate();

            assignmentIds.add(assignment.getId());
            defIdByAssignment.add(def.getId());
        }
        em.flush();
        em.clear();

        // Expected order when createdAt is equal: assignments sorted by their id ASC,
        // using PostgreSQL's UUID ordering. Postgres compares the 16 bytes as UNSIGNED,
        // whereas Java's UUID.compareTo treats each 64-bit half as a SIGNED long — the two
        // disagree whenever the most-significant bit is set. Replicate the DB's unsigned
        // byte order here so the expected order matches `ORDER BY msa.id` in the query.
        Comparator<UUID> postgresUuidOrder = Comparator
                .comparingLong((UUID u) -> u.getMostSignificantBits() + Long.MIN_VALUE)
                .thenComparingLong(u -> u.getLeastSignificantBits() + Long.MIN_VALUE);
        List<UUID> expectedAssignmentIdOrder = assignmentIds.stream().sorted(postgresUuidOrder).toList();

        // Act
        List<MasterServiceAssignment> results =
                masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(master.getId(), PageRequest.of(0, 200));

        // Assert — equal timestamps fall back to the id ASC tiebreaker (stable order)
        assertThat(results).hasSize(3);
        assertThat(results)
                .extracting(MasterServiceAssignment::getId)
                .as("equal createdAt must fall back to the `msa.id ASC` tiebreaker for a stable order")
                .containsExactlyElementsOf(expectedAssignmentIdOrder);
    }

    // ── existsActiveServiceForMaster (menu-emptiness predicate) ──────────────────
    //
    // This backed the old "bulk setup is first-time only" precondition, removed when bulk create
    // became additive. The query itself is retained as a menu-emptiness predicate, and these
    // tests keep pinning its two-level semantics: an assignment counts only when BOTH the
    // assignment and its definition are active.

    @Test
    @DisplayName("should_returnTrue_when_masterHasAnActiveServiceWithActiveDefinition")
    void should_returnTrue_when_masterHasAnActiveServiceWithActiveDefinition() {
        // setUp persisted an active ServiceDefinition; attach an active assignment.
        MasterServiceAssignment assignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build();
        em.persist(assignment);
        em.flush();

        boolean hasActive = masterServiceRepository.existsActiveServiceForMaster(master.getId());

        assertThat(hasActive)
                .as("an active assignment on an active definition means the master's menu is non-empty")
                .isTrue();
    }

    @Test
    @DisplayName("should_returnFalse_when_masterHasNoAssignments")
    void should_returnFalse_when_masterHasNoAssignments() {
        // No assignments persisted for this master at all.
        boolean hasActive = masterServiceRepository.existsActiveServiceForMaster(master.getId());

        assertThat(hasActive)
                .as("a master with zero assignments has an empty menu")
                .isFalse();
    }

    @Test
    @DisplayName("should_returnFalse_when_onlyAssignmentReferencesSoftDeletedDefinition")
    void should_returnFalse_when_onlyAssignmentReferencesSoftDeletedDefinition() {
        // A soft-deleted definition must NOT count as an active service — a master who deleted
        // all their services has an empty menu, not a menu of tombstones.
        ServiceDefinition softDeletedDef = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Retired Service")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("500.00"))
                .serviceType(defaultServiceType)
                .isActive(false)
                .build();
        em.persist(softDeletedDef);

        MasterServiceAssignment assignmentOnSoftDeleted = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(softDeletedDef)
                .isActive(true)
                .build();
        em.persist(assignmentOnSoftDeleted);
        em.flush();

        boolean hasActive = masterServiceRepository.existsActiveServiceForMaster(master.getId());

        assertThat(hasActive)
                .as("a soft-deleted (inactive) definition must not count as an active service")
                .isFalse();
    }

    @Test
    @DisplayName("should_returnFalse_when_onlyAssignmentIsInactive")
    void should_returnFalse_when_onlyAssignmentIsInactive() {
        // The assignment itself is inactive, even though the definition is active.
        MasterServiceAssignment inactiveAssignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(false)
                .build();
        em.persist(inactiveAssignment);
        em.flush();

        boolean hasActive = masterServiceRepository.existsActiveServiceForMaster(master.getId());

        assertThat(hasActive)
                .as("an inactive assignment must not count as an active service")
                .isFalse();
    }

    @Test
    @DisplayName("should_excludeSoftDeletedDef_when_publicQueryUsedOnMasterWithInactiveDef")
    void should_excludeSoftDeletedDef_when_publicQueryUsedOnMasterWithInactiveDef() {
        // Arrange — same master owns an active definition (setUp) and a soft-deleted (inactive)
        // definition. The public browse query must NOT surface the inactive definition.
        ServiceDefinition softDeletedDef = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Retired Service")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("500.00"))
                .serviceType(defaultServiceType)
                .isActive(false)
                .build();
        em.persist(softDeletedDef);

        MasterServiceAssignment activeAssignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build();

        MasterServiceAssignment softDeletedAssignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(softDeletedDef)
                .isActive(false)
                .build();

        em.persist(activeAssignment);
        em.persist(softDeletedAssignment);
        em.flush();
        em.clear();

        // Act — the PUBLIC query
        List<MasterServiceAssignment> publicResults =
                masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(master.getId(), PageRequest.of(0, 200));

        // Assert — the public browse must NOT surface the inactive definition.
        assertThat(publicResults).hasSize(1);
        assertThat(publicResults)
                .extracting(a -> a.getServiceDefinition().getId())
                .containsExactly(serviceDefinition.getId())
                .doesNotContain(softDeletedDef.getId());
    }
}
