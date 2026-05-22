package com.beautica.salon;

import com.beautica.AbstractDataJpaTest;
import com.beautica.TestConstants;
import com.beautica.auth.Role;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 12.1 — repository slice tests for the two new SalonRepository methods:
 *   - existsByOwnerId(UUID)
 *   - findByOwnerIdAndIsPrimaryTrue(UUID)
 *
 * Also verifies the partial unique index idx_salons_owner_primary enforces the
 * one-primary-per-owner invariant at the DB level (Q21 migration content assertion).
 */
@DisplayName("SalonRepository — isPrimary queries (Phase 12.1)")
class SalonRepositoryPrimaryTest extends AbstractDataJpaTest {

    @Autowired
    private SalonRepository salonRepository;

    @Autowired
    private TestEntityManager em;

    // ── existsByOwnerId ────────────────────────────────────────────────────────

    @Test
    @DisplayName("existsByOwnerId — returns true when owner has an active salon")
    void should_returnTrue_when_ownerHasActiveSalon() {
        User owner = persistOwner("exists-active-" + UUID.randomUUID());
        persistSalon(owner, "Active Salon", true, false);
        em.flush();
        em.clear();

        assertThat(salonRepository.existsByOwnerId(owner.getId()))
                .as("existsByOwnerId must return true for an owner with an active salon")
                .isTrue();
    }

    @Test
    @DisplayName("existsByOwnerId — returns true when owner has only an inactive salon")
    void should_returnTrue_when_ownerHasOnlyInactiveSalon() {
        User owner = persistOwner("exists-inactive-" + UUID.randomUUID());
        persistSalon(owner, "Inactive Salon", false, false);
        em.flush();
        em.clear();

        // existsByOwnerId must find ANY salon (active or inactive) per Phase 12.1 design:
        // a deactivated salon still counts as "owner has already created a salon" for
        // the isPrimary decision so a reactivation scenario does not create a second primary.
        assertThat(salonRepository.existsByOwnerId(owner.getId()))
                .as("existsByOwnerId must return true even when the only salon is inactive")
                .isTrue();
    }

    @Test
    @DisplayName("existsByOwnerId — returns false when owner has no salons at all")
    void should_returnFalse_when_ownerHasNoSalons() {
        User owner = persistOwner("no-salon-" + UUID.randomUUID());
        em.flush();
        em.clear();

        assertThat(salonRepository.existsByOwnerId(owner.getId()))
                .as("existsByOwnerId must return false when no salon exists for the owner")
                .isFalse();
    }

    @Test
    @DisplayName("existsByOwnerId — returns false for a completely unknown owner UUID")
    void should_returnFalse_when_ownerIdDoesNotExistAtAll() {
        UUID unknownOwnerId = UUID.randomUUID();

        assertThat(salonRepository.existsByOwnerId(unknownOwnerId))
                .as("existsByOwnerId must return false for a UUID that has never been an owner")
                .isFalse();
    }

    @Test
    @DisplayName("existsByOwnerId — isolates by owner — returns false for owner B when only owner A has a salon")
    void should_returnFalse_when_queriedOwnerBHasNoSalonsButOwnerADoes() {
        User ownerA = persistOwner("ownerA-" + UUID.randomUUID());
        User ownerB = persistOwner("ownerB-" + UUID.randomUUID());
        persistSalon(ownerA, "Owner A Salon", true, false);
        em.flush();
        em.clear();

        assertThat(salonRepository.existsByOwnerId(ownerB.getId()))
                .as("existsByOwnerId must not return true for owner B when only owner A has salons")
                .isFalse();
    }

    // ── findByOwnerIdAndIsPrimaryTrue ──────────────────────────────────────────

    @Test
    @DisplayName("findByOwnerIdAndIsPrimaryTrue — returns populated Optional when owner has a primary salon")
    void should_returnPrimarySalon_when_ownerHasOnePrimarySalon() {
        User owner = persistOwner("primary-exists-" + UUID.randomUUID());
        Salon primary = persistSalon(owner, "Primary Salon", true, true);
        em.flush();
        em.clear();

        Optional<Salon> result = salonRepository.findByOwnerIdAndIsPrimaryTrue(owner.getId());

        assertThat(result)
                .as("findByOwnerIdAndIsPrimaryTrue must return a populated Optional for a primary salon")
                .isPresent();
        assertThat(result.get().getId())
                .as("returned salon id must match the persisted primary salon")
                .isEqualTo(primary.getId());
        assertThat(result.get().isPrimary())
                .as("returned salon must have isPrimary=true")
                .isTrue();
    }

    @Test
    @DisplayName("findByOwnerIdAndIsPrimaryTrue — returns empty Optional when owner has salons but none is primary")
    void should_returnEmpty_when_ownerHasSalonsButNoneIsPrimary() {
        User owner = persistOwner("no-primary-" + UUID.randomUUID());
        persistSalon(owner, "Non-Primary Salon", true, false);
        em.flush();
        em.clear();

        Optional<Salon> result = salonRepository.findByOwnerIdAndIsPrimaryTrue(owner.getId());

        assertThat(result)
                .as("findByOwnerIdAndIsPrimaryTrue must return empty when no primary salon exists for owner")
                .isEmpty();
    }

    @Test
    @DisplayName("findByOwnerIdAndIsPrimaryTrue — returns empty Optional when owner has no salons at all")
    void should_returnEmpty_when_ownerHasNoSalons() {
        User owner = persistOwner("zero-salons-" + UUID.randomUUID());
        em.flush();
        em.clear();

        Optional<Salon> result = salonRepository.findByOwnerIdAndIsPrimaryTrue(owner.getId());

        assertThat(result)
                .as("findByOwnerIdAndIsPrimaryTrue must return empty for owner with no salons")
                .isEmpty();
    }

    @Test
    @DisplayName("findByOwnerIdAndIsPrimaryTrue — isolates by owner — does not return other owner's primary salon")
    void should_returnEmpty_when_ownerBQueriesAndOnlyOwnerAHasPrimarySalon() {
        User ownerA = persistOwner("isoA-" + UUID.randomUUID());
        User ownerB = persistOwner("isoB-" + UUID.randomUUID());
        persistSalon(ownerA, "Owner A Primary", true, true);
        em.flush();
        em.clear();

        Optional<Salon> result = salonRepository.findByOwnerIdAndIsPrimaryTrue(ownerB.getId());

        assertThat(result)
                .as("findByOwnerIdAndIsPrimaryTrue must not return owner A's primary salon when queried for owner B")
                .isEmpty();
    }

    @Test
    @DisplayName("findByOwnerIdAndIsPrimaryTrue — returns correct primary among mixed primary/non-primary salons")
    void should_returnOnlyPrimarySalon_when_ownerHasMixedPrimaryAndNonPrimarySalons() {
        User owner = persistOwner("mixed-" + UUID.randomUUID());
        Salon primary = persistSalon(owner, "Primary Salon", true, true);
        persistSalon(owner, "Second Salon", true, false);
        em.flush();
        em.clear();

        Optional<Salon> result = salonRepository.findByOwnerIdAndIsPrimaryTrue(owner.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getId())
                .as("only the primary salon should be returned even when multiple salons exist")
                .isEqualTo(primary.getId());
    }

    // ── V56 partial unique index: at most one primary per owner ───────────────

    @Test
    @DisplayName("V56 partial unique index — rejects a second isPrimary=true row for the same owner")
    void should_throwDataIntegrityViolation_when_ownerAlreadyHasPrimaryAndSecondPrimaryInserted() {
        User owner = persistOwner("unique-violation-" + UUID.randomUUID());
        persistSalon(owner, "Primary Salon", true, true);
        em.flush();

        // The partial unique index idx_salons_owner_primary is enforced by the DB
        // (WHERE is_primary = true), so a second insert with isPrimary=true for the
        // same owner must be rejected.
        // em.flush() bypasses Spring Data's PersistenceExceptionTranslationPostProcessor,
        // so the raw Hibernate ConstraintViolationException surfaces as a
        // jakarta.persistence.PersistenceException (its supertype) instead of
        // DataIntegrityViolationException. Accept both so the assertion does not depend
        // on which proxy path is active.
        assertThatThrownBy(() -> {
            persistSalon(owner, "Second Primary Salon", true, true);
            em.flush();
        })
                .isInstanceOfAny(
                        DataIntegrityViolationException.class,
                        jakarta.persistence.PersistenceException.class)
                .as("inserting a second isPrimary=true salon for the same owner must violate " +
                    "the idx_salons_owner_primary partial unique index (V56)");
    }

    @Test
    @DisplayName("V56 partial unique index — allows two isPrimary=false rows for the same owner")
    void should_permitMultipleNonPrimaryRowsForSameOwner() {
        User owner = persistOwner("two-non-primary-" + UUID.randomUUID());
        persistSalon(owner, "Non-Primary One", true, false);
        persistSalon(owner, "Non-Primary Two", true, false);
        em.flush();

        long count = salonRepository.findAllByOwnerIdAndIsActiveTrue(owner.getId()).size();

        assertThat(count)
                .as("two non-primary salons for the same owner must be permitted by the partial unique index")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("V56 partial unique index — allows isPrimary=true for two different owners")
    void should_permitPrimaryRowForEachOwnerSeparately() {
        User ownerA = persistOwner("sep-ownerA-" + UUID.randomUUID());
        User ownerB = persistOwner("sep-ownerB-" + UUID.randomUUID());
        persistSalon(ownerA, "Owner A Primary", true, true);
        persistSalon(ownerB, "Owner B Primary", true, true);
        em.flush();
        em.clear();

        // Two different owners each having a primary salon must NOT violate the constraint.
        Optional<Salon> aResult = salonRepository.findByOwnerIdAndIsPrimaryTrue(ownerA.getId());
        Optional<Salon> bResult = salonRepository.findByOwnerIdAndIsPrimaryTrue(ownerB.getId());

        assertThat(aResult).as("owner A's primary salon must be found").isPresent();
        assertThat(bResult).as("owner B's primary salon must be found").isPresent();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private User persistOwner(String emailPrefix) {
        User user = new User(
                emailPrefix + "@beautica.test",
                TestConstants.HASHED_TEST_PASSWORD,
                Role.SALON_OWNER,
                "Olena",
                "Kovalenko",
                "+380501234567"
        );
        return em.persist(user);
    }

    private Salon persistSalon(User owner, String name, boolean isActive, boolean isPrimary) {
        Salon salon = Salon.builder()
                .owner(owner)
                .name(name)
                .isActive(isActive)
                .isPrimary(isPrimary)
                .build();
        return em.persist(salon);
    }
}
