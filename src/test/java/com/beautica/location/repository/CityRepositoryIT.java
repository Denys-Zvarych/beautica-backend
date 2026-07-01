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
 * Integration coverage for {@link CityRepository#findOblastIdById} — the
 * single-id city→oblast FK lookup that backs the {@code oblastId} field on
 * {@code GET /users/me} (so the mobile Location-edit cascade can pre-select the
 * oblast tier without scanning every oblast's city list).
 *
 * <p>The seed assigns city/oblast ids via {@code gen_random_uuid()}, so the
 * known-id test resolves a real seeded {@code (city, oblast)} pair from the DB
 * rather than hardcoding a UUID — the test stays correct if the KATOTTH snapshot
 * changes.
 */
@DisplayName("CityRepository.findOblastIdById — integration")
class CityRepositoryIT extends AbstractIntegrationTest {

    @Autowired
    private CityRepository cityRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("findOblastIdById resolves a seeded city id to its parent oblast id")
    void should_resolveOblastId_when_cityIdExists() {
        Map<String, Object> seeded = jdbcTemplate.queryForMap(
                "SELECT id, oblast_id FROM cities LIMIT 1");
        UUID seededCityId = (UUID) seeded.get("id");
        UUID expectedOblastId = (UUID) seeded.get("oblast_id");

        Optional<UUID> resolved = cityRepository.findOblastIdById(seededCityId);

        assertThat(resolved)
                .as("a seeded city id must resolve to its parent oblast_id FK")
                .contains(expectedOblastId);
    }

    @Test
    @DisplayName("findOblastIdById returns empty Optional for an unknown id")
    void should_returnEmpty_when_cityIdUnknown() {
        UUID unknownId = UUID.randomUUID();

        Optional<UUID> resolved = cityRepository.findOblastIdById(unknownId);

        assertThat(resolved)
                .as("an id that matches no city row must yield an empty Optional, never null/throw")
                .isEmpty();
    }
}
