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
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
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
                .category("MANICURE")
                .baseDurationMinutes(45)
                .priceType(PriceType.FIXED)
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
                .category("PEDICURE")
                .baseDurationMinutes(90)
                .priceType(PriceType.FIXED)
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
                masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(master.getId(), PageRequest.of(0, 200));

        // Assert — LEFT JOIN means null serviceType is valid; query must still return the row
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getServiceDefinition().getServiceType())
                .as("serviceType must be null when no service_type_id is set on the definition")
                .isNull();
    }

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
            // unambiguous and decoupled from the random UUID id.
            Instant createdAt = base.plus(i, ChronoUnit.MINUTES);
            em.getEntityManager()
                    .createNativeQuery("UPDATE master_services SET created_at = :ts WHERE id = :id")
                    .setParameter("ts", createdAt)
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

    // ── findOwnedIncludingDraftsWithGraph (Phase 16.9 Part 2: owner sees drafts) ──────────────────
    //
    // The owner-scoped query drops the assignment isActive filter and widens the definition
    // predicate to (sd.isActive=true OR sd.isDraft=true). These tests pin three guarantees the
    // perf+security matrix flagged as owed:
    //   1. active + draft rows both returned for the owning master;
    //   2. cross-master isolation — another master's rows never leak;
    //   3. soft-deleted non-draft rows (isActive=false AND isDraft=false) stay excluded.

    /** Builds a second master (own user) so cross-master isolation can be exercised with real rows. */
    private Master persistOtherMaster() {
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
        return otherMaster;
    }

    @Test
    @DisplayName("should_returnActiveAndDraftRows_when_ownerListsIncludingDrafts")
    void should_returnActiveAndDraftRows_when_ownerListsIncludingDrafts() {
        // Arrange — the master owns one ACTIVE definition (from setUp) and one DRAFT definition.
        // The draft mirrors the V78 auto-materialized shape: isDraft=true, isActive=false, with an
        // INACTIVE assignment (the public query filters assignment.isActive=true, so this must still
        // come back from the owner query).
        ServiceDefinition draftDef = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Suggested Lashes")
                .category("LASHES")
                .baseDurationMinutes(0)
                .priceType(PriceType.FIXED)
                .basePrice(BigDecimal.ZERO)
                .isActive(false)
                .isDraft(true)
                .build();
        em.persist(draftDef);

        MasterServiceAssignment activeAssignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition) // active def from setUp
                .isActive(true)
                .build();

        MasterServiceAssignment draftAssignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(draftDef)
                .isActive(false) // V78 creates an inactive assignment for the draft
                .build();

        em.persist(activeAssignment);
        em.persist(draftAssignment);
        em.flush();
        em.clear();

        // Act
        List<MasterServiceAssignment> results =
                masterServiceRepository.findOwnedIncludingDraftsWithGraph(master.getId(), PageRequest.of(0, 200));

        // Assert — both the active and the draft definition are returned for the owner.
        assertThat(results).hasSize(2);
        assertThat(results)
                .extracting(a -> a.getServiceDefinition().getId())
                .containsExactlyInAnyOrder(serviceDefinition.getId(), draftDef.getId());
        assertThat(results)
                .extracting(a -> a.getServiceDefinition().isDraft())
                .as("the draft row must be surfaced with isDraft=true alongside the active row")
                .containsExactlyInAnyOrder(false, true);
    }

    @Test
    @DisplayName("should_excludeOtherMastersRows_when_ownerListsIncludingDrafts")
    void should_excludeOtherMastersRows_when_ownerListsIncludingDrafts() {
        // Arrange — Master A (the setUp master) owns a DRAFT; Master B owns an ACTIVE service.
        // Querying A's id must return only A's draft and never B's row (cross-master isolation).
        ServiceDefinition masterADraft = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("A Draft")
                .category("MANICURE")
                .baseDurationMinutes(0)
                .priceType(PriceType.FIXED)
                .basePrice(BigDecimal.ZERO)
                .isActive(false)
                .isDraft(true)
                .build();
        em.persist(masterADraft);

        MasterServiceAssignment masterAAssignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(masterADraft)
                .isActive(false)
                .build();
        em.persist(masterAAssignment);

        Master masterB = persistOtherMaster();
        ServiceDefinition masterBActive = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(masterB.getId())
                .name("B Active")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("500.00"))
                .isActive(true)
                .build();
        em.persist(masterBActive);

        MasterServiceAssignment masterBAssignment = MasterServiceAssignment.builder()
                .master(masterB)
                .serviceDefinition(masterBActive)
                .isActive(true)
                .build();
        em.persist(masterBAssignment);
        em.flush();
        em.clear();

        // Act — query Master A only
        List<MasterServiceAssignment> results =
                masterServiceRepository.findOwnedIncludingDraftsWithGraph(master.getId(), PageRequest.of(0, 200));

        // Assert — only A's draft; B's active row must NOT leak across the master boundary.
        assertThat(results).hasSize(1);
        assertThat(results)
                .extracting(a -> a.getServiceDefinition().getId())
                .containsExactly(masterADraft.getId())
                .doesNotContain(masterBActive.getId());
        assertThat(results)
                .extracting(a -> a.getMaster().getId())
                .as("every returned row must belong to the queried master")
                .containsOnly(master.getId());
    }

    @Test
    @DisplayName("should_excludeSoftDeletedNonDraftRows_when_ownerListsIncludingDrafts")
    void should_excludeSoftDeletedNonDraftRows_when_ownerListsIncludingDrafts() {
        // Arrange — the master owns its active definition (setUp) plus a soft-deleted NON-draft
        // definition (isActive=false AND isDraft=false). The widened predicate must still exclude
        // the soft-deleted non-draft row — only the draft branch relaxes the activity filter.
        ServiceDefinition softDeletedDef = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Retired Pedicure")
                .category("PEDICURE")
                .baseDurationMinutes(90)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("500.00"))
                .isActive(false)
                .isDraft(false)
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
                .isActive(true) // assignment row left active by soft-delete, like the public-query bug
                .build();

        em.persist(activeAssignment);
        em.persist(softDeletedAssignment);
        em.flush();
        em.clear();

        // Act
        List<MasterServiceAssignment> results =
                masterServiceRepository.findOwnedIncludingDraftsWithGraph(master.getId(), PageRequest.of(0, 200));

        // Assert — soft-deleted non-draft definition is hidden; only the active definition remains.
        assertThat(results).hasSize(1);
        assertThat(results)
                .extracting(a -> a.getServiceDefinition().getId())
                .containsExactly(serviceDefinition.getId())
                .doesNotContain(softDeletedDef.getId());
    }

    @Test
    @DisplayName("should_excludeDrafts_when_publicQueryUsedOnMasterWithDraft (regression guard on the public/owner divergence)")
    void should_excludeDrafts_when_publicQueryUsedOnMasterWithDraft() {
        // Arrange — same master owns an active definition (setUp) and a draft. This pins that the
        // PUBLIC browse query continues to EXCLUDE drafts even though the owner query includes them,
        // guarding the public/owner divergence introduced by Phase 16.9 Part 2.
        ServiceDefinition draftDef = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(master.getId())
                .name("Hidden Draft")
                .category("MANICURE")
                .baseDurationMinutes(0)
                .priceType(PriceType.FIXED)
                .basePrice(BigDecimal.ZERO)
                .isActive(false)
                .isDraft(true)
                .build();
        em.persist(draftDef);

        MasterServiceAssignment activeAssignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(serviceDefinition)
                .isActive(true)
                .build();

        MasterServiceAssignment draftAssignment = MasterServiceAssignment.builder()
                .master(master)
                .serviceDefinition(draftDef)
                .isActive(false)
                .build();

        em.persist(activeAssignment);
        em.persist(draftAssignment);
        em.flush();
        em.clear();

        // Act — the PUBLIC query
        List<MasterServiceAssignment> publicResults =
                masterServiceRepository.findByMasterIdAndIsActiveTrueWithGraph(master.getId(), PageRequest.of(0, 200));

        // Assert — the public browse must NOT surface the draft (divergence preserved).
        assertThat(publicResults).hasSize(1);
        assertThat(publicResults)
                .extracting(a -> a.getServiceDefinition().getId())
                .containsExactly(serviceDefinition.getId())
                .doesNotContain(draftDef.getId());
    }
}
