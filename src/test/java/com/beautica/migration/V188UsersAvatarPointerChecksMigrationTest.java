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
 * Contract test for V188__users_avatar_pointer_format_checks.sql (security S-L3). Raw JDBC writes — the
 * whole point of a storage-layer guard is that it holds when application code is bypassed. NULL passes both
 * CHECKs by design (Postgres CHECK semantics). {@link AbstractIntegrationTest#cleanDb()} clears users.
 */
@DisplayName("V188 migration — users avatar pointer CHECK constraints")
class V188UsersAvatarPointerChecksMigrationTest extends AbstractIntegrationTest {

    private static final String KEY_CHECK = "chk_users_avatar_r2_key_format";
    private static final String URL_CHECK = "chk_users_avatar_url_scheme";
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    @Test
    @DisplayName("avatar_r2_key with traversal is rejected")
    void should_rejectAvatarKey_when_keyHasTraversal() {
        UUID id = insertUser();

        assertThatThrownBy(() -> setKey(id, "avatars/" + id + "/../x.jpg"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(KEY_CHECK);
    }

    @Test
    @DisplayName("avatar_r2_key with a leading slash is rejected")
    void should_rejectAvatarKey_when_keyIsAbsolute() {
        UUID id = insertUser();

        assertThatThrownBy(() -> setKey(id, "/avatars/" + id + "/x.jpg"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(KEY_CHECK);
    }

    @Test
    @DisplayName("avatar_r2_key under ANOTHER user's avatars/<id>/ prefix is rejected")
    void should_rejectAvatarKey_when_keyUnderForeignUserPrefix() {
        UUID id = insertUser();

        assertThatThrownBy(() -> setKey(id, "avatars/" + UUID.randomUUID() + "/x.jpg"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(KEY_CHECK);
    }

    @Test
    @DisplayName("avatar_r2_key outside avatars/ (e.g. a service-photo key) is rejected")
    void should_rejectAvatarKey_when_keyOutsideAvatarsRoot() {
        UUID id = insertUser();

        assertThatThrownBy(() -> setKey(id, "services/" + id + "/x.jpg"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(KEY_CHECK);
    }

    @Test
    @DisplayName("avatar_r2_key with a disallowed character is rejected")
    void should_rejectAvatarKey_when_keyHasIllegalCharacter() {
        UUID id = insertUser();

        assertThatThrownBy(() -> setKey(id, "avatars/" + id + "/x y.jpg"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(KEY_CHECK);
    }

    @Test
    @DisplayName("avatar_url with http:// or javascript: is rejected")
    void should_rejectAvatarUrl_when_notHttps() {
        UUID id = insertUser();

        assertThatThrownBy(() -> setUrl(id, "http://cdn.example/a.jpg"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(URL_CHECK);
        assertThatThrownBy(() -> setUrl(id, "javascript:alert(1)"))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining(URL_CHECK);
    }

    @Test
    @DisplayName("the server-generated shape (own prefix) and an https URL are accepted")
    void should_acceptPointers_when_ownPrefixKeyAndHttpsUrl() {
        UUID id = insertUser();

        assertThatCode(() -> {
            setKey(id, "avatars/" + id + "/1712345678901-" + UUID.randomUUID() + ".jpg");
            setUrl(id, "https://pub.r2.dev/avatars/" + id + "/a.jpg");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("NULL key and NULL url are accepted (no avatar / legacy url-only rows)")
    void should_acceptNulls_when_noAvatar() {
        UUID id = insertUser();

        assertThatCode(() -> jdbcTemplate.update(
                "UPDATE users SET avatar_r2_key = NULL, avatar_url = NULL WHERE id = ?", id))
                .doesNotThrowAnyException();
    }

    private void setKey(UUID id, String key) {
        jdbcTemplate.update("UPDATE users SET avatar_r2_key = ? WHERE id = ?", key, id);
    }

    private void setUrl(UUID id, String url) {
        jdbcTemplate.update("UPDATE users SET avatar_url = ? WHERE id = ?", url, id);
    }

    private UUID insertUser() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role) VALUES (?, ?, ?, ?)",
                id, "v188-" + id + "@beautica.test", PASSWORD_HASH, "CLIENT");
        return id;
    }
}
