package com.beautica.user;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Shared raw-SQL fixture helpers for the Phase 300 CLIENT self-deletion integration-test family
 * ({@code ClientAccountHardDeleteIT}, {@code ClientDetachCoherenceIT},
 * {@code ClientAccountDeleteFkCompletenessIT}, {@code DetachedReviewAuthorRenderIT},
 * {@code DetachedClientBookingRenderIT}) — extracted up front (backend-qa playbook Q4) because all
 * five classes need the identical salon+master+booking graph {@code SalonStaffHardDeleteIT}
 * already established as this codebase's house convention for raw-SQL integration fixtures.
 * Login/token helpers deliberately stay on {@link com.beautica.booking.BookingTestFixtures}
 * ({@code tokenFor}/{@code bearerHeaders}) — REUSE-FIRST, no duplicate login logic here.
 */
public class ClientSelfDeleteTestFixtures {

    private static final String TEST_PASSWORD = "Str0ngP@ss1!";

    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;

    public ClientSelfDeleteTestFixtures(JdbcTemplate jdbcTemplate, PasswordEncoder passwordEncoder) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
    }

    public record Salon(UUID ownerId, UUID salonId, UUID masterUserId, UUID masterId,
                         UUID serviceDefId, UUID masterServiceId) {
    }

    private UUID testCityId() {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM cities WHERE name_uk = 'Вінниця' LIMIT 1", UUID.class);
    }

    public UUID createUser(String email, String role, UUID salonId, String firstName, String lastName) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, "
                        + "email_verified, first_name, last_name) "
                        + "VALUES (?, ?, ?, ?, ?, true, true, ?, ?)",
                id, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId, firstName, lastName);
        return id;
    }

    /** Deliberately named, distinctive first/last name — never a fixture value the sentinel could
     * accidentally collide with (Anti-Bug §fixture-values-can-defang-assertions). */
    public UUID createClient() {
        return createUser("csd-client-" + System.nanoTime() + "@beautica.test", "CLIENT", null,
                "Оксана", "Іванова");
    }

    public UUID resolveUnusedServiceTypeId(String ownerType, UUID ownerId) {
        return jdbcTemplate.queryForObject(
                """
                SELECT st.id FROM service_types st
                JOIN platform_categories pc ON pc.name = st.platform_category_name
                WHERE st.is_active = TRUE AND pc.active = TRUE AND pc.status = 'APPROVED'
                  AND NOT EXISTS (SELECT 1 FROM service_definitions sd
                                  WHERE sd.owner_type = ? AND sd.owner_id = ?
                                    AND sd.service_type_id = st.id AND sd.is_active = TRUE)
                ORDER BY st.name_uk LIMIT 1
                """,
                UUID.class, ownerType, ownerId);
    }

    public Salon createSalon() {
        UUID ownerId = createUser("csd-owner-" + System.nanoTime() + "@beautica.test", "SALON_OWNER", null,
                "Тест", "Власник");
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, testCityId());

        UUID masterUserId = createUser(
                "csd-master-" + System.nanoTime() + "@beautica.test", "SALON_MASTER", salonId,
                "Тест", "Майстер");
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())",
                masterId, masterUserId, salonId);

        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, 'SALON', ?, 'Test Service', ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, salonId, resolveUnusedServiceTypeId("SALON", salonId));

        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);

        return new Salon(ownerId, salonId, masterUserId, masterId, serviceDefId, masterServiceId);
    }

    public UUID insertBooking(UUID clientId, Salon salon, String status, OffsetDateTime startsAt) {
        return insertBooking(clientId, salon, status, startsAt, null);
    }

    public UUID insertBooking(UUID clientId, Salon salon, String status, OffsetDateTime startsAt,
                               UUID appointmentId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, "
                        + "appointment_id, status, starts_at, ends_at, price_at_booking, "
                        + "duration_minutes_at_booking, buffer_minutes_at_booking, booking_source, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, salon.masterId(), salon.masterServiceId(), salon.salonId(),
                appointmentId, status, startsAt, startsAt.plusMinutes(60));
        return bookingId;
    }

    public UUID insertAppointmentHeader(UUID clientId, UUID salonId, String status) {
        UUID appointmentId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO appointments (id, client_id, salon_id, status, booking_source, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, 'APP', NOW(), NOW())",
                appointmentId, clientId, salonId, status);
        return appointmentId;
    }

    public void insertReview(UUID bookingId, UUID clientId, UUID masterId, UUID salonId, int rating,
                              String comment) {
        jdbcTemplate.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, comment, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), bookingId, clientId, masterId, salonId, rating, comment);
    }

    public void insertClientReview(UUID bookingId, UUID subjectClientId, UUID authorMasterId, UUID salonId,
                                    int rating) {
        jdbcTemplate.update(
                "INSERT INTO client_reviews (id, booking_id, subject_client_id, author_master_id, "
                        + "salon_id, rating, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), bookingId, subjectClientId, authorMasterId, salonId, rating);
    }

    public void insertFavorite(UUID clientId, String targetType, UUID targetId) {
        jdbcTemplate.update(
                "INSERT INTO favorites (id, client_id, target_type, target_id, created_at) "
                        + "VALUES (?, ?, ?, ?, NOW())",
                UUID.randomUUID(), clientId, targetType, targetId);
    }

    public void insertRefreshToken(UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO refresh_tokens (id, token, user_id, expires_at, is_revoked, family_id, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, NOW() + interval '30 days', false, ?, NOW(), NOW())",
                UUID.randomUUID(), "rt-" + UUID.randomUUID(), userId, UUID.randomUUID());
    }

    public void insertDeviceToken(UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO device_tokens (id, user_id, token, platform, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'ANDROID', true, NOW(), NOW())",
                UUID.randomUUID(), userId, "dt-" + UUID.randomUUID());
    }

    public void insertMediaFile(UUID uploaderId) {
        UUID mediaId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO media_files (id, uploader_id, entity_type, entity_id, media_type, "
                        + "r2_key, r2_url, created_at, updated_at) "
                        + "VALUES (?, ?, 'USER', ?, 'AVATAR', ?, ?, NOW(), NOW())",
                mediaId, uploaderId, uploaderId, "avatars/" + mediaId,
                "https://pub.example.r2.dev/avatars/" + mediaId);
    }

    public void insertPasswordResetTicket(UUID userId) {
        jdbcTemplate.update(
                "INSERT INTO password_reset_tickets (id, ticket_hash, user_id, expires_at, is_used, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, NOW() + interval '1 hour', false, NOW(), NOW())",
                UUID.randomUUID(), "prt-" + UUID.randomUUID(), userId);
    }

    /** Returns the generated {@code platform_categories.id} (BIGSERIAL). */
    public long insertPlatformCategoryRequest(UUID requestedByUserId) {
        String name = "CSDTESTCAT" + System.nanoTime();
        jdbcTemplate.update(
                "INSERT INTO platform_categories (name, display_name, status, requested_by_user_id, "
                        + "active, created_at) VALUES (?, ?, 'PENDING', ?, true, NOW())",
                name, name, requestedByUserId);
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM platform_categories WHERE name = ?", Long.class, name);
        return id == null ? -1 : id;
    }

    public UUID insertServiceTypeSuggestion(UUID requestedByUserId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_type_suggestion (id, requested_by_user_id, category_name, "
                        + "suggested_name, status, created_at) "
                        + "VALUES (?, ?, 'OTHER', ?, 'PENDING', NOW())",
                id, requestedByUserId, "Suggestion-" + id);
        return id;
    }

    public boolean userExists(UUID userId) {
        return count("SELECT COUNT(*) FROM users WHERE id = ?", userId) == 1;
    }

    public boolean bookingExists(UUID bookingId) {
        return count("SELECT COUNT(*) FROM bookings WHERE id = ?", bookingId) == 1;
    }

    public boolean appointmentExists(UUID appointmentId) {
        return count("SELECT COUNT(*) FROM appointments WHERE id = ?", appointmentId) == 1;
    }

    public int count(String sql, Object arg) {
        Integer value = jdbcTemplate.queryForObject(sql, Integer.class, arg);
        return value == null ? -1 : value;
    }
}
