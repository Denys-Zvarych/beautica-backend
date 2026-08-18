package com.beautica.migration;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

/**
 * Raw-SQL seed helper shared by the {@code bookings} constraint/migration tests
 * ({@link GuestBookingColumnsMigrationTest}, {@link V137StaffBookingSourceMigrationTest}).
 *
 * <p>Extracted per the test-infrastructure rule "extract shared {@code @SpringBootTest} fixtures
 * the moment a second test file copies them" — these tests assert DB-level CHECK behaviour and
 * therefore must insert through {@code JdbcTemplate} rather than through JPA, which would let the
 * entity's own guards mask the constraint under test.
 */
final class BookingMigrationFixtures {

    /**
     * Cost 4 (the BCrypt minimum) — a real hash rather than a placeholder string, so a fixture can
     * never smuggle a non-BCrypt value past code that later assumes the column is a valid hash.
     * Computed once: BCrypt is deliberately slow and none of these tests authenticate.
     */
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    private BookingMigrationFixtures() {
    }

    /**
     * Ids of the minimal graph a {@code bookings} row needs to satisfy its FKs.
     *
     * <p>{@code staffUserId} is a SALON_OWNER that owns <em>nothing else</em> in this graph. It
     * exists so {@code created_by_user_id} tests can name a creator that is genuinely a staff
     * member (V137's stated intent) instead of borrowing {@code clientId} — and, more importantly,
     * so a test may DELETE the creator to exercise the FK's delete action without also destroying
     * the master, the service assignment or the booking itself through some other cascade.
     */
    record Ids(UUID clientId, UUID masterUserId, UUID masterId, UUID masterServiceId, UUID staffUserId) {}

    /**
     * Seeds a CLIENT user, a SALON_OWNER staff user, an INDEPENDENT_MASTER user + master, and one
     * active service definition/assignment — the minimum FK graph for inserting into
     * {@code bookings}.
     */
    static Ids seedBookingGraph(JdbcTemplate jdbcTemplate) {
        UUID clientId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true)",
                clientId, "client-" + clientId + "@beautica.test", PASSWORD_HASH);

        UUID staffUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, ?, 'SALON_OWNER', true, true, 'Ірина', 'Бондаренко')",
                staffUserId, "owner-" + staffUserId + "@beautica.test", PASSWORD_HASH);

        UUID masterUserId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true, true, 'Марія', 'Левченко')",
                masterUserId, "master-" + masterUserId + "@beautica.test", PASSWORD_HASH);

        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())",
                masterId, masterUserId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Манікюр', ?, 60, 350.00, 0, true, NOW(), NOW())",
                serviceDefId, masterUserId, resolveServiceTypeId(jdbcTemplate));

        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        return new Ids(clientId, masterUserId, masterId, masterServiceId, staffUserId);
    }

    /**
     * Resolves a real, selectable {@code service_types.id} (V111 made the column NOT NULL).
     * The constraints under test are orthogonal to which service type is used, so any active,
     * APPROVED-category type satisfies the FK.
     */
    private static UUID resolveServiceTypeId(JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1",
                UUID.class);
    }
}
