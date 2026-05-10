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
 * Repository-layer slice tests for {@link SalonRepository#findByFilter}.
 *
 * <p>Uses {@link AbstractDataJpaTest} for the JVM-singleton PostgreSQL
 * Testcontainers instance (no per-class container spin-up). Persists three
 * salons across two cities and verifies the JPQL query honours optional
 * city/region filters and pagination metadata.
 *
 * <p>Tests are transactional via {@code @DataJpaTest}'s default rollback —
 * no manual cleanup required.
 */
@DisplayName("SalonRepository.findByFilter — search slice")
class SalonRepositorySearchTest extends AbstractDataJpaTest {

    @Autowired
    private SalonRepository salonRepository;

    @Autowired
    private TestEntityManager em;

    private User owner;

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
    }

    private Salon salonIn(String city, String region, String name) {
        return salonIn(city, region, name, true);
    }

    private Salon salonIn(String city, String region, String name, boolean isActive) {
        return Salon.builder()
                .owner(owner)
                .name(name)
                .city(city)
                .region(region)
                .isActive(isActive)
                .build();
    }

    @Test
    @DisplayName("returns only salons in the target city when a city filter is applied")
    void should_returnOnlySalonsInCity_when_cityFilterApplied() {
        em.persist(salonIn("Київ", "Kyivska", "Kyiv Salon One"));
        em.persist(salonIn("Київ", "Kyivska", "Kyiv Salon Two"));
        em.persist(salonIn("Львів", "Lvivska", "Lviv Salon"));
        em.flush();
        em.clear();

        Page<Salon> page = salonRepository.findByFilter(
                "Київ",
                null,
                PageRequest.of(0, 20, Sort.by("name"))
        );

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent())
                .extracting(Salon::getCity)
                .containsOnly("Київ");
        assertThat(page.getContent())
                .extracting(Salon::getName)
                .containsExactlyInAnyOrder("Kyiv Salon One", "Kyiv Salon Two");
    }

    @Test
    @DisplayName("returns every salon when neither city nor region is supplied")
    void should_returnAllSalons_when_noCityFilter() {
        em.persist(salonIn("Київ", "Kyivska", "Kyiv Salon One"));
        em.persist(salonIn("Київ", "Kyivska", "Kyiv Salon Two"));
        em.persist(salonIn("Львів", "Lvivska", "Lviv Salon"));
        em.flush();
        em.clear();

        Page<Salon> page = salonRepository.findByFilter(
                null,
                null,
                PageRequest.of(0, 20, Sort.by("name"))
        );

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(3);
    }

    @Test
    @DisplayName("paginates correctly: pageSize 1 over 2 matches gives totalElements 2 and content size 1")
    void should_returnCorrectPage_when_pageSizeIsOne() {
        em.persist(salonIn("Київ", "Kyivska", "Kyiv Salon One"));
        em.persist(salonIn("Київ", "Kyivska", "Kyiv Salon Two"));
        em.persist(salonIn("Львів", "Lvivska", "Lviv Salon"));
        em.flush();
        em.clear();

        Pageable firstPageOfOne = PageRequest.of(0, 1, Sort.by("name"));

        Page<Salon> page = salonRepository.findByFilter("Київ", null, firstPageOfOne);

        assertThat(page.getTotalElements()).isEqualTo(2);
        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getTotalPages()).isEqualTo(2);
    }

    /**
     * Public-facing salon discovery must not leak deactivated salons. Seeds one
     * active salon plus one inactive salon (same city/region) and asserts the
     * inactive row is filtered out by the {@code s.isActive = true} predicate
     * added to {@link SalonRepository#findByFilter}.
     */
    @Test
    @DisplayName("excludes deactivated salons from search results")
    void should_excludeSalon_when_isActiveFalse() {
        Salon active = salonIn("Київ", "Kyivska", "Active Kyiv Salon", true);
        Salon inactive = salonIn("Київ", "Kyivska", "Hidden Kyiv Salon", false);
        em.persist(active);
        em.persist(inactive);
        em.flush();
        em.clear();

        Page<Salon> page = salonRepository.findByFilter(
                null,
                null,
                PageRequest.of(0, 20, Sort.by("name"))
        );

        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent())
                .extracting(Salon::getName)
                .containsExactly("Active Kyiv Salon")
                .doesNotContain("Hidden Kyiv Salon");
    }
}
