package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for V105__add_chk_salons_instagram.sql.
 *
 * <p>V105 adds {@code chk_salons_instagram} as a defense-in-depth guard mirroring
 * the V61 {@code chk_users_instagram} CHECK, now that {@code CreateSalonRequest} /
 * {@code UpdateSalonRequest} accept the same bare-handle-or-URL grammar as
 * {@code users.instagram}. The whole point of a storage-layer guard is that a
 * raw-SQL / seed / future-admin-backfill insert bypasses the DTO {@code @Pattern}
 * entirely, so the only meaningful proof is a RAW JDBC insert that never touches a
 * DTO (Anti-Bug Playbook §O; mirrors {@code V102HardeningConstraintsMigrationTest}).
 *
 * <p>{@link AbstractIntegrationTest#cleanDb()} clears the {@code salons} /
 * {@code users} tables (FK order) after every test.
 */
@DisplayName("V105 migration — chk_salons_instagram CHECK constraint")
class V105SalonInstagramConstraintMigrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("INSERT salon.instagram_url with a disallowed scheme is rejected (23514)")
    void should_rejectInsert_when_instagramUrlIsUnsafeScheme() {
        UUID ownerId = insertUser();

        assertThatThrownBy(() -> insertSalon(ownerId, "javascript:alert(1)"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_salons_instagram");
    }

    @Test
    @DisplayName("INSERT salon.instagram_url as a plain http:// URL is rejected (23514)")
    void should_rejectInsert_when_instagramUrlIsPlainHttp() {
        UUID ownerId = insertUser();

        assertThatThrownBy(() -> insertSalon(ownerId, "http://instagram.com/beauty_studio"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_salons_instagram");
    }

    @Test
    @DisplayName("INSERT salon.instagram_url longer than the 30-char bare-handle grammar is rejected (23514)")
    void should_rejectInsert_when_instagramUrlIsOverlongBareHandle() {
        UUID ownerId = insertUser();

        assertThatThrownBy(() -> insertSalon(ownerId, "a".repeat(31)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_salons_instagram");
    }

    @Test
    @DisplayName("INSERT salon.instagram_url as a bare handle is accepted")
    void should_acceptInsert_when_instagramUrlIsBareHandle() {
        UUID ownerId = insertUser();

        assertThatCode(() -> insertSalon(ownerId, "beauty_studio"))
                .as("a bare handle matching ^@?[A-Za-z0-9._]{1,30}$ must pass the CHECK")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("INSERT salon.instagram_url as a full instagram.com URL is accepted")
    void should_acceptInsert_when_instagramUrlIsFullUrl() {
        UUID ownerId = insertUser();

        assertThatCode(() -> insertSalon(ownerId, "https://instagram.com/beauty_studio"))
                .as("the pre-widening full-URL form already written for existing rows must still pass")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("INSERT salon with a NULL instagram_url is accepted (NULL-safe path)")
    void should_acceptInsert_when_instagramUrlIsNull() {
        UUID ownerId = insertUser();

        assertThatCode(() -> insertSalon(ownerId, null))
                .as("the `instagram_url IS NULL OR ...` predicate is TRUE for NULL")
                .doesNotThrowAnyException();
    }

    // ── insert helpers (raw JDBC, bypassing the DTO layer) ───────────────────

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, ?)",
                id, "v105-" + id + "@beautica.test",
                "$2a$04$placeholder.for.migration.test.only", "SALON_OWNER");
        return id;
    }

    private UUID insertSalon(UUID ownerId, String instagramUrl) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, instagram_url) "
                        + "VALUES (?, ?, ?, true, ?)",
                id, ownerId, "V105 Test Salon", instagramUrl);
        return id;
    }
}
