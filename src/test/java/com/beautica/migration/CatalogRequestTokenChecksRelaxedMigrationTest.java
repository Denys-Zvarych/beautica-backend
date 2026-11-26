package com.beautica.migration;

import com.beautica.AbstractIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B545 (V266, expand/contract) — the token cross-field CHECKs are dropped so a B545 pod can decide a request a pre-B545
 * pod minted with a token, while the token columns and their indexes stay for the deploy overlap and rollback. The
 * columns themselves are dropped later (phase 555).
 */
@DisplayName("V266 migration — catalog request token CHECKs relaxed, columns kept")
class CatalogRequestTokenChecksRelaxedMigrationTest extends AbstractIntegrationTest {

    private static final List<String> TABLES = List.of("platform_categories", "service_type_suggestion");
    private static final String LEGACY_CATEGORY = "ITV266_LEGACY_CAT";
    private static final String NEW_CATEGORY = "ITV266_NEW_CAT";
    private static final String LEGACY_SUGGESTION = "ITV266 Legacy Ritual";
    private static final String NEW_SUGGESTION = "ITV266 New Ritual";
    private static final String LEGACY_HASH = "ab".repeat(32);

    @AfterEach
    void removeSeededRows() {
        // Not in cleanDb(); no FK between the seeded rows (HAIRDRESSING is a seeded category).
        jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE suggested_name IN (?, ?)",
                LEGACY_SUGGESTION, NEW_SUGGESTION);
        jdbcTemplate.update("DELETE FROM platform_categories WHERE name IN (?, ?)", LEGACY_CATEGORY, NEW_CATEGORY);
    }

    @Test
    @DisplayName("the two token CHECK constraints are gone")
    void should_haveNoTokenChecks_when_migrated() {
        List<String> checks = jdbcTemplate.queryForList(
                "SELECT conname FROM pg_constraint WHERE conname IN (?, ?)", String.class,
                "chk_platform_categories_token_pending", "chk_service_type_suggestion_token_pending");

        assertThat(checks).isEmpty();
    }

    @Test
    @DisplayName("both tables still carry nullable token_hash and token_expires_at columns")
    void should_keepNullableTokenColumns_when_migrated() {
        List<String> columns = jdbcTemplate.queryForList("""
                SELECT table_name || '.' || column_name || ':' || is_nullable FROM information_schema.columns
                 WHERE table_schema = current_schema() AND table_name IN (?, ?)
                   AND column_name IN ('token_hash', 'token_expires_at')
                """, String.class, TABLES.get(0), TABLES.get(1));

        assertThat(columns).containsExactlyInAnyOrder(
                "platform_categories.token_hash:YES",
                "platform_categories.token_expires_at:YES",
                "service_type_suggestion.token_hash:YES",
                "service_type_suggestion.token_expires_at:YES");
    }

    @Test
    @DisplayName("the two partial token indexes are still present")
    void should_keepTokenIndexes_when_migrated() {
        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename IN (?, ?)", String.class, TABLES.get(0), TABLES.get(1));

        assertThat(indexes).contains("idx_platform_categories_token_hash", "idx_service_type_suggestion_token_hash");
    }

    @Test
    @DisplayName("deploy overlap: a pre-B545 request minted with a token is decidable with the token left set")
    void should_allowDecidedRowWithLeftoverToken_when_legacyRequestDecided() {
        jdbcTemplate.update("INSERT INTO platform_categories "
                + "(name, display_name, active, status, requested_at, token_hash, token_expires_at) "
                + "VALUES (?, 'Спадковий запит', false, 'PENDING', NOW(), ?, NOW() + INTERVAL '7 days')",
                LEGACY_CATEGORY, LEGACY_HASH);
        UUID suggestionId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO service_type_suggestion "
                + "(id, category_name, suggested_name, status, token_hash, token_expires_at) "
                + "VALUES (?, 'HAIRDRESSING', ?, 'PENDING', ?, NOW() + INTERVAL '7 days')",
                suggestionId, LEGACY_SUGGESTION, LEGACY_HASH);

        // What a B545 pod's decide() writes: status + decided_at only; it no longer maps the token columns.
        int categories = jdbcTemplate.update("UPDATE platform_categories SET status = 'APPROVED', active = true, "
                + "decided_at = NOW() WHERE name = ?", LEGACY_CATEGORY);
        int suggestions = jdbcTemplate.update("UPDATE service_type_suggestion SET status = 'REJECTED', "
                + "decided_at = NOW() WHERE id = ?", suggestionId);

        assertThat(categories).isEqualTo(1);
        assertThat(suggestions).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT status || ':' || token_hash FROM platform_categories WHERE name = ?", String.class,
                LEGACY_CATEGORY)).containsExactly("APPROVED:" + LEGACY_HASH);
        assertThat(jdbcTemplate.queryForList(
                "SELECT status || ':' || token_hash FROM service_type_suggestion WHERE id = ?", String.class,
                suggestionId)).containsExactly("REJECTED:" + LEGACY_HASH);
    }

    @Test
    @DisplayName("a B545 insert carries no token: PENDING rows with NULL token_hash / token_expires_at are accepted")
    void should_acceptTokenlessPendingInsert_when_migrated() {
        int categories = jdbcTemplate.update("INSERT INTO platform_categories "
                + "(name, display_name, active, status, requested_at) "
                + "VALUES (?, 'Новий запит', false, 'PENDING', NOW())", NEW_CATEGORY);
        int suggestions = jdbcTemplate.update("INSERT INTO service_type_suggestion "
                + "(id, category_name, suggested_name, status) VALUES (?, 'HAIRDRESSING', ?, 'PENDING')",
                UUID.randomUUID(), NEW_SUGGESTION);

        assertThat(categories).isEqualTo(1);
        assertThat(suggestions).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM platform_categories "
                + "WHERE name = ? AND token_hash IS NULL AND token_expires_at IS NULL", Long.class, NEW_CATEGORY))
                .isEqualTo(1L);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM service_type_suggestion "
                + "WHERE suggested_name = ? AND token_hash IS NULL AND token_expires_at IS NULL", Long.class,
                NEW_SUGGESTION)).isEqualTo(1L);
    }
}
