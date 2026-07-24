package com.beautica.salon.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.TestConstants;
import com.beautica.auth.Role;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Repository-layer slice tests for the Phase 10.5 FK-based, district-primary
 * salon location filter — Phase 10.8 (MEDIUM-1) refactored the former single
 * non-SARGable {@code findByLocation(cityId, districtId, …)} into three
 * single-equality, SARGable methods:
 * {@link SalonRepository#findActiveByDistrictId},
 * {@link SalonRepository#findActiveByCityId} and
 * {@link SalonRepository#findByIsActiveTrue}. These slice tests pin that the
 * behaviour (result set, paging, ordering, active-flag filter) is identical
 * across the refactor.
 *
 * <p>Uses {@link AbstractDataJpaTest} for the JVM-singleton PostgreSQL
 * Testcontainers instance. Flyway runs in the slice context, so the V53
 * KATOTTH taxonomy is present — real {@code cities} ids are resolved by
 * Ukrainian name and stamped onto {@code salons.city_id} (the legacy
 * free-text {@code city}/{@code region} columns and the old
 * {@code findByFilter} string-equality query are gone in Phase 10.5).
 *
 * <p>Tests are transactional via {@code @DataJpaTest}'s default rollback.
 */
@DisplayName("SalonRepository — FK location search slice (SARGable dispatch)")
class SalonRepositorySearchTest extends AbstractDataJpaTest {

    @Autowired
    private SalonRepository salonRepository;

    @Autowired
    private TestEntityManager em;

    private User owner;
    private UUID kyivCityId;
    private UUID lvivCityId;

    @BeforeEach
    void seedOwner() {
        owner = new User(
                "search-owner-" + UUID.randomUUID() + "@beautica.test",
                TestConstants.HASHED_TEST_PASSWORD,
                Role.SALON_OWNER,
                "Olena",
                "Kovalenko",
                "+380501112233"
        );
        em.persist(owner);

        kyivCityId = cityIdByName("Київ");
        lvivCityId = cityIdByName("Львів");
    }

    /**
     * Resolves a Flyway-seeded {@code cities.id} by canonical Ukrainian name
     * (V53). Reference data — present in the slice context, never rolled back.
     */
    private UUID cityIdByName(String nameUk) {
        return (UUID) em.getEntityManager()
                .createNativeQuery(
                        "SELECT id FROM cities WHERE name_uk = :n ORDER BY katotth_code LIMIT 1")
                .setParameter("n", nameUk)
                .getSingleResult();
    }

    private Salon salonIn(UUID cityId, String name) {
        return salonIn(cityId, name, true);
    }

    private Salon salonIn(UUID cityId, String name, boolean isActive) {
        return Salon.builder()
                .owner(owner)
                .name(name)
                .cityId(cityId)
                .isActive(isActive)
                .build();
    }

    private Salon salonInDistrict(UUID cityId, UUID districtId, String name) {
        return Salon.builder()
                .owner(owner)
                .name(name)
                .cityId(cityId)
                .districtId(districtId)
                .isActive(true)
                .build();
    }

    private UUID firstDistrictIdInCity(UUID cityId) {
        return (UUID) em.getEntityManager()
                .createNativeQuery(
                        "SELECT id FROM city_districts WHERE city_id = :c "
                                + "ORDER BY katotth_code LIMIT 1")
                .setParameter("c", cityId)
                .getSingleResult();
    }

    @Test
    @DisplayName("findActiveByCityId returns only salons in the target city")
    void should_returnOnlySalonsInCity_when_cityFilterApplied() {
        em.persist(salonIn(kyivCityId, "Kyiv Salon One"));
        em.persist(salonIn(kyivCityId, "Kyiv Salon Two"));
        em.persist(salonIn(lvivCityId, "Lviv Salon"));
        em.flush();
        em.clear();

        Page<Salon> page = salonRepository.findActiveByCityId(
                kyivCityId,
                PageRequest.of(0, 20, Sort.by("name"))
        );

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent())
                .extracting(Salon::getCityId)
                .containsOnly(kyivCityId);
        assertThat(page.getContent())
                .extracting(Salon::getName)
                .containsExactlyInAnyOrder("Kyiv Salon One", "Kyiv Salon Two");
    }

    @Test
    @DisplayName("findActiveByDistrictId returns only salons in the target district (district-primary)")
    void should_returnOnlySalonsInDistrict_when_districtFilterApplied() {
        UUID districtId = firstDistrictIdInCity(kyivCityId);
        em.persist(salonInDistrict(kyivCityId, districtId, "District Salon"));
        em.persist(salonIn(kyivCityId, "City-only Kyiv Salon"));
        em.persist(salonIn(lvivCityId, "Lviv Salon"));
        em.flush();
        em.clear();

        Page<Salon> page = salonRepository.findActiveByDistrictId(
                districtId,
                PageRequest.of(0, 20, Sort.by("name"))
        );

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent())
                .extracting(Salon::getName)
                .containsExactly("District Salon");
    }

    @Test
    @DisplayName("findByIsActiveTrue returns every active salon when no location filter is supplied")
    void should_returnAllSalons_when_noLocationFilter() {
        em.persist(salonIn(kyivCityId, "Kyiv Salon One"));
        em.persist(salonIn(kyivCityId, "Kyiv Salon Two"));
        em.persist(salonIn(lvivCityId, "Lviv Salon"));
        em.flush();
        em.clear();

        Page<Salon> page = salonRepository.findByIsActiveTrue(
                PageRequest.of(0, 20, Sort.by("name"))
        );

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(3);
    }

    @Test
    @DisplayName("paginates correctly: pageSize 1 over 2 city matches gives totalElements 2 and content size 1")
    void should_returnCorrectPage_when_pageSizeIsOne() {
        em.persist(salonIn(kyivCityId, "Kyiv Salon One"));
        em.persist(salonIn(kyivCityId, "Kyiv Salon Two"));
        em.persist(salonIn(lvivCityId, "Lviv Salon"));
        em.flush();
        em.clear();

        Pageable firstPageOfOne = PageRequest.of(0, 1, Sort.by("name"));

        Page<Salon> page = salonRepository.findActiveByCityId(kyivCityId, firstPageOfOne);

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    /**
     * Public-facing salon discovery must not leak deactivated salons. Seeds one
     * active plus one inactive salon (same city) and asserts the inactive row
     * is filtered out by the {@code s.isActive = true} predicate that every
     * branch of the SARGable dispatch carries
     * ({@link SalonRepository#findByIsActiveTrue} here).
     */
    @Test
    @DisplayName("excludes deactivated salons from search results")
    void should_excludeSalon_when_isActiveFalse() {
        Salon active = salonIn(kyivCityId, "Active Kyiv Salon", true);
        Salon inactive = salonIn(kyivCityId, "Hidden Kyiv Salon", false);
        em.persist(active);
        em.persist(inactive);
        em.flush();
        em.clear();

        Page<Salon> page = salonRepository.findByIsActiveTrue(
                PageRequest.of(0, 20, Sort.by("name"))
        );

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent())
                .extracting(Salon::getName)
                .containsExactly("Active Kyiv Salon")
                .doesNotContain("Hidden Kyiv Salon");
    }
}
