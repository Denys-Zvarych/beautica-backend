package com.beautica.service.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.user.User;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Proves the V233 partial unique index {@code ux_service_def_owner_type_duration_name_active}
 * (phase 464, replacing V121's per-type index) — one ACTIVE {@link ServiceDefinition} per
 * {@code (owner_type, owner_id, service_type_id, base_duration_minutes, lower(btrim(name)))} —
 * actually bites at the storage layer, and that both
 * {@link ServiceRepository#findActiveDuplicateId} (single-item, PATCH + create) and
 * {@link ServiceRepository#findActiveDuplicateCopies} (batched, bulk create) agree with it.
 *
 * <p>These run against real PostgreSQL (Testcontainers via {@link AbstractDataJpaTest}); an
 * in-memory DB would not evaluate the partial index at all, so the constraint must be exercised
 * here rather than in a mocked service test. Two properties in particular exist ONLY here:
 * the dialect's constraint-name extraction (which the service's {@code DUPLICATE_SERVICE}
 * classification depends on entirely) and the batched finder's owner scoping (which Mockito
 * stubs in {@code ServiceCatalogServiceBulkCreateTest} cannot prove — a stub returns whatever
 * it is told, regardless of what the JPQL {@code WHERE} clause actually says).
 */
class ServiceDefinitionUniqueActiveCopyTest extends AbstractDataJpaTest {

    private static final String INDEX_NAME = "ux_service_def_owner_type_duration_name_active";

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private TestEntityManager em;

    private UUID salonId;
    private ServiceType manicureType;

    @BeforeEach
    void setUp() {
        // Real BCrypt at the cheapest work factor — never a hand-written fake hash (anti-bug §M5).
        User owner = new User(
                "salon-owner-" + UUID.randomUUID() + "@example.com",
                new BCryptPasswordEncoder(4).encode("test-password"),
                Role.SALON_OWNER,
                "Salon",
                "Owner",
                "+380501234567");
        em.persist(owner);

        Salon salon = Salon.builder()
                .cityId(testCityId())
                .owner(owner)
                .name("Beauty Studio")
                .isActive(true)
                .build();
        em.persist(salon);

        manicureType = persistServiceType("Класичне нарощення", "Classic Extension");

        em.flush();
        salonId = salon.getId();
    }

    private ServiceType persistServiceType(String nameUk, String nameEn) {
        CatalogCategory category = CatalogCategory.builder()
                .nameUk("Нігті-" + UUID.randomUUID())
                .nameEn("Nails-" + UUID.randomUUID())
                .sortOrder(ThreadLocalSortOrder.next())
                .build();
        em.persist(category);

        ServiceType serviceType = ServiceType.builder()
                .category(category)
                .nameUk(nameUk)
                .nameEn(nameEn)
                .slug("type-" + UUID.randomUUID())
                .platformCategoryName("NAIL_SERVICE")
                .build();
        em.persist(serviceType);
        return serviceType;
    }

    /** sort_order carries a UNIQUE constraint; hand out a fresh value per call. */
    private static final class ThreadLocalSortOrder {
        private static int counter = 7000;

        private static int next() {
            return ++counter;
        }
    }

    private ServiceDefinition definition(UUID ownerId, ServiceType type, String name,
            String price, int durationMinutes, boolean active) {
        return definition(OwnerType.SALON, ownerId, type, name, price, durationMinutes, active);
    }

    private ServiceDefinition definition(OwnerType ownerType, UUID ownerId, ServiceType type, String name,
            String price, int durationMinutes, boolean active) {
        return ServiceDefinition.builder()
                .ownerType(ownerType)
                .ownerId(ownerId)
                .name(name)
                .category("MANICURE")
                .baseDurationMinutes(durationMinutes)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal(price))
                .serviceType(type)
                .isActive(active)
                .build();
    }

    @Test
    @DisplayName("V233 index — same type + same duration + same name collides even at a different price")
    void should_rejectSecondActiveCopy_when_sameTypeDurationAndNameDifferOnlyInPrice() {
        // Arrange — the owner already offers «Класичне нарощення» 60 хв.
        serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 60, true));

        // Act + Assert — price never participates (phase 464 D3).
        assertThatThrownBy(() -> serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "900.00", 60, true)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(INDEX_NAME);
    }

    @Test
    @DisplayName("V233 index — a name differing only in case and surrounding spaces collides (lower(btrim(name)))")
    void should_rejectSecondActiveCopy_when_nameDiffersOnlyInCaseAndSpaces() {
        // Arrange
        serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 60, true));

        // Act + Assert
        assertThatThrownBy(() -> serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "  класичне НАРОЩЕННЯ ", "500.00", 60, true)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining(INDEX_NAME);
    }

    @Test
    @DisplayName("V233 index — the same type with a DIFFERENT base duration is a legal second copy")
    void should_allowSecondActiveCopy_when_durationDiffers() {
        // Arrange
        serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 60, true));

        // Act + Assert
        assertThatCode(() -> serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 90, true)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("V233 index — the same type and duration with a DIFFERENT name is a legal second copy")
    void should_allowSecondActiveCopy_when_nameDiffers() {
        // Arrange
        serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 60, true));

        // Act + Assert
        assertThatCode(() -> serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Нарощення авторське", "500.00", 60, true)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("V233 index — the Postgres dialect populates getConstraintName() for a PARTIAL unique index (the whole DUPLICATE_SERVICE branch depends on it)")
    void should_reportConstraintName_when_partialUniqueIndexViolated() {
        // Arrange
        serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 60, true));

        // Act
        Throwable thrown = catchThrowable(() -> serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "900.00", 60, true)));

        // Assert — ServiceCatalogService.isDuplicateServiceViolation matches ONLY on the structured
        // getConstraintName(), so the whole DUPLICATE_SERVICE branch hangs on the Postgres dialect
        // populating it for a PARTIAL unique index. The service unit tests cannot cover this: they
        // hand-build the exception with the name already set, asserting the branch rather than the
        // extraction. If the dialect ever returned null or a schema-qualified name, every genuine
        // duplicate race would silently degrade to a generic 409 with no `data.code` — the mobile
        // add-service screen would lose its branch — with all other tests still green.
        assertThat(thrown).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(constraintNameOf(thrown)).isEqualTo(INDEX_NAME);
    }

    /** Mirrors the production cause-walk in {@code isDuplicateServiceViolation}. */
    private static String constraintNameOf(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException hibernateViolation) {
                return hibernateViolation.getConstraintName();
            }
        }
        return null;
    }

    @Test
    @DisplayName("V233 index — partial on is_active, so a soft-deleted service stays re-creatable")
    void should_allowRecreation_when_previousDefinitionWasSoftDeleted() {
        // Arrange — the owner had this service and deleted it (soft delete flips is_active).
        ServiceDefinition first = serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 60, true));
        serviceRepository.deactivateById(first.getId());
        em.flush();
        em.clear();

        // Act + Assert — the index is PARTIAL on is_active = true, so re-adding the service works.
        // An unconditional unique constraint would make this permanently uncreatable.
        assertThatCode(() -> serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "650.00", 60, true)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("V233 index — scoped per owner: two providers may both offer the same service type")
    void should_allowSameServiceType_when_ownerIsDifferent() {
        // Arrange
        serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 60, true));
        UUID otherOwnerId = UUID.randomUUID();

        // Act + Assert — the key is scoped per owner; two providers may both offer the same type.
        assertThatCode(() -> serviceRepository.saveAndFlush(
                definition(otherOwnerId, manicureType, "Класичне нарощення", "500.00", 60, true)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("V233 index — scoped per owner_type too: the same UUID may be an active SALON owner and an active INDEPENDENT_MASTER owner")
    void should_allowSameOwnerId_when_ownerTypeDiffers() {
        // Arrange — a SALON offers «Класичне нарощення» under ownerId = salonId.
        serviceRepository.saveAndFlush(
                definition(OwnerType.SALON, salonId, manicureType, "Класичне нарощення", "500.00", 60, true));

        // Act + Assert — the SAME UUID, reused as the ownerId of an INDEPENDENT_MASTER-owned
        // definition of the SAME service type, must not collide. owner_id is a POLYMORPHIC,
        // un-FK'd column (V6:2 — "owner_type is polymorphic ('SALON' | 'INDEPENDENT_MASTER');
        // no FK to salons/masters"), so a bare UUID collision across the two owner kinds is legal
        // and expected; reusing salonId here is the only way to isolate the owner_type dimension
        // of the index from the owner_id dimension the sibling test above already pins. If
        // owner_type were dropped from the index key, this insert would 409 a legitimate,
        // unrelated master's setup purely because it happens to share a UUID with an existing salon.
        assertThatCode(() -> serviceRepository.saveAndFlush(definition(
                OwnerType.INDEPENDENT_MASTER, salonId, manicureType, "Класичне нарощення", "500.00", 60, true)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("findActiveDuplicateId — returns the colliding definition id for the 409 payload")
    void should_returnExistingId_when_activeDuplicateExists() {
        // Arrange
        ServiceDefinition existing = serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 60, true));

        // Act
        Optional<UUID> found = serviceRepository.findActiveDuplicateId(
                OwnerType.SALON, salonId, manicureType.getId(), 60, "Класичне нарощення", null);

        // Assert
        assertThat(found).contains(existing.getId());
    }

    @Test
    @DisplayName("findActiveDuplicateId — compares the name lower(trim(...)) on both sides, like the index")
    void should_returnExistingId_when_probeNameDiffersOnlyInCaseAndSpaces() {
        // Arrange
        ServiceDefinition existing = serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 60, true));

        // Act
        Optional<UUID> found = serviceRepository.findActiveDuplicateId(
                OwnerType.SALON, salonId, manicureType.getId(), 60, " класичне НАРОЩЕННЯ  ", null);

        // Assert
        assertThat(found).contains(existing.getId());
    }

    @Test
    @DisplayName("findActiveDuplicateId — a different duration or a different name is not a duplicate")
    void should_returnEmpty_when_durationOrNameDiffers() {
        // Arrange
        serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 60, true));

        // Act
        Optional<UUID> otherDuration = serviceRepository.findActiveDuplicateId(
                OwnerType.SALON, salonId, manicureType.getId(), 90, "Класичне нарощення", null);
        Optional<UUID> otherName = serviceRepository.findActiveDuplicateId(
                OwnerType.SALON, salonId, manicureType.getId(), 60, "Нарощення авторське", null);

        // Assert
        assertThat(otherDuration).isEmpty();
        assertThat(otherName).isEmpty();
    }

    @Test
    @DisplayName("findActiveDuplicateId — excludeId keeps a no-op PATCH from detecting itself as its own duplicate")
    void should_returnEmpty_when_theOnlyMatchIsTheExcludedRow() {
        // Arrange — a no-op PATCH on a row must not detect itself as its own duplicate.
        ServiceDefinition existing = serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 60, true));

        // Act
        Optional<UUID> found = serviceRepository.findActiveDuplicateId(
                OwnerType.SALON, salonId, manicureType.getId(), 60, "Класичне нарощення", existing.getId());

        // Assert
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findActiveDuplicateId — mirrors the index predicate: a soft-deleted row is not a duplicate")
    void should_returnEmpty_when_theOnlyMatchIsSoftDeleted() {
        // Arrange — mirrors the index's is_active = true predicate.
        ServiceDefinition existing = serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 60, true));
        serviceRepository.deactivateById(existing.getId());
        em.flush();
        em.clear();

        // Act
        Optional<UUID> found = serviceRepository.findActiveDuplicateId(
                OwnerType.SALON, salonId, manicureType.getId(), 60, "Класичне нарощення", null);

        // Assert
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findActiveDuplicateId — mirrors the index predicate: a row of a DIFFERENT owner_type is not a duplicate")
    void should_returnEmpty_when_theOnlyMatchIsAnotherOwnerType() {
        // Arrange — an INDEPENDENT_MASTER offers «Класичне нарощення» under ownerId = salonId
        // (legal: owner_id is polymorphic and un-FK'd, per V6:2 — see the sibling index-level test).
        serviceRepository.saveAndFlush(definition(
                OwnerType.INDEPENDENT_MASTER, salonId, manicureType, "Класичне нарощення", "500.00", 60, true));

        // Act — probing for a SALON duplicate at the exact same (ownerId, serviceTypeId).
        Optional<UUID> found = serviceRepository.findActiveDuplicateId(
                OwnerType.SALON, salonId, manicureType.getId(), 60, "Класичне нарощення", null);

        // Assert — the finder must mirror the index's full key, including owner_type. If
        // `sd.ownerType = :ownerType` were dropped from the JPQL WHERE clause, this would return
        // the INDEPENDENT_MASTER row's id and wrongly 409 a legitimate SALON create with a
        // DUPLICATE_SERVICE payload pointing at a definition owned by an entirely different party.
        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("findActiveDuplicateCopies — owner scoping keeps a foreign provider’s definition id out of the 409 payload")
    void should_excludeOtherOwners_when_batchResolvingDuplicates() {
        // Arrange — the SAME service type is offered actively by two different owners.
        ServiceDefinition ownedDefinition = serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 60, true));
        serviceRepository.saveAndFlush(definition(
                UUID.randomUUID(), manicureType, "Класичне нарощення", "700.00", 60, true));

        // Act
        List<ActiveDuplicateProjection> found = serviceRepository.findActiveDuplicateCopies(
                OwnerType.SALON.name(), salonId, new UUID[] {manicureType.getId()},
                new Integer[] {60}, new String[] {"Класичне нарощення"});

        // Assert — owner scoping is this finder's security-critical property: it is unscoped by
        // convention (§E-4) only in the sense that the CALLER must prove write access; the owner
        // predicate itself is the sole thing keeping another provider's definition ids out of a
        // DUPLICATE_SERVICE 409 (which carries existingServiceDefId to the client). Dropping
        // `sd.ownerId = :ownerId` would leak a foreign UUID and block a legitimate bulk setup.
        assertThat(found)
                .extracting(ActiveDuplicateProjection::serviceTypeId,
                        ActiveDuplicateProjection::serviceDefinitionId)
                .containsExactly(tuple(manicureType.getId(), ownedDefinition.getId()));
    }

    @Test
    @DisplayName("findActiveDuplicateCopies — returns only ACTIVE owned rows, each type paired with its own definition id")
    void should_returnOnlyActiveOwnedPairs_when_batchResolvingMixedRows() {
        // Arrange — one owner with two active types + one soft-deleted type, plus a foreign owner
        // holding one of the same types. Only the two active owned rows may come back, correctly
        // paired: the constructor-expression projection returns two bare UUIDs of identical type,
        // so a swapped argument order would be invisible to anything but a pair assertion.
        ServiceType pedicureType = persistServiceType("Педикюр", "Pedicure");
        ServiceType spaType = persistServiceType("СПА-догляд", "Spa Care");
        em.flush();

        ServiceDefinition activeManicure = serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 60, true));
        ServiceDefinition activePedicure = serviceRepository.saveAndFlush(
                definition(salonId, pedicureType, "Педикюр", "600.00", 90, true));
        ServiceDefinition softDeletedSpa = serviceRepository.saveAndFlush(
                definition(salonId, spaType, "СПА-догляд", "800.00", 120, true));
        serviceRepository.saveAndFlush(
                definition(UUID.randomUUID(), pedicureType, "Педикюр", "650.00", 90, true));

        serviceRepository.deactivateById(softDeletedSpa.getId());
        em.flush();
        em.clear();

        // Act
        List<ActiveDuplicateProjection> found = serviceRepository.findActiveDuplicateCopies(
                OwnerType.SALON.name(), salonId,
                new UUID[] {manicureType.getId(), pedicureType.getId(), spaType.getId()},
                new Integer[] {60, 90, 120},
                new String[] {"Класичне нарощення", "Педикюр", "СПА-догляд"});

        // Assert — row order is unspecified, hence containsExactlyInAnyOrder.
        assertThat(found)
                .extracting(ActiveDuplicateProjection::serviceTypeId,
                        ActiveDuplicateProjection::serviceDefinitionId)
                .containsExactlyInAnyOrder(
                        tuple(manicureType.getId(), activeManicure.getId()),
                        tuple(pedicureType.getId(), activePedicure.getId()));
    }

    @Test
    @DisplayName("findActiveDuplicateCopies — matches the zipped (type, duration, name) tuple per item, never the cross product")
    void should_matchOnlyZippedTuples_when_batchHoldsSeveralCopiesOfOneType() {
        // Arrange — the owner holds «Класичне нарощення» at 60 only.
        ServiceDefinition held60 = serviceRepository.saveAndFlush(
                definition(salonId, manicureType, "Класичне нарощення", "500.00", 60, true));

        // Act — item A (60, other name) and item B (90, same name): each differs from the held
        // copy in one key part, but three independent IN lists would match (60 from A, name from B).
        List<ActiveDuplicateProjection> crossOnly = serviceRepository.findActiveDuplicateCopies(
                OwnerType.SALON.name(), salonId,
                new UUID[] {manicureType.getId(), manicureType.getId()},
                new Integer[] {60, 90},
                new String[] {"Нарощення авторське", "Класичне нарощення"});
        List<ActiveDuplicateProjection> exact = serviceRepository.findActiveDuplicateCopies(
                OwnerType.SALON.name(), salonId,
                new UUID[] {manicureType.getId(), manicureType.getId()},
                new Integer[] {120, 60},
                new String[] {"Класичне нарощення", " класичне НАРОЩЕННЯ "});

        // Assert
        assertThat(crossOnly).isEmpty();
        assertThat(exact)
                .extracting(ActiveDuplicateProjection::serviceTypeId,
                        ActiveDuplicateProjection::serviceDefinitionId)
                .containsExactly(tuple(manicureType.getId(), held60.getId()));
    }
}
