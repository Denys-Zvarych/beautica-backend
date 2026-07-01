package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.config.TestSecurityConfig;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.service.SalonService;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-persistence guard for the {@code SalonService} redundant-save removal.
 *
 * <p><b>Why this IT exists.</b> {@code deactivateSalon} and {@code updateSalon} dropped their
 * explicit {@code salonRepository.save(salon)} calls and now rely on Hibernate dirty-checking
 * to flush the mutation on transaction commit (the entity is managed because it was loaded by
 * {@code findById…} inside the same {@code @Transactional}). The existing unit tests for these
 * methods are Mockito-only — they verify {@code verify(repo, never()).save(...)} but use a mock
 * repository with NO real persistence context, so they CANNOT prove the dirty-checking flush
 * actually writes the row. A regression that breaks the flush (e.g. detaching the entity, a
 * read-only transaction, or a future refactor that returns a non-managed copy) would stay
 * mock-green while silently failing in production (cf. the profile-save base-URL incident:
 * mock-green is not acceptance for a persistence path).
 *
 * <p><b>What guarantees the proof.</b> Each test invokes the service method (which commits its
 * own {@code @Transactional}) and then re-reads the column with a raw {@link
 * org.springframework.jdbc.core.JdbcTemplate} SQL query. JdbcTemplate bypasses the Hibernate
 * persistence context / first-level cache entirely, so the asserted value is the value that was
 * actually flushed to the committed database row — not the in-memory entity state. The test
 * class is intentionally NOT {@code @Transactional}: if it were, the service's commit would be
 * subsumed by the outer test transaction and the JDBC read could observe unflushed/in-flight
 * state, defeating the proof.
 */
@Import(TestSecurityConfig.class)
@DisplayName("SalonService mutation persistence — dirty-checking flush guards the dropped save()")
class SalonMutationPersistenceIT extends AbstractIntegrationTest {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    @Autowired
    private SalonService salonService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ── deactivateSalon ────────────────────────────────────────────────────────

    @Test
    @DisplayName("deactivateSalon flushes is_active=false to the committed row WITHOUT explicit save()")
    void should_persistIsActiveFalse_when_deactivateSalonRunsWithoutExplicitSave() {
        // Arrange — owner + active salon created through the real service path
        UUID ownerId = persistOwner("deactivate-owner@beautica.test");
        UUID salonId = salonService.createSalon(ownerId, createRequest("Velvet Studio")).id();

        Boolean activeBefore = readIsActive(salonId);
        assertThat(activeBefore)
                .as("precondition: freshly created salon must be active in the committed row")
                .isTrue();

        // Act — deactivate; the dropped save() means only the @Transactional commit can persist this
        salonService.deactivateSalon(ownerId, salonId);

        // Assert — re-read the raw column (bypasses Hibernate context): dirty-check flush persisted
        Boolean activeAfter = readIsActive(salonId);
        assertThat(activeAfter)
                .as("is_active must be false in the committed DB row after deactivate, "
                        + "actual=%s — proves Hibernate dirty-checking flushed the mutation "
                        + "without the dropped salonRepository.save()", activeAfter)
                .isFalse();
    }

    // ── updateSalon ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateSalon flushes the renamed name to the committed row WITHOUT explicit save()")
    void should_persistUpdatedName_when_updateSalonRunsWithoutExplicitSave() {
        // Arrange — owner + salon with a known name
        UUID ownerId = persistOwner("update-owner@beautica.test");
        UUID salonId = salonService.createSalon(ownerId, createRequest("Old Name")).id();

        assertThat(readName(salonId))
                .as("precondition: committed row must hold the original name")
                .isEqualTo("Old Name");

        // Act — rename via the service; updateSalon also dropped its explicit save()
        salonService.updateSalon(ownerId, salonId, updateRequest("New Name"));

        // Assert — raw-column re-read confirms the mutation reached the committed row
        String persistedName = readName(salonId);
        assertThat(persistedName)
                .as("salon name must be 'New Name' in the committed DB row after update, "
                        + "actual=%s — proves dirty-checking flushed the setter mutation "
                        + "without the dropped salonRepository.save()", persistedName)
                .isEqualTo("New Name");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID persistOwner(String email) {
        var user = new User(email, passwordEncoder.encode(TEST_PASSWORD), Role.SALON_OWNER,
                null, null, null);
        return userRepository.save(user).getId();
    }

    private CreateSalonRequest createRequest(String name) {
        UUID cityId = jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE name_uk = 'Вінниця' LIMIT 1", UUID.class);
        // CreateSalonRequest field order: name, description, city, region, address, phone,
        // instagramUrl, cityId, districtId, street, buildingNo, locationNote — mirrors the
        // proven shape used in SalonRegistrationIntegrationTest.
        return new CreateSalonRequest(
                name, null, "Kyiv", null, null, null, null, cityId, null, null, null, null);
    }

    private UpdateSalonRequest updateRequest(String name) {
        UUID cityId = jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE name_uk = 'Вінниця' LIMIT 1", UUID.class);
        // UpdateSalonRequest field order: name, description, city, region, address,
        // cityId, districtId, street, buildingNo, locationNote, phone, instagramUrl.
        return new UpdateSalonRequest(
                name, null, null, null, null, cityId, null, null, null, null, null, null);
    }

    /** Raw-SQL read that bypasses the Hibernate persistence context — reads the committed row. */
    private Boolean readIsActive(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT is_active FROM salons WHERE id = ?", Boolean.class, salonId);
    }

    private String readName(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM salons WHERE id = ?", String.class, salonId);
    }
}
