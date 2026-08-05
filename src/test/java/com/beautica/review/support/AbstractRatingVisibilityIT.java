package com.beautica.review.support;

import com.beautica.AbstractIntegrationTest;
import com.beautica.config.TestSecurityConfig;
import com.beautica.notification.service.NotificationOutboxService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

/**
 * Shared seeding base for the Phase 240 rating-visibility integration tests.
 *
 * <p>Exists to stop a Q4 duplication: {@code ReviewCacheEvictionIT} and
 * {@code ReviewVisibilityAfterElapsedConfirmedIT} need byte-identical provider/booking fixtures
 * (the elapsed-CONFIRMED shape from the bug report, seeded at the DB's default rating state) and
 * would otherwise carry two verbatim copies of five helpers. Consolidating the
 * {@code @Import}/{@code @MockBean} declarations here also collapses both classes onto one
 * cached Spring context instead of two.
 *
 * <p>Every seeded provider starts at {@code avg_rating = 0.00, review_count = 0} — the literal
 * V4 column defaults. That is deliberate: these tests exist to prove a rating MOVES, so the
 * starting state must be the real unreviewed one, not a convenient pre-populated value.
 *
 * <p>Passwords are hashed with the production {@link PasswordEncoder} bean (§M — never a fake
 * {@code '$2a$10$…'} literal), which {@link TestSecurityConfig} lowers to BCrypt cost 4.
 */
@Import(TestSecurityConfig.class)
public abstract class AbstractRatingVisibilityIT extends AbstractIntegrationTest {

    protected static final String TEST_PASSWORD = "Str0ngP@ss1!";
    protected static final String SERVICE_NAME = "Test Service";

    @Autowired
    protected PasswordEncoder passwordEncoder;

    /**
     * Review creation publishes no outbox notification today, but the bean is mocked so a future
     * notification on this path cannot turn these rating assertions into a mail/encryption test.
     */
    @MockBean
    protected NotificationOutboxService notificationOutboxService;

    /**
     * Seeds an email-verified provider-side user.
     *
     * @param role     {@code INDEPENDENT_MASTER}, {@code SALON_MASTER} or {@code SALON_OWNER}
     * @param salonId  the employing salon, or {@code null} for an independent provider
     */
    protected UUID seedProviderUser(String email, String role, UUID salonId) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "salon_id, is_active, email_verified) "
                        + "VALUES (?, ?, ?, ?, 'Olena', 'Koval', ?, true, true)",
                userId, email, passwordEncoder.encode(TEST_PASSWORD), role, salonId);
        return userId;
    }

    protected UUID seedClientUser(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, "
                        + "is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', 'Ivan', 'Client', true, true)",
                userId, email, passwordEncoder.encode(TEST_PASSWORD));
        return userId;
    }

    /** Seeds a salon at the V4 default rating state (0.00 / 0). */
    protected UUID seedSalon(UUID ownerId) {
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, avg_rating, review_count, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, 0.00, 0, true, NOW(), NOW())",
                salonId, ownerId, "Salon-" + ownerId);
        return salonId;
    }

    /** Seeds a {@code masters} row at the V4 default rating state (0.00 / 0). */
    protected UUID seedMaster(UUID userId, UUID salonId, String masterType) {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, "
                        + "is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 0.00, 0, true, NOW(), NOW())",
                masterId, userId, salonId, masterType);
        return masterId;
    }

    /**
     * Seeds a service definition plus the master's active assignment to it, returning the
     * {@code master_services.id} the booking row needs.
     *
     * @param ownerType {@code SALON} or {@code INDEPENDENT_MASTER} — must match {@code ownerId}
     * @param ownerId   the salon id, or the independent master's USER id
     */
    protected UUID seedService(String ownerType, UUID ownerId, UUID masterId) {
        UUID serviceDefId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, "
                        + "created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 60, 500.00, 0, true, NOW(), NOW())",
                serviceDefId, ownerType, ownerId, SERVICE_NAME,
                resolveUnusedServiceTypeId(ownerType, ownerId));
        UUID masterServiceId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, "
                        + "created_at, updated_at) VALUES (?, ?, ?, true, NOW(), NOW())",
                masterServiceId, masterId, serviceDefId);
        return masterServiceId;
    }

    /**
     * The literal reported shape: a booking the provider never closed, whose window has already
     * elapsed. {@code CONFIRMED} + a past {@code ends_at} is reviewable — see
     * {@code ReviewIntegrationTest#should_createReview_when_confirmedBookingHasElapsed}.
     *
     * @param salonId the owning salon, or {@code null} for an independent-master booking (which
     *                is what suppresses the listener's salon branch)
     */
    protected UUID seedElapsedConfirmedBooking(
            UUID clientId, UUID masterId, UUID masterServiceId, UUID salonId) {
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, "
                        + "status, starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'CONFIRMED', NOW() - interval '2 hours', "
                        + "NOW() - interval '1 hour', 500.00, 60, 0, NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId);
        return bookingId;
    }

    protected UUID resolveUserIdByEmail(String email) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE email = ?", UUID.class, email);
    }
}
