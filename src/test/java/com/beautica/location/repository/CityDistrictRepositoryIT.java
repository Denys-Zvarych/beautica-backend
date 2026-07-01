package com.beautica.location.repository;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration coverage for {@link CityDistrictRepository#findNameUkById} — the
 * single-id district-label lookup that backs the {@code districtName} field on
 * {@code GET /users/me}.
 *
 * <p>The seed assigns district ids via {@code gen_random_uuid()} (V53), so the
 * known-id test resolves a real seeded row from the DB rather than hardcoding a
 * UUID — the test stays correct if the KATOTTH snapshot changes.
 */
@DisplayName("CityDistrictRepository.findNameUkById — integration")
class CityDistrictRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private CityDistrictRepository cityDistrictRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("findNameUkById resolves a seeded district id to its name_uk")
    void should_resolveNameUk_when_districtIdExists() {
        Map<String, Object> seeded = jdbcTemplate.queryForMap(
                "SELECT id, name_uk FROM city_districts LIMIT 1");
        UUID seededId = (UUID) seeded.get("id");
        String expectedName = (String) seeded.get("name_uk");

        Optional<String> resolved = cityDistrictRepository.findNameUkById(seededId);

        assertThat(resolved)
                .as("a seeded district id must resolve to its denormalised name_uk")
                .contains(expectedName);
    }

    @Test
    @DisplayName("findNameUkById returns empty Optional for an unknown id")
    void should_returnEmpty_when_districtIdUnknown() {
        UUID unknownId = UUID.randomUUID();

        Optional<String> resolved = cityDistrictRepository.findNameUkById(unknownId);

        assertThat(resolved)
                .as("an id that matches no district row must yield an empty Optional, never null/throw")
                .isEmpty();
    }
}
