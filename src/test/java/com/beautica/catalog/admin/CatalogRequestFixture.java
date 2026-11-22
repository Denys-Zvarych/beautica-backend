package com.beautica.catalog.admin;

import com.beautica.auth.TokenGenerator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Shared JDBC seeding for the catalog-admin request ITs ({@code InternalCatalogRequestControllerIT},
 * {@code InternalCatalogRequestDecisionIT}): users, category requests and service-type suggestions, plus the cleanup of
 * every catalogue row it seeded. One instance per test (built in {@code @BeforeEach}), {@link #cleanup()} from the IT's
 * {@code @AfterEach} — which runs before {@code AbstractIntegrationTest#cleanDb}, as these tables are not in it.
 */
final class CatalogRequestFixture {

    /** A request row and the raw email token whose hash it stores. */
    record Seeded<I>(I id, String rawToken) {}

    private static final long LONG_EXPIRY_SECONDS = 86_400_000L;

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final TokenGenerator tokenGenerator;

    private final List<UUID> suggestionIds = new ArrayList<>();
    private final List<String> categoryNames = new ArrayList<>();
    private final List<String> typeNames = new ArrayList<>();

    CatalogRequestFixture(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder, TokenGenerator tokenGenerator) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.tokenGenerator = tokenGenerator;
    }

    /** Active, e-mail-verified user with a real BCrypt hash. */
    UUID insertUser(String email, String role, String first, String last, String phone) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, "
                        + "last_name, phone_number) VALUES (?, ?, ?, ?, true, true, ?, ?, ?)",
                id, email, passwordEncoder.encode("test-password"), role, first, last, phone);
        return id;
    }

    /** Category request in any status; a PENDING one gets a placeholder (non-redeemable) token hash. */
    long insertCategory(String name, String displayName, String status, UUID requester, Instant createdAt) {
        categoryNames.add(name);
        boolean pending = "PENDING".equals(status);
        jdbcTemplate.update("INSERT INTO platform_categories (name, display_name, active, status, requested_by_user_id, "
                        + "requested_at, token_hash, token_expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                name, displayName, "APPROVED".equals(status), status, requester, Timestamp.from(createdAt),
                pending ? "hash-" + name : null,
                pending ? Timestamp.from(createdAt.plusSeconds(LONG_EXPIRY_SECONDS)) : null,
                Timestamp.from(createdAt));
        return categoryId(name);
    }

    /** Suggestion in any status; a PENDING one gets a placeholder (non-redeemable) token hash. */
    UUID insertSuggestion(String category, String name, String status, UUID requester, Instant createdAt) {
        UUID id = UUID.randomUUID();
        suggestionIds.add(id);
        boolean pending = "PENDING".equals(status);
        jdbcTemplate.update("INSERT INTO service_type_suggestion (id, requested_by_user_id, category_name, "
                        + "suggested_name, description, status, token_hash, token_expires_at, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, requester, category, name, "опис", status,
                pending ? "hash-" + id : null,
                pending ? Timestamp.from(createdAt.plusSeconds(LONG_EXPIRY_SECONDS)) : null,
                Timestamp.from(createdAt));
        return id;
    }

    /**
     * PENDING suggestion with a REAL email token (redeemable on the review link). {@code createdAt} {@code null} = the
     * database's {@code NOW()}. The suggested name is tracked as a service type an approve may promote.
     */
    Seeded<UUID> pendingSuggestionWithToken(String category, String name, UUID requester, Instant tokenExpiresAt,
                                            Instant createdAt) {
        UUID id = UUID.randomUUID();
        String raw = "it-sugg-token-" + id;
        suggestionIds.add(id);
        typeNames.add(name);
        jdbcTemplate.update("INSERT INTO service_type_suggestion (id, requested_by_user_id, category_name, "
                        + "suggested_name, status, token_hash, token_expires_at, created_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', ?, ?, COALESCE(CAST(? AS timestamptz), NOW()))",
                id, requester, category, name, tokenGenerator.hash(raw), Timestamp.from(tokenExpiresAt),
                timestampOrNull(createdAt));
        return new Seeded<>(id, raw);
    }

    /**
     * PENDING category request with a REAL email token; {@code requested_at} = {@code created_at} = {@code createdAt},
     * or the database's {@code NOW()} when {@code null}.
     */
    Seeded<Long> pendingCategoryWithToken(String name, String displayName, UUID requester, String initialServiceName,
                                          Instant tokenExpiresAt, Instant createdAt) {
        categoryNames.add(name);
        String raw = "it-cat-token-" + UUID.randomUUID();
        Timestamp created = timestampOrNull(createdAt);
        jdbcTemplate.update("INSERT INTO platform_categories (name, display_name, active, status, requested_by_user_id, "
                        + "requested_at, token_hash, token_expires_at, created_at, initial_service_name) "
                        + "VALUES (?, ?, false, 'PENDING', ?, COALESCE(CAST(? AS timestamptz), NOW()), ?, ?, COALESCE(CAST(? AS timestamptz), NOW()), ?)",
                name, displayName, requester, created, tokenGenerator.hash(raw), Timestamp.from(tokenExpiresAt),
                created, initialServiceName);
        return new Seeded<>(categoryId(name), raw);
    }

    /** A service-type name an approve will promote, removed on cleanup. */
    void trackType(String name) {
        typeNames.add(name);
    }

    /** A platform category the test inserted by hand, removed on cleanup. */
    void trackCategory(String name) {
        categoryNames.add(name);
    }

    /**
     * Children first: promoted service_types (no service_definitions reference them) → suggestions (incl. those an
     * approved category spawned from its initial service name, keyed by the wire name) → categories. Suggestions and
     * categories reference users ON DELETE SET NULL, so this runs ahead of cleanDb's DELETE FROM users.
     */
    void cleanup() {
        typeNames.forEach(n -> jdbcTemplate.update("DELETE FROM service_types WHERE name_uk = ?", n));
        suggestionIds.forEach(id -> jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE id = ?", id));
        categoryNames.forEach(n -> jdbcTemplate.update("DELETE FROM service_type_suggestion WHERE category_name = ?", n));
        categoryNames.forEach(n -> jdbcTemplate.update("DELETE FROM platform_categories WHERE name = ?", n));
    }

    private long categoryId(String name) {
        return jdbcTemplate.queryForObject("SELECT id FROM platform_categories WHERE name = ?", Long.class, name);
    }

    private static Timestamp timestampOrNull(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
