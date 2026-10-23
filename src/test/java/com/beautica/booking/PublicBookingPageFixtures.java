package com.beautica.booking;

import com.beautica.auth.dto.AuthResponse;
import com.beautica.auth.dto.LoginRequest;
import com.beautica.common.ApiResponse;
import com.beautica.service.ServiceTestFixtures;
import com.beautica.support.BookableMasterSeeder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared master / salon fixtures of the public booking-page ITs ({@link PublicMasterSlugInfoEnrichedIT},
 * {@link PublicSalonBookingInfoIT}, {@link PublicBookingInfoHttpContractIT}), extracted when the third copy
 * appeared (QA playbook: extract on the third repetition). One place defines what a page-ready independent
 * master, a salon with its slug and a stored-slug / legacy salon master look like.
 *
 * <p>Every master carries a seeded phone number and Instagram handle ({@link #SEEDED_PHONE},
 * {@link #SEEDED_INSTAGRAM}) so the no-PII assertions of any IT have something real to look for.
 */
final class PublicBookingPageFixtures {

    static final String PASSWORD = "Str0ngP@ss1!";
    static final String SEEDED_PHONE = "+380671119999";
    static final String SEEDED_INSTAGRAM = "secret_insta_handle";

    /** {@code serviceDefId} is {@code null} for a master built without services. */
    record IndependentMaster(UUID userId, UUID masterId, String slug, String email, UUID serviceDefId) {
    }

    record SalonPage(String ownerToken, UUID salonId, String slug) {
    }

    private final JdbcTemplate jdbc;
    private final TestRestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final ServiceTestFixtures fixtures;

    PublicBookingPageFixtures(JdbcTemplate jdbc, TestRestTemplate restTemplate, ObjectMapper objectMapper,
                              PasswordEncoder passwordEncoder) {
        this.jdbc = jdbc;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
        this.fixtures = new ServiceTestFixtures(restTemplate, jdbc, objectMapper, passwordEncoder);
    }

    /** An active INDEPENDENT_MASTER with a stored slug; with a service and a usable schedule when {@code bookable}. */
    IndependentMaster independentMaster(boolean bookable) {
        String email = "pg-" + UUID.randomUUID() + "@beautica.test";
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, phone_number, bio, "
                        + "instagram, professional_title, city, region, avatar_url, street, building_no, location_note, "
                        + "is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'INDEPENDENT_MASTER', 'Олена', 'Коваль', ?, 'Манікюр 8 років', ?, "
                        + "'Майстер манікюру', 'Львів', 'Львівська область', 'https://cdn.example/a.webp', "
                        + "'вул. Хрещатик', '22', '2 поверх', true, true)",
                userId, email, passwordEncoder.encode(PASSWORD), SEEDED_PHONE, SEEDED_INSTAGRAM);
        UUID masterId = UUID.randomUUID();
        String slug = "olena-k-" + masterId.toString().substring(0, 8);
        jdbc.update(
                "INSERT INTO masters (id, user_id, salon_id, master_type, avg_rating, review_count, is_active, "
                        + "booking_slug, created_at, updated_at) "
                        + "VALUES (?, ?, NULL, 'INDEPENDENT_MASTER', 0, 0, true, ?, NOW(), NOW())",
                masterId, userId, slug);
        UUID defId = null;
        if (bookable) {
            defId = BookableMasterSeeder.assignNewIndependentService(jdbc, masterId);
            // The seeder leaves service_definitions.category NULL; real services carry the platform category key.
            jdbc.update("UPDATE service_definitions SET category = "
                    + "(SELECT platform_category_name FROM service_types WHERE id = service_type_id) WHERE id = ?", defId);
            BookableMasterSeeder.seedUsableSchedule(jdbc, masterId);
        }
        return new IndependentMaster(userId, masterId, slug, email, defId);
    }

    String tokenOf(IndependentMaster master) throws Exception {
        ResponseEntity<String> resp = restTemplate.postForEntity(
                "/api/v1/auth/login", new LoginRequest(master.email(), PASSWORD), String.class);
        assertThat(resp.getStatusCode()).as("login body=%s", resp.getBody()).isEqualTo(HttpStatus.OK);
        return objectMapper.readValue(resp.getBody(), new TypeReference<ApiResponse<AuthResponse>>() {})
                .data().accessToken();
    }

    UUID insertClient() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, email, password_hash, role, first_name, last_name, is_active, email_verified) "
                        + "VALUES (?, ?, ?, 'CLIENT', 'Ірина', 'Кравчук', true, true)",
                id, "pg-c-" + id + "@beautica.test", passwordEncoder.encode(PASSWORD));
        return id;
    }

    /** One COMPLETED booking plus its review, {@code minutesAgo} old; a fresh booking per review (UNIQUE). */
    void insertReview(IndependentMaster master, UUID clientId, int rating, int minutesAgo) {
        UUID masterServiceId = jdbc.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? LIMIT 1", UUID.class, master.masterId());
        UUID bookingId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, NULL, 'COMPLETED', NOW() - interval '2 hours', "
                        + "NOW() - interval '1 hour', 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, master.masterId(), masterServiceId);
        jdbc.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, comment, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, NULL, ?, ?, "
                        + "NOW() - make_interval(mins => ?), NOW())",
                UUID.randomUUID(), bookingId, clientId, master.masterId(), rating, "review-" + minutesAgo, minutesAgo);
        // What the production recalculation (RatingRecalculationService) maintains after a review: the persisted
        // counter the public /reviews route uses to recognise a page beyond the last one.
        jdbc.update("UPDATE masters SET review_count = (SELECT COUNT(*) FROM reviews WHERE master_id = ?), "
                + "avg_rating = COALESCE((SELECT ROUND(AVG(rating), 2) FROM reviews WHERE master_id = ?), 0) "
                + "WHERE id = ?", master.masterId(), master.masterId(), master.masterId());
    }

    /** One COMPLETED booking plus its SALON review for {@code masterId} (who must have a master_services row). */
    void insertSalonReview(UUID salonId, UUID masterId, UUID clientId, int rating, int minutesAgo) {
        UUID masterServiceId = jdbc.queryForObject(
                "SELECT id FROM master_services WHERE master_id = ? LIMIT 1", UUID.class, masterId);
        UUID bookingId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO bookings (id, client_id, master_id, master_service_id, salon_id, status, "
                        + "starts_at, ends_at, price_at_booking, duration_minutes_at_booking, "
                        + "buffer_minutes_at_booking, booking_source, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, 'COMPLETED', NOW() - interval '2 hours', "
                        + "NOW() - interval '1 hour', 500.00, 60, 0, 'APP', NOW(), NOW())",
                bookingId, clientId, masterId, masterServiceId, salonId);
        jdbc.update(
                "INSERT INTO reviews (id, booking_id, client_id, master_id, salon_id, rating, comment, "
                        + "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, NOW() - make_interval(mins => ?), NOW())",
                UUID.randomUUID(), bookingId, clientId, masterId, salonId, rating, "review-" + minutesAgo, minutesAgo);
        // As above, for the salon's persisted counter.
        jdbc.update("UPDATE salons SET review_count = (SELECT COUNT(*) FROM reviews WHERE salon_id = ?), "
                + "avg_rating = COALESCE((SELECT ROUND(AVG(rating), 2) FROM reviews WHERE salon_id = ?), 0) "
                + "WHERE id = ?", salonId, salonId, salonId);
    }

    /** Gives the salon definition behind a seeded assignment its category key (the seeder leaves it NULL). */
    void categorise(UUID serviceDefId) {
        jdbc.update("UPDATE service_definitions SET category = "
                + "(SELECT platform_category_name FROM service_types WHERE id = service_type_id) WHERE id = ?", serviceDefId);
    }

    /** A salon created through {@code POST /salons} (slug issued at creation, phase 376). */
    SalonPage salon(String name) throws Exception {
        String token = fixtures.createSalonOwnerAndGetToken("owner-pg-" + UUID.randomUUID() + "@beautica.test");
        UUID salonId = fixtures.createSalon(token, name);
        String slug = jdbc.queryForObject("SELECT booking_slug FROM salons WHERE id = ?", String.class, salonId);
        return new SalonPage(token, salonId, slug);
    }

    /** A second salon of the SAME owner (needed to rotate a master between salons). */
    SalonPage additionalSalon(SalonPage first, String name) throws Exception {
        UUID salonId = fixtures.createSalon(first.ownerToken(), name);
        String slug = jdbc.queryForObject("SELECT booking_slug FROM salons WHERE id = ?", String.class, salonId);
        return new SalonPage(first.ownerToken(), salonId, slug);
    }

    /** A salon master with a stored slug — the steady state for every master created since phase 13.1. */
    UUID salonMaster(UUID salonId, String firstName, String lastName) {
        UUID masterId = legacySalonMaster(salonId, firstName, lastName);
        jdbc.update("UPDATE masters SET booking_slug = ? WHERE id = ?",
                "m-" + masterId.toString().substring(0, 8) + "-pg", masterId);
        return masterId;
    }

    /** A pre-V86 LEGACY salon master: {@code booking_slug NULL}. */
    UUID legacySalonMaster(UUID salonId, String firstName, String lastName) {
        UUID masterId = fixtures.createSalonMaster(salonId);
        UUID userId = jdbc.queryForObject("SELECT user_id FROM masters WHERE id = ?", UUID.class, masterId);
        jdbc.update(
                "UPDATE users SET first_name = ?, last_name = ?, professional_title = 'Стиліст', "
                        + "avatar_url = 'https://cdn.beautica.test/a.jpg' WHERE id = ?",
                firstName, lastName, userId);
        return masterId;
    }

    String masterSlugOf(UUID masterId) {
        return jdbc.queryForObject("SELECT booking_slug FROM masters WHERE id = ?", String.class, masterId);
    }

    /** Assigns a NEW independent service to {@code masterId}; returns its {@code master_services.id}. */
    UUID assignService(UUID masterId) {
        UUID defId = BookableMasterSeeder.assignNewIndependentService(jdbc, masterId);
        return jdbc.queryForObject("SELECT id FROM master_services WHERE service_def_id = ?", UUID.class, defId);
    }

    /** One working interval on {@code day}'s ISO weekday in the given weekly template. */
    void addInterval(UUID scheduleId, LocalDate day, LocalTime start, LocalTime end) {
        jdbc.update(
                "INSERT INTO working_intervals (id, schedule_id, day_of_week, start_time, end_time) VALUES (?, ?, ?, ?, ?)",
                UUID.randomUUID(), scheduleId, day.getDayOfWeek().getValue(), start, end);
    }

    /** No Authorization header (permitAll route) from source IP {@code ip} via {@code X-Forwarded-For}. */
    ResponseEntity<String> anonymousGet(String url, String ip) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Forwarded-For", ip);
        return restTemplate.exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
    }

    /** A random private source IP, so the shared per-IP guest buckets never leak budget across tests. */
    static String randomIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(1, 255) + "." + r.nextInt(1, 255) + "." + r.nextInt(1, 255);
    }
}
