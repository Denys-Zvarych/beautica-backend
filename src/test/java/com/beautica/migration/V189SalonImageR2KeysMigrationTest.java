package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contract test for V189__add_salon_image_r2_keys.sql (Phase 343 TC-11). Raw JDBC writes — a storage-layer
 * guard must hold when application code is bypassed. NULL passes every CHECK by design (Postgres CHECK
 * semantics: no image / legacy url-only rows). {@link AbstractIntegrationTest#cleanDb()} clears salons + users.
 */
@DisplayName("V189 migration — salon logo/cover R2 key + URL CHECK constraints")
class V189SalonImageR2KeysMigrationTest extends AbstractIntegrationTest {

    private static final String LOGO_KEY_CHECK = "chk_salons_avatar_r2_key_format";
    private static final String COVER_KEY_CHECK = "chk_salons_cover_r2_key_format";
    private static final String LOGO_URL_CHECK = "chk_salons_avatar_url_scheme";
    private static final String COVER_URL_CHECK = "chk_salons_cover_image_url_scheme";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    @Test
    @DisplayName("a key with traversal is rejected")
    void should_rejectKey_when_keyHasTraversal() {
        UUID id = insertSalon();

        assertThatThrownBy(() -> set(id, "avatar_r2_key", "salons/" + id + "/logo/../x.jpg"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(LOGO_KEY_CHECK);
        assertThatThrownBy(() -> set(id, "cover_r2_key", "../x"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(COVER_KEY_CHECK);
    }

    @Test
    @DisplayName("a key with a leading slash is rejected")
    void should_rejectKey_when_keyIsAbsolute() {
        UUID id = insertSalon();

        assertThatThrownBy(() -> set(id, "avatar_r2_key", "/salons/" + id + "/logo/x.jpg"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(LOGO_KEY_CHECK);
    }

    @Test
    @DisplayName("a key under the wrong root or the wrong slot is rejected (avatars/… or a logo key in cover_r2_key)")
    void should_rejectKey_when_prefixWrong() {
        UUID id = insertSalon();

        assertThatThrownBy(() -> set(id, "cover_r2_key", "avatars/" + id + "/x.jpg"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(COVER_KEY_CHECK);
        assertThatThrownBy(() -> set(id, "cover_r2_key", "salons/" + id + "/logo/x.jpg"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(COVER_KEY_CHECK);
        assertThatThrownBy(() -> set(id, "avatar_r2_key", "salons/" + id + "/cover/x.jpg"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(LOGO_KEY_CHECK);
    }

    @Test
    @DisplayName("a key under ANOTHER salon's prefix is rejected")
    void should_rejectKey_when_keyUnderForeignSalon() {
        UUID id = insertSalon();

        assertThatThrownBy(() -> set(id, "avatar_r2_key", "salons/" + UUID.randomUUID() + "/logo/x.jpg"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(LOGO_KEY_CHECK);
    }

    @Test
    @DisplayName("a key with a disallowed character is rejected")
    void should_rejectKey_when_keyHasIllegalCharacter() {
        UUID id = insertSalon();

        assertThatThrownBy(() -> set(id, "cover_r2_key", "salons/" + id + "/cover/x y.jpg"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(COVER_KEY_CHECK);
    }

    @Test
    @DisplayName("a non-https logo/cover URL is rejected")
    void should_rejectUrl_when_notHttps() {
        UUID id = insertSalon();

        assertThatThrownBy(() -> set(id, "avatar_url", "http://cdn.example/a.jpg"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(LOGO_URL_CHECK);
        assertThatThrownBy(() -> set(id, "cover_image_url", "javascript:alert(1)"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(COVER_URL_CHECK);
    }

    @Test
    @DisplayName("the server-generated shapes and https URLs are accepted")
    void should_acceptPointers_when_ownSlotKeysAndHttpsUrls() {
        UUID id = insertSalon();

        assertThatCode(() -> {
            set(id, "avatar_r2_key", "salons/" + id + "/logo/1712345678901-" + UUID.randomUUID() + ".jpg");
            set(id, "cover_r2_key", "salons/" + id + "/cover/1712345678901-" + UUID.randomUUID() + ".webp");
            set(id, "avatar_url", "https://pub.r2.dev/salons/" + id + "/logo/a.jpg");
            set(id, "cover_image_url", "https://pub.r2.dev/salons/" + id + "/cover/c.jpg");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NULL keys and URLs are accepted (no image / legacy url-only rows)")
    void should_acceptNulls_when_noImage() {
        UUID id = insertSalon();

        assertThatCode(() -> jdbcTemplate.update("UPDATE salons SET avatar_url = NULL, avatar_r2_key = NULL, "
                + "cover_image_url = NULL, cover_r2_key = NULL WHERE id = ?", id))
                .doesNotThrowAnyException();
    }

    /** Column names are compile-time constants of this class only — never caller input. */
    private void set(UUID salonId, String column, String value) {
        jdbcTemplate.update("UPDATE salons SET " + column + " = ? WHERE id = ?", value, salonId);
    }

    private UUID insertSalon() {
        UUID ownerId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, 'SALON_OWNER')",
                ownerId, "v189-" + ownerId + "@beautica.test", PASSWORD_HASH);
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                + "VALUES (?, ?, 'V189 Salon', true, NOW(), NOW(), ?)", salonId, ownerId, testCityId());
        return salonId;
    }
}
