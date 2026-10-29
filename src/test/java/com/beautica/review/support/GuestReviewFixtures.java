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

    /**
     * Phase 400 — a LINK visit header in {@code status} with guest phone {@code phone} and accepted privacy-policy
     * version {@code policyVersion} ({@code null} = a pre-381 visit with no consent stamp).
     */
    public UUID linkVisit(String status, String phone, String policyVersion) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO appointments (id, status, booking_source, guest_name, guest_surname, guest_phone, "
                        + "cancel_token_hash, guest_privacy_accepted_at, guest_privacy_policy_version, created_at, "
                        + "updated_at) VALUES (?, ?, 'LINK', ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                id, status, GUEST_NAME, GUEST_SURNAME, phone, randomHex64(),
                policyVersion == null ? null : java.sql.Timestamp.from(java.time.Instant.now()), policyVersion);
        return id;
    }

    /** Phase 400 — a STAFF walk-in visit header for an unregistered guest (phone kept, no cancel link). */
    public UUID staffGuestVisit(String status, String phone) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO appointments (id, status, booking_source, guest_name, guest_surname, guest_phone, "
                        + "created_at, updated_at) VALUES (?, ?, 'STAFF', ?, ?, ?, NOW(), NOW())",
                id, status, GUEST_NAME, GUEST_SURNAME, phone);
        return id;
    }

    /**
     * Phase 400 — one item of a guest visit ({@code source} LINK or STAFF, mirroring its header): {@code hoursAgo}
     * hours in the past, 60 minutes long, so neither the overlap EXCLUDE nor a temporal guard gets in the way.
     */
    public UUID visitItem(UUID appointmentId, UUID masterId, UUID masterServiceId, String source, String status,
                          String phone, int hoursAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, starts_at, "
                        + "ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, cancel_token_hash, appointment_id, "
                        + "created_at, updated_at) "
                        + "VALUES (?, NULL, ?, ?, NULL, ?, NOW() - make_interval(hours => ?), "
                        + "NOW() - make_interval(hours => ?) + interval '60 minutes', 500.00, 60, 0, ?, ?, ?, ?, ?, ?, "
                        + "NOW(), NOW())",
                id, masterId, masterServiceId, status, hoursAgo, hoursAgo, source, GUEST_NAME, GUEST_SURNAME, phone,
                "LINK".equals(source) ? randomHex64() : null, appointmentId);
        return id;
    }

    /**
     * Phase 400 D7 — a single LINK booking with NO visit header ({@code appointment_id} NULL), the shape
     * {@code GuestBookingService#createSingleServiceBooking} persists: {@code hoursAgo} hours in the past, 60 minutes
     * long, cancel-token hash set, privacy-policy stamp {@code policyVersion} ({@code null} = none).
     */
    public UUID singleLinkBooking(UUID masterId, UUID masterServiceId, String status, String phone,
                                  String policyVersion, int hoursAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, starts_at, "
                        + "ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_surname, guest_phone, cancel_token_hash, "
                        + "guest_privacy_accepted_at, guest_privacy_policy_version, created_at, updated_at) "
                        + "VALUES (?, NULL, ?, ?, NULL, ?, NOW() - make_interval(hours => ?), "
                        + "NOW() - make_interval(hours => ?) + interval '60 minutes', 500.00, 60, 0, 'LINK', ?, ?, ?, ?, "
                        + "?, ?, NOW(), NOW())",
                id, masterId, masterServiceId, status, hoursAgo, hoursAgo, GUEST_NAME, GUEST_SURNAME, phone,
                randomHex64(), policyVersion == null ? null : java.sql.Timestamp.from(java.time.Instant.now()),
                policyVersion);
        return id;
    }

    /** Phase 400 D7 — a single APP or STAFF booking of the registered {@code clientId}, no visit header. */
    public UUID singleClientBooking(UUID clientId, UUID masterId, UUID masterServiceId, String source, String status,
                                    int hoursAgo) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, starts_at, "
                        + "ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, ?, NOW() - make_interval(hours => ?), "
                        + "NOW() - make_interval(hours => ?) + interval '60 minutes', 500.00, 60, 0, ?, NOW(), NOW())",
                id, clientId, masterId, masterServiceId, status, hoursAgo, hoursAgo, source);
        return id;
    }

    /** Phase 400 D7 — the retention job's guest-PII erasure, applied to one LINK booking (V199/V206 shape). */
    public void anonymiseBooking(UUID bookingId) {
        jdbc.update("UPDATE bookings SET guest_pii_anonymised_at = NOW(), guest_surname = NULL, guest_phone = NULL, "
                + "cancel_token_hash = NULL, client_comment = NULL, client_cancellation_note = NULL WHERE id = ?",
                bookingId);
    }

    private static String randomHex64() {
        return java.util.HexFormat.of().formatHex(java.nio.ByteBuffer.allocate(32)
                .putLong(UUID.randomUUID().getMostSignificantBits())
                .putLong(UUID.randomUUID().getLeastSignificantBits())
                .putLong(UUID.randomUUID().getMostSignificantBits())
                .putLong(UUID.randomUUID().getLeastSignificantBits())
                .array());
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
