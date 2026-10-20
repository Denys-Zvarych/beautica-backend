package com.beautica.salon;

import com.beautica.AbstractIntegrationTest;
import com.beautica.auth.Role;
import com.beautica.booking.service.BookingSlugService;
import com.beautica.config.TestSecurityConfig;
import com.beautica.salon.dto.CreateSalonRequest;
import com.beautica.salon.dto.UpdateSalonRequest;
import com.beautica.salon.repository.SalonRepository;
import com.beautica.salon.service.SalonService;
import com.beautica.user.User;
import com.beautica.user.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    // Phase 10.6 reversal: street + buildingNo are now @NotBlank on both salon DTOs.
    // These service-layer fixtures carry a valid structured address so they mirror what
    // the @Valid HTTP boundary now guarantees.
    private static final String VALID_STREET = "вул. Хрещатик";
    private static final String VALID_BUILDING_NO = "1";

    @Autowired
    private SalonService salonService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SalonRepository salonRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private BookingSlugService bookingSlugService;

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

    // ── phase 376 — booking slug issued at creation, permanent ─────────────────

    @Test
    @DisplayName("createSalon writes a booking slug from the salon name in the committed row (376 D4)")
    void should_persistNameBasedBookingSlug_when_salonCreated() {
        UUID ownerId = persistOwner("slug-create-owner@beautica.test");

        UUID salonId = salonService.createSalon(ownerId, createRequest("Beauty Lab Київ")).id();

        assertThat(readSlug(salonId)).matches("^beauty-lab-kyiv-[a-z0-9]{4}$");
    }

    @Test
    @DisplayName("updateSalon rename never changes the booking slug (376 D5)")
    void should_keepBookingSlug_when_salonRenamed() {
        UUID ownerId = persistOwner("slug-rename-owner@beautica.test");
        UUID salonId = salonService.createSalon(ownerId, createRequest("Persha Nazva")).id();
        String issued = readSlug(salonId);

        salonService.updateSalon(ownerId, salonId, updateRequest("Druha Nazva"));

        assertThat(readName(salonId)).isEqualTo("Druha Nazva");
        assertThat(readSlug(salonId)).isEqualTo(issued).startsWith("persha-nazva-");
    }

    @Test
    @DisplayName("an entity loaded BEFORE a lazy slug issue flushes without writing NULL back over the slug (updatable=false)")
    void should_keepConcurrentlyIssuedSlug_when_staleSalonSnapshotFlushes() {
        UUID ownerId = persistOwner("slug-stale-owner@beautica.test");
        UUID salonId = salonService.createSalon(ownerId, createRequest("Stale Snapshot")).id();
        jdbcTemplate.update("UPDATE salons SET booking_slug = NULL WHERE id = ?", salonId);

        transactionTemplate.executeWithoutResult(status -> {
            var stale = salonRepository.findById(salonId).orElseThrow();
            // The race-safe issue commits on another connection while this snapshot still holds NULL.
            jdbcTemplate.update("UPDATE salons SET booking_slug = 'issued-meanwhile-ab12' WHERE id = ?", salonId);
            stale.setName("Renamed While Stale");
        });

        assertThat(readName(salonId)).isEqualTo("Renamed While Stale");
        assertThat(readSlug(salonId)).isEqualTo("issued-meanwhile-ab12");
    }

    @Test
    @DisplayName("376 audit: createSalon holds its slug's advisory lock until commit, so no other salon-slug writer can pick it meanwhile")
    void should_holdSlugLockUntilCommit_when_salonCreated() throws Exception {
        UUID ownerId = persistOwner("slug-lock-owner@beautica.test");
        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            String slug = transactionTemplate.execute(status -> {
                UUID salonId = salonService.createSalon(ownerId, createRequest("Zamok Salon")).id();
                String issued = salonRepository.findById(salonId).orElseThrow().getBookingSlug();
                assertThat(tryLockOnOtherConnection(other, issued))
                        .as("a concurrent writer must wait for %s until createSalon commits", issued)
                        .isFalse();
                return issued;
            });

            assertThat(slug).startsWith("zamok-salon-");
            assertThat(tryLockOnOtherConnection(other, slug)).as("released at commit").isTrue();
        } finally {
            other.shutdownNow();
        }
    }

    @Test
    @DisplayName("376 audit: generateSalonSlug outside a transaction throws — its advisory lock would be released at once")
    void should_throw_when_generateSalonSlugCalledOutsideTransaction() {
        assertThatThrownBy(() -> bookingSlugService.generateSalonSlug("Bez Tranzaktsii"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("376 audit: the salon-slug candidate and row locks outside a transaction throw (MANDATORY)")
    void should_throw_when_slugLocksTakenOutsideTransaction() {
        assertThatThrownBy(() -> salonRepository.lockBookingSlugCandidates(new String[] {"bez-tranzaktsii-ab12"}))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> salonRepository.lockSalonIdsWithoutBookingSlug(List.of(UUID.randomUUID())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private Boolean tryLockOnOtherConnection(ExecutorService other, String slug) {
        try {
            return other.submit(() -> new TransactionTemplate(transactionTemplate.getTransactionManager())
                    .execute(status -> jdbcTemplate.queryForObject(
                            "SELECT pg_try_advisory_xact_lock(hashtextextended(?, ?))",
                            Boolean.class, slug, SalonRepository.BOOKING_SLUG_LOCK_SEED)))
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private UUID persistOwner(String email) {
        var user = new User(email, passwordEncoder.encode(TEST_PASSWORD), Role.SALON_OWNER,
                null, null, null);
        return userRepository.save(user).getId();
    }

    private CreateSalonRequest createRequest(String name) {
        UUID cityId = majorCityIdByName("Вінниця");
        // CreateSalonRequest field order: name, description, city, region, address, phone,
        // instagramUrl, cityId, districtId, street, buildingNo, locationNote — mirrors the
        // proven shape used in SalonRegistrationIntegrationTest.
        return new CreateSalonRequest(
                name, null, "Kyiv", null, null, null, null, cityId, null,
                VALID_STREET, VALID_BUILDING_NO, null);
    }

    private UpdateSalonRequest updateRequest(String name) {
        UUID cityId = majorCityIdByName("Вінниця");
        // UpdateSalonRequest field order: name, description, city, region, address,
        // cityId, districtId, street, buildingNo, locationNote, phone, instagramUrl.
        return new UpdateSalonRequest(
                name, null, null, null, null, cityId, null,
                VALID_STREET, VALID_BUILDING_NO, null, null, null);
    }

    /** Raw-SQL read that bypasses the Hibernate persistence context — reads the committed row. */
    private Boolean readIsActive(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT is_active FROM salons WHERE id = ?", Boolean.class, salonId);
    }

    private String readSlug(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT booking_slug FROM salons WHERE id = ?", String.class, salonId);
    }

    private String readName(UUID salonId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM salons WHERE id = ?", String.class, salonId);
    }
}
