package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.config.TestSecurityConfig;
import com.beautica.master.entity.MasterType;
import com.beautica.master.repository.MasterRepository;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 12.2 — service-layer integration tests for the owner-as-master auto-create flow.
 *
 * <p>Extends {@link AbstractIntegrationTest} to reuse the singleton Testcontainers PostgreSQL
 * container and the shared {@code cleanDb()} lifecycle. Tests inject {@link SalonService}
 * directly — no HTTP layer required for this contract.
 */
@Import(TestSecurityConfig.class)
@DisplayName("SalonRegistration — owner-as-master auto-create (Phase 12.2)")
class SalonRegistrationIntegrationTest extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private SalonService salonService;

    @Autowired
    private SalonRepository salonRepository;

    @Autowired
    private MasterRepository masterRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @AfterEach
    void cleanUp() {
        // FK order: masters → salons → users (parent rows)
        // AbstractIntegrationTest.cleanDb() already handles this order but we register
        // an explicit local cleanup to ensure local inserts are isolated per test.
        jdbcTemplate.execute("DELETE FROM masters");
        jdbcTemplate.execute("DELETE FROM salons");
        jdbcTemplate.execute("DELETE FROM users");
    }

    // ── first-salon path ──────────────────────────────────────────────────────

    @Test
    @DisplayName("should_autoCreateOwnerMaster_on_firstSalonCreation")
    void should_autoCreateOwnerMaster_on_firstSalonCreation() {
        // Arrange
        UUID ownerId = persistOwner("owner-first@beautica.test");
        var request = new CreateSalonRequest(
                "Velvet Studio", null, "Kyiv", null, null, null, null, null, null, null, null, null);

        // Act
        var salonResponse = salonService.createSalon(ownerId, request);

        // Assert — salon is primary
        assertThat(salonResponse.isPrimary())
                .as("first salon must be marked primary")
                .isTrue();

        // Assert — one SALON_OWNER master row exists for this user
        var masters = masterRepository.findAll().stream()
                .filter(m -> m.getUser().getId().equals(ownerId))
                .toList();

        assertThat(masters).hasSize(1);

        var ownerMaster = masters.get(0);
        assertThat(ownerMaster.getMasterType())
                .as("auto-created master must have type SALON_OWNER")
                .isEqualTo(MasterType.SALON_OWNER);
        assertThat(ownerMaster.getSalon().getId())
                .as("auto-created master must belong to the new salon")
                .isEqualTo(salonResponse.id());
        assertThat(ownerMaster.isActive())
                .as("auto-created master must be active")
                .isTrue();
        assertThat(ownerMaster.getAvgRating())
                .as("auto-created master must start with zero avg rating")
                .isEqualByComparingTo("0");
    }

    // ── second-salon path ─────────────────────────────────────────────────────

    @Test
    @DisplayName("should_notAutoCreateMaster_on_secondSalonCreation")
    void should_notAutoCreateMaster_on_secondSalonCreation() {
        // Arrange
        UUID ownerId = persistOwner("owner-second@beautica.test");
        var firstRequest = new CreateSalonRequest(
                "First Studio", null, "Kyiv", null, null, null, null, null, null, null, null, null);
        var secondRequest = new CreateSalonRequest(
                "Second Studio", null, "Lviv", null, null, null, null, null, null, null, null, null);

        // Act — create first salon (triggers auto-master)
        salonService.createSalon(ownerId, firstRequest);

        // Act — create second salon
        var secondResponse = salonService.createSalon(ownerId, secondRequest);

        // Assert — second salon is NOT primary
        assertThat(secondResponse.isPrimary())
                .as("second salon must not be marked primary")
                .isFalse();

        // Assert — still exactly one master row for this owner (no second master created)
        long masterCount = masterRepository.findAll().stream()
                .filter(m -> m.getUser().getId().equals(ownerId))
                .count();

        assertThat(masterCount)
                .as("owner must still have exactly one master row after creating a second salon")
                .isEqualTo(1);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID persistOwner(String email) {
        var user = new User(email, passwordEncoder.encode(TEST_PASSWORD), Role.SALON_OWNER,
                null, null, null);
        return userRepository.save(user).getId();
    }
}
