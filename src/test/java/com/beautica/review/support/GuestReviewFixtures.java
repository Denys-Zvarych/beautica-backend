package com.beautica.review.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.UUID;

/**
 * Raw-SQL seeding for the phase-399 guest-review schema tests (§M-3): an independent master with one service, app
 * and LINK-guest bookings, LINK visit headers and reviews. Works over any {@link JdbcTemplate} — the Spring-context
 * ITs' and a private Flyway-migrated container's alike. Stateless; every helper returns the id it created.
 */
public final class GuestReviewFixtures {

    public static final String GUEST_NAME = "Марія";
    public static final String GUEST_SURNAME = "Шевченко";

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    private final JdbcTemplate jdbc;

    public GuestReviewFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID user(String role, String firstName, String lastName) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, ?, ?, true, true, ?, ?)",
                id, "p399-" + id + "@beautica.test", PASSWORD_HASH, role, firstName, lastName);
        return id;
    }

    /** An INDEPENDENT_MASTER account + masters row; returns the master id. */
    public UUID independentMaster() {
        UUID userId = user("INDEPENDENT_MASTER", "Ірина", "Коваль");
        UUID masterId = UUID.randomUUID();
        jdbc.update("INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) "
                + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())", masterId, userId);
        return masterId;
    }

    public UUID masterUserId(UUID masterId) {
        return jdbc.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
    }

    /** One service definition + master_services row for the master; returns the master_services id. */
    public UUID masterService(UUID masterId) {
        UUID serviceTypeId = jdbc.queryForObject("""
                SELECT st.id FROM service_types st
                JOIN platform_categories pc ON pc.name = st.platform_category_name
                WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED'
                ORDER BY st.name_uk LIMIT 1
                """, UUID.class);
        UUID serviceDefId = UUID.randomUUID();
        jdbc.update("INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Манікюр', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, masterId, serviceTypeId);
        UUID masterServiceId = UUID.randomUUID();
        jdbc.update("INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                + "VALUES (?, ?, ?, true, NOW(), NOW())", masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    /** A COMPLETED app booking of {@code clientId} with the master. */
    public UUID appBooking(UUID clientId, UUID masterId, UUID masterServiceId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 'COMPLETED', NOW() - interval '5 hours', NOW() - interval '4 hours', "
                        + "500.00, 60, 0, 'APP', NOW(), NOW())",
                id, clientId, masterId, masterServiceId);
        return id;
    }

    /** A COMPLETED LINK-guest booking (no account) with the master, guest phone {@code phone}. */
    public UUID guestBooking(UUID masterId, UUID masterServiceId, String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, ?, NULL, 'COMPLETED', NOW() - interval '5 hours', "
                        + "NOW() - interval '4 hours', 500.00, 60, 0, 'LINK', ?, ?, ?, NOW(), NOW())",
                id, masterId, masterServiceId, GUEST_NAME, GUEST_SURNAME, phone);
        return id;
    }

    /** A COMPLETED LINK visit header (no account), guest phone {@code phone}. */
    public UUID guestAppointment(String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO appointments (id, status, booking_source, guest_name, guest_surname, guest_phone, "
                        + "created_at, updated_at) VALUES (?, 'COMPLETED', 'LINK', ?, ?, ?, NOW(), NOW())",
                id, GUEST_NAME, GUEST_SURNAME, phone);
        return id;
    }

    /** A review row written WITHOUT the phase-399 columns — the shape every pre-V212 writer produces. */
    public UUID legacyReview(UUID bookingId, UUID clientId, UUID masterId) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, comment, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, NULL, 5, 'Чудово', NOW(), NOW())",
                id, bookingId, clientId, masterId);
        return id;
    }

    /** A review row with an explicit {@code source} / {@code guest_display_name} (V212 columns). */
    public UUID review(UUID bookingId, UUID clientId, UUID masterId, String source, String guestDisplayName) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, comment, source, "
                        + "guest_display_name, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 4, 'Добре', ?, ?, NOW(), NOW())",
                id, bookingId, clientId, masterId, source, guestDisplayName);
        return id;
    }
}
