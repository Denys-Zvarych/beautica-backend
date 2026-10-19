package com.beautica.booking.retention;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JDBC fixtures shared by the phase-373 guest-PII ITs. Raw SQL on purpose: the tests need rows
 * months/years in the past and in every status, which no API path can create.
 */
final class GuestPiiFixtures {

    static final String PHONE = "+380501234567";
    static final String PHONE_LOCAL = "0501234567";
    static final String OTHER_PHONE = "+380671112233";
    static final String GUEST_NAME = "Олена";
    static final String GUEST_SURNAME = "Шевчук";

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode("test-password");

    private final JdbcTemplate jdbc;

    GuestPiiFixtures(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    record Provider(UUID ownerUserId, String ownerEmail, UUID masterId, UUID masterServiceId) {
    }

    Provider createIndependentMaster() {
        UUID ownerId = UUID.randomUUID();
        String email = "pii-owner-" + System.nanoTime() + "@beautica.test";
        jdbc.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', true, true, 'Марія', 'Левченко')",
                ownerId, email, PASSWORD_HASH);
        UUID masterId = UUID.randomUUID();
        jdbc.update("INSERT INTO masters (id, user_id, master_type, is_active, booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, ?, NOW(), NOW())",
                masterId, ownerId, "pii-master-" + Long.toString(System.nanoTime(), 36));
        UUID serviceTypeId = jdbc.queryForObject(
                "SELECT st.id FROM service_types st "
                        + "JOIN platform_categories pc ON pc.name = st.platform_category_name "
                        + "WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED' "
                        + "ORDER BY st.name_uk LIMIT 1", UUID.class);
        UUID serviceDefId = UUID.randomUUID();
        jdbc.update("INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'INDEPENDENT_MASTER', ?, 'Манікюр', ?, 60, 350.00, 0, true, NOW(), NOW())",
                serviceDefId, ownerId, serviceTypeId);
        UUID masterServiceId = UUID.randomUUID();
        jdbc.update("INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())", masterServiceId, masterId, serviceDefId);
        return new Provider(ownerId, email, masterId, masterServiceId);
    }

    UUID createClient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, password_hash, role, is_active, email_verified, first_name, last_name) "
                        + "VALUES (?, ?, ?, 'CLIENT', true, true, 'Ірина', 'Коваль')",
                id, "pii-client-" + System.nanoTime() + "@beautica.test", PASSWORD_HASH);
        return id;
    }

    /** A guest (LINK) single booking ending at {@code endsAt} (60-minute visit). */
    UUID link(Provider p, String status, OffsetDateTime endsAt, String phone, UUID cancelToken) {
        return insert(p, "LINK", status, endsAt, null, phone, GUEST_NAME, GUEST_SURNAME, cancelToken, null);
    }

    UUID staffWalkIn(Provider p, String status, OffsetDateTime endsAt, String phone) {
        return insert(p, "STAFF", status, endsAt, null, phone, GUEST_NAME, GUEST_SURNAME, null, null);
    }

    UUID staffRegistered(Provider p, String status, OffsetDateTime endsAt, UUID clientId) {
        return insert(p, "STAFF", status, endsAt, clientId, null, null, null, null, null);
    }

    UUID app(Provider p, String status, OffsetDateTime endsAt, UUID clientId) {
        return insert(p, "APP", status, endsAt, clientId, null, null, null, null, null);
    }

    UUID visitItem(Provider p, UUID appointmentId, String source, String status, OffsetDateTime endsAt,
                   String phone) {
        UUID token = "LINK".equals(source) && "CONFIRMED".equals(status) ? UUID.randomUUID() : null;
        return insert(p, source, status, endsAt, null, phone, GUEST_NAME, GUEST_SURNAME, token, appointmentId);
    }

    UUID appointment(String source, String status, String phone, UUID cancelToken, UUID createdBy) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO appointments (id, status, booking_source, guest_name, guest_surname, guest_phone, "
                        + "cancel_token, client_comment, created_by_user_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, 'Зателефонуйте 0501234567', ?, NOW(), NOW())",
                id, status, source, GUEST_NAME, GUEST_SURNAME, phone, cancelToken, createdBy);
        return id;
    }

    private UUID insert(Provider p, String source, String status, OffsetDateTime endsAt, UUID clientId,
                        String phone, String name, String surname, UUID cancelToken, UUID appointmentId) {
        UUID id = UUID.randomUUID();
        boolean cancelled = "CANCELLED".equals(status);
        boolean providerTerminated = "DECLINED".equals(status) || "NOT_COMPLETED".equals(status);
        UUID createdBy = "STAFF".equals(source) ? p.ownerUserId() : null;
        jdbc.update("INSERT INTO bookings (id, master_id, master_service_id, client_id, status, starts_at, ends_at, "
                        + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                        + "guest_name, guest_surname, guest_phone, cancel_token, reminder_sent, client_comment, "
                        + "client_cancellation_note, provider_comment, appointment_id, created_by_user_id, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 60, 0, ?, ?, ?, ?, ?, false, ?, ?, ?, ?, ?, NOW(), NOW())",
                id, p.masterId(), p.masterServiceId(), clientId, status,
                endsAt.minusMinutes(60), endsAt, new BigDecimal("350.00"), source,
                name, surname, phone, cancelToken,
                "Мій номер " + (phone == null ? "немає" : phone),
                cancelled ? "Не встигаю, " + GUEST_NAME : null,
                providerTerminated ? "Клієнт не прийшов" : null,
                appointmentId, createdBy);
        return id;
    }

    /** Inserts {@code count} COMPLETED LINK rows ending before {@code latestEnd}, one hour apart. */
    List<UUID> bulkCompletedLink(Provider p, int count, OffsetDateTime latestEnd) {
        List<Object[]> args = new ArrayList<>(count);
        List<UUID> ids = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            OffsetDateTime end = latestEnd.minusHours(i);
            args.add(new Object[]{id, p.masterId(), p.masterServiceId(),
                    Timestamp.from(end.minusMinutes(60).toInstant()), Timestamp.from(end.toInstant()), PHONE});
        }
        jdbc.batchUpdate("INSERT INTO bookings (id, master_id, master_service_id, status, starts_at, ends_at, "
                + "price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                + "guest_name, guest_phone, reminder_sent, created_at, updated_at) "
                + "VALUES (?, ?, ?, 'COMPLETED', ?, ?, 350.00, 60, 0, 'LINK', 'Олена', ?, false, NOW(), NOW())", args);
        return ids;
    }

    /**
     * Set-based EXPLAIN volume: {@code headers} CANCELLED LINK visit headers, then {@code items}
     * COMPLETED LINK bookings (same shape as {@link #bulkCompletedLink}) each linked to its own
     * header, ending one hour apart before {@code latestEnd}. Two statements instead of per-row
     * batches keeps seeding well inside the statement timeout on CI.
     */
    List<UUID> bulkLinkedVisits(Provider p, int headers, int items, OffsetDateTime latestEnd) {
        jdbc.update("INSERT INTO appointments (id, status, booking_source, guest_name, guest_phone, "
                + "created_at, updated_at) "
                + "SELECT gen_random_uuid(), 'CANCELLED', 'LINK', 'Олена', ?, NOW(), NOW() "
                + "FROM generate_series(1, ?)", PHONE, headers);
        Timestamp end = Timestamp.from(latestEnd.toInstant());
        return jdbc.queryForList("INSERT INTO bookings (id, master_id, master_service_id, status, starts_at, "
                        + "ends_at, price_at_booking, duration_minutes_at_booking, buffer_minutes_at_booking, "
                        + "booking_source, guest_name, guest_phone, reminder_sent, appointment_id, "
                        + "created_at, updated_at) "
                        + "SELECT gen_random_uuid(), ?, ?, 'COMPLETED', "
                        + "?::timestamptz - make_interval(hours => h.i) - interval '60 minutes', "
                        + "?::timestamptz - make_interval(hours => h.i), 350.00, 60, 0, 'LINK', 'Олена', ?, "
                        + "false, h.id, NOW(), NOW() "
                        + "FROM (SELECT id, (row_number() OVER ())::int - 1 AS i FROM appointments LIMIT ?) h "
                        + "RETURNING id",
                UUID.class, p.masterId(), p.masterServiceId(), end, end, PHONE, items);
    }

    Map<String, Object> booking(UUID id) {
        return jdbc.queryForMap("SELECT * FROM bookings WHERE id = ?", id);
    }

    Map<String, Object> appointmentRow(UUID id) {
        return jdbc.queryForMap("SELECT * FROM appointments WHERE id = ?", id);
    }

    void insertOtp(String phone) {
        jdbc.update("INSERT INTO phone_otps (phone, code_hash, expires_at, used, attempts, created_at) "
                + "VALUES (?, 'abc', NOW() + INTERVAL '5 minutes', false, 0, NOW())", phone);
    }

    int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }
}
