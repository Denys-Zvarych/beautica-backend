package com.beautica.salon;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.TestConstants;
import com.beautica.salon.entity.Salon;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SalonRepository — data access layer")
class SalonRepositoryTest extends AbstractDataJpaTest {

    @Autowired
    private SalonRepository salonRepository;

    @Autowired
    private TestEntityManager em;

    @Test
    @DisplayName("findAllByOwnerIdAndIsActiveTrue — returns only active salons for the target owner")
    void should_returnOnlyActiveSalons_when_ownerHasActiveAndInactiveSalons() {
        User owner = new User(
                "owner-active-" + UUID.randomUUID() + "@beautica.test",
                TestConstants.HASHED_TEST_PASSWORD,
                Role.SALON_OWNER,
                "Olena",
                "Kovalenko",
                "+380501234567"
        );
        em.persist(owner);

        User otherOwner = new User(
                "other-" + UUID.randomUUID() + "@beautica.test",
                TestConstants.HASHED_TEST_PASSWORD,
                Role.SALON_OWNER,
                "Ivan",
                "Petrenko",
                "+380509876543"
        );
        em.persist(otherOwner);

        Salon activeSalon = Salon.builder()
                .cityId(testCityId())
                .owner(owner)
                .name("Active Salon")
                .isActive(true)
                .build();
        Salon inactiveSalon = Salon.builder()
                .cityId(testCityId())
                .owner(owner)
                .name("Inactive Salon")
                .isActive(false)
                .build();
        Salon otherSalon = Salon.builder()
                .cityId(testCityId())
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
                TestConstants.HASHED_TEST_PASSWORD,
                Role.SALON_OWNER,
                "Olena",
                "Kovalenko",
                "+380501234567"
        );
        em.persist(owner);

        Salon salon = Salon.builder()
                .cityId(testCityId())
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
                TestConstants.HASHED_TEST_PASSWORD,
                Role.SALON_OWNER,
                "Olena",
                "Kovalenko",
                "+380501111111"
        );
        em.persist(owner);

        Salon salon = Salon.builder()
                .cityId(testCityId())
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
    @DisplayName("findByIdAndOwnerId — returns populated Optional when correct owner fetches their salon")
    void should_returnSalon_when_correctOwnerFetchesSalon() {
        User owner = new User(
                "owner-" + UUID.randomUUID() + "@beautica.test",
                TestConstants.HASHED_TEST_PASSWORD,
                Role.SALON_OWNER,
                "Olena",
                "Marchenko",
                "+380501111111"
        );
        em.persist(owner);

        Salon salon = Salon.builder()
                .cityId(testCityId())
                .owner(owner)
                .name("Olena Beauty")
                .isActive(true)
                .build();
        em.persist(salon);
        em.flush();
        em.clear();

        var result = salonRepository.findByIdAndOwnerId(salon.getId(), owner.getId());

        assertThat(result)
                .as("findByIdAndOwnerId must return a populated Optional when the correct owner fetches their salon")
                .isPresent();
        assertThat(result.get().getName())
                .as("salon name must match")
                .isEqualTo("Olena Beauty");
        assertThat(result.get().getOwner().getId())
                .as("owner ID must match")
                .isEqualTo(owner.getId());
    }

    @Test
    @DisplayName("findByIdAndOwnerId — returns empty when owner B tries to fetch owner A's salon")
    void should_returnOptionalEmpty_when_findByIdAndOwnerIdWithWrongOwner() {
        User ownerA = new User(
                "owner-a-" + UUID.randomUUID() + "@beautica.test",
                TestConstants.HASHED_TEST_PASSWORD,
                Role.SALON_OWNER,
                "Anna",
                "Shevchenko",
                "+380502222222"
        );
        User ownerB = new User(
                "owner-b-" + UUID.randomUUID() + "@beautica.test",
                TestConstants.HASHED_TEST_PASSWORD,
                Role.SALON_OWNER,
                "Bohdan",
                "Kravchenko",
                "+380503333333"
        );
        em.persist(ownerA);
        em.persist(ownerB);

        Salon salonA = Salon.builder()
                .cityId(testCityId())
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

    // ── findByIdAndIsActiveTrueWithOwner ───────────────────────────────────────

    @Test
    @DisplayName("findByIdAndIsActiveTrueWithOwner — returns entity with owner graph populated for an active salon")
    void should_returnSalonWithOwner_when_salonIsActive() {
        User owner = new User(
                "owner-active-detail-" + UUID.randomUUID() + "@beautica.test",
                TestConstants.HASHED_TEST_PASSWORD,
                Role.SALON_OWNER,
                "Olena",
                "Bondar",
                "+380501111122"
        );
        em.persist(owner);

        Salon salon = Salon.builder()
                .cityId(testCityId())
                .owner(owner)
                .name("Active Detail Salon")
                .isActive(true)
                .build();
        em.persist(salon);
        em.flush();
        em.clear();

        var result = salonRepository.findByIdAndIsActiveTrueWithOwner(salon.getId());

        assertThat(result)
                .as("findByIdAndIsActiveTrueWithOwner must return a populated Optional for an active salon")
                .isPresent();
        assertThat(result.get().getName())
                .as("salon name must match the persisted value")
                .isEqualTo("Active Detail Salon");
        assertThat(result.get().getOwner())
                .as("owner graph must be populated (JOIN FETCH)")
                .isNotNull();
        assertThat(result.get().getOwner().getId())
                .as("owner ID must match the persisted owner")
                .isEqualTo(owner.getId());
    }

    @Test
    @DisplayName("findByIdAndIsActiveTrueWithOwner — returns Optional.empty() for an inactive salon")
    void should_returnEmpty_when_salonIsInactive() {
        User owner = new User(
                "owner-inactive-detail-" + UUID.randomUUID() + "@beautica.test",
                TestConstants.HASHED_TEST_PASSWORD,
                Role.SALON_OWNER,
                "Ivan",
                "Koval",
                "+380502222233"
        );
        em.persist(owner);

        Salon salon = Salon.builder()
                .cityId(testCityId())
                .owner(owner)
                .name("Inactive Detail Salon")
                .isActive(false)
                .build();
        em.persist(salon);
        em.flush();
        em.clear();

        var result = salonRepository.findByIdAndIsActiveTrueWithOwner(salon.getId());

        assertThat(result)
                .as("findByIdAndIsActiveTrueWithOwner must return empty for an inactive salon")
                .isEmpty();
    }

    @Test
    @DisplayName("findByIdAndIsActiveTrueWithOwner — returns Optional.empty() for a non-existent UUID")
    void should_returnEmpty_when_salonIdDoesNotExist() {
        UUID nonExistentId = UUID.randomUUID();

        var result = salonRepository.findByIdAndIsActiveTrueWithOwner(nonExistentId);

        assertThat(result)
                .as("findByIdAndIsActiveTrueWithOwner must return empty when no salon matches the given UUID")
                .isEmpty();
    }

    // ── V150/V151 "a salon must always have a city" — behavioural enforcement ──────

    @Test
    @DisplayName("persisting a Salon with a null cityId is rejected — proves the V150/V151 DB "
            + "constraint (and Salon.cityId nullable=false) actually block the write, not just "
            + "information_schema metadata")
    void should_rejectPersist_when_salonCityIdIsNull() {
        // Arrange — a real, otherwise-valid owner + salon, but with cityId deliberately null.
        // Bypasses LocalityWriteValidator entirely (that guard lives in SalonService, one layer
        // above the repository) so this test isolates the DB-level invariant on its own: even if
        // every application-layer guard were removed or buggy, the column itself must refuse
        // the write.
        User owner = new User(
                "owner-null-city-" + UUID.randomUUID() + "@beautica.test",
                TestConstants.HASHED_TEST_PASSWORD,
                Role.SALON_OWNER,
                "Nadiya",
                "Bondar",
                "+380503334455"
        );
        em.persist(owner);
        em.flush();

        Salon cityless = Salon.builder()
                .owner(owner)
                .name("Cityless Salon")
                .isActive(true)
                .build(); // cityId deliberately left null

        // Act + Assert — saveAndFlush forces the INSERT to execute now rather than deferring to
        // end-of-test rollback, so a thrown exception here proves the write itself is rejected —
        // confirmed (see this test's real run) to be a genuine DB-level rejection: Postgres
        // returns SQLState 23502 ("null value in column city_id ... violates not-null
        // constraint"), which Spring Data's repository exception translation surfaces as
        // DataIntegrityViolationException. isInstanceOfAny also tolerates Hibernate's own
        // not-null property check short-circuiting locally (PropertyValueException) or a bare
        // PersistenceException wrapper, so this test stays green regardless of which layer
        // happens to intercept the write, as long as one of them does.
        //
        // No follow-up SELECT after this: a failed INSERT leaves the DataJpaTest transaction
        // aborted (Postgres refuses further statements on that connection until rollback,
        // SQLState 25P02), and @DataJpaTest rolls the whole transaction back at test end anyway
        // — the row can never be committed regardless, so re-querying it here would only trip
        // the aborted-transaction state, not add proof.
        assertThatThrownBy(() -> salonRepository.saveAndFlush(cityless))
                .as("a Salon with cityId=null must never be persisted — V150/V151 must reject it "
                        + "at the DB, and/or Hibernate must refuse the write locally for the "
                        + "nullable=false mapping")
                .isInstanceOfAny(
                        jakarta.persistence.PersistenceException.class,
                        org.springframework.dao.DataIntegrityViolationException.class,
                        org.hibernate.PropertyValueException.class);
    }
}
