package com.beautica.client.provider;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.common.security.PhoneHasher;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared raw-SQL seeding + login helpers for the provider-client integration tests ({@link ProviderClientControllerIT}
 * B507, {@link ProviderClientCardIT} B508). Extracted per backend-qa's B508 audit: the two ITs carried byte-identical
 * copies of these helpers.
 *
 * <p>Follows the {@code ServiceTestFixtures} / {@code BookingTestFixtures} convention: a package-private,
 * constructor-injected plain class instantiated per test in {@code @BeforeEach}, never a base class, so each IT keeps
 * extending {@code AbstractIntegrationTest} directly. The two protected lookups of that base class
 * ({@code testCityId}, {@code resolveUnusedServiceTypeId}) arrive as method references. Per-instance state
 * ({@code serviceByMaster}, {@code dayCounter}) therefore resets with every test, as it did when the helpers were
 * private IT members.
 */
final class ProviderClientFixtures {

    static final String PASSWORD = "Str0ngP@ss1!";

    private static String encodedPassword;

    /** 7-digit subscriber numbers, JVM-wide, so two {@link #freshPhone} calls never return the same number. */
    private static final AtomicInteger PHONE_SEQ =
            new AtomicInteger(1_000_000 + ThreadLocalRandom.current().nextInt(8_000_000));

    record Salon(UUID salonId, UUID ownerId, String ownerEmail, UUID masterId, UUID masterUserId,
                 String masterEmail) {}

    record Independent(UUID userId, UUID masterId, String email) {}

    private final JdbcTemplate jdbcTemplate;
    private final TestRestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final PhoneHasher phoneHasher;
    private final Supplier<UUID> cityId;
    private final BiFunction<String, UUID, UUID> unusedServiceTypeId;

    private final Map<UUID, UUID> serviceByMaster = new HashMap<>();
    private int dayCounter;

    ProviderClientFixtures(JdbcTemplate jdbcTemplate, TestRestTemplate restTemplate, ObjectMapper objectMapper,
                           PhoneHasher phoneHasher, Supplier<UUID> cityId,
                           BiFunction<String, UUID, UUID> unusedServiceTypeId) {
        this.jdbcTemplate = jdbcTemplate;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.phoneHasher = phoneHasher;
        this.cityId = cityId;
        this.unusedServiceTypeId = unusedServiceTypeId;
    }

    // ── seeding ──────────────────────────────────────────────────────────────

    /**
     * A fresh {@code +380<operator>xxxxxxx} per call so verified phones never collide with
     * {@code ux_users_verified_phone}. {@code operator} is the 2-digit code (e.g. {@code "63"}, {@code "67"}).
     */
    static String freshPhone(String operator) {
        return "+380" + operator + PHONE_SEQ.getAndIncrement();
    }

    String email(String prefix) {
        return "pc-" + prefix + "-" + UUID.randomUUID() + "@beautica.test";
    }

    UUID user(String email, String role, UUID salonId, String first, String last) {
        if (encodedPassword == null) {
            encodedPassword = new BCryptPasswordEncoder(4).encode(PASSWORD);
        }
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, role, salon_id, is_active, email_verified, first_name, "
                        + "last_name, created_at) VALUES (?, ?, ?, ?, ?, true, true, ?, ?, NOW() - interval '1 day')",
                id, email, encodedPassword, role, salonId, first, last);
        return id;
    }

    Salon salon() {
        String ownerEmail = email("owner");
        UUID ownerId = user(ownerEmail, "SALON_OWNER", null, "Власниця", "Салону");
        UUID salonId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO salons (id, owner_id, name, is_active, created_at, updated_at, city_id) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW(), ?)",
                salonId, ownerId, "Salon-" + salonId, cityId.get());
        String masterEmail = email("master");
        UUID masterUserId = user(masterEmail, "SALON_MASTER", salonId, "Майстер", "А");
        UUID masterId = salonMaster(masterUserId, salonId);
        return new Salon(salonId, ownerId, ownerEmail, masterId, masterUserId, masterEmail);
    }

    UUID salonMaster(UUID userId, UUID salonId) {
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, 'SALON_MASTER', true, NOW(), NOW())", masterId, userId, salonId);
        return masterId;
    }

    /** A second master of the salon; returns {masterId, login email}. */
    Object[] secondMaster(UUID salonId, String first) {
        String masterEmail = email("master2");
        UUID userId = user(masterEmail, "SALON_MASTER", salonId, first, "Б");
        return new Object[]{salonMaster(userId, salonId), masterEmail};
    }

    String admin(UUID salonId) {
        String adminEmail = email("admin");
        user(adminEmail, "SALON_ADMIN", salonId, "Адмін", "С");
        return adminEmail;
    }

    Independent independent() {
        String email = email("indep");
        UUID userId = user(email, "INDEPENDENT_MASTER", null, "Соло", "Майстер");
        UUID masterId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO masters (id, user_id, master_type, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, 'INDEPENDENT_MASTER', true, NOW(), NOW())", masterId, userId);
        return new Independent(userId, masterId, email);
    }

    UUID client(String first, String last) {
        return user(email("client"), "CLIENT", null, first, last);
    }

    /** A fresh service assignment on the master (a distinct service type each call). */
    UUID masterService(UUID masterId, String ownerType, UUID ownerId) {
        UUID defId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO service_definitions (id, owner_type, owner_id, name, service_type_id, "
                        + "base_duration_minutes, base_price, buffer_minutes_after, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 60, 500.00, 0, true, NOW(), NOW())",
                defId, ownerType, ownerId, "Послуга-" + defId.toString().substring(0, 8),
                unusedServiceTypeId.apply(ownerType, ownerId));
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO master_services (id, master_id, service_def_id, is_active, created_at, updated_at) "
                        + "VALUES (?, ?, ?, true, NOW(), NOW())", id, masterId, defId);
        return id;
    }

    UUID serviceOf(UUID masterId, UUID salonId) {
        return serviceByMaster.computeIfAbsent(masterId, id -> salonId != null
                ? masterService(id, "SALON", salonId)
                : masterService(id, "INDEPENDENT_MASTER", id));
    }

    /** A start time {@code daysFromNow} away, minute-unique per call so CONFIRMED rows never overlap. */
    Instant at(int daysFromNow) {
        return Instant.now().truncatedTo(ChronoUnit.MICROS).plus(daysFromNow, ChronoUnit.DAYS)
                .plus(dayCounter++, ChronoUnit.MINUTES);
    }

    /** Registered client's booking (source APP). */
    UUID booking(UUID masterId, UUID salonId, UUID clientId, String status, Instant startsAt) {
        return insertBooking(masterId, salonId, serviceOf(masterId, salonId), clientId, status, startsAt, null, "APP",
                null, null, null, false, false);
    }

    UUID bookingInVisit(UUID masterId, UUID salonId, UUID clientId, String status, Instant startsAt,
                        UUID appointmentId) {
        return insertBooking(masterId, salonId, masterService(masterId, salonId != null ? "SALON"
                : "INDEPENDENT_MASTER", salonId != null ? salonId : masterId), clientId, status, startsAt,
                appointmentId, "APP", null, null, null, false, false);
    }

    UUID guestBooking(UUID masterId, UUID salonId, String source, String phone, String name, String surname,
                      String status, Instant startsAt) {
        return insertBooking(masterId, salonId, serviceOf(masterId, salonId), null, status, startsAt, null, source,
                name, surname, phone, false, false);
    }

    /** A LINK-source guest booking. */
    UUID guestBooking(UUID masterId, UUID salonId, String phone, String name, String surname, String status,
                      Instant startsAt) {
        return guestBooking(masterId, salonId, "LINK", phone, name, surname, status, startsAt);
    }

    UUID anonymisedGuestBooking(UUID masterId, UUID salonId, Instant startsAt) {
        return insertBooking(masterId, salonId, serviceOf(masterId, salonId), null, "COMPLETED", startsAt, null,
                "LINK", "Гість", null, null, true, false);
    }

    UUID detachedBooking(UUID masterId, UUID salonId, Instant startsAt) {
        return insertBooking(masterId, salonId, serviceOf(masterId, salonId), null, "COMPLETED", startsAt, null,
                "APP", "Видалений", null, null, false, true);
    }

    UUID appointment(UUID clientId, UUID salonId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO appointments (id, client_id, salon_id, status, booking_source, created_at, "
                + "updated_at) VALUES (?, ?, ?, 'COMPLETED', 'APP', NOW(), NOW())", id, clientId, salonId);
        return id;
    }

    /** Not-detached overload: the card IT never seeds {@code client_detached_at}. */
    UUID insertBooking(UUID masterId, UUID salonId, UUID masterServiceId, UUID clientId, String status,
                       Instant startsAt, UUID appointmentId, String source, String guestName, String guestSurname,
                       String guestPhone, boolean anonymised) {
        return insertBooking(masterId, salonId, masterServiceId, clientId, status, startsAt, appointmentId, source,
                guestName, guestSurname, guestPhone, anonymised, false);
    }

    UUID insertBooking(UUID masterId, UUID salonId, UUID masterServiceId, UUID clientId, String status,
                       Instant startsAt, UUID appointmentId, String source, String guestName, String guestSurname,
                       String guestPhone, boolean anonymised, boolean detached) {
        UUID id = UUID.randomUUID();
        Timestamp start = Timestamp.from(startsAt);
        Timestamp end = Timestamp.from(startsAt.plus(30, ChronoUnit.MINUTES));
        String tokenHash = "LINK".equals(source) && guestPhone != null
                ? id.toString().replace("-", "").repeat(2) : null;
        jdbcTemplate.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, appointment_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, guest_name, guest_surname, guest_phone, "
                        + "cancel_token_hash, guest_pii_anonymised_at, client_detached_at, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 500.00, 30, 0, ?, ?, ?, ?, ?, "
                        + "CASE WHEN ? THEN NOW() END, CASE WHEN ? THEN NOW() END, NOW(), NOW())",
                id, clientId, masterId, masterServiceId, salonId, appointmentId, status, start, end, source,
                guestName, guestSurname, guestPhone, tokenHash, anonymised, detached);
        return id;
    }

    /**
     * Inserts one provider-to-client review row only. {@code users.avg_rating} / {@code review_count} are NOT touched:
     * callers that read them run the production {@code ClientReviewRepository#recalculateClientRating} afterwards.
     */
    void clientReview(UUID bookingId, UUID clientId, UUID authorMasterId, UUID salonId, int rating, String comment,
                      String moderation) {
        jdbcTemplate.update(
                "INSERT INTO client_reviews (id, booking_id, subject_client_id, author_master_id, salon_id, rating, "
                        + "comment, moderation_status, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                UUID.randomUUID(), bookingId, clientId, authorMasterId, salonId, rating, comment, moderation);
    }

    /** The service name a booking was made for (booking -> master_service -> service_definition). */
    String serviceNameOf(UUID bookingId) {
        return jdbcTemplate.queryForObject(
                "SELECT sd.name FROM bookings b JOIN master_services ms ON ms.id = b.master_service_id "
                        + "JOIN service_definitions sd ON sd.id = ms.service_def_id WHERE b.id = ?",
                String.class, bookingId);
    }

    /** The user id behind a login email. */
    UUID userIdOf(String email) {
        return jdbcTemplate.queryForObject("SELECT id FROM users WHERE email = ?", UUID.class, email);
    }

    void blockGuestInSalon(UUID salonId, String phone) {
        PhoneHasher.HashedPhone hashed = phoneHasher.hash(phone);
        jdbcTemplate.update("INSERT INTO client_blocks (id, blocker_salon_id, phone_hash, phone_last4, display_name) "
                + "VALUES (?, ?, ?, ?, 'Гість')", UUID.randomUUID(), salonId, hashed.hash(), hashed.last4());
    }

    void blockClientInSalon(UUID salonId, UUID userId) {
        jdbcTemplate.update("INSERT INTO client_blocks (id, blocker_salon_id, blocked_user_id, display_name) "
                + "VALUES (?, ?, ?, 'Клієнт')", UUID.randomUUID(), salonId, userId);
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    String token(String email) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(email, PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login %s", email).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    ResponseEntity<String> get(String token, String url, Object... vars) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class, vars);
    }
}
