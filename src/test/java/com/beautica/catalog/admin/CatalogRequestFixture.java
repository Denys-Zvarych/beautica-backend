package com.beautica.catalog.admin;

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

    /** A seeded request row's id. */
    record Seeded<I>(I id) {}

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    private final List<UUID> suggestionIds = new ArrayList<>();
    private final List<String> categoryNames = new ArrayList<>();
    private final List<String> typeNames = new ArrayList<>();

    CatalogRequestFixture(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    /** Active, e-mail-verified user with a real BCrypt hash. */
    UUID insertUser(String email, String role, String first, String last, String phone) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, "
                        + "last_name, phone_number) VALUES (?, ?, ?, ?, true, true, ?, ?, ?)",
                id, email, passwordEncoder.encode("test-password"), role, first, last, phone);
        return id;
    }

    /** Category request in any status. */
    long insertCategory(String name, String displayName, String status, UUID requester, Instant createdAt) {
        categoryNames.add(name);
        jdbcTemplate.update("INSERT INTO platform_categories (name, display_name, active, status, requested_by_user_id, "
                        + "requested_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                name, displayName, "APPROVED".equals(status), status, requester, Timestamp.from(createdAt),
                Timestamp.from(createdAt));
        return categoryId(name);
    }

    /** Suggestion in any status. */
    UUID insertSuggestion(String category, String name, String status, UUID requester, Instant createdAt) {
        UUID id = UUID.randomUUID();
        suggestionIds.add(id);
        jdbcTemplate.update("INSERT INTO service_type_suggestion (id, requested_by_user_id, category_name, "
                        + "suggested_name, description, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, requester, category, name, "опис", status, Timestamp.from(createdAt));
        return id;
    }

    /**
     * PENDING suggestion to be decided. {@code createdAt} {@code null} = the database's {@code NOW()}. The suggested
     * name is tracked as a service type an approve may promote.
     */
    UUID pendingSuggestion(String category, String name, UUID requester, Instant createdAt) {
        UUID id = UUID.randomUUID();
        suggestionIds.add(id);
        typeNames.add(name);
        jdbcTemplate.update("INSERT INTO service_type_suggestion (id, requested_by_user_id, category_name, "
                        + "suggested_name, status, created_at) "
                        + "VALUES (?, ?, ?, ?, 'PENDING', COALESCE(CAST(? AS timestamptz), NOW()))",
                id, requester, category, name, timestampOrNull(createdAt));
        return id;
    }

    /**
     * PENDING category request to be decided; {@code requested_at} = {@code created_at} = {@code createdAt}, or the
     * database's {@code NOW()} when {@code null}.
     */
    long pendingCategory(String name, String displayName, UUID requester, String initialServiceName,
                         Instant createdAt) {
        categoryNames.add(name);
        Timestamp created = timestampOrNull(createdAt);
        jdbcTemplate.update("INSERT INTO platform_categories (name, display_name, active, status, requested_by_user_id, "
                        + "requested_at, created_at, initial_service_name) VALUES (?, ?, false, 'PENDING', ?, "
                        + "COALESCE(CAST(? AS timestamptz), NOW()), COALESCE(CAST(? AS timestamptz), NOW()), ?)",
                name, displayName, requester, created, created, initialServiceName);
        return categoryId(name);
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
