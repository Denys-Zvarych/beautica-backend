package com.beautica.media;

import com.beautica.config.TestSecurityConfig;
import com.beautica.media.service.R2StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.beautica.support.R2DeleteLedger.purgedKeys;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

/**
 * Avatar blob lifecycle against real Postgres with a mocked R2: legacy (url-only) avatars (H2/M2) and the
 * upload-first replace flow (M1).
 */
@Import(TestSecurityConfig.class)
@DisplayName("Avatar blob lifecycle — legacy recovery + upload-first replace")
class AvatarBlobLifecycleIT extends AbstractMediaIntegrationTest {

    private static final String AVATAR_URL = "/api/v1/media/avatar";
    private static final String CDN = "https://cdn.example/";

    @Autowired private TestRestTemplate restTemplate;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PasswordEncoder passwordEncoder;

    @MockBean private R2StorageService r2;

    @Override protected TestRestTemplate restTemplate() { return restTemplate; }
    @Override protected ObjectMapper objectMapper() { return objectMapper; }
    @Override protected PasswordEncoder passwordEncoder() { return passwordEncoder; }

    @BeforeEach
    void setUpR2() {
        reset(r2);
        when(r2.isEnabled()).thenReturn(true);
        when(r2.buildPublicUrl(anyString())).thenAnswer(inv -> CDN + inv.getArgument(0));
        when(r2.extractKeyFromPublicUrl(anyString())).thenAnswer(inv -> {
            String url = inv.getArgument(0);
            return url.startsWith(CDN) ? Optional.of(url.substring(CDN.length())) : Optional.empty();
        });
        doNothing().when(r2).uploadFile(anyString(), any(), anyLong(), anyString());
        doNothing().when(r2).deleteFile(anyString());
    }

    @AfterEach
    void dropFailureTrigger() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS trg_fail_avatar_write ON users");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_avatar_write()");
    }

    // ── H2 — legacy avatar replace ───────────────────────────────────────────────

    @Test
    @DisplayName("H2: replacing a legacy avatar (url set, key null) purges the URL-derived key")
    void should_purgeUrlDerivedKey_when_legacyAvatarReplaced() throws Exception {
        Legacy u = legacyUser("h2-replace", null);

        ResponseEntity<String> resp = post(u.token());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(purgedKeys(r2)).containsExactly(u.derivedKey());
    }

    @Test
    @DisplayName("H2: a legacy URL with a foreign host is NOT purged")
    void should_notPurge_when_legacyUrlHasForeignHost() throws Exception {
        Legacy u = legacyUser("h2-host", "https://evil.example/avatars/%s/x.jpg");

        ResponseEntity<String> resp = post(u.token());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(purgedKeys(r2)).isEmpty();
    }

    @Test
    @DisplayName("H2: a legacy URL under the CDN but another user's prefix is NOT purged")
    void should_notPurge_when_legacyUrlIsUnderForeignPrefix() throws Exception {
        Legacy u = legacyUser("h2-prefix", CDN + "avatars/" + UUID.randomUUID() + "/x.jpg");

        ResponseEntity<String> resp = post(u.token());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(purgedKeys(r2)).isEmpty();
    }

    @Test
    @DisplayName("H2: self-delete of a legacy-avatar client purges the URL-derived key")
    void should_purgeUrlDerivedKey_when_legacyClientSelfDeletes() throws Exception {
        Legacy u = legacyUser("h2-selfdel", null);

        ResponseEntity<Void> resp = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(bearer(u.token())), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(purgedKeys(r2)).containsExactly(u.derivedKey());
    }

    @Test
    @DisplayName("H2: self-delete with a foreign-prefix legacy URL purges nothing")
    void should_notPurge_when_legacyClientWithForeignPrefixSelfDeletes() throws Exception {
        Legacy u = legacyUser("h2-selfdel-foreign", CDN + "avatars/" + UUID.randomUUID() + "/x.jpg");

        ResponseEntity<Void> resp = restTemplate.exchange("/api/v1/users/me", HttpMethod.DELETE,
                new HttpEntity<>(bearer(u.token())), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(purgedKeys(r2)).isEmpty();
    }

    // ── M2 — a legacy avatar can be removed ──────────────────────────────────────

    @Test
    @DisplayName("M2: DELETE /media/avatar on a legacy avatar -> 204, both pointers null, derived key purged")
    void should_clearBothPointersAndPurge_when_legacyAvatarDeleted() throws Exception {
        Legacy u = legacyUser("m2", null);

        ResponseEntity<Void> resp = restTemplate.exchange(AVATAR_URL, HttpMethod.DELETE,
                new HttpEntity<>(bearer(u.token())), Void.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(pointers(u.id())).containsExactly(null, null);
        assertThat(purgedKeys(r2)).containsExactly(u.derivedKey());
    }

    // ── M1 — upload-first replace ────────────────────────────────────────────────

    @Test
    @DisplayName("M1: R2 upload fails -> old avatar intact and the old object is NOT deleted")
    void should_keepOldAvatarAndObject_when_r2UploadFails() throws Exception {
        Seeded u = seededUser("m1-upload");
        doThrow(new RuntimeException("r2 down")).when(r2).uploadFile(anyString(), any(), anyLong(), anyString());

        ResponseEntity<String> resp = post(u.token());

        assertThat(resp.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(pointers(u.id())).containsExactly(u.oldKey(), CDN + u.oldKey());
        assertThat(purgedKeys(r2)).isEmpty();
    }

    @Test
    @DisplayName("M1: DB write fails after upload -> NEW object deleted, old pointers intact, old object kept")
    void should_discardNewObjectAndKeepOld_when_dbWriteFailsAfterUpload() throws Exception {
        Seeded u = seededUser("m1-dbfail");
        jdbcTemplate.execute("""
                CREATE FUNCTION fail_avatar_write() RETURNS trigger AS $$
                BEGIN
                  IF NEW.avatar_r2_key IS DISTINCT FROM OLD.avatar_r2_key AND NEW.avatar_r2_key IS NOT NULL THEN
                    RAISE EXCEPTION 'forced avatar write failure';
                  END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql""");
        jdbcTemplate.execute("CREATE TRIGGER trg_fail_avatar_write BEFORE UPDATE ON users "
                + "FOR EACH ROW EXECUTE FUNCTION fail_avatar_write()");

        ResponseEntity<String> resp = post(u.token());

        assertThat(resp.getStatusCode().is2xxSuccessful()).isFalse();
        assertThat(pointers(u.id())).containsExactly(u.oldKey(), CDN + u.oldKey());
        List<String> purged = purgedKeys(r2);
        assertThat(purged).hasSize(1).doesNotContain(u.oldKey());
        assertThat(purged.get(0)).startsWith("avatars/" + u.id() + "/");
    }

    @Test
    @DisplayName("M1: success -> the old key is purged only AFTER the new pointer is committed")
    void should_purgeOldKeyOnlyAfterCommit_when_replaceSucceeds() throws Exception {
        Seeded u = seededUser("m1-order");
        List<String> keyInDbWhenDeleted = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            keyInDbWhenDeleted.add(jdbcTemplate.queryForObject(
                    "SELECT avatar_r2_key FROM users WHERE id = ?", String.class, u.id()));
            return java.util.Set.of();
        }).when(r2).deleteFiles(any());

        ResponseEntity<String> resp = post(u.token());

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        String newKey = pointers(u.id()).get(0);
        assertThat(newKey).isNotEqualTo(u.oldKey());
        assertThat(purgedKeys(r2)).containsExactly(u.oldKey());
        assertThat(keyInDbWhenDeleted).as("DB already pointed at the new key when the old object was deleted")
                .containsExactly(newKey);
    }

    @Test
    @DisplayName("M1: two concurrent replaces leave exactly one live blob (stored = deleted + live)")
    void should_leaveExactlyOneLiveBlob_when_twoReplacesRace() throws Exception {
        Seeded u = seededUser("m1-race");
        CountDownLatch bothUploading = new CountDownLatch(2);
        List<String> uploaded = new CopyOnWriteArrayList<>();
        doAnswer(inv -> {
            uploaded.add(inv.getArgument(0));
            bothUploading.countDown();
            assertThat(bothUploading.await(20, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(r2).uploadFile(anyString(), any(), anyLong(), anyString());
        ExecutorService pool = Executors.newFixedThreadPool(2);

        List<Future<ResponseEntity<String>>> results;
        try {
            results = pool.invokeAll(List.of(() -> post(u.token()), () -> post(u.token())));
        } finally {
            pool.shutdownNow();
        }

        for (Future<ResponseEntity<String>> r : results) {
            assertThat(r.get().getStatusCode()).isEqualTo(HttpStatus.OK);
        }
        String live = pointers(u.id()).get(0);
        Set<String> everyBlob = new HashSet<>(uploaded);
        everyBlob.add(u.oldKey());
        Set<String> deleted = new HashSet<>(purgedKeys(r2));
        Set<String> expected = new HashSet<>(deleted);
        expected.add(live);
        assertThat(uploaded).hasSize(2);
        assertThat(deleted).doesNotContain(live);
        assertThat(everyBlob).containsExactlyInAnyOrderElementsOf(expected);
    }

    // ── helpers ─────────────────────────────────────────────────────────────────

    private record Legacy(UUID id, String token, String derivedKey) {}

    private record Seeded(UUID id, String token, String oldKey) {}

    /** User with avatar_url set and avatar_r2_key NULL. {@code urlTemplate} may contain one %s for the user id. */
    private Legacy legacyUser(String tag, String urlTemplate) throws Exception {
        String email = tag + "-" + System.nanoTime() + "@beautica.test";
        UUID id = insertClient(email);
        String derivedKey = "avatars/" + id + "/legacy.jpg";
        String url = urlTemplate == null ? CDN + derivedKey
                : urlTemplate.contains("%s") ? urlTemplate.formatted(id) : urlTemplate;
        jdbcTemplate.update("UPDATE users SET avatar_url = ?, avatar_r2_key = NULL WHERE id = ?", url, id);
        return new Legacy(id, loginAndGetToken(email), derivedKey);
    }

    private Seeded seededUser(String tag) throws Exception {
        String email = tag + "-" + System.nanoTime() + "@beautica.test";
        UUID id = insertClient(email);
        String oldKey = "avatars/" + id + "/old.jpg";
        jdbcTemplate.update("UPDATE users SET avatar_url = ?, avatar_r2_key = ? WHERE id = ?",
                CDN + oldKey, oldKey, id);
        return new Seeded(id, loginAndGetToken(email), oldKey);
    }

    private List<String> pointers(UUID userId) {
        return jdbcTemplate.query("SELECT avatar_r2_key, avatar_url FROM users WHERE id = ?",
                (rs, i) -> {
                    List<String> p = new ArrayList<>();
                    p.add(rs.getString(1));
                    p.add(rs.getString(2));
                    return p;
                }, userId).get(0);
    }

    private ResponseEntity<String> post(String token) {
        return restTemplate.exchange(AVATAR_URL, HttpMethod.POST,
                new HttpEntity<>(jpegMultipartBody(), bearerMultipartHeaders(token)), String.class);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }
}
